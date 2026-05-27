package com.lightnote.client.repository;

import com.lightnote.client.config.MyBatisSqlSessionFactory;
import com.lightnote.client.mapper.AppConfigMapper;
import org.apache.ibatis.session.SqlSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 应用配置仓库，负责登录信息、同步游标、界面偏好和分类目录的本地持久化。
 * <p>
 * 基于 MyBatis 实现，委托 {@link AppConfigMapper} 完成 SQLite 键值存取。
 */
public class AppConfigRepository {

    private final Path databasePath;

    public AppConfigRepository(Path databasePath) {
        this.databasePath = databasePath;
    }

    public Optional<String> get(String key) {
        try (SqlSession session = openSession()) {
            return Optional.ofNullable(
                    session.getMapper(AppConfigMapper.class).selectValue(key));
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
        try (SqlSession session = openSession()) {
            session.getMapper(AppConfigMapper.class).upsert(key, value);
            session.commit();
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
        try (SqlSession session = openSession()) {
            session.getMapper(AppConfigMapper.class).delete(key);
            session.commit();
        }
    }

    public List<String> categoryCatalog() {
        return get("category_catalog").map(this::decodeCategoryCatalog).orElseGet(List::of);
    }

    public void addCategory(String categoryName) {
        String normalized = normalizeCategoryName(categoryName);
        if (normalized.isEmpty()) {
            return;
        }
        LinkedHashSet<String> categories = new LinkedHashSet<>(categoryCatalog());
        categories.add(normalized);
        saveCategoryCatalog(categories);
    }

    public void renameCategory(String previousName, String nextName) {
        String previous = normalizeCategoryName(previousName);
        String next = normalizeCategoryName(nextName);
        if (previous.isEmpty() || next.isEmpty()) {
            return;
        }
        LinkedHashSet<String> categories = new LinkedHashSet<>(categoryCatalog());
        if (!categories.remove(previous)) {
            categories.add(next);
        } else {
            categories.add(next);
        }
        saveCategoryCatalog(categories);
    }

    public void removeCategory(String categoryName) {
        String normalized = normalizeCategoryName(categoryName);
        if (normalized.isEmpty()) {
            return;
        }
        LinkedHashSet<String> categories = new LinkedHashSet<>(categoryCatalog());
        if (categories.remove(normalized)) {
            saveCategoryCatalog(categories);
        }
    }

    private void saveCategoryCatalog(Iterable<String> categoryNames) {
        List<String> normalized = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String categoryName : categoryNames) {
            String value = normalizeCategoryName(categoryName);
            if (!value.isEmpty() && seen.add(value)) {
                normalized.add(value);
            }
        }
        put("category_catalog", String.join("\n", normalized));
    }

    private List<String> decodeCategoryCatalog(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String normalized = normalizeCategoryName(line);
            if (!normalized.isEmpty()) {
                categories.add(normalized);
            }
        }
        return new ArrayList<>(categories);
    }

    private String normalizeCategoryName(String value) {
        return value == null ? "" : value.strip();
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "http://localhost:8080" : trimmed;
    }

    private SqlSession openSession() {
        return MyBatisSqlSessionFactory.getInstance(databasePath).openSession();
    }
}

