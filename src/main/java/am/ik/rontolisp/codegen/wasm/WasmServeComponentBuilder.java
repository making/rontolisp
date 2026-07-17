package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.wasm.ComponentWriter;

/**
 * Assembles the serve-variant WASI component for {@code rontolisp:http-handler} on
 * <strong>wasi:http@0.3.0</strong>. The HTTP glue is Lisp -- {@code http.lisp} (spliced
 * by {@code eval/HttpLibrary}) over a wit-imported {@code wasi:http@0.3.0} surface -- so
 * there is no hand-written serve adapter. The core module (compiled in serve mode)
 * exports http.lisp's {@code %serve-handle} as {@code handle}; {@link #build} lifts it as
 * a <strong>callback async</strong> export of {@code wasi:http/handler@0.3.0}
 * ({@code handle: async func(request) -> result<response, error-code>}) against the
 * core's REAL callback {@code async_cb}: a pending handler returns the packed
 * {@code WAIT | (set << 4)} code and the host delivers the task's events through the
 * callback, while the result is delivered mid-task through the {@code task-return}
 * built-in http.lisp binds (0.3's replacement for 0.2's {@code response-outparam.set}).
 *
 * <p>
 * ONE shape serves plain serve AND serve+fetch: the 0.3 {@code service} world always
 * imports {@code client}, so the import block declares it either way and a handler that
 * never fetches simply binds no {@code send}. The import block
 * ({@code import-block-http-server.bin}) declares import instances
 * 0&nbsp;=&nbsp;{@code clocks/types} (dependency-hoisted by {@code wait-for}'s
 * {@code duration}), 1&nbsp;=&nbsp;{@code http/types}, 2&nbsp;=&nbsp;{@code http/client},
 * 3&nbsp;=&nbsp;{@code random}, 4&nbsp;=&nbsp;{@code system-clock},
 * 5&nbsp;=&nbsp;{@code monotonic-clock}, 6&nbsp;=&nbsp;{@code cli/types},
 * 7&nbsp;=&nbsp;{@code cli/stdout}, 8&nbsp;=&nbsp;{@code cli/stderr}, and pre-declares
 * component types 2&nbsp;=&nbsp;{@code request}, 3&nbsp;=&nbsp;{@code response},
 * 4&nbsp;=&nbsp;http {@code error-code}, 11/13&nbsp;=&nbsp;cli {@code error-code}
 * (aliases its own instance types needed); the next free type is 15. All constants
 * derived from {@code wasm-tools dump} of the {@code regen.sh} reference.
 *
 * <p>
 * The preview1 bridge ({@code adapter-http-server-p1.wasm}: random / clocks /
 * stdout-stderr over the 0.3 service-world interfaces and the stream/future built-ins)
 * satisfies the core's {@code wasi_snapshot_preview1} imports and is instantiated before
 * the core. The fixed block-declared interfaces http.lisp / wait.lisp bind are lowered
 * FROM the block ({@link WasmComponentBuilder#lowerFixedFromBlock}); anything else in the
 * import list is a genuine {@code rontolisp:wit-import} (e.g. {@code wasi:keyvalue}) and
 * rides {@link WasmComponentBuilder#appendUserImports}. Run under {@code wasmtime serve
 * -W gc=y -W exceptions=y} -- the handle export is a CALLBACK async lift over the
 * {@code $sched} built-ins this builder synthesizes (context slot 0, the u64 doorbell
 * stream, a waitable-set new/join pair), and every stream/future built-in is the
 * asynchronous variant, all base component-model-async.
 */
final class WasmServeComponentBuilder {

	private static final String RES = "component/";

	/** The shared 16-page memory module (the canonical scratch reaches page 8). */
	private static final byte[] MEM_MODULE = resource("mem-http-client.wasm");

	/**
	 * The preview1 bridge: implements the core's {@code wasi_snapshot_preview1} imports
	 * (random_get / clock_time_get / fd_write to stdout-stderr; graceful stubs for the
	 * rest) over the 0.3 service world's wasi:random / wasi:clocks / wasi:cli plus the
	 * stream/future built-ins.
	 */
	private static final byte[] ADAPTER_HTTP_SERVER_P1 = resource("adapter-http-server-p1.wasm");

	private static final byte[] IMPORT_BLOCK_HTTP_SERVER = resource("import-block-http-server.bin");

