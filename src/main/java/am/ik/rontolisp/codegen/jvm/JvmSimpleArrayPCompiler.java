package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

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
 * <li>a packed {@code byte[]} / {@code long[]} / {@code double[]} / {@code float[]} --
 * simple by construction ({@code make-array} degrades to the general shape the moment
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
 * it, so the frames the class writer infers merge at height 1 with no instruction
 * downstream that needs a narrower type than {@code Object}.
 */
final class JvmSimpleArrayPCompiler {

	private JvmSimpleArrayPCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		MethodCode.Label gotoTrue = ctx.body.newLabel();
		MethodCode.Label gotoFalse = ctx.body.newLabel();
		// The immutable runtime string: simple, no header to read -- but only when it is
		// QUOTE-FRAMED, since a symbol shares java/lang/String without the frame (the
		// same frame test stringp makes) and a symbol is no array at all.
		ctx.body.dup().instanceOf(ctx.stringClass);
		MethodCode.Label ifNotString = ctx.body.newLabel();
		ctx.body.ifeq(ifNotString);
		ctx.body.dup().checkcast(ctx.stringClass).iconst_0();
		ctx.body.invokevirtual(ctx.stringCharAt);
		JvmEmitHelper.emitIntConst(ctx, 34);
		ctx.body.if_icmpne(gotoFalse);
		ctx.body.goto_(gotoTrue);
		ctx.body.labelBinding(ifNotString);
		// The packed vectors: simple by construction -- the octet vector first, whose
		// test tells it from a quantized matrix (no array) where one can exist.
		if (ctx.usesIntArray) {
			MethodCode.Label notOctets = ctx.body.newLabel();
			JvmIntArrayRuntimeBuilder.emitOctetTestOnStack(ctx, notOctets);
			ctx.body.goto_(gotoTrue);
			ctx.body.labelBinding(notOctets);
		}
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
			ctx.body.dup().instanceOf(ctx.cp.classEntry(cls));
			MethodCode.Label ifNot = ctx.body.newLabel();
			ctx.body.ifeq(ifNot);
			ctx.body.goto_(gotoTrue);
			ctx.body.labelBinding(ifNot);
		}
		// Only the general ArrayList shape can still be an array.
		ClassEntry arrayListClass = ctx.cp.classEntry("java/util/ArrayList");
		ClassEntry objectArrayClass = ctx.cp.classEntry("[Ljava/lang/Object;");
		JvmJavaSites javaSites = ctx.javaSites;
		if (javaSites != null) {
			// In a java: program the shared _jlarr: a call can answer an ArrayList (or a
			// subclass, whose own size / get must not be asked) of its own.
			ctx.body.dup().invokestatic(javaSites.direct().lispArray()).ifeq(gotoFalse);
		}
		else {
			ctx.body.dup().instanceOf(arrayListClass).ifeq(gotoFalse);
		}
		ctx.body.checkcast(arrayListClass);
		// An EMPTY list carries no header, so it is no array shape this predicate knows.
		ctx.body.dup();
		ctx.body.invokevirtual(ctx.cp.methodRef(ctx.cp.classEntry("java/util/ArrayList"), "size", "()I"));
		ctx.body.ifeq(gotoFalse);
		ctx.body.iconst_0();
		ctx.body
			.invokevirtual(ctx.cp.methodRef(ctx.cp.classEntry("java/util/ArrayList"), "get", "(I)Ljava/lang/Object;"));
		ctx.body.dup().instanceOf(objectArrayClass).ifeq(gotoFalse);
		ctx.body.checkcast(objectArrayClass);
		// header[1] (the fill pointer) and header[2] (the :adjustable argument): either
		// one non-null means NOT simple.
		for (int slot = 1; slot <= 2; slot++) {
			ctx.body.dup().loadConstant(slot).aaload();
			MethodCode.Label ifNull = ctx.body.newLabel();
			ctx.body.ifnull(ifNull);
			ctx.body.goto_(gotoFalse);
			ctx.body.labelBinding(ifNull);
		}
		// header.length > 4 with a non-null slot 3: displaced (length 5, or 7 for a
		// string view), so NOT simple.
		ctx.body.dup().arraylength().iconst_4();
		MethodCode.Label ifShort = ctx.body.newLabel();
		ctx.body.if_icmple(ifShort);
		ctx.body.dup().iconst_3().aaload();
		MethodCode.Label ifNoTarget = ctx.body.newLabel();
		ctx.body.ifnull(ifNoTarget);
		ctx.body.goto_(gotoFalse);
		ctx.body.labelBinding(ifNoTarget);
		ctx.body.labelBinding(ifShort);
		ctx.body.goto_(gotoTrue);
		ctx.body.labelBinding(gotoFalse);
		ctx.body.pop().aconst_null();
		MethodCode.Label gotoEnd = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd);
		ctx.body.labelBinding(gotoTrue);
		ctx.body.pop();
		JvmEmitHelper.compileTrue(ctx);
		ctx.body.labelBinding(gotoEnd);
	}

}
