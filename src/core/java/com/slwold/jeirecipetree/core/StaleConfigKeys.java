package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class StaleConfigKeys {

    private StaleConfigKeys() {
    }

    public record Result(String text, List<String> removed) {

        public boolean changed() {
            return !removed.isEmpty();
        }
    }

    public static Result clean(String text, Set<String> knownKeys) {
        if (text == null || text.isEmpty() || knownKeys == null || knownKeys.isEmpty()) {
            return new Result(text == null ? "" : text, List.of());
        }

        String[] lines = text.split("\n", -1);
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>(lines.length);
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (isTableHeader(line)) {
                inTable = true;
                kept.add(line);
                continue;
            }
            if (inTable) {
                kept.add(line);
                continue;
            }
            String key = topLevelKey(line);
            if (key == null || knownKeys.contains(key)) {
                kept.add(line);
                continue;
            }
            removed.add(key);

            while (!kept.isEmpty()) {
                int last = kept.size() - 1;
                if (isCommentOnly(kept.get(last))) {
                    kept.remove(last);
                    continue;
                }
                if (kept.get(last).trim().isEmpty() && last > 0
                    && isCommentOnly(kept.get(last - 1))) {
                    kept.remove(last);
                    continue;
                }
                break;
            }

            while (i + 1 < lines.length && isCommentOnly(lines[i + 1])) {
                i++;
            }
        }
        return new Result(String.join("\n", kept), List.copyOf(removed));
    }

    public static SyncResult reblock(String text, java.util.Map<String, String> blocks) {
        if (text == null || text.isEmpty() || blocks == null || blocks.isEmpty()) {
            return new SyncResult(text == null ? "" : text, List.of());
        }
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        List<String> changed = new ArrayList<>();
        boolean inTable = false;
        for (String line : lines) {
            if (isTableHeader(line)) {
                inTable = true;
                out.add(line);
                continue;
            }
            String key = inTable ? null : topLevelKey(line);
            List<String> expected = key == null ? null : commentLines(blocks.get(key));
            if (expected == null) {
                out.add(line);
                continue;
            }

            List<String> existing = new ArrayList<>();
            while (!out.isEmpty() && isCommentOnly(out.get(out.size() - 1))) {
                existing.add(0, out.remove(out.size() - 1));
            }
            if (existing.equals(expected)) {
                out.addAll(existing);
            } else {
                out.addAll(expected);
                changed.add(key);
            }
            out.add(line);
        }
        return new SyncResult(String.join("\n", out), List.copyOf(changed));
    }

    public record SyncResult(String text, List<String> reblocked) {

        public boolean changed() {
            return !reblocked.isEmpty();
        }
    }

    private static List<String> commentLines(String block) {
        if (block == null || block.isEmpty()) {
            return null;
        }
        String[] parts = block.split("\n", -1);
        List<String> comments = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length - 1; i++) {
            if (!isCommentOnly(parts[i])) {
                return null;
            }
            comments.add(parts[i]);
        }
        return comments.isEmpty() ? null : comments;
    }

    public static FillResult fill(String text, java.util.Map<String, String> defaults) {
        if (text == null || text.isEmpty() || defaults == null || defaults.isEmpty()) {
            return new FillResult(text == null ? "" : text, List.of());
        }
        Set<String> present = new LinkedHashSet<>();
        boolean inTable = false;
        for (String line : text.split("\n", -1)) {
            if (isTableHeader(line)) {
                inTable = true;
                continue;
            }
            if (inTable) {
                continue;
            }
            String key = topLevelKey(line);
            if (key != null) {
                present.add(key);
            }
        }
        List<String> added = new ArrayList<>();
        for (java.util.Map.Entry<String, String> entry : defaults.entrySet()) {
            if (!present.contains(entry.getKey())) {
                added.add(entry.getKey());
            }
        }
        if (added.isEmpty()) {
            return new FillResult(text, List.of());
        }

        boolean trailingNewline = text.endsWith("\n");
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));

        while (lines.size() > 1 && lines.get(lines.size() - 1).trim().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int insertAt = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                insertAt = i;
                break;
            }
        }

        List<String> insertion = new ArrayList<>();
        for (String key : added) {

            for (String part : defaults.get(key).split("\n", -1)) {
                insertion.add(part);
            }
        }

        lines.addAll(insertAt, insertion);
        String joined = String.join("\n", lines);
        return new FillResult(trailingNewline ? joined + "\n" : joined, List.copyOf(added));
    }

    public record FillResult(String text, List<String> added) {

        public boolean changed() {
            return !added.isEmpty();
        }
    }

    static String topLevelKey(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
            return null;
        }
        char first = line.charAt(0);
        if (first == '#' || first == '[') {
            return null;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = line.substring(0, eq).trim();
        if (key.isEmpty()) {
            return null;
        }

        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }
        return key;
    }

    static boolean isTableHeader(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]")
            && trimmed.length() > 2;
    }

    private static boolean isCommentOnly(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("#") && !trimmed.startsWith("#!");
    }
}
