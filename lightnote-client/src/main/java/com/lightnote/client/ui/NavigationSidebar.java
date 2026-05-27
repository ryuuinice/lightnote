package com.lightnote.client.ui;

import com.lightnote.client.model.Note;
import com.lightnote.client.model.NoteFilter;
import com.lightnote.client.repository.AppConfigRepository;
import com.lightnote.client.repository.NoteRepository;
import com.lightnote.client.repository.NoteRepository.CategorySummary;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 左侧导航栏，提供筛选按钮（全部/今天/最近7天/收藏/回收站/冲突/归档）、
 * 分类列表（CRUD）、以及工具/设置入口。
 * <p>
 * 通过回调向外通知筛选或分类变更，不直接操作笔记列表或编辑器。
 */
public class NavigationSidebar {

    private static final String UNCATEGORIZED_LABEL = "未分类";

    private final NoteRepository noteRepository;
    private final AppConfigRepository configRepository;
    private final Runnable onFilterOrCategoryChanged;
    private final Runnable onToolsClicked;
    private final Runnable onSettingsClicked;
    private final VBox root;

    private final Map<NoteFilter, Label> navigationCountLabels = new EnumMap<>(NoteFilter.class);
    private final Map<NoteFilter, Button> navigationButtons = new EnumMap<>(NoteFilter.class);
    private final Map<String, Button> categoryButtons = new HashMap<>();
    private final Map<String, Long> categoryCounts = new HashMap<>();
    private final VBox categoryListBox = new VBox(6);
    private final Button addCategoryButton = new Button("+ 分类");
    private final Button renameCategoryButton = new Button("重命名");
    private final Button deleteCategoryButton = new Button("删除");
    private NoteFilter currentFilter = NoteFilter.ALL;
    private String currentCategoryName;

    public NavigationSidebar(
            NoteRepository noteRepository,
            AppConfigRepository configRepository,
            Runnable onFilterOrCategoryChanged,
            Runnable onToolsClicked,
            Runnable onSettingsClicked
    ) {
        this.noteRepository = noteRepository;
        this.configRepository = configRepository;
        this.onFilterOrCategoryChanged = onFilterOrCategoryChanged;
        this.onToolsClicked = onToolsClicked;
        this.onSettingsClicked = onSettingsClicked;
        this.root = buildNavigationColumn();
        configureCategoryActions();
        setCurrentFilter(currentFilter);
    }

    public Parent getRoot() {
        return root;
    }

    // ======================== 状态查询 ========================

    public NoteFilter getCurrentFilter() {
        return currentFilter;
    }

    public String getCurrentCategoryName() {
        return currentCategoryName;
    }

    public boolean isCategoryFilterActive() {
        return currentCategoryName != null;
    }

    public boolean categoryMatchesCurrentFilter(Note note) {
        if (!isCategoryFilterActive()) {
            return true;
        }
        return nullToEmpty(normalizeCategoryName(note == null ? null : note.getCategoryName()))
                .equals(nullToEmpty(currentCategoryName));
    }

    public String contextLabel() {
        return isCategoryFilterActive()
                ? "分类 / " + categoryDisplayName(currentCategoryName)
                : filterLabel(currentFilter);
    }

    public String normalizeCategoryName(String categoryName) {
        if (categoryName == null) {
            return null;
        }
        String normalized = categoryName.strip();
        return normalized.isEmpty() ? "" : normalized;
    }

    public String categoryDisplayName(String categoryName) {
        String normalized = normalizeCategoryName(categoryName);
        return normalized == null || normalized.isEmpty() ? UNCATEGORIZED_LABEL : normalized;
    }

    public String emptyStateTitle() {
        if (isCategoryFilterActive()) {
            return categoryDisplayName(currentCategoryName) + "下还没有笔记";
        }
        return switch (currentFilter) {
            case TODAY -> "今天还没有笔记";
            case RECENT_7_DAYS -> "最近 7 天还没有笔记";
            case FAVORITES -> "还没有收藏笔记";
            case TRASH -> "回收站还是空的";
            case ARCHIVED -> "还没有归档笔记";
            case CONFLICT_COPIES -> "目前没有冲突副本";
            case ALL -> "还没有笔记";
        };
    }

    public String emptyStateDescription() {
        if (isCategoryFilterActive()) {
            return "你可以在当前分类下新建笔记，或切回全部笔记查看更多内容。";
        }
        return switch (currentFilter) {
            case TODAY, RECENT_7_DAYS, ALL -> "点击上方「新建」，从第一条笔记开始。";
            case FAVORITES -> "把重要内容标记为收藏，它们会集中出现在这里。";
            case TRASH -> "先删除一条笔记，它会先进入回收站，你也可以在这里恢复它。";
            case ARCHIVED -> "归档后的笔记会收纳在这里，方便后续回看。";
            case CONFLICT_COPIES -> "发生同步冲突时，保留下来的冲突副本会显示在这里。";
        };
    }

    // ======================== UI 构建 ========================

