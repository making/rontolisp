package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * A lambda's body compiled ({@link BodyCompiler}): the layout of its call's scope and its
 * forms. A call binds the scope {@code LispEvaluator.lexicalLambdaScope} would -- the
 * same names, and the marks a free special declaration puts there -- in the order the
 * compiler laid them out, and runs the forms in the frame that made the call
 * ({@code .kb/interpreter-compiled-bodies.md}).
 */
final class CompiledBody {

	private final LispEvaluator evaluator;

	private final int rewriteEpoch;

	private final LispSymbol[] bound;

	private final String[] slots;

	private final boolean distinct;

	private final int parameters;

	private final boolean rest;

	private final String[] marks;

	private final java.util.Set<String> declared;

	private final @Nullable String blockName;

	private final CompiledForm[] statements;

	private final @Nullable CompiledForm last;

	private int specialEpoch;

	/** Set once the body's declared specials are recorded; a race records them twice. */
	private boolean declaredRecorded;

	/**
	 * A compiled body.
	 * @param evaluator the evaluator it was compiled for
	 * @param rewriteEpoch the evaluator's body rewrite epoch it was compiled under
	 * @param specialEpoch the proclaimed specials' epoch its parameters were found
	 * lexical under
	 * @param bound the required parameters, then the rest parameter if any
	 * @param slots the call scope's names in their order: the distinct parameter names,
	 * then the marked names
	 * @param distinct whether the parameters' names are distinct, so each takes the next
	 * slot
	 * @param parameters how many required parameters there are
	 * @param rest whether there is a rest parameter
	 * @param marks the names the body's free special declaration marks in the scope
	 * @param declared the names the body declares special
	 * @param blockName the block a body that is one {@code block} form establishes, or
	 * null
	 * @param statements the body's forms but the last (the block's, for a block body)
	 * @param last the last form, or null for none
	 */
	CompiledBody(LispEvaluator evaluator, int rewriteEpoch, int specialEpoch, LispSymbol[] bound, String[] slots,
			boolean distinct, int parameters, boolean rest, String[] marks, java.util.Set<String> declared,
			@Nullable String blockName, CompiledForm[] statements, @Nullable CompiledForm last) {
		this.evaluator = evaluator;
		this.rewriteEpoch = rewriteEpoch;
		this.specialEpoch = specialEpoch;
		this.bound = bound;
		this.slots = slots;
		this.distinct = distinct;
		this.parameters = parameters;
		this.rest = rest;
		this.marks = marks;
		this.declared = declared;
		this.blockName = blockName;
		this.statements = statements;
		this.last = last;
	}

	/** The evaluator it was compiled for. */
	LispEvaluator evaluator() {
		return this.evaluator;
	}

	/**
	 * Whether this is the body an evaluator runs now: compiled for it, from the forms it
	 * holds.
	 * @param evaluator the evaluator
	 * @param rewriteEpoch its body rewrite epoch now
	 * @return whether it is
	 */
	boolean compiledFor(LispEvaluator evaluator, int rewriteEpoch) {
		return this.evaluator == evaluator && this.rewriteEpoch == rewriteEpoch;
	}

