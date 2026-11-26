// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A client socket over the program's own TCP sockets (rontolisp.internal.Sockets).
// What a JDK 25 answers of one in each state -- unconnected, bound, connected, closed --
// and the words and order of its refusals are its answers to probes. The host's socket
// is made by the first bind or connect, so a close ends a connect another thread waits
// in; a connect's time limit, the read deadline and half a connection ended are the
// host's. The options are kept and answered back; TCP_NODELAY, SO_KEEPALIVE, SO_LINGER,
// SO_OOBINLINE and IP_TOS are given to the host's socket once it is connected, where the
// host has them (Sockets.option), and the buffer sizes, SO_REUSEADDR and SO_REUSEPORT are
// not. What the options answer and refuse are a JDK 25's answers to probes.
package java.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;
import rontolisp.internal.Sockets;

public class Socket implements Closeable {

    // The host's socket, made by the first bind or connect.
    private Object handle;

    private boolean bound;

    private boolean connected;

    private boolean closed;

    // Which halves shutdownInput and shutdownOutput ended.
    private boolean inputShutdown;

    private boolean outputShutdown;

    // The other end: its address as the program named it, and its port.
    private InetAddress address;

    private int port;

    private InetAddress localAddress;

    private int localPort = -1;

    private int soTimeout;

    private boolean noDelay;

    private boolean keepAlive;

    private boolean reuseAddress;

    private boolean reusePort;

    // SO_LINGER's seconds, -1 off
    private int linger = -1;

    private boolean oobInline;

    private int trafficClass;

    private int sendBufferSize = 8192;

    private int receiveBufferSize = 65536;

    private InputStream input;

    private OutputStream output;

    public Socket() {
    }

    public Socket(String host, int port) throws UnknownHostException, IOException {
        this(host == null ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                : new InetSocketAddress(host, port), null, 0);
    }

    public Socket(InetAddress address, int port) throws IOException {
        this(new InetSocketAddress(Objects.requireNonNull(address), port), null, 0);
    }

    public Socket(String host, int port, InetAddress localAddr, int localPort) throws IOException {
        this(host == null ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                : new InetSocketAddress(host, port), localAddr, localPort);
    }

    public Socket(InetAddress address, int port, InetAddress localAddr, int localPort) throws IOException {
        this(new InetSocketAddress(Objects.requireNonNull(address), port), localAddr, localPort);
    }

    private Socket(InetSocketAddress endpoint, InetAddress localAddr, int localPort) throws IOException {
        try {
            if (localAddr != null || localPort != 0) {
                bind(new InetSocketAddress(localAddr, localPort));
            }
            connect(endpoint);
        }
        catch (IOException e) {
            close();
            throw e;
        }
    }

    // The set supportedOptions answers, made at its first call: a program that asks
    // for no option carries no set.
    private static final class Supported {

        static final Set<SocketOption<?>> OPTIONS = Set.of(StandardSocketOptions.IP_TOS,
                StandardSocketOptions.SO_KEEPALIVE, StandardSocketOptions.SO_LINGER, StandardSocketOptions.SO_RCVBUF,
                StandardSocketOptions.SO_REUSEADDR, StandardSocketOptions.SO_REUSEPORT,
                StandardSocketOptions.SO_SNDBUF, StandardSocketOptions.TCP_NODELAY);
    }

    // Whether NAME is one of the options a socket has.
    private static boolean known(SocketOption<?> name) {
        return name == StandardSocketOptions.TCP_NODELAY || name == StandardSocketOptions.SO_KEEPALIVE
                || name == StandardSocketOptions.SO_LINGER || name == StandardSocketOptions.IP_TOS
                || name == StandardSocketOptions.SO_SNDBUF || name == StandardSocketOptions.SO_RCVBUF
                || name == StandardSocketOptions.SO_REUSEADDR || name == StandardSocketOptions.SO_REUSEPORT;
    }

    // A connection a listener took, its SO_REUSEADDR and receive buffer the listener's.
    static Socket accepted(Object handle, boolean reuseAddress, int receiveBufferSize) {
        Socket made = new Socket();
        made.reuseAddress = reuseAddress;
        made.receiveBufferSize = receiveBufferSize;
        made.handle = handle;
        made.bound = true;
        made.connected = true;
        made.address = new InetAddress(null, numbers(Sockets.peerAddress(handle)));
        made.port = Sockets.peerPort(handle);
        made.ends();
        return made;
    }

