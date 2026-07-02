/*
 * Web Worker host for the rontolisp Web Image runtime (rontoplayground.js).
 *
 * The interpreter runs here, off the main thread, for two reasons:
 * - long evaluations no longer freeze the page, and
 * - rontolisp:fetch can be truly asynchronous: the Java side (BrowserHttp) posts a
 *   {type:"ronto-fetch"} message with a SharedArrayBuffer to the main thread, which
 *   performs the real browser fetch() while the program keeps running; rontolisp:await
 *   then blocks THIS worker with Atomics.wait until the response bytes arrive in the
 *   buffer. Blocking a worker is fine; blocking the main thread is not.
 *
 * SharedArrayBuffer needs cross-origin isolation (COOP/COEP); when it is unavailable
 * the Java side falls back to a synchronous XHR per request (no overlap).
 */
importScripts("rontoplayground.js");

const config = new GraalVM.Config();
// The default wasm path is derived from the current script (ronto-worker.js), so point
// it back at the runtime module explicitly.
config.wasm_path = "rontoplayground.js.wasm";

GraalVM.run([], config)
	.then(() => {
		self.onmessage = (e) => {
			const m = e.data;
			if (m.type !== "call") return;
			let value;
			try {
				value = globalThis[m.fn](...m.args);
			} catch (err) {
				value = "ERROR:" + (err && err.message ? err.message : String(err));
			}
			self.postMessage({ type: "result", id: m.id, value });
		};
		self.postMessage({ type: "ready", crossOriginIsolated: self.crossOriginIsolated === true });
	})
	.catch((e) => {
		self.postMessage({ type: "init-error", error: String(e) });
	});
