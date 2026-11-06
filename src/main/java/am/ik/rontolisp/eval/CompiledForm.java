package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import org.jspecify.annotations.Nullable;

/**
 * One form of a compiled body ({@link BodyCompiler}): what {@link LispEvaluator#evalCons}
 * would do with the form, worked out once, so running it reads no cons, classifies no
 * operator and looks no lexical variable up by name
 * ({@code .kb/interpreter-compiled-bodies.md}). The scopes it makes are the
 * {@link Environment}s the walk makes, with the same names in them, so any form handed
 * back to the walk, a closure and a debugger see what they would have seen.
 *
 * <p>
 * The three ways a form is run are the three positions {@code evalCons} runs a form in:
 * <ul>
 * <li>{@link #eval}: a frame of its own, as {@link LispEvaluator#eval} runs it;</li>
 * <li>{@link #statement}: a frame of its own whose value is dropped -- a statement of a
 * body, a loop or a {@code tagbody} -- whose exits to the blocks of {@code deferTo} may
 * hand their value form over unevaluated ({@code BlockTailExit}), and whose tail
 * {@code go} to one of {@code labels} answers the label's index;</li>
 * <li>{@link #step}: the tail of the current frame, whose blocks are {@code owner}'s. A
 * frame steps its tail in a loop ({@link #run}), as {@code evalCons} does, so a tail
 * chain costs one Java frame however long it is: a form that hands on to a form of its
 * tail answers that form, and the last one answers the frame's value, a {@link TailCall},
 * or a {@link TailForm} the walk continues with -- so a tail call runs in constant
 * stack.</li>
 * </ul>
 */
abstract class CompiledForm {

	/**
	 * The innermost form read from a file the frame stands on at this form: the form
	 * itself when the reader located it, else the one its frame stood on, null where
	 * there is none. An error leaving the form passes it ({@code ConditionTrace}).
	 */
	final @Nullable LocatedCons located;

	/**
	 * A compiled form.
	 * @param located the innermost located form its frame stands on here, or null
	 */
	CompiledForm(@Nullable LocatedCons located) {
		this.located = located;
	}

	/**
	 * The form's value, in a frame of its own.
	 * @param env the scope it runs in
	 * @return the value
	 */
	abstract LispVal eval(Environment env);

	/**
	 * The form as a statement, its value dropped, in a frame of its own: one owning
	 * {@code owner}'s blocks once a block in its tail made it so, handing an exit to one
	 * of {@code deferTo}'s blocks over to that frame, and answering a tail {@code go} to
	 * one of {@code labels}.
	 * @param env the scope it runs in
	 * @param owner the blocks the statement's own frame owns, or null while it owns none
	 * @param deferTo the owner an exit's value form may be handed to, or null
	 * @param labels the labels of the {@code tagbody} whose statement this is, or null
	 * @return the label index a tail {@code go} jumps to, or
	 * {@link LispEvaluator#NO_JUMP}
	 */
	int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
			LispEvaluator.@Nullable TagbodyLabels labels) {
		eval(env);
		return LispEvaluator.NO_JUMP;
	}

	/**
	 * One step of the tail of the frame whose blocks are {@code owner}'s: runs this
	 * form's own part, then answers the form the frame steps onto next -- a
	 * {@link CompiledForm} in the same scope, a {@link Scoped} in a new one -- or what
	 * ends the steps: the value, a {@link TailCall}, a {@link TailForm}.
	 * @param env the scope it runs in
	 * @param owner the exit target of the frame's blocks, never null
	 * @return the next form, or the value or continuation
	 */
	Object step(Environment env, Environment owner) {
		return eval(env);
	}

	/**
	 * Steps a frame's tail from a form ({@link #step}) until a step answers no form, and
	 * gives an error leaving a step the place the frame stood on there.
	 * @param first the form the tail starts with
	 * @param env its scope
	 * @param owner the exit target of the frame's blocks
	 * @return the value, a {@link TailCall} or a {@link TailForm}
	 */
	static Object run(CompiledForm first, Environment env, Environment owner) {
		CompiledForm form = first;
		Environment scope = env;
		try {
			while (true) {
				Object out = form.step(scope, owner);
				if (out instanceof CompiledForm next) {
					form = next;
				}
				else if (out instanceof Scoped scoped) {
					form = scoped.form();
					scope = scoped.env();
				}
				else {
					return out;
				}
			}
		}
		catch (LispEvalException e) {
			throw CompiledForms.passing(e, form.located, scope);
		}
	}

	/**
	 * The form a frame steps onto next, in a scope a step made.
	 *
	 * @param form the form
	 * @param env its scope
	 */
	record Scoped(CompiledForm form, Environment env) {
	}

	/**
	 * A call in a frame's tail, its arguments evaluated: the frame applies the function
	 * as {@code evalCons} applies the one a call form names.
	 *
	 * @param function the function value
	 * @param args its arguments
	 * @param env the scope of the call form
	 * @param located the innermost form read from a file the frame stood on when it
	 * reached the call, or null
	 */
	record TailCall(LispVal function, List<LispVal> args, Environment env, @Nullable LocatedCons located) {
	}

	/**
	 * A form in a frame's tail that the walk evaluates: the frame continues with it.
	 *
	 * @param form the form
	 * @param env its scope
	 * @param located the innermost form read from a file the frame stood on when it
	 * reached the form, or null
	 */
	record TailForm(LispVal form, Environment env, @Nullable LocatedCons located) {
	}

}
