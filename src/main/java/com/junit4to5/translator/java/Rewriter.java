package com.junit4to5.translator.java;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.Interval;

class Rewriter {
    private static final int MAX_LINE_LENGTH = 120;
    
    private final TokenStreamRewriter streamRewriter;
    private final HiddenTokens hiddenTokens;
    private final Set<Region> rewrittenRegions;

    record Region(int start, int end) {
        public boolean contains(Region other) {
            return start <= other.start && other.end <= end;
        }
    }

    Rewriter(TokenStreamRewriter streamRewriter, HiddenTokens hiddenTokens) {
        this.streamRewriter = streamRewriter;
        this.hiddenTokens = hiddenTokens;
        rewrittenRegions = new HashSet<>();
    }

    public void insertBefore(Token t, String text) {
        rewrittenRegions.add(new Region(t.getTokenIndex(), t.getTokenIndex() + 1));
        streamRewriter.insertBefore(t, text);
    }

    public void insertAfter(Token t, String text) {
        rewrittenRegions.add(new Region(t.getTokenIndex(), t.getTokenIndex() + 1));
        streamRewriter.insertAfter(t, text);
    }

    public void replace(Token from, Token to, String text) {
        rewrittenRegions.add(new Region(from.getTokenIndex(), to.getTokenIndex() + 1));
        streamRewriter.replace(from, to, text);
    }

    public void replace(Token indexT, String text) {
        rewrittenRegions.add(new Region(indexT.getTokenIndex(), indexT.getTokenIndex() + 1));
        streamRewriter.replace(indexT, text);
    }

    public void delete(Token from, Token to) {
        streamRewriter.delete(from, to);
    }

    public void delete(Token indexT) {
        streamRewriter.delete(indexT);
    }

    public String getText(Interval interval) {
        return streamRewriter.getText(interval);
    }

    public String getText() {
        return streamRewriter.getText();
    }
    
    public boolean requiresFormatting(Interval interval) {
        Region region = new Region(interval.a, interval.b);
        if (rewrittenRegions.stream().anyMatch(region::contains)) {
            int indentation = hiddenTokens.getIndentation(streamRewriter.getTokenStream().get(interval.a));
            return Stream.of(streamRewriter.getText(interval).split("\n"))
                .anyMatch(t -> t.length() + indentation > MAX_LINE_LENGTH);
        }
        return false;
    }
}
