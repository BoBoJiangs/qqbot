package top.sshh.qqbot.service.liandan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 将分析报告拆为普通文本摘要、合并转发明细和普通文本命令，不依赖原生 Markdown。 */
final class HerbAnalysisMessageFormatter {
    private static final int MAX_TEXT_LENGTH = 2800;
    private static final String COMMAND_TITLE = "可复制调整命令：";

    private HerbAnalysisMessageFormatter() {
    }

    static Report format(String message) {
        String normalized = message.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("背包药材分析完成\n")) {
            return new Report(split(normalized, MAX_TEXT_LENGTH), Collections.emptyList(), Collections.emptyList());
        }

        int commandStart = normalized.indexOf("\n" + COMMAND_TITLE);
        String body = commandStart < 0 ? normalized : normalized.substring(0, commandStart);
        String[] blocks = body.trim().split("\n(?=\\d+\\. )");
        StringBuilder summary = new StringBuilder(blocks[0].trim());
        List<String> details = new ArrayList<>();
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i].trim();
            String heading = firstLine(block);
            summary.append("\n\n").append(heading);
            appendSummaryLine(summary, block, "价格：");
            appendSummaryLine(summary, block, "判断：");
            appendSummaryLine(summary, block, "建议：");

            // 每种药材独立成节点；超长时保留标题并标记续页，所有原始明细均保留。
            String continuationTitle = shorten(heading, 80);
            List<String> parts = split(block, MAX_TEXT_LENGTH - continuationTitle.length() - 16);
            for (int j = 0; j < parts.size(); j++) {
                details.add(j == 0 ? parts.get(j)
                        : continuationTitle + "（续" + (j + 1) + "）\n" + parts.get(j));
            }
        }
        if (!details.isEmpty()) {
            summary.append("\n\n详细配方、库存缺口和收益测算见合并转发。");
        }

        List<String> commands = new ArrayList<>();
        if (commandStart >= 0) {
            String commandBody = normalized.substring(commandStart + 1 + COMMAND_TITLE.length()).trim();
            // 每一段都带非命令标题，防止机器人把自己的建议当成采购指令自动执行。
            String prefix = COMMAND_TITLE + "\n仅供参考，需手动复制执行。\n";
            String batchNotice = "请合并全部分段命令后整批应用，收益按整批价格计算。\n";
            List<String> parts = split(commandBody, MAX_TEXT_LENGTH - prefix.length() - batchNotice.length());
            for (String part : parts) {
                commands.add(prefix + (parts.size() > 1 ? batchNotice : "") + part);
            }
        }
        return new Report(split(summary.toString(), MAX_TEXT_LENGTH), details, commands);
    }

    private static void appendSummaryLine(StringBuilder summary, String block, String label) {
        for (String line : block.split("\n")) {
            if (line.startsWith(label)) {
                summary.append('\n').append(shorten(line, 120));
                return;
            }
        }
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String shorten(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        int end = maxLength;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…（详见明细）";
    }

    private static List<String> split(String text, int maxLength) {
        List<String> parts = new ArrayList<>();
        String remaining = text.trim();
        while (remaining.length() > maxLength) {
            int end = remaining.lastIndexOf('\n', maxLength);
            if (end <= 0) end = maxLength;
            if (Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
            parts.add(remaining.substring(0, end));
            remaining = remaining.substring(end);
            if (remaining.startsWith("\n")) remaining = remaining.substring(1);
        }
        if (!remaining.isEmpty()) parts.add(remaining);
        return parts;
    }

    static final class Report {
        private final List<String> summaries;
        private final List<String> details;
        private final List<String> commands;

        private Report(List<String> summaries, List<String> details, List<String> commands) {
            this.summaries = List.copyOf(summaries);
            this.details = List.copyOf(details);
            this.commands = List.copyOf(commands);
        }

        List<String> getSummaries() {
            return summaries;
        }

        List<String> getDetails() {
            return details;
        }

        List<String> getCommands() {
            return commands;
        }
    }
}
