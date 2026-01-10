//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class GuessIdiom {
    private static final Logger log = LoggerFactory.getLogger(GuessIdiom.class);
    private static Map<String, String> CACHE = new HashMap();
    private static Map<String, String> riddleMap = new HashMap();
    private static Map<String, String> EMOJI_MAP = new HashMap();

    public static String getEmoji(String text) {
        return (String)EMOJI_MAP.getOrDefault(text, text);
    }

    public static String getIdiom(String emoji) {
        return (String)CACHE.get(emoji);
    }

    public static String replaceEmojis(String text) {
        if (text == null || text.isEmpty())
            return text;
        for (Map.Entry<String, String> entry : EMOJI_MAP.entrySet())
            text = text.replace(entry.getKey(), entry.getValue());
        return text;
    }


    public static String getRiddle(String text) {
        Iterator var1 = riddleMap.entrySet().iterator();

        while(var1.hasNext()) {
            Map.Entry entry = (Map.Entry)var1.next();
            if (text.contains((CharSequence)entry.getKey())) {
                return (String)entry.getValue();
            }
        }

        return "";
    }

    public GuessIdiom() {
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof GuessIdiom)) {
            return false;
        } else {
            GuessIdiom other = (GuessIdiom)o;
            return other.canEqual(this);
        }
    }

    protected boolean canEqual(Object other) {
        return other instanceof GuessIdiom;
    }

    public int hashCode() {
        return 1;
    }

    public String toString() {
        return "GuessIdiom()";
    }

    static {
        CACHE.put("⭕\ud83d\udcb0\ud83c\udfc3\ud83d\udee3", "圈钱跑路");
        CACHE.put("\ud83d\udc3a\ud83d\udeac4️⃣\ud83d\udeeb", "狼烟四起");
        CACHE.put("\ud83d\udc304️⃣\ud83d\udc36\ud83c\udf73", "兔死狗烹");
        CACHE.put("\ud83e\udd74➕1️⃣\ud83d\udca1", "罪加一等");
        CACHE.put("\ud83e\udd8a\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66\ud83d\udc2f\ud83e\udde3", "狐假虎威");
        CACHE.put("☝\ud83d\udd28\ud83d\udccc\ud83c\udfb5", "一锤定音");
        CACHE.put("\ud83e\udd24\ud83c\udf50\ud83e\udd81\ud83d\udd12", "流离失所");
        CACHE.put("\ud83d\udc37\ud83d\udc6b\ud83d\udc36\ud83d\udc49", "猪朋狗友");
        CACHE.put("\ud83e\udd76\ud83c\udf2a️\ud83e\udd94\ud83e\uddb4", "寒风刺骨");
        CACHE.put("\ud83d\udc31\ud83d\ude45\ud83c\ude51\ud83d\udc44", "妙不可言");
        CACHE.put("\ud83d\udc2e\ud83e\uddb1\ud83d\udc0e\ud83c\udf5c", "牛头马面");
        CACHE.put("\ud83d\udd78️\ud83d\udc0f\ud83d\ude45\ud83c\udffb\u200d♀️\ud83e\uddd3\ud83c\udffb", "亡羊补牢");
        CACHE.put("\ud83d\udee1\ud83d\udc42\ud83c\udff4\u200d☠️\ud83d\udd14", "掩耳盗铃");
        CACHE.put("\ud83d\udc26\ud83d\udc1f\ud83c\udf38\ud83c\udf4c", "鸟语花香");
        CACHE.put("\ud83d\ude97\ud83d\udca7\ud83d\udc34\ud83d\udc09", "车水马龙");
        CACHE.put("\ud83c\udff4❤️\ud83d\udc5f\ud83d\udcaa", "齐心协力");
        CACHE.put("1️⃣\ud83d\udd78️\ud83c\ude1a️\ud83d\udcb0", "一往无前");
        CACHE.put("\ud83d\udc34\ud83d\udc34\ud83d\udc2f\ud83d\udc2f", "马马虎虎");
        CACHE.put("\ud83e\udd0d\ud83d\udc33\ud83e\udd69\ud83d\udc83", "心惊肉跳");
        CACHE.put("\ud83d\udca9\ud83c\ude1a\ud83d\udcb0\ud83c\udf50", "史无前例");
        CACHE.put("\ud83d\udd1f\ud83e\udd1b\ud83d\udd1f\ud83e\uddda\u200d♀️", "十全十美");
        CACHE.put("⛰️\ud83d\udeaa\ud83c\udf0a4️⃣", "山盟海誓");
        CACHE.put("\ud83d\udc42\ud83d\udc401️⃣\ud83c\udd95", "耳目一新");
        CACHE.put("\ud83d\udc53\ud83c\udf38\ud83d\udca7\ud83c\udf19", "镜花水月");
        CACHE.put("☀️☀️\ud83c\udf03\ud83c\udf03", "日日夜夜");
        CACHE.put("\ud83d\ude32\ud83c\udff9\ud83c\uddff\ud83d\udc26", "惊弓之鸟");
        CACHE.put("\ud83d\udc90\ud83d\udc4d\ud83c\udf19⭕", "花好月圆");
        CACHE.put("\ud83c\udfbb♟\ud83d\udcd6\ud83d\uddbc", "琴棋书画");
        CACHE.put("\ud83c\udf4a\ud83d\udc57\ud83c\udf80✔️", "成群结队");
        CACHE.put("\ud83d\udc4a\ud83c\udf3f\ud83d\ude32\ud83d\udc0d", "打草惊蛇");
        CACHE.put("\ud83c\udf2a️\ud83c\udf7c\ud83c\udf0a\ud83d\udc33", "风平浪静");
        CACHE.put("\ud83d\udcd5\ud83d\udce6\ud83d\udeaa\ud83d\udc66\ud83c\udffb", "书香门第");
        CACHE.put("\ud83d\udc4a❤️\ud83d\udc4a☝", "全心全意");
        CACHE.put("\ud83d\udd2a⏰\ud83e\udd8c\ud83e\uddea", "道重路远");
        CACHE.put("\ud83d\udd73️\ud83c\udfe0\ud83c\udf3a\ud83d\udd6f️", "洞房花烛");
        CACHE.put("7️⃣\ud83d\udc448️⃣\ud83d\udc45", "七嘴八舌");
        CACHE.put("\ud83d\udc13\ud83d\udc15\ud83d\ude81\ud83c\udf01", "鸡犬升天");
        CACHE.put("\ud83c\udfb8⭐\ud83c\udfcc️\ud83d\udcf7", "吉星高照");
        CACHE.put("\ud83d\udeb6\ud83d\udc0e\ud83d\udc40\ud83c\udf38", "走马观花");
        CACHE.put("\ud83c\udf5c\ud83d\udd34\ud83d\udc42\ud83d\udd34", "面红耳赤");
        CACHE.put("\ud83c\udc04️\ud83d\udd2d\ud83d\udd12\ud83d\udc22", "众望所归");
        CACHE.put("\ud83d\udc66\ud83d\udeb6\u200d♂️\ud83c\udf75❄️", "人走茶凉");
        CACHE.put("❄️\ud83c\udf27️⭐️\ud83c\udf2a️", "血雨腥风");
        CACHE.put("\ud83c\udf7a\ud83c\udf40\ud83d\udc68\u200d⚕️\ud83e\udd5c", "九死一生");
        CACHE.put("❤️❤️\ud83d\udcf7\ud83d\udc63", "心心相印");
        CACHE.put("\ud83c\udfca\u200d\ud83d\udd2a\ud83c\udfca\u200d\ud83d\udc1f", "游刃有余");
        CACHE.put("\ud83d\udd0a\ud83d\udc09\ud83d\udd25\ud83d\udc2f", "生龙活虎");
        CACHE.put("❤️\ud83e\udd14\ud83c\udf45\ud83c\udf4a", "心想事成");
        CACHE.put("\ud83d\ude2d\ud83d\ude04\ud83d\ude45\ud83c\ude50", "哭笑不得");
        CACHE.put("\ud83d\ude2d\ud83d\ude04\ud83d\ude45\u200d♀️\ud83c\ude50", "哭笑不得");
        CACHE.put("\ud83d\udcdf\ud83d\udc1d\ud83d\udde3\ud83c\udf27", "呼风唤雨");
        CACHE.put("\ud83d\udc57\ud83d\udc09\ud83d\udc83✋", "群龙无首");
        CACHE.put("\ud83d\udc57\ud83d\udc09\ud83c\ude1a️✋", "群龙无首");
        CACHE.put("✉️\ud83c\udf45\ud83e\udd5a\ud83e\udd5a", "信誓旦旦");
        CACHE.put("\ud83c\udfd4️\ud83d\udc1b\ud83d\udca7➖", "山重水复");
        CACHE.put("\ud83d\udd2b\ud83c\udf32\ud83d\udca3\ud83c\udf27️", "枪林弹雨");
        CACHE.put("\ud83d\udc2a\ud83e\udd40\ud83e\udd19\ud83d\udca6", "落花流水");
        CACHE.put("♟️\ud83c\udfa9\ud83d\ude45\ud83c\udffb\u200d♀️\ud83d\udc0f", "其貌不扬");
        CACHE.put("\ud83d\udd76\ud83d\udd76\ud83e\udd37\ud83e\udd9f", "默默无闻");
        CACHE.put("\ud83e\uddd1\u200d\ud83e\uddaf\ud83e\uddd1✋\ud83d\udc18", "盲人摸象");
        CACHE.put("\ud83d\ude32\ud83c\udf25\ud83d\udc4b\ud83c\udf0e", "惊天动地");
        CACHE.put("\ud83d\udd2a⚡\ud83d\ude80\ud83c\ude3a", "刀光剑影");
        CACHE.put("\ud83c\udf43\ud83d\ude8c\ud83d\udc4c\ud83d\udc32", "叶公好龙");
        CACHE.put("\ud83d\udd2a\ud83d\udef6⚽⚔", "刻舟求剑");
        CACHE.put("\ud83c\udf3e\ud83d\udc8a\ud83d\ude23\ud83d\udc44", "良药苦口");
        CACHE.put("\ud83c\udf4f♣\ud83c\udf8b\ud83d\udc0e", "青梅竹马");
        CACHE.put("\ud83c\udf4e\ud83d\udc40☁️\ud83d\udeac", "过眼云烟");
        CACHE.put("\ud83c\udf29\ud83c\udf27\ud83d\udd00➕", "雷雨交加");
        CACHE.put("\ud83d\udcd6\ud83d\udcb0\ud83d\udc83\ud83d\ude97", "学富五车");
        CACHE.put("\ud83c\udf2b️\ud83c\udf50\ud83d\udc40\ud83c\udf37", "雾里看花");
        CACHE.put("\ud83d\udc0d\ud83d\udc14\ud83c\udd98\ud83d\udc68", "舍己救人");
        CACHE.put("\ud83d\udc44\ud83c\udf45❤️\ud83d\udeab", "口是心非");
        CACHE.put("⭐\ud83d\udd25\ud83d\udc26⚪", "星火燎原");
        CACHE.put("⛰\ud83d\udd90️\ud83c\udf4a\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66", "三五成群");
        CACHE.put("\ud83d\udc1d\ud83c\udf0a☀️\ud83c\udf50", "风和日丽");
        CACHE.put("\ud83d\udc2f\ud83c\udf6c\ud83d\udc34\ud83d\udc26", "门可罗雀");
        CACHE.put("\ud83d\udc12\ud83d\udcc5\ud83d\udc34\ud83c\udf19", "猴年马月");
        CACHE.put("\ud83d\udcf3\ud83d\udc42\ud83e\udd24\ud83d\ude49", "震耳欲聋");
        CACHE.put("\ud83d\udc3b\ud83c\ude36\ud83c\udf4a\ud83c\udf8b", "胸有成竹");
        CACHE.put("\ud83e\uddca\ud83e\uddca\ud83c\ude36\ud83c\udf81", "彬彬有礼");
        CACHE.put("\ud83d\udc1f\ud83c\udfb5\ud83c\udf00\ud83c\udf3e", "余音绕梁");
        CACHE.put("\ud83d\udc76\ud83c\udf5d\ud83d\udc79❤️", "人面兽心");
        CACHE.put("\ud83d\udc69\u200d\ud83c\udfa8\ud83d\udc0d\ud83e\udda2\ud83d\udc3e", "画蛇添足");
        CACHE.put("✍️\ud83d\udc0d\ud83d\udc47\ud83e\uddb6", "画蛇添足");
        CACHE.put("\ud83d\ude04\ud83c\udf50\ud83c\udf71\ud83d\udd2a", "笑里藏刀");
        CACHE.put("\ud83d\udc19\ud83c\udfee\ud83c\udf80\ud83c\udf08", "张灯结彩");
        CACHE.put("\ud83d\ude14\ud83c\udf38\ud83d\ude2d\ud83d\udc67\ud83c\udffb", "愁眉苦脸");
        CACHE.put("\ud83d\ude1e\ud83c\udf38\ud83d\ude2d\ud83d\udc67\ud83c\udffb", "愁眉苦脸");
        CACHE.put("❤️\ud83d\udc44\ud83d\ude45\u200d♂️☝", "心口不一");
        CACHE.put("\ud83c\udfa5\ud83d\udc05\ud83d\udd19⛰", "放虎归山");
        CACHE.put("\ud83d\udcfd\ud83d\udc05\ud83d\udd19⛰", "放虎归山");
        CACHE.put("\ud83d\udeb4\ud83d\udc05\ud83d\ude1e\ud83d\udc47", "骑虎难下");
        CACHE.put("\ud83c\udfc7\ud83c\udffb\ud83d\udc2f\ud83c\udf83\ud83d\udc47", "骑虎难下");
        CACHE.put("\ud83d\udc34\ud83c\udf3e\ud83c\udf4a\ud83c\udff9", "马到成功");
        CACHE.put("\ud83c\udf7d\ud83d\udeaa\ud83d\udc48☯", "旁门左道");
        CACHE.put("\ud83d\udc91\ud83d\udd1d\ud83c\udf39\ud83d\udd25", "喜上眉梢");
        CACHE.put("\ud83c\udf81\ud83d\udc46\ud83c\udffb\ud83d\udd78️\ud83d\udeb6\u200d♀️", "礼尚往来");
        CACHE.put("\ud83d\udc13✈️\ud83e\udd5a\ud83e\udd1b", "鸡飞蛋打");
        CACHE.put("1️⃣\ud83c\ude34\ud83e\udde3\ud83d\uddc4️", "以和为贵");
        CACHE.put("\ud83d\udec0\ud83c\udffb\ud83d\ude2d❌\ud83d\udca6", "欲哭无泪");
        CACHE.put("\ud83d\udcf1\ud83d\udc90\ud83d\udd17\ud83e\udeb5", "移花接木");
        CACHE.put("\ud83d\udc91\ud83d\udeb6\u200d♀️\ud83d\udc40✋", "爱不释手");
        CACHE.put("☀️⬇️\ud83c\udf49\ud83c\udfd4️", "日落西山");
        CACHE.put("\ud83c\udf4a\ud83c\udfe0\ud83c\udf50\ud83c\udf43", "成家立业");
        CACHE.put("\ud83c\udf92\ud83e\ude9e\ud83c\udf50\ud83c\udf4c", "背井离乡");
        CACHE.put("\ud83c\udf2c️\ud83c\udf36️⏏️\ud83c\udfa4", "吹拉弹唱");
        CACHE.put("\ud83e\udd47\ud83c\udf8d\ud83d\udebf\ud83d\udd90\ud83c\udffb", "金盆洗手");
        CACHE.put("\ud83c\udf1e\ud83d\udc14\ud83c\udf19\ud83d\udca3", "日积月累");
        CACHE.put("\ud83c\udf2a\ud83c\udf02\ud83e\udd42\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc67", "风雨交加");
        CACHE.put("\ud83d\udc7a\ud83d\udc79\ud83d\udc7b\ud83d\udc7e", "妖魔鬼怪");
        CACHE.put("\ud83e\udd28\ud83d\udc40\ud83d\udce0\ud83d\udc91", "眉目传情");
        CACHE.put("\ud83d\ude23❤️\ud83d\udc14\ud83e\udd32", "痛心疾首");
        CACHE.put("☝️️\ud83d\udc4f\ud83c\udffb\ud83d\udc14\ud83c\ude34", "一拍即合");
        CACHE.put("\ud83d\udd2d\ud83d\udc41️\ud83c\udf3d\ud83d\udea2", "望眼欲穿");
        CACHE.put("\ud83d\udd78️\ud83d\udc41️\ud83d\udc1f\ud83d\udea2", "望眼欲穿");
        CACHE.put("\ud83c\udf39\ud83d\udc50\ud83d\udc41\ud83d\ude04", "眉开眼笑");
        CACHE.put("\ud83d\udeac\ud83d\udc66\ud83d\udc42\ud83d\udc40", "掩人耳目");
        CACHE.put("\ud83e\uddd1\ud83d\udc7c\ud83d\udc6b\ud83d\udca9", "人神共愤");
        CACHE.put("\ud83e\uddd1\ud83d\udce3\ud83c\udf72♨", "人声鼎沸");
        CACHE.put("\ud83d\udc3a\ud83d\udc2c\ud83d\udc2f\ud83e\udd9c", "狼吞虎咽");
        CACHE.put("\ud83d\ude0b\ud83e\udd42\ud83e\udd39\u200d♂️\ud83d\ude00", "吃喝玩乐");
        CACHE.put("\ud83c\udf0a\ud83e\udd40\ud83e\udea8\ud83d\ude16", "海枯石烂");
        CACHE.put("\ud83c\udf89\ud83c\udf25\ud83c\udfee\ud83c\udf0e", "欢天喜地");
        CACHE.put("❌\ud83d\udc40\ud83d\ude0d\ud83e\udd14", "不堪设想");
        CACHE.put("\ud83c\udf5a\ud83d\udc74⭕\ud83d\udc66", "返老还童");
        CACHE.put("\ud83d\ude45\ud83c\udffb\u200d♀️\ud83d\udcaa\ud83e\udd94\ud83e\uddb6\ud83c\udffb", "不吝赐教");
        CACHE.put("\ud83d\udc1b\ud83d\udc42\ud83c\udffb\ud83d\ude45\ud83c\udffb\u200d♀️\ud83e\udd9f", "充耳不闻");
        CACHE.put("\ud83c\ude1a\ud83d\udcb5\ud83c\uddff\ud83d\udc76", "无价之宝");
        CACHE.put("\ud83d\udc0a\ud83d\udc1f\ud83d\udd2a\ud83d\udc68", "恶语伤人");
        CACHE.put("\ud83d\udc0a\ud83d\udc1f\ud83e\udd15\ud83d\udc68", "恶语伤人");
        CACHE.put("\ud83e\udd6c\ud83e\udd1a\ud83e\udd16\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc67", "白手起家");
        CACHE.put("☀\ud83d\udc87\ud83c\udc07\ud83e\udd16", "日理万机");
        CACHE.put("\ud83d\udd90\ud83c\udf38\ud83d\udc68\ud83d\udeaa", "五花八门");
        CACHE.put("\ud83d\udca7\ud83c\udffe\ud83d\udd25\ud83e\udd75", "水深火热");
        CACHE.put("\ud83c\udf5c\ud83d\udc40\ud83d\udc4a\ud83d\udeab", "面目全非");
        CACHE.put("\ud83c\udf5c\ud83d\udc40\ud83d\udc4a\ud83d\udeeb", "面目全非");
        CACHE.put("\ud83e\udd43\ud83c\udf7d️\ud83d\udc3a\ud83d\udcd5", "杯盘狼藉");
        CACHE.put("\ud83e\uddd1\ud83c\udfd1☝\ud83d\ude4f", "首屈一指");
        CACHE.put("\ud83c\udf45\ud83c\udf0a\ud83d\udcde\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66", "四海为家");
        CACHE.put("❤️\ud83d\udca1\ud83d\udc63\ud83d\udce3", "心照不宣");
        CACHE.put("\ud83d\udc5a\ud83d\udc89\ud83d\ude80☃️", "一针见血");
        CACHE.put("\ud83c\udfb9\ud83e\uddd21⃣\ud83c\ude34", "情投意合");
        CACHE.put("\ud83d\udd2a⛏️\ud83d\udd25\ud83c\udf31", "刀耕火种");
        CACHE.put("\ud83d\udc50\ud83d\udc46\ud83d\udd06\ud83d\udc37", "掌上明珠");
        CACHE.put("1️⃣1️⃣\ud83d\udd2d\ud83e\uddf3", "得意忘形");
        CACHE.put("\ud83c\ude501️⃣\ud83d\udd2d\ud83e\uddf3", "得意忘形");
        CACHE.put("\ud83d\udc11\ud83d\udeb6\ud83d\udc2f\ud83d\udc44", "羊入虎口");
        CACHE.put("\ud83e\uddd1⛰\ud83e\uddd1\ud83c\udf0a", "人山人海");
        CACHE.put("\ud83d\udc68⛰️\ud83d\udc68\ud83c\udf0a", "人山人海");
        CACHE.put("\ud83e\udda9\ud83e\uddcd\ud83d\udc14\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66", "鹤立鸡群");
        CACHE.put("\ud83d\udca6\ud83d\udc02\ud83d\udd0c\ud83c\udfe2", "汗牛充栋");
        CACHE.put("\ud83d\udc44\ud83e\uddf1\ud83d\udc0d\ud83e\uddcd", "唇枪舌战");
        CACHE.put("\ud83e\uddfb\ud83d\ude0c\ud83d\udc11\ud83d\udc11", "喜气洋洋");
        CACHE.put("\ud83d\udc44\ud83d\udc44\ud83d\udcf7\ud83d\udce0", "口口相传");
        CACHE.put("\ud83d\udc14\ud83d\udc20\ud83d\ude4f\ud83c\udffb\ud83c\udf4a", "急于求成");
        CACHE.put("\ud83d\udd2a⛰\ud83d\udd25\ud83c\udf0a", "刀山火海");
        CACHE.put("\ud83c\udf38\ud83c\udf50\ud83d\udc2f\ud83e\udd44", "花里胡哨");
        CACHE.put("➗\ud83d\udc40\ud83d\udc33\ud83e\udd0d", "触目惊心");
        CACHE.put("1️⃣\ud83d\udc40\ud83d\udd70\ud83d\udc91", "一见钟情");
        CACHE.put("\ud83d\udc22❤️\ud83c\udf45\ud83d\ude80", "归心似箭");
        CACHE.put("✅\ud83d\udc2e⏏️\ud83c\udfb9", "对牛弹琴");
        CACHE.put("✖️\ud83d\udca8\ud83d\udc94\ud83c\udf0a", "乘风破浪");
        CACHE.put("✖\ud83d\udca8\ud83d\udc94\ud83c\udf0a", "乘风破浪");
        CACHE.put("\ud83e\uddb1\ud83d\ude35\ud83d\udc40\ud83c\udf00", "头晕眼花");
        CACHE.put("\ud83d\udc4d\ud83d\udde3\ud83e\udd1d☯", "能说会道");
        CACHE.put("\ud83d\udce2\ud83c\udf5c\ud83d\udd14\ud83d\udc09", "八面玲珑");
        CACHE.put("\ud83d\udc1f\ud83d\udc1d\ud83c\udf79\ud83d\udcaa", "渔翁之利");
        CACHE.put("7️⃣\ud83d\udc468️⃣\ud83d\udc47", "七上八下");
        CACHE.put("\ud83c\udf3f\ud83c\udf11\ud83c\udf3a\ud83c\udf15", "柳暗花明");
        CACHE.put("\ud83c\udf4a\ud83d\udc20⬇️\ud83d\udc26", "沉鱼落雁");
        CACHE.put("\ud83d\ude14\ud83d\udc1f\ud83c\udf42\ud83e\udda2", "沉鱼落雁");
        CACHE.put("\ud83c\ude2f\ud83c\udf43\ud83e\udd2c\ud83c\udf33", "指桑骂槐");
        CACHE.put("1️⃣\ud83d\udc0f\ud83d\udc0f", "得意洋洋");
        CACHE.put("☔\ud83c\udf4e☁️☀️", "雨过天晴");
        CACHE.put("\ud83d\udde3\ud83c\udfcb❤️\ud83e\udd92", "语重心长");
        CACHE.put("\ud83d\udc0f\ud83d\udcf1\ud83c\udfb5\ud83e\udde3", "阳奉阴违");
        CACHE.put("\ud83d\udc8d\ud83c\udf38\ud83e\uddf6\ud83d\uded5", "借花献佛");
        CACHE.put("\ud83d\udcaa\ud83d\udc68\ud83d\udcde\ud83d\ude00", "助人为乐");
        CACHE.put("\ud83d\udc14✈\ud83d\udc15\ud83d\udc83", "鸡飞狗跳");
        CACHE.put("\ud83c\udf75\ud83c\udf5a❌\ud83e\udd14", "茶饭不思");
        CACHE.put("\ud83c\udd98\ud83e\uddd1\ud83d\udcde\ud83d\ude00", "助人为乐");
        CACHE.put("\ud83c\ude501️⃣\ud83d\udc0f\ud83d\udc0f", "得意洋洋");
        CACHE.put("\ud83e\udd43\ud83d\udca7\ud83d\ude97\ud83d\udcb4", "杯水车薪");
        CACHE.put("\ud83e\udd1a\ud83e\udd16\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc67", "白手起家");
        CACHE.put("\ud83d\ude23\ud83e\udd0d\ud83d\udc14\ud83d\udd90\ud83c\udffb", "痛心疾首");
        CACHE.put("\ud83d\udc64\ud83d\udeb6\ud83c\udf75❄️", "人走茶凉");
        CACHE.put("\ud83d\udd90\ud83d\udc46\ud83d\udd06\ud83d\udc37", "掌上明珠");
        CACHE.put("\ud83d\udec1\ud83d\ude24\ud83d\udc11\ud83d\udc11", "喜气洋洋");
        CACHE.put("\ud83d\ude22\ud83d\udc2e\ud83c\udf7a\ud83c\udf5c", "泪流满面");
        CACHE.put("\ud83e\uddd1\ud83c\udfd1☝\ud83c\ude2f", "首屈一指");
        CACHE.put("\ud83c\udf2a️\ud83c\udf2a️\ud83d\udd25\ud83d\udd25", "风风火火");
        CACHE.put("\ud83e\udd5f\ud83d\udc30\ud83c\udf02\ud83d\ude2d", "狡兔三窟");
        CACHE.put("\uD83D\uDD1F\uD83E\uDD1B\uD83D\uDD1F\uD83E\uDDDC\u200D♀\uFE0F","十全十美");
        riddleMap.put("红公鸡", "萝卜");
        riddleMap.put("有头没有尾", "水龙头");
        riddleMap.put("四四方方一座城", "象棋");
        riddleMap.put("小小金坛子", "橘子");
        riddleMap.put("白胖娃娃泥里藏", "藕");
        riddleMap.put("有头无尾", "针");
        riddleMap.put("小小姑娘满身黑", "燕子");
        riddleMap.put("小小船儿两头尖", "菱角");
        riddleMap.put("头上两根毛", "蝴蝶");
        riddleMap.put("肚皮下面长口袋", "袋鼠");
        riddleMap.put("红红脸儿圆又圆", "苹果");
        riddleMap.put("红灯笼", "柿子");
        riddleMap.put("小时四只脚", "人");
        riddleMap.put("一个黑孩", "瓜子");
        riddleMap.put("头上长树杈", "梅花鹿");
        riddleMap.put("白天出现", "太阳");
        riddleMap.put("身穿绿衣裳", "西瓜");
        riddleMap.put("有面没有口", "桌子");
        riddleMap.put("有风不动无风动", "扇子");
        riddleMap.put("有头无颈", "鱼");
        riddleMap.put("一只小船两头翘", "鞋子");
        riddleMap.put("兄弟七八个", "大蒜");
        riddleMap.put("远看山有色", "画");
        riddleMap.put("耳朵像蒲扇", "大象");
        riddleMap.put("一座桥，地上架", "滑梯");
        riddleMap.put("威风凛凛山大王", "老虎");
        riddleMap.put("小小诸葛亮", "蜘蛛");
        EMOJI_MAP.put("沙漏", "⌛");
        EMOJI_MAP.put("鲸鱼", "\ud83d\udc33");
        EMOJI_MAP.put("电池", "\ud83d\udd0b");
        EMOJI_MAP.put("苹果", "\ud83c\udf4e");
        EMOJI_MAP.put("葡萄", "\ud83c\udf47");
        EMOJI_MAP.put("图钉", "\ud83d\udccc");
        EMOJI_MAP.put("鸡", "\ud83d\udc14");
        EMOJI_MAP.put("鸡头", "\ud83d\udc14");
        EMOJI_MAP.put("西瓜", "\ud83c\udf49");
        EMOJI_MAP.put("电脑", "\ud83d\udcbb");
        EMOJI_MAP.put("汽车", "\ud83d\ude97");
        EMOJI_MAP.put("书", "\ud83d\udcd6");
        EMOJI_MAP.put("书本", "\ud83d\udcd6");
        EMOJI_MAP.put("马","\uD83D\uDC0E");
        EMOJI_MAP.put("企鹅","\uD83D\uDC27");
        EMOJI_MAP.put("猪","\uD83D\uDC16");
        EMOJI_MAP.put("树","\uD83C\uDF32");
        EMOJI_MAP.put("青蛙","\uD83D\uDC38");
        EMOJI_MAP.put("薯条","\uD83C\uDF5F");
        EMOJI_MAP.put("汉堡","\uD83C\uDF54");
        EMOJI_MAP.put("螃蟹","\uD83E\uDD80");
        EMOJI_MAP.put("龙虾","\uD83E\uDD90");
        EMOJI_MAP.put("大象","\uD83D\uDC18");
        EMOJI_MAP.put("兔子","\uD83D\uDC07");
        EMOJI_MAP.put("锚钩","⚓");
        EMOJI_MAP.put("摩托车","🏍️");
        EMOJI_MAP.put("雨伞","☂️");
        EMOJI_MAP.put("直升机","\uD83D\uDE81");
        EMOJI_MAP.put("飞机","✈️");

        EMOJI_MAP.put("⌛", "沙漏");
        EMOJI_MAP.put("\ud83d\udc33", "鲸鱼");
        EMOJI_MAP.put("\ud83d\udd0b", "电池");
        EMOJI_MAP.put("\ud83c\udf4e", "苹果");
        EMOJI_MAP.put("\ud83c\udf47", "葡萄");
        EMOJI_MAP.put("\ud83d\udccc", "图钉");
        EMOJI_MAP.put("\ud83d\udc14", "鸡");
        EMOJI_MAP.put("\ud83c\udf49", "西瓜");
        EMOJI_MAP.put("\ud83d\udcbb", "电脑");
        EMOJI_MAP.put("\ud83d\ude97", "汽车");
        EMOJI_MAP.put("\ud83d\udcd6", "书");

        EMOJI_MAP.put("\uD83D\uDC0E","马");
        EMOJI_MAP.put("\uD83D\uDC27","企鹅");
        EMOJI_MAP.put("\uD83D\uDC16","猪");
        EMOJI_MAP.put("\uD83C\uDF32","树");
        EMOJI_MAP.put("\uD83D\uDC38","青蛙");
        EMOJI_MAP.put("\uD83C\uDF5F","薯条");
        EMOJI_MAP.put("\uD83C\uDF54","汉堡");
        EMOJI_MAP.put("\uD83E\uDD80","螃蟹");
        EMOJI_MAP.put("\uD83E\uDD90","龙虾");
        EMOJI_MAP.put("\uD83D\uDC18","大象");
        EMOJI_MAP.put("\uD83D\uDC07","兔子");

        EMOJI_MAP.put("⚓","锚钩");
        EMOJI_MAP.put("🏍️","摩托车");
        EMOJI_MAP.put("☂️","雨伞");
        EMOJI_MAP.put("\uD83D\uDE81","直升机");
        EMOJI_MAP.put("✈️","飞机");
    }


}
