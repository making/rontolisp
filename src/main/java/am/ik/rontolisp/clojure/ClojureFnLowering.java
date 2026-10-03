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
 * Function forms of the Clojure lowering: combinators, predicates and coercions as
 * values.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureFnLowering {

	private ClojureFnLowering() {
	}

	/** {@code inc}/{@code dec} as a value: a one-argument lambda over the primitive. */
	static LispVal incValue(ClojureLowering ctx, String name) {
		LispSymbol x = new LispSymbol(ClojureLowering.mangle("inc-x"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(x),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(name.equals("inc") ? "+" : "-"), x, new LispInteger(1)));
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
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"),
								ClojureCollectionLowering.getBranches(ctx, coll, key, dflt, true))));
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
	 * failure (it sits in the else branch), like the oracle's lazy message form. The
	 * failure text carries the failed form as quoted data through the readable string
	 * conversion: {@code Assert failed: (nil? 1)}, or
	 * {@code Assert failed: msg\n(nil? 1)} with a message.
	 */
	static LispVal assertOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "assert takes a test and an optional message");
		LispVal test = ctx.lower(items.get(1));
		String rendered = ClojureStringLowering.prSource(items.get(1));
		LispVal form = rendered != null ? LispString.literal(rendered) : ClojureStringLowering.strOf(ctx,
				ctx.quote(items.get(1)), LispString.literal("nil"), ClojureLowering.TRUE_CONST);
		List<LispVal> parts = new ArrayList<>();
		parts.add(LispString.literal("Assert failed: "));
		if (n == 2) {
			parts.add(ClojureStringLowering.strOf(ctx, ctx.lower(items.get(2)), LispString.literal(""),
					ClojureLowering.NIL_CONST));
			parts.add(LispString.literal("\n"));
		}
		parts.add(form);
		// No message and a rendered form: one literal, so no string-building code is
		// linked.
		LispVal text = n == 1 && rendered != null ? LispString.literal("Assert failed: " + rendered)
				: ClojureStringLowering.concat(ctx, parts);
		LispVal failure = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), text);
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
		LispVal check = ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), List.of(
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), whole),
						ClojureLowering.NIL_CONST),
				ClojureLowerUtil
					.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), ClojureCollectionLowering.isSetForm(whole),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), whole),
							ClojureSortedLowering.sortedTest(whole)), refusal),
				ClojureLowerUtil.list(seqable, pick), ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, refusal)));
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
						ClojureLowerUtil.list(ClojureProtocolLowering.isRecordForm(whole), refusal),
						ClojureLowerUtil.list(ClojureSortedLowering.sortedMapTest(whole), refusal),
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

	// Map verbs: copy-on-write over fresh tables, like assoc/merge

	/** {@code comp}: right-nested application, no functions the identity. */
	static LispVal compOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return identityValue(ctx);
		}
		List<ClojureBindingLowering.FnArg> fns = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			fns.add(ClojureBindingLowering.fnArg(ctx, items.get(i)));
		}
		if (n == 1) {
			return fns.get(0).fun();
		}
		return compForm(ctx, fns);
	}

	/**
	 * The composition over already-lowered functions: the rightmost spreads the
	 * arguments, each outer wraps one result.
	 */
	static LispVal compForm(ClojureLowering ctx, List<ClojureBindingLowering.FnArg> fns) {
		LispSymbol args = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> names = new ArrayList<>();
		for (ClojureBindingLowering.FnArg fn : fns) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, fn.fun()));
			names.add(one);
		}
		LispVal inner = ctx.applyFun(fns.get(fns.size() - 1).real(), names.get(names.size() - 1), args);
		for (int i = names.size() - 2; i >= 0; i--) {
			inner = ctx.callFun(fns.get(i).real(), names.get(i), List.of(inner));
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
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)), ctx.callableApply(fun,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ctx.callableApply(next, args))));
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
	static LispVal partialForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fun, List<LispVal> fixed) {
		LispSymbol fn = ctx.freshTemp();
		LispSymbol more = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(fn, fun.fun()));
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
						ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, more),
						ctx.applyFun(fun.real(), fn, tail)));
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
	static LispVal complementForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fun) {
		LispSymbol fn = ctx.freshTemp();
		LispSymbol args = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal neg = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, ctx.applyFun(fun.real(), fn, args)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
						ClojureLowering.TRUE_CONST, ctx.falseVariable));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fn, fun.fun()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), neg));
	}

	/** {@code complement} as a value: a one-argument lambda over the same negation. */
	static LispVal complementValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("complement-fn"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(fun),
				complementForm(ctx, ClojureBindingLowering.FnArg.of(fun)));
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
	 * without the tag -- and the argument list keys by {@code =}
	 * ({@code rontolisp::%clojure-memo-key}).
	 */
	static LispVal memoizeForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fun) {
		LispSymbol table = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol fn = ctx.freshTemp();
		LispSymbol args = ctx.freshTemp();
		LispSymbol hit = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispVal inner = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
							ClojureLowerUtil.list(List.of(
									ClojureLowerUtil.list(key,
											ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MEMO-KEY"),
													args)),
									ClojureLowerUtil.list(hit,
											ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table, miss)))),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), hit, miss),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
											ClojureLowerUtil.list(List
												.of(ClojureLowerUtil.list(val, ctx.applyFun(fun.real(), fn, args)))),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
													ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
													val),
											val),
									hit)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(fn, fun.fun()))),
				inner);
	}

	/** {@code memoize} as a value: a one-argument lambda over the same cache. */
	static LispVal memoizeValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("memoize-fn"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(fun),
				memoizeForm(ctx, ClojureBindingLowering.FnArg.of(fun)));
	}

	/**
	 * {@code trampoline}: the function applied, then every thunk result invoked with no
	 * arguments until a non-function answers. A labels self call, so mutual thunk chains
	 * stay constant-stack on the interpreter.
	 */
	static LispVal trampolineForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fun, LispVal argList) {
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
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fn, fun.fun()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, ctx.applyFun(fun.real(), fn, argList))));
	}

	/** {@code trampoline} as a value: the function, then any arguments. */
	static LispVal trampolineValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("trampoline-fn"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("trampoline-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fun, ClojureLowering.AMPERSAND_REST, rest)),
				trampolineForm(ctx, ClojureBindingLowering.FnArg.of(fun), rest));
	}

	// Predicates and casts

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
