package am.ik.rontolisp.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Compile-path pass that turns a {@code return-from} which crosses a lambda boundary into
 * a real non-local exit, so it exits the establishing function like the interpreter (and
 * CL) instead of the lambda it sits in.
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
 * Scope: explicit {@code (lambda ...)} boundaries only. {@code flet}/{@code labels} local
 * functions (which macro-expand into lambdas) and a non-lexical {@code go} are left as
 * they are today (still lambda-local); the interpreter and {@code --no-gc} are out of
 * scope.
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
	 * Lowers every cross-lambda {@code return-from} in {@code program}.
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

		boolean used = false;

		Scope(@Nullable String name, int lambdaDepth, LispSymbol idVar) {
			this.name = name;
			this.lambdaDepth = lambdaDepth;
			this.idVar = idVar;
		}

	}

	private static final class Lowerer {

		private final Deque<Scope> scopes = new ArrayDeque<>();

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
			Scope scope = new Scope(blockName, lambdaDepth, idVar);
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
			if (name == null) {
				// (block nil ...) -- the plain-return boundary; not a named cross-lambda
				// target. Descend without establishing a named scope.
				List<LispVal> out = new ArrayList<>();
				out.add(parts.get(0));
				out.add(parts.get(1));
				for (int i = 2; i < parts.size(); i++) {
					out.add(transform(parts.get(i), lambdaDepth));
				}
				return list(out);
			}
			LispSymbol idVar = freshId();
			Scope scope = new Scope(name, lambdaDepth, idVar);
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
			if (name != null) {
				Scope target = nearestScope(name);
				if (target != null && target.lambdaDepth < lambdaDepth) {
					target.used = true;
					this.used = true;
					return list(List.of(new LispSymbol(LispNames.NLX_THROW_INTERNAL), target.idVar, value));
				}
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
			return new LispSymbol("__nlx_id_" + (idCounter++));
		}

		private static @Nullable String defunBlockName(LispVal nameForm) {
			LispSymbol setfPlace = LispMacroExpander.setfFunctionPlaceName(nameForm);
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
