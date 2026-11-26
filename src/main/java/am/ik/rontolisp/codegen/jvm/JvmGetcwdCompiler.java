package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
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
		ClassEntry systemClass = ctx.cp.classEntry("java/lang/System");
		MethodRefEntry getProperty = ctx.cp.methodRef(systemClass, "getProperty",
				"(Ljava/lang/String;)Ljava/lang/String;");
		final MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat",
				"(Ljava/lang/String;)Ljava/lang/String;");
		// System.getProperty("user.dir") -- the raw host string, no Lisp quotes.
		JvmEmitHelper.compileUnspelledLiteral("user.dir", ctx);
		ctx.body.invokestatic(getProperty); // [value|null]
		ctx.body.dup(); // [value, value]
		MethodCode.Label end = ctx.body.newLabel();
		ctx.body.ifnull(end);
		// non-null: wrap as "\"" + value + "\"", the runtime string representation.
		JvmEmitHelper.compileStringLiteral("\"", ctx); // [value, q]
		ctx.body.swap(); // [q, value]
		ctx.body.invokevirtual(concat); // [q+value]
		JvmEmitHelper.compileStringLiteral("\"", ctx); // [.., q]
		ctx.body.invokevirtual(concat); // [quoted]
		ctx.body.goto_(end);
		// null path: leave the null (nil) on the stack.
		ctx.body.labelBinding(end);
	}

}
