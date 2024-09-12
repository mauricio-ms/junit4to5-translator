package com.junit4to5.translator.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.TerminalNode;

import antlr.java.JavaLexer;
import antlr.java.JavaParser;

class JUnit4to5TranslatorFirstPass extends BaseJUnit4To5Pass {
    private static final Map<String, String> TEST_UTIL_DEPENDENCY_CALLS = Map.of(
        "CaptureAppTestUtil", "of",
        "EtlTestUtil", "of",
        "TestNameUtil", "of");
    private static final Map<String, String> TEST_UTIL_STATIC_LIBS_CALLS = Map.of(
        "GoldTable", "start");
    private static final List<String> IMPORTS_FOR_REMOVAL = List.of(
        "com.tngtech.java.junit.dataprovider.DataProvider",
        "com.tngtech.java.junit.dataprovider.DataProviderRunner",
        "com.tngtech.java.junit.dataprovider.UseDataProvider",
        "org.springframework.test.context.junit4.SpringJUnit4ClassRunner",
        "org.mockito.junit.MockitoJUnitRunner");
    private static final String TEST_NAME_RULE = "TEST_NAME_RULE";

    private final BufferedTokenStream tokens;
    private final TokenStreamRewriter rewriter;
    private final CrossReferences crossReferences;
    private final SymbolTable symbolTable;
    private final Set<String> staticAddedImports;
    private final Set<String> addedImports;

    private Scope currentScope;
    private String packageDeclaration;
    private String fullyQualifiedName;
    private boolean isTranslatingParameterizedTest;
    private boolean isMissingTestAnnotation;
    private boolean addTestInfoArgumentToMethod;
    private String expectedTestAnnotationClause;
    private boolean skip;

    JUnit4to5TranslatorFirstPass(
        BufferedTokenStream tokens,
        TokenStreamRewriter rewriter,
        CrossReferences crossReferences,
        SymbolTable symbolTable
    ) {
        this.tokens = tokens;
        this.rewriter = rewriter;
        this.crossReferences = crossReferences;
        this.symbolTable = symbolTable;
        staticAddedImports = new HashSet<>();
        addedImports = new HashSet<>();
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        super.visitCompilationUnit(ctx);

        Map<String, List<String>> importsPerPrefix = buildImportsPerPrefix(addedImports);
        importsPerPrefix.forEach((prefix, imports) ->
            insertImportsDeclarations(
                ctx,
                imports,
                d -> d.qualifiedName().getText().startsWith(prefix),
                "import %s;"::formatted));

        Map<String, List<String>> staticImportsPerPrefix = buildImportsPerPrefix(staticAddedImports);
        staticImportsPerPrefix.forEach((prefix, imports) ->
            insertImportsDeclarations(
                ctx,
                imports,
                d -> d.STATIC() != null && d.qualifiedName().getText().startsWith(prefix),
                "import static %s;"::formatted));

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
        var lastOccurrence = prefixOccurrences.isEmpty() ?
            ctx.importDeclaration(importDeclarations.size() - 1) :
            prefixOccurrences.get(prefixOccurrences.size() - 1);
        String addedImportDeclarations = imports.stream()
            .map(importDeclarationFn)
            .collect(Collectors.joining(System.lineSeparator()));
        StringBuilder insertionSb = new StringBuilder();
        if (prefixOccurrences.isEmpty()) {
            insertionSb.append(System.lineSeparator());
        }
        insertionSb
            .append(addedImportDeclarations)
            .append(System.lineSeparator());
        rewriter.insertAfter(lastOccurrence.stop, insertionSb.toString());
    }

    @Override
    public Void visitPackageDeclaration(JavaParser.PackageDeclarationContext ctx) {
        packageDeclaration = ctx.qualifiedName().getText();
        return null;
    }

