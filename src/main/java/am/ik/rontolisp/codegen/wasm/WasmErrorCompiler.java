package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
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

	/**
	 * Compiles {@code (%error message)}: the condition instance slot is nil. The message
	 * operand IS the payload a handler-case landing synthesizes its {@code simple-error}
	 * from, so it compiles -- unless {@code ctx.condMessagesObservable} says no program
	 * code can ever hold a condition (then no clause binds the synthesized instance, the
	 * tag tests never read the string, and an uncaught throw is a textless trap), in
	 * which case the message render is skipped like {@code %error-cond}'s.
	 */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.ehMode) {
			ctx.writer.write(Instruction.UNREACHABLE);
			return;
		}
		List<LispVal> parts = cons.toList();
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		if (ctx.condMessagesObservable) {
			WasmExprCompiler.compileExpr(parts.get(1), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		emitThrowPayload(ctx);
	}

	/**
	 * Compiles {@code (%error-cond condition message)}. The payload cdr carries only the
	 * text the entry landing pad cannot rebuild from the instance: the handler-case
	 * landing reads the cdr only to synthesize a {@code simple-error} when the instance
	 * (car) is nil -- and every {@code %error-cond} site passes a real instance -- so the
	 * cdr's one reader here is that pad ({@link WasmUncaughtReportCompiler}), which
	 * renders the instance's report through {@code %condition-report-str} itself. A
	 * message that IS the report (a class with its own {@code :report}) therefore
	 * compiles to nil, and so does the report half of a routed message
	 * ({@link LispMacroExpander#conditionReportFallback}); its fallback -- what a
	 * report-less class prints, {@code Condition (TYPE :INITARG v) was signalled.}, built
	 * from the initargs as written and gone once the instance is built -- compiles, and
	 * the pad prints it when the renderer answers nil. A program with NO report renderer
	 * ({@code routesConditionReports} off) constructs no condition at a source site, so
	 * the only {@code %error-cond} is the {@code %program-error} lowering's, whose
	 * message rides the cdr exactly as a plain {@code %error}'s does.
	 */
	static void compileCond(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.ehMode) {
			ctx.writer.write(Instruction.UNREACHABLE);
			return;
		}
		List<LispVal> parts = cons.toList();
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		LispVal text = !ctx.closRegistry.routesConditionReports() ? (ctx.condMessagesObservable ? parts.get(2) : null)
				: LispMacroExpander.conditionReportFallback(parts.get(2));
		if (text != null) {
			WasmExprCompiler.compileExpr(text, ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		emitThrowPayload(ctx);
	}

	/**
	 * Emits the payload construction and throw: the stack holds (instance, message); they
	 * are consed and thrown on the {@code $lisp-cond} tag.
	 * @param ctx the compilation context
	 */
	static void emitThrowPayload(WasmLispCompiler.Ctx ctx) {
		WasmEmitHelper.emitNewCons(ctx);
		ctx.writer.write(Instruction.THROW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
	}

}
