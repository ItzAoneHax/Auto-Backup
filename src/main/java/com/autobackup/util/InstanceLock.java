package com.autobackup.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/** 进程级文件锁, 避免守护进程与手动运行同时备份. */
public final class InstanceLock implements Closeable {

    private final RandomAccessFile raf;
    private final FileLock lock;

    private InstanceLock(RandomAccessFile raf, FileLock lock) {
        this.raf = raf;
        this.lock = lock;
    }

    public static InstanceLock acquire(Path lockFile) throws IOException {
        Path parent = lockFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw");
        FileChannel channel = raf.getChannel();
        FileLock lock = channel.tryLock();
        if (lock == null) {
            raf.close();
            throw new IllegalStateException("已有另一个 Auto-Backup 实例正在运行 (" + lockFile + ")");
        }
        return new InstanceLock(raf, lock);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            raf.close();
        }
    }
}
