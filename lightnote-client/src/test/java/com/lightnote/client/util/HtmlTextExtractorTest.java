package com.lightnote.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HtmlTextExtractorTest {

    @Test
    void stripsTagsWithoutLeakingAttributeContent() {
        String html = """
                <html><body>
                <p data-fragment='{"text":"x","marks":[[]],"state":{}}'>刷磁盘</p>
                <div>/sys/class/scsi_host/host2/scan &gt; echo &quot;- - -&quot;</div>
                </body></html>
                """;

        String text = HtmlTextExtractor.toPlainText(html);

        assertEquals("刷磁盘\n/sys/class/scsi_host/host2/scan > echo \"- - -\"", text);
    }

    @Test
    void decodesEntitiesAndNormalizesSpacing() {
        String html = "<p>&quot;hello&quot;&nbsp;&nbsp;world<br>line2</p>";

        String text = HtmlTextExtractor.toPlainText(html);

        assertEquals("\"hello\" world\nline2", text);
    }
}
