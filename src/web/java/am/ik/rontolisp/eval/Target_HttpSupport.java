package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import am.ik.rontolisp.web.BrowserHttp;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.webimage.api.JSString;

/**
 * Web Image substitution for {@link HttpSupport}. The browser playground compiles the
 * interpreter to WebAssembly with GraalVM Web Image, where {@code java.net.http.HttpClient}
 * cannot be compiled (it pulls in the TLS/security provider stack and host sockets that the
 * browser sandbox does not provide). This substitution routes {@code rontolisp:fetch} to
 * the browser via a synchronous {@code XMLHttpRequest} ({@link BrowserHttp}) instead, so
 * the playground builds <em>and</em> can make real requests (subject to the browser
 * same-origin policy / CORS). It is compiled only under the {@code web} Maven profile (it
 * lives in {@code src/web/java}); the JVM and regular native-image builds use the real
 * {@link HttpSupport}.
 *
 * <p>
 * The promise API is preserved with degraded concurrency: {@code requestAsync} (the seam
 * {@code rontolisp:fetch} starts a request through) cannot use {@code HttpClient.sendAsync}
 * in the single-threaded Web Image runtime, so the XHR is performed synchronously at fetch
 * time and an already-settled future is returned -- {@code rontolisp:await} then just
 * unwraps it. Programs behave the same, requests simply do not overlap. A failed request
 * fails the future rather than throwing, preserving the await-rejection timing of the
 * other backends.
 */
@TargetClass(HttpSupport.class)
final class Target_HttpSupport {

	@Substitute
	static CompletableFuture<HttpSupport.HttpResult> requestAsync(String method, String url,
			List<HttpSupport.Header> requestHeaders, String requestBody) {
		StringBuilder encoded = new StringBuilder();
		for (HttpSupport.Header header : requestHeaders) {
			encoded.append(header.name()).append('\n').append(header.value()).append('\n');
		}
		String raw = BrowserHttp
			.request(JSString.of(method), JSString.of(url), JSString.of(encoded.toString()),
					JSString.of(requestBody == null ? "" : requestBody), JSString.of(requestBody == null ? "0" : "1"))
			.asString();
		String[] parts = raw.split("", 3);
		if (parts.length >= 1 && "ERR".equals(parts[0])) {
			return CompletableFuture
				.failedFuture(new IllegalStateException(parts.length > 1 ? parts[1] : "request failed"));
		}
		int status = Integer.parseInt(parts[0].trim());
		List<HttpSupport.Header> headers = new ArrayList<>();
		if (parts.length > 1) {
			for (String line : parts[1].split("\r\n")) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					headers.add(new HttpSupport.Header(line.substring(0, colon).trim(), line.substring(colon + 1).trim()));
				}
			}
		}
		String body = parts.length > 2 ? parts[2] : "";
		return CompletableFuture.completedFuture(new HttpSupport.HttpResult(status, body, headers));
	}

}
