package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the internal {@code %simple-array-p} predicate behind the {@code simple-array}
 * / {@code simple-vector} / {@code simple-string} type specifiers: true for an array (a
 * string included) with no fill pointer, not adjustable and not displaced.
 *
 * <p>
 * The representations, all from {@link JvmArrayRuntimeBuilder}:
 * <ul>
 * <li>the QUOTE-FRAMED immutable runtime {@code String} -- always simple (a symbol shares
 * the class without the frame and is no array, so the frame is tested here exactly as
 * {@link JvmStringpCompiler} tests it);</li>
 * <li>a packed {@code long[]} / {@code double[]} / {@code float[]} -- simple by
 * construction ({@code make-array} degrades to the general shape the moment
 * {@code :fill-pointer} / {@code :adjustable} / {@code :displaced-to} appears), each
 * behind the same program gate {@link JvmArraypCompiler} tests them behind;</li>
 * <li>an {@code ArrayList} -- the general array, the mutable character vector and the
 * string view: simple unless its slot-0 header carries a fill pointer (slot 1), the
 * {@code :adjustable} argument (slot 2) or a displacement target (slot 3 of a length-5+
 * header -- the same "length &gt; 4 AND a non-null target" rule {@code _arrayDispOffset}
 * uses, so the packed general array's length-6 header with its null slot 3 stays
 * simple);</li>
 * <li>anything else -- not an array, so nil rather than a cast failure. That totality is
 * the point: the predicate is asked about a value the type test has not narrowed.</li>
 * </ul>
 *
 * <p>
 * Every path keeps EXACTLY ONE reference on the operand stack until the two landings pop
 * it, so the frames the {@code StackMapAugmenter} infers merge at height 1 with no
 * instruction downstream that needs a narrower type than {@code Object}.
 */
final class JvmSimpleArrayPCompiler {

	private JvmSimpleArrayPCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		List<Integer> gotoTrue = new ArrayList<>();
		List<Integer> gotoFalse = new ArrayList<>();
		// The immutable runtime string: simple, no header to read -- but only when it is
		// QUOTE-FRAMED, since a symbol shares java/lang/String without the frame (the
		// same frame test stringp makes) and a symbol is no array at all.
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.stringClass.index());
		int ifNotString = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.stringCharAt.index());
		JvmEmitHelper.emitIntConst(ctx, 34);
		gotoFalse.add(ctx.code.size());
		ctx.emit(Opcode.IF_ICMPNE);
		ctx.emitU2(0);
		gotoTrue.add(ctx.code.size());
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotString, ctx.code.size());
		// The packed vectors: simple by construction.
		List<String> simpleClasses = new ArrayList<>();
		if (ctx.usesIntArray) {
			simpleClasses.add("[J");
		}
		if (ctx.usesFloatArray) {
			simpleClasses.add("[D");
			simpleClasses.add("[F");
			simpleClasses.add("[S");
		}
		for (String cls : simpleClasses) {
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8(cls)).index());
			int ifNot = ctx.code.size();
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			gotoTrue.add(ctx.code.size());
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifNot, ctx.code.size());
		}
		// Only the general ArrayList shape can still be an array.
		int arrayListClass = ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")).index();
		int objectArrayClass = ctx.cp.addClass(ctx.cp.addUtf8("[Ljava/lang/Object;")).index();
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(arrayListClass);
		gotoFalse.add(ctx.code.size());
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(arrayListClass);
		// An EMPTY list carries no header, so it is no array shape this predicate knows.
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.cp
			.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")),
					ctx.cp.addNameAndType(ctx.cp.addUtf8("size"), ctx.cp.addUtf8("()I")))
			.index());
		gotoFalse.add(ctx.code.size());
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.cp
			.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")),
					ctx.cp.addNameAndType(ctx.cp.addUtf8("get"), ctx.cp.addUtf8("(I)Ljava/lang/Object;")))
			.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(objectArrayClass);
		gotoFalse.add(ctx.code.size());
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(objectArrayClass);
		// header[1] (the fill pointer) and header[2] (the :adjustable argument): either
		// one non-null means NOT simple.
		for (int slot = 1; slot <= 2; slot++) {
			ctx.emit(Opcode.DUP);
			ctx.emit(slot == 1 ? Opcode.ICONST_1 : Opcode.ICONST_2);
			ctx.emit(Opcode.AALOAD);
			int ifNull = ctx.code.size();
			ctx.emit(Opcode.IFNULL);
			ctx.emitU2(0);
			gotoFalse.add(ctx.code.size());
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifNull, ctx.code.size());
		}
		// header.length > 4 with a non-null slot 3: displaced (length 5, or 7 for a
		// string view), so NOT simple.
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ARRAYLENGTH);
		ctx.emit(Opcode.ICONST_4);
		int ifShort = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPLE);
		ctx.emitU2(0);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_3);
		ctx.emit(Opcode.AALOAD);
		int ifNoTarget = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		gotoFalse.add(ctx.code.size());
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNoTarget, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifShort, ctx.code.size());
		gotoTrue.add(ctx.code.size());
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		for (int pos : gotoFalse) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
		ctx.emit(Opcode.POP);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		for (int pos : gotoTrue) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
		ctx.emit(Opcode.POP);
		JvmEmitHelper.compileTrue(ctx);
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

}
