package com.lightnote.client.ui;

import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.repository.NoteRepository.CategorySummary;
import com.lightnote.client.sync.ClientSyncService;
import com.lightnote.client.util.AppLogger;
import com.lightnote.client.util.HtmlTextExtractor;
import com.lightnote.client.util.MarkdownRenderer;
import com.lightnote.client.util.MarkdownTextExtractor;
import javafx.application.Platform;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Window;
import javafx.util.Duration;

public class MainView {

    private static final Logger LOGGER = AppLogger.logger(MainView.class);

    private static final String NOTE_EDITOR_DIVIDER_KEY = "note_editor_divider_position";
    private static final String CONFLICT_COPY_MARKER = "冲突副本";
    private static final String UNCATEGORIZED_LABEL = "未分类";

    private enum EditorMode {
        EDIT,
        SPLIT,
        PREVIEW
    }

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
    private final ComboBox<String> categoryBox = new ComboBox<>();
    private final TextArea contentEditor = new TextArea();
    private final WebView previewPane = new WebView();
    private final SplitPane markdownSplitPane = new SplitPane();
    private final Button editModeButton = new Button("编辑");
    private final Button splitModeButton = new Button("分屏");
    private final Button previewModeButton = new Button("预览");
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
    private final Map<String, Button> categoryButtons = new HashMap<>();
    private final Map<String, Long> categoryCounts = new HashMap<>();
    private final Label emptyStateTitleLabel = new Label();
    private final Label emptyStateDescriptionLabel = new Label();
    private final VBox categoryListBox = new VBox(6);
    private final Button addCategoryButton = new Button("+ 分类");
    private final Button renameCategoryButton = new Button("重命名");
    private final Button deleteCategoryButton = new Button("删除");
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
    private String currentCategoryName;
    private EditorMode editorMode = EditorMode.SPLIT;

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

        Button allNotes = navButton("全部笔记", NoteFilter.ALL, true);
        Button today = navButton("今天", NoteFilter.TODAY, true);
        Button week = navButton("最近 7 天", NoteFilter.RECENT_7_DAYS, true);
        Button favorites = navButton("收藏", NoteFilter.FAVORITES, true);
        Button conflicts = navButton("冲突", NoteFilter.CONFLICT_COPIES, true);
        Button archive = navButton("归档", NoteFilter.ARCHIVED, true);

        Label categoryTitle = new Label("分类");
        categoryTitle.getStyleClass().add("section-label");
        categoryListBox.getStyleClass().add("category-list");
        configureCategoryActions();
        VBox categoryActions = new VBox(6, addCategoryButton, renameCategoryButton, deleteCategoryButton);
        categoryActions.getStyleClass().add("category-actions");

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
                categoryActions,
                categoryListBox,
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
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setMinWidth(0);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        HBox row = countLabel == null
                ? new HBox(8, titleLabel)
                : new HBox(8, titleLabel, countLabel);
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

        categoryBox.setEditable(true);
        categoryBox.setPromptText("分类");
        categoryBox.getStyleClass().add("category-field");
        categoryBox.setVisibleRowCount(10);

        contentEditor.setPromptText("用 Markdown 记录想法、命令、清单或代码片段...");
        contentEditor.setWrapText(true);
        contentEditor.getStyleClass().add("markdown-editor");
        contentEditor.setPrefHeight(620);
        previewPane.getStyleClass().add("markdown-preview");
        previewPane.setContextMenuEnabled(false);
        markdownSplitPane.getItems().setAll(contentEditor, previewPane);
        markdownSplitPane.setDividerPositions(0.52);
        markdownSplitPane.getStyleClass().add("markdown-split-pane");
        VBox.setVgrow(markdownSplitPane, Priority.ALWAYS);

