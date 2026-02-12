package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DanCalculatorPingLeadTest {

    @Test
    void pingLeadMustBeFromPingTxt() throws Exception {
        DanCalculator danCalculator = new DanCalculator();
        danCalculator.loadData(0L);

        Path pingPath = Path.of("properties", "性平.txt");
        List<String> lines = Files.readAllLines(pingPath, StandardCharsets.UTF_8);
        String first = lines.stream().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElseThrow();

        assertTrue(danCalculator.isPingLeadAllowed("性平", first));
        assertFalse(danCalculator.isPingLeadAllowed("性平", "不存在药引"));
        assertTrue(danCalculator.isPingLeadAllowed("性寒", "不存在药引"));
    }
}

