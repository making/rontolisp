// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;

public abstract class ServerSocketFactory {

    private static final ServerSocketFactory DEFAULT = new DefaultServerSocketFactory();

    protected ServerSocketFactory() {
    }

    public static ServerSocketFactory getDefault() {
        return DEFAULT;
    }

    public ServerSocket createServerSocket() throws IOException {
        throw new SocketException("Unbound server sockets not implemented");
    }

    public abstract ServerSocket createServerSocket(int port) throws IOException;

    public abstract ServerSocket createServerSocket(int port, int backlog) throws IOException;

    public abstract ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException;
}
