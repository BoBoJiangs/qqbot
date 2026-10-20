package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class HerbAnalysisMessageFormatterTest {
    @Test
    void shortNoticeRemainsPlainText() {
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format("无需调整价格");
        assertEquals(List.of("无需调整价格"), report.getSummaries());
        assertTrue(report.getDetails().isEmpty());
        assertTrue(report.getCommands().isEmpty());
    }

    @Test
    void separatesSummaryDetailsAndBothKindsOfPurchaseCommands() {
        String first = "1. 离火梧桐芝 76/30\n价格：坊市价940万；普通采购价900万；重复采购价920万\n判断：搭配药库存不足导致积压\n"
                + "关联配方：消冰宝丸；其他搭配方案\n原因：地龙干缺46，血莲精缺17；调价后收益35万\n建议：优先补齐搭配药，本药材暂不降价。";
        String second = "2. 天问花 50/30\n价格：坊市价90万；普通采购价100万\n判断：采购价格偏高\n关联配方：测试丹\n原因：已达到降价门槛\n建议：降低采购价格";
        String commands = "请整批应用以下建议，预计收益按这批价格共同计算：\n采购药材地龙干 430\n重复采购药材地龙干 430 30\n采购药材天问花 90";
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format(
                "背包药材分析完成\n已解析药材：62种\n检测到超限药材：2种\n\n" + first + "\n\n" + second
                        + "\n\n可复制调整命令：\n" + commands);

        String summary = String.join("\n", report.getSummaries());
        assertTrue(summary.contains("离火梧桐芝 76/30"));
        assertTrue(summary.contains("天问花 50/30"));
        assertTrue(summary.contains("价格：坊市价940万；普通采购价900万；重复采购价920万"));
        assertTrue(summary.contains("价格：坊市价90万；普通采购价100万"));
        assertTrue(summary.contains("判断：搭配药库存不足导致积压"));
        assertTrue(summary.contains("建议：优先补齐搭配药"));
        assertTrue(summary.contains("见合并转发"));
        assertFalse(summary.contains("关联配方："));
        assertFalse(summary.contains("原因："));
        assertFalse(summary.contains("采购药材地龙干 430"));
        assertEquals(List.of(first, second), report.getDetails());
        assertEquals(1, report.getCommands().size());
        assertTrue(report.getCommands().get(0).startsWith("可复制调整命令：\n"));
        assertTrue(report.getCommands().get(0).contains("需手动复制执行"));
        assertTrue(report.getCommands().get(0).endsWith(commands));
    }

    @Test
    void longDetailsKeepAllRowsAndNeverLeakIntoSummaryOrCommands() {
        String rows = "【搭配草】现有0/目标7，缺7；采购价50万，坊市价80万\n".repeat(160);
        String message = "背包药材分析完成\n已解析药材：1种\n\n1. 天问花 10/3\n判断：搭配药短缺\n"
                + "关联配方：6丹 测试丹\n原因：\n" + rows + "内容中也有```反引号\n"
                + "建议：按收益预算采购\n\n可复制调整命令：\n采购药材搭配草 90";
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format(message);
        assertTrue(report.getDetails().size() > 1);
        for (int i = 0; i < report.getDetails().size(); i++) {
            String detail = report.getDetails().get(i);
            assertTrue(detail.length() <= 2800);
            assertTrue(detail.startsWith("1. 天问花 10/3"));
            if (i > 0) assertTrue(detail.contains("（续" + (i + 1) + "）"));
            assertFalse(detail.contains("采购药材搭配草 90"));
        }
        int rowCount = report.getDetails().stream().mapToInt(s -> s.split("【搭配草】", -1).length - 1).sum();
        assertEquals(160, rowCount, "分段丢失明细");
        assertTrue(String.join("\n", report.getDetails()).contains("内容中也有```反引号"));
        assertFalse(String.join("\n", report.getSummaries()).contains("【搭配草】"));
        assertFalse(String.join("\n", report.getCommands()).contains("【搭配草】"));
    }

    @Test
    void noCommandsNoticeStillSentAsSeparatePlainText() {
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format(
                "背包药材分析完成\n\n1. 天问花 10/3\n判断：继续观察\n关联配方：测试丹\n原因：已保证收益\n建议：暂不调价"
                        + "\n\n可复制调整命令：\n本次没有生成采购价调整命令。");
        assertEquals(1, report.getDetails().size());
        assertEquals(1, report.getCommands().size());
        assertTrue(report.getCommands().get(0).endsWith("本次没有生成采购价调整命令。"));
    }

    @Test
    void everyCommandChunkHasSafeTitleAndWholeBatchReminder() {
        String lines = IntStream.range(0, 250).mapToObj(i -> "采购药材测试药" + i + " 430\n重复采购药材测试药" + i + " 430 30")
                .collect(Collectors.joining("\n"));
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format(
                "背包药材分析完成\n已解析药材：250种\n\n可复制调整命令：\n" + lines);
        assertTrue(report.getCommands().size() > 1);
        List<String> actual = report.getCommands().stream().flatMap(s -> s.lines())
                .filter(s -> s.startsWith("采购药材") || s.startsWith("重复采购药材")).collect(Collectors.toList());
        assertEquals(lines.lines().collect(Collectors.toList()), actual);
        for (String chunk : report.getCommands()) {
            assertTrue(chunk.startsWith("可复制调整命令：\n"));
            assertTrue(chunk.contains("需手动复制执行"));
            assertTrue(chunk.contains("合并全部分段命令后整批应用"));
            assertTrue(chunk.length() <= 2800);
        }
    }

    @Test
    void summaryShortensHugeSuggestionButDetailsKeepItInFull() {
        String suggestion = "建议：" + "很长的建议".repeat(100);
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format(
                "背包药材分析完成\n\n1. 天问花 10/3\n判断：观察\n关联配方：测试丹\n原因：长原因\n" + suggestion);
        assertTrue(report.getSummaries().get(0).contains("…（详见明细）"));
        assertFalse(report.getSummaries().get(0).contains(suggestion));
        assertTrue(report.getDetails().get(0).endsWith(suggestion));
    }

    @Test
    void supportsWindowsLineEndingsAndDoesNotSplitSurrogatePairs() {
        String emoji = "😀".repeat(4000);
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format(
                "背包药材分析完成\r\n\r\n1. 天问花 10/3\r\n判断：观察\r\n关联配方：测试丹\r\n原因：" + emoji
                        + "\r\n建议：继续观察\r\n\r\n可复制调整命令：\r\n本次没有生成采购价调整命令。");
        String details = String.join("\n", report.getDetails());
        assertEquals(4000, details.codePoints().filter(c -> c == 0x1f600).count());
        for (String detail : report.getDetails()) {
            for (int i = 0; i < detail.length(); i++) {
                char c = detail.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < detail.length() && Character.isLowSurrogate(detail.charAt(i + 1)));
                    i++;
                } else {
                    assertFalse(Character.isLowSurrogate(c));
                }
            }
        }
        assertTrue(report.getCommands().get(0).endsWith("本次没有生成采购价调整命令。"));
    }
}
