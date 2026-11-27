package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import am.ik.wasm.ComponentWriter;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;
import org.jspecify.annotations.Nullable;

/**
 * Declares, in a component, the types the exports of a world name
 * ({@code rontolisp:wasm-export ... :wit}). The shape is the one
 * {@code wasm-tools component new} gives a world's exports, read off its output for a
 * probe world (records, a variant, an enum, flags, aliases, an unused type, a world-level
 * record; wasm-tools 1.263.0):
 *
 * <ul>
 * <li><strong>a world's own named type is a type IMPORT</strong> ({@code (type (record
 * ...)) (import "pair" (type (eq 0)))}), and a freestanding function's type names the
 * imported index;</li>
 * <li><strong>an exported interface's named types are defined bare</strong> in the
 * component, its functions lifted over those, and then handed to a nested <em>shim</em>
 * component that imports each used type ({@code import-type-<name>}) and each function
 * ({@code import-func-<name>}), declares every type again, EXPORTS it under its name, and
 * exports each function ascribed a type written over the exported types -- a function
 * type an instance exports may only name types the instance exports too, and an export is
 * what gives a type its name;</li>
 * <li><strong>every type the scope defines is declared</strong>, used by a function or
 * not, since a component importing the interface names them; in the order wasm-tools
 * declares them: definition order, a type waiting for the ones it mentions.</li>
 * </ul>
 *
 * <p>
 * A scope reaches this class with its {@code use} clauses written out as its own
 * definitions ({@code am.ik.wit.WitUses}): a value type is structural, so the component
 * declares the copy and imports no instance for it -- {@code wasm-tools component new}
 * imports the other interface's instance and aliases the type out of it, which
 * {@code wasm-tools component targets} does not ask for. A type this boundary does not
 * carry (a stream, a future, a handle of a resource the scope does not declare) is left
 * out, with every type that mentions it; a function naming one was refused when the world
 * was lowered ({@code WitExportDirective}).
 *
 * <p>
 * <strong>A resource of an exported interface</strong> is a type the component defined
 * before its core module was instantiated ({@link ExportedResources}), so the space is
 * told its index ({@link Space#define}); a shim imports it ({@code (sub resource)}) ahead
 * of everything else and exports that import under the resource's name. A named type in a
 * value position that is a resource is an owned handle of it.
 */
final class WitExportTypes {

	private WitExportTypes() {
	}

	/** How a scope's named type enters a type index space. */
	enum Naming {

		/** Defined, then imported under its name: a world's own type. */
		WORLD_IMPORT,

		/**
		 * Defined and referred to by that index: an interface's type, in the component.
		 */
		BARE,

		/** Defined, then imported as {@code import-type-<name>}: a shim's first half. */
		SHIM_IMPORT,

		/** Defined, then exported under its name: a shim's second half. */
		SHIM_EXPORT

	}

	/**
	 * One type index space being written: the component's own, or a shim's.
	 */
	static final class Space {

		private final BiConsumer<Integer, byte[]> sink;

		private int next;

		private final Map<String, Integer> named = new HashMap<>();

		private final Map<String, Integer> anonymous = new HashMap<>();

		private final List<String> imported = new ArrayList<>();

		// The first half of the shim this space is the second half of, whose import
		// of a resource the second half exports.
		private @Nullable Space importing;

		/**
		 * Creates a space.
		 * @param sink takes each entry with the id of the section it belongs to, in order
		 * @param first the first free type index
		 */
		Space(BiConsumer<Integer, byte[]> sink, int first) {
			this.sink = sink;
			this.next = first;
		}

		/**
		 * {@return the first free type index}
		 */
		int next() {
			return this.next;
		}

		/**
		 * Declares a type.
		 * @param defined the encoded defined type or function type
		 * @return its type index
		 */
		int type(byte[] defined) {
			this.sink.accept(ComponentWriter.SEC_TYPE, defined);
			return this.next++;
		}

		/**
		 * Names a type the component already holds: a resource it defined.
		 * @param key the scope's name and the type's, as {@code <scope>#<name>}
		 * @param index the type's index
		 */
		void define(String key, int index) {
			this.named.put(key, index);
		}

		/**
		 * {@return the index a scope's named type is referred to by here}
		 * @param scopeName the scope's name
		 * @param name the type's name
		 */
		int named(String scopeName, String name) {
			return Objects.requireNonNull(this.named.get(scopeName + "#" + name), name);
		}

		/**
		 * {@return the names of the types imported as {@code import-type-<name>}, in
		 * order}
		 */
		List<String> imported() {
			return this.imported;
		}

	}

