package com.junit4to5.translator.java;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;

import antlr.java.JavaLexer;

class HiddenTokens {
    private final BufferedTokenStream tokens;

    HiddenTokens(BufferedTokenStream tokens) {
        this.tokens = tokens;
    }

    public boolean contains(Token start, Token end, String value) {
        return IntStream.range(start.getTokenIndex(), end.getTokenIndex())
            .mapToObj(tokenIndex -> tokens.getHiddenTokensToRight(tokenIndex, JavaLexer.HIDDEN))
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .anyMatch(t -> t.getText().contains(value));
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
