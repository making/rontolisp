package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Parses and desugars extended lambda lists ({@code &optional}, {@code &rest},
 * {@code &key}, {@code &aux}, {@code &allow-other-keys}) shared by the interpreter and
 * both compilers. The only shape the backends implement natively is "required parameters
 * plus an optional trailing {@code &rest} list"; every other lambda-list keyword is
 * rewritten here into that shape plus a {@code let*} prologue wrapped around the body:
 *
 * <pre>
 * (defun f (a &amp;optional (b 10 bp) &amp;key (k 1)) body...)
 * ==&gt;
 * (defun f (a &amp;rest #rest)
 *   (let* ((bp (consp #rest))
 *          (b (if bp (car #rest) 10))
 *          (#rest (if ...)) ...
 *          (k ...))
 *     body...))
 * </pre>
 *
 * Optional/key defaults are evaluated only when the argument is absent (the {@code if}
 * guards), in left-to-right {@code let*} scope so a default can reference earlier
 * parameters, matching Common Lisp. Keyword parsing scans the rest list with a
 * {@code do}/{@code return} loop (the same shape {@code getf} expands to), so no new
 * backend primitives are needed. Unknown keywords signal an error unless
 * {@code &allow-other-keys} is declared or the caller passes {@code :allow-other-keys t}.
 * {@code &whole} is not supported (it is only meaningful for macros).
 */
public final class LambdaLists {

	/** Prefix of generated helper variable names (mirrors {@code __getf_key} etc.). */
	private static final String REST_VAR = "__ll_rest";

	private static final String CUR_VAR = "__ll_cur";

	private static final String CELL_VAR_PREFIX = "__ll_cell_";

	private LambdaLists() {
	}

	/**
	 * A lambda list reduced to the shape the backends implement natively: required
	 * parameter symbols, an optional trailing rest parameter, and the (possibly
	 * prologue-wrapped) body.
	 *
	 * @param required the required parameter symbols
	 * @param rest the rest parameter, or {@code null} for a fixed-arity function
	 * @param body the body forms
	 */
	public record Expanded(List<LispSymbol> required, @Nullable LispSymbol rest, List<LispVal> body) {
	}

	/**
	 * Returns whether the parameter list uses any lambda-list keyword.
	 * @param paramList the raw parameter list AST
	 * @return {@code true} if an element is a symbol starting with {@code &}
	 */
	public static boolean usesLambdaListKeywords(LispVal paramList) {
		if (!(paramList instanceof LispCons cons)) {
			return false;
		}
		for (LispVal p : cons.toList()) {
			if (p instanceof LispSymbol sym && sym.name().startsWith("&")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Parses the lambda list and desugars every extension into the native "required +
	 * rest" shape, wrapping the body in a {@code let*} prologue when needed. A plain
	 * parameter list is returned unchanged (no wrapping).
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @return the native-shape lambda list and body
	 */
	public static Expanded expand(LispVal paramList, List<LispVal> body) {
		return expand(paramList, body, true);
	}

	/**
	 * Like {@link #expand(LispVal, List)}, with the lite {@code return-from} rewrite
	 * optional: the interpreter passes {@code false} because it implements
	 * {@code block}/{@code return-from} natively (a named signal caught by the matching
	 * block), so the name-dropping rewrite must not run there; the compilers keep the
	 * rewrite.
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @param rewriteReturnFrom whether to apply the lite return-from rewrite
	 * @return the native-shape lambda list and body
	 */
	public static Expanded expand(LispVal paramList, List<LispVal> body, boolean rewriteReturnFrom) {
		if (rewriteReturnFrom) {
			body = rewriteReturnFrom(body);
		}
		List<LispVal> params = paramList instanceof LispCons cons ? cons.toList() : List.of();
		if (!usesLambdaListKeywords(paramList)) {
			List<LispSymbol> required = new ArrayList<>(params.size());
			for (LispVal p : params) {
				required.add(asParamSymbol(p));
			}
			return new Expanded(required, null, body);
		}
		Parsed parsed = parse(params);
		if (parsed.optionals().isEmpty() && parsed.keys().isEmpty() && parsed.auxes().isEmpty()) {
			// Pure (a b &rest r): already native, no prologue needed.
			return new Expanded(parsed.required(), parsed.rest(), body);
		}
		LispSymbol restVar = parsed.rest() != null && parsed.optionals().isEmpty() ? parsed.rest()
				: new LispSymbol(REST_VAR);
		List<LispVal> bindings = new ArrayList<>();
		appendPrologueBindings(parsed, restVar, false, bindings);
		List<LispVal> letBody = new ArrayList<>();
		if (!parsed.keys().isEmpty() && !parsed.allowOtherKeys()) {
			letBody.add(unknownKeyCheck(parsed.rest() != null ? parsed.rest() : restVar, parsed.keys()));
		}
		letBody.addAll(body);
		List<LispVal> letParts = new ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET_STAR));
		letParts.add(list(bindings.toArray(LispVal[]::new)));
		letParts.addAll(letBody);
		return new Expanded(parsed.required(), restVar, List.of(list(letParts.toArray(LispVal[]::new))));
	}

	/**
	 * The native lambda shape as the compilers consume it: physical parameter names
	 * (required parameters plus, when variadic, the rest parameter as the last name), the
	 * variadic flag, and the (possibly prologue-wrapped) body.
	 *
	 * @param paramNames the physical parameter names, rest parameter last when variadic
	 * @param variadic whether the last parameter collects the remaining arguments
	 * @param body the body forms
	 */
	public record NativeForm(List<String> paramNames, boolean variadic, List<LispVal> body) {
	}

	/**
	 * Parses a lambda list into the {@link NativeForm} the compilers consume, desugaring
	 * extensions via {@link #expand} when present.
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @return the native form
	 */
	public static NativeForm toNative(LispVal paramList, List<LispVal> body) {
		Expanded e = expand(paramList, body);
		List<String> names = new ArrayList<>(e.required().size() + 1);
		for (LispSymbol s : e.required()) {
			names.add(s.name());
		}
		if (e.rest() != null) {
			names.add(e.rest().name());
		}
		return new NativeForm(names, e.rest() != null, e.body());
	}

	/**
	 * Rewrites every {@code defun}/{@code lambda} form in the program whose parameter
	 * list uses lambda-list keywords into the native "required + rest" shape via
	 * {@link #expand}. Quoted data is left untouched (so forms destined for a runtime
	 * {@code eval} keep their source shape). Used by the compilers as a pre-pass; the
	 * interpreter expands lazily at lambda-creation time instead.
	 * @param program the top-level forms
	 * @return the rewritten forms
	 */
	public static List<LispVal> desugarProgram(List<LispVal> program) {
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(desugar(form));
		}
		return out;
	}

	private static LispVal desugar(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym) {
			String name = sym.name();
			if (LispNames.QUOTE.equals(name)) {
				return form;
			}
			List<LispVal> parts = cons.toList();
			// A defun/lambda is rebuilt through expand() when its parameter list uses
			// lambda-list keywords OR its body uses return-from (the return-from
			// rewrite lives in expand so the interpreter's lazy path shares it).
			if (LispNames.LAMBDA.equals(name) && parts.size() >= 2 && (usesLambdaListKeywords(parts.get(1))
					|| anyContainsReturnFrom(parts.subList(2, parts.size())))) {
				Expanded e = expand(parts.get(1), parts.subList(2, parts.size()));
				return rebuildFunction(sym, null, e);
			}
			if (LispNames.DEFUN.equals(name) && parts.size() >= 3 && (usesLambdaListKeywords(parts.get(2))
					|| anyContainsReturnFrom(parts.subList(3, parts.size())))) {
				Expanded e = expand(parts.get(2), parts.subList(3, parts.size()));
				return rebuildFunction(sym, parts.get(1), e);
			}
		}
		return new LispCons(desugar(cons.car()), desugar(cons.cdr()));
	}

	/**
	 * Lite {@code return-from} support: when the body contains a
	 * {@code (return-from name value)} form, every occurrence is rewritten to
	 * {@code (return value)} (the block NAME is ignored -- there are no named blocks) and
	 * the whole body is wrapped in the internal {@code %block} so the return exits the
	 * function. Deviation: a {@code return-from} nested inside a {@code do}/ {@code loop}
	 * exits that loop's (nearer) block instead, which is only equivalent when the loop is
	 * the function's final form.
	 * @param body the defun/lambda body forms
	 * @return the body, rewritten and block-wrapped when return-from is present
	 */
	private static List<LispVal> rewriteReturnFrom(List<LispVal> body) {
		if (!anyContainsReturnFrom(body)) {
			return body;
		}
		List<LispVal> rewritten = new ArrayList<>(body.size() + 1);
		rewritten.add(new LispSymbol(LispNames.PROGN));
		for (LispVal form : body) {
			rewritten.add(stripReturnFrom(form));
		}
		return List.of(list(new LispSymbol(LispNames.BLOCK_INTERNAL), list(rewritten.toArray(LispVal[]::new))));
	}

	private static boolean anyContainsReturnFrom(List<LispVal> forms) {
		for (LispVal form : forms) {
			if (containsReturnFrom(form)) {
				return true;
			}
		}
		return false;
	}

	// Quoted data is exempt, like the rest of the desugaring. A nested lambda/defun is
	// its
	// own return-from scope: the rewrite stops at the boundary so the inner function
	// wraps
	// its own body in %block (lite: a return-from is scoped to the nearest enclosing
	// function, so a return-from inside a lambda passed to map*/reduce exits the lambda,
	// not the outer defun -- otherwise the stripped `return` would land in the lambda's
	// separately compiled method with no enclosing block).
	private static boolean containsReturnFrom(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol op) {
			if (LispNames.QUOTE.equals(op.name()) || isNestedFunction(op.name())) {
				return false;
			}
			if (LispNames.RETURN_FROM.equals(op.name())) {
				return true;
			}
		}
		return containsReturnFrom(cons.car()) || containsReturnFrom(cons.cdr());
	}

	private static LispVal stripReturnFrom(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol op) {
			if (LispNames.QUOTE.equals(op.name()) || isNestedFunction(op.name())) {
				// Leave the nested function intact; desugar() reaches it later and
				// expand()
				// rewrites its own return-from against its own %block.
				return form;
			}
			if (LispNames.RETURN_FROM.equals(op.name())) {
				List<LispVal> parts = cons.toList();
				LispVal value = parts.size() > 2 ? stripReturnFrom(parts.get(2)) : LispNil.INSTANCE;
				return list(new LispSymbol(LispNames.RETURN), value);
			}
		}
		return new LispCons(stripReturnFrom(cons.car()), stripReturnFrom(cons.cdr()));
	}

	private static boolean isNestedFunction(String op) {
		return LispNames.LAMBDA.equals(op) || LispNames.DEFUN.equals(op);
	}

	private static LispVal rebuildFunction(LispSymbol op, @Nullable LispVal name, Expanded e) {
		List<LispVal> paramParts = new ArrayList<>(e.required());
		if (e.rest() != null) {
			paramParts.add(new LispSymbol(LispNames.LAMBDA_REST));
			paramParts.add(e.rest());
		}
		List<LispVal> parts = new ArrayList<>();
		parts.add(op);
		if (name != null) {
			parts.add(name);
		}
		parts.add(list(paramParts.toArray(LispVal[]::new)));
		for (LispVal bodyForm : e.body()) {
			parts.add(desugar(bodyForm));
		}
		return list(parts.toArray(LispVal[]::new));
	}

	/**
	 * Appends the {@code let*} bindings desugaring the parsed
	 * {@code &optional}/{@code &rest}/{@code &key}/{@code &aux} parameters over
	 * {@code restVar} (the variable holding the argument list tail). When
	 * {@code aliasRest} is {@code true} the declared {@code &rest} parameter is always
	 * bound to {@code restVar} (the destructuring path, where {@code restVar} is a
	 * generated temporary); otherwise the alias is only needed after {@code &optional}
	 * stepping consumed {@code restVar} (the native-parameter path, where a keyword-free
	 * {@code &rest} parameter IS the physical rest parameter).
	 */
	private static void appendPrologueBindings(Parsed parsed, LispSymbol restVar, boolean aliasRest,
			List<LispVal> bindings) {
		for (OptionalParam opt : parsed.optionals()) {
			LispVal supplied = list(new LispSymbol(LispNames.CONSP), restVar);
			if (opt.suppliedP() != null) {
				bindings.add(list(opt.suppliedP(), supplied));
				supplied = opt.suppliedP();
			}
			bindings.add(list(opt.name(),
					list(new LispSymbol(LispNames.IF), supplied, call(LispNames.CAR, restVar), opt.defaultForm())));
			bindings.add(list(restVar, list(new LispSymbol(LispNames.IF),
					list(new LispSymbol(LispNames.CONSP), restVar), call(LispNames.CDR, restVar), LispNil.INSTANCE)));
		}
		if (parsed.rest() != null && (aliasRest || !parsed.optionals().isEmpty())) {
			bindings.add(list(parsed.rest(), restVar));
		}
		LispSymbol keySource = parsed.rest() != null ? parsed.rest() : restVar;
		for (KeyParam key : parsed.keys()) {
			LispSymbol cell = new LispSymbol(CELL_VAR_PREFIX + key.name().name());
			bindings.add(list(cell, keyCellScan(keySource, key.keyword())));
			if (key.suppliedP() != null) {
				bindings.add(list(key.suppliedP(),
						list(new LispSymbol(LispNames.IF), cell, LispTrue.INSTANCE, LispNil.INSTANCE)));
			}
			bindings.add(list(key.name(), list(new LispSymbol(LispNames.IF), cell,
					call(LispNames.CAR, call(LispNames.CDR, cell)), key.defaultForm())));
		}
		for (AuxParam aux : parsed.auxes()) {
			bindings.add(list(aux.name(), aux.initForm()));
		}
	}

	/**
	 * Appends the {@code let*} bindings destructuring a lambda-list tail (the elements
	 * from the first lambda-list keyword on) over {@code restVar}, for
	 * {@code destructuring-bind} and macro lambda lists. The unknown-keyword check (a
	 * {@code do} loop signalling on an undeclared keyword) is appended as a throwaway
	 * binding so the whole tail stays a flat binding list.
	 * @param tailParams the tail elements, starting with a lambda-list keyword
	 * @param restVar the variable holding the remaining list
	 * @param out the binding list to append to
	 */
	static void appendTailBindings(List<LispVal> tailParams, LispSymbol restVar, List<LispVal> out) {
		Parsed parsed = parse(tailParams);
		appendPrologueBindings(parsed, restVar, true, out);
		if (!parsed.keys().isEmpty() && !parsed.allowOtherKeys()) {
			LispSymbol keySource = parsed.rest() != null ? parsed.rest() : restVar;
			out.add(list(new LispSymbol("__ll_check"), unknownKeyCheck(keySource, parsed.keys())));
		}
	}

	// --- lambda list parsing ---

	private record OptionalParam(LispSymbol name, LispVal defaultForm, @Nullable LispSymbol suppliedP) {
	}

	private record KeyParam(LispSymbol keyword, LispSymbol name, LispVal defaultForm, @Nullable LispSymbol suppliedP) {
	}

	private record AuxParam(LispSymbol name, LispVal initForm) {
	}

	private record Parsed(List<LispSymbol> required, List<OptionalParam> optionals, @Nullable LispSymbol rest,
			List<KeyParam> keys, boolean allowOtherKeys, List<AuxParam> auxes) {
	}

	private static Parsed parse(List<LispVal> params) {
		List<LispSymbol> required = new ArrayList<>();
		List<OptionalParam> optionals = new ArrayList<>();
		LispSymbol rest = null;
		List<KeyParam> keys = new ArrayList<>();
		boolean allowOtherKeys = false;
		List<AuxParam> auxes = new ArrayList<>();
		// Section order is fixed: required, &optional, &rest, &key, &allow-other-keys,
		// &aux. Each keyword may appear at most once and only after the previous ones.
		int section = 0;
		int i = 0;
		while (i < params.size()) {
			LispVal p = params.get(i);
			if (p instanceof LispSymbol sym && sym.name().startsWith("&")) {
				int next = switch (sym.name()) {
					case LispNames.LAMBDA_OPTIONAL -> 1;
					case LispNames.LAMBDA_REST, LispNames.LAMBDA_BODY -> 2;
					case LispNames.LAMBDA_KEY -> 3;
					case LispNames.LAMBDA_ALLOW_OTHER_KEYS -> 4;
					case LispNames.LAMBDA_AUX -> 5;
					default -> throw new IllegalArgumentException("Unsupported lambda-list keyword: " + sym.name());
				};
				if (next <= section) {
					throw new IllegalArgumentException("Misplaced lambda-list keyword: " + sym.name());
				}
				section = next;
				if (section == 2) {
					if (i + 1 >= params.size() || !(params.get(i + 1) instanceof LispSymbol restSym)
							|| restSym.name().startsWith("&")) {
						throw new IllegalArgumentException(
								sym.name() + " must be followed by exactly one parameter symbol");
					}
					rest = restSym;
					i += 2;
					continue;
				}
				if (section == 4) {
					allowOtherKeys = true;
				}
				i++;
				continue;
			}
			switch (section) {
				case 0 -> required.add(asParamSymbol(p));
				case 1 -> optionals.add(parseOptional(p));
				case 3 -> keys.add(parseKey(p));
				case 5 -> auxes.add(parseAux(p));
				default -> throw new IllegalArgumentException("Unexpected parameter after &rest: " + p.print());
			}
			i++;
		}
		return new Parsed(required, optionals, rest, keys, allowOtherKeys, auxes);
	}

	private static OptionalParam parseOptional(LispVal spec) {
		if (spec instanceof LispSymbol sym) {
			return new OptionalParam(sym, LispNil.INSTANCE, null);
		}
		List<LispVal> parts = specParts(spec, LispNames.LAMBDA_OPTIONAL, 3);
		LispSymbol name = asParamSymbol(parts.get(0));
		LispVal defaultForm = parts.size() >= 2 ? parts.get(1) : LispNil.INSTANCE;
		LispSymbol suppliedP = parts.size() >= 3 ? asParamSymbol(parts.get(2)) : null;
		return new OptionalParam(name, defaultForm, suppliedP);
	}

	private static KeyParam parseKey(LispVal spec) {
		if (spec instanceof LispSymbol sym) {
			return new KeyParam(keywordFor(sym), sym, LispNil.INSTANCE, null);
		}
		List<LispVal> parts = specParts(spec, LispNames.LAMBDA_KEY, 3);
		LispSymbol keyword;
		LispSymbol name;
		if (parts.get(0) instanceof LispCons kvCons) {
			// ((:keyword var) default supplied-p)
			List<LispVal> kv = kvCons.toList();
			if (kv.size() != 2 || !(kv.get(0) instanceof LispSymbol kwSym)) {
				throw new IllegalArgumentException("Malformed &key parameter: " + spec.print());
			}
			keyword = kwSym.isKeyword() ? kwSym : keywordFor(kwSym);
			name = asParamSymbol(kv.get(1));
		}
		else {
			name = asParamSymbol(parts.get(0));
			keyword = keywordFor(name);
		}
		LispVal defaultForm = parts.size() >= 2 ? parts.get(1) : LispNil.INSTANCE;
		LispSymbol suppliedP = parts.size() >= 3 ? asParamSymbol(parts.get(2)) : null;
		return new KeyParam(keyword, name, defaultForm, suppliedP);
	}

	private static AuxParam parseAux(LispVal spec) {
		if (spec instanceof LispSymbol sym) {
			return new AuxParam(sym, LispNil.INSTANCE);
		}
		List<LispVal> parts = specParts(spec, LispNames.LAMBDA_AUX, 2);
		return new AuxParam(asParamSymbol(parts.get(0)), parts.size() >= 2 ? parts.get(1) : LispNil.INSTANCE);
	}

	private static List<LispVal> specParts(LispVal spec, String section, int maxSize) {
		if (!(spec instanceof LispCons cons)) {
			throw new IllegalArgumentException("Malformed " + section + " parameter: " + spec.print());
		}
		List<LispVal> parts = cons.toList();
		if (parts.isEmpty() || parts.size() > maxSize) {
			throw new IllegalArgumentException("Malformed " + section + " parameter: " + spec.print());
		}
		return parts;
	}

	private static LispSymbol asParamSymbol(LispVal p) {
		if (p instanceof LispSymbol sym && !sym.name().startsWith("&") && !sym.isKeyword()) {
			return sym;
		}
		throw new IllegalArgumentException("Parameter must be a symbol: " + p.print());
	}

	/** Derives the {@code :name} keyword for a variable, ignoring a package prefix. */
	private static LispSymbol keywordFor(LispSymbol var) {
		String name = var.name();
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return new LispSymbol(":" + (qn == null ? name : qn.member()));
	}

	// --- code generation helpers ---

	/**
	 * A {@code do} loop returning the plist cons cell whose car is the keyword, or nil:
	 * {@code (do ((__ll_cur src (cddr __ll_cur))) ((atom __ll_cur) nil)
	 * (if (eql (car __ll_cur) :kw) (return __ll_cur) nil))} — the same stepping shape
	 * {@code getf} expands to, so it works in every backend.
	 */
	private static LispVal keyCellScan(LispSymbol source, LispSymbol keyword) {
		LispSymbol cur = new LispSymbol(CUR_VAR);
		LispVal bindings = list(list(cur, source, call("cddr", cur)));
		LispVal endClause = list(call(LispNames.ATOM, cur), LispNil.INSTANCE);
		LispVal match = list(new LispSymbol(LispNames.EQL), call(LispNames.CAR, cur), keyword);
		LispVal body = list(new LispSymbol(LispNames.IF), match, list(new LispSymbol(LispNames.RETURN), cur),
				LispNil.INSTANCE);
		return list(new LispSymbol(LispNames.DO), bindings, endClause, body);
	}

	/**
	 * A {@code do} loop over the keyword tail signalling on the first indicator that is
	 * not a declared keyword, unless the caller passed {@code :allow-other-keys} with a
	 * true value.
	 */
	private static LispVal unknownKeyCheck(LispSymbol source, List<KeyParam> keys) {
		LispSymbol cur = new LispSymbol(CUR_VAR);
		List<LispVal> known = new ArrayList<>();
		for (KeyParam key : keys) {
			known.add(key.keyword());
		}
		known.add(new LispSymbol(LispNames.ALLOW_OTHER_KEYS_KEYWORD));
		LispVal knownList = list(new LispSymbol(LispNames.QUOTE), list(known.toArray(LispVal[]::new)));
		LispVal bindings = list(list(cur, source, call("cddr", cur)));
		LispVal endClause = list(call(LispNames.ATOM, cur), LispNil.INSTANCE);
		LispVal ok = list(new LispSymbol(LispNames.MEMBER), call(LispNames.CAR, cur), knownList);
		LispVal callerOverride = list(new LispSymbol(LispNames.GETF), source,
				new LispSymbol(LispNames.ALLOW_OTHER_KEYS_KEYWORD));
		LispVal signal = list(new LispSymbol(LispNames.ERROR), new LispString("Unknown keyword argument: ~s"),
				call(LispNames.CAR, cur));
		LispVal body = list(new LispSymbol(LispNames.IF), ok, LispNil.INSTANCE,
				list(new LispSymbol(LispNames.IF), callerOverride, LispNil.INSTANCE, signal));
		return list(new LispSymbol(LispNames.DO), bindings, endClause, body);
	}

	private static LispVal call(String fn, LispVal arg) {
		return list(new LispSymbol(fn), arg);
	}

	private static LispVal list(LispVal... elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			result = new LispCons(elements[i], result);
		}
		return result;
	}

}
