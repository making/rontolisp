// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import rontolisp.internal.Resources;

// A locator: a protocol of the ones a JDK 25 knows, an authority (its user, host and
// port), a path, a query and a fragment, read and refused as a JDK's answers show
// (.kb/java-frontend.md, "Resources"). A URL a class or a loader answers for a
// resource names the file where the program was compiled -- file: for the source tree
// and a directory of the source path, jar:file: for a jar -- and holds the octets the
// program carries, which openStream reads; any other URL has nothing to read here.
public final class URL implements java.io.Serializable {

    private final String protocol;

    private final String host;

    private final int port;

    private final String authority;

    private final String userInfo;

    private final String path;

    private final String query;

    private final String ref;

    // the resource's octets, one character an octet, or null for a URL naming none
    private final String octets;

    public URL(String spec) throws MalformedURLException {
        if (spec == null) {
            throw new MalformedURLException("Cannot invoke \"String.length()\" because \"spec\" is null");
        }
        int start = 0;
        int limit = spec.length();
        while (limit > 0 && spec.charAt(limit - 1) <= ' ') {
            limit--;
        }
        while (start < limit && spec.charAt(start) <= ' ') {
            start++;
        }
        if (spec.regionMatches(true, start, "url:", 0, 4)) {
            start += 4;
        }
        String scheme = null;
        if (start < limit && spec.charAt(start) != '#') {
            for (int i = start; i < limit && spec.charAt(i) != '/'; i++) {
                if (spec.charAt(i) == ':') {
                    String named = spec.substring(start, i);
                    if (isProtocol(named)) {
                        scheme = lowered(named);
                        start = i + 1;
                    }
                    break;
                }
            }
        }
        if (scheme == null) {
            throw new MalformedURLException("no protocol: " + spec);
        }
        known(scheme);
        String fragment = null;
        int hash = spec.indexOf('#', start);
        if (hash >= 0 && hash < limit) {
            fragment = spec.substring(hash + 1, limit);
            limit = hash;
        }
        String rest = spec.substring(start, limit);
        String named = null;
        String user = null;
        String server = "";
        int number = -1;
        if (scheme.equals("jar")) {
            int bang = rest.indexOf("!/");
            if (bang < 0) {
                throw new MalformedURLException("no !/ in spec");
            }
            try {
                new URL(rest.substring(0, bang));
            }
            catch (MalformedURLException ex) {
                throw new MalformedURLException("invalid url:");
            }
        }
        else if (scheme.equals("mailto")) {
            if (rest.isEmpty()) {
                throw new MalformedURLException("No email address");
            }
        }
        else if (rest.startsWith("//")) {
            int end = 2;
            while (end < rest.length() && rest.charAt(end) != '/' && rest.charAt(end) != '?') {
                end++;
            }
            named = rest.substring(2, end);
            rest = rest.substring(end);
            String hostPort = named;
            int at = named.indexOf('@');
            if (at >= 0 && at != named.lastIndexOf('@')) {
                hostPort = null;
            }
            else if (at >= 0) {
                user = named.substring(0, at);
                hostPort = named.substring(at + 1);
            }
            if (hostPort != null) {
                String portText = null;
                if (hostPort.startsWith("[")) {
                    int close = hostPort.indexOf(']');
                    if (close < 0 || (close + 1 < hostPort.length() && hostPort.charAt(close + 1) != ':')) {
                        throw new MalformedURLException("Invalid authority field: " + named);
                    }
                    server = hostPort.substring(0, close + 1);
                    if (close + 1 < hostPort.length()) {
                        portText = hostPort.substring(close + 2);
                    }
                }
                else {
                    int colon = hostPort.indexOf(':');
                    server = colon < 0 ? hostPort : hostPort.substring(0, colon);
                    if (colon >= 0) {
                        portText = hostPort.substring(colon + 1);
                    }
                    for (int i = 0; i < server.length(); i++) {
                        if (!hostCharacter(server.charAt(i))) {
                            throw new MalformedURLException("Illegal character found in host: '" + server.charAt(i)
                                    + "'");
                        }
                    }
                }
                if (portText != null && !portText.isEmpty()) {
                    number = portNumber(portText);
                }
            }
        }
        int question = rest.indexOf('?');
        this.protocol = scheme;
        this.host = server;
        this.port = number;
        this.authority = named;
        this.userInfo = user;
        this.path = question < 0 ? rest : rest.substring(0, question);
        this.query = question < 0 ? null : rest.substring(question + 1);
        this.ref = fragment;
        this.octets = null;
    }

