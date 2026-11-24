package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.core.RecipeRecord;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class RecipeGroups {

    static final int MAX_NAME = 24;

    private RecipeGroups() {
    }

    static Path file(String group) {
        return RecipeLibraryStore.directory(Minecraft.getInstance().gameDirectory.toPath())
            .resolve(group + ".json");
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        name = name.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        return name;
    }

    static List<String> names() {
        Set<String> names = new LinkedHashSet<>();
        names.add(RecipeArchive.GROUP);
        Path directory = RecipeLibraryStore.directory(Minecraft.getInstance().gameDirectory.toPath());
        if (Files.isDirectory(directory)) {
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .forEach(names::add);
            } catch (Exception error) {
                JERTLog.warn("recipe groups: cannot list {}: {}", directory, error.toString());
            }
        }
        return new ArrayList<>(names);
    }

    static boolean create(String group) {
        Path file = file(group);
        if (Files.exists(file)) {
            JERTLog.debug("recipe groups: {} already exists", group);
            return false;
        }
        RecipeLibraryStore.save(file, RecipeLibrary.empty(group));
        StoredRecipes.reload();
        JERTLog.summary("recipe groups: created {}", file.getFileName());
        return true;
    }

    static boolean move(RecipeRecord recipe, String group) {
        if (recipe == null || group == null || group.isBlank()) {
            return false;
        }
        if (group.equals(recipe.group())) {
            return false;
        }
        Path target = file(group);
        if (RecipeLibraryStore.looksCorrupt(target)) {
            JERTLog.warn("recipe groups: {} is not valid JSON, refusing to write into it", target.getFileName());
            return false;
        }
        RecipeLibrary existing = RecipeLibraryStore.load(target);
        List<RecipeRecord> records = new ArrayList<>(existing.recipes());
        records.removeIf(record -> record.id().equals(recipe.id()));
        records.add(new RecipeRecord(recipe.id(), group, recipe.outputs(), recipe.inputs(), recipe.catalysts()));
        RecipeLibraryStore.save(target, RecipeLibrary.of(group, existing.rawRoot(), records, existing.rawByIds()));
        String from = recipe.group().isBlank() ? RecipeArchive.GROUP : recipe.group();
        removeFrom(from, recipe.id());
        StoredRecipes.reload();
        JERTLog.summary("recipe groups: {} moved to {}", recipe.id(), target.getFileName());
        return true;
    }

    static void removeFrom(String group, String id) {
        Path file = file(group);
        if (RecipeLibraryStore.looksCorrupt(file)) {
            JERTLog.warn("recipe groups: {} is not valid JSON, left untouched", file.getFileName());
            return;
        }
        RecipeLibrary existing = RecipeLibraryStore.load(file);
        List<RecipeRecord> records = existing.recipes().stream()
            .filter(record -> !record.id().equals(id))
            .toList();
        if (records.size() == existing.size()) {
            return;
        }
        RecipeLibraryStore.save(
            file,
            RecipeLibrary.of(group, existing.rawRoot(), records, existing.rawByIds())
        );
    }

    static boolean rename(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        if (RecipeArchive.GROUP.equals(from)) {
            JERTLog.warn("recipe groups: {} is the primary group and cannot be renamed", from);
            return false;
        }
        Path source = file(from);
        if (!Files.isRegularFile(source)) {
            return false;
        }
        if (Files.exists(file(to))) {
            JERTLog.warn("recipe groups: {} already exists, rename cancelled", to);
            return false;
        }
        RecipeLibrary existing = RecipeLibraryStore.load(source);
        List<RecipeRecord> records = new ArrayList<>();
        for (RecipeRecord record : existing.recipes()) {
            records.add(new RecipeRecord(record.id(), to, record.outputs(), record.inputs(), record.catalysts()));
        }
        RecipeLibraryStore.save(file(to), RecipeLibrary.of(to, existing.rawRoot(), records, existing.rawByIds()));
        retire(source);
        StoredRecipes.reload();
        JERTLog.summary("recipe groups: {} renamed to {}", from, to);
        return true;
    }

    static boolean delete(String group) {
        if (RecipeArchive.GROUP.equals(group)) {
            JERTLog.warn("recipe groups: {} is the primary group and cannot be deleted", group);
            return false;
        }
        Path source = file(group);
        if (!Files.isRegularFile(source)) {
            return false;
        }
        retire(source);
        StoredRecipes.reload();
        JERTLog.summary("recipe groups: {} deleted (kept as .bak)", group);
        return true;
    }

    private static void retire(Path file) {
        try {
            Files.move(
                file,
                file.resolveSibling(file.getFileName() + ".bak"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        } catch (Exception error) {
            JERTLog.warn("recipe groups: cannot move {} to .bak: {}", file.getFileName(), error.toString());
        }
    }
}
