package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeFingerprint;
import com.slwold.jeirecipetree.core.RecipeRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class RecipeEditPanel {

    interface Callbacks {

        void onSave(RecipeRecord record, String initialGroup);

        void onCopy(RecipeRecord record);

        void onCancel();
    }

    static final int SLOT = 18;
    private static final int COLUMNS = 7;
    private static final int PAD = 7;
    private static final int LABEL_W = 30;
    private static final int SLOT_X = PAD + LABEL_W;
    private static final int TITLE_H = 14;
    private static final int SECTION_GAP = 8;
    private static final int HINT_H = 20;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;
    private static final int FOOTER_H = BUTTON_H * 2 + BUTTON_GAP;
    static final int WIDTH = SLOT_X + COLUMNS * SLOT + PAD;

    private static final int PANEL = 0xFFC6C6C6;
    private static final int EDGE_LIGHT = 0xFFFFFFFF;
    private static final int EDGE_DARK = 0xFF555555;
    private static final int WELL = 0xFF8B8B8B;
    private static final int WELL_DARK = 0xFF373737;
    private static final int INK = 0xFF404040;
    private static final int INK_SOFT = 0xFF5A5A5A;

    static final long FLUID_BUCKET = 1000L;

    private static final long FLUID_STEP = 100L;

    private static final long FLUID_FINE = 10L;

    private final RecipeDraft draft;
    private final Callbacks callbacks;

    private final CandidatePicker picker = new CandidatePicker();

    private final boolean existing;

    private RecipeRecord.Slot hand;

    private String targetGroup;

    private boolean typingGroup;
    private String groupInput = "";

    private boolean overlayOpen;
    private int overlayOrdinal = -1;
    private int overlayIndex = -1;

    private String overlayAmount = "";

    private final int[] page = new int[3];

    private String notice = "";

    private boolean dirty = true;
    private boolean duplicate;

    private int screenWidth;
    private int screenHeight;

    private int lastMouseX;
    private int lastMouseY;

    private Integer homeX;
    private Integer homeY;
    private Integer panelX;
    private Integer panelY;
    private boolean dragging;
    private int grabX;
    private int grabY;

    RecipeEditPanel(RecipeDraft draft, Callbacks callbacks) {
        this.draft = draft;
        this.callbacks = callbacks;
        this.targetGroup = draft.group().isBlank() ? RecipeArchive.GROUP : draft.group();
        this.existing = StoredRecipes.all().stream().anyMatch(record -> record.id().equals(draft.id()));
    }

    RecipeDraft draft() {
        return draft;
    }

    void resize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    void setHome(int x, int y) {
        this.homeX = x;
        this.homeY = y;
    }

    int left() {
        int fallback = homeX == null ? 8 : homeX;
        return clamp(panelX == null ? fallback : panelX, 0, Math.max(0, screenWidth - WIDTH));
    }

    int top() {
        int fallback = homeY == null ? (screenHeight - height()) / 2 : homeY;
        return clamp(panelY == null ? fallback : panelY, 0, Math.max(0, screenHeight - height()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    int height() {
        return PAD + TITLE_H + rowsTotal() * SLOT + 3 * SECTION_GAP + HINT_H + FOOTER_H + PAD;
    }

    private static int rowsOf(RecipeDraft.Section section) {
        return switch (section) {
            case OUTPUT -> 2;
            case CATALYST -> 1;
            case INPUT -> 2;
        };
    }

    private static int rowsTotal() {
        return 2 + 1 + 2;
    }

    private static int cellsOf(RecipeDraft.Section section) {
        return rowsOf(section) * COLUMNS;
    }

    private int sectionTop(int ordinal) {
        int y = top() + PAD + TITLE_H;
        for (int index = 0; index < 3; index++) {
            if (index == ordinal) {
                return y;
            }
            y += rowsOf(sectionOf(index)) * SLOT + SECTION_GAP;
        }
        return y;
    }

    private int slotX(int visibleIndex) {
        return left() + SLOT_X + (visibleIndex % COLUMNS) * SLOT;
    }

    private int slotY(int ordinal, int visibleIndex) {
        return sectionTop(ordinal) + (visibleIndex / COLUMNS) * SLOT;
    }

    private int footerY() {
        return top() + height() - PAD - FOOTER_H;
    }

    private int footerRow2Y() {
        return footerY() + BUTTON_H + BUTTON_GAP;
    }

    private int notesY() {
        return footerY() - HINT_H;
    }

    private static int ordinalOf(RecipeDraft.Section section) {
        return switch (section) {
            case OUTPUT -> 0;
            case CATALYST -> 1;
            case INPUT -> 2;
        };
    }

    private static RecipeDraft.Section sectionOf(int ordinal) {
        return switch (ordinal) {
            case 0 -> RecipeDraft.Section.OUTPUT;
            case 1 -> RecipeDraft.Section.CATALYST;
            default -> RecipeDraft.Section.INPUT;
        };
    }

    private int pages(RecipeDraft.Section section) {
        int size = draft.slots(section).size();
        return Math.max(1, (size + cellsOf(section) - 1) / cellsOf(section));
    }

    private int pageOf(RecipeDraft.Section section) {
        return Math.min(page[ordinalOf(section)], pages(section) - 1);
    }

    private void flipPage(int ordinal, int direction) {
        RecipeDraft.Section section = sectionOf(ordinal);
        int max = pages(section) - 1;
        if (max <= 0) {
            return;
        }
        page[ordinal] = Math.max(0, Math.min(max, pageOf(section) + direction));
        JERTLog.debug("recipe edit: {} page {}/{}", section, page[ordinal] + 1, max + 1);
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        int left = left();
        int top = top();
        int height = height();
        var font = Minecraft.getInstance().font;

        DragHighlight.sample(insideRect(mouseX, mouseY, left, top, WIDTH, height), mouseX, mouseY);
        drawPanel(graphics, left, top, height);

        graphics.drawString(font, tr("gui.jeirecipetree.edit.title"), left + PAD, top + PAD - 2, INK, false);
        drawGroupChip(graphics, left, top);

        drawSection(graphics, RecipeDraft.Section.OUTPUT, tr("gui.jeirecipetree.edit.section.output").getString(), 0, mouseX, mouseY);
        drawSection(graphics, RecipeDraft.Section.CATALYST, tr("gui.jeirecipetree.edit.section.catalyst").getString(), 1, mouseX, mouseY);
        drawSection(graphics, RecipeDraft.Section.INPUT, tr("gui.jeirecipetree.edit.section.input").getString(), 2, mouseX, mouseY);
        drawDragTargets(graphics, mouseX, mouseY);
        drawHandTargets(graphics);

        drawHint(graphics, left);
        drawFooter(graphics, left, mouseX, mouseY);
        picker.render(graphics, screenWidth, screenHeight, mouseX, mouseY);
        renderOverlay(graphics, mouseX, mouseY);
        renderHand(graphics, mouseX, mouseY);
    }

    private void drawPanel(GuiGraphics graphics, int left, int top, int height) {
        graphics.fill(left, top, left + WIDTH, top + height, PANEL);
        graphics.fill(left, top, left + WIDTH, top + 1, EDGE_LIGHT);
        graphics.fill(left, top, left + 1, top + height, EDGE_LIGHT);
        graphics.fill(left + WIDTH - 1, top, left + WIDTH, top + height, EDGE_DARK);
        graphics.fill(left, top + height - 1, left + WIDTH, top + height, EDGE_DARK);

        graphics.fill(left + PAD, top + TITLE_H + 1, left + WIDTH - PAD, top + TITLE_H + 2, EDGE_DARK);
    }

    private void drawGroupChip(GuiGraphics graphics, int left, int top) {
        var font = Minecraft.getInstance().font;
        String label = tr("gui.jeirecipetree.edit.group", targetGroup).getString();
        int plusX = left + WIDTH - PAD - 10;
        int labelX = plusX - 4 - font.width(label);
        graphics.fill(labelX - 2, top + PAD - 4, plusX - 2, top + PAD + 8, 0xFF888888);
        graphics.drawString(font, label, labelX, top + PAD - 2, 0xFF000000, false);
        graphics.fill(plusX, top + PAD - 4, plusX + 10, top + PAD + 8, 0xFF6E6E6E);
        graphics.fill(plusX, top + PAD - 4, plusX + 10, top + PAD - 3, EDGE_LIGHT);
        TreeText.centered(graphics, font, "+", plusX + 5, top + PAD - 2, 0xFFFFFFFF);
    }

    private void drawSection(
        GuiGraphics graphics,
        RecipeDraft.Section section,
        String title,
        int ordinal,
        int mouseX,
        int mouseY
    ) {
        List<RecipeRecord.Slot> slots = draft.slots(section);
        var font = Minecraft.getInstance().font;
        int rows = rowsOf(section);
        int page = pageOf(section);
        int labelY = sectionTop(ordinal) + rows * SLOT / 2 - 4;
        graphics.drawString(font, title, left() + PAD, labelY, INK, false);
        if (pages(section) > 1) {

            String counter = (page + 1) + "/" + pages(section);
            graphics.drawString(font, counter, left() + PAD, labelY + 10, INK_SOFT, false);
        }

        int cells = cellsOf(section);
        for (int visible = 0; visible < cells; visible++) {
            int index = page * cells + visible;
            int x = slotX(visible);
            int y = slotY(ordinal, visible);
            boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
            RecipeRecord.Slot slot = index < slots.size() ? slots.get(index) : null;
            drawSlot(graphics, x, y, slot, hovered);
        }
    }

    private void drawSlot(
        GuiGraphics graphics,
        int x,
        int y,
        RecipeRecord.Slot slot,
        boolean hovered
    ) {
        graphics.fill(x, y, x + SLOT, y + SLOT, WELL);
        graphics.fill(x, y, x + SLOT, y + 1, WELL_DARK);
        graphics.fill(x, y, x + 1, y + SLOT, WELL_DARK);
        graphics.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, EDGE_LIGHT);
        graphics.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, EDGE_LIGHT);
        if (hovered) {

            SlotChrome.frame(graphics, x, y, SLOT, SlotChrome.HOVER);
        }
        if (slot == null) {
            return;
        }
        ItemStack stack = preview(slot);

        if (!stack.isEmpty() && !IngredientDisplay.render(graphics, stack, x + 1, y + 1)) {
            graphics.renderItem(stack, x + 1, y + 1);
        }
        var font = Minecraft.getInstance().font;

        String text = IngredientDisplay.measured(stack)
            ? FluidRecipeCompat.formatAmount(slot.amount())
            : Long.toString(slot.amount());

        AmountText.drawAt(
            graphics, font, text, x + SLOT - 1, y + SLOT - 9, 0xFFFFFFFF,
            RenderLayers.SLOT_AMOUNT, AmountText.HALF);
    }

    private void renderHand(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hand == null) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.PANEL_HAND);
        drawSlot(graphics, mouseX - SLOT / 2, mouseY - SLOT / 2, hand, false);
        graphics.pose().popPose();
    }

    private void drawHint(GuiGraphics graphics, int left) {
        var font = Minecraft.getInstance().font;
        int y = notesY();
        if (typingGroup) {
            drawField(graphics, left + PAD, y, WIDTH - PAD * 2,
                tr("gui.jeirecipetree.edit.newGroupPrompt", groupInput + "_").getString());
            return;
        }
        if (!notice.isBlank()) {
            graphics.drawString(font, notice, left + PAD, y, 0xFFFFE08A, false);
            return;
        }
        if (hand != null) {
            graphics.drawString(font,
                tr("gui.jeirecipetree.edit.hand", describe(hand)), left + PAD, y, INK_SOFT, false);
            return;
        }

        graphics.drawString(font, tr("gui.jeirecipetree.edit.hint.grab"), left + PAD, y, INK_SOFT, false);
        graphics.drawString(font, tr("gui.jeirecipetree.edit.hint.drag"), left + PAD, y + 10, INK_SOFT, false);
    }

    private void drawDragTargets(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!DragHighlight.active()) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.SLOT_OVERLAY);
        for (int[] rect : slotRects()) {
            int x = rect[2];
            int y = rect[3];
            boolean hovered = insideRect(mouseX, mouseY, x, y, SLOT, SLOT);
            graphics.fill(x, y, x + SLOT, y + SLOT,
                hovered ? DragHighlight.HOVER : DragHighlight.TARGET);
        }
        graphics.pose().popPose();
    }

    private void drawHandTargets(GuiGraphics graphics) {
        if (hand == null) {
            return;
        }
        for (int[] rect : slotRects()) {
            SlotChrome.frame(graphics, rect[2], rect[3], SLOT, SlotChrome.ACCEPT);
        }
    }

    private void drawFooter(GuiGraphics graphics, int left, int mouseX, int mouseY) {
        int y1 = footerY();
        drawButton(graphics, left + PAD, y1, 36, tr("gui.jeirecipetree.edit.clear").getString(), true, mouseX, mouseY);
        drawButton(graphics, left + PAD + 40, y1, 44, tr("gui.jeirecipetree.edit.tag").getString(), true, mouseX, mouseY);
        drawButton(graphics, left + PAD + 88, y1, 36, tr("gui.jeirecipetree.edit.copy").getString(), copyEnabled(), mouseX, mouseY);

        int y2 = footerRow2Y();
        drawButton(graphics, left + WIDTH - PAD - 100, y2, 48, tr("gui.jeirecipetree.edit.save").getString(), saveEnabled(), mouseX, mouseY);
        drawButton(graphics, left + WIDTH - PAD - 48, y2, 48, tr("gui.jeirecipetree.edit.cancel").getString(), true, mouseX, mouseY);
    }

    private void drawButton(
        GuiGraphics graphics,
        int x,
        int y,
        int w,
        String label,
        boolean enabled,
        int mouseX,
        int mouseY
    ) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BUTTON_H;
        int background = !enabled ? 0xFFAAAAAA : (hovered ? 0xFFA9A9A9 : PANEL);
        graphics.fill(x, y, x + w, y + BUTTON_H, background);
        graphics.fill(x, y, x + w, y + 1, EDGE_LIGHT);
        graphics.fill(x, y, x + 1, y + BUTTON_H, EDGE_LIGHT);
        graphics.fill(x + w - 1, y, x + w, y + BUTTON_H, EDGE_DARK);
        graphics.fill(x, y + BUTTON_H - 1, x + w, y + BUTTON_H, EDGE_DARK);
        int color = enabled ? INK : 0xFF7A7A7A;
        TreeText.centered(graphics, Minecraft.getInstance().font, label, x + w / 2, y + 6, color);
    }

    private void drawField(GuiGraphics graphics, int x, int y, int w, String text) {
        graphics.fill(x, y, x + w, y + 16, 0xFF202020);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 15, 0xFFFFFFFF);
        graphics.drawString(Minecraft.getInstance().font, text, x + 4, y + 4, 0xFF000000, false);
    }

    private ItemStack preview(RecipeRecord.Slot slot) {
        for (ItemRef ref : slot.alternatives()) {
            if (ref.isTag()) {
                continue;
            }

            int amount = ref.isFluid() || ref.isChemical()
                ? (int) Math.min(Integer.MAX_VALUE, Math.max(1L, slot.amount()))
                : 1;
            ItemStack stack = StoredRecipes.displayStack(ref, amount);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static String describe(RecipeRecord.Slot slot) {
        ItemRef primary = slot.primary();
        String name = primary.isPlaceholder() ? primary.placeholderLabel() : primary.key();
        return name + " ×" + slot.amount();
    }

    private void grab(RecipeRecord.Slot slot) {
        if (slot == null) {
            return;
        }
        hand = slot;
        JERTLog.debug("recipe edit: hand <- {} x{}", slot.primary().key(), slot.amount());
    }

    private void clearHand() {
        hand = null;
    }

    private void putHand(int ordinal, int index) {
        if (hand == null) {
            return;
        }
        RecipeDraft.Section section = sectionOf(ordinal);
        List<RecipeRecord.Slot> slots = draft.slots(section);
        if (index < slots.size()) {
            draft.replaceSlot(section, index, hand);
            JERTLog.debug("recipe edit: hand -> {}[{}] (replaced)", section, index);
        } else if (!draft.addSlot(section, hand)) {
            notice = tr("gui.jeirecipetree.edit.section.full", RecipeDraft.MAX_SLOTS).getString();
            return;
        } else {
            JERTLog.debug("recipe edit: hand -> {}[{}] (added)", section, index);

            page[ordinal] = index / cellsOf(section);
        }
        clearHand();
        dirty = true;
    }

    private void grabFromPicker() {
        picker.open(List.of(), ref -> grab(new RecipeRecord.Slot(
            List.of(ref), ref.isFluid() ? FLUID_BUCKET : 1L)));
    }

    private void openOverlay(int ordinal, int index) {
        overlayOpen = true;
        overlayOrdinal = ordinal;
        overlayIndex = index;
        overlayAmount = "";
    }

    private void closeOverlay() {
        overlayOpen = false;
        overlayOrdinal = -1;
        overlayIndex = -1;
        overlayAmount = "";
    }

    private void handleOverlayClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            closeOverlay();
            return;
        }
        int x = overlayX();
        int y = overlayY();
        if (clickOverlayButton(mouseX, mouseY, x, y)) {
            return;
        }
        if (mouseX < x || mouseX >= x + 112 || mouseY < y || mouseY >= y + 60) {
            closeOverlay();
        }
    }

    private boolean clickOverlayButton(double mouseX, double mouseY, int x, int y) {
        int rowY = y + 32;
        if (mouseY < rowY || mouseY >= rowY + 18) {
            return false;
        }
        int index = overlayButtonAt(mouseX, x);
        switch (index) {
            case 0 -> takeHand();
            case 1 -> openPicker(sectionOf(overlayOrdinal), overlayIndex);
            case 2 -> confirmOverlayAmount();
            case 3 -> deleteOverlaySlot();
            case 4 -> closeOverlay();
            default -> {
                return false;
            }
        }
        return true;
    }

    private static int overlayButtonAt(double mouseX, int x) {
        for (int index = 0; index < 5; index++) {
            double left = x + 6 + index * 21;
            if (mouseX >= left && mouseX < left + 20) {
                return index;
            }
        }
        return -1;
    }

    private void deleteOverlaySlot() {
        if (draft.remove(sectionOf(overlayOrdinal), overlayIndex)) {
            dirty = true;
        } else {
            notice = tr("gui.jeirecipetree.edit.output.keepOne").getString();
        }
        closeOverlay();
    }

    private void takeHand() {
        RecipeRecord.Slot taken = draft.takeSlot(sectionOf(overlayOrdinal), overlayIndex);
        if (taken == null) {
            notice = tr("gui.jeirecipetree.edit.output.keepOne.take").getString();
            return;
        }
        grab(taken);
        dirty = true;
        closeOverlay();
    }

    private void confirmOverlayAmount() {
        if (overlayAmountValid() && !overlayAmount.isEmpty()) {
            try {
                draft.setAmount(sectionOf(overlayOrdinal), overlayIndex, Long.parseLong(overlayAmount));
                dirty = true;
            } catch (NumberFormatException ignored) {

            }
        }
        closeOverlay();
    }

    private int overlayX() {
        int column = overlayIndex % COLUMNS;
        return Math.max(2, Math.min(slotX(column) - 6, screenWidth - 114));
    }

    private int overlayY() {
        int visible = overlayIndex % cellsOf(sectionOf(overlayOrdinal));
        int slotTop = slotY(overlayOrdinal, visible);
        int y = slotTop - 66;
        return y < 2 ? slotTop + SLOT + 6 : y;
    }

    private void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!overlayOpen) {
            return;
        }
        RecipeDraft.Section section = sectionOf(overlayOrdinal);
        List<RecipeRecord.Slot> slots = draft.slots(section);
        if (overlayIndex < 0 || overlayIndex >= slots.size()) {
            closeOverlay();
            return;
        }
        var font = Minecraft.getInstance().font;
        int x = overlayX();
        int y = overlayY();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.PANEL_MENU);
        graphics.fill(x - 1, y - 1, x + 113, y + 61, EDGE_DARK);
        graphics.fill(x, y, x + 112, y + 60, PANEL);
        graphics.fill(x, y, x + 112, y + 1, EDGE_LIGHT);
        graphics.fill(x, y, x + 1, y + 60, EDGE_LIGHT);

        drawSlot(graphics, x + 6, y + 6, slots.get(overlayIndex), false);
        int fieldX = x + 34;
        boolean valid = overlayAmountValid();
        graphics.fill(fieldX, y + 8, fieldX + 56, y + 24, 0xFF202020);
        graphics.fill(fieldX + 1, y + 9, fieldX + 55, y + 23, 0xFFFFFFFF);
        String shown = "x " + (overlayAmount.isEmpty()
            ? IngredientDisplay.measured(preview(slots.get(overlayIndex)))
                ? FluidRecipeCompat.formatAmount(slots.get(overlayIndex).amount())
                : Long.toString(slots.get(overlayIndex).amount())
            : overlayAmount);
        graphics.drawString(font, shown, fieldX + 4, y + 13, valid ? 0xFF000000 : 0xFFCC0000, false);

        int rowY = y + 32;
        drawOverlayButton(graphics, x + 6, rowY, tr("gui.jeirecipetree.edit.amount.take").getString(), true);
        drawOverlayButton(graphics, x + 27, rowY, tr("gui.jeirecipetree.edit.amount.guess").getString(), true);
        drawOverlayButton(graphics, x + 48, rowY, tr("gui.jeirecipetree.edit.amount.confirm").getString(), valid);
        drawOverlayButton(graphics, x + 69, rowY, tr("gui.jeirecipetree.edit.amount.delete").getString(), true);
        drawOverlayButton(graphics, x + 90, rowY, tr("gui.jeirecipetree.edit.amount.discard").getString(), true);
        graphics.pose().popPose();
    }

    private void drawOverlayButton(GuiGraphics graphics, int x, int y, String label, boolean enabled) {
        var font = Minecraft.getInstance().font;
        boolean hovered = mouseXWithin(x, y, 20, 18);
        graphics.fill(x, y, x + 20, y + 18, enabled ? (hovered ? 0xFFA9A9A9 : 0xFF999999) : 0xFFBBBBBB);
        graphics.fill(x, y, x + 20, y + 1, EDGE_LIGHT);
        graphics.fill(x, y + 17, x + 20, y + 18, EDGE_DARK);
        TreeText.centered(graphics, font, label, x + 10, y + 5, enabled ? INK : 0xFF888888);
    }

    private boolean mouseXWithin(int x, int y, int w, int h) {
        return lastMouseX >= x && lastMouseX < x + w && lastMouseY >= y && lastMouseY < y + h;
    }

    private boolean overlayAmountValid() {
        if (overlayAmount.isEmpty()) {
            return true;
        }
        try {
            return Long.parseLong(overlayAmount) > 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private boolean saveEnabled() {
        return !duplicate();
    }

    private boolean copyEnabled() {
        return existing && !duplicate();
    }

    private boolean duplicate() {
        if (!dirty) {
            return duplicate;
        }
        dirty = false;
        duplicate = computeDuplicate();
        return duplicate;
    }

    private boolean computeDuplicate() {
        String mine;
        try {
            mine = signature(draft.toRecord(targetGroup));
        } catch (IllegalArgumentException invalid) {
            return true;
        }
        for (RecipeRecord other : StoredRecipes.all()) {
            if (other.id().equals(draft.id())) {
                continue;
            }
            if (signature(other).equals(mine)) {
                return true;
            }
        }
        return false;
    }

    private static String signature(RecipeRecord record) {
        return RecipeFingerprint.contentId(record.group(), record.outputs(), record.inputs());
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        dirty = true;
        notice = "";
        if (picker.mouseClicked(mouseX, mouseY, button, screenWidth, screenHeight)) {
            return true;
        }
        if (overlayOpen) {
            handleOverlayClick(mouseX, mouseY, button);
            return true;
        }

        if (button != 0) {
            int[] hit = button == 1 && hand == null ? slotAt(mouseX, mouseY) : null;
            if (hit != null && hit[1] < draft.slots(sectionOf(hit[0])).size()) {
                openOverlay(hit[0], hit[1]);
                return true;
            }
            return false;
        }

        if (clickChrome(mouseX, mouseY)) {
            return true;
        }
        if (startDrag(mouseX, mouseY)) {
            return true;
        }
        int[] hit = slotAt(mouseX, mouseY);
        if (hit != null) {
            clickSlot(hit[0], hit[1]);
            return true;
        }
        if (hand != null) {

            clearHand();
            return true;
        }
        return false;
    }

    private void clickSlot(int ordinal, int index) {
        RecipeDraft.Section section = sectionOf(ordinal);
        boolean filled = index < draft.slots(section).size();
        if (hand != null) {
            putHand(ordinal, index);
            return;
        }
        if (filled) {
            openOverlay(ordinal, index);
        }
    }

    private boolean startDrag(double mouseX, double mouseY) {
        if (!insideRect(mouseX, mouseY, left(), top(), WIDTH, TITLE_H)) {
            return false;
        }
        dragging = true;
        grabX = (int) mouseX - left();
        grabY = (int) mouseY - top();
        return true;
    }

    boolean mouseDragged(double mouseX, double mouseY) {
        if (!dragging) {
            return false;
        }
        panelX = (int) Math.round(mouseX) - grabX;
        panelY = (int) Math.round(mouseY) - grabY;
        return true;
    }

    boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (picker.mouseScrolled(scrollY)) {
            return true;
        }
        dirty = true;
        int[] hit = slotAt(mouseX, mouseY);
        if (hit != null) {
            RecipeDraft.Section section = sectionOf(hit[0]);
            if (hit[1] < draft.slots(section).size()) {
                boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
                boolean ctrl = net.minecraft.client.gui.screens.Screen.hasControlDown();
                long step;
                if (!fluidSlot(section, hit[1])) {
                    step = shift ? 16L : 1L;
                } else if (shift) {
                    step = FLUID_BUCKET;
                } else if (ctrl) {
                    step = FLUID_STEP;
                } else {
                    step = FLUID_FINE;
                }
                draft.adjust(section, hit[1], scrollY > 0 ? step : -step);
                return true;
            }
            return false;
        }

        int ordinal = labelColumnAt(mouseX, mouseY);
        if (ordinal >= 0) {
            flipPage(ordinal, scrollY > 0 ? -1 : 1);
            return true;
        }
        return false;
    }

    boolean keyPressed(int keyCode) {
        if (picker.keyPressed(keyCode)) {
            return true;
        }
        if (overlayOpen) {
            return overlayKey(keyCode);
        }
        if (typingGroup) {
            return groupKey(keyCode);
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE && hand != null) {
            clearHand();
            return true;
        }

        return false;
    }

    private boolean overlayKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeOverlay();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !overlayAmount.isEmpty()) {
            overlayAmount = overlayAmount.substring(0, overlayAmount.length() - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirmOverlayAmount();
            return true;
        }

        return true;
    }

    private boolean groupKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            typingGroup = false;
            groupInput = "";
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !groupInput.isEmpty()) {
            groupInput = groupInput.substring(0, groupInput.length() - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            commitGroup();
            return true;
        }
        return true;
    }

    boolean charTyped(char codePoint) {
        if (picker.charTyped(codePoint)) {
            return true;
        }
        if (overlayOpen) {
            if (!Character.isISOControl(codePoint) && overlayAmount.length() < 7) {
                overlayAmount += codePoint;
            }
            return true;
        }
        if (typingGroup) {
            if (!Character.isISOControl(codePoint) && groupInput.length() < RecipeGroups.MAX_NAME) {
                groupInput += codePoint;
            }
            return true;
        }
        return false;
    }

    private boolean clickChrome(double mouseX, double mouseY) {
        int left = left();
        int top = top();
        var font = Minecraft.getInstance().font;
        String label = tr("gui.jeirecipetree.edit.group", targetGroup).getString();
        int plusX = left + WIDTH - PAD - 10;
        int labelX = plusX - 4 - font.width(label);
        if (insideRect(mouseX, mouseY, labelX - 2, top + PAD - 4, plusX - 2 - (labelX - 2), 12)) {
            List<String> groups = RecipeGroups.names();
            targetGroup = groups.get((groups.indexOf(targetGroup) + 1) % groups.size());
            dirty = true;
            return true;
        }
        if (insideRect(mouseX, mouseY, plusX, top + PAD - 4, 10, 12)) {
            typingGroup = true;
            groupInput = "";
            return true;
        }
        int y1 = footerY();
        if (insideRect(mouseX, mouseY, left + PAD, y1, 36, BUTTON_H)) {
            draft.clear();
            dirty = true;
            return true;
        }
        if (insideRect(mouseX, mouseY, left + PAD + 40, y1, 44, BUTTON_H)) {
            grabFromPicker();
            return true;
        }
        if (insideRect(mouseX, mouseY, left + PAD + 88, y1, 36, BUTTON_H)) {
            if (copyEnabled()) {
                callbacks.onCopy(draft.toRecord(targetGroup));
            } else {
                notice = existing
                    ? tr("gui.jeirecipetree.edit.copy.duplicate").getString()
                    : tr("gui.jeirecipetree.edit.copy.saveFirst").getString();
            }
            return true;
        }
        int y2 = footerRow2Y();
        if (insideRect(mouseX, mouseY, left + WIDTH - PAD - 100, y2, 48, BUTTON_H)) {
            if (saveEnabled()) {
                callbacks.onSave(draft.toRecord(targetGroup), draft.group());
            } else {
                notice = tr("gui.jeirecipetree.edit.save.duplicate").getString();
            }
            return true;
        }
        if (insideRect(mouseX, mouseY, left + WIDTH - PAD - 48, y2, 48, BUTTON_H)) {
            callbacks.onCancel();
            return true;
        }
        int ordinal = labelColumnAt(mouseX, mouseY);
        if (ordinal >= 0 && pages(sectionOf(ordinal)) > 1) {
            flipPage(ordinal, 1);
            return true;
        }
        return false;
    }

    private void commitGroup() {
        String name = RecipeGroups.sanitize(groupInput);
        typingGroup = false;
        groupInput = "";
        if (name.isEmpty()) {
            notice = tr("gui.jeirecipetree.edit.group.invalid").getString();
            return;
        }
        boolean created = RecipeGroups.create(name);
        targetGroup = name;
        dirty = true;
        notice = (created
            ? tr("gui.jeirecipetree.edit.group.created", name)
            : tr("gui.jeirecipetree.edit.group.switched", name)).getString();
    }

    private int labelColumnAt(double mouseX, double mouseY) {
        if (mouseX < left() + PAD || mouseX >= left() + SLOT_X - 2) {
            return -1;
        }
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            int top = sectionTop(ordinal);
            if (mouseY >= top && mouseY < top + rowsOf(sectionOf(ordinal)) * SLOT) {
                return ordinal;
            }
        }
        return -1;
    }

    private static boolean insideRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void openPicker(RecipeDraft.Section section, int index) {
        List<RecipeRecord.Slot> slots = draft.slots(section);
        if (index < 0 || index >= slots.size()) {
            return;
        }
        picker.open(slots.get(index).alternatives(), ref -> {
            draft.replaceAlternative(section, index, ref);
            dirty = true;

            closeOverlay();
        });
    }

    List<int[]> slotRects() {
        List<int[]> rects = new ArrayList<>();
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            RecipeDraft.Section section = sectionOf(ordinal);
            int cells = cellsOf(section);
            int page = pageOf(section);
            for (int visible = 0; visible < cells; visible++) {
                int index = page * cells + visible;
                rects.add(new int[]{ordinal, index, slotX(visible), slotY(ordinal, visible)});
            }
        }
        return rects;
    }

    void acceptDrop(int sectionOrdinal, int index, Object ingredient) {
        dirty = true;
        ItemRef ref = dropRef(ingredient);
        if (ref == null) {
            JERTLog.debug("recipe edit: dropped ingredient '{}' has no usable identity, ignored", ingredient);
            return;
        }
        RecipeDraft.Section section = sectionOf(sectionOrdinal);
        if (index < draft.slots(section).size()) {
            draft.replaceAlternative(section, index, ref);
            return;
        }

        long amount = ref.isFluid() ? FLUID_BUCKET : 1L;
        if (!draft.addSlot(section, ref, amount)) {
            notice = tr("gui.jeirecipetree.edit.section.full", RecipeDraft.MAX_SLOTS).getString();
            return;
        }
        page[sectionOrdinal] = index / cellsOf(section);
    }

    private static ItemRef dropRef(Object ingredient) {
        if (ingredient instanceof ItemStack stack) {
            return stack.isEmpty() ? null : ItemKeys.of(stack);
        }
        if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) {
            String key = FluidRecipeCompat.fluidKey(fluid);
            return key.isEmpty() ? null : ItemRef.of(key);
        }
        return null;
    }

    private boolean fluidSlot(RecipeDraft.Section section, int index) {
        List<RecipeRecord.Slot> slots = draft.slots(section);
        if (index < 0 || index >= slots.size()) {
            return false;
        }
        return slots.get(index).alternatives().stream().anyMatch(ItemRef::isFluid);
    }

    private int[] slotAt(double mouseX, double mouseY) {
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            RecipeDraft.Section section = sectionOf(ordinal);
            int cells = cellsOf(section);
            int page = pageOf(section);
            for (int visible = 0; visible < cells; visible++) {
                if (insideRect(mouseX, mouseY, slotX(visible), slotY(ordinal, visible), SLOT, SLOT)) {
                    return new int[]{ordinal, page * cells + visible};
                }
            }
        }
        return null;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
