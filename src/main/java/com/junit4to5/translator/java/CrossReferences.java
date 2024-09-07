package com.junit4to5.translator.java;

import java.util.HashMap;
import java.util.Map;

class CrossReferences {

    private final Map<String, Integer> values;

    CrossReferences() {
        values = new HashMap<>();
    }

    public void addType(String type) {
        values.put(type, 0);
    }

    public boolean hasType(String type) {
        return values.containsKey(type);
    }

    public boolean hasCrossReference(String type) {
        return values.getOrDefault(type, 0) > 0;
    }

    public void incrementType(String type) {
        if (!values.containsKey(type)) {
            throw new IllegalStateException("Unknown type cross reference: " + type);
        }
        values.put(type, values.get(type) + 1);
    }

    @Override
    public String toString() {
        return "CrossReferences{" +
               "values=" + values +
               '}';
    }
}
