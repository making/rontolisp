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
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
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
 * threads or serves -- a request of a server's is a thread --, for such a target alone:
 * where the host has threads the forms stay as they are.
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
 * <strong>What a value may be.</strong> A dispatcher called on a receiver reads only the
 * arms of what the receiver may be, so a reader or a stream read through its abstract
 * class reaches a socket's read only where it may be a socket's stream. What a value may
 * be is found first, a fixed point of its own over the program's forms: the instances a
 * structure's constructor makes and the lambdas written, flowing through what the calls
 * hand the parameters, what the locals are bound and assigned, what the program stores in
 * a slot or a static field, and what each function answers -- a summary in which the
 * parameters it answers as they are handed are the arguments of each call. One slot holds
 * what every instance's slot is assigned, and one function answers for every call: a
 * function is lowered once, awaited or not, so what one call of it cannot suspend another
 * still can. Anything else may be anything, and a dispatcher called on it reads every
 * arm.
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
 * give every walk of a collection a turn. Nor is the making of what a {@code throw}
 * throws: the throw goes whatever it reads.
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

		/**
		 * The {@code funcall}s of a function the lowering made for the run time: they
		 * reach none of the program's lambdas.
		 */
		final Set<LispVal> made = Collections.newSetFromMap(new IdentityHashMap<>());

		/** A monitor's entry to what is known of its lock. */
		final Map<LispVal, Lock> locks = new IdentityHashMap<>();

		/**
		 * A call taking a lock -- {@code lock()}, {@code lockInterruptibly()},
		 * {@code tryLock()} of a {@code java.util.concurrent.locks.Lock} -- to what is
		 * known of the lock.
		 */
		final Map<LispVal, Lock> acquired = new IdentityHashMap<>();

		/**
		 * The {@code lock()} of {@code x.lock(); try { .. } finally { x.unlock(); }}, a
		 * statement followed by a {@code try} giving the lock back, to the
		 * {@code unwind-protect} the {@code try} is.
		 */
		final Map<LispVal, LispVal> lockRegions = new IdentityHashMap<>();

		/** The names of the dispatchers. */
		final Set<String> dispatchers = new HashSet<>();

		/**
		 * A call asking an iterator for more ({@code hasNext()},
		 * {@code hasMoreElements()}) to what names the iterator: a local, or the enhanced
		 * {@code for}'s own.
		 */
		final Map<LispVal, Object> asks = new IdentityHashMap<>();

		/**
		 * A call taking an iterator's next element ({@code next()},
		 * {@code nextElement()}) to what names the iterator.
		 */
		final Map<LispVal, Object> takes = new IdentityHashMap<>();

		/**
		 * The classes of the front end's own library, by the name their functions' names
		 * begin with.
		 */
		final Set<String> library = new HashSet<>();

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
	 * What is known of the object a monitor is entered on, or a lock taken.
	 *
	 * @param type its class: the static type of the expression, or the class itself for a
	 * class's own lock
	 * @param exact whether the object is of that very class
	 * @param literal whether the lock is the class's own (a {@code static synchronized}
	 * method's, a class literal's)
	 * @param field the {@code final} field holding it when the field's initializer makes
	 * it -- {@code new C()}, whose object no other field holds --, or {@code null}
	 */
	record Lock(JavaClass type, boolean exact, boolean literal, @Nullable String field) {

		Lock(JavaClass type, boolean exact, boolean literal) {
			this(type, exact, literal, null);
		}

		/** Whether two locks may be one object; a lock nothing is known of is any. */
		static boolean mayBe(@Nullable Lock one, @Nullable Lock other) {
			if (one == null || other == null) {
				return true;
			}
			if (one.field != null && other.field != null && !one.field.equals(other.field)) {
				// two fields each holding what its own initializer made
				return false;
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
	 * @param locksHeld the names of the functions holding a {@code ReentrantLock}'s
	 * region across a suspension, one per region, with what it holds it across
	 * @param locksBlocking the {@code lock()}s of such regions that may wait
	 */
	record Census(int functions, List<String> resumable, int lambdas, int resumableLambdas, int regions,
			List<String> held, int blocking, List<String> locksHeld, int locksBlocking) {
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

	private static final String SOCKET_CONNECT = runtime("socket-connect").name();

	private static final String SOCKET_ACCEPT = runtime("socket-accept").name();

	private static final String SOCKET_READ = runtime("socket-read").name();

	private static final String INTERRUPTED = runtime("thread-interrupted").name();

	private static final String VOLATILE = runtime("volatile").name();

	private static final String THREAD_START = runtime("thread-start").name();

	private static final String THREAD_MAIN = runtime("thread-main").name();

	private static final String THREAD_ENDING = runtime("thread-ending").name();

	private static final String HTTP_REQUEST = runtime("http-request").name();

	private static final String EXCHANGE = runtime("http-exchange").name();

	private static final LispSymbol LAUNCHED = runtime("http-launched");

	private static final String THROW = runtime("throw").name();

	private static final String NO_RECEIVER = runtime("no-receiver").name();

	/**
	 * The operators that read nothing a thread can write: arithmetic and comparisons, the
	 * length and the code units of a string, which is a value, and the run time's
	 * refusals of a null and of an index.
	 */
	private static final Set<String> PURE = Set.of("<", "<=", ">", ">=", "=", "/=", "+", "-", "*", "1+", "1-", "EQ",
			"EQL", "NOT", "NULL", "ZEROP", "PLUSP", "MINUSP", "ABS", "MIN", "MAX", "MOD", "REM", "TRUNCATE", "FLOOR",
			"ASH", "LOGAND", "LOGIOR", "LOGXOR", "LOGNOT", "%MASK-SIGNED-FIELD", "CHAR-CODE", "CODE-CHAR", "LENGTH",
			"%STRING-CODE", NPE, runtime("string-index").name());

	/**
	 * The end of the name of a class's initialization ({@code JavaNames.initializer}).
	 */
	private static final String INITIALIZER = ".<clinit>";

	private static final LispSymbol SLICE = runtime("slice");

	private static final LispSymbol NOW = runtime("now");

	private static final LispSymbol LOCKED = runtime("locked");

	/**
	 * The library's {@code ReentrantLock}, whose {@code lock()} waits only while another
	 * thread holds the lock, and whose {@code unlock()} gives it back.
	 */
	private static final String REENTRANT = "java.util.concurrent.locks.ReentrantLock";

	private static final String REENTRANT_LOCK = REENTRANT + ".lock()";

	private static final String REENTRANT_UNLOCK = REENTRANT + ".unlock()";

	/**
	 * The functions of the run time a thread waits in, to the ones it is suspended in.
	 */
	private static final Map<String, LispSymbol> SUSPENDING = Map.of(ENTER, runtime("suspending-enter"), WAIT,
			runtime("suspending-wait"), SLEEP, runtime("suspending-sleep"), YIELD, runtime("suspending-yield"),
			INIT_BUSY, runtime("suspending-init"), REPLY, runtime("suspending-http-reply"), SOCKET_CONNECT,
			runtime("suspending-socket-connect"), SOCKET_ACCEPT, runtime("suspending-socket-accept"), SOCKET_READ,
			runtime("suspending-socket-read"));

	/**
	 * The operators a form with a suspension among its operands is not read as a call of:
	 * the lowering emits none of them, and one that came to be emitted has its own
	 * evaluation order to be written here first.
	 */
	private static final Set<String> UNREAD = Set.of("TAGBODY", "GO", "CATCH", "THROW", "FLET", "LABELS", "MACROLET",
			"SYMBOL-MACROLET", "MULTIPLE-VALUE-BIND", "MULTIPLE-VALUE-CALL", "MULTIPLE-VALUE-PROG1", "PROGV",
			"HANDLER-BIND", "RESTART-CASE", "IGNORE-ERRORS", "DOTIMES", "DOLIST", "DO", "DO*", "LOOP", "CASE", "ECASE",
			"TYPECASE", "ETYPECASE", "WHEN", "UNLESS", "PROG", "PROG*", "PROG2", "RETURN", "PSETQ", "INCF", "DECF",
			"PUSH", "POP", "LOCALLY", "EVAL-WHEN", "DESTRUCTURING-BIND", "DEFUN", "DEFVAR", "DEFSTRUCT",
			"%REPORTING-UNCAUGHT");

	/**
	 * The operators whose forms hold an await where they stand: {@link #spine} reads
	 * each.
	 */
	private static final Set<String> SPINE = Set.of("PROGN", "IF", "AND", "OR", "PROG1", "BLOCK", "RETURN-FROM", "SETQ",
			"LET", "LET*", "COND", "WHILE", "HANDLER-CASE", "UNWIND-PROTECT", "SETF", "THE");

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

		/**
		 * Whether it waits on the monitor of a region of its own, which it gives up while
		 * it waits: to its callers a suspension as any, to the one taking a
		 * {@code ReentrantLock} the wait for another thread holding it.
		 */
		boolean waitsOwn;

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

		/**
		 * Whether what it answers is its arguments' doing alone: it reads nothing a
		 * thread can write and calls only functions that do not either.
		 */
		boolean pure;

		/**
		 * Whether it is a function of the front end's own library, or a lambda of one.
		 */
		boolean library;

		/**
		 * Whether it reaches a synchronization action written outside the front end's own
		 * library, through any call.
		 */
		boolean programSyncs;

		/**
		 * What first made it reach one, by name where it does, for the one reading the
		 * analysis.
		 */
		@Nullable String syncWhy;

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

		/**
		 * A dispatcher's arms, in their order; {@code null} where its body is no cond.
		 */
		@Nullable List<Clause> clauses;

		/** A dispatcher's arms a lambda may take. */
		final List<Node> lambdaClauses = new ArrayList<>();

		/** A dispatcher's arms an instance of a structure may take, by the structure. */
		final Map<String, List<Node>> taken = new HashMap<>();

		/**
		 * What it answers: a summary whose parameters ({@link Flow#params}) a call reads
		 * as its arguments.
		 */
		final Flow returns = new Flow();

		/** The variables in reach where a lambda is written. */
		@Nullable Scope captured;

		/** The blocks around a lambda where it is written. */
		@Nullable Blocks enclosing;

		Node(@Nullable String name, List<String> parameters, List<LispVal> body, boolean lambda) {
			this.name = name;
			this.parameters = parameters;
			this.body = body;
			this.lambda = lambda;
			parameters.forEach(parameter -> this.flows.add(new Flow()));
		}

		boolean resumable() {
			return this.general || this.waitsOwn || !this.waits.isEmpty() || this.turns;
		}

	}

	/**
	 * What a value may be: one of some lambdas, an instance of exactly one of some
	 * structures -- the classes made by {@code new} --, or {@code null}; or anything,
	 * once something the analysis does not follow is among what it is handed. In a
	 * function's summary of what it answers, also the parameters it answers as they are
	 * handed ({@link #params}), which a call reads as its arguments.
	 */
	private static final class Flow {

		static final Flow ANY = new Flow();
		static {
			ANY.any = true;
		}

		/** Nothing: {@code null}, or a form that answers no value. Never added to. */
		static final Flow NONE = new Flow();

		boolean any;

		final Set<Node> lambdas = Collections.newSetFromMap(new IdentityHashMap<>());

		/** The structures, by name, of the instances it may be. */
		final Set<String> classes = new HashSet<>();

		/** The parameters a summary answers as they are handed, by index. */
		final BitSet params = new BitSet();

		static Flow of(Node lambda) {
			Flow one = new Flow();
			one.lambdas.add(lambda);
			return one;
		}

		static Flow made(String structure) {
			Flow one = new Flow();
			one.classes.add(structure);
			return one;
		}

		static Flow parameter(int index) {
			Flow one = new Flow();
			one.params.set(index);
			return one;
		}

		boolean empty() {
			return !this.any && this.lambdas.isEmpty() && this.classes.isEmpty() && this.params.isEmpty();
		}

		boolean add(Flow other) {
			if (this.any) {
				return false;
			}
			if (other.any) {
				this.any = true;
				return true;
			}
			boolean grew = this.lambdas.addAll(other.lambdas);
			grew |= this.classes.addAll(other.classes);
			if (!other.params.isEmpty()) {
				int before = this.params.cardinality();
				this.params.or(other.params);
				grew |= this.params.cardinality() != before;
			}
			return grew;
		}

		/** What two values together may be. */
		static Flow union(Flow one, Flow other) {
			if (one.any || other.any) {
				return ANY;
			}
			if (other.empty()) {
				return one;
			}
			if (one.empty()) {
				return other;
			}
			Flow both = new Flow();
			both.add(one);
			both.add(other);
			return both;
		}

	}

	/**
	 * A variable in reach where a form stands: a local a {@code let} binds, or a
	 * parameter of a function -- its own, or one of a function it is written in.
	 *
	 * @param name the variable's name
	 * @param binding a local's binding, {@code (name init)}, which keys what it may be
	 * @param function a parameter's function
	 * @param parameter a parameter's index
	 * @param next the variables reached past this one
	 */
	private record Scope(String name, @Nullable LispVal binding, @Nullable Node function, int parameter,
			@Nullable Scope next) {

		@Nullable Scope find(String variable) {
			for (Scope at = this; at != null; at = at.next) {
				if (at.name.equals(variable)) {
					return at;
				}
			}
			return null;
		}

		/** A function's parameters in reach ahead of the variables around it. */
		static @Nullable Scope parameters(Node function, @Nullable Scope around) {
			Scope scope = around;
			for (int i = function.parameters.size() - 1; i >= 0; i--) {
				scope = new Scope(function.parameters.get(i), null, function, i, scope);
			}
			return scope;
		}

	}

	/**
	 * The blocks around a form, the innermost first.
	 *
	 * @param block a {@code block} form
	 * @param next the blocks around it
	 */
	private record Blocks(LispVal block, @Nullable Blocks next) {

		@Nullable LispVal find(String name) {
			for (Blocks at = this; at != null; at = at.next) {
				if (elements(at.block).get(1) instanceof LispSymbol named && named.name().equals(name)) {
					return at.block;
				}
			}
			return null;
		}

	}

	/** What the test of a dispatcher's arm is: the receiver's structure, or else. */
	private enum Kind {

		/** An instance of a structure or of one under it. */
		CLASS,
		/** A lambda: a function, or a structure holding one. */
		LAMBDA,
		/** Every receiver. */
		DEFAULT,
		/** A test the analysis does not read: any receiver may pass it. */
		OTHER

	}

	/**
	 * An arm of a dispatcher: what its test tests, and what the arm reaches -- a call
	 * whose receiver is known to be an instance of some classes reads only their arms.
	 *
	 * @param form the arm, its test first
	 * @param kind what the test is
	 * @param structure the structure a {@link Kind#CLASS} test tests
	 * @param node what the arm reaches
	 */
	private record Clause(LispVal form, Kind kind, @Nullable String structure, Node node) {
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

	/** The held region an entry that may block was first found to be blocked by. */
	private final Map<LispVal, LispVal> blockers = new IdentityHashMap<>();

	/**
	 * The regions of {@code ReentrantLock}s around the form walked, by their
	 * {@code lock()}s, the innermost first.
	 */
	private final Deque<LispVal> taking = new ArrayDeque<>();

	/** A lock region's {@code unwind-protect} to its {@code lock()}. */
	private final Map<LispVal, LispVal> lockGuards = new IdentityHashMap<>();

	/**
	 * Whether a {@code lock()} and the {@code try} after it are read as a region, by the
	 * {@code lock()}: decided once what values may be is known.
	 */
	private final Map<LispVal, Boolean> lockRegions = new IdentityHashMap<>();

	/** The function a lock region stands in, by its {@code lock()}. */
	private final Map<LispVal, Node> lockOwners = new IdentityHashMap<>();

	/**
	 * The lock regions a suspended thread can hold, by their {@code lock()}s, with what
	 * first held each.
	 */
	private final Map<LispVal, String> locksHeld = new IdentityHashMap<>();

	/** The keys of {@link #locksHeld} in the order they were found. */
	private final List<LispVal> locksHeldFound = new ArrayList<>();

	/** The {@code lock()}s of lock regions that may wait, with why. */
	private final Map<LispVal, String> locksBlocking = new IdentityHashMap<>();

	/**
	 * The calls taking a lock no region reads -- one that may not give it back where the
	 * thread suspends --, to the function each stands in.
	 */
	private final Map<LispVal, Node> takenOtherwise = new IdentityHashMap<>();

	/** The keys of {@link #takenOtherwise} in the order they were found. */
	private final List<LispVal> takenOtherwiseFound = new ArrayList<>();

	/**
	 * The {@code lock()}s of lock regions no suspended thread can hold: each takes its
	 * lock at once, which the rewrite checks.
	 */
	private final Set<LispVal> taken = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * The resumable dispatchers a call reads an arm of that does not suspend, for what
	 * its receiver may be: each must answer the arm's own value.
	 */
	private final Set<Node> unawaited = Collections.newSetFromMap(new IdentityHashMap<>());

	/** The calls awaited. */
	private final Set<LispVal> suspensions = Collections.newSetFromMap(new IdentityHashMap<>());

	/** The loops that count their turns. */
	private final Set<LispVal> turning = Collections.newSetFromMap(new IdentityHashMap<>());

	private final Map<LispVal, Boolean> needing = new IdentityHashMap<>();

	/** A structure to the one it includes: a class to its superclass. */
	private final Map<String, String> includes = new HashMap<>();

	/** A structure's constructor to the structure it makes. */
	private final Map<String, String> allocators = new HashMap<>();

	/** A structure's predicate to the structure it tests. */
	private final Map<String, String> predicates = new HashMap<>();

	/** The structures of lambdas made for an interface, which hold the function. */
	private final Set<String> lambdaStructures = new HashSet<>();

	/**
	 * A slot's accessor to the slot, named by the accessor of the structure declaring it:
	 * an accessor of the structure including it reads the same slot.
	 */
	private final Map<String, String> accessors = new HashMap<>();

	/** What each slot may hold: what the program stores in it. */
	private final Map<String, Flow> slots = new HashMap<>();

	/**
	 * What each global variable the program assigns may hold -- a static field --, by its
	 * name; one it never assigns may be anything.
	 */
	private final Map<String, Flow> globals = new HashMap<>();

	/** What each local may hold, by its binding. */
	private final Map<LispVal, Flow> locals = new IdentityHashMap<>();

	/** What each block may answer by a {@code return-from}, by the block. */
	private final Map<LispVal, Flow> answered = new IdentityHashMap<>();

	/** The variables in reach where the walk stands. */
	private @Nullable Scope scope;

	/** The blocks around the form walked, the innermost first. */
	private @Nullable Blocks blocks;

	/** The arm of a dispatcher walked, which gathers what the arm reaches. */
	private @Nullable Node clause;

	private boolean changed;

	/** Whether what is walked is a dispatcher's call of its receiver as a function. */
	private boolean receiving;

	/** Whether the walk is the last one, which records what the rewrite reads. */
	private boolean marking;

	/**
	 * Whether the walks find what each value may be, and nothing else: what reaches a
	 * suspension is found once they are done.
	 */
	private boolean flowing;

	/**
	 * Whether what is walked makes what a {@code throw} throws: the throw goes whatever
	 * it reads, so a synchronization action there decides no loop (an
	 * {@code SQLException}'s constructor reads {@code DriverManager}'s log writer).
	 */
	private boolean throwing;

	/** How long the analysis took, and how many walks of the program each half took. */
	private long took;

	private int flowWalks;

	private int suspensionWalks;

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
		long started = System.nanoTime();
		resumable.analyse(forms);
		resumable.took = (System.nanoTime() - started) / 1_000_000;
		if (counted != null) {
			counted.accept(resumable.census());
		}
		String listed = System.getProperty("rontolisp.java.resumable");
		if (listed != null) {
			resumable.written(java.nio.file.Path.of(listed));
		}
		List<LispVal> rewritten = new ArrayList<>(forms.size());
		for (LispVal form : forms) {
			rewritten.add(launched(resumable.top(form)));
		}
		return List.copyOf(rewritten);
	}

	/**
	 * The functions by name a thread can be suspended in, as {@link #rewrite} would find
	 * them, the forms left as they are.
	 */
	static Set<String> resumable(List<LispVal> forms, Facts facts, Function<String, RuntimeException> refused) {
		JavaResumable resumable = new JavaResumable(facts, refused);
		resumable.analyse(forms);
		Set<String> names = new HashSet<>();
		for (Node node : resumable.nodes) {
			if (node.name != null && !node.lambda && node.resumable()) {
				names.add(node.name);
			}
		}
		return names;
	}

	/**
	 * A form with every request of the HTTP client sent as one of a program whose threads
	 * take turns: its exchange is the run time's to read while they do.
	 */
	private static LispVal launched(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form instanceof LispSymbol symbol && symbol.name().equals(EXCHANGE) ? LAUNCHED : form;
		}
		if (is(form, "QUOTE")) {
			return form;
		}
		List<LispVal> parts = elements(form);
		boolean same = true;
		List<LispVal> renamed = new ArrayList<>(parts.size());
		for (LispVal part : parts) {
			LispVal lowered = launched(part);
			same &= lowered == part;
			renamed.add(lowered);
		}
		return same ? cons : list(renamed);
	}

	/**
	 * Writes what the analysis found, for the one measuring a program: each function and
	 * lambda a thread can be suspended in, with what first made it so, then each that
	 * reaches a synchronization action through calls by name, which is what gives a loop
	 * around it a turn; with {@code -Drontolisp.java.flows=REGEX}, what the parameters
	 * and the answer of each function, each slot and each static field whose name the
	 * expression finds may be -- why a dispatcher's call read every arm. A debugging
	 * switch of the JVM running the compiler ({@code -Drontolisp.java.resumable=FILE}).
	 */
	private void written(java.nio.file.Path file) {
		List<String> lines = new ArrayList<>();
		List<String> synchronizing = new ArrayList<>();
		for (Node node : this.nodes) {
			if (node.resumable()) {
				lines.add(readable(node) + (node.forwards ? " -> " : ": ") + node.why);
			}
			if (node.syncsByName) {
				synchronizing.add("SYNC " + readable(node) + ": " + node.syncWhy);
			}
		}
		lines.add(0, "# " + lines.size() + " of " + this.nodes.size() + " functions and lambdas are resumable");
		lines.add(1, "# analysed in " + this.took + " ms: " + this.flowWalks + " walks finding what values may be, "
				+ this.suspensionWalks + " what may suspend");
		lines.addAll(synchronizing);
		for (LispVal entry : this.held) {
			lines.add("HELD " + readable(this.regions.get(entry)) + " on " + this.facts.locks.get(entry) + ": "
					+ this.reasons.get(entry));
		}
		for (LispVal entry : this.blocking) {
			lines.add("BLOCKS " + readable(this.regions.get(entry)) + " on " + this.facts.locks.get(entry));
		}
		for (LispVal entry : this.locksHeldFound) {
			lines.add("HELD LOCK " + readable(this.lockOwners.get(entry)) + " on " + this.facts.acquired.get(entry)
					+ ": " + this.locksHeld.get(entry));
		}
		this.locksBlocking.forEach((entry, why) -> lines.add("WAITS LOCK " + readable(this.lockOwners.get(entry))
				+ " on " + this.facts.acquired.get(entry) + ": " + why));
		for (LispVal entry : this.takenOtherwiseFound) {
			lines.add(
					"TAKES LOCK " + readable(this.takenOtherwise.get(entry)) + " on " + this.facts.acquired.get(entry));
		}
		String flows = System.getProperty("rontolisp.java.flows");
		if (flows != null) {
			java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(flows);
			for (Node node : this.named.values()) {
				if (pattern.matcher(java.util.Objects.requireNonNull(node.name)).find()) {
					for (int i = 0; i < node.flows.size(); i++) {
						lines.add("FLOW " + node.name + " " + node.parameters.get(i) + ": " + shown(node.flows.get(i)));
					}
					lines.add("FLOW " + node.name + " answers " + shown(node.returns));
				}
			}
			new java.util.TreeMap<>(this.slots).forEach((slot, flow) -> {
				if (pattern.matcher(slot).find()) {
					lines.add("FLOW slot " + slot + ": " + shown(flow));
				}
			});
			new java.util.TreeMap<>(this.globals).forEach((name, flow) -> {
				if (pattern.matcher(name).find()) {
					lines.add("FLOW static " + name + ": " + shown(flow));
				}
			});
		}
		try {
			java.nio.file.Files.write(file, lines);
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	/** What a value may be, as the listing says it. */
	private static String shown(Flow flow) {
		if (flow.any) {
			return "anything";
		}
		List<String> parts = new ArrayList<>(new java.util.TreeSet<>(flow.classes));
		if (!flow.lambdas.isEmpty()) {
			parts.add(flow.lambdas.size() + " lambdas");
		}
		if (!flow.params.isEmpty()) {
			parts.add("parameters " + flow.params);
		}
		return parts.toString();
	}

	/** A function by its name, a lambda by the function it is written in. */
	private static String readable(@Nullable Node node) {
		if (node == null) {
			return "a form the walk found no function of";
		}
		Node named = node;
		while (named.name == null && named.around != null) {
			named = named.around;
		}
		return node.lambda ? "a lambda in " + named.name : String.valueOf(node.name);
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
		List<String> locking = new ArrayList<>();
		for (LispVal entry : resumable.locksHeldFound) {
			Node owner = resumable.lockOwners.get(entry);
			locking.add((owner == null || owner.name == null ? "a lambda" : owner.name) + ": "
					+ resumable.locksHeld.get(entry));
		}
		Collections.sort(locking);
		return new Census(resumable.named.size(), List.copyOf(names), lambdas, resumableLambdas,
				resumable.regions.size(), List.copyOf(holding), resumable.blocking.size(), List.copyOf(locking),
				resumable.locksBlocking.size());
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
		List<List<LispVal>> structures = new ArrayList<>();
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
			if (is(form, "DEFSTRUCT")) {
				structures.add(elements(form));
				continue;
			}
			top.body.add(form);
		}
		this.nodes.add(top);
		structures(structures);
		this.facts.lockRegions.forEach((entry, guarded) -> this.lockGuards.put(guarded, entry));
		for (Node node : List.copyOf(this.nodes)) {
			if (node.name != null && this.facts.dispatchers.contains(node.name) && !node.parameters.isEmpty()) {
				node.arms = new Node(node.name, node.parameters, node.body, false);
				node.clauses = clauses(node);
			}
			Scope parameters = Scope.parameters(node, null);
			node.body.forEach(form -> declare(form, node, parameters, null));
		}
		for (Node node : this.nodes) {
			if (node.lambda && node.target == null) {
				// a lambda the lowering wrote for the run time, which hands it what it
				// has: nothing the analysis follows
				node.flows.forEach(flow -> flow.add(Flow.ANY));
			}
		}
		for (Node node : this.nodes) {
			Node named = node;
			while (named.name == null && named.around != null) {
				named = named.around;
			}
			node.library = named.name != null && this.facts.library.contains(owner(named.name));
		}
		purity();
		// what each value may be first, then what reaches a suspension: an arm read for
		// what a receiver was found to be so far would be read for good
		this.flowing = true;
		do {
			this.changed = false;
			this.flowWalks++;
			for (Node node : this.nodes) {
				walk(node);
			}
		}
		while (this.changed);
		this.flowing = false;
		do {
			this.changed = false;
			this.suspensionWalks++;
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
		for (Node dispatcher : this.unawaited) {
			if (!dispatcher.forwards) {
				// what a receiver may be left no arm that suspends: the call reads the
				// value
				// of the arm, which a dispatcher that awaits would not answer
				throw new IllegalStateException(
						"a dispatcher that awaits, called where nothing awaits: " + dispatcher.name);
			}
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

	/**
	 * Finds the lambdas of a form, each a function of its own with the variables in reach
	 * where it is written; the global variables the program assigns; and what is named
	 * quoted, which the run time may call or store through.
	 */
	private void declare(LispVal form, Node around, @Nullable Scope scope, @Nullable Blocks blocks) {
		if (!(form instanceof LispCons)) {
			return;
		}
		if (is(form, "QUOTE")) {
			quoted(form);
			return;
		}
		LispVal lambda = lambdaOf(form);
		if (lambda != null) {
			if (!this.lambdas.containsKey(lambda)) {
				List<LispVal> parts = elements(lambda);
				Node node = new Node(null, names(parts.get(1)), parts.subList(2, parts.size()), true);
				node.target = this.facts.lambdas.get(lambda);
				node.around = around;
				node.captured = scope;
				node.enclosing = blocks;
				this.lambdas.put(lambda, node);
				this.nodes.add(node);
				Scope inside = Scope.parameters(node, scope);
				node.body.forEach(statement -> declare(statement, node, inside, blocks));
			}
			return;
		}
		List<LispVal> parts = elements(form);
		if ((is(form, "LET") || is(form, "LET*")) && parts.size() > 1) {
			Scope inside = scope;
			for (LispVal binding : elements(parts.get(1))) {
				List<LispVal> bound = elements(binding);
				if (!bound.isEmpty() && bound.get(0) instanceof LispSymbol variable) {
					if (bound.size() > 1) {
						declare(bound.get(1), around, is(form, "LET") ? scope : inside, blocks);
					}
					inside = new Scope(variable.name(), binding, null, -1, inside);
				}
			}
			for (LispVal part : parts.subList(2, parts.size())) {
				declare(part, around, inside, blocks);
			}
			return;
		}
		if (is(form, "BLOCK") && parts.size() > 1) {
			Blocks inside = new Blocks(form, blocks);
			for (LispVal part : parts.subList(2, parts.size())) {
				declare(part, around, scope, inside);
			}
			return;
		}
		if ((is(form, "SETQ") || is(form, "SETF") || is(form, "DEFVAR")) && parts.size() > 2) {
			for (int i = 1; i < parts.size(); i += 2) {
				if (parts.get(i) instanceof LispSymbol variable && isStatic(variable)) {
					this.globals.computeIfAbsent(variable.name(), name -> new Flow());
				}
			}
		}
		parts.forEach(part -> declare(part, around, scope, blocks));
	}

	/**
	 * Whether a symbol may name a static field's global variable: a class's name ahead of
	 * a dot, no package.
	 */
	private static boolean isStatic(LispSymbol symbol) {
		String name = symbol.name();
		return name.indexOf('.') >= 0 && name.indexOf(':') < 0 && !constant(symbol);
	}

	/**
	 * A quoted name the run time may call or store through: a function so named takes
	 * anything, a slot so named holds anything.
	 */
	private void quoted(LispVal form) {
		if (form instanceof LispSymbol symbol) {
			String slot = this.accessors.get(symbol.name());
			if (slot != null) {
				this.slots.computeIfAbsent(slot, key -> new Flow()).add(Flow.ANY);
			}
			Node named = this.named.get(symbol.name());
			if (named != null) {
				named.flows.forEach(flow -> flow.add(Flow.ANY));
			}
			if (isStatic(symbol)) {
				this.globals.computeIfAbsent(symbol.name(), key -> new Flow()).add(Flow.ANY);
			}
			return;
		}
		if (form instanceof LispCons) {
			elements(form).forEach(this::quoted);
		}
	}

	/**
	 * Reads the program's structures: what includes what, their constructors and
	 * predicates, the slots their accessors read.
	 */
	private void structures(List<List<LispVal>> structures) {
		Map<String, List<String>> own = new HashMap<>();
		Map<String, String> prefixes = new HashMap<>();
		for (List<LispVal> parts : structures) {
			LispVal header = parts.get(1);
			List<LispVal> options = header instanceof LispCons ? elements(header) : List.of(header);
			if (!(options.get(0) instanceof LispSymbol named)) {
				continue;
			}
			String name = named.name();
			String prefix = name + "-";
			for (LispVal option : options.subList(1, options.size())) {
				List<LispVal> said = elements(option);
				if (said.size() < 2 || !(said.get(0) instanceof LispSymbol key)) {
					continue;
				}
				String value = said.get(1) instanceof LispSymbol symbol ? symbol.name() : null;
				switch (key.name()) {
					case ":INCLUDE" -> {
						if (value != null) {
							this.includes.put(name, value);
						}
					}
					case ":CONSTRUCTOR" -> {
						if (value != null && !value.equals("NIL")) {
							this.allocators.put(value, name);
						}
					}
					case ":PREDICATE" -> {
						if (value != null && !value.equals("NIL")) {
							this.predicates.put(value, name);
						}
					}
					case ":CONC-NAME" -> prefix = value == null || value.equals("NIL") ? "" : value;
					default -> {
					}
				}
			}
			if (name.endsWith("%lambda")) {
				this.lambdaStructures.add(name);
			}
			prefixes.put(name, prefix);
			List<String> declared = new ArrayList<>();
			for (LispVal slot : parts.subList(2, parts.size())) {
				List<LispVal> spec = slot instanceof LispCons ? elements(slot) : List.of(slot);
				if (spec.get(0) instanceof LispSymbol symbol) {
					declared.add(symbol.name());
					if (spec.size() > 1 && spec.get(1) != NIL) {
						// a slot made holding a number or such
						this.slots.computeIfAbsent(prefix + symbol.name(), key -> new Flow()).add(Flow.ANY);
					}
				}
			}
			own.put(name, declared);
		}
		for (Map.Entry<String, String> structure : prefixes.entrySet()) {
			// every accessor of a slot, the structure's own and the ones it includes, to
			// the accessor of the structure declaring it
			for (String at = structure.getKey(); at != null; at = this.includes.get(at)) {
				String declaring = prefixes.get(at);
				if (declaring == null) {
					break;
				}
				for (String slot : own.getOrDefault(at, List.of())) {
					this.accessors.put(structure.getValue() + slot, declaring + slot);
				}
			}
		}
	}

	/** Whether an instance of a structure is an instance of another. */
	private boolean under(String made, String structure) {
		for (String at = made; at != null; at = this.includes.get(at)) {
			if (at.equals(structure)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A dispatcher's arms, read from its cond: the structure each tests, as far as the
	 * test is a structure's predicate of the receiver.
	 */
	private @Nullable List<Clause> clauses(Node dispatcher) {
		if (dispatcher.body.size() != 1 || !is(dispatcher.body.get(0), "COND")) {
			return null;
		}
		String receiver = dispatcher.parameters.get(0);
		List<Clause> clauses = new ArrayList<>();
		for (LispVal arm : elements(dispatcher.body.get(0)).subList(1, elements(dispatcher.body.get(0)).size())) {
			List<LispVal> parts = elements(arm);
			if (parts.isEmpty()) {
				return null;
			}
			LispVal test = parts.get(0);
			List<LispVal> tested = elements(test);
			Kind kind = Kind.OTHER;
			String structure = null;
			if (test instanceof LispTrue || (test instanceof LispSymbol symbol && symbol.name().equals("T"))) {
				kind = Kind.DEFAULT;
			}
			else if (tested.size() == 2 && tested.get(0) instanceof LispSymbol predicate
					&& tested.get(1) instanceof LispSymbol argument && argument.name().equals(receiver)) {
				structure = this.predicates.get(predicate.name());
				if (predicate.name().equals("FUNCTIONP")
						|| (structure != null && this.lambdaStructures.contains(structure))) {
					kind = Kind.LAMBDA;
					structure = null;
				}
				else if (structure != null) {
					kind = Kind.CLASS;
				}
			}
			Node node = new Node(dispatcher.name, dispatcher.parameters, List.of(), false);
			clauses.add(new Clause(arm, kind, structure, node));
			if (kind == Kind.LAMBDA) {
				dispatcher.lambdaClauses.add(node);
			}
		}
		return clauses;
	}

	/**
	 * The arms of a dispatcher an instance of exactly a structure may take: the first
	 * testing a structure it is under, and every test before it the analysis does not
	 * read.
	 */
	private List<Node> taken(Node dispatcher, String made) {
		return dispatcher.taken.computeIfAbsent(made, key -> {
			List<Node> taken = new ArrayList<>();
			for (Clause clause : java.util.Objects.requireNonNull(dispatcher.clauses)) {
				switch (clause.kind()) {
					case CLASS -> {
						if (under(made, java.util.Objects.requireNonNull(clause.structure()))) {
							taken.add(clause.node());
							return taken;
						}
					}
					case DEFAULT -> {
						taken.add(clause.node());
						return taken;
					}
					case OTHER -> taken.add(clause.node());
					case LAMBDA -> {
					}
				}
			}
			return taken;
		});
	}

	/** What a value may be where the walk stands. */
	private Flow flowOf(LispVal form) {
		return flowOf(form, false);
	}

	/**
	 * What a value may be: a lambda written there, an instance a structure's constructor
	 * makes, a variable as what is stored in it -- a parameter as what the calls hand it,
	 * a local as what it is bound and assigned, a static field as what the program
	 * assigns it --, a slot as what the program stores in it, what a function answers,
	 * and the forms that only choose among such values; anything else may be anything.
	 * @param summary whether the parameters of the function walked are read as
	 * themselves, for the summary of what it answers
	 */
	private Flow flowOf(LispVal form, boolean summary) {
		if (form instanceof LispNil) {
			return Flow.NONE;
		}
		if (form instanceof LispSymbol symbol) {
			return variable(symbol, summary);
		}
		if (!(form instanceof LispCons)) {
			return Flow.ANY;
		}
		LispVal lambda = lambdaOf(form);
		if (lambda != null) {
			Node made = this.lambdas.get(lambda);
			return made == null ? Flow.ANY : Flow.of(made);
		}
		String operator = head(form);
		if (operator == null) {
			return Flow.ANY;
		}
		List<LispVal> parts = elements(form);
		switch (operator) {
			case "QUOTE", "FUNCTION":
				return Flow.ANY;
			case "THE":
				return parts.size() == 3 ? flowOf(parts.get(2), summary) : Flow.ANY;
			case "PROGN":
				return last(parts, 1, summary);
			case "PROG1", "UNWIND-PROTECT":
				return parts.size() > 1 ? flowOf(parts.get(1), summary) : Flow.NONE;
			case "LET", "LET*": {
				Scope saved = this.scope;
				for (LispVal binding : elements(parts.get(1))) {
					List<LispVal> bound = elements(binding);
					if (!bound.isEmpty() && bound.get(0) instanceof LispSymbol variable) {
						this.scope = new Scope(variable.name(), binding, null, -1, this.scope);
					}
				}
				Flow flow = last(parts, 2, summary);
				this.scope = saved;
				return flow;
			}
			case "IF":
				return parts.size() < 3 ? Flow.ANY : Flow.union(flowOf(parts.get(2), summary),
						parts.size() > 3 ? flowOf(parts.get(3), summary) : Flow.NONE);
			case "COND": {
				Flow flow = Flow.NONE;
				for (LispVal clause : parts.subList(1, parts.size())) {
					List<LispVal> arm = elements(clause);
					flow = Flow.union(flow, arm.isEmpty() ? Flow.NONE : flowOf(arm.get(arm.size() - 1), summary));
				}
				return flow;
			}
			case "AND":
				return parts.size() == 1 ? Flow.ANY : flowOf(parts.get(parts.size() - 1), summary);
			case "OR": {
				Flow flow = Flow.NONE;
				for (LispVal part : parts.subList(1, parts.size())) {
					flow = Flow.union(flow, flowOf(part, summary));
				}
				return flow;
			}
			case "BLOCK":
				return Flow.union(this.answered.getOrDefault(form, Flow.NONE), last(parts, 2, summary));
			case "RETURN-FROM", "GO", "THROW", "ERROR":
				return Flow.NONE;
			case "HANDLER-CASE": {
				Flow flow = parts.size() > 1 ? flowOf(parts.get(1), summary) : Flow.NONE;
				for (LispVal clause : parts.subList(Math.min(2, parts.size()), parts.size())) {
					// what a clause binds is the condition, which is no value followed
					List<LispVal> arm = elements(clause);
					flow = Flow.union(flow, arm.size() > 2 ? flowOf(arm.get(arm.size() - 1), summary) : Flow.NONE);
				}
				return flow;
			}
			case "SETQ", "SETF":
				return parts.size() > 2 ? flowOf(parts.get(parts.size() - 1), summary) : Flow.NONE;
			case "FUNCALL":
				return funcalled(form, parts, summary);
			default:
				break;
		}
		if (operator.equals(WITH_MUTEX)) {
			return last(parts, 2, summary);
		}
		if (operator.equals(THROW) || operator.equals(NPE) || operator.equals(NO_RECEIVER)) {
			return Flow.NONE;
		}
		String made = this.allocators.get(operator);
		if (made != null) {
			// a lambda held by the structure telling its interface is the lambda
			return this.lambdaStructures.contains(made) ? parts.size() == 2 ? flowOf(parts.get(1), summary) : Flow.ANY
					: Flow.made(made);
		}
		String slot = parts.size() == 2 ? this.accessors.get(operator) : null;
		if (slot != null) {
			return this.slots.getOrDefault(slot, Flow.NONE);
		}
		Node callee = this.named.get(operator);
		if (callee != null) {
			return applied(callee.returns, parts.subList(1, parts.size()), summary);
		}
		return Flow.ANY;
	}

	/** What the last of some forms may be. */
	private Flow last(List<LispVal> parts, int from, boolean summary) {
		return parts.size() > from ? flowOf(parts.get(parts.size() - 1), summary) : Flow.NONE;
	}

	/**
	 * What a variable may be: a local or a parameter in reach, a global variable the
	 * program assigns; in a summary, a parameter of the function walked is itself.
	 */
	private Flow variable(LispSymbol symbol, boolean summary) {
		String name = symbol.name();
		if (constant(symbol)) {
			return name.equals("NIL") ? Flow.NONE : Flow.ANY;
		}
		Scope found = this.scope == null ? null : this.scope.find(name);
		if (found != null) {
			if (found.binding() != null) {
				return this.locals.getOrDefault(found.binding(), Flow.NONE);
			}
			Node function = java.util.Objects.requireNonNull(found.function());
			return summary && function == this.current ? Flow.parameter(found.parameter())
					: function.flows.get(found.parameter());
		}
		Flow global = isStatic(symbol) ? this.globals.get(name) : null;
		return global != null ? global : Flow.ANY;
	}

	/**
	 * What a call through a function value answers: what the lambdas it reaches answer, a
	 * function named there what it answers.
	 */
	private Flow funcalled(LispVal form, List<LispVal> parts, boolean summary) {
		if (parts.size() < 2 || this.facts.made.contains(form)) {
			return Flow.ANY;
		}
		List<LispVal> arguments = parts.subList(2, parts.size());
		String name = functionNamed(parts.get(1));
		if (name != null) {
			Node callee = this.named.get(name);
			return callee == null ? Flow.ANY : applied(callee.returns, arguments, summary);
		}
		Flow value = flowOf(function(parts.get(1)));
		if (value.any) {
			return Flow.ANY;
		}
		Flow flow = Flow.NONE;
		for (Node lambda : called(form, value)) {
			flow = Flow.union(flow, applied(lambda.returns, arguments, summary));
		}
		return flow;
	}

	/**
	 * What a call answers, from the summary of what its function answers: the parameters
	 * it answers as they are handed are the arguments.
	 */
	private Flow applied(Flow returns, List<LispVal> arguments, boolean summary) {
		if (returns.any || returns.params.isEmpty()) {
			return returns;
		}
		Flow flow = new Flow();
		flow.lambdas.addAll(returns.lambdas);
		flow.classes.addAll(returns.classes);
		for (int i = returns.params.nextSetBit(0); i >= 0; i = returns.params.nextSetBit(i + 1)) {
			flow.add(i < arguments.size() ? flowOf(arguments.get(i), summary) : Flow.ANY);
		}
		return flow.any ? Flow.ANY : flow;
	}

	/** What a call hands a function's parameters. */
	private void hands(Node callee, List<LispVal> arguments) {
		for (int i = 0; i < callee.flows.size() && i < arguments.size(); i++) {
			if (callee.flows.get(i).add(flowOf(arguments.get(i)))) {
				this.changed = true;
			}
		}
	}

	/**
	 * A variable or a slot assigned: it may then be what it is assigned, a parameter as
	 * well as what the calls hand it.
	 */
	private void assigned(LispVal place, LispVal value) {
		Flow into = null;
		if (place instanceof LispSymbol symbol) {
			Scope found = this.scope == null ? null : this.scope.find(symbol.name());
			if (found != null) {
				into = found.binding() != null
						? this.locals.computeIfAbsent(java.util.Objects.requireNonNull(found.binding()),
								key -> new Flow())
						: java.util.Objects.requireNonNull(found.function()).flows.get(found.parameter());
			}
			else if (isStatic(symbol)) {
				into = this.globals.get(symbol.name());
			}
		}
		else if (place instanceof LispCons) {
			String accessor = head(place);
			String slot = accessor == null || elements(place).size() != 2 ? null : this.accessors.get(accessor);
			if (slot != null) {
				into = this.slots.computeIfAbsent(slot, key -> new Flow());
			}
		}
		if (into != null && into.add(flowOf(value))) {
			this.changed = true;
		}
	}

	private void walk(Node node) {
		this.current = node;
		this.around.clear();
		this.taking.clear();
		this.blocks = node.enclosing;
		this.scope = Scope.parameters(node, node.captured);
		if (node.clauses != null) {
			// a dispatcher, arm by arm: what each reaches is the arm's own
			for (Clause clause : node.clauses) {
				this.clause = clause.node();
				elements(clause.form()).forEach(this::visit);
				List<LispVal> arm = elements(clause.form());
				if (clause.node().returns.add(flowOf(arm.get(arm.size() - 1), true))) {
					this.changed = true;
				}
			}
			this.clause = null;
		}
		else {
			node.body.forEach(this::visit);
		}
		if (node.name != null || node.lambda) {
			if (!node.body.isEmpty() && node.returns.add(flowOf(node.body.get(node.body.size() - 1), true))) {
				this.changed = true;
			}
		}
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
		if (!this.flowing) {
			LispVal entry = this.lockGuards.get(form);
			if (entry != null && lockRegion(entry, elements(entry))) {
				// the try after a lock(): the thread holds the lock in it, and in the
				// unlock() giving it back
				this.taking.push(entry);
				parts.subList(1, parts.size()).forEach(this::visit);
				this.taking.pop();
				return;
			}
			if (this.facts.acquired.containsKey(form)) {
				if (this.facts.lockRegions.containsKey(form) && lockRegion(form, parts)) {
					if (lockTaken(form, parts)) {
						return;
					}
				}
				else if (this.takenOtherwise.putIfAbsent(form, this.current) == null) {
					this.takenOtherwiseFound.add(form);
					this.changed = true;
				}
			}
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
		if ((operator.equals("SETQ") || operator.equals("SETF") || operator.equals("DEFVAR")) && parts.size() > 2) {
			parts.subList(1, parts.size()).forEach(this::visit);
			for (int i = 1; i + 1 < parts.size(); i += 2) {
				assigned(parts.get(i), parts.get(i + 1));
			}
			return;
		}
		if ((operator.equals("LET") || operator.equals("LET*")) && parts.size() > 1) {
			Scope saved = this.scope;
			Scope inside = this.scope;
			for (LispVal binding : elements(parts.get(1))) {
				List<LispVal> bound = elements(binding);
				if (bound.isEmpty() || !(bound.get(0) instanceof LispSymbol variable)) {
					continue;
				}
				LispVal init = bound.size() > 1 ? bound.get(1) : NIL;
				this.scope = operator.equals("LET") ? saved : inside;
				visit(init);
				if (this.locals.computeIfAbsent(binding, key -> new Flow()).add(flowOf(init))) {
					this.changed = true;
				}
				inside = new Scope(variable.name(), binding, null, -1, inside);
			}
			this.scope = inside;
			parts.subList(2, parts.size()).forEach(this::visit);
			this.scope = saved;
			return;
		}
		if (operator.equals("BLOCK") && parts.size() > 1) {
			Blocks saved = this.blocks;
			this.blocks = new Blocks(form, saved);
			parts.subList(2, parts.size()).forEach(this::visit);
			this.blocks = saved;
			return;
		}
		if (operator.equals("RETURN-FROM") && parts.size() > 1) {
			LispVal value = parts.size() > 2 ? parts.get(2) : NIL;
			visit(value);
			LispVal block = parts.get(1) instanceof LispSymbol name && this.blocks != null
					? this.blocks.find(name.name()) : null;
			if (block == null) {
				throw new IllegalStateException("a return-from no block around it takes: " + form.print());
			}
			if (this.answered.computeIfAbsent(block, key -> new Flow()).add(flowOf(value))) {
				this.changed = true;
			}
			return;
		}
		if (operator.equals("WHILE")) {
			parts.subList(1, parts.size()).forEach(this::visit);
			String culprit = this.around.isEmpty() && !this.flowing ? turning(parts) : null;
			if (culprit != null) {
				turn("a loop's turn: " + culprit);
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
						this.changed = true;
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
				boolean thrown = this.throwing;
				this.throwing |= operator.equals(THROW);
				operands.forEach(this::visit);
				this.throwing = thrown;
				if (operator.equals(SLEEP) || operator.equals(YIELD) || operator.equals(REPLY)) {
					suspends(form);
					general(operator.equals(SLEEP) ? "sleeps"
							: operator.equals(YIELD) ? "yields" : "waits for a reply");
				}
				else if (operator.equals(SOCKET_CONNECT) || operator.equals(SOCKET_ACCEPT)
						|| operator.equals(SOCKET_READ)) {
					suspends(form);
					general("waits for a socket");
				}
				else if (operator.equals(WAIT)) {
					suspends(form);
					waits(operands.get(0), "waits");
				}
				else if (operator.equals(ENTER)) {
					entered(form);
				}
				else if (operator.equals(VOLATILE) || operator.equals(INTERRUPTED)) {
					synchronizes(true, operator.equals(VOLATILE) ? "a volatile access" : "the interrupt's test");
					if (!this.current.library) {
						programSynchronizes();
					}
				}
				else if (operator.equals(INIT_BUSY)) {
					// the wait for a class another thread initializes: one there is
					// only when its initialization suspends, which is this function
					if (this.marking && this.current.resumable()) {
						this.suspensions.add(form);
					}
				}
				else if (operator.equals(THREAD_START) || operator.equals(THREAD_MAIN) || operator.equals(THREAD_ENDING)
						|| operator.equals(HTTP_REQUEST)) {
					// the bodies of the threads: the one a program starts, the one it
					// started on with what ends it, the program's end where it
					// registered something for then, a request of a server's
					List<Integer> bodies = operator.equals(THREAD_START) ? List.of(2) : operator.equals(THREAD_MAIN)
							? List.of(0, 1) : operator.equals(THREAD_ENDING) ? List.of(0, 1, 3) : List.of(0);
					for (int i : bodies) {
						if (i >= operands.size()) {
							continue;
						}
						LispVal lambda = lambdaOf(operands.get(i));
						Node body = lambda == null ? null : this.lambdas.get(lambda);
						if (body != null) {
							body.awaited = true;
						}
					}
					if (operator.equals(HTTP_REQUEST) && this.current.lambda) {
						// the function a transport is answered with: it waits for the
						// future a request still in hand answers it
						this.current.awaited = true;
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
		if (receiver.classes.isEmpty() || callee.clauses == null) {
			calls(call, arms, operands, false);
		}
		else {
			// an instance of one of some classes, or one of some lambdas: their arms
			for (String made : receiver.classes) {
				for (Node taken : taken(callee, made)) {
					calls(call, taken, operands, false);
				}
			}
			if (!receiver.lambdas.isEmpty()) {
				for (Node taken : callee.lambdaClauses) {
					calls(call, taken, operands, false);
				}
			}
		}
		for (LispVal funcall : callee.received) {
			for (Node lambda : called(funcall, receiver)) {
				calls(call, lambda, operands.subList(1, operands.size()), false);
				hands(lambda, operands.subList(1, operands.size()));
			}
		}
		if (this.marking && callee.resumable() && !this.suspensions.contains(call)) {
			this.unawaited.add(callee);
		}
	}

	/** The class a function's name begins with: its name up to the member's. */
	private static String owner(String name) {
		int open = name.indexOf('(');
		String member = open < 0 ? name : name.substring(0, open);
		int dot = member.lastIndexOf('.');
		return dot < 0 ? "" : member.substring(0, dot);
	}

	/** A synchronization action written outside the front end's own library. */
	private void programSynchronizes() {
		if (this.flowing || this.throwing) {
			return;
		}
		for (Node node : walked()) {
			if (!node.programSyncs) {
				node.programSyncs = true;
				this.changed = true;
			}
		}
	}

	/** A synchronization action, reached through calls by name alone or through any. */
	private void synchronizes(boolean byName, String reason) {
		if (this.flowing || this.throwing) {
			return;
		}
		for (Node node : walked()) {
			if (!node.syncs || (byName && !node.syncsByName)) {
				node.syncs = true;
				node.syncsByName |= byName;
				this.changed = true;
				if (byName || node.syncWhy == null) {
					node.syncWhy = reason;
				}
			}
		}
	}

	/**
	 * The function walked and, for a dispatcher outside its calls of its receiver, what
	 * it reaches without those calls.
	 */
	private List<Node> walked() {
		Node arms = this.current.arms;
		if (arms == null || this.receiving) {
			return List.of(this.current);
		}
		return this.clause == null ? List.of(this.current, arms) : List.of(this.current, arms, this.clause);
	}

	/**
	 * The lambdas a call through a function value reaches: of the ones the value may be,
	 * the ones made for the interface it is called through.
	 */
	private List<Node> called(LispVal funcall, Flow value) {
		if (this.facts.made.contains(funcall)) {
			return List.of();
		}
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
		calls(call, callee, arguments, byName, false);
	}

	/**
	 * A call reaching a function.
	 * @param taking whether it takes a {@code ReentrantLock} no suspended thread can
	 * hold: the wait of the callee's own, for another thread holding the lock, is none
	 */
	private void calls(LispVal call, Node callee, List<LispVal> arguments, boolean byName, boolean taking) {
		if (this.flowing) {
			return;
		}
		if (taking ? callee.general || callee.turns || !callee.waits.isEmpty() : callee.resumable()) {
			suspends(call);
		}
		if (callee.syncs && !initializes(callee)) {
			synchronizes(byName && callee.syncsByName, "calls " + readable(callee));
		}
		if (callee.programSyncs && !initializes(callee)) {
			programSynchronizes();
		}
		String reason = "calls " + readable(callee);
		if (callee.general || (callee.waitsOwn && !taking)) {
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

	/**
	 * Whether a function is a class's initialization: what it synchronizes it does once,
	 * at the class's first use, so a loop around a call of it polls nothing through it.
	 */
	private static boolean initializes(Node callee) {
		return callee.name != null && callee.name.endsWith(INITIALIZER);
	}

	private void suspends(LispVal call) {
		if (this.flowing) {
			return;
		}
		if (this.marking) {
			this.suspensions.add(call);
		}
	}

	/** A suspension that keeps every monitor the thread holds. */
	private void general(String reason) {
		if (this.flowing) {
			return;
		}
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
		holdLocks(reason);
	}

	/**
	 * The turn of a loop: given by a thread holding no monitor, which may hold a
	 * {@code ReentrantLock}.
	 */
	private void turn(String reason) {
		if (this.flowing) {
			return;
		}
		for (Node node : walked()) {
			if (!node.turns) {
				node.turns = true;
				this.changed = true;
				reasoned(node, reason);
			}
		}
		holdLocks(reason);
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
		if (this.flowing) {
			return;
		}
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
		// a ReentrantLock the thread holds it keeps
		holdLocks(reason);
		int parameter = waited instanceof LispSymbol symbol ? this.current.parameters.indexOf(symbol.name()) : -1;
		for (Node node : walked()) {
			if (given) {
				// the callers' monitors are kept, the region's own given up
				if (!node.waitsOwn) {
					node.waitsOwn = true;
					this.changed = true;
					reasoned(node, reason);
				}
			}
			else if (parameter < 0) {
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
		if (this.flowing) {
			return;
		}
		if (!this.blocking.contains(entry)) {
			Lock lock = this.facts.locks.get(entry);
			for (LispVal other : this.held) {
				if (Lock.mayBe(lock, this.facts.locks.get(other))) {
					this.blocking.add(entry);
					this.blockers.put(entry, other);
					this.changed = true;
					break;
				}
			}
		}
		if (this.blocking.contains(entry)) {
			suspends(entry);
			LispVal other = this.blockers.get(entry);
			general("enters a monitor " + readable(this.regions.get(other)) + " may hold where it "
					+ this.reasons.get(other));
		}
		synchronizes(true, "enters a monitor");
		if (!this.current.library) {
			programSynchronizes();
		}
	}

	/**
	 * Whether a {@code lock()} and the {@code try} after it are read as a region: the
	 * lock is one object at both ends -- a local the {@code try} does not assign, else a
	 * {@code final} field, as the lowering read it --, and both ends are a
	 * {@code ReentrantLock}'s own: called by name, or on what is a {@code ReentrantLock}
	 * and nothing else. Its {@code lock()} then waits only while another thread holds the
	 * lock, and its {@code unlock()} gives the lock back.
	 */
	private boolean lockRegion(LispVal entry, List<LispVal> parts) {
		Boolean known = this.lockRegions.get(entry);
		if (known != null) {
			return known;
		}
		LispVal guarded = java.util.Objects.requireNonNull(this.facts.lockRegions.get(entry));
		List<LispVal> protectedParts = elements(guarded);
		boolean region = parts.size() == 2 && is(guarded, "UNWIND-PROTECT") && protectedParts.size() == 3;
		if (region) {
			LispVal giving = protectedParts.get(2);
			List<LispVal> gave = elements(giving);
			LispVal lock = unwrap(parts.get(1));
			region = gave.size() == 2 && same(lock, unwrap(gave.get(1)))
					&& !(lock instanceof LispSymbol local && assigns(protectedParts.get(1), local.name()))
					&& ((REENTRANT_LOCK.equals(head(entry)) && REENTRANT_UNLOCK.equals(head(giving)))
							|| reentrant(flowOf(parts.get(1))));
		}
		this.lockRegions.put(entry, region);
		return region;
	}

	/** Whether a value is a {@code ReentrantLock} and nothing else, or {@code null}. */
	private static boolean reentrant(Flow flow) {
		return !flow.any && flow.lambdas.isEmpty() && flow.classes.equals(Set.of(REENTRANT));
	}

	/**
	 * The {@code lock()} of a lock region: a wait only where a suspended thread may hold
	 * the lock -- a region of a lock that may be the same object held across a
	 * suspension, or such a lock taken where no region gives it back --, else what it
	 * reaches but that wait, the lock taken at once. Answers whether the call is read:
	 * one that may wait is read as any call.
	 */
	private boolean lockTaken(LispVal entry, List<LispVal> parts) {
		this.lockOwners.put(entry, this.current);
		if (!this.locksBlocking.containsKey(entry)) {
			String blocked = lockBlocker(entry);
			if (blocked != null) {
				this.locksBlocking.put(entry, blocked);
				this.changed = true;
			}
		}
		String blocked = this.locksBlocking.get(entry);
		if (blocked != null) {
			suspends(entry);
			general(blocked);
			return false;
		}
		List<LispVal> operands = parts.subList(1, parts.size());
		operands.forEach(this::visit);
		Node lock = this.named.get(REENTRANT_LOCK);
		if (lock != null) {
			calls(entry, lock, operands, true, true);
			hands(lock, operands);
		}
		if (this.marking && !this.suspensions.contains(entry)) {
			this.taken.add(entry);
			Node dispatcher = this.named.get(java.util.Objects.requireNonNull(head(entry)));
			if (dispatcher != null && dispatcher != lock && dispatcher.resumable()) {
				// a dispatcher answering what the lock() answers, unawaited
				this.unawaited.add(dispatcher);
			}
		}
		return true;
	}

	/**
	 * What may hold the lock a lock region's {@code lock()} takes, said as its reason, or
	 * {@code null}.
	 */
	private @Nullable String lockBlocker(LispVal entry) {
		Lock lock = this.facts.acquired.get(entry);
		for (LispVal held : this.locksHeldFound) {
			if (Lock.mayBe(lock, this.facts.acquired.get(held))) {
				return "takes a lock " + readable(this.lockOwners.get(held)) + " may hold where it "
						+ this.locksHeld.get(held);
			}
		}
		for (LispVal other : this.takenOtherwiseFound) {
			if (Lock.mayBe(lock, this.facts.acquired.get(other))) {
				return "takes a lock " + readable(this.takenOtherwise.get(other)) + " takes where no try gives it back";
			}
		}
		return null;
	}

	/** A lock region around the form walked held across a suspension. */
	private void holdLocks(String reason) {
		for (LispVal entry : this.taking) {
			if (!this.locksHeld.containsKey(entry)) {
				this.locksHeld.put(entry, reason);
				this.locksHeldFound.add(entry);
				this.changed = true;
			}
		}
	}

	/**
	 * Whether a form reaches a synchronization action, a lambda it makes aside: a
	 * {@code volatile} access, a monitor's entry, the test of the interrupt.
	 * @param through whether a dispatcher and a function value are followed too
	 */
	private @Nullable String reaches(LispVal form, boolean through, Set<Object> walks) {
		return reaches(form, through, walks, false);
	}

	/**
	 * Whether a form reaches a synchronization action, a lambda it makes aside.
	 * @param program whether only one written outside the front end's own library counts,
	 * through any call
	 */
	private @Nullable String reaches(LispVal form, boolean through, Set<Object> walks, boolean program) {
		if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null || is(form, THROW)) {
			// what a throw throws decides no loop: the throw goes whatever it reads
			return null;
		}
		Object asked = this.facts.asks.get(form);
		if (asked != null && walks.contains(asked)) {
			// the test of a walk: it asks for more of what the loop takes, as an
			// enhanced for does, which a dispatcher would read as every iterator
			return null;
		}
		String operator = head(form);
		List<LispVal> parts = elements(form);
		String where = through ? "its test " : "its body ";
		if (operator != null) {
			if (!program && (operator.equals(ENTER) || operator.equals(INTERRUPTED) || operator.equals(VOLATILE))) {
				return where + (operator.equals(ENTER) ? "enters a monitor"
						: operator.equals(VOLATILE) ? "makes a volatile access" : "tests the interrupt");
			}
			Node callee = this.named.get(operator);
			if (callee != null && !initializes(callee) && (program ? callee.programSyncs
					: through ? callee.syncs : callee.syncsByName && !this.facts.dispatchers.contains(operator))) {
				return where + "calls " + operator + " (" + callee.syncWhy + ")";
			}
			if (through && operator.equals("FUNCALL") && parts.size() > 1) {
				for (Node node : called(form, flowOf(function(parts.get(1))))) {
					if (program ? node.programSyncs : node.syncs) {
						return where + "calls " + readable(node) + " (" + node.syncWhy + ")";
					}
				}
			}
		}
		for (LispVal part : parts) {
			String found = reaches(part, through, walks, program);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/**
	 * Why a loop outside every region gives the other threads a turn, or {@code null}:
	 * none for a loop its own progress ends ({@link #bounded}); a loop of the front end's
	 * own library when its test reaches a synchronization action the program wrote,
	 * through any call -- the library's own fields and monitors keep its state, and where
	 * it waits it waits; any other loop when its test reaches one through any call, or
	 * its body reaches one through calls by name and the loop does not decide its own end
	 * ({@link #decided}).
	 */
	private @Nullable String turning(List<LispVal> parts) {
		Set<Object> walks = Collections.newSetFromMap(new IdentityHashMap<>());
		for (LispVal part : parts.subList(2, parts.size())) {
			taken(part, walks);
		}
		if (bounded(parts, walks)) {
			return null;
		}
		if (this.current.library) {
			return reaches(parts.get(1), true, walks, true);
		}
		String culprit = reaches(parts.get(1), true, walks);
		if (culprit != null) {
			return culprit;
		}
		for (int i = 2; culprit == null && i < parts.size(); i++) {
			culprit = reaches(parts.get(i), false, walks);
		}
		return culprit == null || decided(parts, walks) ? null : culprit;
	}

	/**
	 * A local assigned in a loop or an exit of it, with the tests it is taken under and
	 * whether it is inside a region.
	 *
	 * @param variable the local, {@code null} for an exit
	 * @param value what is stored, {@code null} for an exit
	 * @param guards the tests the assignment or the exit is taken under
	 * @param held whether a monitor is entered around it
	 * @param bound whether the local is bound there, not assigned
	 */
	private record Guarded(@Nullable String variable, @Nullable LispVal value, List<LispVal> guards, boolean held,
			boolean bound) {
	}

	/**
	 * Whether a loop decides its own end: its test reads nothing a thread can write but
	 * the loop's own locals, no local such a test reads is assigned in the loop from what
	 * a thread can write, inside a region or under a test that reads such a thing, no
	 * exit of the loop is taken there either, and the loop moves toward its end -- its
	 * test or the test of an exit reads a local the loop assigns. Then a synchronization
	 * action in its body cannot be what ends it: what a thread writes reaches none of its
	 * decisions. A loop that cannot end so
	 * ({@code while (true) counter.incrementAndGet();}) is one whose turns the other
	 * threads need.
	 */
	private boolean decided(List<LispVal> parts, Set<Object> walks) {
		List<Guarded> assigned = new ArrayList<>();
		List<Guarded> exits = new ArrayList<>();
		for (LispVal part : parts.subList(1, parts.size())) {
			guarded(part, List.of(), false, new ArrayDeque<>(), assigned, exits);
		}
		Set<String> tainted = new HashSet<>();
		boolean moved = true;
		while (moved) {
			moved = false;
			for (Guarded assignment : assigned) {
				String variable = java.util.Objects.requireNonNull(assignment.variable());
				if (!tainted.contains(variable) && (assignment.held()
						|| !pure(java.util.Objects.requireNonNull(assignment.value()), tainted, walks)
						|| !assignment.guards().stream().allMatch(guard -> pure(guard, tainted, walks)))) {
					tainted.add(variable);
					moved = true;
				}
			}
		}
		if (!pure(parts.get(1), tainted, walks)) {
			return false;
		}
		// what the loop assigns, and what it binds of that at every round
		Set<String> moving = new HashSet<>();
		for (Guarded assignment : assigned) {
			if (!assignment.bound()) {
				moving.add(java.util.Objects.requireNonNull(assignment.variable()));
			}
		}
		moved = true;
		while (moved) {
			moved = false;
			for (Guarded assignment : assigned) {
				if (assignment.bound() && reads(java.util.Objects.requireNonNull(assignment.value()), moving)) {
					moved |= moving.add(java.util.Objects.requireNonNull(assignment.variable()));
				}
			}
		}
		boolean moves = reads(parts.get(1), moving);
		for (Guarded exit : exits) {
			if (exit.held() || !exit.guards().stream().allMatch(guard -> pure(guard, tainted, walks))) {
				return false;
			}
			moves |= exit.guards().stream().anyMatch(guard -> reads(guard, moving));
		}
		return moves;
	}

	/** Whether a form reads one of some locals, its lambdas aside. */
	private static boolean reads(LispVal form, Set<String> locals) {
		if (form instanceof LispSymbol symbol) {
			return locals.contains(symbol.name());
		}
		if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null) {
			return false;
		}
		for (LispVal part : elements(form)) {
			if (reads(part, locals)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a loop ends by its own progress, whatever another thread writes meanwhile:
	 * a conjunct of its test asks for more of an iterator its body takes from, as an
	 * enhanced {@code for} does, or keeps a counter below a bound where the loop raises
	 * the counter by a constant step at every round and assigns it nowhere else, as a
	 * {@code for} over indexes does. Another thread's write can end such a loop sooner,
	 * never keep it going.
	 */
	private boolean bounded(List<LispVal> parts, Set<Object> walks) {
		List<LispVal> conjuncts = new ArrayList<>();
		conjuncts(parts.get(1), conjuncts);
		for (LispVal conjunct : conjuncts) {
			Object asked = this.facts.asks.get(conjunct);
			if ((asked != null && walks.contains(asked)) || counts(conjunct, parts)) {
				return true;
			}
		}
		return false;
	}

	/** What a test holds that is false when it is: the operands of a conjunction. */
	private static void conjuncts(LispVal test, List<LispVal> into) {
		List<LispVal> parts = elements(test);
		if (is(test, "AND") || (is(test, "IF") && parts.size() == 4 && parts.get(3) == NIL)) {
			int end = is(test, "AND") ? parts.size() : 3;
			parts.subList(1, end).forEach(part -> conjuncts(part, into));
			return;
		}
		into.add(test);
	}

	/**
	 * Whether a test keeps a counter below a bound, the counter raised at every round of
	 * the loop and assigned nowhere else in it.
	 */
	private static boolean counts(LispVal compared, List<LispVal> loop) {
		List<LispVal> operands = elements(compared);
		if (operands.size() != 3 || !(is(compared, "<") || is(compared, "<="))
				|| !(operands.get(1) instanceof LispSymbol counter) || !local(counter)) {
			return false;
		}
		int raised = 0;
		for (LispVal part : loop.subList(2, loop.size())) {
			raised += raises(part, counter) ? 1 : 0;
		}
		return raised == 1 && assignments(loop.subList(1, loop.size()), counter.name()) == 1;
	}

	/**
	 * Whether a form raises a counter by a constant step: {@code (setq i (+ i 1))}, its
	 * mask aside.
	 */
	private static boolean raises(LispVal form, LispSymbol counter) {
		List<LispVal> parts = elements(form);
		if (!is(form, "SETQ") || parts.size() != 3 || !same(parts.get(1), counter)) {
			return false;
		}
		LispVal value = parts.get(2);
		if (is(value, "%MASK-SIGNED-FIELD") && elements(value).size() == 3) {
			value = elements(value).get(2);
		}
		List<LispVal> sum = elements(value);
		return is(value, "+") && sum.size() == 3
				&& ((same(sum.get(1), counter) && step(sum.get(2))) || (step(sum.get(1)) && same(sum.get(2), counter)));
	}

	private static boolean step(LispVal form) {
		return form instanceof LispInteger number && number.value() > 0;
	}

	/** How many assignments of a local some forms hold, their lambdas aside. */
	private static int assignments(List<LispVal> forms, String variable) {
		int found = 0;
		for (LispVal form : forms) {
			if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null) {
				continue;
			}
			List<LispVal> parts = elements(form);
			if (is(form, "SETQ") || is(form, "SETF")) {
				for (int i = 1; i < parts.size(); i += 2) {
					found += parts.get(i) instanceof LispSymbol assigned && assigned.name().equals(variable) ? 1 : 0;
				}
			}
			found += assignments(parts, variable);
		}
		return found;
	}

	/**
	 * Gathers the locals a form assigns and the exits it takes from the loop it stands
	 * in, each with the tests it is taken under.
	 * @param blocks the blocks established inside the loop, which an exit to is none
	 */
	private void guarded(LispVal form, List<LispVal> guards, boolean held, Deque<String> blocks, List<Guarded> assigned,
			List<Guarded> exits) {
		if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null) {
			return;
		}
		String operator = head(form);
		List<LispVal> parts = elements(form);
		LispVal[] region = region(form);
		if (region != null) {
			guarded(region[0], guards, held, blocks, assigned, exits);
			guarded(region[1], guards, true, blocks, assigned, exits);
			return;
		}
		if (operator == null) {
			parts.forEach(part -> guarded(part, guards, held, blocks, assigned, exits));
			return;
		}
		switch (operator) {
			case "IF", "AND", "OR", "WHILE" -> {
				// what follows the first part is taken under it, and an operand of and
				// and or under the ones before it
				List<LispVal> under = new ArrayList<>(guards);
				for (int i = 1; i < parts.size(); i++) {
					guarded(parts.get(i), under, held, blocks, assigned, exits);
					if (i == 1 || operator.equals("AND") || operator.equals("OR")) {
						under = new ArrayList<>(under);
						under.add(parts.get(i));
					}
				}
			}
			case "COND" -> {
				List<LispVal> under = new ArrayList<>(guards);
				for (LispVal clause : parts.subList(1, parts.size())) {
					List<LispVal> arm = elements(clause);
					if (arm.isEmpty()) {
						continue;
					}
					guarded(arm.get(0), under, held, blocks, assigned, exits);
					under = new ArrayList<>(under);
					under.add(arm.get(0));
					for (LispVal part : arm.subList(1, arm.size())) {
						guarded(part, under, held, blocks, assigned, exits);
					}
				}
			}
			case "HANDLER-CASE" -> {
				// a clause runs where the form raised: under what the form reads
				guarded(parts.get(1), guards, held, blocks, assigned, exits);
				List<LispVal> under = new ArrayList<>(guards);
				under.add(parts.get(1));
				for (LispVal clause : parts.subList(2, parts.size())) {
					elements(clause).subList(1, elements(clause).size())
						.forEach(part -> guarded(part, under, held, blocks, assigned, exits));
				}
			}
			case "BLOCK" -> {
				blocks.push(String.valueOf(parts.get(1)));
				parts.subList(2, parts.size()).forEach(part -> guarded(part, guards, held, blocks, assigned, exits));
				blocks.pop();
			}
			case "RETURN-FROM" -> {
				parts.subList(2, parts.size()).forEach(part -> guarded(part, guards, held, blocks, assigned, exits));
				if (!blocks.contains(String.valueOf(parts.get(1)))) {
					exits.add(new Guarded(null, null, guards, held, false));
				}
			}
			case "SETQ" -> {
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					guarded(parts.get(i + 1), guards, held, blocks, assigned, exits);
					if (parts.get(i) instanceof LispSymbol variable) {
						assigned.add(new Guarded(variable.name(), parts.get(i + 1), guards, held, false));
					}
				}
			}
			case "LET", "LET*" -> {
				for (LispVal binding : elements(parts.get(1))) {
					List<LispVal> bound = elements(binding);
					if (bound.size() == 2 && bound.get(0) instanceof LispSymbol variable) {
						guarded(bound.get(1), guards, held, blocks, assigned, exits);
						assigned.add(new Guarded(variable.name(), bound.get(1), guards, held, true));
					}
				}
				parts.subList(2, parts.size()).forEach(part -> guarded(part, guards, held, blocks, assigned, exits));
			}
			default -> {
				parts.subList(1, parts.size()).forEach(part -> guarded(part, guards, held, blocks, assigned, exits));
				if (operator.equals(THROW)) {
					exits.add(new Guarded(null, null, guards, held, false));
				}
			}
		}
	}

	/**
	 * Which named functions are pure ({@link Node#pure}): the greatest fixed point, so a
	 * function calling itself is one.
	 */
	private void purity() {
		for (Node node : this.nodes) {
			node.pure = node.name != null && !node.lambda && !this.facts.dispatchers.contains(node.name);
		}
		boolean moved = true;
		while (moved) {
			moved = false;
			for (Node node : this.nodes) {
				if (node.pure && !node.body.stream().allMatch(form -> pure(form, Set.of(), Set.of()))) {
					node.pure = false;
					moved = true;
				}
			}
		}
	}

	/**
	 * Whether a form reads nothing a thread can write: locals but the tainted ones,
	 * constants, an iterator a loop walks asked for more, the operators of {@link #PURE},
	 * pure functions, and the forms that only order these.
	 */
	private boolean pure(LispVal form, Set<String> tainted, Set<Object> walks) {
		if (form instanceof LispSymbol symbol) {
			return local(symbol) ? !tainted.contains(symbol.name()) : constant(symbol);
		}
		if (!(form instanceof LispCons) || is(form, "QUOTE")) {
			return true;
		}
		Object asked = this.facts.asks.get(form);
		if (asked != null && walks.contains(asked)) {
			return true;
		}
		String operator = head(form);
		if (operator == null) {
			return false;
		}
		List<LispVal> parts = elements(form);
		switch (operator) {
			case "DECLARE":
				return true;
			case "THE":
				return parts.size() == 3 && pure(parts.get(2), tainted, walks);
			case "BLOCK", "RETURN-FROM":
				return parts.subList(2, parts.size()).stream().allMatch(part -> pure(part, tainted, walks));
			case "LET", "LET*":
				for (LispVal binding : elements(parts.get(1))) {
					List<LispVal> bound = elements(binding);
					if (bound.size() == 2 && !pure(bound.get(1), tainted, walks)) {
						return false;
					}
				}
				return parts.subList(2, parts.size()).stream().allMatch(part -> pure(part, tainted, walks));
			case "SETQ":
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					if (!(parts.get(i) instanceof LispSymbol variable) || !local(variable)
							|| !pure(parts.get(i + 1), tainted, walks)) {
						return false;
					}
				}
				return true;
			case "COND":
				return parts.subList(1, parts.size())
					.stream()
					.allMatch(clause -> elements(clause).stream().allMatch(part -> pure(part, tainted, walks)));
			case "IF", "AND", "OR", "PROGN", "WHILE":
				return parts.subList(1, parts.size()).stream().allMatch(part -> pure(part, tainted, walks));
			default:
				break;
		}
		Node callee = this.named.get(operator);
		if (!PURE.contains(operator) && (callee == null || !callee.pure)) {
			return false;
		}
		return parts.subList(1, parts.size()).stream().allMatch(part -> pure(part, tainted, walks));
	}

	/**
	 * Whether a symbol names a local: the lowering's globals have a class's name ahead of
	 * a dot, the run time's a package ahead of a colon.
	 */
	private static boolean local(LispSymbol symbol) {
		String name = symbol.name();
		return !constant(symbol) && name.indexOf('.') < 0 && name.indexOf(':') < 0;
	}

	private static boolean constant(LispSymbol symbol) {
		String name = symbol.name();
		return name.equals("T") || name.equals("NIL") || name.startsWith(":");
	}

	/** Adds the iterators a loop's body takes the next element of, its lambdas aside. */
	private void taken(LispVal form, Set<Object> walks) {
		if (!(form instanceof LispCons) || is(form, "QUOTE") || lambdaOf(form) != null) {
			return;
		}
		Object iterator = this.facts.takes.get(form);
		if (iterator != null) {
			walks.add(iterator);
		}
		elements(form).forEach(part -> taken(part, walks));
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

	/**
	 * A form no suspension is in: the lambdas it makes are rewritten, and the
	 * {@code lock()} of a lock no suspended thread can hold is checked to have taken it.
	 */
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
		LispVal descended = same ? form : list(rewritten);
		if (this.taken.contains(form)) {
			// what the lock() answers where it took the lock at once: what a resumable
			// function answers, a future settled, or the program is refused by name
			return list(LOCKED, descended);
		}
		return descended;
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
			case "THE":
				// an await stands in no the: what it asserts a resumable body has no
				// fused site to read
				return spine(parts.get(2));
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
				// once in so many turns the other threads go on
				LispVal counted = list(cl("<"), list(cl("setq"), SLICE, list(cl("-"), SLICE, integer(1))), integer(0));
				if (this.turning.contains(form) && parts.subList(1, parts.size()).stream().noneMatch(this::needs)) {
					return turns(parts, counted);
				}
				List<LispVal> rewritten = new ArrayList<>(parts.size());
				rewritten.add(parts.get(0));
				LispVal test = spine(parts.get(1));
				if (this.turning.contains(form)) {
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
	 * A loop whose one suspension is its turn: a function of its own, no body a thread is
	 * suspended in, which runs the loop until it ends or its turns are used up and says
	 * which; the thread lets the others go on between two calls of it. So the loop is
	 * compiled as any loop is -- a body a thread is resumed in keeps no number unboxed --
	 * and what it assigns it shares with the function around it. An exit of a block
	 * around the loop says where it goes and is taken once the function has returned.
	 */
	private LispVal turns(List<LispVal> parts, LispVal counted) {
		LispSymbol run = temporary();
		LispSymbol left = temporary();
		LispSymbol exit = temporary();
		LispSymbol value = temporary();
		Map<String, Integer> exits = new LinkedHashMap<>();
		List<LispVal> loop = new ArrayList<>(parts.size() + 1);
		loop.add(parts.get(0));
		for (LispVal part : parts.subList(1, parts.size())) {
			loop.add(descend(leaving(part, new HashSet<>(), exits, exit, value, left)));
		}
		loop.add(list(cl("if"), counted, list(cl("return-from"), left, JavaForms.T)));
		LispVal function = list(cl("lambda"), NIL, list(cl("block"), left, list(loop), NIL));
		LispVal turning = list(cl("let"), list(list(run, function)),
				list(cl("while"), list(cl("funcall"), run), await(list(runtime("suspending-turn")))));
		if (exits.isEmpty()) {
			return turning;
		}
		List<LispVal> ends = new ArrayList<>();
		ends.add(cl("cond"));
		for (Map.Entry<String, Integer> named : exits.entrySet()) {
			ends.add(list(list(cl("eql"), exit, integer(named.getValue())),
					list(cl("return-from"), new LispSymbol(named.getKey()), value)));
		}
		return list(cl("let"), list(list(exit, integer(0)), list(value, NIL)), turning, list(ends));
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
		if (this.taken.contains(form)) {
			this.taken.add(copy);
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
