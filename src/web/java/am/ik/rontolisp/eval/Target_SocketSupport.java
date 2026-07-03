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

	private static final String MESSAGE = "TCP sockets are not supported in the browser playground";

	@Substitute
	static Socket connect(String host, int port) {
		throw new LispEvalException("tcp-connect: " + MESSAGE);
	}

	@Substitute
	static ServerSocket listen(int port, String host) {
		throw new LispEvalException("tcp-listen: " + MESSAGE);
	}

	@Substitute
	static Socket accept(ServerSocket listener) {
		throw new LispEvalException("tcp-accept: " + MESSAGE);
	}

	@Substitute
	static long localPort(Closeable entry) {
		return -1;
	}

	@Substitute
	static String readLine(Socket socket) {
		throw new LispEvalException("read-line: " + MESSAGE);
	}

	@Substitute
	static void writeLine(Socket socket, String line) {
		throw new LispEvalException("write-line: " + MESSAGE);
	}

	@Substitute
	static int readByte(Socket socket) {
		throw new LispEvalException("read-byte: " + MESSAGE);
	}

	@Substitute
	static void writeByte(Socket socket, int value) {
		throw new LispEvalException("write-byte: " + MESSAGE);
	}

}
