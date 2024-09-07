package com.junit4to5.translator.java;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

class JavaPublicClassesFinder extends JavaParserBaseVisitor<Void> {

    private final CrossReferences crossReferences;
    private String packageDeclaration;

    public JavaPublicClassesFinder(CrossReferences crossReferences) {
        this.crossReferences = crossReferences;
    }

    @Override
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        return null;
    }

    @Override
    public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        crossReferences.addType(
            "%s.%s".formatted(packageDeclaration, ctx.identifier().getText()));
        return null;
    }
}
