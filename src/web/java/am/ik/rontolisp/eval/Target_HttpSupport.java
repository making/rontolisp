package am.ik.rontolisp.eval;

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
 * the browser instead (subject to the browser same-origin policy / CORS). It is compiled
 * only under the {@code web} Maven profile (it lives in {@code src/web/java}); the JVM and
 * regular native-image builds use the real {@link HttpSupport}.
 *
 * <p>
 * Two paths preserve the future API:
 * <ul>
 * <li><strong>Async</strong> (worker + cross-origin isolation): {@code BrowserHttp.start}
 * hands the request to the main-thread fetch broker and returns immediately, so multiple
 * requests genuinely overlap; the returned future is a pending {@link BrowserFuture}
 * whose {@code join()} (reached from {@code rontolisp:await}) blocks the worker via
 * {@code Atomics.wait} until the response arrives and then completes the root.</li>
 * <li><strong>Sync fallback</strong> (main thread, or no cross-origin isolation):
 * {@code BrowserHttp.start} returns {@code "sync"} and the XHR is performed synchronously
 * at fetch time, yielding an already-settled future -- programs behave the same, requests
 * simply do not overlap.</li>
 * </ul>
 * Either way a failed request fails the future rather than throwing, preserving the
 * await-rejection timing of the other backends.
 */
@TargetClass(HttpSupport.class)
final class Target_HttpSupport {

	@Substitute
	static CompletableFuture<HttpSupport.Start> requestAsync(String method, String url,
			List<HttpSupport.Header> requestHeaders, String requestBody) {
		StringBuilder encoded = new StringBuilder();
		for (HttpSupport.Header header : requestHeaders) {
			encoded.append(header.name()).append('\n').append(header.value()).append('\n');
		}
		String headerLines = encoded.toString();
		String bodyOrEmpty = (requestBody == null) ? "" : requestBody;
		String hasBody = (requestBody == null) ? "0" : "1";
		String id = BrowserHttp
			.start(JSString.of(method), JSString.of(url), JSString.of(headerLines), JSString.of(bodyOrEmpty),
					JSString.of(hasBody))
			.asString();
		if ("sync".equals(id)) {
			String raw = BrowserHttp
				.request(JSString.of(method), JSString.of(url), JSString.of(headerLines), JSString.of(bodyOrEmpty),
						JSString.of(hasBody))
				.asString();
			CompletableFuture<HttpSupport.HttpResult> settled = new CompletableFuture<>();
			BrowserHttpResponses.completeFromRaw(settled, raw);
			return settled.thenApply(BrowserHttpResponses::toStart);
		}
		BrowserFuture<HttpSupport.HttpResult> root = new BrowserFuture<>();
		root.settler(() -> {
			if (root.isDone()) {
				return;
			}
			BrowserHttpResponses.completeFromRaw(root, BrowserHttp.awaitResponse(JSString.of(id)).asString());
		});
		return root.thenApply(BrowserHttpResponses::toStart);
	}

}
