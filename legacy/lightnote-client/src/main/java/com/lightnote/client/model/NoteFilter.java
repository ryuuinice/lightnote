package com.lightnote.client.model;

/**
 * 笔记筛选类型枚举，定义侧边栏和列表查询支持的过滤条件。
 */
public enum NoteFilter {
    ALL,
    TODAY,
    RECENT_7_DAYS,
    FAVORITES,
    TRASH,
    ARCHIVED,
    CONFLICT_COPIES
}

