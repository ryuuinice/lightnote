package com.lightnote.client.mapper;

import com.lightnote.client.model.Note;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * notes 表的 MyBatis Mapper。
 * <p>
 * 所有 SQL 均与原 NoteRepository 保持语义一致，包括筛选条件、排序和字段映射。
 */
public interface NoteMapper {

    // ======================== 查询 ========================

    /**
     * 查询所有活跃笔记（未删除、未回收、未归档），按置顶和更新时间降序排列。
     */
    @Select("""
            SELECT id, note_uuid, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_trashed, is_deleted,
                   object_version, server_version, sync_status,
                   create_time, update_time, delete_time, last_sync_time
            FROM notes
            WHERE is_deleted = 0 AND is_trashed = 0 AND is_archived = 0
            ORDER BY is_pinned DESC, update_time DESC
            """)
    List<Note> listActive();

    /**
     * 按 UUID 查询单条笔记。
     */
    @Select("""
            SELECT id, note_uuid, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_trashed, is_deleted,
                   object_version, server_version, sync_status,
                   create_time, update_time, delete_time, last_sync_time
            FROM notes
            WHERE note_uuid = #{noteUuid} LIMIT 1
            """)
    Note findByUuid(@Param("noteUuid") String noteUuid);

    /**
     * 查询所有待同步的笔记（DELETE_PENDING 或未删除的 DIRTY）。
     */
    @Select("""
            SELECT id, note_uuid, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_trashed, is_deleted,
                   object_version, server_version, sync_status,
                   create_time, update_time, delete_time, last_sync_time
            FROM notes
            WHERE sync_status = 'DELETE_PENDING'
               OR (sync_status = 'DIRTY' AND is_trashed = 0)
            ORDER BY update_time ASC
            """)
    List<Note> listPendingSync();

    /**
     * 按动态 WHERE 子句查询笔记列表（无搜索关键词时使用）。
     *
     * @param whereClause 动态构建的 WHERE 子句（不含 WHERE 关键字）
     */
    @Select("""
            SELECT id, note_uuid, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_trashed, is_deleted,
                   object_version, server_version, sync_status,
                   create_time, update_time, delete_time, last_sync_time
            FROM notes
            WHERE ${whereClause}
            ORDER BY is_pinned DESC, update_time DESC
            """)
    List<Note> listByWhere(@Param("whereClause") String whereClause);

    /**
     * 按动态 WHERE 子句 + LIKE 搜索查询笔记。
     *
     * @param whereClause 动态构建的 WHERE 子句
     * @param pattern     LIKE 搜索模式（含 % 通配符）
     */
    @Select("""
            SELECT id, note_uuid, title, content, content_format, summary, category_name,
                   is_pinned, is_favorite, is_archived, is_trashed, is_deleted,
                   object_version, server_version, sync_status,
                   create_time, update_time, delete_time, last_sync_time
            FROM notes
            WHERE ${whereClause}
              AND (title LIKE #{pattern} OR content LIKE #{pattern} OR summary LIKE #{pattern})
            ORDER BY is_pinned DESC, update_time DESC
            """)
    List<Note> searchLikeWithWhere(@Param("whereClause") String whereClause,
                                   @Param("pattern") String pattern);

    /**
     * 使用 FTS5 全文搜索笔记。
     *
     * @param escapedQuery 已转义的 FTS 查询字符串（含双引号包围）
     */
    @Select("""
            SELECT notes.*
            FROM note_fts
            JOIN notes ON notes.id = note_fts.rowid
            WHERE note_fts MATCH #{query}
              AND notes.is_deleted = 0
              AND notes.is_trashed = 0
              AND notes.is_archived = 0
            ORDER BY notes.is_pinned DESC, notes.update_time DESC
            """)
    List<Note> searchFts(@Param("query") String escapedQuery);

    /**
     * 按动态 WHERE 子句统计笔记数量。
     *
     * @param whereClause 动态构建的 WHERE 子句
     */
    @Select("SELECT COUNT(*) FROM notes WHERE ${whereClause}")
    long countByWhere(@Param("whereClause") String whereClause);

    /**
     * 统计各分类下的笔记数量（用于侧边栏分类列表展示）。
     */
    @Select("""
            SELECT
                COALESCE(NULLIF(TRIM(category_name), ''), '') AS name,
                COUNT(*) AS count
            FROM notes
            WHERE is_deleted = 0 AND is_trashed = 0 AND is_archived = 0
            GROUP BY name
            ORDER BY
                CASE WHEN name = '' THEN 1 ELSE 0 END,
                count DESC,
                name COLLATE NOCASE ASC
            """)
    List<java.util.Map<String, Object>> listCategorySummariesRaw();