	// The interfaces http.lisp / wait.lisp bind that ARE part of the component's fixed
	// WASI surface (declared by the import block), lowered FROM the block rather than
	// as user imports.
	private static final Set<String> SERVE_FIXED_IFACES = Set.of("wasi:http/types@0.3.0", "wasi:http/client@0.3.0",
			"wasi:clocks/monotonic-clock@0.3.0");

	// Component import-instance indices (from import-block-http-server.bin).
	// wasi:clocks/types (instance 0) is dependency-hoisted by wait-for's `use
	// types.{duration}`, shifting every other instance by one.
	private static final int INST_HTTP_TYPES = 1;

	private static final int INST_HTTP_CLIENT = 2;

	private static final int INST_RANDOM = 3;

	private static final int INST_SYS_CLOCK = 4;

	private static final int INST_MONO_CLOCK = 5;

	private static final int INST_CLI_TYPES = 6;

	private static final int INST_STDOUT = 7;

	private static final int INST_STDERR = 8;

	/** The first free component import-instance index after the block. */
	private static final int FIRST_IMPORT_INSTANCE = 9;

	// Component type indices the block pre-declares (aliases for its own instance
	// types); the resource projections are REUSED by the drop / async-type machinery (a
	// resource is nominal).
	private static final int T_REQUEST = 2;

	private static final int T_RESPONSE = 3;

	private static final int T_HTTP_ERRCODE = 4;

	private static final int T_CLI_ERRCODE = 11;

	/** The first free component type index after the block. */
	private static final int FIRST_FREE_TYPE = 15;

	// The five functions the preview1 bridge imports under its "w" group besides the
	// built-ins, aliased out of the block's fixed instances.
	private record BridgeFunc(int instance, String field, String coreName, boolean needsMemory) {
	}

	private static final List<BridgeFunc> BRIDGE_FUNCS = List.of(
			new BridgeFunc(INST_RANDOM, "get-random-u64", "rand-u64", false),
			// system-clock.now returns the instant record -> indirect through memory
			new BridgeFunc(INST_SYS_CLOCK, "now", "sys-now", true),
			new BridgeFunc(INST_MONO_CLOCK, "now", "mono-now", false),
			new BridgeFunc(INST_STDOUT, "write-via-stream", "stdout-write", false),
			new BridgeFunc(INST_STDERR, "write-via-stream", "stderr-write", false));

	private WasmServeComponentBuilder() {
	}

