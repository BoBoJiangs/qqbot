package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.annotation.OnQQConnected;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ForwardNodeMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static top.sshh.qqbot.constant.Constant.MAKE_DAN_SET;
import static top.sshh.qqbot.constant.Constant.targetDir;

@Component
public class DanCalculator {
    private static final Logger logger = LoggerFactory.getLogger(DanCalculator.class);
    private static final ForkJoinPool customPool = new ForkJoinPool(20);

    private List<Herb> herbs = new ArrayList<>();
    private List<Dan> sortedDans = new ArrayList<>();
    private Map<String, Integer> herbPrices = new LinkedHashMap<>();
    private Map<String, Integer> danMarketValues = new LinkedHashMap<>();
    private Map<String, Integer> danAlchemyValues = new LinkedHashMap<>();
//    public Config config = new Config();
    public Map<Long, Config> configMap = new ConcurrentHashMap<>();

    // ======================= 事件入口 =======================
    @OnQQConnected
    public void onConnected(Bot bot) {
        BotConfig botConfig = bot.getBotConfig();
        if (!botConfig.isEnableAlchemy()) return;

        customPool.submit(() -> {
            loadOrCreateConfig(bot.getBotId());
//            loadData(bot.getBotId());
//            calculateAllDans(bot.getBotId());
            addAutoBuyHerbs(bot.getBotId());
        });
    }

    // ======================= 配置文件 =======================
    public void loadOrCreateConfig(Long botId) {
        Path configFile = Paths.get(String.valueOf(botId), "炼丹配置.txt");
        try {
            if (Files.exists(configFile)) {
                logger.info("配置文件存在，正在读取...");
                readConfig(botId);
            } else {
                logger.info("配置文件不存在，创建默认配置...");
                Config config = new Config();
                configMap.put(botId, config);
                saveConfig(config, botId);
            }
        } catch (Exception e) {
            logger.error("配置文件操作失败: {}", e.getMessage(), e);
        }
    }

