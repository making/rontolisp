package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
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
	// WasmLispCompilerIntegrationTest.compileServeComponent, minus the wasmtime run): splice
	// fetch.lisp then serve.lisp, expand, compile in serve mode. No Docker needed for a
	// bytes-only check.
	private static byte[] compileServe(String source) {
		List<LispVal> loaded = am.ik.rontolisp.eval.WitImportInliner.inline(LispReader.readAllFromString(source), null,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT,
				am.ik.rontolisp.eval.SourceLoader.fileSystem());
		loaded = am.ik.rontolisp.eval.FetchLibrary.process(loaded,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT);
		loaded = am.ik.rontolisp.eval.ServeLibrary.process(loaded,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, true);
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded));
		return new WasmLispCompiler(false, true, false, false, true).compile(program);
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

	@Test
	void parsesScalarDirective() {
		WasmExportCompiler.Decl decl = parse("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(decl.name()).isEqualTo("fact");
		assertThat(decl.paramTypes()).containsExactly(":int");
		assertThat(decl.returnType()).isEqualTo(":int");
	}

	@Test
	void parsesMultipleParamsAndMemoryTypes() {
		WasmExportCompiler.Decl decl = parse(
				"(rontolisp:wasm-export 'concat :params '(:string :s-expr) :returns :string)");
		assertThat(decl.paramTypes()).containsExactly(":string", ":s-expr");
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
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int))").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
	}

	@Test
	void treatsExplicitVoidMarkersAsVoid() {
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns :void)").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns nil)").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns '())").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
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
			.hasMessageContaining(":widget");
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
	void rejectsLongDesignatorOnTheGcBackend() {
		// :long maps to i64, which the GC backend cannot represent (its integers are
		// i31ref); it is a --no-gc-only designator, rejected with a pointer to --no-gc.
		assertThatThrownBy(() -> compile("(defun f (a b) (* (+ a b) (+ a b)))"
				+ "(rontolisp:wasm-export 'f :params '(:long :long) :returns :long)"))
			.hasMessageContaining(":long requires --no-gc");
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
	void defaultModeKeepsWasiImports() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "wasi_snapshot_preview1")).isTrue();
	}

	@Test
	void noWasiIsIgnoredInComponentMode() {
		// Component mode has its own (lowered) import story; no-wasi must not apply.
		List<LispVal> program = LispReader.readAllFromString("(print (+ 1 2))");
		byte[] component = new WasmLispCompiler(false, true, true).compile(program);
		// The component wraps a core module that still imports the preview1-style
		// functions.
		assertThat(containsAscii(component, "wasi_snapshot_preview1")).isTrue();
	}

	@Test
	void componentModeLiftsScalarExport() {
		// A scalar export is core-exported, aliased and canonically lifted into a
		// component-model export under its name (todo 92); no memory allocator and none
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
		// A :string export (todo 92 Tier 2) appends the canonical string ABI helpers to
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
		// :async t marks the component lift async (todo 92 Tier 3); default is sync.
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
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'f :params '(:int) :param-names '(Foo) :returns :int)"))
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
		// REUSED instance (jco / wasmCloud); mem-http-client's cabi_realloc is where the host
		// writes each request's result buffers (path / headers / body) and only ever grows,
		// so the wrapper resets its bump-pointer cell to the base FIRST. Without it an
		// instance-reusing host leaks ~one request per call; wasmtime serve re-instantiates,
		// so it never showed. Pin the reset prologue: i32.const 0x10000 (CABI_HP_CELL_ADDR) ;
		// i32.const 0x10008 (CABI_HP_BASE) ; i32.store.
		byte[] reset = hexBytes("4180800441888004360200");
		byte[] serve = compileServe("""
				(defun h (r) (list :status 200 :body "ok"))
				(rontolisp:http-handler 'h)
				""");
		assertThat(indexOf(serve, reset)).as("the serve handle wrapper resets the cabi bump cell").isNotNegative();
		// Gated on serve mode: a plain --component export shares no serve-only cell, so it
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
		// The world's imports are fixed per blob variant, so the recorded WIT must
		// track the same fetch/tcp selection as the component wiring.
		WasmLispCompiler base = new WasmLispCompiler(false, true);
		base.compile(LispReader.readAllFromString("(print 1)"));
		assertThat(base.componentWit()).doesNotContain("wasi:http").doesNotContain("wasi:sockets");
		WasmLispCompiler http = new WasmLispCompiler(false, true);
		http.compile(am.ik.rontolisp.eval.WitLibrary.process(am.ik.rontolisp.eval.FetchLibrary.process(
				LispReader.readAllFromString("(print (rontolisp:fetch \"http://127.0.0.1:9/\"))"),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT)));
		assertThat(http.componentWit()).contains("  import wasi:http/outgoing-handler@0.2.0;");
		WasmLispCompiler sock = new WasmLispCompiler(false, true);
		sock.compile(LispReader.readAllFromString("(close (rontolisp:tcp-listen 7777))"));
		assertThat(sock.componentWit()).contains("  import wasi:sockets/types@0.3.0;");
	}

	@Test
	void serveComponentWitExportsTheIncomingHandlerOnly() {
		List<LispVal> loaded = am.ik.rontolisp.eval.ServeLibrary.process(LispReader.readAllFromString("""
				(defun h (r) (list :status 200 :body "x"))
				(rontolisp:http-handler 'h)
				"""), am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, true);
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded));
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, false, false, true);
		compiler.compile(program);
		assertThat(compiler.componentWit()).contains("  export wasi:http/incoming-handler@0.2.0;")
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
