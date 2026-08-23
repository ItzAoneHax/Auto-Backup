package com.autobackup.scheduler;

import com.autobackup.backup.BackupService;
import com.autobackup.config.AppConfig;
import com.autobackup.util.StateStore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 守护模式: 常驻进程, 每天在 backup.dailyTime 触发一次备份.
 * 启动时若当天计划时间已过且当日尚未运行, 会立即补跑一次(停机错过也不丢备份).
 */
public class DailyScheduler {

    private static final long TICK_MILLIS = 20_000;

    private final AppConfig config;
    private final BackupService backup;
    private final StateStore state;

    public DailyScheduler(AppConfig config, BackupService backup, StateStore state) {
        this.config = config;
        this.backup = backup;
        this.state = state;
    }

    public void runForever() throws InterruptedException {
        LocalTime daily = LocalTime.parse(config.dailyTime());
        System.out.println("[INFO] 守护进程已启动, 每日 " + config.dailyTime() + " 自动备份 (Ctrl+C 退出)");
        String printedNext = "";
        while (true) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime today = now.toLocalDate().atTime(daily);
            if (!now.isBefore(today) && !LocalDate.now().toString().equals(state.lastRunDate())) {
                System.out.println("[INFO] 到达计划时间, 开始执行每日备份");
                try {
                    backup.runOnce();
                } catch (Exception e) {
                    System.err.println("[ERROR] 备份执行异常: " + e.getMessage());
                }
                printedNext = "";
                continue;
            }
            LocalDateTime next = now.isBefore(today) ? today : today.plusDays(1);
            if (!next.toString().equals(printedNext)) {
                System.out.println("[INFO] 下次备份时间: " + next);
                printedNext = next.toString();
            }
            Thread.sleep(TICK_MILLIS);
        }
    }
}
