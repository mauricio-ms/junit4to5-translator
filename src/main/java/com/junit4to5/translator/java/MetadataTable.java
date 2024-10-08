package com.junit4to5.translator.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import antlr.java.JavaParser;

class MetadataTable {

    static class Metadata {
        private final String packageDeclaration;
        private final String extendsIdentifier;
        private final List<String> importDeclarations;
        private final Map<String, Object> instanceVariables;
        private final Map<String, String> annotatedInstanceVariables;
        private final Set<JavaParser.ConstructorDeclarationContext> testInfoUsageConstructors;
        private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;
        private final Set<JavaParser.MethodDeclarationContext> annotatedJUnitMethods;
        private final Set<String> staticAddedImports;
        private final Set<String> addedImports;

        public Metadata(
            String packageDeclaration,
            String extendsIdentifier,
            List<String> importDeclarations,
            Map<String, Object> instanceVariables,
            Map<String, String> annotatedInstanceVariables,
            Set<JavaParser.ConstructorDeclarationContext> testInfoUsageConstructors,
            Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods
        ) {
            this.packageDeclaration = packageDeclaration;
            this.extendsIdentifier = extendsIdentifier;
            this.importDeclarations = importDeclarations;
            this.instanceVariables = instanceVariables;
            this.annotatedInstanceVariables = annotatedInstanceVariables;
            this.testInfoUsageConstructors = testInfoUsageConstructors;
            this.testInfoUsageMethods = testInfoUsageMethods;
            annotatedJUnitMethods = new HashSet<>();
            staticAddedImports = new HashSet<>();
            addedImports = new HashSet<>();
        }

        public Map<String, Object> getInstanceVariables() {
            return instanceVariables;
        }
        
        public Stream<String> streamRules(String... types) {
            return Stream.of(types)
                .map(type -> annotatedInstanceVariables.get("RULE:" + type))
                .filter(Objects::nonNull);
        }

        public void addTestInfoUsageConstructor(JavaParser.ConstructorDeclarationContext testInfoUsageConstructor) {
            testInfoUsageConstructors.add(testInfoUsageConstructor);
        }

        public Stream<JavaParser.ConstructorDeclarationContext> streamTestInfoUsageConstructors(String identifier) {
            return testInfoUsageConstructors.stream()
                .filter(m -> m.identifier().getText().equals(identifier));
        }

        public Set<JavaParser.MethodDeclarationContext> getTestInfoUsageMethods() {
            return testInfoUsageMethods;
        }

        public void addTestInfoUsageMethod(JavaParser.MethodDeclarationContext method) {
            testInfoUsageMethods.add(method);
        }

        public Stream<JavaParser.MethodDeclarationContext> streamTestInfoUsageMethods() {
            return testInfoUsageMethods.stream();
        }
        
        public void addAnnotatedJUnitMethod(JavaParser.MethodDeclarationContext method) {
            annotatedJUnitMethods.add(method);
        }

        public boolean isAnnotatedJUnitMethod(JavaParser.MethodDeclarationContext method) {
            return annotatedJUnitMethods.contains(method);
        }

        public Set<String> getStaticAddedImports() {
            return staticAddedImports;
        }

        public void addStaticImport(String staticImportDeclaration) {
            staticAddedImports.add(staticImportDeclaration);
        }

        public Set<String> getAddedImports() {
            return addedImports;
        }

        public void addImport(String importDeclaration) {
            addedImports.add(importDeclaration);
        }

        static class MetadataBuilder {
            private String packageDeclaration;
            private String extendsIdentifier;
            private Map<String, Object> instanceVariables;
            private final List<String> importDeclarations;
            private final Map<String, String> annotatedInstanceVariables;
            private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;

            public MetadataBuilder() {
                importDeclarations = new ArrayList<>();
                annotatedInstanceVariables = new HashMap<>();
                testInfoUsageMethods = new HashSet<>();
            }

            public void setPackageDeclaration(String packageDeclaration) {
                this.packageDeclaration = packageDeclaration;
            }

            public void setExtendsIdentifier(String extendsIdentifier) {
                this.extendsIdentifier = extendsIdentifier;
            }

            public void setInstanceVariables(Map<String, Object> instanceVariables) {
                this.instanceVariables = instanceVariables;
            }

            public List<String> getImportDeclarations() {
                return importDeclarations;
            }

            public void addImportDeclaration(String importDeclaration) {
                importDeclarations.add(importDeclaration);
            }
            
            public void addRuleInstanceVariable(String type, String identifier) {
                annotatedInstanceVariables.put("RULE:" + type, identifier);
            }

            public void addTestInfoUsageMethod(JavaParser.MethodDeclarationContext testInfoUsageMethod) {
                testInfoUsageMethods.add(testInfoUsageMethod);
            }

