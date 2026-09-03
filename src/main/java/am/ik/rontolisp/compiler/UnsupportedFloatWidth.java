package am.ik.rontolisp.compiler;

import java.util.Locale;

import am.ik.rontolisp.FloatWidth;
import am.ik.rontolisp.LispNames;

/**
 * The one refusal a backend that does not carry a packed float width raises. A width that
 * silently degrades to a boxed array on one backend while staying packed on another
 * breaks the cross-backend identity contract, and the failure a user meets then is a
 * WRONG NUMBER rather than a crash -- so the refusal has to name the width and the
 * backend, and it has to happen where the representation is chosen rather than at the
 * first read.
 *
 * <p>
 * This is the PERMANENT kind of refusal, and the kind is carried by the exception type
 * and the phase rather than by the wording ({@code .kb/bfloat16.md}): an
 * {@code UnsupportedOperationException} thrown on the COMPILE path, which the frontend
 * gives a source position ({@code SourceProvenance.noteFailure}). A width an
 * implementation simply has not got to yet is the other kind -- a
 * {@code LispEvalException} at run time whose message says "does not yet" -- and the two
 * must not be spelled alike, because prose is not something a test can check.
 */
public final class UnsupportedFloatWidth {

	private UnsupportedFloatWidth() {
	}

	/**
	 * Refuses a width on a backend that does not carry it.
	 * @param width the width the program asked for
	 * @param backend the backend, named as the message will read it (for example
	 * {@code "the wasm-GC backend"})
	 * @return the exception to throw
	 */
	public static UnsupportedOperationException refuse(FloatWidth width, String backend) {
		return new UnsupportedOperationException(message(width, backend));
	}

	/**
	 * The same sentence as text, for a backend that lowers the refusal to a CALL-TIME
	 * signal instead of throwing here -- the shape a width-dispatching arm in a spliced
	 * library needs, since that arm is compiled on every backend and is provably dead on
	 * the ones that refuse the width. The user reads one sentence either way.
	 * @param width the width the program asked for
	 * @param backend the backend, named as the message will read it
	 * @return the message
	 */
	public static String message(FloatWidth width, String backend) {
		return lispName(width) + " arrays are supported on the interpreter and the JVM only, not on " + backend;
	}

	/**
	 * The Lisp element-type name of a width, as a program spells it. An exhaustive switch
	 * EXPRESSION, so a width added to {@link FloatWidth} has to be named here rather than
	 * inheriting whichever arm came last -- the statement form would not be checked, the
	 * selector being an enum ({@code .kb/vec.md}).
	 * @param width the width
	 * @return the element-type name, lowercase as a program writes it
	 */
	private static String lispName(FloatWidth width) {
		String name = switch (width) {
			case SINGLE -> LispNames.SINGLE_FLOAT;
			case DOUBLE -> LispNames.DOUBLE_FLOAT;
			case BFLOAT16 -> LispNames.BFLOAT16;
		};
		return name.toLowerCase(Locale.ROOT);
	}

}
