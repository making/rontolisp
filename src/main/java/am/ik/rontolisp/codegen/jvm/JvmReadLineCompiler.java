package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code read-line} built-in function. Without an argument it reads from
 * standard input via the {@code _readLine} helper; with a stream-handle argument it reads
 * from that stream via the {@code _readLineStream} stream runtime helper.
 */
final class JvmReadLineCompiler {

	private JvmReadLineCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.readLineHelper.index());
			return;
		}
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("read-line expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_LINE_STREAM_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_LINE_STREAM_DESC);
		MethodrefConstant readLineStreamRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(readLineStreamRef.index());
	}

}
