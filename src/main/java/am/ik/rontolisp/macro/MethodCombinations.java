package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * {@code define-method-combination} (CLHS 7.6.6.4): the lambda its form becomes, which an
 * evaluator runs for every dispatcher branch of a generic using the combination, and the
 * lowering of the effective method form it answers to calls of the method-body defuns.
 * The interpreter runs the lambda as it generates a dispatcher; a compile path runs it in
 * its macro-time evaluator, which is why a program's dispatchers need no run-time part.
 * See {@code .kb/clos.md}, "define-method-combination".
 */
public final class MethodCombinations {

	/**
	 * What a compile path's macro-time pass leaves of a definition:
	 * {@code (%define-method-combination
	 * 'name 'function)}, the function being the macro-time evaluator's, for the registry
	 * of the backend that generates the dispatchers.
	 */
	public static final String MARKER = "%DEFINE-METHOD-COMBINATION";

	/**
	 * The built-in the lambda matches a method's qualifiers against a qualifier pattern
	 * with ({@link #qualifiersMatch}).
	 */
	public static final String QUALIFIERS_MATCH = "%MC-QUALIFIERS-MATCH-P";

	private static final LispSymbol GF = new LispSymbol("%MC-GF");

	private static final LispSymbol OPTIONS = new LispSymbol("%MC-OPTIONS");

	private static final LispSymbol METHODS = new LispSymbol("%MC-METHODS");

	private static final LispSymbol ARGUMENTS = new LispSymbol("%MC-ARGUMENTS");

	private static final LispSymbol PAIR = new LispSymbol("%MC-PAIR");

	private static final LispSymbol ORDER = new LispSymbol("%MC-ORDER");

	private MethodCombinations() {
	}

