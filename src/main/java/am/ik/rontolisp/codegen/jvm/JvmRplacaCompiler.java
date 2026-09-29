package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code rplaca} built-in function. Destructively replaces the car of a cons
 * cell (Object[] index 0) and leaves the cons cell on the stack.
 */
final class JvmRplacaCompiler {

	private JvmRplacaCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Compile the cons cell
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// A cons, cast; anything else (nil included) is RPLACA's CONS type-error
		// (JvmOperandTypeRuntime).
		ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.CK_CONS));
		// DUP the array ref (to leave it on stack after AASTORE)
		ctx.body.dup();
		// Index 0 = car
		ctx.body.iconst_0();
		// Compile new value
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		// Store: array[0] = newValue
		ctx.body.aastore();
		// The DUPed array ref remains on the stack (rplaca returns the cons cell)
	}

}
