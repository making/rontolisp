// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net;

import java.io.IOException;

public class MalformedURLException extends IOException {

    public MalformedURLException() {
    }

    public MalformedURLException(String message) {
        super(message);
    }
}
