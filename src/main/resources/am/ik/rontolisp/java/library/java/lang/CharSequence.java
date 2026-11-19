// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

import java.util.stream.IntStream;
import rontolisp.internal.Flows;

public interface CharSequence {

    int length();

    char charAt(int index);

    CharSequence subSequence(int start, int end);

    default boolean isEmpty() {
        return length() == 0;
    }

    default IntStream chars() {
        return Flows.chars(toString());
    }

    default IntStream codePoints() {
        return Flows.chars(toString());
    }
}
