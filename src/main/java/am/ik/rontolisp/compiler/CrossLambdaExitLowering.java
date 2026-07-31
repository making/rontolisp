package am.ik.rontolisp.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Compile-path pass that turns a {@code return-from} or a {@code go} which crosses a
 * lambda boundary into a real non-local exit, so it reaches the establishing function
 * like the interpreter (and CL) instead of failing inside the lambda it sits in.
 *
 * <p>
 * The problem: a lambda becomes a separately compiled method/function, so a lexical
 * goto/br cannot cross into it -- a {@code return-from} inside a lambda passed to
 * {@code mapcar}/{@code mapl}/... falls back to the lambda's own boundary and exits only
 * that lambda ({@code .kb/do-return-block.md}). The interpreter is correct because it
 * throws a stack-unwinding {@code BlockReturnSignal}.
 *
 * <p>
 * The lowering reproduces that unwind with exception handling, keyed by a <em>dynamic
 * block-instance id</em> so recursion targets the right frame. For each establishing
 * block (a defun's implicit function block, or a user {@code (block name ...)}) whose
 * {@code return-from} escapes a nested lambda, it:
 *
 * <ul>
 * <li>binds a fresh id -- a genuine lexical variable {@code let}-bound to
 * {@code (%nlx-tag)} -- so the existing free-variable / closure machinery
 * ({@code FreeVarAnalyzer}) carries it into the lambda with no bespoke wiring;</li>
 * <li>wraps the block in {@code (%nlx-catch id ...)}, an exception-handling region that
 * yields the carried value when a matching {@code %nlx-throw} unwinds to it;</li>
 * <li>rewrites the crossing {@code (return-from name value)} to
 * {@code (%nlx-throw id value)}.</li>
 * </ul>
 *
 * A same-function {@code return-from} (one not inside a nested lambda) is left untouched
 * and keeps compiling to the lexical goto/br fast path; the {@code %fn-block} wrap that
 * supports it is added afterwards by {@link LambdaLists#desugarProgram} (which this pass
 * runs before), naturally nesting the {@code %fn-block} boundary around the injected
 * {@code let}/{@code %nlx-catch}. A function with no cross-lambda exit is returned
 * verbatim, so the pass is a no-op (and byte-identical) for the common case.
 *
 * <p>
 * Covered boundaries: explicit {@code (lambda ...)} forms and {@code flet}/{@code labels}
 * local-function definition bodies (which macro-expand into lambdas -- their bodies count
 * as one lambda level deeper). Covered exits: a named {@code return-from} and a plain
 * {@code (return [value])}, whose establishing point is the nearest nil-block boundary --
 * a loop macro ({@code loop}/{@code do}/{@code do*}/{@code dotimes}/{@code dolist}/
 * {@code prog}/{@code prog*}), the internal {@code %block}, or {@code (block nil ...)} --
 * with named blocks in between transparent, mirroring the interpreter's signal
 * transparency (cl-postgres' {@code message-case} does {@code (return)} out of a
 * {@code loop} from inside a {@code labels} function). The interpreter and
 * {@code --no-gc} are out of scope.
 *
 * <p>
 * A crossing {@code go} rides the same three primitives, with one extra move: a block
 * exit LEAVES its block, so a throw/catch pair is the whole story, while a {@code go}
 * RE-ENTERS its {@code tagbody} at a label and keeps running. The establishing
 * {@code tagbody} is therefore rewritten into a re-entry loop -- expressed entirely in
 * ordinary forms, so neither backend's {@code tagbody}/{@code go} compiler changes:
 *
 * <pre>{@code
 * (let ((id (%nlx-tag)) (pc 0) (r nil))
 *   (tagbody
 *    retry
 *      (setq r (%nlx-catch id (tagbody (if (= pc 1) (go t1)) ... ORIGINAL-ITEMS)))
 *      (if r (progn (setq pc r) (go retry)))))
 * }</pre>
 *
 * The crossing {@code (go t1)} becomes {@code (%nlx-throw id 1)}; the caught value is the
 * target label's 1-based index, so the dispatch prologue jumps straight to it and
 * execution continues from there. Normal completion of the inner {@code tagbody} yields
 * nil (a {@code tagbody}'s value), which is what stops the loop -- indices start at 1
 * precisely so nil cannot be mistaken for one. Only labels an actual crossing {@code go}
 * targets get a dispatch entry, and a {@code tagbody} with no crossing {@code go} is
 * emitted verbatim. {@code prog}/{@code prog*} establish tags the same way (their body is
 * a {@code tagbody} body), so the rewrite is spliced in as their single body item,
 * leaving the {@code %block} a plain {@code (return)} exits untouched.
 */
public final class CrossLambdaExitLowering {

	/**
	 * The rewritten program plus whether any cross-lambda exit was lowered (drives EH
	 * mode).
	 *
	 * @param program the rewritten top-level forms
	 * @param used {@code true} when at least one cross-lambda exit was lowered
	 */
	public record Result(List<LispVal> program, boolean used) {
	}

	private CrossLambdaExitLowering() {
	}

	/**
	 * Lowers every cross-lambda {@code return-from} and {@code go} in {@code program}.
	 * @param program the top-level forms (already library-spliced, before lambda-list
	 * desugaring)
	 * @return the rewritten program and the cross-lambda-exit flag
	 */
	public static Result lower(List<LispVal> program) {
		Lowerer lowerer = new Lowerer();
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(lowerer.transform(form, 0));
		}
		return new Result(out, lowerer.used);
	}

	/** A lexically enclosing block establishing point in the current function. */
	private static final class Scope {

		final @Nullable String name;

		final int lambdaDepth;

		final LispSymbol idVar;

		/** True for a nil-block boundary (a loop macro / {@code (block nil ...)}). */
		final boolean nilBlock;

		boolean used = false;

		Scope(@Nullable String name, int lambdaDepth, LispSymbol idVar, boolean nilBlock) {
			this.name = name;
			this.lambdaDepth = lambdaDepth;
			this.idVar = idVar;
			this.nilBlock = nilBlock;
		}

	}

	/**
	 * A lexically enclosing {@code tagbody} (or {@code prog}/{@code prog*}) establishing
	 * point in the current function.
	 */
	private static final class TagScope {

		/** Label name -> the label form as it appears in the body (a symbol). */
		final Map<String, LispSymbol> labels;

		final int lambdaDepth;

		final LispSymbol idVar;

		final LispSymbol pcVar;

		final LispSymbol resultVar;

		final LispSymbol retryTag;

		/** Label name -> 1-based re-entry index, for the labels a crossing go targets. */
		final Map<String, Integer> crossed = new LinkedHashMap<>();

		TagScope(Map<String, LispSymbol> labels, int lambdaDepth, LispSymbol idVar, LispSymbol pcVar,
				LispSymbol resultVar, LispSymbol retryTag) {
			this.labels = labels;
			this.lambdaDepth = lambdaDepth;
			this.idVar = idVar;
			this.pcVar = pcVar;
			this.resultVar = resultVar;
			this.retryTag = retryTag;
		}

		int reentryIndex(String tag) {
			return this.crossed.computeIfAbsent(tag, k -> this.crossed.size() + 1);
		}

	}

	private static final class Lowerer {

		private final Deque<Scope> scopes = new ArrayDeque<>();

		private final Deque<TagScope> tagScopes = new ArrayDeque<>();

		private int idCounter = 0;

		boolean used = false;

		/**
		 * Transforms {@code form}, where {@code lambdaDepth} counts the lambda boundaries
		 * crossed from the nearest enclosing named function. A {@code return-from} at a
		 * greater depth than its target block's establishing depth crosses a lambda.
		 */
		LispVal transform(LispVal form, int lambdaDepth) {
			if (!(form instanceof LispCons cons)) {
				return form;
			}
			if (cons.car() instanceof LispSymbol op) {
				switch (op.name()) {
					case LispNames.QUOTE -> {
						return form;
					}
					case LispNames.LAMBDA -> {
						return transformLambda(cons, lambdaDepth);
					}
					case LispNames.FUNCTION -> {
						return transformFunction(cons, lambdaDepth);
					}
					case LispNames.DEFUN -> {
						return transformDefun(cons, lambdaDepth);
					}
					case LispNames.BLOCK -> {
						return transformBlock(cons, lambdaDepth);
					}
					case LispNames.RETURN_FROM -> {
						return transformReturnFrom(cons, lambdaDepth);
					}
					case LispNames.RETURN -> {
						return transformReturn(cons, lambdaDepth);
					}
					case LispNames.LOOP, LispNames.DO, LispNames.DO_STAR, LispNames.DOTIMES, LispNames.DOLIST,
							LispNames.BLOCK_INTERNAL -> {
						return transformNilBlockForm(cons, lambdaDepth);
					}
					case LispNames.PROG, LispNames.PROG_STAR -> {
						return transformProg(cons, lambdaDepth);
					}
					case LispNames.TAGBODY -> {
						return transformTagbody(cons, lambdaDepth);
					}
					case LispNames.GO -> {
						return transformGo(cons, lambdaDepth);
					}
					case LispNames.FLET, LispNames.LABELS -> {
						return transformFletLabels(cons, lambdaDepth);
					}
					default -> {
						// fall through to structural recursion
					}
				}
			}
			return structural(cons, lambdaDepth);
		}

		// Preserve the cons spine, transforming each element. Operators (symbols) pass
		// through unchanged; nested forms are visited so lambdas and return-froms in
		// argument position are found. This is also the fall-through for a cons whose
		// head
		// only LOOKS like block/return-from -- a let binding, a defstruct slot, or data
		// whose variable happens to be named `block` -- so those are traversed, not
		// mis-parsed as the special form (md5 binds a variable named `block`).
		private LispVal structural(LispCons cons, int lambdaDepth) {
			return new LispCons(transform(cons.car(), lambdaDepth), transform(cons.cdr(), lambdaDepth));
		}

		// A well-formed block/return-from name: a non-keyword symbol or nil. Anything
		// else
		// (a cons, a number, a keyword) means the head is not really that special form.
		private static boolean isBlockName(LispVal form) {
			return (form instanceof LispSymbol sym && !sym.isKeyword()) || form instanceof LispNil;
		}

		private LispVal transformLambda(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				return cons;
			}
			List<LispVal> out = new ArrayList<>(parts.size());
			out.add(parts.get(0));
			out.add(parts.get(1));
			for (int i = 2; i < parts.size(); i++) {
				out.add(transform(parts.get(i), lambdaDepth + 1));
			}
			return list(out);
		}

		private LispVal transformFunction(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			// (function (lambda ...)) -- the sharp-quoted lambda; (function name) is not
			// a
			// form to descend into (name is in the function namespace).
			if (parts.size() == 2 && parts.get(1) instanceof LispCons inner && inner.car() instanceof LispSymbol s
					&& LispNames.LAMBDA.equals(s.name())) {
				return list(List.of(parts.get(0), transform(inner, lambdaDepth)));
			}
			return cons;
		}

		private LispVal transformDefun(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3) {
				return cons;
			}
			String blockName = defunBlockName(parts.get(1));
			LispSymbol idVar = freshId();
			Scope scope = new Scope(blockName, lambdaDepth, idVar, false);
			scopes.push(scope);
			List<LispVal> body = new ArrayList<>();
			for (int i = 3; i < parts.size(); i++) {
				body.add(transform(parts.get(i), lambdaDepth));
			}
			scopes.pop();
			List<LispVal> out = new ArrayList<>();
			out.add(parts.get(0)); // defun
			out.add(parts.get(1)); // name
			out.add(parts.get(2)); // params
			if (scope.used) {
				out.add(wrapWithCatch(idVar, body));
			}
			else {
				out.addAll(body);
			}
			return list(out);
		}

		private LispVal transformBlock(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2 || !isBlockName(parts.get(1))) {
				// Not a real (block name ...) -- a binding/slot/data whose var is
				// `block`.
				return structural(cons, lambdaDepth);
			}
			String name = LispMacroExpander.blockName(parts.get(1));
			LispSymbol idVar = freshId();
			// (block nil ...) is the plain-return boundary: a nil-block scope, so a bare
			// (return) crossing a lambda targets it like a named return-from would.
			Scope scope = new Scope(name, lambdaDepth, idVar, name == null);
			scopes.push(scope);
			List<LispVal> body = new ArrayList<>();
			for (int i = 2; i < parts.size(); i++) {
				body.add(transform(parts.get(i), lambdaDepth));
			}
			scopes.pop();
			List<LispVal> blockParts = new ArrayList<>();
			blockParts.add(parts.get(0)); // block
			blockParts.add(parts.get(1)); // name
			blockParts.addAll(body);
			LispVal blockForm = list(blockParts);
			return scope.used ? wrapWithCatch(idVar, List.of(blockForm)) : blockForm;
		}

		private LispVal transformReturnFrom(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2 || parts.size() > 3 || !isBlockName(parts.get(1))) {
				// Not a real (return-from name [value]) -- e.g. a binding whose var is
				// `return-from`; traverse it so a return-from in an init form is still
				// found.
				return structural(cons, lambdaDepth);
			}
			LispVal value = parts.size() == 3 ? transform(parts.get(2), lambdaDepth) : LispNil.INSTANCE;
			String name = LispMacroExpander.blockName(parts.get(1));
			Scope target = name != null ? nearestScope(name) : nearestNilScope();
			if (target != null && target.lambdaDepth < lambdaDepth) {
				target.used = true;
				this.used = true;
				return list(List.of(new LispSymbol(LispNames.NLX_THROW_INTERNAL), target.idVar, value));
			}
			// Same-function (or unmatched) return-from: keep it lexical.
			List<LispVal> out = new ArrayList<>();
			out.add(parts.get(0));
			out.add(parts.get(1));
			if (parts.size() == 3) {
				out.add(value);
			}
			return list(out);
		}

		// A bare (return [value]) -- (return-from nil ...): targets the nearest
		// nil-block boundary (a loop macro, %block or (block nil ...)); named blocks in
		// between are transparent, mirroring the signal transparency of the runtime.
		private LispVal transformReturn(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.isEmpty() || parts.size() > 2 || !cons.isProperList()) {
				// Not a real (return [value]) -- a binding whose var is `return`, or
				// data; traverse it so nested forms are still found.
				return structural(cons, lambdaDepth);
			}
			LispVal value = parts.size() == 2 ? transform(parts.get(1), lambdaDepth) : LispNil.INSTANCE;
			Scope target = nearestNilScope();
			if (target != null && target.lambdaDepth < lambdaDepth) {
				target.used = true;
				this.used = true;
				return list(List.of(new LispSymbol(LispNames.NLX_THROW_INTERNAL), target.idVar, value));
			}
			List<LispVal> out = new ArrayList<>();
			out.add(parts.get(0));
			if (parts.size() == 2) {
				out.add(value);
			}
			return list(out);
		}

		// A loop macro (loop/do/do*/dotimes/dolist/prog/prog*) or the internal %block:
		// each establishes the implicit nil block a bare (return) exits. The scope wraps
		// the WHOLE form, so a lowered exit delivers the loop form's value.
		private LispVal transformNilBlockForm(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.isEmpty() || !cons.isProperList()) {
				return structural(cons, lambdaDepth);
			}
			LispSymbol idVar = freshId();
			Scope scope = new Scope(null, lambdaDepth, idVar, true);
			scopes.push(scope);
			List<LispVal> out = new ArrayList<>(parts.size());
			out.add(parts.get(0));
			for (int i = 1; i < parts.size(); i++) {
				out.add(transform(parts.get(i), lambdaDepth));
			}
			scopes.pop();
			LispVal form = list(out);
			return scope.used ? wrapWithCatch(idVar, List.of(form)) : form;
		}

		// (tagbody item...) -- the tags a crossing `go` can target. Body atoms (symbols
		// or integers) are labels, other items are forms compiled for effect; visiting
		// the items one by one (instead of falling through to `structural`) also keeps a
		// label that happens to be named `return`/`block` from being read as that
		// special form's head.
		private LispVal transformTagbody(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.isEmpty() || !cons.isProperList()) {
				return structural(cons, lambdaDepth);
			}
			List<LispVal> items = parts.subList(1, parts.size());
			TagScope scope = pushTagScope(items, lambdaDepth);
			List<LispVal> body = transformAll(items, lambdaDepth);
			this.tagScopes.pop();
			if (scope.crossed.isEmpty()) {
				List<LispVal> out = new ArrayList<>(parts.size());
				out.add(parts.get(0));
				out.addAll(body);
				return list(out);
			}
			return reentryLoop(scope, body);
		}

		// (prog bindings item...) = %block + let + tagbody(item...), so it establishes
		// BOTH the nil block a bare (return) exits and the tags a `go` targets. The
		// re-entry loop replaces the items as the prog's single body form, which leaves
		// the block and the bindings exactly where they were.
		private LispVal transformProg(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2 || !cons.isProperList()) {
				return structural(cons, lambdaDepth);
			}
			LispSymbol idVar = freshVar("id");
			Scope nilScope = new Scope(null, lambdaDepth, idVar, true);
			this.scopes.push(nilScope);
			LispVal bindings = transform(parts.get(1), lambdaDepth);
			List<LispVal> items = parts.subList(2, parts.size());
			TagScope tagScope = pushTagScope(items, lambdaDepth);
			List<LispVal> body = transformAll(items, lambdaDepth);
			this.tagScopes.pop();
			this.scopes.pop();
			List<LispVal> out = new ArrayList<>(parts.size());
			out.add(parts.get(0));
			out.add(bindings);
			if (tagScope.crossed.isEmpty()) {
				out.addAll(body);
			}
			else {
				out.add(reentryLoop(tagScope, body));
			}
			LispVal form = list(out);
			return nilScope.used ? wrapWithCatch(idVar, List.of(form)) : form;
		}

		// (go tag): a tag established outside the lambda this go sits in cannot be
		// reached by a lexical goto/br, so it becomes a throw carrying the label's
		// re-entry index; the establishing tagbody's loop catches it and re-dispatches.
		private LispVal transformGo(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.size() != 2 || !cons.isProperList() || !(parts.get(1) instanceof LispSymbol tagSym)) {
				// Not a real (go tag) -- a binding whose variable is `go`, or data;
				// traverse it so nested forms are still found.
				return structural(cons, lambdaDepth);
			}
			String tag = labelName(tagSym);
			TagScope target = nearestTagScope(tag);
			if (target != null && target.lambdaDepth < lambdaDepth) {
				this.used = true;
				return list(List.of(new LispSymbol(LispNames.NLX_THROW_INTERNAL), target.idVar,
						new LispInteger(target.reentryIndex(tag))));
			}
			// Same-function (or unmatched) go: keep it lexical.
			return cons;
		}

		private List<LispVal> transformAll(List<LispVal> items, int lambdaDepth) {
			List<LispVal> out = new ArrayList<>(items.size());
			for (LispVal item : items) {
				out.add(transform(item, lambdaDepth));
			}
			return out;
		}

		private TagScope pushTagScope(List<LispVal> items, int lambdaDepth) {
			Map<String, LispSymbol> labels = new LinkedHashMap<>();
			for (LispVal item : items) {
				// Only symbol labels: `go` takes a symbol on the compile path, so an
				// integer label can never be a crossing go's target. Keywords count --
				// quri's with-array-parsing labels its end-of-input segment `:eof`.
				if (item instanceof LispSymbol sym) {
					labels.putIfAbsent(labelName(sym), sym);
				}
			}
			TagScope scope = new TagScope(labels, lambdaDepth, freshVar("id"), freshVar("pc"), freshVar("r"),
					freshVar("retry"));
			this.tagScopes.push(scope);
			return scope;
		}

		// (let ((id (%nlx-tag)) (pc 0) (r nil))
		// (tagbody retry
		// (setq r (%nlx-catch id (tagbody DISPATCH body...)))
		// (if r (progn (setq pc r) (go retry)))))
		//
		// The inner tagbody yields nil when it runs off its end, so a non-nil r is
		// always a caught re-entry index (they start at 1). The whole form yields nil,
		// which is what the tagbody it replaces yielded.
		private LispVal reentryLoop(TagScope scope, List<LispVal> body) {
			List<LispVal> inner = new ArrayList<>(scope.crossed.size() + body.size() + 1);
			inner.add(new LispSymbol(LispNames.TAGBODY));
			for (Map.Entry<String, Integer> entry : scope.crossed.entrySet()) {
				inner.add(list(List.of(new LispSymbol(LispNames.IF),
						list(List.of(new LispSymbol(LispNames.EQ), scope.pcVar, new LispInteger(entry.getValue()))),
						list(List.of(new LispSymbol(LispNames.GO), scope.labels.get(entry.getKey()))))));
			}
			inner.addAll(body);
			LispVal caught = list(List.of(new LispSymbol(LispNames.NLX_CATCH_INTERNAL), scope.idVar, list(inner)));
			LispVal store = list(List.of(new LispSymbol(LispNames.SETQ), scope.resultVar, caught));
			LispVal again = list(List.of(new LispSymbol(LispNames.PROGN),
					list(List.of(new LispSymbol(LispNames.SETQ), scope.pcVar, scope.resultVar)),
					list(List.of(new LispSymbol(LispNames.GO), scope.retryTag))));
			LispVal loop = list(List.of(new LispSymbol(LispNames.TAGBODY), scope.retryTag, store,
					list(List.of(new LispSymbol(LispNames.IF), scope.resultVar, again))));
			LispVal bindings = list(List.of(
					list(List.of(scope.idVar, list(List.of(new LispSymbol(LispNames.NLX_TAG_INTERNAL))))),
					list(List.of(scope.pcVar, new LispInteger(0))), list(List.of(scope.resultVar, LispNil.INSTANCE))));
			return list(List.of(new LispSymbol(LispNames.LET), bindings, loop));
		}

		private @Nullable TagScope nearestTagScope(String tag) {
			for (TagScope scope : this.tagScopes) {
				if (scope.labels.containsKey(tag)) {
					return scope;
				}
			}
			return null;
		}

		/** The label name of a tagbody symbol, package qualification stripped. */
		private static String labelName(LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return qn == null ? sym.name() : qn.member();
		}

		// flet/labels definition bodies expand into lambdas (separately compiled
		// functions), so a return/return-from inside one crosses a function boundary:
		// transform them at lambdaDepth + 1. The flet body itself stays at this depth.
		private LispVal transformFletLabels(LispCons cons, int lambdaDepth) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2 || !cons.isProperList()
					|| !(parts.get(1) instanceof LispCons || parts.get(1) instanceof LispNil)) {
				return structural(cons, lambdaDepth);
			}
			List<LispVal> defs = parts.get(1) instanceof LispCons defsCons ? defsCons.toList() : List.of();
			List<LispVal> outDefs = new ArrayList<>(defs.size());
			for (LispVal def : defs) {
				if (!(def instanceof LispCons defCons) || !defCons.isProperList()) {
					outDefs.add(def);
					continue;
				}
				List<LispVal> dp = defCons.toList();
				if (dp.size() < 2) {
					outDefs.add(def);
					continue;
				}
				List<LispVal> outDef = new ArrayList<>(dp.size());
				outDef.add(dp.get(0)); // name
				outDef.add(dp.get(1)); // lambda list
				for (int i = 2; i < dp.size(); i++) {
					outDef.add(transform(dp.get(i), lambdaDepth + 1));
				}
				outDefs.add(list(outDef));
			}
			List<LispVal> out = new ArrayList<>(parts.size());
			out.add(parts.get(0));
			out.add(defs.isEmpty() ? parts.get(1) : list(outDefs));
			for (int i = 2; i < parts.size(); i++) {
				out.add(transform(parts.get(i), lambdaDepth));
			}
			return list(out);
		}

		private @Nullable Scope nearestNilScope() {
			for (Scope scope : scopes) {
				if (scope.nilBlock) {
					return scope;
				}
			}
			return null;
		}

		private @Nullable Scope nearestScope(String name) {
			for (Scope scope : scopes) {
				if (name.equals(scope.name)) {
					return scope;
				}
			}
			return null;
		}

		// (let ((id (%nlx-tag))) (%nlx-catch id body...))
		private LispVal wrapWithCatch(LispSymbol idVar, List<LispVal> body) {
			LispVal binding = list(List.of(idVar, list(List.of(new LispSymbol(LispNames.NLX_TAG_INTERNAL)))));
			List<LispVal> catchParts = new ArrayList<>();
			catchParts.add(new LispSymbol(LispNames.NLX_CATCH_INTERNAL));
			catchParts.add(idVar);
			catchParts.addAll(body);
			return list(List.of(new LispSymbol(LispNames.LET), list(List.of(binding)), list(catchParts)));
		}

		private LispSymbol freshId() {
			return freshVar("id");
		}

		/** A generated lexical/label name, unique within the pass. */
		private LispSymbol freshVar(String role) {
			return new LispSymbol("__nlx_" + role + "_" + (this.idCounter++));
		}

		private static @Nullable String defunBlockName(LispVal nameForm) {
			LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(nameForm);
			if (setfPlace != null) {
				return LispMacroExpander.blockName(setfPlace);
			}
			return nameForm instanceof LispSymbol sym ? LispMacroExpander.blockName(sym) : null;
		}

	}

	private static LispVal list(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
