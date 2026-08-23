package com.autobackup.pan;

/** 网盘上的一个文件/目录条目. */
public record RemoteFile(String path, String name, long size, boolean dir, long serverMtime) {}
