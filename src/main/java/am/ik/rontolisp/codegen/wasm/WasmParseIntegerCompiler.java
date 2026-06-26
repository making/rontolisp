package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LispCons;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code parse-integer} inline. The result is an i31 integer (the WASM integer
 * range), so very large inputs overflow. The {@code :radix} and {@code :junk-allowed}
 * keyword values are compiled as ordinary expressions; with junk disallowed (the default)
 * trailing non-whitespace or an empty parse traps. The {@code :start}/{@code :end}
 * keywords of the interpreter are not supported on the compiled backend.
 */
final class WasmParseIntegerCompiler {

	private WasmParseIntegerCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		LispVal radixExpr = null;
		LispVal junkExpr = null;
		for (int i = 2; i + 1 < args.size(); i += 2) {
			String key = (args.get(i) instanceof LispSymbol s) ? s.name() : "";
			switch (key) {
				case LispNames.RADIX_KEYWORD -> radixExpr = args.get(i + 1);
				case LispNames.JUNK_ALLOWED_KEYWORD -> junkExpr = args.get(i + 1);
				default -> throw new UnsupportedOperationException(
						"parse-integer supports only literal :radix and :junk-allowed on the compiled backend, got: "
								+ key);
			}
		}
		var w = ctx.writer;
		int val = ctx.allocTemp();
		int pos = ctx.allocTemp();
		int end = ctx.allocTemp();
		int sign = ctx.allocTemp();
		int acc = ctx.allocTemp();
		int saw = ctx.allocTemp();
		int radix = ctx.allocTemp();
		int junk = ctx.allocTemp();
		int bch = ctx.allocTemp();
		int dval = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		setSlot(ctx, val);
		// pos = offset + 1 (skip the opening quote)
		strField(ctx, val, 0);
		constAdd(ctx, 1);
		box(ctx, pos);
		// end = offset + length - 1 (before the closing quote)
		strField(ctx, val, 0);
		strField(ctx, val, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		box(ctx, end);
		// radix
		if (radixExpr != null) {
			WasmExprCompiler.compileExpr(radixExpr, ctx);
			setSlot(ctx, radix);
		}
		else {
			constI31(ctx, 10);
			setSlot(ctx, radix);
		}
		// junk = (junkExpr is non-nil) ? 1 : 0
		if (junkExpr != null) {
			WasmExprCompiler.compileExpr(junkExpr, ctx);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.I32_EQZ);
			box(ctx, junk);
		}
		else {
			constI31(ctx, 0);
			setSlot(ctx, junk);
		}
		// skip leading whitespace
		skipWhitespace(ctx, pos, end, bch);
		// sign = 1
		constI31(ctx, 1);
		setSlot(ctx, sign);
		get(ctx, pos);
		get(ctx, end);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		loadByte(ctx, pos);
		box(ctx, bch);
		get(ctx, bch);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128('-');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		constI31(ctx, -1);
		setSlot(ctx, sign);
		w.write(Instruction.END);
		get(ctx, bch);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128('+');
		w.write(Instruction.I32_EQ);
		get(ctx, bch);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128('-');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		incr(ctx, pos);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// acc = 0; saw = 0
		constI31(ctx, 0);
		setSlot(ctx, acc);
		constI31(ctx, 0);
		setSlot(ctx, saw);
		// digit loop
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(ctx, pos);
		get(ctx, end);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		loadByte(ctx, pos);
		box(ctx, bch);
		emitWeight(ctx, bch);
		box(ctx, dval);
		// if (dval < 0 || dval >= radix) break
		get(ctx, dval);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		get(ctx, dval);
		get(ctx, radix);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.I32_OR);
		w.write(Instruction.BR_IF, 1);
		// acc = acc * radix + dval
		get(ctx, acc);
		get(ctx, radix);
		w.write(Instruction.I32_MUL);
		get(ctx, dval);
		w.write(Instruction.I32_ADD);
		box(ctx, acc);
		constI31(ctx, 1);
		setSlot(ctx, saw);
		incr(ctx, pos);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// if (junk == 0) { skip trailing whitespace; if (pos != end) trap }
		get(ctx, junk);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		skipWhitespace(ctx, pos, end, bch);
		get(ctx, pos);
		get(ctx, end);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// result: saw == 0 ? (junk ? nil : trap) : i31(sign * acc)
		get(ctx, saw);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		get(ctx, junk);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		get(ctx, sign);
		get(ctx, acc);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
	}

	// while (pos < end && isWhitespace(byte[pos])) pos++
	private static void skipWhitespace(WasmLispCompiler.Ctx ctx, int pos, int end, int bch) {
		var w = ctx.writer;
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(ctx, pos);
		get(ctx, end);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		loadByte(ctx, pos);
		box(ctx, bch);
		isWhitespace(ctx, bch);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		incr(ctx, pos);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Pushes the digit weight of the i31-boxed byte in bch, or -1.
	private static void emitWeight(WasmLispCompiler.Ctx ctx, int bch) {
		var w = ctx.writer;
		range(ctx, bch, '0', '9');
		w.write(Instruction.IF);
		w.write(Type.I32);
		get(ctx, bch);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128('0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.ELSE);
		range(ctx, bch, 'A', 'Z');
		w.write(Instruction.IF);
		w.write(Type.I32);
		get(ctx, bch);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128('A' - 10);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.ELSE);
		range(ctx, bch, 'a', 'z');
		w.write(Instruction.IF);
		w.write(Type.I32);
		get(ctx, bch);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128('a' - 10);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-1);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	private static void isWhitespace(WasmLispCompiler.Ctx ctx, int bch) {
		var w = ctx.writer;
		eqConst(ctx, bch, ' ');
		eqConst(ctx, bch, '\t');
		w.write(Instruction.I32_OR);
		eqConst(ctx, bch, '\n');
		w.write(Instruction.I32_OR);
		eqConst(ctx, bch, '\r');
		w.write(Instruction.I32_OR);
	}

	private static void eqConst(WasmLispCompiler.Ctx ctx, int slot, int value) {
		get(ctx, slot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.I32_EQ);
	}

	private static void range(WasmLispCompiler.Ctx ctx, int slot, char lo, char hi) {
		var w = ctx.writer;
		get(ctx, slot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(lo);
		w.write(Instruction.I32_GE_S);
		get(ctx, slot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(hi);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
	}

	private static void strField(WasmLispCompiler.Ctx ctx, int slot, int field) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeSignedLeb128(field);
	}

	private static void loadByte(WasmLispCompiler.Ctx ctx, int posSlot) {
		get(ctx, posSlot);
		ctx.writer.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
	}

	private static void incr(WasmLispCompiler.Ctx ctx, int slot) {
		get(ctx, slot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		box(ctx, slot);
	}

	private static void constAdd(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.I32_ADD);
	}

	// Pushes the raw i32 stored (i31-boxed) in the slot.
	private static void get(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	// Boxes the i32 on the stack as an i31 and stores it in the slot.
	private static void box(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setSlot(ctx, slot);
	}

	private static void constI31(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private static void setSlot(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

}
