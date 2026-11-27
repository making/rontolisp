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

/**
 * Derives the <strong>component-level</strong> type index of a WIT type -- the operand a
 * {@code canon stream.*}/{@code future.*} built-in is typed by. This is the sibling of
 * {@link WitComponentTypeEncoder}, which writes the same shapes <em>inside an instance
 * type</em>; here they land in the component's own type index space ({@code SEC_TYPE}),
 * which is what a {@code canon} entry can reference.
 *
 * <p>
 * Value types are declared structurally (the component model compares them structurally),
 * so a {@code variant}/{@code record}/{@code enum} reached from a stream/future payload
 * is re-declared fresh rather than aliased out of the imported instance -- which keeps
 * the derivation independent of what the instance type happened to export. The one
 * exception is a <strong>resource</strong>: it is nominal, so it MUST be projected out of
 * the defining interface's imported instance ({@code alias export}), exactly like the
 * hand-assembled base adapter block projects {@code error-code}'s owner types. The caller
 * supplies the projection map (shared with the {@code use}-clause and drop projections,
 * so one resource is projected once) and the instance indices.
 *
 * <p>
 * Entries are accumulated in index order -- an {@code alias} and a {@code type}
 * declaration both append to the one component type index space -- and flushed as
 * coalesced sections by {@link #flush}. The encode-first discipline (children before
 * parent) keeps every reference pointing backwards, as the binary format requires.
 */
final class WitComponentLevelTypes {

	/** Supplies the component instance index an interface was imported at. */
	interface Instances {

		/**
		 * Returns the instance index of an imported interface.
		 * @param ifaceId the interface's canonical id
		 * @return the component instance index
		 * @throws UnsupportedOperationException when the interface is not imported
		 */
		int indexOf(String ifaceId);

	}

	private record Entry(int section, byte[] bytes) {
	}

	private final List<Entry> pending = new ArrayList<>();

	// Structural memo (nominal ids for named types / resources), shared across every
	// import of one component build, so one shape is declared once.
	private final Map<String, Integer> memo = new LinkedHashMap<>();

	// "<owner iface id>#<resource>" -> the projected component type index. SHARED with
	// the caller's use-clause/drop projections: a resource is nominal, so everything in
	// this component must point at the one projection.
	private final Map<String, Integer> outerOf;

	private final Instances instances;

	private int nextType;

	WitComponentLevelTypes(int nextType, Map<String, Integer> outerOf, Instances instances) {
		this.nextType = nextType;
		this.outerOf = outerOf;
		this.instances = instances;
	}

	/** The next free component type index after everything derived so far. */
	int nextType() {
		return this.nextType;
	}

