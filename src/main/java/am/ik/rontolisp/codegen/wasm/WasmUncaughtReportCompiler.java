package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.UncaughtReport;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * The entry function's EH-mode landing pad: a condition nobody caught writes
 * {@link UncaughtReport#PREFIX} plus its report to file descriptor 2 and only then traps.
 *
 * <p>
 * <b>What this replaces.</b> {@link WasmEmitHelper#emitCatchAllPrologue} wraps an entry
 * body in {@code block} + {@code try_table (catch_all)} whose landing is a single
 * {@code unreachable} -- so the payload the throw carried, message and all, was on the
 * stack at the catch and dropped. The user's whole output for a failed cl-postgres
 * connect was {@code wasm trap: wasm 'unreachable' instruction executed}. Here the
 * $lisp-cond tag gets its own catch, which pushes that payload into a
 * {@code block (result (ref null eq))}, and the landing renders it before falling into
 * the same {@code unreachable}: the process still exits as a trap, which is the exit
 * class every host and every test already expects.
 *
 * <p>
 * <b>Entry functions only.</b> {@code _start} / {@code run} is where a PROGRAM ends; an
 * export wrapper is a host call whose failure is the host's to report, and a served
 * handler's is {@code .todo/191}'s. Those keep {@link WasmEmitHelper}'s catch_all-only
 * landing, so their artifacts are unchanged.
 *
 * <p>
 * <b>Outside EH mode nothing changes.</b> {@code %error} is then a bare
 * {@code unreachable} that evaluates none of its arguments and the module has no tag
 * section at all, so there is no payload to read and no landing to read it in. Reporting
 * there would mean turning EH mode on for every program (122 KB -> 176 KB on a two-line
 * toy) to buy an error path most programs never take -- and would give up the
 * byte-identity guarantee a program without a catching form has today. See
 * {@code .kb/error-handling.md}.
 */
final class WasmUncaughtReportCompiler {

	private WasmUncaughtReportCompiler() {
	}

	/**
	 * Opens the reporting wrapper of an entry function: {@code block $trap} +
	 * {@code block $cond (result (ref null eq))} + {@code try_table (catch $lisp-cond
	 * $cond) (catch_all $trap)}. Catch labels are resolved without the try_table's own
	 * label, so 0 is {@code $cond} and 1 is {@code $trap}. The body compiled next runs
	 * inside the try_table and leaves through a {@code return}, so no result blocktype is
	 * needed whatever the function's signature.
	 * @param ctx the compilation context (its wasmCtrlDepth is raised by three)
	 */
	static void emitPrologue(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.TRY_TABLE, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.writeUnsignedLeb128(2);
		ctx.writer.write(Instruction.CATCH);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.CATCH_ALL);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.wasmCtrlDepth += 3;
	}

	/**
	 * Closes the structure opened by {@link #emitPrologue}: the normal {@code return},
	 * the two {@code end}s, the report over the caught payload, and the
	 * {@code unreachable} both landings share. The caller still writes the function's own
	 * final {@code end}.
	 * @param ctx the compilation context
	 */
	static void emitEpilogue(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.RETURN);
		ctx.writer.write(Instruction.END); // try_table
		ctx.wasmCtrlDepth--;
		// The try_table's own end restores a REACHABLE, empty stack, while block $cond
		// owes an eqref -- so say what the return above already made true: nothing falls
		// out of the try_table. Only the catch reaches the block's end, with the payload.
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $cond -- the payload is on the stack
		emitReport(ctx);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $trap
		ctx.writer.write(Instruction.UNREACHABLE);
	}

	/**
	 * Consumes the {@code $lisp-cond} payload cons on the stack and writes one line to
	 * the current {@code *error-output*}.
	 *
	 * <p>
	 * The payload is {@code (condition-instance . message-string)}
	 * ({@link WasmErrorCompiler}), and which half carries the text depends on the signal:
	 * a typed {@code %error-cond} puts a real instance in the car and nil in the cdr, a
	 * plain {@code %error} the other way round. Both are read here through pseudo-locals,
	 * the {@code __hc_cond$<slot>} trick {@code WasmHandlerCaseCompiler} uses, so the
	 * whole line is an ordinary Lisp form: the instance renders through
	 * {@code %condition-report-str} -- ONE call site for the whole program, which is what
	 * lets the per-signal message renders stay deleted ({@code .kb/error-handling.md},
	 * todo-324) -- and a plain message is already the rendered text.
	 */
	private static void emitReport(WasmLispCompiler.Ctx ctx) {
		int payloadSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		int msgSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		emitPayloadHalf(ctx, payloadSlot, 0, condSlot);
		emitPayloadHalf(ctx, payloadSlot, 1, msgSlot);
		String condVar = "__uc_cond$" + condSlot;
		String msgVar = "__uc_msg$" + msgSlot;
		ctx.locals.put(condVar, condSlot);
		ctx.locals.put(msgVar, msgSlot);
		try {
			WasmExprCompiler.compileExpr(reportForm(new LispSymbol(condVar), new LispSymbol(msgVar), ctx), ctx);
		}
		finally {
			ctx.locals.remove(condVar);
			ctx.locals.remove(msgVar);
		}
		// %warn answers nil; the landing pad has no value.
		ctx.writer.write(Instruction.DROP);
	}

	/** Stores {@code (car payload)} (field 0) or {@code (cdr payload)} (field 1). */
	private static void emitPayloadHalf(WasmLispCompiler.Ctx ctx, int payloadSlot, int field, int intoSlot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(field);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(intoSlot);
	}

	/**
	 * {@code (%warn (%string-concat "Unhandled condition: " text))}, where {@code text}
	 * prefers the instance's report and falls back to the message, and an absent or
	 * report-less condition contributes the empty string rather than the value printer's
	 * {@code NIL}.
	 *
	 * <p>
	 * The {@code %condition-report-str} arm is emitted only when the program HAS that
	 * defun: without a condition class there is no typed signal either, so the car is
	 * always nil and the arm would be dead code that drags the whole report machinery in.
	 */
	private static LispVal reportForm(LispSymbol condVar, LispSymbol msgVar, WasmLispCompiler.Ctx ctx) {
		LispVal text = orEmpty("__uc_msg_text", msgVar);
		if (ctx.closRegistry.routesConditionReports()) {
			text = list(new LispSymbol(LispNames.IF), condVar,
					orEmpty("__uc_report", list(new LispSymbol(LispNames.CONDITION_REPORT_STR_INTERNAL), condVar)),
					text);
		}
		return list(new LispSymbol(LispNames.WARN_INTERNAL),
				list(new LispSymbol(LispNames.STRING_CONCAT), new LispString(UncaughtReport.PREFIX), text));
	}

	/** {@code (let ((v form)) (if v v ""))} -- nil renders as nothing, not as NIL. */
	private static LispVal orEmpty(String varName, LispVal form) {
		LispSymbol var = new LispSymbol(varName);
		LispVal binding = list(list(var, form));
		return list(new LispSymbol(LispNames.LET), binding,
				list(new LispSymbol(LispNames.IF), var, var, new LispString("")));
	}

	private static LispVal list(LispVal... items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new LispCons(items[i], result);
		}
		return result;
	}

}
