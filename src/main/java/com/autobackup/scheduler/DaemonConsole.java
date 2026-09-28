package com.autobackup.scheduler;

import com.autobackup.backup.BackupService;
import com.autobackup.config.AppConfig;
import com.autobackup.util.StateStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 守护进程控制台: 在面板终端(或任何 stdin)直接输入命令.
 *
 * <pre>
 * run [路径...]   立即备份; 带路径时临时备份这些目录(引号包裹含空格的路径), 不带则备份配置源
 * status          查看上次运行结果与当前状态
 * exit            退出守护进程(备份进行中时拒绝)
 * </pre>
 */
public final class DaemonConsole implements Runnable {

    private final BackupService backup;
    private final AppConfig config;
    private final StateStore state;

    public DaemonConsole(BackupService backup, AppConfig config, StateStore state) {
        this.backup = backup;
        this.config = config;
        this.state = state;
    }

    @Override
    public void run() {
        System.out.println("[控制台] 已就绪, 输入 help 查看可用命令");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handle(line.replace("\r", "").trim());
            }
            // stdin 关闭(如 systemd 无终端环境), 控制台静默退出, 不影响定时备份
        } catch (Exception e) {
            System.out.println("[控制台] 输入流异常, 控制台退出: " + e.getMessage());
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) return;
        List<String> tokens = tokenize(line);
        String cmd = tokens.get(0);
        List<String> args = tokens.subList(1, tokens.size());
        switch (cmd) {
            case "help" -> printHelp();
            case "status" -> printStatus();
            case "exit", "quit", "stop" -> exit();
            case "run" -> runBackup(args);
            default -> System.out.println("[控制台] 未知命令: " + cmd + " (输入 help 查看命令)");
        }
    }

    private void runBackup(List<String> args) {
        if (!args.isEmpty()) {
            try {
                // 复用常规校验: 路径必须存在, 配置其余项一并核对
                config.withSources(args).validateForBackup();
            } catch (Exception e) {
                System.out.println("[控制台] 拒绝执行:\n" + e.getMessage());
                return;
            }
        }
        if (backup.isRunning()) {
            System.out.println("[控制台] 已有备份正在执行, 本次请求将排队等待其结束后开始");
        }
        boolean ok = args.isEmpty() ? backup.runOnce() : backup.runAdhoc(args);
        System.out.println("[控制台] 备份" + (ok ? "完成" : "存在失败项, 详见上方日志"));
    }

    private void printStatus() {
        String lastDate = state.lastRunDate();
        String lastOk = state.lastRunOk();
        System.out.println("[控制台] 当前状态: "
                + (backup.isRunning() ? "备份执行中" : "空闲")
                + "; 上次定时备份: "
                + (lastDate == null ? "尚未运行" : lastDate + " (" + (Boolean.parseBoolean(lastOk) ? "成功" : "失败") + ")")
                + "; 下次定时备份: " + config.dailyTime());
    }

    private void exit() {
        if (backup.isRunning()) {
            System.out.println("[控制台] 备份正在执行, 结束后再退出");
            return;
        }
        System.out.println("[控制台] 再见");
        System.exit(0);
    }

    private void printHelp() {
        System.out.println("""
                [控制台] 可用命令:
                  run [路径...]  立即备份; 带路径时临时备份指定目录(可多个, 含空格的路径用引号包裹), 不带则备份配置中的 backup.sources
                  status         查看上次运行结果与当前状态
                  help           显示本帮助
                  exit           退出守护进程""");
    }

    /**
     * 分词: 空格分隔, 支持单/双引号包裹含空格的参数; 未闭合的引号把剩余内容整体作为一个参数.
     * 面板仿真终端不做 shell 引号展开, 含空格路径必须由本方法自行解析.
     */
    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());   // 兼容未闭合引号: 剩余内容整体收尾
        }
        return tokens;
    }
}
