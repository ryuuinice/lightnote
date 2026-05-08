package com.lightnote.server.service;

import com.lightnote.server.dto.NoteResponse;
import com.lightnote.server.dto.SyncChangeNote;
import com.lightnote.server.dto.SyncChangesResponse;
import com.lightnote.server.dto.SyncConflictItem;
import com.lightnote.server.dto.SyncItemResult;
import com.lightnote.server.dto.SyncNoteRequest;
import com.lightnote.server.dto.SyncPushRequest;
import com.lightnote.server.dto.SyncPushResponse;
import com.lightnote.server.entity.NoteEntity;
import com.lightnote.server.entity.SyncChangeEntity;
import com.lightnote.server.mapper.NoteMapper;
import com.lightnote.server.mapper.SyncLogMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncService {

    private static final String OBJECT_TYPE_NOTE = "NOTE";
    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_DELETE = "DELETE";

    private final NoteMapper noteMapper;
    private final SyncLogMapper syncLogMapper;
    private final ServerVersionService serverVersionService;

    public SyncService(
            NoteMapper noteMapper,
            SyncLogMapper syncLogMapper,
            ServerVersionService serverVersionService
    ) {
        this.noteMapper = noteMapper;
        this.syncLogMapper = syncLogMapper;
        this.serverVersionService = serverVersionService;
    }

    @Transactional
    public SyncPushResponse push(Long userId, SyncPushRequest request) {
        List<SyncItemResult> successItems = new ArrayList<>();
        List<SyncConflictItem> conflictItems = new ArrayList<>();

        List<SyncNoteRequest> notes = request.notes() == null ? List.of() : request.notes();
        for (SyncNoteRequest item : notes) {
            String operation = normalizeOperation(item.operation());
            NoteEntity current = noteMapper.findByUserIdAndUuidForUpdate(userId, item.noteUuid());

            if (OP_DELETE.equals(operation)) {
                handleDelete(userId, item, current, successItems, conflictItems);
            } else if (current == null) {
                handleCreate(userId, item, operation, successItems);
            } else {
                handleUpdate(userId, item, current, operation, successItems, conflictItems);
            }
        }

        Long currentServerVersion = syncLogMapper.getCurrentServerVersion();
        return new SyncPushResponse(
                currentServerVersion == null ? request.lastSyncVersion() : currentServerVersion,
                successItems,
                conflictItems
        );
    }

    public SyncChangesResponse changes(Long userId, long sinceVersion, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<SyncChangeEntity> rows = syncLogMapper.findNoteChanges(userId, sinceVersion, safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<SyncChangeNote> notes = rows.stream()
                .limit(safeLimit)
                .map(this::toChangeNote)
                .toList();
        long responseServerVersion = notes.stream()
                .mapToLong(SyncChangeNote::serverVersion)
                .max()
                .orElseGet(() -> {
                    Long current = syncLogMapper.getCurrentServerVersion();
                    return current == null ? sinceVersion : current;
                });
        return new SyncChangesResponse(responseServerVersion, hasMore, notes);
    }

    private void handleCreate(
            Long userId,
            SyncNoteRequest item,
            String operation,
            List<SyncItemResult> successItems
    ) {
        LocalDateTime now = LocalDateTime.now();
        long serverVersion = serverVersionService.nextServerVersion();

        NoteEntity note = new NoteEntity();
        note.setNoteUuid(item.noteUuid());
        note.setUserId(userId);
        applyClientFields(note, item);
        note.setIsDeleted(Boolean.TRUE.equals(item.deleted()) ? 1 : 0);
        note.setObjectVersion(1L);
        note.setServerVersion(serverVersion);
        note.setCreateTime(now);
        note.setUpdateTime(now);
        note.setDeleteTime(note.getIsDeleted() == 1 ? now : null);

        noteMapper.insert(note);
        syncLogMapper.insertLog(userId, OBJECT_TYPE_NOTE, item.noteUuid(), operation, serverVersion, now);
        successItems.add(new SyncItemResult(item.noteUuid(), note.getObjectVersion(), serverVersion));
    }

    private void handleUpdate(
            Long userId,
            SyncNoteRequest item,
            NoteEntity current,
            String operation,
            List<SyncItemResult> successItems,
            List<SyncConflictItem> conflictItems
    ) {
        if (isConflict(item, current)) {
            conflictItems.add(toConflict(item, current));
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long serverVersion = serverVersionService.nextServerVersion();
        applyClientFields(current, item);
        current.setIsDeleted(0);
        current.setObjectVersion(current.getObjectVersion() + 1);
        current.setServerVersion(serverVersion);
        current.setUpdateTime(now);
        current.setDeleteTime(null);

        noteMapper.updateFromSync(current);
        syncLogMapper.insertLog(userId, OBJECT_TYPE_NOTE, item.noteUuid(), operation, serverVersion, now);
        successItems.add(new SyncItemResult(item.noteUuid(), current.getObjectVersion(), serverVersion));
    }

    private void handleDelete(
            Long userId,
            SyncNoteRequest item,
            NoteEntity current,
            List<SyncItemResult> successItems,
            List<SyncConflictItem> conflictItems
    ) {
        if (current == null) {
            Long serverVersion = syncLogMapper.getCurrentServerVersion();
            successItems.add(new SyncItemResult(item.noteUuid(), 0, serverVersion == null ? 0 : serverVersion));
            return;
        }
        if (isConflict(item, current)) {
            conflictItems.add(toConflict(item, current));
            return;
        }
        if (current.getIsDeleted() == 1) {
            successItems.add(new SyncItemResult(item.noteUuid(), current.getObjectVersion(), current.getServerVersion()));
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long serverVersion = serverVersionService.nextServerVersion();
        current.setIsDeleted(1);
        current.setObjectVersion(current.getObjectVersion() + 1);
        current.setServerVersion(serverVersion);
        current.setUpdateTime(now);
        current.setDeleteTime(now);

        noteMapper.updateFromSync(current);
        syncLogMapper.insertLog(userId, OBJECT_TYPE_NOTE, item.noteUuid(), OP_DELETE, serverVersion, now);
        successItems.add(new SyncItemResult(item.noteUuid(), current.getObjectVersion(), serverVersion));
    }

    private boolean isConflict(SyncNoteRequest item, NoteEntity current) {
        return item.baseObjectVersion() < current.getObjectVersion();
    }

    private SyncConflictItem toConflict(SyncNoteRequest item, NoteEntity current) {
        return new SyncConflictItem(
                item.noteUuid(),
                item.baseObjectVersion(),
                current.getObjectVersion(),
                toNoteResponse(current)
        );
    }

    private String normalizeOperation(String operation) {
        String normalized = operation == null ? OP_UPDATE : operation.trim().toUpperCase();
        if (!OP_CREATE.equals(normalized) && !OP_UPDATE.equals(normalized) && !OP_DELETE.equals(normalized)) {
            return OP_UPDATE;
        }
        return normalized;
    }

    private void applyClientFields(NoteEntity note, SyncNoteRequest item) {
        note.setTitle(item.title() == null || item.title().isBlank() ? "未命名笔记" : item.title());
        note.setContent(item.content());
        note.setSummary(normalizeSummary(item.summary(), item.content()));
        note.setCategoryName(item.categoryName());
        note.setIsPinned(toInt(item.pinned()));
        note.setIsFavorite(toInt(item.favorite()));
        note.setIsArchived(toInt(item.archived()));
    }

    private SyncChangeNote toChangeNote(SyncChangeEntity row) {
        return new SyncChangeNote(
                row.getNoteUuid(),
                row.getOperation(),
                row.getObjectVersion(),
                row.getLogServerVersion(),
                row.getTitle(),
                row.getContent(),
                row.getSummary(),
                row.getCategoryName(),
                row.getIsPinned() == 1,
                row.getIsFavorite() == 1,
                row.getIsArchived() == 1,
                row.getIsDeleted() == 1,
                row.getCreateTime(),
                row.getUpdateTime(),
                row.getDeleteTime()
        );
    }

    private NoteResponse toNoteResponse(NoteEntity note) {
        return new NoteResponse(
                note.getNoteUuid(),
                note.getTitle(),
                note.getContent(),
                note.getSummary(),
                note.getCategoryName(),
                note.getIsPinned() == 1,
                note.getIsFavorite() == 1,
                note.getIsArchived() == 1,
                note.getIsDeleted() == 1,
                note.getObjectVersion(),
                note.getServerVersion(),
                note.getCreateTime(),
                note.getUpdateTime(),
                note.getDeleteTime()
        );
    }

    private int toInt(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private String normalizeSummary(String summary, String content) {
        if (summary != null && !summary.isBlank()) {
            return limit(summary.strip(), 512);
        }
        if (content == null || content.isBlank()) {
            return "";
        }
        return limit(content.strip().replaceAll("\\s+", " "), 200);
    }

    private String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
