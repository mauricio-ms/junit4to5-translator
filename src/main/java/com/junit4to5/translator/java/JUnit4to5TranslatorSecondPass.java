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

    private Scope currentScope;

    private final SymbolTable symbolTable;
    private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;

    JUnit4to5TranslatorSecondPass(
        BufferedTokenStream tokens,
        TokenStreamRewriter rewriter,
        SymbolTable symbolTable
    ) {
        super(tokens, rewriter);
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
                addParameterBefore(
                    formalParameters.stop,
                    formalParameters.formalParameterList() == null,
                    "TestInfo testInfo");
            });
        }
        return null;
    }

    private void addParameterBefore(Token token, boolean unique, String parameter) {
        maybePreviousTokenAs(token, "\n")
            .ifPresentOrElse(
                nlToken -> rewriter.insertBefore(
                    nlToken,
                    generateNewParameter(unique, "\n%8s".formatted(""), parameter)),
                () -> rewriter.insertBefore(
                    token,
                    generateNewParameter(unique, "", parameter)));
    }

    // TODO - use tokenType instead text, add constants to Java Lexer
    private Optional<Token> maybePreviousTokenAs(Token token, String previousToken) {
        List<Token> hiddenTokensToLeft = tokens.getHiddenTokensToLeft(
            token.getTokenIndex(), JavaLexer.HIDDEN);
        if (hiddenTokensToLeft != null && !hiddenTokensToLeft.isEmpty()) {
            Token hiddenToken = hiddenTokensToLeft.get(0);
            if (hiddenToken.getText().startsWith(previousToken)) { // TODO - FIX STARTSWITH
                return Optional.of(hiddenToken);
            }
        }
        return Optional.empty();
    }

    private String generateNewParameter(boolean unique, String prefix, String parameter) {
        if (unique) {
            return prefix + parameter;
        } else {
            return ", " + prefix + parameter;
        }
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        if (!symbolTable.isTestInfoUsageMethodProcessed(ctx)) {
            currentScope = new NestedScope(currentScope);
            currentScope.declare("method", ctx);
            super.visitMethodDeclaration(ctx);
            currentScope = currentScope.enclosing();
        }
        return null;
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        JavaParser.MethodDeclarationContext method = (JavaParser.MethodDeclarationContext) currentScope.get("method");
        if (method == null) {
            return null;
        }
        Optional.ofNullable(ctx.identifier())
            .flatMap(identifier -> symbolTable.maybeTestInfoUsageMethod(identifier.getText()))
            .ifPresent(testInfoUsageMethod -> {
                // TODO - if not junit annotated, it means nested methods, so throw unsupported exception
                // TODO - Check if call and declaration matches
                List<Parameter> helperMethodParameters = getHelperMethodParameters(testInfoUsageMethod);
                int callArgumentsSize = Optional.ofNullable(ctx.arguments())
                    .map(JavaParser.ArgumentsContext::expressionList)
                    .map(exprList -> exprList.expression().size())
                    .orElse(0);
                //                List<String> methodCallArgumentTypes = getMethodCallArgumentTypes(ctx);
                //                System.out.println(methodCallArgumentTypes);

                // Just checking size, the correct would be checking the types also to consider overload methods
                if (helperMethodParameters.size() == callArgumentsSize) {
                    testInfoUsageMethods.add(method);
                    symbolTable.setTestInfoUsageMethodAsProcessed(method);
                    addParameterBefore(
                        ctx.arguments().stop,
                        ctx.arguments().expressionList() == null,
                        "testInfo");
                }
            });

        return super.visitMethodCall(ctx);
    }

    // TODO - maybe remove all of this
    private List<Parameter> getHelperMethodParameters(JavaParser.MethodDeclarationContext ctx) {
        if (ctx.formalParameters() == null || ctx.formalParameters().formalParameterList() == null) {
            return List.of();
        }

        var formalParameters = ctx.formalParameters().formalParameterList();
        Stream<Parameter> formatParamtersStream = formalParameters.formalParameter().stream()
            .map(p -> new Parameter(resolveType(p.typeType()), p.variableDeclaratorId().getText()));
        Stream<Parameter> lastFormalParameterStream = Stream.of(formalParameters.lastFormalParameter())
            .filter(Objects::nonNull)
            .map(p -> new Parameter(resolveType(p.typeType()), p.variableDeclaratorId().getText()));
        return Stream.concat(formatParamtersStream, lastFormalParameterStream)
            .toList();
    }

    private List<String> getMethodCallArgumentTypes(JavaParser.MethodCallContext ctx) {
        if (ctx.arguments() == null || ctx.arguments().expressionList() == null) {
            return List.of();
        }

        ExpressionResolver expressionResolver = new ExpressionResolver();
        return ctx.arguments().expressionList().expression().stream()
            .map(expressionResolver::visit)
            .toList();
    }

    record Parameter(String type, String identifier) {
    }

    public void saveOutput(Path outputPath) throws IOException {
        Files.writeString(outputPath, rewriter.getText(), StandardOpenOption.TRUNCATE_EXISTING);
    }
}
