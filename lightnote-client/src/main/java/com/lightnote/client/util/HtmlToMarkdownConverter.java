package com.lightnote.client.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.List;

public final class HtmlToMarkdownConverter {

    private HtmlToMarkdownConverter() {
    }

    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(html);
        StringBuilder markdown = new StringBuilder();
        for (Node node : document.body().childNodes()) {
            appendNode(markdown, node, 0);
        }
        return normalizeMarkdown(markdown.toString());
    }

    private static void appendNode(StringBuilder markdown, Node node, int listDepth) {
        if (node instanceof TextNode textNode) {
            markdown.append(textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String tag = element.tagName().toLowerCase();
        switch (tag) {
            case "h1", "h2", "h3" -> {
                ensureBlockSpacing(markdown);
                markdown.append("#".repeat(Math.min(3, Integer.parseInt(tag.substring(1)))))
                        .append(' ')
                        .append(renderInlineChildren(element))
                        .append("\n\n");
            }
            case "p", "div" -> {
                String content = renderInlineChildren(element).strip();
                if (!content.isEmpty()) {
                    ensureBlockSpacing(markdown);
                    markdown.append(content).append("\n\n");
                }
            }
            case "br" -> markdown.append('\n');
            case "blockquote" -> {
                ensureBlockSpacing(markdown);
                for (String line : normalizeMarkdown(renderBlockChildren(element, listDepth)).split("\n")) {
                    if (!line.isBlank()) {
                        markdown.append("> ").append(line).append('\n');
                    }
                }
                markdown.append('\n');
            }
            case "pre" -> {
                ensureBlockSpacing(markdown);
                markdown.append("```").append('\n')
                        .append(element.text().stripTrailing())
                        .append('\n')
                        .append("```")
                        .append("\n\n");
            }
            case "ul" -> appendList(markdown, element.children(), false, listDepth);
            case "ol" -> appendList(markdown, element.children(), true, listDepth);
            case "hr" -> {
                ensureBlockSpacing(markdown);
                markdown.append("---").append("\n\n");
            }
            default -> markdown.append(renderInlineElement(element, listDepth));
        }
    }

    private static void appendList(StringBuilder markdown, List<Element> items, boolean ordered, int listDepth) {
        ensureBlockSpacing(markdown);
        int index = 1;
        for (Element item : items) {
            if (!"li".equalsIgnoreCase(item.tagName())) {
                continue;
            }
            String indent = "  ".repeat(Math.max(0, listDepth));
            String marker = ordered ? index++ + ". " : "- ";
            String content = renderListItem(item, listDepth + 1).strip();
            if (!content.isEmpty()) {
                String[] lines = content.split("\n");
                markdown.append(indent).append(marker).append(lines[0]).append('\n');
                for (int i = 1; i < lines.length; i++) {
                    if (!lines[i].isBlank()) {
                        markdown.append(indent).append("   ").append(lines[i]).append('\n');
                    }
                }
            }
        }
        markdown.append('\n');
    }

    private static String renderListItem(Element item, int listDepth) {
        StringBuilder builder = new StringBuilder();
        for (Node child : item.childNodes()) {
            if (child instanceof Element element && ("ul".equalsIgnoreCase(element.tagName()) || "ol".equalsIgnoreCase(element.tagName()))) {
                builder.append('\n');
                appendNode(builder, element, listDepth);
                continue;
            }
            appendInlineNode(builder, child, listDepth);
        }
        return normalizeInlineSpacing(builder.toString());
    }

    private static String renderBlockChildren(Element element, int listDepth) {
        StringBuilder builder = new StringBuilder();
        for (Node child : element.childNodes()) {
            appendNode(builder, child, listDepth);
        }
        return builder.toString();
    }

    private static String renderInlineChildren(Element element) {
        StringBuilder builder = new StringBuilder();
        for (Node child : element.childNodes()) {
            appendInlineNode(builder, child, 0);
        }
        return normalizeInlineSpacing(builder.toString());
    }

    private static String renderInlineElement(Element element, int listDepth) {
        StringBuilder builder = new StringBuilder();
        appendInlineNode(builder, element, listDepth);
        return builder.toString();
    }

    private static void appendInlineNode(StringBuilder builder, Node node, int listDepth) {
        if (node instanceof TextNode textNode) {
            builder.append(textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String tag = element.tagName().toLowerCase();
        switch (tag) {
            case "strong", "b" -> builder.append("**").append(renderInlineChildren(element)).append("**");
            case "em", "i" -> builder.append('*').append(renderInlineChildren(element)).append('*');
            case "code" -> builder.append('`').append(element.text()).append('`');
            case "a" -> {
                String text = renderInlineChildren(element);
                String href = element.attr("href");
                builder.append('[').append(text).append("](").append(href).append(')');
            }
            case "img" -> builder.append("![").append(element.attr("alt")).append("](").append(element.attr("src")).append(')');
            case "br" -> builder.append('\n');
            case "ul", "ol" -> {
                builder.append('\n');
                appendNode(builder, element, listDepth);
            }
            default -> {
                for (Node child : element.childNodes()) {
                    appendInlineNode(builder, child, listDepth);
                }
            }
        }
    }

    private static void ensureBlockSpacing(StringBuilder builder) {
        if (builder.isEmpty()) {
            return;
        }
        if (!builder.toString().endsWith("\n")) {
            builder.append('\n');
        }
        if (!builder.toString().endsWith("\n\n")) {
            builder.append('\n');
        }
    }

    private static String normalizeInlineSpacing(String value) {
        return value
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .strip();
    }

    private static String normalizeMarkdown(String value) {
        return value
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
