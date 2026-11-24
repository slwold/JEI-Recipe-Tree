package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeRecord;
import com.slwold.jeirecipetree.core.ViewportFit;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.platform.Services;
import mezz.jei.common.util.SafeIngredientUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class RecipeTreeScreen extends Screen {

    private static final float MAX_INTERACTIVE_ZOOM = 2.5f;

    private static final ResourceLocation CREATE_CLIPBOARD_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("create", "textures/item/clipboard.png");

    private static ItemStack iconCraftingTable;
    private static ItemStack iconAnvil;
    private static ItemStack iconWritableBook;

    private static Boolean createLoaded;

    private static RecipeTreeScreen instance;

    private Screen parent;

    private final TreeLayout layout = new TreeLayout();
    private float zoom = 1.0f;
    private float offsetX;
    private float offsetY;
    private boolean dragging;
    private int refreshTicks;

    private long lastInventoryHash = Long.MIN_VALUE;

    private final CandidatePicker treePicker = new CandidatePicker();

    private List<RecipeRecord> pickerRecipes;
    private boolean pickerOpen;
    private int pickerPage;
    private static final int PICKER_COLUMNS = 9;
    private static final int PICKER_ROWS = 4;
    private static final int PICKER_PAGE_SIZE = PICKER_COLUMNS * PICKER_ROWS;
    private static final int PICKER_SLOT = 20;
    private static final int PICKER_WIDTH = PICKER_COLUMNS * PICKER_SLOT + 12;
    private static final int PICKER_HEIGHT = PICKER_ROWS * PICKER_SLOT + 48;

    private static final int ADD_BUTTON_X = 8;
    private static final int ADD_BUTTON_Y = 8;

    private static final int THEME_BUTTON_X = 28;
    private static final int THEME_BUTTON_Y = 8;

    private static final int WELCOME_BUTTON_WIDTH = 120;
    private static final int WELCOME_BUTTON_HEIGHT = 18;

    private static final double MIN_GRID_PIXELS = 4.0;

    private final List<PendingAmount> pendingAmounts = new ArrayList<>();

    private record PendingAmount(String text, int leftX, int baselineY, int color, int zone) {
    }

    private RecipeTreeScreen(Screen parent) {
        super(Component.translatable("jeirecipetree.recipe_tree.title"));
        this.parent = parent;
    }

    public static void openFromRecipeButton(IRecipeLayoutDrawable<?> layout) {
        if (!SafeConfig.recipeTreeEnabled()) {
            return;
        }

        RecipeTreeData.RecipeSnapshot snapshot = RecipeLookup.snapshot(layout).orElse(null);
        RecipeRecord draft = RecipeArchive.draftOf(snapshot, layout);
        if (draft == null) {
            return;
        }

        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        minecraft.setScreen(new RecipeEditScreen(minecraft.screen, layout, new RecipeDraft(draft)));
    }

    static void afterImport(IRecipeLayoutDrawable<?> layout, String recordId) {
        if (RecipeTreeSession.canResolve(layout) && RecipeTreeSession.resolve(layout, recordId)) {
            returnToTree();
            return;
        }

        RecipeRecord saved = storedRecord(recordId);
        if (saved != null) {
            JERTLog.summary("recipe tree: root rebuilt from stored record '{}' (edit applied)", recordId);
        }
        boolean goalSet = saved != null
            ? RecipeTreeSession.setGoalFromRecord(saved)
            : RecipeTreeSession.setGoal(layout);
        if (goalSet) {
            openCurrent(Minecraft.getInstance().screen);
        }
    }

    private static RecipeRecord storedRecord(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        for (RecipeRecord record : StoredRecipes.all()) {
            if (recordId.equals(record.id())) {
                return record;
            }
        }
        return null;
    }

    public static void openCurrent(Screen parent) {
        if (!SafeConfig.recipeTreeEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RecipeTreeScreen open) {
            open.rebuildLayout(false);
            return;
        }
        Screen host = hostScreen(parent);
        if (instance == null) {
            instance = new RecipeTreeScreen(host);
        } else if (host != null) {
            instance.parent = host;
        }
        minecraft.setScreen(instance);
        instance.rebuildLayout(true);
    }

    private static Screen hostScreen(Screen candidate) {
        if (candidate == null
            || candidate instanceof RecipeTreeScreen
            || candidate instanceof RecipeEditScreen
            || candidate instanceof RecipeLibraryScreen) {
            return null;
        }
        return candidate;
    }

    public static void returnToTree() {
        Screen parent = RecipeTreeSession.takeResolutionParent();

        if (instance != null) {
            Minecraft.getInstance().setScreen(instance);
            instance.rebuildLayout(true);
            return;
        }
        openCurrent(parent != null ? parent : Minecraft.getInstance().screen);
    }

    public Screen parentScreen() {
        return parent;
    }

    @Override
    protected void init() {
        RecipeTreeSession.cancelResolution();
        rebuildLayout(true);
    }

    @Override
    public void tick() {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        boolean crafting = tree != null && tree.craftingMode();

        SupplyLedger.setCraftingMode(crafting);
        if (!crafting || ++refreshTicks < 10) {
            return;
        }
        refreshTicks = 0;

        long hash = SupplyLedger.contentHash();
        if (hash == lastInventoryHash) {
            return;
        }
        lastInventoryHash = hash;
        SupplyLedger.invalidate();
        rebuildCosts(tree.analyze());
        RecipeTreeFavorites.refreshNow();
    }

    private void rebuildLayout(boolean recenter) {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree == null) {
            treePicker.close();
            layout.rebuild(null, font, (cost, leftover) -> costText(cost, leftover), TreeText.scale());
            offsetX = 0;
            offsetY = 0;
            return;
        }

        layout.rebuild(tree, font, (cost, leftover) -> costText(cost, leftover), TreeText.scale());
        if (recenter) {
            fitViewToContent();
        }
    }

    private void rebuildCosts(RecipeTreeData.Analysis analysis) {
        layout.rebuildCosts(analysis, font, (cost, leftover) -> costText(cost, leftover), TreeText.scale());
    }

    private void fitViewToContent() {
        if (layout.visibleNodes().isEmpty()) {
            zoom = 1.0f;
            offsetX = 0;
            offsetY = 0;
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (RecipeTreeData.Node node : layout.visibleNodes()) {
            int halfWidth = nodeWidth(node) / 2;
            minX = Math.min(minX, node.x() - halfWidth);
            maxX = Math.max(maxX, node.x() + halfWidth);
            minY = Math.min(minY, node.y() - TreeLayout.NODE_HEIGHT / 2);
            maxY = Math.max(maxY, node.y() + TreeLayout.NODE_HEIGHT / 2);
        }

        int titleWidth = TreeText.width(font, Component.translatable("jeirecipetree.recipe_tree.total_cost"));
        minX = Math.min(minX, layout.costCenterX() - titleWidth / 2);
        maxX = Math.max(maxX, layout.costCenterX() + titleWidth / 2);
        minY = Math.min(minY, layout.costY() - 18);
        maxY = Math.max(maxY, layout.costY() + 8);
        if (layout.costs().stream().anyMatch(TreeLayout.RenderCost::leftover)) {
            int leftoversWidth = TreeText.width(font, Component.translatable("jeirecipetree.recipe_tree.leftovers"));
            minX = Math.min(minX, layout.costCenterX() - leftoversWidth / 2);
            maxX = Math.max(maxX, layout.costCenterX() + leftoversWidth / 2);
            maxY = Math.max(maxY, layout.costY() + 48);
        }
        for (TreeLayout.RenderCost cost : layout.costs()) {
            String text = costText(cost.cost(), cost.leftover());
            int textWidth = Math.min(16, font.width(text));
            minX = Math.min(minX, cost.x() + 17 - textWidth);
            maxX = Math.max(maxX, cost.x() + 17);
            minY = Math.min(minY, cost.y());
            maxY = Math.max(maxY, cost.y() + 16);
        }
        if (!layout.catalysts().isEmpty()) {
            int width2 = TreeText.width(font, Component.translatable("jeirecipetree.recipe_tree.catalysts"));
            minX = Math.min(minX, layout.costCenterX() - width2 / 2);
            maxX = Math.max(maxX, layout.costCenterX() + width2 / 2);
            maxY = Math.max(maxY, layout.costY() + TreeLayout.CATALYST_OFFSET + 16);
        }
        for (TreeLayout.RenderCost cost : layout.catalysts()) {
            minX = Math.min(minX, cost.x());
            maxX = Math.max(maxX, cost.x() + 17);
            minY = Math.min(minY, cost.y());
            maxY = Math.max(maxY, cost.y() + 16);
        }
        minX = Math.min(minX, layout.modeX());
        maxX = Math.max(maxX, layout.modeX() + 16);
        minY = Math.min(minY, layout.modeY());
        maxY = Math.max(maxY, layout.modeY() + 16);
        minX = Math.min(minX, layout.batchX());
        maxX = Math.max(maxX, layout.batchX() + layout.batchWidth() + 6);
        minY = Math.min(minY, layout.batchY());
        maxY = Math.max(maxY, layout.batchY() + 22);

        ViewportFit.Fit fit = ViewportFit.fit(minX, minY, maxX, maxY, width, height);
        zoom = fit.zoom();
        offsetX = fit.offsetX();
        offsetY = fit.offsetY();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {

        TreePalette.syncTheme();

        if (TreeText.sync()) {
            rebuildLayout(true);
        }

        pendingAmounts.clear();
        drawPaperBackground(graphics);
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree == null) {
            renderWelcome(graphics, mouseX, mouseY);
        } else {
            graphics.pose().pushPose();

            graphics.pose().translate(width / 2.0f, height / 2.0f, 0);
            graphics.pose().scale(zoom, zoom, 1.0f);
            graphics.pose().translate(offsetX, offsetY, 0);

            for (RecipeTreeData.Node node : layout.visibleNodes()) {
                if (node.expanded()) {
                    for (RecipeTreeData.Node child : node.children()) {
                        drawConnection(graphics, node, child);
                    }
                }
            }
            for (RecipeTreeData.Node node : layout.visibleNodes()) {
                drawNode(graphics, node, mouseX, mouseY);
            }

            Component totalCost = Component.translatable("jeirecipetree.recipe_tree.total_cost");
            drawTreeCenteredText(graphics, totalCost, layout.costCenterX(), layout.costY() - 18,
                TreePalette.canvasInk());
            if (layout.costs().stream().anyMatch(TreeLayout.RenderCost::leftover)) {
                Component leftovers = Component.translatable("jeirecipetree.recipe_tree.leftovers");
                drawTreeCenteredText(graphics, leftovers, layout.costCenterX(), layout.costY() + 22,
                    TreePalette.canvasInk());
            }
            drawModeButton(graphics, tree, mouseX, mouseY);
            drawBatchLabel(graphics, tree, mouseX, mouseY);
            for (TreeLayout.RenderCost cost : layout.costs()) {
                drawCost(graphics, cost);
            }
            if (!layout.catalysts().isEmpty()) {
                Component catalysts = Component.translatable("jeirecipetree.recipe_tree.catalysts");
                drawTreeCenteredText(
                    graphics,
                    catalysts,
                    layout.costCenterX(),
                    layout.costY() + TreeLayout.CATALYST_OFFSET - 18,
                    TreePalette.canvasInk()
                );
                for (TreeLayout.RenderCost cost : layout.catalysts()) {
                    drawCost(graphics, cost);
                }
            }
            graphics.pose().popPose();

            drawPendingAmounts(graphics);
        }

        drawAddButton(graphics, mouseX, mouseY);
        drawThemeButton(graphics, mouseX, mouseY);
        drawHelp(graphics, mouseX, mouseY);
        if (pickerOpen) {
            renderPicker(graphics, mouseX, mouseY);
        }

        treePicker.render(graphics, width, height, mouseX, mouseY);
        renderHoverTooltip(graphics, mouseX, mouseY);
    }

    private void drawPaperBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, TreePalette.paper());
        double step = TreePalette.GRID_SPACING * (double) zoom;
        if (step <= 0) {
            return;
        }
        double majorStep = step * TreePalette.GRID_MAJOR_EVERY;
        drawGridAxis(graphics, true, majorStep, TreePalette.gridMajor(), 0);
        drawGridAxis(graphics, false, majorStep, TreePalette.gridMajor(), 0);
        if (step >= MIN_GRID_PIXELS) {

            drawGridAxis(graphics, true, step, TreePalette.gridMinor(), TreePalette.GRID_MAJOR_EVERY);
            drawGridAxis(graphics, false, step, TreePalette.gridMinor(), TreePalette.GRID_MAJOR_EVERY);
        }
    }

    private void drawGridAxis(GuiGraphics graphics, boolean vertical, double step, int color, int skipEvery) {
        int size = vertical ? width : height;
        double origin = size / 2.0 + (vertical ? offsetX : offsetY) * (double) zoom;
        long firstIndex = (long) Math.ceil(-origin / step);
        for (long index = firstIndex; ; index++) {
            double position = origin + index * step;
            if (position >= size) {
                return;
            }
            if (skipEvery > 1 && Math.floorMod(index, skipEvery) == 0) {
                continue;
            }
            int line = (int) Math.round(position);
            if (line < 0 || line >= size) {
                continue;
            }
            if (vertical) {
                graphics.fill(line, 0, line + 1, height, color);
            } else {
                graphics.fill(0, line, width, line + 1, color);
            }
        }
    }

    private void drawChoicePageButton(
        GuiGraphics graphics,
        int x,
        int y,
        String text,
        boolean enabled,
        boolean hovered
    ) {
        int border = enabled && hovered ? TreePalette.accent() : TreePalette.rule2();
        graphics.fill(x, y, x + 16, y + 14, border);
        graphics.fill(x + 1, y + 1, x + 15, y + 13, TreePalette.paper2());
        drawCenteredNoShadow(graphics, text, x + 8, y + 3, enabled ? TreePalette.ink() : TreePalette.ink3());
    }

    private void renderWelcome(GuiGraphics graphics, int mouseX, int mouseY) {
        drawTreeCenteredText(graphics, title, width / 2, height / 2 - 36, TreePalette.ink());
        drawTreeCenteredText(
            graphics,
            Component.translatable("jeirecipetree.recipe_tree.empty"),
            width / 2,
            height / 2 - 12,
            TreePalette.ink2()
        );
        drawTreeCenteredText(
            graphics,
            Component.translatable("jeirecipetree.recipe_tree.empty_hint"),
            width / 2,
            height / 2 + 6,
            TreePalette.ink2()
        );
        drawWelcomeButton(graphics, mouseX, mouseY);
    }

    private void drawWelcomeButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = width / 2 - WELCOME_BUTTON_WIDTH / 2;
        int y = welcomeButtonY();
        boolean hovered = isWelcomeButton(mouseX, mouseY);
        graphics.fill(x, y, x + WELCOME_BUTTON_WIDTH, y + WELCOME_BUTTON_HEIGHT,
            hovered ? TreePalette.accent() : TreePalette.rule2());
        graphics.fill(x + 1, y + 1, x + WELCOME_BUTTON_WIDTH - 1, y + WELCOME_BUTTON_HEIGHT - 1,
            TreePalette.paper2());
        drawCenteredNoShadow(
            graphics,
            Component.translatable("jeirecipetree.recipe_tree.picker.button"),
            width / 2,
            y + 5,
            hovered ? TreePalette.accent() : TreePalette.ink()
        );
    }

    private int welcomeButtonY() {
        return height / 2 + 24;
    }

    private boolean isWelcomeButton(double mouseX, double mouseY) {
        return contains(mouseX, mouseY, width / 2 - WELCOME_BUTTON_WIDTH / 2, welcomeButtonY(),
            WELCOME_BUTTON_WIDTH, WELCOME_BUTTON_HEIGHT);
    }

    private void drawThemeButton(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean hovered = isThemeButton(mouseX, mouseY);
        graphics.fill(THEME_BUTTON_X, THEME_BUTTON_Y, THEME_BUTTON_X + 16, THEME_BUTTON_Y + 16,
            hovered ? TreePalette.accent() : TreePalette.rule2());
        graphics.fill(THEME_BUTTON_X + 1, THEME_BUTTON_Y + 1, THEME_BUTTON_X + 15, THEME_BUTTON_Y + 15,
            TreePalette.paper2());

        graphics.fill(THEME_BUTTON_X + 3, THEME_BUTTON_Y + 3, THEME_BUTTON_X + 6, THEME_BUTTON_Y + 13,
            TreePalette.tape());
        graphics.fill(THEME_BUTTON_X + 7, THEME_BUTTON_Y + 3, THEME_BUTTON_X + 10, THEME_BUTTON_Y + 13,
            TreePalette.tapeInk());
        graphics.fill(THEME_BUTTON_X + 11, THEME_BUTTON_Y + 3, THEME_BUTTON_X + 14, THEME_BUTTON_Y + 13,
            TreePalette.accent());
        if (hovered) {
            graphics.renderTooltip(
                font,
                Component.translatable("jeirecipetree.recipe_tree.theme", TreePalette.activeId()),
                mouseX,
                mouseY
            );
        }
    }

    private static boolean isThemeButton(double mouseX, double mouseY) {
        return contains(mouseX, mouseY, THEME_BUTTON_X, THEME_BUTTON_Y, 16, 16);
    }

    private void drawConnection(GuiGraphics graphics, RecipeTreeData.Node parent, RecipeTreeData.Node child) {
        int y1 = parent.y() + TreeLayout.NODE_HEIGHT / 2;
        int y2 = child.y() - TreeLayout.NODE_HEIGHT / 2;
        int middle = (y1 + y2) / 2;
        int color = lineColor(false);
        graphics.fill(parent.x(), y1, parent.x() + 1, middle + 1, color);
        graphics.fill(child.x(), middle, child.x() + 1, y2 + 1, color);
        graphics.fill(Math.min(parent.x(), child.x()), middle, Math.max(parent.x(), child.x()) + 1, middle + 1, color);

        TreeGlyphs.arrowDown(graphics, child.x(), y2, color);
    }

    private void drawNode(GuiGraphics graphics, RecipeTreeData.Node node, int mouseX, int mouseY) {
        int width = nodeWidth(node);
        int left = node.x() - width / 2;
        int top = node.y() - TreeLayout.NODE_HEIGHT / 2;
        boolean hovered = hoveredNode(mouseX, mouseY) == node;
        drawTape(graphics, node, left, top, width, hovered);
        if (node.recipe() != null) {
            IDrawable icon = node.recipe().ref().category() == null
                ? null : node.recipe().ref().category().getIcon();
            if (icon != null) {
                icon.draw(graphics, left + TreeLayout.TAPE_INSET, top + 3);
            } else {
                drawCategoryFallback(graphics, left + TreeLayout.TAPE_INSET, top + 3, TreePalette.tapeInk());
            }
        }

        int itemX = node.recipe() == null
            ? left + TreeLayout.TAPE_INSET
            : left + TreeLayout.TAPE_INSET + 18 + 3;
        int itemY = top + 3;

        if (!IngredientDisplay.render(graphics, node.material(), itemX, itemY)) {
            ItemStack renderStack = FluidRecipeCompat.copyWithDisplay(node.stack());
            if (!renderStack.isEmpty()) {
                renderStack.setCount(1);
                graphics.renderItem(renderStack, itemX, itemY);
            }
        }

        queueAmount(
            formatNodeAmount(node.stack(), node.amount()),
            TreeLayout.amountLeft(node, left),
            tapeTextTop(top),
            TreePalette.tapeInk(),
            TreeLayout.AMOUNT_ZONE
        );
        if (node.hasAlternatives()) {
            drawAlternativeMarker(graphics, node, itemX, itemY);
        }
        if (node.recipe() != null) {
            drawTransferButton(graphics, node, left, top, mouseX, mouseY);
        }

        if (node.recipe() != null && !node.children().isEmpty() && !node.expanded()) {

            TreeGlyphs.expander(graphics, node.x(), top + TreeLayout.NODE_HEIGHT + 1, TreePalette.accent());
        }
    }

    private static void drawTapeRect(GuiGraphics graphics, int left, int top, int width) {
        int height = TreeLayout.NODE_HEIGHT;
        graphics.fill(left, top, left + width, top + height, TreePalette.tape());
        graphics.fill(left, top, left + width, top + 1, TreePalette.tapeEdge());
        graphics.fill(left, top + height - 1, left + width, top + height, TreePalette.tapeEdge());
    }

    private void drawTape(
        GuiGraphics graphics,
        RecipeTreeData.Node node,
        int left,
        int top,
        int width,
        boolean hovered
    ) {
        drawTapeRect(graphics, left, top, width);
        int border = node.cycle()
            ? TreePalette.missing()
            : (hovered ? TreePalette.accent() : 0);
        if (border != 0) {
            outline(graphics, left, top, width, TreeLayout.NODE_HEIGHT, border);
        }
    }

    private static void outline(GuiGraphics graphics, int left, int top, int width, int height, int color) {
        graphics.fill(left, top, left + width, top + 1, color);
        graphics.fill(left, top + height - 1, left + width, top + height, color);
        graphics.fill(left, top + 1, left + 1, top + height - 1, color);
        graphics.fill(left + width - 1, top + 1, left + width, top + height - 1, color);
    }

    private void drawAlternativeMarker(GuiGraphics graphics, RecipeTreeData.Node node, int itemX, int itemY) {
        int x = itemX + 10;
        int y = itemY - 2;
        int color = node.explicitChoice() ? TreePalette.ok() : TreePalette.accent();
        drawPlusMarker(graphics, x, y, color);
    }

    private void drawPlusMarker(GuiGraphics graphics, int x, int y, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.PLUS_MARKER);
        TreeGlyphs.plus(graphics, x, y, color);
        graphics.pose().popPose();
    }

    private void drawTransferButton(
        GuiGraphics graphics,
        RecipeTreeData.Node node,
        int left,
        int top,
        int mouseX,
        int mouseY
    ) {

        int x = left + TreeLayout.TAPE_INSET + 18 - TreeLayout.TRANSFER_BUTTON_SIZE - 1;
        int y = top + 2 + 18 - TreeLayout.TRANSFER_BUTTON_SIZE - 1;

        drawPlusMarker(graphics, x, y, TreePalette.accent());
    }

    private void drawCategoryFallback(GuiGraphics graphics, int x, int y, int color) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                graphics.fill(x + column * 5, y + row * 5, x + column * 5 + 3, y + row * 5 + 3, color);
            }
        }
    }

    private void drawModeButton(GuiGraphics graphics, RecipeTreeData.Tree tree, int mouseX, int mouseY) {
        int[] treeMouse = treeMouse(mouseX, mouseY);
        boolean hovered = modeButtonHovered(treeMouse);
        if (tree.craftingMode()) {
            graphics.renderItem(usesAnvil(tree) ? anvilIcon() : craftingTableIcon(), layout.modeX(), layout.modeY());
        } else if (createLoaded()) {
            graphics.blit(CREATE_CLIPBOARD_TEXTURE, layout.modeX(), layout.modeY(), 0, 0, 16, 16, 16, 16);
        } else {
            graphics.renderItem(writableBookIcon(), layout.modeX(), layout.modeY());
        }
        if (hovered) {
            int accent = TreePalette.accent();
            graphics.fill(layout.modeX() - 1, layout.modeY() - 1, layout.modeX() + 17, layout.modeY(), accent);
            graphics.fill(layout.modeX() - 1, layout.modeY() + 16, layout.modeX() + 17, layout.modeY() + 17, accent);
            graphics.fill(layout.modeX() - 1, layout.modeY(), layout.modeX(), layout.modeY() + 16, accent);
            graphics.fill(layout.modeX() + 16, layout.modeY(), layout.modeX() + 17, layout.modeY() + 16, accent);
        }
    }

    private static boolean usesAnvil(RecipeTreeData.Tree tree) {
        try {
            RecipeTreeData.Node root = tree.root();
            if (root == null || root.recipe() == null || root.recipe().ref().category() == null) {
                return false;
            }
            String uid = root.recipe().ref().category().getRecipeType().getUid().toString().toLowerCase(Locale.ROOT);
            return uid.contains("smithing") || uid.contains("anvil");
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean createLoaded() {
        if (createLoaded == null) {
            createLoaded = ModList.get().isLoaded("create");
            JERTLog.debug("mode icon: Create {}", createLoaded ? "present, use create:item/clipboard" : "absent, fallback to writable_book");
        }
        return createLoaded;
    }

    private static ItemStack writableBookIcon() {
        if (iconWritableBook == null) {
            iconWritableBook = new ItemStack(Items.WRITABLE_BOOK);
        }
        return iconWritableBook;
    }

    private static ItemStack craftingTableIcon() {
        if (iconCraftingTable == null) {
            iconCraftingTable = new ItemStack(Items.CRAFTING_TABLE);
        }
        return iconCraftingTable;
    }

    private static ItemStack anvilIcon() {
        if (iconAnvil == null) {
            iconAnvil = new ItemStack(Items.ANVIL);
        }
        return iconAnvil;
    }

    private boolean modeButtonHovered(int[] treeMouse) {
        int pad = (TreeLayout.MODE_BUTTON_HIT - TreeLayout.MODE_BUTTON_SIZE) / 2;
        return containsTreeArea(treeMouse[0], treeMouse[1],
            layout.modeX() - pad, layout.modeY() - pad, TreeLayout.MODE_BUTTON_HIT, TreeLayout.MODE_BUTTON_HIT);
    }

    private void drawBatchLabel(GuiGraphics graphics, RecipeTreeData.Tree tree, int mouseX, int mouseY) {
        int[] treeMouse = treeMouse(mouseX, mouseY);
        int color = containsTreeArea(treeMouse[0], treeMouse[1], layout.batchX(), layout.batchY(), layout.batchWidth(), 22)
            ? TreePalette.accent()
            : TreePalette.ink();
        Component text = AmountText.latin("x" + tree.batches());

        drawTreeText(graphics, text, layout.batchX() + 6, layout.batchY() + 7, color);
    }

    private void drawTreeCenteredText(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        float scale = TreeText.scale();
        if (scale == 1.0f) {
            drawCenteredNoShadow(graphics, text, centerX, y, color);
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0);
        graphics.pose().scale(scale, scale, 1.0f);
        drawCenteredNoShadow(graphics, text, 0, 0, color);
        graphics.pose().popPose();
    }

    private void drawCenteredNoShadow(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        TreeText.centered(graphics, font, text, centerX, y, color);
    }

    private void drawCenteredNoShadow(GuiGraphics graphics, String text, int centerX, int y, int color) {
        TreeText.centered(graphics, font, text, centerX, y, color);
    }

    private void drawTreeText(GuiGraphics graphics, Component text, int left, int top, int color) {
        float scale = TreeText.scale();
        if (scale == 1.0f) {
            graphics.drawString(font, text, left, top, color, false);
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private List<TreeLayout.RenderCost> catalystAt(int[] point) {
        List<TreeLayout.RenderCost> hits = new ArrayList<>();
        for (TreeLayout.RenderCost cost : layout.catalysts()) {
            if (costHit(point, cost)) {
                hits.add(cost);
            }
        }
        return hits;
    }

    private int tapeTextTop(int tapeTop) {
        return tapeTop + Math.round((TreeLayout.NODE_HEIGHT - font.lineHeight) / 2.0f);
    }

    private static boolean costHit(int[] point, TreeLayout.RenderCost cost) {
        return point[0] >= cost.x() && point[0] < cost.x() + cost.width()
            && point[1] >= cost.y() && point[1] < cost.y() + TreeLayout.NODE_HEIGHT;
    }

    private void drawCost(GuiGraphics graphics, TreeLayout.RenderCost renderCost) {
        int width = renderCost.width();
        drawTapeRect(graphics, renderCost.x(), renderCost.y(), width);
        if (!renderCost.leftover() && costShort(renderCost.cost())) {
            outline(graphics, renderCost.x(), renderCost.y(), width, TreeLayout.NODE_HEIGHT,
                TreePalette.missing());
        }
        int itemX = renderCost.x() + TreeLayout.TAPE_INSET;
        int itemY = renderCost.y() + 3;
        if (!IngredientDisplay.render(graphics, renderCost.cost().material(), itemX, itemY)) {
            ItemStack stack = FluidRecipeCompat.copyWithDisplay(renderCost.cost().stack());
            if (!stack.isEmpty()) {
                stack.setCount(1);
                graphics.renderItem(stack, itemX, itemY);
            }
        }
        queueAmount(
            costText(renderCost.cost(), renderCost.leftover()),
            renderCost.x() + TreeLayout.TAPE_INSET + TreeLayout.ITEM_SIZE + TreeLayout.CONTENT_GAP,
            tapeTextTop(renderCost.y()),
            TreePalette.tapeInk(),
            TreeLayout.AMOUNT_ZONE
        );
    }

    private void queueAmount(String text, int leftX, int baselineY, int color, int zone) {
        if (text == null || text.isEmpty()) {
            return;
        }
        pendingAmounts.add(new PendingAmount(text, leftX, baselineY, color, zone));
    }

    private void drawPendingAmounts(GuiGraphics graphics) {
        if (pendingAmounts.isEmpty()) {
            return;
        }
        var font = Minecraft.getInstance().font;
        float scale = zoom * TreeText.scale();
        for (PendingAmount pending : pendingAmounts) {
            float fit = AmountText.fitScale(font, pending.text(), pending.zone());
            float drawScale = scale * fit;
            int left = Math.round(width / 2.0f + (pending.leftX() + offsetX) * zoom);
            int textWidth = font.width(AmountText.latin(pending.text()));
            int right = left + Math.round(textWidth * drawScale);
            int baseline = Math.round(height / 2.0f + (pending.baselineY() + offsetY) * zoom);
            AmountText.drawAt(
                graphics, font, pending.text(), right, baseline,
                pending.color(), RenderLayers.AMOUNT_BADGE, drawScale);
        }
        pendingAmounts.clear();
    }

    private void drawHelp(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = width - 18;
        int y = height - 18;
        int color = contains(mouseX, mouseY, x, y, 16, 16) ? TreePalette.accent() : TreePalette.ink();
        drawCenteredNoShadow(graphics, "?", x + 8, y + 4, color);
        if (contains(mouseX, mouseY, x, y, 16, 16)) {
            graphics.renderTooltip(
                font,
                font.split(Component.translatable("jeirecipetree.recipe_tree.help"), Math.min(280, width - 30)),
                mouseX,
                mouseY
            );
        }
    }

    private void renderHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.TOOLTIP);
        try {
            renderHoverTooltipInner(graphics, mouseX, mouseY);
        } finally {
            graphics.pose().popPose();
        }
    }

    private void renderHoverTooltipInner(GuiGraphics graphics, int mouseX, int mouseY) {
        if (pickerOpen) {
            RecipeRecord hovered = hoveredPickerRecipe(mouseX, mouseY);
            if (hovered != null) {
                ItemStack preview = StoredRecipes.previewOf(hovered);
                List<Component> lines = new ArrayList<>();
                lines.add(preview.isEmpty()
                    ? Component.literal(hovered.id())
                    : preview.getHoverName());
                if (!hovered.group().isBlank() && !RecipeArchive.GROUP.equals(hovered.group())) {
                    lines.add(Component.literal("[" + hovered.group() + "]").withStyle(ChatFormatting.GRAY));
                }
                lines.add(Component.translatable("jeirecipetree.recipe_tree.picker.pick").withStyle(ChatFormatting.AQUA));
                graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
            }
            return;
        }
        RecipeTreeData.Node node = hoveredNode(mouseX, mouseY);
        if (node != null) {
            if (isTransferArea(node, mouseX, mouseY)) {
                graphics.renderTooltip(
                    font,
                    Component.translatable("jeirecipetree.recipe_tree.transfer"),
                    mouseX,
                    mouseY
                );
            } else if (node.isOutputChoice() && isAlternativeArea(node, mouseX, mouseY)) {
                graphics.renderTooltip(
                    font,
                    Component.translatable("jeirecipetree.recipe_tree.output_choice", node.alternatives().size()),
                    mouseX,
                    mouseY
                );
            } else if (isAlternativeArea(node, mouseX, mouseY)) {
                String key = node.explicitChoice()
                    ? "jeirecipetree.recipe_tree.input_choice.selected"
                    : "jeirecipetree.recipe_tree.input_choice";
                graphics.renderTooltip(font, Component.translatable(key, node.alternatives().size()), mouseX, mouseY);
            } else if ((hasUnfixedAlternatives(node) || node.isOutputChoice()) && isItemArea(node, mouseX, mouseY)) {
                renderAlternativeTooltip(graphics, node, mouseX, mouseY);
            } else if (isCategoryArea(node, mouseX, mouseY) && node.recipe() != null
                && node.recipe().ref().category() != null) {
                graphics.renderTooltip(font, node.recipe().ref().category().getTitle(), mouseX, mouseY);
            } else {
                if (!IngredientDisplay.renderTooltip(graphics, node.stack(), mouseX, mouseY)) {
                    graphics.renderTooltip(font, node.stack(), mouseX, mouseY);
                }
            }
            return;
        }
        int[] treeMouse = treeMouse(mouseX, mouseY);
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree != null && containsTreeArea(treeMouse[0], treeMouse[1], layout.batchX(), layout.batchY(), layout.batchWidth(), 22)) {
            graphics.renderTooltip(font, Component.translatable("jeirecipetree.recipe_tree.batch", tree.batches()), mouseX, mouseY);
        } else if (tree != null && modeButtonHovered(treeMouse)) {
            String key = tree.craftingMode()
                ? "jeirecipetree.recipe_tree.mode.craft"
                : "jeirecipetree.recipe_tree.mode.view";
            graphics.renderTooltip(font, Component.translatable(key), mouseX, mouseY);
        } else {
            for (TreeLayout.RenderCost cost : catalystAt(treeMouse)) {
                if (!IngredientDisplay.renderTooltip(graphics, cost.cost().stack(), mouseX, mouseY)) {
                    graphics.renderTooltip(font, cost.cost().stack(), mouseX, mouseY);
                }
                return;
            }
            for (TreeLayout.RenderCost cost : layout.costs()) {
                if (costHit(treeMouse, cost)) {
                    renderCostTooltip(graphics, cost.cost(), mouseX, mouseY);
                    return;
                }
            }
        }
    }

    private void renderCostTooltip(GuiGraphics graphics, RecipeTreeData.Cost cost, int mouseX, int mouseY) {

        JeiTooltip tooltip = new JeiTooltip();
        boolean rich = IngredientDisplay.collectRich(tooltip, cost.material().typed());
        if (!rich) {
            rich = IngredientDisplay.collectRich(tooltip, cost.stack());
        }
        if (!rich) {
            tooltip.addAll(IngredientDisplay.vanillaLines(cost.stack()));
        }
        JeiTooltip counts = new JeiTooltip();
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree != null && tree.craftingMode()) {
            boolean fluid = IngredientDisplay.measured(cost.stack());
            if (cost.supplied() > 0L) {
                counts.add(Component.translatable("jeirecipetree.recipe_tree.cost.available",
                        quantityText(cost.stack(), cost.supplied(), fluid))
                    .withStyle(ChatFormatting.GRAY));
            }
            if (cost.required() > 0L) {
                counts.add(Component.translatable("jeirecipetree.recipe_tree.cost.missing",
                        quantityText(cost.stack(), cost.required(), fluid))
                    .withStyle(ChatFormatting.GRAY));
            }
        }
        if (rich) {

            tooltip.addIngredientTooltipFooter(counts);
        } else {
            tooltip.addAll(counts);
        }
        tooltip.draw(graphics, mouseX, mouseY);
    }

    private void renderAlternativeTooltip(
        GuiGraphics graphics,
        RecipeTreeData.Node node,
        int mouseX,
        int mouseY
    ) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || node == null) {
            if (node == null || !IngredientDisplay.renderTooltip(graphics, node.stack(), mouseX, mouseY)) {
                graphics.renderTooltip(font, node == null ? ItemStack.EMPTY : node.stack(), mouseX, mouseY);
            }
            return;
        }

        List<ItemStack> alternatives = node.alternatives().stream()
            .map(stack -> {
                ItemStack copy = FluidRecipeCompat.copyWithDisplay(stack);
                copy.setCount(1);
                return copy;
            })
            .toList();
        ItemStack displayed = FluidRecipeCompat.copyWithDisplay(node.stack());
        displayed.setCount(1);
        if (IngredientDisplay.renderTooltip(graphics, displayed, mouseX, mouseY)) {
            return;
        }
        var typed = runtime.getIngredientManager()
            .createTypedIngredient(VanillaTypes.ITEM_STACK, displayed);
        if (typed.isEmpty()) {
            graphics.renderTooltip(font, displayed, mouseX, mouseY);
            return;
        }

        IIngredientRenderer<ItemStack> renderer = runtime.getIngredientManager()
            .getIngredientRenderer(VanillaTypes.ITEM_STACK);
        IIngredientHelper<ItemStack> helper = runtime.getIngredientManager()
            .getIngredientHelper(VanillaTypes.ITEM_STACK);
        JeiTooltip tooltip = new JeiTooltip();
        SafeIngredientUtil.getRichTooltip(tooltip, runtime.getIngredientManager(), renderer, typed.get());
        helper.getTagKeyEquivalent(alternatives).ifPresent(tagKey -> {
            tooltip.add(Component.translatable("jei.tooltip.recipe.tag", "")
                .withStyle(ChatFormatting.GRAY));
            tooltip.add(Services.PLATFORM.getRenderHelper().getName(tagKey)
                .copy().withStyle(ChatFormatting.GRAY));
        });
        if (alternatives.size() > 1) {
            JeiTooltipCompat.createTagContent(runtime, renderer, alternatives)
                .ifPresent(tooltip::add);
        }
        tooltip.draw(graphics, mouseX, mouseY);
    }

    private RecipeTreeData.Node hoveredNode(double mouseX, double mouseY) {
        int[] point = treeMouse(mouseX, mouseY);
        for (RecipeTreeData.Node node : layout.visibleNodes()) {
            int width = nodeWidth(node);
            if (containsTreeArea(point[0], point[1], node.x() - width / 2, node.y() - TreeLayout.NODE_HEIGHT / 2, width, TreeLayout.NODE_HEIGHT)) {
                return node;
            }
        }
        return null;
    }

    private boolean isCategoryArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        if (node.recipe() == null) {
            return false;
        }
        int[] point = treeMouse(mouseX, mouseY);
        int left = node.x() - nodeWidth(node) / 2;
        return containsTreeArea(point[0], point[1], left + TreeLayout.TAPE_INSET, node.y() - 9, 18, 18);
    }

    private boolean isAlternativeArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        if (!node.hasAlternatives()) {
            return false;
        }
        int[] point = treeMouse(mouseX, mouseY);
        int width = nodeWidth(node);
        int left = node.x() - width / 2;

        int itemX = node.recipe() == null
            ? left + TreeLayout.TAPE_INSET
            : left + TreeLayout.TAPE_INSET + 18 + 3;
        int itemY = node.y() - TreeLayout.NODE_HEIGHT / 2 + 3;
        return containsTreeArea(point[0], point[1], itemX + 10, itemY - 2, 7, 7);
    }

    private boolean isTransferArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        if (node == null || node.recipe() == null) {
            return false;
        }
        int[] point = treeMouse(mouseX, mouseY);
        int width = nodeWidth(node);
        int left = node.x() - width / 2;
        int top = node.y() - TreeLayout.NODE_HEIGHT / 2;
        int x = left + 2 + 18 - TreeLayout.TRANSFER_BUTTON_SIZE - 1;
        int y = top + 2 + 18 - TreeLayout.TRANSFER_BUTTON_SIZE - 1;
        return containsTreeArea(point[0], point[1], x, y, TreeLayout.TRANSFER_BUTTON_SIZE, TreeLayout.TRANSFER_BUTTON_SIZE);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {

        if (isGlobalTreeButton(mouseX, mouseY)) {
            if (button == 1) {
                RecipeTreeSession.clear();
                onClose();
                return true;
            }
            if (button == 0) {
                onClose();
                return true;
            }
        }

        if (isThemeButton(mouseX, mouseY) && (button == 0 || button == 1)) {
            TreePalette.cycleTheme(button == 0 ? 1 : -1);
            return true;
        }
        if (RecipeTreeSession.tree() == null && isWelcomeButton(mouseX, mouseY) && button == 0) {
            openPicker();
            return true;
        }
        if (treePicker.isOpen()) {
            return treePicker.mouseClicked(mouseX, mouseY, button, width, height);
        }
        if (pickerOpen) {
            return handlePickerClick(mouseX, mouseY, button);
        }
        if (isAddButton(mouseX, mouseY) && button == 0) {
            openPicker();
            return true;
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        int[] point = treeMouse(mouseX, mouseY);
        if (tree != null && modeButtonHovered(point)) {
            RecipeTreeSession.setCraftingMode(!tree.craftingMode());
            rebuildLayout(false);
            RecipeTreeFavorites.refreshNow();
            return true;
        }
        if (tree != null && containsTreeArea(point[0], point[1], layout.batchX(), layout.batchY(), layout.batchWidth(), 22)) {
            tree.setBatches(tree.idealBatch());
            RecipeTreeSession.savePersistentState();
            rebuildLayout(false);
            RecipeTreeFavorites.refreshNow();
            return true;
        }

        RecipeTreeData.Node node = hoveredNode(mouseX, mouseY);
        if (node != null) {
            if (button == 0 && isTransferArea(node, mouseX, mouseY)) {
                boolean maxTransfer = hasShiftDown();
                tree.directTransferStep(node).ifPresent(step -> {
                    RecipeTreeTransfer.transfer(step, false, maxTransfer);
                });

                if (Minecraft.getInstance().screen == this) {
                    onClose();
                }
                return true;
            }
            if (isAlternativeArea(node, mouseX, mouseY)) {
                if (button == 0) {
                    chooseInput(node);
                } else if (button == 1) {
                    RecipeTreeSession.clearInputSelection(node);
                    rebuildLayout(false);
                }
                return true;
            }
            if (button == 1) {
                if (hasShiftDown()) {
                    RecipeTreeSession.clearResolution(node);
                    rebuildLayout(false);
                } else if (!node.children().isEmpty()) {
                    tree.toggleExpanded(node);
                    rebuildLayout(false);
                }
                return true;
            }
            if (button == 0) {
                if (!hasShiftDown() && hasUnfixedAlternatives(node)
                    && !node.isOutputChoice() && isItemArea(node, mouseX, mouseY)) {

                    chooseInput(node);
                } else if (isCategoryArea(node, mouseX, mouseY) && node.recipe() != null) {
                    showExactRecipe(node);
                } else if (hasShiftDown()) {

                    if (RecipeTreeSession.autoResolve(node)) {
                        rebuildLayout(false);
                    }
                } else {
                    chooseRecipe(node);
                }
                return true;
            }
        }
        if (button == 0 || button == 2) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isGlobalTreeButton(double mouseX, double mouseY) {
        return mouseX >= 6 && mouseX < 26
            && mouseY >= height - 26 && mouseY < height - 6;
    }

    private void chooseInput(RecipeTreeData.Node node) {
        if (node == null || !node.hasAlternatives()) {
            return;
        }
        List<ItemRef> refs = node.alternatives().stream().map(ItemKeys::of).toList();
        treePicker.open(refs, ref -> {

            ItemStack match = node.alternatives().stream()
                .filter(alternative -> ItemKeys.of(alternative).key().equals(ref.key()))
                .findFirst()
                .orElse(null);
            if (match != null && RecipeTreeSession.selectInput(node, match)) {
                rebuildLayout(false);
            }
        });
    }

    private boolean hasUnfixedAlternatives(RecipeTreeData.Node node) {
        return node != null && node.hasAlternatives() && !node.explicitChoice();
    }

    private boolean isItemArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        int[] point = treeMouse(mouseX, mouseY);
        int width = nodeWidth(node);
        int left = node.x() - width / 2;
        int itemX = node.recipe() == null ? node.x() - 8 : left + 24;
        int itemY = node.y() - 8;
        return containsTreeArea(point[0], point[1], itemX, itemY, 16, 16);
    }

    private void chooseRecipe(RecipeTreeData.Node node) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || node.stack().isEmpty()) {
            return;
        }

        Optional<mezz.jei.api.ingredients.ITypedIngredient<?>> source =
            RecipeLookup.representativeSource(node.stack());
        IFocus<?> focus = source.isPresent()
            ? focusFor(runtime, source.get())
            : outputFocus(runtime, node.stack());
        if (focus == null) {

            JERTLog.summary("recipe tree: no JEI focus for '{}' ({}), cannot open recipe page",
                node.ingredientKey(), node.stack().getItem());
            return;
        }
        RecipeTreeSession.beginResolution(node, this);
        runtime.getRecipesGui().show(List.of(focus));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IFocus<?> focusFor(IJeiRuntime runtime, mezz.jei.api.ingredients.ITypedIngredient<?> source) {
        try {

            mezz.jei.api.ingredients.IIngredientType<Object> type =
                (mezz.jei.api.ingredients.IIngredientType<Object>) source.getType();
            Object value = source.getIngredient();
            return runtime.getJeiHelpers().getFocusFactory()
                .<Object>createFocus(RecipeIngredientRole.OUTPUT, type, value);
        } catch (RuntimeException ignored) {

            return null;
        }
    }

    private static IFocus<?> outputFocus(IJeiRuntime runtime, ItemStack stack) {
        Optional<? extends IFocus<?>> fluidFocus = FluidRecipeCompat.createOutputFocus(runtime, stack);
        return fluidFocus.isPresent()
            ? fluidFocus.get()
            : RecipeLookup.createOutputFocus(runtime, stack).orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void showExactRecipe(RecipeTreeData.Node node) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || node.recipe() == null) {
            return;
        }
        if (node.recipe().ref().category() == null) {

            JERTLog.debug("recipe tree: '{}' comes from the player library, no JEI page to open",
                node.recipe().ref().key());
            return;
        }
        runtime.getRecipesGui().showRecipes(
            (mezz.jei.api.recipe.category.IRecipeCategory) node.recipe().ref().category(),
            List.of(node.recipe().ref().recipe()),
            List.<IFocus<?>>of()
        );
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        if (treePicker.isOpen()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (treePicker.isOpen()) {
            return true;
        }
        if (dragging && (button == 0 || button == 2)) {
            offsetX += (float) (dragX / zoom);
            offsetY += (float) (dragY / zoom);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pickerOpen && scrollY != 0) {
            return scrollPicker(scrollY);
        }
        if (treePicker.mouseScrolled(scrollY)) {
            return true;
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        int[] point = treeMouse(mouseX, mouseY);
        if (tree != null && containsTreeArea(point[0], point[1], layout.batchX(), layout.batchY(), layout.batchWidth(), 22) && scrollY != 0) {
            long adjustment;
            if (hasControlDown()) {
                adjustment = scrollY > 0 ? tree.batches() : -Math.max(1, tree.batches() / 2);
            } else {
                adjustment = scrollY > 0 ? 1 : -1;
                if (hasShiftDown()) {
                    adjustment *= 16;
                }
            }
            tree.setBatches(tree.batches() + adjustment);
            RecipeTreeSession.savePersistentState();
            rebuildLayout(false);
            RecipeTreeFavorites.refreshNow();
            return true;
        }
        if (scrollY != 0) {
            return zoomAt(mouseX, mouseY, scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (treePicker.charTyped(codePoint)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pickerOpen && keyCode == 256) {
            pickerOpen = false;
            return true;
        }
        if (treePicker.keyPressed(keyCode)) {
            return true;
        }
        if (keyCode == 256 || Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == 82) {
            rebuildLayout(true);
            return true;
        }
        if (hasControlDown() && keyCode == 67) {
            RecipeTreeSession.clear();
            rebuildLayout(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        RecipeTreeSession.cancelResolution();
        RecipeTreeSession.restoreCraftingTree();

        if (instance == this) {
            instance = null;
        }
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int[] treeMouse(double mouseX, double mouseY) {
        return new int[] {
            (int) ((mouseX - width / 2.0) / zoom - offsetX),
            (int) ((mouseY - height / 2.0) / zoom - offsetY)
        };
    }

    private static int lineColor(boolean hovered) {

        return hovered ? TreePalette.accent() : TreePalette.line();
    }

    private static boolean costShort(RecipeTreeData.Cost cost) {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree == null || !tree.craftingMode()) {
            return false;
        }

        return cost.required() > 0L;
    }

    private static String costText(RecipeTreeData.Cost cost, boolean leftover) {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        boolean fluid = IngredientDisplay.measured(cost.stack());
        if (!leftover && tree != null && tree.craftingMode()) {

            if (cost.required() <= 0L) {
                return "";
            }
            String missing = quantityText(cost.stack(), cost.required(), fluid);
            if (cost.supplied() <= 0L) {
                return missing;
            }
            String available = fluid
                ? FluidRecipeCompat.formatAmount(cost.supplied())
                : quantityText(cost.stack(), cost.supplied(), false);
            return available + "/" + missing;
        }
        return !fluid && cost.required() == 1
            ? ""
            : quantityText(cost.stack(), cost.required(), fluid);
    }

    private int nodeWidth(RecipeTreeData.Node node) {
        int amount = Math.min(TreeLayout.AMOUNT_ZONE, nodeAmountWidth(node));
        int width = TreeLayout.TAPE_INSET
            + (node.recipe() == null ? 0 : TreeLayout.CATEGORY_SIZE + TreeLayout.CONTENT_GAP)
            + TreeLayout.ITEM_SIZE + TreeLayout.CONTENT_GAP + amount + TreeLayout.TAPE_INSET;
        return Math.min(TreeLayout.RECIPE_NODE_WIDTH, width);
    }

    private int nodeAmountWidth(RecipeTreeData.Node node) {
        String text = formatNodeAmount(node.stack(), node.amount());
        if (text.isEmpty()) {
            return 0;
        }
        return Math.round(font.width(AmountText.latin(text)) * TreeText.scale());
    }

    private static String formatNodeAmount(ItemStack stack, long amount) {
        boolean fluid = IngredientDisplay.measured(stack);
        if (!fluid && amount == 1) {
            return "";
        }
        return quantityText(stack, amount, fluid);
    }

    private static String quantityText(ItemStack stack, long units, boolean fluid) {
        if (fluid) {
            return FluidRecipeCompat.formatAmount(FluidRecipeCompat.amountForUnits(stack, units));
        }
        return formatNumber(units);
    }

    private static String formatNumber(long amount) {
        if (amount >= 1_000_000_000L) {
            return trim(amount / 1_000_000_000.0) + "B";
        }
        if (amount >= 1_000_000L) {
            return trim(amount / 1_000_000.0) + "M";
        }
        if (amount >= 10_000L) {
            return trim(amount / 1_000.0) + "K";
        }
        return Long.toString(amount);
    }

    private static String trim(double value) {
        return value >= 100
            ? String.format(Locale.ROOT, "%.0f", value)
            : String.format(Locale.ROOT, "%.1f", value).replace(".0", "");
    }

    private void openPicker() {
        pickerRecipes = null;
        pickerOpen = true;
        pickerPage = 0;
    }

    private List<RecipeRecord> pickerRecipes() {
        if (pickerRecipes == null) {
            pickerRecipes = StoredRecipes.all();
        }
        return pickerRecipes;
    }

    private boolean zoomAt(double mouseX, double mouseY, double scrollY) {
        float oldZoom = zoom;
        float newZoom = Math.max(ViewportFit.MIN_ZOOM,
            Math.min(MAX_INTERACTIVE_ZOOM, zoom + (scrollY > 0 ? 0.1f : -0.1f)));
        if (newZoom != oldZoom) {
            float treeX = (float) ((mouseX - width / 2.0) / oldZoom - offsetX);
            float treeY = (float) ((mouseY - height / 2.0) / oldZoom - offsetY);
            zoom = newZoom;
            offsetX = (float) ((mouseX - width / 2.0) / newZoom - treeX);
            offsetY = (float) ((mouseY - height / 2.0) / newZoom - treeY);
        }
        return true;
    }

    private boolean scrollPicker(double scrollY) {
        int pages = pickerPageCount();
        if (pages > 1) {
            pickerPage = Math.floorMod(pickerPage + (scrollY < 0 ? 1 : -1), pages);
        }
        return true;
    }

    private int pickerPageCount() {
        int size = pickerRecipes().size();
        return Math.max(1, (size + PICKER_PAGE_SIZE - 1) / PICKER_PAGE_SIZE);
    }

    private int pickerX() {
        return (width - PICKER_WIDTH) / 2;
    }

    private int pickerY() {
        return (height - PICKER_HEIGHT) / 2;
    }

    private void renderPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        List<RecipeRecord> recipes = pickerRecipes();
        int panelX = pickerX();
        int panelY = pickerY();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.SELECTION_PANEL);
        graphics.fill(0, 0, width, height, TreePalette.scrim());
        graphics.fill(panelX - 1, panelY - 1, panelX + PICKER_WIDTH + 1, panelY + PICKER_HEIGHT + 1, TreePalette.rule2());
        graphics.fill(panelX, panelY, panelX + PICKER_WIDTH, panelY + PICKER_HEIGHT, TreePalette.paper2());
        drawCenteredNoShadow(
            graphics,
            Component.translatable("jeirecipetree.recipe_tree.picker.title", recipes.size()),
            panelX + PICKER_WIDTH / 2,
            panelY + 6,
            TreePalette.ink()
        );

        if (recipes.isEmpty()) {
            drawCenteredNoShadow(
                graphics,
                Component.translatable("jeirecipetree.recipe_tree.picker.empty"),
                panelX + PICKER_WIDTH / 2,
                panelY + PICKER_HEIGHT / 2,
                TreePalette.ink3()
            );
            graphics.pose().popPose();
            return;
        }

        int start = pickerPage * PICKER_PAGE_SIZE;
        int end = Math.min(recipes.size(), start + PICKER_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int slotX = panelX + 6 + local % PICKER_COLUMNS * PICKER_SLOT;
            int slotY = panelY + 22 + local / PICKER_COLUMNS * PICKER_SLOT;
            boolean hovered = contains(mouseX, mouseY, slotX, slotY, 18, 18);
            graphics.fill(slotX, slotY, slotX + 18, slotY + 18, hovered ? TreePalette.accent() : TreePalette.rule2());
            graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, TreePalette.well());
            ItemStack preview = StoredRecipes.previewOf(recipes.get(index));
            if (!preview.isEmpty()) {
                ItemStack renderStack = FluidRecipeCompat.copyWithDisplay(preview);
                renderStack.setCount(1);
                if (!IngredientDisplay.render(graphics, renderStack, slotX + 1, slotY + 1)) {
                    graphics.renderItem(renderStack, slotX + 1, slotY + 1);
                }
            }
        }

        drawCenteredNoShadow(
            graphics,
            Component.translatable("jeirecipetree.recipe_tree.picker.manage",
                JERTKeyMappings.OPEN_LIBRARY.getTranslatedKeyMessage().getString()),
            panelX + PICKER_WIDTH / 2,
            panelY + PICKER_HEIGHT - 32,
            TreePalette.ink3()
        );
        int footerY = panelY + PICKER_HEIGHT - 18;
        int pages = pickerPageCount();
        drawChoicePageButton(graphics, panelX + 7, footerY, "<", pickerPage > 0,
            contains(mouseX, mouseY, panelX + 7, footerY, 16, 14));
        drawChoicePageButton(graphics, panelX + PICKER_WIDTH - 23, footerY, ">", pickerPage + 1 < pages,
            contains(mouseX, mouseY, panelX + PICKER_WIDTH - 23, footerY, 16, 14));
        drawCenteredNoShadow(
            graphics,
            Component.literal((pickerPage + 1) + " / " + pages),
            panelX + PICKER_WIDTH / 2,
            footerY + 3,
            TreePalette.ink3()
        );
        graphics.pose().popPose();
    }

    private boolean handlePickerClick(double mouseX, double mouseY, int button) {
        int panelX = pickerX();
        int panelY = pickerY();
        if (!contains(mouseX, mouseY, panelX, panelY, PICKER_WIDTH, PICKER_HEIGHT)) {
            pickerOpen = false;
            return true;
        }
        int footerY = panelY + PICKER_HEIGHT - 18;
        if (contains(mouseX, mouseY, panelX + 7, footerY, 16, 14) && pickerPage > 0) {
            pickerPage--;
            return true;
        }
        if (contains(mouseX, mouseY, panelX + PICKER_WIDTH - 23, footerY, 16, 14) && pickerPage + 1 < pickerPageCount()) {
            pickerPage++;
            return true;
        }
        RecipeRecord hovered = hoveredPickerRecipe(mouseX, mouseY);
        if (hovered != null) {
            if (button == 0) {
                startTree(hovered);
            }
            return true;
        }
        return true;
    }

    private RecipeRecord hoveredPickerRecipe(double mouseX, double mouseY) {
        List<RecipeRecord> recipes = pickerRecipes();
        int start = pickerPage * PICKER_PAGE_SIZE;
        int end = Math.min(recipes.size(), start + PICKER_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int slotX = pickerX() + 6 + local % PICKER_COLUMNS * PICKER_SLOT;
            int slotY = pickerY() + 22 + local / PICKER_COLUMNS * PICKER_SLOT;
            if (contains(mouseX, mouseY, slotX, slotY, 18, 18)) {
                return recipes.get(index);
            }
        }
        return null;
    }

    private void startTree(RecipeRecord record) {
        if (!RecipeTreeSession.setGoalFromRecord(record)) {
            return;
        }
        pickerOpen = false;
        rebuildLayout(true);
        RecipeTreeFavorites.refreshNow();
    }

    private void drawAddButton(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean hovered = isAddButton(mouseX, mouseY);
        graphics.fill(ADD_BUTTON_X, ADD_BUTTON_Y, ADD_BUTTON_X + 16, ADD_BUTTON_Y + 16,
            hovered ? TreePalette.accent() : TreePalette.rule2());
        graphics.fill(ADD_BUTTON_X + 1, ADD_BUTTON_Y + 1, ADD_BUTTON_X + 15, ADD_BUTTON_Y + 15, TreePalette.paper2());
        drawCenteredNoShadow(graphics, "+", ADD_BUTTON_X + 8, ADD_BUTTON_Y + 4, TreePalette.ink());
        if (hovered) {
            graphics.renderTooltip(
                font,
                Component.translatable("jeirecipetree.recipe_tree.picker.button"),
                mouseX,
                mouseY
            );
        }
    }

    private static boolean isAddButton(double mouseX, double mouseY) {
        return contains(mouseX, mouseY, ADD_BUTTON_X, ADD_BUTTON_Y, 16, 16);
    }

    private static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static boolean containsTreeArea(int mouseX, int mouseY, int x, int y, int width, int height) {
        return contains(mouseX, mouseY, x, y, width, height);
    }

}
