package com.junit4to5.translator.java;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

class SymbolTable {
    // TODO - use metadata table instead
    private final Map<String, String> imports;

    SymbolTable() {
        imports = new HashMap<>();
    }

    public Optional<String> getImportFor(String className) {
        return Optional.ofNullable(imports.get(className));
    }

    public void addImport(String importName) {
        String[] importParts = importName.split("\\.");
        imports.put(importParts[importParts.length - 1], importName);
    }

    @Override
    public String toString() {
        return "SymbolTable{" +
               "imports=" + imports +
               '}';
    }
}
