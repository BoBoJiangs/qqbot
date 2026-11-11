package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ReplyMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.service.utils.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TestServiceAlchemyExclusionTest {
    @ParameterizedTest
    @CsvSource({
            "天尘丹, false, false", "天尘丹, true, true", "易筋丹, false, false", "易筋丹, true, true",
            "渡厄丹, false, true", "寒铁铸心炉, false, true", "陨铁炉, false, true", "雕花紫铜炉, false, true",
            "渡厄丹, true, true", "寒铁铸心炉, true, true", "陨铁炉, true, true", "雕花紫铜炉, true, true"
    })
    void oneClickAlchemyAppliesFixedProtectionAndCustomExclusions(String name, boolean excluded, boolean shouldSkip) {
        TestService service = new TestService();
        GroupManager groupManager = mock(GroupManager.class);
        ReflectionTestUtils.setField(service, "groupManager", groupManager);
        ReflectionTestUtils.setField(service, "productPriceResponse", mock(ProductPriceResponse.class));
        Bot bot = mock(Bot.class);
        Group group = mock(Group.class);
        BotConfig config = new BotConfig();
        // 每个测试独立的群，避免共用发送队列的间隔影响断言。
        long groupId = 8000000L + Integer.toUnsignedLong(name.hashCode());
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(groupId)).thenReturn(group);
        when(group.getGroupId()).thenReturn(groupId);
        when(groupManager.isAlchemyExcluded(1001L, name)).thenReturn(excluded);
        ReplyMessage reply = new ReplyMessage();
        reply.setText("名字：" + name + "\n物品功效 拥有数量:390---炼金 |坊市数据");
        MessageChain chain = new MessageChain();
        chain.add(reply);

        service.alchemyAndListed(chain, bot, "一键炼金", group);

        if (shouldSkip) {
            verify(group, never()).sendMessage(any(MessageChain.class));
        } else {
            ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
            verify(group, timeout(1000)).sendMessage(sent.capture());
            assertEquals("炼金 " + name + " 390", Utils.getMessageText(sent.getValue()));
        }
    }
}
