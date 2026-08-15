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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code --reentrant} gates ({@code .todo/348}), on node 24 JSPI -- each one the
 * INVERSE of a corruption the re-entry guard was refusing:
 *
 * <ul>
 * <li>the todo-337 reproduction with its expectation inverted: two overlapped calls each
 * binding the same special across a suspend each read their OWN binding back, and the
 * global default survives untouched -- the test that proved the bug proves the per-task
 * store;</li>
 * <li>overlapped {@code :string} boundaries cross exactly: an export's result survives
 * the microtask gap between its return and the host's decode (a park block the host
 * frees), and an import's result crosses in a park block the module frees -- todo-337's
 * second measured corruption, inverted;</li>
 * <li>TWO interleaved {@code :bytes} pull loops keep linear memory flat -- the todo-341
 * finding-2 pin applied to two callers instead of one (the absolute mark-and-restore they
 * could not share is now a recycled park block each);</li>
 * <li>the width the whole item buys: 8 concurrent upstream round trips through ONE
 * envelope-boundary worker instance take about one round trip, not eight -- the
 * dog-fetcher measurement, re-taken against a local upstream.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmReentrantE2eTest#jspiIsAvailable")
class WasmReentrantE2eTest {

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

	private static final String SPECIALS_MODULE = """
			(rontolisp:wasm-import 'pause :from "env" :as "pause" :params '(:int) :returns :int :async t)
			(defvar *ctx* 0)
			(defun observe () *ctx*)
			(rontolisp:async-defun work (n)
			  (let ((*ctx* n))
			    (rontolisp:await (pause n))
			    (+ (* 1000 (observe)) *ctx*)))
			(rontolisp:wasm-export 'work :params '(:int) :returns :int)
			(defun peek () *ctx*)
			(rontolisp:wasm-export 'peek :params '() :returns :int)
			""";

	private static final String SPECIALS_DRIVER = """
			const fs = require('fs');
			const env = { pause: new WebAssembly.Suspending((n) => new Promise((res) => setTimeout(() => res(n), n === 1 ? 60 : 15))) };
			const inst = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[2])), { env });
			inst.exports._initialize();
			const work = WebAssembly.promising(inst.exports.work);
			(async () => {
			  // call 1 parks 60ms; call 2 enters while 1 is parked, parks 15ms, resumes first.
			  const [a, b] = await Promise.all([work(1), work(2)]);
			  console.log(a, b);
			  console.log(inst.exports.peek());
			})();
			""";

	@Test
	void overlappedCallsEachReadTheirOwnDynamicBinding() throws Exception {
		Path wasm = compile("specials.wasm", SPECIALS_MODULE);
		// Each call's binding is its own (the observe() half AND the direct read),
		// and the default is untouched after both -- the pre-guard measurement read
		// the OTHER call's binding back and leaked the outer value.
		assertThat(runNode(driver(SPECIALS_DRIVER), wasm).lines().toList()).containsExactly("1001 2002", "0");
	}

	private static final String PARK_MODULE = """
			(rontolisp:wasm-import 'fetch-word :from "env" :as "fetchWord" :params '(:string) :returns :string :async t)
			(rontolisp:async-defun greet (name)
			  (concatenate 'string "hello, " (rontolisp:await (fetch-word name)) "!"))
			(rontolisp:wasm-export 'greet :params '(:string) :returns :string)

			(rontolisp:wasm-import 'fill-buf :from "env" :as "fillBuf" :params '() :returns :bytes :async t)
			(defvar *big* (make-array 65536 :element-type '(unsigned-byte 8)))
			(rontolisp:async-defun pump (k)
			  (let ((total 0))
			    (dotimes (i k) (setq total (+ total (rontolisp:await (fill-buf *big*)))))
			    total))
			(rontolisp:wasm-export 'pump :params '(:int) :returns :int)
			""";

