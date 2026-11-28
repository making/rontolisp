package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * The settled WIT type &lt;-&gt; rontolisp value mapping — the single vocabulary every
 * backend binder ({@code wit-import} / {@code wit-export}) consults, in the same way
 * {@link WasmImportDirective} is the shared import directive. This class contains
 * <strong>no codegen</strong>; it names the house representation of each WIT type. Full
 * rationale (and the {@code result} decision record): {@code .kb/wit.md}.
 *
 * <p>
 * The two decisions pinned here (reversing either later would break user programs):
 *
 * <ul>
 * <li><strong>{@code result<T, E>}</strong> — the ok arm is the function's return value
 * ({@code nil} when the ok arm is {@code _} or absent); the error arm <strong>signals a
 * condition on every backend</strong>, catchable with {@code handler-case} — including
 * the wasm-GC ones, where a program containing a catching form compiles in EH mode
 * ({@code .kb/error-handling.md}).</li>
 * <li><strong>{@code list<u8>}</strong> — a rontolisp string (byte-per-char, the existing
 * fetch/socket byte-marshalling convention), NOT a list of integers; distinct from WIT
 * {@code string}, which crosses as canonical-ABI UTF-8.</li>
 * <li><strong>A {@code result} as an ARGUMENT</strong> — the envelope cons
 * {@code (:ok . V)} / {@code (:error . E)}. The ok arm is unwrapped on the way OUT of a
 * call, so on the way IN a value still carries its arm; this is exactly the shape a
 * {@code result} lifts to before {@code rontolisp::%wit-result} unwraps it, so a value
 * one call returns can be passed straight into the next. A payload-less arm may also be
 * written as the bare keyword ({@code :ok}) — the general variant rule.</li>
 * </ul>
 */
public final class WitTypeMapper {

	private WitTypeMapper() {
	}

	/**
	 * The rontolisp-side representation of a WIT type.
	 */
	public enum Rep {

		/** {@code s8}/{@code s16}/{@code s32}/{@code u8}/{@code u16}/{@code u32}: int. */
		INT,

		/**
		 * {@code s64}/{@code u64}: int, bignum-safe (beyond {@code i31} the interpreter
		 * and JVM stay exact; the wasm-GC backend follows its existing wide-int
		 * behavior).
		 */
		BIGNUM_INT,

		/** {@code f32}/{@code f64}: float. */
		FLOAT,

		/** {@code bool}: {@code t} / {@code nil}. */
		BOOLEAN,

		/** {@code string}: string (canonical-ABI UTF-8 at a component boundary). */
		STRING,

		/** {@code char}: character. */
		CHARACTER,

		/**
		 * {@code list<u8>}: a string carrying raw bytes one-per-char (the fetch/socket
		 * marshalling convention), NOT a list of ints.
		 */
		BYTE_STRING,

		/** {@code list<T>} (T not {@code u8}): proper list. */
		LIST,

		/** {@code tuple<A, B, ...>}: proper list, positional. */
		TUPLE_LIST,

		/** {@code option<T>}: the value, or {@code nil} when absent. */
		NIL_OR_VALUE,

		/**
		 * {@code result} / {@code result<T>} / {@code result<_, E>} /
		 * {@code result<T, E>}: the ok payload is the return value ({@code nil} for a
		 * payload-less ok arm); the error arm signals a condition carrying the mapped
		 * {@code E} payload on EVERY backend. As an ARGUMENT it is the envelope cons —
		 * see the class doc.
		 */
		RESULT,

		/**
		 * {@code borrow<R>} / {@code own<R>} (and a bare {@code resource} reference): an
		 * opaque integer handle, in the same one-handle-space convention as file/socket
		 * streams ({@code .kb/read-load-streams.md}).
		 */
		HANDLE,

