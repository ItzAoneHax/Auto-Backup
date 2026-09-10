package com.autobackup;

import com.autobackup.backup.LogService;
import com.autobackup.backup.RetentionService;
import com.autobackup.backup.Uploader;
import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.PanException;
import com.autobackup.pan.RemoteFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void parsesDayFolderNamesStrictly() {
        assertEquals(LocalDate.of(2026, 8, 27),
                RetentionService.parseDayFolder("2026-08-27"));
        assertNull(RetentionService.parseDayFolder("myserver"));          // 服务器文件夹不解析
        assertNull(RetentionService.parseDayFolder("backup-2026-08-27.log")); // 文件名不是目录名
        assertNull(RetentionService.parseDayFolder("2026-13-45"));
    }

    @Test
    void returnsNullForUnknownOrInvalidNames() {
        assertNull(RetentionService.parseEmbeddedDate("random-file.zip"));
        assertNull(RetentionService.parseEmbeddedDate("not-a-date-2026-13-45.txt"));
    }

    // ---- 快照补全逻辑(内存版网盘) ----

    @Test
    void backfillsMissingMonthlyAndYearlyForNewSource(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        RetentionService retention = new RetentionService(pan, config, LogService.consoleOnly());

        retention.backfillSnapshots(Map.of("newsrc", List.of(
                dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz"),
                dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz.part001"))));

        assertEquals(4, pan.copies.size());
        assertTrue(pan.copiedTo(monthlyDir("newsrc") + "/newsrc-2026-09-05_030000.tar.gz"));
        assertTrue(pan.copiedTo(monthlyDir("newsrc") + "/newsrc-2026-09-05_030000.tar.gz.part001"));
        assertTrue(pan.copiedTo(yearlyDir("newsrc") + "/newsrc-2026-09-05_030000.tar.gz"));
        assertTrue(pan.copiedTo(yearlyDir("newsrc") + "/newsrc-2026-09-05_030000.tar.gz.part001"));
    }

    @Test
    void skipsBackfillWhenSnapshotAlreadyHasOtherBatch(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        // 本月已有其他批次(月初)的快照, 不应重复留档
        pan.put(monthlyDir("newsrc"), "newsrc-2026-09-01_030000.tar.gz");
        RetentionService retention = new RetentionService(pan, config, LogService.consoleOnly());

        retention.backfillSnapshots(Map.of("newsrc",
                List.of(dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz"))));

        assertTrue(pan.copies.stream().noneMatch(c -> c[1].contains("/monthly/")));
        assertTrue(pan.copiedTo(yearlyDir("newsrc") + "/newsrc-2026-09-05_030000.tar.gz"));
    }

    @Test
    void backfillRetriesQuietlyNextRunWhenCopyFails(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        pan.failAllCopies = true;
        RetentionService retention = new RetentionService(pan, config, LogService.consoleOnly());

        // 复制失败只告警不抛出, 下次运行会重试
        retention.backfillSnapshots(Map.of("newsrc",
                List.of(dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz"))));

        assertTrue(pan.copies.isEmpty());
    }

    @Test
    void completesInterruptedSnapshotCopyWithMissingPartsOnly(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        // 上次复制中断, 只留下了第一个分卷
        pan.put(monthlyDir("newsrc"), "newsrc-2026-09-05_030000.tar.gz.part000");
        RetentionService retention = new RetentionService(pan, config, LogService.consoleOnly());
        FakeUploader uploader = new FakeUploader(pan, config);

        retention.ensureMonthlySnapshot(uploader,
                List.of(Path.of("work/newsrc-2026-09-05_030000.tar.gz.part000"),
                        Path.of("work/newsrc-2026-09-05_030000.tar.gz.part001")),
                List.of(dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz.part000"),
                        dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz.part001")),
                "newsrc");

        assertEquals(List.of(dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz.part001")
                        + ">" + monthlyDir("newsrc") + "/newsrc-2026-09-05_030000.tar.gz.part001"),
                pan.copies.stream().map(c -> c[0] + ">" + c[1]).toList());
        assertTrue(uploader.uploads.isEmpty());   // 云端复制成功, 无需本地上传
    }

    @Test
    void skipsEnsureWhenSnapshotComplete(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        pan.put(monthlyDir("newsrc"), "newsrc-2026-09-01_030000.tar.gz");
        pan.put(yearlyDir("newsrc"), "newsrc-2026-09-01_030000.tar.gz");
        RetentionService retention = new RetentionService(pan, config, LogService.consoleOnly());
        FakeUploader uploader = new FakeUploader(pan, config);

        retention.ensureMonthlySnapshot(uploader,
                List.of(Path.of("work/newsrc-2026-09-05_030000.tar.gz")),
                List.of(dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz")),
                "newsrc");
        retention.ensureYearlySnapshot(uploader,
                List.of(Path.of("work/newsrc-2026-09-05_030000.tar.gz")),
                List.of(dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz")),
                "newsrc");

        assertTrue(pan.copies.isEmpty());
        assertTrue(uploader.uploads.isEmpty());
    }

    @Test
    void fallsBackToLocalUploadWhenCopyFails(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        pan.failAllCopies = true;
        RetentionService retention = new RetentionService(pan, config, LogService.consoleOnly());
        FakeUploader uploader = new FakeUploader(pan, config);

        retention.ensureMonthlySnapshot(uploader,
                List.of(Path.of("work/newsrc-2026-09-05_030000.tar.gz.part000"),
                        Path.of("work/newsrc-2026-09-05_030000.tar.gz.part001")),
                List.of(dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz.part000"),
                        dailyPath("newsrc", "newsrc-2026-09-05_030000.tar.gz.part001")),
                "newsrc");

        assertEquals(2, uploader.uploads.size());
        assertTrue(uploader.uploads.contains("newsrc-2026-09-05_030000.tar.gz.part000"));
        assertTrue(uploader.uploads.contains("newsrc-2026-09-05_030000.tar.gz.part001"));
    }

    // ---- 每日文件夹自包含恢复链 ----

    @Test
    void mirrorsFullAndPriorIncrementalsIntoTodayFolder(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        LocalDate today = LocalDate.now();
        // 两天前的全量(含两个分卷) + 昨天的增量; 中间跳过一天(该日无备份)
        String fullDay = config.dailyDayDir(today.minusDays(2)) + "/newsrc";
        pan.put(fullDay, "newsrc-" + today.minusDays(2) + "_030000.tar.zst",
                "newsrc-" + today.minusDays(2) + "_030000.tar.zst.part002");
        String incDay = config.dailyDayDir(today.minusDays(1)) + "/newsrc";
        pan.put(incDay, "newsrc-" + today.minusDays(1) + "_030000.inc.tar.zst",
                "backup-" + today.minusDays(1) + "_030000.log");   // 日志不应被复制
        String todayDir = config.dailyDayDir(today) + "/newsrc";
        pan.put(todayDir, "newsrc-" + today + "_030000.inc.tar.zst");   // 今日增量已上传

        new RetentionService(pan, config, LogService.consoleOnly())
                .mirrorChainIntoToday("newsrc", todayDir, today.minusDays(2));

        assertTrue(pan.copiedTo(todayDir + "/newsrc-" + today.minusDays(2) + "_030000.tar.zst"));
        assertTrue(pan.copiedTo(todayDir + "/newsrc-" + today.minusDays(2) + "_030000.tar.zst.part002"));
        assertTrue(pan.copiedTo(todayDir + "/newsrc-" + today.minusDays(1) + "_030000.inc.tar.zst"));
        assertEquals(3, pan.copies.size());   // 日志与今日已有的增量不会被复制
    }

    @Test
    void skipsMirroringWhenChainHasNoFullArchive(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        LocalDate today = LocalDate.now();
        // 锚定日只有增量没有全量(链已断), 不应复制任何东西
        pan.put(config.dailyDayDir(today.minusDays(1)) + "/newsrc",
                "newsrc-" + today.minusDays(1) + "_030000.inc.tar.zst");
        String todayDir = config.dailyDayDir(today) + "/newsrc";

        new RetentionService(pan, config, LogService.consoleOnly())
                .mirrorChainIntoToday("newsrc", todayDir, today.minusDays(2));

        assertTrue(pan.copies.isEmpty());
    }

    @Test
    void ignoresArchivesOfOtherSources(@TempDir Path temp) throws IOException {
        AppConfig config = testConfig(temp);
        FakePan pan = new FakePan();
        LocalDate today = LocalDate.now();
        // 同文件夹里混入其他源的归档(前缀不同), 不应复制
        String fullDay = config.dailyDayDir(today.minusDays(1)) + "/newsrc";
        pan.put(fullDay, "newsrc-" + today.minusDays(1) + "_030000.tar.zst",
                "other-2026-01-01_030000.tar.zst", "newsrc-残缺文件.tar.zst");
        String todayDir = config.dailyDayDir(today) + "/newsrc";
        pan.put(todayDir, "newsrc-" + today + "_030000.inc.tar.zst");

        new RetentionService(pan, config, LogService.consoleOnly())
                .mirrorChainIntoToday("newsrc", todayDir, today.minusDays(1));

        assertTrue(pan.copiedTo(todayDir + "/newsrc-" + today.minusDays(1) + "_030000.tar.zst"));
        assertTrue(pan.copies.stream().noneMatch(c -> c[1].contains("other-")));
        assertTrue(pan.copies.stream().noneMatch(c -> c[1].contains("残缺文件")));
    }

    // ---- 测试基础设施 ----

    private static String dailyPath(String source, String file) {
        return "/apps/test/daily/2026-09-05/" + source + "/" + file;
    }

    private static String monthlyDir(String source) {
        return "/apps/test/monthly/" + YearMonth.now() + "/" + source;
    }

    private static String yearlyDir(String source) {
        return "/apps/test/yearly/" + LocalDate.now().getYear() + "/" + source;
    }

    private static AppConfig testConfig(Path temp) throws IOException {
        Path file = temp.resolve("application.properties");
        Files.writeString(file, """
                pan.appKey=k
                pan.secretKey=s
                pan.remoteDir=/apps/test
                backup.sources=/tmp/a
                """);
        return AppConfig.load(file);
    }

    /** 内存版网盘客户端: 记录复制动作, 未创建的目录视为不存在. */
    static final class FakePan extends PanClient {
        final Map<String, List<RemoteFile>> dirs = new LinkedHashMap<>();
        final List<String[]> copies = new ArrayList<>();
        boolean failAllCopies;

        FakePan() {
            super("", null);
        }

        void put(String dir, String... names) {
            List<RemoteFile> files = dirs.computeIfAbsent(dir, d -> new ArrayList<>());
            for (String n : names) {
                files.add(new RemoteFile(dir + "/" + n, n, 1, false, 0));
            }
        }

        boolean copiedTo(String to) {
            return copies.stream().anyMatch(c -> c[1].equals(to));
        }

        @Override
        public void mkdirs(String remoteDir) {
            dirs.computeIfAbsent(remoteDir, d -> new ArrayList<>());
        }

        @Override
        public List<RemoteFile> list(String dir) {
            List<RemoteFile> files = dirs.get(dir);
            if (files == null) throw new PanException(-9, "文件或目录不存在: " + dir);
            return files;
        }

        @Override
        public void copy(String fromPath, String toPath) {
            if (failAllCopies) throw new PanException(2, "模拟复制失败");
            copies.add(new String[]{fromPath, toPath});
        }
    }

    /** 只记录上传动作的假上传器. */
    static final class FakeUploader extends Uploader {
        final List<String> uploads = new ArrayList<>();

        FakeUploader(PanClient pan, AppConfig config) {
            super(pan, config, LogService.consoleOnly());
        }

        @Override
        public long upload(Path localFile, String remotePath) {
            uploads.add(localFile.getFileName().toString());
            return 1;
        }
    }
}
