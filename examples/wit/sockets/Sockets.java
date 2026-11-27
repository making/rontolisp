// The WIT interface wasi:sockets/types as a Java interface, checked against
// wit/sockets.wit when rontolisp compiles the program.
//
// TcpSocket is the resource `tcp-socket`: its methods are the resource's, close() its
// release, and its static function `create` is tcpSocketCreate here. What the host
// answers in pieces is a type of rontolisp-java-api:
//
//   stream<tcp-socket>              WitStream<TcpSocket>, the connections, one a read
//   stream<u8> the host answers     an InputStream
//   stream<u8> the program hands    a Consumer<OutputStream>, handed the writable end
//   future<result<_, error-code>>   a WitFuture<Void>, whose get() throws the error arm
//
// A result is a WitResult, or its ok arm with the error arm thrown as a WitException:
// getLocalAddress says the first, the other methods the second. connect is an async
// func, a method like any other, which waits for the host's answer.

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.function.Consumer;

import am.ik.rontolisp.wit.WitFuture;
import am.ik.rontolisp.wit.WitImport;
import am.ik.rontolisp.wit.WitResult;
import am.ik.rontolisp.wit.WitStream;

@WitImport(value = "wasi:sockets/types@0.3.0", wit = "wit/sockets.wit")
public interface Sockets {

    // create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>
    TcpSocket tcpSocketCreate(IpAddressFamily addressFamily);

    interface TcpSocket extends AutoCloseable {

        // bind: func(local-address: ip-socket-address) -> result<_, error-code>
        void bind(IpSocketAddress localAddress);

        // connect: async func(remote-address: ip-socket-address) -> result<_, error-code>
        void connect(IpSocketAddress remoteAddress);

        // listen: func() -> result<stream<tcp-socket>, error-code>
        WitStream<TcpSocket> listen();

        // send: func(data: stream<u8>) -> future<result<_, error-code>>
        WitFuture<Void> send(Consumer<OutputStream> data);

        // receive: func() -> tuple<stream<u8>, future<result<_, error-code>>>
        Received receive();

        // get-local-address: func() -> result<ip-socket-address, error-code>
        WitResult<IpSocketAddress, ErrorCode> getLocalAddress();

        @Override
        void close();
    }

    // the tuples: a record each, its components the tuple's
    record Received(InputStream data, WitFuture<Void> done) {
    }

    record Ipv4Address(byte a, byte b, byte c, byte d) {
    }

    record Ipv6Address(short a, short b, short c, short d, short e, short f, short g, short h) {
    }

    record Ipv4SocketAddress(short port, Ipv4Address address) {
    }

    record Ipv6SocketAddress(short port, int flowInfo, Ipv6Address address, int scopeId) {
    }

    sealed interface IpSocketAddress {

        record Ipv4(Ipv4SocketAddress value) implements IpSocketAddress {
        }

        record Ipv6(Ipv6SocketAddress value) implements IpSocketAddress {
        }
    }

    enum IpAddressFamily {
        IPV4, IPV6
    }

    // every case of the host's variant: a host checks the type it is asked for whole
    sealed interface ErrorCode {

        record AccessDenied() implements ErrorCode {
        }

        record NotSupported() implements ErrorCode {
        }

        record InvalidArgument() implements ErrorCode {
        }

        record OutOfMemory() implements ErrorCode {
        }

        record Timeout() implements ErrorCode {
        }

        record InvalidState() implements ErrorCode {
        }

        record AddressNotBindable() implements ErrorCode {
        }

        record AddressInUse() implements ErrorCode {
        }

        record RemoteUnreachable() implements ErrorCode {
        }

        record ConnectionRefused() implements ErrorCode {
        }

        record ConnectionBroken() implements ErrorCode {
        }

        record ConnectionReset() implements ErrorCode {
        }

        record ConnectionAborted() implements ErrorCode {
        }

        record DatagramTooLarge() implements ErrorCode {
        }

        record Other(Optional<String> value) implements ErrorCode {
        }
    }
}
