package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.JeiTreeIntegration;

import mezz.jei.gui.bookmarks.BookmarkList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BookmarkList.class, remap = false)
public abstract class BookmarkListMixin {

    @Inject(method = "notifyListenersOfChange", at = @At("HEAD"), remap = false)
    private void jert$syncTreeRowsBeforeBookmarkLayout(CallbackInfo ci) {
        JeiTreeIntegration.bindBookmarks((BookmarkList) (Object) this);
        JeiTreeIntegration.refreshBeforeNotification();
    }
}
