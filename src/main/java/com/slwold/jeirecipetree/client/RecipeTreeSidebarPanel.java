package com.slwold.jeirecipetree.client;

import org.jetbrains.annotations.Nullable;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.drawable.IScalableDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

public final class RecipeTreeSidebarPanel {

    public static final int SLOT = 18;

    private static final int PITCH = SLOT;

    private static final int BORDER_MARGIN = 6;

    private static final int BORDER_PADDING = 5;

    private static final int INNER_PADDING = 2;
    private static final int SEPARATOR = 1;

    private static final int NAV_HEIGHT = 20;

    private static final int NAV_GAP = INNER_PADDING;

    private static final int LINE_GAP = 3;

    private static final int MAX_ROWS = 2;

    private static final int DEFAULT_MAX_COLUMNS = 16;

    private static final int HOVER_MASK = 0x80FFFFFF;

    private static final int FALLBACK_BACKGROUND = 0xE018181C;
    private static final int FALLBACK_SLOT = 0xFF9A9AA2;
    private static final int FALLBACK_SLOT_BORDER = 0xFF55555F;
    private static final int FALLBACK_SEPARATOR = 0xFF3A3A44;

    private static PanelBackground panelStyle = PanelBackground.NONE;

    private static ImmutableRect2i area = ImmutableRect2i.EMPTY;

    private static Screen areaScreen;
    private static int columns = 1;
    private static int visibleRows = MAX_ROWS;
    private static int scrollRow;

    private RecipeTreeSidebarPanel() {
    }

    public static ImmutableRect2i area() {
        return area;
    }

    public static void clear() {
        area = ImmutableRect2i.EMPTY;
        areaScreen = null;
        scrollRow = 0;
    }

    private static boolean reservationLogged;

    public static int preferredHeight(int width) {

        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {
            return 0;
        }
        boolean active = JeiTreeIntegration.isActive();
        int total = RecipeTreeFavorites.rows().size();
        if (!active || total == 0 || !SafeConfig.recipeTreeEnabled() || width <= SLOT) {
            return 0;
        }
        PanelBackground style = jeiBackgroundStyle();
        int cols = columnsFor(width, style);
        int neededRows = (total + cols - 1) / cols;
        int rows = Math.min(neededRows, MAX_ROWS);
        return LINE_GAP + NAV_HEIGHT + NAV_GAP + rows * SLOT + inset(style) * 2;
    }

    public static void setArea(ImmutableRect2i rect) {
        area = rect;
        if (rect.isEmpty()) {
            areaScreen = null;
            columns = 1;
            visibleRows = 1;
            scrollRow = 0;
            return;
        }
        areaScreen = Minecraft.getInstance().screen;
        PanelBackground style = jeiBackgroundStyle();
        panelStyle = style;
        columns = columnsFor(rect.getWidth(), style);

        int usable = rect.getHeight() - inset(style) * 2 - (LINE_GAP + NAV_HEIGHT + NAV_GAP);
        visibleRows = Math.max(1, usable / PITCH);
        clampScroll();
    }

    public static void noteReserved(int height) {
        if (reservationLogged) {
            return;
        }
        reservationLogged = true;
        JERTLog.summary("sidebar tree panel reserved: height={} columns={} visibleRows={} slots={}",
            height, columns, visibleRows, RecipeTreeFavorites.rows().size());
    }

    public static int columns() {
        return columns;
    }

    private static boolean emptyRowsLogged;

    private static void noteEmptyRows() {
        if (emptyRowsLogged) {
            return;
        }
        emptyRowsLogged = true;
        JERTLog.summary("sidebar tree panel: area reserved but no rows yet (area={})", area);
    }

    private static int gridTop(PanelBackground style) {
        return area.getY() + LINE_GAP + NAV_HEIGHT + NAV_GAP + inset(style);
    }

    private static int pageIndex() {
        return visibleRows <= 0 ? 0 : scrollRow / visibleRows;
    }

