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
import java.util.function.Supplier;
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
 * State forms of the Clojure lowering: atoms, STM, binding, try and defonce/defstruct.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureStateLowering {

	private ClojureStateLowering() {
	}

	/**
	 * The {@code try} body lowered behind the barrier: the depth grows while it lowers,
	 * so a {@code recur} inside one trips the barrier no matter where the {@code try}
	 * itself sits. The body is never tail position either.
	 */
	static LispVal tryBodyOf(ClojureLowering ctx, List<LispVal> body) {
		boolean outerTail = ctx.tailPosition;
		ctx.tailPosition = false;
		ctx.tryDepth++;
		try {
			return ctx.bodyOf(body);
		}
		finally {
			ctx.tryDepth--;
			ctx.tailPosition = outerTail;
		}
	}

	/**
	 * {@code (try body... (catch Class var body...)... (finally ...))}: the body guarded
	 * by a {@code handler-case} inside an {@code unwind-protect}. Each catch clause is a
	 * {@code handler-case} clause of the type its class takes
	 * ({@link ClojureThrowables#clauseType}: an unknown class is the oracle's refusal),
	 * in order, so the first clause whose class takes the condition runs, with its var
	 * bound to the condition, and a condition no clause takes is never caught here -- the
	 * oracle's exception passing a catch that does not name its class. The body lowers
	 * behind the {@code try} barrier (a {@code recur} across it is the oracle's
	 * {@code Cannot recur across try} refusal); the catch and finally parts are never
	 * tail position (a {@code recur} there is the oracle's tail refusal), like the
	 * oracle.
	 */
	static LispVal tryOf(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> body = new ArrayList<>();
		List<List<LispVal>> catches = new ArrayList<>();
		List<LispVal> fin = new ArrayList<>();
		boolean closed = false;
		for (int i = 1; i < items.size(); i++) {
			List<LispVal> part = ClojureLowerUtil.items(items.get(i));
			if (part != null && !part.isEmpty() && ClojureLowerUtil.isSymbolNamed(part.get(0), "catch")) {
				ClojureLowerUtil.isTrue(part.size() >= 3 && part.get(1) instanceof LispSymbol,
						"catch takes a class, a name and a body");
				ClojureLowerUtil.plainName(part.get(2), "catch");
				catches.add(part);
				closed = true;
				continue;
			}
			if (part != null && !part.isEmpty() && ClojureLowerUtil.isSymbolNamed(part.get(0), "finally")) {
				fin.addAll(part.subList(1, part.size()));
				closed = true;
				continue;
			}
			ClojureLowerUtil.isTrue(!closed, "a try body comes before catch and finally");
			body.add(items.get(i));
		}
		LispVal guarded = body.isEmpty() ? ClojureLowering.NIL_CONST : tryBodyOf(ctx, body);
		if (!catches.isEmpty()) {
			ctx.usedCatch = true;
			List<LispVal> handler = new ArrayList<>();
			handler.add(ClojureLowerUtil.sym("HANDLER-CASE"));
			handler.add(guarded);
			for (List<LispVal> caught : catches) {
				LispVal type = ClojureThrowables.clauseType(ctx,
						ClojureThrowables.caughtChain(ctx, caught.get(1), "catch"));
				String var = ((LispSymbol) caught.get(2)).name();
				LispVal clauseBody = ctx.inScope(new HashMap<>(Map.of(var, ClojureLowering.Kind.VARIABLE)),
						() -> ctx.nonTailBodyOf(caught.subList(3, caught.size())));
				handler.add(ClojureLowerUtil.list(type, ClojureLowerUtil.list(ctx.localSym(var)), clauseBody));
			}
			guarded = ClojureLowerUtil.list(handler);
		}
		if (!fin.isEmpty()) {
			List<LispVal> forms = new ArrayList<>();
			forms.add(ClojureLowerUtil.sym("UNWIND-PROTECT"));
			forms.add(guarded);
			for (LispVal f : fin) {
				forms.add(ctx.lower(f));
			}
			guarded = ClojureLowerUtil.list(forms);
		}
		return guarded;
	}

	// state: atoms and volatiles over a tagged one-vector cell

	/**
	 * The tag heading an atom: {@code (:C%ATOM #(value))}, the set wrapper's shape, so
	 * the verbs can tell an atom from a plain vector. The value lives in a one-vector
	 * cell, which every backend reads and writes; printing spells the wrapper, not the
	 * oracle's object syntax.
	 */
	static final LispSymbol ATOM_TAG = new LispSymbol(":C%ATOM");

	static LispVal wrapAtom(LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ATOM_TAG,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), value));
	}

	/**
	 * The second slot of a volatile's cell: a volatile is an atom whose cell carries it,
	 * so every atom verb reads and writes it unchanged while {@code volatile?} tells the
	 * two apart, like the oracle's two classes.
	 */
	static final LispSymbol VOLATILE_MARK = new LispSymbol(":C%VOLATILE");

	static LispVal wrapVolatile(LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ATOM_TAG,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), value, VOLATILE_MARK));
	}

	/** {@code volatile!} as a value: a one-argument lambda over the constructor. */
	static LispVal volatileValue() {
		LispSymbol init = new LispSymbol(ClojureLowering.mangle("volatile-init"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(init), wrapVolatile(init));
	}

	/** Whether the form holds a wrapped atom: the tag over a one-vector cell. */
	static LispVal isAtomForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), ATOM_TAG),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), form)));
	}

	/** The value inside an already-bound atom: the cell's only element. */
	static LispVal atomGet(LispVal bound) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), bound), new LispInteger(0));
	}

	/** The cell rewritten to the value: answers the value. */
	static LispVal atomPut(LispVal bound, LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), bound), new LispInteger(0)), value);
	}

	/**
	 * The lowered atom checked and handed to the body builder: anything else signals,
	 * like the oracle's cast failure. The atom runs once, behind a temporary.
	 */
	static LispVal withAtom(ClojureLowering ctx, LispVal lowered, String op,
			java.util.function.Function<LispVal, LispVal> body) {
		LispSymbol cell = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isAtomForm(cell), body.apply(cell), ClojureRefusals
					.refusal(ClojureRefusals.CLASS_CAST_OF, LispString.literal(op + " needs an atom"), cell)));
	}

	static LispVal atomOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "atom takes an initial value");
		return wrapAtom(ctx.lower(items.get(1)));
	}

	static LispVal derefOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "deref takes one argument");
		return derefForm(ctx, ctx.lower(items.get(1)));
	}

	/**
	 * The value inside the lowered atom; anything else goes to the spliced
	 * {@code rontolisp::%clojure-deref-other}, which answers a reduced value's content
	 * (the oracle's {@code Reduced} is an {@code IDeref}) and signals otherwise.
	 */
	static LispVal derefForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol cell = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isAtomForm(cell), atomGet(cell),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DEREF-OTHER"), cell)));
	}

	static LispVal swapOf(ClojureLowering ctx, List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		ClojureLowerUtil.isTrue(items.size() >= 3, op + " takes an atom, a function and arguments");
		LispVal loweredAtom = ctx.lower(items.get(1));
		LispVal fun = items.get(2);
		List<LispVal> rest = items.subList(3, items.size());
		return withAtom(ctx, loweredAtom, op, cell -> {
			List<LispVal> tail = new ArrayList<>();
			for (LispVal arg : rest) {
				tail.add(ctx.lower(arg));
			}
			LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), atomGet(cell),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), tail));
			LispSymbol next = ctx.freshTemp();
			return ClojureLowerUtil
				.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
										ClojureBindingLowering.fnValue(ctx, fun), spread)))),
						atomPut(cell, next), next);
		});
	}

	static LispVal resetOf(ClojureLowering ctx, List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		ClojureLowerUtil.isTrue(items.size() == 3, op + " takes an atom and a value");
		LispVal value = ctx.lower(items.get(2));
		return withAtom(ctx, ctx.lower(items.get(1)), op, cell -> {
			LispSymbol next = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next, value))), atomPut(cell, next), next);
		});
	}

	/**
	 * {@code compare-and-set!}: the cell rewritten only when its value is {@code eql} to
	 * the expected one -- value comparison for numbers, identity for everything else,
	 * which is how the oracle's reference comparison answers on coalesced literals --
	 * answering a Clojure boolean.
	 */
	static LispVal compareAndSetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "compare-and-set! takes an atom, an expected value and a new value");
		LispVal wanted = ctx.lower(items.get(2));
		LispVal value = ctx.lower(items.get(3));
		return withAtom(ctx, ctx.lower(items.get(1)), "compare-and-set!", cell -> {
			LispSymbol next = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next, value))),
					ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"), wanted, atomGet(cell)), ClojureLowerUtil
								.list(ClojureLowerUtil.sym("progn"), atomPut(cell, next), ClojureLowering.TRUE_CONST),
							ClojureLowering.NIL_CONST)));
		});
	}

	/** {@code atom} as a value: a one-argument lambda over the constructor. */
	static LispVal atomValue(ClojureLowering ctx) {
		LispSymbol init = new LispSymbol(ClojureLowering.mangle("atom-init"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(init), wrapAtom(init));
	}

	/** {@code deref} as a value: a one-argument lambda over the reader. */
	static LispVal derefValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("deref-cell"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(cell), derefForm(ctx, cell));
	}

	/** {@code swap!} as a value: over an atom, a function and any more arguments. */
	static LispVal swapValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("swap-cell"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("swap-fun"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("swap-rest"));
		LispSymbol next = new LispSymbol(ClojureLowering.mangle("swap-next"));
		LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), atomGet(cell), rest);
		LispVal update = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), fun, spread)))),
				atomPut(cell, next), next);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, fun, ClojureLowering.AMPERSAND_REST, rest)),
				withAtom(ctx, cell, "swap!", ignored -> update));
	}

	/** {@code reset!} as a value: a two-argument lambda over the writer. */
	static LispVal resetValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("reset-cell"));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("reset-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cell, value)),
				withAtom(ctx, cell, "reset!", bound -> atomPut(bound, value)));
	}

	/**
	 * {@code ex-data}/{@code ex-message}/{@code ex-cause} as a value: a one-argument
	 * lambda over the library reader.
	 */
	static LispVal exHelperValue(ClojureLowering ctx, String helper) {
		ctx.usedExInfo = true;
		LispSymbol ex = new LispSymbol(ClojureLowering.mangle("ex-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(ex),
				ClojureLowerUtil.list(new LispSymbol(helper), ex));
	}

	/**
	 * {@code ex-info} as a value: a lambda of a message, data and an optional cause over
	 * the library constructor.
	 */
	static LispVal exInfoValue(ClojureLowering ctx) {
		ctx.usedExInfo = true;
		LispSymbol message = new LispSymbol(ClojureLowering.mangle("ex-message"));
		LispSymbol data = new LispSymbol(ClojureLowering.mangle("ex-data"));
		LispSymbol cause = new LispSymbol(ClojureLowering.mangle("ex-cause"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(message, data, ClojureLowerUtil.sym("&optional"), cause)),
				ClojureLowerUtil.list(new LispSymbol(EX_INFO), message, data, cause));
	}

	/** The library functions behind the exception verbs ({@code clojure.lisp}). */
	static final String EX_INFO = "RONTOLISP::%CLOJURE-EX-INFO";

	static final String EX_MESSAGE = "RONTOLISP::%CLOJURE-EX-MESSAGE";

	static final String EX_DATA = "RONTOLISP::%CLOJURE-EX-DATA";

	static final String EX_CAUSE = "RONTOLISP::%CLOJURE-EX-CAUSE";

	static final String THROW = "RONTOLISP::%CLOJURE-THROW";

	static final String EXCEPTION_NEW = "RONTOLISP::%CLOJURE-EXCEPTION-NEW";

	static final String EXCEPTION_NEW_1 = "RONTOLISP::%CLOJURE-EXCEPTION-NEW-1";

	static final String EXCEPTION_METHOD = "RONTOLISP::%CLOJURE-EXCEPTION-METHOD";

	/**
	 * {@code (ex-info message data cause?)}: an ExceptionInfo through the library
	 * constructor.
	 */
	static LispVal exInfoOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3 || items.size() == 4,
				"ex-info takes a message, a data map and an optional cause");
		ctx.usedExInfo = true;
		return ClojureLowerUtil.list(new LispSymbol(EX_INFO), ctx.lower(items.get(1)), ctx.lower(items.get(2)),
				items.size() == 4 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code (ex-message e)}, {@code (ex-data e)}, {@code (ex-cause e)} and
	 * {@code (throw e)}: one call to the library function.
	 */
	static LispVal exReaderOf(ClojureLowering ctx, List<LispVal> items, String helper) {
		ClojureLowerUtil.isTrue(items.size() == 2, ((LispSymbol) items.get(0)).name() + " takes one exception");
		ctx.usedExInfo = true;
		return ClojureLowerUtil.list(new LispSymbol(helper), ctx.lower(items.get(1)));
	}

	/** {@code compare-and-set!} as a value: a three-argument lambda over the swap. */
	static LispVal compareAndSetValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("cas-cell"));
		LispSymbol wanted = new LispSymbol(ClojureLowering.mangle("cas-wanted"));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("cas-value"));
		return ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cell, wanted, value)), withAtom(ctx,
					cell, "compare-and-set!",
					bound -> ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eql"), wanted, atomGet(bound)), ClojureLowerUtil
								.list(ClojureLowerUtil.sym("progn"), atomPut(bound, value), ClojureLowering.TRUE_CONST),
							ClojureLowering.NIL_CONST))));
	}

	// state: refs over the atom cell, agents as synchronous atoms, binding over
	// specials, and the small imperative companions

	/**
	 * The STM runtime, spliced once behind the false binding when the program uses refs
	 * or agents: the open-transaction depth (a {@code dosync} binds it one deeper, so
	 * {@code alter} and friends outside one signal), the validator registry (an alist of
	 * cell/validator pairs, cells compared by identity) and the acting-agent var (bound
	 * while a {@code send} runs, nil outside one). Pure lowering over existing
	 * primitives, so every backend runs it unchanged.
	 */
	static List<LispVal> stmRuntime(ClojureLowering ctx) {
		List<LispVal> runtime = new ArrayList<>();
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defvar"), new LispSymbol("C%STM-DEPTH"),
				new LispInteger(0)));
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defvar"), new LispSymbol("C%STM-VALIDATORS"),
				ClojureLowering.NIL_CONST));
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defvar"), new LispSymbol("C%AGENT"),
				ClojureLowering.NIL_CONST));
		LispSymbol cell = new LispSymbol("cell");
		LispSymbol validator = new LispSymbol("validator");
		LispSymbol rest = new LispSymbol("rest");
		LispSymbol value = new LispSymbol("value");
		LispSymbol found = new LispSymbol("found");
		LispSymbol verdict = new LispSymbol("verdict");
		// (defun c%stm-put (cell validator) ...): register the validator, answer it
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%STM-PUT"),
				ClojureLowerUtil.list(List.of(cell, validator)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), new LispSymbol("C%STM-VALIDATORS"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), cell, validator),
								new LispSymbol("C%STM-VALIDATORS"))),
				validator));
		// (defun c%stm-get (cell) ...): the validator registered for the cell, or nil
		LispSymbol walk = new LispSymbol("walk");
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%STM-GET"),
				ClojureLowerUtil.list(List.of(cell)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(ClojureLowerUtil
					.list(walk, ClojureLowerUtil.list(rest), ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("caar"), rest), cell),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("cdar"), rest),
									ClojureLowerUtil.list(walk,
											ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest))))))),
						ClojureLowerUtil.list(walk, new LispSymbol("C%STM-VALIDATORS")))));
		// (defun c%stm-check (cell value) ...): write the value through the
		// validator, answering it; a failed validator signals and writes nothing
		// (the single-threaded rollback: nothing else ran)
		LispVal write = ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), atomPut(cell, value), value);
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%STM-CHECK"),
				ClojureLowerUtil.list(List.of(cell, value)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(found,
								ClojureLowerUtil.list(new LispSymbol("C%STM-GET"), cell)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), found,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
										ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(verdict,
												ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), found, value)))),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
														ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), verdict),
														ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), verdict,
																ctx.falseVariable)),
												ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_STATE,
														LispString.literal("Invalid reference state")),
												write)),
								write))));
		return runtime;
	}

	/** The STM transaction guard: the body runs only inside {@code dosync}. */
	static LispVal txnGuard(LispVal guarded) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), new LispSymbol("C%STM-DEPTH"), new LispInteger(0)),
				guarded,
				ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_STATE, LispString.literal("No transaction running")));
	}

	/**
	 * A {@code ref}/{@code agent} option list over an already-lowered init: the
	 * {@code :validator} as a callable form, or null. {@code :meta} is dropped, like
	 * every other metadata; anything else is refused by name.
	 */
	static @Nullable LispVal validatorOpt(ClojureLowering ctx, List<LispVal> items, int from, String op) {
		LispVal validator = null;
		for (int i = from; i < items.size(); i += 2) {
			if (!(items.get(i) instanceof LispSymbol opt) || i + 1 >= items.size()) {
				throw new LispReadException(op + " takes option/value pairs");
			}
			switch (opt.name()) {
				case ":validator" -> {
					ClojureLowerUtil.isTrue(validator == null, op + " takes one :validator");
					validator = ClojureBindingLowering.fnValue(ctx, items.get(i + 1));
				}
				case ":meta" -> {
				} // dropped, like every other metadata
				default -> throw new LispReadException(op + " option " + opt.name() + " is not supported yet");
			}
		}
		return validator;
	}

	/**
	 * A fresh cell with the validator registered: every {@code ref} and every
	 * {@code agent} with a {@code :validator} builds one.
	 */
	static LispVal checkedCell(ClojureLowering ctx, LispVal init, LispVal validator) {
		LispSymbol made = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(made, wrapAtom(init)))),
				ClojureLowerUtil.list(new LispSymbol("C%STM-PUT"), made, validator), made);
	}

	/**
	 * {@code (ref init & opts)}: an atom cell, like {@code atom}; with a
	 * {@code :validator} the validator runs on every {@code alter}/{@code commute}/
	 * {@code ref-set} and rejects the write on failure.
	 */
	static LispVal refOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "ref takes an initial value");
		LispVal init = ctx.lower(items.get(1));
		LispVal validator = validatorOpt(ctx, items, 2, "ref");
		ctx.usedStm = true;
		if (validator == null) {
			return wrapAtom(init);
		}
		return checkedCell(ctx, init, validator);
	}

	/** {@code ref} as a value: a one-argument lambda over the constructor. */
	static LispVal refValue(ClojureLowering ctx) {
		LispSymbol init = new LispSymbol(ClojureLowering.mangle("ref-init"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(init), wrapAtom(init));
	}

	/**
	 * {@code (dosync body...)}: the body with the transaction depth bound one deeper.
	 * Single-threaded there is nothing to retry and nothing to isolate against, so a
	 * transaction is a dynamic extent; {@code alter} and friends still require one.
	 */
	static LispVal dosyncOf(ClojureLowering ctx, List<LispVal> items) {
		ctx.usedStm = true;
		LispSymbol depth = new LispSymbol("C%STM-DEPTH");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(depth,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), depth, new LispInteger(1))))),
				ctx.body(items, 1));
	}

	/**
	 * {@code (alter ref fun args...)} and {@code (commute ref fun args...)}: the function
	 * applied to the old value and the arguments, written through the validator.
	 * Single-threaded the two commute identically (the oracle may run a commute's
	 * function twice); both require a transaction.
	 */
	static LispVal alterOf(ClojureLowering ctx, List<LispVal> items, String op) {
		ClojureLowerUtil.isTrue(items.size() >= 3, op + " takes a ref, a function and arguments");
		ctx.usedStm = true;
		return alterBuild(ctx, ctx.lower(items.get(1)), ClojureBindingLowering.fnValue(ctx, items.get(2)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)), op);
	}

	/**
	 * An {@code alter}/{@code commute} over already-lowered forms: the guard, the
	 * application and the validated write, answering the new value.
	 */
	static LispVal alterBuild(ClojureLowering ctx, LispVal cell, LispVal fun, LispVal argList, String op) {
		return withAtom(ctx, cell, op, bound -> {
			LispSymbol next = ctx.freshTemp();
			LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), atomGet(bound), argList);
			LispVal update = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), fun, spread)))),
					ClojureLowerUtil.list(new LispSymbol("C%STM-CHECK"), bound, next));
			return txnGuard(update);
		});
	}

	/** {@code alter}/{@code commute} as a value: over a ref, a function and arguments. */
	static LispVal alterValue(ClojureLowering ctx, String op) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("alter-cell"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("alter-fun"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("alter-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, fun, ClojureLowering.AMPERSAND_REST, rest)),
				alterBuild(ctx, cell, fun, rest, op));
	}

	/** {@code (ref-set ref value)}: the value written through the validator. */
	static LispVal refSetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "ref-set takes a ref and a value");
		ctx.usedStm = true;
		LispVal value = ctx.lower(items.get(2));
		return withAtom(ctx, ctx.lower(items.get(1)), "ref-set", bound -> txnGuard(refSetBuild(ctx, bound, value)));
	}

	/** A {@code ref-set} over already-lowered forms: the validated write. */
	static LispVal refSetBuild(ClojureLowering ctx, LispVal bound, LispVal value) {
		LispSymbol next = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next, value))),
				ClojureLowerUtil.list(new LispSymbol("C%STM-CHECK"), bound, next));
	}

	/** {@code ref-set} as a value: a two-argument lambda over the write. */
	static LispVal refSetValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("ref-set-cell"));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("ref-set-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cell, value)),
				withAtom(ctx, cell, "ref-set", bound -> txnGuard(refSetBuild(ctx, bound, value))));
	}

	/**
	 * {@code (ensure ref)}: the ref itself, requiring a transaction like the oracle
	 * (where it also snapshots the ref for the transaction's read set).
	 */
	static LispVal ensureOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "ensure takes one ref");
		ctx.usedStm = true;
		return withAtom(ctx, ctx.lower(items.get(1)), "ensure", bound -> txnGuard(bound));
	}

	/**
	 * {@code (agent init & opts)}: an atom cell, like {@code ref}; a {@code :validator}
	 * runs on every {@code send}.
	 */
	static LispVal agentOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "agent takes an initial value");
		LispVal init = ctx.lower(items.get(1));
		LispVal validator = validatorOpt(ctx, items, 2, "agent");
		ctx.usedStm = true;
		if (validator == null) {
			return wrapAtom(init);
		}
		return checkedCell(ctx, init, validator);
	}

	/** {@code agent} as a value: a one-argument lambda over the constructor. */
	static LispVal agentValue(ClojureLowering ctx) {
		LispSymbol init = new LispSymbol(ClojureLowering.mangle("agent-init"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(init), wrapAtom(init));
	}

	/**
	 * {@code (send agent fun args...)} and {@code (send-off ...)}: the function applied
	 * now, through the validator, answering the agent. Agents run synchronously -- there
	 * is no thread pool on any backend, so async ordering is out and {@code await} is
	 * already past when it runs.
	 */
	static LispVal sendOf(ClojureLowering ctx, List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		ClojureLowerUtil.isTrue(items.size() >= 3, op + " takes an agent, a function and arguments");
		ctx.usedStm = true;
		return sendBuild(ctx, ctx.lower(items.get(1)), ClojureBindingLowering.fnValue(ctx, items.get(2)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)), op);
	}

	/**
	 * A {@code send} over already-lowered forms: the application with {@code *agent*}
	 * bound to the cell (its binding-depth counter one deeper), answering the cell.
	 */
	static LispVal sendBuild(ClojureLowering ctx, LispVal cell, LispVal fun, LispVal argList, String op) {
		return withAtom(ctx, cell, op, bound -> {
			LispSymbol next = ctx.freshTemp();
			LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), atomGet(bound), argList);
			LispVal update = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), fun, spread)))),
					ClojureLowerUtil.list(new LispSymbol("C%STM-CHECK"), bound, next));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(new LispSymbol("C%AGENT"), bound),
							ClojureVarLowering.specialDepthPair(ctx, "*agent*"))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), update, bound));
		});
	}

	/**
	 * {@code send}/{@code send-off} as a value: over an agent, a function and arguments.
	 */
	static LispVal sendValue(ClojureLowering ctx, String op) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("send-cell"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("send-fun"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("send-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, fun, ClojureLowering.AMPERSAND_REST, rest)),
				sendBuild(ctx, cell, fun, rest, op));
	}

	/**
	 * {@code (await agent...)}: every send already ran, so every agent is awaited; each
	 * is still checked, answering nil like the oracle.
	 */
	static LispVal awaitOf(ClojureLowering ctx, List<LispVal> items) {
		ctx.usedStm = true;
		List<LispVal> checks = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			checks.add(derefForm(ctx, ctx.lower(items.get(i))));
		}
		checks.add(ClojureLowering.NIL_CONST);
		if (checks.size() == 1) {
			return ClojureLowering.NIL_CONST;
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), checks);
	}

	/**
	 * One body lowered behind the {@code try} barrier with the tail position kept: a
	 * {@code recur} in it trips the oracle's {@code Cannot recur across try} refusal,
	 * while a target opened inside it still recurs. The {@code binding} and
	 * {@code with-open} bodies lower through here (the oracle wraps both in a
	 * {@code try}), the inits through plain {@code body}.
	 */
	static LispVal barrierBody(ClojureLowering ctx, List<LispVal> items, int from) {
		ctx.tryDepth++;
		try {
			return ctx.body(items, from);
		}
		finally {
			ctx.tryDepth--;
		}
	}

	/**
	 * {@code (binding [var init ...] body...)}: each var rebound around the body, like
	 * the oracle -- which is why only {@code ^:dynamic} vars and the {@code clojure.core}
	 * specials ({@link ClojureCoreSpecials}, each already a special variable) may be
	 * bound. Inits run sequentially, like {@code let}; the body reads each bound var
	 * through the var itself. Every bound var's binding-depth counter rebinds one deeper
	 * beside it, so {@code set!} tests at run time whether the var is thread-bound (a
	 * core special's counter is the one {@code thread-bound?} reads through its core var;
	 * a flag {@code clojure.main} binds has none). The body lowers behind the {@code try}
	 * barrier (the oracle wraps it in a {@code try/finally}), while the inits stay
	 * outside it.
	 */
	static LispVal bindingOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "binding needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "binding");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a binding vector pairs a var with a value");
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.size(); i += 2) {
			String name = ClojureLowerUtil.plainName(bindings.get(i), "binding");
			// a project var (own, referred, or qualified) rebinds its own special,
			// a core special its special variable -- a syntax-quote qualifies one
			// (`*out* reads clojure.core/*out*), and the oracle binds the qualified
			// spelling like the bare one; the body reads either through the var, so
			// neither is a local
			String key = name.startsWith(ClojureCoreNames.PREFIX) ? null : ctx.resolveVar(name);
			String special = key == null ? ClojureCoreSpecials.targetName(name) : null;
			LispVal init = ctx.lower(bindings.get(i + 1));
			if (special != null) {
				pairs.add(ClojureLowerUtil.list(ctx.specialValue(ClojureCoreSpecials.required(special)), init));
				LispVal depth = ClojureVarLowering.specialDepthPair(ctx, special);
				if (depth != null) {
					pairs.add(depth);
				}
			}
			else if (key == null || !ctx.dynamicVars.contains(key)) {
				throw new LispReadException("binding " + name + " needs a ^:dynamic var: only dynamic vars rebind");
			}
			else {
				pairs.add(ClojureLowerUtil.list(ClojureLowering.varSym(key), init));
				LispSymbol depth = ClojureLowering.boundDepthSym(key);
				pairs.add(ClojureLowerUtil.list(depth,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), depth, new LispInteger(1))));
			}
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
				barrierBody(ctx, items, 2));
	}

	/**
	 * {@code (with-open [name init ...] body...)}: the body with each value bound, closed
	 * in reverse order on every exit through {@code unwind-protect}. Closing calls the
	 * {@code close} method, so a Java closeable works where host objects exist (the
	 * interpreter and the JVM -- wasm rejects {@code java:}). A non-empty body lowers
	 * behind the {@code try} barrier (the oracle closes in a {@code finally}); an empty
	 * vector is the plain body, like the oracle's bare {@code do}.
	 */
	static LispVal withOpenOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-open needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "with-open");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a with-open binding vector pairs a name with a value");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		ctx.scopes.add(scope);
		ctx.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				String name = ClojureLowerUtil.plainName(bindings.get(i), "with-open");
				LispVal init = ctx.lower(bindings.get(i + 1));
				pairs.add(ClojureLowerUtil.list(ctx.localSym(name), init));
				names.add(name);
				scope.put(name, ClojureLowering.Kind.VARIABLE);
				if (ClojureLowerUtil.yieldsFun(init)) {
					ctx.markDirect(name);
				}
			}
			LispVal run = names.isEmpty() ? ctx.body(items, 2) : barrierBody(ctx, items, 2);
			if (names.isEmpty()) {
				return run;
			}
			List<LispVal> guarded = new ArrayList<>();
			guarded.add(ClojureLowerUtil.sym("unwind-protect"));
			guarded.add(run);
			for (int i = names.size() - 1; i >= 0; i--) {
				guarded.add(ClojureInteropLowering.instanceCall(ctx, ctx.localSym(names.get(i)), "close", List.of()));
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
					ClojureLowerUtil.list(guarded));
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
		}
	}

	/**
	 * {@code (with-out-str body...)}: the body with {@code *standard-output*} bound to a
	 * fresh string stream, answering what it printed. The stream is built with
	 * {@code make-string-output-stream} (never a literal {@code with-output-to-string},
	 * which would flip a WASM module into EH mode), like {@code str}. It binds
	 * {@code *out*}, so its binding-depth counter goes one deeper too.
	 */
	static LispVal withOutStrOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-out-str needs a body");
		LispSymbol stream = ctx.freshTemp();
		List<LispVal> run = new ArrayList<>(ctx.lowers(items, 1));
		run.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("get-output-stream-string"), stream));
		LispVal captured = run.size() == 1 ? run.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), run);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(stream,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("make-string-output-stream"))),
						ClojureLowerUtil.list(new LispSymbol("*STANDARD-OUTPUT*"), stream),
						ClojureVarLowering.specialDepthPair(ctx, "*out*"))),
				captured);
	}

	/**
	 * {@code (with-in-str s body...)}: the body with {@code *standard-input*} bound to a
	 * string input stream over {@code s}, so {@code read-line}, {@code read} and a
	 * {@code .read} of {@code *in*} read it. It binds {@code *in*}, so its binding-depth
	 * counter goes one deeper too. The body lowers behind the {@code try} barrier (the
	 * oracle closes the reader in a {@code with-open}).
	 */
	static LispVal withInStrOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-in-str needs a string and a body");
		List<LispVal> pairs = new ArrayList<>();
		pairs.add(ClojureLowerUtil.list(ctx.specialValue(ClojureCoreSpecials.required("*in*")),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("make-string-input-stream"), ctx.lower(items.get(1)))));
		pairs.add(ClojureVarLowering.specialDepthPair(ctx, "*in*"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
				barrierBody(ctx, items, 2));
	}

	/**
	 * {@code (time expr)}: the expression timed with {@code get-internal-real-time}
	 * (milliseconds here), reporting {@code Elapsed time: N.0 msecs} -- a double, like
	 * the oracle's {@code nanoTime} quotient, so a {@code \d+\.\d+} match over the report
	 * holds -- and answering the value. Only the value is deterministic -- the report's
	 * number never is, so the spec pins the shape, never the line.
	 */
	static LispVal timeOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "time takes one form");
		LispSymbol start = ctx.freshTemp();
		LispSymbol value = ctx.freshTemp();
		LispVal elapsed = ClojureLowerUtil.list(ClojureLowerUtil.sym("float"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("-"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("get-internal-real-time")), start),
				new LispDouble(1.0));
		// the report straight to the stream, like println of one string part
		List<LispVal> parts = new ArrayList<>();
		parts.add(ClojureStringLowering.strOf(ctx, LispString.literal("Elapsed time: "), LispString.literal(""),
				ClojureLowering.NIL_CONST));
		parts.add(ClojureStringLowering.strOf(ctx, elapsed, LispString.literal(""), ClojureLowering.NIL_CONST));
		parts.add(ClojureStringLowering.strOf(ctx, LispString.literal(" msecs"), LispString.literal(""),
				ClojureLowering.NIL_CONST));
		LispVal report = ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"),
				List.of(ClojureStringLowering.writeDatum(ctx, ClojureStringLowering.concat(ctx, parts),
						LispString.literal("nil"), ClojureLowering.NIL_CONST),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("terpri")), ClojureLowering.NIL_CONST));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(start,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("get-internal-real-time"))),
						ClojureLowerUtil.list(value, ctx.lower(items.get(1))))),
				report, value);
	}

	/**
	 * {@code (defonce name init?)}: {@code def} unless the name is already bound -- a
	 * reload keeps the root, like the oracle.
	 */
	static LispVal defonceForm(ClojureLowering ctx, List<LispVal> items) {
		return defonceForm(ctx, items, null);
	}

	/**
	 * A {@code defonce} lowered for a namespace init: the bound-or-set statement, plus --
	 * for a {@code ^:dynamic} var, into {@code hoisted} -- the declaim and the
	 * binding-depth counter, which stay top-level at the head where the collectors read
	 * them. A reload keeps the root through the same {@code boundp} test either way.
	 * @param ctx the lowering
	 * @param items the defonce datum's items
	 * @param hoisted where a dynamic var's top-level forms go, or null for the ordinary
	 * top-level shape
	 * @return the form
	 */
	static LispVal defonceForm(ClojureLowering ctx, List<LispVal> items, @Nullable List<LispVal> hoisted) {
		return defonceForm(ctx, items, hoisted, null);
	}

	/**
	 * A {@code defonce} that answers like the oracle's REPL: the var when it defined one,
	 * {@code nil} over a var that was already bound.
	 * @param ctx the lowering
	 * @param items the defonce datum's items
	 * @param echo the lowered var the definition answers, built after the definition
	 * lowered
	 * @return the form
	 */
	static LispVal defonceEchoing(ClojureLowering ctx, List<LispVal> items, Supplier<LispVal> echo) {
		return defonceForm(ctx, items, null, echo);
	}

	private static LispVal defonceForm(ClojureLowering ctx, List<LispVal> items, @Nullable List<LispVal> hoisted,
			@Nullable Supplier<LispVal> echo) {
		ClojureLowerUtil.isTrue(items.size() == 2 || items.size() == 3, "defonce takes a name and an optional value");
		LispVal nameDatum = items.get(1);
		String name = ClojureLowerUtil.plainName(nameDatum, "defonce");
		boolean dynamic = ClojureLowerUtil.nameIsDynamic(nameDatum);
		// Like def: the value lowers against the OLD binding first.
		LispVal value = items.size() == 3 ? ctx.lower(items.get(2)) : ClojureLowering.NIL_CONST;
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(nameDatum));
		ctx.globals.put(key, ClojureLowering.Kind.VARIABLE);
		ctx.macros.remove(key); // a definition wins over the macro it shadows
		if (dynamic) {
			ctx.dynamicVars.add(key);
		}
		ClojureDispatchLowering.recordClassDispatchFn(ctx, key, dynamic, items.size() == 3 ? items.get(2) : null);
		if (ClojureLowerUtil.yieldsFun(value)) {
			ctx.globalDirectFuns.add(key);
		}
		else {
			ctx.globalDirectFuns.remove(key);
		}
		LispSymbol var = ClojureLowering.varSym(key);
		LispVal set;
		if (dynamic && hoisted != null) {
			// in a namespace init the setq runs at load time, and the declaim
			// plus the counter ride top-level, where both collectors read them
			hoisted.add(ClojureLowering.declaimSpecial(var, ClojureLowering.boundDepthSym(key)));
			hoisted.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), ClojureLowering.boundDepthSym(key),
					new LispInteger(0)));
		}
		if (hoisted != null) {
			// a reload keeps the root through a runtime boundp probe of the var
			// itself: every store to a global feeds the eval mirror the compiled
			// boundp reads, so the init's own assignment is visible to the
			// probe and no set flag beside the var is needed. The probe survives
			// the compile-time boundp fold -- the init's assignment poisons the
			// name -- like the loaded flag's plain-variable test.
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("unless"), boundTest(ctx, key, var),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), var, value));
		}
		else {
			set = dynamic
					? ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), var, value),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"),
									ClojureLowering.boundDepthSym(key), new LispInteger(0)))
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), var, value);
		}
		if (echo != null) {
			set = ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), set, echo.get());
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), boundTest(ctx, key, var),
				echo != null ? ClojureLowering.NIL_CONST : var, set);
	}

	/**
	 * Whether a {@code defonce}'s var has a root: its global is bound and -- when a
	 * {@code declare} or a value-less {@code def} may have left it the unbound root --
	 * holds no unbound root. The root test is an {@code if}'s, so a program that makes no
	 * unbound root folds it to {@code t} ({@link ClojureArms}).
	 */
	private static LispVal boundTest(ClojureLowering ctx, String key, LispSymbol var) {
		LispVal boundp = ClojureLowerUtil.list(ClojureLowerUtil.sym("boundp"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), var));
		if (!ctx.unboundCapable.contains(key)) {
			return boundp;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), boundp,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-UNBOUND-P"), var),
						ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST));
	}

	/**
	 * {@code (defstruct name key...)}: the key vector behind the name, so {@code struct}
	 * builds maps with exactly those keys. Keys are keywords, like the oracle.
	 */
	static LispVal defstructForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "defstruct takes a name and keys");
		String name = ClojureLowerUtil.plainName(items.get(1), "defstruct");
		String struct = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(items.get(1)));
		ctx.globals.put(struct, ClojureLowering.Kind.VARIABLE);
		ctx.macros.remove(struct); // a definition wins over the macro it shadows
		List<LispVal> keys = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			LispVal key = items.get(i);
			if (!(key instanceof LispSymbol s) || !s.name().startsWith(":")) {
				throw new LispReadException("defstruct takes keyword keys, not " + key.print());
			}
			keys.add(ClojureCollectionLowering
				.keywordForm(ClojureCollectionLowering.resolveKeywordSpelling(ctx, s.name())));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowering.varSym(struct),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("vector"), keys));
	}

	/**
	 * {@code (struct struct-map value...)}: a fresh map pairing the struct's keys with
	 * the values, missing values nil. Too many values signal, like the oracle.
	 */
	static LispVal structOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "struct takes a struct and values");
		return structBuild(ctx, ctx.lower(items.get(1)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)), "struct");
	}

	/** A {@code struct} over an already-lowered key vector and value list. */
	static LispVal structBuild(ClojureLowering ctx, LispVal keys, LispVal values, String op) {
		LispSymbol slots = ctx.freshTemp();
		LispSymbol vals = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol have = ctx.freshTemp();
		LispSymbol want = ctx.freshTemp();
		LispSymbol index = ctx.freshTemp();
		LispVal fill = ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
				ClojureLowerUtil.list(List.of(index, want)),
				ClojureCollectionLowering.tablePut(table,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"), slots, index),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), index, have),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("nth"), index, vals),
								ClojureLowering.NIL_CONST)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(slots, keys), ClojureLowerUtil.list(vals, values),
						ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(have, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), vals)),
						ClojureLowerUtil.list(want, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), slots)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), have, want),
						ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
								LispString.literal("Too many arguments to " + op + " constructor")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), fill, table)));
	}

	/**
	 * {@code (struct-map struct-map key value...)}: a fresh map with the struct's keys
	 * (nil unless overridden here), like the oracle.
	 */
	static LispVal structMapOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "struct-map takes a struct and key/value pairs");
		List<LispVal> pairs = ctx.lowers(items, 2);
		ClojureLowerUtil.isTrue(pairs.size() % 2 == 0, "struct-map takes key/value pairs");
		LispSymbol slots = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol index = ctx.freshTemp();
		List<LispVal> run = new ArrayList<>();
		run.add(ClojureLowerUtil.sym("progn"));
		run.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
				ClojureLowerUtil.list(List.of(index, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), slots))),
				ClojureCollectionLowering.tablePut(table,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"), slots, index), ClojureLowering.NIL_CONST)));
		for (int i = 0; i < pairs.size(); i += 2) {
			run.add(ClojureCollectionLowering.tablePut(table, pairs.get(i), pairs.get(i + 1)));
		}
		run.add(table);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(slots, ctx.lower(items.get(1))),
						ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
				ClojureLowerUtil.list(run));
	}

	// dispatch: multimethods over a method table and a dispatcher defun

	/**
	 * The exception runtime, spliced once behind the false binding when the program
	 * throws, builds or reads an exception: the one condition class every exception of
	 * the program is (ex-info, a throwable construction, a thrown host
	 * {@code Throwable}), carrying a class chain ({@link ClojureThrowables}: the class's
	 * name, then its superclasses' -- what a catch tests), a message, data and a cause,
	 * whose report is the oracle's {@code toString}; {@code C%E-NEW} builds one and
	 * {@code C%E-PARTS} answers the four slots of one (NIL for any other value). The
	 * readers and builders over them are library functions ({@code clojure.lisp},
	 * "Exceptions"), which only a program carrying this runtime reaches. Pure lowering
	 * over the shared condition runtime, so every backend runs it unchanged.
	 */
	static List<LispVal> exInfoRuntime(ClojureLowering ctx) {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol cls = new LispSymbol("C%E-EXCEPTION");
		LispSymbol cond = new LispSymbol("c");
		LispSymbol stream = new LispSymbol("s");
		LispSymbol ex = new LispSymbol("e");
		String[] slots = { "CLASS", "MESSAGE", "DATA", "CAUSE" };
		List<LispVal> slotSpecs = new ArrayList<>();
		List<LispVal> readers = new ArrayList<>();
		List<LispVal> params = new ArrayList<>();
		List<LispVal> initargs = new ArrayList<>();
		initargs.add(ClojureLowerUtil.sym("make-condition"));
		initargs.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cls));
		for (String slot : slots) {
			LispSymbol reader = new LispSymbol("C%E-EXCEPTION-" + slot);
			LispSymbol param = new LispSymbol(slot);
			LispSymbol initarg = ClojureLowerUtil.sym(":" + slot);
			slotSpecs.add(ClojureLowerUtil.list(new LispSymbol("C%E-" + slot), ClojureLowerUtil.sym(":initarg"),
					initarg, ClojureLowerUtil.sym(":reader"), reader));
			readers.add(reader);
			params.add(param);
			initargs.add(initarg);
			initargs.add(param);
		}
		LispVal report = ClojureLowerUtil.list(ClojureLowerUtil.sym(":report"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cond, stream)), ClojureLowerUtil.list(
					ClojureLowerUtil.sym("write-string"),
					ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-EXCEPTION-STRING"),
							ClojureLowerUtil.list(readers.get(0), cond), ClojureLowerUtil.list(readers.get(1), cond),
							ClojureLowerUtil.list(readers.get(2), cond)),
					stream)));
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("define-condition"), cls,
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.sym("error"))), ClojureLowerUtil.list(slotSpecs),
				report));
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%E-NEW"),
				ClojureLowerUtil.list(params), ClojureLowerUtil.list(initargs)));
		List<LispVal> parts = new ArrayList<>();
		parts.add(ClojureLowerUtil.sym("list"));
		for (LispVal reader : readers) {
			parts.add(ClojureLowerUtil.list(reader, ex));
		}
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%E-PARTS"),
				ClojureLowerUtil.list(List.of(ex)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("typep"), ex,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cls)),
						ClojureLowerUtil.list(parts), ClojureLowering.NIL_CONST)));
		return runtime;
	}

}