	private static final String PARK_DRIVER = """
			const fs = require('fs');
			let ex;
			const enc = new TextEncoder(), dec = new TextDecoder();
			// A :string import RESULT is a park block the MODULE frees after copying it out.
			const parkWrite = (s) => {
			  const b = enc.encode(s);
			  const p = ex.__ronto_park_alloc(b.length);
			  new Uint8Array(ex.memory.buffer, p, b.length).set(b);
			  return [p, b.length];
			};
			const bytes = new Uint8Array([0xff, 0xfe, 0x41]);
			const env = {
			  fetchWord: new WebAssembly.Suspending(async (ptr, len) => {
			    const s = dec.decode(new Uint8Array(ex.memory.buffer, ptr, len)); // decoded at call time
			    await new Promise((r) => setTimeout(r, s === 'alpha' ? 40 : 10));
			    return parkWrite(s.toUpperCase());
			  }),
			  fillBuf: new WebAssembly.Suspending(async (ptr, cap) => {
			    await new Promise((r) => setImmediate(r));
			    new Uint8Array(ex.memory.buffer, ptr, Math.min(3, cap)).set(bytes);
			    return 3;
			  }),
			};
			const inst = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[2])), { env });
			ex = inst.exports;
			ex._initialize();
			const greet = WebAssembly.promising(ex.greet);
			const pump = WebAssembly.promising(ex.pump);
			const callGreet = async (s) => {
			  const b = enc.encode(s);
			  const mark = ex.__ronto_alloc_mark();
			  const p = ex.__ronto_alloc(b.length);
			  new Uint8Array(ex.memory.buffer, p, b.length).set(b);
			  const promise = greet(p, b.length);
			  ex.__ronto_alloc_reset(mark); // synchronous: the args were boxed at entry
			  const [rp, rl] = await promise;
			  const out = dec.decode(new Uint8Array(ex.memory.buffer, rp, rl));
			  ex.__ronto_park_free(rp); // the export result's park block is the reader's to free
			  return out;
			};
			(async () => {
			  // 1. two overlapped :string calls: both results exact (the slower one returns
			  // LAST, so its result sat staged while the other's wasm ran -- the scratch
			  // trample the park block exists to prevent).
			  console.log((await Promise.all([callGreet('alpha'), callGreet('beta')])).join(' | '));
			  // 2. two interleaved pull loops staging 64 KiB each: linear memory flat.
			  await Promise.all([pump(50), pump(50)]);
			  const before = ex.memory.buffer.byteLength;
			  const [t1, t2] = await Promise.all([pump(2000), pump(2000)]);
			  console.log(t1, t2, ex.memory.buffer.byteLength === before);
			})();
			""";

	@Test
	void overlappedStringResultsAndInterleavedPullLoopsCrossThroughParkBlocks() throws Exception {
		Path wasm = compile("park.wasm", PARK_MODULE);
		assertThat(runNode(driver(PARK_DRIVER), wasm).lines().toList()).containsExactly("hello, ALPHA! | hello, BETA!",
				"6000 6000 true");
	}

	// The dog-fetcher shape on the ENVELOPE boundary: each request makes one upstream
	// round trip. Compiled --host-fetch --reentrant; the generated glue's worker() is
	// the whole host.
	private static final String WORKER_MODULE = """
			(rontolisp:async-defun relay-response (env)
			  (let* ((res (rontolisp:await (rontolisp:fetch
			                                (concatenate 'string "http://127.0.0.1:" (getf env :query-string) "/word"))))
			         (body (rontolisp:await (rontolisp:read-all (getf res :body)))))
			    (list 200 '(:content-type "text/plain") (list (concatenate 'string "got:" body)))))
			(defun app (env) (relay-response env))
			(rontolisp:http-handler 'app)
			""";

	private static final String WORKER_DRIVER = """
			import fs from 'node:fs';
			import http from 'node:http';
			import { worker } from './worker.js';

			const upstream = http.createServer((req, res) => {
			  setTimeout(() => { res.writeHead(200, { 'content-type': 'text/plain' }); res.end('WOOF'); }, 100);
			});
			await new Promise((r) => upstream.listen(0, '127.0.0.1', r));
			const port = upstream.address().port;

			const module_ = new WebAssembly.Module(fs.readFileSync(new URL('./worker.wasm', import.meta.url)));
			const w = worker(module_);
			const call = async (i) => {
			  const res = await w.fetch(new Request('http://worker.local/?' + port), {}, {});
			  return res.status + ':' + await res.text();
			};
			await call(0); // warm: instantiate + _initialize
			const t0 = performance.now();
			const results = await Promise.all(Array.from({ length: 8 }, (_, i) => call(i)));
			const width = performance.now() - t0;
			console.log(results.join(' '));
			// Serialised, 8 x 100 ms is >= 800 ms; overlapped it is ~one round trip. The
			// bound is generous (CI machines stall) while still refuting serialisation.
			console.log(width < 500 ? 'overlapped' : 'serialised(' + width.toFixed(0) + 'ms)');
			upstream.close();
			""";

