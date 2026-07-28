package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The A-normalization step of the {@code --component} async state machines: an
 * {@code rontolisp:await} may only compile at a spine position (a statement, a
 * {@code let} init, an {@code if}/{@code while} test or branch, a {@code setq} value...)
 * where the wasm operand stack is empty, because a suspension abandons the stack and the
 * resume path cannot reconstruct partially evaluated operands. A strict function call
 * with an await somewhere in its arguments -- {@code (f a (await x) b)} -- is therefore
 * rewritten at its compile site into the equivalent {@code let*} hoist,
 * {@code (let* ((%await$1 a) (%await$2 (await x))) (f %await$1 %await$2 b))}: every
 * argument up to the last await-carrying one moves into a binding (evaluation order
 * preserved; self-evaluating literals stay in place), and the awaits land in init
 * positions, which are spine. Running the rewrite at the compile site (inside
 * {@code WasmExprCompiler.compileCons}) rather than as a pre-pass means macro EXPANSIONS
 * get normalized too.
 *
 * <p>
 * Only heads that evaluate all arguments left to right qualify: the CL special
 * forms/macros ({@link PackageRegistry#specialOperatorNames()}), the {@code %}-prefixed
 * internal forms and the rontolisp/usocket directive macros are excluded -- their awaits
 * are handled structurally by the async-aware form compilers, or rejected by the
 * spine-position check with a hint to hoist manually.
 */
final class WasmAwaitNormalizer {

	private WasmAwaitNormalizer() {
	}

	/**
	 * Returns the {@code let*} hoist of a strict call whose arguments contain awaits, or
	 * {@code null} when the form needs no rewrite (no argument awaits, or the head is not
	 * a strict call).
	 * @param cons the call form
	 * @param ctx the compilation context (supplies the hoist-name counter)
	 * @return the rewritten form, or {@code null}
	 */
	static @Nullable LispVal hoistCallArgs(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
			return null;
		}
		if (!isStrictCallHead(head.name())) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		int lastAwait = -1;
		for (int i = 1; i < parts.size(); i++) {
			if (WasmAwaitAnalysis.countAwaits(parts.get(i)) > 0) {
				lastAwait = i;
			}
		}
		if (lastAwait < 0) {
			return null;
		}
		// (let* ((%await$N argI)...) (head %await$N ... rest))
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> newParts = new ArrayList<>(parts.size());
		newParts.add(parts.get(0));
		for (int i = 1; i < parts.size(); i++) {
			LispVal arg = parts.get(i);
			if (i > lastAwait || isSelfEvaluating(arg)) {
				newParts.add(arg);
				continue;
			}
			LispSymbol temp = new LispSymbol("%await$" + (++ctx.asyncHoistCounter));
			bindings.add(list(temp, arg));
			newParts.add(temp);
		}
		LispVal call = fromList(newParts);
		return list(new LispSymbol(LispNames.LET_STAR), fromList(bindings), call);
	}

	/**
	 * Returns whether {@code name} heads a strict call (all arguments evaluated exactly
	 * once, left to right) that the hoist may rewrite.
	 */
	private static boolean isStrictCallHead(String name) {
		if (name.startsWith("%")) {
			// internal forms (%block, %error, %mv-*, ...) have non-value positions
			return false;
		}
		if (PackageRegistry.specialOperatorNames().contains(name)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		if (qn != null) {
			if (qn.member().startsWith("%")) {
				// The socket rewrite substitutes ORDINARY defuns under %-prefixed names,
				// so the reason the prefix excludes the rest (non-value positions) does
				// not hold for them: they take their arguments strictly.
				return WasmSocketsRewrite.strictDispatchMembers().contains(qn.member());
			}
			if (LispNames.RONTOLISP_PKG.equals(qn.pkg())) {
				// The rontolisp package's directive/special members; its ordinary
				// defuns (fetch, then, tcp-*, ...) are strict calls.
				return switch (qn.member()) {
					case LispNames.AWAIT, LispNames.ASYNC_DEFUN, LispNames.ASYNC_LAMBDA, LispNames.ASYNC_RUN,
							LispNames.WITH_ARENA, LispNames.WITH_MUTEX, LispNames.HTTP_HANDLER, LispNames.WASM_EXPORT,
							LispNames.WASM_IMPORT, LispNames.WIT_EXPORT, LispNames.WIT_IMPORT, LispNames.WIT_PROVIDE ->
						false;
					default -> true;
				};
			}
			if (LispNames.BORDEAUX_THREADS_PKG.equals(qn.pkg())) {
				// with-lock-held takes a lock SPEC and a body; the rest of the bt shim
				// (make-lock, acquire-lock, release-lock) are strict defuns.
				return !LispNames.WITH_LOCK_HELD.equals(qn.member());
			}
			if (LispNames.USOCKET_PKG.equals(qn.pkg())) {
				// the with-*/guard convenience macros bind variables
				return switch (qn.member()) {
					case LispNames.USOCKET_WITH_CLIENT_SOCKET, LispNames.USOCKET_WITH_CONNECTED_SOCKET,
							LispNames.USOCKET_WITH_SERVER_SOCKET, LispNames.USOCKET_WITH_SOCKET_LISTENER,
							LispNames.USOCKET_GUARD ->
						false;
					default -> true;
				};
			}
			return true;
		}
		return true;
	}

	private static boolean isSelfEvaluating(LispVal val) {
		return switch (val) {
			case LispInteger ignored -> true;
			case LispDouble ignored -> true;
			case LispRatio ignored -> true;
			case LispString ignored -> true;
			case LispChar ignored -> true;
			case LispNil ignored -> true;
			case LispTrue ignored -> true;
			case LispSymbol sym -> sym.isKeyword();
			case LispCons cons -> cons.car() instanceof LispSymbol head && LispNames.QUOTE.equals(head.name());
			default -> false;
		};
	}

	private static LispVal list(LispVal... elements) {
		LispVal out = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			out = new LispCons(elements[i], out);
		}
		return out;
	}

	private static LispVal fromList(List<LispVal> elements) {
		LispVal out = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			out = new LispCons(elements.get(i), out);
		}
		return out;
	}

}
