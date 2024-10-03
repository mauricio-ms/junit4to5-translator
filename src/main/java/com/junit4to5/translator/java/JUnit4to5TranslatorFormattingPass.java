package com.junit4to5.translator.java;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;

import antlr.java.JavaParser;

class JUnit4to5TranslatorFormattingPass extends BaseJUnit4To5Pass {
    private static final int INDENTATION_LEVEL = 4;

    private final Rewriter rewriter;
    private final HiddenTokens hiddenTokens;
    private final MetadataTable metadataTable;
    
    private String packageDeclaration;
    private String fullyQualifiedName;
    private JavaParser.ModifierContext methodDeclarationStartIndex;
    private int upperIndentationLevel;
    private boolean regionRequiresFormatting;

    JUnit4to5TranslatorFormattingPass(
        Rewriter rewriter,
        HiddenTokens hiddenTokens,
        MetadataTable metadataTable
    ) {
        this.rewriter = rewriter;
        this.hiddenTokens = hiddenTokens;
        this.metadataTable = metadataTable;
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        super.visitCompilationUnit(ctx);
        MetadataTable.Metadata metadata = metadataTable.get(fullyQualifiedName);
        Map<String, List<String>> importsPerPrefix = buildImportsPerPrefix(metadata.getAddedImports());
        importsPerPrefix.forEach((prefix, imports) ->
            insertImportsDeclarations(
                ctx,
                imports,
                d -> d.qualifiedName().getText().startsWith(prefix),
                "import %s;"::formatted));

        Map<String, List<String>> staticImportsPerPrefix = buildImportsPerPrefix(metadata.getStaticAddedImports());
        staticImportsPerPrefix.forEach((prefix, imports) ->
            insertImportsDeclarations(
                ctx,
                imports,
                d -> d.STATIC() != null && d.qualifiedName().getText().startsWith(prefix),
                "import static %s;"::formatted));

        hiddenTokens.maybePreviousAs(ctx.typeDeclaration(0).getStart(), "\n")
            .ifPresent(beforeType -> rewriter.insertAfter(beforeType, "\n"));
        return null;
    }

    private Map<String, List<String>> buildImportsPerPrefix(Set<String> imports) {
        return imports.stream()
            .collect(Collectors.groupingBy(name -> {
                String[] parts = name.split("\\.");
                StringBuilder prefixSb = new StringBuilder();
                for (int i = 0; i < 2; i++) {
                    prefixSb.append(parts[i]).append(".");
                }
                return prefixSb.toString();
            }));
    }

    private void insertImportsDeclarations(
        JavaParser.CompilationUnitContext ctx,
        List<String> imports,
        Predicate<JavaParser.ImportDeclarationContext> prefixPredicate,
        Function<String, String> importDeclarationFn
    ) {
        var importDeclarations = ctx.importDeclaration();
        var prefixOccurrences = importDeclarations.stream()
            .filter(prefixPredicate)
            .toList();
        var firstOccurrence = prefixOccurrences.isEmpty() ?
            ctx.importDeclaration(0) :
            prefixOccurrences.get(0);
        String addedImportDeclarations = imports.stream()
            .map(importDeclarationFn)
            .collect(Collectors.joining(System.lineSeparator()));
        
        StringBuilder insertionSb = new StringBuilder();
        if (prefixOccurrences.isEmpty()) {
            insertionSb.append(System.lineSeparator());
        }
//        if (hiddenTokens.hasNotNextAs(firstOccurrence.stop, "\n\n")) { TODO - check
//            insertionSb.append(System.lineSeparator());
//        }
        insertionSb
            .append(System.lineSeparator())
            .append(addedImportDeclarations)
            .append(System.lineSeparator());
        
        rewriter.insertAfter(firstOccurrence.stop, insertionSb.toString());
    }

    @Override
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        return null;
    }

    @Override
    public Void visitTypeDeclaration(JavaParser.TypeDeclarationContext ctx) {
        var classDeclaration = ctx.classDeclaration();
        if (classDeclaration != null) {
            fullyQualifiedName = "%s.%s".formatted(
                packageDeclaration, classDeclaration.identifier().getText());
        }
        return super.visitTypeDeclaration(ctx);
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
