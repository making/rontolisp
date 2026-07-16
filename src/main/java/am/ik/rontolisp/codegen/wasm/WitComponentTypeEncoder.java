package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.wasm.ComponentWriter;
import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;
import org.jspecify.annotations.Nullable;

/**
 * Encodes an imported WIT interface as a component-model <strong>instance type</strong>
 * (the {@code ty} of the component import entry), restricted to the functions the program
 * binds. The declaration grammar and index rules were byte-validated against
 * {@code wasm-tools component new} output (see {@code ComponentWriterTest}): a resource
 * is an {@code export "name" (type (sub resource))} declaration, a named type
 * (variant/record/enum/flags) is a type declaration followed by an {@code eq}-bound
 * export of the same name, structural types (list/option/result/tuple) are unexported
 * type declarations deduplicated by shape, and both type declarations and type-bound
 * exports append to the instance type's LOCAL type index space (function exports do not).
 * References always point at already-declared indices, so everything is created on first
 * use, in function order.
 *
 * <p>
 * <strong>A resource an interface {@code use}s from ANOTHER interface is not declared
 * here.</strong> A component-model resource is NOMINAL, so {@code wasi:http/types}'s
 * {@code input-stream} -- which it does not define but {@code use}s from
 * {@code wasi:io/streams} -- has to BE that interface's type: the enclosing component
 * projects it out of the defining instance ({@link ComponentWriter#aliasInstanceType})
 * and this encoder points at that component type index with an
 * {@link ComponentWriter#instanceDeclAliasOuterType alias outer}, then re-exports it
 * under the name the {@code use} clause gives it. Declaring a fresh
 * {@code (sub resource)} instead would mint a second, unrelated resource: the host's real
 * instance has no such export to satisfy it, and a handle from one interface would index
 * the other's table. Non-resource types stay structural (the component model compares
 * them structurally), so a {@code use}d record / variant / enum is still inlined.
 *
 * <p>
 * Because an {@code alias outer} needs a component type index that only exists once the
 * DEFINING interface has been imported, encoding runs twice: a collecting pass (where
 * {@link OuterResources} records what was asked for and answers a dummy index) tells the
 * builder which types to alias out and in what order, and the emitting pass answers the
 * real indices. Both passes traverse identically, so the local index space is the same.
 */
final class WitComponentTypeEncoder {

	/**
	 * Supplies the component type index of a resource an interface {@code use}s from
	 * another interface -- the index the enclosing component aliased it out of the
	 * defining instance to.
	 */
	interface OuterResources {

		/**
		 * Returns the component type index of a foreign resource.
		 * @param ownerIfaceId the canonical id of the interface that DEFINES the resource
		 * @param resource the resource's name in that interface
		 * @return the component type index (a dummy in the collecting pass)
		 */
		int indexOf(String ownerIfaceId, String resource);

	}

	private final WitCanonicalAbi abi;

	private final WitResolver resolver;

	private final WitItem.InterfaceDef iface;

	private final OuterResources outer;

	private final List<byte[]> decls = new ArrayList<>();

	// Structural memo: a rendered shape key -> the local type index that defines it.
	private final Map<String, Integer> memo = new LinkedHashMap<>();

	private int nextLocal;

	private WitComponentTypeEncoder(WasmComponentImportCompiler.Import imported, OuterResources outer) {
		this.resolver = imported.resolver();
		this.iface = imported.iface();
		this.abi = new WitCanonicalAbi(this.resolver, this.iface);
		this.outer = outer;
	}

