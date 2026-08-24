package com.autobackup.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Random;

/** 轻量 HTTP 工具, 基于 JDK HttpClient, 无额外依赖. */
public class HttpUtil {

    /** PCS 接口要求请求必须带 User-Agent. */
    public static final String USER_AGENT = "netdisk;AutoBackup/1.0";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 发送 application/x-www-form-urlencoded POST 请求. */
    public String postForm(String url, Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 以 multipart/form-data 上传一个文件字段. */
    public String postMultipart(String url, String fieldName, String filename, byte[] data, Duration timeout) {
        String boundary = "----AutoBackupBoundary" + Long.toHexString(new Random().nextLong());
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 512);
        out.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(data);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    private <T> T send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            HttpResponse<T> response = client.send(request, handler);
            return response.body();
        } catch (IOException e) {
            throw new HttpNetworkException("HTTP 请求失败(" + e.getClass().getSimpleName() + "): "
                    + request.uri() + " : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP 请求被中断", e);
        }
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
