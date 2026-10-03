package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;

/**
 * A self tail call as a jump: a call in tail position to the function whose method is
 * being emitted -- a {@code defun} calling itself by name (or through a literal
 * {@code #'name}), a {@code labels} function calling itself through its own variable --
 * evaluates its arguments, stores them into the parameter slots and jumps back to the
 * method's first instruction. A loop written as tail recursion (Clojure's
 * {@code loop}/{@code recur}, the Clojure lowering's per-element {@code labels} verbs, a
 * Common Lisp accumulator) then runs in constant stack, as it does on the interpreter and
 * on both wasm backends ({@code .kb/jvm-self-tail-calls.md}).
 *
 * <p>
 * The tail position is the trampoline's mark ({@link JvmTailBounce}): the form whose
 * value is the method's result, re-laid only by forms that hand a sub-form's value on
 * unchanged and open no dynamic extent. The jump target sits BEFORE the prologue that
 * boxes captured parameters, so a closure made in one round keeps that round's cell. The
 * operand stack is empty at the target, and the jump is emitted only from an empty one --
 * the frame the verifier and HotSpot's OSR entry both need
 * ({@code .kb/jvm-osr-backedges.md}).
 */
final class JvmSelfTailCall {

	private JvmSelfTailCall() {
	}

	/**
	 * The method being emitted as the target of its own tail calls.
	 *
	 * @param head the label bound at the method's first instruction
	 * @param firstParamSlot the slot of the first parameter: 0 for a defun, 1 past a
	 * closure's environment
	 * @param required the required parameters
	 * @param optionals the physical optionals (an argument or the UNSUPPLIED marker)
	 * @param variadic whether the last parameter takes the rest list
	 * @param defun the registry entry a direct call names, for a defun's method
	 * @param selfVar the {@code labels} variable holding the closure, for a
	 * {@code labels} function's method
	 */
	record Loop(MethodCode.Label head, int firstParamSlot, int required, int optionals, boolean variadic,
			JvmLispCompiler.@Nullable FunctionInfo defun, @Nullable String selfVar) {

		/**
		 * {@return the physical parameter count}
		 */
		int paramCount() {
			return this.required + this.optionals + (this.variadic ? 1 : 0);
		}

		/**
		 * {@return whether a call passing {@code count} arguments binds the parameters}
		 */
		boolean accepts(int count) {
			return count >= this.required && (this.variadic || count <= this.required + this.optionals);
		}

	}

	/**
	 * The loop of a defun's method, its head bound at the current (first) position.
	 * @param ctx the method, nothing emitted yet
	 * @param fi the defun's registry entry
	 * @return the loop
	 */
	static Loop defun(JvmLispCompiler.Ctx ctx, JvmLispCompiler.FunctionInfo fi) {
		return new Loop(ctx.body.newBoundLabel(), 0, fi.required(), fi.optionals(), fi.variadic(), fi, null);
	}

	/**
	 * The loop of a {@code labels} function's method, its head bound at the current
	 * (first) position.
	 * @param ctx the method, nothing emitted yet
	 * @param lambda the lambda, whose {@link JvmLispCompiler.LambdaInfo#selfVar} is set
	 * @return the loop
	 */
	static Loop labels(JvmLispCompiler.Ctx ctx, JvmLispCompiler.LambdaInfo lambda) {
		int positional = lambda.paramNames().size() - (lambda.variadic() ? 1 : 0);
		return new Loop(ctx.body.newBoundLabel(), 1, positional - lambda.optionals(), lambda.optionals(),
				lambda.variadic(), null, lambda.selfVar());
	}

	/**
	 * Emits a direct call of {@code fi} as a jump when it is this defun's own tail call.
	 * @param fi the callee
	 * @param cons the call form
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 * @return whether the call was emitted (as a jump); false leaves nothing emitted
	 */
	static boolean tryDirect(JvmLispCompiler.FunctionInfo fi, LispCons cons, JvmLispCompiler.Ctx ctx,
			String className) {
		Loop loop = ctx.selfLoop;
		if (loop == null || loop.defun() != fi || !atTail(cons, ctx)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		return emitJump(loop, parts.subList(1, parts.size()), ctx, className);
	}

	/**
	 * Emits {@code (funcall designator arg...)} as a jump when the designator names this
	 * method's own function -- the {@code labels} variable holding this closure, or a
	 * literal {@code #'name} of this defun -- and the call is in tail position.
	 * @param cons the {@code funcall} form
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 * @return whether the call was emitted (as a jump); false leaves nothing emitted
	 */
	static boolean tryFuncall(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Loop loop = ctx.selfLoop;
		if (loop == null || !atTail(cons, ctx)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !namesSelf(parts.get(1), loop, ctx)) {
			return false;
		}
		return emitJump(loop, parts.subList(2, parts.size()), ctx, className);
	}

	private static boolean atTail(LispCons cons, JvmLispCompiler.Ctx ctx) {
		// The mark's chain opens no dynamic extent; the scope checks only refuse what
		// would be a bug elsewhere, so a stale mark can never cut a cleanup off.
		return ctx.tailMark == cons && ctx.unwindScopes.isEmpty() && ctx.spillScopes.isEmpty()
				&& ctx.stack.snapshot().isEmpty();
	}

	private static boolean namesSelf(LispVal designator, Loop loop, JvmLispCompiler.Ctx ctx) {
		String selfVar = loop.selfVar();
		if (selfVar != null) {
			// The closure's own captured variable, which no binding here shadows.
			return designator instanceof LispSymbol sym && selfVar.equals(sym.name())
					&& ctx.captures.containsKey(selfVar) && !ctx.locals.containsKey(selfVar)
					&& !ctx.rawLocals.containsKey(selfVar);
		}
		String name = FunctionDesignators.literalName(designator);
		return name != null && ctx.functions.get(name) == loop.defun();
	}

	/**
	 * The jump: every argument evaluates, left to right, into the physical parameter
	 * sequence a call would pass ({@link JvmPhysicalArgs}), then lands in the parameter
	 * slots -- all of them evaluated before the first store, so an argument reads the
	 * round it was written in -- and control returns to the head.
	 */
	private static boolean emitJump(Loop loop, List<LispVal> args, JvmLispCompiler.Ctx ctx, String className) {
		if (!loop.accepts(args.size())) {
			// A count the lambda list rules out keeps the call, which signals.
			return false;
		}
		List<Runnable> emitters = new ArrayList<>(args.size());
		for (LispVal arg : args) {
			emitters.add(() -> JvmExprCompiler.compileExpr(arg, ctx, className));
		}
		JvmPhysicalArgs.emit(ctx, className, loop.required(), loop.optionals(), loop.variadic(), emitters);
		for (int i = loop.paramCount() - 1; i >= 0; i--) {
			ctx.body.astore(loop.firstParamSlot() + i);
		}
		ctx.body.goto_(loop.head());
		return true;
	}

}
