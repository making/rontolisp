package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;

/**
 * Static rewriting of function designators for the compilers. Common Lisp allows a symbol
 * as a function designator (e.g. {@code (funcall 'car x)}); the compilers support the
 * statically-known case by rewriting a literal {@code (quote name)} in function position
 * into {@code (function name)}, which both backends resolve against the compile-time
 * function registry.
 */
public final class FunctionDesignators {

	/**
	 * The argument index of the function designator of every standard operator that calls
	 * one: the operators the backends resolve with {@link #normalize} directly, and the
	 * ones whose expansion hands the designator to one of them.
	 */
	private static final Map<String, Integer> DESIGNATOR_ARGUMENT;
	static {
		Map<String, Integer> index = new HashMap<>();
		for (String op : List.of(LispNames.FUNCALL, LispNames.APPLY, LispNames.MULTIPLE_VALUE_CALL, LispNames.MAPCAR,
				LispNames.MAPC, LispNames.MAPCAN, LispNames.MAPLIST, LispNames.MAPL, LispNames.MAPCON, LispNames.REDUCE,
				LispNames.MAPHASH, "EVERY", "SOME", LispNames.NOTANY, "NOTEVERY", "REMOVE-IF", "REMOVE-IF-NOT",
				"DELETE-IF", "DELETE-IF-NOT", "FIND-IF", "FIND-IF-NOT", "POSITION-IF", "POSITION-IF-NOT", "COUNT-IF",
				"COUNT-IF-NOT", "MEMBER-IF", "MEMBER-IF-NOT", "ASSOC-IF", "ASSOC-IF-NOT", "RASSOC-IF",
				"RASSOC-IF-NOT")) {
			index.put(op, 1);
		}
		for (String op : List.of(LispNames.MAP, LispNames.MAP_INTO, LispNames.SORT, LispNames.STABLE_SORT,
				"SUBSTITUTE-IF", "SUBSTITUTE-IF-NOT", "NSUBSTITUTE-IF", "NSUBSTITUTE-IF-NOT", LispNames.SUBST_IF,
				"SUBST-IF-NOT", "NSUBST-IF", "NSUBST-IF-NOT")) {
			index.put(op, 2);
		}
		DESIGNATOR_ARGUMENT = Map.copyOf(index);
	}

	private static final Set<String> CASE_FORMS = Set.of(LispNames.CASE, LispNames.ECASE, LispNames.CCASE,
			LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CTYPECASE);

	private FunctionDesignators() {
	}

