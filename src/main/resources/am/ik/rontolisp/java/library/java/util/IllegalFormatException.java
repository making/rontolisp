// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The run time raises the subclasses with the language's message; none has a public
// constructor here.
public class IllegalFormatException extends IllegalArgumentException {

    IllegalFormatException(String message) {
        super(message);
    }
}