    @Override
    public Void visitImportDeclaration(JavaParser.ImportDeclarationContext ctx) {
        boolean wildcardImport = ctx.DOT() != null && ctx.MUL() != null;
        String importName = wildcardImport ?
            ctx.qualifiedName().getText() + ".*" :
            ctx.qualifiedName().getText();
        symbolTable.addImport(importName);
        if (importName.startsWith("org.junit") && !importName.startsWith("org.junit.jupiter")) {
            getJUnit5Import(ctx.STATIC(), importName)
                .ifPresentOrElse(
                    jUnit5Import -> rewriter.replace(ctx.start, ctx.stop, jUnit5Import),
                    () -> {
                        rewriter.delete(ctx.start, ctx.stop);
                        deleteNextIf(ctx.stop, "\n");
                    });
        } else if (IMPORTS_FOR_REMOVAL.contains(importName)) {
            // TODO - should remove also:
            //  rule if all rules were removed
            //  should add test new import only if there is a non-parameterized test
            rewriter.delete(ctx.start, ctx.stop);
            deletePreviousIf(ctx.start, "\n");
            deleteNextIf(ctx.stop, "\n", hiddenToken -> hiddenToken.substring(1));
        }
        return super.visitImportDeclaration(ctx);
    }

    private Optional<String> getJUnit5Import(
        TerminalNode staticNode,
        String importName
    ) {
        return staticNode != null ?
            Optional.of(getJUnit5StaticImport(importName)) :
            getJUnit5NonStaticImport(importName);
    }

    private String getJUnit5StaticImport(String importName) {
        return "import static %s;".formatted(
            switch (importName) {
                case "org.junit.Assert.*" -> "org.junit.jupiter.api.Assertions.*";
                case "org.junit.Assert.assertArrayEquals" -> "org.junit.jupiter.api.Assertions.assertArrayEquals";
                case "org.junit.Assert.assertEquals" -> "org.junit.jupiter.api.Assertions.assertEquals";
                case "org.junit.Assert.assertNotEquals" -> "org.junit.jupiter.api.Assertions.assertNotEquals";
                case "org.junit.Assert.assertSame" -> "org.junit.jupiter.api.Assertions.assertSame";
                case "org.junit.Assert.assertNull" -> "org.junit.jupiter.api.Assertions.assertNull";
                case "org.junit.Assert.assertNotNull" -> "org.junit.jupiter.api.Assertions.assertNotNull";
                case "org.junit.Assert.assertTrue" -> "org.junit.jupiter.api.Assertions.assertTrue";
                case "org.junit.Assert.assertFalse" -> "org.junit.jupiter.api.Assertions.assertFalse";
                case "org.junit.Assert.assertThrows" -> "org.junit.jupiter.api.Assertions.assertThrows";
                case "org.junit.Assert.fail" -> "org.junit.jupiter.api.Assertions.fail";
                case "org.junit.Assume.assumeTrue" -> "org.junit.jupiter.api.Assumptions.assumeTrue";
                case "org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage" -> importName;
                default -> throw new IllegalStateException("Unexpected JUnit static import: " + importName);
            });
    }

    private Optional<String> getJUnit5NonStaticImport(String importName) {
        return Optional.ofNullable(
                switch (importName) {
                    case "org.junit.Assert" -> "org.junit.jupiter.api.Assertions";
                    case "org.junit.Test" -> "org.junit.jupiter.api.Test";
                    case "org.junit.Before" -> "org.junit.jupiter.api.BeforeEach";
                    case "org.junit.BeforeClass" -> "org.junit.jupiter.api.BeforeAll";
                    case "org.junit.After" -> "org.junit.jupiter.api.AfterEach";
                    case "org.junit.AfterClass" -> "org.junit.jupiter.api.AfterAll";
                    case "org.junit.Ignore" -> "org.junit.jupiter.api.Disabled";
                    case "org.junit.Rule",
                         "org.junit.rules.ErrorCollector",
                         "org.junit.rules.ExpectedException",
                         "org.junit.rules.TestRule",
                         "org.junit.runners.model.Statement",
                         "org.junit.runner.Description" -> importName; // TODO - review
                    case "org.junit.rules.TestName" -> "org.junit.jupiter.api.TestInfo";
                    case "org.junit.runners.Parameterized",
                         "org.junit.runners.Parameterized.Parameters" -> {
                        skip = true; // TODO - ignore temporarily
                        yield importName;
                    }
                    case "org.junit.runner.RunWith" -> null;
                    default -> throw new IllegalStateException("Unexpected JUnit import: " + importName);
                })
            .map("import %s;"::formatted);
    }

