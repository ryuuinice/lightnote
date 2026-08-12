package com.lightnote.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HtmlContentSanitizerTest {

    @Test
    void removesEditorChromeAndReturnsBodyContent() {
        String html = """
                <html>
                <head>
                <style id="lightnote-editor-style">body { color: red; }</style>
                <meta charset="utf-8">
                </head>
                <body><p>Hello</p></body>
                </html>
                """;

        String sanitized = HtmlContentSanitizer.sanitizeForStorage(html);

        assertEquals("<p>Hello</p>", sanitized);
    }

    @Test
    void stripsCommonPasteArtifactsWithoutDroppingInlineFormatting() {
        String html = """
                <!DOCTYPE html>
                <!--StartFragment-->
                <html>
                <head>
                <title>Doc</title>
                <script>alert('x')</script>
                </head>
                <body>
                <p class="" style=""><span style="font-weight: bold;">Bold</span><o:p>ignored</o:p></p>
                </body>
                </html>
                <!--EndFragment-->
                """;

        String sanitized = HtmlContentSanitizer.sanitizeForStorage(html);

        assertEquals("<p><span style=\"font-weight: bold\">Bold</span></p>", sanitized);
        assertFalse(sanitized.contains("StartFragment"));
        assertFalse(sanitized.contains("<script"));
        assertFalse(sanitized.contains("<title"));
        assertFalse(sanitized.contains("class=\"\""));
    }

    @Test
    void removesWordSpecificStylesAndNamespacedTagsButKeepsUsefulFormatting() {
        String html = """
                <html><body>
                <p lang="en-US" style="margin: 0; mso-line-height-alt: 120%; color: #333333; font-weight: bold;">
                    <w:sdt><span style="mso-bidi-font-weight: normal; background-color: yellow;">Hello</span></w:sdt>
                </p>
                </body></html>
                """;

        String sanitized = HtmlContentSanitizer.sanitizeForStorage(html);

        assertEquals("""
                <p style="color: #333333; font-weight: bold">
                    <span style="background-color: yellow">Hello</span>
                </p>""", sanitized);
        assertFalse(sanitized.contains("mso-"));
        assertFalse(sanitized.contains("lang="));
        assertFalse(sanitized.contains("w:sdt"));
    }

    @Test
    void unwrapsEmptyInlineWrappersButKeepsSupportedAlignmentAndDecoration() {
        String html = """
                <html><body>
                <p style="text-align: center; margin-left: 10px;">
                    <span style="mso-fareast-language: ZH-CN;"><font><span style="text-decoration: underline;">Hello</span></font></span>
                </p>
                </body></html>
                """;

        String sanitized = HtmlContentSanitizer.sanitizeForStorage(html);

        assertEquals("""
                <p style="text-align: center">
                    <span style="text-decoration: underline">Hello</span>
                </p>""", sanitized);
        assertFalse(sanitized.contains("<font>"));
        assertFalse(sanitized.contains("margin-left"));
    }

    @Test
    void decodesRepeatedlyEscapedHtmlMarkup() {
        String escaped = """
                &amp;amp;lt;span style="font-weight: bold"&amp;amp;gt;Hello&amp;amp;lt;/span&amp;amp;gt;&amp;amp;lt;br&amp;amp;gt;
                """;

        String decoded = HtmlContentSanitizer.decodeEscapedMarkupIfNeeded(escaped);

        assertEquals("<span style=\"font-weight: bold\">Hello</span><br>", decoded.strip());
    }

    @Test
    void keepsPlainEntityTextWhenItIsNotMarkup() {
        String literal = "正文里保留 &quot;hello&quot; 和 &lt;3";

        String decoded = HtmlContentSanitizer.decodeEscapedMarkupIfNeeded(literal);

        assertEquals(literal, decoded);
    }

    @Test
    void recognizesInlineAndListFragmentsAsHtmlMarkup() {
        assertTrue(HtmlContentSanitizer.looksLikeHtmlMarkup("<span style=\"font-weight: bold\">Hello</span>"));
        assertTrue(HtmlContentSanitizer.looksLikeHtmlMarkup("<ul><li>Hello</li></ul>"));
        assertFalse(HtmlContentSanitizer.looksLikeHtmlMarkup("plain < text > only"));
    }

    @Test
    void normalizesEscapedFullDocumentToBodyFragment() {
        String escaped = """
                &lt;html&gt;&lt;head&gt;&lt;style id="lightnote-editor-style"&gt;body{}&lt;/style&gt;&lt;/head&gt;&lt;body&gt;&lt;span style="font-weight: bold"&gt;Hello&lt;/span&gt;&lt;/body&gt;&lt;/html&gt;
                """;

        String normalized = HtmlContentSanitizer.normalizeForStorage(escaped);

        assertEquals("<span style=\"font-weight: bold\">Hello</span>", normalized);
    }
}
