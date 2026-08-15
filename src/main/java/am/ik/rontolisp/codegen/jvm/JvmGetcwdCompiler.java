package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %host-getcwd} internal primitive: the JVM's {@code user.dir} as a
 * runtime string (which carries its surrounding quotes), or nil when the property is
 * absent. The public {@code uiop:getcwd} is Lisp over this ({@code uiop-os.lisp}) and
 * turns a nil answer into the {@code not-implemented-error} the WASM backends get, so all
 * four share one definition and one message.
 *
 * <p>
 * The constant-pool entries are minted HERE rather than in the compiler's fixed
 * {@code systemOps} table ({@link JvmSleepCompiler}'s rule), so a program that never asks
 * for the working directory emits the same bytes as before.
 */
final class JvmGetcwdCompiler {

	private JvmGetcwdCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 1) {
			throw new UnsupportedOperationException(
					LispNames.HOST_GETCWD + " expects no arguments, got " + (parts.size() - 1));
		}
		ClassConstant systemClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/System"));
		MethodrefConstant getProperty = ctx.cp.addMethodref(systemClass, ctx.cp
			.addNameAndType(ctx.cp.addUtf8("getProperty"), ctx.cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		final int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		// System.getProperty("user.dir") -- the raw host string, no Lisp quotes.
		JvmEmitHelper.compileUnspelledLiteral("user.dir", ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(getProperty.index()); // [value|null]
		ctx.emit(Opcode.DUP); // [value, value]
		final int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0); // [value]
		// non-null: wrap as "\"" + value + "\"", the runtime string representation.
		JvmEmitHelper.compileStringLiteral("\"", ctx); // [value, q]
		ctx.emit(Opcode.SWAP); // [q, value]
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat); // [q+value]
		JvmEmitHelper.compileStringLiteral("\"", ctx); // [.., q]
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat); // [quoted]
		final int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// null path: leave the null (nil) on the stack.
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
