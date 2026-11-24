package com.slwold.jeirecipetree.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

final class DashedRule {

    static final int DASH = 8;

    static final int GAP = 6;

    private DashedRule() {
    }

    static void horizontal(GuiGraphics graphics, int x0, int x1, int y, int color) {
        int available = x1 - x0;
        if (available <= 0) {
            return;
        }
        if (available < 2 * DASH + GAP) {
            graphics.fill(x0, y, Math.min(x0 + DASH, x1), y + 1, color);
            return;
        }
        int interval = DASH + GAP;
        int dashes = available / interval + 1;
        float step = (available - DASH) / (float) (dashes - 1);
        for (int i = 0; i < dashes; i++) {
            int start = (int) Mth.clamp(x0 + i * step, x0, x1);
            graphics.fill(start, y, Math.min(start + DASH, x1), y + 1, color);
        }
    }

    static void vertical(GuiGraphics graphics, int y0, int y1, int x, int color) {
        int available = y1 - y0;
        if (available <= 0) {
            return;
        }
        if (available < 2 * DASH + GAP) {
            graphics.fill(x, y0, x + 1, Math.min(y0 + DASH, y1), color);
            return;
        }
        int interval = DASH + GAP;
        int dashes = available / interval + 1;
        float step = (available - DASH) / (float) (dashes - 1);
        for (int i = 0; i < dashes; i++) {
            int start = (int) Mth.clamp(y0 + i * step, y0, y1);
            graphics.fill(x, start, x + 1, Math.min(start + DASH, y1), color);
        }
    }
}