	/**
	 * The lambda of a {@code define-method-combination} form, taking the generic's name,
	 * the {@code :method-combination} options, one {@code (method . qualifiers)} per
	 * applicable method, most specific first, and the forms of the generic's arguments,
	 * and answering the effective method form.
	 * @param form the {@code define-method-combination} form
	 * @return the lambda expression
	 */
	public static LispVal expanderLambda(LispCons form) {
		List<LispVal> parts = form.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispSymbol name)) {
			throw new IllegalArgumentException(
					LispNames.DEFINE_METHOD_COMBINATION + " expects a name: " + form.print());
		}
		if (parts.size() == 2 || (parts.get(2) instanceof LispSymbol option && option.isKeyword())) {
			return expanderLambda(shortFormAsLongForm(name, parts.subList(2, parts.size()), form));
		}
		if (parts.size() < 4 || !isList(parts.get(2)) || !isList(parts.get(3))) {
			throw new IllegalArgumentException(LispNames.DEFINE_METHOD_COMBINATION
					+ " expects a lambda list and method group specifiers: " + form.print());
		}
		int bodyAt = 4;
		List<LispVal> bindings = new ArrayList<>();
		while (bodyAt < parts.size() && parts.get(bodyAt) instanceof LispCons option
				&& option.car() instanceof LispSymbol key && key.isKeyword()) {
			bindings.addAll(optionBindings(key, option, form));
			bodyAt++;
		}
		List<MethodGroup> groups = new ArrayList<>();
		for (LispVal spec : listOf(parts.get(3))) {
			groups.add(MethodGroup.parse(spec, form));
		}
		for (MethodGroup group : groups) {
			bindings.add(list(group.variable(), LispNil.INSTANCE));
		}
		List<LispVal> forms = new ArrayList<>();
		forms.add(classification(groups));
		for (MethodGroup group : groups) {
			forms.add(group.finish(name));
		}
		List<LispVal> body = new ArrayList<>();
		body.add(new LispSymbol(LispNames.LET));
		body.add(LispNil.INSTANCE);
		body.addAll(withoutDocumentation(parts.subList(bodyAt, parts.size())));
		forms.add(toList(body));
		List<LispVal> let = new ArrayList<>();
		let.add(new LispSymbol(LispNames.LET));
		let.add(toList(bindings));
		let.addAll(forms);
		return list(new LispSymbol(LispNames.LAMBDA), list(GF, OPTIONS, METHODS, ARGUMENTS),
				list(new LispSymbol(LispNames.DESTRUCTURING_BIND), parts.get(2), OPTIONS, toList(let)));
	}

	/**
	 * The index of a {@code defmethod} form's lambda list: the first list after the name,
	 * past any qualifiers.
	 * @param parts the form's elements
	 * @return the index, the form's length when it has none
	 */
	public static int methodLambdaListIndex(List<LispVal> parts) {
		int index = 2;
		while (index < parts.size() && !(parts.get(index) instanceof LispCons || parts.get(index) instanceof LispNil)) {
			index++;
		}
		return index;
	}

	/**
	 * Whether a method's qualifiers match a qualifier pattern (CLHS 7.6.6.4): {@code *}
	 * matches anything, as an element or as the tail, the rest position by position.
	 * @param pattern the pattern
	 * @param qualifiers the qualifiers
	 * @return true when they match
	 */
	public static boolean qualifiersMatch(LispVal pattern, LispVal qualifiers) {
		if (isStar(pattern)) {
			return true;
		}
		if (pattern instanceof LispCons patternCons) {
			return qualifiers instanceof LispCons qualifierCons
					&& (isStar(patternCons.car()) || sameQualifier(patternCons.car(), qualifierCons.car()))
					&& qualifiersMatch(patternCons.cdr(), qualifierCons.cdr());
		}
		return pattern instanceof LispNil && qualifiers instanceof LispNil;
	}

	private static boolean sameQualifier(LispVal left, LispVal right) {
		return left instanceof LispSymbol leftSym && right instanceof LispSymbol rightSym
				? ClosRegistry.normalize(leftSym.name()).equals(ClosRegistry.normalize(rightSym.name()))
				: left.print().equals(right.print());
	}

	/**
	 * The form with every object of the map replaced by what it maps to, quoted data
	 * included: an evaluator's method objects as the names of their method-body defuns.
	 * @param form the effective method form
	 * @param replacements what stands for what, by identity
	 * @return the form
	 */
	public static LispVal replaced(LispVal form, Map<LispVal, ? extends LispVal> replacements) {
		LispVal replacement = replacements.get(form);
		if (replacement != null) {
			return replacement;
		}
		if (!(form instanceof LispCons)) {
			return form;
		}
		List<LispVal> items = new ArrayList<>();
		LispVal cursor = form;
		while (cursor instanceof LispCons cons) {
			items.add(replaced(cons.car(), replacements));
			cursor = cons.cdr();
		}
		LispVal out = replaced(cursor, replacements);
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new LispCons(items.get(i), out);
		}
		return out;
	}

	/**
	 * The effective method form with each {@code (call-method m nexts)} a call of its
	 * method-body defun, whose leading argument is the next-method thunk: a lambda over
	 * the rest of {@code nexts}, nil past the last, with a {@code (make-method form)} in
	 * {@code nexts} the thunk of that form.
	 * @param form the effective method form, a method named by its defun
	 * @param params the variables holding the generic's required arguments
	 * @param restVar the variable holding the rest of them, or null
	 * @return the code
	 */
	public static LispVal lowered(LispVal form, List<LispVal> params,
			@org.jspecify.annotations.Nullable LispSymbol restVar) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head) {
			String member = memberOf(head);
			if (LispNames.isQuote(member)) {
				return form;
			}
			if (LispNames.CALL_METHOD.equals(member) && cons.cdr() instanceof LispCons args) {
				LispVal nexts = args.cdr() instanceof LispCons more ? more.car() : LispNil.INSTANCE;
				return callMethod(args.car(), listOf(nexts), params, restVar);
			}
		}
		List<LispVal> items = new ArrayList<>();
		LispVal cursor = form;
		while (cursor instanceof LispCons cell) {
			items.add(lowered(cell.car(), params, restVar));
			cursor = cell.cdr();
		}
		LispVal out = cursor;
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new LispCons(items.get(i), out);
		}
		return out;
	}

	private static LispVal callMethod(LispVal method, List<LispVal> nexts, List<LispVal> params,
			@org.jspecify.annotations.Nullable LispSymbol restVar) {
		if (isMakeMethod(method)) {
			return lowered(makeMethodForm(method), params, restVar);
		}
		LispVal next = LispNil.INSTANCE;
		for (int i = nexts.size() - 1; i >= 0; i--) {
			next = thunk(nexts.get(i), next, params, restVar);
		}
		return call(method, next, params, restVar);
	}

	private static LispVal thunk(LispVal method, LispVal next, List<LispVal> params,
			@org.jspecify.annotations.Nullable LispSymbol restVar) {
		List<LispVal> lambdaList = new ArrayList<>(params);
		if (restVar != null) {
			lambdaList.add(new LispSymbol(LispNames.LAMBDA_REST));
			lambdaList.add(restVar);
		}
		LispVal body = isMakeMethod(method) ? lowered(makeMethodForm(method), params, restVar)
				: call(method, next, params, restVar);
		return list(new LispSymbol(LispNames.LAMBDA), toList(lambdaList), body);
	}

	private static LispVal call(LispVal method, LispVal next, List<LispVal> params,
			@org.jspecify.annotations.Nullable LispSymbol restVar) {
		List<LispVal> call = new ArrayList<>();
		if (restVar != null) {
			call.add(new LispSymbol(LispNames.APPLY));
			call.add(list(new LispSymbol(LispNames.FUNCTION), method));
		}
		else {
			call.add(method);
		}
		call.add(next);
		call.addAll(params);
		if (restVar != null) {
			call.add(restVar);
		}
		return toList(call);
	}

	private static boolean isMakeMethod(LispVal method) {
		return method instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.MAKE_METHOD.equals(memberOf(head));
	}

	private static LispVal makeMethodForm(LispVal makeMethod) {
		return ((LispCons) makeMethod).cdr() instanceof LispCons rest ? rest.car() : LispNil.INSTANCE;
	}

	/** The bindings of a {@code (:arguments ...)} or {@code (:generic-function v)}. */
	private static List<LispVal> optionBindings(LispSymbol key, LispCons option, LispCons form) {
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> items = listOf(option.cdr());
		if (":GENERIC-FUNCTION".equals(key.name()) && items.size() == 1) {
			bindings.add(list(items.get(0), list(new LispSymbol(LispNames.AND),
					list(new LispSymbol(LispNames.FBOUNDP), GF), list(new LispSymbol(LispNames.FDEFINITION), GF))));
			return bindings;
		}
		if (!":ARGUMENTS".equals(key.name())) {
			throw new IllegalArgumentException(
					LispNames.DEFINE_METHOD_COMBINATION + " option is not supported: " + option.print());
		}
		// Each variable holds a form that evaluates to the argument (CLHS): the variable
		// the effective method binds it to, the last argument form being the rest list's.
		for (int i = 0; i < items.size(); i++) {
			if (!(items.get(i) instanceof LispSymbol variable)) {
				throw new IllegalArgumentException(LispNames.DEFINE_METHOD_COMBINATION
						+ " :arguments takes required parameters and &rest: " + form.print());
			}
			if (LispNames.LAMBDA_REST.equals(memberOf(variable)) && i + 2 == items.size()) {
				bindings.add(list(items.get(i + 1),
						list(new LispSymbol(LispNames.CAR), list(new LispSymbol(LispNames.LAST), ARGUMENTS))));
				return bindings;
			}
			if (variable.isLambdaListKeyword()) {
				throw new IllegalArgumentException(LispNames.DEFINE_METHOD_COMBINATION
						+ " :arguments takes required parameters and &rest: " + form.print());
			}
			bindings.add(
					list(variable, list(new LispSymbol(LispNames.NTH), new am.ik.rontolisp.LispInteger(i), ARGUMENTS)));
		}
		return bindings;
	}

	/** The {@code dolist} putting each method in the first group it matches. */
	private static LispVal classification(List<MethodGroup> groups) {
		List<LispVal> clauses = new ArrayList<>();
		clauses.add(new LispSymbol(LispNames.COND));
		LispVal qualifiers = list(new LispSymbol(LispNames.CDR), PAIR);
		LispVal method = list(new LispSymbol(LispNames.CAR), PAIR);
		for (MethodGroup group : groups) {
			clauses.add(list(group.test(qualifiers), list(new LispSymbol(LispNames.SETQ), group.variable(),
					list(new LispSymbol(LispNames.CONS), method, group.variable()))));
		}
		clauses.add(list(am.ik.rontolisp.LispTrue.INSTANCE, list(new LispSymbol(LispNames.INVALID_METHOD_ERROR), method,
				new LispString("The qualifiers ~S match no method group."), qualifiers)));
		return list(new LispSymbol(LispNames.DOLIST), list(PAIR, METHODS), toList(clauses));
	}

	/** {@code (name lambda-list groups body)} of the short form's options. */
	private static LispCons shortFormAsLongForm(LispSymbol name, List<LispVal> options, LispCons form) {
		LispVal operator = name;
		LispVal identity = LispNil.INSTANCE;
		for (int i = 0; i + 1 < options.size(); i += 2) {
			switch (options.get(i) instanceof LispSymbol key ? key.name() : "") {
				case ":OPERATOR" -> operator = options.get(i + 1);
				case ":IDENTITY-WITH-ONE-ARGUMENT" -> identity = options.get(i + 1);
				case ":DOCUMENTATION" -> {
				}
				default -> throw new IllegalArgumentException(LispNames.DEFINE_METHOD_COMBINATION
						+ " option is not supported: " + options.get(i).print() + " in " + form.print());
			}
		}
		// As CLHS spells out what the short form means in terms of the long form.
		LispSymbol around = new LispSymbol("AROUND");
		LispSymbol primary = new LispSymbol("PRIMARY");
		LispSymbol order = new LispSymbol("ORDER");
		LispSymbol result = new LispSymbol("FORM");
		LispSymbol method = new LispSymbol("M");
		LispVal callMethod = quoted(new LispSymbol(LispNames.CALL_METHOD));
		LispVal single = list(new LispSymbol(LispNames.LIST), callMethod, list(new LispSymbol(LispNames.CAR), primary));
		LispVal combined = list(new LispSymbol(LispNames.CONS), quoted(operator),
				list(new LispSymbol(LispNames.MAPCAR), list(new LispSymbol(LispNames.LAMBDA), list(method),
						list(new LispSymbol(LispNames.LIST), callMethod, method)), primary));
		LispVal wrapped = list(new LispSymbol(LispNames.LIST), callMethod, list(new LispSymbol(LispNames.CAR), around),
				list(new LispSymbol(LispNames.APPEND), list(new LispSymbol(LispNames.CDR), around), list(
						new LispSymbol(LispNames.LIST),
						list(new LispSymbol(LispNames.LIST), quoted(new LispSymbol(LispNames.MAKE_METHOD)), result))));
		LispVal body = list(new LispSymbol(LispNames.LET), list(list(result,
				list(new LispSymbol(LispNames.IF),
						list(new LispSymbol(LispNames.AND), quoted(identity),
								list(new LispSymbol(LispNames.NULL), list(new LispSymbol(LispNames.CDR), primary))),
						single, combined))),
				list(new LispSymbol(LispNames.IF), around, wrapped, result));
		LispVal lambdaList = list(new LispSymbol(LispNames.LAMBDA_OPTIONAL),
				list(order, new LispSymbol(":MOST-SPECIFIC-FIRST")));
		LispVal groups = list(list(around, list(new LispSymbol(":AROUND"))), list(primary, list(name),
				new LispSymbol(":ORDER"), order, new LispSymbol(":REQUIRED"), am.ik.rontolisp.LispTrue.INSTANCE));
		return (LispCons) list(form.car(), name, lambdaList, groups, body);
	}

	private static LispVal quoted(LispVal value) {
		return list(new LispSymbol(LispNames.QUOTE), value);
	}

	/** The body without its documentation string; a last string is its value. */
	private static List<LispVal> withoutDocumentation(List<LispVal> body) {
		List<LispVal> out = new ArrayList<>(body);
		for (int i = 0; i + 1 < out.size(); i++) {
			if (out.get(i) instanceof LispString) {
				out.remove(i);
				break;
			}
			if (!(out.get(i) instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DECLARE.equals(memberOf(head)))) {
				break;
			}
		}
		return out;
	}

	private static boolean isStar(LispVal value) {
		return value instanceof LispSymbol sym && "*".equals(memberOf(sym));
	}

	private static String memberOf(LispSymbol sym) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(sym.name());
		return qualified == null ? sym.name() : qualified.member();
	}

	private static boolean isList(LispVal value) {
		return value instanceof LispNil || (value instanceof LispCons cons && cons.isProperList());
	}

	private static List<LispVal> listOf(LispVal value) {
		return value instanceof LispCons cons ? cons.toList() : List.of();
	}

	private static LispVal list(LispVal... items) {
		return toList(List.of(items));
	}

	private static LispVal toList(List<LispVal> items) {
		LispVal out = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new LispCons(items.get(i), out);
		}
		return out;
	}

	/**
	 * One method group specifier: {@code (variable {pattern+ | predicate} [:description
	 * d] [:order o] [:required r])}.
	 */
	private record MethodGroup(LispVal variable, List<LispVal> selectors,
			@org.jspecify.annotations.Nullable LispVal order, @org.jspecify.annotations.Nullable LispVal required) {

		static MethodGroup parse(LispVal spec, LispCons form) {
			if (!(spec instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol)) {
				throw new IllegalArgumentException(
						LispNames.DEFINE_METHOD_COMBINATION + " expects a method group specifier, got " + spec.print());
			}
			List<LispVal> parts = cons.toList();
			List<LispVal> selectors = new ArrayList<>();
			int i = 1;
			while (i < parts.size() && !(parts.get(i) instanceof LispSymbol key && key.isKeyword())) {
				selectors.add(parts.get(i++));
			}
			if (selectors.isEmpty()) {
				throw new IllegalArgumentException(LispNames.DEFINE_METHOD_COMBINATION
						+ " expects qualifier patterns or a predicate in " + spec.print() + ": " + form.print());
			}
			LispVal order = null;
			LispVal required = null;
			for (; i + 1 < parts.size(); i += 2) {
				switch (parts.get(i) instanceof LispSymbol key ? key.name() : "") {
					case ":ORDER" -> order = parts.get(i + 1);
					case ":REQUIRED" -> required = parts.get(i + 1);
					case ":DESCRIPTION" -> {
					}
					default -> throw new IllegalArgumentException(LispNames.DEFINE_METHOD_COMBINATION
							+ " method group option is not supported: " + parts.get(i).print());
				}
			}
			return new MethodGroup(parts.get(0), selectors, order, required);
		}

		/** Whether a method with these qualifiers belongs to the group. */
		LispVal test(LispVal qualifiers) {
			List<LispVal> tests = new ArrayList<>();
			tests.add(new LispSymbol(LispNames.OR));
			for (LispVal selector : this.selectors) {
				tests.add(selector instanceof LispSymbol predicate && !isStar(predicate)
						? list(new LispSymbol(LispNames.FUNCALL), list(new LispSymbol(LispNames.FUNCTION), predicate),
								qualifiers)
						: list(new LispSymbol(QUALIFIERS_MATCH), list(new LispSymbol(LispNames.QUOTE), selector),
								qualifiers));
			}
			return toList(tests);
		}

		/**
		 * The group in its order, most specific first unless {@code :order} says
		 * otherwise, and refused when empty and {@code :required}.
		 */
		LispVal finish(LispSymbol combination) {
			LispVal reversed = list(new LispSymbol(LispNames.REVERSE), this.variable);
			LispVal ordered = reversed;
			if (this.order != null) {
				ordered = list(new LispSymbol(LispNames.LET), list(list(ORDER, this.order)), list(
						new LispSymbol(LispNames.IF),
						list(new LispSymbol(LispNames.EQ_GENERAL), ORDER, new LispSymbol(":MOST-SPECIFIC-LAST")),
						this.variable, reversed));
			}
			LispVal assign = list(new LispSymbol(LispNames.SETQ), this.variable, ordered);
			if (this.required == null) {
				return assign;
			}
			return list(new LispSymbol(LispNames.PROGN), assign,
					list(new LispSymbol(LispNames.IF),
							list(new LispSymbol(LispNames.AND), this.required,
									list(new LispSymbol(LispNames.NULL), this.variable)),
							list(new LispSymbol(LispNames.METHOD_COMBINATION_ERROR),
									new LispString("No method of the required group " + this.variable.print()
											+ " in the method combination " + combination.print() + ".")),
							LispNil.INSTANCE));
		}

	}

}
