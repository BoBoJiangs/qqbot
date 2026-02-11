package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DanCalculatorThresholdTest {

    @Test
    void nextAssistThreshold_matchesElixirProperties() {
        DanCalculator danCalculator = new DanCalculator();
        danCalculator.loadData(0L);

        int threshold = danCalculator.nextDanThresholdForAssistValueForTest("生息", 2816, "炼气", 2816);
        assertTrue(threshold > 2816);
        assertEquals(3072, threshold);
    }
}

