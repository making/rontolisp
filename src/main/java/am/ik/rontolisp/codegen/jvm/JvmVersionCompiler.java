package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.VersionInfo;

/**
 * Compiles {@code (rontolisp:version)} by emitting the constant version property list
 * through the existing quote machinery. The version values are compile-time constants.
 */
final class JvmVersionCompiler {

	private JvmVersionCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal quoteForm = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(VersionInfo.plist(), LispNil.INSTANCE));
		JvmExprCompiler.compileExpr(quoteForm, ctx, className);
	}

}
