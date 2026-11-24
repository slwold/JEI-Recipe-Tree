package com.slwold.jeirecipetree.client;

import net.minecraft.client.gui.GuiGraphics;

final class SlotChrome {

    static final int HOVER = 0x80FFFFFF;

    static final int ACCEPT = 0x60FFFFFF;

    private SlotChrome() {
    }

    static void frame(GuiGraphics graphics, int x, int y, int size, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.SLOT_OVERLAY);
        int right = x + size - 1;
        int bottom = y + size - 1;
        graphics.fill(x + 1, y + 1, right, y + 2, color);
        graphics.fill(x + 1, bottom - 1, right, bottom, color);
        graphics.fill(x + 1, y + 1, x + 2, bottom, color);
        graphics.fill(right - 1, y + 1, right, bottom, color);
        graphics.pose().popPose();
    }
}
