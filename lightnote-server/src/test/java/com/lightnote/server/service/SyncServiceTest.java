package com.lightnote.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lightnote.server.dto.SyncChangeNote;
import com.lightnote.server.dto.SyncChangesResponse;
import com.lightnote.server.dto.SyncNoteRequest;
import com.lightnote.server.dto.SyncPushRequest;
import com.lightnote.server.dto.SyncPushResponse;
import com.lightnote.server.entity.NoteEntity;
import com.lightnote.server.entity.SyncChangeEntity;
import com.lightnote.server.mapper.NoteMapper;
import com.lightnote.server.mapper.SyncLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    private NoteMapper noteMapper;

    @Mock
    private SyncLogMapper syncLogMapper;

    @Mock
    private ServerVersionService serverVersionService;

    @InjectMocks
    private SyncService syncService;

    @Captor
    private ArgumentCaptor<NoteEntity> noteCaptor;

    @Test
    void pushCreatesNewNoteWithNormalizedFields() {
        SyncNoteRequest note = new SyncNoteRequest(
                "note-1",
                "create",
                0L,
                " ",
                "  hello\n   world  ",
                null,
                "work",
                true,
                false,
                true,
                false,
                "2026-05-08T10:00:00"
        );
        when(noteMapper.findByUserIdAndUuidForUpdate(7L, "note-1")).thenReturn(null);
        when(serverVersionService.nextServerVersion()).thenReturn(11L);
        when(syncLogMapper.getCurrentServerVersion()).thenReturn(11L);

        SyncPushResponse response = syncService.push(7L, new SyncPushRequest(5L, List.of(note)));

        assertEquals(11L, response.serverVersion());
        assertEquals(1, response.successItems().size());
        assertTrue(response.conflictItems().isEmpty());
        verify(noteMapper).insert(noteCaptor.capture());
        NoteEntity inserted = noteCaptor.getValue();
        assertEquals("note-1", inserted.getNoteUuid());
        assertEquals(7L, inserted.getUserId());
        assertEquals("未命名笔记", inserted.getTitle());
        assertEquals("hello world", inserted.getSummary());
        assertEquals("  hello\n   world  ", inserted.getContent());
        assertEquals("work", inserted.getCategoryName());
        assertEquals(1, inserted.getIsPinned());
        assertEquals(0, inserted.getIsFavorite());
        assertEquals(1, inserted.getIsArchived());
        assertEquals(0, inserted.getIsDeleted());
        assertEquals(1L, inserted.getObjectVersion());
        assertEquals(11L, inserted.getServerVersion());
        assertNull(inserted.getDeleteTime());
        verify(syncLogMapper).insertLog(eq(7L), eq("NOTE"), eq("note-1"), eq("CREATE"), eq(11L), any(LocalDateTime.class));
    }

    @Test
    void pushReturnsConflictWithoutOverwritingServerNote() {
        NoteEntity current = new NoteEntity();
        current.setNoteUuid("note-2");
        current.setTitle("server");
        current.setObjectVersion(4L);
        current.setServerVersion(9L);
        current.setIsPinned(0);
        current.setIsFavorite(0);
        current.setIsArchived(0);
        current.setIsDeleted(0);
        current.setCreateTime(LocalDateTime.parse("2026-05-08T09:00:00"));
        current.setUpdateTime(LocalDateTime.parse("2026-05-08T09:30:00"));
        when(noteMapper.findByUserIdAndUuidForUpdate(7L, "note-2")).thenReturn(current);
        when(syncLogMapper.getCurrentServerVersion()).thenReturn(9L);

        SyncNoteRequest note = new SyncNoteRequest(
                "note-2",
                "UPDATE",
                3L,
                "client",
                "body",
                "summary",
                null,
                false,
                false,
                false,
                false,
                "2026-05-08T10:00:00"
        );

        SyncPushResponse response = syncService.push(7L, new SyncPushRequest(8L, List.of(note)));

        assertEquals(9L, response.serverVersion());
        assertTrue(response.successItems().isEmpty());
        assertEquals(1, response.conflictItems().size());
        assertEquals("note-2", response.conflictItems().get(0).noteUuid());
        assertEquals(3L, response.conflictItems().get(0).clientBaseObjectVersion());
        assertEquals(4L, response.conflictItems().get(0).serverObjectVersion());
        assertEquals("server", response.conflictItems().get(0).serverNote().title());
        verify(noteMapper, never()).updateFromSync(any());
        verify(syncLogMapper, never()).insertLog(eq(7L), eq("NOTE"), eq("note-2"), eq("UPDATE"), any(Long.class), any(LocalDateTime.class));
        verify(serverVersionService, never()).nextServerVersion();
    }

    @Test
    void deleteAlreadyDeletedNoteIsIdempotent() {
        NoteEntity current = new NoteEntity();
        current.setNoteUuid("note-3");
        current.setObjectVersion(5L);
        current.setServerVersion(14L);
        current.setIsDeleted(1);
        when(noteMapper.findByUserIdAndUuidForUpdate(7L, "note-3")).thenReturn(current);
        when(syncLogMapper.getCurrentServerVersion()).thenReturn(14L);

        SyncNoteRequest note = new SyncNoteRequest(
                "note-3",
                "DELETE",
                5L,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                true,
                "2026-05-08T10:00:00"
        );

        SyncPushResponse response = syncService.push(7L, new SyncPushRequest(14L, List.of(note)));

        assertEquals(14L, response.serverVersion());
        assertEquals(1, response.successItems().size());
        assertEquals(5L, response.successItems().get(0).objectVersion());
        assertEquals(14L, response.successItems().get(0).serverVersion());
        verify(noteMapper, never()).updateFromSync(any());
        verify(syncLogMapper, never()).insertLog(eq(7L), eq("NOTE"), eq("note-3"), eq("DELETE"), any(Long.class), any(LocalDateTime.class));
        verify(serverVersionService, never()).nextServerVersion();
    }

    @Test
    void changesRespectsLimitAndUsesMaxReturnedVersion() {
        SyncChangeEntity first = new SyncChangeEntity();
        first.setNoteUuid("note-1");
        first.setOperation("UPDATE");
        first.setObjectVersion(2L);
        first.setLogServerVersion(8L);
        first.setTitle("First");
        first.setIsPinned(0);
        first.setIsFavorite(0);
        first.setIsArchived(0);
        first.setIsDeleted(0);
        first.setCreateTime(LocalDateTime.parse("2026-05-08T08:00:00"));
        first.setUpdateTime(LocalDateTime.parse("2026-05-08T08:10:00"));

        SyncChangeEntity second = new SyncChangeEntity();
        second.setNoteUuid("note-2");
        second.setOperation("DELETE");
        second.setObjectVersion(3L);
        second.setLogServerVersion(9L);
        second.setTitle("Second");
        second.setIsPinned(0);
        second.setIsFavorite(0);
        second.setIsArchived(0);
        second.setIsDeleted(1);
        second.setCreateTime(LocalDateTime.parse("2026-05-08T08:20:00"));
        second.setUpdateTime(LocalDateTime.parse("2026-05-08T08:30:00"));
        second.setDeleteTime(LocalDateTime.parse("2026-05-08T08:31:00"));

        when(syncLogMapper.findNoteChanges(7L, 4L, 2)).thenReturn(List.of(first, second));

        SyncChangesResponse response = syncService.changes(7L, 4L, 1);

        assertTrue(response.hasMore());
        assertEquals(8L, response.serverVersion());
        assertEquals(1, response.notes().size());
        SyncChangeNote note = response.notes().get(0);
        assertEquals("note-1", note.noteUuid());
        assertEquals("UPDATE", note.operation());
        assertFalse(note.deleted());
    }
}
