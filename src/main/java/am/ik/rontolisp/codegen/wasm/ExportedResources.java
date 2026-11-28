package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.wasm.ComponentWriter;
import am.ik.wasm.Type;
import am.ik.wasm.WasmExports;
import am.ik.wasm.WasmImports;
import am.ik.wasm.WasmShimModules;
import am.ik.wit.WitItem;

/**
 * The resources a world exports, as the component around the core module defines them:
 * one resource type each, its three canonical built-ins for the core module to import,
 * and its destructor where the core module exports one (the scalar backend's resource
 * whose program defines no {@code <resource>-drop} has none:
 * {@code (resource (rep i32))}).
 *
 * <p>
 * The shape is the one {@code wasm-tools component new} gives an exported resource (read
 * off its output for a probe world, wasm-tools 1.263.0). A resource type names its
 * destructor, a core function of the core module; the core module imports the built-ins,
 * which name the resource type. That cycle is broken as the tool breaks it
 * ({@link WasmShimModules}): the type's destructor is a trampoline of a shim module
 * instantiated first, and once the core module is instantiated a fixup module writes the
 * real destructors into the shim's table.
 *
 * <pre>
 * (core instance $shim (instantiate $shim-module))
 * (alias core export $shim "0" (core func $dtor))
 * (type $counter (resource (rep i32) (dtor $dtor)))
 * (core func (canon resource.new $counter)) ...
 * (core instance $builtins (export "[resource-new]counter" ...))
 * (core instance $main (instantiate ... (with "[export]docs:res/counters@0.1.0" $builtins)))
 * (alias core export $main "docs:res/counters@0.1.0#[dtor]counter" (core func ...))
 * (core instance (instantiate $fixup-module ...))
 * </pre>
 */
final class ExportedResources {

	private ExportedResources() {
	}

	/**
	 * A resource an exported interface declares.
	 *
	 * @param ifaceId the exported interface's id
	 * @param name the resource's name
	 */
	record Resource(String ifaceId, String name) {

		/**
		 * {@return the key a type index space names the resource's type by}
		 * ({@code WitExportTypes.Space})
		 */
		String key() {
			return this.ifaceId + "#" + this.name;
		}

	}

	/**
	 * Lists the resources of the world a program's exports implement, in world order.
	 * @param decls the exports
	 * @return the resources; none when no export says what its world is, or the world
	 * exports none
	 */
	static List<Resource> of(List<WasmExportCompiler.Decl> decls) {
		List<Resource> resources = new ArrayList<>();
		for (WasmExportCompiler.Decl decl : decls) {
			WitExportSignature typing = decl.typing();
			if (typing == null) {
				continue;
			}
			for (WitExportDirective.InterfaceExport exported : typing.worldInterfaces()) {
				for (WitItem item : exported.scope().items()) {
					if (item instanceof WitItem.ResourceDef resource) {
						resources.add(new Resource(exported.id(), resource.name()));
					}
				}
			}
			break;
		}
		return resources;
	}

	/**
	 * What defining the resources added to the component, ahead of the core module's
	 * instantiation.
	 *
	 * @param resources the resources, in world order
	 * @param destructed the resources with a destructor, in world order: the slots of the
	 * shim's table
	 * @param typeOf the component type index of each resource's type, by
	 * {@link Resource#key}
	 * @param coreModules the core modules added (the shim and the fixup, or none)
	 * @param coreInstances the core instances added
	 * @param coreFuncs the core functions added
	 * @param builtins the core instance of each interface's built-ins, by the module name
	 * the core module imports them under
	 * @param shimInstance the core instance index of the shim, or {@code -1}
	 * @param fixupModule the core module index of the fixup, or {@code -1}
	 */
	record Defined(List<Resource> resources, List<Resource> destructed, Map<String, Integer> typeOf, int coreModules,
			int coreInstances, int coreFuncs, Map<String, Integer> builtins, int shimInstance, int fixupModule) {

		static final Defined NONE = new Defined(List.of(), List.of(), Map.of(), 0, 0, 0, Map.of(), -1, -1);

		/**
		 * {@return the component types added}: one per resource
		 */
		int types() {
			return this.resources.size();
		}

		/**
		 * {@return the core functions the fixup adds after the core module's
		 * instantiation}: one destructor alias per resource that has one
		 */
		int fixedCoreFuncs() {
			return this.destructed.size();
		}

		/**
		 * {@return the core instances the fixup adds}: its arguments and itself, or none
		 */
		int fixedCoreInstances() {
			return this.destructed.isEmpty() ? 0 : 2;
		}

	}

