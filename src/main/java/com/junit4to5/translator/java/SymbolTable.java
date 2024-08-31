package com.junit4to5.translator.java;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import antlr.java.JavaParser;

class SymbolTable {

    private final Set<JavaParser.MethodDeclarationContext> processedTestInfoUsageMethods;
    private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;
    private final Map<String, String> variableTypes;

    SymbolTable() {
        processedTestInfoUsageMethods = new HashSet<>();
        testInfoUsageMethods = new HashSet<>();
        variableTypes = new HashMap<>();
    }

    public void addTestInfoUsageMethod(JavaParser.MethodDeclarationContext helperMethod) {
//        if (helperMethods.contains(helperMethod)) { todo - check need
//            throw new UnsupportedOperationException(
//                "Overloaded helper methods not implemented: " + helperMethod.getText());
//        }
        testInfoUsageMethods.add(helperMethod);
    }

    public Set<JavaParser.MethodDeclarationContext> getTestInfoUsageMethods() {
        return testInfoUsageMethods;
    }

    public Optional<JavaParser.MethodDeclarationContext> maybeTestInfoUsageMethod(String identifier) {
        return testInfoUsageMethods.stream()
            .filter(m -> m.identifier().getText().equals(identifier))
            .findFirst();
    }

    public boolean isTestInfoUsageMethodProcessed(JavaParser.MethodDeclarationContext testInfoUsageMethod) {
        return processedTestInfoUsageMethods.contains(testInfoUsageMethod);
    }

    public void setTestInfoUsageMethodAsProcessed(JavaParser.MethodDeclarationContext testInfoUsageMethod) {
        processedTestInfoUsageMethods.add(testInfoUsageMethod);
    }

    public String getVariableType(String variable) {
        return variableTypes.get(variable);
    }

    public void setVariableType(String variable, String type) {
        variableTypes.put(variable, type);
    }

    @Override
    public String toString() {
        return "SymbolTable{" +
               "testInfoUsageMethods=" + testInfoUsageMethods +
               ", variableTypes=" + variableTypes +
               '}';
    }
}
