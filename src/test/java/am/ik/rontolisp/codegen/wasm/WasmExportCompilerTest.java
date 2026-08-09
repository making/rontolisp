package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WasmExportCompiler} parsing and for the module-level wiring of
 * {@code (rontolisp:wasm-export ...)} directives (export names present, error cases
 * rejected). These run without Docker; the end-to-end {@code wasmtime --invoke} checks
 * live in {@link WasmLispCompilerIntegrationTest}.
 */
class WasmExportCompilerTest {

	private static WasmExportCompiler.Decl parse(String source) {
		return WasmExportCompiler.parse((LispCons) LispReader.readFromString(source));
	}

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler().compile(program);
	}

	// The CLI-equivalent serve compile pipeline (mirrors
	// WasmLispCompilerIntegrationTest.compileServeComponent, minus the wasmtime run):
	// splice
	// fetch.lisp then serve.lisp, expand, compile in serve mode. No Docker needed for a
	// bytes-only check.
	private static byte[] compileServe(String source) {
		List<LispVal> loaded = am.ik.rontolisp.eval.WitImportInliner.inline(LispReader.readAllFromString(source), null,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT,
				am.ik.rontolisp.eval.SourceLoader.fileSystem());
		boolean bufferBody = am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(loaded);
		loaded = am.ik.rontolisp.eval.HttpLibrary.process(loaded,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, true);
		loaded = am.ik.rontolisp.eval.HttpServerLibrary.process(loaded, bufferBody);
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary.process(
				am.ik.rontolisp.eval.GrayStreamsLibrary.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded)));
		return new WasmLispCompiler(false, true, false, OptimizeLevel.NONE, true).compile(program);
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

	@Test
	void parsesScalarDirective() {
		WasmExportCompiler.Decl decl = parse("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(decl.name()).isEqualTo("FACT");
		assertThat(decl.paramTypes()).containsExactly(BoundaryType.S32);
		assertThat(decl.returnType()).isEqualTo(BoundaryType.S32);
	}

	@Test
	void parsesMultipleParamsAndMemoryTypes() {
		WasmExportCompiler.Decl decl = parse(
				"(rontolisp:wasm-export 'concat :params '(:string :s-expr) :returns :string)");
		assertThat(decl.paramTypes()).containsExactly(BoundaryType.STRING, BoundaryType.S_EXPR);
		assertThat(WasmExportCompiler.usesMemory(decl)).isTrue();
		assertThat(WasmExportCompiler.paramSlotCount(decl)).isEqualTo(4);
	}

	@Test
	void parsesZeroArgDirective() {
		WasmExportCompiler.Decl decl = parse("(rontolisp:wasm-export 'now :params '() :returns :int)");
		assertThat(decl.paramTypes()).isEmpty();
		assertThat(WasmExportCompiler.usesMemory(decl)).isFalse();
	}

	@Test
	void treatsOmittedReturnsAsVoid() {
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int))").returnType()).isEqualTo(BoundaryType.VOID);
	}

	@Test
	void treatsExplicitVoidMarkersAsVoid() {
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns :void)").returnType())
			.isEqualTo(BoundaryType.VOID);
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns nil)").returnType())
			.isEqualTo(BoundaryType.VOID);
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns '())").returnType())
			.isEqualTo(BoundaryType.VOID);
	}

	@Test
	void voidReturnHasNoWasmResult() {
		WasmExportCompiler.Decl decl = parse("(rontolisp:wasm-export 'go :params '(:int))");
		assertThat(WasmExportCompiler.resultWasmTypes(decl)).isEmpty();
		assertThat(WasmExportCompiler.usesMemory(decl)).isFalse();
	}

	@Test
	void defaultsExportNameToTheLispName() {
		assertThat(parse("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)").exportName()).isEqualTo("fact");
	}

	@Test
	void defaultExportNameOfPackageQualifiedNameIsTheUnqualifiedMember() {
		// An export declared inside a user package resolves its name to pkg:name; the
		// host-facing export name must default to the bare member name.
		assertThat(parse("(rontolisp:wasm-export 'app:frame :params '(:float))").exportName()).isEqualTo("frame");
		assertThat(parse("(rontolisp:wasm-export 'app::tick :params '(:float))").exportName()).isEqualTo("tick");
	}

	@Test
	void parsesAsAlias() {
		assertThat(parse("(rontolisp:wasm-export 'fact :as \"fibonacci\" :params '(:int) :returns :int)").exportName())
			.isEqualTo("fibonacci");
		// Leniently, a quoted symbol names the export too.
		assertThat(parse("(rontolisp:wasm-export 'fact :as 'fib :params '(:int) :returns :int)").exportName())
			.isEqualTo("fib");
	}

	@Test
	void rejectsNonStringAsAlias() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'fact :as 42 :params '(:int) :returns :int)"))
			.hasMessageContaining(":as");
	}

	@Test
	void compiledModuleExportsUnderTheAlias() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :as \"fibonacci\" :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "fibonacci")).isTrue();
	}

	@Test
	void treatsNilParamsAsNoArguments() {
		assertThat(parse("(rontolisp:wasm-export 'go :params nil :returns :int)").paramTypes()).isEmpty();
		assertThat(parse("(rontolisp:wasm-export 'go :returns :int)").paramTypes()).isEmpty();
	}

	@Test
	void compilesVoidExport() {
		byte[] bytes = compile(
				"(defun ping () (print \"pong\")) (rontolisp:wasm-export 'ping :params '() :returns :void)");
		assertThat(containsAscii(bytes, "ping")).isTrue();
	}

	@Test
	void rejectsUnknownTypeDesignator() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'g :params '(:widget) :returns :int)"))
			.hasMessageContaining(":WIDGET");
	}

	@Test
	void compiledModuleExportsScalarFunctionByName() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "fact")).isTrue();
	}

	@Test
	void compiledModuleEmitsAllocatorForMemoryExport() {
		byte[] bytes = compile("(defun shout (s) (string-upcase s))"
				+ "(rontolisp:wasm-export 'shout :params '(:string) :returns :string)");
		assertThat(containsAscii(bytes, "__ronto_alloc")).isTrue();
		assertThat(containsAscii(bytes, "shout")).isTrue();
	}

	@Test
	void scalarExportDoesNotEmitAllocator() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "__ronto_alloc")).isFalse();
	}

	@Test
	void memoryExportEmitsTheHostArenaApi() {
		// A memory-exporting module also exports the arena pair, so a resident
		// host can pop the input buffer it bump-allocated: the engine collects the Lisp
		// side, but linear memory at the boundary is invisible to it.
		byte[] bytes = compile("(defun shout (s) (string-upcase s))"
				+ "(rontolisp:wasm-export 'shout :params '(:string) :returns :string)");
		assertThat(containsAscii(bytes, "__ronto_alloc_mark")).isTrue();
		assertThat(containsAscii(bytes, "__ronto_alloc_reset")).isTrue();
	}

	@Test
	void scalarExportOmitsTheHostArenaApi() {
		// No memory-typed export: no linear-memory boundary, so no arena API (and the
		// module stays byte-identical to the shape it had before the arena API).
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "__ronto_alloc_mark")).isFalse();
	}

	@Test
	void componentModeOmitsTheHostArenaApi() {
		// Under --component the memory is imported, not exported, and the host reaches
		// the heap through the canonical cabi_realloc / cabi_post_* pair -- which does
		// the same intern-guarded pop for itself. So no arena API here.
		List<LispVal> program = LispReader
			.readAllFromString("(defun shout (s) (string-upcase s)) (rontolisp:wasm-export 'shout :params '(:string)"
					+ " :returns :string) (print \"hi\")");
		byte[] component = new WasmLispCompiler(false, true).compile(program);
		assertThat(containsAscii(component, "__ronto_alloc_mark")).isFalse();
		assertThat(containsAscii(component, "cabi_post_i32")).isTrue();
	}

	@Test
	void carriesTheSixtyFourBitDesignatorsOnTheGcBackend() {
		// The 64-bit types used to be a compile-time refusal here (integers widened to
		// a float past i31, exact only below 2^53). The boxed exact-integer
		// representation (.kb/wasm-bignum.md) carries the full signed 64-bit range, so
		// the declarations compile on the GC backend too; the legacy :long spelling is
		// the same type.
		assertThat(compile("(defun f (a b) (* (+ a b) (+ a b)))"
				+ "(rontolisp:wasm-export 'f :params '(:long :long) :returns :long)"))
			.isNotEmpty();
		assertThat(compile("(defun f (a b) (* (+ a b) (+ a b)))"
				+ "(rontolisp:wasm-export 'f :params '(:u64 :u64) :returns :u64)"))
			.isNotEmpty();
	}

	@Test
	void theLegacyIntAndLongSpellingsAreAliasesOfTheirWitTypes() {
		// Every program written against the pre-WIT vocabulary keeps parsing, and
		// normalizes to the canonical spelling -- which is what makes it compile to the
		// same bytes as the WIT-spelled twin.
		WasmExportCompiler.Decl legacy = parse("(rontolisp:wasm-export 'f :params '(:int :long) :returns :int)");
		WasmExportCompiler.Decl canonical = parse("(rontolisp:wasm-export 'f :params '(:s32 :s64) :returns :s32)");
		assertThat(legacy).isEqualTo(canonical);
		assertThat(legacy.paramTypes()).containsExactly(BoundaryType.S32, BoundaryType.S64);
	}

	@Test
	void componentLiftsEachIntegerTypeUnderItsOwnValueTypeCode() {
		// There is no integer subtyping in the component model: lifting a u32 export as
		// s32 would make `wasm-tools component targets` (and jco, and any bindgen host)
		// reject the component against its own world. The type code has to be exact, and
		// the recorded WIT is how the component's own type section reads back.
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		compiler.compile(LispReader.readAllFromString("""
				(defun bump (n) (+ n 1))
				(defun narrow (a b c d) (+ a b c d))
				(rontolisp:wasm-export 'bump :params '(:u32) :returns :u32)
				(rontolisp:wasm-export 'narrow :params '(:s8 :s16 :u8 :u16) :returns :u8)
				"""));
		assertThat(compiler.componentWit()).contains("""
				  export bump: func(p0: u32) -> u32;
				  export narrow: func(p0: s8, p1: s16, p2: u8, p3: u16) -> u8;
				""");
	}

	@Test
	void rejectsExportOfUnknownFunction() {
		assertThatThrownBy(() -> compile("(rontolisp:wasm-export 'nope :params '(:int) :returns :int)"))
			.hasMessageContaining("unknown function");
	}

	@Test
	void rejectsArityMismatch() {
		assertThatThrownBy(
				() -> compile("(defun f (a b) (+ a b)) (rontolisp:wasm-export 'f :params '(:int) :returns :int)"))
			.hasMessageContaining("arity mismatch");
	}

	@Test
	void noWasiModeOmitsWasiImports() {
		// Reactor mode: no wasi_snapshot_preview1 imports, but the export wrapper stays.
		List<LispVal> program = LispReader.readAllFromString("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		byte[] bytes = new WasmLispCompiler(false, false, true).compile(program);
		assertThat(containsAscii(bytes, "wasi_snapshot_preview1")).isFalse();
		assertThat(containsAscii(bytes, "fact")).isTrue();
	}

	@Test
	void noWasiFdWriteStubIsASink() {
		// The nine omitted imports become internal stubs; eight are `unreachable`, and
		// fd_write is deliberately not one of them -- it reports the iovec as written
		// and returns errno 0, so a reactor discards its output instead of trapping.
		// Pinned on the emitted body, since the behaviour is invisible structurally.
		List<LispVal> program = LispReader
			.readAllFromString("(defun f (n) (print n) n)(rontolisp:wasm-export 'f :params '(:int) :returns :int)");
		byte[] bytes = new WasmLispCompiler(false, false, true).compile(program);
		// 0 locals; local.get 3 ; local.get 1 ; i32.load off=4 ; i32.store ; i32.const 0
		byte[] sink = { 0x00, 0x20, 0x03, 0x20, 0x01, 0x28, 0x02, 0x04, 0x36, 0x02, 0x00, 0x41, 0x00, 0x0b };
		assertThat(containsBytes(bytes, sink)).as("the no-wasi fd_write sink body").isTrue();
	}

	// The nine --no-wasi stub bodies, in import-slot order: they are the first nine
	// entries of the code section (WasmLispCompiler emits them before the start
	// function).
	// Each element is the raw body -- the locals declaration included -- exactly as the
	// builders produce it, so a stub's whole policy can be pinned byte for byte.
	private static byte[][] noWasiStubBodies(byte[] module) {
		int[] p = { 8 }; // past the magic + version header
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xff;
			int size = readUnsignedLeb128(module, p);
			int end = p[0] + size;
			if (id != 10) { // not the code section
				p[0] = end;
				continue;
			}
			int count = readUnsignedLeb128(module, p);
			byte[][] bodies = new byte[Math.min(count, WasmLispCompiler.IMPORT_FUNC_COUNT)][];
			for (int i = 0; i < bodies.length; i++) {
				int bodySize = readUnsignedLeb128(module, p);
				bodies[i] = java.util.Arrays.copyOfRange(module, p[0], p[0] + bodySize);
				p[0] += bodySize;
			}
			return bodies;
		}
		throw new IllegalStateException("no code section");
	}

	private static int readUnsignedLeb128(byte[] buf, int[] p) {
		int result = 0, shift = 0, b;
		do {
			b = buf[p[0]++] & 0xff;
			result |= (b & 0x7f) << shift;
			shift += 7;
		}
		while ((b & 0x80) != 0);
		return result;
	}

	@Test
	void noWasiStubsAnswerWhereverThereIsATrueAnswerAndTrapWhereThereIsNot() {
		// The whole --no-wasi stub policy in one pin. A stub may answer when the answer
		// is TRUE OF THIS MODULE -- no destination for output, no environment
		// variables, no files -- and `random` may generate, because CL's random is a
		// pseudo-random draw from *random-state*, not an entropy API. It may NOT invent
		// input, and it may not name a time that is not the time: those two keep the
		// bare `unreachable`.
		List<LispVal> program = LispReader
			.readAllFromString("(defun f (n) n)(rontolisp:wasm-export 'f :params '(:int) :returns :int)");
		byte[][] bodies = noWasiStubBodies(new WasmLispCompiler(false, false, true).compile(program));

		byte[] trap = { 0x00, 0x00, 0x0b };
		assertThat(bodies[WasmLispCompiler.FUNC_FD_READ]).as("fd_read: answering EOF would invent input")
			.isEqualTo(trap);
		assertThat(bodies[WasmLispCompiler.FUNC_CLOCK_TIME_GET]).as("clock_time_get: no reading means 'no time'")
			.isEqualTo(trap);

		// 0 locals; local.get 3; local.get 1; i32.load off=4; i32.store; i32.const 0
		assertThat(bodies[WasmLispCompiler.FUNC_FD_WRITE]).as("fd_write is a sink")
			.isEqualTo(
					new byte[] { 0x00, 0x20, 0x03, 0x20, 0x01, 0x28, 0x02, 0x04, 0x36, 0x02, 0x00, 0x41, 0x00, 0x0b });
		// 0 locals; *count = 0; *bufsize = 0; return errno 0 -- an EMPTY environment.
		assertThat(bodies[WasmLispCompiler.FUNC_ENVIRON_SIZES_GET]).as("environ_sizes_get reports an empty environment")
			.isEqualTo(new byte[] { 0x00, 0x20, 0x00, 0x41, 0x00, 0x36, 0x02, 0x00, 0x20, 0x01, 0x41, 0x00, 0x36, 0x02,
					0x00, 0x41, 0x00, 0x0b });
		assertThat(bodies[WasmLispCompiler.FUNC_ENVIRON_GET]).as("environ_get: nothing to write, errno 0")
			.isEqualTo(new byte[] { 0x00, 0x41, 0x00, 0x0b });
		// The filesystem family reports an errno the _open / _probe_file /
		// _list_directory / _load runtimes already turn into nil.
		assertThat(bodies[WasmLispCompiler.FUNC_PATH_OPEN]).as("path_open answers ENOENT")
			.isEqualTo(new byte[] { 0x00, 0x41, (byte) WasmIoRuntimeBuilder.ERRNO_NOENT, 0x0b });
		assertThat(bodies[WasmLispCompiler.FUNC_FD_CLOSE]).as("fd_close answers EBADF")
			.isEqualTo(new byte[] { 0x00, 0x41, (byte) WasmIoRuntimeBuilder.ERRNO_BADF, 0x0b });
		assertThat(bodies[WasmLispCompiler.FUNC_FD_READDIR]).as("fd_readdir answers EBADF")
			.isEqualTo(new byte[] { 0x00, 0x41, (byte) WasmIoRuntimeBuilder.ERRNO_BADF, 0x0b });

		// random_get is a SplitMix64 generator over RANDOM_STATE_ADDR: two i64 locals,
		// and the golden-ratio gamma as a signed LEB128 i64 constant.
		byte[] randomGet = bodies[WasmLispCompiler.FUNC_RANDOM_GET];
		assertThat(randomGet).as("random_get is not a trap").isNotEqualTo(trap);
		assertThat(java.util.Arrays.copyOf(randomGet, 3)).as("two i64 locals")
			.isEqualTo(new byte[] { 0x01, 0x02, 0x7e });
		assertThat(containsBytes(randomGet, new byte[] { (byte) 0x95, (byte) 0xf8, (byte) 0xa9, (byte) 0xfa,
				(byte) 0x97, (byte) 0xb7, (byte) 0xde, (byte) 0x9b, (byte) 0x9e, 0x7f }))
			.as("the SplitMix64 gamma")
			.isTrue();
	}

	@Test
	void noWasiCoreModuleExportsTheHostSeedHook() {
		// A --no-wasi module's `random` runs on its own generator from a CONSTANT start
		// state, so every instance would repeat one sequence. __ronto_seed_random lets a
		// host replace that state with real entropy before _initialize -- an export, not
		// an import, because an import would cost the module the one property the flag
		// exists for (instantiate with {}).
		String source = "(defun draw (n) (random n))(rontolisp:wasm-export 'draw :params '(:int) :returns :int)";
		List<LispVal> program = LispReader.readAllFromString(source);
		assertThat(containsAscii(new WasmLispCompiler(false, false, true).compile(program), "__ronto_seed_random"))
			.as("--no-wasi core module")
			.isTrue();
		// Not on a WASI-carrying build (random_get is the host's there) ...
		assertThat(containsAscii(compile(source), "__ronto_seed_random")).as("Preview 1").isFalse();
		// ... and not on the reactor COMPONENT, whose top level runs at instantiation --
		// there is no window before the load-time draws, and exposing it at all would
		// mean lifting it into the WIT world.
		assertThat(containsAscii(new WasmLispCompiler(false, true, true).compile(program), "__ronto_seed_random"))
			.as("--component --no-wasi reactor")
			.isFalse();
	}

	@Test
	void hostRandomForwardsTheRandomGetSlotAndRetiresTheSeedHook() {
		// --host-random is the ONE opt-in out of the zero-import contract, and it opts
		// out of exactly one slot: random_get stops being the module's own SplitMix64
		// and forwards its (buf, len) to the injected host import, whose call index the
		// WasmImportInjector has rewritten to 0 (it is the only import). Everything the
		// module-local generator implied goes with it -- there is no state left to
		// seed, so no __ronto_seed_random -- and the entropy API is sound again,
		// because the bytes really are the host's.
		// %random-byte is the primitive rontolisp:random-bytes is built out of, and the
		// one the compiler gates -- spelled directly here so the check does not depend
		// on the CLI's library splice.
		String source = """
				(defun draw (n) (random n))
				(rontolisp:wasm-export 'draw :params '(:int) :returns :int)
				(defun secret () (rontolisp::%random-byte))
				(rontolisp:wasm-export 'secret :params '() :returns :int)
				""";
		List<LispVal> program = LispReader.readAllFromString(source);
		byte[] hostRandom = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, true)
			.compile(program);
		// 0 locals; local.get 0; local.get 1; call 0; end
		assertThat(noWasiStubBodies(hostRandom)[WasmLispCompiler.FUNC_RANDOM_GET]).as("random_get forwards to the host")
			.isEqualTo(new byte[] { 0x00, 0x20, 0x00, 0x20, 0x01, 0x10, 0x00, 0x0b });
		assertThat(containsAscii(hostRandom, "__ronto_seed_random")).as("nothing left to seed").isFalse();

		// The default is unchanged: the module-local generator and the seed hook beside
		// it. That the ENTROPY API follows the slot is pinned in WasmImportCompilerTest,
		// where a surviving import is the proof that %random-byte reaches the host.
		byte[] selfContained = new WasmLispCompiler(false, false, true).compile(program);
		assertThat(noWasiStubBodies(selfContained)[WasmLispCompiler.FUNC_RANDOM_GET])
			.isNotEqualTo(noWasiStubBodies(hostRandom)[WasmLispCompiler.FUNC_RANDOM_GET]);
		assertThat(containsAscii(selfContained, "__ronto_seed_random")).isTrue();
	}

	@Test
	void hostRandomIsRejectedWhereThereIsNoSlotToRoute() {
		// Every other WASM build already draws from the host (preview1's random_get,
		// the component's wasi:random), and a --no-wasi reactor COMPONENT imports
		// nothing at all by contract -- an entropy import there would be a WIT
		// world-shape decision, not a core export one.
		assertThatThrownBy(() -> new WasmLispCompiler(false, false, false, OptimizeLevel.NONE, false, false, true))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--host-random requires --no-wasi");
		assertThatThrownBy(() -> new WasmLispCompiler(false, true, true, OptimizeLevel.NONE, false, false, true))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--host-random cannot be combined with --component");
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

	@Test
	void defaultModeKeepsWasiImports() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "wasi_snapshot_preview1")).isTrue();
	}

	@Test
	void componentNoWasiIsAReactorThatImportsNothing() {
		// --no-wasi under --component: a REACTOR component. No component-level import
		// section (the import block, the adapter and the shared memory module are all
		// gone), no wasi:cli/run export, ONE core module whose START SECTION runs the
		// top level at instantiation. Asserted on the component's structure, not on an
		// ASCII needle: the bundled adapter used to carry "wasi_snapshot_preview1" in
		// its own export names, which made the needle-based predecessor of this test
		// pass whatever the compiler did. The zero-import property must hold with AND
		// without --optimize -- it is the flag's contract, not a narrowing outcome.
		List<LispVal> program = LispReader.readAllFromString("""
				(print (+ 1 2))
				(defparameter *base* 41)
				(defun bump () (+ *base* 1))
				(rontolisp:wasm-export 'bump :params '() :returns :int)
				""");
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.DEFAULT)) {
			byte[] component = new WasmLispCompiler(false, true, true, level).compile(program);
			assertThat(componentSectionIds(component)).as("component import section under " + level)
				.doesNotContain(am.ik.wasm.ComponentWriter.SEC_IMPORT);
			assertThat(containsAscii(component, "wasi:cli/run")).as("run export under " + level).isFalse();
			List<byte[]> cores = componentCoreModules(component);
			assertThat(cores).as("core modules under " + level).hasSize(1);
			// Core section id 8 = start: the engine runs the top level at instantiation.
			assertThat(componentSectionIds(cores.get(0))).as("core start section under " + level).contains(8);
			assertThat(containsAscii(component, "bump")).isTrue();
		}
	}

	@Test
	void componentNoWasiReactorRecordsTheEmptyWorldWit() {
		// The reactor's --emit-wit world: no imports, no fixed export -- exactly the
		// appended wasm-export items (byte-diffed against wasm-tools component wit in
		// WitOracleE2eTest). Exact equality is the point: it proves no import line and
		// no wasi:cli/run survive.
		List<LispVal> program = LispReader.readAllFromString("""
				(defparameter *greeting* "hello, ")
				(defun greet (name) (concatenate 'string *greeting* name))
				(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
				""");
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, true);
		compiler.compile(program);
		assertThat(compiler.componentWit()).isEqualTo("""
				package root:component;

				world root {
				  export greet: func(p0: string) -> string;
				}
				""");
	}

	@Test
	void componentNoWasiRejectsServeAndWitImports() {
		// A serve component's entire surface is wasi:http, and any WIT interface
		// binding is a component-level import: both contradict "imports nothing", so
		// both must refuse by name instead of quietly dropping a flag.
		assertThatThrownBy(() -> new WasmLispCompiler(false, true, true, OptimizeLevel.NONE, true))
			.hasMessageContaining("--no-wasi")
			.hasMessageContaining("rontolisp:http-handler");
	}

	// The (id, size, payload) section ids of a component OR a core module (both share
	// the same section framing after the 8-byte magic/version preamble).
	private static List<Integer> componentSectionIds(byte[] binary) {
		List<Integer> ids = new java.util.ArrayList<>();
		int pos = 8;
		while (pos < binary.length) {
			ids.add((int) binary[pos++]);
			int size = 0;
			int shift = 0;
			while (true) {
				int b = binary[pos++] & 0xFF;
				size |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					break;
				}
				shift += 7;
			}
			pos += size;
		}
		return ids;
	}

	// The payloads of a component's core-module sections (each a whole core module).
	private static List<byte[]> componentCoreModules(byte[] component) {
		List<byte[]> modules = new java.util.ArrayList<>();
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
			if (id == am.ik.wasm.ComponentWriter.SEC_CORE_MODULE) {
				modules.add(java.util.Arrays.copyOfRange(component, pos, pos + size));
			}
			pos += size;
		}
		return modules;
	}

	@Test
	void componentModeLiftsScalarExport() {
		// A scalar export is core-exported, aliased and canonically lifted into a
		// component-model export under its name; no memory allocator and none
		// of the string-ABI helpers appear (cabi_realloc exists in every GC component --
		// the shared mem module exports one -- so absence is pinned on cabi_post_).
		List<LispVal> program = LispReader
			.readAllFromString("(defun sumsq (a b) (* (+ a b) (+ a b))) (rontolisp:wasm-export 'sumsq"
					+ " :params '(:int :int) :returns :int) (print \"hi\")");
		byte[] component = new WasmLispCompiler(false, true).compile(program);
		assertThat(containsAscii(component, "sumsq")).isTrue();
		assertThat(containsAscii(component, "__ronto_alloc")).isFalse();
		assertThat(containsAscii(component, "cabi_post_")).isFalse();
	}

	@Test
	void componentModeLiftsStringExportThroughCanonicalStringAbi() {
		// A :string export appends the canonical string ABI helpers to
		// the core module: the core's own cabi_realloc and the shared per-signature
		// post-return (here cabi_post_i32 -- a :string result flattens to a single i32
		// return pointer, so the shim's flat result and a :int result share one kind).
		List<LispVal> program = LispReader
			.readAllFromString("(defun shout (s) (string-upcase s)) (rontolisp:wasm-export 'shout :params '(:string)"
					+ " :returns :string) (print \"hi\")");
		byte[] component = new WasmLispCompiler(false, true).compile(program);
		assertThat(containsAscii(component, "shout")).isTrue();
		assertThat(containsAscii(component, "cabi_post_i32")).isTrue();
	}

	@Test
	void componentModeStringExportKindsShareOnePostReturnPerSignature() {
		// Post-returns are shared per flat-result signature: :string + :int returns both
		// use cabi_post_i32, a :float return adds cabi_post_f64, a :void one
		// cabi_post_void.
		List<LispVal> program = LispReader.readAllFromString("""
				(defun echo (s) s)
				(defun measure (s) (length s))
				(defun ratio (s) 0.5)
				(defun sink (s) nil)
				(rontolisp:wasm-export 'echo :params '(:string) :returns :string)
				(rontolisp:wasm-export 'measure :params '(:string) :returns :int)
				(rontolisp:wasm-export 'ratio :params '(:string) :returns :float)
				(rontolisp:wasm-export 'sink :params '(:string) :returns :void)
				""");
		byte[] component = new WasmLispCompiler(false, true).compile(program);
		assertThat(containsAscii(component, "cabi_post_i32")).isTrue();
		assertThat(containsAscii(component, "cabi_post_f64")).isTrue();
		assertThat(containsAscii(component, "cabi_post_void")).isTrue();
	}

	@Test
	void parsesAsyncOption() {
		// :async t marks the component lift async; default is sync.
		assertThat(parse("(rontolisp:wasm-export 'f :params '(:int) :returns :int :async t)").async()).isTrue();
		assertThat(parse("(rontolisp:wasm-export 'f :params '(:int) :returns :int :async nil)").async()).isFalse();
		assertThat(parse("(rontolisp:wasm-export 'f :params '(:int) :returns :int)").async()).isFalse();
	}

	@Test
	void defaultsParamNamesToPositionalLabels() {
		// A WASM parameter has no name; p0, p1, ... are the labels the --component lift
		// encodes into the export's function type, and therefore what --emit-wit shows.
		assertThat(parse("(rontolisp:wasm-export 'f :params '(:int :string) :returns :int)").paramNames())
			.containsExactly("p0", "p1");
		assertThat(parse("(rontolisp:wasm-export 'now :params '() :returns :int)").paramNames()).isEmpty();
		assertThat(parse("(rontolisp:wasm-export 'now :returns :int)").paramNames()).isEmpty();
	}

	@Test
	void parsesExplicitParamNames() {
		// rontolisp:wit-export fills these in from the WIT world, which is why an
		// implemented world round-trips through --emit-wit with its own parameter names.
		assertThat(parse("(rontolisp:wasm-export 'f :params '(:string :int) :param-names '(text repeat-count)"
				+ " :returns :string)")
			.paramNames()).containsExactly("text", "repeat-count");
		// Leniently, strings name parameters too.
		assertThat(parse("(rontolisp:wasm-export 'f :params '(:string) :param-names '(\"text\") :returns :string)")
			.paramNames()).containsExactly("text");
		assertThat(parse("(rontolisp:wasm-export 'f :params '() :param-names nil :returns :int)").paramNames())
			.isEmpty();
	}

	@Test
	void rejectsParamNamesThatDoNotMatchTheParamCount() {
		assertThatThrownBy(
				() -> parse("(rontolisp:wasm-export 'f :params '(:int :int) :param-names '(a) :returns :int)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":param-names has 1 name(s) but :params has 2 type(s)");
	}

	@Test
	void rejectsAParamNameThatIsNotAComponentModelLabel() {
		// The lifted function type's parameter labels obey the same lower-kebab-case
		// grammar as an export name.
		assertThatThrownBy(
				() -> parse("(rontolisp:wasm-export 'f :params '(:int) :param-names '(\"Foo\") :returns :int)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("is not a valid component-model parameter name");
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'f :params '(:int) :param-names '(x_y) :returns :int)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("'x_y' is not a valid component-model parameter name");
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'f :params '(:int) :param-names '(:x) :returns :int)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":param-names expects symbols or strings");
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'f :params '(:int) :param-names (a) :returns :int)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":param-names expects a quoted list");
	}

	@Test
	void componentLiftsTheExportWithItsOwnParamNames() {
		// The labels ride into the component's function type, so --emit-wit prints them
		// (this
		// is what makes an implemented WIT world round-trip with its parameter names).
		List<LispVal> program = LispReader
			.readAllFromString("(defun shout (s) (string-upcase s)) (rontolisp:wasm-export 'shout :params '(:string)"
					+ " :param-names '(text) :returns :string)");
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		byte[] component = compiler.compile(program);
		assertThat(containsAscii(component, "text")).isTrue();
		assertThat(compiler.componentWit()).contains("export shout: func(text: string) -> string;");
	}

	@Test
	void defaultParamNamesAreByteIdenticalToTheExplicitPositionalOnes() {
		// The default is exactly p0, p1, ... : spelling them out changes nothing, so
		// every
		// artifact predating :param-names is unaffected by it.
		String defun = "(defun sumsq (a b) (* (+ a b) (+ a b)))";
		byte[] omitted = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(
				defun + " (rontolisp:wasm-export 'sumsq :params '(:int :int) :returns :int) (print \"hi\")"));
		byte[] explicit = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(
				defun + " (rontolisp:wasm-export 'sumsq :params '(:int :int) :param-names '(p0 p1) :returns :int)"
						+ " (print \"hi\")"));
		assertThat(explicit).isEqualTo(omitted);
	}

	@Test
	void rejectsNonBooleanAsyncValue() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'f :params '(:int) :returns :int :async :yes)"))
			.hasMessageContaining(":async expects t or nil");
	}

	@Test
	void componentModeLiftsAsyncExportWithAsyncFuncType() {
		// An :async t export lifts against an async function type (tag 0x43, the run
		// shape) instead of the sync 0x40 form -- the ONLY byte difference, so I/O inside
		// the export blocks cooperatively instead of trapping. The core module and the
		// rest of the component wiring are unchanged.
		String defun = "(defun noisy (a b) (print (+ a b)) (+ a b))";
		List<LispVal> syncProgram = LispReader.readAllFromString(
				defun + " (rontolisp:wasm-export 'noisy :params '(:int :int) :returns :int) (print \"hi\")");
		List<LispVal> asyncProgram = LispReader.readAllFromString(
				defun + " (rontolisp:wasm-export 'noisy :params '(:int :int) :returns :int :async t) (print \"hi\")");
		byte[] sync = new WasmLispCompiler(false, true).compile(syncProgram);
		byte[] async = new WasmLispCompiler(false, true).compile(asyncProgram);
		// (s32 p0, s32 p1) -> s32 golden bytes, sync (0x40...) vs async (0x43...); see
		// ComponentWriterTest.asyncFuncTypeScalarsEncoding.
		byte[] syncType = hexBytes("40020270307a0270317a007a");
		byte[] asyncType = hexBytes("43020270307a0270317a007a");
		assertThat(indexOf(sync, syncType)).isNotNegative();
		assertThat(indexOf(sync, asyncType)).isNegative();
		assertThat(indexOf(async, asyncType)).isNotNegative();
		assertThat(indexOf(async, syncType)).isNegative();
		assertThat(async).hasSameSizeAs(sync);
	}

	@Test
	void asyncNilComponentIsByteIdenticalToOmittedAsync() {
		String defun = "(defun sumsq (a b) (* (+ a b) (+ a b)))";
		byte[] omitted = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(
				defun + " (rontolisp:wasm-export 'sumsq :params '(:int :int) :returns :int) (print \"hi\")"));
		byte[] explicitNil = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(defun
				+ " (rontolisp:wasm-export 'sumsq :params '(:int :int) :returns :int :async nil) (print \"hi\")"));
		assertThat(explicitNil).isEqualTo(omitted);
	}

	@Test
	void arenaResetIsGuardedByTheInternPoolHighWater() {
		// The pop is HEAP_PTR = max(mark, RT_INTERN_HEAP) -- HEAP_PTR is a stack pointer
		// over a PERMANENT low region here (the interned-symbol byte pool _intern copies
		// into), so popping to a bare mark would dangle the intern registry's records.
		// Pin both halves: the guarded reset body is in the module, and _intern records
		// the pool's top only for the modules that export the arena.
		byte[] bytes = compile("(defun shout (s) (string-upcase s))"
				+ "(rontolisp:wasm-export 'shout :params '(:string) :returns :string)");
		assertThat(indexOf(bytes, WasmExportRuntimeBuilder.buildAllocResetBody()))
			.as("the guarded reset body is emitted")
			.isNotNegative();
		byte[] guardStore = hexBytes("41AC01"); // i32.const 172 (RT_INTERN_HEAP_ADDR)
		assertThat(indexOf(WasmReadRuntimeBuilder.buildInternBody(0, 0, true), guardStore)).isNotNegative();
		assertThat(indexOf(WasmReadRuntimeBuilder.buildInternBody(0, 0, false), guardStore)).isNegative();
	}

	@Test
	void serveHandleWrapperResetsTheCanonicalAllocatorPerRequest() {
		// `handle` (wasi:http/incoming-handler) is called once per request on a possibly
		// REUSED instance (jco / wasmCloud); mem-http-client's cabi_realloc is where the
		// host
		// writes each request's result buffers (path / headers / body) and only ever
		// grows,
		// so the wrapper resets its bump-pointer cell to the base FIRST. Without it an
		// instance-reusing host leaks ~one request per call; wasmtime serve
		// re-instantiates,
		// so it never showed. Pin the reset prologue: i32.const 0x10000
		// (CABI_HP_CELL_ADDR) ;
		// i32.const 0x10008 (CABI_HP_BASE) ; i32.store.
		byte[] reset = hexBytes("4180800441888004360200");
		byte[] serve = compileServe("""
				(defun h (env) (list 200 nil (list "ok")))
				(rontolisp:http-handler 'h)
				""");
		assertThat(indexOf(serve, reset)).as("the serve handle wrapper resets the cabi bump cell").isNotNegative();
		// Gated on serve mode: a plain --component export shares no serve-only cell, so
		// it
		// never emits the reset.
		byte[] nonServe = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(
				"(defun add (a b) (+ a b)) (rontolisp:wasm-export 'add :params '(:int :int) :returns :int)"));
		assertThat(indexOf(nonServe, reset)).as("a non-serve component never resets a serve-only cell").isNegative();
	}

	private static byte[] hexBytes(String hex) {
		byte[] out = new byte[hex.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	@Test
	void componentModeRejectsRunExportName() {
		// "run" is taken by the lifted wasi:cli/run entry; a second core export under
		// the same name would make the module invalid.
		List<LispVal> program = LispReader.readAllFromString(
				"(defun run-it (a) a) (rontolisp:wasm-export 'run-it :as \"run\" :params '(:int) :returns :int)");
		assertThatThrownBy(() -> new WasmLispCompiler(false, true).compile(program))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("collides with the component's wasi:cli/run entry");
	}

	@Test
	void componentModeRejectsNonKebabExportName() {
		// A component export name must fit the component-model label grammar; :as fixes
		// it.
		List<LispVal> program = LispReader
			.readAllFromString("(defun sum*of* (a b) (+ a b)) (rontolisp:wasm-export 'sum*of*"
					+ " :params '(:int :int) :returns :int)");
		assertThatThrownBy(() -> new WasmLispCompiler(false, true).compile(program))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("not a valid component-model export name");
	}

	@Test
	void componentCompileRecordsTheWitText() {
		// The CLI's --emit-wit output: the compiled component's typed world, with the
		// user
		// exports after the fixed run export (WitEmitter renders; templates + export
		// lines proven against wasm-tools component wit in WitOracleE2eTest).
		List<LispVal> program = LispReader.readAllFromString("""
				(defun pure-add (a b) (+ a b))
				(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'pure-add :as "add-async" :params '(:int :int) :returns :int :async t)
				""");
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		assertThat(compiler.componentWit()).isNull();
		compiler.compile(program);
		assertThat(compiler.componentWit()).contains("""
				  export wasi:cli/run@0.3.0;
				  export pure-add: func(p0: s32, p1: s32) -> s32;
				  export add-async: async func(p0: s32, p1: s32) -> s32;
				}
				""");
	}

	@Test
	void componentWitPicksTheImportVariantOfTheCompiledBlobSet() {
		// The world's fixed imports come from the ONE base variant; fetch and tcp both
		// show up as user WIT-interface imports on top of it (the http.lisp /
		// sockets.lisp splices), so the recorded WIT tracks what the program uses.
		WasmLispCompiler base = new WasmLispCompiler(false, true);
		base.compile(LispReader.readAllFromString("(print 1)"));
		assertThat(base.componentWit()).doesNotContain("wasi:http").doesNotContain("wasi:sockets");
		WasmLispCompiler http = new WasmLispCompiler(false, true);
		http.compile(am.ik.rontolisp.eval.WitLibrary.process(am.ik.rontolisp.eval.HttpLibrary.process(
				LispReader.readAllFromString("(print (rontolisp:fetch \"http://127.0.0.1:9/\"))"),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false)));
		assertThat(http.componentWit()).contains("  import wasi:http/client@0.3.0;");
		WasmLispCompiler sock = new WasmLispCompiler(false, true);
		sock.compile(am.ik.rontolisp.eval.WitLibrary.process(am.ik.rontolisp.eval.StdinLibrary.process(
				am.ik.rontolisp.eval.SocketsLibrary.process(
						LispReader.readAllFromString("(close (rontolisp:tcp-listen 7777))"),
						am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false)));
		String sockWit = java.util.Objects.requireNonNull(sock.componentWit());
		assertThat(sockWit).contains("  import wasi:sockets/types@0.3.0;");
		// stdin.lisp rides the FIXED import block (wasi:cli/stdin is already one of the
		// base world's fixed imports), so splicing it adds no import line: exactly one
		// mention, the fixed one.
		assertThat(sockWit.split("import wasi:cli/stdin@0.3.0;", -1)).hasSize(2);
	}

	@Test
	void serveComponentWitExportsTheIncomingHandlerOnly() {
		List<LispVal> loaded = am.ik.rontolisp.eval.HttpLibrary.process(LispReader.readAllFromString("""
				(defun h (env) (list 200 nil (list "x")))
				(rontolisp:http-handler 'h)
				"""), am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, true);
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded));
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, false, OptimizeLevel.NONE, true);
		compiler.compile(program);
		assertThat(compiler.componentWit()).contains("  export wasi:http/handler@0.3.0;")
			.doesNotContain("http-dispatch");
	}

	@Test
	void nonComponentCompileRecordsNoWitText() {
		List<LispVal> program = LispReader
			.readAllFromString("(defun f (a) a) (rontolisp:wasm-export 'f :params '(:int) :returns :int)");
		WasmLispCompiler compiler = new WasmLispCompiler();
		compiler.compile(program);
		assertThat(compiler.componentWit()).isNull();
	}

}
