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
 * lines of driver, which is all of
 * {@code examples/cloudflare-workers/btc-ticker/src/index.js}.
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmHostGlueE2eTest#jspiIsAvailable")
class WasmHostGlueE2eTest {

	private static final String JSPI_FLAG = "--experimental-wasm-jspi";

	static boolean jspiIsAvailable() {
		try {
			Process process = new ProcessBuilder("node", JSPI_FLAG, "-e",
					"if (typeof WebAssembly.Suspending !== 'function') process.exit(1)")
				.start();
			return process.waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

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
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true);
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
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true);
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
			  ;; carries the "body" key.
			  (if (equal (getf env :path-info) "/nothing")
			      (list 204 nil (list ""))
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
			                            (car up) (car (cdr up))))))))
			(rontolisp:http-handler 'app)
			""";

	// The emitted host, driven as a host: `worker(module)` is the WHOLE Worker, and the
	// three lines below are all of examples/cloudflare-workers/btc-ticker/src/index.js.
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
			server.close();
			""";

	@Test
	void theEmittedWorkerHalfIsTheWholeHostOnTheEnvelopeBoundary() throws Exception {
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true);
		Files.write(this.tempDir.resolve("glue.wasm"),
				compiler.compile(program(ENVELOPE_MODULE, HostBoundary.ENVELOPE)));
		Files.writeString(this.tempDir.resolve("glue.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("glue.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("envelope.mjs");
		Files.writeString(driver, ENVELOPE_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver).lines().toList()).containsExactly("imports env.fetch",
				"200 text/plain GET /hello 203.0.113.7 [] 200 こんにちは", "POST /echo NIL [ほげ] 200 こんにちは",
				"overlapped GET /a NIL [] 200 こんにちは | GET /b NIL [] 200 こんにちは", "empty 204 0");
	}

	// The STREAMING boundary through the SAME `worker(module)`: what the envelope one
	// gets for free, on the shape whose bodies leave the envelope. The handler proves
	// the two things that shape exists for -- a binary request body crossing exactly,
	// and a reply half-drained before the next fetch, whose leftover octets the derived
	// env.fetch has to discard or the second drain answers the first reply's.
	private static final String STREAMING_MODULE = """
			(rontolisp:async-defun grab (url) (rontolisp:await (rontolisp:fetch url)))
			(defun read-bytes (stream)
			  (if (null stream)
			      nil
			      (let ((out nil))
			        (do ((b (read-byte stream nil nil) (read-byte stream nil nil)))
			            ((null b))
			          (setq out (cons b out)))
			        (nreverse out))))
			(rontolisp:async-defun app (env)
			  (if (equal (getf env :path-info) "/echo")
			      (let ((got (read-bytes (getf env :raw-body))))
			        (list 200 (list :content-type "text/plain")
			              (list (format nil "n=~a ~a" (length got) got))))
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
			              (list (format nil "~a ~a" (subseq all 0 6) (length all)))))))
			;; :buffered is the Clack raw-body shape, which is what read-byte needs.
			(rontolisp:http-handler 'app :raw-body :buffered)
			""";

	private static final String STREAMING_DRIVER = """
			import { createServer } from "node:http";
			import { readFileSync } from "node:fs";
			import { worker } from "./glue.js";

			const server = createServer((request, response) => {
			  request.resume();
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

			// A reply half-drained, then a second fetch: the derived env.fetch drops what
			// the glue is still holding, so the second drain is its own 300 octets.
			const two = await app.fetch(new Request(`http://x.test/?${port}`));
			console.log("superseded", (await two.text()).trim());
			server.close();
			""";

	@Test
	void theEmittedWorkerHalfServesTheStreamingBoundaryToo() throws Exception {
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true);
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
				"binary n=3 (255 254 65)", "superseded BBBBBB 300");
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
		return GrayStreamsLibrary
			.process(LispPreludeLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))));
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
