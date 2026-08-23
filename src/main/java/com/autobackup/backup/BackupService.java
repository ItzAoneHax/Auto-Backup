package com.autobackup.backup;

import com.autobackup.auth.OAuthService;
import com.autobackup.auth.TokenInfo;
import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.PanException;
import com.autobackup.util.HttpUtil;
import com.autobackup.util.StateStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 一次完整备份流程:
 * 打包 → 逐个上传 daily 目录 → 年度留档 → 云端过期清理 → 本地清理 → 上传运行日志.
 * 单个备份源失败不影响其他源, 任一失败则本次运行记为失败.
 */
public class BackupService {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final AppConfig config;
    private final HttpUtil http;
    private final OAuthService oauth;
    private final StateStore state;
    private final ArchiveService archiveService = new ArchiveService();

    public BackupService(AppConfig config, HttpUtil http, OAuthService oauth, StateStore state) {
        this.config = config;
        this.http = http;
        this.oauth = oauth;
        this.state = state;
    }

    /** 执行一次备份, 返回是否全部成功; 运行日志写入本地并尽力上传到云端. */
    public boolean runOnce() {
        String runId = LocalDateTime.now().format(RUN_ID);
        Path logFile;
        boolean ok;
        try (LogService log = new LogService(config.logDir(), runId)) {
            logFile = log.file();
            try {
                log.section("自动备份开始 runId=" + runId);
                log.info("配置: " + config.maskedSummary());
                TokenInfo token = oauth.ensureValidToken();
                ok = executeWithTokenRetry(token, log, runId);
            } catch (Exception e) {
                log.error("备份失败: " + e.getMessage(), e);
                ok = false;
            }
            if (ok) {
                log.info("本次备份全部成功 (警告 " + log.warnings() + " 个)");
            } else {
                log.error("本次备份存在失败项, 请检查上方日志");
            }
            state.recordRun(LocalDate.now(), ok);
        } catch (IOException e) {
            System.err.println("[ERROR] 无法创建日志文件: " + e.getMessage());
            return false;
        }
        // 日志文件此时已写完并关闭, 上传的是完整内容
        uploadRunLog(logFile);
        return ok;
    }

    /** 令牌在备份中途过期时自动刷新并整体重试一次. */
    private boolean executeWithTokenRetry(TokenInfo token, LogService log, String runId) {
        try {
            return execute(new PanClient(token.accessToken(), http), log, runId);
        } catch (PanException e) {
            if (!e.isTokenExpired()) throw e;
            log.warn("访问令牌已过期, 自动刷新后重试");
            TokenInfo fresh = oauth.refresh(token);
            return execute(new PanClient(fresh.accessToken(), http), log, runId);
        }
    }

    private boolean execute(PanClient pan, LogService log, String runId) {
        long start = System.currentTimeMillis();
        boolean allOk = true;
        try {
            Files.createDirectories(config.workDir());
            String dailyDir = config.dailyDir();
            String yearlyDir = config.yearlyDir();
            pan.mkdirs(dailyDir);
            pan.mkdirs(yearlyDir);
            Uploader uploader = new Uploader(pan, config, log);
            RetentionService retention = new RetentionService(pan, config, log);

            List<Path> sources = config.sources().stream().map(Path::of).toList();
            List<String> names = sourceNames(sources, log);
            for (int i = 0; i < sources.size(); i++) {
                Path source = sources.get(i);
                String name = names.get(i);
                log.section("备份源 [" + (i + 1) + "/" + sources.size() + "]: " + source);
                try {
                    Path archive = config.workDir().resolve(name + "-" + runId + ".tar.gz");
                    ArchiveService.ArchiveResult result =
                            archiveService.createArchive(source, name, archive, config.excludes(), log);
                    log.info("打包完成: " + archive.getFileName() + " ("
                            + result.size() / 1048576 + " MB, " + result.fileCount() + " 个文件)");
                    uploader.upload(archive, dailyDir + "/" + name + "-" + runId + ".tar.gz");
                    retention.ensureYearlySnapshot(uploader, archive, name, yearlyDir);
                    if (config.deleteLocalArchive()) {
                        Files.deleteIfExists(archive);
                    }
                } catch (Exception e) {
                    log.error("备份源 " + source + " 失败: " + e.getMessage(), e);
                    allOk = false;
                }
            }

            try {
                retention.cleanExpiredDaily(dailyDir);
            } catch (Exception e) {
                log.error("云端过期清理失败: " + e.getMessage(), e);
                allOk = false;
            }

            cleanLocalLogs(log);
            log.info("总耗时 " + (System.currentTimeMillis() - start) / 1000 + " 秒");
            return allOk;
        } catch (IOException e) {
            log.error("本地目录准备失败: " + e.getMessage(), e);
            return false;
        }
    }

    /** 把本次运行日志上传到 daily 目录, 与当日备份文件同期清理. */
    private void uploadRunLog(Path logFile) {
        LogService console = LogService.consoleOnly();
        try {
            TokenInfo token = oauth.ensureValidToken();
            PanClient pan = new PanClient(token.accessToken(), http);
            new Uploader(pan, config, console).upload(logFile, config.dailyDir() + "/" + logFile.getFileName());
            console.info("运行日志已上传: " + logFile.getFileName());
        } catch (Exception e) {
            console.warn("运行日志上传失败(不影响备份结果): " + e.getMessage());
        }
    }

    private void cleanLocalLogs(LogService log) {
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(config.logRetainDays() - 1L, 0));
        try (Stream<Path> stream = Files.list(config.logDir())) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                LocalDate d = RetentionService.parseEmbeddedDate(p.getFileName().toString());
                if (d != null && d.isBefore(cutoff)) {
                    Files.deleteIfExists(p);
                    log.info("本地日志清理: 删除 " + p.getFileName());
                }
            }
        } catch (IOException e) {
            log.warn("本地日志清理失败: " + e.getMessage());
        }
    }

    /** 每个备份源取目录名作为归档名, 重名时追加序号. */
    private List<String> sourceNames(List<Path> sources, LogService log) {
        List<String> names = new ArrayList<>();
        for (Path s : sources) {
            String name = sanitize(s.getFileName().toString());
            if (names.contains(name)) {
                int i = 2;
                while (names.contains(name + "_" + i)) i++;
                name = name + "_" + i;
                log.warn("备份源目录名重复, " + s + " 的归档名使用: " + name);
            }
            names.add(name);
        }
        return names;
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }
}
