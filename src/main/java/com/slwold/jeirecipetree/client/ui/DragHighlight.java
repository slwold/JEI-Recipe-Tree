package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class DragHighlight {

    static final int TARGET = 0x4013C90A;

    static final int HOVER = 0x804CC919;

    private static boolean jeiArmed;
    private static boolean pressArmed;
    private static boolean pressedOutside;
    private static double pressX;
    private static double pressY;
    private static boolean lastDown;
    private static boolean lastActive;

    private static final double DRAG_THRESHOLD = 3.0D;

    private DragHighlight() {
    }

    static void begin() {
        jeiArmed = true;
        JERTLog.debug("drag highlight: JEI doStart armed");
    }

    static void sample(boolean insidePanel, double mouseX, double mouseY) {
        boolean down = leftButtonDown();
        if (down && !lastDown && !insidePanel) {
            pressedOutside = true;
            pressX = mouseX;
            pressY = mouseY;
        }
        if (down && pressedOutside && !pressArmed
            && Math.hypot(mouseX - pressX, mouseY - pressY) > DRAG_THRESHOLD) {
            pressArmed = true;
            JERTLog.debug("drag highlight: press outside panel armed");
        }
        if (!down && lastDown) {
            jeiArmed = false;
            pressArmed = false;
            pressedOutside = false;
            JERTLog.debug("drag highlight: released");
        }
        lastDown = down;
        boolean active = down && (jeiArmed || pressArmed);
        if (active != lastActive) {
            lastActive = active;
            JERTLog.debug("drag highlight: {}", active ? "on" : "off");
        }
    }

    static boolean active() {
        return lastDown && (jeiArmed || pressArmed);
    }

    private static boolean leftButtonDown() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return false;
        }
        return GLFW.glfwGetMouseButton(
            minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }
}
