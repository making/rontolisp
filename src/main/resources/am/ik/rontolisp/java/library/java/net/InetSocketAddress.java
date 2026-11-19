// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A host and a port. A host written as four numbers, or localhost, has its address;
// any other name has none here, where a JDK would ask a name service for it.
package java.net;

public class InetSocketAddress extends SocketAddress {

    private final String hostname;

    private final InetAddress address;

    private final int port;

    private static int checked(int port) {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range:" + port);
        }
        return port;
    }

    public InetSocketAddress(int port) {
        this((InetAddress) null, port);
    }

    public InetSocketAddress(InetAddress addr, int port) {
        this.port = checked(port);
        this.address = addr == null ? new InetAddress("0.0.0.0", "0.0.0.0") : addr;
        this.hostname = null;
    }

    public InetSocketAddress(String hostname, int port) {
        this.port = checked(port);
        if (hostname == null) {
            throw new IllegalArgumentException("hostname can't be null");
        }
        this.address = InetAddress.spelled(hostname);
        this.hostname = this.address == null ? hostname : null;
    }

    private InetSocketAddress(int port, String hostname) {
        this.port = port;
        this.address = null;
        this.hostname = hostname;
    }

    public static InetSocketAddress createUnresolved(String host, int port) {
        checked(port);
        if (host == null) {
            throw new IllegalArgumentException("hostname can't be null");
        }
        return new InetSocketAddress(port, host);
    }

    public final int getPort() {
        return port;
    }

    public final InetAddress getAddress() {
        return address;
    }

    public final String getHostName() {
        return address == null ? hostname : address.getHostName();
    }

    public final String getHostString() {
        return address == null ? hostname : address.getHostName();
    }

    public final boolean isUnresolved() {
        return address == null;
    }

    public final boolean equals(Object o) {
        if (!(o instanceof InetSocketAddress)) {
            return false;
        }
        InetSocketAddress that = (InetSocketAddress) o;
        if (port != that.port) {
            return false;
        }
        if (address == null) {
            return that.address == null && hostname.equalsIgnoreCase(that.hostname);
        }
        return address.equals(that.address);
    }

    public final int hashCode() {
        return (address == null ? hostname.toLowerCase().hashCode() : address.hashCode()) + port;
    }

    public String toString() {
        return (address == null ? hostname + "/<unresolved>" : address.toString()) + ":" + port;
    }
}
