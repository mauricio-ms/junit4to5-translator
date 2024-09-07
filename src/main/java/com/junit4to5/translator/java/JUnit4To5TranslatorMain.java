package com.junit4to5.translator.java;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                translate(new CrossReferences(), args[0], "output/Test.java");
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
        System.out.println("Computing cross references ...");
        CrossReferences crossReferences = findCrossReferences(
            inputFiles.values().stream()
                .flatMap(Collection::stream)
                .toList());
        for (String inputFile : inputFiles.get(JUNIT_4)) {
            System.out.println(">> " + inputFile);
            translate(crossReferences, inputFile, outputPathFn.apply(inputFile));
        }
    }

    private static CrossReferences findCrossReferences(List<String> inputFiles) throws IOException {
        CrossReferences crossReferences = new CrossReferences();

        for (String inputFile : inputFiles) {
            var tree = buildSyntaxTree(inputFile);
            var classesFinder = new JavaPublicClassesFinder(crossReferences);
            classesFinder.visit(tree);
        }

        for (String inputFile : inputFiles) {
            var tree = buildSyntaxTree(inputFile);
            var crossDependenciesFinder = new JavaCrossDependenciesFinder(crossReferences);
            crossDependenciesFinder.visit(tree);
        }

        return crossReferences;
    }

    private static RuleContext buildSyntaxTree(String inputFile) throws IOException {
        var input = new FileInputStream(inputFile);
        var chars = CharStreams.fromStream(input);
        var lexer = new JavaLexer(chars);
        var tokens = new CommonTokenStream(lexer);
        var parser = new JavaParser(tokens);
        parser.setBuildParseTree(true);
        JavaParser.CompilationUnitContext compilationUnitContext = parser.compilationUnit();
        return compilationUnitContext.getRuleContext();
    }

    private static void translate(
        CrossReferences crossReferences,
        String inputFile,
        String outputFile
    ) throws IOException {
        var input = new FileInputStream(inputFile);
        var chars = CharStreams.fromStream(input);
        var lexer = new JavaLexer(chars);
        var tokens = new CommonTokenStream(lexer);
        var parser = new JavaParser(tokens);
        parser.setBuildParseTree(true);
        JavaParser.CompilationUnitContext compilationUnitContext = parser.compilationUnit();
        var tree = compilationUnitContext.getRuleContext();
        TokenStreamRewriter tokenStreamRewriter = new TokenStreamRewriter(tokens);
        SymbolTable symbolTable = new SymbolTable();
        var firstPass = new JUnit4to5TranslatorFirstPass(tokens, tokenStreamRewriter, crossReferences, symbolTable);
        firstPass.visit(tree);
        if (!firstPass.isSkip()) {
            var secondPass = new JUnit4to5TranslatorSecondPass(tokens, tokenStreamRewriter, symbolTable);
            secondPass.visit(tree);
            secondPass.saveOutput(Paths.get(outputFile));
        }
    }
}
