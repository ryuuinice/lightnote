package com.lightnote.client.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DatabaseInitializerTest {

    private Path tempDir;
    private final String originalUserHome = System.getProperty("user.home");

    @AfterEach
    void tearDown() throws IOException {
        System.clearProperty("lightnote.dataDir");
        System.setProperty("user.home", originalUserHome);
        if (tempDir == null) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void initializeRecordsDatabaseSetupLog() throws Exception {
        tempDir = Files.createTempDirectory("lightnote-db-init");
        System.setProperty("lightnote.dataDir", tempDir.toString());

        DatabaseInitializer initializer = new DatabaseInitializer();
        initializer.initialize();

        assertEquals(tempDir.toAbsolutePath().normalize(), initializer.getDataDirectory());
        assertNotNull(initializer.getDatabasePath());
        assertTrue(Files.exists(initializer.getDatabasePath()));
        assertFalse(initializer.initializationLog().isEmpty());
        assertTrue(initializer.initializationLog().stream().anyMatch(line -> line.contains("已确认 notes 表")));
        assertTrue(initializer.initializationLog().stream().anyMatch(line -> line.contains("执行迁移 v1")));
        assertTrue(initializer.initializationLog().stream().anyMatch(line -> line.contains("执行迁移 v2")));
        assertTrue(initializer.initializationLog().stream().anyMatch(line -> line.contains("执行迁移 v3")));
        assertTrue(columnExists(initializer.getDatabasePath(), "notes", "content_format"));
        assertTrue(columnExists(initializer.getDatabasePath(), "notes", "is_trashed"));
    }

    @Test
    void initializeFallsBackWhenConfiguredPathIsAFile() throws Exception {
        tempDir = Files.createTempDirectory("lightnote-db-init-fallback");
        Path configuredFile = tempDir.resolve("not-a-directory.txt");
        Files.writeString(configuredFile, "blocked");
        Path fallbackHome = tempDir.resolve("fake-home");
        System.setProperty("lightnote.dataDir", configuredFile.toString());
        System.setProperty("user.home", fallbackHome.toString());

        DatabaseInitializer initializer = new DatabaseInitializer();
        initializer.initialize();

        assertEquals(fallbackHome.resolve(".lightnote").toAbsolutePath().normalize(), initializer.getDataDirectory());
        assertTrue(initializer.initializationLog().stream().anyMatch(line -> line.contains("初始化失败 [configured]")));
        assertTrue(initializer.initializationLog().stream().anyMatch(line -> line.contains("尝试数据目录 [user-home]")));
    }

    @Test
    void initializeRecognizesUpToDateSchemaWithoutRunningMigrationAgain() throws Exception {
        tempDir = Files.createTempDirectory("lightnote-db-init-version");
        System.setProperty("lightnote.dataDir", tempDir.toString());

        DatabaseInitializer first = new DatabaseInitializer();
        first.initialize();

        DatabaseInitializer second = new DatabaseInitializer();
        second.initialize();

        assertTrue(second.initializationLog().stream().anyMatch(line -> line.contains("数据库无需迁移")));
        assertEquals(3, readUserVersion(second.getDatabasePath()));
    }

    private int readUserVersion(Path databasePath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private boolean columnExists(Path databasePath, String tableName, String columnName) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
