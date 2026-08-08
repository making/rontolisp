package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.wasm.ComponentWriter;
import am.ik.wit.WitDocument;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitMeta;
import am.ik.wit.WitPackageName;
import am.ik.wit.WitPrinter;
import am.ik.wit.WitRef;
import am.ik.wit.WitType;
import org.jspecify.annotations.Nullable;

/**
 * Renders the WIT text ({@code package root:component; world root { ... }}) describing a
 * {@code --component} output, for the CLI's {@code --emit-wit} option, so hosts and
 * binding generators (e.g. {@code jco}) can consume the component's typed surface without
 * introspecting it via {@code wasm-tools component wit}.
 *
 * <p>
 * The fixed part (the world's WASI imports, the fixed {@code wasi:cli/run} /
 * {@code wasi:http/incoming-handler} export, and the referenced package definitions) is
 * the per-variant {@link WasiWitDefinitions} document model; the
 * {@code rontolisp:wasm-export} directives are appended to the world as typed export
 * items, and the whole document is printed by {@link WitPrinter} in the canonical
 * {@code wasm-tools component wit} style — byte-identical to that tool's output on the
 * same component bytes (the http-server variants deliberately restore incoming-handler's
 * {@code use} clause that tool drops; see {@code src/wasm-component/README.md}).
 */
final class WitEmitter {

	/** The GC component's base variant (no fetch, no sockets). */
	static final String VARIANT_BASE = "base";

	/** The GC {@code rontolisp:http-handler} (incoming HTTP) variant. */
	static final String VARIANT_HTTP_SERVER = "http-server";

	/** The adapter-free {@code --no-gc} reactor variant. */
	static final String VARIANT_NOGC = "nogc";

	/**
	 * The {@code --component --no-wasi} reactor on the GC backend: the same empty world
	 * as the adapter-free {@code --no-gc} reactor (no imports, no fixed export -- only
	 * the appended {@code wasm-export} items), so it shares that variant's document.
	 */
	static final String VARIANT_REACTOR = VARIANT_NOGC;

	/** The {@code --no-gc} print-micro-adapter variant. */
	static final String VARIANT_NOGC_PRINT = "nogc-print";

	private WitEmitter() {
	}

	/**
	 * Renders the WIT text for a component of the given variant with the given export
	 * directives.
	 * @param variant one of the {@code VARIANT_*} names
	 * @param exportDecls the {@code rontolisp:wasm-export} directives lifted as
	 * component-model exports, in export order (empty on the http-server variants, whose
	 * only export is the fixed {@code wasi:http/incoming-handler})
	 * @return the WIT text (ends with a newline)
	 */
	static String emit(String variant, List<WasmExportCompiler.Decl> exportDecls) {
		return emit(variant, exportDecls, List.of());
	}

	/**
	 * Renders the WIT text for a component of the given variant with the given export
	 * directives and user WIT-interface imports ({@code rontolisp:wit-import}).
	 * @param variant one of the {@code VARIANT_*} names
	 * @param exportDecls the {@code rontolisp:wasm-export} directives lifted as
	 * component-model exports, in export order
	 * @param imports the user interfaces the component imports, in import order (each
	 * pruned to the functions the program binds -- the component's type declares nothing
	 * else, so neither may the emitted WIT)
	 * @return the WIT text (ends with a newline)
	 */
	static String emit(String variant, List<WasmExportCompiler.Decl> exportDecls,
			List<WasmComponentImportCompiler.Import> imports) {
		return emit(variant, exportDecls, imports, null);
	}

