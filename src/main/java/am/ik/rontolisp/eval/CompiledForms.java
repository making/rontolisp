package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.BodyCode;
import am.ik.rontolisp.DispatchChain;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import org.jspecify.annotations.Nullable;

/**
 * The compiled forms {@link BodyCompiler} builds, one class per arm of
 * {@code LispEvaluator.evalCons} it compiles, each doing what that arm does
 * ({@code .kb/interpreter-compiled-bodies.md}). A form compiled from one read from a file
 * gives an error leaving it that place, as the frame that stood on it would
 * ({@code ConditionTrace}).
 */
final class CompiledForms {

	private CompiledForms() {
	}

	/** {@code LispEvaluator.NO_JUMP}. */
	private static final int NO_JUMP = LispEvaluator.NO_JUMP;

	/**
	 * Gives an error leaving a form read from a file that place.
	 * @param e the error
	 * @param located the form, or null
	 * @param env the scope it ran in
	 * @return the error
	 */
	static LispEvalException passing(LispEvalException e, @Nullable LocatedCons located, Environment env) {
		if (located != null) {
			e.trace().passing(located, env.lexicalFunction());
		}
		return e;
	}

	/** The value of a form whose value is nil, one value. */
	static LispVal nil(Environment env) {
		env.clearSpill();
		return LispNil.INSTANCE;
	}

	/**
	 * A self-evaluating value: a number, a string, a character, a keyword, nil, t, a
	 * quoted datum.
	 */
	static final class Constant extends CompiledForm {

		private final LispVal value;

		Constant(LispVal value) {
			super(null);
			this.value = value;
		}

		@Override
		LispVal eval(Environment env) {
			env.clearSpill();
			return this.value;
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			env.clearSpill();
			return NO_JUMP;
		}

	}

	/**
	 * An atom the walk makes something of each time -- an array literal it copies, a
	 * {@code #S} literal it folds -- or a variable it looks up by name: one no compiled
	 * scope binds, or one a special declaration covers.
	 */
	static final class Walked extends CompiledForm {

		private final LispEvaluator ev;

		private final LispVal atom;

		Walked(LispEvaluator ev, LispVal atom) {
			super(null);
			this.ev = ev;
			this.atom = atom;
		}

		@Override
		LispVal eval(Environment env) {
			return this.ev.evalAtom(this.atom, env);
		}

	}

	/**
	 * A variable a compiled scope binds: the binding at a position of the scope a number
	 * of scopes out, while the name is not proclaimed special.
	 */
	static final class Lexical extends CompiledForm {

		private final LispEvaluator ev;

		private final LispSymbol symbol;

		private final int depth;

		private final int index;

		private int epoch;

		Lexical(LispEvaluator ev, LispSymbol symbol, int depth, int index, int epoch) {
			super(null);
			this.ev = ev;
			this.symbol = symbol;
			this.depth = depth;
			this.index = index;
			this.epoch = epoch;
		}

		@Override
		LispVal eval(Environment env) {
			if (this.epoch != this.ev.specialEpoch() && !stillLexical()) {
				return this.ev.evalAtom(this.symbol, env);
			}
			Environment scope = env;
			for (int i = this.depth; i > 0; i--) {
				scope = scope.parent();
			}
			LispVal value = scope.slot(this.index);
			env.clearSpill();
			return value;
		}

		private boolean stillLexical() {
			int now = this.ev.specialEpoch();
			if (this.ev.isSpecial(this.symbol)) {
				return false;
			}
			this.epoch = now;
			return true;
		}

	}

	/** An {@code if} ({@code evalCons}'s arm). */
	static final class If extends CompiledForm {

		private final CompiledForm test;

		private final CompiledForm then;

		private final @Nullable CompiledForm otherwise;

		If(CompiledForm test, CompiledForm then, @Nullable CompiledForm otherwise, @Nullable LocatedCons located) {
			super(located);
			this.test = test;
			this.then = then;
			this.otherwise = otherwise;
		}

