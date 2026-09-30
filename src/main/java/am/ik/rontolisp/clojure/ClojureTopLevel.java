package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * One top-level datum of a {@link ClojureSession} buffer, lowered.
 *
 * @param forms the Common Lisp forms to evaluate, in order; the value of the LAST one is
 * the datum's
 * @param echoes whether that value is worth showing
 */
public record ClojureTopLevel(List<LispVal> forms, boolean echoes) {
}
