package top.sshh.qqbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AutoBuyGoodsTest {

    @Test
    void computeAutoTaskRefreshTimeoutMs_baseTimeout() {
        assertEquals(10000L, AutoBuyGoods.computeAutoTaskRefreshTimeoutMs(0));
        assertEquals(10000L, AutoBuyGoods.computeAutoTaskRefreshTimeoutMs(-1));
        assertEquals(10000L, AutoBuyGoods.computeAutoTaskRefreshTimeoutMs(1));
    }

    @Test
    void computeAutoTaskRefreshTimeoutMs_respectsFrequency() {
        assertEquals(35000L, AutoBuyGoods.computeAutoTaskRefreshTimeoutMs(30));
    }
}

