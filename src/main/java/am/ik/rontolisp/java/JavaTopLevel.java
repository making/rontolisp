package am.ik.rontolisp.java;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * One step of a {@link JavaSession} buffer, lowered: the setup a buffer's run starts
 * with, or one top-level statement, expression or variable.
 *
 * @param forms the Common Lisp forms to evaluate, in order; the value of the LAST one is
 * the step's
 * @param echoes whether that value is shown: the text {@code label ==> value} of an
 * expression or a variable, {@code nil} for a {@code void} expression
 */
public record JavaTopLevel(List<LispVal> forms, boolean echoes) {
}
