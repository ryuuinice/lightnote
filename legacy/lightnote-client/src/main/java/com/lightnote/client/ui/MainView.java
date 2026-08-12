package com.lightnote.client.ui;

import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.sync.ClientSyncService;
import com.lightnote.client.util.AppLogger;
import com.lightnote.client.util.HtmlToMarkdownConverter;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端主界面协调器，组合 NavigationSidebar、NoteListPanel、NoteEditorPanel 和 SyncController，
 * 负责跨组件的交互编排（筛选→刷新列表→加载编辑器→自动保存→同步）。
 */
public class MainView {

    private static final Logger LOGGER = AppLogger.logger(MainView.class);
    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String NOTE_EDITOR_DIVIDER_KEY = "note_editor_divider_position";
    private static final String CONFLICT_COPY_MARKER = "冲突副本";

    private final NoteRepository noteRepository;
    private final AppConfigRepository configRepository;
    private final ClientSyncService syncService;
    private final Runnable onLogout;
    private final BorderPane root = new BorderPane();

    // 子组件
    private final NavigationSidebar sidebar;
    private final NoteListPanel listPanel;
    private final NoteEditorPanel editorPanel;
    private final SyncController syncController;

    // 编辑状态
    private Note selectedNote;
    private boolean loadingSelection;

    // 本地失败状态
    private String localFailureNoteUuid;
    private String lastLocalFailureMessage;

    // 定时器
    private final PauseTransition autosaveDelay = new PauseTransition(Duration.millis(700));
    private final SplitPane contentSplitPane = new SplitPane();

    public MainView(
            NoteRepository noteRepository,
            AppConfigRepository configRepository,
            ClientSyncService syncService,
            Runnable onLogout
    ) {
        this.noteRepository = noteRepository;
        this.configRepository = configRepository;
        this.syncService = syncService;
        this.onLogout = onLogout;

        // 创建子组件
        this.sidebar = new NavigationSidebar(
                noteRepository, configRepository,
                this::onFilterOrCategoryChanged,
                this::openToolsDialog,
                this::openSettingsDialog
        );
        this.listPanel = new NoteListPanel(noteRepository);
        this.editorPanel = new NoteEditorPanel(configRepository);
        this.syncController = new SyncController(syncService, configRepository);

        // 构建布局
        buildLayout();
        wireCallbacks();

        // 初始加载
        sidebar.setCurrentFilter(NoteFilter.ALL);
        refreshNotes();
    }

    public Parent getRoot() {
        return root;
    }

    // ======================== 布局 ========================

    private void buildLayout() {
        root.getStyleClass().add("app-root");
        root.setLeft(sidebar.getRoot());

        contentSplitPane.getItems().setAll(listPanel.getRoot(), editorPanel.getRoot());
        contentSplitPane.setDividerPositions(
                clampDividerPosition(configRepository.getDouble(NOTE_EDITOR_DIVIDER_KEY, 0.34)));
        contentSplitPane.getStyleClass().add("main-split-pane");
        root.setCenter(contentSplitPane);
        Platform.runLater(this::bindContentDividerPersistence);
    }

    // ======================== 回调编排 ========================

    private void wireCallbacks() {
        // 列表选择
        listPanel.setOnNoteSelected(this::selectNote);
        listPanel.setOnNewNoteClicked(this::createNote);
        listPanel.setOnSearchChanged(this::refreshNotes);

        // 编辑器内容变化
        editorPanel.setOnContentChanged(this::scheduleAutosave);
        editorPanel.setOnToggleChanged(this::saveToggleChange);
        editorPanel.setOnSaveClicked(this::saveSelectedNote);
        editorPanel.setOnSyncClicked(this::handleSyncClicked);
        editorPanel.setOnDeleteClicked(this::deleteSelectedNote);
        editorPanel.setOnRestoreClicked(this::restoreSelectedNote);
        editorPanel.setOnConvertMarkdown(this::convertSelectedNoteToMarkdown);

        // 同步控制器
        syncController.setOnSyncComplete(this::onSyncComplete);
        syncController.setOnSyncError(this::onSyncError);
        syncController.setAutoSyncHandler(this::autoSyncNow);

        // 自动保存
        autosaveDelay.setOnFinished(event -> saveSelectedNote());
    }

