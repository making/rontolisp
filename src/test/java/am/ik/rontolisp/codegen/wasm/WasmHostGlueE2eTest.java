package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
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

	// The CLI's --no-wasi --host-fetch reactor pipeline, in its order.
	private static List<LispVal> program() {
		return program(MODULE);
	}

	private static List<LispVal> program(String source) {
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(source, Features.WASM_REACTOR));
		loaded = HostFetchLibrary.process(loaded);
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true);
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
