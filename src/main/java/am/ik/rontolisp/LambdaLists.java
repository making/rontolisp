package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Parses and desugars extended lambda lists ({@code &optional}, {@code &rest},
 * {@code &key}, {@code &aux}, {@code &allow-other-keys}) shared by the interpreter and
 * both compilers. The shapes the backends implement natively are "required parameters
 * plus an optional trailing {@code &rest} list" and -- on the compiled backends only --
 * "required parameters, PHYSICAL optional parameters, and a trailing rest list"; every
 * other lambda-list keyword is rewritten here into one of them plus a {@code let*}
 * prologue wrapped around the body. The interpreter's shape steps the optionals off the
 * rest list:
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
 * The compilers' shape ({@link #toNative}, {@link #desugarProgram}) passes an optional
 * argument as a parameter of its own, so a call that supplies it conses no rest list: a
 * caller with no argument for it passes the UNSUPPLIED marker the prologue tests with
 * {@code %supplied-p}, and the rest list holds only what is past the last optional:
 *
 * <pre>
 * (defun f (a &amp;optional #opt0 &amp;rest #rest)
 *   (let* ((bp (%supplied-p #opt0))
 *          (b (if (%supplied-p #opt0) #opt0 10))
 *          (k ...))
 *     body...))
 * </pre>
 *
 * Every call path agrees on it: a callee takes {@code required} arguments, then
 * {@code optionals} parameters that are an argument or the marker, then the rest list
 * (nil when nothing is surplus) -- even without {@code &rest}/{@code &key}, where the
 * rest list is the surplus the prologue's count check reports. The physical names are
 * internal ({@value #OPT_VAR_PREFIX}N), never the user's: a default form must not see a
 * LATER parameter's binding, and a physical parameter named after it would be in scope. A
 * lambda list whose required parameters and optionals would exceed the backend's
 * parameter budget keeps its trailing optionals on the rest list, stepped as the
 * interpreter steps them.
 *
 * <p>
 * Optional/key defaults are evaluated only when the argument is absent (the {@code if}
 * guards), in left-to-right {@code let*} scope so a default can reference earlier
 * parameters, matching Common Lisp. Keyword parsing is a CALL per keyword parameter
 * ({@code %ll-key-cell}, the plist cell for the keyword or nil) plus one per function
 * ({@code %ll-check-keys}, the unknown-keyword check); both are ordinary {@code defun}s
 * built here ({@link #runtimeDefun}) -- {@link #desugarProgram} prepends them to a
 * program that spells {@code &key}, the interpreter evaluates them on first resolution --
 * so the scan loop exists once per program rather than once per keyword parameter (a
 * {@code defstruct}-heavy program carried hundreds of inline copies). Unknown keywords
 * signal an error unless {@code &allow-other-keys} is declared or the caller passes
 * {@code :allow-other-keys t}. {@code &whole} is not supported (it is only meaningful for
 * macros).
 *
 * <p>
 * On the compilers a parameter whose name is proclaimed special binds DYNAMICALLY, as CL
 * requires: a required or rest parameter named like a special is renamed to an internal
 * one ({@value #SPECIAL_PARAM_PREFIX}N) and the special is bound from it by a {@code let}
 * around the whole body, prologue included -- the special {@code let} the compilers
 * already bind with a shallow save/restore over every exit -- so a default form sees the
 * binding of every earlier parameter. The optionals, keys and auxes are bound by the
 * prologue's {@code let*} under their own names, which binds a special one dynamically
 * the same way. The interpreter's shape is left alone: it binds a special parameter
 * itself.
 *
 * <pre>
 * (defun f (*x* &amp;optional (y (g))) body...)
 * ==&gt;
 * (defun f (#sp0 &amp;optional #opt0 &amp;rest #rest)
 *   (let ((*x* #sp0))
 *     (let* (... (y (if (%supplied-p #opt0) #opt0 (g))))
 *       body...)))
 * </pre>
 */
public final class LambdaLists {

	/** Prefix of generated helper variable names (mirrors {@code __getf_key} etc.). */
	private static final String REST_VAR = "__ll_rest";

	/**
	 * The prefix of a PHYSICAL optional parameter's name ({@code __ll_opt_0},
	 * {@code __ll_opt_1}, ...): what {@link #toNative} and {@link #desugarProgram} write
	 * into a lambda list whose optionals travel as parameters, and what marks such a list
	 * as already desugared when it is expanded again.
	 */
	private static final String OPT_VAR_PREFIX = "__ll_opt_";

	/**
	 * The prefix of the internal name a compiled function's parameter named like a
	 * special variable takes ({@code __ll_sp_0}, ...; N is the parameter's position): the
	 * special itself is bound from it by the {@code let} around the body
	 * ({@link #bindSpecialParameters}).
	 */
	private static final String SPECIAL_PARAM_PREFIX = "__ll_sp_";

	/**
	 * The parameter budget of the interpreter's expansion: no physical optional, every
	 * optional stepped off the rest list.
	 */
	private static final int STEPPED = 0;

	/**
	 * The most physical parameters (the closure environment not counted) the compiled
	 * backends' shape gives a function: required parameters, physical optionals and the
	 * rest list together. Past it, the remaining optionals ride the rest list. It is the
	 * WASM backend's callable ceiling ({@code WasmLispCompiler.MAX_CALLABLE_ARITY} may
	 * not be lower), and the JVM's too, where the JVM itself would allow 254: every
	 * dispatcher case passes each parameter, and a SPREAD case walks each one out of the
	 * argument list from its head, so a physical parameter's cost grows with the count.
	 * One number, because a lambda list desugared BEFORE the backend is known -- an
	 * {@code flet}/{@code labels} definition ({@link #expandPhysical}) -- has to fit
	 * both.
	 */
	public static final int MAX_PHYSICAL_PARAMS = 10;

	/**
	 * What the INTERPRETER binds a physical optional to when the call has no argument for
	 * it: a lambda list in the compilers' physical shape reaches the interpreter too (an
	 * {@code flet}/{@code labels} definition, {@link #expandPhysical}), and its prologue
	 * tests the parameter with {@code %supplied-p}. A host object no Lisp value can be
	 * {@code eq} to; it self-evaluates, so the expansion names it as a literal.
	 */
	public static final LispVal UNSUPPLIED = new LispJavaObject(new Object());

	private static final String CUR_VAR = "__ll_cur";

	private static final String CELL_VAR_PREFIX = "__ll_cell_";

	private static final String PLIST_PARAM = "__ll_plist";

	private static final String KEY_PARAM = "__ll_key";

	private static final String UPPER_PARAM = "__ll_upper";

	private static final String KNOWN_PARAM = "__ll_known";

	private static final String ARITY_VAR = "__ll_arity";

	/**
	 * The throwaway {@code let*} variable of the destructuring missing-element check
	 * ({@link #destructuringMissingCheck}). A name of its own rather than
	 * {@link #ARITY_VAR}: a required-only level carries both checks in one binding list,
	 * and two same-named entries would only shadow.
	 */
	private static final String MISSING_VAR = "__ll_missing";

	/** The longest optional tail whose surplus test is spelled as nested {@code cdr}s. */
	private static final int NESTED_CDR_MAX = 3;

	private LambdaLists() {
	}

	/**
	 * A lambda list reduced to a shape the backends implement natively: required
	 * parameter symbols, the physical optional parameters (always empty in the
	 * interpreter's shape), an optional trailing rest parameter, and the (possibly
	 * prologue-wrapped) body.
	 *
	 * @param required the required parameter symbols
	 * @param optionals the physical optional parameters, each an argument or the
	 * UNSUPPLIED marker; non-empty only with a rest parameter
	 * @param rest the rest parameter, or {@code null} for a fixed-arity function
	 * @param body the body forms
	 */
	public record Expanded(List<LispSymbol> required, List<LispSymbol> optionals, @Nullable LispSymbol rest,
			List<LispVal> body) {

		/**
		 * The interpreter's shape: no physical optional.
		 * @param required the required parameter symbols
		 * @param rest the rest parameter, or {@code null} for a fixed-arity function
		 * @param body the body forms
		 */
		public Expanded(List<LispSymbol> required, @Nullable LispSymbol rest, List<LispVal> body) {
			this(required, List.of(), rest, body);
		}

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
	 * Parses the lambda list and desugars every extension into the interpreter's native
	 * "required + rest" shape, wrapping the body in a {@code let*} prologue when needed.
	 * A plain parameter list is returned unchanged (no wrapping).
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @return the native-shape lambda list and body
	 */
	public static Expanded expand(LispVal paramList, List<LispVal> body) {
		return expand(paramList, body, true);
	}

	/**
	 * Like {@link #expand(LispVal, List)}, with the {@code %fn-block} function-boundary
	 * wrap optional: the interpreter passes {@code false} because it implements
	 * {@code block}/{@code return-from} natively (a named signal caught by the matching
	 * block), so the wrap must not run there; the compilers keep it (with a {@code nil}
	 * block name -- the lambda shape; {@link #desugarProgram} passes the defun's name for
	 * defuns).
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @param wrapReturnFrom whether to wrap a return-from-containing body in %fn-block
	 * @return the native-shape lambda list and body
	 */
	public static Expanded expand(LispVal paramList, List<LispVal> body, boolean wrapReturnFrom) {
		return expand(paramList, body, wrapReturnFrom, null);
	}

	/**
	 * Like {@link #expand(LispVal, List, boolean)}, for a function with a name: the
	 * {@code &optional} surplus check carries it ({@link #tooManyArgsCheck}).
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @param wrapReturnFrom whether to wrap a return-from-containing body in %fn-block
	 * @param functionName the function's name, or {@code null} for an anonymous one
	 * @return the native-shape lambda list and body
	 */
	public static Expanded expand(LispVal paramList, List<LispVal> body, boolean wrapReturnFrom,
			@Nullable String functionName) {
		return expand(paramList, body, wrapReturnFrom, null, STEPPED, functionName, Set.of());
	}

	/**
	 * The expansion in {@code maxParams}' shape, with every physical parameter named like
	 * one of {@code specials} bound dynamically ({@link #bindSpecialParameters}) -- in
	 * the compilers' shape only: the interpreter's ({@link #STEPPED}) gets no specials,
	 * since it binds such a parameter itself.
	 */
	private static Expanded expand(LispVal paramList, List<LispVal> body, boolean wrapReturnFrom,
			@Nullable LispVal blockNameSym, int maxParams, @Nullable String functionName, Set<String> specials) {
		return bindSpecialParameters(
				desugared(paramList, body, wrapReturnFrom, blockNameSym, maxParams, functionName, specials), specials);
	}

	private static Expanded desugared(LispVal paramList, List<LispVal> body, boolean wrapReturnFrom,
			@Nullable LispVal blockNameSym, int maxParams, @Nullable String functionName, Set<String> specials) {
		if (body.isEmpty()) {
			// An empty function body answers nil, per CL -- dissect's
			// (defun restarts (&optional condition)) interface stubs; without the
			// explicit nil the desugared (let* (...)) prologue has no value form and
			// the compilers' emitters underflow.
			body = List.of(LispNil.INSTANCE);
		}
		if (wrapReturnFrom) {
			body = wrapReturnFrom(blockNameSym, body);
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
		if (isPhysicalShape(parsed)) {
			// (a &optional #opt0 &rest #rest): a lambda list this class already wrote,
			// its prologue already in the body.
			if (maxParams == STEPPED) {
				// The interpreter binds it off its argument list like any other,
				// UNSUPPLIED where the list runs out, so the prologue's %supplied-p reads
				// the same answer a compiled caller's marker gives.
				parsed = withUnsuppliedDefaults(parsed);
			}
			else {
				List<LispSymbol> optionals = new ArrayList<>(parsed.optionals().size());
				for (OptionalParam opt : parsed.optionals()) {
					optionals.add(opt.name());
				}
				return new Expanded(parsed.required(), optionals, parsed.rest(), body);
			}
		}
		if (parsed.optionals().isEmpty() && !parsed.sawKey() && parsed.auxes().isEmpty()) {
			// Pure (a b &rest r): already native, no prologue needed.
			return new Expanded(parsed.required(), parsed.rest(), body);
		}
		boolean bounded = parsed.rest() == null && !parsed.sawKey();
		if (bounded && parsed.optionals().isEmpty()) {
			// (a &aux x): FIXED arity -- the native shape checks the count, so an extra
			// argument signals like any other wrong count.
			List<LispVal> bindings = new ArrayList<>();
			appendPrologueBindings(parsed, List.of(), new LispSymbol(REST_VAR), false, bindings);
			return new Expanded(parsed.required(), null, List.of(letStar(bindings, body)));
		}
		// The optionals that travel as parameters: as many as the budget leaves beside
		// the required parameters and the rest list, which a callee with an optional
		// always takes (it is the surplus the count check below reports).
		int physical = Math.max(0, Math.min(parsed.optionals().size(), maxParams - parsed.required().size() - 1));
		List<OptionalParam> stepped = parsed.optionals().subList(physical, parsed.optionals().size());
		LispSymbol restVar = parsed.rest() != null && parsed.optionals().isEmpty() ? parsed.rest()
				: new LispSymbol(REST_VAR);
		List<LispVal> bindings = new ArrayList<>();
		if (bounded) {
			// (a &optional b): variadic physically, bounded logically. The check comes
			// FIRST, before any default form runs -- CL signals a wrong count before
			// binding anything. A caller hands the rest list only what is past the
			// physical optionals, so a surplus there means every one was supplied.
			bindings.add(tooManyArgsCheck(restVar, parsed.required().size() + physical, stepped.size(), functionName));
		}
		List<LispSymbol> optionals = new ArrayList<>(physical);
		for (int i = 0; i < physical; i++) {
			LispSymbol param = new LispSymbol(OPT_VAR_PREFIX + i);
			optionals.add(param);
			appendPhysicalOptionalBindings(parsed.optionals().get(i), param, bindings);
		}
		appendPrologueBindings(parsed, stepped, restVar, false, bindings);
		List<LispVal> letBody = new ArrayList<>();
		if (parsed.sawKey() && !parsed.allowOtherKeys()) {
			letBody.add(unknownKeyCheck(parsed.rest() != null ? parsed.rest() : restVar, parsed.keys()));
		}
		letBody.addAll(body);
		for (int i = 0; i < physical; i++) {
			LispSymbol suppliedP = parsed.optionals().get(i).suppliedP();
			// A special supplied-p variable keeps its binding: a function the body calls
			// reads it, which a test in place cannot answer.
			if (suppliedP != null && !specials.contains(suppliedP.name())) {
				testSuppliedPInPlace(suppliedP, optionals.get(i), bindings, letBody);
			}
		}
		return new Expanded(parsed.required(), optionals, restVar, List.of(letStar(bindings, letBody)));
	}

	/**
	 * Binds the physical parameters of a compiled function that are named like a special
	 * variable dynamically: each is renamed to an internal parameter
	 * ({@value #SPECIAL_PARAM_PREFIX}N, N its position) and the special is bound from it
	 * by one {@code let} around the whole body -- the {@code let*} prologue included, so
	 * a default form sees it. The compilers bind a special {@code let} with a shallow
	 * save/restore over every exit channel, and that {@code let} is not a
	 * tail-transparent form, so a call in the body is no tail call: the binding must be
	 * undone after it returns. A body that is one {@code %fn-block} wrap takes the
	 * {@code let} INSIDE it, so a later expansion still finds the wrap first
	 * ({@link #wrapReturnFrom} is idempotent on it, not on a {@code let} around it); a
	 * {@code return-from} leaves through the restore either way. The renamed parameters
	 * are no specials, so expanding the result again changes nothing. The physical
	 * optionals are internal names already (their users' names are prologue bindings,
	 * special or not). A function with no such parameter comes back as it was.
	 */
	private static Expanded bindSpecialParameters(Expanded e, Set<String> specials) {
		if (specials.isEmpty()) {
			return e;
		}
		List<LispVal> bindings = null;
		List<LispSymbol> required = e.required();
		for (int i = 0; i < required.size(); i++) {
			LispSymbol param = required.get(i);
			if (specials.contains(param.name())) {
				if (bindings == null) {
					bindings = new ArrayList<>();
					required = new ArrayList<>(required);
				}
				LispSymbol renamed = new LispSymbol(SPECIAL_PARAM_PREFIX + i);
				required.set(i, renamed);
				bindings.add(list(param, renamed));
			}
		}
		LispSymbol rest = e.rest();
		if (rest != null && specials.contains(rest.name())) {
			if (bindings == null) {
				bindings = new ArrayList<>();
			}
			LispSymbol renamed = new LispSymbol(SPECIAL_PARAM_PREFIX + (required.size() + e.optionals().size()));
			bindings.add(list(rest, renamed));
			rest = renamed;
		}
		if (bindings == null) {
			return e;
		}
		List<LispVal> body = e.body();
		List<LispVal> fnBlock = body.size() == 1 && body.get(0) instanceof LispCons only
				&& only.car() instanceof LispSymbol op && LispNames.FN_BLOCK_INTERNAL.equals(op.name())
				&& only.isProperList() ? only.toList() : null;
		List<LispVal> letParts = new ArrayList<>(body.size() + 2);
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(list(bindings.toArray(LispVal[]::new)));
		letParts.addAll(fnBlock != null ? fnBlock.subList(2, fnBlock.size()) : body);
		LispVal let = list(letParts.toArray(LispVal[]::new));
		LispVal wrapped = fnBlock != null ? list(fnBlock.get(0), fnBlock.get(1), let) : let;
		return new Expanded(required, e.optionals(), rest, List.of(wrapped));
	}

	/**
	 * Drops the binding of a physical optional's supplied-p variable whose every
	 * reference -- in the bindings after it and in the body -- is the TEST of an
	 * {@code if}, and tests the physical parameter there instead: {@code (if sp a b)}
	 * becomes {@code (if (%supplied-p #optN) a b)}. The variable is then never read as a
	 * value, so nothing builds the boxed {@code t} it would hold -- one reference
	 * comparison per test, where the binding cost a boolean box per call (on wasmtime a
	 * fifth of a two-argument {@code #'<}, whose wrapper tests its {@code bp},
	 * 2026-09-26). Any other occurrence keeps the binding: a value use, an assignment, a
	 * macro form the variable is an argument of, a rebinding of the name, a declaration.
	 * @param suppliedP the supplied-p variable
	 * @param param the physical optional it reports on
	 * @param bindings the prologue's bindings, the variable's among them
	 * @param letBody the body under the prologue
	 */
	private static void testSuppliedPInPlace(LispSymbol suppliedP, LispSymbol param, List<LispVal> bindings,
			List<LispVal> letBody) {
		int at = -1;
		for (int i = 0; i < bindings.size(); i++) {
			if (bindings.get(i) instanceof LispCons binding && suppliedP.equals(binding.car())) {
				at = i;
				break;
			}
		}
		if (at < 0) {
			return;
		}
		String name = suppliedP.name();
		for (int i = at + 1; i < bindings.size(); i++) {
			if (!onlyIfTests(bindings.get(i), name)) {
				return;
			}
		}
		for (LispVal form : letBody) {
			if (!onlyIfTests(form, name)) {
				return;
			}
		}
		bindings.remove(at);
		for (int i = at; i < bindings.size(); i++) {
			bindings.set(i, replaceIfTests(bindings.get(i), name, param));
		}
		letBody.replaceAll(form -> replaceIfTests(form, name, param));
	}

	/**
	 * Whether every occurrence of the variable in {@code form} outside quoted data is the
	 * test of an {@code if}. The CDR direction is a loop, so a long body costs no stack.
	 */
	private static boolean onlyIfTests(LispVal form, String name) {
		if (form instanceof LispSymbol sym) {
			return !name.equals(sym.name());
		}
		if (!(form instanceof LispCons cons)) {
			return true;
		}
		LispVal node = cons;
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return true;
			}
			if (LispNames.IF.equals(head.name()) && cons.cdr() instanceof LispCons test
					&& test.car() instanceof LispSymbol var && name.equals(var.name())) {
				node = test.cdr();
			}
		}
		while (node instanceof LispCons cell) {
			if (!onlyIfTests(cell.car(), name)) {
				return false;
			}
			node = cell.cdr();
		}
		return onlyIfTests(node, name);
	}

	/**
	 * {@code form} with the test of every {@code (if var ...)} outside quoted data
	 * replaced by {@code (%supplied-p param)}, a fresh cell per test -- the walk
	 * {@link #onlyIfTests} made, which reads a form's head once and every element after
	 * it as a form, so the two agree on what an occurrence is. The cells nothing changed
	 * under keep their identity ({@link LispCons#rebuilt}).
	 */
	private static LispVal replaceIfTests(LispVal form, String name, LispSymbol param) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return form;
			}
			if (LispNames.IF.equals(head.name()) && cons.cdr() instanceof LispCons testCell
					&& testCell.car() instanceof LispSymbol var && name.equals(var.name())) {
				return LispCons.rebuilt(cons, head, LispCons.rebuilt(testCell,
						call(LispNames.SUPPLIED_P_INTERNAL, param), replaceElements(testCell.cdr(), name, param)));
			}
		}
		return replaceElements(cons, name, param);
	}

	// Every element of a list as a form; the tail rebuilt from the back, so a long body
	// costs no stack in the cdr direction.
	private static LispVal replaceElements(LispVal list, String name, LispSymbol param) {
		List<LispCons> cells = new ArrayList<>();
		LispVal node = list;
		while (node instanceof LispCons cell) {
			cells.add(cell);
			node = cell.cdr();
		}
		LispVal tail = node;
		for (int i = cells.size() - 1; i >= 0; i--) {
			LispCons cell = cells.get(i);
			tail = LispCons.rebuilt(cell, replaceIfTests(cell.car(), name, param), tail);
		}
		return tail;
	}

	// A physical lambda list with every optional defaulting to the UNSUPPLIED marker.
	private static Parsed withUnsuppliedDefaults(Parsed parsed) {
		List<OptionalParam> optionals = new ArrayList<>(parsed.optionals().size());
		for (OptionalParam opt : parsed.optionals()) {
			optionals.add(new OptionalParam(opt.name(), UNSUPPLIED, null));
		}
		return new Parsed(parsed.required(), optionals, parsed.rest(), parsed.sawKey(), parsed.keys(),
				parsed.allowOtherKeys(), parsed.auxes());
	}

	/**
	 * Expands a lambda list into the compilers' physical shape ({@link #toNative}) within
	 * {@link #MAX_PHYSICAL_PARAMS}, without the {@code %fn-block} wrap: for a definition
	 * expanded before the backend is known and shared with the interpreter -- an
	 * {@code flet}/{@code labels} local, whose expansion builds its own {@code block}.
	 * The rebuilt lambda list ({@code &optional #opt0 ... &rest #rest}) is one every
	 * backend takes: a compiler as it is, the interpreter by binding a missing optional
	 * to {@link #UNSUPPLIED}. A parameter named like a special keeps its name here (the
	 * expansion knows no specials, and the interpreter binds one itself); a compiler
	 * binds it when the lambda reaches {@link #toNative}.
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @return the physical lambda list and the prologue-wrapped body
	 */
	public static Expanded expandPhysical(LispVal paramList, List<LispVal> body) {
		return expand(paramList, body, false, null, MAX_PHYSICAL_PARAMS, null, Set.of());
	}

	/**
	 * Whether a parsed lambda list is the compilers' physical shape this class writes --
	 * required parameters, plain {@value #OPT_VAR_PREFIX}N optionals, the internal rest
	 * parameter and nothing else -- so expanding it again must neither wrap a second
	 * prologue around the body nor move the optionals back onto the rest list.
	 */
	private static boolean isPhysicalShape(Parsed parsed) {
		if (parsed.optionals().isEmpty() || parsed.sawKey() || !parsed.auxes().isEmpty() || parsed.rest() == null
				|| !REST_VAR.equals(parsed.rest().name())) {
			return false;
		}
		for (int i = 0; i < parsed.optionals().size(); i++) {
			OptionalParam opt = parsed.optionals().get(i);
			if (!(OPT_VAR_PREFIX + i).equals(opt.name().name()) || opt.suppliedP() != null
					|| !(opt.defaultForm() instanceof LispNil)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The bindings of one physical optional: its supplied-p variable, when it has one,
	 * the test's t/nil, and the user's parameter the argument when the caller passed one
	 * and the default otherwise. The parameter's own binding tests the physical parameter
	 * again rather than reading the supplied-p variable: a test of {@code %supplied-p} is
	 * one reference comparison, where a read of the variable is a test of the boxed
	 * boolean the binding had to build.
	 */
	private static void appendPhysicalOptionalBindings(OptionalParam opt, LispSymbol param, List<LispVal> bindings) {
		LispVal supplied = call(LispNames.SUPPLIED_P_INTERNAL, param);
		if (opt.suppliedP() != null) {
			bindings.add(list(opt.suppliedP(), supplied));
		}
		bindings.add(list(opt.name(), list(new LispSymbol(LispNames.IF), supplied, param, opt.defaultForm())));
	}

	private static LispVal letStar(List<LispVal> bindings, List<LispVal> body) {
		List<LispVal> letParts = new ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET_STAR));
		letParts.add(list(bindings.toArray(LispVal[]::new)));
		letParts.addAll(body);
		return list(letParts.toArray(LispVal[]::new));
	}

	/**
	 * The throwaway {@code let*} binding that signals when arguments remain past the last
	 * optional -- nested {@code cdr}s for a tail of up to {@value #NESTED_CDR_MAX}:
	 *
	 * <pre>
	 * (__ll_arity (if (nthcdr k rest) (%program-error (%arity-surplus-message max req rest [name])) nil))
	 * </pre>
	 *
	 * The function's name, when it has one, rides along as a string literal: each backend
	 * reports the surplus under {@code BuiltinFunctionWrappers.arityOperator} of it --
	 * the rule its missing-argument check follows -- so a built-in's function value names
	 * the built-in and a program's own function stays {@code Function}. The rule lives
	 * above this package, hence the name and not the operator.
	 *
	 * INLINE, not a helper call like the keyword check: first-class built-in wrappers
	 * ({@code BuiltinFunctionWrappers}) and several expansions build {@code &optional}
	 * lambdas while the backend compiles, long after any program scan could prepend a
	 * helper. The message is a primitive rather than Lisp over {@code prin1-to-string}:
	 * on the JVM that spelling pulled the mutable-string wrap, the generic {@code length}
	 * and the code-point helpers into every program with an {@code &optional} function
	 * ({@code .kb/lambda-lists.md}). The message is computed, so the compilers' static
	 * program-error warning (literal messages only) never fires for it.
	 */
	private static LispVal tooManyArgsCheck(LispVal restVar, int required, int optionals,
			@Nullable String functionName) {
		// Nested cdrs for a short tail: on the JVM each is a few inline bytes, where
		// nthcdr brings its runtime helper into a program that may not otherwise have it.
		LispVal beyond = restVar;
		if (optionals <= NESTED_CDR_MAX) {
			for (int i = 0; i < optionals; i++) {
				beyond = call(LispNames.CDR, beyond);
			}
		}
		else {
			beyond = list(new LispSymbol(LispNames.NTHCDR), new LispInteger(optionals), restVar);
		}
		LispVal message = functionName == null
				? list(new LispSymbol(LispNames.ARITY_SURPLUS_MESSAGE_INTERNAL), new LispInteger(required + optionals),
						new LispInteger(required), restVar)
				: list(new LispSymbol(LispNames.ARITY_SURPLUS_MESSAGE_INTERNAL), new LispInteger(required + optionals),
						new LispInteger(required), restVar, new LispString(functionName));
		LispVal signal = list(new LispSymbol(LispNames.PROGRAM_ERROR_INTERNAL), message);
		return list(new LispSymbol(ARITY_VAR), list(new LispSymbol(LispNames.IF), beyond, signal, LispNil.INSTANCE));
	}

	/**
	 * The throwaway {@code let*} binding that signals when a destructured list is longer
	 * than a pattern of {@code required} plain elements with nothing after them: the
	 * {@link #tooManyArgsCheck} shape, always over nested {@code cdr}s (the pattern's
	 * {@code car} accessors walk the same chain, so nothing new is pulled in).
	 * @param source the (side-effect-free) expression holding the destructured list
	 * @param required the pattern's element count
	 * @return the binding
	 */
	public static LispVal destructuringSurplusCheck(LispVal source, int required) {
		LispVal beyond = source;
		for (int i = 0; i < required; i++) {
			beyond = call(LispNames.CDR, beyond);
		}
		LispVal message = list(new LispSymbol(LispNames.ARITY_SURPLUS_MESSAGE_INTERNAL), new LispInteger(required),
				new LispInteger(0), source);
		LispVal signal = list(new LispSymbol(LispNames.PROGRAM_ERROR_INTERNAL), message);
		return list(new LispSymbol(ARITY_VAR), list(new LispSymbol(LispNames.IF), beyond, signal, LispNil.INSTANCE));
	}

	/**
	 * The throwaway {@code let*} binding that signals when a destructured list runs out
	 * before a pattern of {@code required} plain elements: one
	 * {@code (consp <cdr chain>)} test per element, inside out, so the depth that runs
	 * out reports its own literal count --
	 *
	 * <pre>
	 * (__ll_missing (if (consp source) (if (consp (cdr source)) nil (%program-error (%arity-missing-message 2 1))) (%program-error (%arity-missing-message 2 0))))
	 * </pre>
	 *
	 * The message is the lower-bound half of the arity report
	 * ({@code Function expects at least N argument(s), got M}), the twin of the surplus
	 * check's upper bound. Both counts are literals, so no {@code prin1-to-string},
	 * {@code length} or {@code nthcdr} rides the error path; the message stays a computed
	 * call, keeping the compilers' static program-error warning (literal messages only)
	 * quiet.
	 * @param source the (side-effect-free) expression holding the destructured list
	 * @param required the pattern's element count
	 * @return the binding
	 */
	public static LispVal destructuringMissingCheck(LispVal source, int required) {
		LispVal form = LispNil.INSTANCE;
		for (int got = required - 1; got >= 0; got--) {
			LispVal chain = source;
			for (int i = 0; i < got; i++) {
				chain = call(LispNames.CDR, chain);
			}
			LispVal message = list(new LispSymbol(LispNames.ARITY_MISSING_MESSAGE_INTERNAL), new LispInteger(required),
					new LispInteger(got));
			LispVal signal = list(new LispSymbol(LispNames.PROGRAM_ERROR_INTERNAL), message);
			form = list(new LispSymbol(LispNames.IF), list(new LispSymbol(LispNames.CONSP), chain), form, signal);
		}
		return list(new LispSymbol(MISSING_VAR), form);
	}

	/**
	 * Lowers {@code (%arity-surplus-message max req rest [name])} for a backend without a
	 * runtime helper for it (WASM) to
	 * {@code (%string-concat "Function expects at most MAX argument(s), got " (%prin1-to-string (+ req (length rest))))}
	 * -- the non-consulting conversion, since the count is decimal whatever
	 * {@code *print-base*} says -- with {@code operator} in place of {@code Function}
	 * when there is one. {@code operator} is what the backend made of the form's function
	 * name ({@link #aritySurplusFunctionName}).
	 * @param form the {@code %arity-surplus-message} form
	 * @param operator the operator the message names, or {@code null} for
	 * {@code Function}
	 * @return the lowered form
	 */
	public static LispVal lowerAritySurplusMessage(LispCons form, @Nullable String operator) {
		List<LispVal> args = form.toList();
		int max = (int) ((LispInteger) args.get(1)).value();
		long required = ((LispInteger) args.get(2)).value();
		LispVal rest = args.get(3);
		LispVal count = required == 0 ? call(LispNames.LENGTH, rest)
				: list(new LispSymbol(LispNames.ADD), new LispInteger(required), call(LispNames.LENGTH, rest));
		String zero = ClosRegistry.aritySurplusMessage(max, 0);
		String opening = zero.substring(0, zero.length() - 1);
		if (operator == null) {
			return list(new LispSymbol(LispNames.STRING_CONCAT), new LispString(opening),
					call(LispNames.PRIN1_TO_STRING_RAW, count));
		}
		// The operator in front of the opening's shared remainder: one literal per
		// operator and one per bound, where a whole opening per operator put +2 KB on
		// the eval-carrying module, whose registry holds every wrapper.
		LispVal tail = list(new LispSymbol(LispNames.STRING_CONCAT),
				new LispString(opening.substring(ClosRegistry.ARITY_ANONYMOUS_OPERATOR.length())),
				call(LispNames.PRIN1_TO_STRING_RAW, count));
		return list(new LispSymbol(LispNames.STRING_CONCAT), new LispString(operator), tail);
	}

	/**
	 * The name of the function whose {@code &optional} surplus check this
	 * {@code (%arity-surplus-message max req rest [name])} form is, or {@code null} for
	 * an anonymous one ({@link #tooManyArgsCheck}).
	 * @param form the {@code %arity-surplus-message} form
	 * @return the function's name, or {@code null}
	 */
	public static @Nullable String aritySurplusFunctionName(LispCons form) {
		List<LispVal> args = form.toList();
		return args.size() > 4 && args.get(4) instanceof LispString name ? name.value() : null;
	}

	/**
	 * Lowers {@code (%arity-missing-message required got)} for a backend without a
	 * runtime helper for it (WASM) to
	 * {@code (%string-concat "Function expects at least N argument(s), got " (%prin1-to-string-raw GOT))}
	 * -- both counts are literals, so unlike the surplus lowering no {@code length} rides
	 * along. The prefix is sliced off the zero-count rendering, keeping the plural with
	 * the required count; the conversion is the non-consulting one, decimal whatever
	 * {@code *print-base*} says.
	 * @param form the {@code %arity-missing-message} form
	 * @return the lowered form
	 */
	public static LispVal lowerArityMissingMessage(LispCons form) {
		List<LispVal> args = form.toList();
		int required = (int) ((LispInteger) args.get(1)).value();
		LispVal got = args.get(2);
		String zero = ClosRegistry.arityMessage(required, true, 0);
		return list(new LispSymbol(LispNames.STRING_CONCAT), new LispString(zero.substring(0, zero.length() - 1)),
				call(LispNames.PRIN1_TO_STRING_RAW, got));
	}

	/**
	 * The native lambda shape as the compilers consume it: physical parameter names
	 * (required parameters, then the physical optionals, then -- when variadic -- the
	 * rest parameter as the last name), the variadic flag, how many physical optionals
	 * sit before the rest parameter, and the (possibly prologue-wrapped) body.
	 *
	 * @param paramNames the physical parameter names, rest parameter last when variadic
	 * @param variadic whether the last parameter collects the remaining arguments
	 * @param optionals how many of the names before the rest parameter are physical
	 * optionals, which a caller fills with an argument or the UNSUPPLIED marker; zero
	 * unless variadic
	 * @param body the body forms
	 */
	public record NativeForm(List<String> paramNames, boolean variadic, int optionals, List<LispVal> body) {

		/**
		 * {@return the arguments a call must pass at least} -- every name before the
		 * physical optionals
		 */
		public int required() {
			return this.paramNames.size() - (this.variadic ? 1 : 0) - this.optionals;
		}

	}

	/**
	 * Parses a lambda list into the {@link NativeForm} the compilers consume, desugaring
	 * extensions when present into the compilers' shape: as many optionals as fit
	 * {@code maxParams} beside the required parameters and the rest list travel as
	 * parameters of their own, and a parameter named like a special variable binds it
	 * dynamically ({@link #bindSpecialParameters}).
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @param maxParams the most physical parameters (the closure environment not counted)
	 * the backend lets a function take
	 * @param specials the program's special variables
	 * @return the native form
	 */
	public static NativeForm toNative(LispVal paramList, List<LispVal> body, int maxParams, Set<String> specials) {
		return toNative(paramList, body, maxParams, null, specials);
	}

	/**
	 * {@link #toNative(LispVal, List, int, Set)} for a function with a name: the
	 * {@code &optional} surplus check carries it ({@link #tooManyArgsCheck}).
	 * @param paramList the raw parameter list AST
	 * @param body the body forms
	 * @param maxParams the most physical parameters the backend lets a function take
	 * @param functionName the function's name, or {@code null} for an anonymous one
	 * @param specials the program's special variables
	 * @return the native form
	 */
	public static NativeForm toNative(LispVal paramList, List<LispVal> body, int maxParams,
			@Nullable String functionName, Set<String> specials) {
		Expanded e = expand(paramList, body, true, null, maxParams, functionName, specials);
		List<String> names = new ArrayList<>(e.required().size() + e.optionals().size() + 1);
		for (LispSymbol s : e.required()) {
			names.add(s.name());
		}
		for (LispSymbol s : e.optionals()) {
			names.add(s.name());
		}
		if (e.rest() != null) {
			names.add(e.rest().name());
		}
		return new NativeForm(names, e.rest() != null, e.optionals().size(), e.body());
	}

	/**
	 * Rewrites every {@code defun}/{@code lambda} form in the program whose parameter
	 * list uses lambda-list keywords into the compilers' native shape via
	 * {@link #toNative}'s expansion -- required parameters, the physical optionals that
	 * fit {@code maxParams}, and a rest list. A required or rest parameter named like a
	 * special keeps its name here: {@link #toNative} binds it where a compiler extracts
	 * the function, knowing every special. Quoted data is left untouched (so forms
	 * destined for a runtime {@code eval} keep their source shape). Used by the compilers
	 * as a pre-pass; the interpreter expands lazily at lambda-creation time instead.
	 *
	 * <p>
	 * A program that spells {@code &key} anywhere gets the two keyword helpers
	 * ({@link #runtimeDefun}) PREPENDED, because the prologues this pass and every later
	 * expansion emit call them: the ones this pass writes for
	 * {@code defun}/{@code lambda} as well as the ones {@code flet}/{@code labels} and
	 * {@code destructuring-bind} write while the backend compiles their bodies -- both
	 * spell {@code &key} in the program, so the one scan covers every caller. Quoted data
	 * counts too: the scan may only over-approximate (an unused helper is two small
	 * defuns the shakers collect), never miss. A program without {@code &key} is returned
	 * form for form.
	 * @param program the top-level forms
	 * @param maxParams the most physical parameters the backend lets a function take
	 * ({@link #toNative})
	 * @param specials the special variables the program declares: a supplied-p variable
	 * named like one keeps its binding
	 * @return the rewritten forms
	 */
	public static List<LispVal> desugarProgram(List<LispVal> program, int maxParams, Set<String> specials) {
		List<LispVal> out = new ArrayList<>(program.size() + 2);
		if (program.stream().anyMatch(LambdaLists::spellsKey)) {
			out.addAll(runtimeDefuns());
		}
		for (LispVal form : program) {
			out.add(desugar(form, maxParams, specials));
		}
		return out;
	}

	// The CDR direction is a loop: a long body is a flat list, and one frame per element
	// would be the list's length deep. Only the CAR direction recurses.
	private static boolean spellsKey(LispVal form) {
		LispVal p = form;
		while (p instanceof LispCons cons) {
			if (spellsKey(cons.car())) {
				return true;
			}
			p = cons.cdr();
		}
		return p instanceof LispSymbol sym && LispNames.LAMBDA_KEY.equals(sym.name());
	}

	/**
	 * Whether {@code name} is one of the keyword helpers {@link #runtimeDefun} defines.
	 * @param name a function name
	 * @return {@code true} for {@code %ll-key-cell} and {@code %ll-check-keys}
	 */
	public static boolean isRuntimeHelper(String name) {
		return LispNames.LL_KEY_CELL.equals(name) || LispNames.LL_CHECK_KEYS.equals(name);
	}

	/**
	 * Both keyword helpers, in definition order, for a program that expands {@code &key}
	 * ({@link #desugarProgram} prepends them).
	 * @return the two {@code defun} forms
	 */
	public static List<LispVal> runtimeDefuns() {
		return List.of(runtimeDefun(LispNames.LL_KEY_CELL), runtimeDefun(LispNames.LL_CHECK_KEYS));
	}

	/**
	 * The {@code defun} of one keyword helper -- the single definition every backend
	 * runs: the compilers get it through {@link #desugarProgram}, the interpreter
	 * evaluates it into its global environment when a prologue first calls it.
	 *
	 * <p>
	 * {@code (%ll-key-cell plist key upper)} is the {@code do} loop returning the plist
	 * cell whose indicator is {@code key} -- or {@code upper}, the upcased twin a
	 * lowercase-authored keyword also accepts, nil when the spellings coincide -- or nil:
	 * the same stepping shape {@code getf} expands to, over a plist that may be dotted or
	 * odd. {@code (%ll-check-keys plist known)} walks the same plist and signals a
	 * {@code program-error} through {@code %program-error} on the first indicator outside
	 * {@code known} unless the caller passed {@code :allow-other-keys} with a true value,
	 * and on a tail of ODD length, which no {@code :allow-other-keys} makes legal (CLHS
	 * 3.5.1.6: the last indicator has no value to pair with). The unknown-indicator
	 * complaint comes FIRST: a trailing POSITIONAL argument is both an unknown indicator
	 * and an odd tail, and naming it is the more useful reading ({@code (linalg:sum m 0)}
	 * -- the numpy-style libraries' own trap); the odd-length complaint is what is left,
	 * a DECLARED keyword with no value. {@code :allow-other-keys} itself is accepted in
	 * both spellings: the upcase reader mode upcases a caller's while a
	 * lowercase-authored library keeps its own.
	 * @param name {@link LispNames#LL_KEY_CELL} or {@link LispNames#LL_CHECK_KEYS}
	 * @return the {@code defun} form
	 */
	public static LispVal runtimeDefun(String name) {
		LispSymbol plist = new LispSymbol(PLIST_PARAM);
		LispSymbol cur = new LispSymbol(CUR_VAR);
		LispVal bindings = list(list(cur, plist, call("CDDR", cur)));
		LispVal endClause = list(call(LispNames.ATOM, cur), LispNil.INSTANCE);
		LispVal indicator = call(LispNames.CAR, cur);
		if (LispNames.LL_KEY_CELL.equals(name)) {
			LispSymbol key = new LispSymbol(KEY_PARAM);
			LispSymbol upper = new LispSymbol(UPPER_PARAM);
			// eq, not eql: the key is always a keyword SYMBOL, for which the two agree,
			// and eq is one ref.eq where the generic eql carries every numeric arm.
			LispVal match = list(new LispSymbol(LispNames.OR),
					list(new LispSymbol(LispNames.EQ_GENERAL), indicator, key), list(new LispSymbol(LispNames.AND),
							upper, list(new LispSymbol(LispNames.EQ_GENERAL), indicator, upper)));
			LispVal body = list(new LispSymbol(LispNames.IF), match, list(new LispSymbol(LispNames.RETURN), cur),
					LispNil.INSTANCE);
			return list(new LispSymbol(LispNames.DEFUN), new LispSymbol(name), list(plist, key, upper),
					list(new LispSymbol(LispNames.DO), bindings, endClause, body));
		}
		if (!LispNames.LL_CHECK_KEYS.equals(name)) {
			throw new IllegalArgumentException("Not a lambda-list helper: " + name);
		}
		LispSymbol known = new LispSymbol(KNOWN_PARAM);
		LispSymbol allowUpper = new LispSymbol(LispNames.ALLOW_OTHER_KEYS_KEYWORD);
		LispSymbol allowLower = new LispSymbol(LispNames.ALLOW_OTHER_KEYS_KEYWORD.toLowerCase(java.util.Locale.ROOT));
		LispVal accepted = list(new LispSymbol(LispNames.OR), list(new LispSymbol(LispNames.MEMBER), indicator, known),
				list(new LispSymbol(LispNames.EQ_GENERAL), indicator, allowUpper),
				list(new LispSymbol(LispNames.EQ_GENERAL), indicator, allowLower),
				list(new LispSymbol(LispNames.GETF), plist, allowUpper),
				list(new LispSymbol(LispNames.GETF), plist, allowLower));
		// The message is concatenated rather than formatted: no format machinery for a
		// check every &key function carries.
		LispVal signal = list(new LispSymbol(LispNames.PROGRAM_ERROR_INTERNAL),
				list(new LispSymbol(LispNames.STRING_CONCAT), new LispString("Unknown keyword argument: "),
						call(LispNames.PRIN1_PIECE_INTERNAL, indicator)));
		LispVal oddSignal = list(new LispSymbol(LispNames.PROGRAM_ERROR_INTERNAL),
				list(new LispSymbol(LispNames.STRING_CONCAT), new LispString("Odd number of keyword arguments: "),
						call(LispNames.PRIN1_PIECE_INTERNAL, indicator)));
		LispVal odd = list(new LispSymbol(LispNames.IF), call(LispNames.ATOM, call(LispNames.CDR, cur)), oddSignal,
				LispNil.INSTANCE);
		LispVal body = list(new LispSymbol(LispNames.IF), accepted, odd, signal);
		return list(new LispSymbol(LispNames.DEFUN), new LispSymbol(name), list(plist, known),
				list(new LispSymbol(LispNames.DO), bindings, endClause, body));
	}

	private static LispVal desugar(LispVal form, int maxParams, Set<String> specials) {
		// A form with no lambda-list keyword and no return-from anywhere under it -- most
		// of every program -- comes back AS IT WAS READ. Cons identity is what
		// {@link SourceProvenance} keys a form's source position on, so a rebuild here
		// would drop every position below the top level of a program that has nothing to
		// desugar. The cdr spine is walked in a loop, so a long list costs no stack.
		return LispTrees.rebuildSpine(form, node -> desugarHead(node, maxParams, specials),
				node -> desugar(node, maxParams, specials));
	}

	/**
	 * What {@link #desugar} makes of a node without splitting it into car and cdr: an
	 * atom, a quoted form, a rebuilt lambda/defun -- or {@code null} for an ordinary
	 * cell.
	 */
	private static @Nullable LispVal desugarHead(LispVal form, int maxParams, Set<String> specials) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym) {
			String name = sym.name();
			if (LispNames.QUOTE.equals(name)) {
				return form;
			}
			List<LispVal> parts = cons.toList();
			// A defun/lambda is rebuilt through the desugaring when its parameter list
			// uses lambda-list keywords OR its body uses return-from (the %fn-block wrap
			// lives there so the lambda compilers' toNative path shares it). A parameter
			// named like a special is left for toNative to bind: it is the one that knows
			// every special, the standard variables declared after this pass included.
			if (LispNames.LAMBDA.equals(name) && parts.size() >= 2 && (usesLambdaListKeywords(parts.get(1))
					|| anyContainsReturnFrom(parts.subList(2, parts.size())))) {
				Expanded e = desugared(parts.get(1), parts.subList(2, parts.size()), true, null, maxParams, null,
						specials);
				return rebuildFunction(sym, null, e, maxParams, specials);
			}
			if (LispNames.DEFUN.equals(name) && parts.size() >= 3 && (usesLambdaListKeywords(parts.get(2))
					|| anyContainsReturnFrom(parts.subList(3, parts.size())))) {
				Expanded e = desugared(parts.get(2), parts.subList(3, parts.size()), true, defunBlockName(parts.get(1)),
						maxParams, parts.get(1) instanceof LispSymbol functionName ? functionName.name() : null,
						specials);
				return rebuildFunction(sym, parts.get(1), e, maxParams, specials);
			}
		}
		return null;
	}

	/**
	 * Compile-path {@code return-from} support: when the body contains a
	 * {@code (return-from name value)} form, the whole body is wrapped in the internal
	 * {@code (%fn-block name body...)} function boundary ({@code name} is the defun's
	 * name, {@code nil} for a lambda). The backends compile it as a named block target
	 * that is ALSO the fallback for a {@code return-from} whose name matches no lexically
	 * enclosing block, so an unmatched named return exits the current function -- which
	 * keeps a {@code return-from} inside a lambda a lambda-local exit (the interpreter's
	 * dynamic-extent crossing cannot span a separately compiled method). The wrap is
	 * idempotent: a body already consisting of a single {@code %fn-block} form (a lambda
	 * rebuilt by {@link #desugarProgram} re-entering through {@code toNative}) is
	 * returned unchanged.
	 * @param blockNameSym the block name symbol, or {@code null} for a lambda
	 * @param body the defun/lambda body forms
	 * @return the body, block-wrapped when return-from is present
	 */
	private static List<LispVal> wrapReturnFrom(@Nullable LispVal blockNameSym, List<LispVal> body) {
		if (!anyContainsReturnFrom(body)) {
			return body;
		}
		if (body.size() == 1 && body.get(0) instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.FN_BLOCK_INTERNAL.equals(op.name())) {
			return body;
		}
		List<LispVal> parts = new ArrayList<>(body.size() + 2);
		parts.add(new LispSymbol(LispNames.FN_BLOCK_INTERNAL));
		parts.add(blockNameSym != null ? blockNameSym : LispNil.INSTANCE);
		parts.addAll(body);
		return List.of(list(parts.toArray(LispVal[]::new)));
	}

	/**
	 * The block name a defun body's {@code %fn-block} wrap carries: the function name, or
	 * the PLACE name for a {@code (defun (setf name) ...)} setf-function -- mirroring the
	 * interpreter's {@code evalDefun} block naming.
	 */
	/**
	 * If {@code nameForm} is a {@code (setf NAME)} function-name designator (as used in
	 * {@code defun} and {@code #'}), returns the place {@code NAME} symbol; otherwise
	 * {@code null}. It is a pure shape test on a function-name designator, so it lives
	 * with the other lambda-list/name-shape helpers rather than in the expander (which
	 * sits above the reader and must stay unreachable from this package).
	 * @param nameForm the candidate function-name designator
	 * @return the place symbol, or {@code null} if not a setf-function designator
	 */
	public static @Nullable LispSymbol setfFunctionPlaceName(LispVal nameForm) {
		if (nameForm instanceof LispCons cons && cons.car() instanceof LispSymbol op && LispNames.SETF.equals(op.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol place
				&& rest.cdr() instanceof LispNil) {
			return place;
		}
		return null;
	}

	private static @Nullable LispVal defunBlockName(LispVal nameForm) {
		LispSymbol setfPlace = setfFunctionPlaceName(nameForm);
		if (setfPlace != null) {
			return setfPlace;
		}
		return nameForm instanceof LispSymbol sym ? sym : null;
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
	// its own return-from scope: the scan stops at the boundary so the inner function
	// wraps its own body in its own %fn-block (a return-from inside a lambda passed to
	// map*/reduce exits the lambda, not the outer defun -- a goto cannot cross into the
	// lambda's separately compiled method).
	private static boolean containsReturnFrom(LispVal form) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol op) {
				if (LispNames.QUOTE.equals(op.name()) || isNestedFunction(op.name())) {
					return false;
				}
				if (LispNames.RETURN_FROM.equals(op.name())) {
					return true;
				}
			}
			if (containsReturnFrom(cons.car())) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
	}

	private static boolean isNestedFunction(String op) {
		return LispNames.LAMBDA.equals(op) || LispNames.DEFUN.equals(op);
	}

	private static LispVal rebuildFunction(LispSymbol op, @Nullable LispVal name, Expanded e, int maxParams,
			Set<String> specials) {
		List<LispVal> paramParts = new ArrayList<>(e.required());
		if (!e.optionals().isEmpty()) {
			paramParts.add(new LispSymbol(LispNames.LAMBDA_OPTIONAL));
			paramParts.addAll(e.optionals());
		}
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
			parts.add(desugar(bodyForm, maxParams, specials));
		}
		return list(parts.toArray(LispVal[]::new));
	}

	/**
	 * Appends the {@code let*} bindings desugaring the parsed
	 * {@code &optional}/{@code &rest}/{@code &key}/{@code &aux} parameters over
	 * {@code restVar} (the variable holding the argument list tail), the optionals being
	 * {@code stepped} -- every one in the interpreter's shape and the destructuring path,
	 * those past the physical ones in the compilers'. When {@code aliasRest} is
	 * {@code true} the declared {@code &rest} parameter is always bound to
	 * {@code restVar} (the destructuring path, where {@code restVar} is a generated
	 * temporary); otherwise the alias is needed exactly when {@code restVar} is not the
	 * declared parameter itself -- a lambda list with optionals, whose rest list is the
	 * internal one (the native-parameter path, where a keyword-free {@code &rest}
	 * parameter of an optional-free list IS the physical rest parameter).
	 */
	private static void appendPrologueBindings(Parsed parsed, List<OptionalParam> stepped, LispSymbol restVar,
			boolean aliasRest, List<LispVal> bindings) {
		for (OptionalParam opt : stepped) {
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
		if (parsed.rest() != null && (aliasRest || !parsed.rest().name().equals(restVar.name()))) {
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
	 * {@code destructuring-bind} and macro lambda lists. The surplus-element check (a
	 * tail with neither {@code &rest}/{@code &body} nor {@code &key}) and the
	 * unknown-keyword check are appended as throwaway bindings so the whole tail stays a
	 * flat binding list.
	 * @param tailParams the tail elements, starting with a lambda-list keyword
	 * @param required how many required elements precede the tail (the message's count)
	 * @param restVar the variable holding the remaining list
	 * @param out the binding list to append to
	 */
	public static void appendTailBindings(List<LispVal> tailParams, int required, LispSymbol restVar,
			List<LispVal> out) {
		Parsed parsed = parse(tailParams);
		if (parsed.rest() == null && !parsed.sawKey()) {
			// Nothing consumes the list past the optionals: a surplus element signals,
			// before any default runs -- the function lambda lists' check.
			out.add(tooManyArgsCheck(restVar, required, parsed.optionals().size(), null));
		}
		appendPrologueBindings(parsed, parsed.optionals(), restVar, true, out);
		if (parsed.sawKey() && !parsed.allowOtherKeys()) {
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
			boolean sawKey, List<KeyParam> keys, boolean allowOtherKeys, List<AuxParam> auxes) {
	}

	private static Parsed parse(List<LispVal> params) {
		List<LispSymbol> required = new ArrayList<>();
		List<OptionalParam> optionals = new ArrayList<>();
		LispSymbol rest = null;
		boolean sawKey = false;
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
				if (section == 3) {
					// &key with no key parameters still switches the tail to keyword
					// convention: the marker itself must not be lost (a bare
					// (x &key &allow-other-keys) accepts any keyword tail).
					sawKey = true;
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
		return new Parsed(required, optionals, rest, sawKey, keys, allowOtherKeys, auxes);
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
	 * {@code (%ll-key-cell src :kw upper)}: the plist cons cell whose car is the keyword
	 * (or its upcased twin), or nil -- {@link #runtimeDefun} has the loop.
	 */
	private static LispVal keyCellScan(LispSymbol source, LispSymbol keyword) {
		// A lowercase-derived keyword (an internal lowercase-authored &key parameter)
		// also accepts its upcased twin: user call sites read upcased. Nil when the
		// spellings coincide, so a nil INDICATOR never matches through the twin.
		LispSymbol upper = upcasedTwin(keyword);
		return list(new LispSymbol(LispNames.LL_KEY_CELL), source, keyword, upper != null ? upper : LispNil.INSTANCE);
	}

	// The all-uppercase spelling of a keyword whose name has lowercase letters, or null
	// when the spellings coincide.
	private static @Nullable LispSymbol upcasedTwin(LispSymbol keyword) {
		String upper = keyword.name().toUpperCase(java.util.Locale.ROOT);
		return upper.equals(keyword.name()) ? null : new LispSymbol(upper);
	}

	/**
	 * {@code (%ll-check-keys src '(:kw ...))}: the unknown-keyword / odd-tail
	 * {@code program-error} check over the keyword tail against the declared keywords
	 * (each with its upcased twin) -- {@link #runtimeDefun} has the loop and the
	 * {@code :allow-other-keys} rules, so the literal carries only what differs per
	 * function.
	 */
	private static LispVal unknownKeyCheck(LispSymbol source, List<KeyParam> keys) {
		List<LispVal> known = new ArrayList<>();
		for (KeyParam key : keys) {
			known.add(key.keyword());
			LispSymbol upper = upcasedTwin(key.keyword());
			if (upper != null) {
				known.add(upper);
			}
		}
		LispVal knownList = known.isEmpty() ? LispNil.INSTANCE
				: list(new LispSymbol(LispNames.QUOTE), list(known.toArray(LispVal[]::new)));
		return list(new LispSymbol(LispNames.LL_CHECK_KEYS), source, knownList);
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
