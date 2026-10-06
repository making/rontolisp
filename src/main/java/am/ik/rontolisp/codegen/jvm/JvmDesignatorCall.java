package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;

/**
 * The call emitted by an operator that FUNCALLS a function argument: {@code funcall}
 * itself, the {@code mapcar}/{@code mapc}/{@code mapcan} loops, {@code reduce} and
 * {@code sort}. The WASM twin is {@code WasmDesignatorCall}, and the two decide
 * identically -- {@code compiler.FunctionDesignators.literalName} plus the backend's own
 * registry -- so a site that stops being dispatchable stops on both.
 *
 * <p>
 * A designator the compiler can READ -- a literal {@code #'name} / {@code 'name} naming a
 * function of a compatible arity -- becomes the DIRECT {@code invokestatic} its
 * head-position spelling would have emitted, which is what the ladder's own case for it
 * carries ({@code JvmRuntimeBuilder.renderCase}: the arguments, a variadic callee's
 * surplus linked into the rest list, {@code invokestatic}). Two things it saves: the
 * dispatch method's id search at run time, and -- the reason this exists -- the funcId
 * never joins {@code Ctx.valueFuncIds}, so the ladder carries no case for it and the
 * writer's shake stops seeing the ladder's reference to everything that case reaches
 * ({@code .kb/optimize-dead-code-elimination.md}).
 *
 * <p>
 * Everything else keeps the dispatcher: a computed designator, a name no function
 * answers, and an arity the callee cannot take. That last one is deliberate rather than a
 * compile error -- the arity contract of these operators is a RUN-time one, so
 * {@code (mapcar #'cons '(1 2))} must still fail where it fails today.
 */
final class JvmDesignatorCall {

	private final JvmLispCompiler.@Nullable FunctionInfo target;

	private final int funcSlot;

	private final int arity;

	private JvmDesignatorCall(JvmLispCompiler.@Nullable FunctionInfo target, int funcSlot, int arity) {
		this.target = target;
		this.funcSlot = funcSlot;
		this.arity = arity;
	}

	/**
	 * Resolves the designator, EMITTING its evaluation into a temp slot on the
	 * dispatching route (and registering the arity so the dispatch method gets a body). A
	 * literal designator has no side effects and no value is needed, so the direct route
	 * emits nothing here and the operator's evaluation order is unchanged.
	 * @param fnForm the function-designator expression, unevaluated
	 * @param arity the number of arguments every call passes
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @return the resolved call
	 */
	static JvmDesignatorCall prepare(LispVal fnForm, int arity, JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.FunctionInfo direct = directTarget(fnForm, arity, ctx);
		if (direct != null) {
			return new JvmDesignatorCall(direct, -1, arity);
		}
		ctx.indirectCallArities.add(arity);
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(fnForm), ctx, className);
		int slot = ctx.allocTemp();
		ctx.body.astore(slot);
		return new JvmDesignatorCall(null, slot, arity);
	}

	/**
	 * The registered function a literal designator names, when it can take {@code arity}
	 * arguments; {@code null} for every other designator.
	 * @param fnForm the function-designator expression, unevaluated
	 * @param arity the number of arguments every call passes
	 * @param ctx the compilation context
	 * @return the function, or null
	 */
	static JvmLispCompiler.@Nullable FunctionInfo directTarget(LispVal fnForm, int arity, JvmLispCompiler.Ctx ctx) {
		String name = FunctionDesignators.literalName(fnForm);
		if (name == null) {
			return null;
		}
		JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
		if (fi == null) {
			// An unregistered name still has the routes the value path gives it: a
			// car/cdr composition synthesizes a lambda, --dynamic defers to the runtime.
			return null;
		}
		return reaches(fi.required(), fi.variadic(), arity) ? fi : null;
	}

	/**
	 * {@return whether a literal designator's call of {@code arity} arguments is the
	 * direct call of a function with this lambda list}
	 * @param required the arguments the function needs
	 * @param variadic whether it takes a rest list
	 * @param arity the arguments the call passes
	 */
	static boolean reaches(int required, boolean variadic, int arity) {
		return variadic ? arity >= required : arity == required;
	}

	/**
	 * Emits the call. Each element of {@code args} pushes one argument and is run exactly
	 * once, left to right.
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param args one emitter per argument
	 */
	void emitCall(JvmLispCompiler.Ctx ctx, String className, List<Runnable> args) {
		this.emitCall(ctx, className, args, null);
	}

	/**
	 * Emits the call {@code form} stands for, which may be the method's tail: a direct
	 * call there hands a bounce its callee answers on (JvmTailBounce).
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param args one emitter per argument
	 * @param form the call form, or null when no form is the call (an operator's
	 * per-element call)
	 */
	void emitCall(JvmLispCompiler.Ctx ctx, String className, List<Runnable> args, @Nullable LispCons form) {
		if (this.target == null) {
			ctx.body.aload(this.funcSlot);
			args.forEach(Runnable::run);
			JvmFunctionCallCompiler.emitDispatchCall(this.arity, ctx, className);
			return;
		}
		// The ladder's own case for this callee, emitted in place: the arguments a
		// parameter takes, the UNSUPPLIED marker for an optional not passed, and a
		// surplus linked into the rest list (JvmPhysicalArgs).
		JvmPhysicalArgs.emit(ctx, className, this.target, args);
		// The callee may answer a bounce: the value this call answers is the
		// trampoline's, or -- the method's tail -- the bounce itself (JvmTailBounce).
		JvmTailBounce.emitDirectCall(this.target, form, ctx, className);
	}

}
