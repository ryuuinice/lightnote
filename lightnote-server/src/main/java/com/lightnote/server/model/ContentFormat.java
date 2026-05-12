package com.lightnote.server.model;

import java.util.Locale;

/**
 * 正文格式枚举，用于区分 HTML 原文与 Markdown 正文。
 */
public enum ContentFormat {
    HTML,
    MARKDOWN;

    public static ContentFormat from(String value) {
        if (value == null || value.isBlank()) {
            return HTML;
        }
        try {
            return ContentFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return HTML;
        }
    }
}

