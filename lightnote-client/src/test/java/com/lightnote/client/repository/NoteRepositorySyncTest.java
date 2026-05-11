package com.lightnote.client.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.model.SyncStatus;
import com.lightnote.client.remote.RemoteNote;
import com.lightnote.client.remote.SyncConflictItem;
import com.lightnote.client.remote.SyncItemResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoteRepositorySyncTest {

    private Path tempDir;
    private DatabaseInitializer initializer;
    private NoteRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("lightnote-note-repo-test");
        System.setProperty("lightnote.dataDir", tempDir.toString());
        initializer = new DatabaseInitializer();
        initializer.initialize();
        repository = new NoteRepository(initializer.getDatabasePath());
    }

    @AfterEach
    void tearDown() throws IOException {
        System.clearProperty("lightnote.dataDir");
        if (tempDir == null) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void markSyncedKeepsDirtyWhenUserContinuesEditingDuringSync() {
        Note note = repository.createEmpty();
        note.setContent("<p>first version</p>");
        repository.save(note);
        String pushedUpdateTime = note.getUpdateTime();

        note.setContent("<p>latest local edit</p>");
        repository.save(note);

        repository.markSynced(new SyncItemResult(note.getNoteUuid(), 3L, 12L), pushedUpdateTime);

        Note stored = repository.findByUuid(note.getNoteUuid());
        assertNotNull(stored);
        assertEquals("<p>latest local edit</p>", stored.getContent());
        assertEquals(3L, stored.getObjectVersion());
        assertEquals(12L, stored.getServerVersion());
        assertEquals(SyncStatus.DIRTY, stored.getSyncStatus());
    }

    @Test
    void applyRemoteIgnoresAlreadyAcknowledgedServerVersion() {
        Note note = repository.createEmpty();
        note.setContent("<p>local newer text</p>");
        repository.save(note);
        String pushedUpdateTime = note.getUpdateTime();
        note.setContent("<p>local newer text after push</p>");
        repository.save(note);
        repository.markSynced(new SyncItemResult(note.getNoteUuid(), 4L, 20L), pushedUpdateTime);

        repository.applyRemote(new RemoteNote(
                note.getNoteUuid(),
                "UPDATE",
                4L,
                20L,
                "Server Title",
                "<p>stale remote copy</p>",
                "stale remote copy",
                "",
                false,
                false,
                false,
                false,
                "2026-05-08T10:00:00",
                "2026-05-08T10:10:00",
                null
        ));

        Note stored = repository.findByUuid(note.getNoteUuid());
        assertNotNull(stored);
        assertEquals("<p>local newer text after push</p>", stored.getContent());
        assertEquals(SyncStatus.DIRTY, stored.getSyncStatus());
        assertEquals(1, repository.listByFilter("", NoteFilter.ALL).size());
    }

    @Test
    void applyRemoteCreatesConflictCopyThenAppliesNewerServerVersion() {
        Note note = repository.createEmpty();
        note.setTitle("Original");
        note.setContent("<p>local content</p>");
        repository.save(note);

        repository.applyRemote(new RemoteNote(
                note.getNoteUuid(),
                "UPDATE",
                2L,
                8L,
                "Remote",
                "<p>remote content</p>",
                "remote content",
                "ops",
                true,
                false,
                false,
                false,
                "2026-05-08T10:00:00",
                "2026-05-08T10:20:00",
                null
        ));

        Note updated = repository.findByUuid(note.getNoteUuid());
        assertNotNull(updated);
        assertEquals("Remote", updated.getTitle());
        assertEquals("<p>remote content</p>", updated.getContent());
        assertEquals(2L, updated.getObjectVersion());
        assertEquals(8L, updated.getServerVersion());
        assertEquals(SyncStatus.SYNCED, updated.getSyncStatus());

        List<Note> notes = repository.listByFilter("", NoteFilter.ALL);
        assertEquals(2, notes.size());
        long conflictCopies = notes.stream()
                .filter(item -> item.getTitle().contains("冲突副本"))
                .count();
        assertEquals(1, conflictCopies);
    }

    @Test
    void resolveConflictRestoresServerVersionWhileKeepingConflictCopySeparate() {
        Note note = repository.createEmpty();
        note.setTitle("Local title");
        note.setContent("<p>local content</p>");
        repository.save(note);

        Note localBeforeResolve = repository.findByUuid(note.getNoteUuid());
        assertNotNull(localBeforeResolve);
        repository.createConflictCopy(localBeforeResolve);
        repository.resolveConflict(new SyncConflictItem(
                note.getNoteUuid(),
                1L,
                3L,
                new RemoteNote(
                        note.getNoteUuid(),
                        "UPDATE",
                        3L,
                        10L,
                        "Server title",
                        "<p>server content</p>",
                        "server content",
                        "ops",
                        false,
                        true,
                        false,
                        false,
                        "2026-05-08T10:00:00",
                        "2026-05-08T10:30:00",
                        null
                )
        ));

        Note resolved = repository.findByUuid(note.getNoteUuid());
        assertNotNull(resolved);
        assertEquals("Server title", resolved.getTitle());
        assertEquals("<p>server content</p>", resolved.getContent());
        assertEquals(3L, resolved.getObjectVersion());
        assertEquals(10L, resolved.getServerVersion());
        assertEquals(SyncStatus.SYNCED, resolved.getSyncStatus());

        List<Note> notes = repository.listByFilter("", NoteFilter.ALL);
        assertEquals(2, notes.size());
        Note conflictCopy = notes.stream()
                .filter(item -> item.getTitle().contains("冲突副本"))
                .findFirst()
                .orElseThrow();
        assertEquals("<p>local content</p>", conflictCopy.getContent());
        assertEquals(SyncStatus.DIRTY, conflictCopy.getSyncStatus());
    }
}
