package com.autobackup.backup;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

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
import java.util.List;

/** 将本地目录打包为 tar.gz, 支持 glob 排除规则与分卷(超过单文件上限时切为多个分卷文件). */
public class ArchiveService {

    private static final int COPY_BUFFER = 64 * 1024;

    /**
     * @param splitBytes 单个分卷的最大字节数, 0 表示不分卷(整个归档写成一个文件);
     *                   大于 0 时输出 base.part001、base.part002...(base 即传入的 targetFile 路径),
     *                   各分卷按顺序拼接后与完整 tar.gz 字节一致
     */
    public record ArchiveResult(List<Path> parts, long totalSize, int fileCount) {}

    public ArchiveResult createArchive(Path sourceDir, String rootName, Path targetFile,
                                       List<String> excludeGlobs, long splitBytes, LogService log) throws IOException {
        List<PathMatcher> matchers = compileGlobs(excludeGlobs);
        int[] count = {0};
        SplitOutputStream split = new SplitOutputStream(targetFile, splitBytes);
        try (OutputStream ignored = split;
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(split);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
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
                    TarArchiveEntry entry = new TarArchiveEntry(rootName + "/" + rel);
                    entry.setSize(attrs.size());
                    entry.setModTime(attrs.lastModifiedTime().toMillis());
                    tar.putArchiveEntry(entry);
                    try (InputStream in = Files.newInputStream(file)) {
                        copy(in, tar);
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
        return new ArchiveResult(split.parts(), split.totalBytes(), count[0]);
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

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }
}
