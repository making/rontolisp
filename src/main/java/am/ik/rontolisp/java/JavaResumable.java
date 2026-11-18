package am.ik.rontolisp.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

import static am.ik.rontolisp.java.JavaForms.NIL;
import static am.ik.rontolisp.java.JavaForms.cl;
import static am.ik.rontolisp.java.JavaForms.integer;
import static am.ik.rontolisp.java.JavaForms.list;
import static am.ik.rontolisp.java.JavaForms.runtime;

/**
 * The threads of a program where the target has one stack: the functions of the lowered
 * program from which a thread can wait are made RESUMABLE -- an {@code async-defun}, a
 * call of one an {@code await} -- so a thread that cannot go on is a suspended body,
 * which the run time resumes when it can ({@code eval/java.lisp}, "threads";
 * {@code .kb/java-frontend.md}, "Threads"). Run over the forms of a program that starts
 * threads, for such a target alone: where the host has threads the forms stay as they
 * are.
 *
 * <p>
 * <strong>What is resumable.</strong> A function is when it reaches a suspension: a
 * sleep, a yield, a wait, the entry of a monitor a suspended thread may hold, the turn a
 * loop gives the other threads. A fixed point over the calls of the program: a call by
 * name, a dispatcher's arms, and a call through a function value, which reaches the
 * lambdas made for the interface it is called through ({@link Facts}: the lowering knows
 * both, the forms neither).
 *
 * <p>
 * <strong>Which monitors a suspended thread can hold.</strong> A {@code synchronized}
 * region is HELD ACROSS A SUSPENSION when its body reaches one that does not give the
 * region's own monitor up: a wait on its own lock does (handed up the calls as long as
 * the object waited on is a parameter), anything else does not. The entry of a monitor
 * MAY BLOCK when its lock may be the lock of a held region ({@link Lock#mayBe}), and such
 * an entry is a suspension. Every other entry stays the plain one: no suspended thread
 * can hold its monitor. A loop's turn is given only by a thread holding no monitor (the
 * run time's test), so it holds no region.
 *
 * <p>
 * <strong>The turn of a loop.</strong> Threads here take turns where they suspend, so a
 * loop that only another thread can end -- one reading a {@code volatile} flag, an
 * atomic, the interrupt -- would turn for ever. A loop outside every region of its
 * function whose test reaches a synchronization action (a {@code volatile} access, a
 * monitor's entry, the interrupt's test) through any call, or whose body reaches one
 * through calls by name, counts its turns and lets the other threads go on once in so
 * many. The test of an enhanced {@code for} is no such test: it asks an iterator for
 * more, and through a dispatcher that reaches every iterator a program makes, which would
 * give every walk of a collection a turn.
 *
 * <p>
 * <strong>The forms.</strong> An {@code await} compiles only where the operand stack is
 * empty, so a call with a suspension among its operands binds the operands up to it
 * first; a {@code handler-case} clause and an {@code unwind-protect} cleanup that suspend
 * run after the form they belong to, since neither can hold an await. A function whose
 * suspensions are all calls in tail position stays a plain one and answers the future of
 * the call (a dispatcher). A function the run time calls -- one named as a value, a
 * lambda the lowering wrote for it -- is called where nothing awaits: a resumable one
 * answers there at once or the program is refused by name.
 */
final class JavaResumable {

	/** What the lowering knows of a form that the form does not say. */
	static final class Facts {

		/** A lambda's form to the interface it was made for. */
		final Map<LispVal, JavaClass> lambdas = new IdentityHashMap<>();

		/**
		 * A {@code funcall} of a function value to the interface it is called through.
		 */
		final Map<LispVal, Called> calls = new IdentityHashMap<>();

		/** A monitor's entry to what is known of its lock. */
		final Map<LispVal, Lock> locks = new IdentityHashMap<>();

		/** The names of the dispatchers. */
		final Set<String> dispatchers = new HashSet<>();

		/**
		 * The loops of the enhanced {@code for}: their test asks an iterator for more.
		 */
		final Set<LispVal> iterations = Collections.newSetFromMap(new IdentityHashMap<>());

	}

	/**
	 * The interface a function value is called through.
	 *
	 * @param receiver the interface
	 * @param exact whether the lambdas reached are the ones made for that very interface,
	 * else the ones made for it or an interface under it
	 */
	record Called(JavaClass receiver, boolean exact) {
	}

	/**
	 * What is known of the object a monitor is entered on.
	 *
	 * @param type its class: the static type of the expression, or the class itself for a
	 * class's own lock
	 * @param exact whether the object is of that very class
	 * @param literal whether the lock is the class's own (a {@code static synchronized}
	 * method's, a class literal's)
	 */
	record Lock(JavaClass type, boolean exact, boolean literal) {

		/** Whether two locks may be one object; a lock nothing is known of is any. */
		static boolean mayBe(@Nullable Lock one, @Nullable Lock other) {
			if (one == null || other == null) {
				return true;
			}
			if (one.literal || other.literal) {
				return one.literal && other.literal && one.type == other.type;
			}
			if (one.exact && other.exact) {
				return one.type == other.type;
			}
			if (one.exact) {
				return one.type.isSubclassOf(other.type);
			}
			if (other.exact) {
				return other.type.isSubclassOf(one.type);
			}
			return one.type.isInterface || other.type.isInterface || one.type.isSubclassOf(other.type)
					|| other.type.isSubclassOf(one.type);
		}

	}

	/**
	 * What the analysis found, for a test and the notes: the functions of the program
	 * written in Java, the ones made resumable, the regions and the ones a suspended
	 * thread can hold.
	 *
	 * @param functions the named functions
	 * @param resumable the named functions a thread can be suspended in
	 * @param lambdas the lambdas
	 * @param resumableLambdas the lambdas a thread can be suspended in
	 * @param regions the {@code synchronized} regions
	 * @param held the names of the functions holding a region across a suspension, one
	 * per region
	 * @param blocking the entries that may block
	 */
	record Census(int functions, List<String> resumable, int lambdas, int resumableLambdas, int regions,
			List<String> held, int blocking) {
	}

	private static final String AWAIT = "RONTOLISP:AWAIT";

	private static final String ASYNC_DEFUN = "RONTOLISP:ASYNC-DEFUN";

	private static final String ASYNC_LAMBDA = "RONTOLISP:ASYNC-LAMBDA";

	private static final String WITH_MUTEX = "RONTOLISP:WITH-MUTEX";

	private static final String NPE = runtime("npe").name();

	private static final String ENTER = runtime("monitor-enter").name();

	private static final String EXIT = runtime("monitor-exit").name();

	private static final String WAIT = runtime("monitor-wait").name();

	private static final String SLEEP = runtime("thread-sleep").name();

	private static final String YIELD = runtime("thread-yield").name();

	private static final String INIT_BUSY = runtime("init-busy").name();

	private static final String REPLY = runtime("http-reply").name();

	private static final String INTERRUPTED = runtime("thread-interrupted").name();

	private static final String VOLATILE = runtime("volatile").name();

	private static final String THREAD_START = runtime("thread-start").name();

