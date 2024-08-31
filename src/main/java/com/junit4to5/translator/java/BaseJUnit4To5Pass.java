package com.junit4to5.translator.java;

import java.util.Optional;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.TokenStreamRewriter;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

// TODO - delete if not used
abstract class BaseJUnit4To5Pass extends JavaParserBaseVisitor<Void> {

    final BufferedTokenStream tokens;
    final TokenStreamRewriter rewriter;

    BaseJUnit4To5Pass(BufferedTokenStream tokens, TokenStreamRewriter rewriter) {
        this.tokens = tokens;
        this.rewriter = rewriter;
    }

    String resolveType(JavaParser.TypeTypeContext ctx) {
        return Optional.ofNullable(ctx.classOrInterfaceType())
            .map(RuleContext::getText)
            .orElseGet(() -> ctx.primitiveType().getText());
    }
}
