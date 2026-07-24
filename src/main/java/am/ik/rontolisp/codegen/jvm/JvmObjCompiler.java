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
 * Compiles the six instance primitives -- {@code %obj-new}, {@code %obj-ref},
 * {@code %obj-set}, {@code %obj-is}, {@code %obj-tag} and {@code %obj-p} -- through which
 * every {@code defstruct}/{@code defclass}/condition instance is built, read, written and
 * type-tested.
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

	private static LispLayout requireLayout(JvmLispCompiler.Ctx ctx, String tag) {
		LispLayout layout = ctx.closRegistry.findLayoutByTag(tag);
		if (layout == null) {
			throw new UnsupportedOperationException("unknown instance type " + tag);
		}
		return layout;
	}

	/** {@code (%obj-new '<tag> v1 ... vn)}. */
	static void compileNew(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		LispLayout layout = requireLayout(ctx, literalTag(args.get(1)));
		FieldrefConstant lf = ctx.layoutPool.intern(ctx.cp, className, layout);
		int slots = layout.slotCount();
		JvmEmitHelper.emitIntConst(ctx, 1 + slots);
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
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		JvmEmitHelper.emitIntConst(ctx, 1 + literalIndex(args.get(2)));
		ctx.emit(Opcode.AALOAD);
	}

	/** {@code (%obj-set obj <k> v)}, returning the value written. */
	static void compileSet(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
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
