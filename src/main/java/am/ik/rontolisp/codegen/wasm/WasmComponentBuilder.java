package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import am.ik.wasm.ComponentImportBlock;
import am.ik.wasm.ComponentWriter;
import am.ik.wasm.WasmExports;
import am.ik.wasm.WasmImports;
import am.ik.wasm.WasmTreeShaker;
import am.ik.wit.WitItem;
import org.jspecify.annotations.Nullable;

/**
 * Wraps a rontolisp core module (compiled in component mode) into a WASI 0.3 (Preview 3)
 * <strong>component</strong> that prints through {@code wasi:cli/stdout@0.3.0} and is
 * runnable with {@code wasmtime run} (wasmtime 46+; the async canonical ABI and stackful
 * lifts are on by default there, only the synchronous stream/future built-ins the adapter
 * uses are still gated).
 *
 * <p>
 * In WASI 0.3 the {@code wasi:io} package is gone: all byte I/O flows through the
 * built-in component-model {@code stream<u8>} / {@code future<T>} types and the async
 * canonical ABI. The wrapped rontolisp core module is unchanged from Preview 1 &mdash; it
 * still imports the eight {@code wasi_snapshot_preview1} functions and prints / reads
 * through them; those imports are satisfied by an <em>adapter</em> core module that
 * implements them over WASI 0.3 ({@code wasi:cli}, {@code wasi:filesystem},
 * {@code wasi:clocks}, {@code wasi:random}) using
 * {@code stream.new}/{@code stream.read}/{@code stream.write} and {@code future.read}
 * (see {@code src/wasm-component/adapter.wat}). The component's
 * {@code wasi:cli/run@0.3.0} export &mdash; an {@code async func} &mdash; is lifted as a
 * <strong>stackful</strong> async export (an ordinary {@link ComponentWriter#canonLift}
 * against an {@link ComponentWriter#asyncFuncTypeResultType async} function type), so the
 * synchronous {@code stream.write}/{@code stream.read}/{@code future.read} built-ins the
 * adapter calls block cooperatively without any callback state machine.
 *
 * <p>
 * A lowered WASI import with a {@code list<u8>}/{@code string} parameter needs the
 * canonical memory, and that memory must exist before the importing module is
 * instantiated. To avoid the instantiate-before-memory cycle, a tiny shared "memory" core
 * module is instantiated first; it exports the memory (and a {@code cabi_realloc}) used
 * by the canonical lowering, the canonical built-ins and the adapter / rontolisp modules.
 *
 * <p>
 * Three byte blobs are loaded from classpath resources next to this class (under
 * {@code component/}) because they are fixed and independent of the compiled program:
 * {@code import-block.bin} (the unified WASI 0.3 import declarations) and the two helper
 * core modules {@code mem.wasm} / {@code adapter.wasm}. The remaining wiring is emitted
 * programmatically below; the indices and per-function canonical options were derived
 * from {@code wasm-tools dump} of a generated reference (see
 * {@code src/wasm-component/README.md}). Each artifact was validated with
 * {@code wasm-tools validate -f component-model -f cm-async} and executed with
 * {@code wasmtime run}.
 */
public final class WasmComponentBuilder {

	/** Classpath location of the embedded component blobs, relative to this class. */
	private static final String RES = "component/";

	/**
	 * Pre-built component type/import sections declaring every imported WASI 0.3
	 * interface, captured from a {@code wasm-tools}-generated reference for the
	 * {@code uni} WIT world (see {@code src/wasm-component/uni.wit}). In order, component
	 * import instances 0-10 are: {@code wasi:cli/types} (the {@code error-code} enum),
	 * {@code wasi:cli/stdout}, {@code wasi:cli/stdin}, {@code wasi:cli/environment},
	 * {@code wasi:clocks/types} (the {@code duration} alias {@code wait-for} pulls in),
	 * {@code wasi:clocks/system-clock}, {@code wasi:clocks/monotonic-clock} (declaring
	 * {@code now} and the async {@code wait-for}), {@code wasi:filesystem/types},
	 * {@code wasi:filesystem/preopens}, {@code wasi:random/random} and
	 * {@code wasi:cli/stderr} (appended last, for {@code warn} on fd&nbsp;2). The block
	 * defines component types 0-15.
	 * <p>
	 * Those numbers describe the blob, NOT the emitted component: {@link #buildBase}
	 * prunes it to the interfaces the program reaches
	 * ({@link ComponentImportBlock#prune}) and reads every instance index, and the first
	 * free type index, back off the result.
	 */
	private static final byte[] IMPORT_BLOCK = resource("import-block.bin");

	/**
	 * A core module exporting a linear {@code memory} (6 pages) and a bump-allocator
	 * {@code cabi_realloc}, shared by the canonical lowering, the canonical built-ins and
	 * the main modules. The allocator half is dropped again for a component whose
	 * canonical options never name it ({@link #memModuleFor(byte[], boolean)}). Source:
	 * {@code src/wasm-component/mem.wat}.
	 */
	private static final byte[] MEM_MODULE = resource("mem.wasm");

	/**
	 * The preview1-to-WASI-0.3 adapter core module: it imports the shared memory, the
	 * lowered WASI 0.3 functions and the async canonical built-ins (under {@code "w"})
	 * and exports the eleven preview1-style functions rontolisp imports -- plus the
	 * STDIO-ONLY halves of the two fd-polymorphic ones ({@code fd_write_stdio} /
	 * {@code fd_read_stdin}), which {@link #fixedSurface} retains under the preview1
	 * names for a core module that imports no {@code path_open}. Source:
	 * {@code src/wasm-component/adapter.wat}.
	 */
	private static final byte[] ADAPTER_MODULE = resource("adapter.wasm");

	// The base block's interface ids, referenced by the fixed wiring below. Their
	// component instance indices are NOT constants: the block is pruned to what the
	// program's core module still reaches, so every index comes from
	// ComponentImportBlock.Pruned.instanceOf().
	private static final String IFACE_CLI_TYPES = "wasi:cli/types@0.3.0";

	private static final String IFACE_STDOUT = "wasi:cli/stdout@0.3.0";

	private static final String IFACE_STDIN = "wasi:cli/stdin@0.3.0";

	private static final String IFACE_ENVIRON = "wasi:cli/environment@0.3.0";

	private static final String IFACE_SYS_CLOCK = "wasi:clocks/system-clock@0.3.0";

	private static final String IFACE_MONO_CLOCK = "wasi:clocks/monotonic-clock@0.3.0";

	private static final String IFACE_FS_TYPES = "wasi:filesystem/types@0.3.0";

	private static final String IFACE_FS_PREOPENS = "wasi:filesystem/preopens@0.3.0";

	private static final String IFACE_RANDOM = "wasi:random/random@0.3.0";

	private static final String IFACE_STDERR = "wasi:cli/stderr@0.3.0";

	/**
	 * The eleven {@code wasi_snapshot_preview1} functions the adapter implements, in its
	 * own export order. A core module imports a subset of them (after {@code --optimize},
	 * only what it reaches), and that subset drives everything below.
	 */
	private static final List<String> PREVIEW1_FUNCS = List.of("fd_write", "fd_read", "path_open", "fd_readdir",
			"fd_close", "random_get", "clock_time_get", "environ_sizes_get", "environ_get", "fd_prestat_get",
			"fd_prestat_dir_name");

	/**
	 * The adapter's NARROW implementations of the two fd-polymorphic entry points,
	 * retained under the preview1 name when the program cannot present the wider fds.
	 * <p>
	 * {@code path_open} is the only writer of the adapter's fd table, so a core module
	 * that does not import it can never present a file fd: {@code fd_write}'s file arm
	 * and {@code fd_read}'s file arm are dead, and with them the whole
	 * {@code wasi:filesystem} surface -- which the shaker cannot see for itself, because
	 * a runtime fd is a value, not an edge. Naming the narrow implementation instead is
	 * what makes the reachability visible.
	 * <p>
	 * {@code fd_write} narrows once more. fd&nbsp;2 is the RESERVED
	 * {@code *error-output*} handle ({@code .kb/standard-output-redirect.md}),
	 * materialized by nothing but that variable, {@code warn} and the entry function's
	 * uncaught-condition landing pad -- so whether a program can write it is a question
	 * about its SOURCE plus what the compiler injects into it, which
	 * {@link Narrowing#reachesStandardError()} answers. When it cannot,
	 * {@code fd_write_stdout} is retained instead and the whole {@code wasi:cli/stderr}
	 * interface leaves the component.
	 * @param preview1 the preview1 entry point the core module imports
	 * @param files whether the program can present a file fd (it imports
	 * {@code path_open})
	 * @param standardError whether the program can present fd 2
	 * @return the adapter export to retain under {@code preview1}
	 */
	private static String narrowImpl(String preview1, boolean files, boolean standardError) {
		if (files) {
			// The fd-polymorphic implementations are the only ones that can serve a file
			// fd, so nothing narrows.
			return preview1;
		}
		return switch (preview1) {
			case "fd_write" -> standardError ? "fd_write_stdio" : "fd_write_stdout";
			case "fd_read" -> "fd_read_stdin";
			default -> preview1;
		};
	}

	/**
	 * How far the wrapper may narrow its fixed WASI surface for one program.
	 * <p>
	 * Most of the narrowing reads the core module's own bytes (which preview1 functions
	 * it still imports, which {@code "w"} members the shaken adapter still binds), but
	 * not all of it can: a runtime file descriptor is a VALUE, not an edge, so "can this
	 * program write fd&nbsp;2" is unanswerable from the module and has to come from the
	 * source. That is what this record carries -- the compile-time facts
	 * {@link WasmLispCompiler} knows and the bytes do not.
	 *
	 * @param shake whether to narrow at all ({@code --optimize}); {@code false} keeps the
	 * whole surface, which is what every pre-{@code --optimize} component had
	 * @param reachesStandardError whether the program can materialize the reserved
	 * {@code *error-output*} handle 2 -- it mentions {@code *error-output*}, calls
	 * {@code warn}, carries the EH-mode entry landing pad whose uncaught-condition report
	 * writes fd 2 ({@code WasmUncaughtReportCompiler#emittedFor}, the producer the
	 * compiler INJECTS rather than one a source scan can find), or is compiled in
	 * {@code --dynamic} mode, where any symbol is reachable at run time
	 */
	record Narrowing(boolean shake, boolean reachesStandardError) {

		/** Keep the whole fixed surface: no {@code --optimize}. */
		static final Narrowing NONE = new Narrowing(false, true);

	}

	/** A WASI function the fixed wiring aliases out of one of the block's instances. */
	private record BlockFunc(String iface, String member) {
	}

	/**
	 * The WASI functions the fixed wiring may alias, in the order the alias section
	 * declares them (which fixes their component function indices). Keyed by the name the
	 * {@code w} import below refers to them by.
	 */
	private static final java.util.LinkedHashMap<String, BlockFunc> BLOCK_FUNCS = blockFuncs();

