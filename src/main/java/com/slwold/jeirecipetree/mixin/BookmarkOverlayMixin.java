package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.ui.*;
import com.slwold.jeirecipetree.client.JeiTreeIntegration;

import com.slwold.jeirecipetree.client.JERTLog;

import com.slwold.jeirecipetree.JERT;
import com.slwold.jeirecipetree.client.SafeConfig;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import mezz.jei.common.util.ImmutablePoint2i;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.overlay.ingredients.IngredientGridWithNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.lang.reflect.*;
import java.util.*;

@Mixin(targets = "mezz.jei.gui.overlay.bookmarks.BookmarkOverlay", remap = false)
public abstract class BookmarkOverlayMixin implements JeiExtraInputArea {
    @Shadow @Final private BookmarkList bookmarkList;
    @Shadow @Final private IngredientGridWithNavigation contents;
    @Shadow public abstract boolean isListDisplayed();
    @Unique private ImmutableRect2i jert$area = ImmutableRect2i.EMPTY;
    @Unique private boolean jert$panelFallbackLogged;

    @Unique private ImmutableRect2i jert$displayArea = ImmutableRect2i.EMPTY;
    @Unique private Set<ImmutableRect2i> jert$exclusions = Set.of();

    @Unique private String jert$lastBoundsState = "";

    @Unique private boolean jert$visible() {
        Screen screen = Minecraft.getInstance().screen;
        return SafeConfig.recipeTreeEnabled() && (screen instanceof RecipeTreeScreen
            || screen instanceof AbstractContainerScreen<?> || screen instanceof mezz.jei.gui.recipes.RecipesGui);
    }

    @Override
    public boolean jert$containsExtraInput(double x, double y) {
        if (!jert$visible()) return false;
        jert$place();
        return jert$area.contains(x, y);
    }

    @Unique private static final boolean EAP_LOADED
        = net.neoforged.fml.ModList.get().isLoaded("extendedae_plus");
    @Unique private boolean jert$offsetLogged;

    @Unique private void jert$place() {
        JeiTreeIntegration.bindBookmarks(bookmarkList);
        Object button = JeiReflectionCompat.readField(this, "historyButton");
        Object bounds = button == null ? null : JeiReflectionCompat.tryInvoke(button, "getArea");
        if (!(bounds instanceof ImmutableRect2i)) bounds = JeiReflectionCompat.readField(button, "area");
        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {
            jert$area = new ImmutableRect2i(6, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 26, 20, 20);
        } else if (bounds instanceof ImmutableRect2i area && !area.isEmpty()) {
            int step = area.getWidth() + 2;
            if (EAP_LOADED) {
                step += area.getWidth() + 2;
                if (!jert$offsetLogged) {
                    jert$offsetLogged = true;
                    JERTLog.debug("global button offset doubled ({}px): ExtendedAE Plus occupies the slot "
                        + "right of the history button", step);
                }
            }
            jert$area = area.moveRight(step);
        } else {

            jert$area = ImmutableRect2i.EMPTY;
        }
    }

