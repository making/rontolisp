// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What SocketFactory.getDefault() answers: the sockets of java.net as they are. The
// class has the name a JDK 25's default has, which getClass() shows.
package javax.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

class DefaultSocketFactory extends SocketFactory {

    DefaultSocketFactory() {
    }

    public Socket createSocket() {
        return new Socket();
    }

    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return new Socket(host, port);
    }

    public Socket createSocket(InetAddress address, int port) throws IOException {
        return new Socket(address, port);
    }

    public Socket createSocket(String host, int port, InetAddress clientAddress, int clientPort)
            throws IOException, UnknownHostException {
        return new Socket(host, port, clientAddress, clientPort);
    }

    public Socket createSocket(InetAddress address, int port, InetAddress clientAddress, int clientPort)
            throws IOException {
        return new Socket(address, port, clientAddress, clientPort);
    }
}
