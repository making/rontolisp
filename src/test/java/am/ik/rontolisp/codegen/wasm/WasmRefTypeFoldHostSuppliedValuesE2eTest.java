package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link am.ik.wasm.WasmRefTypeFolder} decides a module's type tests from the module's
 * own constructors ({@code .kb/wasm-ref-type-fold.md}). The programs here are the shape
 * that asks the most of that: they spell NO float, ratio, bignum, string, character or
 * cons literal of their own, so every representation they meet exists only because the
 * embedded READER built it out of text the host handed across an {@code :s-expr}
 * boundary.
 *
 * <p>
 * That is the one shape where "which representations can this module hold" cannot be read
 * off the source, and getting it wrong is silent: an over-eager fold does not trap or
 * fail to build, it answers {@code NIL} to a {@code floatp} that should say {@code T}, or
 * takes the fixnum arm of {@code +} for a ratio. So the assertion is not that the
 * programs work -- it is that they give the SAME answers with the fold off
 * ({@code --optimize=off}, the one level the pass does not run at) and on, at both levels
 * where it does.
 *
 * <p>
 * The arithmetic cases reduce to a boolean inside the module on purpose: an exported
 * {@code :float} or {@code :s32} return applies the boundary's own coercion rules, which
 * would be a second thing under test. What is pinned is that fixnum, float, ratio, the
 * i64 bignum tier and the limb bignum tier all survive the fold when a value of that
 * representation can actually arrive.
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmRefTypeFoldHostSuppliedValuesE2eTest#nodeIsAvailable")
class WasmRefTypeFoldHostSuppliedValuesE2eTest {

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
			;; Which representation did the host send? Nothing here constructs one.
			(defun classify (x)
			  (let ((v (nth 0 x)))
			    (cond ((floatp v) 1) ((stringp v) 2) ((characterp v) 3)
			          ((consp v) 4) ((symbolp v) 5) ((integerp v) 6) (t 0))))
			(rontolisp:wasm-export 'classify :as "classify" :params '(:s-expr) :returns :int)

			;; Arithmetic over the same values, answered as a boolean so that no boundary
			;; coercion takes part in the result.
			(defun addcheck (x) (if (= (+ (nth 0 x) 1) (nth 1 x)) 1 0))
			(rontolisp:wasm-export 'addcheck :as "addcheck" :params '(:s-expr) :returns :int)
			(defun mulcheck (x) (if (= (* (nth 0 x) (nth 0 x)) (nth 1 x)) 1 0))
			(rontolisp:wasm-export 'mulcheck :as "mulcheck" :params '(:s-expr) :returns :int)
			(defun divcheck (x) (if (= (/ (nth 0 x) (nth 1 x)) (nth 2 x)) 1 0))
			(rontolisp:wasm-export 'divcheck :as "divcheck" :params '(:s-expr) :returns :int)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const enc = new TextEncoder();
			const inst = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[2])), {});
			const e = inst.exports;
			e._initialize();
			const send = (fn, text) => {
			  const b = enc.encode(text);
			  const p = e.__ronto_alloc(b.length);
			  new Uint8Array(e.memory.buffer, p, b.length).set(b);
			  try { return String(e[fn](p, b.length)); } catch (err) { return 'THREW'; }
			};
			// one representation per line: float, string, character, cons, symbol,
			// fixnum, limb-tier bignum, ratio
			for (const t of ['(1.5)', '("s")', '(#\\\\a)', '((1 2))', '(foo)', '(7)',
			                 '(123456789012345678901234567890)', '(1/3)']) {
			  console.log('classify ' + t + ' => ' + send('classify', t));
			}
			// fixnum, float, ratio, i64-tier bignum, limb-tier bignum
			for (const t of ['(7 8)', '(1.5 2.5)', '(1/3 4/3)', '(4611686018427387904 4611686018427387905)',
			                 '(123456789012345678901234567890 123456789012345678901234567891)']) {
			  console.log('addcheck ' + t + ' => ' + send('addcheck', t));
			}
			for (const t of ['(3 9)', '(1.5 2.25)', '(2/3 4/9)', '(4294967296 18446744073709551616)']) {
			  console.log('mulcheck ' + t + ' => ' + send('mulcheck', t));
			}
			for (const t of ['(1 3 1/3)', '(3.0 2 1.5)',
			                 '(123456789012345678901234567890 2 61728394506172839450617283945)']) {
			  console.log('divcheck ' + t + ' => ' + send('divcheck', t));
			}
			""";

	private static final List<String> EXPECTED = List.of("classify (1.5) => 1", "classify (\"s\") => 2",
			"classify (#\\a) => 3", "classify ((1 2)) => 4", "classify (foo) => 5", "classify (7) => 6",
			"classify (123456789012345678901234567890) => 6", "classify (1/3) => 0", "addcheck (7 8) => 1",
			"addcheck (1.5 2.5) => 1", "addcheck (1/3 4/3) => 1",
			"addcheck (4611686018427387904 4611686018427387905) => 1",
			"addcheck (123456789012345678901234567890 123456789012345678901234567891) => 1", "mulcheck (3 9) => 1",
			"mulcheck (1.5 2.25) => 1", "mulcheck (2/3 4/9) => 1", "mulcheck (4294967296 18446744073709551616) => 1",
			"divcheck (1 3 1/3) => 1", "divcheck (3.0 2 1.5) => 1",
			"divcheck (123456789012345678901234567890 2 61728394506172839450617283945) => 1");

	@Test
	void aRepresentationOnlyTheReaderBuildsSurvivesTheFold() throws Exception {
		// --optimize=off is the level the fold does not run at, so it is the control.
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.DEFAULT, OptimizeLevel.SIZE)) {
			assertThat(run(level).lines().toList()).as("optimize=%s", level.spelling()).isEqualTo(EXPECTED);
		}
	}

	private String run(OptimizeLevel level) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(MODULE);
		byte[] wasm = WasmLispCompiler.builder().noWasi(true).optimize(level).build().compile(program);
		Path wasmFile = this.tempDir.resolve("fold-" + level.spelling() + ".wasm");
		Files.write(wasmFile, wasm);
		Path driver = this.tempDir.resolve("fold-" + level.spelling() + ".js");
		Files.writeString(driver, DRIVER, StandardCharsets.UTF_8);
		return runNode(driver, wasmFile);
	}

	private static String runNode(Path driver, Path wasmFile) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("node", driver.toString(), wasmFile.toString()).start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
