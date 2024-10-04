package com.junit4to5.translator.java;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;

import antlr.java.JavaParser;

class JUnit4to5TranslatorFormattingPass extends BaseJUnit4To5Pass {
    private static final int INDENTATION_LEVEL = 4;

    private final Rewriter rewriter;
    private final HiddenTokens hiddenTokens;

    private JavaParser.ImportDeclarationContext firstImport;
    private JavaParser.ImportDeclarationContext lastImport;
    private JavaParser.ModifierContext methodDeclarationStartIndex;
    private int upperIndentationLevel;
    private boolean regionRequiresFormatting;

    JUnit4to5TranslatorFormattingPass(BufferedTokenStream tokens, Rewriter rewriter) {
        this.rewriter = rewriter;
        hiddenTokens = new HiddenTokens(tokens);
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        super.visitCompilationUnit(ctx);

        Interval importsInterval = new Interval(
            firstImport.getSourceInterval().a,
            Optional.ofNullable(lastImport)
                .map(i -> i.getSourceInterval().b)
                .orElseGet(() -> firstImport.getSourceInterval().b) + 1);
        String importsRegion = rewriter.getText(importsInterval);
        StringBuilder formattedImportsRegionSb = new StringBuilder();
        int breakLines = 0;
        for (String importDeclaration : importsRegion.split("\n")) {
            boolean isBreakLine = importDeclaration.isEmpty();
            if (isBreakLine) {
                breakLines++;
            } else {
                breakLines = 0;
            }
            if (breakLines < 2) {
                formattedImportsRegionSb.append(importDeclaration).append('\n');
            }
        }
        Token endTokenImportsRegion = Optional.ofNullable(lastImport)
            .map(ParserRuleContext::getStop)
            .orElseGet(() -> firstImport.getStop());
        rewriter.replace(
            firstImport.getStart(),
            endTokenImportsRegion,
            formattedImportsRegionSb.toString());
        hiddenTokens.maybeNextAs(endTokenImportsRegion, "\n\n")
            .ifPresent(rewriter::delete);
        return null;
    }

    @Override
    public Void visitImportDeclaration(JavaParser.ImportDeclarationContext ctx) {
        if (firstImport == null) {
            firstImport = ctx;
        } else {
            lastImport = ctx;
        }
        return super.visitImportDeclaration(ctx);
    }

    @Override
    public Void visitClassBodyDeclaration(JavaParser.ClassBodyDeclarationContext ctx) {
        var modifiers = ctx.modifier();
        if (!modifiers.isEmpty()) {
            methodDeclarationStartIndex = modifiers.get(0);
        }
        super.visitClassBodyDeclaration(ctx);
        methodDeclarationStartIndex = null;
        return null;
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        ParserRuleContext startDeclaration = Optional.ofNullable((ParserRuleContext) methodDeclarationStartIndex)
            .orElseGet(ctx::typeTypeOrVoid);
        Interval declarationInterval = new Interval(
            startDeclaration.getSourceInterval().a,
            Optional.ofNullable(ctx.methodBody().block())
                .map(b -> b.LBRACE().getSourceInterval().b)
                .orElseGet(() -> ctx.methodBody().SEMI().getSourceInterval().b));
        if (rewriter.requiresFormatting(declarationInterval)) {
            String indentation = " ".repeat(
                hiddenTokens.getIndentation(startDeclaration.getStart()) + INDENTATION_LEVEL);
            String formattedParameters = Stream.of(
                    Optional.of(rewriter.getText(ctx.formalParameters().getSourceInterval()))
                        .map(s -> s.substring(1, s.length() - 1).split(","))
                        .get())
                .map(String::trim)
                .collect(Collectors.joining(",%n%s".formatted(indentation)));
            rewriter.replace(ctx.formalParameters().getStart(), ctx.formalParameters().getStop(),
                "(%n%s%s%n%s)".formatted(indentation, formattedParameters, " ".repeat(INDENTATION_LEVEL)));
        }

        return super.visitMethodDeclaration(ctx);
    }

    @Override
    public Void visitBlockStatement(JavaParser.BlockStatementContext ctx) {
        Interval sourceInterval = ctx.getSourceInterval();
        Interval interval = new Interval(
            sourceInterval.a,
            sourceInterval.b + hiddenTokens.getHiddenTextUntilNewLine(sourceInterval.b).length());
        if (rewriter.requiresFormatting(interval)) {
            upperIndentationLevel = hiddenTokens.getIndentation(ctx.getStart());
            regionRequiresFormatting = true;
            super.visitBlockStatement(ctx);
            regionRequiresFormatting = false;
        }
        return null;
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        if (!regionRequiresFormatting) {
            return null;
        } else if (ctx.QUESTION() != null) {
            String upperIndentation = buildUpperIndentation();
            rewriter.replace(
                ctx.QUESTION().getSymbol(),
                "?%n%s".formatted(upperIndentation));
            rewriter.replace(
                ctx.COLON().getSymbol(),
                ":%n%s".formatted(upperIndentation));
            return null;
        } else {
            super.visitExpression(ctx);
            if (regionRequiresFormatting && ctx.DOT() != null) {
                rewriter.replace(ctx.DOT().getSymbol(), "%n%s.".formatted(buildUpperIndentation()));
            }
            return null;
        }
    }

    private String buildUpperIndentation() {
        return " ".repeat(upperIndentationLevel + INDENTATION_LEVEL);
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        var arguments = ctx.arguments();
        if (arguments.expressionList() == null) {
            return null;
        }
        int indentationAdded = rewriter.getText(ctx.identifier().getSourceInterval()).split(" ").length - 1;
        String indentation = " ".repeat(hiddenTokens.maybeIndentation(ctx.identifier().getStart())
                                            .orElse(upperIndentationLevel) + indentationAdded + INDENTATION_LEVEL);
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
        regionRequiresFormatting = false;
        return null;
    }
}
