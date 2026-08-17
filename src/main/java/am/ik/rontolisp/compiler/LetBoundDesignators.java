package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;

/**
 * The backend-shared rewrite that keeps a designator BOUND to a temp from becoming a
 * function value: a {@code let} binding whose init is a literal designator
 * ({@link FunctionDesignators#literalName}) naming a registered function, which the body
 * only ever uses in a function-designator position, is propagated to those uses and the
 * binding dropped.
 *
 * <p>
 * Every expander that names a designator to avoid re-evaluating it binds one --
 * {@code expandMap} (which {@code coerce} emits for every string it converts),
 * {@code expandMapFamily}, {@code expandEverySomeFamily} -- and the binding is what put
 * the funcId back into {@code valueFuncIds}, giving the arity ladder a case again and
 * pinning everything that case reaches for the tree shaker
 * ({@code .kb/optimize-dead-code-elimination.md}). Doing it here rather than in each
 * expander is what keeps the interpreter out of it: it never sees the rewrite, so it
 * still evaluates the designator once per call rather than once per element, and an
 * undefined function still signals over an empty sequence.
 *
 * <p>
 * <b>Why an occurrence anywhere else disqualifies the binding.</b> The pass certifies the
 * occurrences it understands (the designator argument of the six operators the backends
 * resolve, {@code Jvm/WasmDesignatorCall}) and separately counts EVERY occurrence of the
 * name in the body -- quoted data, binding lists, dotted tails and all. The binding is
 * rewritten only when the two agree, so the substitution can then replace the symbol
 * wherever it appears: anything the certifying walk does not understand -- a shadowing
 * inner binding, a {@code setq}, a use as a plain value, a {@code (funcall f ...)} shaped
 * LIST that is a datum rather than a call -- shows up as an occurrence with no
 * certification and keeps the binding exactly as it was.
 */
public final class LetBoundDesignators {

	private LetBoundDesignators() {
	}

	/**
	 * Propagates every qualifying binding of {@code letForm} into its body and drops it.
	 * @param letForm the {@code (let bindings body...)} form
	 * @param specialVars the dynamically bound names: a {@code let} of one of those is a
	 * binding a CALLEE can read, so it is never dropped
	 * @param knownFunctions the names the backend's function registry answers -- a
	 * designator naming anything else is left bound, because the value it stands for is
	 * then not a plain funcId (a car/cdr composition synthesizes a fresh lambda per site,
	 * {@code --dynamic} resolves the name at run time) and duplicating it would cost more
	 * than the binding
	 * @return the rewritten form, or {@code letForm} itself when no binding qualifies
	 */
	public static LispCons propagate(LispCons letForm, Set<String> specialVars, Set<String> knownFunctions) {
		if (!letForm.isProperList()) {
			return letForm;
		}
		List<LispVal> parts = letForm.toList();
		if (parts.size() < 3 || !(parts.get(1) instanceof LispCons bindings) || !bindings.isProperList()) {
			return letForm;
		}
		Map<String, LispVal> propagated = candidates(bindings, specialVars, knownFunctions);
		if (propagated.isEmpty()) {
			return letForm;
		}
		List<LispVal> body = parts.subList(2, parts.size());
		propagated.keySet().removeIf(name -> !onlyDesignatorUses(body, name));
		if (propagated.isEmpty()) {
			return letForm;
		}
		List<LispVal> kept = new ArrayList<>();
		for (LispVal binding : bindings.toList()) {
			String name = bindingName(binding);
			if (name == null || !propagated.containsKey(name)) {
				kept.add(binding);
			}
		}
		List<LispVal> rewritten = new ArrayList<>();
		rewritten.add(parts.get(0));
		rewritten.add(kept.isEmpty() ? LispNil.INSTANCE : LispCons.rebuiltList(bindings, kept));
		for (LispVal form : body) {
			rewritten.add(substitute(form, propagated));
		}
		return SourceProvenance.inherit(letForm, (LispCons) LispCons.rebuiltList(letForm, rewritten));
	}

	/** The bindings that hold a literal designator this pass may propagate. */
	private static Map<String, LispVal> candidates(LispCons bindings, Set<String> specialVars,
			Set<String> knownFunctions) {
		Set<String> bound = new LinkedHashSet<>();
		Set<String> duplicated = new LinkedHashSet<>();
		for (LispVal binding : bindings.toList()) {
			String name = bindingName(binding);
			if (name != null && !bound.add(name)) {
				// (let ((f #'car) (f 1)) ...): which one the body reads is not this
				// pass's call to make.
				duplicated.add(name);
			}
		}
		Map<String, LispVal> found = new LinkedHashMap<>();
		for (LispVal binding : bindings.toList()) {
			if (!(binding instanceof LispCons pair) || !pair.isProperList()) {
				continue;
			}
			List<LispVal> spec = pair.toList();
			if (spec.size() != 2 || !(spec.get(0) instanceof LispSymbol nameSym)) {
				continue;
			}
			String name = nameSym.name();
			String function = FunctionDesignators.literalName(spec.get(1));
			if (function == null || duplicated.contains(name) || specialVars.contains(name)
					|| !knownFunctions.contains(function)) {
				continue;
			}
			found.put(name, FunctionDesignators.normalize(spec.get(1)));
		}
		return found;
	}

