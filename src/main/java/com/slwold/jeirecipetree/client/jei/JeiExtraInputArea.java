package com.slwold.jeirecipetree.client.jei;

import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.storage.*;

public interface JeiExtraInputArea {
    boolean jert$containsExtraInput(double mouseX, double mouseY);

    default boolean jert$isModalInput() {
        return false;
    }
}
