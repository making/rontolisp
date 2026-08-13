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
 * End-to-end check that a string OUTPUT stream costs the bump arena nothing per write.
 * Against a JS host, because the question is how far {@code __ronto_alloc_mark} moves
 * ACROSS calls -- a wasmtime run can only show the answer, never the arena.
 *
 * <p>
 * The cost used to be per WRITE: every append copied the written content into a
 * persistent linear buffer and linked a 12-byte chunk record at it, so a
 * {@code write-char} loop -- the shape the in-tree UTF-8 / percent decoders take -- cost
 * 15 bytes of linear memory per CHARACTER, reclaimed only when the enclosing
 * {@code __ronto_alloc_reset} ran (a whole request, on a reactor; never, in a program
 * without one). The stream now appends into one growable {@code $str_bytes} GC buffer, so
 * what a write costs is GC-heap memory the engine reclaims, and the arena sees only the
 * 12-byte record the stream itself is.
 *
 * <p>
 * Both spellings are pinned, and both against the SAME character count: the per-write
 * shape (65536 {@code write-char}s) and the per-chunk one (64 {@code write-string}s of 1
 * KiB), which used to differ by 15x and now do not differ at all.
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmStringStreamArenaE2eTest#nodeIsAvailable")
class WasmStringStreamArenaE2eTest {

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

	// The :string parameter is what makes this a memory-exporting module (the host arena
	// API rides on the memory helpers), and it carries the unit both loops repeat.
	private static final String MODULE = """
			(defun build-chars (unit n)
			  (let ((c (char unit 0)))
			    (length (with-output-to-string (s) (dotimes (i n) (write-char c s))))))
			(rontolisp:wasm-export 'build-chars :as "buildChars" :params '(:string :int) :returns :int)

			(defun build-strings (unit n)
			  (length (with-output-to-string (s) (dotimes (i n) (write-string unit s)))))
			(rontolisp:wasm-export 'build-strings :as "buildStrings" :params '(:string :int) :returns :int)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			const inst = new WebAssembly.Instance(mod, {});
			inst.exports._initialize();
			const x = inst.exports;
			// The repeated unit, staged once: 'x' for the per-character loop, 1 KiB of it
			// for the per-chunk one.
			function stage(text) {
			  const b = new TextEncoder().encode(text);
			  const p = x.__ronto_alloc(b.length);
			  new Uint8Array(x.memory.buffer, p, b.length).set(b);
			  return [p, b.length];
			}
			const one = stage('x'), kib = stage('x'.repeat(1024));
			// What ONE call of this shape leaves behind in the arena. The warm-up call is
			// what pays the one-off growth, so the second call's mark delta is the cost
			// per stream.
			function arenaCost(f, unit, n) {
			  f(unit[0], unit[1], n);
			  const before = x.__ronto_alloc_mark();
			  const len = f(unit[0], unit[1], n);
			  return [len, x.__ronto_alloc_mark() - before];
			}
			console.log(...arenaCost(x.buildChars, one, 65536));
			console.log(...arenaCost(x.buildStrings, kib, 64));
			""";

	@Test
	void aStringOutputStreamCostsTheArenaNothingPerWrite() throws Exception {
		List<LispVal> program = LispReader.readAllFromString(MODULE);
		byte[] wasm = new WasmLispCompiler(false, false, true).compile(program);
		Path wasmFile = this.tempDir.resolve("strstream.wasm");
		Files.write(wasmFile, wasm);
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		List<String> lines = runNode(driver, wasmFile).lines().toList();
		assertThat(lines).hasSize(2);
		// Both loops build the same 65536 characters, and neither costs the arena more
		// than the stream record itself (12 bytes; the budget leaves room for the grow
		// guard's alignment without leaving room for a per-write copy).
		for (String line : lines) {
			String[] parts = line.split(" ");
			assertThat(Integer.parseInt(parts[0])).as("characters built").isEqualTo(65536);
			assertThat(Integer.parseInt(parts[1])).as("arena bytes per stream, from: %s", line).isLessThanOrEqualTo(64);
		}
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
