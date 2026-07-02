package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code rontolisp:promisep} predicate. A promise is a
 * {@code CompletableFuture} at runtime (both the {@code rontolisp:fetch} root and the
 * {@code rontolisp:then} chain, see {@link JvmFetchRuntimeBuilder} /
 * {@link JvmThenCompiler}) and nothing else in the runtime value representation is one,
 * so the predicate is a single {@code instanceof}.
 */
final class JvmPromisepCompiler {

	private JvmPromisepCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("promisep expects 1 argument, got " + (args.size() - 1));
		}
		ConstantPool.ClassConstant futureClass = ctx.cp
			.addClass(ctx.cp.addUtf8("java/util/concurrent/CompletableFuture"));
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(futureClass.index());
		int ifNotPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
