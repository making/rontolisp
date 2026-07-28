package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the instance primitives -- {@code %obj-new}, {@code %obj-ref},
 * {@code %obj-set}, {@code %obj-is}, {@code %obj-tag}, {@code %obj-p} and
 * {@code %obj-slots} -- through which every {@code defstruct}/{@code defclass}/condition
 * instance is built, read, written and type-tested.
 *
 * <p>
 * An instance is {@code Object[]{ String[] layout, v1, ..., vn }}. The {@code String[]}
 * in slot 0 is both the layout (<code>{tag, printName, "S"|"C", slot0, ...}</code>,
 * interned once per tag by {@link JvmLispCompiler.LayoutPool}) and the type
 * discriminator: no other value this backend produces has a {@code String[]} there -- a
 * cons is {@code Object[2]} of Lisp values, a function value has an {@code Integer} in
 * slot 0, a ratio is {@code BigInteger[]}, a character is {@code int[]}, and the
 * {@code java:} bridge turns every host array into a list before it becomes a Lisp value.
 */
final class JvmObjCompiler {

	private JvmObjCompiler() {
	}

	/**
	 * Reads a literal quoted instance tag out of the AST. Verbatim: the tag prefix is the
	 * lowercase {@code %struct-}/{@code %class-} synthesized by the expander, which the
	 * upcasing reader can never produce, so no case folding or package stripping applies.
	 */
	private static String literalTag(LispVal form) {
		if (form instanceof LispCons c && c.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& c.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol tag) {
			return tag.name();
		}
		throw new UnsupportedOperationException(
				"an instance tag must be a literal quoted symbol on the compile path, got " + form.print());
	}

	private static int literalIndex(LispVal form) {
		if (form instanceof LispInteger i) {
			return (int) i.value();
		}
		throw new UnsupportedOperationException(
				"an instance slot index must be a literal integer on the compile path, got " + form.print());
	}

	// The gate says whether an instance can EXIST in this class. Only construction needs
	// it on; the four reading primitives answer nil without it (there is nothing to
	// read), which is also what keeps an instance-free class free of their code. A
	// %obj-new here with the gate off is a gate/expansion disagreement -- and a silent
	// one, because consp/listp would then NOT exclude the instance it builds.
	private static void requireGate(JvmLispCompiler.Ctx ctx, String name) {
		if (!ctx.mayUseInstances) {
			throw new UnsupportedOperationException(name + " reached the compiler with no instance representation");
		}
	}

	private static boolean gateOff(JvmLispCompiler.Ctx ctx) {
		return !ctx.mayUseInstances;
	}

	/** Compiles the operand for its side effects and leaves nil on the stack. */
	private static void evaluateForEffectThenNil(LispVal operand, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(operand, ctx, className);
		ctx.emit(Opcode.POP);
		ctx.emit(Opcode.ACONST_NULL);
	}

	private static LispLayout requireLayout(JvmLispCompiler.Ctx ctx, String tag) {
		LispLayout layout = ctx.closRegistry.findLayoutByTag(tag);
		if (layout == null) {
			throw new UnsupportedOperationException("unknown instance type " + tag);
		}
		return layout;
	}

