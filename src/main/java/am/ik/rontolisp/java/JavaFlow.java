package am.ik.rontolisp.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.java.JavaClass.Field;
import am.ik.rontolisp.java.JavaClass.Method;
import am.ik.rontolisp.java.JavaSyntax.ArrayInitializer;
import am.ik.rontolisp.java.JavaSyntax.Assign;
import am.ik.rontolisp.java.JavaSyntax.Binary;
import am.ik.rontolisp.java.JavaSyntax.Block;
import am.ik.rontolisp.java.JavaSyntax.BooleanLiteral;
import am.ik.rontolisp.java.JavaSyntax.Break;
import am.ik.rontolisp.java.JavaSyntax.Call;
import am.ik.rontolisp.java.JavaSyntax.Cast;
import am.ik.rontolisp.java.JavaSyntax.Catch;
import am.ik.rontolisp.java.JavaSyntax.ClassDeclaration;
import am.ik.rontolisp.java.JavaSyntax.Conditional;
import am.ik.rontolisp.java.JavaSyntax.ConstructorCall;
import am.ik.rontolisp.java.JavaSyntax.Continue;
import am.ik.rontolisp.java.JavaSyntax.Declarator;
import am.ik.rontolisp.java.JavaSyntax.DoWhile;
import am.ik.rontolisp.java.JavaSyntax.Expression;
import am.ik.rontolisp.java.JavaSyntax.ExpressionStatement;
import am.ik.rontolisp.java.JavaSyntax.For;
import am.ik.rontolisp.java.JavaSyntax.ForEach;
import am.ik.rontolisp.java.JavaSyntax.If;
import am.ik.rontolisp.java.JavaSyntax.Index;
import am.ik.rontolisp.java.JavaSyntax.InstanceOf;
import am.ik.rontolisp.java.JavaSyntax.Labeled;
import am.ik.rontolisp.java.JavaSyntax.Lambda;
import am.ik.rontolisp.java.JavaSyntax.LocalVariable;
import am.ik.rontolisp.java.JavaSyntax.Name;
import am.ik.rontolisp.java.JavaSyntax.New;
import am.ik.rontolisp.java.JavaSyntax.NewArray;
import am.ik.rontolisp.java.JavaSyntax.Parameter;
import am.ik.rontolisp.java.JavaSyntax.Pattern;
import am.ik.rontolisp.java.JavaSyntax.Return;
import am.ik.rontolisp.java.JavaSyntax.Select;
import am.ik.rontolisp.java.JavaSyntax.Statement;
import am.ik.rontolisp.java.JavaSyntax.Switch;
import am.ik.rontolisp.java.JavaSyntax.SwitchCase;
import am.ik.rontolisp.java.JavaSyntax.Throw;
import am.ik.rontolisp.java.JavaSyntax.Try;
import am.ik.rontolisp.java.JavaSyntax.TypePattern;
import am.ik.rontolisp.java.JavaSyntax.Unary;
import am.ik.rontolisp.java.JavaSyntax.While;
import am.ik.rontolisp.java.JavaSyntax.Yield;
import org.jspecify.annotations.Nullable;

/**
 * What the language decides from the flow of a class's bodies once the lowering has typed
 * them: which statements are reachable and which complete normally (JLS 14.22), which
 * variables are definitely assigned and definitely unassigned (JLS 16), and which locals
 * are effectively final (JLS 4.12.4).
 *
 * <p>
 * The walk follows the syntax tree, as a compiler's flow analysis follows its attributed
 * tree; what only typing knows comes from the lowering ({@link Facts}): the variable each
 * name stands for, the value of each constant condition, the switches that are enhanced,
 * the locals each local or anonymous class reads. A problem is reported as a compiler
 * reports it, and of a class's problems the first of the kind a compiler reports first:
 * reachability, then assignment, then captures.
 */
final class JavaFlow {

	/** What the lowering found while it typed, which the walk reads. */
	static final class Facts {

		/**
		 * The variable each name, and each {@code this.f}, the lowering resolved stands
		 * for: a local's declaring node ({@link Declarator}, {@link Parameter},
		 * {@link TypePattern}, {@link ForEach}, {@link Catch}) or a {@link Field}.
		 */
		final Map<Expression, Object> variables = new IdentityHashMap<>();

		/** The value of each condition that is a constant expression. */
		final Map<Expression, Boolean> constants = new IdentityHashMap<>();

		/**
		 * The enhanced switches: a pattern, a {@code null} or a selector of a type a
		 * switch over constants does not take. Such a switch covers every value.
		 */
		final Set<Switch> enhanced = Collections.newSetFromMap(new IdentityHashMap<>());

		/** The locals of the methods around it each local or anonymous class reads. */
		final Map<ClassDeclaration, List<Capture>> captures = new IdentityHashMap<>();

		void captured(ClassDeclaration reader, Object variable, int pos) {
			this.captures.computeIfAbsent(reader, key -> new ArrayList<>()).add(new Capture(variable, pos));
		}

	}

	/**
	 * A local read from a local or an anonymous class.
	 *
	 * @param variable its declaring node
	 * @param pos the read
	 */
	record Capture(Object variable, int pos) {
	}

	/** What a compiler refuses, and where. */
	record Problem(int pos, String message) {
	}

	private static final int REACH = 0;

	private static final int ASSIGN = 1;

	private static final int CAPTURE = 2;

