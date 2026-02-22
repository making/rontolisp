package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

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
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		// DUP the array ref (to leave it on stack after AASTORE)
		ctx.emit(Opcode.DUP);
		// Index 0 = car
		ctx.emit(Opcode.ICONST_0);
		// Compile new value
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		// Store: array[0] = newValue
		ctx.emit(Opcode.AASTORE);
		// The DUPed array ref remains on the stack (rplaca returns the cons cell)
	}

}
