package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code null} predicate, and {@code endp}, which is {@code null} of a
 * checked list.
 */
final class JvmNullPredCompiler {

	private JvmNullPredCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		emitNullTest(ctx);
	}

	/**
	 * Compiles {@code endp}: the argument through {@code _endp}, which answers nil or a
	 * cons and throws {@code ENDP}'s type-error for anything else, then its null test.
	 */
	static void compileEndp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.ENDP).index());
		emitNullTest(ctx);
	}

	/** Replaces the value on the stack with {@code t} when it is nil, else nil. */
	private static void emitNullTest(JvmLispCompiler.Ctx ctx) {
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		JvmEmitHelper.compileTrue(ctx);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
