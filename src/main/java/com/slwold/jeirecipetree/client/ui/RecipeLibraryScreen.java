package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeRecord;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class RecipeLibraryScreen extends AbstractContainerScreen<ShellMenu> {

    private static final int PANEL_WIDTH = 176;

    private static final int PANEL_HEIGHT = 252;

    private static final int PROMPT_Y = 204;

    private static final int NOTICE_Y = 206;

    private static final int FOOTER_Y = 228;

    private static final int COL1_X = 8;
    private static final int COL1_W = 52;
    private static final int COL2_X = 64;
    private static final int COL2_W = 58;
    private static final int COL3_X = 126;
    private static final int COL3_W = 42;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 7;

    private static final int LIST_TOP = 76;

    private static final int MAX_FILTER = 32;

    private static final int PROMPT_NONE = 0;
    private static final int PROMPT_CREATE = 1;
    private static final int PROMPT_MOVE = 2;
    private static final int PROMPT_RENAME = 3;
    private static final int PROMPT_NEW_RECIPE = 4;

    private final Screen parent;

    private final List<RecipeRecord> recipes = new ArrayList<>();

    private final List<RecipeRecord> view = new ArrayList<>();
    private int selected = -1;
    private int scroll;

    private String viewGroup = "";

    private String filterText = "";
    private boolean searchActive;

    private final List<int[]> tabRects = new ArrayList<>();

    private int prompt = PROMPT_NONE;
    private String promptText = "";

    private String promptGroup = "";

    private Component notice;

    public RecipeLibraryScreen(Screen parent) {
        super(new ShellMenu(), net.minecraft.client.Minecraft.getInstance().player.getInventory(),
            tr("gui.jeirecipetree.library.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        imageWidth = PANEL_WIDTH;
        imageHeight = PANEL_HEIGHT;
        super.init();
        refresh();
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) {

            JERTLog.warn("recipe library: not in game, ignored");
            return;
        }
        mc.setScreen(new RecipeLibraryScreen(parent));
    }

    private void refresh() {
        recipes.clear();
        recipes.addAll(StoredRecipes.all());
        rebuildView();
    }

    private void rebuildView() {
        view.clear();
        for (RecipeRecord recipe : recipes) {
            if (inViewGroup(recipe) && matchesFilter(recipe)) {
                view.add(recipe);
            }
        }
        selected = -1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, view.size() - VISIBLE_ROWS)));
    }

    private boolean inViewGroup(RecipeRecord recipe) {
        return viewGroup.isBlank()
            || viewGroup.equals(recipe.group().isBlank() ? RecipeArchive.GROUP : recipe.group());
    }

    private boolean matchesFilter(RecipeRecord recipe) {
        String needle = filterText.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        if (recipe.id().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
            return true;
        }
        for (RecipeRecord.Slot slot : recipe.outputs()) {
            for (ItemRef ref : slot.alternatives()) {
                if (ref.key().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {

        super.render(graphics, mouseX, mouseY, partialTick);
        int left = leftPos;
        int top = topPos;
        graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, 0xFF3A3A3A);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF0101010);
        graphics.drawString(font, tr("gui.jeirecipetree.library.title.count", view.size()),
            left + 8, top + 6, 0xFFFFFFFF, false);
        drawGroupTabs(graphics, left, top);
        drawButtonRows(graphics, left, top, mouseX, mouseY);
        drawListRows(graphics, left, top, mouseX, mouseY);
        drawState(graphics, left, top, mouseX, mouseY);

        boolean prompting = prompt != PROMPT_NONE;
        drawButton(graphics, left + 80, top + FOOTER_Y, 42, 16,
            tr(prompting ? "gui.jeirecipetree.library.confirm" : "gui.jeirecipetree.library.save"),
            true, mouseX, mouseY);
        drawButton(graphics, left + 126, top + FOOTER_Y, 42, 16,
            tr(prompting ? "gui.jeirecipetree.library.cancel" : "gui.jeirecipetree.library.close"),
            true, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    private void drawListRows(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scroll + row;
            if (index >= view.size()) {
                break;
            }
            int rowY = top + LIST_TOP + row * ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            int background = index == selected ? 0xFF2E4A2E : (hovered ? 0xFF262626 : 0xFF1A1A1A);
            graphics.fill(left + 4, rowY, left + PANEL_WIDTH - 4, rowY + ROW_HEIGHT - 1, background);
            RecipeRecord recipe = view.get(index);
            ItemStack icon = icon(recipe);
            if (!icon.isEmpty() && !IngredientDisplay.render(graphics, icon, left + 6, rowY + 1)) {
                graphics.renderItem(icon, left + 6, rowY + 1);
            }
            String summary = summarize(recipe);

            drawFitted(graphics, summary, left + 28, rowY + 5, PANEL_WIDTH - 32, 0xFFE0E0E0);
        }
        if (view.isEmpty()) {
            Component hint = recipes.isEmpty()
                ? tr("gui.jeirecipetree.library.empty")
                : tr("gui.jeirecipetree.library.empty.filtered");
            drawWrapped(graphics, hint, left + 10, top + LIST_TOP + 4, PANEL_WIDTH - 20, 0xFFA0A0A0, 4);
        }
    }

    private void drawFitted(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        if (font.width(text) > maxWidth) {
            text = font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
        }
        graphics.drawString(font, text, x, y, color, false);
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color, int maxLines) {
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(text, maxWidth);
        int count = Math.min(lines.size(), maxLines);
        for (int i = 0; i < count; i++) {
            graphics.drawString(font, lines.get(i), x, y + i * 10, color, false);
        }
    }

    private void drawState(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        if (prompt == PROMPT_NONE && selected >= 0 && selected - scroll >= 0 && selected - scroll < VISIBLE_ROWS) {
            drawRowOverlay(graphics, left, top, mouseX, mouseY);
        }
        if (prompt != PROMPT_NONE) {
            drawPrompt(graphics, left, top);
        } else if (notice != null) {
            drawFitted(graphics, notice.getString(), left + 8, top + NOTICE_Y, PANEL_WIDTH - 16, 0xFFFFD080);
        }
    }

    private void drawButtonRows(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int y1 = top + 36;
        drawButton(graphics, left + COL1_X, y1, COL1_W, 16, tr("gui.jeirecipetree.library.newRecipe"), true, mouseX, mouseY);

        int searchX = left + COL2_X;
        int searchW = COL2_W;
        int searchColor = searchActive ? 0xFF1E3320 : 0xFF202020;
        graphics.fill(searchX, y1, searchX + searchW, y1 + 16, searchColor);
        graphics.fill(searchX, y1, searchX + 1, y1 + 16, searchActive ? 0xFF55FF88 : 0xFF555555);

        Component searchText = filterText.isBlank() && !searchActive
            ? tr("gui.jeirecipetree.library.search")
            : Component.literal(filterText + (searchActive ? "_" : ""));
        drawFitted(graphics, searchText.getString(), searchX + 4, y1 + 4, searchW - 8, 0xFFC0C0C0);
        drawButton(graphics, left + COL3_X, y1, COL3_W, 16, tr("gui.jeirecipetree.library.export"), true, mouseX, mouseY);

        int y2 = top + 56;
        boolean hasGroup = !viewGroup.isBlank();
        drawButton(graphics, left + COL1_X, y2, COL1_W, 16, tr("gui.jeirecipetree.library.newGroup"), true, mouseX, mouseY);
        drawButton(graphics, left + COL2_X, y2, COL2_W, 16, tr("gui.jeirecipetree.library.renameGroup"), hasGroup, mouseX, mouseY);
        drawButton(graphics, left + COL3_X, y2, COL3_W, 16, tr("gui.jeirecipetree.library.deleteGroup"), hasGroup, mouseX, mouseY);
    }

    private void drawRowOverlay(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int rowY = top + LIST_TOP + (selected - scroll) * ROW_HEIGHT;
        drawButton(graphics, left + 88, rowY + 1, 24, 16, tr("gui.jeirecipetree.library.edit"), true, mouseX, mouseY);
        drawButton(graphics, left + 114, rowY + 1, 24, 16, tr("gui.jeirecipetree.library.delete"), true, mouseX, mouseY);
        drawButton(graphics, left + 140, rowY + 1, 26, 16, tr("gui.jeirecipetree.library.move"), true, mouseX, mouseY);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, int h, Component label, boolean enabled, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int background = !enabled ? 0xFF2A2A2A : hovered ? 0xFF3E5A3E : 0xFF2E7D32;
        graphics.fill(x, y, x + w, y + h, background);
        graphics.fill(x, y, x + w, y + 1, 0xFF555555);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF1A1A1A);
        int color = enabled ? 0xFFFFFFFF : 0xFF707070;
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, color, false);
    }

    private boolean hitButton(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void drawGroupTabs(GuiGraphics graphics, int left, int top) {
        tabRects.clear();
        int x = left + 8;
        int y = top + 20;
        List<String> groups = new ArrayList<>();
        groups.add(tr("gui.jeirecipetree.library.tab.all").getString());
        groups.addAll(RecipeGroups.names());
        for (int i = 0; i < groups.size() && x < left + PANEL_WIDTH - 12; i++) {
            String name = groups.get(i);
            boolean active = (viewGroup.isBlank() && i == 0) || name.equals(viewGroup);
            String label = "[" + name + "]";
            int color = active ? 0xFF55FF88 : 0xFF909090;
            graphics.drawString(font, label, x, y, color, false);
            tabRects.add(new int[]{x, x + font.width(label), i});
            x += font.width(label) + 6;
        }
    }

    private void drawPrompt(GuiGraphics graphics, int left, int top) {
        int boxY = top + PROMPT_Y;
        graphics.fill(left + 2, boxY, left + PANEL_WIDTH - 2, boxY + 20, 0xFF1E3320);
        graphics.fill(left + 2, boxY, left + PANEL_WIDTH - 2, boxY + 1, 0xFF55FF88);
        Component title = switch (prompt) {
            case PROMPT_CREATE -> tr("gui.jeirecipetree.library.prompt.create");
            case PROMPT_MOVE -> tr("gui.jeirecipetree.library.prompt.move");
            case PROMPT_NEW_RECIPE -> tr("gui.jeirecipetree.library.prompt.newRecipe");
            default -> tr("gui.jeirecipetree.library.prompt.rename", promptGroup);
        };
        graphics.drawString(font, title, left + 4, boxY + 6, 0xFFE0E0E0, false);

        int textX = left + 4 + font.width(title);
        if (textX < left + PANEL_WIDTH - 8) {
            drawFitted(graphics, promptText + "_", textX, boxY + 6, left + PANEL_WIDTH - 6 - textX, 0xFFFFFFFF);
        }
    }

    private static String summarize(RecipeRecord recipe) {
        String id = recipe.id();
        if (!recipe.group().isBlank() && !RecipeArchive.GROUP.equals(recipe.group())) {
            id = "[" + recipe.group() + "] " + id;
        }
        if (id.length() > 20) {
            id = id.substring(0, 19) + "…";
        }
        if (recipe.catalysts().isEmpty()) {
            return tr("gui.jeirecipetree.library.summary", id,
                recipe.outputs().size(), recipe.inputs().size()).getString();
        }
        return tr("gui.jeirecipetree.library.summary.catalyst", id,
            recipe.outputs().size(), recipe.inputs().size(), recipe.catalysts().size()).getString();
    }

    private static ItemStack icon(RecipeRecord recipe) {
        return outputStack(recipe);
    }

    private static ItemStack outputStack(RecipeRecord recipe) {
        for (RecipeRecord.Slot slot : recipe.outputs()) {
            for (ItemRef ref : slot.alternatives()) {
                if (ref.isTag()) {
                    continue;
                }
                ItemStack stack = StoredRecipes.displayStack(ref, 1);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static String label(RecipeRecord recipe) {
        ItemStack stack = outputStack(recipe);
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        String id = recipe.id();
        return id.length() > 24 ? id.substring(0, 23) + "…" : id;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = leftPos;
        int top = topPos;
        if (handleButtonAreaClick(mouseX, mouseY, left, top)) {
            return true;
        }

        if (prompt == PROMPT_NONE && selected >= 0 && selected - scroll >= 0 && selected - scroll < VISIBLE_ROWS) {
            int rowY = top + LIST_TOP + (selected - scroll) * ROW_HEIGHT;
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                if (hitButton(mouseX, mouseY, left + 88, rowY + 1, 24, 16)) {
                    openEditor();
                    return true;
                }
                if (hitButton(mouseX, mouseY, left + 114, rowY + 1, 24, 16)) {
                    deleteSelected();
                    return true;
                }
                if (hitButton(mouseX, mouseY, left + 140, rowY + 1, 26, 16)) {
                    startPrompt(PROMPT_MOVE, "");
                    return true;
                }
            }
        }

        int row = (int) ((mouseY - (top + LIST_TOP)) / ROW_HEIGHT);
        if (row >= 0 && row < VISIBLE_ROWS) {
            int index = scroll + row;
            if (index < view.size()) {
                selected = index;
                return true;
            }
        }

        if (searchActive) {
            searchActive = false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleButtonAreaClick(double mouseX, double mouseY, int left, int top) {
        return handleTabClick(mouseX, mouseY, left, top)
            || handleTopButtonsClick(mouseX, mouseY, left, top)
            || handleFooterClick(mouseX, mouseY, left, top);
    }

    private boolean handleTabClick(double mouseX, double mouseY, int left, int top) {
        if (mouseY < top + 20 || mouseY >= top + 32) {
            return false;
        }
        for (int[] rect : tabRects) {
            if (mouseX >= rect[0] && mouseX < rect[1]) {
                viewGroup = rect[2] == 0 ? "" : RecipeGroups.names().get(rect[2] - 1);
                rebuildView();
                return true;
            }
        }
        return false;
    }

    private boolean handleTopButtonsClick(double mouseX, double mouseY, int left, int top) {
        int y1 = top + 36;
        if (mouseY >= y1 && mouseY < y1 + 16) {
            if (hitButton(mouseX, mouseY, left + COL1_X, y1, COL1_W, 16)) {
                startPrompt(PROMPT_NEW_RECIPE, "");
                return true;
            }
            if (hitButton(mouseX, mouseY, left + COL2_X, y1, COL2_W, 16)) {
                searchActive = true;
                prompt = PROMPT_NONE;
                return true;
            }
            if (hitButton(mouseX, mouseY, left + COL3_X, y1, COL3_W, 16)) {
                RecipeArchive.exportAll();
                notice = tr("gui.jeirecipetree.library.exported");
                return true;
            }
        }
        int y2 = top + 56;
        if (mouseY < y2 || mouseY >= y2 + 16) {
            return false;
        }
        if (hitButton(mouseX, mouseY, left + COL1_X, y2, COL1_W, 16)) {
            startPrompt(PROMPT_CREATE, "");
            return true;
        }
        if (hitButton(mouseX, mouseY, left + COL2_X, y2, COL2_W, 16)) {
            openRename();
            return true;
        }
        if (hitButton(mouseX, mouseY, left + COL3_X, y2, COL3_W, 16)) {
            deleteGroupByView();
            return true;
        }
        return false;
    }

    private boolean handleFooterClick(double mouseX, double mouseY, int left, int top) {
        if (mouseY < top + FOOTER_Y || mouseY >= top + FOOTER_Y + 16) {
            return false;
        }
        if (prompt != PROMPT_NONE) {
            if (hitButton(mouseX, mouseY, left + 80, top + FOOTER_Y, 42, 16)) {
                commitPrompt();
                return true;
            }
            if (hitButton(mouseX, mouseY, left + 126, top + FOOTER_Y, 42, 16)) {
                prompt = PROMPT_NONE;
                promptText = "";
                return true;
            }
            return false;
        }
        if (hitButton(mouseX, mouseY, left + 80, top + FOOTER_Y, 42, 16)) {
            save();
            return true;
        }
        if (hitButton(mouseX, mouseY, left + 126, top + FOOTER_Y, 42, 16)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, view.size() - VISIBLE_ROWS);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (prompt != PROMPT_NONE) {
            return handlePromptKey(keyCode);
        }
        if (searchActive) {
            return handleSearchKey(keyCode);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (handleLibraryKey(keyCode)) {
            return true;
        }

        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (prompt != PROMPT_NONE) {

            if (!Character.isISOControl(codePoint) && promptText.length() < RecipeGroups.MAX_NAME) {
                promptText += codePoint;
            }
            return true;
        }
        if (searchActive) {
            if (!Character.isISOControl(codePoint) && filterText.length() < MAX_FILTER) {
                filterText += codePoint;
                rebuildView();
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private boolean handlePromptKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            prompt = PROMPT_NONE;
            promptText = "";
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !promptText.isEmpty()) {
            promptText = promptText.substring(0, promptText.length() - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            commitPrompt();
            return true;
        }
        return true;
    }

    private boolean handleSearchKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !filterText.isEmpty()) {
            filterText = filterText.substring(0, filterText.length() - 1);
            rebuildView();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            searchActive = false;
            return true;
        }
        return true;
    }

    private boolean handleLibraryKey(int keyCode) {
        return handleEntryKey(keyCode) || handleMenuKey(keyCode);
    }

    private boolean handleEntryKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_E && selected >= 0) {
            openEditor();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && !hasControlDown()) {
            deleteSelected();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && hasControlDown()) {
            deleteGroupByView();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G && selected >= 0) {
            startPrompt(PROMPT_MOVE, "");
            return true;
        }
        return false;
    }

    private boolean handleMenuKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            openRename();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A) {
            startPrompt(PROMPT_NEW_RECIPE, "");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_N) {
            startPrompt(PROMPT_CREATE, "");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_S) {
            searchActive = true;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_X) {
            RecipeArchive.exportAll();
            notice = tr("gui.jeirecipetree.library.exported");
            return true;
        }
        return false;
    }

    private void openEditor() {
        if (selected < 0 || selected >= view.size()) {
            return;
        }
        RecipeRecord recipe = view.get(selected);
        minecraft.setScreen(new RecipeEditScreen(this, null, new RecipeDraft(recipe)));
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= view.size()) {
            notice = tr("gui.jeirecipetree.library.select.recipe");
            return;
        }
        RecipeRecord recipe = view.get(selected);
        String group = recipe.group().isBlank() ? RecipeArchive.GROUP : recipe.group();
        RecipeGroups.removeFrom(group, recipe.id());
        StoredRecipes.reload();
        refresh();
        notice = tr("gui.jeirecipetree.library.deleted", label(recipe));
    }

    private void deleteGroupByView() {
        String group = activeGroup();
        if (group.isBlank()) {
            notice = tr("gui.jeirecipetree.library.select.group");
            return;
        }
        notice = RecipeGroups.delete(group)
            ? tr("gui.jeirecipetree.library.group.deleted", group)
            : tr("gui.jeirecipetree.library.group.delete.failed", group);
        viewGroup = "";
        refresh();
    }

    private String activeGroup() {
        if (!viewGroup.isBlank()) {
            return viewGroup;
        }
        if (selected >= 0 && selected < view.size()) {
            String group = view.get(selected).group();
            return group.isBlank() ? RecipeArchive.GROUP : group;
        }
        return "";
    }

    private void openRename() {
        String group = activeGroup();
        if (group.isBlank()) {
            notice = tr("gui.jeirecipetree.library.select.group");
            return;
        }
        if (RecipeArchive.GROUP.equals(group)) {
            notice = tr("gui.jeirecipetree.library.rename.default");
            return;
        }
        promptGroup = group;
        startPrompt(PROMPT_RENAME, group);
    }

    private void startPrompt(int kind, String initial) {
        notice = null;
        searchActive = false;
        prompt = kind;
        promptText = initial;
    }

    private void openNewRecipe(String label) {
        RecipeRecord record = RecipeArchive.newManualRecipe(label, RecipeArchive.GROUP);
        if (record == null) {
            notice = tr("gui.jeirecipetree.library.output.required");
            return;
        }
        minecraft.setScreen(new RecipeEditScreen(this, null, new RecipeDraft(record)));
    }

    private void commitPrompt() {
        int kind = prompt;
        String raw = promptText.trim();
        prompt = PROMPT_NONE;
        promptText = "";
        if (kind == PROMPT_NEW_RECIPE) {
            openNewRecipe(raw);
            return;
        }
        String name = RecipeGroups.sanitize(raw);
        if (name.isEmpty()) {
            notice = tr("gui.jeirecipetree.library.group.invalid");
            return;
        }
        if (kind == PROMPT_CREATE) {
            notice = RecipeGroups.create(name)
                ? tr("gui.jeirecipetree.library.group.created", name)
                : tr("gui.jeirecipetree.library.group.exists", name);
            refresh();
            return;
        }
        if (kind == PROMPT_RENAME) {
            notice = RecipeGroups.rename(promptGroup, name)
                ? tr("gui.jeirecipetree.library.group.renamed", name)
                : tr("gui.jeirecipetree.library.group.rename.failed");
            promptGroup = "";
            refresh();
            return;
        }

        if (selected < 0 || selected >= view.size()) {
            notice = tr("gui.jeirecipetree.library.select.recipe.move");
            return;
        }
        RecipeRecord recipe = view.get(selected);

        RecipeGroups.create(name);
        notice = RecipeGroups.move(recipe, name)
            ? tr("gui.jeirecipetree.library.group.moved", name)
            : tr("gui.jeirecipetree.library.group.move.failed", name);
        refresh();
    }

    private void save() {
        RecipeArchive.saveAll(recipes);
        notice = tr("gui.jeirecipetree.library.saved", recipes.size());
    }

    @Override
    public void onClose() {
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