		@Override
		LispVal eval(Environment env) {
			try {
				if (!(this.test.eval(env) instanceof LispNil)) {
					return this.then.eval(env);
				}
				return this.otherwise != null ? this.otherwise.eval(env) : nil(env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			try {
				if (!(this.test.eval(env) instanceof LispNil)) {
					return this.then.statement(env, owner, deferTo, labels);
				}
				if (this.otherwise != null) {
					return this.otherwise.statement(env, owner, deferTo, labels);
				}
				env.clearSpill();
				return NO_JUMP;
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			if (!(this.test.eval(env) instanceof LispNil)) {
				return this.then;
			}
			return this.otherwise != null ? this.otherwise : nil(env);
		}

	}

	/**
	 * A parameter a dispatcher's chain tells arguments apart by: its value, as
	 * {@code LispEvaluator.evalSymbolRef} answers it, by its position where a compiled
	 * scope binds it.
	 */
	static final class Key {

		private final LispEvaluator ev;

		private final LispSymbol symbol;

		private final int depth;

		private final int index;

		private int epoch;

		Key(LispEvaluator ev, LispSymbol symbol, int depth, int index, int epoch) {
			this.ev = ev;
			this.symbol = symbol;
			this.depth = depth;
			this.index = index;
			this.epoch = epoch;
		}

		LispVal value(Environment env) {
			if (this.depth < 0 || this.epoch != this.ev.specialEpoch() && !stillLexical()) {
				return this.ev.evalSymbolRef(this.symbol, env);
			}
			Environment scope = env;
			for (int i = this.depth; i > 0; i--) {
				scope = scope.parent();
			}
			return scope.slot(this.index);
		}

		private boolean stillLexical() {
			int now = this.ev.specialEpoch();
			if (this.ev.isSpecial(this.symbol)) {
				return false;
			}
			this.epoch = now;
			return true;
		}

	}

	/**
	 * A generic's dispatcher chain ({@link DispatchChain}, {@code dispatchChainArm}): the
	 * arguments' shapes find what their combination leaves to test, and the first test
	 * that holds picks the arm, which is the form's tail. Each combination's tests and
	 * arms are compiled the first time it is met.
	 */
	static final class Chain extends CompiledForm {

		private final LispEvaluator ev;

		private final DispatchChain chain;

		private final Key[] keys;

		private final BodyCompiler.Layout layout;

		/**
		 * Each selection met so far with its compiled form, by identity: a selection is a
		 * record whose last form a structural hash would walk. Replaced, never written,
		 * so a read takes no lock.
		 */
		private volatile java.util.IdentityHashMap<DispatchChain.Selection, Selected> selections = new java.util.IdentityHashMap<>();

		Chain(LispEvaluator ev, DispatchChain chain, Key[] keys, BodyCompiler.Layout layout,
				@Nullable LocatedCons located) {
			super(located);
			this.ev = ev;
			this.chain = chain;
			this.keys = keys;
			this.layout = layout;
		}

		/**
		 * A combination's tests and arms, compiled.
		 *
		 * @param tests the tests the shapes leave open
		 * @param arms the arm each selects
		 * @param last the form when none holds
		 */
		record Selected(CompiledForm[] tests, CompiledForm[] arms, CompiledForm last) {
		}

		private Selected selected(DispatchChain.Selection selection) {
			Selected selected = this.selections.get(selection);
			if (selected != null) {
				return selected;
			}
			Selected compiled = BodyCompiler.compileSelection(this.ev, selection, this.layout, this.located);
			synchronized (this) {
				java.util.IdentityHashMap<DispatchChain.Selection, Selected> grown = new java.util.IdentityHashMap<>(
						this.selections);
				grown.put(selection, compiled);
				this.selections = grown;
			}
			return compiled;
		}

		private CompiledForm arm(Environment env) {
			Object found = this.chain.root();
			for (int i = 0; i < this.keys.length && found != null; i++) {
				found = ((DispatchChain.Shapes) found).get(DispatchChain.shapeOf(this.keys[i].value(env)));
			}
			DispatchChain.Selection selection = found != null ? (DispatchChain.Selection) found
					: this.ev.firstChainSelection(this.chain, env);
			Selected selected = selected(selection);
			CompiledForm[] tests = selected.tests();
			for (int i = 0; i < tests.length; i++) {
				if (!(tests[i].eval(env) instanceof LispNil)) {
					return selected.arms()[i];
				}
			}
			return selected.last();
		}

		@Override
		LispVal eval(Environment env) {
			try {
				return arm(env).eval(env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			try {
				return arm(env).statement(env, owner, deferTo, labels);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			return arm(env);
		}

	}

	/**
	 * A body: forms run for effect, then the last one, in the scope the body runs in
	 * ({@code evalAllButLast}). A {@code progn}'s, and the body of every binding form and
	 * block.
	 */
	static final class Body {

		private final CompiledForm[] statements;

		private final @Nullable CompiledForm last;

		Body(CompiledForm[] statements, @Nullable CompiledForm last) {
			this.statements = statements;
			this.last = last;
		}

		void statements(Environment env, @Nullable Environment exitTo) {
			for (CompiledForm statement : this.statements) {
				statement.statement(env, null, exitTo, null);
			}
		}

		LispVal eval(Environment env) {
			statements(env, null);
			CompiledForm tail = this.last;
			return tail == null ? nil(env) : tail.eval(env);
		}

		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			statements(env, owner != null ? owner : deferTo);
			CompiledForm tail = this.last;
			if (tail == null) {
				env.clearSpill();
				return NO_JUMP;
			}
			return tail.statement(env, owner, deferTo, labels);
		}

		/** The statements run, the form the frame steps onto next in the same scope. */
		Object step(Environment env, Environment owner) {
			statements(env, owner);
			CompiledForm tail = this.last;
			return tail == null ? nil(env) : tail;
		}

		/**
		 * The statements run, the form the frame steps onto next in a scope of its own.
		 */
		Object stepIn(Environment scope, Environment owner) {
			statements(scope, owner);
			CompiledForm tail = this.last;
			return tail == null ? nil(scope) : new CompiledForm.Scoped(tail, scope);
		}

	}

	/** A {@code progn}. */
	static final class Progn extends CompiledForm {

		private final Body body;

		Progn(Body body, @Nullable LocatedCons located) {
			super(located);
			this.body = body;
		}

		@Override
		LispVal eval(Environment env) {
			try {
				return this.body.eval(env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			try {
				return this.body.statement(env, owner, deferTo, labels);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			return this.body.step(env, owner);
		}

	}

	/**
	 * A form the walk runs each time it would, through its {@code evalCons} frame: one of
	 * the forms a compiled body hands back ({@link Guarded}, {@link Pending}).
	 */
	abstract static class Walking extends CompiledForm {

		final LispEvaluator ev;

		final LispCons form;

		Walking(LispEvaluator ev, LispCons form, @Nullable LocatedCons located) {
			super(located);
			this.ev = ev;
			this.form = form;
		}

		LispVal walk(Environment env) {
			return this.ev.eval(this.form, env);
		}

		int walkStatement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			Environment exitTo = owner != null ? owner : deferTo;
			if (labels != null) {
				return this.ev.evalTagbodyStatement(this.form, env, labels, exitTo);
			}
			this.ev.evalStatement(this.form, env, exitTo);
			return NO_JUMP;
		}

		Object walkTail(Environment env) {
			return new TailForm(this.form, env, this.located);
		}

	}

	/**
	 * A form the compiler could not tell the arm of yet -- the walk has not run it, or
	 * what it worked out no longer holds: the walk runs it, and once the walk has run it
	 * the compiler tries again. A compiled form it holds that stops holding hands the
	 * form back to it, so it compiles the form again in its place, never below it.
	 */
	static final class Pending extends Walking {

		/** How many compiles in a row may find nothing before the walk keeps the form. */
		private static final int GIVE_UP_AFTER = 12;

		private final BodyCompiler.Layout layout;

		private volatile @Nullable CompiledForm compiled;

		private int walks;

		private int nextTry = 1;

		private int failed;

		Pending(LispEvaluator ev, LispCons form, BodyCompiler.Layout layout, @Nullable LocatedCons located) {
			super(ev, form, located);
			this.layout = layout;
		}

		/** The form's compile, once the walk has run it, while it holds; else null. */
		private @Nullable CompiledForm compiled() {
			CompiledForm done = this.compiled;
			if (done != null && (!(done instanceof Guarded guarded) || guarded.holds())) {
				return done;
			}
			// a compile is tried after the walk ran the form once, then after 2, 4 ...
			// walks, so a form no compile takes costs a try now and then
			int walked = this.walks;
			if (walked < this.nextTry || this.failed >= GIVE_UP_AFTER) {
				return null;
			}
			CompiledForm fresh = BodyCompiler.compileSite(this.ev, this.form, this.layout, this.located);
			if (fresh == null) {
				this.failed++;
				this.nextTry = walked * 2;
				this.compiled = null;
				return null;
			}
			if (fresh instanceof Guarded guarded) {
				guarded.fallback(this);
			}
			this.failed = 0;
			this.nextTry = walked + 1;
			this.compiled = fresh;
			return fresh;
		}

		@Override
		LispVal eval(Environment env) {
			CompiledForm done = compiled();
			if (done != null) {
				return done.eval(env);
			}
			this.walks++;
			return walk(env);
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			CompiledForm done = compiled();
			if (done != null) {
				return done.statement(env, owner, deferTo, labels);
			}
			this.walks++;
			return walkStatement(env, owner, deferTo, labels);
		}

		@Override
		Object step(Environment env, Environment owner) {
			CompiledForm done = compiled();
			if (done != null) {
				return done;
			}
			// the frame walks it once this returns
			this.walks++;
			return walkTail(env);
		}

	}

	/**
	 * A compiled form that holds while what the walk worked out of its form does -- a
	 * call still an ordinary call, a macro call's expansion still the macro's -- and
	 * hands the form to a {@link Pending} that walks it, and compiles it again, once it
	 * does not.
	 */
	abstract static class Guarded extends Walking {

		private final BodyCompiler.Layout layout;

		private volatile @Nullable Pending fallback;

		Guarded(LispEvaluator ev, LispCons form, BodyCompiler.Layout layout, @Nullable LocatedCons located) {
			super(ev, form, located);
			this.layout = layout;
		}

		/**
		 * Whether what was worked out still holds, taking the current epoch when it does.
		 * @return whether it holds
		 */
		abstract boolean holds();

		/**
		 * Makes the {@link Pending} that compiled this form the one it hands it back to.
		 */
		void fallback(Pending pending) {
			this.fallback = pending;
		}

		/** What runs the form now that this no longer holds. */
		Pending fallback() {
			Pending pending = this.fallback;
			if (pending == null) {
				pending = new Pending(this.ev, this.form, this.layout, this.located);
				this.fallback = pending;
			}
			return pending;
		}

	}

	/**
	 * A {@code let} whose bindings are all lexical ({@code LispEvaluator.lexicalLet}):
	 * the inits evaluated in the outer scope, the names bound in a scope of their own,
	 * the names a free special declaration of its body names marked there. One whose name
	 * is proclaimed special since is the walk's, which binds it dynamically.
	 */
	static final class Let extends Walking {

		private final LispSymbol[] bound;

		private final String[] slots;

		private final boolean distinct;

		private final CompiledForm[] inits;

		private final String[] marks;

		private final java.util.Set<String> declared;

		private final Body body;

		private int epoch;

		private boolean declaredRecorded;

		Let(LispEvaluator ev, LispCons form, @Nullable LocatedCons located, LispSymbol[] bound, String[] slots,
				boolean distinct, CompiledForm[] inits, String[] marks, java.util.Set<String> declared, Body body,
				int epoch) {
			super(ev, form, located);
			this.bound = bound;
			this.slots = slots;
			this.distinct = distinct;
			this.inits = inits;
			this.marks = marks;
			this.declared = declared;
			this.body = body;
			this.epoch = epoch;
		}

		/** The let's scope, or null when a name it binds is special now. */
		private @Nullable Environment bind(Environment env) {
			if (this.epoch != this.ev.specialEpoch() && !stillLexical()) {
				return null;
			}
			if (!this.declaredRecorded) {
				this.ev.recordLocalSpecials(this.declared);
				this.declaredRecorded = true;
			}
			CompiledForm[] inits = this.inits;
			if (this.distinct) {
				LispVal[] values = new LispVal[this.slots.length];
				for (int i = 0; i < inits.length; i++) {
					values[i] = inits[i].eval(env);
				}
				for (int i = inits.length; i < values.length; i++) {
					values[i] = Environment.SPECIAL;
				}
				return new Environment(env, this.slots, values);
			}
			// a name bound twice holds its last binding, at its first slot, as define
			// does
			Environment scope = new Environment(env, this.slots.length);
			for (int i = 0; i < inits.length; i++) {
				scope.define(this.bound[i].name(), inits[i].eval(env));
			}
			for (String mark : this.marks) {
				scope.bindFresh(mark, Environment.SPECIAL);
			}
			return scope;
		}

		private boolean stillLexical() {
			int now = this.ev.specialEpoch();
			for (LispSymbol name : this.bound) {
				if (this.ev.isSpecial(name)) {
					return false;
				}
			}
			int marked = 0;
			for (String name : this.declared) {
				if (!this.ev.isProclaimedSpecial(name)) {
					if (marked >= this.marks.length || !this.marks[marked].equals(name)) {
						return false;
					}
					marked++;
				}
			}
			if (marked != this.marks.length) {
				return false;
			}
			this.epoch = now;
			return true;
		}

		@Override
		LispVal eval(Environment env) {
			try {
				Environment scope = bind(env);
				return scope == null ? walk(env) : this.body.eval(scope);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			try {
				Environment scope = bind(env);
				return scope == null ? walkStatement(env, owner, deferTo, labels)
						: this.body.statement(scope, owner, deferTo, labels);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			Environment scope = bind(env);
			return scope == null ? walkTail(env) : this.body.stepIn(scope, owner);
		}

	}

	/**
	 * A {@code setq} ({@code LispEvaluator.evalSetq}): each value evaluated and stored in
	 * turn, a variable a compiled scope binds by its position, any other the way the walk
	 * stores it ({@code assignVariable}).
	 */
	static final class Setq extends Walking {

		private final LispSymbol[] targets;

		private final int[] depths;

		private final int[] indexes;

		private final CompiledForm[] values;

		private final boolean anyFree;

		private int epoch;

		Setq(LispEvaluator ev, LispCons form, @Nullable LocatedCons located, LispSymbol[] targets, int[] depths,
				int[] indexes, CompiledForm[] values, int epoch) {
			super(ev, form, located);
			this.targets = targets;
			this.depths = depths;
			this.indexes = indexes;
			this.values = values;
			boolean free = false;
			for (int depth : depths) {
				free |= depth < 0;
			}
			this.anyFree = free;
			this.epoch = epoch;
		}

		@Override
		LispVal eval(Environment env) {
			try {
				// a free name may be a global symbol macro, which the walk writes through
				if (this.anyFree && this.ev.globalSymbolMacrosDefined()) {
					return walk(env);
				}
				boolean lexical = this.epoch == this.ev.specialEpoch() || stillLexical();
				LispVal value = LispNil.INSTANCE;
				for (int i = 0; i < this.targets.length; i++) {
					value = this.values[i].eval(env);
					int depth = this.depths[i];
					if (depth < 0 || !lexical) {
						this.ev.assignVariable(this.targets[i].name(), value, env);
					}
					else {
						Environment scope = env;
						for (int d = depth; d > 0; d--) {
							scope = scope.parent();
						}
						scope.setSlot(this.indexes[i], value);
					}
				}
				env.clearSpill();
				return value;
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		private boolean stillLexical() {
			int now = this.ev.specialEpoch();
			for (int i = 0; i < this.targets.length; i++) {
				if (this.depths[i] >= 0 && this.ev.isSpecial(this.targets[i])) {
					return false;
				}
			}
			this.epoch = now;
			return true;
		}

	}

	/**
	 * A {@code block} or {@code %block}: a scope of its own whose exit target is the
	 * frame's -- a block in a frame's tail shares the frame's identity, any other is its
	 * own frame's first block.
	 */
	static final class Block extends CompiledForm {

		private final LispEvaluator ev;

		private final String name;

		private final Body body;

		Block(LispEvaluator ev, String name, Body body, @Nullable LocatedCons located) {
			super(located);
			this.ev = ev;
			this.name = name;
			this.body = body;
		}

		@Override
		LispVal eval(Environment env) {
			try {
				Environment scope = new Environment(env);
				scope.installBlock(this.name, scope);
				return CompiledFrames.tail(this.ev, new BodyTail(this.body, this.located), scope);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			try {
				Environment scope = new Environment(env);
				if (owner != null) {
					scope.installBlock(this.name, owner);
					return this.body.statement(scope, owner, deferTo, labels);
				}
				// the statement's frame owns no block yet: this one is its first
				scope.installBlock(this.name, scope);
				try {
					return this.body.statement(scope, scope, deferTo, labels);
				}
				catch (BlockReturnSignal signal) {
					if (signal.target() != scope) {
						throw signal;
					}
					return NO_JUMP;
				}
				catch (BlockTailExit exit) {
					if (exit.target() != scope) {
						throw exit;
					}
					return continueStatement(exit, scope, labels);
				}
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		/**
		 * An exit to this block from a statement of its body: its value form is the tail
		 * of the statement this block is the first block of, run by the walk.
		 */
		private int continueStatement(BlockTailExit first, Environment scope,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			BlockTailExit exit = first;
			while (true) {
				try {
					if (labels != null) {
						return this.ev.evalTagbodyStatement(exit.form(), exit.env(), labels, scope);
					}
					this.ev.evalStatement(exit.form(), exit.env(), scope);
					return NO_JUMP;
				}
				catch (BlockReturnSignal signal) {
					if (signal.target() != scope) {
						throw signal;
					}
					return NO_JUMP;
				}
				catch (BlockTailExit again) {
					if (again.target() != scope) {
						throw again;
					}
					exit = again;
				}
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			Environment scope = new Environment(env);
			scope.installBlock(this.name, owner);
			return this.body.stepIn(scope, owner);
		}

	}

	/** A body run as the tail of a compiled frame ({@link Block#eval}). */
	private static final class BodyTail extends CompiledForm {

		private final Body body;

		BodyTail(Body body, @Nullable LocatedCons located) {
			super(located);
			this.body = body;
		}

		@Override
		LispVal eval(Environment env) {
			return this.body.eval(env);
		}

		@Override
		Object step(Environment env, Environment owner) {
			return this.body.step(env, owner);
		}

	}

	/**
	 * A {@code return-from} or a {@code return}: an exit to the block the name finds,
	 * whose value form is the tail of the frame that owns that block when the exit is in
	 * that frame's tail ({@code exitsToFrame}), or is handed to it from a statement with
	 * no dynamic extent in between.
	 */
	static final class Exit extends CompiledForm {

		private final LispVal designator;

		private final String name;

		private final @Nullable CompiledForm value;

		private final @Nullable LispVal valueForm;

		Exit(LispVal designator, String name, @Nullable CompiledForm value, @Nullable LispVal valueForm,
				@Nullable LocatedCons located) {
			super(located);
			this.designator = designator;
			this.name = name;
			this.value = value;
			this.valueForm = valueForm;
		}

		@Override
		LispVal eval(Environment env) {
			try {
				LispVal exiting = this.value != null ? this.value.eval(env) : nil(env);
				throw LispEvaluator.blockExit(this.name, exiting, env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			if (this.valueForm != null && this.value != null) {
				if (LispEvaluator.exitsOwnedBlock(this.designator, env, owner)) {
					try {
						return this.value.statement(env, owner, deferTo, labels);
					}
					catch (LispEvalException e) {
						throw passing(e, this.located, env);
					}
				}
				if (deferTo != null && LispEvaluator.exitsOwnedBlock(this.designator, env, deferTo)) {
					throw new BlockTailExit(deferTo, this.valueForm, env);
				}
			}
			eval(env);
			return NO_JUMP;
		}

		@Override
		Object step(Environment env, Environment owner) {
			if (this.valueForm != null && this.value != null
					&& LispEvaluator.exitsOwnedBlock(this.designator, env, owner)) {
				return this.value;
			}
			return eval(env);
		}

	}

	/**
	 * A {@code while} ({@code LispEvaluator.evalWhile}): its body's forms statements of
	 * the frame the loop runs in.
	 */
	static final class While extends CompiledForm {

		private final LispEvaluator ev;

		private final CompiledForm test;

		private final CompiledForm[] body;

		While(LispEvaluator ev, CompiledForm test, CompiledForm[] body, @Nullable LocatedCons located) {
			super(located);
			this.ev = ev;
			this.test = test;
			this.body = body;
		}

		private LispVal loop(Environment env, @Nullable Environment exitTo) {
			try {
				while (!(this.test.eval(env) instanceof LispNil)) {
					this.ev.pollInterrupt();
					for (CompiledForm statement : this.body) {
						statement.statement(env, null, exitTo, null);
					}
				}
				return nil(env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		LispVal eval(Environment env) {
			return loop(env, null);
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			loop(env, owner != null ? owner : deferTo);
			return NO_JUMP;
		}

		@Override
		Object step(Environment env, Environment owner) {
			return loop(env, owner);
		}

	}

	/**
	 * A {@code tagbody} ({@code LispEvaluator.evalTagbody}): its statements run in turn,
	 * a {@code go} in a statement's tail answering the index of its label, any other
	 * caught as the walk catches it.
	 */
	static final class Tagbody extends CompiledForm {

		private final LispEvaluator ev;

		private final @Nullable String[] keys;

		private final @Nullable CompiledForm[] items;

		private final boolean labelled;

		Tagbody(LispEvaluator ev, @Nullable String[] keys, @Nullable CompiledForm[] items,
				@Nullable LocatedCons located) {
			super(located);
			this.ev = ev;
			this.keys = keys;
			this.items = items;
			boolean any = false;
			for (String key : keys) {
				any |= key != null;
			}
			this.labelled = any;
		}

		private LispVal run(Environment env, @Nullable Environment exitTo) {
			try {
				// a tagbody with labels is a dynamic extent to an exit's value form: a go
				// in it must find this tagbody
				Environment statementExitTo = this.labelled ? null : exitTo;
				LispEvaluator.TagbodyLabels labels = LispEvaluator.TagbodyLabels.ofKeys(this.keys);
				int pc = 0;
				int count = this.keys.length;
				while (pc < count) {
					CompiledForm item = this.items[pc];
					if (item == null) {
						pc++;
						continue;
					}
					this.ev.pollInterrupt();
					try {
						int jump = item.statement(env, null, statementExitTo, labels);
						pc = jump == NO_JUMP ? pc + 1 : jump + 1;
					}
					catch (LispEvaluator.GoSignal go) {
						int target = labels.indexOf(go.tag());
						if (target == NO_JUMP) {
							throw go;
						}
						pc = target + 1;
					}
				}
				return nil(env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		LispVal eval(Environment env) {
			return run(env, null);
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			run(env, owner != null ? owner : deferTo);
			return NO_JUMP;
		}

		@Override
		Object step(Environment env, Environment owner) {
			return run(env, owner);
		}

	}

	/**
	 * A {@code go}: in the tail of a statement of the {@code tagbody} that has its label,
	 * the label's index; anywhere else the thrown jump the walk throws.
	 */
	static final class Go extends CompiledForm {

		private final String key;

		private final boolean answered;

		Go(String key, boolean answered) {
			super(null);
			this.key = key;
			this.answered = answered;
		}

		@Override
		LispVal eval(Environment env) {
			throw new LispEvaluator.GoSignal(this.key);
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			if (this.answered && labels != null) {
				int target = labels.indexOf(this.key);
				if (target != NO_JUMP) {
					return target;
				}
			}
			throw new LispEvaluator.GoSignal(this.key);
		}

	}

	/**
	 * A defstruct accessor's checked read or store ({@code %obj-ref}/{@code %obj-set}
	 * with a failure form): the slot of an instance, the failure form, in the tail, for
	 * anything else.
	 */
	static final class SlotAccess extends CompiledForm {

		private final String name;

		private final CompiledForm object;

		private final LispVal index;

		private final @Nullable CompiledForm value;

		private final CompiledForm failure;

		SlotAccess(String name, CompiledForm object, LispVal index, @Nullable CompiledForm value, CompiledForm failure,
				@Nullable LocatedCons located) {
			super(located);
			this.name = name;
			this.object = object;
			this.index = index;
			this.value = value;
			this.failure = failure;
		}

		/**
		 * The instance the object evaluates to, its value evaluated after it, or null.
		 */
		private @Nullable LispInstance instance(Environment env, LispVal[] stored) {
			LispVal target = this.object.eval(env);
			stored[0] = this.value == null ? LispNil.INSTANCE : this.value.eval(env);
			return target instanceof LispInstance inst ? inst : null;
		}

		private LispVal access(LispInstance inst, LispVal stored, Environment env) {
			int slot = LispEvaluator.requireSlotIndex(this.name, inst, this.index);
			if (this.value != null) {
				inst.setSlot(slot, stored);
			}
			env.clearSpill();
			return this.value != null ? stored : inst.slot(slot);
		}

		@Override
		LispVal eval(Environment env) {
			try {
				LispVal[] stored = new LispVal[1];
				LispInstance inst = instance(env, stored);
				return inst == null ? this.failure.eval(env) : access(inst, stored[0], env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			try {
				LispVal[] stored = new LispVal[1];
				LispInstance inst = instance(env, stored);
				if (inst == null) {
					return this.failure.statement(env, owner, deferTo, labels);
				}
				access(inst, stored[0], env);
				return NO_JUMP;
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			LispVal[] stored = new LispVal[1];
			LispInstance inst = instance(env, stored);
			return inst == null ? this.failure : access(inst, stored[0], env);
		}

	}

	/** The arguments of a call, evaluated in turn, one value each. */
	static List<LispVal> arguments(CompiledForm[] args, Environment env) {
		int count = args.length;
		LispVal[] values = new LispVal[count];
		for (int i = 0; i < count; i++) {
			values[i] = args[i].eval(env);
		}
		env.clearSpill();
		return new ArgList(values);
	}

	/**
	 * A call of a global function the walk classified an ordinary call
	 * ({@code FunctionCell#wasCalledOrdinarily}): the name's binding read from its cell,
	 * the arguments evaluated, the function applied as the walk applies it. While the
	 * {@code callSiteEpoch} has not moved the classification holds; once it has, the name
	 * is classified again.
	 */
	static final class Call extends Guarded {

		private final String name;

		private final FunctionCell cell;

		private final CompiledForm[] args;

		private int epoch;

		Call(LispEvaluator ev, LispCons form, BodyCompiler.Layout layout, @Nullable LocatedCons located, String name,
				FunctionCell cell, CompiledForm[] args, int epoch) {
			super(ev, form, layout, located);
			this.name = name;
			this.cell = cell;
			this.args = args;
			this.epoch = epoch;
		}

		@Override
		boolean holds() {
			if (this.epoch == this.ev.callSiteEpoch()) {
				return true;
			}
			int now = this.ev.callSiteEpoch();
			if (!this.ev.stillOrdinaryCall(this.name)) {
				return false;
			}
			this.epoch = now;
			return true;
		}

		/**
		 * The binding the call reads, or null when the walk must take the form: no longer
		 * an ordinary call, or the name unbound, which the walk resolves (a library
		 * loaded on first use) or reports.
		 */
		private FunctionCell.@Nullable Binding binding() {
			if (!holds()) {
				return null;
			}
			FunctionCell.Binding binding = this.cell.binding();
			return binding.value() == null ? null : binding;
		}

		/** Whether a built-in is applied where the call stands, not in a frame. */
		private boolean appliedInPlace(LispVal function) {
			return function instanceof LispFunction builtIn && builtIn != this.ev.funcallBuiltin()
					&& builtIn != this.ev.applyBuiltin() && builtIn != this.ev.asyncRunBuiltin();
		}

		@Override
		LispVal eval(Environment env) {
			try {
				FunctionCell.Binding binding = binding();
				if (binding == null) {
					return fallback().eval(env);
				}
				LispVal function = java.util.Objects.requireNonNull(binding.value());
				List<LispVal> values = arguments(this.args, env);
				LispVal slot = slotRead(binding, function, values, env);
				if (slot != null) {
					return slot;
				}
				if (appliedInPlace(function)) {
					return this.ev.apply(function, values, env);
				}
				return CompiledFrames.call(this.ev, function, values, env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			FunctionCell.Binding binding = binding();
			if (binding == null) {
				return fallback();
			}
			LispVal function = java.util.Objects.requireNonNull(binding.value());
			List<LispVal> values = arguments(this.args, env);
			LispVal slot = slotRead(binding, function, values, env);
			if (slot != null) {
				return slot;
			}
			if (appliedInPlace(function)) {
				return this.ev.apply(function, values, env);
			}
			return new TailCall(function, values, env, this.located);
		}

		/**
		 * A slot reader on an instance: the slot its body would read, as the walk's frame
		 * answers it ({@code FunctionCell.Binding#slotRead}); null for any other call.
		 */
		private @Nullable LispVal slotRead(FunctionCell.Binding binding, LispVal function, List<LispVal> values,
				Environment env) {
			if (binding.slotRead() == FunctionCell.NO_SLOT_READ || !(function instanceof LispLambda)
					|| values.size() != 1 || !(values.get(0) instanceof LispInstance inst)) {
				return null;
			}
			int cell = binding.slotRead() < 0 ? inst.cellCount() + binding.slotRead() : binding.slotRead();
			if (cell < 0 || cell >= inst.cellCount()) {
				return null;
			}
			// every call of a closure is where an interrupted evaluation stops
			this.ev.pollInterrupt();
			LispVal value = inst.slot(cell);
			env.clearSpill();
			return value;
		}

	}

	/**
	 * A call whose head is a lambda form: the closure made, the arguments evaluated, the
	 * closure applied.
	 */
	static final class CallClosure extends CompiledForm {

		private final LispEvaluator ev;

		private final CompiledForm function;

		private final CompiledForm[] args;

		CallClosure(LispEvaluator ev, CompiledForm function, CompiledForm[] args, @Nullable LocatedCons located) {
			super(located);
			this.ev = ev;
			this.function = function;
			this.args = args;
		}

		@Override
		LispVal eval(Environment env) {
			try {
				LispVal closure = this.function.eval(env);
				return CompiledFrames.call(this.ev, closure, arguments(this.args, env), env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			LispVal closure = this.function.eval(env);
			return new TailCall(closure, arguments(this.args, env), env, this.located);
		}

	}

	/**
	 * A {@code lambda} form ({@code LispEvaluator.evalLambdaForm}): a closure over the
	 * current scope, sharing with every closure of this site the slot its body is
	 * compiled into, compiled against the scopes this site lies in.
	 */
	static final class Closure extends CompiledForm {

		private final LispEvaluator ev;

		private final LispEvaluator.LambdaSite site;

		Closure(LispEvaluator ev, LispEvaluator.LambdaSite site) {
			super(null);
			this.ev = ev;
			this.site = site;
		}

		@Override
		LispVal eval(Environment env) {
			LispLambda closure = this.ev.closure(this.site, env);
			env.clearSpill();
			return closure;
		}

		/**
		 * The shape and slot of the closures of a site compiled against a layout.
		 * @param shape the lambda form's walked site
		 * @param layout the scopes the site lies in
		 * @return the site this form makes closures of
		 */
		static LispEvaluator.LambdaSite within(LispEvaluator.LambdaSite shape, BodyCompiler.Layout layout) {
			BodyCode code = new BodyCode();
			code.code(new BodyCompiler.Enclosed(layout));
			return new LispEvaluator.LambdaSite(shape.shape(), code);
		}

	}

	/**
	 * A {@code #'name} ({@code LispEvaluator.functionValue}): the name's function value,
	 * looked up each time.
	 */
	static final class FunctionValue extends CompiledForm {

		private final LispEvaluator ev;

		private final String name;

		FunctionValue(LispEvaluator ev, String name) {
			super(null);
			this.ev = ev;
			this.name = name;
		}

		@Override
		LispVal eval(Environment env) {
			LispVal value = this.ev.functionValue(this.name);
			env.clearSpill();
			return value;
		}

	}

	/** What an {@link Expansion} holds while. */
	enum ExpansionKind {

		/**
		 * A built-in macro arm's, which is a function of the form while the methoded
		 * built-ins are what they were ({@code methodedEpoch}).
		 */
		BUILTIN,
		/** A user macro's, while the name is that macro ({@code callSiteEpoch}). */
		USER_MACRO,
		/**
		 * A walk's -- a {@code flet}, a {@code labels}, a slot macro -- while the macro
		 * table is as it was ({@code macroTableEpoch}).
		 */
		WALK

	}

	/**
	 * A macro call whose expansion the walk noted or memoized, running the expansion
	 * compiled while it holds ({@link ExpansionKind}).
	 */
	static final class Expansion extends Guarded {

		private final CompiledForm expansion;

		private final ExpansionKind kind;

		private final String name;

		private final @Nullable Object macro;

		private int epoch;

		Expansion(LispEvaluator ev, LispCons form, BodyCompiler.Layout layout, @Nullable LocatedCons located,
				CompiledForm expansion, ExpansionKind kind, String name, @Nullable Object macro, int epoch) {
			super(ev, form, layout, located);
			this.expansion = expansion;
			this.kind = kind;
			this.name = name;
			this.macro = macro;
			this.epoch = epoch;
		}

		@Override
		boolean holds() {
			switch (this.kind) {
				case BUILTIN:
					return this.epoch == this.ev.methodedEpoch();
				case WALK:
					return this.ev.walkExpansionsHold(this.epoch);
				default:
					if (this.epoch == this.ev.callSiteEpoch()) {
						return true;
					}
					int now = this.ev.callSiteEpoch();
					// what the walk's classification of the call asks before a user macro
					if (this.ev.userMacro(this.name) != this.macro || this.ev.methoded(this.name)) {
						return false;
					}
					this.epoch = now;
					return true;
			}
		}

		@Override
		LispVal eval(Environment env) {
			if (!holds()) {
				return fallback().eval(env);
			}
			try {
				return this.expansion.eval(env);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		int statement(Environment env, @Nullable Environment owner, @Nullable Environment deferTo,
				LispEvaluator.@Nullable TagbodyLabels labels) {
			if (!holds()) {
				return fallback().statement(env, owner, deferTo, labels);
			}
			try {
				return this.expansion.statement(env, owner, deferTo, labels);
			}
			catch (LispEvalException e) {
				throw passing(e, this.located, env);
			}
		}

		@Override
		Object step(Environment env, Environment owner) {
			return holds() ? this.expansion : fallback();
		}

	}

}
