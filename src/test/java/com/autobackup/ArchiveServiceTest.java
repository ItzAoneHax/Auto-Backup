package com.autobackup;

import com.autobackup.backup.ArchiveService;
import com.autobackup.backup.LogService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class ArchiveServiceTest {

    @TempDir
    Path temp;

    @Test
    void archivesDirectoryTreeWithRootPrefix() throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("a.txt"), "hello");
        Files.writeString(source.resolve("sub/b.txt"), "world");

        Path target = temp.resolve("out.tar.gz");
        ArchiveService.ArchiveResult result = new ArchiveService()
                .createArchive(source, "data", target, List.of(), 0, LogService.consoleOnly());

        List<String> entries = readEntries(target);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/a.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/sub/b.txt"));
        org.junit.jupiter.api.Assertions.assertEquals(2, result.fileCount());
        org.junit.jupiter.api.Assertions.assertTrue(result.totalSize() > 0);
        org.junit.jupiter.api.Assertions.assertEquals(1, result.parts().size());
    }

    @Test
    void respectsExcludeGlobs() throws Exception {
        Path source = temp.resolve("app");
        Files.createDirectories(source.resolve("cache"));
        Files.writeString(source.resolve("main.conf"), "config");
        Files.writeString(source.resolve("cache/blob.bin"), "cache-data");
        Files.writeString(source.resolve("debug.log"), "log");

        Path target = temp.resolve("out2.tar.gz");
        new ArchiveService().createArchive(source, "app", target,
                List.of("cache/**", "*.log"), 0, LogService.consoleOnly());

        List<String> entries = readEntries(target);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("app/main.conf"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("app/cache/blob.bin"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("app/debug.log"));
    }

    @Test
    void splitsIntoPartsWhoseConcatenationIsReadable() throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source.resolve("sub"));
        Random random = new Random(42);
        writeRandom(source.resolve("a.txt"), random, 4096);
        writeRandom(source.resolve("b.txt"), random, 4096);
        writeRandom(source.resolve("sub/c.txt"), random, 4096);

        Path base = temp.resolve("split.tar.gz");
        ArchiveService.ArchiveResult result = new ArchiveService()
                .createArchive(source, "data", base, List.of(), 500, LogService.consoleOnly());

        org.junit.jupiter.api.Assertions.assertTrue(result.parts().size() >= 2);
        for (int i = 0; i < result.parts().size(); i++) {
            String expected = "split.tar.gz" + String.format(".part%03d", i + 1);
            org.junit.jupiter.api.Assertions.assertEquals(expected, result.parts().get(i).getFileName().toString());
            org.junit.jupiter.api.Assertions.assertTrue(Files.size(result.parts().get(i)) <= 500,
                    "分卷超过大小限制: " + result.parts().get(i));
        }

        // 顺序拼接所有分卷后应等价于完整的 tar.gz
        Path joined = temp.resolve("joined.tar.gz");
        try (OutputStream out = Files.newOutputStream(joined)) {
            for (Path part : result.parts()) {
                Files.copy(part, out);
            }
        }
        List<String> entries = readEntries(joined);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/a.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/b.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/sub/c.txt"));
    }

    @Test
    void singlePartWhenBelowSplitLimit() throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source);
        Files.writeString(source.resolve("a.txt"), "hello");

        Path base = temp.resolve("one.tar.gz");
        ArchiveService.ArchiveResult result = new ArchiveService()
                .createArchive(source, "data", base, List.of(), 64 * 1024 * 1024, LogService.consoleOnly());

        // 未超限: 单卷不携带 .part001 后缀, 保持完整可直解的 .tar.gz 名称
        org.junit.jupiter.api.Assertions.assertEquals(1, result.parts().size());
        org.junit.jupiter.api.Assertions.assertEquals("one.tar.gz",
                result.parts().get(0).getFileName().toString());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(base.resolveSibling("one.tar.gz.part001")));
        List<String> entries = readEntries(base);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/a.txt"));
    }

    /** 随机内容不可压缩, 保证归档体积稳定超过分卷阈值. */
    private static void writeRandom(Path file, Random random, int size) throws Exception {
        byte[] data = new byte[size];
        random.nextBytes(data);
        Files.write(file, data);
    }

    private static List<String> readEntries(Path tarGz) throws Exception {
        List<String> entries = new ArrayList<>();
        try (InputStream in = Files.newInputStream(tarGz);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.add(entry.getName());
            }
        }
        return entries;
    }
}
