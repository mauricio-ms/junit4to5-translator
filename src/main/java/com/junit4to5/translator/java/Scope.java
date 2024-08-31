package com.junit4to5.translator.java;

public interface Scope {
    String name();

    Scope enclosing();

    void declare(String name, String value);

    String resolve(String name);
}
