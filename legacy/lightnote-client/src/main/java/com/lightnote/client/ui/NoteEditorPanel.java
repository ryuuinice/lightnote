package com.lightnote.client.ui;

import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.util.HtmlToMarkdownConverter;
import com.lightnote.client.util.HtmlTextExtractor;
import com.lightnote.client.util.MarkdownRenderer;
import com.lightnote.client.util.MarkdownTextExtractor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * 右侧笔记编辑面板，包含标题、分类下拉、Markdown 编辑器/预览、元数据控件和同步按钮。
 * <p>
 * 提供纯 UI 的加载/读取/状态更新方法，不包含数据持久化逻辑。
 * 所有用户交互通过回调通知外部。
 */
public class NoteEditorPanel {

    private final AppConfigRepository configRepository;
    private final VBox root;

    private final TextField titleField = new TextField();
    private final ComboBox<String> categoryBox = new ComboBox<>();
    private final TextArea contentEditor = new TextArea();
    private final WebView previewPane = new WebView();
    private final javafx.scene.control.SplitPane markdownSplitPane = new javafx.scene.control.SplitPane();
    private final Button editModeButton = new Button("编辑");
    private final Button splitModeButton = new Button("分屏");
    private final Button previewModeButton = new Button("预览");
    private final Button convertMarkdownButton = new Button("转为 Markdown");
    private final Button syncButton = new Button("同步");
    private final Button saveButton = new Button("保存");
    private final Button restoreButton = new Button("恢复");
    private final Button deleteButton = new Button("删除");
    private final CheckBox pinnedBox = new CheckBox("置顶");
    private final CheckBox favoriteBox = new CheckBox("收藏");
    private final CheckBox archivedBox = new CheckBox("归档");
    private final Label breadcrumbLabel = new Label("全部笔记");
    private final Label saveStatusLabel = new Label("未选择笔记");
    private final Label syncStatusLabel = new Label("●");
    private final Tooltip syncStatusTooltip = new Tooltip("未同步");
    private final Label contentFormatLabel = new Label("Markdown");
    private final Label updateTimeLabel = new Label("");
    private final Label wordCountLabel = new Label("0 字");

