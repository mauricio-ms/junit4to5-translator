JAR_LOCATION=./build/libs/junit4to5-translator-1.0-SNAPSHOT.jar
echo "Searching JUnit4 files ..."
java -cp $JAR_LOCATION com.junit4to5.translator.java.JUnit4FilesFinderMain $1 | java -cp $JAR_LOCATION com.junit4to5.translator.java.JUnit4To5TranslatorMain