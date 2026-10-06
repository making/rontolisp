package am.ik.rontolisp.clojure;

import java.math.BigInteger;
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
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * String forms of the Clojure lowering: clojure.string, printing, formatting and file
 * reads.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureStringLowering {

	private ClojureStringLowering() {
	}

	/**
	 * A {@code clojure.string} call: the vars {@code ns} resolution already vetted, over
	 * the call's own items (whose head is ignored).
	 */
	static LispVal stringCall(ClojureLowering ctx, String var, List<LispVal> items) {
		int n = items.size() - 1;
		return switch (var) {
			case "join" -> {
				ClojureLowerUtil.isTrue(n == 1 || n == 2, "join takes a collection and an optional separator");
				yield n == 2 ? joinForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)))
						: joinForm(ctx, LispString.literal(""), ctx.lower(items.get(1)));
			}
			case "split" -> {
				ClojureLowerUtil.isTrue(n == 2 || n == 3, "split takes a string, a pattern and an optional limit");
				yield splitForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)),
						n == 3 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST, true);
			}
			case "split-lines" -> {
				ClojureLowerUtil.isTrue(n == 1, "split-lines takes one string");
				yield splitLinesForm(ctx, ctx.lower(items.get(1)));
			}
			case "upper-case", "lower-case" -> {
				ClojureLowerUtil.isTrue(n == 1, var + " takes one string");
				yield ClojureLowerUtil.list(
						ClojureLowerUtil.sym(var.equals("upper-case") ? "string-upcase" : "string-downcase"),
						ctx.lower(items.get(1)));
			}
			case "capitalize" -> {
				ClojureLowerUtil.isTrue(n == 1, "capitalize takes one string");
				yield capitalizeForm(ctx.lower(items.get(1)));
			}
			case "trim" -> {
				ClojureLowerUtil.isTrue(n == 1, "trim takes one string");
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("string-trim"), trimBag(), ctx.lower(items.get(1)));
			}
			case "triml" -> {
				ClojureLowerUtil.isTrue(n == 1, "triml takes one string");
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("string-left-trim"), trimBag(),
						ctx.lower(items.get(1)));
			}
			case "trimr" -> {
				ClojureLowerUtil.isTrue(n == 1, "trimr takes one string");
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("string-right-trim"), trimBag(),
						ctx.lower(items.get(1)));
			}
			case "trim-newline" -> {
				ClojureLowerUtil.isTrue(n == 1, "trim-newline takes one string");
				yield trimNewlineForm(ctx.lower(items.get(1)));
			}
			case "blank?" -> {
				ClojureLowerUtil.isTrue(n == 1, "blank? takes one string");
				yield ctx.booleanAnswer(blankForm(ctx.lower(items.get(1))));
			}
			case "starts-with?", "ends-with?", "includes?" -> {
				ClojureLowerUtil.isTrue(n == 2, var + " takes two strings");
				yield ctx.booleanAnswer(affixForm(var, ctx.lower(items.get(1)), ctx.lower(items.get(2))));
			}
			case "index-of" -> {
				ClojureLowerUtil.isTrue(n == 2 || n == 3, "index-of takes a string, a value and an optional start");
				if (n == 2) {
					yield ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), ctx.lower(items.get(2)),
							ctx.lower(items.get(1)));
				}
				LispSymbol str = ctx.freshTemp();
				LispSymbol sub = ctx.freshTemp();
				LispSymbol from = ctx.freshTemp();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, ctx.lower(items.get(1))),
								ClojureLowerUtil.list(sub, ctx.lower(items.get(2))),
								ClojureLowerUtil.list(from, ctx.lower(items.get(3))))),
						searchFrom(sub, str, from));
			}
			case "last-index-of" -> {
				ClojureLowerUtil.isTrue(n == 2 || n == 3, "last-index-of takes a string, a value and an optional end");
				yield lastIndexForm(ctx.lower(items.get(1)), ctx.lower(items.get(2)),
						n == 3 ? ctx.lower(items.get(3)) : null);
			}
			case "replace" -> {
				ClojureLowerUtil.isTrue(n == 3, "replace takes a string, a match and a replacement");
				yield replaceForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)),
						false);
			}
			case "replace-first" -> {
				ClojureLowerUtil.isTrue(n == 3, "replace-first takes a string, a match and a replacement");
				yield replaceForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)), true);
			}
			case "escape" -> {
				ClojureLowerUtil.isTrue(n == 2, "escape takes a string and a map");
				yield escapeForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			}
			case "re-quote-replacement" -> {
				ClojureLowerUtil.isTrue(n == 1, "re-quote-replacement takes one string");
				yield replaceForm(
						ctx, replaceForm(ctx, ctx.lower(items.get(1)), LispString.literal("\\"),
								LispString.literal("\\\\"), false),
						LispString.literal("$"), LispString.literal("\\$"), false);
			}
			case "reverse" -> {
				ClojureLowerUtil.isTrue(n == 1, "reverse takes one string");
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"), ctx.lower(items.get(1)),
										ClojureLowerUtil.quoted("list"))),
						ClojureLowerUtil.quoted("string"));
			}
			default -> throw new LispReadException("unknown name: clojure.string/" + var);
		};
	}

	/** Whether NAME is a {@code re-*} core name (lowered beside the big switch). */
	static boolean isReName(String name) {
		return name.equals("re-pattern") || name.equals("re-matcher") || name.equals("re-find") || name.equals("re-seq")
				|| name.equals("re-matches") || name.equals("re-groups");
	}

	/**
	 * A {@code re-*} call over the call's own items (whose head is ignored): patterns
	 * lower to the spliced regex runtime, so every backend shares the semantics.
	 */
	static LispVal reCall(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		return switch (name) {
			case "re-pattern" -> {
				ClojureLowerUtil.isTrue(n == 1, "re-pattern takes a pattern or a string");
				yield ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-PATTERN"), ctx.lower(items.get(1)));
			}
			case "re-matcher" -> {
				ClojureLowerUtil.isTrue(n == 2, "re-matcher takes a pattern and a string");
				yield ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-MATCHER"), ctx.lower(items.get(1)),
						ctx.lower(items.get(2)));
			}
			case "re-find" -> {
				ClojureLowerUtil.isTrue(n == 1 || n == 2, "re-find takes a matcher, or a pattern and a string");
				yield n == 2
						? ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-FIND"), ctx.lower(items.get(1)),
								ctx.lower(items.get(2)))
						: ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-FIND-M"),
								ctx.lower(items.get(1)));
			}
			case "re-seq" -> {
				ClojureLowerUtil.isTrue(n == 2, "re-seq takes a pattern and a string");
				yield ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-SEQ"), ctx.lower(items.get(1)),
						ctx.lower(items.get(2)));
			}
			case "re-matches" -> {
				ClojureLowerUtil.isTrue(n == 2, "re-matches takes a pattern and a string");
				yield ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-MATCHES"), ctx.lower(items.get(1)),
						ctx.lower(items.get(2)));
			}
			case "re-groups" -> {
				ClojureLowerUtil.isTrue(n == 1, "re-groups takes a matcher");
				yield ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-GROUPS"), ctx.lower(items.get(1)));
			}
			default -> throw new LispReadException("unknown name: " + name);
		};
	}

	/**
	 * A {@code re-*} var as a function value: one rest lambda dispatching on the argument
	 * count through the call lowering, so {@code (map re-pattern ...)} runs what a call
	 * would run.
	 */
	static LispVal reValue(ClojureLowering ctx, String name, List<Integer> arities) {
		String plain = "rev-rest" + (ctx.counter++);
		LispSymbol ref = new LispSymbol(plain);
		return ctx.inScope(Map.of(plain, ClojureLowering.Kind.VARIABLE), () -> {
			List<LispVal> arms = new ArrayList<>();
			for (int arity : arities) {
				List<LispVal> callItems = new ArrayList<>();
				callItems.add(new LispSymbol(name));
				for (int i = 0; i < arity; i++) {
					callItems.add(ClojureLowerUtil.list(new LispSymbol(ClojureCoreNames.PREFIX + "nth"), ref,
							new LispInteger(i)));
				}
				arms.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("="),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), ctx.localSym(plain)),
						new LispInteger(arity)), reCall(ctx, name, callItems)));
			}
			arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.ARITY,
					LispString.literal(name + " called with wrong number of arguments"))));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
					ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, ctx.localSym(plain))),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms));
		});
	}

	/**
	 * A {@code clojure.string} var as a function value: one rest lambda dispatching on
	 * the argument count through the call lowering, so {@code (map s/upper-case ...)}
	 * runs what a call would run.
	 */
	static LispVal stringValue(ClojureLowering ctx, String var) {
		// a user-style name lowered on both sides: the datum reference mangles to
		// the same symbol the parameter binds, and the counter keeps it unique
		String plain = "strv-rest" + (ctx.counter++);
		LispSymbol ref = new LispSymbol(plain);
		return ctx.inScope(Map.of(plain, ClojureLowering.Kind.VARIABLE), () -> {
			List<LispVal> arms = new ArrayList<>();
			for (int arity : stringArities(var)) {
				List<LispVal> callItems = new ArrayList<>();
				callItems.add(new LispSymbol(var));
				for (int i = 0; i < arity; i++) {
					callItems.add(ClojureLowerUtil.list(new LispSymbol(ClojureCoreNames.PREFIX + "nth"), ref,
							new LispInteger(i)));
				}
				arms.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("="),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), ctx.localSym(plain)),
						new LispInteger(arity)), stringCall(ctx, var, callItems)));
			}
			arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.ARITY,
					LispString.literal(var + " called with wrong number of arguments"))));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
					ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, ctx.localSym(plain))),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms));
		});
	}

	static List<Integer> stringArities(String var) {
		return switch (var) {
			case "join", "index-of", "last-index-of" -> List.of(1, 2);
			case "split" -> List.of(2, 3);
			case "starts-with?", "ends-with?", "includes?", "escape" -> List.of(2);
			case "replace", "replace-first" -> List.of(3);
			default -> List.of(1);
		};
	}

	// clojure.java.io: exactly reader, over the file-stream runtime

	/**
	 * {@code join}: the separator (nil counts as {@code ""}, like the oracle) between the
	 * {@code str} parts of the seq view, concatenated. Each part converts like a
	 * {@code str} part -- {@code ""} for nil, the colon spelling for a keyword.
	 */
	static LispVal joinForm(ClojureLowering ctx, LispVal sep, LispVal coll) {
		LispSymbol sepSym = ctx.freshTemp();
		LispSymbol parts = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal strings = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
						strOf(ctx, one, LispString.literal(""), ClojureLowering.NIL_CONST)),
				ClojureSeqLowering.seqAllForm(ctx, coll));
		LispVal interposed = ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("mapcan"), ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(one), ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), sepSym, one)),
				parts));
		LispVal joined = ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
				ClojureLowerUtil.quoted("string"), interposed);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(List.of(
				ClojureLowerUtil.list(sepSym,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), sep), LispString.literal(""), sep)),
				ClojureLowerUtil.list(parts, strings))), joined);
	}

	/**
	 * {@code split}: the string cut at every (non-overlapping, in order) occurrence of
	 * the string pattern -- a character or anything else signals, like the oracle's cast
	 * failure -- as a strict list. An empty match cuts between characters; an empty input
	 * answers nil; a positive limit caps the parts (the last holding the rest); a
	 * negative limit keeps every part; otherwise trailing empties drop.
	 */
	static LispVal splitForm(ClojureLowering ctx, LispVal text, LispVal pattern, LispVal limit, boolean emptyToNil) {
		LispSymbol str = ctx.freshTemp();
		LispSymbol raw = ctx.freshTemp();
		LispSymbol pat = ctx.freshTemp();
		LispSymbol lim = ctx.freshTemp();
		LispVal chars = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("string")),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"), str, ClojureLowerUtil.quoted("list")));
		LispVal cut = splitLoop(ctx, str, pat, lim);
		LispVal whole = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("zerop"), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), pat)), chars, cut);
		LispVal result = emptyToNil ? ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string="), str, LispString.literal("")),
				ClojureLowering.NIL_CONST, whole) : whole;
		LispVal checked = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), lim),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), lim)),
				result, ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST_OF,
						LispString.literal("split takes an integer limit"), lim));
		// a pattern splits around matches (an empty input one empty part); a string
		// or character splits literally, like ever (the literal-only pin holds)
		LispVal literal = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pat,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
								ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), raw), raw),
								ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), raw),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("string"), raw)),
								ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
										ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST_OF,
												LispString.literal("split takes a string to split on"), raw)))))),
				result);
		LispVal regex = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-SPLIT"), raw, str, lim);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, text), ClojureLowerUtil.list(raw, pattern),
						ClojureLowerUtil.list(lim, limit))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isPatternForm(raw), regex, literal));
	}

	/**
	 * The split loop: a labels self call accumulating the parts in reverse, then the
	 * limit rule -- capped (the last part holding the rest), kept whole, or trailing
	 * empties dropped.
	 */
	static LispVal splitLoop(ClojureLowering ctx, LispVal str, LispVal pat, LispVal lim) {
		String name = ClojureLowering.mangle("split-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pos = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispVal found = ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), pat, str, ClojureLowerUtil.sym(":start2"),
				at);
		LispVal capped = ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), lim),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), lim, new LispInteger(0)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("="),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), acc),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), lim, new LispInteger(1))));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), found),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, at), acc)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), capped,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, at), acc)),
						ClojureLowerUtil.list(self,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), found,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), pat)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, at, found), acc))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(at, acc)), ClojureLowerUtil.cons(step, List.of())));
		LispVal parts = ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
				ClojureLowerUtil.list(self, new LispInteger(0), ClojureLowering.NIL_CONST));
		// a nonzero integer limit keeps every part (a positive one capped the loop
		// above); otherwise trailing empties drop
		LispVal keep = ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), lim), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("not"), ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"), lim)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), keep, parts, dropTrailing(ctx, parts));
	}

	/**
	 * Trailing empty strings dropped: the reversed list past its leading empties,
	 * reversed back.
	 */
	static LispVal dropTrailing(ClojureLowering ctx, LispVal parts) {
		String name = ClojureLowering.mangle("trimtail-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), rest,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("string="),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest), LispString.literal(""))),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest)), rest);
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), parts))));
	}

	/** {@code split-lines}: split on newlines, an empty input nil, each line unended. */
	static LispVal splitLinesForm(ClojureLowering ctx, LispVal text) {
		LispSymbol line = ctx.freshTemp();
		LispSymbol len = ctx.freshTemp();
		LispVal unended = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List
					.of(ClojureLowerUtil.list(len, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), line)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), len, new LispInteger(0)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("char"), line,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), len, new LispInteger(1))),
								new LispChar('\r'))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), line, new LispInteger(0),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), len, new LispInteger(1))),
						line));
		LispVal lines = splitForm(ctx, text, LispString.literal("\n"), ClojureLowering.NIL_CONST, true);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(line), unended), lines);
	}

	/** {@code capitalize}: the first character up, the rest down. */
	static LispVal capitalizeForm(LispVal text) {
		LispSymbol str = new LispSymbol("__clojure_cap");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(List
			.of(ClojureLowerUtil.list(str, text))), ClojureLowerUtil.list(
					ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(
							ClojureLowerUtil.sym("zerop"), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), str)),
					str,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("string-upcase"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, new LispInteger(0),
											new LispInteger(1))),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("string-downcase"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, new LispInteger(1))))));
	}

	/** The whitespace bag {@code trim} trims: the ASCII whitespace characters. */
	static LispVal trimBag() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(List.of(new LispChar(' '),
				new LispChar('\t'), new LispChar('\n'), new LispChar('\r'), new LispChar('\f'))));
	}

	/** {@code trim-newline}: one trailing newline (or carriage-return newline) off. */
	static LispVal trimNewlineForm(LispVal text) {
		LispSymbol str = new LispSymbol("__clojure_tnl");
		LispSymbol len = new LispSymbol("__clojure_tnl_n");
		LispVal last = ClojureLowerUtil.list(ClojureLowerUtil.sym("char"), str,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), len, new LispInteger(1)));
		LispVal prev = ClojureLowerUtil.list(ClojureLowerUtil.sym("char"), str,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), len, new LispInteger(2)));
		return ClojureLowerUtil
			.list(ClojureLowerUtil.sym("let*"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, text),
							ClojureLowerUtil.list(len, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), str)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
							ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), len, new LispInteger(1)),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"), prev, new LispChar('\r')),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"), last, new LispChar('\n'))),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, new LispInteger(0),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), len, new LispInteger(2)))),
							ClojureLowerUtil.list(
									ClojureLowerUtil.list(
											ClojureLowerUtil.sym("and"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), len, new LispInteger(0)),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"), last,
													new LispChar('\n'))),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, new LispInteger(0),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), len, new LispInteger(1)))),
							ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, str)));
	}

	/** Whether the value is blank: nil, or a string of only trimmable characters. */
	static LispVal blankForm(LispVal value) {
		LispSymbol str = new LispSymbol("__clojure_blank");
		return ClojureLowerUtil
			.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, value))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), str),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"), ClojureLowerUtil.list(
									ClojureLowerUtil.sym("length"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("string-trim"), trimBag(), str)))));
	}

	/**
	 * {@code starts-with?} / {@code ends-with?} / {@code includes?}, answering raw: a
	 * prefix, suffix or substring test through {@code string=} and {@code search}.
	 */
	static LispVal affixForm(String var, LispVal text, LispVal wanted) {
		LispSymbol str = new LispSymbol("__clojure_aff");
		LispSymbol sub = new LispSymbol("__clojure_aff_sub");
		LispVal test = switch (var) {
			case "starts-with?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("<="),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), sub),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), str)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("string="), sub,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, new LispInteger(0),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), sub))));
			case "ends-with?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("<="),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), sub),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), str)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("string="), sub,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str,
									ClojureLowerUtil.list(ClojureLowerUtil.sym("-"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), str),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), sub)))));
			default -> ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("null"), ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), sub, str)));
		};
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, text), ClojureLowerUtil.list(sub, wanted))),
				test);
	}

	/**
	 * {@code search} from a start read as Java's {@code indexOf} reads one: a negative
	 * start is 0 and one past the end is the end (the oracle answers nil there, or the
	 * length for an empty match), where {@code search} refuses a start outside the
	 * string. {@code str} is a variable, read twice.
	 */
	static LispVal searchFrom(LispVal sub, LispVal str, LispVal from) {
		LispVal clamped = ClojureLowerUtil.list(ClojureLowerUtil.sym("max"), new LispInteger(0), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("min"), bound(from), ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), str)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), sub, str, ClojureLowerUtil.sym(":start2"),
				clamped);
	}

	/**
	 * {@code last-index-of}: the last occurrence at or before the end index (clamped to
	 * the string, like the oracle; a negative end is nil). Without an end the whole
	 * string is searched backwards.
	 */
	static LispVal lastIndexForm(LispVal text, LispVal wanted, @Nullable LispVal end) {
		LispSymbol str = new LispSymbol("__clojure_li");
		LispSymbol sub = new LispSymbol("__clojure_li_sub");
		LispVal tail = ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), sub, str,
				ClojureLowerUtil.sym(":from-end"), ClojureLowering.TRUE_CONST);
		if (end == null) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil
				.list(List.of(ClojureLowerUtil.list(str, text), ClojureLowerUtil.list(sub, wanted))), tail);
		}
		LispSymbol to = new LispSymbol("__clojure_li_to");
		LispVal window = ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, new LispInteger(0),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("min"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), to, new LispInteger(1)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), str)));
		LispVal found = ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), sub, window,
				ClojureLowerUtil.sym(":from-end"), ClojureLowering.TRUE_CONST);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, text), ClojureLowerUtil.list(sub, wanted),
						ClojureLowerUtil.list(to, end))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), to, new LispInteger(0)),
						ClojureLowering.NIL_CONST, found));
	}

	/**
	 * {@code replace} / {@code replace-first}: every (or the first) occurrence of the
	 * string match swapped for the string-or-character replacement -- anything else
	 * signals, like the oracle's cast failure. An empty match interposes the replacement
	 * between characters.
	 */
	static LispVal replaceForm(ClojureLowering ctx, LispVal text, LispVal match, LispVal replacement, boolean first) {
		// a string match pairs with a string replacement, a character with a
		// character: anything else is the oracle's cast failure
		LispSymbol str = ctx.freshTemp();
		LispSymbol rawMatch = ctx.freshTemp();
		LispSymbol rawRep = ctx.freshTemp();
		LispSymbol mat = ctx.freshTemp();
		LispSymbol rep = ctx.freshTemp();
		LispVal bad = ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST,
				LispString.literal("replace takes a string match and replacement, or a character pair"));
		LispVal matchNorm = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), rawMatch),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), rawRep)), rawMatch),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), rawMatch),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), rawRep)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("string"), rawMatch)),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, bad));
		LispVal repNorm = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), rawMatch),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), rawRep)), rawRep),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), rawMatch),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), rawRep)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("string"), rawRep)),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, bad));
		LispVal looped = first ? replaceFirstLoop(str, mat, rep) : replaceLoop(ctx, str, mat, rep);
		LispVal interposed = joinForm(ctx, rep,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("string")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"), str, ClojureLowerUtil.quoted("list"))));
		// a pattern match swaps through the regex runtime (a string replacement
		// interpolating $ groups, anything else applying to the match); a string
		// or character match swaps literally, like ever
		LispVal literal = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(mat, matchNorm), ClojureLowerUtil.list(rep, repNorm))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("string="), mat, LispString.literal("")), interposed,
						looped));
		// the regex runtime funcalls a replacement that is no string: a literal string or
		// a function form passes as itself, anything else is wrapped here, so a plain
		// replace never carries the IFn dispatcher
		LispVal regexRep = replacement instanceof LispString || ClojureLowerUtil.yieldsFun(replacement) ? rawRep
				: ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-REPLACEMENT"), rawRep);
		LispVal regex = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-REPLACE"), str, rawMatch, regexRep,
				first ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, text), ClojureLowerUtil.list(rawMatch, match),
						ClojureLowerUtil.list(rawRep, replacement))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isPatternForm(rawMatch), regex, literal));
	}

	/**
	 * Whether the lowered value is a regex pattern: one call to the spliced
	 * {@code rontolisp::%clojure-re-pattern-p}.
	 */
	static LispVal isPatternForm(LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-PATTERN-P"), lowered);
	}

	/**
	 * Whether the lowered value is a regex matcher: one call to the spliced
	 * {@code rontolisp::%clojure-re-matcher-p}.
	 */
	static LispVal isMatcherForm(LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-MATCHER-P"), lowered);
	}

	/**
	 * Whether the lowered value is regex state (a pattern or a matcher): opaque to every
	 * collection verb, like a deftype or reify value.
	 */
	static LispVal isRegexForm(LispVal lowered) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), isPatternForm(lowered), isMatcherForm(lowered));
	}

	/** Every occurrence swapped: the parts before, between and after, concatenated. */
	static LispVal replaceLoop(ClojureLowering ctx, LispVal str, LispVal mat, LispVal rep) {
		String name = ClojureLowering.mangle("replace-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pos = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispVal found = ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), mat, str, ClojureLowerUtil.sym(":start2"),
				pos);
		LispVal grown = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), rep,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, pos, found), acc));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), found),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
						ClojureLowerUtil.quoted("string"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, pos), acc))),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), found,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), mat)), grown));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(pos, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
				ClojureLowerUtil.list(self, new LispInteger(0), ClojureLowering.NIL_CONST));
	}

	/** The first occurrence swapped, or the string itself when there is none. */
	static LispVal replaceFirstLoop(LispVal str, LispVal mat, LispVal rep) {
		LispSymbol at = new LispSymbol("__clojure_rf");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List
					.of(ClojureLowerUtil.list(at, ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), mat, str)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), at), str,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str, new LispInteger(0), at), rep,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), str,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at,
												ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), mat))))));
	}

	/**
	 * {@code escape}: each character looked up in the map, a hit (a string or a
	 * character) swapped in, a miss kept as itself, the whole concatenated.
	 */
	static LispVal escapeForm(ClojureLowering ctx, LispVal text, LispVal table) {
		LispSymbol str = ctx.freshTemp();
		LispSymbol cmap = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol hit = ctx.freshTemp();
		LispVal swap = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(hit,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, cmap, miss)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), hit, miss),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("string"), one),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), hit),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("string"), hit), hit)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(str, text), ClojureLowerUtil.list(cmap, table),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
						ClojureLowerUtil.quoted("string"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), swap),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"), str,
										ClojureLowerUtil.quoted("list")))));
	}

	/** The run-time truncation of a bound that is not statically an integer. */
	static final String STRING_BOUND = "RONTOLISP::%CLOJURE-STRING-BOUND";

	/**
	 * The bound of {@code subs}, {@code .substring} or {@code .charAt}: the oracle
	 * truncates a double or ratio one. A literal number inside the int range is truncated
	 * here, and a {@code length} cannot be anything but an integer; both leave a site
	 * that is the plain verb. Any other bound goes through {@link #STRING_BOUND}.
	 */
	static LispVal bound(LispVal form) {
		if (form instanceof LispInteger) {
			return form;
		}
		if (form instanceof LispDouble number && Math.abs(number.value()) < INT_EDGE) {
			return new LispInteger((long) number.value());
		}
		if (form instanceof LispRatio ratio && ratio.truncate().abs().compareTo(BigInteger.valueOf(INT_EDGE)) < 0) {
			return new LispInteger(ratio.truncate().longValue());
		}
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("LENGTH")) {
			return form;
		}
		return ClojureLowerUtil.list(new LispSymbol(STRING_BOUND), form);
	}

	private static final long INT_EDGE = 2147483647L;

	/**
	 * {@code subs}: the substring from the start, past the optional end, through
	 * {@code subseq} -- by its refusal family's alias ({@link ClojureRefusals#SUBS}), so
	 * a bound outside the string is the oracle's {@code StringIndexOutOfBoundsException}
	 * where the program reads a condition's class.
	 */
	static LispVal subsOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "subs takes a string, a start and an optional end");
		return n == 3
				? ClojureLowerUtil.list(new LispSymbol(ClojureRefusals.SUBS), ctx.lower(items.get(1)),
						bound(ctx.lower(items.get(2))), bound(ctx.lower(items.get(3))))
				: ClojureLowerUtil.list(new LispSymbol(ClojureRefusals.SUBS), ctx.lower(items.get(1)),
						bound(ctx.lower(items.get(2))));
	}

	/** {@code subs} as a value: a two- or three-argument lambda over the primitive. */
	static LispVal subsValue(ClojureLowering ctx) {
		LispSymbol str = new LispSymbol(ClojureLowering.mangle("subs-s"));
		LispSymbol from = new LispSymbol(ClojureLowering.mangle("subs-from"));
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("subs-args"));
		LispVal two = ClojureLowerUtil.list(new LispSymbol(ClojureRefusals.SUBS), str, bound(from));
		LispVal three = ClojureLowerUtil.list(new LispSymbol(ClojureRefusals.SUBS), str, bound(from),
				bound(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("subs takes a string, a start and an optional end"));
		LispVal body = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), two,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil
						.list(ClojureLowerUtil.sym("null"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
							three, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(str, from, ClojureLowering.AMPERSAND_REST, args)), body);
	}

	// calls

	/**
	 * {@code str} as a value: over any number of arguments, each converted like a
	 * {@code str} part ({@code ""} for {@code nil}) and concatenated. The parts map over
	 * the rest list and spread back through {@code apply}.
	 */
	static LispVal strValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("str-args"));
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("str-one"));
		LispVal oneFn = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
				strOf(ctx, one, LispString.literal(""), ClojureLowering.NIL_CONST));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("string")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"), oneFn, args)));
	}

	/**
	 * {@code pr-str} as a value: like {@code str} as a value, but every part converts
	 * readably, {@code nil} prints as {@code "nil"}, and the parts join with a single
	 * space (like {@code pr}).
	 */
	static LispVal prStrValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("pr-str-args"));
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("pr-str-one"));
		LispVal oneFn = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
				strOf(ctx, one, LispString.literal("nil"), ClojureLowering.TRUE_CONST));
		LispVal strings = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"), oneFn, args);
		LispVal interposed = ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcan"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), LispString.literal(" "), one)),
						strings));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("string")),
						interposed));
	}

	/**
	 * {@code print-str}/{@code prn-str}/{@code println-str}: what the matching print call
	 * would write, answered as a string. The parts evaluate in the caller (what they
	 * print goes to the real output) and the library builder prints them to a private
	 * stream, so no {@code *standard-output*} rebinding is involved.
	 */
	static LispVal printStrCall(ClojureLowering ctx, List<LispVal> items, boolean readable, boolean newline) {
		List<LispVal> parts = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			parts.add(ctx.lower(items.get(i)));
		}
		return printStrOf(new LispCons(ClojureLowerUtil.sym("list"), ClojureLowerUtil.list(parts)), readable, newline);
	}

	/**
	 * {@code print-str}/{@code prn-str}/{@code println-str} as a value: over any arity.
	 */
	static LispVal printStrValue(boolean readable, boolean newline) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("print-str-args"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), printStrOf(args, readable, newline));
	}

	private static LispVal printStrOf(LispVal parts, boolean readable, boolean newline) {
		return ClojureLowerUtil.list(ClojureLowering.CLOJURE_PRINT_STR, parts,
				readable ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST,
				newline ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST);
	}

	static LispVal strCall(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() == 1) {
			return LispString.literal("");
		}
		List<LispVal> parts = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			parts.add(strOf(ctx, ctx.lower(items.get(i)), LispString.literal(""), ClojureLowering.NIL_CONST));
		}
		return concat(ctx, parts);
	}

	/**
	 * {@code pr-str}: the readable arm of {@code str} -- every part converts readably
	 * (strings print quoted, like {@code pr}), parts joined with a single space (like
	 * {@code pr}), and {@code nil} prints as {@code "nil"}.
	 */
	static LispVal prStrCall(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() == 1) {
			return LispString.literal("");
		}
		List<LispVal> parts = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			if (i > 1) {
				parts.add(LispString.literal(" "));
			}
			parts.add(strOf(ctx, ctx.lower(items.get(i)), LispString.literal("nil"), ClojureLowering.TRUE_CONST));
		}
		return concat(ctx, parts);
	}

	/**
	 * {@code pr-str} of a source datum, rendered at lower time, or null when some part is
	 * not a plain symbol, keyword, integer, string, character, list, vector or map (a
	 * double, ratio, set, regex, reader marker or anything else), where the runtime
	 * printer is the one source of truth.
	 * @param form the datum the reader (or a macro) produced
	 * @return the readable rendering, or null
	 */
	static @Nullable String prSource(LispVal form) {
		StringBuilder out = new StringBuilder();
		return prSource(form, out) ? out.toString() : null;
	}

	private static boolean prSource(LispVal form, StringBuilder out) {
		if (form instanceof LispSymbol s) {
			String name = s.name();
			if (name.isEmpty() || name.startsWith("%")) {
				return false;
			}
			out.append(name);
			return true;
		}
		if (form instanceof LispInteger i) {
			out.append(i.value());
			return true;
		}
		if (form instanceof LispString str) {
			out.append('"');
			String value = str.value();
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				switch (c) {
					case '"' -> out.append("\\\"");
					case '\\' -> out.append("\\\\");
					case '\n' -> out.append("\\n");
					case '\t' -> out.append("\\t");
					case '\r' -> out.append("\\r");
					case '\f' -> out.append("\\f");
					case '\b' -> out.append("\\b");
					default -> out.append(c);
				}
			}
			out.append('"');
			return true;
		}
		if (form instanceof LispChar c) {
			String named = switch (c.codePoint()) {
				case '\n' -> "newline";
				case ' ' -> "space";
				case '\t' -> "tab";
				case '\b' -> "backspace";
				case '\f' -> "formfeed";
				case '\r' -> "return";
				default -> null;
			};
			out.append('\\');
			if (named != null) {
				out.append(named);
			}
			else {
				out.appendCodePoint(c.codePoint());
			}
			return true;
		}
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null) {
			return false;
		}
		boolean vector = !items.isEmpty() && items.get(0) == ClojureReader.VECTOR;
		boolean map = !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-map");
		List<LispVal> body = vector || map ? items.subList(1, items.size()) : items;
		if (map && body.size() % 2 != 0) {
			return false;
		}
		out.append(vector ? '[' : map ? '{' : '(');
		for (int i = 0; i < body.size(); i++) {
			if (i > 0) {
				out.append(map && i % 2 == 0 ? ", " : " ");
			}
			if (!prSource(body.get(i), out)) {
				return false;
			}
		}
		out.append(vector ? ']' : map ? '}' : ')');
		return true;
	}

	/**
	 * One part's Clojure-notation string: {@code rontolisp::%clojure-str-of} over the
	 * lowered value (false is {@code "false"}, {@code T} is {@code "true"}, {@code NIL}
	 * is the replacement, a keyword its colon spelling, collections in Clojure notation).
	 * The value runs once, as the call's argument.
	 * @param value the lowered value
	 * @param nilReplacement the string {@code NIL} prints as
	 * @param readable whether the conversion reads back
	 * @return the form
	 */
	static LispVal strOf(ClojureLowering ctx, LispVal value, LispVal nilReplacement, LispVal readable) {
		return ClojureLowerUtil.list(ClojureLowering.CLOJURE_STR_OF, value, nilReplacement, readable);
	}

	static LispVal printCall(ClojureLowering ctx, List<LispVal> items, boolean newline) {
		// Every part writes to the stream directly behind one
		// rontolisp::%clojure-write-datum call, with the single spaces and the
		// newline as their own writes: no with-output-to-string ever reaches a
		// compiled program (a literal one flips a WASM module into EH mode). The
		// call answers nil, like the oracle.
		return printWrites(ctx, items, newline, ClojureLowering.NIL_CONST);
	}

	/**
	 * The writes of one print-family call: each part through
	 * {@code rontolisp::%clojure-write-datum}, single spaces between, the newline last,
	 * answering nil. The parts evaluate before the first write, like the oracle's
	 * argument evaluation -- a part that prints or throws never splits the line -- so
	 * with several parts any computed one binds every part to a temporary first, in order
	 * (a lone part, or constants only, need none).
	 */
	static LispVal printWrites(ClojureLowering ctx, List<LispVal> items, boolean newline, LispVal readable) {
		List<LispVal> parts = new ArrayList<>();
		boolean computed = false;
		for (int i = 1; i < items.size(); i++) {
			LispVal part = ctx.lower(items.get(i));
			parts.add(part);
			computed |= part instanceof LispCons && !ClojureLowering.isQuoteForm(part);
		}
		List<LispVal> bindings = new ArrayList<>();
		if (computed && parts.size() > 1) {
			for (int i = 0; i < parts.size(); i++) {
				LispSymbol temp = ctx.freshTemp();
				bindings.add(ClojureLowerUtil.list(temp, parts.get(i)));
				parts.set(i, temp);
			}
		}
		List<LispVal> writes = new ArrayList<>();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				writes.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("WRITE-CHAR"), new LispChar(32)));
			}
			writes.add(writeDatum(ctx, parts.get(i), LispString.literal("nil"), readable));
		}
		if (newline) {
			writes.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("TERPRI")));
		}
		writes.add(ClojureLowering.NIL_CONST);
		LispVal progn = ClojureLowerUtil.cons(ClojureLowerUtil.sym("PROGN"), writes);
		if (bindings.isEmpty()) {
			return progn;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET*"), ClojureLowerUtil.list(bindings), progn);
	}

	/**
	 * {@code pr}/{@code prn}: the readable arms of {@code print}/{@code println} -- same
	 * space separator, same newline folding, but every part converts readably, so strings
	 * print quoted like the oracle's.
	 */
	static LispVal prCall(ClojureLowering ctx, List<LispVal> items, boolean newline) {
		return printWrites(ctx, items, newline, ClojureLowering.TRUE_CONST);
	}

	/**
	 * One part's direct write: {@code rontolisp::%clojure-write-datum} over the lowered
	 * value, answering nil.
	 * @param value the lowered value
	 * @param nilReplacement the string {@code NIL} prints as
	 * @param readable whether the conversion reads back
	 * @return the form
	 */
	static LispVal writeDatum(ClojureLowering ctx, LispVal value, LispVal nilReplacement, LispVal readable) {
		return ClojureLowerUtil.list(ClojureLowering.CLOJURE_WRITE_DATUM, value, nilReplacement, readable);
	}

	static LispVal concat(ClojureLowering ctx, List<LispVal> parts) {
		return new LispCons(ClojureLowerUtil.sym("concatenate"),
				new LispCons(ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("string")),
						ClojureLowerUtil.list(parts)));
	}

	/**
	 * {@code spit}: the {@code str} spelling of the content written to the path,
	 * answering nil. With an {@code :append} flag the writes append, else the file is
	 * superseded.
	 */
	static LispVal spitOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 4, "spit takes a path, content and an optional :append flag");
		LispVal exists;
		if (n == 4) {
			if (!ClojureLowerUtil.isSymbolNamed(items.get(3), ":append")) {
				throw new LispReadException("spit takes a path, content and an optional :append flag");
			}
			exists = ctx.ifFalsey(ctx.lower(items.get(4)), ClojureLowerUtil.sym(":append"),
					ClojureLowerUtil.sym(":supersede"));
		}
		else {
			exists = ClojureLowerUtil.sym(":supersede");
		}
		return spitForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)), exists);
	}

	/** The write over already-lowered path, content and {@code :if-exists} mode. */
	static LispVal spitForm(ClojureLowering ctx, LispVal path, LispVal content, LispVal exists) {
		LispSymbol file = ctx.freshTemp();
		LispSymbol text = ctx.freshTemp();
		LispVal spelled = strOf(ctx, content, LispString.literal(""), ClojureLowering.NIL_CONST);
		LispSymbol stream = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(file, path), ClojureLowerUtil.list(text, spelled))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("with-open-file"),
						ClojureLowerUtil.list(List.of(stream, file, ClojureLowerUtil.sym(":direction"),
								ClojureLowerUtil.sym(":output"), ClojureLowerUtil.sym(":if-exists"), exists)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("write-string"), text, stream),
						ClojureLowering.NIL_CONST));
	}

	/** {@code spit} as a value: path, content and an optional append flag. */
	static LispVal spitValue(ClojureLowering ctx) {
		LispSymbol path = new LispSymbol(ClojureLowering.mangle("spit-path"));
		LispSymbol content = new LispSymbol(ClojureLowering.mangle("spit-content"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("spit-rest"));
		LispVal exists = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowerUtil.sym(":supersede"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest), ClojureLowerUtil.sym(":append"),
						ClojureLowerUtil.sym(":supersede")));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(path, content, ClojureLowering.AMPERSAND_REST, rest)),
				spitForm(ctx, path, content, exists));
	}

	/**
	 * {@code slurp}: the whole file as a string, read character by character into a
	 * string stream.
	 */
	static LispVal slurpForm(ClojureLowering ctx, LispVal path) {
		String name = ClojureLowering.mangle("slurp-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol file = ctx.freshTemp();
		LispSymbol stream = ctx.freshTemp();
		LispSymbol out = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("read-char"), stream, ClojureLowering.NIL_CONST,
								ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("get-output-stream-string"), out),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("write-char"), got, out),
								ClojureLowerUtil.list(self))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of()), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(file, path))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("with-open-file"),
						ClojureLowerUtil.list(List.of(stream, file)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
								ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(out,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("make-string-output-stream"))))),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"),
										ClojureLowerUtil.list(List.of(binding)), ClojureLowerUtil.list(self)))));
	}

	/** {@code slurp} as a value: a one-argument lambda over the same read. */
	static LispVal slurpValue(ClojureLowering ctx) {
		LispSymbol path = new LispSymbol(ClojureLowering.mangle("slurp-path"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(path), slurpForm(ctx, path));
	}

	/**
	 * {@code line-seq}: the lines as a strict list -- of a path, opened and closed around
	 * the read (the documented path deviation: the oracle takes a reader and answers
	 * lazily), or of an already-open reader, which is read but never closed
	 * ({@code with-open} owns closing, like the oracle). A stream value takes the reader
	 * loop, anything else the path form.
	 */
	static LispVal lineSeqForm(ClojureLowering ctx, LispVal target) {
		LispSymbol src = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(src, target))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("streamp"), src), lineSeqReaderLoop(ctx, src),
						lineSeqPathForm(ctx, src)));
	}

	/**
	 * The {@code read-line} loop over an already-bound stream: no open, no close, so a
	 * {@code with-open} body may consume its reader and the cleanup still closes exactly
	 * once.
	 */
	static LispVal lineSeqReaderLoop(ClojureLowering ctx, LispVal stream) {
		String name = ClojureLowering.mangle("line-seq-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol acc = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("read-line"), stream, ClojureLowering.NIL_CONST,
								ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
						ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), got, acc))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
				ClojureLowerUtil.list(self, ClojureLowering.NIL_CONST));
	}

	/** The path form: the same loop opened and closed around the file. */
	static LispVal lineSeqPathForm(ClojureLowering ctx, LispVal path) {
		String name = ClojureLowering.mangle("line-seq-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol file = ctx.freshTemp();
		LispSymbol stream = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("read-line"), stream, ClojureLowering.NIL_CONST,
								ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
						ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), got, acc))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(file, path))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("with-open-file"),
						ClojureLowerUtil.list(List.of(stream, file)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
								ClojureLowerUtil.list(self, ClojureLowering.NIL_CONST))));
	}

	/** {@code line-seq} as a value: a one-argument lambda over the same read. */
	static LispVal lineSeqValue(ClojureLowering ctx) {
		LispSymbol path = new LispSymbol(ClojureLowering.mangle("line-seq-path"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(path),
				lineSeqForm(ctx, path));
	}

	/**
	 * {@code format}: the Java-format string translated to Common Lisp directives over
	 * Clojure-notation arguments. The format string must be literal (its directives
	 * translate at lowering time); {@code %s} converts through {@code str} semantics (nil
	 * spells {@code "null"}, like {@code String/valueOf}), {@code %b} through the boolean
	 * spelling, numbers through the matching numeric directive. Precision only rides
	 * {@code %f}/{@code %e}/{@code %g}; any flag, date or hash directive is a named
	 * refusal instead of a wrong answer.
	 */
	static LispVal formatOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "format takes a format string and arguments");
		if (!(items.get(1) instanceof LispString fmt)) {
			throw new LispReadException("format takes a literal format string, not " + items.get(1).print());
		}
		String pattern = fmt.value();
		StringBuilder cl = new StringBuilder();
		List<LispVal> args = new ArrayList<>();
		int next = 2;
		int i = 0;
		while (i < pattern.length()) {
			char c = pattern.charAt(i);
			if (c != '%') {
				cl.append(c == '~' ? "~~" : c);
				i++;
				continue;
			}
			i++;
			ClojureLowerUtil.isTrue(i < pattern.length(), "a format string cannot end in %");
			char d = pattern.charAt(i);
			if (d == '%') {
				cl.append('%');
				i++;
				continue;
			}
			if (d == 'n') {
				cl.append("~%");
				i++;
				continue;
			}
			if ("-0#,+ (".indexOf(d) >= 0) {
				throw new LispReadException("format flag %" + d + " is not supported yet");
			}
			StringBuilder width = new StringBuilder();
			while (i < pattern.length() && Character.isDigit(pattern.charAt(i))) {
				width.append(pattern.charAt(i++));
			}
			StringBuilder precision = new StringBuilder();
			if (i < pattern.length() && pattern.charAt(i) == '.') {
				i++;
				while (i < pattern.length() && Character.isDigit(pattern.charAt(i))) {
					precision.append(pattern.charAt(i++));
				}
			}
			ClojureLowerUtil.isTrue(i < pattern.length(), "a format directive cannot end the string");
			char conv = pattern.charAt(i++);
			ClojureLowerUtil.isTrue(next < items.size(), "format takes an argument per directive");
			LispVal arg = ctx.lower(items.get(next++));
			switch (conv) {
				case 's' -> {
					ClojureLowerUtil.isTrue(precision.length() == 0, "format precision is not supported yet for %s");
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					args.add(strOf(ctx, arg, LispString.literal("null"), ClojureLowering.NIL_CONST));
				}
				case 'd' -> {
					ClojureLowerUtil.isTrue(precision.length() == 0, "format precision is not supported yet for %d");
					cl.append('~').append(width).append('D');
					args.add(checkedArg(ctx, arg, "integerp", "%d takes an integer"));
				}
				case 'x', 'X' -> {
					ClojureLowerUtil.isTrue(precision.length() == 0, "format precision is not supported yet for %x");
					// Common Lisp spells hexadecimal uppercase, like %X: a lowercase
					// %x downcases the converted string first.
					LispVal hex = ClojureLowerUtil.list(ClojureLowerUtil.sym("format"), ClojureLowering.NIL_CONST,
							LispString.literal("~X"), checkedArg(ctx, arg, "integerp", "%x takes an integer"));
					if (conv == 'x') {
						hex = ClojureLowerUtil.list(ClojureLowerUtil.sym("string-downcase"), hex);
					}
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					args.add(hex);
				}
				case 'o' -> {
					ClojureLowerUtil.isTrue(precision.length() == 0, "format precision is not supported yet for %o");
					cl.append('~').append(width).append('O');
					args.add(checkedArg(ctx, arg, "integerp", "%o takes an integer"));
				}
				case 'c' -> {
					ClojureLowerUtil.isTrue(precision.length() == 0, "format precision is not supported yet for %c");
					// ~C prints readably, so characters convert through string first.
					LispVal text = ClojureLowerUtil.list(ClojureLowerUtil.sym("string"),
							checkedArg(ctx, arg, "characterp", "%c takes a character"));
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					args.add(text);
				}
				case 'b' -> {
					ClojureLowerUtil.isTrue(precision.length() == 0, "format precision is not supported yet for %b");
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					LispSymbol test = ctx.freshTemp();
					args.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
							ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(test, arg))),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), test),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), test, ctx.falseVariable)),
									LispString.literal("false"), LispString.literal("true"))));
				}
				case 'f' -> {
					cl.append('~').append(width);
					if (precision.length() > 0) {
						cl.append(',').append(precision);
					}
					cl.append('F');
					args.add(checkedArg(ctx, arg, "floatp", "%f takes a float"));
				}
				default -> throw new LispReadException("format directive %" + conv + " is not supported yet");
			}
		}
		ClojureLowerUtil.isTrue(next == items.size(), "format takes an argument per directive");
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowerUtil.sym("format"));
		call.add(ClojureLowering.NIL_CONST);
		call.add(LispString.literal(cl.toString()));
		call.addAll(args);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * A format argument checked against the directive's type, like the oracle's
	 * conversion check: anything else signals instead of printing wrongly.
	 */
	static LispVal checkedArg(ClojureLowering ctx, LispVal arg, String predicate, String message) {
		LispSymbol one = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, arg))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym(predicate), one), one, ClojureRefusals
							.refusal(ClojureRefusals.ILLEGAL_FORMAT_CONVERSION, LispString.literal(message))));
	}

	// namespaces: ns clauses, require/use/import as alias wiring

}
