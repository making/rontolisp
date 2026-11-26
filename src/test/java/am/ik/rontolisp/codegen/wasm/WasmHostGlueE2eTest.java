package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.GrayStreamsLibrary;
import am.ik.rontolisp.eval.HostFetchLibrary;
import am.ik.rontolisp.eval.HttpReactorInliner;
import am.ik.rontolisp.eval.HttpReactorLibrary;
import am.ik.rontolisp.eval.HttpServerLibrary;
import am.ik.rontolisp.eval.JsonLibrary;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.HostBoundaryRuns;
import am.ik.rontolisp.testsupport.TcpLinePeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that the GENERATED host glue ({@code --emit-js-glue},
 * {@code compiler/HostGlueEmitter}) really carries the boundary it is written from -- one
 * file, driven both ways, which is the claim the {@code :async t} contract makes and the
 * reason the wrapping is the host's decision rather than the declaration's:
 *
 * <ul>
 * <li>a SUSPENDING host marks two of its four entries with the glue's own
 * {@code suspending()}; the module is then entered through {@code WebAssembly.promising},
 * the reply body arrives in chunks that fall inside code points, and TWO overlapping
 * calls both answer because the generated queue admits one at a time -- without it the
 * second would be refused by the module's re-entry guard;</li>
 * <li>a SYNCHRONOUS host marks nothing, gets the same answers from the same file, and its
 * entry point returns a STRING rather than a promise (nothing was wrapped, so nothing
 * suspends);</li>
 * <li>a host that answers a promise WITHOUT marking the entry is told so by name, rather
 * than handing the module a Promise where an i32 was due.</li>
 * </ul>
 *
 * The module is the same in all three, and so is the glue file: it is generated once,
 * from the declarations, and neither run edits it.
 *
 * <p>
 * The last case is the OTHER boundary ({@code --host-boundary=envelope}), where the file
 * writes the host too: {@code worker(module)} against a real {@code node:http} upstream
 * and real {@code Request}/{@code Response} objects, with nothing supplied but the one
 * thing a generated file may not guess -- which header carries the client address. Three
 * lines of driver, which is all of the {@code index.js} {@code --emit-worker-entry}
 * writes for {@code examples/cloudflare-workers/btc-ticker}.
 */
@EnabledIf("am.ik.rontolisp.testsupport.HostBoundaryRuns#jspiAvailable")
class WasmHostGlueE2eTest {

	private static final String JSPI_FLAG = HostBoundaryRuns.JSPI_FLAG;

	@TempDir
	Path tempDir;

	// A fetching reactor: the shape examples/cloudflare-workers/dog-fetcher is, in four
	// lines. The handler echoes the upstream body, so the pull path is what answers.
	private static final String MODULE = """
			(rontolisp:async-defun upstream (url)
			  (rontolisp:await (rontolisp:fetch url)))
			(defun app (env)
			  (declare (ignore env))
			  (let* ((res (rontolisp::%future-force (upstream "https://example.test/x")))
			         (body (rontolisp::%future-force (rontolisp:read-all (getf res :body)))))
			    (list 200 (list :content-type "text/plain") (list (concatenate 'string "up:" body)))))
			(rontolisp:http-handler 'app)
			""";

	// The two hosts, in one driver: the same generated module imported once, instantiated
	// twice. Only the four callbacks differ -- which is the whole point of the file.
	private static final String DRIVER = """
			import { readFileSync } from "node:fs";
			import { instantiate, suspending } from "./glue.js";

			const module = new WebAssembly.Module(readFileSync(new URL("./glue.wasm", import.meta.url)));
			const dec = new TextDecoder();
			const head = JSON.stringify({ method: "GET", target: "/", headers: {} });
			const body = (chunks) => dec.decode(new Uint8Array(chunks.flatMap((c) => [...c])));

			// 1. the suspending host: fetch and the reply body answer promises, and the
			// reply arrives in 4-octet chunks that cut a multi-byte code point in half.
			let async_chunks = [], async_out = [];
			const suspendingHost = instantiate(module, {
			  env: {
			    fetch: suspending(async (request) => {
			      await new Promise((r) => setTimeout(r, 1));
			      const octets = new TextEncoder().encode("\\u3053\\u3093\\u306b\\u3061\\u306f");
			      async_chunks = [];
			      for (let i = 0; i < octets.length; i += 4) async_chunks.push(octets.subarray(i, i + 4));
			      return JSON.stringify({ status: 200, headers: [] });
			    }),
			    readResponseBody: suspending(async () => {
			      await new Promise((r) => setTimeout(r, 1));
			      return async_chunks.shift() ?? null;
			    }),
			    readRequestBody: () => null,
			    writeResponseBody: (chunk) => async_out.push(chunk),
			  },
			});
			const first = suspendingHost.handleRequest(head);
			console.log("promise", typeof first?.then === "function");
			console.log(JSON.parse(await first).status, body(async_out));

			// 2. two OVERLAPPING calls: the generated queue admits one at a time, so both
			// answer -- a host without it would see the module's re-entry guard trap one.
			async_out = [];
			const overlapped = await Promise.allSettled([
			  suspendingHost.handleRequest(head),
			  suspendingHost.handleRequest(head),
			]);
			console.log("overlapped", overlapped.map((r) => r.status).join(","));

			// 3. the SAME file, a synchronous host: nothing marked, nothing wrapped,
			// nothing promising -- and the entry point answers a string, not a promise.
			let sync_chunks = [], sync_out = [];
			const syncHost = instantiate(module, {
			  env: {
			    fetch: () => {
			      sync_chunks = ["sy", "nc"];
			      return JSON.stringify({ status: 200, headers: [] });
			    },
			    readResponseBody: () => sync_chunks.shift() ?? null,
			    readRequestBody: () => null,
			    writeResponseBody: (chunk) => sync_out.push(chunk),
			  },
			});
			const reply = syncHost.handleRequest(head);
			console.log("promise", typeof reply?.then === "function");
			console.log(JSON.parse(reply).status, body(sync_out));

			// 4. an async host function that was never marked is named, at instantiate.
			try {
			  instantiate(module, {
			    env: {
			      fetch: async () => "{}",
			      readResponseBody: () => null,
			      readRequestBody: () => null,
			      writeResponseBody: () => {},
			    },
			  });
			  console.log("unmarked accepted");
			} catch (error) {
			  console.log("unmarked", String(error).includes("host.env.fetch"), String(error).includes("suspending()"));
			}
			""";

