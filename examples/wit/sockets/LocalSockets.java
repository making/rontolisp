// Sockets of the program's own: the part of wasi:sockets/types Echo.java calls, over
// java.net, which answers under a JDK, on the interpreter and on the JVM --
// META-INF/services/Sockets names it, and ServiceLoader finds it there. A WebAssembly
// component's host is its network, and the one ServiceLoader finds there; this class
// goes unused.
//
// What the host answers in pieces, a provider answers with what rontolisp-java-api
// gives: WitStream.of(iterator), WitFuture.of(value), and an OutputStream of its own
// for the consumer the program hands send.

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.function.Consumer;

import am.ik.rontolisp.wit.WitException;
import am.ik.rontolisp.wit.WitFuture;
import am.ik.rontolisp.wit.WitResult;
import am.ik.rontolisp.wit.WitStream;

public class LocalSockets implements Sockets {

    public Sockets.TcpSocket tcpSocketCreate(Sockets.IpAddressFamily addressFamily) {
        if (addressFamily != Sockets.IpAddressFamily.IPV4) {
            throw new WitException("local sockets: IPv4 alone", new Sockets.ErrorCode.NotSupported());
        }
        return new Tcp(null);
    }

    // the error arm of a call a socket in this state cannot make
    static WitException invalidState(String what) {
        return new WitException("local sockets: " + what, new Sockets.ErrorCode.InvalidState());
    }

    static InetSocketAddress inet(Sockets.IpSocketAddress address) {
        if (!(address instanceof Sockets.IpSocketAddress.Ipv4(Sockets.Ipv4SocketAddress(short port,
                Sockets.Ipv4Address ip)))) {
            throw new WitException("local sockets: IPv4 alone", new Sockets.ErrorCode.NotSupported());
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(new byte[] { ip.a(), ip.b(), ip.c(), ip.d() }),
                    Short.toUnsignedInt(port));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Sockets.IpSocketAddress wit(InetAddress ip, int port) {
        byte[] octets = ip.getAddress();
        return new Sockets.IpSocketAddress.Ipv4(new Sockets.Ipv4SocketAddress((short) port,
                new Sockets.Ipv4Address(octets[0], octets[1], octets[2], octets[3])));
    }

    // a tcp-socket: java.net's ServerSocket once bound (it listens from there on), its
    // Socket once connected or accepted
    static final class Tcp implements Sockets.TcpSocket {

        private ServerSocket listener;

        private Socket connection;

        Tcp(Socket connection) {
            this.connection = connection;
        }

        public void bind(Sockets.IpSocketAddress localAddress) {
            if (this.listener != null || this.connection != null) {
                throw invalidState("bound already");
            }
            try {
                ServerSocket listener = new ServerSocket();
                listener.bind(inet(localAddress));
                this.listener = listener;
            }
            catch (BindException e) {
                throw new WitException("local sockets: " + e.getMessage(), new Sockets.ErrorCode.AddressInUse());
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public void connect(Sockets.IpSocketAddress remoteAddress) {
            if (this.listener != null || this.connection != null) {
                throw invalidState("bound or connected already");
            }
            try {
                Socket connection = new Socket();
                connection.connect(inet(remoteAddress));
                this.connection = connection;
            }
            catch (ConnectException e) {
                throw new WitException("local sockets: " + e.getMessage(),
                        new Sockets.ErrorCode.ConnectionRefused());
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public WitStream<Sockets.TcpSocket> listen() {
            if (this.listener == null) {
                throw invalidState("not bound");
            }
            ServerSocket listener = this.listener;
            // the connections as they come: a read of one waits for the next
            return WitStream.of(new Iterator<Sockets.TcpSocket>() {

                public boolean hasNext() {
                    return !listener.isClosed();
                }

                public Sockets.TcpSocket next() {
                    try {
                        return new Tcp(listener.accept());
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
        }

        // the consumer is handed an OutputStream over the connection, whose close()
        // ends what is sent, as the end of wasi:sockets' stream does, and keeps the
        // connection open for what is received
        public WitFuture<Void> send(Consumer<OutputStream> data) {
            if (this.connection == null) {
                throw invalidState("not connected");
            }
            Socket connection = this.connection;
            try {
                OutputStream out = connection.getOutputStream();
                data.accept(new OutputStream() {

                    public void write(int b) throws IOException {
                        out.write(b);
                    }

                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                    }

                    public void close() throws IOException {
                        out.flush();
                        connection.shutdownOutput();
                    }
                });
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return WitFuture.of(null);
        }

        // what is received, to the end the peer's close() makes; closing it leaves the
        // connection to close()
        public Sockets.Received receive() {
            if (this.connection == null) {
                throw invalidState("not connected");
            }
            try {
                InputStream in = this.connection.getInputStream();
                return new Sockets.Received(new InputStream() {

                    public int read() throws IOException {
                        return in.read();
                    }

                    public int read(byte[] b, int off, int len) throws IOException {
                        return in.read(b, off, len);
                    }

                    public void close() {
                    }
                }, WitFuture.of(null));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public WitResult<Sockets.IpSocketAddress, Sockets.ErrorCode> getLocalAddress() {
            if (this.listener != null) {
                return new WitResult.Ok<>(wit(this.listener.getInetAddress(), this.listener.getLocalPort()));
            }
            if (this.connection != null) {
                return new WitResult.Ok<>(wit(this.connection.getLocalAddress(), this.connection.getLocalPort()));
            }
            return new WitResult.Err<>(new Sockets.ErrorCode.InvalidState());
        }

        public void close() {
            try {
                if (this.listener != null) {
                    this.listener.close();
                }
                if (this.connection != null) {
                    this.connection.close();
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
