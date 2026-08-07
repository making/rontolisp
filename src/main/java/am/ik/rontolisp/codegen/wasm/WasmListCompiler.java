package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code list} built-in function.
 *
 * <p>
 * The cons chain has to be linked from the LAST element backwards, but Common Lisp
 * evaluates the argument forms left to right. The two orders are reconciled by evaluating
 * every effectful argument into a temp local first, in source order, and linking the
 * chain from those temps ({@link ArgumentOrder};
 * {@code .kb/argument-evaluation-order.md}). A constant argument needs no temp, so an
 * all-literal {@code list} emits what it always did.
 */
final class WasmListCompiler {

	private WasmListCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Left-to-right pre-evaluation of every effectful argument into a temp local.
		List<@Nullable Integer> slots = new ArrayList<>(args.size());
		slots.add(null);
		for (int i = 1; i < args.size(); i++) {
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
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		for (int i = args.size() - 1; i >= 1; i--) {
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
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		}
	}

}
