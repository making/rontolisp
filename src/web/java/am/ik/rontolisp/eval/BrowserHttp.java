package am.ik.rontolisp.eval;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

/**
 * Browser-side HTTP for the playground, used by the Web Image substitution of
 * {@code HttpSupport} (see {@code src/web/java/.../eval/Target_HttpSupport.java}).
 *
 * <p>
 * A WebAssembly guest cannot {@code await} the Promise returned by the browser
 * {@code fetch()} API (Web Image has neither JS Promise Integration nor threads), so two
 * strategies are used:
 * <ul>
 * <li><strong>Async path</strong> ({@link #start} / {@link #awaitResponse}): when the
 * runtime is inside a Web Worker on a cross-origin-isolated page, {@code start} posts the
 * request (with a growable {@code SharedArrayBuffer}) to the main thread -- which runs
 * the real {@code fetch()} concurrently (see {@code brokerFetch} in
 * {@code playground.html} / {@code ronto-worker.js}) -- and returns a request id
 * immediately, so multiple requests overlap. {@code awaitResponse} then blocks the worker
 * with {@code Atomics.wait} until the response bytes land in the buffer.</li>
 * <li><strong>Sync fallback</strong> ({@link #request}): on the main thread or without
 * cross-origin isolation, a <strong>synchronous</strong> {@code XMLHttpRequest} at fetch
 * time (no overlap).</li>
 * </ul>
 * Either way the response is marshaled as a single string with three {@code U+0001}
 * -separated fields ({@code status}, raw response headers, body), or {@code "ERR"}
 * followed by a message when the request fails (which, in a browser, includes
 * same-origin-policy / CORS rejections).
 */
public final class BrowserHttp {

	private BrowserHttp() {
	}

	/**
	 * Start an asynchronous HTTP request via the main-thread fetch broker.
	 * @param method the HTTP method
	 * @param url the request URL
	 * @param reqHeaders request headers as alternating {@code name\nvalue\n...} lines
	 * @param body the request body (used only when {@code hasBody} is {@code "1"})
	 * @param hasBody {@code "1"} when a request body is present, {@code "0"} otherwise
	 * @return the request id to pass to {@link #awaitResponse}, or {@code "sync"} when
	 * the async path is unavailable (not in a worker, or no cross-origin isolation) and
	 * the caller must fall back to {@link #request}
	 */
	@JS(args = { "method", "url", "reqHeaders", "body", "hasBody" },
			value = """
					try {
					  if (typeof importScripts !== 'function' || typeof SharedArrayBuffer !== 'function'
					      || typeof SharedArrayBuffer.prototype.grow !== 'function') {
					    return 'sync';
					  }
					  var sab = new SharedArrayBuffer(65536, { maxByteLength: 268435456 });
					  globalThis.__rontoFetchSeq = (globalThis.__rontoFetchSeq || 0) + 1;
					  var id = '' + globalThis.__rontoFetchSeq;
					  (globalThis.__rontoFetches = globalThis.__rontoFetches || {})[id] = sab;
					  postMessage({ type: 'ronto-fetch', sab: sab, method: method, url: url,
					                reqHeaders: reqHeaders, body: hasBody === '1' ? body : null });
					  return id;
					} catch (e) {
					  return 'sync';
					}
					""")
	public static native JSString start(JSString method, JSString url, JSString reqHeaders, JSString body,
			JSString hasBody);

	/**
	 * Block until the response for the given request id arrives, and return it. The
	 * buffer layout written by the main-thread broker is {@code [i32 state, i32 length]}
	 * followed by the UTF-8 payload at byte offset 8; {@code state} flips to non-zero
	 * (with {@code Atomics.notify}) once the payload is complete. The bytes are copied
	 * out of the shared buffer before decoding ({@code TextDecoder} rejects
	 * {@code SharedArrayBuffer}-backed views).
	 * @param id the request id returned by {@link #start}
	 * @return the marshaled response (same format as {@link #request})
	 */
	@JS(args = { "id" }, value = """
			var sab = globalThis.__rontoFetches[id];
			delete globalThis.__rontoFetches[id];
			var i32 = new Int32Array(sab, 0, 2);
			Atomics.wait(i32, 0, 0);
			var len = i32[1];
			var bytes = new Uint8Array(len);
			bytes.set(new Uint8Array(sab, 8, len));
			return new TextDecoder().decode(bytes);
			""")
	public static native JSString awaitResponse(JSString id);

	/**
	 * Perform a synchronous HTTP request from the browser.
	 * @param method the HTTP method
	 * @param url the request URL
	 * @param reqHeaders request headers as alternating {@code name\nvalue\n...} lines
	 * @param body the request body (used only when {@code hasBody} is {@code "1"})
	 * @param hasBody {@code "1"} when a request body is present, {@code "0"} otherwise
	 * @return {@code statusheadersbody}, or {@code ERRmessage} on failure
	 */
	@JS(args = { "method", "url", "reqHeaders", "body", "hasBody" },
			value = """
					try {
					  var xhr = new XMLHttpRequest();
					  xhr.open(method, url, false);
					  var hs = reqHeaders.split('\\n');
					  for (var i = 0; i + 1 < hs.length; i += 2) {
					    if (hs[i].length > 0) { try { xhr.setRequestHeader(hs[i], hs[i + 1]); } catch (e) {} }
					  }
					  xhr.send(hasBody === '1' ? body : null);
					  return '' + xhr.status + '\\u0001' + xhr.getAllResponseHeaders() + '\\u0001' + xhr.responseText;
					} catch (e) {
					  return 'ERR\\u0001' + ((e && e.message) ? e.message : 'request failed');
					}
					""")
	public static native JSString request(JSString method, JSString url, JSString reqHeaders, JSString body,
			JSString hasBody);

}
