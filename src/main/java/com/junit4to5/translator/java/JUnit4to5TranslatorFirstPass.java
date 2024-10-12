package com.junit4to5.translator.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import antlr.java.JavaLexer;
import antlr.java.JavaParser;

class JUnit4to5TranslatorFirstPass extends BaseJUnit4To5Pass {
    static final String METHOD_SCOPE = "method";
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
    private static final String[] SETUP_RULES = {
        "TestDataSetupRule",
        "BlockbusterApiTestSetupRule",
        "ScoreboardTestSetupRule"};

    private final BufferedTokenStream tokens;
    private final Rewriter rewriter;
    private final MetadataTable metadataTable;
    private final CrossReferences crossReferences;
    private final SymbolTable symbolTable;
    private final HiddenTokens hiddenTokens;
    private final ParameterAdder parameterAdder;
    private final List<String> setupRuleCalls;

    private Scope currentScope;
    private String packageDeclaration;
    private String fullyQualifiedName;
    private int ruleAnnotationUsage;
    private int testAnnotationUsage;
    private boolean isTestCaseClass;
    private boolean hasBeforeMethod;
    private boolean isTranslatingJUnitAnnotatedMethod;
    private boolean isTranslatingBeforeMethod;
    private boolean hasAddedSetupRules;
    private boolean isTranslatingParameterizedTest;
    private boolean isMissingTestAnnotation;
    private boolean hasAssumeTrueTranslation;
    private boolean testNameRuleExpressionProcessed;
    private boolean hasStartedMethodTranslations;
    private String expectedTestAnnotationClause;
    private JavaParser.AnnotationContext dataProviderSourceAnnotation;
    private JavaParser.FieldDeclarationContext lastInstanceVariableDeclaration;

    JUnit4to5TranslatorFirstPass(
        BufferedTokenStream tokens,
        Rewriter rewriter,
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
        setupRuleCalls = new ArrayList<>();
    }

