package com.junit4to5.translator.java;

import java.util.function.Supplier;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;

class ParameterAdder {
    private final Rewriter rewriter;
    private final HiddenTokens hiddenTokens;

    ParameterAdder(Rewriter rewriter, BufferedTokenStream tokens) {
        this.rewriter = rewriter;
        hiddenTokens = new HiddenTokens(tokens);
    }
    
    public void addBefore(Token token, boolean unique, String parameter) {
        hiddenTokens.maybePreviousAs(token, "\n")
            .ifPresentOrElse(
                nlToken -> rewriter.insertBefore(
                    nlToken,
                    "%s%s".formatted(
                        generateBeforeParameter(() -> ",%n%8s".formatted(""), unique, parameter),
                        nlToken.getText().substring(1))),
                () -> rewriter.insertBefore(
                    token,
                    generateBeforeParameter(() -> ", ", unique, parameter)));
    }

    private String generateBeforeParameter(Supplier<String> prefix, boolean unique, String parameter) {
        if (unique) {
            return parameter;
        } else {
            return prefix.get() + parameter;
        }
    }

    public void addAfter(Token token, boolean unique, String parameter) {
        hiddenTokens.maybeNextAs(token, "\n")
            .ifPresentOrElse(
                nlToken -> rewriter.insertAfter(
                    nlToken,
                    "%s%n%s".formatted(
                        generateAfterParameter(unique, parameter),
                        nlToken.getText().substring(1))),
                () -> rewriter.insertAfter(
                    token,
                    generateAfterParameter(unique, parameter)));
    }

    private String generateAfterParameter(boolean unique, String parameter) {
        if (unique) {
            return parameter;
        } else {
            return parameter + ", ";
        }
    }
}
