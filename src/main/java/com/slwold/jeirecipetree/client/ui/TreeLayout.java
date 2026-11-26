package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public final class TreeLayout {

    public static final int TAPE_INSET = 2;

    public static final int CATEGORY_SIZE = 18;

    public static final int ITEM_SIZE = 16;

    public static final int CONTENT_GAP = 3;

    public static final int AMOUNT_ZONE = 28;

    public static final int RECIPE_NODE_WIDTH =
        TAPE_INSET + CATEGORY_SIZE + CONTENT_GAP + ITEM_SIZE + CONTENT_GAP + AMOUNT_ZONE + TAPE_INSET;

    public static final int LEAF_NODE_WIDTH =
        TAPE_INSET + ITEM_SIZE + CONTENT_GAP + AMOUNT_ZONE + TAPE_INSET;

    public static final int NODE_HEIGHT = 22;

    public static final int COST_SPACING = 8;

    public static final int X_PITCH = RECIPE_NODE_WIDTH + COST_SPACING;

    public static final int Y_SPACING = 44;

    public static final int CATALYST_OFFSET = 80;

    public static final int MODE_BUTTON_SIZE = 16;

    public static final int MODE_BUTTON_HIT = 20;

    public static final int TRANSFER_BUTTON_SIZE = 7;

    public record RenderCost(RecipeTreeData.Cost cost, int x, int y, boolean leftover, int width) {
    }

    private final List<RecipeTreeData.Node> visibleNodes = new ArrayList<>();
    private final List<RenderCost> costs = new ArrayList<>();
    private final List<RenderCost> catalysts = new ArrayList<>();

    private int maxDepth;
    private int costY;
    private int costCenterX;
    private int modeX;
    private int modeY;
    private int batchX;
    private int batchY;
    private int batchWidth;

    public void rebuild(
        RecipeTreeData.Tree tree,
        Font font,
        BiFunction<RecipeTreeData.Cost, Boolean, String> costText,
        float textScale
    ) {
        visibleNodes.clear();
        costs.clear();
        maxDepth = 0;
        costY = 0;
        costCenterX = 0;
        modeX = 0;
        modeY = 0;
        batchX = 0;
        batchY = 0;
        batchWidth = 0;
        if (tree == null) {
            return;
        }

        int[] cursor = {0};
        layoutNode(tree.root(), 0, cursor);
        costCenterX = tree.root().x();

        costY = (maxDepth + 1) * Y_SPACING + 38;
        int totalWidth = Math.round(
            font.width(Component.translatable("jeirecipetree.recipe_tree.total_cost")) * textScale);
        modeX = costCenterX + totalWidth / 2 + 6;
        modeY = costY - 22;
        String batches = "x" + tree.batches();

        batchX = tree.root().x() + RECIPE_NODE_WIDTH / 2 + 6;
        batchY = tree.root().y() - 11;
        batchWidth = Math.round(font.width(batches) * textScale) + 12;
        rebuildCosts(tree.analyze(), font, costText, textScale);
    }

    public void rebuildCosts(
        RecipeTreeData.Analysis analysis,
        Font font,
        BiFunction<RecipeTreeData.Cost, Boolean, String> costText,
        float textScale
    ) {
        costs.clear();
        catalysts.clear();
        if (analysis == null) {
            return;
        }
        addCostRow(costs, analysis.costs(), costY, false, font, costText, textScale);
        addCostRow(costs, analysis.leftovers(), costY + 40, true, font, costText, textScale);

        addCostRow(catalysts, analysis.catalysts(), costY + CATALYST_OFFSET, false, font, costText, textScale);
    }

    private int layoutNode(RecipeTreeData.Node node, int depth, int[] cursor) {
        maxDepth = Math.max(maxDepth, depth);
        visibleNodes.add(node);
        if (!node.expanded() || node.children().isEmpty()) {
            int x = cursor[0] + X_PITCH / 2;
            cursor[0] += X_PITCH;
            node.setPosition(x, depth * Y_SPACING);
            return x;
        }

        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (RecipeTreeData.Node child : node.children()) {
            int x = layoutNode(child, depth + 1, cursor);
            first = Math.min(first, x);
            last = Math.max(last, x);
        }
        int x = (first + last) / 2;
        node.setPosition(x, depth * Y_SPACING);
        return x;
    }

    private void addCostRow(
        List<RenderCost> target,
        List<RecipeTreeData.Cost> entries,
        int y,
        boolean leftover,
        Font font,
        BiFunction<RecipeTreeData.Cost, Boolean, String> costText,
        float textScale
    ) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int x = 0;
        List<RenderCost> row = new ArrayList<>();
        for (RecipeTreeData.Cost cost : entries) {
            int textWidth = Math.round(font.width(costText.apply(cost, leftover)) * textScale);

            int width = TAPE_INSET + ITEM_SIZE + CONTENT_GAP + Math.min(AMOUNT_ZONE, textWidth) + TAPE_INSET;
            row.add(new RenderCost(cost, x, y, leftover, width));
            x += width + COST_SPACING;
        }
        int offset = (x - COST_SPACING) / 2;
        for (RenderCost cost : row) {
            target.add(new RenderCost(
                cost.cost(), cost.x() - offset + costCenterX, cost.y(), cost.leftover(), cost.width()));
        }
    }

    public static int amountLeft(RecipeTreeData.Node node, int left) {
        int icons = TAPE_INSET + (node.recipe() == null ? 0 : CATEGORY_SIZE + CONTENT_GAP) + ITEM_SIZE;
        return left + icons + CONTENT_GAP;
    }

    public List<RecipeTreeData.Node> visibleNodes() {
        return visibleNodes;
    }

    public List<RenderCost> costs() {
        return costs;
    }

    public List<RenderCost> catalysts() {
        return catalysts;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int costY() {
        return costY;
    }

    public int costCenterX() {
        return costCenterX;
    }

    public int modeX() {
        return modeX;
    }

    public int modeY() {
        return modeY;
    }

    public int batchX() {
        return batchX;
    }

    public int batchY() {
        return batchY;
    }

    public int batchWidth() {
        return batchWidth;
    }
}
