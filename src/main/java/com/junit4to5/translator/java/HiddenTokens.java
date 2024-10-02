package com.junit4to5.translator.java;

import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;

import antlr.java.JavaLexer;

class HiddenTokens {
    private final BufferedTokenStream tokens;

    HiddenTokens(BufferedTokenStream tokens) {
        this.tokens = tokens;
    }

    public Integer getIndentation(Token token) {
        return maybeIndentation(token)
            .orElseThrow(() -> new IllegalStateException("No indentation detected before: " + token));
    }

    public Optional<Integer> maybeIndentation(Token token) {
        return maybePreviousAs(token, "\n")
            .map(Token::getText)
            .map(hiddenToken -> hiddenToken.length() - hiddenToken.lastIndexOf('\n') - 1);
    }

    public Optional<Token> maybePreviousAs(Token token, String previous) {
        List<Token> hiddenTokensToLeft = tokens.getHiddenTokensToLeft(
            token.getTokenIndex(), JavaLexer.HIDDEN);
        if (hiddenTokensToLeft != null && !hiddenTokensToLeft.isEmpty()) {
            return hiddenTokensToLeft.stream()
                .filter(hiddenToken -> hiddenToken.getText().contains(previous))
                .findFirst();
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

    public String getText(Interval interval) {
        return tokens.getText(interval);
    }
}
