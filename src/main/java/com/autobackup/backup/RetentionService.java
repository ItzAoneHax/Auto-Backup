package com.autobackup.backup;

import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.RemoteFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 云端保留策略:
 * 1. daily 目录只保留近 N 天(默认 3 天)的备份与日志, 更早的自动删除;
 * 2. 每月/每年第一次成功备份后, 把当次 daily 文件在云端复制一份到 monthly/ 与 yearly/ 目录,
 *    永不清理(服务端复制, 无二次上传流量; 复制失败时回退为从本地上传);
 * 3. daily 清理只删除文件名内嵌 yyyy-MM-dd 日期且早于截止日的文件, 其他文件一律不动.
 */
public class RetentionService {

    /** 文件名中形如 2026-08-23 的日期(程序生成的备份与日志文件名均内嵌运行日期). */
    static final Pattern EMBEDDED_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private final PanClient pan;
    private final AppConfig config;
    private final LogService log;

    public RetentionService(PanClient pan, AppConfig config, LogService log) {
        this.pan = pan;
        this.config = config;
        this.log = log;
    }

    /** 删除 daily 目录中日期早于「今天-保留天数+1」的文件(含运行日志). */
    public void cleanExpiredDaily(String dailyDir) {
        List<RemoteFile> files = pan.list(dailyDir);
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(config.retainDays() - 1L, 0));
        List<String> toDelete = new ArrayList<>();
        for (RemoteFile file : files) {
            if (file.dir()) continue;
            LocalDate date = parseEmbeddedDate(file.name());
            if (date == null) continue;   // 非本程序生成的文件不动
            if (date.isBefore(cutoff)) toDelete.add(file.path());
        }
        if (toDelete.isEmpty()) {
            log.info("云端清理: 没有过期文件 (保留近 " + config.retainDays() + " 天)");
            return;
        }
        pan.delete(toDelete);
        for (String path : toDelete) {
            log.info("云端清理: 已删除过期备份 " + path);
        }
    }

    /** 每月第一次成功备份后, 云端复制一份到 monthly 目录(永不清理). */
    public void ensureMonthlySnapshot(Uploader uploader, List<Path> localParts,
                                      List<String> dailyRemotePaths, String sourceName) {
        if (!config.monthlyEnabled()) return;
        String prefix = monthlyPrefix(sourceName);
        ensureSnapshot(uploader, localParts, dailyRemotePaths,
                config.monthlyDir(), prefix, "月度");
    }

    /** 每年第一次成功备份后, 云端复制一份到 yearly 目录(永不清理). */
    public void ensureYearlySnapshot(Uploader uploader, List<Path> localParts,
                                     List<String> dailyRemotePaths, String sourceName) {
        if (!config.yearlyEnabled()) return;
        String prefix = yearlyPrefix(sourceName);
        ensureSnapshot(uploader, localParts, dailyRemotePaths,
                config.yearlyDir(), prefix, "年度");
    }

    /**
     * 快照 = 把刚上传的 daily 文件在云端复制到快照目录, 保留原始文件名(含完整日期时间).
     * 存在性按前缀判断(兼容旧命名 name-2026.tar.gz); 极端情况下备份源目录名本身
     * 形如 "xx-2026" 时可能误判已存在, 属可接受的命名边界.
     */
    private void ensureSnapshot(Uploader uploader, List<Path> localParts,
                                List<String> dailyRemotePaths, String snapshotDir,
                                String prefix, String label) {
        boolean exists = pan.list(snapshotDir).stream()
                .anyMatch(f -> f.name().startsWith(prefix));
        if (exists) {
            log.info(label + "快照已存在, 跳过 (" + prefix + "*)");
            return;
        }
        try {
            for (int i = 0; i < dailyRemotePaths.size(); i++) {
                String fileName = localParts.get(i).getFileName().toString();
                pan.copy(dailyRemotePaths.get(i), snapshotDir + "/" + fileName);
            }
            log.info(label + "快照: 云端复制完成 -> " + snapshotDir);
        } catch (Exception e) {
            log.warn(label + "快照云端复制失败(" + e.getMessage() + "), 改为从本地上传");
            try {
                for (Path part : localParts) {
                    uploader.upload(part, snapshotDir + "/" + part.getFileName());
                }
            } catch (Exception e2) {
                log.error(label + "快照上传也失败: " + e2.getMessage(), e2);
            }
        }
    }

    /** 年度快照前缀: name-YYYY(兼容匹配旧命名 name-YYYY.tar.gz 与新命名 name-YYYY-MM-DD_...). */
    public static String yearlyPrefix(String sourceName) {
        return sourceName + "-" + LocalDate.now().getYear();
    }

    /** 月度快照前缀: name-YYYY-MM. */
    public static String monthlyPrefix(String sourceName) {
        return sourceName + "-" + YearMonth.now();
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
}
