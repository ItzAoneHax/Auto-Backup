package com.autobackup.backup;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个备份源的上次成功归档清单: 每个已备份文件的 size+mtime 戳与最近一次全量日期,
 * 增量备份据此判断哪些文件需要重新入包.
 *
 * <p>存储格式为一串连续的 JSON 对象(流式读写, 不构建整棵树, 百万级文件也可承受):
 * 首个对象为头信息 {"version","sourcePath","lastFullDate"}, 其后每个文件一个
 * {"p":相对路径,"s":字节数,"m":mtimeMillis}。写入采用临时文件 + 原子替换, 中断不会损坏旧清单。
 */
public final class SourceManifest {

    /** 单个文件的归档比对戳: size 与 mtime 均一致即视为未变化. */
    public record Stamp(long size, long mtimeMillis) {}

    private static final JsonFactory JSON = new JsonFactory();

    private final String sourcePath;
    private final LocalDate lastFullDate;   // 可空: 尚未做过全量
    private final Map<String, Stamp> files;

    public SourceManifest(String sourcePath, LocalDate lastFullDate, Map<String, Stamp> files) {
        this.sourcePath = sourcePath;
        this.lastFullDate = lastFullDate;
        this.files = files;
    }

    public String sourcePath() {
        return sourcePath;
    }

    public LocalDate lastFullDate() {
        return lastFullDate;
    }

    public Map<String, Stamp> files() {
        return files;
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * 从清单文件加载; 文件不存在、损坏或记录的备份源路径与当前不符时返回 null
     * (调用方按无历史处理, 走全量), 后两种情况记警告日志。
     */
    public static SourceManifest load(Path file, Path source, LogService log) {
        if (!Files.isRegularFile(file)) return null;
        String sourcePath = null;
        LocalDate lastFullDate = null;
        Map<String, Stamp> files = new HashMap<>();
        try (JsonParser p = JSON.createParser(new BufferedInputStream(Files.newInputStream(file)))) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("清单缺少头信息");
            // 头对象
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                switch (field) {
                    case "sourcePath" -> sourcePath = p.nextTextValue();
                    case "lastFullDate" -> {
                        String raw = p.nextTextValue();
                        if (raw != null) {
                            try {
                                lastFullDate = LocalDate.parse(raw);
                            } catch (DateTimeParseException ignored) {
                                // 日期损坏视为无全量记录
                            }
                        }
                    }
                    default -> p.nextToken();   // version 等字段跳过
                }
            }
            // 文件戳对象
            while (p.nextToken() == JsonToken.START_OBJECT) {
                String path = null;
                long size = 0;
                long mtime = 0;
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    switch (p.currentName()) {
                        case "p" -> path = p.nextTextValue();
                        case "s" -> size = p.nextLongValue(0);
                        case "m" -> mtime = p.nextLongValue(0);
                        default -> p.nextToken();
                    }
                }
                if (path != null) files.put(path, new Stamp(size, mtime));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("增量清单损坏, 本次走全量 (" + file.getFileName() + ": " + e.getMessage() + ")");
            return null;
        }
        String current = source.toAbsolutePath().normalize().toString();
        if (sourcePath == null || !sourcePath.equals(current)) {
            log.warn("增量清单记录的备份源与当前不一致, 本次走全量 (清单: " + sourcePath + ", 当前: " + current + ")");
            return null;
        }
        return new SourceManifest(current, lastFullDate, files);
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp));
             JsonGenerator g = JSON.createGenerator(out, JsonEncoding.UTF8)) {
            g.writeStartObject();
            g.writeStringField("version", "1");
            g.writeStringField("sourcePath", sourcePath);
            if (lastFullDate != null) g.writeStringField("lastFullDate", lastFullDate.toString());
            g.writeEndObject();
            for (Map.Entry<String, Stamp> e : files.entrySet()) {
                g.writeStartObject();
                g.writeStringField("p", e.getKey());
                g.writeNumberField("s", e.getValue().size());
                g.writeNumberField("m", e.getValue().mtimeMillis());
                g.writeEndObject();
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomic) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
