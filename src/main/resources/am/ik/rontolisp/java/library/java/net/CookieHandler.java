// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What a client asks for a request's cookies and hands a reply's. The default handler
// is kept and answered; no client of the library asks it, as a JDK's HttpClient does
// not.
package java.net;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public abstract class CookieHandler {

    private static CookieHandler installed;

    public CookieHandler() {
    }

    public static synchronized CookieHandler getDefault() {
        return installed;
    }

    public static synchronized void setDefault(CookieHandler cHandler) {
        installed = cHandler;
    }

    public abstract Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders)
            throws IOException;

    public abstract void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException;
}
