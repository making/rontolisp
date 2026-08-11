package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.jvm.Opcode;

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
 * never joins {@code Ctx.valueFuncIds}, so the ladder carries no case for it and
 * {@code JvmClassShaker} stops seeing the ladder's reference to everything that case
 * reaches ({@code .kb/optimize-dead-code-elimination.md}).
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
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slot);
		return new JvmDesignatorCall(null, slot, arity);
	}

	/**
	 * The registered function a literal designator names, when it can take {@code arity}
	 * arguments; {@code null} for every other designator.
	 */
	private static JvmLispCompiler.@Nullable FunctionInfo directTarget(LispVal fnForm, int arity,
			JvmLispCompiler.Ctx ctx) {
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
		int required = required(fi);
		return (fi.variadic() ? arity >= required : arity == required) ? fi : null;
	}

	private static int required(JvmLispCompiler.FunctionInfo fi) {
		return fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
	}

	/**
	 * Emits the call. Each element of {@code args} pushes one argument and is run exactly
	 * once, left to right.
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param args one emitter per argument
	 */
	void emitCall(JvmLispCompiler.Ctx ctx, String className, List<Runnable> args) {
		if (this.target == null) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(this.funcSlot);
			args.forEach(Runnable::run);
			JvmFunctionCallCompiler.emitDispatchCall(this.arity, ctx, className);
			return;
		}
		int required = required(this.target);
		if (this.arity == required) {
			args.forEach(Runnable::run);
			if (this.target.variadic()) {
				// A variadic callee reached at exactly its required count: the empty
				// rest list.
				ctx.emit(Opcode.ACONST_NULL);
			}
		}
		else {
			// A variadic callee reached wider than its required count: the surplus
			// arguments are linked into the rest list, so every argument is evaluated
			// into a temp (left to right, as the dispatching route evaluates them)
			// before anything goes on the stack.
			List<Integer> slots = new ArrayList<>();
			for (Runnable arg : args) {
				arg.run();
				int slot = ctx.allocTemp();
				ctx.emit(Opcode.ASTORE);
				ctx.emit(slot);
				slots.add(slot);
			}
			int restSlot = ctx.allocTemp();
			ctx.emit(Opcode.ACONST_NULL);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(restSlot);
			for (int i = slots.size() - 1; i >= required; i--) {
				ctx.emit(Opcode.ICONST_2);
				ctx.emit(Opcode.ANEWARRAY);
				ctx.emitU2(ctx.objectClass.index());
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slots.get(i));
				ctx.emit(Opcode.AASTORE);
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_1);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(restSlot);
				ctx.emit(Opcode.AASTORE);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(restSlot);
			}
			for (int i = 0; i < required; i++) {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slots.get(i));
			}
			ctx.emit(Opcode.ALOAD);
			ctx.emit(restSlot);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(this.target.methodref().index());
	}

}
