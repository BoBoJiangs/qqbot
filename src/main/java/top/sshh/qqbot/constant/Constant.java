package top.sshh.qqbot.constant;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public  class Constant {
    public static final Set<String> MAKE_DAN_SET = new HashSet(Arrays.asList("金仙破厄丹", "太乙炼髓丹", "混沌丹", "创世丹", "极品混沌丹", "极品创世丹", "九天蕴仙丹", "金仙造化丹", "大道归一丹", "菩提证道丹", "太清玉液丹", "太一仙丸", "无涯鬼丸", "道源丹", "六阳长生丹", "太乙碧莹丹", "天元神丹", "天尘丹", "魇龙之血"));
    public static  String targetDir = "./";

    public static String padRight(String str, int length) {
        return str.length() >= length ? str : String.format("%-" + length + "s", str);
    }
}
