package com.slwold.jeirecipetree.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class RecipeFingerprint {

    private RecipeFingerprint() {
    }

    public static String contentId(
        String categoryUid,
        List<RecipeRecord.Slot> outputs,
        List<RecipeRecord.Slot> inputs
    ) {
        String category = categoryUid == null ? "" : categoryUid;
        StringBuilder seed = new StringBuilder(category);
        appendSlots(seed, 'O', outputs);
        appendSlots(seed, 'I', inputs);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                .digest(seed.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", impossible);
        }
        return category + "|content/" + HexFormat.of().formatHex(digest, 0, 8);
    }

    private static void appendSlots(StringBuilder seed, char tag, List<RecipeRecord.Slot> slots) {
        if (slots == null) {
            return;
        }
        for (RecipeRecord.Slot slot : slots) {
            seed.append('|').append(tag).append(':').append(slot.amount()).append(':');
            for (ItemRef alternative : slot.alternatives()) {
                seed.append(alternative.key()).append(',');
            }
        }
    }
}
