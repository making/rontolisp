package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code (return-from name [value])}: a LEXICAL named exit to the nearest
 * enclosing block whose name matches -- a user {@code (block name ...)} or the
 * {@code %fn-block} function boundary named after the defun -- crossing intervening
 * {@code %block} loop boundaries, which do not catch the named exit (mirroring the
 * interpreter's signal transparency). A {@code return-from} whose name matches no
 * enclosing block falls back to the {@code %fn-block} function boundary and exits the
 * current function: the compile-path deviation that keeps a {@code return-from} inside a
 * lambda a lambda-local exit (a goto cannot cross into a separately compiled method).
 * {@code (return-from nil ...)} is plain {@code return}. The emit sequence (value store,
 * escaped unwind-protect cleanups with hole recording, operand-stack unwind, patched
 * goto) is {@link JvmReturnCompiler#emitExit}.
 */
final class JvmReturnFromCompiler {

	private JvmReturnFromCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new IllegalArgumentException(LispNames.RETURN_FROM + " expects (return-from name [value])");
		}
		String name = LispMacroExpander.blockName(parts.get(1));
		LispVal valueForm = parts.size() == 3 ? parts.get(2) : null;
		if (name == null) {
			// (return-from nil v) is plain return: it targets the nearest plain
			// boundary, like the interpreter's unnamed signal.
			int plainDepth = JvmReturnCompiler.findPlainTargetDepth(ctx);
			if (plainDepth == 0) {
				throw new IllegalStateException("Cannot compile return outside of a loop block");
			}
			JvmReturnCompiler.emitExit(valueForm, ctx, className, plainDepth);
			return;
		}
		int targetDepth = findNamedTargetDepth(ctx, name);
		if (targetDepth == 0) {
			throw new UnsupportedOperationException(LispNames.RETURN_FROM + " " + name
					+ " has no lexically enclosing block: the compilers support return-from within the same function only");
		}
		JvmReturnCompiler.emitExit(valueForm, ctx, className, targetDepth);
	}

	/**
	 * The 1-based depth (from the bottom of the block stack) of the nearest enclosing
	 * block named {@code name}, falling back to the nearest {@code %fn-block} function
	 * boundary when no name matches; 0 when neither encloses.
	 */
	private static int findNamedTargetDepth(JvmLispCompiler.Ctx ctx, String name) {
		int idxFromTop = 0;
		int fallbackDepth = 0;
		for (JvmLispCompiler.BlockTarget target : ctx.blockTargets) {
			int depth = ctx.blockTargets.size() - idxFromTop;
			if (name.equals(target.name())) {
				return depth;
			}
			if (fallbackDepth == 0 && target.functionBoundary()) {
				fallbackDepth = depth;
			}
			idxFromTop++;
		}
		return fallbackDepth;
	}

}