	@Test
	void eightConcurrentUpstreamRoundTripsTakeAboutOneThroughOneInstance() throws Exception {
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true, true);
		Files.write(this.tempDir.resolve("worker.wasm"), compiler.compile(reactorProgram(WORKER_MODULE)));
		Files.writeString(this.tempDir.resolve("worker.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("worker.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("drive.mjs");
		Files.writeString(driver, WORKER_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver, null).lines().toList()).containsExactly(
				"200:got:WOOF 200:got:WOOF 200:got:WOOF 200:got:WOOF 200:got:WOOF 200:got:WOOF 200:got:WOOF 200:got:WOOF",
				"overlapped");
	}

	// The composed-boundary gates: the streaming body protocol under --reentrant. The
	// call id the envelope carries is what makes the overlap sound -- each pull/push
	// names its call, each fetch drain names its reply -- so both tests are the
	// corruption the old refusal prevented, inverted.

	// Overlapped STREAMING requests, one uploading binary and one text, each answered
	// its own echo. The handler drains its request body (binary-exact through the
	// :buffered raw-body) and then PARKS on an upstream fetch, so the second request
	// enters and drains ITS body while the first is suspended; the slower upstream
	// makes the first-in request answer LAST.
	private static final String ECHO_MODULE = """
			(defun read-bytes (stream)
			  (if (null stream)
			      nil
			      (let ((out nil))
			        (do ((b (read-byte stream nil nil) (read-byte stream nil nil)))
			            ((null b))
			          (setq out (cons b out)))
			        (nreverse out))))
			(rontolisp:async-defun app (env)
			  (let ((got (read-bytes (getf env :raw-body))))
			    (rontolisp:await (rontolisp:fetch
			                      (concatenate 'string "http://127.0.0.1:"
			                                   (getf env :query-string)
			                                   (getf env :path-info))))
			    (list 200 (list :content-type "text/plain")
			          (list (format nil "n=~a ~a" (length got) got)))))
			(rontolisp:http-handler 'app :raw-body :buffered)
			""";

	private static final String ECHO_DRIVER = """
			import fs from 'node:fs';
			import http from 'node:http';
			import { worker } from './worker.js';

			const upstream = http.createServer((req, res) => {
			  const delay = req.url.startsWith('/slow') ? 80 : 10;
			  setTimeout(() => { res.writeHead(200); res.end('ok'); }, delay);
			});
			await new Promise((r) => upstream.listen(0, '127.0.0.1', r));
			const port = upstream.address().port;

			const module_ = new WebAssembly.Module(fs.readFileSync(new URL('./worker.wasm', import.meta.url)));
			console.log('imports', WebAssembly.Module.imports(module_).map((i) => i.module + '.' + i.name).sort().join(','));
			const w = worker(module_);
			const post = async (path, body) => {
			  const res = await w.fetch(new Request(`http://x.test${path}?${port}`, { method: 'POST', body }), {}, {});
			  return (await res.text()).trim();
			};
			await post('/fast', 'warm'); // instantiate + _initialize
			// The binary upload parks FIRST and resumes LAST: the text request's body
			// crosses, is drained and answered while the first call is suspended.
			const [slow, fast] = await Promise.all([
			  post('/slow', new Uint8Array([0xff, 0xfe, 0x41])),
			  post('/fast', 'hi!'),
			]);
			console.log('slow', slow);
			console.log('fast', fast);
			upstream.close();
			""";

	@Test
	void overlappedStreamingRequestsEachAnswerTheirOwnEcho() throws Exception {
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true, true);
		Files.write(this.tempDir.resolve("worker.wasm"),
				compiler.compile(reactorProgram(ECHO_MODULE, HostBoundary.STREAMING, true)));
		Files.writeString(this.tempDir.resolve("worker.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("worker.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("echo.mjs");
		Files.writeString(driver, ECHO_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver, null).lines().toList()).containsExactly(
				"imports env.fetch,env.readRequestBody,env.readResponseBody,env.writeResponseBody",
				// The three octets the envelope boundary cannot carry, answered to the
				// call that uploaded them -- not to the text request that ran through
				// the module meanwhile.
				"slow n=3 (255 254 65)", "fast n=3 (104 105 33)");
	}

