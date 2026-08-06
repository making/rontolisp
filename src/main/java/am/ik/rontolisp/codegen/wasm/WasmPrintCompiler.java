package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the three built-ins of the print family -- {@code print}, {@code prin1} and
 * {@code princ} -- which are one emitter with two switches: whether the rendering is the
 * READABLE one ({@code *print-escape*} true: {@code print} / {@code prin1}) or the
 * display one ({@code princ}), and whether a newline follows ({@code print} only). They
 * share the emitter so that the {@link WasmLiteralPrint} fold cannot exist for one
 * spelling and not the others.
 */
final class WasmPrintCompiler {

	private WasmPrintCompiler() {
	}

	/** {@code print}: the readable rendering followed by a newline. */
	static void compilePrint(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, true, true);
	}

	/** {@code prin1}: the readable rendering, no newline. */
	static void compilePrin1(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, true, false);
	}

	/** {@code princ}: the display rendering (no quotes, no escapes), no newline. */
	static void compilePrinc(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, false, false);
	}

	private static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, boolean readably, boolean newline) {
		List<LispVal> args = cons.toList();
		LispVal obj = args.get(1);
		// The print family returns its argument (CL semantics); stash the object in a
		// temp so it can be returned after printing, not nil.
		int objSlot = ctx.allocTemp();
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (WasmEmitHelper.streamArg).
		LispVal stream = WasmEmitHelper.streamArg(ctx, args.size() > 2 ? args.get(2) : null);
		// The print-object hook cannot be in play here: this runs inside the
		// print-object-free gate (WasmExprCompiler.compilePrintOperator).
		String rendered = WasmLiteralPrint.rendered(obj, readably);
		if (stream != null) {
			// (print value stream): render to a string, route it -- and, for print, a
			// newline -- via _write_stream_str (a stream written twice is evaluated once
			// into a temp).
			WasmExprCompiler.compileExpr(obj, ctx);
			if (rendered != null) {
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(objSlot);
				WasmEmitHelper.compileStringLiteral("\"" + rendered + "\"", ctx);
			}
			else {
				ctx.writer.write(Instruction.TEE_LOCAL);
				ctx.writer.writeSignedLeb128(objSlot);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(
						readably ? WasmLispCompiler.FUNC_PRIN1_TO_STR : WasmLispCompiler.FUNC_PRINC_TO_STR);
			}
			int streamSlot = newline ? ctx.allocTemp() : -1;
			WasmExprCompiler.compileExpr(stream, ctx);
			if (newline) {
				ctx.writer.write(Instruction.TEE_LOCAL);
				ctx.writer.writeSignedLeb128(streamSlot);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			if (newline) {
				WasmTerpriCompiler.emitNewlineStringStruct(ctx);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(streamSlot);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
				ctx.writer.write(Instruction.DROP);
			}
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			return;
		}
		WasmExprCompiler.compileExpr(obj, ctx);
		if (rendered != null) {
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			WasmLiteralPrint.emitStaticWrite(rendered, obj, readably, ctx);
		}
		else {
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(readably ? WasmLispCompiler.FUNC_PRINT_VAL : WasmLispCompiler.FUNC_PRINC_VAL);
		}
		if (newline) {
			WasmLiteralPrint.emitNewline(ctx);
		}
		// Return the argument
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(objSlot);
	}

}