	/**
	 * A variable the walk follows. A blank one -- a local declared with no value, a blank
	 * final field -- has a bit in the sets of definitely assigned and definitely
	 * unassigned variables.
	 */
	private static final class Var {

		final String name;

		/** Where it is declared. */
		final int pos;

		final int bit;

		final boolean isFinal;

		final boolean isField;

		/** How many lambdas around its declaration. */
		final int depth;

		boolean effectivelyFinal = true;

		Var(String name, int pos, int bit, boolean isFinal, boolean isField, int depth) {
			this.name = name;
			this.pos = pos;
			this.bit = bit;
			this.isFinal = isFinal;
			this.isField = isField;
			this.depth = depth;
		}

	}

	/**
	 * A jump to a statement around the one it stands in, with what was assigned where it
	 * jumps.
	 *
	 * @param target the statement it leaves: a labeled statement, a loop, a switch; the
	 * body of the function a {@code return} leaves
	 * @param isContinue whether it is a {@code continue}
	 * @param pos where it stands
	 */
	private record Jump(Object target, boolean isContinue, BitSet inits, BitSet uninits, int pos) {
	}

	/**
	 * A statement a jump can leave.
	 *
	 * @param statement the statement
	 * @param label its label, for a labeled statement
	 * @param loop whether a {@code continue} goes to it
	 * @param breakable whether a {@code break} without a label leaves it
	 */
	private record Target(Object statement, @Nullable String label, boolean loop, boolean breakable) {
	}

	/** A local read or assigned inside a lambda or a class declared after it. */
	private record Reference(Var variable, int pos, boolean lambda) {
	}

	private final Facts facts;

	/** Whether only reachability is asked: nothing is reported. */
	private final boolean quiet;

	private final @Nullable Problem[] problems = new Problem[3];

	private int reported;

	private final Map<Object, Var> vars = new IdentityHashMap<>();

	private int bits;

	private BitSet inits = new BitSet();

	private BitSet uninits = new BitSet();

	private BitSet initsWhenTrue = new BitSet();

	private BitSet initsWhenFalse = new BitSet();

	private BitSet uninitsWhenTrue = new BitSet();

	private BitSet uninitsWhenFalse = new BitSet();

	/** The variables definitely unassigned everywhere in the {@code try} block around. */
	private BitSet uninitsTry = new BitSet();

	private boolean alive = true;

	private List<Jump> pending = new ArrayList<>();

	private final Deque<Target> targets = new ArrayDeque<>();

	/** What a {@code return} leaves: the body of the method or lambda being walked. */
	private Object function = new Object();

	/** Whether a loop's body is walked a second time, with what it assigns unassigned. */
	private boolean speculative;

	private int depth;

	private final List<Reference> references = new ArrayList<>();

	private JavaFlow(Facts facts, boolean quiet) {
		this.facts = facts;
		this.quiet = quiet;
	}

	/**
	 * Whether a statement can complete normally (JLS 14.22), standing where it is
	 * reached.
	 */
	static boolean completes(Statement statement, Facts facts) {
		JavaFlow flow = new JavaFlow(facts, true);
		flow.statement(statement);
		return flow.alive;
	}

	/**
	 * What a compiler refuses of the flow of a class's bodies, or {@code null}: its
	 * methods, its constructors with the initializers they run, its static
	 * initialization. A local or an anonymous class is a class of its own; what it reads
	 * of the locals around it is checked where it is declared.
	 */
	static @Nullable Problem check(JavaClass declaration, Facts facts) {
		ClassDeclaration written = Objects.requireNonNull(declaration.declaration);
		JavaFlow first = null;
		for (JavaFlow flow : List.of(statics(declaration, written, facts), instances(declaration, written, facts))) {
			first = earlier(first, flow);
		}
		for (Method method : declaration.methods) {
			JavaSyntax.Method body = method.declaration;
			if (method.isConstructor() || body == null || body.body() == null) {
				continue;
			}
			JavaFlow flow = new JavaFlow(facts, false);
			flow.method(method, Objects.requireNonNull(body.body()));
			first = earlier(first, flow);
		}
		return first == null ? null : first.first();
	}

	private static JavaFlow earlier(@Nullable JavaFlow first, JavaFlow flow) {
		if (first == null) {
			return flow;
		}
		for (int kind = REACH; kind <= CAPTURE; kind++) {
			if (first.problems[kind] != null) {
				return first;
			}
			if (flow.problems[kind] != null) {
				return flow;
			}
		}
		return first;
	}

