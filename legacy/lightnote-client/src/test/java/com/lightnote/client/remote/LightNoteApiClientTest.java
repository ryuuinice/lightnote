package com.lightnote.client.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.model.SyncStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LightNoteApiClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loginTrimsBaseUrlAndParsesResponse() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {"code":0,"message":"success","data":{"token":"jwt-1","expireSeconds":7200}}
                    """);
        });

        LightNoteApiClient client = new LightNoteApiClient(baseUrl() + "/");
        LoginResponse response = client.login("admin", "secret");

        assertEquals("/api/auth/login", path.get());
        assertTrue(body.get().contains("\"username\":\"admin\""));
        assertTrue(body.get().contains("\"password\":\"secret\""));
        assertEquals("jwt-1", response.token());
        assertEquals(7200L, response.expireSeconds());
    }

    @Test
    void pushSendsDeleteOperationAndParsesConflictItems() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = startServer(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "code": 0,
                      "message": "success",
                      "data": {
                        "serverVersion": 15,
                        "successItems": [
                          {"noteUuid":"note-1","objectVersion":3,"serverVersion":15}
                        ],
                        "conflictItems": [
                          {
                            "noteUuid":"note-2",
                            "clientBaseObjectVersion":1,
                            "serverObjectVersion":4,
                            "serverNote":{
                              "noteUuid":"note-2",
                              "operation":"UPDATE",
                              "objectVersion":4,
                              "serverVersion":14,
                              "title":"server",
                              "content":"body",
                              "summary":"summary",
                              "categoryName":"ops",
                              "pinned":true,
                              "favorite":false,
                              "archived":false,
                              "deleted":false,
                              "createTime":"2026-05-08T10:00:00",
                              "updateTime":"2026-05-08T10:30:00",
                              "deleteTime":null
                            }
                          }
                        ]
                      }
                    }
                    """);
        });

        Note note = new Note();
        note.setNoteUuid("note-1");
        note.setTitle("Delete me");
        note.setObjectVersion(2L);
        note.setSyncStatus(SyncStatus.DELETE_PENDING);
        note.setDeleted(true);
        note.setUpdateTime("2026-05-08T11:00:00");

        LightNoteApiClient client = new LightNoteApiClient(baseUrl());
        SyncPushResponse response = client.push("jwt-2", 8L, List.of(note));

        assertEquals("Bearer jwt-2", auth.get());
        assertTrue(body.get().contains("\"operation\":\"DELETE\""));
        assertTrue(body.get().contains("\"baseObjectVersion\":2"));
        assertTrue(body.get().contains("\"contentFormat\":\"HTML\""));
        assertEquals(15L, response.serverVersion());
        assertEquals(1, response.successItems().size());
        assertEquals(1, response.conflictItems().size());
        assertEquals("note-2", response.conflictItems().get(0).noteUuid());
        assertEquals(4L, response.conflictItems().get(0).serverObjectVersion());
        assertEquals("server", response.conflictItems().get(0).serverNote().title());
        assertEquals("HTML", response.conflictItems().get(0).serverNote().contentFormat());
    }

    @Test
    void pushNormalizesEscapedHtmlBeforeSending() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = startServer(exchange -> {
            body.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "code": 0,
                      "message": "success",
                      "data": {
                        "serverVersion": 9,
                        "successItems": [
                          {"noteUuid":"note-rich","objectVersion":1,"serverVersion":9}
                        ],
                        "conflictItems": []
                      }
                    }
                    """);
        });

        Note note = new Note();
        note.setNoteUuid("note-rich");
        note.setTitle("Rich");
        note.setContent("&lt;html&gt;&lt;body&gt;&lt;span style=\"font-weight: bold\"&gt;Hello&lt;/span&gt;&lt;/body&gt;&lt;/html&gt;");
        note.setSummary("Hello");
        note.setSyncStatus(SyncStatus.DIRTY);
        note.setUpdateTime("2026-05-08T11:00:00");

        LightNoteApiClient client = new LightNoteApiClient(baseUrl());
        client.push("jwt-rich", 8L, List.of(note));

        assertNotNull(body.get());
        assertTrue(body.get().contains("<span style=\\\"font-weight: bold\\\">Hello</span>"));
        assertFalse(body.get().contains("&lt;html"));
        assertFalse(body.get().contains("<html"));
    }

    @Test
    void pushKeepsMarkdownContentRaw() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = startServer(exchange -> {
            body.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "code": 0,
                      "message": "success",
                      "data": {
                        "serverVersion": 9,
                        "successItems": [
                          {"noteUuid":"note-md","objectVersion":1,"serverVersion":9}
                        ],
                        "conflictItems": []
                      }
                    }
                    """);
        });

        Note note = new Note();
        note.setNoteUuid("note-md");
        note.setTitle("Markdown");
        note.setContentFormat(ContentFormat.MARKDOWN);
        note.setContent("# Title\\n\\nKeep <literal> tags");
        note.setSummary("Title Keep <literal> tags");
        note.setSyncStatus(SyncStatus.DIRTY);
        note.setUpdateTime("2026-05-08T11:00:00");

        LightNoteApiClient client = new LightNoteApiClient(baseUrl());
        client.push("jwt-md", 8L, List.of(note));

        assertNotNull(body.get());
        assertTrue(body.get().contains("\"contentFormat\":\"MARKDOWN\""));
        assertTrue(body.get().contains("Keep <literal> tags"));
    }

    @Test
    void changesParsesNotesAndSurfacesApiErrors() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server = startServer(exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            if (exchange.getRequestURI().getPath().endsWith("/api/sync/changes")) {
                writeJson(exchange, 200, """
                        {
                          "code": 0,
                          "message": "success",
                          "data": {
                            "serverVersion": 20,
                            "hasMore": false,
                            "notes": [
                              {
                                "noteUuid":"note-9",
                                "operation":"DELETE",
                                "objectVersion":6,
                                "serverVersion":20,
                                "title":"gone",
                                "content":"",
                                "summary":"",
                                "categoryName":"",
                                "pinned":false,
                                "favorite":false,
                                "archived":false,
                                "deleted":true,
                                "createTime":"2026-05-08T09:00:00",
                                "updateTime":"2026-05-08T09:30:00",
                                "deleteTime":"2026-05-08T09:31:00"
                              }
                            ]
                          }
                        }
                        """);
                return;
            }
            writeJson(exchange, 401, """
                    {"code":401,"message":"token invalid","data":null}
                    """);
        });

        LightNoteApiClient client = new LightNoteApiClient(baseUrl());
        SyncChangesResponse response = client.changes("jwt-3", 12L, 50);

        assertEquals("sinceVersion=12&limit=50", query.get());
        assertEquals(20L, response.serverVersion());
        assertFalse(response.hasMore());
        assertEquals(1, response.notes().size());
        assertTrue(response.notes().get(0).deleted());
        assertEquals("HTML", response.notes().get(0).contentFormat());
        assertEquals("2026-05-08T09:31:00", response.notes().get(0).deleteTime());

        server.removeContext("/");
        server.createContext("/", exchange -> writeJson(exchange, 401, """
                {"code":401,"message":"token invalid","data":null}
                """));
        ApiException ex = assertThrows(ApiException.class, () -> client.changes("jwt-3", 12L, 50));
        assertEquals("token invalid", ex.getMessage());
    }

    @Test
    void loginRejectsInvalidServerUrlWithFriendlyMessage() {
        LightNoteApiClient client = new LightNoteApiClient("http://127.0.0.1:8080 bad");

        ApiException ex = assertThrows(ApiException.class, () -> client.login("admin", "secret"));

        assertEquals("服务端地址格式不正确", ex.getMessage());
    }

    private HttpServer startServer(ExchangeHandler handler) throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private String baseUrl() {
        assertNotNull(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