    private static String numbers(String address) {
        return address == null ? "0.0.0.0" : address;
    }

    // What the host says of the local end, kept for after the close: every interface's
    // address is named by its numbers.
    private void ends() {
        localPort = Sockets.localPort(handle);
        String at = numbers(Sockets.localAddress(handle));
        localAddress = new InetAddress(at.equals("0.0.0.0") ? at : null, at);
    }

    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    // A connect waits as long as the timeout, or for a timeout of 0 as long as the
    // host's connect does; a close from another thread ends it.
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (endpoint == null) {
            throw new IllegalArgumentException("connect: The address can't be null");
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("connect: timeout can't be negative");
        }
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (connected) {
            throw new SocketException("Already connected");
        }
        if (!(endpoint instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        InetSocketAddress to = (InetSocketAddress) endpoint;
        InetAddress at = to.getAddress();
        if (at == null && !to.looksUp()) {
            close();
            throw new UnknownHostException(to.getHostName());
        }
        String host = at == null ? to.getHostName() : at.getHostAddress();
        if (handle == null) {
            made(Sockets.socket(null, -1), null);
        }
        Object answer = Sockets.connect(handle, host, to.getPort(), timeout);
        if (Sockets.failed(answer)) {
            IOException failed = closed ? new SocketException("Socket closed")
                    : Sockets.failure(answer, host, Sockets.CONNECTING);
            close();
            throw failed;
        }
        bound = true;
        connected = true;
        address = at == null ? new InetAddress(to.getHostName(), numbers(Sockets.peerAddress(handle))) : at;
        port = to.getPort();
        ends();
        if (soTimeout > 0) {
            Sockets.deadline(handle, soTimeout);
        }
        try {
            given();
        }
        catch (IOException e) {
            close();
            throw e;
        }
    }

    // The options set before the connect, given to the host's socket.
    private void given() throws SocketException {
        if (noDelay) {
            host(Sockets.NO_DELAY, 1);
        }
        if (keepAlive) {
            host(Sockets.KEEP_ALIVE, 1);
        }
        if (linger >= 0) {
            host(Sockets.LINGER, linger);
        }
        if (oobInline) {
            host(Sockets.OOB_INLINE, 1);
        }
        if (trafficClass != 0) {
            host(Sockets.TRAFFIC_CLASS, trafficClass);
        }
    }

    // An option given to the host's socket once it is connected; before, it waits for
    // the connect.
    private void host(int option, int value) throws SocketException {
        if (connected) {
            Object answer = Sockets.option(handle, option, value);
            if (Sockets.failed(answer)) {
                IOException failed = failure(answer);
                throw failed instanceof SocketException ? (SocketException) failed
                        : new SocketException(failed.getMessage());
            }
        }
    }

    // The host's socket a bind (to HOST, * for every interface) or a connect (a null
    // HOST) made, or the exception of why there is none, a name with no address an
    // unresolved one. A socket closed meanwhile closes it.
    private void made(Object made, String host) throws IOException {
        if (Sockets.failed(made)) {
            IOException failed = Sockets.failure(made, host, host == null ? Sockets.USING : Sockets.BINDING);
            throw failed instanceof UnknownHostException ? new SocketException("Unresolved address") : failed;
        }
        synchronized (this) {
            if (!closed) {
                handle = made;
                return;
            }
        }
        Sockets.close(made);
        throw new SocketException("Socket closed");
    }

    public void bind(SocketAddress bindpoint) throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (bound) {
            throw new SocketException("Already bound");
        }
        if (bindpoint != null && !(bindpoint instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        InetSocketAddress at = bindpoint == null ? new InetSocketAddress(0) : (InetSocketAddress) bindpoint;
        InetAddress inet = at.getAddress();
        if (inet == null && !at.looksUp()) {
            throw new SocketException("Unresolved address");
        }
        String host = inet == null ? at.getHostName() : inet.isAnyLocalAddress() ? null : inet.getHostAddress();
        made(Sockets.socket(host, at.getPort()), host == null ? "*" : host);
        bound = true;
        ends();
    }

    public InetAddress getInetAddress() {
        return connected ? address : null;
    }

    public InetAddress getLocalAddress() {
        return localAddress != null && !closed ? localAddress : new InetAddress("0.0.0.0", "0.0.0.0");
    }

    public int getPort() {
        return connected ? port : 0;
    }

    public int getLocalPort() {
        return localPort;
    }

    public SocketAddress getRemoteSocketAddress() {
        return connected ? new InetSocketAddress(address, port) : null;
    }

    public SocketAddress getLocalSocketAddress() {
        return bound && localPort >= 0 ? new InetSocketAddress(getLocalAddress(), localPort) : null;
    }

    public InputStream getInputStream() throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!connected) {
            throw new SocketException("Socket is not connected");
        }
        if (inputShutdown) {
            throw new SocketException("Socket input is shutdown");
        }
        synchronized (this) {
            if (input == null) {
                input = new SocketInputStream(this);
            }
            return input;
        }
    }

