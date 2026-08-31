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
 * What is deliberately NOT here (the measured residue, each still answering an immutable
 * value): {@code princ-to-string} / {@code prin1-to-string} / {@code write-to-string}
 * (the static {@code format} lowering emits its {@code ~a} / {@code ~s} pieces through
 * the same case, so wrapping it would put a convert-and-render round trip on every piece
 * of every literal-control format), a computed (non-literal-{@code nil}) {@code format}
 * destination that turns out to be {@code nil} at run time, the first-class
 * {@code #'format} / {@code #'concatenate} wrapper bodies (they build through the
 * renderer / {@code %string-concat} directly, not through the wrapped cases),
 * {@code string-trim} family, {@code map 'string} / {@code coerce 'string} /
 * {@code reverse} / {@code remove} / {@code substitute} string results,
 * {@code symbol-name} / {@code gensym} names (CLHS leaves {@code symbol-name} mutation
 * undefined; keeping the name immutable is deliberate), and the getenv / fetch / socket
 * read results.
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
			LispNames.GET_OUTPUT_STREAM_STRING, LispNames.READ_LINE,
			// The %io-read-line fallback alias a component's socket splice routes a
			// non-socket read-line through -- the public name may be rewritten away
			// before this scan runs, so the alias keeps the gate on.
			LispNames.READ_LINE_RAW_INTERNAL);

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

}
