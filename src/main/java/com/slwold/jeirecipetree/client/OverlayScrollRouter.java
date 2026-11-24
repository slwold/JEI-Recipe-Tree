package com.slwold.jeirecipetree.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class OverlayScrollRouter {

    private static final List<OverlayScrollTarget> TARGETS = new CopyOnWriteArrayList<>();
    private static final Map<OverlayScrollTarget, Integer> PRIORITIES = new HashMap<>();

    private OverlayScrollRouter() {
    }

    public static void offer(OverlayScrollTarget target) {
        offer(target, 100);
    }

    public static void offer(OverlayScrollTarget target, int priority) {
        if (target == null || contains(target)) {
            return;
        }
        int index = 0;
        for (OverlayScrollTarget existing : TARGETS) {
            if (PRIORITIES.get(existing) != null && PRIORITIES.get(existing) > priority) {
                break;
            }
            index++;
        }
        PRIORITIES.put(target, priority);
        TARGETS.add(index, target);
    }

    public static void withdraw(OverlayScrollTarget target) {
        if (target != null && TARGETS.remove(target)) {
            PRIORITIES.remove(target);
        }
    }

    public static boolean dispatch(double mouseX, double mouseY, double delta) {
        for (OverlayScrollTarget target : TARGETS) {
            try {
                if (target.handleOverlayScroll(mouseX, mouseY, delta)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError error) {
                TARGETS.remove(target);
                PRIORITIES.remove(target);
                JERTLog.warn("scroll target {} failed and was withdrawn: {}",
                    target.getClass().getName(), String.valueOf(error));
            }
        }
        return false;
    }

    public static int size() {
        return TARGETS.size();
    }

    private static boolean contains(OverlayScrollTarget target) {
        for (OverlayScrollTarget existing : TARGETS) {
            if (existing == target) {
                return true;
            }
        }
        return false;
    }
}
