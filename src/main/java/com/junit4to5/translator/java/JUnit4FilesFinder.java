package com.junit4to5.translator.java;

import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

class JUnit4FilesFinder extends JavaParserBaseVisitor<Void> {
    private static final List<String> JUNIT4_IMPORTS = List.of(
        "org.junit.Test",
        "org.junit.Before",
        "org.junit.BeforeClass",
        "org.junit.After",
        "org.junit.AfterClass",
        "org.junit.runner.RunWith");

    private boolean isJUnit4File;
    private boolean isJUnit4TestRule;

    // TODO consider wild card imports
    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        isJUnit4File = ctx.importDeclaration().stream()
            .map(i -> i.qualifiedName().getText())
            .anyMatch(JUNIT4_IMPORTS::contains);
        super.visitCompilationUnit(ctx);
        return null;
    }

    @Override
    public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        if (ctx.IMPLEMENTS() != null) {
            isJUnit4TestRule = ctx.typeList().stream()
                .flatMap(t -> t.typeType().stream())
                .anyMatch(t -> Optional.ofNullable(t.classOrInterfaceType())
                    .map(RuleContext::getText)
                    .filter("TestRule"::equals)
                    .isPresent());
        }
        return null;
    }

    public boolean isJUnit4File() {
        return isJUnit4File;
    }

    public boolean isJUnit4TestRule() {
        return isJUnit4TestRule;
    }
}
