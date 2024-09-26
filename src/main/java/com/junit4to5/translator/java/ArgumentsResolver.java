package com.junit4to5.translator.java;

import java.util.Optional;

import antlr.java.JavaParser;

final class ArgumentsResolver {

    private ArgumentsResolver() {
    }

    public static int resolveSize(JavaParser.ArgumentsContext arguments) {
        return Optional.ofNullable(arguments.expressionList())
            .map(l -> l.expression().size())
            .orElse(0);
    }
}
