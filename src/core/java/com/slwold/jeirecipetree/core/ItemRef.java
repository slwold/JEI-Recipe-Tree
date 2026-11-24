package com.slwold.jeirecipetree.core;

public record ItemRef(String key) {

    public ItemRef {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ItemRef key must not be blank");
        }
    }

    public static ItemRef of(String key) {
        return new ItemRef(key);
    }

    public boolean isFluid() {
        return key.startsWith("fluid:");
    }

    public boolean isTag() {
        return key.startsWith("tag:");
    }

    public boolean isChemical() {
        return key.startsWith("chem:");
    }

    public String chemicalType() {
        if (!isChemical()) {
            return "";
        }
        String rest = key.substring("chem:".length());
        int split = rest.indexOf('#');
        return split < 0 ? rest : rest.substring(0, split);
    }

    public String chemicalUid() {
        if (!isChemical()) {
            return "";
        }
        int split = key.indexOf('#', "chem:".length());
        return split < 0 ? "" : key.substring(split + 1);
    }

    public boolean isPlaceholder() {
        return key.startsWith("placeholder:");
    }

    public String placeholderLabel() {
        return isPlaceholder() ? key.substring("placeholder:".length()) : "";
    }

    public static ItemRef placeholder(String label) {
        String name = label == null ? "" : label.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("placeholder label must not be blank");
        }
        return new ItemRef("placeholder:" + name);
    }
}
