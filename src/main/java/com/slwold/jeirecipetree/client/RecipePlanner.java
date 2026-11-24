package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.client.RecipeTreeData.MutableCost;
import com.slwold.jeirecipetree.client.RecipeTreeData.Node;
import com.slwold.jeirecipetree.client.RecipeTreeData.Progress;
import com.slwold.jeirecipetree.core.Amounts;
import com.slwold.jeirecipetree.core.ItemRef;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public final class RecipePlanner {

    private RecipePlanner() {
    }

    static void resetAmounts(Node node) {
        node.amount = 0;
        node.wanted = 0;
        for (Node child : node.children) resetAmounts(child);
    }

    static void resetProgress(Node node) {
        node.progress = Progress.UNSTARTED;
        node.crafts = 0;
        node.remaining = 0;
        for (Node child : node.children) {
            resetProgress(child);
        }
    }

    static void complete(Node node) {
        node.progress = Progress.COMPLETED;
        for (Node child : node.children) {
            complete(child);
        }
    }

    static void addCost(Map<String, MutableCost> map, Node node, long amount, long wanted) {
        if (node == null || (amount <= 0 && wanted <= 0)) {
            return;
        }

        MutableCost cost = map.get(node.costSignature);
        if (cost == null) {
            List<ItemStack> alternatives = node.explicitChoice
                ? List.of(node.stack())
                : node.alternatives;
            MutableCost created = new MutableCost(node.stack(), alternatives, amount);
            created.wanted = wanted;
            map.put(node.costSignature, created);
        } else {
            cost.amount = Amounts.safeAdd(cost.amount, amount);
            cost.wanted = Amounts.safeAdd(cost.wanted, wanted);
        }
    }

    static long consumeForNode(Map<ItemRef, MutableCost> map, Node node, long desired) {
        if (node == null || desired <= 0) {
            return desired;
        }

        long remaining = consume(map, node.ingredientRef, desired);
        if (!node.explicitChoice && remaining > 0) {
            for (ItemRef key : node.alternativeRefs) {
                if (!key.equals(node.ingredientRef)) {
                    remaining = consume(map, key, remaining);
                    if (remaining <= 0) {
                        break;
                    }
                }
            }
        }
        return remaining;
    }

    static long consume(Map<ItemRef, MutableCost> map, ItemRef key, long desired) {
        MutableCost cost = map.get(key);
        if (cost == null || desired <= 0) {
            return desired;
        }
        long used = Math.min(desired, cost.amount);
        cost.amount -= used;
        if (cost.amount <= 0) {
            map.remove(key);
        }
        return desired - used;
    }
}
