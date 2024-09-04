package com.junit4to5.translator.java;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

abstract class BaseScope implements Scope {
    private static final String THIS = "this.";

    private final Map<String, Object> symbols;
    private final Scope enclosingScope;

    public BaseScope(Scope enclosingScope) {
        symbols = new HashMap<>();
        this.enclosingScope = enclosingScope;
    }

    @Override
    public Scope enclosing() {
        return enclosingScope;
    }

    @Override
    public void declare(String name, Object value) {
        if (symbols.containsKey(name)) {
            throw new RuntimeException("The symbol '" + name + "' is already declared.");
        }
        symbols.put(name, value);
    }

    @Override
    public String resolve(String name) {
        return (String) Optional.ofNullable(symbols.get(name))
                .or(() -> Optional.ofNullable(enclosingScope)
                        .map(s -> s.resolve(name))
                        .or(() -> Optional.of(name)
                                    .filter(s -> s.startsWith(THIS))
                                    .map(s -> resolve(s.replace(THIS, "")))))
                .orElse(null);
    }

    @Override
    public int depth() {
        int d = 0;
        Scope current = this;
        while (current != null) {
            current = current.enclosing();
            d++;
        }
        return d;
    }

    public Object get(String name) {
        return symbols.get(name);
    }

    @Override
    public String toString() {
        return name() + ":" + symbols;
    }
}