	/**
	 * A scope whose types a component declares: an exported interface, or the world.
	 *
	 * @param abi the calculator scoped where the type names resolve
	 * @param name the scope's name: the exported interface's id, or the world's name
	 */
	record Scope(WitCanonicalAbi abi, String name) {
	}

	/**
	 * Declares every type the scope defines, in the order wasm-tools declares them. A
	 * type already declared in the space is left as it is.
	 * @param space the type index space
	 * @param naming how a named type enters it
	 * @param scope the scope
	 */
	static void declareScope(Space space, Naming naming, Scope scope) {
		for (WitItem definition : declared(scope.abi(), naming != Naming.WORLD_IMPORT)) {
			for (String name : namesOf(definition)) {
				named(space, naming, scope, name);
			}
		}
	}

	/**
	 * {@return the names a declared item gives the scope}: a definition's own, or the
	 * local names of a {@code use} clause
	 * @param declared an item {@link #declared} answered
	 */
	static List<String> namesOf(WitItem declared) {
		if (declared instanceof WitItem.Use use) {
			return use.names().stream().map(name -> name.alias() == null ? name.name() : name.alias()).toList();
		}
		return List.of(WitComponentTypeEncoder.definedNameOf(declared));
	}

	/**
	 * Declares the function type of an export typed from its WIT, with whatever its
	 * signature mentions that the space does not hold yet.
	 * @param space the type index space
	 * @param naming how a named type enters it
	 * @param signature the export
	 * @param forceAsync whether the type is the async one whatever the WIT says
	 * @return the function type's index
	 */
	static int funcType(Space space, Naming naming, WitExportSignature signature, boolean forceAsync) {
		Scope scope = signature.scope();
		List<String> names = new ArrayList<>();
		List<byte[]> types = new ArrayList<>();
		for (WitFunc.Param param : signature.func().params()) {
			names.add(param.name());
			types.add(valType(space, naming, scope, param.type()));
		}
		WitType result = signature.func().result();
		byte[] resultType = result == null ? null : valType(space, naming, scope, result);
		return space
			.type(signature.func().async() || forceAsync ? ComponentWriter.asyncFuncTypeOf(names, types, resultType)
					: ComponentWriter.funcTypeOf(names, types, resultType));
	}

	/**
	 * The type definitions of a scope a component declares, in wasm-tools' order:
	 * definition order, except that a type waits until every type it mentions has been
	 * declared (the earliest-defined type that can go, goes). A {@code use} clause an
	 * exported interface still holds names resources of another exported interface: it is
	 * declared where it stands, once, and gives the scope each of its names. A world's
	 * own clause, and a resource the world declares, are imports of the component, which
	 * declares none.
	 * @param abi the calculator scoped to the scope
	 * @param exported whether the scope is an exported interface's, not the world's
	 * @return the definitions, and the clauses
	 */
	static List<WitItem> declared(WitCanonicalAbi abi, boolean exported) {
		LinkedHashMap<String, WitItem> candidates = new LinkedHashMap<>();
		for (WitItem item : abi.scope().items()) {
			switch (item) {
				case WitItem.Use use when exported -> namesOf(use).forEach(name -> candidates.put(name, use));
				case WitItem.TypeAlias alias -> candidates.put(alias.name(), item);
				case WitItem.RecordDef record when !record.fields().isEmpty() -> candidates.put(record.name(), item);
				case WitItem.VariantDef variant -> candidates.put(variant.name(), item);
				case WitItem.EnumDef en -> candidates.put(en.name(), item);
				case WitItem.FlagsDef flags -> candidates.put(flags.name(), item);
				case WitItem.ResourceDef resource when exported -> candidates.put(resource.name(), item);
				default -> {
				}
			}
		}
		// Drop what mentions a type that is not a candidate, until nothing does.
		Map<String, List<String>> mentions = new HashMap<>();
		boolean dropped = true;
		while (dropped) {
			dropped = false;
			for (var entry : List.copyOf(candidates.entrySet())) {
				List<String> names = new ArrayList<>();
				if (!mentioned(entry.getValue(), names) || !candidates.keySet().containsAll(names)) {
					candidates.remove(entry.getKey());
					dropped = true;
				}
				else {
					mentions.put(entry.getKey(), names);
				}
			}
		}
		List<WitItem> ordered = new ArrayList<>();
		java.util.Set<String> done = new java.util.HashSet<>();
		while (!candidates.isEmpty()) {
			String ready = null;
			for (String name : candidates.keySet()) {
				if (done.containsAll(Objects.requireNonNull(mentions.get(name)))) {
					ready = name;
					break;
				}
			}
			if (ready == null) {
				// types that mention each other: no WIT, and nothing declares them
				break;
			}
			WitItem item = candidates.remove(ready);
			if (!(item instanceof WitItem.Use) || ordered.stream().noneMatch(other -> other == item)) {
				ordered.add(item);
			}
			done.add(ready);
		}
		return ordered;
	}

