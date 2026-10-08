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
		return testedBinding(ctx, items, "when-let", false, true);
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
		return testedBinding(ctx, items, "if-let", false, false);
	}

	/**
	 * {@code when-some}: {@link #whenLetOf} tested against nil only, so {@code false}
	 * binds and runs the body. Refusals in the oracle's words.
	 */
	static LispVal whenSomeOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/when-some");
		someBindings(items.get(1), "when-some");
		return testedBinding(ctx, items, "when-some", true, true);
	}

	/**
	 * {@code if-some}: {@link #ifLetOf} tested against nil only, so {@code false} binds
	 * and takes the then branch. Refusals in the oracle's words.
	 */
	static LispVal ifSomeOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/if-some");
		someBindings(items.get(1), "if-some");
		ClojureLowerUtil.isTrue(items.size() <= 4, "if-some requires 1 or 2 forms after binding vector");
		return testedBinding(ctx, items, "if-some", true, false);
	}

	private static void someBindings(LispVal vector, String what) {
		List<LispVal> parts = ClojureLowerUtil.items(vector, List.of());
		ClojureLowerUtil.isTrue(!parts.isEmpty() && parts.get(0) == ClojureReader.VECTOR,
				what + " requires a vector for its binding");
		ClojureLowerUtil.isTrue(parts.size() == 3, what + " requires exactly 2 forms in binding vector");
	}

	/**
	 * One tested binding: the init bound once to a temporary, then the pattern
	 * destructured from it inside the taken branch only, so a failing init destructures
	 * nothing and the else branch sees the names as they were outside, like the oracle's
	 * expansion.
	 * @param ctx the hub
	 * @param items the form's items
	 * @param what the form's name, for refusals
	 * @param nilOnly whether only nil fails the test ({@code if-some}, {@code when-some})
	 * rather than nil and false
	 * @param body whether the rest is a body ({@code when-}) rather than a then and an
	 * optional else ({@code if-})
	 * @return the lowered form
	 */
	private static LispVal testedBinding(ClojureLowering ctx, List<LispVal> items, String what, boolean nilOnly,
			boolean body) {
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), what);
		LispSymbol init = ctx.freshTemp();
		LispVal loweredInit = ctx.lower(bindings.get(1));
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		ctx.scopes.add(scope);
		ctx.directScopes.add(new HashSet<>());
		LispVal then;
		try {
			List<LispVal> pairs = new ArrayList<>();
			Set<String> bound = new HashSet<>(scope.keySet());
			ClojureBindingLowering.destructureInto(ctx, bindings.get(0), init, pairs, scope, what);
			ClojureBindingLowering.noteOrForgetHost(ctx, bindings.get(0), loweredInit, scope.keySet(), bound);
			LispVal taken = body ? ctx.body(items, 2) : ctx.lowerTailSlot(items.get(2));
			then = pairs.isEmpty() ? taken
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs), taken);
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
			ClojureBindingLowering.forgetDeepHosts(ctx);
		}
		LispVal els = !body && items.size() == 4 ? ctx.lowerTailSlot(items.get(3)) : ClojureLowering.NIL_CONST;
		LispVal failed = nilOnly ? ClojureLowerUtil.list(ClojureLowerUtil.sym("NULL"), init) : ctx.isFalsey(init);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(init, loweredInit)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"), failed, els, then));
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
					ctx.lower(items.get(1)), dflt, ClojureCollectionLowering.supplied(n == 2));
		}
		LispSymbol set = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispVal keyForm = ctx.lower(items.get(1));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(set, ClojureCollectionLowering.setBuild(ctx, lowered)),
							ClojureLowerUtil.list(key, keyForm))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
						ClojureCollectionLowering.lookupKey(key, ClojureCollectionLowering.setInner(set),
								ClojureCollectionLowering.isScalarKeyForm(keyForm)),
						ClojureCollectionLowering.setInner(set), dflt));
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
			read = ClojureCollectionLowering.getForm(ctx, ClojureCollectionLowering.mapBuild(lowered), arg, dflt, rest);
		}
		else {
			LispSymbol table = ctx.freshTemp();
			read = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.setBuild(ctx, lowered)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
							ClojureCollectionLowering.lookupKey(arg, ClojureCollectionLowering.setInner(table)),
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
	 * The one-level seq view of an already-lowered collection: one call to the spliced
	 * {@code rontolisp::%clojure-seq}, which realizes a lazy wrapper one level and
	 * otherwise answers the strict LIST every backend already shares (lists pass through
	 * untouched; vectors and strings coerce; maps contribute one two-vector per entry and
	 * sets one member per element, both in the table's walk order, unspecified like the
	 * oracle's; nil and the false object are empty; anything else signals, like the
	 * oracle's). The collection runs once, as the call's argument. A realized lazy seq's
	 * tail is another wrapper, so only a consumer that reads the head
	 * ({@code first}/{@code seq}/{@code when-first}) or steps on with
	 * {@code %clojure-seq-rest} ({@code some}, {@code take-while}, {@code doseq}, ...)
	 * takes this view; every consumer that walks the list with a Common Lisp list
	 * operation takes {@link #seqAllForm}.
	 * @param lowered the lowered collection
	 * @return the form answering the list view
	 */
	static LispVal seqForm(ClojureLowering ctx, LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ"), lowered);
	}

	/**
	 * The whole-collection view of an already-lowered collection: one call to the spliced
	 * {@code rontolisp::%clojure-seq-all}, the {@link #seqForm} view with every lazy tail
	 * realized -- the view itself when its spine holds no wrapper, so a strict input is
	 * never copied. The consumers that walk the list with Common Lisp list operations
	 * ({@code count}, {@code last}, {@code sort}, {@code apply}, ...) take it; an
	 * infinite input never answers, like the oracle's.
	 * @param lowered the lowered collection
	 * @return the form answering the realized list view
	 */
	static LispVal seqAllForm(ClojureLowering ctx, LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ-ALL"), lowered);
	}

	/**
	 * The view of a collection a verb the oracle builds on {@code reduce} walks as a list
	 * ({@code clojure.lisp}, "CollReduce and IKVReduce"): a record, deftype or reify with
	 * its own {@code CollReduce} row answers what that reduction steps, anything else
	 * itself -- an arm a program storing no such row sheds
	 * ({@link ClojureArms.Family#REDUCIBLE}).
	 */
	static final String REDUCIBLE_ITEMS = "RONTOLISP::%CLOJURE-REDUCIBLE-ITEMS";

	/**
	 * The whole-collection view of an already-lowered collection a verb the oracle builds
	 * on {@code reduce} walks ({@code group-by}, {@code frequencies}): the
	 * {@link #REDUCIBLE_ITEMS} view under {@link #seqAllForm}.
	 * @param lowered the lowered collection
	 * @return the form answering the realized list view
	 */
	static LispVal reducedAllForm(ClojureLowering ctx, LispVal lowered) {
		return seqAllForm(ctx, reducibleItemsForm(lowered));
	}

	/**
	 * An already-lowered collection behind the {@link #REDUCIBLE_ITEMS} view: what an
	 * {@code eduction} steps, which the oracle reduces.
	 * @param lowered the lowered collection
	 * @return the view
	 */
	static LispVal reducibleItemsForm(LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol(REDUCIBLE_ITEMS), lowered);
	}

	/**
	 * The step past the head of an already-realized seq: {@code %clojure-seq-rest},
	 * realizing the tail one level when it is a lazy wrapper (a strict tail is already a
	 * seq). The loops over a {@link #seqForm} view step with it instead of {@code cdr}.
	 */
	static LispVal seqRestForm(LispVal seq) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ-REST"), seq);
	}

	/**
	 * {@code nth} over any collection: the spliced {@code %clojure-nth} -- a vector or
	 * string indexed directly, a list, lazy seq or host object stepped through one
	 * realized level at a time, so an infinite input answers, anything else refused like
	 * the oracle's -- past either end the default (nil without one) instead of the
	 * oracle's throw. The collection, the index and the default run once each, in order.
	 */
	static LispVal nthForm(ClojureLowering ctx, LispVal coll, LispVal index, LispVal dflt) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NTH"), coll, index, dflt);
	}

	/**
	 * {@code second} over an already-lowered collection: the spliced
	 * {@code %clojure-seq-nth} at index 1, stepping through the seq view as
	 * {@link #nthForm} does but over every seqable collection (a map or a set too, which
	 * {@code nth} refuses) and refusing the rest as {@code seq} does.
	 */
	static LispVal secondForm(LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ-NTH"), coll, new LispInteger(1),
				ClojureLowering.NIL_CONST);
	}

	static LispVal nthOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "nth takes a collection, an index and an optional default");
		if (n == 2) {
			return nthTwoForm(ctx.lower(items.get(1)), ctx.lower(items.get(2)));
		}
		return nthForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
	}

	/**
	 * {@code nth} of two arguments: {@link #nthForm} with a nil default, but a type
	 * implementing {@code Indexed} answers its {@code nth} over the index alone, like the
	 * oracle's ({@code %clojure-nth-2}, which a program storing no such row calls as
	 * {@code %clojure-nth}, ClojureArms).
	 */
	private static LispVal nthTwoForm(LispVal coll, LispVal index) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NTH-2"), coll, index,
				ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code nth} as a value: a lambda with the Clojure argument order, since a bare
	 * {@code #'NTH} would take the index first.
	 */
	static LispVal nthValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("nth-coll"));
		LispSymbol index = new LispSymbol(ClojureLowering.mangle("nth-index"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(coll, index)),
				nthTwoForm(coll, index));
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
				ClojureLoopLowering.consForm(ctx, item, coll));
	}

	/**
	 * {@code map} as a value: over a function and one rest list of collections; of the
	 * function alone, the transducer.
	 */
	static LispVal mapValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("map-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("map-colls"));
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), ClojureLowering.realFun(fn),
				colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-XF-MAP"), ClojureLowering.realFun(fn)), call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/** {@code filter} as a value: the predicate over the collection. */
	static LispVal filterValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("filter-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("filter-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				filterForm(ctx, ClojureLowering.realFun(pred), coll));
	}

	/**
	 * {@code reduce} as a value: over a function and a collection, or a function, a value
	 * and a collection -- the two call shapes, dispatched on the rest count. Any other
	 * count signals, like a call's arity refusal.
	 */
	static LispVal reduceValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("reduce-fn"));
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("reduce-args"));
		LispVal two = reduceForm(ctx, ClojureLowering.realFun(fn),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), null);
		LispVal three = reduceForm(ctx, ClojureLowering.realFun(fn),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
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
	 * {@code apply} over any leading arguments: each but the last passes through, the
	 * last answers its whole-collection view (a lazy seq realizes) -- CL {@code apply}'s
	 * own shape, so {@code (apply f x args)} spreads like the oracle's.
	 */
	static LispVal applyOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "apply takes a function and an argument list");
		LispVal fun = ClojureBindingLowering.fnValue(ctx, items.get(1));
		List<LispVal> pres = new ArrayList<>();
		for (int i = 2; i < items.size() - 1; i++) {
			pres.add(ctx.lower(items.get(i)));
		}
		LispVal last = seqAllForm(ctx, ctx.lower(items.get(items.size() - 1)));
		if (ClojureBindingLowering.holdsRealFun(ctx, items.get(1), fun)) {
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
		// a type implementing IFn is applied through its applyTo, like the oracle's apply
		// (a view a program storing no such row sheds, ClojureArms)
		LispVal applied = ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.APPLIED_FN), fun);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, applied))), ctx.callableApply(cell, tail));
	}

	/**
	 * {@code map} over an already-lowered function and seq view: direct for real
	 * functions, through the dispatcher for values that may hold collections.
	 */
	/**
	 * {@code map} over an already-lowered real function ({@link ClojureLowering#realFun})
	 * and already-lowered collections (one or more): one call to the spliced
	 * {@code rontolisp::%clojure-map}, which applies it and answers a lazy wrapper when
	 * any input is lazy, the strict list otherwise. Stops at the shortest input, like the
	 * oracle.
	 */
	static LispVal mapForm(ClojureLowering ctx, LispVal fun, List<LispVal> colls) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), fun,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), colls));
	}

	/**
	 * {@code filter} over an already-lowered real predicate
	 * ({@link ClojureLowering#realFun}) and collection: one call to the spliced
	 * {@code rontolisp::%clojure-filter}, which tests Clojure truthiness (a false object
	 * drops like nil) and answers a lazy wrapper when the input is lazy, the strict list
	 * otherwise.
	 */
	static LispVal filterForm(ClojureLowering ctx, LispVal fun, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-FILTER"), fun, coll);
	}

	/**
	 * {@code reduce} over an already-lowered function, collection and optional initial
	 * value: one call to the spliced {@code rontolisp::%clojure-reduce} (2-arity) or
	 * {@code -reduce-init} (3-arity), which walk the lazy-aware seq view one element at a
	 * time (so a lazy input reduces whole) and stop at a {@code reduced} answer, like the
	 * oracle. Arguments evaluate in the Clojure order: function, value, collection. The
	 * function is a real one the runtime funcalls ({@link ClojureLowering#realFun}).
	 */
	static LispVal reduceForm(ClojureLowering ctx, LispVal fun, LispVal coll, @Nullable LispVal init) {
		return init == null ? ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REDUCE"), fun, coll)
				: ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REDUCE-INIT"), fun, init, coll);
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

}
