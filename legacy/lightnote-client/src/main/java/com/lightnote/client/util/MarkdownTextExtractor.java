package com.lightnote.client.util;

public final class MarkdownTextExtractor {

    private MarkdownTextExtractor() {
    }

    public static String toPlainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return markdown
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s{0,3}>\\s?", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+[.)]\\s+", "")
                .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("[*_~`]+", "")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
