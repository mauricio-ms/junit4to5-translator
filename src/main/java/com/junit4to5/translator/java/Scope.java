package com.junit4to5.translator.java;

import java.util.Map;

interface Scope {
    String type();

    Scope enclosing();

    Scope enclosingFor(String type);

    void declare(String name, Object value);

    void declareList(String name, Object value);

    String resolve(String name);

    Object get(String name);

    boolean hasBool(String name);

    Map<String, Object> getSymbols();

    int depth();
}
