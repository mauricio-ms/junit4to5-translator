package com.junit4to5.translator.java;

import java.util.Optional;

class NestedScope extends BaseScope {

    public NestedScope(Scope enclosingScope) {
        super(enclosingScope);
    }

    @Override
    public String resolve(String name) {
        return Optional.ofNullable(get(name))
                .orElseGet(() -> super.resolve(name));
    }

    @Override
    public String name() {
        return "nested";
    }
}
