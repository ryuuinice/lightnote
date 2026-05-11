package com.lightnote.client.ui;

import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.sync.ClientSyncService;
import com.lightnote.client.util.HtmlContentSanitizer;
import com.lightnote.client.util.HtmlTextExtractor;
import javafx.application.Platform;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.HTMLEditor;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

public class MainView {

    private static final String NOTE_EDITOR_DIVIDER_KEY = "note_editor_divider_position";
    private static final String CONFLICT_COPY_MARKER = " - 冲突副本 - ";

    private static final String EDITOR_STYLE = """
            <style id="lightnote-editor-style">
            body {
                background: #ffffff;
                color: #223046;
                font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
                font-size: 15px;
                line-height: 1.82;
                margin: 24px 60px 56px 60px;
            }
            p {
                margin: 0 0 14px 0;
            }
            h1, h2, h3 {
                color: #152033;
                line-height: 1.3;
                font-weight: 700;
                margin: 28px 0 14px 0;
            }
            h1 {
                font-size: 28px;
                margin-top: 8px;
            }
            h2 {
                font-size: 22px;
            }
            h3 {
                font-size: 18px;
            }
            ul, ol {
                margin: 0 0 16px 22px;
                padding: 0;
            }
            li {
                margin: 0 0 8px 0;
            }
            a { color: #3867d6; }
            blockquote {
                border-left: 3px solid #5b7cfa;
                color: #4f5f76;
                margin: 10px 0 16px 0;
                padding: 2px 0 2px 14px;
            }
            pre, code {
                background: #f3f6fb;
                color: #1f2937;
                border-radius: 4px;
            }
            pre {
                margin: 12px 0 16px 0;
                padding: 12px 14px;
                white-space: pre-wrap;
            }
            code {
                padding: 1px 4px;
            }
            hr {
                border: none;
                border-top: 1px solid #dce4f0;
                margin: 20px 0;
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
    private final Button clearSearchButton = new Button("清空");
    private final TextField titleField = new TextField();
    private final TextField categoryField = new TextField();
    private final HTMLEditor contentEditor = new HTMLEditor();
    private final Button syncButton = new Button("同步");
    private final CheckBox pinnedBox = new CheckBox("置顶");
    private final CheckBox favoriteBox = new CheckBox("收藏");
    private final CheckBox archivedBox = new CheckBox("归档");
    private final Label breadcrumbLabel = new Label("全部笔记");
    private final Label saveStatusLabel = new Label("未选择笔记");
    private final Label syncStatusLabel = new Label("●");
    private final Tooltip syncStatusTooltip = new Tooltip("未同步");
    private final Label updateTimeLabel = new Label("");
    private final Label wordCountLabel = new Label("0 字");
    private final Map<NoteFilter, Label> navigationCountLabels = new EnumMap<>(NoteFilter.class);
    private final Map<NoteFilter, Button> navigationButtons = new EnumMap<>(NoteFilter.class);
    private final Label emptyStateTitleLabel = new Label();
    private final Label emptyStateDescriptionLabel = new Label();
    private final PauseTransition autosaveDelay = new PauseTransition(Duration.millis(700));
    private final PauseTransition autoSyncDelay = new PauseTransition(Duration.millis(2500));
    private final PauseTransition manualSyncFeedbackDelay = new PauseTransition(Duration.millis(1400));
    private final SplitPane contentSplitPane = new SplitPane();

    private Note selectedNote;
    private boolean loadingSelection;
    private boolean suppressSelectionChange;
    private boolean syncInProgress;
    private boolean syncRequestedDuringRun;
    private boolean syncFailureActive;
    private String lastSyncError;
    private String localFailureNoteUuid;
    private String lastLocalFailureMessage;
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
        manualSyncFeedbackDelay.setOnFinished(event -> resetSyncButton());
        buildLayout();
        setCurrentFilter(currentFilter);
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
        Button favorites = navButton("收藏", NoteFilter.FAVORITES, true);
        Button conflicts = navButton("冲突", NoteFilter.CONFLICT_COPIES, true);
        Button archive = navButton("归档", NoteFilter.ARCHIVED, true);

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
                conflicts,
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
        clearSearchButton.getStyleClass().add("search-clear-button");
        clearSearchButton.setManaged(false);
        clearSearchButton.setVisible(false);
        clearSearchButton.setOnAction(event -> searchField.clear());

        HBox searchRow = new HBox(8, searchField, clearSearchButton);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        Button newButton = new Button("+ 新建");
        newButton.getStyleClass().add("primary-button");
        newButton.setMaxWidth(Double.MAX_VALUE);
        newButton.setOnAction(event -> createNote());

        VBox quickActions = new VBox(10, searchRow, newButton);
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
        return navButton(text, filter, false);
    }

    private Button navButton(String text, NoteFilter filter, boolean showCountBadge) {
        Button button = new Button();
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-button");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setGraphic(buildNavButtonGraphic(text, showCountBadge ? createNavigationCountLabel(filter) : null));
        button.setOnAction(event -> {
            setCurrentFilter(filter);
            refreshNotes();
        });
        navigationButtons.put(filter, button);
        return button;
    }

    private Parent buildNavButtonGraphic(String text, Label countLabel) {
        Label titleLabel = new Label(text);
        titleLabel.getStyleClass().add("nav-button-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = countLabel == null
                ? new HBox(8, titleLabel, spacer)
                : new HBox(8, titleLabel, spacer, countLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private Label createNavigationCountLabel(NoteFilter filter) {
        Label label = new Label("0");
        label.getStyleClass().add("nav-count-badge");
        navigationCountLabels.put(filter, label);
        return label;
    }

    private Parent buildNoteList() {
        noteList.setCellFactory(list -> new NoteCardCell());
        noteList.getStyleClass().add("note-list");
        noteList.setPlaceholder(buildEmptyState());
        return noteList;
    }

    private Parent buildEditor() {
        titleField.setPromptText("标题");
        titleField.getStyleClass().add("title-field");

        categoryField.setPromptText("分类");
        categoryField.getStyleClass().add("category-field");

        contentEditor.getStyleClass().add("rich-editor");
        contentEditor.setPrefHeight(620);
        contentEditor.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(this::configureEditorToolbar);
            }
        });
        VBox.setVgrow(contentEditor, Priority.ALWAYS);

        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("ghost-button");
        saveButton.setOnAction(event -> saveSelectedNote());

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
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            updateSearchClearButton();
            updateEmptyStateText();
            refreshNotes();
        });

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

    private Parent buildEmptyState() {
        emptyStateTitleLabel.getStyleClass().add("empty-state-title");
        emptyStateDescriptionLabel.getStyleClass().add("empty-state-description");
        emptyStateDescriptionLabel.setWrapText(true);
        updateEmptyStateText();
        VBox box = new VBox(6, emptyStateTitleLabel, emptyStateDescriptionLabel);
        box.getStyleClass().add("empty-state");
        box.setPadding(new Insets(36, 18, 24, 18));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void updateSearchClearButton() {
        boolean active = isSearchActive();
        clearSearchButton.setManaged(active);
        clearSearchButton.setVisible(active);
    }

    private boolean isSearchActive() {
        return searchField.getText() != null && !searchField.getText().isBlank();
    }

    private void updateEmptyStateText() {
        if (isSearchActive()) {
            emptyStateTitleLabel.setText("没有匹配结果");
            emptyStateDescriptionLabel.setText("换个关键词试试，或点击清空回到当前筛选列表。");
            return;
        }
        emptyStateTitleLabel.setText(emptyStateTitle());
        emptyStateDescriptionLabel.setText(emptyStateDescription());
    }

    private String emptyStateTitle() {
        return switch (currentFilter) {
            case TODAY -> "今天还没有笔记";
            case RECENT_7_DAYS -> "最近 7 天还没有笔记";
            case FAVORITES -> "还没有收藏笔记";
            case ARCHIVED -> "还没有归档笔记";
            case CONFLICT_COPIES -> "目前没有冲突副本";
            case ALL -> "还没有笔记";
        };
    }

    private String emptyStateDescription() {
        return switch (currentFilter) {
            case TODAY, RECENT_7_DAYS, ALL -> "点击上方“新建”，从第一条笔记开始。";
            case FAVORITES -> "把重要内容标记为收藏，它们会集中出现在这里。";
            case ARCHIVED -> "归档后的笔记会收纳在这里，方便后续回看。";
            case CONFLICT_COPIES -> "发生同步冲突时，保留下来的冲突副本会显示在这里。";
        };
    }

    private void setCurrentFilter(NoteFilter filter) {
        currentFilter = filter == null ? NoteFilter.ALL : filter;
        updateNavigationSelection();
        updateEmptyStateText();
        updateBreadcrumb();
    }

    private void updateNavigationSelection() {
        navigationButtons.forEach((filter, button) -> {
            boolean active = filter == currentFilter;
            if (active) {
                if (!button.getStyleClass().contains("nav-button-active")) {
                    button.getStyleClass().add("nav-button-active");
                }
            } else {
                button.getStyleClass().remove("nav-button-active");
            }
        });
    }

    private void updateBreadcrumb() {
        if (selectedNote == null) {
            breadcrumbLabel.setText(filterLabel(currentFilter));
            return;
        }
        breadcrumbLabel.setText(filterLabel(currentFilter) + " / " + nullToEmpty(selectedNote.getTitle()));
    }

    private String filterLabel(NoteFilter filter) {
        return switch (filter == null ? NoteFilter.ALL : filter) {
            case ALL -> "全部笔记";
            case TODAY -> "今天";
            case RECENT_7_DAYS -> "最近 7 天";
            case FAVORITES -> "收藏";
            case ARCHIVED -> "归档";
            case CONFLICT_COPIES -> "冲突";
        };
    }

    private void configureEditorToolbar() {
        for (Node toolbarNode : contentEditor.lookupAll(".tool-bar")) {
            if (!(toolbarNode instanceof ToolBar toolBar)) {
                continue;
            }
            toolBar.getItems().forEach(this::customizeEditorToolbarNode);
            normalizeToolbarSeparators(toolBar);
        }
    }

    private void customizeEditorToolbarNode(Node node) {
        if (hasAnyStyleClass(node,
                "html-editor-cut",
                "html-editor-copy",
                "html-editor-paste",
                "html-editor-indent",
                "html-editor-outdent",
                "html-editor-hr")) {
            hideEditorToolbarNode(node);
            return;
        }
        if (hasAnyStyleClass(node, "font-menu-button")) {
            node.getStyleClass().add("editor-font-family");
            applyEditorTooltip(node, "字体");
            return;
        }
        if (hasAnyStyleClass(node, "font-size-menu-button")) {
            node.getStyleClass().add("editor-font-size");
            applyEditorTooltip(node, "字号");
            return;
        }
        if (hasAnyStyleClass(node, "html-editor-bold")) {
            applyEditorTooltip(node, "加粗");
        } else if (hasAnyStyleClass(node, "html-editor-italic")) {
            applyEditorTooltip(node, "斜体");
        } else if (hasAnyStyleClass(node, "html-editor-underline")) {
            applyEditorTooltip(node, "下划线");
        } else if (hasAnyStyleClass(node, "html-editor-strike")) {
            applyEditorTooltip(node, "删除线");
        } else if (hasAnyStyleClass(node, "html-editor-foreground")) {
            applyEditorTooltip(node, "文字颜色");
        } else if (hasAnyStyleClass(node, "html-editor-background")) {
            applyEditorTooltip(node, "高亮");
        } else if (hasAnyStyleClass(node, "html-editor-bullets")) {
            applyEditorTooltip(node, "无序列表");
        } else if (hasAnyStyleClass(node, "html-editor-numbers")) {
            applyEditorTooltip(node, "有序列表");
        } else if (hasAnyStyleClass(node, "html-editor-align-left")) {
            applyEditorTooltip(node, "左对齐");
        } else if (hasAnyStyleClass(node, "html-editor-align-center")) {
            applyEditorTooltip(node, "居中");
        } else if (hasAnyStyleClass(node, "html-editor-align-right")) {
            applyEditorTooltip(node, "右对齐");
        } else if (hasAnyStyleClass(node, "html-editor-align-justify")) {
            applyEditorTooltip(node, "两端对齐");
        }
    }

    private void normalizeToolbarSeparators(ToolBar toolBar) {
        boolean previousVisible = false;
        for (Node item : toolBar.getItems()) {
            if (!(item instanceof Separator)) {
                previousVisible = item.isManaged();
                continue;
            }
            boolean nextVisible = hasVisibleToolbarItemAfter(toolBar, item);
            boolean shouldShow = previousVisible && nextVisible;
            item.setManaged(shouldShow);
            item.setVisible(shouldShow);
            previousVisible = shouldShow;
        }
    }

    private boolean hasVisibleToolbarItemAfter(ToolBar toolBar, Node current) {
        boolean seenCurrent = false;
        for (Node item : toolBar.getItems()) {
            if (!seenCurrent) {
                if (item == current) {
                    seenCurrent = true;
                }
                continue;
            }
            if (!(item instanceof Separator) && item.isManaged()) {
                return true;
            }
        }
        return false;
    }

    private void hideEditorToolbarNode(Node node) {
        node.setManaged(false);
        node.setVisible(false);
    }

    private void applyEditorTooltip(Node node, String text) {
        if (node instanceof Control control) {
            control.setTooltip(new Tooltip(text));
        }
    }

    private boolean hasAnyStyleClass(Node node, String... styleClasses) {
        for (String styleClass : styleClasses) {
            if (node.getStyleClass().contains(styleClass)) {
                return true;
            }
        }
        return false;
    }

    private void refreshNotes() {
        try {
            Note selected = selectedNote;
            List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter);
            refreshNavigationCounts();
            clearLocalFailure();
            suppressSelectionChange = true;
            try {
                notes.setAll(loaded);
            } finally {
                suppressSelectionChange = false;
            }
            if (selected != null) {
                notes.stream()
                        .filter(note -> note.getNoteUuid().equals(selected.getNoteUuid()))
                        .findFirst()
                        .ifPresentOrElse(
                                note -> noteList.getSelectionModel().select(note),
                                () -> {
                                    if (shouldPreserveEditorDuringSearch(selected, loaded)) {
                                        selectedNote = selected;
                                        noteList.getSelectionModel().clearSelection();
                                        noteList.refresh();
                                        updateBreadcrumb();
                                        updateSyncLamp(selectedNote);
                                        return;
                                    }
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
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "刷新列表", ex);
        }
    }

    private boolean shouldPreserveEditorDuringSearch(Note selected, List<Note> loaded) {
        return selected != null
                && isSearchActive()
                && loaded.stream().noneMatch(note -> note.getNoteUuid().equals(selected.getNoteUuid()));
    }

    private void refreshNotesPreservingEditor() {
        if (selectedNote == null) {
            refreshNotes();
            return;
        }
        Note editingNote = selectedNote;
        try {
            String selectedUuid = selectedNote.getNoteUuid();
            List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter);
            refreshNavigationCounts();
            clearLocalFailure();
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
        } catch (RuntimeException ex) {
            markLocalFailure(editingNote, "刷新列表", ex);
        }
    }

    private void createNote() {
        try {
            Note note = noteRepository.createEmpty();
            searchField.clear();
            refreshNotes();
            clearLocalFailure();
            noteList.getSelectionModel().select(notes.stream()
                    .filter(item -> item.getNoteUuid().equals(note.getNoteUuid()))
                    .findFirst()
                    .orElse(note));
            titleField.requestFocus();
            titleField.selectAll();
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "新建笔记", ex);
        }
    }

    private void selectNote(Note note) {
        autosaveDelay.stop();
        if (selectedNote == null || note == null || !selectedNote.getNoteUuid().equals(note.getNoteUuid())) {
            clearLocalFailure();
        }
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
            updateBreadcrumb();
            updateEditorStatus("未选择笔记");
        } else {
            setEditorDisabled(false);
            titleField.setText(nullToEmpty(note.getTitle()));
            categoryField.setText(nullToEmpty(note.getCategoryName()));
            contentEditor.setHtmlText(toEditorHtml(note.getContent()));
            pinnedBox.setSelected(note.isPinned());
            favoriteBox.setSelected(note.isFavorite());
            archivedBox.setSelected(note.isArchived());
            updateBreadcrumb();
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
        breadcrumbLabel.setText(filterLabel(currentFilter) + " / " + titleField.getText());
        saveStatusLabel.setText("正在输入...");
        updateSyncLamp(selectedNote);
        updateWordCount();
        autosaveDelay.playFromStart();
    }

    private boolean saveSelectedNote() {
        return saveSelectedNote(true);
    }

    private boolean saveSelectedNote(boolean scheduleSync) {
        if (selectedNote == null || loadingSelection) {
            return true;
        }
        boolean archivedChanged = selectedNote.isArchived() != archivedBox.isSelected();
        selectedNote.setTitle(titleField.getText());
        selectedNote.setCategoryName(categoryField.getText());
        selectedNote.setContent(sanitizeEditorHtml(contentEditor.getHtmlText()));
        selectedNote.setPinned(pinnedBox.isSelected());
        selectedNote.setFavorite(favoriteBox.isSelected());
        selectedNote.setArchived(archivedBox.isSelected());
        try {
            noteRepository.save(selectedNote);
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "本地保存", ex);
            return false;
        }
        clearLocalFailure();
        updateEditorStatus(scheduleSync ? "已保存到本地，等待同步" : "已保存到本地");
        refreshNavigationCounts();
        if (scheduleSync) {
            scheduleAutoSync();
        }
        if (archivedChanged) {
            refreshNotes();
        } else {
            noteList.refresh();
        }
        return true;
    }

    private void deleteSelectedNote() {
        if (selectedNote == null) {
            return;
        }
        try {
            Note noteToDelete = selectedNote;
            noteRepository.softDelete(noteToDelete);
            clearLocalFailure();
            selectedNote = null;
            refreshNotes();
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "删除笔记", ex);
        }
    }

    private void syncNow(Button syncButton) {
        autoSyncDelay.stop();
        if (!saveSelectedNote(false)) {
            return;
        }
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
                showManualSyncFeedback("同步中...", "ghost-button-attention");
            }
            return;
        }
        syncInProgress = true;
        if (syncButton != null) {
            showManualSyncProgress();
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
                    refreshNotesAfterSync(summary);
                    if (manual) {
                        saveStatusLabel.setText("同步完成: 上传 " + summary.pushedCount()
                                + " / 冲突 " + summary.conflictCount()
                                + " / 拉取 " + summary.pulledCount());
                    } else if (summary.conflictCount() > 0) {
                        saveStatusLabel.setText("自动同步发现 " + summary.conflictCount() + " 个冲突");
                    }
                    if (summary.conflictCount() > 0) {
                        setSyncLamp("unsynced", "冲突");
                        if (manual) {
                            showManualSyncFeedback("发现冲突", "ghost-button-attention");
                        }
                    } else {
                        setSyncLamp("synced", "已同步");
                        if (manual) {
                            showManualSyncFeedback("已同步", "ghost-button-success");
                        }
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
                    lastSyncError = normalizeErrorMessage(ex, "同步失败");
                    saveStatusLabel.setText((manual ? "同步失败: " : "自动同步失败: ") + lastSyncError);
                    setSyncLamp("unsynced", "同步失败: " + lastSyncError);
                    if (syncButton != null) {
                        showManualSyncFeedback("同步失败", "ghost-button-danger");
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

    private void refreshNotesAfterSync(ClientSyncService.SyncSummary summary) {
        if (selectedNote != null) {
            String conflictCopyUuid = summary.conflictCopyUuids().get(selectedNote.getNoteUuid());
            if (conflictCopyUuid != null) {
                refreshNotesAndSelect(conflictCopyUuid);
                saveStatusLabel.setText("检测到同步冲突，已切换到冲突副本继续编辑");
                return;
            }
        }
        refreshNotesPreservingEditor();
    }

    private void refreshNotesAndSelect(String noteUuid) {
        try {
            List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter);
            refreshNavigationCounts();
            clearLocalFailure();
            notes.setAll(loaded);
            if (noteUuid == null || noteUuid.isBlank()) {
                if (!notes.isEmpty()) {
                    noteList.getSelectionModel().selectFirst();
                } else {
                    selectNote(null);
                }
                return;
            }
            Note target = loaded.stream()
                    .filter(note -> note.getNoteUuid().equals(noteUuid))
                    .findFirst()
                    .orElse(null);
            if (target != null) {
                noteList.getSelectionModel().select(target);
                return;
            }
            if (!notes.isEmpty()) {
                noteList.getSelectionModel().selectFirst();
            } else {
                selectNote(null);
            }
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "刷新列表", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void refreshNavigationCounts() {
        updateNavigationCount(NoteFilter.FAVORITES);
        updateNavigationCount(NoteFilter.CONFLICT_COPIES);
        updateNavigationCount(NoteFilter.ARCHIVED);
    }

    private void updateNavigationCount(NoteFilter filter) {
        Label countLabel = navigationCountLabels.get(filter);
        if (countLabel == null) {
            return;
        }
        try {
            countLabel.setText(Long.toString(noteRepository.countByFilter(filter)));
        } catch (RuntimeException ex) {
            markLocalFailure(selectedNote, "刷新统计", ex);
        }
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
        if (hasLocalFailure(note)) {
            setSyncLamp("unsynced", lastLocalFailureMessage);
            return;
        }
        if (syncInProgress) {
            setSyncLamp("syncing", "同步中");
            return;
        }
        if (syncFailureActive) {
            setSyncLamp("unsynced", lastSyncError == null || lastSyncError.isBlank() ? "同步失败" : "同步失败: " + lastSyncError);
            return;
        }
        switch (note.getSyncStatus()) {
            case SYNCED -> setSyncLamp("synced", "已同步");
            case SYNCING -> setSyncLamp("syncing", "同步中");
            case DIRTY -> setSyncLamp("unsynced", isConflictCopy(note) ? "冲突副本，待同步" : "待同步");
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
                    : "同步失败: " + lastSyncError;
        };
    }

    private void showManualSyncProgress() {
        manualSyncFeedbackDelay.stop();
        syncButton.getStyleClass().removeAll("ghost-button-success", "ghost-button-attention", "ghost-button-danger");
        syncButton.getStyleClass().add("ghost-button-attention");
        syncButton.setText("同步中...");
        syncButton.setDisable(true);
    }

    private void showManualSyncFeedback(String text, String styleClass) {
        manualSyncFeedbackDelay.stop();
        syncButton.getStyleClass().removeAll("ghost-button-success", "ghost-button-attention", "ghost-button-danger");
        if (styleClass != null && !styleClass.isBlank()) {
            syncButton.getStyleClass().add(styleClass);
        }
        syncButton.setText(text);
        syncButton.setDisable(false);
        manualSyncFeedbackDelay.playFromStart();
    }

    private void resetSyncButton() {
        if (syncInProgress) {
            showManualSyncProgress();
            return;
        }
        syncButton.getStyleClass().removeAll("ghost-button-success", "ghost-button-attention", "ghost-button-danger");
        syncButton.setText("同步");
        syncButton.setDisable(false);
    }

    private boolean hasLocalFailure(Note note) {
        if (lastLocalFailureMessage == null || lastLocalFailureMessage.isBlank()) {
            return false;
        }
        return localFailureNoteUuid == null
                || note == null
                || localFailureNoteUuid.equals(note.getNoteUuid());
    }

    private void markLocalFailure(Note note, String action, RuntimeException ex) {
        localFailureNoteUuid = note == null ? null : note.getNoteUuid();
        lastLocalFailureMessage = action + "失败: " + normalizeErrorMessage(ex, action + "失败");
        saveStatusLabel.setText(lastLocalFailureMessage);
        setSyncLamp("unsynced", lastLocalFailureMessage);
    }

    private void clearLocalFailure() {
        localFailureNoteUuid = null;
        lastLocalFailureMessage = null;
    }

    private String normalizeErrorMessage(Exception ex, String fallback) {
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

    private void updateWordCount() {
        String plainText = plainText(sanitizeEditorHtml(contentEditor.getHtmlText()));
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
        String withoutOldStyle = sanitizeEditorHtml(html);
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

    private String sanitizeEditorHtml(String html) {
        return HtmlContentSanitizer.sanitizeForStorage(html);
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

    private boolean isConflictCopy(Note note) {
        return note != null
                && note.getTitle() != null
                && note.getTitle().contains(CONFLICT_COPY_MARKER);
    }

    private class NoteCardCell extends ListCell<Note> {

        @Override
        protected void updateItem(Note note, boolean empty) {
            super.updateItem(note, empty);
            if (empty || note == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            TextFlow title = highlightText(note.getTitle(), searchField.getText(), "card-title");
            title.setMaxWidth(Double.MAX_VALUE);

            TextFlow summary = highlightText(summaryText(note), searchField.getText(), "card-summary");
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
            title.prefWidthProperty().bind(card.widthProperty().subtract(20));
            summary.prefWidthProperty().bind(card.widthProperty().subtract(20));
            setText(null);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setGraphic(card);
        }

        private String metaText(Note note) {
            String category = note.getCategoryName() == null || note.getCategoryName().isBlank()
                    ? "未分类"
                    : note.getCategoryName();
            String marker = (note.isPinned() ? "置顶 " : "") + (note.isFavorite() ? "收藏 " : "");
            return marker + category + "  " + note.getUpdateTime();
        }

        private String syncLampState(Note note) {
            return switch (note.getSyncStatus()) {
                case SYNCED -> "synced";
                case SYNCING -> "syncing";
                case DIRTY, CONFLICT, DELETE_PENDING -> "unsynced";
            };
        }

        private String syncTooltipText(Note note) {
            if (isConflictCopy(note)) {
                return "冲突副本，待同步";
            }
            return switch (note.getSyncStatus()) {
                case SYNCED -> "已同步";
                case SYNCING -> "同步中";
                case DIRTY -> "待同步";
                case CONFLICT -> "冲突";
                case DELETE_PENDING -> "待删除";
            };
        }

        private String summaryText(Note note) {
            String candidate = sanitizePreview(note.getSummary());
            if (!candidate.isBlank()) {
                return candidate;
            }
            candidate = sanitizePreview(note.getContent());
            return candidate.isBlank() ? "无正文" : candidate;
        }

        private String sanitizePreview(String value) {
            return HtmlTextExtractor.toPlainText(value).replaceAll("\\s+", " ").strip();
        }

        private TextFlow highlightText(String value, String query, String baseStyleClass) {
            TextFlow flow = new TextFlow();
            flow.getStyleClass().add(baseStyleClass + "-flow");
            String safeValue = value == null || value.isBlank() ? "" : value;
            String safeQuery = query == null ? "" : query.strip();
            if (safeValue.isBlank() || safeQuery.isBlank()) {
                flow.getChildren().add(createStyledText(safeValue, baseStyleClass));
                return flow;
            }

            String lowerValue = safeValue.toLowerCase(Locale.ROOT);
            String lowerQuery = safeQuery.toLowerCase(Locale.ROOT);
            int cursor = 0;
            while (cursor < safeValue.length()) {
                int matchIndex = lowerValue.indexOf(lowerQuery, cursor);
                if (matchIndex < 0) {
                    flow.getChildren().add(createStyledText(safeValue.substring(cursor), baseStyleClass));
                    break;
                }
                if (matchIndex > cursor) {
                    flow.getChildren().add(createStyledText(safeValue.substring(cursor, matchIndex), baseStyleClass));
                }
                Text hit = createStyledText(safeValue.substring(matchIndex, matchIndex + safeQuery.length()), baseStyleClass);
                hit.getStyleClass().add("search-hit");
                flow.getChildren().add(hit);
                cursor = matchIndex + safeQuery.length();
            }
            return flow;
        }

        private Text createStyledText(String value, String styleClass) {
            Text text = new Text(value);
            text.getStyleClass().add(styleClass);
            return text;
        }
    }
}
