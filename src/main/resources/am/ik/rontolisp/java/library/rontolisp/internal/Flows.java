// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import java.util.stream.Stream;

// The streams of a String, which is no class of this source.
public final class Flows {

    private Flows() {
    }

    // String.chars and String.codePoints: a string's elements are its code points
    public static IntStream chars(String s) {
        return IntFlow.range(0, s.length() - 1L).map(i -> s.charAt(i));
    }

    // String.lines: each line without what ends it, a line feed, a carriage return or
    // the two; what follows the last of them only when it is not empty
    public static Stream<String> lines(String s) {
        int length = s.length();
        return ObjectFlow.over(new Iterator<String>() {
            private int at;

            public boolean hasNext() {
                return at < length;
            }

            public String next() {
                if (at >= length) {
                    throw new NoSuchElementException();
                }
                int end = at;
                while (end < length && s.charAt(end) != '\n' && s.charAt(end) != '\r') {
                    end++;
                }
                String line = s.substring(at, end);
                if (end < length && s.charAt(end) == '\r' && end + 1 < length && s.charAt(end + 1) == '\n') {
                    end++;
                }
                at = end + 1;
                return line;
            }
        });
    }
}
