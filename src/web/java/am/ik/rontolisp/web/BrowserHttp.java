package am.ik.rontolisp.web;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

/**
 * Browser-side HTTP for the playground, used by the Web Image substitution of
 * {@code HttpSupport} (see {@code src/web/java/.../eval/Target_HttpSupport.java}).
 *
 * <p>
 * The rontolisp interpreter's {@code fetch} is a synchronous call, and a WebAssembly guest
 * cannot {@code await} the Promise returned by the browser {@code fetch()} API without JS
 * Promise Integration. So this uses a <strong>synchronous</strong> {@code XMLHttpRequest}
 * instead. The result is marshaled back as a single string with three {@code U+0001}
 * -separated fields ({@code status}, raw response headers, body), or {@code "ERR"} followed
 * by a message when the request fails (which, in a browser, includes same-origin-policy /
 * CORS rejections).
 */
public final class BrowserHttp {

	private BrowserHttp() {
	}

	/**
	 * Perform a synchronous HTTP request from the browser.
	 * @param method the HTTP method
	 * @param url the request URL
	 * @param reqHeaders request headers as alternating {@code name\nvalue\n...} lines
	 * @return {@code statusheadersbody}, or {@code ERRmessage} on failure
	 */
	@JS(args = { "method", "url", "reqHeaders" },
			value = """
					try {
					  var xhr = new XMLHttpRequest();
					  xhr.open(method, url, false);
					  var hs = reqHeaders.split('\\n');
					  for (var i = 0; i + 1 < hs.length; i += 2) {
					    if (hs[i].length > 0) { try { xhr.setRequestHeader(hs[i], hs[i + 1]); } catch (e) {} }
					  }
					  xhr.send(null);
					  return '' + xhr.status + '\\u0001' + xhr.getAllResponseHeaders() + '\\u0001' + xhr.responseText;
					} catch (e) {
					  return 'ERR\\u0001' + ((e && e.message) ? e.message : 'request failed');
					}
					""")
	public static native JSString request(JSString method, JSString url, JSString reqHeaders);

}
