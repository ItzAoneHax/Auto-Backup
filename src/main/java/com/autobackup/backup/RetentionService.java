package com.autobackup.backup;

import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.RemoteFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 云端保留策略(daily 按「日期文件夹/服务器文件夹」两级分层):
 * 1. daily 目录下每天一个 yyyy-MM-dd 日期文件夹, 其内每个备份源一个以源目录名命名的子目录;
 *    只保留近 N 天(默认 3 天): 到期的整个日期文件夹一次性删除(含当天全部服务器备份与运行日志),
 *    并兼容旧版扁平结构(按文件名内嵌日期逐个删除);
 * 2. 每月/每年第一次成功备份后, 把当次 daily 文件在云端复制一份到
 *    monthly/&lt;yyyy-MM&gt;/&lt;服务器名&gt;/ 与 yearly/&lt;yyyy&gt;/&lt;服务器名&gt;/, 永不清理
 *    (服务端复制, 无二次上传流量; 复制失败时回退为从本地上传);
 * 3. 每轮备份收尾时核对快照目录: daily 中有而 monthly/yearly 缺失的备份对象
 *    (如月中新加的备份源、快照曾复制中断)从 daily 云端复制补全;
 * 4. 名字无法解析为本程序生成的文件与目录一律不动.
 */
public class RetentionService {

    /** 文件名中形如 2026-08-23 的日期(旧版扁平备份与运行日志的文件名均内嵌运行日期). */
    static final Pattern EMBEDDED_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    /** 本程序生成的归档文件名: {服务器名}-{yyyy-MM-dd_HHmmss}[.inc].tar.(zst|gz)[.partNNN]. */
    private static final Pattern ARCHIVE_NAME =
            Pattern.compile("%s-\\d{4}-\\d{2}-\\d{2}_\\d{6}(\\.inc)?\\.tar\\.(zst|gz)(\\.part\\d{3})?");

    private final PanClient pan;
    private final AppConfig config;
    private final LogService log;

    public RetentionService(PanClient pan, AppConfig config, LogService log) {
        this.pan = pan;
        this.config = config;
        this.log = log;
    }