            public Metadata build() {
                return new Metadata(
                    Objects.requireNonNull(packageDeclaration, "packageDeclaration"),
                    extendsIdentifier,
                    importDeclarations,
                    Optional.ofNullable(instanceVariables).orElseGet(HashMap::new),
                    annotatedInstanceVariables,
                    new HashSet<>(),
                    testInfoUsageMethods);
            }
        }
    }

    private final CrossReferences crossReferences;
    private final Map<String, Metadata> table;

    public MetadataTable(CrossReferences crossReferences) {
        this.crossReferences = crossReferences;
        table = new HashMap<>();
    }

    public Optional<JavaParser.ConstructorDeclarationContext> maybeTestInfoUsageConstructor(
        String fullyQualifiedClassName,
        String identifier,
        JavaParser.ArgumentsContext arguments
    ) {
        return maybeGet(fullyQualifiedClassName)
            .map(metadata -> metadata.streamTestInfoUsageConstructors(identifier))
            .orElseGet(Stream::empty)
            .filter(testInfoUsageConstructor -> {
                FormalParameters formalParameters = FormalParameters.get(testInfoUsageConstructor.formalParameters());
                int callArgumentsSize = ArgumentsResolver.resolveSize(arguments);
                return formalParameters.isCallCompatible(callArgumentsSize);
            })
            .findFirst();
    }

    public Optional<JavaParser.MethodDeclarationContext> maybeTestInfoUsageMethod(
        String fullyQualifiedClassName,
        String identifier,
        JavaParser.ArgumentsContext arguments
    ) {
        return streamTestInfoUsageMethod(fullyQualifiedClassName)
            .filter(m -> m.identifier().getText().equals(identifier))
            .filter(testInfoUsageMethod -> {
                FormalParameters formalParameters = FormalParameters.get(testInfoUsageMethod.formalParameters());
                int callArgumentsSize = ArgumentsResolver.resolveSize(arguments);
                return formalParameters.isCallCompatible(callArgumentsSize);
            })
            .findFirst();
    }

    public Stream<JavaParser.MethodDeclarationContext> streamTestInfoUsageMethod(String fullyQualifiedClassName) {
        Metadata metadata = maybeGet(fullyQualifiedClassName).orElse(null);
        Stream<JavaParser.MethodDeclarationContext> stream = metadata != null ?
            metadata.streamTestInfoUsageMethods() :
            Stream.empty();
        while (metadata != null && metadata.extendsIdentifier != null) {
            PackageResolver packageResolver = new PackageResolver(
                metadata.packageDeclaration,
                metadata.importDeclarations,
                crossReferences);

            metadata = packageResolver.resolveType(metadata.extendsIdentifier)
                .map(this::get)
                .orElse(null);
            if (metadata != null) {
                stream = Stream.concat(
                    stream,
                    metadata.streamTestInfoUsageMethods());
            }
        }

        return stream;
    }

    public Optional<Metadata> maybeGet(String fullyQualifiedClassName) {
        return Optional.ofNullable(table.get(fullyQualifiedClassName));
    }

    public Metadata get(String fullyQualifiedClassName) {
        return Optional.ofNullable(table.get(fullyQualifiedClassName))
            .orElseThrow(() -> new IllegalStateException(fullyQualifiedClassName + " not found in metadata table."));
    }

    public Optional<Metadata> getBase(String fullyQualifiedClassName) {
        return Optional.ofNullable(table.get(fullyQualifiedClassName))
            .map(metadata -> {
                PackageResolver packageResolver = new PackageResolver(
                    metadata.packageDeclaration,
                    metadata.importDeclarations,
                    crossReferences);

                return packageResolver.resolveType(metadata.extendsIdentifier)
                    .map(this::get)
                    .orElse(null);
            });
    }

    public void put(String fullyQualifiedClassName, Metadata metadata) {
        if (table.containsKey(fullyQualifiedClassName)) {
            throw new IllegalStateException(fullyQualifiedClassName + " already declared in metadata table.");
        }
        table.put(fullyQualifiedClassName, metadata);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        table.forEach((fullyQualifiedClassName, metadata) -> {
            sb.append("%s:%n".formatted(fullyQualifiedClassName));
            sb.append("\tPackage: %s%n".formatted(metadata.packageDeclaration));
            Optional.ofNullable(metadata.extendsIdentifier)
                .ifPresent(e -> sb.append("\tExtends: %s%n".formatted(e)));
            sb.append("\tTest Info Usage Methods:%n".formatted());
            metadata.testInfoUsageMethods.forEach(m -> sb.append("\t\t%s%n".formatted(m.getText())));

        });
        return sb.toString();
    }
}
