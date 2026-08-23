package com.autobackup.auth;

import com.autobackup.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 将 access_token / refresh_token 持久化到本地 JSON 文件(Linux 下权限 600). */
public class TokenStore {

    private final Path file;

    public TokenStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized TokenInfo load() {
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode node = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            String accessToken = node.path("access_token").asText("");
            String refreshToken = node.path("refresh_token").asText("");
            long expiresAt = node.path("expires_at").asLong(0);
            if (accessToken.isBlank() || refreshToken.isBlank()) return null;
            return new TokenInfo(accessToken, refreshToken, expiresAt);
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized void save(TokenInfo token) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("access_token", token.accessToken());
            data.put("refresh_token", token.refreshToken());
            data.put("expires_at", token.expiresAtEpochSec());
            Files.writeString(file, Json.write(data), StandardCharsets.UTF_8);
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(file, perms);
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows 等不支持 POSIX 权限的系统忽略
            }
        } catch (IOException e) {
            throw new IllegalStateException("凭证写入失败: " + file + " : " + e.getMessage(), e);
        }
    }
}