	/** The name a binding entry binds: a bare symbol binds itself to nil. */
	private static @Nullable String bindingName(LispVal binding) {
		if (binding instanceof LispSymbol bare) {
			return bare.name();
		}
		return binding instanceof LispCons pair && pair.car() instanceof LispSymbol name ? name.name() : null;
	}

	/**
	 * Whether every occurrence of {@code name} in {@code body} is a certified
	 * function-designator position, and there is at least one.
	 */
	private static boolean onlyDesignatorUses(List<LispVal> body, String name) {
		int occurrences = 0;
		int certified = 0;
		for (LispVal form : body) {
			occurrences += occurrences(form, name);
			certified += designatorUses(form, name);
		}
		return certified > 0 && certified == occurrences;
	}

	/**
	 * Every occurrence of the symbol in the tree, shape-blind on purpose: this is the
	 * count the certified one is checked against, so it must not skip a position the
	 * certifying walk might have misread -- quoted data, a binding name, a dotted tail.
	 */
	private static int occurrences(LispVal form, String name) {
		int found = 0;
		LispVal current = form;
		while (current instanceof LispCons cons) {
			found += occurrences(cons.car(), name);
			current = cons.cdr();
		}
		return found + (current instanceof LispSymbol sym && name.equals(sym.name()) ? 1 : 0);
	}

	/**
	 * The occurrences of {@code name} this pass certifies as an evaluated
	 * function-designator argument. Descending into a position that turns out not to be
	 * evaluated is harmless -- the occurrence is simply not certified and the count check
	 * refuses the binding -- so only the heads that carry DATA need to be opaque.
	 */
	private static int designatorUses(LispVal form, String name) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return 0;
		}
		List<LispVal> parts = cons.toList();
		if (!(parts.get(0) instanceof LispSymbol head)) {
			// ((lambda (x) ...) 1): the head is a form, evaluated like every element.
			return sumUses(parts, 0, name);
		}
		return switch (head.name()) {
			// Data lives under these: a (funcall f ...) shaped list inside one is a
			// datum, not a call site, so nothing under them may be certified.
			case LispNames.QUOTE, LispNames.DECLARE, LispNames.DEFMACRO, LispNames.DEFSTRUCT, LispNames.DEFCLASS,
					LispNames.DEFGENERIC, LispNames.DEFMETHOD, LispNames.DEFPACKAGE ->
				0;
			// The parameter list is not read, and a parameter of this name SHADOWS the
			// binding -- which the occurrence count then refuses.
			case LispNames.LAMBDA -> sumUses(parts, 2, name);
			case LispNames.DEFUN -> sumUses(parts, 3, name);
			case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE,
					LispNames.CTYPECASE ->
				caseUses(parts, name);
			default -> {
				int slot = designatorSlot(head.name(), parts.size());
				boolean designator = slot > 0 && parts.get(slot) instanceof LispSymbol arg && name.equals(arg.name());
				// From 1: a symbol in head position is a Lisp-2 FUNCTION name, never a
				// read of the variable.
				yield (designator ? 1 : 0) + sumUses(parts, 1, name);
			}
		};
	}

	/** {@code (case keyform (keys body...) ...)}: the keys of each clause are data. */
	private static int caseUses(List<LispVal> parts, String name) {
		int uses = parts.size() > 1 ? designatorUses(parts.get(1), name) : 0;
		for (int i = 2; i < parts.size(); i++) {
			if (parts.get(i) instanceof LispCons clause && clause.isProperList()) {
				uses += sumUses(clause.toList(), 1, name);
			}
		}
		return uses;
	}

	private static int sumUses(List<LispVal> parts, int from, String name) {
		int uses = 0;
		for (int i = from; i < parts.size(); i++) {
			uses += designatorUses(parts.get(i), name);
		}
		return uses;
	}

	/**
	 * The argument index an operator reads as a function designator -- the six sites
	 * {@code Jvm/WasmDesignatorCall} resolves -- or -1 for every other form.
	 */
	private static int designatorSlot(String operator, int size) {
		int slot = switch (operator) {
			case LispNames.FUNCALL, LispNames.MAPCAR, LispNames.MAPC, LispNames.MAPCAN, LispNames.REDUCE -> 1;
			case LispNames.SORT -> 2;
			default -> -1;
		};
		return slot < size ? slot : -1;
	}

	/**
	 * Replaces every occurrence of a propagated name with its designator. Shape-blind,
	 * which {@link #onlyDesignatorUses} is what makes safe: it has already established
	 * that the only occurrences left are the certified ones.
	 */
	private static LispVal substitute(LispVal form, Map<String, LispVal> propagated) {
		if (form instanceof LispSymbol sym) {
			LispVal designator = propagated.get(sym.name());
			return designator == null ? form : designator;
		}
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		return SourceProvenance.inherit(cons,
				LispCons.rebuilt(cons, substitute(cons.car(), propagated), substitute(cons.cdr(), propagated)));
	}

}
