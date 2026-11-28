package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.wasm.Type;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * The WIT function behind a canonical export
 * ({@code rontolisp:wasm-export ... :wit "text"}): an export whose types are the WIT's
 * own, lifted and lowered through the canonical ABI instead of the flat boundary's
 * designators. It is the export-side twin of a bound import function
 * ({@code WasmComponentImportCompiler.Decl}), in the opposite direction: a parameter is
 * LIFTED as an import's result is, the result LOWERED as an import's argument is.
 *
 * <p>
 * The core signature is the canonical ABI's for a lifted function: the parameters
 * flattened, or one pointer to their tuple in memory past
 * {@link WitCanonicalAbi#MAX_FLAT_PARAMS}; the result's one flat value, or one pointer to
 * its memory representation when it flattens to more.
 *
 * @param abi the layout calculator scoped where the function's type names resolve
 * @param func the function
 * @param ofWorld whether the scope is the world's own type definitions (a freestanding
 * function export), not an exported interface's
 * @param scopeName the scope's name: the exported interface's id, or the world's name
 * @param at the WIT file and line of the export ({@code path:line}), for a refusal
 * @param octets whether a {@code list<u8>} parameter is lifted as the octets it holds (an
 * {@code (unsigned-byte 8)} vector) instead of as text
 * @param worldInterfaces the interfaces the function's world exports, in world order
 */
