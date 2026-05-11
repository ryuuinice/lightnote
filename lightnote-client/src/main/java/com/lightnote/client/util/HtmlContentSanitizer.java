package com.lightnote.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HtmlContentSanitizer {

    private static final String EDITOR_STYLE_PATTERN =
            "(?is)<style\\s+id=[\"']lightnote-editor-style[\"'][^>]*>.*?</style>";
    private static final Pattern STYLE_ATTRIBUTE_PATTERN =
            Pattern.compile("(?i)\\sstyle=(['\"])(.*?)\\1");
    private static final Set<String> ALLOWED_STYLE_PROPERTIES = Set.of(
            "font-weight",
            "font-style",
            "text-decoration",
            "text-decoration-line",
            "color",
            "background-color",
            "font-size",
            "font-family",
            "text-align"
    );

    private HtmlContentSanitizer() {
    }

    public static String sanitizeForStorage(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String sanitized = html
                .replaceAll(EDITOR_STYLE_PATTERN, "")
                .replaceAll("(?is)<!--\\s*StartFragment\\s*-->", "")
                .replaceAll("(?is)<!--\\s*EndFragment\\s*-->", "")
                .replaceAll("(?is)<!doctype[^>]*>", "")
                .replaceAll("(?is)<\\?xml[^>]*>", "")
                .replaceAll("(?is)<xml[^>]*>.*?</xml>", "")
                .replaceAll("(?is)<meta[^>]*>", "")
                .replaceAll("(?is)<link[^>]*>", "")
                .replaceAll("(?is)<title[^>]*>.*?</title>", "")
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<o:p>\\s*</o:p>", "")
                .replaceAll("(?is)<o:p>.*?</o:p>", "")
                .replaceAll("(?is)</?[a-z]+:[a-z0-9-]+[^>]*>", "")
                .trim();

        String bodyContent = extractBodyContent(sanitized);
        if (bodyContent != null) {
            sanitized = bodyContent;
        }

        sanitized = sanitizeStyleAttributes(sanitized)
                .replaceAll("(?i)\\s(?:lang|xml:lang)=(['\"]).*?\\1", "")
                .replaceAll("(?i)<span>\\s*(.*?)\\s*</span>", "$1")
                .replaceAll("(?i)<font>\\s*(.*?)\\s*</font>", "$1");

        return sanitized
                .replaceAll("(?i)\\sclass=(['\"])\\s*\\1", "")
                .replaceAll("(?i)\\sstyle=(['\"])\\s*\\1", "")
                .trim();
    }

    private static String sanitizeStyleAttributes(String html) {
        Matcher matcher = STYLE_ATTRIBUTE_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String sanitizedStyle = sanitizeInlineStyle(matcher.group(2));
            if (sanitizedStyle.isBlank()) {
                matcher.appendReplacement(buffer, "");
            } else {
                matcher.appendReplacement(buffer, " style=\"" + Matcher.quoteReplacement(sanitizedStyle) + "\"");
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String sanitizeInlineStyle(String styleValue) {
        String[] declarations = styleValue.split(";");
        List<String> kept = new ArrayList<>();
        for (String declaration : declarations) {
            String trimmed = declaration.trim();
            if (trimmed.isEmpty() || !trimmed.contains(":")) {
                continue;
            }
            int separatorIndex = trimmed.indexOf(':');
            String property = trimmed.substring(0, separatorIndex).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(separatorIndex + 1).trim();
            if (property.startsWith("mso-") || value.isEmpty() || !ALLOWED_STYLE_PROPERTIES.contains(property)) {
                continue;
            }
            kept.add(property + ": " + value);
        }
        return String.join("; ", kept);
    }

    private static String extractBodyContent(String html) {
        String normalized = html.stripLeading().toLowerCase();
        int bodyStart = normalized.indexOf("<body");
        if (bodyStart < 0) {
            return null;
        }
        int bodyOpenEnd = normalized.indexOf(">", bodyStart);
        if (bodyOpenEnd < 0) {
            return null;
        }
        int bodyEnd = normalized.lastIndexOf("</body>");
        if (bodyEnd < 0 || bodyEnd <= bodyOpenEnd) {
            return html.substring(bodyOpenEnd + 1);
        }
        return html.substring(bodyOpenEnd + 1, bodyEnd);
    }
}
