package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The one home of "which string PRODUCERS answer a MUTABLE character vector on the
 * compile backends" -- the flip that gives a {@code concatenate} / case-family /
 * {@code format nil} / string-stream-capture / {@code read-line} result the writable
 * identity a Common Lisp string has (two aliases of one string see each other's writes),
 * matching the interpreter and SBCL. {@code subseq} / {@code copy-seq} flipped first
 * ({@code .kb/string-write-runtime.md}, "A copy-seq/subseq result is mutable with
 * identity"); this class carries the second round.
 *
 * <p>
 * Both compile backends wrap the flipped producers' results through one runtime helper
 * ({@code _toMutStr} on the JVM, {@code _to_mut_str} on WASM: a fresh mutable character
 * vector for a string input, anything else passed through), and both wrap ONLY when
 * {@link #programUsesAny} says the program contains a flipped producer -- the same
 * pre-expansion source scan on both, so the backends cannot disagree about whether a
 * producer's result carries identity. On the JVM the scan also joins the array-runtime
 * gate ({@code programUsesAnyArrayOp}), so a wrap site always has the character-vector
 * runtime to call into.
 *
 * <p>
 * The third round ({@code .todo/600}) added the {@code string-trim} family, a
 * PROGRAM-WRITTEN {@code (map 'string ...)} / {@code (coerce seq 'string)} (matched by
 * shape, so the sequence operators' own result conversion -- which carries
 * {@link am.ik.rontolisp.LispNames#SEQ_STRING_RESULT} -- stays out) and the host
 * environment read behind {@code uiop:getenv}.
 *
 * <p>
 * What is deliberately NOT here, each with the number that says why in
 * {@code .kb/string-write-runtime.md}: {@code princ-to-string} / {@code prin1-to-string}
 * / {@code write-to-string} (the expander builds pieces with them at ~25 sites,
 * {@code map 'string}'s per-element accumulator included, so wrapping the shared case
 * costs 17-80% on the whole string-building family -- it needs an internal piece alias
 * first), {@code reverse} / {@code remove} / {@code substitute} / {@code sort} over a
 * string (the gate cannot tell a string sequence from a list one, so a list-only program
 * would pay the JVM array runtime: +6,735 bytes of class on
 * {@code examples/console/nqueens}), a computed (non-literal-{@code nil}) {@code format}
 * destination and a computed {@code coerce} result type that turn out to name a string at
 * run time (same gate problem), and {@code symbol-name} / {@code gensym} names (CLHS
 * leaves {@code symbol-name} mutation undefined; keeping the name immutable is
 * deliberate). The {@code #'format} wrapper needs nothing -- it renders through
 * {@code %fmt-cat}, which is a {@code concatenate 'string} -- and {@code #'concatenate}
 * wraps its own reduce in {@code %str-fresh}.
 */
public final class MutableStringProducers {

	/**
	 * The producers the scan looks for by NAME, anywhere in a form: the
	 * {@code concatenate} string family (any {@code concatenate} matches -- the family
	 * split costs more than the over-approximation), the case family, the two
	 * string-stream captures, and {@code read-line}. {@code format} is matched by shape
	 * instead ({@link #isFormatToString}), so a program that only ever formats to a
	 * stream stays out of the gate.
	 */
	private static final List<String> PRODUCER_NAMES = List.of(LispNames.CONCATENATE, LispNames.STRING_UPCASE,
			LispNames.STRING_DOWNCASE, LispNames.STRING_CAPITALIZE, LispNames.WITH_OUTPUT_TO_STRING,
			LispNames.GET_OUTPUT_STREAM_STRING, LispNames.READ_LINE, LispNames.STRING_TRIM, LispNames.STRING_LEFT_TRIM,
			LispNames.STRING_RIGHT_TRIM,
			// The host environment read behind uiop:getenv. The public name is a
			// spliced Lisp defun over it, so the scan (which runs with the libraries
			// already spliced) sees this one on every backend.
			LispNames.HOST_GETENV,
			// The %io-read-line fallback alias a component's socket splice routes a
			// non-socket read-line through -- the public name may be rewritten away
			// before this scan runs, so the alias keeps the gate on.
			LispNames.READ_LINE_RAW_INTERNAL,
			// The fold-produced fresh-string constant: the pure-builtin fold may have
			// folded every producer NAME away, leaving only (%str-fresh ...) forms,
			// and those need the wrap (and, on the JVM, the array runtime) exactly
			// like the calls they replaced.
			LispNames.STR_FRESH);

	private MutableStringProducers() {
	}

	/**
	 * Whether the program (pre-expansion source, libraries already spliced) contains any
	 * flipped producer, i.e. whether the backends emit the mutable-result wrap at the
	 * producer sites. A first-class {@code #'string-upcase} etc. matches too (the symbol
	 * appears inside the {@code function} form), which is what keeps a wrapper body's own
	 * producer site consistent with call position.
	 * @param program the top-level forms
	 * @return {@code true} when at least one flipped producer appears
	 */
	public static boolean programUsesAny(List<LispVal> program) {
		for (LispVal form : program) {
			if (usesAny(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesAny(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym) {
			String name = sym.name();
			if (PRODUCER_NAMES.contains(name)) {
				return true;
			}
			if (LispNames.FORMAT.equals(name) && isFormatToString(cons)) {
				return true;
			}
			if (isMapToString(cons) || isCoerceToString(cons)) {
				return true;
			}
		}
		return usesAny(cons.car()) || usesAny(cons.cdr());
	}

	/**
	 * Whether this {@code format} call captures its output as a string the caller gets
	 * back -- a LITERAL {@code nil} destination. A computed destination that is
	 * {@code nil} at run time also answers a string, but stays un-flipped (see the class
	 * comment): only the literal spelling is a producer here, on every backend alike.
	 * @param cons a form whose head is {@code format}
	 * @return {@code true} when the destination is a literal {@code nil}
	 */
	public static boolean isFormatToString(LispCons cons) {
		return cons.cdr() instanceof LispCons rest && rest.car() instanceof LispNil;
	}

	/**
	 * Whether this form is a {@code map} whose result type is a LITERAL string designator
	 * -- the one {@code map} shape that BUILDS a string, and the shape a program-written
	 * {@code (coerce x 'string)} would build with if the expansion did not give its own
	 * conversion the internal designator. {@code coerce} carries its wrap in the
	 * EXPANSION instead ({@code LispMacroExpander.coerceToStringBody}), because only its
	 * build arm allocates: a string input must come back BY IDENTITY (CLHS, and SBCL
	 * answers the argument itself), and wrapping the whole {@code coerce} would convert
	 * every argument the {@code %seq-string} normalizer passes through -- i.e. every
	 * argument of every {@code concatenate}.
	 * @param form any form
	 * @return {@code true} for {@code (map 'string ...)} and its designator spellings
	 */
	public static boolean isMapToString(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.MAP.equals(head.name()) && cons.cdr() instanceof LispCons rest
				&& isStringTypeDesignator(rest.car());
	}

	/**
	 * Whether this form is a {@code (coerce x 'string)} -- which the gate must recognize
	 * even though the wrap sits on the {@code map} the expansion builds, because the scan
	 * runs on the program BEFORE that expansion exists.
	 * @param form any form
	 * @return {@code true} for a literal {@code 'string} result designator
	 */
	public static boolean isCoerceToString(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.COERCE.equals(head.name()) && cons.cdr() instanceof LispCons rest
				&& rest.cdr() instanceof LispCons typeCell && isStringTypeDesignator(typeCell.car());
	}

	// A quoted string type designator, in the spellings expandMap normalizes:
	// 'string / 'simple-string / 'base-string / 'simple-base-string, package
	// qualification included.
	private static boolean isStringTypeDesignator(LispVal form) {
		if (!(form instanceof LispCons quote && quote.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && quote.cdr() instanceof LispCons datum)) {
			return false;
		}
		// A COMPOUND spec -- '(string 3), '(simple-string) -- is the same conversion
		// spelled with a length (expandCoerce's quotedCompoundTypeHead collapses it),
		// so the gate has to see it or that spelling would go un-flipped.
		LispVal head = datum.car() instanceof LispCons compound ? compound.car() : datum.car();
		if (!(head instanceof LispSymbol type)) {
			return false;
		}
		// The member half of a package-qualified name, without importing the reader's
		// registry (the compiler package does not depend on reader).
		String name = type.name();
		int colon = name.lastIndexOf(':');
		String member = colon < 0 ? name : name.substring(colon + 1);
		return "STRING".equals(member) || "SIMPLE-STRING".equals(member) || "BASE-STRING".equals(member)
				|| "SIMPLE-BASE-STRING".equals(member);
	}

}
