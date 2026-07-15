package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.wasm.ComponentWriter;

/**
 * Assembles the serve-variant WASI component for {@code rontolisp:http-handler}. The HTTP
 * glue is Lisp -- {@code serve.lisp} (spliced by {@code eval/ServeLibrary}, the mirror of
 * {@code fetch.lisp}) over a wit-imported {@code wasi:http/types} / {@code wasi:io}
 * surface -- so there is <strong>no hand-written serve adapter</strong>. The core module
 * (compiled in serve mode) exports serve.lisp's {@code %serve-handle} as {@code handle}
 * and canon-lowers its own {@code wasi:io} / {@code wasi:http} calls; {@link #build}
 * lifts that {@code handle} wrapper into {@code wasi:http/incoming-handler@0.2.0}.
 *
 * <p>
 * One method serves two shapes, selected by the imports:
 * <ul>
 * <li><strong>plain serve</strong> -- {@code rontolisp:http-handler} alone. serve.lisp
 * binds {@code wasi:io/error} + {@code wasi:io/streams} + {@code wasi:http/types} (the
 * incoming half). The narrow import block ({@code import-block-http-server.bin}) declares
 * import instances 0&nbsp;=&nbsp;{@code monotonic-clock}, 1&nbsp;=&nbsp;{@code io/error},
 * 2&nbsp;=&nbsp;{@code io/streams}, 3&nbsp;=&nbsp;{@code http/types},
 * 4&nbsp;=&nbsp;{@code random}, 5&nbsp;=&nbsp;{@code wall-clock},
 * 6&nbsp;=&nbsp;{@code cli/stdout}, 7&nbsp;=&nbsp;{@code cli/stderr}.</li>
 * <li><strong>serve + fetch</strong> -- the handler also uses {@code rontolisp:fetch}, so
 * {@code fetch.lisp} is spliced alongside serve.lisp and binds the outgoing half too:
 * {@code wasi:io/poll} + {@code wasi:http/outgoing-handler} plus the outgoing-request /
 * future / incoming-response members of {@code wasi:http/types}. The two libraries'
 * overlapping {@code wasi:http/types} / {@code wasi:io/streams} bindings are merged into
 * one component-level import ({@code WasmComponentImportCompiler.mergeByIface}) before
 * this runs. The wide import block ({@code import-block-http-server-client.bin})
 * additionally declares {@code wasi:io/poll} (hoisted first) and
 * {@code wasi:http/outgoing-handler} (last), so its instances are shifted (see
 * {@link #WIDE}). Run under {@code wasmtime serve -S http=y} for outbound HTTP.</li>
 * </ul>
 *
 * <p>
 * Both shapes share the same preview1 bridge ({@code adapter-http-server-p1.wasm}:
 * random&nbsp;/ clock&nbsp;/ stdout-stderr for the core's {@code wasi_snapshot_preview1}
 * imports), instantiated before the core so its exports satisfy those imports. There is
 * no extended fetch bridge any more: once fetch is {@code fetch.lisp} over wit-imported
 * {@code wasi:http/outgoing-handler}, the core imports no {@code http} function, so
 * serve+fetch is byte-for-byte the plain-serve wiring with a wider import surface.
 *
 * <p>
 * The three (plain) / five (serve+fetch) fixed {@code wasi:io} / {@code wasi:http}
 * interfaces ARE the component's fixed WASI surface (declared by the import block), so
 * they are lowered FROM the block ({@link #lowerServeIoFromBlock}) rather than as user
 * imports: a minimal preview1-only block is impossible because {@code wasi:cli/stdout}
 * implicitly uses {@code wasi:io/streams}' {@code output-stream}, which would collide
 * with a user {@code wasi:io/streams} import. Anything else in the import list is a
 * genuine {@code rontolisp:wit-import} (e.g. {@code wasi:keyvalue}) and rides
 * {@link WasmComponentBuilder#appendUserImports}.
 */
final class WasmServeComponentBuilder {

	private static final String RES = "component/";

	/** The shared 16-page memory module (the canonical scratch reaches page 8). */
	private static final byte[] MEM_MODULE = resource("mem-http-client.wasm");

	/**
	 * The preview1 bridge: implements the core's {@code wasi_snapshot_preview1} imports
	 * (random_get / clock_time_get / fd_write to stdout-stderr; graceful stubs for the
	 * rest) over the wasi:http proxy world's wasi:random / wasi:clocks / wasi:cli. Shared
	 * by plain serve and serve+fetch (fetch is fetch.lisp now, so the core imports no
	 * {@code
	 * http} function the bridge would have to provide).
	 */
	private static final byte[] ADAPTER_HTTP_SERVER_P1 = resource("adapter-http-server-p1.wasm");