	/**
	 * Defines the resource types and their built-ins, ahead of the core module's
	 * instantiation. Emits nothing for a world that exports no resource. A resource has a
	 * destructor where the core module exports one ({@link ExportHandles#destructor}),
	 * and the shim and the fixup exist where one does.
	 * @param c the component writer
	 * @param resources the resources, in world order
	 * @param coreModule the core module, whose imports say which built-ins it calls and
	 * whose exports which destructors it has
	 * @param nextCoreModule the first free core module index
	 * @param nextCoreInstance the first free core instance index
	 * @param nextCoreFunc the first free core function index
	 * @param nextType the first free component type index
	 * @return what was added
	 */
	static Defined define(ComponentWriter c, List<Resource> resources, byte[] coreModule, int nextCoreModule,
			int nextCoreInstance, int nextCoreFunc, int nextType) {
		if (resources.isEmpty()) {
			return Defined.NONE;
		}
		final Set<String> exported = WasmExports.names(coreModule);
		final List<Resource> destructed = new ArrayList<>();
		for (Resource resource : resources) {
			if (exported.contains(ExportHandles.destructor(resource.ifaceId(), resource.name()))) {
				destructed.add(resource);
			}
		}
		final int d = destructed.size();
		int coreFunc = nextCoreFunc;
		int shimInstance = -1;
		if (d > 0) {
			// one destructor each: (func (param i32))
			final List<Type[]> params = new ArrayList<>();
			final List<Type[]> results = new ArrayList<>();
			for (int k = 0; k < d; k++) {
				params.add(new Type[] { Type.I32 });
				results.add(new Type[0]);
			}
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, WasmShimModules.shim(params, results));
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, WasmShimModules.fixup(params, results));
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
				.vec(List.of(ComponentWriter.coreInstanceInstantiate(nextCoreModule, List.of(), List.of()))));
			shimInstance = nextCoreInstance;
			final List<byte[]> trampolines = new ArrayList<>();
			for (int k = 0; k < d; k++) {
				trampolines.add(ComponentWriter.aliasCoreFunc(shimInstance, WasmShimModules.slotName(k)));
			}
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(trampolines));
			coreFunc += d;
		}
		final List<byte[]> types = new ArrayList<>();
		final Map<String, Integer> typeOf = new LinkedHashMap<>();
		for (int k = 0; k < resources.size(); k++) {
			int slot = destructed.indexOf(resources.get(k));
			types.add(slot < 0 ? ComponentWriter.definedResource()
					: ComponentWriter.definedResource(nextCoreFunc + slot));
			typeOf.put(resources.get(k).key(), nextType + k);
		}
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(types));
		// The built-ins the core module imports, grouped by the module it imports them
		// from: one per exported interface that declares a resource.
		final Set<String> imported = new HashSet<>();
		for (WasmImports.Entry entry : WasmImports.of(coreModule)) {
			imported.add(entry.module() + "\0" + entry.field());
		}
		final List<byte[]> canons = new ArrayList<>();
		final Map<String, List<String>> fields = new LinkedHashMap<>();
		final Map<String, List<Integer>> funcs = new LinkedHashMap<>();
		for (int k = 0; k < resources.size(); k++) {
			Resource resource = resources.get(k);
			String module = ExportHandles.module(resource.ifaceId());
			int type = nextType + k;
			for (int i = 0; i < ExportHandles.FIELDS.size(); i++) {
				String field = ExportHandles.FIELDS.get(i) + resource.name();
				if (!imported.contains(module + "\0" + field)) {
					continue;
				}
				canons.add(i == ExportHandles.NEW ? ComponentWriter.canonResourceNew(type) : i == ExportHandles.REP
						? ComponentWriter.canonResourceRep(type) : ComponentWriter.canonResourceDrop(type));
				fields.computeIfAbsent(module, m -> new ArrayList<>()).add(field);
				funcs.computeIfAbsent(module, m -> new ArrayList<>()).add(coreFunc++);
			}
		}
		final Map<String, Integer> builtins = new LinkedHashMap<>();
		if (!canons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(canons));
			final List<byte[]> instances = new ArrayList<>();
			int instance = nextCoreInstance + (d > 0 ? 1 : 0);
			for (Map.Entry<String, List<String>> group : fields.entrySet()) {
				instances.add(ComponentWriter.coreInstanceFromFuncs(group.getValue(),
						java.util.Objects.requireNonNull(funcs.get(group.getKey()))));
				builtins.put(group.getKey(), instance++);
			}
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(instances));
		}
		return new Defined(List.copyOf(resources), List.copyOf(destructed), typeOf, d > 0 ? 2 : 0,
				(d > 0 ? 1 : 0) + builtins.size(), coreFunc - nextCoreFunc, builtins, shimInstance,
				d > 0 ? nextCoreModule + 1 : -1);
	}

	/**
	 * Writes the core module's destructors into the shim's table, after the core module's
	 * instantiation. Emits nothing for a world that exports no resource with a
	 * destructor.
	 * @param c the component writer
	 * @param defined what {@link #define} added
	 * @param coreInstance the core instance index of the core module
	 * @param nextCoreInstance the first free core instance index
	 * @param nextCoreFunc the first free core function index
	 * @param nextCoreTable the first free core table index
	 */
	static void fix(ComponentWriter c, Defined defined, int coreInstance, int nextCoreInstance, int nextCoreFunc,
			int nextCoreTable) {
		final List<Resource> destructed = defined.destructed();
		if (destructed.isEmpty()) {
			return;
		}
		final List<byte[]> aliases = new ArrayList<>();
		final List<String> names = new ArrayList<>();
		final List<Integer> sorts = new ArrayList<>();
		final List<Integer> indices = new ArrayList<>();
		aliases.add(ComponentWriter.aliasCoreTable(defined.shimInstance(), WasmShimModules.TABLE_EXPORT));
		names.add(WasmShimModules.TABLE_EXPORT);
		sorts.add(ComponentWriter.CORE_SORT_TABLE);
		indices.add(nextCoreTable);
		for (int k = 0; k < destructed.size(); k++) {
			Resource resource = destructed.get(k);
			aliases.add(ComponentWriter.aliasCoreFunc(coreInstance,
					ExportHandles.destructor(resource.ifaceId(), resource.name())));
			names.add(WasmShimModules.slotName(k));
			sorts.add(ComponentWriter.CORE_SORT_FUNC);
			indices.add(nextCoreFunc + k);
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter
					.vec(List.of(ComponentWriter.coreInstanceFromExports(names, sorts, indices), ComponentWriter
						.coreInstanceInstantiate(defined.fixupModule(), List.of(""), List.of(nextCoreInstance)))));
	}

}
