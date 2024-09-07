package com.junit4to5.translator.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.RuleContext;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

class JavaCrossDependenciesFinder extends JavaParserBaseVisitor<Void> {
    private final CrossReferences crossReferences;
    private final List<String> importDeclarations;
    private String packageDeclaration;

    public JavaCrossDependenciesFinder(CrossReferences crossReferences) {
        this.crossReferences = crossReferences;
        importDeclarations = new ArrayList<>();
    }

    @Override
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        return null;
    }

    @Override
    public Void visitImportDeclaration(JavaParser.ImportDeclarationContext ctx) {
        String importDeclaration = ctx.qualifiedName().getText();
        if (ctx.MUL() != null) {
            importDeclaration += ".*";
        } else {
            // for import static cases
            String possibleTypeName = importDeclaration.substring(0, importDeclaration.lastIndexOf('.'));
            incrementCrossReferenceTypeIfPresent(possibleTypeName);
            incrementCrossReferenceTypeIfPresent(importDeclaration);
        }
        importDeclarations.add(importDeclaration);
        return super.visitImportDeclaration(ctx);
    }

    private void incrementCrossReferenceTypeIfPresent(String type) {
        if (crossReferences.hasType(type)) {
            crossReferences.incrementType(type);
        }
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        if (ctx.DOT() != null && ctx.methodCall() != null) {
            String type = ctx.expression(0).getText();
            // TODO - needed to track cross references to methods imported via static import
            // String call = ctx.methodCall().getText();
            resolveType(type)
                .ifPresent(crossReferences::incrementType);
        }
        return super.visitExpression(ctx);
    }

    @Override
    public Void visitClassOrInterfaceType(JavaParser.ClassOrInterfaceTypeContext ctx) {
        String type = ctx.identifier().stream().map(RuleContext::getText)
            .collect(Collectors.joining("."));
        // TODO - needed to track cross references to methods imported via static import
        // ctx.typeIdentifier().getText()
        resolveType(type)
            .ifPresent(crossReferences::incrementType);
        return super.visitClassOrInterfaceType(ctx);
    }

    /**
     * Import resolution Java rules:
     * 1 - check for fully qualified name:
     * in expression itself
     * in import declarations
     * 2 - check in the default package
     * 3 - check in all wildcard imports
     */
    private Optional<String> resolveType(String type) {
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
            .filter(i -> crossReferences.hasType(
                "%s.%s".formatted(i, type)))
            .findFirst();
    }
}
