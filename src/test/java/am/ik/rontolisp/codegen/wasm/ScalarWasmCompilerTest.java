package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural tests for {@link ScalarWasmCompiler}: the emitted module is a plain MVP
 * module (no wasm-GC types, no imports, no memory), exports the requested functions, and
 * ineligible functions reachable from an export are rejected with a clear error. These
 * run without Docker; the end-to-end {@code wasmtime --invoke} (without {@code -W gc})
 * checks live in {@link WasmLispCompilerIntegrationTest}.
 */
class ScalarWasmCompilerTest {

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new ScalarWasmCompiler().compile(program);
	}

	@Test
	void emitsPlainMvpModuleWithNoGcTypesImportsOrMemory() {
		byte[] module = compile("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""");
		assertThat(new String(module, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("\0asm");
		Map<Integer, byte[]> sections = sections(module);
		// No import section (id 2) and no memory section (id 5): instantiable with no
		// import object and no linear memory.
		assertThat(sections).doesNotContainKey(2).doesNotContainKey(5);
		// Type section present and made up only of func types over i32/f64 (no rec group,
		// no struct/array/i31, no eqref).
		assertThat(sections).containsKey(1);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		// The wrapper is exported under the function name.
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("fact");
	}

	@Test
	void floatAndBoolExportsUseScalarSignatures() {
		byte[] module = compile("""
				(defun area (r) (* 3.14159 (* r r)))
				(defun in-range (x) (if (< x 0) nil (if (> x 100) nil t)))
				(rontolisp:wasm-export 'area :params '(:float) :returns :float)
				(rontolisp:wasm-export 'in-range :params '(:int) :returns :bool)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("area", "in-range");
	}

	@Test
	void infersI64ForIntegerFunctionsAndF64ForFloatFunctions() {
		// fact: (i64) -> i64 internally, (i32) -> i32 at the host boundary.
		List<int[][]> intTypes = funcTypes(Objects.requireNonNull(sections(compile("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""")).get(1)));
		// type 0 = internal fact, type 1 = wrapper
		assertThat(intTypes.get(0)[0]).containsExactly(0x7E); // i64 param
		assertThat(intTypes.get(0)[1]).containsExactly(0x7E); // i64 result
		assertThat(intTypes.get(1)[0]).containsExactly(0x7F); // i32 param (host)
		assertThat(intTypes.get(1)[1]).containsExactly(0x7F); // i32 result (host)

		// area: (f64) -> f64 internally, matching the :float boundary.
		List<int[][]> floatTypes = funcTypes(Objects.requireNonNull(sections(compile("""
				(defun area (r) (* 3.14159 (* r r)))
				(rontolisp:wasm-export 'area :params '(:float) :returns :float)
				""")).get(1)));
		assertThat(floatTypes.get(0)[0]).containsExactly(0x7C); // f64 param
		assertThat(floatTypes.get(0)[1]).containsExactly(0x7C); // f64 result
	}

	@Test
	void onlyReachableFunctionsAreCompiledSoUnreachedIneligibleCodeIsIgnored() {
		// `helper` conses (ineligible), but it is not reachable from the single export,
		// so
		// the module compiles fine and `helper` is simply dropped.
		byte[] module = compile("""
				(defun helper (x) (car (cons x x)))
				(defun square (x) (* x x))
				(rontolisp:wasm-export 'square :params '(:int) :returns :int)
				""");
		assertThat(exportNames(Objects.requireNonNull(sections(module).get(7)))).containsExactly("square");
	}

	@Test
	void rejectsIneligibleOperationReachableFromAnExport() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) (car (cons n n)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("car")
			.hasMessageContaining("f");
	}

	@Test
	void rejectsFreeVariable() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) (+ n missing))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("missing");
	}

	@Test
	void rejectsMemoryBackedExportType() {
		assertThatThrownBy(() -> compile("""
				(defun id (s) s)
				(rontolisp:wasm-export 'id :params '(:string) :returns :string)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining(":string");
	}

	@Test
	void rejectsTopLevelExpression() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) n)
				(print 1)
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("top level");
	}

	@Test
	void rejectsProgramWithNoExports() {
		assertThatThrownBy(() -> compile("(defun f (n) n)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("at least one");
	}

	@Test
	void rejectsExportOfUnknownFunction() {
		assertThatThrownBy(() -> compile("(rontolisp:wasm-export 'nope :params '(:int) :returns :int)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("unknown function");
	}

	@Test
	void rejectsArityMismatch() {
		assertThatThrownBy(() -> compile("""
				(defun f (a b) (+ a b))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("arity mismatch");
	}

	@Test
	void optimizeProducesAValidShapeAndKeepsTheExport() {
		byte[] module = new ScalarWasmCompiler(true).compile(LispReader.readAllFromString("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				"""));
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).doesNotContainKey(2);
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("fact");
	}

	// --- minimal binary parsing helpers ------------------------------------------------

	// Splits a module into a map of section id -> section payload bytes (skipping the
	// 8-byte header). Assumes at most one of each non-custom section, which holds here.
	private static Map<Integer, byte[]> sections(byte[] module) {
		Map<Integer, byte[]> result = new HashMap<>();
		int[] p = { 8 };
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xFF;
			int size = readUleb(module, p);
			byte[] payload = new byte[size];
			System.arraycopy(module, p[0], payload, 0, size);
			p[0] += size;
			result.put(id, payload);
		}
		return result;
	}

	// Asserts the type section contains only function types whose params and results are
	// plain numeric value types (i32 0x7F, i64 0x7E, f64 0x7C) -- i.e. no rec group /
	// struct / array / i31 / ref types. Internal functions use i64/f64; the host wrappers
	// use i32/f64.
	private static void assertScalarFuncTypes(byte[] typeSection) {
		int[] p = { 0 };
		int count = readUleb(typeSection, p);
		for (int i = 0; i < count; i++) {
			int form = typeSection[p[0]++] & 0xFF;
			assertThat(form).as("type %d is a func type (0x60)", i).isEqualTo(0x60);
			int params = readUleb(typeSection, p);
			for (int j = 0; j < params; j++) {
				assertScalarValType(typeSection[p[0]++] & 0xFF);
			}
			int results = readUleb(typeSection, p);
			for (int j = 0; j < results; j++) {
				assertScalarValType(typeSection[p[0]++] & 0xFF);
			}
		}
		assertThat(p[0]).isEqualTo(typeSection.length);
	}

	// Parses the type section into a list of {paramValTypes, resultValTypes} per func
	// type.
	private static List<int[][]> funcTypes(byte[] typeSection) {
		List<int[][]> result = new ArrayList<>();
		int[] p = { 0 };
		int count = readUleb(typeSection, p);
		for (int i = 0; i < count; i++) {
			p[0]++; // 0x60 func form
			int[] params = new int[readUleb(typeSection, p)];
			for (int j = 0; j < params.length; j++) {
				params[j] = typeSection[p[0]++] & 0xFF;
			}
			int[] results = new int[readUleb(typeSection, p)];
			for (int j = 0; j < results.length; j++) {
				results[j] = typeSection[p[0]++] & 0xFF;
			}
			result.add(new int[][] { params, results });
		}
		return result;
	}

	private static void assertScalarValType(int valType) {
		assertThat(valType).as("value type is a plain numeric type (i32/i64/f64)").isIn(0x7F, 0x7E, 0x7C);
	}

	private static List<String> exportNames(byte[] exportSection) {
		List<String> names = new ArrayList<>();
		int[] p = { 0 };
		int count = readUleb(exportSection, p);
		for (int i = 0; i < count; i++) {
			int len = readUleb(exportSection, p);
			names.add(new String(exportSection, p[0], len, StandardCharsets.UTF_8));
			p[0] += len;
			p[0]++; // external kind
			readUleb(exportSection, p); // index
		}
		return names;
	}

	private static int readUleb(byte[] bytes, int[] p) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = bytes[p[0]++] & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		return result;
	}

}
