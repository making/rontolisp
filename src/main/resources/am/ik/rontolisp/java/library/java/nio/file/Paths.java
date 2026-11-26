// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.net.URI;

public final class Paths {

    private Paths() {
    }

    public static Path get(String first, String... more) {
        return Path.of(first, more);
    }

    public static Path get(URI uri) {
        return Path.of(uri);
    }
}
