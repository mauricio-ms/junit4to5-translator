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
import org.antlr.v4.runtime.TokenStreamRewriter;

import antlr.java.JavaParser;

class JUnit4to5TranslatorSecondPass extends BaseJUnit4To5Pass {
    private final TokenStreamRewriter rewriter;
    private final SymbolTable symbolTable;
    private final ParameterAdder parameterAdder;
    private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;
    private Scope currentScope;
    private boolean isFirstLevelMethodCall;

    JUnit4to5TranslatorSecondPass(
        BufferedTokenStream tokens,
        TokenStreamRewriter rewriter,
        SymbolTable symbolTable
    ) {
        this.rewriter = rewriter;
        this.symbolTable = symbolTable;
        parameterAdder = new ParameterAdder(rewriter, tokens);
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
                parameterAdder.addAfter(
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
        return null;
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        isFirstLevelMethodCall = isFirstLevelMethodCall(ctx);
        return super.visitExpression(ctx);
    }

    private boolean isFirstLevelMethodCall(JavaParser.ExpressionContext ctx) {
        List<JavaParser.ExpressionContext> expression = ctx.expression();
        if (!expression.isEmpty()) {
            return expression.get(0).methodCall() != null;
        } else {
            return ctx.methodCall() != null;
        }
    }

    @Override
    public Void visitCreator(JavaParser.CreatorContext ctx) {
        JavaParser.MethodDeclarationContext method = (JavaParser.MethodDeclarationContext) currentScope.get("method");
        if (method == null) {
            return super.visitCreator(ctx);
        }

        symbolTable.streamTestInfoUsageConstructors(ctx.createdName().getText())
            .filter(testInfoUsageConstructor -> {
                List<Parameter> parameters = getParameters(testInfoUsageConstructor.formalParameters());
                int callArgumentsSize = Optional.ofNullable(ctx.classCreatorRest().arguments())
                    .map(JavaParser.ArgumentsContext::expressionList)
                    .map(exprList -> exprList.expression().size())
                    .orElse(0);

                // Just checking size, the correct would be checking the types also to consider overload methods
                int parametersSize = parameters.size();
                return parametersSize == callArgumentsSize ||
                       parametersSize > 0 &&
                       parametersSize < callArgumentsSize &&
                       parameters.get(parametersSize - 1).varargs();
            })
            .findFirst()
            .ifPresent(testInfoUsageMethod -> {
                testInfoUsageMethods.add(method);
                symbolTable.setTestInfoUsageMethodAsProcessed(method);
                parameterAdder.addAfter(
                    ctx.classCreatorRest().arguments().LPAREN().getSymbol(),
                    ctx.classCreatorRest().arguments().expressionList() == null,
                    "testInfo");
            });

        return super.visitCreator(ctx);
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        JavaParser.MethodDeclarationContext method = (JavaParser.MethodDeclarationContext) currentScope.get("method");
        if (!isFirstLevelMethodCall || method == null) {
            return super.visitMethodCall(ctx);
        }

        symbolTable.streamTestInfoUsageMethods(ctx.identifier().getText())
            .filter(testInfoUsageMethod -> {
                List<Parameter> parameters = getParameters(testInfoUsageMethod.formalParameters());
                int callArgumentsSize = Optional.ofNullable(ctx.arguments())
                    .map(JavaParser.ArgumentsContext::expressionList)
                    .map(exprList -> exprList.expression().size())
                    .orElse(0);

                // Just checking size, the correct would be checking the types also to consider overload methods
                int parametersSize = parameters.size();
                return parametersSize == callArgumentsSize ||
                       parametersSize > 0 &&
                       parametersSize < callArgumentsSize &&
                       parameters.get(parametersSize - 1).varargs();
            })
            .findFirst()
            .ifPresent(testInfoUsageMethod -> {
                testInfoUsageMethods.add(method);
                symbolTable.setTestInfoUsageMethodAsProcessed(method);
                parameterAdder.addAfter(
                    ctx.arguments().LPAREN().getSymbol(),
                    ctx.arguments().expressionList() == null,
                    "testInfo");
            });

        return super.visitMethodCall(ctx);
    }

    private List<Parameter> getParameters(JavaParser.FormalParametersContext ctx) {
        if (ctx == null || ctx.formalParameterList() == null) {
            return List.of();
        }

        var formalParameters = ctx.formalParameterList();
        Stream<Parameter> formatParamtersStream = formalParameters.formalParameter().stream()
            .map(p -> new Parameter(resolveType(p.typeType()), p.variableDeclaratorId().getText()));
        Stream<Parameter> lastFormalParameterStream = Stream.of(formalParameters.lastFormalParameter())
            .filter(Objects::nonNull)
            .map(p -> new Parameter(
                resolveType(p.typeType()),
                p.variableDeclaratorId().getText(),
                p.ELLIPSIS() != null));
        return Stream.concat(formatParamtersStream, lastFormalParameterStream).toList();
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
