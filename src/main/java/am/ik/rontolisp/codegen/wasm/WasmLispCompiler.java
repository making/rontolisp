package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.SpecialVarCollector;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.DeadTypeBranchPruner;
import am.ik.rontolisp.compiler.ToplevelStatements;
import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.CompileTimeBoundp;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.CrossLambdaExitLowering;
import am.ik.rontolisp.compiler.DesignatorSpellings;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.GlobalVarCollector;
import am.ik.rontolisp.compiler.NestedDefunRedefinition;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.rontolisp.compiler.NoWasiFilesystemStubs;
import am.ik.rontolisp.compiler.NoWasiLoadPathRefusals;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.ReactorEnvelope;
import am.ik.rontolisp.compiler.RuntimeNameProducers;
import am.ik.rontolisp.compiler.ShadowedBuiltins;
import am.ik.rontolisp.compiler.StreamDesignators;
import am.ik.rontolisp.compiler.SuspendingImports;
import am.ik.rontolisp.compiler.WasmImportDirective;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Section;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * Compiles Lisp expressions to WASM binary with wasm-GC and WASI Preview 1. All Lisp
 * values are represented as (ref eq) on the stack: integers use i31ref, nil uses ref.null
 * eq, strings use a string struct, cons cells use a cons struct, and closures use a
 * closure struct. Supports first-class functions via dispatch functions and closure
 * structs.
 */
public final class WasmLispCompiler implements LispCompiler {

	private final boolean dynamic;

	private final boolean component;

	private final boolean noWasi;

	private final OptimizeLevel optimize;

	private final boolean serve;

	private final boolean simd;

	private final boolean hostRandom;

	private final boolean hostFetch;

	private final boolean reentrant;

	/**
	 * The names the compiled program's {@code *features*} starts out holding. The WASM
	 * backend's own set unless the frontend {@link #runtimeFeatures(List) says otherwise}
	 * -- reading and running must agree on it, and only the frontend knows what it read
	 * with (a {@code --component} or {@code --no-wasi} build carries more).
	 */
	private List<String> runtimeFeatures = LispMacroExpander.backendFeatures(true);

	/**
	 * The one import a {@code --host-random} module carries: preview1's
	 * {@code random_get(buf, len) -> errno} signature exactly (so a host can forward its
	 * own WASI implementation unchanged), under the conventional host module name rather
	 * than {@code wasi_snapshot_preview1} -- the module still imports no WASI function,
	 * it imports the one host function the flag asked for.
	 */
	static final String HOST_RANDOM_MODULE = "env";

	/** The import field name of the {@code --host-random} entropy source. */
	static final String HOST_RANDOM_FIELD = "random_get";

	/**
	 * The WASI Preview 1 module name, for the imports that are APPENDED rather than
	 * declared in the twelve index-pinned fixed slots: the {@code args_sizes_get} /
	 * {@code args_get} pair {@code %host-argv} reads its vector from, bound the way
	 * {@code exit.lisp} binds {@code proc_exit} -- as an ordinary user import, so a
	 * program that never asks for them imports nothing new.
	 */
	static final String WASI_PREVIEW1_MODULE = "wasi_snapshot_preview1";

	/**
	 * Creates a new WASM compiler.
	 * <p>
	 * Compiles at {@link OptimizeLevel#DEFAULT} -- the level an absent {@code --optimize}
	 * selects, so an embedder that names no level gets what this project's own frontend
	 * gives. Declining the optimizer is asked for by name: {@link OptimizeLevel#NONE}.
	 */
	public WasmLispCompiler() {
		this(false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are not rejected at compile time but resolved at runtime against the embedded
	 * {@code eval} global environment (late binding), so a program that defines functions
	 * via {@code load} can compile without changes. This forces the {@code eval} runtime
	 * to be emitted.
	 */
	public WasmLispCompiler(boolean dynamic) {
		this(dynamic, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component when {@code true}, the output is a WASI 0.2 (Preview 2)
	 * <strong>component</strong> instead of a Preview 1 core module: the core module
	 * imports its linear memory and exports a {@code run} entry, and is wrapped by
	 * {@link WasmComponentBuilder} so it prints through {@code wasi:cli/stdout} and runs
	 * with {@code wasmtime run}. Reading and file I/O are not yet available in component
	 * mode.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component) {
		this(dynamic, component, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi when {@code true}, the output imports <strong>no</strong>
	 * {@code wasi_snapshot_preview1} functions, so a host can instantiate it with no
	 * import object (a "reactor"/library module). The nine WASI import slots (function
	 * indices 0-8) are filled with internal stubs so every fixed {@code FUNC_*} index
	 * stays valid: {@code fd_write} is a sink (print/format-t output is discarded), the
	 * other eight are {@code unreachable} traps (read/open/getenv/time/random trap).
	 * Combined with {@code component}, the output is a <strong>reactor component</strong>
	 * that imports nothing at all: the core module keeps this exact Preview 1 no-WASI
	 * contract, declares its own memory, runs its top-level forms from the core start
	 * section at instantiation (there is no {@code wasi:cli/run} export), and only the
	 * {@code (rontolisp:wasm-export ...)} functions are lifted as typed component-model
	 * exports.
	 * <p>
	 * Compiles at {@link OptimizeLevel#DEFAULT} -- the level an absent {@code --optimize}
	 * selects, so an embedder that names no level gets what this project's own frontend
	 * gives. Declining the optimizer is asked for by name: {@link OptimizeLevel#NONE}.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi) {
		this(dynamic, component, noWasi, OptimizeLevel.DEFAULT);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize what to optimize the module FOR (the CLI's {@code --optimize}).
	 * Every level but {@link OptimizeLevel#NONE} runs the emitted core module through
	 * {@link am.ik.wasm.WasmTreeShaker} -- functions unreachable from the module's roots
	 * (its exports and {@code _start}) are dropped and the survivors renumbered, in
	 * {@code component} mode too; combined with {@code noWasi} a pure-compute reactor
	 * module shrinks to a handful of functions. {@link OptimizeLevel#SIZE} additionally
	 * declines the two emissions that spend bytes on speed: integer expression-tree
	 * fusion ({@code .kb/wasm-int-fusion.md}) and unboxed dual-representation locals
	 * ({@code .kb/wasm-unboxed-locals.md}).
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize) {
		this(dynamic, component, noWasi, optimize, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel)}
	 * @param serve when {@code true} (implies {@code component}), the program serves HTTP
	 * via {@code rontolisp:http-handler}: the {@code HttpHandlerInliner} has spliced in a
	 * {@code %http-dispatch} {@code wasm-export} wrapper, so the wasm-export memory-ABI
	 * machinery is enabled even in component mode, and the core is wrapped by
	 * {@link WasmComponentBuilder#buildServe} into a {@code wasi:http/incoming-handler}
	 * component (runnable under {@code wasmtime serve} or any {@code wasi:http} 0.2 host
	 * with wasm-GC enabled, e.g. jco or wasmCloud) instead of the {@code wasi:cli/run}
	 * component.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean serve) {
		this(dynamic, component, noWasi, optimize, serve, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel)}
	 * @param serve see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean)}
	 * @param simd when {@code true} (the CLI's {@code --simd}), the vectorizable
	 * {@code vec:} kernels are intercepted at their call sites and routed to emitted v128
	 * runtime helpers ({@link WasmVecSimdRuntimeBuilder}) instead of the scalar
	 * {@code vec.lisp} defuns. This also switches the packed float-array representation:
	 * a {@code TYPE_FARRAY}'s data field then holds a {@code TYPE_VBLOCK} over an
	 * {@code (array (mut v128))} of lane groups instead of a {@code TYPE_F64ARR}/
	 * {@code TYPE_F32ARR}. Both are ordinary GC objects the engine collects; the data
	 * field is {@code (ref null eq)} either way, and the representation is fixed at
	 * compile time, so one module only ever holds one of the two. Without {@code simd}
	 * the output is byte-identical to a build of this compiler that never knew about the
	 * flag.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean serve,
			boolean simd) {
		this(dynamic, component, noWasi, optimize, serve, simd, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel)}
	 * @param serve see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean)}
	 * @param simd see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean, boolean)}
	 * @param hostRandom when {@code true} (the CLI's {@code --host-random}, which
	 * requires {@code noWasi} and rejects {@code component}), the {@code random_get} slot
	 * forwards to a single host import {@code env.random_get(buf, len) -> errno} instead
	 * of running the module-local SplitMix64 stub. The module then imports exactly that
	 * one function -- the zero-import default is unchanged, this is the opt-in -- and in
	 * exchange {@code rontolisp:random-bytes} works instead of signalling, and the
	 * module-local generator behind {@code random} is SEEDED from the host on its first
	 * draw instead of starting fixed (so a quickloaded library's {@code (random ...)}
	 * draws from an unpredictable stream without the library knowing;
	 * {@code .kb/random.md}). No {@code __ronto_seed_random} is exported, because the
	 * flag already does automatically what that hook exists for.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean serve,
			boolean simd, boolean hostRandom) {
		this(dynamic, component, noWasi, optimize, serve, simd, hostRandom, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel)}
	 * @param serve see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean)}
	 * @param simd see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean, boolean)}
	 * @param hostRandom see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean, boolean, boolean)}
	 * @param hostFetch when {@code true} (the CLI's {@code --host-fetch}, which requires
	 * {@code noWasi} and rejects {@code component}), {@code rontolisp:fetch} compiles on
	 * the reactor: the call falls through to the {@code HostFetchLibrary} splice, whose
	 * transport is one injected host import {@code env.fetch(request-json) ->
	 * response-json} riding the ordinary {@code wasm-import} machinery. The zero-import
	 * default is unchanged -- a program that never fetches gets no splice and no import.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean serve,
			boolean simd, boolean hostRandom, boolean hostFetch) {
		this(dynamic, component, noWasi, optimize, serve, simd, hostRandom, hostFetch, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel)}
	 * @param serve see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean)}
	 * @param simd see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean, boolean)}
	 * @param hostRandom see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean, boolean, boolean)}
	 * @param hostFetch see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, OptimizeLevel, boolean, boolean, boolean, boolean)}
	 * @param reentrant when {@code true} (the CLI's {@code --reentrant}), the module OWNS
	 * its per-call state and a JSPI host may OVERLAP calls into one instance instead of
	 * serialising them: the export wrappers drop the re-entry guard, every
	 * dynamically-bound special moves into a per-call task record swapped around the
	 * suspending host calls (the JVM {@code _d$} hybrid shape), and linear-memory staging
	 * that must survive a park moves off the {@code HEAP_PTR} scratch stack into recycled
	 * park blocks ({@code __ronto_park_alloc}/{@code __ronto_park_free}). What it buys is
	 * I/O overlap on one instance -- one stack still runs at a time. Requires a program
	 * that CAN suspend (an {@code :async t} import, or {@code --host-fetch} with
	 * {@code rontolisp:fetch} used) and changes the host ABI of the memory-typed
	 * boundaries (see the build's obligation lines); without the flag the output is
	 * byte-identical to a build that never knew about it
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean serve,
			boolean simd, boolean hostRandom, boolean hostFetch, boolean reentrant) {
		this.dynamic = dynamic;
		this.component = component;
		this.noWasi = noWasi;
		this.optimize = optimize;
		this.serve = serve && component;
		this.simd = simd;
		this.hostRandom = hostRandom;
		this.hostFetch = hostFetch;
		this.reentrant = reentrant;
		// A component's calls are driven by the component-model scheduler, not by JSPI,
		// and its per-call machinery (cabi marks, task records) is its own; the flag is
		// a core-module (JSPI host) contract.
		if (reentrant && component) {
			throw new UnsupportedOperationException("--reentrant cannot be combined with --component: overlapped calls "
					+ "are a JSPI (core module) host contract; a component's concurrency is the component model's");
		}
		// Late binding reads specials through the eval mirror (GLOBAL_ENV), which is one
		// per instance, not one per call -- a --dynamic module would keep the very
		// corruption the per-task store exists to remove.
		if (reentrant && dynamic) {
			throw new UnsupportedOperationException(
					"--reentrant cannot be combined with --dynamic: late-bound variable "
							+ "reads go through the eval mirror, which is per-instance state the per-task store does not cover");
		}
		// A serve component's entire surface is wasi:http -- its imports AND its
		// handler export -- so a serve build cannot promise "no WASI imports".
		if (this.serve && noWasi) {
			throw new UnsupportedOperationException(
					"--no-wasi cannot be combined with rontolisp:http-handler: a serve component's entire surface is "
							+ "wasi:http (its imports and the wasi:http/handler export), which --no-wasi excludes; "
							+ "drop --no-wasi");
		}
		// --host-random routes ONE WASI slot at a host function, so it only means
		// anything where that slot is a module-local stub in the first place.
		if (hostRandom && !noWasi) {
			throw new UnsupportedOperationException("--host-random requires --no-wasi: every other WASM build already "
					+ "draws `random` from the host's wasi_snapshot_preview1 random_get");
		}
		// The reactor component's contract is that it imports NOTHING; a lifted
		// entropy import would be a WIT world-shape decision, not a core export one.
		// Plain --component already has the host's entropy (wasi:random), so the
		// answer there is to drop --no-wasi rather than to grow the world.
		if (hostRandom && component) {
			throw new UnsupportedOperationException(
					"--host-random cannot be combined with --component: a --no-wasi reactor component imports nothing "
							+ "at all, and a plain --component build already draws `random` from wasi:random; "
							+ "drop --component (core module) or drop --no-wasi (component)");
		}
		// --host-fetch lowers fetch at a host import, so it only means anything where
		// fetch has no transport of its own in the first place.
		if (hostFetch && !noWasi) {
			throw new UnsupportedOperationException("--host-fetch requires --no-wasi: rontolisp:fetch on a "
					+ "WASI build is the component's wasi:http surface (--component), not a host import");
		}
		// Same shape as --host-random: a reactor component's contract is that it
		// imports NOTHING, and lifting a fetch import into its WIT world is a
		// world-shape decision, not a core import one; a plain --component build
		// already fetches over wasi:http.
		if (hostFetch && component) {
			throw new UnsupportedOperationException(
					"--host-fetch cannot be combined with --component: a --no-wasi reactor component imports nothing "
							+ "at all, and a plain --component build already fetches over wasi:http; "
							+ "drop --component (core module) or drop --no-wasi (component)");
		}
	}

	/**
	 * The WIT text describing the component compiled by the last {@link #compile} call
	 * (the CLI's {@code --emit-wit} output), or {@code null} before a component compile.
	 * Semantically identical to {@code wasm-tools component wit} on the emitted bytes;
	 * see {@link WitEmitter}.
	 * @return the WIT text, or {@code null} when not compiling a component
	 */
	public @Nullable String componentWit() {
		return this.componentWit;
	}

	private @Nullable String componentWit;

	/**
	 * The JavaScript host glue for the module compiled by the last {@link #compile} call
	 * (the CLI's {@code --emit-js-glue} output), or {@code null} when the last compile
	 * was not a {@code --no-wasi} core module -- the only shape a JS host instantiates
	 * itself (a component's host glue is jco's, and the boundary the glue writes does not
	 * exist there). The surface it is emitted from is the same set of derived facts the
	 * {@code :async t} obligation lines are printed from; see {@link HostGlueEmitter}.
	 * @param fileName the glue's own file name, so its usage sketch names the real import
	 * @return the ES module source, or {@code null} when there is no glue to write
	 */
	public @Nullable String hostGlueJs(String fileName) {
		HostGlueEmitter.Surface surface = this.hostGlue;
		return surface == null ? null : HostGlueEmitter.emit(fileName, surface);
	}

	private HostGlueEmitter.@Nullable Surface hostGlue;

	// Whether the module imports env.<field>. The injected boundaries all live in the one
	// `env` module (compiler/ReactorEnvelope.HOST_MODULE), and their presence is what
	// says
	// which SHAPE of the boundary was built -- read here rather than threaded down from
	// the flag, because the imports are the answer and a flag is only a request.
	private static boolean imported(java.util.Map<String, WasmImportCompiler.Decl> importWrappers, String field) {
		return importWrappers.values()
			.stream()
			.anyMatch(decl -> ReactorEnvelope.HOST_MODULE.equals(decl.module()) && field.equals(decl.field()));
	}

	// The same question asked of the PROGRAM, for the obligation lines the build prints
	// before the directives have been parsed into decls.
	private static boolean declaresImport(List<LispVal> program, String field) {
		for (LispVal form : program) {
			if (WasmImportDirective.isImportForm(form) && form instanceof LispCons cons) {
				WasmImportDirective directive = WasmImportDirective.parse(cons);
				if (ReactorEnvelope.HOST_MODULE.equals(directive.module()) && field.equals(directive.field())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The index of the first user defun: the fixed runtime helpers, plus -- under
	 * {@code --simd} only -- the {@link WasmVecSimdRuntimeBuilder} block and the
	 * {@link WasmLinalgSimdRuntimeBuilder} one after it, plus the extra per-arity
	 * dispatchers {@link #extraCallArity} asked for. Every fixed {@code FUNC_*} constant
	 * below {@link #FUNC_USER_BASE} keeps its value in every mode; only what follows
	 * shifts, and only when one of those conditional blocks is present.
	 */
	int userFuncBase() {
		return extraDispatchFuncBase() + this.extraCallArity;
	}

	/**
	 * The index of {@code _dispatch_11}, the first EXTRA per-arity dispatcher: right
	 * after the {@code --simd} block and whichever of the async / P1-stream blocks this
	 * module has, so adding one moves no fixed index -- only {@link #userFuncBase()},
	 * which every consumer already reads dynamically. Only meaningful when
	 * {@link #extraCallArity} is non-zero.
	 */
	private int extraDispatchFuncBase() {
		return p1StreamFuncBase() + (this.usesP1Streams ? WasmP1StreamRuntimeBuilder.FUNC_COUNT : 0);
	}

	/**
	 * The index of {@code _p1_stream_read}: right after the async block, which cannot be
	 * present at the same time ({@link #usesP1Streams} is a non-asyncMode gate), so the
	 * two blocks share the slot and neither moves a fixed index. Only meaningful when
	 * {@link #usesP1Streams} is set.
	 */
	private int p1StreamFuncBase() {
		return asyncFuncBase() + (this.asyncMode ? WasmFutureRuntimeBuilder.FUNC_COUNT : 0);
	}

	/**
	 * The index of the first async-runtime function ({@code _future_new}), right after
	 * the {@code --simd} block. The block itself is emitted only in {@code asyncMode}
	 * (the program uses async-defun/async-lambda/await under {@code --component}); every
	 * other module is byte-identical to a build that never knew about it.
	 */
	private int asyncFuncBase() {
		return FUNC_VEC_BASE
				+ (this.simd ? WasmVecSimdRuntimeBuilder.FUNC_COUNT + WasmLinalgSimdRuntimeBuilder.FUNC_COUNT : 0);
	}

	/**
	 * The index of {@code TYPE_FUTURE} ({@code TYPE_ASYNC_FRAME} follows it), appended
	 * after the fixed and {@code --simd} types in {@code asyncMode} only.
	 */
	private int asyncTypeBase() {
		return SCHUB_TYPE_LAST + 1 + (this.simd ? SIMD_TYPE_COUNT : 0);
	}

	/**
	 * The index of the first {@code linalg:} kernel: right after the {@code vec:} block.
	 * Only meaningful under {@code --simd}.
	 */
	static int linalgFuncBase() {
		return FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.FUNC_COUNT;
	}

	/**
	 * The number of type entries this module emits before the export/import wrapper
	 * signatures: the fixed types through {@code TYPE_F32ARR}, plus -- under
	 * {@code --simd} only -- the {@link WasmVecSimdRuntimeBuilder} block's four. The same
	 * conditional-index trick as {@link #userFuncBase()}: without {@code --simd} the
	 * output is byte-identical to a build that never knew about the flag.
	 * @return the index of the first export wrapper type
	 */
	private int fixedTypeCount() {
		return extraCallableTypeBase() + this.extraCallArity;
	}

	/**
	 * The index of the {@code callable_arity_11} signature, the first EXTRA callable
	 * type: right after the fixed, {@code --simd}, async / P1-stream and instance types,
	 * so adding one moves no existing type index. Only meaningful when
	 * {@link #extraCallArity} is non-zero.
	 */
	private int extraCallableTypeBase() {
		return instanceTypeBase() + (this.usesInstances ? INSTANCE_TYPE_COUNT : 0);
	}

	/**
	 * The index of {@code TYPE_INSTANCE}: right after the fixed types, the {@code --simd}
	 * block and the async block, so adding it moves no existing type index. Only
	 * meaningful when the program uses an instance primitive.
	 * @return the index the instance struct would occupy
	 */
	private int instanceTypeBase() {
		return p1StreamTypeBase() + (this.usesP1Streams ? 1 : 0);
	}

	/**
	 * The index of {@code TYPE_P1_STREAM}: right after the fixed types, the
	 * {@code --simd} block and the async block -- which cannot be present at the same
	 * time, so the two share the slot -- and before the instance type, so adding it moves
	 * no existing type index. Only meaningful when {@link #usesP1Streams} is set.
	 */
	private int p1StreamTypeBase() {
		return asyncTypeBase() + (this.asyncMode ? ASYNC_TYPE_COUNT : 0);
	}

	/**
	 * Whether this compilation runs the {@code --component} async state machines: the
	 * program uses {@code rontolisp:async-defun}/{@code async-lambda}/{@code await}.
	 * Forces EH mode (the entry's reject path and the rejected-await re-signal throw on
	 * the {@code $lisp-cond} tag), so an async component needs
	 * {@code wasmtime -W exceptions=y}.
	 */
	private boolean asyncMode;

	/**
	 * Whether the program calls one of the six {@code %obj-*} instance primitives. It
	 * adds ONE type entry and the layout blob and nothing else -- no function index moves
	 * -- so a program without them is byte-identical to a build that never knew about
	 * instances, the same conditional-index discipline {@code --simd} and async use.
	 */
	private boolean usesInstances;

	/**
	 * Whether the program writes {@code (make-hash-table :test 'equalp)} somewhere. It
	 * adds one {@code (mut i32)} global and one real body in the fixed function slot
	 * every module already carries, and tags the header count with the fold flag -- so a
	 * program without it is byte-identical to a build that never knew about the fold.
	 */
	private boolean usesEqualpHashTables;

	/**
	 * Whether the degenerate (non-asyncMode) tier's first-class stream value can exist in
	 * this module: the program names {@code rontolisp::%stream-new}, its one producer.
	 * Adds ONE type entry ({@code TYPE_P1_STREAM}) and the two-function
	 * {@link WasmP1StreamRuntimeBuilder} block, both in the slots asyncMode would have
	 * used -- the two modes are mutually exclusive -- so a program without streams is
	 * byte-identical to a build that never knew about them.
	 */
	private boolean usesP1Streams;

	/**
	 * How many per-arity dispatchers past {@link #MAX_CALLABLE_ARITY} this program asks
	 * for: {@code 0} unless a call site is wider than the fixed block, and never more
	 * than {@link #MAX_EXTRA_CALL_ARITY}. Derived in {@link #compile} from
	 * {@link WasmArityBundler#widestDispatchArity}, before the pass that would otherwise
	 * spread that site through {@code apply}. Each one appends a dispatcher after the
	 * async block and a callable type after the instance block -- the same
	 * conditional-index discipline as {@code --simd}, async and instances, so a program
	 * whose widest call fits the fixed block is byte-identical to a build that never knew
	 * about the extra tier.
	 */
	private int extraCallArity;

	/**
	 * The widest call this module can make through a per-arity dispatcher -- a
	 * {@code funcall}'s argument count or a {@code mapcar}/{@code mapc}/{@code mapcan}'s
	 * list count. A wider {@code funcall} is rewritten into {@code apply}, whose SPREAD
	 * dispatcher takes the arguments as one list
	 * ({@link WasmArityBundler#spreadOverArityFuncalls}); a wider map site is a compile
	 * error ({@link #mapDispatchFuncIndex}).
	 */
	int callArityCeiling() {
		return MAX_CALLABLE_ARITY + this.extraCallArity;
	}

	/**
	 * The dispatcher for a {@code funcall} of {@code arity} arguments: the fixed block's
	 * entry up to {@link #MAX_CALLABLE_ARITY}, else the appended one.
	 * @param arity the argument count, at most {@link #callArityCeiling()}
	 * @param extraBase the value of {@code extraDispatchFuncBase()} for this module
	 * @return the function index to call
	 */
	static int dispatchFuncIndex(int arity, int extraBase) {
		return arity <= MAX_CALLABLE_ARITY ? FUNC_DISPATCH_BASE + arity : extraBase + (arity - MAX_CALLABLE_ARITY - 1);
	}

	/**
	 * As {@link #dispatchFuncIndex}, for an operator that funcalls its argument once per
	 * element of each of {@code arity} lists (the {@code mapcar}/{@code mapc}/
	 * {@code mapcan} family). Past the ceiling there is no per-site fallback -- unlike
	 * {@code funcall}, which has the call-time signal -- and an out-of-range index would
	 * silently call the NEXT runtime helper and emit a module that does not validate, so
	 * the site is a compile error instead.
	 * @param operator the operator name, for the message
	 * @param arity the number of mapped lists
	 * @param ctx the compilation context, whose {@code indirectCallArities} the arity
	 * joins
	 * @return the function index to call
	 */
	static int mapDispatchFuncIndex(String operator, int arity, Ctx ctx) {
		if (arity > ctx.callArityCeiling) {
			throw new UnsupportedOperationException(
					operator + " over " + arity + " lists exceeds the WASM backend's dispatch limit of "
							+ ctx.callArityCeiling + " (bundle the extra lists, or map over a list of lists)");
		}
		ctx.indirectCallArities.add(arity);
		return dispatchFuncIndex(arity, ctx.extraDispatchFuncBase);
	}

	/**
	 * Test hook: export names (beyond serve's {@code handle}) given the CALLBACK-lift
	 * treatment -- the wrapper's core signature gains the trailing packed-code i32, a
	 * pending target future turns the call into a live callback task instead of driving
	 * the blocking event loop, and the module imports the {@code $sched} built-ins and
	 * core-exports {@code async_cb}. Lets the integration test hand-assemble a component
	 * that runs the doorbell/context machinery with plain scalar exports (no wasi:http
	 * host needed). Never set on a CLI path.
	 */
	Set<String> callbackExportsForTest = Set.of();

	/**
	 * Test hook: when {@code true}, {@link #compile} returns the raw (import-injected)
	 * core module instead of wrapping it into a component -- the caller assembles its own
	 * component around it (the callback-probe integration test).
	 */
	boolean rawCoreForTest = false;

	// Function indices (imports come first). Defined functions are indexed relative to
	// IMPORT_FUNC_COUNT so adding an imported function only requires adding its constant
	// and bumping the count -- every defined function shifts automatically.
	static final int FUNC_FD_WRITE = 0; // imported

	static final int FUNC_FD_READ = 1; // imported

	static final int FUNC_PATH_OPEN = 2; // imported (for load/open)

	static final int FUNC_FD_CLOSE = 3; // imported (for close)

	// WASI entropy source imported in both modes: Preview 1 mode binds the real
	// wasi_snapshot_preview1 random_get; component mode binds the adapter's random_get,
	// which draws from wasi:random. Importing it in both modes keeps the import count --
	// and the defined function indices below -- identical across modes. Two callers, both
	// in WasmRandomCompiler: rontolisp::%random-byte spends one call PER BYTE (it
	// promises cryptographic entropy), and `random` spends one per INSTANCE, to seed the
	// generator it then draws from in-module (.kb/random.md).
	static final int FUNC_RANDOM_GET = 4; // imported

	// WASI clock and environment imported in both modes: Preview 1 binds the real
	// wasi_snapshot_preview1 functions; component mode binds the adapter's
	// implementations on top of wasi:clocks / wasi:cli-environment.
	static final int FUNC_CLOCK_TIME_GET = 5; // imported

	static final int FUNC_ENVIRON_SIZES_GET = 6; // imported

	static final int FUNC_ENVIRON_GET = 7; // imported

	// The directory-LISTING import, in both modes like the four above: Preview 1 binds
	// the real wasi_snapshot_preview1 fd_readdir, component mode the adapter's
	// implementation over wasi:filesystem's read-directory. It is the ONE call rontolisp
	// cannot express with path_open/fd_read, and the ninth slot is what
	// %list-directory -- and therefore `directory` and the uiop: spellings -- run on.
	static final int FUNC_FD_READDIR = 8; // imported

	// The preopen TABLE, in both modes like the five above: without it fd 3 is the only
	// directory a path can be resolved against, which makes every ABSOLUTE runtime path
	// unopenable (a host that maps `/` still rejects "/tmp/x" relative to the fd, and
	// nothing here can learn that the fd IS "/"). fd_prestat_get answers a preopened
	// fd's name LENGTH (and EBADF for the first fd that is not preopened, which is what
	// ends the walk); fd_prestat_dir_name answers the name itself. _path_dirfd walks the
	// pair and picks the longest matching prefix -- see WasmIoRuntimeBuilder.
	static final int FUNC_FD_PRESTAT_GET = 9; // imported

	static final int FUNC_FD_PRESTAT_DIR_NAME = 10; // imported

	// The SIZE call, in both modes like the eleven above: preview1 binds the real
	// wasi_snapshot_preview1 fd_filestat_get, component mode the adapter's
	// implementation over wasi:filesystem's descriptor.stat. It is what file-length
	// answers from -- a file's length is a fact about the file that no sequence of
	// path_open / fd_read can establish without reading the whole thing.
	static final int FUNC_FD_FILESTAT_GET = 11; // imported

	/** Number of preview1-style imported functions (fd_write..fd_filestat_get). */
	static final int IMPORT_FUNC_COUNT = 12;

	static final int FUNC_START = IMPORT_FUNC_COUNT; // 12

	static final int FUNC_PRINT_I32 = FUNC_START + 1;

	static final int FUNC_WRITE_STR = FUNC_PRINT_I32 + 1;

	static final int FUNC_PRINT_VAL = FUNC_WRITE_STR + 1;

	static final int FUNC_PRINT_I32_NO_NL = FUNC_PRINT_VAL + 1;

	static final int FUNC_PRINT_F64 = FUNC_PRINT_I32_NO_NL + 1;

	static final int FUNC_PRINT_F64_NO_NL = FUNC_PRINT_F64 + 1;

	// The Schubfach shortest-decimal runtime behind the float printers (todo-431):
	// digit selection identical to Double.toString/Float.toString, so all four
	// backends print byte-identical float text. Bodies in WasmSchubfachRuntimeBuilder,
	// tables in SchubfachTables.
	static final int FUNC_SCHUB_UMULHI = FUNC_PRINT_F64_NO_NL + 1;

	static final int FUNC_SCHUB_G = FUNC_SCHUB_UMULHI + 1;

	static final int FUNC_SCHUB_ROP = FUNC_SCHUB_G + 1;

	static final int FUNC_F64_DEC = FUNC_SCHUB_ROP + 1;

	static final int FUNC_F32_DEC = FUNC_F64_DEC + 1;

	static final int FUNC_DEC_FMT = FUNC_F32_DEC + 1;

	static final int FUNC_WRITE_DEC = FUNC_DEC_FMT + 1;

	// Prints a single-float (a packed #f array element) at its f32 width.
	static final int FUNC_PRINT_F32_NO_NL = FUNC_WRITE_DEC + 1;

	static final int FUNC_APPEND = FUNC_PRINT_F32_NO_NL + 1;

	static final int FUNC_READ_LINE = FUNC_APPEND + 1;

	static final int FUNC_PRINC_VAL = FUNC_READ_LINE + 1;

	static final int FUNC_LOOKUP = FUNC_PRINC_VAL + 1;

	static final int FUNC_ENV_LOOKUP = FUNC_LOOKUP + 1;

	static final int FUNC_EVAL = FUNC_ENV_LOOKUP + 1;

	static final int FUNC_APPLY = FUNC_EVAL + 1;

	static final int FUNC_STORE = FUNC_APPLY + 1;

	// Reader runtime (for read/load); always present (stubs when unused) to keep indices
	// stable.
	static final int FUNC_INTERN = FUNC_STORE + 1;

	static final int FUNC_READ_EXPR = FUNC_INTERN + 1;

	static final int FUNC_READ_LIST = FUNC_READ_EXPR + 1;

	static final int FUNC_READ = FUNC_READ_LIST + 1;

	static final int FUNC_LOAD = FUNC_READ + 1;

	// Rational (ratio) runtime: always present. _rat_new normalizes and constructs,
	// _rat_num/_rat_den read components (treating an i31 integer as value/1), and the
	// arithmetic helpers dispatch between the i31 fast path and exact ratio arithmetic.
	static final int FUNC_RAT_NEW = FUNC_LOAD + 1;

	static final int FUNC_RAT_NUM = FUNC_RAT_NEW + 1;

	static final int FUNC_RAT_DEN = FUNC_RAT_NUM + 1;

	static final int FUNC_RAT_ADD = FUNC_RAT_DEN + 1;

	static final int FUNC_RAT_SUB = FUNC_RAT_ADD + 1;

	static final int FUNC_RAT_MUL = FUNC_RAT_SUB + 1;

	static final int FUNC_RAT_DIV = FUNC_RAT_MUL + 1;

	static final int FUNC_RAT_CMP = FUNC_RAT_DIV + 1;

	// The comparison as a bitmask (1 = lt, 2 = eq, 4 = gt, 0 = unordered): the numeric
	// comparison operators AND the mask they accept, so a NaN operand fails every one
	// of = < > <= >= -- which _rat_cmp's -1/0/1 signum cannot express (todo-108).
	static final int FUNC_RAT_CMP_BITS = FUNC_RAT_CMP + 1;

	static final int FUNC_RAT_TRUNC = FUNC_RAT_CMP_BITS + 1;

	static final int FUNC_RAT_FLOOR = FUNC_RAT_TRUNC + 1;

	static final int FUNC_RAT_CEIL = FUNC_RAT_FLOOR + 1;

	static final int FUNC_RAT_ROUND = FUNC_RAT_CEIL + 1;

	// String runtime: render a value into the heap via the capture mode of _write_str
	// and return a new string struct (princ-to-string / prin1-to-string /
	// %string-concat).
	static final int FUNC_PRINC_TO_STR = FUNC_RAT_ROUND + 1;

	static final int FUNC_PRIN1_TO_STR = FUNC_PRINC_TO_STR + 1;

	static final int FUNC_STRING_CONCAT = FUNC_PRIN1_TO_STR + 1;

	// String runtime: produce/compare strings (string-upcase/downcase/capitalize, subseq,
	// string=/string-equal, string-trim family).
	static final int FUNC_STRING_UPCASE = FUNC_STRING_CONCAT + 1;

	static final int FUNC_STRING_DOWNCASE = FUNC_STRING_UPCASE + 1;

	static final int FUNC_STRING_CAPITALIZE = FUNC_STRING_DOWNCASE + 1;

	static final int FUNC_SUBSEQ = FUNC_STRING_CAPITALIZE + 1;

	static final int FUNC_STRING_EQ = FUNC_SUBSEQ + 1;

	static final int FUNC_STRING_EQUAL = FUNC_STRING_EQ + 1;

	static final int FUNC_STRING_TRIM = FUNC_STRING_EQUAL + 1;

	// File-stream runtime: open a file via path_open (the stream handle is the WASI
	// file descriptor boxed as an i31 integer), close it via fd_close, and write a
	// line to it via fd_write.
	static final int FUNC_OPEN = FUNC_STRING_TRIM + 1;

	static final int FUNC_CLOSE = FUNC_OPEN + 1;

	static final int FUNC_WRITE_LINE = FUNC_CLOSE + 1;

	// Structural equality (equal): recursively compares cons cells; always present.
	static final int FUNC_EQUAL = FUNC_WRITE_LINE + 1;

	// getenv: scans the WASI environ buffer for a variable; always present.
	static final int FUNC_GETENV = FUNC_EQUAL + 1;

	static final int FUNC_DISPATCH_BASE = FUNC_GETENV + 1;

	// The per-arity dispatchers the FIXED block carries (FUNC_DISPATCH_BASE + 0..10), and
	// with them the widest signature a defun/lambda may physically have. It is an index
	// ORIGIN, not merely a limit -- FUNC_DISPATCH_SPREAD and every FUNC_* after it, plus
	// every type index after TYPE_CALLABLE_BASE + 10, are defined off it -- so raising it
	// would move indices in EVERY module. A call site wider than this gets its dispatcher
	// APPENDED instead (extraCallArity), which moves nothing.
	static final int MAX_CALLABLE_ARITY = 10;

	// How far past MAX_CALLABLE_ARITY a call site may pull its own per-arity dispatcher
	// in before the SPREAD dispatcher becomes the better answer. Evidence: across the 122
	// systems of a populated Quicklisp cache (1,886 files) the widest funcall is uiop's
	// 13-argument (funcall 'ensure-pathname p :namestring ... :on-error nil), and chipz's
	// inflate call is 11; every program measured needs ONE extra arity, so four is
	// headroom rather than a fit. Past it the ladders would outgrow the one function that
	// serves every arity, so the program keeps the old ceiling and spreads instead.
	static final int MAX_EXTRA_CALL_ARITY = 4;

	// The SPREAD dispatcher: one function over every callable, taking the argument list
	// as a single cons list (the arity-1 signature). _apply calls it, because the
	// per-arity dispatchers take one WASM parameter per Lisp argument and so stop at
	// MAX_CALLABLE_ARITY -- an apply through a computed designator past that used to
	// trap. Always declared (the body is unreachable without the eval runtime).
	static final int FUNC_DISPATCH_SPREAD = FUNC_DISPATCH_BASE + MAX_CALLABLE_ARITY + 1;

	// Plist runtime helper (always emitted, just after the dispatch functions): look up
	// a plist key by interned offset. The component import compiler uses it to lower a
	// record parameter written as a keyword plist.
	static final int FUNC_PLIST_GET = FUNC_DISPATCH_SPREAD + 1;

	// Structural hash (agrees with _equal): walks conses and folds i31 ints / interned
	// string offsets / char codes / float bits / ratio components into an i32. Always
	// present; only the hash-table compiler references it. Equal keys hash equal.
	static final int FUNC_HASH = FUNC_PLIST_GET + 1;

	// Rehash helper: grows a hash table's bucket array (doubling capacity) and
	// redistributes its entries. Always present; called by puthash when the load factor
	// is exceeded.
	static final int FUNC_HASH_RESIZE = FUNC_HASH + 1;

	// Modulo / remainder runtime: dispatch on operand type (i31 fast path, exact ratio,
	// or f64 for a float operand) so mod/rem work on floats reaching them through a
	// variable. Appended here (after the hash helpers, before FUNC_USER_BASE) so no
	// import/FUNC_START index shifts and the component blobs are unaffected. Always
	// present; only the mod/rem compiler references them.
	static final int FUNC_RAT_REM = FUNC_HASH_RESIZE + 1;

	static final int FUNC_RAT_MOD = FUNC_RAT_REM + 1;

	// gensym runtime: bumps the counter at GENSYM_CTR_ADDR and builds the fresh symbol
	// name (prefix bytes + decimal digits) as a new heap string. Appended before
	// FUNC_USER_BASE like the mod/rem helpers, so no import/FUNC_START index shifts and
	// the component blobs are unaffected. Always present; only the gensym compiler
	// references it.
	static final int FUNC_GENSYM = FUNC_RAT_MOD + 1;

	// _p1_future_await ((ref null eq)) -> (ref null eq): the generic rontolisp:await
	// resolver (WasmP1FutureRuntimeBuilder). Recursive, so it is a real function rather
	// than inline code at each await site.
	static final int FUNC_P1_FUTURE_AWAIT = FUNC_GENSYM + 1;

	// Binary stream runtime: read-byte / write-byte move one raw byte through the
	// BYTE_SCRATCH_ADDR scratch cell via fd_read / fd_write (no quote framing, no
	// newline). Appended before FUNC_USER_BASE like the mod/rem helpers, so no
	// import/FUNC_START index shifts and the component blobs are unaffected.
	static final int FUNC_READ_BYTE = FUNC_P1_FUTURE_AWAIT + 1;

	static final int FUNC_WRITE_BYTE = FUNC_READ_BYTE + 1;

	// String-stream runtime (with-output-to-string / with-input-from-string): a string
	// stream is a NEGATIVE i31 handle whose absolute value is the address of a record in
	// linear memory (a real WASI fd is never negative). An output record heads a chunk
	// list referencing existing string bytes (the bump allocator never moves them); an
	// input record holds a cursor/end byte range. Appended before FUNC_USER_BASE like the
	// mod/rem helpers, so no import/FUNC_START index shifts and the component blobs are
	// unaffected. See WasmStringStreamRuntimeBuilder.
	static final int FUNC_WRITE_STREAM_STR = FUNC_WRITE_BYTE + 1;

	static final int FUNC_MAKE_STR_OSTREAM = FUNC_WRITE_STREAM_STR + 1;

	static final int FUNC_MAKE_STR_ISTREAM = FUNC_MAKE_STR_OSTREAM + 1;

	static final int FUNC_STR_STREAM_CONTENTS = FUNC_MAKE_STR_ISTREAM + 1;

	// Symbol runtime API helpers (WasmSymbolApiRuntimeBuilder), all unary
	// ((ref null eq)) -> (ref null eq): _make_symbol copies "#:" + the string content to
	// a fresh heap symbol, _intern_sym canonicalizes the content range through _intern so
	// the result's offset matches literals in env lookups, _boundp/_symbol_value probe
	// the eval global env mirror (GLOBAL_ENV), _fboundp probes GLOBAL_FENV then the
	// compiled-function registry (_lookup). Appended before FUNC_USER_BASE like the
	// mod/rem helpers, so no import/FUNC_START index shifts and the component blobs are
	// unaffected. Always present; boundp/fboundp/symbol-value force usesEval so the
	// _env_lookup/_lookup bodies they call are real, and intern forces the real _intern
	// body.
	static final int FUNC_MAKE_SYMBOL = FUNC_STR_STREAM_CONTENTS + 1;

	static final int FUNC_INTERN_SYM = FUNC_MAKE_SYMBOL + 1;

	static final int FUNC_BOUNDP = FUNC_INTERN_SYM + 1;

	static final int FUNC_SYMBOL_VALUE = FUNC_BOUNDP + 1;

	static final int FUNC_FBOUNDP = FUNC_SYMBOL_VALUE + 1;

	static final int FUNC_FMAKUNBOUND = FUNC_FBOUNDP + 1;

	// _set_symbol_function(sym, value): the write-side twin of _fmakunbound behind
	// (setf (symbol-function ...)); _fenv_function(sym): the GLOBAL_FENV-only read the
	// setf-only-alias forwarder defuns call. Appended beside the other symbol-API
	// helpers, before FUNC_USER_BASE.
	static final int FUNC_SET_SYMBOL_FUNCTION = FUNC_FMAKUNBOUND + 1;

	static final int FUNC_FENV_FUNCTION = FUNC_SET_SYMBOL_FUNCTION + 1;

	// read-char runtime helper (WasmIoRuntimeBuilder.buildReadCharBody): one byte from
	// stdin, a WASI fd or a string input stream, boxed as a character struct. Appended
	// before FUNC_USER_BASE like the mod/rem helpers, so no import/FUNC_START index
	// shifts and the component blobs are unaffected.
	static final int FUNC_READ_CHAR = FUNC_FENV_FUNCTION + 1;

	// _str_build (off, len) -> (ref null eq): allocates a $str_bytes GC array of
	// length len, copies linear[off..off+len) into it, and returns a TYPE_STRING
	// {id=off, len, data=arr}. Every compiled string/symbol build calls this (it has
	// the same (i32,i32)->ref stack shape the old 2-field struct.new had), so string
	// bytes live on the GC heap. Fixed index just before FUNC_USER_BASE; the component
	// embeds the core opaquely and binds it by export name, so shifting the user/lambda
	// indices below is safe.
	static final int FUNC_STR_BUILD = FUNC_READ_CHAR + 1;

	// _str_fresh (off, len) -> (ref null eq): like _str_build, but stamps a fresh id
	// from the monotonic STRING_ID_CTR counter instead of id=off. Every RUNTIME string
	// build (concatenate/subseq/case/trim, read, gensym/make-symbol, getenv, the
	// capture path, string-stream contents, the host :string boundary) calls this. Its
	// scratch offset is reused across builds (HEAP_PTR is a stack pointer now), so a
	// counter id -- not the reused offset -- is what keeps distinct runtime strings and
	// uninterned symbols eq-distinct. Interned names (literals, _intern'd symbols, t)
	// keep calling _str_build (id=stable offset). Same (i32,i32)->ref shape.
	static final int FUNC_STR_FRESH = FUNC_STR_BUILD + 1;

	// _str_to_mem (str, ptr) -> i32 (TYPE_STR_TO_MEM): copies a string's GC byte array
	// into linear[ptr..) and returns its length. See the type comment.
	static final int FUNC_STR_TO_MEM = FUNC_STR_FRESH + 1;

	// _write_str_gc (str, from, to, esc) -> () (TYPE_WRITE_STR_GC): prints bytes
	// [from, to) of a string value straight from its GC array; esc = 1 frames the range
	// in quotes and escapes the embedded " / \. See the type comment.
	static final int FUNC_WRITE_STR_GC = FUNC_STR_TO_MEM + 1;

	// _charvec_to_str (v) -> (ref null eq): normalizes a mutable character vector (the
	// general array shape holding TYPE_CHAR elements, marked by meta offset i31 == 1)
	// into the equivalent quote-framed runtime string (built via _str_fresh); any other
	// value passes through unchanged. Called by the string consumers (char, subseq,
	// string=/-equal, case/trim/concat, write-string, read-from-string, intern,
	// make-symbol) and at the entry of _equal/_hash/_print_val/_princ_val so a character
	// vector behaves as a string everywhere. Each TYPE_CHAR code point is written as its
	// UTF-8 encoding (1-4 bytes) so the WASM string byte model can carry the full Unicode
	// range without truncation; consumers that treat the byte array as an ASCII character
	// index (char/schar/length/subseq) walk the UTF-8 through _str_char_count /
	// _str_char_at. Reuses the unary TYPE_CALLABLE_BASE + 0 signature, so no new type
	// entry. The SHAPE half is _charvec_p's (below): this function renders, and asks that
	// one whether there is anything to render.
	static final int FUNC_CHARVEC_TO_STR = FUNC_WRITE_STR_GC + 1;

	// _charvec_p (v) -> i32: 1 when v is a mutable character vector, else 0 -- the shape
	// half of _charvec_to_str, split out so a PREDICATE need not render. Constant time:
	// the eight ref.tests down to the meta-offset marker (i31 == 1) and nothing else, no
	// allocation, no element walk. `stringp` is the whole reason it exists (its TYPE_CELL
	// arm used to answer by rendering the vector and throwing the string away, so
	// (stringp s) was O(length s) on every call), and _charvec_to_str calls it too, so
	// the marker invariant has exactly one owner. Reuses the ((ref null eq)) -> i32
	// signature (TYPE_RAT_GET), so no new type entry.
	static final int FUNC_CHARVEC_P = FUNC_CHARVEC_TO_STR + 1;

	// _str_char_count (str) -> i32: the number of Unicode characters in a
	// UTF-8-encoded string (its content walk minus the surrounding quotes, counting one
	// per lead byte -- byte with `(b & 0xC0) != 0x80`). The character-based length of
	// TYPE_STRING; every WasmLengthCompiler string arm reads through it. Reuses the
	// ((ref null eq)) -> i32 signature (TYPE_RAT_GET), so no new type entry.
	static final int FUNC_STR_CHAR_COUNT = FUNC_CHARVEC_P + 1;

	// _str_char_at (str, i) -> i32: the i-th character's Unicode code point in a
	// UTF-8-encoded string, from a byte walk that skips continuation bytes and decodes
	// the 1-4 byte sequence at the matched position. The character indexed accessor
	// every `char` / `schar` lowering calls (the caller boxes the returned i32 as a
	// TYPE_CHAR struct). Reuses the ((ref null eq), i32) -> i32 signature
	// (TYPE_STR_TO_MEM), so no new type entry.
	static final int FUNC_STR_CHAR_AT = FUNC_STR_CHAR_COUNT + 1;

	// _str_char_byte_offset (str, i) -> i32: the byte offset within the string's data
	// array where the i-th character's UTF-8 sequence starts. If i is greater than or
	// equal to the character count, returns len - 1 (the position of the closing quote)
	// so a subseq end walk lands on the string terminator. The compile-time subseq
	// helper reads it twice (for start and end) to translate character indices to byte
	// ranges. Reuses the ((ref null eq), i32) -> i32 signature (TYPE_STR_TO_MEM), so no
	// new type entry.
	static final int FUNC_STR_CHAR_BYTE_OFFSET = FUNC_STR_CHAR_AT + 1;

	// _char_upcase (cp) -> cp: binary-search a compressed (from, to, delta) range table
	// backed by Character.toUpperCase(int) and return the folded code point, or cp
	// unchanged when no range covers it. Every (char-upcase ch) call routes through it
	// (the caller boxes the returned i32 as a TYPE_CHAR struct). Reuses (i32) -> i32
	// (TYPE_LOOKUP), so no new type entry.
	static final int FUNC_CHAR_UPCASE = FUNC_STR_CHAR_BYTE_OFFSET + 1;

	// _char_downcase (cp) -> cp: the same shape as FUNC_CHAR_UPCASE but backed by
	// Character.toLowerCase(int). Every (char-downcase ch) call routes through it.
	static final int FUNC_CHAR_DOWNCASE = FUNC_CHAR_UPCASE + 1;

	// _char_alnum_p (cp) -> 0/1: the same binary search over a (from, to) PAIR table
	// backed by Character.isLetterOrDigit(int), answering membership instead of a fold.
	// _string_capitalize finds its word boundaries with it, so the WASM word constituents
	// are the full-Unicode letter-or-digit set the other backends use.
	static final int FUNC_CHAR_ALNUM_P = FUNC_CHAR_DOWNCASE + 1;

	// The reader's # dispatch helpers (WasmReadRuntimeBuilder): the frontend lexer's
	// dispatch set mirrored into the emitted runtime reader -- character literals,
	// radix integers, bit vectors, #(...)/#nA(...) arrays, #f(/#d( packed floats and
	// #S(...) structure literals, plus the shared token/list walkers they use. Appended
	// before FUNC_VEC_BASE/FUNC_USER_BASE like the mod/rem helpers, so no
	// import/FUNC_START index shifts and the component blobs are unaffected; stubs when
	// the program does not read.
	static final int FUNC_RD_CHARLIT = FUNC_CHAR_ALNUM_P + 1;

	static final int FUNC_RD_RADIX = FUNC_RD_CHARLIT + 1;

	static final int FUNC_RD_BITS = FUNC_RD_RADIX + 1;

	static final int FUNC_RD_ARRAYN = FUNC_RD_BITS + 1;

	static final int FUNC_RD_PACKED = FUNC_RD_ARRAYN + 1;

	static final int FUNC_RD_STRUCT = FUNC_RD_PACKED + 1;

	static final int FUNC_RD_TOKEN = FUNC_RD_STRUCT + 1;

	static final int FUNC_RD_LEN = FUNC_RD_TOKEN + 1;

	static final int FUNC_RD_LEVEL = FUNC_RD_LEN + 1;

	static final int FUNC_RD_DIMS = FUNC_RD_LEVEL + 1;

	static final int FUNC_RD_FLAT = FUNC_RD_DIMS + 1;

	static final int FUNC_RD_INFER = FUNC_RD_FLAT + 1;

	static final int FUNC_RD_MEMEQ = FUNC_RD_INFER + 1;

	// The boxed-integer (bignum) runtime helpers (always present, appended after the
	// reader block so every constant above keeps its value). An exact integer outside
	// the i31 fixnum range lives in a TYPE_BIGNUM struct {i64}; _int_new normalizes an
	// i64 back into an i31 when it fits (the invariant: an in-range integer is ALWAYS
	// an i31, so ref.eq/eql fast paths stay valid), _int_val widens either integer
	// representation to i64, and _print_i64_no_nl renders an i64's digits.

	// _int_new (i64) -> (ref null eq): i31 when in range, else a TYPE_BIGNUM box.
	static final int FUNC_INT_NEW = FUNC_RD_MEMEQ + 1;

	// _int_val ((ref null eq)) -> i64: i31 sign-extended, or a TYPE_BIGNUM's field.
	static final int FUNC_INT_VAL = FUNC_INT_NEW + 1;

	// _print_i64_no_nl (i64) -> (): the i64 counterpart of _print_i32_no_nl.
	static final int FUNC_PRINT_I64_NO_NL = FUNC_INT_VAL + 1;

	// The limb (arbitrary-precision) integer runtime (WasmBigIntRuntimeBuilder, always
	// present, appended after the boxed-i64 helpers so every constant above keeps its
	// value). An exact integer outside the signed 64-bit range is a TYPE_BIGINT struct
	// holding a TYPE_LIMBS array (two's-complement little-endian 32-bit limbs). The
	// _limb_* helpers work on raw limb arrays; the _big_* helpers dispatch across all
	// three integer tiers (i31, TYPE_BIGNUM, TYPE_BIGINT) with an i64 fast path first,
	// promoting on overflow and normalizing every result to the narrowest tier.

	// _limb_of ((ref null eq)) -> (ref null eq): any exact integer's limb array.
	static final int FUNC_LIMB_OF = FUNC_PRINT_I64_NO_NL + 1;

	// _limb_new ((ref null eq) arr) -> (ref null eq): canonicalize to the narrowest tier.
	static final int FUNC_LIMB_NEW = FUNC_LIMB_OF + 1;

	// _limb_get ((ref null eq) arr, i32 i) -> i32: limb i, sign-extended past the top.
	static final int FUNC_LIMB_GET = FUNC_LIMB_NEW + 1;

	// _limb_addsub ((ref null eq) a, (ref null eq) b, i32 sub) -> (ref null eq) array.
	static final int FUNC_LIMB_ADDSUB = FUNC_LIMB_GET + 1;

	// _limb_neg ((ref null eq) arr) -> (ref null eq) array: two's-complement negate.
	static final int FUNC_LIMB_NEG = FUNC_LIMB_ADDSUB + 1;

	// _limb_copy ((ref null eq) arr) -> (ref null eq) array: fresh mutable copy.
	static final int FUNC_LIMB_COPY = FUNC_LIMB_NEG + 1;

	// _limb_mul ((ref null eq) a, (ref null eq) b) -> (ref null eq) array.
	static final int FUNC_LIMB_MUL = FUNC_LIMB_COPY + 1;

	// _limb_cmp ((ref null eq) a, (ref null eq) b) -> i32: signed -1/0/1.
	static final int FUNC_LIMB_CMP = FUNC_LIMB_MUL + 1;

	// _limb_shl / _limb_shr ((ref null eq) arr, i32 bits) -> (ref null eq) array.
	static final int FUNC_LIMB_SHL = FUNC_LIMB_CMP + 1;

	static final int FUNC_LIMB_SHR = FUNC_LIMB_SHL + 1;

	// _limb_divrem_mag ((ref null eq) u, (ref null eq) v, i32 which) -> (ref null eq)
	// array: binary long division on non-negative magnitudes (0 = quotient, 1 = rem).
	static final int FUNC_LIMB_DIVREM_MAG = FUNC_LIMB_SHR + 1;

	// _limb_divmod_small ((ref null eq) arr, i32 d) -> i32 rem: in-place magnitude
	// division by a small positive divisor (the decimal printer's 10^9 chunker).
	static final int FUNC_LIMB_DIVMOD_SMALL = FUNC_LIMB_DIVREM_MAG + 1;

	// _big_add/_big_sub/_big_mul ((ref null eq), (ref null eq)) -> (ref null eq):
	// exact-integer arithmetic across all three tiers; i64 fast path promotes on
	// overflow instead of wrapping.
	static final int FUNC_BIG_ADD = FUNC_LIMB_DIVMOD_SMALL + 1;

	static final int FUNC_BIG_SUB = FUNC_BIG_ADD + 1;

	static final int FUNC_BIG_MUL = FUNC_BIG_SUB + 1;

	// _big_neg ((ref null eq)) -> (ref null eq): exact negation (i64.min promotes).
	static final int FUNC_BIG_NEG = FUNC_BIG_MUL + 1;

	// _big_divrem ((ref null eq) a, (ref null eq) b, i32 which) -> (ref null eq):
	// truncating quotient (0) / remainder (1); traps on a zero divisor.
	static final int FUNC_BIG_DIVREM = FUNC_BIG_NEG + 1;

	// _big_mod ((ref null eq) a, (ref null eq) b) -> (ref null eq): CL mod semantics.
	static final int FUNC_BIG_MOD = FUNC_BIG_DIVREM + 1;

	// _big_cmp ((ref null eq), (ref null eq)) -> i32: -1/0/1 over exact integers.
	static final int FUNC_BIG_CMP = FUNC_BIG_MOD + 1;

	// _big_and/_big_or/_big_xor ((ref null eq), (ref null eq)) -> (ref null eq).
	static final int FUNC_BIG_AND = FUNC_BIG_CMP + 1;

	static final int FUNC_BIG_OR = FUNC_BIG_AND + 1;

	static final int FUNC_BIG_XOR = FUNC_BIG_OR + 1;

	// _big_not ((ref null eq)) -> (ref null eq): lognot.
	static final int FUNC_BIG_NOT = FUNC_BIG_XOR + 1;

	// _big_ash ((ref null eq) x, (ref null eq) count) -> (ref null eq).
	static final int FUNC_BIG_ASH = FUNC_BIG_NOT + 1;

	// _big_intlen ((ref null eq)) -> (ref null eq): integer-length.
	static final int FUNC_BIG_INTLEN = FUNC_BIG_ASH + 1;

	// _big_logbitp ((ref null eq) idx, (ref null eq) n) -> i32.
	static final int FUNC_BIG_LOGBITP = FUNC_BIG_INTLEN + 1;

	// _big_gcd ((ref null eq), (ref null eq)) -> (ref null eq): non-negative gcd.
	static final int FUNC_BIG_GCD = FUNC_BIG_LOGBITP + 1;

	// _big_grow ((ref null eq) acc, i32 radix, i32 digit) -> (ref null eq): the
	// reader's accumulator step acc*radix + digit at any tier.
	static final int FUNC_BIG_GROW = FUNC_BIG_GCD + 1;

	// _big_to_f64 ((ref null eq)) -> f64: float conversion of a limb integer.
	static final int FUNC_BIG_TO_F64 = FUNC_BIG_GROW + 1;

	// _big_print ((ref null eq)) -> (): decimal renderer for a TYPE_BIGINT.
	static final int FUNC_BIG_PRINT = FUNC_BIG_TO_F64 + 1;

	// _big_print_mag ((ref null eq) arr) -> (): recursive magnitude digit renderer.
	static final int FUNC_BIG_PRINT_MAG = FUNC_BIG_PRINT + 1;

	// _big_pad9 (i32) -> (): a zero-padded 9-digit chunk through _write_str.
	static final int FUNC_BIG_PAD9 = FUNC_BIG_PRINT_MAG + 1;

	// _big_eq ((ref null eq), (ref null eq)) -> i32: both-TYPE_BIGINT value equality.
	static final int FUNC_BIG_EQ = FUNC_BIG_PAD9 + 1;

	// _big_hash ((ref null eq)) -> i32: limb fold for _hash.
	static final int FUNC_BIG_HASH = FUNC_BIG_EQ + 1;

	// _big_fdiv ((ref null eq) a, (ref null eq) b, i32 mode) -> (ref null eq): exact
	// integer division -- mode 0 = truncate, 1 = floor, 2 = ceiling, 3 = round (ties
	// to even). The fused form of `(truncate (/ a b))` and friends for exact-integer
	// operands, where the ratio intermediate cannot hold limb components.
	static final int FUNC_BIG_FDIV = FUNC_BIG_HASH + 1;

	static final int BIGINT_FUNC_LAST = FUNC_BIG_FDIV;

	// The unboxed-fixnum fusion helpers (WasmFxRuntimeBuilder, always present, appended
	// after the limb runtime so every constant above keeps its value). Inside a fused
	// integer expression tree (WasmIntFusionCompiler) the intermediates stay raw i64;
	// these carry the guarded leaf unbox and the overflow checks, each answering an
	// (i64, i32 flag) pair -- a non-zero flag bails the fast path to its boxed fallback.

	// _fx_val ((ref null eq)) -> (i64, i32 ok): guarded unbox of an i31 / TYPE_BIGNUM.
	static final int FUNC_FX_VAL = FUNC_BIG_FDIV + 1;

	// _fx_add/_fx_sub/_fx_mul (i64, i64) -> (i64, i32 ovf): checked i64 arithmetic.
	static final int FUNC_FX_ADD = FUNC_FX_VAL + 1;

	static final int FUNC_FX_SUB = FUNC_FX_ADD + 1;

	static final int FUNC_FX_MUL = FUNC_FX_SUB + 1;

	// _fx_ash (i64 v, i64 s) -> (i64, i32 ovf): CL ash on the i64 range.
	static final int FUNC_FX_ASH = FUNC_FX_MUL + 1;

	// _fx_mod/_fx_rem (i64, i64) -> i64: CL mod / truncating rem (trap on 0 divisor).
	static final int FUNC_FX_MOD = FUNC_FX_ASH + 1;

	static final int FUNC_FX_REM = FUNC_FX_MOD + 1;

	// _iv_set ((ref null eq) arr, i32 idx, i64 val): the packed integer-vector raw
	// store -- width dispatch and the wrap-to-width truncation in one place, so a fused
	// aset value can stay a raw i64 on the stack (todo 194 stage 2).
	static final int FUNC_IV_SET = FUNC_FX_REM + 1;

	// _t_sym () -> eqref: the symbol t, built once (lazily) into a module global and
	// returned on every subsequent call. Every emitTrue site (comparisons, predicates)
	// used to rebuild it through _str_build, which ALLOCATED a fresh $str_bytes per
	// true result -- a loop's termination test allocated on every iteration (todo 194
	// stage 3). The cached instance has the same id (the intern offset of "T") and the
	// same bytes as a per-site build, so eq/eql/print behavior is unchanged.
	static final int FUNC_T_SYM = FUNC_IV_SET + 1;

	// _probe_file ((ref null eq) path) -> (ref null eq): the path when it names an
	// existing file, null otherwise. Deliberately NOT part of the _open block above:
	// _open TRAPS on a non-zero path_open errno, which no handler-case can catch, so
	// the probe needs its own body whose errno branch answers nil instead.
	static final int FUNC_PROBE_FILE = FUNC_T_SYM + 1;

	// _fresh_line_stream ((ref null eq) dest) -> (ref null eq): fresh-line for an
	// explicit or redirected (*standard-output*) destination -- nil/t = stdout via the
	// LINE_START tracking, a negative i31 inspects the string-stream record's last
	// byte, a non-negative i31 (WASI fd) always writes a newline. Returns nil.
	static final int FUNC_FRESH_LINE_STREAM = FUNC_PROBE_FILE + 1;

	// _peek_char ((ref null eq) stream, eof-error-p, eof-value) -> (ref null eq): the
	// next character LEFT IN PLACE. A string input record simply decodes at its cursor
	// without advancing it; a WASI fd cannot un-read, so the code point goes into the
	// one-slot pushback cell (PEEK_FD_ADDR / PEEK_CP_ADDR) that _read_char drains first.
	// Appended before FUNC_USER_BASE like the mod/rem helpers, so no import/FUNC_START
	// index shifts and the component adapter blobs are unaffected.
	static final int FUNC_PEEK_CHAR = FUNC_FRESH_LINE_STREAM + 1;

	// _list_directory ((ref null eq) path) -> (ref null eq): (t . names) for a readable
	// directory, null otherwise -- the ONE directory-listing primitive (everything
	// user-facing is Lisp source over it, LispPreludeLibrary). Opens the path as a
	// directory through path_open and drains fd_readdir's preview1 dirent stream,
	// skipping the "." / ".." entries a preview1 host yields but Files.list and
	// wasi:filesystem's read-directory both omit. The names come back in the host's
	// order; `directory` sorts, so every backend prints the same listing.
	static final int FUNC_LIST_DIRECTORY = FUNC_PEEK_CHAR + 1;

	// _ub_read ((ref null eq) shadow, i64 raw) -> (ref null eq): the boxed read of an
	// unboxed dual-representation local (.kb/wasm-unboxed-locals.md) -- the shadow when
	// it is not the sentinel, else the raw i64 boxed (i31 in range, _int_new outside).
	// It is the SAME code the read used to inline at every occurrence; moving it out of
	// line is what the file's "if call sites ever bloat, re-measure before
	// restructuring" trigger asked for, and the measurement that fired it was a
	// cl-postgres component: 19,392 sites x ~42 bytes = 9.5% of the module. The fused
	// fast path never comes here (it reads the pair raw through a RawLeaf), so the call
	// is paid only by a NON-fused reader, and the out-of-i31 arm was already a call.
	// Appended after the last fixed helper so no index above shifts.
	static final int FUNC_UB_READ = FUNC_LIST_DIRECTORY + 1;

	// _fixed_dec ((ref null eq) value, places, int-digits, plus) -> (ref null eq): the
	// %fixed-decimal primitive behind format's ~F / ~$ (WasmFixedDecimalRuntimeBuilder).
	// Appended after the last fixed helper so no index above shifts.
	static final int FUNC_FIXED_DEC = FUNC_UB_READ + 1;

	// _as_f64 ((ref null eq) value) -> f64: the shared numeric-to-f64 coercion ladder
	// (WasmEmitHelper.buildAsF64Body). It used to be inlined at every site that wants an
	// unboxed operand -- ~80 bytes each, 26 copies and 43% of the code section in a
	// five-line float program. Appended after the last fixed helper so no index above
	// shifts.
	static final int FUNC_AS_F64 = FUNC_FIXED_DEC + 1;

	// _arr_get ((ref null eq) header, i32 flat) -> (ref null eq) and
	// _arr_set ((ref null eq) header, i32 flat, (ref null eq) value) -> (ref null eq):
	// the GENERAL-array arm of every element access -- the displacement-chain walk
	// (WasmArrayRuntimeBuilder) plus the buckets array.get/array.set. It used to be
	// inlined by all five accessors (aref rank-1 and rank-n, row-major-aref, %aset,
	// %row-major-aset), ~45 instructions and two never-released temps at each site; the
	// packed float and packed integer arms stay inline on purpose
	// (.kb/packed-integer-vectors.md). Appended after the last fixed helper so no index
	// above shifts.
	static final int FUNC_ARR_GET = FUNC_AS_F64 + 1;

	static final int FUNC_ARR_SET = FUNC_ARR_GET + 1;

	// _seq_len ((ref null eq) value) -> (ref null eq): the generic length dispatch --
	// packed float/int vector, string, general array (fill pointer), hash table, cons
	// walk (WasmLengthCompiler.buildSeqLenBody). It used to be inlined at every length
	// site whose argument's representation is not pinned -- ~300 bytes each, 66 copies
	// and 13.6% of the zlib module. The JVM backend always had the shared form
	// (_length, JvmLengthRuntimeBuilder). Appended after the last fixed helper so no
	// index above shifts.
	static final int FUNC_SEQ_LEN = FUNC_ARR_SET + 1;

	// _ostream_room (i32 rec, i32 n) -> (ref null eq): the string OUTPUT stream's byte
	// buffer, grown (doubling) to hold n more content bytes. The one place the
	// per-stream buffer table is indexed, so every append -- _write_stream_str,
	// _write_line, fresh-line's newline -- and _str_stream_contents route through it.
	// Reuses the (i32,i32) -> (ref null eq) signature (TYPE_RAT_NEW), so no new type
	// entry; appended after the last fixed helper so no index above shifts.
	static final int FUNC_OSTREAM_ROOM = FUNC_SEQ_LEN + 1;

	// _iv_utf8_str ((ref null eq) v) -> (ref null eq): a packed (unsigned-byte 8) vector
	// validated as STRICT UTF-8 and, when it is, copied into the TYPE_STRING its bytes
	// spell -- one array.copy between the frame quotes; anything else answers nil. The
	// native half of the prelude's %octets-to-string, so a well-formed body decodes at
	// the speed of a copy and only malformed bytes reach the per-byte loop. Reuses the
	// unary ((ref null eq)) -> (ref null eq) signature (TYPE_CALLABLE_BASE + 0), so no
	// new type entry; appended after the last fixed helper so no index above shifts.
	static final int FUNC_IV_UTF8_STR = FUNC_OSTREAM_ROOM + 1;

	// _path_dirfd (i32 ptr, i32 len) -> i32: the directory fd a path is opened relative
	// to, and the number of leading bytes path_open must NOT see (written to
	// PATH_SKIP_ADDR). Every path_open call site -- _open, _probe_file, _list_directory,
	// _load -- goes through it, which is what makes an ABSOLUTE runtime path openable:
	// it walks the preopen table (fd_prestat_get / fd_prestat_dir_name) and picks the
	// LONGEST matching prefix. A relative path answers fd 3 / skip 0, today's behavior
	// byte for byte. Reuses the (i32,i32) -> i32 signature (TYPE_INTERN), so no new type
	// entry; appended after the last fixed helper so no index above shifts.
	static final int FUNC_PATH_DIRFD = FUNC_IV_UTF8_STR + 1;

	// _read_packed / _write_packed (seq, stream, start, end) -> value: the bulk binary
	// transfer behind read-sequence / write-sequence over a packed buffer
	// (WasmPackedIoRuntimeBuilder, .kb/binary-sequence-io.md). The 4-parameter callable
	// signature (TYPE_CALLABLE_BASE + 3), so no new type entry; appended after the last
	// fixed helper so no index above shifts.
	static final int FUNC_READ_PACKED = FUNC_PATH_DIRFD + 1;

	static final int FUNC_WRITE_PACKED = FUNC_READ_PACKED + 1;

	// _argv () -> (ref null eq): the program's argument vector as a list of strings,
	// argv0 first -- the host read behind %host-argv, and therefore behind the whole
	// uiop/image command-line family. It scans the buffer args_sizes_get / args_get
	// fill, the pair being APPENDED USER IMPORTS rather than fixed slots
	// (WasmArgvRuntimeBuilder), so the twelve index-pinned preview1 imports do not
	// grow and no --component adapter export list changes. Reuses the () -> (ref null
	// eq) signature (TYPE_READ_LINE), so no new type entry; appended after the last
	// fixed helper so no index above shifts. A program that reads no arguments gets a
	// nil-answering stub body and imports nothing.
	static final int FUNC_ARGV = FUNC_WRITE_PACKED + 1;

	// _equalp_key (key) -> key: the fold an equalp hash table places its keys by
	// (WasmEqualpKeyRuntimeBuilder). Recursive over a cons, so it is a real function
	// rather than inline code at each gethash/puthash/remhash site. Reuses the
	// ((ref null eq)) -> (ref null eq) signature (TYPE_CALLABLE_BASE + 0), so no new type
	// entry; appended after the last fixed helper so no index above shifts. A program
	// that writes no :test 'equalp gets an identity stub body and calls it nowhere.
	static final int FUNC_EQUALP_KEY = FUNC_ARGV + 1;

	// _file_length (stream) -> integer | nil: the byte length of the file behind a file
	// stream, read from the fd_filestat_get import (WasmIoRuntimeBuilder). Everything
	// that genuinely has no length -- a string stream (a negative handle), a standard
	// stream, a socket, a closed or non-handle designator -- answers nil, which is what
	// CL prescribes for "cannot be determined". Reuses the ((ref null eq)) ->
	// (ref null eq) signature (TYPE_CALLABLE_BASE + 0), so no new type entry; appended
	// after the last fixed helper so no index above shifts.
	static final int FUNC_FILE_LENGTH = FUNC_EQUALP_KEY + 1;

	// _type_err_int / _type_err_num ((ref null eq) culprit) -> (): the arithmetic
	// runtime's non-number landing (WasmEmitHelper.buildTypeErrBody). _int_val's
	// non-integer arm calls the first, _as_f64's non-number arm the second; both arms
	// used to be a bare ref.cast whose failure was an UNCATCHABLE host trap
	// (.kb/error-handling.md, "A non-number reaching arithmetic"). In EH mode the body
	// renders "Expected integer|number, got: <prin1>" and throws it on $lisp-cond, so
	// handler-case catches it and the entry landing pad reports it; outside EH mode the
	// body is a bare `unreachable` -- no tag exists and nothing could catch it -- which
	// keeps the printer family unreachable there. Both reuse the ((ref null eq)) -> ()
	// signature (TYPE_PRINT_VAL), so no new type entry; appended after the last fixed
	// helper so no index above shifts.
	static final int FUNC_TYPE_ERR_INT = FUNC_FILE_LENGTH + 1;

	static final int FUNC_TYPE_ERR_NUM = FUNC_TYPE_ERR_INT + 1;

	// _str_char_ref ((ref null eq) s, i32 i) -> i32: the i-th character's code point of
	// a string in EITHER representation -- a mutable character vector reads its ELEMENT
	// (_charvec_p, then _arr_get through the displacement walk, an O(1) read that never
	// renders the vector into a string), anything else decodes through _str_char_at.
	// Every (char s i) / (schar s i) site calls this instead of the old
	// _charvec_to_str + _str_char_at pair, whose render made a scan of a make-string
	// buffer O(n^2) (.kb/string-index-cost.md). Reuses the ((ref null eq), i32) -> i32
	// signature (TYPE_STR_TO_MEM), so no new type entry; appended after the last fixed
	// helper so no index above shifts.
	static final int FUNC_STR_CHAR_REF = FUNC_TYPE_ERR_NUM + 1;

	static final int FX_FUNC_LAST = FUNC_STR_CHAR_REF;

	// The vec: SIMD block (_v_new/_v_get/_v_set + the twelve v128 kernels), emitted ONLY
	// under --simd. Fixed indices relative to FX_FUNC_LAST, so every constant
	// above keeps its value; the user defuns below shift by
	// WasmVecSimdRuntimeBuilder.FUNC_COUNT when the block is present. Read the base
	// through userFuncBase(), never FUNC_USER_BASE.
	static final int FUNC_VEC_BASE = FX_FUNC_LAST + 1;

	// User defuns start after the dispatch functions, the plist helper, the two
	// hash-table runtime helpers, the two mod/rem helpers, the gensym helper, the
	// p1-future-await helper, the two binary stream helpers, the four string-stream
	// helpers, the five symbol-API helpers, the read-char helper, the four string
	// GC helpers (_str_build, _str_fresh, _str_to_mem, _write_str_gc), the
	// character-vector normalizer (_charvec_to_str), the three UTF-8 walking helpers
	// (_str_char_count, _str_char_at, _str_char_byte_offset) and the two case-fold
	// helpers (_char_upcase, _char_downcase), the thirteen reader # dispatch
	// helpers, the three bignum helpers (_int_new, _int_val, _print_i64_no_nl), the
	// limb bigint runtime (_limb_* / _big_*, WasmBigIntRuntimeBuilder) and the
	// unboxed-fixnum fusion helpers (_fx_*, WasmFxRuntimeBuilder) -- plus, under
	// --simd, the vec: SIMD block. Use userFuncBase(), which adds that offset.
	static final int FUNC_USER_BASE = FX_FUNC_LAST + 1;

	// Type indices
	static final int TYPE_FD_WRITE = 0;

	static final int TYPE_START = 1;

	static final int TYPE_PRINT_I32 = 2; // also for _print_i32_no_nl

	static final int TYPE_CONS = 3; // in rec group

	static final int TYPE_STRING = 4; // in rec group

	static final int TYPE_CELL = 5; // in rec group - {(mut ref null eq)}

	static final int TYPE_CLOSURE = 6; // in rec group - {i32 funcId, (ref null eq) env}

	static final int TYPE_FLOAT = 7; // in rec group - {f64 value}

	static final int TYPE_WRITE_STR = 8; // (i32, i32) -> ()

	static final int TYPE_PRINT_VAL = 9; // ((ref null eq)) -> ()

	static final int TYPE_PRINT_F64 = 10; // (f64) -> ()

	// Callable types: arity N = (ref null eq)^(N+1) -> (ref null eq), at index
	// TYPE_CALLABLE_BASE + N (11..21). Used by the dispatch functions, by user functions
	// (defuns/lambdas) and, as the generic "N+1 eq params -> eq" signature, by most of
	// the runtime helpers below. A dispatcher past MAX_CALLABLE_ARITY gets its signature
	// APPENDED after the last conditional type block instead (extraCallableTypeBase), so
	// no index here moves.
	static final int TYPE_CALLABLE_BASE = 11;

	// TYPE_READ_LINE: () -> (ref null eq)
	static final int TYPE_READ_LINE = TYPE_CALLABLE_BASE + MAX_CALLABLE_ARITY + 1; // 22

	// type index for _lookup: (i32) -> (i32); also used by the fd_close import
	static final int TYPE_LOOKUP = TYPE_READ_LINE + 1; // 23

	// type index for _env_lookup: (i32, (ref null eq)) -> (ref null eq)
	static final int TYPE_ENV_LOOKUP = TYPE_LOOKUP + 1; // 24

	// type index for _intern: (i32, i32) -> (i32)
	static final int TYPE_INTERN = TYPE_ENV_LOOKUP + 1; // 25

	// type index for path_open: (i32,i32,i32,i32,i32,i64,i64,i32,i32) -> (i32)
	static final int TYPE_PATH_OPEN = TYPE_ENV_LOOKUP + 2; // 26

	// Ratio struct {i32 numerator, i32 denominator}, always normalized (coprime,
	// denominator > 1, sign on the numerator). A rational whose denominator reduces to
	// one is represented as a plain i31 integer instead.
	static final int TYPE_RATIO = TYPE_PATH_OPEN + 1; // 27

	// type index for _rat_new: (i32, i32) -> (ref null eq)
	static final int TYPE_RAT_NEW = TYPE_RATIO + 1; // 28

	// type index for _rat_num/_rat_den: ((ref null eq)) -> (i32)
	static final int TYPE_RAT_GET = TYPE_RAT_NEW + 1; // 29

	// type index for _rat_cmp: ((ref null eq), (ref null eq)) -> (i32)
	static final int TYPE_RAT_CMP = TYPE_RAT_GET + 1; // 30

	// type index for _read_line: (i32 fd) -> (ref null eq)
	static final int TYPE_READ_LINE_FD = TYPE_RAT_CMP + 1; // 31

	// type index for _open: ((ref null eq) path, i32 mode) -> (ref null eq)
	static final int TYPE_OPEN = TYPE_READ_LINE_FD + 1; // 32

	// clock_time_get (i32 clock_id, i64 precision, i32 result_ptr) -> i32 errno
	static final int TYPE_CLOCK_TIME_GET = TYPE_OPEN + 1; // 33

	// Character struct {i32 code}: the runtime representation of a character, distinct
	// from
	// an i31 integer so characterp and the accessors can dispatch on it via ref.test.
	static final int TYPE_CHAR = TYPE_CLOCK_TIME_GET + 1; // 34

	// Hash-table bucket array: array (mut (ref null eq)). Each slot holds a bucket alist
	// (a cons chain of (key . value) entries) or null. Implicitly a subtype of eq, so a
	// bucket array can be stored in a cons/cell field and compared with ref.eq.
	static final int TYPE_HASH_BUCKETS = TYPE_CHAR + 1; // 35

	// Degenerate-future struct {mut i32 kind, mut (ref null eq) value}: the
	// non-asyncMode runtime representation of a future, distinct from every other value
	// so futurep and _p1_future_await dispatch on it via ref.test. Its only producer,
	// %async-run, runs the body to completion and wraps the value as kind 2 (settled);
	// the kind field stays so the struct's shape cannot structurally canonicalize into
	// the one-field TYPE_CELL (ref.test could no longer tell them apart).
	static final int TYPE_P1_FUTURE = TYPE_HASH_BUCKETS + 1; // 36

	// String byte array: array (mut i8) -- the GC-managed byte storage for a
	// TYPE_STRING's `data` field (field 2). A bare array comptype (implicitly sub
	// final), so it is a subtype of eq and stores in the (ref null eq) data field;
	// readers ref.cast it before array.get_u / array.len. Appended right after
	// TYPE_P1_FUTURE so the export/import wrapper type indices shift by one (see
	// wrapperTypeIndex / importTypeIndex, both TYPE_WRITE_STR_GC + 1 based).
	static final int TYPE_STR_BYTES = TYPE_P1_FUTURE + 1; // 37

	// _str_to_mem ((ref null eq) str, i32 ptr) -> i32: copies a string's $str_bytes
	// array (with its surrounding quotes) into linear[ptr..) and returns the byte
	// count. The array->linear bridge for the paths that still need a linear pointer
	// (WASI iovecs for write-line/open, the reader input scratch, the host :string
	// boundary, runtime intern). Appended after TYPE_STR_BYTES.
	static final int TYPE_STR_TO_MEM = TYPE_STR_BYTES + 1; // 38

	// _write_str_gc ((ref null eq) str, i32 from, i32 to) -> (): writes bytes
	// [from, to) of a string's $str_bytes array to the current print sink -- appended
	// directly to the capture buffer when capture mode is on (no linear staging, so it
	// never aliases the capture buffer), else staged into heap scratch and handed to
	// _write_str for stdout. The print path for string values now that their bytes live
	// on the GC heap. Appended after TYPE_STR_TO_MEM.
	static final int TYPE_WRITE_STR_GC = TYPE_STR_TO_MEM + 1; // 39

	// Packed float-array data storage: array (mut f64). A bare array comptype (implicitly
	// sub final), so a subtype of eq -- it stores in TYPE_FARRAY's (ref null eq) data
	// field and readers ref.cast it before array.get / array.len. The unboxed f64 storage
	// of a packed float array. Appended after TYPE_WRITE_STR_GC.
	static final int TYPE_F64ARR = TYPE_WRITE_STR_GC + 1; // 40

	// Packed float array: struct {(ref null eq) dims, (ref null eq) data} -- a rank-n
	// packed float array as a distinct first-class type (disjoint from TYPE_CELL, so
	// arrayp/print/length discriminate it with a plain ref.test). `dims` is a
	// TYPE_HASH_BUCKETS of i31 dimension sizes (as for a general array), `data` a
	// TYPE_F64ARR (double-float) or TYPE_F32ARR (single-float) of the row-major elements
	// -- the SAME struct serves both widths, distinguished by ref.test-ing the data field
	// (see WasmArrayCompiler; mirrors the JVM `double[]`/`float[]` inline dispatch). No
	// fill pointer / adjustable / displacement (a packed array is a pure compute buffer).
	// Appended after TYPE_F64ARR; the export/import wrapper type indices below shift past
	// it and TYPE_F32ARR.
	static final int TYPE_FARRAY = TYPE_F64ARR + 1; // 41

	// Packed single-float array data storage: array (mut f32). A bare array comptype
	// (implicitly sub final), a subtype of eq -- it stores in TYPE_FARRAY's (ref null eq)
	// data field alongside TYPE_F64ARR and readers pick the width with ref.test $f32arr
	// before array.get / array.set. The unboxed f32 storage of a single-float packed
	// array (#f(...) / make-array :element-type 'single-float). Reads widen f32->f64,
	// writes narrow f64->f32; scalars stay f64 (no single-float scalar type). Appended
	// after TYPE_FARRAY (last type of the DEFAULT module).
	static final int TYPE_F32ARR = TYPE_FARRAY + 1; // 42

	// --- the reader # dispatch signatures (always present) ------------------------
	//
	// Three plain function signatures for the WasmReadRuntimeBuilder # dispatch
	// helpers, appended after TYPE_F32ARR (the conditional --simd/async/instance blocks
	// shift past them, like everything after the fixed types).

	// _rd_dims ((ref null eq) rows, i32 rank) -> (ref null eq) dims buckets
	static final int TYPE_RD_DIMS = TYPE_F32ARR + 1; // 43

	// _rd_flat ((ref null eq) items, i32 depth, (ref null eq) dims, (ref null eq) out,
	// i32 idx) -> i32 next idx
	static final int TYPE_RD_FLAT = TYPE_RD_DIMS + 1; // 44

	// _rd_token () -> i32 start offset (the token's end is the read cursor)
	static final int TYPE_RD_TOKEN = TYPE_RD_FLAT + 1; // 45

	// _rd_memeq (i32 a, i32 b, i32 len) -> i32 (byte-range equality)
	static final int TYPE_RD_MEMEQ = TYPE_RD_TOKEN + 1; // 46

	// How many type entries the reader block appends.
	static final int READER_TYPE_COUNT = 4;

	static final int READER_TYPE_LAST = TYPE_RD_MEMEQ;

	// --- the bignum block (always present) ----------------------------------------
	//
	// The boxed exact-integer type and its helper signatures, appended after the
	// reader signatures (the conditional --simd/async/instance blocks shift past
	// them). See the FUNC_INT_NEW comment for the representation invariant.

	// TYPE_BIGNUM: struct {i64 value} -- an exact integer OUTSIDE the i31 fixnum
	// range. The only {i64} struct in the module, so ref.test can discriminate it.
	// _int_new never boxes an in-range value, so two equal integers are only ever
	// both-i31 (ref.eq works) or both-boxed (compared by field).
	static final int TYPE_BIGNUM = TYPE_RD_MEMEQ + 1; // 47

	// _int_new (i64) -> (ref null eq)
	static final int TYPE_INT_NEW = TYPE_BIGNUM + 1; // 48

	// _int_val ((ref null eq)) -> i64
	static final int TYPE_INT_VAL = TYPE_INT_NEW + 1; // 49

	// _print_i64_no_nl (i64) -> ()
	static final int TYPE_PRINT_I64 = TYPE_INT_VAL + 1; // 50

	static final int BIGNUM_TYPE_LAST = TYPE_PRINT_I64;

	// --- the limb bigint block (always present) -----------------------------------
	//
	// The third exact-integer tier: an integer outside the signed 64-bit range is a
	// TYPE_BIGINT struct holding a TYPE_LIMBS array of two's-complement little-endian
	// 32-bit limbs, canonicalized to the minimal length (>= 3 limbs; anything shorter
	// normalizes down through _int_new). The two types share one rec group -- the
	// struct's field is typed (ref null $limbs), a recursive intra-group reference no
	// other type in the module can structurally canonicalize with, so ref.test
	// discriminates TYPE_BIGINT from every other value.

	// TYPE_LIMBS: array (mut i32) -- limb storage.
	static final int TYPE_LIMBS = BIGNUM_TYPE_LAST + 1; // 51

	// TYPE_BIGINT: struct {(ref null $limbs) limbs}.
	static final int TYPE_BIGINT = TYPE_LIMBS + 1; // 52

	// _limb_shl/_limb_shr ((ref null eq), i32) -> (ref null eq)
	static final int TYPE_BIG_SHIFT = TYPE_BIGINT + 1; // 53

	// _limb_addsub/_limb_divrem_mag/_big_divrem ((ref null eq), (ref null eq), i32) ->
	// (ref null eq)
	static final int TYPE_BIG_TRIPLE = TYPE_BIG_SHIFT + 1; // 54

	// _big_grow ((ref null eq), i32, i32) -> (ref null eq)
	static final int TYPE_BIG_GROW = TYPE_BIG_TRIPLE + 1; // 55

	// _big_to_f64 ((ref null eq)) -> f64
	static final int TYPE_BIG_TO_F64 = TYPE_BIG_GROW + 1; // 56

	static final int BIGINT_TYPE_LAST = TYPE_BIG_TO_F64;

	// --- the unboxed-fixnum fusion helper signatures (always present) --------------
	//
	// The _fx_* helpers of WasmFxRuntimeBuilder (integer expression-tree fusion). The
	// checked shapes return an (i64, i32 flag) pair -- multi-value results, so they
	// need their own entries.

	// _fx_val ((ref null eq)) -> (i64, i32)
	static final int TYPE_FX_VAL = BIGINT_TYPE_LAST + 1; // 57

	// _fx_add/_fx_sub/_fx_mul/_fx_ash (i64, i64) -> (i64, i32)
	static final int TYPE_FX_BIN = TYPE_FX_VAL + 1; // 58

	// _fx_mod/_fx_rem (i64, i64) -> i64
	static final int TYPE_FX_DIV = TYPE_FX_BIN + 1; // 59

	static final int FX_TYPE_LAST = TYPE_FX_DIV;

	// --- the packed integer-vector types (always present) -------------------------
	//
	// A rank-1 (unsigned-byte 8|16|32) vector (todo 194 stage 2) is the BARE
	// (array (mut i8|i16|i32)) value itself -- no struct wrapper and no dims (rank-1
	// only; array.len is the length). Elements store masked to the width and read back
	// unsigned (array.get_u; the i32 width widens with i64.extend_i32_u), which is what
	// keeps the SHA-256 working buffers unboxed. The three share ONE rec group, which
	// keeps them structurally distinct from TYPE_LIMBS (also an array (mut i32)) and
	// from every other bare array under wasm-GC's structural canonicalization, so
	// ref.test discriminates the width directly.

	// array (mut i8) -- an (unsigned-byte 8) vector.
	static final int TYPE_I8ARR = FX_TYPE_LAST + 1; // 60

	// array (mut i16) -- an (unsigned-byte 16) vector.
	static final int TYPE_I16ARR = TYPE_I8ARR + 1; // 61

	// array (mut i32) -- an (unsigned-byte 32) vector.
	static final int TYPE_I32ARR = TYPE_I16ARR + 1; // 62

	// _iv_set ((ref null eq), i32, i64) -> (): the packed integer-vector raw store.
	static final int TYPE_IV_SET = TYPE_I32ARR + 1; // 63

	// _t_sym () -> eqref: the cached-t helper's signature.
	static final int TYPE_T_SYM = TYPE_IV_SET + 1; // 64

	// fd_readdir(fd, buf, buf_len, cookie, retptr) -> errno:
	// (i32, i32, i32, i64, i32) -> i32. Appended after the last fixed type rather than
	// slotted next to TYPE_PATH_OPEN so every type index above keeps its value; the
	// conditional --simd / async / instance blocks follow it through IARR_TYPE_LAST.
	static final int TYPE_FD_READDIR = TYPE_T_SYM + 1; // 65

	// _ub_read ((ref null eq) shadow, i64 raw) -> (ref null eq): the unboxed-local
	// boxed-read helper's signature. Appended after the last fixed type, like
	// TYPE_FD_READDIR above, so every type index keeps its value.
	static final int TYPE_UB_READ = TYPE_FD_READDIR + 1; // 66

	// _arr_set ((ref null eq) header, i32 flat, (ref null eq) value) -> (ref null eq):
	// the shared general-array store's signature. _arr_get reuses TYPE_BIG_SHIFT, which
	// is already ((ref null eq), i32) -> (ref null eq). Appended after the last fixed
	// type, like the two above, so every type index keeps its value.
	static final int TYPE_ARR_SET = TYPE_UB_READ + 1; // 67

	static final int IARR_TYPE_LAST = TYPE_ARR_SET;

	// The Schubfach float-printer runtime types (todo-431). Unconditional, like the
	// printer itself; the tree shaker removes what a program does not reach.
	static final int TYPE_SCHUB_UMULHI = IARR_TYPE_LAST + 1; // (i64, i64) -> i64

	static final int TYPE_SCHUB_G = TYPE_SCHUB_UMULHI + 1; // (i32) -> (i64, i64)

	static final int TYPE_SCHUB_ROP = TYPE_SCHUB_G + 1; // (i64, i64, i64) -> i64

	static final int TYPE_F64_DEC = TYPE_SCHUB_ROP + 1; // (f64) -> (i64, i32)

	static final int TYPE_F32_DEC = TYPE_F64_DEC + 1; // (f32) -> (i64, i32)

	static final int TYPE_DEC_FMT = TYPE_F32_DEC + 1; // (i64, i32, i32, i32) -> i32

	static final int TYPE_WRITE_DEC = TYPE_DEC_FMT + 1; // (i64, i32) -> ()

	static final int TYPE_PRINT_F32 = TYPE_WRITE_DEC + 1; // (f32) -> ()

	// struct {f32}: the transient box a packed single-float array element prints
	// through, so the generic printer can spell it at its f32 width. No Lisp value
	// holds one outside the array printer's buckets.
	static final int TYPE_F32BOX = TYPE_PRINT_F32 + 1;

	static final int SCHUB_TYPE_LAST = TYPE_F32BOX;

	// --- the --simd block (see WasmVecSimdRuntimeBuilder) -------------------------
	//
	// Four types, emitted ONLY under --simd, appended after the packed integer-vector
	// types and before the export/import wrapper signatures (which shift past them via
	// fixedTypeCount()). Declaring an (array (mut v128)) at all requires the SIMD
	// proposal, so the default module keeps validating on a runtime that has it turned
	// off -- which is exactly the dead-flag guard WasmLispCompilerIntegrationTest runs.

	// array (mut v128) -- the lane-group storage of a packed float array under --simd.
	// A bare array comptype (implicitly sub final), so a subtype of eq. array.new_default
	// zeroes every lane, which is what lets the kernels drop their scalar tails.
	static final int TYPE_V128ARR = SCHUB_TYPE_LAST + 1; // 68

	// struct {i32 count, i32 kind, (ref null eq) groups} -- the --simd replacement for
	// the
	// TYPE_F64ARR/TYPE_F32ARR data of a TYPE_FARRAY, stored in the SAME (ref null eq)
	// data
	// field. `count` is the logical element count, `kind` the width tag (0 =
	// double-float,
	// 1 = single-float) that replaces `ref.test $f32arr` now both widths share one
	// TYPE_V128ARR, and `groups` holds ceil(count / lanes) + 1 groups -- the trailing one
	// a
	// zero sentinel so matvec's shuffle window can always read one group past its last.
	static final int TYPE_VBLOCK = SCHUB_TYPE_LAST + 2; // 69

	// _v_get ((ref null eq) vblock, i32 index) -> f64
	static final int TYPE_V_GET = SCHUB_TYPE_LAST + 3; // 70

	// _v_set ((ref null eq) vblock, i32 index, f64 value) -> f64 (the value AS STORED)
	static final int TYPE_V_SET = SCHUB_TYPE_LAST + 4; // 71

	// How many type entries the --simd block appends.
	static final int SIMD_TYPE_COUNT = 4;

	// --- the async block (asyncMode only; see WasmAsyncEmit) ----------------------
	//
	// Three struct types in ONE rec group (the first two are structurally identical,
	// and rec-group membership is what keeps them distinct under wasm-GC's structural
	// type canonicalization): TYPE_FUTURE {mut i32 state, mut value, mut waiters,
	// mut source} at asyncTypeBase(), TYPE_ASYNC_FRAME {mut i32 state, mut spill,
	// mut future, mut env, mut owner} right after it, then TYPE_WASI_STREAM -- plus the
	// callback function type (i32 event, i32 waitable, i32 code) -> i32 packed code
	// used by _sched_dispatch and the serve callback export _async_cb. Appended before
	// the export/import wrapper signatures (which shift past them via
	// fixedTypeCount()).
	static final int ASYNC_TYPE_COUNT = 4;

	// --- the P1 stream struct (usesP1Streams only) --------------------------------
	//
	// TYPE_P1_STREAM {mut i32 eof, mut readFn, mut closeFn} at p1StreamTypeBase(), in its
	// OWN rec group: the degenerate tier's first-class stream value, structurally the
	// same three fields as the async block's TYPE_WASI_STREAM (a stream is a read thunk,
	// a close thunk and a drained flag -- nothing about that is WASI). The two never
	// coexist (asyncMode is --component-only), so they share the slot and no other type
	// index moves. The shape collides with nothing else: TYPE_VBLOCK is the only other
	// 3-field struct and its fields are {i32, i32, eqref}, all immutable.

	// --- the instance struct (usesInstances only) ---------------------------------
	//
	// TYPE_INSTANCE = struct {i32 layout address (const), (ref null eq) slots (MUT)},
	// in its OWN rec group, appended after the fixed types, the --simd block and the
	// async block so every TYPE_* constant above keeps its value. `slots` holds a
	// TYPE_HASH_BUCKETS array, one element per layout slot; `layout` points at the
	// linear-memory record WasmInstanceLayouts bakes, which is what makes an instance
	// self-describing to the printer.
	//
	// The shape is 2 fields, BOTH mutable: field 1 because slots are written, field 0
	// because change-class swaps an instance's layout in place (todo-199). Two other
	// struct types share the {i32, eqref} shape: TYPE_CLOSURE {const i32, const eqref},
	// kept apart by rec-group identity (it is member 3 of the 5-member group), and
	// TYPE_P1_FUTURE {mut i32, mut eqref} -- which a MUTABLE field 0 makes structurally
	// IDENTICAL to this one. The rec group therefore carries a second, never
	// instantiated member: wasm canonicalizes a rec GROUP as a whole, so a 2-member
	// group can never equal the 1-member group TYPE_P1_FUTURE sits in, and ref.test
	// keeps telling an instance from a future. Do NOT "simplify" this to three fields
	// either: {i32, i32, eqref} with an immutable tail would canonicalize equal to
	// TYPE_VBLOCK under --simd and ref.test could no longer tell an instance from a
	// packed-array block.
	static final int INSTANCE_TYPE_COUNT = 2;

	// The exception tag index of the one Lisp condition tag ($lisp-cond), emitted only
	// in EH mode (the program uses handler-case/ignore-errors/unwind-protect). Its
	// payload is a cons (condition-instance . message-string) over TYPE_CONS, and its
	// function type reuses TYPE_PRINT_VAL (((ref null eq)) -> ()), so no type-section
	// entry is added. Tags have their own index space; $lisp-cond is always tag 0.
	static final int TAG_LISP_COND = 0;

	// The block-exit tag a cross-lambda return-from throws/catches, carrying a
	// (block-instance-id . value) cons; its payload type also reuses TYPE_PRINT_VAL, so
	// no
	// type-section entry is added. Emitted (tag 1) only when the program lowers a
	// cross-lambda exit, so a program that does not stays byte-identical to before.
	static final int TAG_BLOCK_EXIT = 1;

	// The empty block type (0x40) for block/try_table instructions without a result.
	static final int BLOCKTYPE_EMPTY = 0x40;

	// Global (wasm global section) index holding the runtime eval top-level environment
	// (an association list of cons(name, value) bindings; ref.null eq when empty).
	static final int GLOBAL_ENV = 0;

	// Global (wasm global section) index holding the runtime eval function namespace
	// (Lisp-2): defuns evaluated at runtime (e.g. from load) are bound here, separate
	// from the variable environment above. (Await results are memoized inside each
	// TYPE_P1_FUTURE struct, so no global cache is needed.)
	static final int GLOBAL_FENV = 1;

	// Memory layout
	static final int PRINT_BUF_OFFSET = 0;

	static final int IOV_OFFSET = 32;

	static final int NWRITTEN_OFFSET = 48;

	static final int OUT_BUF_OFFSET = 64;

	static final int HEAP_PTR_ADDR = 84;

	// FLOOR for the transient _start-prologue allocation that forces the engine to grow
	// its GC heap once, up front (see gcHeapPregrowBytes, the emission site in compile()
	// and .kb/wasm-gc-heap-pregrow.md). 16 MiB dwarfs the live set of a program that
	// loads no library stack at all; the array is garbage immediately, so the cost is a
	// one-time zeroing plus the retained heap capacity, not a live copy on every
	// collection.
	static final int GC_HEAP_PREGROW_BYTES = 16 * 1024 * 1024;

	// CEILING for the same allocation. Growth costs ~1.5 ms per MiB (first-touch page
	// faults on the new semispace), so the headroom a very large program would ask for
	// under the factor below is capped rather than paid in unbounded startup latency;
	// 64 MiB is twice what the largest stack measured here (cl-postgres + rove) needs.
	static final int GC_HEAP_PREGROW_MAX_BYTES = 64 * 1024 * 1024;

	// How much heap headroom one byte of emitted user code is worth. The long-lived
	// environment a library stack leaves behind -- interned symbols, function wrappers,
	// CLOS/defstruct metaobjects, the class and dispatch tables -- scales with the
	// amount of code loaded, and a copying collector needs roughly twice the live set as
	// headroom before it stops collecting every few hundred KB. A fixed 16 MiB covered
	// the biggest stack while that was cl-postgres alone; rove on top of it pushed the
	// live set past what 16 MiB buys, and on wasmtime 47 that is not merely slow but
	// WRONG -- the copying collector loses a live reference when it runs during an
	// exception unwind (.kb/wasm-gc-heap-pregrow.md) -- so the size follows the program
	// instead of being guessed once. Measured on that stack: 3.3 MB of emitted defuns
	// still collects at a 26.5 MiB heap and stops at 32 MiB, i.e. ~9x; 16x leaves the
	// same ~2x margin over the live set that the todo-188 sweep found the plateau at.
	static final int GC_HEAP_PREGROW_CODE_FACTOR = 16;

	/** The bump heap a program with no static data of its own still gets, in pages. */
	static final int HEAP_HEADROOM_MIN_PAGES = 3;

	// The serve-mode counterpart. A served component is instantiated MANY times over a
	// process lifetime (wasmtime serve retires an instance after
	// --max-instance-reuse-count requests, 128 by default for a WASIp3 component; Spin
	// uses the same default), so the pre-grow is paid per instance rather than per
	// process and its cost lands on request latency. It is not free: the growth is
	// linear in the size, ~1.5 ms per MiB on the 47.x engine (mostly first-touch page
	// faults on the new semispace), i.e. 25 ms for the 16 MiB above. 1 MiB is the
	// measured optimum across the reuse counts a real host uses -- see
	// .kb/wasm-gc-heap-pregrow.md for the sweep and the re-evaluation trigger.
	static final int GC_HEAP_PREGROW_SERVE_BYTES = 1024 * 1024;

	// Reader cursor/end (absolute byte offsets) used by the read/load runtime.
	static final int READ_CURSOR_ADDR = 88;

	static final int READ_END_ADDR = 92;

	// Scratch for path_open's output file descriptor (for load).
	static final int READ_FD_ADDR = 96;

	// Runtime intern table: a count cell plus a region of (offset,length) entries used by
	// _intern to give symbols parsed at runtime but absent from the compile-time table
	// (e.g. lambda parameters in loaded files) a stable offset across occurrences.
	static final int RT_INTERN_COUNT_ADDR = 100;

	// Capture mode for the string runtime: while CAPTURE_FLAG is non-zero, _write_str
	// appends bytes at CAPTURE_CUR (a heap cursor) instead of writing to stdout.
	static final int CAPTURE_FLAG_ADDR = 104;

	static final int CAPTURE_CUR_ADDR = 108;

	// Scratch for path_open's output file descriptor (for open).
	static final int OPEN_FD_ADDR = 112;

	// Tracks whether stdout is at the start of a line (0 = at line start, 1 = mid-line),
	// updated by _write_str on every stdout write so fresh-line (~&) can decide whether
	// to
	// emit a newline. Zero-initialized linear memory means we start at a line start.
	static final int LINE_START_ADDR = 116;

	// Scratch (8 bytes) where random_get writes its entropy bytes (Preview 1: the host's
	// wasi_snapshot_preview1 random_get; component: the adapter's wasi:random-backed
	// one).
	static final int RANDOM_SCRATCH_ADDR = 120;

	// Scratch (8 bytes) where clock_time_get writes the current time in nanoseconds.
	static final int TIME_SCRATCH_ADDR = 128;

	// getenv scratch: environ count / buffer-size words (low free region), and the
	// pointer
	// array + "KEY=VALUE\0" buffer placed in page 3 (the canonical realloc heap is page
	// 2).
	static final int ENV_COUNT_ADDR = 136;

	static final int ENV_BUFSIZE_ADDR = 140;

	// gensym counter word (zero-initialized linear memory, so the first symbol is
	// "#:g1").
	static final int GENSYM_CTR_ADDR = 144;

	// One-byte scratch cell moved through fd_read / fd_write by the read-byte /
	// write-byte runtime helpers (below the DATA_BASE_OFFSET=256 headroom, so no
	// interned string bytes are clobbered).
	static final int BYTE_SCRATCH_ADDR = 148;

	// Cell holding the runtime intern table's base address (see RT_INTERN_MIN_BASE);
	// seeded by an active data segment at instantiation from the program's actual
	// static-data size.
	static final int RT_INTERN_BASE_ADDR = 152;

	// Monotonic counter cell handing out the id (field 0) of every RUNTIME-built string
	// and uninterned symbol via _str_fresh. Seeded at instantiation to heapBase, so
	// runtime ids are always >= heapBase > every interned/rt-intern offset (identity
	// with literals/interned symbols is preserved) and never repeat even though the
	// bump scratch offset they were assembled at is reused (HEAP_PTR is a stack pointer
	// -- see FUNC_STR_FRESH). This is what retires the linear string heap leak.
	static final int STRING_ID_CTR_ADDR = 156;

	// Per-call snapshot cells for the --component canonical string ABI (todo 92 Tier 2):
	// the appended cabi_realloc saves HEAP_PTR + the runtime intern count on its first
	// call of an export invocation (ACTIVE flag), and the cabi_post_* post-return
	// restores HEAP_PTR when the intern count is unchanged (interned tokens are permanent
	// heap copies that must survive -- same guard as the serve adapter's per-request
	// reset). Zero-initialized memory means "no mark active"; no data segment needed.
	static final int CABI_MARK_ACTIVE_ADDR = 160;

	static final int CABI_MARK_HEAP_ADDR = 164;

	static final int CABI_MARK_INTERN_ADDR = 168;

	// High-water mark of the interned-symbol byte pool: the HEAP_PTR value just after
	// _intern's last PERMANENT advance. Only written when the host arena API is emitted
	// (a memory-exporting non-component module), so every other module's _intern body is
	// byte-identical to before. Zero-initialized memory means "nothing
	// permanent above heapBase yet", which is exactly what __ronto_alloc_reset's
	// max(mark, high-water) pop wants.
	static final int RT_INTERN_HEAP_ADDR = 172;

	// Doorbell scratch of the callback-task runtime (asyncMode with a callback-lifted
	// export): the standing doorbell reads of EVERY task share one 8-byte cell (the
	// payload is discarded -- overlapping host writes are harmless), and doorbell
	// writes read a throwaway u64 element out of a second one. Both 8-aligned, below
	// the DATA_BASE_OFFSET=256 headroom.
	static final int DB_READ_SCRATCH_ADDR = 176;

	static final int DB_WRITE_SCRATCH_ADDR = 184;

	// The reader's block-comment nesting depth (the inline whitespace skipper uses no
	// locals, so #| ... |# nesting counts through this cell instead).
	static final int RD_DEPTH_ADDR = 192;

	// Monotonic counter cell minting the dynamic block-instance id of the cross-lambda
	// non-local-exit machinery ({@code %nlx-tag}, see WasmNlxCompiler): each catch
	// activation gets the next integer as an i31 value, so throw/catch matching is
	// VALUE equality on i31 (ref.eq of equal i31s is true by spec), never GC-struct
	// identity. Zero-initialized memory starts the ids at 1.
	static final int NLX_ID_CTR_ADDR = 196;

	// peek-char's ONE-SLOT pushback for WASI file descriptors. A fd cannot be un-read,
	// so _peek_char reads a whole code point and parks it here; _read_char drains the
	// cell before touching the fd. PEEK_FD_ADDR holds fd+1 (0 = empty, so the
	// zero-initialized memory starts out drained) and PEEK_CP_ADDR the parked code
	// point. Keying on the fd is what keeps a peek on one stream from being consumed by
	// a read on another. String input streams never use it -- their record carries a
	// cursor, so peeking there is just "decode without advancing".
	static final int PEEK_FD_ADDR = 200;

	static final int PEEK_CP_ADDR = 204;

	// Scratch word where fd_readdir reports how many bytes it wrote into the listing
	// buffer (%list-directory). Still below the DATA_BASE_OFFSET=256 headroom, so no
	// interned string bytes are clobbered.
	static final int READDIR_USED_ADDR = 208;

	// The PRNG's 64-bit state cell (8-aligned at 216..223, still below the
	// DATA_BASE_OFFSET=256 headroom). EVERY build reads and writes it: `random` is a
	// SplitMix64 step over this cell inlined at the call site (WasmRandomCompiler), not
	// a host call, on Preview 1 / --component / --no-wasi alike (.kb/random.md).
	// Zero-initialized memory is a valid SplitMix64 seed -- the step adds the
	// golden-ratio gamma before mixing, so state 0 is no weaker than any other -- so no
	// data segment seeds it. A module with a host to ask replaces the zero with eight
	// bytes of random_get entropy on its FIRST draw (RANDOM_SEEDED_ADDR below); a
	// --no-wasi module has no host, so every instance of it walks the SAME sequence
	// unless the exported __ronto_seed_random hook is called (deliberate; see
	// .kb/wasm-export-no-wasi.md).
	static final int RANDOM_STATE_ADDR = 216;

	// The --no-wasi module's ONE clock: nanoseconds since the Unix epoch, handed over by
	// the exported __ronto_set_time hook (8-aligned at 224..231, still below the
	// DATA_BASE_OFFSET=256 headroom). Zero -- the state of untouched linear memory -- is
	// the UNSET sentinel, so no data segment seeds it, and the three clock built-ins
	// SIGNAL over that state instead of reporting the 1970 it literally names: a host
	// that never called the hook has handed over no time, and inventing one is what the
	// --no-wasi stub rule forbids. A host that did call it hands over its own real time,
	// which is not an invention at all -- see .kb/wasm-export-no-wasi.md. Only a
	// --no-wasi module reads this cell; every other build calls the clock_time_get
	// import, whose slot here stays the trapping backstop.
	static final int HOST_TIME_ADDR = 224;

	// The string output-stream buffer table's two bookkeeping cells (232/236, still
	// below the DATA_BASE_OFFSET=256 headroom): the high-water of slots ever handed out,
	// and the head of the free list of slots handed BACK by _close -- a slot index + 1,
	// so the untouched-memory zero is the empty list. The list itself is threaded
	// through the table's own entries (a free slot holds the next such value as an i31),
	// which puts it on the GC heap where the arena reset a host performs between calls
	// cannot reach it. See WasmStringStreamRuntimeBuilder.
	static final int OSTREAM_SLOT_ADDR = 232;

	static final int OSTREAM_FREE_ADDR = 236;

	// The --reentrant park-block allocator's two cells (240/244, still below the
	// DATA_BASE_OFFSET=256 headroom): the head of the free list of park blocks (0 =
	// empty, which untouched memory already says), and the park FLOOR -- the bump-heap
	// top just after the newest carve, which every arena pop of a reentrant module
	// clamps to so no pop can hand a live park block's bytes out again. See
	// WasmExportRuntimeBuilder.buildParkAllocBody.
	static final int PARK_FREE_ADDR = 240;

	static final int PARK_FLOOR_ADDR = 244;

	// _path_dirfd's out-parameter: how many leading bytes of the staged path the
	// preopen it resolved against already accounts for, so the pointer path_open sees is
	// `ptr + mem[PATH_SKIP_ADDR]` and its length `len - mem[PATH_SKIP_ADDR]`. Zero for a
	// relative path (the whole path goes to fd 3, as it always did). A second cell is
	// not needed: the fd is the function's result.
	static final int PATH_SKIP_ADDR = 248;

	// "The PRNG has been seeded" flag (252..255, the last word below the
	// DATA_BASE_OFFSET=256 headroom): zero-initialized memory means "not yet", so the
	// first `random` draw of an instance that HAS a host draws eight bytes from
	// random_get into RANDOM_STATE_ADDR and sets this, and no later draw pays a host
	// call. A --no-wasi module without --host-random emits no seeding at all -- it has
	// no host to ask -- and never reads this cell.
	static final int RANDOM_SEEDED_ADDR = 252;

	// The serve memory module's (mem-http-client.wat) canonical-ABI bump-pointer CELL,
	// and
	// the allocation base just above its 8 bytes. cabi_realloc keeps its pointer in this
	// linear-memory cell rather than a wasm global so the core -- which shares this
	// memory
	// -- can reset it: cabi_realloc is where the host writes an incoming request's result
	// buffers (path / headers / body), and it only grows, so an instance-reusing host
	// (jco /
	// wasmCloud) that calls handle many times on one instance would grow linear memory by
	// ~one request per call. WasmExportCompiler emits `mem[CELL] = BASE` at the top of
	// the
	// serve `handle` wrapper (the core HEAP_PTR needs no reset -- the %component-import
	// wrapper's pop-back already keeps it at the intern high-water). A memory cell needs
	// no
	// global import (which would shift the core's whole global index space) and no
	// adapter.
	// Serve only: a non-serve component's memory module is mem.wasm, unchanged.
	static final int CABI_HP_CELL_ADDR = 0x10000; // 65536, start of page 1 (serve
													// scratch)

	static final int CABI_HP_BASE = 0x10008; // 65544, just above the 8-byte cell

	static final int ENV_PTRS_ADDR = 0x30000; // 196608, page 3

	static final int ENV_BUF_ADDR = 0x34000; // 212992, page 3 + 16 KiB

	// argv scratch, the same shape one page-3 region higher: the count / buffer-size
	// words, then the pointer array and the "arg\0" buffer args_get fills. The words
	// live here rather than beside ENV_COUNT_ADDR because the low scratch region ends
	// at 255 and DATA_BASE_OFFSET (256) may not move -- shifting it would change the
	// static-data base of every module. The module's memory is floored at four pages,
	// so page 3 is always there.
	static final int ARGV_COUNT_ADDR = 0x38000; // 229376, page 3 + 32 KiB

	static final int ARGV_BUFSIZE_ADDR = 0x38004;

	static final int ARGV_PTRS_ADDR = 0x38010;

	static final int ARGV_BUF_ADDR = 0x3C000; // 245760, page 3 + 48 KiB

	// Socket scratch cell in page 4 (0x40000), between the rontolisp data/heap (pages
	// 0-3) and the adapter scratch (page 5+). sock.tcp-connect / tcp-listen /
	// tcp-accept write the preview1-style socket fd (>= 200, serviced by the sockets
	// adapter's fd_read/fd_write/fd_close branches) through this out-pointer; the
	// compiler boxes it as an i31 integer -- the stream handle. Component mode only.
	// (The address is historical: the cells below it held the deleted 0.2 fetch
	// adapter's scratch.)
	static final int SOCK_FD_ADDR = 0x40018;

	// Minimum base address of the growable runtime intern table (8-byte (offset,len)
	// records appended by _intern for symbols first seen at runtime). The actual base
	// is computed per program -- max(this, 16-aligned end of the static string
	// segment) -- and seeded into the RT_INTERN_BASE_ADDR cell at instantiation, so
	// the records can never start inside the interned-string data (which outgrows a
	// fixed 8192 base on large programs; the old fixed base let runtime interning
	// silently corrupt static strings and the eval registry).
	static final int RT_INTERN_MIN_BASE = 8192;

	// Bytes reserved for the runtime intern table between its base and the heap base
	// (the historical 8192..16384 gap). The bump-allocator heap starts at
	// rtInternBase + this.
	static final int RT_INTERN_REGION_SIZE = 8192;

	// The interned-string data segment must start ABOVE every fixed scratch address
	// below,
	// or the host/adapter writes for getenv (ENV_COUNT_ADDR=136 .. ENV_BUFSIZE_ADDR=143)
	// and
	// the time built-ins (TIME_SCRATCH_ADDR=128 .. 135) would clobber shared string bytes
	// (notably the newline at the old base+9). The highest scratch byte
	// (RANDOM_SEEDED_ADDR=252 .. 255) ends at 255, so 256
	// exactly fits it; the next fixed region (RT_INTERN_BASE=8192) is far above realistic
	// string-segment sizes. Shifting this base does not move any function/import index,
	// so
	// the --component blobs are unaffected (see CLAUDE.md index-stability invariant).
	private static final int DATA_BASE_OFFSET = 256;

	// The --component interned-string data base: page 6, ABOVE every other writer of
	// the component's ONE shared memory -- the core's fixed cells and env/socket
	// scratch (pages 0-4), the preview1 adapter's scratch cells (page 5, 0x50000..),
	// and the serve memory module's canonical-ABI cell/window at 0x10000. A large
	// program's static data (cl-postgres: ~3 MB) otherwise grows straight across
	// those regions: the segment bytes install at instantiation, so the adapter's
	// zero-initialized flag cells read back interned-string bytes and its first
	// blocking wait dies with "unknown handle index" (the cached waitable-set cell
	// held string data). Preview 1 keeps DATA_BASE_OFFSET and stays byte-identical;
	// under --component the non-serve canonical-ABI allocator no longer has a fixed
	// region at all (mem.wat's cabi_realloc bumps the core's HEAP_PTR cell -- one
	// shared monotonic allocator, see src/wasm-component/mem.wat).
	private static final int COMPONENT_DATA_BASE_OFFSET = 0x60000;

	/**
	 * Sets the feature names the compiled program's {@code *features*} starts out
	 * holding. The frontend passes the set it READ the program with, so a
	 * {@code (member :rontolisp-component *features*)} at run time answers what the
	 * {@code #+rontolisp-component} beside it answered at read time. Left alone, the
	 * backend's base set stands ({@link LispMacroExpander#backendFeatures}).
	 * @param features the feature names, without the leading colon
	 * @return this compiler
	 */
	public WasmLispCompiler runtimeFeatures(List<String> features) {
		this.runtimeFeatures = List.copyOf(features);
		return this;
	}

	@Override
	public byte[] compile(List<LispVal> program) {
		// The load-context brackets LoadInliner put around each spliced file become
		// assignments of *load-pathname* / *load-truename* -- when the program reads
		// either; otherwise they are dropped here and nothing downstream sees them.
		// Before the resolver, whose own marker arm is the backstop for a bracket this
		// pass did not lower.
		program = LispMacroExpander.lowerLoadContextMarkers(program);
		// Resolve packages (in-package directives, qualified symbols, *package*) up front
		// so
		// the rest of compilation sees canonical names.
		PackageResolver packageResolver = new PackageResolver();
		program = packageResolver.resolveProgram(program);
		// Splice top-level (progn ...)/(eval-when ...) so Pass 1 collects the defuns
		// nested in them (the CLI already flattens via UserMacroExpander; this keeps
		// direct compiler invocations equivalent).
		program = LispMacroExpander.flattenTopLevel(program);
		// rontolisp:wasm-import :async t declares that the host function may SUSPEND
		// (WebAssembly.Suspending / JSPI). The wrapper only wraps the result in a
		// settled future -- the suspension itself is the host's business -- so the
		// BUILD states what the host now owes (compiler/SuspendingImports; the
		// --host-fetch precedent). Skipped under --component, where the directive is
		// rejected outright below.
		Map<String, String> suspendingImports = this.component ? Map.of() : SuspendingImports.declared(program);
		// A function that can reach a suspending import escaped as #'name: the per-export
		// walk is seeded from that export alone, so a value handed over anywhere ELSE is
		// invisible to it and the answer below would under-report -- which is a MISSING
		// WebAssembly.promising, not a wasted one. Kept as a variable because the
		// generated host glue widens the same way (compiler/HostGlueEmitter).
		boolean anySuspendingImportEscapes = (!suspendingImports.isEmpty() || this.hostFetch)
				&& SuspendingImports.anyTakenAsValue(program, suspendingImports.keySet(), this.hostFetch);
		if (!suspendingImports.isEmpty()) {
			CompileWarnings.warn(":async t: this module imports " + String.join(", ", suspendingImports.values())
					+ ", declared suspending. The host must wrap each in WebAssembly.Suspending (JSPI), enter the"
					+ " exports that can reach one through WebAssembly.promising, and "
					+ (this.reentrant ? "may OVERLAP calls (--reentrant: the module owns its per-call state; a"
							+ " :string/:s-expr result crosses as a __ronto_park_alloc block the reader frees with"
							+ " __ronto_park_free)"
							: "serialise calls (a suspended"
									+ " module can be re-entered; a re-entered export refuses with a trap instead of"
									+ " corrupting both calls)")
					+ "; a host that answers synchronously is equally valid -- the call then returns"
					+ " an already-settled future either way");
			if (anySuspendingImportEscapes) {
				// Whoever received it can call it, so the per-export answer below would
				// under-report -- widen it to every export instead.
				CompileWarnings.warn(":async t: a function that can reach a suspending import is taken as a value"
						+ " (#'name), so ANY export may reach one -- enter every export through"
						+ " WebAssembly.promising");
			}
			else {
				List<String> exportsReaching = new ArrayList<>();
				for (LispVal expr : program) {
					if (WasmExportCompiler.isExportForm(expr)) {
						WasmExportCompiler.Decl decl = WasmExportCompiler.parse((LispCons) expr);
						if (suspendingImports.containsKey(decl.name())
								|| SuspendingImports.reaches(program, suspendingImports.keySet(), decl.name())) {
							exportsReaching.add(decl.exportName());
						}
					}
				}
				if (!exportsReaching.isEmpty()) {
					CompileWarnings.warn(":async t: the exports that can reach a suspending import -- enter each"
							+ " through WebAssembly.promising: " + String.join(", ", exportsReaching));
				}
			}
			if (this.noWasi) {
				// _initialize runs the load path on a stack no promising entered, and
				// the program DECLARED the host may suspend -- an error, not a line
				// (unlike --host-fetch, where a synchronous host is an equally valid
				// implementation of the flag).
				List<String> onLoadPath = SuspendingImports.onLoadPath(program, suspendingImports.keySet());
				if (!onLoadPath.isEmpty()) {
					throw new UnsupportedOperationException(onLoadPath.get(0));
				}
			}
		}
		// Whether a host import may SUSPEND (an :async t declaration, or --host-fetch's
		// env.fetch with fetch actually used): control then returns to the host's event
		// loop mid-call, so an export can be re-entered while the first call is parked.
		// Nothing in the module owns its state per call -- the allocator bracket's marks
		// interleave and the shallowly-bound specials share one global cell -- so the
		// export wrappers of such a module carry a re-entry guard that traps the second
		// entry instead of corrupting both calls. Any other module gains no guard, no
		// global, no instruction.
		boolean hostMaySuspend = !suspendingImports.isEmpty()
				|| (this.hostFetch && programUsesSymbol(program, LispNames.FETCH_QUALIFIED));
		// --reentrant relaxes the guard, so it only means anything where the guard would
		// have existed: a module nothing can suspend has no overlap to allow.
		if (this.reentrant && !hostMaySuspend) {
			throw new UnsupportedOperationException("--reentrant requires a program that can suspend (a"
					+ " rontolisp:wasm-import declared :async t, or --host-fetch with rontolisp:fetch used):"
					+ " without a suspension no host can overlap calls, so the flag would buy nothing");
		}
		// The streaming boundary's body imports compose with --reentrant only in their
		// ID-CARRYING shape (a leading :int naming the call / the reply, which the CLI
		// synthesizes under the flag): without it they are a GLOBAL cursor on the host
		// side -- "the current request's body", "the last fetch's reply" -- which is
		// exactly the per-call identity overlapped calls do not have. Refuse the id-less
		// shape (a hand-written reactor's own imports reach here) instead of shipping a
		// cursor that serves one call another call's octets.
		if (this.reentrant) {
			for (LispVal form : program) {
				if (!WasmImportCompiler.isImportForm(form)) {
					continue;
				}
				WasmImportCompiler.Decl decl = WasmImportCompiler.parse((LispCons) form);
				if (ReactorEnvelope.HOST_MODULE.equals(decl.module())
						&& (ReactorEnvelope.REQUEST_BODY_FIELD.equals(decl.field())
								|| ReactorEnvelope.RESPONSE_BODY_FIELD.equals(decl.field())
								|| FetchResponseShape.HOST_BODY_IMPORT_FIELD.equals(decl.field()))
						&& (decl.paramTypes().isEmpty() || decl.paramTypes().get(0) != BoundaryType.S32)) {
					throw new UnsupportedOperationException("--reentrant requires the streaming body protocol to carry"
							+ " a call identity: env." + decl.field()
							+ " is declared without the leading :int id, so it"
							+ " is a host-side cursor and overlapped calls would read each other's octets. Give the"
							+ " import a leading :int parameter naming the call (what --host-boundary=streaming"
							+ " synthesizes under --reentrant), or use --host-boundary=envelope, whose boundary"
							+ " carries no cursor");
				}
			}
		}
		if (this.noWasi) {
			// Every --no-wasi refusal is a call-time condition, which is right for a call
			// site that may be dead -- but reached from a TOP-LEVEL form there is nothing
			// to catch it and no output to read it in, so the host sees a nameless
			// RuntimeError. The build knows which ones the load path reaches, so it says
			// so (the clock's line is a HOST OBLIGATION rather than a refusal: it names
			// __ronto_set_time). Before the rewrite below, which is what takes the
			// file-opening forms out of the program.
			NoWasiLoadPathRefusals.report(program, this.hostRandom, this.hostFetch, this.component)
				.forEach(CompileWarnings::warn);
			// --host-fetch states its host obligation once, whatever position fetch
			// sits in: the compiler emits nothing for the suspension, so the BUILD is
			// the only place that can say what the host now owes (the clock-hook
			// precedent). A synchronous env.fetch is unconditionally valid; a
			// suspending one (WebAssembly.Suspending) constrains how the exports are
			// entered, and a re-entry is refused by the guard the wrappers carry.
			if (this.hostFetch && programUsesSymbol(program, LispNames.FETCH_QUALIFIED)) {
				// Which imports it really names is the BOUNDARY's answer
				// (--host-boundary), so the line reads the splice rather than restating
				// one shape: with the reply body in band there is no second import, no
				// pull and no mid-body failure to warn about.
				boolean split = declaresImport(program, FetchResponseShape.HOST_BODY_IMPORT_FIELD);
				CompileWarnings.warn("--host-fetch: this module imports env.fetch(request-json) -> " + (split
						? "response-head-json and env.readResponseBody(" + (this.reentrant ? "reply-id, " : "")
								+ "ptr, cap) -> i32, and every rontolisp:fetch crosses both"
								+ " -- the head with the call, the reply BODY pulled out of band afterwards (0 = end"
								+ " of stream, a negative count = the transfer failed mid-body)."
						: "response-json" + ", and every rontolisp:fetch crosses it whole -- the reply's body rides the"
								+ " head's own \"body\" key (--host-boundary=envelope).")
						+ " A host may answer synchronously; a host"
						+ " whose fetch suspends (WebAssembly.Suspending / JSPI) must enter every export through"
						+ " WebAssembly.promising, and "
						+ (this.reentrant
								? "may OVERLAP calls (--reentrant: the module owns its per-call state; a"
										+ " :string/:s-expr result crosses as a __ronto_park_alloc block the reader"
										+ " frees with __ronto_park_free)"
								: "must serialise calls (a suspended module can be"
										+ " re-entered; a re-entered export refuses with a trap instead of corrupting"
										+ " both calls)"));
			}
			// A --no-wasi module has no filesystem, so file-opening forms lower to
			// call-time error stubs. First, so every scan below (usesRead/usesEval,
			// EH mode, the funcall-dispatch gate) reads the program that is actually
			// compiled: clack's dead (read)/(eval) file loader otherwise holds the
			// gate open and pulls the reader+eval runtimes into every Worker module.
			program = NoWasiFilesystemStubs.rewrite(program);
		}
		// A (boundp 'name) over a literal symbol is decided here, against the globals the
		// top-level forms before it declare (compiler/CompileTimeBoundp): the probe is
		// what forces the eval runtime, and the guard it tests is what keeps the
		// definition it wraps from surfacing as a top-level definer. AFTER the --no-wasi
		// rewrite above for the same reason the scans below are: the fold refuses a
		// program that can eval, and clack's DEAD file loader is exactly such a program
		// until that rewrite takes it out. The CLI folds the same program before its
		// tree-shaker runs (where the rewrite has not happened yet); this run decides
		// what only the canonical spellings and the rewritten program can decide, and
		// keeps a direct compiler invocation equivalent.
		program = CompileTimeBoundp.fold(program, this.dynamic, true);
		if (this.optimize.eliminatesDeadCode()) {
			// A typecase clause whose type no call site's argument can have is dead code
			// the tree-shaker cannot see, because its reachability is by NAME
			// (compiler/DeadTypeBranchPruner, .kb/optimize-dead-code-elimination.md).
			// After the rewrite above: it is what closes the funcall-dispatch gate on a
			// clack Worker, and the pruner declines while the gate is open.
			program = DeadTypeBranchPruner.prune(program);
		}
		// rontolisp:await placement is checked on the raw forms; then every
		// async-defun/async-lambda lowers to an ordinary defun/lambda over the
		// %async-run primitive. On this backend %async-run runs the body immediately
		// and wraps the result in a settled future: Preview 1 has no asynchronous host
		// I/O (everything settles), and under --component the body's awaits block the
		// stackful task exactly like the rest of the module's I/O.
		// The (rontolisp:async (defun ...)) wrapper expands first (the CLI already did;
		// this keeps direct compiler invocations and the playground equivalent), so the
		// placement check and the async passes below only ever see the canonical
		// async-defun/async-lambda forms.
		try {
			program = LispMacroExpander.rewriteAsyncSugar(program);
			am.ik.rontolisp.macro.LispAsync.checkTopLevel(program);
		}
		catch (IllegalArgumentException ex) {
			throw new UnsupportedOperationException(ex.getMessage());
		}
		if (this.component) {
			// Socket I/O redirection + async promotion: a no-op unless sockets.lisp is
			// spliced. Runs before any await counting so the promoted awaits are sized
			// like user awaits.
			program = WasmSocketsRewrite.rewrite(program);
		}
		// --component compiles the raw async forms as entry+resume state machines
		// (WasmAsyncEmit): a top-level async-defun becomes a plain defun whose name is
		// remembered, and lowerProgram is skipped. Everything else (Preview 1, and a
		// component with no async surface -- where lowering is a no-op) keeps the
		// degenerate %async-run lowering.
		// %subtask-future counts too: a wit-import binding of an async func member
		// returns a first-class future through it, which needs the async runtime even
		// when the program itself never awaits (the caller may hand the future around).
		boolean usesAsync = programUsesSymbol(program, LispNames.ASYNC_DEFUN_QUALIFIED)
				|| programUsesSymbol(program, LispNames.ASYNC_LAMBDA_QUALIFIED)
				|| programUsesSymbol(program, LispNames.AWAIT_QUALIFIED)
				|| programUsesSymbol(program, LispNames.SUBTASK_FUTURE_INTERNAL_QUALIFIED);
		// A --no-wasi reactor component has no host I/O to suspend on (its import
		// surface is empty by contract), so it keeps the Preview 1 degenerate lowering:
		// every future settles immediately.
		this.asyncMode = this.component && !this.noWasi && usesAsync;
		// The callback-task runtime (per-task waitable-sets over context slots +
		// doorbell streams) exists exactly when the module has a CALLBACK-lifted export:
		// serve's `handle` (its %serve-handle target is an async-defun, so serve implies
		// asyncMode), or a test-designated export.
		boolean cbMode = this.asyncMode && (this.serve || !this.callbackExportsForTest.isEmpty());
		Set<String> asyncDefunNames = new HashSet<>();
		if (this.asyncMode) {
			program = rewriteTopLevelAsyncDefuns(program, asyncDefunNames);
		}
		else {
			program = am.ik.rontolisp.macro.LispAsync.lowerProgram(program);
		}
		// Whether the degenerate tier's first-class stream value can exist: the program
		// names %stream-new, its one producer. Outside asyncMode only -- a --component
		// async program builds TYPE_WASI_STREAMs from the same primitive instead.
		this.usesP1Streams = !this.asyncMode && programUsesSymbol(program, LispNames.STREAM_NEW_INTERNAL_QUALIFIED);
		// Whether a degenerate TYPE_P1_FUTURE can exist in this module at all. It has
		// exactly three producers outside asyncMode: the %async-run the lowering above
		// leaves behind (WasmAsyncRunCompiler), a `wasm-import ... :async t` wrapper
		// (WasmImportCompiler), and the settled future every stream-read of the line
		// above answers. The export wrappers read it to decide whether a returned
		// future must be resolved at the boundary; a module with no producer cannot
		// meet one and gains no instruction.
		boolean p1Futures = !this.asyncMode && (programUsesSymbol(program, LispNames.ASYNC_RUN_QUALIFIED)
				|| !suspendingImports.isEmpty() || this.usesP1Streams);
		// Splice top-level defstructs/defclasses/defgenerics/defmethods into their
		// generated defuns before lambda-list desugaring (the generated constructors
		// use &key) so Pass 1 collects them as ordinary functions; the registries make
		// accessors setf-able places and resolve make-instance/slot-value/dispatch.
		Map<String, Integer> structAccessors = new HashMap<>();
		ClosRegistry closRegistry = new ClosRegistry();
		// Whether the program uses the restart system (handler-bind / restart-case /
		// invoke-restart & friends). Decided on the SURFACE program -- the expansions
		// happen lazily during Pass 2, so the pre-scans below (block-exit tag, EH
		// mode, instance gate) cannot see their catch/throw/%obj-new products.
		// Computed before expandTopLevelDefinitions, which runs the same scan to
		// inject the restart-runtime defuns.
		boolean restartMode = LispMacroExpander.usesRestartSystem(program);
		// Whether signal needs the clause-type match at the signal point (the program
		// both signals and establishes a handler-case). Decided on the SURFACE program
		// like restartMode; expandTopLevelDefinitions runs the same scan to inject the
		// %hc-match-p defun and the cluster-stack defvar.
		boolean signalClauseMatch = LispMacroExpander.needsSignalClauseMatch(program);
		// Whether the entry function's landing pad will READ the condition that escapes
		// it (WasmUncaughtReportCompiler) -- which is exactly EH mode, decided below on
		// the post-expansion program but needed here, because the report-routing gate is
		// part of the expansion. The scan covers every trigger that can accompany a
		// signal: a catching/cleanup form, catch/throw, restart mode, asyncMode. The one
		// it cannot see is a cross-lambda return-from, which reaches ehMode through
		// blockExitTag and is lowered only after this pass; a program whose SOLE EH
		// trigger is one of those keeps the narrow gate, so its landing pad prints a
		// plain %error's message and an empty report for a typed condition. Widening
		// that means moving the lowering above this pass -- and the lowering has to run
		// after it, or a generated dispatcher's return-from would not be lowered at all.
		boolean reportsUncaught = programUsesEhForm(program) || this.asyncMode || restartMode
				|| programUsesSymbol(program, LispNames.CATCH) || programUsesSymbol(program, LispNames.THROW);
		// lazyConditionMessages: without a landing pad that reads it, this backend never
		// renders a signal's message (an uncaught condition is a bare trap and the
		// %error/%error-cond compilers skip the message operand), so the report-routing
		// gate narrows to "can program code HOLD a condition" -- see the
		// expandTopLevelDefinitions overload. In EH mode the landing pad IS a holder, of
		// every condition that escapes, so the gate goes back to the broad answer: the
		// report machinery it injects has ONE call site there, not one per signal.
		// The dispatch narrower drops generic-function branches no call site can select
		// (compiler/GenericDispatchNarrowing); only an optimizing, early-bound compile
		// may narrow -- under --dynamic any name resolves at run time.
		program = LispMacroExpander.expandTopLevelDefinitions(program, structAccessors, closRegistry,
				packageResolver::spellsAsExternal, this.dynamic, !reportsUncaught,
				this.optimize.eliminatesDeadCode() && !this.dynamic
						? new am.ik.rontolisp.compiler.GenericDispatchNarrowing() : null);
		// Whether any signal's message string is observable: the narrowed routing answer
		// (a message is read only through a HELD condition), forced on with it under
		// restart mode / --dynamic, and in EH mode by the landing pad -- a plain %error
		// carries its message in the payload cdr, which is the only text that landing
		// has. Read by WasmErrorCompiler to decide whether a plain %error compiles its
		// message operand.
		boolean condMessagesObservable = closRegistry.routesConditionReports() || restartMode || this.dynamic
				|| reportsUncaught;
		// Whether the PROGRAM itself needs the concatenate 'string argument normalizer
		// (see Ctx.usesSeqString); computed before the wrappers so the lowering only
		// calls a helper that is actually injected. AFTER expandTopLevelDefinitions --
		// the same slot the JVM compiler scans in -- so the registry can resolve a user
		// deftype alias of the string family the way the CONCATENATE lowering will.
		boolean usesSeqString = ConcatenateForms.needsSeqString(program, closRegistry);
		// Whether the packed (unsigned-byte 8|16|32) vector builder is reachable: a
		// concatenate whose result type spells a packed element type lowers to a call to
		// it, and so does the #'concatenate wrapper's own vector arm (its designator is a
		// runtime value, so it re-does the width dispatch there). No array gate to force
		// here -- the packed array types and _iv_set are unconditional on wasm-GC.
		boolean usesSeqIntVector = ConcatenateForms.needsSeqIntVector(program, closRegistry) || program.stream()
			.anyMatch(expr -> BuiltinFunctionWrappers.referencesFunctionValue(expr, LispNames.CONCATENATE));
		// A generic function whose name is a compiler-lowered built-in (fast-io's close
		// methods): rename its dispatcher, keep the built-in as the default method, and
		// route the program's call sites through it. No-op without such a generic.
		// Under --component with sockets.lisp spliced, WasmSocketsRewrite has already
		// redirected close/listen/... call sites onto its %io-* dispatch defuns, so the
		// alias map makes those heads dispatch too and points each fall-through at the
		// %io-* defun (whose socket-table bookkeeping must stay in the loop).
		program = ShadowedBuiltins.process(program, closRegistry,
				this.component ? WasmSocketsRewrite.builtinDispatchAliases(program) : java.util.Map.of());
		// A defun nested in a function body REDEFINES a top-level defun of the same name
		// at run time, and only a global variable can hold both answers: the top-level
		// definition is renamed and an assignment of its function value takes its place,
		// so the name resolves through the variable like every other non-top-level defun
		// (.kb/core-representation.md, "The NAME half"). A no-op unless the two
		// spellings actually meet, and placed after every pass that can introduce a
		// top-level defun of its own (defstruct/defclass accessors, ShadowedBuiltins).
		program = NestedDefunRedefinition.rewrite(program);
		// Every struct/class layout is registered by the pass above, so the instance gate
		// can be decided here -- it must be, because the struct type index and the baked
		// layout addresses are needed as constants before any body is compiled. Restart
		// mode forces it on: the signal hook synthesizes simple-* instances for plain
		// string signals.
		this.usesInstances = LispMacroExpander.mayCreateInstances(program, closRegistry) || restartMode;
		// The equalp key fold, decided on the same snapshot: a table whose keys are
		// folded carries a flag in its header count, so every count read in the module
		// has to agree about whether the flag is there. One program-wide answer is what
		// makes that agreement structural rather than a convention.
		this.usesEqualpHashTables = LispMacroExpander.programMakesEqualpHashTable(program);
		// The stream-value gate is decided on the SAME program snapshot, because
		// mayCreateInstances above already answers for it: read them apart and a later
		// desugaring could turn one on without the other, which is a %obj-new with no
		// instance type behind it.
		final boolean usesStreamValues = LispMacroExpander.mayCreateStreamValues(program);
		// Lower a return-from that crosses a lambda boundary into an EH-based non-local
		// exit (before desugarProgram, so the %fn-block wrap for a same-function
		// return-from naturally nests around the injected let/%nlx-catch).
		CrossLambdaExitLowering.Result crossLambda = CrossLambdaExitLowering.lower(program);
		program = crossLambda.program();
		// catch/throw throw on the same block-exit tag (a wrapped payload tells the two
		// kinds apart), so either one emits the tag and makes handler-case aware of it.
		// Restart mode rides it too: the restart-case expansion transfers through
		// catch/throw, invisible to the surface scans (blockExitTag also implies EH
		// mode below, which the expansions' unwind-protects need).
		boolean blockExitTag = crossLambda.used() || programUsesSymbol(program, LispNames.CATCH)
				|| programUsesSymbol(program, LispNames.THROW) || restartMode;
		// Desugar extended lambda lists (&optional/&key/&aux) into the native
		// "required + &rest" shape so the passes below only see that shape.
		program = LambdaLists.desugarProgram(program);
		// Create the %mv-spill global (a top-level setq) when the program uses a
		// multiple-value operator: the expansions read/write it across functions.
		program = LispMacroExpander.injectMvSpillGlobal(program, this.runtimeFeatures);
		// Bundle the surplus parameters of too-wide fixed-arity defuns into a list
		// (and rewrite their direct call sites) so real-library signatures compile
		// despite the MAX_CALLABLE_ARITY type limit.
		program = WasmArityBundler.bundle(program);
		// A CALL SITE wider than the fixed block is a different question from a too-wide
		// DEFUN: the arguments of a keyword lambda list go through verbatim for the
		// callee's own dispatcher to parse, so a seven-parameter function is funcalled
		// with eleven. Give the program its own per-arity dispatchers up to the widest
		// such site (appended, so no fixed index moves) and route only what is still too
		// wide through apply, whose SPREAD dispatcher has no per-argument parameter --
		// one
		// function over EVERY callable, and 12 KB of the zlib artifact when the only
		// thing
		// that wanted it was an eleven-argument funcall. BEFORE the usesEval scan below:
		// an injected apply is what turns the eval runtime (and with it the spread
		// dispatcher's body) on.
		int widestDispatch = WasmArityBundler.widestDispatchArity(program);
		this.extraCallArity = widestDispatch > MAX_CALLABLE_ARITY + MAX_EXTRA_CALL_ARITY ? 0
				: Math.max(0, widestDispatch - MAX_CALLABLE_ARITY);
		program = WasmArityBundler.spreadOverArityFuncalls(program, callArityCeiling());
		// Detect whether the program uses (eval ...). When it does, a runtime
		// interpreter (_eval) and a function-name registry are emitted, and dispatch
		// functions are generated for every registered arity so _eval can apply them.
		// The reader runtime is emitted for read/load; load also evaluates each form, so
		// it pulls in the eval runtime as well.
		// %host-argv on Preview 1: the two WASI command-line imports and the real _argv
		// body, or neither. A --component program has the spliced environment.lisp defun
		// instead (get-arguments off the fixed block), and a --no-wasi reactor has no
		// command line at all -- the expression compiler answers nil there, the way it
		// does for %host-getcwd.
		boolean usesHostArgv = !this.component && !this.noWasi && programUsesSymbol(program, LispNames.HOST_ARGV);
		boolean usesLoad = programUsesSymbol(program, LispNames.LOAD);
		boolean usesRead = programUsesSymbol(program, LispNames.READ)
				|| programUsesSymbol(program, LispNames.READ_FROM_STRING) || usesLoad;
		// boundp/symbol-value/fboundp probe the eval global envs through
		// _env_lookup/_lookup, so they force the eval runtime; intern needs the real
		// _intern body (canonical offsets) which lives in the reader runtime.
		boolean usesEval = programUsesEval(program) || usesLoad || this.dynamic
				|| programUsesSymbol(program, LispNames.BOUNDP) || programUsesSymbol(program, LispNames.SYMBOL_VALUE)
				|| programUsesSymbol(program, LispNames.FBOUNDP) || programUsesSymbol(program, LispNames.FMAKUNBOUND)
				// (setf (symbol-function ...)) writes GLOBAL_FENV (the raw place shape
				// is scanned: the %set-symbol-function lowering happens per expression,
				// after this gate).
				|| LispMacroExpander.usesSymbolFunctionWrite(program);
		// A runtime apply -- a computed designator, a multiple-value-call, or a
		// flet-bound/unknown literal target -- needs _apply and the SPREAD dispatcher,
		// but NOT the _eval interpreter: an apply whose literal #'f/'f target names a
		// compiled function is a physical direct call and needs neither (todo-315; it
		// used to put the whole eval runtime into the artifact). The wrapper-name set
		// counts only wrappers whose injection the apply site itself guarantees:
		// unconditional catalog entries plus the reference-gated group (the #'name
		// spelling is the reference), minus every group behind a program-scan gate the
		// #'name spelling does not fire.
		java.util.Set<String> applyGateWrappers = BuiltinFunctionWrappers.wrapperNames();
		applyGateWrappers.removeAll(BuiltinFunctionWrappers.WASM_UNSUPPORTED);
		applyGateWrappers.removeAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		applyGateWrappers.removeAll(BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS);
		applyGateWrappers.remove(LispNames.PARSE_INTEGER);
		applyGateWrappers.remove(LispNames.READ_FROM_STRING);
		applyGateWrappers.remove(LispNames.INTERN);
		applyGateWrappers.remove(LispNames.SEQ_STRING);
		applyGateWrappers.remove(LispNames.SEQ_INT_VECTOR);
		boolean usesApplyRuntime = usesEval || LispMacroExpander.needsApplyRuntime(program, applyGateWrappers)
		// An INJECTED wrapper whose body calls apply -- the map* family,
		// every/some, funcall -- is reachable as soon as the program takes that
		// operator as a first-class value. The wrappers are added after this
		// scan, so without this clause _apply stayed a nil-answering stub and
		// (funcall #'mapcar #'list '(1 2) '(3 4)) answered (NIL NIL) here while
		// the interpreter and the JVM answered ((1 3) (2 4)).
				|| program.stream().anyMatch(BuiltinFunctionWrappers::referencesApplyingWrapper);
		// A funcall/apply through a RUNTIME designator resolves a symbol late through
		// the name registry (see the _lookup emission gate below).
		boolean usesRuntimeDesignator = LispMacroExpander.usesRuntimeFunctionDesignator(program);
		// rontolisp:fetch is component-only: it is the spliced http.lisp defun over the
		// canon-lowered wasi:http user import. In Preview 1 mode it raises a compile
		// error (WasmFetchCompiler). await/futurep are generic future operations that
		// compile in every mode.
		// EH mode: the program uses a catching/cleanup form, so the module carries the
		// $lisp-cond exception tag, %error/%error-cond throw it (instead of a bare
		// unreachable) and every entry function converts an uncaught throw back into a
		// trap with a catch_all wrapper. A program without these forms is byte-identical
		// to a build that never knew about EH (the usesStringOp gating precedent). The
		// with-* macros and the usocket guard/with-* family count as triggers too (the
		// todo-129 step-7 retrofit): their expansions ride unwind-protect /
		// handler-case on WASM now, so a program using them needs the EH machinery --
		// and the `wasmtime -W exceptions=y` run flag.
		// asyncMode implies EH mode: the async entry's reject path and the
		// rejected-await re-signal throw on the $lisp-cond tag.
		// A cross-lambda return-from lowers to a throw/catch on a dedicated block-exit
		// tag,
		// so it forces EH mode (and the `wasmtime -W exceptions=y` run flag) exactly like
		// a
		// catching form. A program without one stays byte-identical and flag-free.
		boolean ehMode = programUsesEhForm(program) || this.asyncMode || blockExitTag;
		// Whether the entry function gets the uncaught-condition landing pad. It writes
		// fd 2 through a %warn call the compiler SYNTHESIZES in pass 2, so it is a
		// producer of the reserved *error-output* handle that no scan of the user's text
		// can find -- and the --component narrowing below reads this same fact rather
		// than re-deriving one, so the two cannot drift apart.
		boolean uncaughtReportPad = WasmUncaughtReportCompiler.emittedFor(ehMode);
		// The rontolisp:tcp-* built-ins are component-only the same way: they are the
		// spliced sockets.lisp defuns over a wit-imported wasi:sockets@0.3.0 (an
		// ordinary user import -- the base variant; the dedicated sockets blob variant
		// and its hand-written adapter are gone). In Preview 1 mode they raise a
		// compile error (WasmExprCompiler). fetch + tcp and serve + tcp now compose:
		// both are just user imports of different interfaces.
		// Pass 1: Collect defun declarations and top-level expressions. Lisp-2: only a
		// real (defun ...) form defines a function; a top-level (setq name (lambda ...))
		// binds a variable to a closure like any other setq.
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		// (rontolisp:wasm-export ...) directives: collected here and turned into
		// host-callable
		// export
		// wrappers below. They produce no code in the _start body. In component mode the
		// scalar wrappers are additionally lifted into component-model exports
		// (WasmComponentBuilder).
		List<WasmExportCompiler.Decl> exportDecls = new ArrayList<>();
		// (rontolisp:wasm-import ...) directives: each becomes a Lisp-callable wrapper
		// registered like a top-level defun (Preview 1 only; rejected in component
		// mode), calling the imported host function through a placeholder index that
		// the WasmImportInjector post-pass resolves.
		List<WasmImportCompiler.Decl> importDecls = new ArrayList<>();
		// (rontolisp::%component-import ...) forms (the --component lowering of
		// rontolisp:wit-import): each bound WIT function becomes a synthetic defun whose
		// body marshals through the canonical ABI, and the interface becomes a
		// component-level import wired by WasmComponentBuilder (canon lower).
		List<WasmComponentImportCompiler.Import> componentImports = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				defuns.add(extractSetqLambda(LispMacroExpander.expandDefun(cons)));
			}
			else if (WasmExportCompiler.isExportForm(expr)) {
				exportDecls.add(WasmExportCompiler.parse((LispCons) expr));
			}
			else if (am.ik.rontolisp.compiler.JvmExportDirective.isExportForm(expr)) {
				// rontolisp:jvm-export declares a typed Java entry point for the JVM
				// backend; on WASM it is a no-op, exactly as wasm-export is on the JVM.
			}
			else if (WasmImportCompiler.isImportForm(expr)) {
				importDecls.add(WasmImportCompiler.parse((LispCons) expr));
			}
			else if (WasmComponentImportCompiler.isComponentImportForm(expr)) {
				componentImports.add(WasmComponentImportCompiler.parse((LispCons) expr));
			}
			else {
				topLevelExprs.add(expr);
			}
		}
		// _start drops every top-level form's value, so a form that is nothing BUT a
		// value has nothing to emit. The resolvers leave these behind in bulk -- an
		// in-package/defpackage directive resolves to a quoted symbol, an unselected
		// eval-when to nil (compiler/ToplevelStatements,
		// .kb/toplevel-statement-values.md).
		topLevelExprs = ToplevelStatements.prune(topLevelExprs);
		// Two independently spliced libraries can bind the SAME interface (each in its
		// own %-package). Merge them into one import per
		// interface so the component-level wiring sees a single instance -- the two
		// packages'
		// wrappers stay distinct defuns (their duplicate core imports of the same host
		// function are legal and both resolve to the one lowered instance export). A
		// program
		// with no such overlap is unchanged (merge is a no-op), so every existing
		// component
		// stays byte-identical.
		componentImports = WasmComponentImportCompiler.mergeByIface(componentImports);
		// An interface whose resources another one uses must be imported first (its
		// instance is what the dependent's instance type aliases the resource out of).
		// Sorted HERE, once, so the component wiring, the synthesized core instances, the
		// core module's import fields and its instantiation arguments cannot disagree.
		componentImports = WasmComponentImportCompiler.inDependencyOrder(componentImports);
		if (!importDecls.isEmpty() && this.component) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-import is not supported with --component (Preview 1 core modules only)");
		}
		if (!componentImports.isEmpty() && !this.component) {
			throw new UnsupportedOperationException("the canonical-ABI import lowering requires --component");
		}
		// The one shared enforcement point of the --no-wasi contract on this path: a
		// reactor component imports NOTHING, and every WIT interface binding -- a user
		// rontolisp:wit-import or a wasi:*-binding library splice the CLI failed to
		// gate -- would become a component-level instance import.
		if (!componentImports.isEmpty() && this.component && this.noWasi) {
			throw new UnsupportedOperationException(
					"--no-wasi asks for a component that imports nothing, but the program binds the WIT interface '"
							+ componentImports.get(0).ifaceId()
							+ "' (rontolisp:wit-import); drop --no-wasi or the import");
		}
		// Register each import as a synthetic defun so ordinary calls, #'name, funcall
		// and eval all reach it through the regular defun machinery; Pass 2a swaps in
		// the marshalling wrapper body instead of compiling the (empty) Lisp body.
		Map<String, WasmImportCompiler.Decl> importWrappers = new LinkedHashMap<>();
		for (WasmImportCompiler.Decl decl : importDecls) {
			boolean duplicate = importWrappers.containsKey(decl.name())
					|| defuns.stream().anyMatch(d -> d.name.equals(decl.name()));
			if (duplicate) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-import name collides with an existing function: " + decl.name());
			}
			List<String> paramNames = new ArrayList<>();
			// lispArity, not the declared parameter count: a :returns :bytes import takes
			// one extra trailing argument, the caller-passed receive buffer.
			for (int i = 0; i < WasmImportCompiler.lispArity(decl); i++) {
				paramNames.add("%wasm-import-p" + i);
			}
			importWrappers.put(decl.name(), decl);
			defuns.add(new DefunDecl(decl.name(), paramNames, false, List.of()));
		}
		// Component-import bindings register the same way: a synthetic defun per bound
		// WIT function (Pass 2a reserves the body slot; the canonical-ABI marshalling
		// body is filled in once the memory-helper indices are known).
		Map<String, WasmComponentImportCompiler.Decl> componentImportWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
				boolean duplicate = componentImportWrappers.containsKey(decl.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(decl.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + decl.lispName());
				}
				List<String> paramNames = new ArrayList<>();
				for (int i = 0; i < WasmComponentImportCompiler.lispArity(decl); i++) {
					paramNames.add("%component-import-p" + i);
				}
				componentImportWrappers.put(decl.lispName(), decl);
				defuns.add(new DefunDecl(decl.lispName(), paramNames, false, List.of()));
			}
		}
		// A resource drop binds the same way, and takes the handle as its one parameter.
		// It follows the function bindings in the placeholder ordinal space, so the two
		// maps are walked in this order everywhere below.
		Map<String, WasmComponentImportCompiler.Drop> componentDropWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				boolean duplicate = componentDropWrappers.containsKey(drop.lispName())
						|| componentImportWrappers.containsKey(drop.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(drop.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + drop.lispName());
				}
				componentDropWrappers.put(drop.lispName(), drop);
				defuns.add(new DefunDecl(drop.lispName(), List.of("%component-import-p0"), false, List.of()));
			}
		}
		// An async built-in binds the same way. It follows the drops in the placeholder
		// ordinal space, so the three maps are walked in this order everywhere below.
		Map<String, WasmComponentImportCompiler.Async> componentAsyncWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				boolean duplicate = componentAsyncWrappers.containsKey(async.lispName())
						|| componentDropWrappers.containsKey(async.lispName())
						|| componentImportWrappers.containsKey(async.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(async.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + async.lispName());
				}
				List<String> paramNames = new ArrayList<>();
				for (int i = 0; i < WasmComponentImportCompiler.lispArity(async); i++) {
					paramNames.add("%component-import-p" + i);
				}
				componentAsyncWrappers.put(async.lispName(), async);
				defuns.add(new DefunDecl(async.lispName(), paramNames, false, List.of()));
			}
		}
		// An async func member binds TWO wrappers -- the start (async-lowered call,
		// returning the (packed . retptr) token) and the lift (result lift out of the
		// return area, called by _subtask_future / the scheduler once the subtask has
		// returned) -- registered like the other synthetic defuns, after the async
		// built-ins in the placeholder ordinal space.
		Map<String, WasmComponentImportCompiler.AsyncCall> componentCallStartWrappers = new LinkedHashMap<>();
		Map<String, WasmComponentImportCompiler.AsyncCall> componentCallLiftWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.AsyncCall call : imported.calls()) {
				for (String wrapperName : List.of(call.startName(), call.liftName())) {
					boolean duplicate = componentCallStartWrappers.containsKey(wrapperName)
							|| componentCallLiftWrappers.containsKey(wrapperName)
							|| componentAsyncWrappers.containsKey(wrapperName)
							|| componentDropWrappers.containsKey(wrapperName)
							|| componentImportWrappers.containsKey(wrapperName)
							|| defuns.stream().anyMatch(d -> d.name.equals(wrapperName));
					if (duplicate) {
						throw new UnsupportedOperationException(
								"rontolisp:wit-import name collides with an existing function: " + wrapperName);
					}
				}
				List<String> startParams = new ArrayList<>();
				for (int i = 0; i < WasmComponentImportCompiler.lispArity(call); i++) {
					startParams.add("%component-import-p" + i);
				}
				componentCallStartWrappers.put(call.startName(), call);
				defuns.add(new DefunDecl(call.startName(), startParams, false, List.of()));
				componentCallLiftWrappers.put(call.liftName(), call);
				defuns.add(new DefunDecl(call.liftName(), List.of("%component-import-p0"), false, List.of()));
			}
		}
		// A task-return built-in binds the same way (one parameter: the result value).
		Map<String, WasmComponentImportCompiler.TaskReturn> componentTaskReturnWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				boolean duplicate = componentTaskReturnWrappers.containsKey(tr.lispName())
						|| componentCallStartWrappers.containsKey(tr.lispName())
						|| componentCallLiftWrappers.containsKey(tr.lispName())
						|| componentAsyncWrappers.containsKey(tr.lispName())
						|| componentDropWrappers.containsKey(tr.lispName())
						|| componentImportWrappers.containsKey(tr.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(tr.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + tr.lispName());
				}
				componentTaskReturnWrappers.put(tr.lispName(), tr);
				defuns.add(new DefunDecl(tr.lispName(), List.of("%component-import-p0"), false, List.of()));
			}
		}
		// A :s-expr export parameter parses host-provided text with the embedded reader,
		// so
		// force the reader runtime on (FUNC_READ_EXPR must be a real body, not a stub).
		// Applies to Preview 1 / no-wasi and (since todo 92 Tier 2) component non-serve;
		// serve mode's synthetic %http-dispatch export is :string-only.
		boolean exportNeedsReader = !(this.component && this.serve) && exportDecls.stream()
			.anyMatch(d -> d.paramTypes().contains(am.ik.rontolisp.compiler.BoundaryType.S_EXPR));
		// An :s-expr import result likewise parses host-provided text at runtime.
		boolean importNeedsReader = importDecls.stream().anyMatch(WasmImportCompiler::needsReader);
		if (exportNeedsReader || importNeedsReader) {
			usesRead = true;
		}
		// The intern built-in canonicalizes through the reader runtime's _intern (so a
		// runtime-interned symbol's offset matches literals in env lookups); it forces
		// the real _intern body without pulling in the rest of the reader.
		// uiop:symbol-call lowers to (funcall (intern ...) ...) inside the expression
		// compiler, after this scan -- its pre-lowering spelling counts (todo-229).
		boolean usesIntern = usesRead || programUsesSymbol(program, LispNames.INTERN)
				|| programUsesSymbol(program, LispNames.UIOP_SYMBOL_CALL)
				// A computed find-symbol (call position or the reference-gated #'
				// wrapper) lowers to intern, so it needs the _intern runtime too.
				|| programUsesSymbol(program, LispNames.FIND_SYMBOL);

		// Inject built-in function wrappers (user defuns take priority)
		Set<String> userDefinedNames = new HashSet<>();
		for (DefunDecl defun : defuns) {
			userDefinedNames.add(defun.name);
		}
		// parse-integer is inlined on WASM (no helper), but read-from-string pulls in the
		// reader runtime (FUNC_READ_EXPR), emitted only when usesRead. Exclude each
		// wrapper
		// unless the program references the symbol so the wrapper and its helper stay
		// gated
		// together (parse-integer gated for symmetry with the JVM backend).
		Set<String> wrapperExcludes = new HashSet<>(BuiltinFunctionWrappers.WASM_UNSUPPORTED);
		if (!programUsesSymbol(program, LispNames.PARSE_INTEGER)) {
			wrapperExcludes.add(LispNames.PARSE_INTEGER);
		}
		if (!usesRead) {
			wrapperExcludes.add(LispNames.READ_FROM_STRING);
		}
		// The intern wrapper body calls _intern_sym -> _intern, which is a stub unless
		// the program itself calls intern (usesIntern), so gate the wrapper the same way
		// as read-from-string.
		if (!usesIntern) {
			wrapperExcludes.add(LispNames.INTERN);
		}
		// Hash-table wrappers compile inline hash code (and register maphash's arity-2
		// dispatch); only inject them when the program uses a hash table, gating the
		// whole
		// group together for symmetry with the JVM backend.
		if (!programUsesAnyHashOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		}
		// Fill-pointer array wrappers likewise: inject them only for array-using
		// programs, for symmetry with the JVM backend (where they reference the gated
		// array runtime helpers).
		if (!programUsesAnyArrayOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS);
		}
		// %seq-string is the concatenate 'string argument normalizer, not a first-class
		// value: inject it exactly when a lowering will call it
		// (.kb/concatenate-result-families.md).
		if (!usesSeqString) {
			wrapperExcludes.add(LispNames.SEQ_STRING);
		}
		// %seq-int-vector is the concatenate packed-vector builder, gated the same way.
		if (!usesSeqIntVector) {
			wrapperExcludes.add(LispNames.SEQ_INT_VECTOR);
		}
		// #'error/#'cerror/#'signal/#'warn wrappers forward the datum only (lite), and
		// #'format renders via the runtime control renderer; inject each only when the
		// program takes the operator as a first-class value, so every other program
		// stays byte-identical (JVM gate mirrored). Condition :report lambdas live only
		// in the class registry (define-condition is rewritten out of the program) but
		// are re-injected by the error/signal expansions, so they count as references
		// too.
		for (String op : BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS) {
			if (program.stream().noneMatch(expr -> BuiltinFunctionWrappers.referencesFunctionValue(expr, op))
					&& closRegistry.conditionReports()
						.values()
						.stream()
						.noneMatch(report -> BuiltinFunctionWrappers.referencesFunctionValue(report, op))) {
				wrapperExcludes.add(op);
			}
		}
		// The defuns from here down are INJECTED runtime, not the user's program: the
		// built-in wrapper catalog and the shared sequence helpers. Their bodies funcall
		// a designator PARAMETER, so every one of them is a dispatch of a designator the
		// compiler cannot read -- in every program, including one whose own text has no
		// higher-order call at all. Recording which defuns they are is what lets the
		// name-registry gate below read the user's designators only
		// (Ctx.injectedRuntimeBody).
		Set<String> injectedRuntimeDefuns = new HashSet<>();
		List<LispVal> wrappers = BuiltinFunctionWrappers.generate(userDefinedNames, wrapperExcludes);
		for (LispVal wrapper : wrappers) {
			DefunDecl decl = extractSetqLambda(wrapper);
			injectedRuntimeDefuns.add(decl.name);
			defuns.add(decl);
		}
		// The shared merge sort, once per program that sorts -- from its own source or
		// from the #'sort wrapper just added, which is why this sits here beside the
		// other shared sequence helpers (.kb/sort.md). When it is absent
		// WasmExprCompiler keeps the inline sort.
		if (!userDefinedNames.contains(LispNames.SORT_RUNTIME)
				&& (LispMacroExpander.programUsesSort(program) || LispMacroExpander.programUsesSort(wrappers))) {
			DefunDecl sortDecl = extractSetqLambda(LispMacroExpander.sortRuntimeWrapper());
			injectedRuntimeDefuns.add(sortDecl.name);
			defuns.add(sortDecl);
		}
		// The shared subseq dispatch, once per program that calls subseq -- from its own
		// source or from a wrapper body just added, which is why this is here and not in
		// expandTopLevelDefinitions (.kb/subseq-runtime.md). No array gate: this backend
		// holds every representation unconditionally, so the helper is a strict
		// improvement wherever there is a caller, and WasmSubseqCompiler routes a site to
		// it exactly when it is present.
		//
		// The replace/fill/map-into runtimes sit beside it for the same reason (a
		// #'replace / #'fill wrapper body is a site of its own), and BEFORE it: their
		// bodies call subseq, so they count toward its gate
		// (.kb/sequence-op-runtimes.md).
		List<LispVal> seqOpHelpers = userDefinedNames.stream()
			.anyMatch(LispMacroExpander.sequenceOpRuntimeNames()::contains) ? List.of()
					: LispMacroExpander.sequenceOpRuntimeWrappers(program, wrappers);
		for (LispVal helper : seqOpHelpers) {
			DefunDecl decl = extractSetqLambda(helper);
			injectedRuntimeDefuns.add(decl.name);
			defuns.add(decl);
		}
		if (!userDefinedNames.contains(LispNames.SUBSEQ_RUNTIME)
				&& (LispMacroExpander.programUsesSubseq(program) || LispMacroExpander.programUsesSubseq(wrappers)
						|| LispMacroExpander.programUsesSubseq(seqOpHelpers))) {
			DefunDecl decl = extractSetqLambda(LispMacroExpander.subseqRuntimeWrapper());
			injectedRuntimeDefuns.add(decl.name);
			defuns.add(decl);
		}
		// The shared sequence-conversion trio, once per program whose lowerings can
		// reach a literal coerce -- every generic sequence operator's dispatch does, and
		// most sites live in the wrapper bodies just added, so this sits beside the
		// subseq helper for the same reason. No array gate here either; the compilers'
		// coerce case routes a site to the trio exactly when it is present
		// (.kb/seq-conversion-runtime.md).
		if (!userDefinedNames.contains(LispNames.SEQ_TO_LIST) && (LispMacroExpander.programUsesSeqConversion(program)
				|| LispMacroExpander.programUsesSeqConversion(wrappers))) {
			for (LispVal helper : LispMacroExpander.seqConversionWrappers()) {
				DefunDecl decl = extractSetqLambda(helper);
				injectedRuntimeDefuns.add(decl.name);
				defuns.add(decl);
			}
		}

		// Collect top-level global variables and give each its own module-level wasm
		// global (mut (ref null eq)), placed after GLOBAL_ENV/GLOBAL_FENV (indices 2+).
		// A reference compiles to global.get from any function body, so a defun/lambda
		// can read a defvar/defparameter global. Indices follow declaration order.
		// A SequencedSet for the same reason specialVars below is one: this set's
		// iteration order assigns the module-global indices
		// (.kb/emitted-output-determinism.md).
		SequencedSet<String> globals = GlobalVarCollector.collect(topLevelExprs);
		// A defun nested in a top-level defun's BODY needs the same store: it lowers to
		// (setq name (lambda ...)) like every other non-top-level defun, and a top-level
		// defun is not among topLevelExprs, so this is the one spelling collect() cannot
		// see.
		globals.addAll(GlobalVarCollector.collectNestedInDefunBodies(program));
		// Both spellings of a non-top-level defun, for the call sites: the function value
		// of such a name is only ever in its global variable, so a call and a #'name have
		// to reach the variable before the --dynamic late-binding fallback does.
		Set<String> nestedDefunNames = GlobalVarCollector.collectAllNestedDefunNames(program);
		// Special (dynamically bound) variables need the same module-global backing store
		// (a
		// let of a special save/restores over it), so union them in before indices are
		// assigned; a let/let* of one of these names becomes a dynamic binding
		// (WasmLetCompiler). Collected over the WHOLE program: a local (declare
		// (special x)) inside a defun body (cl-ppcre's remove-registers-p) must make x
		// a global cell for its free readers too.
		// A SequencedSet, not a plain Set: this order assigns the module-global indices,
		// and collectDynamicallyBound copies it wholesale when the program has a progv,
		// so an unordered set here makes the emitted module differ per JVM run
		// (.kb/emitted-output-determinism.md).
		SequencedSet<String> specialVars = SpecialVarCollector.collect(program);
		globals.addAll(specialVars);
		// --reentrant: the specials that are ever DYNAMICALLY BOUND get a slot in the
		// per-call task record (WasmDynVars); every other special keeps its plain
		// module-global read. The JVM hybrid's decision procedure, and the JVM's
		// contract with it: over-collection is only a read cost, under-collection is a
		// compile-time throw at the binding site, never a silent process-global binding.
		Map<String, Integer> dynSlots = new LinkedHashMap<>();
		if (this.reentrant) {
			for (String name : SpecialVarCollector.collectDynamicallyBound(program, specialVars)) {
				dynSlots.put(name, dynSlots.size());
			}
		}
		Map<String, Integer> globalIndices = new HashMap<>();
		int nextGlobalIndex = GLOBAL_FENV + 1;
		for (String g : globals) {
			globalIndices.put(g, nextGlobalIndex++);
		}
		int globalCount = globals.size();
		// EH mode: the handler-depth counter global goes AFTER the user-variable
		// globals, so their indices are unchanged whether or not EH mode is on.
		int ehDepthGlobalIndex = ehMode ? GLOBAL_FENV + 1 + globalCount : -1;
		// asyncMode: the scheduler registry (a cons list of (waitable . (kind .
		// (future . data))) entries -- kind 0 = subtask, kind 1 = in-flight stream
		// read), the CURRENT task's waitable-set handle, the read-buffer free list,
		// the CURRENT task record (null at a synchronous boundary), the task-record
		// list of the callback driver and its id counter, after the EH depth counter
		// (asyncMode implies ehMode). Every non-async module is byte-identical to a
		// build that never knew about them.
		int schedRegistryGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 1 : -1;
		int schedSetGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 2 : -1;
		int schedReadFreeGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 3 : -1;
		int currentTaskGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 4 : -1;
		int tasksGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 5 : -1;
		int taskSeqGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 6 : -1;
		// Serve mode: the init-once flag (a mut i32 = 0) the handle wrapper checks. A
		// serve component's `run` is never lifted, so nothing else executes the top
		// level (defvar/defparameter globals, library state, user top-level forms);
		// the first handle call runs _start under this flag instead. Appended after
		// every other global so non-serve output is byte-identical (serve implies
		// asyncMode, so taskSeqGlobalIndex is always valid here).
		int serveInitGlobalIndex = this.serve ? taskSeqGlobalIndex + 1 : -1;
		// The re-entry guard flag (a mut i32) of a module whose host import may suspend
		// (hostMaySuspend above): every export wrapper sets it on entry and clears it on
		// return, and a second entry -- a call arriving while the first is parked on the
		// suspended import -- traps at the boundary instead of interleaving over the bump
		// allocator and the shallow special bindings. Only when the module both can
		// suspend and exports something, so every other module is byte-identical.
		int lastModeGlobalIndex = this.serve ? serveInitGlobalIndex
				: this.asyncMode ? taskSeqGlobalIndex : ehMode ? ehDepthGlobalIndex : GLOBAL_FENV + globalCount;
		// --reentrant retires the guard (overlap is the point) and instead -- when any
		// special is dynamically bound -- carries the CURRENT task record in a global of
		// its own, swapped by the export wrappers and around the suspending host calls
		// (WasmDynVars). Mutually exclusive with the guard, so they share the slot after
		// lastModeGlobalIndex.
		int reentryGuardGlobalIndex = hostMaySuspend && !exportDecls.isEmpty() && !this.reentrant
				? lastModeGlobalIndex + 1 : -1;
		int reentrantTaskGlobalIndex = this.reentrant && !dynSlots.isEmpty() ? lastModeGlobalIndex + 1 : -1;
		// The cached symbol t (built lazily by _t_sym) and the raw-local sentinel (a
		// private TYPE_CELL instance no user value can be ref.eq to; "shadow ==
		// sentinel" marks an unboxed local's raw i64 as authoritative -- null cannot
		// mark it, because nil IS null), always the LAST globals so every mode-gated
		// index above keeps its value.
		int tSymGlobalIndex = Math.max(Math.max(reentryGuardGlobalIndex, reentrantTaskGlobalIndex), lastModeGlobalIndex)
				+ 1;
		int rawSentinelGlobalIndex = tSymGlobalIndex + 1;
		// The string output-stream buffer table (a TYPE_HASH_BUCKETS of $str_bytes,
		// created by the first _make_str_ostream and doubled from there): the GC root the
		// stream records reach their bytes through, since a linear-memory record cannot
		// hold a reference. Last of all, for the same reason the two above are.
		int ostreamTableGlobalIndex = rawSentinelGlobalIndex + 1;
		// The live recursion depth of _hash, the one piece of state its depth cap needs
		// (its signature is fixed at ((ref null eq)) -> i32, and a hash-table call site
		// cannot pass a budget in). Emitted only for a hash-table-using program, so
		// every other module is byte-identical to a build that never knew the cap; if
		// the source scan under-predicts, _hash simply keeps its uncapped recursion.
		// Appended AFTER the three above for the same reason they are last.
		int hashDepthGlobalIndex = programUsesAnyHashOp(program) ? ostreamTableGlobalIndex + 1 : -1;
		// The other piece of state the cap needs: _hash's remaining WORK budget, which
		// the depth cap does not bound (a key with shared substructure has exponentially
		// many root-to-leaf paths, so 64 levels of them is astronomical). Refilled by the
		// outermost entry rather than restored on the way out, so it is the whole
		// traversal's. Present exactly when the depth global is, and beside it.
		int hashGasGlobalIndex = hashDepthGlobalIndex >= 0 ? hashDepthGlobalIndex + 1 : -1;
		// The live recursion depth of _equalp_key, capped for the same reason and by the
		// same rule. Emitted only for a program that writes a :test 'equalp table --
		// which is also the whole gate on the fold -- so every other module keeps the
		// globals it had. Last, after the two hash counters it sits beside.
		int equalpDepthGlobalIndex = usesEqualpHashTables ? hashGasGlobalIndex + 1 : -1;
		// And _equalp_key's work budget, the counterpart of the hash's: the fold BUILDS a
		// structure, so an unbudgeted walk of a shared key does not merely take
		// exponential time, it allocates exponential space.
		int equalpGasGlobalIndex = equalpDepthGlobalIndex >= 0 ? equalpDepthGlobalIndex + 1 : -1;
		// The renderers' shared cycle guard (the wasm twin of RenderCycleGuard): the
		// current rendering path -- a TYPE_HASH_BUCKETS array lazily allocated by the
		// print branch -- and its depth. A value already on the path, or the frame past
		// RenderCycleGuard.MAX_RENDER_DEPTH, prints as "#" instead of exhausting the
		// wasm stack, and the cons arm's chain-cycle detection rides the same pair.
		// Unconditional since todo-585 -- the cons arm is in every module -- and
		// appended after the recursion counters for the same reason they are last.
		int lastCounterGlobalIndex = equalpGasGlobalIndex >= 0 ? equalpGasGlobalIndex
				: hashGasGlobalIndex >= 0 ? hashGasGlobalIndex : ostreamTableGlobalIndex;
		int renderPathGlobalIndex = lastCounterGlobalIndex + 1;
		int renderDepthGlobalIndex = lastCounterGlobalIndex + 2;
		// The quoted-datum constants (.kb/quoted-data.md): one (mut (ref null eq)) =
		// null per quoted aggregate the bodies compile, discovered DURING body
		// compilation, so they are appended after every fixed-index global above --
		// the render-guard pair included -- and nothing renumbers. A program with no
		// quoted aggregate allocates none and is byte-identical to a build that never
		// knew about them.
		QuoteGlobals quoteGlobals = new QuoteGlobals(renderDepthGlobalIndex + 1);

		// Create string table. The page-6 component base exists to keep the static data
		// clear of the OTHER writers of the shared memory (the adapter's page-5 scratch,
		// the serve cabi window); a --no-wasi reactor has no adapter and owns its whole
		// memory, so it keeps the Preview 1 base and stops reserving 384 KB of address
		// space per instance.
		int dataBase = this.component && !this.noWasi ? COMPONENT_DATA_BASE_OFFSET : DATA_BASE_OFFSET;
		StringTable stringTable = new StringTable(dataBase, this.usesEqualpHashTables);
		StringTable.StringEntry tSymEntry = stringTable.addBodyString("T");
		// The _type_err_int/_type_err_num message prefixes, interned HERE -- before any
		// body compiles -- because a string added during code emission would land after
		// the data segment's content is fixed. EH mode only: outside it both bodies are
		// a bare `unreachable` that cites no bytes.
		StringTable.StringEntry expIntEntry = ehMode
				? stringTable.addBodyString("\"" + am.ik.rontolisp.ClosRegistry.EXPECTED_INTEGER_MESSAGE_PREFIX + "\"")
				: null;
		StringTable.StringEntry expNumEntry = ehMode
				? stringTable.addBodyString("\"" + am.ik.rontolisp.ClosRegistry.EXPECTED_NUMBER_MESSAGE_PREFIX + "\"")
				: null;
		// The Schubfach float-printer tables (todo-431): ONE shakeable blob whose only
		// readers are the _schub_* helper bodies built later, so a program that never
		// prints a float carries no table bytes. Appended here, BEFORE any user body
		// compiles, so a user literal blob (a packed lookup table) stays the LAST
		// aligned append and its marginal per-element cost stays exact.
		int schubBlobBase = stringTable.appendShakeableBlobProbedOnBase(SchubfachTables.blob());
		// Bake the instance layouts into the data segment BEFORE Pass 2a: %obj-new
		// emits a record's address as an i32.const inside an ordinary function body, so
		// unlike the eval registry, the intern table and the case-fold tables -- all of
		// which are consumed by runtime helper bodies built after their append -- these
		// addresses must exist before any body is compiled. (Like them, the append must
		// also land before the data segment is snapshotted.)
		Map<String, Integer> layoutAddresses = this.usesInstances ? WasmInstanceLayouts.emit(closRegistry, stringTable,
				usedLayoutTags(program, closRegistry, usesEval || restartMode || usesRead)) : Map.of();

		// Assign funcIds and build function info map
		int[] nextFuncId = { 0 };
		Map<String, WasmFunctionInfo> functions = new HashMap<>();
		// The fusion-inlinable defuns (todo 194 stage 2): a UNIQUELY-defined,
		// fixed-arity defun whose single body expression is a closed integer-operation
		// tree over its parameters (WasmIntFusionCompiler.isInlinableDefun). A call to
		// one inside a fused expression tree substitutes the body, so ironclad-style
		// one-liner arithmetic wrappers (mod32+, rol32) stop chopping hot expression
		// trees into boxed call boundaries. Never under --dynamic (late binding must
		// keep observing redefinition), and never an async-defun: under asyncMode its
		// rewritten plain defun LOOKS inlinable (a one-form body is a closed int
		// tree), but calling one must build the TYPE_FUTURE its entry+resume state
		// machine answers -- splicing the raw body would hand a synchronous caller
		// the value where every other backend hands a future.
		Map<String, DefunDecl> inlinableDefuns = new HashMap<>();
		if (!this.dynamic) {
			Map<String, Integer> defunCounts = new HashMap<>();
			for (DefunDecl defun : defuns) {
				defunCounts.merge(defun.name(), 1, Integer::sum);
			}
			for (DefunDecl defun : defuns) {
				if (defunCounts.getOrDefault(defun.name(), 0) == 1 && !asyncDefunNames.contains(defun.name())
						&& WasmIntFusionCompiler.isInlinableDefun(defun)) {
					inlinableDefuns.put(defun.name(), defun);
				}
			}
		}
		for (int i = 0; i < defuns.size(); i++) {
			DefunDecl defun = defuns.get(i);
			int funcId = nextFuncId[0]++;
			int arity = defun.paramNames.size();
			if (arity > MAX_CALLABLE_ARITY) {
				throw new UnsupportedOperationException("Cannot compile function '" + defun.name
						+ "': the WASM backend " + "supports at most " + MAX_CALLABLE_ARITY + " parameters, got "
						+ arity + " (bundle the extra arguments into a list)");
			}
			functions.put(defun.name, new WasmFunctionInfo(defun.name, arity, defun.variadic, funcId,
					TYPE_CALLABLE_BASE + arity, userFuncBase() + i));
			if (Boolean.getBoolean("rontolisp.debug.functable")) {
				// Profiling aid: map a perf "wasm[0]::function[N]" index back to its
				// defun (the emitted module carries no name section).
				System.err.println("[functable] " + (userFuncBase() + i) + "\t" + defun.name);
			}
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		// Every funcId Pass 2 materializes as a first-class function value (see
		// Ctx.valueFuncIds). Filled while the bodies are emitted, read below to size the
		// dispatch ladders.
		Set<Integer> valueFuncIds = new HashSet<>();
		// Every literal spelling Pass 2 emits as a runtime value (see
		// Ctx.spelledLiterals). Filled while the bodies are emitted, read below by the
		// dispatch gate's name probes.
		Set<String> spelledLiterals = new HashSet<>();
		// The half of it the user's own text spells; see Ctx.userSpelledLiterals.
		Set<String> userSpelledLiterals = new HashSet<>();
		// The cl functions whose user defun an operator interception already warned
		// about: the warning is per NAME, not per call site (see
		// Ctx.warnedClRedefinitions).
		Set<String> warnedClRedefinitions = new HashSet<>();
		Set<Integer> indirectCallArities = new HashSet<>();
		// Set by the seams that dispatch a designator the compiler could not read; see
		// Ctx.runtimeDesignatorDispatch and the registry gate below.
		boolean[] runtimeDesignatorDispatch = new boolean[1];
		// The lambdas the injected runtime bodies build; see Ctx.injectedRuntimeLambdas.
		Set<Integer> injectedRuntimeLambdas = new HashSet<>();
		// The async waiter wake-up goes through the arity-1 dispatch (the resume
		// functions are arity-1 lambdas), and the wasi-stream read/close thunks
		// through the arity-0 one, so both bodies must be real.
		if (this.asyncMode) {
			indirectCallArities.add(0);
			indirectCallArities.add(1);
		}
		// The degenerate tier's stream runtime calls its read/close thunks through the
		// same arity-0 dispatch, and unlike %async-run (which registers the arity at its
		// own call site) a stream can be created by a program that never lowers one.
		if (this.usesP1Streams) {
			indirectCallArities.add(0);
		}

		// When eval is used, _eval applies any registered function via the dispatch
		// functions, so ensure a real dispatch body exists for every registered arity.
		// A variadic function is reachable from every dispatch arity >= its required
		// count, so all of those need real bodies.
		if (usesEval) {
			for (WasmFunctionInfo fi : functions.values()) {
				if (fi.paramCount() <= MAX_CALLABLE_ARITY) {
					if (fi.variadic()) {
						for (int a = fi.paramCount() - 1; a <= MAX_CALLABLE_ARITY; a++) {
							indirectCallArities.add(a);
						}
					}
					else {
						indirectCallArities.add(fi.paramCount());
					}
				}
			}
		}

		// Defun names defined more than once: a defstruct accessor's slot :type is only
		// trusted while the generated accessor is the ONE definition its name reaches
		// (WasmArrayCompiler.arrayKindOfExpr).
		Set<String> duplicatedDefunNames = new HashSet<>();
		Set<String> seenDefunNames = new HashSet<>();
		for (DefunDecl defun : defuns) {
			if (!seenDefunNames.add(defun.name)) {
				duplicatedDefunNames.add(defun.name);
			}
		}

		// Reusable builder template with shared constants and state
		Ctx.Builder ctxBuilder = Ctx.builder()
			.stringTable(stringTable)
			.ehMode(ehMode)
			.condMessagesObservable(condMessagesObservable)
			.blockExitTag(blockExitTag)
			.restartMode(restartMode)
			.signalClauseMatch(signalClauseMatch)
			.printCase(LispMacroExpander.usesPrintCase(program))
			.usesSeqString(usesSeqString)
			.ehDepthGlobalIndex(ehDepthGlobalIndex)
			.rawSentinelGlobalIndex(rawSentinelGlobalIndex)
			.functions(functions)
			.inlinableDefuns(inlinableDefuns)
			.duplicatedDefunNames(duplicatedDefunNames)
			.lambdaDecls(lambdaDecls)
			.indirectCallArities(indirectCallArities)
			.runtimeDesignatorDispatch(runtimeDesignatorDispatch)
			.injectedRuntimeLambdas(injectedRuntimeLambdas)
			.valueFuncIds(valueFuncIds)
			.spelledLiterals(spelledLiterals)
			.userSpelledLiterals(userSpelledLiterals)
			.nextFuncId(nextFuncId)
			.dynamic(this.dynamic)
			.optimize(this.optimize)
			// Every ctx.component dispatch asks "are the wasi:*-binding library splices
			// (http.lisp / wait.lisp / sockets.lisp / environment.lisp) here to resolve
			// this primitive?". A --no-wasi reactor deliberately has none of them -- its
			// primitives keep the Preview 1 --no-wasi contract -- so the expression
			// compilers see the Preview 1 answer; ctx.noWasi tells the reject sites the
			// reason so their messages name the actual conflict.
			.component(this.component && !this.noWasi)
			.noWasi(this.noWasi)
			.reactorComponent(this.component && this.noWasi)
			.hostRandom(this.hostRandom)
			.hostFetch(this.hostFetch)
			.serve(this.serve)
			.simd(this.simd)
			.userFuncBase(userFuncBase())
			.callArityCeiling(callArityCeiling())
			.extraDispatchFuncBase(extraDispatchFuncBase())
			.numDefuns(defuns.size())
			.userDefunNames(Set.copyOf(userDefinedNames))
			.warnedClRedefinitions(warnedClRedefinitions)
			.usesFmakunbound(programUsesSymbol(program, LispNames.FMAKUNBOUND))
			.usesProgv(programUsesSymbol(program, LispNames.PROGV))
			// Every context carries the flag (not just _start): the progv lowering
			// maintains the eval env mirror from any position, while the top-level-only
			// consumers keep their own ctx.topLevel guard.
			.usesEval(usesEval)
			.packageTable(packageResolver.runtimePackageTable())
			.packageUseTable(packageResolver.runtimePackageUseTable())
			.structAccessors(structAccessors)
			.closRegistry(closRegistry)
			.globals(globals)
			.nestedDefunNames(nestedDefunNames)
			.specialVars(specialVars)
			.globalIndices(globalIndices)
			.quoteGlobals(quoteGlobals)
			.futureTypeIndex(this.asyncMode ? asyncTypeBase() : -1)
			.frameTypeIndex(this.asyncMode ? asyncTypeBase() + 1 : -1)
			.wasiStreamTypeIndex(this.asyncMode ? asyncTypeBase() + 2 : -1)
			.p1StreamTypeIndex(this.usesP1Streams ? p1StreamTypeBase() : -1)
			.p1StreamFuncBase(this.usesP1Streams ? p1StreamFuncBase() : -1)
			.instanceTypeIndex(this.usesInstances ? instanceTypeBase() : -1)
			.usesSynonymStreams(programUsesSymbol(program, LispNames.MAKE_SYNONYM_STREAM))
			.usesEqualpHashTables(this.usesEqualpHashTables)
			.usesStreamValues(usesStreamValues)
			.layoutAddresses(layoutAddresses)
			.asyncFuncBase(this.asyncMode ? asyncFuncBase() : -1)
			.asyncDefunNames(Set.copyOf(asyncDefunNames))
			.p1Futures(p1Futures)
			.currentTaskGlobalIndex(currentTaskGlobalIndex)
			.serveInitGlobalIndex(serveInitGlobalIndex)
			.reentryGuardGlobalIndex(reentryGuardGlobalIndex)
			.reentrant(this.reentrant)
			.dynSlots(dynSlots)
			.reentrantTaskGlobalIndex(reentrantTaskGlobalIndex)
			.callbackExports(cbMode ? this.callbackExportsForTest : Set.of());

		// Passes 2a-2c emit function BODIES, and a body is the only consumer of a string
		// it interns (an i32.const the tree shaker can see). Everything interned outside
		// this window ends up somewhere the shaker cannot follow -- a word in the
		// _lookup registry blob, the instance-layout blob, a reader table, a runtime
		// helper built later -- so only what is interned here may be shaken out with its
		// body. See .kb/optimize-dead-code-elimination.md.
		stringTable.attributing(true);

		// Pass 2a: Compile each defun body (with env param at slot 0)
		List<byte[]> userFunctionBodies = new ArrayList<>();
		// Import wrapper bodies are deferred until after the lambda pass: a :string
		// result calls the _str_from_mem helper, whose index follows the lambdas.
		Map<String, Integer> importBodySlots = new HashMap<>();
		for (DefunDecl defun : defuns) {
			// See Ctx.injectedRuntimeBody: a wrapper catalog body is not the user's
			// designator use, so its dispatches do not arm the name registry.
			ctxBuilder.injectedRuntimeBody(injectedRuntimeDefuns.contains(defun.name));
			if (importWrappers.containsKey(defun.name) || componentImportWrappers.containsKey(defun.name)
					|| componentDropWrappers.containsKey(defun.name) || componentAsyncWrappers.containsKey(defun.name)
					|| componentCallStartWrappers.containsKey(defun.name)
					|| componentCallLiftWrappers.containsKey(defun.name)
					|| componentTaskReturnWrappers.containsKey(defun.name)) {
				importBodySlots.put(defun.name, userFunctionBodies.size());
				userFunctionBodies.add(null); // filled in below
				continue;
			}
			if (this.asyncMode && asyncDefunNames.contains(defun.name)) {
				// entry + resume state machine (WasmAsyncEmit): the resume registers
				// itself in the lambda table; the entry is the defun's own function.
				ByteArrayOutputStream protoBuf = new ByteArrayOutputStream();
				Ctx protoCtx = ctxBuilder.writer(new WasmWriter(protoBuf)).bodyStream(protoBuf).build();
				WasmAsyncEmit.Resume resume = WasmAsyncEmit.compileResume(protoCtx, defun.paramNames, defun.bodyExprs,
						List.of(), false, false);
				userFunctionBodies.add(WasmAsyncEmit.buildEntryBody(protoCtx, defun.paramNames.size(), false, resume));
				continue;
			}
			ByteArrayOutputStream funcBody = new ByteArrayOutputStream();
			WasmWriter funcWriter = new WasmWriter(funcBody);
			Ctx funcCtx = ctxBuilder.writer(funcWriter).bodyStream(funcBody).build();

			// Slot 0 = env (unused for defuns), params start at slot 1
			funcCtx.closureEnvSlot = 0;
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i + 1);
			}
			funcCtx.nextLocal = defun.paramNames.size() + 1;
			// (declare (type ...)) at the body head: array kinds for the parameters, so
			// a declared site emits the single-arm accessor
			// (.kb/declarations-type-checks.md).
			funcCtx.declaredArrays = WasmArrayCompiler.functionBodyDeclaredKinds(defun.bodyExprs, funcCtx);

			// Determine which params are captured by nested lambdas
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(defun.bodyExprs,
					new HashSet<>(defun.paramNames), functions.keySet());
			funcCtx.boxedVars = capturedVars;
			// Box captured params
			for (String paramName : defun.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = funcCtx.locals.get(paramName);
					if (slot != null) {
						WasmEmitHelper.emitBoxLocal(funcCtx, slot);
					}
				}
			}

			if (defun.bodyExprs.isEmpty()) {
				// (defun f ()) -- an empty body answers nil, per CL (dissect's no-op
				// interface stubs are this shape).
				WasmExprCompiler.compileExpr(LispNil.INSTANCE, funcCtx);
			}
			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				// Non-tail statements compile for effect: a docstring materializes
				// nothing (it used to _str_build a fresh string per call), and a
				// statement-position aset/setq skips its value box.
				if (i < defun.bodyExprs.size() - 1) {
					WasmExprCompiler.compileForEffect(defun.bodyExprs.get(i), funcCtx);
				}
				else {
					WasmExprCompiler.compileExpr(defun.bodyExprs.get(i), funcCtx);
				}
			}
			funcWriter.write(Instruction.END);

			// Rebuild with correct local declarations (extra locals beyond env+params)
			userFunctionBodies.add(buildLocalsAndPatch(funcCtx, defun.paramNames.size() + 1, funcBody));
		}

		// Every body from here on is the user's program again (the lambda pass included:
		// a lambda lifted out of a wrapper body is compiled here, and counting it as the
		// user's is the conservative direction).
		ctxBuilder.injectedRuntimeBody(false);

		// Pass 2b: Build _start function body
		ByteArrayOutputStream startBody = new ByteArrayOutputStream();
		WasmWriter startWriter = new WasmWriter(startBody);
		Ctx ctx = ctxBuilder.writer(startWriter).bodyStream(startBody).build();
		ctx.topLevel = true;

		// The heap pointer (HEAP_PTR_ADDR) is seeded by an active data segment at
		// instantiation (see writeDataSection below), not here: its value depends on
		// the final static-data size, which is unknown while this body is built.

		// Pre-grow the engine's GC heap before any user code runs: one large,
		// immediately-dropped byte-array allocation. wasmtime's copying collector
		// only grows the heap when a single allocation cannot fit in the space a
		// collection frees, so a program whose long-lived environment (symbols,
		// wrappers, library data) occupies a sizable share of the heap otherwise
		// collects -- copying the entire live set -- every few hundred KB of
		// allocation, and the cost of every hot loop scales with the amount of code
		// loaded. The heap never shrinks, so this single allocation permanently buys
		// headroom instead. A SERVE component pays this per INSTANCE, not per process
		// (the host retires an instance every N requests), so it pre-grows a smaller
		// heap. See .kb/wasm-gc-heap-pregrow.md.
		startWriter.write(Instruction.I32_CONST);
		startWriter.writeSignedLeb128(gcHeapPregrowBytes(userFunctionBodies));
		startWriter.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		startWriter.writeUnsignedLeb128(TYPE_STR_BYTES);
		startWriter.write(Instruction.DROP);

		// --reentrant with dynamically-bound specials: the LOAD PATH binds against a
		// task record too (a top-level let of a special), so seed one before any user
		// code runs -- binding sites then never need a null check (WasmDynVars).
		WasmDynVars.emitTaskBegin(ctx);

		// A program that redirects *standard-output* / *standard-input* (the variable has
		// a module global only then) seeds its global default with the designator t (the
		// process standard stream), matching the interpreter's permanent value;
		// *error-output*'s default is the handle 2 instead -- the process standard ERROR,
		// which the t designator does not name; here it is literally the WASI fd the
		// write helpers send stderr to. One table (StreamDesignators) for both.
		for (Map.Entry<String, LispVal> streamVar : StreamDesignators.standardStreamDefaults().entrySet()) {
			Integer streamGlobal = ctx.globalIndices.get(streamVar.getKey());
			if (streamGlobal != null) {
				emitStandardStreamDefault(startWriter, streamVar.getValue(), ctx);
				startWriter.write(Instruction.SET_GLOBAL);
				startWriter.writeUnsignedLeb128(streamGlobal);
			}
			// The eval runtime's global-environment mirror is the SECOND home of the same
			// value, and symbol-value/boundp/eval read only that one -- so it seeds from
			// the same table, or a variable the module global just took reads back as
			// unbound there and symbol-value traps (.kb/symbol-runtime-api.md). Gated on
			// the name appearing in the source, which keeps a program that never mentions
			// one byte-identical AND is the very scan the --component stderr narrowing
			// uses, so the two cannot disagree about whether handle 2 is reachable.
			if (!usesEval || !programUsesSymbol(program, streamVar.getKey())) {
				continue;
			}
			// GLOBAL_ENV = cons(cons(name, default), GLOBAL_ENV) -- the binding shape
			// _store prepends, so a later top-level assignment MUTATES this cell rather
			// than shadowing it. The name goes through the string table, so its offset is
			// the one _env_lookup compares a runtime-interned symbol against.
			WasmEmitHelper.compileStringLiteral(streamVar.getKey(), ctx);
			emitStandardStreamDefault(startWriter, streamVar.getValue(), ctx);
			startWriter.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			startWriter.writeUnsignedLeb128(TYPE_CONS);
			startWriter.write(Instruction.GET_GLOBAL);
			startWriter.writeUnsignedLeb128(GLOBAL_ENV);
			startWriter.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			startWriter.writeUnsignedLeb128(TYPE_CONS);
			startWriter.write(Instruction.SET_GLOBAL);
			startWriter.writeUnsignedLeb128(GLOBAL_ENV);
		}

		// EH mode: an uncaught $lisp-cond throw escaping the top level keeps today's
		// trap shape (host-visible exit class) but no longer keeps its silence -- the
		// landing pad catches the tag, writes the condition's report to fd 2 and only
		// then falls into the unreachable. The normal path returns from inside the
		// try_table, which sidesteps needing a result blocktype.
		if (uncaughtReportPad) {
			WasmUncaughtReportCompiler.emitPrologue(ctx);
		}
		int topLevelAwaits = 0;
		if (this.asyncMode) {
			for (LispVal expr : topLevelExprs) {
				topLevelAwaits += WasmAwaitAnalysis.countAwaits(expr);
			}
		}
		if (topLevelAwaits > 0) {
			// The top level is implicitly asynchronous: it compiles as an entry+resume
			// pair like any async-defun. An uncaught condition (incl. a rejected
			// top-level await's re-signal) escapes to the catch-all prologue -- the
			// same trap an uncaught error produces today.
			WasmAsyncEmit.Resume topResume = WasmAsyncEmit.compileResume(ctx, List.of(), topLevelExprs, List.of(), true,
					usesEval);
			WasmAsyncEmit.emitStartEntry(ctx, topResume);
		}
		else {
			// Not a plain loop over the forms: a program's top level is unbounded, and
			// wasmtime's cold compile needs memory superlinear in the size of ONE
			// function body, so concatenating every form into _start makes a large
			// program un-runnable rather than slow. See .kb/wasm-function-body-size.md.
			WasmToplevelEmit.emit(topLevelExprs, ctx);
		}
		if (this.component && !this.noWasi) {
			// _start returns i32 (0 = ok) so it can be lifted as wasi:cli/run `run`.
			// A --no-wasi reactor exports no run at all -- its top level is the core
			// START SECTION, whose function must be () -> () -- so it keeps the
			// Preview 1 void shape.
			startWriter.write(Instruction.I32_CONST);
			startWriter.writeSignedLeb128(0);
		}
		if (uncaughtReportPad) {
			WasmUncaughtReportCompiler.emitEpilogue(ctx);
		}
		startWriter.write(Instruction.END);

		byte[] finalStartBytes = buildLocalsAndPatch(ctx, 0, startBody);

		// Pass 2c: Compile lambda bodies iteratively
		List<byte[]> lambdaFunctionBodies = new ArrayList<>();
		int lambdaIdx = 0;
		while (lambdaIdx < lambdaDecls.size()) {
			LambdaInfo lambda = lambdaDecls.get(lambdaIdx);
			if (lambda.precompiled() != null) {
				// an async entry/resume half, compiled out of line by WasmAsyncEmit
				lambdaFunctionBodies.add(lambda.precompiled());
				lambdaIdx++;
				continue;
			}
			if (lambda.paramNames().size() > MAX_CALLABLE_ARITY) {
				throw new UnsupportedOperationException("Cannot compile lambda: the WASM backend supports at most "
						+ MAX_CALLABLE_ARITY + " parameters, got " + lambda.paramNames().size()
						+ " (bundle the extra arguments into a list)");
			}
			ByteArrayOutputStream lambdaBody = new ByteArrayOutputStream();
			WasmWriter lambdaWriter = new WasmWriter(lambdaBody);
			// A lambda an injected wrapper body built is injected runtime too (see
			// Ctx.injectedRuntimeLambdas); a nested one inherits it through this ctx.
			Ctx lambdaCtx = ctxBuilder.injectedRuntimeBody(injectedRuntimeLambdas.contains(lambda.funcId()))
				.writer(lambdaWriter)
				.bodyStream(lambdaBody)
				.build();

			// Slot 0 = env (closure environment)
			lambdaCtx.closureEnvSlot = 0;
			// Lambda params start at slot 1
			for (int i = 0; i < lambda.paramNames.size(); i++) {
				lambdaCtx.locals.put(lambda.paramNames.get(i), i + 1);
			}
			lambdaCtx.nextLocal = lambda.paramNames.size() + 1;
			// (declare (type ...)) at the body head (a local function's sits inside its
			// (block name ...) wrap): array kinds for the parameters.
			lambdaCtx.declaredArrays = WasmArrayCompiler.functionBodyDeclaredKinds(lambda.bodyExprs, lambdaCtx);

			// Set up captures mapping (free vars accessed from env cons list)
			Map<String, Integer> captures = new HashMap<>();
			for (int i = 0; i < lambda.freeVarNames.size(); i++) {
				captures.put(lambda.freeVarNames.get(i), i);
			}
			lambdaCtx.captures = captures;

			// Determine which locals are captured by further nested lambdas
			Set<String> lambdaLocalVars = new HashSet<>(lambda.paramNames);
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(lambda.bodyExprs, lambdaLocalVars,
					functions.keySet());
			lambdaCtx.boxedVars = capturedVars;
			// Box captured params of this lambda
			for (String paramName : lambda.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = lambdaCtx.locals.get(paramName);
					if (slot != null) {
						WasmEmitHelper.emitBoxLocal(lambdaCtx, slot);
					}
				}
			}

			for (int i = 0; i < lambda.bodyExprs.size(); i++) {
				// Non-tail statements compile for effect, like a defun body's.
				if (i < lambda.bodyExprs.size() - 1) {
					WasmExprCompiler.compileForEffect(lambda.bodyExprs.get(i), lambdaCtx);
				}
				else {
					WasmExprCompiler.compileExpr(lambda.bodyExprs.get(i), lambdaCtx);
				}
			}
			if (lambda.bodyExprs.isEmpty()) {
				// An empty-body (lambda ()) returns nil.
				lambdaWriter.write(Instruction.REF_NULL);
				lambdaWriter.writeHeapType(Type.EQ.code());
			}
			lambdaWriter.write(Instruction.END);

			lambdaFunctionBodies.add(buildLocalsAndPatch(lambdaCtx, lambda.paramNames.size() + 1, lambdaBody));
			lambdaIdx++;
		}
		stringTable.attributing(false);

		// Build dispatch function bodies
		int numDefuns = defuns.size();
		int numLambdas = lambdaDecls.size();

		// Build host-callable export wrappers (all modes; component mode restricts the
		// types to Tier-1 scalars and lifts each wrapper into a component-model export).
		// Each (rontolisp:wasm-export ...) directive becomes a thin wrapper function
		// appended after
		// all user defuns and lambdas, exported under its Lisp name with a host-friendly
		// numeric / memory signature. Indices: wrapper function indices follow the
		// lambdas;
		// wrapper type indices follow TYPE_HASH_BUCKETS (the last fixed type).
		List<ExportPlan> exportPlans = new ArrayList<>();
		List<byte[]> exportBodies = new ArrayList<>();
		// Memory-backed exports (:string/:s-expr) need two appended helper functions: the
		// host-facing bump allocator __ronto_alloc and the _str_from_mem string builder.
		// They precede the wrappers so the fixed FUNC_* constants are unaffected. In
		// component (non-serve) mode the host reaches __ronto_alloc through the appended
		// cabi_realloc instead of calling it directly (todo 92 Tier 2).
		boolean exportUsesMemory = exportDecls.stream().anyMatch(WasmExportCompiler::usesMemory);
		int exportHelperBase = userFuncBase() + numDefuns + numLambdas;
		// A :string import result is written into linear memory by the host and boxed
		// with the same _str_from_mem helper, so it forces the helper pair on too.
		boolean importUsesStrFromMem = importDecls.stream().anyMatch(WasmImportCompiler::usesStrFromMem);
		// A component-import wrapper stages arguments and lifts string results through
		// the same helper pair (__ronto_alloc for the return area, _str_from_mem for
		// host-written bytes), so any bound interface forces the helpers on.
		// The :bytes boundary type (raw (unsigned-byte 8) transfers, both directions):
		// its wrappers stage through __ronto_alloc and call the three _bytes_* helpers
		// below, all gated on the designator actually appearing so every other module
		// stays byte-identical.
		boolean bytesBoundary = exportDecls.stream().anyMatch(WasmExportCompiler::usesBytes)
				|| importDecls.stream().anyMatch(WasmImportCompiler::usesBytes);
		// A component byte-stream read (http.lisp's body-stream-read, sockets' recv)
		// lifts its chunk as a packed (unsigned-byte 8) vector -- the raw octets, no
		// decode -- through the same _bytes_from_mem helper, so a byte-stream reader
		// forces that one helper on (its two siblings stay gated on the :bytes
		// designator). Every module without one is byte-identical.
		boolean streamReadsBytes = componentAsyncWrappers.values()
			.stream()
			.anyMatch(async -> async.stream() && !async.handleElement());
		boolean bytesFromMem = bytesBoundary || streamReadsBytes;
		boolean memoryHelpers = exportUsesMemory || importUsesStrFromMem || bytesBoundary
				|| !componentImportWrappers.isEmpty() || !componentAsyncWrappers.isEmpty()
				|| !componentCallStartWrappers.isEmpty() || !componentTaskReturnWrappers.isEmpty();
		int allocFuncIndex = memoryHelpers ? exportHelperBase : -1;
		int strFromMemFuncIndex = memoryHelpers ? exportHelperBase + 1 : -1;
		// Host arena API: __ronto_alloc_mark / __ronto_alloc_reset, appended right after
		// the two memory helpers. Emitted only when the module EXPORTS its memory -- i.e.
		// never under --component, where the memory is imported and the host reaches the
		// heap through the canonical cabi_realloc / cabi_post_* pair instead (which does
		// the same intern-guarded pop for itself). So every component module stays
		// byte-identical.
		boolean hostArena = memoryHelpers && !this.component;
		int memoryHelperCount = memoryHelpers ? (hostArena ? 4 : 2) : 0;
		int allocMarkFuncIndex = hostArena ? exportHelperBase + 2 : -1;
		int allocResetFuncIndex = hostArena ? exportHelperBase + 3 : -1;
		// The :bytes marshalling helpers, appended right after the memory helpers:
		// _bytes_from_mem ((i32,i32)->(ref null eq), reuses TYPE_RAT_NEW), then
		// _bytes_copy and _bytes_fill (((ref null eq),i32,i32)->i32, one appended
		// signature at abiTypeBase shared by both).
		int bytesFromMemFuncIndex = bytesFromMem ? exportHelperBase + memoryHelperCount : -1;
		int bytesCopyFuncIndex = bytesBoundary ? exportHelperBase + memoryHelperCount + 1 : -1;
		int bytesFillFuncIndex = bytesBoundary ? exportHelperBase + memoryHelperCount + 2 : -1;
		int bytesHelperCount = bytesBoundary ? 3 : (bytesFromMem ? 1 : 0);
		// The --reentrant park-block allocator
		// (WasmExportRuntimeBuilder.buildParkAllocBody):
		// _park_alloc / _park_free (both exported -- the host's cross-call staging must
		// come from park blocks too) and _park_str_result (the export wrappers'
		// :string/:s-expr result staging, host-freed). Appended after the bytes helpers;
		// present exactly when a reentrant module has any memory-typed boundary, so
		// every other module is byte-identical.
		boolean parkHelpers = this.reentrant && memoryHelpers;
		int parkAllocFuncIndex = parkHelpers ? exportHelperBase + memoryHelperCount + bytesHelperCount : -1;
		int parkFreeFuncIndex = parkHelpers ? parkAllocFuncIndex + 1 : -1;
		int parkStrResultFuncIndex = parkHelpers ? parkAllocFuncIndex + 2 : -1;
		int parkHelperCount = parkHelpers ? 3 : 0;
		ctxBuilder.parkAllocFuncIndex(parkAllocFuncIndex)
			.parkFreeFuncIndex(parkFreeFuncIndex)
			.parkStrResultFuncIndex(parkStrResultFuncIndex);
		// __ronto_seed_random (i64) -> (): the host's escape hatch out of the --no-wasi
		// generator's constant start state (WasmIoRuntimeBuilder.buildSeedRandomBody).
		// Appended after the memory helpers, so it shifts only the COMPUTED wrapper/ABI
		// bases below -- every fixed FUNC_* constant is under userFuncBase(). Core-module
		// shape only: a reactor component would have to LIFT it to expose it at all.
		// Under --host-random there is no module-local generator state left to seed, so
		// the hook is not emitted at all rather than exported as a no-op that a host
		// could reasonably read as "seeding still matters here".
		boolean seedRandom = this.noWasi && !this.component && !this.hostRandom;
		int seedRandomFuncIndex = seedRandom ? exportHelperBase + memoryHelperCount + bytesHelperCount + parkHelperCount
				: -1;
		// __ronto_set_time (i64) -> (): the same move for the clock -- the host hands
		// over the one value it really knows and the module could never invent
		// (WasmIoRuntimeBuilder.buildSetTimeBody). Emitted on every --no-wasi CORE
		// module, independently of the seed hook (--host-random retires that one but
		// leaves the clock exactly as it was), and NOT on the reactor component, for the
		// same reason: its top level runs at instantiation, so there is no window in
		// which a host could set the time before the load-time reads.
		boolean setTime = this.noWasi && !this.component;
		int setTimeFuncIndex = setTime
				? exportHelperBase + memoryHelperCount + bytesHelperCount + parkHelperCount + (seedRandom ? 1 : 0) : -1;
		int helperFuncCount = memoryHelperCount + bytesHelperCount + parkHelperCount + (seedRandom ? 1 : 0)
				+ (setTime ? 1 : 0);
		// Unique host-import slots. Two Lisp wrappers can bind the SAME host function --
		// a serve+fetch program's two spliced halves may each call
		// wasi:http/types.fields-append, body-stream-read, ... in their own
		// %-package -- and the component model forbids a core module from importing one
		// (module, field) twice. So the wrappers stay distinct defuns but SHARE one core
		// import: this list has one entry per unique (module, field), and every wrapper
		// of
		// that function calls its slot's placeholder ordinal. Order = wasm-import decls,
		// then
		// component-import decls, then drops (the hostImports / import-type order below).
		// A
		// program with no overlap gets one slot per wrapper, exactly as before, so its
		// bytes
		// do not change.
		record ImportSlot(String module, String field, Type[] params, Type[] results) {
		}
		LinkedHashMap<String, Integer> importSlotIndex = new LinkedHashMap<>();
		List<ImportSlot> importSlots = new ArrayList<>();
		for (WasmImportCompiler.Decl decl : importWrappers.values()) {
			if (importSlotIndex.putIfAbsent(decl.module() + " " + decl.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(decl.module(), decl.field(), WasmImportCompiler.hostParamTypes(decl),
						WasmImportCompiler.hostResultTypes(decl)));
			}
		}
		for (WasmComponentImportCompiler.Decl decl : componentImportWrappers.values()) {
			if (importSlotIndex.putIfAbsent(decl.module() + " " + decl.field(), importSlots.size()) == null) {
				importSlots
					.add(new ImportSlot(decl.module(), decl.field(), WasmComponentImportCompiler.hostParamTypes(decl),
							WasmComponentImportCompiler.hostResultTypes(decl)));
			}
		}
		for (WasmComponentImportCompiler.Drop drop : componentDropWrappers.values()) {
			if (importSlotIndex.putIfAbsent(drop.module() + " " + drop.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(drop.module(), drop.field(),
						WasmComponentImportCompiler.dropParamTypes(), new Type[] {}));
			}
		}
		for (WasmComponentImportCompiler.Async async : componentAsyncWrappers.values()) {
			if (importSlotIndex.putIfAbsent(async.module() + " " + async.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(async.module(), async.field(),
						WasmComponentImportCompiler.asyncParamTypes(async),
						WasmComponentImportCompiler.asyncResultTypes(async)));
			}
		}
		// Async-lowered calls, then the waitable builtins each calling interface shares
		// (one set per interface, driven by every await wrapper of that interface), then
		// the task-return built-ins.
		for (WasmComponentImportCompiler.AsyncCall call : componentCallStartWrappers.values()) {
			if (importSlotIndex.putIfAbsent(call.module() + " " + call.field(), importSlots.size()) == null) {
				importSlots
					.add(new ImportSlot(call.module(), call.field(), WasmComponentImportCompiler.hostParamTypes(call),
							WasmComponentImportCompiler.hostResultTypes(call)));
			}
		}
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			if (imported.calls().isEmpty() && imported.asyncs().isEmpty()) {
				continue;
			}
			// async calls await through the waitable-set, and the async (non-blocking)
			// stream/future built-in wrappers park on it when BLOCKED -- so any
			// interface with either binds the trio.
			for (String field : WasmComponentImportCompiler.WAITABLE_FIELDS) {
				if (importSlotIndex.putIfAbsent(imported.ifaceId() + " " + field, importSlots.size()) == null) {
					importSlots.add(new ImportSlot(imported.ifaceId(), field,
							WasmComponentImportCompiler.waitableParamTypes(field),
							WasmComponentImportCompiler.waitableResultTypes(field)));
				}
			}
		}
		for (WasmComponentImportCompiler.TaskReturn tr : componentTaskReturnWrappers.values()) {
			if (importSlotIndex.putIfAbsent(tr.module() + " " + tr.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(tr.module(), tr.field(),
						WasmComponentImportCompiler.taskReturnParamTypes(tr), new Type[] {}));
			}
		}
		// The scheduler's waitable trio: any async-calling interface's builtins work
		// (they alias the same canonical built-ins as every other interface's), so the
		// first one's ordinals are wired into the async runtime (_subtask_future /
		// _sched_loop). Null when the program binds no async-calling interface --
		// nothing can produce a host-backed pending future there, and the two
		// scheduler members become unreachable stubs.
		WasmFutureRuntimeBuilder.Sched schedWiring = null;
		if (this.asyncMode) {
			for (WasmComponentImportCompiler.Import imported : componentImports) {
				if (imported.calls().isEmpty() && imported.asyncs().isEmpty()) {
					continue;
				}
				String schedIface = imported.ifaceId();
				schedWiring = new WasmFutureRuntimeBuilder.Sched(
						new WasmComponentImportCompiler.WaitOrdinals(
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_NEW)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_WAIT)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_DROP)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_JOIN)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_SUBTASK_DROP))),
						schedRegistryGlobalIndex, schedSetGlobalIndex, schedReadFreeGlobalIndex, allocFuncIndex,
						strFromMemFuncIndex, bytesFromMemFuncIndex);
				break;
			}
		}
		final WasmFutureRuntimeBuilder.Sched sched = schedWiring;
		// The callback-task built-ins ($sched): context slots, the doorbell stream and
		// the scheduler's own waitable-set new/join pair, imported exactly when the
		// module has a callback-lifted export (serve's handle; the component builder
		// canon-defines them into a synthesized "$sched" core instance).
		WasmFutureRuntimeBuilder.Cb cbWiring = null;
		if (cbMode) {
			for (String field : WasmComponentImportCompiler.SCHED_FIELDS) {
				if (importSlotIndex.putIfAbsent(WasmComponentImportCompiler.SCHED_MODULE + "\0" + field,
						importSlots.size()) == null) {
					importSlots.add(new ImportSlot(WasmComponentImportCompiler.SCHED_MODULE, field,
							WasmComponentImportCompiler.schedParamTypes(field),
							WasmComponentImportCompiler.schedResultTypes(field)));
				}
			}
			cbWiring = new WasmFutureRuntimeBuilder.Cb(
					schedOrdinal(importSlotIndex, WasmComponentImportCompiler.FIELD_CONTEXT_GET_0),
					schedOrdinal(importSlotIndex, WasmComponentImportCompiler.FIELD_CONTEXT_SET_0),
					schedOrdinal(importSlotIndex, WasmComponentImportCompiler.FIELD_DOORBELL_NEW),
					schedOrdinal(importSlotIndex, WasmComponentImportCompiler.FIELD_DOORBELL_READ),
					schedOrdinal(importSlotIndex, WasmComponentImportCompiler.FIELD_DOORBELL_WRITE),
					schedOrdinal(importSlotIndex, WasmComponentImportCompiler.FIELD_SCHED_SET_NEW),
					schedOrdinal(importSlotIndex, WasmComponentImportCompiler.FIELD_SCHED_JOIN), schedSetGlobalIndex,
					tasksGlobalIndex, taskSeqGlobalIndex);
		}
		final WasmFutureRuntimeBuilder.Cb cb = cbWiring;
		// Fill in the deferred import wrapper bodies now that the helper indices are
		// known (their positions in userFunctionBodies were reserved in Pass 2a). Each
		// wrapper calls its (module, field) slot's ordinal, so duplicate bindings
		// collapse
		// onto one import.
		{
			for (WasmImportCompiler.Decl decl : importWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(decl.module() + " " + decl.field()));
				byte[] body = WasmImportCompiler.buildWrapperBody(ctxBuilder, decl, ordinal, strFromMemFuncIndex,
						allocFuncIndex, bytesCopyFuncIndex, bytesFillFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(decl.name())), body);
			}
			for (WasmComponentImportCompiler.Decl decl : componentImportWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(decl.module() + " " + decl.field()));
				byte[] body = WasmComponentImportCompiler.buildWrapperBody(ctxBuilder, decl, ordinal, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(decl.lispName())), body);
			}
			for (WasmComponentImportCompiler.Drop drop : componentDropWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(drop.module() + " " + drop.field()));
				byte[] body = WasmComponentImportCompiler.buildDropBody(ctxBuilder, drop, ordinal);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(drop.lispName())), body);
			}
			for (WasmComponentImportCompiler.Async async : componentAsyncWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(async.module() + " " + async.field()));
				WasmComponentImportCompiler.WaitOrdinals asyncWaitOrdinals = new WasmComponentImportCompiler.WaitOrdinals(
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_NEW)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_WAIT)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_DROP)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_JOIN)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_SUBTASK_DROP)));
				byte[] body = WasmComponentImportCompiler.buildAsyncBody(ctxBuilder, async, ordinal, asyncWaitOrdinals,
						allocFuncIndex, strFromMemFuncIndex, bytesFromMemFuncIndex, sched);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(async.lispName())), body);
			}
			for (WasmComponentImportCompiler.AsyncCall call : componentCallStartWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(call.module() + " " + call.field()));
				byte[] body = WasmComponentImportCompiler.buildAsyncStartBody(ctxBuilder, call, ordinal, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(call.startName())), body);
			}
			for (WasmComponentImportCompiler.AsyncCall call : componentCallLiftWrappers.values()) {
				byte[] body = WasmComponentImportCompiler.buildAsyncLiftBody(ctxBuilder, call, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(call.liftName())), body);
			}
			for (WasmComponentImportCompiler.TaskReturn tr : componentTaskReturnWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(tr.module() + " " + tr.field()));
				byte[] body = WasmComponentImportCompiler.buildTaskReturnBody(ctxBuilder, tr, ordinal, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(tr.lispName())), body);
			}
		}
		if (!exportDecls.isEmpty()) {
			int wrapperFuncIndex = exportHelperBase + helperFuncCount;
			int wrapperTypeIndex = fixedTypeCount();
			for (WasmExportCompiler.Decl decl : exportDecls) {
				// Component-model exports (non-serve --component): scalars lift
				// synchronously with no canonical options; :string/:s-expr lift through
				// the canonical string ABI over the appended cabi_realloc / post-return
				// / retptr-shim helpers (todo 92 Tier 2).
				// :bytes is a core-module transfer (Preview 1 / --no-wasi): the component
				// boundary would have to lift it as a canonical-ABI list<u8>, which is
				// its
				// own change, so refuse it eagerly with the reason.
				if (this.component && WasmExportCompiler.usesBytes(decl)) {
					throw new UnsupportedOperationException("rontolisp:wasm-export '" + decl.name()
							+ "' declares :bytes, a core-module (Preview 1 / --no-wasi) boundary type; the"
							+ " --component path does not lift it yet");
				}
				if (this.component && !this.serve) {
					if (!WasmExportCompiler.COMPONENT_EXPORT_NAME.matcher(decl.exportName()).matches()) {
						throw new UnsupportedOperationException("rontolisp:wasm-export name '" + decl.exportName()
								+ "' is not a valid component-model export name (lower-kebab-case words, e.g."
								+ " \"sum-squared\"); rename it with :as \"kebab-name\"");
					}
					// The core module already exports "run" (the lifted wasi:cli/run
					// entry); a second core export under the same name would make the
					// module invalid. A --no-wasi reactor exports no run at all, so the
					// name is free there.
					if ("run".equals(decl.exportName()) && !this.noWasi) {
						throw new UnsupportedOperationException("rontolisp:wasm-export name 'run' collides with the"
								+ " component's wasi:cli/run entry; rename it with :as");
					}
				}
				WasmFunctionInfo target = functions.get(decl.name());
				if (target == null || !userDefinedNames.contains(decl.name())) {
					throw new UnsupportedOperationException(
							"rontolisp:wasm-export names an unknown function (must be a top-level defun): "
									+ decl.name());
				}
				if (decl.paramTypes().size() != target.paramCount()) {
					throw new UnsupportedOperationException("rontolisp:wasm-export arity mismatch for '" + decl.name()
							+ "': declared " + decl.paramTypes().size() + " params, but the function takes "
							+ target.paramCount());
				}
				ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
				WasmWriter bodyWriter = new WasmWriter(bodyStream);
				Ctx wrapperCtx = ctxBuilder.writer(bodyWriter).bodyStream(bodyStream).build();
				int paramSlots = WasmExportCompiler.paramSlotCount(decl);
				// The wrapper's typed scratch locals occupy the slots directly after the
				// parameters, so the boxing code can address them by a base it knows
				// before
				// emission; the (ref null eq) temps allocTemp hands out follow.
				List<Type> scratch = WasmExportCompiler.scratchTypes(decl);
				wrapperCtx.nextLocal = paramSlots + scratch.size();
				WasmExportCompiler.emitBody(wrapperCtx, decl, target.funcIndex(), strFromMemFuncIndex,
						bytesFromMemFuncIndex, bytesCopyFuncIndex);
				// Prepend the local declarations: the scratch locals (one run each, so
				// the
				// declaration order matches the slot order), then the (ref null eq)
				// temps.
				ByteArrayOutputStream finalBody = new ByteArrayOutputStream();
				WasmWriter finalWriter = new WasmWriter(finalBody);
				int extraLocals = wrapperCtx.nextLocal - paramSlots - scratch.size();
				finalWriter.writeUnsignedLeb128(scratch.size() + (extraLocals > 0 ? 1 : 0));
				for (Type scratchType : scratch) {
					finalWriter.writeUnsignedLeb128(1);
					finalWriter.write(scratchType);
				}
				if (extraLocals > 0) {
					finalWriter.writeUnsignedLeb128(extraLocals);
					finalWriter.writeRefType(true, Type.EQ.code());
				}
				finalWriter.write((Object) bodyStream.toByteArray());
				exportBodies.add(finalBody.toByteArray());
				exportPlans.add(new ExportPlan(decl, target.funcIndex(), wrapperTypeIndex++, wrapperFuncIndex++));
			}
		}

		// The host glue the CLI's --emit-js-glue writes (compiler/HostGlueEmitter): the
		// JavaScript half of this boundary, from the same declarations the module was
		// built from, so the two sides cannot drift -- the gl-imports.js precedent. Built
		// HERE because every fact it needs is settled by now and none of it is re-walked:
		// the import/export declarations, the helper exports the module turns out to
		// carry (memoryHelpers/hostArena/seedRandom/setTime), and -- for the promising
		// entries -- the same suspending-import question the obligation lines above
		// asked. --component instantiates through jco instead, and a WASI module's host
		// is wasmtime, so only a --no-wasi core module has glue to write at all.
		if (this.noWasi && !this.component) {
			List<HostGlueEmitter.Import> glueImports = new ArrayList<>();
			for (WasmImportCompiler.Decl decl : importWrappers.values()) {
				glueImports
					.add(new HostGlueEmitter.Import(decl.module(), decl.field(), decl.paramTypes(), decl.returnType()));
			}
			// --host-random routes random_get at an import no directive named, and one
			// the glue IMPLEMENTS rather than asks for: preview1 fixes what
			// random_get(buf, len) does, and filling linear memory is this side's job
			// anyway. Its declared shape is preview1's own, so a host that would rather
			// forward its WASI implementation still can.
			HostGlueEmitter.Import entropy = this.hostRandom ? new HostGlueEmitter.Import(HOST_RANDOM_MODULE,
					HOST_RANDOM_FIELD, List.of(BoundaryType.S32, BoundaryType.S32), BoundaryType.S32) : null;
			List<HostGlueEmitter.Export> glueExports = new ArrayList<>();
			for (ExportPlan plan : exportPlans) {
				WasmExportCompiler.Decl decl = plan.decl();
				boolean promising = hostMaySuspend && (anySuspendingImportEscapes
						|| suspendingImports.containsKey(decl.name())
						|| SuspendingImports.reaches(program, suspendingImports.keySet(), this.hostFetch, decl.name()));
				glueExports.add(
						new HostGlueEmitter.Export(decl.exportName(), decl.paramTypes(), decl.returnType(), promising));
			}
			// The two halves of this boundary the glue can WRITE rather than ask for --
			// both read off the imports that are here, never off a flag threaded down,
			// because what the module really imports is the whole of the question.
			//
			// A --host-fetch build whose reply body did NOT leave the envelope
			// (--host-boundary=envelope) fixes BOTH directions of env.fetch: the request
			// envelope and the whole reply, error arm included (compiler
			// /FetchResponseShape). With the body out of band the host also owns the
			// reader the octets come from and when the cursor moves, which is precisely
			// what a declaration cannot state -- so there the glue asks.
			// programUsesSymbol as well as the import: --host-fetch splices the
			// declaration ONLY for a program that fetches, so without that clause a
			// program declaring its OWN env.fetch -- any shape, any meaning -- would be
			// offered a host half implementing rontolisp's HTTP envelope for it.
			boolean derivedFetch = this.hostFetch && programUsesSymbol(program, LispNames.FETCH_QUALIFIED)
					&& imported(importWrappers, FetchResponseShape.HOST_IMPORT_FIELD);
			// And the reactor's own entry point takes the whole envelope -- a Request in,
			// a Response out -- on EITHER boundary: where the bodies left it, the reader
			// they come from is the platform Request the glue is already holding and the
			// Response it is already building, so that half stays derivable too.
			// Recognised by the synthesized BRIDGE defun rather than by the export name,
			// because "handle-request" is a name any program may choose and only this one
			// is the boundary the compile path built (compiler/ReactorEnvelope). BOTH
			// names, because the member alone is only half a fingerprint: a program may
			// spell a function %reactor-dispatch, and the synthesized bridge is always
			// exported under the transport's own export name as well.
			String envelopeExport = null;
			for (ExportPlan plan : exportPlans) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(plan.decl().name());
				String member = qn == null ? plan.decl().name() : qn.member();
				if (ReactorEnvelope.BRIDGE_FUNCTION.equalsIgnoreCase(member)
						&& ReactorEnvelope.EXPORT_NAME.equals(plan.decl().exportName())) {
					envelopeExport = plan.decl().exportName();
				}
			}
			this.hostGlue = new HostGlueEmitter.Surface(glueImports, entropy, glueExports, hostArena, seedRandom,
					setTime, "_initialize", derivedFetch, envelopeExport, this.reentrant);
		}

		// Canonical string ABI for --component :string/:s-expr exports (todo 92 Tier 2):
		// cabi_realloc (the host lowers string arguments through it; delegates to
		// __ronto_alloc and snapshots the CABI_MARK_* cells), one retptr shim per
		// :string/:s-expr-RETURNING export (MAX_FLAT_RESULTS = 1, so the lifted core
		// function returns a single i32 pointing at an 8-byte (ptr,len) record instead of
		// the wrapper's two values), and one cabi_post_* post-return per flat-result
		// signature (pops the bump heap to the snapshot once the host has copied the
		// results out, intern-count-guarded). All appended after every existing function
		// -- no index shifts -- and emitted ONLY under component (non-serve) with a
		// memory-typed export, so scalar-only components stay byte-identical to Tier 1.
		boolean componentStringAbi = this.component && !this.serve
				&& exportDecls.stream().anyMatch(WasmExportCompiler::usesMemory);
		List<ExportPlan> retptrShimPlans = new ArrayList<>();
		// kind -> the post-return's single flat-result core type (null = void result).
		LinkedHashMap<String, @org.jspecify.annotations.Nullable Type> componentPostKinds = new LinkedHashMap<>();
		if (componentStringAbi) {
			for (ExportPlan p : exportPlans) {
				if (WasmExportCompiler.returnsMemory(p.decl())) {
					retptrShimPlans.add(p);
				}
				if (WasmExportCompiler.usesMemory(p.decl())) {
					componentPostKinds.putIfAbsent(WasmExportCompiler.componentPostReturnKind(p.decl()),
							switch (WasmExportCompiler.componentPostReturnKind(p.decl())) {
								case "f64" -> Type.F64;
								case "void" -> null;
								default -> Type.I32;
							});
				}
			}
		}
		// The type entries appended after the fixed types, the export wrappers and the
		// import signatures: the component string-ABI block, or (mutually exclusive,
		// since
		// the host arena is non-component only) the two arena signatures.
		int abiTypeBase = fixedTypeCount() + exportPlans.size() + importSlots.size();
		int abiFuncBase = exportHelperBase + helperFuncCount + exportPlans.size();
		int cabiReallocFuncIndex = abiFuncBase;
		int cabiPostFuncBase = cabiReallocFuncIndex + 1;
		int retptrShimFuncBase = cabiPostFuncBase + componentPostKinds.size();

		// Host-ABI type entries for the imported functions follow the export wrapper
		// types; the WasmImportInjector post-pass prepends the matching import entries
		// and resolves the placeholder call indices after the module is assembled. One
		// entry
		// per unique (module, field) slot -- a wasm-import, a component-import call
		// (module =
		// the interface's canonical id, field = the canonical-ABI function name), or a
		// resource drop (field = "[resource-drop]<resource>") -- so a host function bound
		// by
		// two Lisp wrappers is imported once, which the component model requires.
		List<am.ik.wasm.WasmImportInjector.HostImport> hostImports = new ArrayList<>();
		int importTypeIndex = fixedTypeCount() + exportPlans.size();
		for (ImportSlot slot : importSlots) {
			hostImports
				.add(new am.ik.wasm.WasmImportInjector.HostImport(slot.module(), slot.field(), importTypeIndex++));
		}
		// --host-random: the entropy source joins the same ordinal space, LAST, so a
		// program that also declares rontolisp:wasm-import functions keeps their
		// ordinals (and its bytes) exactly as they were. It needs no appended type
		// entry -- preview1's random_get is (i32, i32) -> i32, which is TYPE_INTERN's
		// shape, and the fixed types are emitted by every module -- so abiTypeBase,
		// planned over importSlots above, is unaffected too.
		final int hostRandomOrdinal = this.hostRandom ? hostImports.size() : -1;
		if (this.hostRandom) {
			hostImports
				.add(new am.ik.wasm.WasmImportInjector.HostImport(HOST_RANDOM_MODULE, HOST_RANDOM_FIELD, TYPE_INTERN));
		}
		// %host-argv: the WASI command-line pair joins the same ordinal space LAST, for
		// the same reason --host-random does -- a program that also declares
		// rontolisp:wasm-import functions keeps their ordinals and its bytes. Both are
		// (i32, i32) -> i32, TYPE_INTERN's shape, so no type entry is appended either.
		// Preview 1 only: under --component %host-argv is the spliced environment.lisp
		// defun over wasi:cli/environment's get-arguments, and a --no-wasi reactor has
		// no command line to read at all (the compiler answers nil there).
		final int @Nullable [] argvOrdinals = usesHostArgv ? new int[] { hostImports.size(), hostImports.size() + 1 }
				: null;
		if (argvOrdinals != null) {
			hostImports
				.add(new am.ik.wasm.WasmImportInjector.HostImport(WASI_PREVIEW1_MODULE, "args_sizes_get", TYPE_INTERN));
			hostImports
				.add(new am.ik.wasm.WasmImportInjector.HostImport(WASI_PREVIEW1_MODULE, "args_get", TYPE_INTERN));
		}

		// Which funcIds the arity ladders (and the name registry below) must carry a case
		// for. Every emitted body has been compiled by now, so ctx.valueFuncIds holds
		// EXACTLY the funcIds this program turns into callable values -- including the
		// ones a lazily-expanded macro synthesized during Pass 2, which no pre-scan of
		// the source program can see. A defun that is only ever CALLED DIRECTLY needs no
		// case, and without one the tree shaker stops seeing the ladder's call edge to
		// it: that is what makes --optimize reach the library code an ASDF system
		// splices (.kb/optimize-dead-code-elimination.md; the ladders' edges used to
		// keep every spliced defun reachable, so --optimize dropped 22 of 2618 functions
		// on a cl-postgres component).
		//
		// The second source is the name registry: a SYMBOL designator resolves late
		// through _lookup, so any defun whose name the program interned must stay
		// callable. See buildNameRegistrySet -- and note it runs BEFORE the registry
		// blob is built, since building it interns every surviving name.
		boolean nameResolvable = anyNameResolvable(program, usesRead, usesLoad);
		boolean symbolBuilders = RuntimeNameProducers.anySymbolBuilder(program);
		// Whether a runtime SYMBOL designator can reach a call site. The source scan
		// above reads funcall/apply SPELLINGS only, so every other operator that calls
		// its function argument -- mapcar, sort, reduce, maphash, and the whole
		// sequence family, which reaches funcall through a Pass 2 macro expansion --
		// answers with what Pass 2 actually emitted instead
		// (Ctx.runtimeDesignatorDispatch). Both halves are needed: a dispatched
		// designator the compiler could not read is where a symbol ARRIVES, and a name
		// the program spells (or can build, or can read) is what _lookup could answer
		// with. Without the second half every (reduce #'+ l) would pull the registry in
		// -- its expansion binds the function to a temp, so the funcall dispatches --
		// for a module in which no symbol naming a defun exists.
		boolean designatorSymbolArrives = runtimeDesignatorDispatch[0]
				&& (nameResolvable || anyDefunNameSpelled(defuns, userSpelledLiterals, symbolBuilders));
		boolean registryLive = usesEval || usesRuntimeDesignator || usesApplyRuntime || designatorSymbolArrives;
		Set<Integer> dispatchableFuncIds = dispatchableFuncIds(defuns, valueFuncIds, spelledLiterals, registryLive,
				nameResolvable, symbolBuilders);
		List<byte[]> dispatchBodies = new ArrayList<>();
		for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
			if (indirectCallArities.contains(arity)) {
				dispatchBodies.add(WasmRuntimeBuilder.buildDispatchBody(arity, defuns, lambdaDecls, numDefuns,
						stringTable, usesEval, userFuncBase(), false, dispatchableFuncIds));
			}
			else {
				// Unused arity: unreachable body
				ByteArrayOutputStream db = new ByteArrayOutputStream();
				WasmWriter dw = new WasmWriter(db);
				dw.write(0); // 0 locals
				dw.write(Instruction.UNREACHABLE);
				dw.write(Instruction.END);
				dispatchBodies.add(db.toByteArray());
			}
		}
		// The spread dispatcher (FUNC_DISPATCH_SPREAD): only _apply calls it, so it is
		// built exactly when _apply is (the apply runtime, with or without the _eval
		// interpreter); otherwise its body is unreachable like an unused arity's.
		if (usesApplyRuntime) {
			dispatchBodies.add(WasmRuntimeBuilder.buildDispatchBody(0, defuns, lambdaDecls, numDefuns, stringTable,
					usesEval, userFuncBase(), true, dispatchableFuncIds));
		}
		else {
			ByteArrayOutputStream db = new ByteArrayOutputStream();
			WasmWriter dw = new WasmWriter(db);
			dw.write(0);
			dw.write(Instruction.UNREACHABLE);
			dw.write(Instruction.END);
			dispatchBodies.add(db.toByteArray());
		}
		// The EXTRA per-arity dispatchers (extraDispatchFuncBase()..), emitted with the
		// async block rather than here so the fixed indices above keep their values. The
		// slot has to exist as soon as the pre-scan sizes the tier -- Pass 2 writes the
		// index into every call -- but a site the scan saw and Pass 2 never emitted (one
		// inside a defmacro body, say) leaves it an unused arity, stubbed like the fixed
		// block's.
		List<byte[]> extraDispatchBodies = new ArrayList<>();
		for (int arity = MAX_CALLABLE_ARITY + 1; arity <= callArityCeiling(); arity++) {
			if (indirectCallArities.contains(arity)) {
				extraDispatchBodies.add(WasmRuntimeBuilder.buildDispatchBody(arity, defuns, lambdaDecls, numDefuns,
						stringTable, usesEval, userFuncBase(), false, dispatchableFuncIds));
			}
			else {
				ByteArrayOutputStream db = new ByteArrayOutputStream();
				WasmWriter dw = new WasmWriter(db);
				dw.write(0);
				dw.write(Instruction.UNREACHABLE);
				dw.write(Instruction.END);
				extraDispatchBodies.add(db.toByteArray());
			}
		}

		// Build helper function bodies
		byte[] printI32Body = WasmRuntimeBuilder.buildPrintI32Core(true);
		byte[] writeStrBody = WasmRuntimeBuilder.buildWriteStrBody();
		byte[] printValBody = WasmRuntimeBuilder.buildPrintValBody(stringTable, this.simd,
				this.asyncMode ? asyncTypeBase() : -1, this.usesP1Streams ? p1StreamTypeBase() : -1,
				this.usesInstances ? instanceTypeBase() : -1, renderPathGlobalIndex, renderDepthGlobalIndex);
		byte[] printI32NoNlBody = WasmRuntimeBuilder.buildPrintI32Core(false);
		byte[] schubUmulhiBody = WasmSchubfachRuntimeBuilder.buildUmulhiBody();
		byte[] schubGBody = WasmSchubfachRuntimeBuilder.buildGBody(FUNC_SCHUB_UMULHI, schubBlobBase);
		byte[] schubRopBody = WasmSchubfachRuntimeBuilder.buildRopBody(FUNC_SCHUB_UMULHI);
		byte[] f64DecBody = WasmSchubfachRuntimeBuilder.buildF64DecBody(FUNC_SCHUB_G, FUNC_SCHUB_ROP);
		byte[] f32DecBody = WasmSchubfachRuntimeBuilder.buildF32DecBody(FUNC_SCHUB_G, FUNC_SCHUB_UMULHI);
		byte[] decFmtBody = WasmSchubfachRuntimeBuilder.buildDecFmtBody();
		byte[] writeDecBody = WasmSchubfachRuntimeBuilder.buildWriteDecBody(FUNC_DEC_FMT, FUNC_WRITE_STR,
				OUT_BUF_OFFSET, PRINT_BUF_OFFSET);
		byte[] printF32NoNlBody = WasmRuntimeBuilder.buildPrintF32Core(stringTable);
		byte[] printF64Body = WasmRuntimeBuilder.buildPrintF64Core(true, stringTable);
		byte[] printF64NoNlBody = WasmRuntimeBuilder.buildPrintF64Core(false, stringTable);
		byte[] appendBody = WasmRuntimeBuilder.buildAppendBody();
		byte[] readLineBody = WasmRuntimeBuilder.buildReadLineBody(stringTable);
		byte[] princValBody = WasmRuntimeBuilder.buildPrincValBody(stringTable, this.simd,
				this.asyncMode ? asyncTypeBase() : -1, this.usesP1Streams ? p1StreamTypeBase() : -1,
				this.usesInstances ? instanceTypeBase() : -1, renderPathGlobalIndex, renderDepthGlobalIndex);

		// Build the eval runtime (interpreter + function-name registry). The registry
		// maps a symbol-name string offset to (funcId, arity). Because the string table
		// deduplicates, a quoted symbol such as 'car compiles to the same offset as the
		// registry entry for "car", so lookup is a plain i32 offset comparison.
		final byte[] lookupBody;
		final byte[] envLookupBody;
		final byte[] evalBody;
		final byte[] applyBody;
		final byte[] storeBody;
		// The name registry + real _lookup are needed by the eval runtime AND by the
		// dispatch functions: a funcall whose designator is a SYMBOL at run time
		// (cl-postgres passes 'list-row-reader through exec-query) resolves through
		// _lookup, matching the interpreter's late binding. Gated on the program
		// actually having such a call -- the registry embeds every defun NAME, so
		// emitting it unconditionally would make two programs with identical CODE
		// differ in bytes (the wit-import byte-identity pins).
		if (registryLive) {
			ByteArrayOutputStream registry = new ByteArrayOutputStream();
			int registryCount = 0;
			for (int i = 0; i < defuns.size(); i++) {
				DefunDecl defun = defuns.get(i);
				// Only the rows dispatchableFuncIds kept: a row whose name the program
				// never spells cannot be hit (see Ctx.spelledLiterals), and a row
				// whose funcId has no ladder case would resolve to a br_table default.
				// The two sets are computed together so they cannot drift apart.
				if (!dispatchableFuncIds.contains(i)) {
					continue;
				}
				int nameOffset = stringTable.addString(defun.name).offset();
				writeLittleEndian32(registry, nameOffset);
				writeLittleEndian32(registry, i); // funcId == defun index
				// arity; a variadic function is encoded as -physicalParamCount so the
				// eval call path evaluates every argument instead of exactly arity
				writeLittleEndian32(registry, defun.variadic ? -defun.paramNames.size() : defun.paramNames.size());
				registryCount++;
			}
			// Alias rows for INTERNAL names (todo-229): a runtime-interned symbol
			// carries the single-colon external spelling (the 2-arg intern/find-symbol
			// lowerings build it -- exportedness is registry knowledge the run time
			// does not have), so an unexported PKG::NAME defun also answers to
			// PKG:NAME. Collision-free (one package cannot house two distinct symbols
			// with one member name); appended after the base rows so a genuine key
			// always wins in the linear scan.
			//
			// The alias SPELLING has to be reachable for the row to be, and _lookup
			// matches interned OFFSETS: a name this compile already spells is interned
			// (then the row's addString is free), and one it does not can only be
			// assembled by a symbol BUILDER or read out of the input. With neither, the
			// row and its string are bytes nothing can ever address -- one per chipz
			// accessor, -849 B on the zlib size-report row.
			boolean aliasReachable = this.dynamic || nameResolvable || symbolBuilders;
			Set<String> defunNames = new HashSet<>();
			for (DefunDecl defun : defuns) {
				defunNames.add(defun.name);
			}
			for (int i = 0; i < defuns.size(); i++) {
				DefunDecl defun = defuns.get(i);
				if (!dispatchableFuncIds.contains(i)) {
					continue;
				}
				int q = defun.name.indexOf("::");
				if (q > 0) {
					String alias = defun.name.substring(0, q) + defun.name.substring(q + 1);
					if (!defunNames.contains(alias) && (aliasReachable || spelledLiterals.contains(alias))) {
						writeLittleEndian32(registry, stringTable.addString(alias).offset());
						writeLittleEndian32(registry, i);
						writeLittleEndian32(registry,
								defun.variadic ? -defun.paramNames.size() : defun.paramNames.size());
						registryCount++;
					}
				}
			}
			int registryBase = stringTable.appendBlob(registry.toByteArray());
			lookupBody = WasmEvalRuntimeBuilder.buildLookupBody(registryBase, registryCount);
		}
		else {
			lookupBody = WasmEvalRuntimeBuilder.buildLookupStub();
		}
		if (usesEval) {
			WasmEvalRuntimeBuilder.SpecialFormOffsets offsets = WasmEvalRuntimeBuilder.SpecialFormOffsets.builder()
				.add(stringTable, LispNames.QUOTE)
				.add(stringTable, LispNames.IF)
				.add(stringTable, LispNames.PROGN)
				.add(stringTable, LispNames.LET)
				.add(stringTable, LispNames.LAMBDA)
				.add(stringTable, LispNames.DEFUN)
				.add(stringTable, LispNames.COND)
				.add(stringTable, LispNames.AND)
				.add(stringTable, LispNames.OR)
				.add(stringTable, LispNames.WHEN)
				.add(stringTable, LispNames.UNLESS)
				.add(stringTable, LispNames.WHILE)
				.add(stringTable, LispNames.DOTIMES)
				.add(stringTable, LispNames.SETQ)
				.add(stringTable, LispNames.EVAL)
				.add(stringTable, LispNames.FUNCALL)
				.add(stringTable, LispNames.MAPCAR)
				.add(stringTable, LispNames.MAPC)
				.add(stringTable, LispNames.REDUCE)
				.add(stringTable, LispNames.LIST)
				.add(stringTable, LispNames.ADD)
				.add(stringTable, LispNames.SUB)
				.add(stringTable, LispNames.MUL)
				.add(stringTable, LispNames.DIV)
				.add(stringTable, "T")
				.add(stringTable, LispNames.FIRST)
				.add(stringTable, LispNames.REST)
				.add(stringTable, LispNames.SECOND)
				.add(stringTable, LispNames.THIRD)
				.add(stringTable, LispNames.FOURTH)
				.add(stringTable, LispNames.NTH)
				.add(stringTable, LispNames.SETF)
				.add(stringTable, LispNames.PUSH)
				.add(stringTable, LispNames.POP)
				.add(stringTable, LispNames.FUNCTION)
				.add(stringTable, LispNames.SYMBOL_FUNCTION)
				.build();
			envLookupBody = WasmEvalRuntimeBuilder.buildEnvLookupBody();
			evalBody = WasmEvalRuntimeBuilder.buildEvalBody(offsets);
			storeBody = WasmEvalRuntimeBuilder.buildStoreBody(offsets);
		}
		else {
			envLookupBody = WasmEvalRuntimeBuilder.buildEnvLookupStub();
			evalBody = WasmEvalRuntimeBuilder.buildEvalStub();
			storeBody = WasmEvalRuntimeBuilder.buildStoreStub();
		}
		// _apply exists whenever the apply runtime does; without the _eval interpreter
		// its body skips the $fenv and interpreted-closure arms (nothing can create
		// either without _eval/_store).
		applyBody = usesApplyRuntime ? WasmEvalRuntimeBuilder.buildApplyBody(usesEval)
				: WasmEvalRuntimeBuilder.buildApplyStub();

		// The symbol-API helper bodies (always emitted) embed the offset of the symbol
		// T; intern it before the runtime intern blob below is snapshotted so a runtime
		// (intern "T") resolves to the same offset literals use (uppercase-canonical).
		int symbolTOffset = stringTable.addBodyString("T").offset();
		// Build the reader runtime (read/load). Symbols parsed at runtime are interned
		// against a compile-time table of (offset,length) so they match the offsets the
		// eval runtime compares against. The intern built-in reuses _intern for the same
		// canonicalization (usesIntern), without the rest of the reader.
		final byte[] internBody;
		final byte[] readExprBody;
		final byte[] readListBody;
		final byte[] readBody;
		final byte[] loadBody;
		final byte[] rdCharlitBody;
		final byte[] rdRadixBody;
		final byte[] rdBitsBody;
		final byte[] rdArrayNBody;
		final byte[] rdPackedBody;
		final byte[] rdStructBody;
		final byte[] rdTokenBody;
		final byte[] rdLenBody;
		final byte[] rdLevelBody;
		final byte[] rdDimsBody;
		final byte[] rdFlatBody;
		final byte[] rdInferBody;
		final byte[] rdMemeqBody;
		// The intern table's base and row order also feed the tree shaker: each candidate
		// string range is paired with its own (offset, length) row so a dead entry's row
		// is cut with its bytes -- what lets an interning program offer per-entry
		// droppable ranges at all (see the stringRanges computation below).
		final List<StringTable.StringEntry> internRows;
		final int internBase;
		if (usesIntern) {
			// Intern NIL/quote/function before snapshotting so the runtime resolves them
			// to the same offsets the eval runtime uses (uppercase-canonical: the
			// embedded
			// reader upcases a runtime-read token, so nil reads as NIL, and t reads as
			// the
			// ordinary interned symbol T -- no t special-case in the reader). T itself is
			// interned above via symbolTOffset.
			int nilOffset = stringTable.addString("NIL").offset();
			int quoteOffset = stringTable.addString(LispNames.QUOTE).offset();
			int functionOffset = stringTable.addString(LispNames.FUNCTION).offset();
			List<StringTable.StringEntry> internEntries = new ArrayList<>(stringTable.entries());
			// Rows sorted by string offset so a run of dead entries drops as ONE cut of
			// rows next to one cut of bytes (cache order would interleave live and dead
			// rows and fragment the re-emitted segment); _intern scans for the unique
			// byte-equal row, so the order is free to choose.
			internEntries.sort(java.util.Comparator.comparingInt(StringTable.StringEntry::offset));
			internRows = internEntries;
			int internCount = internRows.size();
			internBase = stringTable.appendBlob(buildInternBlob(internRows));
			internBody = WasmReadRuntimeBuilder.buildInternBody(internBase, internCount, hostArena);
			if (usesRead) {
				WasmReadRuntimeBuilder.ReadCtx readCtx = WasmReadRuntimeBuilder.buildReadCtx(stringTable, nilOffset,
						quoteOffset, functionOffset, ehMode, this.simd, this.usesInstances ? instanceTypeBase() : -1,
						closRegistry, layoutAddresses);
				readExprBody = WasmReadRuntimeBuilder.buildReadExprBody(readCtx);
				readListBody = WasmReadRuntimeBuilder.buildReadListBody(readCtx);
				readBody = WasmReadRuntimeBuilder.buildReadBody(readCtx);
				loadBody = WasmReadRuntimeBuilder.buildLoadBody(readCtx);
				rdCharlitBody = WasmReadRuntimeBuilder.buildRdCharlitBody(readCtx);
				rdRadixBody = WasmReadRuntimeBuilder.buildRdRadixBody(readCtx);
				rdBitsBody = WasmReadRuntimeBuilder.buildRdBitsBody();
				rdArrayNBody = WasmReadRuntimeBuilder.buildRdArrayNBody(readCtx);
				rdPackedBody = WasmReadRuntimeBuilder.buildRdPackedBody(readCtx);
				rdStructBody = WasmReadRuntimeBuilder.buildRdStructBody(readCtx);
				rdTokenBody = WasmReadRuntimeBuilder.buildRdTokenBody();
				rdLenBody = WasmReadRuntimeBuilder.buildRdLenBody(readCtx);
				rdLevelBody = WasmReadRuntimeBuilder.buildRdLevelBody(readCtx);
				rdDimsBody = WasmReadRuntimeBuilder.buildRdDimsBody(readCtx);
				rdFlatBody = WasmReadRuntimeBuilder.buildRdFlatBody(readCtx);
				rdInferBody = WasmReadRuntimeBuilder.buildRdInferBody();
				rdMemeqBody = WasmReadRuntimeBuilder.buildRdMemeqBody();
			}
			else {
				readExprBody = WasmReadRuntimeBuilder.buildReadExprStub();
				readListBody = WasmReadRuntimeBuilder.buildReadListStub();
				readBody = WasmReadRuntimeBuilder.buildReadStub();
				loadBody = WasmReadRuntimeBuilder.buildLoadStub();
				rdCharlitBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdRadixBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdBitsBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdArrayNBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdPackedBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdStructBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdTokenBody = WasmReadRuntimeBuilder.buildRdI32Stub();
				rdLenBody = WasmReadRuntimeBuilder.buildRdI32Stub();
				rdLevelBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdDimsBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
				rdFlatBody = WasmReadRuntimeBuilder.buildRdI32Stub();
				rdInferBody = WasmReadRuntimeBuilder.buildRdI32Stub();
				rdMemeqBody = WasmReadRuntimeBuilder.buildRdI32Stub();
			}
		}
		else {
			internRows = List.of();
			internBase = -1;
			internBody = WasmReadRuntimeBuilder.buildInternStub();
			readExprBody = WasmReadRuntimeBuilder.buildReadExprStub();
			readListBody = WasmReadRuntimeBuilder.buildReadListStub();
			readBody = WasmReadRuntimeBuilder.buildReadStub();
			loadBody = WasmReadRuntimeBuilder.buildLoadStub();
			rdCharlitBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdRadixBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdBitsBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdArrayNBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdPackedBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdStructBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdTokenBody = WasmReadRuntimeBuilder.buildRdI32Stub();
			rdLenBody = WasmReadRuntimeBuilder.buildRdI32Stub();
			rdLevelBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdDimsBody = WasmReadRuntimeBuilder.buildRdEqrefStub();
			rdFlatBody = WasmReadRuntimeBuilder.buildRdI32Stub();
			rdInferBody = WasmReadRuntimeBuilder.buildRdI32Stub();
			rdMemeqBody = WasmReadRuntimeBuilder.buildRdI32Stub();
		}
		// Symbol-API helper bodies (FUNC_MAKE_SYMBOL .. FUNC_FMAKUNBOUND), built before
		// the
		// string table is serialized because they embed the offset of the symbol t.
		final byte[] makeSymbolBody = WasmSymbolApiRuntimeBuilder.buildMakeSymbol();
		final byte[] internSymBody = WasmSymbolApiRuntimeBuilder.buildInternSym();
		final byte[] boundpBody = WasmSymbolApiRuntimeBuilder.buildBoundp(symbolTOffset);
		final byte[] symbolValueBody = WasmSymbolApiRuntimeBuilder.buildSymbolValue(symbolTOffset);
		final byte[] fboundpBody = WasmSymbolApiRuntimeBuilder.buildFboundp(symbolTOffset);
		final byte[] fmakunboundBody = WasmSymbolApiRuntimeBuilder.buildFmakunbound();
		final byte[] setSymbolFunctionBody = WasmSymbolApiRuntimeBuilder.buildSetSymbolFunction();
		final byte[] fenvFunctionBody = WasmSymbolApiRuntimeBuilder.buildFenvFunction();

		// Case-fold tables. Two compressed (from, to, delta) triple tables, generated
		// from Character.toUpperCase(int) / toLowerCase(int) so char-upcase /
		// char-downcase
		// fold every Unicode letter (Latin-1 supplement, Greek, Cyrillic, ...) the same
		// way the interpreter and the JVM compile path already do. Placed right after the
		// string data at the same addresses appendBlob would have given them, but emitted
		// as their OWN active data segments (see writeDataSection below): their ~16 KB is
		// referenced only from the two helper bodies, so the tree shaker can drop the
		// segments together with the helpers when the program never case-folds. See
		// WasmCaseFoldRuntimeBuilder.
		byte[] stringData = stringTable.toByteArray();
		final byte[] upperFoldBytes = WasmCaseFoldRuntimeBuilder.upperTableBytes();
		final byte[] lowerFoldBytes = WasmCaseFoldRuntimeBuilder.lowerTableBytes();
		final byte[] alnumBytes = WasmCaseFoldRuntimeBuilder.alnumTableBytes();
		final int upperFoldOffset = (dataBase + stringData.length + 3) & ~3;
		// 12-byte triples and 8-byte pairs both keep the 4-alignment for the next table.
		final int lowerFoldOffset = upperFoldOffset + upperFoldBytes.length;
		final int alnumOffset = lowerFoldOffset + lowerFoldBytes.length;
		final byte[] charUpcaseBody = WasmCaseFoldRuntimeBuilder.buildBody(upperFoldOffset,
				WasmCaseFoldRuntimeBuilder.upperTableCount());
		final byte[] charDowncaseBody = WasmCaseFoldRuntimeBuilder.buildBody(lowerFoldOffset,
				WasmCaseFoldRuntimeBuilder.lowerTableCount());
		final byte[] charAlnumBody = WasmCaseFoldRuntimeBuilder.buildAlnumBody(alnumOffset,
				WasmCaseFoldRuntimeBuilder.alnumTableCount());

		// Final static-data layout. The static data ends after the case-fold tables, so
		// the runtime intern table's base and the bump-allocator heap base can be derived
		// from that end; both are seeded into fixed cells by active data segments below.
		// This keeps runtime interning and heap allocation above the static data no
		// matter how large the program is.
		int staticEnd = alnumOffset + alnumBytes.length;
		int rtInternBase = Math.max(RT_INTERN_MIN_BASE, (staticEnd + 15) & ~15);
		int heapBase = rtInternBase + RT_INTERN_REGION_SIZE;

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter mainWriter = new WasmWriter(out);
		mainWriter //
			.write("\0asm")
			.writeLittleEndian4(1)
			// Type section
			.writeTypeSection(types -> {
				// type 0: fd_write
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 1: _start -- in component mode it returns i32 (0 = ok) so it can
				// be lifted as the wasi:cli/run `run` entry. A --no-wasi reactor keeps
				// the Preview 1 () -> () shape: its top level runs from the core START
				// SECTION, whose function type must be empty.
				types.addFunc(new Type[] {}, this.component && !this.noWasi ? new Type[] { Type.I32 } : new Type[] {});
				// type 2: print_i32 / _print_i32_no_nl
				types.addFunc(new Type[] { Type.I32 }, new Type[] {});
				// types 3-7: struct types in rec group
				types.addRecGroup(rec -> {
					// type 3: cons struct
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 4: string struct {i32 id, i32 len, (ref null eq) data,
					// (mut i32) ci, (mut i32) cb}. id is the canonical integer identity
					// (interned offset or runtime id, compared with i32.eq); len is the
					// byte length; data is the $str_bytes GC array holding the
					// quote-framed bytes (nil until a builder fills it -- see
					// FUNC_STR_BUILD). ci/cb are the character-index cursor
					// (_str_char_byte_offset): character ci starts at byte cb, seeded
					// (0, 1) by every builder.
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
						fields.addField(true, w -> w.write(Type.I32));
						fields.addField(true, w -> w.write(Type.I32));
					});
					// type 5: cell struct {(mut ref null eq) value}
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 6: closure struct {i32 funcId, (ref null eq) env}
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 7: float struct {f64 value}
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.F64));
					});
				});
				// type 8: _write_str
				types.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] {});
				// type 9: _print_val
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
					w.write(0);
				});
				// type 10: _print_f64 / _print_f64_no_nl
				types.addFunc(new Type[] { Type.F64 }, new Type[] {});
				// types 11-21: callable types for arities 0-10 (MAX_CALLABLE_ARITY).
				// Each: (ref null eq)^(arity+1) -> (ref null eq)
				for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
					int paramCount = arity + 1; // env + args
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(paramCount);
						for (int i = 0; i < paramCount; i++) {
							w.writeRefType(true, Type.EQ.code());
						}
						w.write(1);
						w.writeRefType(true, Type.EQ.code());
					});
				}
				// type 19: _read_line () -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(0); // no params
					w.write(1); // 1 result
					w.writeRefType(true, Type.EQ.code());
				});
				// type 20: _lookup (i32) -> (i32)
				types.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 });
				// type 21: _env_lookup (i32, (ref null eq)) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.I32);
					w.writeRefType(true, Type.EQ.code());
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 22: _intern (i32, i32) -> (i32)
				types.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 23: path_open (i32,i32,i32,i32,i32,i64,i64,i32,i32) -> (i32)
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I64, Type.I64,
						Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 24: ratio struct {i32 numerator, i32 denominator}
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(false, w -> w.write(Type.I32));
					fields.addField(false, w -> w.write(Type.I32));
				}));
				// type 25: _rat_new (i32, i32) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 26: _rat_num/_rat_den ((ref null eq)) -> (i32)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
					w.write(1);
					w.write(Type.I32);
				});
				// type 27: _rat_cmp ((ref null eq), (ref null eq)) -> (i32)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.writeRefType(true, Type.EQ.code());
					w.writeRefType(true, Type.EQ.code());
					w.write(1);
					w.write(Type.I32);
				});
				// type 28: _read_line (i32 fd) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.write(Type.I32);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 29: _open ((ref null eq) path, i32 mode) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 30: clock_time_get (i32, i64, i32) -> i32
				types.addFunc(new Type[] { Type.I32, Type.I64, Type.I32 }, new Type[] { Type.I32 });
				// type 31 (TYPE_CHAR): character struct {i32 code}
				types.addRecGroup(
						rec -> rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I32))));
				// type 32 (TYPE_HASH_BUCKETS): array (mut (ref null eq)) -- hash-table
				// buckets. Encoded as a bare array comptype (sugar for sub final),
				// implicitly
				// a subtype of eq so it stores in cons/cell fields and supports ref.eq.
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.writeRefType(true, Type.EQ.code());
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 33 (TYPE_P1_FUTURE): degenerate-future struct {mut i32 kind, mut
				// (ref null eq) value} -- always created settled (kind 2) by %async-run.
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(true, w -> w.write(Type.I32));
					fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
				}));
				// type 34 (TYPE_STR_BYTES): array (mut i8) -- a string's byte storage.
				// A bare array comptype (implicitly sub final), so a subtype of eq: it
				// stores in TYPE_STRING's (ref null eq) data field and readers ref.cast
				// it.
				// The element is the packed storage type i8 (0x78; no Type constant).
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.write(0x78); // i8 packed storage type
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 35 (TYPE_STR_TO_MEM): _str_to_mem ((ref null eq), i32) -> i32
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.write(Type.I32);
				});
				// type 36 (TYPE_WRITE_STR_GC): _write_str_gc ((ref null eq), i32, i32,
				// i32) -> () -- the trailing i32 is the *print-escape* flag.
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(4);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(0);
				});
				// type 37 (TYPE_F64ARR): array (mut f64) -- packed float-array data
				// storage.
				// A bare array comptype (implicitly sub final), a subtype of eq.
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.write(Type.F64);
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 38 (TYPE_FARRAY): struct {(ref null eq) dims, (ref null eq) data}
				// --
				// a packed rank-n double-float array (dims = a TYPE_HASH_BUCKETS of i31
				// sizes, data = a TYPE_F64ARR). Both fields immutable (aset mutates the
				// f64 array contents, not the struct).
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
					fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
				}));
				// type 39 (TYPE_F32ARR): array (mut f32) -- packed single-float array
				// data
				// storage. A bare array comptype (implicitly sub final), a subtype of eq;
				// stored in TYPE_FARRAY's data field alongside TYPE_F64ARR (the width is
				// told apart by ref.test $f32arr).
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.write(Type.F32);
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 40 (TYPE_RD_DIMS): _rd_dims ((ref null eq), i32) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 41 (TYPE_RD_FLAT): _rd_flat ((ref null eq), i32, (ref null eq),
				// (ref null eq), i32) -> i32
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(5);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.writeRefType(true, Type.EQ.code());
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.write(Type.I32);
				});
				// type 42 (TYPE_RD_TOKEN): _rd_token () -> i32
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(0);
					w.write(1);
					w.write(Type.I32);
				});
				// type 43 (TYPE_RD_MEMEQ): _rd_memeq (i32, i32, i32) -> i32
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(3);
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(1);
					w.write(Type.I32);
				});
				// type 44 (TYPE_BIGNUM): struct {i64 value} -- a boxed exact integer
				// outside the i31 fixnum range. Own rec group; the only {i64} struct in
				// the module, so ref.test discriminates it from every other value.
				types.addRecGroup(
						rec -> rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I64))));
				// type 45 (TYPE_INT_NEW): _int_new (i64) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.write(Type.I64);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 46 (TYPE_INT_VAL): _int_val ((ref null eq)) -> i64
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
					w.write(1);
					w.write(Type.I64);
				});
				// type 47 (TYPE_PRINT_I64): _print_i64_no_nl (i64) -> ()
				types.addFunc(new Type[] { Type.I64 }, new Type[] {});
				// types 48+49 (TYPE_LIMBS + TYPE_BIGINT), ONE rec group: the limb array
				// (array (mut i32)) and the struct holding it as a typed (ref null
				// $limbs) field. The intra-group reference keeps the pair structurally
				// unique, so ref.test discriminates TYPE_BIGINT.
				types.addRecGroup(rec -> {
					rec.addSubFinalArray(w -> {
						w.write(Type.I32);
						w.write(am.ik.wasm.Mutability.VAR);
					});
					rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.writeRefType(true, TYPE_LIMBS)));
				});
				// type 50 (TYPE_BIG_SHIFT): ((ref null eq), i32) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 51 (TYPE_BIG_TRIPLE): ((ref null eq), (ref null eq), i32) ->
				// (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(3);
					w.writeRefType(true, Type.EQ.code());
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 52 (TYPE_BIG_GROW): ((ref null eq), i32, i32) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(3);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 53 (TYPE_BIG_TO_F64): ((ref null eq)) -> f64
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
					w.write(1);
					w.write(Type.F64);
				});
				// type 54 (TYPE_FX_VAL): _fx_val ((ref null eq)) -> (i64, i32)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
					w.write(2);
					w.write(Type.I64);
					w.write(Type.I32);
				});
				// type 55 (TYPE_FX_BIN): _fx_add/_fx_sub/_fx_mul/_fx_ash (i64, i64) ->
				// (i64, i32)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.I64);
					w.write(Type.I64);
					w.write(2);
					w.write(Type.I64);
					w.write(Type.I32);
				});
				// type 56 (TYPE_FX_DIV): _fx_mod/_fx_rem (i64, i64) -> i64
				types.addFunc(new Type[] { Type.I64, Type.I64 }, new Type[] { Type.I64 });
				// types 57-59 (TYPE_I8ARR/TYPE_I16ARR/TYPE_I32ARR): the packed
				// integer-vector storage -- bare array (mut i8|i16|i32) values (rank-1,
				// no struct wrapper). ONE rec group, so the i32 width stays structurally
				// distinct from TYPE_LIMBS under wasm-GC canonicalization.
				types.addRecGroup(rec -> {
					rec.addSubFinalArray(w -> {
						w.write(Type.I8_STORAGE);
						w.write(am.ik.wasm.Mutability.VAR);
					});
					rec.addSubFinalArray(w -> {
						w.write(Type.I16_STORAGE);
						w.write(am.ik.wasm.Mutability.VAR);
					});
					rec.addSubFinalArray(w -> {
						w.write(Type.I32);
						w.write(am.ik.wasm.Mutability.VAR);
					});
				});
				// type 60 (TYPE_IV_SET): _iv_set ((ref null eq), i32, i64) -> ()
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(3);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.write(Type.I64);
					w.write(0);
				});
				// type 61 (TYPE_T_SYM): _t_sym () -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(0);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 62 (TYPE_FD_READDIR): fd_readdir
				// (i32 fd, i32 buf, i32 buf_len, i64 cookie, i32 retptr) -> i32 errno
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(5);
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(Type.I64);
					w.write(Type.I32);
					w.write(1);
					w.write(Type.I32);
				});
				// type 63 (TYPE_UB_READ): _ub_read
				// ((ref null eq) shadow, i64 raw) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I64);
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// type 64 (TYPE_ARR_SET): _arr_set ((ref null eq) header, i32 flat,
				// (ref null eq) value) -> (ref null eq). _arr_get needs no type of its
				// own: TYPE_BIG_SHIFT is already ((ref null eq), i32) -> (ref null eq).
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(3);
					w.writeRefType(true, Type.EQ.code());
					w.write(Type.I32);
					w.writeRefType(true, Type.EQ.code());
					w.write(1);
					w.writeRefType(true, Type.EQ.code());
				});
				// The Schubfach float-printer runtime (todo-431), in constant order.
				// TYPE_SCHUB_UMULHI: (i64, i64) -> i64
				types.addFunc(new Type[] { Type.I64, Type.I64 }, new Type[] { Type.I64 });
				// TYPE_SCHUB_G: (i32) -> (i64 g1, i64 g0)
				types.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I64, Type.I64 });
				// TYPE_SCHUB_ROP: (i64, i64, i64) -> i64
				types.addFunc(new Type[] { Type.I64, Type.I64, Type.I64 }, new Type[] { Type.I64 });
				// TYPE_F64_DEC: (f64) -> (i64 digits, i32 k)
				types.addFunc(new Type[] { Type.F64 }, new Type[] { Type.I64, Type.I32 });
				// TYPE_F32_DEC: (f32) -> (i64 digits, i32 k)
				types.addFunc(new Type[] { Type.F32 }, new Type[] { Type.I64, Type.I32 });
				// TYPE_DEC_FMT: (i64 digits, i32 k, i32 scratch, i32 out) -> i32 len
				types.addFunc(new Type[] { Type.I64, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// TYPE_WRITE_DEC: (i64 digits, i32 k) -> ()
				types.addFunc(new Type[] { Type.I64, Type.I32 }, new Type[] {});
				// TYPE_PRINT_F32: (f32) -> ()
				types.addFunc(new Type[] { Type.F32 }, new Type[] {});
				// TYPE_F32BOX: struct {f32} -- the transient single-float print box
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(false, w -> w.write(Type.F32));
				}));
				if (this.simd) {
					// type 48 (TYPE_V128ARR): array (mut v128) -- the lane-group storage
					// of a packed float array. Declaring it at all requires the SIMD
					// proposal, which is why it is gated on --simd.
					types.add(w -> {
						w.write(Type.ARRAY_TYPE);
						w.write(Type.V128);
						w.write(am.ik.wasm.Mutability.VAR);
					});
					// type 45 (TYPE_VBLOCK): struct {i32 count, i32 kind, (ref null eq)
					// groups} -- what TYPE_FARRAY's data field holds under --simd. All
					// fields immutable (an aset mutates a v128 group, not the struct).
					types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
					}));
					// type 46 (TYPE_V_GET): _v_get ((ref null eq), i32) -> f64
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(2);
						w.writeRefType(true, Type.EQ.code());
						w.write(Type.I32);
						w.write(1);
						w.write(Type.F64);
					});
					// type 47 (TYPE_V_SET): _v_set ((ref null eq), i32, f64) -> f64
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(3);
						w.writeRefType(true, Type.EQ.code());
						w.write(Type.I32);
						w.write(Type.F64);
						w.write(1);
						w.write(Type.F64);
					});
				}
				if (this.asyncMode) {
					// TYPE_FUTURE + TYPE_ASYNC_FRAME + TYPE_WASI_STREAM in ONE rec
					// group: the first two are structurally identical, and same-group
					// membership is what keeps identical shapes distinct under wasm-GC's
					// structural type canonicalization (a separate group would
					// canonicalize them EQUAL and ref.test could no longer tell them
					// apart). TYPE_WASI_STREAM joins the group for the same reason (its
					// shape is one field away from colliding with a future refactor).
					types.addRecGroup(rec -> {
						// TYPE_FUTURE {mut i32 state, mut value, mut waiters, mut src}
						rec.addSubFinalStruct(fields -> {
							fields.addField(true, w -> w.write(Type.I32));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						});
						// TYPE_ASYNC_FRAME {mut i32 state, mut spill, mut future,
						// mut env, mut owner} -- owner is the task record of the
						// callback task the frame belongs to (null for a synchronous
						// boundary's frame), the routing key of _wake_list's
						// cross-task doorbell deferral.
						rec.addSubFinalStruct(fields -> {
							fields.addField(true, w -> w.write(Type.I32));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						});
						// TYPE_WASI_STREAM {mut i32 eof, mut readFn, mut closeFn}: a
						// first-class stream value over a wasi-backed byte stream. The
						// two closures (arity 0, dispatched through dispatch_0) carry
						// the handle and the close protocol; eof flips once and makes
						// close idempotent.
						rec.addSubFinalStruct(fields -> {
							fields.addField(true, w -> w.write(Type.I32));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						});
					});
					// The callback type (i32 event, i32 waitable, i32 code) -> i32
					// packed code: _sched_dispatch and the serve callback _async_cb.
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(3);
						w.write(Type.I32);
						w.write(Type.I32);
						w.write(Type.I32);
						w.write(1);
						w.write(Type.I32);
					});
				}
				if (this.usesP1Streams) {
					// TYPE_P1_STREAM {mut i32 eof, mut readFn, mut closeFn} in its OWN
					// rec group: the degenerate tier's stream value, the same shape the
					// async block gives TYPE_WASI_STREAM (which cannot be present at the
					// same time, so the two share this slot). Nothing else in the module
					// is a 3-field struct of {i32, eqref, eqref}, so a 1-member group is
					// enough to keep ref.test discriminating.
					types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.write(Type.I32));
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					}));
				}
				if (this.usesInstances) {
					// TYPE_INSTANCE {i32 layout address (MUT -- change-class swaps it),
					// (ref null eq) slots (mut)} in its OWN rec group, so it can never
					// canonicalize into TYPE_CLOSURE (member 3 of the group opened
					// above). The group's SECOND member is a never-instantiated empty
					// struct: it exists only to give the group a size TYPE_P1_FUTURE's
					// 1-member group cannot match, since the two structs are otherwise
					// structurally identical ({mut i32, mut eq}) and ref.test would stop
					// telling an instance from a future. The slots array is a
					// TYPE_HASH_BUCKETS.
					types.addRecGroup(rec -> {
						rec.addSubFinalStruct(fields -> {
							fields.addField(true, w -> w.write(Type.I32));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						});
						rec.addSubFinalStruct(fields -> {
						});
					});
				}
				// The EXTRA callable signatures (extraCallableTypeBase()..), one per
				// per-arity dispatcher past the fixed block: arity N is
				// (ref null eq)^(N+1) -> (ref null eq), the same shape as
				// TYPE_CALLABLE_BASE + N. Appended here rather than continuing that block
				// so no existing type index moves; the dispatchers are their only users
				// (a defun/lambda is still capped at MAX_CALLABLE_ARITY parameters).
				for (int arity = MAX_CALLABLE_ARITY + 1; arity <= callArityCeiling(); arity++) {
					int paramCount = arity + 1; // env + args
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(paramCount);
						for (int i = 0; i < paramCount; i++) {
							w.writeRefType(true, Type.EQ.code());
						}
						w.write(1);
						w.writeRefType(true, Type.EQ.code());
					});
				}
				// Export wrapper signatures (host-callable), appended after the last
				// fixed type (TYPE_F32ARR, or the --simd block's TYPE_V_SET). One per
				// (rontolisp:wasm-export ...) directive.
				for (ExportPlan p : exportPlans) {
					// a CALLBACK-lifted export (the serve-mode handle, or a
					// test-designated one): its core signature carries the trailing
					// i32 packed callback code
					types.addFunc(WasmExportCompiler.paramWasmTypes(p.decl()),
							WasmExportCompiler.isServeHandle(this.serve, p.decl())
									|| this.callbackExportsForTest.contains(p.decl().exportName())
											? new Type[] { Type.I32 } : WasmExportCompiler.resultWasmTypes(p.decl()));
				}
				// Host-ABI signatures of the imported functions, one per unique (module,
				// field) slot, in the same order as the hostImports entries above -- a
				// host
				// import's declared type index is positional. A slot is a wasm-import
				// signature, a component-import binding's canonical-ABI flat signature,
				// or a
				// resource drop's (func (param i32)); duplicate bindings of one host
				// function
				// share a slot, so its type is written once.
				for (ImportSlot slot : importSlots) {
					types.addFunc(slot.params(), slot.results());
				}
				// Component string-ABI signatures (todo 92 Tier 2), from abiTypeBase:
				// cabi_realloc, one cabi_post_* per flat-result signature, then one
				// retptr shim per :string/:s-expr-returning export (the wrapper's
				// params, a single i32 return pointer).
				if (componentStringAbi) {
					types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
					for (Type flat : componentPostKinds.values()) {
						types.addFunc(flat == null ? new Type[0] : new Type[] { flat }, new Type[0]);
					}
					for (ExportPlan p : retptrShimPlans) {
						types.addFunc(WasmExportCompiler.paramWasmTypes(p.decl()), new Type[] { Type.I32 });
					}
				}
				// Host arena signatures, from abiTypeBase (the component string-ABI block
				// above is mutually exclusive with them, so the base is shared):
				// __ronto_alloc_mark () -> i32, __ronto_alloc_reset (i32) -> ().
				if (hostArena) {
					types.addFunc(new Type[0], new Type[] { Type.I32 });
					types.addFunc(new Type[] { Type.I32 }, new Type[0]);
				}
				// The :bytes helper signature ((ref null eq),i32,i32) -> i32, shared by
				// _bytes_copy and _bytes_fill (_bytes_from_mem reuses the fixed
				// TYPE_RAT_NEW). Appended only when the designator appears, so every
				// other module's type section is untouched.
				if (bytesBoundary) {
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(3);
						w.writeRefType(true, Type.EQ.code());
						w.write(Type.I32);
						w.write(Type.I32);
						w.write(1);
						w.write(Type.I32);
					});
				}
				// The --reentrant _park_str_result signature ((ref null eq)) -> (i32,
				// i32). _park_alloc reuses the fixed TYPE_LOOKUP and _park_free the
				// arena-reset signature above (hostArena is always on when parkHelpers
				// is), so this is the only park type appended.
				if (parkHelpers) {
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(1);
						w.writeRefType(true, Type.EQ.code());
						w.write(2);
						w.write(Type.I32);
						w.write(Type.I32);
					});
				}
				// The host hooks' shared (i64) -> () signature, last of the appended
				// block (a --no-wasi core module never takes the component string-ABI
				// branch above). __ronto_seed_random and __ronto_set_time have the same
				// shape -- one i64 the host hands in -- so they share ONE type entry
				// however many of them this module emits.
				if (seedRandom || setTime) {
					types.addFunc(new Type[] { Type.I64 }, new Type[0]);
				}
			})
			// Import section
			.writeImportSection(imports -> {
				// No-wasi (reactor) mode: emit no wasi_snapshot_preview1 imports so the
				// module instantiates with no import object. Function indices 0-8 are
				// filled
				// with internal trap stubs in the function/code sections below, keeping
				// every
				// FUNC_* constant valid.
				// Serve mode keeps the imports: the preview1 bridge
				// (adapter-http-server-p1.wasm, instantiated before this core by
				// WasmServeComponentBuilder) provides them over the wasi:http proxy
				// world (random / clocks / stdout-stderr; graceful stubs for the rest).
				if (!this.noWasi) {
					imports.addImport("wasi_snapshot_preview1", "fd_write", ExternalKind.FUNCTION, TYPE_FD_WRITE)
						.addImport("wasi_snapshot_preview1", "fd_read", ExternalKind.FUNCTION, TYPE_FD_WRITE)
						.addImport("wasi_snapshot_preview1", "path_open", ExternalKind.FUNCTION, TYPE_PATH_OPEN)
						.addImport("wasi_snapshot_preview1", "fd_close", ExternalKind.FUNCTION, TYPE_LOOKUP)
						// random_get(buf, len) -> errno: (i32, i32) -> i32, same shape as
						// _intern. Preview 1 binds the real host function; component mode
						// binds
						// the adapter's wasi:random-backed implementation.
						.addImport("wasi_snapshot_preview1", "random_get", ExternalKind.FUNCTION, TYPE_INTERN)
						// clock_time_get / environ_sizes_get / environ_get for time and
						// getenv.
						// Component mode binds adapter implementations over wasi:clocks /
						// wasi:cli-environment; Preview 1 binds the real host functions.
						.addImport("wasi_snapshot_preview1", "clock_time_get", ExternalKind.FUNCTION,
								TYPE_CLOCK_TIME_GET)
						.addImport("wasi_snapshot_preview1", "environ_sizes_get", ExternalKind.FUNCTION, TYPE_INTERN)
						.addImport("wasi_snapshot_preview1", "environ_get", ExternalKind.FUNCTION, TYPE_INTERN)
						// fd_readdir backs %list-directory. Preview 1 binds the real host
						// function; component mode binds the adapter's implementation
						// over wasi:filesystem's read-directory.
						.addImport("wasi_snapshot_preview1", "fd_readdir", ExternalKind.FUNCTION, TYPE_FD_READDIR)
						// The preopen table, read by _path_dirfd so an ABSOLUTE runtime
						// path resolves against the preopen that actually covers it
						// instead of being handed to fd 3 whole. fd_prestat_get(fd, buf)
						// -> errno is (i32,i32)->i32 like _intern;
						// fd_prestat_dir_name(fd, ptr, len) -> errno is
						// (i32,i32,i32)->i32
						// like _rd_memeq -- no new type entry for either.
						.addImport("wasi_snapshot_preview1", "fd_prestat_get", ExternalKind.FUNCTION, TYPE_INTERN)
						.addImport("wasi_snapshot_preview1", "fd_prestat_dir_name", ExternalKind.FUNCTION,
								TYPE_RD_MEMEQ)
						// fd_filestat_get backs file-length. fd_filestat_get(fd, buf) ->
						// errno is (i32,i32)->i32 like _intern, so no new type entry.
						// Component mode binds the adapter's implementation over
						// wasi:filesystem's descriptor.stat.
						.addImport("wasi_snapshot_preview1", "fd_filestat_get", ExternalKind.FUNCTION, TYPE_INTERN);
				}
				if (this.component && !this.noWasi) {
					// Import the linear memory from the shared canonical-memory module so
					// the lowered WASI imports and this module share one memory. The min
					// page count matches the P1 own-memory declaration
					// ({@link #memoryMinPages}) so a program whose static data / intern
					// pool needs more than the mem module's default six pages tells its
					// component builder to grow that module too -- otherwise
					// instantiation traps on the first data-segment write.
					// A --no-wasi reactor is this module's ONLY writer of linear memory,
					// so it declares and exports its own (the memory section below),
					// exactly like the Preview 1 build.
					final int componentMemMinPages = memoryMinPages(heapBase);
					imports.add(w -> {
						w.write("mem".length(), "mem", "memory".length(), "memory");
						w.write(ExternalKind.MEMORY);
						w.write(0x00); // limits: min only
						w.writeUnsignedLeb128(componentMemMinPages);
					});
				}
			})
			// Function section
			.writeFunction(fnDef -> {
				// No-wasi mode: the twelve wasi imports were omitted, so define twelve
				// trap
				// stubs at function indices 0-11 with the SAME type indices the imports
				// used
				// (fd_write, fd_read, path_open, fd_close, random_get, clock_time_get,
				// environ_sizes_get, environ_get, fd_readdir, fd_prestat_get,
				// fd_prestat_dir_name, fd_filestat_get). This keeps every FUNC_*
				// constant
				// valid.
				if (this.noWasi) {
					fnDef.addFunction(TYPE_FD_WRITE) // 0: fd_write
						.addFunction(TYPE_FD_WRITE) // 1: fd_read
						.addFunction(TYPE_PATH_OPEN) // 2: path_open
						.addFunction(TYPE_LOOKUP) // 3: fd_close
						.addFunction(TYPE_INTERN) // 4: random_get
						.addFunction(TYPE_CLOCK_TIME_GET) // 5: clock_time_get
						.addFunction(TYPE_INTERN) // 6: environ_sizes_get
						.addFunction(TYPE_INTERN) // 7: environ_get
						.addFunction(TYPE_FD_READDIR) // 8: fd_readdir
						.addFunction(TYPE_INTERN) // 9: fd_prestat_get
						.addFunction(TYPE_RD_MEMEQ) // 10: fd_prestat_dir_name
						.addFunction(TYPE_INTERN); // 11: fd_filestat_get
				}
				fnDef.addFunction(TYPE_START) // _start
					.addFunction(TYPE_PRINT_I32) // print_i32
					.addFunction(TYPE_WRITE_STR) // _write_str
					.addFunction(TYPE_PRINT_VAL) // _print_val
					.addFunction(TYPE_PRINT_I32) // _print_i32_no_nl
					.addFunction(TYPE_PRINT_F64) // _print_f64
					.addFunction(TYPE_PRINT_F64) // _print_f64_no_nl
					.addFunction(TYPE_SCHUB_UMULHI) // _schub_umulhi
					.addFunction(TYPE_SCHUB_G) // _schub_g
					.addFunction(TYPE_SCHUB_ROP) // _schub_rop
					.addFunction(TYPE_F64_DEC) // _f64_dec
					.addFunction(TYPE_F32_DEC) // _f32_dec
					.addFunction(TYPE_DEC_FMT) // _dec_fmt
					.addFunction(TYPE_WRITE_DEC) // _write_dec
					.addFunction(TYPE_PRINT_F32) // _print_f32_no_nl
					.addFunction(TYPE_CALLABLE_BASE + 1) // _append
					.addFunction(TYPE_READ_LINE_FD) // _read_line
					.addFunction(TYPE_PRINT_VAL); // _princ_val
				fnDef.addFunction(TYPE_LOOKUP); // _lookup
				fnDef.addFunction(TYPE_ENV_LOOKUP); // _env_lookup
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _eval (form, env) -> value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _apply (fn, args) -> value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _store (place, value, env)
															// -> value
				fnDef.addFunction(TYPE_INTERN); // _intern (off, len) -> canonicalOff
				fnDef.addFunction(TYPE_READ_LINE); // _read_expr () -> value
				fnDef.addFunction(TYPE_READ_LINE); // _read_list () -> value
				fnDef.addFunction(TYPE_READ_LINE_FD); // _read (i32 fd) -> value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _load (path) -> value
				// Rational runtime
				fnDef.addFunction(TYPE_RAT_NEW); // _rat_new
				fnDef.addFunction(TYPE_RAT_GET); // _rat_num
				fnDef.addFunction(TYPE_RAT_GET); // _rat_den
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_add
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_sub
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_mul
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_div
				fnDef.addFunction(TYPE_RAT_CMP); // _rat_cmp
				fnDef.addFunction(TYPE_RAT_CMP); // _rat_cmp_bits
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_trunc
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_floor
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_ceil
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_round
				// String runtime
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _princ_to_str
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _prin1_to_str
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _string_concat
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _string_upcase
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _string_downcase
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _string_capitalize
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _subseq
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _string_eq
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _string_equal
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _string_trim
				// File-stream runtime
				fnDef.addFunction(TYPE_OPEN); // _open
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _close
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _write_line
				// Structural equality runtime
				fnDef.addFunction(TYPE_RAT_CMP); // _equal ((ref null eq), (ref null eq))
													// -> i32
				// getenv runtime
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _getenv ((ref null eq)) ->
															// (ref null eq)
				// Dispatch functions (arities 0-10) plus the spread one, which reuses the
				// arity-1 signature ((funcval, argList) -> value).
				for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + arity);
				}
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _invoke_v
				// plist runtime helper (FUNC_PLIST_GET)
				fnDef.addFunction(TYPE_OPEN); // _plist_get ((ref null eq), i32) ->
												// (ref null eq)
				// Hash-table runtime helpers
				fnDef.addFunction(TYPE_RAT_GET); // _hash ((ref null eq)) -> i32
				fnDef.addFunction(TYPE_PRINT_VAL); // _hash_resize ((ref null eq)) -> ()
				// Modulo / remainder runtime helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_rem
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_mod
				// gensym runtime helper
				fnDef.addFunction(TYPE_RAT_NEW); // _gensym (i32, i32) -> (ref null eq)
				// p1-future-await runtime helper
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _p1_future_await
				// binary stream runtime helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _read_byte (stream,
															// eof-error-p, eof-value) ->
															// (ref null eq)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _write_byte (byte, stream)
															// -> (ref null eq)
				// string-stream runtime helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _write_stream_str (str,
															// stream) -> (ref null eq)
				fnDef.addFunction(TYPE_READ_LINE); // _make_str_ostream () -> (ref null
													// eq)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _make_str_istream (str)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _str_stream_contents
															// (stream)
				// symbol runtime API helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _make_symbol (str)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _intern_sym (str)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _boundp (sym)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _symbol_value (sym)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _fboundp (sym)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _fmakunbound (sym)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _set_symbol_function (sym,
															// value)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _fenv_function (sym)
				// read-char runtime helper
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _read_char (stream,
															// eof-error-p, eof-value) ->
															// (ref null eq)
				// _str_build (off, len) -> (ref null eq): reuses the (i32,i32)->ref shape
				fnDef.addFunction(TYPE_RAT_NEW); // _str_build (FUNC_STR_BUILD)
				fnDef.addFunction(TYPE_RAT_NEW); // _str_fresh (FUNC_STR_FRESH)
				fnDef.addFunction(TYPE_STR_TO_MEM); // _str_to_mem (FUNC_STR_TO_MEM)
				fnDef.addFunction(TYPE_WRITE_STR_GC); // _write_str_gc (FUNC_WRITE_STR_GC)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _charvec_to_str
															// (FUNC_CHARVEC_TO_STR)
				fnDef.addFunction(TYPE_RAT_GET); // _charvec_p (FUNC_CHARVEC_P)
				fnDef.addFunction(TYPE_RAT_GET); // _str_char_count (FUNC_STR_CHAR_COUNT)
				fnDef.addFunction(TYPE_STR_TO_MEM); // _str_char_at (FUNC_STR_CHAR_AT)
				fnDef.addFunction(TYPE_STR_TO_MEM); // _str_char_byte_offset
													// (FUNC_STR_CHAR_BYTE_OFFSET)
				fnDef.addFunction(TYPE_LOOKUP); // _char_upcase (FUNC_CHAR_UPCASE)
				fnDef.addFunction(TYPE_LOOKUP); // _char_downcase (FUNC_CHAR_DOWNCASE)
				fnDef.addFunction(TYPE_LOOKUP); // _char_alnum_p (FUNC_CHAR_ALNUM_P)
				// the reader # dispatch helpers (FUNC_RD_CHARLIT .. FUNC_RD_MEMEQ)
				fnDef.addFunction(TYPE_READ_LINE); // _rd_charlit () -> value
				fnDef.addFunction(TYPE_READ_LINE_FD); // _rd_radix (radix) -> value
				fnDef.addFunction(TYPE_READ_LINE); // _rd_bits () -> value
				fnDef.addFunction(TYPE_READ_LINE_FD); // _rd_arrayn (rank) -> value
				fnDef.addFunction(TYPE_READ_LINE_FD); // _rd_packed (single) -> value
				fnDef.addFunction(TYPE_READ_LINE); // _rd_struct () -> value
				fnDef.addFunction(TYPE_RD_TOKEN); // _rd_token () -> i32
				fnDef.addFunction(TYPE_RAT_GET); // _rd_len (v) -> i32
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rd_level (v) -> value
				fnDef.addFunction(TYPE_RD_DIMS); // _rd_dims (rows, rank) -> dims
				fnDef.addFunction(TYPE_RD_FLAT); // _rd_flat (items, depth, dims, out,
													// idx) -> idx
				fnDef.addFunction(TYPE_RAT_GET); // _rd_infer_rank (rows) -> i32
				fnDef.addFunction(TYPE_RD_MEMEQ); // _rd_memeq (a, b, len) -> i32
				// bignum helpers (FUNC_INT_NEW, FUNC_INT_VAL, FUNC_PRINT_I64_NO_NL)
				fnDef.addFunction(TYPE_INT_NEW); // _int_new (i64) -> (ref null eq)
				fnDef.addFunction(TYPE_INT_VAL); // _int_val ((ref null eq)) -> i64
				fnDef.addFunction(TYPE_PRINT_I64); // _print_i64_no_nl (i64) -> ()
				// the limb bigint runtime (FUNC_LIMB_OF .. FUNC_BIG_HASH)
				fnDef.addFunction(TYPE_CALLABLE_BASE); // _limb_of
				fnDef.addFunction(TYPE_CALLABLE_BASE); // _limb_new
				fnDef.addFunction(TYPE_STR_TO_MEM); // _limb_get
				fnDef.addFunction(TYPE_BIG_TRIPLE); // _limb_addsub
				fnDef.addFunction(TYPE_CALLABLE_BASE); // _limb_neg
				fnDef.addFunction(TYPE_CALLABLE_BASE); // _limb_copy
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _limb_mul
				fnDef.addFunction(TYPE_RAT_CMP); // _limb_cmp
				fnDef.addFunction(TYPE_BIG_SHIFT); // _limb_shl
				fnDef.addFunction(TYPE_BIG_SHIFT); // _limb_shr
				fnDef.addFunction(TYPE_BIG_TRIPLE); // _limb_divrem_mag
				fnDef.addFunction(TYPE_STR_TO_MEM); // _limb_divmod_small
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_add
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_sub
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_mul
				fnDef.addFunction(TYPE_CALLABLE_BASE); // _big_neg
				fnDef.addFunction(TYPE_BIG_TRIPLE); // _big_divrem
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_mod
				fnDef.addFunction(TYPE_RAT_CMP); // _big_cmp
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_and
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_or
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_xor
				fnDef.addFunction(TYPE_CALLABLE_BASE); // _big_not
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_ash
				fnDef.addFunction(TYPE_CALLABLE_BASE); // _big_intlen
				fnDef.addFunction(TYPE_RAT_CMP); // _big_logbitp
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _big_gcd
				fnDef.addFunction(TYPE_BIG_GROW); // _big_grow
				fnDef.addFunction(TYPE_BIG_TO_F64); // _big_to_f64
				fnDef.addFunction(TYPE_PRINT_VAL); // _big_print
				fnDef.addFunction(TYPE_PRINT_VAL); // _big_print_mag
				fnDef.addFunction(TYPE_PRINT_I32); // _big_pad9
				fnDef.addFunction(TYPE_RAT_CMP); // _big_eq
				fnDef.addFunction(TYPE_RAT_GET); // _big_hash
				fnDef.addFunction(TYPE_BIG_TRIPLE); // _big_fdiv
				fnDef.addFunction(TYPE_FX_VAL); // _fx_val
				fnDef.addFunction(TYPE_FX_BIN); // _fx_add
				fnDef.addFunction(TYPE_FX_BIN); // _fx_sub
				fnDef.addFunction(TYPE_FX_BIN); // _fx_mul
				fnDef.addFunction(TYPE_FX_BIN); // _fx_ash
				fnDef.addFunction(TYPE_FX_DIV); // _fx_mod
				fnDef.addFunction(TYPE_FX_DIV); // _fx_rem
				fnDef.addFunction(TYPE_IV_SET); // _iv_set
				fnDef.addFunction(TYPE_T_SYM); // _t_sym
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _probe_file
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _fresh_line_stream
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _peek_char (stream,
															// eof-error-p, eof-value) ->
															// (ref null eq)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _list_directory
				fnDef.addFunction(TYPE_UB_READ); // _ub_read
				fnDef.addFunction(TYPE_CALLABLE_BASE + 3); // _fixed_dec (value, places,
															// int-digits, plus) -> string
				fnDef.addFunction(TYPE_BIG_TO_F64); // _as_f64 (value) -> f64
				fnDef.addFunction(TYPE_BIG_SHIFT); // _arr_get (header, flat) -> value
				fnDef.addFunction(TYPE_ARR_SET); // _arr_set (header, flat, value) ->
													// value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _seq_len (value) -> length
				fnDef.addFunction(TYPE_RAT_NEW); // _ostream_room (rec, n) -> buffer
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _iv_utf8_str (v) -> string
															// (FUNC_IV_UTF8_STR)
				fnDef.addFunction(TYPE_INTERN); // _path_dirfd (ptr, len) -> dirfd
												// (FUNC_PATH_DIRFD)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 3); // _read_packed (seq, stream,
															// start, end) -> value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 3); // _write_packed (seq, stream,
															// start, end) -> value
				fnDef.addFunction(TYPE_READ_LINE); // _argv () -> arg list (FUNC_ARGV)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _equalp_key (key) -> key
															// (FUNC_EQUALP_KEY)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _file_length (stream) ->
															// length (FUNC_FILE_LENGTH)
				fnDef.addFunction(TYPE_PRINT_VAL); // _type_err_int (FUNC_TYPE_ERR_INT)
				fnDef.addFunction(TYPE_PRINT_VAL); // _type_err_num (FUNC_TYPE_ERR_NUM)
				fnDef.addFunction(TYPE_STR_TO_MEM); // _str_char_ref (s, i) -> code point
													// (FUNC_STR_CHAR_REF)
				// vec: SIMD block (--simd only): the three element helpers + twelve
				// kernels
				if (this.simd) {
					for (int i = 0; i < WasmVecSimdRuntimeBuilder.FUNC_COUNT; i++) {
						fnDef.addFunction(WasmVecSimdRuntimeBuilder.typeIndexOf(i));
					}
					// linalg: SIMD block (--simd only): fifteen kernels, right after.
					for (int i = 0; i < WasmLinalgSimdRuntimeBuilder.FUNC_COUNT; i++) {
						fnDef.addFunction(WasmLinalgSimdRuntimeBuilder.typeIndexOf(i));
					}
				}
				// Async future runtime (asyncMode only), right after the --simd block.
				if (this.asyncMode) {
					for (int i = 0; i < WasmFutureRuntimeBuilder.FUNC_COUNT; i++) {
						fnDef.addFunction(WasmFutureRuntimeBuilder.typeIndexOf(i, asyncTypeBase() + 3));
					}
				}
				// The degenerate tier's stream runtime, in the slot the async block would
				// have taken (the two never coexist).
				if (this.usesP1Streams) {
					for (int i = 0; i < WasmP1StreamRuntimeBuilder.FUNC_COUNT; i++) {
						fnDef.addFunction(WasmP1StreamRuntimeBuilder.typeIndexOf(i));
					}
				}
				// The EXTRA per-arity dispatchers, right after the async block, over the
				// callable signatures appended at extraCallableTypeBase().
				for (int i = 0; i < this.extraCallArity; i++) {
					fnDef.addFunction(extraCallableTypeBase() + i);
				}
				// User defun functions
				for (DefunDecl defun : defuns) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + defun.paramNames.size());
				}
				// Lambda functions
				for (LambdaInfo lambda : lambdaDecls) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + lambda.paramNames.size());
				}
				// Memory-export helpers: __ronto_alloc ((i32)->i32, reuses TYPE_LOOKUP)
				// and
				// _str_from_mem ((i32,i32)->(ref null eq), reuses TYPE_RAT_NEW).
				if (memoryHelpers) {
					fnDef.addFunction(TYPE_LOOKUP);
					fnDef.addFunction(TYPE_RAT_NEW);
					// Host arena API: __ronto_alloc_mark / __ronto_alloc_reset, with the
					// two signatures appended at abiTypeBase.
					if (hostArena) {
						fnDef.addFunction(abiTypeBase);
						fnDef.addFunction(abiTypeBase + 1);
					}
				}
				// The :bytes marshalling helpers: _bytes_from_mem (TYPE_RAT_NEW), then
				// _bytes_copy / _bytes_fill sharing the one appended signature.
				if (bytesFromMem) {
					fnDef.addFunction(TYPE_RAT_NEW);
				}
				if (bytesBoundary) {
					fnDef.addFunction(abiTypeBase + (hostArena ? 2 : 0));
					fnDef.addFunction(abiTypeBase + (hostArena ? 2 : 0));
				}
				// The --reentrant park helpers: _park_alloc ((i32)->i32, TYPE_LOOKUP),
				// _park_free ((i32)->(), the arena-reset signature) and _park_str_result
				// (the one appended park signature).
				if (parkHelpers) {
					fnDef.addFunction(TYPE_LOOKUP);
					fnDef.addFunction(abiTypeBase + 1);
					fnDef.addFunction(abiTypeBase + (hostArena ? 2 : 0) + (bytesBoundary ? 1 : 0));
				}
				// The host hooks, which share the (i64)->() signature following them.
				if (seedRandom) {
					fnDef.addFunction(
							abiTypeBase + (hostArena ? 2 : 0) + (bytesBoundary ? 1 : 0) + (parkHelpers ? 1 : 0));
				}
				if (setTime) {
					fnDef.addFunction(
							abiTypeBase + (hostArena ? 2 : 0) + (bytesBoundary ? 1 : 0) + (parkHelpers ? 1 : 0));
				}
				// Export wrapper functions (host-callable), one per
				// (rontolisp:wasm-export ...).
				for (ExportPlan p : exportPlans) {
					fnDef.addFunction(p.typeIndex());
				}
				// Component string-ABI functions (todo 92 Tier 2): cabi_realloc, the
				// cabi_post_* post-returns, then the retptr shims, matching abiTypeBase.
				if (componentStringAbi) {
					int abiType = abiTypeBase;
					fnDef.addFunction(abiType++);
					for (int k = 0; k < componentPostKinds.size(); k++) {
						fnDef.addFunction(abiType++);
					}
					for (int k = 0; k < retptrShimPlans.size(); k++) {
						fnDef.addFunction(abiType++);
					}
				}
			})
			// Memory section -- in component mode the memory is imported (above), so this
			// section is empty; a --no-wasi reactor declares its own like Preview 1.
			// 4 pages so getenv can place the environ buffer in page 3
			// (the canonical realloc heap is page 1+ in component mode).
			.writeMemory(memories -> {
				if (!this.component || this.noWasi) {
					memories.addMemory(memoryMinPages(heapBase));
				}
			});
		// Tag section (EH mode only): the one $lisp-cond exception tag, whose payload
		// type reuses TYPE_PRINT_VAL (((ref null eq)) -> ()). Belongs between the memory
		// and global sections. Emitting it at all requires the host to enable the
		// exception-handling proposal, which is why it is gated.
		if (ehMode) {
			mainWriter.writeTagSection(tags -> {
				tags.addTag(TYPE_PRINT_VAL);
				// The block-exit tag (index 1), only when a cross-lambda return-from is
				// lowered or catch/throw is used -- so a handler-case-only program keeps
				// exactly one tag and stays byte-identical.
				if (blockExitTag) {
					tags.addTag(TYPE_PRINT_VAL);
				}
			});
		}
		mainWriter
			// Global section: the eval top-level variable environment (GLOBAL_ENV) and
			// the Lisp-2 function namespace (GLOBAL_FENV), both (mut (ref null eq)) =
			// null
			.writeGlobal(gs -> {
				gs.add(g -> {
					g.writeRefType(true, Type.EQ.code());
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.END);
				}).add(g -> {
					g.writeRefType(true, Type.EQ.code());
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.END);
				});
				// One (mut (ref null eq)) = null per top-level global variable (indices
				// 2+).
				for (int i = 0; i < globalCount; i++) {
					gs.add(g -> {
						g.writeRefType(true, Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
				}
				// EH mode: the handler-depth counter, a (mut i32) = 0 at
				// ehDepthGlobalIndex (after every user global).
				if (ehMode) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// asyncMode: the scheduler registry, a (mut (ref null eq)) = null, the
				// CURRENT task's waitable-set handle, a (mut i32) = 0 (created lazily),
				// the read-buffer free list, a (mut (ref null eq)) = null, then the
				// callback-task runtime's three: the CURRENT task record and the
				// task-record list (both (mut (ref null eq)) = null) and the task-id
				// counter, a (mut i32) = 0.
				if (this.asyncMode) {
					gs.add(g -> {
						g.writeRefType(true, Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
					gs.add(g -> {
						g.writeRefType(true, Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
					gs.add(g -> {
						g.writeRefType(true, Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
					gs.add(g -> {
						g.writeRefType(true, Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// Serve mode: the init-once flag at serveInitGlobalIndex, a (mut i32) =
				// 0. The handle wrapper runs the top level (_start) under it on the
				// first request, since a serve component's `run` is never lifted.
				if (this.serve) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// The re-entry guard flag at reentryGuardGlobalIndex, a (mut i32) = 0:
				// set by every export wrapper on entry, cleared on return. A second
				// entry while a call is parked on a suspending host import traps at
				// the boundary instead of interleaving over the bump allocator and
				// the shallow special bindings.
				if (reentryGuardGlobalIndex >= 0) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// --reentrant: the CURRENT task record at reentrantTaskGlobalIndex, a
				// (mut (ref null eq)) = null -- the per-call dynamic-variable store
				// (WasmDynVars), set by _start for the load path and by every export
				// wrapper on entry, saved/restored around the suspending host calls.
				if (reentrantTaskGlobalIndex >= 0) {
					gs.add(g -> {
						g.writeRefType(true, Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
				}
				// The cached symbol t at tSymGlobalIndex, a (mut (ref null eq)) = null,
				// built lazily by _t_sym; then the raw-local sentinel at
				// rawSentinelGlobalIndex, an immutable (ref null eq) initialized by the
				// constant expression (struct.new TYPE_CELL (ref.null eq)) -- a private
				// instance nothing else can hold. Always last, so every other index is
				// stable.
				gs.add(g -> {
					g.writeRefType(true, Type.EQ.code());
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.END);
				});
				gs.add(g -> {
					g.writeRefType(true, Type.EQ.code());
					g.write(am.ik.wasm.Mutability.CONST.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					g.writeUnsignedLeb128(TYPE_CELL);
					g.write(Instruction.END);
				});
				// The string output-stream buffer table at ostreamTableGlobalIndex, a
				// (mut (ref null eq)) = null: allocated by the first _make_str_ostream,
				// so a program that opens no string stream carries the null and nothing
				// else.
				gs.add(g -> {
					g.writeRefType(true, Type.EQ.code());
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.END);
				});
				// The _hash recursion depth at hashDepthGlobalIndex, a (mut i32) = 0:
				// incremented on entry and restored on exit, so the cap is by DEPTH and
				// two equal keys still fold identically.
				if (hashDepthGlobalIndex >= 0) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// The _hash work budget at hashGasGlobalIndex, a (mut i32) whose initial
				// value never matters: the outermost entry (depth == 0) refills it before
				// it is read, which is what keeps a key's hash a function of that key
				// alone.
				if (hashGasGlobalIndex >= 0) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// The _equalp_key recursion depth at equalpDepthGlobalIndex, the same
				// (mut i32) = 0 counter for the same depth cap, present only for a
				// program that folds a key.
				if (equalpDepthGlobalIndex >= 0) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// The _equalp_key work budget at equalpGasGlobalIndex, refilled by the
				// outermost entry exactly like the hash's, so its initial value never
				// matters.
				if (equalpGasGlobalIndex >= 0) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// The renderers' shared cycle guard at renderPathGlobalIndex (the
				// rendering path, a (mut (ref null eq)) = null lazily allocated by the
				// print branch) and renderDepthGlobalIndex (its depth, a (mut i32) =
				// 0). Unconditional: the guarded cons arm is in every module.
				gs.add(g -> {
					g.writeRefType(true, Type.EQ.code());
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.END);
				});
				gs.add(g -> {
					g.write(Type.I32);
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.I32_CONST);
					g.writeSignedLeb128(0);
					g.write(Instruction.END);
				});
				// One (mut (ref null eq)) = null per quoted aggregate datum
				// (.kb/quoted-data.md), filled lazily by its quote site's first
				// evaluation (WasmQuoteCompiler.compile). Discovered during body
				// compilation, hence appended after every fixed-index global.
				for (int i = 0; i < quoteGlobals.count(); i++) {
					gs.add(g -> {
						g.writeRefType(true, Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
				}
			})
			// Export section -- component mode exports `run` (the i32-returning _start)
			// for
			// the lifted wasi:cli/run entry; the memory is imported, not exported
			.writeExport(exports -> {
				if (this.component && this.noWasi) {
					// A --no-wasi REACTOR component: no `run` (the top level is the core
					// start section, run by the engine at instantiation, after the data
					// segments install) and no `_initialize` (nothing would call it). The
					// component wrap aliases `memory` (the canonical string options name
					// it) and lifts each wasm-export wrapper -- a :string/:s-expr-
					// returning one through its retptr shim -- plus the canonical string
					// ABI helpers, exactly like the base component below.
					exports.addExport("memory", ExternalKind.MEMORY, 0);
					for (ExportPlan p : exportPlans) {
						int shim = retptrShimPlans.indexOf(p);
						exports.addExport(p.decl().exportName(), ExternalKind.FUNCTION,
								shim >= 0 ? retptrShimFuncBase + shim : p.funcIndex());
					}
					if (componentStringAbi) {
						exports.addExport(WasmExportCompiler.CABI_REALLOC, ExternalKind.FUNCTION, cabiReallocFuncIndex);
						int k = 0;
						for (String kind : componentPostKinds.keySet()) {
							exports.addExport(WasmExportCompiler.cabiPostExportName(kind), ExternalKind.FUNCTION,
									cabiPostFuncBase + k++);
						}
					}
				}
				else if (this.component) {
					exports.addExport("run", ExternalKind.FUNCTION, FUNC_START);
					// The REAL callback of a callback-lifted export (serve's handle):
					// the component builder aliases it as the `callback` canonical
					// option, and the host invokes it with each event of the waiting
					// task's waitable-set.
					if (cbMode) {
						exports.addExport("async_cb", ExternalKind.FUNCTION,
								asyncFuncBase() + WasmFutureRuntimeBuilder.OFF_ASYNC_CB);
					}
					// Serve mode: the serve adapter calls the core's %http-dispatch (the
					// wasm-export wrapper) per request and __ronto_alloc for its scratch,
					// so
					// export them alongside `run` (which the adapter runs once as init).
					// The
					// memory stays imported (from the shared mem module).
					if (this.serve) {
						if (memoryHelpers) {
							exports.addExport("__ronto_alloc", ExternalKind.FUNCTION, allocFuncIndex);
						}
					}
					// Core-export each (rontolisp:wasm-export ...) wrapper: in serve mode
					// the serve adapter calls %http-dispatch through it; otherwise the
					// component wrapper (WasmComponentBuilder) aliases it and lifts it
					// into a host-callable component-model export. A :string/:s-expr-
					// returning export is exported as its retptr shim (the two-value
					// wrapper stays reachable through the shim's call but is not itself
					// an export).
					for (ExportPlan p : exportPlans) {
						int shim = retptrShimPlans.indexOf(p);
						exports.addExport(p.decl().exportName(), ExternalKind.FUNCTION,
								shim >= 0 ? retptrShimFuncBase + shim : p.funcIndex());
					}
					// The canonical string ABI helpers the component wrap aliases.
					if (componentStringAbi) {
						exports.addExport(WasmExportCompiler.CABI_REALLOC, ExternalKind.FUNCTION, cabiReallocFuncIndex);
						int k = 0;
						for (String kind : componentPostKinds.keySet()) {
							exports.addExport(WasmExportCompiler.cabiPostExportName(kind), ExternalKind.FUNCTION,
									cabiPostFuncBase + k++);
						}
					}
				}
				else {
					// A no-wasi module is a reactor/library, not a WASI command, so name
					// its
					// top-level init entry `_initialize` (the reactor ABI convention: a
					// host
					// runs it once after instantiation) rather than `_start` (the command
					// entrypoint a no-WASI host never calls). Either way it runs the
					// program's
					// top-level initializers (defvar/defparameter/setq globals an export
					// reads)
					// and seeds the heap pointer, and stays a tree-shaker root.
					String initExport = this.noWasi ? "_initialize" : "_start";
					exports.addExport("memory", ExternalKind.MEMORY, 0)
						.addExport(initExport, ExternalKind.FUNCTION, FUNC_START);
					// The host-facing bump allocator, when any memory-typed export is
					// present (a host calls it to reserve a scratch buffer for
					// string/sexpr inputs) or an import returns a :string the host must
					// write into linear memory.
					if (memoryHelpers) {
						exports.addExport("__ronto_alloc", ExternalKind.FUNCTION, allocFuncIndex);
						// The host arena API: snapshot the bump-heap top before the host
						// allocates its input buffer, pop back to it after the call. The
						// engine reclaims the Lisp side; this reclaims the linear-memory
						// buffer at the boundary, which it cannot see.
						exports.addExport("__ronto_alloc_mark", ExternalKind.FUNCTION, allocMarkFuncIndex);
						exports.addExport("__ronto_alloc_reset", ExternalKind.FUNCTION, allocResetFuncIndex);
					}
					// --reentrant: the park-block allocator, for host staging that must
					// stay valid while a call is parked (a receive buffer handed into an
					// export, a :string import result) -- the arena bracket cannot cover
					// those, its pops being what overlapped calls interleave.
					if (parkHelpers) {
						exports.addExport("__ronto_park_alloc", ExternalKind.FUNCTION, parkAllocFuncIndex);
						exports.addExport("__ronto_park_free", ExternalKind.FUNCTION, parkFreeFuncIndex);
					}
					// The host's seed hook: a --no-wasi module's `random` runs on its
					// own generator, whose start state is a constant unless a host
					// replaces it here. An EXPORT, not an import: an import would cost
					// the module its instantiate-with-nothing contract.
					if (seedRandom) {
						exports.addExport("__ronto_seed_random", ExternalKind.FUNCTION, seedRandomFuncIndex);
					}
					// The host's clock hook, the same move for the one service a module
					// with no imports cannot answer on its own: the host writes the time
					// it really knows (nanoseconds since the Unix epoch) and the clock
					// built-ins report it. Until it is called they signal, because a
					// zero cell names 1970 rather than reporting "no time".
					if (setTime) {
						exports.addExport("__ronto_set_time", ExternalKind.FUNCTION, setTimeFuncIndex);
					}
					// Host-callable Lisp functions requested via (rontolisp:wasm-export
					// ...), each under its :as alias (default: the Lisp name).
					for (ExportPlan p : exportPlans) {
						exports.addExport(p.decl().exportName(), ExternalKind.FUNCTION, p.funcIndex());
					}
				}
			});
		// Start section (--no-wasi reactor component only): the engine runs the
		// top-level init at instantiation, after the data segments install -- so a
		// top-level (defparameter ...) is readable from the very first export call,
		// which the host-driven `run` export could never guarantee. The tree shaker
		// treats it as a root and renumbers it like any function reference.
		if (this.component && this.noWasi) {
			mainWriter.writeStartSection(FUNC_START);
		}
		mainWriter
			// Code section
			.writeCode(code -> {
				// No-wasi mode: bodies for the twelve stubs at indices 0-11. TWO are
				// `unreachable; end` (no locals) -- fd_read and clock_time_get;
				// unreachable is stack-polymorphic so one shape satisfies every WASI
				// signature, and calling one traps.
				// The other ten ANSWER, each for its own reason (see the builders):
				// fd_write is a SINK, so writing to stdout/stderr on a reactor discards
				// the bytes instead of killing the instance; random_get is a
				// self-contained SplitMix64 generator over a linear-memory state cell
				// (which nothing calls here any more -- `random` inlines the same step
				// at the draw site, .kb/random.md -- so the shaker drops it unless
				// --host-random makes it the seeding forwarder);
				// the two environ functions report an EMPTY environment; and the four
				// filesystem slots report an errno, which the _open / _probe_file /
				// _list_directory / _file_length / _load runtimes already turn into
				// nil. EBADF on
				// fd_prestat_get is what ends _path_dirfd's preopen walk at the FIRST
				// fd, so a reactor answers "no preopen covers this path" instead of
				// looping.
				// The line the last two keep trapping over: a stub may discard output,
				// draw a pseudo-random number, or report a state the module really is
				// in (no environment, no files) -- but it may not invent INPUT, and it
				// may not name a time that is not the time. clock_time_get is a
				// BACKSTOP rather than the clock now: the three clock built-ins read
				// the host-set HOST_TIME_ADDR cell directly (WasmTimeCompiler), so
				// nothing reaches this slot unless a future path calls it without
				// going through them.
				// --host-random opts ONE slot out of all this: random_get stops being a
				// stub and forwards to a host import, which is the only way an answer
				// here can be the host's rather than a fact about the module. That is
				// what %random-byte spends per byte and what seeds `random`'s generator
				// once per instance.
				if (this.noWasi) {
					for (int i = 0; i < IMPORT_FUNC_COUNT; i++) {
						code.addFunction(switch (i) {
							case FUNC_FD_WRITE -> WasmIoRuntimeBuilder.buildNoWasiFdWriteSinkBody();
							case FUNC_RANDOM_GET -> this.hostRandom
									? WasmIoRuntimeBuilder.buildNoWasiHostRandomGetBody(
											WasmImportCompiler.PLACEHOLDER_FUNC_BASE + hostRandomOrdinal)
									: WasmIoRuntimeBuilder.buildNoWasiRandomGetBody();
							case FUNC_ENVIRON_SIZES_GET -> WasmIoRuntimeBuilder.buildNoWasiEnvironSizesGetBody();
							case FUNC_ENVIRON_GET -> WasmIoRuntimeBuilder.buildNoWasiErrnoBody(0);
							case FUNC_PATH_OPEN ->
								WasmIoRuntimeBuilder.buildNoWasiErrnoBody(WasmIoRuntimeBuilder.ERRNO_NOENT);
							case FUNC_FD_CLOSE, FUNC_FD_READDIR, FUNC_FD_PRESTAT_GET, FUNC_FD_PRESTAT_DIR_NAME,
									FUNC_FD_FILESTAT_GET ->
								WasmIoRuntimeBuilder.buildNoWasiErrnoBody(WasmIoRuntimeBuilder.ERRNO_BADF);
							default -> new byte[] { 0x00, 0x00, 0x0b };
						});
					}
				}
				code.addFunction(finalStartBytes)
					.addFunction(printI32Body)
					.addFunction(writeStrBody)
					.addFunction(printValBody)
					.addFunction(printI32NoNlBody)
					.addFunction(printF64Body)
					.addFunction(printF64NoNlBody)
					.addFunction(schubUmulhiBody)
					.addFunction(schubGBody)
					.addFunction(schubRopBody)
					.addFunction(f64DecBody)
					.addFunction(f32DecBody)
					.addFunction(decFmtBody)
					.addFunction(writeDecBody)
					.addFunction(printF32NoNlBody)
					.addFunction(appendBody)
					.addFunction(readLineBody)
					.addFunction(princValBody)
					.addFunction(lookupBody)
					.addFunction(envLookupBody)
					.addFunction(evalBody)
					.addFunction(applyBody)
					.addFunction(storeBody)
					.addFunction(internBody)
					.addFunction(readExprBody)
					.addFunction(readListBody)
					.addFunction(readBody)
					.addFunction(loadBody)
					.addFunction(WasmRatioRuntimeBuilder.buildRatNewBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatNumBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatDenBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_ADD, Instruction.F64_ADD))
					.addFunction(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_SUB, Instruction.F64_SUB))
					.addFunction(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_MUL, Instruction.F64_MUL))
					.addFunction(WasmRatioRuntimeBuilder.buildRatDivBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatCmpBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatCmpBitsBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatTruncBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatFloorBody(false))
					.addFunction(WasmRatioRuntimeBuilder.buildRatFloorBody(true))
					.addFunction(WasmRatioRuntimeBuilder.buildRatRoundBody())
					.addFunction(WasmRuntimeBuilder.buildToStringBody(FUNC_PRINC_VAL, 1))
					.addFunction(WasmRuntimeBuilder.buildToStringBody(FUNC_PRINT_VAL, 1))
					.addFunction(WasmRuntimeBuilder.buildToStringBody(FUNC_PRINC_VAL, 2))
					.addFunction(WasmStringRuntimeBuilder.buildCaseConvertBody(true))
					.addFunction(WasmStringRuntimeBuilder.buildCaseConvertBody(false))
					.addFunction(WasmStringRuntimeBuilder.buildCapitalizeBody())
					.addFunction(WasmStringRuntimeBuilder.buildSubseqBody())
					.addFunction(WasmStringRuntimeBuilder.buildStringEqBody(false, stringTable))
					.addFunction(WasmStringRuntimeBuilder.buildStringEqBody(true, stringTable))
					.addFunction(WasmStringRuntimeBuilder.buildTrimBody())
					.addFunction(WasmIoRuntimeBuilder.buildOpenBody())
					.addFunction(WasmIoRuntimeBuilder.buildCloseBody(stringTable, ostreamTableGlobalIndex))
					.addFunction(WasmIoRuntimeBuilder.buildWriteLineBody(stringTable))
					.addFunction(WasmRuntimeBuilder.buildEqualBody(this.usesInstances ? instanceTypeBase() : -1))
					.addFunction(WasmGetenvRuntimeBuilder.build());
				// Dispatch function bodies
				for (byte[] body : dispatchBodies) {
					code.addFunction(body);
				}
				// plist runtime helper body (FUNC_PLIST_GET)
				code.addFunction(WasmPlistRuntimeBuilder.buildPlistGet());
				// Hash-table runtime helper bodies (FUNC_HASH, FUNC_HASH_RESIZE)
				code.addFunction(WasmRuntimeBuilder.buildHashBody(this.usesInstances ? instanceTypeBase() : -1,
						hashDepthGlobalIndex, hashGasGlobalIndex));
				code.addFunction(WasmRuntimeBuilder.buildHashResizeBody());
				// Modulo / remainder runtime helper bodies (FUNC_RAT_REM, FUNC_RAT_MOD)
				code.addFunction(WasmRatioRuntimeBuilder.buildRatRemBody(false));
				code.addFunction(WasmRatioRuntimeBuilder.buildRatRemBody(true));
				// gensym runtime helper body (FUNC_GENSYM)
				code.addFunction(WasmGensymRuntimeBuilder.build());
				// p1-future-await runtime helper body (FUNC_P1_FUTURE_AWAIT)
				code.addFunction(WasmP1FutureRuntimeBuilder.buildAwait());
				// binary stream runtime helper bodies (FUNC_READ_BYTE, FUNC_WRITE_BYTE)
				code.addFunction(WasmIoRuntimeBuilder.buildReadByteBody());
				code.addFunction(WasmIoRuntimeBuilder.buildWriteByteBody());
				// string-stream runtime helper bodies (FUNC_WRITE_STREAM_STR,
				// FUNC_MAKE_STR_OSTREAM, FUNC_MAKE_STR_ISTREAM, FUNC_STR_STREAM_CONTENTS)
				code.addFunction(WasmStringStreamRuntimeBuilder.buildWriteStreamStrBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildMakeOutputStreamBody(ostreamTableGlobalIndex));
				code.addFunction(WasmStringStreamRuntimeBuilder.buildMakeInputStreamBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildContentsBody());
				// symbol-API helper bodies (FUNC_MAKE_SYMBOL .. FUNC_FMAKUNBOUND)
				code.addFunction(makeSymbolBody);
				code.addFunction(internSymBody);
				code.addFunction(boundpBody);
				code.addFunction(symbolValueBody);
				code.addFunction(fboundpBody);
				code.addFunction(fmakunboundBody);
				code.addFunction(setSymbolFunctionBody);
				code.addFunction(fenvFunctionBody);
				// read-char runtime helper body (FUNC_READ_CHAR)
				code.addFunction(WasmIoRuntimeBuilder.buildReadCharBody());
				// _str_build helper body (FUNC_STR_BUILD): linear[off..off+len) -> a
				// TYPE_STRING backed by a $str_bytes GC array.
				code.addFunction(WasmStringRuntimeBuilder.buildStrBuildBody());
				// _str_fresh (FUNC_STR_FRESH): like _str_build but id = STRING_ID_CTR++.
				code.addFunction(WasmStringRuntimeBuilder.buildStrFreshBody());
				// _str_to_mem (FUNC_STR_TO_MEM): copy a string's GC array into
				// linear[ptr..).
				code.addFunction(WasmStringRuntimeBuilder.buildStrToMemBody());
				// _write_str_gc (FUNC_WRITE_STR_GC): print a string value from its GC
				// array.
				code.addFunction(WasmStringRuntimeBuilder.buildWriteStrGcBody());
				// _charvec_to_str (FUNC_CHARVEC_TO_STR): normalize a mutable character
				// vector into the equivalent runtime string.
				code.addFunction(WasmStringRuntimeBuilder.buildCharvecToStrBody());
				// _charvec_p (FUNC_CHARVEC_P): the shape half of the above -- is this
				// value a mutable character vector? -- in constant time.
				code.addFunction(WasmStringRuntimeBuilder.buildCharvecPBody());
				// _str_char_count (FUNC_STR_CHAR_COUNT): count Unicode characters in a
				// UTF-8-encoded string.
				code.addFunction(WasmStringRuntimeBuilder.buildStrCharCountBody());
				// _str_char_at (FUNC_STR_CHAR_AT): the i-th character's code point in a
				// UTF-8-encoded string.
				code.addFunction(WasmStringRuntimeBuilder.buildStrCharAtBody());
				// _str_char_byte_offset (FUNC_STR_CHAR_BYTE_OFFSET): the byte offset of
				// the i-th character in a UTF-8-encoded string.
				code.addFunction(WasmStringRuntimeBuilder.buildStrCharByteOffsetBody());
				// _char_upcase (FUNC_CHAR_UPCASE): full-Unicode case fold backed by
				// Character.toUpperCase(int); binary-searches the compressed range table
				// baked into the static data at upperFoldOffset.
				code.addFunction(charUpcaseBody);
				// _char_downcase (FUNC_CHAR_DOWNCASE): the same shape, backed by
				// Character.toLowerCase(int); binary-searches the table at
				// lowerFoldOffset.
				code.addFunction(charDowncaseBody);
				// _char_alnum_p (FUNC_CHAR_ALNUM_P): full-Unicode letter-or-digit
				// membership backed by Character.isLetterOrDigit(int); binary-searches
				// the (from, to) pair table at alnumOffset.
				code.addFunction(charAlnumBody);
				// the reader # dispatch helper bodies (FUNC_RD_CHARLIT .. FUNC_RD_MEMEQ),
				// stubs when the program does not read
				code.addFunction(rdCharlitBody);
				code.addFunction(rdRadixBody);
				code.addFunction(rdBitsBody);
				code.addFunction(rdArrayNBody);
				code.addFunction(rdPackedBody);
				code.addFunction(rdStructBody);
				code.addFunction(rdTokenBody);
				code.addFunction(rdLenBody);
				code.addFunction(rdLevelBody);
				code.addFunction(rdDimsBody);
				code.addFunction(rdFlatBody);
				code.addFunction(rdInferBody);
				code.addFunction(rdMemeqBody);
				// bignum helper bodies (FUNC_INT_NEW, FUNC_INT_VAL, FUNC_PRINT_I64_NO_NL)
				code.addFunction(WasmBignumRuntimeBuilder.buildIntNewBody());
				code.addFunction(WasmBignumRuntimeBuilder.buildIntValBody());
				code.addFunction(WasmBignumRuntimeBuilder.buildPrintI64NoNlBody());
				// the limb bigint runtime bodies (FUNC_LIMB_OF .. FUNC_BIG_HASH)
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbOfBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbNewBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbGetBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbAddsubBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbNegBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbCopyBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbMulBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbCmpBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbShlBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbShrBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbDivremMagBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildLimbDivmodSmallBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigAddBody(false));
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigAddBody(true));
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigMulBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigNegBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigDivremBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigModBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigCmpBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigBitopBody(am.ik.wasm.Instruction.I64_AND));
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigBitopBody(am.ik.wasm.Instruction.I64_OR));
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigBitopBody(am.ik.wasm.Instruction.I64_XOR));
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigNotBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigAshBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigIntlenBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigLogbitpBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigGcdBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigGrowBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigToF64Body());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigPrintBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigPrintMagBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigPad9Body());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigEqBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigHashBody());
				code.addFunction(WasmBigIntRuntimeBuilder.buildBigFdivBody());
				// the unboxed-fixnum fusion helper bodies (FUNC_FX_VAL .. FUNC_FX_REM)
				code.addFunction(WasmFxRuntimeBuilder.buildFxValBody());
				code.addFunction(WasmFxRuntimeBuilder.buildFxAddBody(false));
				code.addFunction(WasmFxRuntimeBuilder.buildFxAddBody(true));
				code.addFunction(WasmFxRuntimeBuilder.buildFxMulBody());
				code.addFunction(WasmFxRuntimeBuilder.buildFxAshBody());
				code.addFunction(WasmFxRuntimeBuilder.buildFxModBody());
				code.addFunction(WasmFxRuntimeBuilder.buildFxRemBody());
				code.addFunction(WasmFxRuntimeBuilder.buildIvSetBody());
				code.addFunction(
						WasmFxRuntimeBuilder.buildTSymBody(tSymEntry.offset(), tSymEntry.length(), tSymGlobalIndex));
				// probe-file runtime helper body (FUNC_PROBE_FILE)
				code.addFunction(WasmIoRuntimeBuilder.buildProbeFileBody());
				// fresh-line stream helper body (FUNC_FRESH_LINE_STREAM)
				code.addFunction(WasmStringStreamRuntimeBuilder.buildFreshLineStreamBody(stringTable.newline.offset()));
				// peek-char runtime helper body (FUNC_PEEK_CHAR)
				code.addFunction(WasmIoRuntimeBuilder.buildPeekCharBody());
				// %list-directory runtime helper body (FUNC_LIST_DIRECTORY)
				code.addFunction(WasmIoRuntimeBuilder.buildListDirectoryBody());
				// unboxed-local boxed-read helper body (FUNC_UB_READ)
				code.addFunction(WasmFxRuntimeBuilder.buildUbReadBody(rawSentinelGlobalIndex));
				// %fixed-decimal runtime helper body (FUNC_FIXED_DEC)
				code.addFunction(WasmFixedDecimalRuntimeBuilder.build());
				// shared numeric-to-f64 coercion body (FUNC_AS_F64)
				code.addFunction(WasmEmitHelper.buildAsF64Body());
				// shared general-array element access bodies (FUNC_ARR_GET/FUNC_ARR_SET)
				code.addFunction(WasmArrayRuntimeBuilder.buildArrGetBody());
				code.addFunction(WasmArrayRuntimeBuilder.buildArrSetBody());
				// shared generic sequence-length dispatch body (FUNC_SEQ_LEN)
				code.addFunction(WasmLengthCompiler.buildSeqLenBody());
				// string output-stream buffer helper body (FUNC_OSTREAM_ROOM)
				code.addFunction(WasmStringStreamRuntimeBuilder.buildOstreamRoomBody(ostreamTableGlobalIndex));
				// strict UTF-8 octet-vector decode body (FUNC_IV_UTF8_STR)
				code.addFunction(WasmStringRuntimeBuilder.buildIvUtf8StrBody());
				// preopen-resolving path front end body (FUNC_PATH_DIRFD)
				code.addFunction(WasmIoRuntimeBuilder.buildPathDirFdBody());
				// bulk packed-buffer binary I/O bodies (FUNC_READ_PACKED,
				// FUNC_WRITE_PACKED)
				code.addFunction(WasmPackedIoRuntimeBuilder.buildReadPackedBody(this.simd));
				code.addFunction(WasmPackedIoRuntimeBuilder.buildWritePackedBody(this.simd));
				// command-line vector body (FUNC_ARGV); a stub unless the program
				// reads its arguments, since the real one calls imports only a reader
				// declares.
				code.addFunction(argvOrdinals == null ? WasmArgvRuntimeBuilder.buildStub()
						: WasmArgvRuntimeBuilder.build(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + argvOrdinals[0],
								WasmImportCompiler.PLACEHOLDER_FUNC_BASE + argvOrdinals[1]));
				// equalp key-fold body (FUNC_EQUALP_KEY); an identity stub unless the
				// program writes a :test 'equalp table, since nothing else calls it.
				code.addFunction(equalpDepthGlobalIndex < 0 ? WasmEqualpKeyRuntimeBuilder.buildStub()
						: WasmEqualpKeyRuntimeBuilder.build(equalpDepthGlobalIndex, equalpGasGlobalIndex));
				// file-length body (FUNC_FILE_LENGTH), over the fd_filestat_get import.
				code.addFunction(WasmIoRuntimeBuilder.buildFileLengthBody());
				// arithmetic non-number landing bodies (FUNC_TYPE_ERR_INT,
				// FUNC_TYPE_ERR_NUM): a catchable $lisp-cond throw in EH mode, a bare
				// `unreachable` outside it (no tag section exists there, and referencing
				// the prin1 renderer would pin the printer family into every module).
				code.addFunction(WasmEmitHelper.buildTypeErrBody(ehMode, expIntEntry));
				code.addFunction(WasmEmitHelper.buildTypeErrBody(ehMode, expNumEntry));
				// either-representation character index body (FUNC_STR_CHAR_REF)
				code.addFunction(WasmStringRuntimeBuilder.buildStrCharRefBody());
				// vec: SIMD block bodies (--simd only), in FUNC_VEC_BASE index order.
				if (this.simd) {
					for (int i = 0; i < WasmVecSimdRuntimeBuilder.FUNC_COUNT; i++) {
						code.addFunction(WasmVecSimdRuntimeBuilder.build(i, FUNC_VEC_BASE));
					}
					// linalg: SIMD block bodies, in linalgFuncBase() index order.
					for (int i = 0; i < WasmLinalgSimdRuntimeBuilder.FUNC_COUNT; i++) {
						code.addFunction(WasmLinalgSimdRuntimeBuilder.build(i, FUNC_VEC_BASE));
					}
				}
				// Async future runtime bodies (asyncMode only), in asyncFuncBase() order.
				if (this.asyncMode) {
					for (int i = 0; i < WasmFutureRuntimeBuilder.FUNC_COUNT; i++) {
						code.addFunction(WasmFutureRuntimeBuilder.build(i, asyncFuncBase(), asyncTypeBase(),
								asyncTypeBase() + 1, asyncTypeBase() + 2, currentTaskGlobalIndex, sched, cb));
					}
				}
				// The degenerate tier's stream runtime bodies, in p1StreamFuncBase()
				// order.
				if (this.usesP1Streams) {
					for (int i = 0; i < WasmP1StreamRuntimeBuilder.FUNC_COUNT; i++) {
						code.addFunction(WasmP1StreamRuntimeBuilder.build(i, p1StreamTypeBase()));
					}
				}
				// The EXTRA per-arity dispatcher bodies, in extraDispatchFuncBase()
				// order.
				for (byte[] body : extraDispatchBodies) {
					code.addFunction(body);
				}
				// User defun function bodies
				for (byte[] body : userFunctionBodies) {
					code.addFunction(body);
				}
				// Lambda function bodies
				for (byte[] body : lambdaFunctionBodies) {
					code.addFunction(body);
				}
				// Memory-export helper bodies (must precede the wrapper bodies to match
				// the
				// function-section order).
				if (memoryHelpers) {
					code.addFunction(WasmExportRuntimeBuilder.buildAllocBody());
					code.addFunction(WasmExportRuntimeBuilder.buildStrFromMemBody());
					if (hostArena) {
						code.addFunction(WasmExportRuntimeBuilder.buildAllocMarkBody());
						// A reentrant module's arena pops also clamp to the park floor:
						// a park block carved while a bracket was open holds another
						// in-flight call's staging.
						code.addFunction(WasmExportRuntimeBuilder.buildAllocResetBody(this.reentrant));
					}
				}
				// The :bytes marshalling helper bodies, matching the function-section
				// order.
				if (bytesFromMem) {
					code.addFunction(WasmExportRuntimeBuilder.buildBytesFromMemBody());
				}
				if (bytesBoundary) {
					code.addFunction(WasmExportRuntimeBuilder.buildBytesCopyBody());
					code.addFunction(WasmExportRuntimeBuilder.buildBytesFillBody());
				}
				// The --reentrant park-block allocator bodies, matching the
				// function-section order.
				if (parkHelpers) {
					code.addFunction(WasmExportRuntimeBuilder.buildParkAllocBody(allocFuncIndex));
					code.addFunction(WasmExportRuntimeBuilder.buildParkFreeBody());
					code.addFunction(WasmExportRuntimeBuilder.buildParkStrResultBody(parkAllocFuncIndex));
				}
				// __ronto_seed_random: the host's replacement for the generator's
				// constant start state, matching the function-section order.
				if (seedRandom) {
					code.addFunction(WasmIoRuntimeBuilder.buildSeedRandomBody());
				}
				// __ronto_set_time: the host's clock, same order.
				if (setTime) {
					code.addFunction(WasmIoRuntimeBuilder.buildSetTimeBody());
				}
				// Export wrapper bodies (host-callable), one per (rontolisp:wasm-export
				// ...).
				for (byte[] body : exportBodies) {
					code.addFunction(body);
				}
				// Component string-ABI bodies (todo 92 Tier 2), matching the function
				// section: cabi_realloc, one cabi_post_* per flat-result signature
				// (identical bodies; the flat params are ignored), then the retptr
				// shims.
				if (componentStringAbi) {
					code.addFunction(WasmExportRuntimeBuilder.buildCabiReallocBody(allocFuncIndex));
					for (int k = 0; k < componentPostKinds.size(); k++) {
						code.addFunction(WasmExportRuntimeBuilder.buildCabiPostReturnBody());
					}
					for (ExportPlan p : retptrShimPlans) {
						code.addFunction(WasmExportRuntimeBuilder.buildRetptrShimBody(p.funcIndex(),
								cabiReallocFuncIndex, WasmExportCompiler.paramSlotCount(p.decl())));
					}
				}
			})
			// Data section
			.writeDataSection(data -> {
				// The heap pointer and the runtime intern table's base are seeded at
				// instantiation (values computed above from the final static-data size).
				// Instantiation-time seeding also covers hosts that call exported
				// functions without running _start.
				data.addActiveData(0, HEAP_PTR_ADDR, littleEndian32(heapBase));
				data.addActiveData(0, RT_INTERN_BASE_ADDR, littleEndian32(rtInternBase));
				// Runtime string ids start at heapBase (above every interned/rt-intern
				// offset) and only increase, so they never collide with an interned id
				// nor
				// with each other even as the assembly scratch offset is reused.
				data.addActiveData(0, STRING_ID_CTR_ADDR, littleEndian32(heapBase));
				if (stringData.length > 0) {
					data.addActiveData(0, dataBase, stringData);
				}
				// Own segments so the shaker can drop each with its sole reader
				// (_char_upcase / _char_downcase) -- see caseFoldSegments below.
				data.addActiveData(0, upperFoldOffset, upperFoldBytes);
				data.addActiveData(0, lowerFoldOffset, lowerFoldBytes);
				data.addActiveData(0, alnumOffset, alnumBytes);
			});
		byte[] coreModule = out.toByteArray();
		// Resolve (rontolisp:wasm-import ...) directives: prepend the host imports and
		// renumber every function reference (incl. the placeholder call indices). Must
		// run before the tree shaker -- the module is not valid until then.
		if (!hostImports.isEmpty()) {
			coreModule = am.ik.wasm.WasmImportInjector.inject(coreModule, hostImports,
					WasmImportCompiler.PLACEHOLDER_FUNC_BASE);
		}
		if (this.rawCoreForTest) {
			// The callback-probe integration test assembles its own component around
			// the raw core module.
			return coreModule;
		}
		// The case-fold tables' segments are owned by their sole readers; the injector
		// (just above) shifted every defined function index up by the injected import
		// count, so the owners' indices shift with them. Segments 0-2 are the fixed
		// seed cells, then the string data (when present), then the three tables.
		int upperFoldSegIndex = stringData.length > 0 ? 4 : 3;
		List<am.ik.wasm.WasmTreeShaker.OwnedDataSegment> caseFoldSegments = List.of(
				new am.ik.wasm.WasmTreeShaker.OwnedDataSegment(upperFoldSegIndex,
						new int[] { FUNC_CHAR_UPCASE + hostImports.size() }),
				new am.ik.wasm.WasmTreeShaker.OwnedDataSegment(upperFoldSegIndex + 1,
						new int[] { FUNC_CHAR_DOWNCASE + hostImports.size() }),
				new am.ik.wasm.WasmTreeShaker.OwnedDataSegment(upperFoldSegIndex + 2,
						new int[] { FUNC_CHAR_ALNUM_P + hostImports.size() }));
		// A string interned by a body Pass 2a-2c emitted, and by nothing else, goes when
		// that body does. One blob cites EVERY entry -- the runtime intern table, which
		// _intern scans by offset -- so under usesIntern each candidate's (offset,
		// length) row is offered as a range of its own, probed on the STRING interval:
		// row and bytes fall together, and _intern skips the zeroed hole a cut row
		// leaves (WasmReadRuntimeBuilder.emitInternScan). A candidate interned after the
		// blob snapshot has no row and offers only its bytes -- such an entry is
		// runtime-invisible by construction (T is interned BEFORE the snapshot for
		// exactly that reason).
		int stringDataSegIndex = upperFoldSegIndex - 1;
		List<am.ik.wasm.WasmTreeShaker.DroppableDataRange> stringRanges = stringData.length == 0 ? List.of()
				: stringTable.shakeableRanges(stringDataSegIndex, dataBase, internBase, internRows);
		@Nullable Map<Integer, String> funcSizeNames = debugFuncSizes() ? funcSizeNames(functions, lambdaDecls) : null;
		if (this.component) {
			if (this.optimize.eliminatesDeadCode()) {
				coreModule = shakeCore(coreModule, caseFoldSegments, stringRanges, funcSizeNames, hostImports.size());
			}
			else if (funcSizeNames != null) {
				dumpFuncSizes(coreModule, funcSizeNames, hostImports.size(),
						am.ik.wasm.WasmSections.importedFunctionCount(coreModule), null);
			}
			if (this.serve) {
				// rontolisp:http-handler: wrap the core (which exports %http-dispatch)
				// into a wasi:http/handler@0.3 component (wasmtime serve). A program that
				// also fetches reaches http.lisp's client half through the same block --
				// serve and serve+fetch are ONE component shape.
				// A rontolisp:wit-import joins the fixed wasi:http surface as an extra
				// instance import (canon lower), so a handler's state can live in a real
				// store.
				// http.lisp's own wasi:http imports are part of the
				// FIXED
				// surface (the import block), so they must NOT be re-emitted as user
				// imports
				// (that double-declares their packages in the emitted WIT) -- only the
				// ADDITIONAL rontolisp:wit-import interfaces are (a served handler whose
				// state
				// lives in a real store: wasi:keyvalue joins as an extra instance
				// import).
				List<WasmComponentImportCompiler.Import> serveUserImports = WasmServeComponentBuilder
					.additionalImports(componentImports);
				this.componentWit = WitEmitter.emit(WitEmitter.VARIANT_HTTP_SERVER, List.of(), serveUserImports);
				return WasmComponentBuilder.buildServe(coreModule, componentImports);
			}
			// Lift each wasm-export into a host-callable component-model export
			// (synchronous canon lift; WAVE-invokable) alongside wasi:cli/run. Scalar
			// exports lift with no canonical options; :string/:s-expr ones with the
			// canonical string options over the appended ABI helpers (todo 92 Tier 2).
			List<WasmExportCompiler.Decl> componentExportDecls = new ArrayList<>();
			for (ExportPlan p : exportPlans) {
				componentExportDecls.add(p.decl());
			}
			if (this.noWasi) {
				// --no-wasi: a REACTOR component that imports nothing -- no import
				// block, no adapter, no shared memory module, with or without
				// --optimize (the zero-import property is the flag's contract, not a
				// narrowing outcome). Its WIT world is the same empty world as the
				// --no-gc reactor's.
				this.componentWit = WitEmitter.emit(WitEmitter.VARIANT_REACTOR, componentExportDecls);
				return WasmComponentBuilder.buildReactor(coreModule, componentExportDecls);
			}
			// This is the non-serve component path (serve returned above), where
			// emitHttpImport is always false: rontolisp:fetch here is the http.lisp
			// library
			// over canon-lowered wasi:http user imports (the base variant), not the WAT
			// http-client blob.
			// An interface the block itself declares (wait.lisp's
			// wasi:clocks/monotonic-clock) is part of the fixed WASI surface, so the
			// emitted WIT must not re-declare it as a user import.
			// Under --optimize the fixed surface itself is narrowed to what the shaken
			// core
			// still reaches, so the world must name only the interfaces that survived --
			// the same set the builder prunes the import block to.
			// One narrowing question the core module's bytes cannot answer: a file
			// descriptor is a VALUE there, so "can this program write fd 2" is decided
			// here. The reserved *error-output* handle is materialized by a read of that
			// variable, by warn's report, by the _start seed the variable's own binding
			// installs, and by the entry function's uncaught-condition landing pad (all
			// go through StreamDesignators.STANDARD_ERROR_HANDLE) -- so naming the two
			// source spellings covers the first three, and the fourth ANSWERS FOR
			// ITSELF: it is injected by the compiler, its text is never in the program,
			// and it contributes the same uncaughtReportPad the emission is gated on
			// rather than a scan that would have to remember it exists. --dynamic makes
			// every symbol reachable at run time, which no source scan can bound, so it
			// keeps the wider surface.
			WasmComponentBuilder.Narrowing narrowing = new WasmComponentBuilder.Narrowing(
					this.optimize.eliminatesDeadCode(),
					this.dynamic || uncaughtReportPad || programUsesSymbol(program, LispNames.ERROR_OUTPUT_VAR)
							|| programUsesSymbol(program, LispNames.WARN)
							|| programUsesSymbol(program, LispNames.WARN_INTERNAL));
			this.componentWit = WitEmitter.emit(WitEmitter.VARIANT_BASE, componentExportDecls,
					WasmComponentBuilder.additionalImports(componentImports),
					WasmComponentBuilder.wasiInterfaces(coreModule, componentImports, narrowing));
			return WasmComponentBuilder.build(coreModule, componentExportDecls, componentImports, narrowing);
		}
		if (this.optimize.eliminatesDeadCode()) {
			return shakeCore(coreModule, caseFoldSegments, stringRanges, funcSizeNames, hostImports.size());
		}
		if (funcSizeNames != null) {
			dumpFuncSizes(coreModule, funcSizeNames, hostImports.size(),
					am.ik.wasm.WasmSections.importedFunctionCount(coreModule), null);
		}
		return coreModule;
	}

	// The import-slot ordinal of a $sched builtin field.
	private static int schedOrdinal(Map<String, Integer> importSlotIndex, String field) {
		return Objects.requireNonNull(importSlotIndex.get(WasmComponentImportCompiler.SCHED_MODULE + "\0" + field));
	}

	// -Drontolisp.wasm.debug-func-sizes (the wasm twin of
	// rontolisp.jvm.debug-method-sizes): dump every SHIPPED function's code-entry size
	// with its final index and the Lisp definition (or runtime helper) it came from.
	private static boolean debugFuncSizes() {
		return System.getProperty("rontolisp.wasm.debug-func-sizes") != null;
	}

	/**
	 * Pre-injection function index to human-readable label, for {@link #dumpFuncSizes}:
	 * the {@code FUNC_*} runtime helper constants (via reflection over this class's own
	 * fields -- a debug aid, so a native image that strips the metadata just degrades to
	 * positional labels), the dispatch ladder, and every user defun / lambda / top-level
	 * chunk by its Lisp-derived name.
	 */
	private static Map<Integer, String> funcSizeNames(Map<String, WasmFunctionInfo> functions,
			List<LambdaInfo> lambdaDecls) {
		Map<Integer, String> names = new HashMap<>();
		for (java.lang.reflect.Field f : WasmLispCompiler.class.getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == int.class
					&& f.getName().startsWith("FUNC_")) {
				try {
					names.put(f.getInt(null), f.getName());
				}
				catch (IllegalAccessException ex) {
					// a missing label only degrades the dump
				}
			}
		}
		for (int a = 0; a <= MAX_CALLABLE_ARITY; a++) {
			names.put(FUNC_DISPATCH_BASE + a, "_dispatch_" + a);
		}
		names.put(FUNC_DISPATCH_SPREAD, "_dispatch_spread");
		for (WasmFunctionInfo fi : functions.values()) {
			names.put(fi.funcIndex(), fi.name());
		}
		for (LambdaInfo li : lambdaDecls) {
			names.put(li.funcIndex(), li.methodName());
		}
		return names;
	}

	/**
	 * Tree-shake the core module, dumping per-function sizes when the debug flag asks.
	 */
	private static byte[] shakeCore(byte[] coreModule,
			List<am.ik.wasm.WasmTreeShaker.OwnedDataSegment> caseFoldSegments,
			List<am.ik.wasm.WasmTreeShaker.DroppableDataRange> stringRanges, @Nullable Map<Integer, String> funcNames,
			int importShift) {
		if (funcNames == null) {
			return am.ik.wasm.WasmTreeShaker.shake(coreModule, caseFoldSegments, stringRanges);
		}
		am.ik.wasm.WasmTreeShaker.ShakeResult res = am.ik.wasm.WasmTreeShaker.shakeWithRemap(coreModule,
				caseFoldSegments, stringRanges);
		dumpFuncSizes(res.module(), funcNames, importShift, res.importedFunctionCount(), res.funcRemap());
		return res.module();
	}

	/**
	 * Walks the finished module's code section and prints one stderr line per function,
	 * largest first: {@code [func-size] <bytes>\t<final index>\t<name>}. Sizes are the
	 * post-shake code-entry sizes, so they are the artifact's actual bytes; names come
	 * from {@link #funcSizeNames} joined through the shaker's index remap.
	 */
	private static void dumpFuncSizes(byte[] module, Map<Integer, String> funcNames, int importShift,
			int importedBefore, int @Nullable [] funcRemap) {
		int importedAfter = importedBefore;
		int[] preOfFinal = null;
		if (funcRemap != null) {
			importedAfter = 0;
			for (int i = 0; i < importedBefore; i++) {
				if (funcRemap[i] >= 0) {
					importedAfter++;
				}
			}
			// Several pre indices can share one final index (identical bodies fold to a
			// survivor); the first -- the survivor -- names the shared body.
			preOfFinal = new int[funcRemap.length];
			Arrays.fill(preOfFinal, -1);
			for (int pre = 0; pre < funcRemap.length; pre++) {
				if (funcRemap[pre] >= 0 && preOfFinal[funcRemap[pre]] < 0) {
					preOfFinal[funcRemap[pre]] = pre;
				}
			}
		}
		record Row(int size, int finalIndex, String name) {
		}
		List<Row> rows = new ArrayList<>();
		int[] p = { 8 };
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xFF;
			int size = readLebU32(module, p);
			int end = p[0] + size;
			if (id == 10) {
				int count = readLebU32(module, p);
				for (int i = 0; i < count; i++) {
					int bodySize = readLebU32(module, p);
					int finalIndex = importedAfter + i;
					int pre = preOfFinal == null ? finalIndex : preOfFinal[finalIndex];
					String name = funcNames.getOrDefault(pre - importShift, "_func_" + (pre - importShift));
					rows.add(new Row(bodySize, finalIndex, name));
					p[0] += bodySize;
				}
				break;
			}
			p[0] = end;
		}
		rows.sort((a, b) -> Integer.compare(b.size(), a.size()));
		long total = 0;
		for (Row row : rows) {
			total += row.size();
			System.err.println("[func-size] " + row.size() + "\t" + row.finalIndex() + "\t" + row.name());
		}
		System.err.println("[func-size] total " + total + " bytes in " + rows.size() + " functions");
	}

	private static int readLebU32(byte[] bytes, int[] p) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = bytes[p[0]++] & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return result;
			}
			shift += 7;
		}
	}

	static boolean hasDoubleLiteral(List<LispVal> args) {
		for (int i = 1; i < args.size(); i++) {
			if (containsDouble(args.get(i))) {
				return true;
			}
		}
		return false;
	}

	/** The forms whose value is an integer whatever their argument types. */
	private static final java.util.Set<String> INTEGER_VALUED_FORMS = java.util.Set.of(LispNames.ROUND,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING);

	static boolean containsDouble(LispVal val) {
		if (val instanceof LispDouble) {
			return true;
		}
		if (val instanceof LispCons cons) {
			// A rounding form yields an integer whatever its argument types, so a
			// double literal inside it must not drag the ENCLOSING arithmetic onto
			// the double path: (- 0 (round (* v 100.0))) is integer work.
			if (cons.car() instanceof LispSymbol head && INTEGER_VALUED_FORMS.contains(head.name())) {
				return false;
			}
			for (LispVal element : cons.toList()) {
				if (containsDouble(element)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The funcIds the arity/spread dispatch ladders and the {@code _lookup} name registry
	 * must be able to reach -- everything else in {@code defuns} is called only through a
	 * direct {@code call}, so naming it in a ladder would do nothing except keep it alive
	 * for {@code --optimize} ({@code .kb/optimize-dead-code-elimination.md}).
	 *
	 * <p>
	 * Two sources, and both are EXACT rather than heuristic:
	 * <ul>
	 * <li>{@code valueFuncIds} -- what Pass 2 actually materialized as a closure. Every
	 * body is emitted by the time this runs, so a {@code #'name} a macro synthesized
	 * during Pass 2 is in it, which is precisely what a pre-scan of the source program
	 * would have missed;</li>
	 * <li>the names a runtime SYMBOL designator can resolve. {@code _lookup} matches
	 * INTERNED OFFSETS, so a registry row is reachable only when the program interned
	 * that name for some other reason -- a quoted symbol, a string literal, an
	 * {@code intern} of a literal. A name nothing spells gets a runtime-interned offset
	 * that matches no static row. The probe reads {@code Ctx.spelledLiterals} -- the
	 * spellings Pass 2 emitted as VALUES -- not the whole string table: the table also
	 * holds entries the compiler interned for its private structures (an instance
	 * layout's slot names, the printer's {@code "-"}/{@code "/"} pieces, registry row
	 * names), and no run-time path turns those bytes into a designator the program did
	 * not spell itself, so letting them arm a row gave every same-named defun a ladder
	 * case -- measured as one row + arm per chipz accessor whose slot name the layout
	 * directory happened to intern. Which spellings count is the shared
	 * {@link am.ik.rontolisp.compiler.DesignatorSpellings}, so the JVM twin cannot drift
	 * from this one.</li>
	 * </ul>
	 *
	 * <p>
	 * The carve-out is {@link am.ik.rontolisp.eval.LibraryDefunPruner}'s, verbatim: a
	 * program that FORGES a function name at run time out of computed strings loses it.
	 * The pruner has already removed such a defun outright in that case (the error is the
	 * ordinary undefined-function one); here the name simply stops resolving. Compile
	 * with {@code --dynamic} -- which turns this gate off entirely, since late binding
	 * resolves any name at run time -- to keep every function dispatchable.
	 * @param defuns the program's defuns, index = funcId
	 * @param valueFuncIds the funcIds Pass 2 materialized as closures
	 * @param spelledLiterals the literal spellings Pass 2 emitted as runtime values
	 * @param registryLive whether a real {@code _lookup} registry is emitted at all
	 * @param symbolBuilders whether the program contains a symbol BUILDER
	 * ({@code RuntimeNameProducers.anySymbolBuilder}) -- only then can a framed string
	 * literal or keyword spelling become a designator, so only then are those probes
	 * applied
	 * @return the funcIds that need a ladder case (and a registry row)
	 */
	/**
	 * Whether any defun's name is one a runtime designator could CARRY -- a spelling this
	 * compile emits as a value, or one a symbol builder can assemble from those. The
	 * registry answers names and nothing else, so a module holding no such spelling has
	 * nothing for {@code _lookup} to find and needs neither it nor the blob, however many
	 * designators Pass 2 dispatched. Uses the probe {@link #dispatchableFuncIds} uses for
	 * the rows themselves, so the gate and the rows agree by construction.
	 * @param defuns the program's top-level functions
	 * @param spelledLiterals every literal spelling Pass 2 emitted as a runtime value
	 * @param symbolBuilders whether the program contains a symbol builder
	 * @return {@code true} when at least one defun name is reachable as a designator
	 */
	private static boolean anyDefunNameSpelled(List<DefunDecl> defuns, Set<String> spelledLiterals,
			boolean symbolBuilders) {
		for (DefunDecl defun : defuns) {
			if (DesignatorSpellings.anySpelled(defun.name, spelledLiterals, symbolBuilders)) {
				return true;
			}
		}
		return false;
	}

	private Set<Integer> dispatchableFuncIds(List<DefunDecl> defuns, Set<Integer> valueFuncIds,
			Set<String> spelledLiterals, boolean registryLive, boolean anyNameResolvable, boolean symbolBuilders) {
		if (this.dynamic || anyNameResolvable) {
			// Late binding, or an operator that can produce a name this compile never
			// sees spelled: any name can be resolved at run time, so nothing is provably
			// call-only.
			Set<Integer> all = new HashSet<>(valueFuncIds);
			for (int i = 0; i < defuns.size(); i++) {
				all.add(i);
			}
			return all;
		}
		Set<Integer> dispatchable = new HashSet<>(valueFuncIds);
		if (registryLive) {
			for (int i = 0; i < defuns.size(); i++) {
				// Every spelling a runtime designator can carry for the name --
				// canonical, the alias row's, the bare member, and (only with a symbol
				// BUILDER present) the framed string literal and the two package-less
				// symbol spellings. The list is shared with the JVM twin
				// (compiler.DesignatorSpellings) so the two cannot drift.
				if (DesignatorSpellings.anySpelled(defuns.get(i).name, spelledLiterals, symbolBuilders)) {
					dispatchable.add(i);
				}
			}
		}
		if (Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			// Sizing aid: how much of the program the ladders still name. A defun listed
			// as neither VALUE nor INTERNED is one --optimize can now reach; a
			// name-armed row names the literal spelling that holds it open.
			System.err.println("[dispatch-gate] " + dispatchable.size() + " of " + defuns.size()
					+ " defuns dispatchable (" + valueFuncIds.size() + " funcIds materialized as values)");
			for (int i = 0; i < defuns.size(); i++) {
				if (!dispatchable.contains(i)) {
					System.err.println("[dispatch-gate] call-only\t" + defuns.get(i).name);
				}
				else if (!valueFuncIds.contains(i)) {
					String name = defuns.get(i).name;
					System.err.println("[dispatch-gate] name-armed\t" + name + "\tby\t"
							+ DesignatorSpellings.matched(name, spelledLiterals, symbolBuilders));
				}
			}
		}
		return dispatchable;
	}

	/**
	 * Whether the program can produce a function NAME this compile never sees spelled out
	 * -- in which case {@link #dispatchableFuncIds} must keep every function
	 * dispatchable, because {@code _lookup} may be asked for any of them.
	 *
	 * <p>
	 * Only the DATA EVALUATORS answer yes -- {@code eval}, {@code read},
	 * {@code read-from-string}, a runtime {@code load}: {@code (eval (read))} calls a
	 * function whose name exists only in the input, which no probe of the module's own
	 * constants can cover. The symbol builders ({@code intern}, {@code find-symbol},
	 * {@code uiop:symbol-call}, ...) no longer bail: whatever they can produce that
	 * RESOLVES is a spelling the module holds, and {@link #dispatchableFuncIds} probes
	 * every such spelling (symbol, framed string literal, keyword, alias, bare member). A
	 * name forged out of computed pieces is {@code LibraryDefunPruner}'s documented
	 * carve-out -- the ordinary undefined-function error, {@code --dynamic} to restore.
	 * The trigger stays syntactic rather than dataflow-shaped on purpose: a dataflow
	 * answer would have to prove no symbol out of {@code read} reaches a funcall, and
	 * being wrong is a trap rather than a diagnosis.
	 * @param program the program, after every AST pass
	 * @param usesRead whether the reader runtime is emitted
	 * @param usesLoad whether a runtime load survived the inliner
	 * @return true when the gate must keep every function dispatchable
	 */
	private static boolean anyNameResolvable(List<LispVal> program, boolean usesRead, boolean usesLoad) {
		// RuntimeNameProducers first, so the -Drontolisp.debug.dispatchgate report names
		// every operator holding the gate open rather than only the first one.
		boolean producer = RuntimeNameProducers.anyNameResolvable(program);
		if ((usesRead || usesLoad) && Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			System.err.println("[dispatch-gate] every function stays dispatchable because of: "
					+ (usesRead ? "read/read-from-string" : "load"));
		}
		return producer || usesRead || usesLoad;
	}

	private static boolean programUsesEval(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesEval(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesEval(LispVal val) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.EVAL.equals(sym.name())) {
			return true;
		}
		return usesEval(cons.car()) || usesEval(cons.cdr());
	}

	/**
	 * Pushes a standard stream variable's seeded default onto the {@code _start} body:
	 * the designator {@code t} (the interned {@code t} symbol) for the two stdio
	 * variables, an i31 stream handle for {@code *error-output*}. The two homes that need
	 * the value -- the variable's module global and the eval runtime's {@code GLOBAL_ENV}
	 * mirror -- both push it through here, so neither can drift from
	 * {@link StreamDesignators}' table.
	 */
	private static void emitStandardStreamDefault(WasmWriter w, LispVal value, Ctx ctx) {
		if (value instanceof LispInteger handle) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128((int) handle.value());
			w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			return;
		}
		if (value instanceof LispCons) {
			// *error-output*'s default is the stream VALUE over the reserved handle 2,
			// i.e. the (%obj-new '%STREAM 2 :standard) form StreamDesignators hands out.
			// ctx.writer IS this start-function writer here, so the ordinary expression
			// compiler builds it -- no second copy of the instance layout.
			//
			// With the instance gate OFF there is nothing to build it out of: a program
			// can be given a module global for a standard stream variable it never
			// NAMES (progv gives every special one), and with no %obj-is in it either
			// the raw handle is the same answer to everything it can ask.
			if (ctx.instanceTypeIndex < 0) {
				w.write(Instruction.I32_CONST);
				w.writeSignedLeb128((int) StreamDesignators.STANDARD_ERROR_HANDLE);
				w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
				return;
			}
			WasmExprCompiler.compileExpr(value, ctx);
			return;
		}
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(FUNC_T_SYM);
	}

	private static boolean programUsesSymbol(List<LispVal> program, String name) {
		for (LispVal expr : program) {
			if (usesSymbol(expr, name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * asyncMode: rewrites every top-level {@code (rontolisp:async-defun name (ll)
	 * body...)} into the equivalent plain {@code (defun name (ll) body...)} and records
	 * the name -- Pass 2a compiles those defuns as entry+resume state machines
	 * ({@code WasmAsyncEmit}) instead of plain bodies. A nested async-defun is rejected
	 * (define it at top level; a first-class async function value is
	 * {@code rontolisp:async-lambda}).
	 * @param program the resolved, flattened top-level forms
	 * @param asyncDefunNames receives the rewritten names
	 * @return the rewritten program
	 */
	private static List<LispVal> rewriteTopLevelAsyncDefuns(List<LispVal> program, Set<String> asyncDefunNames) {
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
					&& LispNames.ASYNC_DEFUN_QUALIFIED.equals(head.name())) {
				if (!(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispSymbol name)) {
					throw new UnsupportedOperationException(LispNames.ASYNC_DEFUN_QUALIFIED
							+ " expects (async-defun name (params) body...): " + form.print());
				}
				asyncDefunNames.add(name.name());
				out.add(new LispCons(new LispSymbol(LispNames.DEFUN), cons.cdr()));
			}
			else {
				out.add(form);
			}
		}
		return out;
	}

	// True when the program contains a form that flips the module into EH mode: a
	// catching/cleanup form, one of the with-* macros whose expansion rides
	// unwind-protect on WASM (todo-129 step 7), or the usocket guard/with-* family
	// (the guard sits in the spliced usocket.lisp defun bodies, so any
	// usocket-using program qualifies).
	private static boolean programUsesEhForm(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesEhForm(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesEhForm(LispVal val) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.HANDLER_CASE, LispNames.IGNORE_ERRORS, LispNames.UNWIND_PROTECT,
						LispNames.WITH_OPEN_FILE, LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING,
						LispNames.CATCH, LispNames.THROW,
						// progv's lowering rides unwind-protect for its restores
						// (LispMacroExpander.expandProgvForCompile), so it needs the EH
						// machinery -- and the `wasmtime -W exceptions=y` run flag --
						// exactly like a written-out unwind-protect.
						LispNames.PROGV -> {
					return true;
				}
				default -> {
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
					if (qn != null && LispNames.USOCKET_PKG.equals(qn.pkg())) {
						switch (qn.member()) {
							case LispNames.USOCKET_WITH_CLIENT_SOCKET, LispNames.USOCKET_WITH_CONNECTED_SOCKET,
									LispNames.USOCKET_WITH_SERVER_SOCKET, LispNames.USOCKET_WITH_SOCKET_LISTENER,
									LispNames.USOCKET_GUARD -> {
								return true;
							}
							default -> {
							}
						}
					}
				}
			}
		}
		return usesEhForm(cons.car()) || usesEhForm(cons.cdr());
	}

	// True when the program references any hash-table operator (including (setf (gethash
	// ...)) which contains gethash). Gates the first-class hash wrappers.
	private static boolean programUsesAnyHashOp(List<LispVal> program) {
		return programUsesSymbol(program, LispNames.MAKE_HASH_TABLE) || programUsesSymbol(program, LispNames.GETHASH)
				|| programUsesSymbol(program, LispNames.REMHASH) || programUsesSymbol(program, LispNames.CLRHASH)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_COUNT)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_P) || programUsesSymbol(program, LispNames.MAPHASH);
	}

	// True when the program references any array operator (or contains an array
	// literal), matching the JVM backend's predicate. Gates the first-class
	// fill-pointer wrappers so they are injected only for array-using programs.
	private static boolean programUsesAnyArrayOp(List<LispVal> program) {
		// make-string / (make-sequence 'string|'vector ...) lower to make-array during
		// compileExpr, after this scan runs, so they gate the wrapper group too -- see
		// the JVM twin for the full reasoning.
		return programUsesSymbol(program, LispNames.MAKE_ARRAY) || programUsesSymbol(program, LispNames.MAKE_STRING)
				|| programUsesSymbol(program, LispNames.MAKE_SEQUENCE) || programUsesSymbol(program, LispNames.AREF)
				|| programUsesSymbol(program, LispNames.ASET) || programUsesSymbol(program, LispNames.ARRAY_DIMENSIONS)
				|| programUsesSymbol(program, LispNames.VECTOR) || programUsesSymbol(program, LispNames.SVREF)
				|| programUsesSymbol(program, LispNames.ARRAY_RANK)
				|| programUsesSymbol(program, LispNames.ARRAY_DIMENSION)
				|| programUsesSymbol(program, LispNames.ARRAY_TOTAL_SIZE)
				|| programUsesSymbol(program, LispNames.ROW_MAJOR_AREF)
				|| programUsesSymbol(program, LispNames.ROW_MAJOR_ASET)
				|| programUsesSymbol(program, LispNames.ARRAY_ROW_MAJOR_INDEX)
				|| programUsesSymbol(program, LispNames.FILL_POINTER)
				|| programUsesSymbol(program, LispNames.SET_FILL_POINTER)
				|| programUsesSymbol(program, LispNames.ARRAY_HAS_FILL_POINTER_P)
				|| programUsesSymbol(program, LispNames.ADJUSTABLE_ARRAY_P)
				|| programUsesSymbol(program, LispNames.ARRAY_ELEMENT_TYPE)
				|| programUsesSymbol(program, LispNames.VECTOR_PUSH) || programUsesSymbol(program, LispNames.VECTOR_POP)
				|| programUsesSymbol(program, LispNames.VECTOR_PUSH_EXTEND)
				|| programUsesSymbol(program, LispNames.ADJUST_ARRAY)
				|| programUsesSymbol(program, LispNames.ARRAY_BECOME)
				|| programUsesSymbol(program, LispNames.ARRAY_DISPLACEMENT)
				|| programUsesSymbol(program, LispNames.ARRAY_DISP_TARGET)
				|| programUsesSymbol(program, LispNames.ARRAY_DISP_OFFSET)
				|| programUsesSymbol(program, LispNames.COERCE)
				// fill/read-sequence/write-sequence join the list for the same reason
				// as the JVM twin (LispMacroExpander.usesGeneralArrayOp): their
				// wrapper bodies have an array-typed arm, so the reference that would
				// otherwise leave the wrapper excluded is what has to turn this gate
				// on (BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS).
				|| programUsesSymbol(program, LispNames.FILL) || programUsesSymbol(program, LispNames.READ_SEQUENCE)
				|| programUsesSymbol(program, LispNames.WRITE_SEQUENCE) || programContainsArrayLiteral(program);
	}

	// True when a self-evaluating array literal (#(...)) appears anywhere in the
	// program.
	private static boolean programContainsArrayLiteral(List<LispVal> program) {
		for (LispVal expr : program) {
			if (containsArrayLiteral(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsArrayLiteral(LispVal val) {
		if (val instanceof am.ik.rontolisp.LispArray) {
			return true;
		}
		if (val instanceof LispCons cons) {
			return containsArrayLiteral(cons.car()) || containsArrayLiteral(cons.cdr());
		}
		return false;
	}

	private static boolean usesSymbol(LispVal val, String name) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && name.equals(sym.name())) {
			return true;
		}
		return usesSymbol(cons.car(), name) || usesSymbol(cons.cdr(), name);
	}

	/**
	 * Builds the compile-time intern table: one {@code (offset i32, length i32)} pair per
	 * interned string. The runtime {@code _intern} scans it to map a freshly-parsed
	 * symbol's bytes to the canonical string-table offset that the eval runtime compares
	 * against.
	 * @param entries the interned string entries
	 * @return the little-endian blob
	 */
	private static byte[] buildInternBlob(java.util.Collection<StringTable.StringEntry> entries) {
		ByteArrayOutputStream blob = new ByteArrayOutputStream();
		for (StringTable.StringEntry e : entries) {
			writeLittleEndian32(blob, e.offset());
			writeLittleEndian32(blob, e.length());
		}
		return blob.toByteArray();
	}

	private static void writeLittleEndian32(ByteArrayOutputStream target, int value) {
		target.write(value & 0xFF);
		target.write((value >>> 8) & 0xFF);
		target.write((value >>> 16) & 0xFF);
		target.write((value >>> 24) & 0xFF);
	}

	private static byte[] littleEndian32(int value) {
		return new byte[] { (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24) };
	}

	private static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(lambdaParts.get(1),
				lambdaParts.subList(2, lambdaParts.size()));
		return new DefunDecl(funcName, nf.paramNames(), nf.variadic(), nf.body());
	}

	/**
	 * A parsed defun. {@code paramNames} are the physical parameters (when
	 * {@code variadic}, the last one is the {@code &rest} parameter receiving the
	 * remaining arguments as a cons list).
	 */
	record DefunDecl(String name, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs) {
	}

	/**
	 * A planned host-callable export wrapper: the parsed directive plus the resolved
	 * target function index and the wrapper's own type and function indices.
	 *
	 * @param decl the parsed rontolisp:wasm-export directive
	 * @param targetFuncIndex the WASM function index of the exported defun
	 * @param typeIndex the wrapper's function type index
	 * @param funcIndex the wrapper's own function index
	 */
	record ExportPlan(WasmExportCompiler.Decl decl, int targetFuncIndex, int typeIndex, int funcIndex) {
	}

	/**
	 * Registry entry for a compiled function. {@code paramCount} is the physical WASM
	 * parameter count (excluding the closure env); when {@code variadic}, the last
	 * parameter is the rest list and the callable minimum is {@code paramCount - 1}
	 * arguments.
	 */
	record WasmFunctionInfo(String name, int paramCount, boolean variadic, int funcId, int typeIndex, int funcIndex) {
	}

	/**
	 * A function compiled in Pass 2c. {@code precompiled} is non-null for an async
	 * entry/resume half ({@code WasmAsyncEmit}), whose body was compiled out of line;
	 * Pass 2c then emits those bytes verbatim.
	 */
	record LambdaInfo(int funcId, String methodName, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs,
			List<String> freeVarNames, int funcIndex, byte @Nullable [] precompiled) {

		LambdaInfo(int funcId, String methodName, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs,
				List<String> freeVarNames, int funcIndex) {
			this(funcId, methodName, paramNames, variadic, bodyExprs, freeVarNames, funcIndex, null);
		}
	}

	/**
	 * Per-resume emission state of the {@code --component} async state machines: the
	 * resume function's own funcId (each suspension registers a
	 * {@code TYPE_CLOSURE{resumeFuncId, frame}} waiter) and the monotonically assigned
	 * suspend-state counter ({@code $rt} dispatch values; 0 = initial entry).
	 */
	static final class AsyncResume {

		final int resumeFuncId;

		int nextState = 1;

		/**
		 * Whether the implicit top-level resume's OUTERMOST body has already been
		 * chunk-outlined ({@code WasmAsyncEmit.compileTopLevelChunkedProgn}); nested
		 * bodies (a top-level {@code let}'s, whose statements may reference its locals)
		 * must never be outlined.
		 */
		boolean topLevelChunked;

		AsyncResume(int resumeFuncId) {
			this.resumeFuncId = resumeFuncId;
		}

	}

	/**
	 * An active unwind scope (EH mode): the protected region of an {@code unwind-protect}
	 * (cleanup forms) or a {@code handler-case} (the {@code %hc-depth-dec} form).
	 * {@code blockDepth} is the block-stack size when the scope was entered -- an exit
	 * escapes the scope when {@code blockDepth >=} the target block's 1-based depth at
	 * the exit site (the scope was entered inside the exit's target block).
	 * {@code trampolineDepth} is the {@code wasmCtrlDepth} marker of the scope's
	 * exit-trampoline block, lexically outside its try_table (so a throw from a cleanup
	 * cannot re-enter the scope's own handler); -1 when the scope has no trampoline (no
	 * enclosing plain-{@code return} boundary, so no {@code return} can escape it). A
	 * named {@code return-from} does not use the trampolines: it inlines the escaped
	 * cleanups at the exit site like {@code go} (see {@link WasmReturnFromCompiler}).
	 *
	 * @param cleanupForms the cleanup forms to run when a {@code return} exits the scope
	 * @param blockDepth the block-stack size at scope entry
	 * @param trampolineDepth the {@code wasmCtrlDepth} marker of the exit trampoline, or
	 * -1
	 */
	record UnwindScope(List<LispVal> cleanupForms, int blockDepth, int trampolineDepth) {
	}

	/**
	 * An active block return boundary during compilation ({@code %block}, a named
	 * {@code block} or the {@code %fn-block} function boundary): the {@code
	 * wasmCtrlDepth} at which the WASM block sits (an exit branches out {@code
	 * wasmCtrlDepth - depth} levels to reach it), the block name a {@code return-from}
	 * matches against ({@code null} for {@code %block} and the {@code nil} block),
	 * whether a plain {@code return} exits it ({@code %block} and {@code (block nil
	 * ...)}), and whether it is the {@code %fn-block} function boundary -- the fallback
	 * target for a {@code return-from} whose name matches no enclosing block.
	 *
	 * @param depth the {@code wasmCtrlDepth} marker of the block
	 * @param name the block name, or {@code null} for the unnamed/nil block
	 * @param catchesPlain whether a plain {@code return} exits this block
	 * @param functionBoundary whether this is the {@code %fn-block} function boundary
	 */
	record BlockMarker(int depth, @Nullable String name, boolean catchesPlain, boolean functionBoundary) {
	}

	/**
	 * The size of the {@code _start} GC-heap pre-grow allocation for this module.
	 * <p>
	 * A served component keeps the small per-instance constant. Everything else scales
	 * with how much code the program carries -- the live set a library stack leaves
	 * behind grows with it, and a copying collector that cannot clear the live set by a
	 * wide margin collects every few hundred KB. The result is clamped between
	 * {@link #GC_HEAP_PREGROW_BYTES} (a program with no libraries pre-grows exactly what
	 * it always did) and {@link #GC_HEAP_PREGROW_MAX_BYTES}.
	 * @param userFunctionBodies the emitted user function bodies so far (Pass 2a's
	 * output; entries reserved for import wrappers are still {@code null} and count as
	 * nothing)
	 * @return the allocation size in bytes
	 */
	/**
	 * {@return the minimum page count the module's linear memory declares} At least four
	 * (getenv places the environ buffer in page 3), and always the static data plus a
	 * bump heap at least as large as that data.
	 * <p>
	 * The headroom follows the program for the same reason the GC pre-grow does
	 * ({@code .kb/wasm-gc-heap-pregrow.md}): what the bump heap above {@code heapBase}
	 * holds is one identity per runtime-created string, so its need scales with how much
	 * a program builds at load time, not with a constant. Three fixed growth pages is
	 * ~192 KB, which cl-unicode -- 68,000 character names plus 11,172 computed Hangul
	 * ones -- exhausts before it finishes loading, trapping out of bounds with no
	 * diagnostic beyond the address. Measured there: 18 to 32 pages needed against the 76
	 * of static data this rule matches. A program with little static data keeps the old
	 * three, which is what leaves a memory-capped host (a Cloudflare Worker reactor) on
	 * the old floor rather than on a large constant.
	 * @param heapBase the first address above the static data and the intern region
	 */
	static int memoryMinPages(int heapBase) {
		int dataPages = (heapBase + 65535) / 65536;
		return Math.max(4, dataPages + Math.max(HEAP_HEADROOM_MIN_PAGES, dataPages));
	}

	private int gcHeapPregrowBytes(List<byte[]> userFunctionBodies) {
		long code = 0;
		for (byte[] body : userFunctionBodies) {
			if (body != null) {
				code += body.length;
			}
		}
		return gcHeapPregrowBytes(this.serve, code);
	}

	/**
	 * The pre-grow size for a module carrying {@code codeBytes} of user code -- the
	 * formula {@link #gcHeapPregrowBytes(List)} applies, exposed for the pinning test.
	 * @param serve whether this is a served component (per-instance, latency-bound)
	 * @param codeBytes the emitted user function bodies' total size
	 * @return the allocation size in bytes
	 */
	static int gcHeapPregrowBytes(boolean serve, long codeBytes) {
		if (serve) {
			return GC_HEAP_PREGROW_SERVE_BYTES;
		}
		long scaled = codeBytes * GC_HEAP_PREGROW_CODE_FACTOR;
		return (int) Math.max(GC_HEAP_PREGROW_BYTES, Math.min(GC_HEAP_PREGROW_MAX_BYTES, scaled));
	}

	/**
	 * Builds a function body's locals declaration and appends the body, resolving the i64
	 * scratch-local references the fusion compiler emitted as placeholders
	 * ({@link Ctx#writeI64LocalIndex}): the declaration is one {@code (ref null eq)} run
	 * for everything past {@code predeclaredSlots} followed by one {@code i64} run, so an
	 * i64 local's absolute index is {@code ctx.nextLocal + slot}. A body with no i64
	 * locals is byte-identical to the pre-stage-3 emission.
	 * <p>
	 * The placeholder is SPLICED OUT rather than overwritten in place, so a resolved
	 * reference costs the LEB length its index actually needs (one byte below 128)
	 * instead of the fixed placeholder width. That is what makes the emitted encoding
	 * minimal, and it is why the references must be ascending -- they are, being appended
	 * by one forward emission walk, and the loop says so rather than trusting it.
	 * @param ctx the function's compilation context after its body was emitted
	 * @param predeclaredSlots slots covered by the signature (params, closure env),
	 * excluded from the declared runs
	 * @param funcBody the emitted body instructions (including the terminating END)
	 * @return the complete code-section entry
	 */
	static byte[] buildLocalsAndPatch(Ctx ctx, int predeclaredSlots, ByteArrayOutputStream funcBody) {
		byte[] body = funcBody.toByteArray();
		int extraEq = ctx.nextLocal - predeclaredSlots;
		int numI64 = ctx.maxI64Locals;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter writer = new WasmWriter(out);
		writer.write((extraEq > 0 ? 1 : 0) + (numI64 > 0 ? 1 : 0));
		if (extraEq > 0) {
			writer.writeUnsignedLeb128(extraEq);
			writer.writeRefType(true, Type.EQ.code());
		}
		if (numI64 > 0) {
			writer.writeUnsignedLeb128(numI64);
			writer.write(Type.I64);
		}
		int cursor = 0;
		for (int[] ref : ctx.i64LocalRefs) {
			if (ref[0] < cursor) {
				throw new IllegalStateException(
						"i64 local placeholders out of order at " + ref[0] + " (cursor " + cursor + ")");
			}
			writer.write((Object) Arrays.copyOfRange(body, cursor, ref[0]));
			writer.writeUnsignedLeb128(ctx.nextLocal + ref[1]);
			cursor = ref[0] + Ctx.I64_LOCAL_PLACEHOLDER_WIDTH;
		}
		writer.write((Object) Arrays.copyOfRange(body, cursor, body.length));
		return out.toByteArray();
	}

	/**
	 * The compilation-wide quoted-datum global allocator (.kb/quoted-data.md): one module
	 * global -- a {@code (mut (ref null eq))} = null, appended after every fixed-index
	 * global so nothing renumbers -- per DISTINCT quoted aggregate datum, filled lazily
	 * by the quote site's first evaluation, so every evaluation of one site answers the
	 * SAME object: the CL-conformant constant reading, and the interpreter's. Keyed by
	 * datum IDENTITY, so a macro expansion splicing one template datum into several sites
	 * shares one constant across them, exactly like the interpreter's shared template
	 * datum. One instance is shared across every context of a compilation,
	 * {@link WasmAsyncEmit}'s fresh contexts included.
	 */
	static final class QuoteGlobals {

		private final int base;

		private final java.util.IdentityHashMap<LispVal, Integer> byDatum = new java.util.IdentityHashMap<>();

		QuoteGlobals(int base) {
			this.base = base;
		}

		/**
		 * The module global holding this datum, allocating the next index on first sight.
		 * @param datum the quoted datum (keyed by identity)
		 * @return the global index
		 */
		int indexFor(LispVal datum) {
			Integer existing = this.byDatum.get(datum);
			if (existing != null) {
				return existing;
			}
			int index = this.base + this.byDatum.size();
			this.byDatum.put(datum, index);
			return index;
		}

		/**
		 * How many quoted-datum globals the global section must append.
		 * @return the allocated count
		 */
		int count() {
			return this.byDatum.size();
		}

	}

	static final class Ctx {

		final WasmWriter writer;

		final ByteArrayOutputStream bodyStream;

		final StringTable stringTable;

		Map<String, Integer> locals = new HashMap<>();

		Map<String, WasmFunctionInfo> functions;

		/**
		 * Uniquely-defined fixed-arity defuns whose single body expression is a closed
		 * integer-operation tree over the parameters -- the fusion compiler substitutes
		 * their bodies at fused call sites (todo 194 stage 2). Empty under
		 * {@code --dynamic}.
		 */
		Map<String, DefunDecl> inlinableDefuns = Map.of();

		/**
		 * Let-bound local functions (the {@code __FLETn_f} lambdas flet lowers to) in
		 * scope whose bodies are closed integer-operation trees -- the fusion compiler
		 * substitutes them at {@code (funcall __FLETn_f ...)} sites (todo 194 stage 3).
		 * Scoped by {@link WasmLetCompiler} (registered for the binding's body, restored
		 * on exit); empty under {@code --dynamic}, like {@link #inlinableDefuns}.
		 */
		Map<String, WasmIntFusionCompiler.LocalIntLambda> localIntLambdas = Map.of();

		/**
		 * Unboxed (dual-representation) locals in scope: name to its (i64 slot, boxed
		 * shadow slot) pair -- see {@link WasmIntFusionCompiler.RawLocal}. Scoped by
		 * {@link WasmLetCompiler} exactly like {@link #locals}; a name in this map has NO
		 * entry in {@link #locals}.
		 */
		Map<String, WasmIntFusionCompiler.RawLocal> rawLocals = Map.of();

		/**
		 * The module global holding the raw-local sentinel (a private TYPE_CELL
		 * instance): a shadow slot ref.eq to it means "the raw i64 is authoritative". A
		 * null shadow cannot carry that meaning -- nil IS null, and a local holding nil
		 * must read as nil, not as the stale raw slot.
		 */
		int rawSentinelGlobalIndex = -1;

		/**
		 * Lexical variables in scope whose ARRAY representation a declaration (or an
		 * initializer this compile itself chose a representation for) pins down --
		 * consumed by the single-arm rank-1 {@code aref}/{@code %aset}/{@code length}
		 * emission ({@code WasmArrayCompiler.arrayKindOfExpr},
		 * {@code .kb/declarations-type-checks.md}). Scoped exactly like {@link #locals}:
		 * registered by the defun/lambda body setup and {@link WasmLetCompiler}, shadowed
		 * names removed, restored on scope exit.
		 */
		Map<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> declaredArrays = Map.of();

		/**
		 * Lexical variables in scope that provably hold an ARRAY -- the weaker fact
		 * {@link #declaredArrays} does not carry, for an initializer that pins the value
		 * down as an array without pinning its representation ({@code (make-array n
		 * :element-type '(unsigned-byte 8))} with a COMPUTED {@code n}: an integer
		 * {@code n} packs, a dimension LIST would not, so the kind is unknown but
		 * {@code %arrayp} is true either way). Read by
		 * {@code WasmArrayCompiler.provesArrayValue} to route a {@code replace} /
		 * {@code fill} site to the array-arm-only shared runtime; scoped exactly like
		 * {@link #declaredArrays}.
		 */
		Set<String> arrayLocals = Set.of();

		/**
		 * Defun names the program defines MORE than once. A {@code defstruct} accessor's
		 * slot {@code :type} is only trusted at a call site when the accessor's generated
		 * body is the one definition the call can reach; a user redefinition of the name
		 * lands here and turns that trust off.
		 */
		Set<String> duplicatedDefunNames = Set.of();

		Map<String, Integer> captures = Map.of();

		Set<String> boxedVars = Set.of();

		int closureEnvSlot = -1;

		List<LambdaInfo> lambdaDecls;

		Set<Integer> indirectCallArities;

		/**
		 * Whether Pass 2 dispatched a designator it could NOT read -- a
		 * {@code funcall}/{@code mapcar}/{@code sort}/{@code maphash}/... whose function
		 * argument is neither {@code #'name} nor {@code 'name} nor a literal
		 * {@code lambda} ({@link LispMacroExpander#isStaticFunctionDesignator}). Only
		 * such a site can be handed a SYMBOL at run time, and only the {@code _lookup}
		 * registry resolves one, so this is half of the registry gate in {@code compile}
		 * -- the other half being whether the program spells a name the registry could
		 * answer with. Recorded during emission rather than scanned off the source
		 * because the sequence operators reach their call through a MACRO expansion no
		 * pre-scan sees ({@code (every f l)} becomes a {@code do} loop over
		 * {@code (funcall #pred elem)}), which is why
		 * {@code LispMacroExpander.usesRuntimeFunctionDesignator} -- reading
		 * {@code funcall}/{@code apply} spellings only -- missed every one of them. One
		 * mutable holder shared by every {@code Ctx}, like {@link #valueFuncIds}.
		 */
		boolean[] runtimeDesignatorDispatch;

		/**
		 * Whether the body being emitted is INJECTED runtime rather than the user's
		 * program -- a {@code BuiltinFunctionWrappers} catalog entry or one of the shared
		 * sequence helpers. Every one of those bodies funcalls a designator PARAMETER, so
		 * they dispatch in every program ever compiled and would hold
		 * {@link #runtimeDesignatorDispatch} permanently true; a symbol can only reach
		 * one by being passed as an ARGUMENT to a first-class use of the built-in, which
		 * is the {@code funcall}/{@code apply} the source scan already reads.
		 */
		boolean injectedRuntimeBody = false;

		/**
		 * The funcIds of lambdas created while {@link #injectedRuntimeBody} was set --
		 * the comparator {@code stable-sort} builds, {@code complement}'s closure -- so
		 * Pass 2c can re-enter each of those bodies with the same answer. Their
		 * designator is the wrapper's parameter just as the wrapper body's is. One
		 * mutable set shared by every {@code Ctx}, like {@link #valueFuncIds}.
		 */
		Set<Integer> injectedRuntimeLambdas;

		/**
		 * The funcIds this program can reach as a first-class FUNCTION VALUE, recorded as
		 * Pass 2 emits them: every {@code (function name)} closure
		 * ({@link WasmFunctionFormCompiler#compileNamed}) and every {@code (lambda ...)}
		 * value ({@link WasmLambdaCompiler#emitClosureValue}). Shared by every
		 * {@code Ctx} (one mutable set, like {@link #indirectCallArities}) and read after
		 * the last body is emitted to decide which targets need a
		 * {@code buildDispatchBody} case -- see {@code dispatchableFuncIds} in
		 * {@code compile}. A funcId absent here is called only DIRECTLY, so the arity
		 * dispatchers have no reason to name it and {@code --optimize} can shake it out.
		 */
		Set<Integer> valueFuncIds;

		/**
		 * Every literal spelling Pass 2 emitted as a runtime VALUE the program can hold
		 * -- a quoted/self-evaluating symbol's name, a string literal's framed form, a
		 * keyword -- recorded where the value is built
		 * ({@link WasmEmitHelper#compileStringLiteral} and the few emitters that build a
		 * literal-derived value directly). Shared by every {@code Ctx} (one mutable set,
		 * like {@link #valueFuncIds}) and read after the last body is emitted by
		 * {@code dispatchableFuncIds}: a runtime symbol designator can only ever BE one
		 * of these (or a builder's product from one), so the name-registry probes read
		 * this set rather than the whole string table -- an entry the compiler interned
		 * for a private table (an instance-layout slot name, a printer piece, a registry
		 * row) is not a name the program spells, and must not arm a dispatch-ladder case.
		 */
		Set<String> spelledLiterals;

		/**
		 * The subset of {@link #spelledLiterals} the USER's program spells -- everything
		 * emitted while {@link #injectedRuntimeBody} was clear. The name-registry gate
		 * reads this rather than the full set: the wrapper catalog quotes {@code 'list},
		 * {@code 'cons}, {@code 'string} and friends as type designators inside its own
		 * bodies, which name wrapper defuns without any of them ever being a function
		 * designator, and reading the full set therefore armed the registry for every
		 * program ever compiled. {@code dispatchableFuncIds} still reads the full set:
		 * once the registry is live, a row is cheap and a spelling anywhere can reach it.
		 */
		Set<String> userSpelledLiterals;

		/**
		 * The {@code cl} function names this compile has already warned about, so an
		 * override that happens at fifty call sites reports once (one mutable set shared
		 * by every {@code Ctx}, like {@link #valueFuncIds}). See
		 * {@link am.ik.rontolisp.compiler.ClRedefinitionWarnings}.
		 */
		Set<String> warnedClRedefinitions = new HashSet<>();

		int[] nextFuncId;

		int nextLocal = 0;

		boolean dynamic = false;

		/**
		 * What this module is being optimized FOR. The emitters ask it exactly one
		 * question -- {@link OptimizeLevel#prefersSizeOverSpeed()} -- at the two places
		 * that deliberately spend bytes on speed: {@link WasmIntFusionCompiler}'s entry
		 * points (a fused site emits its tree twice) and {@link WasmLetCompiler}'s
		 * unboxed-local eligibility. Dead-code elimination is not decided here; it is a
		 * post-pass over the finished module.
		 */
		OptimizeLevel optimize = OptimizeLevel.DEFAULT;

		/**
		 * True when the wasi:*-binding library splices back this build's I/O primitives
		 * (http.lisp / wait.lisp / sockets.lisp / environment.lisp over their
		 * wit-imported wasi:* surfaces) -- i.e. {@code --component} WITHOUT
		 * {@code --no-wasi}. A {@code --component --no-wasi} reactor deliberately sets
		 * this false: its primitives keep the Preview 1 {@code --no-wasi} contract (print
		 * discards, the rest trap or signal), and only the final wrap differs.
		 */
		boolean component = false;

		/**
		 * True under {@code --no-wasi} (Preview 1 reactor or reactor component): the WASI
		 * import slots are internal stubs. Read by the reject sites whose "requires
		 * --component" messages would otherwise mislead a reactor build.
		 */
		boolean noWasi = false;

		/**
		 * True under {@code --component --no-wasi}: the zero-import REACTOR COMPONENT,
		 * which {@link #component} deliberately reports as false (it carries none of the
		 * wasi:*-binding splices). What distinguishes it here is its entry shape: its top
		 * level runs at INSTANTIATION, so the host hooks a core module offers -- and can
		 * be told to call before {@code _initialize} -- do not exist on it.
		 */
		boolean reactorComponent = false;

		/**
		 * True under {@code --no-wasi --host-random}: the {@code random_get} slot
		 * forwards to a host import instead of the module-local stub, so the bytes behind
		 * {@code rontolisp::%random-byte} really are the host's -- and the module-local
		 * generator {@code random} draws from can be seeded from them. Read by that one
		 * site, which must not stand in for a host, and by {@code WasmRandomCompiler},
		 * which emits seeding exactly when there IS a host to ask.
		 */
		boolean hostRandom = false;

		/**
		 * True under {@code --no-wasi --host-fetch}: {@code rontolisp:fetch} is the
		 * {@code HostFetchLibrary} splice over the injected {@code env.fetch} host
		 * import, so the fetch call site falls through to the ordinary defun path instead
		 * of the no-wasi rejection.
		 */
		boolean hostFetch = false;

		// True in a rontolisp:http-handler (serve-mode) component.
		boolean serve = false;

		/**
		 * True when the program uses {@code handler-case}/{@code ignore-errors}/
		 * {@code unwind-protect}: the module carries the {@code $lisp-cond} exception tag
		 * and {@code %error}/{@code %error-cond} throw it instead of trapping. Requires
		 * the host to enable the exception-handling proposal ({@code -W exceptions=y},
		 * wasmtime 37+).
		 */
		boolean ehMode = false;

		/**
		 * True when program code can HOLD a condition value
		 * ({@code LispMacroExpander.mayHoldConditions}; forced under restart mode and
		 * {@code --dynamic}). Off, a plain {@code %error}'s message operand is not
		 * compiled either -- the payload string's only reader is a handler clause that
		 * binds the synthesized condition, and none exists -- so signal-site message
		 * renders vanish from the artifact. {@code %error-cond}/{@code %signal-cond} skip
		 * their message unconditionally (the payload cdr of a non-nil instance is never
		 * read); this flag extends the skip to the instance-less throw.
		 */
		boolean condMessagesObservable = true;

		/**
		 * True when the program throws on the block-exit tag -- it lowers a cross-lambda
		 * {@code return-from}, or it uses {@code catch}/{@code throw}, which share the
		 * tag. The tag (index 1) is then emitted and {@code handler-case} is made
		 * block-exit aware (it lets such an unwind through, decrementing the handler
		 * depth). Gated so a program with neither stays byte-identical.
		 */
		boolean blockExitTag = false;

		/**
		 * True when the program uses the restart system
		 * ({@code LispMacroExpander.usesRestartSystem}): the error/warn/signal/cerror
		 * expansions gain the {@code %run-handlers} signal hook and the real
		 * {@code cerror}, matching the restart-runtime defuns
		 * {@code expandTopLevelDefinitions} injected. Off, every signal expansion is
		 * byte-identical to the pre-restart build.
		 */
		boolean restartMode = false;

		/**
		 * True when the program both signals and establishes a {@code handler-case}
		 * ({@code LispMacroExpander.needsSignalClauseMatch}): {@code handler-case} pushes
		 * its clause types on the dynamic {@code %handler-clusters%} stack and
		 * {@code %signal-cond} throws only when an armed clause MATCHES the condition
		 * (through the injected {@code %hc-match-p} defun), so a handler-case whose
		 * clauses do not match is declined and the signal falls through to nil (CLHS
		 * 9.1.4.1). Off, {@code %signal-cond} keeps the historical depth-counter emission
		 * and stays byte-identical.
		 */
		boolean signalClauseMatch = false;

		/**
		 * True when the program MENTIONS {@code *print-case*}
		 * ({@code LispMacroExpander.usesPrintCase}): every printing operator is rewritten
		 * onto the {@code %print-cased} renderer, which applies the variable to each
		 * symbol spelling. Off, the printing operators compile exactly as they always
		 * did.
		 */
		boolean printCase = false;

		/**
		 * True when the program can build a SYNONYM STREAM ({@code make-synonym-stream}
		 * is the only way to, and it has no read syntax), so every stream-designator
		 * resolution has to run through {@code %SYNONYM-TARGET}. A program that never
		 * spells it keeps its exact bytes.
		 */
		boolean usesSynonymStreams = false;

		/**
		 * True when the program writes {@code (make-hash-table :test 'equalp)} somewhere,
		 * so a table can carry the FOLD FLAG in its header count and the three table
		 * primitives run a key through {@code _equalp_key} before placing it. A program
		 * that writes none keeps its exact bytes: the count is the plain entry count and
		 * no site emits a fold.
		 */
		boolean usesEqualpHashTables = false;

		/**
		 * True when an OPEN stream VALUE ({@code LispLayout.STREAM}) can exist in this
		 * module -- the program spells a stream constructor, or names
		 * {@code *error-output*} whose seeded default is one
		 * ({@code LispMacroExpander.mayCreateStreamValues}). It gates BOTH halves of the
		 * representation: the instance wrap a producer emits and the
		 * {@code %STREAM-TARGET} unwrap a consumer emits, so the two can never disagree
		 * and a program the scan says no about keeps raw handles end to end.
		 */
		boolean usesStreamValues = false;

		/**
		 * True when the {@code %seq-string} helper is injected for this program, i.e. the
		 * program itself writes a {@code (concatenate 'string ...)} with an argument that
		 * is not a literal string. Only then does the string-family lowering normalize
		 * its arguments through it -- the {@code concatenate 'string} forms the macro
		 * expander produces during codegen already hold strings, so every other program
		 * stays byte-identical.
		 */
		boolean usesSeqString = false;

		/**
		 * The wasm global index of the handler-depth counter (a {@code (mut i32)} = 0
		 * appended after the user-variable globals), or -1 outside EH mode. The
		 * {@code handler-case} region increments/decrements it (the JVM
		 * {@code _hcDepthTl} parity) so {@code %signal-cond} raises only under an
		 * established handler.
		 */
		int ehDepthGlobalIndex = -1;

		/**
		 * True under {@code --simd}: the vectorizable {@code vec:} kernels are routed to
		 * the {@link WasmVecSimdRuntimeBuilder} v128 helpers at their call sites, and a
		 * packed float array's {@code TYPE_FARRAY} data field holds a {@code TYPE_VBLOCK}
		 * over an {@code (array (mut v128))} of lane groups rather than a
		 * {@code TYPE_F64ARR}/{@code TYPE_F32ARR}. One module only ever uses one
		 * representation, so every packed reader/writer branches on this flag at COMPILE
		 * time.
		 */
		boolean simd = false;

		/**
		 * {@link WasmLispCompiler#userFuncBase()} -- the index of the first user defun.
		 */
		int userFuncBase = FUNC_USER_BASE;

		/**
		 * {@link WasmLispCompiler#callArityCeiling()} -- the widest {@code funcall} this
		 * module dispatches per arity. A site past it compiles to a call-time signal.
		 */
		int callArityCeiling = MAX_CALLABLE_ARITY;

		/**
		 * The index of {@code _dispatch_11}, the first dispatcher past the fixed block.
		 * Only read for an arity above {@link WasmLispCompiler#MAX_CALLABLE_ARITY}, which
		 * {@link #callArityCeiling} already gates.
		 */
		int extraDispatchFuncBase = FUNC_USER_BASE;

		/**
		 * The number of emitted defun bodies -- the defuns LIST size, one module function
		 * per definition. NOT {@link #functions}{@code .size()}: that map holds one entry
		 * per NAME, so a redefined defun (fast-http redefines 11 struct readers) makes it
		 * smaller, and any function index reserved from it (a top-level chunk, a lambda,
		 * an async entry/resume) points below the real lambda region by the number of
		 * redefined entries.
		 */
		int numDefuns = 0;

		/**
		 * True for the single context that compiles top-level forms (the {@code _start}
		 * body), false for defun/lambda bodies. When {@link #usesEval} is also set, a
		 * top-level global variable binding is mirrored into the eval runtime's global
		 * environment ({@code GLOBAL_ENV}) so an eval'd expression can resolve it.
		 */
		boolean topLevel = false;

		/** True when the program uses the embedded {@code eval} runtime. */
		boolean usesEval = false;

		/**
		 * The one top-level form whose returned NAME the emitter is dropping, or
		 * {@code null}. Set by {@link WasmToplevelEmit} immediately before it compiles a
		 * {@code defvar}/{@code defparameter}/{@code defconstant} in statement position;
		 * {@link WasmDefvarCompiler} clears it when it takes the offer and emits no name
		 * (see {@code compiler/ToplevelStatements},
		 * {@code .kb/toplevel-statement-values.md}). Keyed by the cons IDENTITY, and
		 * cleared on acceptance, so the emitter can tell whether the offer was taken --
		 * an offer the dispatch did not route to the defvar compiler leaves a value on
		 * the stack and still gets its drop -- and so a nested definer compiled while
		 * this one's init expression is being emitted cannot take it.
		 */
		@Nullable LispVal definerNameDropped;

		Set<String> userDefunNames = Set.of();

		/**
		 * Whether the program calls {@code fmakunbound} anywhere. When it does, a LITERAL
		 * {@code (fboundp 'x)} may no longer be folded to a bare constant: the retired
		 * name must answer nil, so the fold is emitted behind a runtime tombstone probe
		 * of {@code GLOBAL_FENV} ({@link WasmSymbolApiCompiler#compileFboundp}).
		 */
		boolean usesFmakunbound = false;

		/**
		 * Whether the program uses {@code progv}. Switches {@code symbol-value} to the
		 * dynamic-first dispatch over the special set
		 * ({@code WasmSymbolApiCompiler.compileSymbolValue}).
		 */
		boolean usesProgv = false;

		/**
		 * The package designators the program's {@code defpackage}s and the built-in
		 * registry make resolvable, mapped to the canonical package name -- the table a
		 * COMPUTED {@code (find-package x)} is answered from, since the compiled runtime
		 * has no registry ({@link LispMacroExpander#expandRuntimeFindPackage}).
		 */
		Map<String, String> packageTable = Map.of();

		/**
		 * Every registered package mapped to the packages it uses -- the table
		 * {@code list-all-packages} / {@code package-use-list} /
		 * {@code package-used-by-list} are answered from, for the same reason
		 * {@link #packageTable} exists ({@link LispMacroExpander#expandPackageQuery}).
		 */
		Map<String, java.util.List<String>> packageUseTable = Map.of();

		/**
		 * {@code defstruct} accessor names to their 1-based slot position, collected by
		 * the pre-pass in {@link WasmLispCompiler#compile}; {@code setf} expansion treats
		 * these as places. Shared across every context.
		 */
		Map<String, Integer> structAccessors = Map.of();

		/**
		 * The CLOS registry (classes, generics, slot positions), collected by the
		 * pre-pass in {@link WasmLispCompiler#compile}; {@code make-instance}/
		 * {@code slot-value} expansion resolves through it. Shared across every context.
		 */
		ClosRegistry closRegistry = new ClosRegistry();

		/**
		 * Names of top-level global variables; each has a wasm global in
		 * {@link #globalIndices}.
		 */
		Set<String> globals = Set.of();

		/**
		 * Names of the program's NON-top-level {@code defun}s (a subset of
		 * {@link #globals}): each lowers to {@code (setq name (lambda ...))}, so the
		 * function value lives in the global variable and nowhere else. A call site and a
		 * {@code #'name} must therefore dispatch through the variable BEFORE the
		 * late-binding fallback, which under {@code --dynamic} resolves the runtime
		 * FUNCTION namespace -- where a nested defun never appears.
		 */
		Set<String> nestedDefunNames = Set.of();

		/**
		 * Names of special (dynamically bound) variables (a subset of {@link #globals}).
		 * A {@code let}/{@code let*} of one of these names saves its module-level wasm
		 * global, assigns the init value, and restores the global on normal exit -- a
		 * dynamic binding -- instead of allocating a fresh lexical local.
		 */
		Set<String> specialVars = Set.of();

		/**
		 * Maps a top-level global variable name to its module-level wasm global index.
		 */
		Map<String, Integer> globalIndices = Map.of();

		/**
		 * The quoted-datum global allocator ({@link QuoteGlobals}), shared across every
		 * context of a compilation so a quote site compiled anywhere -- a defun body, a
		 * lambda, a top-level chunk, an async resume body -- reaches the one table.
		 */
		QuoteGlobals quoteGlobals = new QuoteGlobals(0);

		/**
		 * Top-level globals already initialized by a defvar/defparameter in this
		 * compilation, for defvar's compile-time idempotence. Only the top-level context
		 * mutates it.
		 * <p>
		 * Shared with every context the top level is outlined into
		 * ({@link WasmToplevelEmit} chunks, {@link WasmAsyncEmit} resume chunks) -- see
		 * {@code WasmAsyncEmit.freshCtx}. Two chunks that each contain a {@code defvar}
		 * of the same name must not both emit an initializer.
		 */
		Set<String> definedGlobals = new HashSet<>();

		/**
		 * The number of currently-open WASM control structures (block/loop/if) that
		 * lexically enclose the form being compiled. Tracked by the {@code if}, {@code
		 * while} and {@code %block} compilers so that {@code return} can compute the
		 * relative {@code br} depth to the nearest enclosing block.
		 */
		int wasmCtrlDepth = 0;

		/**
		 * Stack of active block boundaries ({@code %block}/named {@code block}/
		 * {@code %fn-block}), innermost on top. {@code return} branches out
		 * {@code wasmCtrlDepth - marker.depth()} levels to reach the nearest
		 * plain-catching one; {@code return-from} resolves its name against the stack
		 * lexically.
		 */
		final Deque<BlockMarker> blockMarkers = new ArrayDeque<>();

		/**
		 * Active special-variable dynamic bindings, innermost on top:
		 * {@code {globalIndex, saveSlot, blockDepth}} per binding (see WasmLetCompiler).
		 * A {@code return}/{@code return-from} exiting a block entered before the binding
		 * restores the saved value on its way out.
		 */
		final Deque<int[]> specialBindScopes = new ArrayDeque<>();

		/**
		 * Stack of active {@code tagbody} label scopes, innermost on top. A {@code go}
		 * resolves its tag against these lexically -- the compilers do not support the
		 * interpreter's dynamic {@code go} across function boundaries.
		 */
		final Deque<WasmTagbodyCompiler.TagbodyScope> tagbodyScopes = new ArrayDeque<>();

		/**
		 * The {@code TYPE_FUTURE} type index, or -1 outside asyncMode.
		 */
		int futureTypeIndex = -1;

		/**
		 * The {@code TYPE_ASYNC_FRAME} type index, or -1 outside asyncMode.
		 */
		int frameTypeIndex = -1;

		/**
		 * The {@code TYPE_WASI_STREAM} type index, or -1 outside asyncMode.
		 */
		int wasiStreamTypeIndex = -1;

		/**
		 * The {@code TYPE_P1_STREAM} type index, or -1 when no stream value can exist in
		 * this module (asyncMode, or a program that never names {@code %stream-new}).
		 */
		int p1StreamTypeIndex = -1;

		/**
		 * The degenerate tier's stream runtime base function index
		 * ({@code _p1_stream_read}), or -1 when {@link #p1StreamTypeIndex} is.
		 */
		int p1StreamFuncBase = -1;

		/**
		 * The {@code TYPE_INSTANCE} type index, or -1 when the program uses no instance
		 * primitive.
		 */
		int instanceTypeIndex = -1;

		/**
		 * Instance tag to the absolute linear address of its baked layout record (see
		 * {@code WasmInstanceLayouts}); empty when the program uses no instance.
		 */
		Map<String, Integer> layoutAddresses = Map.of();

		/**
		 * The async runtime block's base function index ({@code _future_new}), or -1
		 * outside asyncMode.
		 */
		int asyncFuncBase = -1;

		/**
		 * Names of the top-level {@code rontolisp:async-defun}s (asyncMode): their defuns
		 * compile as entry+resume pairs, and an export wrapper targeting one polls the
		 * returned future.
		 */
		Set<String> asyncDefunNames = Set.of();

		/**
		 * Whether a {@code TYPE_P1_FUTURE} can EXIST in this module: outside asyncMode a
		 * future is the degenerate settled struct, and it has exactly two producers --
		 * the {@code %async-run} the async lowering leaves behind, and a
		 * {@code wasm-import ... :async t} wrapper. Read by the export wrappers, which
		 * resolve such a future at the boundary instead of unboxing it as the declared
		 * scalar; a module with neither producer gains no instruction.
		 */
		boolean p1Futures;

		/**
		 * The global index of the CURRENT task record (asyncMode), or -1. Frames store it
		 * as their owner at creation; the callback-task runtime swaps it at every host
		 * entry.
		 */
		int currentTaskGlobalIndex = -1;

		/**
		 * The global index of the serve init-once flag (a {@code mut i32}), or -1 outside
		 * serve mode. The handle wrapper runs the top level (_start) under it on the
		 * first request: a serve component's {@code run} is never lifted, so nothing else
		 * executes the program's top-level initializers.
		 */
		int serveInitGlobalIndex = -1;

		/**
		 * The global index of the re-entry guard flag (a {@code mut i32}), or -1 when the
		 * module cannot suspend. A suspending host import ({@code wasm-import :async t},
		 * or {@code --host-fetch}'s {@code env.fetch}) hands control back to the host's
		 * event loop mid-call, so an export can be RE-ENTERED while the first call is
		 * parked -- which the bump-allocator bracket and the shallowly-bound specials
		 * cannot survive. Every export wrapper sets the flag on entry and clears it on
		 * return; a second entry traps at the boundary instead of corrupting both calls.
		 */
		int reentryGuardGlobalIndex = -1;

		/**
		 * Whether this module is compiled {@code --reentrant}: the guard is retired, the
		 * dynamically-bound specials read/write/bind through the per-call task record
		 * ({@link WasmDynVars}), and cross-park linear staging goes through the
		 * park-block allocator.
		 */
		boolean reentrant;

		/**
		 * {@code --reentrant}: dynamically-bound special name to its slot in the per-call
		 * task record; empty otherwise.
		 */
		Map<String, Integer> dynSlots = Map.of();

		/**
		 * {@code --reentrant}: the global index of the CURRENT task record (a {@code mut
		 * (ref null eq)} holding a {@code TYPE_HASH_BUCKETS}), or -1 when no special is
		 * dynamically bound (or outside reentrant mode).
		 */
		int reentrantTaskGlobalIndex = -1;

		/**
		 * {@code --reentrant}: the {@code _park_alloc} function index, or -1 when the
		 * module has no memory-typed boundary.
		 */
		int parkAllocFuncIndex = -1;

		/** {@code --reentrant}: the {@code _park_free} function index, or -1. */
		int parkFreeFuncIndex = -1;

		/** {@code --reentrant}: the {@code _park_str_result} function index, or -1. */
		int parkStrResultFuncIndex = -1;

		/**
		 * Export names (beyond serve's {@code handle}) given the CALLBACK-lift treatment
		 * (the test hook); empty on every CLI path.
		 */
		Set<String> callbackExports = Set.of();

		/**
		 * Non-null while compiling an async resume body (asyncMode): the state-machine
		 * emission mode of the form compilers.
		 */
		@Nullable AsyncResume asyncResume;

		/**
		 * Transient marker that the NEXT {@code compileExpr} call compiles a spine child
		 * (empty operand stack, so an await there may suspend). Set by the async-aware
		 * form compilers, consumed at {@code compileExpr} entry.
		 */
		boolean asyncSpine;

		/**
		 * Whether the form currently being compiled sits at a spine position (the
		 * consumed {@link #asyncSpine} of the enclosing {@code compileExpr} call).
		 */
		boolean asyncSpineCurrent;

		/**
		 * Monotonic counter for the {@code %await$N} hoist bindings
		 * ({@code WasmAwaitNormalizer}); per body, purely for name uniqueness.
		 */
		int asyncHoistCounter;

		/**
		 * Stack of active unwind scopes (EH mode): one per {@code unwind-protect}
		 * protected region (cleanup forms) and per {@code handler-case} protected region
		 * (the {@code %hc-depth-dec} form), innermost on top. A {@code return} whose
		 * target {@code %block} lies outside the innermost scope branches to that scope's
		 * exit trampoline instead of the block (see {@code WasmReturnCompiler}); the
		 * trampolines cascade outward, running each escaped scope's cleanups in unwinding
		 * order.
		 */
		final Deque<UnwindScope> unwindScopes = new ArrayDeque<>();

		private Ctx(Builder builder) {
			this.writer = Objects.requireNonNull(builder.writer);
			this.bodyStream = Objects.requireNonNull(builder.bodyStream);
			this.stringTable = Objects.requireNonNull(builder.stringTable);
			this.functions = builder.functions;
			this.lambdaDecls = builder.lambdaDecls;
			this.indirectCallArities = builder.indirectCallArities;
			this.runtimeDesignatorDispatch = builder.runtimeDesignatorDispatch;
			this.injectedRuntimeBody = builder.injectedRuntimeBody;
			this.injectedRuntimeLambdas = builder.injectedRuntimeLambdas;
			this.valueFuncIds = builder.valueFuncIds;
			this.spelledLiterals = builder.spelledLiterals;
			this.userSpelledLiterals = builder.userSpelledLiterals;
			this.warnedClRedefinitions = builder.warnedClRedefinitions;
			this.nextFuncId = builder.nextFuncId;
			this.dynamic = builder.dynamic;
			this.optimize = builder.optimize;
			this.component = builder.component;
			this.noWasi = builder.noWasi;
			this.reactorComponent = builder.reactorComponent;
			this.hostRandom = builder.hostRandom;
			this.hostFetch = builder.hostFetch;
			this.serve = builder.serve;
			this.ehMode = builder.ehMode;
			this.condMessagesObservable = builder.condMessagesObservable;
			this.blockExitTag = builder.blockExitTag;
			this.restartMode = builder.restartMode;
			this.signalClauseMatch = builder.signalClauseMatch;
			this.printCase = builder.printCase;
			this.usesSynonymStreams = builder.usesSynonymStreams;
			this.usesEqualpHashTables = builder.usesEqualpHashTables;
			this.usesStreamValues = builder.usesStreamValues;
			this.usesSeqString = builder.usesSeqString;
			this.ehDepthGlobalIndex = builder.ehDepthGlobalIndex;
			this.rawSentinelGlobalIndex = builder.rawSentinelGlobalIndex;
			this.simd = builder.simd;
			this.userFuncBase = builder.userFuncBase;
			this.callArityCeiling = builder.callArityCeiling;
			this.extraDispatchFuncBase = builder.extraDispatchFuncBase;
			this.numDefuns = builder.numDefuns;
			this.userDefunNames = builder.userDefunNames;
			this.usesFmakunbound = builder.usesFmakunbound;
			this.usesProgv = builder.usesProgv;
			this.usesEval = builder.usesEval;
			this.packageTable = builder.packageTable;
			this.packageUseTable = builder.packageUseTable;
			this.structAccessors = builder.structAccessors;
			this.closRegistry = builder.closRegistry;
			this.globals = builder.globals;
			this.nestedDefunNames = builder.nestedDefunNames;
			this.specialVars = builder.specialVars;
			this.globalIndices = builder.globalIndices;
			this.quoteGlobals = builder.quoteGlobals;
			this.futureTypeIndex = builder.futureTypeIndex;
			this.frameTypeIndex = builder.frameTypeIndex;
			this.wasiStreamTypeIndex = builder.wasiStreamTypeIndex;
			this.p1StreamTypeIndex = builder.p1StreamTypeIndex;
			this.p1StreamFuncBase = builder.p1StreamFuncBase;
			this.instanceTypeIndex = builder.instanceTypeIndex;
			this.layoutAddresses = builder.layoutAddresses;
			this.asyncFuncBase = builder.asyncFuncBase;
			this.asyncDefunNames = builder.asyncDefunNames;
			this.p1Futures = builder.p1Futures;
			this.currentTaskGlobalIndex = builder.currentTaskGlobalIndex;
			this.serveInitGlobalIndex = builder.serveInitGlobalIndex;
			this.reentryGuardGlobalIndex = builder.reentryGuardGlobalIndex;
			this.reentrant = builder.reentrant;
			this.dynSlots = builder.dynSlots;
			this.reentrantTaskGlobalIndex = builder.reentrantTaskGlobalIndex;
			this.parkAllocFuncIndex = builder.parkAllocFuncIndex;
			this.parkFreeFuncIndex = builder.parkFreeFuncIndex;
			this.parkStrResultFuncIndex = builder.parkStrResultFuncIndex;
			this.callbackExports = builder.callbackExports;
			this.inlinableDefuns = builder.inlinableDefuns;
			this.duplicatedDefunNames = builder.duplicatedDefunNames;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private @Nullable WasmWriter writer;

			private @Nullable ByteArrayOutputStream bodyStream;

			private @Nullable StringTable stringTable;

			private Map<String, WasmFunctionInfo> functions = Map.of();

			private Map<String, DefunDecl> inlinableDefuns = Map.of();

			private Set<String> duplicatedDefunNames = Set.of();

			private List<LambdaInfo> lambdaDecls = new ArrayList<>();

			private Set<Integer> indirectCallArities = new HashSet<>();

			private boolean[] runtimeDesignatorDispatch = new boolean[1];

			private boolean injectedRuntimeBody = false;

			private Set<Integer> injectedRuntimeLambdas = new HashSet<>();

			private Set<Integer> valueFuncIds = new HashSet<>();

			private Set<String> spelledLiterals = new HashSet<>();

			private Set<String> userSpelledLiterals = new HashSet<>();

			private Set<String> warnedClRedefinitions = new HashSet<>();

			private int[] nextFuncId = new int[1];

			private boolean dynamic = false;

			private OptimizeLevel optimize = OptimizeLevel.DEFAULT;

			private boolean component = false;

			private boolean noWasi = false;

			private boolean reactorComponent = false;

			private boolean hostRandom = false;

			private boolean hostFetch = false;

			private boolean serve = false;

			private boolean ehMode = false;

			private boolean condMessagesObservable = true;

			private boolean blockExitTag = false;

			private boolean restartMode = false;

			private boolean signalClauseMatch = false;

			private boolean printCase = false;

			private boolean usesSynonymStreams = false;

			private boolean usesEqualpHashTables = false;

			private boolean usesStreamValues = false;

			private boolean usesSeqString = false;

			private int ehDepthGlobalIndex = -1;

			private int rawSentinelGlobalIndex = -1;

			private boolean simd = false;

			private int userFuncBase = FUNC_USER_BASE;

			private int callArityCeiling = MAX_CALLABLE_ARITY;

			private int extraDispatchFuncBase = FUNC_USER_BASE;

			private int numDefuns = 0;

			private Set<String> userDefunNames = Set.of();

			private boolean usesFmakunbound = false;

			private boolean usesProgv = false;

			private boolean usesEval = false;

			private Map<String, String> packageTable = Map.of();

			private Map<String, java.util.List<String>> packageUseTable = Map.of();

			private Map<String, Integer> structAccessors = Map.of();

			private ClosRegistry closRegistry = new ClosRegistry();

			private Set<String> globals = Set.of();

			private Set<String> nestedDefunNames = Set.of();

			private Set<String> specialVars = Set.of();

			private Map<String, Integer> globalIndices = Map.of();

			private QuoteGlobals quoteGlobals = new QuoteGlobals(0);

			private int futureTypeIndex = -1;

			private int frameTypeIndex = -1;

			private int wasiStreamTypeIndex = -1;

			private int p1StreamTypeIndex = -1;

			private int p1StreamFuncBase = -1;

			private int instanceTypeIndex = -1;

			private Map<String, Integer> layoutAddresses = Map.of();

			private int asyncFuncBase = -1;

			private Set<String> asyncDefunNames = Set.of();

			private boolean p1Futures;

			private int currentTaskGlobalIndex = -1;

			private int serveInitGlobalIndex = -1;

			private int reentryGuardGlobalIndex = -1;

			private boolean reentrant;

			private Map<String, Integer> dynSlots = Map.of();

			private int reentrantTaskGlobalIndex = -1;

			private int parkAllocFuncIndex = -1;

			private int parkFreeFuncIndex = -1;

			private int parkStrResultFuncIndex = -1;

			private Set<String> callbackExports = Set.of();

			Builder writer(WasmWriter writer) {
				this.writer = writer;
				return this;
			}

			Builder bodyStream(ByteArrayOutputStream bodyStream) {
				this.bodyStream = bodyStream;
				return this;
			}

			Builder stringTable(StringTable stringTable) {
				this.stringTable = stringTable;
				return this;
			}

			Builder functions(Map<String, WasmFunctionInfo> functions) {
				this.functions = functions;
				return this;
			}

			Builder inlinableDefuns(Map<String, DefunDecl> inlinableDefuns) {
				this.inlinableDefuns = inlinableDefuns;
				return this;
			}

			Builder duplicatedDefunNames(Set<String> duplicatedDefunNames) {
				this.duplicatedDefunNames = duplicatedDefunNames;
				return this;
			}

			Builder lambdaDecls(List<LambdaInfo> lambdaDecls) {
				this.lambdaDecls = lambdaDecls;
				return this;
			}

			Builder indirectCallArities(Set<Integer> indirectCallArities) {
				this.indirectCallArities = indirectCallArities;
				return this;
			}

			Builder runtimeDesignatorDispatch(boolean[] runtimeDesignatorDispatch) {
				this.runtimeDesignatorDispatch = runtimeDesignatorDispatch;
				return this;
			}

			Builder injectedRuntimeBody(boolean injectedRuntimeBody) {
				this.injectedRuntimeBody = injectedRuntimeBody;
				return this;
			}

			Builder injectedRuntimeLambdas(Set<Integer> injectedRuntimeLambdas) {
				this.injectedRuntimeLambdas = injectedRuntimeLambdas;
				return this;
			}

			Builder valueFuncIds(Set<Integer> valueFuncIds) {
				this.valueFuncIds = valueFuncIds;
				return this;
			}

			Builder spelledLiterals(Set<String> spelledLiterals) {
				this.spelledLiterals = spelledLiterals;
				return this;
			}

			Builder userSpelledLiterals(Set<String> userSpelledLiterals) {
				this.userSpelledLiterals = userSpelledLiterals;
				return this;
			}

			Builder warnedClRedefinitions(Set<String> warnedClRedefinitions) {
				this.warnedClRedefinitions = warnedClRedefinitions;
				return this;
			}

			Builder nextFuncId(int[] nextFuncId) {
				this.nextFuncId = nextFuncId;
				return this;
			}

			Builder dynamic(boolean dynamic) {
				this.dynamic = dynamic;
				return this;
			}

			Builder optimize(OptimizeLevel optimize) {
				this.optimize = optimize;
				return this;
			}

			Builder component(boolean component) {
				this.component = component;
				return this;
			}

			Builder noWasi(boolean noWasi) {
				this.noWasi = noWasi;
				return this;
			}

			Builder reactorComponent(boolean reactorComponent) {
				this.reactorComponent = reactorComponent;
				return this;
			}

			Builder hostRandom(boolean hostRandom) {
				this.hostRandom = hostRandom;
				return this;
			}

			Builder hostFetch(boolean hostFetch) {
				this.hostFetch = hostFetch;
				return this;
			}

			Builder serve(boolean serve) {
				this.serve = serve;
				return this;
			}

			Builder ehMode(boolean ehMode) {
				this.ehMode = ehMode;
				return this;
			}

			Builder condMessagesObservable(boolean condMessagesObservable) {
				this.condMessagesObservable = condMessagesObservable;
				return this;
			}

			Builder blockExitTag(boolean blockExitTag) {
				this.blockExitTag = blockExitTag;
				return this;
			}

			Builder usesSeqString(boolean usesSeqString) {
				this.usesSeqString = usesSeqString;
				return this;
			}

			Builder restartMode(boolean restartMode) {
				this.restartMode = restartMode;
				return this;
			}

			Builder signalClauseMatch(boolean signalClauseMatch) {
				this.signalClauseMatch = signalClauseMatch;
				return this;
			}

			Builder printCase(boolean printCase) {
				this.printCase = printCase;
				return this;
			}

			Builder usesStreamValues(boolean usesStreamValues) {
				this.usesStreamValues = usesStreamValues;
				return this;
			}

			Builder usesSynonymStreams(boolean usesSynonymStreams) {
				this.usesSynonymStreams = usesSynonymStreams;
				return this;
			}

			Builder usesEqualpHashTables(boolean usesEqualpHashTables) {
				this.usesEqualpHashTables = usesEqualpHashTables;
				return this;
			}

			Builder rawSentinelGlobalIndex(int rawSentinelGlobalIndex) {
				this.rawSentinelGlobalIndex = rawSentinelGlobalIndex;
				return this;
			}

			Builder ehDepthGlobalIndex(int ehDepthGlobalIndex) {
				this.ehDepthGlobalIndex = ehDepthGlobalIndex;
				return this;
			}

			Builder simd(boolean simd) {
				this.simd = simd;
				return this;
			}

			Builder userFuncBase(int userFuncBase) {
				this.userFuncBase = userFuncBase;
				return this;
			}

			Builder callArityCeiling(int callArityCeiling) {
				this.callArityCeiling = callArityCeiling;
				return this;
			}

			Builder extraDispatchFuncBase(int extraDispatchFuncBase) {
				this.extraDispatchFuncBase = extraDispatchFuncBase;
				return this;
			}

			Builder numDefuns(int numDefuns) {
				this.numDefuns = numDefuns;
				return this;
			}

			Builder userDefunNames(Set<String> userDefunNames) {
				this.userDefunNames = userDefunNames;
				return this;
			}

			Builder usesProgv(boolean usesProgv) {
				this.usesProgv = usesProgv;
				return this;
			}

			Builder usesEval(boolean usesEval) {
				this.usesEval = usesEval;
				return this;
			}

			Builder usesFmakunbound(boolean usesFmakunbound) {
				this.usesFmakunbound = usesFmakunbound;
				return this;
			}

			Builder packageTable(Map<String, String> packageTable) {
				this.packageTable = packageTable;
				return this;
			}

			Builder packageUseTable(Map<String, java.util.List<String>> packageUseTable) {
				this.packageUseTable = packageUseTable;
				return this;
			}

			Builder structAccessors(Map<String, Integer> structAccessors) {
				this.structAccessors = structAccessors;
				return this;
			}

			Builder closRegistry(ClosRegistry closRegistry) {
				this.closRegistry = closRegistry;
				return this;
			}

			Builder globals(Set<String> globals) {
				this.globals = globals;
				return this;
			}

			Builder nestedDefunNames(Set<String> nestedDefunNames) {
				this.nestedDefunNames = nestedDefunNames;
				return this;
			}

			Builder specialVars(Set<String> specialVars) {
				this.specialVars = specialVars;
				return this;
			}

			Builder globalIndices(Map<String, Integer> globalIndices) {
				this.globalIndices = globalIndices;
				return this;
			}

			Builder quoteGlobals(QuoteGlobals quoteGlobals) {
				this.quoteGlobals = quoteGlobals;
				return this;
			}

			Builder futureTypeIndex(int futureTypeIndex) {
				this.futureTypeIndex = futureTypeIndex;
				return this;
			}

			Builder frameTypeIndex(int frameTypeIndex) {
				this.frameTypeIndex = frameTypeIndex;
				return this;
			}

			Builder wasiStreamTypeIndex(int wasiStreamTypeIndex) {
				this.wasiStreamTypeIndex = wasiStreamTypeIndex;
				return this;
			}

			Builder instanceTypeIndex(int instanceTypeIndex) {
				this.instanceTypeIndex = instanceTypeIndex;
				return this;
			}

			Builder layoutAddresses(Map<String, Integer> layoutAddresses) {
				this.layoutAddresses = layoutAddresses;
				return this;
			}

			Builder asyncFuncBase(int asyncFuncBase) {
				this.asyncFuncBase = asyncFuncBase;
				return this;
			}

			Builder asyncDefunNames(Set<String> asyncDefunNames) {
				this.asyncDefunNames = asyncDefunNames;
				return this;
			}

			Builder p1StreamTypeIndex(int p1StreamTypeIndex) {
				this.p1StreamTypeIndex = p1StreamTypeIndex;
				return this;
			}

			Builder p1StreamFuncBase(int p1StreamFuncBase) {
				this.p1StreamFuncBase = p1StreamFuncBase;
				return this;
			}

			Builder p1Futures(boolean p1Futures) {
				this.p1Futures = p1Futures;
				return this;
			}

			Builder currentTaskGlobalIndex(int currentTaskGlobalIndex) {
				this.currentTaskGlobalIndex = currentTaskGlobalIndex;
				return this;
			}

			Builder serveInitGlobalIndex(int serveInitGlobalIndex) {
				this.serveInitGlobalIndex = serveInitGlobalIndex;
				return this;
			}

			Builder reentryGuardGlobalIndex(int reentryGuardGlobalIndex) {
				this.reentryGuardGlobalIndex = reentryGuardGlobalIndex;
				return this;
			}

			Builder reentrant(boolean reentrant) {
				this.reentrant = reentrant;
				return this;
			}

			Builder dynSlots(Map<String, Integer> dynSlots) {
				this.dynSlots = dynSlots;
				return this;
			}

			Builder reentrantTaskGlobalIndex(int reentrantTaskGlobalIndex) {
				this.reentrantTaskGlobalIndex = reentrantTaskGlobalIndex;
				return this;
			}

			Builder parkAllocFuncIndex(int parkAllocFuncIndex) {
				this.parkAllocFuncIndex = parkAllocFuncIndex;
				return this;
			}

			Builder parkFreeFuncIndex(int parkFreeFuncIndex) {
				this.parkFreeFuncIndex = parkFreeFuncIndex;
				return this;
			}

			Builder parkStrResultFuncIndex(int parkStrResultFuncIndex) {
				this.parkStrResultFuncIndex = parkStrResultFuncIndex;
				return this;
			}

			Builder callbackExports(Set<String> callbackExports) {
				this.callbackExports = callbackExports;
				return this;
			}

			Ctx build() {
				return new Ctx(this);
			}

		}

		int allocLocal(String name) {
			int slot = this.nextLocal++;
			this.locals.put(name, slot);
			return slot;
		}

		int allocTemp() {
			return this.nextLocal++;
		}

		/**
		 * Count of i64 scratch locals live in the CURRENT fused site (todo 194 stage 3:
		 * the fusion compiler unboxes each expression leaf ONCE into an i64 local and
		 * re-reads it, instead of re-running the guard at every occurrence). Sites
		 * save/restore this watermark so slots are reused across sites;
		 * {@link #maxI64Locals} keeps the high-water mark the declaration needs.
		 */
		int nextI64Local = 0;

		int maxI64Locals = 0;

		/**
		 * The current fused site's shared i64 scratch slot for the inline literal-operand
		 * add/sub overflow check (WasmIntFusionCompiler), allocated lazily on first use;
		 * {@code -1} = none allocated yet. Each site emission resets it after its leaves
		 * are evaluated (a nested site inside a leaf has its own), and the site's
		 * watermark restore releases the slot with the rest.
		 */
		int fxLitTempSlot = -1;

		/** The byte width of one {@link #writeI64LocalIndex} placeholder. */
		static final int I64_LOCAL_PLACEHOLDER_WIDTH = 3;

		/**
		 * Patch sites for i64 local references: {bodyStream offset, site-relative slot}.
		 * An i64 local's absolute index is {@code nextLocal + slot}, unknown until the
		 * function body is complete (every eqref local precedes the i64 run), so
		 * references are emitted as fixed-width placeholders and replaced by
		 * {@link WasmLispCompiler#buildLocalsAndPatch}, which splices in the minimal LEB
		 * for the resolved index.
		 */
		List<int[]> i64LocalRefs = new ArrayList<>();

		int allocI64Temp() {
			int slot = this.nextI64Local++;
			this.maxI64Locals = Math.max(this.maxI64Locals, this.nextI64Local);
			return slot;
		}

		/**
		 * Writes a reference to i64 scratch local {@code slot} as a fixed-width
		 * placeholder (three bytes, so it never has to be widened) and records it for
		 * resolution. The placeholder never reaches the output: it is spliced out for the
		 * minimal LEB of the resolved index.
		 */
		void writeI64LocalIndex(int slot) {
			this.i64LocalRefs.add(new int[] { this.bodyStream.size(), slot });
			this.writer.write(0x80, 0x80, 0x00);
		}

	}

	/**
	 * The {@code %class-}/{@code %struct-} layout tags the program can reach, or null
	 * when the set is unknowable and every layout must ship. A layout is reachable only
	 * through a name: an {@code %obj-new}/{@code %obj-is} tag (spelled with its prefix),
	 * or a class/struct designator a later expression expansion turns into one
	 * ({@code (make-instance 'c)}, {@code (error 'c ...)} -- spelled bare). Both
	 * spellings are symbols in the post-definitions program, so keeping every layout
	 * whose tag or bare name occurs as ANY symbol (plus the simple-* tags the
	 * handler-case/signal lowering synthesizes AFTER this scan, inside Pass 2) is a sound
	 * over-approximation. Unknowable when the program embeds the eval runtime or restart
	 * mode (evaluated/signal-hook code can reach any registered class), under
	 * {@code --dynamic}, or when it enumerates subclasses / resolves classes by computed
	 * name -- the same openings the pruner's class gates bail on.
	 */
	private java.util.@Nullable Set<String> usedLayoutTags(List<LispVal> program, ClosRegistry closRegistry,
			boolean open) {
		if (open || this.dynamic) {
			return null;
		}
		java.util.Set<String> symbols = new java.util.HashSet<>();
		for (LispVal form : program) {
			collectSymbolNames(form, symbols);
		}
		for (String bail : LAYOUT_TAG_BAILS) {
			if (symbols.contains(bail)) {
				return null;
			}
		}
		java.util.Set<String> used = new java.util.HashSet<>();
		used.add(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-ERROR");
		used.add(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-CONDITION");
		used.add(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-WARNING");
		// The unbound-slot marker's tag is %class-prefixed but is runtime plumbing.
		used.add(ClosRegistry.UNBOUND_TAG);
		// A read whose end of file SIGNALS constructs the end-of-file condition during
		// the EXPRESSION expansion (expandReadEofSignal) -- after this scan -- so the
		// operators' presence stands in for the tag.
		for (String reader : List.of(LispNames.READ_CHAR, LispNames.READ_BYTE, LispNames.READ_LINE,
				LispNames.PEEK_CHAR_INTERNAL, LispNames.READ_CHAR_RAW_INTERNAL, LispNames.READ_BYTE_RAW_INTERNAL,
				LispNames.READ_LINE_RAW_INTERNAL)) {
			if (symbols.contains(reader)) {
				used.add(LispLayout.CLASS_TAG_PREFIX + "END-OF-FILE");
				break;
			}
		}
		for (String tag : closRegistry.layouts().keySet()) {
			String bare = tag.startsWith(LispLayout.CLASS_TAG_PREFIX)
					? tag.substring(LispLayout.CLASS_TAG_PREFIX.length()) : tag.startsWith(LispLayout.STRUCT_TAG_PREFIX)
							? tag.substring(LispLayout.STRUCT_TAG_PREFIX.length()) : null;
			if (bare == null || symbols.contains(tag) || symbols.contains(bare)
					|| symbols.contains(LispSymbol.memberName(bare))) {
				used.add(tag);
			}
		}
		return used;
	}

	/**
	 * Member names whose presence makes the reachable-layout set unknowable: subclass
	 * enumeration reaches classes no name spells, and a class resolved from a computed
	 * name can be any registered one.
	 */
	private static final java.util.Set<String> LAYOUT_TAG_BAILS = java.util.Set.of("CLASS-DIRECT-SUBCLASSES",
			"%CLASS-DIRECT-SUBCLASSES", "FIND-CLASS", "CHANGE-CLASS", "ALLOCATE-INSTANCE", "SYMBOL-FUNCTION",
			"FDEFINITION");

	private static void collectSymbolNames(LispVal form, java.util.Set<String> out) {
		switch (form) {
			case LispSymbol sym -> {
				out.add(sym.name());
				String member = LispSymbol.memberName(sym.name());
				if (!member.equals(sym.name())) {
					out.add(member);
				}
			}
			case LispCons cons -> {
				collectSymbolNames(cons.car(), out);
				collectSymbolNames(cons.cdr(), out);
			}
			case LispArray array -> {
				for (LispVal element : array.data()) {
					collectSymbolNames(element, out);
				}
			}
			case am.ik.rontolisp.LispStructLiteral literal -> {
				out.add(literal.typeName());
				for (LispVal value : literal.slotValues()) {
					collectSymbolNames(value, out);
				}
			}
			default -> {
			}
		}
	}

	static final class StringTable {

		private final ByteArrayOutputStream data = new ByteArrayOutputStream();

		private final Map<String, StringEntry> cache = new HashMap<>();

		/**
		 * The entries a dead function body may take with it: the ones interned ONLY while
		 * {@link #attributing} was on, i.e. while a user function / top level / lambda
		 * body was being emitted (or the printer prologue below, whose every reader is a
		 * runtime body), where the only consumer of the offset is a body's own
		 * {@code i32.const}. Every other {@code addString} caller bakes the offset
		 * somewhere the tree shaker cannot see it -- a word inside the {@code _lookup}
		 * registry blob, the instance-layout blob, the reader tables -- so touching an
		 * entry outside the window pins it for good. See
		 * {@code .kb/optimize-dead-code-elimination.md}.
		 */
		private final Set<String> shakeable = new HashSet<>();

		/**
		 * {@code {absolute offset, length}} of every {@link #appendShakeableBlob} blob,
		 * in append (i.e. address) order so the emitted range list is deterministic.
		 */
		private final List<int[]> shakeableBlobs = new ArrayList<>();

		private boolean attributing;

		private int nextOffset;

		final StringEntry nil;

		final StringEntry lparen;

		final StringEntry rparen;

		final StringEntry space;

		final StringEntry dot;

		final StringEntry newline;

		final StringEntry funcStr;

		final StringEntry futureStr;

		// A hash table is the OTHER shape of the TYPE_CELL box the array printer walks
		// (see WasmRuntimeBuilder.emitPrintArray): it prints as this unreadable tag with
		// the header's entry count between the two halves, the same text
		// LispHashTable.print() answers on the interpreter.
		final StringEntry hashTableStr;

		// The same tag for a table whose keys are FOLDED, interned only by a module that
		// can build one -- every other module prints the constant EQUAL tag it always
		// did, and carries these bytes no more than it carries the fold.
		final @Nullable StringEntry hashTableEqualpStr;

		final StringEntry hashTableEnd;

		// Vector/array literal printing: the "#(" prefix for rank-1; a rank-n array
		// prints "#", the rank as an integer, then "A(". A packed float array
		// (TYPE_FARRAY) prints "#d(" (double) or "#f(" (single) at every rank instead, so
		// its printed form round-trips to a packed array of the same width -- the printer
		// picks fPrefix / sfPrefix by ref.test-ing the data array's width.
		final StringEntry vecPrefix;

		final StringEntry hashPrefix;

		final StringEntry rankAOpen;

		final StringEntry fPrefix;

		final StringEntry sfPrefix;

		final StringEntry minus;

		final StringEntry period;

		final StringEntry slash;

		// Float printing: the IEEE specials and the exponent marker (todo-108 group C).
		final StringEntry nanStr;

		final StringEntry infinityStr;

		final StringEntry expE;

		// Character printing: the "#\" prefix and the standard names for the non-graphic
		// characters that prin1 spells out (see WasmRuntimeBuilder.emitPrintChar).
		final StringEntry charPrefix;

		final StringEntry charSpace;

		final StringEntry charNewline;

		final StringEntry charTab;

		final StringEntry charReturn;

		final StringEntry charPage;

		final StringEntry charBackspace;

		final StringEntry charNul;

		final StringEntry charRubout;

		StringTable(int baseOffset, boolean equalpTables) {
			this.nextOffset = baseOffset;
			// The printer prologue. Every entry below is read by a RUNTIME body -- the
			// generic printer arms (_print_val / _princ_val), the float printer, the
			// character printer, the array printer, the newline writers -- and each of
			// them bakes the offset as its own i32.const, which is exactly the shape the
			// droppable-range scan reads -- so they are interned as body strings and
			// stand or fall with the bodies that address them; a hello world that never
			// reaches the generic printer keeps none of them. A later intern of the same
			// text from a blob-citing caller (the reader's char-name table, the runtime
			// intern blob) retracts the candidacy through the ordinary addString rule.
			this.nil = addBodyString("NIL");
			this.lparen = addBodyString("(");
			this.rparen = addBodyString(")");
			this.space = addBodyString(" ");
			this.dot = addBodyString(" . ");
			this.newline = addBodyString("\n");
			this.funcStr = addBodyString("#<function>");
			this.futureStr = addBodyString("#<FUTURE>");
			this.hashTableStr = addBodyString(LispHashTable.HASH_TABLE_PREFIX);
			this.hashTableEqualpStr = equalpTables ? addBodyString(LispHashTable.HASH_TABLE_PREFIX_EQUALP) : null;
			this.hashTableEnd = addBodyString(">");
			this.vecPrefix = addBodyString("#(");
			this.hashPrefix = addBodyString("#");
			this.rankAOpen = addBodyString("A(");
			this.fPrefix = addBodyString("#d(");
			this.sfPrefix = addBodyString("#f(");
			this.minus = addBodyString("-");
			this.period = addBodyString(".");
			this.slash = addBodyString("/");
			this.nanStr = addBodyString("NaN");
			this.infinityStr = addBodyString("Infinity");
			this.expE = addBodyString("e");
			this.charPrefix = addBodyString("#\\");
			this.charSpace = addBodyString("Space");
			this.charNewline = addBodyString("Newline");
			this.charTab = addBodyString("Tab");
			this.charReturn = addBodyString("Return");
			this.charPage = addBodyString("Page");
			this.charBackspace = addBodyString("Backspace");
			this.charNul = addBodyString("Nul");
			this.charRubout = addBodyString("Rubout");
		}

		/**
		 * Opens/closes the body-emission window: while it is open, a string first
		 * interned here is a candidate for {@link #shakeableRanges}. Interning the same
		 * string with the window CLOSED retracts the candidacy, whichever came first.
		 * @param on whether a function body is being emitted
		 */
		void attributing(boolean on) {
			this.attributing = on;
		}

		/**
		 * Interns a string whose offset is ONLY ever baked as an {@code i32.const} inside
		 * a function body -- the explicit spelling for a runtime helper built outside the
		 * pass-2 window (the cached {@code T} symbol and the helper bodies that return
		 * it). Candidacy is granted exactly as {@link #addString} inside the window does,
		 * i.e. on the FIRST intern only, so a blob-citing caller that got there first
		 * still pins the entry for good.
		 * @param s the string to intern
		 * @return its entry
		 */
		StringEntry addBodyString(String s) {
			boolean saved = this.attributing;
			this.attributing = true;
			try {
				return addString(s);
			}
			finally {
				this.attributing = saved;
			}
		}

		/**
		 * Interns a string that is the TAIL of another as a VIEW into that string's
		 * bytes, rather than a second copy of them. The one structural pair worth it is
		 * an instance layout's, whose record holds both the {@code %class-FOO} tag and
		 * the {@code FOO} print name it is a prefix of -- around 600 bytes on a library
		 * with two dozen classes and structs.
		 *
		 * <p>
		 * A shared entry can never be a tree-shake candidate: {@link #shakeableRanges}
		 * hands the shaker byte ranges to CUT, and a cut range has to own its bytes
		 * outright. So the reuse is declined when the container is already a candidate,
		 * and the tail joins its container in never being one -- which costs nothing
		 * here, since a layout record cites both offsets from data the shaker cannot see
		 * through anyway.
		 * @param whole the containing string, interned by this call when it is not
		 * already
		 * @param from the char index the tail starts at
		 * @return the tail's entry, overlapping {@code whole}'s bytes when it may
		 */
		StringEntry addTailOf(String whole, int from) {
			String tail = whole.substring(from);
			StringEntry existing = this.cache.get(tail);
			if (existing != null) {
				// Already interned on its own; sharing now would strand those bytes.
				return addString(tail);
			}
			StringEntry container = addString(whole);
			if (this.shakeable.contains(whole)) {
				return addString(tail);
			}
			int prefixBytes = whole.substring(0, from).getBytes(StandardCharsets.UTF_8).length;
			StringEntry entry = new StringEntry(container.offset() + prefixBytes, container.length() - prefixBytes);
			this.cache.put(tail, entry);
			return entry;
		}

		StringEntry addString(String s) {
			if (!this.attributing) {
				this.shakeable.remove(s);
			}
			StringEntry existing = this.cache.get(s);
			if (existing != null) {
				return existing;
			}
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			int offset = this.nextOffset;
			this.data.write(bytes, 0, bytes.length);
			this.nextOffset += bytes.length;
			StringEntry entry = new StringEntry(offset, bytes.length);
			this.cache.put(s, entry);
			if (this.attributing) {
				this.shakeable.add(s);
			}
			return entry;
		}

		/**
		 * The byte ranges (relative to the string data segment's own base) the tree
		 * shaker may cut out when no surviving function body addresses them. Ordered by
		 * offset so the emitted module stays deterministic. When the runtime intern table
		 * exists ({@code internBase >= 0}), a candidate that has a row in it offers that
		 * row as a second range PROBED ON THE STRING's interval, so the
		 * {@code (offset, length)} pair is cut together with the bytes it describes --
		 * the citation from data that used to disqualify every candidate of an interning
		 * program, resolved structurally instead ({@code _intern} skips the zeroed hole a
		 * cut row leaves).
		 * @param segmentIndex the data segment index the string data occupies
		 * @param dataBase the linear-memory address the segment starts at
		 * @param internBase the runtime intern table's absolute base address, or -1 when
		 * the program does not intern
		 * @param internRows the intern table's rows in blob order (empty when absent)
		 * @return the candidate ranges, each string range followed by its row range (the
		 * shaker orders cuts itself; this order is fixed so the module is deterministic)
		 */
		List<am.ik.wasm.WasmTreeShaker.DroppableDataRange> shakeableRanges(int segmentIndex, int dataBase,
				int internBase, List<StringEntry> internRows) {
			List<StringEntry> entries = new ArrayList<>(this.shakeable.size());
			for (String s : this.shakeable) {
				entries.add(this.cache.get(s));
			}
			entries.sort(java.util.Comparator.comparingInt(StringEntry::offset));
			Map<Integer, Integer> rowIndexByOffset = new HashMap<>();
			for (int i = 0; i < internRows.size(); i++) {
				rowIndexByOffset.put(internRows.get(i).offset(), i);
			}
			List<am.ik.wasm.WasmTreeShaker.DroppableDataRange> ranges = new ArrayList<>(entries.size());
			for (StringEntry e : entries) {
				int start = e.offset() - dataBase;
				int end = start + e.length();
				ranges.add(new am.ik.wasm.WasmTreeShaker.DroppableDataRange(segmentIndex, start, end));
				Integer row = internBase >= 0 ? rowIndexByOffset.get(e.offset()) : null;
				if (row != null) {
					int rowStart = internBase - dataBase + row * 8;
					ranges.add(new am.ik.wasm.WasmTreeShaker.DroppableDataRange(segmentIndex, rowStart, rowStart + 8,
							start, end));
				}
			}
			for (int[] blob : this.shakeableBlobs) {
				int start = blob[0] - dataBase;
				int probeLen = blob[2] > 0 ? blob[2] : blob[1];
				ranges.add(new am.ik.wasm.WasmTreeShaker.DroppableDataRange(segmentIndex, start, start + blob[1], start,
						start + probeLen));
			}
			return ranges;
		}

		/**
		 * Appends a raw byte blob to the data segment (4-byte aligned) and returns its
		 * absolute memory offset. Used to place the eval function-name registry after the
		 * string data within the single active data segment.
		 * @param blob the bytes to append
		 * @return the absolute offset where the blob was placed
		 */
		int appendBlob(byte[] blob) {
			while (this.nextOffset % 4 != 0) {
				this.data.write(0);
				this.nextOffset++;
			}
			int offset = this.nextOffset;
			this.data.write(blob, 0, blob.length);
			this.nextOffset += blob.length;
			return offset;
		}

		/**
		 * {@link #appendBlob} for a blob whose ONLY reader is a function body's own
		 * {@code i32.const} -- a baked packed integer-vector literal
		 * ({@code WasmQuoteCompiler.compileIntVectorLiteral}). The bytes join the
		 * {@link #shakeableRanges} candidates on exactly the terms an
		 * {@link #addBodyString} entry does: the shaker keeps the range when a surviving
		 * body still holds a constant inside it, and cuts it when the last reader died.
		 * Unlike a string a blob is never deduplicated, so one append is one range.
		 * @param blob the bytes to place
		 * @return the absolute offset where the blob was placed
		 */
		int appendShakeableBlob(byte[] blob) {
			int offset = appendBlob(blob);
			this.shakeableBlobs.add(new int[] { offset, blob.length, 0 });
			return offset;
		}

		/**
		 * {@link #appendShakeableBlob} for a blob whose readers all cite its BASE address
		 * (interior offsets are derived arithmetically inside the reading bodies): the
		 * droppable range is probed on the first word only, so an unrelated small
		 * constant landing somewhere inside a wide blob cannot pin it -- with a 755-byte
		 * table that false retention was near-certain (todo-431's Schubfach tables are
		 * the one user today).
		 * @param blob the bytes to place
		 * @return the absolute offset where the blob was placed
		 */
		int appendShakeableBlobProbedOnBase(byte[] blob) {
			int offset = appendBlob(blob);
			this.shakeableBlobs.add(new int[] { offset, blob.length, 4 });
			return offset;
		}

		byte[] toByteArray() {
			return this.data.toByteArray();
		}

		/**
		 * Returns a snapshot of all interned string entries. Used to build the
		 * compile-time intern table scanned by the runtime {@code _intern} so that
		 * symbols parsed by {@code read} resolve to the canonical offset the eval runtime
		 * compares against.
		 * @return the interned entries
		 */
		java.util.Collection<StringEntry> entries() {
			return new java.util.ArrayList<>(this.cache.values());
		}

		record StringEntry(int offset, int length) {
		}

	}

}
