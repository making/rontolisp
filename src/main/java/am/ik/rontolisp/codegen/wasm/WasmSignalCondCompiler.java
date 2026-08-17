package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code (%signal-cond condition message)} primitive behind
 * {@code signal}, mirroring {@code JvmSignalCondCompiler}: in EH mode, when a
 * {@code handler-case} that will HANDLE the condition is established (the handler-depth
 * global is positive and, under {@code Ctx.signalClauseMatch}, the injected
 * {@code %hc-match-p} defun finds a MATCHING handler-case clause on the dynamic
 * {@code %handler-clusters%} stack -- CLHS 9.1.4.1, a handler-case whose clauses do not
 * match is declined), the condition is thrown on the {@code $lisp-cond} tag like
 * {@code %error-cond}; otherwise the whole form yields nil (the CL fall-through of an
 * unhandled signal). The condition operand is evaluated either way (initarg effects are
 * observable); the message operand is never compiled, for the reason
 * {@code WasmErrorCompiler.compileCond} gives -- the payload cdr of a non-nil instance
 * has no reader on this backend, and a fall-through signal discards it outright.
 */
final class WasmSignalCondCompiler {

	private WasmSignalCondCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (!ctx.ehMode) {
			// No handlers exist, so an unhandled signal falls through to nil (the CL
			// semantics); the condition is evaluated for effect.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		int condSlot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
		// A handler is armed (depth > 0): raise like %error-cond -- under the clause
		// match gate only when some armed handler-case clause will actually take it.
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.ehDepthGlobalIndex);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_GT_S);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		if (ctx.signalClauseMatch) {
			// Ask the clause-type stack whether any armed handler-case clause matches;
			// the condition rides a pseudo-local so the call compiles as ordinary Lisp.
			String condVarName = "__sc_cond$" + condSlot;
			ctx.locals.put(condVarName, condSlot);
			try {
				WasmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.HC_MATCH_INTERNAL),
						new LispCons(new LispSymbol(condVarName), LispNil.INSTANCE)), ctx);
			}
			finally {
				ctx.locals.remove(condVarName);
			}
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.wasmCtrlDepth++;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		WasmErrorCompiler.emitThrowPayload(ctx);
		if (ctx.signalClauseMatch) {
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END);
		}
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
