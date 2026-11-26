// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What ServerSocketFactory.getDefault() answers: the listeners of java.net as they
// are. The class has the name a JDK 25's default has, which getClass() shows.
package javax.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

class DefaultServerSocketFactory extends ServerSocketFactory {

    DefaultServerSocketFactory() {
    }

    public ServerSocket createServerSocket() throws IOException {
        return new ServerSocket();
    }

    public ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }

    public ServerSocket createServerSocket(int port, int backlog) throws IOException {
        return new ServerSocket(port, backlog);
    }

    public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
        return new ServerSocket(port, backlog, ifAddress);
    }
}