	// Collects the type names a definition mentions; false when it mentions a type no
	// component export declares (a stream, a future).
	private static boolean mentioned(WitItem definition, List<String> names) {
		return switch (definition) {
			case WitItem.TypeAlias alias -> mentioned(alias.target(), names);
			case WitItem.RecordDef record -> record.fields().stream().allMatch(field -> mentioned(field.type(), names));
			case WitItem.VariantDef variant ->
				variant.cases().stream().allMatch(c -> c.payload() == null || mentioned(c.payload(), names));
			default -> true;
		};
	}

	private static boolean mentioned(WitType type, List<String> names) {
		return switch (type) {
			case WitType.Prim ignored -> true;
			case WitType.Named named -> names.add(named.name());
			case WitType.ListOf list -> mentioned(list.element(), names);
			case WitType.OptionOf option -> mentioned(option.element(), names);
			case WitType.ResultOf res ->
				(res.ok() == null || mentioned(res.ok(), names)) && (res.err() == null || mentioned(res.err(), names));
			case WitType.TupleOf tuple -> tuple.elements().stream().allMatch(element -> mentioned(element, names));
			case WitType.OwnOf own -> names.add(own.resource());
			case WitType.BorrowOf borrow -> names.add(borrow.resource());
			default -> false;
		};
	}

	// The index a named type of the scope is referred to by, declaring it -- after the
	// types it mentions -- when the space does not hold it.
	private static int named(Space space, Naming naming, Scope scope, String name) {
		String key = scope.name() + "#" + name;
		Integer existing = space.named.get(key);
		if (existing != null) {
			return existing;
		}
		WitResolver.Owned owned = scope.abi().resolveOwned(new WitType.Named(name));
		if (owned.item() instanceof WitItem.ResourceDef) {
			int index = resource(space, naming, scope, name);
			space.named.put(key, index);
			return index;
		}
		byte @Nullable [] defined = switch (owned.item()) {
			case WitItem.RecordDef record -> {
				List<String> names = new ArrayList<>();
				List<byte[]> types = new ArrayList<>();
				for (WitItem.Field field : record.fields()) {
					names.add(field.name());
					types.add(valType(space, naming, scope, field.type()));
				}
				yield ComponentWriter.definedRecordOf(names, types);
			}
			case WitItem.VariantDef variant -> {
				List<String> names = new ArrayList<>();
				List<byte @Nullable []> payloads = new ArrayList<>();
				for (WitItem.Case c : variant.cases()) {
					names.add(c.name());
					payloads.add(c.payload() == null ? null : valType(space, naming, scope, c.payload()));
				}
				yield ComponentWriter.definedVariantOf(names, payloads);
			}
			case WitItem.EnumDef en ->
				ComponentWriter.definedEnumOf(en.cases().stream().map(WitItem.Case::name).toList());
			case WitItem.FlagsDef flags ->
				ComponentWriter.definedFlagsOf(flags.cases().stream().map(WitItem.Case::name).toList());
			case WitItem.TypeAlias alias -> switch (alias.target()) {
				case WitType.Prim prim -> ComponentWriter.definedPrim(WitComponentTypeEncoder.primCode(prim.name()));
				// another name of a named type: no definition of its own
				case WitType.Named ignored -> null;
				default -> defined(space, naming, scope, alias.target());
			};
			default -> throw new UnsupportedOperationException(
					"the WIT type '" + name + "' cannot be declared as a component export type");
		};
		int definition = defined != null ? space.type(defined)
				: named(space, naming, scope, ((WitType.Named) ((WitItem.TypeAlias) owned.item()).target()).name());
		int index = switch (naming) {
			case BARE -> definition;
			case WORLD_IMPORT -> {
				space.sink.accept(ComponentWriter.SEC_IMPORT, ComponentWriter.importTypeEq(name, definition));
				yield space.next++;
			}
			case SHIM_IMPORT -> {
				space.sink.accept(ComponentWriter.SEC_IMPORT,
						ComponentWriter.importTypeEq("import-type-" + name, definition));
				space.imported.add(name);
				yield space.next++;
			}
			case SHIM_EXPORT -> {
				space.sink.accept(ComponentWriter.SEC_EXPORT, ComponentWriter.exportType(name, definition));
				yield space.next++;
			}
		};
		space.named.put(key, index);
		return index;
	}

