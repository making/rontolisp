package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code fresh-line} built-in function. Writes a newline only when stdout is
 * not already at the start of a line, as tracked by {@code LINE_START_ADDR} (which
 * {@code _write_str} updates on every stdout write), and answers whether it did.
 */
final class WasmFreshLineCompiler {

	private WasmFreshLineCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// A statement takes the offer to answer nothing (compiler/ToplevelStatements), so
		// a site whose answer no one reads does not keep the symbol t reachable.
		boolean answerDropped = ctx.valueDropped == cons;
		if (answerDropped) {
			ctx.valueDropped = null;
		}
		java.util.List<am.ik.rontolisp.LispVal> args = cons.toList();
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (WasmEmitHelper.streamArg); either goes through the
		// handle-aware _fresh_line_stream runtime helper.
		am.ik.rontolisp.LispVal stream = WasmEmitHelper.streamArg(ctx, args.size() > 1 ? args.get(1) : null);
		if (stream != null) {
			// The call is what makes the writes keep the descriptors' line starts, in a
			// table no other module carries (QuoteGlobals.lineStartTable).
			ctx.quoteGlobals.lineStartTable();
			WasmExprCompiler.compileExpr(stream, ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FRESH_LINE_STREAM);
			if (answerDropped) {
				ctx.writer.write(Instruction.DROP);
			}
			return;
		}
		// if (memory[LINE_START_ADDR] != 0) { write "\n"; t } else nil
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.LINE_START_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		if (answerDropped) {
			ctx.writer.write(Instruction.IF, 0x40);
		}
		else {
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		if (!answerDropped) {
			WasmEmitHelper.emitTrue(ctx);
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.END);
	}

}