	/** {@code (%obj-new '<tag> v1 ... vn)}. */
	static void compileNew(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		requireGate(ctx, LispNames.OBJ_NEW);
		List<LispVal> args = cons.toList();
		LispLayout layout = requireLayout(ctx, literalTag(args.get(1)));
		FieldrefConstant lf = ctx.layoutPool.intern(ctx.cp, className, layout);
		int slots = layout.slotCount();
		// capacity, not slotCount: an instance IS its Object[] here, so a change-class
		// into a wider class of the same chain can only keep the object identity if the
		// room was reserved at construction (LispLayout.capacity). The surplus cells stay
		// null (= nil) until %obj-become hands them to the wider layout.
		JvmEmitHelper.emitIntConst(ctx, 1 + layout.capacity());
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(lf.index());
		ctx.emit(Opcode.AASTORE);
		for (int i = 0; i < slots; i++) {
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, 1 + i);
			if (2 + i < args.size()) {
				JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			}
			else {
				ctx.emit(Opcode.ACONST_NULL);
			}
			ctx.emit(Opcode.AASTORE);
		}
		// Surplus arguments are still evaluated (for effect) and dropped, matching the
		// interpreter, which evaluates every argument before taking the first slotCount.
		for (int i = 2 + slots; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.emit(Opcode.POP);
		}
	}

	/** {@code (%obj-ref obj <k>)}. */
	static void compileRef(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			// No instance can exist here, so this read is unreachable; the object is
			// still evaluated for effect and the result is nil.
			evaluateForEffectThenNil(args.get(1), ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		JvmEmitHelper.emitIntConst(ctx, 1 + literalIndex(args.get(2)));
		ctx.emit(Opcode.AALOAD);
	}

	/**
	 * {@code (%obj-become obj '<tag>)}: swaps the layout constant in slot 0, so the
	 * instance IS one of the new type from here on, and yields the instance. The slot
	 * storage is untouched -- construction reserved
	 * {@link am.ik.rontolisp.LispLayout#capacity()} cells for exactly this.
	 */
	static void compileBecome(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		requireGate(ctx, LispNames.OBJ_BECOME);
		List<LispVal> args = cons.toList();
		LispLayout layout = requireLayout(ctx, literalTag(args.get(2)));
		FieldrefConstant lf = ctx.layoutPool.intern(ctx.cp, className, layout);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(lf.index());
		ctx.emit(Opcode.AASTORE);
	}

	/** {@code (%obj-set obj <k> v)}, returning the value written. */
	static void compileSet(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		requireGate(ctx, LispNames.OBJ_SET);
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		JvmEmitHelper.emitIntConst(ctx, 1 + literalIndex(args.get(2)));
		JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		// [arr, idx, v] -> [v, arr, idx, v]: keeps left-to-right evaluation without a
		// temp, so the object is evaluated before the value as in the interpreter.
		ctx.emit(Opcode.DUP_X2);
		ctx.emit(Opcode.AASTORE);
	}

	/**
	 * Emits the shared instance guard over the value already stored in {@code objSlot}:
	 * it must be an {@code Object[]}, non-empty, with a {@code String[]} in slot 0. The
	 * header is left in {@code hdrSlot} and the operand stack empty; every escape branch
	 * position is appended to {@code toFalse}.
	 */
	private static void emitInstanceGuard(JvmLispCompiler.Ctx ctx, int objSlot, int hdrSlot, List<Integer> toFalse) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		toFalse.add(ctx.code.size());
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ARRAYLENGTH);
		toFalse.add(ctx.code.size());
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(hdrSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(hdrSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.layoutPool.stringArrayClass(ctx.cp).index());
		toFalse.add(ctx.code.size());
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
	}

	/** {@code (%obj-is obj '<tag1> '<tag2> ...)}. */
	static void compileIs(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int objSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(objSlot);
		int hdrSlot = ctx.allocTemp();
		List<Integer> toFalse = new ArrayList<>();
		List<Integer> toTrue = new ArrayList<>();
		emitInstanceGuard(ctx, objSlot, hdrSlot, toFalse);
		for (int i = 2; i < args.size(); i++) {
			// Compares the tag TEXT, not layout-array identity: an instance may be built
			// by the runtime reader or the embedded eval as well as by %obj-new here.
			ctx.emit(Opcode.ALOAD);
			ctx.emit(hdrSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.layoutPool.stringArrayClass(ctx.cp).index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
			JvmEmitHelper.compileStringLiteral(literalTag(args.get(i)), ctx);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(ctx.objectEquals.index());
			toTrue.add(ctx.code.size());
			ctx.emit(Opcode.IFNE);
			ctx.emitU2(0);
		}
		int gotoFalsePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		for (int p : toTrue) {
			JvmEmitHelper.patchBranch(ctx, p, ctx.code.size());
		}
		JvmEmitHelper.compileTrue(ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, gotoFalsePos, ctx.code.size());
		for (int p : toFalse) {
			JvmEmitHelper.patchBranch(ctx, p, ctx.code.size());
		}
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	/**
	 * {@code (%obj-slots obj)}: a FRESH list of the slot values in layout order, nil for
	 * a non-instance. Built back to front so each cons can be closed as it is made, which
	 * keeps the whole thing one loop with no tail pointer.
	 */
	static void compileSlots(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int objSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(objSlot);
		int hdrSlot = ctx.allocTemp();
		List<Integer> toFalse = new ArrayList<>();
		emitInstanceGuard(ctx, objSlot, hdrSlot, toFalse);
		// list = null; for (i = layout.length - 3; i >= 1; i--) list = new
		// Object[]{obj[i], list}. The cursor stops at 1, not 0: slot 0 of the instance
		// array is the layout, not a slot value. It starts at the LAYOUT's slot count
		// (its String[] is {tag, printName, kind, slot...}), not at the array length,
		// because a change-class-reserved array is longer than the layout describes.
		int listSlot = ctx.allocTemp();
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);
		int idxSlot = ctx.allocTemp();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(hdrSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.layoutPool.stringArrayClass(ctx.cp).index());
		ctx.emit(Opcode.ARRAYLENGTH);
		ctx.emit(Opcode.ICONST_3);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(idxSlot);
		int loopTop = ctx.code.size();
		ctx.emit(Opcode.ILOAD);
		ctx.emit(idxSlot);
		int exitLoop = ctx.code.size();
		ctx.emit(Opcode.IFLE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ILOAD);
		ctx.emit(idxSlot);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);
		ctx.emit(Opcode.IINC);
		ctx.emit(idxSlot);
		ctx.emit(0xff);
		int gotoTop = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, gotoTop, loopTop);
		JvmEmitHelper.patchBranch(ctx, exitLoop, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		for (int p : toFalse) {
			JvmEmitHelper.patchBranch(ctx, p, ctx.code.size());
		}
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	/** {@code (%obj-tag obj)}: the tag symbol, or nil for a non-instance. */
	static void compileTag(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileGuarded(cons, ctx, className, true);
	}

	/** {@code (%obj-p obj)}. */
	static void compileP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileGuarded(cons, ctx, className, false);
	}

	/**
	 * The shared body of {@code %obj-tag} and {@code %obj-p}: guard, then either read the
	 * tag string out of the header (a symbol IS a bare String on this backend) or answer
	 * {@code t}.
	 */
	private static void compileGuarded(LispCons cons, JvmLispCompiler.Ctx ctx, String className, boolean readTag) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int objSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(objSlot);
		int hdrSlot = ctx.allocTemp();
		List<Integer> toFalse = new ArrayList<>();
		emitInstanceGuard(ctx, objSlot, hdrSlot, toFalse);
		if (readTag) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(hdrSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.layoutPool.stringArrayClass(ctx.cp).index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		else {
			JvmEmitHelper.compileTrue(ctx);
		}
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		for (int p : toFalse) {
			JvmEmitHelper.patchBranch(ctx, p, ctx.code.size());
		}
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
