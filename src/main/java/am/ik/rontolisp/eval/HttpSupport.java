package am.ik.rontolisp.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * Performs outgoing HTTP requests for the interpreter backend, using the JDK
 * {@link HttpClient}. The result is a backend-neutral {@link HttpResult} that the
 * interpreter converts into Lisp values. The {@code method} and {@code body} parameters
 * carry the request method and optional request body (e.g. for POST/PUT).
 */
final class HttpSupport {

	private HttpSupport() {
	}

	/** A single HTTP header (name and value). */
	record Header(String name, String value) {
	}

	/**
	 * The outcome of an HTTP request: status code, response body and response headers.
	 */
	record HttpResult(int status, String body, List<Header> headers) {
	}

	/**
	 * Starts an HTTP request asynchronously (JavaScript {@code fetch}-style) via
	 * {@link HttpClient#sendAsync}: the request is in flight when this returns and the
	 * future settles when the full response has been read. Request-building failures
	 * (e.g. a malformed URL) fail the returned future rather than throwing, so every
	 * failure surfaces at await time. This is the seam the browser playground substitutes
	 * (see {@code src/web/java/.../eval/Target_HttpSupport.java}), where the request is
	 * performed synchronously and an already-completed future is returned. The
	 * per-request client is intentionally not closed: {@code close()} would block until
	 * the in-flight request completes, and the compiled JVM backend leaves its client to
	 * be garbage-collected the same way.
	 * @param method the HTTP method (e.g. {@code "GET"}, {@code "POST"})
	 * @param url the request URL
	 * @param requestHeaders the request headers to set
	 * @param body the request body, or {@code null} for no body
	 * @return a future settling to the response status, body and headers
	 */
	static CompletableFuture<HttpResult> requestAsync(String method, String url, List<Header> requestHeaders,
			@Nullable String body) {
		HttpClient client;
		HttpRequest request;
		try {
			HttpRequest.BodyPublisher publisher = (body == null) ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body);
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).method(method, publisher);
			for (Header header : requestHeaders) {
				builder.header(header.name(), header.value());
			}
			request = builder.build();
			client = HttpClient.newHttpClient();
		}
		catch (RuntimeException ex) {
			return CompletableFuture
				.failedFuture(new IllegalStateException("HTTP request failed: " + ex.getMessage(), ex));
		}
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(HttpSupport::toResult);
	}

	private static HttpResult toResult(HttpResponse<String> response) {
		List<Header> responseHeaders = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
			for (String value : entry.getValue()) {
				responseHeaders.add(new Header(entry.getKey(), value));
			}
		}
		return new HttpResult(response.statusCode(), response.body(), responseHeaders);
	}

}