	private @Nullable Problem first() {
		for (Problem problem : this.problems) {
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	private void problem(int kind, int pos, String message) {
		if (this.quiet) {
			return;
		}
		this.reported++;
		if (this.problems[kind] == null) {
			this.problems[kind] = new Problem(pos, message);
		}
	}

	// ---- bodies

	private void method(Method method, Block body) {
		this.function = body;
		parameters(Objects.requireNonNull(method.declaration).parameters());
		statement(body);
		if (method.returnType != JavaType.Primitive.VOID && this.alive) {
			problem(REACH, body.end(), "missing return statement");
		}
		effectivelyFinal();
	}

	/** The static initialization: the enum constants, the static fields and blocks. */
	private static JavaFlow statics(JavaClass declaration, ClassDeclaration written, Facts facts) {
		JavaFlow flow = new JavaFlow(facts, false);
		List<Var> blank = flow.blankFields(declaration, true);
		for (JavaSyntax.EnumConstant constant : written.constants()) {
			constant.arguments().forEach(flow::expression);
			if (constant.body() != null) {
				flow.declared(Objects.requireNonNull(constant.body()));
			}
		}
		flow.initializers(declaration, written, true);
		for (Var field : blank) {
			if (!flow.inits.get(field.bit)) {
				flow.problem(ASSIGN, field.pos, "variable " + field.name + " might not have been initialized");
			}
		}
		flow.effectivelyFinal();
		return flow;
	}

	/**
	 * The constructors, each with the instance initializers it runs; a class declaring
	 * none, its initializers alone.
	 */
	private static JavaFlow instances(JavaClass declaration, ClassDeclaration written, Facts facts) {
		JavaFlow first = null;
		boolean declares = false;
		for (Method constructor : declaration.methods) {
			JavaSyntax.Method body = constructor.declaration;
			if (!constructor.isConstructor() || body == null || body.body() == null) {
				continue;
			}
			declares = true;
			JavaFlow flow = new JavaFlow(facts, false);
			flow.constructor(declaration, written, body, Objects.requireNonNull(body.body()));
			first = earlier(first, flow);
		}
		if (declares) {
			return Objects.requireNonNull(first);
		}
		JavaFlow flow = new JavaFlow(facts, false);
		List<Var> blank = flow.blankFields(declaration, false);
		flow.initializers(declaration, written, false);
		for (Var field : blank) {
			if (!flow.inits.get(field.bit)) {
				flow.problem(ASSIGN, field.pos,
						"variable " + field.name + " not initialized in the default constructor");
			}
		}
		flow.effectivelyFinal();
		return flow;
	}

	private void constructor(JavaClass declaration, ClassDeclaration written, JavaSyntax.Method method, Block body) {
		this.function = body;
		List<Var> blank = blankFields(declaration, false);
		parameters(method.parameters());
		ConstructorCall explicit = null;
		for (Statement statement : body.statements()) {
			if (statement instanceof ExpressionStatement first && first.expression() instanceof ConstructorCall call) {
				explicit = call;
				break;
			}
		}
		if (explicit == null) {
			initializers(declaration, written, false);
		}
		for (Statement statement : body.statements()) {
			if (statement instanceof ExpressionStatement held && held.expression() == explicit) {
				if (!this.alive) {
					problem(REACH, statement.pos(), "unreachable statement");
					this.alive = true;
				}
				ConstructorCall call = Objects.requireNonNull(explicit);
				if (call.outer() != null) {
					expression(Objects.requireNonNull(call.outer()));
				}
				call.arguments().forEach(this::expression);
				if (call.onSuper()) {
					initializers(declaration, written, false);
				}
				else {
					// this(..) gave every field its value
					for (Var field : blank) {
						this.inits.set(field.bit);
						this.uninits.clear(field.bit);
					}
				}
			}
			else {
				statement(statement);
			}
		}
		if (this.alive) {
			unassigned(blank, this.inits, body.end());
		}
		for (Jump exit : this.pending) {
			if (exit.target() == body) {
				unassigned(blank, exit.inits(), exit.pos());
			}
		}
		effectivelyFinal();
	}

	private void unassigned(List<Var> fields, BitSet assigned, int pos) {
		for (Var field : fields) {
			if (!assigned.get(field.bit)) {
				problem(ASSIGN, pos, "variable " + field.name + " might not have been initialized");
			}
		}
	}

	/** The blank final fields of a class, static or not: each unassigned and a bit. */
	private List<Var> blankFields(JavaClass declaration, boolean isStatic) {
		List<Var> blank = new ArrayList<>();
		if (declaration.isRecord && !isStatic) {
			// a record's fields are its components, given their values by its canonical
			// constructor
			return blank;
		}
		for (Field field : declaration.fields) {
			if (field.isStatic == isStatic && field.isFinal && field.declaration != null
					&& field.declaration.initializer() == null) {
				Var variable = new Var(field.name, field.pos, this.bits++, true, true, 0);
				this.vars.put(field, variable);
				this.uninits.set(variable.bit);
				blank.add(variable);
			}
		}
		return blank;
	}

	/** A class's field initializers and initializer blocks, static or not, in order. */
	private void initializers(JavaClass declaration, ClassDeclaration written, boolean isStatic) {
		record Item(int pos, @Nullable Expression value, @Nullable Block block) {
		}
		List<Item> items = new ArrayList<>();
		for (Field field : declaration.fields) {
			if (field.isStatic == isStatic && field.declaration != null && field.declaration.initializer() != null) {
				items.add(new Item(field.pos, field.declaration.initializer(), null));
			}
		}
		for (JavaSyntax.Initializer block : written.initializers()) {
			if (block.isStatic() == isStatic) {
				items.add(new Item(block.pos(), null, block.body()));
			}
		}
		items.sort(Comparator.comparingInt(Item::pos));
		Object saved = this.function;
		for (Item item : items) {
			if (item.value() != null) {
				expression(Objects.requireNonNull(item.value()));
			}
			else {
				this.function = new Object();
				statement(Objects.requireNonNull(item.block()));
				this.function = saved;
			}
		}
	}

	private void parameters(List<Parameter> parameters) {
		for (Parameter parameter : parameters) {
			declare(parameter, parameter.name(), false, parameter.isFinal());
		}
	}

	/**
	 * Reports each local a lambda or a class reads that is neither final nor effectively.
	 */
	private void effectivelyFinal() {
		for (Reference reference : this.references) {
			Var variable = reference.variable();
			if (!variable.isFinal && !variable.effectivelyFinal) {
				problem(CAPTURE, reference.pos(),
						"local variables referenced from "
								+ (reference.lambda() ? "a lambda expression" : "an inner class")
								+ " must be final or effectively final");
			}
		}
	}

	// ---- variables

	private Var declare(Object key, String name, boolean blank, boolean isFinal) {
		Var known = this.vars.get(key);
		Var variable = known != null ? known : new Var(name, 0, blank ? this.bits++ : -1, isFinal, false, this.depth);
		this.vars.put(key, variable);
		if (variable.bit >= 0) {
			this.inits.clear(variable.bit);
			this.uninits.set(variable.bit);
		}
		return variable;
	}

	private @Nullable Var variable(Expression name) {
		Object key = this.facts.variables.get(name);
		return key == null ? null : this.vars.get(key);
	}

	/** A read of a variable: blank, it must be definitely assigned. */
	private void read(Var variable, int pos) {
		if (variable.bit >= 0 && !this.inits.get(variable.bit)) {
			problem(ASSIGN, pos, "variable " + variable.name + " might not have been initialized");
			this.inits.set(variable.bit);
		}
		captured(variable, pos);
	}

	private void captured(Var variable, int pos) {
		if (!variable.isField && variable.depth < this.depth) {
			this.references.add(new Reference(variable, pos, true));
		}
	}

	/** An assignment of a variable. */
	private void assign(Var variable, int pos) {
		captured(variable, pos);
		if (variable.bit < 0) {
			variable.effectivelyFinal = false;
			return;
		}
		if (!this.uninits.get(variable.bit)) {
			if (variable.isFinal) {
				problem(ASSIGN, pos, "variable " + variable.name
						+ (this.speculative ? " might be assigned in loop" : " might already have been assigned"));
			}
			else {
				variable.effectivelyFinal = false;
			}
		}
		else {
			if (!this.inits.get(variable.bit)) {
				this.uninitsTry.clear(variable.bit);
			}
			this.uninits.clear(variable.bit);
		}
		this.inits.set(variable.bit);
	}

	/**
	 * What a local or an anonymous class reads of the locals around it: each definitely
	 * assigned where the class is declared, and effectively final.
	 */
	private void declared(ClassDeclaration declaration) {
		for (Capture capture : this.facts.captures.getOrDefault(declaration, List.of())) {
			Var variable = this.vars.get(capture.variable());
			if (variable == null) {
				continue;
			}
			if (variable.bit >= 0 && !this.inits.get(variable.bit)) {
				problem(ASSIGN, capture.pos(), "variable " + variable.name + " might not have been initialized");
			}
			this.references.add(new Reference(variable, capture.pos(), false));
		}
	}

	private void markDead() {
		this.inits.set(0, this.bits);
		this.uninits.set(0, this.bits);
		this.alive = false;
	}

	private static BitSet copy(BitSet bits) {
		return (BitSet) bits.clone();
	}

	private static BitSet and(BitSet a, BitSet b) {
		BitSet both = copy(a);
		both.and(b);
		return both;
	}

	/** Whether a variable older than a loop is unassigned at its entry and not after. */
	private static boolean lost(BitSet entry, BitSet after, int bits) {
		BitSet lost = copy(entry);
		lost.andNot(after);
		int found = lost.nextSetBit(0);
		return found >= 0 && found < bits;
	}

	// ---- jumps

	private void jump(Object target, boolean isContinue, int pos) {
		if (this.alive) {
			this.pending.add(new Jump(target, isContinue, copy(this.inits), copy(this.uninits), pos));
		}
		markDead();
	}

	/**
	 * Takes the jumps to a statement into what is assigned where they land: whether there
	 * was one.
	 */
	private boolean resolve(Object target, boolean isContinue) {
		boolean found = false;
		List<Jump> left = new ArrayList<>();
		for (Jump jump : this.pending) {
			if (jump.target() == target && jump.isContinue() == isContinue) {
				this.inits.and(jump.inits());
				this.uninits.and(jump.uninits());
				found = true;
			}
			else {
				left.add(jump);
			}
		}
		this.pending = left;
		return found;
	}

	/** Resolves the breaks of a statement and puts back the jumps from around it. */
	private boolean resolveBreaks(Object target, List<Jump> around) {
		boolean found = resolve(target, false);
		List<Jump> all = new ArrayList<>(around);
		all.addAll(this.pending);
		this.pending = all;
		return found;
	}

	private @Nullable Object breakTarget(@Nullable String label) {
		for (Target target : this.targets) {
			if (label != null ? label.equals(target.label()) : target.breakable()) {
				return target.statement();
			}
		}
		return null;
	}

	private @Nullable Object continueTarget(@Nullable String label) {
		for (Target target : this.targets) {
			if (label == null && target.loop()) {
				return target.statement();
			}
			if (label != null && label.equals(target.label())) {
				Statement body = ((Labeled) target.statement()).body();
				while (body instanceof Labeled inner) {
					body = inner.body();
				}
				return body;
			}
		}
		return null;
	}

	// ---- statements

	private void statement(Statement statement) {
		if (!this.alive) {
			problem(REACH, statement.pos(), "unreachable statement");
			this.alive = true;
		}
		switch (statement) {
			case ExpressionStatement plain -> expression(plain.expression());
			case LocalVariable variable -> {
				for (Declarator declarator : variable.declarators()) {
					Expression initializer = declarator.initializer();
					if (initializer != null) {
						expression(initializer);
					}
					declare(declarator, declarator.name(), initializer == null, variable.isFinal());
				}
			}
			case JavaSyntax.LocalClass local -> declared(local.declaration());
			case Labeled labeled -> {
				List<Jump> around = this.pending;
				this.pending = new ArrayList<>();
				this.targets.push(new Target(labeled, labeled.label(), false, false));
				statement(labeled.body());
				this.targets.pop();
				this.alive |= resolveBreaks(labeled, around);
			}
			case If branch -> {
				condition(branch.condition());
				BitSet elseInits = this.initsWhenFalse;
				BitSet elseUninits = this.uninitsWhenFalse;
				this.inits = this.initsWhenTrue;
				this.uninits = this.uninitsWhenTrue;
				boolean before = this.alive;
				statement(branch.then());
				Statement otherwise = branch.otherwise();
				if (otherwise != null) {
					BitSet thenInits = this.inits;
					BitSet thenUninits = this.uninits;
					boolean afterThen = this.alive;
					this.inits = elseInits;
					this.uninits = elseUninits;
					this.alive = before;
					statement(otherwise);
					this.inits.and(thenInits);
					this.uninits.and(thenUninits);
					this.alive |= afterThen;
				}
				else {
					this.inits.and(elseInits);
					this.uninits.and(elseUninits);
					this.alive = before;
				}
			}
			case While loop -> whileLoop(loop);
			case DoWhile loop -> doLoop(loop);
			case For loop -> forLoop(loop);
			case ForEach loop -> forEach(loop);
			case Block block -> {
				for (Statement inner : block.statements()) {
					statement(inner);
				}
			}
			case Return exit -> {
				if (exit.value() != null) {
					expression(Objects.requireNonNull(exit.value()));
				}
				jump(this.function, false, exit.pos());
			}
			case Break exit -> {
				Object target = breakTarget(exit.label());
				if (target != null) {
					jump(target, false, exit.pos());
				}
				else {
					markDead();
				}
			}
			case Continue exit -> {
				Object target = continueTarget(exit.label());
				if (target != null) {
					jump(target, true, exit.pos());
				}
				else {
					markDead();
				}
			}
			case Yield exit -> {
				expression(exit.value());
				Object target = null;
				for (Target candidate : this.targets) {
					if (candidate.statement() instanceof Switch block && !candidate.breakable()) {
						target = block;
						break;
					}
				}
				if (target != null) {
					jump(target, false, exit.pos());
				}
				else {
					markDead();
				}
			}
			case Throw exit -> {
				expression(exit.value());
				markDead();
			}
			case Try attempt -> attempt(attempt);
			case JavaSyntax.Synchronized held -> {
				if (held.lock() != null) {
					expression(Objects.requireNonNull(held.lock()));
				}
				statement(held.body());
			}
			case JavaSyntax.Close close -> {
			}
			case JavaSyntax.Empty empty -> {
			}
			case Switch block -> switchBlock(block, false);
		}
	}

	private @Nullable Boolean constant(@Nullable Expression condition) {
		if (condition == null) {
			return true;
		}
		if (condition instanceof BooleanLiteral literal) {
			return literal.value();
		}
		return this.facts.constants.get(condition);
	}

	private void whileLoop(While loop) {
		List<Jump> around = this.pending;
		this.pending = new ArrayList<>();
		this.targets.push(new Target(loop, null, true, true));
		boolean savedSpeculative = this.speculative;
		this.speculative = false;
		boolean before = this.alive;
		Boolean constant = constant(loop.condition());
		int errors = this.reported;
		int known = this.bits;
		BitSet entry = copy(this.uninits);
		BitSet skipInits = null;
		BitSet skipUninits = null;
		while (true) {
			condition(loop.condition());
			if (skipInits == null) {
				skipInits = this.initsWhenFalse;
				skipUninits = this.uninitsWhenFalse;
			}
			this.inits = this.initsWhenTrue;
			this.uninits = this.uninitsWhenTrue;
			this.alive = before && !Boolean.FALSE.equals(constant);
			statement(loop.body());
			resolve(loop, true);
			if (this.reported != errors || this.speculative || !lost(entry, this.uninits, known)) {
				break;
			}
			this.uninits = and(entry, this.uninits);
			this.speculative = true;
		}
		this.speculative = savedSpeculative;
		this.inits = Objects.requireNonNull(skipInits);
		this.uninits = Objects.requireNonNull(skipUninits);
		this.alive = before && !Boolean.TRUE.equals(constant);
		this.targets.pop();
		this.alive |= resolveBreaks(loop, around);
	}

	private void doLoop(DoWhile loop) {
		List<Jump> around = this.pending;
		this.pending = new ArrayList<>();
		this.targets.push(new Target(loop, null, true, true));
		boolean savedSpeculative = this.speculative;
		this.speculative = false;
		boolean before = this.alive;
		Boolean constant = constant(loop.condition());
		int errors = this.reported;
		BitSet skipInits = null;
		BitSet skipUninits = null;
		boolean bodyCompletes = false;
		while (true) {
			int known = this.bits;
			BitSet entry = copy(this.uninits);
			this.alive = before;
			statement(loop.body());
			boolean completes = this.alive;
			completes |= resolve(loop, true);
			this.alive = completes;
			condition(loop.condition());
			if (skipInits == null) {
				skipInits = this.initsWhenFalse;
				skipUninits = this.uninitsWhenFalse;
				bodyCompletes = completes;
			}
			if (this.reported != errors || this.speculative || !lost(entry, this.uninitsWhenTrue, known)) {
				break;
			}
			this.inits = this.initsWhenTrue;
			this.uninits = and(entry, this.uninitsWhenTrue);
			this.speculative = true;
		}
		this.speculative = savedSpeculative;
		this.inits = Objects.requireNonNull(skipInits);
		this.uninits = Objects.requireNonNull(skipUninits);
		this.alive = bodyCompletes && !Boolean.TRUE.equals(constant);
		this.targets.pop();
		this.alive |= resolveBreaks(loop, around);
	}

	private void forLoop(For loop) {
		for (Statement initializer : loop.initializers()) {
			statement(initializer);
		}
		List<Jump> around = this.pending;
		this.pending = new ArrayList<>();
		this.targets.push(new Target(loop, null, true, true));
		boolean savedSpeculative = this.speculative;
		this.speculative = false;
		boolean before = this.alive;
		Boolean constant = constant(loop.condition());
		int errors = this.reported;
		BitSet skipInits = null;
		BitSet skipUninits = null;
		while (true) {
			int known = this.bits;
			BitSet entry = copy(this.uninits);
			Expression condition = loop.condition();
			if (condition != null) {
				condition(condition);
				if (skipInits == null) {
					skipInits = this.initsWhenFalse;
					skipUninits = this.uninitsWhenFalse;
				}
				this.inits = this.initsWhenTrue;
				this.uninits = this.uninitsWhenTrue;
			}
			else if (skipInits == null) {
				// no condition is true: nothing leaves but a break
				skipInits = copy(this.inits);
				skipInits.set(0, this.bits);
				skipUninits = copy(this.uninits);
				skipUninits.set(0, this.bits);
			}
			this.alive = before && !Boolean.FALSE.equals(constant);
			statement(loop.body());
			resolve(loop, true);
			loop.updates().forEach(this::expression);
			if (this.reported != errors || this.speculative || !lost(entry, this.uninits, known)) {
				break;
			}
			this.uninits = and(entry, this.uninits);
			this.speculative = true;
		}
		this.speculative = savedSpeculative;
		this.inits = Objects.requireNonNull(skipInits);
		this.uninits = Objects.requireNonNull(skipUninits);
		this.alive = before && !Boolean.TRUE.equals(constant);
		this.targets.pop();
		this.alive |= resolveBreaks(loop, around);
	}

	private void forEach(ForEach loop) {
		expression(loop.iterable());
		BitSet startInits = copy(this.inits);
		BitSet startUninits = copy(this.uninits);
		declare(loop, loop.name(), false, false);
		List<Jump> around = this.pending;
		this.pending = new ArrayList<>();
		this.targets.push(new Target(loop, null, true, true));
		boolean savedSpeculative = this.speculative;
		this.speculative = false;
		boolean before = this.alive;
		int errors = this.reported;
		while (true) {
			int known = this.bits;
			BitSet entry = copy(this.uninits);
			this.alive = before;
			statement(loop.body());
			resolve(loop, true);
			if (this.reported != errors || this.speculative || !lost(entry, this.uninits, known)) {
				break;
			}
			this.uninits = and(entry, this.uninits);
			this.speculative = true;
		}
		this.speculative = savedSpeculative;
		this.inits = startInits;
		startUninits.and(this.uninits);
		this.uninits = startUninits;
		this.alive = before;
		this.targets.pop();
		this.alive |= resolveBreaks(loop, around);
	}

	private void attempt(Try attempt) {
		BitSet uninitsTryAround = copy(this.uninitsTry);
		List<Jump> around = this.pending;
		this.pending = new ArrayList<>();
		BitSet initsTry = copy(this.inits);
		this.uninitsTry = copy(this.uninits);
		boolean before = this.alive;
		statement(attempt.body());
		this.uninitsTry.and(this.uninits);
		BitSet initsEnd = copy(this.inits);
		BitSet uninitsEnd = copy(this.uninits);
		boolean aliveEnd = this.alive;
		BitSet uninitsCatch = copy(this.uninitsTry);
		for (Catch clause : attempt.catches()) {
			this.inits = copy(initsTry);
			this.uninits = copy(uninitsCatch);
			this.alive = before;
			declare(clause, clause.name(), false, false);
			statement(clause.body());
			initsEnd.and(this.inits);
			uninitsEnd.and(this.uninits);
			aliveEnd |= this.alive;
		}
		Block last = attempt.finallyBlock();
		if (last != null) {
			this.inits = copy(initsTry);
			this.uninits = copy(this.uninitsTry);
			List<Jump> exits = this.pending;
			this.pending = around;
			this.alive = before;
			statement(last);
			if (this.alive) {
				this.uninits.and(uninitsEnd);
				for (Jump exit : exits) {
					exit.inits().or(this.inits);
					exit.uninits().and(this.uninits);
					this.pending.add(exit);
				}
				this.inits.or(initsEnd);
				this.alive = aliveEnd;
			}
		}
		else {
			this.inits = initsEnd;
			this.uninits = uninitsEnd;
			List<Jump> exits = this.pending;
			this.pending = around;
			this.pending.addAll(exits);
			this.alive = aliveEnd;
		}
		this.uninitsTry.and(uninitsTryAround);
		this.uninitsTry.and(this.uninits);
	}

	/**
	 * A switch statement or expression. A case starts with what the selector left, a
	 * colon case also with what the one running into it left; what a switch that need not
	 * take every value leaves includes the selector's.
	 */
	private void switchBlock(Switch block, boolean isExpression) {
		List<Jump> around = this.pending;
		this.pending = new ArrayList<>();
		expression(block.selector());
		this.targets.push(new Target(block, null, false, !isExpression));
		boolean before = this.alive;
		BitSet initsSwitch = copy(this.inits);
		BitSet uninitsSwitch = copy(this.uninits);
		boolean hasDefault = false;
		for (SwitchCase written : block.cases()) {
			this.inits = copy(initsSwitch);
			this.uninits = and(this.uninits, uninitsSwitch);
			this.alive = before;
			for (Pattern pattern : written.patterns()) {
				pattern(pattern);
			}
			if (written.guard() != null) {
				condition(Objects.requireNonNull(written.guard()));
				this.inits = this.initsWhenTrue;
				this.uninits = this.uninitsWhenTrue;
			}
			hasDefault |= written.hasDefault();
			if (written.value() != null) {
				expression(Objects.requireNonNull(written.value()));
				jump(block, false, written.pos());
				continue;
			}
			for (Statement statement : written.body()) {
				statement(statement);
			}
			if (block.arrow() && this.alive) {
				if (isExpression) {
					Statement body = written.body().get(0);
					problem(REACH, body instanceof Block inner ? inner.end() : body.pos(),
							"switch rule completes without providing a value");
				}
				jump(block, false, written.pos());
			}
			for (Statement statement : written.body()) {
				if (statement instanceof LocalVariable variable) {
					for (Declarator declarator : variable.declarators()) {
						Var declared = this.vars.get(declarator);
						if (declared != null && declared.bit >= 0) {
							initsSwitch.clear(declared.bit);
							uninitsSwitch.set(declared.bit);
						}
					}
				}
			}
		}
		this.targets.pop();
		if (isExpression) {
			if (!block.arrow() && this.alive) {
				problem(REACH, block.end(), "switch expression completes without providing a value");
			}
			markDead();
			resolveBreaks(block, around);
			this.alive = before;
			return;
		}
		if (!hasDefault && !this.facts.enhanced.contains(block)) {
			// no case may be taken
			this.inits = initsSwitch;
			this.uninits = and(this.uninits, uninitsSwitch);
			this.alive = before;
		}
		this.alive |= resolveBreaks(block, around);
	}

	private void pattern(Pattern pattern) {
		switch (pattern) {
			case TypePattern written -> declare(written, written.name(), false, false);
			case JavaSyntax.RecordPattern written -> written.components().forEach(this::pattern);
		}
	}

	// ---- expressions

	/**
	 * Walks a condition, leaving what is assigned when it is true and when it is false.
	 */
	private void condition(Expression expression) {
		Boolean constant = constant(expression);
		if (constant != null) {
			// a constant expression assigns nothing and reads no blank variable; the
			// branch it never takes is vacuous
			BitSet all = copy(this.inits);
			all.set(0, this.bits);
			BitSet allUninits = copy(this.uninits);
			allUninits.set(0, this.bits);
			this.initsWhenTrue = constant ? copy(this.inits) : all;
			this.uninitsWhenTrue = constant ? copy(this.uninits) : allUninits;
			this.initsWhenFalse = constant ? copy(all) : copy(this.inits);
			this.uninitsWhenFalse = constant ? copy(allUninits) : copy(this.uninits);
			return;
		}
		switch (expression) {
			case Binary binary when binary.operator().equals("&&") || binary.operator().equals("||") -> {
				boolean and = binary.operator().equals("&&");
				condition(binary.left());
				BitSet shortInits = and ? this.initsWhenFalse : this.initsWhenTrue;
				BitSet shortUninits = and ? this.uninitsWhenFalse : this.uninitsWhenTrue;
				this.inits = and ? this.initsWhenTrue : this.initsWhenFalse;
				this.uninits = and ? this.uninitsWhenTrue : this.uninitsWhenFalse;
				condition(binary.right());
				if (and) {
					this.initsWhenFalse.and(shortInits);
					this.uninitsWhenFalse.and(shortUninits);
				}
				else {
					this.initsWhenTrue.and(shortInits);
					this.uninitsWhenTrue.and(shortUninits);
				}
			}
			case Unary unary when unary.operator().equals("!") -> {
				condition(unary.operand());
				BitSet swap = this.initsWhenTrue;
				this.initsWhenTrue = this.initsWhenFalse;
				this.initsWhenFalse = swap;
				swap = this.uninitsWhenTrue;
				this.uninitsWhenTrue = this.uninitsWhenFalse;
				this.uninitsWhenFalse = swap;
			}
			case Conditional conditional -> {
				condition(conditional.condition());
				BitSet falseInits = this.initsWhenFalse;
				BitSet falseUninits = this.uninitsWhenFalse;
				this.inits = this.initsWhenTrue;
				this.uninits = this.uninitsWhenTrue;
				condition(conditional.whenTrue());
				BitSet trueTrue = this.initsWhenTrue;
				BitSet trueFalse = this.initsWhenFalse;
				BitSet trueTrueUninits = this.uninitsWhenTrue;
				BitSet trueFalseUninits = this.uninitsWhenFalse;
				this.inits = falseInits;
				this.uninits = falseUninits;
				condition(conditional.whenFalse());
				this.initsWhenTrue.and(trueTrue);
				this.initsWhenFalse.and(trueFalse);
				this.uninitsWhenTrue.and(trueTrueUninits);
				this.uninitsWhenFalse.and(trueFalseUninits);
			}
			default -> {
				expression(expression);
				this.initsWhenTrue = copy(this.inits);
				this.initsWhenFalse = copy(this.inits);
				this.uninitsWhenTrue = copy(this.uninits);
				this.uninitsWhenFalse = copy(this.uninits);
			}
		}
	}

	/** Walks a condition where its value is used as any value. */
	private void merged(Expression expression) {
		condition(expression);
		this.inits = and(this.initsWhenTrue, this.initsWhenFalse);
		this.uninits = and(this.uninitsWhenTrue, this.uninitsWhenFalse);
	}

	private void expression(Expression expression) {
		switch (expression) {
			case Name name -> {
				Var variable = variable(name);
				if (variable != null) {
					read(variable, name.pos());
				}
			}
			case Select select -> {
				Var variable = variable(select);
				if (variable != null) {
					read(variable, select.pos());
				}
				else {
					expression(select.target());
				}
			}
			case Call call -> {
				if (call.target() != null) {
					expression(Objects.requireNonNull(call.target()));
				}
				call.arguments().forEach(this::expression);
			}
			case ConstructorCall call -> {
				if (call.outer() != null) {
					expression(Objects.requireNonNull(call.outer()));
				}
				call.arguments().forEach(this::expression);
			}
			case New creation -> {
				if (creation.outer() != null) {
					expression(Objects.requireNonNull(creation.outer()));
				}
				creation.arguments().forEach(this::expression);
				if (creation.body() != null) {
					declared(Objects.requireNonNull(creation.body()));
				}
			}
			case NewArray creation -> {
				creation.lengths().forEach(this::expression);
				if (creation.initializer() != null) {
					expression(Objects.requireNonNull(creation.initializer()));
				}
			}
			case ArrayInitializer initializer -> initializer.elements().forEach(this::expression);
			case Index index -> {
				expression(index.array());
				expression(index.index());
			}
			case Unary unary -> {
				if (unary.operator().equals("!")) {
					merged(unary);
				}
				else if (unary.operator().equals("++") || unary.operator().equals("--")) {
					expression(unary.operand());
					Var variable = variable(unary.operand());
					if (variable != null) {
						assign(variable, unary.operand().pos());
					}
				}
				else {
					expression(unary.operand());
				}
			}
			case Binary binary -> {
				if (binary.operator().equals("&&") || binary.operator().equals("||")) {
					merged(binary);
				}
				else {
					expression(binary.left());
					expression(binary.right());
				}
			}
			case Assign assignment -> {
				Var variable = variable(assignment.target());
				if (variable == null || !assignment.operator().equals("=")) {
					expression(assignment.target());
				}
				expression(assignment.value());
				if (variable != null) {
					assign(variable, assignment.target().pos());
				}
			}
			case Conditional conditional -> {
				condition(conditional.condition());
				BitSet falseInits = this.initsWhenFalse;
				BitSet falseUninits = this.uninitsWhenFalse;
				this.inits = this.initsWhenTrue;
				this.uninits = this.uninitsWhenTrue;
				expression(conditional.whenTrue());
				BitSet trueInits = this.inits;
				BitSet trueUninits = this.uninits;
				this.inits = falseInits;
				this.uninits = falseUninits;
				expression(conditional.whenFalse());
				this.inits.and(trueInits);
				this.uninits.and(trueUninits);
			}
			case Cast cast -> expression(cast.operand());
			case InstanceOf test -> {
				expression(test.operand());
				if (test.pattern() != null) {
					pattern(Objects.requireNonNull(test.pattern()));
				}
			}
			case Lambda lambda -> lambda(lambda);
			case JavaSyntax.MethodReference reference -> {
				if (reference.target() != null) {
					expression(Objects.requireNonNull(reference.target()));
				}
			}
			case JavaSyntax.Echo echo -> {
				if (echo.value() != null) {
					expression(Objects.requireNonNull(echo.value()));
				}
			}
			case Switch block -> switchBlock(block, true);
			default -> {
				// a literal, this, a lowered value
			}
		}
	}

	/**
	 * A lambda's body: what is assigned before it is assigned in it, nothing around it is
	 * unassigned, and what it assigns stays in it.
	 */
	private void lambda(Lambda lambda) {
		BitSet savedInits = copy(this.inits);
		BitSet savedUninits = copy(this.uninits);
		BitSet savedTry = copy(this.uninitsTry);
		boolean savedAlive = this.alive;
		List<Jump> savedPending = this.pending;
		Object savedFunction = this.function;
		boolean savedSpeculative = this.speculative;
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		this.targets.clear();
		this.pending = new ArrayList<>();
		this.uninits.clear(0, this.bits);
		this.alive = true;
		this.speculative = false;
		this.depth++;
		this.function = lambda;
		parameters(lambda.parameters());
		if (lambda.expression() != null) {
			expression(Objects.requireNonNull(lambda.expression()));
		}
		else {
			statement(Objects.requireNonNull(lambda.block()));
		}
		this.depth--;
		this.function = savedFunction;
		this.speculative = savedSpeculative;
		this.targets.addAll(savedTargets);
		this.pending = savedPending;
		this.alive = savedAlive;
		this.inits = savedInits;
		this.uninits = savedUninits;
		this.uninitsTry = savedTry;
	}

}
