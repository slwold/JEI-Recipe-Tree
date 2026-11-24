package com.slwold.jeirecipetree;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;

public final class ConfigBlocksTest {

    private ConfigBlocksTest() {
    }

    public static void main(String[] args) {
        String toml = renderDefaultBlocks();
        CommentedConfig parsed = new TomlParser().parse(toml);

        boolean correct = JERTConfig.SPEC.isCorrect(parsed);
        if (!correct) {
            StringBuilder diff = new StringBuilder();
            JERTConfig.SPEC.correct(parsed,
                (action, path, wrong, right) -> diff.append("\n  value ").append(path)
                    .append(": ").append(wrong).append(" -> ").append(right),
                (action, path, wrong, right) -> diff.append("\n  comment ").append(path)
                    .append(": ").append(wrong).append(" -> ").append(right));
            throw new AssertionError("DEFAULT_BLOCKS does not match the spec:" + diff + "\n--- toml ---\n" + toml);
        }

        String stale = toml.replace("#Allowed Values: paper", "#Allowed Values: paper ");
        check(!JERTConfig.SPEC.isCorrect(new TomlParser().parse(stale)),
            "a drifted comment must be reported as incorrect, otherwise this test proves nothing");

        System.out.println("Config block checks passed: "
            + JERTConfig.DEFAULT_BLOCKS.size() + " keys round-trip through the spec");
    }

    private static String renderDefaultBlocks() {
        StringBuilder out = new StringBuilder();
        for (String key : JERTConfig.KNOWN_KEYS) {
            String block = JERTConfig.DEFAULT_BLOCKS.get(key);
            if (block == null) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(block).append('\n');
        }
        return out.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
