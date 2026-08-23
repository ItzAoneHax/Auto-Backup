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

    /**
     * 令牌无效/过期, 上层应刷新令牌后重试.
     * 官方公共错误码: -6 身份验证失败, 20016 access_token 已过期,
     * 20017 access_token 无效, 31045 access_token 验证未通过.
     */
    public boolean isTokenExpired() {
        return errno == -6 || errno == 20016 || errno == 20017 || errno == 31045;
    }

    /** 111: 有其他异步任务正在执行, 官方建议稍后重试. */
    public boolean isAsyncBusy() {
        return errno == 111;
    }
}
