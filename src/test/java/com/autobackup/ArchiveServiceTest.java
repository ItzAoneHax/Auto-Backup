package com.autobackup;

import com.autobackup.backup.ArchiveService;
import com.autobackup.backup.LogService;
import com.autobackup.backup.SourceManifest;
import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

class ArchiveServiceTest {

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {ArchiveService.Compression.ZSTD, ArchiveService.Compression.GZIP})
    void archivesDirectoryTreeWithRootPrefix(String algorithm) throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("a.txt"), "hello");
        Files.writeString(source.resolve("sub/b.txt"), "world");

        Path target = temp.resolve("out." + compression(algorithm).extension());
        ArchiveService.ArchiveResult result = new ArchiveService()
                .createArchive(source, "data", target, List.of(), 0, compression(algorithm), null,
                        LogService.consoleOnly());

        List<String> entries = readEntries(target, algorithm);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/a.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/sub/b.txt"));
        org.junit.jupiter.api.Assertions.assertEquals(2, result.fileCount());
        org.junit.jupiter.api.Assertions.assertEquals(2, result.scannedCount());
        org.junit.jupiter.api.Assertions.assertEquals(2, result.stamps().size());
        org.junit.jupiter.api.Assertions.assertTrue(result.totalSize() > 0);
        org.junit.jupiter.api.Assertions.assertEquals(1, result.parts().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {ArchiveService.Compression.ZSTD, ArchiveService.Compression.GZIP})
    void respectsExcludeGlobs(String algorithm) throws Exception {
        Path source = temp.resolve("app");
        Files.createDirectories(source.resolve("cache"));
        Files.writeString(source.resolve("main.conf"), "config");
        Files.writeString(source.resolve("cache/blob.bin"), "cache-data");
        Files.writeString(source.resolve("debug.log"), "log");

        Path target = temp.resolve("out2." + compression(algorithm).extension());
        new ArchiveService().createArchive(source, "app", target,
                List.of("cache/**", "*.log"), 0, compression(algorithm), null, LogService.consoleOnly());

        List<String> entries = readEntries(target, algorithm);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("app/main.conf"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("app/cache/blob.bin"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("app/debug.log"));
    }

    @ParameterizedTest
    @ValueSource(strings = {ArchiveService.Compression.ZSTD, ArchiveService.Compression.GZIP})
    void splitsIntoPartsWhoseConcatenationIsReadable(String algorithm) throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source.resolve("sub"));
        Random random = new Random(42);
        writeRandom(source.resolve("a.txt"), random, 4096);
        writeRandom(source.resolve("b.txt"), random, 4096);
        writeRandom(source.resolve("sub/c.txt"), random, 4096);

        String ext = compression(algorithm).extension();
        Path base = temp.resolve("split." + ext);
        ArchiveService.ArchiveResult result = new ArchiveService()
                .createArchive(source, "data", base, List.of(), 500, compression(algorithm), null,
                        LogService.consoleOnly());

        org.junit.jupiter.api.Assertions.assertTrue(result.parts().size() >= 2);
        for (int i = 0; i < result.parts().size(); i++) {
            String expected = "split." + ext + String.format(".part%03d", i + 1);
            org.junit.jupiter.api.Assertions.assertEquals(expected, result.parts().get(i).getFileName().toString());
            org.junit.jupiter.api.Assertions.assertTrue(Files.size(result.parts().get(i)) <= 500,
                    "分卷超过大小限制: " + result.parts().get(i));
        }

        // 顺序拼接所有分卷后应等价于完整的压缩归档
        Path joined = temp.resolve("joined." + ext);
        try (OutputStream out = Files.newOutputStream(joined)) {
            for (Path part : result.parts()) {
                Files.copy(part, out);
            }
        }
        List<String> entries = readEntries(joined, algorithm);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/a.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/b.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/sub/c.txt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {ArchiveService.Compression.ZSTD, ArchiveService.Compression.GZIP})
    void singlePartWhenBelowSplitLimit(String algorithm) throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source);
        Files.writeString(source.resolve("a.txt"), "hello");

        String ext = compression(algorithm).extension();
        Path base = temp.resolve("one." + ext);
        ArchiveService.ArchiveResult result = new ArchiveService()
                .createArchive(source, "data", base, List.of(), 64 * 1024 * 1024, compression(algorithm),
                        null, LogService.consoleOnly());

        // 未超限: 单卷不携带 .part001 后缀, 保持完整可直解的归档名称
        org.junit.jupiter.api.Assertions.assertEquals(1, result.parts().size());
        org.junit.jupiter.api.Assertions.assertEquals("one." + ext,
                result.parts().get(0).getFileName().toString());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(base.resolveSibling("one." + ext + ".part001")));
        List<String> entries = readEntries(base, algorithm);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/a.txt"));
    }

    // ---- 增量归档 ----

    @Test
    void incrementalArchiveOnlyContainsChangedFiles() throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("same.txt"), "unchanged");
        Files.writeString(source.resolve("gone.txt"), "will be deleted");
        Files.writeString(source.resolve("mod.txt"), "v1");

        ArchiveService archive = new ArchiveService();
        ArchiveService.ArchiveResult full = archive.createArchive(source, "data",
                temp.resolve("full.tar.zst"), List.of(), 0, zstd(), null, LogService.consoleOnly());
        org.junit.jupiter.api.Assertions.assertEquals(3, full.fileCount());

        // 变更: 修改 mod.txt、新增 new.txt、删除 gone.txt
        Files.writeString(source.resolve("mod.txt"), "v2");
        Files.writeString(source.resolve("new.txt"), "fresh");
        Files.delete(source.resolve("gone.txt"));

        ArchiveService.ArchiveResult inc = archive.createArchive(source, "data",
                temp.resolve("inc.tar.zst"), List.of(), 0, zstd(), full.stamps(), LogService.consoleOnly());

        org.junit.jupiter.api.Assertions.assertEquals(3, inc.scannedCount());
        org.junit.jupiter.api.Assertions.assertEquals(2, inc.fileCount());
        List<String> entries = readEntries(temp.resolve("inc.tar.zst"), ArchiveService.Compression.ZSTD);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/mod.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/new.txt"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("data/same.txt"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("data/gone.txt"));
        // 新清单已不含被删除的文件
        org.junit.jupiter.api.Assertions.assertFalse(inc.stamps().containsKey("gone.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(inc.stamps().containsKey("new.txt"));
    }

    @Test
    void incrementalWithNoChangesProducesEmptyArchive() throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source);
        Files.writeString(source.resolve("a.txt"), "hello");

        ArchiveService archive = new ArchiveService();
        ArchiveService.ArchiveResult full = archive.createArchive(source, "data",
                temp.resolve("f.tar.zst"), List.of(), 0, zstd(), null, LogService.consoleOnly());

        ArchiveService.ArchiveResult inc = archive.createArchive(source, "data",
                temp.resolve("i.tar.zst"), List.of(), 0, zstd(), full.stamps(), LogService.consoleOnly());

        org.junit.jupiter.api.Assertions.assertEquals(0, inc.fileCount());
        org.junit.jupiter.api.Assertions.assertEquals(1, inc.scannedCount());
        org.junit.jupiter.api.Assertions.assertEquals(full.stamps(), inc.stamps());
    }

    @Test
    void zstdWorkersAccelerateCompressionWithoutBreakingFormat() throws Exception {
        Path source = temp.resolve("data");
        Files.createDirectories(source);
        Random random = new Random(7);
        writeRandom(source.resolve("blob.bin"), random, 512 * 1024);

        new ArchiveService().createArchive(source, "data", temp.resolve("mt.tar.zst"), List.of(), 0,
                new ArchiveService.Compression(ArchiveService.Compression.ZSTD, 3, 4),
                null, LogService.consoleOnly());

        org.junit.jupiter.api.Assertions.assertTrue(
                readEntries(temp.resolve("mt.tar.zst"), ArchiveService.Compression.ZSTD)
                        .contains("data/blob.bin"));
    }

    // ---- 增量清单持久化 ----

    @Test
    void manifestRoundTripsThroughDisk() throws Exception {
        Path source = temp.resolve("src");
        Files.createDirectories(source);
        Path manifestFile = temp.resolve("manifests").resolve("src.json");

        SourceManifest saved = new SourceManifest(source.toAbsolutePath().normalize().toString(),
                java.time.LocalDate.of(2026, 9, 10),
                Map.of("a.txt", new SourceManifest.Stamp(5, 1694300000000L),
                        "目录/中文 文件'.txt", new SourceManifest.Stamp(123, 1694300000001L)));
        saved.save(manifestFile);

        SourceManifest loaded = SourceManifest.load(manifestFile, source, LogService.consoleOnly());
        org.junit.jupiter.api.Assertions.assertNotNull(loaded);
        org.junit.jupiter.api.Assertions.assertEquals(saved.sourcePath(), loaded.sourcePath());
        org.junit.jupiter.api.Assertions.assertEquals(saved.lastFullDate(), loaded.lastFullDate());
        org.junit.jupiter.api.Assertions.assertEquals(saved.files(), loaded.files());
    }

    @Test
    void manifestLoadRejectsMissingMismatchedOrCorruptFile() throws Exception {
        Path source = temp.resolve("src");
        Files.createDirectories(source);
        Path manifestFile = temp.resolve("manifests").resolve("src.json");

        // 不存在 → null
        org.junit.jupiter.api.Assertions.assertNull(SourceManifest.load(manifestFile, source, LogService.consoleOnly()));

        // 源路径不一致 → null
        new SourceManifest("/other/place", null, Map.of("a", new SourceManifest.Stamp(1, 1)))
                .save(manifestFile);
        org.junit.jupiter.api.Assertions.assertNull(SourceManifest.load(manifestFile, source, LogService.consoleOnly()));

        // 内容损坏 → null
        Files.writeString(manifestFile, "{not json at all");
        org.junit.jupiter.api.Assertions.assertNull(SourceManifest.load(manifestFile, source, LogService.consoleOnly()));
    }

    /** 随机内容不可压缩, 保证归档体积稳定超过分卷阈值. */
    private static void writeRandom(Path file, Random random, int size) throws Exception {
        byte[] data = new byte[size];
        random.nextBytes(data);
        Files.write(file, data);
    }

    private static ArchiveService.Compression zstd() {
        return new ArchiveService.Compression(ArchiveService.Compression.ZSTD, 3, 0);
    }

    private static ArchiveService.Compression compression(String algorithm) {
        return ArchiveService.Compression.ZSTD.equals(algorithm)
                ? zstd() : ArchiveService.Compression.gzip();
    }

    private static List<String> readEntries(Path archive, String algorithm) throws Exception {
        List<String> entries = new ArrayList<>();
        try (InputStream in = Files.newInputStream(archive);
             InputStream decompressed = ArchiveService.Compression.ZSTD.equals(algorithm)
                     ? new ZstdInputStream(in) : new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.add(entry.getName());
            }
        }
        return entries;
    }

    @org.junit.jupiter.api.Test
    void copyTruncatesGrownFileAndPadsShrunkFile() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        // 头部声明 4 字节, 实际流 6 字节(打包期间被追加) → 只写出前 4 字节
        ArchiveService.copy(new java.io.ByteArrayInputStream("abcdef".getBytes()),
                out, 4, "growing.log", LogService.consoleOnly());
        org.junit.jupiter.api.Assertions.assertEquals("abcd", out.toString());

        // 头部声明 6 字节, 实际流 3 字节(打包期间被截短) → 零补齐到 6 字节
        java.io.ByteArrayOutputStream padded = new java.io.ByteArrayOutputStream();
        ArchiveService.copy(new java.io.ByteArrayInputStream("abc".getBytes()),
                padded, 6, "shrunk.log", LogService.consoleOnly());
        byte[] bytes = padded.toByteArray();
        org.junit.jupiter.api.Assertions.assertEquals(6, bytes.length);
        org.junit.jupiter.api.Assertions.assertEquals('a', bytes[0]);
        org.junit.jupiter.api.Assertions.assertEquals(0, bytes[5]);
    }
}