	/**
	 * Assemble the serve component, importing the given interfaces alongside the fixed
	 * wasi:http surface.
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param imports the interface imports -- http.lisp's fixed wasi:http (lowered from
	 * the block) plus any genuine {@code rontolisp:wit-import} (e.g. wasi:keyvalue, wired
	 * by {@code appendUserImports}). Empty for none.
	 * @return the wasi:http@0.3.0 handler component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmComponentImportCompiler.Import> imports) {
		final List<WasmComponentImportCompiler.Import> serveFixed = new ArrayList<>();
		final List<WasmComponentImportCompiler.Import> userImports = new ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			(SERVE_FIXED_IFACES.contains(imported.ifaceId()) ? serveFixed : userImports).add(imported);
		}
		// The monotonic-clock binding (wait.lisp) may only alias what the block
		// declares; the http.lisp surface carries no allow-list (it owns the block).
		WasmComponentBuilder.validateFixedMembers(serveFixed);
		final int userIfaces = userImports.size();
		final ComponentWriter c = new ComponentWriter();
		c.writeRaw(IMPORT_BLOCK_HTTP_SERVER);
		// Core modules: 0 = shared memory, 1 = preview1 bridge, 2 = rontolisp core (serve
		// mode). The handler function IS the core's `handle` wasm-export (http.lisp),
		// lifted directly.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_HTTP_SERVER_P1);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// --- preview1 bridge WASI: alias + lower its five functions (component funcs
		// 0-4, core funcs 1-5), define the cli stream/future types (component types
		// 13-15 -- the cli error-code reuses the block's alias, type 9) and the five
		// stream/future built-ins (core funcs 6-10), then group everything into its "w"
		// import instance (core instance 1). ---
		final List<byte[]> bridgeAliases = new ArrayList<>();
		final List<byte[]> bridgeLowers = new ArrayList<>();
		final List<String> bridgeNames = new ArrayList<>();
		final List<Integer> bridgeCoreIndices = new ArrayList<>();
		for (int i = 0; i < BRIDGE_FUNCS.size(); i++) {
			BridgeFunc f = BRIDGE_FUNCS.get(i);
			bridgeAliases.add(ComponentWriter.aliasInstanceFunc(f.instance(), f.field()));
			bridgeLowers.add(f.needsMemory() ? ComponentWriter.canonLowerMemory(i, 0) : ComponentWriter.canonLower(i));
			bridgeNames.add(f.coreName());
			bridgeCoreIndices.add(i + 1);
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(bridgeAliases));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(bridgeLowers));
		final int tStream = FIRST_FREE_TYPE; // 13: stream<u8>
		final int tCliResult = FIRST_FREE_TYPE + 1; // 14: result<_, cli error-code>
		final int tCliFuture = FIRST_FREE_TYPE + 2; // 15: future<result<_, ...>>
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8),
						ComponentWriter.definedResultErr(T_CLI_ERRCODE), ComponentWriter.definedFuture(tCliResult))));
		// The async (non-blocking) built-in variants + the waitable-set trio: the
		// bridge's blocking wrappers park on BLOCKED, so no gated feature is needed.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonStreamNew(tStream), // 6
				ComponentWriter.canonStreamWriteAsync(tStream, 0), // 7
				ComponentWriter.canonStreamDropWritable(tStream), // 8
				ComponentWriter.canonFutureReadAsync(tCliFuture, 0), // 9
				ComponentWriter.canonFutureDropReadable(tCliFuture), // 10
				ComponentWriter.canonWaitableSetNew(), // 11
				ComponentWriter.canonWaitableJoin(), // 12
				ComponentWriter.canonWaitableSetWait(0)))); // 13
		bridgeNames.addAll(List.of("stream-new", "stream-write", "stream-drop-w", "future-read-cli", "future-drop-cli",
				"waitable-set-new", "waitable-join", "waitable-set-wait"));
		bridgeCoreIndices.addAll(List.of(6, 7, 8, 9, 10, 11, 12, 13));
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(bridgeNames, bridgeCoreIndices))));
		// Instantiate the preview1 bridge (core instance 2): mem = instance 0, w =
		// instance 1. It must precede the rontolisp core, whose wasi_snapshot_preview1
		// imports bind its exports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// --- the fixed block-declared interfaces http.lisp / wait.lisp bind: lower each
		// bound function FROM THE BLOCK's already-declared instances (component funcs
		// 5.., core funcs 14.., component types 18..), one synthesized core instance per
		// interface, from core instance 3. This is appendUserImports MINUS the
		// instance-type / importInstance emission, because the block already declared
		// these interfaces. ---
		final Map<String, Integer> projected = new LinkedHashMap<>();
		projected.put("wasi:http/types@0.3.0#request", T_REQUEST);
		projected.put("wasi:http/types@0.3.0#response", T_RESPONSE);
		final Map<String, Integer> instanceOf = new LinkedHashMap<>();
		instanceOf.put("wasi:http/types@0.3.0", INST_HTTP_TYPES);
		instanceOf.put("wasi:http/client@0.3.0", INST_HTTP_CLIENT);
		instanceOf.put("wasi:clocks/monotonic-clock@0.3.0", INST_MONO_CLOCK);
		final WasmComponentBuilder.FixedIo io = WasmComponentBuilder.lowerFixedFromBlock(c, serveFixed, instanceOf,
				projected, 5, 14, FIRST_FREE_TYPE + 3, 3);
		// Additional user imports (rontolisp:wit-import): component types / import
		// instances / component funcs / core funcs / core instances continue right after
		// the fixed serve surface. Emits nothing when there are none.
		final WasmComponentBuilder.Appended user = WasmComponentBuilder.appendUserImports(c, userImports, io.nextType(),
				FIRST_IMPORT_INSTANCE, io.nextComponentFunc(), io.nextCoreFunc());
		// --- the $sched core instance: the callback-task built-ins the core imports
		// (the context slot, the u64 doorbell stream, its own waitable-set new/join).
		// One defined type (stream<u64>) + seven canon-built core funcs, grouped into
		// one synthesized core instance passed to the core as "$sched". ---
		int typeIdx = io.nextType() + user.types();
		final int tU64Stream = typeIdx++;
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U64))));
		final int schedFn = io.nextCoreFunc() + user.coreFuncs();
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonContextGet(0), // schedFn
				ComponentWriter.canonContextSet(0), // +1
				ComponentWriter.canonStreamNew(tU64Stream), // +2
				ComponentWriter.canonStreamReadAsync(tU64Stream, 0), // +3
				ComponentWriter.canonStreamWriteAsync(tU64Stream, 0), // +4
				ComponentWriter.canonWaitableSetNew(), // +5
				ComponentWriter.canonWaitableJoin()))); // +6
		final List<Integer> schedIndices = new ArrayList<>();
		for (int i = 0; i < WasmComponentImportCompiler.SCHED_FIELDS.size(); i++) {
			schedIndices.add(schedFn + i);
		}
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceFromFuncs(WasmComponentImportCompiler.SCHED_FIELDS, schedIndices))));
		final int schedInst = io.nextCoreInstance() + userIfaces;
		// Instantiate the rontolisp core: mem = 0, wasi_snapshot_preview1 = the bridge
		// (instance 2), then one argument per fixed serve interface, then each user
		// interface's canon-lowered core instance, then $sched. The core exports handle
		// and its real callback async_cb.
		final List<String> coreNames = new ArrayList<>(List.of("mem", "wasi_snapshot_preview1"));
		final List<Integer> coreInstances = new ArrayList<>(List.of(0, 2));
		io.coreInstanceOf().forEach((ifaceId, instance) -> {
			coreNames.add(ifaceId);
			coreInstances.add(instance);
		});
		coreNames.add(WasmComponentImportCompiler.SCHED_MODULE);
		coreInstances.add(schedInst);
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(WasmComponentBuilder
			.rontolispInstantiate(2, coreNames, coreInstances, userImports, io.nextCoreInstance()))));
		final int coreInst = schedInst + 1;
		// --- the handler export ---
		// handle: async func(request: own<request>) -> result<own<response>, error-code>.
		// Every non-structural type the EXPORTED function type references must be a
		// NAMED type (the component-model export rule), so it is built over the block's
		// pre-declared request / response / error-code aliases -- named through the
		// wasi:http/types import -- never over the structurally re-declared shapes the
		// internal task-return canon uses (structurally equal, but anonymous).
		final int tOwnRequest = typeIdx++;
		final int tOwnResponse = typeIdx++;
		final int tHandleResult = typeIdx++;
		final int tHandleFunc = typeIdx++;
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(ComponentWriter.definedOwn(T_REQUEST),
				ComponentWriter.definedOwn(T_RESPONSE),
				ComponentWriter.definedResultOf(ComponentWriter.valTypeIndex(tOwnResponse),
						ComponentWriter.valTypeIndex(T_HTTP_ERRCODE)),
				ComponentWriter.asyncFuncTypeOf(List.of("request"), List.of(ComponentWriter.valTypeIndex(tOwnRequest)),
						ComponentWriter.valTypeIndex(tHandleResult)))));
		// Alias the core's `handle` wasm-export plus its REAL callback (async_cb, the
		// _async_cb runtime function), and lift handle with the CALLBACK async ABI
		// (base component-model-async; no stackful-lift feature): the core signature
		// is [i32 request] -> [i32 code], the result arrives via task.return, and a
		// pending handler returns WAIT | (set << 4) -- the host then delivers each of
		// the task's events through the callback instead of the task blocking inside
		// the export call.
		final int handleFn = schedFn + WasmComponentImportCompiler.SCHED_FIELDS.size();
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(coreInst, "handle"),
						ComponentWriter.aliasCoreFunc(coreInst, "async_cb"))));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter
			.vec(List.of(ComponentWriter.canonLiftMemoryUtf8AsyncCallback(handleFn, tHandleFunc, 0, handleFn + 1))));
		c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.componentInstanceFromFunc("handle", io.nextComponentFunc() + user.componentFuncs()))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(List
			.of(ComponentWriter.exportInstance("wasi:http/handler@0.3.0", FIRST_IMPORT_INSTANCE + userIfaces))));
		return c.toByteArray();
	}

	/**
	 * The imports that are NOT part of the fixed wasi:http surface -- the genuine
	 * additional {@code rontolisp:wit-import} interfaces (e.g. wasi:keyvalue), which are
	 * the only ones a double-import collision check applies to, and the only ones the
	 * emitted WIT describes on top of the fixed surface.
	 * @param imports the full import list
	 * @return the additional user imports
	 */
	static List<WasmComponentImportCompiler.Import> additionalImports(
			List<WasmComponentImportCompiler.Import> imports) {
		final List<WasmComponentImportCompiler.Import> out = new ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (!SERVE_FIXED_IFACES.contains(imported.ifaceId())) {
				out.add(imported);
			}
		}
		return out;
	}

	private static byte[] resource(String name) {
		try (InputStream in = WasmServeComponentBuilder.class.getResourceAsStream(RES + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing component resource: " + RES + name);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to read component resource: " + RES + name, ex);
		}
	}

}