    public OutputStream getOutputStream() throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!connected) {
            throw new SocketException("Socket is not connected");
        }
        if (outputShutdown) {
            throw new SocketException("Socket output is shutdown");
        }
        synchronized (this) {
            if (output == null) {
                output = new SocketOutputStream(this);
            }
            return output;
        }
    }

    private void open() throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
    }

    public void setTcpNoDelay(boolean on) throws SocketException {
        open();
        host(Sockets.NO_DELAY, on ? 1 : 0);
        noDelay = on;
    }

    public boolean getTcpNoDelay() throws SocketException {
        open();
        return noDelay;
    }

    public void setSoTimeout(int timeout) throws SocketException {
        open();
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout can't be negative");
        }
        if (handle != null) {
            Object answer = Sockets.deadline(handle, timeout);
            if (Sockets.failed(answer)) {
                throw new SocketException(Sockets.text(answer));
            }
        }
        soTimeout = timeout;
    }

    public int getSoTimeout() throws SocketException {
        open();
        return soTimeout;
    }

    public void setSendBufferSize(int size) throws SocketException {
        if (size <= 0) {
            throw new IllegalArgumentException("negative send size");
        }
        open();
        sendBufferSize = size;
    }

    public int getSendBufferSize() throws SocketException {
        open();
        return sendBufferSize;
    }

    public void setReceiveBufferSize(int size) throws SocketException {
        if (size <= 0) {
            throw new IllegalArgumentException("invalid receive size");
        }
        open();
        receiveBufferSize = size;
    }

    public int getReceiveBufferSize() throws SocketException {
        open();
        return receiveBufferSize;
    }

    public void setKeepAlive(boolean on) throws SocketException {
        open();
        host(Sockets.KEEP_ALIVE, on ? 1 : 0);
        keepAlive = on;
    }

    public boolean getKeepAlive() throws SocketException {
        open();
        return keepAlive;
    }

    public void setReuseAddress(boolean on) throws SocketException {
        open();
        reuseAddress = on;
    }

    public boolean getReuseAddress() throws SocketException {
        open();
        return reuseAddress;
    }

    // Off, or on for as many seconds, 65535 at most.
    public void setSoLinger(boolean on, int linger) throws SocketException {
        open();
        if (on && linger < 0) {
            throw new IllegalArgumentException("invalid value for SO_LINGER");
        }
        int seconds = on ? Math.min(linger, 65535) : -1;
        host(Sockets.LINGER, seconds);
        this.linger = seconds;
    }

    public int getSoLinger() throws SocketException {
        open();
        return linger;
    }

    public void setOOBInline(boolean on) throws SocketException {
        open();
        host(Sockets.OOB_INLINE, on ? 1 : 0);
        oobInline = on;
    }

    public boolean getOOBInline() throws SocketException {
        open();
        return oobInline;
    }

    // The two low bits, ECN's, are not kept, as a JDK on Linux answers.
    public void setTrafficClass(int tc) throws SocketException {
        if (tc < 0 || tc > 255) {
            throw new IllegalArgumentException("tc is not in range 0 -- 255");
        }
        open();
        host(Sockets.TRAFFIC_CLASS, tc);
        trafficClass = tc & 0xfc;
    }

    public int getTrafficClass() throws SocketException {
        if (closed) {
            throw new SocketException("Socket closed");
        }
        return trafficClass;
    }

    // One byte of urgent data, the low eight bits of DATA, after what was written.
    public void sendUrgentData(int data) throws IOException {
        if (closed) {
            throw new SocketException("Socket closed");
        }
        if (!connected) {
            throw new SocketException("Not connected");
        }
        if (outputShutdown) {
            // what the host says of a send after its output ended
            throw new IOException("Broken pipe");
        }
        Object answer = Sockets.option(handle, Sockets.URGENT, data & 0xff);
        if (Sockets.failed(answer)) {
            throw failure(answer);
        }
    }

    public Set<SocketOption<?>> supportedOptions() {
        return Supported.OPTIONS;
    }

    public <T> Socket setOption(SocketOption<T> name, T value) throws IOException {
        Objects.requireNonNull(name);
        open();
        if (!known(name)) {
            throw unsupported(name);
        }
        checked(name, value);
        if (name == StandardSocketOptions.TCP_NODELAY) {
            setTcpNoDelay((Boolean) value);
        }
        else if (name == StandardSocketOptions.SO_KEEPALIVE) {
            setKeepAlive((Boolean) value);
        }
        else if (name == StandardSocketOptions.SO_REUSEADDR) {
            setReuseAddress((Boolean) value);
        }
        else if (name == StandardSocketOptions.SO_REUSEPORT) {
            reusePort = (Boolean) value;
        }
        else if (name == StandardSocketOptions.SO_LINGER) {
            int seconds = (Integer) value;
            setSoLinger(seconds >= 0, seconds);
        }
        else if (name == StandardSocketOptions.IP_TOS) {
            setTrafficClass(tos((Integer) value));
        }
        else {
            int size = size((Integer) value);
            if (size > 0 && name == StandardSocketOptions.SO_SNDBUF) {
                sendBufferSize = size;
            }
            else if (size > 0) {
                receiveBufferSize = size;
            }
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name) throws IOException {
        Objects.requireNonNull(name);
        open();
        if (!known(name)) {
            throw unsupported(name);
        }
        Object value;
        if (name == StandardSocketOptions.TCP_NODELAY) {
            value = noDelay;
        }
        else if (name == StandardSocketOptions.SO_KEEPALIVE) {
            value = keepAlive;
        }
        else if (name == StandardSocketOptions.SO_REUSEADDR) {
            value = reuseAddress;
        }
        else if (name == StandardSocketOptions.SO_REUSEPORT) {
            value = reusePort;
        }
        else if (name == StandardSocketOptions.SO_LINGER) {
            value = linger;
        }
        else if (name == StandardSocketOptions.IP_TOS) {
            value = trafficClass;
        }
        else if (name == StandardSocketOptions.SO_SNDBUF) {
            value = sendBufferSize;
        }
        else {
            value = receiveBufferSize;
        }
        return (T) value;
    }

    // The refusals the options of a socket and a server socket share.
    static UnsupportedOperationException unsupported(SocketOption<?> name) {
        return new UnsupportedOperationException("'" + name + "' not supported");
    }

    static void checked(SocketOption<?> name, Object value) {
        if (!(name.type() == Boolean.class ? value instanceof Boolean : value instanceof Integer)) {
            throw new IllegalArgumentException("Invalid value '" + value + "'");
        }
    }

    static int tos(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Invalid IP_TOS value");
        }
        return value;
    }

    // A size of 0 leaves the size as it was.
    static int size(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Invalid send/receive buffer size");
        }
        return value;
    }

    public void close() throws IOException {
        Object made;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            made = handle;
        }
        if (made != null) {
            Sockets.close(made);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isBound() {
        return bound;
    }

    public boolean isClosed() {
        return closed;
    }

    // The input half ended: the stream reads its end, what came and was not read
    // dropped, and a read another thread waits in answers the end.
    public void shutdownInput() throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!connected) {
            throw new SocketException("Socket is not connected");
        }
        if (inputShutdown) {
            throw new SocketException("Socket input is already shutdown");
        }
        inputShutdown = true;
        Object answer = Sockets.shutdown(handle, false);
        if (Sockets.failed(answer)) {
            throw failure(answer);
        }
    }

    // The output half ended: the peer reads its end once what was written has come.
    public void shutdownOutput() throws IOException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!connected) {
            throw new SocketException("Socket is not connected");
        }
        if (outputShutdown) {
            throw new SocketException("Socket output is already shutdown");
        }
        outputShutdown = true;
        Object answer = Sockets.shutdown(handle, true);
        if (Sockets.failed(answer)) {
            throw failure(answer);
        }
    }

    public boolean isInputShutdown() {
        return inputShutdown;
    }

    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    public String toString() {
        if (!connected) {
            return "Socket[unconnected]";
        }
        return "Socket[addr=" + address + ",port=" + port + ",localport=" + localPort + "]";
    }

    // The failure of a read or a write: a socket closed meanwhile is that.
    private IOException failure(Object answer) {
        if (closed) {
            return new SocketException("Socket closed");
        }
        return Sockets.failure(answer, null, Sockets.USING);
    }

    // What came and was not read yet is buffer[at, end): what a read of one byte or
    // available() took in, which a read hands out first. A read of several into an
    // array of the program's takes the host's bytes straight into it. A stream that
    // read the end answers it again, even once its socket is closed, as a JDK's does.
    private static final class SocketInputStream extends InputStream {

        private final Socket socket;

        private final byte[] buffer = new byte[8192];

        private int at;

        private int end;

        private boolean ended;

        SocketInputStream(Socket socket) {
            this.socket = socket;
        }

        private void open() throws SocketException {
            if (socket.closed) {
                throw new SocketException("Socket closed");
            }
        }

        public int read() throws IOException {
            if (ended) {
                return -1;
            }
            open();
            if (socket.inputShutdown) {
                ended = true;
                return -1;
            }
            if (at == end) {
                int n = fill(buffer, 0, buffer.length);
                if (n < 0) {
                    return -1;
                }
                at = 0;
                end = n;
            }
            return buffer[at++] & 0xff;
        }

        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (ended) {
                return -1;
            }
            open();
            if (socket.inputShutdown) {
                ended = true;
                return -1;
            }
            if (at < end) {
                int n = Math.min(len, end - at);
                System.arraycopy(buffer, at, b, off, n);
                at += n;
                return n;
            }
            return fill(b, off, len);
        }

        // At least one byte once one has come, -1 at the end.
        private int fill(byte[] b, int off, int len) throws IOException {
            Object answer = Sockets.read(socket.handle, b, off, len);
            if (Sockets.failed(answer)) {
                throw socket.failure(answer);
            }
            int n = (Integer) answer;
            if (n < 0) {
                if (socket.closed) {
                    throw new SocketException("Socket closed");
                }
                ended = true;
            }
            return n;
        }

        // The bytes that came: what a read took in and has not handed out, else what
        // has come since, taken in with no wait.
        public int available() throws IOException {
            open();
            if (socket.inputShutdown) {
                return 0;
            }
            if (at == end && !ended) {
                Object answer = Sockets.take(socket.handle, buffer, 0, buffer.length);
                if (Sockets.failed(answer)) {
                    throw socket.failure(answer);
                }
                at = 0;
                end = (Integer) answer;
            }
            return end - at;
        }

        public void close() throws IOException {
            socket.close();
        }
    }

    // A write goes out before it returns, as a JDK's does.
    private static final class SocketOutputStream extends OutputStream {

        private final Socket socket;

        SocketOutputStream(Socket socket) {
            this.socket = socket;
        }

        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return;
            }
            if (socket.closed) {
                throw new SocketException("Socket closed");
            }
            if (socket.outputShutdown) {
                // what the host says of a write after its output ended
                throw new SocketException("Broken pipe");
            }
            Object answer = Sockets.write(socket.handle, b, off, len);
            if (Sockets.failed(answer)) {
                throw socket.failure(answer);
            }
        }

        public void close() throws IOException {
            socket.close();
        }
    }
}
