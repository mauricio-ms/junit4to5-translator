package com.junit4to5.translator.java;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
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
        "org.mockito.junit.MockitoJUnitRunner",
        "junit.framework.TestCase");
    private static final List<String> CLASS_ACCESS = List.of(
        "this.getClass()", "getClass()",
        // TODO - hardcoded expressions known by return Class type
        //  correct would be to collect metadata of all classes before translation
        "helper.getTestClass()");

    private final BufferedTokenStream tokens;
    private final TokenStreamRewriter rewriter;
    private final MetadataTable metadataTable;
    private final CrossReferences crossReferences;
    private final SymbolTable symbolTable;
    private final HiddenTokens hiddenTokens;
    private final ParameterAdder parameterAdder;
    private final Set<String> staticAddedImports;
    private final Set<String> addedImports;

    private Scope currentScope;
    private String packageDeclaration;
    private String fullyQualifiedName;
    private boolean isTestCaseClass;
    private boolean isTranslatingParameterizedTest;
    private boolean isMissingTestAnnotation;
    private boolean testNameRuleExpressionProcessed;
    private String expectedTestAnnotationClause;
    private boolean skip;

    JUnit4to5TranslatorFirstPass(
        BufferedTokenStream tokens,
        TokenStreamRewriter rewriter,
        MetadataTable metadataTable,
        CrossReferences crossReferences,
        SymbolTable symbolTable
    ) {
        this.tokens = tokens;
        this.rewriter = rewriter;
        this.metadataTable = metadataTable;
        this.crossReferences = crossReferences;
        this.symbolTable = symbolTable;
        hiddenTokens = new HiddenTokens(tokens);
        parameterAdder = new ParameterAdder(rewriter, tokens);
        staticAddedImports = new HashSet<>();
        addedImports = new HashSet<>();
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        super.visitCompilationUnit(ctx);

        if (metadataTable.streamTestInfoUsageMethod(fullyQualifiedName).findAny().isPresent()) {
            addedImports.add("org.junit.jupiter.api.TestInfo");
        }

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
            .append(System.lineSeparator())
            .append(addedImportDeclarations)
            .append(System.lineSeparator()); // TODO - should include this only if this is not the last import
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
                    case "org.junit.runners.Parameterized",
                         "org.junit.runners.Parameterized.Parameters" -> {
                        skip = true; // TODO - ignore temporarily
                        yield importName;
                    }
                    case "org.junit.rules.TestName" -> {
                        addedImports.add("org.junit.jupiter.api.TestInfo");
                        yield null;
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
    public Void visitInterfaceDeclaration(JavaParser.InterfaceDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope);
        super.visitInterfaceDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
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
        currentScope = new NestedScope(currentScope, CLASS_SCOPE);
        maybeExtendsTestCase(ctx)
            .ifPresent(testCase -> {
                isTestCaseClass = true;
                rewriter.replace(ctx.EXTENDS().getSymbol(), testCase.stop, "");
                deleteNextIf(testCase.stop, " ");
            });
        super.visitClassDeclaration(ctx);
        boolean addTestInfoArgumentToConstructor = currentScope
            .hasBool("addTestInfoArgumentToConstructor");
        if (addTestInfoArgumentToConstructor) {
            List<JavaParser.ConstructorDeclarationContext> constructors =
                (List<JavaParser.ConstructorDeclarationContext>) currentScope.get("constructor");
            for (JavaParser.ConstructorDeclarationContext constructor : constructors) {
                metadataTable.get(fullyQualifiedName)
                    .addTestInfoUsageConstructor(constructor);

                parameterAdder.addAfter(
                    constructor.formalParameters().LPAREN().getSymbol(),
                    constructor.formalParameters().formalParameterList() == null,
                    "TestInfo testInfo");

                maybeThisCall(constructor)
                    .ifPresentOrElse(thisCall -> {
                        Token lParen = thisCall.arguments().LPAREN().getSymbol();
                        parameterAdder.addAfter(lParen, thisCall.arguments().expressionList() == null, "testInfo");
                    }, () -> {
                        Token lBrace = constructor.block().LBRACE().getSymbol();
                        Token newLine = hiddenTokens.maybeNextAs(lBrace, "\n")
                            .orElseThrow(() -> new IllegalStateException("\n not found"));
                        rewriter.insertAfter(lBrace, newLine.getText() + "this.testInfo = testInfo;");
                    });
            }

            String instanceVariableDeclaration = "private final TestInfo testInfo;";
            maybeStartInstanceVariablesSection(ctx.classBody())
                .ifPresentOrElse(start -> rewriter.insertBefore(start,
                        "%s\n%8s".formatted(instanceVariableDeclaration, "")),
                    () -> rewriter.insertAfter(ctx.classBody().LBRACE().getSymbol(),
                        "\n%8s%s".formatted("", instanceVariableDeclaration)));
        }
        currentScope = currentScope.enclosing();
        return null;
    }

    private Optional<JavaParser.TypeTypeContext> maybeExtendsTestCase(JavaParser.ClassDeclarationContext ctx) {
        return Optional.of(ctx)
            .filter(c -> c.EXTENDS() != null)
            .map(JavaParser.ClassDeclarationContext::typeType)
            .filter(t -> "TestCase".equals(TypeResolver.resolve(t)));
    }

    private Optional<JavaParser.MethodCallContext> maybeThisCall(JavaParser.ConstructorDeclarationContext constructor) {
        JavaParser.BlockContext block = constructor.block();
        if (block.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(block.blockStatement(0))
            .map(JavaParser.BlockStatementContext::statement)
            .flatMap(stmt -> Optional.ofNullable(stmt.expression())
                .filter(expr -> !expr.isEmpty())
                .map(expr -> expr.get(0)))
            .map(JavaParser.ExpressionContext::methodCall)
            .filter(call -> call.THIS() != null);
    }

    private Optional<Token> maybeStartInstanceVariablesSection(JavaParser.ClassBodyContext classBody) {
        Predicate<JavaParser.ModifierContext> isNonStaticModifier = m ->
            m.classOrInterfaceModifier() != null && m.classOrInterfaceModifier().STATIC() != null;
        Predicate<JavaParser.ClassBodyDeclarationContext> isNonStaticDeclaration = d ->
            d.modifier().stream().noneMatch(isNonStaticModifier);
        Predicate<JavaParser.ClassBodyDeclarationContext> isInstanceVariableDeclaration = d ->
            d.memberDeclaration() != null && d.memberDeclaration().fieldDeclaration() != null;
        return classBody.classBodyDeclaration().stream()
            .filter(isNonStaticDeclaration.and(isInstanceVariableDeclaration))
            .findFirst()
            .map(ParserRuleContext::getStart);
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
                    .filter(f -> isTestNameRule(ctx, f))
                    .ifPresent(__ -> {
                        rewriter.delete(ctx.start, ctx.stop);
                        deleteNextIf(ctx.stop, "\n");
                    });

                declareInstanceVariables(ctx, currentScope);
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

    @Override
    public Void visitConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx) {
        currentScope.declareList("constructor", ctx);
        currentScope = new NestedScope(currentScope);
        super.visitConstructorDeclaration(ctx);
        currentScope = currentScope.enclosing();
        return null;
    }

    @Override
    public Void visitCreator(JavaParser.CreatorContext ctx) {
        Optional<JavaParser.ClassCreatorRestContext> maybeClassCreator = Optional.ofNullable(ctx.classCreatorRest());
        maybeClassCreator
            .map(JavaParser.ClassCreatorRestContext::arguments)
            .map(JavaParser.ArgumentsContext::expressionList)
            .ifPresent(this::replaceOldTestNameRuleSignature);
        Optional<JavaParser.ClassBodyContext> maybeAnonymousClassCreator = maybeClassCreator
            .map(JavaParser.ClassCreatorRestContext::classBody);
        maybeAnonymousClassCreator.ifPresent(__ -> currentScope = new NestedScope(currentScope));
        super.visitCreator(ctx);
        maybeAnonymousClassCreator.ifPresent(__ -> currentScope = currentScope.enclosing());
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
            maybeTestNameRuleMethodCall(ctx)
                .ifPresent(methodCall -> {
                    if (!"getMethodName".equals(methodCall.identifier().getText())) {
                        throw new IllegalStateException(
                            "Unexpected test name rule method call: " + methodCall.getText());
                    }
                    replaceTestNameRuleArgument(
                        ctx.start, ctx.stop, "testInfo.getTestMethod().orElseThrow().getName()");
                });

            maybeInstanceVariableAccessViaThis(ctx)
                .ifPresent(instanceVariable -> {
                    if (TEST_NAME_RULE.equals(currentScope.resolve(instanceVariable))) {
                        replaceTestNameRuleArgument(ctx.start, ctx.stop, "testInfo");
                    }
                });

            maybeOldJUnitAssertCall(ctx)
                .ifPresent(assertExpr ->
                    rewriter.replace(assertExpr.start, assertExpr.stop, "Assertions"));

            // TODO - maybe it can be removed
            if (isTestUtilDependencyCall(ctx) || isTestUtilStaticCall(ctx)) {
                maybeTestUtilArguments(ctx.methodCall().arguments())
                    .ifPresent(args -> removeArgument(
                        ctx.methodCall().arguments().expressionList(),
                        args,
                        0));
            }
        } else if (ctx.NEW() != null && ctx.creator() != null) {
            if (isTestUtilTypeCreator(ctx.creator())) {
                maybeTestUtilArguments(ctx.creator().classCreatorRest().arguments())
                    .ifPresent(args -> removeArgument(
                        ctx.creator().classCreatorRest().arguments().expressionList(),
                        args,
                        0));
            }
        }
        super.visitExpression(ctx);
        testNameRuleExpressionProcessed = false;
        return null;
    }

    private Optional<JavaParser.MethodCallContext> maybeTestNameRuleMethodCall(JavaParser.ExpressionContext ctx) {
        return Optional.of(ctx.expression(0))
            .filter(e -> TEST_NAME_RULE.equals(currentScope.resolve(e.getText())))
            .map(__ -> ctx.methodCall());
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

    private void removeArgument(
        JavaParser.ExpressionListContext expressionList,
        List<JavaParser.ExpressionContext> arguments,
        int index
    ) {
        var argument = arguments.get(index);
        var comma = expressionList.COMMA(index).getSymbol();
        rewriter.replace(argument.start, argument.stop, "");
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
        return null;
    }

    @Override
    public Void visitFormalParameter(JavaParser.FormalParameterContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), TypeResolver.resolve(ctx.typeType()));
        return super.visitFormalParameter(ctx);
    }

    @Override
    public Void visitLastFormalParameter(JavaParser.LastFormalParameterContext ctx) {
        currentScope.declare(ctx.variableDeclaratorId().getText(), TypeResolver.resolve(ctx.typeType()));
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
        Optional.ofNullable(ctx.arguments().expressionList())
            .ifPresent(this::replaceOldTestNameRuleSignature);
        if (isTestCaseClass) {
            Optional.ofNullable(ctx.identifier())
                .filter(id -> "assertEquals".equals(id.getText()))
                .ifPresent(__ -> staticAddedImports.add("org.junit.jupiter.api.Assertions.assertEquals"));
        }
        return super.visitMethodCall(ctx);
    }

    private void replaceOldTestNameRuleSignature(
        JavaParser.ExpressionListContext expressionList
    ) {
        var arguments = expressionList.expression();
        if (arguments.size() >= 2) {
            arguments.stream()
                .filter(arg -> TEST_NAME_RULE.equals(currentScope.resolve(arg.getText())))
                .findFirst()
                .ifPresent(testNameRuleArg -> {
                    int indexOf = arguments.indexOf(testNameRuleArg);
                    if (indexOf > 0 && CLASS_ACCESS.contains(arguments.get(indexOf - 1).getText())) {
                        removeArgument(
                            expressionList, arguments, indexOf - 1);
                    }
                });
        }
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
        currentScope.declare(ctx.variableDeclaratorId().getText(), TypeResolver.resolve(ctx.typeType()));
        return super.visitEnhancedForControl(ctx);
    }

    @Override
    public Void visitLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {
        Optional.ofNullable(ctx.VAR())
            .ifPresentOrElse(
                v -> currentScope.declare(ctx.identifier().getText(), v.getText()),
                () -> {
                    String type = TypeResolver.resolve(ctx.typeType());
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
                    replaceTestNameRuleArgument(id.start, id.stop, "testInfo");
                }
            });

        return super.visitPrimary(ctx);
    }

    private void replaceTestNameRuleArgument(Token start, Token stop, String replacement) {
        if (!testNameRuleExpressionProcessed) {
            Scope classScope = currentScope.enclosingFor(CLASS_SCOPE);
            boolean isAtMainClassScope = classScope.depth() == 2;
            // TODO - maybe migrate this also to metadata collector phase
            if (!isAtMainClassScope) {
                classScope.declare("addTestInfoArgumentToConstructor", true);
            }

            rewriter.replace(start, stop, replacement);
            testNameRuleExpressionProcessed = true;
        }
    }

    private void deleteTokenPlusSpace(Token token) {
        rewriter.delete(token);
        deleteNextIf(token, " ");
    }

    private void deletePreviousIf(Token token, String previousToken) {
        hiddenTokens.maybePreviousAs(token, previousToken)
            .ifPresent(hiddenToken -> {
                String replacement = "\n".equals(previousToken) ?
                    hiddenToken.getText().substring(1) :
                    "";
                rewriter.replace(hiddenToken, replacement);
            });
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
        return hiddenTokens.maybeNextAs(token, nextToken)
            .map(hiddenToken -> {
                rewriter.replace(hiddenToken, replacementFn.apply(hiddenToken.getText()));
                return true;
            })
            .orElse(false);
    }

    public boolean isSkip() {
        return skip;
    }
}