    /**
     * 清理 daily 目录中早于「今天-有效保留天数+1」的内容:
     * 新结构的整天日期文件夹直接删除, 旧版扁平文件按内嵌日期逐个删除, 其他一律不动.
     * 增量模式下有效保留天数为 max(保留天数, 全量间隔+2), 确保增量恢复链完整.
     */
    public void cleanExpiredDaily(String dailyDir) {
        List<RemoteFile> entries = pan.list(dailyDir);
        int effective = config.effectiveRetainDays();
        if (effective != config.retainDays()) {
            log.info("增量模式保留天数取 max(保留天数, 全量间隔+2) = " + effective);
        }
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(effective - 1L, 0));
        List<String> toDelete = new ArrayList<>();
        for (RemoteFile entry : entries) {
            LocalDate date = entry.dir() ? parseDayFolder(entry.name()) : parseEmbeddedDate(entry.name());
            if (date == null) continue;
            if (date.isBefore(cutoff)) toDelete.add(entry.path());
        }
        if (toDelete.isEmpty()) {
            log.info("云端清理: 没有过期内容 (保留近 " + effective + " 天)");
            return;
        }
        pan.delete(toDelete);
        for (String path : toDelete) {
            log.info("云端清理: 已删除过期备份 " + path);
        }
    }

    /**
     * 快照目录尚无任何文件(或不存在/不可读)时返回 true.
     * 用于月初/年初第一次备份前判断: 本期快照缺失则强制全量, 保证快照自包含可独立恢复.
     */
    public boolean snapshotPending(String snapshotDir) {
        try {
            return pan.list(snapshotDir).isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * 把自上次全量以来的完整恢复链(全量归档 + 其后各增量归档, 含分卷)云端复制进今天的服务器文件夹,
     * 使每天下载整个日期文件夹、按文件名顺序解压全部归档, 即可得到当日时刻的完整服务端
     * (增量只含变化文件, 后解压的覆盖先解压的同名文件).
     * 云端复制不产生上传流量; 保留窗口(全量间隔+2 天)已覆盖整条链.
     */
    public void mirrorChainIntoToday(String sourceName, String todayServerDir, LocalDate lastFullDate) {
        if (lastFullDate == null) return;
        try {
            Set<String> present = new HashSet<>();
            for (RemoteFile f : pan.list(todayServerDir)) {
                present.add(f.name());
            }
            boolean sawFull = false;
            List<RemoteFile> chain = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (LocalDate d = lastFullDate; !d.isAfter(today); d = d.plusDays(1)) {
                if (d.equals(today)) continue;   // 今日文件刚上传, 已在文件夹内
                List<RemoteFile> files;
                try {
                    files = pan.list(config.dailyDayDir(d) + "/" + sourceName);
                } catch (RuntimeException e) {
                    continue;   // 当日该源无备份(未运行或失败), 链上允许有缺口
                }
                for (RemoteFile f : files) {
                    if (!isSourceArchive(sourceName, f.name())) continue;
                    if (!f.name().contains(".inc.")) sawFull = true;
                    chain.add(f);
                }
            }
            if (!sawFull) {
                log.warn("恢复链不完整: 未找到 " + lastFullDate + " 以来的全量归档, 今日文件夹无法自包含");
                return;
            }
            int copied = 0;
            for (RemoteFile f : chain) {
                if (present.contains(f.name())) continue;
                pan.copy(f.path(), todayServerDir + "/" + f.name());
                present.add(f.name());
                copied++;
            }
            if (copied > 0) {
                log.info("今日文件夹已补齐完整恢复链(云端复制 " + copied + " 个归档, 零上传流量)");
            }
        } catch (Exception e) {
            log.warn("恢复链补齐失败: " + e.getMessage() + " (不影响备份结果, 明日运行会重试)");
        }
    }

    /** 文件名是否为该源由本程序生成的归档(全量或增量, 含分卷). */
    private static boolean isSourceArchive(String sourceName, String filename) {
        return Pattern.compile(ARCHIVE_NAME.pattern().formatted(Pattern.quote(sourceName)))
                .matcher(filename).matches();
    }

    /** 每月第一次成功备份后, 云端复制一份到 monthly/&lt;yyyy-MM&gt;/&lt;服务器名&gt;(永不清理). */
    public void ensureMonthlySnapshot(Uploader uploader, List<Path> localParts,
                                      List<String> dailyRemotePaths, String sourceName) {
        if (!config.monthlyEnabled()) return;
        ensureSnapshot(uploader, localParts, dailyRemotePaths,
                config.monthlyServerDir(sourceName), "月度");
    }

    /** 每年第一次成功备份后, 云端复制一份到 yearly/&lt;yyyy&gt;/&lt;服务器名&gt;(永不清理). */
    public void ensureYearlySnapshot(Uploader uploader, List<Path> localParts,
                                     List<String> dailyRemotePaths, String sourceName) {
        if (!config.yearlyEnabled()) return;
        ensureSnapshot(uploader, localParts, dailyRemotePaths,
                config.yearlyServerDir(sourceName), "年度");
    }

    /**
     * 备份收尾的快照补全: 对本轮成功上传的每个备份源, 检查其月度/年度快照目录,
     * 仍缺失时(如月中新加的备份源、快照复制曾中断)从 daily 云端复制补齐.
     * 此时本地分卷多已删除, 只做云端复制; 仍失败则留给下次运行重试.
     */
    public void backfillSnapshots(Map<String, List<String>> dailyFilesBySource) {
        for (Map.Entry<String, List<String>> entry : dailyFilesBySource.entrySet()) {
            String sourceName = entry.getKey();
            if (config.monthlyEnabled()) {
                backfillSnapshot(entry.getValue(), config.monthlyServerDir(sourceName), "月度");
            }
            if (config.yearlyEnabled()) {
                backfillSnapshot(entry.getValue(), config.yearlyServerDir(sourceName), "年度");
            }
        }
    }

    private void backfillSnapshot(List<String> dailyRemotePaths, String snapshotDir, String label) {
        try {
            List<String> missing = missingSnapshotFiles(dailyRemotePaths, snapshotDir);
            if (missing.isEmpty()) return;
            copyToSnapshot(missing, snapshotDir);
            log.info(label + "快照补全: 已从 daily 复制缺失文件 -> " + snapshotDir);
        } catch (Exception e) {
            log.warn(label + "快照补全失败(" + snapshotDir + "): " + e.getMessage() + ", 留待下次运行重试");
        }
    }

    /**
     * 快照 = 把刚上传的 daily 文件在云端复制到快照目录, 保留原始文件名(含完整日期时间).
     * 快照目录按「期间+服务器」粒度, 已有其他批次(文件名时间戳不同)的文件即视为本期间已完成;
     * 若目录里只有本批文件的一部分(上次复制中断), 只补齐缺失的分卷.
     */
    private void ensureSnapshot(Uploader uploader, List<Path> localParts,
                                List<String> dailyRemotePaths, String snapshotDir, String label) {
        List<String> missing;
        try {
            missing = missingSnapshotFiles(dailyRemotePaths, snapshotDir);
        } catch (Exception e) {
            log.warn(label + "快照目录读取失败(" + e.getMessage() + "), 改为从本地上传");
            uploadFallback(uploader, localParts, snapshotDir, label);
            return;
        }
        if (missing.isEmpty()) {
            log.info(label + "快照已存在, 跳过 -> " + snapshotDir);
            return;
        }
        try {
            copyToSnapshot(missing, snapshotDir);
            log.info(label + "快照: 云端复制完成 -> " + snapshotDir);
        } catch (Exception e) {
            log.warn(label + "快照云端复制失败(" + e.getMessage() + "), 改为从本地上传");
            uploadFallback(uploader, localParts, snapshotDir, label);
        }
    }

    /** 复制失败时的兜底: 从本地重新上传全部分卷(同名覆盖, 与已复制的分卷内容一致). */
    private void uploadFallback(Uploader uploader, List<Path> localParts,
                                String snapshotDir, String label) {
        try {
            for (Path part : localParts) {
                uploader.upload(part, snapshotDir + "/" + part.getFileName());
            }
            log.info(label + "快照: 本地上传完成 -> " + snapshotDir);
        } catch (Exception e2) {
            log.error(label + "快照上传也失败: " + e2.getMessage(), e2);
        }
    }

    /**
     * 计算快照目录缺失的 daily 文件路径: 目录为空则全部缺失;
     * 已有本期间其他批次的文件则视为快照完成, 返回空(避免一个月内重复留档).
     */
    private List<String> missingSnapshotFiles(List<String> dailyRemotePaths, String snapshotDir) {
        pan.mkdirs(snapshotDir);
        Set<String> present = new HashSet<>();
        for (RemoteFile f : pan.list(snapshotDir)) {
            present.add(f.name());
        }
        if (present.isEmpty()) return new ArrayList<>(dailyRemotePaths);
        Set<String> expected = new HashSet<>();
        for (String p : dailyRemotePaths) {
            expected.add(fileName(p));
        }
        if (!expected.containsAll(present)) return List.of();
        List<String> missing = new ArrayList<>();
        for (String p : dailyRemotePaths) {
            if (!present.contains(fileName(p))) missing.add(p);
        }
        return missing;
    }

    private void copyToSnapshot(List<String> fromPaths, String snapshotDir) {
        for (String from : fromPaths) {
            pan.copy(from, snapshotDir + "/" + fileName(from));
        }
    }

    private static String fileName(String remotePath) {
        return remotePath.substring(remotePath.lastIndexOf('/') + 1);
    }

    /** 从文件名解析内嵌日期, 无日期或非法日期返回 null. */
    public static LocalDate parseEmbeddedDate(String filename) {
        Matcher m = EMBEDDED_DATE.matcher(filename);
        if (!m.find()) return null;
        try {
            return LocalDate.parse(m.group(1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 目录名严格为 yyyy-MM-dd 时解析为日期(新版 daily 日期文件夹), 其余返回 null. */
    public static LocalDate parseDayFolder(String name) {
        if (EMBEDDED_DATE.matcher(name).matches()) {
            try {
                return LocalDate.parse(name);
            } catch (DateTimeParseException e) {
                // 落到下方返回 null
            }
        }
        return null;
    }
}
