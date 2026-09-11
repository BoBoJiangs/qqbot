package top.sshh.qqbot.service.liandan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 动态丹方生成后的只读蓝图。价格和库存不属于丹方规则，因此不保存在蓝图中。
 */
final class DanRecipeBlueprint {
    private final String danName;
    private final String mainName;
    private final int mainCount;
    private final String leadName;
    private final int leadCount;
    private final String assistName;
    private final int assistCount;
    private final Map<String, Integer> herbRequirements;

    DanRecipeBlueprint(
            String danName,
            String mainName,
            int mainCount,
            String leadName,
            int leadCount,
            String assistName,
            int assistCount
    ) {
        this.danName = Objects.requireNonNull(danName, "danName");
        this.mainName = Objects.requireNonNull(mainName, "mainName");
        this.mainCount = mainCount;
        this.leadName = Objects.requireNonNull(leadName, "leadName");
        this.leadCount = leadCount;
        this.assistName = Objects.requireNonNull(assistName, "assistName");
        this.assistCount = assistCount;
        Map<String, Integer> requirements = new LinkedHashMap<>();
        requirements.merge(mainName, mainCount, Integer::sum);
        requirements.merge(leadName, leadCount, Integer::sum);
        requirements.merge(assistName, assistCount, Integer::sum);
        this.herbRequirements = Collections.unmodifiableMap(requirements);
    }

    String getDanName() {
        return danName;
    }

    String getMainName() {
        return mainName;
    }

    int getMainCount() {
        return mainCount;
    }

    String getLeadName() {
        return leadName;
    }

    int getLeadCount() {
        return leadCount;
    }

    String getAssistName() {
        return assistName;
    }

    int getAssistCount() {
        return assistCount;
    }

    int getTotalCount() {
        return mainCount + leadCount + assistCount;
    }

    Map<String, Integer> getHerbRequirements() {
        return herbRequirements;
    }

    String signature() {
        return String.join("|",
                danName,
                mainName, String.valueOf(mainCount),
                leadName, String.valueOf(leadCount),
                assistName, String.valueOf(assistCount));
    }
}
