package am.ik.rontolisp.starlark;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * One step of a {@link StarlarkSession} buffer, lowered: the setup a buffer's run starts
 * with, or one top-level statement.
 *
 * @param forms the Common Lisp forms to evaluate, in order; the value of the LAST one is
 * the step's
 * @param echoes whether that value is shown: an expression statement's is, unless it is
 * {@code None}
 */
public record StarlarkTopLevel(List<LispVal> forms, boolean echoes) {
}
