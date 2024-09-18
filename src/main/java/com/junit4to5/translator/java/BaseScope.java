package com.junit4to5.translator.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class BaseScope implements Scope {
    private static final String THIS = "this.";

    private final Scope enclosingScope;
    private final Map<String, Object> symbols;

    public BaseScope(Scope enclosingScope) {
        this.enclosingScope = enclosingScope;
        symbols = new HashMap<>();
    }

    @Override
    public Scope enclosing() {
        return enclosingScope;
    }

    @Override
    public Scope enclosingFor(String type) {
        Scope current = this;
        while (current != null) {
            if (type.equals(current.type())) {
                break;
            }
            current = current.enclosing();
        }
        return current;
    }

    @Override
    public void declare(String name, Object value) {
        if (symbols.containsKey(name)) {
            throw new RuntimeException("The symbol '" + name + "' is already declared.");
        }
        symbols.put(name, value);
    }

    @Override
    public void declareList(String name, Object value) {
        if (!symbols.containsKey(name)) {
            symbols.put(name, new ArrayList<>());
        }
        var values = (List<Object>) symbols.get(name);
        values.add(value);
    }

    @Override
    public String resolve(String name) {
        if (name.startsWith(THIS)) {
            return resolve(name.replace(THIS, ""));
        }
        return (String) Optional.ofNullable(symbols.get(name))
                .or(() -> Optional.ofNullable(enclosingScope)
                        .map(s -> s.resolve(name)))
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

    @Override
    public Object get(String name) {
        return symbols.get(name);
    }

    @Override
    public boolean hasBool(String name) {
        Object v = get(name);
        return v != null && (boolean) v;
    }

    @Override
    public String toString() {
        return type() + ":" + symbols;
    }
}
