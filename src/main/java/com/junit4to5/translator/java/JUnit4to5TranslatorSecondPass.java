package com.junit4to5.translator.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;

import antlr.java.JavaParser;

class JUnit4to5TranslatorSecondPass extends BaseJUnit4To5Pass {
    private static final List<String> INSTANCE_ACCESSOR = List.of("super", "this");

    private final TokenStreamRewriter rewriter;
    private final MetadataTable metadataTable;
    private final ParameterAdder parameterAdder;
    private final Set<JavaParser.MethodDeclarationContext> testInfoUsageMethods;
    private final Map<JavaParser.MethodDeclarationContext, List<Token>> testInfoUsageMethodsTokensProcessed;

    private Scope currentScope;
    private String packageDeclaration;
    private String fullyQualifiedName;
    private boolean isFirstLevelMethodCall;

    JUnit4to5TranslatorSecondPass(
        BufferedTokenStream tokens,
        TokenStreamRewriter rewriter,
        MetadataTable metadataTable
    ) {
        this.rewriter = rewriter;
        this.metadataTable = metadataTable;
        parameterAdder = new ParameterAdder(rewriter, tokens);
        testInfoUsageMethods = new HashSet<>();
        testInfoUsageMethodsTokensProcessed = new HashMap<>();
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        super.visitCompilationUnit(ctx);
        if (fullyQualifiedName == null) {
            return null;
        }

        if (!testInfoUsageMethods.isEmpty()) {
            MetadataTable.Metadata metadata = metadataTable.get(fullyQualifiedName);
            testInfoUsageMethods.forEach(metadata::addTestInfoUsageMethod);
            testInfoUsageMethods.clear();
            visitCompilationUnit(ctx);
        } else {
            metadataTable.get(fullyQualifiedName).getTestInfoUsageMethods().forEach(method -> {
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
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        return null;
    }

    @Override
    public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        if (fullyQualifiedName == null) {
            fullyQualifiedName = "%s.%s".formatted(packageDeclaration, ctx.identifier().getText());
        }
        super.visitClassDeclaration(ctx);
        return null;
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        currentScope.declare("method", ctx);
        super.visitMethodDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        isFirstLevelMethodCall = isFirstLevelMethodCall(ctx);
        return super.visitExpression(ctx);
    }

    private boolean isFirstLevelMethodCall(JavaParser.ExpressionContext ctx) {
        if (INSTANCE_ACCESSOR.contains(ctx.getText())) {
            return true;
        }

        List<JavaParser.ExpressionContext> expression = ctx.expression();
        if (ctx.DOT() != null) {
            var left = expression.get(0);
            var expr = INSTANCE_ACCESSOR.contains(left.getText()) ?
                ctx :
                left;
            return expr.methodCall() != null;
        } else {
            return ctx.methodCall() != null;
        }
    }

    @Override
    public Void visitCreator(JavaParser.CreatorContext ctx) {
        JavaParser.MethodDeclarationContext method = (JavaParser.MethodDeclarationContext) currentScope.get("method");
        if (method == null || isProcessed(method, ctx.start)) {
            return super.visitCreator(ctx);
        }

        Optional.ofNullable(ctx.classCreatorRest())
            .flatMap(classCreatorRest -> metadataTable.maybeTestInfoUsageConstructor(
                fullyQualifiedName, ctx.createdName().getText(), classCreatorRest.arguments()))
            .ifPresent(testInfoUsageMethod -> {
                setTokenProcessed(method, ctx.start);
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
        if (!isFirstLevelMethodCall || method == null || isProcessed(method, ctx.start)) {
            return super.visitMethodCall(ctx);
        }

        metadataTable.maybeTestInfoUsageMethod(fullyQualifiedName, ctx.identifier().getText(), ctx.arguments())
            .ifPresent(testInfoUsageMethod -> {
                setTokenProcessed(method, ctx.start);
                parameterAdder.addAfter(
                    ctx.arguments().LPAREN().getSymbol(),
                    ctx.arguments().expressionList() == null,
                    "testInfo");
            });

        return super.visitMethodCall(ctx);
    }

    private boolean isProcessed(JavaParser.MethodDeclarationContext method, Token token) {
        return Optional.ofNullable(method)
            .map(testInfoUsageMethodsTokensProcessed::get)
            .filter(tokens -> tokens.contains(token))
            .isPresent();
    }

    private void setTokenProcessed(
        JavaParser.MethodDeclarationContext method,
        Token token
    ) {
        testInfoUsageMethods.add(method);
        if (!testInfoUsageMethodsTokensProcessed.containsKey(method)) {
            testInfoUsageMethodsTokensProcessed.put(method, new ArrayList<>());
        }
        testInfoUsageMethodsTokensProcessed.get(method).add(token);
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

    public void saveOutput(Path outputPath) throws IOException {
        Files.writeString(
            outputPath,
            rewriter.getText(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
    }
}
