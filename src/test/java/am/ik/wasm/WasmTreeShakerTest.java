package am.ik.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural (no-Docker) tests for the WASM dead-code eliminator. These verify the
 * tree-shaker's invariants directly on real compiled modules: the output is smaller,
 * stays well-formed (function and code sections stay aligned, every function reference is
 * in range), the roots survive, and the pass is idempotent. End-to-end execution under
 * wasmtime is covered by {@code WasmLispCompilerIntegrationTest}.
 */
class WasmTreeShakerTest {

	private static byte[] compile(String source, boolean noWasi, boolean optimize) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, noWasi, optimize).compile(program);
	}

	@Test
	void dropsUnreachableFunctionsAndShrinksOutput() {
		String source = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""";
		byte[] plain = compile(source, true, false);
		byte[] optimized = compile(source, true, true);

		assertThat(optimized.length).isLessThan(plain.length / 5);
		Module before = Module.parse(plain);
		Module after = Module.parse(optimized);
		assertThat(after.definedFunctionCount()).isLessThan(before.definedFunctionCount());
		// no-wasi reactor: the top-level init entry is exported as `_initialize`, not
		// `_start`.
		assertThat(after.exportedFunctionNames()).contains("fact", "_initialize");
		after.assertWellFormed();
	}

	@Test
	void dropsUnusedWasiImports() {
		// A program that only prints uses fd_write; the other seven WASI imports are
		// dead.
		byte[] optimized = compile("(print (+ 1 2))", false, true);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		assertThat(m.functionImportNames()).containsExactly("fd_write");
	}

	@Test
	void keepsTransitivelyReachableRuntime() {
		// Ratio arithmetic reaches the rational runtime helpers; they must survive.
		byte[] optimized = compile("(print (+ 1/3 1/6))", false, true);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		assertThat(m.exportedFunctionNames()).contains("_start");
	}

	@Test
	void shakesEhModeModules() {
		// EH mode (todo 129): the shaker must walk try_table/throw/throw_ref
		// immediates correctly and keep the tag section verbatim, so P1 EH +
		// --optimize compose.
		String source = """
				(defun protected-div (a b)
				  (handler-case (/ a b) (error (e) -1)))
				(print (unwind-protect (protected-div 10 2) (print :cleaned)))
				""";
		byte[] plain = compile(source, false, false);
		byte[] optimized = compile(source, false, true);
		assertThat(optimized.length).isLessThan(plain.length);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		assertThat(m.exportedFunctionNames()).contains("_start");
		// Idempotence over the EH opcodes too.
		assertThat(WasmTreeShaker.shake(optimized)).isEqualTo(optimized);
	}

	@Test
	void isIdempotent() {
		byte[] once = WasmTreeShaker.shake(compile("(print 42)", false, false));
		byte[] twice = WasmTreeShaker.shake(once);
		assertThat(twice).isEqualTo(once);
	}

	@Test
	void returnsEquivalentModuleWhenNothingToDrop() {
		// Shaking an already-minimal module should not corrupt it.
		byte[] optimized = compile("(print 1)", false, true);
		assertThat(WasmTreeShaker.shake(optimized)).isEqualTo(optimized);
	}

	/**
	 * A minimal read-only view of a core WASM module sufficient to assert the
	 * tree-shaker's structural invariants. Mirrors the binary framing the shaker itself
	 * relies on.
	 */
	private record Module(int functionImportCount, List<String> functionImportNames, int definedFunctionCount,
			int codeEntryCount, List<String> exportedFunctionNames, List<Integer> exportedFunctionIndices) {

		void assertWellFormed() {
			// Function section and code section must stay aligned.
			assertThat(codeEntryCount).isEqualTo(definedFunctionCount);
			// Every exported function index must be in range.
			int total = functionImportCount + definedFunctionCount;
			assertThat(exportedFunctionIndices).allSatisfy(i -> assertThat(i).isBetween(0, total - 1));
		}

		static Module parse(byte[] module) {
			int[] p = { 8 };
			int functionImportCount = 0;
			List<String> functionImportNames = new ArrayList<>();
			int definedFunctionCount = 0;
			int codeEntryCount = 0;
			List<String> exportedFunctionNames = new ArrayList<>();
			List<Integer> exportedFunctionIndices = new ArrayList<>();
			while (p[0] < module.length) {
				int id = module[p[0]++] & 0xff;
				int size = readU(module, p);
				int end = p[0] + size;
				switch (id) {
					case 2 -> { // import
						int count = readU(module, p);
						for (int i = 0; i < count; i++) {
							skipName(module, p); // module
							String name = readName(module, p); // field
							int kind = module[p[0]++] & 0xff;
							switch (kind) {
								case 0x00 -> { // func
									readU(module, p); // typeidx
									functionImportCount++;
									functionImportNames.add(name);
								}
								case 0x01 -> { // table
									skipValType(module, p);
									skipLimits(module, p);
								}
								case 0x02 -> skipLimits(module, p);
								case 0x03 -> { // global
									skipValType(module, p);
									p[0]++;
								}
								default -> throw new IllegalStateException("kind " + kind);
							}
						}
					}
					case 3 -> definedFunctionCount = readU(module, p); // function
					case 7 -> { // export
						int count = readU(module, p);
						for (int i = 0; i < count; i++) {
							String name = readName(module, p);
							int kind = module[p[0]++] & 0xff;
							int index = readU(module, p);
							if (kind == 0x00) {
								exportedFunctionNames.add(name);
								exportedFunctionIndices.add(index);
							}
						}
					}
					case 10 -> codeEntryCount = readU(module, p); // code
					default -> {
					}
				}
				p[0] = end;
			}
			return new Module(functionImportCount, functionImportNames, definedFunctionCount, codeEntryCount,
					exportedFunctionNames, exportedFunctionIndices);
		}

		private static int readU(byte[] buf, int[] p) {
			int result = 0;
			int shift = 0;
			while (true) {
				int b = buf[p[0]++] & 0xff;
				result |= (b & 0x7f) << shift;
				if ((b & 0x80) == 0) {
					return result;
				}
				shift += 7;
			}
		}

		private static String readName(byte[] buf, int[] p) {
			int len = readU(buf, p);
			String s = new String(buf, p[0], len, java.nio.charset.StandardCharsets.UTF_8);
			p[0] += len;
			return s;
		}

		private static void skipName(byte[] buf, int[] p) {
			// Read the length first: `p[0] += readU(buf, p)` would discard readU's own
			// advance of p[0] (compound assignment captures the old p[0] before the
			// call).
			int len = readU(buf, p);
			p[0] += len;
		}

		private static void skipValType(byte[] buf, int[] p) {
			int b = buf[p[0]++] & 0xff;
			if (b == 0x63 || b == 0x64) {
				readU(buf, p);
			}
		}

		private static void skipLimits(byte[] buf, int[] p) {
			int flag = buf[p[0]++] & 0xff;
			readU(buf, p);
			if ((flag & 0x01) != 0) {
				readU(buf, p);
			}
		}
	}

}
