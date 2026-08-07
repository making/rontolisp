package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.DoubleValuedForms;
import am.ik.rontolisp.compiler.StringValuedForms;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the three built-ins of the print family -- {@code print}, {@code prin1} and
 * {@code princ} -- which are one emitter with two switches: whether the rendering is the
 * READABLE one ({@code *print-escape*} true: {@code print} / {@code prin1}) or the
 * display one ({@code princ}), and whether a newline follows ({@code print} only). They
 * share the emitter so that the {@link WasmLiteralPrint} fold cannot exist for one
 * spelling and not the others.
 *
 * <p>
 * Above the literal fold sit two STATIC-TYPE shortcuts, and what they buy is reachability
 * rather than instructions: the value dispatch ({@code _princ_val} / {@code _print_val})
 * is the root of nearly every printer in the runtime, so an argument the compiler can
 * type keeps all of them out of the module. Printing one float used to cost 3.7 KB in a
 * module whose float printer is 379 bytes. See {@link StringValuedForms} and
 * {@link DoubleValuedForms}.
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
		if (!readably && !newline && rendered == null && StringValuedForms.certainlyString(obj)) {
			// princ of a string IS write-string: the same text, the same returned
			// object, and no reason to route it through the value dispatch (or the
			// character-vector normalizer in front of that) when the argument form
			// cannot answer anything but an immutable string.
			WasmExprCompiler.compileExpr(obj, ctx);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			if (stream != null) {
				WasmExprCompiler.compileExpr(stream, ctx);
			}
			else {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			return;
		}
		if (stream != null) {
			// (print value stream): render to a string, route it -- and, for print, a
			// newline -- via _write_stream_str (a stream written twice is evaluated once
			// into a temp).
			WasmExprCompiler.compileExpr(obj, ctx);
			if (rendered != null) {
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(objSlot);
				WasmEmitHelper.compileStringLiteral("\"" + rendered + "\"", ctx);
			}
			else {
				ctx.writer.write(Instruction.TEE_LOCAL);
				ctx.writer.writeUnsignedLeb128(objSlot);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(
						readably ? WasmLispCompiler.FUNC_PRIN1_TO_STR : WasmLispCompiler.FUNC_PRINC_TO_STR);
			}
			int streamSlot = newline ? ctx.allocTemp() : -1;
			WasmExprCompiler.compileExpr(stream, ctx);
			if (newline) {
				ctx.writer.write(Instruction.TEE_LOCAL);
				ctx.writer.writeUnsignedLeb128(streamSlot);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			if (newline) {
				WasmTerpriCompiler.emitNewlineStringStruct(ctx);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(streamSlot);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
				ctx.writer.write(Instruction.DROP);
			}
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			return;
		}
		WasmExprCompiler.compileExpr(obj, ctx);
		if (rendered != null) {
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			WasmLiteralPrint.emitStaticWrite(rendered, obj, readably, ctx);
		}
		else if (DoubleValuedForms.certainlyDouble(obj)) {
			// A float takes the SAME arm in both dispatches -- unbox the struct, call
			// the digit printer -- so calling it directly is byte-identical output and
			// leaves the whole value dispatch (and every printer reachable only from
			// it) unreferenced. See the class comment.
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.writeUnsignedLeb128(0);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		}
		else {
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer
				.writeUnsignedLeb128(readably ? WasmLispCompiler.FUNC_PRINT_VAL : WasmLispCompiler.FUNC_PRINC_VAL);
		}
		if (newline) {
			WasmLiteralPrint.emitNewline(ctx);
		}
		// Return the argument
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(objSlot);
	}

}
