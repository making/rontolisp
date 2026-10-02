package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Collection forms of the Clojure lowering: maps, sets, vectors, their verbs and function
 * values.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureCollectionLowering {

	private ClojureCollectionLowering() {
	}

	/** The tag heading a wrapped set: a set is {@code (LIST :C%SET table)}. */
	static final LispSymbol SET_TAG = new LispSymbol(":C%SET");

	/**
	 * The tag heading a keyword value: a keyword is {@code (LIST :C%KEYWORD name)}
	 * holding its spelling verbatim (without the colon, case-preserved), so {@code :a}
	 * and {@code :A} stay apart. A cons keys an {@code equal} table structurally, so
	 * keywords key structurally and never collide with strings; {@code equal} compares
	 * two spellings case-sensitively through the same shape.
	 */
	static final LispSymbol KEYWORD_TAG = new LispSymbol(":C%KEYWORD");

	/** A keyword's spelling as data: {@code (:C%KEYWORD "name")}, for quoted forms. */
	static LispVal keywordDatum(String spelling) {
		return new LispCons(KEYWORD_TAG, new LispCons(new LispString(spelling), ClojureLowering.NIL_CONST));
	}

	/** A keyword's construction: {@code (LIST :C%KEYWORD "name")}. */
	static LispVal keywordForm(String spelling) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), KEYWORD_TAG, LispString.literal(spelling));
	}

	/**
	 * The tag heading a nil method's table key: a nil dispatch value answers the one-list
	 * {@code (:C%NIL)}, never the {@code (:C%KEYWORD "nil")} a literal {@code :nil}
	 * keyword lowers to, so the two stay apart like the oracle tells them apart. No user
	 * value spells the tag (a {@code :C%NIL} source spelling lexes as a keyword, which
	 * wraps behind {@code :C%KEYWORD}), the same reason the record, set and keyword
	 * wrappers keep their cars.
	 */
	static final LispSymbol NIL_TAG = new LispSymbol(":C%NIL");

	/** A nil method's table key construction: {@code (LIST :C%NIL)}. */
	static LispVal nilMarkerForm() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), NIL_TAG);
	}

	/**
	 * A keyword's spelling without its colon: {@code ::kw} resolves against the current
	 * namespace, {@code ::alias/kw} against the alias (or the namespace's own name, or a
	 * known namespace without any require), and anything else stays opaque data, printing
	 * and comparing whole like the oracle's. A bare {@code :} names nothing, and an
	 * unknown alias or a second slash is the oracle's {@code Invalid token} refusal.
	 */
	static String resolveKeywordSpelling(ClojureLowering ctx, String name) {
		ClojureLowerUtil.isTrue(name.length() > 1, "a keyword needs a name: " + name);
		if (!name.startsWith("::")) {
			return name.substring(1);
		}
		String rest = name.substring(2);
		int slash = rest.indexOf('/');
		if (slash < 0) {
			if (rest.isEmpty()) {
				throw new LispReadException("Invalid token: " + name);
			}
			return ctx.currentNs + "/" + rest;
		}
		String alias = rest.substring(0, slash);
		String tail = rest.substring(slash + 1);
		if (alias.isEmpty() || tail.isEmpty() || tail.indexOf('/') >= 0) {
			throw new LispReadException("Invalid token: " + name);
		}
		String ns = ctx.aliases.get(alias);
		if (ns == null) {
			if (alias.equals(ctx.currentNs) || ClojureNamespaceLowering.isKnownNamespace(alias)) {
				ns = alias;
			}
			else {
				throw new LispReadException("Invalid token: " + name);
			}
		}
		return ns + "/" + tail;
	}

	/**
	 * A keyword in call position: the map lookup {@code (:k m)} (or with a default
	 * {@code (:k m dflt)}), over the same table-aware read {@code get} lowers to, so sets
	 * answer their member and vectors and strings their indexed element too.
	 */
	static LispVal keywordCall(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, name + " takes a collection and an optional default");
		List<LispVal> getForm = new ArrayList<>();
		getForm.add(new LispSymbol("get"));
		getForm.add(items.get(1));
		getForm.add(items.get(0));
		if (n == 2) {
			getForm.add(items.get(2));
		}
		return getOf(ctx, getForm);
	}

	/** {@code (RONTOLISP:PLIST-HASH-TABLE plist :TEST 'EQUAL)}: a fresh equal table. */
	static LispVal tableFromPlist(LispVal plist) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("rontolisp:plist-hash-table"), plist,
				ClojureLowerUtil.sym(":test"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("equal")));
	}

	/** {@code (RONTOLISP:HASH-TABLE-PLIST table)}. */
	static LispVal tablePlist(LispVal table) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("rontolisp:hash-table-plist"), table);
	}

	/** {@code (MAKE-HASH-TABLE :TEST 'EQUAL)}. */
	static LispVal makeTable() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("make-hash-table"), ClojureLowerUtil.sym(":test"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("equal")));
	}

	/**
	 * Whether the form holds a wrapped set: a cons headed by the tag over a table. The
	 * full shape check keeps user data from misfiring the test.
	 */
	static LispVal isSetForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), SET_TAG),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), form)));
	}

	/** The table inside a wrapped set: {@code (CADR form)}. */
	static LispVal setInner(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), form);
	}

	/** {@code (LIST :C%SET table)}: the set wrapper. */
	static LispVal wrapSet(LispVal table) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), SET_TAG, table);
	}

	/**
	 * A map construction over lowered key/value pairs: an equal table, so vector keys and
	 * nested maps compare structurally. The pairs evaluate once each, left to right.
	 */
	static LispVal mapBuild(List<LispVal> pairs) {
		return tableFromPlist(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), pairs));
	}

	/**
	 * A regex literal: the source string compiled to a pattern value at run time (parsed
	 * eagerly, like the oracle). The source datum is a reader-produced literal, so it
	 * travels as is -- shared by quoted and syntax-quoted literals.
	 */
	static LispVal regexForm(List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "a regex literal takes a pattern string");
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-COMPILE"), items.get(1));
	}

	/**
	 * A set construction over lowered elements: an equal table holding each member under
	 * itself, wrapped so verbs tell it from a map. Each element is bound once, so a
	 * side-effecting element runs once.
	 */
	static LispVal setBuild(ClojureLowering ctx, List<LispVal> elements) {
		LispSymbol table = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(table, makeTable()));
		List<LispVal> body = new ArrayList<>();
		for (LispVal element : elements) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, element));
			body.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table), one));
		}
		body.add(wrapSet(table));
		return ClojureLowerUtil.letForm(bindings, body);
	}

	static LispVal assocOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 3 && n % 2 == 1, "assoc takes a map and key/value pairs");
		LispSymbol map = ctx.freshTemp();
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(map),
				ClojureDispatchLowering.typedTableOf(map), map);
		LispVal grown = ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"),
				List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), map, tablePlist(src),
						ClojureLowering.NIL_CONST),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(map, ctx.lower(items.get(1))))),
				rewrapAnswer(ctx, map, tableFromPlist(grown)));
	}

	/**
	 * {@code assoc} as a value: over a map and a rest list of alternating keys and
	 * values, grown in one copy like a call (later pairs winning, onto {@code nil} from
	 * empty). An odd rest count signals, like a call's pair refusal.
	 */
	static LispVal assocValue(ClojureLowering ctx) {
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("assoc-map"));
		LispSymbol pairs = new LispSymbol(ClojureLowering.mangle("assoc-pairs"));
		LispSymbol bound = ctx.freshTemp();
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(bound),
				ClojureDispatchLowering.typedTableOf(bound), bound);
		LispVal grown = ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), List.of(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), bound, tablePlist(src), ClojureLowering.NIL_CONST),
				pairs));
		LispVal build = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(bound, map))),
				rewrapAnswer(ctx, bound, tableFromPlist(grown)));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("assoc takes a map and key/value pairs"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("oddp"), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), pairs)), arity,
				build);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, ClojureLowering.AMPERSAND_REST, pairs)), body);
	}

	/**
	 * A rebuilt table back in the record it came from: {@code assoc} (and everything
	 * through {@link #assocPairForm}, like {@code update} and {@code assoc-in}) keeps the
	 * record's tag and fields, like the oracle. Anything else answers the table.
	 */
	static LispVal rewrapAnswer(ClojureLowering ctx, LispVal map, LispVal tableForm) {
		LispSymbol done = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(done, tableForm))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(map),
						ClojureDispatchLowering.wrapRecord(ClojureDispatchLowering.typedTagOf(map),
								ClojureDispatchLowering.typedFieldsOf(map), done),
						done));
	}

	static LispVal dissocOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "dissoc takes a map and keys");
		LispSymbol map = ctx.freshTemp();
		LispSymbol copy = ctx.freshTemp();
		List<LispVal> body = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			body.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"), ctx.lower(items.get(i)), copy));
		}
		body.add(dissocAnswer(ctx, map, copy));
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(map),
				ClojureDispatchLowering.typedTableOf(map), map);
		LispVal rebuilt = ClojureLowerUtil
			.letForm(List.of(ClojureLowerUtil.list(copy, tableFromPlist(tablePlist(src)))), body);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(map, ctx.lower(items.get(1))))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), map, rebuilt, ClojureLowering.NIL_CONST));
	}

	/**
	 * A dissociated table back in its record: the record survives only while every
	 * declared field is still present (removing an extension key keeps the type, removing
	 * a base field drops to a plain map), like the oracle. Anything else answers the
	 * table.
	 */
	static LispVal dissocAnswer(ClojureLowering ctx, LispVal map, LispVal copy) {
		LispSymbol keep = ctx.freshTemp();
		LispSymbol field = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispVal scan = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(field, ClojureDispatchLowering.typedFieldsOf(map))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), field, copy, miss), miss),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), keep, ClojureLowering.NIL_CONST)));
		LispVal rewrap = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), keep, ClojureDispatchLowering.wrapRecord(
				ClojureDispatchLowering.typedTagOf(map), ClojureDispatchLowering.typedFieldsOf(map), copy), copy);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(keep, ClojureLowering.TRUE_CONST),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(map),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), scan, rewrap), copy));
	}

	/**
	 * {@code dissoc} as a value: over a map and a rest list of keys, copied once and
	 * dropped one by one, like a call (of {@code nil}, {@code nil}).
	 */
	static LispVal dissocValue(ClojureLowering ctx) {
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("dissoc-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("dissoc-keys"));
		LispSymbol bound = ctx.freshTemp();
		LispSymbol copy = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(bound),
				ClojureDispatchLowering.typedTableOf(bound), bound);
		LispVal drops = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, keys)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"), one, copy));
		LispVal rebuilt = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(copy, tableFromPlist(tablePlist(src))))), drops,
				dissocAnswer(ctx, bound, copy));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, ClojureLowering.AMPERSAND_REST, keys)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(bound, map))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), bound, rebuilt, ClojureLowering.NIL_CONST)));
	}

	/**
	 * The bounds check of an indexed read: a vector for {@code get}, either for the rest.
	 */
	static LispVal indexForm(LispVal coll, LispVal key, boolean vector) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(vector ? "vectorp" : "stringp"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), key),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), key, new LispInteger(0)), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("<"), key, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), coll)));
	}

	static LispVal getOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "get takes a map, a key and an optional default");
		return getForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)),
				n == 3 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST);
	}

	/**
	 * The table-aware read over already-lowered collection, key and default: a set
	 * answers its member, a map its value, a vector or a string its indexed element,
	 * anything else the default.
	 */
	static LispVal getForm(ClojureLowering ctx, LispVal coll, LispVal key, LispVal dflt) {
		LispSymbol collSym = ctx.freshTemp();
		LispSymbol keySym = ctx.freshTemp();
		LispSymbol dfltSym = ctx.freshTemp();
		List<LispVal> bindings = List.of(ClojureLowerUtil.list(collSym, coll), ClojureLowerUtil.list(keySym, key),
				ClojureLowerUtil.list(dfltSym, dflt));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), getBranches(ctx, collSym, keySym, dfltSym)));
	}

	/**
	 * The branches of a table-aware read over an already-bound collection, key and
	 * default: a set answers its member, a map its value, a vector or a string its
	 * indexed element, anything else the default. The three arrive as side-effect-free
	 * forms (bound temporaries, a lambda parameter), so the branches may name them more
	 * than once.
	 */
	static List<LispVal> getBranches(ClojureLowering ctx, LispVal coll, LispVal key, LispVal dflt) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isSetForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, setInner(coll), dflt)));
		// a record reads through its entry table, like a map; a deftype or reify is
		// opaque and falls to the default, like the oracle
		branches.add(ClojureLowerUtil.list(ClojureDispatchLowering.isRecordForm(coll), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("gethash"), key, ClojureDispatchLowering.typedTableOf(coll), dflt)));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, coll, dflt)));
		branches.add(ClojureLowerUtil.list(indexForm(coll, key, true),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), coll, key)));
		branches.add(ClojureLowerUtil.list(indexForm(coll, key, false),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("char"), coll, key)));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, dflt));
		return branches;
	}

	/**
	 * {@code get} as a value: over a collection and a key, or those plus a default -- the
	 * two call shapes, dispatched on the rest count. Any other count signals, like a
	 * call's arity refusal.
	 */
	static LispVal getValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("get-coll"));
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("get-key"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("get-rest"));
		LispVal two = getForm(ctx, coll, key, ClojureLowering.NIL_CONST);
		LispVal three = getForm(ctx, coll, key, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("get takes a map, a key and an optional default"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), two),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest)), three),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(coll, key, ClojureLowering.AMPERSAND_REST, rest)), body);
	}

	static LispVal containsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "contains? takes a collection and a key");
		return containsForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
	}

	/**
	 * The presence test over an already-lowered collection and key: a sentinel
	 * {@code gethash} for maps, records and sets, a bounds check for vectors and strings,
	 * answering {@code T}-or-false.
	 */
	static LispVal containsForm(ClojureLowering ctx, LispVal coll, LispVal key) {
		LispSymbol bound = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> bindings = List.of(ClojureLowerUtil.list(bound, coll), ClojureLowerUtil.list(at, key),
				ClojureLowerUtil.list(miss,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)));
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isSetForm(bound),
				ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), at, setInner(bound), miss),
								miss)))));
		branches.add(
				ClojureLowerUtil
					.list(ClojureDispatchLowering.isRecordForm(bound),
							ctx.booleanAnswer(
									ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
													ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), at,
															ClojureDispatchLowering.typedTableOf(bound), miss),
													miss)))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), bound),
				ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), at, bound, miss), miss)))));
		branches.add(ClojureLowerUtil.list(indexForm(bound, at, true), ClojureLowering.TRUE_CONST));
		branches.add(ClojureLowerUtil.list(indexForm(bound, at, false), ClojureLowering.TRUE_CONST));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ctx.falseVariable));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/** {@code contains?} as a value: a two-argument lambda over the same test. */
	static LispVal containsValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("contains-coll"));
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("contains-key"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(coll, key)),
				containsForm(ctx, coll, key));
	}

	static LispVal keysOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "keys takes one map");
		return tableKeysOf(ctx, items, true);
	}

	static LispVal valsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "vals takes one map");
		return tableKeysOf(ctx, items, false);
	}

	/**
	 * {@code keys} (or {@code vals}): the table's keys (or values) accumulated into a
	 * list. The order is the table's walk order, unspecified like the oracle's.
	 */
	static LispVal tableKeysOf(ClojureLowering ctx, List<LispVal> items, boolean keys) {
		return tableKeysForm(ctx, ctx.lower(items.get(1)), keys);
	}

	/** {@code keys}/{@code vals} over an already-lowered map. */
	static LispVal tableKeysForm(ClojureLowering ctx, LispVal lowered, boolean keys) {
		LispSymbol map = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal take = keys ? key : val;
		LispVal drop = keys ? val : key;
		LispVal collect = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), drop)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), acc,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), take, acc))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(map),
						ClojureDispatchLowering.typedTableOf(map), map));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(map, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), map,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
								ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(acc, ClojureLowering.NIL_CONST))),
								collect, acc),
						ClojureLowering.NIL_CONST));
	}

	/** {@code keys} as a value: a one-argument lambda over the same accumulation. */
	static LispVal keysValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("keys-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				tableKeysForm(ctx, coll, true));
	}

	/** {@code vals} as a value: a one-argument lambda over the same accumulation. */
	static LispVal valsValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("vals-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				tableKeysForm(ctx, coll, false));
	}

	static LispVal mergeOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return ClojureLowering.NIL_CONST;
		}
		if (n == 1) {
			return ctx.lower(items.get(1));
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> present = new ArrayList<>();
		List<LispVal> plists = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, ctx.lower(items.get(i))));
			present.add(one);
			// a record contributes its entries, like a map; anything opaque signals
			// in the plist walk, like the oracle
			LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(one),
					ClojureDispatchLowering.typedTableOf(one), one);
			plists.add(
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, tablePlist(src), ClojureLowering.NIL_CONST));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), present),
						rewrapAnswer(ctx, present.get(0),
								tableFromPlist(ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), plists))),
						ClojureLowering.NIL_CONST));
	}

	/**
	 * {@code merge} as a value: over a rest list of maps, every map's pairs appended in
	 * one copy like a call (later maps winning), rewrapped in the first map's record when
	 * there is one. Of no maps, {@code nil}.
	 */
	static LispVal mergeValue(ClojureLowering ctx) {
		LispSymbol maps = new LispSymbol(ClojureLowering.mangle("merge-maps"));
		LispSymbol one = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol grown = ctx.freshTemp();
		LispSymbol probe = ctx.freshTemp();
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(one),
				ClojureDispatchLowering.typedTableOf(one), one);
		LispVal onePlist = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, tablePlist(src),
				ClojureLowering.NIL_CONST);
		LispVal gather = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), onePlist), maps);
		LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("append")), gather);
		LispVal find = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(probe, maps)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), found), probe),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), found, probe)));
		// the answer is nil unless some map is present, but the rewrap follows the
		// first map (a nil first map answers a plain map), like the oracle
		LispVal first = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), maps);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, maps),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(found, ClojureLowering.NIL_CONST),
								ClojureLowerUtil.list(grown, spread))),
						find, ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), found,
								rewrapAnswer(ctx, first, tableFromPlist(grown)), ClojureLowering.NIL_CONST)));
	}

	static LispVal conjOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "conj takes a collection and items");
		LispVal acc = ctx.lower(items.get(1));
		for (int i = 2; i < items.size(); i++) {
			acc = conjTwo(ctx, acc, items.get(i));
		}
		return acc;
	}

	/**
	 * One conjoined item: a set gains a member, a map gains the item's entries, a vector
	 * gains at the end, a list or nil at the front. Anything else signals, like the
	 * oracle's.
	 */
	static LispVal conjTwo(ClojureLowering ctx, LispVal coll, LispVal itemDatum) {
		return conjTwoForm(ctx, coll, ctx.lower(itemDatum));
	}

	/**
	 * One conjoined item over an already-lowered collection and item: a set gains a
	 * member, a map gains the item's entries, a vector gains at the end, a list or nil at
	 * the front. Anything else signals, like the oracle's.
	 */
	static LispVal conjTwoForm(ClojureLowering ctx, LispVal coll, LispVal itemLowered) {
		LispSymbol collSym = ctx.freshTemp();
		LispSymbol item = ctx.freshTemp();
		List<LispVal> bindings = List.of(ClojureLowerUtil.list(collSym, coll),
				ClojureLowerUtil.list(item, itemLowered));
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isSetForm(collSym), setAdd(ctx, collSym, item)));
		// onto a record the entries join the entry table and the type survives, like
		// the oracle; onto anything opaque the oracle signals, like below
		branches.add(ClojureLowerUtil.list(ClojureDispatchLowering.isRecordForm(collSym),
				rewrapAnswer(ctx, collSym, tableFromPlist(ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"),
						List.of(tablePlist(ClojureDispatchLowering.typedTableOf(collSym)), entryPlist(ctx, item)))))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), collSym),
				tableFromPlist(ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"),
						List.of(tablePlist(collSym), entryPlist(ctx, item))))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), collSym),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("append"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"), collSym,
										ClojureLowerUtil.quoted("list")),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), item)),
						ClojureLowerUtil.quoted("vector"))));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), ClojureLowerUtil.list(
						ClojureLowerUtil.sym("or"), ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), collSym),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), collSym)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
								ClojureDispatchLowering.isTypedForm(collSym)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureStringLowering.isRegexForm(collSym)),
						// atoms (and refs/agents/volatiles, the same cell) are cons
						// wrappers too, so the oracle signals instead of consing (b42,
						// the b21 regex-guard precedent)
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureStateLowering.isAtomForm(collSym))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), item, collSym)));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil
			.list(ClojureLowerUtil.sym("error"), LispString.literal("conj needs a collection and an item"))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/**
	 * The entries one conjoined item adds to a map, as a plist: a map's own pairs, a
	 * two-vector's or two-list's pair, or a set's members each as an entry.
	 */
	static LispVal entryPlist(ClojureLowering ctx, LispVal item) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), item),
				tablePlist(item)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), item),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), item), new LispInteger(2))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), item, new LispInteger(0)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), item, new LispInteger(1)))));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), item),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), isSetForm(item)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), item)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cddr"), item))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), item),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), item))));
		branches.add(ClojureLowerUtil.list(isSetForm(item), membersPlist(ctx, item)));
		branches
			.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
					LispString.literal("conj needs a map entry: a map, a [k v] vector or a (k v) list"))));
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches);
	}

	/**
	 * The entries of a set conjoined onto a map, as a plist: each member is itself an
	 * entry, one level deep. A set nested in the set is refused: entries nest one level.
	 */
	static LispVal membersPlist(ClojureLowering ctx, LispVal item) {
		LispSymbol grown = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal collect = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), grown,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("append"), grown, memberEntryPlist(key)))),
				setInner(item));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(grown, ClojureLowering.NIL_CONST))), collect,
				grown);
	}

	/**
	 * One set member's entries as a plist: a map's pairs, a two-vector's or two-list's
	 * pair. Unlike {@link #entryPlist}, this never recurses, so the Java construction
	 * terminates; a set nested in the conjoined set is refused at run time instead.
	 */
	static LispVal memberEntryPlist(LispVal key) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), key),
				tablePlist(key)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), key),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), key), new LispInteger(2))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), key, new LispInteger(0)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), key, new LispInteger(1)))));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), key),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), isSetForm(key)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), key)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cddr"), key))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), key),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), key))));
		branches
			.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
					LispString.literal("conj needs a map entry: a map, a [k v] vector or a (k v) list"))));
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches);
	}

	/** One member added to a set: a fresh table over the old members plus the member. */
	static LispVal setAdd(ClojureLowering ctx, LispVal coll, LispVal item) {
		LispSymbol table = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal copy = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table), key)),
				setInner(coll));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, makeTable()))), copy,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), item, table), item),
				wrapSet(table));
	}

	/**
	 * {@code conj} as a value: over a collection and a rest list of items, folded one by
	 * one through the same per-kind read, so {@code (map conj ...)} and
	 * {@code (swap! a conj x)} run what a call would run.
	 */
	static LispVal conjValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("conj-coll"));
		LispSymbol items = new LispSymbol(ClojureLowering.mangle("conj-items"));
		LispSymbol acc = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(acc, one)),
				conjTwoForm(ctx, acc, one));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(coll, ClojureLowering.AMPERSAND_REST, items)), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("reduce"), step, items, ClojureLowerUtil.sym(":initial-value"), coll));
	}

	static LispVal disjOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "disj takes a set and members");
		LispSymbol set = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal copy = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table), key)),
				setInner(set));
		List<LispVal> body = new ArrayList<>();
		body.add(ClojureLowerUtil.list(table, makeTable()));
		LispVal kept = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(body), copy,
				remhashes(ctx, items, table), wrapSet(table));
		LispVal needSet = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("disj needs a set"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(set, ctx.lower(items.get(1))))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), set,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isSetForm(set), kept, needSet),
						ClojureLowering.NIL_CONST));
	}

	/**
	 * The {@code remhash} of each of {@code items}' keys from {@code table}, in order.
	 */
	static LispVal remhashes(ClojureLowering ctx, List<LispVal> items, LispSymbol table) {
		if (items.size() == 2) {
			return table;
		}
		List<LispVal> drops = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			drops.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"), ctx.lower(items.get(i)), table));
		}
		drops.add(table);
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), drops);
	}

	/**
	 * {@code disj} as a value: over a set and a rest list of members, copied once and
	 * dropped one by one, like a call (of {@code nil}, {@code nil}; of a non-set, a
	 * signal).
	 */
	static LispVal disjValue(ClojureLowering ctx) {
		LispSymbol set = new LispSymbol(ClojureLowering.mangle("disj-set"));
		LispSymbol members = new LispSymbol(ClojureLowering.mangle("disj-members"));
		LispSymbol bound = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal copy = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table), key)),
				setInner(bound));
		LispVal drops = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(one, members)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"), one, table));
		LispVal kept = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, makeTable()))), copy, drops, wrapSet(table));
		LispVal needSet = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("disj needs a set"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(set, ClojureLowering.AMPERSAND_REST, members)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(bound, set))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), bound,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isSetForm(bound), kept, needSet),
								ClojureLowering.NIL_CONST)));
	}

	static LispVal setOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "set takes one collection");
		return setForm(ctx, ctx.lower(items.get(1)));
	}

	/**
	 * A set over an already-lowered collection: every member under itself in a fresh
	 * table, wrapped so verbs tell it from a map. The walk populates the table for
	 * effect; the wrapper answers.
	 */
	static LispVal setForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol coll = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol entry = ctx.freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
						ClojureLowerUtil.list(List.of(one,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"), coll,
										ClojureLowerUtil.quoted("list")))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table), one))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"), ClojureLowerUtil.list(
						ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
								ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(entry,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), key, val)))),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), entry, table), entry))),
						coll)));
		branches.add(ClojureLowerUtil.list(isSetForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), val)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table), key)),
						setInner(coll))));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, coll)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table), one))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(coll, lowered), ClojureLowerUtil.list(table, makeTable()))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches), wrapSet(table));
	}

	/** {@code set} as a value: a one-argument lambda over the same construction. */
	static LispVal setValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("set-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll), setForm(ctx, coll));
	}

	static LispVal mapConstructorOf(ClojureLowering ctx, List<LispVal> items, String what) {
		ClojureLowerUtil.isTrue((items.size() - 1) % 2 == 0, what + " takes key/value pairs");
		return mapBuild(ctx.lowers(items, 1));
	}

	/**
	 * {@code hash-map}/{@code array-map} as a value: over a rest list of alternating keys
	 * and values, built in one table like a call. An odd rest count signals, like a
	 * call's pair refusal.
	 */
	static LispVal mapConstructorValue(ClojureLowering ctx, String what) {
		LispSymbol pairs = new LispSymbol(ClojureLowering.mangle(what + "-pairs"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal(what + " takes key/value pairs"));
		LispVal body = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("oddp"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), pairs)),
					arity, tableFromPlist(pairs));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, pairs), body);
	}

	/**
	 * {@code vec} over one collection: the fully realized seq view coerced to a vector,
	 * so {@code (vec nil)} is {@code []}, {@code (vec "ab")} is the character vector, and
	 * lazy inputs realize fully (an infinite input hangs, like the oracle's).
	 */
	static LispVal vecOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "vec takes one collection");
		return vecForm(ctx, ctx.lower(items.get(1)));
	}

	/** {@code vec} over an already-lowered collection. */
	static LispVal vecForm(ClojureLowering ctx, LispVal lowered) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REALIZE-ALL"), lowered),
				ClojureLowerUtil.quoted("vector"));
	}

	/** {@code vec} as a value: a one-argument lambda over the same coercion. */
	static LispVal vecValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("vec-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll), vecForm(ctx, coll));
	}

	static LispVal countOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "count takes one collection");
		return countForm(ctx, ctx.lower(items.get(1)));
	}

	/** The count of an already-lowered collection: tables by entries, else length. */
	static LispVal countForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol coll = ctx.freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isSetForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), setInner(coll))));
		// a record counts its entries, like a map; anything opaque signals, like the
		// oracle (a bare length would silently answer the wrapper's size)
		branches.add(ClojureLowerUtil.list(ClojureDispatchLowering.isRecordForm(coll), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("hash-table-count"), ClojureDispatchLowering.typedTableOf(coll))));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), ClojureDispatchLowering.isDeftypeForm(coll),
						ClojureDispatchLowering.isReifyForm(coll)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("count needs a collection"))));
		branches.add(ClojureLowerUtil.list(ClojureStringLowering.isRegexForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("count needs a collection"))));
		// atoms (and refs/agents/volatiles, the same cell) are cons wrappers
		// too, so the oracle signals instead of counting (b45, the b42
		// conj-guard precedent)
		branches.add(ClojureLowerUtil.list(ClojureStateLowering.isAtomForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("count needs a collection"))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), coll)));
		// the false object counts as empty, like the oracle; anything else takes length
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), coll, ctx.falseVariable),
				new LispInteger(0)));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), coll)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(coll, lowered))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	static LispVal emptyOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "empty? takes one collection");
		return emptyForm(ctx, ctx.lower(items.get(1)));
	}

	/** Whether an already-lowered collection is empty, answering raw. */
	static LispVal emptyForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol coll = ctx.freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isSetForm(coll), ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), setInner(coll)))));
		branches.add(ClojureLowerUtil.list(ClojureDispatchLowering.isRecordForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("hash-table-count"), ClojureDispatchLowering.typedTableOf(coll)))));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), ClojureDispatchLowering.isDeftypeForm(coll),
						ClojureDispatchLowering.isReifyForm(coll)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("empty? needs a collection"))));
		branches.add(ClojureLowerUtil.list(ClojureStringLowering.isRegexForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("empty? needs a collection"))));
		// atoms (and refs/agents/volatiles, the same cell) are cons wrappers
		// too, so the oracle signals instead of answering false (b45, the b42
		// conj-guard precedent)
		branches.add(ClojureLowerUtil.list(ClojureStateLowering.isAtomForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("empty? needs a collection"))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), coll))));
		branches
			.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), coll), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("zerop"), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), coll))));
		branches
			.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), coll), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("zerop"), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), coll))));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), coll)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(coll, lowered))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/** {@code inc}/{@code dec} as a value: a one-argument lambda over the primitive. */
	static LispVal incValue(ClojureLowering ctx, String name) {
		LispSymbol x = new LispSymbol(ClojureLowering.mangle("inc-x"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(x),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(name.equals("inc") ? "+" : "-"), x, new LispInteger(1)));
	}

	/** {@code count} as a value: the table-aware count. */
	static LispVal countValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("count-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll), countForm(ctx, coll));
	}

	/**
	 * {@code empty?} as a value: the table-aware emptiness test, answering
	 * {@code T}-or-false.
	 */
	static LispVal emptyValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("empty-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ctx.booleanAnswer(emptyForm(ctx, coll)));
	}

	/**
	 * {@code =} over any arity: pairs of neighbours compared with the map- and set-aware
	 * two-form below, {@code AND}ed. Zero arguments is true; one evaluates its argument
	 * and is true. The answer is raw ({@code T} or {@code NIL}); the call sites wrap it
	 * in {@link #booleanAnswer} for the Clojure {@code T}-or-false.
	 */
	static LispVal equalityRaw(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return ClojureLowering.TRUE_CONST;
		}
		if (n == 1) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(1)),
					ClojureLowering.TRUE_CONST);
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> names = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, ctx.lower(items.get(i))));
			names.add(one);
		}
		if (n == 2) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
					equalityTwo(ctx, names.get(0), names.get(1)));
		}
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i + 1 < names.size(); i++) {
			pairs.add(equalityTwo(ctx, names.get(i), names.get(i + 1)));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("and"), pairs));
	}

	/**
	 * Two values compared the Clojure way: two wrapped sets by membership both ways
	 * (order-free, deep in the members), two tables entry by entry (deep in the values),
	 * anything else with {@code equal}. The comparison is a labels self call, so nested
	 * maps and sets compare all the way down.
	 */
	static LispVal equalityTwo(ClojureLowering ctx, LispVal first, LispVal second) {
		LispSymbol eq = ctx.freshTemp();
		LispSymbol left = ctx.freshTemp();
		LispSymbol right = ctx.freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), isSetForm(left), isSetForm(right)),
				setEquality(ctx, left, right, eq)));
		// two records compare by tag plus entries (never equal to a plain map, like
		// the oracle); a deftype or reify on either side is identity, like the oracle
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), ClojureDispatchLowering.isRecordForm(left),
						ClojureDispatchLowering.isRecordForm(right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"), ClojureDispatchLowering.typedTagOf(left),
								ClojureDispatchLowering.typedTagOf(right)),
						mapEquality(ctx, ClojureDispatchLowering.typedTableOf(left),
								ClojureDispatchLowering.typedTableOf(right), eq))));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), ClojureDispatchLowering.isTypedForm(left),
								ClojureDispatchLowering.isTypedForm(right)),
						ClojureDispatchLowering.isDeftypeForm(left), ClojureDispatchLowering.isReifyForm(left),
						ClojureDispatchLowering.isDeftypeForm(right), ClojureDispatchLowering.isReifyForm(right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), right)),
				mapEquality(ctx, left, right, eq)));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"), left, right)));
		LispVal test = ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches);
		LispVal binding = new LispCons(eq,
				new LispCons(ClojureLowerUtil.list(List.of(left, right)), ClojureLowerUtil.cons(test, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
				new LispCons(eq, ClojureLowerUtil.list(List.of(first, second))));
	}

	/** Two tables are equal when they hold the same count and every entry agrees. */
	static LispVal mapEquality(ClojureLowering ctx, LispVal left, LispVal right, LispVal eq) {
		LispSymbol ok = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal walk = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("when"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, right, miss), miss),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
										ClojureLowerUtil.list(eq, val,
												ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, right)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ok, ClojureLowering.NIL_CONST))),
				left);
		return ClojureLowerUtil
			.list(ClojureLowerUtil.sym("and"), ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), left), ClojureLowerUtil
						.list(ClojureLowerUtil.sym("hash-table-count"), right)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
							ClojureLowerUtil.list(
									List.of(ClojureLowerUtil.list(ok, ClojureLowering.TRUE_CONST),
											ClojureLowerUtil.list(miss, ClojureLowerUtil
												.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
							walk, ok));
	}

	/**
	 * Two wrapped sets are equal when they hold the same count and every member agrees.
	 */
	static LispVal setEquality(ClojureLowering ctx, LispVal left, LispVal right, LispVal eq) {
		LispSymbol ok = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal walk = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), val)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("when"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, found, miss), miss),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
										ClojureLowerUtil.list(eq, key,
												ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, found)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ok, ClojureLowering.NIL_CONST))),
				setInner(left));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), setInner(left)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), setInner(right))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(ok, ClojureLowering.TRUE_CONST),
								ClojureLowerUtil.list(miss,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
								ClojureLowerUtil.list(found, setInner(right)))),
						walk, ok));
	}

	/**
	 * A keyword as a function value: the lookup over one argument plus an optional
	 * default, so {@code (map :k coll)} reads the key out of each member and a
	 * keyword-dispatched multimethod called with several arguments dispatches on the
	 * lookup with the second call argument as the default, like the oracle (the
	 * dispatcher applies the dispatch function to every call argument). The key lowers
	 * once, behind a temporary; the collection is the lambda's first parameter and the
	 * default reads the rest list once -- the same rest-tolerant shape the map/vector/set
	 * siblings lower to. Trailing arguments past the default are ignored, like those
	 * siblings (a lenient superset: the oracle signals past two).
	 * @param keyDatum the keyword datum
	 * @return the form
	 */
	static LispVal keywordFn(ClojureLowering ctx, LispVal keyDatum) {
		LispSymbol coll = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol dflt = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(coll, ClojureLowering.AMPERSAND_REST, rest)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(key, ctx.lower(keyDatum)),
						ClojureLowerUtil.list(dflt, ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest))))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), getBranches(ctx, coll, key, dflt))));
	}

	/**
	 * A predicate as a first-class value: a lambda answering {@code T}-or-false, like a
	 * call, so {@code (map odd? ...)} prints what the oracle prints; sequence operators
	 * that need raw truthiness test through it explicitly. Null when not a predicate.
	 * @param name the Clojure name
	 * @return the lambda, or null
	 */
	static @Nullable LispVal predicateValue(ClojureLowering ctx, String name) {
		LispSymbol arg = new LispSymbol(ClojureLowering.mangle("pred"));
		return switch (name) {
			case "false?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("LAMBDA"), ClojureLowerUtil.list(arg),
					ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), arg, ctx.falseVariable)));
			case "true?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("LAMBDA"), ClojureLowerUtil.list(arg), ctx
				.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), arg, ClojureLowering.TRUE_CONST)));
			case "boolean?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("LAMBDA"), ClojureLowerUtil.list(arg),
					ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("OR"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), arg, ClojureLowering.TRUE_CONST),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), arg, ctx.falseVariable))));
			default -> null;
		};
	}

	/**
	 * A one-argument predicate as a function value, answering {@code T}-or-false like its
	 * call: the raw Common Lisp test runs once, behind a temporary.
	 * @param raw the raw test over the bound value
	 * @return the lambda
	 */
	/** {@code not} as a value: the falsehood of Clojure truthiness. */
	static LispVal notValue(ClojureLowering ctx) {
		LispSymbol arg = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(arg),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), arg),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), arg, ctx.falseVariable)),
						ClojureLowering.TRUE_CONST, ctx.falseVariable));
	}

	static LispVal predValue(ClojureLowering ctx, java.util.function.Function<LispVal, LispVal> raw) {
		LispSymbol arg = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(arg),
				ctx.booleanAnswer(raw.apply(arg)));
	}

	/**
	 * {@code boolean} over an already-lowered value: {@code T} for anything truthy (only
	 * nil and the false object are falsey), the false object otherwise.
	 */
	static LispVal booleanForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol one = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, lowered))),
				ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ctx.isFalsey(one))));
	}

	/** {@code boolean} as a value: a one-argument lambda over the same test. */
	static LispVal booleanValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("boolean-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), booleanForm(ctx, one));
	}

	/**
	 * {@code char} over an already-lowered value: one call to the spliced
	 * {@code rontolisp::%clojure-char} (a character itself, a number through its
	 * truncated code point, anything else a signal).
	 */
	static LispVal charForm(ClojureLowering ctx, LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CHAR"), lowered);
	}

	/** {@code char} as a value: a one-argument lambda over the same conversion. */
	static LispVal charValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("char-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), charForm(ctx, one));
	}

	/**
	 * {@code keyword} over one or two arguments: the spliced
	 * {@code rontolisp::%clojure-keyword-1} (a keyword itself, a symbol's demangled
	 * spelling, a string verbatim, nil for anything else) or
	 * {@code rontolisp::%clojure-keyword-2} (the slash-joined spelling).
	 */
	static LispVal keywordOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "keyword takes a name, or a namespace and a name");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-1"), ctx.lower(items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-2"), ctx.lower(items.get(1)),
				ctx.lower(items.get(2)));
	}

	/** {@code keyword} as a value: the one- and two-argument shapes over a rest list. */
	static LispVal keywordValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("keyword-args"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-1"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
		LispVal two = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-2"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("keyword takes a name, or a namespace and a name"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), one),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						two),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code symbol} over one or two arguments: the spliced
	 * {@code rontolisp::%clojure-symbol-1} (itself for a symbol, the spelled one for a
	 * keyword or a string, else a signal) or {@code rontolisp::%clojure-symbol-2} (a
	 * mangled symbol over the slash-joined spelling, so it prints and compares whole).
	 */
	static LispVal symbolOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "symbol takes a name, or a namespace and a name");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-1"), ctx.lower(items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-2"), ctx.lower(items.get(1)),
				ctx.lower(items.get(2)));
	}

	/** {@code symbol} as a value: the one- and two-argument shapes over a rest list. */
	static LispVal symbolValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("symbol-args"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-1"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
		LispVal two = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-2"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("symbol takes a name, or a namespace and a name"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), one),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						two),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/** {@code name} as a value: a one-argument lambda over the spliced helper. */
	static LispVal nameValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("name-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NAME"), one));
	}

	/** {@code namespace} as a value: a one-argument lambda over the spliced helper. */
	static LispVal namespaceValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("namespace-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NAMESPACE"), one));
	}

	/**
	 * {@code assert} over a test and an optional message: nil when the test is truthy
	 * (nil and the false object are falsey), else a signal. The message evaluates only on
	 * failure (it sits in the else branch), like the oracle's lazy message form.
	 */
	static LispVal assertOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "assert takes a test and an optional message");
		LispVal test = ctx.lower(items.get(1));
		LispVal failure;
		if (n == 2) {
			LispVal text = ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
					LispString.literal("Assert failed: "), ClojureStringLowering.strOf(ctx, ctx.lower(items.get(2)),
							LispString.literal(""), ClojureLowering.NIL_CONST));
			failure = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), text);
		}
		else {
			failure = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), LispString.literal("Assert failed"));
		}
		return ctx.ifFalsey(test, ClojureLowering.NIL_CONST, failure);
	}

	/**
	 * {@code rand} over zero or one arguments: the bare draw is {@code (random 1.0)} (a
	 * double in [0,1)); with a bound it scales one draw (never a domain check -- a
	 * negative bound answers a negative double, like the oracle's multiply).
	 */
	static LispVal randOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 0 || n == 1, "rand takes no bound, or one bound");
		LispVal draw = ClojureLowerUtil.list(ClojureLowerUtil.sym("random"), new LispDouble(1.0));
		if (n == 0) {
			return draw;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("*"), ctx.lower(items.get(1)), draw);
	}

	/** {@code rand} as a value: the zero- and one-argument shapes over a rest list. */
	static LispVal randValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("rand-args"));
		LispVal none = ClojureLowerUtil.list(ClojureLowerUtil.sym("random"), new LispDouble(1.0));
		LispVal one = ClojureLowerUtil.list(ClojureLowerUtil.sym("*"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("random"), new LispDouble(1.0)));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("rand takes no bound, or one bound"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), none),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), one),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code rand-int} over an already-lowered bound: the truncation of one scaled draw
	 * (an int in [0,n) for a positive bound; 0 and negative bounds answer without a
	 * domain check, like the oracle's int-of-rand).
	 */
	static LispVal randIntForm(ClojureLowering ctx, LispVal bound) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("truncate"), ClojureLowerUtil.list(ClojureLowerUtil.sym("*"),
				bound, ClojureLowerUtil.list(ClojureLowerUtil.sym("random"), new LispDouble(1.0))));
	}

	/** {@code rand-int} as a value: a one-argument lambda over the same draw. */
	static LispVal randIntValue(ClojureLowering ctx) {
		LispSymbol bound = new LispSymbol(ClojureLowering.mangle("rand-int-bound"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(bound),
				randIntForm(ctx, bound));
	}

	/**
	 * {@code rand-nth} over an already-lowered collection: the fully realized list
	 * indexed by one scaled draw. Nil answers nil (the empty list with it, both being nil
	 * -- the seq-view past-the-end rule our {@code nth} keeps); an empty vector, string
	 * or seq signals (like the oracle's throw); maps and sets signal too (none are
	 * indexed there).
	 */
	static LispVal randNthForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol whole = ctx.freshTemp();
		LispSymbol realized = ctx.freshTemp();
		LispVal index = ClojureLowerUtil.list(ClojureLowerUtil.sym("truncate"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("*"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), realized),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("random"), new LispDouble(1.0))));
		LispVal hit = ClojureLowerUtil.list(ClojureLowerUtil.sym("nth"), index, realized);
		LispVal emptyErr = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("rand-nth of an empty collection"));
		LispVal refusal = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("rand-nth needs a vector, string, list or seq"));
		LispVal pick = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(realized,
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REALIZE-ALL"), whole)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), realized), emptyErr, hit));
		LispVal seqable = ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), whole),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), whole),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), whole));
		LispVal check = ClojureLowerUtil
			.cons(ClojureLowerUtil.sym("cond"),
					List.of(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), whole),
							ClojureLowering.NIL_CONST),
							ClojureLowerUtil.list(
									ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), isSetForm(whole),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), whole)),
									refusal),
							ClojureLowerUtil.list(seqable, pick),
							ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, refusal)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(whole, lowered))), check);
	}

	/** {@code rand-nth} as a value: a one-argument lambda over the same draw. */
	static LispVal randNthValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("rand-nth-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				randNthForm(ctx, coll));
	}

	/**
	 * {@code shuffle} over an already-lowered collection: the realized members through
	 * the spliced Fisher-Yates, answering a fresh vector. Nil, strings and maps signal
	 * (none shuffle on the oracle either); sets shuffle through their member list.
	 */
	static LispVal shuffleForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol whole = ctx.freshTemp();
		LispVal items = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REALIZE-ALL"), whole);
		LispVal shuffled = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SHUFFLE"), items);
		LispVal refusal = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("shuffle needs a vector, list or set"));
		LispVal check = ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"),
				List.of(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), whole), refusal),
						ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), whole), refusal),
						ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), whole),
								refusal),
						ClojureLowerUtil.list(ClojureDispatchLowering.isRecordForm(whole), refusal),
						ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, shuffled)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(whole, lowered))), check);
	}

	/** {@code shuffle} as a value: a one-argument lambda over the same permutation. */
	static LispVal shuffleValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("shuffle-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				shuffleForm(ctx, coll));
	}

	// b15 map verbs: copy-on-write over fresh tables, like assoc/merge

	/**
	 * One association over already-lowered map, key and value: a fresh table over the old
	 * pairs plus the pair, so {@code assoc} onto nil builds from empty.
	 */
	static LispVal assocPairForm(ClojureLowering ctx, LispVal map, LispVal key, LispVal val) {
		LispSymbol one = ctx.freshTemp();
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(one),
				ClojureDispatchLowering.typedTableOf(one), one);
		LispVal grown = ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), List.of(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, tablePlist(src), ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), key, val)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, map))),
				rewrapAnswer(ctx, one, tableFromPlist(grown)));
	}

	/** {@code update}: the key rewritten through the function and extra arguments. */
	static LispVal updateOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 3, "update takes a map, a key, a function and arguments");
		LispSymbol map = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol fun = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(map, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(key, ctx.lower(items.get(2))));
		bindings.add(ClojureLowerUtil.list(fun, ClojureBindingLowering.fnValue(ctx, items.get(3))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), updateForm(ctx, map,
				key, fun, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 4))));
	}

	/**
	 * The update over already-lowered map, key, function and the extra-arguments tail
	 * list: {@code (apply f (cons current tail))} associated back, copy-on-write.
	 */
	static LispVal updateForm(ClojureLowering ctx, LispVal map, LispVal key, LispVal fun, LispVal tail) {
		LispSymbol one = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol fn = ctx.freshTemp();
		LispVal next = ctx.callableApply(fn, ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
				getForm(ctx, one, at, ClojureLowering.NIL_CONST), tail));
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(one),
				ClojureDispatchLowering.typedTableOf(one), one);
		LispVal grown = ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), List.of(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, tablePlist(src), ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), at, next)));
		return ClojureLowerUtil.list(
				ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, map),
						ClojureLowerUtil.list(at, key), ClojureLowerUtil.list(fn, fun))),
				rewrapAnswer(ctx, one, tableFromPlist(grown)));
	}

	/** {@code update} as a value: map, key, function and any extra arguments. */
	static LispVal updateValue(ClojureLowering ctx) {
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("update-map"));
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("update-key"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("update-fn"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("update-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, key, fun, ClojureLowering.AMPERSAND_REST, rest)),
				updateForm(ctx, map, key, fun, rest));
	}

	/** The key vector of {@code update-in}/{@code assoc-in}/{@code get-in}. */
	static List<LispVal> keysVector(LispVal datum, String what) {
		List<LispVal> keyItems = ClojureLowerUtil.items(datum);
		if (keyItems == null || keyItems.isEmpty() || keyItems.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(what + " takes a vector of keys, not " + datum.print());
		}
		return keyItems.subList(1, keyItems.size());
	}

	/** {@code update-in}: the nested update, recursing down the key vector. */
	static LispVal updateInOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 3, "update-in takes a map, keys, a function and arguments");
		List<LispVal> keyData = keysVector(items.get(2), "update-in");
		ClojureLowerUtil.isTrue(!keyData.isEmpty(), "update-in takes a non-empty vector of keys");
		LispSymbol map = ctx.freshTemp();
		LispSymbol fun = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(map, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(fun, ClojureBindingLowering.fnValue(ctx, items.get(3))));
		List<LispVal> keys = new ArrayList<>();
		for (LispVal keyDatum : keyData) {
			LispSymbol key = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(key, ctx.lower(keyDatum)));
			keys.add(key);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), updateInForm(ctx,
				map, keys, fun, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 4))));
	}

	/**
	 * The nested update over already-lowered map, keys, function and extra-arguments
	 * tail: the leaf updates, outer levels re-associate through a one-argument lambda.
	 */
	static LispVal updateInForm(ClojureLowering ctx, LispVal map, List<LispVal> keys, LispVal fun, LispVal tail) {
		if (keys.size() == 1) {
			return updateForm(ctx, map, keys.get(0), fun, tail);
		}
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				updateInForm(ctx, inner, keys.subList(1, keys.size()), fun, tail));
		return updateForm(ctx, map, keys.get(0), step, ClojureLowering.NIL_CONST);
	}

	/** {@code update-in} as a value: the key sequence walked at run time. */
	static LispVal updateInValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("update-in-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("update-in-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("update-in-keys"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("update-in-fn"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("update-in-rest"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol whole = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left), inner));
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), left),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
						LispString.literal("update-in takes a non-empty vector of keys")),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left)),
						updateForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), fun, rest),
						updateForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), step,
								ClojureLowering.NIL_CONST)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left, whole)), ClojureLowerUtil.cons(go, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, keys, fun, ClojureLowering.AMPERSAND_REST, rest)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, ClojureSeqLowering.seqForm(ctx, keys), map)));
	}

	/** {@code assoc-in}: the nested association, building missing levels. */
	static LispVal assocInOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "assoc-in takes a map, keys and a value");
		List<LispVal> keyData = keysVector(items.get(2), "assoc-in");
		LispSymbol map = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(map, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(val, ctx.lower(items.get(3))));
		if (keyData.isEmpty()) {
			// like the oracle: no keys associates under nil
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
					assocPairForm(ctx, map, ClojureLowering.NIL_CONST, val));
		}
		List<LispVal> keys = new ArrayList<>();
		for (LispVal keyDatum : keyData) {
			LispSymbol key = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(key, ctx.lower(keyDatum)));
			keys.add(key);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				assocInForm(ctx, map, keys, val));
	}

	/**
	 * The nested association over already-lowered map, keys and value: the leaf
	 * associates, outer levels re-associate through a one-argument lambda.
	 */
	static LispVal assocInForm(ClojureLowering ctx, LispVal map, List<LispVal> keys, LispVal val) {
		if (keys.size() == 1) {
			return assocPairForm(ctx, map, keys.get(0), val);
		}
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				assocInForm(ctx, inner, keys.subList(1, keys.size()), val));
		return updateForm(ctx, map, keys.get(0), step, ClojureLowering.NIL_CONST);
	}

	/** {@code assoc-in} as a value: the key sequence walked at run time. */
	static LispVal assocInValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("assoc-in-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("assoc-in-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("assoc-in-keys"));
		LispSymbol val = new LispSymbol(ClojureLowering.mangle("assoc-in-val"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol whole = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left), inner));
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left)),
				assocPairForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), val),
				updateForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), step,
						ClojureLowering.NIL_CONST));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left, whole)), ClojureLowerUtil.cons(go, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(map, keys, val)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, ClojureSeqLowering.seqForm(ctx, keys), map)));
	}

	/**
	 * {@code get-in}: the read folded down the key vector, the default threaded through
	 * every level, like the oracle.
	 */
	static LispVal getInOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "get-in takes a map, keys and an optional default");
		List<LispVal> keyData = keysVector(items.get(2), "get-in");
		LispVal dflt = n == 3 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST;
		LispVal acc = ctx.lower(items.get(1));
		for (LispVal keyDatum : keyData) {
			acc = getForm(ctx, acc, ctx.lower(keyDatum), dflt);
		}
		return acc;
	}

	/** {@code get-in} as a value: the key sequence walked at run time. */
	static LispVal getInValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("get-in-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("get-in-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("get-in-keys"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("get-in-rest"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol whole = ctx.freshTemp();
		LispSymbol dflt = ctx.freshTemp();
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), left), whole,
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left),
						getForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), dflt)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left, whole)), ClojureLowerUtil.cons(go, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, keys, ClojureLowering.AMPERSAND_REST, rest)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(dflt, ClojureLowerUtil.list(
								ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
								ClojureLowering.NIL_CONST, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest))))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
								ClojureLowerUtil.list(self, ClojureSeqLowering.seqForm(ctx, keys), map))));
	}

	/**
	 * {@code select-keys}: a fresh map holding the present keys only. Of nil, the empty
	 * map; anything else that is no map signals.
	 */
	static LispVal selectKeysForm(ClojureLowering ctx, LispVal map, LispVal keys) {
		LispSymbol whole = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol out = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal keep = ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, out), got);
		LispSymbol src = ctx.freshTemp();
		LispVal gather = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(one, keys)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, src, miss)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, miss), ClojureLowering.NIL_CONST,
								keep)));
		// a record reads through its entry table and answers a plain map, like the
		// oracle; anything opaque signals, like the oracle
		LispVal norm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(whole),
				ClojureDispatchLowering.typedTableOf(whole), whole);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(whole, map),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(out, makeTable()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), whole), out,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
								ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(src, norm))),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), src),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), gather, out),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
												LispString.literal("select-keys needs a map"))))));
	}

	/** {@code select-keys} as a value: a two-argument lambda over the same read. */
	static LispVal selectKeysValue(ClojureLowering ctx) {
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("select-keys-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("select-keys-keys"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(map, keys)),
				selectKeysForm(ctx, map, ClojureSeqLowering.seqForm(ctx, keys)));
	}

	/** {@code merge-with}: every map merged, conflicts resolved through the function. */
	static LispVal mergeWithOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "merge-with takes a function and maps");
		if (n == 1) {
			return ClojureLowering.NIL_CONST;
		}
		if (n == 2) {
			return ctx.lower(items.get(2));
		}
		LispVal fun = ClojureBindingLowering.fnValue(ctx, items.get(1));
		List<LispVal> maps = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			maps.add(ctx.lower(items.get(i)));
		}
		return mergeWithForm(ctx, fun, maps);
	}

	/**
	 * The merge over an already-lowered function and maps: a fresh table grown map by
	 * map, so inputs are never mutated and nil maps contribute nothing.
	 */
	static LispVal mergeWithForm(ClojureLowering ctx, LispVal fun, List<LispVal> maps) {
		LispSymbol fn = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(fn, fun));
		bindings.add(ClojureLowerUtil.list(acc, makeTable()));
		bindings.add(ClojureLowerUtil.list(miss,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)));
		List<LispVal> syms = new ArrayList<>();
		for (LispVal map : maps) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, map));
			syms.add(one);
		}
		List<LispVal> merges = new ArrayList<>();
		for (LispVal one : syms) {
			LispSymbol key = ctx.freshTemp();
			LispSymbol val = ctx.freshTemp();
			LispSymbol old = ctx.freshTemp();
			LispVal invoked = ctx.callFun(fun, fn, List.of(old, val));
			// a record contributes its entries, like a map; anything opaque signals
			// in the maphash, like the oracle
			LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(one),
					ClojureDispatchLowering.typedTableOf(one), one);
			LispVal join = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)), ClojureLowerUtil.list(
						ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(old,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, acc, miss)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, acc),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), old, miss), val, invoked)))),
					src);
			merges.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, join, ClojureLowering.NIL_CONST));
		}
		merges.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), syms), rewrapAnswer(ctx, syms.get(0), acc),
				ClojureLowering.NIL_CONST));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), merges));
	}

	/**
	 * {@code merge-with} as a value: the function, then any number of maps, grown map by
	 * map through {@code f} like a call and rewrapped in the first rest map's record when
	 * there is one. Of no maps, {@code nil}.
	 */
	static LispVal mergeWithValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("merge-with-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("merge-with-fn"));
		LispSymbol maps = new LispSymbol(ClojureLowering.mangle("merge-with-maps"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispSymbol old = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol probe = ctx.freshTemp();
		// a record contributes its entries, like a map; anything opaque signals
		// in the maphash, like the oracle (the mergeWithForm precedent)
		LispVal head = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left);
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureDispatchLowering.isRecordForm(head),
				ClojureDispatchLowering.typedTableOf(head), head);
		LispVal join = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)), ClojureLowerUtil.list(
					ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil
						.list(old, ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, acc, miss)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, acc),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), old, miss), val,
									ctx.callableApply(fn,
											ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), List.of(old, val))))))),
				src);
		LispVal answer = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), found,
				rewrapAnswer(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), maps), acc),
				ClojureLowering.NIL_CONST);
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), left), answer,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), join, ClojureLowering.NIL_CONST),
						ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left)), ClojureLowerUtil.cons(go, List.of())));
		// the answer is nil unless some rest map is present, but the rewrap follows
		// the first rest map (a nil first map answers a plain map), like the oracle
		LispVal find = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(probe, maps)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), found), probe),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), found, probe)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, maps)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(acc, makeTable()),
								ClojureLowerUtil.list(miss,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
								ClojureLowerUtil.list(found, ClojureLowering.NIL_CONST))),
						find, ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"),
								ClojureLowerUtil.list(List.of(binding)), ClojureLowerUtil.list(self, maps))));
	}

	/**
	 * {@code into}: the source conjoined onto the target, one member at a time. A
	 * three-argument call names a transducer, which stays refused.
	 */
	static LispVal intoOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 3) {
			throw new LispReadException("transducers are not supported yet: into");
		}
		ClojureLowerUtil.isTrue(n == 2, "into takes a target and a source collection");
		return intoForm(ctx, ctx.lower(items.get(1)), ClojureSeqLowering.seqForm(ctx, ctx.lower(items.get(2))));
	}

	/** The conj fold over an already-lowered target and seq view. */
	static LispVal intoForm(ClojureLowering ctx, LispVal to, LispVal from) {
		LispSymbol acc = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(acc, one)),
				conjTwoForm(ctx, acc, one));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("reduce"), step, from, ClojureLowerUtil.sym(":initial-value"),
				to);
	}

	/** {@code into} as a value: a two-argument lambda over the same fold. */
	static LispVal intoValue(ClojureLowering ctx) {
		LispSymbol to = new LispSymbol(ClojureLowering.mangle("into-to"));
		LispSymbol from = new LispSymbol(ClojureLowering.mangle("into-from"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(to, from)),
				intoForm(ctx, to, ClojureSeqLowering.seqForm(ctx, from)));
	}

	/**
	 * {@code frequencies}: the member counts in one pass over the seq view, as a fresh
	 * map.
	 */
	static LispVal frequenciesForm(ClojureLowering ctx, LispVal seq) {
		LispSymbol table = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol old = ctx.freshTemp();
		LispVal bump = ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), old, miss), new LispInteger(1),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), old, new LispInteger(1))));
		LispVal step = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, seq)),
					ClojureLowerUtil
						.list(ClojureLowerUtil.sym("let"),
								ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(old,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table, miss)))),
								bump));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, makeTable()),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				step, table);
	}

	/** {@code frequencies} as a value: a one-argument lambda over the same pass. */
	static LispVal frequenciesValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("frequencies-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				frequenciesForm(ctx, ClojureSeqLowering.seqForm(ctx, coll)));
	}

	// b15 higher-order functions: closures, no new runtime

	/** {@code comp}: right-nested application, no functions the identity. */
	static LispVal compOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return identityValue(ctx);
		}
		List<LispVal> fns = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			fns.add(ClojureBindingLowering.fnValue(ctx, items.get(i)));
		}
		if (n == 1) {
			return fns.get(0);
		}
		return compForm(ctx, fns);
	}

	/**
	 * The composition over already-lowered functions: the rightmost spreads the
	 * arguments, each outer wraps one result.
	 */
	static LispVal compForm(ClojureLowering ctx, List<LispVal> fns) {
		LispSymbol args = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> names = new ArrayList<>();
		for (LispVal fn : fns) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, fn));
			names.add(one);
		}
		LispVal inner = ctx.callableApply(names.get(names.size() - 1), args);
		for (int i = names.size() - 2; i >= 0; i--) {
			inner = ctx.callFun(fns.get(i), names.get(i), List.of(inner));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), inner));
	}

	/**
	 * {@code comp} as a value: the function list composed at run time, so
	 * {@code (apply comp fns)} runs.
	 */
	static LispVal compValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("comp-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fns = ctx.freshTemp();
		LispSymbol fun = ctx.freshTemp();
		LispSymbol next = ctx.freshTemp();
		LispSymbol args = ctx.freshTemp();
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("comp-one"));
		LispSymbol more = new LispSymbol(ClojureLowering.mangle("comp-more"));
		LispVal identity = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(one, ClojureLowering.AMPERSAND_REST, more)), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("declare"), ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), more)),
				one);
		LispVal chain = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)),
				ctx.callableApply(fun, ClojureLowerUtil.list(List.of(ctx.callableApply(next, args)))));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil
			.sym("null"), fns), identity, ClojureLowerUtil.list(
					ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), fns)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), fns),
					ClojureLowerUtil.list(
							ClojureLowerUtil.sym("let*"),
							ClojureLowerUtil.list(List.of(
									ClojureLowerUtil.list(fun, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), fns)),
									ClojureLowerUtil.list(next,
											ClojureLowerUtil.list(self,
													ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), fns))))),
							chain)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(fns)), ClojureLowerUtil.cons(step, List.of())));
		LispSymbol outer = new LispSymbol(ClojureLowering.mangle("comp-fns"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, outer),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, outer)));
	}

	/**
	 * {@code partial}: the function over the fixed arguments plus whatever arrives. The
	 * fixed arguments run once, behind temporaries.
	 */
	static LispVal partialForm(ClojureLowering ctx, LispVal fun, List<LispVal> fixed) {
		LispSymbol fn = ctx.freshTemp();
		LispSymbol more = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(fn, fun));
		List<LispVal> names = new ArrayList<>();
		for (LispVal arg : fixed) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, arg));
			names.add(one);
		}
		LispVal tail = names.isEmpty() ? more : ClojureLowerUtil.list(ClojureLowerUtil.sym("append"),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), names), more);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, more), ctx.callableApply(fn, tail)));
	}

	/** {@code partial} as a value: the function, then the fixed arguments. */
	static LispVal partialValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("partial-fn"));
		LispSymbol fixed = new LispSymbol(ClojureLowering.mangle("partial-fixed"));
		LispSymbol more = new LispSymbol(ClojureLowering.mangle("partial-more"));
		LispVal inner = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, more),
				ctx.callableApply(fn, ClojureLowerUtil.list(ClojureLowerUtil.sym("append"), fixed, more)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, fixed)), inner);
	}

	/**
	 * {@code complement}: the predicate negated, answering {@code T}-or-false, like every
	 * boolean-answering builtin.
	 */
	static LispVal complementForm(ClojureLowering ctx, LispVal fun) {
		LispSymbol fn = ctx.freshTemp();
		LispSymbol args = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal neg = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, ctx.callableApply(fn, args)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
						ClojureLowering.TRUE_CONST, ctx.falseVariable));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fn, fun))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), neg));
	}

	/** {@code complement} as a value: a one-argument lambda over the same negation. */
	static LispVal complementValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("complement-fn"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(fun),
				complementForm(ctx, fun));
	}

	/**
	 * {@code constantly}: the value answered whatever the arguments -- evaluated once,
	 * behind a temporary.
	 */
	static LispVal constantlyForm(ClojureLowering ctx, LispVal val) {
		LispSymbol kept = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(kept, val))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, rest),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), rest)),
						kept));
	}

	/** {@code constantly} as a value: a one-argument lambda over the same closure. */
	static LispVal constantlyValue(ClojureLowering ctx) {
		LispSymbol val = new LispSymbol(ClojureLowering.mangle("constantly-val"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(val),
				constantlyForm(ctx, val));
	}

	/** {@code identity} as a value: the one-argument lambda. */
	static LispVal identityValue(ClojureLowering ctx) {
		LispSymbol val = new LispSymbol(ClojureLowering.mangle("identity-val"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(val), val);
	}

	/**
	 * {@code memoize}: the function cached behind an {@code equal} table, so repeated
	 * arguments run once. The table lives in the closure -- the atom cell's shape,
	 * without the tag -- and the argument list keys structurally.
	 */
	static LispVal memoizeForm(ClojureLowering ctx, LispVal fun) {
		LispSymbol table = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol fn = ctx.freshTemp();
		LispSymbol args = ctx.freshTemp();
		LispSymbol hit = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal inner = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(hit,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), args, table, miss)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), hit, miss),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
										ClojureLowerUtil
											.list(List.of(ClojureLowerUtil.list(val, ctx.callableApply(fn, args)))),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), args, table),
												val),
										val),
								hit)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, makeTable()),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(fn, fun))),
				inner);
	}

	/** {@code memoize} as a value: a one-argument lambda over the same cache. */
	static LispVal memoizeValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("memoize-fn"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(fun), memoizeForm(ctx, fun));
	}

	/**
	 * {@code trampoline}: the function applied, then every thunk result invoked with no
	 * arguments until a non-function answers. A labels self call, so mutual thunk chains
	 * stay constant-stack on the interpreter.
	 */
	static LispVal trampolineForm(ClojureLowering ctx, LispVal fun, LispVal argList) {
		String name = ClojureLowering.mangle("trampoline-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fn = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), got),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), got)), got);
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(got)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fn, fun))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, ctx.callableApply(fn, argList))));
	}

	/** {@code trampoline} as a value: the function, then any arguments. */
	static LispVal trampolineValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("trampoline-fn"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("trampoline-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fun, ClojureLowering.AMPERSAND_REST, rest)),
				trampolineForm(ctx, fun, rest));
	}

	// b15 predicates and casts

	/**
	 * Whether the lowered value is a collection: a list, vector, map or set. Nil and the
	 * false object are no collections, like the oracle -- and neither are strings, even
	 * though the runtime stores them as vectors (like {@code vector?} sees).
	 */
	static LispVal collRaw(ClojureLowering ctx, LispVal lowered) {
		LispSymbol one = ctx.freshTemp();
		LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), one)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureStringLowering.isRegexForm(one)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), one),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), one),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), one), isSetForm(one)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, lowered))), test);
	}

	/** {@code coll?} as a value: a one-argument lambda answering {@code T}-or-false. */
	static LispVal collValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("coll-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
				ctx.booleanAnswer(collRaw(ctx, one)));
	}

	/** {@code string?} as a value: a one-argument lambda answering {@code T}-or-false. */
	static LispVal stringPredValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("string-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
				ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), one)));
	}

	/**
	 * Whether the lowered value is a symbol: true for identifiers, false for the
	 * booleans, nil, keywords (which are lists) and everything else.
	 */
	static LispVal symbolRaw(ClojureLowering ctx, LispVal lowered) {
		LispSymbol one = ctx.freshTemp();
		LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("symbolp"), one),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), one)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ClojureLowering.TRUE_CONST)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ctx.falseVariable)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, lowered))), test);
	}

	/** {@code symbol?} as a value: a one-argument lambda answering {@code T}-or-false. */
	static LispVal symbolPredValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("symbol-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
				ctx.booleanAnswer(symbolRaw(ctx, one)));
	}

}
