package am.ik.wasm;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural (no-Docker) tests for the duplicate-body folder that runs as the tail of the
 * tree shaker: a module with N identical bodies emits one, and the emitted module is at
 * the no-duplicate fixpoint. Behavior (a folded module still prints what the unfolded one
 * does, and {@code (eq #'f #'g)} stays NIL when f and g fold) is covered by
 * {@code WasmLispCompilerIntegrationTest} and the {@code ci-spec.yaml} corpus.
 */
class WasmBodyFolderTest {

	private static byte[] compile(String source, OptimizeLevel optimize) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, false, optimize).compile(program);
	}

	private static final String TWINS = """
			(defun fold-twin-a (n) (* n 17))
			(defun fold-twin-b (n) (* n 17))
			(defun fold-twin-c (n) (* n 17))
			(print (fold-twin-a 2))
			(print (fold-twin-b 3))
			(print (fold-twin-c 4))
			""";

	@Test
	void aModuleWithIdenticalBodiesEmitsOne() {
		// The unoptimized module carries one body per definition; the optimized one has
		// folded them -- and not just the three twins: the emitted module holds NO two
		// defined functions with the same declared type and identical code bytes (the
		// fold iterates to that fixpoint, so redirected callers that become identical
		// fold too).
		assertThat(duplicateBodyCount(compile(TWINS, OptimizeLevel.NONE))).isPositive();
		byte[] optimized = compile(TWINS, OptimizeLevel.DEFAULT);
		assertThat(duplicateBodyCount(optimized)).isZero();
		assertFunctionRefsInRange(optimized);
	}

	@Test
	void aSizeLevelModuleReachesTheSameFixpoint() {
		byte[] size = compile(TWINS, OptimizeLevel.SIZE);
		assertThat(duplicateBodyCount(size)).isZero();
		assertFunctionRefsInRange(size);
	}

	@Test
	void theNoGcBackendFoldsToo() {
		// --no-gc declares one type ENTRY per function, so its duplicate signatures are
		// duplicate entries -- the canonical-equality half of the fold key. The reactor
		// carries the two twins plus their two export wrappers; folding the twins makes
		// the wrappers identical in turn (the fixpoint iteration), so two defined
		// functions remain and both export names alias the one wrapper.
		String source = """
				(defun nogc-twin-a (n) (* n 17))
				(defun nogc-twin-b (n) (* n 17))
				(rontolisp:wasm-export 'nogc-twin-a :params '(:int) :returns :int)
				(rontolisp:wasm-export 'nogc-twin-b :params '(:int) :returns :int)
				""";
		List<LispVal> program = LispReader.readAllFromString(source);
		assertThat(
				WasmTreeShaker.parseCodeEntries(section(new NoGcWasmCompiler(OptimizeLevel.NONE).compile(program), 10)))
			.hasSize(4);
		byte[] optimized = new NoGcWasmCompiler(OptimizeLevel.DEFAULT).compile(program);
		assertThat(WasmTreeShaker.parseCodeEntries(section(optimized, 10))).hasSize(2);
		assertThat(exportedFunctionIndices(optimized)).hasSize(2)
			.containsOnly(exportedFunctionIndices(optimized).get(0));
		assertFunctionRefsInRange(optimized);
	}

	private static List<Integer> exportedFunctionIndices(byte[] module) {
		byte[] payload = section(module, 7);
		int[] p = { 0 };
		int count = WasmTreeShaker.readU(payload, p);
		List<Integer> indices = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) {
			WasmTreeShaker.skipName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			int index = WasmTreeShaker.readU(payload, p);
			if (kind == 0x00) {
				indices.add(index);
			}
		}
		return indices;
	}

	// How many defined functions share their declared type index and code bytes with an
	// earlier one (0 = the module is at the folder's fixpoint).
	private static int duplicateBodyCount(byte[] module) {
		int[] defTypeIdx = WasmTreeShaker.parseFunctionSection(section(module, 3));
		List<byte[]> codeEntries = WasmTreeShaker.parseCodeEntries(section(module, 10));
		assertThat(defTypeIdx.length).isEqualTo(codeEntries.size());
		Map<String, Integer> seen = new HashMap<>();
		int duplicates = 0;
		for (int i = 0; i < codeEntries.size(); i++) {
			String key = defTypeIdx[i] + ":" + HexFormat.of().formatHex(codeEntries.get(i));
			if (seen.putIfAbsent(key, i) != null) {
				duplicates++;
			}
		}
		return duplicates;
	}

	// Every function reference a body still holds must name a function that exists.
	private static void assertFunctionRefsInRange(byte[] module) {
		List<byte[]> codeEntries = WasmTreeShaker.parseCodeEntries(section(module, 10));
		int totalFuncs = WasmTreeShaker.importedFunctionCount(module) + codeEntries.size();
		for (byte[] entry : codeEntries) {
			for (WasmTreeShaker.Ref r : WasmTreeShaker.scanBody(entry)) {
				if (r.kind() == WasmTreeShaker.RefKind.FUNC) {
					assertThat(r.index()).isLessThan(totalFuncs);
				}
			}
		}
	}

	private static byte[] section(byte[] module, int id) {
		for (WasmTreeShaker.Section s : WasmTreeShaker.parseSections(module)) {
			if (s.id() == id) {
				return s.payload();
			}
		}
		throw new IllegalStateException("no section " + id);
	}

}
