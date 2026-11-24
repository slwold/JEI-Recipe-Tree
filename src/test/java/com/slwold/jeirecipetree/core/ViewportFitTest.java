package com.slwold.jeirecipetree.core;

public final class ViewportFitTest {

    private static final int SCREEN_W = 854;
    private static final int SCREEN_H = 480;

    private ViewportFitTest() {
    }

    public static void main(String[] args) {
        emptyContentStaysAtFullScale();
        compactTreeIsNotEnlarged();
        wideTreeScalesDownToFit();
        deepTreeScalesDownToFit();
        extremeTreeHitsTheFloor();
        offsetsCentreTheContent();
        zoomAlwaysStaysInRange();
        System.out.println("ViewportFit checks passed: empty/compact/wide/deep/extreme trees, "
            + "centre offsets, zoom bounds.");
    }

    private static void emptyContentStaysAtFullScale() {
        ViewportFit.Fit fit = ViewportFit.fit(0, 0, 0, 0, SCREEN_W, SCREEN_H);
        check(fit.zoom() == 1.0f, "an empty tree must open at 1.0, got " + fit.zoom());
    }

    private static void compactTreeIsNotEnlarged() {
        ViewportFit.Fit fit = ViewportFit.fit(-40, -20, 40, 20, SCREEN_W, SCREEN_H);
        check(fit.zoom() == 1.0f, "a compact tree must not be enlarged, got " + fit.zoom());
    }

    private static void wideTreeScalesDownToFit() {

        ViewportFit.Fit fit = ViewportFit.fit(0, 0, 2000, 100, SCREEN_W, SCREEN_H);
        check(fit.zoom() < 1.0f, "a wide tree must scale down, got " + fit.zoom());
        check(fit.zoom() > 0.3f && fit.zoom() < 0.6f, "unexpected width-driven zoom " + fit.zoom());
    }

    private static void deepTreeScalesDownToFit() {

        ViewportFit.Fit fit = ViewportFit.fit(0, 0, 200, 900, SCREEN_W, SCREEN_H);
        check(fit.zoom() < 1.0f, "a deep tree must scale down, got " + fit.zoom());
        check(fit.zoom() > 0.4f && fit.zoom() < 0.6f, "unexpected height-driven zoom " + fit.zoom());
    }

    private static void extremeTreeHitsTheFloor() {
        ViewportFit.Fit fit = ViewportFit.fit(0, 0, 100_000, 100_000, SCREEN_W, SCREEN_H);
        check(fit.zoom() == ViewportFit.MIN_ZOOM,
            "an extreme tree must clamp to MIN_ZOOM, got " + fit.zoom());
    }

    private static void offsetsCentreTheContent() {
        ViewportFit.Fit fit = ViewportFit.fit(-100, -50, 300, 150, SCREEN_W, SCREEN_H);

        check(fit.offsetX() == -100.0f, "offsetX must negate the centre, got " + fit.offsetX());
        check(fit.offsetY() == -50.0f, "offsetY must negate the centre, got " + fit.offsetY());
    }

    private static void zoomAlwaysStaysInRange() {
        int[] samples = {0, 1, 64, 800, 8_000, 80_000};
        for (int size : samples) {
            for (int screen : new int[] {64, 480, 1080}) {
                ViewportFit.Fit fit = ViewportFit.fit(0, 0, size, size, screen, screen);
                check(fit.zoom() >= ViewportFit.MIN_ZOOM && fit.zoom() <= ViewportFit.MAX_ZOOM,
                    "zoom out of range for size=" + size + " screen=" + screen + ": " + fit.zoom());
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
