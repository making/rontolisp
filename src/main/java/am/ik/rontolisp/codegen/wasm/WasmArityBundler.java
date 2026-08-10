package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Rewrites fixed-arity {@code defun}s with more parameters than the WASM callable-type
 * limit ({@link WasmLispCompiler#MAX_CALLABLE_ARITY}) into the "bundle the extra
 * arguments into a list" shape the limit's error message suggests -- automatically, so
 * real-library code with wide helper signatures (e.g. split-sequence's 10-parameter
 * {@code split-list}) compiles without source changes. Raising the limit itself would
 * shift the type/function indices the pinned {@code --component} adapter blobs depend on,
 * so the transform stays at the AST level:
 *
 * <pre>
 * (defun f (p1 .. p10) body)          -> (defun f (p1 .. p6 %bundle)
 *                                          (let* ((p7 (nth 0 %bundle)) .. (p10 (nth 3 %bundle)))
 *                                            body))
 * (f a1 .. a10)                       -> (f a1 .. a6 (list a7 .. a10))
 * </pre>
 *
 * Only DIRECT calls are rewritten (in Lisp-2 a head-position symbol is unambiguous), so a
 * first-class reference ({@code #'f}, {@code symbol-function}) to a bundled function is
 * rejected with a clear error -- the arity dispatchers only exist up to the limit.
 * Variadic ({@code &rest}) definitions past the limit keep the existing hard error, and a
 * {@code flet}/{@code labels} binding of the same name shadows the rewrite inside its
 * form.
 */
final class WasmArityBundler {

	private static final String BUNDLE_VAR = "%arity-bundle";

	private WasmArityBundler() {
	}

	/**
	 * Applies the transform: bundles every too-wide fixed-arity defun and rewrites its
	 * direct call sites. Runs after lambda-list desugaring (so only the native "required
	 * + &rest" shape appears) and before compilation.
	 * @param program the top-level forms
	 * @return the transformed program (the same list when nothing is too wide)
	 */
	static List<LispVal> bundle(List<LispVal> program) {
		// name -> original parameter count of each too-wide fixed-arity defun.
		Map<String, Integer> wide = new HashMap<>();
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(op.name()) && cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				if (parts.size() >= 3 && parts.get(1) instanceof LispSymbol name
						&& (parts.get(2) instanceof LispCons || parts.get(2) instanceof LispNil)) {
					List<LispVal> params = parts.get(2) instanceof LispCons paramsCons ? paramsCons.toList()
							: List.of();
					if (params.size() > WasmLispCompiler.MAX_CALLABLE_ARITY
							&& params.stream().allMatch(WasmArityBundler::isPlainParam)) {
						wide.put(name.name(), params.size());
					}
				}
			}
		}
		if (wide.isEmpty()) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(rewrite(form, wide));
		}
		return out;
	}

	private static boolean isPlainParam(LispVal param) {
		return param instanceof LispSymbol sym && !sym.name().startsWith("&");
	}

	/**
	 * Lowers a {@code funcall} with more arguments than
	 * {@link WasmLispCompiler#MAX_CALLABLE_ARITY} into the equivalent {@code apply}:
	 *
	 * <pre>
	 * (funcall f a1 .. a11) -> (apply f (list a1 .. a11))
	 * </pre>
	 *
	 * The per-arity dispatchers take one WASM parameter per Lisp argument and so stop at
	 * the limit, but {@code _apply} does not: it hands the whole argument list to the
	 * SPREAD dispatcher ({@link WasmLispCompiler#FUNC_DISPATCH_SPREAD}), which reads each
	 * target's parameters back out of the list. That mechanism already exists for
	 * {@code apply} through a computed designator; this pass is what lets {@code funcall}
	 * reach it too, instead of compiling to a call-time "not supported" signal.
	 *
	 * <p>
	 * It has to be an AST pass rather than a codegen branch because the injected
	 * {@code apply} is what turns the eval runtime on -- {@code usesEval} is a scan of
	 * the program, and it runs after this one.
	 *
	 * <p>
	 * A keyword lambda list is the shape that reaches the limit in practice: the
	 * arguments are passed through verbatim for the callee's own dispatcher to parse, so
	 * chipz's {@code (funcall fun state input output :input-start s :input-end e
	 * :output-start s :output-end e)} is eleven of them for a function whose lambda list
	 * has seven parameters.
	 * @param program the top-level forms
	 * @return the transformed program (the same list when no funcall is too wide)
	 */
	static List<LispVal> spreadOverArityFuncalls(List<LispVal> program) {
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			LispVal rewritten = spread(form);
			changed |= rewritten != form;
			out.add(rewritten);
		}
		return changed ? out : program;
	}

	private static LispVal spread(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		boolean quoted = cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name());
		if (quoted) {
			return form;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> out = new ArrayList<>(parts.size());
		for (LispVal part : parts) {
			out.add(spread(part));
		}
		if (cons.car() instanceof LispSymbol op && LispNames.FUNCALL.equals(op.name())
				&& out.size() - 2 > WasmLispCompiler.MAX_CALLABLE_ARITY) {
			List<LispVal> listParts = new ArrayList<>();
			listParts.add(new LispSymbol(LispNames.LIST));
			listParts.addAll(out.subList(2, out.size()));
			return listToCons(List.of(new LispSymbol(LispNames.APPLY), out.get(1), listToCons(listParts)));
		}
		return LispCons.rebuiltList(cons, out);
	}

	private static LispVal rewrite(LispVal form, Map<String, Integer> wide) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		if (!(cons.car() instanceof LispSymbol op)) {
			return rewriteElements(cons, cons.toList(), 0, wide);
		}
		String name = op.name();
		if (LispNames.QUOTE.equals(name)) {
			return form;
		}
		if (LispNames.DEFUN.equals(name)) {
			List<LispVal> parts = cons.toList();
			if (parts.size() >= 3 && parts.get(1) instanceof LispSymbol defName && wide.containsKey(defName.name())) {
				return bundleDefun(parts, wide);
			}
			return rewriteElements(cons, parts, 3, wide);
		}
		if ((LispNames.FLET.equals(name) || LispNames.LABELS.equals(name)) && cons.toList().size() >= 2) {
			// A local function shadows a bundled global of the same name for the
			// whole form; the shadowed names' call sites must stay untouched there.
			List<LispVal> parts = cons.toList();
			Set<String> shadowed = new HashSet<>();
			if (parts.get(1) instanceof LispCons defs) {
				for (LispVal def : defs.toList()) {
					if (def instanceof LispCons defCons && defCons.car() instanceof LispSymbol localName) {
						shadowed.add(localName.name());
					}
				}
			}
			if (shadowed.stream().anyMatch(wide::containsKey)) {
				Map<String, Integer> visible = new HashMap<>(wide);
				visible.keySet().removeAll(shadowed);
				return visible.isEmpty() ? form : rewrite(form, visible);
			}
			return rewriteElements(cons, parts, 1, wide);
		}
		if (LispNames.FUNCTION.equals(name) || LispNames.SYMBOL_FUNCTION.equals(name)) {
			List<LispVal> parts = cons.toList();
			LispVal target = parts.size() == 2 ? parts.get(1) : LispNil.INSTANCE;
			String referenced = target instanceof LispSymbol sym ? sym.name() : quotedName(target);
			if (referenced != null && wide.containsKey(referenced)) {
				throw new UnsupportedOperationException("Cannot take a function value of '" + referenced + "': its "
						+ wide.get(referenced) + "-parameter signature exceeds the WASM limit of "
						+ WasmLispCompiler.MAX_CALLABLE_ARITY + " and was bundled, which only direct calls support");
			}
			return rewriteElements(cons, parts, 1, wide);
		}
		Integer arity = wide.get(name);
		List<LispVal> parts = cons.toList();
		if (arity != null && parts.size() == arity + 1) {
			// (f a1 .. aN) -> (f a1 .. a6 (list a7 .. aN)), arguments rewritten too.
			List<LispVal> out = new ArrayList<>();
			out.add(op);
			for (int i = 1; i <= WasmLispCompiler.MAX_CALLABLE_ARITY - 1; i++) {
				out.add(rewrite(parts.get(i), wide));
			}
			List<LispVal> bundleParts = new ArrayList<>();
			bundleParts.add(new LispSymbol(LispNames.LIST));
			for (int i = WasmLispCompiler.MAX_CALLABLE_ARITY; i < parts.size(); i++) {
				bundleParts.add(rewrite(parts.get(i), wide));
			}
			out.add(listToCons(bundleParts));
			return listToCons(out);
		}
		return rewriteElements(cons, parts, 1, wide);
	}

	private static LispVal bundleDefun(List<LispVal> parts, Map<String, Integer> wide) {
		List<LispVal> params = ((LispCons) parts.get(2)).toList();
		int keep = WasmLispCompiler.MAX_CALLABLE_ARITY - 1;
		List<LispVal> newParams = new ArrayList<>(params.subList(0, keep));
		LispSymbol bundle = new LispSymbol(BUNDLE_VAR);
		newParams.add(bundle);
		List<LispVal> letBindings = new ArrayList<>();
		for (int i = keep; i < params.size(); i++) {
			LispVal nth = listToCons(List.of(new LispSymbol(LispNames.NTH), new LispInteger(i - keep), bundle));
			letBindings.add(listToCons(List.of(params.get(i), nth)));
		}
		List<LispVal> letParts = new ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET_STAR));
		letParts.add(listToCons(letBindings));
		for (int i = 3; i < parts.size(); i++) {
			letParts.add(rewrite(parts.get(i), wide));
		}
		return listToCons(List.of(parts.get(0), parts.get(1), listToCons(newParams), listToCons(letParts)));
	}

	// Identity-preserving (LispCons.rebuilt): a form with no bundled call in it is
	// handed back as it came in, so its SourceProvenance position survives the pass.
	private static LispVal rewriteElements(LispCons original, List<LispVal> parts, int from,
			Map<String, Integer> wide) {
		List<LispVal> out = new ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			out.add(i < from ? parts.get(i) : rewrite(parts.get(i), wide));
		}
		return LispCons.rebuiltList(original, out);
	}

	private static LispVal listToCons(List<LispVal> items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			result = new LispCons(items.get(i), result);
		}
		return result;
	}

	/** The symbol name inside a {@code (quote name)} designator, or null. */
	@Nullable private static String quotedName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name())
				&& cons.cdr() instanceof LispCons datumCell && datumCell.car() instanceof LispSymbol datum) {
			return datum.name();
		}
		return null;
	}

}
