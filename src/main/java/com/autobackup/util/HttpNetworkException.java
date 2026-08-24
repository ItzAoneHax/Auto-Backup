package com.autobackup.util;

/** 网络层失败(连接不上/超时等), 区别于业务错误, 便于上层切换备用服务器重试. */
public class HttpNetworkException extends IllegalStateException {
    public HttpNetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