    @Inject(method = "isListDisplayed", at = @At("RETURN"), cancellable = true, remap = false)
    private void jert$list(CallbackInfoReturnable<Boolean> cir) {
        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) cir.setReturnValue(false);
        else if (jert$visible() && JeiTreeIntegration.isActive()) cir.setReturnValue(true);
    }

    @Redirect(
        method = "updateBounds",
        at = @At(
            value = "INVOKE",
            target = "Lmezz/jei/gui/overlay/ingredients/IngredientGridWithNavigation;"
                + "updateBounds(Lmezz/jei/common/util/ImmutableRect2i;Ljava/util/Set;"
                + "Lmezz/jei/common/util/ImmutablePoint2i;)V"
        ),
        require = 0,
        remap = false
    )
    private void jert$reserveTreePanel(IngredientGridWithNavigation grid, ImmutableRect2i area,
                                              Set<ImmutableRect2i> exclusions,
                                              ImmutablePoint2i mouseExclusion) {

        jert$displayArea = area;
        jert$exclusions = exclusions == null ? Set.of() : exclusions;
        int height = RecipeTreeSidebarPanel.preferredHeight(area.getWidth());

        String state = area + " rows=" + JeiTreeIntegration.rowCount()
            + " active=" + JeiTreeIntegration.isActive()
            + " enabled=" + SafeConfig.recipeTreeEnabled() + " height=" + height;
        if (!state.equals(jert$lastBoundsState)) {
            jert$lastBoundsState = state;
            JERTLog.debug("bookmark grid bounds: area={} {}x{} rows={} active={} enabled={} height={}",
                area, area.getWidth(), area.getHeight(), JeiTreeIntegration.rowCount(),
                JeiTreeIntegration.isActive(), SafeConfig.recipeTreeEnabled(), height);
        }
        if (height <= 0) {
            RecipeTreeSidebarPanel.clear();
            grid.updateBounds(area, exclusions, mouseExclusion);
            return;
        }
        RecipeTreeSidebarPanel.setArea(area.keepBottom(height));
        RecipeTreeSidebarPanel.noteReserved(height);
        grid.updateBounds(area.cropBottom(height + 2), exclusions, mouseExclusion);
    }

    @Unique private void jert$reseatIfNeeded() {
        if (!RecipeTreeSidebarPanel.area().isEmpty() || jert$displayArea.isEmpty()) {
            return;
        }
        if (!JeiTreeIntegration.hasRows()) {
            return;
        }
        int height = RecipeTreeSidebarPanel.preferredHeight(jert$displayArea.getWidth());
        if (height <= 0) {
            return;
        }
        RecipeTreeSidebarPanel.setArea(jert$displayArea.keepBottom(height));
        contents.updateBounds(jert$displayArea.cropBottom(height + 2), jert$exclusions, null);
        RecipeTreeSidebarPanel.noteReserved(height);
    }

    @Inject(method = "drawForeground", at = @At("TAIL"), remap = false, require = 0)
    private void jert$draw(Minecraft mc, GuiGraphics graphics, int x, int y, float partial, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {

            RecipeTreeSidebarPanel.setOverlayVisible(false);
            jert$render(graphics, x, y);
            return;
        }

        jert$reseatIfNeeded();
        jert$ensurePanelArea();
        boolean show = RecipeTreeSidebarPanel.canDraw();
        RecipeTreeSidebarPanel.setOverlayVisible(show);
        if (show) {
            RecipeTreeSidebarPanel.render(graphics, x, y);
        }
        jert$render(graphics, x, y);
    }

    @Unique private void jert$ensurePanelArea() {
        if (!RecipeTreeSidebarPanel.area().isEmpty()) {
            return;
        }
        ImmutableRect2i background = contents.getBackgroundArea();
        if (background.isEmpty()) {
            return;
        }
        int height = RecipeTreeSidebarPanel.preferredHeight(background.getWidth());
        if (height <= 0) {
            return;
        }
        RecipeTreeSidebarPanel.setArea(background.keepBottom(height));
        if (!jert$panelFallbackLogged) {
            jert$panelFallbackLogged = true;
            JERTLog.warn("Recipe tree panel could not reserve space in the bookmark grid "
                + "(updateBounds redirect missing?); drawing over the bookmark area instead.");
        }
    }

    @Inject(method = "drawScreen", at = @At("TAIL"), remap = false, require = 0)
    private void jert$drawLegacy(Minecraft mc, GuiGraphics graphics, int x, int y, float partial, CallbackInfo ci) {
        if (!JeiReflectionCompat.hasMethod(this, "drawForeground")) jert$render(graphics, x, y);
    }

    @Unique private void jert$render(GuiGraphics graphics, int x, int y) {
        if (!jert$visible()) return;
        jert$place();
        if (jert$area.isEmpty()) return;
        RecipeTreeIcons.drawGlobalButton(graphics, jert$area,
            jert$area.contains(x, y),
            JeiTreeIntegration.hasTree());
    }

    @Inject(method = "drawTooltips", at = @At("HEAD"), cancellable = true, remap = false)
    private void jert$tooltip(Minecraft mc, GuiGraphics graphics, int x, int y, CallbackInfo ci) {
        if (jert$visible()) jert$place();
        if (jert$visible() && jert$area.contains(x, y)) {
            List<Component> lines = new ArrayList<>(List.of(
                Component.translatable("jeirecipetree.recipe_tree.global_button"),
                Component.translatable(!JeiTreeIntegration.hasTree()
                    ? "jeirecipetree.recipe_tree.empty_hint"
                    : "jeirecipetree.recipe_tree.global_button.clear")));

            if (MaterialFetch.isAvailable()) {
                lines.add(Component.translatable("jeirecipetree.recipe_tree.global_button.fetch"));
            }
            graphics.renderTooltip(mc.font, lines, Optional.empty(), x, y);
        }
    }

    @Inject(method = "createInputHandler", at = @At("RETURN"), cancellable = true, remap = false,
        require = 0, expect = 0)
    private void jert$input(CallbackInfoReturnable<Object> cir) {

        RecipeTreeSidebarPanel.markLegacyInputProxy();
        Object original = cir.getReturnValue();
        Object tree = JeiReflectionCompat.customInputHandler((proxy, method, args) -> {
            if (method.getName().equals("toString")) return "JERT tree button";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("unfocus")) return null;

            if (args != null && method.getName().equals("handleUserInput")) {
                Object raw = JeiReflectionCompat.inputArgument(args);

                if (RecipeTreeSidebarPanel.handleClick(raw)) {
                    return Optional.of(proxy);
                }
            }
            if (args != null && method.getName().equals("handleMouseScrolled") && args.length == 4) {
                try {
                    double mx = ((Number) args[0]).doubleValue();
                    double my = ((Number) args[1]).doubleValue();
                    double dy = ((Number) args[3]).doubleValue();
                    if (RecipeTreeSidebarPanel.handleScroll(mx, my, dy)) return Optional.of(proxy);
                } catch (RuntimeException ignored) {

                }
            }
            if (!method.getName().equals("handleUserInput") || !jert$visible()) return Optional.empty();
            Object input = JeiReflectionCompat.inputArgument(args);
            var key = JeiReflectionCompat.inputKey(input);
            jert$place();
            if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                || key.getValue() > 1 || !jert$area.contains(
                    JeiReflectionCompat.inputMouseX(input), JeiReflectionCompat.inputMouseY(input))) return Optional.empty();
            if (!JeiReflectionCompat.isInputSimulated(input)) {
                Screen screen = Minecraft.getInstance().screen;
                if (key.getValue() == 0 && Screen.hasControlDown()) {

                    JERTLog.debug("global button: Ctrl+left -> fetch materials");
                    MaterialFetch.request();
                } else {
                    if (key.getValue() == 1) JeiTreeIntegration.clearSession();
                    if (screen instanceof RecipeTreeScreen treeScreen) treeScreen.onClose();
                    else if (key.getValue() == 0) JeiTreeIntegration.openCurrentTree(screen);
                }
            }
            return Optional.of(proxy);
        });
        Object combined = JeiReflectionCompat.combineInputHandlers("JERT bookmarks", tree, original);
        cir.setReturnValue(JeiReflectionCompat.proxyInputHandler(() ->
            Minecraft.getInstance().screen instanceof RecipeTreeScreen ? tree : combined));
    }
}
