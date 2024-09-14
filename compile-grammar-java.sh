cd grammars
java -Xmx500M -cp "../tools/antlr-4.13.2-complete.jar:$CLASSPATH" org.antlr.v4.Tool -visitor -o ../src/main/java/antlr/java -package antlr.java JavaLexer.g4 JavaParser.g4