package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code apply} built-in function. The leading arguments are taken literally
 * and the final argument is a list whose elements are spread; the full argument list is
 * built as {@code (cons arg1 (cons ... lastList))} and passed to the runtime
 * {@code _apply} helper. Using {@code apply} forces the eval runtime to be emitted (see
 * {@code WasmLispCompiler}), which provides {@code _apply}.
 */
final class WasmApplyCompiler {

	private WasmApplyCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int n = args.size();

		// Compile the function designator.
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);

		// Compile the leading literal arguments (indices 2 .. n-2), left to right.
		List<Integer> argSlots = new ArrayList<>();
		for (int i = 2; i < n - 1; i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			int s = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(s);
			argSlots.add(s);
		}

		// Compile the final list argument; it becomes the tail of the argument list.
		WasmExprCompiler.compileExpr(args.get(n - 1), ctx);
		int curSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(curSlot);

		// Prepend each leading argument: cur = cons(arg, cur).
		for (int k = argSlots.size() - 1; k >= 0; k--) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(argSlots.get(k));
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(curSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(curSlot);
		}

		// _apply(func, argList)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(curSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_APPLY);
	}

}
