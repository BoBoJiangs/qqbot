package top.sshh.qqbot.service.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析新式单行药材背包记录，例如“冰灵果 - 数量：2 炼金 | 坊市数据”。 */
public final class HerbBackpackParser {
    private static final Pattern INLINE_HERB_COUNT_PATTERN =
            Pattern.compile("^(.+?)\\s*[-－—]\\s*数量\\s*[:：]\\s*(\\d+)(?:\\D.*)?$");
    private static final Pattern LEGACY_HERB_NAME_PATTERN =
            Pattern.compile("名字\\s*[:：]\\s*(.+?)(?=\\s*(?:拥有数量|数量)\\s*[:：]|$)");

    private HerbBackpackParser() {
    }

    /** 非新式单行药材记录返回 null。 */
    public static Entry parseInlineEntry(String rawLine) {
        if (rawLine == null) return null;
        String line = Utils.stripMarkdownLink(rawLine.trim());
        Matcher matcher = INLINE_HERB_COUNT_PATTERN.matcher(line);
        if (!matcher.matches()) return null;

        String name = Utils.stripMarkdownLink(matcher.group(1)).replaceAll("\\s+", "");
        if (name.isEmpty()) return null;
        try {
            return new Entry(name, Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析旧式“名字：药材名”，也兼容名字和拥有数量位于同一行。 */
    public static String parseLegacyName(String rawLine) {
        if (rawLine == null) return null;
        String line = Utils.stripMarkdownLink(rawLine.trim());
        Matcher matcher = LEGACY_HERB_NAME_PATTERN.matcher(line);
        if (!matcher.find()) return null;
        String name = Utils.stripMarkdownLink(matcher.group(1)).replaceAll("\\s+", "");
        return name.isEmpty() ? null : name;
    }

    /** 解析“名字：药材名 拥有数量：2”这种单行旧格式。 */
    public static Entry parseLegacyInlineEntry(String rawLine) {
        String name = parseLegacyName(rawLine);
        int count = Utils.parseHerbCount(rawLine);
        return name == null || count < 0 ? null : new Entry(name, count);
    }

    public static final class Entry {
        private final String name;
        private final int count;

        private Entry(String name, int count) {
            this.name = name;
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }
}
