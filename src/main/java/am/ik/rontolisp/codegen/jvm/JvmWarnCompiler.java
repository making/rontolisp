package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.StreamDesignators;

/**
 * Compiles the internal {@code %warn} primitive: it evaluates its single string argument
 * (the pre-built {@code WARNING: ...} message), writes it to the current
 * {@code *error-output*} and pushes nil.
 *
 * <p>
 * A program that never binds {@code *error-output*} keeps the direct {@link System#err}
 * path (byte-identical to before the redirect existed): the variable's seeded value IS
 * the process standard error. Runtime strings carry surrounding quotes ({@code "msg"}),
 * so the closing and opening quotes are stripped via
 * {@code msg.substring(1, msg.length() - 1)} before printing, like
 * {@link JvmErrorCompiler}.
 *
 * <p>
 * When the program DOES bind it -- {@code (let ((*error-output* s)) (warn ...))}, CL's
 * warning-capture idiom -- the report goes through the {@code _writeLine} runtime helper
 * with the variable's current (dynamic-first) value as the destination, so a string
 * stream captures it and the seeded handle 2 still reaches stderr. That helper strips the
 * quotes itself. A program that carries the Gray write-line dispatch writes through it
 * instead, because the variable can then hold a Gray instance the helper cannot reach.
 */
final class JvmWarnCompiler {

	private JvmWarnCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (ctx.globals.contains(LispNames.ERROR_OUTPUT_VAR)
				&& ctx.functions.containsKey(LispNames.GRAY_WRITE_LINE_DISPATCH)) {
			// The variable may hold a Gray instance (a broadcast stream), which only the
			// Gray dispatch writes to; it falls back to the built-in write-line for
			// everything else.
			JvmExprCompiler
				.compileExpr(
						new LispCons(new LispSymbol(LispNames.GRAY_WRITE_LINE_DISPATCH),
								new LispCons(args.get(1),
										new LispCons(StreamDesignators.errorOutput(), LispNil.INSTANCE))),
						ctx, className);
			ctx.body.pop().aconst_null();
			return;
		}
		if (ctx.globals.contains(LispNames.ERROR_OUTPUT_VAR)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(
					java.util.Objects
						.requireNonNull(JvmStringStreamCompiler.streamDesignator(ctx, StreamDesignators.errorOutput())),
					ctx, className);
			MethodRefEntry writeLineRef = ctx.cp.methodRef(ctx.cp.classEntry(className),
					JvmIoRuntimeBuilder.WRITE_LINE_METHOD, JvmIoRuntimeBuilder.WRITE_LINE_DESC);
			ctx.body.invokestatic(writeLineRef);
			// _writeLine answers the string; %warn answers nil.
			ctx.body.pop().aconst_null();
			return;
		}
		ClassEntry systemClass = ctx.cp.classEntry("java/lang/System");
		FieldRefEntry systemErr = ctx.cp.fieldRef(systemClass, "err", "Ljava/io/PrintStream;");
		MethodRefEntry length = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		MethodRefEntry substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;");
		// System.err
		ctx.body.getstatic(systemErr);
		// message: arg.substring(1, arg.length() - 1)
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// A warning message can be a mutable character vector (a flipped producer's
		// result): render it before the (String) cast (a no-op without the array
		// runtime).
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass).dup().invokevirtual(length);
		ctx.body.iconst_1().isub().iconst_1().swap().invokevirtual(substring);
		// System.err.println(message); result is nil
		ctx.body.invokevirtual(ctx.printlnStr).aconst_null();
	}

}
