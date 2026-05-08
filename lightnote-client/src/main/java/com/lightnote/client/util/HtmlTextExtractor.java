package com.lightnote.client.util;

import java.io.IOException;
import java.io.StringReader;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

public final class HtmlTextExtractor {

    private HtmlTextExtractor() {
    }

    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        HTMLEditorKit.ParserCallback callback = new HTMLEditorKit.ParserCallback() {
            @Override
            public void handleText(char[] data, int pos) {
                out.append(data);
            }

            @Override
            public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                if (tag == HTML.Tag.BR) {
                    out.append('\n');
                }
            }

            @Override
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                if (tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.LI
                        || tag == HTML.Tag.H1 || tag == HTML.Tag.H2 || tag == HTML.Tag.H3
                        || tag == HTML.Tag.H4 || tag == HTML.Tag.H5 || tag == HTML.Tag.H6
                        || tag == HTML.Tag.TR) {
                    appendSpacing(out, '\n');
                }
            }

            @Override
            public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.LI
                        || tag == HTML.Tag.H1 || tag == HTML.Tag.H2 || tag == HTML.Tag.H3
                        || tag == HTML.Tag.H4 || tag == HTML.Tag.H5 || tag == HTML.Tag.H6
                        || tag == HTML.Tag.TR) {
                    appendSpacing(out, '\n');
                }
            }
        };

        try (StringReader reader = new StringReader(html)) {
            new ParserDelegator().parse(reader, callback, true);
        } catch (IOException ex) {
            return fallbackDecode(html);
        }

        return normalizeWhitespace(out.toString());
    }

    private static void appendSpacing(StringBuilder out, char spacing) {
        if (out.isEmpty()) {
            return;
        }
        if (out.charAt(out.length() - 1) != spacing) {
            out.append(spacing);
        }
    }

    private static String normalizeWhitespace(String value) {
        return value
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String fallbackDecode(String html) {
        return html
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>|</div>|</li>|</h[1-6]>", "\n")
                .replaceAll("<[^>]+>", " ")
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