	/**
	 * Encodes the instance type of the given import: the resources other imported
	 * interfaces project out of it, then its bound functions.
	 * @param imported the parsed component import
	 * @param outer the component type indices of the resources this interface uses from
	 * other interfaces
	 * @param provided the resources this interface must EXPORT because another imported
	 * interface uses them -- an instance type can only be projected from
	 * ({@link ComponentWriter#aliasInstanceType}) for a name it exports, and a function
	 * signature is not the only thing that can reach a resource. An interface may end up
	 * with these and no functions at all: a real fetch component's {@code wasi:io/error}
	 * instance type is exactly one resource and nothing else.
	 * @return the encoded instance type bytes
	 */
	static byte[] encode(WasmComponentImportCompiler.Import imported, OuterResources outer,
			java.util.Set<String> provided) {
		WitComponentTypeEncoder encoder = new WitComponentTypeEncoder(imported, outer);
		for (String resource : provided) {
			encoder.resourceIndex(encoder.abi, resource);
		}
		for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
			encoder.declareFunction(decl.func(), decl.field(), false);
		}
		// Async func members follow the sync ones; their instance-type declaration is the
		// async function type (tag 0x43) -- the host's real instance declares them async,
		// and instance linking checks the flag.
		for (WasmComponentImportCompiler.AsyncCall call : imported.calls()) {
			encoder.declareFunction(call.func(), call.field(), true);
		}
		return ComponentWriter.instanceTypeOf(encoder.decls);
	}

	/**
	 * A resource an interface {@code use}s from another interface.
	 *
	 * @param ownerIfaceId the canonical id of the interface that DEFINES the resource
	 * @param resource the resource's name in that interface (the name its instance
	 * exports it under, and so the name to project it out by)
	 */
	record ForeignResource(String ownerIfaceId, String resource) {
	}

	/**
	 * Returns the resources this import uses from OTHER interfaces, in the order the
	 * encoder reaches them &mdash; the collecting pass. It runs the very same traversal
	 * {@link #encode} does, so the caller cannot project a type the emitting pass will
	 * not ask for, or miss one that it will.
	 * @param imported the parsed component import
	 * @return the foreign resources, deduplicated, in first-use order (empty for an
	 * interface that uses none, which is every import before this machinery existed
	 * &mdash; hence byte-identical output for them)
	 */
	static List<ForeignResource> foreignResourcesOf(WasmComponentImportCompiler.Import imported) {
		final List<ForeignResource> found = new ArrayList<>();
		final Map<String, Boolean> seen = new LinkedHashMap<>();
		encode(imported, (ownerIfaceId, resource) -> {
			if (seen.putIfAbsent(ownerIfaceId + "#" + resource, Boolean.TRUE) == null) {
				found.add(new ForeignResource(ownerIfaceId, resource));
			}
			return 0; // a dummy: the collecting pass throws its bytes away
		}, java.util.Set.of());
		return found;
	}

	private void declareFunction(WitResolver.Func func, String field, boolean async) {
		List<String> paramNames = new ArrayList<>();
		List<byte[]> paramTypes = new ArrayList<>();
		if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
			paramNames.add("self");
			paramTypes.add(ComponentWriter.valTypeIndex(borrowIndex(this.abi, func.resource())));
		}
		for (var param : func.def().func().params()) {
			paramNames.add(param.name());
			paramTypes.add(valType(this.abi, param.type()));
		}
		WitType result = this.abi.resultType(func);
		byte[] resultType = result == null ? null : valType(this.abi, result);
		int funcType = addDecl(async ? ComponentWriter.asyncFuncTypeOf(paramNames, paramTypes, resultType)
				: ComponentWriter.funcTypeOf(paramNames, paramTypes, resultType));
		this.decls.add(ComponentWriter.instanceDeclExportFunc(field, funcType));
	}

	// The encoded valtype operand of a WIT type use: a primitive is inlined by its code;
	// everything else is referenced by the local index of its (memoized, on-demand) type
	// declaration -- which {@link #definedIndexOf} computes. A transparent alias
	// (`type headers = fields`, `type field-key = string`) is followed here, so an
	// alias-to-primitive inlines as a primitive valtype rather than seeking a
	// (nonexistent) type index.
	private byte[] valType(WitCanonicalAbi abi, WitType type) {
		if (type instanceof WitType.Prim prim) {
			return ComponentWriter.valTypePrim(primCode(prim.name()));
		}
		if (type instanceof WitType.Named named) {
			WitResolver.Owned owned = abi.resolveOwned(named);
			if (owned.item() instanceof WitItem.TypeAlias alias) {
				return valType(abi.scopedTo(owned.owner()), alias.target());
			}
		}
		return ComponentWriter.valTypeIndex(definedIndexOf(abi, type));
	}

	// The local component-type index of a WIT type that is represented by its own
	// defined-type declaration -- everything except a bare primitive, which is inlined
	// and
	// has no index. `abi` is the scope the type's named references resolve in, and it
	// CHANGES on the way down: a type reached through a `use` clause is written in the
	// interface that defines it, and so are its fields and cases. A
	// `stream<T>`/`future<T>`
	// references its element/payload BY INDEX (unlike list/option, which inline a
	// valtype),
	// so those arms resolve it through this method rather than through {@link #valType}.
	private int definedIndexOf(WitCanonicalAbi abi, WitType type) {
		return switch (type) {
			case WitType.Prim prim -> throw new UnsupportedOperationException("the primitive WIT type '" + prim.name()
					+ "' is inlined as a valtype and has no component type index");
			case WitType.ListOf list -> memoized("list<" + key(abi, list.element()) + ">",
					() -> ComponentWriter.definedListOf(valType(abi, list.element())));
			case WitType.OptionOf opt -> memoized("option<" + key(abi, opt.element()) + ">",
					() -> ComponentWriter.definedOptionOf(valType(abi, opt.element())));
			case WitType.ResultOf res -> memoized(
					"result<" + (res.ok() == null ? "_" : key(abi, res.ok())) + ","
							+ (res.err() == null ? "_" : key(abi, res.err())) + ">",
					() -> ComponentWriter.definedResultOf(res.ok() == null ? null : valType(abi, res.ok()),
							res.err() == null ? null : valType(abi, res.err())));
			case WitType.TupleOf tuple -> memoized(key(abi, tuple), () -> {
				List<byte[]> elements = new ArrayList<>();
				for (WitType element : tuple.elements()) {
					elements.add(valType(abi, element));
				}
				return ComponentWriter.definedTupleOf(elements);
			});
			// A stream<u8> inlines its u8 element (definedStream), matching the base
			// adapter's own stream<u8>; a stream over a defined element references it by
			// index. A future references its payload by index (definedFuture). The
			// encode-first discipline of `memoized` claims the element/payload's index
			// before the stream/future's own, so the forward reference is valid.
			case WitType.StreamOf stream -> memoized(key(abi, stream), () -> {
				WitType element = stream.element();
				if (element instanceof WitType.Prim prim) {
					return ComponentWriter.definedStream(primCode(prim.name()));
				}
				if (element == null) {
					throw new UnsupportedOperationException(
							"a bare stream (no element type) cannot be encoded as a component import type");
				}
				return ComponentWriter.definedStreamOfType(definedIndexOf(abi, element));
			});
			case WitType.FutureOf fut -> memoized(key(abi, fut), () -> {
				WitType payload = fut.element();
				if (payload == null) {
					throw new UnsupportedOperationException(
							"a bare future (no payload type) cannot be encoded as a component import type");
				}
				return ComponentWriter.definedFuture(definedIndexOf(abi, payload));
			});
			case WitType.BorrowOf borrow -> borrowIndex(abi, borrow.resource());
			case WitType.OwnOf own -> ownIndex(abi, own.resource());
			case WitType.Named named -> {
				WitResolver.Owned owned = abi.resolveOwned(named);
				// The interface that DEFINES the type is where its own members resolve.
				WitCanonicalAbi in = abi.scopedTo(owned.owner());
				String id = nominalId(owned);
				yield switch (owned.item()) {
					case WitItem.TypeAlias alias -> definedIndexOf(in, alias.target());
					// A bare resource reference is an own handle (WIT's rule; borrow must
					// be
					// explicit). Key it by the name THIS interface writes -- a `use`
					// clause
					// may rename the resource, and resourceIndex resolves that name
					// itself.
					case WitItem.ResourceDef ignored -> ownIndex(abi, named.name());
					case WitItem.RecordDef record -> namedIndex(id, named.name(), () -> {
						List<String> names = new ArrayList<>();
						List<byte[]> types = new ArrayList<>();
						for (WitItem.Field field : record.fields()) {
							names.add(field.name());
							types.add(valType(in, field.type()));
						}
						return ComponentWriter.definedRecordOf(names, types);
					});
					case WitItem.VariantDef variant -> namedIndex(id, named.name(), () -> {
						List<String> names = new ArrayList<>();
						List<byte @Nullable []> payloads = new ArrayList<>();
						for (WitItem.Case c : variant.cases()) {
							names.add(c.name());
							payloads.add(c.payload() == null ? null : valType(in, c.payload()));
						}
						return ComponentWriter.definedVariantOf(names, payloads);
					});
					case WitItem.EnumDef en -> namedIndex(id, named.name(),
							() -> ComponentWriter.definedEnumOf(en.cases().stream().map(WitItem.Case::name).toList()));
					case WitItem.FlagsDef flags -> namedIndex(id, named.name(), () -> ComponentWriter
						.definedFlagsOf(flags.cases().stream().map(WitItem.Case::name).toList()));
					default -> throw new UnsupportedOperationException(
							"the WIT type '" + named.name() + "' cannot be encoded as a component import type");
				};
			}
		};
	}

	// A named type's identity across scopes: "<defining interface>#<its own name>". Two
	// interfaces reaching one type by different local names must share one declaration.
	private String nominalId(WitResolver.Owned owned) {
		return Objects.requireNonNull(this.resolver.canonicalId(owned.owner()), "canonical id") + "#"
				+ definedNameOf(owned.item());
	}

	static String definedNameOf(WitItem item) {
		return switch (item) {
			case WitItem.TypeAlias alias -> alias.name();
			case WitItem.RecordDef def -> def.name();
			case WitItem.VariantDef def -> def.name();
			case WitItem.EnumDef def -> def.name();
			case WitItem.FlagsDef def -> def.name();
			case WitItem.ResourceDef def -> def.name();
			default -> throw new IllegalStateException("not a type definition: " + item);
		};
	}

	// A structurally-memoized unexported type declaration.
	private int memoized(String shapeKey, java.util.function.Supplier<byte[]> encode) {
		Integer existing = this.memo.get(shapeKey);
		if (existing != null) {
			return existing;
		}
		// Encode FIRST (creating any nested declarations), then claim this type's index.
		byte[] encoded = encode.get();
		int index = addDecl(encoded);
		this.memo.put(shapeKey, index);
		return index;
	}

	// A named (variant/record/enum/flags) definition: one type declaration + an eq-bound
	// export of the WIT name; references use the exported index (the wasm-tools shape).
	// get/put rather than computeIfAbsent: encoding the definition can recursively
	// declare (and memoize) the types its fields use.
	private int namedIndex(String nominalId, String exportName, java.util.function.Supplier<byte[]> encode) {
		Integer existing = this.memo.get("named:" + nominalId);
		if (existing != null) {
			return existing;
		}
		byte[] encoded = encode.get();
		int defined = addDecl(encoded);
		this.decls.add(ComponentWriter.instanceDeclExportTypeEq(exportName, defined));
		int exported = this.nextLocal++;
		this.memo.put("named:" + nominalId, exported);
		return exported;
	}

	// The local type index of a resource, BY THE NAME THE GIVEN SCOPE CALLS IT (a `use`
	// clause may rename it). A resource the encoded interface defines is declared here;
	// one
	// it uses from another interface is aliased in from the enclosing component -- see
	// the
	// class comment for why a nominal type cannot be re-declared. The memo is keyed by
	// the
	// resource's true identity, so two scopes reaching one resource share one
	// declaration.
	private int resourceIndex(WitCanonicalAbi abi, String localName) {
		WitResolver.Owned owned = abi.resolveOwned(new WitType.Named(localName));
		if (!(owned.item() instanceof WitItem.ResourceDef def)) {
			throw new UnsupportedOperationException("the WIT type '" + localName + "' is not a resource");
		}
		String id = nominalId(owned);
		Integer existing = this.memo.get("resource:" + id);
		if (existing != null) {
			return existing;
		}
		int index;
		if (owned.owner() == this.iface) {
			this.decls.add(ComponentWriter.instanceDeclExportResource(localName));
			index = this.nextLocal++;
		}
		else {
			String ownerId = Objects.requireNonNull(this.resolver.canonicalId(owned.owner()));
			this.decls.add(ComponentWriter.instanceDeclAliasOuterType(1, this.outer.indexOf(ownerId, def.name())));
			int aliased = this.nextLocal++;
			this.decls.add(ComponentWriter.instanceDeclExportTypeEq(localName, aliased));
			index = this.nextLocal++;
		}
		this.memo.put("resource:" + id, index);
		return index;
	}

	private int borrowIndex(WitCanonicalAbi abi, String resource) {
		int resourceIdx = resourceIndex(abi, resource);
		return memoized("borrow:" + resourceIdx, () -> ComponentWriter.definedBorrow(resourceIdx));
	}

	private int ownIndex(WitCanonicalAbi abi, String resource) {
		int resourceIdx = resourceIndex(abi, resource);
		return memoized("own:" + resourceIdx, () -> ComponentWriter.definedOwn(resourceIdx));
	}

	private int addDecl(byte[] definedType) {
		this.decls.add(ComponentWriter.instanceDeclType(definedType));
		return this.nextLocal++;
	}

	// A stable structural key for memoization. A named type keys by its NOMINAL identity
	// (defining interface + name), so one type reached from two scopes -- or under two
	// `use` aliases -- memoizes to one declaration.
	private String key(WitCanonicalAbi abi, WitType type) {
		return switch (type) {
			case WitType.Prim prim -> prim.name();
			case WitType.ListOf list -> "list<" + key(abi, list.element()) + ">";
			case WitType.OptionOf opt -> "option<" + key(abi, opt.element()) + ">";
			case WitType.ResultOf res -> "result<" + (res.ok() == null ? "_" : key(abi, res.ok())) + ","
					+ (res.err() == null ? "_" : key(abi, res.err())) + ">";
			case WitType.TupleOf tuple -> {
				StringBuilder sb = new StringBuilder("tuple<");
				for (WitType element : tuple.elements()) {
					sb.append(key(abi, element)).append(',');
				}
				yield sb.append('>').toString();
			}
			case WitType.BorrowOf borrow ->
				"borrow<" + nominalId(abi.resolveOwned(new WitType.Named(borrow.resource()))) + ">";
			case WitType.OwnOf own -> "own<" + nominalId(abi.resolveOwned(new WitType.Named(own.resource()))) + ">";
			case WitType.StreamOf stream ->
				"stream<" + (stream.element() == null ? "" : key(abi, stream.element())) + ">";
			case WitType.FutureOf fut -> "future<" + (fut.element() == null ? "" : key(abi, fut.element())) + ">";
			case WitType.Named named -> "named:" + nominalId(abi.resolveOwned(named));
			default -> type.toString();
		};
	}

	static int primCode(String name) {
		return switch (name) {
			case "bool" -> ComponentWriter.VT_BOOL;
			case "s8" -> ComponentWriter.VT_S8;
			case "u8" -> ComponentWriter.VT_U8;
			case "s16" -> ComponentWriter.VT_S16;
			case "u16" -> ComponentWriter.VT_U16;
			case "s32" -> ComponentWriter.VT_S32;
			case "u32" -> ComponentWriter.VT_U32;
			case "s64" -> ComponentWriter.VT_S64;
			case "u64" -> ComponentWriter.VT_U64;
			case "f32" -> ComponentWriter.VT_F32;
			case "f64" -> ComponentWriter.VT_F64;
			case "char" -> ComponentWriter.VT_CHAR;
			case "string" -> ComponentWriter.VT_STRING;
			default -> throw new UnsupportedOperationException("Unknown WIT primitive: " + name);
		};
	}

}
