package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the hash-table built-ins. A hash table is a {@code TYPE_CELL} struct (a
 * mutable single-field box, never otherwise exposed as a Lisp value) whose field holds a
 * <em>header</em> {@code TYPE_CONS} of {@code (count . buckets)}: {@code count} is an i31
 * integer of live entries (so {@code hash-table-count} is O(1)) and {@code buckets} is a
 * {@code TYPE_HASH_BUCKETS} array (open-chaining hash table). Each bucket slot holds an
 * association list of {@code (key . value)} entries or nil. A key's bucket is
 * {@code (_hash(key) & 0x7fffffff) % capacity}; {@code _hash}
 * ({@link WasmLispCompiler#FUNC_HASH}) agrees with the structural {@code _equal} runtime
 * ({@link WasmLispCompiler#FUNC_EQUAL}) used to compare keys within a bucket, so equal
 * keys land in the same bucket. The table grows (doubling, via
 * {@link WasmLispCompiler#FUNC_HASH_RESIZE}) once the load factor exceeds 0.75, keeping
 * {@code gethash}/{@code puthash}/{@code remhash} amortized O(1) -- unlike the previous
 * O(n) single-alist representation.
 *
 * <p>
 * In a module that writes {@code (make-hash-table :test 'equalp)} anywhere
 * ({@code Ctx.usesEqualpHashTables}), that {@code count} is TAGGED --
 * {@code entries * 2 + fold} -- so a table carries its own test without a second field
 * and without disturbing {@code hash-table-p}, whose discrimination is that the header
 * car is an i31 at all. Every count read then shifts past the flag, and
 * {@code gethash}/{@code puthash}/{@code remhash} run the key through
 * {@link WasmLispCompiler#FUNC_EQUALP_KEY} when it is set ({@code .kb/hash-tables.md}).
 * In every other module the count is the plain entry count and no site emits a fold.
 */
final class WasmHashTableCompiler {

	// Initial bucket count for a fresh (or cleared) table.
	private static final int INITIAL_CAP = 8;

	private WasmHashTableCompiler() {
	}

	static void compileMake(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// The arguments are read from the SOURCE, never evaluated: :test 'equalp sets the
		// header's fold flag so the table's keys are folded before they are placed, and
		// every other keyword (:size and friends) is accepted and ignored. Result: a cell
		// holding a fresh (count . empty-buckets) header.
		emitNewHeader(ctx, ctx.usesEqualpHashTables && LispMacroExpander.isEqualpHashTableMake(cons));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	/**
	 * Compiles {@code hash-table-test} to the test the table actually implements:
	 * {@code equalp} when it folds its keys, {@code equal} otherwise -- an {@code eql}
	 * table still places structurally ({@code .todo/012}). A module that can build no
	 * folding table answers the constant, which is then the only true answer.
	 * @param cons the accessor expression
	 * @param ctx the compilation context
	 */
	static void compileTest(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.usesEqualpHashTables) {
			WasmExprCompiler.compileExpr(LispMacroExpander.expandHashTableTest(cons), ctx);
			return;
		}
		List<LispVal> args = cons.toList();
		int headerSlot = headerSlot(args.get(1), ctx);
		emitFoldFlag(ctx, headerSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQUALP), ctx);
		ctx.writer.write(Instruction.ELSE);
		WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQUAL), ctx);
		ctx.writer.write(Instruction.END);
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
		// header (count . buckets)
		int headerSlot = headerSlot(args.get(2), ctx);
		emitFoldKey(ctx, headerSlot, keySlot);
		// cur = the bucket alist head for key
		pushBucketHead(ctx, headerSlot, keySlot);
		int curSlot = setTemp(ctx);

		// block $result (eqref) / block $notfound / loop
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		emitCursorIsConsElseBreak(ctx, curSlot, 1); // not a cons -> $notfound

		// equal(key, car(car(cur)))?
		getLocal(ctx, keySlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		castConsGet(ctx, 0); // car(entry) = key
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
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
		int headerSlot = headerSlot(args.get(2), ctx);
		emitFoldKey(ctx, headerSlot, keySlot);
		// idx = bucket index for key, boxed as i31 so it survives the find loop
		pushBucketIndex(ctx, headerSlot, keySlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		int idxSlot = setTemp(ctx);
		// cur = buckets[idx]
		getHeaderArr(ctx, headerSlot);
		getIndex(ctx, idxSlot);
		arrayGet(ctx);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		ctx.writer.write(Instruction.IF, 0x40);
		// match: rplacd(entry, value)
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1);
		// depth 3: $if(0) $loop(1) $notfound(2) $done(3)
		ctx.writer.write(Instruction.BR, 3); // -> $done
		ctx.writer.write(Instruction.END); // end if

		advanceCursor(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end $notfound
		// not found: buckets[idx] = cons(cons(key, value), buckets[idx])
		getHeaderArr(ctx, headerSlot); // array
		getIndex(ctx, idxSlot); // index
		// new entry = cons(key, value)
		getLocal(ctx, keySlot);
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		// cons(newentry, oldhead)
		getHeaderArr(ctx, headerSlot);
		getIndex(ctx, idxSlot);
		arrayGet(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		arraySet(ctx);
		// count = count + 1
		addToCount(ctx, headerSlot, 1);
		// grow when load factor (count / capacity) exceeds 0.75: count*4 >= capacity*3
		getCount(ctx, headerSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(4);
		ctx.writer.write(Instruction.I32_MUL);
		getHeaderArr(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(3);
		ctx.writer.write(Instruction.I32_MUL);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, headerSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH_RESIZE);
		ctx.writer.write(Instruction.END); // end resize if
		ctx.writer.write(Instruction.END); // end $done
		// return value
		getLocal(ctx, valSlot);
	}

	static void compileRem(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int keySlot = setTemp(ctx);
		int headerSlot = headerSlot(args.get(2), ctx);
		emitFoldKey(ctx, headerSlot, keySlot);
		// idx (boxed i31)
		pushBucketIndex(ctx, headerSlot, keySlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		int idxSlot = setTemp(ctx);
		// cur = buckets[idx]
		getHeaderArr(ctx, headerSlot);
		getIndex(ctx, idxSlot);
		arrayGet(ctx);
		int curSlot = setTemp(ctx);
		// prev = nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		int prevSlot = setTemp(ctx);

		ctx.writer.write(Instruction.BLOCK); // $result (eqref)
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.BLOCK, 0x40); // $notfound
		ctx.writer.write(Instruction.LOOP, 0x40);

		emitCursorIsConsElseBreak(ctx, curSlot, 1);

		getLocal(ctx, keySlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0);
		castConsGet(ctx, 0);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		ctx.writer.write(Instruction.IF, 0x40);
		// remove cur: if prev is nil, buckets[idx] = cdr(cur); else rplacd(prev,
		// cdr(cur))
		getLocal(ctx, prevSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		getHeaderArr(ctx, headerSlot);
		getIndex(ctx, idxSlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		arraySet(ctx);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, prevSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.END); // end inner if
		// count = count - 1
		addToCount(ctx, headerSlot, -1);
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
		// The emptied table keeps its TEST: the fresh header's count carries the old
		// header's fold flag, and nothing else of it.
		emitClearedHeader(ctx, cellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeUnsignedLeb128(0);
		getLocal(ctx, cellSlot);
	}

	static void compileCount(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// O(1): the live-entry count is the car of the header cons (an i31 integer),
		// shifted past the fold flag in a module that carries one.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		castCellGet0(ctx); // header cons
		castConsGet(ctx, 0); // count i31
		if (ctx.usesEqualpHashTables) {
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.I32_SHR_S);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
	}

	static void compileP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// General arrays share the TYPE_CELL box (see WasmArraypCompiler): a hash
		// table's header car is its i31 entry count, an array's is its dims array --
		// mirror %arrayp's discrimination with the car test inverted.
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valueSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		int innerSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		// inner = cell.get(value, 0)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(innerSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(innerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		// header car is an i31 entry count (a hash table), not a dims array?
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(innerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(am.ik.wasm.Type.I31.code());
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

	static void compileMaphash(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(2);
		// maphash always dispatches (it has no direct-call route), so the registry gate
		// reads the designator itself -- see Ctx.runtimeDesignatorDispatch.
		if (!ctx.injectedRuntimeBody && !LispMacroExpander.isStaticFunctionDesignator(args.get(1))) {
			ctx.runtimeDesignatorDispatch[0] = true;
		}
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + 2;

		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = setTemp(ctx);
		int headerSlot = headerSlot(args.get(2), ctx);
		// i = 0 (boxed i31 bucket index)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		int iSlot = setTemp(ctx);
		int curSlot = ctx.allocTemp();

		// outer loop over buckets
		ctx.writer.write(Instruction.BLOCK, 0x40); // $outer
		ctx.writer.write(Instruction.LOOP, 0x40); // $o
		// if i >= capacity break $outer
		getIndex(ctx, iSlot);
		getHeaderArr(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		// cur = buckets[i]
		getHeaderArr(ctx, headerSlot);
		getIndex(ctx, iSlot);
		arrayGet(ctx);
		setLocal(ctx, curSlot);
		// inner loop over the bucket alist
		ctx.writer.write(Instruction.BLOCK, 0x40); // $inner
		ctx.writer.write(Instruction.LOOP, 0x40); // $in
		emitCursorIsConsElseBreak(ctx, curSlot, 1); // -> $inner
		// dispatch_2(func, car(entry), cdr(entry))
		getLocal(ctx, funcSlot);
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		castConsGet(ctx, 0); // key
		getLocal(ctx, curSlot);
		castConsGet(ctx, 0); // entry
		castConsGet(ctx, 1); // value
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(dispatchFuncIdx);
		ctx.writer.write(Instruction.DROP);
		advanceCursor(ctx, curSlot);
		ctx.writer.write(Instruction.BR, 0); // loop $in
		ctx.writer.write(Instruction.END); // end loop $in
		ctx.writer.write(Instruction.END); // end $inner
		// i = i + 1
		getIndex(ctx, iSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, iSlot);
		ctx.writer.write(Instruction.BR, 0); // loop $o
		ctx.writer.write(Instruction.END); // end loop $o
		ctx.writer.write(Instruction.END); // end $outer
		// maphash returns nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	// --- helpers -------------------------------------------------------------

	// (quote NAME): the answer hash-table-test hands back is a SYMBOL, so it is compiled
	// as quoted data rather than as a variable reference to it.
	private static LispVal quotedSymbol(String name) {
		return new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(name), am.ik.rontolisp.LispNil.INSTANCE));
	}

	private static int setTemp(WasmLispCompiler.Ctx ctx) {
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
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

	// Compiles the table expression and reads its (count . buckets) header cons into a
	// fresh temp, returning the temp slot.
	private static int headerSlot(LispVal tableExpr, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(tableExpr, ctx);
		castCellGet0(ctx);
		return setTemp(ctx);
	}

	// Emits a fresh header cons (count, empty buckets array) onto the stack. In a module
	// that folds, the count is TAGGED -- entries * 2 + the fold flag -- which keeps the
	// header car an i31 and so leaves hash-table-p's discrimination (an i31 count here, a
	// dims array for a general array sharing the TYPE_CELL box) exactly as it was.
	private static void emitNewHeader(WasmLispCompiler.Ctx ctx, boolean equalp) {
		// count = i31(0), or i31(1) for an empty table that folds
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(equalp ? 1 : 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		// buckets = array.new buckets (null, INITIAL_CAP)
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(INITIAL_CAP);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		// header = cons(count, buckets)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	// Emits the header a cleared table gets: zero entries, fresh buckets, and the fold
	// flag the cell's current header carries.
	private static void emitClearedHeader(WasmLispCompiler.Ctx ctx, int cellSlot) {
		if (!ctx.usesEqualpHashTables) {
			emitNewHeader(ctx, false);
			return;
		}
		getLocal(ctx, cellSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		// buckets = array.new buckets (null, INITIAL_CAP)
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(INITIAL_CAP);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	// Pushes the header's fold flag (the low bit of the tagged count) as an i32.
	private static void emitFoldFlag(WasmLispCompiler.Ctx ctx, int headerSlot) {
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_AND);
	}

	// Replaces the key in keySlot with its equalp fold when the table folds. Not one
	// instruction in a module that writes no equalp table.
	private static void emitFoldKey(WasmLispCompiler.Ctx ctx, int headerSlot, int keySlot) {
		if (!ctx.usesEqualpHashTables) {
			return;
		}
		emitFoldFlag(ctx, headerSlot);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, keySlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUALP_KEY);
		setLocal(ctx, keySlot);
		ctx.writer.write(Instruction.END);
	}

	// Pushes the header's bucket array (cast to TYPE_HASH_BUCKETS) onto the stack.
	private static void getHeaderArr(WasmLispCompiler.Ctx ctx, int headerSlot) {
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 1); // cdr(header) = buckets (ref null eq)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// Pushes the i32 bucket index for key (in keySlot) onto the stack.
	private static void pushBucketIndex(WasmLispCompiler.Ctx ctx, int headerSlot, int keySlot) {
		getLocal(ctx, keySlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0x7fffffff);
		ctx.writer.write(Instruction.I32_AND);
		getHeaderArr(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_REM_U);
	}

	// Pushes buckets[bucketIndex(key)] (the bucket alist head) onto the stack.
	private static void pushBucketHead(WasmLispCompiler.Ctx ctx, int headerSlot, int keySlot) {
		getHeaderArr(ctx, headerSlot);
		pushBucketIndex(ctx, headerSlot, keySlot);
		arrayGet(ctx);
	}

	// Unboxes the i31 in idxSlot to an i32 on the stack.
	private static void getIndex(WasmLispCompiler.Ctx ctx, int idxSlot) {
		getLocal(ctx, idxSlot);
		WasmEmitHelper.castI31GetS(ctx);
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

	// Pushes the header's live-entry count as an i32 on the stack, past the fold flag in
	// a module that carries one.
	private static void getCount(WasmLispCompiler.Ctx ctx, int headerSlot) {
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0); // count i31
		WasmEmitHelper.castI31GetS(ctx);
		if (ctx.usesEqualpHashTables) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.I32_SHR_S);
		}
	}

	// header.count += delta (delta is +1 or -1) -- one ENTRY, so the tagged count moves
	// by two and the fold flag under it is untouched.
	private static void addToCount(WasmLispCompiler.Ctx ctx, int headerSlot, int delta) {
		getLocal(ctx, headerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.usesEqualpHashTables ? delta * 2 : delta);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0);
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
