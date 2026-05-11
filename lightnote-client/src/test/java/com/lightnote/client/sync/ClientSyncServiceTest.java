package com.lightnote.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lightnote.client.model.Note;
import com.lightnote.client.remote.ApiException;
import com.lightnote.client.remote.LightNoteApiClient;
import com.lightnote.client.remote.LoginResponse;
import com.lightnote.client.remote.RemoteNote;
import com.lightnote.client.remote.SyncChangesResponse;
import com.lightnote.client.remote.SyncConflictItem;
import com.lightnote.client.remote.SyncItemResult;
import com.lightnote.client.remote.SyncPushResponse;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClientSyncServiceTest {

    @Mock
    private NoteRepository noteRepository;

    @Mock
    private AppConfigRepository configRepository;

    @Test
    void loginUsesFactoryAndPersistsSession() {
        StubApiClient apiClient = new StubApiClient("http://server");
        apiClient.loginResponse = new LoginResponse("jwt-1", 7200);
        ClientSyncService service = new ClientSyncService(noteRepository, configRepository, fixedFactory(apiClient));

        LoginResponse response = service.login("http://server", "admin", "secret");

        assertSame(apiClient.loginResponse, response);
        assertEquals("admin", apiClient.lastUsername);
        assertEquals("secret", apiClient.lastPassword);
        verify(configRepository).saveLogin("http://server", "jwt-1");
    }

    @Test
    void syncNowResolvesConflictsAndPullsAllPages() {
        Note pending = note("note-1", "2026-05-11T09:00:00");
        Note local = note("note-2", "2026-05-11T09:01:00");
        RemoteNote serverNote = new RemoteNote(
                "note-2", "UPDATE", 4L, 12L, "Server", "<p>body</p>", "body", "",
                false, false, false, false, "2026-05-11T08:00:00", "2026-05-11T08:30:00", null
        );
        SyncConflictItem conflict = new SyncConflictItem("note-2", 1L, 4L, serverNote);
        RemoteNote remote1 = new RemoteNote(
                "note-3", "UPDATE", 2L, 13L, "Remote 1", "<p>r1</p>", "r1", "",
                false, false, false, false, "2026-05-11T08:00:00", "2026-05-11T08:30:00", null
        );
        RemoteNote remote2 = new RemoteNote(
                "note-4", "UPDATE", 3L, 14L, "Remote 2", "<p>r2</p>", "r2", "",
                false, false, false, false, "2026-05-11T08:00:00", "2026-05-11T08:40:00", null
        );

        StubApiClient apiClient = new StubApiClient("http://server");
        apiClient.pushResponse = new SyncPushResponse(12L, List.of(new SyncItemResult("note-1", 3L, 12L)), List.of(conflict));
        apiClient.changesResponses = List.of(
                new SyncChangesResponse(13L, true, List.of(remote1)),
                new SyncChangesResponse(14L, false, List.of(remote2))
        );
        ClientSyncService service = new ClientSyncService(noteRepository, configRepository, fixedFactory(apiClient));

        when(configRepository.serverUrl()).thenReturn("http://server");
        when(configRepository.token()).thenReturn(Optional.of("jwt-2"));
        when(configRepository.lastSyncVersion()).thenReturn(10L);
        when(noteRepository.listPendingSync()).thenReturn(List.of(pending));
        when(noteRepository.findByUuid("note-2")).thenReturn(local);
        Note conflictCopy = note("note-2-copy", "2026-05-11T09:02:00");
        when(noteRepository.createConflictCopy(local)).thenReturn(conflictCopy);

        ClientSyncService.SyncSummary summary = service.syncNow();

        assertEquals(1, summary.pendingCount());
        assertEquals(1, summary.pushedCount());
        assertEquals(1, summary.conflictCount());
        assertEquals(2, summary.pulledCount());
        assertEquals(14L, summary.serverVersion());
        assertEquals("note-2-copy", summary.conflictCopyUuids().get("note-2"));
        verify(noteRepository).markSynced(new SyncItemResult("note-1", 3L, 12L), "2026-05-11T09:00:00");
        verify(noteRepository).createConflictCopy(local);
        verify(noteRepository).resolveConflict(conflict);
        verify(noteRepository).applyRemote(remote1);
        verify(noteRepository).applyRemote(remote2);
        verify(configRepository).saveLastSyncVersion(14L);
        assertEquals(List.of(10L, 13L), apiClient.changesSinceVersions);
    }

    @Test
    void syncNowPropagatesApiFailureWithoutTouchingPersistedVersion() {
        StubApiClient apiClient = new StubApiClient("http://server");
        apiClient.pushException = new ApiException("network down");
        ClientSyncService service = new ClientSyncService(noteRepository, configRepository, fixedFactory(apiClient));

        when(configRepository.serverUrl()).thenReturn("http://server");
        when(configRepository.token()).thenReturn(Optional.of("jwt-2"));
        when(configRepository.lastSyncVersion()).thenReturn(10L);
        when(noteRepository.listPendingSync()).thenReturn(List.of(note("note-1", "2026-05-11T09:00:00")));

        ApiException ex = assertThrows(ApiException.class, service::syncNow);

        assertEquals("network down", ex.getMessage());
        verify(noteRepository, never()).markSynced(any(), any());
        verify(configRepository, never()).saveLastSyncVersion(any(Long.class));
    }

    private Function<String, LightNoteApiClient> fixedFactory(StubApiClient apiClient) {
        return ignored -> apiClient;
    }

    private Note note(String uuid, String updateTime) {
        Note note = new Note();
        note.setNoteUuid(uuid);
        note.setUpdateTime(updateTime);
        return note;
    }

    private static final class StubApiClient extends LightNoteApiClient {
        private LoginResponse loginResponse;
        private SyncPushResponse pushResponse;
        private List<SyncChangesResponse> changesResponses = List.of();
        private RuntimeException pushException;
        private RuntimeException changesException;
        private int changesIndex;
        private String lastUsername;
        private String lastPassword;
        private final java.util.ArrayList<Long> changesSinceVersions = new java.util.ArrayList<>();

        private StubApiClient(String serverUrl) {
            super(serverUrl);
        }

        @Override
        public LoginResponse login(String username, String password) {
            this.lastUsername = username;
            this.lastPassword = password;
            return loginResponse;
        }

        @Override
        public SyncPushResponse push(String token, long lastSyncVersion, List<Note> notes) {
            if (pushException != null) {
                throw pushException;
            }
            return pushResponse;
        }

        @Override
        public SyncChangesResponse changes(String token, long sinceVersion, int limit) {
            changesSinceVersions.add(sinceVersion);
            if (changesException != null) {
                throw changesException;
            }
            return changesResponses.get(changesIndex++);
        }
    }
}
