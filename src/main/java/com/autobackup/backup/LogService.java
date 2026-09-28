package com.autobackup.backup;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 单次备份运行的日志: 同时写本地文件与控制台(daemon 模式下进入 systemd journal). */
public final class LogService implements AutoCloseable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path logFile;
    private final PrintWriter writer;
    private int warnings = 0;
    private int errors = 0;

    public LogService(Path logDir, String runId) throws IOException {
        this(logDir, "backup-", runId);
    }

    /** prefix 区分常规(backup-)与临时(adhoc-)运行日志. */
    public LogService(Path logDir, String prefix, String runId) throws IOException {
        Files.createDirectories(logDir);
        this.logFile = logDir.resolve(prefix + runId + ".log");
        this.writer = new PrintWriter(Files.newBufferedWriter(this.logFile, StandardCharsets.UTF_8), true);
    }

    /** 只输出到控制台的日志实例(用于备份完成后上传日志的阶段). */
    public static LogService consoleOnly() {
        return new LogService(null, new PrintWriter(Writer.nullWriter(), true));
    }

    public Path file() {
        return logFile;
    }

    public synchronized void info(String message) {
        write("INFO ", message);
    }

    public synchronized void warn(String message) {
        warnings++;
        write("WARN ", message);
    }

    public synchronized void error(String message) {
        error(message, null);
    }

    public synchronized void error(String message, Throwable t) {
        errors++;
        write("ERROR", message);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            for (String line : sw.toString().split("\\R")) {
                write("ERROR", "    " + line);
            }
        }
    }

    public synchronized void section(String title) {
        write("=====", "--------------------------------------------------");
        write("=====", title);
        write("=====", "--------------------------------------------------");
    }

    public int warnings() {
        return warnings;
    }

    public int errors() {
        return errors;
    }

    private void write(String level, String message) {
        String line = "[" + LocalDateTime.now().format(TS) + "] [" + level + "] " + message;
        writer.println(line);
        System.out.println(line);
    }

    private LogService(Path logFile, PrintWriter writer) {
        this.logFile = logFile;
        this.writer = writer;
    }

    @Override
    public void close() {
        writer.close();
    }
}
