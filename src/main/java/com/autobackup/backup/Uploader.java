package com.autobackup.backup;

import com.autobackup.config.AppConfig;
import com.autobackup.pan.PanClient;
import com.autobackup.pan.PanException;
import com.autobackup.pan.PrecreateResult;
import com.autobackup.util.HttpNetworkException;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 分片上传: 计算分片 md5 → 预上传(precreate) → 逐片上传(superfile2) → 合并(create).
 * 单个文件最多 1024 个分片(4MB 分片即上限 4GB).
 */
public class Uploader {

    private final PanClient pan;
    private final AppConfig config;
    private final LogService log;

    public Uploader(PanClient pan, AppConfig config, LogService log) {
        this.pan = pan;
        this.config = config;
        this.log = log;
    }

    /** 上传本地文件到远程路径, 返回文件 fs_id. */
    public long upload(Path localFile, String remotePath) throws IOException {
        long size = Files.size(localFile);
        int chunkSize = config.chunkSizeBytes();
        int chunkCount = (int) ((size + chunkSize - 1) / chunkSize);
        if (chunkCount > 1024) {
            throw new IOException("文件过大: " + localFile.getFileName() + " " + size + " 字节, 超过 1024 分片上限(当前分片 "
                    + chunkSize / 1048576 + "MB). 可增大 upload.chunkSizeMB(会员 16/超级会员 32), "
                    + "或配置 upload.splitSizeMB 分卷使单个文件不超过账号单文件上限");
        }

        log.info("开始上传 " + localFile.getFileName() + " -> " + remotePath
                + " (" + size + " 字节, " + Math.max(chunkCount, 1) + " 个分片)");
        List<String> blockList = computeBlockList(localFile, chunkSize);
        PrecreateResult pre = pan.precreate(remotePath, size, blockList);
        if (pre.rapidUpload()) {
            log.info("秒传成功(云端已有相同内容, 未重新上传)");
            return pre.fsId();
        }
        // 官方文档: 预上传返回的 block_list 为空数组时等价于 [0], 仍需上传第 0 片
        List<Integer> parts = pre.parts().isEmpty() ? List.of(0) : pre.parts();
        for (int seq : parts) {
            byte[] chunk = readChunk(localFile, seq, chunkSize);
            uploadChunkWithRetry(remotePath, pre.uploadId(), seq, chunk, blockList.get(seq));
        }
        long fsId = pan.create(remotePath, size, pre.uploadId(), blockList);
        log.info("上传完成: fs_id=" + fsId);
        return fsId;
    }

    /** 单次流式读取, 计算每个分片的 md5(小写十六进制). */
    private List<String> computeBlockList(Path file, int chunkSize) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<String> blocks = new ArrayList<>();
        byte[] buffer = new byte[64 * 1024];
        int position = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                int offset = 0;
                while (offset < n) {
                    int take = Math.min(n - offset, chunkSize - position);
                    digest.update(buffer, offset, take);
                    position += take;
                    offset += take;
                    if (position == chunkSize) {
                        blocks.add(HexFormat.of().formatHex(digest.digest()));
                        position = 0;
                    }
                }
            }
        }
        if (position > 0 || blocks.isEmpty()) {
            blocks.add(HexFormat.of().formatHex(digest.digest()));
        }
        log.info("分片校验值计算完成, 共 " + blocks.size() + " 片");
        return blocks;
    }

    private byte[] readChunk(Path file, int seq, int chunkSize) throws IOException {
        long offset = (long) seq * chunkSize;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            int len = (int) Math.min(chunkSize, raf.length() - offset);
            byte[] data = new byte[len];
            raf.readFully(data);
            return data;
        }
    }

    private void uploadChunkWithRetry(String remotePath, String uploadId, int seq,
                                      byte[] chunk, String expectedMd5) {
        int attempts = Math.max(config.uploadRetries(), 1);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String md5 = pan.uploadChunk(remotePath, uploadId, seq, chunk);
                if (!md5.isBlank() && !md5.equalsIgnoreCase(expectedMd5)) {
                    throw new IllegalStateException("分片 md5 不一致 seq=" + seq
                            + " 本地=" + expectedMd5 + " 远端=" + md5);
                }
                log.info("分片 " + seq + " 上传成功 (" + chunk.length / 1024 + " KB)");
                return;
            } catch (RuntimeException e) {
                if (e instanceof PanException pe && pe.isTokenExpired()) {
                    throw e;   // 令牌过期交由上层统一刷新重试, 不在分片层重试
                }
                if (e instanceof HttpNetworkException) {
                    pan.invalidateUploadServer();   // 定位域名不可达, 换默认上传域名再试
                }
                last = e;
                log.warn("分片 " + seq + " 第 " + attempt + " 次上传失败: " + e.getMessage());
                if (attempt < attempts) {
                    try {
                        Thread.sleep((1L << attempt) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("上传被中断", ie);
                    }
                }
            }
        }
        throw new IllegalStateException("分片 " + seq + " 上传失败(已重试 " + attempts + " 次)", last);
    }
}
