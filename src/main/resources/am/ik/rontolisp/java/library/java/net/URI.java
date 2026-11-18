// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A reference is cut and checked by the rules the front ends share (eval/uri.lisp:
// RFC 3986's cut, the characters java.net.URI takes in each component, its
// resolution); what an authority's host and port are is read here.
package java.net;

import java.nio.charset.StandardCharsets;

public final class URI implements Comparable<URI> {

    private final String text;

    private final String scheme;

    private final String authority;

    private final String path;

    private final String query;

    private final String fragment;

    /** What follows the scheme, up to the fragment. */
    private final String specific;

    private String userInfo;

    private String host;

    private int port = -1;

    public URI(String str) throws URISyntaxException {
        if (str == null) {
            throw new NullPointerException();
        }
        Object refusal = refusal0(str);
        if (refusal != null) {
            throw new URISyntaxException(str, reason0(refusal), index0(refusal));
        }
        String[] parts = split0(str);
        this.text = str;
        this.scheme = parts[0];
        this.fragment = parts[4];
        int from = this.scheme == null ? 0 : this.scheme.length() + 1;
        int to = this.fragment == null ? str.length() : str.length() - this.fragment.length() - 1;
        this.specific = str.substring(from, to);
        if (this.scheme != null && !this.specific.startsWith("/")) {
            // an opaque reference has no component but its scheme and its fragment
            this.authority = null;
            this.path = null;
            this.query = null;
        }
        else {
            this.authority = parts[1];
            this.path = parts[2];
            this.query = parts[3];
            if (this.authority != null) {
                server(this.authority);
            }
        }
    }

    public static URI create(String str) {
        try {
            return new URI(str);
        }
        catch (URISyntaxException refused) {
            throw new IllegalArgumentException(refused.getMessage(), refused);
        }
    }

    /** The user, host and port of an authority naming a server; none of the three of any other. */
    private void server(String authority) {
        int at = authority.lastIndexOf('@');
        String user = at < 0 ? null : authority.substring(0, at);
        String rest = authority.substring(at + 1);
        String name = rest;
        int number = -1;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            name = rest.substring(0, close + 1);
            rest = rest.substring(close + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                return;
            }
            rest = rest.isEmpty() ? null : rest.substring(1);
        }
        else {
            int colon = rest.indexOf(':');
            name = colon < 0 ? rest : rest.substring(0, colon);
            rest = colon < 0 ? null : rest.substring(colon + 1);
            if (!named(name)) {
                return;
            }
        }
        if (rest != null && !rest.isEmpty()) {
            if (rest.length() > 9) {
                return;
            }
            number = 0;
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c < '0' || c > '9') {
                    return;
                }
                number = number * 10 + (c - '0');
            }
        }
        this.userInfo = user;
        this.host = name;
        this.port = number;
    }

    /** Whether a text is a host's name: four numbers of an address, or labels the last of which starts with a letter. */
    private static boolean named(String name) {
        if (name.isEmpty()) {
            return false;
        }
        int dots = 0;
        boolean numbers = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                dots++;
            }
            else if (c < '0' || c > '9') {
                numbers = false;
            }
        }
        if (numbers) {
            if (dots != 3) {
                return false;
            }
            int value = -1;
            for (int i = 0; i <= name.length(); i++) {
                char c = i < name.length() ? name.charAt(i) : '.';
                if (c == '.') {
                    if (value < 0 || value > 255) {
                        return false;
                    }
                    value = -1;
                }
                else {
                    value = (value < 0 ? 0 : value * 10) + (c - '0');
                    if (value > 255) {
                        return false;
                    }
                }
            }
            return true;
        }
        int end = name.endsWith(".") ? name.length() - 1 : name.length();
        int start = 0;
        while (start <= end) {
            int dot = name.indexOf('.', start);
            int stop = dot < 0 || dot > end ? end : dot;
            if (stop == start) {
                return false;
            }
            for (int i = start; i < stop; i++) {
                char c = name.charAt(i);
                boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
                boolean digit = c >= '0' && c <= '9';
                if (!letter && !digit && !(c == '-' && i > start && i < stop - 1)) {
                    return false;
                }
                if (i == start && stop == end && !letter) {
                    return false;
                }
            }
            start = stop + 1;
        }
        return true;
    }

    /** A component with its escapes read as the UTF-8 they spell. */
    private static String decoded(String raw) {
        if (raw == null || raw.indexOf('%') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            if (raw.charAt(i) != '%') {
                out.append(raw.charAt(i++));
                continue;
            }
            int count = 0;
            while (i + 3 * count < raw.length() && raw.charAt(i + 3 * count) == '%') {
                count++;
            }
            byte[] octets = new byte[count];
            for (int k = 0; k < count; k++) {
                octets[k] = (byte) Integer.parseInt(raw.substring(i + 3 * k + 1, i + 3 * k + 3), 16);
            }
            out.append(new String(octets, StandardCharsets.UTF_8));
            i += 3 * count;
        }
        return out.toString();
    }

    public String getScheme() {
        return scheme;
    }

    public boolean isAbsolute() {
        return scheme != null;
    }

    public boolean isOpaque() {
        return path == null;
    }

    public String getRawSchemeSpecificPart() {
        return specific;
    }

    public String getSchemeSpecificPart() {
        return decoded(specific);
    }

    public String getRawAuthority() {
        return authority;
    }

    public String getAuthority() {
        return decoded(authority);
    }

    public String getRawUserInfo() {
        return userInfo;
    }

    public String getUserInfo() {
        return decoded(userInfo);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getRawPath() {
        return path;
    }

    public String getPath() {
        return decoded(path);
    }

    public String getRawQuery() {
        return query;
    }

    public String getQuery() {
        return decoded(query);
    }

    public String getRawFragment() {
        return fragment;
    }

    public String getFragment() {
        return decoded(fragment);
    }

    public URI resolve(URI uri) {
        if (uri.isOpaque() || this.isOpaque()) {
            return uri;
        }
        return create(resolve0(this.text, uri.text));
    }

    public URI resolve(String str) {
        return resolve(create(str));
    }

    /** A text with the case of what is read whatever its case made one: the scheme, the host, an escape's digits. */
    private String folded() {
        StringBuilder out = new StringBuilder();
        int lower = scheme == null ? 0 : scheme.length();
        int from = -1;
        if (host != null) {
            from = text.indexOf(host, lower);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean fold = i < lower || (from >= 0 && i >= from && i < from + host.length())
                    || (i > 0 && text.charAt(i - 1) == '%') || (i > 1 && text.charAt(i - 2) == '%');
            out.append(fold ? Character.toLowerCase(c) : c);
        }
        return out.toString();
    }

    public boolean equals(Object o) {
        return o instanceof URI other && folded().equals(other.folded());
    }

    public int hashCode() {
        return folded().hashCode();
    }

    public int compareTo(URI that) {
        return folded().compareTo(that.folded());
    }

    public String toString() {
        return text;
    }

    private static native Object refusal0(String text);

    private static native String reason0(Object refusal);

    private static native int index0(Object refusal);

    private static native String[] split0(String text);

    private static native String resolve0(String base, String reference);
}
