package top.sshh.qqbot.service;

import com.zhuangxv.bot.core.Bot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductPrice;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoSellGoodsInventoryTest {
    private static final long BOT_ID = 1001L;

    @Test
    @SuppressWarnings("unchecked")
    void parseHerbListSupportsInlineBackpackRowsAndTenItemListingLimit() throws Exception {
        AutoSellGoods service = new AutoSellGoods();
        GroupManager groupManager = mock(GroupManager.class);
        ReflectionTestUtils.setField(service, "groupManager", groupManager);
        Bot bot = mock(Bot.class);
        when(bot.getBotId()).thenReturn(BOT_ID);

        service.parseHerbList(Arrays.asList(
                "@咕咕咕丫",
                "冰灵果 - 数量：16 炼金 | 坊市数据",
                "☆------五品药材------☆",
                "名字：旧格式药材",
                "拥有数量：3 炼金 | 坊市数据",
                "第2页/共3页 上一页 下一页"), bot);

        Map<Long, List<ProductPrice>> herbPacks =
                (Map<Long, List<ProductPrice>>) ReflectionTestUtils.getField(service, "herbPackMap");
        List<ProductPrice> listings = herbPacks.get(BOT_ID);
        assertEquals(3, listings.size());
        assertEquals("冰灵果", listings.get(0).getName());
        assertEquals(10, listings.get(0).getHerbCount());
        assertEquals("冰灵果", listings.get(1).getName());
        assertEquals(6, listings.get(1).getHerbCount());
        assertEquals("旧格式药材", listings.get(2).getName());
        assertEquals(3, listings.get(2).getHerbCount());
    }
}