    public URL(String protocol, String host, int port, String file) throws MalformedURLException {
        String scheme = lowered(protocol);
        known(scheme);
        if (port < -1) {
            throw new MalformedURLException("Invalid port number :" + port);
        }
        String server = host != null && host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        int hash = file.indexOf('#');
        String rest = hash < 0 ? file : file.substring(0, hash);
        int question = rest.indexOf('?');
        this.protocol = scheme;
        this.host = server;
        this.port = port;
        this.authority = server == null ? null : port == -1 ? server : server + ":" + port;
        this.userInfo = null;
        this.path = question < 0 ? rest : rest.substring(0, question);
        this.query = question < 0 ? null : rest.substring(question + 1);
        this.ref = hash < 0 ? null : file.substring(hash + 1);
        this.octets = null;
    }

    public URL(String protocol, String host, String file) throws MalformedURLException {
        this(protocol, host, -1, file);
    }

    private URL(String protocol, String authority, String path, String octets) {
        this.protocol = protocol;
        this.host = "";
        this.port = -1;
        this.authority = authority;
        this.userInfo = null;
        this.path = path;
        this.query = null;
        this.ref = null;
        this.octets = octets;
    }

    // a protocol's name: a letter, then letters, digits, '+', '-' and '.'
    private static boolean isProtocol(String name) {
        if (name.isEmpty() || !letter(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!letter(c) && (c < '0' || c > '9') && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean letter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static String lowered(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    // the protocols a JDK 25 has a handler for
    private static void known(String protocol) throws MalformedURLException {
        switch (protocol) {
            case "file", "ftp", "http", "https", "jar", "jmod", "jrt", "mailto" -> {
            }
            default -> throw new MalformedURLException("unknown protocol: " + protocol);
        }
    }

    private static boolean hostCharacter(char c) {
        return c > ' ' && c != '"' && c != '<' && c != '>' && c != '\\' && c != ']' && c != '^' && c != '`'
                && c != '{' && c != '|' && c != '}' && c != 0x7f;
    }

    // a port's digits, a sign ahead of them allowed; refused at the first character that
    // is no digit or that takes the number past an int
    private static int portNumber(String text) throws MalformedURLException {
        boolean negative = text.charAt(0) == '-';
        int i = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        if (i == text.length()) {
            throw portRefused(text, i);
        }
        // the number's negative, which reaches an int's least value
        int value = 0;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9' || value < -214748364
                    || value * 10 < (negative ? Integer.MIN_VALUE : -Integer.MAX_VALUE) + (c - '0')) {
                throw portRefused(text, i);
            }
            value = value * 10 - (c - '0');
        }
        int port = negative ? value : -value;
        if (port < -1) {
            throw new MalformedURLException("Invalid port number :" + port);
        }
        return port;
    }

    private static MalformedURLException portRefused(String text, int index) {
        return new MalformedURLException("Error at index " + index + " in: \"" + text + "\"");
    }

    // what the lowering makes of a resource found: a URL of where it was compiled, in
    // the source tree (kind 0), a directory of the source path (1) or a jar (2), its
    // path written as a source launcher's loaders write it, and its octets
    static URL located(int kind, String place, String name, String octets) {
        return kind == 2 ? new URL("jar", null, "file:" + escaped(place, true) + "!/" + escaped(name, true), octets)
                : new URL("file", "", escaped(place + "/" + name, kind == 1), octets);
    }

    // the URLs of a resource every place holds, for getResources
    static ArrayList<URL> none() {
        return new ArrayList<>();
    }

    static ArrayList<URL> more(ArrayList<URL> urls, URL url) {
        urls.add(url);
        return urls;
    }

    static Enumeration<URL> listed(ArrayList<URL> urls) {
        return Collections.enumeration(urls);
    }

    // a path with the characters a URL cannot hold escaped: the source tree's in
    // capitals, by each character's UTF-8; a class path's in small letters, ';' and '='
    // too, and a character past the first 65536 as its two surrogates' three octets each
    private static String escaped(String path, boolean classPath) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            int c = path.charAt(i);
            if (c < 0x80) {
                if (c <= ' ' || c == 0x7f || "\"#%<>?[\\]^`{|}".indexOf(c) >= 0
                        || (classPath && (c == ';' || c == '='))) {
                    octet(out, c, classPath);
                }
                else {
                    out.append((char) c);
                }
            }
            else if (classPath && c > 0xffff) {
                utf8(out, 0xd800 + ((c - 0x10000) >> 10), true);
                utf8(out, 0xdc00 + ((c - 0x10000) & 0x3ff), true);
            }
            else {
                utf8(out, c, classPath);
            }
        }
        return out.toString();
    }

