package com.slwold.jeirecipetree.client.jei;

import com.slwold.jeirecipetree.client.ui.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.storage.*;

public interface OverlayScrollTarget {

    boolean handleOverlayScroll(double mouseX, double mouseY, double delta);
}
