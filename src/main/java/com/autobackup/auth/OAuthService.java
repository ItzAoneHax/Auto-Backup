package com.autobackup.auth;

import com.autobackup.config.AppConfig;
import com.autobackup.util.HttpUtil;
import com.autobackup.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * 百度 OAuth2.0 设备码授权与令牌刷新.
 *
 * <p>服务器无浏览器, 采用设备码(device code)方式: 程序打印验证地址和验证码,
 * 用户在任意设备完成授权后程序自动获得令牌. access_token 有效期 30 天,
 * refresh_token 有效期 10 年, 每次运行前距过期不足 7 天时自动刷新.
 */
public class OAuthService {

    private static final String DEVICE_CODE_URL = "https://openapi.baidu.com/oauth/2.0/device/code";
    private static final String TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token";
    private static final long REFRESH_WINDOW_SEC = Duration.ofDays(7).toSeconds();

    private final AppConfig config;
    private final HttpUtil http;
    private final TokenStore store;

    public OAuthService(AppConfig config, HttpUtil http, TokenStore store) {
        this.config = config;
        this.http = http;
        this.store = store;
    }

    /** 交互式设备码登录: 打印验证地址, 轮询直到用户完成授权. */
    public TokenInfo loginInteractive() throws InterruptedException {
        JsonNode code = Json.parse(http.get(DEVICE_CODE_URL
                + "?client_id=" + config.appKey() + "&scope=basic,netdisk"));
        checkOAuthError(code, "获取设备码失败");
        String deviceCode = code.path("device_code").asText();
        String userCode = code.path("user_code").asText();
        String verificationUrl = code.path("verification_url").asText("https://openapi.baidu.com/device");
        String qrcodeUrl = code.path("qrcode_url").asText("");
        long expiresIn = code.path("expires_in").asLong(300);
        long interval = Math.max(code.path("interval").asInt(5), 5);

        System.out.println("======================================================");
        System.out.println(" 百度网盘授权登录");
        System.out.println("------------------------------------------------------");
        System.out.println(" 1. 在任意设备(如你的电脑/手机)打开: " + verificationUrl);
        if (!qrcodeUrl.isBlank()) {
            System.out.println("    或扫码: " + qrcodeUrl);
        }
        System.out.println(" 2. 输入验证码: " + userCode);
        System.out.println(" 3. 登录百度账号并确认授权, 程序会自动继续 (" + expiresIn + " 秒内有效)");
        System.out.println("======================================================");

        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JsonNode token = Json.parse(http.get(TOKEN_URL
                    + "?grant_type=device_token&code=" + deviceCode
                    + "&client_id=" + config.appKey()
                    + "&client_secret=" + config.secretKey()));
            if (token.hasNonNull("access_token")) {
                TokenInfo saved = saveToken(token, null);
                System.out.println("[OK] 授权成功, 凭证已保存到 " + store.file().toAbsolutePath());
                return saved;
            }
            String error = token.path("error").asText("");
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) {
                interval += 5;
                continue;
            }
            throw new IllegalStateException("授权失败: " + errorDescription(token));
        }
        throw new IllegalStateException("授权超时, 请重新执行 login");
    }

    /** 读取已保存的凭证, 过期或即将过期时用 refresh_token 换新. */
    public TokenInfo ensureValidToken() {
        TokenInfo token = store.load();
        if (token == null) {
            throw new IllegalStateException("未找到授权凭证, 请先执行: java -jar auto-backup.jar login");
        }
        if (token.expiringWithin(REFRESH_WINDOW_SEC)) {
            return refresh(token);
        }
        return token;
    }

    public TokenInfo refresh(TokenInfo current) {
        JsonNode node = Json.parse(http.get(TOKEN_URL
                + "?grant_type=refresh_token&refresh_token=" + current.refreshToken()
                + "&client_id=" + config.appKey()
                + "&client_secret=" + config.secretKey()));
        if (!node.hasNonNull("access_token")) {
            throw new IllegalStateException("刷新凭证失败(" + errorDescription(node)
                    + "), 请在服务器上重新执行: java -jar auto-backup.jar login");
        }
        return saveToken(node, current);
    }

    private TokenInfo saveToken(JsonNode node, TokenInfo fallback) {
        String accessToken = node.path("access_token").asText();
        String refreshToken = node.path("refresh_token").asText(null);
        if ((refreshToken == null || refreshToken.isBlank()) && fallback != null) {
            refreshToken = fallback.refreshToken();
        }
        long expiresAt = System.currentTimeMillis() / 1000
                + node.path("expires_in").asLong(2592000L) - 600;
        TokenInfo token = new TokenInfo(accessToken, refreshToken, expiresAt);
        store.save(token);
        return token;
    }

    private static void checkOAuthError(JsonNode node, String action) {
        if (node.has("error")) {
            throw new IllegalStateException(action + ": " + errorDescription(node));
        }
    }

    private static String errorDescription(JsonNode node) {
        return node.path("error").asText("unknown")
                + ": " + node.path("error_description").asText("");
    }
}
