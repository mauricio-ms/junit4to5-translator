package com.junit4to5.translator.java;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Function;
import java.util.stream.Stream;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.TokenStreamRewriter;

import antlr.java.JavaLexer;
import antlr.java.JavaParser;

public class JUnit4To5TranslatorMain {
    private static final String JUNIT_4 = "JUNIT4";
    private static final Map<String, SyntaxTree> SYNTAX_TREE_CACHE = new HashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            Path argPath = Path.of(args[0]);
            if (Files.isDirectory(argPath)) {
                try (Stream<Path> filesStream = Files.list(argPath)) {
                    translate(
                        Map.of(JUNIT_4, filesStream.map(Path::toString).toList()),
                        inputFile -> "output/" + Path.of(inputFile).subpath(1, 2));
                }
            } else {
                translate(
                    Map.of(JUNIT_4, List.of(args[0])),
                    inputFile -> "output/Test.java");
            }
            return;
        }
        Scanner standardInputScanner = new Scanner(new BufferedInputStream(System.in), StandardCharsets.UTF_8);

        Map<String, List<String>> inputFiles = new HashMap<>();
        while (standardInputScanner.hasNextLine()) {
            String[] input = standardInputScanner.nextLine().split(":");
            inputFiles.computeIfAbsent(input[0], __ -> new ArrayList<>());
            inputFiles.get(input[0]).add(input[1]);
        }
        translate(inputFiles, Function.identity());
    }

    private static void translate(
        Map<String, List<String>> inputFiles,
        Function<String, String> outputPathFn
    ) throws IOException {
        System.out.println("Collecting classes metadata ...");
        CrossReferences crossReferences = new CrossReferences();
        MetadataTable metadataTable = new MetadataTable(crossReferences);
        collectMetadata(
            crossReferences,
            metadataTable,
            inputFiles.values().stream()
                .flatMap(Collection::stream)
                .toList());
        for (String inputFile : Optional.ofNullable(inputFiles.get(JUNIT_4)).orElseGet(ArrayList::new)) {
            System.out.println(">> " + inputFile);
            translate(crossReferences, metadataTable, inputFile, outputPathFn.apply(inputFile));
        }
    }

    private static void collectMetadata(
        CrossReferences crossReferences,
        MetadataTable metadataTable,
        List<String> inputFiles
    ) {
        for (String inputFile : inputFiles) {
            var tree = buildSyntaxTree(inputFile);
            var classesFinder = new JavaPublicClassesFinder(crossReferences);
            classesFinder.visit(tree.ruleContext());
        }

        for (String inputFile : inputFiles) {
            var tree = buildSyntaxTree(inputFile);
            var metadataCollector = new JavaMetadataCollector(metadataTable, crossReferences);
            metadataCollector.visit(tree.ruleContext());
        }

        for (String inputFile : inputFiles) {
            var tree = buildSyntaxTree(inputFile);
            var jUnit4TestNameRecursiveFinder = new JUnit4TestNameRecursiveFinder(metadataTable);
            jUnit4TestNameRecursiveFinder.visit(tree.ruleContext());
        }
    }

    private static void translate(
        CrossReferences crossReferences,
        MetadataTable metadataTable,
        String inputFile,
        String outputFile
    ) throws IOException {
        var tree = buildSyntaxTree(inputFile);
        Rewriter rewriter = new Rewriter(new TokenStreamRewriter(tree.tokens()), new HiddenTokens(tree.tokens()));
        SymbolTable symbolTable = new SymbolTable();
        var firstPass = new JUnit4to5TranslatorFirstPass(
            tree.tokens(), rewriter, metadataTable, crossReferences, symbolTable);
        firstPass.visit(tree.ruleContext());
        var secondPass = new JUnit4to5TranslatorSecondPass(tree.tokens(), rewriter, metadataTable);
        secondPass.visit(tree.ruleContext());
        var formattingPass = new JUnit4to5TranslatorFormattingPass(
            rewriter, new HiddenTokens(tree.tokens()), metadataTable);
        formattingPass.visit(tree.ruleContext());
        
        saveOutput(rewriter.getText(), Paths.get(outputFile));
    }

    private static SyntaxTree buildSyntaxTree(String inputFile) {
        return SYNTAX_TREE_CACHE.computeIfAbsent(inputFile, f -> {
            try {
                var input = new FileInputStream(f);
                var chars = CharStreams.fromStream(input);
                var lexer = new JavaLexer(chars);
                var tokens = new CommonTokenStream(lexer);
                var parser = new JavaParser(tokens);
                parser.setBuildParseTree(true);
                JavaParser.CompilationUnitContext compilationUnitContext = parser.compilationUnit();
                return new SyntaxTree(compilationUnitContext.getRuleContext(), tokens);
            } catch (IOException e) {
                throw new IllegalArgumentException("File %s not found:".formatted(f), e);
            }
        });
    }

    private record SyntaxTree(RuleContext ruleContext, CommonTokenStream tokens) {}

    private static void saveOutput(String text, Path outputPath) throws IOException {
        Files.writeString(
            outputPath,
            text,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
    }
}
