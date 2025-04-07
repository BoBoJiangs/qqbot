package top.sshh.qqbot.data;


public class Herb {
    public String name;
    public String mainAttr1Type;
    public int mainAttr1Value;
    public String mainAttr2Type;
    public int mainAttr2Value;
    public String leadAttrType;
    public int leadAttrValue;
    public String assistAttrType;
    public int assistAttrValue;

    public int price; // 药材价格
    public Herb(String[] parts) {
        this.name = parts[0];
        parseAttributes(parts);
    }

    private void parseAttributes(String[] parts) {
        mainAttr1Type = parts[1].replaceAll("\\d", "");
        mainAttr1Value = Integer.parseInt(parts[1].replaceAll("\\D", ""));
        mainAttr2Type = parts[2].replaceAll("\\d", "");
        mainAttr2Value = Integer.parseInt(parts[2].replaceAll("\\D", ""));
        leadAttrType = parts[3].replaceAll("\\d", "");
        leadAttrValue = Integer.parseInt(parts[3].replaceAll("\\D", ""));
        assistAttrType = parts[4].replaceAll("\\d", "");
        assistAttrValue = Integer.parseInt(parts[4].replaceAll("\\D", ""));
    }

}
