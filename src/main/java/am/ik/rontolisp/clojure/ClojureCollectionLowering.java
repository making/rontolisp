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

	/**
	 * The host arm test of {@code seq}, {@code count}, {@code empty?}, {@code get},
	 * {@code contains?}, {@code keys}, {@code vals} and the map verbs ({@code find},
	 * {@code select-keys}, {@code reduce-kv}, {@code conj}, {@code merge},
	 * {@code merge-with}; {@code clojure.lisp}): a host {@code Iterable}, {@code Map} or
	 * {@code CharSequence}, which only a {@code java:} operator hands the program
	 * ({@link ClojureArms.Family#HOST}).
	 */
	static final String HOST_SEQABLE_P = "RONTOLISP::%CLOJURE-HOST-SEQABLE-P";

	/**
	 * A verb's host arm, the clause ahead of its fall-through: the host object
	 * {@code coll} answers {@code body}. A program naming no {@code java:} operator sheds
	 * it.
	 */
	private static LispVal hostArm(LispVal coll, LispVal body) {
		return ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(HOST_SEQABLE_P), coll), body);
	}

	/**
	 * A call of the {@code clojure.lisp} host helper {@code %clojure-host-<suffix>}, the
	 * suffix in its canonical spelling.
	 */
	private static LispVal hostCall(String suffix, LispVal... args) {
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol("RONTOLISP::%CLOJURE-HOST-" + suffix));
		call.addAll(List.of(args));
		return ClojureLowerUtil.list(call);
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
		String ns = ctx.ns().aliases.get(alias);
		if (ns == null) {
			if (alias.equals(ctx.currentNs) || ClojureNamespaceLowering.isKnownNamespace(alias)
					|| ClojureBuiltinNamespaces.isStartup(alias)) {
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

	/**
	 * Whether a lowered key form is a literal whose {@code =} an {@code equal} table
	 * already decides: a keyword construction, a string, number or character literal,
	 * {@code nil}, {@code true}, or a quoted symbol or literal. Such a key skips the
	 * structural-key runtime (clojure.lisp, "Structural keys"), so the common keyword
	 * lookup pays nothing.
	 */
	static boolean isScalarKeyForm(LispVal form) {
		if (isScalarLiteral(form)) {
			return true;
		}
		List<LispVal> parts = ClojureLowerUtil.items(form);
		if (parts == null) {
			return false;
		}
		if (parts.size() == 3 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "LIST")
				&& ClojureLowerUtil.isSymbolNamed(parts.get(1), KEYWORD_TAG.name())
				&& parts.get(2) instanceof LispString) {
			return true;
		}
		return parts.size() == 2 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "QUOTE")
				&& (parts.get(1) instanceof LispSymbol || isScalarLiteral(parts.get(1)));
	}

	private static boolean isScalarLiteral(LispVal form) {
		if (form instanceof LispDouble d) {
			// the two float zeros are one key under =, not under equal
			return d.value() != 0.0;
		}
		return form instanceof LispString || form instanceof LispInteger || form instanceof LispChar
				|| form instanceof LispNil || form instanceof LispTrue;
	}

	/**
	 * The key a lookup of {@code key} reads {@code table} under: the stored key {@code =}
	 * to it ({@code rontolisp::%clojure-table-key}), or a literal scalar key itself. Both
	 * forms must be side-effect-free when {@code table} is named again at the site.
	 */
	static LispVal lookupKey(LispVal key, LispVal table) {
		return lookupKey(key, table, isScalarKeyForm(key));
	}

	/**
	 * {@link #lookupKey} for a key already bound to a temporary, whose literal-scalar
	 * test the caller made on the form it bound.
	 */
	static LispVal lookupKey(LispVal key, LispVal table, boolean scalar) {
		return scalar ? key : ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TABLE-KEY"), key, table);
	}

	/**
	 * The key a store of {@code key} into {@code table} writes under
	 * ({@code rontolisp::%clojure-store-key}: the {@code =} key the table holds, else the
	 * program's representative), or a literal scalar key itself.
	 */
	static LispVal storeKey(LispVal key, LispVal table) {
		return isScalarKeyForm(key) ? key
				: ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-STORE-KEY"), key, table);
	}

	/**
	 * {@code (SETF (GETHASH key table) value)} with the key stored through
	 * {@link #storeKey}.
	 */
	static LispVal tablePut(LispVal table, LispVal key, LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), storeKey(key, table), table), value);
	}

	/**
	 * One member added to a set's table, stored under itself through the structural-key
	 * runtime ({@code rontolisp::%clojure-set-put}); a literal scalar member inline.
	 */
	static LispVal setPut(LispVal table, LispVal member) {
		if (isScalarKeyForm(member)) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), member, table), member);
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SET-PUT"), table, member);
	}

	/**
	 * A fresh map over {@code base}'s entries (a form answering a table or {@code nil},
	 * copied as is) plus the alternating keys and values {@code plist} answers, each key
	 * stored through the structural-key runtime, later pairs winning
	 * ({@code rontolisp::%clojure-plist-table}).
	 */
	static LispVal grownTable(LispVal base, LispVal plist) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-PLIST-TABLE"), base, plist);
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
	 * A map construction over lowered key/value pairs: an equal table, every key a
	 * literal scalar ({@link #isScalarKeyForm}) placed directly, any other through the
	 * structural-key runtime so vector keys and nested maps find each other by {@code =}.
	 * The pairs evaluate once each, left to right.
	 */
	static LispVal mapBuild(List<LispVal> pairs) {
		LispVal plist = ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), pairs);
		for (int i = 0; i < pairs.size(); i += 2) {
			if (!isScalarKeyForm(pairs.get(i))) {
				return grownTable(ClojureLowering.NIL_CONST, plist);
			}
		}
		return tableFromPlist(plist);
	}

	/**
	 * A map literal ({@code (%hash-map k v ...)} as read), refusing keys {@code =} once
	 * evaluated like the oracle's compiler (clj 1.12.6, 2026-10-08): when every key is a
	 * constant ({@link #constantKey}), two {@code =} ones are its compile-time
	 * {@code Duplicate constant keys in map}, refused here, and the map builds unchecked;
	 * otherwise two or more pairs build through {@code rontolisp::%clojure-map-literal},
	 * whose run-time {@code Duplicate key} names the earlier key. The read forms already
	 * differ ({@code ClojureReader.equivKey}).
	 */
	static LispVal mapLiteral(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> pairs = ctx.lowers(items, 1);
		List<LispVal> keys = new ArrayList<>();
		for (int i = 1; i < items.size(); i += 2) {
			keys.add(items.get(i));
		}
		Set<String> constants = constantKeys(ctx, keys);
		if (constants != null) {
			ClojureLowerUtil.isTrue(constants.size() == keys.size(), "Duplicate constant keys in map");
			return mapBuild(pairs);
		}
		if (keys.size() < 2) {
			return mapBuild(pairs);
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP-LITERAL"),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), pairs));
	}

	/**
	 * A set literal ({@code (%hash-set x ...)} as read): every member a constant
	 * ({@link #constantKey}) builds unchecked, {@code =} constants deduplicated like the
	 * oracle's constant set; otherwise two or more members build through
	 * {@code rontolisp::%clojure-set-literal}, whose run-time {@code Duplicate key} names
	 * the later member.
	 */
	static LispVal setLiteral(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> members = items.subList(1, items.size());
		List<LispVal> elements = ctx.lowers(items, 1);
		if (members.size() < 2 || constantKeys(ctx, members) != null) {
			return setBuild(ctx, elements);
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SET-LITERAL"),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), elements));
	}

	/**
	 * The {@code =} classes of the constant values {@code forms} answer
	 * ({@link #constantKey}), or {@code null} when one of them is no constant.
	 */
	private static @Nullable Set<String> constantKeys(ClojureLowering ctx, List<LispVal> forms) {
		Set<String> keys = new HashSet<>();
		int[] unique = { 0 };
		for (LispVal form : forms) {
			String key = constantKey(ctx, form, false, unique);
			if (key == null) {
				return null;
			}
			keys.add(key);
		}
		return keys;
	}

	/**
	 * A string equal for two read forms exactly when the constant values they answer are
	 * {@code =}, or {@code null} when {@code form} is no constant to the oracle's
	 * compiler (its {@code LiteralExpr}): a number, string, character, keyword,
	 * {@code nil}, {@code true}, {@code false}, a quoted datum, a regex, or a non-empty
	 * vector, map or set literal of constants without metadata. Inside a quoted datum
	 * ({@code quoted}) every form is data: a symbol stands for itself and metadata is
	 * ignored. A vector equals a list of the same members, a map or set compares its
	 * members in any order, the float zeros are one value, a regex or {@code ##NaN}
	 * equals nothing else.
	 */
	static @Nullable String constantKey(ClojureLowering ctx, LispVal form, boolean quoted, int[] unique) {
		if (form instanceof LispDouble number) {
			if (Double.isNaN(number.value())) {
				return "#" + unique[0]++;
			}
			return "d" + (number.value() == 0 ? 0.0 : number.value());
		}
		if (form instanceof LispSymbol symbol) {
			String name = symbol.name();
			if (name.startsWith(":")) {
				return ":" + resolveKeywordSpelling(ctx, name);
			}
			if (name.equals("nil") || name.equals("true") || name.equals("false")) {
				return name;
			}
			return quoted ? "'" + name : null;
		}
		if (form instanceof LispNil) {
			// () is the oracle's EmptyExpr, no constant; quoted it is the empty list
			return quoted ? "()" : null;
		}
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null) {
			return form.print();
		}
		LispVal head = items.get(0);
		if (items.size() == 3 && ClojureLowerUtil.isSymbolNamed(head, ClojureLowerUtil.READER_META)) {
			return quoted ? constantKey(ctx, items.get(1), true, unique) : null;
		}
		if (head == ClojureReader.REGEX || ClojureLowerUtil.isSymbolNamed(head, "%regex")) {
			return "#" + unique[0]++;
		}
		boolean map = ClojureLowerUtil.isSymbolNamed(head, "%hash-map");
		boolean set = ClojureLowerUtil.isSymbolNamed(head, "%hash-set");
		if (head == ClojureReader.VECTOR || map || set) {
			if (!quoted && items.size() == 1) {
				// an empty collection literal is the oracle's EmptyExpr
				return null;
			}
			List<String> members = new ArrayList<>();
			for (int i = 1; i < items.size(); i += map ? 2 : 1) {
				String member = constantKey(ctx, items.get(i), quoted, unique);
				String value = map ? constantKey(ctx, items.get(i + 1), quoted, unique) : "";
				if (member == null || value == null) {
					return null;
				}
				members.add(map ? member + " " + value : member);
			}
			if (map || set) {
				members.sort(null);
				return (map ? "{" : "#{") + String.join(",", members) + "}";
			}
			return "(" + String.join(" ", members) + ")";
		}
		if (head instanceof LispSymbol symbol && symbol.name().startsWith("%")) {
			// a record, tagged or reader-value literal: left to the run-time check
			return null;
		}
		if (!quoted) {
			return items.size() == 2 && ClojureLowerUtil.isSymbolNamed(head, "quote")
					? constantKey(ctx, items.get(1), true, unique) : null;
		}
		List<String> members = new ArrayList<>();
		for (LispVal item : items) {
			String member = constantKey(ctx, item, true, unique);
			if (member == null) {
				return null;
			}
			members.add(member);
		}
		return "(" + String.join(" ", members) + ")";
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
			if (isScalarKeyForm(element)) {
				body.add(setPut(table, element));
				continue;
			}
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, element));
			body.add(setPut(table, one));
		}
		body.add(wrapSet(table));
		return ClojureLowerUtil.letForm(bindings, body);
	}

	static LispVal assocOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 3 && n % 2 == 1, "assoc takes a map and key/value pairs");
		LispSymbol map = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(map, ctx.lower(items.get(1))))),
				assocAnswer(ctx, map, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2))));
	}

	/**
	 * The association over a bound target and a form answering the alternating keys and
	 * values, evaluated once: a vector gets its indexes replaced in a fresh vector
	 * ({@code rontolisp::%clojure-vector-assoc}), anything else a fresh table over the
	 * old pairs plus the new ones, later pairs winning, back in its record ({@code nil}
	 * builds from empty). The run-time {@code vectorp} test is the map path's one extra
	 * step. Every {@code assoc}, {@code update}, {@code update-in} and {@code assoc-in}
	 * lowers through here.
	 */
	static LispVal assocAnswer(ClojureLowering ctx, LispSymbol map, LispVal plist) {
		LispSymbol pairs = ctx.freshTemp();
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(map),
				ClojureProtocolLowering.typedTableOf(map), map);
		LispVal grown = grownTable(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), map, src, ClojureLowering.NIL_CONST), pairs);
		LispVal core = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), map),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-VECTOR-ASSOC"), map, pairs),
				rewrapAnswer(ctx, map, grown));
		// a type implementing Associative takes each pair through its assoc, like the
		// oracle's RT.assoc (an arm a program storing no such row sheds, ClojureArms)
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(IASSOCIATIVE_P), map),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ASSOCIATIVE-ASSOC"), map, pairs), core);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pairs, plist))), typed);
	}

	/**
	 * {@code assoc} as a value: over a map and a rest list of alternating keys and
	 * values, grown in one copy like a call (later pairs winning, onto {@code nil} from
	 * empty, a vector indexed). An odd rest count signals, like a call's pair refusal.
	 */
	static LispVal assocValue(ClojureLowering ctx) {
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("assoc-map"));
		LispSymbol pairs = new LispSymbol(ClojureLowering.mangle("assoc-pairs"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				LispString.literal("assoc takes a map and key/value pairs"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("oddp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), pairs)),
				arity, assocAnswer(ctx, map, pairs));
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
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(map),
						ClojureProtocolLowering.rewrapRecord(map, done), done));
	}

	static LispVal dissocOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "dissoc takes a map and keys");
		LispSymbol map = ctx.freshTemp();
		LispSymbol copy = ctx.freshTemp();
		List<LispVal> body = new ArrayList<>();
		List<LispVal> keys = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			LispVal key = ctx.lower(items.get(i));
			keys.add(key);
			body.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
					lookupKey(storedKey(key, map), copy, isScalarKeyForm(key)), copy));
		}
		body.add(dissocAnswer(ctx, map, copy));
		LispVal lowered = ctx.lower(items.get(1));
		LispVal src = dissocSource(map);
		LispVal rebuilt = ClojureLowerUtil
			.letForm(List.of(ClojureLowerUtil.list(copy, tableFromPlist(tablePlist(src)))), body);
		// a type implementing IPersistentMap drops each key through its without, like the
		// oracle's RT.dissoc (an arm a program storing no such row sheds, ClojureArms); a
		// branch runs the keys, so each runs once
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(IMAP_P), map),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-IMAP-DISSOC"), map,
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys)),
				rebuilt);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(map, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), map, typed, ClojureLowering.NIL_CONST));
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
				ClojureLowerUtil.list(List.of(field, ClojureProtocolLowering.typedFieldsOf(map))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), field, copy, miss), miss),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), keep, ClojureLowering.NIL_CONST)));
		LispVal rewrap = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), keep,
				ClojureProtocolLowering.rewrapRecord(map, copy), copy);
		// a sorted map's survivors go back in its order (a view a program building no
		// sorted collection sheds, ClojureArms)
		LispVal survivors = ClojureSortedLowering.runtime("sorted-shrunk", copy, map);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(keep, ClojureLowering.TRUE_CONST),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(map),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), scan, rewrap), survivors));
	}

	/**
	 * The table {@code dissoc} copies: a record's entry table, a sorted map's entries as
	 * a fresh table (an arm a program building no sorted collection sheds), else the map
	 * itself.
	 */
	static LispVal dissocSource(LispVal map) {
		LispVal sorted = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureSortedLowering.sortedTest(map),
				ClojureSortedLowering.runtime("sorted-table", map, LispString.literal("dissoc")), map);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(map),
				ClojureProtocolLowering.typedTableOf(map), sorted);
	}

	/**
	 * A key looked up in a table of {@code coll}'s entries or members: the key a sorted
	 * {@code coll} stores comparing equal to it, else the key itself (a view a program
	 * building no sorted collection sheds, ClojureArms). It goes inside
	 * {@link #lookupKey}, whose literal-scalar test reads the key form itself.
	 */
	static LispVal storedKey(LispVal key, LispVal coll) {
		return ClojureSortedLowering.runtime("sorted-key", key, coll);
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
		LispVal src = dissocSource(bound);
		LispVal drops = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, keys)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"), lookupKey(storedKey(one, bound), copy, false),
						copy));
		LispVal rebuilt = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(copy, tableFromPlist(tablePlist(src))))), drops,
				dissocAnswer(ctx, bound, copy));
		// a type implementing IPersistentMap drops each key through its without (an arm a
		// program storing no such row sheds, ClojureArms)
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(IMAP_P), bound),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-IMAP-DISSOC"), bound, keys), rebuilt);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, ClojureLowering.AMPERSAND_REST, keys)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(bound, map))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), bound, typed, ClojureLowering.NIL_CONST)));
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
				n == 3 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST, supplied(n == 3));
	}

	/**
	 * Whether a read's default was given, as the form {@link #getBranches} takes: true or
	 * nil.
	 * @param given whether the call spells a default
	 * @return the constant
	 */
	static LispVal supplied(boolean given) {
		return given ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST;
	}

	/**
	 * The table-aware read over already-lowered collection, key and default: a set
	 * answers its member, a map its value, a vector or a string its indexed element,
	 * anything else the default. {@code supplied} answers whether the call gave the
	 * default ({@link #supplied}, or a form over the call's arguments), which picks the
	 * {@code valAt} of a type implementing {@code ILookup}, like the oracle's {@code get}
	 * of two or three arguments.
	 */
	static LispVal getForm(ClojureLowering ctx, LispVal coll, LispVal key, LispVal dflt, LispVal supplied) {
		LispSymbol collSym = ctx.freshTemp();
		LispSymbol keySym = ctx.freshTemp();
		LispSymbol dfltSym = ctx.freshTemp();
		List<LispVal> bindings = List.of(ClojureLowerUtil.list(collSym, coll), ClojureLowerUtil.list(keySym, key),
				ClojureLowerUtil.list(dfltSym, dflt));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"),
						getBranches(ctx, collSym, keySym, dfltSym, isScalarKeyForm(key), supplied)));
	}

	/**
	 * The branches of a table-aware read over an already-bound collection, key and
	 * default: a set answers its member, a map its value, a vector or a string its
	 * indexed element, anything else the default. The three arrive as side-effect-free
	 * forms (bound temporaries, a lambda parameter), so the branches may name them more
	 * than once. {@code scalarKey} says the key is a literal scalar
	 * ({@link #isScalarKeyForm}), read without the structural-key runtime;
	 * {@code supplied} whether the call gave the default ({@link #getForm}).
	 */
	static List<LispVal> getBranches(ClojureLowering ctx, LispVal coll, LispVal key, LispVal dflt, boolean scalarKey,
			LispVal supplied) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isSetForm(coll), ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
				lookupKey(key, setInner(coll), scalarKey), setInner(coll), dflt)));
		// a record reads through its entry table, like a map; a deftype or reify is
		// opaque and falls to the default, like the oracle
		LispVal entries = ClojureProtocolLowering.typedTableOf(coll);
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isRecordForm(coll), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("gethash"), lookupKey(key, entries, scalarKey), entries, dflt)));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), lookupKey(key, coll, scalarKey), coll, dflt)));
		branches.add(ClojureLowerUtil.list(indexForm(coll, key, true),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), coll, key)));
		branches.add(ClojureLowerUtil.list(indexForm(coll, key, false),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("char"), coll, key)));
		// a sorted map or set reads by its comparator (an arm a program building none
		// sheds, ClojureArms)
		branches.add(ClojureLowerUtil.list(ClojureSortedLowering.sortedTest(coll),
				ClojureSortedLowering.runtime("sorted-get", coll, key, dflt)));
		// a reader conditional or tagged literal is an ILookup of its parts (an arm a
		// program making neither sheds, ClojureArms)
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(new LispSymbol(ClojurePredicateLowering.READER_VALUE_P), coll),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-READER-VALUE-GET"), coll, key, dflt)));
		// a deftype or reify implementing ILookup reads through its valAt (an arm a
		// program storing no such row sheds, ClojureArms)
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(LOOKUP_P), coll),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-LOOKUP-GET"), coll, key, dflt, supplied)));
		// past ILookup, the oracle's RT.getFrom: a java.util.Map's get, then an
		// IPersistentSet's (arms of their families, ClojureArms)
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(JMAP_P), coll),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-JMAP-GET"), coll, key, dflt, supplied)));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ISET_P), coll),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ISET-GET"), coll, key, dflt, supplied)));
		// a byte array reads its element at a number key, like the oracle's RT.getFrom
		// of an array (an arm a program making none sheds)
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ClojureBytesLowering.BYTES_P), coll),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-BYTES-GET"), coll, key, dflt)));
		branches.add(hostArm(coll, hostCall("GET", coll, key, dflt)));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, dflt));
		return branches;
	}

	/** The lookup family's test of a type implementing {@code ILookup}. */
	static final String LOOKUP_P = "RONTOLISP::%CLOJURE-LOOKUP-P";

	/**
	 * {@code get} as a value: over a collection and a key, or those plus a default -- the
	 * two call shapes, dispatched on the rest count. Any other count signals, like a
	 * call's arity refusal.
	 */
	static LispVal getValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("get-coll"));
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("get-key"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("get-rest"));
		LispVal two = getForm(ctx, coll, key, ClojureLowering.NIL_CONST, supplied(false));
		LispVal three = getForm(ctx, coll, key, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest),
				supplied(true));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
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
	 * answering {@code T}-or-false. What none of them holds goes to the spliced
	 * {@code %clojure-contains-past}: false of nil, a vector or a string, refused for
	 * anything else like the oracle's.
	 */
	static LispVal containsForm(ClojureLowering ctx, LispVal coll, LispVal key) {
		boolean scalar = isScalarKeyForm(key);
		LispSymbol bound = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> bindings = List.of(ClojureLowerUtil.list(bound, coll), ClojureLowerUtil.list(at, key),
				ClojureLowerUtil.list(miss,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)));
		List<LispVal> branches = new ArrayList<>();
		branches.add(
				ClojureLowerUtil.list(isSetForm(bound),
						ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
												lookupKey(at, setInner(bound), scalar), setInner(bound), miss),
										miss)))));
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isRecordForm(bound),
				ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
										lookupKey(at, ClojureProtocolLowering.typedTableOf(bound), scalar),
										ClojureProtocolLowering.typedTableOf(bound), miss),
								miss)))));
		branches
			.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), bound),
					ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), ClojureLowerUtil
								.list(ClojureLowerUtil.sym("gethash"), lookupKey(at, bound, scalar), bound, miss),
									miss)))));
		branches.add(ClojureLowerUtil.list(indexForm(bound, at, true), ClojureLowering.TRUE_CONST));
		branches.add(ClojureLowerUtil.list(indexForm(bound, at, false), ClojureLowering.TRUE_CONST));
		branches.add(ClojureLowerUtil.list(ClojureSortedLowering.sortedTest(bound),
				ctx.booleanAnswer(ClojureSortedLowering.runtime("sorted-contains", bound, at))));
		// the oracle's RT.contains: an Associative's containsKey, an IPersistentSet's
		// contains, a java.util.Map's containsKey, a java.util.Set's contains (arms of
		// their families, ClojureArms)
		branches
			.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(IASSOCIATIVE_P), bound), ctx.booleanAnswer(
					ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ASSOCIATIVE-CONTAINS"), bound, at))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ISET_P), bound), ctx
			.booleanAnswer(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ISET-CONTAINS"), bound, at))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(JMAP_P), bound), ctx
			.booleanAnswer(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-JMAP-CONTAINS"), bound, at))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(JSET_P), bound), ctx
			.booleanAnswer(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-JSET-CONTAINS"), bound, at))));
		// a byte array holds a number key inside it, like the oracle's RT.contains of an
		// array (an arm a program making none sheds)
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ClojureBytesLowering.BYTES_P), bound),
				ctx.booleanAnswer(
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-BYTES-CONTAINS"), bound, at))));
		branches.add(hostArm(bound, ctx.booleanAnswer(hostCall("CONTAINS-P", bound, at))));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CONTAINS-PAST"), bound, at)));
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
	 * list. The order is the table's walk order, unspecified like the oracle's; a
	 * record's is its declared fields, then the rest.
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
				map);
		LispVal walked = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(acc, ClojureLowering.NIL_CONST))), collect, acc);
		// a record answers its declared fields in order, then its extension keys
		// (%clojure-record-entries), where a table walks in its own order
		LispVal gathered = ClojureLowerUtil.list(
				ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(map), ClojureLowerUtil
					.list(new LispSymbol("RONTOLISP::%CLOJURE-RECORD-COLUMN"), map, new LispInteger(keys ? 0 : 1)),
				walked);
		// a host map answers its entries' keys (values): an arm a program naming no java:
		// operator sheds
		LispVal host = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(HOST_SEQABLE_P), map),
				hostCall("KEYS", map, new LispInteger(keys ? 0 : 1)), gathered);
		// a sorted map answers its keys (vals) in order
		LispVal sorted = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureSortedLowering.sortedTest(map),
				ClojureSortedLowering.runtime("sorted-keys", map, new LispInteger(keys ? 0 : 1)), host);
		// a type implementing IPersistentMap or java.util.Map answers its seq's entries'
		// keys (vals), like the oracle's KeySeq (arms of their families, ClojureArms)
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), ClojureLowerUtil.list(new LispSymbol(IMAP_P), map),
						ClojureLowerUtil.list(new LispSymbol(JMAP_P), map)),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TYPED-KEYS"), map,
						new LispInteger(keys ? 0 : 1)),
				sorted);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(map, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), map, typed, ClojureLowering.NIL_CONST));
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
		LispVal base = ClojureLowering.NIL_CONST;
		for (int i = 1; i < items.size(); i++) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, ctx.lower(items.get(i))));
			present.add(one);
			// a record contributes its entries, like a map; anything opaque signals
			// in the plist walk, like the oracle
			LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(one),
					ClojureProtocolLowering.typedTableOf(one), one);
			if (i == 1) {
				// the first map is copied as is; the later ones join key by key
				base = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, src, ClojureLowering.NIL_CONST);
				continue;
			}
			plists.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, mergedEntriesPlist(one),
					ClojureLowering.NIL_CONST));
		}
		LispVal merged = rewrapAnswer(ctx, present.get(0),
				grownTable(base, ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), plists)));
		// a first map implementing IPersistentCollection takes each later one through its
		// cons, the oracle's conj folded over the maps (an arm a program storing no such
		// row sheds, ClojureArms)
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(ICOLLECTION_P), present.get(0)),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TYPED-MERGE"), present.get(0),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), present.subList(1, present.size()))),
				merged);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), present), typed, ClojureLowering.NIL_CONST));
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
		LispVal onePlist = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, mergedEntriesPlist(one),
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
		// first map (a nil first map answers a plain map), like the oracle; a sorted
		// first map is the base the pairs join, so the answer keeps its order (an arm a
		// program building no sorted collection sheds)
		LispVal first = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), maps);
		LispVal base = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureSortedLowering.sortedMapTest(first),
				first, ClojureLowering.NIL_CONST);
		LispVal merged = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(found, ClojureLowering.NIL_CONST),
						ClojureLowerUtil.list(grown, spread))),
				find, ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), found,
						rewrapAnswer(ctx, first, grownTable(base, grown)), ClojureLowering.NIL_CONST));
		// a first map implementing IPersistentCollection takes each later one through its
		// cons (an arm a program storing no such row sheds, ClojureArms)
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(ICOLLECTION_P), first),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TYPED-MERGE"), first,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), maps)),
				merged);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, maps), typed);
	}

	/**
	 * The entries a later {@code merge} item adds, as a plist. Merge is conj folded over
	 * the maps: a table's pairs are read inline, anything else (a record, a sorted map, a
	 * {@code [k v]} vector, a set or seq of entries) goes through one shared worker
	 * instead of inlining every arm at each item.
	 */
	static LispVal mergedEntriesPlist(LispSymbol item) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), item), tablePlist(item),
				ClojureSortedLowering.runtime("merge-entry-plist", item));
	}

	/**
	 * The entries a map adds, as a plist: a sorted map's in order (an arm a program
	 * building no sorted collection sheds), else its table's ({@code src}, a record's
	 * entry table or the map).
	 */
	static LispVal entriesPlist(LispSymbol map, LispVal src) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureSortedLowering.sortedMapTest(map),
				ClojureSortedLowering.runtime("sorted-plist", map), tablePlist(src));
	}

	static LispVal conjOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			// the oracle's init arity: (transduce xf conj coll) starts from it
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"));
		}
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
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isRecordForm(collSym), rewrapAnswer(ctx, collSym,
				grownTable(ClojureProtocolLowering.typedTableOf(collSym), entryPlist(ctx, item)))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), collSym),
				grownTable(collSym, entryPlist(ctx, item))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), collSym),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("append"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"), collSym,
										ClojureLowerUtil.quoted("list")),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), item)),
						ClojureLowerUtil.quoted("vector"))));
		// a sorted map or set takes the item in its order, before the list arm below
		// (its wrapper is a cons)
		branches.add(ClojureLowerUtil.list(ClojureSortedLowering.sortedTest(collSym),
				ClojureSortedLowering.runtime("sorted-conj", collSym, item)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), ClojureLowerUtil.list(
						ClojureLowerUtil.sym("or"), ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), collSym),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), collSym)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
								ClojureProtocolLowering.isTypedForm(collSym)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureStringLowering.isRegexForm(collSym)),
						// atoms (and refs/agents/volatiles, the same cell) are cons
						// wrappers too, so the oracle signals instead of consing
						// (the regex-guard precedent)
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureStateLowering.isAtomForm(collSym))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), item, collSym)));
		// a type implementing IPersistentCollection takes the item through its cons, like
		// the oracle's RT.conj (an arm a program storing no such row sheds, ClojureArms)
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ICOLLECTION_P), collSym),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-COLLECTION-CONS"), collSym, item)));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals
			.refusal(ClojureRefusals.CLASS_CAST, LispString.literal("conj needs a collection and an item"))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/**
	 * The entries one conjoined item adds to a map, as a plist: none of nil, a map's own
	 * pairs, a two-vector's pair, a set's members each as an entry, a sorted map's pairs
	 * and a seq's members, or a host map's entries. A list of non-entries is none, like
	 * the oracle's.
	 */
	static LispVal entryPlist(ClojureLowering ctx, LispVal item) {
		List<LispVal> branches = new ArrayList<>();
		// a nil item adds nothing, like the oracle
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), item),
				ClojureLowering.NIL_CONST));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), item),
				tablePlist(item)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), item),
						// a string is a vector too, and no entry
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), item)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), item), new LispInteger(2))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), item, new LispInteger(0)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), item, new LispInteger(1)))));
		branches.add(ClojureLowerUtil.list(isSetForm(item), membersPlist(ctx, item)));
		// a sorted map or a seq of entries (the other cons wrappers fall through to the
		// signal inside the worker)
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), item),
				ClojureSortedLowering.runtime("seq-entry-plist", item)));
		// a host map's entries, or a host seq's, each an entry
		branches.add(hostArm(item, hostCall("ENTRY-PLIST", item)));
		branches
			.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.MAP_ENTRY,
					LispString.literal("conj needs a map entry: a map, a [k v] vector or nil"), item)));
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches);
	}

	/**
	 * The entries of a set conjoined onto a map, as a plist: each member is itself an
	 * entry (a two-vector, which a map entry is here), nothing else.
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
	 * One set member's entry as a plist: a two-vector's pair. The oracle casts every
	 * member of a conjoined set to a map entry, so a map, list, set or nil member is
	 * refused (a map entry is a plain two-vector here, so a vector member stays
	 * accepted).
	 */
	static LispVal memberEntryPlist(LispVal key) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), key),
						// a string is a vector too, and no entry
						ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), key)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), key), new LispInteger(2))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), key, new LispInteger(0)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("elt"), key, new LispInteger(1)))));
		branches.add(
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST_OF,
						LispString.literal("conj needs a map entry: a map, a [k v] vector or nil"), key)));
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
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, makeTable()))), copy, setPut(table, item),
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
		// (conj) is [], the oracle's init arity, so (transduce xf conj coll) runs
		LispSymbol supplied = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.sym("&optional"),
						ClojureLowerUtil.list(coll, ClojureLowering.NIL_CONST, supplied),
						ClojureLowering.AMPERSAND_REST, items)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), supplied,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("reduce"), step, items,
								ClojureLowerUtil.sym(":initial-value"), coll),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"))));
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
				setInner(hashedSet(set)));
		List<LispVal> body = new ArrayList<>();
		body.add(ClojureLowerUtil.list(table, makeTable()));
		List<LispVal> members = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			members.add(ctx.lower(items.get(i)));
		}
		LispVal kept = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(body), copy,
				remhashes(members, table, set), shrunkSet(table, set));
		LispVal needSet = ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST, LispString.literal("disj needs a set"));
		// a type implementing IPersistentSet drops each member through its disjoin, like
		// the oracle's disj (an arm a program storing no such row sheds, ClojureArms); a
		// branch runs the members, so each runs once
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(ISET_P), set),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ISET-DISJ"), set,
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), members)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isAnySetForm(set), kept, needSet));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(set, ctx.lower(items.get(1))))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), set, typed, ClojureLowering.NIL_CONST));
	}

	/**
	 * Whether the bound value is a set or a sorted set: the sorted half an arm a program
	 * building no sorted collection sheds (ClojureArms), which leaves {@link #isSetForm}.
	 */
	static LispVal isAnySetForm(LispSymbol set) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), isSetForm(set),
				ClojureSortedLowering.sortedSetTest(set));
	}

	/**
	 * The set {@code disj} copies: a sorted set as a hash set of its members, anything
	 * else itself (a view a program building no sorted collection sheds).
	 */
	static LispVal hashedSet(LispSymbol set) {
		return ClojureSortedLowering.runtime("sorted-hashed", set);
	}

	/**
	 * The set {@code disj} answers over its table: the hash set, or back in the sorted
	 * set's order when it took one (a view a program building no sorted collection
	 * sheds).
	 */
	static LispVal shrunkSet(LispSymbol table, LispSymbol set) {
		return ClojureSortedLowering.runtime("sorted-shrunk", wrapSet(table), set);
	}

	/**
	 * The {@code remhash} of each of the lowered {@code members} from {@code table}, in
	 * order.
	 */
	static LispVal remhashes(List<LispVal> members, LispSymbol table, LispSymbol set) {
		if (members.isEmpty()) {
			return table;
		}
		List<LispVal> drops = new ArrayList<>();
		for (LispVal member : members) {
			drops.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
					lookupKey(storedKey(member, set), table, isScalarKeyForm(member)), table));
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
				setInner(hashedSet(bound)));
		LispVal drops = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(one, members)), ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
						lookupKey(storedKey(one, bound), table, false), table));
		LispVal kept = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, makeTable()))), copy, drops,
				shrunkSet(table, bound));
		LispVal needSet = ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST, LispString.literal("disj needs a set"));
		// a type implementing IPersistentSet drops each member through its disjoin (an
		// arm a
		// program storing no such row sheds, ClojureArms)
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(ISET_P), bound),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ISET-DISJ"), bound, members),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isAnySetForm(bound), kept, needSet));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(set, ClojureLowering.AMPERSAND_REST, members)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(bound, set))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), bound, typed, ClojureLowering.NIL_CONST)));
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
				ClojureLowerUtil.list(
						ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, ClojureLowerUtil
							.list(ClojureLowerUtil.sym("coerce"), coll, ClojureLowerUtil.quoted("list")))),
						setPut(table, one))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
										ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(entry,
												ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), key, val)))),
										setPut(table, entry))),
						coll)));
		branches.add(ClojureLowerUtil.list(isSetForm(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), val)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table), key)),
						setInner(coll))));
		// a seq walks its whole-collection view (a lazy one realizes -- its own cells
		// are no members)
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
						ClojureLowerUtil.list(List.of(one, ClojureSeqLowering.seqAllForm(ctx, coll))),
						setPut(table, one))));
		// a value implementing IReduceInit is what its reduction steps, like the oracle's
		// set (a view a program storing no such row sheds, ClojureArms)
		LispVal members = ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.REDUCE_INIT_ITEMS), lowered);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(coll, members), ClojureLowerUtil.list(table, makeTable()))),
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
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				LispString.literal(what + " takes key/value pairs"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("oddp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), pairs)),
				arity, grownTable(ClojureLowering.NIL_CONST, pairs));
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

	/**
	 * {@code vec} over an already-lowered collection. A value that is no collection is
	 * the oracle's {@code RuntimeException} (it casts to an array before it seqs), a view
	 * of the refusal family, so a program reading no class compiles the bare coercion.
	 */
	static LispVal vecForm(ClojureLowering ctx, LispVal lowered) {
		// a value implementing IReduceInit is what its reduction steps, like the oracle's
		// vec (a view a program storing no such row sheds, ClojureArms)
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REALIZE-ALL"),
						ClojureLowerUtil.list(new LispSymbol(ClojureRefusals.VEC_ARG),
								ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.REDUCE_INIT_ITEMS), lowered))),
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

	/** The tag heading a lazy seq: {@code (LIST :C%LAZY cell)}. */
	private static final LispSymbol LAZY_TAG = new LispSymbol(":C%LAZY");

	/**
	 * The count of an already-lowered collection: tables by entries, else length. A cons
	 * headed by a CL keyword is a tagged wrapper (no user list holds one): a set or a
	 * record counts its entries, a sorted collection or a lazy seq its members, and any
	 * other (a keyword, a var, an atom, a pattern, a deftype ...) is refused like the
	 * oracle's count, so a list or a vector reaches its arm past one test. Anything else
	 * takes {@code length}, whose type error is the oracle's refusal of a number,
	 * {@code false} or a character.
	 */
	static LispVal countForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol coll = ctx.freshTemp();
		LispVal tagged = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), tagOf(coll), SET_TAG),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), setInner(coll))),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), tagOf(coll),
								ClojureProtocolLowering.RECORD_TAG),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"),
								ClojureProtocolLowering.typedTableOf(coll))),
				ClojureLowerUtil.list(ClojureSortedLowering.sortedTest(coll),
						ClojureSortedLowering.runtime("sorted-count", coll)),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), tagOf(coll), LAZY_TAG),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"),
								ClojureSeqLowering.seqAllForm(ctx, coll))),
				// a type implementing Counted counts through its count (an arm a program
				// storing no such row sheds, ClojureArms)
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(COUNTED_P), coll),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-COUNTED-COUNT"), coll)),
				// past it, the oracle's countFrom: an IPersistentCollection walks its
				// seq,
				// a java.util Collection or Map answers its size (arms of their families)
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ICOLLECTION_P), coll),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-COLLECTION-COUNT"), coll)),
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(JCOLLECTION_P), coll),
						ClojureLowerUtil.list(new LispSymbol(JAVA_SIZE), coll)),
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(JMAP_P), coll),
						ClojureLowerUtil.list(new LispSymbol(JAVA_SIZE), coll)),
				// a byte array counts its octets (an arm a program making none sheds)
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ClojureBytesLowering.BYTES_P), coll),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), coll)))),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals
					.refusal(ClojureRefusals.UNSUPPORTED_OPERATION, LispString.literal("count needs a collection"))));
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), coll), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("keywordp"), tagOf(coll)),
					tagged,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), ClojureSeqLowering.seqAllForm(ctx, coll)))));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), coll)));
		branches.add(hostArm(coll, hostCall("COUNT", coll)));
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

	/**
	 * Whether an already-lowered collection is empty, answering raw. A plain list is not;
	 * a set, a record or a sorted collection reads its count; a table, a vector or a
	 * string its size; anything else takes its seq view, so a lazy seq realizes one level
	 * and a value that is no collection (a keyword, {@code false}, a number ...) is
	 * refused as {@code seq} refuses it, like the oracle's {@code (not (seq coll))}.
	 */
	static LispVal emptyForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol coll = ctx.freshTemp();
		LispVal tagged = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), tagOf(coll), SET_TAG),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), setInner(coll)))),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), tagOf(coll),
								ClojureProtocolLowering.RECORD_TAG),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"),
										ClojureProtocolLowering.typedTableOf(coll)))),
				ClojureLowerUtil.list(ClojureSortedLowering.sortedTest(coll),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
								ClojureSortedLowering.runtime("sorted-count", coll))),
				// the oracle's empty? asks counted? first: a type implementing Counted
				// is empty at a zero count (an arm a program storing no such row sheds)
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(COUNTED_P), coll),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
								ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-COUNTED-COUNT"), coll))),
				// a byte array is empty at no octet (an arm a program making none sheds)
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ClojureBytesLowering.BYTES_P), coll),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), coll))))),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), ClojureSeqLowering.seqForm(ctx, coll))));
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("keywordp"), tagOf(coll)), tagged,
						ClojureLowering.NIL_CONST)));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-count"), coll))));
		branches
			.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), coll), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("zerop"), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), coll))));
		branches
			.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), coll), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("zerop"), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), coll))));
		branches.add(hostArm(coll, hostCall("EMPTY-P", coll)));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), ClojureSeqLowering.seqForm(ctx, coll))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(coll, lowered))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/** The counted family's test of a type implementing {@code Counted}. */
	static final String COUNTED_P = "RONTOLISP::%CLOJURE-COUNTED-P";

	/**
	 * The collection-interface family's test of a type implementing
	 * {@code IPersistentCollection}.
	 */
	static final String ICOLLECTION_P = "RONTOLISP::%CLOJURE-ICOLLECTION-P";

	/**
	 * The associative-interface family's test of a type implementing {@code Associative}.
	 */
	static final String IASSOCIATIVE_P = "RONTOLISP::%CLOJURE-IASSOCIATIVE-P";

	/** The map-interface family's test of a type implementing {@code IPersistentMap}. */
	static final String IMAP_P = "RONTOLISP::%CLOJURE-IMAP-P";

	/** The set-interface family's test of a type implementing {@code IPersistentSet}. */
	static final String ISET_P = "RONTOLISP::%CLOJURE-ISET-P";

	/**
	 * The java-collection family's test of a type implementing
	 * {@code java.util.Collection}.
	 */
	static final String JCOLLECTION_P = "RONTOLISP::%CLOJURE-JCOLLECTION-P";

	/** The java-collection family's test of a type implementing {@code java.util.Set}. */
	static final String JSET_P = "RONTOLISP::%CLOJURE-JSET-P";

	/** The java-map family's test of a type implementing {@code java.util.Map}. */
	static final String JMAP_P = "RONTOLISP::%CLOJURE-JMAP-P";

	/** The size of a type implementing a {@code java.util} Collection or Map. */
	static final String JAVA_SIZE = "RONTOLISP::%CLOJURE-JAVA-SIZE";

	/** The tag a wrapper is headed by: {@code (CAR form)}. */
	private static LispVal tagOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form);
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
	 * Two values compared the Clojure way, through the spliced
	 * {@code rontolisp::%clojure-equal}: two wrapped sets by membership both ways
	 * (order-free, deep in the members), two tables (or two records with the same tag)
	 * entry by entry, two sequentials (lists, non-string vectors, lazy seqs, nil as the
	 * empty list) element by element, a deftype or reify by identity, anything else with
	 * {@code equal}. The answer is raw ({@code T} or {@code NIL}).
	 */
	static LispVal equalityTwo(ClojureLowering ctx, LispVal first, LispVal second) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-EQUAL"), first, second);
	}

}
