package top.sshh.qqbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AutoBuyGoodsCooldownTest {

    @Test
    void remainingMinutesCeil_works() {
        assertEquals(0, AutoBuyGoods.remainingMinutesCeil(0));
        assertEquals(0, AutoBuyGoods.remainingMinutesCeil(-1));
        assertEquals(1, AutoBuyGoods.remainingMinutesCeil(1));
        assertEquals(1, AutoBuyGoods.remainingMinutesCeil(60_000));
        assertEquals(2, AutoBuyGoods.remainingMinutesCeil(60_001));
        assertEquals(2, AutoBuyGoods.remainingMinutesCeil(120_000));
    }
}

