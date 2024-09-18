package com.junit4to5.translator.java;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import antlr.java.JavaParser;

class SymbolTable {

    private final Set<JavaParser.ConstructorDeclarationContext> testInfoUsageConstructors;
    private final Set<JavaParser.MethodDeclarationContext> processedTestInfoUsageMethods;
    private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;
    private final Map<String, String> imports;

    SymbolTable() {
        testInfoUsageConstructors = new HashSet<>();
        processedTestInfoUsageMethods = new HashSet<>();
        testInfoUsageMethods = new HashSet<>();
        imports = new HashMap<>();
    }

    public void addTestInfoUsageConstructor(JavaParser.ConstructorDeclarationContext constructor) {
        testInfoUsageConstructors.add(constructor);
    }

    public Stream<JavaParser.ConstructorDeclarationContext> streamTestInfoUsageConstructors(String identifier) {
        return testInfoUsageConstructors.stream()
            .filter(m -> m.identifier().getText().equals(identifier));
    }

    public void addTestInfoUsageMethod(JavaParser.MethodDeclarationContext method) {
//        if (helperMethods.contains(helperMethod)) { todo - check need
//            throw new UnsupportedOperationException(
//                "Overloaded helper methods not implemented: " + helperMethod.getText());
//        }
        testInfoUsageMethods.add(method);
    }

    public Set<JavaParser.MethodDeclarationContext> getTestInfoUsageMethods() {
        return testInfoUsageMethods;
    }

    public Stream<JavaParser.MethodDeclarationContext> streamTestInfoUsageMethods(String identifier) {
        return testInfoUsageMethods.stream()
            .filter(m -> m.identifier().getText().equals(identifier));
    }

    public boolean isTestInfoUsageMethodProcessed(JavaParser.MethodDeclarationContext method) {
        return processedTestInfoUsageMethods.contains(method);
    }

    public void setTestInfoUsageMethodAsProcessed(JavaParser.MethodDeclarationContext method) {
        processedTestInfoUsageMethods.add(method);
    }

    public Optional<String> getImportFor(String className) {
        return Optional.ofNullable(imports.get(className));
    }

    public void addImport(String importName) {
        String[] importParts = importName.split("\\.");
        imports.put(importParts[importParts.length-1], importName);
    }

    @Override
    public String toString() {
        return "SymbolTable{" +
               "testInfoUsageMethods=" + testInfoUsageMethods +
               ", testInfoUsageConstructors=" + testInfoUsageConstructors +
               ", imports=" + imports +
               '}';
    }
}
