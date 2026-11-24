package com.slwold.jeirecipetree.mixin;

import mezz.jei.gui.bookmarks.BookmarkList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = BookmarkList.class, remap = false)
public interface BookmarkListAccessor {
    @Invoker("notifyListenersOfChange")
    void jert$notifyListenersOfChange();
}
