package com.junit4to5.translator.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

class CrossReferences {

    private final Map<String, Integer> values;
    private final Map<String, List<Method>> methods;
    
    private static final class Method {
        private final FormalParameters formalParameters;
        private int usages;

        Method(FormalParameters formalParameters) {
            this.formalParameters = formalParameters;
        }

        boolean isCallCompatible(int callArgumentsSize) {
            return formalParameters.isCallCompatible(callArgumentsSize);
        }

        public int usages() {
            return usages;
        }

        void incrementUsage() {
            usages++;
        }
    }

    CrossReferences() {
        values = new HashMap<>();
        methods = new HashMap<>();
    }

    public void addType(String type) {
        values.put(type, 0);
    }

    public void addMethod(String type, String methodIdentifier, FormalParameters formalParameters) {
        String methodKey = buildMethodKey(type, methodIdentifier);
        methods.computeIfAbsent(methodKey, k -> new ArrayList<>());
        methods.get(methodKey).add(new Method(formalParameters));
    }

    public boolean hasType(String type) {
        return values.containsKey(type);
    }

    public boolean hasMethod(String type, String methodIdentifier, int argumentsSize) {
        return Optional.ofNullable(methods.get(buildMethodKey(type, methodIdentifier)))
            .stream()
            .flatMap(List::stream)
            .anyMatch(m -> m.isCallCompatible(argumentsSize));
    }

    public boolean hasCrossReference(String type) {
        return values.getOrDefault(type, 0) > 0;
    }

    public boolean hasCrossReference(String type, String methodIdentifier, int argumentsSize) {
        return Optional.ofNullable(methods.get(buildMethodKey(type, methodIdentifier)))
            .stream()
            .flatMap(List::stream)
            .filter(m -> m.isCallCompatible(argumentsSize))
            .anyMatch(m -> m.usages() > 0);
    }

    public void incrementType(String type) {
        if (!values.containsKey(type)) {
            throw new IllegalStateException("Unknown type cross reference: " + type);
        }
        values.put(type, values.get(type) + 1);
    }

    public void incrementMethod(String type, String methodIdentifier, int argumentsSize) {
        String methodKey = buildMethodKey(type, methodIdentifier);
        if (!methods.containsKey(methodKey)) {
            throw new IllegalStateException("Unknown method cross reference: " + methodKey);
        }
        Method method = methods.get(methodKey).stream()
            .filter(m -> m.isCallCompatible(argumentsSize))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No compatible method cross reference: " + methodKey));
        method.incrementUsage();
    }

    public void incrementMethods(String type, String methodIdentifier) {
        String methodKey = buildMethodKey(type, methodIdentifier);
        if (methods.containsKey(methodKey)) {
            methods.get(methodKey).forEach(Method::incrementUsage);
        }
    }

    private static String buildMethodKey(String type, String methodIdentifier) {
        return "%s#%s".formatted(type, methodIdentifier);
    }

    @Override
    public String toString() {
        return "CrossReferences{" +
               "values=" + values +
               '}';
    }
}
