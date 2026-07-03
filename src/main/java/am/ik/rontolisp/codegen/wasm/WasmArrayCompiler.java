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
 * Only ranks 1 and 2 are supported, so a rank-2 element {@code (i, j)} lives at flat
 * index {@code i * dims[1] + j} and a rank-1 element {@code (i)} at {@code i}.
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
		// dims is a list (d0 . rest)
		getLocal(ctx, dimsSlot);
		castConsGet(ctx, 1); // rest
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.IF, 0x40);
		// rank 2: dimsArr = [car(dims), car(rest)]; total = d0 * d1
		refNull(ctx);
		i32Const(ctx, 2);
		arrayNew(ctx);
		setLocal(ctx, dimsArrSlot);
		getBuckets(ctx, dimsArrSlot);
		i32Const(ctx, 0);
		getLocal(ctx, dimsSlot);
		castConsGet(ctx, 0); // car(dims)
		arraySet(ctx);
		getBuckets(ctx, dimsArrSlot);
		i32Const(ctx, 1);
		getLocal(ctx, dimsSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 0); // car(cdr(dims))
		arraySet(ctx);
		getLocal(ctx, dimsSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, dimsSlot);
		castConsGet(ctx, 1);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_MUL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, totalSlot);
		ctx.writer.write(Instruction.ELSE);
		// rank 1 (single-element list): dimsArr = [car(dims)]; total = d0
		getLocal(ctx, dimsSlot);
		castConsGet(ctx, 0);
		i32Const(ctx, 1);
		arrayNew(ctx);
		setLocal(ctx, dimsArrSlot);
		getLocal(ctx, dimsSlot);
		castConsGet(ctx, 0);
		setLocal(ctx, totalSlot);
		ctx.writer.write(Instruction.END); // end inner if
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
		int cdrSlot = ctx.allocTemp();
		// cdr = (array.len dims == 1) ? null : cons(dims[1], null)
		getBuckets(ctx, dimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF, 0x40);
		refNull(ctx);
		setLocal(ctx, cdrSlot);
		ctx.writer.write(Instruction.ELSE);
		getBuckets(ctx, dimsSlot);
		i32Const(ctx, 1);
		arrayGet(ctx);
		refNull(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		setLocal(ctx, cdrSlot);
		ctx.writer.write(Instruction.END);
		// result = cons(dims[0], cdr); the dims elements are already i31 integers.
		getBuckets(ctx, dimsSlot);
		i32Const(ctx, 0);
		arrayGet(ctx);
		getLocal(ctx, cdrSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
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

	// Pushes the i32 flat index for the subscripts at args[firstSub..firstSub+rank-1].
	// dims (car of header) is consulted for the rank-2 column stride.
	private static void emitFlatIndex(WasmLispCompiler.Ctx ctx, int headerSlot, List<LispVal> args, int firstSub,
			int rank) {
		if (rank == 1) {
			WasmExprCompiler.compileExpr(args.get(firstSub), ctx);
			WasmEmitHelper.castI31GetS(ctx);
		}
		else if (rank == 2) {
			WasmExprCompiler.compileExpr(args.get(firstSub), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			// cols = dims[1]
			getLocal(ctx, headerSlot);
			castConsGet(ctx, 0);
			castBuckets(ctx);
			i32Const(ctx, 1);
			arrayGet(ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_MUL);
			WasmExprCompiler.compileExpr(args.get(firstSub + 1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_ADD);
		}
		else {
			throw new UnsupportedOperationException("aref supports rank 1 and 2 only, got rank " + rank);
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
