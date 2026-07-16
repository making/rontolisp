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
 * a <strong>stackful async</strong> export of {@code wasi:http/handler@0.3.0}
 * ({@code handle: async func(request) -> result<response, error-code>}), whose result is
 * delivered mid-task through the {@code task-return} built-in http.lisp binds (0.3's
 * replacement for 0.2's {@code response-outparam.set}).
 *
 * <p>
 * ONE shape serves plain serve AND serve+fetch: the 0.3 {@code service} world always
 * imports {@code client}, so the import block declares it either way and a handler that
 * never fetches simply binds no {@code send}. The import block
 * ({@code import-block-http-server.bin}) declares import instances
 * 0&nbsp;=&nbsp;{@code http/types}, 1&nbsp;=&nbsp;{@code http/client},
 * 2&nbsp;=&nbsp;{@code random}, 3&nbsp;=&nbsp;{@code system-clock},
 * 4&nbsp;=&nbsp;{@code monotonic-clock}, 5&nbsp;=&nbsp;{@code cli/types},
 * 6&nbsp;=&nbsp;{@code cli/stdout}, 7&nbsp;=&nbsp;{@code cli/stderr}, and pre-declares
 * component types 1&nbsp;=&nbsp;{@code request}, 2&nbsp;=&nbsp;{@code response},
 * 3&nbsp;=&nbsp;http {@code error-code}, 9/11&nbsp;=&nbsp;cli {@code error-code} (aliases
 * its own instance types needed); the next free type is 13. All constants derived from
 * {@code wasm-tools dump} of the {@code regen.sh} reference.
 *
 * <p>
 * The preview1 bridge ({@code adapter-http-server-p1.wasm}: random / clocks /
 * stdout-stderr over the 0.3 service-world interfaces and the stream/future built-ins)
 * satisfies the core's {@code wasi_snapshot_preview1} imports and is instantiated before
 * the core. The fixed {@code wasi:http} interfaces http.lisp binds are lowered FROM the
 * block ({@link #lowerServeIoFromBlock}); anything else in the import list is a genuine
 * {@code rontolisp:wit-import} (e.g. {@code wasi:keyvalue}) and rides
 * {@link WasmComponentBuilder#appendUserImports}. Run under {@code wasmtime serve -W gc=y
 * -W exceptions=y} -- the handle export is a CALLBACK async lift and every stream/future
 * built-in is the asynchronous variant with a blocking waitable-set park, all base
 * component-model-async.
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

	// The wasi:http interfaces http.lisp binds, which ARE the component's fixed WASI
	// surface (declared by the import block), lowered FROM the block rather than as
	// user imports.
	private static final Set<String> SERVE_FIXED_IFACES = Set.of("wasi:http/types@0.3.0", "wasi:http/client@0.3.0");

	// Component import-instance indices (from import-block-http-server.bin).
	private static final int INST_HTTP_TYPES = 0;

	private static final int INST_HTTP_CLIENT = 1;

	private static final int INST_RANDOM = 2;

	private static final int INST_SYS_CLOCK = 3;

	private static final int INST_MONO_CLOCK = 4;

	private static final int INST_CLI_TYPES = 5;

	private static final int INST_STDOUT = 6;

	private static final int INST_STDERR = 7;

	/** The first free component import-instance index after the block. */
	private static final int FIRST_IMPORT_INSTANCE = 8;

	// Component type indices the block pre-declares (aliases for its own instance
	// types); the resource projections are REUSED by the drop / async-type machinery (a
	// resource is nominal).
	private static final int T_REQUEST = 1;

	private static final int T_RESPONSE = 2;

	private static final int T_HTTP_ERRCODE = 3;

	private static final int T_CLI_ERRCODE = 9;

	/** The first free component type index after the block. */
	private static final int FIRST_FREE_TYPE = 13;

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
		// --- http.lisp's fixed wasi:http interfaces: lower each bound function FROM THE
		// BLOCK's already-declared instances (component funcs 5.., core funcs 11..,
		// component types 16..), one synthesized core instance per interface, from core
		// instance 3. This is appendUserImports MINUS the instance-type / importInstance
		// emission, because the block already declared these interfaces. ---
		final ServeIo io = lowerServeIoFromBlock(c, serveFixed, 5, 14, FIRST_FREE_TYPE + 3, 3);
		// Additional user imports (rontolisp:wit-import): component types / import
		// instances / component funcs / core funcs / core instances continue right after
		// the fixed serve surface. Emits nothing when there are none.
		final WasmComponentBuilder.Appended user = WasmComponentBuilder.appendUserImports(c, userImports, io.nextType(),
				FIRST_IMPORT_INSTANCE, io.nextComponentFunc(), io.nextCoreFunc());
		// Instantiate the rontolisp core: mem = 0, wasi_snapshot_preview1 = the bridge
		// (instance 2), then one argument per fixed serve interface, then each user
		// interface's canon-lowered core instance. The core exports handle.
		final List<String> coreNames = new ArrayList<>(List.of("mem", "wasi_snapshot_preview1"));
		final List<Integer> coreInstances = new ArrayList<>(List.of(0, 2));
		io.coreInstanceOf().forEach((ifaceId, instance) -> {
			coreNames.add(ifaceId);
			coreInstances.add(instance);
		});
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(WasmComponentBuilder
			.rontolispInstantiate(2, coreNames, coreInstances, userImports, io.nextCoreInstance()))));
		final int coreInst = io.nextCoreInstance() + userIfaces;
		// --- the handler export ---
		// handle: async func(request: own<request>) -> result<own<response>, error-code>.
		// Every non-structural type the EXPORTED function type references must be a
		// NAMED type (the component-model export rule), so it is built over the block's
		// pre-declared request / response / error-code aliases -- named through the
		// wasi:http/types import -- never over the structurally re-declared shapes the
		// internal task-return canon uses (structurally equal, but anonymous).
		int typeIdx = io.nextType() + user.types();
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
		// Alias the core's `handle` wasm-export plus the bridge's stub callback, and
		// lift handle with the CALLBACK async ABI (base component-model-async; no
		// stackful-lift feature): the core signature is [i32 request] -> [i32 code],
		// the result arrives via task.return, and the task's blocking is the parked
		// waitable-set.wait inside the wrappers -- so the callback is never invoked
		// and stays a stub.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreFunc(coreInst, "handle"), ComponentWriter.aliasCoreFunc(2, "async_cb"))));
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter
					.vec(List.of(ComponentWriter.canonLiftMemoryUtf8AsyncCallback(io.nextCoreFunc() + user.coreFuncs(),
							tHandleFunc, 0, io.nextCoreFunc() + user.coreFuncs() + 1))));
		c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.componentInstanceFromFunc("handle", io.nextComponentFunc() + user.componentFuncs()))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(List
			.of(ComponentWriter.exportInstance("wasi:http/handler@0.3.0", FIRST_IMPORT_INSTANCE + userIfaces))));
		return c.toByteArray();
	}

	// The outcome of lowering the fixed wasi:http interfaces from the block: the
	// synthesized core instances are already emitted; coreInstanceOf maps each interface
	// to its core instance index (feeding the core module's instantiation arguments by
	// name). The next* cursors let the additional user imports and the export wiring
	// continue without a hardcoded count.
	private record ServeIo(Map<String, Integer> coreInstanceOf, int nextComponentFunc, int nextCoreFunc, int nextType,
			int nextCoreInstance) {
	}

	// Lowers the fixed wasi:http interfaces by aliasing each bound function out of the
	// block's already-declared import instance and canon-lowering it (async funcs with
	// the async option), then the resource drops, the stream/future built-ins bound off
	// the type aliases, the task-return built-ins and the waitable-set builtins -- the
	// appendUserImports emission kinds, against the block's instances. Bound functions
	// are deduplicated by canonical field name (two splices of one interface may repeat
	// a function).
	private static ServeIo lowerServeIoFromBlock(ComponentWriter c, List<WasmComponentImportCompiler.Import> serveFixed,
			int firstComponentFunc, int firstCoreFunc, int firstType, int firstCoreInstance) {
		// Resources the block already aliased are reused; everything else is projected
		// fresh (the map is shared with the component-level type derivation below).
		final Map<String, Integer> projected = new LinkedHashMap<>();
		projected.put("wasi:http/types@0.3.0#request", T_REQUEST);
		projected.put("wasi:http/types@0.3.0#response", T_RESPONSE);
		final Map<String, Integer> instanceOf = new LinkedHashMap<>();
		instanceOf.put("wasi:http/types@0.3.0", INST_HTTP_TYPES);
		instanceOf.put("wasi:http/client@0.3.0", INST_HTTP_CLIENT);
		final List<byte[]> funcAliases = new ArrayList<>();
		final List<byte[]> lowers = new ArrayList<>();
		final List<byte[]> dropAliases = new ArrayList<>();
		final List<byte[]> dropCanons = new ArrayList<>();
		final Map<String, List<String>> names = new LinkedHashMap<>();
		final Map<String, List<Integer>> indices = new LinkedHashMap<>();
		int compFunc = firstComponentFunc;
		int coreFunc = firstCoreFunc;
		int typeIdx = firstType;
		// Pass A: the bound functions (sync then async), deduplicated by field within
		// each interface.
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			final int instance = Objects.requireNonNull(instanceOf.get(imported.ifaceId()));
			final List<String> fieldNames = new ArrayList<>();
			final List<Integer> coreIndices = new ArrayList<>();
			final Set<String> seen = new java.util.HashSet<>();
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
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			final List<Integer> coreIndices = Objects.requireNonNull(indices.get(imported.ifaceId()));
			final Set<String> seenDrops = new java.util.HashSet<>(fieldNames);
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
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(funcAliases));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
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
		final List<byte[]> asyncCanons = new ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			final List<Integer> coreIndices = Objects.requireNonNull(indices.get(imported.ifaceId()));
			final Set<String> seen = new LinkedHashSet<>(fieldNames);
			final Map<String, Integer> typeOfAlias = new LinkedHashMap<>();
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				typeOfAlias.computeIfAbsent(async.alias(),
						a -> componentTypes.indexOf(imported.resolver(), async.abi(), async.type()));
			}
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				if (!seen.add(async.field())) {
					continue;
				}
				int type = Objects.requireNonNull(typeOfAlias.get(async.alias()));
				asyncCanons.add(WasmComponentBuilder.asyncCanon(async, type));
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
					asyncCanons.add(WasmComponentBuilder.waitableCanon(field));
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
		final List<byte[]> coreInstanceDefs = new ArrayList<>();
		final Map<String, Integer> coreInstanceOf = new LinkedHashMap<>();
		int coreInstance = firstCoreInstance;
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			if (fieldNames.isEmpty()) {
				continue;
			}
			coreInstanceDefs.add(ComponentWriter.coreInstanceFromFuncs(fieldNames,
					Objects.requireNonNull(indices.get(imported.ifaceId()))));
			coreInstanceOf.put(imported.ifaceId(), coreInstance++);
		}
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(coreInstanceDefs));
		return new ServeIo(coreInstanceOf, compFunc, coreFunc, typeIdx, coreInstance);
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