	private static java.util.LinkedHashMap<String, BlockFunc> blockFuncs() {
		final java.util.LinkedHashMap<String, BlockFunc> funcs = new java.util.LinkedHashMap<>();
		funcs.put("stdout-write", new BlockFunc(IFACE_STDOUT, "write-via-stream"));
		funcs.put("stdin-read", new BlockFunc(IFACE_STDIN, "read-via-stream"));
		funcs.put("get-environment", new BlockFunc(IFACE_ENVIRON, "get-environment"));
		funcs.put("sys-now", new BlockFunc(IFACE_SYS_CLOCK, "now"));
		funcs.put("mono-now", new BlockFunc(IFACE_MONO_CLOCK, "now"));
		funcs.put("file-read", new BlockFunc(IFACE_FS_TYPES, "[method]descriptor.read-via-stream"));
		funcs.put("file-append", new BlockFunc(IFACE_FS_TYPES, "[method]descriptor.append-via-stream"));
		funcs.put("open-at", new BlockFunc(IFACE_FS_TYPES, "[method]descriptor.open-at"));
		funcs.put("get-directories", new BlockFunc(IFACE_FS_PREOPENS, "get-directories"));
		funcs.put("get-random-u64", new BlockFunc(IFACE_RANDOM, "get-random-u64"));
		funcs.put("stderr-write", new BlockFunc(IFACE_STDERR, "write-via-stream"));
		funcs.put("read-dir", new BlockFunc(IFACE_FS_TYPES, "[method]descriptor.read-directory"));
		return funcs;
	}

	// Type keys of the fixed wiring. The four PROJECTED ones are aliased out of a block
	// instance; the rest are defined over them. Emission order fixes their component type
	// indices: the projections join the alias section, the definitions the type section
	// after it.
	private static final String T_CLI_ERRCODE = "cli-error-code";

	private static final String T_FS_ERRCODE = "fs-error-code";

	private static final String T_DESCRIPTOR = "descriptor";

	private static final String T_DIRENT = "directory-entry";

	private static final String T_STREAM = "stream<u8>";

	private static final String T_DE_STREAM = "stream<directory-entry>";

	private static final String T_CLI_RESULT = "result<_,cli-error-code>";

	private static final String T_CLI_FUTURE = "future<result<_,cli-error-code>>";

	private static final String T_FS_RESULT = "result<_,fs-error-code>";

	private static final String T_FS_FUTURE = "future<result<_,fs-error-code>>";

	private static final String T_RUN_RESULT = "result";

	private static final String T_RUN_FUNC = "async func() -> result";

	/** A type the fixed wiring projects out of one of the block's instances. */
	private static final java.util.LinkedHashMap<String, BlockFunc> PROJECTED_TYPES = projectedTypes();

	private static java.util.LinkedHashMap<String, BlockFunc> projectedTypes() {
		final java.util.LinkedHashMap<String, BlockFunc> types = new java.util.LinkedHashMap<>();
		types.put(T_CLI_ERRCODE, new BlockFunc(IFACE_CLI_TYPES, "error-code"));
		types.put(T_FS_ERRCODE, new BlockFunc(IFACE_FS_TYPES, "error-code"));
		types.put(T_DESCRIPTOR, new BlockFunc(IFACE_FS_TYPES, "descriptor"));
		types.put(T_DIRENT, new BlockFunc(IFACE_FS_TYPES, "directory-entry"));
		return types;
	}

	/**
	 * A type the fixed wiring DEFINES: what it is built over, and how to encode it once
	 * those have indices.
	 *
	 * @param needs the type keys the encoding names
	 * @param encode the encoder, given the assigned component type indices
	 */
	private record DefinedType(List<String> needs,
			java.util.function.Function<java.util.Map<String, Integer>, byte[]> encode) {
	}

	/** The defined types, in the order the type section declares them. */
	private static final java.util.LinkedHashMap<String, DefinedType> DEFINED_TYPES = definedTypes();

	private static java.util.LinkedHashMap<String, DefinedType> definedTypes() {
		final java.util.LinkedHashMap<String, DefinedType> types = new java.util.LinkedHashMap<>();
		types.put(T_STREAM, new DefinedType(List.of(), t -> ComponentWriter.definedStream(ComponentWriter.VT_U8)));
		types.put(T_DE_STREAM,
				new DefinedType(List.of(T_DIRENT), t -> ComponentWriter.definedStreamOfType(at(t, T_DIRENT))));
		types.put(T_CLI_RESULT,
				new DefinedType(List.of(T_CLI_ERRCODE), t -> ComponentWriter.definedResultErr(at(t, T_CLI_ERRCODE))));
		types.put(T_CLI_FUTURE,
				new DefinedType(List.of(T_CLI_RESULT), t -> ComponentWriter.definedFuture(at(t, T_CLI_RESULT))));
		types.put(T_FS_RESULT,
				new DefinedType(List.of(T_FS_ERRCODE), t -> ComponentWriter.definedResultErr(at(t, T_FS_ERRCODE))));
		types.put(T_FS_FUTURE,
				new DefinedType(List.of(T_FS_RESULT), t -> ComponentWriter.definedFuture(at(t, T_FS_RESULT))));
		types.put(T_RUN_RESULT, new DefinedType(List.of(), t -> ComponentWriter.definedResultVoid()));
		types.put(T_RUN_FUNC, new DefinedType(List.of(T_RUN_RESULT),
				t -> ComponentWriter.asyncFuncTypeResultType(at(t, T_RUN_RESULT))));
		return types;
	}

	/**
	 * One member of the adapter's {@code "w"} import: the lowered WASI function or
	 * canonical built-in it binds, what that needs, and how to encode its {@code canon}
	 * entry.
	 *
	 * @param func the {@link #BLOCK_FUNCS} key this member lowers, or {@code null} for a
	 * canonical built-in
	 * @param types the type keys the encoding names
	 * @param realloc whether the encoding names the shared {@code cabi_realloc}
	 * @param encode the encoder, given the assigned component function and type indices
	 */
	private record WMember(@Nullable String func, List<String> types, boolean realloc,
			java.util.function.BiFunction<java.util.Map<String, Integer>, java.util.Map<String, Integer>, byte[]> encode) {
	}

	/**
	 * The adapter's whole {@code "w"} import, in the order the canon section declares it
	 * (which fixes the core function indices). A member survives exactly when the shaken
	 * adapter still imports its name -- and what survives decides which WASI functions
	 * are aliased, which types are declared, and finally which interfaces the block still
	 * has to import.
	 */
	private static final java.util.LinkedHashMap<String, WMember> W_MEMBERS = wMembers();

	private static java.util.LinkedHashMap<String, WMember> wMembers() {
		final java.util.LinkedHashMap<String, WMember> w = new java.util.LinkedHashMap<>();
		w.put("stdout-write", lower("stdout-write", ComponentWriter::canonLower));
		w.put("stdin-read", lower("stdin-read", f -> ComponentWriter.canonLowerMemory(f, 0)));
		w.put("get-environment",
				lowerRealloc("get-environment", (f, r) -> ComponentWriter.canonLowerMemoryReallocUtf8(f, 0, r)));
		w.put("sys-now", lower("sys-now", f -> ComponentWriter.canonLowerMemory(f, 0)));
		w.put("mono-now", lower("mono-now", ComponentWriter::canonLower));
		w.put("file-read", lower("file-read", f -> ComponentWriter.canonLowerMemory(f, 0)));
		w.put("file-append", lower("file-append", ComponentWriter::canonLower));
		w.put("open-at", lowerRealloc("open-at", (f, r) -> ComponentWriter.canonLowerMemoryReallocUtf8(f, 0, r)));
		w.put("get-directories",
				lowerRealloc("get-directories", (f, r) -> ComponentWriter.canonLowerMemoryReallocUtf8(f, 0, r)));
		w.put("get-random-u64", lower("get-random-u64", ComponentWriter::canonLower));
		w.put("drop-desc", builtin(T_DESCRIPTOR, ComponentWriter::canonResourceDrop));
		w.put("stream-new", builtin(T_STREAM, ComponentWriter::canonStreamNew));
		// The ASYNC (non-blocking) built-in variants of base component-model-async: a
		// BLOCKED result completes through the waitable trio below (the adapter's
		// blocking
		// wrappers), so no gated feature is needed (neither more-async-builtins nor
		// stackful).
		w.put("stream-read", builtin(T_STREAM, t -> ComponentWriter.canonStreamReadAsync(t, 0)));
		w.put("stream-write", builtin(T_STREAM, t -> ComponentWriter.canonStreamWriteAsync(t, 0)));
		w.put("stream-drop-r", builtin(T_STREAM, ComponentWriter::canonStreamDropReadable));
		w.put("stream-drop-w", builtin(T_STREAM, ComponentWriter::canonStreamDropWritable));
		w.put("future-read-cli", builtin(T_CLI_FUTURE, t -> ComponentWriter.canonFutureReadAsync(t, 0)));
		w.put("future-drop-cli", builtin(T_CLI_FUTURE, ComponentWriter::canonFutureDropReadable));
		// the filesystem error-code is a variant with a string-bearing case, so its
		// future
		// payload needs the shared allocator
		w.put("future-read-fs", builtinRealloc(T_FS_FUTURE, (t, r) -> ComponentWriter.canonFutureReadAsync(t, 0, r)));
		w.put("future-drop-fs", builtin(T_FS_FUTURE, ComponentWriter::canonFutureDropReadable));
		w.put("stderr-write", lower("stderr-write", ComponentWriter::canonLower));
		w.put("waitable-set-new", new WMember(null, List.of(), false, (f, t) -> ComponentWriter.canonWaitableSetNew()));
		w.put("waitable-join", new WMember(null, List.of(), false, (f, t) -> ComponentWriter.canonWaitableJoin()));
		w.put("waitable-set-wait",
				new WMember(null, List.of(), false, (f, t) -> ComponentWriter.canonWaitableSetWait(0)));
		// descriptor.read-directory: the result is a (stream, future) handle pair, two
		// flat
		// values, so it returns through a memory retptr.
		w.put("read-dir", lower("read-dir", f -> ComponentWriter.canonLowerMemory(f, 0)));
		// The directory-entry stream's own read / drop. The read needs realloc: each
		// element owns its name string.
		w.put("stream-read-de", builtinRealloc(T_DE_STREAM, (t, r) -> ComponentWriter.canonStreamReadAsync(t, 0, r)));
		w.put("stream-drop-r-de", builtin(T_DE_STREAM, ComponentWriter::canonStreamDropReadable));
		return w;
	}

	// Every lookup below is total by construction (the tables and the surface are
	// computed
	// from each other), so a miss is a wiring bug, not a case to handle.
	private static <K, V> V at(java.util.Map<K, V> table, K key) {
		return Objects.requireNonNull(table.get(key), () -> "the fixed WASI wiring has no entry for " + key);
	}

	private static WMember lower(String func, java.util.function.IntFunction<byte[]> encode) {
		return new WMember(func, List.of(), false, (f, t) -> encode.apply(at(f, func)));
	}

	private static WMember builtin(String type, java.util.function.IntFunction<byte[]> encode) {
		return new WMember(null, List.of(type), false, (f, t) -> encode.apply(at(t, type)));
	}

	/**
	 * The core function index of the shared memory module's {@code cabi_realloc}, which
	 * is aliased first and so is core func 0 <strong>whenever it is aliased at
	 * all</strong> ({@link #needsSharedRealloc}). It is reachable only through the two
	 * factories below, which is what keeps {@link WMember#realloc()} from drifting away
	 * from the encoders: a member that names the allocator cannot be declared without
	 * declaring that it does.
	 */
	private static final int SHARED_REALLOC = 0;

	/**
	 * Encodes a {@code canon} entry that stages host-owned bytes through an allocator.
	 */
	@FunctionalInterface
	private interface ReallocEncoder {