		/**
		 * {@code stream<T>}: an opaque i32 handle (the readable or writable end), in the
		 * same one-handle-space convention as {@link #HANDLE}, but read/written through
		 * the async canonical built-ins ({@code stream.read} / {@code stream.write}).
		 * Only the {@code --component} backend can marshal it; the interpreter, the JVM
		 * and Preview 1 WASM reject it, since it needs the component-model async ABI. A
		 * {@code stream<u8>} reads out as a byte string, a stream of resource handles a
		 * handle a read, a stream of any other element a list of at most as many values
		 * as the read asks for.
		 */
		STREAM_HANDLE,

		/**
		 * {@code future<T>}: an opaque i32 handle (the readable end), like
		 * {@link #HANDLE}, but resolved through the async canonical built-in
		 * {@code future.read}. Only {@code --component} can marshal it. A
		 * {@code future<result<...>>} reads out as its mapped {@code result}
		 * ({@link #RESULT}).
		 */
		FUTURE_HANDLE,

		/**
		 * A {@code record} definition: keyword plist ({@code rontolisp:http-handler}
		 * precedent).
		 */
		PLIST,

		/** An {@code enum} definition: keyword. */
		KEYWORD,

		/**
		 * A {@code variant} definition: the case keyword ({@code :get}), or
		 * {@code (keyword . payload)} when the case carries a payload
		 * ({@code (:other . "PATCH")}).
		 */
		TAGGED_LIST,

		/** A {@code flags} definition: list of keywords. */
		KEYWORD_LIST,

		/**
		 * A defensive default with no producer today — every WIT type a binder can see
		 * now maps to one of the reps above ({@code stream}/{@code future} became
		 * {@link #STREAM_HANDLE}/{@link #FUTURE_HANDLE}). Kept as a safety net so a
		 * future WIT type addition surfaces as a clear rejection rather than a silent
		 * misclassification.
		 */
		UNSUPPORTED

	}

	/**
	 * A WIT type use resolved to its house representation, for a front end that binds a
	 * WIT surface before the lowering runs ({@link WitImportDirective#describe},
	 * {@link WitExportDirective#describe}) and converts its own values to and from the
	 * representation: the representation plus every type nested in it, aliases followed.
	 *
	 * @param rep the house representation
	 * @param element an {@code option}'s element, a {@code result}'s ok arm, a
	 * {@code list}'s or a {@code stream}'s element or a {@code future}'s payload
	 * ({@code null} for a payload-less arm, a bare stream or future, and every other
	 * representation)
	 * @param wit the type as the WIT spells it where it is used, for a message
	 * @param error a {@code result}'s error arm, {@code null} for a payload-less arm and
	 * for every other representation
	 * @param parts a {@code record}'s fields, a {@code variant}'s cases (a payload-less
	 * case with no shape), an {@code enum}'s or a {@code flags}' labels (no shapes) and a
	 * {@code tuple}'s elements (labelled by position), in WIT order; empty for every
	 * other representation
	 * @param definition the name a type resolves to, aliases followed: a named type's
	 * definition ({@code fields} for a {@code headers} aliasing it, {@code bucket} for an
	 * {@code own<bucket>}), a primitive's name ({@code u64} for a {@code duration}
	 * aliasing it); {@code null} for every structural type and where nothing set it: what
	 * a front end that names its types after the WIT's finds its own type by
	 * @param borrow whether the type is a {@code borrow<R>} handle, aliases followed: one
	 * the callee does not take from its caller
	 * @param home the canonical id of the interface defining a named type or the resource
	 * a handle is of (the plain name of a world's inline interface), what tells two
	 * definitions of one name apart; {@code null} for every structural type
	 */
	public record Shape(Rep rep, @Nullable Shape element, String wit, @Nullable Shape error, List<Part> parts,
			@Nullable String definition, boolean borrow, @Nullable String home) {

		/**
		 * A shape of no home.
		 * @param rep the house representation
		 * @param element the element, or {@code null}
		 * @param wit the type as the WIT spells it
		 * @param error a result's error arm, or {@code null}
		 * @param parts the labelled members
		 * @param definition the name the type resolves to, or {@code null}
		 * @param borrow whether it is a {@code borrow<R>} handle
		 */
		public Shape(Rep rep, @Nullable Shape element, String wit, @Nullable Shape error, List<Part> parts,
				@Nullable String definition, boolean borrow) {
			this(rep, element, wit, error, parts, definition, borrow, null);
		}

		/**
		 * A shape of no definition.
		 * @param rep the house representation
		 * @param element the element, or {@code null}
		 * @param wit the type as the WIT spells it
		 * @param error a result's error arm, or {@code null}
		 * @param parts the labelled members
		 */
		public Shape(Rep rep, @Nullable Shape element, String wit, @Nullable Shape error, List<Part> parts) {
			this(rep, element, wit, error, parts, null, false);
		}

		/**
		 * A shape nesting nothing but an element.
		 * @param rep the house representation
		 * @param element the element, or {@code null}
		 * @param wit the type as the WIT spells it
		 */
		public Shape(Rep rep, @Nullable Shape element, String wit) {
			this(rep, element, wit, null, List.of());
		}

	}

