package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural tests for {@link NoGcWasmCompiler}: the emitted module is a plain MVP module
 * (no wasm-GC types, no imports, no memory), exports the requested functions, and
 * ineligible functions reachable from an export are rejected with a clear error. These
 * run without Docker; the end-to-end {@code wasmtime --invoke} checks live in
 * {@link WasmLispCompilerIntegrationTest}.
 */
class NoGcWasmCompilerTest {

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new NoGcWasmCompiler().compile(program);
	}

	// Compiles with --simd on, so the vectorizable vec: kernels lower to native v128
	// (f64x2/f32x4). Without --simd (the plain compile() above) they lower to scalar
	// loops
	// with no 0xFD opcode. The [count][data] block layout is identical either way.
	private static byte[] compileSimd(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new NoGcWasmCompiler(OptimizeLevel.NONE, true).compile(program);
	}

	@Test
	void unwindProtectIsCompileError() {
		// The wasm-GC backends now catch via the exception-handling proposal (todo
		// 129), but --no-gc keeps the clear rejection: no condition objects in its
		// unboxed value model, and its contract is a zero-flag plain MVP module.
		assertThatThrownBy(() -> compile("""
				(defun up-f (n) (unwind-protect (* n 2) n))
				(rontolisp:wasm-export 'up-f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("UNWIND-PROTECT");
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
	void longBoundaryExportsTheInternalFunctionDirectly() {
		// :long pins both the internal i64 representation AND the host boundary to i64,
		// so a wrapper would be a pure pass-through (get_local*n; call; end). No wrapper
		// is emitted: the export names the internal function itself.
		byte[] module = compile("""
				(defun sumsquared (a b) (* (+ a b) (+ a b)))
				(rontolisp:wasm-export 'sumsquared :params '(:long :long) :returns :long)
				""");
		Map<Integer, byte[]> sections = sections(module);
		List<int[][]> types = funcTypes(Objects.requireNonNull(sections.get(1)));
		// Exactly one function in the module: the internal (i64,i64) -> i64.
		assertThat(types).hasSize(1);
		assertThat(types.get(0)[0]).containsExactly(0x7E, 0x7E); // i64,i64 params
		assertThat(types.get(0)[1]).containsExactly(0x7E); // i64 result
		assertThat(functionBodies(Objects.requireNonNull(sections.get(10)))).hasSize(1);
		assertThat(exportedFuncIndex(Objects.requireNonNull(sections.get(7)), "sumsquared")).isEqualTo(0);
	}

	@Test
	void floatBoundaryExportsTheInternalFunctionDirectly() {
		// :float pins f64 on both sides, so the same pass-through elision applies.
		byte[] module = compile("""
				(defun area (r) (* 3.14159 (* r r)))
				(rontolisp:wasm-export 'area :params '(:float) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(funcTypes(Objects.requireNonNull(sections.get(1)))).hasSize(1);
		assertThat(exportedFuncIndex(Objects.requireNonNull(sections.get(7)), "area")).isEqualTo(0);
	}

	@Test
	void mixedBoundariesElideOnlyThePassThroughWrapper() {
		// sumsquared (:long -> :long) is a pass-through and exports the internal
		// function; fact (:int -> :int) needs wrap/extend marshalling and keeps its
		// wrapper, which is the sole function after the internals.
		byte[] module = compile("""
				(defun sumsquared (a b) (* (+ a b) (+ a b)))
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'sumsquared :params '(:long :long) :returns :long)
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""");
		Map<Integer, byte[]> sections = sections(module);
		byte[] exports = Objects.requireNonNull(sections.get(7));
		// 2 internals + 1 wrapper (fact's); sumsquared -> internal 0, fact -> wrapper 2.
		assertThat(functionBodies(Objects.requireNonNull(sections.get(10)))).hasSize(3);
		assertThat(exportedFuncIndex(exports, "sumsquared")).isEqualTo(0);
		assertThat(exportedFuncIndex(exports, "fact")).isEqualTo(2);
	}

	@Test
	void longBoundaryOverAFloatResultKeepsTheTruncatingWrapper() {
		// The internal return type is inferred f64, so the :long boundary needs an
		// i64.trunc_f64_s conversion -- the wrapper stays.
		byte[] module = compile("""
				(defun half (a) (/ (float a) 2.0))
				(rontolisp:wasm-export 'half :params '(:long) :returns :long)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(functionBodies(Objects.requireNonNull(sections.get(10)))).hasSize(2);
		assertThat(exportedFuncIndex(Objects.requireNonNull(sections.get(7)), "half")).isEqualTo(1);
	}

	@Test
	void memoryUsingModuleKeepsTheHeapResetWrapperForALongExport() {
		// A string literal makes the module use linear memory, so a scalar-return
		// export must keep its wrapper for the bump-heap reset even when the
		// host signature matches the internal one exactly.
		byte[] module = compile("""
				(defun withlit (a) (+ a (length "hello")))
				(rontolisp:wasm-export 'withlit :params '(:long) :returns :long)
				""");
		Map<Integer, byte[]> sections = sections(module);
		byte[] wrapper = functionBody(Objects.requireNonNull(sections.get(10)),
				exportedFuncIndex(Objects.requireNonNull(sections.get(7)), "withlit"));
		assertThat(containsGlobalReset(wrapper)).as("memory-using :long export keeps the heap-reset wrapper").isTrue();
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
			.hasMessageContaining("setq target 'G'")
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
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("DOLIST");
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
			.hasMessageContaining("CAR")
			.hasMessageContaining("f");
	}

	@Test
	void rejectsFreeVariable() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) (+ n missing))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("MISSING");
	}

	@Test
	void rejectsSexprExportType() {
		// :string is supported, but :s-expr still needs a cons/reader/printer
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
		byte[] module = new NoGcWasmCompiler(OptimizeLevel.DEFAULT).compile(LispReader.readAllFromString("""
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
		// the moment the call returns, so the wrapper snapshots and restores heap
		// global 0. Assert both :int and :long returns carry the reset in the wrapper
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
		// reset is emitted -- the wrapper carries no snapshot/restore pair at all.
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

	@Test
	void stringModuleExportsTheHostArenaApi() {
		// A module that uses linear memory exports the two arena functions
		// (__ronto_alloc_mark / __ronto_alloc_reset) alongside memory and
		// __ronto_alloc, so a resident host can bracket its own input allocation.
		byte[] module = compile("""
				(defun count-vowels (s)
				  (let ((n 0)) (dotimes (i (length s)) (when (char= (char s i) #\\a) (setq n (+ n 1)))) n))
				(rontolisp:wasm-export 'count-vowels :as "cv" :params '(:string) :returns :int)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("cv", "memory", "__ronto_alloc",
				"__ronto_alloc_mark", "__ronto_alloc_reset");
	}

	@Test
	void pureNumericModuleOmitsTheHostArenaApi() {
		// No linear memory (no heap-pointer global exists), so neither arena function is
		// emitted -- a pure-numeric module exports neither the mark nor the reset.
		byte[] module = compile("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""");
		assertThat(exportNames(Objects.requireNonNull(sections(module).get(7)))).doesNotContain("__ronto_alloc_mark",
				"__ronto_alloc_reset");
	}

	@Test
	void hostArenaApiBodiesAreTheHeapPointerGetAndSet() {
		// __ronto_alloc_mark is `global.get 0; end` (no locals) and __ronto_alloc_reset
		// is
		// `local.get 0; global.set 0; end` (no locals) -- the two halves of a bump-arena
		// snapshot/restore over heap-pointer global 0.
		byte[] module = compile("""
				(defun echo (s) (concatenate 'string s s))
				(rontolisp:wasm-export 'echo :params '(:string) :returns :string)
				""");
		Map<Integer, byte[]> sections = sections(module);
		byte[] code = Objects.requireNonNull(sections.get(10));
		byte[] exports = Objects.requireNonNull(sections.get(7));
		byte[] mark = functionBody(code, exportedFuncIndex(exports, "__ronto_alloc_mark"));
		byte[] reset = functionBody(code, exportedFuncIndex(exports, "__ronto_alloc_reset"));
		// [locals=0][global.get 0][end]
		assertThat(mark).containsExactly(0x00, 0x23, 0x00, 0x0B);
		// [locals=0][local.get 0][global.set 0][end]
		assertThat(reset).containsExactly(0x00, 0x20, 0x00, 0x24, 0x00, 0x0B);
	}

	// --- print / stdout --------------------------------------------------------------

	// The exact import-section payload a printing module must carry: exactly one entry,
	// (import "wasi_snapshot_preview1" "fd_write" (func (type 0))).
	private static byte[] fdWriteImportSection() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0x01); // one import
		byte[] module = "wasi_snapshot_preview1".getBytes(StandardCharsets.UTF_8);
		out.write(module.length);
		out.writeBytes(module);
		byte[] field = "fd_write".getBytes(StandardCharsets.UTF_8);
		out.write(field.length);
		out.writeBytes(field);
		out.write(0x00); // external kind: function
		out.write(0x00); // type index 0
		return out.toByteArray();
	}

	@Test
	void printGatesTheFdWriteImportOnAndOff() {
		// The SAME defun with and without the print: only the printing build gains an
		// import section, and its bytes are exactly the single fd_write entry. This pins
		// the contract in both directions: a print-free program keeps ZERO imports
		// (the foundation of the adapter-free --no-gc component), a printing program
		// imports exactly wasi_snapshot_preview1.fd_write.
		byte[] printing = compile("""
				(defun show (n) (print n) n)
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				""");
		byte[] silent = compile("""
				(defun show (n) n)
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				""");
		assertThat(sections(silent)).doesNotContainKey(2);
		byte[] importSection = Objects.requireNonNull(sections(printing).get(2));
		assertThat(importSection).containsExactly(fdWriteImportSection());
	}

	@Test
	void printingModuleStillRunsThroughStringMachineryAndExportsWork() {
		// print of int/float/string/bool literals and princ/terpri all compile; the
		// module keeps the memory + allocator machinery and the export under its name.
		byte[] module = compile("""
				(defun show (n)
				  (print n)
				  (print 3.14)
				  (print "hello")
				  (princ "bare")
				  (print t)
				  (print nil)
				  (terpri)
				  n)
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(2).containsKey(5);
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("show", "memory", "__ronto_alloc",
				"__ronto_alloc_mark", "__ronto_alloc_reset");
	}

	@Test
	void fdWriteImportShiftsEveryFunctionIndexByOne() {
		// The import occupies function index 0, so the exported wrapper of the FIRST
		// program sits one above the print-free build's -- all index math flows through
		// the Mem.funcIndex accessor.
		byte[] printing = compile("""
				(defun show (n) (print n) n)
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				""");
		byte[] silent = compile("""
				(defun show (n) (princ-to-string n) n)
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				""");
		int printingIndex = exportedFuncIndex(Objects.requireNonNull(sections(printing).get(7)), "show");
		int silentIndex = exportedFuncIndex(Objects.requireNonNull(sections(silent).get(7)), "show");
		assertThat(printingIndex).isEqualTo(silentIndex + 1);
	}

	@Test
	void princToStringOfAFloatNowCompiles() {
		// The __ftoa port lifts the old "no float printer in scalar mode"
		// error; a float renders through the same digit-extraction algorithm as the GC
		// backend. No print op is involved, so the module still has ZERO imports.
		byte[] module = compile("""
				(defun render (x) (length (princ-to-string x)))
				(rontolisp:wasm-export 'render :params '(:float) :returns :int)
				""");
		assertThat(sections(module)).doesNotContainKey(2).containsKey(5);
	}

	@Test
	void printRejectsTheStreamArgumentAndPackedArrays() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) (print n 1))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("stream argument");
		assertThatThrownBy(() -> compile("""
				(defun f () (terpri 1))
				(rontolisp:wasm-export 'f :params '() :returns :void)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("stream argument");
		assertThatThrownBy(() -> compile("""
				(defun f (n) (print (vec:ones n)) n)
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("packed float array");
	}

	// --- with-arena ------------------------------------------------------------------

	@Test
	void withArenaCompilesForScalarStringAndPackedResults() {
		// A scalar, a string and a packed-vector result all compile; the module keeps
		// zero imports (with-arena is pure memory management, no I/O).
		byte[] module = compile("""
				(defun s (n)
				  (rontolisp:with-arena () (+ n 1)))
				(defun str ()
				  (rontolisp:with-arena () (concatenate 'string "a" "b")))
				(defun v (n)
				  (vec:aref (rontolisp:with-arena () (vec:ones n)) 0))
				(rontolisp:wasm-export 's :params '(:int) :returns :int)
				(rontolisp:wasm-export 'str :params '() :returns :string)
				(rontolisp:wasm-export 'v :params '(:int) :returns :float)
				""");
		assertThat(sections(module)).doesNotContainKey(2);
		assertThat(exportNames(Objects.requireNonNull(sections(module).get(7)))).contains("s", "str", "v");
	}

	@Test
	void withArenaOnAMemorylessModuleIsAPlainProgn() {
		// No linear memory at all (pure numeric): there is no allocator to mark/reset,
		// so with-arena is a plain progn and the module stays memoryless.
		byte[] module = compile("""
				(defun f (n) (rontolisp:with-arena () (* n n)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""");
		assertThat(sections(module)).doesNotContainKey(2).doesNotContainKey(5);
	}

	@Test
	void withArenaRejectsANonEmptyOptionList() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) (rontolisp:with-arena (:size 10) n))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("empty option list");
	}

	// --- minimal binary parsing helpers ------------------------------------------------

	// Whether the byte array contains the given (unsigned) byte sequence anywhere. Used
	// to
	// confirm a specific SIMD opcode (SIMD_PREFIX 0xFD + a sub-opcode) is emitted.
	private static boolean containsSequence(byte[] haystack, int... needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if ((haystack[i + j] & 0xFF) != (needle[j] & 0xFF)) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	// All payloads of the given section id, in order. A printing component carries
	// several sections of one id (four core-module sections, interleaved alias/canon/
	// instance sections), which the map-shaped sections() helper cannot represent.
	private static List<byte[]> sectionPayloads(byte[] binary, int sectionId) {
		List<byte[]> result = new ArrayList<>();
		int[] p = { 8 };
		while (p[0] < binary.length) {
			int id = binary[p[0]++] & 0xFF;
			int size = readUleb(binary, p);
			byte[] payload = new byte[size];
			System.arraycopy(binary, p[0], payload, 0, size);
			p[0] += size;
			if (id == sectionId) {
				result.add(payload);
			}
		}
		return result;
	}

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
	// allocator inline, so its only global ops are the heap reset.
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

	// --- packed double-float vectors (F64VEC) ------------------------------------------

	@Test
	void packedFloatVectorUsesLinearMemoryWithScalarTypesOnly() {
		// A #d(...) literal + aref: the vector lives in linear memory (a memory section
		// is
		// present, id 5) but every type is still a plain scalar func type -- no wasm-GC
		// struct/array/i31/eqref and no imports.
		byte[] module = compile("""
				(defun third (i) (aref #d(10.0 20.0 30.0 40.0) i))
				(rontolisp:wasm-export 'third :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("third");
	}

	@Test
	void makeArrayDoubleFloatAndSetfArefCompileToAScalarModule() {
		// make-array :element-type 'double-float + (setf (aref ...)) + length: still a
		// plain MVP module with a memory section and scalar-only func types.
		byte[] module = compile("""
				(defun build (n x)
				  (let ((v (make-array n :element-type 'double-float :initial-element 0.0)))
				    (setf (aref v 0) x)
				    (+ (aref v 0) (length v))))
				(rontolisp:wasm-export 'build :params '(:int :float) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("build");
	}

	@Test
	void rank2FloatLiteralIsAClearCompileError() {
		assertThatThrownBy(() -> compile("""
				(defun f () (aref #d((1.0 2.0) (3.0 4.0)) 0))
				(rontolisp:wasm-export 'f :params '() :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("multi-dimensional #d(...) literal (rank 2)")
			.hasMessageContaining("only a rank-1");
	}

	@Test
	void rank3MakeArrayIsAClearCompileError() {
		// Rank-2 is the packed matrix layout; rank >= 3 still has no packed
		// layout on this backend.
		assertThatThrownBy(() -> compile("""
				(defun f () (aref (make-array '(2 3 4) :element-type 'double-float) 0))
				(rontolisp:wasm-export 'f :params '() :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rank-3 make-array")
			.hasMessageContaining("rank-1 (a vector) or rank-2 (a matrix)");
	}

	@Test
	void rank2MakeArrayCompilesToThePackedMatrixLayout() {
		// (make-array (list d n)) builds the [rows][cols][data] packed matrix:
		// two-subscript aref/setf and a flat row-major-aref compile to a plain scalar
		// module -- linear memory, scalar func types only, no 0xFD SIMD opcode.
		byte[] module = compile("""
				(defun f (d)
				  (let ((w (make-array (list d 3) :element-type 'double-float :initial-element 1.0)))
				    (setf (aref w 1 2) 5.0)
				    (+ (aref w 1 2) (row-major-aref w 5))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(sections.get(5)).as("memory section (the matrix lives in linear memory)").isNotNull();
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix in the scalar module").isFalse();
		// A quoted literal dimension list builds the same layout (the single-float width
		// included).
		byte[] quoted = compile("""
				(defun f ()
				  (let ((w (make-array '(2 3) :element-type 'single-float :initial-element 2.0)))
				    (aref w 1 1)))
				(rontolisp:wasm-export 'f :params '() :returns :float)
				""");
		assertScalarFuncTypes(Objects.requireNonNull(sections(quoted).get(1)));
	}

	@Test
	void makeArrayWithoutDoubleFloatElementTypeIsAClearCompileError() {
		// The scalar backend has no general (boxed) array type, so a make-array without
		// :element-type 'double-float cannot be represented.
		assertThatThrownBy(() -> compile("""
				(defun f (n) (aref (make-array n) 0))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("only supported with :element-type")
			.hasMessageContaining("double-float");
	}

	@Test
	void makeArrayWithFillPointerOrAdjustableIsAClearCompileError() {
		assertThatThrownBy(() -> compile("""
				(defun f (n) (aref (make-array n :element-type 'double-float :adjustable t) 0))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":fill-pointer / :adjustable / :displaced-to");
	}

	@Test
	void simdReductionKernelsEmitRealV128Instructions() {
		// With --simd, vec:dot lowers to native fixed-width SIMD over the packed vector:
		// the
		// code section carries the SIMD prefix (0xFD) with the f64x2 reduction ops (splat
		// +
		// a horizontal extract_lane fold), not a plain scalar loop. The module stays a
		// plain
		// MVP module: a memory section, scalar-only func types, no wasm-GC and no
		// imports.
		byte[] module = compileSimd("""
				(defun dot (n) (let ((v (vec:arange n))) (vec:dot v v)))
				(rontolisp:wasm-export 'dot :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("dot");
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xFD, 0x14)).as("f64x2.splat (0xFD 0x14)").isTrue();
		assertThat(containsSequence(code, 0xFD, 0x21)).as("f64x2.extract_lane (0xFD 0x21)").isTrue();
	}

	@Test
	void simdElementwiseAndConstructorKernelsCompileToAPlainMvpModule() {
		// With --simd, zeros/ones/arange/add/scale/sum/mean/norm all lower to the packed
		// vector + v128 SIMD and stay a plain MVP module (no wasm-GC types, no imports).
		// The
		// element-wise kernels' lane loop emits v128.store (0xFD 0x0B).
		byte[] module = compileSimd("""
				(defun go (n)
				  (let* ((a (vec:arange n))
				         (b (vec:scale (vec:ones n) 2.0))
				         (c (vec:add a b)))
				    (+ (vec:sum c) (vec:mean a) (vec:norm b))))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("go");
		assertThat(containsSequence(Objects.requireNonNull(sections.get(10)), 0xFD, 0x0B)).as("v128.store (0xFD 0x0B)")
			.isTrue();
	}

	@Test
	void theQuotientKernelAndTheOperatorAliasesLowerNativelyOnBothNoGcModes() {
		// --no-gc never splices vec.lisp: every vec: name is intercepted here, so an
		// un-wired name is a hard compile error rather than a slow fallback. vec:div,
		// vec:div-into and the four CL operator spellings must therefore lower like the
		// kernels they alias, in the scalar AND the v128 lowering alike.
		String source = """
				(defun go (n)
				  (let* ((a (vec:arange n))
				         (b (vec:ones n))
				         (c (vec:div-into (vec:zeros n) (vec:div a b) b)))
				    (vec:sum (vec:/ (vec:* (vec:+ a b) b) (vec:- c b)))))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""";
		byte[] scalar = compile(source);
		assertThat(containsSequence(Objects.requireNonNull(sections(scalar).get(10)), 0xA3))
			.as("f64.div (0xA3) in the scalar lowering")
			.isTrue();
		byte[] simd = compileSimd(source);
		assertThat(containsSequence(Objects.requireNonNull(sections(simd).get(10)), 0xFD, 0xF3))
			.as("f64x2.div (0xFD 0xF3) in the v128 lowering")
			.isTrue();
	}

	@Test
	void noSimdVecKernelsLowerToScalarLoopsWithNoV128() {
		// Without --simd (the default --no-gc), the SAME vec: program lowers to plain
		// scalar
		// linear-memory loops: NO 0xFD SIMD opcode anywhere (runs on an MVP runtime
		// lacking
		// the SIMD proposal), yet it stays a plain module with a memory section and
		// scalar-only func types. The element-wise and reduction kernels use
		// f64.load/store
		// (0x2B / 0x39) and f64.add/mul rather than v128, over the byte-identical
		// [count][data] block.
		String source = """
				(defun go (n)
				  (let* ((a (vec:arange n))
				         (b (vec:scale (vec:ones n) 2.0))
				         (c (vec:add a b)))
				    (+ (vec:sum c) (vec:dot a b) (vec:mean a) (vec:norm b))))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""";
		byte[] module = compile(source);
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix (0xFD) in the scalar --no-gc module").isFalse();
		assertThat(containsSequence(code, 0x2B, 0x00, 0x00)).as("f64.load (0x2B) reads packed elements").isTrue();
		assertThat(containsSequence(code, 0x39, 0x00, 0x00)).as("f64.store (0x39) writes packed elements").isTrue();
		// The v128 build of the very same source DOES carry 0xFD -- the two differ only
		// in
		// the loop body, so both are valid lowerings of one program.
		assertThat(containsSequence(Objects.requireNonNull(sections(compileSimd(source)).get(10)), 0xFD))
			.as("the --simd build of the same source does use v128")
			.isTrue();
	}

	// --- destination-passing kernels -------------------------------------------------

	@Test
	void intoKernelsCallTheBumpAllocatorOnlyForTheConstructors() {
		// The whole point of -into: the kernel writes into the caller's block instead of
		// bump-allocating a fresh one. Both programs construct two vectors (2 __alloc
		// calls); the allocating vec:add adds a third, vec:add-into adds none. Asserted
		// on
		// both lowerings, since allocVec is emitted outside the scalar/v128 branch.
		String into = """
				(defun f (n)
				  (let ((o (vec:zeros n)) (a (vec:ones n)))
				    (vec:sum (vec:add-into o a a))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		String alloc = into.replace("(vec:add-into o a a)", "(vec:add a a)");
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(allocCallCount(simd ? compileSimd(into) : compile(into))).as("vec:add-into, simd=%s", simd)
				.isEqualTo(2);
			assertThat(allocCallCount(simd ? compileSimd(alloc) : compile(alloc))).as("vec:add, simd=%s", simd)
				.isEqualTo(3);
		}
	}

	@Test
	void scaleIntoAlsoSkipsTheBumpAllocator() {
		String into = """
				(defun f (n)
				  (let ((o (vec:zeros n)) (a (vec:ones n)))
				    (vec:sum (vec:scale-into o a 2.0))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		assertThat(allocCallCount(compileSimd(into))).isEqualTo(2);
		assertThat(allocCallCount(compileSimd(into.replace("(vec:scale-into o a 2.0)", "(vec:scale a 2.0)"))))
			.isEqualTo(3);
	}

	@Test
	void simdIntoKernelsEmitRealV128InstructionsAndTheScalarLoweringEmitsNone() {
		// Same scalar/v128 seam as the allocating siblings: --simd emits v128.store
		// (0xFD 0x0B), the default emits no 0xFD at all and drives f64.load/store.
		String source = """
				(defun go (n)
				  (let ((o (vec:zeros n)) (a (vec:arange n)) (b (vec:ones n)))
				    (vec:scale-into o (vec:mul-into o (vec:sub-into o (vec:add-into o a b) b) a) 2.0)
				    (vec:sum o)))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""";
		byte[] v128 = Objects.requireNonNull(sections(compileSimd(source)).get(10));
		assertThat(containsSequence(v128, 0xFD, 0x0B)).as("v128.store (0xFD 0x0B)").isTrue();
		byte[] scalar = Objects.requireNonNull(sections(compile(source)).get(10));
		assertThat(containsSequence(scalar, 0xFD)).as("no SIMD prefix in the scalar --no-gc -into lowering").isFalse();
		assertThat(containsSequence(scalar, 0x2B, 0x00, 0x00)).as("f64.load (0x2B)").isTrue();
		assertThat(containsSequence(scalar, 0x39, 0x00, 0x00)).as("f64.store (0x39)").isTrue();
	}

	@Test
	void singleFloatIntoKernelsUseTheF32StrideAndOpcodes() {
		String source = """
				(defun go (n)
				  (let ((o (vec:zeros n :element-type 'single-float)) (a (vec:arange n :element-type 'single-float)))
				    (vec:sum (vec:add-into o a a))))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""";
		byte[] scalar = Objects.requireNonNull(sections(compile(source)).get(10));
		assertThat(containsSequence(scalar, 0x2A, 0x00, 0x00)).as("f32.load (0x2A)").isTrue();
		assertThat(containsSequence(scalar, 0x38, 0x00, 0x00)).as("f32.store (0x38)").isTrue();
		assertThat(allocCallCount(compile(source))).as("only the two constructors allocate").isEqualTo(2);
	}

	// --- element-wise unary ufuncs ---------------------------------------------------

	@Test
	void unaryUfuncsEmitV128UnderSimdAndScalarLoopsByDefault() {
		// sqrt/abs/square/negative/reciprocal (+ -into) lower to whole v128 groups under
		// --simd (f64x2.sqrt 0xFD 0xEF among them) and to plain f64 loops by default (no
		// 0xFD at all). exp / sign lower as element loops in both modes (pinned below).
		String source = """
				(defun go (n)
				  (let ((o (vec:zeros n)) (v (vec:arange n)))
				    (vec:sqrt-into o (vec:square v))
				    (vec:negative-into o o)
				    (vec:abs-into o o)
				    (vec:reciprocal-into o (vec:add o (vec:ones n)))
				    (+ (vec:sum o) (vec:sum (vec:sqrt (vec:abs (vec:negative (vec:reciprocal (vec:square v)))))))))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""";
		byte[] v128 = Objects.requireNonNull(sections(compileSimd(source)).get(10));
		assertThat(containsSequence(v128, 0xFD, 0xEF)).as("f64x2.sqrt (0xFD 0xEF)").isTrue();
		assertThat(containsSequence(v128, 0xFD, 0xEC)).as("f64x2.abs (0xFD 0xEC)").isTrue();
		assertThat(containsSequence(v128, 0xFD, 0xED)).as("f64x2.neg (0xFD 0xED)").isTrue();
		byte[] scalar = Objects.requireNonNull(sections(compile(source)).get(10));
		assertThat(containsSequence(scalar, 0xFD)).as("no SIMD prefix in the scalar --no-gc unary lowering").isFalse();
		assertThat(containsSequence(scalar, 0x9F)).as("f64.sqrt (0x9F)").isTrue();
		assertThat(containsSequence(scalar, 0x99)).as("f64.abs (0x99)").isTrue();
		assertThat(containsSequence(scalar, 0x9A)).as("f64.neg (0x9A)").isTrue();
	}

	@Test
	void singleFloatUnaryUfuncsUseTheF32Opcodes() {
		String source = """
				(defun go (n)
				  (let ((v (vec:arange n :element-type 'single-float)))
				    (vec:sum (vec:sqrt (vec:abs (vec:negative v))))))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""";
		byte[] scalar = Objects.requireNonNull(sections(compile(source)).get(10));
		assertThat(containsSequence(scalar, 0x91)).as("f32.sqrt (0x91)").isTrue();
		assertThat(containsSequence(scalar, 0x8B)).as("f32.abs (0x8B)").isTrue();
		assertThat(containsSequence(scalar, 0x8C)).as("f32.neg (0x8C)").isTrue();
		byte[] v128 = Objects.requireNonNull(sections(compileSimd(source)).get(10));
		assertThat(containsSequence(v128, 0xFD, 0xE3)).as("f32x4.sqrt (0xFD 0xE3)").isTrue();
	}

	@Test
	void unaryIntoKernelsSkipTheBumpAllocator() {
		String into = """
				(defun f (n)
				  (let ((o (vec:zeros n)) (a (vec:ones n)))
				    (vec:sum (vec:sqrt-into o a))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		String alloc = into.replace("(vec:sqrt-into o a)", "(vec:sqrt a)");
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(allocCallCount(simd ? compileSimd(into) : compile(into))).as("vec:sqrt-into, simd=%s", simd)
				.isEqualTo(2);
			assertThat(allocCallCount(simd ? compileSimd(alloc) : compile(alloc))).as("vec:sqrt, simd=%s", simd)
				.isEqualTo(3);
		}
	}

	@Test
	void expAndSignLowerNativelyOnNoGc() {
		// vec:exp / vec:sign (and -into) reuse the
		// GC backend's raw-f64 emitters (WasmVecSimdRuntimeBuilder.emitExpF64 /
		// emitSignumF64), so BOTH lowerings drive the same scalar element loop -- no
		// 0xFD SIMD opcode even under --simd, and the exp argument-reduction constant
		// (f64.const 1/256, WasmExpCompiler.INV_SCALE) appears in the body. The probe
		// avoids vec:sum (whose --simd lowering IS v128) so 0xFD absence is exp/sign's.
		String source = """
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:exp-into o v)
				    (vec:sign-into o o)
				    (+ (vec:aref (vec:exp v) 0) (vec:aref (vec:sign o) 1))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		int[] invScale = new int[9];
		invScale[0] = 0x44; // f64.const
		long bits = Double.doubleToRawLongBits(WasmExpCompiler.INV_SCALE);
		for (int i = 0; i < 8; i++) {
			invScale[1 + i] = (int) ((bits >>> (8 * i)) & 0xFF);
		}
		for (boolean simd : new boolean[] { false, true }) {
			byte[] code = Objects.requireNonNull(sections(simd ? compileSimd(source) : compile(source)).get(10));
			assertThat(containsSequence(code, invScale)).as("exp INV_SCALE constant, simd=%s", simd).isTrue();
			assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix in the exp/sign lowering, simd=%s", simd)
				.isFalse();
		}
		// -into writes into the caller's block: only the two constructors allocate.
		assertThat(allocCallCount(compile("""
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:sum (vec:sign-into o (vec:exp-into o v)))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				"""))).as("exp-into / sign-into skip the bump allocator").isEqualTo(2);
	}

	@Test
	void logAndTanhLowerNativelyOnNoGc() {
		// vec:log / vec:tanh (and -into) reuse the GC backend's
		// raw-f64 emitters (WasmVecSimdRuntimeBuilder.emitLogF64 / emitTanhF64), so
		// BOTH lowerings drive the same scalar element loop -- no 0xFD SIMD opcode even
		// under --simd, and the log mantissa-normalization constant (f64.const sqrt(2),
		// WasmLogCompiler.SQRT2) appears in the body. The probe avoids vec:sum (whose
		// --simd lowering IS v128) so 0xFD absence is log/tanh's.
		String source = """
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:log-into o v)
				    (vec:tanh-into o o)
				    (+ (vec:aref (vec:log v) 0) (vec:aref (vec:tanh o) 1))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		int[] sqrt2 = new int[9];
		sqrt2[0] = 0x44; // f64.const
		long bits = Double.doubleToRawLongBits(WasmLogCompiler.SQRT2);
		for (int i = 0; i < 8; i++) {
			sqrt2[1 + i] = (int) ((bits >>> (8 * i)) & 0xFF);
		}
		for (boolean simd : new boolean[] { false, true }) {
			byte[] code = Objects.requireNonNull(sections(simd ? compileSimd(source) : compile(source)).get(10));
			assertThat(containsSequence(code, sqrt2)).as("log SQRT2 constant, simd=%s", simd).isTrue();
			assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix in the log/tanh lowering, simd=%s", simd)
				.isFalse();
		}
		// -into writes into the caller's block: only the two constructors allocate.
		assertThat(allocCallCount(compile("""
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:sum (vec:tanh-into o (vec:log-into o v)))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				"""))).as("log-into / tanh-into skip the bump allocator").isEqualTo(2);
	}

	@Test
	void sinCosTanLowerNativelyOnNoGc() {
		// vec:sin / vec:cos / vec:tan (and -into)
		// reuse the GC backend's raw-f64 emitter (WasmVecSimdRuntimeBuilder
		// .emitSinCosF64), so BOTH lowerings drive the same scalar element loop -- no
		// 0xFD SIMD opcode even under --simd, and the Cody-Waite reduction constant
		// (f64.const WasmSinCosCompiler.PIO2_1) appears in the body. The probe avoids
		// vec:sum (whose --simd lowering IS v128) so 0xFD absence is sin/cos/tan's.
		String source = """
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:sin-into o v)
				    (vec:cos-into o o)
				    (+ (vec:aref (vec:sin v) 0) (vec:aref (vec:cos o) 1) (vec:aref (vec:tan (vec:tan-into o o)) 2))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		int[] pio21 = new int[9];
		pio21[0] = 0x44; // f64.const
		long bits = Double.doubleToRawLongBits(WasmSinCosCompiler.PIO2_1);
		for (int i = 0; i < 8; i++) {
			pio21[1 + i] = (int) ((bits >>> (8 * i)) & 0xFF);
		}
		for (boolean simd : new boolean[] { false, true }) {
			byte[] code = Objects.requireNonNull(sections(simd ? compileSimd(source) : compile(source)).get(10));
			assertThat(containsSequence(code, pio21)).as("Cody-Waite PIO2_1 constant, simd=%s", simd).isTrue();
			assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix in the sin/cos/tan lowering, simd=%s", simd)
				.isFalse();
		}
		// -into writes into the caller's block: only the two constructors allocate.
		assertThat(allocCallCount(compile("""
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:sum (vec:tan-into o (vec:cos-into o (vec:sin-into o v))))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				"""))).as("sin-into / cos-into / tan-into skip the bump allocator").isEqualTo(2);
	}

	@Test
	void arcAndHyperbolicLowerNativelyOnNoGc() {
		// vec:asin / vec:acos / vec:atan / vec:sinh /
		// vec:cosh (and -into) reuse the GC backend's raw-f64 emitters
		// (WasmVecSimdRuntimeBuilder.emitAtanFamilyF64 / emitSinhCoshF64), so BOTH
		// lowerings drive the same scalar element loop -- no 0xFD SIMD opcode even
		// under --simd, and the atan reciprocal-fold constant (f64.const pi/2,
		// WasmAtanCompiler.PI_OVER_2) plus the sinh series constant (f64.const 1/9!,
		// WasmSinhCoshCompiler.SINH_COEFFS[0]) appear in the body. The probe avoids
		// vec:sum (whose --simd lowering IS v128) so 0xFD absence is these ops'.
		String source = """
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:asin-into o v)
				    (vec:acos-into o o)
				    (vec:atan-into o o)
				    (vec:sinh-into o o)
				    (+ (vec:aref (vec:asin v) 0) (vec:aref (vec:acos o) 1) (vec:aref (vec:atan v) 0)
				       (vec:aref (vec:sinh v) 0) (vec:aref (vec:cosh (vec:cosh-into o o)) 2))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		int[] piOver2 = new int[9];
		piOver2[0] = 0x44; // f64.const
		long bits = Double.doubleToRawLongBits(WasmAtanCompiler.PI_OVER_2);
		for (int i = 0; i < 8; i++) {
			piOver2[1 + i] = (int) ((bits >>> (8 * i)) & 0xFF);
		}
		int[] sinhC0 = new int[9];
		sinhC0[0] = 0x44; // f64.const
		long sBits = Double.doubleToRawLongBits(WasmSinhCoshCompiler.SINH_COEFFS[0]);
		for (int i = 0; i < 8; i++) {
			sinhC0[1 + i] = (int) ((sBits >>> (8 * i)) & 0xFF);
		}
		for (boolean simd : new boolean[] { false, true }) {
			byte[] code = Objects.requireNonNull(sections(simd ? compileSimd(source) : compile(source)).get(10));
			assertThat(containsSequence(code, piOver2)).as("atan PI_OVER_2 constant, simd=%s", simd).isTrue();
			assertThat(containsSequence(code, sinhC0)).as("sinh series constant, simd=%s", simd).isTrue();
			assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix in the arc/hyperbolic lowering, simd=%s", simd)
				.isFalse();
		}
		// -into writes into the caller's block: only the two constructors allocate.
		assertThat(allocCallCount(
				compile("""
						(defun f (n)
						  (let ((v (vec:ones n)) (o (vec:zeros n)))
						    (vec:sum (vec:cosh-into o (vec:sinh-into o (vec:atan-into o (vec:acos-into o (vec:asin-into o v))))))))
						(rontolisp:wasm-export 'f :params '(:int) :returns :float)
						""")))
			.as("asin..cosh -into skip the bump allocator")
			.isEqualTo(2);
	}

	@Test
	void comparisonSelectsLowerNativelyOnNoGc() {
		// vec:maximum / vec:minimum / vec:relu / vec:clip (and
		// -into) are strict-comparison selects. Without --simd they are scalar
		// compare+select loops -- the f64.gt/f64.lt + select pairs appear and no 0xFD
		// SIMD opcode does; under --simd, maximum/minimum/relu become gt/lt lane masks
		// + v128.bitselect. The probes avoid vec:sum (whose --simd lowering IS v128)
		// so the 0xFD assertions are these ops' own.
		String selects = """
				(defun f (n)
				  (let ((a (vec:ones n)) (b (vec:zeros n)) (o (vec:zeros n)))
				    (vec:maximum-into o a b)
				    (vec:minimum-into o o a)
				    (vec:relu-into o o)
				    (+ (vec:aref (vec:maximum a b) 0) (vec:aref (vec:minimum a b) 0) (vec:aref (vec:relu o) 0))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		byte[] scalarCode = Objects.requireNonNull(sections(compile(selects)).get(10));
		assertThat(containsSequence(scalarCode, 0xFD)).as("no SIMD prefix in the default lowering").isFalse();
		assertThat(containsSequence(scalarCode, 0x64, 0x1B)).as("f64.gt + select (maximum/relu)").isTrue();
		assertThat(containsSequence(scalarCode, 0x63, 0x1B)).as("f64.lt + select (minimum)").isTrue();
		byte[] simdCode = Objects.requireNonNull(sections(compileSimd(selects)).get(10));
		assertThat(containsSequence(simdCode, 0xFD, 0x4A)).as("f64x2.gt lane mask under --simd").isTrue();
		assertThat(containsSequence(simdCode, 0xFD, 0x49)).as("f64x2.lt lane mask under --simd").isTrue();
		assertThat(containsSequence(simdCode, 0xFD, 0x52)).as("v128.bitselect under --simd").isTrue();
		// clip drives the same widened scalar element loop in BOTH modes (like exp).
		String clip = """
				(defun f (n)
				  (let ((v (vec:ones n)) (o (vec:zeros n)))
				    (vec:clip-into o v -1.0 1.0)
				    (vec:aref (vec:clip o 0.0 2.0) 0)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			byte[] code = Objects.requireNonNull(sections(simd ? compileSimd(clip) : compile(clip)).get(10));
			assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix in the clip lowering, simd=%s", simd).isFalse();
			assertThat(containsSequence(code, 0x64, 0x1B)).as("clip's gt select, simd=%s", simd).isTrue();
			assertThat(containsSequence(code, 0x63, 0x1B)).as("clip's lt select, simd=%s", simd).isTrue();
		}
		// -into writes into the caller's block: only the three constructors allocate.
		assertThat(allocCallCount(compile("""
				(defun f (n)
				  (let ((a (vec:ones n)) (b (vec:zeros n)) (o (vec:zeros n)))
				    (vec:maximum-into o a b)
				    (vec:minimum-into o o b)
				    (vec:relu-into o o)
				    (vec:clip-into o o -1.0 1.0)
				    (vec:sum o)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				"""))).as("the comparison-select -into forms skip the bump allocator").isEqualTo(3);
	}

	@Test
	void matvecOnARankOneVectorIsAClearCompileError() {
		// matvec is GEMV over a rank-2 packed matrix; passing a rank-1 vector
		// as W is a clear error, for matvec and matvec-into alike.
		assertThatThrownBy(() -> compile("""
				(defun f (n) (vec:sum (vec:matvec (vec:zeros n) (vec:zeros n))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("vec:matvec")
			.hasMessageContaining("rank-2 packed matrix");
		assertThatThrownBy(() -> compile("""
				(defun f (n) (vec:sum (vec:matvec-into (vec:zeros n) (vec:zeros n) (vec:zeros n))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("vec:matvec-into")
			.hasMessageContaining("rank-2 packed matrix");
	}

	@Test
	void matvecEmitsPerRowV128DotUnderSimdAndScalarLoopsByDefault() {
		// The GEMV kernel runs one dot per matrix row: under --simd the same
		// f64x2 lane loop vec:dot uses (splat accumulator 0xFD 0x14, lane multiply 0xFD
		// 0xF2 (LEB 0xF2 0x01), horizontal extract_lane 0xFD 0x21); without --simd a
		// v128-free scalar loop, so the module stays MVP-clean.
		String doubles = """
				(defun f (n)
				  (let ((w (make-array (list n n) :element-type 'double-float :initial-element 1.0))
				        (x (vec:ones n)))
				    (vec:aref (vec:matvec w x) 0)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		byte[] scalar = Objects.requireNonNull(sections(compile(doubles)).get(10));
		assertThat(containsSequence(scalar, 0xFD)).as("no SIMD prefix in the scalar matvec lowering").isFalse();
		byte[] simd = Objects.requireNonNull(sections(compileSimd(doubles)).get(10));
		assertThat(containsSequence(simd, 0xFD, 0x14)).as("f64x2.splat (per-row accumulator)").isTrue();
		assertThat(containsSequence(simd, 0xFD, 0xF2, 0x01)).as("f64x2.mul (lane multiply-accumulate)").isTrue();
		assertThat(containsSequence(simd, 0xFD, 0x21)).as("f64x2.extract_lane (horizontal fold)").isTrue();
		// A single-float matrix drives the f32x4 dot (four lanes) at the f32 stride.
		String singles = """
				(defun f (n)
				  (let ((w (make-array (list n n) :element-type 'single-float :initial-element 1.0))
				        (x (vec:ones n :element-type 'single-float)))
				    (vec:aref (vec:matvec w x) 0)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""";
		byte[] simdF = Objects.requireNonNull(sections(compileSimd(singles)).get(10));
		assertThat(containsSequence(simdF, 0xFD, 0x13)).as("f32x4.splat (per-row accumulator)").isTrue();
		assertThat(containsSequence(simdF, 0xFD, 0xE6, 0x01)).as("f32x4.mul (lane multiply-accumulate)").isTrue();
		assertThat(containsSequence(simdF, 0xFD, 0x1F)).as("f32x4.extract_lane (horizontal fold)").isTrue();
		assertThat(containsSequence(Objects.requireNonNull(sections(compile(singles)).get(10)), 0xFD))
			.as("no SIMD prefix in the scalar single-float matvec lowering")
			.isFalse();
	}

	@Test
	void matvecIntoSkipsTheBumpAllocatorAndGuardsAliasing() {
		// matvec-into writes into the caller's vector: only the matrix + the two
		// constructors allocate. Its out-aliases-x/w guard is a runtime pointer-equality
		// trap (i32.or; if; unreachable), the --no-gc analog of the other backends'
		// error / ref.eq trap -- out MUST NOT alias x or w (each output element folds
		// over all of x).
		byte[] module = compile("""
				(defun f (n)
				  (let ((w (make-array (list n n) :element-type 'double-float :initial-element 1.0))
				        (x (vec:ones n))
				        (o (vec:zeros n)))
				    (vec:matvec-into o w x)
				    (vec:sum o)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""");
		assertThat(allocCallCount(module)).as("the matrix + two constructors allocate; matvec-into does not")
			.isEqualTo(3);
		byte[] code = Objects.requireNonNull(sections(module).get(10));
		assertThat(containsSequence(code, 0x72, 0x04, 0x40, 0x00)).as("i32.or; if; unreachable alias trap").isTrue();
	}

	@Test
	void matvecWidthMismatchIsATypeError() {
		// x (and out) must be the same width as W -- the vec: fail-fast rule; a f32
		// matrix against a f64 vector is the incompatible-types error, not a silent
		// widening.
		assertThatThrownBy(() -> compile("""
				(defun f (n)
				  (let ((w (make-array (list n n) :element-type 'single-float :initial-element 1.0))
				        (x (vec:ones n)))
				    (vec:aref (vec:matvec w x) 0)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("incompatible types");
	}

	/**
	 * Counts {@code allocVec} sites in the FIRST function body -- the first reachable
	 * defun, which the tests above name {@code f} / {@code go}. Restricted to that body
	 * because the always-emitted {@code __itoa} helper calls the allocator too, so a
	 * whole-code-section count carries a constant helper baseline.
	 *
	 * <p>
	 * Matched on {@code allocVec}'s full trailing instruction sequence
	 * ({@code i32.shl; i32.add; call $__ronto_alloc}) rather than the bare {@code call}:
	 * a two-byte {@code 0x10 <index>} scan hits false positives inside the v128
	 * immediates of a {@code --simd} body. The allocator's function index is read from
	 * the export section (a memory-using module exports it) rather than recomputed.
	 */
	private static int allocCallCount(byte[] module) {
		Map<Integer, byte[]> sections = sections(module);
		int allocIndex = exportedFunctionIndex(Objects.requireNonNull(sections.get(7)), "__ronto_alloc");
		assertThat(allocIndex).as("__ronto_alloc is exported by a memory-using --no-gc module").isNotNegative();
		assertThat(allocIndex).as("a single-byte LEB index keeps the byte scan exact").isLessThan(128);
		byte[] body = functionBodies(Objects.requireNonNull(sections.get(10))).get(0);
		int count = 0;
		for (int i = 0; i + 3 < body.length; i++) {
			boolean allocVec = (body[i] & 0xFF) == 0x74 // i32.shl (count << elemShift)
					&& (body[i + 1] & 0xFF) == 0x6A // i32.add (+ the 4-byte header)
					&& (body[i + 2] & 0xFF) == 0x10 // call
					&& (body[i + 3] & 0xFF) == allocIndex;
			if (allocVec) {
				count++;
			}
		}
		return count;
	}

	/** Splits a code section into its per-function bodies (each a size-prefixed blob). */
	private static List<byte[]> functionBodies(byte[] codeSection) {
		List<byte[]> bodies = new ArrayList<>();
		int[] p = { 0 };
		int count = readUleb(codeSection, p);
		for (int i = 0; i < count; i++) {
			int size = readUleb(codeSection, p);
			bodies.add(Arrays.copyOfRange(codeSection, p[0], p[0] + size));
			p[0] += size;
		}
		return bodies;
	}

	private static int exportedFunctionIndex(byte[] exportSection, String name) {
		int[] p = { 0 };
		int count = readUleb(exportSection, p);
		for (int i = 0; i < count; i++) {
			int len = readUleb(exportSection, p);
			String found = new String(exportSection, p[0], len, StandardCharsets.UTF_8);
			p[0] += len;
			p[0]++; // external kind
			int index = readUleb(exportSection, p);
			if (found.equals(name)) {
				return index;
			}
		}
		return -1;
	}

	@Test
	void simdFromListIsAClearCompileError() {
		// vec:from-list / to-list need Lisp cons lists, which the scalar backend lacks,
		// so
		// they run only on the portable backends via vec.lisp.
		assertThatThrownBy(() -> compile("""
				(defun f () (vec:sum (vec:from-list '(1.0 2.0 3.0))))
				(rontolisp:wasm-export 'f :params '() :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("portable backends only");
	}

	// --- packed single-float vectors (F32VEC) ----------------------------------------

	@Test
	void packedSingleFloatVectorUsesLinearMemoryWithScalarTypesOnly() {
		// A #f(...) single-float literal + aref: the vector lives in linear memory
		// (memory
		// section id 5) with a 4-byte f32 stride, but every boundary type is still a
		// plain
		// scalar func type -- no wasm-GC and no imports. aref reads via f32.load (0x2A)
		// then
		// widens with f64.promote_f32 (0xBB); the literal narrows each constant with
		// f32.demote_f64 (0xB6) and stores via f32.store (0x38).
		byte[] module = compile("""
				(defun third (i) (aref #f(10.0 20.0 30.0 40.0) i))
				(rontolisp:wasm-export 'third :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("third");
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xB6)).as("f32.demote_f64 (0xB6) narrows the literal").isTrue();
		assertThat(containsSequence(code, 0x2A, 0x00, 0x00)).as("f32.load (0x2A) reads an element").isTrue();
		assertThat(containsSequence(code, 0xBB)).as("f64.promote_f32 (0xBB) widens the read").isTrue();
	}

	@Test
	void makeArraySingleFloatAndSetfArefCompileToAScalarModule() {
		// make-array :element-type 'single-float + (setf (aref ...)) + length: a plain
		// MVP
		// module with a memory section and scalar-only func types. The store narrows with
		// f32.demote_f64 (0xB6) into an f32.store (0x38).
		byte[] module = compile("""
				(defun build (n x)
				  (let ((v (make-array n :element-type 'single-float :initial-element 0.0)))
				    (setf (aref v 0) x)
				    (+ (aref v 0) (length v))))
				(rontolisp:wasm-export 'build :params '(:int :float) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		assertThat(exportNames(Objects.requireNonNull(sections.get(7)))).contains("build");
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xB6)).as("f32.demote_f64 (0xB6) narrows the store").isTrue();
		assertThat(containsSequence(code, 0x38, 0x00, 0x00)).as("f32.store (0x38) writes an element").isTrue();
	}

	@Test
	void rank2SingleFloatLiteralIsAClearCompileError() {
		assertThatThrownBy(() -> compile("""
				(defun f () (aref #f((1.0 2.0) (3.0 4.0)) 0))
				(rontolisp:wasm-export 'f :params '() :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("multi-dimensional #f(...) literal (rank 2)")
			.hasMessageContaining("only a rank-1");
	}

	@Test
	void makeArraySingleFloatErrorMessageMentionsBothWidths() {
		// A make-array without a packed element-type still errors, now naming both
		// widths.
		assertThatThrownBy(() -> compile("""
				(defun f (n) (aref (make-array n) 0))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("only supported with :element-type")
			.hasMessageContaining("single-float");
	}

	@Test
	void simdSingleFloatReductionKernelsEmitF32x4Instructions() {
		// vec:dot over #f single-float operands lowers to native f32x4 SIMD: the code
		// section carries the SIMD prefix (0xFD) with the f32x4 reduction ops -- splat
		// (0xFD 0x13) and a four-lane extract_lane fold (0xFD 0x1F) -- NOT the f64x2 pair
		// (0xFD 0x14 / 0xFD 0x21). Still a plain MVP module: memory section, scalar-only
		// func types, no wasm-GC and no imports.
		byte[] module = compileSimd("""
				(defun dot (i)
				  (let ((v #f(1.0 2.0 3.0 4.0 5.0)))
				    (vec:dot v v)))
				(rontolisp:wasm-export 'dot :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xFD, 0x13)).as("f32x4.splat (0xFD 0x13)").isTrue();
		assertThat(containsSequence(code, 0xFD, 0x1F)).as("f32x4.extract_lane (0xFD 0x1F)").isTrue();
		assertThat(containsSequence(code, 0xFD, 0xE6, 0x01)).as("f32x4.mul (0xFD 0xE6)").isTrue();
		assertThat(containsSequence(code, 0xFD, 0x14)).as("no f64x2.splat leaked into the f32 path").isFalse();
	}

	@Test
	void simdSingleFloatElementwiseEmitsF32x4StoreKernel() {
		// vec:add / vec:scale over #f operands emit the f32x4 element-wise lane loop
		// (v128.store 0xFD 0x0B + f32x4.add 0xFD 0xE4 / f32x4.mul 0xFD 0xE6) and stay a
		// plain MVP module. The result width is preserved (a f32 vector out).
		byte[] module = compileSimd("""
				(defun go (i)
				  (let* ((a #f(1.0 2.0 3.0))
				         (b (vec:scale a 2.0))
				         (c (vec:add a b)))
				    (aref c 0)))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xFD, 0x0B)).as("v128.store (0xFD 0x0B)").isTrue();
		assertThat(containsSequence(code, 0xFD, 0xE4, 0x01)).as("f32x4.add (0xFD 0xE4)").isTrue();
		assertThat(containsSequence(code, 0xFD, 0xE6, 0x01)).as("f32x4.mul (0xFD 0xE6) from scale").isTrue();
	}

	@Test
	void noSimdSingleFloatKernelsLowerToScalarF32LoopsWithNoV128() {
		// Without --simd, the same #f (single-float) program lowers to scalar f32 loops:
		// NO
		// 0xFD anywhere, but f32.load (0x2A) / f32.store (0x38) over the byte-identical
		// [count][f32...] block, computing in f32 throughout (the reduction promotes to
		// the
		// f64 boundary on return). Runs on an MVP runtime without the SIMD proposal.
		byte[] module = compile("""
				(defun go (i)
				  (let* ((a #f(1.0 2.0 3.0))
				         (b (vec:scale a 2.0))
				         (c (vec:add a b)))
				    (vec:dot a c)))
				(rontolisp:wasm-export 'go :params '(:int) :returns :float)
				""");
		Map<Integer, byte[]> sections = sections(module);
		assertThat(sections).containsKey(5).doesNotContainKey(2);
		assertScalarFuncTypes(Objects.requireNonNull(sections.get(1)));
		byte[] code = Objects.requireNonNull(sections.get(10));
		assertThat(containsSequence(code, 0xFD)).as("no SIMD prefix (0xFD) in the scalar f32 module").isFalse();
		assertThat(containsSequence(code, 0x2A, 0x00, 0x00)).as("f32.load (0x2A) reads packed f32 elements").isTrue();
		assertThat(containsSequence(code, 0x38, 0x00, 0x00)).as("f32.store (0x38) writes packed f32 elements").isTrue();
	}

	@Test
	void mixedWidthSimdOperandsAreAClearCompileError() {
		// vec: is the fixed-contract package: a single-float and a double-float operand
		// in
		// the same kernel is a genuine type error, not a silent widen.
		assertThatThrownBy(() -> compile("""
				(defun f (n) (aref (vec:add #f(1.0 2.0 3.0) (vec:zeros n)) 0))
				(rontolisp:wasm-export 'f :params '(:int) :returns :float)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("incompatible types");
	}

	// --- --no-gc --component (compact reactor component) ------------------------------

	// Compiles in component mode: the same MVP core module wrapped as a reactor-style
	// component whose scalar exports are typed component-model exports.
	private static byte[] compileComponent(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new NoGcWasmCompiler(OptimizeLevel.NONE, false, true).compile(program);
	}

	private static final String COMPONENT_PROGRAM = """
			(defun sumsquared (a b) (+ (* a a) (* b b)))
			(rontolisp:wasm-export 'sumsquared :params '(:int :int) :returns :int)
			""";

	@Test
	void componentWrapsThePlainCoreModuleVerbatim() {
		// The wrap is a pure post stage: the component preamble (layer 0x01), then core
		// module section 0 carrying the byte-identical non-component module, then the
		// instantiate / alias / type / lift / export wiring -- no import block, no
		// adapter module, no shared-memory module (the compact selling point).
		byte[] plain = compile(COMPONENT_PROGRAM);
		byte[] component = compileComponent(COMPONENT_PROGRAM);
		assertThat(new String(component, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("\0asm");
		assertThat(component[6]).as("component layer byte").isEqualTo((byte) 0x01);
		Map<Integer, byte[]> outer = sections(component);
		// Core-module section (component section id 1) carries the plain module
		// byte-for-byte; no second core module (adapter/mem) exists.
		assertThat(outer.get(1)).isEqualTo(plain);
		// Exactly the reactor wiring: core instance (2), alias (6), type (7), canon (8),
		// export (11) -- and no component/instance import machinery (10, 5, 4, 3).
		assertThat(outer).containsKeys(2, 6, 7, 8, 11);
		assertThat(outer).doesNotContainKey(10).doesNotContainKey(5).doesNotContainKey(4).doesNotContainKey(3);
		// A single-export component of a tiny program stays in the hundreds of bytes.
		assertThat(component.length).as("compact component size").isLessThan(1024);
	}

	@Test
	void componentLongExportsUseS64() {
		// :long crosses the canonical ABI as s64 (VT_S64 = 0x78) -- --no-gc is the one
		// backend where :long is valid, so the component type section must carry it.
		byte[] component = compileComponent("""
				(defun bigmul (a b) (* a b))
				(rontolisp:wasm-export 'bigmul :params '(:long :long) :returns :long)
				""");
		byte[] typeSection = Objects.requireNonNull(sections(component).get(7));
		int s64Count = 0;
		for (byte b : typeSection) {
			if ((b & 0xFF) == 0x78) {
				s64Count++;
			}
		}
		assertThat(s64Count).as("two s64 params + one s64 result").isGreaterThanOrEqualTo(3);
	}

	@Test
	void componentPrintWiresTheMicroAdapter() {
		// A printing program under --no-gc --component (the print micro-adapter) is not
		// a compile error: the wrap prepends the WASI 0.3 stdout import block and wires
		// three fixed core modules -- a funcref-table shim, the bridge implementing the
		// core's single fd_write import over write-via-stream + the async stream/future
		// canon built-ins (parking on a blocking waitable-set.wait, so the exports
		// become ASYNC lifts), and the fixup whose element segment closes the shim
		// indirection. The embedded core module stays byte-identical to the plain
		// --no-gc printing output.
		String program = """
				(defun f (n) (print n) n)
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""";
		// Both sides at OptimizeLevel.NONE: the assertion below is a byte-identity pair,
		// so the two compiles must agree on the level.
		byte[] plain = new NoGcWasmCompiler(OptimizeLevel.NONE, false).compile(LispReader.readAllFromString(program));
		byte[] component = compileComponent(program);
		assertThat(component[6]).as("component layer byte").isEqualTo((byte) 0x01);
		String text = new String(component, StandardCharsets.ISO_8859_1);
		assertThat(text).contains("wasi:cli/types@0.3.0", "wasi:cli/stdout@0.3.0", "write-via-stream");
		assertThat(text).doesNotContain("@0.2.0");
		List<byte[]> coreModules = sectionPayloads(component, 1);
		assertThat(coreModules).as("core + shim + bridge + fixup").hasSize(4);
		assertThat(coreModules.get(0)).isEqualTo(plain);
		// The shim exports the fixup's patch target: the "$imports" funcref table.
		assertThat(new String(coreModules.get(1), StandardCharsets.ISO_8859_1)).contains("$imports", "fd_write");
		// The export is lifted against an ASYNC function type (tag 0x43, params vec,
		// one named p0: s32 param, result s32) -- only an async-typed task may block in
		// the bridge's waitable-set park.
		boolean asyncFuncType = false;
		for (byte[] typeSection : sectionPayloads(component, 7)) {
			if (containsSequence(typeSection, 0x43, 0x01, 0x02, 'p', '0', 0x7a)) {
				asyncFuncType = true;
			}
		}
		assertThat(asyncFuncType).as("async function type for the printing export").isTrue();
		// The fixed machinery adds O(hundreds of bytes) to the scalar baseline: the
		// whole printing component of a tiny program stays around 2 KB.
		assertThat(component.length).as("printing component size").isLessThan(2560);
	}

	@Test
	void componentPrintFreeProgramsCarryNoneOfThePrintMachinery() {
		// The micro-adapter is gated on the fd_write import (mem.printUsed): a
		// print-free component keeps the single embedded core module and no import
		// section (componentWrapsThePlainCoreModuleVerbatim pins the full adapter-free
		// shape; this pins the gate from the print side).
		byte[] component = compileComponent(COMPONENT_PROGRAM);
		assertThat(sectionPayloads(component, 1)).hasSize(1);
		assertThat(new String(component, StandardCharsets.ISO_8859_1)).doesNotContain("wasi:cli/stdout@0.3.0");
	}

	@Test
	void componentPrintComposesWithStringExports() {
		// Print + :string in one component: the canonical string ABI
		// lifts over the core's own memory, which the print wiring has already aliased
		// as core memory 0 -- so exactly ONE memory alias exists, and the core module
		// carries the cabi_* exports next to its fd_write import.
		String program = """
				(defun shout (s) (print "in") (concatenate 'string s "!"))
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				""";
		byte[] component = compileComponent(program);
		List<byte[]> coreModules = sectionPayloads(component, 1);
		assertThat(coreModules).hasSize(4);
		byte[] core = coreModules.get(0);
		assertThat(exportNames(Objects.requireNonNull(sections(core).get(7)))).contains("shout", "memory",
				"cabi_realloc", "cabi_post_i32");
		// An alias-core-memory entry is [00 02] (sort core memory) [01] (instance
		// export) [01] (the core instance) then the name "memory"; the string lift
		// reuses the print wiring's alias instead of adding a second one.
		int memoryAliases = 0;
		for (byte[] aliasSection : sectionPayloads(component, 6)) {
			if (containsSequence(aliasSection, 0x00, 0x02, 0x01, 0x01, 0x06, 'm', 'e', 'm', 'o', 'r', 'y')) {
				memoryAliases++;
			}
		}
		assertThat(memoryAliases).as("exactly one core-memory alias section hit").isEqualTo(1);
		// The string-involving lift keeps its four canonical options: (memory 0)
		// (realloc ...) utf8 (post-return ...).
		boolean stringLift = false;
		for (byte[] canonSection : sectionPayloads(component, 8)) {
			if (containsSequence(canonSection, 0x04, 0x03, 0x00, 0x04)) {
				stringLift = true;
			}
		}
		assertThat(stringLift).as("canonical string-lift options present").isTrue();
	}

	@Test
	void componentStringExportAppendsTheCanonicalStringAbi() {
		// A :string boundary under --component appends the canonical
		// string ABI to the core module: cabi_realloc (the host lowers string arguments
		// through it), the shared cabi_post_i32 post-return, and a retptr shim exported
		// under the export name (the canonical ABI caps flat results at one, so a
		// :string result crosses as a single i32 pointing at an 8-byte (ptr,len)
		// record, not the wrapper's two values). The component lifts with the four
		// canonical options in wasm-tools order: (memory 0) (realloc 0) utf8
		// (post-return 1), then type index 0.
		byte[] component = compileComponent("""
				(defun shout (s) (concatenate 'string s "!"))
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				""");
		byte[] core = Objects.requireNonNull(sections(component).get(1));
		Map<Integer, byte[]> coreSections = sections(core);
		byte[] exports = Objects.requireNonNull(coreSections.get(7));
		assertThat(exportNames(exports)).contains("shout", "memory", "cabi_realloc", "cabi_post_i32");
		// The post-return pops the bump heap back to its base so a resident instance
		// stays flat: [locals=0][i32.const heapBase][global.set 0][end].
		byte[] code = Objects.requireNonNull(coreSections.get(10));
		byte[] post = functionBody(code, exportedFuncIndex(exports, "cabi_post_i32"));
		assertThat(post[0]).as("no locals").isEqualTo((byte) 0x00);
		assertThat(post[1]).as("i32.const").isEqualTo((byte) 0x41);
		assertThat(post[post.length - 3]).as("global.set").isEqualTo((byte) 0x24);
		assertThat(post[post.length - 2]).as("global 0").isEqualTo((byte) 0x00);
		// cabi_realloc delegates to the bump allocator: [locals=0][local.get 3]
		// [call __ronto_alloc][end].
		byte[] realloc = functionBody(code, exportedFuncIndex(exports, "cabi_realloc"));
		assertThat(realloc[0]).isEqualTo((byte) 0x00);
		assertThat(realloc[1]).isEqualTo((byte) 0x20);
		assertThat(realloc[2]).isEqualTo((byte) 0x03);
		assertThat(realloc[3]).isEqualTo((byte) 0x10); // call
		// The component type section carries the string valtype (0x73) and the canon
		// section the options vec (04: memory 03 00, realloc 04 00, utf8 00,
		// post-return 05 01).
		byte[] componentTypes = Objects.requireNonNull(sections(component).get(7));
		assertThat(containsSequence(componentTypes, 0x73)).as("string valtype").isTrue();
		byte[] canon = Objects.requireNonNull(sections(component).get(8));
		assertThat(containsSequence(canon, 0x04, 0x03, 0x00, 0x04, 0x00, 0x00, 0x05, 0x01))
			.as("canonical string-lift options")
			.isTrue();
	}

	@Test
	void componentSharesOnePostReturnPerFlatResultSignature() {
		// One cabi_post_* per flat-result signature, shared across exports: a :string
		// result and an :int result both flatten to i32 (one cabi_post_i32), :float to
		// f64, an omitted result to none -- and only one cabi_realloc regardless of how
		// many exports use strings. A scalar export in the same module lifts optionless
		// and adds nothing.
		byte[] component = compileComponent("""
				(defun shout (s) (concatenate 'string s "!"))
				(defun count-len (s) (length s))
				(defun ratio (s) (* 1.5 (length s)))
				(defun consume (s) (length s))
				(defun scalar-only (a b) (+ a b))
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				(rontolisp:wasm-export 'count-len :params '(:string) :returns :int)
				(rontolisp:wasm-export 'ratio :params '(:string) :returns :float)
				(rontolisp:wasm-export 'consume :params '(:string))
				(rontolisp:wasm-export 'scalar-only :params '(:int :int) :returns :int)
				""");
		byte[] core = Objects.requireNonNull(sections(component).get(1));
		List<String> names = exportNames(Objects.requireNonNull(sections(core).get(7)));
		assertThat(names).contains("cabi_realloc", "cabi_post_i32", "cabi_post_f64", "cabi_post_void");
		assertThat(names.stream().filter("cabi_realloc"::equals)).hasSize(1);
		assertThat(names.stream().filter("cabi_post_i32"::equals)).as("shared by shout and count-len").hasSize(1);
	}

	@Test
	void componentWithoutStringExportsOmitsTheStringAbi() {
		// The string ABI is gated on a :string boundary: a scalar-only component's core
		// module carries no cabi_* exports (and componentWrapsThePlainCoreModuleVerbatim
		// pins it byte-identical to the non-component output).
		byte[] component = compileComponent(COMPONENT_PROGRAM);
		byte[] core = Objects.requireNonNull(sections(component).get(1));
		assertThat(exportNames(Objects.requireNonNull(sections(core).get(7)))).doesNotContain("cabi_realloc",
				"cabi_post_i32", "cabi_post_void");
	}

	@Test
	void componentRejectsNonKebabName() {
		// Component-model export names must match the label grammar; the same rule as
		// the GC component path (rename with :as).
		assertThatThrownBy(() -> compileComponent("""
				(defun sum_squared (a) a)
				(rontolisp:wasm-export 'sum_squared :params '(:int) :returns :int)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("not a valid component-model export name");
	}

	@Test
	void asyncAwaitSurfaceIsRejected() {
		// no futures, no suspension, no boxed values to represent them
		assertThatThrownBy(() -> compile("""
				(rontolisp:async-defun f () 1)
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:ASYNC-DEFUN is not supported with --no-gc");
		assertThatThrownBy(() -> compile("""
				(rontolisp:async (defun f () 1))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:ASYNC is not supported with --no-gc");
		assertThatThrownBy(() -> compile("""
				(defun f () (rontolisp:await 1))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:AWAIT is not supported with --no-gc");
		assertThatThrownBy(() -> compile("""
				(defun f () (rontolisp:make-stream))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:MAKE-STREAM is not supported with --no-gc");
		assertThatThrownBy(() -> compile("""
				(defun f () (rontolisp:wait-for 10))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:WAIT-FOR is not supported with --no-gc");
		// The future-as-value combinators are rejected BY NAME (not the downstream
		// async-lambda the prelude splice would inject): the diagnostic points at the
		// operator the user's program actually mentions.
		assertThatThrownBy(() -> compile("""
				(defun f () (rontolisp:then 1 #'identity))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:THEN is not supported with --no-gc");
		assertThatThrownBy(() -> compile("""
				(defun f () (rontolisp:then* 1 #'identity))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:THEN* is not supported with --no-gc");
		assertThatThrownBy(() -> compile("""
				(defun f () (rontolisp:catch 1 (lambda (c) c)))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:CATCH is not supported with --no-gc");
		assertThatThrownBy(() -> compile("""
				(defun f () (rontolisp:finally 1 (lambda () nil)))
				(rontolisp:wasm-export 'f :returns :long)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("RONTOLISP:FINALLY is not supported with --no-gc");
	}

	@Test
	void componentRejectsAsyncExport() {
		// :async is not a user-level knob here: a printing program's exports become
		// async lifts automatically and every other I/O op is a compile error, so an
		// explicit :async request is a clear error rather than a silently-ignored
		// option.
		assertThatThrownBy(() -> compileComponent("""
				(defun add2 (a b) (+ a b))
				(rontolisp:wasm-export 'add2 :params '(:long :long) :returns :long :async t)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":async is not supported with --no-gc --component");
	}

	@Test
	void componentInternalStringUseIsAllowedWhenTheBoundaryIsScalar() {
		// Internal strings (the module-private linear memory) are fine under the wrap;
		// only the :string BOUNDARY type is Tier 2. The core module keeps its memory
		// section; the component still has no import machinery.
		byte[] component = compileComponent("""
				(defun f (n) (length (princ-to-string n)))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""");
		assertThat(new String(component, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("\0asm");
		assertThat(component[6]).isEqualTo((byte) 0x01);
	}

	@Test
	void componentCompileRecordsTheWitText() {
		// The CLI's --emit-wit output for the adapter-free reactor: an import-free world
		// of
		// just the typed exports (:long lifts as s64 here only).
		NoGcWasmCompiler compiler = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true);
		assertThat(compiler.componentWit()).isNull();
		compiler.compile(LispReader.readAllFromString("""
				(defun big-add (a b) (+ a b))
				(defun evenp2 (n) (= 0 (mod n 2)))
				(rontolisp:wasm-export 'big-add :params '(:long :long) :returns :long)
				(rontolisp:wasm-export 'evenp2 :params '(:int) :returns :bool)
				"""));
		assertThat(compiler.componentWit()).isEqualTo("""
				package root:component;

				world root {
				  export big-add: func(p0: s64, p1: s64) -> s64;
				  export evenp2: func(p0: s32) -> bool;
				}
				""");
	}

	@Test
	void componentWitOfAPrintingProgramCarriesTheStdioImports() {
		// The print micro-adapter's WASI 0.3 stdout surface shows up as world imports
		// (plus their package definitions), separated from the exports by one blank
		// line the way wasm-tools prints it -- and the exports say `async func`,
		// because a printing program's exports are async lifts.
		NoGcWasmCompiler compiler = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true);
		compiler.compile(LispReader.readAllFromString("""
				(defun hello () (print "hi"))
				(rontolisp:wasm-export 'hello)
				"""));
		assertThat(compiler.componentWit()).contains("""
				world root {
				  import wasi:cli/types@0.3.0;
				  import wasi:cli/stdout@0.3.0;

				  export hello: async func();
				}
				""").contains("package wasi:cli@0.3.0 {");
	}

	@Test
	void nonComponentCompileRecordsNoWitText() {
		NoGcWasmCompiler compiler = new NoGcWasmCompiler();
		compiler.compile(LispReader
			.readAllFromString("(defun f (a) a) (rontolisp:wasm-export 'f :params '(:long) :returns :long)"));
		assertThat(compiler.componentWit()).isNull();
	}

	@Test
	void noWasiReplacesTheFdWriteImportWithADiscardingSink() {
		// --no-wasi on a PRINTING program: the single fd_write import becomes an
		// internal sink DEFINED at function index 0 (funcBase stays 1, so every planned
		// index holds), and the module keeps zero imports -- the GC backend's contract
		// (output discarded, nothing traps) applied unchanged. A print-free program
		// never had the import, so the flag is a byte-exact no-op there.
		String printing = """
				(defun show (n) (print n) n)
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				""";
		byte[] noWasi = new NoGcWasmCompiler(OptimizeLevel.NONE, false, false, true)
			.compile(LispReader.readAllFromString(printing));
		assertThat(sections(noWasi)).as("import section").doesNotContainKey(2);
		// The sink body: 0 locals; local.get 3 ; local.get 1 ; i32.load off=4 ;
		// i32.store ; i32.const 0 (the same body the GC backend pins).
		byte[] sink = { 0x00, 0x20, 0x03, 0x20, 0x01, 0x28, 0x02, 0x04, 0x36, 0x02, 0x00, 0x41, 0x00, 0x0b };
		assertThat(containsBytes(noWasi, sink)).as("the fd_write sink body").isTrue();
		// The index shift is unchanged: the exported wrapper sits at the printing
		// build's index, one above the silent build's (the sink occupies index 0 the
		// way the import did).
		byte[] importing = compile(printing);
		assertThat(exportedFuncIndex(Objects.requireNonNull(sections(noWasi).get(7)), "show"))
			.isEqualTo(exportedFuncIndex(Objects.requireNonNull(sections(importing).get(7)), "show"));
		String silent = """
				(defun show (n) n)
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				""";
		assertThat(new NoGcWasmCompiler(OptimizeLevel.NONE, false, false, true)
			.compile(LispReader.readAllFromString(silent))).isEqualTo(compile(silent));
	}

	@Test
	void componentNoWasiPrintingProgramTakesThePrintFreeShape() {
		// --no-gc --no-wasi --component on a PRINTING program: the core carries the
		// sink instead of the import, so the wrap never wires the print micro-adapter
		// -- ONE core module, no wasi:cli/stdout anywhere, and the exports lift SYNC
		// again (the WIT says `func`, not `async func`, over the same empty world as a
		// print-free reactor). A printing program collapses back onto the print-free
		// shape rather than merely losing its imports.
		NoGcWasmCompiler compiler = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true, true);
		byte[] component = compiler.compile(LispReader.readAllFromString("""
				(defun show (n) (print n) (* n 2))
				(rontolisp:wasm-export 'show :params '(:s64) :returns :s64)
				"""));
		assertThat(containsAscii(component, "wasi:cli/stdout")).isFalse();
		assertThat(countCoreModuleSections(component)).isEqualTo(1);
		assertThat(compiler.componentWit()).isEqualTo("""
				package root:component;

				world root {
				  export show: func(p0: s64) -> s64;
				}
				""");
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

	private static boolean containsBytes(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	// The number of core-module sections (component section id 1) in a component.
	private static int countCoreModuleSections(byte[] component) {
		int count = 0;
		int pos = 8;
		while (pos < component.length) {
			int id = component[pos++];
			int size = 0;
			int shift = 0;
			while (true) {
				int b = component[pos++] & 0xFF;
				size |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					break;
				}
				shift += 7;
			}
			if (id == 1) {
				count++;
			}
			pos += size;
		}
		return count;
	}

	@Test
	void theSizeLevelIsADocumentedNoOpOnThisBackend() {
		// Same statement as on the JVM: this lowering is i64-native, so it never emits
		// the boxed/unboxed pair --optimize=size declines on wasm-GC. Accepted, equal,
		// and pinned so the docs cannot quietly go stale.
		List<LispVal> program = LispReader.readAllFromString("""
				(defun rol32f (x s) (logand (logior (ash x s) (ash x (- s 32))) 4294967295))
				(rontolisp:wasm-export 'rol32f :params '(:long :long) :returns :long)
				""");
		byte[] fast = new NoGcWasmCompiler(OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new NoGcWasmCompiler(OptimizeLevel.SIZE).compile(program);
		assertThat(small).isEqualTo(fast);
	}

}
