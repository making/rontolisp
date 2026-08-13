package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the degenerate tier's first-class stream value against a JS host: a
 * {@code --no-wasi} reactor pulls its chunks one at a time through a suspending
 * ({@code :async t}) host import and drains them the PORTABLE way,
 * {@code (await (read-all s))}. A wasm host preloaded into wasmtime has its own linear
 * memory, so a chunk's CONTENT can only be shown to cross against a host that shares the
 * module's -- which is the host this boundary exists for.
 *
 * <p>
 * What is pinned, and why:
 * <ul>
 * <li>{@code streamp} answers T for the value {@code rontolisp::%stream-new} builds, and
 * the prelude's {@code read-all} -- the same source every other backend runs --
 * reassembles the chunks in order;</li>
 * <li>the read thunk is PULLED once per chunk (the host counts its calls: one per chunk
 * plus the one that reports the end), so the body is not buffered up front;</li>
 * <li>the close protocol runs exactly ONCE, at EOF, and a later {@code stream-close} is a
 * no-op;</li>
 * <li>the thunk may answer a FUTURE -- what a {@code :async t} import gives -- and the
 * stream still sees end-of-stream through it: a future wrapping nil is not nil, so the
 * runtime resolves before testing.</li>
 * </ul>
 * The host here answers synchronously, which the {@code :async t} contract allows on this
 * backend (started == settled either way); a JSPI host suspends inside the same call.
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmHostStreamE2eTest#nodeIsAvailable")
class WasmHostStreamE2eTest {

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
			(rontolisp:wasm-import 'next-chunk :from "env" :as "nextChunk" :params '() :returns :string :async t)

			(defvar *closes* 0)

			;; The host spells end-of-body as the empty string; the stream contract is nil.
			;; next-chunk answers a FUTURE (:async t), and %future-force is how synchronous
			;; code resolves one -- the stream runtime resolves it too, so a thunk that just
			;; returned the future would work as well.
			(defun body-read ()
			  (let ((c (rontolisp::%future-force (next-chunk))))
			    (if (= (length c) 0) nil c)))

			(defun body-stream ()
			  (rontolisp::%stream-new #'body-read (lambda () (setq *closes* (+ *closes* 1)) nil)))

			;; The portable drain: exactly what a handler writes on the other three backends.
			(defun drain ()
			  (let ((s (body-stream)))
			    (if (rontolisp:streamp s) (rontolisp:read-all s) "not-a-stream")))
			(rontolisp:wasm-export 'drain :params '() :returns :string)

			;; Draining runs the close protocol once; closing again after EOF changes nothing.
			(defun drain-then-close ()
			  (let* ((s (body-stream))
			         (body (rontolisp::%future-force (rontolisp:read-all s))))
			    (rontolisp:stream-close s)
			    body))
			(rontolisp:wasm-export 'drain-then-close :as "drainThenClose" :params '() :returns :string)

			(defun closes () *closes*)
			(rontolisp:wasm-export 'closes :params '() :returns :int)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			let chunks = [];
			let pulls = 0;
			let inst;
			const enc = new TextEncoder();
			const env = {
			  nextChunk: () => {
			    pulls++;
			    const s = chunks.length ? chunks.shift() : '';
			    const bytes = enc.encode(s);
			    const p = inst.exports.__ronto_alloc(bytes.length);
			    new Uint8Array(inst.exports.memory.buffer).set(bytes, p);
			    return [p, bytes.length];
			  },
			};
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			const readString = ([ptr, len]) =>
			  new TextDecoder().decode(new Uint8Array(inst.exports.memory.buffer).subarray(ptr, ptr + len));
			// 1. three chunks pulled one at a time and reassembled in order.
			chunks = ['ab', 'cde', 'f'];
			console.log(readString(inst.exports.drain()));
			// one pull per chunk, plus the one that reports the end
			console.log(pulls);
			// the close protocol ran once, at EOF
			console.log(inst.exports.closes());
			// 2. an empty body is end-of-stream at the first pull, and closing after a
			// drain is a no-op.
			chunks = [];
			console.log(JSON.stringify(readString(inst.exports.drainThenClose())));
			console.log(inst.exports.closes());
			""";

	@Test
	void aNoWasiModulePullsItsBodyThroughAHostImportAndDrainsItPortably() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(MODULE));
		byte[] wasm = new WasmLispCompiler(false, false, true).compile(program);
		Path wasmFile = this.tempDir.resolve("stream.wasm");
		Files.write(wasmFile, wasm);
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		assertThat(runNode(driver, wasmFile).lines().toList()).containsExactly("abcdef", "4", "1", "\"\"", "2");
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
