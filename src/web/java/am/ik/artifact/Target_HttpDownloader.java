package am.ik.artifact;

import java.io.IOException;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link HttpDownloader}. The browser playground compiles the
 * interpreter to WebAssembly with GraalVM Web Image, where {@code java.net.http.HttpClient}
 * cannot be compiled (it pulls in virtual threads and the TLS/host-socket stack the browser
 * sandbox does not provide), and an artifact cache needs a filesystem the browser lacks.
 * Substituting {@link HttpDownloader#get} removes the only path to {@code HttpClient}
 * and refuses every download; each consumer prefixes the refusal with its own operation
 * ({@code ql:quickload: ...}), so the failure lands at the call site that asked. Compiled
 * only under the {@code web} Maven profile (it lives in {@code src/web/java}); the JVM and
 * regular native-image builds use the real {@link HttpDownloader}.
 */
@TargetClass(HttpDownloader.class)
final class Target_HttpDownloader {

	@Substitute
	public byte[] get(String url) throws IOException {
		throw new IOException("downloading " + url
				+ " is not available in the browser playground (no network or filesystem access)");
	}

}
