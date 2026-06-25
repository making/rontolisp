package am.ik.rontolisp.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Performs outgoing HTTP requests for the interpreter backend, using the JDK
 * {@link HttpClient}. The result is a backend-neutral {@link HttpResult} that the
 * interpreter converts into Lisp values. The {@code method} parameter is threaded through
 * so future HTTP methods (POST, etc.) can reuse this entry point; the {@code rontolisp}
 * package currently only exposes GET.
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
	 * Sends an HTTP request and reads the full response body as a string.
	 * @param method the HTTP method (e.g. {@code "GET"})
	 * @param url the request URL
	 * @param requestHeaders the request headers to set
	 * @return the response status, body and headers
	 */
	static HttpResult request(String method, String url, List<Header> requestHeaders) {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.method(method, HttpRequest.BodyPublishers.noBody());
			for (Header header : requestHeaders) {
				builder.header(header.name(), header.value());
			}
			HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			List<Header> responseHeaders = new ArrayList<>();
			for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
				for (String value : entry.getValue()) {
					responseHeaders.add(new Header(entry.getKey(), value));
				}
			}
			return new HttpResult(response.statusCode(), response.body(), responseHeaders);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("HTTP request interrupted: " + ex.getMessage(), ex);
		}
		catch (Exception ex) {
			throw new IllegalStateException("HTTP request failed: " + ex.getMessage(), ex);
		}
	}

}
