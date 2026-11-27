// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Which cookies a CookieManager keeps. The original server's are those whose domain
// the host of the URI domain-matches; with no URI or no cookie, none (a JDK 25's
// answers).
package java.net;

public interface CookiePolicy {

    CookiePolicy ACCEPT_ALL = new CookiePolicy() {
        public boolean shouldAccept(URI uri, HttpCookie cookie) {
            return true;
        }
    };

    CookiePolicy ACCEPT_NONE = new CookiePolicy() {
        public boolean shouldAccept(URI uri, HttpCookie cookie) {
            return false;
        }
    };

    CookiePolicy ACCEPT_ORIGINAL_SERVER = new CookiePolicy() {
        public boolean shouldAccept(URI uri, HttpCookie cookie) {
            return uri != null && cookie != null && HttpCookie.domainMatches(cookie.getDomain(), uri.getHost());
        }
    };

    boolean shouldAccept(URI uri, HttpCookie cookie);
}
