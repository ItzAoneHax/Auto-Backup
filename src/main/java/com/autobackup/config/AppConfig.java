package com.autobackup.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import com.autobackup.backup.ArchiveService;

/** 全部可自定义配置项, 从 properties 文件加载, 含默认值与校验. */
public final class AppConfig {

    private final Path configFile;
    private final String appKey;
    private final String secretKey;
    private final String remoteDir;
    private final List<String> sources;
    private final List<String> excludes;
    private final String dailyTime;
    private final int retainDays;
    private final boolean yearlyEnabled;
    private final boolean monthlyEnabled;
    private final Path workDir;
    private final Path logDir;
    private final int logRetainDays;
    private final boolean deleteLocalArchive;
    private final int chunkSizeMB;
    private final int splitSizeMB;
    private final int uploadRetries;
    private final int uploadParallelChunks;
    private final String compressor;
    private final int zstdLevel;
    private final int zstdWorkers;
    private final boolean incrementalEnabled;
    private final int fullIntervalDays;
    private final Path tokenFile;

    private AppConfig(Path configFile, Properties props) {
        this.configFile = configFile.toAbsolutePath().normalize();
        this.appKey = props.getProperty("pan.appKey", "").trim();
        this.secretKey = props.getProperty("pan.secretKey", "").trim();
        this.remoteDir = normalizeRemote(props.getProperty("pan.remoteDir", "").trim());
        this.sources = splitList(props.getProperty("backup.sources", ""));
        this.excludes = splitList(props.getProperty("backup.excludes", ""));
        this.dailyTime = props.getProperty("backup.dailyTime", "03:00").trim();
        this.retainDays = intProp(props, "backup.retainDays", 3);
        this.yearlyEnabled = boolProp(props, "backup.yearly.enabled", true);
        this.monthlyEnabled = boolProp(props, "backup.monthly.enabled", true);
        this.workDir = Path.of(props.getProperty("backup.workDir", "./work")).toAbsolutePath().normalize();
        this.logDir = Path.of(props.getProperty("backup.logDir", "./logs")).toAbsolutePath().normalize();
        this.logRetainDays = intProp(props, "backup.logRetainDays", 30);
        this.deleteLocalArchive = boolProp(props, "backup.deleteLocalArchive", true);
        this.chunkSizeMB = intProp(props, "upload.chunkSizeMB", 4);
        this.splitSizeMB = intProp(props, "upload.splitSizeMB", 0);
        this.uploadRetries = intProp(props, "upload.retries", 3);
        this.uploadParallelChunks = intProp(props, "upload.parallelChunks", 4);
        this.compressor = props.getProperty("archive.compressor", ArchiveService.Compression.ZSTD).trim();
        this.zstdLevel = intProp(props, "archive.zstdLevel", 3);
        this.zstdWorkers = intProp(props, "archive.zstdWorkers", 0);
        this.incrementalEnabled = boolProp(props, "backup.incremental.enabled", false);
        this.fullIntervalDays = intProp(props, "backup.incremental.fullIntervalDays", 7);
        this.tokenFile = this.configFile.resolveSibling("token.json");
    }

