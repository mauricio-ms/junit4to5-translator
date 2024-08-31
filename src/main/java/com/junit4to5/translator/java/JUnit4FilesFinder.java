package com.junit4to5.translator.java;

import java.util.List;

import org.antlr.v4.runtime.tree.ParseTree;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

class JUnit4FilesFinder extends JavaParserBaseVisitor<Boolean> {
    private static final List<String> JUNIT4_IMPORTS = List.of(
        "org.junit.Test",
        "org.junit.Before",
        "org.junit.BeforeClass",
        "org.junit.After",
        "org.junit.AfterClass",
        "org.junit.runner.RunWith");

    private boolean isJUnit4File;

    @Override
    public Boolean visit(ParseTree tree) {
        super.visit(tree);
        return isJUnit4File;
    }

    // TODO consider wild card imports
    @Override
    public Boolean visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        isJUnit4File = ctx.importDeclaration().stream()
            .map(i -> i.qualifiedName().getText())
            .anyMatch(JUNIT4_IMPORTS::contains);
        return isJUnit4File;
    }
}
