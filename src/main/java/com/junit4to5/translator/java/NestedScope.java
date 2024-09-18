package com.junit4to5.translator.java;

class NestedScope extends BaseScope {
    private final String type;

    public NestedScope(Scope enclosingScope) {
        this(enclosingScope, "nested");
    }

    public NestedScope(Scope enclosingScope, String type) {
        super(enclosingScope);
        this.type = type;
    }

    @Override
    public String type() {
        return type;
    }
}