	/**
	 * The component type index of a WIT type, deriving (and memoizing) its declaration
	 * chain on first use.
	 * @param resolver the resolver of the WIT document the type comes from
	 * @param abi the calculator scoped to the interface the type is written in
	 * @param type the type
	 * @return the component type index
	 */
	int indexOf(WitResolver resolver, WitCanonicalAbi abi, WitType type) {
		return switch (type) {
			case WitType.Prim prim ->
				memoized(prim.name(), () -> ComponentWriter.definedPrim(WitComponentTypeEncoder.primCode(prim.name())));
			case WitType.ListOf list -> memoized("list<" + key(resolver, abi, list.element()) + ">",
					() -> ComponentWriter.definedListOf(valType(resolver, abi, list.element())));
			case WitType.OptionOf opt -> memoized("option<" + key(resolver, abi, opt.element()) + ">",
					() -> ComponentWriter.definedOptionOf(valType(resolver, abi, opt.element())));
			case WitType.ResultOf res -> memoized(
					"result<" + (res.ok() == null ? "_" : key(resolver, abi, res.ok())) + ","
							+ (res.err() == null ? "_" : key(resolver, abi, res.err())) + ">",
					() -> ComponentWriter.definedResultOf(res.ok() == null ? null : valType(resolver, abi, res.ok()),
							res.err() == null ? null : valType(resolver, abi, res.err())));
			case WitType.TupleOf tuple -> memoized(key(resolver, abi, tuple), () -> {
				List<byte[]> elements = new ArrayList<>();
				for (WitType element : tuple.elements()) {
					elements.add(valType(resolver, abi, element));
				}
				return ComponentWriter.definedTupleOf(elements);
			});
			case WitType.StreamOf stream -> memoized(key(resolver, abi, stream), () -> {
				WitType element = stream.element();
				if (element == null) {
					throw new UnsupportedOperationException(
							"a bare stream (no element type) has no component-level type");
				}
				if (element instanceof WitType.Prim prim) {
					return ComponentWriter.definedStream(WitComponentTypeEncoder.primCode(prim.name()));
				}
				return ComponentWriter.definedStreamOfType(indexOf(resolver, abi, element));
			});
			case WitType.FutureOf fut -> memoized(key(resolver, abi, fut), () -> {
				WitType payload = fut.element();
				if (payload == null) {
					throw new UnsupportedOperationException(
							"a bare future (no payload type) has no component-level type");
				}
				return ComponentWriter.definedFuture(indexOf(resolver, abi, payload));
			});
			case WitType.BorrowOf borrow -> {
				int resource = resourceIndex(resolver, abi, borrow.resource());
				yield memoized("borrow:" + resource, () -> ComponentWriter.definedBorrow(resource));
			}
			case WitType.OwnOf own -> {
				int resource = resourceIndex(resolver, abi, own.resource());
				yield memoized("own:" + resource, () -> ComponentWriter.definedOwn(resource));
			}
			case WitType.Named named -> {
				WitResolver.Owned owned = abi.resolveOwned(named);
				WitCanonicalAbi in = abi.scopedTo(owned.owner());
				String id = nominalId(resolver, owned);
				yield switch (owned.item()) {
					case WitItem.TypeAlias alias -> indexOf(resolver, in, alias.target());
					// A bare resource reference is an own handle (WIT's rule).
					case WitItem.ResourceDef ignored -> {
						int resource = resourceIndex(resolver, abi, named.name());
						yield memoized("own:" + resource, () -> ComponentWriter.definedOwn(resource));
					}
					case WitItem.RecordDef record -> memoized("named:" + id, () -> {
						List<String> names = new ArrayList<>();
						List<byte[]> types = new ArrayList<>();
						for (WitItem.Field field : record.fields()) {
							names.add(field.name());
							types.add(valType(resolver, in, field.type()));
						}
						return ComponentWriter.definedRecordOf(names, types);
					});
					case WitItem.VariantDef variant -> memoized("named:" + id, () -> {
						List<String> names = new ArrayList<>();
						List<byte @org.jspecify.annotations.Nullable []> payloads = new ArrayList<>();
						for (WitItem.Case c : variant.cases()) {
							names.add(c.name());
							payloads.add(c.payload() == null ? null : valType(resolver, in, c.payload()));
						}
						return ComponentWriter.definedVariantOf(names, payloads);
					});
					case WitItem.EnumDef en -> memoized("named:" + id,
							() -> ComponentWriter.definedEnumOf(en.cases().stream().map(WitItem.Case::name).toList()));
					case WitItem.FlagsDef flags -> memoized("named:" + id, () -> ComponentWriter
						.definedFlagsOf(flags.cases().stream().map(WitItem.Case::name).toList()));
					default -> throw new UnsupportedOperationException(
							"the WIT type '" + named.name() + "' has no component-level type");
				};
			}
			default -> throw new UnsupportedOperationException(
					"the WIT type '" + type.getClass().getSimpleName() + "' has no component-level type");
		};
	}

