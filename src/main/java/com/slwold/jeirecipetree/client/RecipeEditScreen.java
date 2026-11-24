package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.core.RecipeRecord;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class RecipeEditScreen extends AbstractContainerScreen<ShellMenu> {

    private static RecipeEditScreen active;

    private final Screen parent;

    private final IRecipeLayoutDrawable<?> layout;
    private final RecipeEditPanel panel;

    public RecipeEditScreen(Screen parent, IRecipeLayoutDrawable<?> layout, RecipeDraft draft) {
        super(new ShellMenu(), net.minecraft.client.Minecraft.getInstance().player.getInventory(),
            Component.translatable("gui.jeirecipetree.edit.title"));
        this.parent = parent;
        this.layout = layout;
        this.panel = new RecipeEditPanel(draft, new RecipeEditPanel.Callbacks() {
            @Override
            public void onSave(RecipeRecord record, String initialGroup) {
                save(record, initialGroup);
            }

            @Override
            public void onCopy(RecipeRecord record) {
                copy(record);
            }

            @Override
            public void onCancel() {
                onClose();
            }
        });
    }

    static List<int[]> slotRects() {
        return active == null ? List.of() : active.panel.slotRects();
    }

    static void acceptDrop(int sectionOrdinal, int index, Object ingredient) {
        if (active != null) {
            active.panel.acceptDrop(sectionOrdinal, index, ingredient);
        }
    }

    @Override
    protected void init() {
        active = this;
        panel.resize(width, height);

        imageWidth = RecipeEditPanel.WIDTH;
        imageHeight = panel.height();
        super.init();
        panel.setHome(leftPos, topPos);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {

        super.render(graphics, mouseX, mouseY, partialTick);
        panel.render(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        panel.resize(width, height);
        return panel.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return panel.mouseDragged(mouseX, mouseY) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return panel.mouseReleased() || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.resize(width, height);
        return panel.mouseScrolled(mouseX, mouseY, scrollY)
            || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (panel.keyPressed(keyCode)) {
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }

        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return panel.charTyped(codePoint) || super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        if (active == this) {
            active = null;
        }
        super.removed();
    }

    private void save(RecipeRecord record, String initialGroup) {

        if (initialGroup != null && !initialGroup.isBlank() && !initialGroup.equals(record.group())) {
            RecipeGroups.removeFrom(initialGroup, record.id());
        }
        RecipeArchive.saveRecord(record);
        if (parent != null) {
            minecraft.setScreen(parent);
        }
        if (layout != null) {

            RecipeTreeScreen.afterImport(layout, record.id());
        }
    }

    private void copy(RecipeRecord record) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (RecipeRecord existing : StoredRecipes.all()) {
            taken.add(existing.id());
        }
        String id = record.id();
        for (int suffix = 2; taken.contains(id); suffix++) {
            id = record.id() + "#" + suffix;
        }
        RecipeArchive.saveRecord(new RecipeRecord(
            id, record.group(), record.outputs(), record.inputs(), record.catalysts()));
        if (parent != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void onClose() {

        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
