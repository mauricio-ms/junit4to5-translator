package com.junit4to5.translator.java;

import java.util.List;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

class HelperTranslator extends JavaParserBaseVisitor<Void> {
    private static final List<String> IMPORTS_FOR_REMOVAL = List.of(
        "com.tngtech.java.junit.dataprovider.DataProvider");

    private final Rewriter rewriter;

    HelperTranslator(Rewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public Void visitImportDeclaration(JavaParser.ImportDeclarationContext ctx) {
        boolean wildcardImport = ctx.DOT() != null && ctx.MUL() != null;
        String importName = wildcardImport ?
            ctx.qualifiedName().getText() + ".*" :
            ctx.qualifiedName().getText();
        if (IMPORTS_FOR_REMOVAL.contains(importName)) {
            rewriter.delete(ctx.start, ctx.stop);
            rewriter.deleteNextIf(ctx.stop, "\n", hiddenToken -> hiddenToken.substring(1));
        }
        return super.visitImportDeclaration(ctx);
    }

    @Override
    public Void visitAnnotation(JavaParser.AnnotationContext ctx) {
        String annotationName = ctx.qualifiedName().getText();
        if ("DataProvider".equals(annotationName)) {
            rewriter.replace(ctx.start, ctx.stop, "");
            rewriter.deleteNextIf(ctx.stop, "\n");
        }
        return super.visitAnnotation(ctx);
    }
}
