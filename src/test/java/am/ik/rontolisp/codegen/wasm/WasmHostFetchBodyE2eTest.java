package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.GrayStreamsLibrary;
import am.ik.rontolisp.eval.HostFetchLibrary;
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
 * End-to-end check of the OUTGOING direction of the split reactor boundary: a
 * {@code --no-wasi --host-fetch} module gets the reply HEAD from {@code env.fetch} and
 * pulls the reply BODY out of band through {@code env.readResponseBody} --
 * {@code (ptr, cap) -> i32}, the mirror of the reactor's {@code readRequestBody}. The
 * content of a chunk can only be shown to cross against a host that shares the module's
 * memory, so the host is JS on node; it answers synchronously, which the {@code :async t}
 * contract allows (a JSPI host suspends inside the same call).
 *
 * <p>
 * What is pinned, and why:
 * <ul>
 * <li>the drain is the PORTABLE one -- {@code (await (read-all (getf res :body)))}, the
 * same spelling the other three backends serve -- and the chunks come back reassembled in
 * order through a body whose every chunk boundary falls inside a code point;</li>
 * <li>the body is pulled ONE chunk per read (the host counts its calls: one per chunk
 * plus the one that reports the end), so nothing is buffered up front;</li>
 * <li><strong>a BINARY reply crosses</strong>: {@code ff fe 41} arrives as those three
 * octets, where the JSON envelope turned them into code point 0x1FE062 and two NULs;</li>
 * <li><strong>the head is small</strong>: a 256 KiB reply the program never drains leaves
 * {@code memory.buffer.byteLength} where it was, four fetches in a row -- the whole point
 * of taking the body out of the envelope, and impossible while it rode inside it -- and
 * the transport holds nothing across a 64-chunk pull either, four drains of the same body
 * ending where the first left it (the reused receive buffer plus the import wrapper's
 * heap mark). What one drain PEAKS at is not this boundary: the chunk decode's string
 * output stream is {@code .todo/350};</li>
 * <li>a host may still answer in band: the head's own {@code "body"} key is the
 * already-buffered case of the same source;</li>
 * <li><strong>a transfer that fails MID-BODY signals at the DRAIN</strong>, which is the
 * semantic the split forces and what the other three backends have always done -- the
 * fetch future now settles when the HEADERS arrive;</li>
 * <li>one live body at a time: a second fetch supersedes the first one's, and draining
 * the superseded one says so instead of answering the second reply's octets.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmHostFetchBodyE2eTest#nodeIsAvailable")
class WasmHostFetchBodyE2eTest {

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

