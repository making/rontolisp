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

	/**
	 * Emits a packed float-array literal ({@code #d(...)}) as a {@code TYPE_FARRAY}
	 * struct {@code (dims . data)}: {@code dims} a {@code TYPE_HASH_BUCKETS} of i31
	 * dimension sizes (as for a general array), {@code data} a {@code TYPE_F64ARR} of the
	 * unboxed row-major {@code f64} elements. This is the native packed representation,
	 * disjoint from the general array's {@code TYPE_CELL}. Shared by the {@code quote}
	 * path and the bare-literal path in {@link WasmExprCompiler}.
	 * @param fa the packed literal
	 * @param ctx the compilation context
	 */
	static void compilePackedLiteral(am.ik.rontolisp.LispDoubleFloatArray fa, WasmLispCompiler.Ctx ctx) {
		double[] data = fa.data();
		int[] dims = fa.dims();
		// data: array.new TYPE_F64ARR (0.0, data.length), then array.set each element.
		f64Const(ctx, 0.0);
		i32Const(ctx, data.length);
		f64ArrayNew(ctx);
		int dataSlot = ctx.allocTemp();
		setLocal(ctx, dataSlot);
		for (int i = 0; i < data.length; i++) {
			getF64Arr(ctx, dataSlot);
			i32Const(ctx, i);
			f64Const(ctx, data[i]);
			f64ArraySet(ctx);
		}
		// dims: array.new TYPE_HASH_BUCKETS (null, dims.length), filled with i31 sizes.
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
		// struct.new TYPE_FARRAY (dims, data)
		getLocal(ctx, dimsSlot);
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	/**
	 * Emits a packed single-float array literal ({@code #f(...)}) as a
	 * {@code TYPE_FARRAY} struct {@code (dims . data)} whose {@code data} is a
	 * {@code TYPE_F32ARR} ({@code array (mut f32)}) of the unboxed row-major {@code f32}
	 * elements -- the same struct type as the double width, distinguished at read time by
	 * {@code ref.test $f32arr}. Each element is emitted as its widening {@code f64}
	 * constant narrowed with {@code f32.demote_f64} (an exact round-trip), so no f32
	 * immediate encoding is needed (mirrors the JVM {@code d2f} literal trick). Shared by
	 * the {@code quote} path and the bare-literal path in {@link WasmExprCompiler}.
	 * @param fa the packed single-float literal
	 * @param ctx the compilation context
	 */
	static void compileSinglePackedLiteral(am.ik.rontolisp.LispSingleFloatArray fa, WasmLispCompiler.Ctx ctx) {
		float[] data = fa.data();
		int[] dims = fa.dims();
		// data: array.new TYPE_F32ARR (0.0, data.length), then array.set each element.
		f32Const(ctx, 0.0f);
		i32Const(ctx, data.length);
		f32ArrayNew(ctx);
		int dataSlot = ctx.allocTemp();
		setLocal(ctx, dataSlot);
		for (int i = 0; i < data.length; i++) {
			getF32Arr(ctx, dataSlot);
			i32Const(ctx, i);
			f32Const(ctx, data[i]);
			f32ArraySet(ctx);
		}
		// dims: array.new TYPE_HASH_BUCKETS (null, dims.length), filled with i31 sizes.
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
		// struct.new TYPE_FARRAY (dims, data)
		getLocal(ctx, dimsSlot);
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
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
			case am.ik.rontolisp.LispChar c -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(c.codePoint());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
			}
			case LispSymbol sym -> WasmEmitHelper.compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx);
			case LispArray array -> compileQuotedArray(array, ctx);
			case am.ik.rontolisp.LispDoubleFloatArray fa -> compilePackedLiteral(fa, ctx);
			case am.ik.rontolisp.LispSingleFloatArray fa -> compileSinglePackedLiteral(fa, ctx);
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
	// cons (dims . (meta . data)), where dims and data are both TYPE_HASH_BUCKETS
	// arrays and meta is a (fillPointer . (adjustable . offset)) chain. dims holds the
	// dimension sizes as i31 integers, data holds the row-major elements; a literal
	// array never has a fill pointer, is not adjustable and is not displaced, so the
	// meta fields stay null with a 0 offset. This mirrors the layout produced by
	// WasmArrayCompiler.compileMake.
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
		// header = cons(dims, cons(cons(null, cons(null, 0)), data));
		// cell = struct.new TYPE_CELL(header)
		getBuckets(ctx, dimsSlot);
		refNull(ctx);
		refNull(ctx);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		getBuckets(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
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

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

	private static void setLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	// Pushes the f64 array stored in slot, cast to TYPE_F64ARR.
	private static void getF64Arr(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
	}

	private static void f64ArrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	private static void f64ArraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// Pushes the f32 array stored in slot, cast to TYPE_F32ARR.
	private static void getF32Arr(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
	}

	private static void f32ArrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F32ARR);
	}

	private static void f32ArraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F32ARR);
	}

	// Pushes an f32 constant: emitted as its widening f64 constant narrowed with
	// f32.demote_f64 (an exact round-trip), so no f32 immediate encoding is needed.
	private static void f32Const(WasmLispCompiler.Ctx ctx, float value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
		ctx.writer.write(Instruction.F32_DEMOTE_F64);
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
