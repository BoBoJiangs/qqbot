//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.apache.commons.lang3.StringUtils;
import top.sshh.qqbot.data.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DanCalculator {
    static List<Herb> herbs = new ArrayList();
    static List<Dan> sortedDans = new ArrayList();
    public static Map<String, Integer> herbPrices = new LinkedHashMap();
    public static Map<String, Integer> danMarketValues = new LinkedHashMap();
    public static Map<String, Integer> danAlchemyValues = new LinkedHashMap();
    public static Config config;
    private static String MAKE_DAN = "极品创世丹&混沌丹&创世丹&极品混沌丹&极品创世丹&九天蕴仙丹&金仙造化丹&大道归一丹&菩提证道丹&太清玉液丹";
    public static final String targetDir = "src/main/";
    private static final ForkJoinPool customPool = new ForkJoinPool(20);

    public DanCalculator() {
        customPool.submit(new Runnable() {
            public void run() {
                DanCalculator.this.loadOrCreateConfig();
                DanCalculator.this.loadData();
                DanCalculator.this.calculateAllDans();
                DanCalculator.this.addAutoBuyHerbs();
            }
        });
    }

    public void addAutoBuyHerbs() {
        System.out.println("getAlchemyQQ==" + config.getAlchemyQQ());
        if (config != null && config.getAlchemyQQ() != null) {
            List<String> lines = new ArrayList();
            Map productMap = (Map) AutoBuyHerbs.AUTO_BUY_HERBS.computeIfAbsent(config.getAlchemyQQ(), (k) -> {
                return new ConcurrentHashMap();
            });

            try {
                BufferedReader reader = new BufferedReader(new FileReader("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\properties\\药材价格.txt"));

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

                    System.out.println("productMap==" + productMap.size());
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

    public void setConfig(Config config) {
        DanCalculator.config = config;
    }

    public void loadOrCreateConfig() {
        Path configFile = Paths.get("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\炼丹配置.txt");

        try {
            if (Files.exists(configFile, new LinkOption[0])) {
                System.out.println("配置文件存在，正在读取...");
                this.readConfig();
            } else {
                System.out.println("配置文件不存在，创建并写入默认配置...");
                config = new Config();
                this.saveConfig(config);
            }
        } catch (Exception var3) {
            Exception e = var3;
            System.err.println("配置文件操作失败: " + e.getMessage());
        }

    }

    private void readConfig() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\炼丹配置.txt"));

            try {
                StringBuilder jsonStr = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null) {
                    jsonStr.append(line);
                }

                config = (Config) JSON.parseObject(jsonStr.toString(), Config.class);
                System.out.println("配置读取成功！" + JSON.toJSONString(config));
            } catch (Throwable var4) {
            }

            reader.close();
        } catch (Exception var5) {
            config = new Config();
            System.err.println("读取配置文件失败: ");
        }

    }

    public void saveConfig(Config config) {
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\炼丹配置.txt"), StandardCharsets.UTF_8));

            try {
                String jsonStr = JSON.toJSONString(config);
                System.out.println("配置保存成功！" + jsonStr);
                writer.write(jsonStr);
                writer.flush();
                System.out.println("配置保存成功！");
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

    public void parseRecipes(String recipeName, Group group) throws IOException {
        String fileContent = this.readFileContent("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\炼丹配方.txt");
        fileContent = fileContent.replaceFirst("\n", "");
        Map<String, String> recipeMap = this.parseAlchemyRecipes(fileContent);
        String recipe = this.getRecipeByName(recipeName, recipeMap).replaceAll("\n", "\n\n");
        group.sendMessage((new MessageChain()).text(recipe));
    }

    public String getRecipeByName(String recipeName, Map<String, String> recipeMap) {
        return (String) recipeMap.getOrDefault(recipeName, "未找到对应的丹方");
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
        String resourcePath = "C:\\Users\\Administrator\\Desktop\\修仙java脚本\\properties\\elixirproperties.txt";

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
        this.loadTxtFile("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\properties\\丹药坊市价值.txt", (parts) -> {
            int value = Integer.parseInt(parts[0]);
            danMarketValues.put(parts[1], value);
        });
    }

    public void loadDanAlchemyData() throws Exception {
        this.loadTxtFile("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\properties\\丹药炼金价值.txt", (parts) -> {
            int value = Integer.parseInt(parts[0]);
            danAlchemyValues.put(parts[1], value);
        });
    }

    public void loadHerbPricesData() throws Exception {
        this.loadTxtFile("C:\\Users\\Administrator\\Desktop\\修仙java脚本\\properties\\药材价格.txt", (parts) -> {
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
        Iterator var0;
        Dan dan;
        for (var0 = sortedDans.iterator(); var0.hasNext(); dan.alchemyValue = (Integer) danAlchemyValues.getOrDefault(dan.name, 0)) {
            dan = (Dan) var0.next();
            dan.marketValue = (Integer) danMarketValues.getOrDefault(dan.name, 0);
        }

        Herb herb;
        for (var0 = herbs.iterator(); var0.hasNext(); herb.price = (Integer) herbPrices.getOrDefault(herb.name, 0)) {
            herb = (Herb) var0.next();
        }

    }

    public void calculateAllDans() {
        Map<Dan, Set<String>> danRecipes = new LinkedHashMap();

        try {
            Path dirPath = Paths.get("C:\\Users\\Administrator\\Desktop\\修仙java脚本");
            if (!Files.exists(dirPath, new LinkOption[0])) {
                Files.createDirectories(dirPath);
            }

            String targetFilePath = "C:\\Users\\Administrator\\Desktop\\修仙java脚本\\炼丹配方.txt";
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetFilePath), StandardCharsets.UTF_8));
            Iterator var5 = herbs.iterator();

            label64:
            while (true) {
                Herb main;
                do {
                    if (!var5.hasNext()) {
                        StringBuilder sb = new StringBuilder();
                        sortedDans.forEach((dan) -> {
                            try {
                                writer.write(System.lineSeparator() + dan.name + " 配方" + System.lineSeparator());
                                Set<String> recipes = (Set) danRecipes.getOrDefault(dan, Collections.emptySet());
                                List<String> sortedRecipes = new ArrayList(recipes);
                                Collections.sort(sortedRecipes, (r1, r2) -> {
                                    int spend1 = this.extractSpendFromRecipe(r1);
                                    int spend2 = this.extractSpendFromRecipe(r2);
                                    return Integer.compare(spend1, spend2);
                                });

                                // 只取前 30 个元素
                                List<String> top20Recipes = sortedRecipes.stream()
                                        .limit(30)
                                        .collect(Collectors.toList());

                                Iterator var7 = top20Recipes.iterator();

                                while (var7.hasNext()) {
                                    String recipe = (String) var7.next();
                                    if (!StringUtils.isBlank(recipe)) {
                                        sb.append(recipe).append(System.lineSeparator()).append(System.lineSeparator());
                                        writer.write(recipe + System.lineSeparator());
                                    }
                                }

                                writer.flush();
                            } catch (Exception var9) {
                            }

                        });
                        System.out.println("配方已成功生成至：" + (new File(targetFilePath)).getAbsolutePath());
                        return;
                    }

                    main = (Herb) var5.next();
                } while (main.price == 0);

                Iterator var7 = herbs.iterator();

                label62:
                while (true) {
                    Herb lead;
                    do {
                        do {
                            if (!var7.hasNext()) {
                                continue label64;
                            }

                            lead = (Herb) var7.next();
                        } while (lead.price == 0);
                    } while (!this.checkBalance(main, lead));

                    Iterator var9 = herbs.iterator();

                    while (true) {
                        Herb assist;
                        do {
                            if (!var9.hasNext()) {
                                continue label62;
                            }

                            assist = (Herb) var9.next();
                        } while (assist.price == 0);

                        List<RecipeResult> resultList = this.findHighestDan(main, lead, assist);
                        Iterator var12 = resultList.iterator();

                        while (var12.hasNext()) {
                            RecipeResult result = (RecipeResult) var12.next();
                            String recipe = this.formatRecipe(main, lead, assist, result.mainCount, result.leadCount, result.assistCount, result.spend, result.alchemyValue, result.marketValue);
                            ((Set) danRecipes.computeIfAbsent(result.dan, (k) -> {
                                return new HashSet();
                            })).add(recipe);
                        }
                    }
                }
            }
        } catch (Exception var15) {
            System.err.println("文件写入失败：");
        }
    }

    private int extractSpendFromRecipe(String recipe) {
        String[] parts = recipe.split(" ");
        String[] var2 = parts;
        int var3 = parts.length;

        for (int var4 = 0; var4 < var3; ++var4) {
            String part = var2[var4];
            if (part.startsWith("花费")) {
                String spendStr = part.substring(2);
                return Integer.parseInt(spendStr);
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

    List<RecipeResult> findHighestDan(Herb main, Herb lead, Herb assist) {
        List<RecipeResult> recipeResultList = new ArrayList();
        Iterator var4 = sortedDans.iterator();

        while (true) {
            Dan dan;
            int mainCount;
            int assistCount;
            boolean valid;
            int leadCount;
            int spend;
            int alchemyValue;
            int marketValue;
            while (true) {
                do {
                    while (true) {
                        if (!var4.hasNext()) {
                            return recipeResultList;
                        }

                        dan = (Dan) var4.next();
                        mainCount = 0;
                        assistCount = 0;
                        valid = true;
                        Iterator var10 = dan.requirements.entrySet().iterator();

                        while (var10.hasNext()) {
                            Map.Entry<String, Integer> req = (Map.Entry) var10.next();
                            String type = (String) req.getKey();
                            int needed = (Integer) req.getValue();
                            int per;
                            if (main.mainAttr2Type.equals(type)) {
                                per = main.mainAttr2Value;
                                if (per == 0) {
                                    valid = false;
                                    break;
                                }

                                mainCount = Math.max(mainCount, (int) Math.ceil((double) needed / (double) per));
                            } else {
                                if (!assist.assistAttrType.equals(type)) {
                                    valid = false;
                                    break;
                                }

                                per = assist.assistAttrValue;
                                if (per == 0) {
                                    valid = false;
                                    break;
                                }

                                assistCount = Math.max(assistCount, (int) Math.ceil((double) needed / (double) per));
                            }
                        }

                        if (main.mainAttr1Type.equals("性平")) {
                            leadCount = 1;
                            break;
                        }

                        spend = main.mainAttr1Value * mainCount;
                        if (spend >= lead.leadAttrValue) {
                            leadCount = spend / lead.leadAttrValue;
                            if (spend != leadCount * lead.leadAttrValue) {
                                continue;
                            }
                            break;
                        }
                    }

                    spend = mainCount * main.price + leadCount * lead.price + assistCount * assist.price;
                    alchemyValue = dan.alchemyValue * config.getDanNumber();
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
                } while (spend > alchemyValue - 0 && spend > marketValue - 0);

                if (config.isAlchemy()) {
                    if (spend > alchemyValue - config.getAlchemyNumber()) {
                        continue;
                    }
                    break;
                } else if (("&" + config.getMakeName() + "&").contains("&" + config.getMakeName() + "&") && spend <= marketValue - config.getMakeNumber()) {
                    break;
                }
            }

            if (valid && mainCount + leadCount + assistCount < 100) {
                recipeResultList.add(new RecipeResult(dan, mainCount, leadCount, assistCount, spend, alchemyValue, marketValue));
            }
        }
    }

    String formatRecipe(Herb main, Herb lead, Herb assist, int mainCount, int leadCount, int assistCount, int spend, int alchemyValue, int marketValue) {
        return String.format("主药%s-%d&%d 药引%s-%d&%d 辅药%s-%d&%d 花费%d 炼金收益%d 坊市收益%d %d丹", main.name, mainCount, main.price, lead.name, leadCount, lead.price, assist.name, assistCount, assist.price, spend, alchemyValue, marketValue, config.getDanNumber());
    }
}
