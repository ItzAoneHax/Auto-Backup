package com.autobackup;

import com.autobackup.auth.OAuthService;
import com.autobackup.auth.TokenInfo;
import com.autobackup.auth.TokenStore;
import com.autobackup.backup.BackupService;
import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.RemoteFile;
import com.autobackup.scheduler.DailyScheduler;
import com.autobackup.util.HttpUtil;
import com.autobackup.util.InstanceLock;
import com.autobackup.util.Json;
import com.autobackup.util.StateStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令行入口.
 *
 * <pre>
 * java -jar auto-backup.jar login    # 首次授权登录(设备码方式, 只需一次)
 * java -jar auto-backup.jar verify   # 检查凭证/容量/远程目录
 * java -jar auto-backup.jar run      # 立即执行一次备份(适合配到系统 cron)
 * java -jar auto-backup.jar daemon   # 常驻进程, 每天 backup.dailyTime 自动备份(适合 systemd)
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
            case "run" -> runOnce(config) ? 0 : 1;
            case "daemon" -> daemon(config) ? 0 : 1;
            case "ls" -> {
                String remotePath = firstNonFlagArg(args);
                if (remotePath == null) throw new IllegalStateException("用法: ls <远程路径>  例: ls /apps");
                listRemote(config, remotePath);
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
            List<RemoteFile> files = pan.list(dir);
            long count = files.stream().filter(f -> !f.dir()).count();
            long bytes = files.stream().filter(f -> !f.dir()).mapToLong(RemoteFile::size).sum();
            System.out.println(label + ": " + dir + " (" + count + " 个文件, " + bytes / 1048576 + " MB)");
        } catch (Exception e) {
            System.out.println(label + ": " + dir + " (尚不存在, 首次备份时自动创建)");
        }
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

    /** 取第一个非选项、非命令的参数(如 ls 的路径). */
    private static String firstNonFlagArg(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("-") && !"ls".equals(arg)) return arg;
        }
        return null;
    }

    private static boolean runOnce(Path configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        config.validateForBackup();
        HttpUtil http = new HttpUtil();
        OAuthService oauth = new OAuthService(config, http, new TokenStore(config.tokenFile()));
        StateStore state = new StateStore(config.workDir().resolve("state.properties"));
        BackupService backup = new BackupService(config, http, oauth, state);
        try (InstanceLock ignored = InstanceLock.acquire(config.workDir().resolve("auto-backup.lock"))) {
            return backup.runOnce();
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
                  daemon   常驻进程, 每天 backup.dailyTime 自动备份(适合 systemd)
                  ls       列出网盘目录(调试用, 例: ls /apps)
                  help     显示本帮助

                默认配置文件: config/application.properties (相对当前工作目录)
                配置模板: config/application.properties.example
                详细文档: README.md / docs/设计方案.md
                """);
    }

    private Main() {}
}