	/**
	 * A labelled member of a {@link Shape}: a record's field, a variant's case, an enum's
	 * or a flags' label, a tuple's element.
	 *
	 * @param label the WIT label as written ({@code DNS-error}), or the element's
	 * position
	 * @param shape its type, or {@code null} for a payload-less case and a label
	 */
	public record Part(String label, @Nullable Shape shape) {
	}

	/**
	 * A named parameter of a described WIT function.
	 *
	 * @param name the parameter's WIT name ({@code self} for a resource method's handle)
	 * @param shape its type
	 */
	public record Param(String name, Shape shape) {
	}

	/**
	 * Maps a structural WIT type use to its rontolisp representation. A
	 * {@link WitType.Named} reference cannot be classified structurally — resolve it to
	 * its definition and use {@link #repOfDefinition(WitItem)}.
	 * @param type the WIT type use
	 * @return the representation
	 * @throws IllegalArgumentException for a {@link WitType.Named} reference (the caller
	 * must resolve it first)
	 */
	public static Rep rep(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> switch (prim.name()) {
				case "s8", "s16", "s32", "u8", "u16", "u32" -> Rep.INT;
				case "s64", "u64" -> Rep.BIGNUM_INT;
				case "f32", "f64" -> Rep.FLOAT;
				case "bool" -> Rep.BOOLEAN;
				case "string" -> Rep.STRING;
				case "char" -> Rep.CHARACTER;
				default -> throw new IllegalArgumentException("Unknown WIT primitive: " + prim.name());
			};
			case WitType.ListOf list ->
				(list.element() instanceof WitType.Prim prim && "u8".equals(prim.name())) ? Rep.BYTE_STRING : Rep.LIST;
			case WitType.TupleOf ignored -> Rep.TUPLE_LIST;
			case WitType.OptionOf ignored -> Rep.NIL_OR_VALUE;
			case WitType.ResultOf ignored -> Rep.RESULT;
			case WitType.BorrowOf ignored -> Rep.HANDLE;
			case WitType.OwnOf ignored -> Rep.HANDLE;
			case WitType.StreamOf ignored -> Rep.STREAM_HANDLE;
			case WitType.FutureOf ignored -> Rep.FUTURE_HANDLE;
			case WitType.Named named -> throw new IllegalArgumentException(
					"Named type '" + named.name() + "' must be resolved to its definition first");
		};
	}

	/**
	 * Maps a named WIT type definition to the representation of its instances.
	 * @param definition the type definition ({@code record} / {@code variant} /
	 * {@code enum} / {@code flags} / {@code resource} / {@code type} alias)
	 * @return the representation ({@code type} aliases classify by their target)
	 * @throws IllegalArgumentException when the item is not a type definition, or when it
	 * is an alias whose target is itself a {@link WitType.Named} reference
	 * ({@code type headers = fields}) — only a resolver can follow that, so the caller
	 * must resolve aliases before asking
	 */
	public static Rep repOfDefinition(WitItem definition) {
		return switch (definition) {
			case WitItem.RecordDef ignored -> Rep.PLIST;
			case WitItem.EnumDef ignored -> Rep.KEYWORD;
			case WitItem.VariantDef ignored -> Rep.TAGGED_LIST;
			case WitItem.FlagsDef ignored -> Rep.KEYWORD_LIST;
			case WitItem.ResourceDef ignored -> Rep.HANDLE;
			case WitItem.TypeAlias alias -> rep(alias.target());
			default -> throw new IllegalArgumentException("Not a WIT type definition: " + definition);
		};
	}

	/**
	 * The whole shape of a WIT type use: aliases followed (across interfaces), a named
	 * type classified by its definition, every nested type described in the scope THAT
	 * type is written in -- what a front end converts its own values by, an import
	 * member's ({@link WitImportDirective#describe}) and an export's
	 * ({@link WitExportDirective#describe}) alike. A nested type naming no definition, or
	 * a definition containing itself, is {@link Rep#UNSUPPORTED}: an import's member is
	 * the front end's to refuse where a program calls it, an export's is refused first.
	 * @param type the type use
	 * @param resolver the resolver over the document
	 * @param scope the scope the type is written in
	 * @return the shape
	 */
	public static Shape shape(WitType type, WitResolver resolver, WitItem.InterfaceDef scope) {
		return shape(type, resolver, scope, resolver::canonicalId);
	}

	/**
	 * Like {@link #shape(WitType, WitResolver, WitItem.InterfaceDef)}, a named type's
	 * home named by the caller: a scope of its own making (an exported interface with its
	 * {@code use} clauses written out) is in no package of the document.
	 * @param type the type use
	 * @param resolver the resolver over the document
	 * @param scope the scope the type is written in
	 * @param homes the id of the interface defining a type, by that interface
	 * @return the shape
	 */
	public static Shape shape(WitType type, WitResolver resolver, WitItem.InterfaceDef scope,
			java.util.function.Function<WitItem.InterfaceDef, @Nullable String> homes) {
		return shapeOf(type, resolver, scope, new ArrayList<>(), homes);
	}

	// The whole shape, every nested type described in the scope THAT type is written in
	// (an option's element, a result's arms, a list's element, a record's fields, a
	// variant's payloads, a tuple's elements). `path` holds the definitions being
	// described.
	private static Shape shapeOf(WitType type, WitResolver resolver, WitItem.InterfaceDef iface, List<WitItem> path,
			java.util.function.Function<WitItem.InterfaceDef, @Nullable String> homes) {
		Scoped scoped = followAliases(type, resolver, iface);
		WitType t = scoped.type();
		WitItem.InterfaceDef in = scoped.iface();
		String wit = am.ik.wit.WitPrinter.type(type);
		switch (t) {
			case WitType.Named named -> {
				WitResolver.Owned owned = resolver.resolveOwned(in, named.name());
				if (owned == null || path.stream().anyMatch(seen -> seen == owned.item())) {
					return new Shape(Rep.UNSUPPORTED, null, wit);
				}
				path.add(owned.item());
				try {
					List<Part> parts = new ArrayList<>();
					switch (owned.item()) {
						case WitItem.RecordDef record -> {
							for (WitItem.Field field : record.fields()) {
								parts.add(new Part(field.name(),
										shapeOf(field.type(), resolver, owned.owner(), path, homes)));
							}
						}
						case WitItem.VariantDef variant -> {
							for (WitItem.Case c : variant.cases()) {
								parts.add(new Part(c.name(), c.payload() == null ? null
										: shapeOf(c.payload(), resolver, owned.owner(), path, homes)));
							}
						}
						case WitItem.EnumDef enumDef -> {
							for (WitItem.Case c : enumDef.cases()) {
								parts.add(new Part(c.name(), null));
							}
						}
						case WitItem.FlagsDef flags -> {
							for (WitItem.Case c : flags.cases()) {
								parts.add(new Part(c.name(), null));
							}
						}
						default -> {
							// a resource: an opaque handle
						}
					}
					return new Shape(repOfDefinition(owned.item()), null, wit, null, List.copyOf(parts),
							definedName(owned.item()), false, homes.apply(owned.owner()));
				}
				finally {
					path.removeLast();
				}
			}
			case WitType.BorrowOf borrow -> {
				return handle(borrow.resource(), wit, true, resolver, in, homes);
			}
			case WitType.OwnOf own -> {
				return handle(own.resource(), wit, false, resolver, in, homes);
			}
			case WitType.OptionOf option -> {
				return new Shape(Rep.NIL_OR_VALUE, shapeOf(option.element(), resolver, in, path, homes), wit);
			}
			case WitType.ResultOf result -> {
				return new Shape(Rep.RESULT,
						result.ok() == null ? null : shapeOf(result.ok(), resolver, in, path, homes), wit,
						result.err() == null ? null : shapeOf(result.err(), resolver, in, path, homes), List.of());
			}
			case WitType.ListOf list -> {
				// a list of bytes is a byte string whatever alias names its element, as
				// the canonical ABI lifts it (WasmComponentImportCompiler's isU8)
				if (followAliases(list.element(), resolver, in).type() instanceof WitType.Prim prim
						&& "u8".equals(prim.name())) {
					return new Shape(Rep.BYTE_STRING, null, wit);
				}
				return new Shape(Rep.LIST, shapeOf(list.element(), resolver, in, path, homes), wit);
			}
			case WitType.TupleOf tuple -> {
				List<Part> parts = new ArrayList<>();
				for (WitType element : tuple.elements()) {
					parts.add(new Part(Integer.toString(parts.size()), shapeOf(element, resolver, in, path, homes)));
				}
				return new Shape(Rep.TUPLE_LIST, null, wit, null, List.copyOf(parts));
			}
			case WitType.StreamOf stream -> {
				return new Shape(Rep.STREAM_HANDLE,
						stream.element() == null ? null : shapeOf(stream.element(), resolver, in, path, homes), wit);
			}
			case WitType.FutureOf future -> {
				return new Shape(Rep.FUTURE_HANDLE,
						future.element() == null ? null : shapeOf(future.element(), resolver, in, path, homes), wit);
			}
			default -> {
				return new Shape(rep(t), null, wit, null, List.of(),
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

	// The handle an own<R> or a borrow<R> is: the resource R names, aliases followed (R
	// may be one), and the interface defining it.
	private static Shape handle(String name, String wit, boolean borrow, WitResolver resolver, WitItem.InterfaceDef in,
			java.util.function.Function<WitItem.InterfaceDef, @Nullable String> homes) {
		Scoped scoped = followAliases(new WitType.Named(name), resolver, in);
		if (scoped.type() instanceof WitType.Named named) {
			WitResolver.Owned owned = resolver.resolveOwned(scoped.iface(), named.name());
			if (owned != null && owned.item() instanceof WitItem.ResourceDef resource) {
				return new Shape(Rep.HANDLE, null, wit, null, List.of(), resource.name(), borrow,
						homes.apply(owned.owner()));
			}
		}
		return new Shape(Rep.HANDLE, null, wit, null, List.of(), name, borrow);
	}

	// A type and the scope it is written in.
	private record Scoped(WitItem.InterfaceDef iface, WitType type) {
	}

	// Follows a `type` alias chain (across interfaces) to the type it bottoms out at.
	private static Scoped followAliases(WitType type, WitResolver resolver, WitItem.InterfaceDef iface) {
		if (type instanceof WitType.Named named) {
			WitResolver.Owned owned = resolver.resolveOwned(iface, named.name());
			if (owned != null && owned.item() instanceof WitItem.TypeAlias alias) {
				return followAliases(alias.target(), resolver, owned.owner());
			}
		}
		return new Scoped(iface, type);
	}

}
