package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code car} built-in function.
 */
final class JvmCarCompiler {

	private JvmCarCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// nil answers nil, a cons its field, a non-list is CAR's type-error
		// (JvmOperandTypeRuntime).
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.CAR).index());
	}

}
