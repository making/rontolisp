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
 * {@code :initial-element} keyword at compile time; {@code aref}/{@code %aset} pick a
 * helper by the number of subscripts: ranks 1 and 2 call dedicated fast helpers, higher
 * ranks package the subscripts into an {@code Object[]} for the generic {@code _arefN}/
 * {@code _asetN}.
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
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			emitSubscriptArray(args, 2, rank, ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.AREFN, JvmArrayRuntimeBuilder.AREFN_DESC);
		}
	}

	static void compileRowMajorAref(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (row-major-aref array index): the data is stored flat right after the header,
		// so this is exactly the rank-1 accessor, independent of the array's rank.
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(
					"row-major-aref expects an array and an index, got " + (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.AREF1, JvmArrayRuntimeBuilder.AREF1_DESC);
	}

	static void compileRowMajorAset(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%row-major-aset array index value): flat store, the rank-1 setter.
		List<LispVal> args = cons.toList();
		if (args.size() != 4) {
			throw new UnsupportedOperationException("%row-major-aset expects an array, an index and a value, got "
					+ (args.size() - 1) + " argument(s)");
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.ASET1, JvmArrayRuntimeBuilder.ASET1_DESC);
	}

	static void compileDims(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("array-dimensions expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, JvmArrayRuntimeBuilder.DIMS, JvmArrayRuntimeBuilder.DIMS_DESC);
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
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			emitSubscriptArray(args, 2, rank, ctx, className);
			JvmExprCompiler.compileExpr(value, ctx, className);
			invokeHelper(ctx, className, JvmArrayRuntimeBuilder.ASETN, JvmArrayRuntimeBuilder.ASETN_DESC);
		}
	}

	// Packages the rank subscript expressions starting at args[firstSub] into an
	// Object[] (evaluated left to right) for the generic _arefN/_asetN helpers.
	private static void emitSubscriptArray(List<LispVal> args, int firstSub, int rank, JvmLispCompiler.Ctx ctx,
			String className) {
		JvmEmitHelper.emitIntConst(ctx, rank);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		for (int i = 0; i < rank; i++) {
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, i);
			JvmExprCompiler.compileExpr(args.get(firstSub + i), ctx, className);
			ctx.emit(Opcode.AASTORE);
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
