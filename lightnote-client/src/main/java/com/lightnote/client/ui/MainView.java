package com.lightnote.client.ui;

import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.sync.ClientSyncService;
import com.lightnote.client.util.HtmlTextExtractor;
import javafx.application.Platform;
import java.util.List;
import java.util.Locale;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.HTMLEditor;
import javafx.util.Duration;

public class MainView {

    private static final String NOTE_EDITOR_DIVIDER_KEY = "note_editor_divider_position";

    private static final String EDITOR_STYLE = """
            <style id="lightnote-editor-style">
            body {
                background: #ffffff;
                color: #1f2937;
                font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
                font-size: 15px;
                line-height: 1.75;
                margin: 20px 56px 48px 56px;
            }
            p { margin: 0 0 12px 0; }
            h1, h2, h3 { color: #111827; line-height: 1.35; margin: 20px 0 12px 0; }
            a { color: #3867d6; }
            blockquote {
                border-left: 3px solid #5b7cfa;
                color: #4b5563;
                margin: 8px 0;
                padding-left: 12px;
            }
            pre, code {
                background: #f3f6fb;
                color: #1f2937;
                border-radius: 4px;
            }
            ::-webkit-scrollbar { width: 8px; height: 8px; }
            ::-webkit-scrollbar-track { background: transparent; }
            ::-webkit-scrollbar-thumb {
                background: #d6deea;
                border-radius: 8px;
            }
            ::-webkit-scrollbar-thumb:hover { background: #b8c4d4; }
            </style>
            """;

    private final NoteRepository noteRepository;
    private final AppConfigRepository configRepository;
    private final ClientSyncService syncService;
    private final Runnable onLogout;
    private final BorderPane root = new BorderPane();
    private final ObservableList<Note> notes = FXCollections.observableArrayList();
    private final ListView<Note> noteList = new ListView<>(notes);
    private final TextField searchField = new TextField();
    private final TextField titleField = new TextField();
    private final TextField categoryField = new TextField();
    private final HTMLEditor contentEditor = new HTMLEditor();
    private final CheckBox pinnedBox = new CheckBox("置顶");
    private final CheckBox favoriteBox = new CheckBox("收藏");
    private final CheckBox archivedBox = new CheckBox("归档");
    private final Label breadcrumbLabel = new Label("全部笔记");
    private final Label saveStatusLabel = new Label("未选择笔记");
    private final Label syncStatusLabel = new Label("●");
    private final Tooltip syncStatusTooltip = new Tooltip("未同步");
    private final Label updateTimeLabel = new Label("");
    private final Label wordCountLabel = new Label("0 字");
    private final PauseTransition autosaveDelay = new PauseTransition(Duration.millis(700));
    private final PauseTransition autoSyncDelay = new PauseTransition(Duration.millis(2500));
    private final SplitPane contentSplitPane = new SplitPane();

