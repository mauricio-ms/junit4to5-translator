package com.junit4to5.translator.java;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import antlr.java.JavaParser;
import antlr.java.JavaParserBaseVisitor;

abstract class BaseJUnit4To5Pass extends JavaParserBaseVisitor<Void> {
    static final String CLASS_SCOPE = "class";
    static final String TEST_NAME_RULE = "TEST_NAME_RULE";

    Optional<Token> maybePublicToken(Stream<JavaParser.ClassOrInterfaceModifierContext> modifiersStream) {
        return modifiersStream
            .map(JavaParser.ClassOrInterfaceModifierContext::PUBLIC)
            .filter(Objects::nonNull)
            .map(TerminalNode::getSymbol)
            .findFirst();
    }

    void declareInstanceVariables(
        JavaParser.ClassBodyDeclarationContext classBodyDeclaration,
        Scope currentScope
    ) {
        Optional.ofNullable(classBodyDeclaration.memberDeclaration())
            .map(JavaParser.MemberDeclarationContext::fieldDeclaration)
            .ifPresent(fieldDeclaration -> {
                String variableType = getFieldDeclarationType(classBodyDeclaration, fieldDeclaration);
                fieldDeclaration.variableDeclarators().variableDeclarator()
                    .forEach(v -> currentScope.declare(v.variableDeclaratorId().getText(), variableType));
            });
    }

    private String getFieldDeclarationType(
        JavaParser.ClassBodyDeclarationContext classBodyDeclaration,
        JavaParser.FieldDeclarationContext fieldDeclaration
    ) {
        return isTestNameRule(classBodyDeclaration, fieldDeclaration) ?
            TEST_NAME_RULE :
            TypeResolver.resolve(fieldDeclaration.typeType());
    }

    boolean isTestNameRule(
        JavaParser.ClassBodyDeclarationContext classBodyDeclaration,
        JavaParser.FieldDeclarationContext fieldDeclaration
    ) {
        boolean isRule = getAnnotationsStream(classBodyDeclaration)
            .anyMatch(a -> a.qualifiedName().getText().equals("Rule"));
        if (isRule) {
            return Optional.ofNullable(fieldDeclaration.typeType().classOrInterfaceType())
                .filter(t -> t.getText().equals("TestName"))
                .isPresent();
        }
        return false;
    }

    Stream<JavaParser.AnnotationContext> getAnnotationsStream(JavaParser.ClassBodyDeclarationContext ctx) {
        return ctx.modifier().stream()
            .map(JavaParser.ModifierContext::classOrInterfaceModifier)
            .filter(Objects::nonNull)
            .map(JavaParser.ClassOrInterfaceModifierContext::annotation)
            .filter(Objects::nonNull);
    }
}