	private static final String THREAD_MAIN = runtime("thread-main").name();

	private static final LispSymbol SLICE = runtime("slice");

	private static final LispSymbol NOW = runtime("now");

	/**
	 * The functions of the run time a thread waits in, to the ones it is suspended in.
	 */
	private static final Map<String, LispSymbol> SUSPENDING = Map.of(ENTER, runtime("suspending-enter"), WAIT,
			runtime("suspending-wait"), SLEEP, runtime("suspending-sleep"), YIELD, runtime("suspending-yield"),
			INIT_BUSY, runtime("suspending-init"), REPLY, runtime("suspending-http-reply"));

	/**
	 * The operators a form with a suspension among its operands is not read as a call of:
	 * the lowering emits none of them, and one that came to be emitted has its own
	 * evaluation order to be written here first.
	 */
	private static final Set<String> UNREAD = Set.of("TAGBODY", "GO", "CATCH", "THROW", "FLET", "LABELS", "MACROLET",
			"SYMBOL-MACROLET", "MULTIPLE-VALUE-BIND", "MULTIPLE-VALUE-CALL", "MULTIPLE-VALUE-PROG1", "PROGV",
			"HANDLER-BIND", "RESTART-CASE", "IGNORE-ERRORS", "DOTIMES", "DOLIST", "DO", "DO*", "LOOP", "CASE", "ECASE",
			"TYPECASE", "ETYPECASE", "WHEN", "UNLESS", "PROG", "PROG*", "PROG2", "RETURN", "PSETQ", "INCF", "DECF",
			"PUSH", "POP", "THE", "LOCALLY", "EVAL-WHEN", "DESTRUCTURING-BIND", "DEFUN", "DEFVAR", "DEFSTRUCT",
			"%REPORTING-UNCAUGHT");

	/**
	 * The operators whose forms hold an await where they stand: {@link #spine} reads
	 * each.
	 */
	private static final Set<String> SPINE = Set.of("PROGN", "IF", "AND", "OR", "PROG1", "BLOCK", "RETURN-FROM", "SETQ",
			"LET", "LET*", "COND", "WHILE", "HANDLER-CASE", "UNWIND-PROTECT", "SETF");

	/**
	 * A function of the program: a {@code defun}, a lambda, or what runs at top level.
	 */
	private static final class Node {

		final @Nullable String name;

		final List<String> parameters;

		final List<LispVal> body;

		/**
		 * The interface a lambda was made for; {@code null} for one the run time calls.
		 */
		@Nullable JavaClass target;

		final boolean lambda;

		/** Whether it reaches a suspension that keeps every monitor of its caller. */
		boolean general;

		/** The parameters whose monitor alone a suspension it reaches gives up. */
		final BitSet waits = new BitSet();

		/**
		 * Whether it reaches the turn of a loop: a suspension of a thread holding none.
		 */
		boolean turns;

		/** Whether it reaches a synchronization action. */
		boolean syncs;

		/** Whether it reaches one through calls by name alone. */
		boolean syncsByName;

		/** Whether the run time awaits what it answers. */
		boolean awaited;

		/** Whether it stays a plain function answering the future of a call. */
		boolean forwards;

		/** What first made it resumable, for the one reading the analysis. */
		@Nullable String why;

		/** The function a lambda is written in. */
		@Nullable Node around;

		/** The lambdas each parameter may be, as far as the calls tell. */
		final List<Flow> flows = new ArrayList<>();

		/**
		 * A dispatcher's calls of its receiver as a function, and what it reaches without
		 * them: a call of the dispatcher that knows the lambdas its receiver may be reads
		 * those in their place.
		 */
		final List<LispVal> received = new ArrayList<>();

		@Nullable Node arms;

		Node(@Nullable String name, List<String> parameters, List<LispVal> body, boolean lambda) {
			this.name = name;
			this.parameters = parameters;
			this.body = body;
			this.lambda = lambda;
			parameters.forEach(parameter -> this.flows.add(new Flow()));
		}

		boolean resumable() {
			return this.general || !this.waits.isEmpty() || this.turns;
		}

	}

	/**
	 * The lambdas a value may be: the ones handed to a parameter where its function is
	 * called, or any lambda of its interface once a call hands it something else.
	 */
	private static final class Flow {

		static final Flow ANY = new Flow();
		static {
			ANY.any = true;
		}

		boolean any;

		final Set<Node> lambdas = Collections.newSetFromMap(new IdentityHashMap<>());

		boolean add(Flow other) {
			if (this.any) {
				return false;
			}
			if (other.any) {
				this.any = true;
				return true;
			}
			return this.lambdas.addAll(other.lambdas);
		}

	}

	/** A region being walked: its entry and the lock, without the test of a null. */
	private record Region(LispVal entry, LispVal lock) {
	}

	private final Facts facts;

	private final Function<String, RuntimeException> refused;

	private final Map<String, Node> named = new LinkedHashMap<>();

	private final Map<LispVal, Node> lambdas = new IdentityHashMap<>();

	private final List<Node> nodes = new ArrayList<>();

	/** The entries of the regions a suspended thread can hold. */
	private final Set<LispVal> held = Collections.newSetFromMap(new IdentityHashMap<>());

	private final Map<LispVal, Node> regions = new IdentityHashMap<>();

	/** What a held region was first found held for. */
	private final Map<LispVal, String> reasons = new IdentityHashMap<>();

	/** The entries that may block. */
	private final Set<LispVal> blocking = Collections.newSetFromMap(new IdentityHashMap<>());

	/** The calls awaited. */
	private final Set<LispVal> suspensions = Collections.newSetFromMap(new IdentityHashMap<>());

	/** The loops that count their turns. */
	private final Set<LispVal> turning = Collections.newSetFromMap(new IdentityHashMap<>());

	private final Map<LispVal, Boolean> needing = new IdentityHashMap<>();

	private boolean changed;

	/** Whether what is walked is a dispatcher's call of its receiver as a function. */
	private boolean receiving;

	/** Whether the walk is the last one, which records what the rewrite reads. */
	private boolean marking;

	private Node current;

	private final Deque<Region> around = new ArrayDeque<>();

	private int temporaries;

	private JavaResumable(Facts facts, Function<String, RuntimeException> refused) {
		this.facts = facts;
		this.refused = refused;
		this.current = new Node(null, List.of(), List.of(), false);
	}

	/**
	 * Makes the functions of a program that starts threads resumable.
	 * @param forms the lowered program
	 * @param facts what the lowering knows of its forms
	 * @param refused the error of a program this cannot lower, from what it is said of
	 * @return the program, its resumable functions rewritten
	 */
	static List<LispVal> rewrite(List<LispVal> forms, Facts facts, Function<String, RuntimeException> refused,
			java.util.function.@Nullable Consumer<Census> counted) {
		JavaResumable resumable = new JavaResumable(facts, refused);
		resumable.analyse(forms);
		if (counted != null) {
			counted.accept(resumable.census());
		}
		List<LispVal> rewritten = new ArrayList<>(forms.size());
		for (LispVal form : forms) {
			rewritten.add(resumable.top(form));
		}
		return List.copyOf(rewritten);
	}