	/**
	 * Rewrites every literal {@code 'name} designator of a WRAPPED built-in
	 * ({@link BuiltinFunctionWrappers#names()} and the reference-gated ones) into
	 * {@code #'name}, where an operator takes it as its function argument
	 * ({@code (apply 'format nil ...)}, {@code (mapcar 'class-of ...)}).
	 *
	 * <p>
	 * The backends resolve both spellings to the same function ({@link #normalize}), but
	 * only at code generation. Every gate that decides whether a wrapper -- and whatever
	 * runtime its body calls -- is emitted at all scans the program BEFORE that for
	 * {@code (function name)}, so a program that spelled the designator with a quote
	 * compiled to a call of a function its own gate had left out: postmodern's
	 * {@code (apply 'concatenate 'string expanded)} failed with "Cannot compile:
	 * CONCATENATE". Rewriting here, once, puts every such gate and the code generator on
	 * one spelling instead of teaching each scan the second one.
	 *
	 * <p>
	 * Limited to built-in names because for them the two spellings cannot differ: a
	 * standard function may not be bound locally (CLHS 11.1.2.1.2), and a name a local
	 * {@code flet}/{@code labels}/{@code macrolet} binds anyway is left alone below it.
	 * Quoted data is not walked, and a {@code case}-family clause's keys are not code.
	 * Identity-preserving: a form with nothing to rewrite comes back as itself.
	 * @param program the package-resolved top-level forms
	 * @return the program with those designators rewritten, or {@code program} itself
	 */
	public static List<LispVal> normalizeBuiltinDesignators(List<LispVal> program) {
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal form = program.get(i);
			LispVal rewritten = rewrite(form, Set.of());
			if (rewritten != form && out == null) {
				out = new ArrayList<>(program.subList(0, i));
			}
			if (out != null) {
				out.add(rewritten);
			}
		}
		return out == null ? program : out;
	}

	private static boolean isBuiltin(String name) {
		return BuiltinFunctionWrappers.names().contains(name)
				|| BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS.contains(name);
	}

	private static LispVal rewrite(LispVal form, Set<String> shadowed) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		String op = cons.car() instanceof LispSymbol sym ? sym.name() : null;
		if (LispNames.QUOTE.equals(op) || LispNames.FUNCTION.equals(op)) {
			// #'(lambda ...) holds code, which the generic walk below reaches through the
			// lambda form itself.
			return LispNames.FUNCTION.equals(op) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispCons lambda
							? LispCons.rebuilt(cons, cons.car(),
									LispCons.rebuilt(rest, rewrite(lambda, shadowed), rest.cdr()))
							: form;
		}
		if (op != null && CASE_FORMS.contains(op) && cons.cdr() instanceof LispCons keyed) {
			// (case key (keys body...)...): the keys are data.
			LispVal clauses = LispTrees.rebuildSpine(keyed.cdr(), node -> null, clause -> clause instanceof LispCons c
					? LispCons.rebuilt(c, c.car(), rewriteEach(c.cdr(), shadowed)) : clause);
			return LispCons.rebuilt(cons, cons.car(), LispCons.rebuilt(keyed, rewrite(keyed.car(), shadowed), clauses));
		}
		Set<String> inner = shadowed;
		if ((LispNames.FLET.equals(op) || LispNames.LABELS.equals(op) || LispNames.MACROLET.equals(op))
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispCons definitions) {
			inner = new HashSet<>(shadowed);
			for (LispVal node = definitions; node instanceof LispCons cell; node = cell.cdr()) {
				if (cell.car() instanceof LispCons def && def.car() instanceof LispSymbol name) {
					inner.add(name.name());
				}
			}
		}
		LispCons call = cons;
		Integer index = op == null ? null : DESIGNATOR_ARGUMENT.get(op);
		if (index != null) {
			call = withBuiltinDesignator(cons, index, shadowed);
		}
		return rewriteEach(call, inner);
	}

	/**
	 * Every element of a list walked as code, the list rebuilt only where one changed.
	 */
	private static LispVal rewriteEach(LispVal list, Set<String> shadowed) {
		return LispTrees.rebuildSpine(list, node -> null, element -> rewrite(element, shadowed));
	}

	/**
	 * {@code call} with its argument at {@code index} rewritten from {@code 'name} to
	 * {@code #'name} when it names a built-in no local definition shadows.
	 */
	private static LispCons withBuiltinDesignator(LispCons call, int index, Set<String> shadowed) {
		List<LispCons> prefix = new ArrayList<>();
		LispVal node = call;
		for (int i = 0; i < index && node instanceof LispCons cell; i++) {
			prefix.add(cell);
			node = cell.cdr();
		}
		if (!(node instanceof LispCons argCell) || !(argCell.car() instanceof LispCons quoted)
				|| !(quoted.car() instanceof LispSymbol q) || !LispNames.QUOTE.equals(q.name())
				|| !(quoted.cdr() instanceof LispCons quotedRest) || !(quotedRest.car() instanceof LispSymbol name)
				|| !(quotedRest.cdr() instanceof LispNil) || name.isKeyword() || !isBuiltin(name.name())
				|| shadowed.contains(name.name())) {
			return call;
		}
		LispCons designator = SourceProvenance.inherit(quoted,
				new LispCons(new LispSymbol(LispNames.FUNCTION), new LispCons(name, LispNil.INSTANCE)));
		LispVal tail = new LispCons(designator, argCell.cdr());
		for (int i = prefix.size() - 1; i >= 0; i--) {
			tail = new LispCons(prefix.get(i).car(), tail);
		}
		return SourceProvenance.inherit(call, (LispCons) tail);
	}

	/**
	 * Rewrites a literal {@code (quote name)} function argument into
	 * {@code (function name)}; any other form is returned unchanged.
	 * @param fnForm the expression in function-designator position
	 * @return the normalized expression
	 */
	public static LispVal normalize(LispVal fnForm) {
		if (fnForm instanceof LispCons cons && cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol sym && !sym.isKeyword()) {
			return new LispCons(new LispSymbol(LispNames.FUNCTION), new LispCons(sym, LispNil.INSTANCE));
		}
		return fnForm;
	}

	/**
	 * The function NAME a designator the compiler can READ spells -- {@code #'name} or
	 * {@code 'name} -- or {@code null} for one it cannot (a variable, a call, an inline
	 * {@code (lambda ...)}).
	 *
	 * <p>
	 * This is what lets an operator that funcalls its function argument emit the DIRECT
	 * call its head-position spelling would have emitted instead of routing the value
	 * through the arity dispatcher; a name is only a candidate, and each backend still
	 * decides whether its own registry answers it at the arity in hand.
	 *
	 * <p>
	 * A local function shadowing the name is not a concern here: {@code flet}/{@code
	 * labels} rewrite both {@code (f x)} and {@code #'f} into their binding VARIABLE
	 * before any backend sees the form ({@code .kb/flet-labels.md}), so a surviving
	 * {@code (function name)} names the global one by construction.
	 * @param fnForm the expression in function-designator position
	 * @return the name, or {@code null} when the designator is not a literal one
	 */
	public static @Nullable String literalName(LispVal fnForm) {
		return normalize(fnForm) instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.FUNCTION.equals(op.name()) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol sym && !sym.isKeyword() && rest.cdr() instanceof LispNil
						? sym.name() : null;
	}

}
