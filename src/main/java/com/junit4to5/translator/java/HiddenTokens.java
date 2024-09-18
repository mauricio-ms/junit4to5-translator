package com.junit4to5.translator.java;

import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;

import antlr.java.JavaLexer;

class HiddenTokens {
    private final BufferedTokenStream tokens;

    HiddenTokens(BufferedTokenStream tokens) {
        this.tokens = tokens;
    }

    public Optional<Token> maybePreviousAs(Token token, String previous) {
        List<Token> hiddenTokensToLeft = tokens.getHiddenTokensToLeft(
            token.getTokenIndex(), JavaLexer.HIDDEN);
        if (hiddenTokensToLeft != null && !hiddenTokensToLeft.isEmpty()) {
            Token hiddenToken = hiddenTokensToLeft.get(0);
            if (hiddenToken.getText().startsWith(previous)) {
                return Optional.of(hiddenToken);
            }
        }
        return Optional.empty();
    }

    public Optional<Token> maybeNextAs(Token token, String next) {
        List<Token> hiddenTokensToRight = tokens.getHiddenTokensToRight(
            token.getTokenIndex(), JavaLexer.HIDDEN);
        if (hiddenTokensToRight != null && !hiddenTokensToRight.isEmpty()) {
            Token hiddenToken = hiddenTokensToRight.get(0);
            if (hiddenToken.getText().startsWith(next)) {
                return Optional.of(hiddenToken);
            }
        }
        return Optional.empty();
    }
}