    private VBox buildNavigationColumn() {
        Label avatar = new Label("LightNote");
        avatar.getStyleClass().add("avatar-badge");

        Label navTitle = new Label("浏览");
        navTitle.getStyleClass().add("section-label");

        Button allNotes = navButton("全部笔记", NoteFilter.ALL, true);
        Button today = navButton("今天", NoteFilter.TODAY, true);
        Button week = navButton("最近 7 天", NoteFilter.RECENT_7_DAYS, true);
        Button favorites = navButton("收藏", NoteFilter.FAVORITES, true);
        Button trash = navButton("回收站", NoteFilter.TRASH, true);
        Button conflicts = navButton("冲突", NoteFilter.CONFLICT_COPIES, true);
        Button archive = navButton("归档", NoteFilter.ARCHIVED, true);

        Label categoryTitle = new Label("分类");
        categoryTitle.getStyleClass().add("section-label");
        categoryListBox.getStyleClass().add("category-list");
        VBox categoryActions = new VBox(6, addCategoryButton, renameCategoryButton, deleteCategoryButton);
        categoryActions.getStyleClass().add("category-actions");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button tools = new Button("工具");
        tools.getStyleClass().add("secondary-nav-button");
        tools.setOnAction(event -> onToolsClicked.run());

        Button settings = new Button("设置");
        settings.getStyleClass().add("secondary-nav-button");
        settings.setOnAction(event -> onSettingsClicked.run());

        VBox sidebar = new VBox(10,
                avatar,
                new javafx.scene.control.Separator(),
                navTitle,
                allNotes,
                today,
                week,
                favorites,
                trash,
                conflicts,
                archive,
                new javafx.scene.control.Separator(),
                categoryTitle,
                categoryActions,
                categoryListBox,
                spacer,
                tools,
                settings);
        sidebar.setPrefWidth(150);
        sidebar.setMaxWidth(150);
        sidebar.setMinWidth(100);
        sidebar.setPadding(new Insets(18, 14, 18, 14));
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private Button navButton(String text, NoteFilter filter, boolean showCountBadge) {
        Button button = new Button();
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-button");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setGraphic(buildNavButtonGraphic(text, showCountBadge ? createNavigationCountLabel(filter) : null));
        button.setOnAction(event -> {
            setCurrentFilter(filter);
            onFilterOrCategoryChanged.run();
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

    // ======================== 筛选状态 ========================

    public void setCurrentFilter(NoteFilter filter) {
        currentFilter = filter == null ? NoteFilter.ALL : filter;
        currentCategoryName = null;
        updateNavigationSelection();
        updateCategorySelection();
    }

    public void setCurrentCategory(String categoryName) {
        currentCategoryName = normalizeCategoryName(categoryName);
        currentFilter = NoteFilter.ALL;
        updateNavigationSelection();
        updateCategorySelection();
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

    // ======================== 分类管理 ========================

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
                    refreshNavigationCounts();
                    refreshCategoryList();
                    onFilterOrCategoryChanged.run();
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
                    setCurrentCategory(nextName);
                    refreshNavigationCounts();
                    refreshCategoryList();
                    onFilterOrCategoryChanged.run();
                });
    }

    private void deleteCurrentCategoryIfEmpty() {
        if (!isCategoryFilterActive() || currentCategoryName == null || currentCategoryName.isBlank()) {
            return;
        }
        if (categoryCounts.getOrDefault(currentCategoryName, 0L) > 0) {
            return;
        }
        configRepository.removeCategory(currentCategoryName);
        setCurrentFilter(NoteFilter.ALL);
        refreshCategoryList();
        onFilterOrCategoryChanged.run();
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

    // ======================== 数量刷新 ========================

    public void refreshNavigationCounts() {
        updateNavigationCount(NoteFilter.ALL);
        updateNavigationCount(NoteFilter.TODAY);
        updateNavigationCount(NoteFilter.RECENT_7_DAYS);
        updateNavigationCount(NoteFilter.FAVORITES);
        updateNavigationCount(NoteFilter.TRASH);
        updateNavigationCount(NoteFilter.CONFLICT_COPIES);
        updateNavigationCount(NoteFilter.ARCHIVED);
    }

    public void refreshCategoryList() {
        List<CategorySummary> summaries = noteRepository.listCategorySummaries();
        categoryCounts.clear();
        for (CategorySummary summary : summaries) {
            categoryCounts.put(normalizeCategoryName(summary.name()), summary.count());
        }
        List<String> categoryNames = new ArrayList<>(configRepository.categoryCatalog());
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
        if (categoryNames.isEmpty()) {
            Label placeholder = new Label("还没有分类");
            placeholder.getStyleClass().add("category-placeholder");
            categoryListBox.getChildren().setAll(placeholder);
            updateCategoryActionState();
            return;
        }

        List<Node> items = new ArrayList<>();
        for (String name : categoryNames) {
            final String categoryName = name;
            Label countLabel = new Label(Long.toString(categoryCounts.getOrDefault(categoryName, 0L)));
            countLabel.getStyleClass().add("nav-count-badge");
            Button button = new Button();
            button.setMaxWidth(Double.MAX_VALUE);
            button.getStyleClass().addAll("nav-button", "category-button");
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.setGraphic(buildNavButtonGraphic(categoryDisplayName(categoryName), countLabel));
            button.setOnAction(event -> {
                setCurrentCategory(categoryName);
                onFilterOrCategoryChanged.run();
            });
            categoryButtons.put(categoryName, button);
            items.add(button);
        }
        categoryListBox.getChildren().setAll(items);
        updateCategorySelection();
    }

    public void refreshCategoryOptions(List<String> categoryNames) {
        // 只暴露分类名称列表，供编辑器下拉框使用
    }

    private void updateNavigationCount(NoteFilter filter) {
        Label countLabel = navigationCountLabels.get(filter);
        if (countLabel == null) {
            return;
        }
        countLabel.setText(Long.toString(noteRepository.countByFilter(filter)));
    }

    // ======================== 工具方法 ========================

    private String filterLabel(NoteFilter filter) {
        return switch (filter == null ? NoteFilter.ALL : filter) {
            case ALL -> "全部笔记";
            case TODAY -> "今天";
            case RECENT_7_DAYS -> "最近 7 天";
            case FAVORITES -> "收藏";
            case TRASH -> "回收站";
            case ARCHIVED -> "归档";
            case CONFLICT_COPIES -> "冲突";
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
