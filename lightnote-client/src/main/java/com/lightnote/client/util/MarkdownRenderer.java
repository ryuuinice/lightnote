package com.lightnote.client.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownRenderer {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^]]*)]\\(([^)]+)\\)");
    private static final Pattern LINK_PATTERN = Pattern.compile("(?<!!)\\[([^]]+)]\\(([^)]+)\\)");

    private MarkdownRenderer() {
    }

    public static String renderDocument(String markdown) {
        return """
                <html>
                <head>
                <style>
                body {
                    background: #ffffff;
                    color: #223046;
                    font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
                    font-size: 15px;
                    line-height: 1.78;
                    margin: 28px 44px 56px 44px;
                }
                h1, h2, h3 { color: #152033; line-height: 1.3; margin: 26px 0 12px; }
                h1 { font-size: 28px; }
                h2 { font-size: 22px; }
                h3 { font-size: 18px; }
                p { margin: 0 0 14px; }
                blockquote { border-left: 3px solid #5b7cfa; color: #4f5f76; margin: 10px 0 16px; padding-left: 14px; }
                pre { background: #f3f6fb; border-radius: 8px; color: #1f2937; padding: 12px 14px; white-space: pre-wrap; }
                code { background: #f3f6fb; border-radius: 4px; padding: 1px 4px; }
                ul, ol { margin: 0 0 16px 24px; padding: 0; }
                li { margin: 0 0 7px; }
                a { color: #3867d6; }
                img { max-width: 100%; border-radius: 8px; border: 1px solid #e1e7f0; }
                hr { border: none; border-top: 1px solid #dce4f0; margin: 22px 0; }
                .missing-asset { color: #8793a6; border: 1px dashed #cfd8e6; border-radius: 8px; padding: 10px 12px; }
                </style>
                </head>
                <body>
                {{content}}
                </body>
                </html>
                """.replace("{{content}}", renderBody(markdown));
    }

    public static String renderBody(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "<p></p>";
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder html = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        boolean inCodeBlock = false;
        StringBuilder codeBlock = new StringBuilder();
        boolean inList = false;
        boolean orderedList = false;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    html.append("<pre><code>").append(escapeHtml(codeBlock.toString().stripTrailing())).append("</code></pre>");
                    codeBlock.setLength(0);
                    inCodeBlock = false;
                } else {
                    flushParagraph(html, paragraph);
                    if (inList) {
                        html.append(orderedList ? "</ol>" : "</ul>");
                        inList = false;
                    }
                    inCodeBlock = true;
                }
                continue;
            }
            if (inCodeBlock) {
                codeBlock.append(line).append('\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(html, paragraph);
                if (inList) {
                    html.append(orderedList ? "</ol>" : "</ul>");
                    inList = false;
                }
                continue;
            }
            if (trimmed.equals("---") || trimmed.equals("***")) {
                flushParagraph(html, paragraph);
                html.append("<hr>");
                continue;
            }

            Matcher listMatcher = Pattern.compile("^(?:([-*+])|(\\d+)[.)])\\s+(.+)$").matcher(trimmed);
            if (listMatcher.matches()) {
                flushParagraph(html, paragraph);
                boolean nextOrdered = listMatcher.group(2) != null;
                if (!inList || orderedList != nextOrdered) {
                    if (inList) {
                        html.append(orderedList ? "</ol>" : "</ul>");
                    }
                    orderedList = nextOrdered;
                    html.append(orderedList ? "<ol>" : "<ul>");
                    inList = true;
                }
                html.append("<li>").append(renderInline(listMatcher.group(3))).append("</li>");
                continue;
            }
            if (inList) {
                html.append(orderedList ? "</ol>" : "</ul>");
                inList = false;
            }

            if (trimmed.startsWith("#")) {
                flushParagraph(html, paragraph);
                int level = headingLevel(trimmed);
                if (level > 0) {
                    String text = trimmed.substring(level).strip();
                    html.append("<h").append(level).append(">")
                            .append(renderInline(text))
                            .append("</h").append(level).append(">");
                    continue;
                }
            }
            if (trimmed.startsWith(">")) {
                flushParagraph(html, paragraph);
                html.append("<blockquote>").append(renderInline(trimmed.substring(1).strip())).append("</blockquote>");
                continue;
            }

            if (!paragraph.isEmpty()) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }

        if (inCodeBlock) {
            html.append("<pre><code>").append(escapeHtml(codeBlock.toString().stripTrailing())).append("</code></pre>");
        }
        flushParagraph(html, paragraph);
        if (inList) {
            html.append(orderedList ? "</ol>" : "</ul>");
        }
        return html.toString();
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        html.append("<p>").append(renderInline(paragraph.toString())).append("</p>");
        paragraph.setLength(0);
    }

    private static int headingLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        return level >= 1 && level <= 6 && trimmed.length() > level && Character.isWhitespace(trimmed.charAt(level))
                ? Math.min(level, 3)
                : 0;
    }

    private static String renderInline(String value) {
        String escaped = escapeHtml(value);
        escaped = replaceImages(escaped);
        escaped = replaceLinks(escaped);
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        escaped = escaped.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        return escaped;
    }

    private static String replaceImages(String value) {
        Matcher matcher = IMAGE_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String src = matcher.group(2);
            String replacement = src.startsWith("lightnote-asset://")
                    ? "<div class=\"missing-asset\">附件预览将在第二阶段支持: " + alt + "</div>"
                    : "<img src=\"" + src + "\" alt=\"" + alt + "\">";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceLinks(String value) {
        Matcher matcher = LINK_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String text = matcher.group(1);
            String href = matcher.group(2);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("<a href=\"" + href + "\">" + text + "</a>"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
