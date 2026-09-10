package com.autobackup;

import com.autobackup.backup.LogService;
import com.autobackup.backup.Uploader;
import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.PanException;
import com.autobackup.pan.PrecreateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploaderTest {

    @TempDir
    Path temp;

    @Test
    void uploadsChunksConcurrentlyAndMerges() throws Exception {
        AppConfig config = testConfig("upload.parallelChunks=3\nupload.retries=1\n");
        FakePan pan = new FakePan(new CyclicBarrier(3));
        long fsId = new Uploader(pan, config, LogService.consoleOnly())
                .upload(randomFile(3), "/apps/test/daily/x.tar.zst");

        assertEquals(42, fsId);
        assertEquals(1, pan.createCalls.get());
        assertEquals(Set.of(0, 1, 2), pan.uploadedSeqs);
        assertTrue(pan.barrierTripped.get() > 0,
                "三个分片未真正并发(屏障未触发), 实测最大并发=" + pan.maxInflight.get());
    }

    @Test
    void serialModeUploadsOneChunkAtATime() throws Exception {
        AppConfig config = testConfig("upload.parallelChunks=1\nupload.retries=1\n");
        FakePan pan = new FakePan(null);
        new Uploader(pan, config, LogService.consoleOnly())
                .upload(randomFile(3), "/apps/test/daily/x.tar.zst");

        assertEquals(1, pan.maxInflight.get());
        assertEquals(Set.of(0, 1, 2), pan.uploadedSeqs);
    }

    @Test
    void failsWholeFileWhenChunkExhaustsRetries() throws Exception {
        AppConfig config = testConfig("upload.parallelChunks=2\nupload.retries=1\n");
        FakePan pan = new FakePan(null);
        pan.failSeq = 1;

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new Uploader(pan, config, LogService.consoleOnly())
                        .upload(randomFile(3), "/apps/test/daily/x.tar.zst"));

        assertTrue(e.getMessage().contains("分片 1"));
        assertEquals(0, pan.createCalls.get(), "分片失败后不应调用合并接口");
    }

    /** 3 个 4MB 分片的随机文件. */
    private Path randomFile(int chunks) throws IOException {
        Path file = temp.resolve("data.bin");
        byte[] data = new byte[chunks * 4 * 1024 * 1024];
        new java.util.Random(42).nextBytes(data);
        Files.write(file, data);
        return file;
    }

    private AppConfig testConfig(String extra) throws IOException {
        Path file = temp.resolve("application.properties");
        Files.writeString(file, """
                pan.appKey=k
                pan.secretKey=s
                pan.remoteDir=/apps/test
                backup.sources=/tmp/a
                upload.chunkSizeMB=4
                """ + extra);
        return AppConfig.load(file);
    }

    /** 内存版网盘客户端: 返回真实 md5 通过校验, 屏障用于确定性验证并发, 可指定必失败分片. */
    static final class FakePan extends PanClient {
        final CyclicBarrier barrier;
        final AtomicInteger inflight = new AtomicInteger();
        final AtomicInteger maxInflight = new AtomicInteger();
        final AtomicInteger barrierTripped = new AtomicInteger();
        final Set<Integer> uploadedSeqs = Collections.synchronizedSet(new LinkedHashSet<>());
        final AtomicInteger createCalls = new AtomicInteger();
        volatile int failSeq = -1;

        FakePan(CyclicBarrier barrier) {
            super("", null);
            this.barrier = barrier;
        }

        @Override
        public PrecreateResult precreate(String path, long size, List<String> blockList) {
            List<Integer> parts = new ArrayList<>();
            for (int i = 0; i < blockList.size(); i++) parts.add(i);
            return new PrecreateResult("uid", parts, false, 0);
        }

        @Override
        public String uploadChunk(String path, String uploadId, int partSeq, byte[] data) {
            int cur = inflight.incrementAndGet();
            maxInflight.accumulateAndGet(cur, Math::max);
            try {
                if (barrier != null) {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        barrierTripped.incrementAndGet();
                    } catch (Exception e) {
                        // 屏障超时=并发不足, 不在这里失败, 由断言读取 barrierTripped 判定
                    }
                }
                if (partSeq == failSeq) throw new PanException(2, "模拟分片失败");
                uploadedSeqs.add(partSeq);
                return md5(data);
            } finally {
                inflight.decrementAndGet();
            }
        }

        @Override
        public long create(String path, long size, String uploadId, List<String> blockList) {
            createCalls.incrementAndGet();
            return 42;
        }

        private static String md5(byte[] data) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
