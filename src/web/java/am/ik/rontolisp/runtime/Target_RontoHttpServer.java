package am.ik.rontolisp.runtime;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link RontoHttpServer}. The browser playground compiles
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
 * and regular native-image builds use the real {@link RontoHttpServer}. The failure is the
 * runtime package's own exception, which the interpreter's call site turns into a Lisp
 * error -- this package imports nothing of the project's.
 */
@TargetClass(RontoHttpServer.class)
final class Target_RontoHttpServer {

	@Substitute
	static void serve(int port, RontoHttpServer.Handler handler) {
		throw new RontoHttpServer.ServerException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static long startServer(int port, Object address, RontoHttpServer.Handler handler) {
		throw new RontoHttpServer.ServerException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static void joinServer(long handle) {
		throw new RontoHttpServer.ServerException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static void stopServer(long handle) {
		throw new RontoHttpServer.ServerException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

	@Substitute
	static long serverPort(long handle) {
		throw new RontoHttpServer.ServerException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

}
