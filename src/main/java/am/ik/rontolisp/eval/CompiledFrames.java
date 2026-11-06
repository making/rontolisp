package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import org.jspecify.annotations.Nullable;

/**
 * The frames compiled forms open ({@code .kb/interpreter-compiled-bodies.md}): a call in
 * a position whose value is wanted, and a {@code block} evaluated for its value. Each is
 * what {@code LispEvaluator.evalCons}'s loop is for the walk -- one frame that takes
 * every tail call and every form its tail answers in turn, owns the blocks entered in its
 * tail, and is where an error leaving it gets its place in the {@code ConditionTrace}
 * and, past an absorbed {@code funcall}, its signal point -- for a frame whose forms are
 * compiled. A frame that meets a function it cannot run compiled applies it as the walk
 * does ({@code LispEvaluator.apply}); a form its tail hands the walk runs in a walk frame
 * of its own, which hands an exit to this frame's blocks back as its value form, so the
 * frame keeps every tail it reaches.
 *
 * <p>
 * A non-tail call is the commonest frame, and the Java stack it costs is what a
 * recursion's depth is made of: a compiled call enters {@link #call} straight from the
 * node, which runs the callee's body ({@link CompiledBody#run}, its tail stepped in that
 * one frame) and goes round for its continuations, so a call costs the node's frame and
 * this one's.
 */
final class CompiledFrames {

	private CompiledFrames() {
	}

	/**
	 * Runs a compiled body whose call scope is bound in a frame of its own: what the
	 * walk's application of a closure ({@code LispEvaluator.apply}) runs it in. The walk
	 * enters that body in no frame, so an async body it runs is named by none.
	 * @param ev the evaluator
	 * @param body the body
	 * @param scope its call's scope
	 * @return the value
	 */
	static LispVal run(LispEvaluator ev, CompiledBody body, Environment scope) {
		return call(ev, null, null, scope, body, scope, null);
	}

	/**
	 * Evaluates a compiled form as the tail of a frame of its own whose blocks are
	 * {@code owner}'s: a block's body, the block's scope its identity.
	 * @param ev the evaluator
	 * @param form the form
	 * @param owner the block's scope, which the form runs in
	 * @return the value
	 */
	static LispVal tail(LispEvaluator ev, CompiledForm form, Environment owner) {
		return call(ev, null, null, owner, form, owner, null);
	}

	/**
	 * Applies a function to its arguments in a frame of its own. A compiled body runs
	 * here, the frame going on in the general loop
	 * ({@link #call(LispEvaluator, LispVal, List, Environment, Object, Environment, LispLambda)})
	 * only once its tail answers a continuation: the frame a non-tail call of a compiled
	 * body costs is this method's, with as few locals as it takes.
	 * @param ev the evaluator
	 * @param function the function value
	 * @param args its arguments, evaluated
	 * @param env the scope of the call
	 * @return the value
	 */
	static LispVal call(LispEvaluator ev, LispVal function, List<LispVal> args, Environment env) {
		if (!(function instanceof LispLambda lambda)) {
			return call(ev, function, args, env, null, null, null);
		}
		// every call of a closure is where an interrupted evaluation stops
		ev.pollInterrupt();
		CompiledBody compiled = ev.compiledBody(lambda);
		Environment scope = compiled == null ? null : compiled.bind(lambda, args);
		if (compiled == null || scope == null) {
			return ev.applyWalked(lambda, args, env);
		}
		Object out;
		try {
			out = compiled.run(scope, scope);
		}
		catch (BlockReturnSignal signal) {
			if (signal.target() != scope) {
				throw signal;
			}
			return signal.value();
		}
		catch (RuntimeException e) {
			return leaving(ev, e, scope, lambda);
		}
		return out instanceof LispVal value ? value : call(ev, null, null, scope, out, scope, lambda);
	}

	/**
	 * What {@link #call(LispEvaluator, LispVal, List, Environment)}'s frame does with an
	 * exception leaving the body it ran, as the general frame does: an exit to its blocks
	 * continues the frame with its value form, an error gets its signal point, a raw
	 * failure becomes its condition, anything else goes on.
	 */
	private static LispVal leaving(LispEvaluator ev, RuntimeException e, Environment scope, LispLambda lambda) {
		if (e instanceof BlockTailExit exit && exit.target() == scope) {
			return call(ev, null, null, scope, new CompiledForm.TailForm(exit.form(), exit.env(), null), scope, lambda);
		}
		if (e instanceof LispEvalException error) {
			throw ev.debuggerHere() ? ev.signalPoint(error, scope, null) : error;
		}
		if (e instanceof IllegalArgumentException || e instanceof IndexOutOfBoundsException
				|| e instanceof ClassCastException || e instanceof ArithmeticException
				|| e instanceof NegativeArraySizeException) {
			throw failure(ev, e, null, null, false, scope);
		}
		throw e;
	}

