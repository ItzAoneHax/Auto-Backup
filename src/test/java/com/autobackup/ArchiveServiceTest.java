package com.autobackup;

import com.autobackup.backup.ArchiveService;
import com.autobackup.backup.LogService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
                .createArchive(source, "data", target, List.of(), LogService.consoleOnly());

        List<String> entries = readEntries(target);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/a.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("data/sub/b.txt"));
        org.junit.jupiter.api.Assertions.assertEquals(2, result.fileCount());
        org.junit.jupiter.api.Assertions.assertTrue(result.size() > 0);
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
                List.of("cache/**", "*.log"), LogService.consoleOnly());

        List<String> entries = readEntries(target);
        org.junit.jupiter.api.Assertions.assertTrue(entries.contains("app/main.conf"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("app/cache/blob.bin"));
        org.junit.jupiter.api.Assertions.assertFalse(entries.contains("app/debug.log"));
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
