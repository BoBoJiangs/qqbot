package top.sshh.qqbot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestServiceGiftAmountTest {

    @ParameterizedTest
    @CsvSource({"5400万,54000000", "1.5亿,150000000", "0.05万,500", "54000000枚,54000000", "0万,0"})
    void extractsRemainingReceiveLimitWithUnits(String amount, long expected) {
        assertEquals(expected, TestService.extractGiftReceiveLimit("今日您还可收 " + amount + "灵石 赠送上限"));
    }

    @Test
    void onlyUsesTodayRemainingLimitAndIgnoresExclusiveAllowancesAndMarkup() {
        String text = "您的接收码为：c882710a-3fef-4c84-acd4-3362d6eb0069 点击赠送 （有效期10分钟）\n"
                + "今日您还可收 [**5,400万灵石**](mqqapi://test) 赠送上限\n"
                + "（师徒/羁绊另有 1000万、好友另有 300万 的每日专属额度）";
        assertEquals(54000000L, TestService.extractGiftReceiveLimit(text));
    }

    @Test
    void missingInvalidOrOverflowingLimitIsNotTreatedAsAnAmount() {
        assertEquals(-1L, TestService.extractGiftReceiveLimit("师徒/羁绊另有1000万、好友另有300万"));
        assertEquals(-1L, TestService.extractGiftReceiveLimit("今日您还可收 -100万灵石"));
        assertEquals(-1L, TestService.extractGiftReceiveLimit("今日您还可收 999999999999999999999亿灵石"));
        assertEquals(-1L, TestService.extractGiftReceiveLimit(null));
    }

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
        // 文案缺少金额时不累计，避免将无法确认归属的回执记入统计。
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

    @Test
    void parsesUserExampleAndMarkdownWithoutCountingOtherNumbers() {
        assertEquals(87000000L, TestService.extractGiftAmount(
                "尼檀共赠送69600000枚灵石给井雲道友！收取手续费17400000枚"));
        assertEquals(87000000L, TestService.extractGiftAmount(
                "尼檀共赠送[**69,600,000**](mqqapi://test)枚灵石给井雲道友！收取手续费 **17,400,000**枚"));
        assertEquals(0L, TestService.extractGiftAmount("共赠送未知枚灵石给道友！收取手续费100枚"));
        assertEquals(0L, TestService.extractGiftAmount("共赠送9223372036854775807枚灵石，手续费1枚"));
    }
}
