package com.slwold.jeirecipetree.client;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

final class ShellMenu extends AbstractContainerMenu {

    ShellMenu() {
        super(null, 0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
