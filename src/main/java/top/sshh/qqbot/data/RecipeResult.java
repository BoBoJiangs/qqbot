package top.sshh.qqbot.data;

public class RecipeResult {
    public Dan dan;
    public int mainCount;
    public int leadCount;
    public int assistCount;
    public int spend;
    public int alchemyValue;
    public int marketValue;

    public RecipeResult(Dan dan, int mainCount, int leadCount, int assistCount, int spend, int alchemyValue, int marketValue) {
        this.dan = dan;
        this.mainCount = mainCount;
        this.leadCount = leadCount;
        this.assistCount = assistCount;
        this.spend = spend;
        this.alchemyValue = alchemyValue;
        this.marketValue = marketValue;
    }
}
