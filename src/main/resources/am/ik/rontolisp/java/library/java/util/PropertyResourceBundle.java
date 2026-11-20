// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

// The lines of a .properties text as a bundle: a stream's octets read as UTF-8, or as
// ISO 8859-1 where they are no UTF-8. Its keys are a HashMap's made of the Properties
// the text was read into.
public class PropertyResourceBundle extends ResourceBundle {

    private final Map<String, Object> lookup;

    public PropertyResourceBundle(InputStream stream) throws IOException {
        this(read(new StringReader(decoded(stream.readAllBytes()))));
    }

    public PropertyResourceBundle(Reader reader) throws IOException {
        this(read(reader));
    }

    PropertyResourceBundle(Map<String, Object> lookup) {
        this.lookup = lookup;
    }

    private static Map<String, Object> read(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        return new HashMap(properties);
    }

    // UTF-8 where every sequence is well formed (the Unicode standard's table 3-7),
    // else one character an octet
    private static String decoded(byte[] octets) {
        int at = 0;
        while (at < octets.length) {
            int lead = octets[at] & 0xff;
            int length = lead < 0x80 ? 1 : lead >= 0xc2 && lead <= 0xdf ? 2 : lead >= 0xe0 && lead <= 0xef ? 3
                    : lead >= 0xf0 && lead <= 0xf4 ? 4 : 0;
            if (length == 0 || at + length > octets.length) {
                return latin(octets);
            }
            for (int i = 1; i < length; i++) {
                int next = octets[at + i] & 0xff;
                int low = 0x80;
                int high = 0xbf;
                if (i == 1) {
                    low = lead == 0xe0 ? 0xa0 : lead == 0xf0 ? 0x90 : 0x80;
                    high = lead == 0xed ? 0x9f : lead == 0xf4 ? 0x8f : 0xbf;
                }
                if (next < low || next > high) {
                    return latin(octets);
                }
            }
            at += length;
        }
        return new String(octets, StandardCharsets.UTF_8);
    }

    private static String latin(byte[] octets) {
        return new String(octets, StandardCharsets.ISO_8859_1);
    }

    public Object handleGetObject(String key) {
        if (key == null) {
            throw new NullPointerException();
        }
        return lookup.get(key);
    }

    public Enumeration<String> getKeys() {
        return ResourceBundle.keys(lookup.keySet(), parent);
    }

    protected Set<String> handleKeySet() {
        return lookup.keySet();
    }
}
