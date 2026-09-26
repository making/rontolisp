package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code rplacd} built-in function. Destructively replaces the cdr of a cons
 * cell (Object[] index 1) and leaves the cons cell on the stack.
 */
final class JvmRplacdCompiler {

	private JvmRplacdCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Compile the cons cell
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// A cons, cast; anything else (nil included) is RPLACD's CONS type-error
		// (JvmOperandTypeRuntime).
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.CK_CONS).index());
		// DUP the array ref (to leave it on stack after AASTORE)
		ctx.emit(Opcode.DUP);
		// Index 1 = cdr
		ctx.emit(Opcode.ICONST_1);
		// Compile new value
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		// Store: array[1] = newValue
		ctx.emit(Opcode.AASTORE);
		// The DUPed array ref remains on the stack (rplacd returns the cons cell)
	}

}