	/**
	 * A frame: it applies a function to its arguments, or starts on {@code pending} -- a
	 * bound body to run, a form to run as its tail, the continuation a body answered --
	 * and goes round for each continuation that answers.
	 * @param ev the evaluator
	 * @param function the function value, or null with {@code pending}
	 * @param args its arguments, evaluated, or null with {@code pending}
	 * @param env the scope of the call, or the one {@code pending} runs in
	 * @param pending what the frame starts on, or null
	 * @param owner the exit target of the frame's blocks, or null while it owns none
	 * @param frameLambda the function whose body the frame entered, or null
	 * @return the value
	 */
	private static LispVal call(LispEvaluator ev, @Nullable LispVal function, @Nullable List<LispVal> args,
			Environment env, @Nullable Object pending, @Nullable Environment owner, @Nullable LispLambda frameLambda) {
		LispVal callee = function;
		List<LispVal> arguments = args;
		Object next = pending;
		Environment blocks = owner;
		Environment scope = env;
		// the function whose body this frame entered last, which names an async body it
		// runs (a body the walk's application entered, as `run` starts with, is no
		// frame's)
		LispLambda entered = frameLambda;
		boolean seam = false;
		LocatedCons located = null;
		String locatedIn = null;
		while (true) {
			try {
				Object out;
				if (next == null) {
					LispVal applied = java.util.Objects.requireNonNull(callee);
					List<LispVal> applying = java.util.Objects.requireNonNull(arguments);
					// (funcall closure ...) and (apply closure ...) run the closure in
					// this
					// frame, keeping the built-in's signal point for what it raises
					LispFunction funcall = ev.funcallBuiltin();
					LispFunction apply = ev.applyBuiltin();
					if ((applied == funcall || applied == apply) && !applying.isEmpty()) {
						LispLambda closure = ev.closureCalled(applying.get(0));
						if (closure != null && applied == funcall) {
							applied = closure;
							applying = applying.subList(1, applying.size());
							seam = true;
						}
						else if (closure != null && applying.size() >= 2) {
							applied = closure;
							applying = LispEvaluator.spreadApplyArguments(applying);
							seam = true;
						}
					}
					if (applied == ev.asyncRunBuiltin()) {
						return ev.runAsyncInFrame(applying, entered);
					}
					if (!(applied instanceof LispLambda lambda)) {
						return ev.apply(applied, applying, scope);
					}
					// every call of a closure is where an interrupted evaluation stops
					ev.pollInterrupt();
					CompiledBody compiled = ev.compiledBody(lambda);
					Environment callScope = compiled == null ? null : compiled.bind(lambda, applying);
					if (callScope == null) {
						return ev.applyWalked(lambda, applying, scope);
					}
					if (blocks == null) {
						blocks = callScope;
					}
					entered = lambda;
					scope = callScope;
					out = java.util.Objects.requireNonNull(compiled).run(callScope, blocks);
				}
				else if (next instanceof CompiledBody body) {
					next = null;
					out = body.run(scope, java.util.Objects.requireNonNull(blocks));
				}
				else if (next instanceof CompiledForm form) {
					next = null;
					out = CompiledForm.run(form, scope, java.util.Objects.requireNonNull(blocks));
				}
				else if (next instanceof CompiledForm.TailCall tailCall) {
					next = null;
					callee = tailCall.function();
					arguments = tailCall.args();
					scope = tailCall.env();
					if (tailCall.located() != null) {
						located = tailCall.located();
						locatedIn = scope.lexicalFunction();
					}
					continue;
				}
				else {
					// a form the tail left to the walk: its exits to this frame's blocks
					// come
					// back as BlockTailExit
					CompiledForm.TailForm walked = (CompiledForm.TailForm) next;
					next = null;
					scope = walked.env();
					if (walked.located() != null) {
						located = walked.located();
						locatedIn = scope.lexicalFunction();
					}
					return ev.evalForFrame(walked.form(), scope, blocks);
				}
				if (out instanceof LispVal value) {
					return value;
				}
				next = out;
			}
			catch (BlockReturnSignal signal) {
				if (blocks == null || signal.target() != blocks) {
					throw signal;
				}
				return signal.value();
			}
			catch (BlockTailExit exit) {
				// an exit to one of this frame's blocks: its value form is the frame's
				// tail
				if (blocks == null || exit.target() != blocks) {
					throw exit;
				}
				next = new CompiledForm.TailForm(exit.form(), exit.env(), null);
			}
			catch (LispEvalException e) {
				e.trace().passing(located, locatedIn);
				throw seam || ev.debuggerHere() ? ev.signalPoint(e, scope, null) : e;
			}
			catch (IllegalArgumentException | IndexOutOfBoundsException | ClassCastException | ArithmeticException
					| NegativeArraySizeException raw) {
				throw failure(ev, raw, located, locatedIn, seam, scope);
			}
		}
	}

	/**
	 * A raw Java failure leaving a frame as the condition the walk's frame makes of it
	 * ({@code LispEvaluator.rawEvaluationFailure}).
	 */
	private static LispEvalException failure(LispEvaluator ev, RuntimeException raw, @Nullable LocatedCons located,
			@Nullable String locatedIn, boolean seam, Environment scope) {
		String condition = raw instanceof IllegalArgumentException || raw instanceof IndexOutOfBoundsException
				? ClosRegistry.PROGRAM_ERROR_CLASS_NAME : LispEvaluator.rawFailureConditionClass(raw);
		LispEvalException failure = LispEvaluator.rawEvaluationFailure(condition, raw);
		failure.trace().passing(located, locatedIn);
		return seam || ev.debuggerHere() ? ev.signalPoint(failure, scope, null) : failure;
	}

}
