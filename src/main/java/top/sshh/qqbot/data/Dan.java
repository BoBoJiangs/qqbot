//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.data;

import java.util.LinkedHashMap;
import java.util.Map;

public class Dan implements Comparable<Dan> {
    public String name;
    public Map<String, Integer> requirements = new LinkedHashMap();
    public int priority;
    public int marketValue;
    public int alchemyValue;

    public Dan(String[] parts) {
        this.name = parts[0];

        for(int i = 1; i < parts.length; ++i) {
            String type = parts[i].replaceAll("\\d", "");
            int value = Integer.parseInt(parts[i].replaceAll("\\D", ""));
            this.requirements.put(type, value);
        }

        this.priority = this.requirements.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int compareTo(Dan o) {
        return Integer.compare(o.priority, this.priority);
    }
}
