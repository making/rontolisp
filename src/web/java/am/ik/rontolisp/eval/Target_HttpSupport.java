package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

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
 */
@TargetClass(HttpSupport.class)
final class Target_HttpSupport {


	@Substitute
	static HttpSupport.HttpResult request(String method, String url, List<HttpSupport.Header> requestHeaders) {
		StringBuilder encoded = new StringBuilder();
		for (HttpSupport.Header header : requestHeaders) {
			encoded.append(header.name()).append('\n').append(header.value()).append('\n');
		}
		String raw = BrowserHttp.request(JSString.of(method), JSString.of(url), JSString.of(encoded.toString()))
			.asString();
		String[] parts = raw.split("\u0001", 3);
		if (parts.length >= 1 && "ERR".equals(parts[0])) {
			throw new IllegalStateException(parts.length > 1 ? parts[1] : "request failed");
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
		return new HttpSupport.HttpResult(status, body, headers);
	}

}
