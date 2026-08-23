package com.autobackup.pan;

/** 网盘接口错误(errno 非 0 等). */
public class PanException extends RuntimeException {

    private final int errno;

    public PanException(String message) {
        this(-1, message);
    }

    public PanException(int errno, String message) {
        super(message + " [errno=" + errno + "]");
        this.errno = errno;
    }

    /** 令牌过期/失效(111: access token 过期; -6: 令牌非法). */
    public boolean isTokenExpired() {
        return errno == 111 || errno == -6;
    }
}
