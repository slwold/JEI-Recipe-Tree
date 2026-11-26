package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class TreeText {

    private static final float ORIGINAL = 1.0f;

    private static volatile float scale = ORIGINAL;

    private static volatile boolean followGameFont;

    private TreeText() {
    }

    public static boolean sync() {
        float next = SafeConfig.treeTextSize().scale();
        boolean nextFont = SafeConfig.useGameFontForNumbers();
        if (next == scale && nextFont == followGameFont) {
            return false;
        }
        scale = next;
        followGameFont = nextFont;
        JERTLog.debug("tree text: scale={} gameFont={}", next, nextFont);
        return true;
    }

    public static float scale() {
        return scale;
    }

    public static int width(Font font, Component text) {
        return Math.round(font.width(text) * scale);
    }

    public static int width(Font font, String text) {
        return Math.round(font.width(text) * scale);
    }

    public static void centered(GuiGraphics graphics, Font font, Component text, int centerX, int y, int color) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    public static void centered(GuiGraphics graphics, Font font, String text, int centerX, int y, int color) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }
}
