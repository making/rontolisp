package am.ik.rontolisp.eval;

/**
 * A compiled body's forms as the code of one class ({@link BodyClassWriter}): what
 * {@link CompiledBody#run} does with its statements and the steps of its tail, with each
 * form's code where its tree node would be called
 * ({@code .kb/interpreter-compiled-bodies.md}).
 */
abstract class BodyClass {

	/** The class a writer defines calls this. */
	BodyClass() {
	}

	/**
	 * Runs the body in a call's scope: its statements, then its tail, stepped as far as
	 * the class's code reaches.
	 * @param scope the scope {@link CompiledBody#bind} made
	 * @param owner the exit target of the frame's blocks
	 * @return the value, a {@link CompiledForm.TailCall} or
	 * {@link CompiledForm.TailForm}, or the form the frame steps onto next: a
	 * {@link CompiledForm} in {@code scope}, a {@link CompiledForm.Scoped} in a scope of
	 * its own
	 */
	abstract Object run(Environment scope, Environment owner);

}
