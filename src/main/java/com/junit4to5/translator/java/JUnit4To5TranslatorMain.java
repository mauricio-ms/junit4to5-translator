package com.junit4to5.translator.java;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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

    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            Path argPath = Path.of(args[0]);
            Function<String, String> outputFn = inputFile ->
                "output/" + Path.of(inputFile).subpath(1, 2);
            if (Files.isDirectory(argPath)) {
                try (Stream<Path> filesStream = Files.list(argPath)) {
                    translate(
                        filesStream.map(Path::toString).toList(),
                        outputFn);
                }
            } else {
                translate(new CrossReferences(), args[0], outputFn.apply(args[0]));
            }
            return;
        }
        Scanner standardInputScanner = new Scanner(new BufferedInputStream(System.in), StandardCharsets.UTF_8);

        List<String> inputFiles = new ArrayList<>();
        while (standardInputScanner.hasNextLine()) {
            inputFiles.add(standardInputScanner.nextLine());
        }

        translate(inputFiles, Function.identity());
    }

    private static void translate(
        List<String> inputFiles,
        Function<String, String> outputPathFn
    ) throws IOException {
        System.out.println("Computing cross references ...");
        CrossReferences crossReferences = findCrossReferences(inputFiles);
        for (String inputFile : inputFiles) {
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
