package com.autobackup.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Properties;

/** 记录最近一次运行日期/结果, 防止守护进程当日重复触发. */
public class StateStore {

    private final Path file;
    private final Properties props = new Properties();

    public StateStore(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 状态文件损坏时视为无状态, 不影响运行
            }
        }
    }

    public synchronized String lastRunDate() {
        return props.getProperty("lastRunDate");
    }

    public synchronized void recordRun(LocalDate date, boolean ok) {
        props.setProperty("lastRunDate", date.toString());
        props.setProperty("lastRunOk", Boolean.toString(ok));
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Auto-Backup run state (auto generated)");
            }
        } catch (IOException e) {
            System.err.println("[WARN] 状态文件写入失败: " + e.getMessage());
        }
    }
}
