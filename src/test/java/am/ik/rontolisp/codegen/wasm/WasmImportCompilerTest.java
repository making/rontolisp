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
		assertThat(functionCount(compile(withBytes))).isEqualTo(functionCount(compile(withoutBytes)) + 4);
	}

	// The number of entries in the function section (defined functions, imports
	// excluded).
	private static int functionCount(byte[] module) {
		byte[] section = Objects.requireNonNull(section(module, 3));
		return readU(section, new int[] { 0 });
	}

	@Test
	void rejectsUnknownOption() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'g :wat 1)")).hasMessageContaining(":WAT");
	}

	@Test
	void injectedImportsComeFirstInTheImportSection() {
		byte[] module = compile("""
				(rontolisp:wasm-import 'begin-frame :from "gl" :as "beginFrame" :params '(:int))
				(rontolisp:wasm-import 'draw :from "gl" :params '(:float :float) :returns :int)
				(print (draw 1.0 2.0))
				""");
		List<String[]> imports = functionImports(module);
		// The two host imports occupy function indices 0 and 1, ahead of the eleven
		// wasi_snapshot_preview1 imports.
		assertThat(imports).hasSize(13);
		assertThat(imports.get(0)).containsExactly("gl", "beginFrame");
		assertThat(imports.get(1)).containsExactly("gl", "draw");
		assertThat(imports.get(2)[0]).isEqualTo("wasi_snapshot_preview1");
	}

	@Test
	void functionExportIndicesShiftPastTheInjectedImports() {
		String source = "(rontolisp:wasm-import 'ping :params '())" + "(print 1)";
		byte[] withImport = compile(source);
		byte[] without = compile("(print 1)");
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
	void rejectsNoGcMode() {
		List<LispVal> program = LispReader.readAllFromString("""
				(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)
				(defun add10 (n) (add n 10))
				(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
				""");
		assertThatThrownBy(() -> new NoGcWasmCompiler().compile(program)).hasMessageContaining("--no-gc");
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
		// One guard global (a mut i32, fourth from last -- the cached-t, raw-local
		// sentinel and string-stream table globals stay the LAST three); a module that
		// cannot suspend gains no global and no guard instruction.
		assertThat(globalCount(guarded)).isEqualTo(globalCount(unguarded) + 1);
		assertThat(countOf(unguarded, GUARD_TRAP_AND_SET)).isZero();
		// Both wrappers check-and-set on entry (global.get g; if; unreachable; end;
		// i32.const 1; global.set g) and clear on return (i32.const 0; global.set g).
		int g = globalCount(guarded) - UNCONDITIONAL_TRAILING_GLOBALS - 1;
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
		// A fetching module builds header tables, so it also carries the _hash
		// recursion-depth global, which is appended after the unconditional three
		// (.kb/hash-tables.md) and pushes the guard one further down.
		int g = globalCount(fetching) - UNCONDITIONAL_TRAILING_GLOBALS - HASH_DEPTH_GLOBAL - 1;
		assertThat(countOf(fetching, prependGlobalGet(g, GUARD_TRAP_AND_SET), (byte) g)).isEqualTo(1);
		byte[] noFetch = compileHostFetch("""
				(defun run () 1)
				(rontolisp:wasm-export 'run :params '() :returns :int)
				""");
		assertThat(countOf(noFetch, GUARD_TRAP_AND_SET)).isZero();
	}

	// The globals every module ends with, whatever its mode: the cached symbol t, the
	// raw-local sentinel and the string output-stream buffer table. The re-entry guard
	// is the last MODE-GATED global, so it sits just below them.
	private static final int UNCONDITIONAL_TRAILING_GLOBALS = 3;

	// The _hash recursion-depth global, present exactly when the program uses a hash
	// table; it is appended after the three above, so it is the very last global.
	private static final int HASH_DEPTH_GLOBAL = 1;

	// if (blocktype empty); unreachable; end; i32.const 1; global.set -- the re-entry
	// guard's trap-and-set, minus the leading global.get whose index varies by module.
	private static final byte[] GUARD_TRAP_AND_SET = { 0x04, 0x40, 0x00, 0x0b, 0x41, 0x01, 0x24 };

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
