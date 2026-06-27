package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code quote} special form.
 */
final class WasmQuoteCompiler {

	private WasmQuoteCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx);
	}

	/**
	 * Emits the construction of a self-evaluating array literal ({@code #(...)}). Shared
	 * by the {@code quote} path and the bare-literal path in {@link WasmExprCompiler}.
	 * @param array the literal array
	 * @param ctx the compilation context
	 */
	static void compileLiteralArray(LispArray array, WasmLispCompiler.Ctx ctx) {
		compileQuotedArray(array, ctx);
	}

	private static void compileQuotedVal(LispVal val, WasmLispCompiler.Ctx ctx) {
		switch (val) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case am.ik.rontolisp.LispRatio r -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(r.numerator().intValue());
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(r.denominator().intValue());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_RATIO);
			}
			case LispDouble d -> {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(d.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case LispNil ignored -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> WasmEmitHelper.emitTrue(ctx);
			case LispString s -> WasmEmitHelper.compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> WasmEmitHelper.compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx);
			case LispArray array -> compileQuotedArray(array, ctx);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	private static void compileQuotedCons(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileQuotedVal(cons.car(), ctx);
		compileQuotedVal(cons.cdr(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	// Builds the runtime array representation: a TYPE_CELL box wrapping a header
	// cons (dims . data), where dims and data are both TYPE_HASH_BUCKETS arrays. dims
	// holds
	// the dimension sizes as i31 integers, data holds the row-major elements. This
	// mirrors
	// the layout produced by WasmArrayCompiler.compileMake.
	private static void compileQuotedArray(LispArray array, WasmLispCompiler.Ctx ctx) {
		int[] dims = array.dimensions();
		LispVal[] data = array.data();
		// data array: array.new TYPE_HASH_BUCKETS (null, data.length), then fill each
		// slot.
		refNull(ctx);
		i32Const(ctx, data.length);
		arrayNew(ctx);
		int dataSlot = ctx.allocTemp();
		setLocal(ctx, dataSlot);
		for (int i = 0; i < data.length; i++) {
			getBuckets(ctx, dataSlot);
			i32Const(ctx, i);
			compileQuotedVal(data[i] == null ? LispNil.INSTANCE : data[i], ctx);
			arraySet(ctx);
		}
		// dims array: array.new TYPE_HASH_BUCKETS (null, dims.length), then fill with
		// i31s.
		refNull(ctx);
		i32Const(ctx, dims.length);
		arrayNew(ctx);
		int dimsSlot = ctx.allocTemp();
		setLocal(ctx, dimsSlot);
		for (int d = 0; d < dims.length; d++) {
			getBuckets(ctx, dimsSlot);
			i32Const(ctx, d);
			i32Const(ctx, dims[d]);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			arraySet(ctx);
		}
		// header = cons(dims, data); cell = struct.new TYPE_CELL(header)
		getBuckets(ctx, dimsSlot);
		getBuckets(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	private static void refNull(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	private static void i32Const(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	private static void setLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	// Pushes the bucket array stored in slot, cast to TYPE_HASH_BUCKETS.
	private static void getBuckets(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void arrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void arraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

}
