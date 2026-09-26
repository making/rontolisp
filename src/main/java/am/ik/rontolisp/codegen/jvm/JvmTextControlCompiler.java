package am.ik.rontolisp.codegen.jvm;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;

/**
 * Compiles {@code (%text-control text)} and its inverse {@code (%control-text control)}:
 * a string with every {@code ~} doubled (undoubled), anything else unchanged -- the
 * interpreter's {@code ClosRegistry.textControl}/{@code controlText}. Both live in one
 * per-class helper each ({@link JvmEmitHelper#emitSharedCall}), since every condition a
 * landing pad synthesizes stores its message through the first.
 */
final class JvmTextControlCompiler {

	private JvmTextControlCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, boolean undo) {
		JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
		JvmEmitHelper.emitSharedCall(ctx, className, undo ? "_ctlText" : "_textCtl", 1,
				helper -> emitBody(helper, className, undo));
	}

	/**
	 * The helper's body over its parameter (slot 0): a mutable character vector is
	 * normalized first, a {@code String} answers its {@code replace}, anything else
	 * itself. A quote-framed string's framing quotes are not tildes, so the replace
	 * leaves them alone.
	 */
	private static void emitBody(JvmLispCompiler.Ctx ctx, String className, boolean undo) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(0);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(0);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.stringClass.index());
		int notString = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		JvmEmitHelper.compileUnspelledLiteral(undo ? "~~" : "~", ctx);
		JvmEmitHelper.compileUnspelledLiteral(undo ? "~" : "~~", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper
			.stringMethod(ctx, "replace", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;")
			.index());
		ctx.emit(Opcode.ARETURN);
		JvmEmitHelper.patchBranch(ctx, notString, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(0);
	}

}
