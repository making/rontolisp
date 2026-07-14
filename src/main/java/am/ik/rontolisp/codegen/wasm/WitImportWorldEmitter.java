package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.wit.Wit;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitMeta;
import am.ik.wit.WitPackageName;
import am.ik.wit.WitRef;
import am.ik.wit.WitType;

/**
 * Renders the WIT of the user interfaces a component imports
 * ({@code rontolisp:wit-import} under {@code --component}): one world {@code import} item
 * plus the package block defining the interface. Both are <strong>pruned to what the
 * component actually imports</strong> -- the bound functions, and only the types those
 * reach -- because that is what the component's type genuinely says: the imported
 * instance type declares nothing else, so {@code wasm-tools component wit} prints nothing
 * else either, and {@code --emit-wit} must agree with it.
 *
 * <p>
 * The item order mirrors {@link WitComponentTypeEncoder}'s declaration order, which is
 * what the tool reads back: named definitions (resources, variants, records, enums,
 * flags) in the order the traversal first reaches them, then the freestanding functions.
 * Doc comments and gates are dropped -- a component's type does not carry them.
 */
final class WitImportWorldEmitter {

	private final WitCanonicalAbi abi;

	private final WasmComponentImportCompiler.Import imported;

	// Discovery-ordered named definitions: the WIT name -> the item to print.
	private final Map<String, WitItem> named = new LinkedHashMap<>();

	// The bound methods of each resource, in binding order.
	private final Map<String, List<WitItem>> resourceMembers = new LinkedHashMap<>();

	private final Set<String> visiting = new LinkedHashSet<>();

	private WitImportWorldEmitter(WasmComponentImportCompiler.Import imported) {
		this.imported = imported;
		this.abi = new WitCanonicalAbi(imported.resolver(), imported.iface());
	}

	/**
	 * The world {@code import} item of an imported interface.
	 * @param imported the parsed component import
	 * @return the import item
	 */
	static WitItem importItem(WasmComponentImportCompiler.Import imported) {
		return Wit.importRef(ref(imported.ifaceId()));
	}

