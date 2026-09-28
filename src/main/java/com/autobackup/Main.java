package com.autobackup;

import com.autobackup.auth.OAuthService;
import com.autobackup.auth.TokenInfo;
import com.autobackup.auth.TokenStore;
import com.autobackup.backup.BackupService;
import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.RemoteFile;
import com.autobackup.scheduler.DaemonConsole;
import com.autobackup.scheduler.DailyScheduler;
import com.autobackup.util.HttpUtil;
import com.autobackup.util.InstanceLock;
import com.autobackup.util.Json;
import com.autobackup.util.StateStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行入口.
 *
 * <pre>
 * java -jar auto-backup.jar login          # 首次授权登录(设备码方式, 只需一次)
 * java -jar auto-backup.jar verify         # 检查凭证/容量/远程目录
 * java -jar auto-backup.jar run            # 立即执行一次备份(适合配到系统 cron)
 * java -jar auto-backup.jar run /data/foo  # 临时备份指定目录, 可多个空格分隔
 * java -jar auto-backup.jar daemon         # 常驻进程, 每天 backup.dailyTime 自动备份(适合 systemd/面板托管)
 *                                         #   daemon 运行中支持控制台命令: run [路径...] / status / exit
 * </pre>
 */
public final class Main {

    private static final String DEFAULT_CONFIG = "config/application.properties";

    public static void main(String[] args) {
        // 部分上传域名同时有 AAAA 记录, 无 IPv6 路由的主机会出现 "Network is unreachable";
        // 统一走 IPv4(须在任何网络调用前设置)
        System.setProperty("java.net.preferIPv4Stack", "true");
        try {
            System.exit(dispatch(args));
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    private static int dispatch(String[] args) throws Exception {
        String command = null;
        String configPath = DEFAULT_CONFIG;
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
            } else if (!arg.startsWith("-") && command == null) {
                command = arg;
            }
        }
        if (command == null) command = "help";
        Path config = Path.of(configPath);
        return switch (command) {
            case "login" -> {
                login(config);
                yield 0;
            }
            case "verify" -> {
                verify(config);
                yield 0;
            }
            case "run" -> runOnce(config, argsAfterCommand(args)) ? 0 : 1;
            case "daemon" -> daemon(config) ? 0 : 1;
            case "ls" -> {
                List<String> rest = argsAfterCommand(args);
                if (rest.isEmpty()) throw new IllegalStateException("用法: ls <远程路径>  例: ls /apps");
                listRemote(config, rest.get(0));
                yield 0;
            }
            default -> {
                printHelp();
                yield "help".equals(command) ? 0 : 2;
            }
        };
    }

    private static void login(Path configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        if (config.appKey().isBlank() || config.secretKey().isBlank()) {
            throw new IllegalStateException("请先在 " + configPath + " 中配置 pan.appKey 与 pan.secretKey");
        }
        HttpUtil http = new HttpUtil();
        TokenStore store = new TokenStore(config.tokenFile());
        new OAuthService(config, http, store).loginInteractive();
        TokenInfo token = store.load();
        JsonNode info = new PanClient(token.accessToken(), http).userInfo();
        System.out.println("[OK] 当前授权账号: "
                + info.path("netdisk_name").asText(info.path("baidu_name").asText("未知")));
    }

    private static void verify(Path configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        HttpUtil http = new HttpUtil();
        TokenStore store = new TokenStore(config.tokenFile());
        TokenInfo token = new OAuthService(config, http, store).ensureValidToken();
        PanClient pan = new PanClient(token.accessToken(), http);

        JsonNode info = pan.userInfo();
        System.out.println("账号: " + info.path("netdisk_name").asText("?")
                + " (" + info.path("baidu_name").asText("?") + ")");

        JsonNode quota = pan.quota();
        long total = quota.path("total").asLong(0);
        long used = quota.path("used").asLong(0);
        System.out.printf("网盘容量: %.2f GB, 已用 %.2f GB%n", total / 1073741824.0, used / 1073741824.0);

        if (!config.remoteDir().isBlank()) {
            printDirSummary(pan, config.dailyDir(), "daily 目录(每日备份, 到期自动清理)");
            printDirSummary(pan, config.yearlyDir(), "yearly 目录(每年一份, 永久保留)");
        }
        System.out.println("[OK] 验证通过");
    }

    private static void printDirSummary(PanClient pan, String dir, String label) {
        try {
            long[] stats = countFilesRecursively(pan, dir);
            System.out.println(label + ": " + dir
                    + " (" + stats[0] + " 个文件, " + stats[1] / 1048576 + " MB)");
        } catch (Exception e) {
            System.out.println(label + ": " + dir + " (尚不存在, 首次备份时自动创建)");
        }
    }