	/**
	 * The resources a type reaches (through aliases, fields, cases and element types) --
	 * each must be exported by its defining interface's instance type so the projection
	 * can link. Run BEFORE the instance types are encoded (the {@code provides}
	 * machinery); it walks exactly what {@link #indexOf} will.
	 * @param resolver the resolver of the WIT document the type comes from
	 * @param abi the calculator scoped to the interface the type is written in
	 * @param type the type
	 * @param found the collected {@code (owner iface id, resource)} pairs, appended in
	 * first-use order
	 */
	static void collectResources(WitResolver resolver, WitCanonicalAbi abi, WitType type,
			List<WitComponentTypeEncoder.ForeignResource> found) {
		switch (type) {
			case WitType.ListOf list -> collectResources(resolver, abi, list.element(), found);
			case WitType.OptionOf opt -> collectResources(resolver, abi, opt.element(), found);
			case WitType.ResultOf res -> {
				if (res.ok() != null) {
					collectResources(resolver, abi, res.ok(), found);
				}
				if (res.err() != null) {
					collectResources(resolver, abi, res.err(), found);
				}
			}
			case WitType.TupleOf tuple -> {
				for (WitType element : tuple.elements()) {
					collectResources(resolver, abi, element, found);
				}
			}
			case WitType.StreamOf stream -> {
				if (stream.element() != null) {
					collectResources(resolver, abi, stream.element(), found);
				}
			}
			case WitType.FutureOf fut -> {
				if (fut.element() != null) {
					collectResources(resolver, abi, fut.element(), found);
				}
			}
			case WitType.BorrowOf borrow -> collectResource(resolver, abi, borrow.resource(), found);
			case WitType.OwnOf own -> collectResource(resolver, abi, own.resource(), found);
			case WitType.Named named -> {
				WitResolver.Owned owned = abi.resolveOwned(named);
				WitCanonicalAbi in = abi.scopedTo(owned.owner());
				switch (owned.item()) {
					case WitItem.TypeAlias alias -> collectResources(resolver, in, alias.target(), found);
					case WitItem.ResourceDef ignored -> collectResource(resolver, abi, named.name(), found);
					case WitItem.RecordDef record -> {
						for (WitItem.Field field : record.fields()) {
							collectResources(resolver, in, field.type(), found);
						}
					}
					case WitItem.VariantDef variant -> {
						for (WitItem.Case c : variant.cases()) {
							if (c.payload() != null) {
								collectResources(resolver, in, c.payload(), found);
							}
						}
					}
					default -> {
						// enum / flags reach no resource
					}
				}
			}
			default -> {
				// primitives reach no resource
			}
		}
	}

	private static void collectResource(WitResolver resolver, WitCanonicalAbi abi, String localName,
			List<WitComponentTypeEncoder.ForeignResource> found) {
		WitResolver.Owned owned = abi.resolveOwned(new WitType.Named(localName));
		if (!(owned.item() instanceof WitItem.ResourceDef def)) {
			throw new UnsupportedOperationException("the WIT type '" + localName + "' is not a resource");
		}
		String ownerId = Objects.requireNonNull(resolver.canonicalId(owned.owner()), "canonical id");
		found.add(new WitComponentTypeEncoder.ForeignResource(ownerId, def.name()));
	}

	/**
	 * Writes the accumulated declarations, coalescing adjacent entries of one section
	 * kind, and clears the buffer. Must run before any {@code canon} that references the
	 * derived indices (a component reference always points backwards).
	 * @param c the component writer
	 */
	void flush(ComponentWriter c) {
		int i = 0;
		while (i < this.pending.size()) {
			int section = this.pending.get(i).section();
			List<byte[]> run = new ArrayList<>();
			while (i < this.pending.size() && this.pending.get(i).section() == section) {
				run.add(this.pending.get(i).bytes());
				i++;
			}
			c.rawSection(section, ComponentWriter.vec(run));
		}
		this.pending.clear();
	}

