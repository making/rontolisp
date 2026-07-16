package am.ik.wit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Name resolution over a parsed {@link WitDocument}: finds an interface by the reference
 * a user writes ({@code wasi:keyvalue/store@0.2.0}), and resolves a {@link WitType.Named}
 * reference to the item that defines it, following an interface's own type definitions
 * and its {@code use} clauses.
 *
 * <p>
 * The parser deliberately produces a lossless, purely syntactic model: a {@code use}
 * clause is a {@link WitItem.Use} record, and a type reference is a
 * {@link WitType.Named}(name) with no link to its definition. That is right for a
 * round-tripping printer, but a binder needs the link -- a consumer classifying a type
 * cannot say what a {@code Named} reference is until someone resolves it to the item that
 * defines it. This class is that someone.
 *
 * <p>
 * Like the rest of {@code am.ik.wit} it is language-independent: no rontolisp imports, no
 * external dependencies.
 */
public final class WitResolver {

	private final WitDocument document;

	// Fully-qualified interface id ("wasi:keyvalue/store@0.2.0") -> definition, in
	// document order.
	private final Map<String, WitItem.InterfaceDef> interfaces = new LinkedHashMap<>();

	// Bare interface name ("store") -> definition; a name defined twice maps to null (an
	// ambiguous bare reference must be spelled out).
	private final Map<String, WitItem.@Nullable InterfaceDef> byBareName = new LinkedHashMap<>();

	/**
	 * Creates a resolver over a parsed document.
	 * @param document the parsed WIT document
	 */
	public WitResolver(WitDocument document) {
		this.document = document;
		collect(document.items(), null);
	}

	private void collect(List<WitItem> items, @Nullable WitPackageName enclosing) {
		WitPackageName current = enclosing;
		// A WIT file declares at most one top-level `package` header, and it names the
		// package of every interface in the file -- wherever in the file it is written.
		// So it is picked up in full BEFORE the interfaces are indexed, rather than
		// tracked positionally. (A file may instead open its packages as
		// `package foo:bar { ... }` blocks, which the second loop recurses into.)
		for (WitItem item : items) {
			if (item instanceof WitItem.PackageHeader header) {
				current = header.name();
			}
		}
		for (WitItem item : items) {
			switch (item) {
				case WitItem.PackageBlock block -> collect(block.items(), block.name());
				case WitItem.InterfaceDef iface -> index(iface, current);
				default -> {
				}
			}
		}
	}

	private void index(WitItem.InterfaceDef iface, @Nullable WitPackageName pkg) {
		this.interfaces.put(qualify(pkg, iface.name()), iface);
		if (this.byBareName.containsKey(iface.name())) {
			this.byBareName.put(iface.name(), null);
		}
		else {
			this.byBareName.put(iface.name(), iface);
		}
	}

	// "wasi:keyvalue/store@0.2.0" -- the version trails the interface name, which is how
	// a WIT reference is written (the package name carries it in the model).
	private static String qualify(@Nullable WitPackageName pkg, String name) {
		if (pkg == null) {
			return name;
		}
		String base = pkg.namespace() + ":" + pkg.name() + "/" + name;
		return pkg.version() == null ? base : base + "@" + pkg.version();
	}

	/**
	 * Returns every interface this document defines, by fully-qualified id, in document
	 * order.
	 * @return the interface ids (e.g. {@code wasi:keyvalue/store@0.2.0})
	 */
	public List<String> interfaceIds() {
		return List.copyOf(this.interfaces.keySet());
	}

	/**
	 * Finds an interface by the reference a user writes. Accepted spellings, in order:
	 * the fully-qualified id ({@code wasi:keyvalue/store@0.2.0}), the id without its
	 * version ({@code wasi:keyvalue/store} -- unambiguous unless the document defines the
	 * same interface at two versions), and the bare interface name ({@code store}).
	 * @param reference the interface reference
	 * @return the interface, or {@code null} when the document defines no such interface
	 */
	public WitItem.@Nullable InterfaceDef findInterface(String reference) {
		WitItem.InterfaceDef exact = this.interfaces.get(reference);
		if (exact != null) {
			return exact;
		}
		WitItem.InterfaceDef unversioned = null;
		for (Map.Entry<String, WitItem.InterfaceDef> entry : this.interfaces.entrySet()) {
			String id = entry.getKey();
			int at = id.lastIndexOf('@');
			if (at > 0 && id.substring(0, at).equals(reference)) {
				if (unversioned != null) {
					return null;
				}
				unversioned = entry.getValue();
			}
		}
		if (unversioned != null) {
			return unversioned;
		}
		return reference.indexOf('/') < 0 ? this.byBareName.get(reference) : null;
	}

