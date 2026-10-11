package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the hash-table built-ins. A hash table is a {@code TYPE_CELL} struct (a
 * mutable single-field box, never otherwise exposed as a Lisp value) whose field holds a
 * <em>header</em> {@code TYPE_CONS} of {@code (count . record)}: {@code count} is an i31
 * integer of live entries (so {@code hash-table-count} is O(1)) and {@code record} the
 * hash chains -- a chained table, a persistent trie once copied -- and the insertion
 * order that the runtime functions of {@link WasmHashTableRuntimeBuilder} keep, which
 * every site here calls: {@code _ht_get}, {@code _ht_put}, {@code _ht_rem},
 * {@code _ht_entries} (the snapshot {@code maphash} walks) and {@code _ht_copy}
 * ({@code rontolisp::%copy-equal-table}, O(1) for an {@code equal} table once it has been
 * copied). A key's chain is placed by {@code _hash} ({@link WasmLispCompiler#FUNC_HASH}),
 * which agrees with the structural {@code _equal} ({@link WasmLispCompiler#FUNC_EQUAL})
 * the chain is scanned with, so equal keys share a chain.
 *
 * <p>
 * In a module that writes a non-equal {@code :test} anywhere ({@link #tagged tagged}),
 * that {@code count} is TAGGED -- {@code entries * 4 + test} (0 equal, 1 equalp, 2 eql, 3
 * eq) -- so a table carries its own test without a second field and without disturbing
 * {@code hash-table-p}, whose discrimination is that the header car is an i31 at all.
 * Every count read then shifts past the tag; the runtime functions run an equalp key
 * through {@link WasmLispCompiler#FUNC_EQUALP_KEY} when the tag says so, compare an
 * eql/eq key with the eql/eq comparison, and place an eql/eq key by
 * {@link WasmLispCompiler#FUNC_IHASH}: the identity-hash slot every cons, cell and
 * instance of such a module carries ({@link WasmIdentityHashRuntimeBuilder}), stable
 * under slot mutation, which a structural hash is not ({@code .kb/hash-tables.md}). In
 * every other module the count is the plain entry count and no object carries the slot.
 */
final class WasmHashTableCompiler {

	private WasmHashTableCompiler() {
	}

	static void compileMake(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// The arguments are read from the SOURCE, never evaluated: a literal :test
		// tags the header count with the test the table's lookups implement, and
		// every other keyword (:size and friends) is accepted and ignored. Result: a
		// cell holding a fresh (count . empty-record) header.
		emitNewHeader(ctx, tagged(ctx) ? LispMacroExpander.hashTableTestCode(cons) : LispHashTable.TEST_EQUAL);
		WasmEmitHelper.emitNewCell(ctx);
	}

	/**
	 * Whether any count in this module carries the two-bit test tag: the program writes a
	 * non-equal {@code :test} somewhere. Every count read, write and print agrees on the
	 * one answer.
	 * @param ctx the compilation context
	 * @return whether counts are tagged
	 */
	static boolean tagged(WasmLispCompiler.Ctx ctx) {
		return ctx.usesEqualpHashTables || ctx.usesIdentityHashTables;
	}

	/**
	 * Compiles {@code hash-table-test} to the test the table actually implements. A
	 * module that can build no non-equal table answers the constant, which is then the
	 * only true answer.
	 * @param cons the accessor expression
	 * @param ctx the compilation context
	 */
	static void compileTest(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!tagged(ctx)) {
			compileTableThenConstant(LispMacroExpander.expandHashTableTest(cons), ctx);
			return;
		}
		List<LispVal> args = cons.toList();
		int headerSlot = headerSlot(args.get(1), ctx);
		int tagSlot = emitTestTag(ctx, headerSlot);
		if (!ctx.usesIdentityHashTables) {
			getTag(ctx, tagSlot);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(LispHashTable.TEST_EQUALP);
			ctx.writer.write(Instruction.I32_EQ);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQUALP), ctx);
			ctx.writer.write(Instruction.ELSE);
			WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQUAL), ctx);
			ctx.writer.write(Instruction.END);
			return;
		}
		getTag(ctx, tagSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(LispHashTable.TEST_EQUALP);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQUALP), ctx);
		ctx.writer.write(Instruction.ELSE);
		getTag(ctx, tagSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(LispHashTable.TEST_EQL);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQL), ctx);
		ctx.writer.write(Instruction.ELSE);
		getTag(ctx, tagSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(LispHashTable.TEST_EQ);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQ_GENERAL), ctx);
		ctx.writer.write(Instruction.ELSE);
		WasmExprCompiler.compileExpr(quotedSymbol(LispNames.EQUAL), ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	static void compileGet(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// key -> keySlot
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int keySlot = setTemp(ctx);
		// The operands run key, table, default (CLHS 3.1.2.1.2.3), and a table that is no
		// table signals after the default ran. A site whose order cannot be seen keeps
		// the default ahead of the table.
		boolean tableFirst = args.size() > 3 && !isOrderFreeAfterTable(args.get(3), args.get(2));
		int tableSlot = -1;
		if (tableFirst) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			tableSlot = setTemp(ctx);
		}
		// default -> dfltSlot
		if (args.size() > 3) {
			WasmExprCompiler.compileExpr(args.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		int dfltSlot = setTemp(ctx);
		int headerSlot = tableFirst ? headerSlotOf(tableSlot, ctx) : headerSlot(args.get(2), ctx);
		getLocal(ctx, headerSlot);
		getLocal(ctx, keySlot);
		getLocal(ctx, dfltSlot);
		call(ctx, WasmLispCompiler.FUNC_HT_BASE + WasmHashTableRuntimeBuilder.GET);
	}

	static void compilePut(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// (%puthash key table value)
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int keySlot = setTemp(ctx);
		// The operands run key, table, value, and a table that is no table signals after
		// the value ran. A site whose order cannot be seen keeps the value ahead of the
		// table.
		int valSlot;
		int headerSlot;
		if (isOrderFreeAfterTable(args.get(3), args.get(2))) {
			WasmExprCompiler.compileExpr(args.get(3), ctx);
			valSlot = setTemp(ctx);
			headerSlot = headerSlot(args.get(2), ctx);
		}
		else {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			int tableSlot = setTemp(ctx);
			WasmExprCompiler.compileExpr(args.get(3), ctx);
			valSlot = setTemp(ctx);
			headerSlot = headerSlotOf(tableSlot, ctx);
		}
		getLocal(ctx, headerSlot);
		getLocal(ctx, keySlot);
		getLocal(ctx, valSlot);
		call(ctx, WasmLispCompiler.FUNC_HT_BASE + WasmHashTableRuntimeBuilder.PUT);
	}

	static void compileRem(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int keySlot = setTemp(ctx);
		int headerSlot = headerSlot(args.get(2), ctx);
		getLocal(ctx, headerSlot);
		getLocal(ctx, keySlot);
		call(ctx, WasmLispCompiler.FUNC_HT_BASE + WasmHashTableRuntimeBuilder.REM);
	}

	/**
	 * Compiles {@code (rontolisp::%copy-equal-table table)}, the copy every Clojure map,
	 * set and record field table takes before a verb changes it: {@code _ht_copy}, which
	 * shares an {@code equal} table's structure in O(1) ({@code .kb/hash-tables.md},
	 * "Copies share structure"). In EH mode anything but a table is its
	 * {@code HASH-TABLE} type-error, as the interpreter's native copy refuses it.
	 * @param cons the call
	 * @param ctx the compile context
	 */
	static void compileCopy(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("%copy-equal-table expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int tableSlot = setTemp(ctx);
		emitTableCheck(ctx, tableSlot);
		getLocal(ctx, tableSlot);
		call(ctx, WasmLispCompiler.FUNC_HT_BASE + WasmHashTableRuntimeBuilder.COPY);
	}

	static void compileClr(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int cellSlot = setTemp(ctx);
		emitTableCheck(ctx, cellSlot);
		getLocal(ctx, cellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		// The emptied table keeps its TEST: the fresh header's count carries the old
		// header's test tag, and nothing else of it.
		emitClearedHeader(ctx, cellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeUnsignedLeb128(0);
		getLocal(ctx, cellSlot);
	}

	static void compileCount(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// O(1): the live-entry count is the car of the header cons (an i31 integer),
		// shifted past the test tag in a module that carries one.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		if (WasmEmitHelper.checksConsFields(ctx)) {
			int tableSlot = setTemp(ctx);
			emitTableCheck(ctx, tableSlot);
			getLocal(ctx, tableSlot);
		}
		castCellGet0(ctx); // header cons
		castConsGet(ctx, 0); // count i31
		if (tagged(ctx)) {
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(2);
			ctx.writer.write(Instruction.I32_SHR_S);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
	}

	static void compileP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valueSlot = setTemp(ctx);
		emitHashTablePI32(ctx, valueSlot);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// Pushes the i32 hash-table-p answers for the value in valueSlot. General arrays
	// share the TYPE_CELL box (see WasmArraypCompiler): a hash table's header car is its
	// i31 entry count, an array's is its dims array -- mirror %arrayp's discrimination
	// with the car test inverted.
	private static void emitHashTablePI32(WasmLispCompiler.Ctx ctx, int valueSlot) {
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
	}

	/**
	 * EH mode only: a value in {@code slot} that is no hash table is the innermost named
	 * operator's {@code HASH-TABLE} type-error -- the accessors below cast it, which
	 * trapped. A module outside EH mode emits nothing.
	 * @param ctx the compile context
	 * @param slot the local holding the table operand
	 */
	private static void emitTableCheck(WasmLispCompiler.Ctx ctx, int slot) {
		if (!WasmEmitHelper.checksConsFields(ctx)) {
			return;
		}
		emitHashTablePI32(ctx, slot);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		WasmOperandTypes.emitTypeError(ctx, slot, OperandTypes.Kind.HASH_TABLE);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Compiles an accessor that answers a constant after evaluating its table,
	 * {@code (progn table constant)}, checking the table in between in EH mode
	 * ({@link #emitTableCheck}).
	 * @param expansion the {@code (progn table constant)} expansion
	 * @param ctx the compile context
	 */
	static void compileTableThenConstant(LispVal expansion, WasmLispCompiler.Ctx ctx) {
		if (!WasmEmitHelper.checksConsFields(ctx)) {
			WasmExprCompiler.compileExpr(expansion, ctx);
			return;
		}
		List<LispVal> forms = ((LispCons) expansion).toList();
		WasmExprCompiler.compileExpr(forms.get(1), ctx);
		emitTableCheck(ctx, setTemp(ctx));
		WasmExprCompiler.compileExpr(forms.get(2), ctx);
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

		WasmExprCompiler.compileExpr(WasmFunctionFormCompiler.designator(args.get(1), ctx), ctx);
		int funcSlot = setTemp(ctx);
		int headerSlot = headerSlot(args.get(2), ctx);
		// The live entries in insertion order, a snapshot: storing into the table or
		// removing the current key from the function changes neither.
		getLocal(ctx, headerSlot);
		call(ctx, WasmLispCompiler.FUNC_HT_ENTRIES);
		int entriesSlot = setTemp(ctx);
		// i = 0 (boxed i31 index)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		int iSlot = setTemp(ctx);
		int entrySlot = ctx.allocTemp();

		ctx.writer.write(Instruction.BLOCK, 0x40); // $done
		ctx.writer.write(Instruction.LOOP, 0x40); // $next
		// if i >= length break $done
		getIndex(ctx, iSlot);
		getLocal(ctx, entriesSlot);
		castEntries(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		// entry = entries[i]
		getLocal(ctx, entriesSlot);
		castEntries(ctx);
		getIndex(ctx, iSlot);
		arrayGet(ctx);
		setLocal(ctx, entrySlot);
		// dispatch_2(func, key, value): the key as first stored -- an equalp entry's
		// seventh slot when its fold differs
		getLocal(ctx, funcSlot);
		if (foldingEntries(ctx)) {
			getLocal(ctx, entrySlot);
			castEntries(ctx);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmHashTableRuntimeBuilder.E_ORIG + 1);
			ctx.writer.write(Instruction.I32_EQ);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			entrySlotGet(ctx, entrySlot, WasmHashTableRuntimeBuilder.E_ORIG);
			ctx.writer.write(Instruction.ELSE);
			entrySlotGet(ctx, entrySlot, WasmHashTableRuntimeBuilder.E_KEY);
			ctx.writer.write(Instruction.END);
		}
		else {
			entrySlotGet(ctx, entrySlot, WasmHashTableRuntimeBuilder.E_KEY);
		}
		entrySlotGet(ctx, entrySlot, WasmHashTableRuntimeBuilder.E_VALUE);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(dispatchFuncIdx);
		ctx.writer.write(Instruction.DROP);
		// i = i + 1
		getIndex(ctx, iSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, iSlot);
		ctx.writer.write(Instruction.BR, 0); // loop $next
		ctx.writer.write(Instruction.END); // end loop $next
		ctx.writer.write(Instruction.END); // end $done
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

	// Calls the runtime function at the given index.
	private static void call(WasmLispCompiler.Ctx ctx, int function) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(function);
	}

	// Compiles the table expression and reads its (count . record) header cons into a
	// fresh temp, returning the temp slot.
	private static int headerSlot(LispVal tableExpr, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(tableExpr, ctx);
		if (WasmEmitHelper.checksConsFields(ctx)) {
			int tableSlot = setTemp(ctx);
			emitTableCheck(ctx, tableSlot);
			getLocal(ctx, tableSlot);
		}
		castCellGet0(ctx);
		return setTemp(ctx);
	}

	// The header of a table already evaluated into tableSlot, checked as a table.
	private static int headerSlotOf(int tableSlot, WasmLispCompiler.Ctx ctx) {
		if (WasmEmitHelper.checksConsFields(ctx)) {
			emitTableCheck(ctx, tableSlot);
		}
		getLocal(ctx, tableSlot);
		castCellGet0(ctx);
		return setTemp(ctx);
	}

	// Whether the table operand may be evaluated after the operand that follows it in
	// the source with no observable difference: that operand is a constant, or both are
	// reads (a read assigns nothing, so neither sees the other).
	private static boolean isOrderFreeAfterTable(LispVal later, LispVal table) {
		return ArgumentOrder.isOrderIndependent(later) || (later instanceof LispSymbol
				&& (table instanceof LispSymbol || ArgumentOrder.isOrderIndependent(table)));
	}

	// Emits a fresh header cons (count, empty record) onto the stack. In a module that
	// tags, the count is TAGGED -- entries * 4 + the two-bit test code -- which keeps the
	// header car an i31 and so leaves hash-table-p's discrimination (an i31 count here,
	// a dims array for a general array sharing the TYPE_CELL box) exactly as it was.
	private static void emitNewHeader(WasmLispCompiler.Ctx ctx, int testCode) {
		// count = i31(testCode) for an empty table (0 entries)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(testCode);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		WasmHashTableRuntimeBuilder.emitNewRecord(ctx.writer);
		WasmEmitHelper.emitNewCons(ctx);
	}

	// Emits the header a cleared table gets: zero entries, a fresh record, and the test
	// tag the cell's current header carries. The old record's nodes stay as they are,
	// for any copy that shares them.
	private static void emitClearedHeader(WasmLispCompiler.Ctx ctx, int cellSlot) {
		if (!tagged(ctx)) {
			emitNewHeader(ctx, LispHashTable.TEST_EQUAL);
			return;
		}
		getLocal(ctx, cellSlot);
		castCellGet0(ctx);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(3);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		WasmHashTableRuntimeBuilder.emitNewRecord(ctx.writer);
		WasmEmitHelper.emitNewCons(ctx);
	}

	// Pushes the header's two-bit test tag (the low two bits of the tagged count),
	// boxed as an i31 (temps hold references) into a fresh temp whose slot is
	// answered. Reads back through getTag.
	private static int emitTestTag(WasmLispCompiler.Ctx ctx, int headerSlot) {
		getLocal(ctx, headerSlot);
		castConsGet(ctx, 0);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(3);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		return setTemp(ctx);
	}

	// Pushes the i32 test tag in tagSlot onto the stack.
	private static void getTag(WasmLispCompiler.Ctx ctx, int tagSlot) {
		getLocal(ctx, tagSlot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	/**
	 * Whether this module's tables can hold FOLDING entries: an {@code equalp} table's
	 * entry whose fold differs from its key keeps the key as first stored in a seventh
	 * slot, which {@code maphash} hands back. A module that makes no {@code equalp} table
	 * emits not one instruction for it.
	 * @param ctx the compilation context
	 * @return whether the program makes an {@code equalp} table
	 */
	private static boolean foldingEntries(WasmLispCompiler.Ctx ctx) {
		return ctx.usesEqualpHashTables;
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

	// Casts the eqref on the stack to the TYPE_HASH_BUCKETS array an entry and the
	// entries snapshot are.
	private static void castEntries(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// Pushes the given slot of the entry in entrySlot.
	private static void entrySlotGet(WasmLispCompiler.Ctx ctx, int entrySlot, int slot) {
		getLocal(ctx, entrySlot);
		castEntries(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot);
		arrayGet(ctx);
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

}