    private Note selectedNote;
    private EditorMode editorMode = EditorMode.SPLIT;
    private final javafx.animation.PauseTransition manualSyncFeedbackDelay =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(1400));

    // Callbacks
    private Runnable onSaveClicked;
    private Consumer<Button> onSyncClicked;
    private Runnable onDeleteClicked;
    private Runnable onRestoreClicked;
    private Runnable onContentChanged;
    private Runnable onToggleChanged;
    private Runnable onConvertMarkdown;

    public NoteEditorPanel(AppConfigRepository configRepository) {
        this.configRepository = configRepository;
        this.root = buildEditor();
        setEditorMode(editorMode);
        bindEditorEvents();
        manualSyncFeedbackDelay.setOnFinished(event -> resetSyncButton());
    }

    public Parent getRoot() {
        return root;
    }

    // ======================== 枚举 ========================

    public enum EditorMode {
        EDIT,
        SPLIT,
        PREVIEW
    }

    // ======================== 回调注册 ========================

    public void setOnSaveClicked(Runnable onSaveClicked) {
        this.onSaveClicked = onSaveClicked;
    }

    public void setOnSyncClicked(Consumer<Button> onSyncClicked) {
        this.onSyncClicked = onSyncClicked;
    }

    public void setOnDeleteClicked(Runnable onDeleteClicked) {
        this.onDeleteClicked = onDeleteClicked;
    }

    public void setOnRestoreClicked(Runnable onRestoreClicked) {
        this.onRestoreClicked = onRestoreClicked;
    }

    public void setOnContentChanged(Runnable onContentChanged) {
        this.onContentChanged = onContentChanged;
    }

    public void setOnToggleChanged(Runnable onToggleChanged) {
        this.onToggleChanged = onToggleChanged;
    }

    public void setOnConvertMarkdown(Runnable onConvertMarkdown) {
        this.onConvertMarkdown = onConvertMarkdown;
    }

    // ======================== 笔记加载/清空 ========================

    public void loadNote(Note note) {
        this.selectedNote = note;
        if (note == null) {
            clearEditor();
            return;
        }
        titleField.setText(nullToEmpty(note.getTitle()));
        applyCategoryEditorValue(note.getCategoryName());
        contentEditor.setText(editorTextForNote(note));
        contentEditor.setEditable(true);
        refreshMarkdownPreview();
        pinnedBox.setSelected(note.isPinned());
        favoriteBox.setSelected(note.isFavorite());
        archivedBox.setSelected(note.isArchived());
        updateContentFormatControls(note);
        updateTrashControls(note);
    }

    public void clearEditor() {
        this.selectedNote = null;
        titleField.clear();
        categoryBox.getSelectionModel().clearSelection();
        categoryBox.getEditor().clear();
        contentEditor.clear();
        refreshMarkdownPreview();
        pinnedBox.setSelected(false);
        favoriteBox.setSelected(false);
        archivedBox.setSelected(false);
        updateContentFormatControls(null);
        updateTrashControls(null);
    }

    public Note getSelectedNote() {
        return selectedNote;
    }

    // ======================== 编辑器内容读取 ========================

    public String getTitleText() {
        return titleField.getText();
    }

    public String getEditorContent() {
        return contentEditor.getText();
    }

    public String getCategoryText() {
        return categoryBox.getEditor().getText();
    }

    public boolean isPinnedSelected() {
        return pinnedBox.isSelected();
    }

    public boolean isFavoriteSelected() {
        return favoriteBox.isSelected();
    }

    public boolean isArchivedSelected() {
        return archivedBox.isSelected();
    }

    public EditorMode getEditorMode() {
        return editorMode;
    }

    // ======================== 编辑器状态写入 ========================

    public void setEditorDisabled(boolean disabled) {
        titleField.setDisable(disabled);
        categoryBox.setDisable(disabled);
        contentEditor.setDisable(disabled);
        previewPane.setDisable(disabled);
        editModeButton.setDisable(disabled);
        splitModeButton.setDisable(disabled);
        previewModeButton.setDisable(disabled);
        convertMarkdownButton.setDisable(disabled
                || selectedNote == null
                || selectedNote.getContentFormat() != ContentFormat.HTML);
        restoreButton.setDisable(disabled || selectedNote == null || !selectedNote.isTrashed());
        restoreButton.setVisible(!disabled && selectedNote != null && selectedNote.isTrashed());
        restoreButton.setManaged(!disabled && selectedNote != null && selectedNote.isTrashed());
        deleteButton.setDisable(disabled);
        pinnedBox.setDisable(disabled);
        favoriteBox.setDisable(disabled);
        archivedBox.setDisable(disabled);
    }

    public void setBreadcrumb(String text) {
        breadcrumbLabel.setText(text);
    }

    public void setSaveStatus(String text) {
        saveStatusLabel.setText(text);
    }

    public void setUpdateTime(String text) {
        updateTimeLabel.setText(text);
    }

    public void setWordCount(String text) {
        wordCountLabel.setText(text);
    }

    /**
     * 更新字数统计，基于当前编辑器内容计算。
     */
    public void updateWordCount() {
        String plainText = MarkdownTextExtractor.toPlainText(contentEditor.getText());
        long count = plainText.chars().filter(ch -> !Character.isWhitespace(ch)).count();
        wordCountLabel.setText(count + " 字");
    }

    /**
     * 替换编辑器内容并刷新预览（用于 Markdown 转换等场景）。
     */
    public void setEditorContent(String text) {
        contentEditor.setText(text == null ? "" : text);
        // textProperty listener will handle refreshMarkdownPreview
    }

    /**
     * 获取保存用的编辑器内容。
     */
    public String editorContentForSave() {
        return contentEditor.getText();
    }

    /**
     * 获取笔记展示用的编辑器文本（Markdown 转换后）。
     */
    public String editorTextForNote(Note note) {
        if (note == null || note.getContent() == null) {
            return "";
        }
        if (note.getContentFormat() == ContentFormat.MARKDOWN) {
            return note.getContent();
        }
        return HtmlToMarkdownConverter.convert(note.getContent());
    }

    // ======================== Markdown 渲染 ========================

    public void refreshMarkdownPreview() {
        previewPane.getEngine().loadContent(MarkdownRenderer.renderDocument(
                selectedNote == null ? "" : contentEditor.getText()));
    }

    // ======================== 控件状态 ========================

    public void updateTrashControls(Note note) {
        boolean trashed = note != null && note.isTrashed();
        deleteButton.setText(trashed ? "彻底删除" : "删除");
        restoreButton.setVisible(trashed);
        restoreButton.setManaged(trashed);
        restoreButton.setDisable(!trashed);
    }

    public void updateContentFormatControls(Note note) {
        if (note == null) {
            contentFormatLabel.setText("未选择");
            convertMarkdownButton.setVisible(false);
            convertMarkdownButton.setManaged(false);
            return;
        }
        ContentFormat format = note.getContentFormat();
        contentFormatLabel.setText(format == ContentFormat.MARKDOWN ? "Markdown" : "HTML 原文");
        boolean canConvert = format == ContentFormat.HTML;
        convertMarkdownButton.setVisible(canConvert);
        convertMarkdownButton.setManaged(canConvert);
        convertMarkdownButton.setDisable(!canConvert);
    }

    // ======================== 编辑器模式 ========================

    public void setEditorMode(EditorMode mode) {
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

    // ======================== 分类下拉框 ========================

    public void refreshCategoryOptions(List<String> categoryNames) {
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
        if (categoryName == null || categoryName.strip().isEmpty()) {
            categoryBox.getSelectionModel().clearSelection();
            categoryBox.getEditor().clear();
            return;
        }
        String normalized = categoryName.strip();
        if (!categoryBox.getItems().contains(normalized)) {
            categoryBox.getItems().add(normalized);
        }
        categoryBox.getSelectionModel().select(normalized);
        categoryBox.getEditor().setText(normalized);
    }

    // ======================== 同步状态灯 ========================

    public Button getSyncButton() {
        return syncButton;
    }

    public void setSyncLamp(String state) {
        setSyncLamp(state, syncTooltipText2(state));
    }

    public void setSyncLamp(String state, String tooltipText) {
        syncStatusLabel.getStyleClass().removeAll("sync-lamp", "sync-lamp-synced", "sync-lamp-syncing", "sync-lamp-unsynced");
        syncStatusLabel.getStyleClass().add("sync-lamp");
        syncStatusLabel.getStyleClass().add("sync-lamp-" + state);
        syncStatusTooltip.setText(tooltipText);
    }

    private String syncTooltipText2(String state) {
        return switch (state) {
            case "synced" -> "已同步";
            case "syncing" -> "同步中";
            default -> "未同步";
        };
    }

    public void setSyncStatusLabel(String lastSyncError) {
        if (lastSyncError != null && !lastSyncError.isBlank()) {
            syncStatusTooltip.setText("同步失败: " + lastSyncError);
        }
    }

    // ======================== 同步按钮反馈 ========================

    public void showManualSyncProgress() {
        syncButton.getStyleClass().removeAll("ghost-button-success", "ghost-button-attention", "ghost-button-danger");
        syncButton.getStyleClass().add("ghost-button-attention");
        syncButton.setText("同步中...");
        syncButton.setDisable(true);
    }

    public void showManualSyncFeedback(String text, String styleClass) {
        manualSyncFeedbackDelay.stop();
        syncButton.getStyleClass().removeAll("ghost-button-success", "ghost-button-attention", "ghost-button-danger");
        if (styleClass != null && !styleClass.isBlank()) {
            syncButton.getStyleClass().add(styleClass);
        }
        syncButton.setText(text);
        syncButton.setDisable(false);
        manualSyncFeedbackDelay.playFromStart();
    }

    public void resetSyncButton() {
        syncButton.getStyleClass().removeAll("ghost-button-success", "ghost-button-attention", "ghost-button-danger");
        syncButton.setText("同步");
        syncButton.setDisable(false);
    }

    // ======================== Markdown 转换确认对话框 ========================

    /**
     * 展示 Markdown 转换确认对话框，返回用户是否确认。
     */
    public boolean confirmMarkdownConversion(Note note, String convertedMarkdown) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("转换为 Markdown");
        dialog.setHeaderText("确认将当前 HTML 笔记转换为 Markdown");
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label tip = new Label("转换会保留原笔记内容，并将这条笔记标记为待同步。");
        tip.getStyleClass().add("conversion-tip");

        TextArea originalArea = new TextArea(HtmlTextExtractor.toPlainText(note.getContent()));
        originalArea.setEditable(false);
        originalArea.setWrapText(true);
        originalArea.getStyleClass().add("conversion-textarea");

        TextArea convertedArea = new TextArea(convertedMarkdown);
        convertedArea.setEditable(false);
        convertedArea.setWrapText(true);
        convertedArea.getStyleClass().add("conversion-textarea");

        WebView conversionPreview = new WebView();
        conversionPreview.getEngine().loadContent(MarkdownRenderer.renderDocument(convertedMarkdown));
        conversionPreview.getStyleClass().add("conversion-preview");

        VBox originalBox = new VBox(6, new Label("原正文文本"), originalArea);
        VBox convertedBox = new VBox(6, new Label("转换后的 Markdown"), convertedArea);
        VBox previewBox = new VBox(6, new Label("Markdown 渲染效果"), conversionPreview);
        VBox.setVgrow(originalArea, Priority.ALWAYS);
        VBox.setVgrow(convertedArea, Priority.ALWAYS);
        VBox.setVgrow(conversionPreview, Priority.ALWAYS);

        javafx.scene.control.SplitPane splitPane = new javafx.scene.control.SplitPane(originalBox, convertedBox, previewBox);
        splitPane.setDividerPositions(0.32, 0.66);
        splitPane.getStyleClass().add("conversion-split-pane");

        VBox content = new VBox(12, tip, splitPane);
        content.setPrefWidth(1080);
        content.setPrefHeight(640);
        content.getStyleClass().add("conversion-dialog-content");
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (convertedMarkdown.isBlank()) {
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
        }
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    // ======================== UI 构建 ========================

    private VBox buildEditor() {
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
        convertMarkdownButton.getStyleClass().add("ghost-button");
        convertMarkdownButton.getStyleClass().add("convert-markdown-button");
        editModeButton.setOnAction(event -> setEditorMode(EditorMode.EDIT));
        splitModeButton.setOnAction(event -> setEditorMode(EditorMode.SPLIT));
        previewModeButton.setOnAction(event -> setEditorMode(EditorMode.PREVIEW));
        convertMarkdownButton.setOnAction(event -> {
            if (onConvertMarkdown != null) {
                onConvertMarkdown.run();
            }
        });
        contentFormatLabel.getStyleClass().add("content-format-badge");
        HBox editorModes = new HBox(6, editModeButton, splitModeButton, previewModeButton, contentFormatLabel, convertMarkdownButton);
        editorModes.getStyleClass().add("editor-mode-switch");

        saveButton.getStyleClass().add("ghost-button");
        saveButton.setOnAction(event -> {
            if (onSaveClicked != null) {
                onSaveClicked.run();
            }
        });

        syncButton.getStyleClass().add("ghost-button");
        syncButton.setOnAction(event -> {
            if (onSyncClicked != null) {
                onSyncClicked.accept(syncButton);
            }
        });

        deleteButton.getStyleClass().add("danger-link-button");
        deleteButton.setOnAction(event -> {
            if (onDeleteClicked != null) {
                onDeleteClicked.run();
            }
        });
        restoreButton.getStyleClass().add("ghost-button");
        restoreButton.setOnAction(event -> {
            if (onRestoreClicked != null) {
                onRestoreClicked.run();
            }
        });
        syncStatusLabel.setTooltip(syncStatusTooltip);
        setSyncLamp("unsynced", "未同步");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox topLine = new HBox(10, breadcrumbLabel, headerSpacer, syncStatusLabel, saveButton, syncButton, restoreButton, deleteButton);
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

    // ======================== 内部事件绑定 ========================

    private void bindEditorEvents() {
        titleField.textProperty().addListener((obs, oldValue, newValue) -> fireContentChanged());
        categoryBox.getEditor().textProperty().addListener((obs, oldValue, newValue) -> fireContentChanged());
        contentEditor.textProperty().addListener((obs, oldValue, newValue) -> {
            refreshMarkdownPreview();
            fireContentChanged();
        });
        pinnedBox.selectedProperty().addListener((obs, oldValue, newValue) -> fireToggleChanged());
        favoriteBox.selectedProperty().addListener((obs, oldValue, newValue) -> fireToggleChanged());
        archivedBox.selectedProperty().addListener((obs, oldValue, newValue) -> fireToggleChanged());
    }

    private void fireContentChanged() {
        if (onContentChanged != null) {
            onContentChanged.run();
        }
    }

    private void fireToggleChanged() {
        if (onToggleChanged != null) {
            onToggleChanged.run();
        }
    }

    // ======================== 工具方法 ========================

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public void requestTitleFocus() {
        titleField.requestFocus();
        titleField.selectAll();
    }
}
