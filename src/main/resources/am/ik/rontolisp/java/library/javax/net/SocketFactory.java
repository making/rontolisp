// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;

public abstract class SocketFactory {

    private static final SocketFactory DEFAULT = new DefaultSocketFactory();

    protected SocketFactory() {
    }

    public static SocketFactory getDefault() {
        return DEFAULT;
    }

    public Socket createSocket() throws IOException {
        throw new SocketException("Unconnected sockets not implemented");
    }

    public abstract Socket createSocket(String host, int port) throws IOException, UnknownHostException;

    public abstract Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException, UnknownHostException;

    public abstract Socket createSocket(InetAddress host, int port) throws IOException;

    public abstract Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException;
}