    private static void utf8(StringBuilder out, int c, boolean small) {
        if (c < 0x800) {
            octet(out, 0xc0 | (c >> 6), small);
        }
        else if (c < 0x10000) {
            octet(out, 0xe0 | (c >> 12), small);
            octet(out, 0x80 | ((c >> 6) & 0x3f), small);
        }
        else {
            octet(out, 0xf0 | (c >> 18), small);
            octet(out, 0x80 | ((c >> 12) & 0x3f), small);
            octet(out, 0x80 | ((c >> 6) & 0x3f), small);
        }
        octet(out, 0x80 | (c & 0x3f), small);
    }

    private static void octet(StringBuilder out, int octet, boolean small) {
        String digits = small ? "0123456789abcdef" : "0123456789ABCDEF";
        out.append('%').append(digits.charAt(octet >> 4)).append(digits.charAt(octet & 0xf));
    }

    public String getProtocol() {
        return protocol;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getDefaultPort() {
        return switch (protocol) {
            case "http" -> 80;
            case "https" -> 443;
            case "ftp" -> 21;
            default -> -1;
        };
    }

    public String getAuthority() {
        return authority;
    }

    public String getUserInfo() {
        return userInfo;
    }

    public String getPath() {
        return path;
    }

    public String getQuery() {
        return query;
    }

    public String getFile() {
        return query == null ? path : path + "?" + query;
    }

    public String getRef() {
        return ref;
    }

    public URI toURI() throws URISyntaxException {
        return new URI(toString());
    }

    // the octets of a resource the program carries; there is nothing else to read
    public InputStream openStream() throws IOException {
        if (octets == null) {
            throw new FileNotFoundException(toExternalForm() + " (no resource the program carries)");
        }
        return Resources.stream(octets);
    }

    // a connection reading what openStream reads
    public URLConnection openConnection() throws IOException {
        return new URLConnection.Carried(this);
    }

    public boolean sameFile(URL other) {
        return other != null && protocol.equals(other.protocol) && getFile().equals(other.getFile())
                && (host == null ? other.host == null : other.host != null && host.equalsIgnoreCase(other.host))
                && portOrDefault() == other.portOrDefault();
    }

    private int portOrDefault() {
        return port == -1 ? getDefaultPort() : port;
    }

    public boolean equals(Object o) {
        return o instanceof URL other && sameFile(other)
                && (ref == null ? other.ref == null : ref.equals(other.ref));
    }

    // a number of our own, equal for equal URLs
    public int hashCode() {
        return protocol.hashCode() + (host == null ? 0 : host.toLowerCase().hashCode()) + getFile().hashCode()
                + portOrDefault() + (ref == null ? 0 : ref.hashCode());
    }

    public String toExternalForm() {
        return protocol + ":" + (authority == null || authority.isEmpty() ? "" : "//" + authority) + getFile()
                + (ref == null ? "" : "#" + ref);
    }

    public String toString() {
        return toExternalForm();
    }
}
