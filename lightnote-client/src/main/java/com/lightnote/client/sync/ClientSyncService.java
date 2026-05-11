package com.lightnote.client.sync;

import com.lightnote.client.model.Note;
import com.lightnote.client.remote.LightNoteApiClient;
import com.lightnote.client.remote.LoginResponse;
import com.lightnote.client.remote.SyncChangesResponse;
import com.lightnote.client.remote.SyncConflictItem;
import com.lightnote.client.remote.SyncPushResponse;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientSyncService {

    private final NoteRepository noteRepository;
    private final AppConfigRepository configRepository;

    public ClientSyncService(NoteRepository noteRepository, AppConfigRepository configRepository) {
        this.noteRepository = noteRepository;
        this.configRepository = configRepository;
    }

    public LoginResponse login(String serverUrl, String username, String password) {
        LightNoteApiClient apiClient = new LightNoteApiClient(serverUrl);
        LoginResponse response = apiClient.login(username, password);
        configRepository.saveLogin(serverUrl, response.token());
        return response;
    }

    public SyncSummary syncNow() {
        String serverUrl = configRepository.serverUrl();
        String token = configRepository.token()
                .orElseThrow(() -> new IllegalStateException("请先登录"));
        LightNoteApiClient apiClient = new LightNoteApiClient(serverUrl);

        long lastSyncVersion = configRepository.lastSyncVersion();
        List<Note> pending = noteRepository.listPendingSync();
        Map<String, String> pendingUpdateTimes = new HashMap<>();
        pending.forEach(note -> pendingUpdateTimes.put(note.getNoteUuid(), note.getUpdateTime()));
        SyncPushResponse pushResponse = apiClient.push(token, lastSyncVersion, pending);
        pushResponse.successItems().forEach(item -> noteRepository.markSynced(item, pendingUpdateTimes.get(item.noteUuid())));
        for (SyncConflictItem conflict : pushResponse.conflictItems()) {
            Note local = noteRepository.findByUuid(conflict.noteUuid());
            if (local != null) {
                noteRepository.createConflictCopy(local);
            }
            noteRepository.resolveConflict(conflict);
        }

        int pulled = 0;
        boolean hasMore;
        long nextSince = lastSyncVersion;
        do {
            SyncChangesResponse changes = apiClient.changes(token, nextSince, 200);
            changes.notes().forEach(noteRepository::applyRemote);
            pulled += changes.notes().size();
            nextSince = changes.serverVersion();
            hasMore = changes.hasMore();
        } while (hasMore);

        long finalVersion = Math.max(pushResponse.serverVersion(), nextSince);
        configRepository.saveLastSyncVersion(finalVersion);
        return new SyncSummary(pending.size(), pushResponse.successItems().size(), pushResponse.conflictItems().size(), pulled, finalVersion);
    }

    public record SyncSummary(
            int pendingCount,
            int pushedCount,
            int conflictCount,
            int pulledCount,
            long serverVersion
    ) {
    }
}
