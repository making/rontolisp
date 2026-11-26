// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A listener over the program's own TCP sockets (rontolisp.internal.Sockets). What a
// JDK 25 answers of one unbound, bound and closed, and the words and order of its
// refusals, are its answers to probes. The accept deadline is the host's; the backlog is
// the host's, and SO_REUSEADDR, SO_REUSEPORT, IP_TOS and the receive buffer's size are
// kept and answered back, a connection it takes having its SO_REUSEADDR and receive
// buffer, as a JDK 25's answered probes.
package java.net;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import rontolisp.internal.Sockets;

public class ServerSocket implements Closeable {

    // The host's listener once bound.
    private Object handle;

    private boolean bound;

    private boolean closed;

    private InetAddress address;

    private int port = -1;

    private boolean reuseAddress = true;

    private boolean reusePort;

    private int trafficClass;

    private int receiveBufferSize = 65536;

    private int soTimeout;

    // The set supportedOptions answers, made at its first call.
    private static final class Supported {

        static final Set<SocketOption<?>> OPTIONS = Set.of(StandardSocketOptions.IP_TOS,
                StandardSocketOptions.SO_RCVBUF, StandardSocketOptions.SO_REUSEADDR,
                StandardSocketOptions.SO_REUSEPORT);
    }

    private static boolean known(SocketOption<?> name) {
        return name == StandardSocketOptions.IP_TOS || name == StandardSocketOptions.SO_RCVBUF
                || name == StandardSocketOptions.SO_REUSEADDR || name == StandardSocketOptions.SO_REUSEPORT;
    }

    public ServerSocket() throws IOException {
    }

    public ServerSocket(int port) throws IOException {
        this(port, 50, null);
    }

    public ServerSocket(int port, int backlog) throws IOException {
        this(port, backlog, null);
    }

    public ServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("Port value out of range: " + port);
        }
        try {
            bind(new InetSocketAddress(bindAddr, port), backlog);
        }
        catch (IOException e) {
            close();
            throw e;
        }
    }

    public void bind(SocketAddress endpoint) throws IOException {
        bind(endpoint, 50);
    }

    public void bind(SocketAddress endpoint, int backlog) throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (bound) {
            throw new SocketException("Already bound");
        }
        if (endpoint == null) {
            endpoint = new InetSocketAddress(0);
        }
        if (!(endpoint instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        InetSocketAddress at = (InetSocketAddress) endpoint;
        InetAddress inet = at.getAddress();
        if (inet == null && !at.looksUp()) {
            throw new SocketException("Unresolved address");
        }
        String host = inet == null ? at.getHostName() : inet.isAnyLocalAddress() ? null : inet.getHostAddress();
        Object answer = Sockets.listen(host, at.getPort());
        if (Sockets.failed(answer)) {
            IOException failed = Sockets.failure(answer, host == null ? "*" : host, Sockets.BINDING);
            // a name with no address is a JDK's unresolved one
            throw failed instanceof UnknownHostException ? new SocketException("Unresolved address") : failed;
        }
        handle = answer;
        bound = true;
        if (soTimeout > 0) {
            Sockets.deadline(handle, soTimeout);
        }
        port = Sockets.localPort(handle);
        if (inet == null) {
            String numbers = Sockets.localAddress(handle);
            inet = new InetAddress(at.getHostName(), numbers == null ? "0.0.0.0" : numbers);
        }
        address = inet;
    }

    public InetAddress getInetAddress() {
        return address;
    }

    public int getLocalPort() {
        return port;
    }

    public SocketAddress getLocalSocketAddress() {
        return bound ? new InetSocketAddress(address, port) : null;
    }

    public Socket accept() throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!bound) {
            throw new SocketException("Socket is not bound yet");
        }
        Object answer = Sockets.accept(handle);
        if (Sockets.failed(answer)) {
            if (closed) {
                throw new SocketException("Socket closed");
            }
            throw Sockets.failure(answer, null, Sockets.USING);
        }
        return Socket.accepted(answer, reuseAddress, receiveBufferSize);
    }

    public void close() throws IOException {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (handle != null) {
            Sockets.close(handle);
        }
    }

    public boolean isBound() {
        return bound;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setReuseAddress(boolean on) throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        reuseAddress = on;
    }

    public boolean getReuseAddress() throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        return reuseAddress;
    }

    // An accept waits as long as the timeout, 0 for no limit.
    public void setSoTimeout(int timeout) throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout < 0");
        }
        if (handle != null) {
            Object answer = Sockets.deadline(handle, timeout);
            if (Sockets.failed(answer)) {
                throw new SocketException(Sockets.text(answer));
            }
        }
        soTimeout = timeout;
    }

    public int getSoTimeout() throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        return soTimeout;
    }

    public void setReceiveBufferSize(int size) throws SocketException {
        if (size <= 0) {
            throw new IllegalArgumentException("negative receive size");
        }
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        receiveBufferSize = size;
    }

    public int getReceiveBufferSize() throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        return receiveBufferSize;
    }

    public Set<SocketOption<?>> supportedOptions() {
        return Supported.OPTIONS;
    }

    public <T> ServerSocket setOption(SocketOption<T> name, T value) throws IOException {
        Objects.requireNonNull(name);
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!known(name)) {
            throw Socket.unsupported(name);
        }
        Socket.checked(name, value);
        if (name == StandardSocketOptions.SO_REUSEADDR) {
            reuseAddress = (Boolean) value;
        }
        else if (name == StandardSocketOptions.SO_REUSEPORT) {
            reusePort = (Boolean) value;
        }
        else if (name == StandardSocketOptions.IP_TOS) {
            trafficClass = Socket.tos((Integer) value) & 0xfc;
        }
        else {
            int size = Socket.size((Integer) value);
            if (size > 0) {
                receiveBufferSize = size;
            }
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name) throws IOException {
        Objects.requireNonNull(name);
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!known(name)) {
            throw Socket.unsupported(name);
        }
        Object value;
        if (name == StandardSocketOptions.SO_REUSEADDR) {
            value = reuseAddress;
        }
        else if (name == StandardSocketOptions.SO_REUSEPORT) {
            value = reusePort;
        }
        else if (name == StandardSocketOptions.IP_TOS) {
            value = trafficClass;
        }
        else {
            value = receiveBufferSize;
        }
        return (T) value;
    }

    public String toString() {
        if (!bound) {
            return "ServerSocket[unbound]";
        }
        return "ServerSocket[addr=" + address + ",localport=" + port + "]";
    }
}
