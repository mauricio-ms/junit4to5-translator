package com.junit4to5.translator.java;

class GlobalScope extends BaseScope implements Scope {
    public GlobalScope() {
        super(null);
    }

    @Override
    public String name() {
        return "global";
    }
}
