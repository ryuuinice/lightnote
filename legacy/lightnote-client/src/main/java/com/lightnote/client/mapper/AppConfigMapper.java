package com.lightnote.client.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * app_config 表的 MyBatis Mapper。
 * <p>
 * 提供简单的键值存取操作，替代原先的原始 JDBC 实现。
 */
public interface AppConfigMapper {

    /**
     * 按 key 查询配置值。
     *
     * @param key 配置键
     * @return 配置值，不存在时返回 null
     */
    @Select("SELECT config_value FROM app_config WHERE config_key = #{key}")
    String selectValue(@Param("key") String key);

    /**
     * 插入或更新配置项。
     * <p>
     * 使用 SQLite 的 ON CONFLICT … DO UPDATE 语义实现 upsert。
     *
     * @param key   配置键
     * @param value 配置值
     * @return 受影响行数
     */
    @Insert("""
            INSERT INTO app_config(config_key, config_value)
            VALUES (#{key}, #{value})
            ON CONFLICT(config_key) DO UPDATE SET config_value = excluded.config_value
            """)
    int upsert(@Param("key") String key, @Param("value") String value);

    /**
     * 删除配置项。
     *
     * @param key 配置键
     * @return 受影响行数
     */
    @Delete("DELETE FROM app_config WHERE config_key = #{key}")
    int delete(@Param("key") String key);
}
