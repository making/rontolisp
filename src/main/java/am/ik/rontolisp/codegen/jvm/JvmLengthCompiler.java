package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code length} built-in. It pushes its single argument and calls the
 * {@code _length} static runtime helper emitted by {@link JvmLengthRuntimeBuilder}, which
 * handles strings, vectors (rank-1 arrays) and lists. Keeping the call site to one
 * {@code invokestatic} matters because top-level forms compile into one {@code main}
 * method bounded by the JVM's 64&nbsp;KB per-method code limit.
 */
final class JvmLengthCompiler {

	private JvmLengthCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// When the program uses packed arrays, route through the packed dispatch chain
		// (_ivLength handles a packed long[] and delegates down; _fvLength handles a
		// packed double[]/float[] and delegates the general case to _length); the
		// descriptors match, so the default build is unchanged.
		String method = ctx.usesIntArray ? JvmIntArrayRuntimeBuilder.LENGTH
				: ctx.usesFloatArray ? JvmFloatArrayRuntimeBuilder.LENGTH : JvmLengthRuntimeBuilder.METHOD;
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(method), ctx.cp.addUtf8(JvmLengthRuntimeBuilder.DESC)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
