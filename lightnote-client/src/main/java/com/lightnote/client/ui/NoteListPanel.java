package com.lightnote.client.ui;

import com.lightnote.client.model.Note;
import com.lightnote.client.repository.NoteRepository;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 中间笔记列表面板，包含搜索框、新建按钮、笔记卡片列表和空状态提示。
 * <p>
 * 不直接操作编辑器或侧边栏，通过回调通知外部状态变化。
 */
public class NoteListPanel {

    private final NoteRepository noteRepository;
    private final VBox root;
    private final ObservableList<Note> notes = FXCollections.observableArrayList();
    private final ListView<Note> noteList = new ListView<>(notes);
    private final TextField searchField = new TextField();
    private final Button clearSearchButton = new Button("清空");
    private final Label emptyStateTitleLabel = new Label();
    private final Label emptyStateDescriptionLabel = new Label();

    private Consumer<Note> onNoteSelected;
    private Runnable onNewNoteClicked;
    private Runnable onSearchChanged;
    private boolean suppressSelectionChange;

    public NoteListPanel(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
        this.root = buildPanel();
        bindEvents();
        bindSelectionListener();
    }

    public Parent getRoot() {
        return root;
    }

    // ======================== 回调注册 ========================

    public void setOnNoteSelected(Consumer<Note> onNoteSelected) {
        this.onNoteSelected = onNoteSelected;
    }

    public void setOnNewNoteClicked(Runnable onNewNoteClicked) {
        this.onNewNoteClicked = onNewNoteClicked;
    }

    public void setOnSearchChanged(Runnable onSearchChanged) {
        this.onSearchChanged = onSearchChanged;
    }

    // ======================== 状态查询 ========================

    public ObservableList<Note> getNotes() {
        return notes;
    }

    public ListView<Note> getNoteList() {
        return noteList;
    }

    public String getSearchText() {
        return searchField.getText();
    }

    public boolean isSearchActive() {
        return searchField.getText() != null && !searchField.getText().isBlank();
    }

    public Note getSelectedItem() {
        return noteList.getSelectionModel().getSelectedItem();
    }

    // ======================== 列表操作 ========================

    /**
     * 替换列表数据，抑制选择变更回调。返回新选中的笔记，由外部决定如何处理。
     */
    public Note replaceAll(List<Note> loaded, String selectUuid) {
        suppressSelectionChange = true;
        try {
            notes.setAll(loaded);
            if (selectUuid != null && !selectUuid.isBlank()) {
                for (Note note : notes) {
                    if (note.getNoteUuid().equals(selectUuid)) {
                        noteList.getSelectionModel().select(note);
                        return noteList.getSelectionModel().getSelectedItem();
                    }
                }
            }
            if (!notes.isEmpty()) {
                noteList.getSelectionModel().select(0);
                return noteList.getSelectionModel().getSelectedItem();
            }
            noteList.getSelectionModel().clearSelection();
            return null;
        } finally {
            suppressSelectionChange = false;
        }
    }

    /**
     * 替换列表数据，不改变当前选中状态（用于编辑器保留场景）。
     */
    public void replaceAllPreservingSelection(List<Note> loaded) {
        suppressSelectionChange = true;
        try {
            notes.setAll(loaded);
        } finally {
            suppressSelectionChange = false;
        }
    }

    /**
     * 选中指定笔记（抑制选择变更回调）。
     */
    public void select(Note note) {
        suppressSelectionChange = true;
        try {
            if (note != null) {
                noteList.getSelectionModel().select(note);
            } else {
                noteList.getSelectionModel().clearSelection();
            }
        } finally {
            suppressSelectionChange = false;
        }
    }

    /**
     * 选中列表中匹配 UUID 的笔记（用于刷新后恢复选中状态）。
     */
    public void selectByUuid(String noteUuid) {
        suppressSelectionChange = true;
        try {
            if (noteUuid == null || noteUuid.isBlank()) {
                if (!notes.isEmpty()) {
                    noteList.getSelectionModel().select(0);
                } else {
                    noteList.getSelectionModel().clearSelection();
                }
                return;
            }
            for (Note note : notes) {
                if (note.getNoteUuid().equals(noteUuid)) {
                    noteList.getSelectionModel().select(note);
                    return;
                }
            }
            if (!notes.isEmpty()) {
                noteList.getSelectionModel().select(0);
            } else {
                noteList.getSelectionModel().clearSelection();
            }
        } finally {
            suppressSelectionChange = false;
        }
    }

    public void clearSelection() {
        suppressSelectionChange = true;
        try {
            noteList.getSelectionModel().clearSelection();
        } finally {
            suppressSelectionChange = false;
        }
    }

    public void refreshList() {
        noteList.refresh();
    }

    public void clearSearch() {
        searchField.clear();
    }

    // ======================== 空状态 ========================

    public void updateEmptyStateText(String title, String description) {
        emptyStateTitleLabel.setText(title);
        emptyStateDescriptionLabel.setText(description);
    }

    // ======================== UI 构建 ========================

    private VBox buildPanel() {
        searchField.setPromptText("搜索标题、正文或摘要");
        searchField.getStyleClass().add("search-field");
        clearSearchButton.getStyleClass().add("search-clear-button");
        clearSearchButton.setManaged(false);
        clearSearchButton.setVisible(false);
        clearSearchButton.setOnAction(event -> {
            searchField.clear();
            if (onSearchChanged != null) {
                onSearchChanged.run();
            }
        });

        HBox searchRow = new HBox(8, searchField, clearSearchButton);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        Button newButton = new Button("+ 新建");
        newButton.getStyleClass().add("primary-button");
        newButton.setMaxWidth(Double.MAX_VALUE);
        newButton.setOnAction(event -> {
            if (onNewNoteClicked != null) {
                onNewNoteClicked.run();
            }
        });

        VBox quickActions = new VBox(10, searchRow, newButton);
        quickActions.getStyleClass().add("quick-actions");

        noteList.setCellFactory(list -> new NoteCardCell(searchField::getText));
        noteList.getStyleClass().add("note-list");
        noteList.setPlaceholder(buildEmptyState());

        VBox workspace = new VBox(12, quickActions, noteList);
        workspace.setPrefWidth(320);
        workspace.setMinWidth(240);
        workspace.setPadding(new Insets(16, 16, 12, 16));
        workspace.getStyleClass().add("workspace-panel");
        VBox.setVgrow(noteList, Priority.ALWAYS);
        return workspace;
    }

    private VBox buildEmptyState() {
        emptyStateTitleLabel.getStyleClass().add("empty-state-title");
        emptyStateDescriptionLabel.getStyleClass().add("empty-state-description");
        emptyStateDescriptionLabel.setWrapText(true);
        updateEmptyStateText("还没有笔记", "点击上方「新建」，从第一条笔记开始。");
        VBox box = new VBox(6, emptyStateTitleLabel, emptyStateDescriptionLabel);
        box.getStyleClass().add("empty-state");
        box.setPadding(new Insets(36, 18, 24, 18));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void bindEvents() {
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            updateSearchClearButton();
            if (onSearchChanged != null) {
                onSearchChanged.run();
            }
        });
    }

    private void bindSelectionListener() {
        noteList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (!suppressSelectionChange && newValue != null && onNoteSelected != null) {
                onNoteSelected.accept(newValue);
            }
        });
    }

    private void updateSearchClearButton() {
        boolean active = isSearchActive();
        clearSearchButton.setManaged(active);
        clearSearchButton.setVisible(active);
    }
}
