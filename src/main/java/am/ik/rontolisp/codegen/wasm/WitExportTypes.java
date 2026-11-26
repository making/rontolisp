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
 * carry (a resource, or what holds its handle) is left out, with every type that mentions
 * it; a function naming one was refused when the world was lowered
 * ({@code WitExportDirective}).
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
		for (WitItem definition : declared(scope.abi())) {
			named(space, naming, scope, WitComponentTypeEncoder.definedNameOf(definition));
		}
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
	 * declared (the earliest-defined type that can go, goes).
	 * @param abi the calculator scoped to the scope
	 * @return the definitions
	 */
	static List<WitItem> declared(WitCanonicalAbi abi) {
		LinkedHashMap<String, WitItem> candidates = new LinkedHashMap<>();
		for (WitItem item : abi.scope().items()) {
			switch (item) {
				case WitItem.TypeAlias alias -> candidates.put(alias.name(), item);
				case WitItem.RecordDef record when !record.fields().isEmpty() -> candidates.put(record.name(), item);
				case WitItem.VariantDef variant -> candidates.put(variant.name(), item);
				case WitItem.EnumDef en -> candidates.put(en.name(), item);
				case WitItem.FlagsDef flags -> candidates.put(flags.name(), item);
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
			ordered.add(candidates.remove(ready));
			done.add(ready);
		}
		return ordered;
	}

	// Collects the type names a definition mentions; false when it mentions a type no
	// component export declares (a handle, a stream, a future).
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

	// The valtype operand of a type use: a primitive inline, a named type by the index
	// its scope's declaration gave it, a list / option / result / tuple by the index of
	// its definition, declared once per shape.
	private static byte[] valType(Space space, Naming naming, Scope scope, WitType type) {
		return switch (type) {
			case WitType.Prim prim -> ComponentWriter.valTypePrim(WitComponentTypeEncoder.primCode(prim.name()));
			case WitType.Named named -> ComponentWriter.valTypeIndex(named(space, naming, scope, named.name()));
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

	// The defined type of a list / option / result / tuple, its operands declared first.
	private static byte[] defined(Space space, Naming naming, Scope scope, WitType type) {
		return switch (type) {
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
		for (Member member : members) {
			WitExportSignature signature = member.signature();
			int type = signature == null ? imports.type(Objects.requireNonNull(member.flatType()))
					: funcType(imports, Naming.SHIM_IMPORT, signature, forceAsync);
			sink.accept(ComponentWriter.SEC_IMPORT, ComponentWriter.importFunc("import-func-" + member.name(), type));
		}
		// The second half names nothing of the first: its types are declared again and
		// exported, in a fresh view of the same index space.
		Space exports = new Space(sink, imports.next());
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

}
