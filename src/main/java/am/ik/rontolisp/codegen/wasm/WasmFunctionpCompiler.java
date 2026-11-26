package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code functionp} predicate: a function value is a {@code TYPE_CLOSURE}
 * struct (funcId + capture environment), and nothing else uses that type -- plus, in a
 * module that can build one, a funcallable instance ({@link WasmFuncallableInstances}).
 */
final class WasmFunctionpCompiler {

	private WasmFunctionpCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmFuncallableInstances funcallable = WasmFuncallableInstances.of(ctx.closRegistry, ctx.layoutAddresses,
				ctx.instanceTypeIndex);
		if (funcallable != null) {
			int valueSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(valueSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(valueSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
			ctx.writer.write(Instruction.IF, Type.I32.code());
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.ELSE);
			funcallable.emitTest(ctx.writer, valueSlot);
			ctx.writer.write(Instruction.END);
			WasmEmitHelper.emitBoolFromI32(ctx);
			return;
		}
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
