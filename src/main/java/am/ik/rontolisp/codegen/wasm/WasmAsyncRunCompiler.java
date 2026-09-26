package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Compiles the internal {@code rontolisp::%async-run} primitive (the lowered
 * {@code rontolisp:async-defun}/{@code async-lambda} body) outside asyncMode. Preview 1
 * has no asynchronous execution of its own -- nothing can genuinely suspend, so the body
 * thunk runs to completion right here through the arity-0 dispatch function, and its
 * value is wrapped in a settled (kind 2) {@code TYPE_P1_FUTURE} struct -- the degenerate
 * future that keeps the cross-backend surface identical. (A {@code --component} program
 * with an async surface is asyncMode and compiles through the {@code WasmAsyncEmit} state
 * machines instead.)
 */
final class WasmAsyncRunCompiler {

	private WasmAsyncRunCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("%async-run expects 1 argument, got " + (args.size() - 1));
		}
		ctx.indirectCallArities.add(0);
		// --report-locations: the thunk is the async body, so a condition leaving it
		// crosses into whoever awaits -- the hop named after the function holding this
		// call, as the interpreter names it.
		if (ctx.uncaughtLocations != null && args.get(1) instanceof LispCons thunk
				&& thunk.car() instanceof LispSymbol head && LispNames.LAMBDA.equals(head.name())) {
			ctx.ucPendingHop = WasmUncaughtLocations.hopText(ctx.ucFunctionName);
		}
		Integer spillGlobal = ctx.globalIndices.get(LispNames.MV_SPILL);
		if (spillGlobal != null) {
			compileCapturingValues(args.get(1), spillGlobal, ctx);
			return;
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmP1FutureRuntimeBuilder.KIND_SETTLED);
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
	}

	// In a program with a multiple-value consumer: the channel holds the body's extra
	// values the moment the thunk returns (its tail settled them), so a body that
	// answered other than one value settles a KIND_VALUES future over
	// (primary . extras) -- what the await publishes -- and any other a KIND_SETTLED one.
	private static void compileCapturingValues(LispVal thunk, int spillGlobal, WasmLispCompiler.Ctx ctx) {
		WasmWriter w = ctx.writer;
		WasmExprCompiler.compileExpr(thunk, ctx);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE);
		int primary = ctx.allocTemp();
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(primary);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(spillGlobal);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmP1FutureRuntimeBuilder.KIND_SETTLED);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(primary);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmP1FutureRuntimeBuilder.KIND_VALUES);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(primary);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(spillGlobal);
		WasmEmitHelper.emitNewCons(ctx);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.END);
	}

}