	// Two overlapped calls each fetching a DIFFERENT upstream and relaying the reply
	// body chunk-at-a-time: the handler answers the reply STREAM itself, so every chunk
	// crosses env.readResponseBody(reply-id) in and env.writeResponseBody(call-id) out
	// while both calls are in flight -- and each client receives its own upstream's
	// octets.
	private static final String RELAY_MODULE = """
			(rontolisp:async-defun app (env)
			  (let ((res (rontolisp:await (rontolisp:fetch
			                               (concatenate 'string "http://127.0.0.1:"
			                                            (getf env :query-string)
			                                            (getf env :path-info))))))
			    (list 200 (list :content-type "text/plain") (getf res :body))))
			(rontolisp:http-handler 'app)
			""";

	private static final String RELAY_DRIVER = """
			import fs from 'node:fs';
			import http from 'node:http';
			import { worker } from './worker.js';

			// Five chunks each, phase-shifted so the two relays interleave pull by pull.
			const upstream = http.createServer((req, res) => {
			  const letter = req.url.startsWith('/a') ? 'A' : 'B';
			  const gap = letter === 'A' ? 20 : 31;
			  res.writeHead(200, { 'content-type': 'text/plain' });
			  let sent = 0;
			  const tick = () => {
			    res.write(letter.repeat(8));
			    sent += 1;
			    if (sent < 5) setTimeout(tick, gap);
			    else res.end();
			  };
			  setTimeout(tick, gap);
			});
			await new Promise((r) => upstream.listen(0, '127.0.0.1', r));
			const port = upstream.address().port;

			const module_ = new WebAssembly.Module(fs.readFileSync(new URL('./worker.wasm', import.meta.url)));
			const w = worker(module_);
			const call = async (path) => {
			  const res = await w.fetch(new Request(`http://x.test${path}?${port}`), {}, {});
			  return (await res.text()).trim();
			};
			await call('/a'); // warm: instantiate + _initialize
			const [a, b] = await Promise.all([call('/a'), call('/b')]);
			console.log('a', a === 'A'.repeat(40) ? 'ok' : a);
			console.log('b', b === 'B'.repeat(40) ? 'ok' : b);
			upstream.close();
			""";

	@Test
	void overlappedRelaysEachReceiveTheirOwnUpstreamsOctets() throws Exception {
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true, true);
		Files.write(this.tempDir.resolve("worker.wasm"),
				compiler.compile(reactorProgram(RELAY_MODULE, HostBoundary.STREAMING, true)));
		Files.writeString(this.tempDir.resolve("worker.js"),
				java.util.Objects.requireNonNull(compiler.hostGlueJs("worker.js")), StandardCharsets.UTF_8);
		Path driver = this.tempDir.resolve("relay.mjs");
		Files.writeString(driver, RELAY_DRIVER, StandardCharsets.UTF_8);
		assertThat(runNode(driver, null).lines().toList()).containsExactly("a ok", "b ok");
	}

	// The CLI's --no-wasi --host-fetch reactor pipeline, in its order (the
	// WasmHostGlueE2eTest harness).
	private static List<LispVal> reactorProgram(String source) {
		return reactorProgram(source, HostBoundary.ENVELOPE, false);
	}

	private static List<LispVal> reactorProgram(String source, HostBoundary boundary, boolean reentrant) {
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(source, Features.WASM_REACTOR));
		loaded = HostFetchLibrary.process(loaded, boundary, reentrant);
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true, boundary, reentrant);
		loaded = HttpReactorLibrary.process(loaded);
		loaded = HttpServerLibrary.process(loaded, false);
		return GrayStreamsLibrary
			.process(LispPreludeLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))));
	}

	private Path compile(String fileName, String source) throws IOException {
		List<LispVal> program = LispReader.readAllFromString(source);
		byte[] wasm = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false, false, true)
			.compile(program);
		Path file = this.tempDir.resolve(fileName);
		Files.write(file, wasm);
		return file;
	}

	private Path driver(String source) throws IOException {
		Path file = this.tempDir.resolve("driver.js");
		Files.writeString(file, source, StandardCharsets.UTF_8);
		return file;
	}

	private static String runNode(Path driver, @Nullable Path wasmFile) throws IOException, InterruptedException {
		List<String> command = wasmFile == null ? List.of("node", JSPI_FLAG, driver.toString())
				: List.of("node", JSPI_FLAG, driver.toString(), wasmFile.toString());
		Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
