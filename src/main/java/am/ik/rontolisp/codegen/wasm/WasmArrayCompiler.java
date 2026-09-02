package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ArrayElementTypes;
import am.ik.rontolisp.ArrayGrowth;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.DeclaredArrayTypes;
import am.ik.rontolisp.macro.LispMacroExpander;
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
 * unchanged. Any rank {@code >= 0} is supported: the flat index is the Horner fold over
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
 * The allocation is emitted inline apart from the DIMENSION parse, which every shape
 * shares and which is three callees in {@link WasmArrayRuntimeBuilder} --
 * {@code _arr_dims}, {@code _arr_total} and {@code _arr_fp}; the rank-1 integer shorthand
 * stays at the site, and only the list arm calls (`.kb/array-literals.md`). The packed
 * types are the only new heap types and they are added at the END of the type section, so
 * the static function-import indices stay identical across Preview 1 and
 * {@code --component} modes and the component blobs are unaffected.
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
		LispVal runtimeElementTypeLowering = am.ik.rontolisp.macro.LispMacroExpander
			.lowerRuntimeElementTypeMakeArray(cons, ctx.functions::containsKey);
		if (runtimeElementTypeLowering != null) {
			// A :element-type held in a VARIABLE picks the representation at run time,
			// since no expansion-time recognizer can see it: a call to the
			// %make-array-et prelude helper where that defun is present, the whole
			// seven-arm dispatch inline where it is not.
			WasmExprCompiler.compileExpr(runtimeElementTypeLowering, ctx);
			return;
		}
		LispVal charContentsLowering = am.ik.rontolisp.macro.LispMacroExpander
			.lowerCharacterInitialContentsMakeArray(cons);
		if (charContentsLowering != null) {
			// A rank-1 character array built from :initial-contents is a fresh string
			// copy of the contents (a mutable character vector normalizes through the
			// lowering's subseq).
			WasmExprCompiler.compileExpr(charContentsLowering, ctx);
			return;
		}
		LispVal contentsLowering = am.ik.rontolisp.macro.LispMacroExpander.lowerInitialContentsMakeArray(cons);
		if (contentsLowering != null) {
			// :initial-contents lowers to the allocation plus an element-wise fill.
			WasmExprCompiler.compileExpr(contentsLowering, ctx);
			return;
		}
		// Resolved ONCE, through the deftype registry: every recognizer below sees the
		// expansion a user alias stands for, so (make-array n :element-type 'octet)
		// picks the same representation as the literal '(unsigned-byte 8) spelling.
		LispVal elementType = am.ik.rontolisp.macro.LispMacroExpander
			.resolveElementTypeAlias(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD), ctx.closRegistry);
		boolean singleFloat = isSingleFloatElementType(elementType);
		boolean doubleFloat = isDoubleFloatElementType(elementType);
		LispVal fpArg = findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD);
		LispVal adjArg = findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD);
		// A rank-1 :element-type 'character array is the general array shape holding
		// TYPE_CHAR elements, marked mutable-character by a meta offset of 1 (an
		// ordinary array's offset is 0; a displaced array holds a cell in its data
		// slot). {@code _charvec_to_str} renders it as a UTF-8 encoded string on
		// demand, so a non-ASCII code point stored via setf-aref round-trips through
		// the string boundary intact. Every make-array with :element-type 'character
		// now takes this route (matching the JVM), so setf-aref writes always land in
		// place -- the previous immutable TYPE_STRING branch dropped high bytes on
		// downstream read even for programs that never called mutation. The MARK itself
		// is conditional on the runtime rank being 1 (below), since nothing above rank 1
		// is a string.
		boolean charVector = am.ik.rontolisp.macro.LispMacroExpander.isCharacterElementType(elementType);
		int elementTypeCode = ArrayElementTypes.codeOf(elementType);
		if ((doubleFloat || singleFloat) && fpArg == null && adjArg == null) {
			// A plain :element-type 'double-float / 'single-float array (no fill pointer
			// /
			// adjustable / displacement) is a packed farray: emitParseDims for the shape,
			// then a TYPE_F64ARR (double) or TYPE_F32ARR (single) of the coerced init
			// (default 0.0).
			compilePackedMake(args, ctx, singleFloat);
			return;
		}
		int packedIntWidth = packedIntElementWidth(elementType);
		if (packedIntWidth > 0 && fpArg == null && adjArg == null) {
			// A plain :element-type '(unsigned-byte 8|16|32) array packs when the
			// runtime rank is 1 (a rank-n shape keeps the general boxed representation,
			// matching the interpreter's rank-1-only packing).
			compilePackedIntMake(args, ctx, packedIntWidth);
			return;
		}
		// dims -> dimsSlot, init -> initSlot
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int dimsSlot = setTemp(ctx);
		LispVal init = findInitialElement(args);
		if (init == null) {
			// A declared element type that reaches the GENERAL representation still
			// fills with an element OF THAT TYPE: #\Space for a character request, 0 for
			// a packed integer width, 0.0 for a float one. The type itself is remembered
			// in the meta marker below, so array-element-type answers it too.
			init = ArrayElementTypes.defaultElement(elementTypeCode);
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
			getLocal(ctx, dimsArrSlot);
			callFixed(ctx, WasmLispCompiler.FUNC_ARR_FP);
			fpValSlot = setTemp(ctx);
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
		if (charVector) {
			// The marker 1 MEANS "a rank-1 character array", i.e. a string, so it is set
			// only when the runtime rank is 1. Above rank 1 a character element type
			// selects no representation of its own: the value is the plain general
			// array, which is what stringp / type-of then answer for -- the same degrade
			// an (unsigned-byte 8) request takes above rank 1 -- and the marker instead
			// REMEMBERS the character element type (elementTypeMarker below), which is
			// the one trace it leaves. Rank 1 gives 2 - 1 = 1, rank n gives 2 - 0 = 2.
			i32Const(ctx, elementTypeMarker(ArrayElementTypes.CHARACTER));
			getBuckets(ctx, dimsArrSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
			i32Const(ctx, 1);
			ctx.writer.write(Instruction.I32_EQ);
			ctx.writer.write(Instruction.I32_SUB);
		}
		else {
			i32Const(ctx, elementTypeMarker(elementTypeCode));
		}
		boxI31(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, dataArrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	// (make-array dims :displaced-to target [:displaced-index-offset off]
	// [:fill-pointer fp] [:adjustable adj]): a displaced view -- the data slot holds the
	// TARGET CELL and meta carries the offset alongside the view's OWN fill pointer and
	// adjustable flag. Only :initial-element / :initial-contents are rejected (the one
	// combination CLHS forbids: the view owns no storage to initialize). The view is
	// bounds-checked against the target's total size (the product of its dims), trapping
	// when too small.
	private static void compileMakeDisplaced(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD) != null
				|| findKeywordValue(args, LispNames.INITIAL_CONTENTS_KEYWORD) != null) {
			throw new UnsupportedOperationException(
					"make-array: :displaced-to cannot be combined with :initial-element/:initial-contents");
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
		emitTargetDimsProduct(ctx, targetSlot, targetTotalSlot);
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
		// The view's OWN :fill-pointer / :adjustable, resolved exactly as the ordinary
		// path resolves them (the shared _arr_fp body, against the VIEW's shape) and
		// stored in the same meta slots, so every reader answers for a view unchanged.
		LispVal fpExpr = findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD);
		int fpValSlot = -1;
		if (fpExpr != null) {
			WasmExprCompiler.compileExpr(fpExpr, ctx);
			getLocal(ctx, dimsArrSlot);
			callFixed(ctx, WasmLispCompiler.FUNC_ARR_FP);
			fpValSlot = setTemp(ctx);
		}
		LispVal adjExpr = findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD);
		int adjValSlot = -1;
		if (adjExpr != null) {
			WasmExprCompiler.compileExpr(adjExpr, ctx);
			adjValSlot = setTemp(ctx);
		}
		// header = cons(dimsArr, cons(cons(fp, cons(adj, off)), targetCell))
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
		getLocal(ctx, offSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, targetSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	// (make-array dims :element-type '(unsigned-byte 8|16|32) [:initial-element x]): a
	// packed integer vector when the RUNTIME rank is 1, else the general boxed shape
	// (matching the interpreter's rank-1-only packing). The packed arm allocates
	// array.new_default (all zero) and runs a mask-store fill loop only for a non-zero
	// init; the general arm mirrors the ordinary make-array construction with the same
	// evaluated init (nil default).
	private static void compilePackedIntMake(List<LispVal> args, WasmLispCompiler.Ctx ctx, int width) {
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int dimsSlot = setTemp(ctx);
		int dimsArrSlot = ctx.allocTemp();
		int totalSlot = ctx.allocTemp();
		emitParseDims(ctx, dimsSlot, dimsArrSlot, totalSlot);
		// An unsupplied element is 0, not nil: the packed branch is zero-filled by
		// array.new_default anyway, and the rank-n GENERAL fallback below must fill with
		// an integer too -- an array asked to hold bytes holds bytes at every rank.
		LispVal init = java.util.Objects.requireNonNullElseGet(findInitialElement(args), () -> new LispInteger(0));
		WasmExprCompiler.compileExpr(init, ctx);
		int initSlot = setTemp(ctx);
		int type = intArrType(width);
		// rank == 1?
		getBuckets(ctx, dimsArrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_EQ);
		emitIfEq(ctx);
		// packed: arr = array.new_default (zero-filled), plus a mask-store fill loop
		// for a non-zero :initial-element.
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		intArrNewDefault(ctx, type);
		int arrSlot = setTemp(ctx);
		if (!(init instanceof LispInteger zero && zero.value() == 0)) {
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
			emitIntArrSet(ctx, arrSlot, iSlot, initSlot, type);
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
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.ELSE);
		// general: data = array.new buckets(init, total); header; cell. The header
		// REMEMBERS the (unsigned-byte width) that selected no representation at this
		// rank -- the one trace it leaves besides the 0 fill.
		getLocal(ctx, initSlot);
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayNew(ctx);
		int dataArrSlot = setTemp(ctx);
		getLocal(ctx, dimsArrSlot);
		refNull(ctx);
		refNull(ctx);
		i32Const(ctx, elementTypeMarker(elementTypeCodeForWidth(width)));
		boxI31(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, dataArrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.END);
	}

	// (%array-alike seq n): a fresh zero-filled rank-1 array of length n with the SAME
	// representation as seq -- a packed integer vector yields a packed vector of the
	// same width, anything else a general (nil-filled) vector. The subseq vector
	// lowering allocates through this.
	static void compileArrayAlike(LispCons cons, WasmLispCompiler.Ctx ctx) {
		requireArgs(cons, 3, "%array-alike expects a sequence and a length");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int seqSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int nSlot = setTemp(ctx);
		testIntVector(ctx, seqSlot);
		emitIfEq(ctx);
		getLocal(ctx, seqSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		emitIfEq(ctx);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		intArrNewDefault(ctx, WasmLispCompiler.TYPE_I8ARR);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, seqSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		emitIfEq(ctx);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		intArrNewDefault(ctx, WasmLispCompiler.TYPE_I16ARR);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		intArrNewDefault(ctx, WasmLispCompiler.TYPE_I32ARR);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// general: array.new buckets(nil, n) under a fresh 1-dim header cell.
		refNull(ctx);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayNew(ctx);
		int dataArrSlot = setTemp(ctx);
		getLocal(ctx, nSlot);
		i32Const(ctx, 1);
		arrayNew(ctx);
		refNull(ctx);
		refNull(ctx);
		i32Const(ctx, 0);
		boxI31(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, dataArrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.END);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + vecFunc);
	}

	// Parses the make-array dimensions value in dimsSlot (an i31 for the rank-1
	// shorthand, otherwise a cons list of sizes) into a fresh buckets array of i31 sizes
	// (dimsArrSlot) and the boxed-i31 total element count (totalSlot).
	//
	// The i31 shorthand is spelled HERE because it is three instructions and the shape
	// nearly every allocation writes; the list arm -- two walks over the list, ~200 bytes
	// -- is the shared _arr_dims / _arr_total pair, so a rank-n site costs a pair of
	// calls instead of the loops (.kb/array-literals.md).
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
		// dims is a cons list of sizes (any rank): the shared parse, then its product.
		getLocal(ctx, dimsSlot);
		callFixed(ctx, WasmLispCompiler.FUNC_ARR_DIMS);
		setLocal(ctx, dimsArrSlot);
		getLocal(ctx, dimsArrSlot);
		callFixed(ctx, WasmLispCompiler.FUNC_ARR_TOTAL);
		setLocal(ctx, totalSlot);
		ctx.writer.write(Instruction.END); // end outer if
	}

	// call $_arr_dims / $_arr_total and the other fixed-index runtime helpers.
	private static void callFixed(WasmLispCompiler.Ctx ctx, int func) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(func);
	}

	// Pushes nothing; stores the i31 product of the dims of the array cell in targetSlot
	// into productSlot.
	private static void emitTargetDimsProduct(WasmLispCompiler.Ctx ctx, int targetSlot, int productSlot) {
		// A STRING target has no dims header: its element count is its character count,
		// and the view built over it is a string VIEW (the target decides the shape).
		getLocal(ctx, targetSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, targetSlot);
		WasmEmitHelper.emitStrCharCountCall(ctx);
		boxI31(ctx);
		setLocal(ctx, productSlot);
		ctx.writer.write(Instruction.ELSE);
		// The product of the target's dims -- the same fold make-array sizes its data
		// with, so it is the same callee.
		getLocal(ctx, targetSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
		callFixed(ctx, WasmLispCompiler.FUNC_ARR_TOTAL);
		setLocal(ctx, productSlot);
		ctx.writer.write(Instruction.END); // string-vs-array if
	}

	static void compileAref(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// The array expression is evaluated exactly once (side effects run once, not
		// once per branch below): pushed here, then run through emitArefCheckRank, which
		// traps unless the array's actual rank matches subscriptCount -- the ORIGINAL
		// number of subscripts at this call site (0 for a bare (aref a), which reads a
		// rank-0 array's single element), computed before any arity-specific dispatch
		// below (todo 479; the JVM backend had the same hole --
		// JvmArrayCompiler#compileAref
		// carries the matching comment).
		int subscriptCount = args.size() - 2;
		// Evaluate the array once; a packed farray reads its unboxed f64 store directly,
		// a
		// general array resolves the displacement chain into its buckets.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		emitArefCheckRank(ctx, arrSlot, subscriptCount);
		if (subscriptCount <= 1) {
			// Rank 0 or 1: evaluate the index once (the constant 0 for a bare (aref a):
			// a rank-0 array holds its one element at row-major index 0), then dispatch
			// from the slots (the shape WasmIntFusionCompiler reuses for its aref
			// leaves). A site whose array representation a declaration (or an
			// initializer this compile itself chose a representation for) pins down
			// emits that ONE representation's read with a trapping ref.cast instead of
			// the full dispatch chain (.kb/declarations-type-checks.md).
			WasmExprCompiler.compileExpr(subscriptCount == 1 ? args.get(2) : new LispInteger(0), ctx);
			int idxSlot = setTemp(ctx);
			DeclaredArrayTypes.Kind kind = arrayKindOfExpr(args.get(1), ctx);
			if (kind != null) {
				emitKindedAref1(ctx, kind, arrSlot, idxSlot);
			}
			else {
				emitAref1FromSlots(ctx, arrSlot, idxSlot);
			}
			return;
		}
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed: box(data[Horner(subscripts)]), reading the f64/f32 store per width.
		int pdimsSlot = ctx.allocTemp();
		farrayField(ctx, arrSlot, 0);
		setLocal(ctx, pdimsSlot);
		emitPackedFlatIndex(ctx, pdimsSlot, args, 2, subscriptCount);
		boxI31(ctx);
		int pIdxSlot = setTemp(ctx);
		emitPackedReadF64(ctx, arrSlot, pIdxSlot);
		boxFloat(ctx);
		ctx.writer.write(Instruction.ELSE);
		// general: arr -> header (the (dims . (meta . data)) cons), then data[flat].
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		emitFlatIndex(ctx, headerSlot, args, 2, subscriptCount);
		callArrGet(ctx);
		ctx.writer.write(Instruction.END);
	}

	// Calls the shared _arr_check_rank(arr, given) -> arr (FUNC_ARR_CHECK_RANK,
	// WasmArrayRuntimeBuilder#buildArrCheckRankBody): traps (UNREACHABLE) when the array
	// in arrSlot's actual rank doesn't match `given`, the subscript count the aref/%aset
	// call site baked in at compile time -- the same invariant
	// LispArray/LispFloatArray#flatIndex enforce (with a message) in the interpreter.
	// The WASM backend's internal array-compiler checks are bare traps with no message
	// (see the displaced-to bounds check in compileMakeDisplaced), so this one is too.
	// The returned reference is dropped -- arrSlot already holds it, every arm below
	// reads it from there -- so this is a call, not a per-site copy of the four-way
	// representation dispatch (~90 bytes; see
	// WasmLispCompilerTest#anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime).
	// Never called from row-major-aref/%row-major-aset, which intentionally accept any
	// rank.
	private static void emitArefCheckRank(WasmLispCompiler.Ctx ctx, int arrSlot, int given) {
		getLocal(ctx, arrSlot);
		i32Const(ctx, given);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_ARR_CHECK_RANK);
		ctx.writer.write(Instruction.DROP);
	}

	// The rank-1 aref dispatch over pre-evaluated slots (array as eq, index boxed):
	// string -> character decode, packed float -> boxed f64, packed integer vector ->
	// boxed unsigned element, general -> displacement-resolved buckets read. Leaves the
	// boxed element on the stack. Shared by compileAref and the fused-tree fallback in
	// WasmIntFusionCompiler.
	static void emitAref1FromSlots(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot) {
		// A string is a rank-1 character array in CL: (aref s i) reads like (char s i),
		// walking the UTF-8 byte data with _str_char_at to decode the i-th character's
		// 1-4 byte sequence.
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		emitIfEq(ctx);
		getLocal(ctx, arrSlot);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		WasmEmitHelper.emitStrCharAtCall(ctx);
		WasmCharCompiler.makeChar(ctx);
		ctx.writer.write(Instruction.ELSE);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed float: box(data[idx]), reading the f64/f32 store per width.
		emitPackedReadF64(ctx, arrSlot, idxSlot);
		boxFloat(ctx);
		ctx.writer.write(Instruction.ELSE);
		// packed integer vector: box(_int_new(data[idx])), reading the raw i8/i16/i32
		// store unsigned.
		testIntVector(ctx, arrSlot);
		emitIfEq(ctx);
		emitPackedIntRead(ctx, arrSlot, idxSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		ctx.writer.write(Instruction.ELSE);
		// general: arr -> header (the (dims . (meta . data)) cons), then data[idx].
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		callArrGet(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * The array representation kind of an expression at an access site, or null when
	 * nothing pins one down. Three sources, each a declaration or this compile's own
	 * choice: a lexical variable with a declared/derived kind
	 * ({@code Ctx.declaredArrays}, registered by the defun/lambda body setup and
	 * {@link WasmLetCompiler}), a {@code (the spec expr)} wrap, and a {@code defstruct}
	 * accessor call whose slot declares a {@code :type} (via the registry side table;
	 * skipped under {@code --dynamic} and for a name the program defines more than once,
	 * where the accessor's body is not the only definition the call could reach).
	 */
	static DeclaredArrayTypes.@Nullable Kind arrayKindOfExpr(LispVal expr, WasmLispCompiler.Ctx ctx) {
		if (expr instanceof LispSymbol sym) {
			return ctx.declaredArrays.get(sym.name());
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (LispNames.THE.equals(localName(head.name())) && parts.size() == 3) {
			DeclaredArrayTypes.Kind kind = DeclaredArrayTypes.kindOfSpec(parts.get(1), ctx.closRegistry);
			return kind != null ? kind : arrayKindOfExpr(parts.get(2), ctx);
		}
		if (parts.size() == 2 && !ctx.dynamic && ctx.closRegistry != null
				&& !ctx.duplicatedDefunNames.contains(head.name())) {
			LispVal slotType = ctx.closRegistry.structAccessorType(head.name());
			if (slotType != null) {
				return DeclaredArrayTypes.kindOfSpec(slotType, ctx.closRegistry);
			}
		}
		return null;
	}

	/**
	 * Whether an expression PROVABLY evaluates to a value {@code %arrayp} answers true
	 * for -- never a list, never an immutable string. This is a weaker question than
	 * {@link #arrayKindOfExpr}: it asks which BRANCH of a representation dispatch a value
	 * can take, not which accessor to emit, so it is answered for a {@code make-array}
	 * whose rank (and therefore whose packed-or-general representation) is not known
	 * until run time.
	 *
	 * <p>
	 * Its consumers are the {@code replace} / {@code fill} sites, which route to the
	 * array-arm-only shared runtime on it ({@code .kb/sequence-op-runtimes.md}). Three
	 * sources, and each is one already in use for array emission:
	 * <ol>
	 * <li>a pinned non-string representation kind (a declaration, a {@code defstruct}
	 * slot {@code :type}, a {@code (the ...)} wrap, an initializer this compile chose a
	 * representation for) -- {@link #arrayKindOfExpr};</li>
	 * <li>a {@code let} binding registered in {@code Ctx.arrayLocals} because its
	 * initializer proved this weaker fact and the body never reassigns it;</li>
	 * <li>a {@code make-array} call right at the site, through
	 * {@link #makeArrayBuildsArrayValue}.</li>
	 * </ol>
	 * A false DECLARATION is undefined behavior in CL and becomes a deterministic trap
	 * here, exactly as {@code .kb/declarations-type-checks.md} records for the single-arm
	 * accessors: the array arm's {@code %row-major-aset} casts, and a list or string
	 * fails that cast.
	 * @param expr the expression at the sequence position
	 * @param ctx the compile context
	 * @return true only when the value cannot be a list or an immutable string
	 */
	static boolean provesArrayValue(LispVal expr, WasmLispCompiler.Ctx ctx) {
		if (expr instanceof LispSymbol sym && ctx.arrayLocals.contains(sym.name())) {
			return true;
		}
		DeclaredArrayTypes.Kind kind = arrayKindOfExpr(expr, ctx);
		if (kind != null) {
			return kind != DeclaredArrayTypes.Kind.STRING;
		}
		return makeArrayBuildsArrayValue(expr, ctx);
	}

	/**
	 * Whether an expression is a {@code make-array} call that cannot answer a STRING --
	 * i.e. one whose value {@code %arrayp} is true whatever its rank turns out to be at
	 * run time, which is what lets this answer where {@link #initExprKind} cannot (that
	 * one must know the rank, because rank decides packed-or-general).
	 *
	 * <p>
	 * Conservative in three places, each of them a shape {@link #compileMake} can route
	 * to a string or to a value it is not worth reasoning about: a CHARACTER element type
	 * (with {@code :initial-contents} it builds a fresh string), an element type that is
	 * a bare symbol (a VARIABLE holding a designator computed at run time, which can name
	 * {@code character}) and {@code :displaced-to} (whose target may itself be a string).
	 * Every other shape -- packed, general, fill-pointered, adjustable, any rank -- is an
	 * array.
	 * @param expr the expression at the sequence position
	 * @param ctx the compile context
	 * @return true when the call cannot answer anything but an array
	 */
	static boolean makeArrayBuildsArrayValue(LispVal expr, WasmLispCompiler.Ctx ctx) {
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)
				|| !LispNames.MAKE_ARRAY.equals(localName(head.name()))
				|| ctx.duplicatedDefunNames.contains(head.name())) {
			return false;
		}
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || args.size() % 2 != 0) {
			return false;
		}
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol key)) {
				return false;
			}
			switch (key.name()) {
				case LispNames.ELEMENT_TYPE_KEYWORD -> {
					LispVal raw = args.get(i + 1);
					if (raw instanceof LispSymbol) {
						return false;
					}
					if (LispMacroExpander
						.isCharacterElementType(LispMacroExpander.resolveElementTypeAlias(raw, ctx.closRegistry))) {
						return false;
					}
				}
				case LispNames.INITIAL_ELEMENT_KEYWORD, LispNames.INITIAL_CONTENTS_KEYWORD,
						LispNames.FILL_POINTER_KEYWORD, LispNames.ADJUSTABLE_KEYWORD -> {
					// None of these can turn the result into anything but an array.
				}
				default -> {
					return false;
				}
			}
		}
		return true;
	}

	private static String localName(String name) {
		int colon = name.lastIndexOf(':');
		return colon >= 0 ? name.substring(colon + 1) : name;
	}

	/**
	 * The declared array kinds a FUNCTION body's leading {@code (declare (type ...))}
	 * forms establish for its parameters (and free names), specials filtered out. The
	 * declarations may sit one wrapper deep: {@code LambdaLists} wraps a
	 * return-from-using body in {@code (%fn-block name ...)} and the flet lowering wraps
	 * a local function's body in {@code (block name ...)} -- both still declare the
	 * function body, so the scan follows a sole trailing wrapper of either spelling.
	 */
	static Map<String, DeclaredArrayTypes.Kind> functionBodyDeclaredKinds(List<LispVal> bodyExprs,
			WasmLispCompiler.Ctx ctx) {
		Map<String, DeclaredArrayTypes.Kind> kinds = new java.util.HashMap<>(
				DeclaredArrayTypes.declaredKinds(bodyExprs, ctx.closRegistry));
		for (int i = 0; i < bodyExprs.size(); i++) {
			LispVal form = bodyExprs.get(i);
			if (form instanceof am.ik.rontolisp.LispString || isDeclareForm(form)) {
				continue;
			}
			if (i == bodyExprs.size() - 1 && form instanceof LispCons wrapper && wrapper.isProperList()
					&& wrapper.car() instanceof LispSymbol head && (LispNames.FN_BLOCK_INTERNAL.equals(head.name())
							|| LispNames.BLOCK.equals(localName(head.name())))
					&& wrapper.toList().size() >= 3) {
				List<LispVal> wrapped = wrapper.toList();
				DeclaredArrayTypes.declaredKinds(wrapped.subList(2, wrapped.size()), ctx.closRegistry)
					.forEach(kinds::putIfAbsent);
			}
			break;
		}
		kinds.keySet().removeAll(ctx.specialVars);
		return kinds.isEmpty() ? Map.of() : kinds;
	}

	private static boolean isDeclareForm(LispVal form) {
		return form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& LispNames.DECLARE.equals(localName(head.name()));
	}

	/**
	 * A single-pair {@code (setf (aref var idx) value)} whose VARIABLE place has a pinned
	 * non-string array kind, rewritten to the {@code (%aset var idx value)} the setf
	 * expansion would reach -- minus the {@code stringp}/{@code schar-set} branch the
	 * expansion wraps around a variable place (a string is a rank-1 character array, but
	 * a value of this kind cannot be one). Null for every other shape, which keeps the
	 * ordinary expansion.
	 */
	static @Nullable LispCons nonStringArefStore(LispCons setfCons, WasmLispCompiler.Ctx ctx) {
		if (!setfCons.isProperList()) {
			return null;
		}
		List<LispVal> parts = setfCons.toList();
		if (parts.size() != 3 || !(parts.get(1) instanceof LispCons place) || !place.isProperList()
				|| !(place.car() instanceof LispSymbol head)) {
			return null;
		}
		String op = localName(head.name());
		if (!LispNames.AREF.equals(op) && !LispNames.SVREF.equals(op)) {
			return null;
		}
		List<LispVal> placeParts = place.toList();
		if (placeParts.size() != 3 || !(placeParts.get(1) instanceof LispSymbol arrayVar)) {
			return null;
		}
		DeclaredArrayTypes.Kind kind = arrayKindOfExpr(arrayVar, ctx);
		if (kind == null || kind == DeclaredArrayTypes.Kind.STRING) {
			return null;
		}
		return new LispCons(new LispSymbol(LispNames.ASET),
				new LispCons(arrayVar, new LispCons(placeParts.get(2), new LispCons(parts.get(2), LispNil.INSTANCE))));
	}

	/**
	 * The array kind a {@code let} binding's INIT expression proves without any
	 * declaration -- this compile's own representation choice, so the kind is exact, not
	 * trusted: a literal packed {@code make-array} (the same recognizers
	 * {@link #compileMake} uses), or a kinded source through {@link #arrayKindOfExpr} (a
	 * declared outer variable, an accessor call). The caller must still verify the
	 * binding is never reassigned; a declaration needs no such check (it covers
	 * assignments too).
	 */
	static DeclaredArrayTypes.@Nullable Kind initExprKind(LispVal init, WasmLispCompiler.Ctx ctx) {
		DeclaredArrayTypes.Kind direct = arrayKindOfExpr(init, ctx);
		if (direct != null) {
			return direct;
		}
		if (!(init instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)
				|| !LispNames.MAKE_ARRAY.equals(localName(head.name()))) {
			return null;
		}
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || !(args.get(1) instanceof LispInteger)) {
			// Rank-1 with a literal size only; a dims list or computed size keeps the
			// generic path.
			return null;
		}
		LispVal elementType = null;
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol key)) {
				return null;
			}
			switch (key.name()) {
				case LispNames.ELEMENT_TYPE_KEYWORD -> elementType = args.get(i + 1);
				case LispNames.INITIAL_ELEMENT_KEYWORD, LispNames.INITIAL_CONTENTS_KEYWORD -> {
					// Neither changes the representation.
				}
				default -> {
					// :fill-pointer / :adjustable / :displaced-to (or a computed keyword)
					// build the general adjustable shapes -- prove nothing here.
					return null;
				}
			}
		}
		LispVal resolved = LispMacroExpander.resolveElementTypeAlias(elementType, ctx.closRegistry);
		if (resolved == null) {
			return DeclaredArrayTypes.Kind.GENERAL;
		}
		int packedWidth = packedIntElementWidth(resolved);
		if (packedWidth != 0) {
			return switch (packedWidth) {
				case 8 -> DeclaredArrayTypes.Kind.U8;
				case 16 -> DeclaredArrayTypes.Kind.U16;
				default -> DeclaredArrayTypes.Kind.U32;
			};
		}
		if (isDoubleFloatElementType(resolved) || isSingleFloatElementType(resolved)) {
			return DeclaredArrayTypes.Kind.FLOAT;
		}
		if (LispMacroExpander.isCharacterElementType(resolved)) {
			// A character vector's representation is the marked general array OR a
			// string after normalization -- two representations, so prove neither.
			return null;
		}
		if (elementTypeLocalName(resolved) != null || resolved instanceof LispCons) {
			// A literal non-packed element type ((unsigned-byte 64), fixnum, t, ...)
			// keeps the general boxed representation.
			return DeclaredArrayTypes.Kind.GENERAL;
		}
		return null;
	}

	// The declared-kind single-arm rank-1 read: the one representation's accessor with a
	// trapping ref.cast where the generic chain runs the 4-way dispatch. arrSlot holds
	// the array (as eq), idxSlot the boxed index; leaves the boxed element.
	private static void emitKindedAref1(WasmLispCompiler.Ctx ctx, DeclaredArrayTypes.Kind kind, int arrSlot,
			int idxSlot) {
		switch (kind) {
			case U8, U16, U32 -> {
				int type = intArrType(kind.packedIntWidth());
				getLocal(ctx, arrSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(type);
				getLocal(ctx, idxSlot);
				WasmEmitHelper.castI31GetS(ctx);
				if (kind == DeclaredArrayTypes.Kind.U32) {
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
					ctx.writer.writeUnsignedLeb128(type);
					ctx.writer.write(Instruction.I64_EXTEND_U_I32);
					ctx.writer.write(Instruction.CALL);
					ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
				}
				else {
					// A u8/u16 element always fits an i31: box inline, no call.
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
					ctx.writer.writeUnsignedLeb128(type);
					boxI31(ctx);
				}
			}
			case FLOAT -> {
				emitPackedReadF64(ctx, arrSlot, idxSlot);
				boxFloat(ctx);
			}
			case GENERAL -> {
				getLocal(ctx, arrSlot);
				castCellGet0(ctx);
				getLocal(ctx, idxSlot);
				WasmEmitHelper.castI31GetS(ctx);
				callArrGet(ctx);
			}
			case STRING -> {
				getLocal(ctx, arrSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
				getLocal(ctx, idxSlot);
				WasmEmitHelper.castI31GetS(ctx);
				WasmEmitHelper.emitStrCharAtCall(ctx);
				WasmCharCompiler.makeChar(ctx);
			}
		}
	}

	static void compileRowMajorAref(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (row-major-aref array index): the data array is flat, so this is exactly the
		// rank-1 accessor (data[index]) shape aref uses, independent of the array's
		// rank -- including the STRING arm (a string is a rank-1 character array in
		// CL), so this shares aref's rank-1 slot dispatch rather than repeating the
		// farray/int-vector/general chain without it.
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"row-major-aref expects an array and an index, got " + (args.size() - 1) + " argument(s)");
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int idxSlot = setTemp(ctx);
		emitAref1FromSlots(ctx, arrSlot, idxSlot);
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
		// packed integer vector: raw mask-store, returning the value AS STORED.
		testIntVector(ctx, arrSlot);
		emitIfEq(ctx);
		emitPackedIntStore(ctx, arrSlot, args.get(2), args.get(3), true);
		ctx.writer.write(Instruction.ELSE);
		// general: resolve the displacement chain, store, and leave the value.
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		WasmExprCompiler.compileExpr(args.get(3), ctx);
		callArrSet(ctx);
		ctx.writer.write(Instruction.END);
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
		// An immutable string carries no header at all, but it IS a rank-1 character
		// array: its dimensions are its length in code points. Every other shape reader
		// -- array-rank, array-dimension, array-total-size, array-row-major-index --
		// expands through array-dimensions, so this one arm is what lets all of them
		// accept a string, as the interpreter's do.
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		emitIfEq(ctx);
		getLocal(ctx, arrSlot);
		WasmEmitHelper.emitStrCharCountCall(ctx);
		boxI31(ctx);
		i32Const(ctx, 1);
		arrayNew(ctx);
		ctx.writer.write(Instruction.ELSE);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		farrayField(ctx, arrSlot, 0);
		ctx.writer.write(Instruction.ELSE);
		// packed integer vector: a fresh 1-length buckets array of the boxed length
		// (rank-1 by construction), feeding the shared cons-list build below.
		testIntVector(ctx, arrSlot);
		emitIfEq(ctx);
		emitPackedIntLen(ctx, arrSlot);
		boxI31(ctx);
		i32Const(ctx, 1);
		arrayNew(ctx);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
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
		compileAset(cons, ctx, true);
	}

	/**
	 * Compiles {@code (%aset array subscript... value)}. With {@code resultNeeded} false
	 * (a statement position, see {@code WasmExprCompiler.compileForEffect}) the
	 * value-as-stored is not materialized: the packed integer-vector arm skips its
	 * read-back {@code _int_new} box entirely -- the hot-loop store allocates nothing.
	 */
	static void compileAset(LispCons cons, WasmLispCompiler.Ctx ctx, boolean resultNeeded) {
		// (%aset array subscript... value)
		List<LispVal> args = cons.toList();
		// subscriptCount is the ORIGINAL number of subscripts at this call site (0 for a
		// bare (%aset a value) -- a rank-0 array holds its one element at row-major
		// index 0, the twin of compileAref's (aref a) arm), computed before any
		// arity-specific dispatch below so emitArefCheckRank always runs, even through
		// the rank-1 fast paths (todo 479; see compileAref's matching comment). idxExpr
		// substitutes a literal 0 for the subscript expression that a 0-subscript call
		// site does not have -- args.get(1) (the array) and args.get(args.size() - 1)
		// (the value) already fall in the right place for both shapes, so no rewritten
		// cons is needed the way compileAref's (aref a) rank-0 arm once built one.
		int subscriptCount = args.size() - 3;
		LispVal idxExpr = subscriptCount == 0 ? new LispInteger(0) : args.get(2);
		if (subscriptCount <= 1) {
			// A store whose array representation is pinned down (declaration / accessor
			// slot :type / this compile's own initializer choice) emits that ONE arm; a
			// string kind never stores (strings are immutable structs -- the generic
			// general arm's cast traps there too, so the generic path answers it).
			DeclaredArrayTypes.Kind kind = arrayKindOfExpr(args.get(1), ctx);
			if (kind != null && kind != DeclaredArrayTypes.Kind.STRING) {
				emitKindedAset1(args, idxExpr, kind, ctx, resultNeeded, subscriptCount);
				return;
			}
			if (!WasmIntFusionCompiler.speedTradesEnabled(ctx)) {
				// Size level: evaluate array/index/value ONCE into temps and let every
				// arm read the slots -- the legacy shape below re-emits the index and
				// value expressions in each arm, which triples their bytes (only one arm
				// ever runs, so evaluation order and once-only effects are identical).
				// The speed levels keep the legacy shape: its packed-int arm compiles
				// the value RAW through the fusion machinery, which a pre-boxed temp
				// would defeat.
				emitHoistedAset1(args, idxExpr, ctx, resultNeeded, subscriptCount);
				return;
			}
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		emitArefCheckRank(ctx, arrSlot, subscriptCount);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed: store the coerced f64 (narrowing to f32 for a single-float array) at
		// data[Horner(subscripts)], returning the value AS STORED (read back widened) to
		// match the interpreter/JVM across widths. Evaluation order: array (done),
		// subscripts, then value.
		int pdimsSlot = ctx.allocTemp();
		farrayField(ctx, arrSlot, 0);
		setLocal(ctx, pdimsSlot);
		emitPackedFlatIndex(ctx, pdimsSlot, args, 2, subscriptCount);
		boxI31(ctx);
		int pIdxSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(args.size() - 1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		boxFloat(ctx);
		int pBoxSlot = setTemp(ctx);
		emitPackedWriteF64(ctx, arrSlot, pIdxSlot, pBoxSlot);
		ctx.writer.write(Instruction.ELSE);
		if (subscriptCount == 1) {
			// packed integer vector: raw mask-store (no box on the value's fast path,
			// see emitPackedIntStore), returning the value AS STORED when needed. A
			// rank > 1 subscript set never targets one (rank-1 by construction).
			testIntVector(ctx, arrSlot);
			emitIfEq(ctx);
			emitPackedIntStore(ctx, arrSlot, args.get(2), args.get(args.size() - 1), resultNeeded);
			ctx.writer.write(Instruction.ELSE);
		}
		// general: data[flat] = val, leaving val as the result -- the shared _arr_set
		// answers the value it stored.
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		emitFlatIndex(ctx, headerSlot, args, 2, subscriptCount);
		WasmExprCompiler.compileExpr(args.get(args.size() - 1), ctx);
		callArrSet(ctx);
		if (subscriptCount == 1) {
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.END);
		if (!resultNeeded) {
			ctx.writer.write(Instruction.DROP);
		}
	}

	// The declared-kind single-arm rank-1 store: array, subscript and value evaluate in
	// the generic order, then the one representation's store runs with a trapping
	// ref.cast. A packed integer store keeps the raw-value fast path (tryCompileRaw)
	// because the single arm needs the value only once; the result -- the value AS
	// STORED -- materializes only when the caller consumes it. `given` is the caller's
	// original subscript count (0 or 1; compileAset never dispatches here for 2+), fed
	// straight to emitArefCheckRank.
	private static void emitKindedAset1(List<LispVal> args, LispVal idxExpr, DeclaredArrayTypes.Kind kind,
			WasmLispCompiler.Ctx ctx, boolean resultNeeded, int given) {
		LispVal valueExpr = args.get(args.size() - 1);
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		emitArefCheckRank(ctx, arrSlot, given);
		switch (kind) {
			case U8, U16, U32 -> {
				int type = intArrType(kind.packedIntWidth());
				WasmExprCompiler.compileExpr(idxExpr, ctx);
				int idxSlot = setTemp(ctx);
				getLocal(ctx, arrSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(type);
				getLocal(ctx, idxSlot);
				WasmEmitHelper.castI31GetS(ctx);
				if (!WasmIntFusionCompiler.tryCompileRaw(valueExpr, ctx)) {
					WasmExprCompiler.compileExpr(valueExpr, ctx);
					int valSlot = setTemp(ctx);
					emitUnboxIntForStore(ctx, valSlot);
				}
				ctx.writer.write(Instruction.I32_WRAP_I64);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
				ctx.writer.writeUnsignedLeb128(type);
				if (resultNeeded) {
					emitKindedAref1(ctx, kind, arrSlot, idxSlot);
				}
			}
			case FLOAT -> {
				WasmExprCompiler.compileExpr(idxExpr, ctx);
				int idxSlot = setTemp(ctx);
				WasmExprCompiler.compileExpr(valueExpr, ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				boxFloat(ctx);
				int boxSlot = setTemp(ctx);
				emitPackedWriteF64(ctx, arrSlot, idxSlot, boxSlot);
				if (!resultNeeded) {
					ctx.writer.write(Instruction.DROP);
				}
			}
			case GENERAL -> {
				getLocal(ctx, arrSlot);
				castCellGet0(ctx);
				WasmExprCompiler.compileExpr(idxExpr, ctx);
				WasmEmitHelper.castI31GetS(ctx);
				WasmExprCompiler.compileExpr(valueExpr, ctx);
				callArrSet(ctx);
				if (!resultNeeded) {
					ctx.writer.write(Instruction.DROP);
				}
			}
			case STRING -> throw new IllegalStateException("a string kind never reaches the kinded store");
		}
	}

	// The size-level generic rank-1 store: array/index/value hoisted into temps ONCE,
	// then the same three-arm dispatch as the legacy shape reading the slots -- one
	// evaluation each instead of a per-arm re-emission of the index and value
	// expressions. Leaves the value as stored (or nothing when unconsumed), exactly like
	// the legacy emission.
	private static void emitHoistedAset1(List<LispVal> args, LispVal idxExpr, WasmLispCompiler.Ctx ctx,
			boolean resultNeeded, int given) {
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		emitArefCheckRank(ctx, arrSlot, given);
		WasmExprCompiler.compileExpr(idxExpr, ctx);
		int idxSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(args.size() - 1), ctx);
		int valSlot = setTemp(ctx);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		// packed float: coerce the boxed value to its float box and store at the flat
		// (= sole) subscript.
		getLocal(ctx, valSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		boxFloat(ctx);
		int boxSlot = setTemp(ctx);
		emitPackedWriteF64(ctx, arrSlot, idxSlot, boxSlot);
		ctx.writer.write(Instruction.ELSE);
		testIntVector(ctx, arrSlot);
		emitIfEq(ctx);
		// packed integer vector: mask-store through _iv_set, answering the value as
		// stored only when consumed.
		getLocal(ctx, arrSlot);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		emitUnboxIntForStore(ctx, valSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_IV_SET);
		if (resultNeeded) {
			emitPackedIntRead(ctx, arrSlot, idxSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		}
		else {
			refNull(ctx);
		}
		ctx.writer.write(Instruction.ELSE);
		// general: data[idx] = val through the shared _arr_set, which answers the value.
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, valSlot);
		callArrSet(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		if (!resultNeeded) {
			ctx.writer.write(Instruction.DROP);
		}
	}

	// The packed integer-vector store: [arr, idx-i32, raw i64 value] -> _iv_set (width
	// dispatch + wrap-to-width in the helper). The value stays a RAW i64 when it is an
	// integer operation tree (WasmIntFusionCompiler.tryCompileRaw -- no _int_new box at
	// all on the fast path); otherwise it evaluates boxed and unboxes with the store
	// semantics. The result -- the value AS STORED, read back unsigned -- boxes only
	// when the caller consumes it; a statement-position store allocates nothing.
	private static void emitPackedIntStore(WasmLispCompiler.Ctx ctx, int arrSlot, LispVal idxExpr, LispVal valueExpr,
			boolean resultNeeded) {
		WasmExprCompiler.compileExpr(idxExpr, ctx);
		int idxSlot = setTemp(ctx);
		getLocal(ctx, arrSlot);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		if (!WasmIntFusionCompiler.tryCompileRaw(valueExpr, ctx)) {
			WasmExprCompiler.compileExpr(valueExpr, ctx);
			int valSlot = setTemp(ctx);
			emitUnboxIntForStore(ctx, valSlot);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_IV_SET);
		if (resultNeeded) {
			emitPackedIntRead(ctx, arrSlot, idxSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		}
		else {
			refNull(ctx);
		}
	}

	// The general-array read: expects [header, i32 flat] on the stack, leaves the
	// element.
	// The displacement-chain walk behind it is WasmArrayRuntimeBuilder's, emitted once
	// per module -- it used to be spelled inline here, ~45 instructions and two
	// never-released temps at each of the five accessor sites.
	private static void callArrGet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_ARR_GET);
	}

	// The general-array store: expects [header, i32 flat, value] on the stack, leaves the
	// value as stored (which is the value). Same sharing as callArrGet.
	private static void callArrSet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_ARR_SET);
	}

	// Pushes the i32 flat index for the subscripts at args[firstSub..firstSub+rank-1]:
	// the Horner fold flat = ((s0 * dims[1] + s1) * dims[2] + s2) ..., unrolled at the
	// call site (the subscript count is static). dims (car of header) supplies the
	// per-dimension strides; a rank-1 access never touches it.
	private static void emitFlatIndex(WasmLispCompiler.Ctx ctx, int headerSlot, List<LispVal> args, int firstSub,
			int rank) {
		if (rank == 0) {
			// A rank-0 array: the empty fold is the constant 0. (compileAref/compileAset
			// rewrite that shape to an explicit index 0 before reaching here, so this is
			// the fold's definition rather than a live path.)
			i32Const(ctx, 0);
			return;
		}
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
		if (rank == 0) {
			i32Const(ctx, 0);
			return;
		}
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
		// (array-element-type array): character for a string (a string is a vector of
		// characters, the one character type); the symbol single-float / double-float for
		// a packed farray (by the data array's width); the list (unsigned-byte 8|16|32)
		// for a packed integer vector; else t (the general array's lite element type,
		// matching the (if (stringp arr) 'character t) expansion). Emitted
		// unconditionally
		// -- the farray types always exist on the GC backend.
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-element-type expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		// A string answers character before the packed/general dispatch: the synthesized
		// name is unspelled (real run-time data, and character is also a function name).
		WasmStringpCompiler.emitStringpI32(ctx, arrSlot);
		emitIfEq(ctx);
		WasmEmitHelper.compileUnspelledLiteral(LispNames.CHARACTER_TYPE, ctx);
		ctx.writer.write(Instruction.ELSE);
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
		// packed integer vector: the list (unsigned-byte 8|16|32) by the value's width;
		// general array: t (the lite element type).
		testIntVector(ctx, arrSlot);
		emitIfEq(ctx);
		WasmEmitHelper.compileStringLiteral(LispNames.UNSIGNED_BYTE, ctx);
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		i32Const(ctx, 8);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		i32Const(ctx, 16);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 32);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		boxI31(ctx);
		refNull(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.ELSE);
		emitRememberedElementType(ctx, arrSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * The GENERAL array's element type: what the array REMEMBERS being asked for, in the
	 * meta marker word, or {@code t}. Emitted only for the codes the program's
	 * {@code make-array} calls can actually produce
	 * ({@link WasmLispCompiler.Ctx#typedArrayCodes}), so a program that never asks for a
	 * specialized element type gets exactly the constant {@code t} it always got.
	 *
	 * <p>
	 * The shape guard is {@code %arrayp}'s (a {@code TYPE_CELL} whose header car is the
	 * dims array, which is what tells an array from a hash table); the displacement guard
	 * is {@code %array-disp-offset}'s, because the marker word holds a real offset on a
	 * displaced view, where the element type is the TARGET's question and is not
	 * remembered.
	 */
	private static void emitRememberedElementType(WasmLispCompiler.Ctx ctx, int arrSlot) {
		if (ctx.typedArrayCodes == 0) {
			WasmEmitHelper.emitTrue(ctx);
			return;
		}
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		emitIfEq(ctx);
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitIfEq(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		emitIfEq(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int dataSlot = setTemp(ctx);
		emitDataSlotIsTarget(ctx, dataSlot);
		emitIfEq(ctx);
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int markerSlot = setTemp(ctx);
		int arms = 0;
		for (int code = ArrayElementTypes.CHARACTER; code <= ArrayElementTypes.DOUBLE_FLOAT; code++) {
			if ((ctx.typedArrayCodes & (1 << code)) == 0) {
				continue;
			}
			arms++;
			getLocal(ctx, markerSlot);
			WasmEmitHelper.castI31GetS(ctx);
			i32Const(ctx, elementTypeMarker(code));
			ctx.writer.write(Instruction.I32_EQ);
			emitIfEq(ctx);
			emitElementTypeValue(ctx, code);
			ctx.writer.write(Instruction.ELSE);
		}
		WasmEmitHelper.emitTrue(ctx);
		for (int i = 0; i < arms; i++) {
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.END);
	}

	// The value array-element-type answers for one remembered code: a name symbol, or
	// the list (unsigned-byte n) built as two conses, exactly as the packed
	// integer-vector arm above builds it.
	private static void emitElementTypeValue(WasmLispCompiler.Ctx ctx, int code) {
		switch (code) {
			case ArrayElementTypes.CHARACTER -> WasmEmitHelper.compileUnspelledLiteral(LispNames.CHARACTER_TYPE, ctx);
			case ArrayElementTypes.SINGLE_FLOAT -> WasmEmitHelper.compileStringLiteral(LispNames.SINGLE_FLOAT, ctx);
			case ArrayElementTypes.DOUBLE_FLOAT -> WasmEmitHelper.compileStringLiteral(LispNames.DOUBLE_FLOAT, ctx);
			default -> {
				WasmEmitHelper.compileStringLiteral(LispNames.UNSIGNED_BYTE, ctx);
				i32Const(ctx, code == ArrayElementTypes.UNSIGNED_BYTE_8 ? 8
						: code == ArrayElementTypes.UNSIGNED_BYTE_16 ? 16 : 32);
				boxI31(ctx);
				refNull(ctx);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			}
		}
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
		// (array-has-fill-pointer-p array): meta.car is an i31? A value that is not the
		// general-array shape -- a packed farray or integer vector, a plain string -- is
		// nil rather than a cast trap, which is both what CL says (a simple array has no
		// fill pointer) and what the interpreter answers; the shape is guarded by
		// ref.test first, exactly as adjustable-array-p guards it.
		requireArgs(cons, 2, "array-has-fill-pointer-p expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valueSlot = setTemp(ctx);
		getLocal(ctx, valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		getLocal(ctx, valueSlot);
		castCellGet0(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		getLocal(ctx, valueSlot);
		castCellGet0(ctx);
		getMeta(ctx);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	static void compileAdjustableArrayP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (adjustable-array-p array): meta.cdr.car holds the raw :adjustable argument
		// (null = nil), so non-null means adjustable. A non-array argument (a plain
		// string handed by cl-ppcre's gather-strings collector) is nil, not a cast
		// trap: the general-array shape is guarded by ref.test first.
		requireArgs(cons, 2, "adjustable-array-p expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valueSlot = setTemp(ctx);
		getLocal(ctx, valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		getLocal(ctx, valueSlot);
		castCellGet0(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		getLocal(ctx, valueSlot);
		castCellGet0(ctx);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	static void compileSimpleArrayP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%simple-array-p x): is x an array (a string included) that is SIMPLE -- no
		// fill pointer, not adjustable, not displaced? Any other value, array or not,
		// answers nil, so the predicate is TOTAL and needs no guard at a call site: it
		// is asked about a value the simple- type specifiers have not narrowed yet.
		//
		// The representations: the immutable TYPE_STRING and both packed shapes
		// (TYPE_FARRAY, the bare integer vectors) are simple by construction, since
		// make-array degrades to the general one the moment :fill-pointer /
		// :adjustable / :displaced-to appears.
		// The general array is the TYPE_CELL box whose header car
		// is a dims bucket array (the test that tells it from a hash table, as %arrayp
		// does), and its meta cons answers the other three: meta.car is an i31 exactly
		// when there IS a fill pointer, meta.cdr.car holds the raw :adjustable argument,
		// and the data slot holds a target (a cell, or the STRING a view aliases) exactly
		// when the array is displaced -- the same rule %array-disp-target reads.
		requireArgs(cons, 2, "%simple-array-p expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valueSlot = setTemp(ctx);
		int headerSlot = ctx.allocTemp();
		int metaSlot = ctx.allocTemp();
		int dataSlot = ctx.allocTemp();

		getLocal(ctx, valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		// A TYPE_STRING is a string only when QUOTE-FRAMED: a symbol's name shares the
		// struct without the frame, and a symbol is no array at all (the frame test is
		// stringp's).
		getLocal(ctx, valueSlot);
		WasmEmitHelper.emitStrBytesArray(ctx);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		i32Const(ctx, 34); // '"'
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		testIntVector(ctx, valueSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.ELSE);

		// The general array: a cell whose header is a cons carrying a dims bucket array.
		getLocal(ctx, valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		getLocal(ctx, valueSlot);
		castCellGet0(ctx);
		setLocal(ctx, headerSlot);
		getLocal(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		setLocal(ctx, metaSlot);
		// A fill pointer (meta.car is an i31) or the :adjustable argument (meta.cdr.car
		// is non-null) settles it: not simple.
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		getLocal(ctx, metaSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.ELSE);
		// Displaced: the data slot holds the target rather than the array's own storage.
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		setLocal(ctx, dataSlot);
		emitDataSlotIsTarget(ctx, dataSlot);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	static void compileStringDimension(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%string-dimension s): the array DIMENSION of a string -- what a sized string
		// type specifier compares against, which is NOT `length` (that answers the fill
		// pointer of a character vector). Callers guard with stringp, so only the two
		// string representations arrive: the immutable TYPE_STRING, whose dimension is
		// its character count, and the TYPE_CELL character vector / string view, whose
		// dimension is dims[0] of the header (an i31 already).
		requireArgs(cons, 2, "%string-dimension expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valueSlot = setTemp(ctx);
		getLocal(ctx, valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		emitIfEq(ctx);
		getLocal(ctx, valueSlot);
		WasmEmitHelper.emitStrCharCountCall(ctx);
		boxI31(ctx);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, valueSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
		castBuckets(ctx);
		i32Const(ctx, 0);
		arrayGet(ctx);
		ctx.writer.write(Instruction.END);
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

	static void compileArrayAdoptElementType(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%array-adopt-element-type new old): make the freshly built general array new
		// remember what old remembers, and answer new. adjust-array does not change an
		// array's element type, and a NON-adjustable adjustment answers a fresh array,
		// so the copy takes the original's stamp.
		//
		// The stamp IS the meta marker word, so this copies that word rather than
		// decoding it: no per-code arm, and therefore nothing for the per-width
		// Ctx.typedArrayCodes gate to predict -- the marker being copied was written by
		// a make-array the same program already contains. Writing a 0 marker onto a
		// fresh array is what it already holds, so the copy is unconditional (the write
		// is one struct.set; a guard would cost more than it saves).
		requireArgs(cons, 3, "%array-adopt-element-type expects 2 arguments");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int newSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int oldSlot = setTemp(ctx);
		getLocal(ctx, newSlot);
		castCellGet0(ctx);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castCons(ctx);
		emitRememberedMarker(ctx, oldSlot);
		boxI31(ctx);
		structSetCons(ctx, 1);
		getLocal(ctx, newSlot);
	}

	/**
	 * Pushes the meta MARKER word the array in {@code arrSlot} carries as an i32, or 0
	 * when it remembers nothing.
	 *
	 * <p>
	 * Two arms, because {@code adjust-array} -- the only caller -- accepts two shapes: a
	 * STRING (an immutable one or the mutable character vector, both marker 1, the one
	 * marker no {@code make-array} scan can predict) and a general array cell, whose word
	 * is read back verbatim. The guards are {@link #emitRememberedElementType}'s: the
	 * header cons whose car is the dims buckets is what tells an array from a hash table,
	 * and a DISPLACED array's word is a real offset rather than a type, so it remembers
	 * nothing. A packed vector reaches here from no caller -- {@code adjust-array}
	 * rejects a packed integer vector outright -- and answers 0.
	 */
	private static void emitRememberedMarker(WasmLispCompiler.Ctx ctx, int arrSlot) {
		WasmStringpCompiler.emitStringpI32(ctx, arrSlot);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		// The mutable character vector's marker: rank 1 of the character element type.
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int dataSlot = setTemp(ctx);
		emitDataSlotIsTarget(ctx, dataSlot);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	static void compileDispTarget(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%array-disp-target array): the displaced-to target cell (the data slot when
		// it is a cell), or nil. An immutable string owns its characters and carries no
		// header at all -- a string VIEW is a cell, not a TYPE_STRING -- so it answers
		// nil without reaching the cell cast.
		requireArgs(cons, 2, "%array-disp-target expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int dtSlot = setTemp(ctx);
		getLocal(ctx, dtSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		emitIfEq(ctx);
		refNull(ctx);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, dtSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int dataSlot = setTemp(ctx);
		emitDataSlotIsTarget(ctx, dataSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.ELSE);
		refNull(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// Leaves 1 when the data slot in dataSlot is a displacement TARGET rather than the
	// array's own storage: a general array's target CELL, or the STRING a string view
	// aliases.
	private static void emitDataSlotIsTarget(WasmLispCompiler.Ctx ctx, int dataSlot) {
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		getLocal(ctx, dataSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.I32_OR);
	}

	static void compileDispOffset(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%array-disp-offset array): the displacement offset i31 (meta.cdr.cdr), but
		// only when the array IS displaced (its data slot holds a target cell) -- a
		// non-displaced array reports i31 0 even when the offset word carries the
		// mutable-character-vector marker (1).
		requireArgs(cons, 2, "%array-disp-offset expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int doSlot = setTemp(ctx);
		getLocal(ctx, doSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		emitIfEq(ctx);
		i32Const(ctx, 0);
		boxI31(ctx);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, doSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int offDataSlot = setTemp(ctx);
		emitDataSlotIsTarget(ctx, offDataSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		boxI31(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	static void compileArrayUndisplace(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%array-undisplace array): the shared _arr_undisplace helper
		// (vector-push-extend's growth already calls it before it grows a full view) --
		// copies a displaced view's current contents into a buckets array of its own and
		// drops the displacement, IN PLACE (struct.set on the existing header), keeping
		// dims/fill-pointer/adjustable; a no-op for an array that already owns its
		// storage. Returns array unchanged (same cell identity: the header is mutated,
		// not rebuilt). adjust-array's expansion calls it UNCONDITIONALLY, on every
		// representation it accepts, so an immutable string -- which carries no header
		// at all, same as %array-disp-target's own check -- is answered unchanged
		// without reaching the cell cast (a packed float/int array has no such guard:
		// it traps on the cast, the same parity bar adjust-array already had via the
		// old %array-disp-target probe).
		requireArgs(cons, 2, "%array-undisplace expects 1 argument");
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int cellSlot = setTemp(ctx);
		getLocal(ctx, cellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		emitIfEq(ctx);
		getLocal(ctx, cellSlot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, cellSlot);
		castCellGet0(ctx);
		callFixed(ctx, WasmLispCompiler.FUNC_ARR_UNDISPLACE);
		ctx.writer.write(Instruction.DROP);
		getLocal(ctx, cellSlot);
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
		ctx.writer.writeRefType(true, Type.EQ.code());
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
		// data[fp - 1], through the shared _arr_get so a displaced view pops its
		// TARGET's element.
		getLocal(ctx, headerSlot);
		getLocal(ctx, fpSlot);
		WasmEmitHelper.castI31GetS(ctx);
		callFixed(ctx, WasmLispCompiler.FUNC_ARR_GET);
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
		// A full DISPLACED view stops being a view first: the shared _arr_undisplace
		// copies its contents into a buckets array of its own and drops the
		// displacement, so the growth below extends that storage instead of running past
		// the end of the target's (SBCL 2.2.9 does the same, and array-displacement
		// answers nil from here on).
		getLocal(ctx, headerSlot);
		callFixed(ctx, WasmLispCompiler.FUNC_ARR_UNDISPLACE);
		ctx.writer.write(Instruction.DROP);
		// The shared growth policy, spelled out in wasm (am.ik.rontolisp.ArrayGrowth,
		// which generated code cannot call): a supplied extension is added verbatim, and
		// otherwise the capacity doubles, off a floor for the zero-capacity vector. The
		// counters stay boxed as i31 (temps are (ref null eq)).
		int newCapSlot = ctx.allocTemp();
		int newDataSlot = ctx.allocTemp();
		int idxSlot = ctx.allocTemp();
		int capSlot = ctx.allocTemp();
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		castBuckets(ctx);
		i32Const(ctx, 0);
		arrayGet(ctx);
		setLocal(ctx, capSlot);
		if (extSlot >= 0) {
			getLocal(ctx, extSlot);
			WasmEmitHelper.castI31GetS(ctx);
			i32Const(ctx, ArrayGrowth.NO_EXTENSION);
			ctx.writer.write(Instruction.I32_GT_S);
			ctx.writer.write(Instruction.IF, 0x7F);
			getLocal(ctx, capSlot);
			WasmEmitHelper.castI31GetS(ctx);
			getLocal(ctx, extSlot);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_ADD);
			ctx.writer.write(Instruction.ELSE);
			emitDefaultGrownCapacity(ctx, capSlot);
			ctx.writer.write(Instruction.END);
		}
		else {
			emitDefaultGrownCapacity(ctx, capSlot);
		}
		boxI31(ctx);
		setLocal(ctx, newCapSlot);
		// newData = array.new buckets (fill, newCap): the slots the growth OPENS take
		// the REMEMBERED element type's own zero, the same fill make-array gives an
		// unsupplied element -- nil for the general vector, which is what this array.new
		// always used.
		emitDefaultElementForHeader(ctx, headerSlot);
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

	// Leaves on the stack, as i32, the capacity a full vector with the i31 capacity in
	// capSlot grows to when vector-push-extend is given no extension: the capacity
	// doubled, or the floor when it is zero (am.ik.rontolisp.ArrayGrowth).
	private static void emitDefaultGrownCapacity(WasmLispCompiler.Ctx ctx, int capSlot) {
		getLocal(ctx, capSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, ArrayGrowth.MIN_CAPACITY);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x7F);
		i32Const(ctx, ArrayGrowth.MIN_CAPACITY);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, capSlot);
		WasmEmitHelper.castI31GetS(ctx);
		i32Const(ctx, ArrayGrowth.GROWTH_FACTOR);
		ctx.writer.write(Instruction.I32_MUL);
		ctx.writer.write(Instruction.END);
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
		// Through the shared _arr_set, not the data slot directly: a DISPLACED
		// fill-pointered view has no data slots of its own, so its push writes THROUGH to
		// the target's storage (SBCL does the same).
		getLocal(ctx, headerSlot);
		getLocal(ctx, fpSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, valSlot);
		callFixed(ctx, WasmLispCompiler.FUNC_ARR_SET);
		ctx.writer.write(Instruction.DROP);
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

	// The packed integer-vector width (8/16/32) a make-array :element-type value
	// designates: the list (unsigned-byte 8|16|32), quoted as written at a call site or
	// bare as a resolved deftype alias hands it over. Anything else -- including a
	// runtime-computed element type -- answers 0 and keeps the general boxed
	// representation, like the float widths. The optional quote makes this accept the
	// same two shapes as JvmArrayCompiler.packedIntElementWidth and the interpreter's
	// (which only ever sees the evaluated, unquoted designator); accepting only the
	// quoted one silently declined every alias the resolver had just expanded.
	static int packedIntElementWidth(@Nullable LispVal elementType) {
		LispVal unquoted = elementType;
		if (unquoted instanceof LispCons quote && quote.car() instanceof LispSymbol q
				&& LispNames.QUOTE.equals(q.name()) && quote.cdr() instanceof LispCons quoteRest
				&& quoteRest.cdr() instanceof LispNil) {
			unquoted = quoteRest.car();
		}
		if (unquoted instanceof LispCons spec && spec.car() instanceof LispSymbol head
				&& spec.cdr() instanceof LispCons widthCell && widthCell.car() instanceof LispInteger width
				&& widthCell.cdr() instanceof LispNil) {
			String name = head.name();
			int colon = name.lastIndexOf(':');
			String local = colon >= 0 ? name.substring(colon + 1) : name;
			if (local.equals(LispNames.UNSIGNED_BYTE)
					&& (width.value() == 8 || width.value() == 16 || width.value() == 32)) {
				return (int) width.value();
			}
		}
		return 0;
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
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
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
		ctx.writer.writeRefType(true, Type.EQ.code());
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		ctx.writer.writeUnsignedLeb128(field);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeUnsignedLeb128(0);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
		ctx.writer.writeUnsignedLeb128(1);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// array.get TYPE_F64ARR: [array, i32 index] -> [f64].
	private static void f64ArrayGet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// array.set TYPE_F64ARR: [array, i32 index, f64] -> [].
	private static void f64ArraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
	}

	// array.new TYPE_F32ARR: [f32 init, i32 size] -> [array].
	private static void f32ArrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
	}

	// array.get TYPE_F32ARR: [array, i32 index] -> [f32].
	private static void f32ArrayGet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
	}

	// array.set TYPE_F32ARR: [array, i32 index, f32] -> [].
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

	// array.new TYPE_HASH_BUCKETS: [init, size] -> [array].
	private static void arrayNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// array.get TYPE_HASH_BUCKETS: [array, i32 index] -> [value].
	private static void arrayGet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// array.set TYPE_HASH_BUCKETS: [array, i32 index, value] -> [].
	private static void arraySet(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(field);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeUnsignedLeb128(0);
	}

	// Assumes a cons (eqref) on the stack; replaces it with car (field 0) or cdr (field
	// 1).
	private static void castConsGet(WasmLispCompiler.Ctx ctx, int field) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(field);
	}

	// --- packed integer-vector (TYPE_I8ARR/I16ARR/I32ARR) helpers ---------------------
	//
	// A packed integer vector is the BARE (array (mut i8|i16|i32)) value (rank-1, no
	// struct wrapper; array.len is the length). Elements store masked to the width
	// (array.set / i32.wrap_i64 truncation) and read back unsigned (array.get_u /
	// i64.extend_i32_u). See WasmLispCompiler.TYPE_I8ARR.

	// Pushes an i32: whether the value in slot is a packed integer vector (any width).
	static void testIntVector(WasmLispCompiler.Ctx ctx, int slot) {
		testIntVector(ctx.writer, slot);
	}

	// The raw-WasmWriter counterpart, for the runtime builders that emit into a
	// WasmWriter directly.
	static void testIntVector(am.ik.wasm.WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.I32_OR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I32ARR);
		w.write(Instruction.I32_OR);
	}

	// Pushes the i32 length of the packed integer vector in slot (width dispatch via
	// ref.test; array.len needs the concrete array type for the cast).
	static void emitPackedIntLen(WasmLispCompiler.Ctx ctx, int slot) {
		emitPackedIntLen(ctx.writer, slot);
	}

	// The raw-WasmWriter counterpart, for the runtime builders that emit into a
	// WasmWriter directly.
	static void emitPackedIntLen(am.ik.wasm.WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.IF, Type.I32.code());
		castIntArrLen(w, slot, WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.IF, Type.I32.code());
		castIntArrLen(w, slot, WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.ELSE);
		castIntArrLen(w, slot, WasmLispCompiler.TYPE_I32ARR);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	private static void castIntArrLen(am.ik.wasm.WasmWriter w, int slot, int type) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(type);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	// Reads data[idx] of the packed integer vector in arrSlot as an UNSIGNED i64,
	// dispatching on the width. idxSlot holds the i31-boxed index; array.get traps on an
	// out-of-range index (the interpreter/JVM signal an error on the same condition).
	// Leaves one i64 on the stack.
	static void emitPackedIntRead(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot) {
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I64);
		emitIntArrGetU(ctx, arrSlot, idxSlot, WasmLispCompiler.TYPE_I8ARR);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I64);
		emitIntArrGetU(ctx, arrSlot, idxSlot, WasmLispCompiler.TYPE_I16ARR);
		ctx.writer.write(Instruction.ELSE);
		emitIntArrGetU(ctx, arrSlot, idxSlot, WasmLispCompiler.TYPE_I32ARR);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Compiles {@code (%replace-bulk dst src s1 s2 n)} -- the bulk-copy arm the shared
	 * {@code %replace-runtime-array} body fronts its element loop with
	 * ({@code LispMacroExpander.replaceDispatch}): when {@code dst} and {@code src} are
	 * DISTINCT packed integer vectors of the SAME width, the three bounds are
	 * non-negative i31s and both ranges are in bounds, the {@code n} elements move with
	 * ONE {@code array.copy} and the form answers {@code t}; every other shape answers
	 * nil having copied nothing, and the caller's loop runs. The same-object case is
	 * deliberately declined: the loop copies forward element by element, and matching it
	 * exactly (rather than {@code array.copy}'s memmove semantics) is what keeps an
	 * overlapping same-array {@code replace} identical across backends. Out-of-range
	 * bounds decline too, so the error shape stays the loop's.
	 */
	static void compileReplaceBulk(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		int[] slots = new int[5];
		for (int i = 0; i < 5; i++) {
			WasmExprCompiler.compileExpr(parts.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		int dst = slots[0];
		int src = slots[1];
		// block $done (result eqref) { block $fail { guards; per-width array.copy;
		// t; br $done } } ref.null eq } -- a decline falls out of $fail to the nil.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.BLOCK, 0x40);
		// The same object must take the loop (see above).
		getLocal(ctx, dst);
		getLocal(ctx, src);
		ctx.writer.write(Instruction.REF_EQ);
		ctx.writer.write(Instruction.BR_IF, 0);
		// Each bound must be a non-negative i31.
		for (int i = 2; i < 5; i++) {
			getLocal(ctx, slots[i]);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(Type.I31.code());
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.BR_IF, 0);
			getLocal(ctx, slots[i]);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.I32_LT_S);
			ctx.writer.write(Instruction.BR_IF, 0);
		}
		for (int type : new int[] { WasmLispCompiler.TYPE_I8ARR, WasmLispCompiler.TYPE_I16ARR,
				WasmLispCompiler.TYPE_I32ARR }) {
			getLocal(ctx, dst);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(type);
			getLocal(ctx, src);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(type);
			ctx.writer.write(Instruction.I32_AND);
			ctx.writer.write(Instruction.IF, 0x40);
			// start + n > len declines (i31 bounds cannot overflow an i32 add).
			emitBulkBoundsGuard(ctx, dst, slots[2], slots[4], type);
			emitBulkBoundsGuard(ctx, src, slots[3], slots[4], type);
			getLocal(ctx, dst);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(type);
			getLocal(ctx, slots[2]);
			WasmEmitHelper.castI31GetS(ctx);
			getLocal(ctx, src);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(type);
			getLocal(ctx, slots[3]);
			WasmEmitHelper.castI31GetS(ctx);
			getLocal(ctx, slots[4]);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_COPY);
			ctx.writer.writeUnsignedLeb128(type);
			ctx.writer.writeUnsignedLeb128(type);
			WasmEmitHelper.emitTrue(ctx);
			ctx.writer.write(Instruction.BR, 2);
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	// [ ] -> [ ]: br_if to the enclosing $fail (depth 1, from inside the width arm's if)
	// when start + n exceeds the packed vector's length.
	private static void emitBulkBoundsGuard(WasmLispCompiler.Ctx ctx, int arrSlot, int startSlot, int nSlot, int type) {
		getLocal(ctx, startSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_ADD);
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(type);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_GT_S);
		ctx.writer.write(Instruction.BR_IF, 1);
	}

	// [ ] -> [i64]: data[idx] zero-extended (array.get_u for the sub-i32 widths, a plain
	// array.get for i32 -- its element IS the raw 32 bits -- then i64.extend_i32_u).
	private static void emitIntArrGetU(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot, int type) {
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(type);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		if (type == WasmLispCompiler.TYPE_I32ARR) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		}
		else {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		}
		ctx.writer.writeUnsignedLeb128(type);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
	}

	// Unboxes the exact integer in valSlot to an i64: an i31 sign-extends, a TYPE_BIGNUM
	// reads its i64 field, a TYPE_BIGINT reads its LOW 32 BITS (limb 0 -- enough for
	// every packed width, matching the interpreter's low-bits masking), anything else
	// (float, ratio, ...) traps -- the interpreter/JVM signal a type error there.
	static void emitUnboxIntForStore(WasmLispCompiler.Ctx ctx, int valSlot) {
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I64);
		getLocal(ctx, valSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I64_EXTEND_S_I32);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I64);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.ELSE);
		// TYPE_BIGINT: low limb (32 bits) via _limb_get(limbs, 0); the ref.cast traps
		// on a non-integer, preserving the exact-or-trap boundary.
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.writeUnsignedLeb128(0);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_LIMB_GET);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	private static void emitIntArrSet(WasmLispCompiler.Ctx ctx, int arrSlot, int idxSlot, int valSlot, int type) {
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(type);
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
		emitUnboxIntForStore(ctx, valSlot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(type);
	}

	// array.new_default of the packed width for TYPE_I8ARR/I16ARR/I32ARR (all elements
	// zero): [i32 size] -> [array].
	static void intArrNewDefault(WasmLispCompiler.Ctx ctx, int type) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		ctx.writer.writeUnsignedLeb128(type);
	}

	// The TYPE_* index of a packed integer-vector width.
	static int intArrType(int width) {
		return switch (width) {
			case 8 -> WasmLispCompiler.TYPE_I8ARR;
			case 16 -> WasmLispCompiler.TYPE_I16ARR;
			default -> WasmLispCompiler.TYPE_I32ARR;
		};
	}

	/**
	 * The marker word of the mutable CHARACTER VECTOR -- a string rather than a general
	 * array remembering a type, so it is outside the remembered-type space and outside
	 * {@link WasmLispCompiler.Ctx#typedArrayCodes}. It answers the character zero for the
	 * same reason marker 2 does.
	 */
	private static final int CHARACTER_VECTOR_MARKER = 1;

	/**
	 * Compiles {@code (%array-default-element array)}: the element an UNSUPPLIED slot of
	 * the array takes -- its element type's own zero, or nil when it remembers nothing.
	 * {@code adjust-array}'s expansion passes it as the {@code :initial-element} it was
	 * not given, so the slots an adjustment OPENS read back as what {@code make-array}
	 * fills an unsupplied element with. Mirrors
	 * {@code am.ik.rontolisp.ArrayElementTypes#defaultElement} and shapes its dispatch
	 * exactly like {@link #compileArrayElementType}'s, because it is the same question
	 * asked of the same four representations.
	 */
	static void compileArrayDefaultElement(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					"%array-default-element expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int arrSlot = setTemp(ctx);
		WasmStringpCompiler.emitStringpI32(ctx, arrSlot);
		emitIfEq(ctx);
		WasmExprCompiler.compileExpr(
				java.util.Objects.requireNonNull(ArrayElementTypes.defaultElement(ArrayElementTypes.CHARACTER)), ctx);
		ctx.writer.write(Instruction.ELSE);
		testFarray(ctx, arrSlot);
		emitIfEq(ctx);
		WasmExprCompiler.compileExpr(
				java.util.Objects.requireNonNull(ArrayElementTypes.defaultElement(ArrayElementTypes.DOUBLE_FLOAT)),
				ctx);
		ctx.writer.write(Instruction.ELSE);
		testIntVector(ctx, arrSlot);
		emitIfEq(ctx);
		WasmExprCompiler.compileExpr(
				java.util.Objects.requireNonNull(ArrayElementTypes.defaultElement(ArrayElementTypes.UNSIGNED_BYTE_8)),
				ctx);
		ctx.writer.write(Instruction.ELSE);
		// The general array: the remembered type's zero, read off the meta marker under
		// %arrayp's shape guard and %array-disp-offset's displacement guard -- a
		// displaced view holds an offset in that word and remembers no type.
		getLocal(ctx, arrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		emitIfEq(ctx);
		getLocal(ctx, arrSlot);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		getLocal(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitIfEq(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		emitIfEq(ctx);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int dataSlot = setTemp(ctx);
		emitDataSlotIsTarget(ctx, dataSlot);
		emitIfEq(ctx);
		refNull(ctx);
		ctx.writer.write(Instruction.ELSE);
		emitDefaultElementForHeader(ctx, headerSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		refNull(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		refNull(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		refNull(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Leaves on the stack the element an UNSUPPLIED slot of the general array whose
	 * header cons is in {@code headerSlot} takes: its remembered element type's own zero,
	 * or nil. The arms for the remembered types are gated per width on
	 * {@link WasmLispCompiler.Ctx#typedArrayCodes}, exactly as
	 * {@link #emitRememberedElementType}'s are, so a program that never asks for a
	 * specialized element type emits only the character-vector arm -- the one marker no
	 * {@code make-array :element-type} scan can predict, because any mutable string
	 * carries it.
	 */
	private static void emitDefaultElementForHeader(WasmLispCompiler.Ctx ctx, int headerSlot) {
		getLocal(ctx, headerSlot);
		getMeta(ctx);
		castConsGet(ctx, 1);
		castConsGet(ctx, 1);
		int markerSlot = setTemp(ctx);
		int arms = 0;
		for (int code = ArrayElementTypes.CHARACTER; code <= ArrayElementTypes.DOUBLE_FLOAT; code++) {
			boolean character = code == ArrayElementTypes.CHARACTER;
			if (!character && (ctx.typedArrayCodes & (1 << code)) == 0) {
				continue;
			}
			arms++;
			if (character) {
				// Marker 1 (the string) always, marker 2 (a rank-n character array) only
				// where a make-array asked for one.
				getLocal(ctx, markerSlot);
				WasmEmitHelper.castI31GetS(ctx);
				i32Const(ctx, CHARACTER_VECTOR_MARKER);
				ctx.writer.write(Instruction.I32_EQ);
				if ((ctx.typedArrayCodes & (1 << code)) != 0) {
					getLocal(ctx, markerSlot);
					WasmEmitHelper.castI31GetS(ctx);
					i32Const(ctx, elementTypeMarker(code));
					ctx.writer.write(Instruction.I32_EQ);
					ctx.writer.write(Instruction.I32_OR);
				}
			}
			else {
				getLocal(ctx, markerSlot);
				WasmEmitHelper.castI31GetS(ctx);
				i32Const(ctx, elementTypeMarker(code));
				ctx.writer.write(Instruction.I32_EQ);
			}
			emitIfEq(ctx);
			WasmExprCompiler.compileExpr(java.util.Objects.requireNonNull(ArrayElementTypes.defaultElement(code)), ctx);
			ctx.writer.write(Instruction.ELSE);
		}
		refNull(ctx);
		for (int i = 0; i < arms; i++) {
			ctx.writer.write(Instruction.END);
		}
	}

	/**
	 * The meta MARKER word for a remembered element type: 0 for {@code t} (nothing
	 * remembered), and {@code code + 1} for anything else, which leaves 1 -- the mutable
	 * character vector, {@code _charvec_p}'s marker -- to the one shape that is a string
	 * rather than a general array carrying a type. The word is only a marker on a
	 * NON-DISPLACED array; a displaced view holds its offset there and a target cell in
	 * its data slot, which is how the two are told apart.
	 * @param code an {@code ArrayElementTypes} code
	 * @return the marker word
	 */
	static int elementTypeMarker(int code) {
		return code == ArrayElementTypes.T ? 0 : code + 1;
	}

	// The ArrayElementTypes code for a packed integer width (8/16/32 by construction).
	private static int elementTypeCodeForWidth(int width) {
		return switch (width) {
			case 8 -> ArrayElementTypes.UNSIGNED_BYTE_8;
			case 16 -> ArrayElementTypes.UNSIGNED_BYTE_16;
			default -> ArrayElementTypes.UNSIGNED_BYTE_32;
		};
	}

}
