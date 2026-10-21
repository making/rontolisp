package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * {@code #=} read-eval in Clojure source: {@code #=form} evaluates while the program
 * lowers -- on the compile host -- and the answer stands as the datum where the form
 * stood, so a compiled artifact carries the RESULT as a literal. The evaluation reuses
 * the macro-time evaluator ({@link ClojureMacroEvaluator}), which holds every definition
 * lowered above the site, like the oracle's form-by-form load; it runs before any macro
 * sees the enclosing form.
 *
 * <p>
 * The oracle's rule, measured on {@code clj} 1.12.6.1673: a symbol answers the class of
 * the name ({@code #=java.lang.String} is the class); a list resolves its head in the
 * current namespace and applies the var -- as a function -- to the UNEVALUATED rest, so
 * special forms never resolve ({@code #=(if true 1 2)} is {@code Can't resolve if}) and a
 * macro's function runs on the raw arguments (of none or one, {@code Wrong number of
 * args}); anything else is {@code Unsupported #= form}. {@code *read-eval*} gates the
 * read ({@code EvalReader not allowed when *read-eval* is false}).
 */
final class ClojureReadEval {

	private ClojureReadEval() {
	}

	/**
	 * The datum with every {@code #=} marker evaluated: a post-order walk, so a nested
	 * {@code #=} resolves before the form around it, and a {@code quote} never shields
	 * one -- the oracle evaluates at read time, before the quote is seen. Datums with no
	 * marker are answered unchanged.
	 * @param ctx the hub, between two top-level datums
	 * @param datum the datum as read
	 * @return the datum with the markers' answers in their place
	 */
	static LispVal resolve(ClojureLowering ctx, LispVal datum) {
		if (!ClojureLowerUtil.mentions(datum, "%read-eval")) {
			return datum;
		}
		return resolveDatum(ctx, datum);
	}

	/**
	 * The datum with every {@code #=} marker evaluated: a post-order walk, so a nested
	 * {@code #=} resolves before the form around it, and a {@code quote} never shields
	 * one -- the oracle evaluates at read time, before the quote is seen.
	 */
	private static LispVal resolveDatum(ClojureLowering ctx, LispVal datum) {
		if (!(datum instanceof LispCons cons) || cons.car() != ClojureReader.READ_EVAL) {
			if (!(datum instanceof LispCons pair)) {
				return datum;
			}
			LispVal car = resolveDatum(ctx, pair.car());
			LispVal cdr = resolveDatum(ctx, pair.cdr());
			if (car == pair.car() && cdr == pair.cdr()) {
				return datum;
			}
			return new LispCons(car, cdr);
		}
		if (!(cons.cdr() instanceof LispCons rest) || !(rest.cdr() instanceof LispNil)) {
			throw positioned(ctx, datum, "Unsupported #= form");
		}
		LispVal form = resolveDatum(ctx, rest.car());
		return readEval(ctx, datum, ClojureLowerUtil.stripMeta(form));
	}

	/**
	 * What a top-level {@code set!} sets {@code *read-eval*} to, when its value is a
	 * literal: false for {@code false} and {@code nil}, true for any other literal; null
	 * when it sets something else or a computed value, which a lowering cannot know.
	 * Mirrors the {@code *assert*} rule ({@code assertEnabled}), which the oracle reads
	 * likewise where it expands.
	 * @param ctx the hub
	 * @param items the {@code set!} form, head included
	 * @return the literal's truth, or null
	 */
	static @Nullable Boolean readEvalSetTo(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() != 3 || !(ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol target)) {
			return null;
		}
		String name = target.name();
		boolean programVar = !name.startsWith(ClojureCoreNames.PREFIX) && ctx.resolveVar(name) != null;
		if (ctx.localKind(name) != null || programVar || !"*read-eval*".equals(ClojureCoreSpecials.targetName(name))) {
			return null;
		}
		LispVal value = items.get(2);
		if (value instanceof LispSymbol symbol) {
			return switch (symbol.name()) {
				case "false", "nil" -> false;
				case "true" -> true;
				default -> symbol.name().startsWith(":") ? Boolean.TRUE : null;
			};
		}
		return value instanceof LispCons ? null : Boolean.TRUE;
	}

	/**
	 * One {@code #=} marker evaluated: the oracle's branches over the form. A symbol
	 * answers its class; a list applies its head's var to the unevaluated rest; anything
	 * else is refused like the oracle's {@code Unsupported #= form}.
	 * @param ctx the hub
	 * @param marker the marker datum, for positions
	 * @param form the form, nested markers resolved and reader metadata stripped
	 * @return the answer, decoded back to a datum
	 */
	private static LispVal readEval(ClojureLowering ctx, LispVal marker, LispVal form) {
		if (!ctx.readEvalEnabled) {
			throw positioned(ctx, marker, "EvalReader not allowed when *read-eval* is false.");
		}
		if (form instanceof LispSymbol symbol) {
			return readEvalClass(ctx, marker, symbol.name());
		}
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null || items.isEmpty() || !(items.get(0) instanceof LispSymbol head)
				|| isMarkerHead(items.get(0))) {
			throw positioned(ctx, marker, "Unsupported #= form");
		}
		if (head.name().equals("var")) {
			// the var itself, like the oracle's (var sym) head: lowered and run,
			// so an unqualified name resolves in the current namespace
			return evaluate(ctx, marker, form);
		}
		return readEvalCall(ctx, marker, head.name(), items.subList(1, items.size()));
	}

	/**
	 * Whether a symbol heads a reader marker rather than a call: a collection literal, a
	 * record, a regex, a tagged or pending literal, a read-eval marker, a default
	 * reader's construction, or reader metadata the strip missed. Such a form is no call,
	 * like the oracle's non-list datums.
	 */
	private static boolean isMarkerHead(LispVal head) {
		return head == ClojureReader.VECTOR || ClojureLowerUtil.isSymbolNamed(head, "%hash-map")
				|| ClojureLowerUtil.isSymbolNamed(head, "%hash-set") || head == ClojureReader.REGEX
				|| head == ClojureReader.RECORD || head == ClojureReader.TAGGED || head == ClojureReader.PENDING_TAG
				|| head == ClojureReader.READ_EVAL || ClojureLowerUtil.isSymbolNamed(head, ClojureLowerUtil.READER_META)
				|| ClojureDefaultReaders.isMarker(head);
	}

	/**
	 * A bare symbol's class, like the oracle's {@code RT.classForName}: the class object,
	 * which has no literal spelling here, so a found class is refused where the oracle
	 * embeds it, and a missing one is refused by its name.
	 */
	private static LispVal readEvalClass(ClojureLowering ctx, LispVal marker, String name) {
		Class<?> found;
		try {
			found = Class.forName(name, false, ctx.files.javaClassLoader());
		}
		catch (ClassNotFoundException | LinkageError ex) {
			throw positioned(ctx, marker, name);
		}
		return decode(ctx, marker, new LispJavaObject(found));
	}

	/**
	 * A call form's head applied to its unevaluated arguments: the var the head resolves
	 * to -- a program definition above the site, a referred or aliased one, or a core or
	 * known-library value -- called on the quoted arguments in the macro-time evaluator.
	 * A macro head is the oracle's arity refusal; an unknown head the oracle's
	 * {@code Can't resolve}.
	 */
	private static LispVal readEvalCall(ClojureLowering ctx, LispVal marker, String head, List<LispVal> args) {
		ClojureMacroEvaluator evaluator = ctx.macroEvaluator;
		if (evaluator == null) {
			throw positioned(ctx, marker, "read-eval cannot run without a macro evaluator (lower with one)");
		}
		@Nullable String key = qualifiedKey(ctx, head);
		ClojureLowering.@Nullable Kind kind = null;
		if (key != null) {
			kind = ctx.globals.get(key);
		}
		LispVal call;
		if (key != null && kind == ClojureLowering.Kind.FUNCTION) {
			LispSymbol fn = ctx.currentDefnSym(key);
			call = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("fboundp"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), fn)),
					applied(ctx, fn, args), ClojureDataReaders.UNBOUND);
		}
		else if (key != null && (kind == ClojureLowering.Kind.VARIABLE || kind == ClojureLowering.Kind.DECLARED)) {
			LispSymbol cell = ClojureLowering.varSym(key);
			call = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("boundp"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cell)),
					ClojureLowerUtil.list(ClojureDataReaders.CALL, cell, quotedList(ctx, args)),
					ClojureDataReaders.UNBOUND);
		}
		else if (kind == ClojureLowering.Kind.MACRO
				|| key == null && head.indexOf('/') < 0 && ClojureCoreNames.isMacro(head)) {
			// a macro's function runs on the raw arguments: of none or one the
			// oracle's arity refusal, which is all this answers with
			String var = key == null ? "clojure.core/" + head : key;
			throw positioned(ctx, marker, "Wrong number of args (" + args.size() + ") passed to: " + var);
		}
		else {
			LispVal value = key != null ? null : libraryValue(ctx, head);
			if (value == null) {
				throw positioned(ctx, marker, "Can't resolve " + head);
			}
			call = ClojureLowerUtil.list(ClojureDataReaders.CALL, value, quotedList(ctx, args));
		}
		ClojureMacroLowering.handOverCaughtClasses(ctx, evaluator);
		LispVal invocation = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureCoreSpecials.NS, ClojureCoreSpecials.namespaceObject(ctx.currentNs)),
				ClojureLowerUtil.list(ClojureCoreSpecials.FILE, LispString.literal(ctx.loadingFile)),
				ClojureLowerUtil.list(ClojureCoreSpecials.SOURCE_PATH, LispString.literal(ctx.loadingSourcePath))),
				call);
		LispVal value;
		try {
			value = evaluator.evaluate(invocation);
		}
		catch (RuntimeException ex) {
			throw positioned(ctx, marker, ClojureMacroLowering.exMessage(ex));
		}
		if (value instanceof LispSymbol answered && answered.name().equals(ClojureDataReaders.UNBOUND.name())) {
			// declared above or defined below, never bound where read: the
			// oracle's load never saw the var, so neither did this read
			throw positioned(ctx, marker, "Can't resolve " + head);
		}
		return decode(ctx, marker, value);
	}

	/**
	 * The var key a call head resolves to without the privacy refusal a call site
	 * answers: the oracle calls a private var too. Null for a name no program var claims,
	 * which a core or library value may still answer.
	 */
	private static @Nullable String qualifiedKey(ClojureLowering ctx, String head) {
		try {
			return ctx.lookupVar(head);
		}
		catch (LispReadException ex) {
			return null;
		}
	}

	/**
	 * The value a head no program var claims calls: a {@code clojure.core} var's, a known
	 * library var's, else null.
	 */
	private static @Nullable LispVal libraryValue(ClojureLowering ctx, String head) {
		int slash = ClojureLowering.qualifierSlash(head);
		if (slash > 0) {
			String ns = head.substring(0, slash);
			String name = head.substring(slash + 1);
			String aliased = ctx.ns().aliases.get(ns);
			String target = aliased != null ? aliased : ns;
			if (ns.equals("clojure.core") || "clojure.core".equals(target)) {
				return ClojureCoreNames.isMacro(name) ? null : ctx.coreValueOrNull(name);
			}
			if (ClojureNamespaceLowering.isKnownVar(target, name)) {
				return ClojureNamespaceLowering.namespaceValue(ctx, new ClojureLowering.VarRef(target, name));
			}
			return null;
		}
		return ctx.coreValueOrNull(head);
	}

	/** A function applied to the quoted unevaluated arguments. */
	private static LispVal applied(ClojureLowering ctx, LispSymbol fn, List<LispVal> args) {
		List<LispVal> call = new ArrayList<>();
		call.add(fn);
		for (LispVal arg : args) {
			call.add(ctx.quote(arg));
		}
		return ClojureLowerUtil.list(call);
	}

	/** The quoted unevaluated arguments as one list, for the IFn dispatcher. */
	private static LispVal quotedList(ClojureLowering ctx, List<LispVal> args) {
		List<LispVal> quoted = new ArrayList<>();
		for (LispVal arg : args) {
			quoted.add(ctx.quote(arg));
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), quoted);
	}

	/**
	 * A whole form lowered detached and run in the macro-time evaluator, decoded back to
	 * a datum: the {@code (var sym)} head, whose var machinery the form needs.
	 */
	private static LispVal evaluate(ClojureLowering ctx, LispVal marker, LispVal form) {
		ClojureMacroEvaluator evaluator = ctx.macroEvaluator;
		if (evaluator == null) {
			throw positioned(ctx, marker, "read-eval cannot run without a macro evaluator (lower with one)");
		}
		ClojureMacroLowering.handOverCaughtClasses(ctx, evaluator);
		LispVal value;
		try {
			value = evaluator.evaluate(ctx.lowerDetached(form));
		}
		catch (RuntimeException ex) {
			throw positioned(ctx, marker, ClojureMacroLowering.exMessage(ex));
		}
		return decode(ctx, marker, value);
	}

	/**
	 * A macro-time answer back to a datum, like a macro's: anything with no literal
	 * spelling is the oracle's {@code Can't embed object in code}.
	 */
	private static LispVal decode(ClojureLowering ctx, LispVal marker, LispVal value) {
		try {
			return ClojureMacroLowering.decodeDatum(ctx, value);
		}
		catch (LispReadException ex) {
			throw positioned(ctx, marker,
					"Can't embed object in code, maybe print-dup not defined: " + strOf(ctx, value));
		}
	}

	/** {@code str} of a macro-time answer, for a refusal. */
	private static String strOf(ClojureLowering ctx, LispVal value) {
		ClojureMacroEvaluator evaluator = ctx.macroEvaluator;
		if (evaluator != null) {
			try {
				LispVal str = evaluator.evaluate(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-STR-OF"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), value), LispString.literal(""),
						ClojureLowering.NIL_CONST));
				if (str instanceof LispString text) {
					return text.value();
				}
			}
			catch (RuntimeException ex) {
				// the value as the evaluator prints it
			}
		}
		return value.print();
	}

	/** A refusal positioned at the marker where the reader read it. */
	private static LispReadException positioned(ClojureLowering ctx, LispVal marker, String message) {
		if (ctx.reader == null) {
			return new LispReadException(message);
		}
		SourceLocation at = ctx.reader.locate(marker);
		if (at == null) {
			return new LispReadException(message);
		}
		return new LispReadException(message, at);
	}

}
