package com.lightnote.client.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.model.SyncStatus;
import com.lightnote.client.util.HtmlContentSanitizer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 远端 API 客户端，负责与服务端登录、推送同步和拉取增量变更。
 */
public class LightNoteApiClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String serverUrl;

    public LightNoteApiClient(String serverUrl) {
        this.serverUrl = trimTrailingSlash(serverUrl);
    }

    /**
     * 调用登录接口并解析令牌信息。
     */
    public LoginResponse login(String username, String password) {
        Map<String, String> body = Map.of("username", username, "password", password);
        JsonNode data = sendJson("POST", "/api/auth/login", null, body);
        return new LoginResponse(data.path("token").asText(), data.path("expireSeconds").asLong());
    }

    /**
     * 将本地待同步笔记序列化为同步请求并提交到服务端。
     */
    public SyncPushResponse push(String token, long lastSyncVersion, List<Note> notes) {
        Map<String, Object> body = new HashMap<>();
        body.put("lastSyncVersion", lastSyncVersion);
        body.put("notes", notes.stream().map(this::toSyncNoteRequest).toList());
        JsonNode data = sendJson("POST", "/api/sync/push", token, body);
        return parsePushResponse(data);
    }

    /**
     * 拉取指定版本之后的增量变化，供客户端按 serverVersion 顺序应用。
     */
    public SyncChangesResponse changes(String token, long sinceVersion, int limit) {
        JsonNode data = sendJson("GET", "/api/sync/changes?sinceVersion=" + sinceVersion + "&limit=" + limit, token, null);
        List<RemoteNote> notes = new ArrayList<>();
        for (JsonNode item : data.withArray("notes")) {
            notes.add(parseRemoteNote(item));
        }
        return new SyncChangesResponse(data.path("serverVersion").asLong(), data.path("hasMore").asBoolean(), notes);
    }

    /**
     * 将本地笔记转换为服务端同步协议结构，并按正文格式决定发送原始 Markdown 还是规范化 HTML。
     */
    private Map<String, Object> toSyncNoteRequest(Note note) {
        Map<String, Object> item = new HashMap<>();
        item.put("noteUuid", note.getNoteUuid());
        item.put("operation", note.getSyncStatus() == SyncStatus.DELETE_PENDING ? "DELETE" : note.getObjectVersion() == 0 ? "CREATE" : "UPDATE");
        item.put("baseObjectVersion", note.getObjectVersion());
        item.put("title", note.getTitle());
        item.put("content", contentForSync(note));
        item.put("contentFormat", note.getContentFormat().name());
        item.put("summary", note.getSummary());
        item.put("categoryName", note.getCategoryName());
        item.put("pinned", note.isPinned());
        item.put("favorite", note.isFavorite());
        item.put("archived", note.isArchived());
        item.put("deleted", note.isDeleted());
        item.put("clientUpdateTime", note.getUpdateTime());
        return item;
    }

    private SyncPushResponse parsePushResponse(JsonNode data) {
        List<SyncItemResult> successItems = new ArrayList<>();
        for (JsonNode item : data.withArray("successItems")) {
            successItems.add(new SyncItemResult(
                    item.path("noteUuid").asText(),
                    item.path("objectVersion").asLong(),
                    item.path("serverVersion").asLong()
            ));
        }
        List<SyncConflictItem> conflictItems = new ArrayList<>();
        for (JsonNode item : data.withArray("conflictItems")) {
            conflictItems.add(new SyncConflictItem(
                    item.path("noteUuid").asText(),
                    item.path("clientBaseObjectVersion").asLong(),
                    item.path("serverObjectVersion").asLong(),
                    parseRemoteNote(item.path("serverNote"))
            ));
        }
        return new SyncPushResponse(data.path("serverVersion").asLong(), successItems, conflictItems);
    }

    private RemoteNote parseRemoteNote(JsonNode item) {
        return new RemoteNote(
                item.path("noteUuid").asText(),
                item.path("operation").asText("UPDATE"),
                item.path("objectVersion").asLong(),
                item.path("serverVersion").asLong(),
                item.path("contentFormat").asText(ContentFormat.HTML.name()),
                item.path("title").asText(""),
                item.path("content").asText(""),
                item.path("summary").asText(""),
                item.path("categoryName").asText(""),
                item.path("pinned").asBoolean(),
                item.path("favorite").asBoolean(),
                item.path("archived").asBoolean(),
                item.path("deleted").asBoolean(),
                textOrNull(item.path("createTime")),
                textOrNull(item.path("updateTime")),
                textOrNull(item.path("deleteTime"))
        );
    }

    private String contentForSync(Note note) {
        if (note.getContentFormat() == ContentFormat.MARKDOWN) {
            return note.getContent() == null ? "" : note.getContent();
        }
        return HtmlContentSanitizer.normalizeForStorage(note.getContent());
    }

    /**
     * 统一处理 JSON 请求、鉴权头和错误映射，把网络异常翻译成更可读的中文错误。
     */
    private JsonNode sendJson(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + path))
                    .timeout(Duration.ofSeconds(20));
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || root.path("code").asInt(-1) != 0) {
                throw new ApiException(root.path("message").asText("HTTP " + response.statusCode()));
            }
            return root.path("data");
        } catch (IllegalArgumentException ex) {
            throw new ApiException("服务端地址格式不正确", ex);
        } catch (HttpTimeoutException ex) {
            throw new ApiException("连接服务端超时", ex);
        } catch (IOException ex) {
            throw new ApiException(networkErrorMessage(ex), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException("网络请求被中断", ex);
        }
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String networkErrorMessage(IOException ex) {
        if (ex instanceof ConnectException) {
            return "无法连接到服务端";
        }
        if (ex instanceof UnknownHostException) {
            return "无法解析服务端地址";
        }
        String message = ex.getMessage();
        if (message != null && message.toLowerCase().contains("timed out")) {
            return "连接服务端超时";
        }
        return "网络请求失败";
    }
}