    @Override
    public Void visitAnnotation(JavaParser.AnnotationContext ctx) {
        maybeExpectedTestAnnotationClause(ctx)
            .ifPresent(expected -> {
                expectedTestAnnotationClause = expected;
                staticAddedImports.add("org.junit.jupiter.api.Assertions.assertThrows");
                rewriter.delete(ctx.LPAREN().getSymbol(), ctx.RPAREN().getSymbol());
            });
        maybeAnnotationReplacement(ctx)
            .ifPresent(a -> {
                rewriter.replace(ctx.start, ctx.stop, a);
                if (a.isBlank()) {
                    deleteNextIf(ctx.stop, "\n");
                }
            });
        return super.visitAnnotation(ctx);
    }

    private Optional<String> maybeExpectedTestAnnotationClause(JavaParser.AnnotationContext ctx) {
        String annotationName = ctx.qualifiedName().getText();
        if ("Test".equals(annotationName)) {
            return Optional.ofNullable(ctx.elementValuePairs())
                .map(JavaParser.ElementValuePairsContext::elementValuePair)
                .flatMap(pairs -> {
                    if (pairs.isEmpty()) {
                        return Optional.empty();
                    } else if (pairs.size() > 1) {
                        throw new IllegalStateException("Unexpected annotation value pairs: " + ctx.getText());
                    }

                    var valuePair = pairs.get(0);
                    if (!"expected".equals(valuePair.identifier().getText())) {
                        throw new IllegalStateException("Unexpected annotation value pairs: " + ctx.getText());
                    }
                    return Optional.of(valuePair.elementValue().getText());
                });
        }

        return Optional.empty();
    }

    private Optional<String> maybeAnnotationReplacement(JavaParser.AnnotationContext ctx) {
        String annotationName = ctx.qualifiedName().getText();
        if (isTranslatingParameterizedTest) {
            return switch (annotationName) {
                case "Test" -> Optional.of("@ParameterizedTest");
                case "UseDataProvider" -> {
                    String methodSourceAnnotation = generatedMethodSourceAnnotation(ctx);
                    if (isMissingTestAnnotation) {
                        yield Optional.of(
                            "@ParameterizedTest%s%4s%s"
                                .formatted(System.lineSeparator(), "", methodSourceAnnotation));
                    }
                    yield Optional.of(methodSourceAnnotation);
                }
                default -> maybeAnnotationReplacementDefault(ctx);
            };
        }

        return maybeAnnotationReplacementDefault(ctx);
    }

    private Optional<String> maybeAnnotationReplacementDefault(JavaParser.AnnotationContext ctx) {
        String annotationName = ctx.qualifiedName().getText();
        return switch (annotationName) {
            case "RunWith" -> switch (ctx.elementValue().getText()) {
                case "DataProviderRunner.class",
                     "Parameterized.class" -> Optional.of("");
                case "SpringJUnit4ClassRunner.class" -> {
                    addedImports.add("org.junit.jupiter.api.extension.ExtendWith");
                    addedImports.add("org.springframework.test.context.junit.jupiter.SpringExtension");
                    yield Optional.of("@ExtendWith(SpringExtension.class)");
                }
                case "MockitoJUnitRunner.class" -> {
                    addedImports.add("org.junit.jupiter.api.extension.ExtendWith");
                    addedImports.add("org.mockito.junit.jupiter.MockitoExtension");
                    yield Optional.of("@ExtendWith(MockitoExtension.class)");
                }
                default -> throw new IllegalStateException("Unexpected JUnit RunWith: " + ctx.getText());
            };
            case "Before" -> Optional.of("@BeforeEach");
            case "BeforeClass" -> Optional.of("@BeforeAll");
            case "After" -> Optional.of("@AfterEach");
            case "AfterClass" -> Optional.of("@AfterAll");
            case "Ignore" -> Optional.of("@Disabled");
            case "DataProvider" -> Optional.of("");
            default -> Optional.empty();
        };
    }

