package com.junit4to5.translator.java;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Scanner;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.TokenStreamRewriter;

import antlr.java.JavaLexer;
import antlr.java.JavaParser;

public class JUnit4To5TranslatorMain {

    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            translate(args[0], "output/Test.java");
            return;
        }
        Scanner standardInputScanner = new Scanner(new BufferedInputStream(System.in), StandardCharsets.UTF_8);

        while (standardInputScanner.hasNextLine()) {
            String inputFile = standardInputScanner.nextLine();
            System.out.println(">> " + inputFile);
            translate(inputFile, inputFile);
        }
    }

    private static void translate(String inputFile, String outputFile) throws IOException {
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
        var firstPass = new JUnit4to5TranslatorFirstPass(tokens, tokenStreamRewriter, symbolTable);
        firstPass.visit(tree);
        if (!firstPass.isSkip()) {
            var secondPass = new JUnit4to5TranslatorSecondPass(tokens, tokenStreamRewriter, symbolTable);
            secondPass.visit(tree);
            secondPass.saveOutput(Paths.get(outputFile));
        }
    }
}
