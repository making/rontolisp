// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The Basic scheme of RFC 7617 over RFC 4648's base64. What a realm may hold, which
// requests are asked again and which refused, and the field each is answered with
// are a JDK 25's answers to a probe.
package com.sun.net.httpserver;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public abstract class BasicAuthenticator extends Authenticator {

    protected final String realm;

    private final Charset charset;

    public BasicAuthenticator(String realm) {
        this(realm, StandardCharsets.UTF_8);
    }

    public BasicAuthenticator(String realm, Charset charset) {
        if (charset == null) {
            throw new NullPointerException();
        }
        if (realm.isEmpty()) {
            throw new IllegalArgumentException("realm must not be empty");
        }
        if (!quotable(realm)) {
            throw new IllegalArgumentException("realm invalid: " + realm);
        }
        this.realm = realm;
        this.charset = charset;
    }

    // Whether a text can stand between quotes as it is: a backslash takes the character
    // after it, and no quote, control or character past 0xFF stands alone.
    private static boolean quotable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                if (i == text.length()) {
                    return false;
                }
            }
            else if (c == '"' || c < 0x20 || c == 0x7f || c > 0xff) {
                return false;
            }
        }
        return true;
    }

    public String getRealm() {
        return realm;
    }

    public Result authenticate(HttpExchange t) {
        String sent = t.getRequestHeaders().getFirst("Authorization");
        if (sent == null) {
            challenge(t);
            return new Authenticator.Retry(401);
        }
        int space = sent.indexOf(' ');
        if (space < 0 || !sent.substring(0, space).equalsIgnoreCase("Basic")) {
            return new Authenticator.Failure(401);
        }
        String pair = new String(decoded(sent.substring(space + 1)), charset);
        int colon = pair.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("no colon in the credentials");
        }
        String username = pair.substring(0, colon);
        if (checkCredentials(username, pair.substring(colon + 1))) {
            return new Authenticator.Success(new HttpPrincipal(username, realm));
        }
        challenge(t);
        return new Authenticator.Failure(401);
    }

    private void challenge(HttpExchange t) {
        t.getResponseHeaders()
            .set("WWW-Authenticate",
                    "Basic realm=\"" + realm + "\"" + (charset.equals(StandardCharsets.UTF_8) ? ", charset=\"UTF-8\"" : ""));
    }

    // The octets a base64 text of the basic alphabet spells; its padding may be left out.
    private static byte[] decoded(String text) {
        int length = text.length();
        int padding = 0;
        while (padding < 2 && length > 0 && text.charAt(length - 1) == '=') {
            length--;
            padding++;
        }
        int odd = length % 4;
        if (odd == 1 || (padding > 0 && odd + padding != 4)) {
            throw new IllegalArgumentException("credentials cut short");
        }
        byte[] octets = new byte[length / 4 * 3 + (odd == 0 ? 0 : odd - 1)];
        int at = 0;
        int bits = 0;
        int held = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            int six = c >= 'A' && c <= 'Z' ? c - 'A'
                    : c >= 'a' && c <= 'z' ? c - 'a' + 26
                            : c >= '0' && c <= '9' ? c - '0' + 52 : c == '+' ? 62 : c == '/' ? 63 : -1;
            if (six < 0) {
                throw new IllegalArgumentException("credentials in no base64");
            }
            held = (held << 6) | six;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                octets[at++] = (byte) (held >> bits);
            }
        }
        return octets;
    }

    public abstract boolean checkCredentials(String username, String password);
}