    /** 目录按日期+服务器分层后文件不再位于根下, 递归统计文件数与总字节(限深防异常结构). */
    private static long[] countFilesRecursively(PanClient pan, String dir) {
        return countFilesRecursively(pan, dir, 0);
    }

    private static long[] countFilesRecursively(PanClient pan, String dir, int depth) {
        long count = 0;
        long bytes = 0;
        if (depth < 5) {
            for (RemoteFile f : pan.list(dir)) {
                if (f.dir()) {
                    long[] sub = countFilesRecursively(pan, f.path(), depth + 1);
                    count += sub[0];
                    bytes += sub[1];
                } else {
                    count++;
                    bytes += f.size();
                }
            }
        }
        return new long[]{count, bytes};
    }

    /** 调试用: 列出网盘任意目录, 常用于发现个人应用的应用目录名(ls /apps). */
    private static void listRemote(Path configPath, String remotePath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        HttpUtil http = new HttpUtil();
        OAuthService oauth = new OAuthService(config, http, new TokenStore(config.tokenFile()));
        PanClient pan = new PanClient(oauth.ensureValidToken().accessToken(), http);
        List<RemoteFile> files = pan.list(remotePath);
        if (files.isEmpty()) {
            System.out.println("(目录为空或不存在: " + remotePath + ")");
            return;
        }
        for (RemoteFile f : files) {
            System.out.println((f.dir() ? "d " : "- ") + f.name() + (f.dir() ? "" : "  " + f.size() + " 字节"));
        }
    }

    /** 命令之后的所有非选项参数(run 的备份路径列表, ls 的远程路径). */
    private static List<String> argsAfterCommand(String[] args) {
        List<String> rest = new ArrayList<>();
        boolean commandSeen = false;
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            if (!commandSeen) {
                commandSeen = true;   // 第一个非选项参数是命令本身
                continue;
            }
            rest.add(arg);
        }
        return List.copyOf(rest);
    }

    /**
     * 立即执行一次备份. 不带路径时备份配置中的 backup.sources;
     * 带路径时为临时备份: 用命令行路径替换备份源, 固定全量上传到当日 daily 目录,
     * 不写增量清单、不生成快照、不触发云端清理, 也不影响当日定时备份.
     */
    private static boolean runOnce(Path configPath, List<String> adhocPaths) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        boolean adhoc = !adhocPaths.isEmpty();
        if (adhoc) {
            config = config.withSources(adhocPaths);
        }
        config.validateForBackup();
        HttpUtil http = new HttpUtil();
        OAuthService oauth = new OAuthService(config, http, new TokenStore(config.tokenFile()));
        StateStore state = new StateStore(config.workDir().resolve("state.properties"));
        BackupService backup = new BackupService(config, http, oauth, state);
        try (InstanceLock ignored = InstanceLock.acquire(config.workDir().resolve("auto-backup.lock"))) {
            return adhoc ? backup.runAdhoc() : backup.runOnce();
        }
    }

    private static boolean daemon(Path configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        config.validateForBackup();
        HttpUtil http = new HttpUtil();
        OAuthService oauth = new OAuthService(config, http, new TokenStore(config.tokenFile()));
        StateStore state = new StateStore(config.workDir().resolve("state.properties"));
        BackupService backup = new BackupService(config, http, oauth, state);
        try (InstanceLock ignored = InstanceLock.acquire(config.workDir().resolve("auto-backup.lock"))) {
            Thread console = new Thread(new DaemonConsole(backup, config, state), "daemon-console");
            console.setDaemon(true);
            console.start();
            new DailyScheduler(config, backup, state).runForever();
        }
        return true;
    }

    private static void printHelp() {
        System.out.println("""
                Auto-Backup - 百度网盘自动备份工具 v1.0.0

                用法: java -jar auto-backup.jar [命令] [--config=配置文件路径]

                命令:
                  login    首次授权登录(设备码方式, 只需一次)
                  verify   检查凭证、网盘容量与远程目录
                  run      立即执行一次备份(适合配置到系统 crontab)
                  run 路径  临时备份指定目录, 可多个空格分隔: 固定全量上传到当日 daily 目录,
                           到期随日常清理删除; 不写增量清单、不生成快照、不触发云端清理,
                           也不影响当日定时备份
                  daemon   常驻进程, 每天 backup.dailyTime 自动备份(适合 systemd / 面板托管);
                           运行中可在终端输入 help/status/run 控制台命令(如 run "/data/含空格 目录")
                  ls       列出网盘目录(调试用, 例: ls /apps)
                  help     显示本帮助

                默认配置文件: config/application.properties (相对当前工作目录)
                配置模板: config/application.properties.example
                详细文档: README.md / docs/设计方案.md
                """);
    }

    private Main() {}
}
