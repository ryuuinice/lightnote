package com.lightnote.server.util;

import com.lightnote.server.model.ContentFormat;

public final class ContentTextExtractor {

    private ContentTextExtractor() {
    }

    public static String toPlainText(String content, String contentFormat) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (ContentFormat.from(contentFormat) == ContentFormat.MARKDOWN) {
            return markdownToPlainText(content);
        }
        return htmlToPlainText(content);
    }

    private static String markdownToPlainText(String markdown) {
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

    private static String htmlToPlainText(String html) {
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p\\s*>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
