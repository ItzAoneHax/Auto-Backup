package com.autobackup.backup;

import com.github.luben.zstd.ZstdOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将本地目录打包为 tar.zst(或 tar.gz), 支持 glob 排除规则、增量归档
 * (previousStamps 非 null 时只打包与上次清单不一致的文件)与分卷(超过单文件上限时切为多个分卷文件).
 */
public class ArchiveService {

    private static final int COPY_BUFFER = 64 * 1024;

    /** 压缩算法与参数; algorithm 为 "zstd" 或 "gzip", extension() 为对应归档后缀. */
    public record Compression(String algorithm, int level, int workers) {

        public static final String ZSTD = "zstd";
        public static final String GZIP = "gzip";

        /** 兼容旧版本的 gzip 参数(默认级别, 单线程). */
        public static Compression gzip() {
            return new Compression(GZIP, -1, 1);
        }

        public String extension() {
            return ZSTD.equals(algorithm) ? "tar.zst" : "tar.gz";
        }

        /** 实际使用的 zstd 工作线程数(0 表示按 CPU 核数取). */
        public int effectiveWorkers() {
            if (!ZSTD.equals(algorithm)) return 1;
            return workers > 0 ? workers : Runtime.getRuntime().availableProcessors();
        }
    }

    /**
     * @param splitBytes 单个分卷的最大字节数, 0 表示不分卷(整个归档写成一个文件);
     *                   大于 0 时输出 base.part001、base.part002...(base 即传入的 targetFile 路径),
     *                   各分卷按顺序拼接后与完整压缩流字节一致
     * @param previousStamps 上次归档的文件清单(相对路径 → size+mtime 戳), null 表示全量;
     *                      非 null 时 size 与 mtime 均未变化的文件跳过不入包
     */
    public record ArchiveResult(List<Path> parts, long totalSize, int fileCount,
                                int scannedCount, Map<String, SourceManifest.Stamp> stamps) {}

