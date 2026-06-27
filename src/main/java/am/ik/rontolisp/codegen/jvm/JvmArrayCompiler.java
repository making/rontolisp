package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the array built-ins ({@code make-array}, {@code aref}, {@code %aset}). Each
 * pushes its arguments and calls the matching static runtime helper emitted by
 * {@link JvmArrayRuntimeBuilder}. {@code make-array} resolves the
 * {@code :initial-element} keyword at compile time; {@code aref}/{@code %aset} pick the
 * rank-1 or rank-2 helper by the number of subscripts (only ranks 1 and 2 are supported).
 */
final class JvmArrayCompiler {

	private JvmArrayCompiler() {
	}

	static void compileMake(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2) {
			throw new UnsupportedOperationException("make-array expects at least 1 argument");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		LispVal init = findInitialElement(args);
		if (init != null) {
			JvmExprCompiler.compileExpr(init, ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.MAKE, JvmArrayRuntimeBuilder.MAKE_DESC);
	}

	static void compileAref(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int rank = args.size() - 2;
		if (rank == 1) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.AREF1, JvmArrayRuntimeBuilder.AREF1_DESC);
		}
		else if (rank == 2) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.AREF2, JvmArrayRuntimeBuilder.AREF2_DESC);
		}
		else {
			throw new UnsupportedOperationException("aref supports rank 1 and 2 only, got rank " + rank);
		}
	}

	static void compileAset(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%aset array subscript... value)
		List<LispVal> args = cons.toList();
		int rank = args.size() - 3;
		LispVal value = args.get(args.size() - 1);
		if (rank == 1) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmExprCompiler.compileExpr(value, ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.ASET1, JvmArrayRuntimeBuilder.ASET1_DESC);
		}
		else if (rank == 2) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
			JvmExprCompiler.compileExpr(value, ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.ASET2, JvmArrayRuntimeBuilder.ASET2_DESC);
		}
		else {
			throw new UnsupportedOperationException("(setf aref) supports rank 1 and 2 only, got rank " + rank);
		}
	}

	private static @Nullable LispVal findInitialElement(List<LispVal> args) {
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && LispNames.INITIAL_ELEMENT_KEYWORD.equals(kw.name())) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	private static void invokeHelper(JvmLispCompiler.Ctx ctx, String className, String name, String desc) {
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
