package com.lightnote.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlToMarkdownConverterTest {

    @Test
    void convertsHeadingsParagraphsAndInlineFormatting() {
        String markdown = HtmlToMarkdownConverter.convert("""
                <h1>部署笔记</h1>
                <p>这里有 <strong>重点</strong>、<em>强调</em> 和 <a href="https://example.com">链接</a>。</p>
                """);

        assertEquals("""
                # 部署笔记

                这里有 **重点**、*强调* 和 [链接](https://example.com)。""", markdown);
    }

    @Test
    void convertsListsAndBlockquotes() {
        String markdown = HtmlToMarkdownConverter.convert("""
                <blockquote><p>先检查连接</p></blockquote>
                <ol><li>发现 target</li><li>执行登录</li></ol>
                <ul><li>确认盘符</li><li>检查挂载点</li></ul>
                """);

        assertEquals("""
                > 先检查连接

                1. 发现 target
                2. 执行登录

                - 确认盘符
                - 检查挂载点""", markdown);
    }

    @Test
    void convertsCodeBlocksAndImages() {
        String markdown = HtmlToMarkdownConverter.convert("""
                <pre><code>echo hello
                ls -la</code></pre>
                <p><img src="lightnote-asset://asset-1" alt="截图"></p>
                """);

        assertTrue(markdown.contains("```"));
        assertTrue(markdown.contains("echo hello"));
        assertTrue(markdown.contains("![截图](lightnote-asset://asset-1)"));
    }
}
