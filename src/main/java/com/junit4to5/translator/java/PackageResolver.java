package com.junit4to5.translator.java;

import java.util.List;
import java.util.Optional;

class PackageResolver {
    private final String packageDeclaration;
    private final List<String> importDeclarations;
    private final CrossReferences crossReferences;

    public PackageResolver(
        String packageDeclaration,
        List<String> importDeclarations,
        CrossReferences crossReferences
    ) {
        this.packageDeclaration = packageDeclaration;
        this.importDeclarations = importDeclarations;
        this.crossReferences = crossReferences;
    }

    /**
     * Import resolution Java rules:
     * 1 - check for fully qualified name:
     * in expression itself
     * in import declarations
     * 2 - check in the default package
     * 3 - check in all wildcard imports
     */
    public Optional<String> resolveType(String type) {
        return resolveFullyQualifiedImport(type)
            .or(() -> resolveDefaultPackage(type))
            .or(() -> resolveWildCardImport(type));
    }

    private Optional<String> resolveFullyQualifiedImport(String type) {
        return importDeclarations.stream()
            .filter(i -> i.endsWith("." + type))
            .findFirst()
            .filter(crossReferences::hasType);
    }

    private Optional<String> resolveDefaultPackage(String type) {
        return Optional.ofNullable("%s.%s".formatted(packageDeclaration, type))
            .filter(crossReferences::hasType);
    }

    private Optional<String> resolveWildCardImport(String type) {
        return importDeclarations.stream()
            .filter(i -> i.endsWith(".*"))
            .map(i -> i.substring(0, i.length() - 2))
            .map(i -> "%s.%s".formatted(i, type))
            .filter(crossReferences::hasType)
            .findFirst();
    }
}
