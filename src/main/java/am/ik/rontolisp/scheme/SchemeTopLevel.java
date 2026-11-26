package am.ik.rontolisp.scheme;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * One top-level datum of a {@link SchemeSession} buffer, lowered.
 *
 * @param forms the Common Lisp forms to evaluate, in order; the value of the LAST one is
 * the datum's
 * @param echoes whether that value is worth showing: a definition, an import, a
 * {@code set!} and a procedure called for its effect ({@code display}) have none
 */
public record SchemeTopLevel(List<LispVal> forms, boolean echoes) {
}
