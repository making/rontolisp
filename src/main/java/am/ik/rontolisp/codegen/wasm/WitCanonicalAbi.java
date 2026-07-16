package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.Type;
import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * Canonical-ABI layout and flattening calculator over the {@code am.ik.wit} model, for
 * the guest side of a lowered component import. Implements the size / alignment /
 * flattening rules of the component-model canonical ABI (despecialization:
 * {@code option<T>} = {@code variant { none, some(T) }}, {@code result<T, E>} =
 * {@code variant { ok(T), err(E) }}, {@code enum} = a payload-less variant, {@code tuple}
 * = a record), validated end-to-end against wasmtime's real {@code wasi:keyvalue} host
 * (the component-import reference probe).
 *
 * <p>
 * <strong>A WIT type only means something together with the interface scope its named
 * references resolve in</strong>, and that scope CHANGES as the walk descends. Follow a
 * {@code use} clause into another interface and you are looking at that interface's
 * types, whose own internal references were never imported into the scope you started
 * from: {@code wasi:http/outgoing-handler} uses {@code error-code} from
 * {@code wasi:http/types}, and {@code error-code}'s {@code DNS-error} case carries a
 * {@code DNS-error-payload} that {@code outgoing-handler} neither defines nor imports. So
 * every named type is resolved with {@link WitResolver#resolveOwned}, which reports the
 * interface that DEFINES it, and the walk continues in {@link #scopedTo that} interface's
 * scope. {@link VariantInfo} and {@link RecordInfo} carry the scope their payload / field
 * types belong to, because a consumer that gets those types back has to keep walking them
 * -- doing it against the original scope is how a layout comes out wrong (or a resolution
 * fails outright).
 *
 * <p>
 * A bare resource reference (and {@code own}/{@code borrow}) is an {@code i32} handle.
 */
final class WitCanonicalAbi {

	/** The canonical ABI caps flattened parameters at 16 (beyond = spill to memory). */
	static final int MAX_FLAT_PARAMS = 16;

	/** The canonical ABI caps flattened results at 1 (beyond = return pointer). */
	static final int MAX_FLAT_RESULTS = 1;

	private final WitResolver resolver;

	private final WitItem.InterfaceDef scope;

	// Sibling calculators for the other interfaces this one's types reach, by identity.
	private final java.util.Map<WitItem.InterfaceDef, WitCanonicalAbi> siblings = new java.util.IdentityHashMap<>();

	WitCanonicalAbi(WitResolver resolver, WitItem.InterfaceDef scope) {
		this.resolver = resolver;
		this.scope = scope;
	}

	/**
	 * A WIT type together with the calculator whose interface scope its named references
	 * resolve in. Handing the two around as a pair is what keeps a walk that has crossed
	 * a {@code use} clause honest.
	 *
	 * @param abi the calculator scoped to the interface the type is written in
	 * @param type the type
	 */
	record Scoped(WitCanonicalAbi abi, WitType type) {
	}

	/**
	 * A variant-shaped type (variant / enum / option / result) despecialized to its case
	 * names and payloads, with its layout facts.
	 *
	 * @param names the case names, in order
	 * @param payloads the case payload types ({@code null} = payload-less case)
	 * @param discSize the discriminant byte width (1 / 2 / 4)
	 * @param payloadOffset the payload offset from the variant's start
	 * @param abi the calculator whose scope the payload types are written in -- NOT
	 * necessarily the one {@link #variantInfo} was called on (a variant reached through a
	 * {@code use} clause belongs to the interface that defines it)
	 */
	record VariantInfo(List<String> names, List<@Nullable WitType> payloads, int discSize, int payloadOffset,
			WitCanonicalAbi abi) {
	}

	/**
	 * A record-shaped type (record / tuple) despecialized to its field names, types and
	 * offsets.
	 *
	 * @param names the field names (tuple fields are {@code "0"}, {@code "1"}, ...)
	 * @param types the field types
	 * @param offsets the field offsets from the record's start
	 * @param abi the calculator whose scope the field types are written in (see
	 * {@link VariantInfo#abi()})
	 */
	record RecordInfo(List<String> names, List<WitType> types, List<Integer> offsets, WitCanonicalAbi abi) {
	}

	/**
	 * The flat core signature of a lowered import call.
	 *
	 * @param params the flat parameter types (the return pointer included when present)
	 * @param results the flat result types (empty when void or indirect)
	 * @param retptr whether the results are returned indirectly through a caller-supplied
	 * return pointer (appended as the last parameter)
	 * @param retSize the byte size of the return area (0 without a retptr)
	 */
	record FlatSig(Type[] params, Type[] results, boolean retptr, int retSize) {
	}

	/**
	 * Computes the flat core signature of the given interface function, in the canonical
	 * ABI's "lower" direction (a guest-side import call): parameters are flattened up to
	 * {@link #MAX_FLAT_PARAMS}; a result whose flattening exceeds
	 * {@link #MAX_FLAT_RESULTS} becomes a trailing {@code i32} return pointer.
	 * @param func the interface function (with its owning resource, if any)
	 * @return the flat signature
	 */
	FlatSig flatSig(WitResolver.Func func) {
		List<Type> params = new ArrayList<>();
		if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
			params.add(Type.I32); // the borrowed self handle
		}
		for (var param : func.def().func().params()) {
			params.addAll(flatTypes(param.type()));
		}
		if (params.size() > MAX_FLAT_PARAMS) {
			throw new UnsupportedOperationException("'" + func.def().name() + "': its parameters flatten to "
					+ params.size() + " core values, and beyond " + MAX_FLAT_PARAMS
					+ " the canonical ABI spills them into a caller-allocated memory area -- a mechanism the component "
					+ "import boundary does not implement yet. The WIT types themselves are fine; it is the width of "
					+ "their flattening that is not");
		}
		WitType result = resultType(func);
		if (result == null) {
			return new FlatSig(params.toArray(new Type[0]), new Type[0], false, 0);
		}
		List<Type> resultFlats = flatTypes(result);
		if (resultFlats.size() <= MAX_FLAT_RESULTS) {
			return new FlatSig(params.toArray(new Type[0]), resultFlats.toArray(new Type[0]), false, 0);
		}
		params.add(Type.I32); // the return pointer
		return new FlatSig(params.toArray(new Type[0]), new Type[0], true, size(result));
	}

	/**
	 * The WIT result type of a function; a constructor returns its own resource (an
	 * {@code own} handle), which the WIT model leaves implicit.
	 * @param func the interface function
	 * @return the result type, or {@code null} for none
	 */
	@Nullable WitType resultType(WitResolver.Func func) {
		if (func.def().kind() == WitItem.FuncKind.CONSTRUCTOR) {
			return new WitType.OwnOf(java.util.Objects.requireNonNull(func.resource()));
		}
		return func.def().func().result();
	}

	/**
	 * The byte size of a WIT type in the canonical ABI's memory representation.
	 * @param type the WIT type
	 * @return the byte size
	 */
	int size(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> primSize(prim.name());
			case WitType.ListOf ignored -> 8;
			case WitType.OptionOf opt -> variantSize(payloadsOf(opt));
			case WitType.ResultOf res -> variantSize(payloadsOf(res));
			case WitType.TupleOf tuple -> recordSize(tuple.elements());
			case WitType.BorrowOf ignored -> 4;
			case WitType.OwnOf ignored -> 4;
			// A stream<T>/future<T> crosses as a bare i32 handle (the readable/writable
			// end),
			// so its memory footprint is the handle's, exactly like a resource handle;
			// the
			// element type governs the async read/write marshalling, not the handle
			// layout.
			case WitType.StreamOf ignored -> 4;
			case WitType.FutureOf ignored -> 4;
			case WitType.Named named -> {
				WitCanonicalAbi in = scopeOf(named);
				yield switch (resolveNamed(named)) {
					case WitItem.TypeAlias alias -> in.size(alias.target());
					case WitItem.RecordDef record -> in.recordSize(fieldTypes(record));
					case WitItem.VariantDef variant -> in.variantSize(casePayloads(variant));
					case WitItem.EnumDef en -> in.variantSize(nCases(en.cases().size()));
					case WitItem.ResourceDef ignored -> 4;
					default -> throw unsupported(named.name());
				};
			}
			default -> throw unsupported(describe(type));
		};
	}

	/**
	 * The byte alignment of a WIT type in the canonical ABI's memory representation.
	 * @param type the WIT type
	 * @return the alignment
	 */
	int alignment(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> primAlignment(prim.name());
			case WitType.ListOf ignored -> 4;
			case WitType.OptionOf opt -> variantAlignment(payloadsOf(opt));
			case WitType.ResultOf res -> variantAlignment(payloadsOf(res));
			case WitType.TupleOf tuple -> recordAlignment(tuple.elements());
			case WitType.BorrowOf ignored -> 4;
			case WitType.OwnOf ignored -> 4;
			case WitType.StreamOf ignored -> 4;
			case WitType.FutureOf ignored -> 4;
			case WitType.Named named -> {
				WitCanonicalAbi in = scopeOf(named);
				yield switch (resolveNamed(named)) {
					case WitItem.TypeAlias alias -> in.alignment(alias.target());
					case WitItem.RecordDef record -> in.recordAlignment(fieldTypes(record));
					case WitItem.VariantDef variant -> in.variantAlignment(casePayloads(variant));
					case WitItem.EnumDef en -> in.variantAlignment(nCases(en.cases().size()));
					case WitItem.ResourceDef ignored -> 4;
					default -> throw unsupported(named.name());
				};
			}
			default -> throw unsupported(describe(type));
		};
	}

	/**
	 * The flat core value types of a WIT type in the canonical ABI's flattening.
	 * @param type the WIT type
	 * @return the flat types, in order
	 */
	List<Type> flatTypes(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> switch (prim.name()) {
				case "bool", "char", "s8", "u8", "s16", "u16", "s32", "u32" -> List.of(Type.I32);
				case "s64", "u64" -> List.of(Type.I64);
				case "f32" -> List.of(Type.F32);
				case "f64" -> List.of(Type.F64);
				case "string" -> List.of(Type.I32, Type.I32);
				default -> throw unsupported(prim.name());
			};
			case WitType.ListOf ignored -> List.of(Type.I32, Type.I32);
			case WitType.OptionOf opt -> variantFlatTypes(payloadsOf(opt));
			case WitType.ResultOf res -> variantFlatTypes(payloadsOf(res));
			case WitType.TupleOf tuple -> recordFlatTypes(tuple.elements());
			case WitType.BorrowOf ignored -> List.of(Type.I32);
			case WitType.OwnOf ignored -> List.of(Type.I32);
			case WitType.StreamOf ignored -> List.of(Type.I32);
			case WitType.FutureOf ignored -> List.of(Type.I32);
			case WitType.Named named -> {
				WitCanonicalAbi in = scopeOf(named);
				yield switch (resolveNamed(named)) {
					case WitItem.TypeAlias alias -> in.flatTypes(alias.target());
					case WitItem.RecordDef record -> in.recordFlatTypes(fieldTypes(record));
					case WitItem.VariantDef variant -> in.variantFlatTypes(casePayloads(variant));
					case WitItem.EnumDef ignored -> List.of(Type.I32);
					case WitItem.ResourceDef ignored -> List.of(Type.I32);
					default -> throw unsupported(named.name());
				};
			}
			default -> throw unsupported(describe(type));
		};
	}

	/**
	 * Despecializes a variant-shaped type (variant / enum / option / result, directly or
	 * through a named definition) into its cases and layout.
	 * @param type the WIT type
	 * @return the variant info
	 */
	VariantInfo variantInfo(WitType type) {
		return switch (type) {
			case WitType.OptionOf opt -> variantInfoOf(List.of("none", "some"), payloadsOf(opt));
			case WitType.ResultOf res -> variantInfoOf(List.of("ok", "error"), payloadsOf(res));
			case WitType.Named named -> {
				WitCanonicalAbi in = scopeOf(named);
				yield switch (resolveNamed(named)) {
					case WitItem.TypeAlias alias -> in.variantInfo(alias.target());
					case WitItem.VariantDef variant -> in.variantInfoOf(
							variant.cases().stream().map(WitItem.Case::name).toList(), casePayloads(variant));
					case WitItem.EnumDef en -> in.variantInfoOf(en.cases().stream().map(WitItem.Case::name).toList(),
							nCases(en.cases().size()));
					default -> throw unsupported(named.name());
				};
			}
			default -> throw unsupported(describe(type));
		};
	}

	/**
	 * Despecializes a record-shaped type (record / tuple, directly or through a named
	 * definition) into its fields and offsets.
	 * @param type the WIT type
	 * @return the record info
	 */
	RecordInfo recordInfo(WitType type) {
		return switch (type) {
			case WitType.TupleOf tuple -> recordInfoOf(positionalNames(tuple.elements().size()), tuple.elements());
			case WitType.Named named -> {
				WitCanonicalAbi in = scopeOf(named);
				yield switch (resolveNamed(named)) {
					case WitItem.TypeAlias alias -> in.recordInfo(alias.target());
					case WitItem.RecordDef record ->
						in.recordInfoOf(record.fields().stream().map(WitItem.Field::name).toList(), fieldTypes(record));
					default -> throw unsupported(named.name());
				};
			}
			default -> throw unsupported(describe(type));
		};
	}

	/**
	 * Resolves a named type reference within this interface scope, reporting the
	 * interface that DEFINES it.
	 * @param named the named reference
	 * @return the definition and its defining interface
	 */
	WitResolver.Owned resolveOwned(WitType.Named named) {
		WitResolver.Owned owned = this.resolver.resolveOwned(this.scope, named.name());
		if (owned == null) {
			throw new UnsupportedOperationException("WIT type '" + named.name() + "' is not defined in interface '"
					+ this.scope.name() + "' (nor imported with a use clause)");
		}
		return owned;
	}

	/**
	 * Resolves a named type reference to its definition within this interface scope.
	 * @param named the named reference
	 * @return the definition
	 */
	WitItem resolveNamed(WitType.Named named) {
		return resolveOwned(named).item();
	}

	/**
	 * The calculator to continue a walk in once a named type has been resolved: the one
	 * scoped to the interface that DEFINES it, since that is where its fields' / cases'
	 * own type references resolve.
	 * @param named the named reference
	 * @return the calculator for the defining interface ({@code this} when it defines it)
	 */
	WitCanonicalAbi scopeOf(WitType.Named named) {
		return scopedTo(resolveOwned(named).owner());
	}

	/**
	 * The calculator scoped to another interface.
	 * @param owner the interface
	 * @return the calculator for it ({@code this} when it is this calculator's own scope)
	 */
	WitCanonicalAbi scopedTo(WitItem.InterfaceDef owner) {
		if (owner == this.scope) {
			return this;
		}
		return this.siblings.computeIfAbsent(owner, o -> new WitCanonicalAbi(this.resolver, o));
	}

	/**
	 * Follows a {@code type} alias chain (across interfaces) to the type it bottoms out
	 * at, and reports the scope THAT type is written in. A non-alias is returned
	 * unchanged, in this scope.
	 * @param type the type
	 * @return the aliased-through type and its scope
	 */
	Scoped resolveAliases(WitType type) {
		if (type instanceof WitType.Named named && resolveNamed(named) instanceof WitItem.TypeAlias alias) {
			return scopeOf(named).resolveAliases(alias.target());
		}
		return new Scoped(this, type);
	}

	/**
	 * Whether a type is {@code u8} (through any alias chain) -- the element type that
	 * makes a {@code list} a byte string rather than a refused {@code list<T>}.
	 * @param type the type
	 * @return {@code true} for {@code u8}
	 */
	boolean isU8(WitType type) {
		return resolveAliases(type).type() instanceof WitType.Prim prim && "u8".equals(prim.name());
	}

	// --- variant / record layout math (per the canonical ABI spec) ---

	private VariantInfo variantInfoOf(List<String> names, List<@Nullable WitType> payloads) {
		int discSize = discriminantSize(names.size());
		int maxAlign = 1;
		for (WitType payload : payloads) {
			if (payload != null) {
				maxAlign = Math.max(maxAlign, alignment(payload));
			}
		}
		return new VariantInfo(names, payloads, discSize, alignTo(discSize, maxAlign), this);
	}

	private RecordInfo recordInfoOf(List<String> names, List<WitType> types) {
		List<Integer> offsets = new ArrayList<>();
		int offset = 0;
		for (WitType t : types) {
			offset = alignTo(offset, alignment(t));
			offsets.add(offset);
			offset += size(t);
		}
		return new RecordInfo(names, types, offsets, this);
	}

	private int variantSize(List<@Nullable WitType> payloads) {
		VariantInfo info = variantInfoOf(positionalNames(payloads.size()), payloads);
		int maxPayload = 0;
		for (WitType payload : payloads) {
			if (payload != null) {
				maxPayload = Math.max(maxPayload, size(payload));
			}
		}
		return alignTo(info.payloadOffset() + maxPayload, variantAlignment(payloads));
	}

	private int variantAlignment(List<@Nullable WitType> payloads) {
		int align = discriminantSize(payloads.size());
		for (WitType payload : payloads) {
			if (payload != null) {
				align = Math.max(align, alignment(payload));
			}
		}
		return align;
	}

	private List<Type> variantFlatTypes(List<@Nullable WitType> payloads) {
		List<Type> joined = new ArrayList<>();
		for (WitType payload : payloads) {
			if (payload == null) {
				continue;
			}
			List<Type> flats = flatTypes(payload);
			for (int i = 0; i < flats.size(); i++) {
				if (i < joined.size()) {
					joined.set(i, join(joined.get(i), flats.get(i)));
				}
				else {
					joined.add(flats.get(i));
				}
			}
		}
		List<Type> result = new ArrayList<>();
		result.add(Type.I32); // the discriminant
		result.addAll(joined);
		return result;
	}

	private int recordSize(List<WitType> fields) {
		int offset = 0;
		for (WitType t : fields) {
			offset = alignTo(offset, alignment(t));
			offset += size(t);
		}
		return alignTo(offset, recordAlignment(fields));
	}

	private int recordAlignment(List<WitType> fields) {
		int align = 1;
		for (WitType t : fields) {
			align = Math.max(align, alignment(t));
		}
		return align;
	}

	private List<Type> recordFlatTypes(List<WitType> fields) {
		List<Type> flats = new ArrayList<>();
		for (WitType t : fields) {
			flats.addAll(flatTypes(t));
		}
		return flats;
	}

	private static Type join(Type a, Type b) {
		if (a == b) {
			return a;
		}
		if ((a == Type.I32 && b == Type.F32) || (a == Type.F32 && b == Type.I32)) {
			return Type.I32;
		}
		return Type.I64;
	}

	static int discriminantSize(int caseCount) {
		if (caseCount <= 256) {
			return 1;
		}
		return caseCount <= 65536 ? 2 : 4;
	}

	static int alignTo(int offset, int alignment) {
		return (offset + alignment - 1) / alignment * alignment;
	}

	private static int primSize(String name) {
		return switch (name) {
			case "bool", "s8", "u8" -> 1;
			case "s16", "u16" -> 2;
			case "s32", "u32", "f32", "char" -> 4;
			case "s64", "u64", "f64", "string" -> 8;
			default -> throw unsupported(name);
		};
	}

	private static int primAlignment(String name) {
		return switch (name) {
			case "bool", "s8", "u8" -> 1;
			case "s16", "u16" -> 2;
			case "s64", "u64", "f64" -> 8;
			default -> 4; // s32/u32/f32/char, and string (ptr alignment)
		};
	}

	private static List<WitType> fieldTypes(WitItem.RecordDef record) {
		return record.fields().stream().map(WitItem.Field::type).toList();
	}

	private static List<@Nullable WitType> casePayloads(WitItem.VariantDef variant) {
		List<@Nullable WitType> payloads = new ArrayList<>();
		for (WitItem.Case c : variant.cases()) {
			payloads.add(c.payload());
		}
		return payloads;
	}

	private static List<@Nullable WitType> payloadsOf(WitType.OptionOf opt) {
		List<@Nullable WitType> payloads = new ArrayList<>();
		payloads.add(null);
		payloads.add(opt.element());
		return payloads;
	}

	private static List<@Nullable WitType> payloadsOf(WitType.ResultOf res) {
		List<@Nullable WitType> payloads = new ArrayList<>();
		payloads.add(res.ok());
		payloads.add(res.err());
		return payloads;
	}

	private static List<@Nullable WitType> nCases(int n) {
		List<@Nullable WitType> payloads = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			payloads.add(null);
		}
		return payloads;
	}

	private static List<String> positionalNames(int n) {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			names.add(Integer.toString(i));
		}
		return names;
	}

	private static String describe(WitType type) {
		return type.getClass().getSimpleName();
	}

	private static UnsupportedOperationException unsupported(String what) {
		return new UnsupportedOperationException(
				"the WIT type '" + what + "' does not cross the component import boundary yet");
	}

}
