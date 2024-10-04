package com.junit4to5.translator.java;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.RuleContext;

import antlr.java.JavaParser;

class JavaMetadataCollector extends BaseJUnit4To5Pass {
    private final MetadataTable metadataTable;
    private final CrossReferences crossReferences;
    private final MetadataTable.Metadata.MetadataBuilder metadataBuilder;

    private Scope currentScope;
    private String packageDeclaration;
    private String fullyQualifiedName;
    private PackageResolver packageResolver;
    private boolean addTestInfoArgumentToMethod;

    public JavaMetadataCollector(MetadataTable metadataTable, CrossReferences crossReferences) {
        this.metadataTable = metadataTable;
        this.crossReferences = crossReferences;
        metadataBuilder = new MetadataTable.Metadata.MetadataBuilder();
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        super.visitCompilationUnit(ctx);
        if (fullyQualifiedName != null) {
            metadataTable.put(fullyQualifiedName, metadataBuilder.build());
        }
        return null;
    }

    @Override
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        metadataBuilder.setPackageDeclaration(packageDeclaration);
        return null;
    }

    @Override
    public Void visitImportDeclaration(JavaParser.ImportDeclarationContext ctx) {
        String importDeclaration = ctx.qualifiedName().getText();
        if (ctx.MUL() != null) {
            importDeclaration += ".*";
        } else {
            incrementCrossReferenceTypeIfPresent(importDeclaration);
        }
        metadataBuilder.addImportDeclaration(importDeclaration);
        return super.visitImportDeclaration(ctx);
    }

    private void incrementCrossReferenceTypeIfPresent(String importDeclaration) {
        if (crossReferences.hasType(importDeclaration)) {
            crossReferences.incrementType(importDeclaration);
        } else {
            // for import static cases
            int lastDotIndexOf = importDeclaration.lastIndexOf('.');
            String staticImportType = importDeclaration.substring(0, lastDotIndexOf);
            if (crossReferences.hasType(staticImportType)) {
                crossReferences.incrementType(staticImportType);
                crossReferences.incrementMethods(staticImportType, importDeclaration.substring(lastDotIndexOf+1));
            }
        }
    }

    @Override
    public Void visitInterfaceDeclaration(JavaParser.InterfaceDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitInterfaceDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitEnumDeclaration(JavaParser.EnumDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitEnumDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitRecordDeclaration(JavaParser.RecordDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitRecordDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope, CLASS_SCOPE);
        if (fullyQualifiedName == null) {
            fullyQualifiedName = "%s.%s".formatted(packageDeclaration, ctx.identifier().getText());
            if (ctx.EXTENDS() != null) {
                metadataBuilder.setExtendsIdentifier(TypeResolver.resolve(ctx.typeType()));
            }
            packageResolver = getPackageResolverSupplier().get();
        }
        super.visitClassDeclaration(ctx);
        boolean isAtMainClassScope = currentScope.depth() == 2;
        if (isAtMainClassScope) {
            metadataBuilder.setInstanceVariables(currentScope.getSymbols());
        }
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitClassBodyDeclaration(JavaParser.ClassBodyDeclarationContext ctx) {
        declareInstanceVariables(ctx, currentScope);
        Optional.ofNullable(ctx.memberDeclaration())
            .map(JavaParser.MemberDeclarationContext::fieldDeclaration)
            .ifPresent(fieldDeclaration -> {
                if (isRule(ctx)) {
                    String ruleType = TypeResolver.resolve(fieldDeclaration.typeType());
                    fieldDeclaration.variableDeclarators().variableDeclarator()
                        .forEach(v -> metadataBuilder.addRuleInstanceVariable(
                            ruleType, v.variableDeclaratorId().getText()));
                }
            });
        return super.visitClassBodyDeclaration(ctx);
    }

    @Override
    public Void visitConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitConstructorDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitCreator(JavaParser.CreatorContext ctx) {
        Optional<JavaParser.ClassBodyContext> maybeAnonymousClassCreator = Optional.ofNullable(ctx.classCreatorRest())
            .map(JavaParser.ClassCreatorRestContext::classBody);
        maybeAnonymousClassCreator.ifPresent(__ -> currentScope = new NestedScope(currentScope));
        super.visitCreator(ctx);
        maybeAnonymousClassCreator.ifPresent(__ -> currentScope = currentScope.enclosing());
        return null;
    }

    @Override
    public Void visitLambdaExpression(JavaParser.LambdaExpressionContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitLambdaExpression(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitLambdaParameters(JavaParser.LambdaParametersContext ctx) {
        ctx.identifier()
            .forEach(identifier -> currentScope.declare(identifier.getText(), "notInferredLambdaParameter"));
        return super.visitLambdaParameters(ctx);
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        if (ctx.DOT() != null && ctx.methodCall() != null) {
            getPackageResolver()
                .resolveType(ctx.expression(0).getText())
                .ifPresent(type -> {
                    crossReferences.incrementType(type);
                    Optional.ofNullable(ctx.methodCall().identifier())
                        .map(RuleContext::getText)
                        .ifPresent(methodCall -> {
                            int argumentsSize = ArgumentsResolver.resolveSize(ctx.methodCall().arguments());
                            if (crossReferences.hasMethod(type, methodCall, argumentsSize)) {
                                crossReferences.incrementMethod(type, methodCall, argumentsSize);
                            }
                        });
                });
        }
        return super.visitExpression(ctx);
    }

    @Override
    public Void visitClassOrInterfaceType(JavaParser.ClassOrInterfaceTypeContext ctx) {
        String type = ctx.identifier().stream().map(RuleContext::getText)
            .collect(Collectors.joining("."));
        // TODO - needed to track cross references to methods imported via static import
        // ctx.typeIdentifier().getText()
        getPackageResolver()
            .resolveType(type)
            .ifPresent(crossReferences::incrementType);
        return super.visitClassOrInterfaceType(ctx);
    }

    private PackageResolver getPackageResolver() {
        return Optional.ofNullable(packageResolver)
            .orElseGet(getPackageResolverSupplier());
    }

    private Supplier<PackageResolver> getPackageResolverSupplier() {
        return () -> new PackageResolver(
            packageDeclaration,
            metadataBuilder.getImportDeclarations(),
            crossReferences);
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitMethodDeclaration(ctx);
        currentScope = currentScope.enclosing();
        if (addTestInfoArgumentToMethod) {
            metadataBuilder.addTestInfoUsageMethod(ctx);
            addTestInfoArgumentToMethod = false;
        }
        return null;
    }

    @Override
    public Void visitFormalParameter(JavaParser.FormalParameterContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), TypeResolver.resolve(ctx.typeType()));
        return super.visitFormalParameter(ctx);
    }

    @Override
    public Void visitLastFormalParameter(JavaParser.LastFormalParameterContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), TypeResolver.resolve(ctx.typeType()));
        return super.visitLastFormalParameter(ctx);
    }

    @Override
    public Void visitBlock(JavaParser.BlockContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitBlock(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitStatement(JavaParser.StatementContext ctx) {
        boolean shouldCreateNestedScope = Stream.of(ctx.FOR())
            .anyMatch(Objects::nonNull);
        if (shouldCreateNestedScope) {
            currentScope = new NestedScope(currentScope);
        }
        super.visitStatement(ctx);
        if (shouldCreateNestedScope) {
            currentScope = currentScope.enclosing();
        }
        return null;
    }

    @Override
    public Void visitEnhancedForControl(JavaParser.EnhancedForControlContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), TypeResolver.resolve(ctx.typeType()));
        return super.visitEnhancedForControl(ctx);
    }

    @Override
    public Void visitLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {
        Optional.ofNullable(ctx.VAR())
            .ifPresentOrElse(
                v -> currentScope.declare(ctx.identifier().getText(), v.getText()),
                () -> {
                    String type = TypeResolver.resolve(ctx.typeType());
                    for (var varDeclarator : ctx.variableDeclarators().variableDeclarator()) {
                        currentScope.declare(varDeclarator.variableDeclaratorId().getText(), type);
                    }
                });
        return super.visitLocalVariableDeclaration(ctx);
    }

    @Override
    public Void visitPrimary(JavaParser.PrimaryContext ctx) {
        Optional.ofNullable(ctx.identifier())
            .ifPresent(id -> {
                if (TEST_NAME_RULE.equals(currentScope.resolve(id.getText()))) {
                    Scope classScope = currentScope.enclosingFor(CLASS_SCOPE);
                    boolean isAtMainClassScope = classScope.depth() == 2;
                    if (isAtMainClassScope) {
                        addTestInfoArgumentToMethod = true;
                    }
                }
            });

        return super.visitPrimary(ctx);
    }
}
