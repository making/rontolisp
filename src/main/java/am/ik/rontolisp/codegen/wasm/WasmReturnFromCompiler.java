package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles {@code (return-from name [value])}: a LEXICAL named exit to the nearest
 * enclosing block whose name matches -- a user {@code (block name ...)} or the
 * {@code %fn-block} function boundary named after the defun -- crossing intervening
 * {@code %block} loop boundaries, which do not catch the named exit (mirroring the
 * interpreter's signal transparency). A {@code return-from} whose name matches no
 * enclosing block falls back to the {@code %fn-block} function boundary and exits the
 * current function: the compile-path deviation that keeps a {@code return-from} inside a
 * lambda a lambda-local exit (a {@code br} cannot cross into a separately compiled
 * function). {@code (return-from nil ...)} is plain {@code return} and delegates to
 * {@link WasmReturnCompiler}.
 *
 * <p>
 * EH mode: unlike plain {@code return} (whose escaped {@code unwind-protect} cleanups run
 * through the pre-built trampoline cascade, which always ends at the nearest
 * plain-{@code return} boundary), a named exit inlines the cleanup forms of every escaped
 * scope at the exit site, innermost first, then branches straight to the target block --
 * the same strategy (and the same documented lite limit) as {@code go}: a throw from such
 * an inlined cleanup can re-enter its own scope's handler.
 */
final class WasmReturnFromCompiler {

	private WasmReturnFromCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new IllegalArgumentException(LispNames.RETURN_FROM + " expects (return-from name [value])");
		}
		String name = LispMacroExpander.blockName(parts.get(1));
		if (name == null) {
			// (return-from nil v) is plain return: it targets the nearest plain
			// boundary, like the interpreter's unnamed signal.
			List<LispVal> returnParts = new java.util.ArrayList<>();
			returnParts.add(new am.ik.rontolisp.LispSymbol(LispNames.RETURN));
			if (parts.size() == 3) {
				returnParts.add(parts.get(2));
			}
			LispVal returnForm = am.ik.rontolisp.LispNil.INSTANCE;
			for (int i = returnParts.size() - 1; i >= 0; i--) {
				returnForm = new LispCons(returnParts.get(i), returnForm);
			}
			WasmReturnCompiler.compile((LispCons) returnForm, ctx);
			return;
		}
		WasmLispCompiler.BlockMarker target = findNamedTarget(ctx, name);
		if (target == null) {
			throw new UnsupportedOperationException(LispNames.RETURN_FROM + " " + name
					+ " has no lexically enclosing block: the compilers support return-from within the same function only");
		}
		int targetDepth = WasmReturnCompiler.blockStackDepthOf(ctx, target);
		if (parts.size() == 3) {
			// state-machine mode: the return value is a spine child (empty stack)
			WasmAsyncEmit.spine(parts.get(2), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		// Inline the cleanups of every unwind scope entered inside the target block,
		// innermost first -- the exit leaves their protected regions. The value stays
		// beneath on the operand stack (each cleanup's value is dropped).
		for (WasmLispCompiler.UnwindScope scope : ctx.unwindScopes) {
			if (scope.blockDepth() < targetDepth) {
				break;
			}
			WasmUnwindProtectCompiler.compileCleanups(scope.cleanupForms(), ctx);
		}
		// Restore every special-variable dynamic binding this exit escapes, innermost
		// first -- so a named exit from a scan closure does not leak the bound value
		// into the global.
		for (int[] bind : ctx.specialBindScopes) {
			if (bind[2] < targetDepth) {
				break;
			}
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bind[1]);
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(bind[0]);
		}
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - target.depth());
	}

	/**
	 * The nearest enclosing block marker named {@code name}, falling back to the nearest
	 * {@code %fn-block} function boundary when no name matches; null when neither
	 * encloses.
	 */
	private static WasmLispCompiler.@Nullable BlockMarker findNamedTarget(WasmLispCompiler.Ctx ctx, String name) {
		WasmLispCompiler.BlockMarker fallback = null;
		for (WasmLispCompiler.BlockMarker marker : ctx.blockMarkers) {
			if (name.equals(marker.name())) {
				return marker;
			}
			if (fallback == null && marker.functionBoundary()) {
				fallback = marker;
			}
		}
		return fallback;
	}

}
