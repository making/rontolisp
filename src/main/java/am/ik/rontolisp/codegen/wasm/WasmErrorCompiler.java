package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code (%error message)} and {@code (%error-cond condition
 * message)} primitives.
 *
 * <p>
 * Outside EH mode both abort execution with a WASM trap: {@code unreachable} is
 * stack-polymorphic, so it type-checks in any context (including a typed {@code if}
 * branch that expects a result value). The arguments are not evaluated (a WASM trap
 * carries no message).
 *
 * <p>
 * In EH mode (the program uses {@code handler-case}/{@code ignore-errors}/
 * {@code unwind-protect}) both evaluate their arguments, build the {@code $lisp-cond}
 * payload -- a cons {@code (condition-instance . message-string)}, the instance being nil
 * for a plain {@code %error} -- and {@code throw} it, so an enclosing
 * {@code handler-case} (or an unwind-protect cleanup pass) can intercept it.
 * {@code throw} is stack-polymorphic like {@code unreachable}, so the call sites need no
 * change. An uncaught throw is converted back into a trap by the entry functions'
 * catch_all wrapper (see {@link WasmEmitHelper#emitCatchAllPrologue}).
 */
final class WasmErrorCompiler {

	private WasmErrorCompiler() {
	}

	/** Compiles {@code (%error message)}: the condition instance slot is nil. */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.ehMode) {
			ctx.writer.write(Instruction.UNREACHABLE);
			return;
		}
		List<LispVal> parts = cons.toList();
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		emitThrowPayload(ctx);
	}

	/** Compiles {@code (%error-cond condition message)}. */
	static void compileCond(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.ehMode) {
			ctx.writer.write(Instruction.UNREACHABLE);
			return;
		}
		List<LispVal> parts = cons.toList();
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		emitThrowPayload(ctx);
	}

	/**
	 * Emits the payload construction and throw: the stack holds (instance, message); they
	 * are consed and thrown on the {@code $lisp-cond} tag.
	 * @param ctx the compilation context
	 */
	static void emitThrowPayload(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.THROW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
	}

}
