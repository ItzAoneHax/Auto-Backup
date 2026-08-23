package com.autobackup.pan;

import com.autobackup.util.HttpUtil;
import com.autobackup.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百度网盘开放平台 REST API 客户端.
 *
 * <p>接口文档: https://pan.baidu.com/union/doc/ 
 * 覆盖: 文件预上传(precreate)、分片上传(superfile2)、合并(create)、
 * 目录创建、文件列表(list)、删除(filemanager)、账号与容量信息.
 */
public class PanClient {

    private static final String XPAN_FILE = "https://pan.baidu.com/rest/2.0/xpan/file";
    private static final String QUOTA = "https://pan.baidu.com/api/quota";
    private static final String UINFO = "https://pan.baidu.com/rest/2.0/nas";
    private static final String PCS_UPLOAD = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2";
    private static final int LIST_PAGE = 1000;

    private final String accessToken;
    private final HttpUtil http;

    public PanClient(String accessToken, HttpUtil http) {
        this.accessToken = accessToken;
        this.http = http;
    }

    /** 账号信息(含 netdisk_name / baidu_name). */
    public JsonNode userInfo() {
        JsonNode node = Json.parse(http.get(UINFO + "?method=uinfo&access_token=" + accessToken));
        if (node.has("errno") && node.path("errno").asInt(0) != 0) {
            throw new PanException(node.path("errno").asInt(), "获取账号信息失败");
        }
        return node;
    }

    /** 网盘容量(total/used, 字节). */
    public JsonNode quota() {
        JsonNode node = Json.parse(http.get(QUOTA + "?checkfree=1&access_token=" + accessToken));
        checkErrno(node, "获取网盘容量失败");
        return node;
    }

    /** 逐级创建远程目录(等价 mkdir -p), 已存在不视为错误. */
    public void mkdirs(String remoteDir) {
        String[] segments = remoteDir.split("/");
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) continue;
            current.append('/').append(segment);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("path", current.toString());
            form.put("isdir", "1");
            form.put("rtype", "0");
            JsonNode node = Json.parse(http.postForm(
                    XPAN_FILE + "?method=create&access_token=" + accessToken, form));
            int errno = node.path("errno").asInt(0);
            if (errno != 0 && errno != -8) {   // -8: 目录已存在
                throw new PanException(errno, "创建远程目录失败: " + current);
            }
        }
    }

    /** 列出目录下全部文件/子目录(自动翻页), 按时间倒序. */
    public List<RemoteFile> list(String dir) {
        List<RemoteFile> result = new ArrayList<>();
        int start = 0;
        while (true) {
            String url = XPAN_FILE + "?method=list&access_token=" + accessToken
                    + "&dir=" + encode(dir)
                    + "&order=time&desc=1&limit=" + LIST_PAGE + "&start=" + start;
            JsonNode node = Json.parse(http.get(url));
            checkErrno(node, "列出远程目录失败: " + dir);
            int count = 0;
            for (JsonNode item : node.path("list")) {
                result.add(new RemoteFile(
                        item.path("path").asText(),
                        item.path("server_filename").asText(),
                        item.path("size").asLong(0),
                        item.path("isdir").asInt(0) == 1,
                        item.path("server_mtime").asLong(0)));
                count++;
            }
            if (count < LIST_PAGE) return result;
            start += LIST_PAGE;
        }
    }

    /** 预上传: 返回 uploadid 与待上传分片序号; 云端已有相同文件时秒传. */
    public PrecreateResult precreate(String path, long size, List<String> blockList) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("path", path);
        form.put("size", String.valueOf(size));
        form.put("isdir", "0");
        form.put("autoinit", "1");
        form.put("rtype", "3");   // 同名文件覆盖
        form.put("block_list", Json.write(blockList));
        JsonNode node = Json.parse(http.postForm(
                XPAN_FILE + "?method=precreate&access_token=" + accessToken, form));
        checkErrno(node, "预上传失败: " + path);
        if (node.path("return_type").asInt(1) == 2) {
            long fsId = node.path("info").path("fs_id").asLong(node.path("fs_id").asLong());
            return new PrecreateResult("", List.of(), true, fsId);
        }
        List<Integer> parts = new ArrayList<>();
        for (JsonNode p : node.path("block_list")) {
            parts.add(p.asInt());
        }
        return new PrecreateResult(node.path("uploadid").asText(), parts, false, 0);
    }

    /** 上传一个分片, 返回服务端校验的 md5. */
    public String uploadChunk(String path, String uploadId, int partSeq, byte[] data) {
        String url = PCS_UPLOAD + "?method=upload&access_token=" + accessToken
                + "&type=tmpfile&path=" + encode(path)
                + "&uploadid=" + encode(uploadId)
                + "&partseq=" + partSeq;
        JsonNode node = Json.parse(http.postMultipart(url, "file", "part-" + partSeq, data, Duration.ofMinutes(10)));
        checkErrno(node, "分片上传失败 partseq=" + partSeq);
        return node.path("md5").asText("");
    }

    /** 合并分片完成文件创建, 返回 fs_id. */
    public long create(String path, long size, String uploadId, List<String> blockList) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("path", path);
        form.put("size", String.valueOf(size));
        form.put("isdir", "0");
        form.put("uploadid", uploadId);
        form.put("rtype", "3");
        form.put("block_list", Json.write(blockList));
        JsonNode node = Json.parse(http.postForm(
                XPAN_FILE + "?method=create&access_token=" + accessToken, form));
        checkErrno(node, "合并文件失败: " + path);
        return node.path("fs_id").asLong();
    }

    /** 删除远程文件(同步模式). */
    public void delete(List<String> paths) {
        if (paths.isEmpty()) return;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("filelist", Json.write(paths));
        form.put("async", "2");
        JsonNode node = Json.parse(http.postForm(
                XPAN_FILE + "?method=filemanager&access_token=" + accessToken + "&opera=delete", form));
        checkErrno(node, "删除远程文件失败");
        for (JsonNode info : node.path("info")) {
            int errno = info.path("errno").asInt(0);
            if (errno != 0) {
                throw new PanException(errno, "删除失败: " + info.path("path").asText());
            }
        }
    }

    private void checkErrno(JsonNode node, String action) {
        int errno = node.path("errno").asInt(0);
        if (errno != 0) {
            throw new PanException(errno, action);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