    private static int pageCount() {
        return visibleRows <= 0 ? 1 : Math.max(1, (totalRows() + visibleRows - 1) / visibleRows);
    }

    private static int inset(PanelBackground style) {
        return style == PanelBackground.PANEL
            ? BORDER_MARGIN + BORDER_PADDING + INNER_PADDING
            : BORDER_MARGIN;
    }

    private static int[] contentArea() {
        int pad = panelStyle == PanelBackground.PANEL ? INNER_PADDING : 0;
        int grid = Math.min(area.getWidth() - inset(panelStyle) * 2, columns * PITCH);
        return new int[] { area.getX() + inset(panelStyle) - pad, grid + pad * 2 };
    }

    private static int columnsFor(int width, PanelBackground style) {
        int fit = (width - inset(style) * 2) / PITCH;
        return Math.max(1, Math.min(fit, jeiGridLimit(true)));
    }

    private static int totalRows() {
        int total = RecipeTreeFavorites.rows().size();
        return total == 0 ? 0 : (total + columns - 1) / columns;
    }

    private static void clampScroll() {

        scrollRow = Math.max(0, Math.min(pageIndex(), pageCount() - 1)) * visibleRows;
    }

    private static ImmutableRect2i slotOf(int index) {
        int column = index % columns;
        int row = index / columns - scrollRow;
        if (row < 0 || row >= visibleRows) {
            return null;
        }
        int x = area.getX() + inset(panelStyle) + column * PITCH;
        int y = gridTop(panelStyle) + row * PITCH;
        return new ImmutableRect2i(x, y, SLOT, SLOT);
    }

    private static final OverlayScrollTarget SCROLL_TARGET = RecipeTreeSidebarPanel::handleScroll;

    public static boolean canDraw() {

        return !area.isEmpty()
            && areaScreen == Minecraft.getInstance().screen
            && !RecipeTreeFavorites.rows().isEmpty();
    }

    public static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (area.isEmpty()) {
            return;
        }
        List<IElement<?>> rows = RecipeTreeFavorites.rows();
        if (rows.isEmpty()) {
            noteEmptyRows();
            OverlayScrollRouter.withdraw(SCROLL_TARGET);
            return;
        }

        if (overlayVisible && !legacyInputProxy) {
            OverlayScrollRouter.offer(SCROLL_TARGET);
        } else {
            OverlayScrollRouter.withdraw(SCROLL_TARGET);
        }

        PanelBackground style = jeiBackgroundStyle();
        panelStyle = style;
        drawPanelBackground(graphics, style);
        drawSeparator(graphics, style);
        drawNavigation(graphics, mouseX, mouseY);

        int hovered = indexAt(mouseX, mouseY);
        for (int index = 0; index < rows.size(); index++) {
            ImmutableRect2i slot = slotOf(index);
            if (slot == null) {
                continue;
            }
            drawSlot(graphics, slot, rows.get(index), style, index == hovered);
        }

