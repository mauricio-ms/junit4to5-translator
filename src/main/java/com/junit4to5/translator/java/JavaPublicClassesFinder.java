package com.junit4to5.translator.java;

import java.util.Optional;

import antlr.java.JavaParser;

class JavaPublicClassesFinder extends BaseJUnit4To5Pass {
    private final CrossReferences crossReferences;
    private Scope currentScope;
    private String packageDeclaration;
    private String fullyQualifiedName;

    public JavaPublicClassesFinder(CrossReferences crossReferences) {
        this.crossReferences = crossReferences;
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        return super.visitCompilationUnit(ctx);
    }

    @Override
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        return null;
    }

    @Override
    public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        boolean isAtMainClassScope = currentScope.depth() == 2;
        if (isAtMainClassScope) {
            return null;
        }
        currentScope = new NestedScope(currentScope);
        fullyQualifiedName = "%s.%s".formatted(packageDeclaration, ctx.identifier().getText());
        crossReferences.addType(fullyQualifiedName);
        super.visitClassDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitClassBodyDeclaration(JavaParser.ClassBodyDeclarationContext ctx) {
        Optional.ofNullable(ctx.memberDeclaration())
            .ifPresent(memberDeclaration -> {
                boolean isAtMainClassScope = currentScope.depth() == 2;
                if (isAtMainClassScope && memberDeclaration.methodDeclaration() != null) {
                    maybePublicToken(ctx.modifier().stream().map(JavaParser.ModifierContext::classOrInterfaceModifier))
                        .ifPresent(__ -> crossReferences.addType(
                            "%s.%s".formatted(
                                fullyQualifiedName,
                                memberDeclaration.methodDeclaration().identifier().getText())));
                }
            });
        return null;
    }
}
