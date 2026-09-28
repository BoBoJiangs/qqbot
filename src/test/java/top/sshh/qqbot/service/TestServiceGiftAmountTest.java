package top.sshh.qqbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestServiceGiftAmountTest {

    @Test
    void extractsGiveAndFeeFromPlainConfirmationText() {
        String text = "弘瑑一共赠送800000枚灵石给一心道友！收取手续费200000枚";
        assertEquals(1000000L, TestService.extractGiftAmount(text));
    }

    @Test
    void ignoresBotAppidAndOtherNumbersInFullChainText() {
        // SnowLuma 整链拼接文本：reply段 + @段 + 正文 + 尾部 json[inline_keyboard]，
        // 旧实现用 \d+ 全文求和会把 bot_appid(102074059) 当作赠送灵石累加成1.02亿
        String text = "reply[2078]@3865898589\n"
                + "弘瑑一共赠送800000枚灵石给一心道友！收取手续费200000枚\n"
                + "json[{\"type\":\"inline_keyboard\",\"data\":{\"bot_appid\":\"102074059\",\"rows\":[]}}]";
        assertEquals(1000000L, TestService.extractGiftAmount(text));
    }

    @Test
    void returnsZeroWhenConfirmationNumbersMissing() {
        // 文案变化导致提取不到时返回0，handler 会回退用 LingShiNum*10000
        assertEquals(0L, TestService.extractGiftAmount(
                "json[{\"type\":\"inline_keyboard\",\"data\":{\"bot_appid\":\"102074059\",\"rows\":[]}}]"));
        assertEquals(0L, TestService.extractGiftAmount(""));
        assertEquals(0L, TestService.extractGiftAmount(null));
    }

    @Test
    void handlesMillionScaleAmounts() {
        String text = "弘瑑一共赠送80000000枚灵石给一心道友！收取手续费20000000枚";
        assertEquals(100000000L, TestService.extractGiftAmount(text));
    }
}