    @Override
    public Void visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
        currentScope = new GlobalScope();
        super.visitCompilationUnit(ctx);
        if (testAnnotationUsage > 0) {
            metadataTable.get(fullyQualifiedName)
                .addImport("org.junit.jupiter.api.Test");
        }
        if (ruleAnnotationUsage > 0) {
            metadataTable.get(fullyQualifiedName)
                .addImport("org.junit.Rule");
        }
        if (!hasBeforeMethod) {
            MetadataTable.Metadata metadata = metadataTable.get(fullyQualifiedName);
            String setupRuleCalls = metadata.streamRules(SETUP_RULES)
                .map(setupRule -> "%8s%s%n".formatted("", buildSetupRuleCall(setupRule)))
                .collect(Collectors.joining());
            if (!setupRuleCalls.isEmpty()) {
                metadata.addImport("org.junit.jupiter.api.BeforeEach");
                String beforeEachMethod =
                    "%n%n%4s@BeforeEach%n".formatted("") +
                    "%4svoid setUp() {%n".formatted("") +
                    setupRuleCalls +
                    "%4s}".formatted("");
                rewriter.insertAfter(lastInstanceVariableDeclaration.getStop(), beforeEachMethod);
            }
        }
        return null;
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
                        rewriter.deleteNextIf(ctx.stop, "\n");
                    });
        } else if (IMPORTS_FOR_REMOVAL.contains(importName)) {
            rewriter.delete(ctx.start, ctx.stop);
            rewriter.deleteNextIf(ctx.stop, "\n", hiddenToken -> hiddenToken.substring(1));
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
                case "org.junit.Assume.assumeTrue" -> {
                    hasAssumeTrueTranslation = true;
                    yield "org.junit.jupiter.api.Assumptions.assumeTrue";
                }
                case "org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage" -> importName;
                default -> throw new IllegalStateException("Unexpected JUnit static import: " + importName);
            });
    }

    private Optional<String> getJUnit5NonStaticImport(String importName) {
        return Optional.ofNullable(
                switch (importName) {
                    case "org.junit.Assert" -> "org.junit.jupiter.api.Assertions";
                    case "org.junit.Before" -> "org.junit.jupiter.api.BeforeEach";
                    case "org.junit.BeforeClass" -> "org.junit.jupiter.api.BeforeAll";
                    case "org.junit.After" -> "org.junit.jupiter.api.AfterEach";
                    case "org.junit.AfterClass" -> "org.junit.jupiter.api.AfterAll";
                    case "org.junit.Ignore" -> "org.junit.jupiter.api.Disabled";
                    case "org.junit.rules.ErrorCollector",
                         "org.junit.rules.ExpectedException",
                         "org.junit.rules.TestRule",
                         "org.junit.runners.model.Statement",
                         "org.junit.runner.Description" -> importName; // TODO - review
                    case "org.junit.runner.RunWith",
                         "org.junit.Rule",
                         "org.junit.runners.Parameterized",
                         "org.junit.runners.Parameterized.Parameters",
                         "org.junit.Test",
                         "org.junit.rules.TestName" -> null;
                    default -> throw new IllegalStateException("Unexpected JUnit import: " + importName);
                })
            .map("import %s;"::formatted);
    }

    @Override
    public Void visitAnnotation(JavaParser.AnnotationContext ctx) {
        maybeExpectedTestAnnotationClause(ctx)
            .ifPresent(expected -> {
                expectedTestAnnotationClause = expected;
                metadataTable.get(fullyQualifiedName)
                    .addStaticImport("org.junit.jupiter.api.Assertions.assertThrows");
                rewriter.delete(ctx.LPAREN().getSymbol(), ctx.RPAREN().getSymbol());
            });
        maybeAnnotationReplacement(ctx)
            .ifPresent(a -> {
                rewriter.replace(ctx.start, ctx.stop, a);
                if (a.isBlank()) {
                    rewriter.deleteNextIf(ctx.stop, "\n");
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
        switch (annotationName) {
            case "Test" -> {
                isTranslatingJUnitAnnotatedMethod = true;
                testAnnotationUsage++;
            }
            case "Rule" -> ruleAnnotationUsage++;
        }

        if (isTranslatingParameterizedTest) {
            return switch (annotationName) {
                case "Test" -> {
                    testAnnotationUsage--;
                    yield Optional.of("@ParameterizedTest");
                }
                case "UseDataProvider" -> {
                    isTranslatingJUnitAnnotatedMethod = true;
                    metadataTable.get(fullyQualifiedName)
                        .addImport("org.junit.jupiter.params.provider.MethodSource");
                    String methodSourceAnnotation = generatedMethodSourceAnnotation(ctx);
                    if (isMissingTestAnnotation) {
                        yield Optional.of(
                            "@ParameterizedTest%s%4s%s"
                                .formatted(System.lineSeparator(), "", methodSourceAnnotation));
                    }
                    yield Optional.of(methodSourceAnnotation);
                }
                case "DataProvider" -> {
                    dataProviderSourceAnnotation = ctx;
                    yield Optional.empty();
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
                    MetadataTable.Metadata metadata = metadataTable.get(fullyQualifiedName);
                    metadata.addImport("org.junit.jupiter.api.extension.ExtendWith");
                    metadata.addImport("org.springframework.test.context.junit.jupiter.SpringExtension");
                    yield Optional.of("@ExtendWith(SpringExtension.class)");
                }
                case "MockitoJUnitRunner.class" -> {
                    MetadataTable.Metadata metadata = metadataTable.get(fullyQualifiedName);
                    metadata.addImport("org.junit.jupiter.api.extension.ExtendWith");
                    metadata.addImport("org.mockito.junit.jupiter.MockitoExtension");
                    yield Optional.of("@ExtendWith(MockitoExtension.class)");
                }
                default -> throw new IllegalStateException("Unexpected JUnit RunWith: " + ctx.getText());
            };
            case "Before" -> {
                isTranslatingJUnitAnnotatedMethod = true;
                isTranslatingBeforeMethod = true;
                hasBeforeMethod = true;
                yield Optional.of("@BeforeEach");
            }
            case "BeforeClass" -> {
                isTranslatingJUnitAnnotatedMethod = true;
                yield Optional.of("@BeforeAll");
            }
            case "After" -> {
                isTranslatingJUnitAnnotatedMethod = true;
                yield Optional.of("@AfterEach");
            }
            case "AfterClass" -> {
                isTranslatingJUnitAnnotatedMethod = true;
                yield Optional.of("@AfterAll");
            }
            case "Ignore" -> {
                isTranslatingJUnitAnnotatedMethod = true;
                yield Optional.of("@Disabled");
            }
            case "DataProvider" -> Optional.of("");
            default -> Optional.empty();
        };
    }

    private String generatedMethodSourceAnnotation(JavaParser.AnnotationContext ctx) {
        String methodSourceParam = Optional.ofNullable(ctx.elementValue())
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
                            .orElseGet(() -> "%s.%s".formatted(packageDeclaration, location)),
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
            });
        int length = "@MethodSource(".length() +
                     methodSourceParam.length() +
                     JUnit4to5TranslatorFormattingPass.INDENTATION_LEVEL;
        if (length > Rewriter.MAX_LINE_LENGTH) {
            String[] parts = methodSourceParam.split("#");
            return "@MethodSource(%n%8s%s#\" +%n%1$8s\"%s)".formatted(
                "",
                parts[0],
                parts[1]);
        }
        return "@MethodSource(%s)".formatted(methodSourceParam);
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
        boolean isBeforeMainClassScope = currentScope.depth() == 1;
        if (isBeforeMainClassScope && ctx.EXTENDS() != null) {
            currentScope = new NestedScope(currentScope);
            metadataTable.getBase(fullyQualifiedName)
                .ifPresent(baseClassMetadata ->
                    baseClassMetadata.getInstanceVariables().forEach(currentScope::declare));
        }

        currentScope = new NestedScope(currentScope, CLASS_SCOPE);
        currentScope.declare("$main", isBeforeMainClassScope);
        maybeExtendsTestCase(ctx)
            .ifPresent(testCase -> {
                isTestCaseClass = true;
                rewriter.replace(ctx.EXTENDS().getSymbol(), testCase.stop, "");
                rewriter.deleteNextIf(testCase.stop, " ");
            });
        super.visitClassDeclaration(ctx);
        boolean addTestInfoArgumentToConstructor = currentScope
            .hasBool("addTestInfoArgumentToConstructor");
        if (addTestInfoArgumentToConstructor) {
            metadataTable.get(fullyQualifiedName)
                .addImport("org.junit.jupiter.api.TestInfo");
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
                        parameterAdder.addAfter(
                            lParen, thisCall.arguments().expressionList() == null, "testInfo");
                    }, () -> {
                        Token lBrace = constructor.block().LBRACE().getSymbol();
                        Token newLine = hiddenTokens.maybeNextAs(lBrace, "\n")
                            .orElseThrow(() -> new IllegalStateException("\n not found"));
                        rewriter.insertAfter(lBrace, newLine.getText() + "this.testInfo = testInfo;");
                    });
            }

            String instanceVariableDeclaration = "private final TestInfo testInfo;";
            maybeStartInstanceVariablesSection(ctx.classBody())
                .ifPresentOrElse(
                    start -> rewriter.insertBefore(
                        start,
                        "%s\n%8s".formatted(instanceVariableDeclaration, "")),
                    () -> rewriter.insertAfter(
                        ctx.classBody().LBRACE().getSymbol(),
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
                if (currentScope.hasBool("$main") && memberDeclaration.methodDeclaration() != null) {
                    boolean hasCrossReference = crossReferences.hasCrossReference(
                        fullyQualifiedName,
                        memberDeclaration.methodDeclaration().identifier().getText(),
                        FormalParameters.get(memberDeclaration.methodDeclaration().formalParameters()).size());
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
                        ruleAnnotationUsage--;
                        rewriter.delete(ctx.start, ctx.stop);
                        rewriter.deleteNextIf(ctx.stop, "\n");
                    });

                declareInstanceVariables(ctx, currentScope);
            });

        List<String> annotations = getAnnotationsStream(ctx)
            .map(JavaParser.AnnotationContext::qualifiedName)
            .map(RuleContext::getText)
            .toList();
        if (annotations.contains("UseDataProvider") ||
            annotations.contains("Test") && annotations.contains("DataProvider")) {
            isTranslatingParameterizedTest = true;
            isMissingTestAnnotation = !annotations.contains("Test");
            metadataTable.get(fullyQualifiedName)
                .addImport("org.junit.jupiter.params.ParameterizedTest");
        }

        super.visitClassBodyDeclaration(ctx);
        isTranslatingParameterizedTest = false;
        return null;
    }

    @Override
    public Void visitFieldDeclaration(JavaParser.FieldDeclarationContext ctx) {
        if (!hasStartedMethodTranslations && currentScope.depth() == 2) {
            lastInstanceVariableDeclaration = ctx;
        }
        return super.visitFieldDeclaration(ctx);
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
        if (!rewriter.deleteNextIf(comma, " ")) {
            rewriter.deleteNextIf(comma, "\n");
        }
    }

    private String getMethodCallIdentifier(JavaParser.MethodCallContext ctx) {
        return Optional.ofNullable(ctx.identifier())
            .map(RuleContext::getText)
            .orElse(null);
    }

    @Override
    public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        currentScope = new NestedScope(currentScope, METHOD_SCOPE);
        hasStartedMethodTranslations = true;
        if (dataProviderSourceAnnotation != null) {
            var formalParameters = FormalParameters.get(ctx.formalParameters());
            rewriter.replace(dataProviderSourceAnnotation.getStart(), dataProviderSourceAnnotation.getStop(),
                generateParametersSourceAnnotationFor(formalParameters.wideType()));
            dataProviderSourceAnnotation = null;
        }
        super.visitMethodDeclaration(ctx);
        if (currentScope.hasBool("addTestInfoArgumentToMethod")) {
            metadataTable.get(fullyQualifiedName).addTestInfoUsageMethod(ctx);
        }
        currentScope = currentScope.enclosing();
        if (isTranslatingJUnitAnnotatedMethod) {
            metadataTable.get(fullyQualifiedName).addAnnotatedJUnitMethod(ctx);
            isTranslatingJUnitAnnotatedMethod = false;
        }
        return null;
    }

    private String generateParametersSourceAnnotationFor(String type) {
        return switch (type) {
            case "boolean", "Boolean" -> {
                metadataTable.get(fullyQualifiedName)
                    .addImport("org.junit.jupiter.params.provider.ValueSource");
                yield "@ValueSource(%s)".formatted(
                    rewriter.getText(dataProviderSourceAnnotation.elementValuePairs().getSourceInterval())
                        .replace("value", "booleans")
                        .replace("\"", "")
                        .toLowerCase());
            }
            case "int", "Integer" -> {
                metadataTable.get(fullyQualifiedName)
                    .addImport("org.junit.jupiter.params.provider.ValueSource");
                yield "@ValueSource(%s)".formatted(
                    rewriter.getText(dataProviderSourceAnnotation.elementValuePairs().getSourceInterval())
                        .replace("value", "ints")
                        .replace("\"", "")
                        .toLowerCase());
            }
            case "String" -> {
                metadataTable.get(fullyQualifiedName)
                    .addImport("org.junit.jupiter.params.provider.ValueSource");
                yield "@ValueSource(%s)".formatted(
                    rewriter.getText(dataProviderSourceAnnotation.elementValuePairs().getSourceInterval())
                        .replace("value", "strings"));
            }
            case "Strings" -> {
                metadataTable.get(fullyQualifiedName)
                    .addImport("org.junit.jupiter.params.provider.CsvSource");

                String annotationValue = Optional.ofNullable(dataProviderSourceAnnotation.elementValuePairs())
                    .map(elementValue -> rewriter.getText(elementValue.elementValuePair().stream()
                        .filter(v -> "value".equals(v.identifier().getText()))
                        .findFirst().orElseThrow(() ->
                            new IllegalStateException("No value element found on annotation: "
                                                      + dataProviderSourceAnnotation.getText()))
                        .getSourceInterval()))
                    .orElseGet(() -> rewriter.getText(dataProviderSourceAnnotation.elementValue().getSourceInterval()));
                yield "@CsvSource(%s)".formatted(annotationValue);
            }
            default -> {
                metadataTable.get(fullyQualifiedName)
                    .addImport("org.junit.jupiter.params.provider.EnumSource");

                yield "@EnumSource(%s)".formatted(
                    rewriter.getText(dataProviderSourceAnnotation.elementValuePairs().getSourceInterval())
                        .replace("value", "names"));
            }
            // TODO - the correct is to derive the type before using in the switch via the metadata table
            //  so AEnum will be enum and the we can have an enum case
            //  default -> throw new IllegalStateException("Unexpected type for parameters source generation: " + type)
        };
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
        if (isTranslatingBeforeMethod && !hasAddedSetupRules) {
            setupRuleCalls.addAll(
                metadataTable.get(fullyQualifiedName)
                    .streamRules(SETUP_RULES)
                    .map(JUnit4to5TranslatorFirstPass::buildSetupRuleCall)
                    .toList());
            isTranslatingBeforeMethod = false;
            hasAddedSetupRules = true;
        }
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
        super.visitMethodBody(ctx);
        if (!setupRuleCalls.isEmpty()) {
            String setupRuleCallStmt = hiddenTokens.maybeNextAs(ctx.block().LBRACE().getSymbol(), "\n\n")
                .map(__ -> setupRuleCalls.stream().map(setupRuleCall ->
                        "%n%8s%s".formatted(" ", setupRuleCall))
                    .collect(Collectors.joining()))
                .orElseGet(() -> setupRuleCalls.stream().map(setupRuleCall ->
                        "%n%8s%s%n".formatted(" ", setupRuleCall))
                    .collect(Collectors.joining()));
            rewriter.insertAfter(
                ctx.block().LBRACE().getSymbol(),
                setupRuleCallStmt);
        }
        setupRuleCalls.clear();
        return null;
    }

    private static String buildSetupRuleCall(String setupRuleIdentifier) {
        return "%s.setup();".formatted(setupRuleIdentifier);
    }

    @Override
    public Void visitMethodCall(JavaParser.MethodCallContext ctx) {
        Optional.ofNullable(ctx.arguments().expressionList())
            .ifPresent(this::replaceOldTestNameRuleSignature);
        Optional<JavaParser.MethodCallContext> maybeAssertEquals = Optional.of(ctx)
            .filter(methodCall -> methodCall.identifier() != null)
            .filter(methodCall -> "assertEquals".equals(methodCall.identifier().getText()));
        maybeAssertEquals
            .filter(ae -> {
                var arguments = ae.arguments().expressionList().expression();
                return arguments.size() == 3 && arguments.get(0).getText().startsWith("\"");
            })
            .ifPresent(ae -> {
                var expressionList = ctx.arguments().expressionList();
                var arguments = expressionList.expression();
                var message = arguments.get(0);
                var actual = arguments.get(2);
                Token messageComma = expressionList.COMMA(0).getSymbol();
                rewriter.delete(message.start, messageComma);
                rewriter.deleteNextIf(messageComma, " ");
                rewriter.insertAfter(actual.stop,
                    ", " + hiddenTokens.getText(message.getSourceInterval()));
            });
        if (isTestCaseClass) {
            maybeAssertEquals
                .ifPresent(__ -> metadataTable.get(fullyQualifiedName)
                    .addStaticImport("org.junit.jupiter.api.Assertions.assertEquals"));
        }
        if (hasAssumeTrueTranslation) {
            Optional.ofNullable(ctx.identifier())
                .filter(id -> "assumeTrue".equals(id.getText()))
                .ifPresent(__ -> {
                    var arguments = ctx.arguments().expressionList().expression();
                    if (arguments.size() != 2) {
                        throw new IllegalStateException("Unexpected arguments for assumeTrue: " + ctx.getText());
                    }
                    var message = arguments.get(0);
                    var assumption = arguments.get(1);
                    rewriter.replace(message.start, message.stop,
                        hiddenTokens.getText(assumption.getSourceInterval()));
                    rewriter.replace(assumption.start, assumption.stop,
                        hiddenTokens.getText(message.getSourceInterval()));
                });
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
        setupRuleCalls.remove(ctx.getText());

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
            // TODO - maybe migrate this also to metadata collector phase
            if (!classScope.hasBool("$main")) {
                classScope.declare("addTestInfoArgumentToConstructor", true);
            } else {
                Scope methodScope = currentScope.enclosingFor(METHOD_SCOPE);
                if (!methodScope.hasBool("addTestInfoArgumentToMethod")) {
                    methodScope
                        .declare("addTestInfoArgumentToMethod", true);
                }
            }

            rewriter.replace(start, stop, replacement);
            testNameRuleExpressionProcessed = true;
        }
    }

    private void deleteTokenPlusSpace(Token token) {
        rewriter.delete(token);
        rewriter.deleteNextIf(token, " ");
    }
}
