package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.JERTConfig;

final class TreePalette {

    static final int GRID_SPACING = 13;

    static final int GRID_MAJOR_EVERY = 5;

    private record Theme(
        String id,
        int paper,
        int paper2,
        int gridMinor,
        int gridMajor,
        int well,
        int nodeFill,
        int nodeBorder,
        int ink,
        int ink2,
        int ink3,
        int rule,
        int rule2,
        int line,
        int accent,
        int ok,
        int missing,
        int scrim,
        int tape,
        int tapeEdge,
        int tapeInk
    ) {
    }

    private static final Theme PAPER = new Theme(
        "paper",
        0xFFFBF8F1,
        0xFFFFFDF7,
        0x8CE0D7C3,
        0xCCC9BE9E,
        0xFFEDE7D6,
        0xFFFFFDF7,
        0xFFD3C9B0,
        0xFF17324C,
        0xFF3F5B76,
        0xFF5F6C77,
        0xFFE3DCCA,
        0xFFD3C9B0,
        0xFF8C99A6,
        0xFF2F6FD0,
        0xFF2E7D32,
        0xFFC23B22,
        0x44000000,
        0xC8E8873A,
        0xFFA85A1C,
        0xFF3A1F05
    );

    private static final Theme CLASSIC = new Theme(
        "classic",
        0xFFF7F4EC,
        0xFFFCFAF4,
        0x1C2F5C96,
        0x382F5C96,
        0xFFF2EFE6,
        0xFFFCFAF4,
        0xFF8FA6C4,
        0xFF16346B,
        0xFF1E4A7A,
        0xFF5C6E8C,
        0xFF8FA6C4,
        0xFF16346B,
        0xFF3C6396,
        0xFF1E6CB5,
        0xFF1F7A4C,
        0xFFC0362B,
        0x30000000,
        0xC82F5C96,
        0xFF1B3961,
        0xFFF2F7FF
    );

    private static final Theme BLUEPRINT = new Theme(
        "blueprint",
        0xFFCBD7EA,
        0xFFE4ECF8,
        0x1F3362AC,
        0x473362AC,
        0xFFB7C7DE,
        0xFFE4ECF8,
        0xFF93A9C9,
        0xFF12294B,
        0xFF2C4770,
        0xFF475770,
        0xFFB9C8E0,
        0xFF93A9C9,
        0xFF6B82A6,
        0xFF1F5AA8,
        0xFF186339,
        0xFFA12B18,
        0x44203A5C,
        0xC81F4E96,
        0xFF13346B,
        0xFFF4F8FF
    );

    private static final Theme BLUEPRINT_DARK = new Theme(
        "blueprint_dark",
        0xFF14305C,
        0xFF1E4172,
        0x2ECBD7EA,
        0x57CBD7EA,
        0xFF1E4172,
        0xFF1E4172,
        0xFF3A5F94,
        0xFFE7EEF8,
        0xFFB9CBE4,
        0xFF8FA7C6,
        0xFF2C4E80,
        0xFF3A5F94,
        0xFF7E97BC,
        0xFF7FB2F0,
        0xFF6FD08C,
        0xFFFF8A6B,
        0x66000000,
        0xC8F5C542,
        0xFFA87B18,
        0xFF241A03
    );

    private static final Theme AE2 = new Theme(
        "ae2",
        0xFFDEDFE3,
        0xFFF2F2F2,
        0x18777B8C,
        0x30777B8C,
        0xFFADB0C4,
        0xFFF2F2F2,
        0xFF878FA5,
        0xFF413F54,
        0xFF5D6174,
        0xFF565C6A,
        0xFF878FA5,
        0xFF413F54,
        0xFF777B8C,
        0xFF16637F,
        0xFF276646,
        0xFFB31F01,
        0x44000000,
        0xC84A3F8F,
        0xFF2E2760,
        0xFFF7F4FF
    );

