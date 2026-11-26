package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.gui.GuiGraphics;

public record AmountOverlay(String value, int stateColor) implements IDrawable {

    static final int TEXT_COLOR = 0xFFFFFFFF;

    @Override
    public int getWidth() {
        return 16;
    }

    @Override
    public int getHeight() {
        return 16;
    }

    @Override
    public void draw(GuiGraphics graphics, int xOffset, int yOffset) {

        AmountText.drawSlot(graphics, value, xOffset, yOffset, TEXT_COLOR, 0);
    }
}
