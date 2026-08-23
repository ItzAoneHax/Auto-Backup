package com.autobackup.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

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
    private final Path workDir;
    private final Path logDir;
    private final int logRetainDays;
    private final boolean deleteLocalArchive;
    private final int chunkSizeMB;
    private final int uploadRetries;
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
        this.workDir = Path.of(props.getProperty("backup.workDir", "./work")).toAbsolutePath().normalize();
        this.logDir = Path.of(props.getProperty("backup.logDir", "./logs")).toAbsolutePath().normalize();
        this.logRetainDays = intProp(props, "backup.logRetainDays", 30);
        this.deleteLocalArchive = boolProp(props, "backup.deleteLocalArchive", true);
        this.chunkSizeMB = intProp(props, "upload.chunkSizeMB", 4);
        this.uploadRetries = intProp(props, "upload.retries", 3);
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
                + ", 年度备份=" + yearlyEnabled + ", 分片=" + chunkSizeMB + "MB, 重试=" + uploadRetries;
    }

    public int chunkSizeBytes() {
        return chunkSizeMB * 1024 * 1024;
    }

    public String dailyDir() {
        return remoteDir + "/daily";
    }

    public String yearlyDir() {
        return remoteDir + "/yearly";
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
    public Path workDir() { return workDir; }
    public Path logDir() { return logDir; }
    public int logRetainDays() { return logRetainDays; }
    public boolean deleteLocalArchive() { return deleteLocalArchive; }
    public int chunkSizeMB() { return chunkSizeMB; }
    public int uploadRetries() { return uploadRetries; }
    public Path tokenFile() { return tokenFile; }

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
