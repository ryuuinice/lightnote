package com.lightnote.client.repository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class AppConfigRepository {

    private final Path databasePath;

    public AppConfigRepository(Path databasePath) {
        this.databasePath = databasePath;
    }

    public Optional<String> get(String key) {
        String sql = "SELECT config_value FROM app_config WHERE config_key = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.ofNullable(resultSet.getString("config_value"));
                }
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read config " + key, ex);
        }
    }

    public long getLong(String key, long defaultValue) {
        return get(key).map(value -> {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return get(key).map(value -> {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public void put(String key, String value) {
        String sql = """
                INSERT INTO app_config(config_key, config_value)
                VALUES (?, ?)
                ON CONFLICT(config_key) DO UPDATE SET config_value = excluded.config_value
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to write config " + key, ex);
        }
    }

    public String serverUrl() {
        return get("server_url").orElse("http://localhost:8080");
    }

    public Optional<String> token() {
        return get("jwt_token");
    }

    public long lastSyncVersion() {
        return getLong("last_sync_version", 0);
    }

    public void saveLogin(String serverUrl, String token) {
        put("server_url", trimTrailingSlash(serverUrl));
        put("jwt_token", token);
    }

    public void saveLastSyncVersion(long serverVersion) {
        put("last_sync_version", Long.toString(serverVersion));
    }

    public void clearLogin() {
        delete("jwt_token");
    }

    public void delete(String key) {
        String sql = "DELETE FROM app_config WHERE config_key = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete config " + key, ex);
        }
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "http://localhost:8080" : trimmed;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }
}
