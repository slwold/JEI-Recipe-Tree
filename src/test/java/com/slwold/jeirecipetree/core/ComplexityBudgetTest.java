package com.slwold.jeirecipetree.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ComplexityBudgetTest {

    private static final int CC_LIMIT = 15;

    private static final int MIN_LOC = 15;

    private static final Set<String> BASELINE = Set.of(

        "client/RecipeTreeScreen.java:mouseClicked",
        "client/RecipeTreeScreen.java:renderHoverTooltipInner",

        "client/StorageNetworkIntegration.java:craftingGridMatches",
        "client/StorageNetworkIntegration.java:refinedStorageSnapshot",
        "client/StorageNetworkIntegration.java:findCompatibleMethod",
        "client/StorageNetworkIntegration.java:storedStacks",
        "client/StorageNetworkIntegration.java:fluidEntry",
        "client/StorageNetworkIntegration.java:prioritizeBetterBeyond",
        "client/StorageNetworkIntegration.java:findBackingList",
        "client/Ae2StorageIntegration.java:craftingGridMatches",
        "client/Ae2StorageIntegration.java:tryFillCraftingGrid",

        "client/RecipeTreeTransfer.java:maxBatchesForInstantInputs",
        "client/RecipeTreeTransfer.java:tick",
        "client/RecipeTreeTransfer.java:tickAe2Carry",
        "client/RecipeTreeSession.java:ensureRestored",

        "client/RecipeTreeFavorites.java:refreshNowMeasured",
        "client/RecipeTreeFavorites.java:laidOutBookmarkColumns",
        "client/RecipeTreeSidebarPanel.java:handleClickAt",
        "client/JeiTooltipCompat.java:createTagContent",
        "client/SupplyLedger.java:scan",

        "core/StaleConfigKeys.java:fill",
        "core/StaleConfigKeys.java:clean",
        "core/StaleConfigKeys.java:topLevelKey"
    );

    private static final Pattern SIGNATURE = Pattern.compile(
        "^\\s{4}(?:public|private|protected|static|final|synchronized|abstract|native)"
            + "[\\w\\s<>\\[\\],.?]*?\\b(\\w+)\\s*\\(",
        Pattern.MULTILINE);

    private static final Pattern[] DECISIONS = {
        Pattern.compile("\\bif\\b"),
        Pattern.compile("\\belse\\s+if\\b"),
        Pattern.compile("\\bfor\\b"),
        Pattern.compile("\\bwhile\\b"),
        Pattern.compile("\\bcase\\b"),
        Pattern.compile("\\bcatch\\b"),
        Pattern.compile("&&"),
        Pattern.compile("\\|\\|"),
        Pattern.compile("\\?[^:]*:")
    };

    private static final Set<String> NON_METHODS = Set.of(
        "class", "record", "enum", "interface", "new", "return", "if", "for", "while", "switch");

    private ComplexityBudgetTest() {
    }

    public static void main(String[] args) throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> found = new TreeSet<>();

        for (Path source : sources()) {
            String text = stripCommentsAndLiterals(Files.readString(source, StandardCharsets.UTF_8));
            String relative = relative(source);
            for (Measurement m : measure(text)) {
                if (m.complexity <= CC_LIMIT || m.lines < MIN_LOC) {
                    continue;
                }
                String id = relative + ":" + m.name;
                found.add(id);
                if (!BASELINE.contains(id)) {
                    offenders.add(id + "  CC=" + m.complexity + " LOC=" + m.lines);
                }
            }
        }

        if (!offenders.isEmpty()) {
            throw new AssertionError(
                "new method(s) exceed the complexity budget (CC > " + CC_LIMIT + ", LOC >= " + MIN_LOC
                    + "). Extract a helper or return early; if it is genuinely irreducible, "
                    + "add it to BASELINE with a reason:\n  " + String.join("\n  ", offenders));
        }

        Set<String> stale = new LinkedHashSet<>(BASELINE);
        stale.removeAll(found);
        System.out.println("Complexity budget checks passed: "
            + found.size() + " baseline method(s) over CC " + CC_LIMIT
            + ", no new offenders"
            + (stale.isEmpty() ? "" : ", " + stale.size() + " baseline entr(ies) no longer over budget"));
    }

    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String root : new String[] {"src/main", "src/core"}) {
            try (var stream = Files.walk(Path.of(root))) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    private static String relative(Path source) {
        String normalized = source.toString().replace('\\', '/');
        int at = normalized.indexOf("jeirecipetree/");
        return at < 0 ? normalized : normalized.substring(at + "jeirecipetree/".length());
    }

    private record Measurement(String name, int complexity, int lines) {
    }

    private static List<Measurement> measure(String source) {
        List<Measurement> out = new ArrayList<>();
        Matcher m = SIGNATURE.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            if (NON_METHODS.contains(name)) {
                continue;
            }
            int brace = source.indexOf('{', m.end());
            if (brace < 0) {
                continue;
            }
            int semicolon = source.indexOf(';', m.end());
            if (semicolon >= 0 && semicolon < brace) {
                continue;
            }
            int end = matchBrace(source, brace);
            if (end < 0) {
                continue;
            }
            String body = source.substring(brace, end);
            int complexity = 1;
            for (Pattern decision : DECISIONS) {
                Matcher d = decision.matcher(body);
                while (d.find()) {
                    complexity++;
                }
            }
            int startLine = lineOf(source, m.start());
            int endLine = lineOf(source, end);
            out.add(new Measurement(name, complexity, endLine - startLine + 1));
        }
        return out;
    }

    private static int matchBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int lineOf(String source, int index) {
        int line = 1;
        for (int i = 0; i < index && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (source.charAt(i) == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(quote).append(quote);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
