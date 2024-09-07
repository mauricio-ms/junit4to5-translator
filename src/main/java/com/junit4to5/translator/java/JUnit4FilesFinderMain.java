package com.junit4to5.translator.java;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import antlr.java.JavaLexer;
import antlr.java.JavaParser;

public class JUnit4FilesFinderMain {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Usage: junit4to5-translator <path>");
        }

        System.out.println("Searching JUnit4 files ...");
        Path path = Paths.get(args[0]);
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                stream.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(isJUnit4FilePredicate())
                    .forEach(System.out::println);
            }
        } else {
            System.out.println(path);
        }
    }

    private static Predicate<Path> isJUnit4FilePredicate() {
        return inputFile -> {
            try {
                var input = new FileInputStream(inputFile.toString());
                var chars = CharStreams.fromStream(input);
                var lexer = new JavaLexer(chars);
                var tokens = new CommonTokenStream(lexer);
                var parser = new JavaParser(tokens);
                parser.setBuildParseTree(true);
                JavaParser.CompilationUnitContext compilationUnitContext = parser.compilationUnit();
                var tree = compilationUnitContext.getRuleContext();
                JUnit4FilesFinder jUnit4FilesFinder = new JUnit4FilesFinder();
                jUnit4FilesFinder.visit(tree);
                return jUnit4FilesFinder.isJUnit4File() && !jUnit4FilesFinder.isJUnit4TestRule();
            } catch (IOException e) {
                throw new RuntimeException("Error reading the input file " + inputFile + ":", e);
            }
        };
    }
}
