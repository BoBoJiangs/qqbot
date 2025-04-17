//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
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

@Component
public class DanCalculator {
    private static final Logger logger = LoggerFactory.getLogger(DanCalculator.class);
    static List<Herb> herbs = new ArrayList<>();
    static List<Dan> sortedDans = new ArrayList<>();
    public static Map<String, Integer> herbPrices = new LinkedHashMap();
    public static Map<String, Integer> danMarketValues = new LinkedHashMap();
    public static Map<String, Integer> danAlchemyValues = new LinkedHashMap();
    public Config config = new Config();
//    private static String MAKE_DAN = "极品创世丹&混沌丹&创世丹&极品混沌丹&极品创世丹&九天蕴仙丹&金仙造化丹&大道归一丹&菩提证道丹&太清玉液丹";
    public static final Set<String> MAKE_DAN_SET = new HashSet(Arrays.asList("金仙破厄丹", "太乙炼髓丹", "混沌丹", "创世丹", "极品混沌丹", "极品创世丹", "九天蕴仙丹", "金仙造化丹", "大道归一丹", "菩提证道丹", "太清玉液丹", "太一仙丸", "无涯鬼丸", "道源丹", "六阳长生丹", "太乙碧莹丹", "天元神丹", "天尘丹", "魇龙之血"));
    public static  String targetDir = "./";
    private static final ForkJoinPool customPool = new ForkJoinPool(20);

    public DanCalculator() {
//        targetDir = JarPathHelper.getJarDir();
        customPool.submit(new Runnable() {
            public void run() {
                DanCalculator.this.loadOrCreateConfig();
                DanCalculator.this.loadData();
                DanCalculator.this.calculateAllDans();
                DanCalculator.this.addAutoBuyHerbs();
            }
        });
    }

//    public static void main(String[] args) throws Exception {
//        DanCalculator danCalculator = new DanCalculator();
//        config = new Config();
//        danCalculator.loadData();
//        danCalculator.calculateAllDans();
////        parseRecipes();
//    }