	private static final byte[] IMPORT_BLOCK_HTTP_SERVER = resource("import-block-http-server.bin");

	private static final byte[] IMPORT_BLOCK_HTTP_SERVER_CLIENT = resource("import-block-http-server-client.bin");

	// The wasi:io / wasi:http interfaces serve.lisp + fetch.lisp bind, which ARE the
	// component's fixed WASI surface (declared by the import block), lowered FROM the
	// block
	// rather than as user imports. io/poll and http/outgoing-handler appear only in the
	// serve+fetch shape; for plain serve they are simply absent from the import list.
	private static final Set<String> SERVE_FIXED_IFACES = Set.of("wasi:io/poll@0.2.0", "wasi:io/error@0.2.0",
			"wasi:io/streams@0.2.0", "wasi:http/types@0.2.0", "wasi:http/outgoing-handler@0.2.0");

	// The interfaces that appear ONLY when the handler also fetches; their presence
	// selects
	// the wide import block.
	private static final Set<String> FETCH_ONLY_IFACES = Set.of("wasi:io/poll@0.2.0",
			"wasi:http/outgoing-handler@0.2.0");

	private WasmServeComponentBuilder() {
	}

	/**
	 * A fixed serve import block and the constants a build needs to wire against it.
	 *
	 * @param bytes the import block (type / import / alias sections)
	 * @param instanceOf each fixed interface's canonical id -> its component
	 * import-instance index in the block
	 * @param preDeclared {@code "<iface id>#<resource>"} -> the component type index the
	 * block already aliased that resource to (reused for {@code canon resource.drop}
	 * instead of re-aliasing)
	 * @param firstFreeType the first free component type index after the block
	 * @param firstImportInstance the first free component import-instance index (right
	 * after the block's fixed import instances), where any additional user import
	 * instances start
	 */
	private record ServeBlock(byte[] bytes, Map<String, Integer> instanceOf, Map<String, Integer> preDeclared,
			int firstFreeType, int firstImportInstance) {
	}

	// Plain serve: the narrow block (import instances 0-7, component types 0-12; the
	// block
	// pre-declares input-stream = 4 / output-stream = 5). Next free component type = 13.
	private static final ServeBlock NARROW = new ServeBlock(IMPORT_BLOCK_HTTP_SERVER,
			Map.of("wasi:clocks/monotonic-clock@0.2.0", 0, "wasi:io/error@0.2.0", 1, "wasi:io/streams@0.2.0", 2,
					"wasi:http/types@0.2.0", 3, "wasi:random/random@0.2.0", 4, "wasi:clocks/wall-clock@0.2.0", 5,
					"wasi:cli/stdout@0.2.0", 6, "wasi:cli/stderr@0.2.0", 7),
			Map.of("wasi:io/streams@0.2.0#input-stream", 4, "wasi:io/streams@0.2.0#output-stream", 5), 13, 8);

	// Serve + fetch: the wide block (import instances 0-9 with io/poll hoisted first and
	// http/outgoing-handler last, component types 0-19; the block pre-declares
	// input-stream
	// = 5, output-stream = 6, pollable = 7, outgoing-request = 15,
	// future-incoming-response
	// = 17). Next free component type = 20.
	private static final ServeBlock WIDE = new ServeBlock(IMPORT_BLOCK_HTTP_SERVER_CLIENT,
			Map.of("wasi:io/poll@0.2.0", 0, "wasi:clocks/monotonic-clock@0.2.0", 1, "wasi:io/error@0.2.0", 2,
					"wasi:io/streams@0.2.0", 3, "wasi:http/types@0.2.0", 4, "wasi:random/random@0.2.0", 5,
					"wasi:clocks/wall-clock@0.2.0", 6, "wasi:cli/stdout@0.2.0", 7, "wasi:cli/stderr@0.2.0", 8,
					"wasi:http/outgoing-handler@0.2.0", 9),
			Map.of("wasi:io/streams@0.2.0#input-stream", 5, "wasi:io/streams@0.2.0#output-stream", 6,
					"wasi:io/poll@0.2.0#pollable", 7, "wasi:http/types@0.2.0#outgoing-request", 15,
					"wasi:http/types@0.2.0#future-incoming-response", 17),
			20, 10);

