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
	void longBoundaryKeepsTheHostSignatureI64WithNoWrapOrExtend() {
		// :long pins both the internal i64 representation AND the host boundary to i64,
		// so
		// the wrapper signature is (i64,i64)->i64 -- identical to the internal function,
		// unlike :int which is (i32,...)->i32 with wrap/extend in the wrapper.
		List<int[][]> types = funcTypes(Objects.requireNonNull(sections(compile("""
				(defun sumsquared (a b) (* (+ a b) (+ a b)))
				(rontolisp:wasm-export 'sumsquared :params '(:long :long) :returns :long)
				""")).get(1)));
		// type 0 = internal, type 1 = wrapper; both (i64,i64) -> i64.
		assertThat(types.get(0)[0]).containsExactly(0x7E, 0x7E); // i64,i64 params
		assertThat(types.get(0)[1]).containsExactly(0x7E); // i64 result
		assertThat(types.get(1)[0]).containsExactly(0x7E, 0x7E); // i64,i64 host params
		assertThat(types.get(1)[1]).containsExactly(0x7E); // i64 host result
	}

	@Test
	void iterationAndLocalMutationCompileToAPlainMvpModule() {
		// dotimes + a let-bound accumulator mutated by setq: no heap, no GC types, still
		// a
		// plain MVP module that exports the function.
		byte[] module = compile("""
				(defun sum-upto (n)
				  (let ((acc 0))
				    (dotimes (i n) (setq acc (+ acc i)))
				    acc))
				(rontolisp:wasm-export 'sum-upto :params '(:int) :returns :int)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).doesNotContainKey(2).doesNotContainKey(5);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("sum-upto");
	}

	@Test
	void aFloatAccumulatorWidensTheLocalAndReturnTypeToF64() {
		// acc starts as an integer 0 but is summed with floats, so the inferred local and
		// the function's return type widen to f64.
		List<int[][]> funcTypes = funcTypes(Objects.requireNonNull(sections(compile("""
				(defun sumsq (n)
				  (let ((acc 0))
				    (dotimes (i n) (setq acc (+ acc (* (float i) (float i)))))
				    acc))
				(rontolisp:wasm-export 'sumsq :params '(:int) :returns :float)
				""")).get(1)));
		// type 0 = internal sumsq: (i64) -> f64 (param pinned i64, return widened to f64)
		assertThat(funcTypes.get(0)[0]).containsExactly(0x7E); // i64 param
		assertThat(funcTypes.get(0)[1]).containsExactly(0x7C); // f64 result
	}

	@Test
	void mathBuiltinsCompileToAPlainMvpModule() {
		byte[] module = compile("""
				(defun popcount (x)
				  (let ((c 0))
				    (do ((v x (ash v -1))) ((= v 0) c)
				      (setq c (+ c (logand v 1))))))
				(defun root (x) (sqrt x))
				(rontolisp:wasm-export 'popcount :params '(:int) :returns :int)
				(rontolisp:wasm-export 'root :params '(:float) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).doesNotContainKey(2).doesNotContainKey(5);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("popcount", "root");
	}

	@Test
	void rejectsSetqOfANonLocal() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) (setq g (+ n 1)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("setq target 'g'")
			.hasMessageContaining("not a parameter or let binding");
	}

	@Test
	void rejectsSpecialVariableDeclaration() {
		// Special (dynamically bound) variables need a global backing store the scalar
		// backend does not have; a top-level defvar is rejected outright (only defun and
		// wasm-export are allowed at top level), so a special can never be declared here.
		assertThatThrownBy(() -> compile("""
				(defvar *x* 1)
				(defun f (n) (let ((*x* n)) *x*))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("--no-gc supports only");
	}

	@Test
	void rejectsListIteration() {
		// dolist iterates a list (car/cdr), which is ineligible; it is not an expanded
		// core
		// form here, so it is rejected as an unsupported operation.
		assertThatThrownBy(() -> compile("""
				(defun f (n) (let ((s 0)) (dolist (x (list 1 2 n)) (setq s (+ s x))) s))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("dolist");
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
	void rejectsSexprExportType() {
		// :string is supported (Phase 2a), but :s-expr still needs a cons/reader/printer
		// runtime and is rejected.
		assertThatThrownBy(() -> compile("""
				(defun id (s) s)
				(rontolisp:wasm-export 'id :params '(:s-expr) :returns :s-expr)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining(":s-expr");
	}

	@Test
	void stringExportEmitsLinearMemoryDataAndAllocator() {
		// A :string-returning export that concatenates literals needs linear memory: the
		// module now carries a memory (id 5), global (id 6) and data (id 11) section, and
		// exports the memory + the __ronto_alloc bump allocator alongside the function.
		byte[] module = compile("""
				(defun shade (i) (cond ((>= i 10) "#") ((>= i 5) ".") (t " ")))
				(defun row (n) (let ((out "")) (dotimes (k n) (setq out (concatenate 'string out (shade k)))) out))
				(rontolisp:wasm-export 'row :params '(:int) :returns :string)
				""");
		Map<Integer, byte[]> sections = sections(module);
		// Still a plain MVP module: no import (id 2) and no wasm-GC rec group.
		assertThat(sections).doesNotContainKey(2);
		assertThat(sections).containsKey(5); // memory
		assertThat(sections).containsKey(6); // global (heap pointer)
		assertThat(sections).containsKey(11); // data (string literals)
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("row", "memory", "__ronto_alloc");
	}

	@Test
	void aStringValuedFunctionInfersAnI32ReturnType() {
		// concatenate yields a STRING, lowered to an i32 pointer internally; the wrapper
		// returns the host (ptr,len) pair (two i32 results).
		byte[] module = compile("""
				(defun greet () (concatenate 'string "hi " "there"))
				(rontolisp:wasm-export 'greet :params '() :returns :string)
				""");
		List<int[][]> types = funcTypes(Objects.requireNonNull(sections(module).get(1)));
		// type 0 = internal greet: no params, one i32 (string pointer) result.
		assertThat(types.get(0)[0]).isEmpty();
		assertThat(types.get(0)[1]).containsExactly(0x7F); // i32 pointer
		// type 1 = wrapper: no params, two i32 results (content ptr, length).
		assertThat(types.get(1)[1]).containsExactly(0x7F, 0x7F);
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

	@Test
	void scalarReturnExportResetsTheBumpHeapAtWrapperExit() {
		// A :string param copies the host bytes into a fresh internal [len][bytes] header
		// via __ronto_alloc inside the wrapper; with a scalar return that copy is dead
		// the
		// moment the call returns, so the wrapper snapshots and restores heap global 0
		// (todo 88). Assert both :int and :long returns carry the reset in the wrapper
		// body.
		for (String ret : List.of(":int", ":long")) {
			byte[] module = compile("""
					(defun count-vowels (s)
					  (let ((n 0)) (dotimes (i (length s)) (when (char= (char s i) #\\a) (setq n (+ n 1)))) n))
					(rontolisp:wasm-export 'count-vowels :as "cv" :params '(:string) :returns %s)
					""".formatted(ret));
			Map<Integer, byte[]> sections = sections(module);
			byte[] wrapper = functionBody(Objects.requireNonNull(sections.get(10)),
					exportedFuncIndex(Objects.requireNonNull(sections.get(7)), "cv"));
			assertThat(containsGlobalReset(wrapper)).as("wrapper for :string -> %s resets heap global 0", ret).isTrue();
		}
	}

	@Test
	void stringReturnExportDoesNotResetTheHeap() {
		// A :string result is a live heap pointer the host is about to read, so the
		// wrapper
		// must NOT reset the heap (that would free the returned string).
		byte[] module = compile("""
				(defun echo (s) (concatenate 'string s s))
				(rontolisp:wasm-export 'echo :params '(:string) :returns :string)
				""");
		Map<Integer, byte[]> sections = sections(module);
		byte[] wrapper = functionBody(Objects.requireNonNull(sections.get(10)),
				exportedFuncIndex(Objects.requireNonNull(sections.get(7)), "echo"));
		assertThat(containsGlobalReset(wrapper)).as("wrapper for :string -> :string does not reset the heap").isFalse();
	}

	@Test
	void pureNumericExportEmitsNoHeapReset() {
		// A pure-numeric export has no linear memory at all (no global 0 exists), so no
		// reset is emitted -- the wrapper is byte-for-byte the pre-todo-88 shape.
		byte[] module = compile("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).doesNotContainKey(6); // no global section
		byte[] wrapper = functionBody(Objects.requireNonNull(sections.get(10)),
				exportedFuncIndex(Objects.requireNonNull(sections.get(7)), "fact"));
		assertThat(containsGlobalReset(wrapper)).as("pure-numeric wrapper has no heap reset").isFalse();
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

	// Extracts the body (locals decl + instructions) of the function at the given index
	// in
	// the code section (id 10). No imports exist in a --no-gc module, so the function
	// index
	// equals the code-section entry index.
	private static byte[] functionBody(byte[] codeSection, int index) {
		int[] p = { 0 };
		int count = readUleb(codeSection, p);
		for (int i = 0; i < count; i++) {
			int size = readUleb(codeSection, p);
			if (i == index) {
				byte[] body = new byte[size];
				System.arraycopy(codeSection, p[0], body, 0, size);
				return body;
			}
			p[0] += size;
		}
		throw new IllegalArgumentException("no function at index " + index);
	}

	// Returns the function index of the export with the given name (external kind 0x00).
	private static int exportedFuncIndex(byte[] exportSection, String name) {
		int[] p = { 0 };
		int count = readUleb(exportSection, p);
		for (int i = 0; i < count; i++) {
			int len = readUleb(exportSection, p);
			String n = new String(exportSection, p[0], len, StandardCharsets.UTF_8);
			p[0] += len;
			int kind = exportSection[p[0]++] & 0xFF;
			int idx = readUleb(exportSection, p);
			if (kind == 0x00 && n.equals(name)) {
				return idx;
			}
		}
		throw new IllegalArgumentException("no exported function named " + name);
	}

	// True if the body contains both a global.get 0 (0x23 0x00) and a global.set 0
	// (0x24 0x00) -- the heap snapshot/restore pair. The wrapper never calls the
	// allocator
	// inline, so its only global ops are the todo-88 reset.
	private static boolean containsGlobalReset(byte[] body) {
		boolean getGlobal0 = false;
		boolean setGlobal0 = false;
		for (int i = 0; i + 1 < body.length; i++) {
			int op = body[i] & 0xFF;
			int operand = body[i + 1] & 0xFF;
			if (op == 0x23 && operand == 0x00) {
				getGlobal0 = true;
			}
			if (op == 0x24 && operand == 0x00) {
				setGlobal0 = true;
			}
		}
		return getGlobal0 && setGlobal0;
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
