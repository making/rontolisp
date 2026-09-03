package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the internal {@code %arrayp} predicate used by the {@code vector}/
 * {@code array}/{@code sequence} type specifiers. A general array is a
 * {@code java.util.ArrayList} at runtime (see {@link JvmArrayRuntimeBuilder}), and no
 * other value uses that class, so a plain {@code instanceof} suffices. When the program
 * uses a packed representation, the packed shapes are arrays too: a {@code long[]}
 * (packed integer vector, {@link JvmIntArrayRuntimeBuilder}) and a
 * {@code double[]}/{@code float[]} (packed float array,
 * {@link JvmFloatArrayRuntimeBuilder}) each get a preceding {@code instanceof} branch;
 * without the gates the default build is byte-identical.
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
		List<Integer> gotoEnds = new ArrayList<>();
		for (String cls : packedClasses) {
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8(cls)).index());
			int ifNotPackedPos = ctx.code.size();
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			ctx.emit(Opcode.POP);
			JvmEmitHelper.compileTrue(ctx);
			gotoEnds.add(ctx.code.size());
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifNotPackedPos, ctx.code.size());
		}
		// fall through with the value still on the stack for the ArrayList check
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")).index());
		int ifNotListPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		JvmEmitHelper.compileTrue(ctx);
		gotoEnds.add(ctx.code.size());
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotListPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		for (int gotoEnd : gotoEnds) {
			JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
		}
	}

}
