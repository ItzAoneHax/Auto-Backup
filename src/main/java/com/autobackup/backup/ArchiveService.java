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

/** 将本地目录打包为 tar.gz, 支持 glob 排除规则. */
public class ArchiveService {

    private static final int COPY_BUFFER = 64 * 1024;

    public record ArchiveResult(Path file, long size, int fileCount) {}

    public ArchiveResult createArchive(Path sourceDir, String rootName, Path targetFile,
                                       List<String> excludeGlobs, LogService log) throws IOException {
        List<PathMatcher> matchers = compileGlobs(excludeGlobs);
        int[] count = {0};
        try (OutputStream fos = Files.newOutputStream(targetFile);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(fos);
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
        }
        return new ArchiveResult(targetFile, Files.size(targetFile), count[0]);
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
