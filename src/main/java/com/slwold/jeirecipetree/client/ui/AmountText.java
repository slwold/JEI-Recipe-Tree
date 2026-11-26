package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.HashMap;
import java.util.Map;

public final class AmountText {

    private static final int BADGE_WIDTH = 16;

    static final float HALF = 0.5f;

    private static final Map<String, Component> LATIN_CACHE = new HashMap<>();
    private static final int LATIN_CACHE_LIMIT = 256;

    private static boolean cachedFollowGameFont;

    private AmountText() {
    }

    public static Component latin(String text) {
        boolean followGameFont = SafeConfig.useGameFontForNumbers();
        if (followGameFont != cachedFollowGameFont) {
            cachedFollowGameFont = followGameFont;
            LATIN_CACHE.clear();
        }
        Component cached = LATIN_CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        Component created = followGameFont
            ? Component.literal(text)
            : Component.literal(text).withStyle(Style.EMPTY.withFont(Minecraft.DEFAULT_FONT));
        if (LATIN_CACHE.size() >= LATIN_CACHE_LIMIT) {
            LATIN_CACHE.clear();
        }
        LATIN_CACHE.put(text, created);
        return created;
    }

    static void drawSlot(GuiGraphics graphics, String text, int slotX, int slotY, int color, int z) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        drawAt(graphics, font, text, slotX + BADGE_WIDTH + 1, slotY + 9, color, z, fitScale(font, text, BADGE_WIDTH));
    }

    public static void drawAt(
        GuiGraphics graphics,
        Font font,
        String text,
        int rightX,
        int baselineY,
        int color,
        int z,
        float scale
    ) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Component latin = latin(text);
        int width = Math.max(1, font.width(latin));

        int left = rightX - Math.round(width * scale);
        graphics.pose().pushPose();
        graphics.pose().translate(left, baselineY, z);
        if (scale != 1.0f) {
            graphics.pose().scale(scale, scale, 1.0f);
        }

        graphics.drawString(font, latin, 0, 0, color, false);
        graphics.pose().popPose();
    }

    public static float fitScale(Font font, String text, int available) {
        return font.width(latin(text)) <= available ? 1.0f : HALF;
    }
}