	// The encoded valtype operand of a type use: a primitive is inlined by its code,
	// everything else referenced by its derived index.
	private byte[] valType(WitResolver resolver, WitCanonicalAbi abi, WitType type) {
		if (type instanceof WitType.Prim prim) {
			return ComponentWriter.valTypePrim(WitComponentTypeEncoder.primCode(prim.name()));
		}
		if (type instanceof WitType.Named named && abi.resolveNamed(named) instanceof WitItem.TypeAlias alias) {
			return valType(resolver, abi.scopeOf(named), alias.target());
		}
		return ComponentWriter.valTypeIndex(indexOf(resolver, abi, type));
	}

	// A memoized SEC_TYPE declaration: encode FIRST (creating any nested declarations),
	// then claim this type's index.
	private int memoized(String shapeKey, java.util.function.Supplier<byte[]> encode) {
		Integer existing = this.memo.get(shapeKey);
		if (existing != null) {
			return existing;
		}
		byte[] encoded = encode.get();
		this.pending.add(new Entry(ComponentWriter.SEC_TYPE, encoded));
		int index = this.nextType++;
		this.memo.put(shapeKey, index);
		return index;
	}

	// The projected component type index of a resource, BY THE NAME THE GIVEN SCOPE
	// CALLS IT; projects it out of the defining interface's imported instance on first
	// use (SEC_ALIAS also consumes a type index).
	private int resourceIndex(WitResolver resolver, WitCanonicalAbi abi, String localName) {
		WitResolver.Owned owned = abi.resolveOwned(new WitType.Named(localName));
		if (!(owned.item() instanceof WitItem.ResourceDef def)) {
			throw new UnsupportedOperationException("the WIT type '" + localName + "' is not a resource");
		}
		String ownerId = Objects.requireNonNull(resolver.canonicalId(owned.owner()), "canonical id");
		String key = ownerId + "#" + def.name();
		Integer existing = this.outerOf.get(key);
		if (existing != null) {
			return existing;
		}
		int instance = this.instances.indexOf(ownerId);
		this.pending.add(new Entry(ComponentWriter.SEC_ALIAS, ComponentWriter.aliasInstanceType(instance, def.name())));
		int index = this.nextType++;
		this.outerOf.put(key, index);
		return index;
	}

	private static String nominalId(WitResolver resolver, WitResolver.Owned owned) {
		return Objects.requireNonNull(resolver.canonicalId(owned.owner()), "canonical id") + "#"
				+ WitComponentTypeEncoder.definedNameOf(owned.item());
	}

	// A stable structural key (nominal ids for named types), mirroring the instance-type
	// encoder's.
	private String key(WitResolver resolver, WitCanonicalAbi abi, WitType type) {
		return switch (type) {
			case WitType.Prim prim -> prim.name();
			case WitType.ListOf list -> "list<" + key(resolver, abi, list.element()) + ">";
			case WitType.OptionOf opt -> "option<" + key(resolver, abi, opt.element()) + ">";
			case WitType.ResultOf res -> "result<" + (res.ok() == null ? "_" : key(resolver, abi, res.ok())) + ","
					+ (res.err() == null ? "_" : key(resolver, abi, res.err())) + ">";
			case WitType.TupleOf tuple -> {
				StringBuilder sb = new StringBuilder("tuple<");
				for (WitType element : tuple.elements()) {
					sb.append(key(resolver, abi, element)).append(',');
				}
				yield sb.append('>').toString();
			}
			case WitType.StreamOf stream ->
				"stream<" + (stream.element() == null ? "" : key(resolver, abi, stream.element())) + ">";
			case WitType.FutureOf fut ->
				"future<" + (fut.element() == null ? "" : key(resolver, abi, fut.element())) + ">";
			case WitType.BorrowOf borrow ->
				"borrow<" + nominalId(resolver, abi.resolveOwned(new WitType.Named(borrow.resource()))) + ">";
			case WitType.OwnOf own ->
				"own<" + nominalId(resolver, abi.resolveOwned(new WitType.Named(own.resource()))) + ">";
			case WitType.Named named -> "named:" + nominalId(resolver, abi.resolveOwned(named));
			default -> type.toString();
		};
	}

}
