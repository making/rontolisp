package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code rontolisp::%future-new}/{@code %future-settle}/
 * {@code %future-reject} test primitives of the {@code --component} async state machines:
 * thin bindings of the {@link WasmFutureRuntimeBuilder} runtime, available only in
 * asyncMode. They exist so the suspension machinery is exercisable end-to-end (a pending
 * future an async function suspends on, settled or rejected later) before the import
 * layer produces pending futures of its own; they are deliberately undocumented and
 * absent from every other backend.
 */
final class WasmFutureInternalCompiler {

	private WasmFutureInternalCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncFuncBase < 0) {
			throw new UnsupportedOperationException("rontolisp::" + member
					+ " requires --component and an async program (it is an internal async-runtime binding)");
		}
		List<LispVal> args = cons.toList();
		switch (member) {
			case LispNames.FUTURE_NEW_INTERNAL -> {
				expectArgs(member, args, 0);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_NEW);
			}
			case LispNames.FUTURE_SETTLE_INTERNAL -> {
				expectArgs(member, args, 2);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SETTLE);
			}
			case LispNames.FUTURE_REJECT_INTERNAL -> {
				expectArgs(member, args, 2);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				// The $lisp-cond payload shape is (condition-instance . message); a nil
				// instance makes the catch side synthesize a simple-error from the
				// message, exactly like a plain %error throw.
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_REJECT);
			}
			default -> throw new IllegalArgumentException("unknown future internal: " + member);
		}
	}

	private static void expectArgs(String member, List<LispVal> args, int expected) {
		if (args.size() - 1 != expected) {
			throw new UnsupportedOperationException(
					"rontolisp::" + member + " expects " + expected + " arguments, got " + (args.size() - 1));
		}
	}

}
