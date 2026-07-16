package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Decodes the marshaled response string produced by {@code BrowserHttp} (both the sync
 * XHR path and the main-thread fetch broker use the same wire format) and completes a
 * future with it. Lives outside {@code Target_HttpSupport} because a substitution class
 * may only contain annotated members.
 */
final class BrowserHttpResponses {

	private BrowserHttpResponses() {
	}

	// Completes the future from the marshaled "status\u0001headers\u0001body" response
	// (or "ERR\u0001message" on failure).
	static void completeFromRaw(CompletableFuture<HttpSupport.HttpResult> future, String raw) {
		String[] parts = raw.split("\u0001", 3);
		if (parts.length >= 1 && "ERR".equals(parts[0])) {
			future.completeExceptionally(new IllegalStateException(parts.length > 1 ? parts[1] : "request failed"));
			return;
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
		future.complete(new HttpSupport.HttpResult(status, body, headers));
	}

	/**
	 * Converts the broker's buffered outcome into the streaming shape: the whole body
	 * becomes a single already-settled stream chunk.
	 */
	static HttpSupport.Start toStart(HttpSupport.HttpResult result) {
		return new HttpSupport.Start(result.status(), result.headers(),
				am.ik.rontolisp.LispStream.settled(new am.ik.rontolisp.LispString(result.body())));
	}

}
