package am.ik.wit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Writes an interface's {@code use} clauses out as the definitions they name: the scope a
 * consumer gets holds every value type it mentions itself, so nothing downstream has to
 * cross an interface to read one.
 *
 * <p>
 * A value type is structural in the component model -- two records of the same fields are
 * one type, whichever interface wrote them -- so a scope that defines a copy of a
 * {@code use}d record under the name the clause gives it says what the clause says. A
 * {@code resource} is nominal: a copy would be another resource. A name whose definition
 * is a resource, or holds a handle of one, therefore stays a {@code use} clause, as does
 * a name the document does not define.
 *
 * <p>
 * What a copied definition mentions is written by the name the scope has for it -- a
 * clause's name wins, wherever the type is reached from. A type the scope has no name
 * for: an alias is written as what it names; a record, a variant, an enum or flags, which
 * only a name can stand for, comes with the definition under the name its own interface
 * gives it ({@code use geometry.{line}} brings {@code geometry}'s {@code point} when a
 * {@code line} holds two), or is refused where the scope's names are not its own to add
 * to (a world's are imports).
 */
public final class WitUses {

	private WitUses() {
	}

	/**
	 * A type a clause's definition mentions that cannot come with it: its name is another
	 * type's in the scope ({@link #taken}), or the scope takes no name the clause does
	 * not write.
	 */
	public static final class Unnamed extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final transient WitItem.Use use;

		private final String name;

		private final boolean taken;

		Unnamed(WitItem.Use use, String name, boolean taken) {
			super("the type '" + name + "' has no name in the scope");
			this.use = use;
			this.name = name;
			this.taken = taken;
		}

		/**
		 * {@return the clause that reaches the type}
		 */
		public WitItem.Use use() {
			return this.use;
		}

		/**
		 * {@return the type's name in its own interface}
		 */
		public String name() {
			return this.name;
		}

		/**
		 * {@return whether the scope holds another type of that name}
		 */
		public boolean taken() {
			return this.taken;
		}

	}

	/**
	 * Returns the scope with its {@code use} clauses written out.
	 * @param resolver the resolver over the document the scope is read from
	 * @param scope the interface (or a world's types and clauses, as one)
	 * @param brings whether a record, a variant, an enum or flags that a definition
	 * mentions and no clause names comes into the scope under its own name: an
	 * interface's types are its exports, which it may add to; a world's are imports
	 * @return the scope, each clause replaced by the definitions it names; the same
	 * instance when it has no clause
	 * @throws Unnamed when a type a definition mentions gets no name in the scope
	 */
	public static WitItem.InterfaceDef inline(WitResolver resolver, WitItem.InterfaceDef scope, boolean brings) {
		boolean any = false;
		for (WitItem item : scope.items()) {
			any |= item instanceof WitItem.Use;
		}
		return any ? new Inliner(resolver, scope, brings).run() : scope;
	}

	private static final class Inliner {

		private final WitResolver resolver;

		private final WitItem.InterfaceDef scope;

		// A definition -> the name the scope knows it by.
		private final Map<WitItem, String> names = new IdentityHashMap<>();

		// A name -> the definition holding it.
		private final Map<String, WitItem> holders = new HashMap<>();

		private final Set<WitItem> written = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

		private final List<WitItem> out = new ArrayList<>();

		private final boolean brings;

		Inliner(WitResolver resolver, WitItem.InterfaceDef scope, boolean brings) {
			this.resolver = resolver;
			this.scope = scope;
			this.brings = brings;
		}

		WitItem.InterfaceDef run() {
			for (WitItem item : this.scope.items()) {
				String name = WitResolver.definedTypeName(item);
				if (name != null) {
					hold(item, name);
					this.written.add(item);
				}
			}
			// A clause's name first: a type reached again from another one's definition
			// is then that name.
			for (WitItem item : this.scope.items()) {
				if (item instanceof WitItem.Use use) {
					for (WitItem.UseName imported : use.names()) {
						WitResolver.Owned owned = carried(imported);
						String local = local(imported);
						if (owned != null && !this.names.containsKey(owned.item())
								&& !this.holders.containsKey(local)) {
							hold(owned.item(), local);
						}
					}
				}
			}
			for (WitItem item : this.scope.items()) {
				if (!(item instanceof WitItem.Use use)) {
					this.out.add(item);
					continue;
				}
				List<WitItem.UseName> kept = new ArrayList<>();
				for (WitItem.UseName imported : use.names()) {
					WitResolver.Owned owned = carried(imported);
					if (owned == null) {
						kept.add(imported);
						continue;
					}
					String local = local(imported);
					if (!this.names.containsKey(owned.item())) {
						// the clause's name is another type's here
						throw new Unnamed(use, local, true);
					}
					String held = bring(owned, use);
					if (!local.equals(held)) {
						// a second name of a type the scope holds
						if (this.holders.containsKey(local)) {
							throw new Unnamed(use, local, true);
						}
						WitItem alias = new WitItem.TypeAlias(WitMeta.none(), local, new WitType.Named(held));
						hold(alias, local);
						this.out.add(alias);
					}
				}
				if (!kept.isEmpty()) {
					this.out.add(new WitItem.Use(use.meta(), qualified(use.path()), List.copyOf(kept)));
				}
			}
			return new WitItem.InterfaceDef(this.scope.meta(), this.scope.name(), List.copyOf(this.out));
		}

		private void hold(WitItem item, String name) {
			this.names.put(item, name);
			this.holders.put(name, item);
		}

		private static String local(WitItem.UseName imported) {
			return imported.alias() == null ? imported.name() : imported.alias();
		}

		// What a clause's name resolves to, when it is a value type all the way down.
		private WitResolver.@Nullable Owned carried(WitItem.UseName imported) {
			WitResolver.Owned owned = this.resolver.resolveOwned(this.scope, local(imported));
			return owned != null && owned.owner() != this.scope && carried(owned, new HashSet<>()) ? owned : null;
		}

		private boolean carried(WitResolver.Owned owned, Set<WitItem> walking) {
			if (owned.item() instanceof WitItem.ResourceDef || !walking.add(owned.item())) {
				return false;
			}
			boolean carried = switch (owned.item()) {
				case WitItem.TypeAlias alias -> carried(alias.target(), owned.owner(), walking);
				case WitItem.RecordDef record ->
					record.fields().stream().allMatch(field -> carried(field.type(), owned.owner(), walking));
				case WitItem.VariantDef variant -> variant.cases()
					.stream()
					.allMatch(c -> c.payload() == null || carried(c.payload(), owned.owner(), walking));
				default -> true;
			};
			walking.remove(owned.item());
			return carried;
		}

		private boolean carried(@Nullable WitType type, WitItem.InterfaceDef owner, Set<WitItem> walking) {
			return switch (type) {
				case null -> true;
				case WitType.Prim ignored -> true;
				case WitType.Named named -> {
					WitResolver.Owned owned = this.resolver.resolveOwned(owner, named.name());
					yield owned != null && carried(owned, walking);
				}
				case WitType.ListOf list -> carried(list.element(), owner, walking);
				case WitType.OptionOf option -> carried(option.element(), owner, walking);
				case WitType.ResultOf res -> carried(res.ok(), owner, walking) && carried(res.err(), owner, walking);
				case WitType.TupleOf tuple ->
					tuple.elements().stream().allMatch(element -> carried(element, owner, walking));
				case WitType.StreamOf stream -> carried(stream.element(), owner, walking);
				case WitType.FutureOf future -> carried(future.element(), owner, walking);
				case WitType.BorrowOf ignored -> false;
				case WitType.OwnOf ignored -> false;
			};
		}

		// Writes a definition of another interface into the scope, after the ones it
		// mentions, and answers the name it has here: the one a clause gave it, or its
		// own.
		private String bring(WitResolver.Owned owned, WitItem.Use use) {
			WitItem item = owned.item();
			String name = this.names.get(item);
			if (name == null) {
				name = java.util.Objects.requireNonNull(WitResolver.definedTypeName(item));
				if (!this.brings || this.holders.containsKey(name)) {
					throw new Unnamed(use, name, this.brings);
				}
				hold(item, name);
			}
			if (!this.written.add(item)) {
				return name;
			}
			WitItem.InterfaceDef owner = owned.owner();
			WitItem copy = switch (item) {
				case WitItem.TypeAlias alias ->
					new WitItem.TypeAlias(alias.meta(), name, renamed(alias.target(), owner, use));
				case WitItem.RecordDef record -> {
					List<WitItem.Field> fields = new ArrayList<>();
					for (WitItem.Field field : record.fields()) {
						fields.add(new WitItem.Field(field.meta(), field.name(), renamed(field.type(), owner, use)));
					}
					yield new WitItem.RecordDef(record.meta(), name, List.copyOf(fields));
				}
				case WitItem.VariantDef variant -> {
					List<WitItem.Case> cases = new ArrayList<>();
					for (WitItem.Case c : variant.cases()) {
						cases.add(new WitItem.Case(c.meta(), c.name(),
								c.payload() == null ? null : renamed(c.payload(), owner, use)));
					}
					yield new WitItem.VariantDef(variant.meta(), name, List.copyOf(cases));
				}
				case WitItem.EnumDef en -> new WitItem.EnumDef(en.meta(), name, en.cases());
				case WitItem.FlagsDef flags -> new WitItem.FlagsDef(flags.meta(), name, flags.cases());
				default -> throw new IllegalStateException("not a value type definition: " + item);
			};
			this.out.add(copy);
			return name;
		}

		// A type as its owner wrote it, each name replaced by the one the scope has for
		// the definition (written first); an alias the scope has no name for, by what it
		// names.
		private WitType renamed(WitType type, WitItem.InterfaceDef owner, WitItem.Use use) {
			return switch (type) {
				case WitType.Named named -> {
					WitResolver.Owned owned = java.util.Objects
						.requireNonNull(this.resolver.resolveOwned(owner, named.name()));
					yield owned.item() instanceof WitItem.TypeAlias alias && !this.names.containsKey(alias)
							? renamed(alias.target(), owned.owner(), use) : new WitType.Named(bring(owned, use));
				}
				case WitType.ListOf list -> new WitType.ListOf(renamed(list.element(), owner, use));
				case WitType.OptionOf option -> new WitType.OptionOf(renamed(option.element(), owner, use));
				case WitType.ResultOf res ->
					new WitType.ResultOf(res.ok() == null ? null : renamed(res.ok(), owner, use),
							res.err() == null ? null : renamed(res.err(), owner, use));
				case WitType.TupleOf tuple -> new WitType.TupleOf(
						tuple.elements().stream().map(element -> renamed(element, owner, use)).toList());
				case WitType.StreamOf stream ->
					new WitType.StreamOf(stream.element() == null ? null : renamed(stream.element(), owner, use));
				case WitType.FutureOf future ->
					new WitType.FutureOf(future.element() == null ? null : renamed(future.element(), owner, use));
				default -> type;
			};
		}

		// The clause's interface by its full id: the scope answered is in no package, so
		// a sibling's bare name would no longer find it.
		private WitRef qualified(WitRef path) {
			WitItem.InterfaceDef source = this.resolver.findUsedInterface(this.scope, path);
			WitRef ref = source == null ? null : this.resolver.refOf(source);
			return ref == null ? path : ref;
		}

	}

}
