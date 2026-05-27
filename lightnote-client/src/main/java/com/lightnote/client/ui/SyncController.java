package com.lightnote.client.ui;

import com.lightnote.client.model.Note;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.sync.ClientSyncService;
import com.lightnote.client.util.AppLogger;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 同步生命周期控制器，管理自动同步定时、手动同步触发、同步状态追踪和错误处理。
 * <p>
 * 不直接持有 UI 控件引用，通过回调通知外部同步完成/失败事件。
 */
public class SyncController {

    private static final Logger LOGGER = AppLogger.logger(SyncController.class);

    private final ClientSyncService syncService;
    private final AppConfigRepository configRepository;
    private final PauseTransition autoSyncDelay = new PauseTransition(Duration.millis(2500));

    private boolean syncInProgress;
    private boolean syncRequestedDuringRun;
    private boolean syncFailureActive;
    private String lastSyncError;

    private BiConsumer<ClientSyncService.SyncSummary, Note> onSyncComplete;
    private BiConsumer<Exception, Boolean> onSyncError;

    public SyncController(ClientSyncService syncService, AppConfigRepository configRepository) {
        this.syncService = syncService;
        this.configRepository = configRepository;
    }

    // ======================== 回调注册 ========================

    /**
     * @param callback (syncSummary, currentlySelectedNote) — 同步完成后的回调
     */
    public void setOnSyncComplete(BiConsumer<ClientSyncService.SyncSummary, Note> callback) {
        this.onSyncComplete = callback;
    }

    /**
     * @param callback (exception, isManual) — 同步出错时的回调
     */
    public void setOnSyncError(BiConsumer<Exception, Boolean> callback) {
        this.onSyncError = callback;
    }

    // ======================== 状态查询 ========================

    public boolean isSyncInProgress() {
        return syncInProgress;
    }

    public boolean isSyncFailureActive() {
        return syncFailureActive;
    }

    public String getLastSyncError() {
        return lastSyncError;
    }

    // ======================== 同步触发 ========================

    /**
     * 手动同步，立即执行。
     *
     * @param manual         是否由用户主动触发
     * @param preSyncSave    同步前执行的保存操作（返回 false 则中止同步）
     * @param currentlySelected 当前选中的笔记（用于回调中判断冲突副本）
     */
    public void syncNow(boolean manual, java.util.function.Supplier<Boolean> preSyncSave, Note currentlySelected) {
        autoSyncDelay.stop();
        if (!preSyncSave.get()) {
            return;
        }
        runSync(manual, currentlySelected);
    }

    /**
     * 安排延迟自动同步（2.5 秒防抖）。
     */
    public void scheduleAutoSync() {
        if (configRepository.token().isEmpty()) {
            return;
        }
        if (syncInProgress) {
            syncRequestedDuringRun = true;
            return;
        }
        autoSyncDelay.playFromStart();
    }

    public void setAutoSyncHandler(Runnable handler) {
        autoSyncDelay.setOnFinished(event -> handler.run());
    }

    // ======================== 同步执行 ========================

    private void runSync(boolean manual, Note currentlySelected) {
        if (syncInProgress) {
            return;
        }
        syncInProgress = true;

        Thread thread = new Thread(() -> {
            try {
                ClientSyncService.SyncSummary summary = syncService.syncNow();
                javafx.application.Platform.runLater(() -> {
                    syncInProgress = false;
                    syncFailureActive = false;
                    lastSyncError = null;
                    if (onSyncComplete != null) {
                        onSyncComplete.accept(summary, currentlySelected);
                    }
                });
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() -> {
                    syncInProgress = false;
                    syncFailureActive = true;
                    lastSyncError = normalizeErrorMessage(ex, "同步失败");
                    LOGGER.log(Level.WARNING, "同步失败", ex);
                    if (onSyncError != null) {
                        onSyncError.accept(ex, manual);
                    }
                });
            }
        }, "lightnote-sync");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 同步结束后调用，检查是否有在同步期间请求的延迟同步。
     */
    public void scheduleDeferredSyncIfNeeded() {
        if (!syncRequestedDuringRun) {
            return;
        }
        syncRequestedDuringRun = false;
        scheduleAutoSync();
    }

    // ======================== 最近同步状态灯 ========================

    /**
     * 根据当前同步状态和本地失败状态，返回灯的状态字符串。
     */
    public String resolveSyncLampState(Note note, boolean hasLocalFailure, String localFailureMessage) {
        if (hasLocalFailure) {
            return "unsynced";
        }
        if (syncInProgress) {
            return "syncing";
        }
        if (syncFailureActive) {
            return "unsynced";
        }
        if (note == null) {
            return "unsynced";
        }
        return switch (note.getSyncStatus()) {
            case SYNCED -> "synced";
            case SYNCING -> "syncing";
            case DIRTY, CONFLICT, DELETE_PENDING -> "unsynced";
        };
    }

    /**
     * 根据当前同步状态和本地失败状态，返回灯的工具提示文本。
     */
    public String resolveSyncLampTooltip(Note note, boolean hasLocalFailure, String localFailureMessage) {
        if (hasLocalFailure) {
            return localFailureMessage;
        }
        if (syncInProgress) {
            return "同步中";
        }
        if (syncFailureActive) {
            return lastSyncError == null || lastSyncError.isBlank() ? "同步失败" : "同步失败: " + lastSyncError;
        }
        if (note == null) {
            return "未同步";
        }
        return switch (note.getSyncStatus()) {
            case SYNCED -> "已同步";
            case SYNCING -> "同步中";
            case DIRTY -> "待同步";
            case CONFLICT -> "冲突";
            case DELETE_PENDING -> "待删除";
        };
    }

    // ======================== 工具方法 ========================

    static String normalizeErrorMessage(Exception ex, String fallback) {
        if (ex == null) {
            return fallback;
        }
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        Throwable cause = ex.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return fallback;
    }

    static boolean isAuthFailure(Exception ex) {
        String message = normalizeErrorMessage(ex, "");
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("401")
                || normalized.contains("未登录")
                || normalized.contains("请先登录");
    }
}
