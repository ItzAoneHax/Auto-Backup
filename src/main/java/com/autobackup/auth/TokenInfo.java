package com.autobackup.auth;

/** 已保存的 OAuth 凭证. */
public record TokenInfo(String accessToken, String refreshToken, long expiresAtEpochSec) {

    /** 距过期不足指定秒数时返回 true. */
    public boolean expiringWithin(long seconds) {
        return System.currentTimeMillis() / 1000 + seconds >= expiresAtEpochSec;
    }
}
