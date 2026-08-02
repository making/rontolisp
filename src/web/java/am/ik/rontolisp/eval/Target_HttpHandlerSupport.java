package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link HttpHandlerSupport}. The browser playground compiles
 * the interpreter to WebAssembly with GraalVM Web Image, where the JDK
 * {@code com.sun.net.httpserver.HttpServer} cannot be compiled (the browser sandbox
 * provides no host server socket). The interpreter only ever reaches {@link #serve} (the
 * {@code http-handler} directive) or the {@code %http-server-*} seam (the
 * {@code clack-handler-rontolisp} backend's stoppable acceptor, {@code startServer}
 * /{@code joinServer}/{@code stopServer}/{@code serverPort}), so substituting all five to
 * signal an error keeps {@code HttpServer} -- and the real {@code startServer}'s reachable
 * {@code java.lang.VirtualThread} scheduling code, which Web Image's points-to analysis
 * cannot compile for the {@code svm-wasm} platform -- out of the image; it is compiled
 * only under the {@code web} Maven profile (it lives in {@code src/web/java}), and the JVM
 * and regular native-image builds use the real {@link HttpHandlerSupport}.
 */
@TargetClass(HttpHandlerSupport.class)
final class Target_HttpHandlerSupport {

	@Substitute
	static void serve(int port, HttpHandlerSupport.Handler handler) {
		throw new LispEvalException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static long startServer(int port, Object address, HttpHandlerSupport.Handler handler) {
		throw new LispEvalException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static void joinServer(long handle) {
		throw new LispEvalException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static void stopServer(long handle) {
		throw new LispEvalException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static long serverPort(long handle) {
		throw new LispEvalException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

}