		byte[] encode(int index, int realloc);

	}

	private static WMember lowerRealloc(String func, ReallocEncoder encode) {
		return new WMember(func, List.of(), true, (f, t) -> encode.encode(at(f, func), SHARED_REALLOC));
	}

	private static WMember builtinRealloc(String type, ReallocEncoder encode) {
		return new WMember(null, List.of(type), true, (f, t) -> encode.encode(at(t, type), SHARED_REALLOC));
	}

	/**
	 * The interfaces of the base/sockets blocks a {@code %component-import} may bind FROM
	 * THE BLOCK (the wait.lisp shim's wasi:clocks/monotonic-clock, environment.lisp's
	 * wasi:cli/environment): they are part of the fixed WASI surface, so importing them
	 * again as user instances would be invalid. Maps each interface to the member fields
	 * the block actually declares -- binding anything else would only fail component
	 * validation with a byte offset.
	 */
	private static final java.util.Map<String, java.util.Set<String>> FIXED_BLOCK_IFACES = java.util.Map.of(
			"wasi:clocks/monotonic-clock@0.3.0", java.util.Set.of("now", "wait-for"), "wasi:cli/stdin@0.3.0",
			java.util.Set.of("read-via-stream"), "wasi:cli/environment@0.3.0",
			java.util.Set.of("get-environment", "get-arguments"));

	private WasmComponentBuilder() {
	}

	/**
	 * A {@code rontolisp:wasm-export} function to expose as a component-model export. The
	 * core module core-exports a wrapper under {@code name}; the component aliases it and
	 * lifts it <strong>synchronously</strong> by default (a pure-compute export needs no
	 * async, unlike the stackful-async {@code run} lift). A scalar export lifts with no
	 * canonical options; a {@code :string}/{@code :s-expr}-involving one with the
	 * canonical string options. An {@code :async t} export instead lifts against an
	 * <strong>async</strong> function type &mdash; the same stackful-async shape as
	 * {@code run}, with an identical flat core signature &mdash; so I/O inside it blocks
	 * cooperatively instead of trapping.
	 *
	 * @param name the export name (a valid component-model label; honors {@code :as})
	 * @param paramNames the parameter labels the function type carries ({@code p0},
	 * {@code p1}, ... by default; the WIT world's own names under
	 * {@code rontolisp:wit-export}, or an explicit {@code :param-names})
	 * @param paramValTypes the {@code ComponentWriter.VT_*} code of each parameter
	 * @param resultValType the {@code ComponentWriter.VT_*} result code, or {@code null}
	 * for no result
	 * @param async whether to lift against an async function type ({@code :async t})
	 * @param iface the fully-qualified id of the WIT interface this export belongs to
	 * ({@code "docs:adder/add@0.1.0"}), or {@code null} for a flat top-level function
	 * export. Exports sharing an {@code iface} are bundled into one exported component
	 * <em>instance</em> under that id, instead of being exported as flat functions
	 */
	public record FuncExport(String name, List<String> paramNames, List<Integer> paramValTypes,
			@Nullable Integer resultValType, boolean async, @Nullable String iface) {
	}

