package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.Dan;
import top.sshh.qqbot.data.Herb;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DanCalculatorSynchronizationTest {
    private static final long BOT_ID = 987650001001L;
    private static final int HERBS_PER_ROLE = 12;
    private static final String OLD_RECIPES = "之前成功生成的完整丹方\n";
    private final Path recipePath = Path.of(String.valueOf(BOT_ID), "炼丹配方.txt");

    @TempDir
    Path directory;

    private DanCalculator calculator;
    private List<Herb> herbs;

    @BeforeEach
    void setUp() {
        calculator = new DanCalculator();
        calculator.configMap.put(BOT_ID, new Config());
        herbs = new ArrayList<>();
        Set<String> leadNames = new HashSet<>();
        for (int i = 0; i < HERBS_PER_ROLE; i++) {
            herbs.add(herb("主药" + i, "生息10", "无效0"));
            herbs.add(herb("药引" + i, "无效0", "无效0"));
            herbs.add(herb("辅药" + i, "无效0", "炼气10"));
            leadNames.add("药引" + i);
        }
        List<Dan> dans = new ArrayList<>();
        for (int level = 1; level <= 2; level++) {
            Dan dan = new Dan(new String[]{"测试丹" + level, "生息" + level * 10, "炼气" + level * 10});
            dan.alchemyValue = level * 100;
            dans.add(dan);
        }
        Collections.sort(dans);
        ReflectionTestUtils.setField(calculator, "herbs", herbs);
        ReflectionTestUtils.setField(calculator, "sortedDans", dans);
        ReflectionTestUtils.setField(calculator, "pingLeadNames", leadNames);
        ReflectionTestUtils.setField(calculator, "danProfilesByTypePairKey",
                ReflectionTestUtils.invokeMethod(calculator, "buildDanProfilesByKey", dans));
    }

    @AfterEach
    void cleanUp() throws Exception {
        LocalDataFiles.deleteIfExists(recipePath);
    }

    @Test
    @Timeout(30)
    void parallelCalculationKeepsEveryRecipeAcrossRepeatedRuns() throws Exception {
        Set<String> expected = expectedRecipes();
        ForkJoinPool pool = new ForkJoinPool(8);
        try {
            for (int round = 0; round < 10; round++) {
                pool.submit(() -> calculator.calculateAllDans(BOT_ID)).get(10, TimeUnit.SECONDS);
                List<String> actual = LocalDataFiles.readAllLines(recipePath, StandardCharsets.UTF_8).stream()
                        .filter(line -> line.startsWith("主药"))
                        .collect(Collectors.toList());

                assertEquals(expected.size(), actual.size(), "第 " + round + " 轮配方数量不完整");
                assertEquals(expected, new HashSet<>(actual), "第 " + round + " 轮配方内容不完整");
                assertTrue(actual.get(0).endsWith("测试丹2"), "配方应按收益降序排列");
                assertTrue(actual.get(actual.size() - 1).endsWith("测试丹1"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void calculationFailureKeepsPreviousRecipes() throws Exception {
        LocalDataFiles.writeString(recipePath, OLD_RECIPES, StandardCharsets.UTF_8);
        herbs.get(0).mainAttr1Type = null;

        assertThrows(RuntimeException.class, () -> calculator.calculateAllDans(BOT_ID));

        assertEquals(OLD_RECIPES, LocalDataFiles.readString(recipePath));
    }

    @Test
    void loadFailureIsPropagatedWithItsCause() throws Exception {
        Path properties = Files.createDirectories(directory.resolve("properties"));
        Files.writeString(properties.resolve("elixirproperties.txt"),
                "-----药材列表-----\n坏药材\t性平0\n", StandardCharsets.UTF_8);
        String previousDirectory = Constant.targetDir;
        try {
            Constant.targetDir = directory + File.separator;

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> calculator.loadData(BOT_ID));

            assertTrue(failure.getMessage().startsWith("加载炼丹数据失败："));
            assertNotNull(failure.getCause());
        } finally {
            Constant.targetDir = previousDirectory;
        }
    }

    @Test
    void saveFailureIsPropagatedAndKeepsPreviousRecipes() throws Exception {
        LocalDataFiles.writeString(recipePath, OLD_RECIPES, StandardCharsets.UTF_8);
        String triggerName = "reject_dan_sync_test_" + BOT_ID;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + LocalDataFiles.databasePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER " + triggerName + " BEFORE UPDATE ON app_document "
                    + "WHEN NEW.document_key = '" + BOT_ID + "/炼丹配方.txt' "
                    + "BEGIN SELECT RAISE(FAIL, 'simulated recipe save failure'); END");
            try {
                UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                        () -> calculator.calculateAllDans(BOT_ID));

                assertTrue(failure.getMessage().startsWith("保存炼丹配方失败："));
                assertEquals(OLD_RECIPES, LocalDataFiles.readString(recipePath));
            } finally {
                statement.execute("DROP TRIGGER " + triggerName);
            }
        }
    }

    private Herb herb(String name, String mainAttribute, String assistAttribute) {
        Herb herb = new Herb(new String[]{name, "性平0", mainAttribute, "性平0", assistAttribute});
        herb.price = 10;
        return herb;
    }

    private Set<String> expectedRecipes() {
        Set<String> expected = new HashSet<>();
        for (int level = 1; level <= 2; level++) {
            int spend = (2 * level + 1) * 10;
            int profit = level * 100 * 6 - spend;
            for (int main = 0; main < HERBS_PER_ROLE; main++) {
                for (int lead = 0; lead < HERBS_PER_ROLE; lead++) {
                    for (int assist = 0; assist < HERBS_PER_ROLE; assist++) {
                        expected.add(String.format("主药主药%d-%d&10 药引药引%d-1&10 辅药辅药%d-%d&10 花费%d 炼金收益%d 6丹 测试丹%d",
                                main, level, lead, assist, level, spend, profit, level));
                    }
                }
            }
        }
        return expected;
    }
}
