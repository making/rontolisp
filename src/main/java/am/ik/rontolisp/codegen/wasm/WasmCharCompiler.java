package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the character built-ins. A character is a {@code TYPE_CHAR} struct holding the
 * i32 code point. Because every compiler-allocated temporary local is a {@code (ref null
 * eq)}, i32 intermediates that must outlive a single stack expression are boxed as i31
 * refs and unboxed on use. Strings are UTF-8 encoded byte sequences (a Lisp index
 * {@code i} names the i-th Unicode CHARACTER, whose UTF-8 sequence starts at a byte
 * offset the {@code _str_char_at} runtime helper walks to and decodes).
 */
final class WasmCharCompiler {

	private WasmCharCompiler() {
	}

	/** {@code (char string index)} / {@code (schar string index)}. */
	static void compileChar(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// A mutable character vector normalizes to a string first; the string carries
		// its content as UTF-8 bytes and _str_char_at walks to the i-th character.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		WasmEmitHelper.emitStrCharAtCall(ctx);
		makeChar(ctx);
	}

	/** {@code (char-code ch)}. */
	static void compileCharCode(LispCons cons, WasmLispCompiler.Ctx ctx) {
		pushCode(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/** {@code (code-char n)}. */
	static void compileCodeChar(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		makeChar(ctx);
	}

	/** {@code (characterp x)}. */
	static void compileCharacterp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/** {@code (char-upcase ch)}. Full-Unicode fold via {@code _char_upcase}. */
	static void compileUpcase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileCaseFold(cons, ctx, WasmLispCompiler.FUNC_CHAR_UPCASE);
	}

	/** {@code (char-downcase ch)}. Full-Unicode fold via {@code _char_downcase}. */
	static void compileDowncase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileCaseFold(cons, ctx, WasmLispCompiler.FUNC_CHAR_DOWNCASE);
	}

	// Pushes the code point, calls the case-fold helper (identity for a non-letter code
	// point) and boxes the returned i32 back into a TYPE_CHAR struct.
	// Character.toUpperCase
	// / toLowerCase always return a SINGLE code point, so the whole char fold stays
	// inside
	// TYPE_CHAR without allocating a string.
	private static void compileCaseFold(LispCons cons, WasmLispCompiler.Ctx ctx, int funcIndex) {
		pushCode(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(funcIndex);
		makeChar(ctx);
	}

	/** {@code (alpha-char-p ch)} (ASCII letters). */
	static void compileAlphaCharP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		int t = ctx.allocTemp();
		pushCode(cons.toList().get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(t);
		inRange(ctx, t, 'A', 'Z');
		inRange(ctx, t, 'a', 'z');
		ctx.writer.write(Instruction.I32_OR);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/** {@code (digit-char-p ch [radix])}. */
	static void compileDigitCharP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int c = ctx.allocTemp();
		int r = ctx.allocTemp();
		int d = ctx.allocTemp();
		pushCode(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(c);
		if (args.size() > 2) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
		}
		else {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(10);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(r);
		// d = weight of c (digit / letter), or -1
		inRange(ctx, c, '0', '9');
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		getI32(ctx, c);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128('0');
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.ELSE);
		emitLetterWeight(ctx, c, 'A', 'Z');
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		getI32(ctx, c);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128('A' - 10);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.ELSE);
		emitLetterWeight(ctx, c, 'a', 'z');
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		getI32(ctx, c);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128('a' - 10);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(-1);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(d);
		// (d >= 0 && d < radix) ? i31(d) : nil
		getI32(ctx, d);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_GE_S);
		getI32(ctx, d);
		getI32(ctx, r);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getI32(ctx, d);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	/** {@code (char= ...)}. */
	static void compileEq(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_EQ);
	}

	/** {@code (char< ...)}. */
	static void compileLt(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_LT_S);
	}

	/** {@code (char<= ...)}. */
	static void compileLe(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileChain(cons, ctx, Instruction.I32_LE_S);
	}

	private static void compileChain(LispCons cons, WasmLispCompiler.Ctx ctx, int cmpOpcode) {
		List<LispVal> args = cons.toList();
		int prev = ctx.allocTemp();
		int cur = ctx.allocTemp();
		int acc = ctx.allocTemp();
		pushCode(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(prev);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(acc);
		for (int i = 2; i < args.size(); i++) {
			pushCode(args.get(i), ctx);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(cur);
			getI32(ctx, acc);
			getI32(ctx, prev);
			getI32(ctx, cur);
			ctx.writer.write(cmpOpcode);
			ctx.writer.write(Instruction.I32_AND);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(acc);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(cur);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(prev);
		}
		getI32(ctx, acc);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// Pushes the i32 code point of the character produced by the argument expression.
	private static void pushCode(LispVal arg, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(arg, ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.writeUnsignedLeb128(0);
	}

	// Boxes the i32 on the stack into a TYPE_CHAR struct.
	static void makeChar(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
	}

	// Reads an i31-boxed i32 temp back onto the stack as a raw i32.
	private static void getI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	// Pushes 1 when the i31-boxed code in slot is within [lo, hi], else 0.
	private static void inRange(WasmLispCompiler.Ctx ctx, int slot, char lo, char hi) {
		getI32(ctx, slot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(lo);
		ctx.writer.write(Instruction.I32_GE_S);
		getI32(ctx, slot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(hi);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.I32_AND);
	}

	// Pushes 1 when the i31-boxed code in slot is a letter within [lo, hi], else 0
	// (a synonym for inRange, named for readability at the digit-weight call sites).
	private static void emitLetterWeight(WasmLispCompiler.Ctx ctx, int slot, char lo, char hi) {
		inRange(ctx, slot, lo, hi);
	}

}
