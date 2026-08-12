package com.lightnote.client.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.config.MyBatisSqlSessionFactory;
import com.lightnote.client.model.SyncStatus;
import com.lightnote.client.repository.NoteRepository.CategorySummary;
import com.lightnote.client.remote.RemoteNote;
import com.lightnote.client.remote.SyncConflictItem;
import com.lightnote.client.remote.SyncItemResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoteRepositorySyncTest {

    private static final Pattern CONFLICT_COPY_TITLE_PATTERN =
            Pattern.compile(".+（冲突副本 \\d{2}-\\d{2} \\d{2}:\\d{2}）");

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
        MyBatisSqlSessionFactory.resetForTest();
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

        try {
            Thread.sleep(2);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
        try {
            Thread.sleep(2);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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

    @Test
    void conflictFilterReturnsOnlyConflictCopies() {
        Note normal = repository.createEmpty();
        normal.setTitle("Normal");
        normal.setContent("<p>normal</p>");
        repository.save(normal);

        Note source = repository.createEmpty();
        source.setTitle("Server note");
        source.setContent("<p>local conflict</p>");
        repository.save(source);
        repository.createConflictCopy(repository.findByUuid(source.getNoteUuid()));

        List<Note> conflicts = repository.listByFilter("", NoteFilter.CONFLICT_COPIES);

        assertEquals(1, conflicts.size());
        assertEquals(true, conflicts.get(0).getTitle().contains("冲突副本"));
    }

    @Test
    void countByFilterReturnsFavoriteArchivedAndConflictTotals() {
        Note favorite = repository.createEmpty();
        favorite.setTitle("Favorite");
        favorite.setFavorite(true);
        repository.save(favorite);

        Note archived = repository.createEmpty();
        archived.setTitle("Archived");
        archived.setArchived(true);
        repository.save(archived);

        Note source = repository.createEmpty();
        source.setTitle("Source");
        source.setContent("<p>local conflict</p>");
        repository.save(source);
        repository.createConflictCopy(repository.findByUuid(source.getNoteUuid()));

        Note trashed = repository.createEmpty();
        trashed.setTitle("Trash");
        repository.save(trashed);
        repository.moveToTrash(trashed);

        assertEquals(1L, repository.countByFilter(NoteFilter.FAVORITES));
        assertEquals(1L, repository.countByFilter(NoteFilter.ARCHIVED));
        assertEquals(1L, repository.countByFilter(NoteFilter.TRASH));
        assertEquals(1L, repository.countByFilter(NoteFilter.CONFLICT_COPIES));
    }

    @Test
    void countByFilterFavoritesDoesNotRequireUncategorizedNotes() {
        Note favorite = repository.createEmpty();
        favorite.setTitle("Categorized Favorite");
        favorite.setCategoryName("运维");
        favorite.setFavorite(true);
        repository.save(favorite);

        assertEquals(1L, repository.countByFilter(NoteFilter.FAVORITES));
    }

    @Test
    void createConflictCopyUsesReadableTitleFormat() {
        Note source = repository.createEmpty();
        source.setTitle("Deploy Guide");
        repository.save(source);

        Note conflictCopy = repository.createConflictCopy(repository.findByUuid(source.getNoteUuid()));

        assertNotNull(conflictCopy);
        assertEquals(true, CONFLICT_COPY_TITLE_PATTERN.matcher(conflictCopy.getTitle()).matches());
    }

    @Test
    void createConflictCopyPreservesMarkdownFormatAndContent() {
        Note source = repository.createEmpty();
        source.setTitle("Markdown Source");
        source.setContentFormat(ContentFormat.MARKDOWN);
        source.setContent("""
                # Heading

                Keep <literal> tags.
                """);
        repository.save(source);

        Note conflictCopy = repository.createConflictCopy(repository.findByUuid(source.getNoteUuid()));

        assertNotNull(conflictCopy);
        assertEquals(ContentFormat.MARKDOWN, conflictCopy.getContentFormat());
        assertEquals(source.getContent(), conflictCopy.getContent());
        assertEquals("Heading Keep <literal> tags.", conflictCopy.getSummary());
        assertEquals(SyncStatus.DIRTY, conflictCopy.getSyncStatus());
    }

    @Test
    void listByFilterSupportsCategoryFilterIncludingUncategorized() {
        Note ops = repository.createEmpty();
        ops.setTitle("Ops");
        ops.setCategoryName("  运维  ");
        repository.save(ops);

        Note dev = repository.createEmpty();
        dev.setTitle("Dev");
        dev.setCategoryName("研发");
        repository.save(dev);

        Note uncategorized = repository.createEmpty();
        uncategorized.setTitle("Inbox");
        repository.save(uncategorized);

        List<String> opsTitles = repository.listByFilter("", NoteFilter.ALL, "运维").stream()
                .map(Note::getTitle)
                .collect(Collectors.toList());
        List<String> uncategorizedTitles = repository.listByFilter("", NoteFilter.ALL, "").stream()
                .map(Note::getTitle)
                .collect(Collectors.toList());

        assertIterableEquals(List.of("Ops"), opsTitles);
        assertIterableEquals(List.of("Inbox"), uncategorizedTitles);
    }

    @Test
    void listCategorySummariesReturnsNormalizedCounts() {
        Note first = repository.createEmpty();
        first.setTitle("First");
        first.setCategoryName(" 运维 ");
        repository.save(first);

        Note second = repository.createEmpty();
        second.setTitle("Second");
        second.setCategoryName("运维");
        repository.save(second);

        Note uncategorized = repository.createEmpty();
        uncategorized.setTitle("Inbox");
        repository.save(uncategorized);

        List<CategorySummary> summaries = repository.listCategorySummaries();

        assertEquals(2, summaries.size());
        assertEquals("运维", summaries.get(0).name());
        assertEquals(2L, summaries.get(0).count());
        assertEquals("", summaries.get(1).name());
        assertEquals(1L, summaries.get(1).count());
    }

    @Test
    void renameCategoryUpdatesExistingNotes() {
        Note note = repository.createEmpty();
        note.setTitle("Ops");
        note.setCategoryName("运维");
        repository.save(note);

        repository.renameCategory("运维", "平台");

        Note stored = repository.findByUuid(note.getNoteUuid());
        assertNotNull(stored);
        assertEquals("平台", stored.getCategoryName());
        assertEquals(1, repository.listByFilter("", NoteFilter.ALL, "平台").size());
    }

    @Test
    void moveToTrashHidesNoteFromDefaultListAndShowsItInTrash() {
        Note note = repository.createEmpty();
        note.setTitle("Trash me");
        repository.save(note);

        repository.moveToTrash(note);

        assertEquals(0, repository.listByFilter("", NoteFilter.ALL).size());
        List<Note> trashNotes = repository.listByFilter("", NoteFilter.TRASH);
        assertEquals(1, trashNotes.size());
        assertEquals("Trash me", trashNotes.get(0).getTitle());
        assertEquals(true, trashNotes.get(0).isTrashed());
    }

    @Test
    void restoreFromTrashReturnsNoteToDefaultList() {
        Note note = repository.createEmpty();
        note.setTitle("Restore me");
        repository.save(note);
        repository.moveToTrash(note);

        repository.restoreFromTrash(repository.findByUuid(note.getNoteUuid()));

        assertEquals(1, repository.listByFilter("", NoteFilter.ALL).size());
        assertEquals(0, repository.listByFilter("", NoteFilter.TRASH).size());
    }

    @Test
    void permanentDeleteFromTrashCreatesDeletePendingTombstone() {
        Note note = repository.createEmpty();
        note.setTitle("Delete forever");
        repository.save(note);
        repository.moveToTrash(note);

        repository.softDelete(repository.findByUuid(note.getNoteUuid()));

        assertEquals(0, repository.listByFilter("", NoteFilter.TRASH).size());
        Note stored = repository.findByUuid(note.getNoteUuid());
        assertNotNull(stored);
        assertEquals(true, stored.isDeleted());
        assertEquals(SyncStatus.DELETE_PENDING, stored.getSyncStatus());
        assertEquals(1, repository.listPendingSync().size());
        assertEquals(note.getNoteUuid(), repository.listPendingSync().get(0).getNoteUuid());
    }

    @Test
    void applyRemoteDecodesEscapedHtmlContent() {
        Note note = repository.createEmpty();

        repository.applyRemote(new RemoteNote(
                note.getNoteUuid(),
                "UPDATE",
                2L,
                8L,
                "Remote",
                "&amp;lt;span style=\"font-weight: bold\"&amp;gt;Hello&amp;lt;/span&amp;gt;",
                "Hello",
                "",
                false,
                false,
                false,
                false,
                "2026-05-08T10:00:00",
                "2026-05-08T10:20:00",
                null
        ));

        Note updated = repository.findByUuid(note.getNoteUuid());
        assertNotNull(updated);
        assertEquals("<span style=\"font-weight: bold\">Hello</span>", updated.getContent());
    }

    @Test
    void saveNormalizesEscapedHtmlBeforeSyncReadsIt() {
        Note note = repository.createEmpty();
        note.setContentFormat(ContentFormat.HTML);
        note.setTitle("Rich");
        note.setContent("&lt;html&gt;&lt;body&gt;&lt;span style=\"font-weight: bold\"&gt;Hello&lt;/span&gt;&lt;/body&gt;&lt;/html&gt;");

        repository.save(note);

        Note stored = repository.findByUuid(note.getNoteUuid());
        assertNotNull(stored);
        assertEquals("<span style=\"font-weight: bold\">Hello</span>", stored.getContent());
        assertEquals("Hello", stored.getSummary());
        assertEquals("<span style=\"font-weight: bold\">Hello</span>", repository.listPendingSync().get(0).getContent());
    }

    @Test
    void markdownContentIsStoredWithoutHtmlNormalization() {
        Note note = repository.createEmpty();
        note.setContentFormat(ContentFormat.MARKDOWN);
        note.setTitle("Markdown");
        note.setContent("""
                # Heading

                Keep <literal> tags & symbols.

                - **bold item**
                """);

        repository.save(note);

        Note stored = repository.findByUuid(note.getNoteUuid());
        assertNotNull(stored);
        assertEquals(ContentFormat.MARKDOWN, stored.getContentFormat());
        assertEquals(note.getContent(), stored.getContent());
        assertEquals("Heading Keep <literal> tags & symbols. bold item", stored.getSummary());
    }

    @Test
    void applyRemotePreservesMarkdownFormat() {
        Note note = repository.createEmpty();

        repository.applyRemote(new RemoteNote(
                note.getNoteUuid(),
                "UPDATE",
                2L,
                8L,
                "MARKDOWN",
                "Remote Markdown",
                "# Remote\n\n![Alt](lightnote-asset://asset-1)",
                "",
                "",
                false,
                false,
                false,
                false,
                "2026-05-08T10:00:00",
                "2026-05-08T10:20:00",
                null
        ));

        Note updated = repository.findByUuid(note.getNoteUuid());
        assertNotNull(updated);
        assertEquals(ContentFormat.MARKDOWN, updated.getContentFormat());
        assertEquals("# Remote\n\n![Alt](lightnote-asset://asset-1)", updated.getContent());
        assertEquals("Remote Alt", updated.getSummary());
    }

    @Test
    void todayAndRecentFiltersUseCreateTimeWindow() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayTime = today.atTime(10, 0);
        LocalDateTime threeDaysAgo = today.minusDays(3).atTime(10, 0);
        LocalDateTime tenDaysAgo = today.minusDays(10).atTime(10, 0);

        Note todayNote = repository.createEmpty();
        todayNote.setTitle("Today");
        repository.save(todayNote);

        repository.applyRemote(new RemoteNote(
                "recent-note",
                "UPDATE",
                1L,
                2L,
                ContentFormat.HTML.name(),
                "Recent",
                "<p>recent</p>",
                "",
                "",
                false,
                false,
                false,
                false,
                threeDaysAgo.toString(),
                todayTime.toString(),
                null
        ));

        repository.applyRemote(new RemoteNote(
                "old-note",
                "UPDATE",
                1L,
                3L,
                ContentFormat.HTML.name(),
                "Old",
                "<p>old</p>",
                "",
                "",
                false,
                false,
                false,
                false,
                tenDaysAgo.toString(),
                todayTime.toString(),
                null
        ));

        List<String> todayTitles = repository.listByFilter("", NoteFilter.TODAY).stream()
                .map(Note::getTitle)
                .toList();
        List<String> recentTitles = repository.listByFilter("", NoteFilter.RECENT_7_DAYS).stream()
                .map(Note::getTitle)
                .toList();

        assertEquals(List.of("Today"), todayTitles);
        assertEquals(List.of("Today", "Recent"), recentTitles);
    }
}
