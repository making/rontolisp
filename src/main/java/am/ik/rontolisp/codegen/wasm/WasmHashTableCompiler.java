package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the hash-table built-ins. A hash table is represented as a {@code TYPE_CELL}
 * struct (a mutable single-field box, never otherwise exposed as a Lisp value) whose
 * field holds an association list of {@code (key . value)} pairs. Keys are compared with
 * the recursive {@code _equal} runtime ({@link WasmLispCompiler#FUNC_EQUAL}), i.e.
 * structural comparison, matching the interpreter and the JVM backend. Lookups are O(n);
 * this keeps the backend small while giving programs the standard hash-table API.
 */
final class WasmHashTableCompiler {

	private WasmHashTableCompiler() {
	}

	static void compileMake(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// :test (and any other keyword) is accepted but ignored: lookup is always
		// structural, so the arguments are not evaluated. Result: a cell holding nil.
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	static void compileGet(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// key -> keySlot
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int keySlot = setTemp(ctx);
		// default -> dfltSlot
		if (args.size() > 3) {
			WasmExprCompiler.compileExpr(args.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		int dfltSlot = setTemp(ctx);
		// cur = (alist head of) table
		int curSlot = headSlot(args.get(2), ctx);

		// block $result (eqref) / block $notfound / loop
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		emitCursorIsConsElseBreak(ctx, curSlot, 1); // not a cons -> $notfound

		// equal(key, car(car(cur)))?
		getLocal(ctx, keySlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		castConsGet(ctx, 0); // car(entry) = key
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		ctx.writer.write(Instruction.IF, 0x40);
		// match: push cdr(entry) and break to $result
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		castConsGet(ctx, 1); // cdr(entry) = value
		// depth 3: $if(0) $loop(1) $notfound(2) $result(3)
		ctx.writer.write(Instruction.BR, 3); // -> $result
		ctx.writer.write(Instruction.END); // end if

		advanceCursor(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0); // loop
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end $notfound
		// not found: the default
		getLocal(ctx, dfltSlot);
		ctx.writer.write(Instruction.END); // end $result
	}

	static void compilePut(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%puthash key table value)
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int keySlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(3), ctx);
		int valSlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int cellSlot = setTemp(ctx);
		// cur = cell.field0
		getLocal(ctx, cellSlot);
		castCellGet0(ctx);
		int curSlot = setTemp(ctx);

		ctx.writer.write(Instruction.BLOCK, 0x40); // $done
		ctx.writer.write(Instruction.BLOCK, 0x40); // $notfound
		ctx.writer.write(Instruction.LOOP, 0x40);

		emitCursorIsConsElseBreak(ctx, curSlot, 1); // -> $notfound

		getLocal(ctx, keySlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0);
		castConsGet(ctx, 0); // key of entry
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		ctx.writer.write(Instruction.IF, 0x40);
		// match: rplacd(entry, value)
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
		// depth 3: $if(0) $loop(1) $notfound(2) $done(3)
		ctx.writer.write(Instruction.BR, 3); // -> $done
		ctx.writer.write(Instruction.END); // end if

		advanceCursor(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end $notfound
		// not found: cell.field0 = cons(cons(key, value), oldhead)
		getLocal(ctx, cellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		// new entry = cons(key, value)
		getLocal(ctx, keySlot);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		// cons(newentry, oldhead)
		getLocal(ctx, cellSlot);
		castCellGet0(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END); // end $done
		// return value
		getLocal(ctx, valSlot);
	}

	static void compileRem(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int keySlot = setTemp(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int cellSlot = setTemp(ctx);
		getLocal(ctx, cellSlot);
		castCellGet0(ctx);
		int curSlot = setTemp(ctx);
		// prev = nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		int prevSlot = setTemp(ctx);

		ctx.writer.write(Instruction.BLOCK); // $result (eqref)
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.BLOCK, 0x40); // $notfound
		ctx.writer.write(Instruction.LOOP, 0x40);

		emitCursorIsConsElseBreak(ctx, curSlot, 1);

		getLocal(ctx, keySlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		ctx.writer.write(Instruction.IF, 0x40);
		// remove cur: if prev is nil, cell.field0 = cdr(cur); else rplacd(prev, cdr(cur))
		getLocal(ctx, prevSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, cellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, prevSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.END); // end inner if
		WasmEmitHelper.emitTrue(ctx);
		// depth 3: $outerIf(0) $loop(1) $notfound(2) $result(3)
		ctx.writer.write(Instruction.BR, 3); // -> $result
		ctx.writer.write(Instruction.END); // end outer if

		// prev = cur; cur = cdr(cur)
		getLocal(ctx, curSlot);
		setLocal(ctx, prevSlot);
		advanceCursor(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end $notfound
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END); // end $result
	}

	static void compileClr(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int cellSlot = setTemp(ctx);
		getLocal(ctx, cellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
		getLocal(ctx, cellSlot);
	}

	static void compileCount(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int curSlot = headSlot(args.get(1), ctx);
		// count = i31(0)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		int countSlot = setTemp(ctx);

		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		emitCursorIsConsElseBreak(ctx, curSlot, 1);
		// count = count + 1
		getLocal(ctx, countSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, countSlot);
		advanceCursor(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block
		getLocal(ctx, countSlot);
	}

	static void compileP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	static void compileMaphash(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(2);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + 2;

		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = setTemp(ctx);
		int curSlot = headSlot(args.get(2), ctx);

		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		emitCursorIsConsElseBreak(ctx, curSlot, 1);
		// dispatch_2(func, car(entry), cdr(entry))
		getLocal(ctx, funcSlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		castConsGet(ctx, 0); // key
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		castConsGet(ctx, 1); // value
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
		ctx.writer.write(Instruction.DROP);
		advanceCursor(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block
		// maphash returns nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	// --- helpers -------------------------------------------------------------

	private static int setTemp(WasmLispCompiler.Ctx ctx) {
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
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

	// Compiles the table expression, casts it to the cell and reads its alist head into a
	// fresh temp, returning the temp slot.
	private static int headSlot(LispVal tableExpr, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(tableExpr, ctx);
		castCellGet0(ctx);
		return setTemp(ctx);
	}

	// Assumes a cell (eqref) on the stack; replaces it with its field-0 value.
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

	// if cursor is not a cons, break out by `depth`.
	private static void emitCursorIsConsElseBreak(WasmLispCompiler.Ctx ctx, int curSlot, int depth) {
		getLocal(ctx, curSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, depth);
	}

	// cursor = cdr(cursor)
	private static void advanceCursor(WasmLispCompiler.Ctx ctx, int curSlot) {
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		setLocal(ctx, curSlot);
	}

}