	/**
	 * Renders the WIT text for a component whose FIXED WASI surface has itself been
	 * pruned to what the program reaches ({@code --optimize} on the base variant).
	 * @param variant one of the {@code VARIANT_*} names
	 * @param exportDecls the {@code rontolisp:wasm-export} directives lifted as
	 * component-model exports, in export order
	 * @param imports the user interfaces the component imports, in import order
	 * @param wasiInterfaces the fixed WASI interface ids the component still imports, or
	 * {@code null} for the variant's whole world. A dropped interface leaves the world's
	 * import list AND the package definitions, exactly as it leaves the component's type
	 * -- {@code wasm-tools component wit} prints what the binary declares and nothing
	 * else
	 * @return the WIT text (ends with a newline)
	 */
	static String emit(String variant, List<WasmExportCompiler.Decl> exportDecls,
			List<WasmComponentImportCompiler.Import> imports, @Nullable Set<String> wasiInterfaces) {
		WitDocument document = prune(WasiWitDefinitions.document(variant), wasiInterfaces);
		// On the nogc-print variant EVERY export is an async lift (the print bridge's
		// blocking waitable-set park is legal only inside an async-typed task), so the
		// emitted WIT must say `async func` the way wasm-tools does -- the directives
		// themselves never carry :async there (it is rejected under --no-gc).
		boolean forceAsync = VARIANT_NOGC_PRINT.equals(variant);
		// An export naming a WIT interface (`export docs:adder/add;`) is bundled by its
		// interface id: it prints as an `export <id>;` reference plus a package block
		// defining the interface, exactly as wasm-tools reads the component's exported
		// instance. A flat export prints as an inline `export name: func(...)`.
		List<WasmExportCompiler.Decl> flat = new ArrayList<>();
		LinkedHashMap<String, List<WasmExportCompiler.Decl>> byInterface = new LinkedHashMap<>();
		for (WasmExportCompiler.Decl decl : exportDecls) {
			if (decl.iface() == null) {
				flat.add(decl);
			}
			else {
				byInterface.computeIfAbsent(decl.iface(), k -> new ArrayList<>()).add(decl);
			}
		}
		if (!exportDecls.isEmpty() || !imports.isEmpty()) {
			WitItem.World world = document.world();
			List<WitItem> items = new ArrayList<>(world.items());
			// A user import joins the world's import block: after the fixed WASI imports,
			// before the export block (the printer separates the two with a blank line).
			int firstExport = items.size();
			for (int i = 0; i < items.size(); i++) {
				if (items.get(i) instanceof WitItem.ExportRef || items.get(i) instanceof WitItem.ExportNamed) {
					firstExport = i;
					break;
				}
			}
			List<WitItem> importItems = new ArrayList<>();
			for (WasmComponentImportCompiler.Import imported : imports) {
				importItems.add(WitImportWorldEmitter.importItem(imported));
			}
			items.addAll(firstExport, importItems);
			// Flat function exports first (in export order), then one interface reference
			// per exported interface -- the order the component's export section carries.
			for (WasmExportCompiler.Decl decl : flat) {
				items.add(exportItem(decl, forceAsync));
			}
			for (String ifaceId : byInterface.keySet()) {
				items.add(new WitItem.ExportRef(WitMeta.none(), new WitRef(WitImportWorldEmitter.packageOf(ifaceId),
						WitImportWorldEmitter.interfaceNameOf(ifaceId))));
			}
			document = document.withWorld(new WitItem.World(world.meta(), world.name(), List.copyOf(items)));
		}
		// Package blocks: the imported interfaces first (as before), then a block per
		// exported-interface package, defining the interfaces the world's `export <id>;`
		// references -- the tail wasm-tools prints for a component's exported instances.
		List<WitItem> extraBlocks = new ArrayList<>();
		if (!imports.isEmpty()) {
			extraBlocks.addAll(WitImportWorldEmitter.packageBlocks(imports));
		}
		extraBlocks.addAll(exportPackageBlocks(byInterface, forceAsync));
		if (!extraBlocks.isEmpty()) {
			List<WitItem> items = new ArrayList<>(document.items());
			items.addAll(extraBlocks);
			document = new WitDocument(List.copyOf(items));
		}
		return WitPrinter.print(orderPackagesByFirstReference(document));
	}