	// A resource of the scope where the space does not hold it yet: a shim's first half
	// imports it, its second half exports that import. The component itself defined
	// the type ahead of its core module (Space.define) -- under the interface that
	// declares it, which another's use clause names --, and a world has none.
	private static int resource(Space space, Naming naming, Scope scope, String name) {
		return switch (naming) {
			case BARE -> {
				WitResolver.Owned owned = scope.abi().resolveOwned(new WitType.Named(name));
				String owner = scope.abi().resolver().canonicalId(owned.owner());
				Integer defined = owner == null ? null
						: space.named.get(owner + "#" + WitComponentTypeEncoder.definedNameOf(owned.item()));
				if (defined == null) {
					throw new IllegalStateException(
							"the resource '" + name + "' of " + scope.name() + " is defined by no component");
				}
				yield defined;
			}
			case SHIM_IMPORT -> {
				space.sink.accept(ComponentWriter.SEC_IMPORT,
						ComponentWriter.importTypeSubResource("import-type-" + name));
				space.imported.add(name);
				yield space.next++;
			}
			case SHIM_EXPORT -> {
				space.sink.accept(ComponentWriter.SEC_EXPORT, ComponentWriter.exportType(name,
						Objects.requireNonNull(space.importing).named(scope.name(), name)));
				yield space.next++;
			}
			default -> throw new IllegalStateException(
					"the resource '" + name + "' of " + scope.name() + " is defined by no component");
		};
	}

	// Whether a name of the scope is a resource's, by whatever alias: in a value
	// position it is then an owned handle.
	private static boolean isResource(Scope scope, String name) {
		WitCanonicalAbi.Scoped resolved = scope.abi().resolveAliases(new WitType.Named(name));
		return resolved.type() instanceof WitType.Named named
				&& resolved.abi().resolveNamed(named) instanceof WitItem.ResourceDef;
	}

	// The valtype operand of a type use: a primitive inline, a named type by the index
	// its scope's declaration gave it, a list / option / result / tuple / handle by the
	// index of its definition, declared once per shape.
	private static byte[] valType(Space space, Naming naming, Scope scope, WitType type) {
		return switch (type) {
			case WitType.Prim prim -> ComponentWriter.valTypePrim(WitComponentTypeEncoder.primCode(prim.name()));
			case WitType.Named named when !isResource(scope, named.name()) ->
				ComponentWriter.valTypeIndex(named(space, naming, scope, named.name()));
			default -> {
				byte[] defined = defined(space, naming, scope, type);
				String shape = java.util.HexFormat.of().formatHex(defined);
				Integer existing = space.anonymous.get(shape);
				if (existing == null) {
					existing = space.type(defined);
					space.anonymous.put(shape, existing);
				}
				yield ComponentWriter.valTypeIndex(existing);
			}
		};
	}

	// The defined type of a list / option / result / tuple / handle, its operands
	// declared first.
	private static byte[] defined(Space space, Naming naming, Scope scope, WitType type) {
		return switch (type) {
			case WitType.Named named -> ComponentWriter.definedOwn(named(space, naming, scope, named.name()));
			case WitType.OwnOf own -> ComponentWriter.definedOwn(named(space, naming, scope, own.resource()));
			case WitType.BorrowOf borrow ->
				ComponentWriter.definedBorrow(named(space, naming, scope, borrow.resource()));
			case WitType.ListOf list -> ComponentWriter.definedListOf(valType(space, naming, scope, list.element()));
			case WitType.OptionOf option ->
				ComponentWriter.definedOptionOf(valType(space, naming, scope, option.element()));
			case WitType.ResultOf res ->
				ComponentWriter.definedResultOf(res.ok() == null ? null : valType(space, naming, scope, res.ok()),
						res.err() == null ? null : valType(space, naming, scope, res.err()));
			case WitType.TupleOf tuple -> {
				List<byte[]> elements = new ArrayList<>();
				for (WitType element : tuple.elements()) {
					elements.add(valType(space, naming, scope, element));
				}
				yield ComponentWriter.definedTupleOf(elements);
			}
			default -> throw new UnsupportedOperationException(
					"the WIT type " + type + " cannot be declared as a component export type");
		};
	}

	/**
	 * One function of an exported interface, as its shim sees it.
	 *
	 * @param name the function's label
	 * @param signature its WIT function, for an export typed from its WIT, or
	 * {@code null}
	 * @param flatType its encoded function type over primitives, for an export that is
	 * not, or {@code null}
	 */
	record Member(String name, @Nullable WitExportSignature signature, byte @Nullable [] flatType) {
	}