	private static final String MODULE = """
			;; The portable drain -- the same spelling examples/net/dog-fetcher.lisp uses on
			;; every other backend. %future-force is how a synchronous export resolves the
			;; settled futures this backend answers.
			(defun body-of (url)
			  (rontolisp::%future-force
			   (rontolisp:read-all (getf (rontolisp::%future-force (rontolisp:fetch url)) :body))))
			(defun reply (url)
			  (let ((res (rontolisp::%future-force (rontolisp:fetch url))))
			    (format nil "~a ~a ~a" (getf res :status) (length (getf res :headers))
			            (rontolisp::%future-force (rontolisp:read-all (getf res :body))))))
			(rontolisp:wasm-export 'reply :params '(:string) :returns :string)

			;; The octets as code points: a reply a JSON string cannot carry.
			(defun codes (url)
			  (let ((s (body-of url)) (out ""))
			    (dotimes (i (length s))
			      (setq out (concatenate 'string out (format nil "~a " (char-code (char s i))))))
			    out))
			(rontolisp:wasm-export 'codes :params '(:string) :returns :string)

			;; The head alone -- the body is never pulled, so it never crosses.
			(defun head-only (url)
			  (getf (rontolisp::%future-force (rontolisp:fetch url)) :status))
			(rontolisp:wasm-export 'head-only :as "headOnly" :params '(:string) :returns :int)

			(defun caught (url)
			  (handler-case (body-of url) (error (e) (format nil "~a" e))))
			(rontolisp:wasm-export 'caught :params '(:string) :returns :string)

			(defun superseded (url)
			  (let ((res (rontolisp::%future-force (rontolisp:fetch url))))
			    (rontolisp::%future-force (rontolisp:fetch url))
			    (handler-case
			        (rontolisp::%future-force (rontolisp:read-all (getf res :body)))
			      (error (e) (format nil "~a" e)))))
			(rontolisp:wasm-export 'superseded :params '(:string) :returns :string)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const enc = new TextEncoder(), dec = new TextDecoder();
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			let inst, chunks = [], rest = null, fail = false, pulls = 0;
			const split = (bytes, size) => {
			  const out = [];
			  for (let i = 0; i < bytes.length; i += size) out.push(bytes.subarray(i, i + size));
			  return out;
			};
			const replies = {
			  // every chunk boundary of this body falls inside a code point
			  text: { head: { status: 200, headers: [['content-type', 'text/plain']] },
			          chunks: split(enc.encode('こんにちは'), 2) },
			  binary: { head: { status: 200, headers: [] },
			            chunks: [new Uint8Array([0xff, 0xfe, 0x41])] },
			  'in-band': { head: { status: 200, headers: [], body: 'the head carried it' },
			               chunks: [] },
			  empty: { head: { status: 204, headers: [] }, chunks: [] },
			  big: { head: { status: 200, headers: [] },
			         chunks: split(enc.encode('x'.repeat(256 * 1024)), 4096) },
			  half: { head: { status: 200, headers: [] }, chunks: [enc.encode('taken')], fail: true },
			};
			const env = {
			  // The request head in, the response head out -- no "body" key either way.
			  fetch: (ptr, len) => {
			    const request = JSON.parse(dec.decode(new Uint8Array(inst.exports.memory.buffer, ptr, len)));
			    const reply = replies[request.url];
			    chunks = reply.chunks.slice(); rest = null; fail = !!reply.fail; pulls = 0;
			    const bytes = enc.encode(JSON.stringify(reply.head));
			    const p = inst.exports.__ronto_alloc(bytes.length);
			    new Uint8Array(inst.exports.memory.buffer, p, bytes.length).set(bytes);
			    return [p, bytes.length];
			  },
			  // (ptr, cap) -> n: the body the last fetch opened. 0 is end of stream, a
			  // negative count is a transfer that failed after the head had crossed.
			  readResponseBody: (ptr, cap) => {
			    pulls++;
			    while (!rest || rest.length === 0) {
			      if (!chunks.length) return fail ? -1 : 0;
			      rest = chunks.shift();
			    }
			    const n = Math.min(cap, rest.length);
			    new Uint8Array(inst.exports.memory.buffer, ptr, n).set(rest.subarray(0, n));
			    rest = rest.subarray(n);
			    return n;
			  },
			};
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			function call(name, url) {
			  const x = inst.exports;
			  const mark = x.__ronto_alloc_mark();
			  const bytes = enc.encode(url);
			  const p = x.__ronto_alloc(bytes.length);
			  new Uint8Array(x.memory.buffer, p, bytes.length).set(bytes);
			  const r = x[name](p, bytes.length);
			  const out = Array.isArray(r)
			    ? dec.decode(new Uint8Array(x.memory.buffer.slice(r[0], r[0] + r[1])))
			    : r;
			  x.__ronto_alloc_reset(mark);
			  return out;
			}
			// 1. status and headers ride the head; the body is pulled one chunk at a time
			// and reassembled -- one pull per chunk, plus the one that reports the end.
			console.log(call('reply', 'text'), pulls);
			// 2. binary, which the JSON envelope could not carry at all.
			console.log(call('codes', 'binary').trim());
			// 3. a host may still answer in band: the head's own key wins.
			console.log(call('reply', 'in-band'), pulls);
			// 4. no body at all is the empty string, decided by ONE pull.
			console.log(JSON.stringify(call('reply', 'empty')), pulls);
			// 5. the head is small: 256 KiB never drained leaves linear memory where it was.
			call('headOnly', 'big');
			const before = inst.exports.memory.buffer.byteLength;
			for (let i = 0; i < 3; i++) call('headOnly', 'big');
			console.log(call('headOnly', 'big'), inst.exports.memory.buffer.byteLength === before);
			// 5b. and the transport holds nothing across a 64-chunk pull either: draining
			// the same 256 KiB again and again ends where the first drain left it.
			const drained = call('reply', 'big').length;
			const afterDrain = inst.exports.memory.buffer.byteLength;
			for (let i = 0; i < 3; i++) call('reply', 'big');
			console.log(drained, pulls, inst.exports.memory.buffer.byteLength === afterDrain);
			// 6. a transfer that fails MID-BODY signals at the drain, not at the fetch.
			console.log(call('caught', 'half'));
			// 7. one live body: a second fetch supersedes the first one's.
			console.log(call('superseded', 'text'));
			""";

	@Test
	void aHostFetchReactorPullsItsReplyBodyOutOfBandAndDrainsItPortably() throws Exception {
		Path wasmFile = this.tempDir.resolve("fetch.wasm");
		Files.write(wasmFile, compile());
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		assertThat(runNode(driver, wasmFile).lines().toList()).containsExactly("200 1 こんにちは 9", "255 254 65",
				"200 0 the head carried it 0", "\"204 0 \" 1", "200 true", "262150 65 true",
				"fetch: the response body failed mid-transfer",
				"fetch: the response body was superseded by a later fetch");
	}

	// The CLI's --no-wasi --host-fetch pipeline, in its order: the fetch lowering (the
	// two env imports + the envelope defuns), then the reactor transport whose body
	// source machinery the reply's stream rides, then the JSON library and the prelude
	// picking up the splice's own call sites.
	private static byte[] compile() {
		List<LispVal> loaded = HostFetchLibrary.process(LispReader.readAllFromString(MODULE, Features.WASM_REACTOR),
				HostBoundary.STREAMING);
		loaded = HttpReactorLibrary.process(loaded);
		loaded = HttpServerLibrary.process(loaded, false);
		List<LispVal> program = GrayStreamsLibrary
			.process(LispPreludeLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))));
		return new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false, true).compile(program);
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