    private static final Theme DARK = new Theme(
        "dark",
        0xFF10161C,
        0xFF1B242B,
        0x241E2A31,
        0x38384650,
        0xFF273038,
        0xFF1B242B,
        0xFF4D5962,
        0xFFFFFFFF,
        0xFFDDE6EE,
        0xFF9AA6B0,
        0xFF4D5962,
        0xFF66737B,
        0xFFC3CBD3,
        0xFF7CC7FF,
        0xFF7CFF9B,
        0xFFFF8A80,
        0xB0000000,
        0xC84FC3E8,
        0xFF2A7E9C,
        0xFF05161C
    );

    private static volatile Theme active = BLUEPRINT;

    private static volatile com.slwold.jeirecipetree.TreeTheme activeChoice =
        com.slwold.jeirecipetree.TreeTheme.blueprint;

    private TreePalette() {
    }

    static void syncTheme() {
        com.slwold.jeirecipetree.TreeTheme choice = SafeConfig.treeTheme();
        if (choice != activeChoice) {
            activeChoice = choice;
            active = resolve(choice);
            JERTLog.debug("tree theme: {}", active.id());
        }
    }

    static Theme resolve(com.slwold.jeirecipetree.TreeTheme theme) {
        return switch (theme) {
            case paper -> PAPER;
            case classic -> CLASSIC;
            case blueprint -> BLUEPRINT;
            case blueprint_dark -> BLUEPRINT_DARK;
            case ae2 -> AE2;
            case dark -> DARK;
        };
    }

    static String activeId() {
        return active.id();
    }

    static void cycleTheme(int step) {
        com.slwold.jeirecipetree.TreeTheme[] choices = com.slwold.jeirecipetree.TreeTheme.values();
        int delta = step < 0 ? -1 : 1;
        com.slwold.jeirecipetree.TreeTheme next =
            choices[Math.floorMod(activeChoice.ordinal() + delta, choices.length)];
        activeChoice = next;
        active = resolve(next);
        JERTLog.debug("tree theme cycled: {} ({})", next.name(), delta > 0 ? "next" : "prev");
        try {
            JERTConfig.TREE_THEME.set(next);
            JERTConfig.SPEC.save();
        } catch (Throwable error) {
            JERTLog.warn("could not persist treeTheme '{}': {}", next.name(), error.getMessage());
        }
    }

    static String ids() {
        StringBuilder text = new StringBuilder();
        for (com.slwold.jeirecipetree.TreeTheme choice : com.slwold.jeirecipetree.TreeTheme.values()) {
            if (!text.isEmpty()) {
                text.append(" / ");
            }
            text.append(choice.name());
        }
        return text.toString();
    }

    private static final int CANVAS_INK = 0xFFFFFFFF;

    static int canvasInk() {
        return CANVAS_INK;
    }

    static int paper() {
        return active.paper();
    }

    static int paper2() {
        return active.paper2();
    }

    static int gridMinor() {
        return active.gridMinor();
    }

    static int gridMajor() {
        return active.gridMajor();
    }

    static int well() {
        return active.well();
    }

    static int nodeFill() {
        return active.nodeFill();
    }

    static int nodeBorder() {
        return active.nodeBorder();
    }

    static int tape() {
        return active.tape();
    }

    static int tapeEdge() {
        return active.tapeEdge();
    }

    static int tapeInk() {
        return active.tapeInk();
    }

    static int ink() {
        return active.ink();
    }

    static int ink2() {
        return active.ink2();
    }

    static int ink3() {
        return active.ink3();
    }

    static int rule() {
        return active.rule();
    }

    static int rule2() {
        return active.rule2();
    }

    static int line() {
        return active.line();
    }

    static int accent() {
        return active.accent();
    }

    static int ok() {
        return active.ok();
    }

    static int missing() {
        return active.missing();
    }

    static int scrim() {
        return active.scrim();
    }
}