    // ======================== 写入 ========================

    /**
     * 插入新笔记，自动回填自增 id。
     */
    @Insert("""
            INSERT INTO notes (
                note_uuid, title, content, content_format, summary, category_name,
                is_pinned, is_favorite, is_archived, is_trashed, is_deleted,
                object_version, server_version, sync_status, create_time, update_time
            ) VALUES (
                #{noteUuid}, #{title}, #{content}, #{contentFormat}, #{summary}, #{categoryName},
                #{pinned}, #{favorite}, #{archived}, #{trashed}, #{deleted},
                #{objectVersion}, #{serverVersion}, #{syncStatus}, #{createTime}, #{updateTime}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Note note);

    /**
     * 更新笔记主体内容（不含版本号、删除时间等同步字段）。
     */
    @Update("""
            UPDATE notes
            SET title = #{title}, content = #{content}, content_format = #{contentFormat},
                summary = #{summary}, category_name = #{categoryName},
                is_pinned = #{pinned}, is_favorite = #{favorite}, is_archived = #{archived},
                is_trashed = #{trashed}, sync_status = #{syncStatus},
                update_time = #{updateTime}
            WHERE id = #{id}
            """)
    int update(Note note);

    /**
     * 移入回收站。
     */
    @Update("UPDATE notes SET is_trashed = 1, update_time = #{updateTime} WHERE id = #{id}")
    int moveToTrash(@Param("id") long id, @Param("updateTime") String updateTime);

    /**
     * 从回收站恢复。
     */
    @Update("UPDATE notes SET is_trashed = 0, update_time = #{updateTime} WHERE id = #{id}")
    int restoreFromTrash(@Param("id") long id, @Param("updateTime") String updateTime);

    /**
     * 软删除（标记为已删除，状态设为 DELETE_PENDING）。
     */
    @Update("""
            UPDATE notes
            SET is_trashed = 1, is_deleted = 1,
                sync_status = #{syncStatus}, update_time = #{updateTime},
                delete_time = #{deleteTime}
            WHERE id = #{id}
            """)
    int softDelete(@Param("id") long id,
                   @Param("syncStatus") String syncStatus,
                   @Param("updateTime") String updateTime,
                   @Param("deleteTime") String deleteTime);

    /**
     * 同步成功后更新版本号和同步状态。
     */
    @Update("""
            UPDATE notes
            SET object_version = #{objectVersion},
                server_version = #{serverVersion},
                sync_status = #{syncStatus},
                last_sync_time = #{lastSyncTime}
            WHERE note_uuid = #{noteUuid}
            """)
    int markSynced(@Param("noteUuid") String noteUuid,
                   @Param("objectVersion") long objectVersion,
                   @Param("serverVersion") long serverVersion,
                   @Param("syncStatus") String syncStatus,
                   @Param("lastSyncTime") String lastSyncTime);

    /**
     * 插入远端笔记（包含所有字段，含 delete_time 和 last_sync_time）。
     */
    @Insert("""
            INSERT INTO notes (
                note_uuid, title, content, content_format, summary, category_name,
                is_pinned, is_favorite, is_archived, is_trashed, is_deleted,
                object_version, server_version, sync_status,
                create_time, update_time, delete_time, last_sync_time
            ) VALUES (
                #{noteUuid}, #{title}, #{content}, #{contentFormat}, #{summary}, #{categoryName},
                #{pinned}, #{favorite}, #{archived}, #{trashed}, #{deleted},
                #{objectVersion}, #{serverVersion}, #{syncStatus},
                #{createTime}, #{updateTime}, #{deleteTime}, #{lastSyncTime}
            )
            """)
    int insertFull(Note note);

    /**
     * 使用远端数据覆盖更新本地笔记（含所有字段）。
     */
    @Update("""
            UPDATE notes
            SET title = #{title}, content = #{content}, content_format = #{contentFormat},
                summary = #{summary}, category_name = #{categoryName},
                is_pinned = #{pinned}, is_favorite = #{favorite}, is_archived = #{archived},
                is_trashed = #{trashed}, is_deleted = #{deleted},
                object_version = #{objectVersion}, server_version = #{serverVersion},
                sync_status = #{syncStatus}, update_time = #{updateTime},
                delete_time = #{deleteTime}, last_sync_time = #{lastSyncTime}
            WHERE note_uuid = #{noteUuid}
            """)
    int updateByUuid(Note note);

    /**
     * 重命名分类。
     */
    @Update("""
            UPDATE notes
            SET category_name = #{next}
            WHERE COALESCE(NULLIF(TRIM(category_name), ''), '') = #{previous}
            """)
    int renameCategory(@Param("previous") String previous, @Param("next") String next);
}