	// The six functions the preview1 bridge imports under its "w" group, aliased out of
	// the
	// block's fixed instances (the io-write one is
	// output-stream.blocking-write-and-flush,
	// which the bridge uses to implement fd_write to stdout/stderr). The instance each
	// comes
	// from is looked up in the chosen block, so the same list works for both blocks.
	private record BridgeFunc(String ifaceId, String field, String coreName, boolean needsMemory) {
	}

	private static final List<BridgeFunc> BRIDGE_FUNCS = List.of(
			new BridgeFunc("wasi:random/random@0.2.0", "get-random-u64", "rand-u64", false),
			new BridgeFunc("wasi:clocks/wall-clock@0.2.0", "now", "wall-now", true),
			new BridgeFunc("wasi:clocks/monotonic-clock@0.2.0", "now", "mono-now", false),
			new BridgeFunc("wasi:cli/stdout@0.2.0", "get-stdout", "get-stdout", false),
			new BridgeFunc("wasi:cli/stderr@0.2.0", "get-stderr", "get-stderr", false), new BridgeFunc(
					"wasi:io/streams@0.2.0", "[method]output-stream.blocking-write-and-flush", "io-write", true));

	/**
	 * Assemble the serve component, importing the given interfaces alongside the fixed
	 * wasi:http surface. The block is chosen by the imports: the wide block when the
	 * handler also fetches ({@code wasi:io/poll} / {@code wasi:http/outgoing-handler}
	 * present), the narrow block otherwise.
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param imports the interface imports -- serve.lisp's / fetch.lisp's fixed wasi:io /
	 * wasi:http (lowered from the block) plus any genuine {@code rontolisp:wit-import}
	 * (e.g. wasi:keyvalue, wired by {@code appendUserImports}). Empty for none.
	 * @return the WASI 0.2 (http/incoming-handler) component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmComponentImportCompiler.Import> imports) {
		final List<WasmComponentImportCompiler.Import> serveFixed = new ArrayList<>();
		final List<WasmComponentImportCompiler.Import> userImports = new ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			(SERVE_FIXED_IFACES.contains(imported.ifaceId()) ? serveFixed : userImports).add(imported);
		}
		final ServeBlock block = usesWideBlock(serveFixed) ? WIDE : NARROW;
		final int userIfaces = userImports.size();
		final int userFuncs = WasmComponentBuilder.userImportFuncs(userImports);
		// A resource drop is a CORE function with no component function behind it (canon
		// resource.drop), so the core-function count and the component-function count are
		// two
		// numbers; confusing them yields a component that VALIDATES while lifting the
		// wrong
		// function.
		final int userCoreFuncs = WasmComponentBuilder.userImportCoreFuncs(userImports);
		final int userTypes = WasmComponentBuilder.userImportTypes(userImports);
		final ComponentWriter c = new ComponentWriter();
		c.writeRaw(block.bytes());
		// Core modules: 0 = shared memory, 1 = preview1 bridge, 2 = rontolisp core (serve
		// mode). No serve adapter, no fetch bridge -- the incoming-handler function IS
		// the
		// core's `handle` wasm-export (serve.lisp), lifted directly, and fetch is
		// fetch.lisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_HTTP_SERVER_P1);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// --- preview1 bridge WASI: alias + lower the 6 functions it imports (component
		// funcs
		// 0-5, core funcs 1-6) and group them into its "w" import instance (core instance
		// 1). ---
		final List<byte[]> bridgeAliases = new ArrayList<>();
		final List<byte[]> bridgeLowers = new ArrayList<>();
		final List<String> bridgeNames = new ArrayList<>();
		final List<Integer> bridgeCoreIndices = new ArrayList<>();
		for (int i = 0; i < BRIDGE_FUNCS.size(); i++) {
			BridgeFunc f = BRIDGE_FUNCS.get(i);
			bridgeAliases.add(ComponentWriter
				.aliasInstanceFunc(Objects.requireNonNull(block.instanceOf().get(f.ifaceId())), f.field()));
			bridgeLowers.add(f.needsMemory() ? ComponentWriter.canonLowerMemory(i, 0) : ComponentWriter.canonLower(i));
			bridgeNames.add(f.coreName());
			bridgeCoreIndices.add(i + 1);
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(bridgeAliases));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(bridgeLowers));
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(bridgeNames, bridgeCoreIndices))));
		// Instantiate the preview1 bridge (core instance 2): mem = instance 0, w =
		// instance
		// 1. It must precede the rontolisp core, whose wasi_snapshot_preview1 imports
		// bind
		// its exports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// --- serve.lisp's + fetch.lisp's fixed wasi:io / wasi:http interfaces: lower
		// each
		// bound function FROM THE BLOCK's already-declared instances (component funcs
		// 6..,
		// core funcs 7..), one synthesized core instance per interface, from core
		// instance 3.
		// io/error is skipped (0 decls). This is appendUserImports MINUS the
		// instance-type /
		// importInstance emission, because the block already declared these interfaces.
		// ---
		final ServeIo io = lowerServeIoFromBlock(c, serveFixed, block, 6, 7, block.firstFreeType(), 3);
		// Additional user imports (rontolisp:wit-import): component types / import
		// instances /
		// component funcs / core funcs / core instances continue right after the fixed
		// serve
		// surface. Emits nothing when there are none, so a plain serve component shifts
		// by
		// zero.
		WasmComponentBuilder.appendUserImports(c, userImports, io.nextType(), block.firstImportInstance(),
				io.nextComponentFunc(), io.nextCoreFunc());
		// Instantiate the rontolisp core: mem = 0, wasi_snapshot_preview1 = the bridge
		// (instance 2), then one argument per fixed serve interface (module name = its
		// canonical id, satisfied by the synthesized core instance lowered above), then
		// each
		// user interface's canon-lowered core instance. The core exports handle /
		// __ronto_alloc.
		final List<String> coreNames = new ArrayList<>(List.of("mem", "wasi_snapshot_preview1"));
		final List<Integer> coreInstances = new ArrayList<>(List.of(0, 2));
		io.coreInstanceOf().forEach((ifaceId, instance) -> {
			coreNames.add(ifaceId);
			coreInstances.add(instance);
		});
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(WasmComponentBuilder
			.rontolispInstantiate(2, coreNames, coreInstances, userImports, io.nextCoreInstance()))));
		final int coreInst = io.nextCoreInstance() + userIfaces;
		// --- interface export ---
		// own<incoming-request> reuses the incoming-request resource type the drop
		// lowering
		// already projected; response-outparam is projected now (serve.lisp never drops
		// it).
		final int httpTypes = Objects.requireNonNull(block.instanceOf().get("wasi:http/types@0.2.0"));
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceType(httpTypes, "response-outparam"))));
		final int tResponseOutparam = io.nextType() + userTypes;
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedOwn(io.incomingRequestType()), // own<incoming-request>
						ComponentWriter.definedOwn(tResponseOutparam), // own<response-outparam>
						ComponentWriter.funcTypeParamsNoResult(List.of("request", "response-out"),
								List.of(tResponseOutparam + 1, tResponseOutparam + 2)))));
		final int handleFuncType = tResponseOutparam + 3;
		// Alias the core's `handle` wasm-export and lift it against the own<> handle
		// function
		// type. componentInstanceFromFunc then exports it as the incoming-handler
		// interface.
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(coreInst, "handle"))));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter
			.vec(List.of(ComponentWriter.canonLift(io.nextCoreFunc() + userCoreFuncs, handleFuncType))));
		c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.componentInstanceFromFunc("handle", io.nextComponentFunc() + userFuncs))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(List.of(ComponentWriter
			.exportInstance("wasi:http/incoming-handler@0.2.0", block.firstImportInstance() + userIfaces))));
		return c.toByteArray();
	}

	// The wide block is needed exactly when the handler also fetches -- i.e. one of the
	// fetch-only interfaces (wasi:io/poll, wasi:http/outgoing-handler) is bound.
	private static boolean usesWideBlock(List<WasmComponentImportCompiler.Import> serveFixed) {
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			if (FETCH_ONLY_IFACES.contains(imported.ifaceId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns whether the imports include the outgoing wasi:http surface fetch.lisp adds
	 * (so the serve+fetch WIT world / wide block applies rather than the plain-serve
	 * one).
	 * @param imports the full import list
	 * @return {@code true} when a fetch-only interface is bound
	 */
	static boolean usesFetchSurface(List<WasmComponentImportCompiler.Import> imports) {
		return usesWideBlock(imports);
	}

