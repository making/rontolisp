package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The type and collection predicates of the Clojure lowering ({@code seq?}, {@code map?},
 * {@code keyword?}, {@code int?}, {@code identical?}, ...). A one-argument predicate is
 * one test answering a Common Lisp boolean -- a CL type predicate or a spliced
 * {@code rontolisp::%clojure-is-} helper in {@code clojure.lisp} -- wrapped in
 * {@code (if test T false)}; as a value it is a one-argument lambda over the same test. A
 * predicate whose kind no value here can have ({@code delay?}, {@code decimal?}, ...)
 * answers false after evaluating its argument; {@code future?} and the future verbs
 * ({@code future-done?}, {@code future-cancelled?}, {@code future-cancel}) know the two
 * kinds that exist, a host {@code java.util.concurrent.Future} and the rontolisp future
 * {@code rontolisp.http-client} answers under {@code :async true}. {@code set?} and
 * {@code reversible?} name the sorted-aware helpers, which a program building no sorted
 * collection calls as the plain ones ({@link ClojureArms.Family#SORTED}).
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojurePredicateLowering {

	private ClojurePredicateLowering() {
	}

	/**
	 * A one-argument test: the function called on the value, then the constant extra
	 * arguments.
	 */
	private record Test(String function, List<LispVal> extra) {

		LispVal over(LispVal value) {
			List<LispVal> call = new ArrayList<>();
			call.add(new LispSymbol(this.function));
			call.add(value);
			call.addAll(this.extra);
			return ClojureLowerUtil.list(call);
		}

	}

	/**
	 * {@code uuid?}'s test {@code (p value "java.util.UUID")}: a UUID or a host one, an
	 * alias of {@code %clojure-host-instance-p} in a program that makes no UUID
	 * ({@link ClojureArms.Family#UUID}), which lowers {@code uuid?} as before UUIDs
	 * existed.
	 */
	static final String IS_UUID = "RONTOLISP::%CLOJURE-IS-UUID";

	/** The instant family's test of a Date, a Timestamp or a Calendar. */
	static final String INSTANT_P = "RONTOLISP::%CLOJURE-INSTANT-P";

	/**
	 * The instant family's test of an instant {@code inst?} takes: a Date or a Timestamp.
	 */
	static final String INST_P = "RONTOLISP::%CLOJURE-INST-P";

	/** The instant family's test of a Date. */
	static final String DATE_P = "RONTOLISP::%CLOJURE-DATE-P";

	/** The instant family's test of a Timestamp. */
	static final String TIMESTAMP_P = "RONTOLISP::%CLOJURE-TIMESTAMP-P";

	/** The instant family's test of a Calendar. */
	static final String CALENDAR_P = "RONTOLISP::%CLOJURE-CALENDAR-P";

	/** The UUID family's test of a UUID. */
	static final String UUID_P = "RONTOLISP::%CLOJURE-UUID-P";

	private static final LispVal T = ClojureLowering.TRUE_CONST;

	private static final LispVal NIL = ClojureLowering.NIL_CONST;

	private static final Map<String, Test> TESTS = Map.ofEntries(Map.entry("seq?", helper("IS-SEQ")),
			Map.entry("sequential?", helper("IS-SEQUENTIAL")), Map.entry("map?", helper("IS-MAP")),
			Map.entry("set?", helper("IS-SET")), Map.entry("list?", helper("IS-LIST")),
			Map.entry("record?", helper("RECORD-P")), Map.entry("coll?", helper("IS-COLL")),
			Map.entry("seqable?", helper("IS-SEQABLE")), Map.entry("associative?", helper("IS-ASSOCIATIVE")),
			Map.entry("counted?", helper("IS-COUNTED")), Map.entry("indexed?", helper("IS-INDEXED")),
			Map.entry("reversible?", helper("IS-REVERSIBLE")), Map.entry("ifn?", helper("IS-IFN")),
			Map.entry("sorted?", helper("IS-SORTED")), Map.entry("number?", cl("NUMBERP")),
			Map.entry("integer?", cl("INTEGERP")), Map.entry("int?", helper("IS-INT")),
			Map.entry("double?", cl("FLOATP")), Map.entry("float?", cl("FLOATP")),
			Map.entry("ratio?", helper("IS-RATIO")), Map.entry("rational?", cl("RATIONALP")),
			Map.entry("nat-int?", helper("IS-NAT-INT")), Map.entry("pos-int?", helper("IS-POS-INT")),
			Map.entry("neg-int?", helper("IS-NEG-INT")), Map.entry("infinite?", helper("IS-INFINITE")),
			Map.entry("NaN?", helper("IS-NAN")), Map.entry("keyword?", helper("KEYWORD-P")),
			Map.entry("ident?", helper("IS-IDENT")), Map.entry("simple-ident?", qualified(T, T, NIL)),
			Map.entry("qualified-ident?", qualified(T, T, T)), Map.entry("simple-keyword?", qualified(T, NIL, NIL)),
			Map.entry("qualified-keyword?", qualified(T, NIL, T)), Map.entry("simple-symbol?", qualified(NIL, T, NIL)),
			Map.entry("qualified-symbol?", qualified(NIL, T, T)), Map.entry("char?", cl("CHARACTERP")),
			Map.entry("var?", helper("VAR-P")), Map.entry("volatile?", helper("IS-VOLATILE")),
			Map.entry("realized?", helper("IS-REALIZED")), Map.entry("special-symbol?", helper("IS-SPECIAL-SYMBOL")),
			Map.entry("inst?", helper("IS-INST")),
			Map.entry("uuid?", new Test(IS_UUID, List.of(LispString.literal("java.util.UUID")))),
			Map.entry("uri?", helper("IS-URI")), Map.entry("class?", host("java.lang.Class")));

	/**
	 * The predicates of a kind no value here can have: no chunked seq, decimal, byte
	 * array or delay exists on any backend, so each answers false for every value, which
	 * is the oracle's answer for every value a program here can build.
	 */
	private static final List<String> NEVER = List.of("chunked-seq?", "decimal?", "bytes?", "delay?");

	/**
	 * The predicates of a reader conditional and a tagged literal, each to its library
	 * function {@code (p value false)}: an alias of {@code progn} in a program that makes
	 * neither ({@link ClojureArms.Family#READER_VALUE}), where it answers false like
	 * {@link #NEVER}'s.
	 */
	private static final Map<String, String> READER_VALUE_PREDICATES = Map.of("reader-conditional?",
			"RONTOLISP::%CLOJURE-IS-READER-CONDITIONAL", "tagged-literal?", "RONTOLISP::%CLOJURE-IS-TAGGED-LITERAL");

	/** The reader-value family's test of either kind ({@code clojure.lisp}). */
	static final String READER_VALUE_P = "RONTOLISP::%CLOJURE-READER-VALUE-P";

	/** The reader-value family's test of a reader conditional. */
	static final String READER_COND_P = "RONTOLISP::%CLOJURE-READER-COND-P";

	/** The reader-value family's test of a tagged literal. */
	static final String TAGGED_LITERAL_P = "RONTOLISP::%CLOJURE-TAGGED-LITERAL-P";

	/**
	 * The verbs of a host {@code Future}, each to the library function reading it (an arm
	 * of {@link ClojureArms.Family#HOST}): {@code isDone}, {@code isCancelled} and
	 * {@code cancel(true)}, the oracle's casts to {@code java.util.concurrent.Future}.
	 */
	private static final Map<String, String> FUTURE_VERBS = Map.of("future-done?",
			"RONTOLISP::%CLOJURE-HOST-FUTURE-DONE-P", "future-cancelled?",
			"RONTOLISP::%CLOJURE-HOST-FUTURE-CANCELLED-P", "future-cancel", "RONTOLISP::%CLOJURE-HOST-FUTURE-CANCEL");

	/**
	 * A one-argument predicate's bare test over an already-lowered value: the Common Lisp
	 * boolean, without the {@code T}-or-false answer, for a lowering that branches on it.
	 * @param name the predicate's Clojure name ({@code map?}, {@code ident?}, ...)
	 * @param value the lowered value, a variable when the test is a sorted arm
	 * @return the test form
	 */
	static LispVal rawTest(String name, LispVal value) {
		Test test = TESTS.get(name);
		if (test == null) {
			throw new IllegalArgumentException("no one-argument predicate " + name);
		}
		return test.over(value);
	}

	/**
	 * A predicate in call position, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		Test test = TESTS.get(name);
		if (test != null) {
			ClojureCoreLowering.arity(name, n, 1, 1);
			return ctx.booleanAnswer(test.over(ctx.lower(items.get(1))));
		}
		if (name.equals("future?")) {
			ClojureCoreLowering.arity(name, n, 1, 1);
			return hostFuture(ctx, ctx.lower(items.get(1)));
		}
		String readerValue = READER_VALUE_PREDICATES.get(name);
		if (readerValue != null) {
			ClojureCoreLowering.arity(name, n, 1, 1);
			return ClojureLowerUtil.list(new LispSymbol(readerValue), ctx.lower(items.get(1)), ctx.falseVariable);
		}
		String verb = FUTURE_VERBS.get(name);
		if (verb != null) {
			ClojureCoreLowering.arity(name, n, 1, 1);
			return futureVerb(ctx, name, verb, ctx.lower(items.get(1)));
		}
		if (NEVER.contains(name) || name.equals("any?")) {
			ClojureCoreLowering.arity(name, n, 1, 1);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(1)),
					name.equals("any?") ? T : ctx.falseVariable);
		}
		switch (name) {
			case "not-any?", "not-every?": {
				ClojureCoreLowering.arity(name, n, 2, 2);
				ClojureBindingLowering.FnArg fn = ClojureBindingLowering.fnArg(ctx, items.get(1));
				LispVal seq = ClojureSeqLowering.seqForm(ctx, ctx.lower(items.get(2)));
				return negated(ctx, name.equals("not-any?") ? ClojureFilterLowering.someForm(ctx, fn, seq)
						: ClojureFilterLowering.everyForm(ctx, fn, seq));
			}
			case "identical?":
				ClojureCoreLowering.arity(name, n, 2, 2);
				return ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-IDENTICAL"), ctx.lower(items.get(1)),
						ctx.lower(items.get(2))));
			case "distinct?":
				ClojureCoreLowering.arity(name, n, 1, -1);
				return ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-DISTINCT"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1))));
			case "bound?":
				return ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-BOUND"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1))));
			case "thread-bound?":
				return ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-THREAD-BOUND"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1))));
			case "extends?":
				ClojureCoreLowering.arity(name, n, 2, 2);
				return extendsOf(ctx, items);
			default:
				return null;
		}
	}

	/**
	 * A predicate as a function value, or null when the name is none of them.
	 * {@code extends?} has none: its protocol and type are names read at lower time.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(ClojureLowering ctx, String name) {
		Test test = TESTS.get(name);
		if (test != null) {
			return ClojureFnLowering.predValue(ctx, test::over);
		}
		if (name.equals("future?")) {
			LispSymbol one = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
					hostFuture(ctx, one));
		}
		String readerValue = READER_VALUE_PREDICATES.get(name);
		if (readerValue != null) {
			LispSymbol one = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
					ClojureLowerUtil.list(new LispSymbol(readerValue), one, ctx.falseVariable));
		}
		String verb = FUTURE_VERBS.get(name);
		if (verb != null) {
			LispSymbol one = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
					futureVerb(ctx, name, verb, one));
		}
		if (NEVER.contains(name) || name.equals("any?")) {
			LispSymbol one = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), one,
					name.equals("any?") ? T : ctx.falseVariable);
		}
		switch (name) {
			case "not-any?", "not-every?": {
				LispSymbol pred = new LispSymbol(ClojureLowering.mangle(name + "-pred"));
				LispSymbol coll = new LispSymbol(ClojureLowering.mangle(name + "-coll"));
				ClojureBindingLowering.FnArg fn = ClojureBindingLowering.FnArg.of(pred);
				LispVal seq = ClojureSeqLowering.seqForm(ctx, coll);
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
						negated(ctx, name.equals("not-any?") ? ClojureFilterLowering.someForm(ctx, fn, seq)
								: ClojureFilterLowering.everyForm(ctx, fn, seq)));
			}
			case "identical?": {
				LispSymbol a = ctx.freshTemp();
				LispSymbol b = ctx.freshTemp();
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(a, b)),
						ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-IDENTICAL"), a, b)));
			}
			case "distinct?":
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), helperSymbol("IS-DISTINCT-V"));
			case "bound?":
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), helperSymbol("IS-BOUND-V"));
			case "thread-bound?":
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), helperSymbol("IS-THREAD-BOUND-V"));
			default:
				return null;
		}
	}

	/**
	 * {@code future?}: {@code (%clojure-future-or-host-p value false)}, which answers
	 * {@code T} for a rontolisp future ({@code rontolisp.http-client}'s {@code :async}
	 * answer) or a host {@code Future} and the false object otherwise. A program that
	 * fetches nothing has the call stand for {@code (%clojure-host-future-p value false)}
	 * ({@link ClojureArms.Family#FETCH}'s alias), and one naming no {@code java:}
	 * operator that for {@code (progn value false)} ({@link ClojureArms.Family#HOST}'s),
	 * what it lowered to before either could be one.
	 */
	private static LispVal hostFuture(ClojureLowering ctx, LispVal lowered) {
		return ClojureLowerUtil.list(new LispSymbol(FUTURE_OR_HOST_P), lowered, ctx.falseVariable);
	}

	/** {@code future?}'s host arm ({@code clojure.lisp}). */
	static final String HOST_FUTURE_P = "RONTOLISP::%CLOJURE-HOST-FUTURE-P";

	/** {@code future?}'s fetch arm ({@code clojure.lisp}), over the host one. */
	static final String FUTURE_OR_HOST_P = "RONTOLISP::%CLOJURE-FUTURE-OR-HOST-P";

	/**
	 * A verb of a future: over a rontolisp future, whether it has settled
	 * ({@code future-done?}) or false (it is never cancelled); over a host
	 * {@code Future}, the library function, answering a Clojure boolean; else the
	 * oracle's cast failure (nil a {@code NullPointerException}). A program that fetches
	 * nothing folds the first test away ({@link ClojureArms.Family#FETCH}), and one
	 * naming no {@code java:} operator the host test ({@link ClojureArms.Family#HOST}),
	 * leaving the refusal.
	 */
	private static LispVal futureVerb(ClojureLowering ctx, String name, String function, LispVal lowered) {
		LispSymbol cell = ctx.freshTemp();
		LispVal host = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(ClojureDispatchLowering.HOST_OBJECT_P), cell,
						LispString.literal(ClojureStateLowering.HOST_FUTURE)),
				ctx.booleanAnswer(ClojureLowerUtil.list(new LispSymbol(function), cell)), ClojureRefusals
					.refusal(ClojureRefusals.CLASS_CAST_OF, LispString.literal(name + " needs a future"), cell));
		// a rontolisp future (rontolisp.http-client's :async answer) is done once it
		// settles and is never cancelled -- future-cancel answers false, the "not
		// possible" of its contract: an arm a program that fetches nothing folds
		LispVal fetched = name.equals("future-done?")
				? ctx.booleanAnswer(ClojureLowerUtil.list(new LispSymbol(LispNames.FUTURE_SETTLED_QUALIFIED), cell))
				: ctx.falseVariable;
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.FUTURE_P), cell), fetched, host));
	}

	/**
	 * {@code (extends? Protocol Type)}: whether the type has a row in the protocol's
	 * table -- an inline implementation or an extension -- or, for {@code Object},
	 * whether the protocol was extended to {@code Object}, like the oracle (which counts
	 * an {@code Object} extension for no other type). Both are literal names, like
	 * {@code satisfies?}'s protocol.
	 */
	private static LispVal extendsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extends? takes a protocol name");
		ClojureLowerUtil.isTrue(items.get(2) instanceof LispSymbol, "extends? takes a type name");
		String protocol = ((LispSymbol) items.get(1)).name();
		ClojureLowering.ProtocolDef def = ClojureProtocolLowering.protocolOf(ctx, protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispVal key = ClojureProtocolLowering.extendKeyForm(ctx, ((LispSymbol) items.get(2)).name(), "extends?");
		ctx.usedProtocols = true;
		if (key == null) {
			return ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), def.defaultVar())));
		}
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> hits = new ArrayList<>();
		hits.add(ClojureLowerUtil.sym("or"));
		hits.add(rowHit(key, def.methodsVar(), miss));
		if (def.inlineVar() != null) {
			hits.add(rowHit(key, def.inlineVar(), miss));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(miss,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ctx.booleanAnswer(ClojureLowerUtil.list(hits)));
	}

	/** Whether the table holds a row under the key: its lookup is not the miss marker. */
	private static LispVal rowHit(LispVal key, LispSymbol table, LispSymbol miss) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table, miss), miss));
	}

	/** {@code T} when the {@code some}/{@code every?} answer is falsey, else false. */
	private static LispVal negated(ClojureLowering ctx, LispVal answer) {
		LispSymbol got = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, answer))),
				ctx.booleanAnswer(ctx.isFalsey(got)));
	}

	private static Test helper(String name) {
		return new Test(helperSymbol(name).name(), List.of());
	}

	private static Test cl(String name) {
		return new Test(name, List.of());
	}

	private static Test qualified(LispVal keywords, LispVal symbols, LispVal qualified) {
		return new Test(helperSymbol("IS-QUALIFIED").name(), List.of(keywords, symbols, qualified));
	}

	private static Test host(String className) {
		return new Test(helperSymbol("HOST-INSTANCE-P").name(), List.of(LispString.literal(className)));
	}

	private static LispSymbol helperSymbol(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name);
	}

}
