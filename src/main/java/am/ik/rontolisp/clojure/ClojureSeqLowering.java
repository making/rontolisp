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
 * Sequence forms of the Clojure lowering: the seq view, lazy wrappers, loops and
 * threading.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureSeqLowering {

	private ClojureSeqLowering() {
	}

	static LispVal condOf(ClojureLowering ctx, List<LispVal> items) {
		LispVal out = ClojureLowering.NIL_CONST;
		// an odd trailing arm is the default: it fires whatever came before
		int pairsEnd = items.size() % 2 == 0 ? items.size() - 1 : items.size();
		if (pairsEnd != items.size()) {
			out = ctx.lowerTailSlot(items.get(items.size() - 1));
		}
		for (int i = pairsEnd - 2; i >= 1; i -= 2) {
			out = ctx.ifFalsey(condTest(ctx, items.get(i)), ctx.lowerTailSlot(items.get(i + 1)), out);
		}
		return out;
	}

	static LispVal condTest(ClojureLowering ctx, LispVal test) {
		if (ClojureLowerUtil.isSymbolNamed(test, ":else")) {
			return ClojureLowering.TRUE_CONST;
		}
		return ctx.lower(test);
	}

	/**
	 * {@code when-let}: one binding tested, the body only on truthy. The init runs once
	 * behind a temporary; the pattern destructures from it like {@code let}, so a vector
	 * or map pattern tests the whole init value, like the oracle.
	 */
	static LispVal whenLetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "when-let needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "when-let");
		ClojureLowerUtil.isTrue(bindings.size() == 2, "when-let takes a single binding pair");
		LispSymbol init = ctx.freshTemp();
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		ctx.scopes.add(scope);
		ctx.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			LispVal loweredInit = ctx.lower(bindings.get(1));
			pairs.add(ClojureLowerUtil.list(init, loweredInit));
			Set<String> bound = new HashSet<>(scope.keySet());
			ClojureBindingLowering.destructureInto(ctx, bindings.get(0), init, pairs, scope, "when-let");
			ClojureBindingLowering.noteOrForgetHost(ctx, bindings.get(0), loweredInit, scope.keySet(), bound);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
					ctx.ifFalsey(init, ctx.body(items, 2), ClojureLowering.NIL_CONST));
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
			ClojureBindingLowering.forgetDeepHosts(ctx);
		}
	}

	/**
	 * {@code if-let}: one binding tested, the then or the else branch. Same shape as
	 * {@link #whenLetOf}, with the else defaulting to nil.
	 */
	static LispVal ifLetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3 || items.size() == 4,
				"if-let takes a binding vector, a then and an optional else");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "if-let");
		ClojureLowerUtil.isTrue(bindings.size() == 2, "if-let takes a single binding pair");
		LispSymbol init = ctx.freshTemp();
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		ctx.scopes.add(scope);
		ctx.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			LispVal loweredInit = ctx.lower(bindings.get(1));
			pairs.add(ClojureLowerUtil.list(init, loweredInit));
			Set<String> bound = new HashSet<>(scope.keySet());
			ClojureBindingLowering.destructureInto(ctx, bindings.get(0), init, pairs, scope, "if-let");
			ClojureBindingLowering.noteOrForgetHost(ctx, bindings.get(0), loweredInit, scope.keySet(), bound);
			LispVal then = ctx.lowerTailSlot(items.get(2));
			LispVal els = items.size() == 4 ? ctx.lowerTailSlot(items.get(3)) : ClojureLowering.NIL_CONST;
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
					ctx.ifFalsey(init, then, els));
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
			ClojureBindingLowering.forgetDeepHosts(ctx);
		}
	}

	/** {@code when-not}: the body unless the test is truthy. */
	static LispVal whenNotOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "when-not needs a condition and a body");
		return ctx.ifFalsey(ctx.lower(items.get(1)), ClojureLowering.NIL_CONST, ctx.body(items, 2));
	}

	/** {@code if-not}: the branches swapped, the else defaulting to nil. */
	static LispVal ifNotOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3 || items.size() == 4,
				"if-not takes a condition, a then and an optional else");
		LispVal then = ctx.lowerTailSlot(items.get(2));
		LispVal els = items.size() == 4 ? ctx.lowerTailSlot(items.get(3)) : ClojureLowering.NIL_CONST;
		return ctx.ifFalsey(ctx.lower(items.get(1)), els, then);
	}

	/**
	 * {@code when-first}: the pattern bound to the head of the seq view, the body only
	 * when the collection is non-empty. The seq runs once behind a temporary.
	 */
	static LispVal whenFirstOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "when-first needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "when-first");
		ClojureLowerUtil.isTrue(bindings.size() == 2, "when-first takes a single binding pair");
		LispSymbol seq = ctx.freshTemp();
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		ctx.scopes.add(scope);
		ctx.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			pairs.add(ClojureLowerUtil.list(seq, seqForm(ctx, ctx.lower(bindings.get(1)))));
			Set<String> bound = new HashSet<>(scope.keySet());
			ClojureBindingLowering.destructureInto(ctx, bindings.get(0),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), seq), pairs, scope, "when-first");
			ClojureBindingLowering.forgetHostClasses(ctx, scope.keySet(), bound);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
					ctx.ifFalsey(seq, ctx.body(items, 2), ClojureLowering.NIL_CONST));
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
			ClojureBindingLowering.forgetDeepHosts(ctx);
		}
	}

	/**
	 * Whether the call head is a collection literal used as a function: a vector, map or
	 * set datum in head position, like {@code (#{:h} :h)} or {@code ([1 2] 0)}.
	 */
	static boolean isCollectionHead(LispVal head) {
		if (!(head instanceof LispCons cons)) {
			return false;
		}
		LispVal marker = cons.car();
		return marker == ClojureReader.VECTOR || ClojureLowerUtil.isSymbolNamed(marker, "%hash-map")
				|| ClojureLowerUtil.isSymbolNamed(marker, "%hash-set");
	}

	/**
	 * A collection literal in call position: a set answers its member, a map its value, a
	 * vector its indexed element, each with an optional default. One or two arguments
	 * besides the collection, like the oracle.
	 */
	static LispVal collectionCall(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "a collection as a function takes a key and an optional default");
		LispVal dflt = n == 2 ? ctx.lower(items.get(2)) : ClojureLowering.NIL_CONST;
		List<LispVal> headItems = ClojureLowerUtil.items(items.get(0), List.of());
		if (headItems.isEmpty()) {
			throw new LispReadException("a collection as a function takes a key and an optional default");
		}
		LispVal marker = headItems.get(0);
		if (marker == ClojureReader.VECTOR) {
			List<LispVal> elements = new ArrayList<>();
			for (int i = 1; i < headItems.size(); i++) {
				elements.add(ctx.lower(headItems.get(i)));
			}
			return nthForm(ctx, ClojureLowerUtil.cons(ClojureLowerUtil.sym("vector"), elements),
					ctx.lower(items.get(1)), dflt);
		}
		List<LispVal> lowered = new ArrayList<>();
		for (int i = 1; i < headItems.size(); i++) {
			lowered.add(ctx.lower(headItems.get(i)));
		}
		if (ClojureLowerUtil.isSymbolNamed(marker, "%hash-map")) {
			return ClojureCollectionLowering.getForm(ctx, ClojureCollectionLowering.mapBuild(lowered),
					ctx.lower(items.get(1)), dflt);
		}
		LispSymbol set = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(set, ClojureCollectionLowering.setBuild(ctx, lowered)),
							ClojureLowerUtil.list(key, ctx.lower(items.get(1))))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, ClojureCollectionLowering.setInner(set),
						dflt));
	}

	/**
	 * A collection literal as a function value: a one-or-two-argument lambda over the
	 * same read a call lowers to, so {@code (filter #{:h} ...)} runs. Null when the datum
	 * is no collection literal.
	 * @param datum the function-position datum
	 * @return the lambda, or null
	 */
	static @Nullable LispVal collectionValue(ClojureLowering ctx, LispVal datum) {
		List<LispVal> headItems = ClojureLowerUtil.items(datum);
		if (headItems == null || headItems.isEmpty()) {
			return null;
		}
		LispVal marker = headItems.get(0);
		boolean vector = marker == ClojureReader.VECTOR;
		boolean map = ClojureLowerUtil.isSymbolNamed(marker, "%hash-map");
		boolean set = ClojureLowerUtil.isSymbolNamed(marker, "%hash-set");
		if (!vector && !map && !set) {
			return null;
		}
		List<LispVal> lowered = new ArrayList<>();
		for (int i = 1; i < headItems.size(); i++) {
			lowered.add(ctx.lower(headItems.get(i)));
		}
		LispSymbol arg = new LispSymbol(ClojureLowering.mangle("coll-fn-arg"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("coll-fn-rest"));
		LispVal dflt = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest));
		LispVal read;
		if (vector) {
			read = nthForm(ctx, ClojureLowerUtil.cons(ClojureLowerUtil.sym("vector"), lowered), arg, dflt);
		}
		else if (map) {
			read = ClojureCollectionLowering.getForm(ctx, ClojureCollectionLowering.mapBuild(lowered), arg, dflt);
		}
		else {
			LispSymbol table = ctx.freshTemp();
			read = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.setBuild(ctx, lowered)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), arg,
							ClojureCollectionLowering.setInner(table), dflt));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(arg, ClojureLowering.AMPERSAND_REST, rest)), read);
	}

	/** {@code (and a b ...)} answers the first falsey value or the last value. */
	static LispVal andOf(ClojureLowering ctx, List<LispVal> args) {
		if (args.isEmpty()) {
			return ClojureLowering.TRUE_CONST; // (and) is true
		}
		if (args.size() == 1) {
			return ctx.lowerTailSlot(args.get(0));
		}
		LispSymbol temp = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(temp, ctx.lower(args.get(0)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"), ctx.isFalsey(temp), temp,
						andOf(ctx, args.subList(1, args.size()))));
	}

	/** {@code (or a b ...)} answers the first truthy value or the last value. */
	static LispVal orOf(ClojureLowering ctx, List<LispVal> args) {
		if (args.isEmpty()) {
			return ClojureLowering.NIL_CONST; // (or) is nil
		}
		if (args.size() == 1) {
			return ctx.lowerTailSlot(args.get(0));
		}
		LispSymbol temp = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(temp, ctx.lower(args.get(0)))), ClojureLowerUtil.list(
						ClojureLowerUtil.sym("IF"), ctx.isFalsey(temp), orOf(ctx, args.subList(1, args.size())), temp));
	}

	// clojure.string: each verb over the core string operations

	/**
	 * The seq view of an already-lowered collection: one call to the spliced
	 * {@code rontolisp::%clojure-seq}, which realizes a lazy wrapper one level and
	 * otherwise answers the strict LIST every backend already shares (lists pass through
	 * untouched; vectors and strings coerce; maps contribute one two-vector per entry and
	 * sets one member per element, both in the table's walk order, unspecified like the
	 * oracle's; nil and the false object are empty; anything else signals, like the
	 * oracle's). The collection runs once, as the call's argument. Non-listed verbs
	 * consume one level through this view; only
	 * {@code take}/{@code drop}/{@code first}/{@code rest}/{@code next}/{@code seq}/
	 * {@code map}/{@code filter}/{@code concat}/{@code cons} preserve laziness past it
	 * (b11).
	 * @param lowered the lowered collection
	 * @return the form answering the list view
	 */
	static LispVal seqForm(ClojureLowering ctx, LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ"), lowered);
	}

	/**
	 * {@code nth} over any collection: the seq view indexed, past the end the default
	 * (nil without one) instead of the oracle's throw. The collection and the index run
	 * once each.
	 */
	static LispVal nthForm(ClojureLowering ctx, LispVal coll, LispVal index, LispVal dflt) {
		LispSymbol seq = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(seq, seqForm(ctx, coll)), ClojureLowerUtil.list(at, index))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), at,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), seq)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("nth"), at, seq), dflt));
	}

	static LispVal nthOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "nth takes a collection, an index and an optional default");
		return nthForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)),
				n == 3 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code nth} as a value: a lambda with the Clojure argument order, since a bare
	 * {@code #'NTH} would take the index first.
	 */
	static LispVal nthValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("nth-coll"));
		LispSymbol index = new LispSymbol(ClojureLowering.mangle("nth-index"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(coll, index)),
				nthForm(ctx, coll, index, ClojureLowering.NIL_CONST));
	}

	/**
	 * {@code quot} as a value: a lambda over the primitive, like {@link #nthValue}.
	 */
	static LispVal quotValue(ClojureLowering ctx) {
		LispSymbol first = new LispSymbol(ClojureLowering.mangle("quot-a"));
		LispSymbol second = new LispSymbol(ClojureLowering.mangle("quot-b"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(first, second)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("truncate"), first, second));
	}

	/** {@code seq} as a value: the seq view as a one-argument lambda. */
	static LispVal seqValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("seq-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll), seqForm(ctx, coll));
	}

	/** {@code first} as a value: the head of the seq view. */
	static LispVal firstValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("first-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), seqForm(ctx, coll)));
	}

	/** {@code rest}/{@code next} as a value: the tail of the seq view. */
	static LispVal restValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("rest-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), seqForm(ctx, coll)));
	}

	/** {@code cons} as a value: the item over the collection, lazily when lazy. */
	static LispVal consValue(ClojureLowering ctx) {
		LispSymbol item = new LispSymbol(ClojureLowering.mangle("cons-item"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("cons-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(item, coll)),
				consForm(ctx, item, coll));
	}

	/** {@code map} as a value: over a function and one rest list of collections. */
	static LispVal mapValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("map-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("map-colls"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("map takes a function and collections"));
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), fn, colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls), arity, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/** {@code filter} as a value: the predicate over the collection. */
	static LispVal filterValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("filter-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("filter-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				filterForm(ctx, pred, coll));
	}

	/**
	 * {@code reduce} as a value: over a function and a collection, or a function, a value
	 * and a collection -- the two call shapes, dispatched on the rest count. Any other
	 * count signals, like a call's arity refusal.
	 */
	static LispVal reduceValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("reduce-fn"));
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("reduce-args"));
		LispVal two = reduceForm(ctx, fn, seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)), null);
		LispVal three = reduceForm(ctx, fn,
				seqForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("reduce takes a function, an optional value and a collection"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), two),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						three),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, args)), body);
	}

	/** {@code concat} as a value: every argument appended, lazily when lazy. */
	static LispVal concatValue(ClojureLowering ctx) {
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("concat-colls"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, colls),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CONCAT"), colls));
	}

	/** {@code take} as a value: the strict prefix over the collection. */
	static LispVal takeValue(ClojureLowering ctx) {
		LispSymbol count = new LispSymbol(ClojureLowering.mangle("take-count"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("take-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(count, coll)),
				takeForm(ctx, count, coll));
	}

	/** {@code drop} as a value: the collection past the strict prefix. */
	static LispVal dropValue(ClojureLowering ctx) {
		LispSymbol count = new LispSymbol(ClojureLowering.mangle("drop-count"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("drop-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(count, coll)),
				dropForm(ctx, count, coll));
	}

	/**
	 * {@code repeat} as a value: over a value, or a count and a value -- the two call
	 * shapes, dispatched on the rest count. Any other count signals, like a call's arity
	 * refusal.
	 */
	static LispVal repeatValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("repeat-args"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
		LispVal two = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT-N"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("repeat takes a value, or a count and a value"));
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

	/** {@code cycle} as a value: a one-argument lambda over the cycled seq. */
	static LispVal cycleValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("cycle-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CYCLE"), coll));
	}

	/** {@code iterate} as a value: a two-argument lambda over the iterated seq. */
	static LispVal iterateValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("iterate-fn"));
		LispSymbol start = new LispSymbol(ClojureLowering.mangle("iterate-start"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, start)),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ITERATE"), fun, start));
	}

	/**
	 * {@code repeatedly} as a value: over a function, or a count and a function -- the
	 * two call shapes, dispatched on the rest count. Any other count signals, like a
	 * call's arity refusal.
	 */
	static LispVal repeatedlyValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("repeatedly-args"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
		LispVal two = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY-N"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("repeatedly takes a function, or a count and a function"));
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
	 * {@code range} as a value: the one-, two- and three-argument call shapes over the
	 * rest list. Any other count signals, like a call's arity refusal.
	 */
	static LispVal rangeValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("range-args"));
		LispVal second = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args));
		LispVal third = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("cdr"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispVal one = rangeForm(ctx, new LispInteger(0), ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				new LispInteger(1));
		LispVal two = rangeForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), second,
				new LispInteger(1));
		LispVal three = rangeForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), second, third);
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("range takes an end, or a start, an end and an optional step"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), one),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						two),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)))),
						three),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code apply} over any leading arguments: each but the last passes through, the
	 * last answers its seq view -- CL {@code apply}'s own shape, so
	 * {@code (apply f x args)} spreads like the oracle's.
	 */
	static LispVal applyOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "apply takes a function and an argument list");
		LispVal fun = ClojureBindingLowering.fnValue(ctx, items.get(1));
		List<LispVal> pres = new ArrayList<>();
		for (int i = 2; i < items.size() - 1; i++) {
			pres.add(ctx.lower(items.get(i)));
		}
		LispVal last = seqForm(ctx, ctx.lower(items.get(items.size() - 1)));
		if (ClojureLowerUtil.isDirectFun(fun)) {
			List<LispVal> out = new ArrayList<>();
			out.add(ClojureLowerUtil.sym("apply"));
			out.add(fun);
			out.addAll(pres);
			out.add(last);
			return ClojureLowerUtil.list(out);
		}
		LispSymbol cell = ctx.freshTemp();
		LispVal tail = pres.isEmpty() ? last : ClojureLowerUtil.list(ClojureLowerUtil.sym("append"),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), pres), last);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, fun))), ctx.callableApply(cell, tail));
	}

	/**
	 * {@code map} over an already-lowered function and seq view: direct for real
	 * functions, through the dispatcher for values that may hold collections.
	 */
	/**
	 * {@code map} over an already-lowered function and already-lowered collections (one
	 * or more): one call to the spliced {@code rontolisp::%clojure-map}, which applies
	 * through the IFn dispatcher (real functions and collection values alike) and answers
	 * a lazy wrapper when any input is lazy, the strict list otherwise. Stops at the
	 * shortest input, like the oracle.
	 */
	static LispVal mapForm(ClojureLowering ctx, LispVal fun, List<LispVal> colls) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), fun,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), colls));
	}

	/**
	 * {@code filter} over an already-lowered predicate and collection: one call to the
	 * spliced {@code rontolisp::%clojure-filter}, which tests Clojure truthiness (a false
	 * object drops like nil) and answers a lazy wrapper when the input is lazy, the
	 * strict list otherwise.
	 */
	static LispVal filterForm(ClojureLowering ctx, LispVal fun, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-FILTER"), fun, coll);
	}

	/**
	 * {@code reduce} over an already-lowered function, seq view and optional initial
	 * value: direct for real functions, through the dispatcher otherwise.
	 */
	static LispVal reduceForm(ClojureLowering ctx, LispVal fun, LispVal seq, @Nullable LispVal init) {
		if (ClojureLowerUtil.isDirectFun(fun)) {
			if (init == null) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("reduce"), fun, seq);
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("reduce"), fun, seq,
					ClojureLowerUtil.sym(":initial-value"), init);
		}
		LispSymbol cell = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(acc, one)),
				ctx.callableApply(cell, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), List.of(acc, one))));
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowerUtil.sym("reduce"));
		call.add(step);
		call.add(seq);
		if (init != null) {
			call.add(ClojureLowerUtil.sym(":initial-value"));
			call.add(init);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, fun))), ClojureLowerUtil.list(call));
	}

	/** {@code take}: the first {@code n} of the collection as a strict list. */
	static LispVal takeOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "take takes a count and a collection");
		return takeForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
	}

	/**
	 * The first {@code count} of an already-lowered collection: one call to the spliced
	 * {@code rontolisp::%clojure-take}, which steps through one wrapper at a time, so
	 * {@code (take n infinite)} terminates with a strict prefix.
	 */
	static LispVal takeForm(ClojureLowering ctx, LispVal count, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TAKE"), count, coll);
	}

	/** {@code drop}: the collection past its first {@code n}. */
	static LispVal dropOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "drop takes a count and a collection");
		return dropForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
	}

	/** The already-lowered collection past the first {@code count}. */
	static LispVal dropForm(ClojureLowering ctx, LispVal count, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DROP"), count, coll);
	}

	/**
	 * {@code (lazy-seq body...)}: the body behind a memoized thunk. The body runs at most
	 * once per seq object -- when first realized -- and answers the seq's contents (nil,
	 * a cons, or another collection to seq). The body is its own zero-arity recur target
	 * (the oracle's thunk is a zero-argument function): a {@code recur} in the body's
	 * tail position calls the thunk itself, checked against arity 0, while a
	 * {@code recur} anywhere else is refused by the tail walk first, and a {@code try}
	 * between the {@code recur} and the body trips the barrier the same way. The thunk
	 * lambda wraps itself in a {@code labels} self-binding only when a {@code recur}
	 * reaches it (the anonymous-{@code fn} shape).
	 */
	static LispVal lazySeqOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(ctx.freshRecurName(), true);
		target.setArity(0, false);
		ctx.pushRecurTarget(target);
		try {
			LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of()),
					ctx.bodyTail(items, 1));
			LispVal thunk = target.used() ? ClojureLowering.labelsSelfCall(target.callName(), lambda) : lambda;
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAKE-LAZY"), thunk);
		}
		finally {
			ctx.recurTargets.pop();
		}
	}

	/**
	 * {@code (lazy-cat e...)}: each expression behind its own {@code lazy-seq},
	 * concatenated lazily -- a datum rewrite onto {@code concat}, so chunk-free laziness
	 * holds per member. Of none, nil.
	 */
	static LispVal lazyCatOf(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() == 1) {
			return ClojureLowering.NIL_CONST;
		}
		List<LispVal> form = new ArrayList<>();
		form.add(new LispSymbol("concat"));
		for (int i = 1; i < items.size(); i++) {
			form.add(ClojureLowerUtil.list(new LispSymbol("lazy-seq"), items.get(i)));
		}
		return ctx.lower(ClojureLowerUtil.list(form));
	}

	/**
	 * {@code (repeat x)} (infinite, lazy) or {@code (repeat n x)} (finite, strict like
	 * the oracle's print).
	 */
	static LispVal repeatOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "repeat takes a value, or a count and a value");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT"), ctx.lower(items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT-N"), ctx.lower(items.get(1)),
				ctx.lower(items.get(2)));
	}

	/**
	 * {@code (repeatedly f)} (infinite, lazy) or {@code (repeatedly n f)} (finite, strict
	 * like the oracle's print).
	 */
	static LispVal repeatedlyOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "repeatedly takes a function, or a count and a function");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY"),
					ClojureBindingLowering.fnValue(ctx, items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY-N"), ctx.lower(items.get(1)),
				ClojureBindingLowering.fnValue(ctx, items.get(2)));
	}

	/**
	 * {@code (-> x form...)}: each step with the value inserted second (a bare name calls
	 * with it, a keyword step reads through it); a pure datum rewrite.
	 */
	static LispVal threadFirst(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "-> takes a value and forms to thread it through");
		LispVal acc = items.get(1);
		for (int i = 2; i < items.size(); i++) {
			acc = threadInsert(items.get(i), acc, false);
		}
		return ctx.lower(acc);
	}

	/**
	 * {@code (->> x form...)}: each step with the value appended last; a pure datum
	 * rewrite like {@link #threadFirst}.
	 */
	static LispVal threadLast(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "->> takes a value and forms to thread it through");
		LispVal acc = items.get(1);
		for (int i = 2; i < items.size(); i++) {
			acc = threadInsert(items.get(i), acc, true);
		}
		return ctx.lower(acc);
	}

	/**
	 * One threading step around the threaded datum: a proper list not headed by a reader
	 * marker takes the value second (first) or last; anything else -- a bare name, a
	 * keyword, a literal -- calls or reads with it. A list headed by another list inserts
	 * blindly, like the oracle's purely syntactic rule; a marker-headed literal cannot
	 * take the value and signals when called.
	 */
	static LispVal threadInsert(LispVal form, LispVal acc, boolean last) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		if (parts != null && !parts.isEmpty() && !isThreadAtomHead(parts.get(0))) {
			List<LispVal> out = new ArrayList<>();
			out.add(parts.get(0));
			if (!last) {
				out.add(acc);
			}
			out.addAll(parts.subList(1, parts.size()));
			if (last) {
				out.add(acc);
			}
			return ClojureLowerUtil.list(out);
		}
		return ClojureLowerUtil.list(List.of(form, acc));
	}

	/** A threading step head that must not be inserted into: a reader marker. */
	static boolean isThreadAtomHead(LispVal head) {
		return head instanceof LispSymbol s && s.name().startsWith("%");
	}

	/**
	 * {@code (as-> x name form...)}: nested {@code let}s rebinding the name step by step,
	 * so shadowing matches the oracle exactly.
	 */
	static LispVal threadAs(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "as-> takes a value, a name and forms");
		String name = ClojureLowerUtil.plainName(items.get(2), "as->");
		if (items.size() == 3) {
			return ctx.lower(items.get(1));
		}
		LispVal acc = items.get(items.size() - 1);
		for (int i = items.size() - 2; i >= 3; i--) {
			acc = ClojureBindingLowering.letDatum(name, items.get(i), acc);
		}
		return ctx.lower(ClojureBindingLowering.letDatum(name, items.get(1), acc));
	}

	/**
	 * {@code (doto x form...)}: each step threaded first around one temporary, answering
	 * the original value.
	 */
	static LispVal dotoOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "doto takes a value and forms");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		String temp = "doto-" + ctx.counter++;
		scope.put(temp, ClojureLowering.Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return ctx.inScope(scope, () -> {
			List<LispVal> form = new ArrayList<>();
			form.add(ClojureLowerUtil.sym("let"));
			form.add(ClojureLowerUtil
				.list(List.of(ClojureLowerUtil.list(ClojureLowerUtil.idSym(temp), ctx.lower(items.get(1))))));
			for (int i = 2; i < items.size(); i++) {
				form.add(ctx.lower(threadInsert(items.get(i), ref, false)));
			}
			form.add(ClojureLowerUtil.idSym(temp));
			return ClojureLowerUtil.list(form);
		});
	}

	/**
	 * {@code (cond-> x test form...)} (or {@code cond->>} threading last): each pair
	 * rebinds one temporary to the running value and threads only when its test is
	 * truthy.
	 */
	static LispVal condThread(ClojureLowering ctx, List<LispVal> items, boolean last) {
		String arrow = last ? "cond->>" : "cond->";
		ClojureLowerUtil.isTrue(items.size() >= 2 && items.size() % 2 == 0,
				arrow + " takes a value and test/form pairs");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		String temp = "condthread-" + ctx.counter++;
		scope.put(temp, ClojureLowering.Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return ctx.inScope(scope, () -> {
			LispVal acc = items.get(1);
			for (int i = 2; i + 1 < items.size(); i += 2) {
				acc = ClojureBindingLowering.letDatum(temp, acc, ClojureLowerUtil
					.list(List.of(new LispSymbol("if"), items.get(i), threadInsert(items.get(i + 1), ref, last), ref)));
			}
			return ctx.lower(acc);
		});
	}

	/**
	 * {@code (some-> x form...)} (or {@code some->>} threading last): each step threaded
	 * around one temporary, short-circuiting to nil when it is nil -- but not when it is
	 * false, like the oracle.
	 */
	static LispVal someThread(ClojureLowering ctx, List<LispVal> items, boolean last) {
		String arrow = last ? "some->>" : "some->";
		ClojureLowerUtil.isTrue(items.size() >= 2, arrow + " takes a value and forms");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		String temp = "somethread-" + ctx.counter++;
		scope.put(temp, ClojureLowering.Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return ctx.inScope(scope, () -> {
			LispVal acc = items.get(1);
			for (int i = 2; i < items.size(); i++) {
				acc = ClojureBindingLowering.letDatum(temp, acc,
						ClojureLowerUtil.list(List.of(new LispSymbol("if"),
								ClojureLowerUtil.list(List.of(new LispSymbol("nil?"), ref)), LispNil.INSTANCE,
								threadInsert(items.get(i), ref, last))));
			}
			return ctx.lower(acc);
		});
	}

	/**
	 * {@code list*}: a right fold of {@code cons} over the seq view -- of one argument,
	 * just its seq, signalling for a non-collection like the oracle.
	 */
	static LispVal listStar(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "list* takes a value and more collections");
		if (items.size() == 2) {
			return seqForm(ctx, ctx.lower(items.get(1)));
		}
		LispVal acc = ctx.lower(items.get(items.size() - 1));
		for (int i = items.size() - 2; i >= 1; i--) {
			acc = consForm(ctx, ctx.lower(items.get(i)), acc);
		}
		return acc;
	}

	static LispVal consForm(ClojureLowering ctx, LispVal item, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CONS"), item, coll);
	}

	/**
	 * {@code doseq}: side-effecting iteration over the seq view, answering nil. One
	 * {@code dolist} per binding pair (which the macro expander already shares with every
	 * backend), nested left to right; patterns destructure through the same {@code let}
	 * lowering; {@code :when} skips the element, {@code :while} ends its level's loop
	 * through a block (an outer level's ends the whole form), {@code :let} binds
	 * sequentially. An empty binding vector runs the body once.
	 */
	static LispVal doseqOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "doseq takes a binding vector and a body");
		List<ClojureLowering.SeqLevel> levels = seqLevels(ClojureLowerUtil.bindingItems(items.get(1), "doseq"),
				"doseq");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		for (ClojureLowering.SeqLevel level : levels) {
			collectSeqNames(level, scope);
		}
		return ctx.inScope(scope, () -> {
			// the body answers nil through the loops, never the target: a recur
			// inside one is not in tail position, like the oracle
			LispVal inner = ctx.nonTailBody(items, 2);
			for (int i = levels.size() - 1; i >= 0; i--) {
				inner = seqLevel(ctx, levels.get(i), inner, scope, "doseq");
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), inner, ClojureLowering.NIL_CONST);
		});
	}

	/**
	 * {@code dotimes}: one strict binding over the integers below the count, answering
	 * nil -- the core {@code dotimes} the macro expander already shares. The count runs
	 * through {@code truncate} first, the oracle's {@code intCast} cast in lowering form:
	 * a float counts its truncation ({@code 2.5} runs {@code 0 1}), and a non-number
	 * signals there instead of in the loop's comparison.
	 */
	static LispVal dotimesOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "dotimes takes a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "dotimes");
		ClojureLowerUtil.isTrue(bindings.size() == 2, "dotimes takes exactly one name and count");
		String name = ClojureLowerUtil.plainName(bindings.get(0), "dotimes");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		scope.put(name, ClojureLowering.Kind.VARIABLE);
		LispVal count = ClojureLowerUtil.list(ClojureLowerUtil.sym("truncate"), ctx.lower(bindings.get(1)));
		// the body answers nil through the loop, never the target: a recur inside
		// one is not in tail position, like the oracle
		return ctx.inScope(scope, () -> ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.idSym(name), count)), ctx.nonTailBody(items, 2)));
	}

	/**
	 * {@code for}: a strict list comprehension over the seq view -- nested {@code dolist}
	 * loops accumulating in reverse, like {@code take}'s labels walk, so no backend
	 * learns a representation. Modifiers behave per level, left to right: {@code :when}
	 * skips the element, {@code :while} ends its level's loop (an outer level's ends the
	 * whole comprehension), {@code :let} binds sequentially. Answers the strict list,
	 * {@code nil} when empty (the {@code rest}/{@code take} divergence, not {@code ()}).
	 */
	static LispVal forOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "for takes a binding vector and a body");
		List<ClojureLowering.SeqLevel> levels = seqLevels(ClojureLowerUtil.bindingItems(items.get(1), "for"), "for");
		ClojureLowerUtil.isTrue(!levels.isEmpty(), "for takes at least one binding pair");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		for (ClojureLowering.SeqLevel level : levels) {
			collectSeqNames(level, scope);
		}
		return ctx.inScope(scope, () -> {
			LispSymbol acc = ctx.freshTemp();
			LispVal inner = ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), acc,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), ctx.lower(items.get(2)), acc));
			for (int i = levels.size() - 1; i >= 0; i--) {
				inner = seqLevel(ctx, levels.get(i), inner, scope, "for");
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(acc, ClojureLowering.NIL_CONST))), inner,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc));
		});
	}

	/**
	 * The levels of a {@code doseq}/{@code for} binding vector: pattern/collection pairs,
	 * each trailed by its {@code :when}/{@code :while}/{@code :let} modifiers in order.
	 * Any other keyword is the oracle's {@code Invalid ... keyword} refusal.
	 */
	static List<ClojureLowering.SeqLevel> seqLevels(List<LispVal> bindings, String owner) {
		List<ClojureLowering.SeqLevel> levels = new ArrayList<>();
		int i = 0;
		while (i < bindings.size()) {
			LispVal head = bindings.get(i);
			if (head instanceof LispSymbol keyword && keyword.name().startsWith(":")) {
				if (keyword.name().equals(":when") || keyword.name().equals(":while")
						|| keyword.name().equals(":let")) {
					throw new LispReadException(
							"Invalid '" + owner + "' keyword " + keyword.name() + " without a binding before it");
				}
				throw new LispReadException("Invalid '" + owner + "' keyword " + keyword.name());
			}
			ClojureLowerUtil.isTrue(i + 1 < bindings.size(),
					"a " + owner + " binding vector pairs a name with a value");
			LispVal pattern = head;
			LispVal coll = bindings.get(i + 1);
			i += 2;
			List<ClojureLowering.SeqModifier> modifiers = new ArrayList<>();
			while (i < bindings.size() && bindings.get(i) instanceof LispSymbol trailer
					&& trailer.name().startsWith(":")) {
				String kind = trailer.name();
				ClojureLowerUtil.isTrue(kind.equals(":when") || kind.equals(":while") || kind.equals(":let"),
						"Invalid '" + owner + "' keyword " + kind);
				ClojureLowerUtil.isTrue(i + 1 < bindings.size(), owner + " " + kind + " takes a form after it");
				modifiers.add(new ClojureLowering.SeqModifier(kind, bindings.get(i + 1)));
				i += 2;
			}
			levels.add(new ClojureLowering.SeqLevel(pattern, coll, List.copyOf(modifiers)));
		}
		return levels;
	}

	/**
	 * One binding level wrapped around its inner content: the collection's seq view
	 * iterated by {@code dolist} (patterns through the {@code let} destructuring), the
	 * level's modifiers applied in order around the content. A {@code :while} ends the
	 * level's own loop through a block, so an outer level's ends the whole
	 * {@code doseq}/{@code for} while an inner one's lets the outer loops continue, like
	 * the oracle's.
	 */
	static LispVal seqLevel(ClojureLowering ctx, ClojureLowering.SeqLevel level, LispVal inner,
			Map<String, ClojureLowering.Kind> scope, String owner) {
		LispVal seq = seqForm(ctx, ctx.lower(level.coll()));
		LispVal wrap = inner;
		LispSymbol whileBlock = null;
		for (int m = level.modifiers().size() - 1; m >= 0; m--) {
			ClojureLowering.SeqModifier modifier = level.modifiers().get(m);
			switch (modifier.kind()) {
				case ":when" -> wrap = ctx.ifFalsey(ctx.lower(modifier.datum()), wrap, ClojureLowering.NIL_CONST);
				case ":while" -> {
					if (whileBlock == null) {
						whileBlock = ctx.freshTemp();
					}
					LispSymbol stop = whileBlock;
					wrap = ctx.ifFalsey(ctx.lower(modifier.datum()), wrap, ClojureLowerUtil
						.list(ClojureLowerUtil.sym("return-from"), stop, ClojureLowering.NIL_CONST));
				}
				case ":let" -> wrap = seqLetOf(ctx, modifier.datum(), wrap, scope, owner);
				default -> throw new LispReadException("Invalid '" + owner + "' keyword " + modifier.kind());
			}
		}
		LispVal loopForm = dolistOf(ctx, level.pattern(), seq, wrap, scope, owner);
		if (whileBlock != null) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("block"), whileBlock, loopForm);
		}
		return loopForm;
	}

	/**
	 * One {@code dolist} over an already-lowered seq view: a plain name binds the element
	 * directly, a pattern through the {@code let} destructuring over a temporary.
	 */
	static LispVal dolistOf(ClojureLowering ctx, LispVal pattern, LispVal seq, LispVal wrap,
			Map<String, ClojureLowering.Kind> scope, String owner) {
		if (pattern instanceof LispSymbol) {
			String name = ClojureLowerUtil.plainName(pattern, owner);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.idSym(name), seq)), wrap);
		}
		LispSymbol temp = ctx.freshTemp();
		List<LispVal> pairs = new ArrayList<>();
		ClojureBindingLowering.destructureInto(ctx, pattern, temp, pairs, scope, owner);
		if (pairs.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(temp, seq)),
					wrap);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(temp, seq)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs), wrap));
	}

	/**
	 * A {@code :let} modifier's binding vector around its level's content: sequential
	 * pairs through the {@code let} destructuring, like {@code let} itself.
	 */
	static LispVal seqLetOf(ClojureLowering ctx, LispVal letVector, LispVal wrap,
			Map<String, ClojureLowering.Kind> scope, String owner) {
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(letVector, owner + " :let");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a " + owner + " :let vector pairs a name with a value");
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.size(); i += 2) {
			LispVal pattern = ClojureLowerUtil.stripMeta(bindings.get(i));
			if (pattern instanceof LispSymbol) {
				String name = ClojureLowerUtil.plainName(pattern, owner + " :let");
				pairs.add(ClojureLowerUtil.list(ClojureLowerUtil.idSym(name), ctx.lower(bindings.get(i + 1))));
				scope.put(name, ClojureLowering.Kind.VARIABLE);
				continue;
			}
			LispSymbol temp = ctx.freshTemp();
			pairs.add(ClojureLowerUtil.list(temp, ctx.lower(bindings.get(i + 1))));
			ClojureBindingLowering.destructureInto(ctx, pattern, temp, pairs, scope, owner + " :let");
		}
		if (pairs.isEmpty()) {
			return wrap;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs), wrap);
	}

	/**
	 * Every name a {@code doseq}/{@code for} level binds, registered before anything
	 * lowers: a later collection (or the body) may use an earlier binding, like
	 * {@code let*}'s sequential scope. Lenient by design -- anything malformed stays for
	 * the lowering to refuse with the {@code let} shape.
	 */
	static void collectSeqNames(ClojureLowering.SeqLevel level, Map<String, ClojureLowering.Kind> scope) {
		collectPatternNames(level.pattern(), scope);
		for (ClojureLowering.SeqModifier modifier : level.modifiers()) {
			if (modifier.kind().equals(":let")) {
				collectLetNames(modifier.datum(), scope);
			}
		}
	}

	/**
	 * The names a binding pattern binds, without lowering: a plain name binds directly, a
	 * vector positionally ({@code &} the rest, {@code :as} the whole), a map through
	 * {@code :keys}/{@code :syms}/{@code :strs}, explicit locals, {@code :as} and nested
	 * patterns -- mirroring {@code destructureInto}, which still owns every refusal.
	 */
	static void collectPatternNames(LispVal pattern, Map<String, ClojureLowering.Kind> scope) {
		if (pattern instanceof LispSymbol name) {
			if (!name.name().startsWith(":") && !name.name().equals("&")) {
				scope.put(name.name(), ClojureLowering.Kind.VARIABLE);
			}
			return;
		}
		List<LispVal> elements = ClojureLowerUtil.items(pattern);
		if (elements == null || elements.isEmpty()) {
			return;
		}
		if (elements.get(0) == ClojureReader.VECTOR) {
			List<LispVal> rest = elements.subList(1, elements.size());
			for (int i = 0; i < rest.size(); i++) {
				LispVal element = rest.get(i);
				if (ClojureLowerUtil.isSymbolNamed(element, ":as")) {
					if (i + 1 < rest.size() && rest.get(i + 1) instanceof LispSymbol named
							&& !named.name().startsWith(":") && !named.name().equals("&")) {
						scope.put(named.name(), ClojureLowering.Kind.VARIABLE);
					}
					i++;
					continue;
				}
				if (ClojureLowerUtil.isSymbolNamed(element, "&")) {
					if (i + 1 < rest.size()) {
						collectPatternNames(rest.get(i + 1), scope);
					}
					break;
				}
				collectPatternNames(element, scope);
			}
			return;
		}
		if (ClojureLowerUtil.isSymbolNamed(elements.get(0), "%hash-map")) {
			List<LispVal> entries = elements.subList(1, elements.size());
			for (int i = 0; i + 1 < entries.size(); i += 2) {
				LispVal head = entries.get(i);
				LispVal arg = entries.get(i + 1);
				if (ClojureLowerUtil.isSymbolNamed(head, ":or")) {
					continue;
				}
				if (ClojureLowerUtil.isSymbolNamed(head, ":as")) {
					if (arg instanceof LispSymbol named && !named.name().startsWith(":") && !named.name().equals("&")) {
						scope.put(named.name(), ClojureLowering.Kind.VARIABLE);
					}
					continue;
				}
				if (head instanceof LispSymbol kind && (kind.name().equals(":keys") || kind.name().equals(":syms")
						|| kind.name().equals(":strs"))) {
					collectKeyNames(kind.name(), arg, scope);
					continue;
				}
				if (head instanceof LispSymbol named && !named.name().startsWith(":")) {
					scope.put(named.name(), ClojureLowering.Kind.VARIABLE);
					continue;
				}
				collectPatternNames(head, scope);
			}
		}
	}

	/**
	 * The locals one {@code :keys}/{@code :syms}/{@code :strs} directive binds: each
	 * entry its local (a {@code :keys} entry may qualify, binding the short name) --
	 * mirroring {@code bindKeys}.
	 */
	static void collectKeyNames(String kind, LispVal names, Map<String, ClojureLowering.Kind> scope) {
		List<LispVal> elements = ClojureLowerUtil.items(names);
		if (elements == null || elements.isEmpty() || elements.get(0) != ClojureReader.VECTOR) {
			return;
		}
		for (LispVal element : elements.subList(1, elements.size())) {
			if (element instanceof LispSymbol spelled && !spelled.name().startsWith(":")
					&& !spelled.name().equals("&")) {
				String local = spelled.name();
				if ((kind.equals(":keys") || kind.equals(":syms")) && local.lastIndexOf('/') >= 0) {
					local = local.substring(local.lastIndexOf('/') + 1);
					if (local.isEmpty()) {
						continue;
					}
				}
				scope.put(local, ClojureLowering.Kind.VARIABLE);
			}
		}
	}

	/** The names a {@code :let} modifier's binding vector binds, without lowering. */
	static void collectLetNames(LispVal letVector, Map<String, ClojureLowering.Kind> scope) {
		List<LispVal> found = ClojureLowerUtil.items(letVector);
		if (found == null || found.isEmpty() || found.get(0) != ClojureReader.VECTOR) {
			return;
		}
		List<LispVal> bindings = found.subList(1, found.size());
		for (int i = 0; i + 1 < bindings.size(); i += 2) {
			collectPatternNames(bindings.get(i), scope);
		}
	}

	/**
	 * {@code dorun}: the strict companion of {@code doseq} -- seqs are already strict
	 * lists here, so realizing one is evaluating it; answers nil.
	 */
	static LispVal dorunOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "dorun takes a collection and an optional count");
		if (n == 1) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(1)),
					ClojureLowering.NIL_CONST);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(1)), ctx.lower(items.get(2)),
				ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code doall}: like {@code dorun}, but answers the collection itself (never
	 * coerced: a vector stays a vector, like the oracle's).
	 */
	static LispVal doallOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "doall takes a collection and an optional count");
		if (n == 1) {
			return ctx.lower(items.get(1));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(1)), ctx.lower(items.get(2)));
	}

	/**
	 * {@code dorun} as a value: over one collection (or a count and a collection),
	 * answering nil; any other count signals, like a call's arity refusal.
	 */
	static LispVal dorunValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("dorun-args"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("dorun takes a collection and an optional count"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code doall} as a value: over one collection (or a count and a collection),
	 * answering the collection; any other count signals.
	 */
	static LispVal doallValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("doall-args"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("doall takes a collection and an optional count"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), args)),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code range} with an end: the strict list, built by a labels self call. Without an
	 * end there is no strict lowering -- an infinite seq cannot be spelled -- so it is
	 * refused by name, like {@code lazy-seq}. A zero step signals at run time.
	 */
	static LispVal rangeOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			throw new LispReadException("infinite range is not supported: range needs an end");
		}
		ClojureLowerUtil.isTrue(n >= 1 && n <= 3, "range takes an end, or a start, an end and an optional step");
		LispVal start = n >= 2 ? ctx.lower(items.get(1)) : new LispInteger(0);
		LispVal end = n >= 2 ? ctx.lower(items.get(2)) : ctx.lower(items.get(1));
		LispVal step = n == 3 ? ctx.lower(items.get(3)) : new LispInteger(1);
		return rangeForm(ctx, start, end, step);
	}

	/** The strict list from {@code start} below {@code end} stepping by {@code step}. */
	static LispVal rangeForm(ClojureLowering ctx, LispVal start, LispVal end, LispVal step) {
		String name = ClojureLowering.mangle("range-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol from = ctx.freshTemp();
		LispSymbol to = ctx.freshTemp();
		LispSymbol by = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol grown = ctx.freshTemp();
		LispVal pastEnd = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("plusp"), by),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), at, to),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<="), at, to));
		LispVal advance = ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, by),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), at, grown));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), pastEnd,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), grown), advance);
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(at, grown)), ClojureLowerUtil.cons(body, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(from, start), ClojureLowerUtil.list(to, end),
						ClojureLowerUtil.list(by, step))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"), by),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
								LispString.literal("range step cannot be zero")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
								ClojureLowerUtil.list(self, from, ClojureLowering.NIL_CONST))));
	}

	/**
	 * {@code keep}: the non-nil results of the function over the seq view. {@code false}
	 * is kept (only nil drops), and a signalling function signals --
	 * {@code (keep inc [1 nil 2])} throws, like the oracle, instead of skipping.
	 */
	static LispVal keepForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		LispSymbol fun = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal mapped = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), ctx.callFun(fn, fun, List.of(one))), coll);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remove-if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("null")), mapped));
	}

	/** {@code keep} as a value: a two-argument lambda over the same removal. */
	static LispVal keepValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("keep-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("keep-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				keepForm(ctx, fun, seqForm(ctx, coll)));
	}

	/**
	 * {@code keep-indexed}: like {@code keep}, but the function takes the index and the
	 * item. A labels self call accumulating in reverse, so it stays tail-recursive.
	 */
	static LispVal keepIndexedForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("keep-indexed-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, fun, List.of(at, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, new LispInteger(1)),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest), acc),
								ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, new LispInteger(1)),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), got, acc)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(at, rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, new LispInteger(0), coll, ClojureLowering.NIL_CONST)));
	}

	/** {@code keep-indexed} as a value: a two-argument lambda over the same loop. */
	static LispVal keepIndexedValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("keep-indexed-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("keep-indexed-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				keepIndexedForm(ctx, fun, seqForm(ctx, coll)));
	}

	/**
	 * {@code map-indexed}: the function of index and item over the seq view, strictly.
	 * Same loop as {@link #keepIndexedForm}, keeping every result.
	 */
	static LispVal mapIndexedForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("map-indexed-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, fun, List.of(at, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, new LispInteger(1)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), invoked, acc)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(at, rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, new LispInteger(0), coll, ClojureLowering.NIL_CONST)));
	}

	/** {@code map-indexed} as a value: a two-argument lambda over the same loop. */
	static LispVal mapIndexedValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("map-indexed-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("map-indexed-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				mapIndexedForm(ctx, fun, seqForm(ctx, coll)));
	}

	/**
	 * {@code every?}: true when the predicate holds for every member, answering
	 * {@code T}-or-false directly (empty is true, like the oracle).
	 */
	static LispVal everyForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("every-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil
			.sym("null"), rest), ClojureLowering.TRUE_CONST, ClojureLowerUtil.list(
					ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
							ctx.callFun(fn, fun, List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
							ctx.falseVariable,
							ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code every?} as a value: a two-argument lambda over the same loop. */
	static LispVal everyValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("every-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("every-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				everyForm(ctx, pred, seqForm(ctx, coll)));
	}

	/**
	 * {@code some}: the first truthy predicate result, or nil. The predicate's own value
	 * answers (not the member), like the oracle.
	 */
	static LispVal someForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("some-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
								ctx.callFun(fn, fun,
										List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
								ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest)),
								got)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code some} as a value: a two-argument lambda over the same loop. */
	static LispVal someValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("some-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("some-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				someForm(ctx, pred, seqForm(ctx, coll)));
	}

	/** {@code remove}: the members the predicate rejects, over the seq view. */
	static LispVal removeForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		LispSymbol pred = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoke = ClojureLowerUtil.isDirectFun(fn)
				? ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), pred, one)
				: ctx.callableApply(pred, ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), one));
		LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoke))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remove-if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), test), coll));
	}

	/** {@code remove} as a value: a two-argument lambda over the same removal. */
	static LispVal removeValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("remove-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("remove-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				removeForm(ctx, pred, seqForm(ctx, coll)));
	}

	/**
	 * {@code distinct}: the seq view with later duplicates dropped, first occurrences
	 * kept in order. Membership is {@code equal} (vectors key by identity, like the table
	 * runtime).
	 */
	static LispVal distinctForm(ClojureLowering ctx, LispVal seq) {
		String name = ClojureLowering.mangle("distinct-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol table = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal keep = ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table), one),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), one, acc)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List
							.of(ClojureLowerUtil.list(one, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table, miss), miss),
								keep, ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest), acc))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq, ClojureLowering.NIL_CONST)));
	}

	/** {@code distinct} as a value: a one-argument lambda over the same loop. */
	static LispVal distinctValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("distinct-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				distinctForm(ctx, seqForm(ctx, coll)));
	}

	/** {@code partition}: size, optional step (defaulting to the size), collection. */
	static LispVal partitionOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "partition takes a size, an optional step and a collection");
		LispSymbol size = ctx.freshTemp();
		LispSymbol step = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(size, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(step, n == 3 ? ctx.lower(items.get(2)) : size));
		bindings.add(ClojureLowerUtil.list(coll, seqForm(ctx, ctx.lower(items.get(n)))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				partitionForm(ctx, size, step, coll));
	}

	/**
	 * The partition loop over already-bound size, step and seq: full groups consed, an
	 * incomplete tail dropped, like the oracle. A non-positive size signals.
	 */
	static LispVal partitionForm(ClojureLowering ctx, LispVal size, LispVal step, LispVal seq) {
		String name = ClojureLowering.mangle("partition-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = ctx.freshTemp();
		LispSymbol part = ctx.freshTemp();
		LispVal stepBody = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(part, takeForm(ctx, size, rest)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("<"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), part), size),
								ClojureLowering.NIL_CONST,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), part, ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("nthcdr"), step, rest))))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(stepBody, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<="), size, new LispInteger(0)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
						LispString.literal("partition takes a positive size")),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code partition} as a value: a one- or two-rest lambda over the same loop. */
	static LispVal partitionValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("partition-args"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("partition takes a size, an optional step and a collection"));
		LispVal one = partitionForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), seqForm(ctx, ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))));
		LispVal two = partitionForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
				seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("cdr"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)))));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
								one, arity)),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)))),
						two),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code take-while}: the strict prefix while the predicate stays truthy
	 * ({@code false} stops, like nil).
	 */
	static LispVal takeWhileForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("take-while-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, pred, List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(
								ClojureLowerUtil.sym("or"), ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
								ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest), acc)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq, ClojureLowering.NIL_CONST)));
	}

	/** {@code take-while} as a value: a two-argument lambda over the same loop. */
	static LispVal takeWhileValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("take-while-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("take-while-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				takeWhileForm(ctx, pred, seqForm(ctx, coll)));
	}

	/** {@code drop-while}: the seq view past the truthy prefix, sharing the tail. */
	static LispVal dropWhileForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("drop-while-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, pred, List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil
			.sym("null"), rest), ClojureLowering.NIL_CONST, ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)), rest,
							ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code drop-while} as a value: a two-argument lambda over the same loop. */
	static LispVal dropWhileValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("drop-while-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("drop-while-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				dropWhileForm(ctx, pred, seqForm(ctx, coll)));
	}

	/** {@code interleave}: round-robin over the seq views, stopping at the shortest. */
	static LispVal interleaveOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return ClojureLowering.NIL_CONST;
		}
		List<LispVal> seqs = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			seqs.add(seqForm(ctx, ctx.lower(items.get(i))));
		}
		return interleaveGo(ctx, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), seqs));
	}

	/**
	 * The interleave loop over an already-lowered list of seq views: heads appended while
	 * every view is non-empty.
	 */
	static LispVal interleaveGo(ClojureLowering ctx, LispVal lists) {
		String name = ClojureLowering.mangle("interleave-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = ctx.freshTemp();
		LispVal stop = ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureLowerUtil.list(ClojureLowerUtil.sym("every"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("identity")),
						rest)));
		LispVal step = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("if"), stop, ClojureLowering.NIL_CONST,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("append"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
											ClojureLowerUtil.sym("car")),
									rest),
							ClojureLowerUtil.list(self,
									ClojureLowerUtil.list(
											ClojureLowerUtil.sym("mapcar"), ClojureLowerUtil
												.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("cdr")),
											rest))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
				ClojureLowerUtil.list(self, lists));
	}

	/** {@code interleave} as a value: every argument's seq view interleaved. */
	static LispVal interleaveValue(ClojureLowering ctx) {
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("interleave-colls"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, colls),
				interleaveGo(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"), seqValue(ctx), colls)));
	}

	/**
	 * {@code interpose}: the separator between every two members, strictly. The head
	 * answers bare, so a one-member collection never shows the separator.
	 */
	static LispVal interposeForm(ClojureLowering ctx, LispVal sep, LispVal seq) {
		LispSymbol gap = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal looped = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcan"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), gap, one)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), coll));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(gap, sep), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), coll), ClojureLowering.NIL_CONST,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), coll), looped)));
	}

	/** {@code interpose} as a value: a two-argument lambda over the same shape. */
	static LispVal interposeValue(ClojureLowering ctx) {
		LispSymbol gap = new LispSymbol(ClojureLowering.mangle("interpose-sep"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("interpose-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(gap, coll)),
				interposeForm(ctx, gap, seqForm(ctx, coll)));
	}

	/**
	 * {@code zipmap}: a fresh map pairing each key with its value, stopping at the
	 * shorter side, like the oracle.
	 */
	static LispVal zipmapForm(ClojureLowering ctx, LispVal keys, LispVal vals) {
		String name = ClojureLowering.mangle("zipmap-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol table = ctx.freshTemp();
		LispSymbol keyRest = ctx.freshTemp();
		LispSymbol valRest = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), keyRest),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), valRest)),
				table,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), keyRest), table),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), valRest)),
						ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), keyRest),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), valRest))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(keyRest, valRest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, keys, vals)));
	}

	/** {@code zipmap} as a value: a two-argument lambda over the same loop. */
	static LispVal zipmapValue(ClojureLowering ctx) {
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("zipmap-keys"));
		LispSymbol vals = new LispSymbol(ClojureLowering.mangle("zipmap-vals"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(keys, vals)),
				zipmapForm(ctx, seqForm(ctx, keys), seqForm(ctx, vals)));
	}

	/**
	 * {@code group-by}: a fresh map from each function value to the vector of members
	 * answering it, in encounter order. Members accumulate reversed, then convert.
	 */
	static LispVal groupByForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		LispSymbol fun = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal keyed = ctx.callFun(fn, fun, List.of(one));
		LispVal collect = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(one, seq)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(key, keyed))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), one, ClojureLowerUtil
									.list(ClojureLowerUtil.sym("gethash"), key, table, ClojureLowering.NIL_CONST)))));
		LispVal freeze = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("nreverse"), val),
										ClojureLowerUtil.quoted("vector")))),
				table);
		return ClojureLowerUtil
			.list(ClojureLowerUtil.sym("let*"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn),
							ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
					collect, freeze, table);
	}

	/** {@code group-by} as a value: a two-argument lambda over the same pass. */
	static LispVal groupByValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("group-by-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("group-by-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				groupByForm(ctx, fun, seqForm(ctx, coll)));
	}

	/** {@code sort}: the seq view copied and sorted, with an optional comparator. */
	static LispVal sortOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "sort takes a collection and an optional comparator");
		LispVal seq = seqForm(ctx, ctx.lower(items.get(n)));
		if (n == 1) {
			return sortForm(ctx, seq, null);
		}
		return sortForm(ctx, seq, ClojureBindingLowering.fnValue(ctx, items.get(1)));
	}

	/**
	 * The sort over an already-lowered seq view: a copy (the primitive sorts
	 * destructively) under the default or wrapped comparator.
	 */
	static LispVal sortForm(ClojureLowering ctx, LispVal seq, @Nullable LispVal cmp) {
		LispSymbol coll = ctx.freshTemp();
		LispVal pred;
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(coll, ClojureLowerUtil.list(ClojureLowerUtil.sym("copy-list"), seq)));
		if (cmp == null) {
			pred = defaultCmpFn(ctx);
		}
		else {
			LispSymbol fun = ctx.freshTemp();
			LispSymbol left = ctx.freshTemp();
			LispSymbol right = ctx.freshTemp();
			LispSymbol got = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(fun, cmp));
			LispVal truthy = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(got, ctx.callFun(cmp, fun, List.of(left, right))))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
							ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST));
			pred = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(left, right)),
					truthy);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("sort"), coll, pred));
	}

	/**
	 * The default comparator: numbers with {@code <}, strings with {@code string<},
	 * characters with {@code char<}, keywords by spelling; anything else signals instead
	 * of answering wrongly.
	 */
	static LispVal defaultCmpFn(ClojureLowering ctx) {
		LispSymbol left = new LispSymbol(ClojureLowering.mangle("sort-a"));
		LispSymbol right = new LispSymbol(ClojureLowering.mangle("sort-b"));
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("char<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), keywordTest(left), keywordTest(right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), right))));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil
			.list(ClojureLowerUtil.sym("error"), LispString.literal("sort needs mutually comparable elements"))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(left, right)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/** Whether the bound value is a keyword wrapper. */
	static LispVal keywordTest(LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), value),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), value),
						ClojureCollectionLowering.KEYWORD_TAG));
	}

	/** {@code sort} as a value: a one- or two-argument lambda over the same sort. */
	static LispVal sortValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("sort-args"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("sort takes a collection and an optional comparator"));
		LispVal one = sortForm(ctx, seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)), null);
		LispVal two = sortForm(ctx,
				seqForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
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

	/** {@code sort-by}: the seq view sorted by key, with an optional comparator. */
	static LispVal sortByOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3,
				"sort-by takes a key function, a collection and an optional comparator");
		LispVal coll = seqForm(ctx, ctx.lower(items.get(n)));
		if (n == 2) {
			return sortByForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), coll, null);
		}
		return sortByForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), coll,
				ClojureBindingLowering.fnValue(ctx, items.get(2)));
	}

	/**
	 * The key sort over already-lowered key function, seq view and optional comparator:
	 * the comparator (or the default) runs on the keyed values.
	 */
	static LispVal sortByForm(ClojureLowering ctx, LispVal keyFn, LispVal seq, @Nullable LispVal cmp) {
		LispSymbol key = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol left = ctx.freshTemp();
		LispSymbol right = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(key, keyFn));
		bindings.add(ClojureLowerUtil.list(coll, ClojureLowerUtil.list(ClojureLowerUtil.sym("copy-list"), seq)));
		LispVal keyedLeft = ctx.callFun(keyFn, key, List.of(left));
		LispVal keyedRight = ctx.callFun(keyFn, key, List.of(right));
		LispVal predBody;
		if (cmp == null) {
			predBody = defaultCmpBody(ctx, keyedLeft, keyedRight);
		}
		else {
			LispSymbol fun = ctx.freshTemp();
			LispSymbol got = ctx.freshTemp();
			LispVal invoked = ctx.callFun(cmp, fun, List.of(keyedLeft, keyedRight));
			bindings.add(ClojureLowerUtil.list(fun, cmp));
			predBody = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
							ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST));
		}
		LispVal pred = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(left, right)), predBody);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("sort"), coll, pred));
	}

	/** The default comparison over two already-lowered key forms. */
	static LispVal defaultCmpBody(ClojureLowering ctx, LispVal left, LispVal right) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("char<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), keywordTest(left), keywordTest(right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), right))));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil
			.list(ClojureLowerUtil.sym("error"), LispString.literal("sort needs mutually comparable elements"))));
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches);
	}

	/** {@code sort-by} as a value: key, then one or two more arguments. */
	static LispVal sortByValue(ClojureLowering ctx) {
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("sort-by-key"));
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("sort-by-args"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("sort-by takes a key function, a collection and an optional comparator"));
		LispVal one = sortByForm(ctx, key, seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)),
				null);
		LispVal two = sortByForm(ctx, key,
				seqForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
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
				ClojureLowerUtil.list(List.of(key, ClojureLowering.AMPERSAND_REST, args)), body);
	}

	/** {@code last} as a value: the final member, or nil. */
	static LispVal lastValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("last-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("last"), seqForm(ctx, coll))));
	}

	/** {@code butlast} as a value: everything but the final member. */
	static LispVal butlastValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("butlast-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("butlast"), seqForm(ctx, coll)));
	}

	/** {@code second} as a value: the member past the head, or nil. */
	static LispVal secondValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("second-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), seqForm(ctx, coll)));
	}

	// b18 core convenience fns: strict vectors, head pairs, names, randomness

	/**
	 * {@code mapv} over an already-lowered function and collections (one or more): one
	 * call to the spliced {@code rontolisp::%clojure-mapv}, which realizes every input
	 * fully (lazy inputs answer strictly too) and coerces to a vector, like the oracle.
	 */
	static LispVal mapvOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 2, "mapv takes a function and collections");
		return mapvForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), ctx.lowers(items, 2));
	}

	/** {@code mapv} over an already-lowered function and collections. */
	static LispVal mapvForm(ClojureLowering ctx, LispVal fun, List<LispVal> colls) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), fun,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), colls));
	}

	/** {@code mapv} as a value: over a function and one rest list of collections. */
	static LispVal mapvValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("mapv-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("mapv-colls"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("mapv takes a function and collections"));
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), fn, colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls), arity, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code filterv} over an already-lowered predicate and collection: one call to the
	 * spliced {@code rontolisp::%clojure-filterv}, the strict vector arm of
	 * {@code filter}.
	 */
	static LispVal filtervForm(ClojureLowering ctx, LispVal fun, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-FILTERV"), fun, coll);
	}

	/** {@code filterv} as a value: the predicate over the collection. */
	static LispVal filtervValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("filterv-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("filterv-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				filtervForm(ctx, pred, coll));
	}

	/**
	 * {@code mapcat} over an already-lowered function and collections (one or more): one
	 * call to the spliced {@code rontolisp::%clojure-mapcat}, the strict concat-of-maps
	 * over the seq views (nil-safe, like {@code concat}). A lone function is the oracle's
	 * transducer shape, which stays refused.
	 */
	static LispVal mapcatOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "mapcat takes a function and collections");
		if (n == 1) {
			throw new LispReadException("transducers are not supported yet: mapcat");
		}
		return mapcatForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), ctx.lowers(items, 2));
	}

	/** {@code mapcat} over an already-lowered function and collections. */
	static LispVal mapcatForm(ClojureLowering ctx, LispVal fun, List<LispVal> colls) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPCAT"), fun,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), colls));
	}

	/** {@code mapcat} as a value: over a function and one rest list of collections. */
	static LispVal mapcatValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("mapcat-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("mapcat-colls"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("mapcat takes a function and collections"));
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPCAT"), fn, colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls), arity, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code ffirst} over an already-lowered seq view: the head of the head, each level
	 * through the view (so a vector head seqs before its own head is read).
	 */
	static LispVal ffirstForm(ClojureLowering ctx, LispVal seq) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
				seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), seq)));
	}

	/** {@code ffirst} as a value: a one-argument lambda over the same heads. */
	static LispVal ffirstValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("ffirst-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ffirstForm(ctx, seqForm(ctx, coll)));
	}

	/**
	 * {@code nfirst} over an already-lowered seq view: the tail of the head, each level
	 * through the view (of empty, nil -- the {@code next} shape, not {@code rest}).
	 */
	static LispVal nfirstForm(ClojureLowering ctx, LispVal seq) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
				seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), seq)));
	}

	/** {@code nfirst} as a value: a one-argument lambda over the same tail. */
	static LispVal nfirstValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("nfirst-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				nfirstForm(ctx, seqForm(ctx, coll)));
	}

}
