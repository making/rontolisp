package am.ik.rontolisp.eval;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispVal;

/**
 * Thread-scoped dynamic (special) variable bindings for the interpreter.
 *
 * <p>
 * Common Lisp special variables have <em>dynamic extent</em>:
 * {@code (let ((*x* v)) body)} on a special {@code *x*} establishes a binding visible to
 * every function called during {@code body} (not just lexically nested code), restored on
 * any exit -- normal return, a non-local exit ({@code return}), or an error unwind. This
 * class holds those bindings.
 *
 * <p>
 * Bindings are <strong>per thread of control</strong>: the map lives in a
 * {@link ThreadLocal}, so the HTTP handler server (one virtual thread per request,
 * sharing the single global {@link Environment}) never lets one request's dynamic
 * bindings leak into a concurrent request. The instance is owned by one
 * {@link LispEvaluator}, so two independent evaluators sharing a thread do not collide
 * either.
 *
 * <p>
 * Each special name maps to a stack of values (top = current binding); nested
 * {@code let}s push and pop, and {@code setq} of a bound special replaces the top. A name
 * with an empty stack is not dynamically bound -- its value is the global default held in
 * the evaluator's global environment (shallow-binding fallback), which is what the read
 * path consults when {@link #isBound(String)} is false.
 */
final class DynamicBindings {

	private final ThreadLocal<Map<String, Deque<LispVal>>> stacks = ThreadLocal.withInitial(HashMap::new);

	/**
	 * Returns whether the given special name currently has a dynamic binding on this
	 * thread.
	 * @param name the variable name
	 * @return {@code true} if a dynamic binding is in effect
	 */
	boolean isBound(String name) {
		Deque<LispVal> stack = this.stacks.get().get(name);
		return stack != null && !stack.isEmpty();
	}

	/**
	 * Returns the current (innermost) dynamic value of a bound special. The caller must
	 * have checked {@link #isBound(String)}.
	 * @param name the variable name
	 * @return the current dynamic value
	 */
	LispVal get(String name) {
		Deque<LispVal> stack = Objects.requireNonNull(this.stacks.get().get(name), name);
		return Objects.requireNonNull(stack.peek(), name);
	}

	/**
	 * Replaces the current (innermost) dynamic value of a bound special. The caller must
	 * have checked {@link #isBound(String)}. Used by {@code setq}/{@code setf} of a
	 * dynamically bound special.
	 * @param name the variable name
	 * @param value the new value
	 */
	void setCurrent(String name, LispVal value) {
		Deque<LispVal> stack = Objects.requireNonNull(this.stacks.get().get(name), name);
		stack.pop();
		stack.push(value);
	}

	/**
	 * Establishes a new dynamic binding for a special (pushes onto its stack). Balanced
	 * by a {@link #pop(String)} in a {@code finally} on the binding form's exit.
	 * @param name the variable name
	 * @param value the value to bind
	 */
	void push(String name, LispVal value) {
		this.stacks.get().computeIfAbsent(name, k -> new ArrayDeque<>()).push(value);
	}

	/**
	 * Removes the innermost dynamic binding for a special, restoring the previous one (or
	 * the global default when the stack empties).
	 * @param name the variable name
	 */
	void pop(String name) {
		Map<String, Deque<LispVal>> map = this.stacks.get();
		Deque<LispVal> stack = map.get(name);
		if (stack != null) {
			stack.pop();
			if (stack.isEmpty()) {
				map.remove(name);
			}
		}
	}

}