	private Census census() {
		JavaResumable resumable = this;
		List<String> names = new ArrayList<>();
		int lambdas = 0;
		int resumableLambdas = 0;
		for (Node node : resumable.nodes) {
			if (node.lambda) {
				lambdas++;
				resumableLambdas += node.resumable() ? 1 : 0;
			}
			else if (node.name != null && node.resumable()) {
				names.add(node.name + (node.forwards ? " -> " : ": ") + node.why);
			}
		}
		List<String> holding = new ArrayList<>();
		for (LispVal entry : resumable.held) {
			Node owner = resumable.regions.get(entry);
			holding.add((owner == null || owner.name == null ? "a lambda" : owner.name) + ": "
					+ resumable.reasons.get(entry));
		}
		Collections.sort(holding);
		return new Census(resumable.named.size(), List.copyOf(names), lambdas, resumableLambdas,
				resumable.regions.size(), List.copyOf(holding), resumable.blocking.size());
	}

	// ---- the forms

	private static @Nullable String head(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol symbol ? symbol.name() : null;
	}

	private static boolean is(LispVal form, String operator) {
		return operator.equals(head(form));
	}

	private static List<LispVal> elements(LispVal form) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			elements.add(cons.car());
			rest = cons.cdr();
		}
		return elements;
	}

	/** The lambda a form is, written as itself or under {@code function}. */
	private static @Nullable LispVal lambdaOf(LispVal form) {
		if (is(form, "LAMBDA")) {
			return form;
		}
		if (is(form, "FUNCTION")) {
			List<LispVal> parts = elements(form);
			return parts.size() == 2 && is(parts.get(1), "LAMBDA") ? parts.get(1) : null;
		}
		return null;
	}

	/** The name a {@code (function name)} form names. */
	private static @Nullable String functionNamed(LispVal form) {
		if (is(form, "FUNCTION")) {
			List<LispVal> parts = elements(form);
			if (parts.size() == 2 && parts.get(1) instanceof LispSymbol symbol) {
				return symbol.name();
			}
		}
		return null;
	}

	/** The value under the test of a null the lowering puts around a receiver. */
	private static LispVal unwrap(LispVal form) {
		LispVal value = form;
		while (is(value, "OR")) {
			List<LispVal> parts = elements(value);
			if (parts.size() != 3 || !is(parts.get(2), NPE) || elements(parts.get(2)).size() != 1) {
				break;
			}
			value = parts.get(1);
		}
		return value;
	}

	/** Whether two forms are the same reading of a variable or of a slot of one. */
	private static boolean same(LispVal one, LispVal other) {
		if (one instanceof LispSymbol a && other instanceof LispSymbol b) {
			return a.name().equals(b.name());
		}
		if (one instanceof LispCons && other instanceof LispCons) {
			List<LispVal> a = elements(one);
			List<LispVal> b = elements(other);
			if (a.size() != 2 || b.size() != 2 || is(one, "QUOTE") != is(other, "QUOTE")) {
				return false;
			}
			return same(a.get(0), b.get(0)) && same(a.get(1), b.get(1));
		}
		return false;
	}

	/**
	 * The region a form is: {@code (let ((m (%java-monitor-enter lock))) (unwind-protect
	 * body (%java-monitor-exit m)))}, as the lowering writes one. Answers the entry, the
	 * body and the whole binding.
	 */
	private static LispVal @Nullable [] region(LispVal form) {
		if (!is(form, "LET")) {
			return null;
		}
		List<LispVal> parts = elements(form);
		if (parts.size() != 3 || !is(parts.get(2), "UNWIND-PROTECT")) {
			return null;
		}
		List<LispVal> bindings = elements(parts.get(1));
		if (bindings.size() != 1) {
			return null;
		}
		List<LispVal> binding = elements(bindings.get(0));
		if (binding.size() != 2 || !is(binding.get(1), ENTER)) {
			return null;
		}
		List<LispVal> guarded = elements(parts.get(2));
		if (guarded.size() != 3 || !is(guarded.get(2), EXIT)) {
			return null;
		}
		return new LispVal[] { binding.get(1), guarded.get(1) };
	}

	// ---- the analysis

	private void analyse(List<LispVal> forms) {
		Node top = new Node(null, List.of(), new ArrayList<>(), false);
		for (LispVal form : forms) {
			if (is(form, "DEFUN")) {
				List<LispVal> parts = elements(form);
				if (parts.get(1) instanceof LispSymbol name) {
					Node node = new Node(name.name(), names(parts.get(2)), parts.subList(3, parts.size()), false);
					this.named.put(name.name(), node);
					this.nodes.add(node);
					continue;
				}
			}
			top.body.add(form);
		}
		this.nodes.add(top);
		for (Node node : List.copyOf(this.nodes)) {
			if (node.name != null && this.facts.dispatchers.contains(node.name) && !node.parameters.isEmpty()) {
				node.arms = new Node(node.name, node.parameters, node.body, false);
			}
			node.body.forEach(form -> declare(form, node));
		}
		do {
			this.changed = false;
			for (Node node : this.nodes) {
				walk(node);
			}
		}
		while (this.changed);
		this.marking = true;
		for (Node node : this.nodes) {
			walk(node);
		}
		for (Node node : this.nodes) {
			node.forwards = node.resumable() && forwards(node);
		}
		if (top.resumable()) {
			throw this.refused.apply("a thread waiting where no method of the program runs");
		}
	}

	private static List<String> names(LispVal parameters) {
		List<String> names = new ArrayList<>();
		for (LispVal parameter : elements(parameters)) {
			names.add(parameter instanceof LispSymbol symbol ? symbol.name() : "");
		}
		return names;
	}

	/** Finds the lambdas of a form: each is a function of its own. */
	private void declare(LispVal form, Node around) {
		if (!(form instanceof LispCons) || is(form, "QUOTE")) {
			return;
		}
		LispVal lambda = lambdaOf(form);
		if (lambda != null) {
			if (!this.lambdas.containsKey(lambda)) {
				List<LispVal> parts = elements(lambda);
				Node node = new Node(null, names(parts.get(1)), parts.subList(2, parts.size()), true);
				node.target = this.facts.lambdas.get(lambda);
				node.around = around;
				this.lambdas.put(lambda, node);
				this.nodes.add(node);
				node.body.forEach(statement -> declare(statement, node));
			}
			return;
		}
		elements(form).forEach(part -> declare(part, around));
	}

	/**
	 * The lambdas a value may be: a lambda written there, a parameter of the function or
	 * of one it is written in as the calls hand it, anything else any lambda.
	 */
	private Flow flowOf(LispVal form) {
		LispVal value = unwrap(form);
		while (is(value, "LET") || is(value, "LET*") || is(value, "PROGN")) {
			// the bindings a method reference is made under
			List<LispVal> parts = elements(value);
			value = parts.get(parts.size() - 1);
		}
		LispVal lambda = lambdaOf(value);
		if (lambda == null && value instanceof LispCons) {
			// a lambda held by the structure telling its interface
			List<LispVal> parts = elements(value);
			String maker = head(value);
			if (parts.size() == 2 && maker != null && maker.startsWith("%make-") && maker.endsWith("%lambda")) {
				lambda = lambdaOf(parts.get(1));
			}
		}
		if (lambda != null) {
			Node made = this.lambdas.get(lambda);
			if (made == null) {
				return Flow.ANY;
			}
			Flow one = new Flow();
			one.lambdas.add(made);
			return one;
		}
		if (value instanceof LispSymbol symbol) {
			for (Node node = this.current; node != null; node = node.around) {
				int parameter = node.parameters.indexOf(symbol.name());
				if (parameter >= 0) {
					return node.flows.get(parameter);
				}
			}
		}
		return Flow.ANY;
	}

	/** What a call hands a function's parameters. */
	private void hands(Node callee, List<LispVal> arguments) {
		for (int i = 0; i < callee.flows.size() && i < arguments.size(); i++) {
			if (callee.flows.get(i).add(flowOf(arguments.get(i)))) {
				this.changed = true;
			}
		}
	}

	/** A parameter assigned is no longer what the calls handed it. */
	private void assigned(LispVal variable) {
		if (variable instanceof LispSymbol symbol) {
			for (Node node = this.current; node != null; node = node.around) {
				int parameter = node.parameters.indexOf(symbol.name());
				if (parameter >= 0) {
					if (node.flows.get(parameter).add(Flow.ANY)) {
						this.changed = true;
					}
					return;
				}
			}
		}
	}

	private void walk(Node node) {
		this.current = node;
		this.around.clear();
		node.body.forEach(this::visit);
	}

	private void visit(LispVal form) {
		if (!(form instanceof LispCons) || is(form, "QUOTE")) {
			return;
		}
		if (lambdaOf(form) != null) {
			return;
		}
		String operator = head(form);
		List<LispVal> parts = elements(form);
		LispVal[] region = region(form);
		if (region != null) {
			LispVal entry = region[0];
			LispVal lock = elements(entry).get(1);
			visit(lock);
			entered(entry);
			this.regions.put(entry, this.current);
			this.around.push(new Region(entry, unwrap(lock)));
			visit(region[1]);
			this.around.pop();
			return;
		}
		if (operator == null) {
			parts.forEach(this::visit);
			return;
		}
		if (operator.equals("FUNCTION")) {
			String name = functionNamed(form);
			Node value = name == null ? null : this.named.get(name);
			if (value != null) {
				for (Flow flow : value.flows) {
					this.changed |= flow.add(Flow.ANY);
				}
			}
			return;
		}
		if ((operator.equals("SETQ") || operator.equals("SETF")) && parts.size() > 1) {
			for (int i = 1; i < parts.size(); i += 2) {
				assigned(parts.get(i));
			}
		}
		if (operator.equals("WHILE")) {
			parts.subList(1, parts.size()).forEach(this::visit);
			if (this.around.isEmpty() && ((!this.facts.iterations.contains(form) && reaches(parts.get(1), true))
					|| parts.subList(2, parts.size()).stream().anyMatch(part -> reaches(part, false)))) {
				turn("a loop's turn");
				if (this.marking) {
					this.turning.add(form);
				}
			}
			return;
		}
		List<LispVal> operands = parts.subList(1, parts.size());
		switch (operator) {
			case "FUNCALL" -> {
				String name = operands.isEmpty() ? null : functionNamed(operands.get(0));
				if (name != null) {
					// a function called by its name
					operands.subList(1, operands.size()).forEach(this::visit);
					Node callee = this.named.get(name);
					if (callee != null) {
						calls(form, callee, operands.subList(1, operands.size()), true);
						hands(callee, operands.subList(1, operands.size()));
						if (this.marking && callee.resumable()) {
							// the lowering calls a function by its name; a function
							// named as a value is one the run time calls
							throw new IllegalStateException("a resumable function called through " + form.print());
						}
					}
					break;
				}
				operands.forEach(this::visit);
				Node dispatcher = this.current.arms == null ? null : this.current;
				if (dispatcher != null && function(operands.get(0)) instanceof LispSymbol receiver
						&& receiver.name().equals(dispatcher.parameters.get(0))) {
					// a dispatcher's call of its receiver: what a call of the dispatcher
					// that knows its receiver reads in its own way
					if (!dispatcher.received.contains(form)) {
						dispatcher.received.add(form);
					}
					this.receiving = true;
				}
				for (Node callee : called(form, flowOf(function(operands.get(0))))) {
					calls(form, callee, operands.subList(1, operands.size()), false);
					hands(callee, operands.subList(1, operands.size()));
				}
				this.receiving = false;
			}
			default -> {
				operands.forEach(this::visit);
				if (operator.equals(SLEEP) || operator.equals(YIELD) || operator.equals(REPLY)) {
					suspends(form);
					general(operator.equals(SLEEP) ? "sleeps"
							: operator.equals(YIELD) ? "yields" : "waits for a reply");
				}
				else if (operator.equals(WAIT)) {
					suspends(form);
					waits(operands.get(0), "waits");
				}
				else if (operator.equals(ENTER)) {
					entered(form);
				}
				else if (operator.equals(VOLATILE) || operator.equals(INTERRUPTED)) {
					synchronizes(true);
				}
				else if (operator.equals(INIT_BUSY)) {
					// the wait for a class another thread initializes: one there is
					// only when its initialization suspends, which is this function
					if (this.marking && this.current.resumable()) {
						this.suspensions.add(form);
					}
				}
				else if (operator.equals(THREAD_START) || operator.equals(THREAD_MAIN)) {
					int from = operator.equals(THREAD_START) ? 2 : 0;
					int to = operator.equals(THREAD_START) ? 3 : 2;
					for (int i = from; i < to && i < operands.size(); i++) {
						LispVal lambda = lambdaOf(operands.get(i));
						Node body = lambda == null ? null : this.lambdas.get(lambda);
						if (body != null) {
							body.awaited = true;
						}
					}
				}
				else {
					Node callee = this.named.get(operator);
					if (callee != null) {
						dispatched(form, callee, operands);
						hands(callee, operands);
					}
				}
			}
		}
	}

	/**
	 * The function a {@code funcall} calls: the value itself, or the one a structure
	 * holds.
	 */
	private static LispVal function(LispVal operand) {
		String accessor = head(operand);
		if (accessor != null && accessor.endsWith("%lambda-%f")) {
			List<LispVal> parts = elements(operand);
			if (parts.size() == 2) {
				return parts.get(1);
			}
		}
		return unwrap(operand);
	}

	/**
	 * A call by name. A dispatcher called on a receiver whose lambdas the calls tell
	 * reaches its arms and those lambdas, not every lambda of the interface.
	 */
	private void dispatched(LispVal call, Node callee, List<LispVal> operands) {
		Node arms = callee.arms;
		Flow receiver = arms == null || operands.isEmpty() ? Flow.ANY : flowOf(operands.get(0));
		if (arms == null || receiver.any) {
			calls(call, callee, operands, !this.facts.dispatchers.contains(callee.name));
			return;
		}
		calls(call, arms, operands, false);
		for (LispVal funcall : callee.received) {
			for (Node lambda : called(funcall, receiver)) {
				calls(call, lambda, operands.subList(1, operands.size()), false);
				hands(lambda, operands.subList(1, operands.size()));
			}
		}
	}

	/** A synchronization action, reached through calls by name alone or through any. */
	private void synchronizes(boolean byName) {
		for (Node node : walked()) {
			if (!node.syncs || (byName && !node.syncsByName)) {
				node.syncs = true;
				node.syncsByName |= byName;
				this.changed = true;
			}
		}
	}

	/**
	 * The function walked and, for a dispatcher outside its calls of its receiver, what
	 * it reaches without those calls.
	 */
	private List<Node> walked() {
		Node arms = this.current.arms;
		return arms == null || this.receiving ? List.of(this.current) : List.of(this.current, arms);
	}

	/**
	 * The lambdas a call through a function value reaches: of the ones the value may be,
	 * the ones made for the interface it is called through.
	 */
	private List<Node> called(LispVal funcall, Flow value) {
		Called through = this.facts.calls.get(funcall);
		List<Node> reached = new ArrayList<>();
		for (Node node : value.any ? this.nodes : value.lambdas) {
			if (!node.lambda || node.target == null) {
				continue;
			}
			if (through == null || (through.exact() ? node.target == through.receiver()
					: node.target.isSubclassOf(through.receiver()))) {
				reached.add(node);
			}
		}
		return reached;
	}

	private void calls(LispVal call, Node callee, List<LispVal> arguments, boolean byName) {
		if (callee.resumable()) {
			suspends(call);
		}
		if (callee.syncs) {
			synchronizes(byName && callee.syncsByName);
		}
		String reason = "calls " + (callee.name != null ? callee.name : "a lambda");
		if (callee.general) {
			general(reason);
		}
		for (int i = callee.waits.nextSetBit(0); i >= 0; i = callee.waits.nextSetBit(i + 1)) {
			if (i < arguments.size()) {
				waits(arguments.get(i), reason);
			}
			else {
				general(reason);
			}
		}
		if (callee.turns) {
			turn(reason);
		}
	}

	private void suspends(LispVal call) {
		if (this.marking) {
			this.suspensions.add(call);
		}
	}

	/** A suspension that keeps every monitor the thread holds. */
	private void general(String reason) {
		for (Node node : walked()) {
			if (!node.general) {
				node.general = true;
				this.changed = true;
				reasoned(node, reason);
			}
		}
		for (Region region : this.around) {
			hold(region, reason);
		}
	}

	private void turn(String reason) {
		for (Node node : walked()) {
			if (!node.turns) {
				node.turns = true;
				this.changed = true;
				reasoned(node, reason);
			}
		}
	}

	private static void reasoned(Node node, String reason) {
		if (node.why == null) {
			node.why = reason;
		}
	}

	private void hold(Region region, String reason) {
		if (this.held.add(region.entry())) {
			this.changed = true;
			this.reasons.put(region.entry(), reason);
		}
	}

	/** A wait on an object: the one suspension that gives a monitor up, the object's. */
	private void waits(LispVal object, String reason) {
		LispVal waited = unwrap(object);
		boolean given = false;
		for (Region region : this.around) {
			if (same(region.lock(), waited)) {
				given = true;
			}
			else {
				hold(region, reason);
			}
		}
		int parameter = waited instanceof LispSymbol symbol ? this.current.parameters.indexOf(symbol.name()) : -1;
		for (Node node : walked()) {
			if (given || parameter < 0) {
				// the callers' monitors are kept
				if (!node.general) {
					node.general = true;
					this.changed = true;
					reasoned(node, reason);
				}
			}
			else if (!node.waits.get(parameter)) {
				node.waits.set(parameter);
				this.changed = true;
				reasoned(node, reason);
			}
		}
	}

	/** The entry of a monitor: a suspension when a suspended thread may hold it. */
	private void entered(LispVal entry) {
		if (!this.blocking.contains(entry)) {
			Lock lock = this.facts.locks.get(entry);
			for (LispVal other : this.held) {
				if (Lock.mayBe(lock, this.facts.locks.get(other))) {
					this.blocking.add(entry);
					this.changed = true;
					break;
				}
			}
		}
		if (this.blocking.contains(entry)) {
			suspends(entry);
			general("enters a monitor a suspended thread may hold");
		}
		synchronizes(true);
	}

	/**
	 * Whether a form reaches a synchronization action, a lambda it makes aside: a
	 * {@code volatile} access, a monitor's entry, the test of the interrupt.
	 * @param through whether a dispatcher and a function value are followed too
	 */
	private boolean reaches(LispVal form, boolean through) {
		if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null) {
			return false;
		}
		String operator = head(form);
		List<LispVal> parts = elements(form);
		if (operator != null) {
			if (operator.equals(ENTER) || operator.equals(INTERRUPTED) || operator.equals(VOLATILE)) {
				return true;
			}
			Node callee = this.named.get(operator);
			if (callee != null
					&& (through ? callee.syncs : callee.syncsByName && !this.facts.dispatchers.contains(operator))) {
				return true;
			}
			if (through && operator.equals("FUNCALL") && parts.size() > 1
					&& called(form, flowOf(function(parts.get(1)))).stream().anyMatch(node -> node.syncs)) {
				return true;
			}
		}
		for (LispVal part : parts) {
			if (reaches(part, through)) {
				return true;
			}
		}
		return false;
	}

	// ---- the rewrite

	private LispVal top(LispVal form) {
		if (is(form, "DEFUN")) {
			List<LispVal> parts = elements(form);
			Node node = parts.get(1) instanceof LispSymbol name ? this.named.get(name.name()) : null;
			if (node != null) {
				this.current = node;
				boolean async = node.resumable() && !node.forwards;
				List<LispVal> rewritten = new ArrayList<>();
				rewritten.add(async ? new LispSymbol(ASYNC_DEFUN) : parts.get(0));
				rewritten.add(parts.get(1));
				rewritten.add(parts.get(2));
				boolean same = !async;
				for (LispVal statement : node.body) {
					LispVal lowered = body(node, statement);
					same &= lowered == statement;
					rewritten.add(lowered);
				}
				return same ? form : list(rewritten);
			}
		}
		return descend(form);
	}

	private LispVal body(Node node, LispVal statement) {
		if (!node.resumable()) {
			return descend(statement);
		}
		return node.forwards ? forward(statement) : spine(statement);
	}

	/** A lambda, an {@code async-lambda} when a thread can be suspended in it. */
	private LispVal lambda(LispVal form) {
		LispVal lambda = java.util.Objects.requireNonNull(lambdaOf(form));
		Node node = java.util.Objects.requireNonNull(this.lambdas.get(lambda));
		Node saved = this.current;
		this.current = node;
		List<LispVal> parts = elements(lambda);
		boolean async = node.resumable() && !node.forwards;
		List<LispVal> rewritten = new ArrayList<>();
		rewritten.add(async ? new LispSymbol(ASYNC_LAMBDA) : parts.get(0));
		rewritten.add(parts.get(1));
		boolean same = !async;
		for (LispVal statement : node.body) {
			LispVal lowered = body(node, statement);
			same &= lowered == statement;
			rewritten.add(lowered);
		}
		this.current = saved;
		if (same) {
			return form;
		}
		LispVal made = list(rewritten);
		if (node.target != null || node.awaited) {
			return made;
		}
		List<LispVal> call = new ArrayList<>();
		call.add(cl("funcall"));
		call.add(made);
		call.addAll(elements(parts.get(1)));
		return list(cl("lambda"), parts.get(1), list(NOW, list(call)));
	}

	/**
	 * A function named as a value: one the run time calls, which awaits nothing. Called
	 * there a resumable function answers at once or the program is refused by name
	 * ({@code %java-now}): its {@code toString()} entering a monitor a suspended thread
	 * may hold is fine while none holds it.
	 */
	private LispVal named(LispVal form, Node node) {
		List<LispVal> parameters = new ArrayList<>();
		node.parameters.forEach(parameter -> parameters.add(new LispSymbol(parameter)));
		List<LispVal> call = new ArrayList<>();
		call.add(elements(form).get(1));
		call.addAll(parameters);
		return list(cl("lambda"), list(parameters), list(NOW, list(call)));
	}

	/** A form no suspension is in: the lambdas it makes are rewritten, nothing else. */
	private LispVal descend(LispVal form) {
		if (!(form instanceof LispCons) || is(form, "QUOTE")) {
			return form;
		}
		if (lambdaOf(form) != null) {
			return lambda(form);
		}
		String name = functionNamed(form);
		if (name != null) {
			Node value = this.named.get(name);
			return value != null && value.resumable() ? named(form, value) : form;
		}
		List<LispVal> parts = elements(form);
		boolean same = true;
		List<LispVal> rewritten = new ArrayList<>(parts.size());
		for (LispVal part : parts) {
			LispVal lowered = descend(part);
			same &= lowered == part;
			rewritten.add(lowered);
		}
		return same ? form : list(rewritten);
	}

	/** Whether a form holds a suspension of the function it stands in. */
	private boolean needs(LispVal form) {
		if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null) {
			return false;
		}
		Boolean known = this.needing.get(form);
		if (known != null) {
			return known;
		}
		boolean needs = this.suspensions.contains(form) || this.turning.contains(form);
		if (!needs) {
			for (LispVal part : elements(form)) {
				if (needs(part)) {
					needs = true;
					break;
				}
			}
		}
		this.needing.put(form, needs);
		return needs;
	}

	private LispSymbol temporary() {
		return new LispSymbol("%w" + (this.temporaries++));
	}

	private static LispVal await(LispVal call) {
		return list(new LispSymbol(AWAIT), call);
	}

	/** A call a thread is suspended in, by the name it is suspended under. */
	private static LispVal suspending(LispVal operator) {
		LispSymbol renamed = operator instanceof LispSymbol symbol ? SUSPENDING.get(symbol.name()) : null;
		return renamed != null ? renamed : operator;
	}

	/**
	 * Whether the suspensions of a function are all calls in tail position, none among
	 * the operands of another: it then answers the future of the call it ends with.
	 */
	private boolean forwards(Node node) {
		if (node.body.isEmpty()) {
			return false;
		}
		for (int i = 0; i < node.body.size() - 1; i++) {
			if (needs(node.body.get(i))) {
				return false;
			}
		}
		return tail(node.body.get(node.body.size() - 1));
	}

	private boolean tail(LispVal form) {
		if (!needs(form)) {
			return true;
		}
		List<LispVal> parts = elements(form);
		String operator = head(form);
		if (operator == null || this.turning.contains(form)) {
			return false;
		}
		if (this.suspensions.contains(form)) {
			return !operator.equals(INIT_BUSY) && parts.subList(1, parts.size()).stream().noneMatch(this::needs);
		}
		switch (operator) {
			case "IF":
				return !needs(parts.get(1)) && parts.subList(2, parts.size()).stream().allMatch(this::tail);
			case "COND":
				for (LispVal clause : parts.subList(1, parts.size())) {
					List<LispVal> arm = elements(clause);
					if (arm.size() < 2 || !last(arm)) {
						return false;
					}
				}
				return true;
			case "PROGN":
				return last(parts.subList(1, parts.size()));
			case "LET", "LET*":
				return region(form) == null && !needs(parts.get(1)) && last(parts.subList(2, parts.size()));
			default:
				return false;
		}
	}

	private boolean last(List<LispVal> forms) {
		for (int i = 0; i < forms.size() - 1; i++) {
			if (needs(forms.get(i))) {
				return false;
			}
		}
		return !forms.isEmpty() && tail(forms.get(forms.size() - 1));
	}

	/** A function answering the future of its last call: the call is not awaited. */
	private LispVal forward(LispVal form) {
		if (!needs(form)) {
			return descend(form);
		}
		List<LispVal> parts = elements(form);
		List<LispVal> rewritten = new ArrayList<>(parts.size());
		if (this.suspensions.contains(form)) {
			rewritten.add(suspending(parts.get(0)));
			parts.subList(1, parts.size()).forEach(part -> rewritten.add(descend(part)));
			return list(rewritten);
		}
		if (is(form, "COND")) {
			rewritten.add(parts.get(0));
			for (LispVal clause : parts.subList(1, parts.size())) {
				List<LispVal> arm = new ArrayList<>();
				elements(clause).forEach(part -> arm.add(forward(part)));
				rewritten.add(list(arm));
			}
			return list(rewritten);
		}
		parts.forEach(part -> rewritten.add(forward(part)));
		return list(rewritten);
	}

	/**
	 * A form of a resumable function standing where an await may: every suspension in it
	 * is awaited, each await where the operand stack is empty.
	 */
	private LispVal spine(LispVal form) {
		if (!(form instanceof LispCons) || is(form, "QUOTE")) {
			return form;
		}
		if (lambdaOf(form) != null) {
			return lambda(form);
		}
		if (!needs(form)) {
			return descend(form);
		}
		String operator = head(form);
		List<LispVal> parts = elements(form);
		if (operator == null) {
			throw new IllegalStateException("a suspension under a form with no operator: " + form.print());
		}
		switch (operator) {
			case "PROGN", "IF", "AND", "OR", "PROG1":
				return rebuilt(parts, 1);
			case "BLOCK", "RETURN-FROM":
				return rebuilt(parts, 2);
			case "SETQ": {
				List<LispVal> rewritten = new ArrayList<>(parts.size());
				rewritten.add(parts.get(0));
				for (int i = 1; i < parts.size(); i++) {
					rewritten.add(i % 2 == 0 ? spine(parts.get(i)) : parts.get(i));
				}
				return list(rewritten);
			}
			case "LET", "LET*": {
				List<LispVal> rewritten = new ArrayList<>(parts.size());
				rewritten.add(parts.get(0));
				List<LispVal> bindings = new ArrayList<>();
				for (LispVal binding : elements(parts.get(1))) {
					List<LispVal> bound = elements(binding);
					bindings.add(bound.size() == 2 ? list(bound.get(0), spine(bound.get(1))) : binding);
				}
				rewritten.add(list(bindings));
				parts.subList(2, parts.size()).forEach(part -> rewritten.add(spine(part)));
				return list(rewritten);
			}
			case "COND": {
				List<LispVal> rewritten = new ArrayList<>(parts.size());
				rewritten.add(parts.get(0));
				for (LispVal clause : parts.subList(1, parts.size())) {
					List<LispVal> arm = new ArrayList<>();
					elements(clause).forEach(part -> arm.add(spine(part)));
					rewritten.add(list(arm));
				}
				return list(rewritten);
			}
			case "WHILE": {
				List<LispVal> rewritten = new ArrayList<>(parts.size());
				rewritten.add(parts.get(0));
				LispVal test = spine(parts.get(1));
				if (this.turning.contains(form)) {
					// once in so many turns the other threads go on
					LispVal counted = list(cl("<"), list(cl("setq"), SLICE, list(cl("-"), SLICE, integer(1))),
							integer(0));
					test = list(cl("progn"), list(cl("if"), counted, await(list(runtime("suspending-turn")))), test);
				}
				rewritten.add(test);
				parts.subList(2, parts.size()).forEach(part -> rewritten.add(spine(part)));
				return list(rewritten);
			}
			case "HANDLER-CASE":
				return handled(parts);
			case "UNWIND-PROTECT":
				return guarded(parts);
			case "SETF": {
				if (parts.size() != 3) {
					throw new IllegalStateException("a suspension under " + form.print());
				}
				if (!(parts.get(1) instanceof LispCons)) {
					return list(parts.get(0), parts.get(1), spine(parts.get(2)));
				}
				List<LispVal> place = elements(parts.get(1));
				List<LispVal> operands = new ArrayList<>(place.subList(1, place.size()));
				operands.add(parts.get(2));
				return call(operands, false, bound -> list(parts.get(0),
						list(prepend(place.get(0), bound.subList(0, bound.size() - 1))), bound.get(bound.size() - 1)));
			}
			default:
				break;
		}
		if (operator.equals(WITH_MUTEX)) {
			// the access a volatile field's lock orders: its operands are read ahead
			// of the lock
			if (parts.size() != 3 || !(parts.get(2) instanceof LispCons) || this.suspensions.contains(parts.get(2))) {
				throw new IllegalStateException("a suspension under " + form.print());
			}
			List<LispVal> access = elements(parts.get(2));
			int from = (is(parts.get(2), "SETQ") || is(parts.get(2), "SETF")) && access.size() == 3
					&& access.get(1) instanceof LispSymbol ? 2 : 1;
			if (from == 1 && UNREAD.contains(head(parts.get(2)))) {
				throw new IllegalStateException("a suspension under " + form.print());
			}
			return call(access.subList(from, access.size()), false,
					bound -> list(parts.get(0), parts.get(1), list(prepend(access.subList(0, from), bound))));
		}
		if (UNREAD.contains(operator)) {
			throw new IllegalStateException("a suspension under " + operator + ": " + form.print());
		}
		boolean suspension = this.suspensions.contains(form);
		LispVal called = suspension ? suspending(parts.get(0)) : parts.get(0);
		return call(parts.subList(1, parts.size()), suspension, bound -> list(prepend(called, bound)));
	}

	private static List<LispVal> prepend(LispVal first, List<LispVal> rest) {
		return prepend(List.of(first), rest);
	}

	private static List<LispVal> prepend(List<LispVal> first, List<LispVal> rest) {
		List<LispVal> all = new ArrayList<>(first.size() + rest.size());
		all.addAll(first);
		all.addAll(rest);
		return all;
	}

	/** A form whose parts from an index on stand where an await may. */
	private LispVal rebuilt(List<LispVal> parts, int from) {
		List<LispVal> rewritten = new ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			rewritten.add(i < from ? parts.get(i) : spine(parts.get(i)));
		}
		return list(rewritten);
	}

	/**
	 * A call standing where an await may: its operands are evaluated in turn, so what is
	 * evaluated ahead of the last suspension among them is bound first -- a literal, and
	 * a variable nothing after it assigns, stay where they are -- and the call, awaited
	 * when it is a suspension itself, is made of what is left.
	 */
	private LispVal call(List<LispVal> operands, boolean awaited, Function<List<LispVal>, LispVal> made) {
		List<LispVal> bindings = new ArrayList<>();
		LispVal call = made.apply(operands(operands, bindings));
		if (awaited) {
			call = await(call);
		}
		return bindings.isEmpty() ? call : list(cl("let*"), list(bindings), call);
	}

	/**
	 * The operands of a call as they are read once the bindings added are made: the ones
	 * ahead of the last suspension bound, the one holding it read after them.
	 */
	private List<LispVal> operands(List<LispVal> operands, List<LispVal> bindings) {
		int last = -1;
		for (int i = 0; i < operands.size(); i++) {
			if (needs(operands.get(i))) {
				last = i;
			}
		}
		List<LispVal> read = new ArrayList<>(operands.size());
		for (int i = 0; i < operands.size(); i++) {
			LispVal operand = operands.get(i);
			if (i > last) {
				read.add(descend(operand));
				continue;
			}
			if (stays(operand, operands.subList(i + 1, last + 1))) {
				read.add(operand);
				continue;
			}
			LispVal value = value(operand, bindings);
			if (i < last && !(value instanceof LispSymbol)) {
				LispSymbol temporary = temporary();
				bindings.add(list(temporary, value));
				value = temporary;
			}
			read.add(value);
		}
		return read;
	}

	/**
	 * An operand as it is read once the bindings added are made: a call's suspensions are
	 * bound and the call is left, an awaited call and any other form holding a suspension
	 * is bound whole.
	 */
	private LispVal value(LispVal operand, List<LispVal> bindings) {
		if (!needs(operand)) {
			return descend(operand);
		}
		String operator = head(operand);
		if (operator == null || SPINE.contains(operator) || operator.equals(WITH_MUTEX) || UNREAD.contains(operator)) {
			LispSymbol temporary = temporary();
			bindings.add(list(temporary, spine(operand)));
			return temporary;
		}
		List<LispVal> parts = elements(operand);
		boolean suspension = this.suspensions.contains(operand);
		LispVal called = suspension ? suspending(parts.get(0)) : parts.get(0);
		LispVal call = list(prepend(called, operands(parts.subList(1, parts.size()), bindings)));
		if (!suspension) {
			return call;
		}
		LispSymbol temporary = temporary();
		bindings.add(list(temporary, await(call)));
		return temporary;
	}

	/** Whether an operand reads the same whenever it is read among the ones after it. */
	private boolean stays(LispVal operand, List<LispVal> after) {
		if (operand instanceof LispSymbol symbol) {
			String name = symbol.name();
			if (name.startsWith(":") || name.equals("T") || name.equals("NIL")) {
				return true;
			}
			if (name.indexOf('.') >= 0) {
				// a static field, which another thread may write while this one is
				// suspended
				return false;
			}
			return after.stream().noneMatch(form -> assigns(form, name));
		}
		return !(operand instanceof LispCons) || is(operand, "QUOTE");
	}

	private static boolean assigns(LispVal form, String name) {
		if (!(form instanceof LispCons) || is(form, "QUOTE")) {
			return false;
		}
		List<LispVal> parts = elements(form);
		if ((is(form, "SETQ") || is(form, "SETF")) && parts.size() > 1 && parts.get(1) instanceof LispSymbol assigned
				&& assigned.name().equals(name)) {
			return true;
		}
		for (LispVal part : parts) {
			if (assigns(part, name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A {@code handler-case}: a clause that suspends cannot hold an await, so the clauses
	 * only say which one took the condition and the one that did runs after the form.
	 */
	private LispVal handled(List<LispVal> parts) {
		boolean suspends = false;
		for (LispVal clause : parts.subList(2, parts.size())) {
			suspends |= needs(clause);
		}
		if (!suspends) {
			List<LispVal> rewritten = new ArrayList<>();
			rewritten.add(parts.get(0));
			rewritten.add(spine(parts.get(1)));
			rewritten.addAll(descendAll(parts.subList(2, parts.size())));
			return list(rewritten);
		}
		LispSymbol taken = temporary();
		LispSymbol which = temporary();
		LispSymbol value = temporary();
		List<LispVal> clauses = new ArrayList<>();
		List<LispVal> arms = new ArrayList<>();
		int number = 0;
		for (LispVal clause : parts.subList(2, parts.size())) {
			List<LispVal> arm = elements(clause);
			List<LispVal> variables = elements(arm.get(1));
			number++;
			LispSymbol condition = variables.isEmpty() ? temporary() : (LispSymbol) variables.get(0);
			clauses.add(list(arm.get(0), list(condition), list(cl("setq"), taken, condition),
					list(cl("setq"), which, integer(number)), NIL));
			List<LispVal> body = new ArrayList<>();
			body.add(cl("let"));
			body.add(variables.isEmpty() ? NIL : list(list(condition, taken)));
			arm.subList(2, arm.size()).forEach(statement -> body.add(spine(statement)));
			arms.add(list(list(cl("eql"), which, integer(number)), list(body)));
		}
		arms.add(0, list(list(cl("eql"), which, integer(0)), value));
		List<LispVal> guarded = new ArrayList<>();
		guarded.add(parts.get(0));
		guarded.add(spine(parts.get(1)));
		guarded.addAll(clauses);
		return list(cl("let"), list(list(taken, NIL), list(which, integer(0))),
				list(cl("let"), list(list(value, list(guarded))), list(prepend(cl("cond"), arms))));
	}

	private List<LispVal> descendAll(List<LispVal> forms) {
		List<LispVal> all = new ArrayList<>(forms.size());
		forms.forEach(form -> all.add(descend(form)));
		return all;
	}

	/**
	 * An {@code unwind-protect}: a cleanup that suspends cannot hold an await, so the
	 * form is left by a way of its own -- what it threw kept, an exit of a block around
	 * it kept -- the cleanup runs after it, and it then ends the way it did.
	 */
	private LispVal guarded(List<LispVal> parts) {
		boolean suspends = false;
		for (LispVal cleanup : parts.subList(2, parts.size())) {
			suspends |= needs(cleanup);
		}
		if (!suspends) {
			List<LispVal> rewritten = new ArrayList<>();
			rewritten.add(parts.get(0));
			rewritten.add(spine(parts.get(1)));
			rewritten.addAll(descendAll(parts.subList(2, parts.size())));
			return list(rewritten);
		}
		LispSymbol thrown = temporary();
		LispSymbol exit = temporary();
		LispSymbol value = temporary();
		LispSymbol left = temporary();
		LispSymbol condition = temporary();
		Map<String, Integer> exits = new HashMap<>();
		LispVal guarded = leaving(parts.get(1), new HashSet<>(), exits, exit, value, left);
		List<LispVal> body = new ArrayList<>();
		body.add(cl("let"));
		body.add(list(list(thrown, NIL), list(exit, integer(0)), list(value, NIL)));
		body.add(list(cl("block"), left, list(cl("handler-case"), list(cl("setq"), value, spine(guarded)),
				list(cl("error"), list(condition), list(cl("setq"), thrown, condition)))));
		parts.subList(2, parts.size()).forEach(cleanup -> body.add(spine(cleanup)));
		List<LispVal> ends = new ArrayList<>();
		ends.add(cl("cond"));
		ends.add(list(thrown, list(cl("error"), thrown)));
		for (Map.Entry<String, Integer> named : exits.entrySet()) {
			ends.add(list(list(cl("eql"), exit, integer(named.getValue())),
					list(cl("return-from"), new LispSymbol(named.getKey()), value)));
		}
		ends.add(list(JavaForms.T, value));
		body.add(list(ends));
		return list(body);
	}

	/**
	 * A form in which every exit of a block around it says where it goes and leaves by
	 * the block the cleanup follows.
	 */
	private LispVal leaving(LispVal form, Set<String> inside, Map<String, Integer> exits, LispSymbol exit,
			LispSymbol value, LispSymbol left) {
		if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null) {
			return form;
		}
		List<LispVal> parts = elements(form);
		if (is(form, "RETURN-FROM") && parts.get(1) instanceof LispSymbol name && !inside.contains(name.name())) {
			int number = exits.computeIfAbsent(name.name(), key -> exits.size() + 1);
			LispVal answered = parts.size() > 2 ? leaving(parts.get(2), inside, exits, exit, value, left) : NIL;
			LispVal leaves = list(cl("progn"), list(cl("setq"), value, answered),
					list(cl("setq"), exit, integer(number)), list(cl("return-from"), left, NIL));
			carry(form, leaves);
			return leaves;
		}
		Set<String> within = inside;
		if (is(form, "BLOCK") && parts.get(1) instanceof LispSymbol name) {
			within = new HashSet<>(inside);
			within.add(name.name());
		}
		boolean same = true;
		List<LispVal> rewritten = new ArrayList<>(parts.size());
		for (LispVal part : parts) {
			LispVal lowered = leaving(part, within, exits, exit, value, left);
			same &= lowered == part;
			rewritten.add(lowered);
		}
		if (same) {
			return form;
		}
		LispVal copy = list(rewritten);
		carry(form, copy);
		return copy;
	}

	/**
	 * What the analysis recorded of a form is true of the copy an exit was rewritten in.
	 */
	private void carry(LispVal form, LispVal copy) {
		if (this.suspensions.contains(form)) {
			this.suspensions.add(copy);
		}
		if (this.turning.contains(form)) {
			this.turning.add(copy);
		}
		Boolean needs = this.needing.get(form);
		if (needs != null) {
			this.needing.put(copy, needs);
		}
	}

}
