package com.junit4to5.translator.java;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;

class ParameterAdder {
    private final TokenStreamRewriter rewriter;
    private final HiddenTokens hiddenTokens;

    ParameterAdder(TokenStreamRewriter rewriter, BufferedTokenStream tokens) {
        this.rewriter = rewriter;
        hiddenTokens = new HiddenTokens(tokens);
    }

    public void addAfter(Token token, boolean unique, String parameter) {
        hiddenTokens.maybeNextAs(token, "\n")
            .ifPresentOrElse(
                nlToken -> rewriter.insertAfter(
                    nlToken,
                    "%s%n%s".formatted(
                        generateNewParameter(unique, parameter),
                        nlToken.getText().substring(1))),
                () -> rewriter.insertAfter(
                    token,
                    generateNewParameter(unique, parameter)));
    }

    private String generateNewParameter(boolean unique, String parameter) {
        if (unique) {
            return parameter;
        } else {
            return parameter + ", ";
        }
    }
}
