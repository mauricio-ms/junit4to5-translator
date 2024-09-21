package com.junit4to5.translator.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.antlr.v4.runtime.Token;

import antlr.java.JavaParser;

class JUnit4TestNameRecursiveFinder extends BaseJUnit4To5Pass {
    private final MetadataTable metadataTable;
    private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;
    private final Map<JavaParser.MethodDeclarationContext, List<Token>> testInfoUsageMethodsTokensProcessed;

    private Scope currentScope;
    private String packageDeclaration;
    private String fullyQualifiedName;
    private boolean isFirstLevelMethodCall;

    JUnit4TestNameRecursiveFinder(MetadataTable metadataTable) {
        this.metadataTable = metadataTable;
        testInfoUsageMethods = new HashSet<>();
        testInfoUsageMethodsTokensProcessed = new HashMap<>();
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        super.visitCompilationUnit(ctx);
        if (fullyQualifiedName != null) {
            MetadataTable.Metadata metadata = metadataTable.get(fullyQualifiedName);
            if (!testInfoUsageMethods.isEmpty()) {
                testInfoUsageMethods.forEach(metadata::addTestInfoUsageMethod);
                testInfoUsageMethods.clear();
                visitCompilationUnit(ctx);
            }
        }
        return null;
    }

    @Override
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        return null;
    }

    @Override
    public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        if (fullyQualifiedName == null) {
            fullyQualifiedName = "%s.%s".formatted(packageDeclaration, ctx.identifier().getText());
        }
        super.visitClassDeclaration(ctx);
        return null;
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        currentScope.declare("method", ctx);
        super.visitMethodDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        isFirstLevelMethodCall = isFirstLevelMethodCall(ctx);
        return super.visitExpression(ctx);
    }

    private boolean isFirstLevelMethodCall(JavaParser.ExpressionContext ctx) {
        List<JavaParser.ExpressionContext> expression = ctx.expression();
        if (!expression.isEmpty()) {
            return expression.get(0).methodCall() != null;
        } else {
            return ctx.methodCall() != null;
        }
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        JavaParser.MethodDeclarationContext method = (JavaParser.MethodDeclarationContext) currentScope.get("method");
        if (!isFirstLevelMethodCall || method == null || isProcessed(method, ctx.start)) {
            return super.visitMethodCall(ctx);
        }

        metadataTable.maybeTestInfoUsageMethod(fullyQualifiedName, ctx.identifier().getText(), ctx.arguments())
            .ifPresent(testInfoUsageMethod -> {
                testInfoUsageMethods.add(method);
                if (!testInfoUsageMethodsTokensProcessed.containsKey(method)) {
                    testInfoUsageMethodsTokensProcessed.put(method, new ArrayList<>());
                }
                testInfoUsageMethodsTokensProcessed.get(method).add(ctx.start);
            });

        return super.visitMethodCall(ctx);
    }

    private boolean isProcessed(JavaParser.MethodDeclarationContext method, Token token) {
        return Optional.ofNullable(method)
            .map(testInfoUsageMethodsTokensProcessed::get)
            .filter(tokens -> tokens.contains(token))
            .isPresent();
    }
}
