package com.lightnote.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    @Test
    void rendersMarkdownPreviewFromPlainEditorText() {
        String html = MarkdownRenderer.renderDocument("""
                # 标题

                这里有 **重点** 和 <literal> 标签。

                ![截图](lightnote-asset://asset-1)
                """);

        assertTrue(html.contains("<h1>标题</h1>"));
        assertTrue(html.contains("<strong>重点</strong>"));
        assertTrue(html.contains("&lt;literal&gt;"));
        assertTrue(html.contains("附件预览将在第二阶段支持: 截图"));
    }
}