	/**
	 * The scope of a call, its parameters bound lexically; null when one has been
	 * proclaimed special since the compile, whose binding the walk's application makes.
	 * @param lambda the function called
	 * @param args its arguments
	 * @return the scope, or null
	 */
	@Nullable Environment bind(LispLambda lambda, List<LispVal> args) {
		if (this.specialEpoch != this.evaluator.specialEpoch() && !stillLexical()) {
			return null;
		}
		LispEvaluator.checkArity(lambda, args);
		if (!this.declaredRecorded) {
			this.evaluator.recordLocalSpecials(this.declared);
			this.declaredRecorded = true;
		}
		int count = this.parameters;
		if (this.distinct) {
			LispVal[] values;
			if (args instanceof ArgList evaluated && this.slots.length == count) {
				// the arguments a compiled call evaluated are the scope's values
				values = evaluated.values();
			}
			else {
				values = new LispVal[this.slots.length];
				for (int i = 0; i < count; i++) {
					values[i] = args.get(i);
				}
				int slot = count;
				if (this.rest) {
					values[slot++] = restList(args, count);
				}
				for (int i = 0; i < this.marks.length; i++) {
					values[slot++] = Environment.SPECIAL;
				}
			}
			return LispEvaluator.callScope(lambda, this.slots, values);
		}
		// a name bound twice holds its last binding, at its first slot, as define does
		Environment scope = LispEvaluator.callScope(lambda, this.slots.length);
		for (int i = 0; i < count; i++) {
			scope.define(this.bound[i].name(), args.get(i));
		}
		if (this.rest) {
			scope.define(this.bound[count].name(), restList(args, count));
		}
		for (String mark : this.marks) {
			scope.bindFresh(mark, Environment.SPECIAL);
		}
		return scope;
	}

	private static LispVal restList(List<LispVal> args, int required) {
		LispVal list = LispNil.INSTANCE;
		for (int i = args.size() - 1; i >= required; i--) {
			list = new LispCons(args.get(i), list);
		}
		return list;
	}

	/**
	 * Whether the parameters are still lexical, and the marks still the ones a call
	 * makes, after a proclamation: true takes the new epoch.
	 */
	private boolean stillLexical() {
		int epoch = this.evaluator.specialEpoch();
		for (LispSymbol parameter : this.bound) {
			if (this.evaluator.isSpecial(parameter)) {
				return false;
			}
		}
		int marked = 0;
		for (String name : this.declared) {
			if (!this.evaluator.isProclaimedSpecial(name)) {
				if (marked >= this.marks.length || !this.marks[marked].equals(name)) {
					return false;
				}
				marked++;
			}
		}
		if (marked != this.marks.length) {
			return false;
		}
		this.specialEpoch = epoch;
		return true;
	}

	/**
	 * Runs the body in a call's scope, in the frame that made the call: its statements,
	 * then its tail stepped as {@link CompiledForm#run} steps one, in this Java frame --
	 * a recursion's depth is the frames a call costs.
	 * @param scope the scope {@link #bind} made
	 * @param owner the exit target of the frame's blocks
	 * @return the value, or the continuation of its tail ({@link CompiledForm#step})
	 */
	Object run(Environment scope, Environment owner) {
		CompiledForm form = this.last;
		Environment env = scope;
		if (this.blockName != null) {
			// A defun's body is one block, established in the call's own scope.
			scope.installBlock(this.blockName, owner);
			for (CompiledForm statement : this.statements) {
				if (statement.statement(scope, null, owner, null) == LispEvaluator.EXITED) {
					// an exit to the body's block: its value form is the tail
					BlockTailExit exit = scope.takeExit();
					form = exit.compiled();
					env = exit.env();
					if (form == null) {
						return new CompiledForm.TailForm(exit.form(), env, null);
					}
					break;
				}
			}
			if (form == null) {
				scope.clearSpill();
				return LispNil.INSTANCE;
			}
		}
		else {
			// no exit is handed on with no frame to hand it to
			for (CompiledForm statement : this.statements) {
				statement.statement(scope, null, null, null);
			}
			if (form == null) {
				return LispNil.INSTANCE;
			}
		}
		// the tail, stepped as CompiledForm.run steps one, in this Java frame
		try {
			while (true) {
				Object out = form.step(env, owner);
				if (out instanceof CompiledForm next) {
					form = next;
				}
				else if (out instanceof CompiledForm.Scoped scoped) {
					form = scoped.form();
					env = scoped.env();
				}
				else {
					return out;
				}
			}
		}
		catch (LispEvalException e) {
			throw CompiledForms.passing(e, form.located, env);
		}
	}

}
