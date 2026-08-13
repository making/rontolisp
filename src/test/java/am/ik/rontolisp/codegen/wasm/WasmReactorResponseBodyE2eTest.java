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
 * End-to-end check of the other half of the split boundary: a {@code --no-wasi} REACTOR
 * puts its RESPONSE body out of band too, through {@code env.writeResponseBody} --
 * {@code (ptr, len)}, "take these octets" -- against a JS host that shares the module's
 * memory. {@code WasmReactorBodyE2eTest} is the request half.
 *
 * <p>
 * What is pinned, and why:
 * <ul>
 * <li>the head carries NO {@code "body"} key when the body crossed out of band: absent,
 * not empty, because a host has to be able to tell that from a response whose body IS the
 * empty string;</li>
 * <li><strong>a BINARY body crosses exactly</strong> -- the payoff, and the reason the
 * shared normalizer stopped flattening an {@code (unsigned-byte 8)} body into characters:
 * once flattened it is indistinguishable from text, and every transport that UTF-8
 * encodes what it is given doubles the high octets;</li>
 * <li>a STREAM body (a proxied fetch response) is FORWARDED chunk at a time, not
 * collected into one string first: the host counts the writes;</li>
 * <li><strong>the head wins over chunks already taken</strong>: a handler that fails
 * halfway through its body answers a 500 whose report rides the head in band, and the
 * host discards what it took. That is what makes a mid-body error recoverable rather than
 * a corrupt response;</li>
 * <li>the transport holds nothing: 256 KiB streamed out a chunk at a time leaves
 * {@code memory.buffer.byteLength} where it was, four responses in a row.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmReactorResponseBodyE2eTest#nodeIsAvailable")
class WasmReactorResponseBodyE2eTest {

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

	// One rontolisp:http-handler source -- the same directive that binds a socket on the
	// interpreter -- answering each response body shape the transport distinguishes.
	private static final String MODULE = """
			(defvar *parts* nil)
			(defun next-part ()
			  (if *parts*
			      (let ((c (car *parts*))) (setq *parts* (cdr *parts*)) c)
			      nil))
			;; ... and one that fails once its first chunk is already gone.
			(defun failing-part ()
			  (if *parts*
			      (let ((c (car *parts*))) (setq *parts* (cdr *parts*)) c)
			      (error "the body failed halfway")))
			(defun parts-stream (parts thunk)
			  (setq *parts* parts)
			  (rontolisp::%stream-new thunk (lambda () nil)))
			(defun big-parts (n)
			  (let ((out nil))
			    (dotimes (i n) (setq out (cons (make-string 4096 :initial-element #\\x) out)))
			    out))
			(defun octets ()
			  (let ((v (make-array 3 :element-type '(unsigned-byte 8))))
			    (setf (aref v 0) #xff)
			    (setf (aref v 1) #xfe)
			    (setf (aref v 2) #x41)
			    v))
			(defun handle (env)
			  (let ((path (getf env :path-info)))
			    (cond ((string= path "/text")
			           (list 200 (list :content-type "text/plain") (list "こんにちは")))
			          ((string= path "/empty") (list 200 nil (list "")))
			          ((string= path "/binary")
			           (list 200 (list :content-type "application/octet-stream") (octets)))
			          ((string= path "/stream")
			           (list 200 nil (parts-stream (list "one " "two " "three")
			                                       (function next-part))))
			          ((string= path "/half")
			           (list 200 nil (parts-stream (list "taken") (function failing-part))))
			          ((string= path "/big")
			           (list 200 nil (parts-stream (big-parts 64) (function next-part))))
			          (t (error "no route")))))
			(rontolisp:http-handler 'handle 8080)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const enc = new TextEncoder(), dec = new TextDecoder();
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			let inst, out = [];
			const env = {
			  // No request body in this test: 0 is end of stream.
			  readRequestBody: () => 0,
			  // (ptr, len): one response chunk. COPY it now -- the module pops the
			  // staging behind these octets the moment the call returns, and reuses it
			  // for the next chunk.
			  writeResponseBody: (ptr, len) => {
			    out.push(new Uint8Array(inst.exports.memory.buffer.slice(ptr, ptr + len)));
			  },
			};
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			function collected() {
			  const all = new Uint8Array(out.reduce((n, c) => n + c.length, 0));
			  let at = 0;
			  for (const c of out) { all.set(c, at); at += c.length; }
			  return all;
			}
			function call(target) {
			  out = [];
			  const x = inst.exports;
			  const mark = x.__ronto_alloc_mark();
			  const hb = enc.encode(JSON.stringify({ method: 'GET', target, headers: {} }));
			  const p = x.__ronto_alloc(hb.length);
			  new Uint8Array(x.memory.buffer, p, hb.length).set(hb);
			  const [rp, rl] = x['handle-request'](p, hb.length);
			  const head = dec.decode(new Uint8Array(x.memory.buffer.slice(rp, rp + rl)));
			  x.__ronto_alloc_reset(mark);
			  return JSON.parse(head);
			}
			const hex = (u8) => [...u8].map((b) => b.toString(16).padStart(2, '0')).join('');
			// 1. the body is not in the head at all -- absent, not empty -- and the octets
			// the host took decode to what the handler wrote.
			let r = call('/text');
			console.log('body' in r, dec.decode(collected()), out.length);
			// 2. ... and a response whose body IS the empty string is the same head with
			// no chunks, which is exactly why the key has to be absent rather than "".
			r = call('/empty');
			console.log('body' in r, out.length);
			// 3. binary: three octets a JSON string cannot carry, and which every
			// character-per-octet flattening turns into five.
			call('/binary');
			console.log(hex(collected()));
			// 4. a stream body is FORWARDED: three chunks, in order, none collected first.
			call('/stream');
			console.log(out.length, dec.decode(collected()));
			// 5. a handler that fails halfway answers in band, and the chunk already taken
			// is the host's to discard.
			r = call('/half');
			console.log(r.status, r.body.includes('halfway'), dec.decode(collected()));
			// 6. the transport holds nothing: 256 KiB out, four responses in a row.
			call('/big');
			const before = inst.exports.memory.buffer.byteLength;
			for (let i = 0; i < 3; i++) call('/big');
			r = call('/big');
			console.log(collected().length, inst.exports.memory.buffer.byteLength === before);
			""";

	@Test
	void aReactorPushesItsResponseBodyThroughTheHostImportWithoutHoldingIt() throws Exception {
		Path wasmFile = this.tempDir.resolve("reactor.wasm");
		Files.write(wasmFile, compile());
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		assertThat(runNode(driver, wasmFile).lines().toList()).containsExactly("false こんにちは 1", "false 0", "fffe41",
				"3 one two three", "500 true taken", "262144 true");
	}

	// The CLI's --no-wasi reactor pipeline, in its order -- the same one
	// WasmReactorBodyE2eTest compiles.
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
		Process process = new ProcessBuilder("node", driver.toString(), wasmFile.toString()).redirectErrorStream(false)
			.start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
