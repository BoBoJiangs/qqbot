package top.sshh.qqbot.data;

public class BountyInfo {
    public int index;
    public int completionRate;
    public long cultivation;
    public int price;

    public BountyInfo(int index, int completionRate, long cultivation, int price) {
        this.index = index;
        this.completionRate = completionRate;
        this.cultivation = cultivation;
        this.price = price;
    }
}
