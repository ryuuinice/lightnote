package com.lightnote.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
