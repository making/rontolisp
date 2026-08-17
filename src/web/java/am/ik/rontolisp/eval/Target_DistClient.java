package am.ik.rontolisp.eval;

import java.io.IOException;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link DistClient}. The browser playground compiles the
 * interpreter to WebAssembly with GraalVM Web Image, where {@code java.net.http.HttpClient}
 * cannot be compiled (it pulls in virtual threads and the TLS/host-socket stack the browser
 * sandbox does not provide). {@code ql:quickload} needs both network and a filesystem cache
 * the browser lacks, so these substitutions cut the HTTP stack out of the image and make it
 * report that it is unavailable: {@link #createDefault()} no longer references the real
 * {@code httpGet} downloader (removing the only path to {@code HttpClient}), and any
 * downloader call raises a clear error. Compiled only under the {@code web} Maven profile
 * (it lives in {@code src/web/java}); the JVM and regular native-image builds use the real
 * {@link DistClient}.
 */
@TargetClass(DistClient.class)
final class Target_DistClient {

	@Substitute
	static DistClient createDefault() {
		// The inline lambda avoids the `DistClient::httpGet` method reference of the
		// real createDefault, so httpGet -- and therefore HttpClient -- is unreachable.
		return new DistClient(DistClient.defaultBase(), url -> {
			throw new IOException(
					"ql:quickload is not available in the browser playground (no network or filesystem access)");
		});
	}

	@Substitute
	static byte[] httpGet(String url) throws IOException {
		throw new IOException("ql:quickload is not available in the browser playground (no network or filesystem access)");
	}

}