    private String generatedMethodSourceAnnotation(JavaParser.AnnotationContext ctx) {
        return "@MethodSource(%s)"
            .formatted(Optional.ofNullable(ctx.elementValue())
                .map(RuleContext::getText)
                .orElseGet(() -> {
                    var elementValuePairs = ctx.elementValuePairs().elementValuePair();
                    int elementValuePairsSize = elementValuePairs.size();
                    if (elementValuePairsSize == 2) {
                        var value = elementValuePairs.stream()
                            .filter(e -> e.identifier().getText().equals("value"))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                "No value parameter found in annotation: " + ctx.getText()))
                            .elementValue()
                            .getText();
                        var location = elementValuePairs.stream()
                            .filter(e -> e.identifier().getText().equals("location"))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                "No location parameter found in annotation: " + ctx.getText()))
                            .elementValue()
                            .getText()
                            .replace(".class", "");
                        return "\"%s#%s\"".formatted(
                            symbolTable.getImportFor(location)
                                // todo - it's needed to check for wildcard imports to make sure it's
                                //  not imported from some other package
                                .orElse(location),
                            value.substring(1, value.length() - 1));
                    } else if (elementValuePairsSize == 1) {
                        var elementValuePair = elementValuePairs.get(0);
                        if (!"value".equals(elementValuePair.identifier().getText())) {
                            throw new IllegalStateException(
                                "No value parameter found in annotation: " + ctx.getText());
                        }
                        return elementValuePair.elementValue().getText();
                    } else {
                        throw new IllegalStateException(
                            "Unexpected annotation parameters: " + ctx.getText());
                    }
                }));
    }

    @Override
    public Void visitTypeDeclaration(JavaParser.TypeDeclarationContext ctx) {
        var classDeclaration = ctx.classDeclaration();
        if (classDeclaration != null) {
            fullyQualifiedName = "%s.%s".formatted(
                packageDeclaration, classDeclaration.identifier().getText());
            if (!crossReferences.hasCrossReference(fullyQualifiedName)) {
                maybePublicToken(ctx.classOrInterfaceModifier().stream())
                    .ifPresent(this::deleteTokenPlusSpace);
            }
        }
        return super.visitTypeDeclaration(ctx);
    }

    @Override
    public Void visitEnumDeclaration(JavaParser.EnumDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitEnumDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitRecordDeclaration(JavaParser.RecordDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitRecordDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitClassDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitClassBodyDeclaration(JavaParser.ClassBodyDeclarationContext ctx) {
        Optional.ofNullable(ctx.memberDeclaration())
            .ifPresent(memberDeclaration -> {
                boolean isAtMainClassScope = currentScope.depth() == 2;
                if (isAtMainClassScope && memberDeclaration.methodDeclaration() != null) {
                    boolean hasCrossReference = crossReferences.hasCrossReference(
                        "%s.%s".formatted(
                            fullyQualifiedName,
                            memberDeclaration.methodDeclaration().identifier().getText()));
                    if (!hasCrossReference) {
                        maybePublicToken(ctx.modifier().stream()
                            .map(JavaParser.ModifierContext::classOrInterfaceModifier)
                            .filter(Objects::nonNull))
                            .ifPresent(this::deleteTokenPlusSpace);
                    }
                }

                Optional.ofNullable(memberDeclaration.fieldDeclaration())
                    .ifPresent(fieldDeclaration -> {
                        AtomicBoolean isTestNameRule = new AtomicBoolean();
                        boolean isRule = getAnnotationsStream(ctx)
                            .anyMatch(a -> a.qualifiedName().getText().equals("Rule"));
                        if (isRule) {
                            Optional.ofNullable(fieldDeclaration.typeType().classOrInterfaceType())
                                .filter(t -> t.getText().equals("TestName"))
                                .ifPresent(__ -> {
                                    isTestNameRule.set(true);
                                    rewriter.delete(ctx.start, ctx.stop);
                                    deleteNextIf(ctx.stop, "\n");
                                });
                        }

                        String variableType = isTestNameRule.get() ?
                            TEST_NAME_RULE :
                            resolveType(fieldDeclaration.typeType());
                        fieldDeclaration.variableDeclarators().variableDeclarator()
                            .forEach(v -> currentScope.declare(v.variableDeclaratorId().getText(), variableType));
                    });
            });

        List<String> annotations = getAnnotationsStream(ctx)
            .map(JavaParser.AnnotationContext::qualifiedName)
            .map(RuleContext::getText)
            .toList();
        if (annotations.contains("UseDataProvider")) {
            isTranslatingParameterizedTest = true;
            isMissingTestAnnotation = !annotations.contains("Test");
            addedImports.add("org.junit.jupiter.params.ParameterizedTest");
            addedImports.add("org.junit.jupiter.params.provider.MethodSource");
        }

        super.visitClassBodyDeclaration(ctx);
        isTranslatingParameterizedTest = false;
        return null;
    }

    private Stream<JavaParser.AnnotationContext> getAnnotationsStream(JavaParser.ClassBodyDeclarationContext ctx) {
        return ctx.modifier().stream()
            .map(JavaParser.ModifierContext::classOrInterfaceModifier)
            .filter(Objects::nonNull)
            .map(JavaParser.ClassOrInterfaceModifierContext::annotation)
            .filter(Objects::nonNull);
    }

    @Override
    public Void visitConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitConstructorDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitCreator(JavaParser.CreatorContext ctx) {
        Optional<JavaParser.ClassBodyContext> anonymousClassCreator = Optional.ofNullable(ctx.classCreatorRest())
            .map(JavaParser.ClassCreatorRestContext::classBody);
        anonymousClassCreator.ifPresent(__ -> currentScope = new NestedScope(currentScope));
        super.visitCreator(ctx);
        anonymousClassCreator.ifPresent(__ -> currentScope = currentScope.enclosing());
        return null;
    }

    @Override
    public Void visitLambdaExpression(JavaParser.LambdaExpressionContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitLambdaExpression(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitLambdaParameters(JavaParser.LambdaParametersContext ctx) {
        ctx.identifier()
            .forEach(identifier -> currentScope.declare(identifier.getText(), "notInferredLambdaParameter"));
        return super.visitLambdaParameters(ctx);
    }

    @Override
    public Void visitExpression(JavaParser.ExpressionContext ctx) {
        if (ctx.DOT() != null) {
            maybeInstanceVariableAccessViaThis(ctx)
                .ifPresent(instanceVariable -> {
                    if (TEST_NAME_RULE.equals(currentScope.resolve(instanceVariable))) {
                        addTestInfoArgumentToMethod = true;
                        rewriter.replace(ctx.start, ctx.stop, "testInfo");
                    }
                });

            maybeOldJUnitAssertCall(ctx)
                .ifPresent(assertExpr ->
                    rewriter.replace(assertExpr.start, assertExpr.stop, "Assertions"));

            // TODO - maybe it can be removed
            if (isTestUtilDependencyCall(ctx) || isTestUtilStaticCall(ctx)) {
                maybeTestUtilArguments(ctx.methodCall().arguments())
                    .ifPresent(args -> removeClassArgument(
                        args,
                        ctx.methodCall().arguments().expressionList().COMMA(0).getSymbol()));
            }
        } else if (ctx.NEW() != null && ctx.creator() != null) {
            if (isTestUtilTypeCreator(ctx.creator())) {
                maybeTestUtilArguments(ctx.creator().classCreatorRest().arguments())
                    .ifPresent(args -> removeClassArgument(
                        args,
                        ctx.creator().classCreatorRest().arguments().expressionList().COMMA(0).getSymbol()));
            }
        }
        return super.visitExpression(ctx);
    }

    private Optional<String> maybeInstanceVariableAccessViaThis(JavaParser.ExpressionContext ctx) {
        return Optional.of(ctx.expression(0))
            .map(JavaParser.ExpressionContext::primary)
            .filter(p -> p.THIS() != null && ctx.identifier() != null)
            .map(__ -> "this." + ctx.identifier().getText());
    }

    private Optional<JavaParser.ExpressionContext> maybeOldJUnitAssertCall(JavaParser.ExpressionContext ctx) {
        return Optional.ofNullable(ctx.expression(0))
            .filter(e -> "Assert".equals(e.getText()));
    }

    private boolean isTestUtilDependencyCall(JavaParser.ExpressionContext ctx) {
        return Optional.ofNullable(ctx.expression(0).getText())
            .map(currentScope::resolve)
            .filter(v -> {
                String methodName = TEST_UTIL_DEPENDENCY_CALLS.get(v);
                return methodName != null && methodName.equals(getMethodCallIdentifier(ctx.methodCall()));
            })
            .isPresent();
    }

    private boolean isTestUtilStaticCall(JavaParser.ExpressionContext ctx) {
        return Optional.ofNullable(ctx.expression(0).getText())
            .filter(v -> {
                String methodName = TEST_UTIL_STATIC_LIBS_CALLS.get(v);
                return methodName != null && methodName.equals(getMethodCallIdentifier(ctx.methodCall()));
            })
            .isPresent();
    }

    private boolean isTestUtilTypeCreator(JavaParser.CreatorContext ctx) {
        return TEST_UTIL_DEPENDENCY_CALLS.containsKey(ctx.createdName().getText());
    }

    private Optional<List<JavaParser.ExpressionContext>> maybeTestUtilArguments(
        JavaParser.ArgumentsContext arguments
    ) {
        return Optional.of(arguments)
            .map(JavaParser.ArgumentsContext::expressionList)
            .map(JavaParser.ExpressionListContext::expression)
            .filter(args -> {
                if (args.size() == 2) {
                    String testNameRule = args.get(1).getText();
                    return TEST_NAME_RULE.equals(currentScope.resolve(testNameRule));
                }
                return false;
            });
    }

    private void removeClassArgument(
        List<JavaParser.ExpressionContext> args,
        Token comma
    ) {
        var classArg = args.get(0);
        rewriter.replace(classArg.start, classArg.stop, "");
        rewriter.delete(comma);
        if (!deleteNextIf(comma, " ")) {
            deleteNextIf(comma, "\n");
        }
    }

    private String getMethodCallIdentifier(JavaParser.MethodCallContext ctx) {
        return Optional.ofNullable(ctx.identifier())
            .map(RuleContext::getText)
            .orElse(null);
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitMethodDeclaration(ctx);
        currentScope = currentScope.enclosing();
        if (addTestInfoArgumentToMethod) {
            symbolTable.addTestInfoUsageMethod(ctx);
            addTestInfoArgumentToMethod = false;
        }
        return null;
    }

    @Override
    public Void visitFormalParameter(JavaParser.FormalParameterContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), resolveType(ctx.typeType()));
        return super.visitFormalParameter(ctx);
    }

    @Override
    public Void visitLastFormalParameter(JavaParser.LastFormalParameterContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), resolveType(ctx.typeType()));
        return super.visitLastFormalParameter(ctx);
    }

    @Override
    public Void visitMethodBody(JavaParser.MethodBodyContext ctx) {
        if (expectedTestAnnotationClause != null) {
            String before = "%s%8sassertThrows(%s, () -> {"
                .formatted(System.lineSeparator(), "", expectedTestAnnotationClause);
            rewriter.insertAfter(ctx.block().start, before);

            ctx.block().blockStatement()
                .forEach(stmt -> {
                    String indent = "%4s".formatted("");
                    rewriter.insertBefore(stmt.start, indent);
                    Optional.ofNullable(tokens.getTokens(
                            stmt.start.getTokenIndex(), stmt.stop.getTokenIndex(), JavaLexer.WS))
                        .ifPresent(stmtTokens -> stmtTokens.stream()
                            .filter(ws -> ws.getText().contains("\n"))
                            .forEach(ws -> rewriter.insertAfter(ws, indent)));
                });

            String after = "%4s});%s%1$4s".formatted("", System.lineSeparator());
            rewriter.insertBefore(ctx.block().stop, after);
            expectedTestAnnotationClause = null;
        }
        return super.visitMethodBody(ctx);
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        var arguments = Optional.ofNullable(ctx.arguments().expressionList())
            .map(JavaParser.ExpressionListContext::expression)
            .orElseGet(ArrayList::new);

        if (arguments.size() >= 2) {
            var first = arguments.get(0);
            var second = arguments.get(1);
            if (TEST_NAME_RULE.equals(currentScope.resolve(second.getText())) &&
                "getClass()".equals(first.getText())) {
                removeClassArgument(
                    arguments,
                    ctx.arguments().expressionList().COMMA(0).getSymbol());
            }
        }

        return super.visitMethodCall(ctx);
    }

    @Override
    public Void visitBlock(JavaParser.BlockContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitBlock(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitStatement(JavaParser.StatementContext ctx) {
        boolean shouldCreateNestedScope = Stream.of(ctx.FOR())
            .anyMatch(Objects::nonNull);
        if (shouldCreateNestedScope) {
            currentScope = new NestedScope(currentScope);
        }
        super.visitStatement(ctx);
        if (shouldCreateNestedScope) {
            currentScope = currentScope.enclosing();
        }
        return null;
    }

    @Override
    public Void visitEnhancedForControl(JavaParser.EnhancedForControlContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), resolveType(ctx.typeType()));
        return super.visitEnhancedForControl(ctx);
    }

    @Override
    public Void visitLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {
        Optional.ofNullable(ctx.VAR())
            .ifPresentOrElse(
                v -> currentScope.declare(ctx.identifier().getText(), v.getText()),
                () -> {
                    String type = resolveType(ctx.typeType());
                    for (var varDeclarator : ctx.variableDeclarators().variableDeclarator()) {
                        currentScope.declare(varDeclarator.variableDeclaratorId().getText(), type);
                    }
                });
        return super.visitLocalVariableDeclaration(ctx);
    }

    @Override
    public Void visitPrimary(JavaParser.PrimaryContext ctx) {
        Optional.ofNullable(ctx.identifier())
            .ifPresent(id -> {
                if (TEST_NAME_RULE.equals(currentScope.resolve(id.getText()))) {
                    addTestInfoArgumentToMethod = true;
                    rewriter.replace(id.start, id.stop, "testInfo");
                }
            });

        return super.visitPrimary(ctx);
    }

    private void deleteTokenPlusSpace(Token token) {
        rewriter.delete(token);
        deleteNextIf(token, " ");
    }

    private void deletePreviousIf(Token token, String previousToken) {
        List<Token> hiddenTokensToLeft = tokens.getHiddenTokensToLeft(
            token.getTokenIndex(), JavaLexer.HIDDEN);
        if (hiddenTokensToLeft != null && !hiddenTokensToLeft.isEmpty()) {
            Token hiddenToken = hiddenTokensToLeft.get(0);
            if (hiddenToken.getText().startsWith(previousToken)) {
                String replacement = "\n".equals(previousToken) ?
                    hiddenToken.getText().substring(1) :
                    "";
                rewriter.replace(hiddenToken, replacement);
            }
        }
    }

    private boolean deleteNextIf(
        Token token,
        String nextToken
    ) {
        return deleteNextIf(token, nextToken, __ -> "");
    }

    private boolean deleteNextIf(
        Token token,
        String nextToken,
        Function<String, String> replacementFn
    ) {
        List<Token> hiddenTokensToRight = tokens.getHiddenTokensToRight(
            token.getTokenIndex(), JavaLexer.HIDDEN);
        if (hiddenTokensToRight != null && !hiddenTokensToRight.isEmpty()) {
            Token hiddenToken = hiddenTokensToRight.get(0);
            if (hiddenToken.getText().startsWith(nextToken)) {
                rewriter.replace(hiddenToken, replacementFn.apply(hiddenToken.getText()));
                return true;
            }
        }
        return false;
    }

    public boolean isSkip() {
        return skip;
    }
}
