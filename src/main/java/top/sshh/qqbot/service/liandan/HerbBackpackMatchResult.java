package top.sshh.qqbot.service.liandan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 药材背包只读匹配结果。 */
final class HerbBackpackMatchResult {
    private final List<Allocation> allocations;
    private final long totalFurnaces;
    private final long totalProfit;
    private final Set<String> missingHerbPrices;
    private final Set<String> missingMarketDanPrices;
    private final Set<String> missingAlchemyValues;

    HerbBackpackMatchResult(
            List<Allocation> allocations,
            long totalFurnaces,
            long totalProfit,
            Set<String> missingHerbPrices,
            Set<String> missingMarketDanPrices,
            Set<String> missingAlchemyValues
    ) {
        this.allocations = Collections.unmodifiableList(new ArrayList<>(allocations));
        this.totalFurnaces = totalFurnaces;
        this.totalProfit = totalProfit;
        this.missingHerbPrices = immutableSortedSet(missingHerbPrices);
        this.missingMarketDanPrices = immutableSortedSet(missingMarketDanPrices);
        this.missingAlchemyValues = immutableSortedSet(missingAlchemyValues);
    }

    private static Set<String> immutableSortedSet(Set<String> source) {
        return Collections.unmodifiableSet(new TreeSet<>(source));
    }

    List<Allocation> getAllocations() {
        return allocations;
    }

    long getTotalFurnaces() {
        return totalFurnaces;
    }

    long getTotalProfit() {
        return totalProfit;
    }

    Set<String> getMissingHerbPrices() {
        return missingHerbPrices;
    }

    Set<String> getMissingMarketDanPrices() {
        return missingMarketDanPrices;
    }

    Set<String> getMissingAlchemyValues() {
        return missingAlchemyValues;
    }

    static final class Allocation {
        private final DanRecipeBlueprint blueprint;
        private final long furnaceCount;
        private final long unitCost;
        private final long unitProfit;
        private final long totalProfit;
        private final int danUnitPrice;
        private final Map<String, Integer> herbUnitPrices;

        Allocation(
                DanRecipeBlueprint blueprint,
                long furnaceCount,
                long unitCost,
                long unitProfit,
                int danUnitPrice,
                Map<String, Integer> herbUnitPrices
        ) {
            this.blueprint = blueprint;
            this.furnaceCount = furnaceCount;
            this.unitCost = unitCost;
            this.unitProfit = unitProfit;
            this.totalProfit = Math.multiplyExact(unitProfit, furnaceCount);
            this.danUnitPrice = danUnitPrice;
            this.herbUnitPrices = Collections.unmodifiableMap(new LinkedHashMap<>(herbUnitPrices));
        }

        DanRecipeBlueprint getBlueprint() {
            return blueprint;
        }

        long getFurnaceCount() {
            return furnaceCount;
        }

        long getUnitCost() {
            return unitCost;
        }

        long getUnitProfit() {
            return unitProfit;
        }

        long getTotalProfit() {
            return totalProfit;
        }

        int getDanUnitPrice() {
            return danUnitPrice;
        }

        int getHerbUnitPrice(String herbName) {
            return herbUnitPrices.getOrDefault(herbName, 0);
        }
    }
}
