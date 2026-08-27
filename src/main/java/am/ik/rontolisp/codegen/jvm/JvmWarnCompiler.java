package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
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
 * quotes itself.
 */
final class JvmWarnCompiler {

	private JvmWarnCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (ctx.globals.contains(LispNames.ERROR_OUTPUT_VAR)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(
					java.util.Objects
						.requireNonNull(JvmStringStreamCompiler.streamDesignator(ctx, StreamDesignators.errorOutput())),
					ctx, className);
			ConstantPool.MethodrefConstant writeLineRef = ctx.cp.addMethodref(
					ctx.cp.addClass(ctx.cp.addUtf8(className)),
					ctx.cp.addNameAndType(ctx.cp.addUtf8(JvmIoRuntimeBuilder.WRITE_LINE_METHOD),
							ctx.cp.addUtf8(JvmIoRuntimeBuilder.WRITE_LINE_DESC)));
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(writeLineRef.index());
			// _writeLine answers the string; %warn answers nil.
			ctx.emit(Opcode.POP);
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		ConstantPool.ClassConstant systemClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/System"));
		ConstantPool.FieldrefConstant systemErr = ctx.cp.addFieldref(systemClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("err"), ctx.cp.addUtf8("Ljava/io/PrintStream;")));
		int length = JvmEmitHelper.stringMethod(ctx, "length", "()I").index();
		int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		// System.err
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(systemErr.index());
		// message: arg.substring(1, arg.length() - 1)
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(length);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(substring);
		// System.err.println(message); result is nil
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnStr.index());
		ctx.emit(Opcode.ACONST_NULL);
	}

}
