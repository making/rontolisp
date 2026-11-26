package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code list} and {@code list*} built-in functions.
 *
 * <p>
 * The cons chain has to be linked from the LAST element backwards, but Common Lisp
 * evaluates the argument forms left to right. The two orders are reconciled by evaluating
 * every effectful argument into a temp local first, in source order, and linking the
 * chain from those temps ({@link ArgumentOrder};
 * {@code .kb/argument-evaluation-order.md}). A constant argument needs no temp, so an
 * all-literal {@code list} emits what it always did. {@code list*} is the same chain
 * linked onto its last argument instead of nil: one loop, not a {@code cons} nested per
 * argument, so its width costs no stack.
 */
final class WasmListCompiler {

	private WasmListCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons.toList(), false, ctx);
	}

	static void compileStar(LispCons cons, WasmLispCompiler.Ctx ctx, boolean tail) {
		List<LispVal> args = cons.toList();
		if (args.size() <= 2) {
			// (list* x) is x, in the form's tail position; no argument is an error.
			ctx.tailPosition = tail;
			WasmExprCompiler.compileExpr(LispMacroExpander.expandListStar(cons), ctx);
			return;
		}
		compile(args, true, ctx);
	}

	/**
	 * Links {@code args[1..]} into a fresh chain ending in nil, or for {@code star}
	 * ending in the value of the last argument, evaluated after every other.
	 */
	private static void compile(List<LispVal> args, boolean star, WasmLispCompiler.Ctx ctx) {
		int end = star ? args.size() - 1 : args.size();
		// Left-to-right pre-evaluation of every effectful argument into a temp local.
		List<@Nullable Integer> slots = new ArrayList<>(end);
		slots.add(null);
		for (int i = 1; i < end; i++) {
			if (ArgumentOrder.isOrderIndependent(args.get(i))) {
				slots.add(null);
				continue;
			}
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			int slot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			slots.add(slot);
		}
		// Build cons list right-to-left
		if (star) {
			WasmExprCompiler.compileExpr(args.get(end), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		for (int i = end - 1; i >= 1; i--) {
			Integer pre = slots.get(i);
			if (pre == null) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
			}
			else {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(pre);
			}
			// Swap car and cdr on stack using temp
			int tmpCdr = ctx.allocTemp();
			int tmpCar = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpCar);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpCdr);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpCar);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpCdr);
			WasmEmitHelper.emitNewCons(ctx);
		}
	}

}
