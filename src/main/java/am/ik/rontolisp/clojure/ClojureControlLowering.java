package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Control forms of the Clojure lowering: {@code case}, {@code condp}, {@code while} and
 * {@code locking}.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureControlLowering {

	private ClojureControlLowering() {
	}

	/** The spliced worker answering the lock a {@code locking} form holds. */
	static final LispSymbol MONITOR = new LispSymbol("RONTOLISP::%CLOJURE-MONITOR");

	/**
	 * {@code (case expr test result... default?)}: the value bound once, then one test
	 * per clause in order. A test constant is never evaluated; a list of constants is the
	 * clause's alternatives. Each constant compares by its kind, decided at lower time
	 * (the oracle's hash-then-{@code =} dispatch): {@code eql} for a number or character
	 * (so {@code 1} never matches {@code 1.0}, {@code -0.0} never {@code 0.0}, and
	 * {@code ##NaN} nothing), {@code equal} for a string or keyword, {@code eq} for a
	 * symbol, {@code nil}, {@code true} and {@code false}, and {@code %clojure-equal} for
	 * a collection, which compares a vector constant equal to a list or lazy seq like
	 * {@code =}. A constant met twice is the oracle's compile-time refusal; no matching
	 * clause without a default its {@code IllegalArgumentException}.
	 */
	static LispVal caseOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/case");
		LispVal value = ctx.lower(items.get(1));
		LispSymbol temp = ctx.freshTemp();
		List<LispVal> clauses = items.subList(2, items.size());
		int pairsEnd = clauses.size() - clauses.size() % 2;
		Set<String> seen = new HashSet<>();
		List<LispVal> tests = new ArrayList<>();
		List<LispVal> results = new ArrayList<>();
		for (int i = 0; i < pairsEnd; i += 2) {
			LispVal test = ClojureLowerUtil.stripMeta(clauses.get(i));
			List<LispVal> alternatives = alternatives(test);
			List<LispVal> arms = new ArrayList<>();
			for (LispVal constant : alternatives) {
				// NaN is no duplicate of itself: the oracle's = of it is false
				boolean nan = ClojureLowerUtil.stripMeta(constant) instanceof LispDouble d && Double.isNaN(d.value());
				if (!nan && !seen.add(caseKey(ctx, constant))) {
					throw new LispReadException("Duplicate case test constant: " + strSpelling(constant));
				}
				LispVal arm = constantTest(ctx, temp, ClojureLowerUtil.stripMeta(constant));
				if (arm != null) {
					arms.add(arm);
				}
			}
			tests.add(arms.isEmpty() ? ClojureLowering.NIL_CONST
					: arms.size() == 1 ? arms.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), arms));
			results.add(ctx.lowerTailSlot(clauses.get(i + 1)));
		}
		LispVal out = pairsEnd < clauses.size() ? ctx.lowerTailSlot(clauses.get(pairsEnd))
				: noMatchingClause(ctx, temp);
		for (int i = tests.size() - 1; i >= 0; i--) {
			out = ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"), tests.get(i), results.get(i), out);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(temp, value)), out);
	}

	/**
	 * A clause's test constants: a list datum's members, anything else itself. An empty
	 * list lists none, which the oracle refuses when it compiles (as an arity error of
	 * the {@code max} it takes over the alternatives' hashes).
	 */
	private static List<LispVal> alternatives(LispVal test) {
		if (test instanceof LispNil) {
			throw new LispReadException("case test constant () lists no alternatives");
		}
		if (!isListDatum(test)) {
			return List.of(test);
		}
		return ClojureLowerUtil.items(test, List.of());
	}

	/**
	 * Whether the datum is a list form, not a vector, map, set or other reader construct.
	 */
	private static boolean isListDatum(LispVal datum) {
		if (!(datum instanceof LispCons cons)) {
			return false;
		}
		LispVal head = cons.car();
		return head != ClojureReader.VECTOR && head != ClojureReader.REGEX
				&& !(head instanceof LispSymbol s && s.name().startsWith("%"));
	}

	/**
	 * The test of one constant against the bound value, or null for {@code ##NaN}, which
	 * matches nothing (the oracle's {@code =} of NaN is false).
	 */
	private static @Nullable LispVal constantTest(ClojureLowering ctx, LispSymbol temp, LispVal constant) {
		if (constant instanceof LispSymbol s) {
			String name = s.name();
			if (name.equals("nil")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("NULL"), temp);
			}
			if (name.equals("true")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), temp, ClojureLowering.TRUE_CONST);
			}
			if (name.equals("false")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), temp, ctx.falseVariable);
			}
			if (name.startsWith(":")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("EQUAL"), temp, ctx.quote(constant));
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), temp, ctx.quote(constant));
		}
		if (constant instanceof LispDouble d && Double.isNaN(d.value())) {
			return null;
		}
		if (constant instanceof LispInteger || constant instanceof LispBigInteger || constant instanceof LispRatio
				|| constant instanceof LispDouble || constant instanceof LispChar) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("EQL"), temp, constant);
		}
		if (constant instanceof LispString) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("EQUAL"), temp, constant);
		}
		if (constant instanceof LispNil) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("NULL"), temp);
		}
		return ClojureCollectionLowering.equalityTwo(ctx, temp, ctx.quote(constant));
	}

	/**
	 * A test constant's identity for the duplicate check: two constants the oracle's
	 * {@code case} would take for one (its {@code =}, so a list and a vector of the same
	 * members, and a map or set whatever its literal order) share it.
	 */
	private static String caseKey(ClojureLowering ctx, LispVal datum) {
		datum = ClojureLowerUtil.stripMeta(datum);
		if (datum instanceof LispSymbol s) {
			String name = s.name();
			if (name.equals("nil") || name.equals("true") || name.equals("false")) {
				return name;
			}
			if (name.startsWith(":")) {
				return "k" + ClojureCollectionLowering.resolveKeywordSpelling(ctx, name);
			}
			return "y" + name;
		}
		if (datum instanceof LispString str) {
			return "s" + str.value();
		}
		if (datum instanceof LispChar c) {
			return "c" + c.codePoint();
		}
		if (datum instanceof LispDouble d) {
			// -0.0 is 0.0 to the duplicate check (the oracle's =), though a -0.0
			// value never matches a 0.0 constant (its hash differs)
			return "d" + (d.value() == 0.0 ? 0.0 : d.value());
		}
		if (datum instanceof LispNil) {
			return "nil";
		}
		if (datum instanceof LispCons cons) {
			List<LispVal> items = ClojureLowerUtil.items(datum, List.of());
			LispVal head = cons.car();
			if (ClojureLowerUtil.isSymbolNamed(head, "%hash-map") && items.size() % 2 == 1) {
				Set<String> entries = new TreeSet<>();
				for (int i = 1; i + 1 < items.size(); i += 2) {
					entries.add(caseKey(ctx, items.get(i)) + "=" + caseKey(ctx, items.get(i + 1)));
				}
				return "{" + String.join(" ", entries) + "}";
			}
			if (ClojureLowerUtil.isSymbolNamed(head, "%hash-set")) {
				Set<String> members = new TreeSet<>();
				for (LispVal member : items.subList(1, items.size())) {
					members.add(caseKey(ctx, member));
				}
				return "#{" + String.join(" ", members) + "}";
			}
			if (head == ClojureReader.VECTOR || isListDatum(datum)) {
				List<String> members = new ArrayList<>();
				for (LispVal member : items.subList(head == ClojureReader.VECTOR ? 1 : 0, items.size())) {
					members.add(caseKey(ctx, member));
				}
				return "[" + String.join(" ", members) + "]";
			}
		}
		return "n" + datum.print();
	}

	/** A test constant as {@code str} spells it, for the duplicate refusal. */
	private static String strSpelling(LispVal datum) {
		datum = ClojureLowerUtil.stripMeta(datum);
		if (datum instanceof LispString str) {
			return str.value();
		}
		if (datum instanceof LispChar c) {
			return Character.toString(c.codePoint());
		}
		if (datum instanceof LispNil || ClojureLowerUtil.isSymbolNamed(datum, "nil")) {
			return "";
		}
		return prSpelling(datum);
	}

	private static String prSpelling(LispVal datum) {
		datum = ClojureLowerUtil.stripMeta(datum);
		List<LispVal> items = ClojureLowerUtil.items(datum);
		if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-set")) {
			List<String> members = new ArrayList<>();
			for (LispVal member : items.subList(1, items.size())) {
				members.add(prSpelling(member));
			}
			return "#{" + String.join(" ", members) + "}";
		}
		String rendered = ClojureStringLowering.prSource(datum);
		return rendered != null ? rendered : datum.print();
	}

	/**
	 * The oracle's {@code IllegalArgumentException} of a {@code case} or {@code condp} no
	 * clause took: {@code No matching clause: } and the value as {@code str} spells it.
	 */
	static LispVal noMatchingClause(ClojureLowering ctx, LispSymbol value) {
		return ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				ClojureStringLowering.concat(ctx, List.of(LispString.literal("No matching clause: "),
						ClojureStringLowering.strOf(ctx, value, LispString.literal(""), ClojureLowering.NIL_CONST))));
	}

	/**
	 * {@code (condp pred expr clause... default?)}: the predicate and the value bound
	 * once, in that order, then each clause's test applied as {@code (pred test expr)} in
	 * turn. A clause is {@code test result}, or {@code test :>> f}, which answers
	 * {@code f} of the predicate's truthy answer. A lone trailing form is the default;
	 * without one, no truthy clause is the oracle's {@code IllegalArgumentException}. The
	 * clauses split like the oracle's: a {@code :>>} second form takes three forms,
	 * anything else two.
	 */
	static LispVal condpOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/condp");
		LispVal predForm = ctx.lower(items.get(1));
		LispSymbol pred = ctx.freshTemp();
		LispVal value = ctx.lower(items.get(2));
		LispSymbol expr = ctx.freshTemp();
		boolean realPred = ClojureLowerUtil.yieldsFun(predForm);
		LispVal body = condpClauses(ctx, realPred, pred, expr, items.subList(3, items.size()));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET*"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(pred, predForm), ClojureLowerUtil.list(expr, value)), body);
	}

	private static LispVal condpClauses(ClojureLowering ctx, boolean realPred, LispSymbol pred, LispSymbol expr,
			List<LispVal> args) {
		int width = args.size() >= 2 && ClojureLowerUtil.isSymbolNamed(args.get(1), ":>>") ? 3 : 2;
		int n = Math.min(width, args.size());
		if (n == 0) {
			return noMatchingClause(ctx, expr);
		}
		if (n == 1) {
			return ctx.lowerTailSlot(args.get(0));
		}
		LispVal test = ctx.lower(args.get(0));
		LispVal applied = ctx.callFun(realPred, pred, List.of(test, expr));
		if (n == 2) {
			LispVal then = ctx.lowerTailSlot(args.get(1));
			LispVal rest = condpClauses(ctx, realPred, pred, expr, args.subList(n, args.size()));
			return ctx.ifFalsey(applied, then, rest);
		}
		LispSymbol answer = ctx.freshTemp();
		LispVal fnForm = ctx.lower(args.get(2));
		LispSymbol fn = ctx.freshTemp();
		LispVal call = ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(fn, fnForm)), ctx.callFun(fnForm, fn, List.of(answer)));
		LispVal rest = condpClauses(ctx, realPred, pred, expr, args.subList(n, args.size()));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(answer, applied)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"), ctx.isFalsey(answer), rest, call));
	}

	/**
	 * {@code (while test body...)}: the body again while the test is truthy, answering
	 * nil. One {@code do} loop whose end test is the falsey check; the body is never in
	 * tail position (the oracle's is a {@code loop} body followed by its {@code recur}).
	 */
	static LispVal whileOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/while");
		LispVal end = ctx.ifFalsey(ctx.lower(items.get(1)), ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST);
		List<LispVal> loop = new ArrayList<>();
		loop.add(ClojureLowerUtil.sym("do"));
		loop.add(ClojureLowering.NIL_CONST);
		loop.add(ClojureLowerUtil.list(end, ClojureLowering.NIL_CONST));
		if (items.size() > 2) {
			loop.add(ctx.nonTailBody(items, 2));
		}
		return ClojureLowerUtil.list(loop);
	}

	/**
	 * {@code (locking x body...)}: the body while holding the monitor of {@code x}. Where
	 * threads exist (the interpreter, the JVM: a Ring handler runs one per request) the
	 * monitor is a reentrant {@code rontolisp:make-mutex} the spliced
	 * {@code %clojure-monitor} keeps per object, held through
	 * {@code rontolisp:with-mutex}; on wasm, single-threaded by construction, the body
	 * runs as is. {@code nil} is the oracle's {@code NullPointerException} before the
	 * body. The body sits behind the {@code try} barrier, like the oracle's.
	 */
	static LispVal lockingOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/locking");
		LispVal lockee = ctx.lower(items.get(1));
		LispSymbol temp = ctx.freshTemp();
		LispVal body = ClojureStateLowering.barrierBody(ctx, items, 2);
		LispVal held = ctx.hostTarget ? ClojureLowerUtil.list(new LispSymbol("RONTOLISP:WITH-MUTEX"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(MONITOR, temp)), body) : body;
		LispVal nullCheck = ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("NULL"), temp),
				ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER,
						LispString.literal("Cannot enter synchronized block because \"locklocal\" is null")));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(temp, lockee)), nullCheck, held);
	}

}
