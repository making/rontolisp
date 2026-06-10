package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageIntrospection;

/**
 * Compiles {@code (rontolisp:list-functions)} / {@code (rontolisp:list-macros)} /
 * {@code (rontolisp:list-special-forms)} by emitting the constant symbol list through the
 * existing quote machinery, like {@link WasmVersionCompiler}: no new runtime function is
 * added (the fixed WASM function indices stay stable).
 * {@link am.ik.rontolisp.PackageResolver} has already normalized the optional
 * package-designator argument to a keyword literal, so the {@code cl-user} listing is a
 * compile-time snapshot of the Pass-1 user defun names.
 */
final class WasmIntrospectionCompiler {

	private WasmIntrospectionCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		String pkg = packageOf(member, cons);
		List<String> names = PackageIntrospection.listNames(member, pkg, ctx.userDefunNames);
		LispVal quoteForm = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(PackageIntrospection.symbolList(names), LispNil.INSTANCE));
		WasmExprCompiler.compileExpr(quoteForm, ctx);
	}

	private static String packageOf(String member, LispCons cons) {
		if (!(cons.cdr() instanceof LispCons argCell)) {
			return LispNames.CL_PKG;
		}
		if (argCell.car() instanceof LispSymbol sym && sym.isKeyword() && argCell.cdr() instanceof LispNil) {
			return sym.name().substring(1);
		}
		throw new UnsupportedOperationException(
				"Cannot compile " + member + ": expects a package-designator keyword literal");
	}

}
