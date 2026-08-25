package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code nthcdr} built-in function. It pushes the count and the list and
 * calls the {@code _nthcdr} static runtime helper emitted by
 * {@link JvmNthcdrRuntimeBuilder}, which walks {@code cdr} that many times.
 *
 * <p>
 * The walk deliberately does NOT live here: an inline loop would put its backedge under
 * the enclosing expression's pending operands, and HotSpot refuses to OSR-compile such a
 * loop head at every tier ({@link JvmNthcdrRuntimeBuilder} has the detail). Every
 * {@code nth} reaches this compiler too -- {@code (nth n l)} expands to
 * {@code (car (nthcdr n l))}.
 */
final class JvmNthcdrCompiler {

	private JvmNthcdrCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Evaluate n -> Long, unbox to long, convert to int. Evaluated before the list,
		// as the inline walk this replaced did.
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)), ctx.cp.addNameAndType(
				ctx.cp.addUtf8(JvmNthcdrRuntimeBuilder.METHOD), ctx.cp.addUtf8(JvmNthcdrRuntimeBuilder.DESC)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
