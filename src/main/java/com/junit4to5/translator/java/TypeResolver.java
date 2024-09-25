package com.junit4to5.translator.java;

import java.util.Optional;

import org.antlr.v4.runtime.RuleContext;

import antlr.java.JavaParser;

final class TypeResolver {

    private TypeResolver() {
    }

    public static String resolve(JavaParser.TypeTypeContext type) {
        return Optional.ofNullable(type)
            .map(t -> Optional.ofNullable(t.classOrInterfaceType())
                .map(JavaParser.ClassOrInterfaceTypeContext::typeIdentifier)
                .map(RuleContext::getText)
                .orElseGet(() -> t.primitiveType().getText()))
            .orElse("unknown");
    }
}
