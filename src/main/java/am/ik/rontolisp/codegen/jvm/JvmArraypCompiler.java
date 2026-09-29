package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code %arrayp} predicate used by the {@code vector}/
 * {@code array}/{@code sequence} type specifiers. A general array is a
 * {@code java.util.ArrayList} at runtime (see {@link JvmArrayRuntimeBuilder}), and no
 * other Lisp value uses that class, so a plain {@code instanceof} suffices -- except in a
 * {@code java:} program, where a call can answer a host {@code ArrayList} and the
 * program's shared {@code _jlarr} test ({@link JvmJavaDirectSites#lispArray()}) decides.
 * When the program uses a packed representation, the packed shapes are arrays too: a
 * {@code byte[]} / {@code long[]} (packed integer vector,
 * {@link JvmIntArrayRuntimeBuilder}) and a {@code double[]}/{@code float[]} (packed float
 * array, {@link JvmFloatArrayRuntimeBuilder}) each get a preceding {@code instanceof}
 * branch; without the gates the default build is byte-identical. A quantized matrix, the
 * other {@code byte[]}, is no array: the octet test reads the tag where one can exist.
 */
final class JvmArraypCompiler {

	private JvmArraypCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// The packed representations in dispatch order (iv then fv, matching the
		// accessor chain); each emits "if (v instanceof <cls>) { pop; return t; }".
		List<String> packedClasses = new ArrayList<>();
		if (ctx.usesIntArray) {
			packedClasses.add("[J");
		}
		if (ctx.usesFloatArray) {
			packedClasses.add("[D");
			packedClasses.add("[F");
			packedClasses.add("[S");
		}
		MethodCode.Label gotoEnds = ctx.body.newLabel();
		if (ctx.usesIntArray) {
			MethodCode.Label notOctets = ctx.body.newLabel();
			JvmIntArrayRuntimeBuilder.emitOctetTestOnStack(ctx, notOctets);
			ctx.body.pop();
			JvmEmitHelper.compileTrue(ctx);
			ctx.body.goto_(gotoEnds);
			ctx.body.labelBinding(notOctets);
		}
		for (String cls : packedClasses) {
			ctx.body.dup().instanceOf(ctx.cp.addClass(ctx.cp.addUtf8(cls)).entry());
			MethodCode.Label ifNotPackedPos = ctx.body.newLabel();
			ctx.body.ifeq(ifNotPackedPos);
			ctx.body.pop();
			JvmEmitHelper.compileTrue(ctx);
			ctx.body.goto_(gotoEnds);
			ctx.body.labelBinding(ifNotPackedPos);
		}
		// fall through with the value still on the stack for the ArrayList check -- in a
		// java: program the shared _jlarr, since a call can answer a host ArrayList
		JvmJavaSites javaSites = ctx.javaSites;
		if (javaSites != null) {
			ctx.body.invokestatic(javaSites.direct().lispArray());
		}
		else {
			ctx.body.instanceOf(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")).entry());
		}
		MethodCode.Label ifNotListPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotListPos);
		JvmEmitHelper.compileTrue(ctx);
		ctx.body.goto_(gotoEnds);
		ctx.body.labelBinding(ifNotListPos);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEnds);
	}

}
