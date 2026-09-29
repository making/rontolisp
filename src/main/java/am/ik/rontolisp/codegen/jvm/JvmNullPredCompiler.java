package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

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
		ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.ENDP));
		emitNullTest(ctx);
	}

	/**
	 * Compiles {@code (%check-list x 'op)}: {@code x}, checked to be a list under
	 * {@code op} -- {@code endp}'s own {@code _endp}, or {@code _ckList} through the
	 * operator's wrapper.
	 */
	static void compileCheckList(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		String operator = LispMacroExpander.checkListOperator(cons);
		if (LispNames.ENDP.equals(operator)) {
			ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.ENDP));
			return;
		}
		@Nullable String outer = ctx.operator;
		ctx.operator = operator;
		try {
			JvmEmitHelper.emitListCheck(ctx);
		}
		finally {
			ctx.operator = outer;
		}
	}

	/** Replaces the value on the stack with {@code t} when it is nil, else nil. */
	private static void emitNullTest(JvmLispCompiler.Ctx ctx) {
		MethodCode.Label ifNullPos = ctx.body.newLabel();
		ctx.body.ifnull(ifNullPos);
		ctx.body.aconst_null();
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifNullPos);
		JvmEmitHelper.compileTrue(ctx);
		ctx.body.labelBinding(gotoEndPos);
	}

}
