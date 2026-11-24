package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.core.StaleConfigKeys;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigHygiene {

    private static final String FILE_NAME = "jeirecipetree-client.toml";

    private ConfigHygiene() {
    }

    public static Summary apply(Set<String> knownKeys, Map<String, String> defaults) {
        Path file = configFile();
        if (file == null) {

            return new Summary(false, List.of(), List.of(), List.of(), "config path unavailable", null);
        }
        if (!Files.isRegularFile(file)) {
            return new Summary(false, List.of(), List.of(), List.of(), "no config file", file.toString());
        }
        String original;
        try {
            original = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException error) {
            return new Summary(false, List.of(), List.of(), List.of(), "unreadable: " + error, file.toString());
        }

        StaleConfigKeys.Result cleaned = StaleConfigKeys.clean(original, knownKeys);
        StaleConfigKeys.FillResult filled = StaleConfigKeys.fill(cleaned.text(), defaults);

        StaleConfigKeys.SyncResult synced = StaleConfigKeys.reblock(filled.text(), defaults);
        if (!cleaned.changed() && !filled.changed() && !synced.changed()) {
            return new Summary(false, List.of(), List.of(), List.of(), "already current", file.toString());
        }

        String result = synced.text();
        try {
            Files.writeString(file, result, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException error) {

            return new Summary(false, cleaned.removed(), filled.added(), synced.reblocked(),
                "write failed: " + error, file.toString());
        }
        return new Summary(true, cleaned.removed(), filled.added(), synced.reblocked(), "repaired", file.toString());
    }

    private static Path configFile() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Throwable error) {
            try {

                return FMLLoader.getGamePath().resolve("config").resolve(FILE_NAME);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static String literal(boolean value) {
        return Boolean.toString(value);
    }

    public static String literal(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('"').append(escape(values.get(i))).append('"');
        }
        return out.append(']').toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record Summary(
        boolean repaired,
        List<String> removed,
        List<String> added,
        List<String> reblocked,
        String detail,
        String file
    ) {

        @Override
        public String toString() {
            Map<String, Object> parts = new LinkedHashMap<>();
            parts.put("repaired", repaired);
            parts.put("removed", removed);
            parts.put("added", added);
            parts.put("reblocked", reblocked);
            parts.put("detail", detail);
            parts.put("file", file);
            return parts.toString();
        }
    }
}
