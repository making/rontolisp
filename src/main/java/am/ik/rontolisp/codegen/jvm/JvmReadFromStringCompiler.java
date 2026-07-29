package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code read-from-string} built-in. Parses one datum from the string
 * argument via the {@code _readFromString} runtime helper (which reuses the embedded
 * reader). The optional {@code eof-error-p}/{@code eof-value} and {@code :start}/
 * {@code :end} arguments of Common Lisp are not supported.
 */
final class JvmReadFromStringCompiler {

	private JvmReadFromStringCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new UnsupportedOperationException("read-from-string expects at least 1 argument");
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// The source may be a mutable character vector (a filled make-string buffer);
		// _readFromString casts to String, so normalize first.
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)), ctx.cp.addNameAndType(
				ctx.cp.addUtf8("_readFromString"), ctx.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
