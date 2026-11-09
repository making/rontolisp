package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;

/**
 * The type special forms {@code deftype*} and {@code reify*}
 * ({@code .kb/clojure-frontend.md}, "Macros", the type special forms): what the oracle's
 * {@code deftype}, {@code defrecord} and {@code reify} expand to, which a program's macro
 * may build itself -- datascript's {@code defrecord-updatable} rewrites a record's
 * expansion, extend-clj's {@code deftype-atom} spells {@code deftype*} directly. Each
 * lowers through the machinery of the macro it underlies, read off the datum that macro
 * would have been: the names of its interface vector -- a protocol's interface standing
 * for the protocol -- are the body's groups and its methods the body's methods, matched
 * by name and count across all of them like the oracle's class.
 *
 * <p>
 * A {@code deftype*} type is the literal class: its every field (a record expansion's
 * {@code __meta}, {@code __extmap} and mutable hash caches included), every method the
 * form spells -- a record expansion's own map methods among them, so a type implementing
 * {@code IRecord} is the oracle's record method for method, overrides included -- and no
 * {@code ->Name}, which the form never defines. Its constructions call a constructor of
 * its own over every field; the oracle's shorter ones (all but the trailing record
 * fields, and all but the two hash caches) fill {@code nil}, {@code nil}, {@code 0} and
 * {@code 0}, and with such fields the class's static {@code create} builds it from a map.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureTypeSpecials {

	/**
	 * The trailing fields of a record's expansion the oracle's shorter constructors fill
	 * ({@code Compiler}'s {@code altCtorDrops}), by name to their fill.
	 */
	private static final Map<String, LispVal> RECORD_FIELDS = Map.of("__meta", ClojureLowering.NIL_CONST, "__extmap",
			ClojureLowering.NIL_CONST, "__hash", new LispInteger(0), "__hasheq", new LispInteger(0));

	/** The hash caches, which the second shorter constructor fills alone. */
	private static final Set<String> HASH_FIELDS = Set.of("__hash", "__hasheq");

	/**
	 * The shipped internal namespace of what a record's expansion calls that no class
	 * here has ({@code lib/rontolisp/internal/records.clj}): the {@code APersistentMap}
	 * statics, {@code RecordIterator}'s construction and the private
	 * {@code clojure.core/imap-cons}.
	 */
	static final String RECORDS = "rontolisp.internal.records";

	private ClojureTypeSpecials() {
	}

	/**
	 * A call of a function of {@link #RECORDS}, the namespace loaded where such a call
	 * first lowers.
	 * @param ctx the hub
	 * @param fn the function's name
	 * @param args the lowered arguments
	 * @return the call
	 */
	static LispVal recordsCall(ClojureLowering ctx, String fn, List<LispVal> args) {
		loadRecords(ctx);
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowering.varSym(RECORDS + "/" + fn));
		call.addAll(args);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * A function of {@link #RECORDS} as a value: the root of the private
	 * {@code #'clojure.core/imap-cons} a record's {@code cons} calls.
	 * @param ctx the hub
	 * @param fn the function's name
	 * @return the value form
	 */
	static LispVal recordsFunction(ClojureLowering ctx, String fn) {
		loadRecords(ctx);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowering.varSym(RECORDS + "/" + fn));
	}

	private static void loadRecords(ClojureLowering ctx) {
		if (!ctx.loadedNamespaces.contains(RECORDS) && !ctx.loadingNamespaces.contains(RECORDS)) {
			ClojureNamespaceLowering.preload(ctx, RECORDS);
		}
	}

	/**
	 * {@code (reify* [interface...] method...)}: the reify of the same groups and methods
	 * ({@link ClojureProtocolLowering#reifyForm}).
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered value
	 */
	static LispVal reifyStarOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(
				items.size() >= 2 && ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(items.get(1))),
				"reify* takes a vector of interfaces and methods");
		List<LispVal> equivalent = new ArrayList<>();
		equivalent.add(new LispSymbol("reify"));
		equivalent.add(new LispSymbol(ClojureInterfaces.OBJECT));
		for (LispVal one : ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(items.get(1)), "reify*")) {
			equivalent.add(groupSymbol(ctx, one, "reify*"));
		}
		equivalent.addAll(items.subList(2, items.size()));
		return ClojureProtocolLowering.reifyForm(ctx, equivalent);
	}

	/**
	 * {@code (deftype* ns/Name ns.Name [field...] :implements [interface...] method...)}:
	 * the type over every field, its own constructor (and {@code create} with record
	 * fields), the rows of every method, answering the class. Options but
	 * {@code :implements} ({@code :tag}, {@code :load-ns}) change nothing here.
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered definition
	 */
	static LispVal deftypeStarOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 4, "deftype* takes a tag, a class name and a field vector");
		String tag = symbolName(items.get(1));
		String className = symbolName(items.get(2));
		LispVal fields = items.get(3);
		ClojureLowerUtil.isTrue(ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(fields)),
				"deftype* takes a field vector, not " + fields.print());
		int at = 4;
		List<LispVal> interfaces = List.of();
		while (at < items.size() && ClojureLowerUtil.stripMeta(items.get(at)) instanceof LispSymbol option
				&& option.name().startsWith(":")) {
			ClojureLowerUtil.isTrue(at + 1 < items.size(), "deftype* option " + option.name() + " takes a value");
			if (option.name().equals(":implements")) {
				LispVal named = ClojureLowerUtil.stripMeta(items.get(at + 1));
				ClojureLowerUtil.isTrue(ClojureBindingLowering.isVectorDatum(named),
						":implements takes a vector of interfaces, not " + named.print());
				interfaces = ClojureLowerUtil.bindingItems(named, "deftype*");
			}
			at += 2;
		}
		String name = tag.substring(tag.indexOf('/') + 1);
		List<LispVal> equivalent = new ArrayList<>();
		equivalent.add(new LispSymbol("deftype"));
		equivalent.add(new LispSymbol(name));
		equivalent.add(unqualifiedFields(ClojureLowerUtil.stripMeta(fields)));
		equivalent.add(new LispSymbol(ClojureInterfaces.OBJECT));
		for (LispVal one : interfaces) {
			equivalent.add(groupSymbol(ctx, one, "deftype*"));
		}
		equivalent.addAll(items.subList(at, items.size()));
		List<String> fieldNames = ClojureProtocolLowering.recordFields(ctx, equivalent);
		int drops = 0;
		while (drops < fieldNames.size() && RECORD_FIELDS.containsKey(fieldNames.get(fieldNames.size() - 1 - drops))) {
			drops++;
		}
		ClojureLowering.TypeDef def = ClojureProtocolLowering.declareStarType(ctx, equivalent, className, drops);
		String key = ClojureLowering.varKey(ctx.currentNs, name);
		ClojureProtocolLowering.TypeBody body = ClojureProtocolLowering.typeBody(ctx,
				equivalent.subList(3, equivalent.size()), "deftype*");
		List<LispVal> forms = new ArrayList<>();
		forms.add(ClojureProtocolLowering.positionalCtor(ctx, def, constructor(key)));
		if (drops > 0) {
			forms.add(createDefun(ctx, key, def));
		}
		forms.addAll(ClojureProtocolLowering.bodyForms(ctx, def, body, "deftype*"));
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
	}

	/**
	 * The group a name of an interface vector stands for: the protocol whose interface
	 * the class names ({@code user.P} for {@code user/P}), else the name itself.
	 */
	private static LispVal groupSymbol(ClojureLowering ctx, LispVal datum, String what) {
		if (!(ClojureLowerUtil.stripMeta(datum) instanceof LispSymbol named) || named.name().startsWith(":")) {
			throw new LispReadException(what + " takes interface names, not " + datum.print());
		}
		String cls = ClojureNamespaceLowering.resolveClass(ctx, named.name());
		Map.Entry<String, ClojureLowering.ProtocolDef> protocol = ClojureProtocolLowering.protocolOfInterface(ctx, cls);
		return protocol != null ? new LispSymbol(protocol.getKey()) : named;
	}

	/**
	 * The field vector with each field's name unqualified, its metadata kept: a
	 * syntax-quoted expansion spells a field it does not unquote qualified (extend-clj's
	 * {@code extend-clj.core/__extmap}), which the oracle's class names by its name.
	 */
	private static LispVal unqualifiedFields(LispVal fields) {
		List<LispVal> out = new ArrayList<>();
		LispVal rest = fields;
		while (rest instanceof am.ik.rontolisp.LispCons cell) {
			out.add(unqualifiedName(cell.car()));
			rest = cell.cdr();
		}
		return ClojureLowerUtil.list(out);
	}

	private static LispVal unqualifiedName(LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		if (parts != null && parts.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			return ClojureLowerUtil.list(parts.get(0), unqualifiedName(parts.get(1)), parts.get(2));
		}
		if (datum instanceof LispSymbol named && named.name().indexOf('/') > 0 && !named.name().startsWith(":")) {
			return new LispSymbol(named.name().substring(named.name().indexOf('/') + 1));
		}
		return datum;
	}

	private static String symbolName(LispVal datum) {
		if (!(ClojureLowerUtil.stripMeta(datum) instanceof LispSymbol named) || named.name().startsWith(":")) {
			throw new LispReadException("deftype* takes a tag and a class name, not " + datum.print());
		}
		return named.name();
	}

	/**
	 * The defun of a {@code deftype*} type's constructor over every field, which no
	 * Clojure name spells.
	 * @param typeKey the type's var key
	 * @return the defun's name
	 */
	static LispSymbol constructor(String typeKey) {
		return new LispSymbol(arrowSym(typeKey).name() + "%new");
	}

	/** The defun of a {@code deftype*} type's static {@code create}. */
	private static LispSymbol creator(String typeKey) {
		return new LispSymbol(arrowSym(typeKey).name() + "%create");
	}

	private static LispSymbol arrowSym(String typeKey) {
		int slash = typeKey.indexOf('/');
		return ClojureLowering.varSym(typeKey.substring(0, slash + 1) + "->" + typeKey.substring(slash + 1));
	}

	/**
	 * A construction of a {@code deftype*} type, {@code (new T args...)},
	 * {@code (T. args...)} or {@code (T/new args...)}: its constructor over the arguments
	 * and the fills of the shorter constructor of their count; any other count is the
	 * oracle's compile-time refusal.
	 * @param ctx the hub
	 * @param typeKey the type's var key
	 * @param def the type
	 * @param argDatums the arguments
	 * @return the lowered construction
	 */
	static LispVal construction(ClojureLowering ctx, String typeKey, ClojureLowering.TypeDef def,
			List<LispVal> argDatums) {
		List<LispVal> fills = fills(def, argDatums.size());
		if (fills == null) {
			throw new LispReadException("No matching ctor found for class " + def.className());
		}
		List<LispVal> call = new ArrayList<>();
		call.add(constructor(typeKey));
		call.addAll(ctx.lowers(argDatums, 0));
		call.addAll(fills);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * {@code T/new} of a {@code deftype*} type as a value: a function of the counts its
	 * constructors take.
	 * @param ctx the hub
	 * @param typeKey the type's var key
	 * @param def the type
	 * @return the value form
	 */
	static LispVal constructionValue(ClojureLowering ctx, String typeKey, ClojureLowering.TypeDef def) {
		LispSymbol args = ctx.freshTemp();
		LispSymbol count = ctx.freshTemp();
		List<LispVal> arms = new ArrayList<>();
		int n = def.fields().size();
		for (int k : List.of(n, n - def.starDrops(), n - 2)) {
			List<LispVal> fills = fills(def, k);
			if (k < 0 || fills == null) {
				continue;
			}
			arms.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(k)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), constructor(typeKey)),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("append"), args,
									ctx.quote(ClojureLowerUtil.list(fills))))));
		}
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
						LispString.literal("No matching ctor found for class " + def.className()))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(count,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args)))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms)));
	}

	/**
	 * The fills of the constructor taking {@code count} arguments: none for every field,
	 * the trailing record fields' for the fields before them, the hash caches' for all
	 * but those; null for a count no constructor takes.
	 */
	private static @org.jspecify.annotations.Nullable List<LispVal> fills(ClojureLowering.TypeDef def, int count) {
		int n = def.fields().size();
		int dropped;
		if (count == n) {
			dropped = 0;
		}
		else if (def.starDrops() > 0 && count == n - def.starDrops()) {
			dropped = def.starDrops();
		}
		else if (def.starDrops() > 2 && count == n - 2 && HASH_FIELDS.contains(def.fields().get(n - 1))
				&& HASH_FIELDS.contains(def.fields().get(n - 2))) {
			dropped = 2;
		}
		else {
			return null;
		}
		List<LispVal> fills = new ArrayList<>();
		for (String field : def.fields().subList(n - dropped, n)) {
			fills.add(RECORD_FIELDS.get(field));
		}
		return fills;
	}

	/**
	 * Whether the type has the oracle's static {@code create}: a {@code deftype*} type
	 * with trailing record fields.
	 * @param def the type
	 * @return {@code true} when {@code T/create} names it
	 */
	static boolean creates(ClojureLowering.TypeDef def) {
		return def.starDrops() > 0;
	}

	/**
	 * {@code (T/create m)} of a {@code deftype*} type: its static {@code create}.
	 * @param ctx the hub
	 * @param typeKey the type's var key
	 * @param argDatums the arguments
	 * @return the lowered call
	 */
	static LispVal createCall(ClojureLowering ctx, String typeKey, List<LispVal> argDatums) {
		ClojureLowerUtil.isTrue(argDatums.size() == 1, "No matching method create found taking " + argDatums.size()
				+ " args for class " + typeKey.replace('/', '.'));
		return ClojureLowerUtil.list(creator(typeKey), ctx.lower(argDatums.get(0)));
	}

	/**
	 * {@code T/create} of a {@code deftype*} type as a value.
	 * @param typeKey the type's var key
	 * @return the value form
	 */
	static LispVal createValue(String typeKey) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), creator(typeKey));
	}

	/**
	 * The static {@code create} of a type with trailing record fields, the oracle's: each
	 * other field read off the map and taken out of it, then the type over them, no
	 * metadata, the rest of the map (nil when empty) as {@code __extmap} and zero hash
	 * caches.
	 */
	private static LispVal createDefun(ClojureLowering ctx, String typeKey, ClojureLowering.TypeDef def) {
		LispSymbol map = new LispSymbol("m");
		int n = def.fields().size();
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> args = new ArrayList<>();
		for (String field : def.fields().subList(0, n - def.starDrops())) {
			LispVal key = new LispSymbol(":" + field);
			bindings.add(new LispSymbol(field));
			bindings.add(ClojureLowerUtil.list(new LispSymbol("clojure.core/get"), map, key));
			bindings.add(map);
			bindings.add(ClojureLowerUtil.list(new LispSymbol("clojure.core/dissoc"), map, key));
			args.add(new LispSymbol(field));
		}
		for (String field : def.fields().subList(n - def.starDrops(), n)) {
			args.add(field.equals("__extmap") ? ClojureLowerUtil.list(new LispSymbol("if"),
					ClojureLowerUtil.list(new LispSymbol("clojure.core/seq"), map), map, ClojureLowering.NIL_CONST)
					: RECORD_FIELDS.get(field));
		}
		List<LispVal> construct = new ArrayList<>();
		construct.add(new LispSymbol("new"));
		construct.add(new LispSymbol(def.simpleName()));
		construct.addAll(args);
		LispVal datum = ClojureLowerUtil.list(new LispSymbol("let*"),
				ClojureLowerUtil.cons(ClojureReader.VECTOR, bindings), ClojureLowerUtil.list(construct));
		Map<String, ClojureLowering.Kind> scope = new HashMap<>(Map.of("m", ClojureLowering.Kind.VARIABLE));
		LispVal body = ctx.inScope(scope, () -> ctx.lower(datum));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), creator(typeKey),
				ClojureLowerUtil.list(ctx.localSym("m")), body);
	}

}
