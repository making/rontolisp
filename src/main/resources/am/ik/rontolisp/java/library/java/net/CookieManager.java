// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A cookie handler over a store and a policy. What the specification leaves open was
// read off a JDK 25's answers (java-spec.yaml, the cookies' case): put keeps the
// cookies of each Set-Cookie and Set-Cookie2 field, a cookie of no domain the host's
// (a host of one label under .local) and of no path the directory of the URI's path,
// a port list of no ports the URI's port, a cookie whose ports leave the URI's out
// none, a field it cannot read none; get answers the cookies the store has for the
// URI whose path the URI's path starts with, a secure one only over https, an
// HTTP-only one only over http and https, the longest path first, as one Cookie list,
// $Version="1" ahead of it when the first is of version 1.
package java.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CookieManager extends CookieHandler {

    private final CookieStore store;

    private CookiePolicy policy;

    public CookieManager() {
        this(null, null);
    }

    public CookieManager(CookieStore store, CookiePolicy cookiePolicy) {
        this.store = store == null ? new InMemoryCookieStore() : store;
        this.policy = cookiePolicy == null ? CookiePolicy.ACCEPT_ORIGINAL_SERVER : cookiePolicy;
    }

    public void setCookiePolicy(CookiePolicy cookiePolicy) {
        if (cookiePolicy != null) {
            this.policy = cookiePolicy;
        }
    }

    public CookieStore getCookieStore() {
        return store;
    }

    public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
        if (uri == null || requestHeaders == null) {
            throw new IllegalArgumentException("Argument is null");
        }
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        boolean https = scheme.equalsIgnoreCase("https");
        boolean http = https || scheme.equalsIgnoreCase("http");
        int port = port(uri);
        List<HttpCookie> sent = new ArrayList<>();
        for (HttpCookie cookie : store.get(uri)) {
            if (cookie.getPath() != null && path.startsWith(cookie.getPath()) && (https || !cookie.getSecure())
                    && (http || !cookie.isHttpOnly())
                    && (cookie.getPortlist() == null || listed(cookie.getPortlist(), port))) {
                // the longest path first, the order kept among paths as long
                int at = sent.size();
                while (at > 0 && sent.get(at - 1).getPath().length() < cookie.getPath().length()) {
                    at--;
                }
                sent.add(at, cookie);
            }
        }
        List<String> fields = new ArrayList<>();
        if (!sent.isEmpty() && sent.get(0).getVersion() == 1) {
            fields.add("$Version=\"1\"");
        }
        for (HttpCookie cookie : sent) {
            fields.add(cookie.toString());
        }
        return Map.of("Cookie", List.copyOf(fields));
    }

    public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
        if (uri == null || responseHeaders == null) {
            throw new IllegalArgumentException("Argument is null");
        }
        for (Map.Entry<String, List<String>> field : responseHeaders.entrySet()) {
            String name = field.getKey();
            if (name == null || (!name.equalsIgnoreCase("Set-Cookie") && !name.equalsIgnoreCase("Set-Cookie2"))) {
                continue;
            }
            for (String value : field.getValue()) {
                List<HttpCookie> cookies;
                try {
                    cookies = HttpCookie.parse(value);
                }
                catch (IllegalArgumentException invalid) {
                    continue;
                }
                for (HttpCookie cookie : cookies) {
                    kept(uri, cookie);
                }
            }
        }
    }

    private void kept(URI uri, HttpCookie cookie) {
        if (cookie.getPath() == null) {
            String path = uri.getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            cookie.setPath(slash < 0 ? "/" : path.substring(0, slash + 1));
        }
        String host = uri.getHost();
        if (cookie.getDomain() == null && host != null) {
            cookie.setDomain(host.indexOf('.') < 0 ? host + ".local" : host);
        }
        String ports = cookie.getPortlist();
        if (ports != null) {
            int port = port(uri);
            if (ports.isEmpty()) {
                cookie.setPortlist(String.valueOf(port));
            }
            else if (!listed(ports, port)) {
                return;
            }
        }
        if (policy.shouldAccept(uri, cookie)) {
            store.add(uri, cookie);
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    // Whether a port list names a port: each number before a comma, then the rest, as a
    // JDK 25 was seen to read one (a list that starts with a comma names none).
    private static boolean listed(String ports, int port) {
        String rest = ports;
        int comma;
        while ((comma = rest.indexOf(',')) > 0) {
            if (named(rest.substring(0, comma), port)) {
                return true;
            }
            rest = rest.substring(comma + 1);
        }
        return named(rest, port);
    }

    private static boolean named(String number, int port) {
        try {
            return Integer.parseInt(number) == port;
        }
        catch (NumberFormatException ex) {
            return false;
        }
    }
}
