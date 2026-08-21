package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.HostBoundary;
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
 * End-to-end check that a {@code --no-wasi} REACTOR takes its request body out of band,
 * against a JS host that shares the module's memory (which is the only host a byte
 * boundary can be shown to cross). The head still crosses as the JSON envelope; the body
 * crosses through {@code env.readRequestBody} -- {@code (ptr, cap) -> n}, into ONE buffer
 * the module reuses.
 *
 * <p>
 * What is pinned, and why:
 * <ul>
 * <li>a body whose every character STRADDLES a chunk boundary is reassembled exactly: the
 * host cuts the body where its buffer ends and knows nothing about code points;</li>
 * <li>a reader that answers 0 is NO BODY -- {@code :raw-body} stays nil, the same value a
 * request without one has always produced, which is what upstream's
 * {@code (when raw-body ...)} guards expect;</li>
 * <li>an EMPTY reader still falls back to the envelope's own {@code "body"} key, so a
 * host may hand a reader over before it stops filling the envelope;</li>
 * <li>a BINARY body crosses exactly -- the whole reason the boundary is {@code :bytes};
 * the {@code :string} decoder corrupts arbitrary octets into garbage code points;</li>
 * <li><strong>the boundary costs no linear memory</strong>: a 256 KiB body a handler
 * never reads leaves {@code memory.buffer.byteLength} where it was -- the envelope used
 * to hold the body about 17 times over. What a handler then does with the body (this
 * one's {@code read-all} builds it as one string) is the handler's cost, not the
 * transport's.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmReactorBodyE2eTest#nodeIsAvailable")
class WasmReactorBodyE2eTest {

	static boolean nodeIsAvailable() {
		try {
			return new ProcessBuilder("node", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	@TempDir
	Path tempDir;

	// One rontolisp:http-handler source -- the same one that binds a socket on the
	// interpreter -- in the directive's DEFAULT :raw-body mode, so the drain below is the
	// portable (await (read-all ...)) every other backend serves.
	private static final String MODULE = """
			(rontolisp:async-defun handle (env)
			  (let ((body (getf env :raw-body)) (path (getf env :path-info)))
			    (list 200 (list :content-type "text/plain")
			          (list (cond ((null body) "no-body")
			                      ((string= path "/drop") "dropped")
			                      ((string= path "/len")
			                       (format nil "~a"
			                               (length (rontolisp:await (rontolisp:read-all body)))))
			                      (t (rontolisp:await (rontolisp:read-all body))))))))
			(rontolisp:http-handler 'handle 8080)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const enc = new TextEncoder(), dec = new TextDecoder();
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			let inst, body = new Uint8Array(0), pos = 0, pulls = 0, out = [];
			const env = {
			  // (ptr, cap) -> n: write up to cap octets at ptr and answer how many; 0 is
			  // end of stream. The module owns the buffer and hands it over per call.
			  readRequestBody: (ptr, cap) => {
			    pulls++;
			    const n = Math.min(cap, body.length - pos);
			    if (n <= 0) return 0;
			    new Uint8Array(inst.exports.memory.buffer, ptr, n)
			      .set(body.subarray(pos, pos + n));
			    pos += n;
			    return n;
			  },
			  // (ptr, len): the response body, out of band. COPY now -- the module pops
			  // the staging behind these octets the moment the call returns.
			  writeResponseBody: (ptr, len) => {
			    out.push(new Uint8Array(inst.exports.memory.buffer.slice(ptr, ptr + len)));
			  },
			};
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			// The chunks this host took, as one buffer.
			function collected() {
			  const all = new Uint8Array(out.reduce((n, c) => n + c.length, 0));
			  let at = 0;
			  for (const c of out) { all.set(c, at); at += c.length; }
			  return all;
			}
			function call(head, bytes) {
			  body = bytes; pos = 0; pulls = 0; out = [];
			  const x = inst.exports;
			  const mark = x.__ronto_alloc_mark();
			  const hb = enc.encode(head);
			  const p = x.__ronto_alloc(hb.length);
			  new Uint8Array(x.memory.buffer, p, hb.length).set(hb);
			  const [rp, rl] = x['handle-request'](p, hb.length);
			  const text = dec.decode(new Uint8Array(x.memory.buffer.slice(rp, rp + rl)));
			  x.__ronto_alloc_reset(mark);
			  // The response body left the head too, so it is the chunks -- unless the
			  // head carries a "body" key, which WINS (the 500 arm answers in band).
			  const reply = JSON.parse(text);
			  return { ...reply, body: reply.body ?? dec.decode(collected()) };
			}
			const head = (t, extra) =>
			  JSON.stringify({ method: 'POST', target: t, headers: { host: 'h' }, ...extra });
			// 1. every character of this body straddles a chunk boundary of any size the
			// host picks, and the answer is still the text that was sent.
			console.log(call(head('/echo'), enc.encode('こんにちは')).body);
			// 2. a reader that answers 0 is no body at all -- one pull decides it.
			console.log(call(head('/echo'), new Uint8Array(0)).body, pulls);
			// 3. ... and the envelope's own key still wins when the reader is empty.
			console.log(call(head('/echo', { body: 'in-band' }), new Uint8Array(0)).body);
			// 4. binary: three octets a JSON string cannot carry.
			console.log(call(head('/len'), new Uint8Array([0xff, 0xfe, 0x41])).body);
			// 5. the body costs the BOUNDARY nothing: 256 KiB the handler never reads,
			// four times over, and linear memory is where it started.
			const big = enc.encode('x'.repeat(256 * 1024));
			const before = inst.exports.memory.buffer.byteLength;
			for (let i = 0; i < 4; i++) call(head('/drop'), big);
			console.log(call(head('/drop'), big).body,
			            inst.exports.memory.buffer.byteLength === before);
			""";

	@Test
	void aReactorPullsItsRequestBodyThroughTheHostImportWithoutGrowingMemory() throws Exception {
		Path wasmFile = this.tempDir.resolve("reactor.wasm");
		Files.write(wasmFile, compile());
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		assertThat(runNode(driver, wasmFile).lines().toList()).containsExactly("こんにちは", "no-body 1", "in-band", "3",
				"dropped true");
	}

	// The CLI's --no-wasi reactor pipeline, in its order: the http-handler directive
	// lowers to the host-driven transport, the marker becomes the handle-request export
	// plus the body import, then the transport and the server value model are spliced.
	private static byte[] compile() {
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(MODULE, Features.WASM_REACTOR));
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true, HostBoundary.STREAMING);
		loaded = HttpReactorLibrary.process(loaded);
		loaded = HttpServerLibrary.process(loaded, false);
		List<LispVal> program = GrayStreamsLibrary
			.process(LispPreludeLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))));
		return new WasmLispCompiler(false, false, true).compile(program);
	}

	private static String runNode(Path driver, Path wasmFile) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("node", driver.toString(), wasmFile.toString()).redirectErrorStream(false)
			.start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
