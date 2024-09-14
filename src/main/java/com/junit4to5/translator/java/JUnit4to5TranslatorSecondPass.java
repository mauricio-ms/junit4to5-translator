package com.junit4to5.translator.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;

import antlr.java.JavaLexer;
import antlr.java.JavaParser;

class JUnit4to5TranslatorSecondPass extends BaseJUnit4To5Pass {
    private final BufferedTokenStream tokens;
    private final TokenStreamRewriter rewriter;
    private final SymbolTable symbolTable;
    private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;
    private Scope currentScope;

    JUnit4to5TranslatorSecondPass(
        BufferedTokenStream tokens,
        TokenStreamRewriter rewriter,
        SymbolTable symbolTable
    ) {
        this.tokens = tokens;
        this.rewriter = rewriter;
        this.symbolTable = symbolTable;
        testInfoUsageMethods = new HashSet<>();
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        super.visitCompilationUnit(ctx);
        if (!testInfoUsageMethods.isEmpty()) {
            testInfoUsageMethods.forEach(symbolTable::addTestInfoUsageMethod);
            testInfoUsageMethods.clear();
            visitCompilationUnit(ctx);
        } else {
            symbolTable.getTestInfoUsageMethods().forEach(method -> {
                var formalParameters = method.formalParameters();
                addParameterAfter(
                    formalParameters.LPAREN().getSymbol(),
                    formalParameters.formalParameterList() == null,
                    "TestInfo testInfo");
            });
        }
        return null;
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        if (!symbolTable.isTestInfoUsageMethodProcessed(ctx)) {
            currentScope = new NestedScope(currentScope);
            currentScope.declare("method", ctx);
            super.visitMethodDeclaration(ctx);
            currentScope = currentScope.enclosing();
        }
        return super.visitMethodDeclaration(ctx);
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        JavaParser.MethodDeclarationContext method = (JavaParser.MethodDeclarationContext) currentScope.get("method");
        if (method == null) {
            return null;
        }
        symbolTable.streamTestInfoUsageMethods(ctx.identifier().getText())
            .filter(testInfoUsageMethod -> {
                List<Parameter> helperMethodParameters = getHelperMethodParameters(testInfoUsageMethod);
                int callArgumentsSize = Optional.ofNullable(ctx.arguments())
                    .map(JavaParser.ArgumentsContext::expressionList)
                    .map(exprList -> exprList.expression().size())
                    .orElse(0);

                // Just checking size, the correct would be checking the types also to consider overload methods
                int helperMethodParametersSize = helperMethodParameters.size();

                return helperMethodParametersSize == callArgumentsSize ||
                       helperMethodParametersSize > 0 &&
                       helperMethodParametersSize < callArgumentsSize &&
                       helperMethodParameters.get(helperMethodParametersSize - 1).varargs();
            })
            .findFirst()
            .ifPresent(testInfoUsageMethod -> {
                testInfoUsageMethods.add(method);
                symbolTable.setTestInfoUsageMethodAsProcessed(method);
                addParameterAfter(
                    ctx.arguments().LPAREN().getSymbol(),
                    ctx.arguments().expressionList() == null,
                    "testInfo");
            });

        return null;
    }

    private List<Parameter> getHelperMethodParameters(JavaParser.MethodDeclarationContext ctx) {
        if (ctx.formalParameters() == null || ctx.formalParameters().formalParameterList() == null) {
            return List.of();
        }

        var formalParameters = ctx.formalParameters().formalParameterList();
        Stream<Parameter> formatParamtersStream = formalParameters.formalParameter().stream()
            .map(p -> new Parameter(resolveType(p.typeType()), p.variableDeclaratorId().getText()));
        Stream<Parameter> lastFormalParameterStream = Stream.of(formalParameters.lastFormalParameter())
            .filter(Objects::nonNull)
            .map(p -> new Parameter(
                resolveType(p.typeType()),
                p.variableDeclaratorId().getText(),
                p.ELLIPSIS() != null));
        return Stream.concat(formatParamtersStream, lastFormalParameterStream)
            .toList();
    }

    private void addParameterAfter(Token token, boolean unique, String parameter) {
        maybeNewLineNext(token)
            .ifPresentOrElse(
                nlToken -> rewriter.insertAfter(
                    nlToken,
                    "%s%n%s".formatted(
                        generateNewParameter(unique, parameter),
                        nlToken.getText().substring(1))),
                () -> rewriter.insertAfter(
                    token,
                    generateNewParameter(unique, parameter)));
    }

    private Optional<Token> maybeNewLineNext(Token token) {
        List<Token> hiddenTokensToRight = tokens.getHiddenTokensToRight(
            token.getTokenIndex(), JavaLexer.HIDDEN);
        if (hiddenTokensToRight != null && !hiddenTokensToRight.isEmpty()) {
            Token hiddenToken = hiddenTokensToRight.get(0);
            if (hiddenToken.getText().startsWith("\n")) {
                return Optional.of(hiddenToken);
            }
        }
        return Optional.empty();
    }

    private String generateNewParameter(boolean unique, String parameter) {
        if (unique) {
            return parameter;
        } else {
            return parameter + ", ";
        }
    }

    // TODO - maybe remove
    private List<String> getMethodCallArgumentTypes(JavaParser.MethodCallContext ctx) {
        if (ctx.arguments() == null || ctx.arguments().expressionList() == null) {
            return List.of();
        }

        ExpressionResolver expressionResolver = new ExpressionResolver();
        return ctx.arguments().expressionList().expression().stream()
            .map(expressionResolver::visit)
            .toList();
    }

    private record Parameter(String type, String identifier, boolean varargs) {
        private Parameter(String type, String identifier) {
            this(type, identifier, false);
        }
    }

    public void saveOutput(Path outputPath) throws IOException {
        Files.writeString(
            outputPath,
            rewriter.getText(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
    }
}
