package top.sshh.qqbot.service.utils;

import top.sshh.qqbot.data.ProductPrice;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarketPriceUtils {
    private static final String AMOUNT = "([0-9]+(?:\\.[0-9]+)?)\\s*([万亿]?)";
    private static final Pattern ITEM_NAME = Pattern.compile(
            "(?m)^\\s*(.+?)\\s*所在位置\\s*[:：]");
    private static final Pattern MARKET_LIMIT = Pattern.compile(
            "市场限价\\s*[:：]\\s*" + AMOUNT + "\\s*[~～〜至—–-]\\s*" + AMOUNT);
    private static final Pattern CURRENT_PRICE = Pattern.compile(
            "当前价格\\s*[:：]\\s*" + AMOUNT);
    private static final Pattern CATEGORY = Pattern.compile("(?m)^\\s*分类\\s*[:：]\\s*([^\\r\\n|]+)");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LEGACY_IMAGE_TITLE = Pattern.compile(
            "^!物品价格(?:[ \\t]+#\\d+px)*[ \\t]*");

    private MarketPriceUtils() {
    }

    /** 只采集“市场限价”，不使用行情统计中的最高价、最低价。 */
    public static ProductPrice parseMarketInfo(String message) {
        if (message == null || !message.contains("市场限价")) {
            return null;
        }
        // 图片与物品链接可能紧挨着；必须先删除图片，避免把图片标题拼到药材名称里。
        String text = message.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
        text = Utils.stripMarkdownLink(MARKDOWN_IMAGE.matcher(text).replaceAll("")).replace("**", "")
                .replace("\\~", "~").replace("\\\r\n", "\n").replace("\\\n", "\n");
        Matcher name = ITEM_NAME.matcher(text);
        Matcher limits = MARKET_LIMIT.matcher(text);
        if (!name.find() || !limits.find()) {
            return null;
        }
        try {
            long minimum = parseAmount(limits.group(1), limits.group(2));
            long maximum = parseAmount(limits.group(3), limits.group(4));
            if (minimum <= 0L || maximum < minimum) {
                return null;
            }
            String itemName = name.group(1).replaceAll("^#+\\s*", "").trim();
            if (itemName.isEmpty()) return null;
            ProductPrice product = new ProductPrice();
            product.setName(itemName);
            Matcher category = CATEGORY.matcher(text);
            if (category.find()) product.setCategory(category.group(1).trim());
            product.setMarketMinPrice(minimum);
            product.setMarketMaxPrice(maximum);
            Matcher currentPrice = CURRENT_PRICE.matcher(text);
            if (currentPrice.find()) {
                // 现有行情单价使用整数万；限价单独保留到灵石精度。
                product.setPrice(Math.toIntExact(parseAmount(currentPrice.group(1), currentPrice.group(2)) / 10000L));
            }
            return product;
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** 修复旧解析器已写入数据库的图片标题前缀，保留原记录的价格、限价及历史。 */
    public static String normalizeStoredMarketName(String name) {
        if (name == null) return null;
        String normalized = LEGACY_IMAGE_TITLE.matcher(name).replaceFirst("").trim();
        return normalized.isEmpty() ? name : normalized;
    }

    private static long parseAmount(String amount, String unit) {
        int scale = "亿".equals(unit) ? 8 : "万".equals(unit) ? 4 : 0;
        return new BigDecimal(amount).movePointRight(scale).longValueExact();
    }

    /** 在所有减价、税档调整完成后调用，参数和返回值均为灵石单价。 */
    public static long limitListingPrice(ProductPrice product, long proposedPrice) {
        Long minimum = product.getMarketMinPrice();
        Long maximum = product.getMarketMaxPrice();
        long price = proposedPrice;
        if (minimum != null && minimum > 0L) {
            price = Math.max(price, minimum);
        }
        if (maximum != null && maximum > 0L) {
            price = Math.min(price, maximum);
        }
        return price;
    }
}
