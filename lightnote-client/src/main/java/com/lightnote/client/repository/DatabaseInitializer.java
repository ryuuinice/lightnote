package com.lightnote.client.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseInitializer {

    private static final int CURRENT_SCHEMA_VERSION = 2;

    private Path dataDirectory;
    private Path databasePath;
    private final List<String> initializationLog = new ArrayList<>();

    public DatabaseInitializer() {
    }

    public void initialize() {
        List<String> failures = new ArrayList<>();
        initializationLog.clear();
        initializationLog.add("开始初始化本地数据库");
        for (CandidateDirectory candidate : dataDirectoryCandidates()) {
            try {
                initializationLog.add("尝试数据目录 [" + candidate.source() + "]: " + candidate.path());
                initializeAt(candidate);
                initializationLog.add("数据库初始化完成: " + candidateDatabaseSummary());
                return;
            } catch (Exception ex) {
                initializationLog.add("初始化失败 [" + candidate.source() + "]: " + candidate.path() + " -> " + ex.getMessage());
                failures.add(candidate.path() + ": " + ex.getMessage());
            }
        }
        throw new IllegalStateException("Failed to initialize local database. Tried " + failures);
    }

    private void initializeAt(CandidateDirectory candidate) throws Exception {
        Path candidateDirectory = candidate.path();
        Path candidateDatabasePath = candidateDirectory.resolve("lightnote.db");
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(candidateDirectory);
            verifyWritable(candidateDirectory);
            try (Connection connection = DriverManager.getConnection(jdbcUrl(candidateDatabasePath));
                 Statement statement = connection.createStatement()) {
                tryEnableWal(statement);
                statement.executeUpdate("PRAGMA foreign_keys=ON");
                initializationLog.add("已启用 foreign_keys");
                int currentVersion = readUserVersion(statement);
                initializationLog.add("检测到数据库 schema 版本: " + currentVersion);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            note_uuid TEXT NOT NULL UNIQUE,
                            title TEXT NOT NULL,
                            content TEXT,
                            content_format TEXT NOT NULL DEFAULT 'HTML',
                            summary TEXT,
                            category_name TEXT,
                            is_pinned INTEGER NOT NULL DEFAULT 0,
                            is_favorite INTEGER NOT NULL DEFAULT 0,
                            is_archived INTEGER NOT NULL DEFAULT 0,
                            is_deleted INTEGER NOT NULL DEFAULT 0,
                            object_version INTEGER NOT NULL DEFAULT 0,
                            server_version INTEGER NOT NULL DEFAULT 0,
                            sync_status TEXT NOT NULL DEFAULT 'DIRTY',
                            create_time TEXT NOT NULL,
                            update_time TEXT NOT NULL,
                            delete_time TEXT,
                            last_sync_time TEXT
                        )
                        """);
                initializationLog.add("已确认 notes 表");
                statement.executeUpdate("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts5(
                            title,
                            content,
                            summary,
                            content='notes',
                            content_rowid='id'
                        )
                        """);
                initializationLog.add("已确认 note_fts 索引");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS app_config (
                            config_key TEXT PRIMARY KEY,
                            config_value TEXT
                        )
                        """);
                initializationLog.add("已确认 app_config 表");
                createFtsTriggers(statement);
                initializationLog.add("已确认 FTS 触发器");
                applyMigrations(statement, currentVersion);
            }
            this.dataDirectory = candidateDirectory;
            this.databasePath = candidateDatabasePath;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize local database at " + candidateDatabasePath, ex);
        }
    }

    private void createFtsTriggers(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS notes_ai AFTER INSERT ON notes BEGIN
                    INSERT INTO note_fts(rowid, title, content, summary)
                    VALUES (new.id, new.title, new.content, new.summary);
                END
                """);
        statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS notes_ad AFTER DELETE ON notes BEGIN
                    INSERT INTO note_fts(note_fts, rowid, title, content, summary)
                    VALUES ('delete', old.id, old.title, old.content, old.summary);
                END
                """);
        statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS notes_au AFTER UPDATE ON notes BEGIN
                    INSERT INTO note_fts(note_fts, rowid, title, content, summary)
                    VALUES ('delete', old.id, old.title, old.content, old.summary);
                    INSERT INTO note_fts(rowid, title, content, summary)
                    VALUES (new.id, new.title, new.content, new.summary);
                END
                """);
    }

    private void tryEnableWal(Statement statement) {
        try {
            statement.executeUpdate("PRAGMA journal_mode=WAL");
            initializationLog.add("已启用 WAL 模式");
        } catch (SQLException ignored) {
            initializationLog.add("WAL 模式不可用，继续使用默认 journal_mode");
            // Some locked-down filesystems reject WAL sidecar file changes. The app can still run safely without WAL.
        }
    }

    private void applyMigrations(Statement statement, int currentVersion) throws SQLException {
        if (currentVersion >= CURRENT_SCHEMA_VERSION) {
            initializationLog.add("数据库无需迁移，当前版本已是 " + currentVersion);
            return;
        }
        for (int nextVersion = currentVersion + 1; nextVersion <= CURRENT_SCHEMA_VERSION; nextVersion++) {
            applyMigration(statement, nextVersion);
        }
        initializationLog.add("数据库迁移完成: " + currentVersion + " -> " + CURRENT_SCHEMA_VERSION);
    }

    private void applyMigration(Statement statement, int targetVersion) throws SQLException {
        switch (targetVersion) {
            case 1 -> {
                initializationLog.add("执行迁移 v1: 建立基线 schema 版本");
                statement.executeUpdate("PRAGMA user_version = 1");
            }
            case 2 -> {
                initializationLog.add("执行迁移 v2: 增加正文格式字段");
                if (!columnExists(statement, "notes", "content_format")) {
                    statement.executeUpdate("ALTER TABLE notes ADD COLUMN content_format TEXT NOT NULL DEFAULT 'HTML'");
                }
                statement.executeUpdate("PRAGMA user_version = 2");
            }
            default -> throw new IllegalStateException("Unsupported schema version: " + targetVersion);
        }
    }

    private boolean columnExists(Statement statement, String tableName, String columnName) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private int readUserVersion(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private void verifyWritable(Path directory) throws Exception {
        Path probe = directory.resolve(".lightnote-write-probe");
        Files.writeString(probe, "ok");
        Files.deleteIfExists(probe);
        initializationLog.add("已确认目录可写: " + directory);
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public List<String> initializationLog() {
        return List.copyOf(initializationLog);
    }

    private String candidateDatabaseSummary() {
        return "dataDir=" + dataDirectory + ", db=" + databasePath;
    }

    private List<CandidateDirectory> dataDirectoryCandidates() {
        List<CandidateDirectory> candidates = new ArrayList<>();
        String configuredDataDir = System.getProperty("lightnote.dataDir");
        if (configuredDataDir == null || configuredDataDir.isBlank()) {
            configuredDataDir = System.getenv("LIGHTNOTE_DATA_DIR");
        }
        if (configuredDataDir != null && !configuredDataDir.isBlank()) {
            addCandidate(candidates, Path.of(configuredDataDir), "configured");
        }
        addCandidate(candidates, Path.of(System.getProperty("user.home"), ".lightnote"), "user-home");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            addCandidate(candidates, Path.of(localAppData, "LightNote"), "local-app-data");
        }
        addCandidate(candidates, Path.of(System.getProperty("user.dir"), ".lightnote"), "working-directory");
        addCandidate(candidates, Path.of(System.getProperty("java.io.tmpdir"), "LightNote"), "temp-directory");
        return candidates;
    }

    private void addCandidate(List<CandidateDirectory> candidates, Path path, String source) {
        Path candidate = path.toAbsolutePath().normalize();
        boolean duplicate = candidates.stream().anyMatch(item -> item.path().equals(candidate));
        if (candidate.getParent() == null || duplicate) {
            return;
        }
        candidates.add(new CandidateDirectory(candidate, source));
    }

    private String jdbcUrl(Path path) {
        return "jdbc:sqlite:" + path;
    }

    private record CandidateDirectory(Path path, String source) {
    }
}
