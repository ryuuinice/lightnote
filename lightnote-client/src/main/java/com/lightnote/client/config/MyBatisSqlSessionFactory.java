package com.lightnote.client.config;

import com.lightnote.client.mapper.AppConfigMapper;
import com.lightnote.client.mapper.ContentFormatTypeHandler;
import com.lightnote.client.mapper.NoteMapper;
import com.lightnote.client.model.ContentFormat;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * MyBatis SqlSessionFactory 单例，为 LightNote 客户端提供数据库连接池和映射能力。
 * <p>
 * 使用 MyBatis 内置的 PooledDataSource 替代每次 DriverManager.getConnection() 的原始 JDBC 模式。
 * 配置了 underscore-to-camel 自动映射（note_uuid → noteUuid）以及自定义的 ContentFormat 类型处理器。
 */
public class MyBatisSqlSessionFactory {

    private static volatile SqlSessionFactory instance;
    private static volatile Path currentDatabasePath;

    private MyBatisSqlSessionFactory() {
    }

    /**
     * 获取或创建 SqlSessionFactory 单例。
     * <p>
     * 双重检查锁定保证线程安全。当 databasePath 变化时（常见于测试），会重建工厂。
     *
     * @param databasePath SQLite 数据库文件路径
     * @return SqlSessionFactory 实例
     */
    public static SqlSessionFactory getInstance(Path databasePath) {
        if (instance == null || !databasePath.equals(currentDatabasePath)) {
            synchronized (MyBatisSqlSessionFactory.class) {
                if (instance == null || !databasePath.equals(currentDatabasePath)) {
                    instance = build(databasePath);
                    currentDatabasePath = databasePath;
                }
            }
        }
        return instance;
    }

    /**
     * 供测试使用：替换当前单例。
     *
     * @param testInstance 测试用的 SqlSessionFactory
     */
    static void setInstance(SqlSessionFactory testInstance) {
        instance = testInstance;
        currentDatabasePath = null;
    }

    /**
     * 重置单例（供测试清理用）。
     */
    public static void resetForTest() {
        instance = null;
        currentDatabasePath = null;
    }

    private static SqlSessionFactory build(Path databasePath) {
        DataSource dataSource = new PooledDataSource(
                "org.sqlite.JDBC",
                "jdbc:sqlite:" + databasePath,
                null,
                null
        );

        JdbcTransactionFactory txFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("lightnote-client", txFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);

        // 注册自定义类型处理器
        configuration.getTypeHandlerRegistry().register(ContentFormat.class, new ContentFormatTypeHandler());

        // 注册 Mapper
        configuration.addMapper(NoteMapper.class);
        configuration.addMapper(AppConfigMapper.class);

        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
