// An echo over TCP on wasi:sockets@0.3.0, through the @WitImport interface Sockets: a
// socket listens on the loopback, a second one connects and sends a line, the server
// answers it in capitals.
//
//   rontolisp Echo.java                                  the interpreter: LocalSockets
//   rontolisp Echo.java -o Echo.class && java Echo       the JVM: LocalSockets
//   rontolisp Echo.java -o echo.wasm --component
//     + wasmtime run -S inherit-network=y echo.wasm      wasmtime's own wasi:sockets
//   java -cp rontolisp-java-api.jar Echo.java            a JDK: LocalSockets
//
// (rontolisp takes --java-source-path rontolisp-java-api/src/main/java, where the
// annotation is.) META-INF/services/Sockets names LocalSockets, sockets of the
// program's own over java.net, which answer under a JDK, on the interpreter and on the
// JVM; in a component the host answers, and the output is the same.

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

import am.ik.rontolisp.wit.WitException;
import am.ik.rontolisp.wit.WitFuture;
import am.ik.rontolisp.wit.WitResult;
import am.ik.rontolisp.wit.WitStream;

public class Echo {

    // 127.0.0.1, port 0: the host picks the port
    static final Sockets.IpSocketAddress LOOPBACK = new Sockets.IpSocketAddress.Ipv4(
            new Sockets.Ipv4SocketAddress((short) 0, new Sockets.Ipv4Address((byte) 127, (byte) 0, (byte) 0, (byte) 1)));

    // get-local-address answers a result, declared a WitResult: the error arm is a
    // value, an Err holding the case
    static String where(Sockets.TcpSocket socket) {
        return switch (socket.getLocalAddress()) {
            case WitResult.Ok<Sockets.IpSocketAddress, Sockets.ErrorCode>(
                    Sockets.IpSocketAddress.Ipv4(Sockets.Ipv4SocketAddress(short port, Sockets.Ipv4Address ip))) ->
                // a u16 is a short, its bits as Java holds them
                Byte.toUnsignedInt(ip.a()) + "." + Byte.toUnsignedInt(ip.b()) + "." + Byte.toUnsignedInt(ip.c()) + "."
                        + Byte.toUnsignedInt(ip.d()) + ", a port of its own: " + (Short.toUnsignedInt(port) > 0);
            case WitResult.Ok<Sockets.IpSocketAddress, Sockets.ErrorCode>(Sockets.IpSocketAddress address) ->
                address.toString();
            case WitResult.Err<Sockets.IpSocketAddress, Sockets.ErrorCode>(Sockets.ErrorCode error) ->
                "none, " + error;
        };
    }

    // a stream<u8> the program hands the host: the binding makes the stream, hands the
    // host its readable end, then hands the consumer an OutputStream over the writable
    // end; a write waits until the host has taken it, close() ends the stream
    static void send(Sockets.TcpSocket socket, String text) {
        WitFuture<Void> sent = socket.send(out -> {
            try (out) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        // the future's result<_, error-code>: get() answers null, or throws the error
        sent.get();
    }

    // a stream<u8> the host answers is an InputStream, read to the end the peer's
    // close() makes; the future beside it says how the stream ended
    static String receive(Sockets.TcpSocket socket) throws IOException {
        Sockets.Received received = socket.receive();
        try (InputStream in = received.data(); WitFuture<Void> done = received.done()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            done.get();
            return text;
        }
    }

    public static void main(String[] args) throws IOException {
        Sockets sockets = ServiceLoader.load(Sockets.class).findFirst().orElseThrow();
        try (Sockets.TcpSocket server = sockets.tcpSocketCreate(Sockets.IpAddressFamily.IPV4)) {
            System.out.println("before bind:  " + where(server));
            // bind answers a result<_, error-code>, declared as its ok arm: nothing, or a
            // WitException holding the error
            server.bind(LOOPBACK);
            System.out.println("bound:        " + where(server));
            try {
                server.bind(LOOPBACK);
            }
            catch (WitException e) {
                System.out.println("bound again:  " + e.error());
            }

            // listen answers the connections as a stream of sockets: a WitStream, one
            // socket a read
            Sockets.IpSocketAddress address = ((WitResult.Ok<Sockets.IpSocketAddress, Sockets.ErrorCode>) server
                .getLocalAddress()).value();
            try (WitStream<Sockets.TcpSocket> connections = server.listen();
                    Sockets.TcpSocket client = sockets.tcpSocketCreate(Sockets.IpAddressFamily.IPV4)) {
                // connect is an async func: the call waits until the host has connected
                client.connect(address);
                List<Sockets.TcpSocket> accepted = connections.read(1);
                try (Sockets.TcpSocket peer = accepted.get(0)) {
                    send(client, "hello, sockets");
                    String line = receive(peer);
                    System.out.println("the server:   " + line);
                    send(peer, line.toUpperCase(Locale.ROOT));
                    System.out.println("the client:   " + receive(client));
                }
            }
        }
    }
}
