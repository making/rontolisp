package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
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
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(fnForm), ctx);
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
		int required = required(fi);
		return (fi.variadic() ? arity >= required : arity == required) ? fi : null;
	}

	private static int required(WasmLispCompiler.WasmFunctionInfo fi) {
		return fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
	}

	/**
	 * Emits the call. Each element of {@code args} pushes one argument and is run exactly
	 * once, left to right.
	 * @param ctx the compilation context
	 * @param args one emitter per argument
	 */
	void emitCall(WasmLispCompiler.Ctx ctx, List<Runnable> args) {
		if (this.target == null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(this.funcSlot);
			args.forEach(Runnable::run);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(this.dispatchFuncIndex);
			return;
		}
		int required = required(this.target);
		if (this.arity == required) {
			// The env every defun ignores, the arguments, and -- for a variadic callee
			// reached at exactly its required count -- the empty rest list.
			emitNull(ctx); // env
			args.forEach(Runnable::run);
			if (this.target.variadic()) {
				emitNull(ctx);
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
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				slots.add(slot);
			}
			int restSlot = ctx.allocTemp();
			emitNull(ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(restSlot);
			for (int i = slots.size() - 1; i >= required; i--) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slots.get(i));
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
			}
			emitNull(ctx); // env
			for (int i = 0; i < required; i++) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slots.get(i));
			}
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(restSlot);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(this.target.funcIndex());
	}

	/** {@code ref.null eq} -- the ignored env of a defun, and the empty rest list. */
	private static void emitNull(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
