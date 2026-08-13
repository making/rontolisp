package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.GrayStreamsLibrary;
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
 * End-to-end check of the OTHER host the reactor body import accepts: one that SUSPENDS
 * inside {@code handle-request} instead of buffering the body before calling in.
 * {@code env.readRequestBody} is declared {@code :async t}, which
 * {@code WasmReactorBodyE2eTest} exercises through a synchronous host; here it is a
 * {@code WebAssembly.Suspending} over a {@code ReadableStream}'s reader, and the export
 * is entered through {@code WebAssembly.promising} -- the shape a Cloudflare Worker needs
 * to stream an upload it never buffers.
 *
 * <p>
 * What is pinned, and why:
 * <ul>
 * <li><strong>the module parks and resumes inside one export call</strong>: the reader
 * hands its chunks over on the MACROTASK queue (a {@code setTimeout} per chunk), so a
 * host that had not suspended could not have them at all -- a correct answer is the
 * proof, and the driver also counts the event-loop turns the call took;</li>
 * <li>the body is reassembled exactly although EVERY chunk boundary falls inside a code
 * point: a host reading a socket cuts where its buffer ends and knows nothing about
 * UTF-8, so a straddling character is the normal case for a streaming host, not a corner
 * one;</li>
 * <li>a reader that answers 0 is still NO BODY through a suspending host -- the same
 * {@code :raw-body} nil upstream's {@code (when raw-body ...)} guards expect;</li>
 * <li><strong>the transport holds nothing</strong>: 256 KiB streamed a chunk at a time to
 * a handler that drops it leaves {@code memory.buffer.byteLength} where it was, four
 * requests in a row. What a handler that READS the body then costs is not this boundary
 * -- and is not free; {@code .kb/clack.md} says what it costs and why.</li>
 * </ul>
 * The re-entry guard a suspending module carries is pinned by
 * {@code WasmImportCompilerTest} and is what makes the serialising host below correct;
 * this test drives one call at a time, which is what that guard requires.
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmReactorStreamingHostE2eTest#jspiIsAvailable")
class WasmReactorStreamingHostE2eTest {

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