        editModeButton.getStyleClass().add("mode-button");
        splitModeButton.getStyleClass().add("mode-button");
        previewModeButton.getStyleClass().add("mode-button");
        editModeButton.setOnAction(event -> setEditorMode(EditorMode.EDIT));
        splitModeButton.setOnAction(event -> setEditorMode(EditorMode.SPLIT));
        previewModeButton.setOnAction(event -> setEditorMode(EditorMode.PREVIEW));
        HBox editorModes = new HBox(6, editModeButton, splitModeButton, previewModeButton);
        editorModes.getStyleClass().add("editor-mode-switch");
        setEditorMode(editorMode);

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

        HBox meta = new HBox(10, categoryBox, pinnedBox, favoriteBox, archivedBox);
        meta.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(categoryBox, Priority.ALWAYS);
        meta.getStyleClass().add("document-meta");

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(14, saveStatusLabel, updateTimeLabel, footerSpacer, wordCountLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("document-footer");

        VBox document = new VBox(12, topLine, titleField, meta, editorModes, markdownSplitPane, footer);
        document.setMaxWidth(980);
        document.getStyleClass().add("document-surface");
        VBox.setVgrow(markdownSplitPane, Priority.ALWAYS);

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
        categoryBox.getEditor().textProperty().addListener((obs, oldValue, newValue) -> scheduleAutosave());
        contentEditor.textProperty().addListener((obs, oldValue, newValue) -> {
            refreshMarkdownPreview();
            scheduleAutosave();
        });
        pinnedBox.selectedProperty().addListener((obs, oldValue, newValue) -> saveToggleChange());
        favoriteBox.selectedProperty().addListener((obs, oldValue, newValue) -> saveToggleChange());
        archivedBox.selectedProperty().addListener((obs, oldValue, newValue) -> saveToggleChange());
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
            emptyStateDescriptionLabel.setText("换个关键词试试，或点击清空回到" + contextLabel() + "。");
            return;
        }
        emptyStateTitleLabel.setText(emptyStateTitle());
        emptyStateDescriptionLabel.setText(emptyStateDescription());
    }

    private String emptyStateTitle() {
        if (isCategoryFilterActive()) {
            return categoryDisplayName(currentCategoryName) + "下还没有笔记";
        }
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
        if (isCategoryFilterActive()) {
            return "你可以在当前分类下新建笔记，或切回全部笔记查看更多内容。";
        }
        return switch (currentFilter) {
            case TODAY, RECENT_7_DAYS, ALL -> "点击上方“新建”，从第一条笔记开始。";
            case FAVORITES -> "把重要内容标记为收藏，它们会集中出现在这里。";
            case ARCHIVED -> "归档后的笔记会收纳在这里，方便后续回看。";
            case CONFLICT_COPIES -> "发生同步冲突时，保留下来的冲突副本会显示在这里。";
        };
    }

    private void setCurrentFilter(NoteFilter filter) {
        currentFilter = filter == null ? NoteFilter.ALL : filter;
        currentCategoryName = null;
        updateNavigationSelection();
        updateCategorySelection();
        updateEmptyStateText();
        updateBreadcrumb();
    }

    private void setCurrentCategory(String categoryName) {
        currentCategoryName = normalizeCategoryName(categoryName);
        currentFilter = NoteFilter.ALL;
        updateNavigationSelection();
        updateCategorySelection();
        updateEmptyStateText();
        updateBreadcrumb();
    }

    private void updateNavigationSelection() {
        navigationButtons.forEach((filter, button) -> {
            boolean active = !isCategoryFilterActive() && filter == currentFilter;
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
        String context = contextLabel();
        if (selectedNote == null) {
            breadcrumbLabel.setText(context);
            return;
        }
        breadcrumbLabel.setText(context + " / " + nullToEmpty(selectedNote.getTitle()));
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

    private String contextLabel() {
        return isCategoryFilterActive()
                ? "分类 / " + categoryDisplayName(currentCategoryName)
                : filterLabel(currentFilter);
    }

    private boolean isCategoryFilterActive() {
        return currentCategoryName != null;
    }

    private boolean categoryMatchesCurrentFilter(Note note) {
        if (!isCategoryFilterActive()) {
            return true;
        }
        return nullToEmpty(normalizeCategoryName(note == null ? null : note.getCategoryName()))
                .equals(nullToEmpty(currentCategoryName));
    }

    private String normalizeCategoryName(String categoryName) {
        if (categoryName == null) {
            return null;
        }
        String normalized = categoryName.strip();
        return normalized.isEmpty() ? "" : normalized;
    }

    private String categoryDisplayName(String categoryName) {
        String normalized = normalizeCategoryName(categoryName);
        return normalized == null || normalized.isEmpty() ? UNCATEGORIZED_LABEL : normalized;
    }

    private void updateCategorySelection() {
        categoryButtons.forEach((categoryName, button) -> {
            boolean active = isCategoryFilterActive() && categoryName.equals(currentCategoryName);
            if (active) {
                if (!button.getStyleClass().contains("nav-button-active")) {
                    button.getStyleClass().add("nav-button-active");
                }
            } else {
                button.getStyleClass().remove("nav-button-active");
            }
        });
        updateCategoryActionState();
    }

    private void configureCategoryActions() {
        addCategoryButton.getStyleClass().add("secondary-nav-button");
        renameCategoryButton.getStyleClass().add("secondary-nav-button");
        deleteCategoryButton.getStyleClass().add("secondary-nav-button");
        addCategoryButton.setMaxWidth(Double.MAX_VALUE);
        renameCategoryButton.setMaxWidth(Double.MAX_VALUE);
        deleteCategoryButton.setMaxWidth(Double.MAX_VALUE);
        addCategoryButton.setOnAction(event -> createCategory());
        renameCategoryButton.setOnAction(event -> renameCurrentCategory());
        deleteCategoryButton.setOnAction(event -> deleteCurrentCategoryIfEmpty());
        updateCategoryActionState();
    }

    private void updateCategoryActionState() {
        boolean hasSelectedCategory = isCategoryFilterActive()
                && currentCategoryName != null
                && !currentCategoryName.isBlank();
        renameCategoryButton.setDisable(!hasSelectedCategory);
        long count = hasSelectedCategory ? categoryCounts.getOrDefault(currentCategoryName, 0L) : -1L;
        deleteCategoryButton.setDisable(!hasSelectedCategory || count > 0);
    }

    private void createCategory() {
        TextInputDialog dialog = createCategoryInputDialog("新建分类", "", "输入分类名称");
        dialog.showAndWait()
                .map(this::normalizeCategoryName)
                .filter(name -> name != null && !name.isBlank())
                .ifPresent(categoryName -> {
                    configRepository.addCategory(categoryName);
                    setCurrentCategory(categoryName);
                    refreshNotes();
                });
    }

    private void renameCurrentCategory() {
        if (!isCategoryFilterActive() || currentCategoryName == null || currentCategoryName.isBlank()) {
            return;
        }
        TextInputDialog dialog = createCategoryInputDialog("重命名分类", currentCategoryName, "输入新的分类名称");
        dialog.showAndWait()
                .map(this::normalizeCategoryName)
                .filter(name -> name != null && !name.isBlank())
                .ifPresent(nextName -> {
                    noteRepository.renameCategory(currentCategoryName, nextName);
                    configRepository.renameCategory(currentCategoryName, nextName);
                    if (selectedNote != null && currentCategoryName.equals(normalizeCategoryName(selectedNote.getCategoryName()))) {
                        selectedNote.setCategoryName(nextName);
                    }
                    setCurrentCategory(nextName);
                    refreshNotes();
                });
    }

    private void deleteCurrentCategoryIfEmpty() {
        if (!isCategoryFilterActive() || currentCategoryName == null || currentCategoryName.isBlank()) {
            return;
        }
        if (categoryCounts.getOrDefault(currentCategoryName, 0L) > 0) {
            saveStatusLabel.setText("请先清空这个分类下的笔记，再删除分类");
            return;
        }
        configRepository.removeCategory(currentCategoryName);
        setCurrentFilter(NoteFilter.ALL);
        refreshNotes();
        saveStatusLabel.setText("已删除空分类");
    }

    private TextInputDialog createCategoryInputDialog(String title, String initialValue, String promptText) {
        TextInputDialog dialog = new TextInputDialog(initialValue);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(promptText);
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog;
    }

    private void refreshNotes() {
        try {
            Note selected = selectedNote;
            List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter, currentCategoryName);
            refreshNavigationCounts();
            refreshCategoryList();
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
            List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter, currentCategoryName);
            refreshNavigationCounts();
            refreshCategoryList();
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
            selectedNote.setCategoryName(refreshedNote.getCategoryName());
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
            if (isCategoryFilterActive()) {
                note.setCategoryName(currentCategoryName);
                noteRepository.save(note);
            }
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
        try {
            if (note == null) {
                titleField.clear();
                categoryBox.getSelectionModel().clearSelection();
                categoryBox.getEditor().clear();
                contentEditor.clear();
                contentEditor.setEditable(true);
                refreshMarkdownPreview();
                pinnedBox.setSelected(false);
                favoriteBox.setSelected(false);
                archivedBox.setSelected(false);
                setEditorDisabled(true);
                updateBreadcrumb();
                updateEditorStatus("未选择笔记");
            } else {
                setEditorDisabled(false);
                titleField.setText(nullToEmpty(note.getTitle()));
                applyCategoryEditorValue(note.getCategoryName());
                contentEditor.setText(editorTextForNote(note));
                contentEditor.setEditable(true);
                refreshMarkdownPreview();
                pinnedBox.setSelected(note.isPinned());
                favoriteBox.setSelected(note.isFavorite());
                archivedBox.setSelected(note.isArchived());
                updateBreadcrumb();
                updateEditorStatus("已打开");
            }
        } finally {
            loadingSelection = false;
        }
    }

    private void setEditorDisabled(boolean disabled) {
        titleField.setDisable(disabled);
        categoryBox.setDisable(disabled);
        contentEditor.setDisable(disabled);
        previewPane.setDisable(disabled);
        editModeButton.setDisable(disabled);
        splitModeButton.setDisable(disabled);
        previewModeButton.setDisable(disabled);
        pinnedBox.setDisable(disabled);
        favoriteBox.setDisable(disabled);
        archivedBox.setDisable(disabled);
    }

    private void scheduleAutosave() {
        if (loadingSelection || selectedNote == null) {
            return;
        }
        breadcrumbLabel.setText(contextLabel() + " / " + titleField.getText());
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
        boolean titleChanged = !nullToEmpty(selectedNote.getTitle()).equals(nullToEmpty(titleField.getText()));
        boolean categoryChanged = !nullToEmpty(normalizeCategoryName(selectedNote.getCategoryName()))
                .equals(nullToEmpty(normalizeCategoryName(categoryBox.getEditor().getText())));
        String originalContent = selectedNote.getContent();
        String displayedContent = editorTextForNote(selectedNote);
        String editorContent = editorContentForSave(selectedNote);
        boolean convertHtmlToMarkdown = selectedNote.getContentFormat() == ContentFormat.HTML
                && !nullToEmpty(displayedContent).equals(nullToEmpty(editorContent));
        boolean contentChanged = selectedNote.getContentFormat() == ContentFormat.HTML
                ? convertHtmlToMarkdown
                : !nullToEmpty(originalContent).equals(nullToEmpty(editorContent));
        boolean pinnedChanged = selectedNote.isPinned() != pinnedBox.isSelected();
        boolean favoriteChanged = selectedNote.isFavorite() != favoriteBox.isSelected();
        boolean archivedChanged = selectedNote.isArchived() != archivedBox.isSelected();
        selectedNote.setTitle(titleField.getText());
        selectedNote.setCategoryName(categoryBox.getEditor().getText());
        selectedNote.setContent(convertHtmlToMarkdown || selectedNote.getContentFormat() == ContentFormat.MARKDOWN
                ? editorContent
                : originalContent);
        if (convertHtmlToMarkdown) {
            selectedNote.setContentFormat(ContentFormat.MARKDOWN);
        }
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
        refreshCategoryList();
        if (scheduleSync) {
            scheduleAutoSync();
        }
        boolean needsFullReload = archivedChanged
                || categoryChanged
                || (isCategoryFilterActive() && !categoryMatchesCurrentFilter(selectedNote))
                || (favoriteChanged && currentFilter == NoteFilter.FAVORITES)
                || (contentChanged && isSearchActive())
                || (titleChanged && isSearchActive());
        boolean needsCardRefresh = titleChanged || categoryChanged || pinnedChanged || favoriteChanged || archivedChanged;
        if (needsFullReload) {
            refreshNotes();
        } else if (needsCardRefresh) {
            refreshCategoryOptions();
            noteList.refresh();
        }
        return true;
    }

    private void saveToggleChange() {
        if (loadingSelection || selectedNote == null) {
            return;
        }
        autosaveDelay.stop();
        saveSelectedNote();
    }

    private String editorContentForSave(Note note) {
        return contentEditor.getText();
    }

    private String editorTextForNote(Note note) {
        if (note == null || note.getContent() == null) {
            return "";
        }
        if (note.getContentFormat() == ContentFormat.MARKDOWN) {
            return note.getContent();
        }
        return HtmlTextExtractor.toPlainText(note.getContent());
    }

    private void refreshMarkdownPreview() {
        if (selectedNote == null) {
            previewPane.getEngine().loadContent(MarkdownRenderer.renderDocument(""));
            return;
        }
        previewPane.getEngine().loadContent(MarkdownRenderer.renderDocument(contentEditor.getText()));
    }

    private void setEditorMode(EditorMode mode) {
        editorMode = mode == null ? EditorMode.SPLIT : mode;
        markdownSplitPane.getItems().clear();
        switch (editorMode) {
            case EDIT -> markdownSplitPane.getItems().setAll(contentEditor);
            case PREVIEW -> markdownSplitPane.getItems().setAll(previewPane);
            case SPLIT -> {
                markdownSplitPane.getItems().setAll(contentEditor, previewPane);
                markdownSplitPane.setDividerPositions(0.52);
            }
        }
        updateEditorModeButtons();
    }

    private void updateEditorModeButtons() {
        editModeButton.getStyleClass().remove("mode-button-active");
        splitModeButton.getStyleClass().remove("mode-button-active");
        previewModeButton.getStyleClass().remove("mode-button-active");
        switch (editorMode) {
            case EDIT -> editModeButton.getStyleClass().add("mode-button-active");
            case SPLIT -> splitModeButton.getStyleClass().add("mode-button-active");
            case PREVIEW -> previewModeButton.getStyleClass().add("mode-button-active");
        }
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
                    LOGGER.log(Level.WARNING, "同步失败", ex);
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
            List<Note> loaded = noteRepository.listByFilter(searchField.getText(), currentFilter, currentCategoryName);
            refreshNavigationCounts();
            refreshCategoryList();
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
        updateNavigationCount(NoteFilter.ALL);
        updateNavigationCount(NoteFilter.TODAY);
        updateNavigationCount(NoteFilter.RECENT_7_DAYS);
        updateNavigationCount(NoteFilter.FAVORITES);
        updateNavigationCount(NoteFilter.CONFLICT_COPIES);
        updateNavigationCount(NoteFilter.ARCHIVED);
    }

    private void refreshCategoryList() {
        List<CategorySummary> summaries = noteRepository.listCategorySummaries();
        categoryCounts.clear();
        for (CategorySummary summary : summaries) {
            categoryCounts.put(normalizeCategoryName(summary.name()), summary.count());
        }
        List<String> categoryNames = new java.util.ArrayList<>(configRepository.categoryCatalog());
        for (CategorySummary summary : summaries) {
            String categoryName = normalizeCategoryName(summary.name());
            if (!categoryNames.contains(categoryName)) {
                categoryNames.add(categoryName);
            }
        }
        categoryNames.sort((left, right) -> {
            boolean leftUncategorized = left != null && left.isEmpty();
            boolean rightUncategorized = right != null && right.isEmpty();
            if (leftUncategorized != rightUncategorized) {
                return leftUncategorized ? 1 : -1;
            }
            long leftCount = categoryCounts.getOrDefault(left, 0L);
            long rightCount = categoryCounts.getOrDefault(right, 0L);
            int countCompare = Long.compare(rightCount, leftCount);
            if (countCompare != 0) {
                return countCompare;
            }
            return categoryDisplayName(left).compareToIgnoreCase(categoryDisplayName(right));
        });

        categoryButtons.clear();
        refreshCategoryOptions(categoryNames);
        if (categoryNames.isEmpty()) {
            Label placeholder = new Label("还没有分类");
            placeholder.getStyleClass().add("category-placeholder");
            categoryListBox.getChildren().setAll(placeholder);
            updateCategoryActionState();
            return;
        }

        List<Node> items = new java.util.ArrayList<>();
        for (String categoryName : categoryNames) {
            Label countLabel = new Label(Long.toString(categoryCounts.getOrDefault(categoryName, 0L)));
            countLabel.getStyleClass().add("nav-count-badge");
            Button button = new Button();
            button.setMaxWidth(Double.MAX_VALUE);
            button.getStyleClass().addAll("nav-button", "category-button");
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.setGraphic(buildNavButtonGraphic(categoryDisplayName(categoryName), countLabel));
            button.setOnAction(event -> {
                setCurrentCategory(categoryName);
                refreshNotes();
            });
            categoryButtons.put(categoryName, button);
            items.add(button);
        }
        categoryListBox.getChildren().setAll(items);
        updateCategorySelection();
    }

    private void refreshCategoryOptions() {
        refreshCategoryOptions(configRepository.categoryCatalog());
    }

    private void refreshCategoryOptions(List<String> categoryNames) {
        String editorValue = categoryBox.getEditor().getText();
        categoryBox.getItems().setAll(categoryNames.stream()
                .filter(name -> name != null && !name.isEmpty())
                .toList());
        if (selectedNote != null) {
            applyCategoryEditorValue(selectedNote.getCategoryName());
            return;
        }
        categoryBox.getSelectionModel().clearSelection();
        categoryBox.getEditor().setText(editorValue == null ? "" : editorValue);
    }

    private void applyCategoryEditorValue(String categoryName) {
        String normalized = normalizeCategoryName(categoryName);
        if (normalized == null || normalized.isEmpty()) {
            categoryBox.getSelectionModel().clearSelection();
            categoryBox.getEditor().clear();
            return;
        }
        if (!categoryBox.getItems().contains(normalized)) {
            categoryBox.getItems().add(normalized);
        }
        categoryBox.getSelectionModel().select(normalized);
        categoryBox.getEditor().setText(normalized);
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
        LOGGER.log(Level.WARNING, action + "失败", ex);
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
        String plainText = MarkdownTextExtractor.toPlainText(contentEditor.getText());
        long count = plainText.chars().filter(ch -> !Character.isWhitespace(ch)).count();
        wordCountLabel.setText(count + " 字");
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

    private String displayTitle(Note note) {
        String title = note == null || note.getTitle() == null ? "" : note.getTitle().strip();
        if (!isConflictCopy(note)) {
            return title;
        }
        return title.replaceFirst("（冲突副本\\s+[^）]+）$", "").strip();
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

            TextFlow title = highlightText(displayTitle(note), searchField.getText(), "card-title");
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
            String marker = (isConflictCopy(note) ? "冲突副本 " : "")
                    + (note.isPinned() ? "置顶 " : "")
                    + (note.isFavorite() ? "收藏 " : "");
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
            candidate = sanitizePreview(note.getContent(), note.getContentFormat());
            return candidate.isBlank() ? "无正文" : candidate;
        }

        private String sanitizePreview(String value) {
            return HtmlTextExtractor.toPlainText(value).replaceAll("\\s+", " ").strip();
        }

        private String sanitizePreview(String value, ContentFormat contentFormat) {
            if (contentFormat == ContentFormat.MARKDOWN) {
                return MarkdownTextExtractor.toPlainText(value).replaceAll("\\s+", " ").strip();
            }
            return sanitizePreview(value);
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