	/**
	 * The package blocks defining the imported interfaces, one per WIT package (several
	 * interfaces of one package share a block), each pruned to the bound surface.
	 * @param imports the parsed component imports
	 * @return the package block items, in import order
	 */
	static List<WitItem> packageBlocks(List<WasmComponentImportCompiler.Import> imports) {
		Map<WitPackageName, List<WitItem>> byPackage = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			WitPackageName pkg = packageOf(imported.ifaceId());
			byPackage.computeIfAbsent(pkg, ignored -> new ArrayList<>())
				.add(new WitImportWorldEmitter(imported).iface());
		}
		List<WitItem> blocks = new ArrayList<>();
		byPackage
			.forEach((pkg, ifaces) -> blocks.add(new WitItem.PackageBlock(WitMeta.none(), pkg, List.copyOf(ifaces))));
		return blocks;
	}

	// The pruned interface: the named definitions the bound functions reach, in discovery
	// order, then the bound freestanding functions.
	private WitItem.InterfaceDef iface() {
		List<WitItem.FuncDef> freestanding = new ArrayList<>();
		for (WasmComponentImportCompiler.Decl decl : this.imported.decls()) {
			var func = decl.func();
			String resource = func.resource();
			if (resource != null) {
				discoverResource(resource);
				Objects.requireNonNull(this.resourceMembers.get(resource)).add(strip(func.def()));
			}
			else {
				freestanding.add(strip(func.def()));
			}
			for (var param : func.def().func().params()) {
				discover(param.type());
			}
			WitType result = this.abi.resultType(func);
			if (result != null) {
				discover(result);
			}
		}
		List<WitItem> items = new ArrayList<>();
		this.named
			.forEach(
					(name, item) -> items.add(
							item instanceof WitItem.ResourceDef
									? new WitItem.ResourceDef(WitMeta.none(), name,
											List.copyOf(Objects.requireNonNull(this.resourceMembers.get(name))))
									: item));
		items.addAll(freestanding);
		return new WitItem.InterfaceDef(WitMeta.none(), this.imported.iface().name(), List.copyOf(items));
	}

	// Walks a type use, registering every named definition it reaches.
	private void discover(WitType type) {
		switch (type) {
			case WitType.ListOf list -> discover(list.element());
			case WitType.OptionOf opt -> discover(opt.element());
			case WitType.ResultOf res -> {
				if (res.ok() != null) {
					discover(res.ok());
				}
				if (res.err() != null) {
					discover(res.err());
				}
			}
			case WitType.TupleOf tuple -> tuple.elements().forEach(this::discover);
			case WitType.BorrowOf borrow -> discoverResource(borrow.resource());
			case WitType.OwnOf own -> discoverResource(own.resource());
			case WitType.Named name -> discoverNamed(name);
			default -> {
				// a primitive: nothing to define
			}
		}
	}

	private void discoverNamed(WitType.Named reference) {
		WitItem definition = this.abi.resolveNamed(reference);
		String name = reference.name();
		if (definition instanceof WitItem.TypeAlias alias) {
			// An alias is transparent at the component boundary: the type it names is
			// what
			// the instance type declares.
			discover(alias.target());
			return;
		}
		if (definition instanceof WitItem.ResourceDef) {
			discoverResource(name);
			return;
		}
		if (this.named.containsKey(name) || !this.visiting.add(name)) {
			return;
		}
		switch (definition) {
			case WitItem.RecordDef record -> {
				List<WitItem.Field> fields = new ArrayList<>();
				for (WitItem.Field field : record.fields()) {
					discover(field.type());
					fields.add(new WitItem.Field(WitMeta.none(), field.name(), field.type()));
				}
				this.named.put(name, new WitItem.RecordDef(WitMeta.none(), name, List.copyOf(fields)));
			}
			case WitItem.VariantDef variant -> {
				List<WitItem.Case> cases = new ArrayList<>();
				for (WitItem.Case c : variant.cases()) {
					if (c.payload() != null) {
						discover(c.payload());
					}
					cases.add(new WitItem.Case(WitMeta.none(), c.name(), c.payload()));
				}
				this.named.put(name, new WitItem.VariantDef(WitMeta.none(), name, List.copyOf(cases)));
			}
			case WitItem.EnumDef en ->
				this.named.put(name, new WitItem.EnumDef(WitMeta.none(), name, stripCases(en.cases())));
			case WitItem.FlagsDef flags ->
				this.named.put(name, new WitItem.FlagsDef(WitMeta.none(), name, stripCases(flags.cases())));
			default -> throw new UnsupportedOperationException(
					"the WIT type '" + name + "' cannot be emitted as a component import type");
		}
		this.visiting.remove(name);
	}

	private void discoverResource(String name) {
		if (this.resourceMembers.containsKey(name)) {
			return;
		}
		this.resourceMembers.put(name, new ArrayList<>());
		// The members are filled in as the bound functions are walked; the placeholder
		// fixes the resource's position in the discovery order.
		this.named.put(name, new WitItem.ResourceDef(WitMeta.none(), name, List.of()));
	}

	private static List<WitItem.Case> stripCases(List<WitItem.Case> cases) {
		List<WitItem.Case> out = new ArrayList<>();
		for (WitItem.Case c : cases) {
			out.add(new WitItem.Case(WitMeta.none(), c.name(), c.payload()));
		}
		return List.copyOf(out);
	}

	private static WitItem.FuncDef strip(WitItem.FuncDef def) {
		WitFunc func = def.func();
		return new WitItem.FuncDef(WitMeta.none(), def.name(), def.kind(),
				new WitFunc(func.async(), func.params(), func.result()));
	}

	// wasi:keyvalue/store@0.2.0-draft -> the WitRef of the interface.
	private static WitRef ref(String ifaceId) {
		WitPackageName pkg = packageOf(ifaceId);
		return new WitRef(pkg, interfaceNameOf(ifaceId));
	}

	// wasi:keyvalue/store@0.2.0-draft -> package wasi:keyvalue@0.2.0-draft.
	private static WitPackageName packageOf(String ifaceId) {
		int slash = ifaceId.indexOf('/');
		int colon = ifaceId.indexOf(':');
		if (slash < 0 || colon < 0 || colon > slash) {
			throw new IllegalStateException("Not a fully-qualified WIT interface id: " + ifaceId);
		}
		int at = ifaceId.indexOf('@', slash);
		String version = at < 0 ? null : ifaceId.substring(at + 1);
		return new WitPackageName(ifaceId.substring(0, colon), ifaceId.substring(colon + 1, slash), version);
	}

	private static String interfaceNameOf(String ifaceId) {
		int slash = ifaceId.indexOf('/');
		int at = ifaceId.indexOf('@', slash);
		return at < 0 ? ifaceId.substring(slash + 1) : ifaceId.substring(slash + 1, at);
	}

}
