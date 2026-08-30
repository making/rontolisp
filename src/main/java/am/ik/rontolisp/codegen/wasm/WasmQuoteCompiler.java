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
		// A quoted AGGREGATE is one shared constant (.kb/quoted-data.md): the site
		// caches its build in a dedicated module global -- global.get; build and
		// global.set when still null; global.get -- so every evaluation answers the
		// SAME object, like the interpreter, whose evalQuote hands back the reader's
		// datum. Lazy rather than start-time because the global count is only known
		// once every body has compiled; wasm is single-threaded here, so the first
		// evaluation wins with no race. Keyed by datum IDENTITY
		// (WasmLispCompiler.QuoteGlobals), so a macro expansion splicing one template
		// datum into several sites shares one constant across them too. An atom keeps
		// the inline emission below; a BARE array literal never comes here and stays a
		// constructor (.kb/array-literals.md), which is what PureBuiltinFolder's
		// packed-table fold rests on.
		if (isSharedAggregate(quoted)) {
			emitSharedConstant(quoted, ctx, () -> compileQuotedVal(quoted, ctx));
			return;
		}
		compileQuotedVal(quoted, ctx);
	}

	/**
	 * Emits one datum's build behind its lazy module global, so every evaluation of the
	 * site answers the same object. Shared by the {@code quote} path and the
	 * bare-instance-literal path, which have the same identity rule and differ only in
	 * what they build.
	 * @param datum the datum the global is keyed by (identity, not equality)
	 * @param ctx the compilation context
	 * @param build emits the construction, leaving exactly one value on the stack
	 */
	private static void emitSharedConstant(LispVal datum, WasmLispCompiler.Ctx ctx, Runnable build) {
		int global = ctx.quoteGlobals.indexFor(datum);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(global);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		build.run();
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(global);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(global);
	}

	/**
	 * Whether a quoted datum is memoized into one shared constant. Exactly the mutable
	 * aggregates: an atom (a number, a string, a symbol, a character, nil, t) has no
	 * identity a program can observe diverging, so it keeps its inline emission.
	 * @param val the quoted datum
	 * @return true when the datum gets a shared module global
	 */
	private static boolean isSharedAggregate(LispVal val) {
		return val instanceof LispCons || val instanceof LispArray || val instanceof am.ik.rontolisp.LispInstance
				|| val instanceof am.ik.rontolisp.LispDoubleFloatArray
				|| val instanceof am.ik.rontolisp.LispSingleFloatArray || val instanceof am.ik.rontolisp.LispIntVector;
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	/**
	 * Compiles a packed integer-vector literal (ironclad's {@code #N@(...)} table syntax,
	 * or a table the {@code PureBuiltinFolder} reduced a literal
	 * {@code coerce}/{@code make-array} to) into its native representation: a bare
	 * {@code TYPE_I8ARR/I16ARR/I32ARR} array of the pre-masked elements. Shared by the
	 * {@code quote} path and the bare-literal path in {@link WasmExprCompiler}.
	 *
	 * <p>
	 * <b>Two emissions, and the cheaper one wins.</b> A literal can be allocated zeroed
	 * with {@code array.new_default} and filled with {@code array.set} -- around 12 bytes
	 * an element, but no fixed cost and a ZERO element costs nothing at all -- or its
	 * elements can go into the module's static data, little-endian at the element width,
	 * with the site a copy loop over them: {@code w/8} bytes an element (4 for a CRC
	 * table, 1 for a byte table) plus a fixed ~55. {@link #prefersDataSegment} counts
	 * both and picks, so a real lookup table is baked (that is the whole point of the
	 * fold that produces these -- chipz's ~700 constant-table elements were 11.8 bytes
	 * each as the cons list the {@code coerce} spelling built) while a short one, and a
	 * mostly-zero one of any length, keeps the emission it always had.
	 *
	 * <p>
	 * The array is still allocated and filled AT THE SITE, so each evaluation of the
	 * literal yields a fresh, independently mutable vector -- the property the fold rests
	 * on. The blob is registered as a droppable data range, so {@code --optimize} cuts
	 * the bytes when the last body holding their address dies.
	 * @param iv the packed literal
	 * @param ctx the compilation context
	 */
	static void compileIntVectorLiteral(am.ik.rontolisp.LispIntVector iv, WasmLispCompiler.Ctx ctx) {
		long[] data = iv.data();
		int type = WasmArrayCompiler.intArrType(iv.width());
		// An async resume body declares its own locals and never resolves an i64 scratch
		// placeholder, which is why every other i64-local user (fusion, the raw let
		// locals, the counted dotimes) stands down there too; the array.set run needs no
		// counter, so it is the fallback rather than a missing case.
		if (ctx.asyncResume == null && prefersDataSegment(data, iv.width())) {
			compileIntVectorFromData(iv, type, ctx);
			return;
		}
		i32Const(ctx, data.length);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		ctx.writer.writeUnsignedLeb128(type);
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
			ctx.writer.writeUnsignedLeb128(type);
		}
		getLocal(ctx, dataSlot);
	}

	/**
	 * The copy loop's fixed cost in bytes: the allocation, the two counter locals, the
	 * cast, the two scaled reads of the counter, the load, the store, the increment, the
	 * bound test and the branch. Counted from the emitter below (~55 with a four-byte
	 * base address), and used only to CHOOSE between two correct emissions, so being a
	 * few bytes off moves the crossover by one element and nothing else.
	 */
	private static final int DATA_SEGMENT_FIXED_BYTES = 55;

	/**
	 * Whether baking the elements as static data beats storing them one {@code array.set}
	 * at a time. Counting rather than thresholding matters in one direction that a
	 * threshold gets wrong: the {@code array.set} run SKIPS a zero element entirely, so a
	 * long mostly-zero literal is nearly free that way and would pay its full width per
	 * element as data.
	 */
	private static boolean prefersDataSegment(long[] data, int width) {
		// array.new_default + the two slot accesses, then per stored element:
		// local.get, ref.cast, the index, the value, array.set.
		long inline = 9;
		for (int i = 0; i < data.length; i++) {
			if (data[i] != 0) {
				inline += 10 + signedLebLength(i) + signedLebLength((int) data[i]);
			}
		}
		return DATA_SEGMENT_FIXED_BYTES + (long) data.length * (width / 8) < inline;
	}

	/** The byte length of a signed LEB128 encoding, as {@code WasmWriter} emits it. */
	private static int signedLebLength(int value) {
		int length = 1;
		for (int remaining = value >> 6; remaining != 0 && remaining != -1; remaining >>= 7) {
			length++;
		}
		return length;
	}

	// arr = array.new_default T (count); for (n = 0; n < count; n++)
	// arr[n] = load_u<width>(base + (n << log2 bytes)); arr
	//
	// The loop counter is an i64 scratch local -- the only raw local flavour a body has,
	// and the watermark is restored afterwards so the slot is reused like a fused site's.
	// The base is an explicit i32.const rather than the load's memarg offset ON PURPOSE:
	// the tree shaker's droppable-range probe reads i32.const values out of the surviving
	// bodies and skips memargs, so a base hidden in a memarg would let it cut a table its
	// own reader still addresses.
	//
	// Elements are stored little-endian, which is what the load reads back on every host:
	// wasm's linear memory is little-endian by definition.
	private static void compileIntVectorFromData(am.ik.rontolisp.LispIntVector iv, int type, WasmLispCompiler.Ctx ctx) {
		long[] data = iv.data();
		int bytes = iv.width() / 8;
		int shift = Integer.numberOfTrailingZeros(bytes);
		byte[] blob = new byte[data.length * bytes];
		for (int i = 0; i < data.length; i++) {
			for (int b = 0; b < bytes; b++) {
				blob[i * bytes + b] = (byte) (data[i] >>> (8 * b));
			}
		}
		int base = ctx.stringTable.appendShakeableBlob(blob);
		int arrSlot = ctx.allocTemp();
		int savedI64Locals = ctx.nextI64Local;
		int counter = ctx.allocI64Temp();
		i32Const(ctx, data.length);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		ctx.writer.writeUnsignedLeb128(type);
		setLocal(ctx, arrSlot);
		i64Const(ctx, 0);
		setI64Local(ctx, counter);
		ctx.writer.write(Instruction.LOOP, 0x40);
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(type);
		getI64Local(ctx, counter);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		getI64Local(ctx, counter);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		if (shift > 0) {
			i32Const(ctx, shift);
			ctx.writer.write(Instruction.I32_SHL);
		}
		i32Const(ctx, base);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(switch (bytes) {
			case 1 -> Instruction.I32_LOAD8_U;
			case 2 -> Instruction.I32_LOAD16_U;
			default -> Instruction.I32_LOAD;
		});
		ctx.writer.writeUnsignedLeb128(shift); // alignment hint: the natural one
		ctx.writer.writeUnsignedLeb128(0); // offset
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(type);
		getI64Local(ctx, counter);
		i64Const(ctx, 1);
		ctx.writer.write(Instruction.I64_ADD);
		setI64Local(ctx, counter);
		getI64Local(ctx, counter);
		i64Const(ctx, data.length);
		ctx.writer.write(Instruction.I64_LT_U);
		ctx.writer.write(Instruction.BR_IF);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, arrSlot);
		ctx.nextI64Local = savedI64Locals;
	}

	private static void i64Const(WasmLispCompiler.Ctx ctx, long value) {
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	private static void getI64Local(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void setI64Local(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_NEW);
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
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
			ctx.writer.writeUnsignedLeb128(2);
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
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_V128ARR);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
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
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_RATIO);
			}
			case LispDouble d -> {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(d.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case LispNil ignored -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> WasmEmitHelper.emitTrue(ctx);
			case LispString s -> WasmEmitHelper.compileStringLiteral(s.literal(), ctx);
			case am.ik.rontolisp.LispChar c -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(c.codePoint());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		getBuckets(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	/**
	 * Compiles a self-evaluating instance literal in code position (an instance is
	 * neither a symbol nor a cons, CLHS 3.1.2.1.3).
	 * <p>
	 * It is one SHARED constant, exactly as under {@code quote} (.kb/quoted-data.md): the
	 * interpreter's {@code LispInstance} arm hands the reader's own instance back at
	 * every evaluation, so the site memoizes into the same lazy module global a quoted
	 * datum uses. This is the one literal family that does NOT follow the
	 * fresh-per-evaluation rule of an array literal (.kb/array-literals.md): there the
	 * interpreter could be moved, here it cannot -- the same arm carries every live
	 * instance the evaluator splices back through {@code (quote <value>)}.
	 * @param inst the instance literal
	 * @param ctx the compilation context
	 */
	static void compileLiteralInstance(am.ik.rontolisp.LispInstance inst, WasmLispCompiler.Ctx ctx) {
		emitSharedConstant(inst, ctx, () -> compileQuotedInstance(inst, ctx));
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
		ctx.writer.writeUnsignedLeb128(slotsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(ctx.instanceTypeIndex);
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
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	// Pushes the f64 array stored in slot, cast to TYPE_F64ARR.
	private static void getF64Arr(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
	}

	private static void f64ArrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	private static void f64ArraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// Pushes the f32 array stored in slot, cast to TYPE_F32ARR.
	private static void getF32Arr(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
	}

	private static void f32ArrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
	}

	private static void f32ArraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
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
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void arrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void arraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

}