	/**
	 * Reorders the package definitions the way {@code wasm-tools component wit} does: by
	 * the order in which the WORLD first names them &mdash; every import in import order,
	 * then the exports.
	 * <p>
	 * This used to be true by accident. The templates list their packages in exactly that
	 * order because {@code wasi:cli/types} is always the first world import, so the fixed
	 * blocks and the appended user-import / exported-interface blocks happened to line
	 * up. Pruning breaks the accident: drop every {@code wasi:cli} IMPORT and the package
	 * survives only through the fixed {@code export wasi:cli/run}, which the tool prints
	 * last and the template first. Deriving the order from the world removes the hidden
	 * coupling instead of adding a second list to keep in step.
	 */
	private static WitDocument orderPackagesByFirstReference(WitDocument document) {
		Map<String, Integer> firstReference = new LinkedHashMap<>();
		int position = 0;
		for (WitItem item : document.world().items()) {
			WitRef target = item instanceof WitItem.ImportRef importRef ? importRef.target()
					: item instanceof WitItem.ExportRef exportRef ? exportRef.target() : null;
			if (target != null && target.pkg() != null) {
				firstReference.putIfAbsent(target.pkg().toString(), position++);
			}
		}
		List<WitItem> blocks = new ArrayList<>();
		List<Integer> slots = new ArrayList<>();
		List<WitItem> items = new ArrayList<>();
		for (WitItem item : document.items()) {
			if (item instanceof WitItem.PackageBlock block) {
				blocks.add(block);
				// A block nothing references keeps its relative position at the end
				// rather
				// than being dropped: this method reorders, it does not prune.
				slots.add(firstReference.getOrDefault(block.name().toString(), Integer.MAX_VALUE));
				items.add(null);
			}
			else {
				items.add(item);
			}
		}
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			order.add(i);
		}
		order.sort(java.util.Comparator.comparingInt(slots::get));
		int next = 0;
		List<WitItem> ordered = new ArrayList<>(items.size());
		for (WitItem item : items) {
			ordered.add(item == null ? blocks.get(order.get(next++)) : item);
		}
		return new WitDocument(List.copyOf(ordered));
	}

	/**
	 * Drops the world imports the component no longer has, and with them every package
	 * definition nothing references any more. A component's WIT is a description of its
	 * type, so an interface the binary stopped importing has to leave the text too --
	 * otherwise {@code --emit-wit} hands a binding generator a world the component does
	 * not implement, and the oracle diff against {@code wasm-tools component wit} fails.
	 */
	private static WitDocument prune(WitDocument document, @Nullable Set<String> wasiInterfaces) {
		if (wasiInterfaces == null) {
			return document;
		}
		WitItem.World world = document.world();
		List<WitItem> worldItems = new ArrayList<>();
		for (WitItem item : world.items()) {
			if (item instanceof WitItem.ImportRef importRef
					&& !wasiInterfaces.contains(importRef.target().toString())) {
				continue;
			}
			worldItems.add(item);
		}
		// Which interfaces the surviving world still names, import or export.
		Set<String> referenced = new java.util.LinkedHashSet<>();
		for (WitItem item : worldItems) {
			if (item instanceof WitItem.ImportRef importRef) {
				referenced.add(importRef.target().toString());
			}
			else if (item instanceof WitItem.ExportRef exportRef) {
				referenced.add(exportRef.target().toString());
			}
		}
		List<WitItem> items = new ArrayList<>();
		for (WitItem item : document.items()) {
			if (item == world) {
				items.add(new WitItem.World(world.meta(), world.name(), List.copyOf(worldItems)));
				continue;
			}
			if (!(item instanceof WitItem.PackageBlock block)) {
				items.add(item);
				continue;
			}
			List<WitItem> kept = new ArrayList<>();
			for (WitItem member : block.items()) {
				if (member instanceof WitItem.InterfaceDef iface
						&& !referenced.contains(new WitRef(block.name(), iface.name()).toString())) {
					continue;
				}
				kept.add(member);
			}
			if (!kept.isEmpty()) {
				items.add(new WitItem.PackageBlock(block.meta(), block.name(), List.copyOf(kept)));
			}
		}
		return new WitDocument(List.copyOf(items));
	}

	// One package block per exported-interface package (interfaces of one package share a
	// block), each defining the interface's exported functions -- the reconstruction of
	// the component's exported instance type wasm-tools prints.
	private static List<WitItem> exportPackageBlocks(LinkedHashMap<String, List<WasmExportCompiler.Decl>> byInterface,
			boolean forceAsync) {
		LinkedHashMap<WitPackageName, List<WitItem>> byPackage = new LinkedHashMap<>();
		byInterface.forEach((ifaceId, decls) -> {
			List<WitItem> members = new ArrayList<>();
			for (WasmExportCompiler.Decl decl : decls) {
				members.add(new WitItem.FuncDef(WitMeta.none(), decl.exportName(), WitItem.FuncKind.PLAIN,
						witFunc(decl, forceAsync)));
			}
			WitItem.InterfaceDef iface = new WitItem.InterfaceDef(WitMeta.none(),
					WitImportWorldEmitter.interfaceNameOf(ifaceId), List.copyOf(members));
			byPackage.computeIfAbsent(WitImportWorldEmitter.packageOf(ifaceId), k -> new ArrayList<>()).add(iface);
		});
		List<WitItem> blocks = new ArrayList<>();
		byPackage
			.forEach((pkg, ifaces) -> blocks.add(new WitItem.PackageBlock(WitMeta.none(), pkg, List.copyOf(ifaces))));
		return blocks;
	}

	// Builds one typed world export the way wasm-tools prints it, e.g.
	// " export noisy-mul: async func(p0: s32, p1: s32) -> s32;". The parameter names are
	// the declaration's own (p0, p1, ... by default; the WIT world's names when the
	// program was compiled against one with rontolisp:wit-export) -- the very labels
	// WasmComponentBuilder/NoGcWasmComponentBuilder encode into the component's function
	// types, which is what makes an implemented world round-trip unchanged.
	private static WitItem exportItem(WasmExportCompiler.Decl decl, boolean forceAsync) {
		return new WitItem.ExportNamed(WitMeta.none(), decl.exportName(),
				new WitItem.Extern.ExternFunc(witFunc(decl, forceAsync)));
	}

	// The WIT function type of an export declaration: its parameter labels and boundary
	// types, and its result, with `async func` forced on the nogc-print variant.
	private static WitFunc witFunc(WasmExportCompiler.Decl decl, boolean forceAsync) {
		List<WitFunc.Param> params = new ArrayList<>();
		List<am.ik.rontolisp.compiler.BoundaryType> paramTypes = decl.paramTypes();
		for (int i = 0; i < paramTypes.size(); i++) {
			params.add(new WitFunc.Param(decl.paramNames().get(i), witType(paramTypes.get(i))));
		}
		Integer result = WasmExportCompiler.componentValType(decl.returnType());
		return new WitFunc(decl.async() || forceAsync, List.copyOf(params), result == null ? null : witTypeOf(result));
	}

	private static WitType witType(am.ik.rontolisp.compiler.BoundaryType designator) {
		Integer valType = WasmExportCompiler.componentValType(designator);
		if (valType == null) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-export type " + designator.designator() + " is not a WIT parameter type");
		}
		return witTypeOf(valType);
	}

	// A component value type back to its WIT spelling. An implemented world therefore
	// round-trips through --emit-wit with its own type names intact -- the same property
	// :param-names gives the labels -- which is what lets a world be fed straight back
	// in.
	private static WitType witTypeOf(int valType) {
		return new WitType.Prim(switch (valType) {
			case ComponentWriter.VT_S8 -> "s8";
			case ComponentWriter.VT_S16 -> "s16";
			case ComponentWriter.VT_S32 -> "s32";
			case ComponentWriter.VT_S64 -> "s64";
			case ComponentWriter.VT_U8 -> "u8";
			case ComponentWriter.VT_U16 -> "u16";
			case ComponentWriter.VT_U32 -> "u32";
			case ComponentWriter.VT_U64 -> "u64";
			case ComponentWriter.VT_F64 -> "f64";
			case ComponentWriter.VT_BOOL -> "bool";
			case ComponentWriter.VT_STRING -> "string";
			default -> throw new UnsupportedOperationException(
					"component value type 0x" + Integer.toHexString(valType) + " has no WIT rendering");
		});
	}

}
