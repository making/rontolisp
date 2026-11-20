// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

// A resource the lowering found: its octets travel with the program as a text of one
// character an octet, made a stream of its own at every read.
public final class Resources {

    private Resources() {
    }

    public static InputStream stream(String octets) {
        byte[] bytes = new byte[octets.length()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) octets.charAt(i);
        }
        return new ByteArrayInputStream(bytes);
    }
}
