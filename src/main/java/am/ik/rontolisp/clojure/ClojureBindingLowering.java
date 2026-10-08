package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Binding forms of the Clojure lowering: def/defn/fn/let/loop/letfn/recur and
 * destructuring.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureBindingLowering {

	private ClojureBindingLowering() {
	}

	/**
	 * A {@code def} lowered for a namespace init: the setq statement, plus -- for a
	 * {@code ^:dynamic} var, into {@code hoisted} -- the declaim and the binding-depth
	 * counter, which stay top-level at the head where {@code SpecialVarCollector} and
	 * {@code GlobalVarCollector} read them (a {@code defparameter} nested in the init
	 * would proclaim nothing and hide the counter's store). A var whose metadata
	 * evaluates something (a {@code :test} fn) adds the store of that metadata behind the
	 * definition.
	 * @param ctx the lowering
	 * @param form the def datum (its position for the var's metadata)
	 * @param items the def datum's items
	 * @param hoisted where a dynamic var's top-level forms go, or null for the ordinary
	 * top-level shape
	 * @return the forms (the init statement, or the top-level shapes)
	 */
	static List<LispVal> defForms(ClojureLowering ctx, LispVal form, List<LispVal> items,
			@Nullable List<LispVal> hoisted) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "def takes a name and an optional value");
		LispVal nameDatum = items.get(1);
		String name = ClojureLowerUtil.plainName(nameDatum, "def");
		boolean dynamic = ClojureLowerUtil.nameIsDynamic(nameDatum);
		int at = 2;
		LispString doc = null;
		if (items.size() > at + 1 && items.get(at) instanceof LispString string) {
			doc = string; // the docstring (only with a value behind it: a lone string is
							// the value)
			at++;
		}
		LispVal attrMap = null;
		if (items.size() > at + 1 && isAttrMap(items.get(at))) {
			attrMap = items.get(at); // the attr map (only with a value behind it: a lone
										// map is the value)
			at++;
		}
		ClojureLowerUtil.isTrue(items.size() == at || items.size() == at + 1, "def takes a name and an optional value");
		// The value lowers against the OLD binding, so (def p (memoize p)) after a
		// (defn p ...) captures the function cell (a FUNCTION) instead of reading
		// the still-unbound value cell; only then does the name become a VARIABLE.
		boolean valueless = items.size() == at;
		LispVal value = valueless ? ClojureLowering.NIL_CONST : ctx.lower(items.get(at));
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(nameDatum));
		ctx.globals.put(key, ClojureLowering.Kind.VARIABLE);
		ctx.macros.remove(key); // a definition wins over the macro it shadows
		if (dynamic) {
			ctx.dynamicVars.add(key);
		}
		boolean redef = ctx.redefinable(key, nameDatum);
		ClojureDispatchLowering.recordClassDispatchFn(ctx, key, dynamic || redef,
				items.size() == at + 1 ? items.get(at) : null);
		if (ClojureLowerUtil.yieldsFun(value) && !redef) {
			ctx.globalDirectFuns.add(key);
		}
		else {
			ctx.globalDirectFuns.remove(key);
		}
		List<LispVal> metaStore = ClojureVarLowering.record(ctx, key, form, nameDatum, null, doc, attrMap, false,
				false);
		if (valueless) {
			// the oracle's (def x) interns x and leaves a bound root alone; an
			// unbound one holds the unbound root
			return valuelessDefForms(ctx, key, dynamic, metaStore, hoisted);
		}
		if (dynamic) {
			// a dynamic var is a special: defparameter always sets it (like def)
			// and proclaims it, so binding rebinds it with dynamic extent; the
			// binding-depth counter beside it lets set! test at run time whether
			// the var is thread-bound. Two top-level forms, so both keep their
			// defparameter head for SpecialVarCollector. In a namespace init the
			// setq runs at load time instead, and the declaim plus the counter
			// ride top-level, where both collectors read them.
			if (hoisted != null) {
				hoisted.add(ClojureLowering.declaimSpecial(ClojureLowering.varSym(key),
						ClojureLowering.boundDepthSym(key)));
				hoisted.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"),
						ClojureLowering.boundDepthSym(key), new LispInteger(0)));
				return withMetaStore(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowering.varSym(key), value),
						metaStore);
			}
			List<LispVal> forms = new ArrayList<>(List.of(
					ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), ClojureLowering.varSym(key), value),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), ClojureLowering.boundDepthSym(key),
							new LispInteger(0))));
			forms.addAll(0, metaStore);
			return forms;
		}
		return withMetaStore(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowering.varSym(key), value),
				metaStore);
	}

	/**
	 * A value-less {@code def}: the unbound root where the var has none yet, and for a
	 * {@code ^:dynamic} one the declaim and the binding-depth counter a valued one adds,
	 * without a store of the root.
	 */
	private static List<LispVal> valuelessDefForms(ClojureLowering ctx, String key, boolean dynamic,
			List<LispVal> metaStore, @Nullable List<LispVal> hoisted) {
		LispVal root = ClojureVarLowering.unboundRoot(ctx, key);
		List<LispVal> forms = new ArrayList<>(metaStore);
		if (dynamic) {
			List<LispVal> special = List.of(
					ClojureLowering.declaimSpecial(ClojureLowering.varSym(key), ClojureLowering.boundDepthSym(key)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), ClojureLowering.boundDepthSym(key),
							new LispInteger(0)));
			if (hoisted != null) {
				hoisted.addAll(special);
			}
			else {
				forms.addAll(special);
			}
		}
		forms.add(root != null ? root : ClojureLowering.NIL_CONST);
		return forms;
	}

	/**
	 * The definition behind the store of its var's evaluated metadata, if any: the
	 * definition stays last, so its value is still what the form answers (a session's
	 * echo).
	 */
	private static List<LispVal> withMetaStore(LispVal definition, List<LispVal> metaStore) {
		if (metaStore.isEmpty()) {
			return List.of(definition);
		}
		List<LispVal> forms = new ArrayList<>(metaStore);
		forms.add(definition);
		return forms;
	}

	static LispVal def(ClojureLowering ctx, LispVal form, List<LispVal> items) {
		List<LispVal> forms = defForms(ctx, form, items, null);
		if (ctx.nestedDefAnswersVar) {
			forms = new ArrayList<>(forms);
			forms.add(ClojureVarLowering.definedVar(ctx, ClojureLowerUtil.plainName(items.get(1), "def")));
		}
		if (forms.size() == 1) {
			return forms.get(0);
		}
		// in a body: one progn, like a dynamic defn's defun plus defparameters
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
	}

	/** Whether the datum is an attr map (its marker head), not a value. */
	static boolean isAttrMap(LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		return parts != null && !parts.isEmpty() && ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-map");
	}

	/**
	 * A used variadic clause's worker: the entry or {@code defun} taking the rest as an
	 * ordinary parameter. A lone {@code %} no mangled identifier spells, so workers stay
	 * apart from user definitions, like the multi-{@code defn} helpers.
	 */
	static String workerName(String callName) {
		return callName + "%*";
	}

	/** The parameter shape of one parameter vector datum, without lowering anything. */
	static ClojureLowering.ParamShape paramShape(LispVal paramVector) {
		List<LispVal> names;
		try {
			names = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(paramVector), "the parameter vector of");
		}
		catch (LispReadException ex) {
			// malformed: the real pass reports the shape, so say nothing here
			return new ClojureLowering.ParamShape(false, -1);
		}
		for (int i = 0; i < names.size(); i++) {
			if (ClojureLowerUtil.isSymbolNamed(ClojureLowerUtil.stripMeta(names.get(i)), "&")) {
				return new ClojureLowering.ParamShape(true, i);
			}
		}
		return new ClojureLowering.ParamShape(false, names.size());
	}

	static boolean isVariadicParams(LispVal paramVector) {
		return paramShape(paramVector).variadic();
	}

	/**
	 * One labels entry for a used variadic clause's worker: the rest as an ordinary
	 * parameter around the clause's prologue and body, so a {@code recur} assigns
	 * exactly.
	 */
	static LispVal workerEntry(String worker, ClojureLowering.Clause clause) {
		List<LispVal> params = new ArrayList<>(clause.params());
		params.remove(ClojureLowering.AMPERSAND_REST);
		return new LispCons(new LispSymbol(worker),
				new LispCons(ClojureLowerUtil.list(params), ClojureLowerUtil.cons(clause.wrapped(), List.of())));
	}

	/** A direct call forwarding each parameter (the rest as one value) to the worker. */
	static LispVal workerCall(String worker, ClojureLowering.Clause clause) {
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(worker));
		for (LispVal param : clause.params()) {
			if (!ClojureLowering.AMPERSAND_REST.equals(param)) {
				call.add(param);
			}
		}
		return ClojureLowerUtil.list(call);
	}

	/**
	 * One already-lowered lambda behind a {@code labels} head entry plus any worker
	 * entries, answering the head: the shape a named {@code fn} always takes, an
	 * anonymous one only when a {@code recur} reached it.
	 */
	static LispVal labelsWithHead(String headName, List<LispVal> workers, LispVal lambda) {
		LispVal paramsAndBody = ((LispCons) lambda).cdr();
		List<LispVal> entries = new ArrayList<>(workers);
		entries.add(new LispCons(new LispSymbol(headName), paramsAndBody));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(entries),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), new LispSymbol(headName)));
	}

	/**
	 * A used variadic stored-method lambda splits like every other {@code fn} shape: the
	 * worker takes the rest as an ordinary parameter (the {@code recur} call assigns
	 * exactly) while the {@code &rest} head answers normal calls (wrapping, like the
	 * oracle); an unused variadic keeps its single shape. The body is the clause's
	 * wrapped body, or the fields-bound one for an inline record body.
	 */
	static LispVal splitMethodLambda(ClojureLowering ctx, String fresh, String worker, ClojureLowering.Clause clause,
			LispVal bodyForm) {
		List<LispVal> workerParams = new ArrayList<>(clause.params());
		workerParams.remove(ClojureLowering.AMPERSAND_REST);
		LispVal entry = new LispCons(new LispSymbol(worker),
				new LispCons(ClojureLowerUtil.list(workerParams), ClojureLowerUtil.cons(bodyForm, List.of())));
		LispVal head = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(clause.params()),
				workerCall(worker, clause));
		return labelsWithHead(fresh, List.of(entry), head);
	}

	static List<LispVal> defuns(ClojureLowering ctx, LispVal form, List<LispVal> items) {
		int at = 2;
		LispString doc = null;
		if (items.size() > at && items.get(at) instanceof LispString string) {
			doc = string; // the docstring
			at++;
		}
		LispVal attrMap = null;
		if (items.size() > at + 1 && isAttrMap(items.get(at))) {
			attrMap = items.get(at); // the attr map (only with a value behind it: a lone
										// map is the value)
			at++;
		}
		ClojureLowerUtil.isTrue(items.size() > at + 1 || items.size() == at + 1 && items.get(at) instanceof LispCons,
				"defn needs a parameter vector and a body");
		String name = ClojureLowerUtil.plainName(items.get(1), "defn");
		boolean dynamic = ClojureLowerUtil.nameIsDynamic(items.get(1));
		String key = ctx.intern(name,
				ClojureLowerUtil.isSymbolNamed(items.get(0), "defn-") || ClojureLowerUtil.nameIsPrivate(items.get(1)));
		// a redefinable var (with-redefs, ^:redef) keeps its function in the value
		// cell like a dynamic one, but calls it through the dispatcher: a with-redefs
		// may store any IFn there, a map or a keyword too
		boolean redef = ctx.redefinable(key, items.get(1));
		ctx.globals.put(key, dynamic || redef ? ClojureLowering.Kind.VARIABLE : ClojureLowering.Kind.FUNCTION);
		ctx.macros.remove(key); // a definition wins over the macro it shadows
		if (dynamic) {
			// a dynamic var is rebindable: the value cell holds the function
			// (proclaimed special, so binding rebinds it with dynamic extent)
			// while the function cell keeps the definition
			ctx.dynamicVars.add(key);
		}
		if (redef) {
			ctx.globalDirectFuns.remove(key);
		}
		else if (dynamic) {
			ctx.globalDirectFuns.add(key);
		}
		// a redefined defn gets a fresh internal name per definition: the call
		// sites below it call the newest, and a value position captures the
		// definition current at that point. The first definition keeps the
		// bare var symbol, so a single defn lowers exactly as before.
		int definition = ctx.defnCounts.merge(key, 1, Integer::sum);
		LispSymbol fn = ClojureLowering.defnSym(key, definition);
		String callName = fn.name();
		List<LispVal> fnParts = new ArrayList<>();
		fnParts.add(new LispSymbol("fn*")); // a special form: no program macro captures
											// it
		fnParts.addAll(items.subList(at, items.size()));
		ClojureDispatchLowering.recordClassDispatchFn(ctx, key, dynamic || redef, ClojureLowerUtil.list(fnParts));
		// the arities an export of the var may take ({:wasm/export ...},
		// rontolisp.wasm/export, a world's label), and the export the definition's
		// metadata declares, its spec held quoted so the metadata stays a constant
		List<ClojureLowering.ParamShape> shapes = new ArrayList<>();
		for (LispVal arglist : arglistsOf(items.subList(at, items.size()))) {
			shapes.add(paramShape(arglist));
		}
		ctx.wasm.defnShapes.put(key, List.copyOf(shapes));
		ClojureWasmLowering.DefnExport exported = ClojureWasmLowering.defnExport(ctx, name, items.get(1), attrMap,
				form);
		// recorded ahead of the body, so a #' of the name inside it sees this
		// definition's metadata
		List<LispVal> metaStore = ClojureVarLowering.record(ctx, key, form, exported.nameDatum(),
				arglistsOf(items.subList(at, items.size())), doc, exported.attrMap(),
				ClojureLowerUtil.isSymbolNamed(items.get(0), "defn-"), false);
		List<LispVal> forms;
		if (items.get(at) instanceof LispCons && !isVectorDatum(items.get(at))) {
			forms = multiDefun(ctx, name, items.subList(at, items.size()), callName);
		}
		else {
			boolean variadic = isVariadicParams(items.get(at));
			String worker = workerName(callName);
			ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(variadic ? worker : callName, true);
			ClojureLowering.Clause clause = clause(ctx, items.get(at), items.subList(at + 1, items.size()), target);
			if (target.used() && clause.variadic()) {
				// a used variadic target splits like the multi-defn helpers: a
				// worker defun taking the rest as an ordinary parameter (the recur
				// call assigns exactly) plus the &rest head for normal calls
				// (wrapping, like the oracle); an unused variadic keeps its shape
				List<LispVal> workerParams = new ArrayList<>(clause.params());
				workerParams.remove(ClojureLowering.AMPERSAND_REST);
				forms = new ArrayList<>(List.of(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol(worker),
								ClojureLowerUtil.list(workerParams), clause.wrapped()),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), fn, ClojureLowerUtil.list(clause.params()),
								workerCall(worker, clause))));
			}
			else {
				forms = new ArrayList<>(List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), fn,
						ClojureLowerUtil.list(clause.params()), clause.wrapped())));
			}
		}
		if (dynamic) {
			// the value cell carries the function for calls and value carries
			// (a funcall of it, like a def'd function); recur and the
			// arity-dispatch helpers stay direct calls to the function cell;
			// the binding-depth counter beside it lets set! test at run time
			// whether the var is thread-bound. A redefined dynamic defn installs
			// its fresh function cell, so the value cell always holds the newest
			forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), ClojureLowering.varSym(key),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), fn)));
			forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), ClojureLowering.boundDepthSym(key),
					new LispInteger(0)));
		}
		else if (redef) {
			// the value cell every call reads, set where the definition stands
			forms.add(redefCellStore(key, fn));
		}
		forms.addAll(0, metaStore); // ahead, so the definition still answers the form
		return forms;
	}

	/** A redefinable {@code defn}'s value-cell store: {@code (setq var #'fn)}. */
	static LispVal redefCellStore(String key, LispSymbol fn) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowering.varSym(key),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), fn));
	}

	/** Whether a lowered form is a redefinable {@code defn}'s value-cell store. */
	static boolean isRedefCellStore(LispVal form) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		if (parts == null || parts.size() != 3 || !ClojureLowerUtil.isSymbolNamed(parts.get(0), "SETQ")) {
			return false;
		}
		List<LispVal> value = ClojureLowerUtil.items(parts.get(2));
		return value != null && value.size() == 2 && ClojureLowerUtil.isSymbolNamed(value.get(0), "FUNCTION");
	}

	/** The parameter vectors of a {@code defn}'s single arity or of each clause. */
	static List<LispVal> arglistsOf(List<LispVal> body) {
		if (!(body.get(0) instanceof LispCons) || isVectorDatum(ClojureLowerUtil.stripMeta(body.get(0)))) {
			return List.of(body.get(0));
		}
		List<LispVal> vectors = new ArrayList<>();
		for (LispVal clause : body) {
			List<LispVal> parts = ClojureLowerUtil.items(clause);
			if (parts != null && !parts.isEmpty()) {
				vectors.add(parts.get(0));
			}
		}
		return vectors;
	}

	/** Whether the datum is a `[...]` vector (its marker head), not an arity clause. */
	static boolean isVectorDatum(LispVal form) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		return parts != null && !parts.isEmpty() && parts.get(0) == ClojureReader.VECTOR;
	}

	/**
	 * A multi-arity {@code defn}: one {@code defun} per arity plus a dispatch
	 * {@code defun} picking by argument count, the shape {@code case-lambda} takes. Each
	 * clause keeps its own parameters (a variadic clause its {@code &rest}); the dispatch
	 * hands each its arguments positionally. A name no identifier mangles to (a single
	 * {@code %} outside the {@code :} escape) keeps the helpers apart from user
	 * definitions. A {@code recur} in a clause body is checked against that clause's
	 * arity and calls the clause's own helper -- a variadic one takes the rest as an
	 * ordinary parameter, so the call assigns exactly -- never the dispatch: the oracle's
	 * {@code recur} re-enters its own arity, and a direct self call is what a backend
	 * runs in constant stack (the JVM's jump, {@code .kb/jvm-self-tail-calls.md}) where a
	 * round trip through the dispatch was a mutual recursion of two functions.
	 */
	static List<LispVal> multiDefun(ClojureLowering ctx, String name, List<LispVal> clauses, String callName) {
		// the shapes first, without lowering: each clause recurs to its own helper
		// (a variadic one's takes the rest as an ordinary parameter, so the recur
		// call assigns exactly, where the dispatch's NTHCDR rest would wrap it in a
		// list)
		List<ClojureLowering.ParamShape> shapes = new ArrayList<>();
		for (LispVal clauseDatum : clauses) {
			List<LispVal> parts = ClojureLowerUtil.items(clauseDatum);
			shapes.add(parts == null || parts.isEmpty() ? new ClojureLowering.ParamShape(false, -1)
					: paramShape(parts.get(0)));
		}
		List<String> helperNames = new ArrayList<>();
		List<ClojureLowering.RecurTarget> targets = new ArrayList<>();
		for (ClojureLowering.ParamShape shape : shapes) {
			String helperName = callName + "%" + (shape.variadic() ? "*" : shape.fixed());
			helperNames.add(helperName);
			targets.add(new ClojureLowering.RecurTarget(helperName, true));
		}
		List<ClojureLowering.Clause> parsed = arityClauses(ctx, clauses, "defn", targets::get);
		List<LispVal> forms = new ArrayList<>();
		LispVal args = ctx.freshTemp();
		LispVal count = ctx.freshTemp();
		List<LispVal> helpers = new ArrayList<>();
		for (int i = 0; i < parsed.size(); i++) {
			ClojureLowering.Clause clause = parsed.get(i);
			LispSymbol helper = new LispSymbol(helperNames.get(i));
			helpers.add(helper);
			// the dispatch hands a variadic clause its rest pre-built as a list,
			// so the helper takes it as an ordinary parameter, not &rest
			List<LispVal> helperParams = new ArrayList<>(clause.params());
			helperParams.remove(ClojureLowering.AMPERSAND_REST);
			forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), helper, ClojureLowerUtil.list(helperParams),
					clause.wrapped()));
		}
		List<LispVal> arms = new ArrayList<>();
		for (int i = 0; i < parsed.size(); i++) {
			ClojureLowering.Clause clause = parsed.get(i);
			LispSymbol helper = (LispSymbol) helpers.get(i);
			List<LispVal> call = new ArrayList<>();
			call.add(helper);
			for (int p = 0; p < clause.fixed(); p++) {
				call.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("NTH"), new LispInteger(p), args));
			}
			if (clause.variadic()) {
				call.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("NTHCDR"), new LispInteger(clause.fixed()), args));
			}
			LispVal test = clause.variadic()
					? ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), count, new LispInteger(clause.fixed()))
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(clause.fixed()));
			arms.add(ClojureLowerUtil.list(test, ClojureLowerUtil.list(call)));
		}
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("wrong number of arguments passed to: " + name))));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol(callName),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(count,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args)))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms))));
		return forms;
	}

	/**
	 * The arity clauses of a multi-arity {@code defn} or {@code fn}, each a list of a
	 * parameter vector and a body, with at most one variadic clause and one clause per
	 * fixed arity. Each clause body lowers with its own recur target (a clause is its own
	 * recur boundary, like the oracle: the count must match the enclosing clause, never
	 * just any clause of the function).
	 */
	static List<ClojureLowering.Clause> arityClauses(ClojureLowering ctx, List<LispVal> clauses, String owner) {
		return arityClauses(ctx, clauses, owner, i -> null);
	}

	static List<ClojureLowering.Clause> arityClauses(ClojureLowering ctx, List<LispVal> clauses, String owner,
			java.util.function.IntFunction<ClojureLowering.RecurTarget> targets) {
		ClojureLowerUtil.isTrue(!clauses.isEmpty(), owner + " needs a parameter vector and a body");
		List<ClojureLowering.Clause> parsed = new ArrayList<>();
		Set<Integer> fixed = new HashSet<>();
		boolean variadic = false;
		for (LispVal clauseDatum : clauses) {
			List<LispVal> parts = ClojureLowerUtil.items(clauseDatum);
			if (parts == null || parts.isEmpty()) {
				throw new LispReadException(
						owner + " arity clauses take a parameter vector and a body, not " + clauseDatum.print());
			}
			ClojureLowering.Clause clause = clause(ctx, parts.get(0), parts.subList(1, parts.size()),
					targets.apply(parsed.size()));
			if (clause.variadic()) {
				ClojureLowerUtil.isTrue(!variadic, owner + " takes at most one variadic clause");
				variadic = true;
			}
			else {
				ClojureLowerUtil.isTrue(fixed.add(clause.fixed()),
						owner + " has two clauses for arity " + clause.fixed());
			}
			parsed.add(clause);
		}
		return parsed;
	}

	/**
	 * One lambda over a parameter vector and a not-yet-lowered body, the params scoped.
	 * Destructured parameters travel as generated temporaries with a {@code let*}
	 * prologue rebinding them to their patterns. With a recur target, the body lowers
	 * with it pushed (its arity set from the parsed parameters first, so a {@code recur}
	 * in the body checks against this clause), and the target records whether any
	 * {@code recur} reached it.
	 */
	static ClojureLowering.Clause clause(ClojureLowering ctx, LispVal paramVector, List<LispVal> bodyForms) {
		return clause(ctx, paramVector, bodyForms, null);
	}

	static ClojureLowering.Clause clause(ClojureLowering ctx, LispVal paramVector, List<LispVal> bodyForms,
			ClojureLowering.@Nullable RecurTarget target) {
		// metadata on the vector (type hints like ^String) is dropped, like the
		// rest: it never affects dispatch
		List<LispVal> names = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(paramVector),
				"the parameter vector of");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		List<LispVal> params = new ArrayList<>();
		List<LispVal> prologue = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			LispVal datum = ClojureLowerUtil.stripMeta(names.get(i));
			if (ClojureLowerUtil.isSymbolNamed(datum, "&")) {
				ClojureLowerUtil.isTrue(i + 1 < names.size(), "a & needs a rest name after it");
				ClojureLowerUtil.isTrue(i + 2 == names.size(), "only one name may follow & in a parameter vector");
				params.add(ClojureLowering.AMPERSAND_REST);
				LispVal rest = ClojureLowerUtil.stripMeta(names.get(++i));
				if (rest instanceof LispSymbol) {
					String name = ClojureLowerUtil.plainName(rest, "the parameter vector of");
					scope.put(name, ClojureLowering.Kind.VARIABLE);
					params.add(ctx.localSym(name));
				}
				else {
					LispSymbol temp = ctx.freshTemp();
					params.add(temp);
					destructureInto(ctx, rest, temp, prologue, scope, "the parameter vector of");
				}
				continue;
			}
			if (datum instanceof LispSymbol) {
				String param = ClojureLowerUtil.plainName(datum, "the parameter vector of");
				scope.put(param, ClojureLowering.Kind.VARIABLE);
				params.add(ctx.localSym(param));
				continue;
			}
			LispSymbol temp = ctx.freshTemp();
			params.add(temp);
			destructureInto(ctx, datum, temp, prologue, scope, "the parameter vector of");
		}
		LispVal body;
		boolean variadic = params.contains(ClojureLowering.AMPERSAND_REST);
		int fixed = variadic ? params.indexOf(ClojureLowering.AMPERSAND_REST) : params.size();
		if (target == null) {
			body = ctx.inScope(scope, () -> ctx.bodyOf(bodyForms));
		}
		else {
			target.setArity(variadic ? fixed + 1 : fixed, variadic);
			ctx.pushRecurTarget(target);
			try {
				body = ctx.inScope(scope, () -> ctx.bodyOfTail(bodyForms));
			}
			finally {
				ctx.recurTargets.pop();
			}
		}
		return new ClojureLowering.Clause(List.copyOf(params), List.copyOf(prologue), body, variadic, fixed);
	}

	static LispVal fn(ClojureLowering ctx, List<LispVal> items) {
		int at = 1;
		ClojureLowerUtil.isTrue(items.size() > at, "fn needs a parameter vector and a body");
		String self = null;
		if (items.get(at) instanceof LispSymbol) {
			self = ClojureLowerUtil.plainName(items.get(at), "fn");
			at++;
		}
		ClojureLowerUtil.isTrue(items.size() > at, "fn needs a parameter vector and a body");
		if (self == null) {
			String fresh = ctx.freshRecurName();
			ClojureLowering.SplitLambda split = singleOrMultiFn(ctx, items, at, "fn", fresh);
			if (!split.used()) {
				return split.lambda();
			}
			return labelsWithHead(fresh, split.workers(), split.lambda());
		}
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		scope.put(self, ClojureLowering.Kind.FUNCTION);
		String name = self;
		String callName = ctx.localSym(name).name();
		int from = at;
		return ctx.inScope(scope, () -> {
			ClojureLowering.SplitLambda split = singleOrMultiFn(ctx, items, from, name, callName);
			// a labels self-binding: calls lower directly and the labels
			// expansion rewrites them to the local; the value is the local
			return labelsWithHead(callName, split.workers(), split.lambda());
		});
	}

	static ClojureLowering.SplitLambda singleOrMultiFn(ClojureLowering ctx, List<LispVal> items, int at, String owner,
			String headName) {
		String worker = workerName(headName);
		if (items.get(at) instanceof LispCons && !isVectorDatum(items.get(at))) {
			return multiFn(ctx, items.subList(at, items.size()), owner, headName, worker);
		}
		boolean variadic = isVariadicParams(items.get(at));
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(variadic ? worker : headName, true);
		ClojureLowering.Clause clause = clause(ctx, items.get(at), items.subList(at + 1, items.size()), target);
		if (target.used() && clause.variadic()) {
			// a used variadic target splits: the worker takes the rest as an
			// ordinary parameter (the recur call assigns exactly) while the
			// &rest head answers normal calls (wrapping, like the oracle)
			LispVal head = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(clause.params()),
					workerCall(worker, clause));
			return new ClojureLowering.SplitLambda(head, List.of(workerEntry(worker, clause)), true);
		}
		return new ClojureLowering.SplitLambda(ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(clause.params()), clause.wrapped()), List.of(), target.used());
	}

	/**
	 * A multi-arity {@code fn}: one {@code lambda} over {@code &rest} dispatching per
	 * arity through {@code let*} argument bindings -- no local functions, so a clause
	 * body closes over the outer scope like any lambda body. A {@code recur} in a fixed
	 * clause body is checked against that clause's arity and calls the dispatch, which
	 * routes by count back to the same clause (every fixed count names exactly one
	 * clause, so the routing is exact except where a variadic clause listed before a
	 * fixed one also matches the count); a {@code recur} in a used variadic clause calls
	 * the worker instead, which takes the rest as an ordinary parameter.
	 */
	static ClojureLowering.SplitLambda multiFn(ClojureLowering ctx, List<LispVal> clauses, String owner,
			String headName, String worker) {
		// the shapes first, without lowering: a used variadic clause recurs to its
		// worker (which takes the rest as an ordinary parameter) while the dispatch
		// arm hands it the rest pre-built; every fixed clause recurs through the
		// dispatch, its own arm of this lambda
		List<ClojureLowering.RecurTarget> made = new ArrayList<>();
		for (LispVal clauseDatum : clauses) {
			List<LispVal> parts = ClojureLowerUtil.items(clauseDatum);
			boolean variadic = parts != null && !parts.isEmpty() && isVariadicParams(parts.get(0));
			made.add(new ClojureLowering.RecurTarget(variadic ? worker : headName, true));
		}
		List<ClojureLowering.Clause> parsed = arityClauses(ctx, clauses, owner, made::get);
		LispVal args = ctx.freshTemp();
		LispVal count = ctx.freshTemp();
		List<LispVal> arms = new ArrayList<>();
		int split = -1;
		for (int i = 0; i < parsed.size(); i++) {
			ClojureLowering.Clause clause = parsed.get(i);
			if (clause.variadic() && made.get(i).used()) {
				List<LispVal> call = new ArrayList<>();
				call.add(new LispSymbol(worker));
				for (int p = 0; p < clause.fixed(); p++) {
					call.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("NTH"), new LispInteger(p), args));
				}
				call.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("NTHCDR"), new LispInteger(clause.fixed()), args));
				arms.add(ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), count, new LispInteger(clause.fixed())),
						ClojureLowerUtil.list(call)));
				split = i;
				continue;
			}
			List<LispVal> bindings = new ArrayList<>();
			int position = 0;
			boolean rest = false;
			for (LispVal param : clause.params()) {
				if (ClojureLowering.AMPERSAND_REST.equals(param)) {
					rest = true;
					continue;
				}
				bindings.add(ClojureLowerUtil.list(param, rest
						? ClojureLowerUtil.list(ClojureLowerUtil.sym("NTHCDR"), new LispInteger(position), args)
						: ClojureLowerUtil.list(ClojureLowerUtil.sym("NTH"), new LispInteger(position++), args)));
			}
			bindings.addAll(clause.prologue());
			LispVal test = clause.variadic()
					? ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), count, new LispInteger(clause.fixed()))
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(clause.fixed()));
			arms.add(ClojureLowerUtil.list(test, ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
					ClojureLowerUtil.list(bindings), clause.body())));
		}
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("wrong number of arguments passed to: " + owner))));
		LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(count,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args)))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms)));
		boolean used = made.stream().anyMatch(ClojureLowering.RecurTarget::used);
		if (split >= 0) {
			return new ClojureLowering.SplitLambda(lambda, List.of(workerEntry(worker, parsed.get(split))), used);
		}
		return new ClojureLowering.SplitLambda(lambda, List.of(), used);
	}

	static LispVal let(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "let needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "let");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a let binding vector pairs a name with a value");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		ctx.scopes.add(scope); // let* : each value sees the bindings before it
		ctx.directScopes.add(new HashSet<>());
		LispVal form;
		try {
			List<LispVal> pairs = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				LispVal pattern = ClojureLowerUtil.stripMeta(bindings.get(i));
				if (pattern instanceof LispSymbol) {
					String name = ClojureLowerUtil.plainName(pattern, "let");
					LispVal init = ctx.lower(bindings.get(i + 1));
					pairs.add(ClojureLowerUtil.list(ctx.localSym(name), init));
					scope.put(name, ClojureLowering.Kind.VARIABLE);
					if (ClojureLowerUtil.yieldsFun(init)) {
						ctx.markDirect(name);
					}
					noteHostClass(ctx, name, init);
					continue;
				}
				LispSymbol temp = ctx.freshTemp();
				pairs.add(ClojureLowerUtil.list(temp, ctx.lower(bindings.get(i + 1))));
				Set<String> bound = new HashSet<>(scope.keySet());
				destructureInto(ctx, pattern, temp, pairs, scope, "let");
				forgetHostClasses(ctx, scope.keySet(), bound);
			}
			form = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
					ctx.body(items, 2));
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
			forgetDeepHosts(ctx);
		}
		return form;
	}

	/**
	 * A {@code let} binding's host class, when its lowered init is a construction literal
	 * of a loadable class: the FQN the instance-call wrap consults. Anything else forgets
	 * the name, so rebinding the name hides the old class.
	 */
	static void noteHostClass(ClojureLowering ctx, String name, LispVal init) {
		String fqn = ClojureInteropLowering.constructedClass(init);
		if (fqn == null) {
			ctx.hostClasses.remove(ctx.localSym(name).name());
		}
		else {
			ctx.hostClasses.put(ctx.localSym(name).name(), new ClojureLowering.HostClass(fqn, name, ctx.scopes.size()));
		}
	}

	/**
	 * Forgets host classes for names a destructuring pattern just bound: pattern members
	 * are collection members, never constructions.
	 */
	static void forgetHostClasses(ClojureLowering ctx, Set<String> now, Set<String> before) {
		for (String key : now) {
			if (!before.contains(key)) {
				ctx.hostClasses.remove(ctx.localSym(key).name());
			}
		}
	}

	/**
	 * Records an {@code if-let}/{@code when-let} binding the way {@code let} does: a
	 * plain name takes the lowered init's construction class, a destructuring pattern
	 * forgets its members (collection members, never constructions). The binding is
	 * single-shot, so the depth-walk soundness carries over.
	 */
	static void noteOrForgetHost(ClojureLowering ctx, LispVal pattern, LispVal loweredInit, Set<String> now,
			Set<String> before) {
		LispVal stripped = ClojureLowerUtil.stripMeta(pattern);
		if (stripped instanceof LispSymbol) {
			noteHostClass(ctx, ClojureLowerUtil.plainName(stripped, "if-let"), loweredInit);
		}
		else {
			forgetHostClasses(ctx, now, before);
		}
	}

	/**
	 * Drops host classes recorded below the current scope depth: their {@code let}
	 * finished, so a later form must not see them.
	 */
	static void forgetDeepHosts(ClojureLowering ctx) {
		int depth = ctx.scopes.size();
		ctx.hostClasses.values().removeIf(held -> held.depth() > depth);
	}

	/**
	 * A {@code recur} form lowered: a direct call to the innermost enclosing target (a
	 * {@code loop}, a named or anonymous {@code fn}, a {@code defn} clause, a
	 * {@code letfn} entry, or a {@code lazy-seq} body of arity 0), checked against that
	 * target's arity. A {@code try} between the {@code recur} and its target is the
	 * oracle's {@code Cannot recur across try} refusal, and a {@code recur} outside its
	 * target body's tail position the oracle's {@code Can only recur from tail position}
	 * refusal; both beat the arity check, like the oracle. Outside any target it stays a
	 * refusal.
	 */
	static LispVal recurOf(ClojureLowering ctx, LispVal form, List<LispVal> items) {
		if (ctx.recurTargets.isEmpty()) {
			throw new LispReadException("recur outside loop");
		}
		ClojureLowering.RecurTarget target = ctx.recurTargets.peek();
		if (ctx.tryDepth > target.depth()) {
			throw new LispReadException("Cannot recur across try");
		}
		if (!ctx.tailPosition) {
			throw new LispReadException("Can only recur from tail position");
		}
		int given = items.size() - 1;
		if (target.checked() && given != target.arity()) {
			throw new LispReadException(
					"wrong number of arguments passed to recur: expected " + target.arity() + ", got " + given);
		}
		target.markUsed(form);
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(target.callName()));
		for (int i = 1; i < items.size(); i++) {
			out.add(ctx.lower(items.get(i)));
		}
		return ClojureLowerUtil.list(out);
	}

	static LispVal loop(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "loop needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "loop");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a loop binding vector pairs a name with a value");
		String name = ClojureLowering.mangle("loop-") + ctx.counter++;
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		List<LispVal> paramSyms = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		List<LispVal> prologue = new ArrayList<>();
		ctx.scopes.add(scope); // let*-like: each init sees the bindings before it
		ctx.directScopes.add(new HashSet<>());
		try {
			for (int i = 0; i < bindings.size(); i += 2) {
				LispVal pattern = ClojureLowerUtil.stripMeta(bindings.get(i));
				LispVal init = ctx.lower(bindings.get(i + 1));
				if (pattern instanceof LispSymbol) {
					String binding = ClojureLowerUtil.plainName(pattern, "loop");
					paramSyms.add(ctx.localSym(binding));
					inits.add(init);
					scope.put(binding, ClojureLowering.Kind.VARIABLE);
					if (ClojureLowerUtil.yieldsFun(init)) {
						ctx.markDirect(binding);
					}
					continue;
				}
				LispSymbol temp = ctx.freshTemp();
				paramSyms.add(temp);
				inits.add(init);
				destructureInto(ctx, pattern, temp, prologue, scope, "loop");
			}
			ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(name, true);
			target.setArity(paramSyms.size(), false);
			ctx.pushRecurTarget(target);
			try {
				LispVal lambdaBody = prologue.isEmpty() ? ctx.bodyTail(items, 2) : ClojureLowerUtil
					.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(prologue), ctx.bodyTail(items, 2));
				// a labels binding is a named function: (name (params...) body...)
				LispVal binding = new LispCons(new LispSymbol(name),
						new LispCons(ClojureLowerUtil.list(paramSyms), ClojureLowerUtil.cons(lambdaBody, List.of())));
				// the self call wrapped in the sequential inits: labels parameters
				// bind in parallel, but each init sees the bindings before it
				LispVal self = new LispCons(new LispSymbol(name), ClojureLowerUtil.list(paramSyms));
				if (paramSyms.isEmpty()) {
					return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(binding), self);
				}
				List<LispVal> initPairs = new ArrayList<>();
				for (int i = 0; i < paramSyms.size(); i++) {
					initPairs.add(ClojureLowerUtil.list(paramSyms.get(i), inits.get(i)));
				}
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(binding),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(initPairs), self));
			}
			finally {
				ctx.recurTargets.pop();
			}
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
		}
	}

	/**
	 * {@code (letfn [(f [params] body...)+] body...)}: mutual local functions over one
	 * {@code labels}, so every entry -- and the body -- calls every other directly, the
	 * shape a named {@code fn} self-binding already uses. Every name is pre-scanned first
	 * (the {@code defn} mutual-recursion precedent), so siblings call each other; each
	 * entry then lowers like a named {@code fn} clause, with its own recur target pushing
	 * while its body lowers. Parameters destructure like {@code fn} parameters, and an
	 * entry's name is a function value (through {@code function}, like any other local
	 * function). An empty binding vector is just the body.
	 */
	static LispVal letfn(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "letfn needs a binding vector and a body");
		List<LispVal> specs = ClojureLowerUtil.bindingItems(items.get(1), "letfn");
		if (specs.isEmpty()) {
			return ctx.body(items, 2);
		}
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		List<List<LispVal>> fnspecs = new ArrayList<>();
		for (LispVal spec : specs) {
			List<LispVal> parts = ClojureLowerUtil.items(spec);
			if (parts == null || parts.size() < 3) {
				throw new LispReadException("a letfn binding takes a name and a function, not " + spec.print());
			}
			scope.put(ClojureLowerUtil.plainName(parts.get(0), "letfn"), ClojureLowering.Kind.FUNCTION);
			fnspecs.add(parts);
		}
		return ctx.inScope(scope, () -> {
			// a later entry shadows an earlier one with the same name, like the
			// oracle (labels itself refuses a name twice)
			// the functions are closures: inside a deftype method they copy the
			// mutable fields they read at letfn entry, while the body keeps the
			// fields themselves (see capturingMutableFields)
			List<String> captured = ctx.visibleMutableFields();
			Map<String, ClojureLowering.Kind> captureScope = new HashMap<>();
			for (String name : captured) {
				captureScope.put(name, ClojureLowering.Kind.VARIABLE);
			}
			Map<String, LispVal> bindings = new LinkedHashMap<>();
			for (List<LispVal> parts : fnspecs) {
				String fname = ClojureLowerUtil.plainName(parts.get(0), "letfn");
				String callName = ctx.localSym(fname).name();
				ClojureLowering.SplitLambda split = ctx.inScope(captureScope,
						() -> singleOrMultiFn(ctx, parts, 1, fname, callName));
				for (LispVal worker : split.workers()) {
					LispVal key = ((LispCons) worker).car();
					bindings.put(((LispSymbol) key).name(), worker);
				}
				bindings.put(callName, new LispCons(new LispSymbol(callName), ((LispCons) split.lambda()).cdr()));
			}
			LispVal functions = ClojureLowerUtil.list(new ArrayList<>(bindings.values()));
			LispVal body = ctx.body(items, 2);
			List<LispVal> copies = ctx.capturedBindings(captured, functions);
			if (copies.isEmpty()) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), functions, body);
			}
			// the copies shadow the fields' symbol macros; the body re-establishes
			// them over the same (field slot) pairs
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(copies),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), functions, ClojureLowerUtil
						.list(ClojureLowerUtil.sym("symbol-macrolet"), ClojureLowerUtil.list(copies), body)));
		});
	}

	/** The datum {@code (let [name init] body)}. */
	static LispVal letDatum(String name, LispVal init, LispVal body) {
		return ClojureLowerUtil.list(List.of(new LispSymbol(ClojureCoreNames.PREFIX + "let"),
				new LispCons(ClojureReader.VECTOR, ClojureLowerUtil.list(List.of(new LispSymbol(name), init))), body));
	}

	/**
	 * A function argument a spliced runtime worker funcalls: {@link #fnValue}, passed as
	 * itself when it is a real function (a function form, or a variable bound to one),
	 * else wrapped by {@link ClojureLowering#realFun}.
	 */
	static LispVal realFnValue(ClojureLowering ctx, LispVal form) {
		LispVal fun = fnValue(ctx, form);
		return holdsRealFun(ctx, form, fun) ? fun : ClojureLowering.realFun(fun);
	}

	/**
	 * A function argument an inline loop invokes at its call site: the {@link #fnValue}
	 * plus whether it always evaluates to a real function, so the loop funcalls it
	 * without the IFn dispatcher.
	 *
	 * @param fun the lowered function form
	 * @param real whether {@code fun} is always a real function
	 */
	record FnArg(LispVal fun, boolean real) {

		/** A form that was lowered elsewhere: real only when it {@code yieldsFun}. */
		static FnArg of(LispVal fun) {
			return new FnArg(fun, ClojureLowerUtil.yieldsFun(fun));
		}

	}

	/**
	 * The {@link FnArg} of a function datum: its {@link #fnValue}, real when
	 * {@link #holdsRealFun} -- decided here, from the datum, never from a lowered symbol.
	 */
	static FnArg fnArg(ClojureLowering ctx, LispVal form) {
		LispVal fun = fnValue(ctx, form);
		return new FnArg(fun, holdsRealFun(ctx, form, fun));
	}

	/**
	 * Whether the {@link #fnValue} of the datum always evaluates to a real function: a
	 * form that {@link ClojureLowerUtil#yieldsFun yields one}, or a variable bound to
	 * one.
	 * @param form the function datum
	 * @param fun its {@link #fnValue}
	 * @return whether it can be funcalled without the IFn dispatcher
	 */
	static boolean holdsRealFun(ClojureLowering ctx, LispVal form, LispVal fun) {
		return ClojureLowerUtil.yieldsFun(fun)
				|| form instanceof LispSymbol s && fun instanceof LispSymbol && ctx.isDirectVar(s.name());
	}

	/**
	 * A function in argument position: a known defn, a local binding, a lambda, a
	 * keyword.
	 */
	static LispVal fnValue(ClojureLowering ctx, LispVal form) {
		if (form instanceof LispSymbol s && s.name().startsWith(":")) {
			// no scope can bind a keyword (plainName refuses one), so this is data
			return ClojureFnLowering.keywordFn(ctx, form);
		}
		if (form instanceof LispSymbol s && ctx.known(s.name())) {
			if (ctx.isMacro(s.name())) {
				throw new LispReadException(s.name() + " is a macro, not a function");
			}
			if (ctx.isFunction(s.name())) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ctx.symOf(s.name()));
			}
			if (ctx.session && ctx.isDeclaredOnly(s.name())) {
				return ClojureVarLowering.sessionDeclaredRoot(ctx.symOf(s.name()));
			}
			return ctx.symOf(s.name());
		}
		if (form instanceof LispSymbol s) {
			LispVal predicate = ClojureFnLowering.predicateValue(ctx, s.name());
			if (predicate != null) {
				return predicate;
			}
		}
		if (form instanceof LispSymbol s) {
			ClojureLowering.VarRef qualified = ClojureNamespaceLowering.resolveQualified(ctx, s.name());
			if (qualified == null) {
				qualified = ClojureNamespaceLowering.libraryRefer(ctx, s.name());
			}
			if (qualified != null) {
				return ClojureNamespaceLowering.namespaceValue(ctx, qualified);
			}
		}
		LispVal collection = ClojureSeqLowering.collectionValue(ctx, form);
		if (collection != null) {
			return collection;
		}
		return ctx.lower(form);
	}

	/**
	 * One binding pattern destructured against an already-lowered init: flat
	 * {@code (symbol init)} pairs appended to {@code pairs}, plain names registered in
	 * {@code scope}. A plain symbol binds directly; a vector binds positionally through
	 * the seq view ({@code &} the rest as a seq, {@code :as} the whole); a map binds
	 * through the table-aware read ({@code :keys}/{@code :syms}/ {@code :strs}, explicit
	 * locals, {@code :as}, {@code :or} defaults, whose keys name the locals they
	 * default).
	 */
	static void destructureInto(ClojureLowering ctx, LispVal pattern, LispVal init, List<LispVal> pairs,
			Map<String, ClojureLowering.Kind> scope, String what) {
		// metadata on a pattern (type hints like ^String) is dropped, like the
		// rest: it never affects dispatch
		pattern = ClojureLowerUtil.stripMeta(pattern);
		if (pattern instanceof LispSymbol) {
			String name = ClojureLowerUtil.plainName(pattern, what);
			scope.put(name, ClojureLowering.Kind.VARIABLE);
			pairs.add(ClojureLowerUtil.list(ctx.localSym(name), init));
			return;
		}
		List<LispVal> elements = ClojureLowerUtil.items(pattern);
		if (elements != null && !elements.isEmpty() && elements.get(0) == ClojureReader.VECTOR) {
			destructureVector(ctx, elements.subList(1, elements.size()), init, pairs, scope, what);
			return;
		}
		if (elements != null && !elements.isEmpty() && ClojureLowerUtil.isSymbolNamed(elements.get(0), "%hash-map")) {
			destructureMap(ctx, elements.subList(1, elements.size()), init, pairs, scope, what);
			return;
		}
		throw new LispReadException(what + " needs a plain name or a vector or map pattern, not " + pattern.print());
	}

	/**
	 * A vector pattern against an already-lowered init: each element binds positionally
	 * through the seq view, so maps seq to entry vectors and anything past the end is
	 * nil; {@code &} binds the rest as a seq, {@code :as} the whole. Elements destructure
	 * recursively.
	 */
	static void destructureVector(ClojureLowering ctx, List<LispVal> elements, LispVal init, List<LispVal> pairs,
			Map<String, ClojureLowering.Kind> scope, String what) {
		LispSymbol whole = ctx.freshTemp();
		pairs.add(ClojureLowerUtil.list(whole, init));
		int position = 0;
		for (int i = 0; i < elements.size(); i++) {
			LispVal element = elements.get(i);
			if (ClojureLowerUtil.isSymbolNamed(element, ":as")) {
				ClojureLowerUtil.isTrue(i + 1 < elements.size(), "a vector pattern :as needs a plain name after it");
				String name = ClojureLowerUtil.plainName(elements.get(++i), "a vector pattern :as");
				scope.put(name, ClojureLowering.Kind.VARIABLE);
				pairs.add(ClojureLowerUtil.list(ctx.localSym(name), whole));
				continue;
			}
			if (ClojureLowerUtil.isSymbolNamed(element, "&")) {
				ClojureLowerUtil.isTrue(i + 1 < elements.size(),
						"a vector pattern & needs a single rest pattern after it");
				ClojureLowerUtil.isTrue(i + 2 == elements.size(),
						"a vector pattern & takes a single rest pattern after it");
				destructureInto(ctx, elements.get(++i), dropView(ctx, whole, position), pairs, scope, what);
				continue;
			}
			destructureInto(ctx, element,
					ClojureSeqLowering.nthForm(ctx, whole, new LispInteger(position), ClojureLowering.NIL_CONST), pairs,
					scope, what);
			position++;
		}
	}

	/**
	 * The seq view past the first {@code n} items: the spliced {@code %clojure-drop},
	 * stepping one realized level at a time, so a lazy rest stays lazy (and an infinite
	 * input still binds).
	 */
	static LispVal dropView(ClojureLowering ctx, LispVal coll, int n) {
		if (n == 0) {
			return ClojureSeqLowering.seqForm(ctx, coll);
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DROP"), new LispInteger(n), coll);
	}

	/**
	 * A map pattern against an already-lowered init: every entry but the
	 * {@code :keys}/{@code :syms}/{@code :strs}/{@code :as}/{@code :or} directives binds
	 * its local through the table-aware read of its key expression, with the {@code :or}
	 * default when present.
	 */
	static void destructureMap(ClojureLowering ctx, List<LispVal> entries, LispVal init, List<LispVal> pairs,
			Map<String, ClojureLowering.Kind> scope, String what) {
		LispSymbol whole = ctx.freshTemp();
		pairs.add(ClojureLowerUtil.list(whole, init));
		Map<String, LispVal> defaults = new HashMap<>();
		for (int i = 0; i + 1 < entries.size(); i += 2) {
			if (!ClojureLowerUtil.isSymbolNamed(entries.get(i), ":or")) {
				continue;
			}
			List<LispVal> orMap = ClojureLowerUtil.items(entries.get(i + 1));
			if (orMap == null || orMap.isEmpty() || !ClojureLowerUtil.isSymbolNamed(orMap.get(0), "%hash-map")) {
				throw new LispReadException(
						"a map pattern :or takes a map of defaults, not " + entries.get(i + 1).print());
			}
			List<LispVal> orEntries = orMap.subList(1, orMap.size());
			for (int j = 0; j + 1 < orEntries.size(); j += 2) {
				LispVal defaultKey = orEntries.get(j);
				ClojureLowerUtil.isTrue(defaultKey instanceof LispSymbol key && !key.name().startsWith(":"),
						"a map pattern :or takes plain names for defaults, not " + defaultKey.print());
				defaults.put(((LispSymbol) defaultKey).name(), orEntries.get(j + 1));
			}
		}
		for (int i = 0; i + 1 < entries.size(); i += 2) {
			LispVal head = entries.get(i);
			LispVal arg = entries.get(i + 1);
			if (ClojureLowerUtil.isSymbolNamed(head, ":or")) {
				continue;
			}
			if (ClojureLowerUtil.isSymbolNamed(head, ":as")) {
				String name = ClojureLowerUtil.plainName(arg, "a map pattern :as");
				scope.put(name, ClojureLowering.Kind.VARIABLE);
				pairs.add(ClojureLowerUtil.list(ctx.localSym(name), whole));
				continue;
			}
			if (head instanceof LispSymbol kind
					&& (kind.name().equals(":keys") || kind.name().equals(":syms") || kind.name().equals(":strs"))) {
				bindKeys(ctx, kind.name(), arg, whole, pairs, scope, defaults);
				continue;
			}
			if (head instanceof LispSymbol) {
				String name = ClojureLowerUtil.plainName(head, "a map pattern binding");
				scope.put(name, ClojureLowering.Kind.VARIABLE);
				pairs.add(ClojureLowerUtil.list(ctx.localSym(name), ClojureCollectionLowering.getForm(ctx, whole,
						ctx.lower(arg), defaultFor(ctx, defaults, name))));
				continue;
			}
			// a nested pattern binds from the same read, without an :or default
			destructureInto(ctx, head,
					ClojureCollectionLowering.getForm(ctx, whole, ctx.lower(arg), ClojureLowering.NIL_CONST), pairs,
					scope, what);
		}
	}

	/**
	 * One {@code :keys}/{@code :syms}/{@code :strs} directive: each entry binds its local
	 * from the keyword, symbol or string key of that spelling (a {@code :keys} entry may
	 * qualify, binding the short name).
	 */
	static void bindKeys(ClojureLowering ctx, String kind, LispVal names, LispVal whole, List<LispVal> pairs,
			Map<String, ClojureLowering.Kind> scope, Map<String, LispVal> defaults) {
		List<LispVal> elements = ClojureLowerUtil.items(names);
		if (elements == null || elements.isEmpty() || elements.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(
					"a map pattern " + kind + " takes a vector of plain names, not " + names.print());
		}
		for (LispVal element : elements.subList(1, elements.size())) {
			ClojureLowerUtil.isTrue(
					element instanceof LispSymbol spelled && !spelled.name().startsWith(":")
							&& !spelled.name().equals("&"),
					"a map pattern " + kind + " takes a vector of plain names, not " + element.print());
			String lookup = ((LispSymbol) element).name();
			String local = lookup;
			if ((kind.equals(":keys") || kind.equals(":syms")) && lookup.lastIndexOf('/') >= 0) {
				local = lookup.substring(lookup.lastIndexOf('/') + 1);
				ClojureLowerUtil.isTrue(!local.isEmpty(),
						"a map pattern " + kind + " takes a vector of plain names, not " + element.print());
			}
			LispVal keyForm = switch (kind) {
				case ":keys" -> ctx.lower(new LispSymbol(":" + lookup));
				case ":syms" -> ctx.quote(element);
				default -> LispString.literal(local);
			};
			scope.put(local, ClojureLowering.Kind.VARIABLE);
			pairs.add(ClojureLowerUtil.list(ctx.localSym(local),
					ClojureCollectionLowering.getForm(ctx, whole, keyForm, defaultFor(ctx, defaults, local))));
		}
	}

	static LispVal defaultFor(ClojureLowering ctx, Map<String, LispVal> defaults, String name) {
		LispVal found = defaults.get(name);
		return found == null ? ClojureLowering.NIL_CONST : ctx.lower(found);
	}

	// Seq verbs: each over the seq view, strict lists, nil-for-empty

}
