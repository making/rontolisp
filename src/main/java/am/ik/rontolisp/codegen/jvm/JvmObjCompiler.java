package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the instance primitives -- {@code %obj-new}, {@code %obj-ref},
 * {@code %obj-set}, {@code %obj-is}, {@code %obj-tag}, {@code %obj-p}, {@code %obj-slots}
 * and {@code copy-structure} -- through which every
 * {@code defstruct}/{@code defclass}/condition instance is built, read, written,
 * type-tested and (shallow-)copied.
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
		ctx.body.pop().aconst_null();
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
		FieldRefEntry lf = ctx.layoutPool.intern(ctx.cp, className, layout);
		int slots = layout.capacity();
		// capacity, not slotCount, in BOTH places: an instance IS its Object[] here, so a
		// change-class into a wider class of the same chain can only keep the object
		// identity if the room was reserved at construction (LispLayout.capacity), and a
		// type that keeps machinery beside its declared slots
		// (LispLayout.SYNONYM_STREAM's
		// reader closure) is handed that cell as an ordinary trailing argument. Cells no
		// argument reaches stay null (= nil).
		JvmEmitHelper.emitIntConst(ctx, 1 + layout.capacity());
		ctx.body.anewarray(ctx.objectClass).dup().iconst_0().getstatic(lf);
		ctx.body.aastore();
		for (int i = 0; i < slots; i++) {
			ctx.body.dup();
			JvmEmitHelper.emitIntConst(ctx, 1 + i);
			if (2 + i < args.size()) {
				JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			}
			else {
				ctx.body.aconst_null();
			}
			ctx.body.aastore();
		}
		// Surplus arguments are still evaluated (for effect) and dropped, matching the
		// interpreter, which evaluates every argument before taking the first slotCount.
		for (int i = 2 + slots; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.body.pop();
		}
	}

	/**
	 * Wraps the raw stream HANDLE already on the stack into the OPEN stream value of
	 * {@link LispLayout#STREAM}: {@code Object[]{layout, handle, :kind}}. Emitted at
	 * every stream producer when {@code ctx.usesStreamValues} is on -- which is exactly
	 * when {@code JvmStringStreamCompiler.streamDesignator} emits the matching unwrap, so
	 * the two halves cannot disagree.
	 * @param ctx the compile context (a temporary local is allocated)
	 * @param className the class being emitted
	 * @param kind one of {@link LispLayout.Kinds}
	 */
	static void emitWrapStream(JvmLispCompiler.Ctx ctx, String className, String kind) {
		requireGate(ctx, LispNames.OBJ_NEW);
		FieldRefEntry lf = ctx.layoutPool.intern(ctx.cp, className, LispLayout.STREAM);
		int handleSlot = ctx.allocTemp();
		ctx.body.astore(handleSlot);
		JvmEmitHelper.emitIntConst(ctx, 1 + LispLayout.STREAM.capacity());
		ctx.body.anewarray(ctx.objectClass).dup().iconst_0().getstatic(lf);
		ctx.body.aastore().dup().iconst_1().aload(handleSlot).aastore().dup().iconst_2();
		// Through the ordinary keyword compilation, so the KIND slot holds exactly what
		// the makeTypeTest kind comparison reads back.
		JvmExprCompiler.compileExpr(new LispSymbol(kind), ctx, className);
		ctx.body.aastore();
	}

	/**
	 * {@code (%obj-ref obj <k>)}, and a {@code defstruct} accessor's checked
	 * {@code (%obj-ref obj <k> failure)}: {@code failure} is compiled on the arm the
	 * instance guard rejects, so a non-instance signals the accessor's type-error rather
	 * than a {@code ClassCastException} -- or, being a cons, reading a slot.
	 */
	static void compileRef(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		LispVal failure = args.size() > 3 ? args.get(3) : null;
		if (gateOff(ctx)) {
			// No instance can exist here: the object is still evaluated for effect, and
			// the read answers nil -- or fails, when it checks.
			if (failure == null) {
				evaluateForEffectThenNil(args.get(1), ctx, className);
			}
			else {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				ctx.body.pop();
				JvmExprCompiler.compileExpr(failure, ctx, className);
			}
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (failure == null) {
			ctx.body.checkcast(ctx.objectArrayClass);
			JvmEmitHelper.emitIntConst(ctx, 1 + literalIndex(args.get(2)));
			ctx.body.aaload();
			return;
		}
		int objSlot = ctx.allocTemp();
		ctx.body.astore(objSlot);
		emitChecked(ctx, className, objSlot, failure, () -> {
			ctx.body.aload(objSlot).checkcast(ctx.objectArrayClass);
			JvmEmitHelper.emitIntConst(ctx, 1 + literalIndex(args.get(2)));
			ctx.body.aaload();
		});
	}

	/**
	 * Emits {@code access} when the value in {@code objSlot} passes the instance guard,
	 * else {@code failure}; either arm leaves one value.
	 */
	private static void emitChecked(JvmLispCompiler.Ctx ctx, String className, int objSlot, LispVal failure,
			Runnable access) {
		int hdrSlot = ctx.allocTemp();
		MethodCode.Label toFailure = ctx.body.newLabel();
		emitInstanceGuard(ctx, objSlot, hdrSlot, toFailure);
		access.run();
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(toFailure);
		JvmExprCompiler.compileExpr(failure, ctx, className);
		ctx.body.labelBinding(gotoEndPos);
	}

	private static MethodRefEntry arraysCopyOfMethod(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.methodRef(ctx.cp.classEntry("java/util/Arrays"), "copyOf",
				"([Ljava/lang/Object;I)[Ljava/lang/Object;");
	}

	/**
	 * {@code (copy-structure s)} (CLHS 18.3): {@code java.util.Arrays.copyOf} over the
	 * argument's {@code Object[]} representation -- a FRESH array carrying the same
	 * layout constant and slot VALUES (a shallow copy). Unlike the {@code copy-<name>}
	 * copier {@code expandDefstruct} generates per type, this argument's type is not
	 * known until run time, so it cannot expand into a literal-tag {@code %obj-new} call
	 * the way that copier does -- it clones the representation directly instead, the one
	 * exception the instance-primitives file makes for a generic (not per-type) copy.
	 */
	static void compileCopyStructure(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.checkcast(ctx.objectArrayClass).dup().arraylength();
		ctx.body.invokestatic(arraysCopyOfMethod(ctx));
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
		FieldRefEntry lf = ctx.layoutPool.intern(ctx.cp, className, layout);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.checkcast(ctx.objectArrayClass).dup().iconst_0().getstatic(lf);
		ctx.body.aastore();
	}

	/**
	 * {@code (%obj-set obj <k> v)}, returning the value written; with a fifth operand, a
	 * {@code defstruct} accessor place's checked store ({@link #compileRef}), whose check
	 * follows the object AND the value.
	 *
	 * <p>
	 * The unchecked store compiles with the gate off too: no operand can be an instance
	 * then, and the code below meets a non-instance the same way whatever the gate says
	 * (the {@code checkcast} fails). A library may so write a reserved cell behind an
	 * {@code %obj-is} test without knowing whether the artifact builds instances.
	 */
	static void compileSet(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() > 4) {
			requireGate(ctx, LispNames.OBJ_SET);
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			int objSlot = ctx.allocTemp();
			ctx.body.astore(objSlot);
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
			int valSlot = ctx.allocTemp();
			ctx.body.astore(valSlot);
			emitChecked(ctx, className, objSlot, args.get(4), () -> {
				ctx.body.aload(objSlot).checkcast(ctx.objectArrayClass);
				JvmEmitHelper.emitIntConst(ctx, 1 + literalIndex(args.get(2)));
				ctx.body.aload(valSlot).aastore().aload(valSlot);
			});
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.checkcast(ctx.objectArrayClass);
		JvmEmitHelper.emitIntConst(ctx, 1 + literalIndex(args.get(2)));
		JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		// [arr, idx, v] -> [v, arr, idx, v]: keeps left-to-right evaluation without a
		// temp, so the object is evaluated before the value as in the interpreter.
		ctx.body.dup_x2().aastore();
	}

	/**
	 * Emits the shared instance guard over the value already stored in {@code objSlot}:
	 * it must be an {@code Object[]}, non-empty, with a {@code String[]} in slot 0. The
	 * header is left in {@code hdrSlot} and the operand stack empty; every escape branch
	 * jumps to {@code toFalse}.
	 */
	private static void emitInstanceGuard(JvmLispCompiler.Ctx ctx, int objSlot, int hdrSlot, MethodCode.Label toFalse) {
		ctx.body.aload(objSlot).instanceOf(ctx.objectArrayClass).ifeq(toFalse);
		ctx.body.aload(objSlot).checkcast(ctx.objectArrayClass).arraylength().ifeq(toFalse);
		ctx.body.aload(objSlot).checkcast(ctx.objectArrayClass).iconst_0().aaload();
		ctx.body.astore(hdrSlot).aload(hdrSlot);
		ctx.body.instanceOf(ctx.layoutPool.stringArrayClass(ctx.cp)).ifeq(toFalse);
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
		ctx.body.astore(objSlot);
		int hdrSlot = ctx.allocTemp();
		MethodCode.Label toFalse = ctx.body.newLabel();
		MethodCode.Label toTrue = ctx.body.newLabel();
		emitInstanceGuard(ctx, objSlot, hdrSlot, toFalse);
		for (int i = 2; i < args.size(); i++) {
			// Compares the tag TEXT, not layout-array identity: an instance may be built
			// by the runtime reader or the embedded eval as well as by %obj-new here.
			ctx.body.aload(hdrSlot).checkcast(ctx.layoutPool.stringArrayClass(ctx.cp));
			ctx.body.iconst_0().aaload();
			JvmEmitHelper.compileStringLiteral(literalTag(args.get(i)), ctx);
			ctx.body.invokevirtual(ctx.objectEquals).ifne(toTrue);
		}
		MethodCode.Label gotoFalsePos = ctx.body.newLabel();
		ctx.body.goto_(gotoFalsePos);
		ctx.body.labelBinding(toTrue);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(gotoFalsePos);
		ctx.body.labelBinding(toFalse);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEndPos);
	}

	/**
	 * {@code (%obj-slots obj)}: a FRESH list of the slot values in layout order, nil for
	 * a non-instance. Built back to front so each cons can be closed as it is made, which
	 * keeps the whole thing one loop with no tail pointer.
	 */
	static void compileSlots(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileSlotsLoop(cons, ctx, className));
	}

	private static void compileSlotsLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (gateOff(ctx)) {
			evaluateForEffectThenNil(args.get(1), ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int objSlot = ctx.allocTemp();
		ctx.body.astore(objSlot);
		int hdrSlot = ctx.allocTemp();
		MethodCode.Label toFalse = ctx.body.newLabel();
		emitInstanceGuard(ctx, objSlot, hdrSlot, toFalse);
		// list = null; for (i = layout.length - 3; i >= 1; i--) list = new
		// Object[]{obj[i], list}. The cursor stops at 1, not 0: slot 0 of the instance
		// array is the layout, not a slot value. It starts at the LAYOUT's slot count
		// (its String[] is {tag, printName, kind, slot...}), not at the array length,
		// because a change-class-reserved array is longer than the layout describes.
		int listSlot = ctx.allocTemp();
		ctx.body.aconst_null().astore(listSlot);
		int idxSlot = ctx.allocTemp();
		ctx.body.aload(hdrSlot).checkcast(ctx.layoutPool.stringArrayClass(ctx.cp));
		ctx.body.arraylength().iconst_3().isub().istore(idxSlot);
		MethodCode.Label loopTop = ctx.body.newBoundLabel();
		ctx.body.iload(idxSlot);
		MethodCode.Label exitLoop = ctx.body.newLabel();
		ctx.body.ifle(exitLoop);
		ctx.body.iconst_2().anewarray(ctx.objectClass).dup().iconst_0().aload(objSlot);
		ctx.body.checkcast(ctx.objectArrayClass).iload(idxSlot).aaload().aastore().dup();
		ctx.body.iconst_1().aload(listSlot).aastore().astore(listSlot);
		// Through the typed layer: the byte emitter wrote iinc's slot in one byte, which
		// past 255 named another local.
		ctx.body.iinc(idxSlot, -1).goto_(loopTop);
		ctx.body.labelBinding(exitLoop);
		ctx.body.aload(listSlot);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(toFalse);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEndPos);
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
		ctx.body.astore(objSlot);
		int hdrSlot = ctx.allocTemp();
		MethodCode.Label toFalse = ctx.body.newLabel();
		emitInstanceGuard(ctx, objSlot, hdrSlot, toFalse);
		if (readTag) {
			ctx.body.aload(hdrSlot).checkcast(ctx.layoutPool.stringArrayClass(ctx.cp));
			ctx.body.iconst_0().aaload();
		}
		else {
			JvmEmitHelper.compileTrue(ctx);
		}
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(toFalse);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEndPos);
	}

}
