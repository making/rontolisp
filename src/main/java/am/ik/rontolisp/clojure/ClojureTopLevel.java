package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * One top-level datum of a {@link ClojureSession} buffer, lowered.
 *
 * @param forms the Common Lisp forms to evaluate, in order; the value of the LAST one is
 * the datum's
 * @param echoes whether that value is worth showing
 * @param location where the datum starts in the buffer, or null for an entry no datum
 * wrote (the runtimes travelling ahead of a buffer) or a datum read without positions
 */
public record ClojureTopLevel(List<LispVal> forms, boolean echoes, @Nullable SourceLocation location) {

	/**
	 * An entry with no position.
	 * @param forms the forms
	 * @param echoes whether the last one's value is worth showing
	 */
	public ClojureTopLevel(List<LispVal> forms, boolean echoes) {
		this(forms, echoes, null);
	}

}
