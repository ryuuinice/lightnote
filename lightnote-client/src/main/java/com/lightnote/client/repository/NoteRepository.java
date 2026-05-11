package com.lightnote.client.repository;

import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.model.SyncStatus;
import com.lightnote.client.remote.RemoteNote;
import com.lightnote.client.remote.SyncConflictItem;
import com.lightnote.client.remote.SyncItemResult;
import com.lightnote.client.util.HtmlTextExtractor;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NoteRepository {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String CONFLICT_COPY_MARKER = " - 冲突副本 - ";

    private final Path databasePath;

    public NoteRepository(Path databasePath) {
        this.databasePath = databasePath;
    }

    public List<Note> listActive(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return queryNotes("""
                    SELECT *
                    FROM notes
                    WHERE is_deleted = 0
                      AND is_archived = 0
                    ORDER BY is_pinned DESC, update_time DESC
                    """);
        }
        return searchLike(normalized);
    }

    public List<Note> listByFilter(String query, NoteFilter filter) {
        NoteFilter safeFilter = filter == null ? NoteFilter.ALL : filter;
        if (safeFilter == NoteFilter.ALL) {
            return listActive(query);
        }

        String normalized = query == null ? "" : query.trim();
        String where = whereClauseForFilter(safeFilter);

        if (normalized.isEmpty()) {
            return queryNotes("""
                    SELECT *
                    FROM notes
                    WHERE %s
                    ORDER BY is_pinned DESC, update_time DESC
                    """.formatted(where));
        }
        return searchLikeWithWhere(normalized, where);
    }

    public long countByFilter(NoteFilter filter) {
        NoteFilter safeFilter = filter == null ? NoteFilter.ALL : filter;
        String where = safeFilter == NoteFilter.ALL
                ? "is_deleted = 0 AND is_archived = 0"
                : whereClauseForFilter(safeFilter);
        String sql = "SELECT COUNT(*) FROM notes WHERE " + where;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to count notes for filter " + safeFilter, ex);
        }
    }

    public Note createEmpty() {
        Note note = new Note();
        String now = now();
        note.setNoteUuid(UUID.randomUUID().toString());
        note.setTitle("未命名笔记");
        note.setContent("");
        note.setSummary("");
        note.setCategoryName("");
        note.setPinned(false);
        note.setFavorite(false);
        note.setArchived(false);
        note.setDeleted(false);
        note.setObjectVersion(0);
        note.setServerVersion(0);
        note.setSyncStatus(SyncStatus.DIRTY);
        note.setCreateTime(now);
        note.setUpdateTime(now);

        String sql = """
                INSERT INTO notes (
                    note_uuid, title, content, summary, category_name,
                    is_pinned, is_favorite, is_archived, is_deleted,
                    object_version, server_version, sync_status, create_time, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindNoteForInsert(statement, note);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    note.setId(keys.getLong(1));
                }
            }
            return note;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create note", ex);
        }
    }

    public void save(Note note) {
        if (note.getId() == null) {
            throw new IllegalArgumentException("Cannot save note without id");
        }
        note.setTitle(normalizeTitle(note.getTitle(), note.getContent()));
        note.setSummary(buildSummary(note.getSummary(), note.getContent()));
        note.setUpdateTime(now());
        if (note.getSyncStatus() == SyncStatus.SYNCED) {
            note.setSyncStatus(SyncStatus.DIRTY);
        }

        String sql = """
                UPDATE notes
                SET title = ?,
                    content = ?,
                    summary = ?,
                    category_name = ?,
                    is_pinned = ?,
                    is_favorite = ?,
                    is_archived = ?,
                    sync_status = ?,
                    update_time = ?
                WHERE id = ?
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, note.getTitle());
            statement.setString(2, note.getContent());
            statement.setString(3, note.getSummary());
            statement.setString(4, note.getCategoryName());
            statement.setInt(5, note.isPinned() ? 1 : 0);
            statement.setInt(6, note.isFavorite() ? 1 : 0);
            statement.setInt(7, note.isArchived() ? 1 : 0);
            statement.setString(8, note.getSyncStatus().name());
            statement.setString(9, note.getUpdateTime());
            statement.setLong(10, note.getId());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save note", ex);
        }
    }

    public void softDelete(Note note) {
        if (note == null || note.getId() == null) {
            return;
        }
        String now = now();
        String sql = """
                UPDATE notes
                SET is_deleted = 1,
                    sync_status = ?,
                    update_time = ?,
                    delete_time = ?
                WHERE id = ?
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SyncStatus.DELETE_PENDING.name());
            statement.setString(2, now);
            statement.setString(3, now);
            statement.setLong(4, note.getId());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete note", ex);
        }
    }

    public List<Note> listPendingSync() {
        return queryNotes("""
                SELECT *
                FROM notes
                WHERE sync_status IN ('DIRTY', 'DELETE_PENDING')
                ORDER BY update_time ASC
                """);
    }

    public Note findByUuid(String noteUuid) {
        String sql = "SELECT * FROM notes WHERE note_uuid = ? LIMIT 1";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, noteUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Note> found = readNotes(resultSet);
                return found.isEmpty() ? null : found.get(0);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to find note " + noteUuid, ex);
        }
    }

    public void markSynced(SyncItemResult item, String expectedUpdateTime) {
        Note current = findByUuid(item.noteUuid());
        if (current == null) {
            return;
        }
        boolean unchangedSincePush = expectedUpdateTime != null && expectedUpdateTime.equals(current.getUpdateTime());
        SyncStatus nextStatus = unchangedSincePush || current.getSyncStatus() == SyncStatus.DELETE_PENDING
                ? SyncStatus.SYNCED
                : SyncStatus.DIRTY;
        String sql = """
                UPDATE notes
                SET object_version = ?,
                    server_version = ?,
                    sync_status = ?,
                    last_sync_time = ?
                WHERE note_uuid = ?
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, item.objectVersion());
            statement.setLong(2, item.serverVersion());
            statement.setString(3, nextStatus.name());
            statement.setString(4, now());
            statement.setString(5, item.noteUuid());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to mark note synced", ex);
        }
    }

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

    public Note createConflictCopy(Note local) {
        Note copy = new Note();
        String now = now();
        copy.setNoteUuid(UUID.randomUUID().toString());
        copy.setTitle(local.getTitle() + CONFLICT_COPY_MARKER + now.replace(":", "").replace("-", "").replace("T", "-"));
        copy.setContent(local.getContent());
        copy.setSummary(local.getSummary());
        copy.setCategoryName(local.getCategoryName());
        copy.setPinned(local.isPinned());
        copy.setFavorite(local.isFavorite());
        copy.setArchived(local.isArchived());
        copy.setDeleted(false);
        copy.setObjectVersion(0);
        copy.setServerVersion(0);
        copy.setSyncStatus(SyncStatus.DIRTY);
        copy.setCreateTime(now);
        copy.setUpdateTime(now);

        String sql = """
                INSERT INTO notes (
                    note_uuid, title, content, summary, category_name,
                    is_pinned, is_favorite, is_archived, is_deleted,
                    object_version, server_version, sync_status, create_time, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindNoteForInsert(statement, copy);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    copy.setId(keys.getLong(1));
                }
            }
            return copy;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create conflict copy", ex);
        }
    }

    public void resolveConflict(SyncConflictItem conflict) {
        if (conflict == null || conflict.serverNote() == null) {
            return;
        }
        updateRemote(conflict.serverNote());
    }

    private void insertRemote(RemoteNote remote) {
        Note note = noteFromRemote(remote);
        String sql = """
                INSERT INTO notes (
                    note_uuid, title, content, summary, category_name,
                    is_pinned, is_favorite, is_archived, is_deleted,
                    object_version, server_version, sync_status,
                    create_time, update_time, delete_time, last_sync_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, note.getNoteUuid());
            statement.setString(2, note.getTitle());
            statement.setString(3, note.getContent());
            statement.setString(4, note.getSummary());
            statement.setString(5, note.getCategoryName());
            statement.setInt(6, note.isPinned() ? 1 : 0);
            statement.setInt(7, note.isFavorite() ? 1 : 0);
            statement.setInt(8, note.isArchived() ? 1 : 0);
            statement.setInt(9, note.isDeleted() ? 1 : 0);
            statement.setLong(10, note.getObjectVersion());
            statement.setLong(11, note.getServerVersion());
            statement.setString(12, note.getSyncStatus().name());
            statement.setString(13, note.getCreateTime());
            statement.setString(14, note.getUpdateTime());
            statement.setString(15, note.getDeleteTime());
            statement.setString(16, note.getLastSyncTime());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to insert remote note", ex);
        }
    }

    private void updateRemote(RemoteNote remote) {
        String sql = """
                UPDATE notes
                SET title = ?,
                    content = ?,
                    summary = ?,
                    category_name = ?,
                    is_pinned = ?,
                    is_favorite = ?,
                    is_archived = ?,
                    is_deleted = ?,
                    object_version = ?,
                    server_version = ?,
                    sync_status = ?,
                    update_time = ?,
                    delete_time = ?,
                    last_sync_time = ?
                WHERE note_uuid = ?
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, remote.title());
            statement.setString(2, remote.content());
            statement.setString(3, remote.summary());
            statement.setString(4, remote.categoryName());
            statement.setInt(5, remote.pinned() ? 1 : 0);
            statement.setInt(6, remote.favorite() ? 1 : 0);
            statement.setInt(7, remote.archived() ? 1 : 0);
            statement.setInt(8, remote.deleted() ? 1 : 0);
            statement.setLong(9, remote.objectVersion());
            statement.setLong(10, remote.serverVersion());
            statement.setString(11, SyncStatus.SYNCED.name());
            statement.setString(12, nullToNow(remote.updateTime()));
            statement.setString(13, remote.deleteTime());
            statement.setString(14, now());
            statement.setString(15, remote.noteUuid());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update remote note", ex);
        }
    }

    private Note noteFromRemote(RemoteNote remote) {
        Note note = new Note();
        note.setNoteUuid(remote.noteUuid());
        note.setTitle(remote.title());
        note.setContent(remote.content());
        note.setSummary(remote.summary());
        note.setCategoryName(remote.categoryName());
        note.setPinned(remote.pinned());
        note.setFavorite(remote.favorite());
        note.setArchived(remote.archived());
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
        String sql = """
                SELECT notes.*
                FROM note_fts
                JOIN notes ON notes.id = note_fts.rowid
                WHERE note_fts MATCH ?
                  AND notes.is_deleted = 0
                  AND notes.is_archived = 0
                ORDER BY notes.is_pinned DESC, notes.update_time DESC
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, escapeFtsQuery(query));
            try (ResultSet resultSet = statement.executeQuery()) {
                return readNotes(resultSet);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("FTS search failed", ex);
        }
    }

    private List<Note> searchLike(String query) {
        return searchLikeWithWhere(query, "is_deleted = 0 AND is_archived = 0");
    }

    private String whereClauseForFilter(NoteFilter filter) {
        return switch (filter) {
            case TODAY -> "is_deleted = 0 AND is_archived = 0 AND substr(update_time, 1, 10) = date('now', 'localtime')";
            case RECENT_7_DAYS -> "is_deleted = 0 AND is_archived = 0 AND substr(update_time, 1, 10) >= date('now', '-7 days', 'localtime')";
            case FAVORITES -> "is_deleted = 0 AND is_archived = 0 AND is_favorite = 1";
            case ARCHIVED -> "is_deleted = 0 AND is_archived = 1";
            case CONFLICT_COPIES -> "is_deleted = 0 AND title LIKE '%" + CONFLICT_COPY_MARKER + "%'";
            case ALL -> "is_deleted = 0 AND is_archived = 0";
        };
    }

    private List<Note> searchLikeWithWhere(String query, String where) {
        String sql = """
                SELECT *
                FROM notes
                WHERE %s
                  AND (title LIKE ? OR content LIKE ? OR summary LIKE ?)
                ORDER BY is_pinned DESC, update_time DESC
                """.formatted(where);
        String pattern = "%" + query + "%";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            statement.setString(3, pattern);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readNotes(resultSet);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("LIKE search failed", ex);
        }
    }

    private List<Note> queryNotes(String sql) {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return readNotes(resultSet);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query notes", ex);
        }
    }

    private List<Note> readNotes(ResultSet resultSet) throws SQLException {
        List<Note> notes = new ArrayList<>();
        while (resultSet.next()) {
            Note note = new Note();
            note.setId(resultSet.getLong("id"));
            note.setNoteUuid(resultSet.getString("note_uuid"));
            note.setTitle(resultSet.getString("title"));
            note.setContent(resultSet.getString("content"));
            note.setSummary(resultSet.getString("summary"));
            note.setCategoryName(resultSet.getString("category_name"));
            note.setPinned(resultSet.getInt("is_pinned") == 1);
            note.setFavorite(resultSet.getInt("is_favorite") == 1);
            note.setArchived(resultSet.getInt("is_archived") == 1);
            note.setDeleted(resultSet.getInt("is_deleted") == 1);
            note.setObjectVersion(resultSet.getLong("object_version"));
            note.setServerVersion(resultSet.getLong("server_version"));
            note.setSyncStatus(SyncStatus.valueOf(resultSet.getString("sync_status")));
            note.setCreateTime(resultSet.getString("create_time"));
            note.setUpdateTime(resultSet.getString("update_time"));
            note.setDeleteTime(resultSet.getString("delete_time"));
            note.setLastSyncTime(resultSet.getString("last_sync_time"));
            notes.add(note);
        }
        return notes;
    }

    private void bindNoteForInsert(PreparedStatement statement, Note note) throws SQLException {
        statement.setString(1, note.getNoteUuid());
        statement.setString(2, note.getTitle());
        statement.setString(3, note.getContent());
        statement.setString(4, note.getSummary());
        statement.setString(5, note.getCategoryName());
        statement.setInt(6, note.isPinned() ? 1 : 0);
        statement.setInt(7, note.isFavorite() ? 1 : 0);
        statement.setInt(8, note.isArchived() ? 1 : 0);
        statement.setInt(9, note.isDeleted() ? 1 : 0);
        statement.setLong(10, note.getObjectVersion());
        statement.setLong(11, note.getServerVersion());
        statement.setString(12, note.getSyncStatus().name());
        statement.setString(13, note.getCreateTime());
        statement.setString(14, note.getUpdateTime());
    }

    private String normalizeTitle(String title, String content) {
        if (title != null && !title.isBlank()) {
            return title.strip();
        }
        String plainContent = stripHtml(content);
        if (!plainContent.isBlank()) {
            String firstLine = plainContent.lines().findFirst().orElse("").strip();
            if (!firstLine.isEmpty()) {
                return firstLine.length() > 255 ? firstLine.substring(0, 255) : firstLine;
            }
        }
        return "未命名笔记";
    }

    private String buildSummary(String summary, String content) {
        String source = stripHtml(content).strip().replaceAll("\\s+", " ");
        if (source.isEmpty() && summary != null) {
            source = stripHtml(summary).strip();
        }
        return source.length() > 200 ? source.substring(0, 200) : source;
    }

    private String stripHtml(String value) {
        return HtmlTextExtractor.toPlainText(value);
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

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }
}