    public static AppConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("配置文件不存在: " + file.toAbsolutePath()
                    + " (可从 config/application.properties.example 复制一份)");
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return new AppConfig(file, props);
    }

    /** 命令行临时指定备份源(run 路径): 返回替换 sources 后的副本, 其余配置原样保留. */
    public AppConfig withSources(List<String> override) {
        return new AppConfig(this, List.copyOf(override));
    }

    private AppConfig(AppConfig base, List<String> sources) {
        this.configFile = base.configFile;
        this.appKey = base.appKey;
        this.secretKey = base.secretKey;
        this.remoteDir = base.remoteDir;
        this.sources = sources;
        this.excludes = base.excludes;
        this.dailyTime = base.dailyTime;
        this.retainDays = base.retainDays;
        this.yearlyEnabled = base.yearlyEnabled;
        this.monthlyEnabled = base.monthlyEnabled;
        this.workDir = base.workDir;
        this.logDir = base.logDir;
        this.logRetainDays = base.logRetainDays;
        this.deleteLocalArchive = base.deleteLocalArchive;
        this.chunkSizeMB = base.chunkSizeMB;
        this.splitSizeMB = base.splitSizeMB;
        this.uploadRetries = base.uploadRetries;
        this.uploadParallelChunks = base.uploadParallelChunks;
        this.compressor = base.compressor;
        this.zstdLevel = base.zstdLevel;
        this.zstdWorkers = base.zstdWorkers;
        this.incrementalEnabled = base.incrementalEnabled;
        this.fullIntervalDays = base.fullIntervalDays;
        this.tokenFile = base.tokenFile;
    }

    /** 校验备份所需的关键配置, 有问题时一次性列出全部问题. */
    public void validateForBackup() {
        List<String> problems = new ArrayList<>();
        if (appKey.isBlank()) problems.add("pan.appKey 未配置");
        if (secretKey.isBlank()) problems.add("pan.secretKey 未配置");
        if (remoteDir.isBlank()) problems.add("pan.remoteDir 未配置(个人应用一般为 /apps/你的应用目录名)");
        if (sources.isEmpty()) problems.add("backup.sources 未配置");
        else {
            for (String s : sources) {
                if (!Files.isDirectory(Path.of(s))) problems.add("备份目录不存在: " + s);
            }
        }
        if (retainDays < 1) problems.add("backup.retainDays 不能小于 1");
        if (logRetainDays < 1) problems.add("backup.logRetainDays 不能小于 1");
        if (chunkSizeMB < 4 || chunkSizeMB > 32 || chunkSizeMB % 4 != 0) {
            problems.add("upload.chunkSizeMB 需为 4-32 之间 4 的倍数");
        }
        if (uploadRetries < 1) problems.add("upload.retries 不能小于 1");
        if (uploadParallelChunks < 1 || uploadParallelChunks > 16) {
            problems.add("upload.parallelChunks 需为 1-16(官方支持分片并发, 但并发越高失败率越高, 建议 4-8)");
        }
        if (splitSizeMB != 0 && (splitSizeMB < 64 || splitSizeMB > 20480)) {
            problems.add("upload.splitSizeMB 需为 0(不分卷)或 64-20480 之间的 MB 数(不超过账号单文件上限)");
        }
        if (!ArchiveService.Compression.ZSTD.equals(compressor)
                && !ArchiveService.Compression.GZIP.equals(compressor)) {
            problems.add("archive.compressor 仅支持 zstd 或 gzip, 当前: " + compressor);
        }
        if (zstdLevel < 1 || zstdLevel > 22) {
            problems.add("archive.zstdLevel 需为 1-22(常用 3, 数值越大压缩越狠越慢)");
        }
        if (zstdWorkers < 0 || zstdWorkers > 128) {
            problems.add("archive.zstdWorkers 需为 0(自动取 CPU 核数)或 1-128");
        }
        if (fullIntervalDays < 1 || fullIntervalDays > 365) {
            problems.add("backup.incremental.fullIntervalDays 需为 1-365 之间的天数");
        }
        try {
            LocalTime.parse(dailyTime);
        } catch (DateTimeParseException e) {
            problems.add("backup.dailyTime 格式应为 HH:mm, 当前: " + dailyTime);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("配置有误:\n  - " + String.join("\n  - ", problems));
        }
    }

    /** 日志中打印的配置摘要(敏感信息打码). */
    public String maskedSummary() {
        return "appKey=" + appKey + ", secretKey=" + mask(secretKey)
                + ", 远程目录=" + remoteDir + ", 备份源=" + sources
                + ", 每日时间=" + dailyTime + ", 保留天数=" + retainDays
                + ", 年度备份=" + yearlyEnabled + ", 月度备份=" + monthlyEnabled
                + ", 分片=" + chunkSizeMB + "MB"
                + ", 分卷=" + (splitSizeMB == 0 ? "关闭" : splitSizeMB + "MB")
                + ", 重试=" + uploadRetries
                + ", 上传并发=" + uploadParallelChunks
                + ", 压缩=" + compressor + (ArchiveService.Compression.ZSTD.equals(compressor)
                        ? "(级别 " + zstdLevel + ", 线程 " + compression().effectiveWorkers() + ")" : "")
                + ", 增量=" + (incrementalEnabled ? "开(全量间隔 " + fullIntervalDays + " 天)" : "关(每天全量)");
    }

    public int chunkSizeBytes() {
        return chunkSizeMB * 1024 * 1024;
    }

    /** 分卷字节数, 0 表示不分卷. */
    public long splitSizeBytes() {
        return (long) splitSizeMB * 1024 * 1024;
    }

    public String dailyDir() {
        return remoteDir + "/daily";
    }

    public String yearlyDir() {
        return remoteDir + "/yearly";
    }

    public String monthlyDir() {
        return remoteDir + "/monthly";
    }

    /** 某天的 daily 日期文件夹: daily/2026-08-27, 其下按服务器名再分目录. */
    public String dailyDayDir(LocalDate date) {
        return dailyDir() + "/" + date;
    }

    /** 某服务器当月的月度快照目录: monthly/2026-08/<serverName>. */
    public String monthlyServerDir(String serverName) {
        return monthlyDir() + "/" + YearMonth.now() + "/" + serverName;
    }

    /** 某服务器当年的年度快照目录: yearly/2026/<serverName>. */
    public String yearlyServerDir(String serverName) {
        return yearlyDir() + "/" + LocalDate.now().getYear() + "/" + serverName;
    }

    public Path configFile() { return configFile; }
    public String appKey() { return appKey; }
    public String secretKey() { return secretKey; }
    public String remoteDir() { return remoteDir; }
    public List<String> sources() { return sources; }
    public List<String> excludes() { return excludes; }
    public String dailyTime() { return dailyTime; }
    public int retainDays() { return retainDays; }
    public boolean yearlyEnabled() { return yearlyEnabled; }
    public boolean monthlyEnabled() { return monthlyEnabled; }
    public Path workDir() { return workDir; }
    public Path logDir() { return logDir; }
    public int logRetainDays() { return logRetainDays; }
    public boolean deleteLocalArchive() { return deleteLocalArchive; }
    public int chunkSizeMB() { return chunkSizeMB; }
    public int splitSizeMB() { return splitSizeMB; }
    public int uploadRetries() { return uploadRetries; }
    public int uploadParallelChunks() { return uploadParallelChunks; }
    public String compressor() { return compressor; }
    public int zstdLevel() { return zstdLevel; }
    public int zstdWorkers() { return zstdWorkers; }
    public boolean incrementalEnabled() { return incrementalEnabled; }
    public int fullIntervalDays() { return fullIntervalDays; }
    public Path tokenFile() { return tokenFile; }

    /** 归档压缩参数. */
    public ArchiveService.Compression compression() {
        if (ArchiveService.Compression.GZIP.equals(compressor)) return ArchiveService.Compression.gzip();
        return new ArchiveService.Compression(ArchiveService.Compression.ZSTD, zstdLevel, zstdWorkers);
    }

    /**
     * 增量模式下 daily 的实际保留天数: 全量间隔 + 2 天(保证"最近一次全量 + 其后增量"的恢复链
     * 始终完整, 并容忍一次全量失败), 不低于用户配置的保留天数。
     */
    public int effectiveRetainDays() {
        return incrementalEnabled ? Math.max(retainDays, fullIntervalDays + 2) : retainDays;
    }

    private static String normalizeRemote(String dir) {
        if (dir.isBlank()) return "";
        String d = dir.endsWith("/") && dir.length() > 1 ? dir.substring(0, dir.length() - 1) : dir;
        return d.startsWith("/") ? d : "/" + d;
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static int intProp(Properties props, String key, int def) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置项 " + key + " 不是合法数字: " + raw);
        }
    }

    private static boolean boolProp(Properties props, String key, boolean def) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return def;
        return Boolean.parseBoolean(raw.trim());
    }

    private static String mask(String s) {
        if (s.length() <= 4) return "****";
        return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }
}
