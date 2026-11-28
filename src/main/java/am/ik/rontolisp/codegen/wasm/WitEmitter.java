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

	/**
	 * The base variant of a reactor ({@code rontolisp:wit-export :reactor t}): the base
	 * world without its fixed {@code export wasi:cli/run}, which the component does not
	 * lift, and without the definition only that export named.
	 */
	static final String VARIANT_BASE_REACTOR = "base-reactor";

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

	/** The fixed export of a command component, which a reactor does not have. */
	private static final String RUN_EXPORT = "wasi:cli/run@0.3.0";

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
	 * @param wasiMembers the fixed WASI interface ids the component still imports, each
	 * with the names -- functions and types -- its instance type still exports, or
	 * {@code null} for the variant's whole world. A dropped interface leaves the world's
	 * import list AND the package definitions, and a dropped member its interface,
	 * exactly as they leave the component's type -- {@code wasm-tools component wit}
	 * prints what the binary declares and nothing else
	 * @return the WIT text (ends with a newline)
	 */
	static String emit(String variant, List<WasmExportCompiler.Decl> exportDecls,
			List<WasmComponentImportCompiler.Import> imports,
			@Nullable Map<String, ? extends Set<String>> wasiMembers) {
		List<WitItem> importItems = new ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			importItems.add(WitImportWorldEmitter.importItem(imported));
		}
		return emit(variant, exportDecls, importItems,
				imports.isEmpty() ? List.of() : WitImportWorldEmitter.packageBlocks(imports), wasiMembers);
	}

	/**
	 * Renders the WIT text of a {@code --no-gc --component} component: the variant's
	 * fixed world (the empty one, or the print micro-adapter's stdout import) plus the
	 * typed exports and the host imports the core reaches. An import module that is a WIT
	 * interface id ({@code docs:host/env}) prints as an {@code import <id>;} with a
	 * package block defining the interface; a plain label ({@code env}) prints as an
	 * inline {@code import env: interface { ... }} -- the two shapes
	 * {@code wasm-tools component wit} reads back from the component's instance import.
	 * @param variant {@link #VARIANT_NOGC} or {@link #VARIANT_NOGC_PRINT}
	 * @param exportDecls the export directives, in export order
	 * @param imports the reached host imports, in the core's import order
	 * @return the WIT text (ends with a newline)
	 */
	static String emitNoGc(String variant, List<WasmExportCompiler.Decl> exportDecls,
			List<WasmImportCompiler.Decl> imports) {
		// One imported instance per module, in first-appearance order; the functions of
		// one module in core import order.
		LinkedHashMap<String, List<WitItem>> byModule = new LinkedHashMap<>();
		for (WasmImportCompiler.Decl decl : imports) {
			byModule.computeIfAbsent(decl.module(), k -> new ArrayList<>())
				.add(new WitItem.FuncDef(WitMeta.none(), decl.field(), WitItem.FuncKind.PLAIN, witFunc(decl)));
		}
		List<WitItem> importItems = new ArrayList<>();
		LinkedHashMap<WitPackageName, List<WitItem>> byPackage = new LinkedHashMap<>();
		byModule.forEach((module, funcs) -> {
			if (NoGcWasmComponentBuilder.INTERFACE_ID.matcher(module).matches()) {
				WitPackageName pkg = WitImportWorldEmitter.packageOf(module);
				String iface = WitImportWorldEmitter.interfaceNameOf(module);
				importItems.add(new WitItem.ImportRef(WitMeta.none(), new WitRef(pkg, iface)));
				byPackage.computeIfAbsent(pkg, k -> new ArrayList<>())
					.add(new WitItem.InterfaceDef(WitMeta.none(), iface, List.copyOf(funcs)));
			}
			else {
				importItems.add(new WitItem.ImportNamed(WitMeta.none(), module,
						new WitItem.Extern.ExternInterface(List.copyOf(funcs))));
			}
		});
		List<WitItem> blocks = new ArrayList<>();
		byPackage
			.forEach((pkg, ifaces) -> blocks.add(new WitItem.PackageBlock(WitMeta.none(), pkg, List.copyOf(ifaces))));
		return emit(variant, exportDecls, importItems, blocks, null);
	}

	// The one renderer: the variant's fixed document, the user import items spliced
	// into the world's import block, the typed exports, then the user-import package
	// blocks and the exported-interface blocks.
	private static String emit(String variant, List<WasmExportCompiler.Decl> exportDecls, List<WitItem> importItems,
			List<WitItem> importPackageBlocks, @Nullable Map<String, ? extends Set<String>> wasiMembers) {
		boolean run = !VARIANT_BASE_REACTOR.equals(variant);
		WitDocument document = prune(WasiWitDefinitions.document(run ? variant : VARIANT_BASE), wasiMembers, run);
		// On the nogc-print variant EVERY export is an async lift (the print bridge's
		// blocking waitable-set park is legal only inside an async-typed task), so the
		// emitted WIT must say `async func` the way wasm-tools does, whether or not the
		// directive carries :async.
		boolean forceAsync = VARIANT_NOGC_PRINT.equals(variant);
		// An export naming a WIT interface (`export docs:adder/add;`) is bundled by its
		// interface id: it prints as an `export <id>;` reference plus a package block
		// defining the interface, exactly as wasm-tools reads the component's exported
		// instance. A flat export prints as an inline `export name: func(...)`.
		List<WasmExportCompiler.Decl> flat = new ArrayList<>();
		LinkedHashMap<String, List<WasmExportCompiler.Decl>> byInterface = new LinkedHashMap<>();
		// The interfaces of a world that names types, in world order, one of types
		// alone among them (the component's own order: WasmComponentBuilder).
		Map<String, WitExportTypes.Scope> worldInterfaces = WitExportSignature.exportedInterfaces(exportDecls);
		for (String ifaceId : worldInterfaces.keySet()) {
			byInterface.put(ifaceId, new ArrayList<>());
		}
		for (WasmExportCompiler.Decl decl : exportDecls) {
			if (decl.iface() == null) {
				flat.add(decl);
			}
			else {
				byInterface.computeIfAbsent(decl.iface(), k -> new ArrayList<>()).add(decl);
			}
		}
		if (!exportDecls.isEmpty() || !importItems.isEmpty()) {
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
			items.addAll(firstExport, importItems);
			// An export of the world's own function that is typed from its WIT declares
			// the world's named types (type imports of the component), which the tool
			// prints with the imports: a use clause -- the resources the component
			// imports for the world's functions -- ahead of the definitions, whatever
			// the order of the type imports.
			for (WasmExportCompiler.Decl decl : flat) {
				WitExportSignature wit = decl.typing();
				if (wit != null) {
					List<WitItem> declared = WitExportTypes.declared(wit.abi(), false);
					List<WitItem> ordered = new ArrayList<>();
					declared.stream().filter(WitItem.Use.class::isInstance).forEach(ordered::add);
					declared.stream().filter(item -> !(item instanceof WitItem.Use)).forEach(ordered::add);
					items.addAll(firstExport + importItems.size(), ordered);
					break;
				}
			}
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
		List<WitItem> extraBlocks = new ArrayList<>(importPackageBlocks);
		extraBlocks.addAll(exportPackageBlocks(byInterface, worldInterfaces, forceAsync));
		if (!extraBlocks.isEmpty()) {
			List<WitItem> items = new ArrayList<>(document.items());
			items.addAll(extraBlocks);
			document = new WitDocument(List.copyOf(items));
		}
		return WitPrinter.print(orderPackagesByFirstReference(document));
	}

	/**
	 * Lays the package definitions out the way {@code wasm-tools component wit} does: one
	 * block per package, in the order in which the WORLD first names a package &mdash;
	 * every import in import order, then the exports &mdash; and inside a block the
	 * interfaces in that same order.
	 * <p>
	 * This used to be true by accident. The templates list their packages in exactly that
	 * order because {@code wasi:cli/types} is always the first world import, so the fixed
	 * blocks and the appended user-import / exported-interface blocks happened to line
	 * up. Pruning breaks the accident: drop every {@code wasi:cli} IMPORT and the package
	 * survives only through the fixed {@code export wasi:cli/run}, which the tool prints
	 * last and the template first. And a user import of a package the fixed world also
	 * names ({@code wasi:cli/exit} beside {@code wasi:cli/stdout}) arrives as a second
	 * block of that package, which is no WIT at all. Deriving the layout from the world
	 * removes the hidden coupling instead of adding a second list to keep in step.
	 */
	private static WitDocument orderPackagesByFirstReference(WitDocument document) {
		Map<String, Integer> packageReference = new LinkedHashMap<>();
		Map<String, Integer> interfaceReference = new LinkedHashMap<>();
		for (WitItem item : document.world().items()) {
			WitRef target = item instanceof WitItem.ImportRef importRef ? importRef.target()
					: item instanceof WitItem.ExportRef exportRef ? exportRef.target() : null;
			if (target != null && target.pkg() != null) {
				packageReference.putIfAbsent(target.pkg().toString(), packageReference.size());
				interfaceReference.putIfAbsent(target.toString(), interfaceReference.size());
			}
		}
		// One block per package, holding every block's members in the order they came.
		LinkedHashMap<String, WitItem.PackageBlock> blocks = new LinkedHashMap<>();
		List<WitItem> others = new ArrayList<>();
		for (WitItem item : document.items()) {
			if (item instanceof WitItem.PackageBlock block) {
				blocks.merge(block.name().toString(), block, (first, next) -> {
					List<WitItem> members = new ArrayList<>(first.items());
					members.addAll(next.items());
					return new WitItem.PackageBlock(first.meta(), first.name(), List.copyOf(members));
				});
			}
			else {
				others.add(item);
			}
		}
		// A block, or an interface, nothing references keeps its relative position at the
		// end rather than being dropped: this method lays out, it does not prune.
		List<WitItem.PackageBlock> ordered = new ArrayList<>(blocks.values());
		ordered.sort(java.util.Comparator
			.comparingInt(block -> packageReference.getOrDefault(block.name().toString(), Integer.MAX_VALUE)));
		List<WitItem> items = new ArrayList<>(others);
		for (WitItem.PackageBlock block : ordered) {
			List<WitItem> members = new ArrayList<>(block.items());
			members.sort(java.util.Comparator.comparingInt(member -> member instanceof WitItem.InterfaceDef iface
					? interfaceReference.getOrDefault(interfaceId(block, iface), Integer.MAX_VALUE)
					: Integer.MAX_VALUE));
			items.add(new WitItem.PackageBlock(block.meta(), block.name(), List.copyOf(members)));
		}
		return new WitDocument(List.copyOf(items));
	}

	/**
	 * Drops the world imports the component no longer has, the members a surviving import
	 * no longer declares, and with them every package definition nothing references any
	 * more. A component's WIT is a description of its type, so what the binary stopped
	 * importing has to leave the text too -- otherwise {@code --emit-wit} hands a binding
	 * generator a world the component does not implement, and the oracle diff against
	 * {@code wasm-tools component wit} fails. The same holds for the {@code wasi:cli/run}
	 * export a reactor does not lift.
	 */
	private static WitDocument prune(WitDocument document, @Nullable Map<String, ? extends Set<String>> wasiMembers,
			boolean run) {
		if (wasiMembers == null && run) {
			return document;
		}
		WitItem.World world = document.world();
		List<WitItem> worldItems = new ArrayList<>();
		for (WitItem item : world.items()) {
			if (item instanceof WitItem.ImportRef importRef && wasiMembers != null
					&& !wasiMembers.containsKey(importRef.target().toString())) {
				continue;
			}
			// a reactor's component lifts no run
			if (item instanceof WitItem.ExportRef exportRef && !run
					&& RUN_EXPORT.equals(exportRef.target().toString())) {
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
				if (!(member instanceof WitItem.InterfaceDef iface)) {
					kept.add(member);
					continue;
				}
				String id = interfaceId(block, iface);
				if (!referenced.contains(id)) {
					continue;
				}
				// An interface the block imports declares what its instance type still
				// exports; the fixed export is not the block's and keeps its members.
				Set<String> names = wasiMembers == null ? null : wasiMembers.get(id);
				kept.add(names == null ? member
						: new WitItem.InterfaceDef(iface.meta(), iface.name(), exporting(id, iface.items(), names)));
			}
			if (!kept.isEmpty()) {
				items.add(new WitItem.PackageBlock(block.meta(), block.name(), List.copyOf(kept)));
			}
		}
		return new WitDocument(List.copyOf(items));
	}

	// wasi:cli/stdout@0.3.0: an interface of a package block, as the world names it.
	private static String interfaceId(WitItem.PackageBlock block, WitItem.InterfaceDef iface) {
		return new WitRef(block.name(), iface.name()).toString();
	}

	// An interface's items cut down to the names its instance type exports: a function
	// under its own name, a resource's method as `[method]resource.name`, a type -- one
	// the interface defines or one it `use`s -- under the name it has here. A name the
	// template has no item for is a template older than the import block: saying so
	// beats printing a world that leaves the member out.
	private static List<WitItem> exporting(String id, List<WitItem> items, Set<String> exported) {
		Set<String> unmatched = new java.util.LinkedHashSet<>(exported);
		// a name is looked up once, by the one item that declares it
		java.util.function.Predicate<String> names = unmatched::remove;
		List<WitItem> kept = new ArrayList<>();
		for (WitItem item : items) {
			switch (item) {
				case WitItem.Use use -> {
					List<WitItem.UseName> used = use.names()
						.stream()
						.filter(name -> names.test(name.alias() == null ? name.name() : name.alias()))
						.toList();
					if (!used.isEmpty()) {
						kept.add(new WitItem.Use(use.meta(), use.path(), used));
					}
				}
				case WitItem.ResourceDef resource -> {
					if (!names.test(resource.name())) {
						continue;
					}
					List<WitItem> body = new ArrayList<>();
					for (WitItem member : resource.body() == null ? List.<WitItem>of() : resource.body()) {
						if (member instanceof WitItem.FuncDef func
								&& names.test(resourceExportName(resource.name(), func))) {
							body.add(member);
						}
					}
					kept.add(new WitItem.ResourceDef(resource.meta(), resource.name(),
							body.isEmpty() ? null : List.copyOf(body)));
				}
				case WitItem.FuncDef func -> {
					if (names.test(func.name())) {
						kept.add(item);
					}
				}
				case WitItem.TypeAlias alias -> {
					if (names.test(alias.name())) {
						kept.add(item);
					}
				}
				case WitItem.RecordDef record -> {
					if (names.test(record.name())) {
						kept.add(item);
					}
				}
				case WitItem.VariantDef variant -> {
					if (names.test(variant.name())) {
						kept.add(item);
					}
				}
				case WitItem.EnumDef en -> {
					if (names.test(en.name())) {
						kept.add(item);
					}
				}
				case WitItem.FlagsDef flags -> {
					if (names.test(flags.name())) {
						kept.add(item);
					}
				}
				default -> throw new IllegalStateException(
						"the fixed WIT world has an interface item no instance type exports: " + item);
			}
		}
		if (!unmatched.isEmpty()) {
			throw new IllegalStateException("the component imports " + unmatched + " of '" + id
					+ "', which the fixed WIT world does not declare: its template is older than the import block"
					+ " (src/wasm-component/regen-wit.sh)");
		}
		return List.copyOf(kept);
	}

	// The name a resource's function has among its interface's exports.
	private static String resourceExportName(String resource, WitItem.FuncDef func) {
		return switch (func.kind()) {
			case PLAIN -> "[method]" + resource + "." + func.name();
			case STATIC -> "[static]" + resource + "." + func.name();
			case CONSTRUCTOR -> "[constructor]" + resource;
		};
	}

	// One package block per exported-interface package (interfaces of one package share a
	// block), each defining the interface's exported functions -- the reconstruction of
	// the component's exported instance type wasm-tools prints.
	private static List<WitItem> exportPackageBlocks(LinkedHashMap<String, List<WasmExportCompiler.Decl>> byInterface,
			Map<String, WitExportTypes.Scope> worldInterfaces, boolean forceAsync) {
		LinkedHashMap<WitPackageName, List<WitItem>> byPackage = new LinkedHashMap<>();
		byInterface.forEach((ifaceId, decls) -> {
			List<WitItem> members = new ArrayList<>();
			// An interface with a member typed from its WIT, or of types alone, exports
			// every type it defines, ahead of its functions.
			WitExportTypes.Scope scope = decls.stream()
				.map(WasmExportCompiler.Decl::typing)
				.filter(java.util.Objects::nonNull)
				.map(WitExportSignature::scope)
				.findFirst()
				.orElse(decls.isEmpty() ? worldInterfaces.get(ifaceId) : null);
			if (scope != null) {
				WitPackageName pkg = WitImportWorldEmitter.packageOf(ifaceId);
				for (WitItem declared : WitExportTypes.declared(scope.abi(), true)) {
					// a clause naming an interface of the block's own package names it
					// bare, as the tool prints it
					members.add(declared instanceof WitItem.Use use ? new WitItem.Use(WitMeta.none(),
							pkg.equals(use.path().pkg()) ? WitRef.local(use.path().name()) : use.path(), use.names())
							: declared instanceof WitItem.ResourceDef resource ? undocumented(resource) : declared);
				}
			}
			for (WasmExportCompiler.Decl decl : decls) {
				// a resource's function is printed in its resource, with the types
				if (!WasmExportCompiler.isResourceExportName(decl)) {
					members.add(new WitItem.FuncDef(WitMeta.none(), decl.exportName(), WitItem.FuncKind.PLAIN,
							witFunc(decl, forceAsync)));
				}
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

	// A resource as a component says it: its functions, and no word of the WIT's docs.
	private static WitItem.ResourceDef undocumented(WitItem.ResourceDef resource) {
		List<WitItem> body = resource.body();
		if (body == null) {
			return new WitItem.ResourceDef(WitMeta.none(), resource.name(), null);
		}
		List<WitItem> functions = new ArrayList<>();
		for (WitItem member : body) {
			if (member instanceof WitItem.FuncDef func) {
				functions.add(new WitItem.FuncDef(WitMeta.none(), func.name(), func.kind(), func.func()));
			}
		}
		return new WitItem.ResourceDef(WitMeta.none(), resource.name(), List.copyOf(functions));
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
	// A host import's WIT function type: the declared boundary types under the
	// directive's parameter names, an `async func` where the call waits for it
	// (:blocking t; --no-gc refuses :async t, a future).
	private static WitFunc witFunc(WasmImportCompiler.Decl decl) {
		List<WitFunc.Param> params = new ArrayList<>();
		List<am.ik.rontolisp.compiler.BoundaryType> paramTypes = decl.paramTypes();
		for (int i = 0; i < paramTypes.size(); i++) {
			params.add(new WitFunc.Param(decl.paramNames().get(i), witType(paramTypes.get(i))));
		}
		Integer result = WasmExportCompiler.componentValType(decl.returnType());
		return new WitFunc(decl.blocking(), List.copyOf(params), result == null ? null : witTypeOf(result));
	}

	private static WitFunc witFunc(WasmExportCompiler.Decl decl, boolean forceAsync) {
		WitExportSignature wit = decl.typing();
		if (wit != null) {
			// the function of an export typed from its WIT is the WIT's own, type names
			// and all
			WitFunc func = wit.func();
			return forceAsync && !func.async() ? new WitFunc(true, func.params(), func.result()) : func;
		}
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
