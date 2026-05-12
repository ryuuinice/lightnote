package com.lightnote.server.service;

import com.lightnote.server.dto.NoteCreateRequest;
import com.lightnote.server.dto.NoteResponse;
import com.lightnote.server.dto.NoteUpdateRequest;
import com.lightnote.server.entity.NoteEntity;
import com.lightnote.server.exception.BusinessException;
import com.lightnote.server.mapper.NoteMapper;
import com.lightnote.server.mapper.SyncLogMapper;
import com.lightnote.server.model.ContentFormat;
import com.lightnote.server.util.ContentTextExtractor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

    private static final String OBJECT_TYPE_NOTE = "NOTE";
    private static final int NOTE_NOT_FOUND = 2001;
    private static final int NOTE_CONFLICT = 2002;

    private final NoteMapper noteMapper;
    private final SyncLogMapper syncLogMapper;
    private final ServerVersionService serverVersionService;

    public NoteService(
            NoteMapper noteMapper,
            SyncLogMapper syncLogMapper,
            ServerVersionService serverVersionService
    ) {
        this.noteMapper = noteMapper;
        this.syncLogMapper = syncLogMapper;
        this.serverVersionService = serverVersionService;
    }

    public List<NoteResponse> listActive(Long userId) {
        return noteMapper.findActiveByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public NoteResponse create(Long userId, NoteCreateRequest request) {
        LocalDateTime now = LocalDateTime.now();
        long serverVersion = serverVersionService.nextServerVersion();

        NoteEntity note = new NoteEntity();
        note.setNoteUuid(UUID.randomUUID().toString());
        note.setUserId(userId);
        note.setTitle(request.title());
        note.setContent(request.content());
        note.setContentFormat(normalizeContentFormat(request.contentFormat(), ContentFormat.HTML).name());
        note.setSummary(normalizeSummary(request.summary(), request.content(), note.getContentFormat()));
        note.setCategoryName(request.categoryName());
        note.setIsPinned(toInt(request.pinned()));
        note.setIsFavorite(toInt(request.favorite()));
        note.setIsArchived(toInt(request.archived()));
        note.setIsDeleted(0);
        note.setObjectVersion(1L);
        note.setServerVersion(serverVersion);
        note.setCreateTime(now);
        note.setUpdateTime(now);

        noteMapper.insert(note);
        syncLogMapper.insertLog(userId, OBJECT_TYPE_NOTE, note.getNoteUuid(), "CREATE", serverVersion, now);
        return toResponse(note);
    }

    @Transactional
    public NoteResponse update(Long userId, String noteUuid, NoteUpdateRequest request) {
        NoteEntity current = requireNote(userId, noteUuid);
        if (request.baseObjectVersion() < current.getObjectVersion()) {
            throw new BusinessException(NOTE_CONFLICT, "note has been modified on server");
        }

        LocalDateTime now = LocalDateTime.now();
        long serverVersion = serverVersionService.nextServerVersion();
        current.setTitle(request.title());
        current.setContent(request.content());
        current.setContentFormat(normalizeContentFormat(request.contentFormat(), ContentFormat.from(current.getContentFormat())).name());
        current.setSummary(normalizeSummary(request.summary(), request.content(), current.getContentFormat()));
        current.setCategoryName(request.categoryName());
        current.setIsPinned(toInt(request.pinned()));
        current.setIsFavorite(toInt(request.favorite()));
        current.setIsArchived(toInt(request.archived()));
        current.setObjectVersion(current.getObjectVersion() + 1);
        current.setServerVersion(serverVersion);
        current.setUpdateTime(now);

        noteMapper.update(current);
        syncLogMapper.insertLog(userId, OBJECT_TYPE_NOTE, noteUuid, "UPDATE", serverVersion, now);
        return toResponse(current);
    }

    @Transactional
    public void delete(Long userId, String noteUuid) {
        NoteEntity current = requireNote(userId, noteUuid);
        LocalDateTime now = LocalDateTime.now();
        long serverVersion = serverVersionService.nextServerVersion();
        int updated = noteMapper.softDelete(userId, noteUuid, serverVersion, now);
        if (updated == 0) {
            throw new BusinessException(NOTE_NOT_FOUND, "note not found");
        }
        syncLogMapper.insertLog(userId, OBJECT_TYPE_NOTE, current.getNoteUuid(), "DELETE", serverVersion, now);
    }

    private NoteEntity requireNote(Long userId, String noteUuid) {
        NoteEntity note = noteMapper.findByUserIdAndUuid(userId, noteUuid);
        if (note == null || note.getIsDeleted() == 1) {
            throw new BusinessException(NOTE_NOT_FOUND, "note not found");
        }
        return note;
    }

    private NoteResponse toResponse(NoteEntity note) {
        return new NoteResponse(
                note.getNoteUuid(),
                note.getTitle(),
                note.getContent(),
                normalizeContentFormat(note.getContentFormat(), ContentFormat.HTML).name(),
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

    private ContentFormat normalizeContentFormat(String contentFormat, ContentFormat fallback) {
        ContentFormat normalized = ContentFormat.from(contentFormat);
        if (contentFormat == null || contentFormat.isBlank()) {
            return fallback == null ? ContentFormat.HTML : fallback;
        }
        return normalized;
    }

    private String normalizeSummary(String summary, String content, String contentFormat) {
        if (summary != null && !summary.isBlank()) {
            return limit(summary.strip(), 512);
        }
        if (content == null || content.isBlank()) {
            return "";
        }
        return limit(ContentTextExtractor.toPlainText(content, contentFormat), 200);
    }

    private String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
