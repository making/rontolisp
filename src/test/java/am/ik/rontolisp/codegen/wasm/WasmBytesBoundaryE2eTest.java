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
 * End-to-end checks of the {@code :bytes} boundary type against a JS host (plain node, no
 * JSPI needed -- the imports here are synchronous). A wasm host preloaded into wasmtime
 * has its own linear memory, so byte CONTENT can only be proven to cross against a host
 * that reads and writes the module's memory -- which is exactly the host a {@code :bytes}
 * boundary exists for.
 *
 * <p>
 * What is pinned, and why:
 * <ul>
 * <li>the bytes {@code ff fe 41} round-trip EXACTLY in all four directions (export
 * parameter, export result, import result, and the import result's content read back from
 * Lisp) -- a {@code :string} result's non-validating UTF-8 decode corrupts exactly that
 * sequence into a garbage code point;</li>
 * <li>a {@code :bytes} RESULT is caller-buffered: the host/wrapper answers the FULL
 * length even when the buffer is undersized (a retry, not a truncation), and writes
 * nothing past the capacity;</li>
 * <li>a pull loop over ONE reused Lisp buffer keeps linear memory FLAT -- the wrapper
 * stages through the bump allocator and pops back to its mark, so a chunked body pull no
 * longer grows the arena by the whole body.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmBytesBoundaryE2eTest#nodeIsAvailable")
class WasmBytesBoundaryE2eTest {

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
			(rontolisp:wasm-import 'fill-buf :from "env" :as "fillBuf" :params '() :returns :bytes)

			;; export :bytes parameter: the host-staged bytes arrive exactly.
			(defun sum3 (v) (+ (* 65536 (aref v 0)) (* 256 (aref v 1)) (aref v 2)))
			(rontolisp:wasm-export 'sum3 :params '(:bytes) :returns :int)

			;; export :bytes result: the vector's bytes go out through the caller's buffer.
			(defun magic () (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(255 254 65)))
			(rontolisp:wasm-export 'magic :params '() :returns :bytes)

			;; import :bytes result: pull into a Lisp buffer and read the content back.
			(defvar *buf* (make-array 3 :element-type '(unsigned-byte 8)))
			(defun pull-code ()
			  (let ((n (fill-buf *buf*)))
			    (+ (* 16777216 n) (* 65536 (aref *buf* 0)) (* 256 (aref *buf* 1)) (aref *buf* 2))))
			(rontolisp:wasm-export 'pull-code :as "pullCode" :params '() :returns :int)

			;; the pull loop: one reused 64 KiB buffer, k pulls -- linear memory must stay flat.
			(defvar *big* (make-array 65536 :element-type '(unsigned-byte 8)))
			(defun pump (k)
			  (let ((total 0))
			    (dotimes (i k) (setq total (+ total (fill-buf *big*))))
			    total))
			(rontolisp:wasm-export 'pump :params '(:int) :returns :int)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const bytes = [0xff, 0xfe, 0x41];
			let inst;
			const env = {
			  fillBuf: (ptr, cap) => {
			    const mem = new Uint8Array(inst.exports.memory.buffer);
			    for (let i = 0; i < Math.min(bytes.length, cap); i++) mem[ptr + i] = bytes[i];
			    return bytes.length;
			  },
			};
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			// 1. export :bytes parameter: stage ff fe 41 and read the exact sum back.
			const p = inst.exports.__ronto_alloc(3);
			new Uint8Array(inst.exports.memory.buffer).set(bytes, p);
			console.log(inst.exports.sum3(p, 3));
			// 2. export :bytes result: full buffer, then undersized (full length, no overrun).
			const out = inst.exports.__ronto_alloc(4);
			new Uint8Array(inst.exports.memory.buffer)[out + 3] = 0x7f;
			const n = inst.exports.magic(out, 3);
			const m = new Uint8Array(inst.exports.memory.buffer);
			console.log(n, m[out], m[out + 1], m[out + 2]);
			const out2 = inst.exports.__ronto_alloc(4);
			new Uint8Array(inst.exports.memory.buffer)[out2 + 2] = 0x7f;
			const n2 = inst.exports.magic(out2, 2);
			const m2 = new Uint8Array(inst.exports.memory.buffer);
			console.log(n2, m2[out2], m2[out2 + 1], m2[out2 + 2]);
			// 3. import :bytes result: the module pulls into its own Lisp buffer.
			console.log(inst.exports.pullCode());
			// 4. arena flatness: k pulls staging a 64 KiB buffer each must not grow memory.
			inst.exports.pump(1);
			const before = inst.exports.memory.buffer.byteLength;
			console.log(inst.exports.pump(10000));
			console.log(inst.exports.memory.buffer.byteLength === before);
			""";

	@Test
	void bytesRoundTripExactlyAndThePullLoopKeepsMemoryFlat() throws Exception {
		List<LispVal> program = LispReader.readAllFromString(MODULE);
		byte[] wasm = new WasmLispCompiler(false, false, true).compile(program);
		Path wasmFile = this.tempDir.resolve("bytes.wasm");
		Files.write(wasmFile, wasm);
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		String stdout = runNode(driver, wasmFile);
		assertThat(stdout.lines().toList()).containsExactly(
				// 0xff*65536 + 0xfe*256 + 0x41
				"16776769",
				// full buffer: full length, exact bytes, the guard byte untouched
				"3 255 254 65",
				// undersized buffer: still the FULL length, nothing past cap
				"3 255 254 127",
				// 3*16777216 + 0xff*65536 + 0xfe*256 + 0x41
				"67108417",
				// 10000 pulls x 3 bytes, memory flat
				"30000", "true");
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
