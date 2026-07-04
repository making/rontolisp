package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link HttpHandlerSupport}. The browser playground compiles
 * the interpreter to WebAssembly with GraalVM Web Image, where the JDK
 * {@code com.sun.net.httpserver.HttpServer} cannot be compiled (the browser sandbox
 * provides no host server socket). The interpreter only ever calls {@link #serve}, so
 * substituting it to signal an error keeps {@code HttpServer} out of the image; it is
 * compiled only under the {@code web} Maven profile (it lives in {@code src/web/java}),
 * and the JVM and regular native-image builds use the real {@link HttpHandlerSupport}.
 */
@TargetClass(HttpHandlerSupport.class)
final class Target_HttpHandlerSupport {

	@Substitute
	static void serve(int port, HttpHandlerSupport.Handler handler) {
		throw new LispEvalException(
				"http-handler: serving HTTP is not supported in the browser playground");
	}

}
