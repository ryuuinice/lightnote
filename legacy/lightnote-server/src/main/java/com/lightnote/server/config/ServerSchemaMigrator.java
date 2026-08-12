package com.lightnote.server.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
/**
 * 服务端表结构迁移器，负责启动时补齐必要字段和兼容旧库结构。
 */
public class ServerSchemaMigrator implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSchemaMigrator.class);

    private final DataSource dataSource;

    public ServerSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            ensureContentFormatColumn(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("数据库 schema 迁移失败，请检查数据库用户是否有 ALTER 权限", ex);
        }
    }

    private void ensureContentFormatColumn(Connection connection) throws SQLException {
        if (columnExists(connection, "tbl_note", "content_format")) {
            return;
        }
        LOGGER.info("检测到 tbl_note 缺少 content_format 字段，开始自动迁移");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE tbl_note
                        ADD COLUMN content_format VARCHAR(16) NOT NULL DEFAULT 'HTML'
                        AFTER content
                    """);
        }
        LOGGER.info("tbl_note.content_format 字段迁移完成");
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS matched
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt("matched") > 0;
            }
        }
    }
}

