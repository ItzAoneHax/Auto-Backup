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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 一次完整备份流程:
 * 打包(全量或增量) → 按日期+服务器目录逐个上传 daily → 增量日补齐当日文件夹的完整恢复链(云端复制)
 * → 月度/年度快照 → 快照补全核对 → 云端过期清理 → 本地清理 → 上传运行日志.
 * 单个备份源失败不影响其他源, 任一失败则本次运行记为失败.
 *
 * <p>临时备份({@link #runAdhoc()})只走其中"打包 → 上传 daily"一段, 不碰其余环节.</p>
 */
public class BackupService {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final AppConfig config;
    private final HttpUtil http;
    private final OAuthService oauth;
    private final StateStore state;
    private final ArchiveService archiveService = new ArchiveService();

    /** 序列化定时备份与控制台触发的备份, 避免两个流程同时打包/上传. */
    private final ReentrantLock runLock = new ReentrantLock(true);
    private volatile boolean running;

    public BackupService(AppConfig config, HttpUtil http, OAuthService oauth, StateStore state) {
        this.config = config;
        this.http = http;
        this.oauth = oauth;
        this.state = state;
    }

    /** 执行一次常规备份(配置中的 backup.sources), 返回是否全部成功; 运行日志写入本地并尽力上传到云端. */
    public boolean runOnce() {
        return run(false, null);
    }

    /**
     * 临时备份(命令行指定路径): 固定全量, 只打包并上传到当日 daily 目录, 到期随日常清理一起删除.
     * 不写增量清单(避免污染同名备份源的增量链)、不生成月度/年度快照、不触发云端清理,
     * 也不记录调度状态(否则守护进程会把当天真正的定时备份当成已跑过而跳过).
     */
    public boolean runAdhoc() {
        return run(true, null);
    }

    /** 守护进程控制台触发的临时备份: 显式指定备份源(已在调用方完成路径校验). */
    public boolean runAdhoc(List<String> sources) {
        return run(true, sources);
    }

    /** 是否有备份正在执行(控制台 status/exit 命令用). */
    public boolean isRunning() {
        return running;
    }

    private boolean run(boolean adhoc, List<String> adhocSources) {
        runLock.lock();
        running = true;
        try {
            return doRun(adhoc, adhocSources);
        } finally {
            running = false;
            runLock.unlock();
        }
    }

    private boolean doRun(boolean adhoc, List<String> adhocSources) {
        List<String> sourcePaths = adhocSources != null ? adhocSources : config.sources();
        String runId = LocalDateTime.now().format(RUN_ID);
        Path logFile;
        boolean ok;
        try (LogService log = new LogService(config.logDir(), runId)) {
            logFile = log.file();
            try {
                log.section((adhoc ? "临时备份开始" : "自动备份开始") + " runId=" + runId);
                log.info("配置: " + config.maskedSummary());
                if (adhocSources != null) {
                    log.info("备份源(临时指定): " + adhocSources);
                }
                TokenInfo token = oauth.ensureValidToken();
                ok = executeWithTokenRetry(token, log, runId, adhoc, sourcePaths);
            } catch (Exception e) {
                log.error("备份失败: " + e.getMessage(), e);
                ok = false;
            }
            if (ok) {
                log.info("本次备份全部成功 (警告 " + log.warnings() + " 个)");
            } else {
                log.error("本次备份存在失败项, 请检查上方日志");
            }
            if (!adhoc) {
                state.recordRun(LocalDate.now(), ok);
            }
        } catch (IOException e) {
            System.err.println("[ERROR] 无法创建日志文件: " + e.getMessage());
            return false;
        }
        // 日志文件此时已写完并关闭, 上传的是完整内容
        uploadRunLog(logFile);
        return ok;
    }

    /** 令牌在备份中途过期时自动刷新并整体重试一次. */
    private boolean executeWithTokenRetry(TokenInfo token, LogService log, String runId,
                                          boolean adhoc, List<String> sourcePaths) {
        try {
            return execute(new PanClient(token.accessToken(), http), log, runId, adhoc, sourcePaths);
        } catch (PanException e) {
            if (!e.isTokenExpired()) throw e;
            log.warn("访问令牌已过期, 自动刷新后重试");
            TokenInfo fresh = oauth.refresh(token);
            return execute(new PanClient(fresh.accessToken(), http), log, runId, adhoc, sourcePaths);
        }
    }

    private boolean execute(PanClient pan, LogService log, String runId,
                            boolean adhoc, List<String> sourcePaths) {
        long start = System.currentTimeMillis();
        boolean allOk = true;
        try {
            Files.createDirectories(config.workDir());
            String dayDir = config.dailyDayDir(LocalDate.now());
            pan.mkdirs(dayDir);
            Uploader uploader = new Uploader(pan, config, log);
            RetentionService retention = new RetentionService(pan, config, log);

            List<Path> sources = sourcePaths.stream().map(Path::of).toList();
            List<String> names = sourceNames(sources, log);
            Map<String, List<String>> uploadedDailyFiles = new LinkedHashMap<>();
            for (int i = 0; i < sources.size(); i++) {
                Path source = sources.get(i);
                String name = names.get(i);
                log.section("备份源 [" + (i + 1) + "/" + sources.size() + "]: " + source);
                List<Path> parts = List.of();
                try {
                    SourceManifest prev = !adhoc && config.incrementalEnabled()
                            ? SourceManifest.load(manifestPath(name), source, log) : null;
                    String fullReason;
                    if (adhoc) {
                        fullReason = "临时备份固定全量";
                    } else if (!config.incrementalEnabled()) {
                        fullReason = "增量备份未启用";
                    } else {
                        fullReason = fullBackupReason(prev, name, retention);
                    }
                    boolean full = fullReason != null;
                    if (full) log.info("本次全量备份: " + fullReason);
                    Path archiveBase = config.workDir().resolve(name + "-" + runId
                            + (full ? "" : ".inc") + "." + config.compression().extension());
                    ArchiveService.ArchiveResult result = archiveService.createArchive(
                            source, name, archiveBase, config.excludes(), config.splitSizeBytes(),
                            config.compression(), full ? null : prev.files(), log);
                    parts = result.parts();
                    log.info("打包完成: " + archiveBase.getFileName() + " (共 " + parts.size() + " 个分卷, "
                            + result.totalSize() / 1048576 + " MB, 归档 " + result.fileCount()
                            + "/" + result.scannedCount() + " 个文件)");
                    String serverDir = dayDir + "/" + name;
                    pan.mkdirs(serverDir);
                    List<String> remotePaths = new ArrayList<>();
                    for (Path part : parts) {
                        String remote = serverDir + "/" + part.getFileName();
                        uploader.upload(part, remote);
                        remotePaths.add(remote);
                    }
                    if (!adhoc) {
                        retention.ensureMonthlySnapshot(uploader, parts, remotePaths, name);
                        retention.ensureYearlySnapshot(uploader, parts, remotePaths, name);
                        saveManifest(source, name, prev, result, full, log);
                    }
                    if (!full) {
                        // 增量日: 把"上次全量 + 其后增量"云端复制进今日文件夹,
                        // 保证每天下载整个日期文件夹即可解压出当日完整服务端(复制零流量)
                        retention.mirrorChainIntoToday(name, serverDir, prev.lastFullDate());
                    }
                    uploadedDailyFiles.put(name, remotePaths);
                    deleteLocal(parts);
                } catch (Exception e) {
                    deleteLocal(parts);   // 上传失败的分卷留在本地也没有价值(下次运行会重新打包)
                    log.error("备份源 " + source + " 失败: " + e.getMessage(), e);
                    allOk = false;
                }
            }

            if (!adhoc) {
                try {
                    retention.backfillSnapshots(uploadedDailyFiles);
                } catch (Exception e) {
                    log.warn("快照补全核对失败: " + e.getMessage());
                }

                try {
                    retention.cleanExpiredDaily(config.dailyDir());
                } catch (Exception e) {
                    log.error("云端过期清理失败: " + e.getMessage());
                    allOk = false;
                }
            }

            cleanLocalLogs(log);
            log.info("总耗时 " + (System.currentTimeMillis() - start) / 1000 + " 秒");
            return allOk;
        } catch (IOException e) {
            log.error("本地目录准备失败: " + e.getMessage(), e);
            return false;
        }
    }

    /** 按配置清理本地归档分卷; deleteLocalArchive=false 时保留. */
    private void deleteLocal(List<Path> parts) {
        if (!config.deleteLocalArchive()) return;
        for (Path part : parts) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException e) {
                // 清理失败不影响本次备份结果, 残留分卷随 workDir 人工清理
            }
        }
    }

    /** 备份源对应的本地增量清单路径. */
    private Path manifestPath(String name) {
        return config.workDir().resolve("manifests").resolve(name + ".json");
    }

    /**
     * 返回该源本次需要走全量的原因; null 表示可以走增量.
     * 月度/年度快照缺失时强制全量, 保证快照目录里的批次自包含、可脱离 daily 链独立恢复,
     * 同时天然覆盖"月中新增备份源"与"上次快照复制中断"两种补全场景.
     */
    private String fullBackupReason(SourceManifest prev, String name, RetentionService retention) {
        if (prev == null || prev.lastFullDate() == null) return "无可用的增量清单";
        long days = java.time.temporal.ChronoUnit.DAYS.between(prev.lastFullDate(), LocalDate.now());
        if (days >= config.fullIntervalDays()) {
            return "距上次全量已 " + days + " 天 (全量间隔 " + config.fullIntervalDays() + " 天)";
        }
        if (config.monthlyEnabled() && retention.snapshotPending(config.monthlyServerDir(name))) {
            return "本月月度快照缺失, 快照需自包含";
        }
        if (config.yearlyEnabled() && retention.snapshotPending(config.yearlyServerDir(name))) {
            return "本年度年度快照缺失, 快照需自包含";
        }
        return null;
    }

    /**
     * 上传成功后写入新的增量清单: 文件戳取本次扫描结果,
     * 全量基准日期在走全量时重置为今天, 走增量时沿用旧清单的基准.
     */
    private void saveManifest(Path source, String name, SourceManifest prev,
                              ArchiveService.ArchiveResult result, boolean full, LogService log) {
        if (!config.incrementalEnabled()) return;
        LocalDate baseDate = full || prev == null || prev.lastFullDate() == null
                ? LocalDate.now() : prev.lastFullDate();
        try {
            new SourceManifest(source.toAbsolutePath().normalize().toString(), baseDate, result.stamps())
                    .save(manifestPath(name));
            log.info("增量清单已更新: " + result.stamps().size() + " 个文件, 全量基准 " + baseDate);
        } catch (IOException e) {
            throw new IllegalStateException("增量清单写入失败: " + e.getMessage(), e);
        }
    }

    /** 把本次运行日志上传到当日日期文件夹根部, 与当日全部服务器备份同期清理. */
    private void uploadRunLog(Path logFile) {
        LogService console = LogService.consoleOnly();
        try {
            TokenInfo token = oauth.ensureValidToken();
            PanClient pan = new PanClient(token.accessToken(), http);
            String dayDir = config.dailyDayDir(LocalDate.now());
            pan.mkdirs(dayDir);   // 已存在不报错, 仅防极端情况(跨午夜)目录未创建
            new Uploader(pan, config, console).upload(logFile, dayDir + "/" + logFile.getFileName());
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
