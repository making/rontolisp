// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net;

import java.util.List;

public interface CookieStore {

    void add(URI uri, HttpCookie cookie);

    List<HttpCookie> get(URI uri);

    List<HttpCookie> getCookies();

    List<URI> getURIs();

    boolean remove(URI uri, HttpCookie cookie);

    boolean removeAll();
}