	/**
	 * Append the per-export alias / type / lift / export wiring for the
	 * {@code wasm-export} functions. Emits nothing when {@code decls} is empty, so an
	 * export-free program's component stays byte-identical. The rontolisp core is always
	 * core instance 3 (mem = 0, adapter = 2); each new index space entry is appended
	 * after the {@code run} wiring, whose next free indices the caller passes in.
	 *
	 * <p>
	 * A {@code :string}/{@code :s-expr} boundary type lifts through the canonical string
	 * ABI: the alias section additionally projects the core's own {@code cabi_realloc}
	 * (the host lowers string arguments into linear memory through it) and one
	 * {@code cabi_post_*} post-return per flat-result signature (it pops the core's bump
	 * heap back to the per-call snapshot once the host has copied the results out,
	 * intern-count-guarded), and the string-involving exports are lifted with the
	 * {@code (memory 0) (realloc ...) string-encoding=utf8 (post-return ...)} options.
	 * Memory 0 is the shared {@code mem.wasm} memory aliased at build start -- the same
	 * memory instance the core imports, so the wrapper's {@code (ptr,len)} values and the
	 * host's lowered argument bytes live in one address space. A program with no
	 * {@code :string}/{@code :s-expr} export gets none of this: its component is
	 * byte-identical to the Tier 1 scalar-only shape.
	 * @param c the component writer
	 * @param decls the parsed export directives
	 * @param nextCoreFunc the first free core function index (after the {@code run}
	 * alias)
	 * @param nextType the first free component type index
	 * @param nextComponentFunc the first free component function index (after the
	 * {@code run} lift)
	 * @param nextComponentInstance the first free component instance index (after the
	 * {@code wasi:cli/run} instance and the user-import instances); each exported WIT
	 * interface consumes one
	 */
	private static void appendFuncExports(ComponentWriter c, List<WasmExportCompiler.Decl> decls, int nextCoreFunc,
			int nextType, int nextComponentFunc, int rontolispInstance, int nextComponentInstance) {
		if (decls.isEmpty()) {
			return;
		}
		final List<byte[]> aliases = new java.util.ArrayList<>();
		final List<byte[]> types = new java.util.ArrayList<>();
		final List<byte[]> lifts = new java.util.ArrayList<>();
		final List<byte[]> instances = new java.util.ArrayList<>();
		final List<byte[]> exports = new java.util.ArrayList<>();
		// Exports that name a WIT interface (`export docs:adder/add;`) are bundled into
		// one
		// component instance per interface, exported under the interface's id; a flat
		// (interface-less) export is exported as a top-level function, as before.
		// Insertion
		// order is world order, so the emitted exports keep it.
		final java.util.LinkedHashMap<String, List<java.util.Map.Entry<String, Integer>>> ifaceGroups = new java.util.LinkedHashMap<>();
		// The canonical string ABI aliases (cabi_realloc, the cabi_post_* post-returns)
		// come first in the core function index space; they exist only when a
		// :string/:s-expr boundary is present, so a scalar-only component keeps the
		// Tier 1 bytes.
		int coreFunc = nextCoreFunc;
		int realloc = -1;
		final java.util.Map<String, Integer> postFuncs = new java.util.LinkedHashMap<>();
		if (decls.stream().anyMatch(WasmExportCompiler::usesMemory)) {
			aliases.add(ComponentWriter.aliasCoreFunc(rontolispInstance, WasmExportCompiler.CABI_REALLOC));
			realloc = coreFunc++;
			for (WasmExportCompiler.Decl d : decls) {
				if (WasmExportCompiler.usesMemory(d)) {
					String kind = WasmExportCompiler.componentPostReturnKind(d);
					if (!postFuncs.containsKey(kind)) {
						aliases.add(ComponentWriter.aliasCoreFunc(rontolispInstance,
								WasmExportCompiler.cabiPostExportName(kind)));
						postFuncs.put(kind, coreFunc++);
					}
				}
			}
		}
		for (int i = 0; i < decls.size(); i++) {
			WasmExportCompiler.Decl decl = decls.get(i);
			FuncExport e = WasmExportCompiler.componentExport(decl);
			// Alias the core wrapper export out of the rontolisp instance; a
			// :string/:s-expr-returning export's core export is its retptr shim.
			aliases.add(ComponentWriter.aliasCoreFunc(rontolispInstance, e.name()));
			int func = coreFunc++;
			// One function type per export (params p0, p1, ... unless the directive names
			// them): synchronous by default; an :async t export gets the async
			// counterpart (tag 0x43), which turns the lift below into a stackful
			// async export (the run shape) with the same flat core signature -- the ONLY
			// byte difference an :async export introduces.
			final List<String> paramNames = e.paramNames();
			types.add(e.async() ? ComponentWriter.asyncFuncTypeScalars(paramNames, e.paramValTypes(), e.resultValType())
					: ComponentWriter.funcTypeScalars(paramNames, e.paramValTypes(), e.resultValType()));
			if (WasmExportCompiler.usesMemory(decl)) {
				// String-involving export: lift with the canonical string options over
				// the shared memory (core memory 0).
				int postFunc = java.util.Objects
					.requireNonNull(postFuncs.get(WasmExportCompiler.componentPostReturnKind(decl)));
				lifts.add(
						ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(func, nextType + i, 0, realloc, postFunc));
			}
			else {
				// Sync lift with no canonical options: flat scalars need no
				// memory/realloc.
				lifts.add(ComponentWriter.canonLift(func, nextType + i));
			}
			if (e.iface() == null) {
				// Flat export: export the lifted component func directly under its name.
				exports.add(ComponentWriter.exportFunc(e.name(), nextComponentFunc + i));
			}
			else {
				// Interface member: defer to a per-interface instance built below.
				ifaceGroups.computeIfAbsent(e.iface(), k -> new java.util.ArrayList<>())
					.add(java.util.Map.entry(e.name(), nextComponentFunc + i));
			}
		}
		// One synthesized instance per exported interface, exported under the interface's
		// fully-qualified id (`export docs:adder/add@0.1.0`). The instances come after
		// the
		// run instance and the user-import instances in the component instance index
		// space.
		int componentInstance = nextComponentInstance;
		for (java.util.Map.Entry<String, List<java.util.Map.Entry<String, Integer>>> group : ifaceGroups.entrySet()) {
			instances.add(ComponentWriter.componentInstanceFromFuncs(group.getValue()));
			exports.add(ComponentWriter.exportInstance(group.getKey(), componentInstance++));
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(types));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));
		if (!instances.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(instances));
		}
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode (imports its
	 * memory, imports {@code wasi_snapshot_preview1}, and exports a {@code run} function
	 * returning {@code i32})
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule) {
		return build(coreModule, List.of());
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module,
	 * additionally exposing the given {@code rontolisp:wasm-export} functions as
	 * host-callable component-model exports (synchronous canonical lifts alongside the
	 * stackful-async {@code wasi:cli/run} export; {@code :string}/{@code :s-expr}
	 * boundaries lift through the canonical string ABI).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the parsed function export directives (empty for none; an empty
	 * list yields output byte-identical to {@link #build(byte[])})
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports) {
		return build(coreModule, funcExports, List.of(), Narrowing.NONE);
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module,
	 * additionally importing the given user WIT interfaces ({@code rontolisp:wit-import}
	 * under {@code --component}): each interface becomes a component-level instance
	 * import whose functions are {@code canon lower}ed into a synthesized core instance
	 * that satisfies the core module's matching imports at instantiation. An empty import
	 * list emits nothing and shifts nothing, so an import-free component stays
	 * byte-identical. A {@code rontolisp:tcp-*} program is just this path with
	 * sockets.lisp's own {@code wasi:sockets/types@0.3.0} import in the list (the
	 * dedicated sockets blob variant and its hand-written adapter are gone).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the parsed function export directives (empty for none)
	 * @param imports the user WIT interface imports (empty for none)
	 * @return the WASI 0.3 component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports,
			List<WasmComponentImportCompiler.Import> imports, Narrowing narrowing) {
		// rontolisp:fetch off serve is the http.lisp library over canon-lowered
		// wasi:http@0.3 user imports (the base variant); a serving program goes through
		// buildServe instead.
		// An import of an interface the block itself declares (wait.lisp's
		// wasi:clocks/monotonic-clock) is bound FROM the block, never re-imported.
		final List<WasmComponentImportCompiler.Import> fixed = new java.util.ArrayList<>();
		final List<WasmComponentImportCompiler.Import> user = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			(FIXED_BLOCK_IFACES.containsKey(imported.ifaceId()) ? fixed : user).add(imported);
		}
		validateFixedMembers(fixed);
		rejectAdapterImportCollisions(user, WitEmitter.VARIANT_BASE);
		rejectDuplicateUserImports(user);
		return buildBase(coreModule, funcExports, fixed, user, narrowing);
	}

	/**
	 * The fixed WASI interfaces the base variant's component will actually import for
	 * this core module -- what {@code --emit-wit} must describe, since a pruned block
	 * declares fewer of them than the variant's full world.
	 * <p>
	 * The emitted WIT and the emitted bytes have to say the same thing, so this is the
	 * same computation {@link #build} runs, not a parallel one.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param imports the interface imports the program declares
	 * @param narrowing how far the fixed surface is narrowed ({@code --optimize} plus the
	 * source-derived facts)
	 * @return the interface ids the component imports from the block
	 */
	static java.util.Set<String> wasiInterfaces(byte[] coreModule, List<WasmComponentImportCompiler.Import> imports,
			Narrowing narrowing) {
		final List<WasmComponentImportCompiler.Import> fixed = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (FIXED_BLOCK_IFACES.containsKey(imported.ifaceId())) {
				fixed.add(imported);
			}
		}
		return ComponentImportBlock.parse(IMPORT_BLOCK)
			.prune(fixedSurface(coreModule, fixed, narrowing).interfaces())
			.instanceOf()
			.keySet();
	}

	// Two user imports of ONE interface id (a program's own wit-import of
	// wasi:sockets/types beside the tcp built-ins' sockets.lisp import) would emit the
	// same instance import name twice -- invalid, and nothing downstream says so in
	// words.
	private static void rejectDuplicateUserImports(List<WasmComponentImportCompiler.Import> imports) {
		final java.util.Set<String> seen = new java.util.HashSet<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (!seen.add(imported.ifaceId())) {
				throw new UnsupportedOperationException("rontolisp:wit-import binds '" + imported.ifaceId()
						+ "' twice: a component cannot import the same interface twice. The rontolisp:tcp-* built-ins "
						+ "bind wasi:sockets/types@0.3.0 themselves, so either drop the built-ins and drive the "
						+ "interface through your own binding, or drop the duplicate directive");
			}
		}
	}

	/**
	 * The imports that are NOT bound from the base/sockets blocks' own fixed WASI surface
	 * -- the genuine user instance imports, which are the only ones the emitted WIT
	 * describes on top of the fixed world (the counterpart of
	 * {@link WasmServeComponentBuilder#additionalImports}).
	 * @param imports the full import list
	 * @return the additional user imports
	 */
	static List<WasmComponentImportCompiler.Import> additionalImports(
			List<WasmComponentImportCompiler.Import> imports) {
		final List<WasmComponentImportCompiler.Import> out = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (!FIXED_BLOCK_IFACES.containsKey(imported.ifaceId())) {
				out.add(imported);
			}
		}
		return out;
	}

	// A block-bound interface can only alias the member fields its instance type
	// actually declares; anything else would only fail component validation downstream
	// with a byte offset, so say it in words here.
	static void validateFixedMembers(List<WasmComponentImportCompiler.Import> fixed) {
		for (WasmComponentImportCompiler.Import imported : fixed) {
			java.util.Set<String> allowed = FIXED_BLOCK_IFACES.get(imported.ifaceId());
			if (allowed == null) {
				continue;
			}
			final List<String> bound = new java.util.ArrayList<>();
			imported.decls().forEach(d -> bound.add(d.field()));
			imported.calls().forEach(call -> bound.add(call.field()));
			// Async ALIAS built-ins (stdin.lisp's stdin-stream-read &c) are fine on a
			// block-bound interface: each is typed by a component-level stream/future
			// type derived from the WIT and aliases nothing out of the block instance.
			// Drops and task-returns would project the block instance's own types, so
			// they stay rejected.
			if (!imported.drops().isEmpty() || !imported.taskReturns().isEmpty()) {
				throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
						+ "': this interface is part of the component's fixed WASI surface and only its functions "
						+ allowed + " (and async type-alias built-ins) can be bound from it");
			}
			for (String field : bound) {
				if (!allowed.contains(field)) {
					throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
							+ "' cannot bind '" + field + "': this interface is part of the component's fixed WASI "
							+ "surface, which declares only " + allowed);
				}
			}
		}
	}

	// A user import must not name an interface the component's own WASI surface already
	// imports: the component would carry that instance import name TWICE, which is
	// invalid
	// -- and nothing downstream says so in words (wasmtime and wasm-tools reject the
	// module
	// at load time with a byte offset). Which interfaces those are depends on what the
	// program uses, so the check is here, where the blob variant is finally known.
	private static void rejectAdapterImportCollisions(List<WasmComponentImportCompiler.Import> imports,
			String variant) {
		if (imports.isEmpty()) {
			return;
		}
		final java.util.Set<String> wasiSurface = new java.util.LinkedHashSet<>();
		for (WitItem item : WasiWitDefinitions.document(variant).world().items()) {
			if (item instanceof WitItem.ImportRef importRef) {
				wasiSurface.add(importRef.target().toString());
			}
		}
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (wasiSurface.contains(imported.ifaceId())) {
				throw new UnsupportedOperationException("rontolisp:wit-import cannot bind '" + imported.ifaceId()
						+ "': this component already imports that interface as part of its own WASI surface, and a "
						+ "component cannot import the same interface twice. Either drop the built-in that carries it "
						+ "and drive the interface through the WIT binding, or bind a different interface");
			}
		}
	}

	/**
	 * Emits the user WIT-interface import wiring: one component <strong>type</strong>
	 * (the imported instance's type) + <strong>import</strong> per interface, an
	 * <strong>alias</strong> per bound function, a {@code canon lower} per function
	 * (memory 0 / realloc = the shared memory module's {@code cabi_realloc} = core func 0
	 * / UTF-8, exactly when the call touches linear memory), and one synthesized core
	 * instance per interface whose export names match the core module's import fields.
	 * Emits nothing when {@code imports} is empty.
	 * @param c the component writer
	 * @param imports the user interface imports
	 * @param nextType the first free component type index
	 * @param firstImportInstance the first free component instance index (right after the
	 * variant's fixed import instances)
	 * @param nextComponentFunc the first free component function index
	 * @param nextCoreFunc the first free core function index
	 */
	/**
	 * What {@link #appendUserImports} actually consumed of each index space -- the SINGLE
	 * source of truth every downstream fixed index shifts by. Asyncs make the type count
	 * derivation-dependent (a future's payload chain declares a data-dependent number of
	 * component types), so a static pre-count would be the "validates while lifting the
	 * wrong function" hazard incarnate; the emission reports what it did instead.
	 *
	 * @param types component type indices consumed (instance types + projections +
	 * async-type declaration chains)
	 * @param componentFuncs component function indices consumed (one alias per bound
	 * function; drops and asyncs consume none)
	 * @param coreFuncs core function indices consumed (one {@code canon lower} per bound
	 * function + one {@code canon resource.drop} per drop + one async built-in per bound
	 * async op)
	 */
	record Appended(int types, int componentFuncs, int coreFuncs) {

		static final Appended NONE = new Appended(0, 0, 0);

	}

	static Appended appendUserImports(ComponentWriter c, List<WasmComponentImportCompiler.Import> imports, int nextType,
			int firstImportInstance, int nextComponentFunc, int nextCoreFunc) {
		if (imports.isEmpty()) {
			return Appended.NONE;
		}
		// The imports are already in dependency order (WasmComponentImportCompiler
		// .inDependencyOrder, applied once where they are collected), so an interface is
		// imported before any interface that uses its resources -- which is what lets the
		// projection below name an instance that already exists.
		final java.util.Map<String, Integer> instanceOf = new java.util.LinkedHashMap<>();
		int instIdx = firstImportInstance;
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (instanceOf.put(imported.ifaceId(), instIdx++) != null) {
				throw new UnsupportedOperationException(
						"the component already imports the WIT interface '" + imported.ifaceId() + "'");
			}
		}
		// Which resources each interface must EXPORT because another one uses them: a
		// type
		// can only be projected out of an instance that exports it. This is why an
		// interface may be imported with no bound functions at all (wasi:io/error exists
		// in a fetch component purely to own the `error` resource wasi:io/streams' own
		// `stream-error` carries), and it is knowable only here, with every import in
		// hand.
		final java.util.Map<String, java.util.Set<String>> provides = new java.util.LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			for (WitComponentTypeEncoder.ForeignResource foreign : WitComponentTypeEncoder
				.foreignResourcesOf(imported)) {
				provides.computeIfAbsent(foreign.ownerIfaceId(), id -> new java.util.LinkedHashSet<>())
					.add(foreign.resource());
			}
			// A DROPPED resource must be declared too: `canon resource.drop` projects it
			// out
			// of this instance, and the encoder would otherwise never declare it -- it
			// declares a resource only when a bound function's signature reaches one, and
			// a
			// program may bind nothing but the drop.
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				provides.computeIfAbsent(imported.ifaceId(), id -> new java.util.LinkedHashSet<>())
					.add(drop.resource());
			}
			// A resource an ASYNC type's payload chain reaches must be declared too: the
			// component-level future/stream type points at the projected resource, and
			// the projection can only link against a name the instance type exports.
			for (WasmComponentImportCompiler.Async async : distinctAsyncTypes(imported)) {
				final List<WitComponentTypeEncoder.ForeignResource> reached = new java.util.ArrayList<>();
				WitComponentLevelTypes.collectResources(imported.resolver(), async.abi(), async.type(), reached);
				for (WitComponentTypeEncoder.ForeignResource resource : reached) {
					provides.computeIfAbsent(resource.ownerIfaceId(), id -> new java.util.LinkedHashSet<>())
						.add(resource.resource());
				}
			}
			// A task-return's declared result type reaches resources the same way (its
			// component-level type is derived by the same machinery).
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				final List<WitComponentTypeEncoder.ForeignResource> reached = new java.util.ArrayList<>();
				WitComponentLevelTypes.collectResources(imported.resolver(), tr.abi(), tr.type(), reached);
				for (WitComponentTypeEncoder.ForeignResource resource : reached) {
					provides.computeIfAbsent(resource.ownerIfaceId(), id -> new java.util.LinkedHashSet<>())
						.add(resource.resource());
				}
			}
		}
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (imported.decls().isEmpty() && imported.calls().isEmpty() && !provides.containsKey(imported.ifaceId())) {
				throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
						+ "': the program calls none of its functions, and no other imported interface uses its types");
			}
		}
		// "<owner iface id>#<resource>" -> the component type index it was projected to.
		final java.util.Map<String, Integer> outerOf = new java.util.LinkedHashMap<>();
		final List<byte[]> funcAliases = new java.util.ArrayList<>();
		final List<byte[]> lowers = new java.util.ArrayList<>();
		final List<byte[]> coreInstances = new java.util.ArrayList<>();
		final java.util.Map<String, CoreExports> exports = new java.util.LinkedHashMap<>();
		int typeIdx = nextType;
		int compFunc = nextComponentFunc;
		int coreFunc = nextCoreFunc;
		for (WasmComponentImportCompiler.Import imported : imports) {
			// Project every resource this interface USES from another one out of that
			// interface's instance, into this component's type index space. The instance
			// type below points at these with an `alias outer` instead of re-declaring
			// the resource -- a resource is nominal (WitComponentTypeEncoder's comment).
			final List<byte[]> typeAliases = new java.util.ArrayList<>();
			for (WitComponentTypeEncoder.ForeignResource foreign : WitComponentTypeEncoder
				.foreignResourcesOf(imported)) {
				String key = foreign.ownerIfaceId() + "#" + foreign.resource();
				if (outerOf.containsKey(key)) {
					continue;
				}
				Integer ownerInstance = instanceOf.get(foreign.ownerIfaceId());
				if (ownerInstance == null) {
					throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
							+ "' cannot bind the resource '" + foreign.resource() + "', which it uses from '"
							+ foreign.ownerIfaceId()
							+ "': a component-model resource is its defining interface's type, so that interface must "
							+ "be imported too. Add (rontolisp:wit-import ... :interface \"" + foreign.ownerIfaceId()
							+ "\")");
				}
				typeAliases.add(ComponentWriter.aliasInstanceType(ownerInstance, foreign.resource()));
				outerOf.put(key, typeIdx++);
			}
			if (!typeAliases.isEmpty()) {
				c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(typeAliases));
			}
			byte[] instanceType = WitComponentTypeEncoder.encode(imported,
					(ownerId, resource) -> Objects.requireNonNull(outerOf.get(ownerId + "#" + resource),
							() -> ownerId + "#" + resource),
					provides.getOrDefault(imported.ifaceId(), java.util.Set.of()));
			c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(instanceType)));
			c.rawSection(ComponentWriter.SEC_IMPORT,
					ComponentWriter.vec(List.of(ComponentWriter.importInstance(imported.ifaceId(), typeIdx++))));
			final int instance = Objects.requireNonNull(instanceOf.get(imported.ifaceId()));
			final List<String> fields = new java.util.ArrayList<>();
			final List<Integer> coreIndices = new java.util.ArrayList<>();
			for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, decl.field()));
				lowers.add(WasmComponentImportCompiler.needsMemory(decl)
						? ComponentWriter.canonLowerMemoryReallocUtf8(compFunc, 0, 0)
						: ComponentWriter.canonLower(compFunc));
				fields.add(decl.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			// Async func members follow the sync ones: aliased like any bound function,
			// but canon-lowered with the `async` option -- the core call returns the
			// packed (subtask << 4) | status immediately, and the memory options cover
			// the always-indirect result.
			for (WasmComponentImportCompiler.AsyncCall call : imported.calls()) {
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, call.field()));
				lowers.add(ComponentWriter.canonLowerAsyncMemoryReallocUtf8(compFunc, 0, 0));
				fields.add(call.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			exports.put(imported.ifaceId(), new CoreExports(fields, coreIndices));
		}
		// Resource drops. `canon resource.drop` takes the resource's type in THIS
		// component's index space and produces a CORE function directly -- no instance
		// function to alias, no `canon lower`. So it is a second emission kind, and the
		// two
		// counts the user imports cost (component functions, core functions) stop being
		// the
		// same number. It needs the resource projected out of the instance that owns it,
		// which the loop above may already have done for a `use`d resource: reuse that
		// projection rather than making a second, identical one.
		final List<byte[]> dropAliases = new java.util.ArrayList<>();
		final List<byte[]> dropCanons = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			CoreExports core = Objects.requireNonNull(exports.get(imported.ifaceId()));
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				String key = imported.ifaceId() + "#" + drop.resource();
				Integer outer = outerOf.get(key);
				if (outer == null) {
					dropAliases.add(ComponentWriter.aliasInstanceType(
							Objects.requireNonNull(instanceOf.get(imported.ifaceId())), drop.resource()));
					outer = typeIdx++;
					outerOf.put(key, outer);
				}
				dropCanons.add(ComponentWriter.canonResourceDrop(outer));
				core.fields().add(drop.field());
				core.coreIndices().add(coreFunc++);
			}
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(funcAliases));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
		if (!dropAliases.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(dropAliases));
		}
		if (!dropCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(dropCanons));
		}
		// Async built-ins. A `canon stream.*`/`future.*` is the THIRD emission kind: a
		// CORE function (no component-func alias, like a drop), but typed by a
		// COMPONENT-LEVEL stream/future type derived from the WIT here -- the
		// data-driven counterpart of the hand-assembled base-adapter async block. The
		// derivation declares the type's whole payload chain (structural types fresh,
		// resources projected through the shared outerOf map), so the type count
		// becomes data-dependent -- which is why this method reports its counts instead
		// of trusting a static pre-count.
		final WitComponentLevelTypes componentTypes = new WitComponentLevelTypes(typeIdx, outerOf,
				ifaceId -> Objects.requireNonNull(instanceOf.get(ifaceId),
						() -> "the WIT interface '" + ifaceId + "' owning an async type's resource is not imported"));
		final List<byte[]> asyncCanons = new java.util.ArrayList<>();
		final java.util.Map<String, java.util.List<Integer>> asyncCoreOf = new java.util.LinkedHashMap<>();
		final java.util.Map<String, java.util.List<String>> asyncFieldsOf = new java.util.LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (imported.asyncs().isEmpty()) {
				continue;
			}
			final java.util.Map<String, Integer> typeOfAlias = new java.util.LinkedHashMap<>();
			for (WasmComponentImportCompiler.Async async : distinctAsyncTypes(imported)) {
				typeOfAlias.put(async.alias(), componentTypes.indexOf(imported.resolver(), async.abi(), async.type()));
			}
			// Two splices of one interface (mergeByIface) repeat the same ops: one canon
			// per FIELD, exactly like the deduplicated (module, field) core imports both
			// wrappers call through.
			final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
			final List<Integer> coreIndices = asyncCoreOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			final List<String> fields = asyncFieldsOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				if (!seen.add(async.field())) {
					continue;
				}
				int type = Objects.requireNonNull(typeOfAlias.get(async.alias()));
				asyncCanons.add(asyncCanon(async, type));
				fields.add(async.field());
				coreIndices.add(coreFunc++);
			}
		}
		// Task-return built-ins (typed by their alias's derived component-level type)
		// and the waitable-set builtins each async-calling interface shares. Emitted
		// into the same canon run as the async built-ins; the type derivation must
		// precede the flush below.
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (imported.taskReturns().isEmpty() && imported.calls().isEmpty() && imported.asyncs().isEmpty()) {
				continue;
			}
			final List<Integer> coreIndices = asyncCoreOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			final List<String> fields = asyncFieldsOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			final java.util.Set<String> seen = new java.util.LinkedHashSet<>(fields);
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				if (!seen.add(tr.field())) {
					continue;
				}
				int type = componentTypes.indexOf(imported.resolver(), tr.abi(), tr.type());
				asyncCanons.add(ComponentWriter.canonTaskReturnTypeMemoryUtf8(type, 0));
				fields.add(tr.field());
				coreIndices.add(coreFunc++);
			}
			if (!imported.calls().isEmpty() || !imported.asyncs().isEmpty()) {
				// async calls await through the waitable-set; the async (non-blocking)
				// stream/future built-in wrappers park on it when BLOCKED
				for (String field : WasmComponentImportCompiler.WAITABLE_FIELDS) {
					if (!seen.add(field)) {
						continue;
					}
					asyncCanons.add(waitableCanon(field));
					fields.add(field);
					coreIndices.add(coreFunc++);
				}
			}
		}
		typeIdx = componentTypes.nextType();
		componentTypes.flush(c);
		if (!asyncCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(asyncCanons));
		}
		for (WasmComponentImportCompiler.Import imported : imports) {
			CoreExports core = Objects.requireNonNull(exports.get(imported.ifaceId()));
			List<Integer> asyncCores = asyncCoreOf.get(imported.ifaceId());
			if (asyncCores != null) {
				core.fields().addAll(Objects.requireNonNull(asyncFieldsOf.get(imported.ifaceId())));
				core.coreIndices().addAll(asyncCores);
			}
			coreInstances.add(ComponentWriter.coreInstanceFromFuncs(core.fields(), core.coreIndices()));
		}
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(coreInstances));
		return new Appended(typeIdx - nextType, compFunc - nextComponentFunc, coreFunc - nextCoreFunc);
	}

	/**
	 * The outcome of lowering a set of block-declared interfaces: the synthesized core
	 * instances are already emitted; {@code coreInstanceOf} maps each interface to its
	 * core instance index (feeding the core module's instantiation arguments by name).
	 * The {@code next*} cursors let the user imports and the export wiring continue
	 * without a hardcoded count.
	 */
	record FixedIo(java.util.Map<String, Integer> coreInstanceOf, int nextComponentFunc, int nextCoreFunc, int nextType,
			int nextCoreInstance) {
	}

	/**
	 * Lowers interfaces the import block ALREADY DECLARES by aliasing each bound function
	 * out of the block's import instance and canon-lowering it (async funcs with the
	 * async option), then the resource drops, the stream/future built-ins bound off the
	 * type aliases, the task-return built-ins and the waitable-set builtins -- the
	 * {@link #appendUserImports} emission kinds, against the block's instances
	 * (appendUserImports MINUS the instance-type / importInstance emission). Bound
	 * functions are deduplicated by canonical field name (two splices of one interface
	 * may repeat a function). Emits nothing when {@code fixed} is empty.
	 * @param c the component writer
	 * @param fixed the block-declared interfaces to lower
	 * @param instanceOf the block import-instance index of each fixed interface
	 * @param projected resource projections the block pre-declares, keyed
	 * {@code "<iface>#<resource>"} (shared with, and extended by, the drop / async-type
	 * machinery)
	 * @param firstComponentFunc the first free component function index
	 * @param firstCoreFunc the first free core function index
	 * @param firstType the first free component type index
	 * @param firstCoreInstance the first free core instance index
	 */
	static FixedIo lowerFixedFromBlock(ComponentWriter c, List<WasmComponentImportCompiler.Import> fixed,
			java.util.Map<String, Integer> instanceOf, java.util.Map<String, Integer> projected, int firstComponentFunc,
			int firstCoreFunc, int firstType, int firstCoreInstance) {
		if (fixed.isEmpty()) {
			// Emit nothing and shift nothing, so a component without a block-bound
			// interface stays byte-identical.
			return new FixedIo(new java.util.LinkedHashMap<>(), firstComponentFunc, firstCoreFunc, firstType,
					firstCoreInstance);
		}
		final List<byte[]> funcAliases = new java.util.ArrayList<>();
		final List<byte[]> lowers = new java.util.ArrayList<>();
		final List<byte[]> dropAliases = new java.util.ArrayList<>();
		final List<byte[]> dropCanons = new java.util.ArrayList<>();
		final java.util.Map<String, List<String>> names = new java.util.LinkedHashMap<>();
		final java.util.Map<String, List<Integer>> indices = new java.util.LinkedHashMap<>();
		int compFunc = firstComponentFunc;
		int coreFunc = firstCoreFunc;
		int typeIdx = firstType;
		// Pass A: the bound functions (sync then async), deduplicated by field within
		// each interface.
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final int instance = Objects.requireNonNull(instanceOf.get(imported.ifaceId()));
			final List<String> fieldNames = new java.util.ArrayList<>();
			final List<Integer> coreIndices = new java.util.ArrayList<>();
			final java.util.Set<String> seen = new java.util.HashSet<>();
			for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
				if (!seen.add(decl.field())) {
					continue;
				}
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, decl.field()));
				lowers.add(WasmComponentImportCompiler.needsMemory(decl)
						? ComponentWriter.canonLowerMemoryReallocUtf8(compFunc, 0, 0)
						: ComponentWriter.canonLower(compFunc));
				fieldNames.add(decl.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			for (WasmComponentImportCompiler.AsyncCall call : imported.calls()) {
				if (!seen.add(call.field())) {
					continue;
				}
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, call.field()));
				lowers.add(ComponentWriter.canonLowerAsyncMemoryReallocUtf8(compFunc, 0, 0));
				fieldNames.add(call.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			names.put(imported.ifaceId(), fieldNames);
			indices.put(imported.ifaceId(), coreIndices);
		}
		// Pass B: the resource drops, deduplicated by resource, reusing an
		// already-projected resource type.
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			final List<Integer> coreIndices = Objects.requireNonNull(indices.get(imported.ifaceId()));
			final java.util.Set<String> seenDrops = new java.util.HashSet<>(fieldNames);
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				if (!seenDrops.add(drop.field())) {
					continue;
				}
				final String key = imported.ifaceId() + "#" + drop.resource();
				Integer type = projected.get(key);
				if (type == null) {
					dropAliases.add(ComponentWriter.aliasInstanceType(
							Objects.requireNonNull(instanceOf.get(imported.ifaceId())), drop.resource()));
					type = typeIdx++;
					projected.put(key, type);
				}
				dropCanons.add(ComponentWriter.canonResourceDrop(type));
				fieldNames.add(drop.field());
				coreIndices.add(coreFunc++);
			}
		}
		if (!funcAliases.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(funcAliases));
		}
		if (!lowers.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
		}
		if (!dropAliases.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(dropAliases));
		}
		if (!dropCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(dropCanons));
		}
		// Pass C: the async built-ins (typed by component-level types derived from the
		// WIT, resources projected through the shared map), the task-return built-ins
		// and the waitable-set builtins of each async-calling interface.
		final WitComponentLevelTypes componentTypes = new WitComponentLevelTypes(typeIdx, projected,
				ifaceId -> Objects.requireNonNull(instanceOf.get(ifaceId),
						() -> "the WIT interface '" + ifaceId + "' owning an async type's resource is not imported"));
		final List<byte[]> asyncCanons = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			final List<Integer> coreIndices = Objects.requireNonNull(indices.get(imported.ifaceId()));
			final java.util.Set<String> seen = new java.util.LinkedHashSet<>(fieldNames);
			final java.util.Map<String, Integer> typeOfAlias = new java.util.LinkedHashMap<>();
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				typeOfAlias.computeIfAbsent(async.alias(),
						a -> componentTypes.indexOf(imported.resolver(), async.abi(), async.type()));
			}
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				if (!seen.add(async.field())) {
					continue;
				}
				int type = Objects.requireNonNull(typeOfAlias.get(async.alias()));
				asyncCanons.add(asyncCanon(async, type));
				fieldNames.add(async.field());
				coreIndices.add(coreFunc++);
			}
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				if (!seen.add(tr.field())) {
					continue;
				}
				int type = componentTypes.indexOf(imported.resolver(), tr.abi(), tr.type());
				asyncCanons.add(ComponentWriter.canonTaskReturnTypeMemoryUtf8(type, 0));
				fieldNames.add(tr.field());
				coreIndices.add(coreFunc++);
			}
			if (!imported.calls().isEmpty() || !imported.asyncs().isEmpty()) {
				// async calls await through the waitable-set; the async (non-blocking)
				// stream/future built-in wrappers park on it when BLOCKED
				for (String field : WasmComponentImportCompiler.WAITABLE_FIELDS) {
					if (!seen.add(field)) {
						continue;
					}
					asyncCanons.add(waitableCanon(field));
					fieldNames.add(field);
					coreIndices.add(coreFunc++);
				}
			}
		}
		typeIdx = componentTypes.nextType();
		componentTypes.flush(c);
		if (!asyncCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(asyncCanons));
		}
		final List<byte[]> coreInstanceDefs = new java.util.ArrayList<>();
		final java.util.Map<String, Integer> coreInstanceOf = new java.util.LinkedHashMap<>();
		int coreInstance = firstCoreInstance;
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			if (fieldNames.isEmpty()) {
				continue;
			}
			coreInstanceDefs.add(ComponentWriter.coreInstanceFromFuncs(fieldNames,
					Objects.requireNonNull(indices.get(imported.ifaceId()))));
			coreInstanceOf.put(imported.ifaceId(), coreInstance++);
		}
		if (!coreInstanceDefs.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(coreInstanceDefs));
		}
		return new FixedIo(coreInstanceOf, compFunc, coreFunc, typeIdx, coreInstance);
	}

	// The first bound async op per ALIAS: the ops of one alias share one stream/future
	// type, so the type derivation runs once per alias.
	private static List<WasmComponentImportCompiler.Async> distinctAsyncTypes(
			WasmComponentImportCompiler.Import imported) {
		final java.util.Map<String, WasmComponentImportCompiler.Async> byAlias = new java.util.LinkedHashMap<>();
		for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
			byAlias.putIfAbsent(async.alias(), async);
		}
		return new java.util.ArrayList<>(byAlias.values());
	}

	// The canon entry of one async built-in, typed by the derived component-level type.
	// Memory options mirror the hand-assembled base block: reads and writes stage
	// through the shared memory (core memory 0); a future.read whose payload carries a
	// string/list<u8> additionally needs realloc (the shared memory's cabi_realloc =
	// core func 0) for the host-staged bytes.
	static byte[] asyncCanon(WasmComponentImportCompiler.Async async, int type) {
		return switch (async.op()) {
			case NEW -> async.stream() ? ComponentWriter.canonStreamNew(type) : ComponentWriter.canonFutureNew(type);
			// reads/writes are the ASYNC (non-blocking) built-in variants of base
			// component-model-async: the generated wrapper parks on the interface's
			// waitable-set when one reports BLOCKED (no gated feature involved)
			case READ -> async.stream() ? ComponentWriter.canonStreamReadAsync(type, 0)
					: (WasmComponentImportCompiler.asyncReadNeedsRealloc(async)
							? ComponentWriter.canonFutureReadAsync(type, 0, 0)
							: ComponentWriter.canonFutureReadAsync(type, 0));
			case WRITE -> async.stream() ? ComponentWriter.canonStreamWriteAsync(type, 0)
					: ComponentWriter.canonFutureWriteAsync(type, 0);
			case DROP_READABLE -> async.stream() ? ComponentWriter.canonStreamDropReadable(type)
					: ComponentWriter.canonFutureDropReadable(type);
			case DROP_WRITABLE -> async.stream() ? ComponentWriter.canonStreamDropWritable(type)
					: ComponentWriter.canonFutureDropWritable(type);
		};
	}

	// The canon entry of one waitable-set builtin (host-verified core signatures; the
	// wait's event payload is written through the shared memory, core memory 0).
	static byte[] waitableCanon(String field) {
		return switch (field) {
			case WasmComponentImportCompiler.FIELD_WAITABLE_SET_NEW -> ComponentWriter.canonWaitableSetNew();
			case WasmComponentImportCompiler.FIELD_WAITABLE_SET_WAIT -> ComponentWriter.canonWaitableSetWait(0);
			case WasmComponentImportCompiler.FIELD_WAITABLE_SET_DROP -> ComponentWriter.canonWaitableSetDrop();
			case WasmComponentImportCompiler.FIELD_WAITABLE_JOIN -> ComponentWriter.canonWaitableJoin();
			case WasmComponentImportCompiler.FIELD_SUBTASK_DROP -> ComponentWriter.canonSubtaskDrop();
			default -> throw new IllegalStateException("not a waitable builtin field: " + field);
		};
	}

	// The core functions an interface's synthesized core instance exports, by the field
	// name the core module imports them under. Filled in two passes (the lowered
	// functions,
	// then the drops), so it outlives the loop that starts it.
	private record CoreExports(List<String> fields, List<Integer> coreIndices) {
	}

	// The rontolisp core instance's instantiation arguments: mem + the fixed adapter
	// names, then one argument per user interface (module name = the interface's
	// canonical id, satisfied by its synthesized core instance starting right after the
	// adapter instance).
	static byte[] rontolispInstantiate(int moduleIndex, List<String> fixedNames, List<Integer> fixedInstances,
			List<WasmComponentImportCompiler.Import> imports, int firstUserCoreInstance) {
		final List<String> names = new java.util.ArrayList<>(fixedNames);
		final List<Integer> instances = new java.util.ArrayList<>(fixedInstances);
		int idx = firstUserCoreInstance;
		for (WasmComponentImportCompiler.Import imported : imports) {
			names.add(imported.ifaceId());
			instances.add(idx++);
		}
		return ComponentWriter.coreInstanceInstantiate(moduleIndex, names, instances);
	}

	/**
	 * Assemble the {@code --no-wasi} REACTOR component: the single rontolisp core module
	 * -- which imports <strong>nothing</strong> (its eleven
	 * {@code wasi_snapshot_preview1} slots are internal stubs and it declares its own
	 * memory) and runs its top-level forms from its core <em>start section</em> at
	 * instantiation -- instantiated with no arguments and wrapped as
	 * {@code alias / type / lift / export} per {@code wasm-export}, exactly the
	 * {@link NoGcWasmComponentBuilder} print-free shape on the GC backend. There is no
	 * import block, no WASI adapter, no shared memory module and no {@code wasi:cli/run}
	 * export, with or without {@code --optimize}: the zero-import surface is the flag's
	 * contract, not a narrowing outcome. A {@code :string}/{@code :s-expr} boundary lifts
	 * through the canonical string ABI over the core's <em>own</em> exported memory and
	 * its own {@code cabi_realloc} / {@code cabi_post_*} helpers
	 * ({@link #appendFuncExports} aliases them off the rontolisp instance -- instance 0
	 * here).
	 * @param coreModule the rontolisp core module compiled in component no-wasi mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @return the reactor component binary
	 */
	static byte[] buildReactor(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports) {
		final ComponentWriter c = new ComponentWriter();
		// Core module 0 = the whole program; core instance 0 = instantiate it with no
		// arguments (it has no imports). The engine runs its start section here, so the
		// top-level initializers are in place before any lifted export can be called.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// The canonical string options name core memory 0: on this shape that is the
		// core's own exported memory (there is no shared memory module), aliased before
		// the export wiring so it takes index 0. A scalar-only reactor aliases nothing.
		if (funcExports.stream().anyMatch(WasmExportCompiler::usesMemory)) {
			c.rawSection(ComponentWriter.SEC_ALIAS,
					ComponentWriter.vec(List.of(ComponentWriter.aliasCoreMemory(0, "memory"))));
		}
		// Every index space starts from zero: no run wiring, no fixed surface, no user
		// imports precede the export wiring (the +1/+1/+2 cursor shifts of the base
		// build are the run alias/lift/instance-pair, none of which exist here).
		appendFuncExports(c, funcExports, 0, 0, 0, 0, 0);
		return c.toByteArray();
	}

	/**
	 * Assemble the serve-variant component for a {@code rontolisp:http-handler} program:
	 * wrap the rontolisp core (compiled in serve mode, exporting http.lisp's
	 * {@code handle} and its real callback {@code async_cb}) with the preview1 bridge
	 * ({@code adapter-http-server-p1.wasm}: random / clocks / stdout-stderr over the 0.3
	 * service-world interfaces) and lift {@code handle} as the callback-async
	 * {@code wasi:http/handler@0.3.0} export -- a pending handler returns the packed WAIT
	 * code and the host drives it through the callback. The HTTP glue is Lisp
	 * (http.lisp), so there is no hand-written serve adapter. Runs under
	 * {@code wasmtime serve} (wasmtime 46+; everything is base component-model-async).
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @return the wasi:http@0.3.0 handler component binary
	 */
	public static byte[] buildServe(byte[] coreModule) {
		return buildServe(coreModule, List.of());
	}

	/**
	 * Assemble the serve-variant component, additionally importing the given interfaces.
	 * ONE shape serves plain serve AND serve+fetch (the 0.3 service world always imports
	 * {@code client}); a served handler whose state lives in a real store adds a
	 * {@code rontolisp:wit-import} (e.g. wasi:keyvalue), wired by
	 * {@link #appendUserImports}.
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param imports the interface imports (fixed wasi:http from http.lisp, plus any user
	 * {@code rontolisp:wit-import}; empty for none)
	 * @return the wasi:http@0.3.0 handler component binary
	 */
	static byte[] buildServe(byte[] coreModule, List<WasmComponentImportCompiler.Import> imports) {
		// Only the ADDITIONAL rontolisp:wit-import interfaces are checked for a
		// double-import collision -- the fixed wasi:http surface is declared by the
		// import block and lowered from it, not re-imported. ONE world serves plain
		// serve and serve+fetch (the 0.3 service world always imports client).
		rejectAdapterImportCollisions(WasmServeComponentBuilder.additionalImports(imports),
				WitEmitter.VARIANT_HTTP_SERVER);
		return WasmServeComponentBuilder.build(coreModule, imports);
	}

	/**
	 * What a program's core module still needs of the fixed WASI surface, once
	 * {@code --optimize} has told the truth about which {@code wasi_snapshot_preview1}
	 * functions it imports. Everything the base wrapper emits follows from this, and so
	 * does the world {@code --emit-wit} prints -- which is why it is computed once, by a
	 * pure function both callers run.
	 *
	 * @param adapter the adapter core module, narrowed to the entry points the core binds
	 * and shaken
	 * @param members the {@link #W_MEMBERS} keys the shaken adapter still imports, in
	 * declaration order
	 * @param funcs the {@link #BLOCK_FUNCS} keys those members lower
	 * @param types the type keys those members name, closed over the definitions' own
	 * dependencies
	 * @param interfaces the block interfaces all of that reaches, in block order
	 */
	record FixedSurface(byte[] adapter, java.util.LinkedHashSet<String> members, java.util.LinkedHashSet<String> funcs,
			java.util.LinkedHashSet<String> types, java.util.LinkedHashSet<String> interfaces) {
	}

	/**
	 * Narrows the fixed WASI surface to what the core module reaches.
	 * <p>
	 * The chain is one-directional and every link is exact. The core's surviving
	 * {@code wasi_snapshot_preview1} imports name the adapter entry points that still
	 * have a caller; {@link WasmExports#retain} makes them the adapter's only exports
	 * (choosing the narrow implementation of {@code fd_write} / {@code fd_read} the
	 * program can still reach, see {@link #narrowImpl}) and {@link WasmTreeShaker#shake}
	 * then deletes everything unreachable from them -- including the adapter's own
	 * {@code "w"} imports. What is left of {@code "w"} decides which WASI functions are
	 * lowered, which component types are declared, and finally which interfaces the
	 * import block still has to declare.
	 * <p>
	 * Without {@link Narrowing#shake()} the core keeps all eleven imports, so every step
	 * is the identity and the component is the one this builder always emitted.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param blockBound the block-declared interfaces the program binds directly
	 * (wait.lisp's monotonic-clock &c) -- they are reached by the Lisp library rather
	 * than by the adapter, so they join the interface set here
	 * @param narrowing how far to narrow, and the source-derived facts the core module's
	 * bytes cannot answer
	 * @return the narrowed surface
	 */
	static FixedSurface fixedSurface(byte[] coreModule, List<WasmComponentImportCompiler.Import> blockBound,
			Narrowing narrowing) {
		final java.util.LinkedHashSet<String> corePreview1 = WasmImports.functionFields(coreModule,
				"wasi_snapshot_preview1");
		byte[] adapter = ADAPTER_MODULE;
		if (narrowing.shake()) {
			final boolean files = corePreview1.contains("path_open");
			final java.util.LinkedHashMap<String, String> keep = new java.util.LinkedHashMap<>();
			for (String preview1 : PREVIEW1_FUNCS) {
				if (corePreview1.contains(preview1)) {
					keep.put(narrowImpl(preview1, files, narrowing.reachesStandardError()), preview1);
				}
			}
			adapter = WasmTreeShaker.shake(WasmExports.retain(ADAPTER_MODULE, keep));
		}
		final java.util.LinkedHashSet<String> bound = WasmImports.functionFields(adapter, "w");
		final java.util.LinkedHashSet<String> members = new java.util.LinkedHashSet<>();
		final java.util.LinkedHashSet<String> funcs = new java.util.LinkedHashSet<>();
		final java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
		for (java.util.Map.Entry<String, WMember> entry : W_MEMBERS.entrySet()) {
			if (!bound.contains(entry.getKey())) {
				continue;
			}
			members.add(entry.getKey());
			if (entry.getValue().func() != null) {
				funcs.add(entry.getValue().func());
			}
			types.addAll(entry.getValue().types());
		}
		if (!bound.equals(members)) {
			final java.util.LinkedHashSet<String> unknown = new java.util.LinkedHashSet<>(bound);
			unknown.removeAll(members);
			throw new IllegalStateException(
					"the component adapter imports w." + unknown + ", which the fixed WASI wiring does not declare");
		}
		// The run export's own types are always needed: the lift names them whatever the
		// program does.
		types.add(T_RUN_FUNC);
		closeOverTypeDependencies(types);
		final java.util.LinkedHashSet<String> interfaces = new java.util.LinkedHashSet<>();
		for (String func : funcs) {
			interfaces.add(at(BLOCK_FUNCS, func).iface());
		}
		for (String type : types) {
			BlockFunc projection = PROJECTED_TYPES.get(type);
			if (projection != null) {
				interfaces.add(projection.iface());
			}
		}
		for (WasmComponentImportCompiler.Import imported : blockBound) {
			interfaces.add(imported.ifaceId());
		}
		return new FixedSurface(adapter, members, funcs, types, interfaces);
	}

	// A defined type names other types; a type kept for one member keeps those too.
	private static void closeOverTypeDependencies(java.util.LinkedHashSet<String> types) {
		boolean grew = true;
		while (grew) {
			grew = false;
			for (String type : List.copyOf(types)) {
				DefinedType defined = DEFINED_TYPES.get(type);
				if (defined != null && types.addAll(defined.needs())) {
					grew = true;
				}
			}
		}
	}

	/**
	 * Assemble the base WASI 0.3 component (no {@code rontolisp:fetch}).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @param fixed the block-declared interfaces to bind FROM the block (wait.lisp's
	 * wasi:clocks/monotonic-clock; empty for none)
	 * @param imports the genuine user interface imports
	 * @param narrowing how far to narrow the fixed surface ({@code --optimize} plus the
	 * source-derived facts the core module's bytes cannot answer)
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildBase(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports,
			List<WasmComponentImportCompiler.Import> fixed, List<WasmComponentImportCompiler.Import> imports,
			Narrowing narrowing) {
		final int userIfaces = imports.size();
		final ComponentWriter c = new ComponentWriter();
		final FixedSurface surface = fixedSurface(coreModule, fixed, narrowing);
		// The imported WASI 0.3 interfaces, pruned to the ones the surface above still
		// reaches. Their component instance indices, and the first free component type
		// index after the block, come back from the prune -- nothing here may assume
		// them.
		final ComponentImportBlock.Pruned block = ComponentImportBlock.parse(IMPORT_BLOCK).prune(surface.interfaces());
		final java.util.Map<String, Integer> instanceOf = block.instanceOf();
		c.writeRaw(block.bytes());
		// Core modules: 0 = shared memory, 1 = adapter, 2 = rontolisp. The shared
		// memory module is sized to fit the rontolisp module's static data / intern
		// pool (its memory-import min-pages declaration), so a program with a large
		// data segment does not trap on the very first data-segment write when the
		// mem module is instantiated with only its default six pages.
		final boolean sharedRealloc = needsSharedRealloc(surface, fixed, imports);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, memModuleFor(coreModule, sharedRealloc));
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, surface.adapter());
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// ONE alias section, in three index spaces that each advance in declaration
		// order: the shared memory (core memory 0) and, when anything below stages
		// host-owned bytes, cabi_realloc (core func 0 -- SHARED_REALLOC); then the
		// projected types (continuing the block's component type space); then the WASI
		// functions (component funcs from 0). A member that shook out simply leaves a
		// gap nobody names. The format allows repeated sections, so splitting these
		// three groups apart would still be correct -- it would just spend a section
		// header per group for nothing.
		final java.util.Map<String, Integer> typeIndex = new java.util.LinkedHashMap<>();
		final java.util.Map<String, Integer> funcIndex = new java.util.LinkedHashMap<>();
		final List<byte[]> aliases = new java.util.ArrayList<>();
		aliases.add(ComponentWriter.aliasCoreMemory(0, "memory"));
		if (sharedRealloc) {
			aliases.add(ComponentWriter.aliasCoreFunc(0, "cabi_realloc"));
		}
		int nextType = block.typeCount();
		for (java.util.Map.Entry<String, BlockFunc> projection : PROJECTED_TYPES.entrySet()) {
			if (!surface.types().contains(projection.getKey())) {
				continue;
			}
			aliases.add(ComponentWriter.aliasInstanceType(at(instanceOf, projection.getValue().iface()),
					projection.getValue().member()));
			typeIndex.put(projection.getKey(), nextType++);
		}
		int nextComponentFunc = 0;
		for (java.util.Map.Entry<String, BlockFunc> func : BLOCK_FUNCS.entrySet()) {
			if (!surface.funcs().contains(func.getKey())) {
				continue;
			}
			aliases.add(ComponentWriter.aliasInstanceFunc(at(instanceOf, func.getValue().iface()),
					func.getValue().member()));
			funcIndex.put(func.getKey(), nextComponentFunc++);
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
		// The async value/function types the surviving members (and the run lift) name.
		final List<byte[]> types = new java.util.ArrayList<>();
		for (java.util.Map.Entry<String, DefinedType> defined : DEFINED_TYPES.entrySet()) {
			if (!surface.types().contains(defined.getKey())) {
				continue;
			}
			types.add(defined.getValue().encode().apply(typeIndex));
			typeIndex.put(defined.getKey(), nextType++);
		}
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(types));
		// Lower the WASI functions and emit the canonical built-ins, in W_MEMBERS order:
		// core funcs after the aliases above (the shared cabi_realloc took index 0
		// exactly
		// when it was aliased).
		final List<byte[]> canons = new java.util.ArrayList<>();
		final List<String> wNames = new java.util.ArrayList<>();
		final List<Integer> wCoreFuncs = new java.util.ArrayList<>();
		int nextCoreFunc = sharedRealloc ? 1 : 0;
		for (java.util.Map.Entry<String, WMember> member : W_MEMBERS.entrySet()) {
			if (!surface.members().contains(member.getKey())) {
				continue;
			}
			canons.add(member.getValue().encode().apply(funcIndex, typeIndex));
			wNames.add(member.getKey());
			wCoreFuncs.add(nextCoreFunc++);
		}
		if (!canons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(canons));
		}
		// One section, two entries: group the lowered functions for the adapter's "w"
		// import (core instance 1, names matching adapter.wat's imports), then
		// instantiate the adapter itself (core instance 2): mem = instance 0, w =
		// instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(wNames, wCoreFuncs),
						ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		final int runFuncType = at(typeIndex, T_RUN_FUNC);
		// Block-declared interfaces the program binds (wait.lisp's monotonic-clock,
		// stdin.lisp's wasi:cli/stdin, environment.lisp's wasi:cli/environment): lowered
		// FROM the block's own import instances, continuing every index space. Emits
		// nothing when there are none.
		final java.util.Map<String, Integer> blockBound = new java.util.LinkedHashMap<>();
		FIXED_BLOCK_IFACES.keySet().forEach(iface -> {
			Integer instance = instanceOf.get(iface);
			if (instance != null) {
				blockBound.put(iface, instance);
			}
		});
		final FixedIo io = lowerFixedFromBlock(c, fixed, blockBound, new java.util.LinkedHashMap<>(), nextComponentFunc,
				nextCoreFunc, nextType, 3);
		// User WIT-interface imports (rontolisp:wit-import): instance types, import
		// instances (right after the block's own), function aliases and lowered core
		// funcs
		// continue after the fixed surface. Emits nothing when there are none, so every
		// fixed index below shifts by zero -- and what it DID consume of each index space
		// comes back as `user`, the single source every downstream fixed index shifts by.
		final int blockInstances = instanceOf.size();
		final Appended user = appendUserImports(c, imports, io.nextType(), blockInstances, io.nextComponentFunc(),
				io.nextCoreFunc());
		// Instantiate rontolisp (core instance 3 + one per fixed/user interface): mem =
		// instance 0, wasi_snapshot_preview1 = adapter instance 2, plus each fixed
		// interface's block-lowered core instance and each user interface's
		// canon-lowered core instance under its canonical id.
		final List<String> coreNames = new java.util.ArrayList<>(List.of("mem", "wasi_snapshot_preview1"));
		final List<Integer> coreInstances = new java.util.ArrayList<>(List.of(0, 2));
		io.coreInstanceOf().forEach((ifaceId, instance) -> {
			coreNames.add(ifaceId);
			coreInstances.add(instance);
		});
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(rontolispInstantiate(2, coreNames, coreInstances, imports, io.nextCoreInstance()))));
		final int rontolisp = io.nextCoreInstance() + userIfaces;
		// Alias rontolisp's run, then lift it with the async function type (an
		// async-typed
		// sync-ABI lift: the task may block, no gated feature involved).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(rontolisp, "run"))));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter
			.vec(List.of(ComponentWriter.canonLift(io.nextCoreFunc() + user.coreFuncs(), runFuncType))));
		// A component instance (after the block's import instances and the user imports)
		// exporting run, exported as the wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.componentInstanceFromFunc("run", io.nextComponentFunc() + user.componentFuncs()))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter
			.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", blockInstances + userIfaces))));
		// Scalar wasm-export functions: next free indices after the run wiring, each
		// shifted by the fixed and user-import counts. The component instance index space
		// at this point holds the run from-exports instance AND the instance its
		// `export wasi:cli/run` statement itself introduces -- an instance export
		// consumes
		// an index -- so the next free instance is two past the import instances.
		appendFuncExports(c, funcExports, io.nextCoreFunc() + user.coreFuncs() + 1, io.nextType() + user.types(),
				io.nextComponentFunc() + user.componentFuncs() + 1, rontolisp, blockInstances + 2 + userIfaces);
		return c.toByteArray();
	}

	/**
	 * Load a fixed component blob bundled as a classpath resource next to this class.
	 * @param name the file name under {@code component/}
	 * @return the raw bytes
	 */
	private static byte[] resource(String name) {
		try (InputStream in = WasmComponentBuilder.class.getResourceAsStream(RES + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing component resource: " + RES + name);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to read component resource: " + RES + name, ex);
		}
	}

	/**
	 * Return {@link #MEM_MODULE} bytes with the exported memory sized to at least the
	 * rontolisp core module's memory-import minimum. The default {@code mem.wasm} exports
	 * six pages, which fits every small program but traps at instantiation on a program
	 * whose static data / intern pool alone exceeds 384KB (uax-15's ~2.7MB UnicodeData
	 * tables, for instance). This walks the core module's import section to find its
	 * {@code "mem"/"memory"} declaration and rewrites the mem module's memory section so
	 * its exported memory starts with at least that many pages -- because active data
	 * segments are copied to memory at instantiation, growing on demand from
	 * {@code _start} is too late.
	 * @param coreModule the rontolisp core module whose memory-import minimum drives the
	 * mem module's initial size
	 * @return a fresh copy of {@link #MEM_MODULE} with the memory section rewritten, or
	 * the unchanged resource when the core module asks for six pages or fewer
	 */
	static byte[] memModuleFor(byte[] coreModule) {
		return memModuleFor(coreModule, true);
	}

	/**
	 * {@link #memModuleFor(byte[])}, additionally dropping the allocator when nothing in
	 * the component stages host-owned bytes through it ({@link #needsSharedRealloc}).
	 * <p>
	 * The module exists for its MEMORY: the canonical options of the {@code "w"}
	 * lowerings name a core memory, and that memory has to belong to an instance older
	 * than the adapter they are grouped for -- which is why the allocator sits here
	 * beside it rather than in the adapter or the core. Its allocator half is a different
	 * question, answered by the canonical options that actually reference it, so it is
	 * retained and shaken like any other over-provisioned helper module
	 * ({@link WasmExports#retain}).
	 * @param coreModule the rontolisp core module whose memory-import minimum drives the
	 * mem module's initial size
	 * @param realloc whether the component aliases the module's {@code cabi_realloc}
	 * @return the mem module bytes
	 */
	static byte[] memModuleFor(byte[] coreModule, boolean realloc) {
		byte[] mem = realloc ? MEM_MODULE
				: WasmTreeShaker.shake(WasmExports.retain(MEM_MODULE, java.util.Map.of("memory", "memory")));
		int needed = requiredMemPagesFromCore(coreModule);
		if (needed <= 6) {
			return mem;
		}
		return patchMemModuleMinPages(mem, needed);
	}

	/**
	 * Whether anything in this component stages host-owned bytes through the shared
	 * memory module's {@code cabi_realloc} ({@link #SHARED_REALLOC}).
	 * <p>
	 * For the fixed WASI wiring the answer is exact: a {@code "w"} member names the
	 * allocator exactly when it was declared through one of the {@code *Realloc}
	 * factories, so a print-only program -- whose surviving members are stdout, the
	 * stream/future built-ins and the waitable trio, none of which lift anything -- needs
	 * no allocator at all and drops the whole 86-byte bump-allocator body with it. For a
	 * program that imports an INTERFACE the answer is a plain yes: every block-bound and
	 * user lowering stages through it (an async call always, a sync one whenever it
	 * touches memory), and such a component is orders of magnitude past the budget this
	 * distinction exists for, so paying 158 bytes there buys precision nobody measures.
	 * <p>
	 * {@code wasm-export}s are not part of the question: a
	 * {@code :string}/{@code :s-expr} boundary lifts through the CORE module's own
	 * {@code cabi_realloc} ({@link #appendFuncExports}), never this one.
	 * @param surface the narrowed fixed surface
	 * @param fixed the block-bound interfaces the program binds
	 * @param user the genuine user interface imports
	 * @return whether to alias the allocator
	 */
	private static boolean needsSharedRealloc(FixedSurface surface, List<WasmComponentImportCompiler.Import> fixed,
			List<WasmComponentImportCompiler.Import> user) {
		for (String member : surface.members()) {
			if (at(W_MEMBERS, member).realloc()) {
				return true;
			}
		}
		return !fixed.isEmpty() || !user.isEmpty();
	}

	/**
	 * Extract the minimum-pages value from the {@code "mem"/"memory"} import of the given
	 * core module. Returns six (the mem module's default) when the import is not present
	 * so any embedder that reuses the mem module beside a non-rontolisp core module keeps
	 * working.
	 * @param coreModule the core module bytes
	 * @return the requested min pages, or six when the import is absent
	 */
	private static int requiredMemPagesFromCore(byte[] coreModule) {
		return WasmImports.memoryMinPages(coreModule, "mem", "memory").orElse(6);
	}

	/**
	 * Patch the given mem module bytes so its memory section declares the given min
	 * pages. Assumes the memory section is the first section with id 5 and rewrites only
	 * its size prefix and min-pages LEB128. Bytes after the memory section are shifted by
	 * the size delta.
	 * @param original the original mem module bytes
	 * @param minPages the new min-pages value
	 * @return a fresh byte array with the memory section rewritten
	 */
	private static byte[] patchMemModuleMinPages(byte[] original, int minPages) {
		int pos = 8; // \0asm + version
		while (pos < original.length) {
			int id = original[pos] & 0xFF;
			long[] sz = readLeb128(original, pos + 1);
			int sizePos = pos + 1;
			int bodyPos = (int) sz[1];
			int bodyLen = (int) sz[0];
			if (id != 5) {
				pos = bodyPos + bodyLen;
				continue;
			}
			// memory section body: vec of memories. Each memory is (limits).
			// Rewrite the whole body: count 01, flags 00, minPages LEB128.
			byte[] newLeb = encodeLeb128(minPages);
			byte[] newBody = new byte[2 + newLeb.length];
			newBody[0] = 0x01; // count
			newBody[1] = 0x00; // flags: min only
			System.arraycopy(newLeb, 0, newBody, 2, newLeb.length);
			byte[] newSize = encodeLeb128(newBody.length);
			byte[] result = new byte[pos + 1 + newSize.length + newBody.length
					+ (original.length - (bodyPos + bodyLen))];
			System.arraycopy(original, 0, result, 0, pos + 1);
			int cur = pos + 1;
			System.arraycopy(newSize, 0, result, cur, newSize.length);
			cur += newSize.length;
			System.arraycopy(newBody, 0, result, cur, newBody.length);
			cur += newBody.length;
			System.arraycopy(original, bodyPos + bodyLen, result, cur, original.length - (bodyPos + bodyLen));
			return result;
		}
		return original;
	}

	// Reads an unsigned LEB128 integer starting at position and returns [value, nextPos].
	private static long[] readLeb128(byte[] buf, int pos) {
		long value = 0;
		int shift = 0;
		while (true) {
			int b = buf[pos++] & 0xFF;
			value |= ((long) (b & 0x7F)) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		return new long[] { value, pos };
	}

	// Encodes an unsigned integer as LEB128.
	private static byte[] encodeLeb128(int value) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int v = value;
		while (true) {
			int b = v & 0x7F;
			v >>>= 7;
			if (v == 0) {
				out.write(b);
				break;
			}
			out.write(b | 0x80);
		}
		return out.toByteArray();
	}

}
