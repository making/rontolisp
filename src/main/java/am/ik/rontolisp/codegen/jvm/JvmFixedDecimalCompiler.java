package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the internal {@code %fixed-decimal} built-in function -- what {@code format}'s
 * {@code ~F} and {@code ~$} lower to -- as one call to the generated {@code _fixdec}
 * helper ({@link JvmNumericRuntimeBuilder#FIXED_DEC}). The helper does the coercions
 * itself, so the call site is the four argument expressions and nothing else.
 */
final class JvmFixedDecimalCompiler {

	private JvmFixedDecimalCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 5) {
			throw new UnsupportedOperationException("%fixed-decimal expects 4 arguments, got " + (args.size() - 1));
		}
		for (int i = 1; i <= 4; i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.FIXED_DEC).index());
	}

}
