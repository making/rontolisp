package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.function.IntSupplier;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * The call emitted by an operator that FUNCALLS a function argument: {@code funcall}
 * itself, the {@code mapcar}/{@code mapc}/{@code mapcan} loops, {@code reduce} and
 * {@code sort}.
 *
 * <p>
 * A designator the compiler can READ -- a literal {@code #'name} / {@code 'name} naming a
 * function of a compatible arity -- becomes the DIRECT call its head-position spelling
 * would have emitted, which is the same instruction sequence the ladder's own case for it
 * carries ({@code WasmRuntimeBuilder.buildDispatchBody}: the closure's env, the
 * arguments, a variadic callee's surplus linked into the rest list, {@code call}). Two
 * things it saves: the {@code br_table} over every callable of that arity at run time,
 * and -- the reason this exists -- the funcId never joins {@code Ctx.valueFuncIds}, so
 * the ladder carries no case for it and the tree shaker stops seeing the ladder's call
 * edge to everything that case reaches ({@code .kb/optimize-dead-code-elimination.md}).
 *
 * <p>
 * Everything else keeps the dispatcher: a computed designator, a name no function
 * answers, and an arity the callee cannot take. That last one is deliberate rather than a
 * compile error -- the arity contract of these operators is a RUN-time one, so
 * {@code (mapcar #'cons '(1 2))} must still fail where it fails today.
 */
final class WasmDesignatorCall {

	private final WasmLispCompiler.@Nullable WasmFunctionInfo target;

	private final int funcSlot;

	private final int dispatchFuncIndex;

	private final int arity;

	private WasmDesignatorCall(WasmLispCompiler.@Nullable WasmFunctionInfo target, int funcSlot, int dispatchFuncIndex,
			int arity) {
		this.target = target;
		this.funcSlot = funcSlot;
		this.dispatchFuncIndex = dispatchFuncIndex;
		this.arity = arity;
	}

	/**
	 * Resolves the designator, EMITTING its evaluation into a temp slot on the
	 * dispatching route. A literal designator has no side effects and no value is needed,
	 * so the direct route emits nothing here and the operator's evaluation order is
	 * unchanged.
	 * @param fnForm the function-designator expression, unevaluated
	 * @param arity the number of arguments every call passes
	 * @param dispatchFuncIndex the dispatcher for {@code arity}, asked for ONLY on the
	 * dispatching route -- it also registers the arity with the module, and rejects one
	 * past the ceiling
	 * @param ctx the compilation context
	 * @return the resolved call
	 */
	static WasmDesignatorCall prepare(LispVal fnForm, int arity, IntSupplier dispatchFuncIndex,
			WasmLispCompiler.Ctx ctx) {
		WasmDesignatorCall direct = direct(fnForm, arity, ctx);
		if (direct != null) {
			return direct;
		}
		int index = dispatchFuncIndex.getAsInt();
		// A designator the compiler cannot READ may deliver a SYMBOL at run time, which
		// only the name registry resolves -- so the site tells the module it dispatched
		// one (Ctx.runtimeDesignatorDispatch). A static designator is a closure the
		// compiler built and needs nothing.
		if (!ctx.injectedRuntimeBody && !LispMacroExpander.isStaticFunctionDesignator(fnForm)) {
			ctx.runtimeDesignatorDispatch[0] = true;
		}
		WasmExprCompiler.compileExpr(WasmFunctionFormCompiler.designator(fnForm, ctx), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		return new WasmDesignatorCall(null, slot, index, arity);
	}

	/**
	 * The direct call this designator earns, or {@code null} when it has to be
	 * dispatched. Emits nothing either way, so a caller with a route of its own for the
	 * dispatching case (an arity past the dispatch ceiling, say) can ask first.
	 * @param fnForm the function-designator expression, unevaluated
	 * @param arity the number of arguments every call passes
	 * @param ctx the compilation context
	 * @return the direct call, or {@code null}
	 */
	static @Nullable WasmDesignatorCall direct(LispVal fnForm, int arity, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.WasmFunctionInfo target = directTarget(fnForm, arity, ctx);
		return target == null ? null : new WasmDesignatorCall(target, -1, -1, arity);
	}

	/**
	 * The registered function a literal designator names, when it can take {@code arity}
	 * arguments; {@code null} for every other designator.
	 */
	private static WasmLispCompiler.@Nullable WasmFunctionInfo directTarget(LispVal fnForm, int arity,
			WasmLispCompiler.Ctx ctx) {
		String name = FunctionDesignators.literalName(fnForm);
		if (name == null) {
			return null;
		}
		WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi == null) {
			// An unregistered name still has the routes the value path gives it: a
			// car/cdr composition synthesizes a lambda, --dynamic defers to the runtime.
			return null;
		}
		int required = fi.required();
		return (fi.variadic() ? arity >= required : arity == required) ? fi : null;
	}

	/**
	 * Emits the call. Each element of {@code args} pushes one argument and is run exactly
	 * once, left to right.
	 * @param ctx the compilation context
	 * @param args one emitter per argument
	 */
	void emitCall(WasmLispCompiler.Ctx ctx, List<Runnable> args) {
		emitCall(ctx, args, false);
	}

	/**
	 * As {@link #emitCall(WasmLispCompiler.Ctx, List)}; with {@code tail}, the call is a
	 * {@code return_call} ({@code Ctx.tailPosition}).
	 * @param ctx the compilation context
	 * @param args one emitter per argument
	 * @param tail whether the call is in tail position of the function being built
	 */
	void emitCall(WasmLispCompiler.Ctx ctx, List<Runnable> args, boolean tail) {
		if (this.target == null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(this.funcSlot);
			args.forEach(Runnable::run);
			WasmUncaughtLocations.emitValueCall(ctx, tail, args.size() + 1, this.dispatchFuncIndex);
			return;
		}
		// The ladder's own case for this callee, emitted in place: the env every defun
		// ignores, the arguments a parameter takes, the UNSUPPLIED marker for an
		// optional not passed, and a surplus linked into the rest list
		// (WasmPhysicalArgs).
		emitNull(ctx); // env
		WasmPhysicalArgs.emit(ctx, this.target, args);
		ctx.writer.write(WasmUncaughtLocations.tailCallOp(ctx, tail, this.target.name()));
		ctx.writer.writeUnsignedLeb128(this.target.funcIndex());
	}

	/** {@code ref.null eq} -- the ignored env of a defun, and the empty rest list. */
	private static void emitNull(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
