package com.junit4to5.translator.java;

interface Scope {
    String name();

    Scope enclosing();

    void declare(String name, Object value);

    String resolve(String name);

    Object get(String name);

    int depth();
}
