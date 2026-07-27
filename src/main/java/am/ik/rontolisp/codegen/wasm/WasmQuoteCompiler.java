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
		if (ctx.simd) {
			compilePackedVblockLiteral(ctx, dims, data.length, false, group -> {
				byte[] bytes = new byte[16];
				for (int lane = 0; lane < 2; lane++) {
					int i = group * 2 + lane;
					long bits = i < data.length ? Double.doubleToRawLongBits(data[i]) : 0L;
					for (int b = 0; b < 8; b++) {
						bytes[lane * 8 + b] = (byte) (bits >>> (8 * b));
					}
				}
				return bytes;
			});
			return;
		}
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
	 * Compiles a packed integer-vector literal (ironclad's {@code #N@(...)} table syntax,
	 * or a macro-time value) into its native representation: a bare
	 * {@code TYPE_I8ARR/I16ARR/I32ARR} array of the pre-masked elements, allocated zeroed
	 * with {@code array.new_default} and filled with {@code array.set} (zero elements
	 * skip their store). Shared by the {@code quote} path and the bare-literal path in
	 * {@link WasmExprCompiler}.
	 * @param iv the packed literal
	 * @param ctx the compilation context
	 */
	static void compileIntVectorLiteral(am.ik.rontolisp.LispIntVector iv, WasmLispCompiler.Ctx ctx) {
		long[] data = iv.data();
		int type = WasmArrayCompiler.intArrType(iv.width());
		i32Const(ctx, data.length);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		ctx.writer.writeSignedLeb128(type);
		int dataSlot = ctx.allocTemp();
		setLocal(ctx, dataSlot);
		for (int i = 0; i < data.length; i++) {
			if (data[i] == 0) {
				continue;
			}
			getLocal(ctx, dataSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(type);
			i32Const(ctx, i);
			i32Const(ctx, (int) data[i]);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			ctx.writer.writeSignedLeb128(type);
		}
		getLocal(ctx, dataSlot);
	}

	// --- --simd packed literals (v128 lane groups) --------------------------------
	//
	// Under --simd a packed literal allocates a zeroed TYPE_VBLOCK at the point of
	// evaluation (so a literal inside a loop body allocates once per iteration, exactly
	// as
	// the GC path's array.new does) and fills it a whole lane group at a time with
	// v128.const -- the element bytes are known at compile time. An all-zero group (and
	// the trailing zero sentinel) is skipped: _v_new already wrote it.

	/** Supplies the sixteen little-endian bytes of lane group {@code g}. */
	private interface GroupBytes {

		byte[] of(int group);

	}

	// vb = _v_new(count, kind); vb.groups[g] = v128.const <bytes(g)>; the TYPE_FARRAY.
	private static void compilePackedVblockLiteral(WasmLispCompiler.Ctx ctx, int[] dims, int count, boolean single,
			GroupBytes bytes) {
		i32Const(ctx, count);
		i32Const(ctx, single ? 1 : 0);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_NEW);
		int vbSlot = ctx.allocTemp();
		setLocal(ctx, vbSlot);
		int lanes = single ? 4 : 2;
		int groupCount = (count + lanes - 1) / lanes;
		if (groupCount > 0) {
			int groupsSlot = ctx.allocTemp();
			getLocal(ctx, vbSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_VBLOCK);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
			ctx.writer.writeSignedLeb128(2);
			setLocal(ctx, groupsSlot);
			for (int g = 0; g < groupCount; g++) {
				byte[] groupBytes = bytes.of(g);
				if (isZero(groupBytes)) {
					continue; // _v_new zeroed it
				}
				getLocal(ctx, groupsSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_V128ARR);
				i32Const(ctx, g);
				ctx.writer.write(Instruction.SIMD_PREFIX);
				ctx.writer.writeUnsignedLeb128(Instruction.V128_CONST);
				ctx.writer.write((Object) groupBytes);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_V128ARR);
			}
		}
		// dims buckets, then struct.new TYPE_FARRAY (dims, vb)
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
		getLocal(ctx, dimsSlot);
		getLocal(ctx, vbSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	private static boolean isZero(byte[] bytes) {
		for (byte b : bytes) {
			if (b != 0) {
				return false;
			}
		}
		return true;
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
		if (ctx.simd) {
			compilePackedVblockLiteral(ctx, dims, data.length, true, group -> {
				byte[] bytes = new byte[16];
				for (int lane = 0; lane < 4; lane++) {
					int i = group * 4 + lane;
					int bits = i < data.length ? Float.floatToRawIntBits(data[i]) : 0;
					for (int b = 0; b < 4; b++) {
						bytes[lane * 4 + b] = (byte) (bits >>> (8 * b));
					}
				}
				return bytes;
			});
			return;
		}
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
			case LispInteger i -> WasmEmitHelper.compileIntegerLiteral(i.value(), ctx);
			case am.ik.rontolisp.LispBigInteger bi -> WasmEmitHelper.compileBigIntegerLiteral(bi.value(), ctx);
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
			case am.ik.rontolisp.LispIntVector iv -> compileIntVectorLiteral(iv, ctx);
			// An instance inside quoted data (a #S(...) literal) builds the same
			// TYPE_INSTANCE struct %obj-new does; it is self-evaluating, so it also
			// reaches here from the bare-literal arm of compileExpr.
			case am.ik.rontolisp.LispInstance inst -> compileQuotedInstance(inst, ctx);
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

	/**
	 * Compiles a self-evaluating instance literal in code position (an instance is
	 * neither a symbol nor a cons, CLHS 3.1.2.1.3).
	 * @param inst the instance literal
	 * @param ctx the compilation context
	 */
	static void compileLiteralInstance(am.ik.rontolisp.LispInstance inst, WasmLispCompiler.Ctx ctx) {
		compileQuotedInstance(inst, ctx);
	}

	// Builds a TYPE_INSTANCE over the layout the value already carries: the slots array
	// first, then the baked layout address, then struct.new.
	private static void compileQuotedInstance(am.ik.rontolisp.LispInstance inst, WasmLispCompiler.Ctx ctx) {
		Integer address = ctx.layoutAddresses.get(inst.layout().tag());
		if (address == null || ctx.instanceTypeIndex < 0) {
			throw new UnsupportedOperationException(
					"Cannot quote an instance of an unregistered type: " + inst.layout().tag());
		}
		int slotCount = inst.slotCount();
		refNull(ctx);
		i32Const(ctx, slotCount);
		arrayNew(ctx);
		int slotsSlot = ctx.allocTemp();
		setLocal(ctx, slotsSlot);
		for (int i = 0; i < slotCount; i++) {
			getBuckets(ctx, slotsSlot);
			i32Const(ctx, i);
			compileQuotedVal(inst.slot(i), ctx);
			arraySet(ctx);
		}
		i32Const(ctx, address);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slotsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(ctx.instanceTypeIndex);
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