	/**
	 * A shim: the nested component, and the types it imports.
	 *
	 * @param component the encoded component
	 * @param importedTypes the names of the scope's types it imports as
	 * {@code import-type-<name>}, in order; each function it imports as
	 * {@code import-func-<name>}
	 */
	record Shim(byte[] component, List<String> importedTypes) {
	}

	/**
	 * Builds the shim of an exported interface that declares types: it imports each
	 * function and each type one mentions, and exports every type of the interface and
	 * each function, typed over the exported types.
	 * @param scope the interface
	 * @param members the interface's exported functions, in order; none for an interface
	 * of types alone
	 * @param forceAsync whether every function's type is the async one
	 * @return the shim
	 */
	static Shim shim(Scope scope, List<Member> members, boolean forceAsync) {
		ComponentWriter nested = new ComponentWriter();
		BiConsumer<Integer, byte[]> sink = (section, entry) -> nested.rawSection(section,
				ComponentWriter.vec(List.of(entry)));
		Space imports = new Space(sink, 0);
		// the interface's resources first: every function over one names its import
		for (WitItem definition : declared(scope.abi(), true)) {
			if (definition instanceof WitItem.ResourceDef || definition instanceof WitItem.Use) {
				for (String name : namesOf(definition)) {
					named(imports, Naming.SHIM_IMPORT, scope, name);
				}
			}
		}
		for (Member member : members) {
			WitExportSignature signature = member.signature();
			int type = signature == null ? imports.type(Objects.requireNonNull(member.flatType()))
					: funcType(imports, Naming.SHIM_IMPORT, signature, forceAsync);
			sink.accept(ComponentWriter.SEC_IMPORT, ComponentWriter.importFunc(importName(member.name()), type));
		}
		// The second half names nothing of the first: its types are declared again and
		// exported, in a fresh view of the same index space.
		Space exports = new Space(sink, imports.next());
		exports.importing = imports;
		declareScope(exports, Naming.SHIM_EXPORT, scope);
		for (int i = 0; i < members.size(); i++) {
			Member member = members.get(i);
			WitExportSignature signature = member.signature();
			int type = signature == null ? exports.type(Objects.requireNonNull(member.flatType()))
					: funcType(exports, Naming.SHIM_EXPORT, signature, forceAsync);
			sink.accept(ComponentWriter.SEC_EXPORT, ComponentWriter.exportFuncAs(member.name(), i, type));
		}
		return new Shim(nested.toByteArray(), List.copyOf(imports.imported()));
	}

	/**
	 * A resource an interface names in a {@code use} clause: the exported interface that
	 * declares it, and its name there.
	 *
	 * @param ifaceId the declaring interface's id
	 * @param name the resource's name in that interface
	 */
	record Foreign(String ifaceId, String name) {
	}

	/**
	 * {@return the resource of another interface a name of the scope stands for}, or
	 * {@code null} for a type the scope defines itself
	 * @param scope the scope
	 * @param name a type's name in it
	 */
	static @Nullable Foreign foreign(Scope scope, String name) {
		WitResolver.Owned owned = scope.abi().resolveOwned(new WitType.Named(name));
		if (!(owned.item() instanceof WitItem.ResourceDef resource) || owned.owner() == scope.abi().scope()) {
			return null;
		}
		return new Foreign(Objects.requireNonNull(scope.abi().resolver().canonicalId(owned.owner())), resource.name());
	}

	/**
	 * The index the component refers to a scope's named type by, declaring it when the
	 * space does not hold it: what a shim's type import is instantiated with.
	 * @param space the component's type index space
	 * @param scope the scope
	 * @param name the type's name
	 * @return the index
	 */
	static int bare(Space space, Scope scope, String name) {
		return named(space, Naming.BARE, scope, name);
	}

	/**
	 * The name a shim imports a function of its interface under, as wasm-tools spells it:
	 * {@code import-func-<name>}, and for a resource's function
	 * {@code import-constructor-<r>}, {@code import-method-<r>-<m>} or
	 * {@code import-static-<r>-<f>}.
	 * @param label the function's export name ({@code [method]r.m} for a method)
	 * @return the import name
	 */
	static String importName(String label) {
		if (label.startsWith("[")) {
			int close = label.indexOf(']');
			return "import-" + label.substring(1, close) + "-" + label.substring(close + 1).replace('.', '-');
		}
		return "import-func-" + label;
	}

}
