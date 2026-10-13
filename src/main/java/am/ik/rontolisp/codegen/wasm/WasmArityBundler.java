package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.DeclaredArityReport;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites fixed-arity {@code defun}s with more parameters than the WASM callable-type
 * limit ({@link WasmLispCompiler#MAX_CALLABLE_ARITY}) into the "bundle the extra
 * arguments into a list" shape the limit's error message suggests -- automatically, so
 * real-library code with wide helper signatures compiles without source changes. Raising
 * the limit itself would move indices in every module -- it is the origin
 * {@code FUNC_DISPATCH_SPREAD} and every later {@code FUNC_*} and type index are defined
 * off -- so the transform stays at the AST level:
 *
 * <pre>
 * (defun f (p1 .. p12) body)  -> (defun f (&amp;rest %bundle)
 *                                  (let* ((p1 (progn (check %bundle) (nth 0 %bundle)))
 *                                         (p2 (nth 1 %bundle)) .. (p12 (nth 11 %bundle)))
 *                                    body))
 * </pre>
 *
 * The bundle is a {@code &rest} list, so every caller packs it the way it packs any
 * variadic callee's: a direct call, {@code #'f} through a per-arity dispatcher,
 * {@code apply}, and a name the compiled {@code eval} resolves at run time, which reaches
 * the SPREAD dispatcher. A bundle passed as one explicit list argument after the first
 * nine used to serve direct calls only: {@code #'f} was refused and
 * {@code (eval '(f ...))} trapped on the cast of an argument that was no list. With no
 * required parameter left, the check is the only count judge, so every wrong count -- a
 * direct call's included -- signals the interpreter's {@code program-error},
 * {@code Function expects 12 arguments, got 13}; keeping the first nine as required
 * parameters would have made a short call report {@code at least 9}. The price is one
 * cons per argument per call, on a signature only a generated or unusually wide helper
 * has. Variadic ({@code &rest}) definitions past the limit keep the existing hard error.
 *
 * <p>
 * First-class functions ride the same bundling ({@link #bundleLambdas}): a {@code lambda}
 * value with more than ten plain parameters, a {@code flet} or {@code labels} clause of
 * that width, and a non-top-level {@code defun} (which Pass 2 lowers to a closure) are
 * rewritten to the same {@code (&rest %bundle)} shape. Their callers already pack a
 * variadic callee's rest list -- the per-arity dispatchers a {@code funcall} or a local
 * call travels through, and the SPREAD dispatcher an {@code apply} reaches -- so no call
 * site is rewritten. A {@code lambda} in call position
 * ({@code ((lambda (p...) ...) args...)}) is left alone: it is inlined into its caller
 * and never becomes a function. A body-declared wrong-count report
 * ({@link DeclaredArityReport}, what a Clojure {@code fn} lowers to) stays at the
 * rewritten body's head, and the check names the enclosing lambda through the compilation
 * context, so the refusal keeps the declaring words.
 */
final class WasmArityBundler {

	private static final String BUNDLE_VAR = "%arity-bundle";

	/** The count check a rewritten defun opens with ({@link #compileCheck}). */
	static final String CHECK = "%ARITY-BUNDLE-CHECK";

	private WasmArityBundler() {
	}

	/**
	 * Applies the transform to every too-wide fixed-arity top-level defun; its call sites
	 * need no rewrite. Runs after lambda-list desugaring (so only the native "required +
	 * &rest" shape appears) and before compilation.
	 * @param program the top-level forms
	 * @return the transformed program (the same list when nothing is too wide)
	 */
	static List<LispVal> bundle(List<LispVal> program) {
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			if (program.get(i) instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(op.name()) && cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				if (parts.size() >= 3 && parts.get(1) instanceof LispSymbol
						&& parts.get(2) instanceof LispCons paramsCons) {
					List<LispVal> params = paramsCons.toList();
					if (params.size() > WasmLispCompiler.MAX_CALLABLE_ARITY
							&& params.stream().allMatch(WasmArityBundler::isPlainParam)) {
						if (out == null) {
							out = new ArrayList<>(program);
						}
						out.set(i, bundleDefun(parts));
					}
				}
			}
		}
		return out == null ? program : out;
	}

	private static boolean isPlainParam(LispVal param) {
		return param instanceof LispSymbol sym && !sym.name().startsWith("&");
	}

	/**
	 * Applies the bundling to every too-wide fixed-arity first-class function: a
	 * {@code lambda} value with more than ten plain parameters, a {@code flet} or
	 * {@code labels} clause of that width, and a non-top-level {@code defun} (which the
	 * expression compiler lowers to a closure). Runs after {@link #bundle}, whose
	 * top-level defuns it recurses into, and before compilation. A {@code lambda} in call
	 * position is inlined into its caller and never becomes a function, so only its body
	 * is rewritten, never its parameter list. Quoted data is left alone.
	 * @param program the top-level forms
	 * @return the transformed program (the same list when no lambda is too wide)
	 */
	static List<LispVal> bundleLambdas(List<LispVal> program) {
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal rewritten = bundleForm(program.get(i));
			if (rewritten != program.get(i)) {
				if (out == null) {
					out = new ArrayList<>(program);
				}
				out.set(i, rewritten);
			}
		}
		return out == null ? program : out;
	}

	private static LispVal bundleForm(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		if (cons.car() instanceof LispSymbol q && LispNames.isQuote(q.name())) {
			return form;
		}
		List<LispVal> parts = cons.toList();
		if (parts.get(0) instanceof LispCons head && isLambdaForm(head)) {
			// An inline call: the lambda runs in its caller's frame, so its
			// parameters are locals, never wasm parameters -- rewrite only what the
			// body holds, never the parameter list.
			LispVal inlined = bundleLambdaContents(head);
			List<LispVal> out = new ArrayList<>(parts.size());
			out.add(inlined == head ? head : inlined);
			boolean changed = inlined != head;
			for (int i = 1; i < parts.size(); i++) {
				LispVal arg = bundleForm(parts.get(i));
				changed |= arg != parts.get(i);
				out.add(arg);
			}
			return changed ? LispCons.rebuiltList(cons, out) : form;
		}
		if (parts.get(0) instanceof LispSymbol op) {
			String name = op.name();
			if (LispNames.LAMBDA.equals(name) && parts.size() >= 2) {
				LispVal bundled = bundleLambdaValue(parts);
				return bundled == null ? bundleChildren(cons, parts) : bundleForm(bundled);
			}
			if ((LispNames.FLET.equals(name) || LispNames.LABELS.equals(name)) && parts.size() >= 2) {
				return bundleFletLabels(cons, parts);
			}
			if (LispNames.DEFUN.equals(name) && parts.size() >= 3 && parts.get(1) instanceof LispSymbol
					&& parts.get(2) instanceof LispCons paramsCons && isWidePlainParams(paramsCons)) {
				// A non-top-level defun (a top-level one already rode bundle());
				// the expression compiler lowers it to a closure either way.
				return bundleForm(bundleDefun(parts));
			}
		}
		return bundleChildren(cons, parts);
	}

	private static boolean isLambdaForm(LispCons head) {
		return head.isProperList() && head.car() instanceof LispSymbol op && LispNames.LAMBDA.equals(op.name())
				&& head.cdr() instanceof LispCons;
	}

	private static boolean isWidePlainParams(LispCons paramsCons) {
		if (!paramsCons.isProperList()) {
			return false;
		}
		List<LispVal> params = paramsCons.toList();
		return params.size() > WasmLispCompiler.MAX_CALLABLE_ARITY
				&& params.stream().allMatch(WasmArityBundler::isPlainParam);
	}

	/** The body of a {@code lambda} form with its nested functions bundled. */
	private static LispVal bundleLambdaContents(LispCons head) {
		List<LispVal> parts = head.toList();
		if (parts.size() < 2) {
			return head;
		}
		List<LispVal> out = new ArrayList<>(parts.size());
		out.add(parts.get(0));
		out.add(parts.get(1));
		boolean changed = false;
		for (int i = 2; i < parts.size(); i++) {
			LispVal rewritten = bundleForm(parts.get(i));
			changed |= rewritten != parts.get(i);
			out.add(rewritten);
		}
		return changed ? LispCons.rebuiltList(head, out) : head;
	}

	/**
	 * A {@code lambda} value's bundled form, or {@code null} when its parameters fit.
	 */
	private static @Nullable LispVal bundleLambdaValue(List<LispVal> parts) {
		if (!(parts.get(1) instanceof LispCons paramsCons) || !isWidePlainParams(paramsCons)) {
			return null;
		}
		List<LispVal> params = paramsCons.toList();
		List<LispVal> body = parts.subList(2, parts.size());
		List<LispVal> lambda = new ArrayList<>();
		lambda.add(parts.get(0));
		lambda.add(listToCons(List.of(new LispSymbol(LispNames.LAMBDA_REST), new LispSymbol(BUNDLE_VAR))));
		DeclaredArityReport declared = DeclaredArityReport.of(body);
		if (declared != null) {
			lambda.add(declared.declaration());
		}
		// Unnamed: the check names the enclosing lambda through the compilation
		// context, so a declared report keeps its words.
		lambda.add(bundleLetStar(params, body, listToCons(
				List.of(new LispSymbol(CHECK), new LispSymbol(BUNDLE_VAR), new LispInteger(params.size())))));
		return listToCons(lambda);
	}

	private static LispVal bundleChildren(LispCons cons, List<LispVal> parts) {
		List<LispVal> out = new ArrayList<>(parts.size());
		boolean changed = false;
		for (LispVal part : parts) {
			LispVal rewritten = bundleForm(part);
			changed |= rewritten != part;
			out.add(rewritten);
		}
		return changed ? LispCons.rebuiltList(cons, out) : cons;
	}

	/**
	 * A {@code flet} or {@code labels} form with every too-wide clause bundled; each
	 * clause keeps its name and declares its report where the expansion still reads it.
	 */
	private static LispVal bundleFletLabels(LispCons cons, List<LispVal> parts) {
		List<LispVal> out = new ArrayList<>(parts.size());
		out.add(parts.get(0));
		boolean changed = false;
		for (int i = 1; i < parts.size(); i++) {
			LispVal part = parts.get(i);
			if (i == 1 && part instanceof LispCons defsCons && defsCons.isProperList()) {
				List<LispVal> defs = defsCons.toList();
				List<LispVal> rewrittenDefs = new ArrayList<>(defs.size());
				boolean defsChanged = false;
				for (LispVal def : defs) {
					LispVal rewritten = bundleFletClause(def);
					defsChanged |= rewritten != def;
					rewrittenDefs.add(rewritten);
				}
				LispVal rewritten = defsChanged ? LispCons.rebuiltList(defsCons, rewrittenDefs) : part;
				changed |= defsChanged;
				out.add(rewritten);
			}
			else {
				LispVal rewritten = bundleForm(part);
				changed |= rewritten != part;
				out.add(rewritten);
			}
		}
		return changed ? LispCons.rebuiltList(cons, out) : cons;
	}

	private static LispVal bundleFletClause(LispVal def) {
		if (!(def instanceof LispCons defCons) || !defCons.isProperList()) {
			return def;
		}
		List<LispVal> dp = defCons.toList();
		if (dp.size() < 2 || !(dp.get(0) instanceof LispSymbol) || !(dp.get(1) instanceof LispCons paramsCons)
				|| !isWidePlainParams(paramsCons)) {
			return bundleForm(def);
		}
		List<LispVal> params = paramsCons.toList();
		List<LispVal> body = dp.subList(2, dp.size());
		List<LispVal> clause = new ArrayList<>();
		clause.add(dp.get(0));
		clause.add(listToCons(List.of(new LispSymbol(LispNames.LAMBDA_REST), new LispSymbol(BUNDLE_VAR))));
		DeclaredArityReport declared = DeclaredArityReport.of(body);
		if (declared != null) {
			clause.add(declared.declaration());
		}
		clause.add(bundleLetStar(params, body, listToCons(
				List.of(new LispSymbol(CHECK), new LispSymbol(BUNDLE_VAR), new LispInteger(params.size())))));
		return listToCons(clause);
	}

	/**
	 * The {@code (let* ((p1 (progn check (nth 0 bundle))) ...) body...)} a bundled
	 * function's parameter bindings become.
	 * @param check the count check the first binding runs ({@link #bundleCheck})
	 */
	private static LispVal bundleLetStar(List<LispVal> params, List<LispVal> body, LispVal check) {
		LispSymbol bundle = new LispSymbol(BUNDLE_VAR);
		List<LispVal> letBindings = new ArrayList<>();
		for (int i = 0; i < params.size(); i++) {
			LispVal nth = listToCons(List.of(new LispSymbol(LispNames.NTH), new LispInteger(i), bundle));
			if (i == 0) {
				nth = listToCons(List.of(new LispSymbol(LispNames.PROGN), check, nth));
			}
			letBindings.add(listToCons(List.of(params.get(i), nth)));
		}
		List<LispVal> letParts = new ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET_STAR));
		letParts.add(listToCons(letBindings));
		letParts.addAll(body);
		return listToCons(letParts);
	}

	/**
	 * The widest PER-ARITY dispatch the program asks for: the argument count of its
	 * widest {@code funcall}, or the list count of its widest
	 * {@code mapcar}/{@code mapc}/{@code mapcan} (each iteration funcalls the mapped
	 * function with one element per list), whichever is larger -- {@code 0} when it has
	 * neither. What the compiler sizes its EXTRA dispatcher tier from
	 * ({@link WasmLispCompiler#callArityCeiling()}), so that an eleven-argument
	 * {@code funcall} answers with one 975-byte ladder rather than by pulling in a 12 KB
	 * spread dispatcher that serves every callable at every width.
	 *
	 * <p>
	 * A direct call a nested {@code defun} or a {@code flet}/{@code labels} local takes
	 * becomes a {@code funcall} only when Pass 2 lowers it, past this scan -- so the scan
	 * counts those too, by name: a call whose head spells a nested definition or a local
	 * function contributes its argument count, unless a top-level {@code defun} of the
	 * name stays a direct call. A pre-scan of the source program cannot see a
	 * {@code funcall} a macro synthesizes out of nothing later in Pass 2, which is
	 * exactly what the codegen ceiling checks are still for: anything past the ceiling
	 * stays a call-time signal ({@code funcall}) or a compile error (the map family,
	 * which has no per-site fallback).
	 * @param program the top-level forms
	 * @return the widest dispatch arity
	 */
	static int widestDispatchArity(List<LispVal> program) {
		Set<String> directNames = new HashSet<>();
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(op.name()) && cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				if (parts.size() >= 3 && parts.get(1) instanceof LispSymbol name) {
					directNames.add(name.name());
				}
			}
		}
		Set<String> valueNames = new HashSet<>();
		for (LispVal form : program) {
			collectValueNames(form, true, valueNames);
		}
		valueNames.removeAll(directNames);
		int widest = 0;
		for (LispVal form : program) {
			widest = Math.max(widest, widestDispatchArityIn(form, valueNames));
		}
		return widest;
	}

	/**
	 * The names a direct call reaches as a function value: a non-top-level
	 * {@code defun}'s (Pass 2 lowers the definition to a closure and the call to a
	 * {@code funcall}) and every {@code flet}/{@code labels} clause's (the local-call
	 * rewrite turns call position into a {@code funcall} of the bound variable).
	 */
	private static void collectValueNames(LispVal form, boolean topLevel, Set<String> out) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return;
		}
		if (cons.car() instanceof LispSymbol q && LispNames.isQuote(q.name())) {
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.get(0) instanceof LispSymbol op) {
			String name = op.name();
			if (LispNames.DEFUN.equals(name) && parts.size() >= 3 && parts.get(1) instanceof LispSymbol defName) {
				if (!topLevel) {
					out.add(defName.name());
				}
				for (int i = 3; i < parts.size(); i++) {
					collectValueNames(parts.get(i), false, out);
				}
				return;
			}
			if ((LispNames.FLET.equals(name) || LispNames.LABELS.equals(name)) && parts.size() >= 2
					&& parts.get(1) instanceof LispCons defsCons && defsCons.isProperList()) {
				for (LispVal def : defsCons.toList()) {
					if (def instanceof LispCons defCons && defCons.isProperList()) {
						List<LispVal> dp = defCons.toList();
						if (!dp.isEmpty() && dp.get(0) instanceof LispSymbol clause) {
							out.add(clause.name());
						}
						for (int i = 1; i < dp.size(); i++) {
							collectValueNames(dp.get(i), false, out);
						}
					}
				}
				for (int i = 2; i < parts.size(); i++) {
					collectValueNames(parts.get(i), false, out);
				}
				return;
			}
		}
		for (LispVal part : parts) {
			collectValueNames(part, false, out);
		}
	}

	private static int widestDispatchArityIn(LispVal form, Set<String> valueNames) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()
				|| cons.car() instanceof LispSymbol q && LispNames.isQuote(q.name())) {
			return 0;
		}
		List<LispVal> parts = cons.toList();
		// (funcall f a1 .. aN) and (mapcar f l1 .. lN) both dispatch at N.
		int widest = 0;
		if (cons.car() instanceof LispSymbol op) {
			if (LispNames.FUNCALL.equals(op.name()) || LispNames.MAPCAR.equals(op.name())
					|| LispNames.MAPC.equals(op.name()) || LispNames.MAPCAN.equals(op.name())) {
				widest = parts.size() - 2;
			}
			else if (!valueNames.isEmpty() && valueNames.contains(op.name())) {
				// A direct call Pass 2 turns into a funcall of a function value.
				widest = parts.size() - 1;
			}
		}
		for (LispVal part : parts) {
			widest = Math.max(widest, widestDispatchArityIn(part, valueNames));
		}
		return widest;
	}

	/**
	 * Lowers a {@code funcall} with more arguments than the module's per-arity
	 * dispatchers can take into the equivalent {@code apply}:
	 *
	 * <pre>
	 * (funcall f a1 .. a15) -> (apply f (list a1 .. a15))
	 * </pre>
	 *
	 * The per-arity dispatchers take one WASM parameter per Lisp argument and so stop at
	 * the ceiling, but {@code _apply} does not: it hands the whole argument list to the
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
	 * A keyword lambda list is the shape that reaches the fixed block's limit in
	 * practice: the arguments are passed through verbatim for the callee's own dispatcher
	 * to parse, so chipz's {@code (funcall fun state input output :input-start s
	 * :input-end e :output-start s :output-end e)} is eleven of them for a function whose
	 * lambda list has seven parameters. That one is now inside the derived ceiling and
	 * reaches its own dispatcher; what still comes here is a call wide enough that a
	 * per-arity ladder is the more expensive answer.
	 * @param program the top-level forms
	 * @param ceiling the widest funcall this module dispatches per arity
	 * ({@link WasmLispCompiler#callArityCeiling()})
	 * @return the transformed program (the same list when no funcall is too wide)
	 */
	static List<LispVal> spreadOverArityFuncalls(List<LispVal> program, int ceiling) {
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			LispVal rewritten = spread(form, ceiling);
			changed |= rewritten != form;
			out.add(rewritten);
		}
		return changed ? out : program;
	}

	private static LispVal spread(LispVal form, int ceiling) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		boolean quoted = cons.car() instanceof LispSymbol q && LispNames.isQuote(q.name());
		if (quoted) {
			return form;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> out = new ArrayList<>(parts.size());
		for (LispVal part : parts) {
			out.add(spread(part, ceiling));
		}
		if (cons.car() instanceof LispSymbol op && LispNames.FUNCALL.equals(op.name()) && out.size() - 2 > ceiling) {
			List<LispVal> listParts = new ArrayList<>();
			listParts.add(new LispSymbol(LispNames.LIST));
			listParts.addAll(out.subList(2, out.size()));
			return listToCons(List.of(new LispSymbol(LispNames.APPLY), out.get(1), listToCons(listParts)));
		}
		return LispCons.rebuiltList(cons, out);
	}

	private static LispVal bundleDefun(List<LispVal> parts) {
		List<LispVal> params = ((LispCons) parts.get(2)).toList();
		LispSymbol bundle = new LispSymbol(BUNDLE_VAR);
		List<LispVal> newParams = List.of(new LispSymbol(LispNames.LAMBDA_REST), bundle);
		// a report the body declares stays the defun's own (DeclaredArityReport), and the
		// check names the defun, so its report is spelled in the declared words
		DeclaredArityReport declared = DeclaredArityReport.of(parts.subList(3, parts.size()));
		List<LispVal> defun = new ArrayList<>(List.of(parts.get(0), parts.get(1), listToCons(newParams)));
		if (declared != null) {
			defun.add(declared.declaration());
		}
		defun.add(bundleLetStar(params, parts.subList(3, parts.size()),
				bundleCheck(bundle, params.size(), declared != null ? (LispSymbol) parts.get(1) : null)));
		return listToCons(defun);
	}

	/**
	 * {@code (%arity-bundle-check bundle n [name])}: the count check a fixed-arity
	 * callee's dispatch would make ({@link #compileCheck}); {@code name} is the defun's
	 * when its body declares its report.
	 */
	private static LispVal bundleCheck(LispSymbol bundle, int arity, @Nullable LispSymbol declaring) {
		return declaring == null ? listToCons(List.of(new LispSymbol(CHECK), bundle, new LispInteger(arity)))
				: listToCons(List.of(new LispSymbol(CHECK), bundle, new LispInteger(arity),
						LispString.literal(declaring.name())));
	}

	/**
	 * Compiles {@code (%arity-bundle-check bundle n)} to nil, after measuring the list:
	 * through {@code _arity_chk}, the helper every other count report uses, which throws
	 * the interpreter's {@code program-error},
	 * {@code Function expects n arguments, got m} -- or the report the named defun
	 * declares ({@link DeclaredArityReport}) -- in EH mode behind a handler landing pad
	 * and traps elsewhere, as a dispatcher's no-match arm does there. A bundled lambda's
	 * check carries no name; it names the enclosing lambda instead (Pass 2c records its
	 * funcId on the context), so a declared report keeps its words there too, and a
	 * lambda declaring none reports the same generic text a named shape would. A module
	 * without the helper traps in place. A Lisp-level {@code (error 'program-error ...)}
	 * would have pulled the instance machinery into every module with a wide defun, and
	 * could not be compiled at all where the instance gate had already closed.
	 * @param cons the check form
	 * @param ctx the compilation context
	 */
	static void compileCheck(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		int arity = (int) ((LispInteger) parts.get(2)).value();
		if (ctx.arityChkFuncIndex >= 0) {
			WasmExprCompiler.compileExpr(parts.get(1), ctx);
			// a defun declaring its report carries its funcId, which names it
			WasmLispCompiler.WasmFunctionInfo declaring = parts.size() > 3 && parts.get(3) instanceof LispString name
					? ctx.functions.get(name.value()) : null;
			int named = -1;
			if (declaring != null && declaring.declaredArity() != null && ctx.namesArityOperators
					&& declaring.funcId() < WasmRuntimeBuilder.ARITY_MAX_NAMED_FUNC_ID) {
				named = declaring.funcId();
				ctx.arityNamedCallees.add(named);
			}
			else if (declaring == null && parts.size() == 3 && ctx.enclosingLambdaFuncId >= 0 && ctx.namesArityOperators
					&& ctx.enclosingLambdaFuncId < WasmRuntimeBuilder.ARITY_MAX_NAMED_FUNC_ID) {
				// a bundled lambda's check: the enclosing lambda's report (if it
				// declares one) spells the refusal, like the miss arm of a dispatcher
				// would for an unbundled one
				named = ctx.enclosingLambdaFuncId;
				ctx.arityNamedCallees.add(named);
			}
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmRuntimeBuilder.arityShape(arity, false, named));
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.arityChkFuncIndex);
			ctx.writer.write(Instruction.DROP);
		}
		else {
			WasmExprCompiler.compileExpr(listToCons(List.of(new LispSymbol(LispNames.LENGTH), parts.get(1))), ctx);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(Type.I31.code());
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(arity);
			ctx.writer.write(Instruction.I32_NE);
			ctx.writer.write(Instruction.IF, 0x40);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	private static LispVal listToCons(List<LispVal> items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			result = new LispCons(items.get(i), result);
		}
		return result;
	}

}
