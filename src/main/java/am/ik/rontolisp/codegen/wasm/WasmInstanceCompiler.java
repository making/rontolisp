package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the instance primitives -- {@code %obj-new}, {@code %obj-ref},
 * {@code %obj-set}, {@code %obj-is}, {@code %obj-tag}, {@code %obj-p} and
 * {@code %obj-slots} -- through which every {@code defstruct}/{@code defclass}/condition
 * instance is built, read, written and type-tested.
 *
 * <p>
 * An instance is a {@code TYPE_INSTANCE} struct: field 0 is the absolute linear address
 * of the layout record {@code WasmInstanceLayouts} baked, field 1 a
 * {@code TYPE_HASH_BUCKETS} array of slot values. Everything is emitted INLINE, so no
 * {@code FUNC_*} index moves and a program without instances stays byte-identical.
 *
 * <p>
 * {@code %obj-is} compares layout ADDRESSES: the table interns exactly one record per tag
 * per compilation, so address equality is tag equality and the test is a single
 * {@code i32.eq}.
 */
final class WasmInstanceCompiler {

	private WasmInstanceCompiler() {
	}

	/** {@code (%obj-new '<tag> v1 ... vn)}. */
	static void compileNew(LispCons cons, WasmLispCompiler.Ctx ctx) {
		requireGate(ctx, LispNames.OBJ_NEW);
		List<LispVal> args = cons.toList();
		String tag = quotedTag(args.get(1), LispNames.OBJ_NEW);
		LispLayout layout = ctx.closRegistry.findLayoutByTag(tag);
		if (layout == null) {
			throw new UnsupportedOperationException(LispNames.OBJ_NEW + ": unknown instance type " + tag);
		}
		int address = layoutAddress(ctx, tag);
		int slotCount = layout.capacity();
		// slots = array.new $buckets (ref.null eq) capacity -- every element nil, so
		// missing trailing values need no store. capacity, not slotCount, for the stores
		// too: a class a change-class can widen reserves the target's slot count up front
		// (so the ONE allocation shape serves both layouts), and a type keeping machinery
		// beside its declared slots (LispLayout.SYNONYM_STREAM's reader closure) is
		// handed
		// that cell as an ordinary trailing argument.
		refNull(ctx);
		i32Const(ctx, layout.capacity());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		int slotsSlot = ctx.allocTemp();
		setLocal(ctx, slotsSlot);
		for (int i = 2; i < args.size(); i++) {
			int index = i - 2;
			if (index < slotCount) {
				getLocal(ctx, slotsSlot);
				castBuckets(ctx);
				i32Const(ctx, index);
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
			}
			else {
				// A surplus argument is still evaluated, left to right, then dropped --
				// the interpreter evaluates every argument before taking slotCount.
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				ctx.writer.write(Instruction.DROP);
			}
		}
		i32Const(ctx, address);
		getLocal(ctx, slotsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(ctx.instanceTypeIndex);
	}

	/** {@code (%obj-ref obj <k>)}. */
	static void compileRef(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			// No instance can exist in this module, so this read is unreachable; the
			// object is still evaluated for effect and the result is nil.
			evaluateForEffectThenNil(args.get(1), ctx);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		pushSlots(ctx);
		i32Const(ctx, literalIndex(args.get(2), LispNames.OBJ_REF));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	/**
	 * {@code (%obj-become obj '<tag>)}: stores the new layout address into field 0, so
	 * the instance IS one of the new type from here on, and yields the instance. The
	 * slots array is untouched -- construction reserved
	 * {@link am.ik.rontolisp.LispLayout#capacity()} elements for exactly this, which is
	 * why field 0 is mutable (see {@code WasmLispCompiler.INSTANCE_TYPE_COUNT}).
	 */
	static void compileBecome(LispCons cons, WasmLispCompiler.Ctx ctx) {
		requireGate(ctx, LispNames.OBJ_BECOME);
		List<LispVal> args = cons.toList();
		int address = layoutAddress(ctx, quotedTag(args.get(2), LispNames.OBJ_BECOME));
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int objSlot = ctx.allocTemp();
		setLocal(ctx, objSlot);
		getLocal(ctx, objSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(ctx.instanceTypeIndex);
		i32Const(ctx, address);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(ctx.instanceTypeIndex);
		ctx.writer.writeUnsignedLeb128(0);
		getLocal(ctx, objSlot);
	}

	/** {@code (%obj-set obj <k> v)}, returning the value written. */
	static void compileSet(LispCons cons, WasmLispCompiler.Ctx ctx) {
		requireGate(ctx, LispNames.OBJ_SET);
		List<LispVal> args = cons.toList();
		// The object is evaluated before the value, as in the interpreter.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int objSlot = ctx.allocTemp();
		setLocal(ctx, objSlot);
		WasmExprCompiler.compileExpr(args.get(3), ctx);
		int valSlot = ctx.allocTemp();
		setLocal(ctx, valSlot);
		getLocal(ctx, objSlot);
		pushSlots(ctx);
		i32Const(ctx, literalIndex(args.get(2), LispNames.OBJ_SET));
		getLocal(ctx, valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(ctx, valSlot);
	}

	/** {@code (%obj-is obj '<tag1> '<tag2> ...)}. */
	static void compileIs(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int objSlot = ctx.allocTemp();
		setLocal(ctx, objSlot);
		getLocal(ctx, objSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(ctx.instanceTypeIndex);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		boolean any = false;
		for (int i = 2; i < args.size(); i++) {
			Integer address = ctx.layoutAddresses.get(quotedTag(args.get(i), LispNames.OBJ_IS));
			if (address == null) {
				// A tag no registered layout carries can never match; the interpreter
				// answers nil for it too.
				continue;
			}
			getLocal(ctx, objSlot);
			pushLayoutAddress(ctx);
			i32Const(ctx, address);
			ctx.writer.write(Instruction.I32_EQ);
			if (any) {
				ctx.writer.write(Instruction.I32_OR);
			}
			any = true;
		}
		if (!any) {
			i32Const(ctx, 0);
		}
		ctx.writer.write(Instruction.ELSE);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.END);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/** {@code (%obj-tag obj)}: the tag symbol, or nil for a non-instance. */
	static void compileTag(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int objSlot = ctx.allocTemp();
		setLocal(ctx, objSlot);
		getLocal(ctx, objSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(ctx.instanceTypeIndex);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// _str_build (NOT _str_fresh): the tag text was interned by
		// WasmInstanceLayouts, so the symbol's id is the interned offset and two reads
		// of the same tag stay eq, exactly like a quoted symbol literal.
		getLocal(ctx, objSlot);
		pushLayoutAddress(ctx);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, WasmInstanceLayouts.OFF_TAG_OFF);
		getLocal(ctx, objSlot);
		pushLayoutAddress(ctx);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, WasmInstanceLayouts.OFF_TAG_LEN);
		WasmEmitHelper.emitStrBuildCall(ctx.writer);
		ctx.writer.write(Instruction.ELSE);
		refNull(ctx);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * {@code (%obj-slots obj)}: a FRESH list of the slot values in layout order, nil for
	 * a non-instance. Built back to front so each cons closes as it is made -- one loop,
	 * no tail pointer. Every local here is an {@code eqref} (the only local type the
	 * emitted functions declare), so the cursor rides as an i31.
	 */
	static void compileSlots(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int objSlot = ctx.allocTemp();
		setLocal(ctx, objSlot);
		int listSlot = ctx.allocTemp();
		int idxSlot = ctx.allocTemp();
		getLocal(ctx, objSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(ctx.instanceTypeIndex);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		refNull(ctx);
		setLocal(ctx, listSlot);
		// The cursor starts at the LAYOUT's slot count, not at array.len: a
		// change-class-reserved slots array is longer than the layout describes.
		getLocal(ctx, objSlot);
		pushLayoutAddress(ctx);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, WasmInstanceLayouts.OFF_SLOT_COUNT);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_SUB);
		refI31(ctx);
		setLocal(ctx, idxSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		getIndex(ctx, idxSlot);
		i32Const(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		getLocal(ctx, objSlot);
		pushSlots(ctx);
		getIndex(ctx, idxSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(ctx, listSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		setLocal(ctx, listSlot);
		getIndex(ctx, idxSlot);
		i32Const(ctx, 1);
		ctx.writer.write(Instruction.I32_SUB);
		refI31(ctx);
		setLocal(ctx, idxSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block
		getLocal(ctx, listSlot);
		ctx.writer.write(Instruction.ELSE);
		refNull(ctx);
		ctx.writer.write(Instruction.END);
	}

	// Pushes the i32 value of the i31-boxed cursor in the given local.
	private static void getIndex(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	private static void refI31(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/** {@code (%obj-p x)}. */
	static void compileP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(ctx.instanceTypeIndex);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// --- helpers -------------------------------------------------------------

	// The gate says whether an instance can EXIST in this module. Only construction
	// needs it to be on; the four reading primitives answer nil without it, which is
	// exactly right (there is nothing to read) and keeps an instance-free module
	// byte-identical to a build that never knew about instances. A %obj-new that gets
	// here with the gate off is a gate/expansion disagreement -- it would emit a
	// negative heap type, which validates as garbage rather than failing loudly.
	private static void requireGate(WasmLispCompiler.Ctx ctx, String name) {
		if (ctx.instanceTypeIndex < 0) {
			throw new UnsupportedOperationException(name + " reached the compiler with no instance type emitted");
		}
	}

	private static boolean gateOff(WasmLispCompiler.Ctx ctx) {
		return ctx.instanceTypeIndex < 0;
	}

	/** Compiles the operand for its side effects and leaves nil on the stack. */
	private static void evaluateForEffectThenNil(LispVal operand, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(operand, ctx);
		ctx.writer.write(Instruction.DROP);
		refNull(ctx);
	}

	private static int layoutAddress(WasmLispCompiler.Ctx ctx, String tag) {
		Integer address = ctx.layoutAddresses.get(tag);
		if (address == null) {
			throw new UnsupportedOperationException("no layout was baked for instance type " + tag);
		}
		return address;
	}

	/**
	 * The instance tag named by a literal quoted symbol, verbatim: the prefix is the
	 * lowercase {@code %struct-}/{@code %class-} the expander synthesizes, which the
	 * upcasing reader cannot produce, so nothing is folded or package-stripped.
	 */
	private static String quotedTag(LispVal form, String name) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol tag) {
			return tag.name();
		}
		throw new UnsupportedOperationException(name + " expects a literal quoted instance tag, got " + form.print());
	}

	private static int literalIndex(LispVal form, String name) {
		if (form instanceof LispInteger i) {
			return (int) i.value();
		}
		throw new UnsupportedOperationException(name + " expects a literal integer slot index, got " + form.print());
	}

	// Consumes an instance on the stack, pushes its slots array (cast to $buckets).
	private static void pushSlots(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(ctx.instanceTypeIndex);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(ctx.instanceTypeIndex);
		ctx.writer.writeUnsignedLeb128(1);
		castBuckets(ctx);
	}

	// Consumes an instance on the stack, pushes its i32 layout address.
	private static void pushLayoutAddress(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(ctx.instanceTypeIndex);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(ctx.instanceTypeIndex);
		ctx.writer.writeUnsignedLeb128(0);
	}

	private static void castBuckets(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
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

}
