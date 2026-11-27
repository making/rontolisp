package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.wasm.ComponentWriter;
import org.jspecify.annotations.Nullable;

/**
 * The component half of a program's exports: each export's function type, its lift, and
 * the exported function or the exported instance of its interface -- with the types of a
 * world that names them ({@link WitExportTypes}). What both component builders append
 * after they have aliased each export's core function.
 */
final class ComponentExportWiring {

	private ComponentExportWiring() {
	}

	/**
	 * An export and the core functions its lift names.
	 *
	 * @param decl the export
	 * @param coreFunc the core function index of its wrapper
	 * @param postReturn the core function index of its post-return, or {@code -1} for an
	 * export that touches no memory and is lifted with no canonical option
	 */
	record Lifted(WasmExportCompiler.Decl decl, int coreFunc, int postReturn) {
	}

	// One function of an exported interface: its label, its lifted component function,
	// and either its WIT function (an export typed from its WIT) or its function type
	// over primitives.
	private record IfaceMember(String name, int func, @Nullable WitExportSignature wit, byte @Nullable [] flatType) {
	}

	/**
	 * Appends the type, canon, component, instance and export sections of the exports.
	 * @param c the component writer
	 * @param exported the exports, in program order, each with its aliased core functions
	 * @param memory the core memory index the canonical options name
	 * @param realloc the core function index of {@code cabi_realloc}, or {@code -1} when
	 * no export touches memory
	 * @param nextType the first free component type index
	 * @param nextComponentFunc the first free component function index
	 * @param nextComponentInstance the first free component instance index; each exported
	 * interface consumes one
	 * @param forceAsync whether every export is lifted against an async function type
	 * @param resourceTypes the component type index of each resource the world exports,
	 * by {@code <interface id>#<resource>} ({@link ExportedResources}); empty for a world
	 * that exports none
	 */
	static void append(ComponentWriter c, List<Lifted> exported, int memory, int realloc, int nextType,
			int nextComponentFunc, int nextComponentInstance, boolean forceAsync, Map<String, Integer> resourceTypes) {
		// The type declarations, each with its section: an export of a world's own
		// function that is typed from its WIT declares the world's named types as type
		// IMPORTS between them (WitExportTypes); any other declares one function type.
		final List<Map.Entry<Integer, byte[]>> typeEntries = new ArrayList<>();
		final WitExportTypes.Space space = new WitExportTypes.Space(
				(section, entry) -> typeEntries.add(Map.entry(section, entry)), nextType);
		resourceTypes.forEach(space::define);
		final List<byte[]> lifts = new ArrayList<>();
		final List<byte[]> shims = new ArrayList<>();
		final List<byte[]> instances = new ArrayList<>();
		final List<byte[]> exports = new ArrayList<>();
		// Exports that name a WIT interface (`export docs:adder/add;`) are bundled into
		// one component instance per interface, exported under the interface's id; a
		// flat (interface-less) export is exported as a top-level function. Insertion
		// order is world order, so the emitted exports keep it.
		final LinkedHashMap<String, List<IfaceMember>> ifaceGroups = new LinkedHashMap<>();
		// Exports that say what their world is (a world that names types) bring its
		// interfaces: world order, one of types alone among them.
		final Map<String, WitExportTypes.Scope> worldInterfaces = WitExportSignature
			.exportedInterfaces(exported.stream().map(Lifted::decl).toList());
		for (String iface : worldInterfaces.keySet()) {
			ifaceGroups.put(iface, new ArrayList<>());
		}
		for (int i = 0; i < exported.size(); i++) {
			WasmExportCompiler.Decl decl = exported.get(i).decl();
			// One function type per export (params p0, p1, ... unless the directive names
			// them): synchronous by default; an :async t export gets the async
			// counterpart (tag 0x43), which turns the lift below into a stackful
			// async export (the run shape) with the same flat core signature -- the ONLY
			// byte difference an :async export introduces.
			WitExportSignature wit = decl.typing();
			byte[] flatType = null;
			int funcType;
			if (wit == null) {
				WasmComponentBuilder.FuncExport e = WasmExportCompiler.componentExport(decl);
				final List<String> paramNames = e.paramNames();
				flatType = e.async() || forceAsync
						? ComponentWriter.asyncFuncTypeScalars(paramNames, e.paramValTypes(), e.resultValType())
						: ComponentWriter.funcTypeScalars(paramNames, e.paramValTypes(), e.resultValType());
				funcType = space.type(flatType);
			}
			else {
				// An export typed from its WIT: its scope's types first -- a world's as
				// type imports, an interface's bare, which its shim names below --, then
				// the function type over them.
				WitExportTypes.Naming naming = wit.ofWorld() ? WitExportTypes.Naming.WORLD_IMPORT
						: WitExportTypes.Naming.BARE;
				WitExportTypes.declareScope(space, naming, wit.scope());
				funcType = WitExportTypes.funcType(space, naming, wit, forceAsync);
			}
			int func = exported.get(i).coreFunc();
			int postReturn = exported.get(i).postReturn();
			// An export that touches memory is lifted with the canonical string options;
			// flat scalars need no memory and no realloc.
			lifts.add(postReturn < 0 ? ComponentWriter.canonLift(func, funcType) : ComponentWriter
				.canonLiftMemoryReallocUtf8PostReturn(func, funcType, memory, realloc, postReturn));
			if (decl.iface() == null) {
				// Flat export: export the lifted component func directly under its name.
				exports.add(ComponentWriter.exportFunc(decl.exportName(), nextComponentFunc + i));
			}
			else {
				// Interface member: defer to a per-interface instance built below.
				ifaceGroups.computeIfAbsent(decl.iface(), k -> new ArrayList<>())
					.add(new IfaceMember(decl.exportName(), nextComponentFunc + i, wit, flatType));
			}
		}
		// One synthesized instance per exported interface, exported under the interface's
		// fully-qualified id (`export docs:adder/add@0.1.0`). An interface with a member
		// typed from its WIT, or of types alone, is instantiated from its shim, the
		// nested component that gives the interface's types their names.
		final List<Instance> groups = new ArrayList<>();
		for (Map.Entry<String, List<IfaceMember>> group : ifaceGroups.entrySet()) {
			List<IfaceMember> members = group.getValue();
			WitExportTypes.Scope scope = members.stream()
				.map(IfaceMember::wit)
				.filter(Objects::nonNull)
				.map(WitExportSignature::scope)
				.findFirst()
				.orElse(members.isEmpty() ? worldInterfaces.get(group.getKey()) : null);
			if (scope == null) {
				instances.add(ComponentWriter.componentInstanceFromFuncs(
						members.stream().map(member -> Map.entry(member.name(), member.func())).toList()));
				groups.add(new Instance(group.getKey(), -1, List.of(), List.of(), List.of(), List.of()));
				continue;
			}
			WitExportTypes.Shim shim = WitExportTypes.shim(scope,
					members.stream()
						.map(member -> new WitExportTypes.Member(member.name(), member.wit(), member.flatType()))
						.toList(),
					forceAsync);
			List<String> argNames = new ArrayList<>();
			List<Integer> argSorts = new ArrayList<>();
			List<Integer> argIndices = new ArrayList<>();
			List<Used> used = new ArrayList<>();
			for (IfaceMember member : members) {
				argNames.add(WitExportTypes.importName(member.name()));
				argSorts.add(ComponentWriter.SORT_FUNC);
				argIndices.add(member.func());
			}
			for (String type : shim.importedTypes()) {
				argNames.add("import-type-" + type);
				argSorts.add(ComponentWriter.SORT_TYPE);
				WitExportTypes.Foreign foreign = WitExportTypes.foreign(scope, type);
				if (foreign != null) {
					used.add(new Used(argIndices.size(), foreign));
				}
				argIndices.add(WitExportTypes.bare(space, scope, type));
			}
			instances.add(ComponentWriter.componentInstantiateWith(shims.size(), argNames, argSorts, argIndices));
			groups.add(new Instance(group.getKey(), shims.size(), argNames, argSorts, argIndices, used));
			shims.add(shim.component());
		}
		for (Map.Entry<Integer, byte[]> entry : typeEntries) {
			c.rawSection(entry.getKey(), ComponentWriter.vec(List.of(entry.getValue())));
		}
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));
		for (byte[] shim : shims) {
			c.rawSection(ComponentWriter.SEC_COMPONENT, shim);
		}
		if (groups.stream().allMatch(group -> group.used().isEmpty())) {
			// every instance, then every export: an instance's index is its place
			int componentInstance = nextComponentInstance;
			for (Instance group : groups) {
				exports.add(ComponentWriter.exportInstance(group.id(), componentInstance++));
			}
			if (!instances.isEmpty()) {
				c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(instances));
			}
			c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
			return;
		}
		// An interface that names another's resource in a use clause exports THAT
		// resource: its shim takes the type as the other interface's instance exports
		// it, so each instance is exported before the next is made (wasm-tools' shape;
		// handed the component's own definition instead, the instance would export a
		// resource of its own name, which a reader of the component takes for a second
		// type). The world's own functions first; an export consumes an instance index.
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
		int componentInstance = nextComponentInstance;
		int nextAliasedType = space.next();
		final Map<String, Integer> exportedAs = new java.util.HashMap<>();
		final Map<String, Integer> aliasedTypes = new java.util.HashMap<>();
		for (int i = 0; i < groups.size(); i++) {
			Instance group = groups.get(i);
			List<Integer> argIndices = new ArrayList<>(group.argIndices());
			List<byte[]> aliases = new ArrayList<>();
			for (Used use : group.used()) {
				WitExportTypes.Foreign foreign = use.foreign();
				String key = foreign.ifaceId() + "#" + foreign.name();
				Integer aliased = aliasedTypes.get(key);
				if (aliased == null) {
					aliases.add(ComponentWriter.aliasInstanceType(
							Objects.requireNonNull(exportedAs.get(foreign.ifaceId()), foreign.ifaceId()),
							foreign.name()));
					aliased = nextAliasedType++;
					aliasedTypes.put(key, aliased);
				}
				argIndices.set(use.argument(), aliased);
			}
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
			c.rawSection(ComponentWriter.SEC_INSTANCE,
					ComponentWriter.vec(List.of(group.shim() < 0 ? instances.get(i) : ComponentWriter
						.componentInstantiateWith(group.shim(), group.argNames(), group.argSorts(), argIndices))));
			c.rawSection(ComponentWriter.SEC_EXPORT,
					ComponentWriter.vec(List.of(ComponentWriter.exportInstance(group.id(), componentInstance))));
			exportedAs.put(group.id(), componentInstance + 1);
			componentInstance += 2;
		}
	}

	// A type argument of a shim's instantiation that is a resource of another exported
	// interface: its position among the arguments.
	private record Used(int argument, WitExportTypes.Foreign foreign) {
	}

	// One exported interface's instance: its id, and the instantiation of its shim (-1
	// for an instance made of its functions alone).
	private record Instance(String id, int shim, List<String> argNames, List<Integer> argSorts,
			List<Integer> argIndices, List<Used> used) {
	}

}
