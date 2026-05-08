package com.lightnote.client.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseInitializer {

    private Path dataDirectory;
    private Path databasePath;

    public DatabaseInitializer() {
    }

    public void initialize() {
        List<String> failures = new ArrayList<>();
        for (Path candidate : dataDirectoryCandidates()) {
            try {
                initializeAt(candidate);
                return;
            } catch (Exception ex) {
                failures.add(candidate + ": " + ex.getMessage());
            }
        }
        throw new IllegalStateException("Failed to initialize local database. Tried " + failures);
    }

    private void initializeAt(Path candidateDirectory) throws Exception {
        Path candidateDatabasePath = candidateDirectory.resolve("lightnote.db");
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(candidateDirectory);
            try (Connection connection = DriverManager.getConnection(jdbcUrl(candidateDatabasePath));
                 Statement statement = connection.createStatement()) {
                tryEnableWal(statement);
                statement.executeUpdate("PRAGMA foreign_keys=ON");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            note_uuid TEXT NOT NULL UNIQUE,
                            title TEXT NOT NULL,
                            content TEXT,
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
                statement.executeUpdate("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts5(
                            title,
                            content,
                            summary,
                            content='notes',
                            content_rowid='id'
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS app_config (
                            config_key TEXT PRIMARY KEY,
                            config_value TEXT
                        )
                        """);
                createFtsTriggers(statement);
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
        } catch (SQLException ignored) {
            // Some locked-down filesystems reject WAL sidecar file changes. The app can still run safely without WAL.
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    private List<Path> dataDirectoryCandidates() {
        List<Path> candidates = new ArrayList<>();
        String configuredDataDir = System.getProperty("lightnote.dataDir");
        if (configuredDataDir == null || configuredDataDir.isBlank()) {
            configuredDataDir = System.getenv("LIGHTNOTE_DATA_DIR");
        }
        if (configuredDataDir != null && !configuredDataDir.isBlank()) {
            addCandidate(candidates, Path.of(configuredDataDir));
        }
        addCandidate(candidates, Path.of(System.getProperty("user.home"), ".lightnote"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            addCandidate(candidates, Path.of(localAppData, "LightNote"));
        }
        addCandidate(candidates, Path.of(System.getProperty("user.dir"), ".lightnote"));
        return candidates;
    }

    private void addCandidate(List<Path> candidates, Path path) {
        Path candidate = path.toAbsolutePath().normalize();
        if (candidate.getParent() == null || candidates.contains(candidate)) {
            return;
        }
        candidates.add(candidate);
    }

    private String jdbcUrl(Path path) {
        return "jdbc:sqlite:" + path;
    }
}
