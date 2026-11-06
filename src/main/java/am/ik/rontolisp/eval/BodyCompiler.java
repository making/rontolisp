package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.DispatchChain;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCircularLiteral;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispStructLiteral;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.SpecialDeclarations;
import am.ik.rontolisp.compiler.BuiltinCallArity;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Compiles a lambda's body into {@link CompiledForm}s, once the walk has run it
 * ({@code .kb/interpreter-compiled-bodies.md}). The compiler runs nothing and expands
 * nothing: what it knows of a form is what the walk left on it -- a call's classification
 * ({@link FunctionCell#wasCalledOrdinarily}), a macro call's expansion (the head's
 * {@code ExpansionNote}, a walk's memo), a lambda's shape -- and the special forms
 * {@code evalCons} runs itself, whose arms it mirrors. A form it can tell nothing of yet
 * stays the walk's ({@link CompiledForms.Pending}), which compiles it once the walk has
 * run it. A variable a scope of the compiled body binds is read by its position in that
 * scope ({@link Layout}), every other one by name, as the walk reads it.
 */
final class BodyCompiler {

	/** How many forms one compile takes at most: a bigger body is walked. */
	private static final int BUDGET = 100_000;

	private static final String[] NO_NAMES = new String[0];

	private static final CompiledForm[] NO_FORMS = new CompiledForm[0];

	/** A body past its budget, or a form nested past the stack. */
	private static final class TooBig extends RuntimeException {

		TooBig() {
			super(null, null, false, false);
		}

	}

	private static final TooBig TOO_BIG = new TooBig();

	private final LispEvaluator ev;

	private int budget = BUDGET;

	private BodyCompiler(LispEvaluator ev) {
		this.ev = ev;
	}

	/**
	 * The scopes a compiled body makes, as its compiler laid them out: their names in the
	 * order they are bound, the names a free special declaration marks last. The scope a
	 * body was compiled alone in (its closure) is no layout: a variable bound there is
	 * read by name.
	 */
	static final class Layout {

		private final @Nullable Layout parent;

		private final String[] names;

		private final int marked;

		/**
		 * A scope's layout.
		 * @param parent the layout of the scope it lies in, or null where the compiled
		 * scopes end
		 * @param names its names in their order
		 * @param marked how many of them come before the marked ones
		 */
		Layout(@Nullable Layout parent, String[] names, int marked) {
			this.parent = parent;
			this.names = names;
			this.marked = marked;
		}

		/**
		 * Where a name is bound: {@code depth << 32 | index} for a lexical binding, or
		 * {@link #MARKED} where a special declaration is the innermost thing naming it,
		 * or {@link #UNKNOWN} past the compiled scopes.
		 */
		long resolve(String name) {
			int depth = 0;
			for (Layout scope = this; scope != null; scope = scope.parent, depth++) {
				String[] bound = scope.names;
				for (int i = 0; i < bound.length; i++) {
					if (bound[i].equals(name)) {
						return i < scope.marked ? ((long) depth << 32) | i : MARKED;
					}
				}
			}
			return UNKNOWN;
		}

	}

	private static final long UNKNOWN = -1L;

	private static final long MARKED = -2L;

	/**
	 * What a lambda form's slot holds before its closures' body is compiled: the layout
	 * of the scope the closures are made in, which their body is compiled within.
	 *
	 * @param layout the layout
	 */
	record Enclosed(Layout layout) {
	}

	/**
	 * Compiles a lambda's body, or answers null when it is left to the walk: a parameter
	 * binds dynamically, or the body is too big.
	 * @param ev the evaluator
	 * @param lambda the lambda
	 * @param enclosed the layout its closures are made in, when a compiled form made them
	 * @param rewriteEpoch the evaluator's body rewrite epoch
	 * @return the compiled body, or null
	 */
	static @Nullable CompiledBody compile(LispEvaluator ev, LispLambda lambda, @Nullable Enclosed enclosed,
			int rewriteEpoch) {
		try {
			return new BodyCompiler(ev).body(lambda, enclosed == null ? null : enclosed.layout(), rewriteEpoch);
		}
		catch (TooBig | StackOverflowError tooBig) {
			return null;
		}
	}

	/**
	 * Compiles one form a {@link CompiledForms.Pending} holds, now that the walk has run
	 * it, or answers null when the compiler still cannot tell what it is.
	 * @param ev the evaluator
	 * @param form the form
	 * @param layout the layout of the scope it runs in
	 * @param located the innermost located form of the frame there
	 * @return the compiled form, or null
	 */
	static @Nullable CompiledForm compileSite(LispEvaluator ev, LispCons form, Layout layout,
			@Nullable LocatedCons located) {
		try {
			CompiledForm compiled = new BodyCompiler(ev).cons(form, layout, located);
			return compiled instanceof CompiledForms.Pending ? null : compiled;
		}
		catch (TooBig | StackOverflowError tooBig) {
			return null;
		}
	}

	private @Nullable CompiledBody body(LispLambda lambda, @Nullable Layout enclosing, int rewriteEpoch) {
		int specialEpoch = this.ev.specialEpoch();
		List<LispVal> forms = lambda.body();
		Set<String> declared = SpecialDeclarations.leading(forms, true);
		List<LispSymbol> bound = new ArrayList<>(lambda.params());
		if (lambda.rest() != null) {
			bound.add(lambda.rest());
		}
		List<String> names = new ArrayList<>();
		boolean distinct = true;
		for (LispSymbol parameter : bound) {
			// a special parameter binds dynamically, which the walk's application pops
			if (this.ev.isSpecial(parameter) || declared.contains(parameter.name())) {
				return null;
			}
			if (names.contains(parameter.name())) {
				distinct = false;
			}
			else {
				names.add(parameter.name());
			}
		}
		int lexical = names.size();
		List<String> marks = new ArrayList<>();
		for (String name : declared) {
			if (!this.ev.isProclaimedSpecial(name)) {
				marks.add(name);
			}
		}
		names.addAll(marks);
		if (names.size() > Environment.PRESIZED_SCOPE_MAX) {
			return null;
		}
		Layout scope = new Layout(enclosing, names.toArray(NO_NAMES), lexical);
		String blockName = null;
		List<LispVal> body = forms;
		if (forms.size() == 1 && forms.get(0) instanceof LispCons block && block.car() instanceof LispSymbol head
				&& LispNames.BLOCK.equals(head.name()) && block.cdr() instanceof LispCons nameCell) {
			// a defun's body: one block, established in the call's own scope
			if (!(nameCell.car() instanceof LispNil || nameCell.car() instanceof LispSymbol)) {
				return null;
			}
			blockName = LispEvaluator.blockName(nameCell.car());
			body = chain(nameCell.cdr());
		}
		CompiledForm[] statements = new CompiledForm[Math.max(body.size() - 1, 0)];
		for (int i = 0; i < statements.length; i++) {
			statements[i] = form(body.get(i), scope, null);
		}
		CompiledForm last = body.isEmpty() ? null : form(body.get(body.size() - 1), scope, null);
		return new CompiledBody(this.ev, rewriteEpoch, specialEpoch, bound.toArray(new LispSymbol[0]),
				names.toArray(NO_NAMES), distinct, lambda.params().size(), lambda.rest() != null,
				marks.toArray(NO_NAMES), declared, blockName, statements, last);
	}

	/**
	 * The forms of a body held as a cons chain, as {@code evalAllButLast} runs them:
	 * every car down the chain, a dotted end left out.
	 */
	private static List<LispVal> chain(LispVal forms) {
		List<LispVal> list = new ArrayList<>();
		for (LispVal cell = forms; cell instanceof LispCons cons; cell = cons.cdr()) {
			list.add(cons.car());
		}
		return list;
	}

	private CompiledForms.Body body(LispVal forms, Layout scope, @Nullable LocatedCons located) {
		List<LispVal> list = chain(forms);
		if (list.isEmpty()) {
			return new CompiledForms.Body(NO_FORMS, null);
		}
		CompiledForm[] statements = new CompiledForm[list.size() - 1];
		for (int i = 0; i < statements.length; i++) {
			statements[i] = form(list.get(i), scope, null);
		}
		return new CompiledForms.Body(statements, form(list.get(list.size() - 1), scope, located));
	}

	/**
	 * Compiles a form.
	 * @param form the form
	 * @param scope the layout of the scope it runs in
	 * @param inherited the innermost located form the frame stood on reaching it, null in
	 * a position that opens a frame of its own
	 * @return the compiled form
	 */
	private CompiledForm form(LispVal form, Layout scope, @Nullable LocatedCons inherited) {
		if (--this.budget < 0) {
			throw TOO_BIG;
		}
		if (form instanceof LispCons cons) {
			return cons(cons, scope, inherited);
		}
		if (form instanceof LispSymbol symbol) {
			return symbol(symbol, scope);
		}
		if (form instanceof LispArray || form instanceof LispFloatArray || form instanceof LispIntVector
				|| form instanceof LispStructLiteral || form instanceof LispCircularLiteral) {
			// an array literal is copied, a #S literal folded, at each evaluation
			return new CompiledForms.Walked(this.ev, form);
		}
		return new CompiledForms.Constant(form);
	}

	private CompiledForm symbol(LispSymbol symbol, Layout scope) {
		if (symbol.isKeyword()) {
			return new CompiledForms.Constant(symbol);
		}
		int epoch = this.ev.specialEpoch();
		if (LispNames.PACKAGE_VAR.equals(symbol.name()) || this.ev.isSpecial(symbol)) {
			return new CompiledForms.Walked(this.ev, symbol);
		}
		long where = scope.resolve(symbol.name());
		if (where < 0) {
			return new CompiledForms.Walked(this.ev, symbol);
		}
		return new CompiledForms.Lexical(this.ev, symbol, (int) (where >>> 32), (int) where, epoch);
	}

	private CompiledForm pending(LispCons form, Layout scope, @Nullable LocatedCons located) {
		return new CompiledForms.Pending(this.ev, form, scope, located);
	}

	/** Compiles a compound form: what {@code evalCons} does with it. */
	CompiledForm cons(LispCons cons, Layout scope, @Nullable LocatedCons inherited) {
		LocatedCons here = cons instanceof LocatedCons located ? located : inherited;
		LispVal head = cons.car();
		if (!(head instanceof LispSymbol symbol)) {
			// ((lambda ...) args): the head is evaluated and its value applied
			int length = cons.properLength();
			if (length < 0) {
				return pending(cons, scope, here);
			}
			return new CompiledForms.CallClosure(this.ev, form(head, scope, null), arguments(cons, scope), here);
		}
		String name = symbol.name();
		int length = LispNames.QUOTE.equals(name) ? 1 : cons.properLength();
		if (length < 0) {
			// the walk reports the dotted call
			return pending(cons, scope, here);
		}
		LispEvaluator.ExpansionNote note = this.ev.currentExpansionNote(cons);
		if (note != null) {
			return noted(cons, name, note, scope, here);
		}
		if (this.ev.methoded(name)) {
			// a methoded built-in's calls go to its dispatcher
			return pending(cons, scope, here);
		}
		switch (name) {
			case LispNames.QUOTE:
			case LispNames.UNSPELLED_QUOTE:
				return cons.cdr() instanceof LispCons datum
						? new CompiledForms.Constant(LispCircularLiteral.unwrapped(datum.car()))
						: pending(cons, scope, here);
			case LispNames.IF:
				if (cons instanceof DispatchChain chain) {
					return chain(chain, scope, here);
				}
				return length < 3 ? pending(cons, scope, here) : ifForm(cons, scope, here);
			case LispNames.LET:
				return let(cons, scope, here);
			case LispNames.PROGN:
				return new CompiledForms.Progn(body(cons.cdr(), scope, here), here);
			case LispNames.SETQ:
				return setq(cons, length, scope, here);
			case LispNames.FUNCTION:
				return function(cons, length, scope, here);
			case LispNames.LAMBDA:
				return lambda(cons, scope, here);
			case LispNames.BLOCK:
			case LispNames.BLOCK_INTERNAL:
				return block(cons, name, scope, here);
			case LispNames.RETURN_FROM:
				return exit(cons, length, true, scope, here);
			case LispNames.RETURN:
				return exit(cons, length, false, scope, here);
			case LispNames.WHILE:
				return whileForm(cons, length, scope, here);
			case LispNames.TAGBODY:
				return tagbody(cons, scope, here);
			case LispNames.GO:
				return go(cons, length, scope, here);
			case LispNames.OBJ_REF:
			case LispNames.OBJ_SET:
				if (length == (LispNames.OBJ_REF.equals(name) ? 4 : 5)) {
					return slotAccess(cons, name, scope, here);
				}
				// any other shape is the plain function's call, which the walk notes none
				// of
				return call(cons, name, length, scope, here, true);
			case LispNames.FLOOR:
			case LispNames.CEILING:
			case LispNames.ROUND:
			case LispNames.TRUNCATE:
				// (floor (/ a b)) is the walk's own arm; any other shape the built-in's
				// call, which the walk notes none of
				return LispEvaluator.floorFamilyOperands(cons) != null && length == 2 ? pending(cons, scope, here)
						: call(cons, name, length, scope, here, true);
			case LispNames.REDUCE:
			case LispNames.SORT:
				return reduceOrSort(cons, name, length, scope, here);
			case LispNames.UNWIND_PROTECT: {
				if (length < 2) {
					// the walk reports it
					return pending(cons, scope, here);
				}
				List<LispVal> parts = chain(cons.cdr());
				CompiledForm[] cleanups = new CompiledForm[parts.size() - 1];
				for (int i = 0; i < cleanups.length; i++) {
					cleanups[i] = form(parts.get(i + 1), scope, null);
				}
				return new CompiledForms.UnwindProtect(form(parts.get(0), scope, null), cleanups, here);
			}
			default:
				break;
		}
		// a built-in arm's expansion, memoized per form (a note is per head object, which
		// the forms one macro makes share)
		int methodedEpoch = this.ev.methodedEpoch();
		LispVal expansion = this.ev.memoizedBuiltinExpansion(cons);
		if (expansion != null) {
			return new CompiledForms.Expansion(this.ev, cons, scope, here, form(expansion, scope, here),
					CompiledForms.ExpansionKind.BUILTIN, name, null, methodedEpoch);
		}
		int macroTableEpoch = this.ev.macroTableEpoch();
		LispVal walked = this.ev.heldWalkExpansion(cons);
		if (walked != null) {
			return new CompiledForms.Expansion(this.ev, cons, scope, here, form(walked, scope, here),
					CompiledForms.ExpansionKind.WALK, name, null, macroTableEpoch);
		}
		int callSiteEpoch = this.ev.callSiteEpoch();
		LispVal macroExpansion = this.ev.memoizedUserExpansion(cons, name);
		Object macro = macroExpansion == null ? null : this.ev.userMacro(name);
		if (macroExpansion != null && macro != null) {
			return new CompiledForms.Expansion(this.ev, cons, scope, here, form(macroExpansion, scope, here),
					CompiledForms.ExpansionKind.USER_MACRO, name, macro, callSiteEpoch);
		}
		return call(cons, name, length, scope, here, false);
	}

	/**
	 * A {@code reduce} or {@code sort}: the lowering its arm makes of a shape the
	 * built-in does not take ({@code :from-end}, {@code :key}) -- a function of the form,
	 * which the arm makes again at each evaluation -- or the built-in's call.
	 */
	private CompiledForm reduceOrSort(LispCons cons, String name, int length, Layout scope,
			@Nullable LocatedCons here) {
		BuiltinCallArity.Shape shape = BuiltinCallArity.of(name);
		if (shape != null && !shape.accepts(length - 1)) {
			// the walk reports the count
			return pending(cons, scope, here);
		}
		int epoch = this.ev.methodedEpoch();
		LispVal expansion = LispNames.REDUCE.equals(name) ? LispMacroExpander.expandReduce(cons)
				: LispMacroExpander.expandSortWithKey(cons);
		if (expansion == null) {
			return call(cons, name, length, scope, here, true);
		}
		return new CompiledForms.Expansion(this.ev, cons, scope, here, form(expansion, scope, here),
				CompiledForms.ExpansionKind.BUILTIN, name, null, epoch);
	}

	/** A macro call the walk noted the expansion of. */
	private CompiledForm noted(LispCons cons, String name, LispEvaluator.ExpansionNote note, Layout scope,
			@Nullable LocatedCons here) {
		if (note.userMacro()) {
			int epoch = note.epoch();
			Object macro = this.ev.userMacro(name);
			if (macro == null || this.ev.methoded(name)) {
				return pending(cons, scope, here);
			}
			return new CompiledForms.Expansion(this.ev, cons, scope, here, form(note.expansion(), scope, here),
					CompiledForms.ExpansionKind.USER_MACRO, name, macro, epoch);
		}
		int epoch = this.ev.methodedEpoch();
		return new CompiledForms.Expansion(this.ev, cons, scope, here, form(note.expansion(), scope, here),
				CompiledForms.ExpansionKind.BUILTIN, name, null, epoch);
	}

	/**
	 * An ordinary call, once the walk classified the name one.
	 * @param unclassified whether the name's calls of this shape are ordinary calls the
	 * walk does not note ({@code %obj-ref} past its checked shape)
	 */
	private CompiledForm call(LispCons cons, String name, int length, Layout scope, @Nullable LocatedCons here,
			boolean unclassified) {
		FunctionCell cell = this.ev.globalEnvironment().functionCell(name);
		if (cell == null || !(unclassified || cell.wasCalledOrdinarily())) {
			return pending(cons, scope, here);
		}
		BuiltinCallArity.Shape shape = BuiltinCallArity.of(name);
		if (shape != null && !shape.accepts(length - 1)) {
			// the walk evaluates the arguments and reports the count
			return pending(cons, scope, here);
		}
		int epoch = this.ev.callSiteEpoch();
		if (!this.ev.stillOrdinaryCall(name)) {
			return pending(cons, scope, here);
		}
		return new CompiledForms.Call(this.ev, cons, scope, here, name, cell, arguments(cons, scope), epoch);
	}

	/** A call's argument forms, each in a frame of its own. */
	private CompiledForm[] arguments(LispCons cons, Layout scope) {
		List<LispVal> forms = chain(cons.cdr());
		CompiledForm[] args = new CompiledForm[forms.size()];
		for (int i = 0; i < args.length; i++) {
			args[i] = form(forms.get(i), scope, null);
		}
		return args;
	}

	/** A dispatcher's chain: its keys compiled now, each combination when it is met. */
	private CompiledForm chain(DispatchChain chain, Layout scope, @Nullable LocatedCons here) {
		LispSymbol[] symbols = chain.keys();
		CompiledForms.Key[] keys = new CompiledForms.Key[symbols.length];
		for (int i = 0; i < keys.length; i++) {
			LispSymbol symbol = symbols[i];
			int epoch = this.ev.specialEpoch();
			long where = symbol.isKeyword() || LispNames.PACKAGE_VAR.equals(symbol.name()) || this.ev.isSpecial(symbol)
					? UNKNOWN : scope.resolve(symbol.name());
			keys[i] = where < 0 ? new CompiledForms.Key(this.ev, symbol, -1, -1, epoch)
					: new CompiledForms.Key(this.ev, symbol, (int) (where >>> 32), (int) where, epoch);
		}
		return new CompiledForms.Chain(this.ev, chain, keys, scope, here);
	}

	/**
	 * Compiles what a combination of a chain's shapes leaves to test: the tests, each a
	 * frame of its own, and the arms, each the chain's tail.
	 * @param ev the evaluator
	 * @param selection the combination's selection
	 * @param scope the layout of the dispatcher's scope there
	 * @param located the innermost located form of the frame there
	 * @return the compiled selection
	 */
	static CompiledForms.Chain.Selected compileSelection(LispEvaluator ev, DispatchChain.Selection selection,
			Layout scope, @Nullable LocatedCons located) {
		LispVal[] tests = selection.tests();
		LispVal[] arms = selection.arms();
		CompiledForm[] compiledTests = new CompiledForm[tests.length];
		CompiledForm[] compiledArms = new CompiledForm[arms.length];
		try {
			BodyCompiler compiler = new BodyCompiler(ev);
			for (int i = 0; i < tests.length; i++) {
				compiledTests[i] = compiler.form(tests[i], scope, null);
				compiledArms[i] = compiler.form(arms[i], scope, located);
			}
			return new CompiledForms.Chain.Selected(compiledTests, compiledArms,
					compiler.form(selection.last(), scope, located));
		}
		catch (TooBig | StackOverflowError tooBig) {
			for (int i = 0; i < tests.length; i++) {
				compiledTests[i] = walked(ev, tests[i], scope, null);
				compiledArms[i] = walked(ev, arms[i], scope, located);
			}
			return new CompiledForms.Chain.Selected(compiledTests, compiledArms,
					walked(ev, selection.last(), scope, located));
		}
	}

	/** A form the walk runs each time. */
	private static CompiledForm walked(LispEvaluator ev, LispVal form, Layout scope, @Nullable LocatedCons located) {
		return form instanceof LispCons cons ? new CompiledForms.Pending(ev, cons, scope, located)
				: new CompiledForms.Walked(ev, form);
	}

	private CompiledForm ifForm(LispCons cons, Layout scope, @Nullable LocatedCons here) {
		LispCons rest = (LispCons) cons.cdr();
		LispCons arms = (LispCons) rest.cdr();
		CompiledForm test = form(rest.car(), scope, null);
		CompiledForm then = form(arms.car(), scope, here);
		CompiledForm otherwise = arms.cdr() instanceof LispCons elseCell ? form(elseCell.car(), scope, here) : null;
		return new CompiledForms.If(test, then, otherwise, here);
	}

	/**
	 * A {@code let} whose bindings are all lexical now
	 * ({@code LispEvaluator.lexicalLet}); any other is the walk's, which binds a special
	 * and restores it.
	 */
	private CompiledForm let(LispCons cons, Layout scope, @Nullable LocatedCons here) {
		if (!(cons.cdr() instanceof LispCons bindingsCell)) {
			return pending(cons, scope, here);
		}
		int epoch = this.ev.specialEpoch();
		LispVal bindings = LispMacroExpander.normalizeBindingList(bindingsCell.car());
		if (!(bindings instanceof LispNil) && !(bindings instanceof LispCons)) {
			return pending(cons, scope, here);
		}
		Set<String> declared = SpecialDeclarations.leading(bindingsCell.cdr(), false);
		List<LispSymbol> bound = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		for (LispVal entry = bindings; entry instanceof LispCons cell; entry = cell.cdr()) {
			if (!(cell.car() instanceof LispCons pair) || !(pair.car() instanceof LispSymbol name)
					|| !(pair.cdr() instanceof LispCons init)) {
				return pending(cons, scope, here);
			}
			if (LispNames.PACKAGE_VAR.equals(name.name()) || this.ev.isSpecial(name)
					|| declared.contains(name.name())) {
				return specialLet(cons, bindingsCell, scope, here);
			}
			bound.add(name);
			inits.add(init.car());
		}
		List<String> names = new ArrayList<>();
		boolean distinct = true;
		for (LispSymbol name : bound) {
			if (names.contains(name.name())) {
				distinct = false;
			}
			else {
				names.add(name.name());
			}
		}
		int lexical = names.size();
		List<String> marks = new ArrayList<>();
		for (String name : declared) {
			if (!this.ev.isProclaimedSpecial(name)) {
				marks.add(name);
			}
		}
		names.addAll(marks);
		if (names.size() > Environment.PRESIZED_SCOPE_MAX) {
			return pending(cons, scope, here);
		}
		// the inits run in the outer scope, each in a frame of its own
		CompiledForm[] values = new CompiledForm[inits.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = form(inits.get(i), scope, null);
		}
		Layout inner = new Layout(scope, names.toArray(NO_NAMES), lexical);
		return new CompiledForms.Let(this.ev, cons, here, bound.toArray(new LispSymbol[0]), names.toArray(NO_NAMES),
				distinct, values, marks.toArray(NO_NAMES), declared, body(bindingsCell.cdr(), inner, here), epoch);
	}

	/**
	 * A {@code let} binding a special or {@code *package*}, as {@code evalLetIn} binds
	 * it, its body's scope laid out from the names proclaimed special now.
	 */
	private CompiledForm specialLet(LispCons cons, LispCons bindingsCell, Layout scope, @Nullable LocatedCons here) {
		int epoch = this.ev.specialEpoch();
		LispVal bindings;
		try {
			bindings = LispMacroExpander.letBindingList(bindingsCell.car());
		}
		catch (RuntimeException malformed) {
			// the walk reports it
			return pending(cons, scope, here);
		}
		if (!(bindings instanceof LispNil) && !(bindings instanceof LispCons)) {
			return pending(cons, scope, here);
		}
		Set<String> declared = SpecialDeclarations.leading(bindingsCell.cdr(), false);
		List<String> names = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		for (LispVal entry = bindings; entry instanceof LispCons cell; entry = cell.cdr()) {
			if (!(cell.car() instanceof LispCons pair) || !(pair.car() instanceof LispSymbol name)
					|| !(pair.cdr() instanceof LispCons init) || names.contains(name.name())) {
				return pending(cons, scope, here);
			}
			names.add(name.name());
			inits.add(init.car());
		}
		int count = names.size();
		boolean[] dynamic = new boolean[count];
		boolean[] swapsPackage = new boolean[count];
		List<String> lexical = new ArrayList<>();
		List<Integer> sources = new ArrayList<>();
		List<String> marks = new ArrayList<>();
		List<String> watched = new ArrayList<>();
		List<Boolean> proclaimed = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String name = names.get(i);
			boolean special = this.ev.isProclaimedSpecial(name);
			watched.add(name);
			proclaimed.add(special);
			swapsPackage[i] = LispNames.PACKAGE_VAR.equals(name);
			dynamic[i] = special || declared.contains(name);
			if (!dynamic[i]) {
				lexical.add(name);
				sources.add(i);
			}
			else if (!special) {
				marks.add(name);
			}
		}
		for (String name : declared) {
			boolean special = this.ev.isProclaimedSpecial(name);
			watched.add(name);
			proclaimed.add(special);
			if (!special && !names.contains(name)) {
				marks.add(name);
			}
		}
		List<String> slots = new ArrayList<>(lexical);
		slots.addAll(marks);
		if (slots.size() > Environment.PRESIZED_SCOPE_MAX) {
			return pending(cons, scope, here);
		}
		CompiledForm[] values = new CompiledForm[count];
		for (int i = 0; i < count; i++) {
			values[i] = form(inits.get(i), scope, null);
		}
		Layout inner = new Layout(scope, slots.toArray(NO_NAMES), lexical.size());
		int[] sourceIndexes = new int[sources.size()];
		for (int i = 0; i < sourceIndexes.length; i++) {
			sourceIndexes[i] = sources.get(i);
		}
		boolean[] watchedProclaimed = new boolean[proclaimed.size()];
		for (int i = 0; i < watchedProclaimed.length; i++) {
			watchedProclaimed[i] = proclaimed.get(i);
		}
		// the body's forms but the last run in frames of their own, the last in the let's
		CompiledForms.Body body = body(bindingsCell.cdr(), inner, null);
		return new CompiledForms.SpecialLet(this.ev, cons, here, names.toArray(NO_NAMES), values, dynamic, swapsPackage,
				slots.toArray(NO_NAMES), sourceIndexes, watched.toArray(NO_NAMES), watchedProclaimed, declared, body,
				!(bindingsCell.cdr() instanceof LispCons), epoch);
	}

	private CompiledForm setq(LispCons cons, int length, Layout scope, @Nullable LocatedCons here) {
		if ((length - 1) % 2 != 0) {
			// the walk reports the odd count
			return pending(cons, scope, here);
		}
		int pairs = (length - 1) / 2;
		LispSymbol[] targets = new LispSymbol[pairs];
		int[] depths = new int[pairs];
		int[] indexes = new int[pairs];
		CompiledForm[] values = new CompiledForm[pairs];
		LispVal[] valueForms = new LispVal[pairs];
		int epoch = this.ev.specialEpoch();
		LispVal cell = cons.cdr();
		for (int i = 0; i < pairs; i++) {
			LispCons nameCell = (LispCons) cell;
			LispCons valueCell = (LispCons) nameCell.cdr();
			if (!(nameCell.car() instanceof LispSymbol target)) {
				return pending(cons, scope, here);
			}
			targets[i] = target;
			long where = LispNames.PACKAGE_VAR.equals(target.name()) || this.ev.isSpecial(target) ? UNKNOWN
					: scope.resolve(target.name());
			depths[i] = where < 0 ? -1 : (int) (where >>> 32);
			indexes[i] = where < 0 ? -1 : (int) where;
			valueForms[i] = valueCell.car();
			values[i] = form(valueCell.car(), scope, null);
			cell = valueCell.cdr();
		}
		return new CompiledForms.Setq(this.ev, cons, here, targets, depths, indexes, values, valueForms, epoch);
	}

	/** A {@code #'...} ({@code LispEvaluator.evalFunction}). */
	private CompiledForm function(LispCons cons, int length, Layout scope, @Nullable LocatedCons here) {
		if (length != 2) {
			return pending(cons, scope, here);
		}
		LispVal designator = ((LispCons) cons.cdr()).car();
		if (designator instanceof LispCons lambdaForm && lambdaForm.car() instanceof LispSymbol op
				&& LispNames.LAMBDA.equals(op.name())) {
			return lambda(lambdaForm, scope, here);
		}
		LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(designator);
		if (setfPlace != null) {
			return new CompiledForms.FunctionValue(this.ev, LispMacroExpander.setfFunctionName(setfPlace.name()));
		}
		if (designator instanceof LispSymbol symbol) {
			return new CompiledForms.FunctionValue(this.ev, symbol.name());
		}
		return pending(cons, scope, here);
	}

	/** A {@code lambda} form, once the walk made a closure of it (its shape). */
	private CompiledForm lambda(LispCons cons, Layout scope, @Nullable LocatedCons here) {
		LispEvaluator.LambdaSite site = this.ev.lambdaSite(cons);
		if (site == null) {
			return pending(cons, scope, here);
		}
		return new CompiledForms.Closure(this.ev, CompiledForms.Closure.within(site, scope));
	}

	private CompiledForm block(LispCons cons, String operator, Layout scope, @Nullable LocatedCons here) {
		LispVal body = cons.cdr();
		String name = "NIL";
		if (LispNames.BLOCK.equals(operator)) {
			if (!(body instanceof LispCons nameCell)
					|| !(nameCell.car() instanceof LispNil || nameCell.car() instanceof LispSymbol)) {
				return pending(cons, scope, here);
			}
			name = LispEvaluator.blockName(nameCell.car());
			body = nameCell.cdr();
		}
		Layout inner = new Layout(scope, NO_NAMES, 0);
		return new CompiledForms.Block(this.ev, name, body(body, inner, here), here);
	}

	/**
	 * A {@code return-from} ({@code evalReturnFrom}) or a {@code return}
	 * ({@code evalReturnValue}).
	 */
	private CompiledForm exit(LispCons cons, int length, boolean named, Layout scope, @Nullable LocatedCons here) {
		LispVal designator;
		LispVal valueForm;
		boolean deferrable;
		if (named) {
			if (length < 2 || length > 3) {
				return pending(cons, scope, here);
			}
			LispCons nameCell = (LispCons) cons.cdr();
			designator = nameCell.car();
			if (!(designator instanceof LispNil || designator instanceof LispSymbol)) {
				return pending(cons, scope, here);
			}
			valueForm = nameCell.cdr() instanceof LispCons valueCell ? valueCell.car() : null;
			deferrable = length == 3;
		}
		else {
			designator = LispNil.INSTANCE;
			valueForm = cons.cdr() instanceof LispCons valueCell ? valueCell.car() : null;
			deferrable = length == 2;
		}
		CompiledForm value = valueForm == null ? null : form(valueForm, scope, here);
		return new CompiledForms.Exit(designator, LispEvaluator.blockName(designator), value,
				deferrable ? valueForm : null, here);
	}

	private CompiledForm whileForm(LispCons cons, int length, Layout scope, @Nullable LocatedCons here) {
		if (length < 2) {
			return pending(cons, scope, here);
		}
		List<LispVal> parts = chain(cons.cdr());
		CompiledForm test = form(parts.get(0), scope, null);
		CompiledForm[] body = new CompiledForm[parts.size() - 1];
		for (int i = 0; i < body.length; i++) {
			body[i] = form(parts.get(i + 1), scope, null);
		}
		return new CompiledForms.While(this.ev, test, body, here);
	}

	private CompiledForm tagbody(LispCons cons, Layout scope, @Nullable LocatedCons here) {
		List<LispVal> items = chain(cons.cdr());
		@Nullable String[] keys = new String[items.size()];
		@Nullable CompiledForm[] statements = new CompiledForm[items.size()];
		for (int i = 0; i < keys.length; i++) {
			keys[i] = LispEvaluator.goTagKey(items.get(i));
			statements[i] = keys[i] != null ? null : form(items.get(i), scope, null);
		}
		return new CompiledForms.Tagbody(this.ev, keys, statements, here);
	}

	private CompiledForm go(LispCons cons, int length, Layout scope, @Nullable LocatedCons here) {
		String key = LispEvaluator.goTagKey(cons.cdr() instanceof LispCons tagCell ? tagCell.car() : null);
		if (key == null) {
			// the walk reports the missing tag
			return pending(cons, scope, here);
		}
		return new CompiledForms.Go(key, length == 2);
	}

	private CompiledForm slotAccess(LispCons cons, String name, Layout scope, @Nullable LocatedCons here) {
		boolean read = LispNames.OBJ_REF.equals(name);
		LispCons objectCell = (LispCons) cons.cdr();
		LispCons indexCell = (LispCons) objectCell.cdr();
		LispCons afterIndex = (LispCons) indexCell.cdr();
		CompiledForm object = form(objectCell.car(), scope, null);
		CompiledForm value = read ? null : form(afterIndex.car(), scope, null);
		LispVal failureForm = read ? afterIndex.car() : ((LispCons) afterIndex.cdr()).car();
		return new CompiledForms.SlotAccess(name, object, indexCell.car(), value, form(failureForm, scope, here), here);
	}

}
