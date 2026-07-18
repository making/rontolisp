package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the array built-ins ({@code make-array}, {@code aref}, {@code %aset}, and the
 * fill-pointer surface). An array mirrors the hash-table layout: a {@code TYPE_CELL} box
 * holding a header {@code TYPE_CONS} of {@code (dims . (meta . data))}, where
 * {@code dims} and {@code data} are {@code TYPE_HASH_BUCKETS} arrays
 * ({@code array (mut (ref null eq))}) and {@code meta} is a {@code TYPE_CONS} of
 * {@code (fillPointer . (adjustable . offset))} -- {@code fillPointer} an i31 or null
 * when the array has none, {@code adjustable} the raw {@code :adjustable} argument (null
 * = nil), {@code offset} the displacement offset as an i31 (0 for an ordinary array).
 * {@code dims} holds the dimension sizes as i31 integers; {@code data} holds the
 * row-major elements. The header's car stays the dims bucket array, so the
 * array-vs-hash-table discriminator used by {@code %arrayp}/{@code length}/the printer is
 * unchanged. Any rank {@code >= 1} is supported: the flat index is the Horner fold over
 * the subscripts (unrolled per call site, whose subscript count is static), so a rank-2
 * element {@code (i, j)} lives at flat index {@code i * dims[1] + j} and a rank-1 element
 * {@code (i)} at {@code i}.
 *
 * <p>
 * A displaced array ({@code make-array :displaced-to}) stores the TARGET CELL in the data
 * slot instead of a buckets array: every data access resolves the chain (adding each
 * hop's meta offset to the flat index) before touching the base buckets, so writes alias
 * the target's storage -- including after the target is grown by
 * {@code vector-push-extend} or {@code adjust-array} (the chain re-reads the target's
 * header). A displaced array never has a fill pointer and is never adjustable (lite
 * semantics, enforced at compile time because make-array keywords are literal).
 *
 * <p>
 * A packed float array ({@code #d(...)} or {@code make-array :element-type 'double-float}
 * without a fill pointer / adjustable / displacement) is a distinct {@code TYPE_FARRAY}
 * struct {@code (dims . data)} holding unboxed {@code f64} elements (see
 * {@link WasmQuoteCompiler#compilePackedLiteral}) -- disjoint from the general array's
 * {@code TYPE_CELL}. Every accessor here ({@code aref}/{@code %aset}/{@code row-major-*}/
 * {@code array-dimensions}/{@code make-array}) opens with a {@code ref.test $farray}
 * branch: the packed arm reads/writes the {@code TYPE_F64ARR} directly (flat index via
 * {@link #emitPackedFlatIndex}, boxing each element into a {@code TYPE_FLOAT} on read and
 * coercing to {@code f64} via {@link WasmEmitHelper#castFloatGetF64} on write), while the
 * general arm runs the cell logic unchanged. This test must precede any
 * {@code castCellGet0}, which traps on a non-cell.
 *
 * <p>
 * Everything is emitted inline (no runtime helper function); the packed types are the
 * only new heap types and they are added at the END of the type section, so the static
 * function-import indices stay identical across Preview 1 and {@code --component} modes
 * and the component blobs are unaffected.
 */
final class WasmArrayCompiler {

	private WasmArrayCompiler() {
	}

	static void compileMake(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (findKeywordValue(args, LispNames.DISPLACED_TO_KEYWORD) != null) {
			compileMakeDisplaced(cons, ctx);
			return;
		}
		if (findKeywordValue(args, LispNames.DISPLACED_INDEX_OFFSET_KEYWORD) != null) {
			throw new UnsupportedOperationException("make-array: :displaced-index-offset requires :displaced-to");
		}
		LispVal charContentsLowering = am.ik.rontolisp.LispMacroExpander.lowerCharacterInitialContentsMakeArray(cons);
		if (charContentsLowering != null) {
			// A rank-1 character array built from :initial-contents is a fresh string
			// copy of the contents (a mutable character vector normalizes through the
			// lowering's subseq).
			WasmExprCompiler.compileExpr(charContentsLowering, ctx);
			return;
		}
		LispVal contentsLowering = am.ik.rontolisp.LispMacroExpander.lowerInitialContentsMakeArray(cons);
		if (contentsLowering != null) {
			// :initial-contents lowers to the allocation plus an element-wise fill.
			WasmExprCompiler.compileExpr(contentsLowering, ctx);
			return;
		}
		LispVal characterString = am.ik.rontolisp.LispMacroExpander.lowerCharacterMakeArray(cons);
		if (characterString != null) {
			// A rank-1 :element-type 'character array is a string: make-string.
			WasmExprCompiler.compileExpr(characterString, ctx);
			return;
		}
		boolean singleFloat = isSingleFloatElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD));
		boolean doubleFloat = isDoubleFloatElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD));
		LispVal fpArg = findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD);
		LispVal adjArg = findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD);
		// A fill-pointered/adjustable character vector: the general array shape holding
		// TYPE_CHAR elements, marked mutable-character by a meta offset of 1 (an
		// ordinary array's offset is 0; a displaced array holds a cell in its data
		// slot). _charvec_to_str renders it as a string on demand.
		boolean charVector = am.ik.rontolisp.LispMacroExpander.isCharacterElementType(
				findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD)) && (fpArg != null || adjArg != null);
		if ((doubleFloat || singleFloat) && fpArg == null && adjArg == null) {
			// A plain :element-type 'double-float / 'single-float array (no fill pointer
			// /
			// adjustable / displacement) is a packed farray: emitParseDims for the shape,
			// then a TYPE_F64ARR (double) or TYPE_F32ARR (single) of the coerced init
			// (default 0.0).
			compilePackedMake(args, ctx, singleFloat);
			return;
		}
		// dims -> dimsSlot, init -> initSlot
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int dimsSlot = setTemp(ctx);
		LispVal init = findInitialElement(args);
		if (init == null && charVector) {
			// Elements default to spaces so unfilled slots read back as characters.
			init = new LispChar(' ');
		}
		if (init == null && (doubleFloat || singleFloat)) {
			// An :element-type 'double-float / 'single-float array with a fill pointer /
			// adjustable falls back to a general (boxed) array; default its elements to
			// 0.0, not nil. (A packed array never has a fill pointer / adjustable, so the
			// packed width is intentionally not preserved on this fallback -- matching
			// the
			// double path and the JVM.)
			init = new LispDouble(0.0);
		}
		if (init != null) {
			WasmExprCompiler.compileExpr(init, ctx);
		}
		else {
			refNull(ctx);
		}
		int initSlot = setTemp(ctx);
		int dimsArrSlot = ctx.allocTemp();
		int totalSlot = ctx.allocTemp(); // holds an i31-boxed total element count
		emitParseDims(ctx, dimsSlot, dimsArrSlot, totalSlot);

		// data = array.new buckets (init, total)
		getLocal(ctx, initSlot);
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayNew(ctx);
		int dataArrSlot = setTemp(ctx);

		// Resolve the :fill-pointer argument into an i31-or-null (nil = none; an
		// integer = that value, bounds-checked; anything else, i.e. t, = the vector
		// size) and stash the raw :adjustable argument. Both are compiled only when the
		// keyword appears at the call site.
		LispVal fpExpr = findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD);
		int fpValSlot = -1;
		if (fpExpr != null) {
			WasmExprCompiler.compileExpr(fpExpr, ctx);
			int fpSlot = setTemp(ctx);
			fpValSlot = ctx.allocTemp();
			getLocal(ctx, fpSlot);
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.IF, 0x40);
			refNull(ctx);
			setLocal(ctx, fpValSlot);
			ctx.writer.write(Instruction.ELSE);
			// a fill pointer requires a rank-1 array
			getBuckets(ctx, dimsArrSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
			i32Const(ctx, 1);
			ctx.writer.write(Instruction.I32_NE);
			ctx.writer.write(Instruction.IF, 0x40);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.writer.write(Instruction.END);
			getLocal(ctx, fpSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(Type.I31.code());
			ctx.writer.write(Instruction.IF, 0x40);
			// integer: 0 <= fp <= dims[0], else trap
			getLocal(ctx, fpSlot);
			WasmEmitHelper.castI31GetS(ctx);
			i32Const(ctx, 0);
			ctx.writer.write(Instruction.I32_LT_S);
			ctx.writer.write(Instruction.IF, 0x40);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.writer.write(Instruction.END);
			getLocal(ctx, fpSlot);
			WasmEmitHelper.castI31GetS(ctx);
			getBuckets(ctx, dimsArrSlot);
			i32Const(ctx, 0);
			arrayGet(ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_GT_S);
			ctx.writer.write(Instruction.IF, 0x40);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.writer.write(Instruction.END);
			getLocal(ctx, fpSlot);
			setLocal(ctx, fpValSlot);
			ctx.writer.write(Instruction.ELSE);
			// t: the vector size (dims[0] is already an i31)
			getBuckets(ctx, dimsArrSlot);
			i32Const(ctx, 0);
			arrayGet(ctx);
			setLocal(ctx, fpValSlot);
			ctx.writer.write(Instruction.END);
			ctx.writer.write(Instruction.END);
		}
		LispVal adjExpr = findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD);
		int adjValSlot = -1;
		if (adjExpr != null) {
			WasmExprCompiler.compileExpr(adjExpr, ctx);
			adjValSlot = setTemp(ctx);
		}

		// header = cons(dimsArr, cons(cons(fp, cons(adj, offset)), data)); the offset
		// is 1 for a mutable character vector (the marker), else 0.
		// cell = struct.new TYPE_CELL(header)
		getLocal(ctx, dimsArrSlot);
		if (fpValSlot >= 0) {
			getLocal(ctx, fpValSlot);
		}
		else {
			refNull(ctx);
		}
		if (adjValSlot >= 0) {
			getLocal(ctx, adjValSlot);
		}
		else {
			refNull(ctx);
		}
		i32Const(ctx, charVector ? 1 : 0);
		boxI31(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, dataArrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	// (make-array dims :displaced-to target [:displaced-index-offset off]): a displaced
	// view -- the data slot holds the TARGET CELL and meta carries the offset. The other
	// make-array keywords are rejected (lite semantics). The view is bounds-checked
	// against the target's total size (the product of its dims), trapping when too small.
	private static void compileMakeDisplaced(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD) != null
				|| findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD) != null
				|| findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD) != null) {
			throw new UnsupportedOperationException(
					"make-array: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element");
		}
		LispVal targetExpr = findKeywordValue(args, LispNames.DISPLACED_TO_KEYWORD);
		if (targetExpr == null) {
			throw new UnsupportedOperationException("make-array: :displaced-to expects a value");
		}
		LispVal offsetExpr = findKeywordValue(args, LispNames.DISPLACED_INDEX_OFFSET_KEYWORD);
		// dims -> dimsArr + total (boxed i31), same parse as the ordinary path
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int dimsSlot = setTemp(ctx);
		int dimsArrSlot = ctx.allocTemp();
		int totalSlot = ctx.allocTemp();
		emitParseDims(ctx, dimsSlot, dimsArrSlot, totalSlot);
		// target -> targetSlot; offset -> offSlot (i31; 0 when absent or nil)
		WasmExprCompiler.compileExpr(targetExpr, ctx);
		int targetSlot = setTemp(ctx);
		int offSlot = ctx.allocTemp();
		if (offsetExpr != null) {
			WasmExprCompiler.compileExpr(offsetExpr, ctx);
			setLocal(ctx, offSlot);
			getLocal(ctx, offSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(Type.I31.code());
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, 0x40);
			i32Const(ctx, 0);
			boxI31(ctx);
			setLocal(ctx, offSlot);
			ctx.writer.write(Instruction.END);
		}
		else {
			i32Const(ctx, 0);
			boxI31(ctx);
			setLocal(ctx, offSlot);
		}
		// targetTotal = product of the target's dims; trap unless
		// 0 <= off and total + off <= targetTotal
		int targetTotalSlot = ctx.allocTemp();
		int mSlot = ctx.allocTemp();
		emitTargetDimsProduct(ctx, targetSlot, targetTotalSlot, mSlot);
		getLocal(ctx, offSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, offSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_ADD);
		getLocal(ctx, targetTotalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_GT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// header = cons(dimsArr, cons(cons(null, cons(null, off)), targetCell))
		getLocal(ctx, dimsArrSlot);
		refNull(ctx);
		refNull(ctx);
		getLocal(ctx, offSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, targetSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	// (make-array dims :element-type 'double-float | 'single-float [:initial-element x]):
	// a packed farray. dims is parsed exactly like the general path (emitParseDims -> a
	// buckets array of i31 sizes + the total element count); data is a TYPE_F64ARR
	// (double) or TYPE_F32ARR (single) filled with the coerced init (default 0.0), stored
	// in the SAME TYPE_FARRAY struct. No fill pointer / adjustable / displacement -- a
	// packed array never has them, and the caller has already routed those cases to the
	// general path. Under --simd the data is a TYPE_VBLOCK of v128 lane groups instead;
	// see compilePackedMakeVblock.
	private static void compilePackedMake(List<LispVal> args, WasmLispCompiler.Ctx ctx, boolean single) {
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int dimsSlot = setTemp(ctx);
		int dimsArrSlot = ctx.allocTemp();
		int totalSlot = ctx.allocTemp();
		emitParseDims(ctx, dimsSlot, dimsArrSlot, totalSlot);
		LispVal init = findInitialElement(args);
		if (ctx.simd) {
			compilePackedMakeVblock(ctx, dimsArrSlot, totalSlot, single, init);
			return;
		}
		// init (coerced to f64; a non-real value traps, matching the packed element
		// type),
		// narrowed to f32 for a single-float array.
		if (init != null) {
			WasmExprCompiler.compileExpr(init, ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
		}
		else {
			f64Const(ctx, 0.0);
		}
		if (single) {
			ctx.writer.write(Instruction.F32_DEMOTE_F64);
		}
		// data = array.new TYPE_F32ARR / TYPE_F64ARR (init, total)
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		if (single) {
			f32ArrayNew(ctx);
		}
		else {
			f64ArrayNew(ctx);
		}
		int dataArrSlot = setTemp(ctx);
		// struct.new TYPE_FARRAY (dims, data)
		getLocal(ctx, dimsArrSlot);
		getLocal(ctx, dataArrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	// The --simd lowering of compilePackedMake: the data is a _v_new'd TYPE_VBLOCK stored
	// in the SAME (ref null eq) data field. _v_new zero-fills, so the ubiquitous
	// zero-initial-element case (make-array without :initial-element, linalg:zeros,
	// vec:zeros) needs no fill loop at all -- only a literal non-zero or a computed init
	// pays for one, and it runs through _v_set so the padding lanes stay zero.
	private static void compilePackedMakeVblock(WasmLispCompiler.Ctx ctx, int dimsArrSlot, int totalSlot,
			boolean single, @Nullable LispVal init) {
		// vb = _v_new(total, kind)
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, single ? 1 : 0);
		callVec(ctx, WasmVecSimdRuntimeBuilder.V_NEW);
		int vbSlot = setTemp(ctx);
		if (init != null && !isPositiveZeroLiteral(init)) {
			WasmExprCompiler.compileExpr(init, ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			boxFloat(ctx);
			int initSlot = setTemp(ctx);
			// for (i = 0; i < total; i++) _v_set(vb, i, init)
			int iSlot = ctx.allocTemp();
			i32Const(ctx, 0);
			boxI31(ctx);
			setLocal(ctx, iSlot);
			ctx.writer.write(Instruction.BLOCK, 0x40);
			ctx.writer.write(Instruction.LOOP, 0x40);
			getLocal(ctx, iSlot);
			WasmEmitHelper.castI31GetS(ctx);
			getLocal(ctx, totalSlot);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_GE_S);
			ctx.writer.write(Instruction.BR_IF, 1);
			getLocal(ctx, vbSlot);
			getLocal(ctx, iSlot);
			WasmEmitHelper.castI31GetS(ctx);
			getLocal(ctx, initSlot);
			unboxKnownFloat(ctx);
			callVec(ctx, WasmVecSimdRuntimeBuilder.V_SET);
			ctx.writer.write(Instruction.DROP);
			getLocal(ctx, iSlot);
			WasmEmitHelper.castI31GetS(ctx);
			i32Const(ctx, 1);
			ctx.writer.write(Instruction.I32_ADD);
			boxI31(ctx);
			setLocal(ctx, iSlot);
			ctx.writer.write(Instruction.BR, 0);
			ctx.writer.write(Instruction.END); // loop
			ctx.writer.write(Instruction.END); // block
		}
		// struct.new TYPE_FARRAY (dims, vb)
		getLocal(ctx, dimsArrSlot);
		getLocal(ctx, vbSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	// Whether the :initial-element form is a literal that coerces to POSITIVE zero -- the
	// value array.new_default already wrote. -0.0 is deliberately excluded: it has a
	// different bit pattern and prints differently.
	private static boolean isPositiveZeroLiteral(LispVal init) {
		return switch (init) {
			case am.ik.rontolisp.LispDouble d -> Double.doubleToRawLongBits(d.value()) == 0L;
			case am.ik.rontolisp.LispInteger i -> i.value() == 0L;
			default -> false;
		};
	}

	// call $_v_new / $_v_get / $_v_set.
	private static void callVec(WasmLispCompiler.Ctx ctx, int vecFunc) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + vecFunc);
	}

	// Parses the make-array dimensions value in dimsSlot (an i31 for the rank-1
	// shorthand, otherwise a cons list of sizes) into a fresh buckets array of i31 sizes
	// (dimsArrSlot) and the boxed-i31 total element count (totalSlot).
	private static void emitParseDims(WasmLispCompiler.Ctx ctx, int dimsSlot, int dimsArrSlot, int totalSlot) {
		// if dims is an i31 (rank-1 shorthand: an integer) ...
		getLocal(ctx, dimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF, 0x40);
		// dimsArr = array.new buckets (dims, 1); total = dims
		getLocal(ctx, dimsSlot);
		i32Const(ctx, 1);
		arrayNew(ctx);
		setLocal(ctx, dimsArrSlot);
		getLocal(ctx, dimsSlot);
		setLocal(ctx, totalSlot);
		ctx.writer.write(Instruction.ELSE);
		// dims is a cons list of sizes (any rank): count its length, then copy the
		// sizes into dimsArr while multiplying the total element count. cur walks the
		// list; n, idx and total are kept boxed as i31 (temps are (ref null eq)).
		int curSlot = ctx.allocTemp();
		int nSlot = ctx.allocTemp();
		int idxSlot = ctx.allocTemp();
		// n = 0; cur = dims
		i32Const(ctx, 0);
		boxI31(ctx);
		setLocal(ctx, nSlot);
		getLocal(ctx, dimsSlot);
		setLocal(ctx, curSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		getLocal(ctx, curSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_ADD);
		boxI31(ctx);
		setLocal(ctx, nSlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		setLocal(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		// dimsArr = array.new buckets (null, n); total = 1; idx = 0; cur = dims
		refNull(ctx);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayNew(ctx);
		setLocal(ctx, dimsArrSlot);
		i32Const(ctx, 1);
		boxI31(ctx);
		setLocal(ctx, totalSlot);
		i32Const(ctx, 0);
		boxI31(ctx);
		setLocal(ctx, idxSlot);
		getLocal(ctx, dimsSlot);
		setLocal(ctx, curSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		// dimsArr[idx] = car(cur)
		getBuckets(ctx, dimsArrSlot);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0);
		arraySet(ctx);
		// total = total * car(cur)
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_MUL);
		boxI31(ctx);
		setLocal(ctx, totalSlot);
		// cur = cdr(cur); idx++
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		setLocal(ctx, curSlot);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_ADD);
		boxI31(ctx);
		setLocal(ctx, idxSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		ctx.writer.write(Instruction.END); // end outer if
	}

	// Pushes nothing; stores the i31 product of the dims of the array cell in targetSlot
	// into productSlot (mSlot is boxed-i31 scratch).
	private static void emitTargetDimsProduct(WasmLispCompiler.Ctx ctx, int targetSlot, int productSlot, int mSlot) {
		int dimsBucketsSlot = ctx.allocTemp();
		getLocal(ctx, targetSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
		setLocal(ctx, dimsBucketsSlot);
		i32Const(ctx, 1);
		boxI31(ctx);
		setLocal(ctx, productSlot);
		i32Const(ctx, 0);
		boxI31(ctx);
		setLocal(ctx, mSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		getLocal(ctx, mSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getBuckets(ctx, dimsBucketsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		getLocal(ctx, productSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getBuckets(ctx, dimsBucketsSlot);
		getLocal(ctx, mSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayGet(ctx);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_MUL);
		boxI31(ctx);
		setLocal(ctx, productSlot);
		getLocal(ctx, mSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_ADD);
		boxI31(ctx);
		setLocal(ctx, mSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
	}

	static void compileAref(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			// (aref a): a rank-0 array holds its one element at row-major index 0.
			compileAref(new LispCons(args.get(0),
					new LispCons(args.get(1), new LispCons(new LispInteger(0), LispNil.INSTANCE))), ctx);
			return;
		}
		int rank = args.size() - 2;
		// Evaluate the array once; a packed farray reads its unboxed f64 store directly,
		// a
		// general array resolves the displacement chain into its buckets.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		if (rank == 1) {
			// A string is a rank-1 character array in CL: (aref s i) reads like
			// (char s i) (bytes[1 + i] skips the opening quote byte).
			getLocal(ctx, arrSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
			emitIfEq(ctx);
			getLocal(ctx, arrSlot);
			WasmEmitHelper.emitStrBytesArray(ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_ADD);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
			WasmCharCompiler.makeChar(ctx);
			ctx.writer.write(Instruction.ELSE);
		}
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed: box(data[Horner(subscripts)]), reading the f64/f32 store per width.
		int pdimsSlot = ctx.allocTemp();
		farrayField(ctx, arrSlot, 0);
		setLocal(ctx, pdimsSlot);
		emitPackedFlatIndex(ctx, pdimsSlot, args, 2, rank);
		boxI31(ctx);
		int pIdxSlot = setTemp(ctx);
		emitPackedReadF64(ctx, arrSlot, pIdxSlot);
		boxFloat(ctx);
		ctx.writer.write(Instruction.ELSE);
		// general: arr -> header (the (dims . (meta . data)) cons), then data[flat].
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		emitFlatIndex(ctx, headerSlot, args, 2, rank);
		emitResolveDataAndIndex(ctx, headerSlot);
		arrayGet(ctx);
		ctx.writer.write(Instruction.END);
		if (rank == 1) {
			ctx.writer.write(Instruction.END);
		}
	}

	static void compileRowMajorAref(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (row-major-aref array index): the data array is flat, so this is exactly the
		// rank-1 accessor (data[index]), independent of the array's rank.
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"row-major-aref expects an array and an index, got " + (args.size() - 1) + " argument(s)");
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed: box(data[index]), reading the f64/f32 store per width.
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		boxI31(ctx);
		int pIdxSlot = setTemp(ctx);
		emitPackedReadF64(ctx, arrSlot, pIdxSlot);
		boxFloat(ctx);
		ctx.writer.write(Instruction.ELSE);
		// general: resolve the displacement chain, then data[index].
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		emitResolveDataAndIndex(ctx, headerSlot);
		arrayGet(ctx);
		ctx.writer.write(Instruction.END);
	}

	static void compileRowMajorAset(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%row-major-aset array index value): flat store, leaving the value as the
		// result.
		List<LispVal> args = cons.toList();
		if (args.size() != 4) {
			throw new UnsupportedOperationException("%row-major-aset expects an array, an index and a value, got "
					+ (args.size() - 1) + " argument(s)");
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed: coerce value -> f64 (narrowing to f32 for a single-float array), store
		// at data[index], and return the value AS STORED (read back widened), matching
		// the
		// interpreter/JVM across widths.
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		boxI31(ctx);
		int pIdxSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(3), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		boxFloat(ctx);
		int pBoxSlot = setTemp(ctx);
		emitPackedWriteF64(ctx, arrSlot, pIdxSlot, pBoxSlot);
		ctx.writer.write(Instruction.ELSE);
		// general: resolve the displacement chain, store, and leave the value.
		int valSlot = ctx.allocTemp();
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		emitResolveDataAndIndex(ctx, headerSlot);
		WasmExprCompiler.compileExpr(args.get(3), ctx);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		arraySet(ctx);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.END);
	}

	static void compileDims(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-dimensions expects 1 argument, got " + (args.size() - 1));
		}
		// arr -> the dims buckets array (a packed farray's field 0, else the general
		// header's car) in a temp; the cons-list build below is shared.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		farrayField(ctx, arrSlot, 0);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.END);
		int dimsSlot = setTemp(ctx);
		int resultSlot = ctx.allocTemp();
		int jSlot = ctx.allocTemp();
		// Build the cons list backwards: result = nil; for j = len-1 down to 0:
		// result = cons(dims[j], result). The dims elements are already i31 integers;
		// j is kept boxed as i31 (temps are (ref null eq)).
		refNull(ctx);
		setLocal(ctx, resultSlot);
		getBuckets(ctx, dimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_SUB);
		boxI31(ctx);
		setLocal(ctx, jSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		getLocal(ctx, jSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		getBuckets(ctx, dimsSlot);
		getLocal(ctx, jSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayGet(ctx);
		getLocal(ctx, resultSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		setLocal(ctx, resultSlot);
		getLocal(ctx, jSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_SUB);
		boxI31(ctx);
		setLocal(ctx, jSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		getLocal(ctx, resultSlot);
	}

	static void compileAset(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%aset array subscript... value)
		List<LispVal> args = cons.toList();
		int rank = args.size() - 3;
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed: store the coerced f64 (narrowing to f32 for a single-float array) at
		// data[Horner(subscripts)], returning the value AS STORED (read back widened) to
		// match the interpreter/JVM across widths. Evaluation order: array (done),
		// subscripts, then value.
		int pdimsSlot = ctx.allocTemp();
		farrayField(ctx, arrSlot, 0);
		setLocal(ctx, pdimsSlot);
		emitPackedFlatIndex(ctx, pdimsSlot, args, 2, rank);
		boxI31(ctx);
		int pIdxSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(args.size() - 1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		boxFloat(ctx);
		int pBoxSlot = setTemp(ctx);
		emitPackedWriteF64(ctx, arrSlot, pIdxSlot, pBoxSlot);
		ctx.writer.write(Instruction.ELSE);
		// general: data[flat] = val, leaving val as the result. tee keeps the value on
		// the
		// stack for array.set.
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		int valSlot = ctx.allocTemp();
		emitFlatIndex(ctx, headerSlot, args, 2, rank);
		emitResolveDataAndIndex(ctx, headerSlot);
		WasmExprCompiler.compileExpr(args.get(args.size() - 1), ctx);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		arraySet(ctx);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.END);
	}

	// Resolves the displacement chain: expects the i32 flat index on the stack; leaves
	// [buckets, i32 index] ready for array.get/array.set. While the header's data slot
	// holds a TARGET CELL (a displaced view), the meta offset is added to the index and
	// the walk hops to the target's header; an ordinary array falls straight through.
	private static void emitResolveDataAndIndex(WasmLispCompiler.Ctx ctx, int headerSlot) {
		int flatSlot = ctx.allocTemp();
		boxI31(ctx);
		setLocal(ctx, flatSlot);
		int curSlot = ctx.allocTemp();
		getLocal(ctx, headerSlot);
		setLocal(ctx, curSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// exit when the data slot is not a cell (it is the buckets array)
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1);
		// flat += the meta offset (meta.cdr.cdr)
		getLocal(ctx, flatSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, curSlot);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_ADD);
		boxI31(ctx);
		setLocal(ctx, flatSlot);
		// cur = the target cell's header
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		castCellGet0(ctx);
		setLocal(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		getLocal(ctx, curSlot);
		getData(ctx);
		getLocal(ctx, flatSlot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	// Pushes the i32 flat index for the subscripts at args[firstSub..firstSub+rank-1]:
	// the Horner fold flat = ((s0 * dims[1] + s1) * dims[2] + s2) ..., unrolled at the
	// call site (the subscript count is static). dims (car of header) supplies the
	// per-dimension strides; a rank-1 access never touches it.
	private static void emitFlatIndex(WasmLispCompiler.Ctx ctx, int headerSlot, List<LispVal> args, int firstSub,
			int rank) {
		WasmExprCompiler.compileExpr(args.get(firstSub), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		for (int k = 1; k < rank; k++) {
			// flat = flat * dims[k] + subscript_k
			getLocal(ctx, headerSlot);
			castConsGet(ctx, 0);
			castBuckets(ctx);
			i32Const(ctx, k);
			arrayGet(ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_MUL);
			WasmExprCompiler.compileExpr(args.get(firstSub + k), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_ADD);
		}
	}

	// The Horner fold for a packed farray: the same shape as emitFlatIndex, but the
	// per-dimension strides come from the farray's own dims buckets (held in dimsSlot as
	// eq) rather than a general-array header. A rank-1 access never touches dimsSlot.
	private static void emitPackedFlatIndex(WasmLispCompiler.Ctx ctx, int dimsSlot, List<LispVal> args, int firstSub,
			int rank) {
		WasmExprCompiler.compileExpr(args.get(firstSub), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		for (int k = 1; k < rank; k++) {
			// flat = flat * dims[k] + subscript_k
			getBuckets(ctx, dimsSlot);
			i32Const(ctx, k);
			arrayGet(ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_MUL);
			WasmExprCompiler.compileExpr(args.get(firstSub + k), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_ADD);
		}
	}

	static void compileElementType(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (array-element-type array): the symbol single-float / double-float for a packed
		// farray (by the data array's width), else t (the general array's lite element
		// type, matching the (progn arr t) expansion). Emitted unconditionally -- the
		// farray types always exist on the GC backend.
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-element-type expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed: single-float when the data array is a TYPE_F32ARR (--simd: when the
		// vblock's kind field is 1), else double-float.
		if (ctx.simd) {
			vblockKind(ctx, arrSlot);
		}
		else {
			farrayField(ctx, arrSlot, 1);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		}
		emitIfEq(ctx);
		WasmEmitHelper.compileStringLiteral(LispNames.SINGLE_FLOAT, ctx);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.compileStringLiteral(LispNames.DOUBLE_FLOAT, ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.END);
	}

	static void compileFillPointer(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (fill-pointer array): meta.car, trapping when the array has none.
		requireArgs(cons, 2, "fill-pointer expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		getMeta(ctx);
		int metaSlot = setTemp(ctx);
		emitRequireFillPointer(ctx, metaSlot);
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
	}

	static void compileSetFillPointer(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%set-fill-pointer array value): the setf target of (fill-pointer array);
		// bounds-checked against dims[0], returning the value.
		requireArgs(cons, 3, "%set-fill-pointer expects an array and a value");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		int metaSlot = setTemp(ctx);
		emitRequireFillPointer(ctx, metaSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int valSlot = setTemp(ctx);
		// 0 <= value <= dims[0], else trap
		getLocal(ctx, valSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, valSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		castBuckets(ctx);
		i32Const(ctx, 0);
		arrayGet(ctx);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_GT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// meta.car = value
		getLocal(ctx, metaSlot);
		castCons(ctx);
		getLocal(ctx, valSlot);
		structSetCons(ctx, 0);
		getLocal(ctx, valSlot);
	}

	static void compileHasFillPointer(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (array-has-fill-pointer-p array): meta.car is an i31?
		requireArgs(cons, 2, "array-has-fill-pointer-p expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		getMeta(ctx);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	static void compileAdjustableArrayP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (adjustable-array-p array): meta.cdr.car holds the raw :adjustable argument
		// (null = nil), so non-null means adjustable.
		requireArgs(cons, 2, "adjustable-array-p expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.I32_EQZ);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	static void compileArrayBecome(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%array-become old new): replace old's dims, fill pointer and data with new's
		// in place (the in-place half of adjust-array on an adjustable array); returns
		// old. The adjustable flag and offset (meta.cdr) are kept.
		requireArgs(cons, 3, "%array-become expects 2 arguments");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int oldCellSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int newCellSlot = setTemp(ctx);
		int oldHeaderSlot = ctx.allocTemp();
		int newHeaderSlot = ctx.allocTemp();
		getLocal(ctx, oldCellSlot);
		castCellGet0(ctx);
		setLocal(ctx, oldHeaderSlot);
		getLocal(ctx, newCellSlot);
		castCellGet0(ctx);
		setLocal(ctx, newHeaderSlot);
		// oldHeader.car = newHeader.car (the dims buckets)
		getLocal(ctx, oldHeaderSlot);
		castCons(ctx);
		getLocal(ctx, newHeaderSlot);
		castConsGet(ctx, 0);
		structSetCons(ctx, 0);
		// oldMeta.car = newMeta.car (the fill pointer)
		getLocal(ctx, oldHeaderSlot);
		getMeta(ctx);
		castCons(ctx);
		getLocal(ctx, newHeaderSlot);
		getMeta(ctx);
		castConsGet(ctx, 0);
		structSetCons(ctx, 0);
		// (cdr oldHeader).cdr = (cdr newHeader).cdr (the data buckets)
		getLocal(ctx, oldHeaderSlot);
		castConsGet(ctx, 1);
		castCons(ctx);
		getLocal(ctx, newHeaderSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		structSetCons(ctx, 1);
		getLocal(ctx, oldCellSlot);
	}

	static void compileDispTarget(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%array-disp-target array): the displaced-to target cell (the data slot when
		// it is a cell), or nil.
		requireArgs(cons, 2, "%array-disp-target expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int dataSlot = setTemp(ctx);
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.ELSE);
		refNull(ctx);
		ctx.writer.write(Instruction.END);
	}

	static void compileDispOffset(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%array-disp-offset array): the displacement offset i31 (meta.cdr.cdr), but
		// only when the array IS displaced (its data slot holds a target cell) -- a
		// non-displaced array reports i31 0 even when the offset word carries the
		// mutable-character-vector marker (1).
		requireArgs(cons, 2, "%array-disp-offset expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		boxI31(ctx);
		ctx.writer.write(Instruction.END);
	}

	static void compileVectorPush(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (vector-push value vector): store value at the fill pointer and return the
		// index used, or nil when the vector is full.
		requireArgs(cons, 3, "vector-push expects a value and a vector");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		int metaSlot = ctx.allocTemp();
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		setLocal(ctx, metaSlot);
		emitRequireFillPointer(ctx, metaSlot);
		// if fp >= dims[0] -> nil, else store and advance
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		castBuckets(ctx);
		i32Const(ctx, 0);
		arrayGet(ctx);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		refNull(ctx);
		ctx.writer.write(Instruction.ELSE);
		emitStoreAtFillPointerAndAdvance(ctx, headerSlot, metaSlot, valSlot);
		ctx.writer.write(Instruction.END);
	}

	static void compileVectorPop(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (vector-pop vector): decrement the fill pointer and return the element it
		// passed, trapping on an empty vector.
		requireArgs(cons, 2, "vector-pop expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		int metaSlot = ctx.allocTemp();
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		setLocal(ctx, metaSlot);
		emitRequireFillPointer(ctx, metaSlot);
		// fp == 0 -> trap
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// meta.car = fp - 1
		int fpSlot = ctx.allocTemp();
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_SUB);
		boxI31(ctx);
		setLocal(ctx, fpSlot);
		getLocal(ctx, metaSlot);
		castCons(ctx);
		getLocal(ctx, fpSlot);
		structSetCons(ctx, 0);
		// data[fp - 1]
		getLocal(ctx, headerSlot);
		getData(ctx);
		getLocal(ctx, fpSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayGet(ctx);
	}

	static void compileVectorPushExtend(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (vector-push-extend value vector [extension]): like vector-push but grows the
		// data array (by at least extension elements, minimum 1) when the vector is
		// full, updating the stored dimension size.
		List<LispVal> args = cons.toList();
		if (args.size() < 3 || args.size() > 4) {
			throw new UnsupportedOperationException(
					"vector-push-extend expects 2 or 3 arguments, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		int extSlot = -1;
		if (args.size() == 4) {
			WasmExprCompiler.compileExpr(args.get(3), ctx);
			extSlot = setTemp(ctx);
		}
		int metaSlot = ctx.allocTemp();
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		setLocal(ctx, metaSlot);
		emitRequireFillPointer(ctx, metaSlot);
		// if fp >= dims[0]: grow
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		castBuckets(ctx);
		i32Const(ctx, 0);
		arrayGet(ctx);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.IF, 0x40);
		// newCap = cap + max(ext, 1); the counters stay boxed as i31 (temps are
		// (ref null eq)).
		int newCapSlot = ctx.allocTemp();
		int newDataSlot = ctx.allocTemp();
		int idxSlot = ctx.allocTemp();
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		castBuckets(ctx);
		i32Const(ctx, 0);
		arrayGet(ctx);
		WasmEmitHelper.castI31GetS(ctx);
		if (extSlot >= 0) {
			// max(ext, 1)
			getLocal(ctx, extSlot);
			WasmEmitHelper.castI31GetS(ctx);
			i32Const(ctx, 1);
			ctx.writer.write(Instruction.I32_LT_S);
			ctx.writer.write(Instruction.IF, 0x7F);
			i32Const(ctx, 1);
			ctx.writer.write(Instruction.ELSE);
			getLocal(ctx, extSlot);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.END);
		}
		else {
			i32Const(ctx, 1);
		}
		ctx.writer.write(Instruction.I32_ADD);
		boxI31(ctx);
		setLocal(ctx, newCapSlot);
		// newData = array.new buckets (null, newCap); grown slots read as nil
		refNull(ctx);
		getLocal(ctx, newCapSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayNew(ctx);
		setLocal(ctx, newDataSlot);
		// copy the old elements: for idx in 0..fp-1: newData[idx] = data[idx]
		i32Const(ctx, 0);
		boxI31(ctx);
		setLocal(ctx, idxSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		getBuckets(ctx, newDataSlot);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, headerSlot);
		getData(ctx);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayGet(ctx);
		arraySet(ctx);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_ADD);
		boxI31(ctx);
		setLocal(ctx, idxSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		// (cdr header).cdr = newData; dims[0] = newCap
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castCons(ctx);
		getLocal(ctx, newDataSlot);
		structSetCons(ctx, 1);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		castBuckets(ctx);
		i32Const(ctx, 0);
		getLocal(ctx, newCapSlot);
		arraySet(ctx);
		ctx.writer.write(Instruction.END); // grow if
		emitStoreAtFillPointerAndAdvance(ctx, headerSlot, metaSlot, valSlot);
	}

	// Traps unless the meta cons in metaSlot carries a fill pointer (an i31 car).
	private static void emitRequireFillPointer(WasmLispCompiler.Ctx ctx, int metaSlot) {
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
	}

	// data[fp] = val; meta.car = fp + 1; leaves the i31 fp on the stack as the result.
	private static void emitStoreAtFillPointerAndAdvance(WasmLispCompiler.Ctx ctx, int headerSlot, int metaSlot,
			int valSlot) {
		int fpSlot = ctx.allocTemp();
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		setLocal(ctx, fpSlot);
		getLocal(ctx, headerSlot);
		getData(ctx);
		getLocal(ctx, fpSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, valSlot);
		arraySet(ctx);
		getLocal(ctx, metaSlot);
		castCons(ctx);
		getLocal(ctx, fpSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_ADD);
		boxI31(ctx);
		structSetCons(ctx, 0);
		getLocal(ctx, fpSlot);
	}

	private static void requireArgs(LispCons cons, int size, String message) {
		if (cons.toList().size() != size) {
			throw new UnsupportedOperationException(message);
		}
	}

	private static @Nullable LispVal findInitialElement(List<LispVal> args) {
		return findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD);
	}

	private static @Nullable LispVal findKeywordValue(List<LispVal> args, String keyword) {
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && keyword.equals(kw.name())) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	// Whether a make-array :element-type value designates double-float. On the compile
	// path the value is a literal quoted symbol -- (quote double-float) -- so the quote
	// is
	// unwrapped and the symbol name matched (ignoring any package qualifier). Mirrors the
	// JVM JvmArrayCompiler.isDoubleFloatElementType.
	private static boolean isDoubleFloatElementType(@Nullable LispVal elementType) {
		return LispNames.DOUBLE_FLOAT.equals(elementTypeLocalName(elementType));
	}

	// Whether a make-array :element-type value designates single-float (packs to an f32
	// array, not yet supported on the WASM backend). Mirrors the JVM
	// JvmArrayCompiler.isSingleFloatElementType.
	private static boolean isSingleFloatElementType(@Nullable LispVal elementType) {
		return LispNames.SINGLE_FLOAT.equals(elementTypeLocalName(elementType));
	}

	// The local (package-qualifier-stripped) symbol name of a literal quoted
	// :element-type
	// value, or null when it is not a quoted symbol.
	private static @Nullable String elementTypeLocalName(@Nullable LispVal elementType) {
		LispVal sym = elementType;
		if (sym instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
			sym = rest.car();
		}
		if (sym instanceof LispSymbol s) {
			String name = s.name();
			int colon = name.lastIndexOf(':');
			return colon >= 0 ? name.substring(colon + 1) : name;
		}
		return null;
	}

	// --- helpers -------------------------------------------------------------

	private static int setTemp(WasmLispCompiler.Ctx ctx) {
		int slot = ctx.allocTemp();
		setLocal(ctx, slot);
		return slot;
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	private static void setLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	private static void i32Const(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	private static void refNull(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	// Boxes the i32 on the stack as an i31ref (temps are (ref null eq) locals).
	private static void boxI31(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// --- packed float-array (TYPE_FARRAY) helpers ----------------------------

	// Pushes f64.const value.
	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

	// Boxes the f64 on the stack into a TYPE_FLOAT struct (an eqref value).
	private static void boxFloat(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	// Pushes an i32: whether the value in slot is a TYPE_FARRAY (packed float array).
	private static void testFarray(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
	}

	// Opens an IF whose result type is (ref null eq); pairs with ELSE/END.
	private static void emitIfEq(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	// Pushes the given field of the TYPE_FARRAY held (as eq) in slot: field 0 = dims
	// buckets (a TYPE_HASH_BUCKETS held as eq), field 1 = the packed data array held as
	// eq (a TYPE_F64ARR for double, a TYPE_F32ARR for single -- ref.test $f32arr to tell
	// them apart, see emitPackedReadF64 / emitPackedWriteF64).
	private static void farrayField(WasmLispCompiler.Ctx ctx, int slot, int field) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		ctx.writer.writeSignedLeb128(field);
	}

	// --- packed float-array VBLOCK helpers (--simd only) ------------------------------
	//
	// Under --simd the farray's data field holds a TYPE_VBLOCK -- {count, kind, groups}
	// over an (array (mut v128)) of lane groups. The width is a runtime field, and the
	// element's lane index is an instruction IMMEDIATE, so both branches live once inside
	// the _v_get / _v_set runtime helpers and every site below is a single call.

	// Unboxes a value KNOWN to be a TYPE_FLOAT struct (the compiler boxed it itself), so
	// no i31/ratio type test is needed.
	private static void unboxKnownFloat(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeSignedLeb128(0);
	}

	// The --simd counterpart of emitPackedReadF64: _v_get(vblock, idx).
	private static void emitPackedReadF64Vblock(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot) {
		farrayField(ctx, arrSlot, 1);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		callVec(ctx, WasmVecSimdRuntimeBuilder.V_GET);
	}

	// The --simd counterpart of emitPackedWriteF64: _v_set returns the value AS STORED (a
	// single-float write returns the f32-round-tripped value), so this just boxes it.
	private static void emitPackedWriteF64Vblock(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot, int boxValSlot) {
		farrayField(ctx, arrSlot, 1);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, boxValSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		callVec(ctx, WasmVecSimdRuntimeBuilder.V_SET);
		boxFloat(ctx);
	}

	// Pushes the i32 width tag (0 = double, 1 = single) of the packed farray in slot.
	private static void vblockKind(WasmLispCompiler.Ctx ctx, int slot) {
		farrayField(ctx, slot, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_VBLOCK);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
		ctx.writer.writeSignedLeb128(1);
	}

	// Reads the packed farray element data[idx] as an f64, dispatching on the data
	// array's width: a TYPE_F32ARR element is widened f32->f64 (f64.promote_f32), a
	// TYPE_F64ARR element is read directly. Leaves one f64 on the stack. arrSlot holds
	// the
	// farray (as eq); idxSlot holds the i31-boxed flat index.
	private static void emitPackedReadF64(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot) {
		if (ctx.simd) {
			emitPackedReadF64Vblock(ctx, arrSlot, idxSlot);
			return;
		}
		farrayField(ctx, arrSlot, 1);
		int dataSlot = setTemp(ctx);
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		ctx.writer.write(Instruction.IF, Type.F64.code());
		// single: promote(data[idx] : f32)
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		f32ArrayGet(ctx);
		ctx.writer.write(Instruction.F64_PROMOTE_F32);
		ctx.writer.write(Instruction.ELSE);
		// double: data[idx] : f64
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		f64ArrayGet(ctx);
		ctx.writer.write(Instruction.END);
	}

	// Stores the coerced f64 held (boxed as a TYPE_FLOAT) in boxValSlot at data[idx],
	// narrowing to f32 for a TYPE_F32ARR, and leaves the boxed value AS STORED on the
	// stack (the read-back-widened value for single, the coerced f64 for double),
	// matching
	// the interpreter/JVM aset return across widths. arrSlot holds the farray; idxSlot
	// the
	// i31-boxed flat index.
	private static void emitPackedWriteF64(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot, int boxValSlot) {
		if (ctx.simd) {
			emitPackedWriteF64Vblock(ctx, arrSlot, idxSlot, boxValSlot);
			return;
		}
		farrayField(ctx, arrSlot, 1);
		int dataSlot = setTemp(ctx);
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		emitIfEq(ctx); // (result (ref null eq)): the boxed value as stored
		// single: narrowed = box(promote(demote(value))) -- the value read back through
		// f32; store demote(narrowed) (== demote(value)); return narrowed.
		getLocal(ctx, boxValSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F32_DEMOTE_F64);
		ctx.writer.write(Instruction.F64_PROMOTE_F32);
		boxFloat(ctx);
		int narrowedSlot = setTemp(ctx);
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, narrowedSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F32_DEMOTE_F64);
		f32ArraySet(ctx);
		getLocal(ctx, narrowedSlot);
		ctx.writer.write(Instruction.ELSE);
		// double: store the coerced f64, return it unchanged.
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, boxValSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		f64ArraySet(ctx);
		getLocal(ctx, boxValSlot);
		ctx.writer.write(Instruction.END);
	}

	// array.new TYPE_F64ARR: [f64 init, i32 size] -> [array].
	private static void f64ArrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// array.get TYPE_F64ARR: [array, i32 index] -> [f64].
	private static void f64ArrayGet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// array.set TYPE_F64ARR: [array, i32 index, f64] -> [].
	private static void f64ArraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// array.new TYPE_F32ARR: [f32 init, i32 size] -> [array].
	private static void f32ArrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F32ARR);
	}

	// array.get TYPE_F32ARR: [array, i32 index] -> [f32].
	private static void f32ArrayGet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_F32ARR);
	}

	// array.set TYPE_F32ARR: [array, i32 index, f32] -> [].
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

	// array.new TYPE_HASH_BUCKETS: [init, size] -> [array].
	private static void arrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// array.get TYPE_HASH_BUCKETS: [array, i32 index] -> [value].
	private static void arrayGet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// array.set TYPE_HASH_BUCKETS: [array, i32 index, value] -> [].
	private static void arraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// Casts the (ref null eq) on the stack to TYPE_HASH_BUCKETS.
	private static void castBuckets(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// Casts the (ref null eq) on the stack to TYPE_CONS.
	private static void castCons(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
	}

	// struct.set TYPE_CONS field: [cons, value] -> [].
	private static void structSetCons(WasmLispCompiler.Ctx ctx, int field) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(field);
	}

	// Assumes the header cons (eqref) on the stack; replaces it with the data bucket
	// array (cddr of the header), cast to TYPE_HASH_BUCKETS.
	private static void getData(WasmLispCompiler.Ctx ctx) {
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		castBuckets(ctx);
	}

	// Assumes the header cons (eqref) on the stack; replaces it with the meta cons
	// (cadr of the header, the (fillPointer . adjustable) pair) as an eqref.
	private static void getMeta(WasmLispCompiler.Ctx ctx) {
		castConsGet(ctx, 1);
		castConsGet(ctx, 0);
	}

	// Pushes the bucket array stored in slot, cast to TYPE_HASH_BUCKETS.
	private static void getBuckets(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		castBuckets(ctx);
	}

	// Assumes a cell (eqref) on the stack; replaces it with its field-0 value (the header
	// cons).
	private static void castCellGet0(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
	}

	// Assumes a cons (eqref) on the stack; replaces it with car (field 0) or cdr (field
	// 1).
	private static void castConsGet(WasmLispCompiler.Ctx ctx, int field) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(field);
	}

}