	// The outcome of lowering the fixed wasi:io / wasi:http interfaces from the block:
	// the
	// synthesized core instances are already emitted; coreInstanceOf maps each interface
	// that got one to its core instance index (feeding the core module's instantiation
	// arguments by name). incomingRequestType is the projected own<incoming-request> the
	// interface export reuses. The next* cursors let the additional user imports and the
	// export wiring continue without a hardcoded count.
	private record ServeIo(Map<String, Integer> coreInstanceOf, int incomingRequestType, int nextComponentFunc,
			int nextCoreFunc, int nextType, int nextCoreInstance) {
	}

	// Lowers the fixed wasi:io / wasi:http interfaces by aliasing each bound function out
	// of
	// the block's already-declared import instance and canon-lowering it (memory 0 /
	// realloc
	// 0 exactly when the call touches linear memory -- the appendUserImports rule, the
	// same
	// lowering fetch.lisp uses over the same interfaces), then a canon resource.drop per
	// bound drop, then one core instance per interface whose export names match the core
	// module's import fields. io/error is skipped (it binds nothing).
	//
	// serve.lisp and fetch.lisp may both bind the same function of the same interface
	// (the
	// merge upstream keeps both Lisp wrappers as separate defuns, so an interface arrives
	// here with duplicate fields). The component-level instance must export each field
	// ONCE
	// -- the two core imports both resolve to it -- so this DEDUPLICATES by canonical
	// field
	// name / resource.
	private static ServeIo lowerServeIoFromBlock(ComponentWriter c, List<WasmComponentImportCompiler.Import> serveFixed,
			ServeBlock block, int firstComponentFunc, int firstCoreFunc, int firstType, int firstCoreInstance) {
		// Resources the block already aliased are reused; everything else is aliased
		// fresh.
		final Map<String, Integer> projected = new LinkedHashMap<>(block.preDeclared());
		final List<byte[]> funcAliases = new ArrayList<>();
		final List<byte[]> lowers = new ArrayList<>();
		final List<byte[]> dropAliases = new ArrayList<>();
		final List<byte[]> dropCanons = new ArrayList<>();
		final Map<String, List<String>> names = new LinkedHashMap<>();
		final Map<String, List<Integer>> indices = new LinkedHashMap<>();
		int compFunc = firstComponentFunc;
		int coreFunc = firstCoreFunc;
		int typeIdx = firstType;
		// Pass A: the bound functions, deduplicated by field within each interface.
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			if (imported.decls().isEmpty() && imported.drops().isEmpty()) {
				continue; // io/error: nothing bound
			}
			final int instance = Objects.requireNonNull(block.instanceOf().get(imported.ifaceId()));
			final List<String> fieldNames = new ArrayList<>();
			final List<Integer> coreIndices = new ArrayList<>();
			final Set<String> seen = new java.util.HashSet<>();
			for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
				if (!seen.add(decl.field())) {
					continue; // both libraries bound this function; the block exports it
								// once
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
			names.put(imported.ifaceId(), fieldNames);
			indices.put(imported.ifaceId(), coreIndices);
		}
		// Pass B: the resource drops (canon resource.drop = a core function with no
		// component
		// function behind it), deduplicated by resource, reusing a resource type already
		// projected.
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			final List<String> fieldNames = names.get(imported.ifaceId());
			if (fieldNames == null) {
				continue; // io/error: no bound functions, so no core instance to add a
							// drop to
			}
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
							Objects.requireNonNull(block.instanceOf().get(imported.ifaceId())), drop.resource()));
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
		final List<byte[]> coreInstanceDefs = new ArrayList<>();
		final Map<String, Integer> coreInstanceOf = new LinkedHashMap<>();
		int coreInstance = firstCoreInstance;
		for (WasmComponentImportCompiler.Import imported : serveFixed) {
			final List<String> fieldNames = names.get(imported.ifaceId());
			if (fieldNames == null || fieldNames.isEmpty()) {
				continue; // io/error: no core instance
			}
			coreInstanceDefs.add(ComponentWriter.coreInstanceFromFuncs(fieldNames,
					Objects.requireNonNull(indices.get(imported.ifaceId()))));
			coreInstanceOf.put(imported.ifaceId(), coreInstance++);
		}
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(coreInstanceDefs));
		return new ServeIo(coreInstanceOf,
				Objects.requireNonNull(projected.get("wasi:http/types@0.2.0#incoming-request")), compFunc, coreFunc,
				typeIdx, coreInstance);
	}

	/**
	 * The imports that are NOT part of the fixed wasi:io / wasi:http surface -- the
	 * genuine additional {@code rontolisp:wit-import} interfaces (e.g. wasi:keyvalue),
	 * which are the only ones a double-import collision check applies to, and the only
	 * ones the emitted WIT describes on top of the fixed surface.
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
