package com.junit4to5.translator.java;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.misc.Interval;

import antlr.java.JavaParser;

class JUnit4to5TranslatorFormattingPass extends BaseJUnit4To5Pass {
    private static final int INDENTATION_LEVEL = 4;

    private final Rewriter rewriter;
    private final HiddenTokens hiddenTokens;
    private int upperIndentationLevel;

    JUnit4to5TranslatorFormattingPass(Rewriter rewriter, HiddenTokens hiddenTokens) {
        this.rewriter = rewriter;
        this.hiddenTokens = hiddenTokens;
    }

    @Override
    public Void visitBlockStatement(JavaParser.BlockStatementContext ctx) {
        if (hiddenTokens.contains(ctx.getStart(), ctx.getStop(), "\n")) {
            return null;
        }

        if (rewriter.requiresFormatting(ctx.getSourceInterval())) {
            upperIndentationLevel = hiddenTokens.getIndentation(ctx.getStart());
            super.visitBlockStatement(ctx);
        }
        return null;
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        if (ctx.QUESTION() != null) {
            String upperIndentation = buildUpperIndentation();
            rewriter.replace(
                ctx.QUESTION().getSymbol(),
                "?%n%s".formatted(upperIndentation));
            rewriter.replace(
                ctx.COLON().getSymbol(),
                ":%n%s".formatted(upperIndentation));
            return null;
        } else if (ctx.DOT() == null) {
            return super.visitExpression(ctx);
        } else {
            rewriter.replace(ctx.DOT().getSymbol(), "%n%s.".formatted(buildUpperIndentation()));
            return null;
        }
    }

    private String buildUpperIndentation() {
        return " ".repeat(upperIndentationLevel + INDENTATION_LEVEL);
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        var arguments = ctx.arguments();
        String indentation = " ".repeat(hiddenTokens.maybeIndentation(ctx.identifier().getStart())
                                            .orElse(upperIndentationLevel) + INDENTATION_LEVEL);
        Interval argumentsInterval = new Interval(
            arguments.LPAREN().getSymbol().getTokenIndex() + 1,
            arguments.RPAREN().getSymbol().getTokenIndex() - 1);
        String formattedArguments = Stream.of(rewriter.getText(argumentsInterval).split(","))
            .map(String::trim)
            .collect(Collectors.joining(",%n%s".formatted(indentation)));
        rewriter.replace(
            arguments.LPAREN().getSymbol(),
            arguments.RPAREN().getSymbol(),
            "(%n%s%s)".formatted(indentation, formattedArguments));
        return null;
    }
}
