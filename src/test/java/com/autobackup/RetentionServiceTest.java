package com.autobackup;

import com.autobackup.backup.RetentionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RetentionServiceTest {

    @Test
    void parsesEmbeddedDateFromGeneratedNames() {
        assertEquals(LocalDate.of(2026, 8, 23),
                RetentionService.parseEmbeddedDate("data-2026-08-23_030000.tar.gz"));
        assertEquals(LocalDate.of(2025, 1, 1),
                RetentionService.parseEmbeddedDate("backup-2025-01-01_030000.log"));
        assertEquals(LocalDate.of(2026, 12, 31),
                RetentionService.parseEmbeddedDate("mysql_data-2026-12-31_235959.tar.gz"));
        assertEquals(LocalDate.of(2026, 8, 23),
                RetentionService.parseEmbeddedDate("data-2026-08-23_030000.tar.gz.part001"));
    }

    @Test
    void returnsNullForUnknownOrInvalidNames() {
        assertNull(RetentionService.parseEmbeddedDate("random-file.zip"));
        assertNull(RetentionService.parseEmbeddedDate("not-a-date-2026-13-45.txt"));
    }
}
