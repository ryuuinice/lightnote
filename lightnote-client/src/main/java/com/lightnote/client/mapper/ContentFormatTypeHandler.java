package com.lightnote.client.mapper;

import com.lightnote.client.model.ContentFormat;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis 类型处理器，负责 ContentFormat 枚举与数据库字符串的互转。
 * <p>
 * 写入时存储枚举名称（HTML / MARKDOWN），
 * 读取时通过 {@link ContentFormat#from(String)} 容错解析，避免因遗留数据导致异常。
 */
public class ContentFormatTypeHandler extends BaseTypeHandler<ContentFormat> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    ContentFormat parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.name());
    }

    @Override
    public ContentFormat getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return ContentFormat.from(rs.getString(columnName));
    }

    @Override
    public ContentFormat getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return ContentFormat.from(rs.getString(columnIndex));
    }

    @Override
    public ContentFormat getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return ContentFormat.from(cs.getString(columnIndex));
    }
}
