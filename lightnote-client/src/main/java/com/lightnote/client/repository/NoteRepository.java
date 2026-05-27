package com.lightnote.client.repository;

import com.lightnote.client.config.MyBatisSqlSessionFactory;
import com.lightnote.client.mapper.NoteMapper;
import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.model.SyncStatus;
import com.lightnote.client.remote.RemoteNote;
import com.lightnote.client.remote.SyncConflictItem;
import com.lightnote.client.remote.SyncItemResult;
import com.lightnote.client.util.HtmlContentSanitizer;
import com.lightnote.client.util.HtmlTextExtractor;
import com.lightnote.client.util.MarkdownTextExtractor;
import org.apache.ibatis.session.SqlSession;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 笔记仓库，负责本地 SQLite 中笔记的增删改查、筛选、搜索与同步字段维护。
 * <p>
 * 基于 MyBatis 实现，委托 {@link NoteMapper} 完成数据库操作。
 */
public class NoteRepository {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter CONFLICT_COPY_TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String CONFLICT_COPY_MARKER = "冲突副本";

    private final Path databasePath;

    public NoteRepository(Path databasePath) {
        this.databasePath = databasePath;
    }

    // ======================== 查询方法 ========================

    /**
     * 查询默认活动笔记列表；有搜索词时退化为标题、正文、摘要的模糊搜索。
     */
    public List<Note> listActive(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            try (SqlSession session = openSession()) {
                return mapper(session).listActive();
            }
        }
        return searchLike(normalized);
    }

    public List<Note> listByFilter(String query, NoteFilter filter) {
        return listByFilter(query, filter, null);
    }

    public List<Note> listByFilter(String query, NoteFilter filter, String categoryName) {
        NoteFilter safeFilter = filter == null ? NoteFilter.ALL : filter;
        boolean categoryFilterActive = categoryName != null;
        String normalizedCategory = categoryFilterActive ? normalizeCategoryName(categoryName) : null;
        if (safeFilter == NoteFilter.ALL && !categoryFilterActive) {
            return listActive(query);
        }

        String normalized = query == null ? "" : query.trim();
        String where = buildWhereClause(safeFilter, normalizedCategory);

        if (normalized.isEmpty()) {
            try (SqlSession session = openSession()) {
                return mapper(session).listByWhere(where);
            }
        }
        return searchLikeWithWhere(normalized, where);
    }

    /**
     * 统计某个筛选入口下的笔记数量，供侧边栏徽标实时展示。
     */
    public long countByFilter(NoteFilter filter) {
        NoteFilter safeFilter = filter == null ? NoteFilter.ALL : filter;
        String where = safeFilter == NoteFilter.ALL
                ? "is_deleted = 0 AND is_trashed = 0 AND is_archived = 0"
                : buildWhereClause(safeFilter, null);
        try (SqlSession session = openSession()) {
            return mapper(session).countByWhere(where);
        }
    }

    /**
     * 汇总当前所有分类及其笔记数，未分类会归并为空字符串类别。
     */
    public List<CategorySummary> listCategorySummaries() {
        try (SqlSession session = openSession()) {
            List<Map<String, Object>> raw = mapper(session).listCategorySummariesRaw();
            List<CategorySummary> result = new ArrayList<>(raw.size());
            for (Map<String, Object> row : raw) {
                result.add(new CategorySummary(
                        (String) row.get("name"),
                        ((Number) row.get("count")).longValue()
                ));
            }
            return result;
        }
    }

    /**
     * 批量重命名现有分类，保持同一分类下的笔记一起迁移。
     */
    public void renameCategory(String previousName, String nextName) {
        String previous = normalizeCategoryName(previousName);
        String next = normalizeCategoryName(nextName);
        if (previous.isEmpty() || next.isEmpty() || previous.equals(next)) {
            return;
        }
        try (SqlSession session = openSession()) {
            mapper(session).renameCategory(previous, next);
            session.commit();
        }
    }

    /**
     * 创建一条默认 Markdown 空笔记，并立即落库以便界面可以直接选中编辑。
     */
    public Note createEmpty() {
        Note note = new Note();
        String now = now();
        note.setNoteUuid(UUID.randomUUID().toString());
        note.setTitle("未命名笔记");
        note.setContent("");
        note.setContentFormat(ContentFormat.MARKDOWN);
        note.setSummary("");
        note.setCategoryName("");
        note.setPinned(false);
        note.setFavorite(false);
        note.setArchived(false);
        note.setTrashed(false);
        note.setDeleted(false);
        note.setObjectVersion(0);
        note.setServerVersion(0);
        note.setSyncStatus(SyncStatus.DIRTY);
        note.setCreateTime(now);
        note.setUpdateTime(now);

        try (SqlSession session = openSession()) {
            mapper(session).insert(note);
            session.commit();
        }
        return note;
    }

    /**
     * 保存笔记当前编辑结果，同时规范化标题、摘要、分类和本地同步状态。
     */
    public void save(Note note) {
        if (note.getId() == null) {
            throw new IllegalArgumentException("Cannot save note without id");
        }
        note.setContent(normalizeStoredContent(note.getContentFormat(), note.getContent()));
        note.setTitle(normalizeTitle(note.getTitle(), note.getContent()));
        note.setSummary(buildSummary(note.getSummary(), note.getContent(), note.getContentFormat()));
        note.setCategoryName(normalizeCategoryName(note.getCategoryName()));
        note.setUpdateTime(now());
        if (note.getSyncStatus() == SyncStatus.SYNCED) {
            note.setSyncStatus(SyncStatus.DIRTY);
        }

        try (SqlSession session = openSession()) {
            mapper(session).update(note);
            session.commit();
        }
    }

    public void moveToTrash(Note note) {
        if (note == null || note.getId() == null) {
            return;
        }
        String now = now();
        try (SqlSession session = openSession()) {
            mapper(session).moveToTrash(note.getId(), now);
            session.commit();
        }
    }

    public void restoreFromTrash(Note note) {
        if (note == null || note.getId() == null) {
            return;
        }
        String now = now();
        try (SqlSession session = openSession()) {
            mapper(session).restoreFromTrash(note.getId(), now);
            session.commit();
        }
    }

    public void softDelete(Note note) {
        if (note == null || note.getId() == null) {
            return;
        }
        String now = now();
        try (SqlSession session = openSession()) {
            mapper(session).softDelete(note.getId(), SyncStatus.DELETE_PENDING.name(), now, now);
            session.commit();
        }
    }

    public List<Note> listPendingSync() {
        try (SqlSession session = openSession()) {
            return mapper(session).listPendingSync();
        }
    }

    public Note findByUuid(String noteUuid) {
        try (SqlSession session = openSession()) {
            return mapper(session).findByUuid(noteUuid);
        }
    }

    /**
     * 根据服务端确认结果回写版本号；若用户在同步期间继续编辑，则保持 DIRTY 以等待下一轮推送。
     */
    public void markSynced(SyncItemResult item, String expectedUpdateTime) {
        Note current = findByUuid(item.noteUuid());
        if (current == null) {
            return;
        }
        boolean unchangedSincePush = expectedUpdateTime != null && expectedUpdateTime.equals(current.getUpdateTime());
        SyncStatus nextStatus = unchangedSincePush || current.getSyncStatus() == SyncStatus.DELETE_PENDING
                ? SyncStatus.SYNCED
                : SyncStatus.DIRTY;
        try (SqlSession session = openSession()) {
            mapper(session).markSynced(item.noteUuid(), item.objectVersion(), item.serverVersion(),
                    nextStatus.name(), now());
            session.commit();
        }
    }

    /**
     * 应用远端增量变化；若本地仍有未同步修改，则先保留一份冲突副本再覆盖为服务端版本。
     */
    public void applyRemote(RemoteNote remote) {
        Note existing = findByUuid(remote.noteUuid());
        if (existing != null && existing.getServerVersion() >= remote.serverVersion()) {
            return;
        }

        if (existing != null && (existing.getSyncStatus() == SyncStatus.DIRTY
                || existing.getSyncStatus() == SyncStatus.DELETE_PENDING
                || existing.getSyncStatus() == SyncStatus.CONFLICT)) {
            createConflictCopy(existing);
        }

        if (existing == null) {
            insertRemote(remote);
        } else {
            updateRemote(remote);
        }
    }

    /**
     * 基于当前本地笔记创建冲突副本，保留正文格式和最新本地内容，供用户后续人工处理。
     */
    public Note createConflictCopy(Note local) {
        Note copy = new Note();
        String now = now();
        copy.setNoteUuid(UUID.randomUUID().toString());
        copy.setTitle(buildConflictCopyTitle(local.getTitle(), now));
        copy.setContentFormat(local.getContentFormat());
        copy.setContent(normalizeStoredContent(copy.getContentFormat(), local.getContent()));
        copy.setSummary(buildSummary(local.getSummary(), copy.getContent(), copy.getContentFormat()));
        copy.setCategoryName(local.getCategoryName());
        copy.setPinned(local.isPinned());
        copy.setFavorite(local.isFavorite());
        copy.setArchived(local.isArchived());
        copy.setTrashed(false);
        copy.setDeleted(false);
        copy.setObjectVersion(0);
        copy.setServerVersion(0);
        copy.setSyncStatus(SyncStatus.DIRTY);
        copy.setCreateTime(now);
        copy.setUpdateTime(now);

        try (SqlSession session = openSession()) {
            mapper(session).insert(copy);
            session.commit();
        }
        return copy;
    }

    /**
     * 用服务端版本覆盖原笔记，和冲突副本配合完成"保留本地修改 + 恢复服务端原件"的策略。
     */
    public void resolveConflict(SyncConflictItem conflict) {
        if (conflict == null || conflict.serverNote() == null) {
            return;
        }
        updateRemote(conflict.serverNote());
    }

    // ======================== 私有方法 ========================

    private SqlSession openSession() {
        return MyBatisSqlSessionFactory.getInstance(databasePath).openSession();
    }

    private NoteMapper mapper(SqlSession session) {
        return session.getMapper(NoteMapper.class);
    }

    private void insertRemote(RemoteNote remote) {
        Note note = noteFromRemote(remote);
        try (SqlSession session = openSession()) {
            mapper(session).insertFull(note);
            session.commit();
        }
    }

    private void updateRemote(RemoteNote remote) {
        Note note = noteFromRemote(remote);
        try (SqlSession session = openSession()) {
            mapper(session).updateByUuid(note);
            session.commit();
        }
    }

    private Note noteFromRemote(RemoteNote remote) {
        Note note = new Note();
        note.setNoteUuid(remote.noteUuid());
        note.setTitle(remote.title());
        note.setContentFormat(ContentFormat.from(remote.contentFormat()));
        note.setContent(normalizeStoredContent(note.getContentFormat(), remote.content()));
        note.setSummary(buildSummary(remote.summary(), note.getContent(), note.getContentFormat()));
        note.setCategoryName(normalizeCategoryName(remote.categoryName()));
        note.setPinned(remote.pinned());
        note.setFavorite(remote.favorite());
        note.setArchived(remote.archived());
        note.setTrashed(false);
        note.setDeleted(remote.deleted());
        note.setObjectVersion(remote.objectVersion());
        note.setServerVersion(remote.serverVersion());
        note.setSyncStatus(SyncStatus.SYNCED);
        note.setCreateTime(nullToNow(remote.createTime()));
        note.setUpdateTime(nullToNow(remote.updateTime()));
        note.setDeleteTime(remote.deleteTime());
        note.setLastSyncTime(now());
        return note;
    }

    private List<Note> searchFts(String query) {
        try (SqlSession session = openSession()) {
            return mapper(session).searchFts(escapeFtsQuery(query));
        }
    }

    private List<Note> searchLike(String query) {
        return searchLikeWithWhere(query, "is_deleted = 0 AND is_trashed = 0 AND is_archived = 0");
    }

    private List<Note> searchLikeWithWhere(String query, String where) {
        String pattern = "%" + query + "%";
        try (SqlSession session = openSession()) {
            return mapper(session).searchLikeWithWhere(where, pattern);
        }
    }

    private String buildWhereClause(NoteFilter filter, String categoryName) {
        String baseWhere = switch (filter) {
            case TODAY -> "is_deleted = 0 AND is_trashed = 0 AND is_archived = 0 AND substr(create_time, 1, 10) = date('now', 'localtime')";
            case RECENT_7_DAYS -> "is_deleted = 0 AND is_trashed = 0 AND is_archived = 0 AND substr(create_time, 1, 10) >= date('now', '-7 days', 'localtime')";
            case FAVORITES -> "is_deleted = 0 AND is_trashed = 0 AND is_archived = 0 AND is_favorite = 1";
            case TRASH -> "is_deleted = 0 AND is_trashed = 1";
            case ARCHIVED -> "is_deleted = 0 AND is_trashed = 0 AND is_archived = 1";
            case CONFLICT_COPIES -> "is_deleted = 0 AND is_trashed = 0 AND title LIKE '%" + CONFLICT_COPY_MARKER + "%'";
            case ALL -> "is_deleted = 0 AND is_trashed = 0 AND is_archived = 0";
        };
        if (categoryName == null) {
            return baseWhere;
        }
        String normalizedCategory = normalizeCategoryName(categoryName);
        return baseWhere + " AND COALESCE(NULLIF(TRIM(category_name), ''), '') = " + quoteSql(normalizedCategory);
    }

    private String buildConflictCopyTitle(String title, String now) {
        String baseTitle = title == null || title.isBlank() ? "未命名笔记" : title.strip();
        LocalDateTime parsedTime = LocalDateTime.parse(now, FORMATTER);
        return baseTitle + "（冲突副本 " + parsedTime.format(CONFLICT_COPY_TITLE_TIME_FORMATTER) + "）";
    }

    private String normalizeCategoryName(String categoryName) {
        return categoryName == null ? "" : categoryName.strip();
    }

    private String quoteSql(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    public record CategorySummary(String name, long count) {
    }

    private String normalizeTitle(String title, String content) {
        if (title != null && !title.isBlank()) {
            return title.strip();
        }
        String plainContent = plainText(content, ContentFormat.HTML);
        if (!plainContent.isBlank()) {
            String firstLine = plainContent.lines().findFirst().orElse("").strip();
            if (!firstLine.isEmpty()) {
                return firstLine.length() > 255 ? firstLine.substring(0, 255) : firstLine;
            }
        }
        return "未命名笔记";
    }

    private String buildSummary(String summary, String content, ContentFormat contentFormat) {
        String source = plainText(content, contentFormat).strip().replaceAll("\\s+", " ");
        if (source.isEmpty() && summary != null) {
            source = plainText(summary, contentFormat).strip();
        }
        return source.length() > 200 ? source.substring(0, 200) : source;
    }

    private String plainText(String value, ContentFormat contentFormat) {
        if (contentFormat == ContentFormat.MARKDOWN) {
            return MarkdownTextExtractor.toPlainText(value);
        }
        return HtmlTextExtractor.toPlainText(value);
    }

    private String normalizeStoredContent(ContentFormat contentFormat, String value) {
        if (contentFormat == ContentFormat.MARKDOWN) {
            return value == null ? "" : value;
        }
        return HtmlContentSanitizer.normalizeForStorage(value);
    }

    private String escapeFtsQuery(String query) {
        return "\"" + query.replace("\"", "\"\"") + "\"";
    }

    private String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    private String nullToNow(String value) {
        return value == null || value.isBlank() ? now() : value;
    }
}
