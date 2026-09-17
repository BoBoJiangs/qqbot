package top.sshh.qqbot.service.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HerbBackpackParserTest {

    @Test
    void parsesLegacyNameAndCountOnSameLine() {
        HerbBackpackParser.Entry entry = HerbBackpackParser.parseLegacyInlineEntry(
                "名字：[冰灵果](mqqapi://test) 拥有数量：12 炼金 | 坊市数据");

        assertNotNull(entry);
        assertEquals("冰灵果", entry.getName());
        assertEquals(12, entry.getCount());
    }

    @Test
    void parsesInlineMarkdownEntry() {
        HerbBackpackParser.Entry entry = HerbBackpackParser.parseInlineEntry(
                "[冰灵果](mqqapi://test) - 数量：2 炼金 | 坊市数据");

        assertNotNull(entry);
        assertEquals("冰灵果", entry.getName());
        assertEquals(2, entry.getCount());
    }
}