	/**
	 * Returns the fully-qualified id of an interface this document defines -- the one
	 * spelling that names it uniquely, whichever of the accepted spellings the user wrote
	 * to {@link #findInterface(String)}.
	 * <p>
	 * A binder keying a registry (or a host module) by interface must key it by THIS id:
	 * keying it by the reference as written would make {@code wasi:keyvalue/store@0.2.0},
	 * {@code wasi:keyvalue/store} and {@code store} -- three spellings of one interface
	 * -- bind three different keys.
	 * @param iface an interface obtained from this resolver
	 * @return the fully-qualified id (e.g. {@code wasi:keyvalue/store@0.2.0}), or
	 * {@code null} when the interface does not come from this document
	 */
	public @Nullable String canonicalId(WitItem.InterfaceDef iface) {
		for (Map.Entry<String, WitItem.InterfaceDef> entry : this.interfaces.entrySet()) {
			if (entry.getValue() == iface) {
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Resolves a named type reference against an interface: its own type definitions
	 * first, then the types its {@code use} clauses import (following aliases, and
	 * transitively through the interfaces those clauses name).
	 * @param scope the interface the reference appears in
	 * @param name the type name as written (the bare name -- {@code %}-escaping is source
	 * syntax the parser has already stripped)
	 * @return the defining item ({@code record} / {@code variant} / {@code enum} /
	 * {@code flags} / {@code resource} / {@code type} alias), or {@code null} when the
	 * document defines no such type in this scope
	 */
	public @Nullable WitItem resolveType(WitItem.InterfaceDef scope, String name) {
		Owned owned = resolveOwned(scope, name);
		return owned == null ? null : owned.item();
	}

	/**
	 * Resolves a named type reference the way {@link #resolveType} does, but reports the
	 * interface that DEFINES it as well as the definition.
	 * <p>
	 * The distinction is invisible to a consumer that only wants to classify a type, and
	 * decisive for one that has to ENCODE it: in the component model a {@code resource}
	 * is NOMINAL, so a type an interface merely {@code use}s is the defining interface's
	 * type and must be referred to as such (an {@code alias outer} to the type aliased
	 * out of the defining instance), not re-declared structurally. Re-declaring it mints
	 * a second, unrelated resource -- a component the host cannot satisfy, and handle
	 * tables that do not line up.
	 * @param scope the interface the reference appears in
	 * @param name the type name as written in {@code scope} (a {@code use} clause's local
	 * alias, when it has one)
	 * @return the definition and its defining interface, or {@code null} when the
	 * document defines no such type in this scope
	 */
	public @Nullable Owned resolveOwned(WitItem.InterfaceDef scope, String name) {
		return resolveOwned(scope, name, new HashSet<>());
	}

	private @Nullable Owned resolveOwned(WitItem.InterfaceDef scope, String name, Set<WitItem.InterfaceDef> visited) {
		if (!visited.add(scope)) {
			return null;
		}
		for (WitItem item : scope.items()) {
			if (name.equals(definedTypeName(item))) {
				return new Owned(item, scope);
			}
		}
		for (WitItem item : scope.items()) {
			if (!(item instanceof WitItem.Use use)) {
				continue;
			}
			for (WitItem.UseName imported : use.names()) {
				String local = imported.alias() == null ? imported.name() : imported.alias();
				if (!name.equals(local)) {
					continue;
				}
				WitItem.InterfaceDef source = findUsedInterface(scope, use.path());
				if (source == null) {
					return null;
				}
				return resolveOwned(source, imported.name(), visited);
			}
		}
		return null;
	}

	// Resolves a `use` clause's interface path from within `scope`. An UNQUALIFIED path
	// (`use types.{...}`) names a sibling of the SAME package (the WIT rule), so it is
	// tried against the scope's own package first -- two packages may both define an
	// interface named `types` (wasi:clocks and wasi:http both do), and the bare-name
	// fallback alone would call that ambiguous.
	private WitItem.@Nullable InterfaceDef findUsedInterface(WitItem.InterfaceDef scope, WitRef path) {
		if (path.pkg() == null) {
			String scopeId = canonicalId(scope);
			if (scopeId != null) {
				int slash = scopeId.lastIndexOf('/');
				if (slash > 0) {
					int at = scopeId.lastIndexOf('@');
					String suffix = at > slash ? scopeId.substring(at) : "";
					WitItem.InterfaceDef sibling = this.interfaces
						.get(scopeId.substring(0, slash + 1) + path.name() + suffix);
					if (sibling != null) {
						return sibling;
					}
				}
			}
		}
		return findInterface(path.toString());
	}

	/**
	 * A resolved type reference: the item that defines it, and the interface that item
	 * was found in.
	 * <p>
	 * {@code owner} is the interface whose scope the definition literally sits in, after
	 * every {@code use} clause and {@code type} alias has been followed -- so for
	 * {@code wasi:http/types}'s {@code input-stream} it is {@code wasi:io/streams}, which
	 * is exactly the interface a component-model consumer must alias the type out of.
	 *
	 * @param item the defining item ({@code record} / {@code variant} / {@code enum} /
	 * {@code flags} / {@code resource} / {@code type} alias)
	 * @param owner the interface that defines it
	 */
	public record Owned(WitItem item, WitItem.InterfaceDef owner) {
	}

	// The name a type-defining item introduces; null for anything that is not a type
	// definition (a func, a use clause, ...).
	private static @Nullable String definedTypeName(WitItem item) {
		return switch (item) {
			case WitItem.TypeAlias alias -> alias.name();
			case WitItem.RecordDef def -> def.name();
			case WitItem.VariantDef def -> def.name();
			case WitItem.EnumDef def -> def.name();
			case WitItem.FlagsDef def -> def.name();
			case WitItem.ResourceDef def -> def.name();
			default -> null;
		};
	}

	/**
	 * Finds the interface a {@code use} clause (or a world {@code import}/{@code export})
	 * names. A reference with no package part is looked up by bare name in this document.
	 * @param reference the interface reference
	 * @return the interface, or {@code null} when the document defines no such interface
	 * (an interface from a dependency the document does not carry)
	 */
	public WitItem.@Nullable InterfaceDef findInterface(WitRef reference) {
		return findInterface(qualify(reference.pkg(), reference.name()));
	}

	/**
	 * Returns the functions an interface declares, in document order: its freestanding
	 * functions, and the constructor / methods / static functions of every
	 * {@code resource} it defines.
	 * @param iface the interface
	 * @return the functions, each tagged with the resource that owns it (or {@code null}
	 * for a freestanding one)
	 */
	public static List<Func> functions(WitItem.InterfaceDef iface) {
		List<Func> funcs = new ArrayList<>();
		for (WitItem item : iface.items()) {
			switch (item) {
				case WitItem.FuncDef func -> funcs.add(new Func(null, func));
				case WitItem.ResourceDef resource -> {
					List<WitItem> body = resource.body();
					if (body != null) {
						for (WitItem member : body) {
							if (member instanceof WitItem.FuncDef func) {
								funcs.add(new Func(resource.name(), func));
							}
						}
					}
				}
				default -> {
				}
			}
		}
		return funcs;
	}

	/**
	 * One function of an interface, and the resource that owns it.
	 *
	 * @param resource the owning {@code resource}'s name, or {@code null} for a
	 * freestanding interface function
	 * @param def the function definition
	 */
	public record Func(@Nullable String resource, WitItem.FuncDef def) {
	}

	/**
	 * Returns the document this resolver was built over.
	 * @return the document
	 */
	public WitDocument document() {
		return this.document;
	}

}
