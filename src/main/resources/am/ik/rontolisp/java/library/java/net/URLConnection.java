// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net;

import java.io.IOException;
import java.io.InputStream;

// A connection to what a URL names: here a resource the program carries, read as the URL
// reads it (URL.openStream). Nothing is cached, so useCaches is a setting and nothing
// more.
public abstract class URLConnection {

    private static boolean defaultUseCaches = true;

    protected URL url;

    protected boolean connected;

    protected boolean useCaches = defaultUseCaches;

    protected URLConnection(URL url) {
        this.url = url;
    }

    public abstract void connect() throws IOException;

    public URL getURL() {
        return url;
    }

    public InputStream getInputStream() throws IOException {
        throw new UnknownServiceException("protocol doesn't support input");
    }

    public void setUseCaches(boolean usecaches) {
        if (connected) {
            throw new IllegalStateException("Already connected");
        }
        useCaches = usecaches;
    }

    public boolean getUseCaches() {
        return useCaches;
    }

    public boolean getDefaultUseCaches() {
        return defaultUseCaches;
    }

    public void setDefaultUseCaches(boolean defaultusecaches) {
        defaultUseCaches = defaultusecaches;
    }

    public String toString() {
        return getClass().getName() + ":" + url;
    }

    // the connection of a URL: its octets, or nothing to read
    static final class Carried extends URLConnection {

        Carried(URL url) {
            super(url);
        }

        public void connect() throws IOException {
            connected = true;
        }

        public InputStream getInputStream() throws IOException {
            connect();
            return url.openStream();
        }
    }
}
