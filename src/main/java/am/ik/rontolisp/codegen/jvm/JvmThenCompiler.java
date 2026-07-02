package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code rontolisp:then}: derives a new promise from a base value (usually a
 * promise) and a callback. The derived promise is a completed {@code CompletableFuture}
 * holding the chain payload {@code Object[]{MARKER, base, fn}}; the {@code _await}
 * runtime helper ({@link JvmFetchRuntimeBuilder}) recognizes the payload, resolves the
 * base, applies the callback through the {@code _invoke_1} dispatcher at first await, and
 * memoizes the result back into the future. Representing the chain as a future keeps
 * {@code promisep} a single {@code instanceof CompletableFuture} and the print branch a
 * single check.
 */
final class JvmThenCompiler {

	private JvmThenCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException("then expects 2 arguments, got " + (args.size() - 1));
		}
		ClassConstantHolder refs = new ClassConstantHolder(ctx);
		// new Object[]{MARKER, base, fn}
		ctx.emit(Opcode.ICONST_3);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		JvmEmitHelper.compileStringLiteral(JvmFetchRuntimeBuilder.MARKER, ctx);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_2);
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(2)), ctx, className);
		ctx.emit(Opcode.AASTORE);
		// CompletableFuture.completedFuture(payload) -- the promise value
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(refs.completedFuture.index());
	}

	// Constant-pool references, created lazily per call site (addUtf8/addClass dedupe).
	private static final class ClassConstantHolder {

		final ConstantPool.MethodrefConstant completedFuture;

		ClassConstantHolder(JvmLispCompiler.Ctx ctx) {
			ConstantPool.ClassConstant futureClass = ctx.cp
				.addClass(ctx.cp.addUtf8("java/util/concurrent/CompletableFuture"));
			this.completedFuture = ctx.cp.addMethodref(futureClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("completedFuture"),
							ctx.cp.addUtf8("(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;")));
		}

	}

}
