package com.slwold.jeirecipetree.client;

import net.minecraft.client.gui.GuiGraphics;

public final class TreeGlyphs {

    private TreeGlyphs() {
    }

    public static void arrowDown(GuiGraphics graphics, int centerX, int tipY, int color) {

        graphics.fill(centerX - 3, tipY - 4, centerX + 4, tipY - 3, color);
        graphics.fill(centerX - 2, tipY - 3, centerX + 3, tipY - 2, color);
        graphics.fill(centerX - 1, tipY - 2, centerX + 2, tipY - 1, color);
        graphics.fill(centerX, tipY - 1, centerX + 1, tipY, color);
    }

    public static void plus(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 7, y + 7, 0xE0101010);
        graphics.fill(x + 1, y + 3, x + 6, y + 4, color);
        graphics.fill(x + 3, y + 1, x + 4, y + 6, color);
    }

    public static void expander(GuiGraphics graphics, int centerX, int topY, int color) {
        int left = centerX - 2;
        graphics.fill(left, topY + 2, left + 5, topY + 3, color);
        graphics.fill(centerX, topY, centerX + 1, topY + 5, color);
    }
}
