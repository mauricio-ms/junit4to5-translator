package com.junit4to5.translator.java;

import java.util.Optional;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

class ExpressionResolver extends JavaParserBaseVisitor<String> {

    @Override
    public String visitLiteral(JavaParser.LiteralContext ctx) {
        if (ctx.integerLiteral() != null) {
            return "int";
        } else if (ctx.floatLiteral() != null) {
            return "float";
        } else if (ctx.CHAR_LITERAL() != null) {
            return "char";
        } else if (ctx.STRING_LITERAL() != null || ctx.TEXT_BLOCK() != null) {
            return "String";
        } else if (ctx.BOOL_LITERAL() != null) {
            return "boolean";
        } else if (ctx.NULL_LITERAL() != null) {
            return "null";
        } else if (ctx.CHAR_LITERAL() != null) {
            return "char";
        } else {
            throw new IllegalStateException("Unexpected literal: " + ctx.getText());
        }
    }

    @Override
    public String visitIdentifier(JavaParser.IdentifierContext ctx) {
        System.out.println(ctx.getText());
        return "alo";
    }
}
