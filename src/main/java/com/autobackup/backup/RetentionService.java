package com.autobackup.backup;

import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.RemoteFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 云端保留策略:
 * 1. daily 目录只保留近 N 天(默认 3 天)的备份与日志, 更早的自动删除;
 * 2. 每年第一次成功备份额外上传一份到 yearly 目录, 永不清理;
 * 3. 只删除文件名内嵌 yyyy-MM-dd 日期且早于截止日的文件, 其他文件一律不动.
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

    /** 每年第一次成功备份时, 额外上传一份到 yearly 目录(永不清理). */
    public void ensureYearlySnapshot(Uploader uploader, Path localArchive, String sourceName, String yearlyDir) {
        if (!config.yearlyEnabled()) return;
        String target = sourceName + "-" + LocalDate.now().getYear() + ".tar.gz";
        boolean exists = pan.list(yearlyDir).stream()
                .anyMatch(f -> f.name().equals(target));
        if (exists) {
            log.info("年度备份 " + target + " 已存在, 跳过");
            return;
        }
        log.info("上传年度永久备份 " + target);
        try {
            uploader.upload(localArchive, yearlyDir + "/" + target);
        } catch (Exception e) {
            log.error("年度备份上传失败: " + target, e);
        }
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
