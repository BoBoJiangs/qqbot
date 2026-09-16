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

class AutoSellGoodsPillInventoryTest {
    private static final long BOT_ID = 1001L;

    @Test
    @SuppressWarnings("unchecked")
    void parsePillListSupportsInlineRowsAndSkipsAlchemyFurnace() throws Exception {
        AutoSellGoods service = new AutoSellGoods();
        GroupManager groupManager = mock(GroupManager.class);
        ReflectionTestUtils.setField(service, "groupManager", groupManager);
        Bot bot = mock(Bot.class);
        when(bot.getBotId()).thenReturn(BOT_ID);

        service.parsePillList(Arrays.asList(
                "@一心(发言次数:10)",
                "一心的丹药背包",
                "☆------炼丹炉------☆",
                "寒铁铸心炉 - 数量：1 查看效果",
                "☆------恢复丹药------☆",
                "易筋丹 - 数量：18 炼金 | 坊市数据 | 查看效果",
                "素心真丸 - 数量：1260 炼金 | 坊市数据 | 查看效果",
                "名字：旧格式丹药",
                "拥有数量：3 炼金 | 查看效果",
                "第2页/共3页 上一页 下一页"), bot);

        Map<Long, List<ProductPrice>> pillPacks =
                (Map<Long, List<ProductPrice>>) ReflectionTestUtils.getField(service, "pillPackMap");
        List<ProductPrice> pills = pillPacks.get(BOT_ID);
        assertEquals(3, pills.size());
        assertEquals("易筋丹", pills.get(0).getName());
        assertEquals(18, pills.get(0).getHerbCount());
        assertEquals("素心真丸", pills.get(1).getName());
        assertEquals(1260, pills.get(1).getHerbCount());
        assertEquals("旧格式丹药", pills.get(2).getName());
        assertEquals(3, pills.get(2).getHerbCount());
    }
}