    public ArchiveResult createArchive(Path sourceDir, String rootName, Path targetFile,
                                       List<String> excludeGlobs, long splitBytes,
                                       Compression compression, Map<String, SourceManifest.Stamp> previousStamps,
                                       LogService log) throws IOException {
        List<PathMatcher> matchers = compileGlobs(excludeGlobs);
        int[] count = {0};
        int[] scanned = {0};
        Map<String, SourceManifest.Stamp> stamps = new HashMap<>();
        boolean incremental = previousStamps != null;
        SplitOutputStream split = new SplitOutputStream(targetFile, splitBytes);
        try (OutputStream ignored = split;
             OutputStream compressed = compressedStream(split, compression);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            addDirEntry(tar, rootName);
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String rel = relative(sourceDir, dir);
                    if (excluded(rel, matchers)) return FileVisitResult.SKIP_SUBTREE;
                    if (!rel.isEmpty()) addDirEntry(tar, rootName + "/" + rel);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = relative(sourceDir, file);
                    if (excluded(rel, matchers)) return FileVisitResult.CONTINUE;
                    if (Files.isSymbolicLink(file)) {
                        log.warn("跳过符号链接: " + rel);
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    scanned[0]++;
                    SourceManifest.Stamp cur =
                            new SourceManifest.Stamp(attrs.size(), attrs.lastModifiedTime().toMillis());
                    stamps.put(rel, cur);
                    if (incremental && cur.equals(previousStamps.get(rel))) {
                        return FileVisitResult.CONTINUE;   // size 与 mtime 均未变化, 跳过
                    }
                    TarArchiveEntry entry = new TarArchiveEntry(rootName + "/" + rel);
                    entry.setSize(attrs.size());
                    entry.setModTime(attrs.lastModifiedTime().toMillis());
                    tar.putArchiveEntry(entry);
                    try (InputStream in = Files.newInputStream(file)) {
                        copy(in, tar, attrs.size(), rel, log);
                    }
                    tar.closeArchiveEntry();
                    count[0]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("跳过无法读取的文件: " + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            split.deleteParts();   // 失败的半成品分卷没有恢复价值, 直接清理
            throw e;
        }
        List<Path> parts = split.parts();
        if (splitBytes > 0 && parts.size() == 1) {
            // 未超过分卷阈值时去掉 .part001 后缀, 云端与本地均为完整可直解的压缩归档
            Files.move(parts.get(0), targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            parts = List.of(targetFile);
        }
        return new ArchiveResult(parts, split.totalBytes(), count[0], scanned[0], stamps);
    }

    private static OutputStream compressedStream(OutputStream out, Compression c) throws IOException {
        if (Compression.ZSTD.equals(c.algorithm())) {
            ZstdOutputStream zstd = new ZstdOutputStream(out, c.level());
            zstd.setWorkers(c.effectiveWorkers());   // 必须在写入数据前设置
            zstd.setChecksum(true);
            return zstd;
        }
        GzipParameters params = new GzipParameters();
        if (c.level() > 0) params.setCompressionLevel(c.level());
        return new GzipCompressorOutputStream(out, params);
    }

    /**
     * 顺序写出的分卷输出流: 写满一个分卷后自动切换到下一个.
     * limit &lt;= 0 时行为与普通文件输出流一致(仅 base 一个文件).
     */
    private static final class SplitOutputStream extends OutputStream {
        private final Path base;
        private final long limit;
        private final List<Path> parts = new ArrayList<>();
        private OutputStream out;
        private int index;
        private long written;
        private long totalBytes;

        SplitOutputStream(Path base, long limit) throws IOException {
            this.base = base;
            this.limit = limit;
            openNext();
        }

        private void openNext() throws IOException {
            Path part = limit > 0 ? base.resolveSibling(base.getFileName() + String.format(".part%03d", index + 1)) : base;
            out = Files.newOutputStream(part);
            parts.add(part);
            written = 0;
            index++;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            while (len > 0) {
                if (limit > 0 && written >= limit) {
                    out.close();
                    openNext();
                }
                int take = limit > 0 ? (int) Math.min(len, limit - written) : len;
                out.write(buf, off, take);
                written += take;
                totalBytes += take;
                off += take;
                len -= take;
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        List<Path> parts() {
            return List.copyOf(parts);
        }

        long totalBytes() {
            return totalBytes;
        }

        void deleteParts() {
            for (Path p : parts) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响原始异常的抛出
                }
            }
        }
    }

    private static void addDirEntry(TarArchiveOutputStream tar, String name) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name + "/");
        entry.setModTime(System.currentTimeMillis());
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static List<PathMatcher> compileGlobs(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>();
        FileSystem fs = FileSystems.getDefault();
        for (String glob : globs) {
            matchers.add(fs.getPathMatcher("glob:" + glob));
        }
        return matchers;
    }

    private static boolean excluded(String rel, List<PathMatcher> matchers) {
        if (rel.isEmpty()) return false;
        for (PathMatcher m : matchers) {
            if (m.matches(Path.of(rel))) return true;
        }
        return false;
    }

    private static String relative(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    /**
     * 拷贝至多 limit 字节. 头部尺寸取自打包前的文件属性, 而活跃文件(如服务器日志)在读取期间
     * 可能被追加或截短(日志轮转): 追加则截断到初始尺寸, 截短则零补齐, 保证 tar 流始终自洽,
     * 单个文件的临时变化不再导致整个备份源失败.
     */
    public static void copy(InputStream in, OutputStream out, long limit, String rel, LogService log)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long written = 0;
        int n;
        while (written < limit
                && (n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - written))) != -1) {
            out.write(buffer, 0, n);
            written += n;
        }
        if (written < limit) {
            byte[] zeros = new byte[COPY_BUFFER];
            while (written < limit) {
                int len = (int) Math.min(zeros.length, limit - written);
                out.write(zeros, 0, len);
                written += len;
            }
            log.warn("打包期间文件被截短(可能发生日志轮转), 差异部分零补齐: " + rel);
        } else if (in.read() != -1) {
            log.warn("打包期间文件被追加, 已截断到初始尺寸: " + rel);
        }
    }
}