	@Test
	void oneGeneratedFileDrivesASuspendingHostAndASynchronousOne() throws Exception {
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(OptimizeLevel.NONE)
			.hostFetch(true)
			.build();
		Files.write(this.tempDir.resolve("glue.wasm"), compiler.compile(program()));
		Files.writeString(this.tempDir.resolve("glue.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("glue.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("driver.mjs");
		Files.writeString(driver, DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver).lines().toList()).containsExactly("promise true", "200 up:こんにちは",
				"overlapped fulfilled,fulfilled", "promise false", "200 up:sync", "unmarked true true");
	}

	// A handler that leaves a reply body half-drained and then fetches again: the host's
	// SOURCE moves under an import inside ONE call, which nothing but the host can know.
	private static final String TWO_FETCHES = """
			(rontolisp:async-defun grab (url) (rontolisp:await (rontolisp:fetch url)))
			(defun app (env)
			  (declare (ignore env))
			  (let* ((r1 (rontolisp::%future-force (grab "https://a.test/1")))
			         (head (rontolisp::%future-force (rontolisp:stream-read (getf r1 :body))))
			         (r2 (rontolisp::%future-force (grab "https://b.test/2")))
			         (all (rontolisp::%future-force (rontolisp:read-all (getf r2 :body)))))
			    (list 200 (list :content-type "text/plain")
			          (list (format nil "~a ~a" (subseq all 0 6) (length all))))))
			(rontolisp:http-handler 'app)
			""";

	private static final String DROP_DRIVER = """
			import { readFileSync } from "node:fs";
			import { instantiate, suspending } from "./glue.js";

			const module = new WebAssembly.Module(readFileSync(new URL("./glue.wasm", import.meta.url)));
			const head = JSON.stringify({ method: "GET", target: "/", headers: {} });

			// ONE host chunk far larger than the module's own pull buffer, so the glue is
			// left holding a remainder the moment the handler stops reading.
			const run = async (drop) => {
			  let pending = [];
			  const out = [];
			  const lisp = instantiate(module, {
			    env: {
			      fetch: suspending(async (request) => {
			        if (drop) lisp.drop("env.readResponseBody");
			        const first = JSON.parse(request).url.endsWith("1");
			        pending = [new TextEncoder().encode((first ? "A" : "B").repeat(first ? 100003 : 300))];
			        return JSON.stringify({ status: 200, headers: [] });
			      }),
			      readResponseBody: suspending(async () => pending.shift() ?? null),
			      readRequestBody: () => null,
			      writeResponseBody: (chunk) => out.push(chunk),
			    },
			  });
			  await lisp.serially((entry) => entry.handleRequest(head));
			  return new TextDecoder().decode(new Uint8Array(out.flatMap((c) => [...c])));
			};

			console.log("dropped", await run(true));
			console.log("kept", await run(false));
			""";

	@Test
	void aHostWhoseSourceMovesInsideOneCallDropsWhatTheGlueStillHolds() throws Exception {
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(OptimizeLevel.NONE)
			.hostFetch(true)
			.build();
		Files.write(this.tempDir.resolve("glue.wasm"), compiler.compile(program(TWO_FETCHES)));
		Files.writeString(this.tempDir.resolve("glue.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("glue.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("drop.mjs");
		Files.writeString(driver, DROP_DRIVER, StandardCharsets.UTF_8);
		// The second reply is its own 300 octets. Without the drop it is the FIRST
		// reply's undrained remainder with the second's octets behind it -- the glue
		// serves them without ever asking the host, so the module-side "superseded body"
		// counter never sees the read either.
		assertThat(runNode(driver).lines().toList()).containsExactly("dropped BBBBBB 300", "kept AAAAAA 34767");
	}

	// The ENVELOPE boundary, where every body rides the head -- so the glue writes the
	// two halves the transport fixes and the host writes nothing at all. The upstream URL
	// comes out of the QUERY STRING because the stub server below binds an ephemeral port
	// (surefire runs two forks with intra-class parallelism 16; a fixed port is a flake).
	private static final String ENVELOPE_MODULE = """
			(rontolisp:async-defun grab (url)
			  (let* ((res (rontolisp:await (rontolisp:fetch url)))
			         (body (rontolisp:await (rontolisp:read-all (getf res :body)))))
			    (list (getf res :status) body)))
			(rontolisp:async-defun app (env)
			  ;; /nothing answers 204 with an empty body -- the one status a Response
			  ;; may NOT be constructed with a body for, and the envelope always
			  ;; carries the "body" key. /boom signals.
			  (cond
			    ((equal (getf env :path-info) "/nothing") (list 204 nil (list "")))
			    ((equal (getf env :path-info) "/boom") (error "secret ~a" 42))
			    (t
			      (let* ((raw (getf env :raw-body))
			             (sent (if raw (rontolisp:await (rontolisp:read-all raw)) ""))
			             (up
			              (rontolisp:await
			               (grab
			                (concatenate 'string "http://127.0.0.1:"
			                             (getf env :query-string) "/up")))))
			        (list 200 (list :content-type "text/plain")
			              (list (format nil "~a ~a ~a [~a] ~a ~a"
			                            (if (eq (getf env :request-method) :post) "POST"
			                                "GET")
			                            (getf env :path-info) (getf env :remote-addr) sent
			                            (car up) (car (cdr up)))))))))
			(rontolisp:http-handler 'app)
			""";

	// The emitted host, driven as a host: `worker(module)` is the WHOLE Worker, and the
	// three lines below are all of the index.js --emit-worker-entry writes for
	// examples/cloudflare-workers/btc-ticker.
	// The upstream is a real node:http server rather than a stubbed env.fetch, because
	// what is under test here is the env.fetch the GLUE wrote -- stubbing it would test
	// nothing.
	private static final String ENVELOPE_DRIVER = """
			import { createServer } from "node:http";
			import { readFileSync } from "node:fs";
			import { worker } from "./glue.js";

			const server = createServer((request, response) => {
			  request.resume();
			  request.on("end", () => {
			    response.writeHead(200, { "content-type": "text/plain" });
			    response.end("\\u3053\\u3093\\u306b\\u3061\\u306f");
			  });
			});
			await new Promise((r) => server.listen(0, "127.0.0.1", r));
			const port = server.address().port;

			const module = new WebAssembly.Module(readFileSync(new URL("./glue.wasm", import.meta.url)));
			console.log("imports", WebAssembly.Module.imports(module).map((i) => i.module + "." + i.name).join(","));

			// Which header carries the client address is the platform's business, so the
			// generated file does not guess -- this is the one thing it asks for.
			const app = worker(module, { remoteAddr: (r) => r.headers.get("x-real-ip") });

			const get = await app.fetch(
			  new Request(`http://x.test/hello?${port}`, { headers: { "x-real-ip": "203.0.113.7" } }),
			);
			console.log(get.status, get.headers.get("content-type"), (await get.text()).trim());

			// A request BODY, through the envelope's own "body" key.
			const post = await app.fetch(
			  new Request(`http://x.test/echo?${port}`, { method: "POST", body: "\\u307b\\u3052" }),
			);
			console.log((await post.text()).trim());

			// Two OVERLAPPING requests: worker() runs them through the generated queue, so
			// both answer -- without it the module's re-entry guard would trap one.
			const both = await Promise.all([
			  app.fetch(new Request(`http://x.test/a?${port}`)).then((r) => r.text()),
			  app.fetch(new Request(`http://x.test/b?${port}`)).then((r) => r.text()),
			]);
			console.log("overlapped", both.map((t) => t.trim()).join(" | "));

			// 204 answered with an EMPTY body: the envelope always carries the "body" key,
			// and `new Response("", { status: 204 })` is a TypeError -- so a handler doing
			// the ordinary thing would come back as a 500 with the instance thrown away.
			const empty = await app.fetch(new Request(`http://x.test/nothing?${port}`));
			console.log("empty", empty.status, (await empty.text()).length);

			// A handler that signals: the client gets the plain 500 a socket transport
			// answers, and the condition's report goes to the Worker's log alone.
			const logged = [];
			console.error = (...args) => logged.push(args.join(" "));
			const boom = await app.fetch(new Request(`http://x.test/boom?${port}`));
			console.log("boom", boom.status, boom.headers.get("content-type"), await boom.text(), logged.join("|"));
			server.close();
			""";

	@Test
	void theEmittedWorkerHalfIsTheWholeHostOnTheEnvelopeBoundary() throws Exception {
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(OptimizeLevel.NONE)
			.hostFetch(true)
			.build();
		Files.write(this.tempDir.resolve("glue.wasm"),
				compiler.compile(program(ENVELOPE_MODULE, HostBoundary.ENVELOPE)));
		Files.writeString(this.tempDir.resolve("glue.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("glue.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("envelope.mjs");
		Files.writeString(driver, ENVELOPE_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver).lines().toList()).containsExactly("imports env.fetch",
				"200 text/plain GET /hello 203.0.113.7 [] 200 こんにちは", "POST /echo NIL [ほげ] 200 こんにちは",
				"overlapped GET /a NIL [] 200 こんにちは | GET /b NIL [] 200 こんにちは", "empty 204 0",
				"boom 500 text/plain Internal Server Error HTTP-HANDLER: handler failed: secret 42");
	}

	// The STREAMING boundary through the SAME `worker(module)`: what the envelope one
	// gets for free, on the shape whose bodies leave the envelope. The handler proves
	// the three things that shape exists for -- a binary request body crossing
	// exactly, a binary REPLY relayed as the response body crossing exactly (the
	// dog-relay shape: nothing reads the stream, the transport pulls each octet chunk
	// and pushes it out untouched -- it used to be decoded to text and re-encoded, so
	// a JPEG's ff d8 ff came out c3 bf d8), and a reply half-drained before the next
	// fetch, whose leftover octets the derived env.fetch has to discard or the second
	// drain answers the first reply's.
	private static final String STREAMING_MODULE = """
			(rontolisp:async-defun grab (url) (rontolisp:await (rontolisp:fetch url)))
			(rontolisp:async-defun relay (port)
			  (let ((res (rontolisp:await (grab (concatenate 'string "http://127.0.0.1:" port "/jpeg")))))
			    (list (getf res :status)
			          (list :content-type
			                (cdr (assoc "content-type" (getf res :headers) :test #'string-equal)))
			          (getf res :body))))
			(defun read-bytes (stream)
			  (if (null stream)
			      nil
			      (let ((out nil))
			        (do ((b (read-byte stream nil nil) (read-byte stream nil nil)))
			            ((null b))
			          (setq out (cons b out)))
			        (nreverse out))))
			(rontolisp:async-defun app (env)
			  (cond
			    ((equal (getf env :path-info) "/echo")
			     (let ((got (read-bytes (getf env :raw-body))))
			       (list 200 (list :content-type "text/plain")
			             (list (format nil "n=~a ~a" (length got) got)))))
			    ((equal (getf env :path-info) "/relay")
			     (rontolisp:await (relay (getf env :query-string))))
			    (t
			      (let* ((port (getf env :query-string))
			             (r1
			              (rontolisp:await
			               (grab (concatenate 'string "http://127.0.0.1:" port "/big"))))
			             (head
			              (rontolisp:await (rontolisp:stream-read (getf r1 :body))))
			             (r2
			              (rontolisp:await
			               (grab (concatenate 'string "http://127.0.0.1:" port "/small"))))
			             (all (rontolisp:await (rontolisp:read-all (getf r2 :body)))))
			        (declare (ignore head))
			        (list 200 (list :content-type "text/plain")
			              (list (format nil "~a ~a" (subseq all 0 6) (length all))))))))
			;; :buffered is the Clack raw-body shape, which is what read-byte needs.
			(rontolisp:http-handler 'app :raw-body :buffered)
			""";

	private static final String STREAMING_DRIVER = """
			import { createServer } from "node:http";
			import { readFileSync } from "node:fs";
			import { worker } from "./glue.js";

			const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0x00, 0x41, 0xfe, 0x80, 0xc3, 0xbf]);
			const server = createServer((request, response) => {
			  request.resume();
			  if (request.url.startsWith("/jpeg")) {
			    response.writeHead(200, { "content-type": "image/jpeg" });
			    response.end(jpeg);
			    return;
			  }
			  response.writeHead(200, { "content-type": "text/plain" });
			  response.end(request.url.startsWith("/big") ? "A".repeat(100003) : "B".repeat(300));
			});
			await new Promise((r) => server.listen(0, "127.0.0.1", r));
			const port = server.address().port;

			const module = new WebAssembly.Module(readFileSync(new URL("./glue.wasm", import.meta.url)));
			console.log("imports", WebAssembly.Module.imports(module).map((i) => i.module + "." + i.name).join(","));

			// Three lines of host on the boundary whose bodies are OUT of the envelope.
			const app = worker(module);

			// A binary request body, through the :bytes import rather than the head.
			const echo = await app.fetch(
			  new Request("http://x.test/echo", { method: "POST", body: new Uint8Array([0xff, 0xfe, 0x41]) }),
			);
			console.log("binary", (await echo.text()).trim());

			// A binary reply RELAYED as the response body: the upstream's exact octets,
			// content type and all, through env.readResponseBody -> env.writeResponseBody.
			const relayed = await app.fetch(new Request(`http://x.test/relay?${port}`));
			const octets = new Uint8Array(await relayed.arrayBuffer());
			console.log("relayed", relayed.status, relayed.headers.get("content-type"),
			  octets.length === jpeg.length && octets.every((b, i) => b === jpeg[i]) ? "exact" : Array.from(octets).join(" "));

			// A reply half-drained, then a second fetch: the derived env.fetch drops what
			// the glue is still holding, so the second drain is its own 300 octets.
			const two = await app.fetch(new Request(`http://x.test/?${port}`));
			console.log("superseded", (await two.text()).trim());
			server.close();
			""";

	@Test
	void theEmittedWorkerHalfServesTheStreamingBoundaryToo() throws Exception {
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(OptimizeLevel.NONE)
			.hostFetch(true)
			.build();
		Files.write(this.tempDir.resolve("glue.wasm"),
				compiler.compile(program(STREAMING_MODULE, HostBoundary.STREAMING)));
		Files.writeString(this.tempDir.resolve("glue.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("glue.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("streaming.mjs");
		Files.writeString(driver, STREAMING_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver).lines().toList()).containsExactly(
				"imports env.fetch,env.readResponseBody,env.readRequestBody,env.writeResponseBody",
				// The three octets the ENVELOPE boundary cannot carry: there they arrive
				// as seven, two U+FFFD where two octets were.
				"binary n=3 (255 254 65)", "relayed 200 image/jpeg exact", "superseded BBBBBB 300");
	}

	// A WIT interface the flat set of a core import does not carry -- every kind of value
	// the canonical ABI lowers, a resource, a result's error arm -- and wasi:keyvalue's
	// store, whose host is defaultHost() over a namespace shaped like Workers KV.
	private static final String SHAPES_WIT = """
			package example:shapes@0.1.0;

			interface echo {
			  enum color { red, green, blue }
			  record point { x: s32, y: s32, label: string }
			  record wrapped { inner: u32 }
			  variant shape { circle(f32), square(u64), named(string), nothing }
			  resource counter {
			    constructor(start: s32);
			    bump: func(by: s32) -> s32;
			  }
			  small: func(a: bool, b: s8, c: u8, d: s16, e: u16, f: s32) -> tuple<bool, s8, u8, s16, u16, s32>;
			  wide: func(g: u32, h: s64, i: u64, j: f32, k: f64, l: char) -> tuple<u32, s64, u64, f32, f64, char>;
			  points: func(ps: list<point>) -> list<point>;
			  pick: func(c: color) -> color;
			  shapes: func(s: shape) -> shape;
			  maybe: func(o: option<string>) -> option<string>;
			  checked: func(n: s32) -> result<s32, string>;
			  words: func(ws: list<string>) -> list<string>;
			  pair: func(p: tuple<string, list<u8>>) -> tuple<list<u8>, string>;
			  wrap: func(n: u32) -> wrapped;
			  big: func(n: u64) -> u64;
			  done: func(ok: bool) -> result;
			}
			""";

	private static final String KEYVALUE_WIT = """
			package wasi:keyvalue@0.2.0-draft2;

			interface store {
			  variant error {
			    no-such-store,
			    access-denied,
			    other(string),
			  }
			  record key-response {
			    keys: list<string>,
			    cursor: option<string>,
			  }
			  open: func(identifier: string) -> result<bucket, error>;
			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			    delete: func(key: string) -> result<_, error>;
			    exists: func(key: string) -> result<bool, error>;
			    list-keys: func(cursor: option<string>) -> result<key-response, error>;
			  }
			}
			""";

	private static final String CANONICAL_MODULE = """
			(rontolisp:wit-import "shapes.wit" :interface "example:shapes/echo" :package sh :octets t)
			(rontolisp:wit-import "keyvalue.wit" :interface "wasi:keyvalue/store" :package kv)

			(defun shapes (out)
			  (format out "~s~%" (sh:small t -5 250 -300 65000 -70000))
			  (format out "~s~%" (sh:wide 4000000000 -5000000000 9007199254740993 1.5 2.25 #\\é))
			  (format out "~s~%" (sh:points (list (list :x 1 :y 2 :label "a") (list :x -3 :y 4 :label "bé"))))
			  (format out "~s ~s ~s ~s ~s~%" (sh:pick :green) (sh:shapes '(:circle . 2.5))
			          (sh:shapes '(:square . 12345678901)) (sh:shapes '(:named . "x")) (sh:shapes :nothing))
			  (format out "~s ~s ~s~%" (sh:maybe "hi") (sh:maybe nil) (sh:words (list "x" "yy" "")))
			  (format out "~s ~s~%" (sh:checked 7)
			          (handler-case (sh:checked -1) (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
			  (format out "~s ~s ~s~%"
			          (sh:pair (list "k" (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(0 255 65))))
			          (sh:wrap 4294967295) (sh:big 9007199254740993))
			  (format out "~s ~s~%" (sh:done t)
			          (handler-case (sh:done nil) (rontolisp:wit-error (e) (list :caught (rontolisp:wit-error-payload e)))))
			  (let ((c (sh:counter-new 10)))
			    (format out "~s ~s~%" (sh:counter-bump c 5) (sh:counter-bump c 1))
			    (sh:counter-drop c)))

			(defun store (out)
			  (kv:with-bucket (b (kv:open ""))
			    (kv:bucket-set b "a" "1")
			    (kv:bucket-set b "a" "2")
			    (kv:bucket-set b "b" "é")
			    (format out "~s ~s ~s ~s~%" (kv:bucket-get b "a") (kv:bucket-get b "b") (kv:bucket-exists b "b")
			            (kv:bucket-get b "absent"))
			    (kv:bucket-delete b "b")
			    (format out "~s~%" (kv:bucket-exists b "b"))
			    (let ((page (kv:bucket-list-keys b nil)))
			      (format out "~s ~s~%" page (kv:bucket-list-keys b (getf page :cursor)))))
			  (format out "~s~%" (handler-case (kv:open "nowhere") (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
			  (format out "~s~%" (handler-case (kv:with-bucket (b (kv:open "broken")) (kv:bucket-get b "a"))
			                       (rontolisp:wit-error (e) (rontolisp:wit-error-payload e)))))

			(defun app (env)
			  (let ((out (make-string-output-stream)))
			    (if (string= (getf env :path-info) "/shapes") (shapes out) (store out))
			    (list 200 (list :content-type "text/plain") (list (get-output-stream-string out)))))
			(rontolisp:http-handler 'app)
			""";

	private static final String CANONICAL_DRIVER = """
			import { readFileSync } from "node:fs";
			import { worker, WitError } from "./app.js";

			const module = new WebAssembly.Module(readFileSync(new URL("./app.wasm", import.meta.url)));
			// What a Workers KV namespace answers -- every answer a promise -- and what it
			// was asked; a page of the keys is one key.
			const asked = [];
			const kept = new Map([["y", new Uint8Array([1])], ["z", new Uint8Array([2])]]);
			const namespace = {
			  get: async (key) => (asked.push("get " + key), kept.has(key) ? kept.get(key).slice().buffer : null),
			  put: async (key, value) => (asked.push("put " + key), kept.set(key, new Uint8Array(value))),
			  delete: async (key) => (asked.push("delete " + key), kept.delete(key)),
			  list: async ({ cursor } = {}) => {
			    asked.push("list " + (cursor ?? "-"));
			    const keys = [...kept.keys()].sort();
			    const from = cursor === undefined ? 0 : Number(cursor);
			    return {
			      keys: keys.slice(from, from + 1).map((name) => ({ name })),
			      list_complete: from + 1 >= keys.length,
			      cursor: String(from + 1),
			    };
			  },
			};
			const broken = {
			  ...namespace,
			  get: async () => {
			    throw new Error("KV GET failed: 503 Service Unavailable");
			  },
			};
			const echo = {
			  "[constructor]counter": (start) => ({ n: start }),
			  "[method]counter.bump": (self, by) => (self.n += by),
			  small: (...args) => args,
			  wide: (...args) => args,
			  points: (ps) => ps.map((p) => ({ x: p.y, y: p.x, label: p.label + "!" })),
			  pick: (c) => (c === "green" ? "blue" : "red"),
			  shapes: (s) => s,
			  maybe: (o) => (o === undefined ? "none" : undefined),
			  checked: (n) => {
			    if (n < 0) throw new WitError("negative");
			    return n * 2;
			  },
			  words: (ws) => ws.map((w) => w + w.length),
			  pair: ([k, octets]) => [octets, k + octets.length],
			  wrap: (n) => ({ inner: n }),
			  big: (n) => n,
			  done: (ok) => {
			    if (!ok) throw new WitError(undefined);
			  },
			};
			const served = worker(module, {
			  host: { "example:shapes/echo@0.1.0": echo },
			  keyvalue: { "": namespace, broken },
			});
			const ask = async (path) => (await served.fetch(new Request("http://x" + path), {}, {})).text();
			console.log(await ask("/shapes"));
			console.log(await ask("/store"));
			console.log(asked.join(", "));
			console.log([...kept.keys()].sort().join(" "), new TextDecoder().decode(kept.get("a")));
			""";

	@Test
	void anInterfaceTheFlatSetDoesNotCarryCrossesThroughTheCanonicalAbi() throws Exception {
		Files.writeString(this.tempDir.resolve("shapes.wit"), SHAPES_WIT, StandardCharsets.UTF_8);
		Files.writeString(this.tempDir.resolve("keyvalue.wit"), KEYVALUE_WIT, StandardCharsets.UTF_8);
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, CANONICAL_MODULE, StandardCharsets.UTF_8);
		HostBoundaryRuns.cli(program.toString(), "-o", this.tempDir.resolve("app.wasm").toString(), "--no-wasi",
				"--emit-js-glue");
		Path driver = this.tempDir.resolve("canonical.mjs");
		Files.writeString(driver, CANONICAL_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver).lines().toList()).containsExactly(
				// Every value the host is handed and answers, both ways: the scalars
				// (an f32 out of a variant's joined i64 slot), records, an enum, a
				// variant, an option, a list, a tuple, a single-value record and u64,
				// the error arm thrown as a WitError, a resource's handle.
				"(T -5 250 -300 65000 -70000)", "(4000000000 -5000000000 9007199254740993 1.5 2.25 #\\é)",
				"((:X 2 :Y 1 :LABEL \"a!\") (:X 4 :Y -3 :LABEL \"bé!\"))",
				":BLUE (:CIRCLE . 2.5) (:SQUARE . 12345678901) (:NAMED . \"x\") :NOTHING",
				"NIL \"none\" (\"x1\" \"yy2\" \"0\")", "14 \"negative\"",
				"(#(0 255 65) \"k3\") (:INNER 4294967295) 9007199254740993", "NIL (:CAUGHT NIL)", "15 16", "",
				// The store: a call reads back its own writes, which wait until it is
				// over -- one put a key, the last value -- and a key list follows its
				// cursor; a store the host has not got, and a KV call that fails.
				"\"2\" \"é\" T NIL", "NIL", "(:KEYS (\"y\") :CURSOR \"1\") (:KEYS (\"z\") :CURSOR NIL)",
				":NO-SUCH-STORE", "(:OTHER . \"KV GET failed: 503 Service Unavailable\")", "",
				"get absent, list -, list 1, put a, delete b", "a y z 2");
		// The other direction: a world exporting the same kinds of value, implemented
		// in Lisp and called from JavaScript through the glue's entry points.
		Files.writeString(this.tempDir.resolve("mirror.wit"), MIRROR_WIT, StandardCharsets.UTF_8);
		Path exporter = this.tempDir.resolve("mirror.lisp");
		Files.writeString(exporter, MIRROR_MODULE, StandardCharsets.UTF_8);
		HostBoundaryRuns.cli(exporter.toString(), "-o", this.tempDir.resolve("mirror.wasm").toString(), "--no-wasi",
				"--emit-js-glue");
		Path called = this.tempDir.resolve("mirror.mjs");
		Files.writeString(called, MIRROR_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(called).lines().toList()).containsExactly(
				// the scalars, both ways; a list of records, an enum, a variant (an f32,
				// a u64 and a string in its joined slots), an option, a result's arms, a
				// list of strings, a tuple with octets, a one-field record, a u64 past
				// 2^53, a result with no payload, parameters spilled to memory under a
				// camel-cased name
				"[true,-5,250,-300,65000,-70000]", "[4000000000,\"-5000000000n\",\"9007199254740993n\",1.5,2.25,\"é\"]",
				"[{\"x\":2,\"y\":1,\"label\":\"a!\"},{\"x\":4,\"y\":-3,\"label\":\"bé!\"}]",
				"\"blue\" {\"tag\":\"circle\",\"val\":2.5} {\"tag\":\"square\",\"val\":\"12345678901n\"} "
						+ "{\"tag\":\"named\",\"val\":\"x\"} {\"tag\":\"nothing\"}",
				"undefined \"none\" [\"x1\",\"yy2\",\"0\"]", "14 {\"thrown\":\"negative\"}",
				"[[0,255,65],\"k3\"] {\"inner\":4294967295} \"9007199254740993n\"", "undefined {\"thrown\":null}", "91",
				"the arena is where it was: true");
	}

	// A world whose interface carries every kind of value an export lifts and lowers
	// on a --no-wasi module (a handle is refused on an export).
	private static final String MIRROR_WIT = """
			package example:mirror@0.1.0;

			interface mirror {
			  enum color { red, green, blue }
			  record point { x: s32, y: s32, label: string }
			  record wrapped { inner: u32 }
			  variant shape { circle(f32), square(u64), named(string), nothing }
			  small: func(a: bool, b: s8, c: u8, d: s16, e: u16, f: s32) -> tuple<bool, s8, u8, s16, u16, s32>;
			  wide: func(g: u32, h: s64, i: u64, j: f32, k: f64, l: char) -> tuple<u32, s64, u64, f32, f64, char>;
			  points: func(ps: list<point>) -> list<point>;
			  pick: func(c: color) -> color;
			  shapes: func(s: shape) -> shape;
			  maybe: func(o: option<string>) -> option<string>;
			  checked: func(n: s32) -> result<s32, string>;
			  words: func(ws: list<string>) -> list<string>;
			  pair: func(p: tuple<string, list<u8>>) -> tuple<list<u8>, string>;
			  wrap: func(n: u32) -> wrapped;
			  big: func(n: u64) -> u64;
			  done: func(ok: bool) -> result;
			  spread-out: func(a: point, b: point, c: point, d: point, e: point, f: point) -> s32;
			}

			world mirrored {
			  export mirror;
			}
			""";

	private static final String MIRROR_MODULE = """
			(defun small (a b c d e f) (list a b c d e f))
			(defun wide (g h i j k l) (list g h i j k l))
			(defun points (ps)
			  (mapcar (lambda (p)
			            (list :x (getf p :y) :y (getf p :x) :label (concatenate 'string (getf p :label) "!")))
			          ps))
			(defun pick (c) (if (eq c :green) :blue :red))
			(defun shapes (s) s)
			(defun maybe (o) (if o nil "none"))
			(defun checked (n) (if (< n 0) (error 'rontolisp:wit-error :payload "negative") (* n 2)))
			(defun words (ws) (mapcar (lambda (w) (format nil "~a~d" w (length w))) ws))
			(defun pair (p) (list (second p) (format nil "~a~d" (first p) (length (second p)))))
			(defun wrap (n) (list :inner n))
			(defun big (n) n)
			(defun done (ok) (unless ok (error 'rontolisp:wit-error :payload nil)))
			(defun spread-out (a b c d e f)
			  (reduce #'+ (mapcar (lambda (p) (* (getf p :x) (length (getf p :label)))) (list a b c d e f))))
			(rontolisp:wit-export "mirror.wit" :octets t)
			""";

	private static final String MIRROR_DRIVER = """
			import { readFileSync } from "node:fs";
			import { instantiate, WitError } from "./mirror.js";

			const lisp = instantiate(new WebAssembly.Module(readFileSync(new URL("./mirror.wasm", import.meta.url))));
			const show = (value) =>
			  value === undefined
			    ? "undefined"
			    : JSON.stringify(value, (key, v) =>
			        typeof v === "bigint" ? v + "n" : v instanceof Uint8Array ? [...v] : v,
			      );
			const raised = (thunk) => {
			  try {
			    return thunk();
			  } catch (error) {
			    if (error instanceof WitError) return { thrown: error.payload ?? null };
			    throw error;
			  }
			};
			const mark = lisp.exports.__ronto_alloc_mark();
			console.log(show(lisp.small(true, -5, 250, -300, 65000, -70000)));
			console.log(show(lisp.wide(4000000000, -5000000000n, 9007199254740993n, 1.5, 2.25, "é")));
			console.log(show(lisp.points([{ x: 1, y: 2, label: "a" }, { x: -3, y: 4, label: "bé" }])));
			console.log(
			  [
			    lisp.pick("green"),
			    lisp.shapes({ tag: "circle", val: 2.5 }),
			    lisp.shapes({ tag: "square", val: 12345678901n }),
			    lisp.shapes({ tag: "named", val: "x" }),
			    lisp.shapes({ tag: "nothing" }),
			  ]
			    .map(show)
			    .join(" "),
			);
			console.log([lisp.maybe("hi"), lisp.maybe(undefined), lisp.words(["x", "yy", ""])].map(show).join(" "));
			console.log([lisp.checked(7), raised(() => lisp.checked(-1))].map(show).join(" "));
			console.log(
			  [lisp.pair(["k", new Uint8Array([0, 255, 65])]), lisp.wrap(4294967295), lisp.big(9007199254740993n)]
			    .map(show)
			    .join(" "),
			);
			console.log([lisp.done(true), raised(() => lisp.done(false))].map(show).join(" "));
			const at = (x, label) => ({ x, y: 0, label });
			console.log(show(lisp.spreadOut(at(1, "a"), at(2, "bb"), at(3, "ccc"), at(4, "dddd"), at(5, "eeeee"), at(6, "ffffff"))));
			console.log("the arena is where it was:", lisp.exports.__ronto_alloc_mark() === mark);
			""";

	// A module whose sockets are its host's (--host-sockets): the tcp built-ins and the
	// usocket shim over the glue's host half, here over node:net in place of connect()
	// of cloudflare:sockets. Every wait is a suspension in the host; the host only
	// connects; a socket the host ended fails at its next use.
	private static final String SOCKETS_MODULE = """
			(defun said (peer line)
			  (write-line line peer)
			  (read-line peer))

			(defun refusal (thunk)
			  (handler-case (funcall thunk)
			    (error (c) (princ-to-string c))))

			(defun ask (port)
			  (let ((peer (rontolisp:tcp-connect "127.0.0.1" port))
			        (octets (make-array 256 :element-type '(unsigned-byte 8))))
			    (format nil "~a|~a|~a ~a ~a|~a|~a|~a|~a|~a|~a"
			            (said peer "echo héllo")
			            (progn (write-line "octets" peer)
			                   (list (read-sequence octets peer) (reduce #'+ octets)))
			            (rontolisp:tcp-peer-port peer) (rontolisp:tcp-peer-address peer)
			            (> (rontolisp:tcp-local-port peer) 0)
			            (listen peer)
			            (progn (rontolisp:tcp-set-timeout peer 100)
			                   (refusal (lambda () (said peer "quiet"))))
			            (progn (rontolisp:tcp-set-timeout peer nil)
			                   (said peer "later 50"))
			            (progn (write-line "bye" peer)
			                   (prog1 (list (read-line peer) (read-byte peer nil :eof))
			                     (close peer)))
			            (let ((socket (usocket:socket-connect "127.0.0.1" port)))
			              (prog1 (said (usocket:socket-stream socket) "echo through usocket")
			                (usocket:socket-close socket)))
			            (list (refusal (lambda () (rontolisp:tcp-listen 0)))
			                  (refusal (lambda () (rontolisp:tcp-connect "127.0.0.1" 0)))))))
			(rontolisp:wasm-export 'ask :params '(:s32) :returns :string)

			(defvar *kept* nil)

			(defun keep (port)
			  (setq *kept* (rontolisp:tcp-connect "127.0.0.1" port))
			  (said *kept* "echo kept"))
			(rontolisp:wasm-export 'keep :params '(:s32) :returns :string)

			(defun again ()
			  (refusal (lambda () (said *kept* "echo again"))))
			(rontolisp:wasm-export 'again :returns :string)

			(defun secure (port tls-port)
			  (format nil "~a|~a|~a|~a|~a"
			          (let* ((peer (rontolisp:tcp-connect "127.0.0.1" port))
			                 (go (said peer "starttls"))
			                 (up (rontolisp:tls-upgrade peer "localhost")))
			            (prog1 (list go (eq up peer) (said up "echo sealed"))
			              (close up)))
			          (let ((peer (rontolisp:tls-connect "127.0.0.1" tls-port)))
			            (prog1 (said peer "over tls") (close peer)))
			          (let ((peer (rontolisp:tcp-connect "127.0.0.1" port)))
			            (said peer "starttls")
			            (prog1 (refusal (lambda () (rontolisp:tls-upgrade peer "elsewhere.test")))
			              (close peer)))
			          (refusal (lambda () (rontolisp:tls-connect "127.0.0.1" tls-port :insecure t)))
			          (refusal (lambda () (rontolisp:tls-connect "127.0.0.1" 0)))))
			(rontolisp:wasm-export 'secure :params '(:s32 :s32) :returns :string)
			""";

	private static final String SOCKETS_DRIVER = TcpLinePeer.NODE_CONNECT + """
			import { readFileSync } from "node:fs";
			import { instantiate, defaultHost, hostSockets } from "./app.js";

			const module = new WebAssembly.Module(readFileSync(new URL("./app.wasm", import.meta.url)));
			const sockets = hostSockets(connect);
			const lisp = instantiate(module, defaultHost(sockets));
			const port = Number(process.argv[2]);
			for (const part of (await lisp.ask(port)).split("|")) console.log(part);
			console.log(await lisp.keep(port));
			sockets.expire("Socket closed: gone");
			console.log(await lisp.again());
			for (const part of (await lisp.secure(port, Number(process.argv[3]))).split("|")) console.log(part);
			""";

	@Test
	void aModulesSocketsAreItsHostsConnect() throws Exception {
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, SOCKETS_MODULE, StandardCharsets.UTF_8);
		HostBoundaryRuns.cli(program.toString(), "-o", this.tempDir.resolve("app.wasm").toString(), "--no-wasi",
				"--host-sockets", "--emit-js-glue");
		Path driver = this.tempDir.resolve("sockets.mjs");
		Files.writeString(driver, SOCKETS_DRIVER, StandardCharsets.UTF_8);
		try (TcpLinePeer peer = new TcpLinePeer();
				javax.net.ssl.SSLServerSocket sealed = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(sealed);
			ProcessBuilder builder = new ProcessBuilder("node", JSPI_FLAG, driver.toString(),
					Integer.toString(peer.port()), Integer.toString(sealed.getLocalPort()));
			builder.environment().put("RONTOLISP_TEST_CA", am.ik.rontolisp.TlsTestSupport.pemFiles()[0].toString());
			Process process = builder.redirectErrorStream(false).start();
			String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(process.waitFor()).as("node exit code, stderr: %s", stderr).isZero();
			List<String> lines = stdout.lines().toList();
			assertThat(lines.subList(0, 8)).containsExactly(
					// a line and its reply; every octet as it is; the ends of the
					// connection
					"héllo", "(256 32640)", peer.port() + " 127.0.0.1 T",
					// nothing has come; a read past its deadline, and the next within
					// none
					"NIL", "read-line: Read timed out", "late",
					// the peer's end
					"(NIL EOF)", "through usocket");
			// the host only connects, and a connect nothing takes says why in its words
			assertThat(lines.get(8))
				.startsWith("(tcp-listen: cannot listen on *:0: the host of a --no-wasi module only connects"
						+ " (--host-sockets) tcp-connect: cannot connect to 127.0.0.1:0: ");
			// a socket the host ended
			assertThat(lines.subList(9, 11)).containsExactly("kept", "write-line: Socket closed: gone");
			// TLS: a connection made to speak it where it is, the same stream; one made
			// with it; a certificate not for the name, :insecure and a connect nothing
			// takes, each said by name
			assertThat(lines.subList(11, 13)).containsExactly("(go T sealed)", "over tls");
			assertThat(lines.get(13)).startsWith(
					"tls-upgrade: cannot upgrade the stream to TLS against elsewhere.test: Hostname/IP does not match");
			assertThat(lines.get(14)).isEqualTo("rontolisp:tls-connect: :insecure is not supported on a --no-wasi"
					+ " module's host sockets (the host verifies every certificate)");
			assertThat(lines.subList(15, lines.size())).singleElement()
				.asString()
				.startsWith("tls-connect: cannot connect to 127.0.0.1:0: ");
		}
	}

	// Under --optimize the shake drops a host hook whose cell no surviving body reads
	// (.kb/wasm-export-no-wasi.md), so the glue may call only the hooks that SURVIVED:
	// one that calls a dropped hook throws at instantiate, and every request is a 500.
	private static final String HOOKS_DRIVER = """
			import { readFileSync } from "node:fs";
			import { instantiate } from "./glue.js";

			const module = new WebAssembly.Module(readFileSync(new URL("./glue.wasm", import.meta.url)));
			const hooks = WebAssembly.Module.exports(module)
			  .map((e) => e.name)
			  .filter((n) => n.startsWith("__ronto_s"));
			const lisp = instantiate(module, {});
			console.log(hooks.join(",") || "none", typeof lisp.f(10));
			""";

	@Test
	void theGlueCallsOnlyTheHostHooksTheShakeKept() throws Exception {
		String fib = """
				(defun f (n) (if (<= n 1) n (+ (f (- n 1)) (f (- n 2)))))
				(rontolisp:wasm-export 'f :params '(:s32) :returns :s32)
				""";
		String draws = """
				(defun f (n) (random n))
				(rontolisp:wasm-export 'f :params '(:s32) :returns :s32)
				""";
		String clocks = """
				(defun f (n) (mod (get-universal-time) n))
				(rontolisp:wasm-export 'f :params '(:s32) :returns :s32)
				""";
		assertThat(runHooks(fib, OptimizeLevel.SIZE)).isEqualTo("none number");
		assertThat(runHooks(draws, OptimizeLevel.SIZE)).isEqualTo("__ronto_seed_random number");
		assertThat(runHooks(clocks, OptimizeLevel.SIZE)).isEqualTo("__ronto_set_time number");
		assertThat(runHooks(fib, OptimizeLevel.NONE)).isEqualTo("__ronto_seed_random,__ronto_set_time number");
	}

	private String runHooks(String source, OptimizeLevel optimize) throws Exception {
		WasmLispCompiler compiler = WasmLispCompiler.builder().noWasi(true).optimize(optimize).build();
		Files.write(this.tempDir.resolve("glue.wasm"), compiler.compile(LispReader.readAllFromString(source)));
		Files.writeString(this.tempDir.resolve("glue.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("glue.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("hooks.mjs");
		Files.writeString(driver, HOOKS_DRIVER, StandardCharsets.UTF_8);
		return runNode(driver);
	}

	@Test
	void aSleepUnderHostTimerWaitsInTheHostsTimerAtTheTopLevelAndInACall() throws Exception {
		// --host-timer: sleep is env.sleep, which the glue answers with setTimeout and
		// the
		// clock moved on return; _initialize is entered through promising as every export
		// is, so the top level may wait too, and instantiate answers a promise. Two calls
		// at once take their turns.
		String source = """
				(defun waited (seconds)
				  (let ((before (get-internal-real-time)))
				    (sleep seconds)
				    (- (get-internal-real-time) before)))
				(defvar *loaded* (waited 0.05))
				(defun f (ms) (waited (/ ms 1000)))
				(defun g () *loaded*)
				(rontolisp:wasm-export 'f :params '(:s32) :returns :s32)
				(rontolisp:wasm-export 'g :params '() :returns :s32)
				""";
		WasmLispCompiler compiler = WasmLispCompiler.builder().noWasi(true).build();
		Files.write(this.tempDir.resolve("glue.wasm"),
				compiler.compile(am.ik.rontolisp.eval.HostTimerLibrary.process(LispReader.readAllFromString(source))));
		Files.writeString(this.tempDir.resolve("glue.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("glue.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("timer.mjs");
		Files.writeString(driver, """
				import { readFileSync } from "node:fs";
				import { instantiate } from "./glue.js";

				const module = new WebAssembly.Module(readFileSync(new URL("./glue.wasm", import.meta.url)));
				const made = instantiate(module);
				const lisp = await made;
				const started = Date.now();
				const [a, b] = await Promise.all([lisp.f(80), lisp.f(20)]);
				console.log(typeof made.then, await lisp.g() >= 50, a >= 80, b >= 20, Date.now() - started >= 100);
				""", StandardCharsets.UTF_8);
		assertThat(runNode(driver)).isEqualTo("function true true true true");
	}

	// The CLI's --no-wasi --host-fetch reactor pipeline, in its order.
	private static List<LispVal> program() {
		return program(MODULE);
	}

	private static List<LispVal> program(String source) {
		return program(source, HostBoundary.STREAMING);
	}

	private static List<LispVal> program(String source, HostBoundary boundary) {
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(source, Features.WASM_REACTOR));
		loaded = HostFetchLibrary.process(loaded, boundary);
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true, boundary);
		loaded = HttpReactorLibrary.process(loaded);
		loaded = HttpServerLibrary.process(loaded, false);
		return GrayStreamsLibrary.process(LispPreludeLibrary.process(JsonLibrary
			.process(UserMacroExpander.expand(loaded), am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_GC)));
	}

	private static String runNode(Path driver) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("node", JSPI_FLAG, driver.toString()).redirectErrorStream(false).start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
