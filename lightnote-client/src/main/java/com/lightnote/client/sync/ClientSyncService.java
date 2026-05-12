package com.lightnote.client.sync;

import com.lightnote.client.model.Note;
import com.lightnote.client.remote.LightNoteApiClient;
import com.lightnote.client.remote.LoginResponse;
import com.lightnote.client.remote.SyncChangesResponse;
import com.lightnote.client.remote.SyncConflictItem;
import com.lightnote.client.remote.SyncPushResponse;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.util.AppLogger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 客户端同步服务，负责登录会话下的推送、拉取、冲突处理与同步结果汇总。
 */
public class ClientSyncService {

    private static final Logger LOGGER = AppLogger.logger(ClientSyncService.class);

    private final NoteRepository noteRepository;
    private final AppConfigRepository configRepository;
    private final Function<String, LightNoteApiClient> apiClientFactory;

    public ClientSyncService(NoteRepository noteRepository, AppConfigRepository configRepository) {
        this(noteRepository, configRepository, LightNoteApiClient::new);
    }

    ClientSyncService(
            NoteRepository noteRepository,
            AppConfigRepository configRepository,
            Function<String, LightNoteApiClient> apiClientFactory
    ) {
        this.noteRepository = noteRepository;
        this.configRepository = configRepository;
        this.apiClientFactory = apiClientFactory;
    }

    /**
     * 执行登录并持久化服务端地址与 JWT，会话建立后供后续同步直接复用。
     */
    public LoginResponse login(String serverUrl, String username, String password) {
        LightNoteApiClient apiClient = apiClientFactory.apply(serverUrl);
        LoginResponse response = apiClient.login(username, password);
        configRepository.saveLogin(serverUrl, response.token());
        LOGGER.info("登录成功: serverUrl=" + serverUrl + ", user=" + username);
        return response;
    }

    /**
     * 执行一次完整同步：先推送本地待同步笔记，再分页拉取远端增量，并在本地生成冲突副本。
     */
    public SyncSummary syncNow() {
        String serverUrl = configRepository.serverUrl();
        String token = configRepository.token()
                .orElseThrow(() -> new IllegalStateException("请先登录"));
        LightNoteApiClient apiClient = apiClientFactory.apply(serverUrl);

        long lastSyncVersion = configRepository.lastSyncVersion();
        List<Note> pending = noteRepository.listPendingSync();
        LOGGER.info("开始同步: serverUrl=" + serverUrl
                + ", lastSyncVersion=" + lastSyncVersion
                + ", pending=" + pending.size());
        Map<String, String> pendingUpdateTimes = new HashMap<>();
        Map<String, String> conflictCopyUuids = new HashMap<>();
        pending.forEach(note -> pendingUpdateTimes.put(note.getNoteUuid(), note.getUpdateTime()));
        SyncPushResponse pushResponse = apiClient.push(token, lastSyncVersion, pending);
        pushResponse.successItems().forEach(item -> noteRepository.markSynced(item, pendingUpdateTimes.get(item.noteUuid())));
        for (SyncConflictItem conflict : pushResponse.conflictItems()) {
            Note local = noteRepository.findByUuid(conflict.noteUuid());
            if (local != null) {
                Note conflictCopy = noteRepository.createConflictCopy(local);
                conflictCopyUuids.put(conflict.noteUuid(), conflictCopy.getNoteUuid());
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
        LOGGER.info("同步完成: pushed=" + pushResponse.successItems().size()
                + ", conflicts=" + pushResponse.conflictItems().size()
                + ", pulled=" + pulled
                + ", finalVersion=" + finalVersion);
        return new SyncSummary(
                pending.size(),
                pushResponse.successItems().size(),
                pushResponse.conflictItems().size(),
                pulled,
                finalVersion,
                Map.copyOf(conflictCopyUuids)
        );
    }

    /**
     * 同步结果摘要，供界面层展示上传、冲突、拉取数量以及冲突副本映射。
     */
    public record SyncSummary(
            int pendingCount,
            int pushedCount,
            int conflictCount,
            int pulledCount,
            long serverVersion,
            Map<String, String> conflictCopyUuids
    ) {
    }
}