record WitExportSignature(WitCanonicalAbi abi, WitFunc func, boolean ofWorld, String scopeName, String at,
		boolean octets, List<WitExportDirective.InterfaceExport> worldInterfaces) {

	/**
	 * Reads the signature a canonical export form names.
	 * @param witText the WIT document
	 * @param world the world's name
	 * @param ifaceId the exported interface's id, or {@code null} for a freestanding
	 * function
	 * @param label the export's label
	 * @param at the WIT file and line of the export
	 * @param octets whether a {@code list<u8>} parameter is lifted as octets
	 * @return the signature
	 */
	static WitExportSignature of(String witText, String world, @Nullable String ifaceId, String label, String at,
			boolean octets) {
		WitExportDirective.Signature signature = WitExportDirective.signature(witText, world, ifaceId, label);
		return new WitExportSignature(new WitCanonicalAbi(signature.resolver(), signature.scope()), signature.func(),
				signature.ofWorld(), ifaceId == null ? world : ifaceId, at, octets,
				WitExportDirective.interfaceExports(witText, world));
	}

	/**
	 * {@return the scope the function's type names resolve in}
	 */
	WitExportTypes.Scope scope() {
		return new WitExportTypes.Scope(this.abi, this.scopeName);
	}

	/**
	 * The interfaces a component of these exports' world exports, in world order, each
	 * with its scope: the ones the exports are members of, and the ones of types alone,
	 * which no export stands for. An interface that neither holds an export nor declares
	 * a type is not exported.
	 * @param decls the exports
	 * @return the interfaces by id; empty when no export says what its world is, and the
	 * interfaces are then the exports' own, in their order
	 */
	static Map<String, WitExportTypes.Scope> exportedInterfaces(List<WasmExportCompiler.Decl> decls) {
		Map<String, WitExportTypes.Scope> interfaces = new LinkedHashMap<>();
		for (WasmExportCompiler.Decl decl : decls) {
			WitExportSignature typing = decl.typing();
			if (typing == null) {
				continue;
			}
			for (WitExportDirective.InterfaceExport exported : typing.worldInterfaces()) {
				WitExportTypes.Scope scope = new WitExportTypes.Scope(typing.abi().scopedTo(exported.scope()),
						exported.id());
				if (decls.stream().anyMatch(member -> exported.id().equals(member.iface()))
						|| !WitExportTypes.declared(scope.abi(), true).isEmpty()) {
					interfaces.put(exported.id(), scope);
				}
			}
			break;
		}
		return interfaces;
	}

	/**
	 * What a world's exports name of an interface the component imports: the resources
	 * whose types their signatures name, which the component takes from that interface's
	 * instance, and those an export's parameter borrows -- a borrowed handle of a
	 * resource another component implements is one more in this instance's handle table,
	 * which the export must release before it returns (wasmtime traps "borrow handles
	 * still remain at the end of the call").
	 *
	 * @param ifaceId the interface's id
	 * @param iface the interface, as the world's document defines it
	 * @param resolver the resolver over that document
	 * @param types the resources' names in the interface, in the order the world names
	 * them
	 * @param borrowed the names of those an export's parameter borrows
	 * @param at the WIT file and line of the first export that names one
	 * ({@code path:line})
	 */
	record Imported(String ifaceId, WitItem.InterfaceDef iface, WitResolver resolver, List<String> types,
			List<String> borrowed, String at) {
	}

	/**
	 * Lists what the exports of a world name of the interfaces the component imports
	 * ({@link Imported}): a resource an exported interface names in a {@code use} clause
	 * of an interface the world does not export -- whether or not a function takes it,
	 * since the interface exports the type -- and one a {@code use} clause of the world
	 * names, which only a function of the world reaches ({@code WitExportDirective} cuts
	 * the clause to those).
	 * @param decls the exports
	 * @return one entry per interface, in the order the world names them; none when no
	 * export says what its world is
	 */
	static List<Imported> importedResources(List<WasmExportCompiler.Decl> decls) {
		Map<String, Imported> found = new LinkedHashMap<>();
		java.util.Set<String> exported = new java.util.HashSet<>();
		boolean interfaces = false;
		for (WasmExportCompiler.Decl decl : decls) {
			WitExportSignature typing = decl.typing();
			if (typing == null) {
				continue;
			}
			if (!interfaces) {
				interfaces = true;
				typing.worldInterfaces().forEach(iface -> exported.add(iface.id()));
				for (WitExportDirective.InterfaceExport iface : typing.worldInterfaces()) {
					WitExportSignature first = decls.stream()
						.map(WasmExportCompiler.Decl::typing)
						.filter(java.util.Objects::nonNull)
						.filter(other -> iface.id().equals(other.scopeName()))
						.findFirst()
						.orElse(typing);
					used(typing.abi().scopedTo(iface.scope()), exported, false, first, found);
				}
			}
			if (typing.ofWorld()) {
				used(typing.abi(), exported, true, typing, found);
			}
			if (typing == decl.wit()) {
				for (WitFunc.Param param : typing.func().params()) {
					typing.borrows(param.type(), typing.abi(), exported, found, new java.util.HashSet<>());
				}
			}
		}
		return List.copyOf(found.values());
	}

	// The resources a scope's use clauses name of interfaces the component imports: in a
	// world, every one (a world's clause imports the interface it names); in an exported
	// interface, those of an interface the world does not export.
	private static void used(WitCanonicalAbi abi, java.util.Set<String> exported, boolean world, WitExportSignature by,
			Map<String, Imported> found) {
		for (WitItem item : abi.scope().items()) {
			if (item instanceof WitItem.Use use) {
				for (WitItem.UseName name : use.names()) {
					WitResolver.Owned owned = abi.resolver()
						.resolveOwned(abi.scope(), name.alias() == null ? name.name() : name.alias());
					if (owned != null && owned.item() instanceof WitItem.ResourceDef) {
						imported(abi, owned, exported, world, false, by, found);
					}
				}
			}
		}
	}

	// Records a resource of an interface the component imports; one of an exported
	// interface, named from an interface, is the component's own.
	private static void imported(WitCanonicalAbi abi, WitResolver.Owned owned, java.util.Set<String> exported,
			boolean world, boolean borrowed, WitExportSignature by, Map<String, Imported> found) {
		String ifaceId = abi.resolver().canonicalId(owned.owner());
		if (ifaceId == null || !world && exported.contains(ifaceId)) {
			return;
		}
		String name = WitComponentTypeEncoder.definedNameOf(owned.item());
		Imported was = found.get(ifaceId);
		List<String> types = new ArrayList<>(was == null ? List.of() : was.types());
		List<String> borrows = new ArrayList<>(was == null ? List.of() : was.borrowed());
		if (!types.contains(name)) {
			types.add(name);
		}
		if (borrowed && !borrows.contains(name)) {
			borrows.add(name);
		}
		found.put(ifaceId,
				was != null
						? new Imported(ifaceId, was.iface(), was.resolver(), List.copyOf(types), List.copyOf(borrows),
								was.at())
						: new Imported(ifaceId, owned.owner(), abi.resolver(), List.copyOf(types), List.copyOf(borrows),
								by.at()));
	}

	// The resources of interfaces the component imports that a parameter of this export
	// borrows, wherever it nests.
	private void borrows(@Nullable WitType type, WitCanonicalAbi abi, java.util.Set<String> exported,
			Map<String, Imported> found, java.util.Set<WitItem> walking) {
		switch (type) {
			case null -> {
			}
			case WitType.BorrowOf borrow -> {
				WitResolver.Owned owned = abi.resolveOwned(new WitType.Named(borrow.resource()));
				if (owned.item() instanceof WitItem.ResourceDef) {
					imported(abi, owned, exported, this.ofWorld, true, this, found);
				}
			}
			case WitType.Named named -> {
				WitResolver.Owned owned = abi.resolveOwned(named);
				if (!walking.add(owned.item())) {
					return;
				}
				WitCanonicalAbi in = abi.scopedTo(owned.owner());
				switch (owned.item()) {
					case WitItem.TypeAlias alias -> borrows(alias.target(), in, exported, found, walking);
					case WitItem.RecordDef record ->
						record.fields().forEach(field -> borrows(field.type(), in, exported, found, walking));
					case WitItem.VariantDef variant ->
						variant.cases().forEach(c -> borrows(c.payload(), in, exported, found, walking));
					default -> {
					}
				}
				walking.remove(owned.item());
			}
			case WitType.ListOf list -> borrows(list.element(), abi, exported, found, walking);
			case WitType.OptionOf option -> borrows(option.element(), abi, exported, found, walking);
			case WitType.ResultOf res -> {
				borrows(res.ok(), abi, exported, found, walking);
				borrows(res.err(), abi, exported, found, walking);
			}
			case WitType.TupleOf tuple ->
				tuple.elements().forEach(element -> borrows(element, abi, exported, found, walking));
			default -> {
			}
		}
	}

	/**
	 * {@return the function's parameter count}
	 */
	int arity() {
		return this.func.params().size();
	}

	/**
	 * {@return the parameter names, in order}
	 */
	List<String> paramNames() {
		return this.func.params().stream().map(WitFunc.Param::name).toList();
	}

	/**
	 * {@return the flat core types of every parameter, in order}
	 */
	List<Type> flatParams() {
		List<Type> flats = new ArrayList<>();
		for (WitFunc.Param param : this.func.params()) {
			flats.addAll(this.abi.flatTypes(param.type()));
		}
		return flats;
	}

	/**
	 * {@return whether the parameters arrive as one pointer to their tuple in memory}
	 * (they flatten past {@link WitCanonicalAbi#MAX_FLAT_PARAMS})
	 */
	boolean spilledParams() {
		return flatParams().size() > WitCanonicalAbi.MAX_FLAT_PARAMS;
	}

	/**
	 * {@return the core parameter types of the lifted function}
	 */
	Type[] coreParams() {
		return spilledParams() ? new Type[] { Type.I32 } : flatParams().toArray(new Type[0]);
	}

	/**
	 * {@return whether the result is answered as one pointer to its memory
	 * representation} (it flattens past {@link WitCanonicalAbi#MAX_FLAT_RESULTS})
	 */
	boolean indirectResult() {
		WitType result = this.func.result();
		return result != null && this.abi.flatTypes(result).size() > WitCanonicalAbi.MAX_FLAT_RESULTS;
	}

	/**
	 * {@return the core result types of the lifted function}
	 */
	Type[] coreResults() {
		WitType result = this.func.result();
		if (result == null) {
			return new Type[0];
		}
		return indirectResult() ? new Type[] { Type.I32 } : this.abi.flatTypes(result).toArray(new Type[0]);
	}

	/**
	 * {@return whether the export touches linear memory}: a parameter the host lowers
	 * into it (a string or a list anywhere inside, or the spilled tuple), or a result
	 * answered there. Such an export is lifted with the canonical memory options and a
	 * post-return.
	 */
	boolean usesMemory() {
		if (spilledParams() || indirectResult()) {
			return true;
		}
		for (WitFunc.Param param : this.func.params()) {
			if (holdsBytes(param.type(), this.abi)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@return whether a parameter holds a {@code list<u8>} this export lifts as octets}
	 */
	boolean liftsOctets() {
		if (!this.octets) {
			return false;
		}
		for (WitFunc.Param param : this.func.params()) {
			if (WasmComponentImportCompiler.liftsListU8(param.type(), this.abi)) {
				return true;
			}
		}
		return false;
	}

	// Whether a value of this type holds a string or a list anywhere inside.
	private static boolean holdsBytes(@Nullable WitType type, WitCanonicalAbi abi) {
		return switch (type) {
			case null -> false;
			case WitType.Prim prim -> "string".equals(prim.name());
			case WitType.ListOf ignored -> true;
			case WitType.OptionOf opt -> holdsBytes(opt.element(), abi);
			case WitType.ResultOf res -> holdsBytes(res.ok(), abi) || holdsBytes(res.err(), abi);
			case WitType.TupleOf tuple -> tuple.elements().stream().anyMatch(element -> holdsBytes(element, abi));
			case WitType.Named named -> {
				WitCanonicalAbi in = abi.scopeOf(named);
				yield switch (abi.resolveNamed(named)) {
					case WitItem.TypeAlias alias -> holdsBytes(alias.target(), in);
					case WitItem.RecordDef record ->
						record.fields().stream().anyMatch(field -> holdsBytes(field.type(), in));
					case WitItem.VariantDef variant ->
						variant.cases().stream().anyMatch(c -> holdsBytes(c.payload(), in));
					default -> false;
				};
			}
			default -> false;
		};
	}

}
