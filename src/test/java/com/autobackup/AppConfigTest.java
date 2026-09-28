package com.autobackup;

import com.autobackup.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {

    @Test
    void withSourcesReplacesSourcesAndKeepsOtherConfig(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("application.properties");
        Files.writeString(cfg, """
                pan.appKey=k
                pan.secretKey=s
                pan.remoteDir=/apps/backup
                backup.sources=/nonexistent-default
                backup.retainDays=5
                """);
        AppConfig base = AppConfig.load(cfg);
        Path dir = Files.createDirectory(tmp.resolve("data"));
        AppConfig copy = base.withSources(List.of(dir.toString()));

        assertEquals(List.of(dir.toString()), copy.sources());
        assertEquals("k", copy.appKey());
        assertEquals("/apps/backup", copy.remoteDir());
        assertEquals("/apps/backup/adhoc/2026-09-28", copy.adhocDayDir(java.time.LocalDate.of(2026, 9, 28)));
        assertEquals(5, copy.retainDays());
        assertEquals(base.tokenFile(), copy.tokenFile());

        // 替换后的副本可直接通过备份校验(命令行路径存在性由同一处校验覆盖)
        assertDoesNotThrow(copy::validateForBackup);
        // 原配置不受影响, 其默认源仍不存在
        assertThrows(IllegalStateException.class, base::validateForBackup);
        assertEquals(List.of("/nonexistent-default"), base.sources());
    }

    @Test
    void adhocRunRejectsNonexistentPath(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("application.properties");
        Files.writeString(cfg, """
                pan.appKey=k
                pan.secretKey=s
                pan.remoteDir=/apps/backup
                backup.sources=%s
                """.formatted(tmp.toString()));
        AppConfig config = AppConfig.load(cfg).withSources(List.of("/no/such/dir"));

        IllegalStateException e = assertThrows(IllegalStateException.class, config::validateForBackup);
        assertEquals(true, e.getMessage().contains("备份目录不存在"));
    }
}
