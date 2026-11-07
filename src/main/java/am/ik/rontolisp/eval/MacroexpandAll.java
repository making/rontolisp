package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.CallSiteWalk;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's {@code macroexpand-all} ({@code rontolisp::%macroexpand-all}, what
 * trivial-macroexpand-all and SWANK's {@code swank-macroexpand-all} answer): every macro
 * call in a form expanded, outermost first, at the positions {@link CallSiteWalk} knows
 * to be code. A global or {@code macrolet} macro and a built-in one expand through
 * {@link LispEvaluator#macroexpand1}; a {@code macrolet}'s macros are bound while its
 * body is walked, and a local function shadows a macro of its name. A symbol macro
 * expands where it is REFERENCED, the expansion walked there in turn: petalisp's
 * {@code transform} finds the variables an expression mentions by binding each to a
 * symbol macro whose expansion calls a local macro that records it, so expanding a
 * binding that no reference names would record it too. A built-in macro the evaluator
 * runs natively ({@code defun}, {@code handler-case}) stays as written and its subforms
 * are walked by their shape, since its expansion is an internal form no walk knows. A
 * compiler macro is not applied, as {@code sb-cltl2:macroexpand-all} applies none.
 */
final class MacroexpandAll {

	private MacroexpandAll() {
	}

	/**
	 * Expands every macro in a form.
	 * @param form the form
	 * @param evaluator the evaluator whose macros apply
	 * @param symbolMacros the symbol macros in scope where the form stands (the global
	 * ones, or those of the {@code &environment} a macro was handed)
	 * @return the expansion
	 */
	static LispVal expand(LispVal form, LispEvaluator evaluator, Map<String, LispVal> symbolMacros) {
		LispVal start = symbolMacros.isEmpty() ? form
				: LispMacroExpander.substituteGlobalSymbolMacros(form, symbolMacros);
		return new Expander(evaluator, Set.of()).walk(start);
	}

	/** The walk's rewriter; {@code functions} are the local functions in scope. */
	private record Expander(LispEvaluator evaluator, Set<String> functions) implements CallSiteWalk.Rewriter {

		LispVal walk(LispVal form) {
			return CallSiteWalk.walk(form, this);
		}

		@Override
		public @Nullable LispVal call(LispCons form, LispSymbol head) {
			if (this.functions.contains(head.name())) {
				return null;
			}
			switch (standardName(head)) {
				case LispNames.QUOTE, LispNames.DECLARE:
					return form;
				case LispNames.FUNCTION:
					return functionForm(form);
				case LispNames.MACROLET:
					return macrolet(form);
				case LispNames.SYMBOL_MACROLET:
					return symbolMacrolet(form);
				case LispNames.FLET:
					return localFunctions(form, false);
				case LispNames.LABELS:
					return localFunctions(form, true);
				default:
					break;
			}
			LispVal expanded = this.evaluator.macroexpand1(form);
			if (expanded == form || LispMacroExpander.foldNativeExpansion(expanded) != null) {
				return null;
			}
			return walk(expanded);
		}

		// #'name stays; #'(lambda ...) is code.
		private LispVal functionForm(LispCons form) {
			if (form.cdr() instanceof LispCons arguments && arguments.car() instanceof LispCons lambda
					&& lambda.car() instanceof LispSymbol head && LispNames.LAMBDA.equals(standardName(head))) {
				return LispCons.rebuilt(form, form.car(), LispCons.rebuilt(arguments, walk(lambda), arguments.cdr()));
			}
			return form;
		}

		// (macrolet ((name lambda-list body...)...) body...): the macros are bound while
		// the body is walked, and shadow a local function of their name there. The form
		// stays, the body expanded, as SBCL's macroexpand-all keeps it.
		private LispVal macrolet(LispCons form) {
			List<LispVal> parts = form.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons || parts.get(1) instanceof LispNil)) {
				return form;
			}
			List<LispVal> definitions = parts.get(1) instanceof LispCons list ? list.toList() : List.of();
			return this.evaluator.withLocalMacros(() -> {
				Map<String, @Nullable Object> saved = new LinkedHashMap<>();
				Set<String> functions = new HashSet<>(this.functions);
				for (LispVal definition : definitions) {
					if (definition instanceof LispCons cons && cons.isProperList()
							&& cons.car() instanceof LispSymbol name && cons.cdr() instanceof LispCons rest) {
						Object previous = this.evaluator.pushLocalMacro(name, rest.car(),
								rest.cdr() instanceof LispCons body ? body.toList() : List.of());
						if (!saved.containsKey(name.name())) {
							saved.put(name.name(), previous);
						}
						functions.remove(name.name());
					}
				}
				try {
					Expander body = new Expander(this.evaluator, Set.copyOf(functions));
					List<LispVal> out = new ArrayList<>(parts.subList(0, 2));
					for (LispVal member : parts.subList(2, parts.size())) {
						out.add(body.walk(member));
					}
					return LispCons.rebuiltList(form, out);
				}
				finally {
					for (Map.Entry<String, @Nullable Object> entry : saved.entrySet()) {
						this.evaluator.popLocalMacro(entry.getKey(), entry.getValue());
					}
				}
			});
		}

		// (symbol-macrolet ((name expansion)...) body...): each reference is replaced by
		// its expansion, shadow-aware, and what that made is walked -- so an expansion
		// expands where a reference stands and nowhere else.
		private LispVal symbolMacrolet(LispCons form) {
			List<LispVal> parts = form.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons || parts.get(1) instanceof LispNil)) {
				return form;
			}
			Map<String, LispVal> macros = new LinkedHashMap<>();
			if (parts.get(1) instanceof LispCons bindings) {
				for (LispVal binding : bindings.toList()) {
					if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol name
							&& pair.cdr() instanceof LispCons rest) {
						macros.putIfAbsent(name.name(), rest.car());
					}
				}
			}
			List<LispVal> body = new ArrayList<>();
			body.add(new LispSymbol(LispNames.PROGN));
			for (LispVal member : parts.subList(2, parts.size())) {
				if (!(member instanceof LispCons cons && cons.car() instanceof LispSymbol head
						&& LispNames.DECLARE.equals(standardName(head)))) {
					body.add(member);
				}
			}
			return walk(LispMacroExpander.substituteGlobalSymbolMacros(list(body), macros));
		}

		// (flet ((name lambda-list body...)...) body...): the names shadow a macro of
		// theirs in the body -- and, for labels, in the definitions too.
		private @Nullable LispVal localFunctions(LispCons form, boolean labels) {
			List<LispVal> parts = form.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons definitions) || !definitions.isProperList()) {
				return null;
			}
			Set<String> functions = new HashSet<>(this.functions);
			for (LispVal definition : definitions.toList()) {
				if (definition instanceof LispCons cons && cons.car() instanceof LispSymbol name) {
					functions.add(name.name());
				}
			}
			Expander inner = new Expander(this.evaluator, Set.copyOf(functions));
			Expander outer = labels ? inner : this;
			List<LispVal> walked = new ArrayList<>();
			for (LispVal definition : definitions.toList()) {
				if (definition instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol
						&& cons.cdr() instanceof LispCons rest) {
					LispVal lambda = outer.walk(new LispCons(new LispSymbol(LispNames.LAMBDA), rest));
					walked.add(LispCons.rebuilt(cons, cons.car(), ((LispCons) lambda).cdr()));
				}
				else {
					walked.add(definition);
				}
			}
			List<LispVal> out = new ArrayList<>();
			out.add(parts.get(0));
			out.add(LispCons.rebuiltList(definitions, walked));
			for (LispVal member : parts.subList(2, parts.size())) {
				out.add(inner.walk(member));
			}
			return LispCons.rebuiltList(form, out);
		}

		private static LispVal list(List<LispVal> elements) {
			LispVal list = LispNil.INSTANCE;
			for (int i = elements.size() - 1; i >= 0; i--) {
				list = new LispCons(elements.get(i), list);
			}
			return list;
		}

		// A cl symbol's member name, so an explicit cl:macrolet is a macrolet.
		private static String standardName(LispSymbol head) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(head.name());
			return qn != null && (LispNames.CL_PKG.equals(qn.pkg()) || "COMMON-LISP".equals(qn.pkg())) ? qn.member()
					: head.name();
		}

	}

}
