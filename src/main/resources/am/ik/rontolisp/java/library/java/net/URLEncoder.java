// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;

// A text in the form HTML's forms send it in (application/x-www-form-urlencoded): a
// letter, a digit and . - * _ as they are, a space as +, and every other character as
// %XY of each byte the charset makes of it, in upper case. A run of such characters is
// made bytes at once, so a surrogate pair is one character and a lone surrogate the
// charset's replacement. The words of a refusal are a JDK's (.kb/java-frontend.md,
// "java.net.URLEncoder and URLDecoder").
public class URLEncoder {

    private static final String DIGITS = "0123456789ABCDEF";

    private URLEncoder() {
    }

    @Deprecated
    public static String encode(String s) {
        return encode(s, Charset.defaultCharset());
    }

    public static String encode(String s, String enc) throws UnsupportedEncodingException {
        Objects.requireNonNull(enc, "charsetName");
        Charset charset;
        try {
            charset = Charset.forName(enc);
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException(enc);
        }
        return encode(s, charset);
    }

    public static String encode(String s, Charset charset) {
        Objects.requireNonNull(charset, "charset");
        int length = s.length();
        StringBuilder encoded = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            char c = s.charAt(i);
            if (kept(c)) {
                encoded.append(c);
                i++;
            }
            else if (c == ' ') {
                encoded.append('+');
                i++;
            }
            else {
                int start = i;
                while (i < length && !kept(s.charAt(i)) && s.charAt(i) != ' ') {
                    i++;
                }
                for (byte b : s.substring(start, i).getBytes(charset)) {
                    encoded.append('%').append(DIGITS.charAt((b >> 4) & 0xF)).append(DIGITS.charAt(b & 0xF));
                }
            }
        }
        return encoded.toString();
    }

    private static boolean kept(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-'
                || c == '*' || c == '_';
    }

}
