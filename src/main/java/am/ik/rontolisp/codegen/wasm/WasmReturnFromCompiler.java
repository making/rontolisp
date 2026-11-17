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
 * EH mode: an escaped {@code unwind-protect} whose protected form holds a named exit has
 * a NAMED trampoline lexically outside every handler inside it
 * ({@link WasmUnwindProtectCompiler}): the exit names its target in the scope's selector
 * and branches there, and the trampoline runs the cleanups and goes on toward the target,
 * through the next such scope. The cleanups of the scopes before the first such one -- a
 * {@code handler-case}'s depth counter, a special {@code let}'s restores, none of which
 * throws -- are inlined at the exit site, innermost first. Inlining a user cleanup there,
 * as {@code go} still does, left it inside the handlers the exit was leaving: an error of
 * the cleanup was taken by them, the region's own pad running the cleanup a second time.
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
			// The value is the block's; when the block is in tail position and no
			// protected region's cleanups follow it, it is the function's too
			// (Ctx.tailPosition).
			WasmLispCompiler.UnwindScope innermost = ctx.unwindScopes.peek();
			ctx.tailPosition = target.tail() && (innermost == null || innermost.blockDepth() < targetDepth);
			// state-machine mode: the return value is a spine child (empty stack)
			WasmAsyncEmit.spine(parts.get(2), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		exit(target, ctx.unwindScopes, ctx);
	}

	/**
	 * Leaves the scopes entered inside the target block, innermost first, with the value
	 * on the operand stack: to the named trampoline of the first that has one, which goes
	 * on from there, else straight to the block. The cleanups of the scopes before it are
	 * inlined (each cleanup's value is dropped; the value stays beneath).
	 * @param target the block the exit goes to
	 * @param scopes the unwind scopes around the exit, innermost first
	 * @param ctx the compilation context
	 */
	static void exit(WasmLispCompiler.BlockMarker target, Iterable<WasmLispCompiler.UnwindScope> scopes,
			WasmLispCompiler.Ctx ctx) {
		int targetDepth = WasmReturnCompiler.blockStackDepthOf(ctx, target);
		for (WasmLispCompiler.UnwindScope scope : scopes) {
			if (scope.blockDepth() < targetDepth) {
				break;
			}
			if (scope.namedTrampolineDepth() >= 0) {
				if (!scope.namedTargets().contains(target)) {
					scope.namedTargets().add(target);
				}
				selector(targetDepth, ctx);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(scope.selectorSlot());
				ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - scope.namedTrampolineDepth());
				return;
			}
			WasmUnwindProtectCompiler.compileCleanups(scope.cleanupForms(), ctx);
		}
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - target.depth());
	}

	/**
	 * Pushes the selector naming a target block: its block-stack depth as an
	 * {@code i31ref}, which {@code ref.eq} compares.
	 */
	static void selector(int targetDepth, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(targetDepth);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/**
	 * Whether a form holds a named {@code return-from}: an {@code unwind-protect} around
	 * it gives such exits a named trampoline. Quoted data and nested lambdas are walked
	 * too; a trampoline nothing branches to costs bytes only.
	 * @param form the protected form
	 * @return whether some {@code (return-from name ...)} with a name stands in it
	 */
	static boolean holdsNamedExit(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof am.ik.rontolisp.LispSymbol head && LispNames.RETURN_FROM.equals(head.name())
				&& cons.cdr() instanceof LispCons rest && LispMacroExpander.blockName(rest.car()) != null) {
			return true;
		}
		LispVal rest = cons;
		while (rest instanceof LispCons each) {
			if (holdsNamedExit(each.car())) {
				return true;
			}
			rest = each.cdr();
		}
		return false;
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
