package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * A WIT type use described whole, for a front end that binds a WIT surface before the
 * lowering runs and converts its own values ({@link WitImportDirective#describe},
 * {@link WitExportDirective#describe}): its {@link WitTypeMapper.Shape}, aliases
 * followed, every nested type with it. Both directives describe through here, and follow
 * an alias by the one rule here.
 */
final class WitShapes {

	private WitShapes() {
	}

	/**
	 * The whole shape of a type, every nested type described in the scope THAT type is
	 * written in (an option's element, a result's arms, a list's element, a record's
	 * fields, a variant's payloads, a tuple's elements). A nested type naming no
	 * definition, or a definition containing itself, has no representation
	 * ({@link WitTypeMapper.Rep#UNSUPPORTED}): an import's {@code lower()} reaches
	 * neither on the interpreter and the JVM, so the member is the front end's to refuse
	 * where a program calls it, never the whole interface's.
	 * @param type the type as written
	 * @param resolver the resolver over the WIT document
	 * @param iface the scope the type is written in: an interface, or a world's own type
	 * definitions
	 * @return the shape
	 */
	static WitTypeMapper.Shape of(WitType type, WitResolver resolver, WitItem.InterfaceDef iface) {
		return shapeOf(type, resolver, iface, new ArrayList<>());
	}

	// `path` holds the definitions being described.
	private static WitTypeMapper.Shape shapeOf(WitType type, WitResolver resolver, WitItem.InterfaceDef iface,
			List<WitItem> path) {
		Scoped scoped = resolveAliases(type, resolver, iface);
		WitType t = scoped.type();
		WitItem.InterfaceDef in = scoped.iface();
		String wit = am.ik.wit.WitPrinter.type(type);
		switch (t) {
			case WitType.Named named -> {
				WitResolver.Owned owned = resolver.resolveOwned(in, named.name());
				if (owned == null || path.stream().anyMatch(seen -> seen == owned.item())) {
					return new WitTypeMapper.Shape(WitTypeMapper.Rep.UNSUPPORTED, null, wit);
				}
				path.add(owned.item());
				try {
					List<WitTypeMapper.Part> parts = new ArrayList<>();
					switch (owned.item()) {
						case WitItem.RecordDef record -> {
							for (WitItem.Field field : record.fields()) {
								parts.add(new WitTypeMapper.Part(field.name(),
										shapeOf(field.type(), resolver, owned.owner(), path)));
							}
						}
						case WitItem.VariantDef variant -> {
							for (WitItem.Case c : variant.cases()) {
								parts.add(new WitTypeMapper.Part(c.name(), c.payload() == null ? null
										: shapeOf(c.payload(), resolver, owned.owner(), path)));
							}
						}
						case WitItem.EnumDef enumDef -> {
							for (WitItem.Case c : enumDef.cases()) {
								parts.add(new WitTypeMapper.Part(c.name(), null));
							}
						}
						case WitItem.FlagsDef flags -> {
							for (WitItem.Case c : flags.cases()) {
								parts.add(new WitTypeMapper.Part(c.name(), null));
							}
						}
						default -> {
							// a resource: an opaque handle
						}
					}
					return new WitTypeMapper.Shape(WitTypeMapper.repOfDefinition(owned.item()), null, wit, null,
							List.copyOf(parts), definedName(owned.item()), false);
				}
				finally {
					path.removeLast();
				}
			}
			case WitType.BorrowOf borrow -> {
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.HANDLE, null, wit, null, List.of(),
						resourceName(borrow.resource(), resolver, in), true);
			}
			case WitType.OwnOf own -> {
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.HANDLE, null, wit, null, List.of(),
						resourceName(own.resource(), resolver, in), false);
			}
			case WitType.OptionOf option -> {
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.NIL_OR_VALUE,
						shapeOf(option.element(), resolver, in, path), wit);
			}
			case WitType.ResultOf result -> {
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.RESULT,
						result.ok() == null ? null : shapeOf(result.ok(), resolver, in, path), wit,
						result.err() == null ? null : shapeOf(result.err(), resolver, in, path), List.of());
			}
			case WitType.ListOf list -> {
				// a list of bytes is a byte string whatever alias names its element, as
				// the canonical ABI lifts it (WasmComponentImportCompiler's isU8)
				if (resolveAliases(list.element(), resolver, in).type() instanceof WitType.Prim prim
						&& "u8".equals(prim.name())) {
					return new WitTypeMapper.Shape(WitTypeMapper.Rep.BYTE_STRING, null, wit);
				}
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.LIST, shapeOf(list.element(), resolver, in, path),
						wit);
			}
			case WitType.TupleOf tuple -> {
				List<WitTypeMapper.Part> parts = new ArrayList<>();
				for (WitType element : tuple.elements()) {
					parts.add(new WitTypeMapper.Part(Integer.toString(parts.size()),
							shapeOf(element, resolver, in, path)));
				}
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.TUPLE_LIST, null, wit, null, List.copyOf(parts));
			}
			case WitType.StreamOf stream -> {
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.STREAM_HANDLE,
						stream.element() == null ? null : shapeOf(stream.element(), resolver, in, path), wit);
			}
			case WitType.FutureOf future -> {
				return new WitTypeMapper.Shape(WitTypeMapper.Rep.FUTURE_HANDLE,
						future.element() == null ? null : shapeOf(future.element(), resolver, in, path), wit);
			}
			default -> {
				return new WitTypeMapper.Shape(WitTypeMapper.rep(t), null, wit, null, List.of(),
						t instanceof WitType.Prim prim ? prim.name() : null, false);
			}
		}
	}

	// The name a type definition introduces: what a front end naming its types after the
	// WIT's finds its own by.
	private static @Nullable String definedName(WitItem item) {
		return switch (item) {
			case WitItem.RecordDef def -> def.name();
			case WitItem.VariantDef def -> def.name();
			case WitItem.EnumDef def -> def.name();
			case WitItem.FlagsDef def -> def.name();
			case WitItem.ResourceDef def -> def.name();
			default -> null;
		};
	}

	// The resource an own<R> or a borrow<R> names, aliases followed (R may be one).
	private static String resourceName(String name, WitResolver resolver, WitItem.InterfaceDef in) {
		Scoped scoped = resolveAliases(new WitType.Named(name), resolver, in);
		if (scoped.type() instanceof WitType.Named named) {
			WitResolver.Owned owned = resolver.resolveOwned(scoped.iface(), named.name());
			if (owned != null && owned.item() instanceof WitItem.ResourceDef resource) {
				return resource.name();
			}
		}
		return name;
	}

	/**
	 * A WIT type together with the interface scope its named references resolve in. The
	 * scope CHANGES as a walk descends: follow a {@code use} clause into another
	 * interface and you are looking at THAT interface's types, whose own internal
	 * references the starting scope never imported ({@code wasi:http/outgoing-handler}
	 * uses {@code error-code} from {@code wasi:http/types}, and {@code error-code}'s
	 * {@code DNS-error} case carries a {@code DNS-error-payload} it has never heard of).
	 *
	 * @param iface the interface the type is written in
	 * @param type the type
	 */
	record Scoped(WitItem.InterfaceDef iface, WitType type) {
	}

	/**
	 * Follows a {@code type} alias chain (across interfaces) to the type it bottoms out
	 * at, and reports the scope THAT type is written in.
	 * @param type the type as written
	 * @param resolver the resolver over the WIT document
	 * @param iface the scope the type is written in
	 * @return the type no alias names, and its scope
	 */
	static Scoped resolveAliases(WitType type, WitResolver resolver, WitItem.InterfaceDef iface) {
		if (type instanceof WitType.Named named) {
			WitResolver.Owned owned = resolver.resolveOwned(iface, named.name());
			if (owned != null && owned.item() instanceof WitItem.TypeAlias alias) {
				return resolveAliases(alias.target(), resolver, owned.owner());
			}
		}
		return new Scoped(iface, type);
	}

}
