package com.lightnote.server.mapper;

import com.lightnote.server.entity.NoteEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
/**
 * MyBatis 映射接口，负责服务端笔记表的读写与同步更新。
 */
public interface NoteMapper {

    @Select("""
            SELECT id, note_uuid, user_id, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_deleted,
                   object_version, server_version, create_time, update_time, delete_time
            FROM tbl_note
            WHERE user_id = #{userId}
              AND is_deleted = 0
            ORDER BY is_pinned DESC, update_time DESC
            """)
    List<NoteEntity> findActiveByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT id, note_uuid, user_id, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_deleted,
                   object_version, server_version, create_time, update_time, delete_time
            FROM tbl_note
            WHERE user_id = #{userId}
              AND note_uuid = #{noteUuid}
            LIMIT 1
            """)
    NoteEntity findByUserIdAndUuid(@Param("userId") Long userId, @Param("noteUuid") String noteUuid);

    @Select("""
            SELECT id, note_uuid, user_id, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_deleted,
                   object_version, server_version, create_time, update_time, delete_time
            FROM tbl_note
            WHERE user_id = #{userId}
              AND note_uuid = #{noteUuid}
            LIMIT 1
            FOR UPDATE
            """)
    NoteEntity findByUserIdAndUuidForUpdate(@Param("userId") Long userId, @Param("noteUuid") String noteUuid);

    @Insert("""
            INSERT INTO tbl_note (
                note_uuid, user_id, title, content, content_format, summary, category_name,
                is_pinned, is_favorite, is_archived, is_deleted,
                object_version, server_version, create_time, update_time
            ) VALUES (
                #{note.noteUuid}, #{note.userId}, #{note.title}, #{note.content}, #{note.contentFormat}, #{note.summary}, #{note.categoryName},
                #{note.isPinned}, #{note.isFavorite}, #{note.isArchived}, #{note.isDeleted},
                #{note.objectVersion}, #{note.serverVersion}, #{note.createTime}, #{note.updateTime}
            )
            """)
    int insert(@Param("note") NoteEntity note);

    @Update("""
            UPDATE tbl_note
            SET title = #{note.title},
                content = #{note.content},
                content_format = #{note.contentFormat},
                summary = #{note.summary},
                category_name = #{note.categoryName},
                is_pinned = #{note.isPinned},
                is_favorite = #{note.isFavorite},
                is_archived = #{note.isArchived},
                object_version = #{note.objectVersion},
                server_version = #{note.serverVersion},
                update_time = #{note.updateTime}
            WHERE user_id = #{note.userId}
              AND note_uuid = #{note.noteUuid}
              AND is_deleted = 0
            """)
    int update(@Param("note") NoteEntity note);

    @Update("""
            UPDATE tbl_note
            SET title = #{note.title},
                content = #{note.content},
                content_format = #{note.contentFormat},
                summary = #{note.summary},
                category_name = #{note.categoryName},
                is_pinned = #{note.isPinned},
                is_favorite = #{note.isFavorite},
                is_archived = #{note.isArchived},
                is_deleted = #{note.isDeleted},
                object_version = #{note.objectVersion},
                server_version = #{note.serverVersion},
                update_time = #{note.updateTime},
                delete_time = #{note.deleteTime}
            WHERE user_id = #{note.userId}
              AND note_uuid = #{note.noteUuid}
            """)
    int updateFromSync(@Param("note") NoteEntity note);

    @Update("""
            UPDATE tbl_note
            SET is_deleted = 1,
                object_version = object_version + 1,
                server_version = #{serverVersion},
                update_time = #{now},
                delete_time = #{now}
            WHERE user_id = #{userId}
              AND note_uuid = #{noteUuid}
              AND is_deleted = 0
            """)
    int softDelete(
            @Param("userId") Long userId,
            @Param("noteUuid") String noteUuid,
            @Param("serverVersion") Long serverVersion,
            @Param("now") LocalDateTime now
    );
}

