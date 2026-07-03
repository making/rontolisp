package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the array built-ins ({@code make-array}, {@code aref}, {@code %aset}). An
 * array mirrors the hash-table layout: a {@code TYPE_CELL} box holding a header
 * {@code TYPE_CONS} of {@code (dims . data)}, where both {@code dims} and {@code data}
 * are {@code TYPE_HASH_BUCKETS} arrays ({@code array (mut (ref null eq))}). {@code dims}
 * holds the dimension sizes as i31 integers; {@code data} holds the row-major elements.
 * Any rank {@code >= 1} is supported: the flat index is the Horner fold over the
 * subscripts (unrolled per call site, whose subscript count is static), so a rank-2
 * element {@code (i, j)} lives at flat index {@code i * dims[1] + j} and a rank-1 element
 * {@code (i)} at {@code i}.
 *
 * <p>
 * Everything is emitted inline (no runtime helper function and no new heap type), so the
 * static function-import indices stay identical across Preview 1 and {@code --component}
 * modes and the component blobs are unaffected.
 */
final class WasmArrayCompiler {

	private WasmArrayCompiler() {
	}

	static void compileMake(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// dims -> dimsSlot, init -> initSlot
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int dimsSlot = setTemp(ctx);
		LispVal init = findInitialElement(args);
		if (init != null) {
			WasmExprCompiler.compileExpr(init, ctx);
		}
		else {
			refNull(ctx);
		}
		int initSlot = setTemp(ctx);
		int dimsArrSlot = ctx.allocTemp();
		int totalSlot = ctx.allocTemp(); // holds an i31-boxed total element count

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

		// data = array.new buckets (init, total)
		getLocal(ctx, initSlot);
		getLocal(ctx, totalSlot);
		WasmEmitHelper.castI31GetS(ctx);
		arrayNew(ctx);
		int dataArrSlot = setTemp(ctx);
		// header = cons(dimsArr, data); cell = struct.new TYPE_CELL(header)
		getLocal(ctx, dimsArrSlot);
		getLocal(ctx, dataArrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	static void compileAref(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int rank = args.size() - 2;
		// arr -> header (the (dims . data) cons)
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		// data[flat]: push the data array first, then the i32 flat index on top.
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castBuckets(ctx);
		emitFlatIndex(ctx, headerSlot, args, 2, rank);
		arrayGet(ctx);
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
		castCellGet0(ctx);
		castConsGet(ctx, 1);
		castBuckets(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		arrayGet(ctx);
	}

	static void compileRowMajorAset(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%row-major-aset array index value): flat store, leaving the value as the
		// result.
		List<LispVal> args = cons.toList();
		if (args.size() != 4) {
			throw new UnsupportedOperationException("%row-major-aset expects an array, an index and a value, got "
					+ (args.size() - 1) + " argument(s)");
		}
		int valSlot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		castConsGet(ctx, 1);
		castBuckets(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		WasmExprCompiler.compileExpr(args.get(3), ctx);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		arraySet(ctx);
		getLocal(ctx, valSlot);
	}

	static void compileDims(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-dimensions expects 1 argument, got " + (args.size() - 1));
		}
		// arr -> header (the (dims . data) cons) -> the dims buckets array in a temp.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
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
		castCellGet0(ctx);
		int headerSlot = setTemp(ctx);
		int valSlot = ctx.allocTemp();
		// data[flat] = val, leaving val as the result. Evaluation order: array (done),
		// subscripts, then value; tee keeps the value on the stack for array.set.
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1);
		castBuckets(ctx);
		emitFlatIndex(ctx, headerSlot, args, 2, rank);
		WasmExprCompiler.compileExpr(args.get(args.size() - 1), ctx);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		arraySet(ctx);
		getLocal(ctx, valSlot);
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

	private static @Nullable LispVal findInitialElement(List<LispVal> args) {
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && LispNames.INITIAL_ELEMENT_KEYWORD.equals(kw.name())) {
				return args.get(i + 1);
			}
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
