package am.ik.rontolisp.eval;

import java.io.Closeable;
import java.net.ServerSocket;
import java.net.Socket;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link SocketSupport}. The browser playground compiles the
 * interpreter to WebAssembly with GraalVM Web Image, where {@code java.net.Socket} cannot
 * be compiled (the browser sandbox provides no host TCP sockets). Every operation signals
 * an error; unlike {@code rontolisp:fetch} (which the browser can route through its own
 * {@code fetch}), raw TCP has no browser equivalent. It is compiled only under the
 * {@code web} Maven profile (it lives in {@code src/web/java}); the JVM and regular
 * native-image builds use the real {@link SocketSupport}.
 */
@TargetClass(SocketSupport.class)
final class Target_SocketSupport {

	// No fields here: every member of a @TargetClass substitution must carry
	// @Delete/@Alias/@Inject, so the shared message is inlined at each use.

	@Substitute
	static Socket connect(String host, int port) {
		throw new LispEvalException("tcp-connect: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static Socket connectTls(String host, int port, boolean insecure) {
		throw new LispEvalException("tls-connect: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static ServerSocket listen(int port, String host) {
		throw new LispEvalException("tcp-listen: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static ServerSocket listenTls(String keyStorePath, String password, int port, String host) {
		throw new LispEvalException("tls-listen: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static ServerSocket listenTlsPem(String certPath, String keyPath, int port, String host) {
		throw new LispEvalException("tls-listen-pem: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static ServerSocket listenTlsP12(String base64KeyStore, String password, int port, String host) {
		throw new LispEvalException("tls-listen-pem: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static Socket accept(ServerSocket listener) {
		throw new LispEvalException("tcp-accept: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static long localPort(Closeable entry) {
		return -1;
	}

	@Substitute
	static String readLine(Socket socket) {
		throw new LispEvalException("read-line: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static void writeLine(Socket socket, String line) {
		throw new LispEvalException("write-line: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static int readByte(Socket socket) {
		throw new LispEvalException("read-byte: TCP sockets are not supported in the browser playground");
	}

	@Substitute
	static void writeByte(Socket socket, int value) {
		throw new LispEvalException("write-byte: TCP sockets are not supported in the browser playground");
	}

}
