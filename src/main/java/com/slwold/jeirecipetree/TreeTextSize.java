package com.slwold.jeirecipetree;

public enum TreeTextSize {

    small(0.5f),

    normal(1.0f),

    large(2.0f),

    huge(3.0f);

    private final float scale;

    TreeTextSize(float scale) {
        this.scale = scale;
    }

    public float scale() {
        return scale;
    }
}