        if (hovered >= 0 && hovered < rows.size()) {
            TreeRowAccess row = asRow(rows.get(hovered));
            if (row != null) {

                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, RenderLayers.TOOLTIP);
                renderRowTooltip(graphics, row, mouseX, mouseY);
                graphics.pose().popPose();
            }
        }
    }

    private static void renderRowTooltip(GuiGraphics graphics, TreeRowAccess row, int mouseX, int mouseY) {
        List<Component> own = row.tooltipLines();
        JeiTooltip base = new JeiTooltip();
        if (!IngredientDisplay.collectRich(base, row.typedIngredient())) {
            graphics.renderComponentTooltip(Minecraft.getInstance().font, own, mouseX, mouseY);
            return;
        }
        JeiTooltip extra = new JeiTooltip();

        for (int i = 1; i < own.size(); i++) {
            extra.add(own.get(i));
        }
        base.addIngredientTooltipFooter(extra);
        base.draw(graphics, mouseX, mouseY);
    }

    private static int[] navArea() {
        int[] content = contentArea();
        return new int[] { content[0], area.getY() + LINE_GAP, content[1], NAV_HEIGHT };
    }

    private static void drawNavigation(GuiGraphics graphics, int mouseX, int mouseY) {
        int[] nav = navArea();
        int x = nav[0];
        int y = nav[1];
        int width = nav[2];
        int height = nav[3];
        int button = Math.min(height, width / 2);
        if (button <= 0) {
            return;
        }
        int backX = x;
        int nextX = x + width - button;
        graphics.fill(backX + button, y, nextX, y + height,
            guiColor("PAGE_NAVIGATION_BACKGROUND", 0x00000000));

        int pages = pageCount();
        int page = pageIndex();
        String label = (page + 1) + "/" + pages;
        var font = Minecraft.getInstance().font;
        int available = width - button * 2;
        if (font.width(label) <= available) {
            graphics.drawString(font, label,
                x + Math.round((width - font.width(label)) / 2.0f),
                y + Math.round((height - font.lineHeight) / 2.0f),
                guiColor("PAGE_NAVIGATION_TEXT", 0xFFE0E0E0), false);
        }
        drawNavButton(graphics, backX, y, button, height, true, page > 0, mouseX, mouseY);
        drawNavButton(graphics, nextX, y, button, height, false, page + 1 < pages, mouseX, mouseY);
    }

    private static void drawNavButton(
        GuiGraphics graphics,
        int x,
        int y,
        int width,
        int height,
        boolean previous,
        boolean enabled,
        int mouseX,
        int mouseY
    ) {
        boolean hovered = inRect(mouseX, mouseY, x, y, width, height);
        drawButtonFrame(graphics, x, y, width, height, enabled, hovered);
        Object texture = textureDrawable(previous ? "getArrowPrevious" : "getArrowNext");
        if (!(texture instanceof IDrawable icon)) {
            return;
        }
        int color = !enabled ? 0xFFA0A0A0 : (hovered ? 0xFFFFFFFF : 0xFFE0E0E0);
        RenderSystem.setShaderColor(
            (color >> 16 & 255) / 255.0F,
            (color >> 8 & 255) / 255.0F,
            (color & 255) / 255.0F,
            (color >> 24 & 255) / 255.0F
        );
        graphics.pose().pushPose();
        graphics.pose().translate(
            x + (width - icon.getWidth()) / 2.0,
            y + (height - icon.getHeight()) / 2.0,
            0
        );
        icon.draw(graphics);
        graphics.pose().popPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawButtonFrame(
        GuiGraphics graphics,
        int x,
        int y,
        int width,
        int height,
        boolean enabled,
        boolean hovered
    ) {
        try {
            Object textures = mezz.jei.common.Internal.getTextures();
            Object frame = textures.getClass()
                .getMethod("getButtonForState", boolean.class, boolean.class, boolean.class)
                .invoke(textures, false, enabled, hovered);
            if (frame instanceof IScalableDrawable drawable) {
                drawable.draw(graphics, x, y, width, height);
                return;
            }
        } catch (Throwable ignored) {

        }
        graphics.fill(x, y, x + width, y + height, enabled ? 0xFF8B8B8B : 0xFF5A5A5A);
    }

    private enum NavHit {
        NONE,
        BACK,
        NEXT
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static NavHit navHit(double mouseX, double mouseY) {
        int[] nav = navArea();
        int size = Math.min(nav[3], nav[2] / 2);
        if (size <= 0) {
            return NavHit.NONE;
        }
        if (inRect(mouseX, mouseY, nav[0], nav[1], size, nav[3])) {
            return NavHit.BACK;
        }
        if (inRect(mouseX, mouseY, nav[0] + nav[2] - size, nav[1], size, nav[3])) {
            return NavHit.NEXT;
        }
        return NavHit.NONE;
    }

    private static boolean handleNavClick(double mouseX, double mouseY, int button) {
        if (button != 0 || area.isEmpty()) {
            return false;
        }
        NavHit hit = navHit(mouseX, mouseY);
        if (hit == NavHit.NONE) {
            return false;
        }
        if (hit == NavHit.BACK && pageIndex() > 0) {
            turnPage(-1);
        } else if (hit == NavHit.NEXT && pageIndex() + 1 < pageCount()) {
            turnPage(1);
        }
        return true;
    }

    private static void turnPage(int delta) {
        int pages = pageCount();

        int target = Math.max(0, Math.min(pages - 1, pageIndex() + delta));
        scrollRow = target * visibleRows;
        JERTLog.debug("sidebar tree panel: page {}/{} (scrollRow={}, rows={}, perPage={})",
            target + 1, pages, scrollRow, totalRows(), visibleRows);
    }

    private static void drawPanelBackground(GuiGraphics graphics, PanelBackground style) {
        if (style != PanelBackground.PANEL) {
            return;
        }

        int[] content = contentArea();
        int x = content[0] - BORDER_PADDING;
        int width = content[1] + BORDER_PADDING * 2;
        Object background = textureDrawable("getBookmarkListBackground");
        if (background instanceof IScalableDrawable drawable) {
            drawable.draw(graphics, x, area.getY(), width, area.getHeight());
            return;
        }
        graphics.fill(x, area.getY(), x + width, area.getY() + area.getHeight(), FALLBACK_BACKGROUND);
    }

    @Nullable
    private static Object invokeFirstAccessor(Object target, @Nullable Object fallbackTarget, List<String> accessors) {
        for (String accessor : accessors) {
            try {
                Object result = target.getClass().getMethod(accessor).invoke(target);
                if (result != null) {
                    return result;
                }
            } catch (NoSuchMethodException ignored) {

            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return fallbackTarget;
    }

    private enum PanelBackground {

        NONE,

        BORDER_ONLY,

        PANEL
    }

    private static boolean warnedBackgroundProbe;

    @Nullable
    private static Object gridConfig() {
        Object configs = invokeFirstAccessor(mezz.jei.common.Internal.class, null,
            List.of("getJeiClientConfigs", "getClientConfigs"));
        if (configs == null) {
            return null;
        }
        return invokeFirstAccessor(configs, configs,
            List.of("getBookmarkListConfig", "getIngredientListConfig"));
    }

    private static int jeiGridLimit(boolean columns) {
        try {
            Object config = gridConfig();
            if (config == null) {
                return DEFAULT_MAX_COLUMNS;
            }
            Object value = config.getClass().getMethod(columns ? "maxColumns" : "maxRows").invoke(config);
            return readInt(value, DEFAULT_MAX_COLUMNS);
        } catch (Throwable ignored) {
            return DEFAULT_MAX_COLUMNS;
        }
    }

    private static int readInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        for (String getter : List.of("getValue", "get")) {
            try {
                Object raw = value.getClass().getMethod(getter).invoke(value);
                if (raw instanceof Number number) {
                    return number.intValue();
                }
            } catch (NoSuchMethodException ignored) {

            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static PanelBackground jeiBackgroundStyle() {
        try {
            Object gridConfig = gridConfig();
            if (gridConfig == null) {
                return PanelBackground.NONE;
            }

            try {
                Object value = gridConfig.getClass().getMethod("drawBackground").invoke(gridConfig);
                return readInt(value, 0) != 0 ? PanelBackground.PANEL : PanelBackground.NONE;
            } catch (NoSuchMethodException ignored) {

            }
            Object style = gridConfig.getClass().getMethod("backgroundStyle").invoke(gridConfig);
            String name = ((Enum<?>) style).name();
            if ("BACKGROUND".equals(name) || "GRID".equals(name)) {
                return PanelBackground.PANEL;
            }
            if ("BORDER_ONLY".equals(name)) {
                return PanelBackground.BORDER_ONLY;
            }
            return PanelBackground.NONE;
        } catch (Throwable error) {
            if (!warnedBackgroundProbe) {
                warnedBackgroundProbe = true;
                JERTLog.warn("Unable to read JEI background config; tree panel falls back to none", error);
            }
            return PanelBackground.NONE;
        }
    }

    private static void drawSeparator(GuiGraphics graphics, PanelBackground style) {
        int color = lookupHistoryLineColor();

        int[] content = contentArea();
        int x0 = content[0];
        int width = content[1];
        if (style == PanelBackground.NONE) {
            DashedRule.horizontal(graphics, x0, x0 + width, area.getY(), color);
            return;
        }
        graphics.fill(x0, area.getY(), x0 + width, area.getY() + SEPARATOR, color);
    }

    private static int lookupHistoryLineColor() {
        return guiColor("LOOKUP_HISTORY_LINE", FALLBACK_SEPARATOR);
    }

    private static int guiColor(String name, int fallback) {
        try {
            Class<?> colors = Class.forName("mezz.jei.common.gui.JeiGuiColors");
            Class<?> guiColor = Class.forName("mezz.jei.common.gui.JeiGuiColors$GuiColor");
            for (Object candidate : guiColor.getEnumConstants()) {
                if (name.equals(((Enum<?>) candidate).name())) {
                    return (Integer) colors.getMethod("getColor", guiColor).invoke(null, candidate);
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object textureDrawable(String getter) {
        try {
            Object textures = mezz.jei.common.Internal.getTextures();
            if (textures == null) {
                return null;
            }
            return textures.getClass().getMethod(getter).invoke(textures);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void drawSlot(
        GuiGraphics graphics,
        ImmutableRect2i slot,
        IElement<?> element,
        PanelBackground style,
        boolean hovered
    ) {
        int x = slot.getX();
        int y = slot.getY();

        if (style == PanelBackground.PANEL) {
            Object background = textureDrawable("getBookmarkListSlotBackground");
            if (background instanceof IScalableDrawable drawable) {
                drawable.draw(graphics, x, y, SLOT, SLOT);
            } else {
                graphics.fill(x, y, x + SLOT, y + SLOT, FALLBACK_SLOT_BORDER);
                graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, FALLBACK_SLOT);
            }
        }

        TreeRowAccess row = asRow(element);
        ITypedIngredient<?> typed = row != null ? row.typedIngredient() : element.getTypedIngredient();

        drawIngredient(graphics, typed, x + 1, y + 1);

        var overlay = element.createRenderOverlay();

        if (overlay instanceof AmountOverlay amount) {
            drawFrame(graphics, x, y, SLOT, amount.stateColor());
        }
        if (overlay != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, RenderLayers.OVERLAY_BADGE);
            overlay.draw(graphics, 0, 0);
            graphics.pose().popPose();
        }
        if (hovered) {

            graphics.fillGradient(
                RenderType.guiOverlay(),
                x, y, x + SLOT, y + SLOT,
                HOVER_MASK, HOVER_MASK, 0
            );
        }
    }

    private static void drawFrame(GuiGraphics graphics, int x, int y, int size, int color) {
        RenderType layer = RenderType.guiOverlay();
        graphics.fill(layer, x, y, x + size, y + 1, color);
        graphics.fill(layer, x, y + size - 1, x + size, y + size, color);
        graphics.fill(layer, x, y + 1, x + 1, y + size - 1, color);
        graphics.fill(layer, x + size - 1, y + 1, x + size, y + size - 1, color);
    }

    private static void drawIngredient(GuiGraphics graphics, ITypedIngredient<?> typed, int x, int y) {
        if (typed == null) {
            return;
        }
        if (DirectoryRecipePlugin.getJeiRuntime() == null) {
            return;
        }
        try {

            IngredientDisplay.render(graphics, typed, x, y);
        } catch (RuntimeException exception) {

            JERTLog.debug("could not render tree row", exception);
        }
    }

    private static TreeRowAccess asRow(IElement<?> element) {
        return element instanceof TreeRowAccess row ? row : null;
    }

    private static int indexAt(double mouseX, double mouseY) {
        if (area.isEmpty() || !area.contains(mouseX, mouseY)) {
            return -1;
        }
        int x = area.getX() + inset(panelStyle);
        int y = gridTop(panelStyle);
        int column = (int) ((mouseX - x) / PITCH);
        int row = (int) ((mouseY - y) / PITCH);
        if (column < 0 || column >= columns || row < 0 || row >= visibleRows) {
            return -1;
        }
        int index = (scrollRow + row) * columns + column;
        if (index < 0 || index >= RecipeTreeFavorites.rows().size()) {
            return -1;
        }

        ImmutableRect2i slot = slotOf(index);
        return slot != null && slot.contains(mouseX, mouseY) ? index : -1;
    }

    public static boolean handleClick(Object input) {
        if (area.isEmpty() || input == null) {
            return false;
        }
        var key = JeiReflectionCompat.inputKey(input);
        if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return false;
        }
        double mouseX = JeiReflectionCompat.inputMouseX(input);
        double mouseY = JeiReflectionCompat.inputMouseY(input);
        if (consumedRecently(InputSource.LEGACY_PROXY, mouseX, mouseY)) {
            JERTLog.debug("proxy click skipped (event layer already handled)");
            return false;
        }
        boolean simulate = JeiReflectionCompat.isInputSimulated(input);
        boolean handled = handleClickAt(mouseX, mouseY, key.getValue(), simulate,
            InputSource.LEGACY_PROXY);
        if (handled && !simulate) {
            recordConsumption(InputSource.LEGACY_PROXY, mouseX, mouseY);
        }
        return handled;
    }

    public static boolean handleClickAt(double mouseX, double mouseY, int button, boolean simulate,
                                        InputSource source) {
        if (!simulate && consumedRecently(source, mouseX, mouseY)) {
            JERTLog.debug("click skipped (other path already handled)");
            return false;
        }

        JERTLog.debug("click: visible={} area={} simulate={}", overlayVisible, area, simulate);
        if (!overlayVisible || area.isEmpty()) {
            JERTLog.debug("click -> rejected by gate");
            return false;
        }
        if (handleNavClick(mouseX, mouseY, button)) {
            JERTLog.debug("click -> page navigation");
            return true;
        }
        int index = indexAt(mouseX, mouseY);
        if (index < 0 || button != 0) {
            JERTLog.debug("click -> no hit (index={}, button={})", index, button);
            return false;
        }
        List<IElement<?>> rows = RecipeTreeFavorites.rows();
        TreeRowAccess row = asRow(rows.get(index));
        if (row == null) {
            JERTLog.debug("click -> row null at {}", index);
            return false;
        }
        JERTLog.debug("click -> row ok at {} ({})", index, String.valueOf(row.typedIngredient()));
        boolean ctrl = net.minecraft.client.gui.screens.Screen.hasControlDown();
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if (ctrl || shift) {
            boolean recursive = ctrl && SafeConfig.automaticCraftingEnabled();
            if (ctrl && !recursive) {
                JERTLog.debug("transfer -> recursive crafting disabled by config");
                return false;
            }
            boolean canTransfer = row.canTransfer(recursive);
            JERTLog.debug("transfer -> canTransfer={} (ctrl={} shift={} recursive={} source={})",
                canTransfer, ctrl, shift, recursive, source);
            if (!canTransfer) {
                return false;
            }
            if (!simulate) {
                boolean moved = row.transfer(recursive);
                JERTLog.debug("transfer -> done={}", moved);
                recordConsumption(source, mouseX, mouseY);
            }
            return true;
        }

        if (simulate) {
            return true;
        }
        boolean opened = showRecipes(row.typedIngredient());
        JERTLog.debug("showRecipes -> {}", opened);
        if (opened && !simulate) {
            recordConsumption(source, mouseX, mouseY);
        }
        return opened;
    }

    private static boolean legacyInputProxy;

    private static final long DUPLICATE_WINDOW_NANOS = 200_000_000L;
    private static long lastEventLayerNanos;
    private static double lastEventLayerX;
    private static double lastEventLayerY;
    private static long lastProxyNanos;
    private static double lastProxyX;
    private static double lastProxyY;
    private static boolean notedEventLayerTakeover;

    public enum InputSource {
        EVENT_LAYER,
        LEGACY_PROXY
    }

    private static boolean consumedRecently(InputSource source, double mouseX, double mouseY) {
        boolean byEventLayer = source == InputSource.EVENT_LAYER;
        long otherNanos = byEventLayer ? lastProxyNanos : lastEventLayerNanos;
        double otherX = byEventLayer ? lastProxyX : lastEventLayerX;
        double otherY = byEventLayer ? lastProxyY : lastEventLayerY;
        if (otherNanos == 0L) {
            return false;
        }
        long delta = System.nanoTime() - otherNanos;
        return delta >= 0L && delta < DUPLICATE_WINDOW_NANOS
            && Math.abs(mouseX - otherX) < 2.0 && Math.abs(mouseY - otherY) < 2.0;
    }

    private static void recordConsumption(InputSource source, double mouseX, double mouseY) {
        if (source == InputSource.EVENT_LAYER) {
            lastEventLayerNanos = System.nanoTime();
            lastEventLayerX = mouseX;
            lastEventLayerY = mouseY;
        } else {
            lastProxyNanos = System.nanoTime();
            lastProxyX = mouseX;
            lastProxyY = mouseY;
        }
    }

    private static boolean overlayVisible;

    public static void markLegacyInputProxy() {
        if (!legacyInputProxy) {
            JERTLog.summary("legacy input proxy installed (old JEI API kept); event layer stays armed");
        }
        legacyInputProxy = true;
    }

    public static boolean isLegacyInputProxy() {
        return legacyInputProxy;
    }

    public static void noteEventLayerTakeover() {
        if (!notedEventLayerTakeover) {
            notedEventLayerTakeover = true;
            JERTLog.summary("input: JEI 19.64+ ignores the legacy proxy; "
                + "clicks are handled by the NeoForge event layer");
        }
    }

    public static void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
    }

    public static boolean isOverlayVisible() {
        return overlayVisible;
    }

    private static boolean showRecipes(ITypedIngredient<?> typed) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null) {
            JERTLog.debug("showRecipes -> no JEI runtime");
            return false;
        }
        if (typed == null) {
            JERTLog.debug("showRecipes -> no typed ingredient");
            return false;
        }
        Optional<ItemStack> stack = typed.getItemStack();
        if (stack.isEmpty()) {
            JERTLog.debug("showRecipes -> empty item stack");
            return false;
        }
        try {
            IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();
            IFocus<ItemStack> focus = focusFactory.createFocus(
                RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack.get());
            IRecipesGui recipesGui = runtime.getRecipesGui();
            recipesGui.show(List.of(focus));
            JERTLog.debug("showRecipes -> opened {}", stack.get());
            return true;
        } catch (RuntimeException exception) {
            JERTLog.warn("could not open recipes from tree panel", exception);
            return false;
        }
    }

    public static boolean handleScroll(double mouseX, double mouseY, double delta) {
        if (!overlayVisible || area.isEmpty() || !area.contains(mouseX, mouseY)) {
            return false;
        }
        if (totalRows() <= visibleRows) {
            return false;
        }
        turnPage(delta > 0 ? 1 : -1);
        return true;
    }
}
