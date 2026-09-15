package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code rational} built-in function. Integers and ratios answer themselves;
 * a finite float normalizes to its exact binary value through the {@code _rational}
 * numeric helper (which shares {@code _frat}'s decomposition with the exact float
 * division route).
 */
final class JvmRationalCompiler {

	private JvmRationalCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// _rational handles Long, BigInteger, Double and ratios (BigInteger[]).
		ctx.emit(am.ik.jvm.Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.RATIONAL).index());
	}

}
