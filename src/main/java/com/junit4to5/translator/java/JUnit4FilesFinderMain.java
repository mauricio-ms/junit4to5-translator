package com.junit4to5.translator.java;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import antlr.java.JavaLexer;
import antlr.java.JavaParser;

public class JUnit4FilesFinderMain {
    private static final String SRC_TEST_JAVA = "/src/test/java/";

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Usage: junit4to5-translator <path>");
        }

        Path path = Paths.get(args[0]);
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                stream.filter(Files::isRegularFile)
                    .map(Path::toString)
                    .filter(f -> f.contains(SRC_TEST_JAVA) && f.endsWith(".java"))
                    .forEach(inputFile -> {
                        JUnit4FilesFinder jUnit4FilesFinder = buildJUnit4FilesFinder(inputFile);
                        if (!jUnit4FilesFinder.isJUnit4TestRule() && !jUnit4FilesFinder.isJUnit5File()) {
                            String fileType = jUnit4FilesFinder.isJUnit4File() ? "JUNIT4" : "HELPER";
                            System.out.printf("%s:%s%n", fileType, inputFile);
                        }
                    });
            }
        } else {
            System.out.println(path);
        }
    }

    private static JUnit4FilesFinder buildJUnit4FilesFinder(String inputFile) {
        try {
            var input = new FileInputStream(inputFile);
            var chars = CharStreams.fromStream(input);
            var lexer = new JavaLexer(chars);
            var tokens = new CommonTokenStream(lexer);
            var parser = new JavaParser(tokens);
            parser.setBuildParseTree(true);
            JavaParser.CompilationUnitContext compilationUnitContext = parser.compilationUnit();
            var tree = compilationUnitContext.getRuleContext();
            JUnit4FilesFinder jUnit4FilesFinder = new JUnit4FilesFinder();
            jUnit4FilesFinder.visit(tree);
            return jUnit4FilesFinder;
        } catch (IOException e) {
            throw new RuntimeException("Error reading the input file " + inputFile + ":", e);
        }
    }
}