    public void addAutoBuyHerbs() {
        if (config != null && config.getAlchemyQQ() != null) {
            List<String> lines = new ArrayList();
            Map productMap = (Map) AutoBuyHerbs.AUTO_BUY_HERBS.computeIfAbsent(config.getAlchemyQQ(), (k) -> {
                return new ConcurrentHashMap();
            });

            try {
                BufferedReader reader = new BufferedReader(new FileReader(targetDir+"properties/药材价格.txt"));

                try {
                    int i = 0;

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            lines.add(line);
                            String[] parts = line.split("\\s+", 2);
                            if (parts.length == 2) {
                                ProductPrice productPrice = new ProductPrice();
                                productPrice.setName(parts[1].trim());
                                productPrice.setPrice(Integer.parseInt(parts[0].trim()));
                                productPrice.setTime(LocalDateTime.now());
                                productPrice.setId((long) (i++));
                                productMap.put(productPrice.getName(), productPrice);
                            }
                        }
                    }
                    logger.info("自动添加到药材采购列表={}", productMap.size());
                } catch (Throwable var8) {
                }

                reader.close();
            } catch (Exception var9) {
            }
        }

    }

    public Config getConfig() {

        return config;
    }



    public void loadOrCreateConfig() {
        Path configFile = Paths.get(targetDir+"炼丹配置.txt");

        try {
            if (Files.exists(configFile, new LinkOption[0])) {
                logger.info("配置文件存在，正在读取...");
                this.readConfig();
            } else {
                logger.info("配置文件不存在，创建并写入默认配置...");
                config = new Config();
                this.saveConfig(config);
            }
        } catch (Exception e) {
            logger.info("配置文件操作失败{}", e.getMessage());
        }

    }

    private void readConfig() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(targetDir+"炼丹配置.txt"));

            try {
                StringBuilder jsonStr = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null) {
                    jsonStr.append(line);
                }

                if(StringUtils.isNotBlank(jsonStr)){
                    config = (Config) JSON.parseObject(jsonStr.toString(), Config.class);
                    logger.info("配置读取成功！{}", JSON.toJSONString(config));
                }



            } catch (Throwable var4) {
            }

            reader.close();
        } catch (Exception var5) {
            config = new Config();
            logger.info("读取配置文件失败!");
        }

    }

    public void saveConfig(Config config) {
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetDir+"炼丹配置.txt"), StandardCharsets.UTF_8));

            try {
                String jsonStr = JSON.toJSONString(config);
                writer.write(jsonStr);
                writer.flush();
                logger.info("配置保存成功!{}", jsonStr);
            } catch (Throwable var4) {
            }

            writer.close();
        } catch (Exception var5) {
            System.err.println("写入失败: ");
        }

    }

    public void loadData() {
        try {
            this.loadElixirProperties();
            this.loadDanMarketData();
            this.loadDanAlchemyData();
            this.loadHerbPricesData();
            this.bindAdditionalData();

        } catch (Exception e) {
        }
    }

    public void parseRecipes(String text, Group group) throws IOException {

        Pattern textPattern = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
        Matcher textMatcher = textPattern.matcher(text);
        String recipeName = "";
        if (textMatcher.find()) {
            recipeName = textMatcher.group().substring(3);
        }

        Pattern numberPattern = Pattern.compile("\\d+");
        Matcher numberMatcher = numberPattern.matcher(text);
        int danNum = 6;
        if (numberMatcher.find()) {
            danNum = Integer.parseInt(numberMatcher.group());
        }

        String fileContent = this.readFileContent(targetDir+"properties/丹方查询.txt");
        fileContent = fileContent.replaceFirst("\n", "");

        // 解析丹方
        Map<String, String> recipeMap = parseAlchemyRecipes(fileContent);

        String recipes = getRecipeByName(recipeName, recipeMap);

        int marketValue;
        if (MAKE_DAN_SET.contains(recipeName)) {
            marketValue = danMarketValues.getOrDefault(recipeName, 0);
            if (marketValue <= 500) {
                marketValue = (int) (marketValue * 0.95 * danNum);
            } else if (marketValue <= 1000) {
                marketValue = (int) (marketValue * 0.90 * danNum);
            } else if (marketValue <= 1500) {
                marketValue = (int) (marketValue * 0.85 * danNum);
            } else if (marketValue <= 2000) {
                marketValue = (int) (marketValue * 0.80 * danNum);
            } else {
                marketValue = (int) (marketValue * 0.70 * danNum);
            }
        } else {
            marketValue = danAlchemyValues.getOrDefault(recipeName, 0) * danNum;
        }

        if (!recipes.isEmpty()&&!recipes.contains("未找到对应的丹方记录")) {
            String[] recipeArray = recipes.split("\n");
//            List<String> result = recipeArray.size() > 30 ? recipeArray.subList(0, 30) : recipes;
            StringBuilder sb = new StringBuilder();
            for (String recipe : recipeArray) {
                if (recipe.endsWith("配方")) {
                    if(MAKE_DAN_SET.contains(recipeName)){
                        sb.append(recipeName).append(" 坊市价格："+danMarketValues.getOrDefault(recipeName, 0)+"万/个").append("\n\n");
                    }else{
                        sb.append(recipeName).append(" 炼金价格："+danAlchemyValues.getOrDefault(recipeName, 0)+"万/个").append("\n\n");
                    }
                    continue;
                }
                recipe = recipe.replaceAll("-", "");
                String[] parts = recipe.split(" ");
                int price = 0;
                for (String part : parts) {

                    if (part.startsWith("花费")) {
                        String spendStr = part.substring(2); // 去掉 "花费" 前缀
                        price = marketValue - Integer.parseInt(spendStr); // 转换为整数
                    }

                }
                sb.append(recipe).append(" 收益").append(price).append(" ").append(danNum).append("丹").append("\n\n");
            }
            group.sendMessage((new MessageChain()).text(sb.toString()));
//            System.out.println(sb); // 输出每个丹方
        }
    }

    public String getRecipeByName(String recipeName, Map<String, String> recipeMap) {
        return (String) recipeMap.getOrDefault(recipeName, "未找到对应的丹方记录");
    }

    public String readFileContent(String filePath) throws IOException {
        StringBuilder content = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));

        String line;
        try {
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (Throwable var8) {
            try {
                reader.close();
            } catch (Throwable var7) {
            }
        }

        reader.close();
        return content.toString();
    }

    public Map<String, String> parseAlchemyRecipes(String fileContent) {
        Map<String, String> recipeMap = new HashMap();
        String[] recipes = fileContent.split("\n\n");
        String[] var3 = recipes;
        int var4 = recipes.length;

        for (int var5 = 0; var5 < var4; ++var5) {
            String recipe = var3[var5];
            if (!recipe.trim().isEmpty()) {
                String[] lines = recipe.split("\n");
                String recipeName = lines[0].split(" ")[0];
                recipeMap.put(recipeName, recipe);
            }
        }

        return recipeMap;
    }

    public void loadElixirProperties() throws Exception {
        String resourcePath = targetDir+"properties/elixirproperties.txt";

        try {
            BufferedReader br = new BufferedReader(new FileReader(resourcePath));

            try {
                List<Dan> dans = new ArrayList();
                boolean isHerbSection = false;

                while (true) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("-----药材列表-----")) {
                            isHerbSection = true;
                        } else if (line.startsWith("-----丹药列表-----")) {
                            isHerbSection = false;
                        } else if (isHerbSection && !line.trim().isEmpty()) {
                            herbs.add(new Herb(line.split("\t")));
                        } else if (!line.trim().isEmpty()) {
                            dans.add(new Dan(line.split("\t")));
                        }
                    }

                    sortedDans = (List) dans.stream().sorted().collect(Collectors.toList());
                    System.out.println("==========" + sortedDans.size());
                    br.close();
                    break;
                }
            } catch (Throwable var7) {
                try {
                    br.close();
                } catch (Throwable var6) {
                }
            }

            br.close();
        } catch (Exception var8) {
        }

    }

    public void loadDanMarketData() throws Exception {
        this.loadTxtFile(targetDir+"properties/丹药坊市价值.txt", (parts) -> {
            int value = Integer.parseInt(parts[0]);
            danMarketValues.put(parts[1], value);
        });
    }

    public void loadDanAlchemyData() throws Exception {
        this.loadTxtFile(targetDir+"properties/丹药炼金价值.txt", (parts) -> {
            int value = Integer.parseInt(parts[0]);
            danAlchemyValues.put(parts[1], value);
        });
    }

    public void loadHerbPricesData() throws Exception {
        this.loadTxtFile(targetDir+"properties/药材价格.txt", (parts) -> {
            int value = Integer.parseInt(parts[0]);
            herbPrices.put(parts[1], value + 0);
        });
    }

    private void loadTxtFile(String filename, Consumer<String[]> processor) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(filename));

        String line;
        try {
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    processor.accept(parts);
                }
            }
        } catch (Throwable var6) {
            System.out.println("读取文件失败 ");
        }

        br.close();
    }

    public void bindAdditionalData() {
        // 绑定丹药附加属性
        for (Dan dan : sortedDans) {
            dan.marketValue = danMarketValues.getOrDefault(dan.name, 0);
            dan.alchemyValue = danAlchemyValues.getOrDefault(dan.name, 0);
        }
        // 绑定药材价格
        for (Herb herb : herbs) {
            herb.price = herbPrices.getOrDefault(herb.name, 0);
        }

    }



    public void calculateAllDans() {
        Map<Dan, Set<String>> danRecipes = new LinkedHashMap<>();

        // 使用 try-with-resources 自动关闭资源
        String targetFilePath = targetDir+"炼丹配方.txt";
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetFilePath), StandardCharsets.UTF_8))) {

            for (Herb main : herbs) {
                if (main.price == 0) continue;

                for (Herb lead : herbs) {
                    if (lead.price == 0 || !this.checkBalance(main, lead)) continue;

                    for (Herb assist : herbs) {
                        if (assist.price == 0) continue;

                        List<RecipeResult> resultList = this.findHighestDan(main, lead, assist);
                        for (RecipeResult result : resultList) {
                            String recipe = this.formatRecipe(main, lead, assist,
                                    result.mainCount, result.leadCount, result.assistCount,
                                    result.spend, (result.alchemyValue - result.spend), (result.marketValue - result.spend),result.dan.name);
                            danRecipes.computeIfAbsent(result.dan, k -> new HashSet<>()).add(recipe);
                        }
                    }
                }
            }

            // 写入结果
            List<String> sortedRecipes = new ArrayList<>();
            writer.write(System.lineSeparator() +  (config.isAlchemy()?"炼金丹配方":"坊市丹配方") + System.lineSeparator());
            sortedDans.forEach(dan -> {
                try {
//                    writer.write(System.lineSeparator() + dan.name + " 配方" + System.lineSeparator());

                    // 获取当前丹方的配方集合
                    Set<String> recipes = danRecipes.getOrDefault(dan, Collections.emptySet());
                    // 将 Set 转换为 List 并排序
//                    List<String> sortedRecipes = new ArrayList<>(recipes);
                    sortedRecipes.addAll(new ArrayList<>(recipes));


                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // 自定义排序规则：按 "花费" 字段升序排序
            Collections.sort(sortedRecipes, (r1, r2) -> {
                int spend1 = extractSpendFromRecipe(r1); // 提取 r1 的花费
                int spend2 = extractSpendFromRecipe(r2); // 提取 r2 的花费
                return Integer.compare(spend2, spend1); // 升序排序
            });


            // 写入排序后的配方
            for (String recipe : sortedRecipes) {
                if (!StringUtils.isBlank(recipe)) {
                    writer.write(recipe + System.lineSeparator());
                }
            }
            writer.flush();

            System.out.println("配方已成功生成至：" + new File(targetFilePath).getAbsolutePath());
        } catch (Exception e) {
            System.err.println("文件写入失败：" + e.getMessage());
            e.printStackTrace();
        }
    }




    private int extractSpendFromRecipe(String recipe) {
        //"主药尘磊岩麟果-20&1140 药引七彩月兰-1&570 辅药地龙干-20&440 花费32170 炼金收益2700 坊市收益33054 6丹"
        String[] parts = recipe.split(" ");
//        int price1 = 0;
//        int price2 = 0;
        for (String part : parts) {

//            if (part.startsWith("花费")) {
//                String spendStr = part.substring(2); // 去掉 "花费" 前缀
//                price1 =  Integer.parseInt(spendStr); // 转换为整数
//            }

            if(part.startsWith("坊市收益")){
                String spendStr = part.substring(4); // 去掉 "坊市收益" 前缀
                return Integer.parseInt(spendStr); // 转换为整数
            }

            if (part.startsWith("炼金收益")) {
                String spendStr = part.substring(4); // 去掉 "炼金收益" 前缀
                return Integer.parseInt(spendStr); // 转换为整数
            }

        }
        return 0;
    }

    boolean checkBalance(Herb main, Herb lead) {
        if (main.mainAttr1Type.startsWith("性寒") && lead.leadAttrType.startsWith("性热")) {
            return true;
        } else if (main.mainAttr1Type.startsWith("性热") && lead.leadAttrType.startsWith("性寒")) {
            return true;
        } else {
            return main.mainAttr1Type.startsWith("性平") && lead.leadAttrType.startsWith("性平");
        }
    }

//    List<RecipeResult> findHighestDan(Herb main, Herb lead, Herb assist) {
//        List<RecipeResult> recipeResultList = new ArrayList();
//        Iterator var4 = sortedDans.iterator();
//
//        while (true) {
//            Dan dan;
//            int mainCount;
//            int assistCount;
//            boolean valid;
//            int leadCount;
//            int spend;
//            int alchemyValue;
//            int marketValue;
//            while (true) {
//                do {
//                    while (true) {
//                        if (!var4.hasNext()) {
//                            return recipeResultList;
//                        }
//
//                        dan = (Dan) var4.next();
//                        mainCount = 0;
//                        assistCount = 0;
//                        valid = true;
//                        Iterator var10 = dan.requirements.entrySet().iterator();
//
//                        while (var10.hasNext()) {
//                            Map.Entry<String, Integer> req = (Map.Entry) var10.next();
//                            String type = (String) req.getKey();
//                            int needed = (Integer) req.getValue();
//                            int per;
//                            if (main.mainAttr2Type.equals(type)) {
//                                per = main.mainAttr2Value;
//                                if (per == 0) {
//                                    valid = false;
//                                    break;
//                                }
//
//                                mainCount = Math.max(mainCount, (int) Math.ceil((double) needed / (double) per));
//                            } else {
//                                if (!assist.assistAttrType.equals(type)) {
//                                    valid = false;
//                                    break;
//                                }
//
//                                per = assist.assistAttrValue;
//                                if (per == 0) {
//                                    valid = false;
//                                    break;
//                                }
//
//                                assistCount = Math.max(assistCount, (int) Math.ceil((double) needed / (double) per));
//                            }
//                        }
//
//                        if (main.mainAttr1Type.equals("性平")) {
//                            leadCount = 1;
//                            break;
//                        }
//
//                        spend = main.mainAttr1Value * mainCount;
//                        if (spend >= lead.leadAttrValue) {
//                            leadCount = spend / lead.leadAttrValue;
//                            if (spend != leadCount * lead.leadAttrValue) {
//                                continue;
//                            }
//                            break;
//                        }
//                    }
//
//                    spend = mainCount * main.price + leadCount * lead.price + assistCount * assist.price;
//                    alchemyValue = dan.alchemyValue * config.getDanNumber();
//                    if (dan.marketValue <= 500) {
//                        marketValue = (int) ((double) dan.marketValue * 0.95 * (double) config.getDanNumber());
//                    } else if (dan.marketValue <= 1000) {
//                        marketValue = (int) ((double) dan.marketValue * 0.9 * (double) config.getDanNumber());
//                    } else if (dan.marketValue <= 1500) {
//                        marketValue = (int) ((double) dan.marketValue * 0.85 * (double) config.getDanNumber());
//                    } else if (dan.marketValue <= 2000) {
//                        marketValue = (int) ((double) dan.marketValue * 0.8 * (double) config.getDanNumber());
//                    } else {
//                        marketValue = (int) ((double) dan.marketValue * 0.7 * (double) config.getDanNumber());
//                    }
//                } while (spend > alchemyValue - 0 && spend > marketValue - 0);
//
//                if (config.isAlchemy()) {
//                    if (spend > alchemyValue - config.getAlchemyNumber()) {
//                        continue;
//                    }
//                    break;
//                } else if (("&" + config.getMakeName() + "&").contains("&" + config.getMakeName() + "&") && spend <= marketValue - config.getMakeNumber()) {
//                    break;
//                }
//            }
//
//            if (valid && mainCount + leadCount + assistCount < 100) {
//                recipeResultList.add(new RecipeResult(dan, mainCount, leadCount, assistCount, spend, alchemyValue, marketValue));
//            }
//        }
//    }

    List<RecipeResult> findHighestDan(Herb main, Herb lead, Herb assist) {
        List<RecipeResult> recipeResultList = new ArrayList<>();
        for (Dan dan : sortedDans) {

            int mainCount = 0;
            int leadCount = 0;
            int assistCount = 0;
            boolean valid = true;

            for (Map.Entry<String, Integer> req : dan.requirements.entrySet()) {
                String type = req.getKey();
                int needed = req.getValue();

                if (main.mainAttr2Type.equals(type)) {
                    int per = main.mainAttr2Value;
                    if (per == 0) {
                        valid = false;
                        break;
                    }
                    mainCount = Math.max(mainCount, (int) Math.ceil((double) needed / per));
                } else if (assist.assistAttrType.equals(type)) {
                    int per = assist.assistAttrValue;
                    if (per == 0 || per/needed >= 2) {
                        valid = false;
                        break;
                    }
                    assistCount = Math.max(assistCount, (int) Math.ceil((double) needed / per));
                } else {
                    valid = false;
                    break;
                }
            }

            if (main.mainAttr1Type.equals("性平")){
                leadCount = 1;
            }else {
                int leadNumber = main.mainAttr1Value * mainCount;
                if (leadNumber < lead.leadAttrValue){
                    continue;
                }
                leadCount = leadNumber/lead.leadAttrValue;
                if (leadNumber != leadCount*lead.leadAttrValue){
                    continue;
                }
            }
            //花费
            int spend = mainCount * main.price + leadCount * lead.price + assistCount * assist.price;
            //炼金收益

            int alchemyValue = dan.alchemyValue * config.getDanNumber();
            //坊市收益
            // 0|500w|1000w|1500w|2000w|无限　
            // 5％|-10％‐|-15％-|-20％‐|-30％‐|手续费
            int marketValue;
            if (dan.marketValue <= 500) {
                marketValue = (int) ((double) dan.marketValue * 0.95 * (double) config.getDanNumber());
            } else if (dan.marketValue <= 1000) {
                marketValue = (int) ((double) dan.marketValue * 0.9 * (double) config.getDanNumber());
            } else if (dan.marketValue <= 1500) {
                marketValue = (int) ((double) dan.marketValue * 0.85 * (double) config.getDanNumber());
            } else if (dan.marketValue <= 2000) {
                marketValue = (int) ((double) dan.marketValue * 0.8 * (double) config.getDanNumber());
            } else {
                marketValue = (int) ((double) dan.marketValue * 0.7 * (double) config.getDanNumber());
            }
            if (config.isAlchemy()) {
                if (spend > alchemyValue - config.getAlchemyNumber()) {
                    continue;
                }
            } else if (("&" + config.getMakeName() + "&").contains("&" + config.getMakeName() + "&") && spend <= marketValue - config.getMakeNumber()) {
                if (spend > alchemyValue - config.getAlchemyNumber() && spend > marketValue - config.getMakeNumber()){
                    continue;
                }
            }


            if (valid && (mainCount + leadCount + assistCount) < 100) {
                recipeResultList.add(new RecipeResult(dan, mainCount, leadCount, assistCount, spend, alchemyValue, marketValue));

            }
        }
        return recipeResultList;
    }

     String formatRecipe(Herb main, Herb lead, Herb assist, int mainCount, int leadCount, int assistCount, int spend, int alchemyValue, int marketValue,String name) {
         if(config.isAlchemy()){
             return String.format("主药%s-%d&%d 药引%s-%d&%d 辅药%s-%d&%d 花费%d 炼金收益%d %d丹 %s",
                     main.name, mainCount,main.price,
                     lead.name, leadCount,lead.price,
                     assist.name, assistCount, assist.price,
                     spend, alchemyValue, config.getDanNumber(), name);
         }else{
             return String.format("主药%s-%d&%d 药引%s-%d&%d 辅药%s-%d&%d 花费%d 坊市收益%d %d丹 %s",
                     main.name, mainCount,main.price,
                     lead.name, leadCount,lead.price,
                     assist.name, assistCount, assist.price,
                     spend, marketValue, config.getDanNumber(), name);
         }
    }
}