    private void onFilterOrCategoryChanged() {
        refreshNotes();
    }

    // ======================== 笔记选中 ========================

    private void selectNote(Note note) {
        autosaveDelay.stop();
        if (selectedNote == null || note == null || !selectedNote.getNoteUuid().equals(note.getNoteUuid())) {
            clearLocalFailure();
        }
        selectedNote = note;
        loadingSelection = true;
        try {
            if (note == null) {
                editorPanel.clearEditor();
                editorPanel.setEditorDisabled(true);
                editorPanel.setBreadcrumb(sidebar.contextLabel());
                updateEditorStatus("未选择笔记");
            } else {
                editorPanel.setEditorDisabled(false);
                editorPanel.loadNote(note);
                editorPanel.setBreadcrumb(sidebar.contextLabel() + " / " + nullToEmpty(note.getTitle()));
                updateEditorStatus("已打开");
            }
        } finally {
            loadingSelection = false;
        }
    }

    // ======================== 创建 / 删除 / 恢复 ========================

    private void createNote() {
        try {
            Note note = noteRepository.createEmpty();
            if (sidebar.isCategoryFilterActive()) {
                note.setCategoryName(sidebar.getCurrentCategoryName());
                noteRepository.save(note);
            }
            listPanel.clearSearch();
            refreshNotes();
            clearLocalFailure();
            Note inList = listPanel.getNotes().stream()
                    .filter(item -> item.getNoteUuid().equals(note.getNoteUuid()))
                    .findFirst()
                    .orElse(null);
            if (inList != null) {
                listPanel.select(inList);
                selectNote(inList);
            }
            editorPanel.requestTitleFocus();
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "新建笔记", ex);
        }
    }

    private void deleteSelectedNote() {
        if (selectedNote == null) {
            return;
        }
        try {
            Note noteToDelete = selectedNote;
            if (noteToDelete.isTrashed()) {
                noteRepository.softDelete(noteToDelete);
                syncController.scheduleAutoSync();
                editorPanel.setSaveStatus("已彻底删除，等待同步");
            } else {
                noteRepository.moveToTrash(noteToDelete);
                editorPanel.setSaveStatus("已移入回收站");
            }
            clearLocalFailure();
            selectedNote = null;
            refreshNotes();
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "删除笔记", ex);
        }
    }

    private void restoreSelectedNote() {
        if (selectedNote == null || !selectedNote.isTrashed()) {
            return;
        }
        try {
            Note noteToRestore = selectedNote;
            noteRepository.restoreFromTrash(noteToRestore);
            clearLocalFailure();
            if (sidebar.getCurrentFilter() == NoteFilter.TRASH) {
                selectedNote = null;
                refreshNotes();
            } else {
                refreshNotesAndSelect(noteToRestore.getNoteUuid());
            }
            editorPanel.setSaveStatus("已从回收站恢复");
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "恢复笔记", ex);
        }
    }

    // ======================== 保存 ========================

    private void saveToggleChange() {
        if (loadingSelection || selectedNote == null) {
            return;
        }
        autosaveDelay.stop();
        saveSelectedNote();
    }

    private void scheduleAutosave() {
        if (loadingSelection || selectedNote == null) {
            return;
        }
        if (!hasPendingEditorChanges()) {
            editorPanel.updateWordCount();
            return;
        }
        editorPanel.setBreadcrumb(sidebar.contextLabel() + " / " + editorPanel.getTitleText());
        editorPanel.setSaveStatus("正在输入...");
        updateSyncLamp(selectedNote);
        editorPanel.updateWordCount();
        autosaveDelay.playFromStart();
    }

    private boolean hasPendingEditorChanges() {
        if (selectedNote == null) {
            return false;
        }
        boolean titleChanged = !nullToEmpty(selectedNote.getTitle()).equals(nullToEmpty(editorPanel.getTitleText()));
        boolean categoryChanged = !nullToEmpty(sidebar.normalizeCategoryName(selectedNote.getCategoryName()))
                .equals(nullToEmpty(sidebar.normalizeCategoryName(editorPanel.getCategoryText())));
        String originalContent = selectedNote.getContent();
        String displayedContent = editorPanel.editorTextForNote(selectedNote);
        String editorContent = editorPanel.getEditorContent();
        boolean contentChanged = selectedNote.getContentFormat() == ContentFormat.HTML
                ? !nullToEmpty(displayedContent).equals(nullToEmpty(editorContent))
                : !nullToEmpty(originalContent).equals(nullToEmpty(editorContent));
        return titleChanged
                || categoryChanged
                || contentChanged
                || selectedNote.isPinned() != editorPanel.isPinnedSelected()
                || selectedNote.isFavorite() != editorPanel.isFavoriteSelected()
                || selectedNote.isArchived() != editorPanel.isArchivedSelected();
    }

    /**
     * 将当前编辑器内容写回选中笔记，并按变化范围决定只刷新卡片还是整列重载。
     */
    private boolean saveSelectedNote() {
        return saveSelectedNote(true);
    }

    private boolean saveSelectedNote(boolean scheduleSync) {
        if (selectedNote == null || loadingSelection) {
            return true;
        }
        boolean titleChanged = !nullToEmpty(selectedNote.getTitle()).equals(nullToEmpty(editorPanel.getTitleText()));
        boolean categoryChanged = !nullToEmpty(sidebar.normalizeCategoryName(selectedNote.getCategoryName()))
                .equals(nullToEmpty(sidebar.normalizeCategoryName(editorPanel.getCategoryText())));
        String originalContent = selectedNote.getContent();
        String displayedContent = editorPanel.editorTextForNote(selectedNote);
        String editorContent = editorPanel.getEditorContent();
        boolean convertHtmlToMarkdown = selectedNote.getContentFormat() == ContentFormat.HTML
                && !nullToEmpty(displayedContent).equals(nullToEmpty(editorContent));
        boolean contentChanged = selectedNote.getContentFormat() == ContentFormat.HTML
                ? convertHtmlToMarkdown
                : !nullToEmpty(originalContent).equals(nullToEmpty(editorContent));
        boolean pinnedChanged = selectedNote.isPinned() != editorPanel.isPinnedSelected();
        boolean favoriteChanged = selectedNote.isFavorite() != editorPanel.isFavoriteSelected();
        boolean archivedChanged = selectedNote.isArchived() != editorPanel.isArchivedSelected();
        boolean hasChanges = titleChanged || categoryChanged || contentChanged
                || pinnedChanged || favoriteChanged || archivedChanged;
        if (!hasChanges) {
            clearLocalFailure();
            editorPanel.setSaveStatus("已保存到本地");
            return true;
        }
        selectedNote.setTitle(editorPanel.getTitleText());
        selectedNote.setCategoryName(editorPanel.getCategoryText());
        selectedNote.setContent(convertHtmlToMarkdown || selectedNote.getContentFormat() == ContentFormat.MARKDOWN
                ? editorContent
                : originalContent);
        if (convertHtmlToMarkdown) {
            selectedNote.setContentFormat(ContentFormat.MARKDOWN);
        }
        selectedNote.setPinned(editorPanel.isPinnedSelected());
        selectedNote.setFavorite(editorPanel.isFavoriteSelected());
        selectedNote.setArchived(editorPanel.isArchivedSelected());
        try {
            noteRepository.save(selectedNote);
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "本地保存", ex);
            return false;
        }
        clearLocalFailure();
        editorPanel.setSaveStatus(scheduleSync ? "已保存到本地，等待同步" : "已保存到本地");
        sidebar.refreshNavigationCounts();
        sidebar.refreshCategoryList();
        editorPanel.refreshCategoryOptions(configRepository.categoryCatalog());
        if (scheduleSync) {
            syncController.scheduleAutoSync();
        }
        boolean needsFullReload = archivedChanged
                || categoryChanged
                || (sidebar.isCategoryFilterActive() && !sidebar.categoryMatchesCurrentFilter(selectedNote))
                || (favoriteChanged && sidebar.getCurrentFilter() == NoteFilter.FAVORITES)
                || (contentChanged && listPanel.isSearchActive())
                || (titleChanged && listPanel.isSearchActive());
        boolean needsCardRefresh = titleChanged || categoryChanged || pinnedChanged || favoriteChanged || archivedChanged;
        if (needsFullReload) {
            refreshNotes();
        } else if (needsCardRefresh) {
            listPanel.refreshList();
        }
        return true;
    }

    // ======================== Markdown 转换 ========================

    private void convertSelectedNoteToMarkdown() {
        if (selectedNote == null || selectedNote.getContentFormat() != ContentFormat.HTML) {
            return;
        }
        String originalContent = selectedNote.getContent();
        ContentFormat originalFormat = selectedNote.getContentFormat();
        String converted = HtmlToMarkdownConverter.convert(originalContent);
        if (!editorPanel.confirmMarkdownConversion(selectedNote, converted)) {
            return;
        }
        try {
            loadingSelection = true;
            editorPanel.setEditorContent(converted);
            // setEditorContent triggers content listener → refreshMarkdownPreview
            selectedNote.setContentFormat(ContentFormat.MARKDOWN);
            editorPanel.updateContentFormatControls(selectedNote);
        } finally {
            loadingSelection = false;
        }
        if (!saveSelectedNote()) {
            selectedNote.setContentFormat(originalFormat);
            selectedNote.setContent(originalContent);
            editorPanel.loadNote(selectedNote);
            editorPanel.updateContentFormatControls(selectedNote);
            return;
        }
        editorPanel.setSaveStatus("已转换为 Markdown");
    }

    // ======================== 同步 ========================

    private void handleSyncClicked(Button syncButton) {
        syncController.syncNow(true, () -> saveSelectedNote(false), selectedNote);
    }

    private void autoSyncNow() {
        syncController.syncNow(false, () -> saveSelectedNote(false), selectedNote);
    }

    private void onSyncComplete(ClientSyncService.SyncSummary summary, Note currentlySelected) {
        if (currentlySelected != null) {
            String conflictCopyUuid = summary.conflictCopyUuids().get(currentlySelected.getNoteUuid());
            if (conflictCopyUuid != null) {
                refreshNotesAndSelect(conflictCopyUuid);
                editorPanel.setSaveStatus("检测到同步冲突，已切换到冲突副本继续编辑");
                return;
            }
        }
        refreshNotesPreservingEditor();
        String prefix = summary.conflictCount() > 0 ? "自动同步发现 " + summary.conflictCount() + " 个冲突" : "";
        editorPanel.setSaveStatus(prefix.isBlank() ? "已同步" : prefix);
        if (summary.conflictCount() > 0) {
            editorPanel.setSyncLamp("unsynced", "冲突");
        } else {
            editorPanel.setSyncLamp("synced", "已同步");
        }
        syncController.scheduleDeferredSyncIfNeeded();
    }

    private void onSyncError(Exception ex, boolean manual) {
        String errorMsg = SyncController.normalizeErrorMessage(ex, "同步失败");
        LOGGER.log(Level.WARNING, "同步失败", ex);
        editorPanel.setSaveStatus((manual ? "同步失败: " : "自动同步失败: ") + errorMsg);
        editorPanel.setSyncLamp("unsynced", errorMsg);
        if (manual && SyncController.isAuthFailure(ex)) {
            promptRelogin();
        }
        syncController.scheduleDeferredSyncIfNeeded();
    }

    // ======================== 列表刷新 ========================

    /**
     * 按当前筛选和搜索条件重载第二栏列表，并尽量保留当前选中笔记或编辑上下文。
     */
    private void refreshNotes() {
        try {
            Note previous = selectedNote;
            List<Note> loaded = noteRepository.listByFilter(
                    listPanel.getSearchText(),
                    sidebar.getCurrentFilter(),
                    sidebar.getCurrentCategoryName());
            sidebar.refreshNavigationCounts();
            sidebar.refreshCategoryList();
            clearLocalFailure();

            // 更新空状态文本
            listPanel.updateEmptyStateText(sidebar.emptyStateTitle(), sidebar.emptyStateDescription());

            // 替换列表数据
            if (previous != null) {
                String previousUuid = previous.getNoteUuid();
                loaded.stream()
                        .filter(n -> n.getNoteUuid().equals(previousUuid))
                        .findFirst()
                        .ifPresentOrElse(
                                note -> {
                                    listPanel.replaceAll(loaded, previousUuid);
                                    Note selectedInList = listPanel.getSelectedItem();
                                    if (selectedInList != null && !selectedInList.getNoteUuid().equals(previousUuid)) {
                                        selectNote(selectedInList);
                                    }
                                },
                                () -> {
                                    if (shouldPreserveEditorDuringSearch(previous, loaded)) {
                                        selectedNote = previous;
                                        listPanel.replaceAllPreservingSelection(loaded);
                                        listPanel.clearSelection();
                                        listPanel.refreshList();
                                        updateBreadcrumb();
                                        updateSyncLamp(selectedNote);
                                        return;
                                    }
                                    if (!loaded.isEmpty()) {
                                        listPanel.replaceAll(loaded, null);
                                        selectNote(listPanel.getSelectedItem());
                                    } else {
                                        listPanel.replaceAll(loaded, null);
                                        selectNote(null);
                                    }
                                }
                        );
            } else {
                listPanel.replaceAll(loaded, null);
                if (listPanel.getSelectedItem() != null) {
                    selectNote(listPanel.getSelectedItem());
                } else {
                    selectNote(null);
                }
            }
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "刷新列表", ex);
        }
    }

    private boolean shouldPreserveEditorDuringSearch(Note note, List<Note> loaded) {
        return note != null
                && listPanel.isSearchActive()
                && loaded.stream().noneMatch(n -> n.getNoteUuid().equals(note.getNoteUuid()));
    }

    private void refreshNotesPreservingEditor() {
        if (selectedNote == null) {
            refreshNotes();
            return;
        }
        Note editingNote = selectedNote;
        try {
            String selectedUuid = selectedNote.getNoteUuid();
            List<Note> loaded = noteRepository.listByFilter(
                    listPanel.getSearchText(),
                    sidebar.getCurrentFilter(),
                    sidebar.getCurrentCategoryName());
            sidebar.refreshNavigationCounts();
            sidebar.refreshCategoryList();
            clearLocalFailure();

            listPanel.replaceAllPreservingSelection(loaded);
            selectedNote = editingNote;

            Note refreshedNote = loaded.stream()
                    .filter(n -> n.getNoteUuid().equals(selectedUuid))
                    .findFirst()
                    .orElse(null);
            if (refreshedNote == null) {
                listPanel.refreshList();
                updateSyncLamp(selectedNote);
                return;
            }
            selectedNote.setSyncStatus(refreshedNote.getSyncStatus());
            selectedNote.setServerVersion(refreshedNote.getServerVersion());
            selectedNote.setUpdateTime(refreshedNote.getUpdateTime());
            selectedNote.setTrashed(refreshedNote.isTrashed());
            selectedNote.setCategoryName(refreshedNote.getCategoryName());
            editorPanel.setUpdateTime("更新 " + formatDisplayTime(selectedNote.getUpdateTime()));

            listPanel.selectByUuid(selectedUuid);
            listPanel.refreshList();
            updateSyncLamp(selectedNote);
        } catch (RuntimeException ex) {
            markLocalFailure(editingNote, "刷新列表", ex);
        }
    }

    private void refreshNotesAndSelect(String noteUuid) {
        try {
            List<Note> loaded = noteRepository.listByFilter(
                    listPanel.getSearchText(),
                    sidebar.getCurrentFilter(),
                    sidebar.getCurrentCategoryName());
            sidebar.refreshNavigationCounts();
            sidebar.refreshCategoryList();
            clearLocalFailure();
            listPanel.replaceAll(loaded, noteUuid);
            Note target = listPanel.getSelectedItem();
            if (target != null) {
                selectNote(target);
            } else if (!loaded.isEmpty()) {
                selectNote(loaded.get(0));
            } else {
                selectNote(null);
            }
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "刷新列表", ex);
        }
    }

    // ======================== 状态更新 ========================

    private void updateEditorStatus(String message) {
        if (selectedNote == null) {
            editorPanel.setSaveStatus(message);
            editorPanel.setSyncLamp("unsynced");
            editorPanel.setUpdateTime("");
            editorPanel.setWordCount("0 字");
            return;
        }
        editorPanel.setSaveStatus(message);
        updateSyncLamp(selectedNote);
        editorPanel.setUpdateTime("更新 " + formatDisplayTime(selectedNote.getUpdateTime()));
        editorPanel.updateWordCount();
    }

    private void updateSyncLamp(Note note) {
        String state;
        String tooltip;

        if (hasLocalFailure(note)) {
            state = "unsynced";
            tooltip = lastLocalFailureMessage;
        } else if (syncController.isSyncInProgress()) {
            state = "syncing";
            tooltip = "同步中";
        } else if (syncController.isSyncFailureActive()) {
            state = "unsynced";
            String err = syncController.getLastSyncError();
            tooltip = (err == null || err.isBlank()) ? "同步失败" : "同步失败: " + err;
        } else if (note != null) {
            state = syncController.resolveSyncLampState(note, false, null);
            if (note.getSyncStatus() == com.lightnote.client.model.SyncStatus.DIRTY && isConflictCopy(note)) {
                tooltip = "冲突副本，待同步";
            } else {
                tooltip = syncController.resolveSyncLampTooltip(note, false, null);
            }
        } else {
            state = "unsynced";
            tooltip = "未同步";
        }
        editorPanel.setSyncLamp(state, tooltip);
    }

    private void updateBreadcrumb() {
        String context = sidebar.contextLabel();
        if (selectedNote == null) {
            editorPanel.setBreadcrumb(context);
            return;
        }
        editorPanel.setBreadcrumb(context + " / " + nullToEmpty(selectedNote.getTitle()));
    }

    // ======================== 本地失败处理 ========================

    private boolean hasLocalFailure(Note note) {
        if (lastLocalFailureMessage == null || lastLocalFailureMessage.isBlank()) {
            return false;
        }
        return localFailureNoteUuid == null
                || note == null
                || localFailureNoteUuid.equals(note.getNoteUuid());
    }

    private void markLocalFailure(Note note, String action, RuntimeException ex) {
        LOGGER.log(Level.WARNING, action + "失败", ex);
        localFailureNoteUuid = note == null ? null : note.getNoteUuid();
        lastLocalFailureMessage = action + "失败: " + SyncController.normalizeErrorMessage(ex, action + "失败");
        editorPanel.setSaveStatus(lastLocalFailureMessage);
        editorPanel.setSyncLamp("unsynced", lastLocalFailureMessage);
    }

    private void clearLocalFailure() {
        localFailureNoteUuid = null;
        lastLocalFailureMessage = null;
    }

    // ======================== 对话框 ========================

    private void openToolsDialog() {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) {
            new ToolsDialog().show(owner);
        }
    }

    private void openSettingsDialog() {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) {
            new SettingsDialog(configRepository, syncService, onLogout).show(owner);
        }
    }

    private void promptRelogin() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("登录已过期");
        alert.setHeaderText("当前登录状态已失效");
        alert.setContentText("点击「重新登录」返回登录界面，重新获取同步凭据。");
        alert.getButtonTypes().setAll(ButtonType.CANCEL, new ButtonType("重新登录", ButtonBar.ButtonData.OK_DONE));
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && "重新登录".equals(result.get().getText())) {
            configRepository.clearLogin();
            onLogout.run();
        }
    }

    // ======================== 工具方法 ========================

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String formatDisplayTime(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return LocalDateTime.parse(value).format(DISPLAY_TIME_FORMATTER);
        } catch (Exception ignored) {
            return value.replace('T', ' ');
        }
    }

    private boolean isConflictCopy(Note note) {
        return note != null
                && note.getTitle() != null
                && note.getTitle().contains(CONFLICT_COPY_MARKER);
    }

    private void bindContentDividerPersistence() {
        if (contentSplitPane.getDividers().isEmpty()) {
            return;
        }
        contentSplitPane.setDividerPositions(
                clampDividerPosition(configRepository.getDouble(NOTE_EDITOR_DIVIDER_KEY, 0.34)));
        contentSplitPane.getDividers().get(0).positionProperty().addListener((obs, oldValue, newValue) ->
                configRepository.put(NOTE_EDITOR_DIVIDER_KEY,
                        String.format(Locale.ROOT, "%.5f", clampDividerPosition(newValue.doubleValue()))));
    }

    private double clampDividerPosition(double value) {
        return Math.max(0.22, Math.min(0.55, value));
    }
}
