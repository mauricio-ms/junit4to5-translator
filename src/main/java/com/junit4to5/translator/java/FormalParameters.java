package com.junit4to5.translator.java;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import antlr.java.JavaParser;

class FormalParameters {
    private record Parameter(String type, String identifier, boolean varargs) {
        private Parameter(String type, String identifier) {
            this(type, identifier, false);
        }
    }
    
    private final List<Parameter> parameters;

    public static FormalParameters get(JavaParser.FormalParametersContext ctx) {
        if (ctx == null || ctx.formalParameterList() == null) {
            return new FormalParameters(List.of());
        }

        var formalParameters = ctx.formalParameterList();
        Stream<Parameter> formatParamtersStream = formalParameters.formalParameter().stream()
            .map(p -> new Parameter(TypeResolver.resolve(p.typeType()), p.variableDeclaratorId().getText()));
        Stream<Parameter> lastFormalParameterStream = Stream.of(formalParameters.lastFormalParameter())
            .filter(Objects::nonNull)
            .map(p -> new Parameter(
                TypeResolver.resolve(p.typeType()),
                p.variableDeclaratorId().getText(),
                p.ELLIPSIS() != null));
        return new FormalParameters (
            Stream.concat(formatParamtersStream, lastFormalParameterStream).toList());
    }
    
    private FormalParameters(List<Parameter> parameters) {
        this.parameters = parameters;
    }
    
    public String wideType() {
        List<Parameter> nonJUnitParameters = parameters.stream()
            .filter(p -> !p.type().equals("TestInfo"))
            .toList();

        if (nonJUnitParameters.size() > 1) {
            return "Strings";
        }
        return nonJUnitParameters.get(0).type();
    }

    // Just checking size, the correct would be checking the types also to consider overload methods
    public boolean isCallCompatible(int callArgumentsSize) {
        int parametersSize = parameters.size();
        return parametersSize == callArgumentsSize ||
               parametersSize > 0 &&
               parametersSize < callArgumentsSize &&
               parameters.get(parametersSize - 1).varargs();
    }
    
    public int size() {
        return parameters.size();
    }
}
