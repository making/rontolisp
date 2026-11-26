// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;

// The text of the form HTML's forms send (application/x-www-form-urlencoded): a + is a
// space, a run of %XY (either case) is the bytes the charset decodes -- what is no
// character of it the charset's replacement --, and any other character is itself. A %
// with fewer than two characters after it, and one followed by a character that is no
// hexadecimal digit (the first of the two that is not), are refused in a JDK's words
// (.kb/java-frontend.md, "java.net.URLEncoder and URLDecoder").
public class URLDecoder {

    @Deprecated
    public URLDecoder() {
    }

    @Deprecated
    public static String decode(String s) {
        return decode(s, Charset.defaultCharset());
    }

    // The charset is looked for before the text is read.
    public static String decode(String s, String enc) throws UnsupportedEncodingException {
        if (enc.isEmpty()) {
            throw new UnsupportedEncodingException("URLDecoder: empty string enc parameter");
        }
        Charset charset;
        try {
            charset = Charset.forName(enc);
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException(enc);
        }
        return decode(s, charset);
    }

    public static String decode(String s, Charset charset) {
        Objects.requireNonNull(charset, "Charset");
        int length = s.length();
        StringBuilder decoded = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            char c = s.charAt(i);
            if (c == '+') {
                decoded.append(' ');
                i++;
            }
            else if (c == '%') {
                byte[] bytes = new byte[(length - i) / 3];
                int n = 0;
                while (i < length && s.charAt(i) == '%') {
                    if (i + 2 >= length) {
                        throw new IllegalArgumentException("URLDecoder: Incomplete trailing escape (%) pattern");
                    }
                    int high = digit(s.charAt(i + 1));
                    bytes[n++] = (byte) ((high << 4) | digit(s.charAt(i + 2)));
                    i += 3;
                }
                decoded.append(new String(bytes, 0, n, charset));
            }
            else {
                decoded.append(c);
                i++;
            }
        }
        return decoded.toString();
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - "
                + "not a hexadecimal digit: \"" + c + "\" = " + (int) c);
    }

}
