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
 * stripped via {@code msg.substring(1, msg.length() - 1)} before the throw.
 */
final class JvmErrorCompiler {

	private JvmErrorCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant ctor = ctx.cp.addMethodref(runtimeEx,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("(Ljava/lang/String;)V")));
		int length = JvmEmitHelper.stringMethod(ctx, "length", "()I").index();
		int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		// new RuntimeException
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtimeEx.index());
		ctx.emit(Opcode.DUP);
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
		// throw
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(ctor.index());
		ctx.emit(Opcode.ATHROW);
	}

}
