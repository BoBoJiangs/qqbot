package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAlchemyTaskFileExportTest {
    private static final long BOT_ID = 987650001003L;
    private static final long GROUP_ID = 123456L;
    private final Path directory = Path.of(String.valueOf(BOT_ID));
    private final AutoAlchemyTask task = new AutoAlchemyTask();
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);

    @BeforeEach
    void setUp() {
        task.danCalculator = mock(DanCalculator.class);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(new BotConfig());
        when(group.getGroupId()).thenReturn(GROUP_ID);
        when(task.danCalculator.getConfig(BOT_ID)).thenReturn(new Config());
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String name : new String[]{"炼丹配方.txt", "药材价格.txt"}) {
            Path path = directory.resolve(name);
            LocalDataFiles.deleteIfExists(path);
            Files.deleteIfExists(path);
        }
        Files.deleteIfExists(directory);
    }

    @Test
    void recipeQueryExportsAndUploadsFreshDatabaseContentAcrossContainers() throws Exception {
        String recipe = "主药明心问道果-1&950 药引梧桐木-1&390 辅药地龙干-1&440 花费1780 炼金收益20 6丹 消冰宝丸\n";
        assertFreshContentUploaded("查询炼丹配方", "炼丹配方.txt", recipe.repeat(2000));
    }

    @Test
    void priceQueryExportsAndUploadsFreshDatabaseContentAcrossContainers() throws Exception {
        assertFreshContentUploaded("查询药材价格", "药材价格.txt", "440 地龙干\n");
    }

    @Test
    void uploadFailureIsReportedWithoutRetryingOrLosingTheExport() throws Exception {
        Path path = directory.resolve("炼丹配方.txt");
        String content = "最新的炼丹配方\n";
        LocalDataFiles.writeString(path, content, StandardCharsets.UTF_8);
        doThrow(new IllegalStateException("模拟协议端上传失败"))
                .when(bot).uploadGroupFile(eq(GROUP_ID), anyString(), eq("炼丹配方.txt"), eq(""));

        executeCommand("查询炼丹配方");

        verify(bot).uploadGroupFile(eq(GROUP_ID), anyString(), eq("炼丹配方.txt"), eq(""));
        assertEquals(content, Files.readString(path));
        assertEquals(content, LocalDataFiles.readString(path));
        ArgumentCaptor<MessageChain> response = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(response.capture());
        assertEquals("上传炼丹配方.txt失败：模拟协议端上传失败", textOf(response.getValue()));
    }

    @Test
    void missingDatabaseDocumentDoesNotUploadAStalePhysicalFile() throws Exception {
        Path path = directory.resolve("炼丹配方.txt");
        LocalDataFiles.deleteIfExists(path);
        Files.createDirectories(directory);
        Files.writeString(path, "过期的配方", StandardCharsets.UTF_8);

        executeCommand("查询炼丹配方");

        verify(bot, never()).uploadGroupFile(eq(GROUP_ID), anyString(), anyString(), anyString());
        ArgumentCaptor<MessageChain> response = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(response.capture());
        assertTrue(textOf(response.getValue()).startsWith("上传炼丹配方.txt失败："));
    }

    private void assertFreshContentUploaded(String command, String fileName, String content) throws Exception {
        Path path = directory.resolve(fileName);
        byte[] expected = content.getBytes(StandardCharsets.UTF_8);
        LocalDataFiles.write(path, expected);
        Files.createDirectories(directory);
        Files.writeString(path, "380 地龙干\n", StandardCharsets.UTF_8);

        executeCommand(command);

        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(bot).uploadGroupFile(eq(GROUP_ID), source.capture(), eq(fileName), eq(""));
        assertTrue(source.getValue().startsWith("base64://"));
        assertArrayEquals(expected, Base64.getDecoder().decode(source.getValue().substring("base64://".length())));
        assertArrayEquals(expected, Files.readAllBytes(path));
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    private void executeCommand(String command) throws Exception {
        task.enableScheduled(bot, group, null, new MessageChain().text(command), command, 1);
        ForkJoinPool pool = (ForkJoinPool) ReflectionTestUtils.getField(AutoAlchemyTask.class, "customPool");
        assertTrue(pool.awaitQuiescence(5, TimeUnit.SECONDS), "文件导出任务未按时完成");
    }

    private String textOf(MessageChain chain) {
        return chain.getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText)
                .collect(Collectors.joining());
    }
}
