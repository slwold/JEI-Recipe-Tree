package com.slwold.jeirecipetree.core;

public final class ViewportFit {

    public static final float MIN_ZOOM = 0.10f;

    public static final float MAX_ZOOM = 1.0f;

    public static final float CONTENT_PADDING = 20.0f;

    public static final int SCREEN_MARGIN = 24;

    public record Fit(float zoom, float offsetX, float offsetY) {
    }

    private ViewportFit() {
    }

    public static Fit fit(
        int minX,
        int minY,
        int maxX,
        int maxY,
        int screenWidth,
        int screenHeight
    ) {
        float contentWidth = Math.max(1, maxX - minX + CONTENT_PADDING * 2.0f);
        float contentHeight = Math.max(1, maxY - minY + CONTENT_PADDING * 2.0f);
        float availableWidth = Math.max(1, screenWidth - SCREEN_MARGIN);
        float availableHeight = Math.max(1, screenHeight - SCREEN_MARGIN);
        float zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM,
            Math.min(availableWidth / contentWidth, availableHeight / contentHeight)));

        return new Fit(zoom, -(minX + maxX) / 2.0f, -(minY + maxY) / 2.0f);
    }
}
