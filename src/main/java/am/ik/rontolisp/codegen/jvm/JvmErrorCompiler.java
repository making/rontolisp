package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code %error} primitive: it evaluates its single string argument
 * and throws a {@link RuntimeException} with that message, aborting execution. Runtime
 * strings carry surrounding quotes ({@code "msg"}), so the closing and opening quotes are
 * stripped via {@code msg.substring(1, msg.length() - 1)} before the throw. The
 * message-stripping throw shape is shared with {@link JvmErrorCondCompiler} (the
 * condition-carrying variant).
 */
final class JvmErrorCompiler {

	private JvmErrorCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileThrowRuntimeException(args.get(1), ctx, className);
	}

	/**
	 * Emits {@code throw new RuntimeException(strip(messageExpr))}: the message
	 * expression is compiled, its quote framing stripped, and the exception thrown.
	 */
	static void compileThrowRuntimeException(LispVal messageExpr, JvmLispCompiler.Ctx ctx, String className) {
		ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant ctor = ctx.cp.addMethodref(runtimeEx,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("(Ljava/lang/String;)V")));
		int length = JvmEmitHelper.stringMethod(ctx, "length", "()I").index();
		int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		// message: arg.substring(1, arg.length() - 1), computed into a local BEFORE the
		// allocation. The verifier tracks a half-constructed object apart from an
		// ordinary reference and no local can hold one, so evaluating the message under
		// a live `new` would deny every form that has to spill the operand stack -- a
		// handler-case, and any loop, inside the message expression (the message is
		// usually a `format` call, which is full of both).
		JvmExprCompiler.compileExpr(messageExpr, ctx, className);
		// A message built by a flipped producer (format nil, concatenate, a report
		// lambda's with-output-to-string capture) can be a mutable character vector:
		// render it before the (String) cast (a no-op without the array runtime).
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
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
		int messageSlot = ctx.errorMessageSlot();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(messageSlot);
		// throw new RuntimeException(message)
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtimeEx.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(messageSlot);
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(ctor.index());
		ctx.emit(Opcode.ATHROW);
	}

}
