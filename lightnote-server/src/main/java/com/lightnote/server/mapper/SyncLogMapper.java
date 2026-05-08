package com.lightnote.server.mapper;

import com.lightnote.server.entity.SyncChangeEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SyncLogMapper {

    @Select("""
            SELECT current_server_version
            FROM tbl_server_state
            WHERE id = 1
            FOR UPDATE
            """)
    Long lockCurrentServerVersion();

    @Select("""
            SELECT current_server_version
            FROM tbl_server_state
            WHERE id = 1
            """)
    Long getCurrentServerVersion();

    @Update("""
            UPDATE tbl_server_state
            SET current_server_version = #{serverVersion}
            WHERE id = 1
            """)
    int updateCurrentServerVersion(@Param("serverVersion") Long serverVersion);

    @Insert("""
            INSERT INTO tbl_sync_log (
                user_id, object_type, object_uuid, operation, server_version, change_time
            ) VALUES (
                #{userId}, #{objectType}, #{objectUuid}, #{operation}, #{serverVersion}, #{changeTime}
            )
            """)
    int insertLog(
            @Param("userId") Long userId,
            @Param("objectType") String objectType,
            @Param("objectUuid") String objectUuid,
            @Param("operation") String operation,
            @Param("serverVersion") Long serverVersion,
            @Param("changeTime") LocalDateTime changeTime
    );

    @Select("""
            SELECT l.operation,
                   l.server_version AS log_server_version,
                   n.note_uuid,
                   n.title,
                   n.content,
                   n.summary,
                   n.category_name,
                   n.is_pinned,
                   n.is_favorite,
                   n.is_archived,
                   n.is_deleted,
                   n.object_version,
                   n.create_time,
                   n.update_time,
                   n.delete_time
            FROM tbl_sync_log l
            JOIN tbl_note n
              ON n.user_id = l.user_id
             AND n.note_uuid = l.object_uuid
            WHERE l.user_id = #{userId}
              AND l.object_type = 'NOTE'
              AND l.server_version > #{sinceVersion}
            ORDER BY l.server_version ASC
            LIMIT #{limit}
            """)
    List<SyncChangeEntity> findNoteChanges(
            @Param("userId") Long userId,
            @Param("sinceVersion") Long sinceVersion,
            @Param("limit") int limit
    );
}
