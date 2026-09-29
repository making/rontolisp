package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code (%elt-cell list index)}, the list arm of {@code elt} and of its
 * {@code setf} place: the list, then the index checked as an integer under {@code ELT}'s
 * name ({@code _ckIdx}), then a call to the {@code _eltCell} walk
 * ({@link JvmEltCellRuntimeBuilder}).
 */
final class JvmEltCellCompiler {

	private JvmEltCellCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.CK_IDX));
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(className), JvmEltCellRuntimeBuilder.METHOD,
				JvmEltCellRuntimeBuilder.DESC);
		ctx.body.invokestatic(ref);
	}

}
