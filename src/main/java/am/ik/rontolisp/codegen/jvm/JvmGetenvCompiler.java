package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code %host-getenv} internal primitive: the HOST's value for an
 * environment variable as a string, or {@code nil} (a {@code null} reference at runtime)
 * when it is unset. The argument is a runtime string, which carries surrounding quotes
 * ({@code "PATH"}); the quotes are stripped before calling {@code System.getenv} and
 * re-applied to a non-null result so it is a proper Lisp string. The public
 * {@code uiop:getenv} is Lisp over this ({@code uiop-os.lisp}), consulting the override
 * map a {@code (setf (uiop:getenv ...))} wrote first.
 */
final class JvmGetenvCompiler {

	private JvmGetenvCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.HOST_GETENV + " expects 1 argument, got " + (args.size() - 1));
		}
		final int length = JvmEmitHelper.stringMethod(ctx, "length", "()I").index();
		final int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		final int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();

		JvmExprCompiler.compileExpr(args.get(1), ctx, className); // [s]
		// A variable name built by a string producer (concatenate, format nil) is a
		// mutable character vector: render it before the (String) cast (a no-op
		// without the array runtime).
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		// name = s.substring(1, s.length() - 1)
		ctx.emit(Opcode.DUP); // [s, s]
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(length); // [s, len]
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISUB); // [s, len-1]
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.SWAP); // [s, 1, len-1]
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(substring); // [name]
		// System.getenv(name)
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.systemOp("getenv").index()); // [value|null]
		ctx.emit(Opcode.DUP); // [value, value]
		final int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0); // [value]
		// non-null: wrap as "\"" + value + "\""
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
		// null path: leave the null (nil) on the stack
		final int nullStart = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, ifNullPos, nullStart);
		final int endPos = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, endPos);
	}

}