	// The same rontolisp:http-handler source the synchronous-host test drives, in the
	// directive's DEFAULT :raw-body mode, so the drain is the portable
	// (await (read-all ...)) every other backend serves.
	private static final String MODULE = """
			(rontolisp:async-defun handle (env)
			  (let ((body (getf env :raw-body)) (path (getf env :path-info)))
			    (list 200 (list :content-type "text/plain")
			          (list (cond ((null body) "no-body")
			                      ((string= path "/drop") "dropped")
			                      (t (rontolisp:await (rontolisp:read-all body))))))))
			(rontolisp:http-handler 'handle 8080)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const enc = new TextEncoder(), dec = new TextDecoder();
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));

			let inst, reader = null, pending = new Uint8Array(0), pulls = 0, out = [];

			// (ptr, cap) -> n, the same contract a synchronous host answers -- except this
			// one awaits the request body's reader, so the module parks here and the event
			// loop runs while it is suspended.
			async function readRequestBody(ptr, cap) {
			  pulls++;
			  while (pending.length === 0) {
			    if (!reader) return 0;
			    const { value, done } = await reader.read();
			    if (done) { reader = null; return 0; }
			    pending = value;
			  }
			  const n = Math.min(cap, pending.length);
			  // AFTER the await: a resumed module may have grown memory, detaching the buffer.
			  new Uint8Array(inst.exports.memory.buffer, ptr, n).set(pending.subarray(0, n));
			  pending = pending.subarray(n);
			  return n;
			}

			// (ptr, len), and this one suspends too: a host writing to a socket applies
			// backpressure, so the module parks in the MIDDLE of producing its response.
			// The octets are copied before the await, because the module pops the staging
			// behind them when the call returns.
			async function writeResponseBody(ptr, len) {
			  const chunk = new Uint8Array(inst.exports.memory.buffer.slice(ptr, ptr + len));
			  await new Promise((resolve) => setTimeout(resolve, 0));
			  out.push(chunk);
			}

			inst = new WebAssembly.Instance(mod, {
			  env: {
			    readRequestBody: new WebAssembly.Suspending(readRequestBody),
			    writeResponseBody: new WebAssembly.Suspending(writeResponseBody),
			  },
			});
			inst.exports._initialize();
			const handleRequest = WebAssembly.promising(inst.exports['handle-request']);

			// Chunks arrive on the MACROTASK queue, the way a socket's do: a host that had
			// not suspended could never see them from inside the call.
			function streamOf(chunks) {
			  let i = 0;
			  return new ReadableStream({
			    pull: (c) => new Promise((resolve) => setTimeout(() => {
			      if (i >= chunks.length) c.close(); else c.enqueue(chunks[i++]);
			      resolve();
			    }, 0)),
			  });
			}

			// Event-loop turns taken while ONE handle-request call is outstanding.
			let inCall = false, turns = 0;
			function tick() { if (inCall) { turns++; setTimeout(tick, 0); } }

			async function call(head, chunks) {
			  reader = chunks ? streamOf(chunks).getReader() : null;
			  pending = new Uint8Array(0);
			  pulls = 0; turns = 0; out = [];
			  const x = inst.exports;
			  const mark = x.__ronto_alloc_mark();
			  const hb = enc.encode(head);
			  const p = x.__ronto_alloc(hb.length);
			  new Uint8Array(x.memory.buffer, p, hb.length).set(hb);
			  inCall = true; setTimeout(tick, 0);
			  const [rp, rl] = await handleRequest(p, hb.length);
			  inCall = false;
			  const text = dec.decode(new Uint8Array(x.memory.buffer.slice(rp, rp + rl)));
			  x.__ronto_alloc_reset(mark);
			  const all = new Uint8Array(out.reduce((n, c) => n + c.length, 0));
			  let at = 0;
			  for (const c of out) { all.set(c, at); at += c.length; }
			  const reply = JSON.parse(text);
			  return { ...reply, body: reply.body ?? dec.decode(all) };
			}

			const head = (t) =>
			  JSON.stringify({ method: 'POST', target: t, headers: { host: 'h' } });

			// Cut the octets at offsets that fall INSIDE code points: none of these chunk
			// boundaries is a character boundary.
			function chunked(text, size) {
			  const bytes = enc.encode(text), out = [];
			  for (let i = 0; i < bytes.length; i += size) out.push(bytes.subarray(i, i + size));
			  return out;
			}

			(async () => {
			  // 1. five 3-octet characters delivered as eight 2-octet chunks, one per
			  // event-loop turn, and the answer is the text that was sent.
			  const r = await call(head('/echo'), chunked('こんにちは', 2));
			  console.log(r.body, pulls, turns > 0);
			  // 2. an empty reader is no body at all, through a suspending host too.
			  console.log((await call(head('/echo'), null)).body, pulls);
			  // 3. the transport holds nothing: 256 KiB streamed to a handler that drops
			  // it, four requests in a row, and linear memory is where it started.
			  const big = chunked('x'.repeat(256 * 1024), 64 * 1024);
			  await call(head('/drop'), big);
			  const before = inst.exports.memory.buffer.byteLength;
			  for (let i = 0; i < 3; i++) await call(head('/drop'), big);
			  console.log((await call(head('/drop'), big)).body,
			              inst.exports.memory.buffer.byteLength === before);
			})();
			""";

	@Test
	void aReactorStreamsItsRequestBodyFromASuspendingHostWithoutHoldingIt() throws Exception {
		Path wasmFile = this.tempDir.resolve("reactor.wasm");
		Files.write(wasmFile, compile());
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		assertThat(runNode(driver, wasmFile).lines().toList()).containsExactly("こんにちは 9 true", "no-body 1",
				"dropped true");
	}

	// The CLI's --no-wasi reactor pipeline, in its order -- the same one
	// WasmReactorBodyE2eTest compiles: the module is identical whichever host drives it,
	// which is the point of declaring the import :async t rather than forking the build.
	private static byte[] compile() {
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(MODULE, Features.WASM_REACTOR));
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true);
		loaded = HttpReactorLibrary.process(loaded);
		loaded = HttpServerLibrary.process(loaded, false);
		List<LispVal> program = GrayStreamsLibrary
			.process(LispPreludeLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))));
		return new WasmLispCompiler(false, false, true).compile(program);
	}

	private static String runNode(Path driver, Path wasmFile) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("node", JSPI_FLAG, driver.toString(), wasmFile.toString())
			.redirectErrorStream(false)
			.start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
