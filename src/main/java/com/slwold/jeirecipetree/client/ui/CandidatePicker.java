package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import com.slwold.jeirecipetree.client.jei.JeiInputAreaCompat;
import com.slwold.jeirecipetree.core.ItemRef;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CandidatePicker {

    public interface Sink {
        void pick(ItemRef ref);
    }

    private static final int COLUMNS = 9;
    private static final int ROWS = 5;
    private static final int SLOT = 18;
    private static final int PAD = 7;
    private static final int PAGE_SIZE = COLUMNS * ROWS;
    static final int WIDTH = COLUMNS * SLOT + PAD * 2;
    private static final int HEIGHT = PAD + 12 + 14 + 18 + ROWS * SLOT + 16 + PAD;

    private static final int SECTION_ITEMS = 0;
    private static final int SECTION_TAGS = 1;
    private static final int SECTION_FLUIDS = 2;
    private static final int SECTION_PLACEHOLDER = 3;

    private static final String[] SECTION_KEYS = {
        "gui.jeirecipetree.picker.tab.items",
        "gui.jeirecipetree.picker.tab.tags",
        "gui.jeirecipetree.picker.tab.fluids",
        "gui.jeirecipetree.picker.tab.placeholder"
    };

    private static final int PANEL = 0xFFC6C6C6;
    private static final int EDGE_LIGHT = 0xFFFFFFFF;
    private static final int EDGE_DARK = 0xFF555555;
    private static final int WELL = 0xFF8B8B8B;
    private static final int WELL_DARK = 0xFF373737;
    private static final int INK = 0xFF404040;
    private static final int INK_SOFT = 0xFF5A5A5A;

    private static List<ItemStack> catalog;

    private static List<ItemRef> tagCatalog;

    private static final java.util.LinkedHashSet<String> recentPlaceholders = new java.util.LinkedHashSet<>();

    private boolean open;
    private int section = SECTION_ITEMS;
    private String query = "";
    private int page;
    private List<ItemRef> existing = List.of();
    private Sink sink;

    private List<ItemRef> cached;
    private String cachedKey = "\u0000";

    public boolean isOpen() {
        return open;
    }

    public void open(List<ItemRef> candidates, Sink sink) {
        this.open = true;
        this.section = SECTION_ITEMS;
        this.query = "";
        this.page = 0;
        this.existing = expandTags(candidates);
        this.sink = sink;
        refresh();
    }

    private static List<ItemRef> expandTags(List<ItemRef> candidates) {
        List<ItemRef> expanded = new ArrayList<>();
        for (ItemRef ref : candidates) {
            if (ref.isTag()) {
                List<ItemRef> members = SlotTags.members(ref);
                if (members.isEmpty()) {
                    expanded.add(ref);
                } else {
                    expanded.addAll(members);
                }
            } else {
                expanded.add(ref);
            }
        }
        if (candidates.size() == 1 && !candidates.get(0).isTag()) {
            for (ItemRef sibling : SlotTags.siblingsOf(candidates.get(0))) {
                if (!expanded.contains(sibling)) {
                    expanded.add(sibling);
                }
            }
        }
        return expanded.stream().distinct().toList();
    }

    public void close() {
        open = false;
        sink = null;
        refresh();
    }

    private static List<ItemStack> catalog() {
        if (catalog == null) {
            List<ItemStack> built = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                if (!stack.isEmpty()) {
                    built.add(stack);
                }
            }
            catalog = List.copyOf(built);
            JERTLog.debug("recipe edit: ingredient catalog built with {} items", catalog.size());
        }
        return catalog;
    }

    private java.util.Set<String> existingKeys = java.util.Set.of();

    private void refresh() {
        cached = null;
        cachedKey = "\u0000";
        existingKeys = existing.stream().map(ItemRef::key).collect(java.util.stream.Collectors.toSet());
    }

    private List<ItemRef> visibleRefs() {
        String key = section + "|" + query + "|" + existing.size();
        if (cached != null && key.equals(cachedKey)) {
            return cached;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<ItemRef> result = switch (section) {
            case SECTION_TAGS -> tagEntries(needle);
            case SECTION_FLUIDS -> fluidEntries(needle);
            case SECTION_PLACEHOLDER -> placeholderEntries(needle);
            default -> itemEntries(needle);
        };
        cached = List.copyOf(result);
        cachedKey = key;
        return cached;
    }

    private List<ItemRef> itemEntries(String needle) {
        List<ItemRef> result = new ArrayList<>();
        if (needle.isEmpty() && !existing.isEmpty()) {
            for (ItemRef ref : existing) {
                if (!iconFor(ref).isEmpty()) {
                    result.add(ref);
                }
            }
            return result;
        }
        Map<String, ItemRef> unique = new LinkedHashMap<>();
        for (ItemStack stack : catalog()) {
            if (!needle.isEmpty()) {
                String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
                if (!name.contains(needle)) {
                    continue;
                }
            }
            unique.putIfAbsent(ItemKeys.of(stack).key(), ItemKeys.of(stack));
        }
        result.addAll(unique.values());
        return result;
    }

    private List<ItemRef> tagEntries(String needle) {
        List<ItemRef> result = new ArrayList<>();

        for (ItemRef ref : existing) {
            if (ref.isTag() && (needle.isEmpty() || ref.key().toLowerCase(Locale.ROOT).contains(needle))) {
                result.add(ref);
            }
        }
        for (ItemRef tag : tagCatalog()) {
            if (existingKeys.contains(tag.key())) {
                continue;
            }
            if (needle.isEmpty() || tag.key().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(tag);
            }
        }
        return result;
    }

    private List<ItemRef> fluidEntries(String needle) {
        List<ItemRef> result = new ArrayList<>();
        for (ItemRef ref : existing) {
            if (ref.isFluid() && (needle.isEmpty() || ref.key().toLowerCase(Locale.ROOT).contains(needle))) {
                result.add(ref);
            }
        }
        for (ItemRef ref : fluidCatalog()) {
            if (existingKeys.contains(ref.key())) {
                continue;
            }
            if (needle.isEmpty() || ref.key().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(ref);
            }
        }
        return result;
    }

    private List<ItemRef> placeholderEntries(String needle) {
        List<ItemRef> result = new ArrayList<>();
        for (String key : recentPlaceholders) {
            String label = key.substring("placeholder:".length());
            if (needle.isEmpty() || label.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(ItemRef.of(key));
            }
        }
        return result;
    }

    private static List<ItemRef> tagCatalog() {
        if (tagCatalog == null) {
            List<ItemRef> built = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagNames().forEach(tag -> {
                var holders = BuiltInRegistries.ITEM.getTag(tag);
                if (holders.isPresent() && holders.get().size() > 1) {
                    built.add(ItemRef.of(SlotTags.PREFIX + tag.location()));
                }
            });
            tagCatalog = List.copyOf(built);
            JERTLog.debug("recipe edit: tag catalog built with {} tags", tagCatalog.size());
        }
        return tagCatalog;
    }

    private static List<ItemRef> fluidCatalog() {
        List<ItemRef> built = new ArrayList<>();
        BuiltInRegistries.FLUID.stream()
            .filter(fluid -> fluid.defaultFluidState().isSource())
            .forEach(fluid -> {
                String key = FluidRecipeCompat.fluidKey(new FluidStack(fluid, 1000));
                if (!key.isEmpty()) {
                    built.add(ItemRef.of(key));
                }
            });
        return List.copyOf(built);
    }

    private static ItemStack iconFor(ItemRef ref) {
        if (ref.isTag()) {
            for (ItemRef member : SlotTags.members(ref)) {
                ItemStack stack = StoredRecipes.displayStack(member, 1);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
            return ItemStack.EMPTY;
        }
        if (ref.isFluid()) {
            return FluidRecipeCompat.representativeForKey(ref.key(), 1).orElse(ItemStack.EMPTY);
        }
        return StoredRecipes.displayStack(ref, 1);
    }

    private int pageCount() {
        return Math.max(1, (visibleRefs().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private int left(int screenWidth) {
        return (screenWidth - WIDTH) / 2;
    }

    private int top(int screenHeight) {
        return (screenHeight - HEIGHT) / 2;
    }

    public void render(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int left = left(screenWidth);
        int top = top(screenHeight);

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.SELECTION_PANEL);
        graphics.fill(0, 0, screenWidth, screenHeight, 0x80000000);
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, PANEL);
        graphics.fill(left, top, left + WIDTH, top + 1, EDGE_LIGHT);
        graphics.fill(left, top, left + 1, top + HEIGHT, EDGE_LIGHT);
        graphics.fill(left + WIDTH - 1, top, left + WIDTH, top + HEIGHT, EDGE_DARK);
        graphics.fill(left, top + HEIGHT - 1, left + WIDTH, top + HEIGHT, EDGE_DARK);

        graphics.drawString(font, tr("gui.jeirecipetree.picker.title"), left + PAD, top + PAD, INK, false);
        String count = (page + 1) + "/" + pageCount();
        graphics.drawString(font, count, left + WIDTH - PAD - font.width(count), top + PAD, INK_SOFT, false);

        int tabsY = top + PAD + 12;
        int tabW = (WIDTH - PAD * 2 - 3 * 4) / 4;

        for (int i = 0; i < SECTION_KEYS.length; i++) {
            int tx = left + PAD + i * (tabW + 4);
            boolean active = section == i;
            graphics.fill(tx, tabsY, tx + tabW, tabsY + 12, active ? 0xFF8FAF8F : 0xFF9A9A9A);
            TreeText.centered(graphics, font, tr(SECTION_KEYS[i]), tx + tabW / 2, tabsY + 2,
                active ? 0xFF000000 : INK);
        }

        int fieldY = tabsY + 14;
        graphics.fill(left + PAD, fieldY, left + WIDTH - PAD, fieldY + 16, 0xFF202020);
        graphics.fill(left + PAD + 1, fieldY + 1, left + WIDTH - PAD - 1, fieldY + 15, 0xFFFFFFFF);
        String placeholder = section == SECTION_PLACEHOLDER
            ? tr("gui.jeirecipetree.picker.prompt.new").getString()
            : tr("gui.jeirecipetree.picker.prompt.search").getString();
        String shown = query.isEmpty() ? placeholder : query + "_";
        graphics.drawString(font, shown, left + PAD + 4, fieldY + 4,
            query.isEmpty() ? 0xFF909090 : 0xFF000000, false);
        if (section == SECTION_PLACEHOLDER) {
            Component create = tr("gui.jeirecipetree.picker.create");
            int btnX = left + WIDTH - PAD - font.width(create) - 10;
            graphics.drawString(font, create, btnX + 5, fieldY + 4,
                query.isBlank() ? 0xFF909090 : 0xFF1E5A1E, false);
        }

        List<ItemRef> items = visibleRefs();
        int start = page * PAGE_SIZE;
        int gridY = fieldY + 20;
        ItemRef hoverRef = null;
        for (int offset = 0; offset < PAGE_SIZE; offset++) {
            int index = start + offset;
            int x = left + PAD + (offset % COLUMNS) * SLOT;
            int y = gridY + (offset / COLUMNS) * SLOT;
            boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
            graphics.fill(x, y, x + SLOT, y + SLOT, WELL);
            graphics.fill(x, y, x + SLOT, y + 1, WELL_DARK);
            graphics.fill(x, y, x + 1, y + SLOT, WELL_DARK);
            if (index >= items.size()) {
                continue;
            }
            ItemRef ref = items.get(index);
            if (hovered) {
                hoverRef = ref;
            }
            if (existingKeys.contains(ref.key())) {

                graphics.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, 0xFFFFC107);
                graphics.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, 0xFFFFC107);
            }
            if (hovered) {

                SlotChrome.frame(graphics, x, y, SLOT, SlotChrome.HOVER);
            }
            ItemStack icon = iconFor(ref);
            if (!icon.isEmpty() && !IngredientDisplay.render(graphics, icon, x + 1, y + 1)) {
                graphics.renderItem(icon, x + 1, y + 1);
            } else if (icon.isEmpty() && ref.isTag()) {

                String tag = ref.key().substring(SlotTags.PREFIX.length());
                graphics.drawString(font, tag, x + 2, y + 6, 0xFF303030, false);
            }
        }

        int footerY = top + HEIGHT - 14;
        graphics.drawString(font, tr("gui.jeirecipetree.picker.footer"),
            left + PAD, footerY, INK_SOFT, false);
        graphics.pose().popPose();
        if (hoverRef != null) {
            drawTooltip(graphics, hoverRef, mouseX, mouseY);
        }
    }

    private void drawTooltip(GuiGraphics graphics, ItemRef ref, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        ItemStack icon = iconFor(ref);
        if (!icon.isEmpty()) {
            lines.add(icon.getHoverName());
        }
        lines.add(Component.literal(ref.key()).withStyle(ChatFormatting.DARK_GRAY));
        if (ref.isTag()) {
            lines.add(tr("gui.jeirecipetree.picker.tooltip.members",
                SlotTags.members(ref).size()).withStyle(ChatFormatting.GRAY));
        }
        if (existingKeys.contains(ref.key())) {
            lines.add(tr("gui.jeirecipetree.picker.tooltip.existing")
                .withStyle(ChatFormatting.GOLD));
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, RenderLayers.TOOLTIP);
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        graphics.pose().popPose();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button, int screenWidth, int screenHeight) {
        if (!open) {
            return false;
        }
        int left = left(screenWidth);
        int top = top(screenHeight);
        boolean inside = mouseX >= left && mouseX < left + WIDTH && mouseY >= top && mouseY < top + HEIGHT;
        if (!inside) {
            close();
            return true;
        }
        if (button == 1) {
            close();
            return true;
        }
        int tabsY = top + PAD + 12;
        if (mouseY >= tabsY && mouseY < tabsY + 12) {
            int tabW = (WIDTH - PAD * 2 - 3 * 4) / 4;
            for (int i = 0; i < SECTION_KEYS.length; i++) {
                int tx = left + PAD + i * (tabW + 4);
                if (mouseX >= tx && mouseX < tx + tabW) {
                    section = i;
                    page = 0;
                    refresh();
                    return true;
                }
            }
        }
        int fieldY = tabsY + 14;
        if (mouseY >= fieldY && mouseY < fieldY + 16) {
            return true;
        }

        if (section == SECTION_PLACEHOLDER && !query.isBlank()) {
            var font = Minecraft.getInstance().font;
            int btnX = left + WIDTH - PAD - font.width(tr("gui.jeirecipetree.picker.create")) - 10;
            if (mouseX >= btnX && mouseY >= fieldY - 12 && mouseY < fieldY) {
                createPlaceholder();
                return true;
            }
        }
        List<ItemRef> items = visibleRefs();
        int gridY = fieldY + 20;
        for (int offset = 0; offset < PAGE_SIZE; offset++) {
            int x = left + PAD + (offset % COLUMNS) * SLOT;
            int y = gridY + (offset / COLUMNS) * SLOT;
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
                int index = page * PAGE_SIZE + offset;
                if (index < items.size() && sink != null) {
                    sink.pick(items.get(index));
                    close();
                }
                return true;
            }
        }
        return true;
    }

    public boolean mouseScrolled(double scrollY) {
        if (!open || scrollY == 0) {
            return false;
        }
        int pages = pageCount();
        if (pages > 1) {
            page = Math.floorMod(page + (scrollY < 0 ? 1 : -1), pages);
        }
        return true;
    }

    public boolean keyPressed(int keyCode) {
        if (!open) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            page = 0;
            refresh();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
            && section == SECTION_PLACEHOLDER && !query.isBlank()) {
            createPlaceholder();
            return true;
        }
        return true;
    }

    private void createPlaceholder() {
        try {
            ItemRef ref = ItemRef.placeholder(query.trim());
            recentPlaceholders.add(ref.key());
            if (sink != null) {
                sink.pick(ref);
            }
            close();
        } catch (IllegalArgumentException error) {

        }
    }

    public boolean charTyped(char codePoint) {
        if (!open) {
            return false;
        }
        if (!Character.isISOControl(codePoint) && query.length() < 24) {
            query += codePoint;
            page = 0;
            refresh();
        }
        return true;
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
