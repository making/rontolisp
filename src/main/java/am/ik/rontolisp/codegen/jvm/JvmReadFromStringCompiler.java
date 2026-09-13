package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
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
		emitRead(cons, ctx, className, LispNames.READ_FROM_STRING);
	}

	/**
	 * Compiles {@code (%read-from-string-end s)} -- the stop index {@code
	 * read-from-string} answers as its SECOND value, emitted only by the multiple-value
	 * lowering of a {@code read-from-string} producer. The datum is parsed and thrown
	 * away, and the reader's cursor is the answer: the emitted reader has no suppressed
	 * mode, so here the index exists exactly where the datum parses (the interpreter
	 * scans instead, and so answers for text the parse refuses -- see
	 * {@code .kb/read-load-streams.md}).
	 * @param cons the call form
	 * @param ctx the compilation context
	 * @param className the enclosing class name
	 */
	static void compileEnd(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		emitRead(cons, ctx, className, LispNames.READ_FROM_STRING_END);
		ctx.emit(Opcode.POP);
		FieldrefConstant pos = ctx.cp.addFieldref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_readPos"), ctx.cp.addUtf8("I")));
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(pos.index());
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	private static void emitRead(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String op) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new UnsupportedOperationException(op + " expects at least 1 argument");
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
