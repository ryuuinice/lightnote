package com.lightnote.client.ui;

import com.lightnote.client.model.ContentFormat;
import com.lightnote.client.model.Note;
import com.lightnote.client.util.HtmlTextExtractor;
import com.lightnote.client.util.MarkdownTextExtractor;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 笔记卡片列表项，负责在 ListView 中渲染单条笔记的标题、摘要、元信息和同步状态。
 * <p>
 * 支持搜索关键词高亮显示。
 */
class NoteCardCell extends ListCell<Note> {

    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double NOTE_CARD_CELL_HEIGHT = 124;
    private static final double NOTE_CARD_HEIGHT = 116;
    private static final double NOTE_CARD_TITLE_HEIGHT = 38;
    private static final double NOTE_CARD_SUMMARY_HEIGHT = 34;
    static final String CONFLICT_COPY_MARKER = "冲突副本";

    private final Supplier<String> searchQuerySupplier;

    NoteCardCell(Supplier<String> searchQuerySupplier) {
        this.searchQuerySupplier = searchQuerySupplier;
    }

    @Override
    protected void updateItem(Note note, boolean empty) {
        super.updateItem(note, empty);
        if (empty || note == null) {
            setText(null);
            setGraphic(null);
            setPadding(Insets.EMPTY);
            setMinHeight(Region.USE_COMPUTED_SIZE);
            setPrefHeight(Region.USE_COMPUTED_SIZE);
            setMaxHeight(Region.USE_COMPUTED_SIZE);
            return;
        }

        String searchQuery = searchQuerySupplier.get();

        TextFlow title = highlightText(displayTitle(note), searchQuery, "card-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setMinHeight(NOTE_CARD_TITLE_HEIGHT);
        title.setPrefHeight(NOTE_CARD_TITLE_HEIGHT);
        title.setMaxHeight(NOTE_CARD_TITLE_HEIGHT);
        applyFixedHeightClip(title, NOTE_CARD_TITLE_HEIGHT);

        TextFlow summary = highlightText(summaryText(note), searchQuery, "card-summary");
        summary.setMaxWidth(Double.MAX_VALUE);
        summary.setMinHeight(NOTE_CARD_SUMMARY_HEIGHT);
        summary.setPrefHeight(NOTE_CARD_SUMMARY_HEIGHT);
        summary.setMaxHeight(NOTE_CARD_SUMMARY_HEIGHT);
        applyFixedHeightClip(summary, NOTE_CARD_SUMMARY_HEIGHT);

        Label meta = new Label(metaText(note));
        meta.getStyleClass().add("card-meta");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label status = new Label("●");
        status.getStyleClass().add("card-sync-lamp");
        status.getStyleClass().add("card-sync-lamp-" + syncLampState(note));
        status.setTooltip(new Tooltip(syncTooltipText(note)));
        HBox bottom = new HBox(8, meta, spacer, status);
        bottom.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox card = new VBox(6, title, summary, bottom);
        card.getStyleClass().add("note-card");
        card.setPadding(new Insets(10));
        card.setFillWidth(true);
        card.setMinHeight(NOTE_CARD_HEIGHT);
        card.setPrefHeight(NOTE_CARD_HEIGHT);
        card.setMaxHeight(NOTE_CARD_HEIGHT);
        card.prefWidthProperty().bind(widthProperty().subtract(18));
        card.maxWidthProperty().bind(widthProperty().subtract(18));
        title.prefWidthProperty().bind(card.widthProperty().subtract(20));
        summary.prefWidthProperty().bind(card.widthProperty().subtract(20));
        setText(null);
        setPadding(new Insets(0, 0, 8, 0));
        setMinHeight(NOTE_CARD_CELL_HEIGHT);
        setPrefHeight(NOTE_CARD_CELL_HEIGHT);
        setMaxHeight(NOTE_CARD_CELL_HEIGHT);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setGraphic(card);
    }

    private String metaText(Note note) {
        String category = note.getCategoryName() == null || note.getCategoryName().isBlank()
                ? "未分类"
                : note.getCategoryName();
        String marker = (note.isTrashed() ? "回收站 " : "")
                + (isConflictCopy(note) ? "冲突副本 " : "")
                + (note.isPinned() ? "置顶 " : "")
                + (note.isFavorite() ? "收藏 " : "");
        return marker + category + "  " + formatDisplayTime(note.getUpdateTime());
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

    private void applyFixedHeightClip(Region region, double height) {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(region.widthProperty());
        clip.setHeight(height);
        region.setClip(clip);
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

    private String displayTitle(Note note) {
        String title = note == null || note.getTitle() == null ? "" : note.getTitle().strip();
        if (!isConflictCopy(note)) {
            return title;
        }
        return title.replaceFirst("（冲突副本\\s+[^）]+）$", "").strip();
    }
}
