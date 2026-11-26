// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.regex;

public interface MatchResult {

    int start();

    int start(int group);

    int end();

    int end(int group);

    String group();

    String group(int group);

    int groupCount();

    default int start(String name) {
        throw new UnsupportedOperationException();
    }

    default int end(String name) {
        throw new UnsupportedOperationException();
    }

    default String group(String name) {
        throw new UnsupportedOperationException();
    }

    default boolean hasMatch() {
        throw new UnsupportedOperationException();
    }
}
