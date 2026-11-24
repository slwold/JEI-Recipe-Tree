package com.slwold.jeirecipetree.client;

public interface JeiExtraInputArea {
    boolean jert$containsExtraInput(double mouseX, double mouseY);

    default boolean jert$isModalInput() {
        return false;
    }
}
