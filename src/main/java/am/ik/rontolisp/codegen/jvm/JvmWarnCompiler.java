package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code %warn} primitive: it evaluates its single string argument
 * (the pre-built {@code WARNING: ...} message), prints it to {@link System#err} and
 * pushes nil. Runtime strings carry surrounding quotes ({@code "msg"}), so the closing
 * and opening quotes are stripped via {@code msg.substring(1, msg.length() - 1)} before
 * printing, like {@link JvmErrorCompiler}.
 */
final class JvmWarnCompiler {

	private JvmWarnCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
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
