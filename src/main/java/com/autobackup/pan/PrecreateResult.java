package com.autobackup.pan;

import java.util.List;

/** 预上传(precreate)结果: 需要上传的分片序号; 秒传时无需后续请求. */
public record PrecreateResult(String uploadId, List<Integer> parts, boolean rapidUpload, long fsId) {}
