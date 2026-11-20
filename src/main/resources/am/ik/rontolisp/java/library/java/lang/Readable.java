// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

import java.io.IOException;
import java.nio.CharBuffer;

// A source of characters, read into a buffer: a Reader is one.
public interface Readable {

    int read(CharBuffer cb) throws IOException;
}
