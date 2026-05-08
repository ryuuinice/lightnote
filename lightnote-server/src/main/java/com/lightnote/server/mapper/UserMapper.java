package com.lightnote.server.mapper;

import com.lightnote.server.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("""
            SELECT id, username, password_hash, nickname, status, create_time, update_time
            FROM tbl_user
            WHERE username = #{username}
            LIMIT 1
            """)
    UserEntity findByUsername(@Param("username") String username);
}
