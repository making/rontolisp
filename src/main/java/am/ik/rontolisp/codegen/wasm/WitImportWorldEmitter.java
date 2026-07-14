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
import am.ik.wit.WitResolver;
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
 * what the tool reads back: {@code use} clauses, then named definitions (resources,
 * variants, records, enums, flags) in the order the traversal first reaches them, then
 * the freestanding functions. Doc comments and gates are dropped -- a component's type
 * does not carry them.
 *
 * <p>
 * A type the interface {@code use}s from ANOTHER interface is emitted as a {@code use}
 * clause, not copied in: it is that interface's type (nominally so, for a
 * {@code resource}), and duplicating the definition into this package block would print a
 * document claiming two unrelated types where the component has one -- see
 * {@link WitComponentTypeEncoder}. Which means the type walk has to track the scope it is
 * in, exactly as the encoder and the layout calculator do.
 */
final class WitImportWorldEmitter {

	private final WitCanonicalAbi abi;

	private final WasmComponentImportCompiler.Import imported;

	// Discovery-ordered named definitions THIS interface owns: the WIT name -> the item.
	private final Map<String, WitItem> named = new LinkedHashMap<>();

	// The types this interface uses from others: the defining interface -> the names, in
	// discovery order. Printed as `use` clauses ahead of the definitions.
	private final Map<WitItem.InterfaceDef, Set<String>> used = new LinkedHashMap<>();

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
		// The resources each interface must DECLARE because another one uses them. An
		// interface can be imported purely to own a type (wasi:io/error owns the `error`
		// resource that wasi:io/streams' `stream-error` carries), and its own bound
		// functions -- there may be none at all -- would never reach it: printing the
		// block
		// without it would leave a `use` clause pointing at nothing.
		Map<String, Set<String>> provides = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			for (WitComponentTypeEncoder.ForeignResource foreign : WitComponentTypeEncoder
				.foreignResourcesOf(imported)) {
				provides.computeIfAbsent(foreign.ownerIfaceId(), id -> new LinkedHashSet<>()).add(foreign.resource());
			}
		}
		Map<WitPackageName, List<WitItem>> byPackage = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			WitPackageName pkg = packageOf(imported.ifaceId());
			byPackage.computeIfAbsent(pkg, ignored -> new ArrayList<>())
				.add(new WitImportWorldEmitter(imported).iface(provides.getOrDefault(imported.ifaceId(), Set.of())));
		}
		List<WitItem> blocks = new ArrayList<>();
		byPackage
			.forEach((pkg, ifaces) -> blocks.add(new WitItem.PackageBlock(WitMeta.none(), pkg, List.copyOf(ifaces))));
		return blocks;
	}

	// The pruned interface: the named definitions the bound functions reach, in discovery
	// order, then the bound freestanding functions.
	private WitItem.InterfaceDef iface(Set<String> provided) {
		for (String resource : provided) {
			discoverResource(resource);
		}
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
				discover(this.abi, param.type());
			}
			WitType result = this.abi.resultType(func);
			if (result != null) {
				discover(this.abi, result);
			}
		}
		List<WitItem> items = new ArrayList<>();
		this.used.forEach((owner,
				names) -> items.add(new WitItem.Use(WitMeta.none(),
						ref(Objects.requireNonNull(this.imported.resolver().canonicalId(owner))),
						names.stream().map(name -> new WitItem.UseName(name, null)).toList())));
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

	// Walks a type use, registering every named definition it reaches. `abi` is the scope
	// the type's names resolve in: a definition reached through a `use` clause belongs to
	// the interface that owns it, and so do its own fields and cases.
	private void discover(WitCanonicalAbi abi, WitType type) {
		switch (type) {
			case WitType.ListOf list -> discover(abi, list.element());
			case WitType.OptionOf opt -> discover(abi, opt.element());
			case WitType.ResultOf res -> {
				if (res.ok() != null) {
					discover(abi, res.ok());
				}
				if (res.err() != null) {
					discover(abi, res.err());
				}
			}
			case WitType.TupleOf tuple -> tuple.elements().forEach(element -> discover(abi, element));
			case WitType.BorrowOf borrow -> discoverNamed(abi, new WitType.Named(borrow.resource()));
			case WitType.OwnOf own -> discoverNamed(abi, new WitType.Named(own.resource()));
			case WitType.Named name -> discoverNamed(abi, name);
			default -> {
				// a primitive: nothing to define
			}
		}
	}

	private void discoverNamed(WitCanonicalAbi abi, WitType.Named reference) {
		WitResolver.Owned owned = abi.resolveOwned(reference);
		WitItem definition = owned.item();
		String name = reference.name();
		// A type this interface does not define is the OTHER interface's type: say so
		// with
		// a `use` clause rather than copying the definition into this package block.
		if (owned.owner() != this.imported.iface()) {
			this.used.computeIfAbsent(owned.owner(), ignored -> new LinkedHashSet<>()).add(name);
			return;
		}
		if (definition instanceof WitItem.ResourceDef) {
			discoverResource(name);
			return;
		}
		if (this.named.containsKey(name) || !this.visiting.add(name)) {
			return;
		}
		WitCanonicalAbi in = abi.scopedTo(owned.owner());
		switch (definition) {
			case WitItem.TypeAlias alias -> {
				// An alias is transparent at the ABI boundary, but NOT in the document: a
				// bound signature can name it (`constructor(headers: headers)`), so
				// leaving
				// it out prints a block that references a type it never defines.
				discover(in, alias.target());
				this.named.put(name, new WitItem.TypeAlias(WitMeta.none(), name, alias.target()));
			}
			case WitItem.RecordDef record -> {
				List<WitItem.Field> fields = new ArrayList<>();
				for (WitItem.Field field : record.fields()) {
					discover(in, field.type());
					fields.add(new WitItem.Field(WitMeta.none(), field.name(), field.type()));
				}
				this.named.put(name, new WitItem.RecordDef(WitMeta.none(), name, List.copyOf(fields)));
			}
			case WitItem.VariantDef variant -> {
				List<WitItem.Case> cases = new ArrayList<>();
				for (WitItem.Case c : variant.cases()) {
					if (c.payload() != null) {
						discover(in, c.payload());
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
