package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WasmImportCompiler} parsing and for the module-level wiring of
 * {@code (rontolisp:wasm-import ...)} directives: the injected import entries come first
 * in the import section (function indices 0..K-1), every other function reference is
 * shifted, and unsupported modes are rejected. These run without Docker; the end-to-end
 * {@code wasmtime --preload} checks live in {@link WasmLispCompilerIntegrationTest}.
 */
class WasmImportCompilerTest {

	private static WasmImportCompiler.Decl parse(String source) {
		return WasmImportCompiler.parse((LispCons) LispReader.readFromString(source));
	}

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler().compile(program);
	}

	// The UNOPTIMIZED module, for the tests that count import entries or read a function
	// index off the fixed layout: the tree shaker drops an import the program cannot
	// reach and renumbers what survives, so those counts and indices are the shape a
	// build that declined the optimizer has.
	private static byte[] compileUnshaken(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, false, OptimizeLevel.NONE).compile(program);
	}

	private static byte[] compileNoWasi(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, true).compile(program);
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

	@Test
	void parsesDirectiveWithDefaults() {
		WasmImportCompiler.Decl decl = parse("(rontolisp:wasm-import 'draw :params '(:int :int) :returns :void)");
		assertThat(decl.name()).isEqualTo("DRAW");
		assertThat(decl.module()).isEqualTo("env");
		assertThat(decl.field()).isEqualTo("draw");
		assertThat(decl.paramTypes()).containsExactly(BoundaryType.S32, BoundaryType.S32);
		assertThat(decl.returnType()).isEqualTo(BoundaryType.VOID);
	}

	@Test
	void parsesFromAndAsOptions() {
		WasmImportCompiler.Decl decl = parse(
				"(rontolisp:wasm-import 'draw-pixel :from \"gl\" :as \"drawPixel\" :params '(:float) :returns :int)");
		assertThat(decl.name()).isEqualTo("DRAW-PIXEL");
		assertThat(decl.module()).isEqualTo("gl");
		assertThat(decl.field()).isEqualTo("drawPixel");
		assertThat(decl.paramTypes()).containsExactly(BoundaryType.FLOAT);
		assertThat(decl.returnType()).isEqualTo(BoundaryType.S32);
	}

	@Test
	void defaultFieldOfPackageQualifiedNameIsTheUnqualifiedMember() {
		// A directive inside a user package resolves its name to pkg:name; the
		// host-facing import field must default to the bare member name, not the
		// package-qualified spelling.
		WasmImportCompiler.Decl decl = parse("(rontolisp:wasm-import 'gl:enable :params '(:int) :returns :void)");
		assertThat(decl.name()).isEqualTo("GL:ENABLE");
		assertThat(decl.field()).isEqualTo("enable");
		WasmImportCompiler.Decl internal = parse("(rontolisp:wasm-import 'gl::fail :params '(:string))");
		assertThat(internal.name()).isEqualTo("GL::FAIL");
		assertThat(internal.field()).isEqualTo("fail");
	}

	@Test
	void treatsOmittedReturnsAsVoid() {
		assertThat(parse("(rontolisp:wasm-import 'go :params '(:int))").returnType()).isEqualTo(BoundaryType.VOID);
		assertThat(parse("(rontolisp:wasm-import 'go :params '(:int) :returns :void)").returnType())
			.isEqualTo(BoundaryType.VOID);
		assertThat(parse("(rontolisp:wasm-import 'go :params '(:int) :returns nil)").returnType())
			.isEqualTo(BoundaryType.VOID);
	}

	@Test
	void parsesParamNamesWithTheExportSidesDefault() {
		// :param-names are the labels of the imported function's component type (read by
		// the --no-gc --component wrap alone); the default is the export side's p0, p1,
		// ... so a program naming neither side gets one convention, and a count that
		// disagrees with :params is refused at the parse.
		assertThat(
				parse("(rontolisp:wasm-import 'greet :params '(:string :s32) :param-names '(name times))").paramNames())
			.containsExactly("name", "times");
		assertThat(parse("(rontolisp:wasm-import 'greet :params '(:string) :param-names '(\"who\"))").paramNames())
			.containsExactly("who");
		assertThat(parse("(rontolisp:wasm-import 'greet :params '(:string :s32))").paramNames()).containsExactly("p0",
				"p1");
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'greet :params '(:string) :param-names '(a b))"))
			.hasMessageContaining(":param-names has 2 name(s) but :params declares 1");
	}

	@Test
	void parsesAsyncOption() {
		// :async t declares that the host may suspend: the call answers a settled
		// future. The default -- and an explicit nil -- is the plain synchronous
		// wrapper, byte-identical to every pre-:async module.
		assertThat(parse("(rontolisp:wasm-import 'pull :params '(:string) :returns :string :async t)").async())
			.isTrue();
		assertThat(parse("(rontolisp:wasm-import 'pull :params '(:string) :returns :string)").async()).isFalse();
		assertThat(parse("(rontolisp:wasm-import 'pull :params '(:string) :returns :string :async nil)").async())
			.isFalse();
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'pull :params '(:string) :async 1)"))
			.hasMessageContaining(":ASYNC expects t or nil");
	}

	@Test
	void rejectsUnknownTypeDesignator() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'g :params '(:widget))"))
			.hasMessageContaining(":WIDGET");
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'g :params '(:int) :returns :widget)"))
			.hasMessageContaining(":WIDGET");
	}

	@Test
	void parsesBytesAndDerivesTheCallerBufferShape() {
		// :bytes is the byte-transfer type: a parameter crosses as raw (ptr,len); a
		// RESULT follows the caller-passes-the-buffer read(2) shape -- the Lisp
		// signature gains one trailing buffer-vector parameter, the host is called with
		// a trailing (ptr,cap) pair and answers the value's FULL length.
		WasmImportCompiler.Decl pull = parse("(rontolisp:wasm-import 'pull :params '(:int) :returns :bytes)");
		assertThat(pull.paramTypes()).containsExactly(BoundaryType.S32);
		assertThat(pull.returnType()).isEqualTo(BoundaryType.BYTES);
		assertThat(WasmImportCompiler.lispArity(pull)).isEqualTo(2);
		assertThat(WasmImportCompiler.hostParamTypes(pull)).containsExactly(am.ik.wasm.Type.I32, am.ik.wasm.Type.I32,
				am.ik.wasm.Type.I32);
		assertThat(WasmImportCompiler.hostResultTypes(pull)).containsExactly(am.ik.wasm.Type.I32);
		WasmImportCompiler.Decl sink = parse("(rontolisp:wasm-import 'sink :params '(:bytes) :returns :int)");
		assertThat(WasmImportCompiler.lispArity(sink)).isEqualTo(1);
		assertThat(WasmImportCompiler.hostParamTypes(sink)).containsExactly(am.ik.wasm.Type.I32, am.ik.wasm.Type.I32);
		assertThat(WasmImportCompiler.hostResultTypes(sink)).containsExactly(am.ik.wasm.Type.I32);
	}

	@Test
	void bytesImportCompilesToACallableWrapperWithTheBufferArity() {
		// The synthetic defun carries the Lisp arity (declared params + the trailing
		// receive buffer for a :bytes result), so an ordinary call site with the buffer
		// argument compiles; the module carries the import entry like any other.
		byte[] module = compileNoWasi("""
				(rontolisp:wasm-import 'pull :from "host" :params '() :returns :bytes)
				(rontolisp:wasm-import 'sink :from "host" :params '(:bytes) :returns :int)
				(defun probe ()
				  (let ((buf (make-array 3 :element-type '(unsigned-byte 8))))
				    (+ (pull buf) (sink buf))))
				(rontolisp:wasm-export 'probe :params '() :returns :int)
				""");
		List<String[]> imports = functionImports(module);
		assertThat(imports).hasSize(2);
		assertThat(imports.get(0)).containsExactly("host", "pull");
		assertThat(imports.get(1)).containsExactly("host", "sink");
	}

	@Test
	void bytesHelpersRideOnlyABytesDeclaringModule() {
		// The three _bytes_* marshalling helpers (and their one appended signature) are
		// emitted exactly when the designator appears; a module without :bytes keeps its
		// function count -- the gating that preserves byte-identity everywhere else.
		String withoutBytes = """
				(rontolisp:wasm-import 'geta :from "host" :params '() :returns :string)
				(print (geta))
				""";
		String withBytes = """
				(rontolisp:wasm-import 'geta :from "host" :params '() :returns :string)
				(rontolisp:wasm-import 'sink :from "host" :params '(:bytes) :returns :int)
				(print (geta))
				""";
		// The :bytes module adds exactly its own import wrapper (one function) plus the
		// three helpers.
		assertThat(functionCount(compileUnshaken(withBytes)))
			.isEqualTo(functionCount(compileUnshaken(withoutBytes)) + 4);
	}

	// The number of entries in the function section (defined functions, imports
	// excluded).
	private static int functionCount(byte[] module) {
		byte[] section = Objects.requireNonNull(section(module, 3));
		return readU(section, new int[] { 0 });
	}

	@Test
	void twoMemoryTypedParamsStageOnDistinctRegions() {
		// The defect this pins: N :string/:s-expr parameters all staged at the ONE
		// un-advanced HEAP_PTR scratch, so the host saw the LAST argument's bytes under
		// every pointer. With two or more the wrapper advances the scratch past each
		// region (8-aligned, then popped back to the wrapper's mark after the call) --
		// counted here as the align-and-store of HEAP_PTR, once per staged parameter.
		// A single memory-typed parameter has nothing to collide with and keeps the
		// non-advancing scratch, so its module is byte-identical to a build made before
		// this existed. The content is checked against a real host in
		// WasmStringParamBoundaryE2eTest.
		assertThat(countOf(compileNoWasi(importing("'(:string :string)")), HEAP_PTR_ADVANCE)).isEqualTo(2);
		assertThat(countOf(compileNoWasi(importing("'(:string :s-expr)")), HEAP_PTR_ADVANCE)).isEqualTo(2);
		assertThat(countOf(compileNoWasi(importing("'(:string :int :s-expr :string)")), HEAP_PTR_ADVANCE)).isEqualTo(3);
		assertThat(countOf(compileNoWasi(importing("'(:string)")), HEAP_PTR_ADVANCE)).isZero();
		assertThat(countOf(compileNoWasi(importing("'(:string :int)")), HEAP_PTR_ADVANCE)).isZero();
		assertThat(WasmImportCompiler.stagesMemoryParams(parse("(rontolisp:wasm-import 'g :params '(:string))")))
			.isFalse();
		assertThat(WasmImportCompiler
			.stagesMemoryParams(parse("(rontolisp:wasm-import 'g :params '(:string :bytes :string))"))).isTrue();
	}

	// One import of the given parameter list, called from an export so nothing shakes it
	// out. Every parameter is passed the same literal, which the :s-expr designator
	// takes as readily as the :string one.
	private static String importing(String paramTypes) {
		// Each argument fits its declared type: an integer parameter handed a string
		// would be a type error the type-test fold proves at compile time, and the
		// wrapper's code after that unbox -- the stagings this pin counts -- would be
		// dead code, correctly (.kb/wasm-ref-type-fold.md).
		StringBuilder args = new StringBuilder();
		for (String type : paramTypes.replace("'(", "").replace(")", "").trim().split("\\s+")) {
			args.append(type.equals(":int") ? " 1" : " \"s\"");
		}
		return "(rontolisp:wasm-import 'ask :from \"host\" :params " + paramTypes + " :returns :int)\n"
				+ "(defun probe () (ask" + args + "))\n" + "(rontolisp:wasm-export 'probe :params '() :returns :int)\n";
	}

	// i32.const 7; i32.add; i32.const -8; i32.and; i32.store align=2 offset=0 -- the
	// 8-aligned bump of HEAP_PTR past one staged parameter region.
	private static final byte[] HEAP_PTR_ADVANCE = { 0x41, 0x07, 0x6a, 0x41, 0x78, 0x71, 0x36, 0x02, 0x00 };

	private static int countOf(byte[] module, byte[] needle) {
		int count = 0;
		outer: for (int i = 0; i <= module.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (module[i + j] != needle[j]) {
					continue outer;
				}
			}
			count++;
		}
		return count;
	}

	@Test
	void rejectsUnknownOption() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'g :wat 1)")).hasMessageContaining(":WAT");
	}

	@Test
	void injectedImportsComeFirstInTheImportSection() {
		byte[] module = compileUnshaken("""
				(rontolisp:wasm-import 'begin-frame :from "gl" :as "beginFrame" :params '(:int))
				(rontolisp:wasm-import 'draw :from "gl" :params '(:float :float) :returns :int)
				(print (draw 1.0 2.0))
				""");
		List<String[]> imports = functionImports(module);
		// The two host imports occupy function indices 0 and 1, ahead of the fifteen
		// wasi_snapshot_preview1 imports.
		assertThat(imports).hasSize(17);
		assertThat(imports.get(0)).containsExactly("gl", "beginFrame");
		assertThat(imports.get(1)).containsExactly("gl", "draw");
		assertThat(imports.get(2)[0]).isEqualTo("wasi_snapshot_preview1");
	}

	@Test
	void functionExportIndicesShiftPastTheInjectedImports() {
		String source = "(rontolisp:wasm-import 'ping :params '())" + "(print 1)";
		byte[] withImport = compileUnshaken(source);
		byte[] without = compileUnshaken("(print 1)");
		// _start sits at the fixed FUNC_START index; injecting one import shifts the
		// exported index up by exactly one.
		assertThat(exportedFunctionIndex(withImport, "_start")).isEqualTo(exportedFunctionIndex(without, "_start") + 1);
	}

	@Test
	void noWasiModuleImportsOnlyTheHostFunctions() {
		byte[] module = compileNoWasi("""
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(defun add10 (n) (add n 10))
				(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
				""");
		assertThat(containsAscii(module, "wasi_snapshot_preview1")).isFalse();
		List<String[]> imports = functionImports(module);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0)).containsExactly("host", "add");
	}

	@Test
	void hostRandomJoinsTheOrdinalSpaceLastAndIsTheOnlyImportOnItsOwn() {
		// --host-random reaches the host through the same injector, so it costs one
		// import entry and nothing else. It is appended LAST so a program that also
		// declares wasm-imports keeps their ordinals -- and their bytes -- exactly as
		// they were.
		List<LispVal> program = LispReader.readAllFromString("""
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(defun roll (n) (add (random n) 10))
				(rontolisp:wasm-export 'roll :params '(:int) :returns :int)
				""");
		List<String[]> imports = functionImports(
				new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, true).compile(program));
		assertThat(imports).hasSize(2);
		assertThat(imports.get(0)).containsExactly("host", "add");
		assertThat(imports.get(1)).containsExactly("env", "random_get");

		// Alone it is the whole import list; and without the flag the module keeps the
		// zero-import default even though it draws random.
		List<LispVal> plain = LispReader.readAllFromString("""
				(defun roll (n) (random n))
				(rontolisp:wasm-export 'roll :params '(:int) :returns :int)
				""");
		assertThat(functionImports(
				new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, true).compile(plain)))
			.singleElement()
			.satisfies(entry -> assertThat(entry).containsExactly("env", "random_get"));
		assertThat(functionImports(new WasmLispCompiler(false, false, true).compile(plain))).isEmpty();
	}

	@Test
	void underHostRandomTheEntropyApiReachesTheHostAndAnUnusedImportIsStillShaken() {
		// The surviving import IS the proof that rontolisp:random-bytes is un-gated:
		// %random-byte is the only thing this program does, so if it still compiled to
		// the "--no-wasi has no entropy source" call-time error, nothing would call the
		// random_get slot and --optimize would shake the import away with it.
		List<LispVal> entropyOnly = LispReader.readAllFromString("""
				(defun secret () (rontolisp::%random-byte))
				(rontolisp:wasm-export 'secret :params '() :returns :int)
				""");
		assertThat(functionImports(new WasmLispCompiler(false, false, true, OptimizeLevel.DEFAULT, false, false, true)
			.compile(entropyOnly))).singleElement()
			.satisfies(entry -> assertThat(entry).containsExactly("env", "random_get"));

		// And the same shake is what keeps the flag honest for a program that never
		// draws: asking for host entropy costs an import only where entropy is used.
		List<LispVal> noDraw = LispReader.readAllFromString("""
				(defun nothing () 1)
				(rontolisp:wasm-export 'nothing :params '() :returns :int)
				""");
		assertThat(functionImports(
				new WasmLispCompiler(false, false, true, OptimizeLevel.DEFAULT, false, false, true).compile(noDraw)))
			.isEmpty();
	}

	// Mirrors the CLI pre-passes of a --no-wasi --host-fetch build: the HostFetchLibrary
	// splice (the two env imports + the envelope defuns), the reactor transport whose
	// body-source machinery the reply's stream rides, then the JSON library and the
	// prelude picking up the splice's own call sites.
	private static byte[] compileHostFetch(String source) {
		List<LispVal> loaded = am.ik.rontolisp.eval.HostFetchLibrary.process(
				LispReader.readAllFromString(source, am.ik.rontolisp.reader.Features.WASM_REACTOR),
				am.ik.rontolisp.compiler.HostBoundary.STREAMING);
		loaded = am.ik.rontolisp.eval.HttpReactorLibrary.process(loaded);
		loaded = am.ik.rontolisp.eval.HttpServerLibrary.process(loaded, false);
		List<LispVal> program = am.ik.rontolisp.eval.GrayStreamsLibrary.process(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.JsonLibrary.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded))));
		return new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false, true).compile(program);
	}

	@Test
	void hostFetchLowersFetchAtEnvFetchAndAProgramThatNeverFetchesImportsNothing() {
		// The whole point of the flag: rontolisp:fetch COMPILES on a --no-wasi reactor,
		// carried by the host imports the boundary declares -- the head through
		// env.fetch, the reply BODY out of band through env.readResponseBody -- and
		// nothing else.
		byte[] module = compileHostFetch("""
				(rontolisp:async-defun dog ()
				  (let* ((res (rontolisp:await (rontolisp:fetch "https://dog.ceo/api/breeds/image/random")))
				         (body (rontolisp:await (rontolisp:read-all (getf res :body)))))
				    body))
				(defun run () (rontolisp::%future-force (dog)))
				(rontolisp:wasm-export 'run :params '() :returns :string)
				""");
		assertThat(functionImports(module).stream().map(entry -> String.join(".", entry)))
			.containsExactlyInAnyOrder("env.fetch", "env.readResponseBody");

		// And the zero-import contract is untouched for a program that never fetches:
		// asking for a host fetch costs an import only where fetch is used.
		byte[] noFetch = compileHostFetch("""
				(defun run () 1)
				(rontolisp:wasm-export 'run :params '() :returns :int)
				""");
		assertThat(functionImports(noFetch)).isEmpty();
	}

	@Test
	void withoutHostFetchANoWasiFetchNamesTheWayOut() {
		assertThatThrownBy(() -> compileNoWasi("(defun f () (rontolisp:fetch \"https://x\"))"))
			.hasMessageContaining("--host-fetch")
			.hasMessageContaining("env.fetch");
	}

	@Test
	void hostFetchRequiresNoWasiAndRejectsComponent() {
		assertThatThrownBy(
				() -> new WasmLispCompiler(false, false, false, OptimizeLevel.NONE, false, false, false, true))
			.hasMessageContaining("--host-fetch requires --no-wasi");
		assertThatThrownBy(() -> new WasmLispCompiler(false, true, true, OptimizeLevel.NONE, false, false, false, true))
			.hasMessageContaining("--host-fetch cannot be combined with --component");
	}

	@Test
	void stringResultExportsTheAllocator() {
		// A :string result is written into linear memory by the host via __ronto_alloc,
		// so the allocator must be exported even without any memory-typed export.
		byte[] module = compile("""
				(rontolisp:wasm-import 'greet :params '(:int) :returns :string)
				(print (greet 1))
				""");
		assertThat(exportedFunctionIndex(module, "__ronto_alloc")).isNotNegative();
	}

	@Test
	void sexprResultExportsTheAllocatorToo() {
		// The same rule, and it used to be missed: an :s-expr result is host-written
		// bytes exactly like a :string one (the wrapper reads the text back and hands it
		// to the embedded reader), so a module whose ONLY memory-typed boundary is such
		// an import still owes its host the allocator. Without this the host had no
		// __ronto_alloc to write the s-expression into and could not answer at all.
		byte[] module = compile("""
				(rontolisp:wasm-import 'ask :params '() :returns :s-expr)
				(print (ask))
				""");
		assertThat(exportedFunctionIndex(module, "__ronto_alloc")).isNotNegative();
	}

	@Test
	void intOnlyImportDoesNotExportTheAllocator() {
		byte[] module = compile("""
				(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)
				(print (add 1 2))
				""");
		assertThat(containsAscii(module, "__ronto_alloc")).isFalse();
	}

	@Test
	void importIsCallableAsAFirstClassFunction() {
		// #'add and eval both route through the regular defun dispatch machinery; this
		// must compile without errors (behavior is covered by the integration tests).
		byte[] module = compile("""
				(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)
				(print (funcall #'add 1 2))
				(print (eval '(add 3 4)))
				""");
		assertThat(functionImports(module).get(0)).containsExactly("env", "add");
	}

	@Test
	void rejectsNameCollidingWithADefun() {
		assertThatThrownBy(() -> compile(
				"(defun add (a b) (+ a b))" + "(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)"))
			.hasMessageContaining("collides");
	}

	@Test
	void rejectsComponentMode() {
		List<LispVal> program = LispReader
			.readAllFromString("(rontolisp:wasm-import 'add :params '(:int :int) :returns :int) (print (add 1 2))");
		assertThatThrownBy(() -> new WasmLispCompiler(false, true).compile(program))
			.hasMessageContaining("--component");
	}

	@Test
	void theNoGcBackendTakesTheSameDirectiveThroughItsOwnWrappers() {
		// The directive is backend-independent; what differs is the wrapper the backend
		// builds for it. --no-gc used to refuse the form outright, which cost a
		// host-driven module -- exactly the shape --no-gc is for -- about three times
		// its bytes on the GC backend. Its own marshalling lives in NoGcWasmCompiler and
		// is pinned there; here it is enough that the form compiles, and that the
		// vocabulary widens with the house integer (i64 there, i31ref here).
		List<LispVal> program = LispReader.readAllFromString("""
				(rontolisp:wasm-import 'add :params '(:long :long) :returns :long)
				(defun add10 (n) (add n 10))
				(rontolisp:wasm-export 'add10 :params '(:long) :returns :long)
				""");
		assertThat(new NoGcWasmCompiler().compile(program)).isNotEmpty();
		assertThatThrownBy(() -> new WasmLispCompiler(false, false, true).compile(program))
			.hasMessageContaining("type designator :LONG is not supported");
	}

	@Test
	void composesWithTheTreeShaker() {
		// --optimize runs after import injection; the shaken module keeps the used
		// import and stays instantiable (behavior covered by the integration tests).
		List<LispVal> program = LispReader.readAllFromString("""
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(defun add10 (n) (add n 10))
				(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
				""");
		byte[] optimized = new WasmLispCompiler(false, false, true, OptimizeLevel.DEFAULT).compile(program);
		List<String[]> imports = functionImports(optimized);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0)).containsExactly("host", "add");
		assertThat(exportedFunctionIndex(optimized, "add10")).isNotNegative();
	}

	// --- Minimal module readers (sections / import entries / export indices) ---

	// Returns the (module, name) pairs of the function imports, in index order.
	private static List<String[]> functionImports(byte[] module) {
		byte[] payload = section(module, 2);
		List<String[]> result = new ArrayList<>();
		if (payload == null) {
			return result;
		}
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			String mod = readName(payload, p);
			String name = readName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			switch (kind) {
				case 0x00 -> { // function: typeidx
					readU(payload, p);
					result.add(new String[] { mod, name });
				}
				case 0x02 -> { // memory: limits
					int flag = payload[p[0]++] & 0xff;
					readU(payload, p);
					if ((flag & 0x01) != 0) {
						readU(payload, p);
					}
				}
				default -> throw new IllegalStateException("Unexpected import kind: " + kind);
			}
		}
		return result;
	}

	// Returns the function index exported under the given name, or -1 if absent.
	private static int exportedFunctionIndex(byte[] module, String exportName) {
		byte[] payload = Objects.requireNonNull(section(module, 7));
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			String name = readName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			int index = readU(payload, p);
			if (kind == 0x00 && exportName.equals(name)) {
				return index;
			}
		}
		return -1;
	}

	private static byte @org.jspecify.annotations.Nullable [] section(byte[] module, int id) {
		int[] p = { 8 }; // skip "\0asm" + version
		while (p[0] < module.length) {
			int sectionId = module[p[0]++] & 0xff;
			int size = readU(module, p);
			if (sectionId == id) {
				byte[] payload = new byte[size];
				System.arraycopy(module, p[0], payload, 0, size);
				return payload;
			}
			p[0] += size;
		}
		return null;
	}

	@Test
	void aSuspendingImportGuardsEveryExportAgainstReentry() {
		// A parked JSPI call returns control to the host's event loop, so a second
		// call can enter while the first still holds the allocator bracket and the
		// shallowly-bound specials (the measured corruption: a special read back
		// wrong, a returned (ptr,len) overwritten). A module that can suspend
		// carries a guard global that EVERY export wrapper sets on entry -- a second
		// entry traps at the boundary -- and clears on return.
		String asyncSrc = """
				(rontolisp:wasm-import 'slow :from "env" :params '(:int) :returns :int :async t)
				(defun poke (n) (rontolisp::%future-force (slow n)))
				(defun peek (n) (+ n 1))
				(rontolisp:wasm-export 'poke :params '(:int) :returns :int)
				(rontolisp:wasm-export 'peek :params '(:int) :returns :int)
				""";
		String syncSrc = """
				(rontolisp:wasm-import 'slow :from "env" :params '(:int) :returns :int)
				(defun poke (n) (slow n))
				(defun peek (n) (+ n 1))
				(rontolisp:wasm-export 'poke :params '(:int) :returns :int)
				(rontolisp:wasm-export 'peek :params '(:int) :returns :int)
				""";
		byte[] guarded = compileNoWasi(asyncSrc);
		byte[] unguarded = compileNoWasi(syncSrc);
		// One guard global (a mut i32); a module that cannot suspend gains no global
		// and no guard instruction.
		assertThat(globalCount(guarded)).isEqualTo(globalCount(unguarded) + 1);
		assertThat(countOf(unguarded, GUARD_TRAP_AND_SET)).isZero();
		// Both wrappers check-and-set on entry (global.get g; if; unreachable; end;
		// i32.const 1; global.set g) and clear on return (i32.const 0; global.set g).
		int g = guardGlobalIndex(guarded);
		assertThat(countOf(guarded, prependGlobalGet(g, GUARD_TRAP_AND_SET), (byte) g)).isEqualTo(2);
		assertThat(countOf(guarded, new byte[] { 0x41, 0x00, 0x24, (byte) g })).isGreaterThanOrEqualTo(2);
	}

	@Test
	void hostFetchGuardsExportsExactlyWhereFetchIsUsed() {
		// --host-fetch's env.fetch is the other import a host may answer through
		// WebAssembly.Suspending, so a module whose program fetches carries the same
		// re-entry guard -- and one that never fetches (no import, nothing to suspend
		// on) stays guard-free.
		byte[] fetching = compileHostFetch("""
				(rontolisp:async-defun dog ()
				  (let* ((res (rontolisp:await (rontolisp:fetch "https://dog.ceo/api/breeds/image/random")))
				         (body (rontolisp:await (rontolisp:read-all (getf res :body)))))
				    body))
				(defun run () (rontolisp::%future-force (dog)))
				(rontolisp:wasm-export 'run :params '() :returns :string)
				""");
		int g = guardGlobalIndex(fetching);
		assertThat(countOf(fetching, prependGlobalGet(g, GUARD_TRAP_AND_SET), (byte) g)).isEqualTo(1);
		byte[] noFetch = compileHostFetch("""
				(defun run () 1)
				(rontolisp:wasm-export 'run :params '() :returns :int)
				""");
		assertThat(countOf(noFetch, GUARD_TRAP_AND_SET)).isZero();
	}

	// if (blocktype empty); unreachable; end; i32.const 1; global.set -- the re-entry
	// guard's trap-and-set, minus the leading global.get whose index varies by module.
	private static final byte[] GUARD_TRAP_AND_SET = { 0x04, 0x40, 0x00, 0x0b, 0x41, 0x01, 0x24 };

	// The guard global's index, recovered from the wrapper's own bytes (the global.get
	// that leads the trap-and-set). It cannot be computed from the global section's END
	// any more: the quoted-datum globals (.kb/quoted-data.md) are appended after every
	// fixed-index global, and how many a module carries depends on the quote sites its
	// injected wrappers compile.
	private static int guardGlobalIndex(byte[] module) {
		for (int g = 0; g < 128; g++) {
			if (countOf(module, prependGlobalGet(g, GUARD_TRAP_AND_SET)) > 0) {
				return g;
			}
		}
		throw new AssertionError("no re-entry guard trap-and-set found in the module");
	}

	private static byte[] prependGlobalGet(int globalIndex, byte[] tail) {
		byte[] result = new byte[tail.length + 2];
		result[0] = 0x23;
		result[1] = (byte) globalIndex;
		System.arraycopy(tail, 0, result, 2, tail.length);
		return result;
	}

	// Occurrences of needle in the module, optionally requiring the byte AFTER each
	// match to equal trailing (the guard global's index following global.set).
	private static int countOf(byte[] module, byte[] needle, byte... trailing) {
		int count = 0;
		outer: for (int i = 0; i <= module.length - needle.length - trailing.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (module[i + j] != needle[j]) {
					continue outer;
				}
			}
			for (int j = 0; j < trailing.length; j++) {
				if (module[i + needle.length + j] != trailing[j]) {
					continue outer;
				}
			}
			count++;
		}
		return count;
	}

	private static byte[] compileNoWasiSize(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, true, OptimizeLevel.SIZE).compile(program);
	}

	// The charvec normalization is the biggest single thing a :string boundary drags in
	// (.kb/wasm-gc-strings.md), and a program that cannot MAKE a mutable character
	// vector must not carry it. The pin is the PAIR, not an absolute size: the same
	// module with one flipped producer added has to carry it, and the difference is the
	// whole group rather than the one call.
	@Test
	void theCharvecNormalizationIsAbsentFromAModuleThatCannotMakeOne() {
		String reactor = """
				(rontolisp:wasm-import 'host-log :from "env" :as "host_log" :params '(:string) :returns nil)
				(defun init-app () (host-log %s))
				(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
				""";
		int free = compileNoWasiSize(reactor.formatted("\"module initialized\"")).length;
		// string-upcase answers a mutable character vector (MutableStringProducers), so
		// the same boundary has something to normalize again.
		int withProducer = compileNoWasiSize(reactor.formatted("(string-upcase \"module initialized\")")).length;
		assertThat(withProducer - free).isGreaterThan(1_000);
	}

	// The gate is an ALLOWLIST over the program's operators, so an operator it has never
	// heard of has to OPEN it: write-string's :start/:end becomes a subseq at Pass 2,
	// and a module that dropped the normalization there handed the host an unrendered
	// character vector (measured as a cast-failure trap).
	@Test
	void anOperatorThatLowersToAConstructorKeepsTheNormalization() {
		String reactor = """
				(rontolisp:wasm-import 'host-log :from "env" :as "host_log" :params '(:string) :returns nil)
				(defun init-app () (host-log "module initialized") %s)
				(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
				""";
		int free = compileNoWasiSize(reactor.formatted("nil")).length;
		int lowered = compileNoWasiSize(reactor.formatted("(write-string \"hello\" nil :start 1 :end 3)")).length;
		assertThat(lowered - free).isGreaterThan(1_000);
	}

	// A user (defun subseq ...) on a `cl` name the backend intercepts as an operator:
	// the call still compiles to the standard subseq operator (ClRedefinitionWarnings --
	// the definition never runs), which reaches %SUBSEQ-RUNTIME assuming a charvec is
	// possible. defunNames used to trust the definition anyway and close the gate ahead
	// of it, so this program stopped building. Reproduces the exact program from the bug
	// report, at both optimize levels since the gate is level-independent.
	@Test
	void aUserDefunOnASubseqInterceptedNameStillOpensTheGate() {
		String source = """
				(rontolisp:wasm-import 'emit :from "env" :as "emit" :params '(:string) :returns nil)
				(defun subseq (s a b) (if (< a b) s s))
				(defun go () (emit (subseq "abcdef" 1 3)))
				(rontolisp:wasm-export 'go :as "Go" :params '() :returns nil)
				""";
		assertThat(compileNoWasiSize(source)).isNotEmpty();
		List<LispVal> program = LispReader.readAllFromString(source);
		assertThat(new WasmLispCompiler(false, false, true, OptimizeLevel.DEFAULT).compile(program)).isNotEmpty();
	}

	// Same defect, the %SEQ-TO-LIST route: `copy-seq` reaches it through the same
	// injected subseq-family runtime as a whole-sequence copy.
	@Test
	void aUserDefunOnACopySeqInterceptedNameStillOpensTheGate() {
		String source = """
				(rontolisp:wasm-import 'emit :from "env" :as "emit" :params '(:string) :returns nil)
				(defun copy-seq (s) s)
				(defun go () (emit (copy-seq "abcdef")))
				(rontolisp:wasm-export 'go :as "Go" :params '() :returns nil)
				""";
		assertThat(compileNoWasiSize(source)).isNotEmpty();
	}

	// Same defect, `reverse`'s %SEQ-TO-LIST arm.
	@Test
	void aUserDefunOnAReverseInterceptedNameStillOpensTheGate() {
		String source = """
				(rontolisp:wasm-import 'emit :from "env" :as "emit" :params '(:string) :returns nil)
				(defun reverse (s) s)
				(defun go () (emit (reverse "abcdef")))
				(rontolisp:wasm-export 'go :as "Go" :params '() :returns nil)
				""";
		assertThat(compileNoWasiSize(source)).isNotEmpty();
	}

	// Same defect, the flipped-string-producer arm of the assertion (`string-upcase` /
	// `string-trim` construct their result rather than calling into injected runtime,
	// but the over-trusted defunNames closed the gate ahead of them too).
	@Test
	void aUserDefunOnAStringUpcaseInterceptedNameStillOpensTheGate() {
		String source = """
				(rontolisp:wasm-import 'emit :from "env" :as "emit" :params '(:string) :returns nil)
				(defun string-upcase (s) s)
				(defun go () (emit (string-upcase "abcdef")))
				(rontolisp:wasm-export 'go :as "Go" :params '() :returns nil)
				""";
		assertThat(compileNoWasiSize(source)).isNotEmpty();
	}

	@Test
	void aUserDefunOnAStringTrimInterceptedNameStillOpensTheGate() {
		String source = """
				(rontolisp:wasm-import 'emit :from "env" :as "emit" :params '(:string) :returns nil)
				(defun string-trim (bag s) s)
				(defun go () (emit (string-trim " " "abcdef")))
				(rontolisp:wasm-export 'go :as "Go" :params '() :returns nil)
				""";
		assertThat(compileNoWasiSize(source)).isNotEmpty();
	}

	// Negative (.todo/793): a user defun on a name that is NOT a `cl` function keeps the
	// gate closed exactly as before -- fixing this by trusting every user defun would
	// give back the bytes .todo/789 bought.
	@Test
	void aNonClUserDefunKeepsTheGateClosed() {
		String bare = """
				(rontolisp:wasm-import 'host-log :from "env" :as "host_log" :params '(:string) :returns nil)
				(defun init-app () (host-log "module initialized"))
				(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
				""";
		String withNonClDefun = """
				(rontolisp:wasm-import 'host-log :from "env" :as "host_log" :params '(:string) :returns nil)
				(defun my-helper (s) s)
				(defun init-app () (host-log (my-helper "module initialized")))
				(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
				""";
		// The normalization group alone is ~1,961 bytes (.kb/wasm-gc-strings.md); a plain
		// extra one-line defun costs nowhere near that, so this stays well under it.
		assertThat(compileNoWasiSize(withNonClDefun).length - compileNoWasiSize(bare).length).isLessThan(500);
	}

	// The global section's entry count (0 when the section is absent).
	private static int globalCount(byte[] module) {
		byte[] payload = section(module, 6);
		return payload == null ? 0 : readU(payload, new int[] { 0 });
	}

	private static String readName(byte[] buf, int[] p) {
		int len = readU(buf, p);
		String s = new String(buf, p[0], len, StandardCharsets.UTF_8);
		p[0] += len;
		return s;
	}

	private static int readU(byte[] buf, int[] p) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = buf[p[0]++] & 0xff;
			result |= (b & 0x7f) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		return result;
	}

}
