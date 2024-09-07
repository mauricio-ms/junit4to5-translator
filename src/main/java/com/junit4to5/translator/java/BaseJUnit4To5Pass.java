package com.junit4to5.translator.java;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.TerminalNode;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

abstract class BaseJUnit4To5Pass extends JavaParserBaseVisitor<Void> {
    String resolveType(JavaParser.TypeTypeContext ctx) {
        return Optional.ofNullable(ctx.classOrInterfaceType())
            .map(RuleContext::getText)
            .orElseGet(() -> ctx.primitiveType().getText());
    }

    Optional<Token> maybePublicToken(Stream<JavaParser.ClassOrInterfaceModifierContext> modifiersStream) {
        return modifiersStream
            .map(JavaParser.ClassOrInterfaceModifierContext::PUBLIC)
            .filter(Objects::nonNull)
            .map(TerminalNode::getSymbol)
            .findFirst();
    }
}