    private void readConfig(Long botId) {
        Path configFile = Paths.get(String.valueOf(botId), "炼丹配置.txt");
        try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            String jsonStr = reader.lines().collect(Collectors.joining());
            if (StringUtils.isNotBlank(jsonStr)) {
                Config config = JSON.parseObject(jsonStr, Config.class);
                configMap.put(botId, config);
                logger.info("配置读取成功：{}", JSON.toJSONString(config));
            }
        } catch (IOException e) {
            logger.error("读取配置文件失败!", e);
            Config config = new Config();
            configMap.put(botId, config);
        }
    }

    public void saveConfig(Config config, Long botId) {
        Path filePath = Paths.get(String.valueOf(botId), "炼丹配置.txt");
        try {
            Files.createDirectories(filePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                writer.write(JSON.toJSONString(config));
                logger.info("配置保存成功！");
            }
        } catch (IOException e) {
            logger.error("写入配置文件失败!", e);
        }
    }

    public Config getConfig(Long botId) {
        return configMap.get(botId);
    }

    // ======================= 自动购买药材 =======================
    public void addAutoBuyHerbs(Long botId) {
        Config config = getConfig(botId);
        if (config == null || config.getAlchemyQQ() == null) return;

        Map<String, ProductPrice> productMap = AutoBuyHerbs.AUTO_BUY_HERBS
                .computeIfAbsent(config.getAlchemyQQ(), k -> new ConcurrentHashMap<>());

        Path filePath = Paths.get(String.valueOf(botId), "药材价格.txt");
        if (!Files.exists(filePath)) return;

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int id = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2) continue;

                ProductPrice productPrice = new ProductPrice();
                productPrice.setPrice(Integer.parseInt(parts[0].trim()));
                productPrice.setName(parts[1].trim());
                productPrice.setTime(LocalDateTime.now());
                productPrice.setId((long) id++);
                productMap.put(productPrice.getName(), productPrice);
            }
            logger.info("自动添加药材到购买列表，数量={}", productMap.size());
        } catch (IOException e) {
            logger.error("读取药材价格失败", e);
        }
    }

    // ======================= 数据加载 =======================
    public void loadData(Long botId) {
        try {
            loadElixirProperties();
            loadDanMarketData();
            loadDanAlchemyData();
            loadHerbPricesData(botId);
            bindAdditionalData();
        } catch (Exception e) {
            AutoAlchemyTask.isCreateDan = false;
            logger.error("加载数据失败", e);
        }
    }

    private void loadElixirProperties() throws IOException {
        Path resourcePath = Paths.get(targetDir, "properties", "elixirproperties.txt");
        if (!Files.exists(resourcePath)) return;

        herbs.clear();
        List<Dan> dans = new ArrayList<>();
        boolean isHerbSection = false;

        try (BufferedReader br = Files.newBufferedReader(resourcePath)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("-----药材列表-----")) {
                    isHerbSection = true;
                    continue;
                } else if (line.startsWith("-----丹药列表-----")) {
                    isHerbSection = false;
                    continue;
                }

                if (line.trim().isEmpty()) continue;

                if (isHerbSection) {
                    herbs.add(new Herb(line.split("\t")));
                } else {
                    dans.add(new Dan(line.split("\t")));
                }
            }
        }

        sortedDans = dans.stream().sorted().collect(Collectors.toList());
        logger.info("加载丹药数据，数量={}", sortedDans.size());
    }

    private void loadDanMarketData() throws IOException {
        loadTxtFile(Paths.get(targetDir, "properties", "丹药坊市价值.txt"), parts -> {
            danMarketValues.put(parts[1], Integer.parseInt(parts[0]));
        });
    }

    private void loadDanAlchemyData() throws IOException {
        loadTxtFile(Paths.get(targetDir, "properties", "丹药炼金价值.txt"), parts -> {
            danAlchemyValues.put(parts[1], Integer.parseInt(parts[0]));
        });
    }

    private void loadHerbPricesData(Long botId) throws IOException {
        herbPrices.clear();
        loadTxtFile(Paths.get(String.valueOf(botId), "药材价格.txt"), parts -> {
            herbPrices.put(parts[1], Integer.parseInt(parts[0]));
        });
    }

    private void loadTxtFile(Path filePath, Consumer<String[]> processor) throws IOException {
        if (!Files.exists(filePath)) return;
        try (BufferedReader br = Files.newBufferedReader(filePath)) {
            br.lines()
                    .map(line -> line.split("\\s+"))
                    .filter(parts -> parts.length >= 2)
                    .forEach(processor);
        }
    }

    private void bindAdditionalData() {
        // 丹药附加属性
        sortedDans.forEach(dan -> {
            dan.marketValue = danMarketValues.getOrDefault(dan.name, 0);
            dan.alchemyValue = danAlchemyValues.getOrDefault(dan.name, 0);
        });

        // 药材价格
        herbs.forEach(herb -> herb.price = herbPrices.getOrDefault(herb.name, 0));
    }

    // ======================= 配方解析 =======================
    public void parseRecipes(String text, Group group, Bot bot) throws IOException {
        Pattern textPattern = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
        Matcher textMatcher = textPattern.matcher(text);
        String recipeName = textMatcher.find() ? textMatcher.group().substring(3) : "";

        Pattern numberPattern = Pattern.compile("\\d+");
        Matcher numberMatcher = numberPattern.matcher(text);
        int danNum = numberMatcher.find() ? Integer.parseInt(numberMatcher.group()) : 6;

        String fileContent = readFileContent(targetDir + "properties/丹方查询.txt").replaceFirst("\n", "");
        Map<String, String> recipeMap = parseAlchemyRecipes(fileContent);
        String recipes = recipeMap.getOrDefault(recipeName, "未找到对应的丹方记录");

        if (!recipes.contains("未找到对应的丹方记录")) {
            StringBuilder sb = new StringBuilder();
            for (String recipe : recipes.split("\n")) {
                if (recipe.endsWith("配方")) {
                    if (MAKE_DAN_SET.contains(recipeName)) {
                        sb.append(recipeName)
                                .append(" 坊市价格：")
                                .append(danMarketValues.getOrDefault(recipeName, 0))
                                .append("万/个\n\n");
                    } else {
                        sb.append(recipeName)
                                .append(" 炼金价格：")
                                .append(danAlchemyValues.getOrDefault(recipeName, 0))
                                .append("万/个\n\n");
                    }
                    continue;
                }

                recipe = recipe.replaceAll("-", "");
                String[] parts = recipe.split(" ");
                int price = 0;
                for (String part : parts) {
                    if (part.startsWith("花费")) {
                        price = danMarketValues.getOrDefault(recipeName, 0) - Integer.parseInt(part.substring(2));
                    }
                }
                sb.append(recipe).append(" 收益").append(price).append(" ").append(danNum).append("丹\n\n");
            }

            List<ForwardNodeMessage> forwardNodes = new ArrayList<>();
            forwardNodes.add(new ForwardNodeMessage(String.valueOf(bot.getBotId()), "丹方查询助手",
                    new MessageChain().text(sb.toString())));
            forwardNodes.add(new ForwardNodeMessage(String.valueOf(bot.getBotId()), "丹方查询助手",
                    new MessageChain().text("ps：仅输出利润前20条！\n药材价格仅供参考，以实际坊市价格为准！")));
            group.sendGroupForwardMessage(forwardNodes);
        }
    }

    private Map<String, String> parseAlchemyRecipes(String fileContent) {
        Map<String, String> recipeMap = new HashMap<>();
        for (String recipe : fileContent.split("\n\n")) {
            if (recipe.trim().isEmpty()) continue;
            String[] lines = recipe.split("\n");
            recipeMap.put(lines[0].split(" ")[0], recipe);
        }
        return recipeMap;
    }

    private String readFileContent(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return "";

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    // ======================= 配方计算 =======================
    public void calculateAllDans(Long botId) {
        Map<Dan, Set<String>> danRecipes = new LinkedHashMap<>();
        String targetFilePath = botId + "/炼丹配方.txt";
        Config config = getConfig(botId);
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(targetFilePath), StandardCharsets.UTF_8)) {
            // 三重循环遍历组合（可以考虑 parallelStream 提升性能）
            herbs.parallelStream()
                    .filter(main -> main.price > 0)
                    .forEach(main -> {
                        herbs.stream()
                                .filter(lead -> lead.price > 0 && checkBalance(main, lead))
                                .forEach(lead -> {
                                    herbs.stream()
                                            .filter(assist -> assist.price > 0)
                                            .forEach(assist -> {
                                                List<RecipeResult> resultList = findHighestDan(main, lead, assist,botId);
                                                for (RecipeResult result : resultList) {
                                                    String recipe = formatRecipe(main, lead, assist,
                                                            result.mainCount, result.leadCount, result.assistCount,
                                                            result.spend, (result.alchemyValue - result.spend), (result.marketValue - result.spend), result.dan.name,botId);
                                                    danRecipes.computeIfAbsent(result.dan, k -> new HashSet<>()).add(recipe);
                                                }
                                            });
                                });
                    });

            List<String> sortedRecipes = new ArrayList<>();
            writer.write(System.lineSeparator() +  (config.isAlchemy()?"炼金丹配方":"坊市丹配方") + System.lineSeparator());
            sortedDans.forEach(dan -> {
                Set<String> recipes = danRecipes.getOrDefault(dan, Collections.emptySet());
                sortedRecipes.addAll(recipes);
            });

            sortedRecipes.sort((r1, r2) -> Integer.compare(extractValue(r2), extractValue(r1)));
            for (String recipe : sortedRecipes) {
                if (!StringUtils.isBlank(recipe)) writer.write(recipe + System.lineSeparator());
            }

            logger.info("配方已生成至：{}", new File(targetFilePath).getAbsolutePath());
        } catch (IOException e) {
            logger.error("文件写入失败", e);
            AutoAlchemyTask.isCreateDan = true;
        }
    }

    private int extractValue(String recipe) {
        Pattern pattern = Pattern.compile("(坊市收益|炼金收益)(\\d+)");
        Matcher matcher = pattern.matcher(recipe);
        if (matcher.find()) return Integer.parseInt(matcher.group(2));
        return 0;
    }

    private boolean checkBalance(Herb main, Herb lead) {
        return (main.mainAttr1Type.startsWith("性寒") && lead.leadAttrType.startsWith("性热")) ||
                (main.mainAttr1Type.startsWith("性热") && lead.leadAttrType.startsWith("性寒")) ||
                (main.mainAttr1Type.startsWith("性平") && lead.leadAttrType.startsWith("性平"));
    }

    private List<RecipeResult> findHighestDan(Herb main, Herb lead, Herb assist,Long botId) {
        Config config = getConfig(botId);
        List<RecipeResult> recipeResultList = new ArrayList<>();
        for (Dan dan : sortedDans) {
            int mainCount = 0, leadCount = 0, assistCount = 0;
            boolean valid = true;

            for (Map.Entry<String, Integer> req : dan.requirements.entrySet()) {
                String type = req.getKey();
                int needed = req.getValue();

                if (main.mainAttr2Type.equals(type)) {
                    if (main.mainAttr2Value == 0) { valid = false; break; }
                    mainCount = Math.max(mainCount, (int) Math.ceil((double) needed / main.mainAttr2Value));
                } else if (assist.assistAttrType.equals(type)) {
                    if (assist.assistAttrValue == 0 || assist.assistAttrValue / needed >= 2) { valid = false; break; }
                    assistCount = Math.max(assistCount, (int) Math.ceil((double) needed / assist.assistAttrValue));
                } else valid = false;
            }

            if (main.mainAttr1Type.equals("性平")) leadCount = 1;
            else {
                int leadNumber = main.mainAttr1Value * mainCount;
                if (leadNumber < lead.leadAttrValue) continue;
                leadCount = leadNumber / lead.leadAttrValue;
                if (leadNumber != leadCount * lead.leadAttrValue) continue;
            }

            int spend = mainCount * main.price + leadCount * lead.price + assistCount * assist.price;
            int alchemyValue = dan.alchemyValue * config.getDanNumber();
            int marketValue;
            if (dan.marketValue <= 500) marketValue = (int) (dan.marketValue * 0.95 * config.getDanNumber());
            else if (dan.marketValue <= 1000) marketValue = (int) (dan.marketValue * 0.9 * config.getDanNumber());
            else if (dan.marketValue <= 1500) marketValue = (int) (dan.marketValue * 0.85 * config.getDanNumber());
            else if (dan.marketValue <= 2000) marketValue = (int) (dan.marketValue * 0.8 * config.getDanNumber());
            else marketValue = (int) (dan.marketValue * 0.7 * config.getDanNumber());

            if (config.isAlchemy() && spend > alchemyValue - config.getAlchemyNumber()) continue;
            if (!config.isAlchemy() && spend > marketValue - config.getMakeNumber()) continue;

            if (valid && mainCount + leadCount + assistCount < 100) {
                recipeResultList.add(new RecipeResult(dan, mainCount, leadCount, assistCount, spend, alchemyValue, marketValue));
            }
        }
        return recipeResultList;
    }

    private String formatRecipe(Herb main, Herb lead, Herb assist, int mainCount, int leadCount, int assistCount,
                                int spend, int alchemyValue, int marketValue, String name,Long botId) {
        Config config = getConfig(botId);
        if (config.isAlchemy()) {
            return String.format("主药%s-%d&%d 药引%s-%d&%d 辅药%s-%d&%d 花费%d 炼金收益%d %d丹 %s",
                    main.name, mainCount, main.price,
                    lead.name, leadCount, lead.price,
                    assist.name, assistCount, assist.price,
                    spend, alchemyValue, config.getDanNumber(), name);
        } else {
            return String.format("主药%s-%d&%d 药引%s-%d&%d 辅药%s-%d&%d 花费%d 坊市收益%d %d丹 %s",
                    main.name, mainCount, main.price,
                    lead.name, leadCount, lead.price,
                    assist.name, assistCount, assist.price,
                    spend, marketValue, config.getDanNumber(), name);
        }
    }
}