    private Note selectedNote;
    private boolean loadingSelection;
    private boolean suppressSelectionChange;
    private boolean syncInProgress;
    private boolean syncRequestedDuringRun;
    private boolean syncFailureActive;
    private String lastSyncError;
    private NoteFilter currentFilter = NoteFilter.ALL;

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
        buildLayout();
        bindEvents();
        refreshNotes();
    }

    public Parent getRoot() {
        return root;
    }

    private void buildLayout() {
        root.getStyleClass().add("app-root");
        root.setLeft(buildNavigationColumn());
        root.setCenter(buildContentSplitPane());
    }

    private Parent buildContentSplitPane() {
        contentSplitPane.getItems().setAll(buildNoteWorkspace(), buildEditor());
        contentSplitPane.setDividerPositions(clampDividerPosition(configRepository.getDouble(NOTE_EDITOR_DIVIDER_KEY, 0.34)));
        contentSplitPane.getStyleClass().add("main-split-pane");
        Platform.runLater(this::bindContentDividerPersistence);
        return contentSplitPane;
    }

    private Parent buildNavigationColumn() {
        Label avatar = new Label("LN");
        avatar.getStyleClass().add("avatar-badge");

        Label productName = new Label("LightNote");
        productName.getStyleClass().add("product-name");

        Label navTitle = new Label("浏览");
        navTitle.getStyleClass().add("section-label");

        Button allNotes = navButton("全部笔记", NoteFilter.ALL);
        Button today = navButton("今天", NoteFilter.TODAY);
        Button week = navButton("最近 7 天", NoteFilter.RECENT_7_DAYS);
        Button favorites = navButton("收藏", NoteFilter.FAVORITES);
        Button archive = navButton("归档", NoteFilter.ARCHIVED);

        Label categoryTitle = new Label("分类");
        categoryTitle.getStyleClass().add("section-label");
        Label defaultCategory = new Label("默认");
        defaultCategory.getStyleClass().add("category-placeholder");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button settings = new Button("设置");
        settings.getStyleClass().add("secondary-nav-button");
        settings.setOnAction(event -> new SettingsDialog(configRepository, onLogout)
                .show(root.getScene().getWindow()));

        VBox sidebar = new VBox(10,
                avatar,
                productName,
                new Separator(),
                navTitle,
                allNotes,
                today,
                week,
                favorites,
                archive,
                new Separator(),
                categoryTitle,
                defaultCategory,
                spacer,
                settings);
        sidebar.setPrefWidth(120);
        sidebar.setMaxWidth(120);
        sidebar.setMinWidth(100);
        sidebar.setPadding(new Insets(18, 14, 18, 14));
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private Parent buildQuickActions() {
        searchField.setPromptText("搜索标题、正文或摘要");
        searchField.getStyleClass().add("search-field");

        Button newButton = new Button("+ 新建");
        newButton.getStyleClass().add("primary-button");
        newButton.setMaxWidth(Double.MAX_VALUE);
        newButton.setOnAction(event -> createNote());

        VBox quickActions = new VBox(10, searchField, newButton);
        quickActions.getStyleClass().add("quick-actions");
        return quickActions;
    }

    private Parent buildNoteWorkspace() {
        VBox workspace = new VBox(12, buildQuickActions(), buildNoteList());
        workspace.setPrefWidth(320);
        workspace.setMinWidth(240);
        workspace.setPadding(new Insets(16, 16, 12, 16));
        workspace.getStyleClass().add("workspace-panel");
        VBox.setVgrow(noteList, Priority.ALWAYS);
        return workspace;
    }

    private Button navButton(String text, NoteFilter filter) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-button");
        button.setOnAction(event -> {
            currentFilter = filter;
            refreshNotes();
        });
        return button;
    }

    private Parent buildNoteList() {
        noteList.setCellFactory(list -> new NoteCardCell());
        noteList.getStyleClass().add("note-list");
        return noteList;
    }

    private Parent buildEditor() {
        titleField.setPromptText("标题");
        titleField.getStyleClass().add("title-field");

        categoryField.setPromptText("分类");
        categoryField.getStyleClass().add("category-field");

        contentEditor.getStyleClass().add("rich-editor");
        contentEditor.setPrefHeight(620);
        VBox.setVgrow(contentEditor, Priority.ALWAYS);

        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("ghost-button");
        saveButton.setOnAction(event -> saveSelectedNote());

        Button syncButton = new Button("同步");
        syncButton.getStyleClass().add("ghost-button");
        syncButton.setOnAction(event -> syncNow(syncButton));

        Button deleteButton = new Button("删除");
        deleteButton.getStyleClass().add("danger-link-button");
        deleteButton.setOnAction(event -> deleteSelectedNote());
        syncStatusLabel.setTooltip(syncStatusTooltip);
        setSyncLamp("unsynced", "未同步");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox topLine = new HBox(10, breadcrumbLabel, headerSpacer, syncStatusLabel, saveButton, syncButton, deleteButton);
        topLine.setAlignment(Pos.CENTER_LEFT);
        topLine.getStyleClass().add("document-topline");

        HBox meta = new HBox(10, categoryField, pinnedBox, favoriteBox, archivedBox);
        meta.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(categoryField, Priority.ALWAYS);
        meta.getStyleClass().add("document-meta");

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(14, saveStatusLabel, updateTimeLabel, footerSpacer, wordCountLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("document-footer");

        VBox document = new VBox(12, topLine, titleField, meta, contentEditor, footer);
        document.setMaxWidth(980);
        document.getStyleClass().add("document-surface");
        VBox.setVgrow(contentEditor, Priority.ALWAYS);

        VBox editor = new VBox(document);
        editor.setMinWidth(420);
        editor.setPadding(new Insets(28, 34, 20, 34));
        editor.getStyleClass().add("editor");
        VBox.setVgrow(document, Priority.ALWAYS);
        return editor;
    }

    private void bindEvents() {
        noteList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (!suppressSelectionChange) {
                selectNote(newValue);
            }
        });
        searchField.textProperty().addListener((obs, oldValue, newValue) -> refreshNotes());

        autosaveDelay.setOnFinished(event -> saveSelectedNote());
        autoSyncDelay.setOnFinished(event -> autoSyncNow());
        titleField.textProperty().addListener((obs, oldValue, newValue) -> scheduleAutosave());
        categoryField.textProperty().addListener((obs, oldValue, newValue) -> scheduleAutosave());
        contentEditor.setOnKeyReleased(event -> scheduleAutosave());
        contentEditor.setOnMouseReleased(event -> scheduleAutosave());
        pinnedBox.selectedProperty().addListener((obs, oldValue, newValue) -> scheduleAutosave());
        favoriteBox.selectedProperty().addListener((obs, oldValue, newValue) -> scheduleAutosave());
        archivedBox.selectedProperty().addListener((obs, oldValue, newValue) -> scheduleAutosave());
    }

    private void refreshNotes() {
        Note selected = selectedNote;
        List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter);
        notes.setAll(loaded);
        if (selected != null) {
            notes.stream()
                    .filter(note -> note.getNoteUuid().equals(selected.getNoteUuid()))
                    .findFirst()
                    .ifPresentOrElse(
                            note -> noteList.getSelectionModel().select(note),
                            () -> {
                                if (!notes.isEmpty()) {
                                    noteList.getSelectionModel().selectFirst();
                                } else {
                                    selectNote(null);
                                }
                            }
                    );
        } else if (!notes.isEmpty()) {
            noteList.getSelectionModel().selectFirst();
        } else {
            selectNote(null);
        }
    }

    private void refreshNotesPreservingEditor() {
        if (selectedNote == null) {
            refreshNotes();
            return;
        }
        Note editingNote = selectedNote;
        String selectedUuid = selectedNote.getNoteUuid();
        List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter);
        suppressSelectionChange = true;
        try {
            notes.setAll(loaded);
        } finally {
            suppressSelectionChange = false;
        }
        selectedNote = editingNote;
        Note refreshedNote = loaded.stream()
                .filter(note -> note.getNoteUuid().equals(selectedUuid))
                .findFirst()
                .orElse(null);
        if (refreshedNote == null) {
            noteList.refresh();
            updateSyncLamp(selectedNote);
            return;
        }
        selectedNote.setSyncStatus(refreshedNote.getSyncStatus());
        selectedNote.setServerVersion(refreshedNote.getServerVersion());
        selectedNote.setUpdateTime(refreshedNote.getUpdateTime());
        updateTimeLabel.setText("更新 " + selectedNote.getUpdateTime());
        suppressSelectionChange = true;
        try {
            noteList.getSelectionModel().select(refreshedNote);
        } finally {
            suppressSelectionChange = false;
        }
        noteList.refresh();
        updateSyncLamp(selectedNote);
    }

    private void createNote() {
        Note note = noteRepository.createEmpty();
        searchField.clear();
        refreshNotes();
        noteList.getSelectionModel().select(notes.stream()
                .filter(item -> item.getNoteUuid().equals(note.getNoteUuid()))
                .findFirst()
                .orElse(note));
        titleField.requestFocus();
        titleField.selectAll();
    }

    private void selectNote(Note note) {
        autosaveDelay.stop();
        selectedNote = note;
        loadingSelection = true;
        if (note == null) {
            titleField.clear();
            categoryField.clear();
            contentEditor.setHtmlText(toEditorHtml(""));
            pinnedBox.setSelected(false);
            favoriteBox.setSelected(false);
            archivedBox.setSelected(false);
            setEditorDisabled(true);
            breadcrumbLabel.setText("全部笔记");
            updateEditorStatus("未选择笔记");
        } else {
            setEditorDisabled(false);
            titleField.setText(nullToEmpty(note.getTitle()));
            categoryField.setText(nullToEmpty(note.getCategoryName()));
            contentEditor.setHtmlText(toEditorHtml(note.getContent()));
            pinnedBox.setSelected(note.isPinned());
            favoriteBox.setSelected(note.isFavorite());
            archivedBox.setSelected(note.isArchived());
            breadcrumbLabel.setText("全部笔记 / " + nullToEmpty(note.getTitle()));
            updateEditorStatus("已打开");
        }
        loadingSelection = false;
    }

    private void setEditorDisabled(boolean disabled) {
        titleField.setDisable(disabled);
        categoryField.setDisable(disabled);
        contentEditor.setDisable(disabled);
        pinnedBox.setDisable(disabled);
        favoriteBox.setDisable(disabled);
        archivedBox.setDisable(disabled);
    }

    private void scheduleAutosave() {
        if (loadingSelection || selectedNote == null) {
            return;
        }
        breadcrumbLabel.setText("全部笔记 / " + titleField.getText());
        saveStatusLabel.setText("正在输入...");
        setSyncLamp("unsynced");
        updateWordCount();
        autosaveDelay.playFromStart();
    }

    private void saveSelectedNote() {
        saveSelectedNote(true);
    }

    private void saveSelectedNote(boolean scheduleSync) {
        if (selectedNote == null || loadingSelection) {
            return;
        }
        boolean archivedChanged = selectedNote.isArchived() != archivedBox.isSelected();
        selectedNote.setTitle(titleField.getText());
        selectedNote.setCategoryName(categoryField.getText());
        selectedNote.setContent(stripEditorStyle(contentEditor.getHtmlText()));
        selectedNote.setPinned(pinnedBox.isSelected());
        selectedNote.setFavorite(favoriteBox.isSelected());
        selectedNote.setArchived(archivedBox.isSelected());
        noteRepository.save(selectedNote);
        updateEditorStatus(scheduleSync ? "已保存到本地，等待同步" : "已保存到本地");
        if (scheduleSync) {
            scheduleAutoSync();
        }
        if (archivedChanged) {
            refreshNotes();
        } else {
            noteList.refresh();
        }
    }

    private void deleteSelectedNote() {
        if (selectedNote == null) {
            return;
        }
        Note noteToDelete = selectedNote;
        noteRepository.softDelete(noteToDelete);
        selectedNote = null;
        refreshNotes();
    }

    private void syncNow(Button syncButton) {
        autoSyncDelay.stop();
        saveSelectedNote(false);
        runSync(syncButton, true);
    }

    private void scheduleAutoSync() {
        if (configRepository.token().isEmpty()) {
            return;
        }
        if (syncInProgress) {
            syncRequestedDuringRun = true;
            setSyncLamp("syncing", "同步中");
            return;
        }
        setSyncLamp("unsynced", "未同步");
        autoSyncDelay.playFromStart();
    }

    private void autoSyncNow() {
        runSync(null, false);
    }

    private void runSync(Button syncButton, boolean manual) {
        if (syncInProgress) {
            if (manual) {
                saveStatusLabel.setText("同步正在进行中");
            }
            return;
        }
        syncInProgress = true;
        if (syncButton != null) {
            syncButton.setDisable(true);
        }
        setSyncLamp("syncing", "同步中");
        if (manual) {
            saveStatusLabel.setText("正在同步...");
        }
        Thread thread = new Thread(() -> {
            try {
                ClientSyncService.SyncSummary summary = syncService.syncNow();
                Platform.runLater(() -> {
                    syncInProgress = false;
                    syncFailureActive = false;
                    lastSyncError = null;
                    refreshNotesPreservingEditor();
                    if (manual) {
                        saveStatusLabel.setText("同步完成: 上传 " + summary.pushedCount()
                                + " / 冲突 " + summary.conflictCount()
                                + " / 拉取 " + summary.pulledCount());
                    } else if (summary.conflictCount() > 0) {
                        saveStatusLabel.setText("自动同步发现 " + summary.conflictCount() + " 个冲突");
                    }
                    if (summary.conflictCount() > 0) {
                        setSyncLamp("unsynced", "冲突");
                    } else {
                        setSyncLamp("synced", "已同步");
                    }
                    if (syncButton != null) {
                        syncButton.setDisable(false);
                    }
                    scheduleDeferredSyncIfNeeded();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    syncInProgress = false;
                    syncFailureActive = true;
                    lastSyncError = ex.getMessage();
                    saveStatusLabel.setText((manual ? "同步失败: " : "自动同步失败: ") + ex.getMessage());
                    setSyncLamp("unsynced", "未同步: " + ex.getMessage());
                    if (syncButton != null) {
                        syncButton.setDisable(false);
                    }
                    scheduleDeferredSyncIfNeeded();
                });
            }
        }, "lightnote-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private void scheduleDeferredSyncIfNeeded() {
        if (!syncRequestedDuringRun) {
            return;
        }
        syncRequestedDuringRun = false;
        scheduleAutoSync();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void updateEditorStatus(String message) {
        if (selectedNote == null) {
            saveStatusLabel.setText(message);
            setSyncLamp("unsynced");
            updateTimeLabel.setText("");
            wordCountLabel.setText("0 字");
            return;
        }
        saveStatusLabel.setText(message);
        updateSyncLamp(selectedNote);
        updateTimeLabel.setText("更新 " + selectedNote.getUpdateTime());
        updateWordCount();
    }

    private void updateSyncLamp(Note note) {
        if (syncInProgress) {
            setSyncLamp("syncing", "同步中");
            return;
        }
        if (syncFailureActive) {
            setSyncLamp("unsynced", lastSyncError == null || lastSyncError.isBlank() ? "未同步" : "未同步: " + lastSyncError);
            return;
        }
        switch (note.getSyncStatus()) {
            case SYNCED -> setSyncLamp("synced", "已同步");
            case SYNCING -> setSyncLamp("syncing", "同步中");
            case DIRTY -> setSyncLamp("unsynced", "待同步");
            case CONFLICT -> setSyncLamp("unsynced", "冲突");
            case DELETE_PENDING -> setSyncLamp("unsynced", "待删除");
        }
    }

    private void setSyncLamp(String state) {
        setSyncLamp(state, syncTooltipText(state));
    }

    private void setSyncLamp(String state, String tooltipText) {
        syncStatusLabel.getStyleClass().removeAll("sync-lamp", "sync-lamp-synced", "sync-lamp-syncing", "sync-lamp-unsynced");
        syncStatusLabel.getStyleClass().add("sync-lamp");
        syncStatusLabel.getStyleClass().add("sync-lamp-" + state);
        syncStatusTooltip.setText(tooltipText);
    }

    private String syncTooltipText(String state) {
        return switch (state) {
            case "synced" -> "已同步";
            case "syncing" -> "同步中";
            default -> lastSyncError == null || lastSyncError.isBlank()
                    ? "未同步"
                    : "未同步: " + lastSyncError;
        };
    }

    private void updateWordCount() {
        String plainText = plainText(stripEditorStyle(contentEditor.getHtmlText()));
        long count = plainText.chars().filter(ch -> !Character.isWhitespace(ch)).count();
        wordCountLabel.setText(count + " 字");
    }

    private String toEditorHtml(String content) {
        if (content == null || content.isBlank()) {
            return editorDocument("");
        }
        String trimmed = content.stripLeading();
        String lowerTrimmed = trimmed.toLowerCase();
        if (lowerTrimmed.startsWith("<html") || lowerTrimmed.startsWith("<!doctype")) {
            return injectEditorStyle(content);
        }
        if (lowerTrimmed.startsWith("<body") || lowerTrimmed.startsWith("<p")
                || lowerTrimmed.startsWith("<div") || lowerTrimmed.startsWith("<h")) {
            return editorDocument(content);
        }
        return editorDocument(escapeHtml(content).replace("\n", "<br>"));
    }

    private String editorDocument(String bodyHtml) {
        return "<html><head>" + EDITOR_STYLE + "</head><body>" + bodyHtml + "</body></html>";
    }

    private String injectEditorStyle(String html) {
        String withoutOldStyle = stripEditorStyle(html);
        String lowerHtml = withoutOldStyle.toLowerCase();
        int headEnd = lowerHtml.indexOf("</head>");
        if (headEnd >= 0) {
            return withoutOldStyle.substring(0, headEnd) + EDITOR_STYLE + withoutOldStyle.substring(headEnd);
        }
        int htmlStartEnd = lowerHtml.indexOf(">");
        if (lowerHtml.startsWith("<html") && htmlStartEnd >= 0) {
            return withoutOldStyle.substring(0, htmlStartEnd + 1)
                    + "<head>" + EDITOR_STYLE + "</head>"
                    + withoutOldStyle.substring(htmlStartEnd + 1);
        }
        return editorDocument(withoutOldStyle);
    }

    private String stripEditorStyle(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html.replaceAll("(?is)<style\\s+id=[\"']lightnote-editor-style[\"'][^>]*>.*?</style>", "");
    }

    private String plainText(String html) {
        return HtmlTextExtractor.toPlainText(html);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void bindContentDividerPersistence() {
        if (contentSplitPane.getDividers().isEmpty()) {
            return;
        }
        contentSplitPane.setDividerPositions(clampDividerPosition(configRepository.getDouble(NOTE_EDITOR_DIVIDER_KEY, 0.34)));
        contentSplitPane.getDividers().get(0).positionProperty().addListener((obs, oldValue, newValue) ->
                configRepository.put(NOTE_EDITOR_DIVIDER_KEY, String.format(Locale.ROOT, "%.5f", clampDividerPosition(newValue.doubleValue()))));
    }

    private double clampDividerPosition(double value) {
        return Math.max(0.22, Math.min(0.55, value));
    }

    private static class NoteCardCell extends ListCell<Note> {

        @Override
        protected void updateItem(Note note, boolean empty) {
            super.updateItem(note, empty);
            if (empty || note == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            Label title = new Label(note.getTitle());
            title.getStyleClass().add("card-title");
            title.setMaxWidth(Double.MAX_VALUE);
            title.setWrapText(true);

            Label summary = new Label(summaryText(note));
            summary.getStyleClass().add("card-summary");
            summary.setWrapText(true);
            summary.setMaxHeight(42);
            summary.setMaxWidth(Double.MAX_VALUE);

            Label meta = new Label(metaText(note));
            meta.getStyleClass().add("card-meta");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label status = new Label("●");
            status.getStyleClass().add("card-sync-lamp");
            status.getStyleClass().add("card-sync-lamp-" + syncLampState(note));
            status.setTooltip(new Tooltip(syncTooltipText(note)));
            HBox bottom = new HBox(8, meta, spacer, status);
            bottom.setAlignment(Pos.CENTER_LEFT);

            VBox card = new VBox(6, title, summary, bottom);
            card.getStyleClass().add("note-card");
            card.setPadding(new Insets(10));
            card.setFillWidth(true);
            card.prefWidthProperty().bind(widthProperty().subtract(18));
            card.maxWidthProperty().bind(widthProperty().subtract(18));
            title.maxWidthProperty().bind(card.widthProperty().subtract(20));
            summary.maxWidthProperty().bind(card.widthProperty().subtract(20));
            setText(null);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setGraphic(card);
        }

        private static String metaText(Note note) {
            String category = note.getCategoryName() == null || note.getCategoryName().isBlank()
                    ? "未分类"
                    : note.getCategoryName();
            String marker = (note.isPinned() ? "置顶 " : "") + (note.isFavorite() ? "收藏 " : "");
            return marker + category + "  " + note.getUpdateTime();
        }

        private static String syncLampState(Note note) {
            return switch (note.getSyncStatus()) {
                case SYNCED -> "synced";
                case SYNCING -> "syncing";
                case DIRTY, CONFLICT, DELETE_PENDING -> "unsynced";
            };
        }

        private static String syncTooltipText(Note note) {
            return switch (note.getSyncStatus()) {
                case SYNCED -> "已同步";
                case SYNCING -> "同步中";
                case DIRTY -> "待同步";
                case CONFLICT -> "冲突";
                case DELETE_PENDING -> "待删除";
            };
        }

        private static String summaryText(Note note) {
            String candidate = sanitizePreview(note.getSummary());
            if (!candidate.isBlank()) {
                return candidate;
            }
            candidate = sanitizePreview(note.getContent());
            return candidate.isBlank() ? "无正文" : candidate;
        }

        private static String sanitizePreview(String value) {
            return HtmlTextExtractor.toPlainText(value).replaceAll("\\s+", " ").strip();
        }
    }
}
