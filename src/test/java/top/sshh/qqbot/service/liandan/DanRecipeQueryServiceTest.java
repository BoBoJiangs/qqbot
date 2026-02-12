package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.service.ProductPriceResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DanRecipeQueryServiceTest {

    @Test
    void generateRecipeSignaturesForTest_usesRequestedDanName() throws Exception {
        DanRecipeQueryService service = new DanRecipeQueryService();
        ProductPriceResponse productPriceResponse = Mockito.mock(ProductPriceResponse.class);
        Mockito.when(productPriceResponse.getFirstByNameOrderByTimeDesc(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "productPriceResponse", productPriceResponse);

        List<String> signatures = service.generateRecipeSignaturesForTest("洗髓丹", 6);
        assertFalse(signatures.isEmpty());
        assertTrue(signatures.stream().allMatch(s -> s.startsWith("洗髓丹|")));
    }

    @Test
    void generateRecipeSignaturesForTest_doesNotCrossMatchDifferentDan() throws Exception {
        DanRecipeQueryService service = new DanRecipeQueryService();
        ProductPriceResponse productPriceResponse = Mockito.mock(ProductPriceResponse.class);
        Mockito.when(productPriceResponse.getFirstByNameOrderByTimeDesc(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "productPriceResponse", productPriceResponse);

        List<String> signatures = service.generateRecipeSignaturesForTest("极品创世丹", 6);
        assertTrue(signatures.stream().allMatch(s -> s.startsWith("极品创世丹|")));
    }

    @Test
    void assistProvidedMustBeLessThanNextDanThreshold() throws Exception {
        DanRecipeQueryService service = new DanRecipeQueryService();
        ProductPriceResponse productPriceResponse = Mockito.mock(ProductPriceResponse.class);
        Mockito.when(productPriceResponse.getFirstByNameOrderByTimeDesc(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "productPriceResponse", productPriceResponse);

        String text = service.generateRecipeTextForTest("极品创世丹", 6, 50);
        assertFalse(text.isBlank());
        assertTrue(text.contains("需求：生息2816"));
        assertTrue(text.contains("炼气2816"));
        assertFalse(text.contains("辅药森檀木6"));
    }

    @Test
    void pingLeadMustComeFromPingTxt() throws Exception {
        DanRecipeQueryService service = new DanRecipeQueryService();
        ProductPriceResponse productPriceResponse = Mockito.mock(ProductPriceResponse.class);
        Mockito.when(productPriceResponse.getFirstByNameOrderByTimeDesc(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "productPriceResponse", productPriceResponse);

        String text = service.generateRecipeTextForTest("洗髓丹", 6, 50);
        assertFalse(text.isBlank());
        assertFalse(text.contains("药引剑心竹"));
    }

    @Test
    void exportRecipeTextForManualReview() throws Exception {
        DanRecipeQueryService service = new DanRecipeQueryService();
        ProductPriceResponse productPriceResponse = Mockito.mock(ProductPriceResponse.class);
        Mockito.when(productPriceResponse.getFirstByNameOrderByTimeDesc(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "productPriceResponse", productPriceResponse);

        export(service, "洗髓丹", 6);
        export(service, "极品创世丹", 6);
    }

    @Test
    void parseQuery_supportsModeAndDefaultDanNum() throws Exception {
        DanRecipeQueryService service = new DanRecipeQueryService();
        ProductPriceResponse productPriceResponse = Mockito.mock(ProductPriceResponse.class);
        Mockito.when(productPriceResponse.getFirstByNameOrderByTimeDesc(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "productPriceResponse", productPriceResponse);
        service.generateRecipeSignaturesForTest("洗髓丹", 6);

        Object q1 = ReflectionTestUtils.invokeMethod(service, "parseQuery", "查丹方极品创世丹6 炼金丹");
        assertTrue(((String) ReflectionTestUtils.getField(q1, "danName")).contains("极品创世丹"));
        assertTrue(((int) ReflectionTestUtils.getField(q1, "danNum")) == 6);
        assertTrue(ReflectionTestUtils.getField(q1, "mode").toString().equals("ALCHEMY"));

        Object q2 = ReflectionTestUtils.invokeMethod(service, "parseQuery", "查丹方 极品创世丹 坊市丹");
        assertTrue(((int) ReflectionTestUtils.getField(q2, "danNum")) == 6);
        assertTrue(ReflectionTestUtils.getField(q2, "mode").toString().equals("MARKET"));
    }

    private static void export(DanRecipeQueryService service, String danName, int danNum) throws Exception {
        String text = service.generateRecipeTextForTest(danName, danNum, 20);
        assertFalse(text.isBlank());

        Path out = Path.of("target", "dan-recipe-output-" + danName + ".txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.out.println("丹方输出文件: " + out.toAbsolutePath());
        System.out.println(text);
    }
}
