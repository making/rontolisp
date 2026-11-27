// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The store a CookieManager made with none keeps its cookies in; its name is the one a
// JDK 25's getClass() answered. The cookies in the order they were kept, each under the
// URI it came with, which is the scheme http and the URI's host: a cookie replaces the
// one it equals, an expired one is kept by no one. What the store has for a URI, as a
// JDK 25 was seen to answer (java-spec.yaml, the cookies' case): first the cookies whose
// domain the host matches -- by HttpCookie.domainMatches for a cookie of version 1; for
// one of version 0 the host ending in the domain, or the domain a dot and the host, or
// .local for a host of one label, of a domain with a dot neither first nor last but
// .local --, then the others kept under the URI. A JDK orders the cookies a get answers
// otherwise among those of one path length, and its getURIs by no order seen.
package java.net;

import java.util.ArrayList;
import java.util.List;

class InMemoryCookieStore implements CookieStore {

    private final ArrayList<HttpCookie> cookies = new ArrayList<>();

    // the host each cookie was kept under, null for none
    private final ArrayList<String> hosts = new ArrayList<>();

    private final ArrayList<URI> uris = new ArrayList<>();

    InMemoryCookieStore() {
    }

    public synchronized void add(URI uri, HttpCookie cookie) {
        if (cookie == null) {
            throw new NullPointerException("cookie is null");
        }
        removed(cookie);
        if (cookie.hasExpired()) {
            return;
        }
        String host = uri == null ? null : uri.getHost();
        cookies.add(cookie);
        hosts.add(host);
        if (host != null && !kept(host)) {
            uris.add(URI.create("http://" + host));
        }
    }

    private boolean kept(String host) {
        for (URI uri : uris) {
            if (uri.getHost().equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<HttpCookie> get(URI uri) {
        if (uri == null) {
            throw new NullPointerException("uri is null");
        }
        expired();
        String host = uri.getHost();
        List<HttpCookie> found = new ArrayList<>();
        if (host == null) {
            return List.copyOf(found);
        }
        for (HttpCookie cookie : cookies) {
            if (cookie.getVersion() == 0 ? tailMatches(cookie.getDomain(), host)
                    : HttpCookie.domainMatches(cookie.getDomain(), host)) {
                found.add(cookie);
            }
        }
        for (int i = 0; i < cookies.size(); i++) {
            HttpCookie cookie = cookies.get(i);
            if (hosts.get(i) != null && hosts.get(i).equalsIgnoreCase(host) && !found.contains(cookie)) {
                found.add(cookie);
            }
        }
        return List.copyOf(found);
    }

    private static boolean tailMatches(String domain, String host) {
        if (domain == null) {
            return false;
        }
        boolean local = domain.equalsIgnoreCase(".local");
        if (!local && !HttpCookie.dotted(domain)) {
            return false;
        }
        int rest = host.length() - domain.length();
        if (rest >= 0 && host.regionMatches(true, rest, domain, 0, domain.length())) {
            return true;
        }
        if (domain.charAt(0) == '.' && host.equalsIgnoreCase(domain.substring(1))) {
            return true;
        }
        return local && host.indexOf('.') < 0;
    }

    public synchronized List<HttpCookie> getCookies() {
        expired();
        return List.copyOf(cookies);
    }

    public synchronized List<URI> getURIs() {
        return List.copyOf(uris);
    }

    public synchronized boolean remove(URI uri, HttpCookie cookie) {
        if (cookie == null) {
            throw new NullPointerException("cookie is null");
        }
        return removed(cookie);
    }

    public synchronized boolean removeAll() {
        boolean changed = !cookies.isEmpty() || !uris.isEmpty();
        cookies.clear();
        hosts.clear();
        uris.clear();
        return changed;
    }

    private boolean removed(HttpCookie cookie) {
        int at = cookies.indexOf(cookie);
        if (at < 0) {
            return false;
        }
        cookies.remove(at);
        hosts.remove(at);
        return true;
    }

    private void expired() {
        for (int i = cookies.size() - 1; i >= 0; i--) {
            if (cookies.get(i).hasExpired()) {
                cookies.remove(i);
                hosts.remove(i);
            }
        }
    }
}
