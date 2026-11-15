package am.ik.rontolisp.starlark;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.starlark.StarlarkBuiltins.Signature;
import am.ik.rontolisp.starlark.StarlarkSyntax.Argument;
import am.ik.rontolisp.starlark.StarlarkSyntax.ArgumentKind;
import am.ik.rontolisp.starlark.StarlarkSyntax.Assignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.AugmentedAssignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.Binary;
import am.ik.rontolisp.starlark.StarlarkSyntax.BinaryOperator;
import am.ik.rontolisp.starlark.StarlarkSyntax.Break;
import am.ik.rontolisp.starlark.StarlarkSyntax.BytesLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Call;
import am.ik.rontolisp.starlark.StarlarkSyntax.Clause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Conditional;
import am.ik.rontolisp.starlark.StarlarkSyntax.Continue;
import am.ik.rontolisp.starlark.StarlarkSyntax.Def;
import am.ik.rontolisp.starlark.StarlarkSyntax.DictComprehension;
import am.ik.rontolisp.starlark.StarlarkSyntax.DictLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Dot;
import am.ik.rontolisp.starlark.StarlarkSyntax.Entry;
import am.ik.rontolisp.starlark.StarlarkSyntax.Expression;
import am.ik.rontolisp.starlark.StarlarkSyntax.ExpressionStatement;
import am.ik.rontolisp.starlark.StarlarkSyntax.FloatLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.For;
import am.ik.rontolisp.starlark.StarlarkSyntax.ForClause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Identifier;
import am.ik.rontolisp.starlark.StarlarkSyntax.If;
import am.ik.rontolisp.starlark.StarlarkSyntax.IfClause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Index;
import am.ik.rontolisp.starlark.StarlarkSyntax.IntLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Lambda;
import am.ik.rontolisp.starlark.StarlarkSyntax.ListComprehension;
import am.ik.rontolisp.starlark.StarlarkSyntax.ListLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Load;
import am.ik.rontolisp.starlark.StarlarkSyntax.LoadBinding;
import am.ik.rontolisp.starlark.StarlarkSyntax.Parameter;
import am.ik.rontolisp.starlark.StarlarkSyntax.Pass;
import am.ik.rontolisp.starlark.StarlarkSyntax.Return;
import am.ik.rontolisp.starlark.StarlarkSyntax.Slice;
import am.ik.rontolisp.starlark.StarlarkSyntax.Statement;
import am.ik.rontolisp.starlark.StarlarkSyntax.StringLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.TupleLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Unary;
import am.ik.rontolisp.starlark.StarlarkSyntax.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Lowers a Starlark syntax tree to the Common Lisp core forms the pipeline already
 * consumes, so a Starlark program joins {@code CompileFrontend.expand} and
 * {@code LispEvaluator} unchanged and no backend learns a new operator. The lowering
 * table and the decisions behind each row: {@code .kb/starlark-frontend.md}.
 *
 * <p>
 * The shape of the pass:
 * <ul>
 * <li><b>One file at a time, whole file at once.</b> A name is resolved statically: to
 * the innermost function or comprehension that binds it anywhere in its body, else to the
 * file's globals, else to a built-in. A name bound nowhere is an error at its position
 * before anything runs.</li>
 * <li><b>A {@code load} statement's module is lowered with the file that loads it.</b>
 * The module is a lowering of its own whose globals are spelled behind its path, so two
 * files may define one name; its definitions join the program's and its statements stand
 * where the first {@code load} of it does, behind a state that runs them once per
 * program. What a {@code load} binds is a global of the loading file, assigned the
 * module's. A module spelled {@code @package} is a Common Lisp package: the name bound is
 * that symbol's function.</li>
 * <li><b>A file's run is bracketed.</b> Every list and dict made between its first and
 * its last statement is frozen after the last.</li>
 * <li><b>A session's buffers are one file's run</b> ({@link #interact}): each buffer is
 * lowered against the globals the buffers before it bound, nothing is frozen, and no call
 * of a global is direct, since a later buffer may bind the name again.</li>
 * <li><b>Two call tiers.</b> A call whose callee and argument shape are known while
 * lowering -- a top-level {@code def} bound once, a built-in function, a method -- is a
 * direct call of a Common Lisp function with every parameter in place: the arguments
 * bound to the parameters here, by position and by name, a default taken from where the
 * {@code def} statement left its value. Any other call hands the callee, the positional
 * arguments and the named ones to the run-time binder
 * ({@code rontolisp::%starlark-call}). Both tiers evaluate the arguments in source
 * order.</li>
 * <li><b>Control flow is lexical.</b> A function body is a {@code block} around a
 * {@code let} of every name it assigns, a {@code for} a {@code tagbody} over the
 * iterable's vector -- over a counter when the iterable is a call of {@code range} --,
 * {@code return} / {@code break} / {@code continue} a {@code return-from}; no exit
 * crosses a {@code lambda}. A {@code return} inside loops ends each loop's count on the
 * iterable itself before it leaves, so no {@code unwind-protect} is needed.</li>
 * <li><b>An operator is generic unless its operands are known.</b> The lowering knows an
 * expression to be an int or a Boolean from its spelling and from the bindings of the
 * variables it reads ({@link Kind}): a variable is an int when every binding of it in its
 * block is one. An arithmetic operator or a comparison over two ints is the Common Lisp
 * operator; over operands that may be ints it is that operator behind a test of them, the
 * generic helper otherwise; over one that is no int it is the helper alone.</li>
 * <li><b>A read is checked only where it can come before the assignment.</b> A
 * definite-assignment pass runs with the lowering: a name assigned on every path to a
 * read is read as it stands, any other read goes through
 * {@code rontolisp::%starlark-local} or {@code -global}, which refuses the unassigned
 * marker. A function's body starts with what was assigned where the function value was
 * made: a variable is never unassigned again.</li>
 * <li><b>Every statement and expression form is located</b> at its node's position, as a
 * reader locates what it reads: recorded in the compile path's table, a
 * {@link LocatedCons} for the interpreter, so an uncaught error names the line it
 * happened on.</li>
 * <li><b>Truth is fused into tests.</b> A comparison, {@code not} and the Boolean
 * constants answer the Common Lisp booleans, so a condition built of them is tested as it
 * stands; only a value of unknown type goes through
 * {@code rontolisp::%starlark-truth}.</li>
 * </ul>
 */
final class StarlarkLowering {

	private static final LispSymbol QUOTE = new LispSymbol("QUOTE");

	private static final LispSymbol FUNCTION = new LispSymbol("FUNCTION");

	private static final LispSymbol LAMBDA = new LispSymbol("LAMBDA");

	private static final LispSymbol DEFUN = new LispSymbol("DEFUN");

	private static final LispSymbol SETQ = new LispSymbol("SETQ");

	private static final LispSymbol LET = new LispSymbol("LET");

	private static final LispSymbol LET_STAR = new LispSymbol("LET*");

	private static final LispSymbol PROGN = new LispSymbol("PROGN");

	private static final LispSymbol IF = new LispSymbol("IF");

	private static final LispSymbol AND = new LispSymbol("AND");

	private static final LispSymbol OR = new LispSymbol("OR");

	private static final LispSymbol NOT = new LispSymbol("NOT");

	private static final LispSymbol EQ = new LispSymbol("EQ");

	private static final LispSymbol BLOCK = new LispSymbol("BLOCK");

	private static final LispSymbol RETURN_FROM = new LispSymbol("RETURN-FROM");

	private static final LispSymbol TAGBODY = new LispSymbol("TAGBODY");

	private static final LispSymbol GO = new LispSymbol("GO");

	private static final LispSymbol LIST = new LispSymbol("LIST");

	private static final LispSymbol APPEND = new LispSymbol("APPEND");

	private static final LispSymbol AREF = new LispSymbol("AREF");

	private static final LispSymbol LENGTH = new LispSymbol("LENGTH");

	private static final LispSymbol LESS = new LispSymbol("<");

	private static final LispSymbol GREATER = new LispSymbol(">");

	private static final LispSymbol LESS_EQUAL = new LispSymbol("<=");

	private static final LispSymbol GREATER_EQUAL = new LispSymbol(">=");

	private static final LispSymbol NUMBER_EQUAL = new LispSymbol("=");

	private static final LispSymbol PLUS = new LispSymbol("+");

	private static final LispSymbol MINUS = new LispSymbol("-");

	private static final LispSymbol TIMES = new LispSymbol("*");

	private static final LispSymbol MOD = new LispSymbol("MOD");

	private static final LispSymbol FLOOR = new LispSymbol("FLOOR");

	private static final LispSymbol VALUES = new LispSymbol("VALUES");

	private static final LispSymbol ASH = new LispSymbol("ASH");

	private static final LispSymbol LOGAND = new LispSymbol("LOGAND");

	private static final LispSymbol LOGIOR = new LispSymbol("LOGIOR");

	private static final LispSymbol LOGXOR = new LispSymbol("LOGXOR");

	private static final LispSymbol LOGNOT = new LispSymbol("LOGNOT");

	private static final LispSymbol INTEGERP = new LispSymbol("INTEGERP");

	private static final LispSymbol VECTOR_PUSH_EXTEND = new LispSymbol("VECTOR-PUSH-EXTEND");

	private static final LispSymbol DEFVAR = new LispSymbol("DEFVAR");

	private static final LispSymbol CONSP = new LispSymbol("CONSP");

	/** The block a function body is: what {@code return} leaves. */
	private static final LispSymbol FUNCTION_BLOCK = new LispSymbol("%SL-FN");

	/** The block a loop is: what {@code break} leaves. */
	private static final LispSymbol BREAK_BLOCK = new LispSymbol("%SL-BREAK");

	/** The block a loop's body is: what {@code continue} leaves. */
	private static final LispSymbol CONTINUE_BLOCK = new LispSymbol("%SL-CONTINUE");

	private static final LispSymbol TRUTH = runtime("TRUTH");

	private static final LispSymbol CALL = runtime("CALL");

	private static final LispSymbol STAR_ELEMENTS = runtime("STAR-ELEMENTS");

	private static final LispSymbol KEYWORDS = runtime("KEYWORDS");

	private static final LispSymbol MAKE_FUNCTION = runtime("MAKE-FUNCTION");

	private static final LispSymbol BUILTIN = runtime("BUILTIN");

	private static final LispSymbol METHOD = runtime("METHOD");

	private static final LispSymbol NO_ATTRIBUTE = runtime("NO-ATTRIBUTE");

	private static final LispSymbol SET_ATTRIBUTE = runtime("SET-ATTRIBUTE");

	private static final LispSymbol MAKE_LIST = runtime("MAKE-LIST");

	private static final LispSymbol MAKE_TUPLE = runtime("MAKE-TUPLE");

	private static final LispSymbol MAKE_DICT = runtime("MAKE-DICT");

	private static final LispSymbol EMPTY_DICT = runtime("EMPTY-DICT");

	private static final LispSymbol LIST_OF = runtime("LIST-OF");

	private static final LispSymbol TUPLE_OF = runtime("TUPLE-OF");

	private static final LispSymbol DICT_OF = runtime("DICT-OF");

	/** Builds a list, tuple or dict expression of constants from its quoted form. */
	private static final LispSymbol LITERAL = runtime("LITERAL");

	private static final LispSymbol LIST_MARK = new LispSymbol(":%LIST");

	private static final LispSymbol TUPLE_MARK = new LispSymbol(":%TUPLE");

	private static final LispSymbol DICT_MARK = new LispSymbol(":%DICT");

	private static final LispSymbol LOOP_STOP = runtime("LOOP-STOP");

	private static final LispSymbol LOOP_STEP = runtime("LOOP-STEP");

	private static final LispSymbol VECTOR = runtime("VECTOR");

	private static final LispSymbol LIST_FROM = runtime("LIST-FROM");

	private static final LispSymbol INDEX = runtime("INDEX");

	private static final LispSymbol SET_INDEX = runtime("SET-INDEX");

	private static final LispSymbol SLICE = runtime("SLICE");

	private static final LispSymbol UNPACK = runtime("UNPACK");

	private static final LispSymbol ITERATE = runtime("ITERATE");

	private static final LispSymbol ITERATED = runtime("ITERATED");

	private static final LispSymbol EQUAL = runtime("EQUAL");

	private static final LispSymbol IN = runtime("IN");

	private static final LispSymbol ADD_IN_PLACE = runtime("ADD-IN-PLACE");

	private static final LispSymbol LOCAL = runtime("LOCAL");

	private static final LispSymbol GLOBAL = runtime("GLOBAL");

	private static final LispSymbol ENTER = runtime("ENTER");

	private static final LispSymbol LEAVE = runtime("LEAVE");

	private static final LispSymbol FILE_BEGIN = runtime("FILE-BEGIN");

	private static final LispSymbol FILE_END = runtime("FILE-END");

	private static final LispSymbol LOADED = runtime("LOADED");

	private static final LispSymbol FOREIGN = runtime("FOREIGN");

	private static final LispSymbol UNWIND = runtime("UNWIND");

	/** The cell the lists and dicts made now are made under ("Freezing"). */
	private static final LispSymbol MUTABILITY = runtime("MUTABILITY");

	/** What a {@code load} statement's module starts with when it is a package. */
	private static final String PACKAGE_MARK = "@";

	/** What a variable holds until it is assigned: a value no program can make. */
	private static final LispSymbol UNBOUND = new LispSymbol(":%UNBOUND");

	private static final Map<String, LispVal> CONSTANTS = Map.of("None", StarlarkBuiltins.NONE, "True",
			LispTrue.INSTANCE, "False", LispNil.INSTANCE);

	/**
	 * The operators that are the Common Lisp one behind a test of their operands, where
	 * the operands may be ints: what a counting loop is written with. The bitwise
	 * operators are the Common Lisp ones over known ints only.
	 */
	private static final Set<BinaryOperator> TESTED = Set.of(BinaryOperator.PLUS, BinaryOperator.MINUS,
			BinaryOperator.MULTIPLY, BinaryOperator.FLOOR_DIVIDE, BinaryOperator.MODULO, BinaryOperator.EQUALS,
			BinaryOperator.NOT_EQUALS, BinaryOperator.LESS, BinaryOperator.LESS_EQUALS, BinaryOperator.GREATER,
			BinaryOperator.GREATER_EQUALS);

	/**
	 * What the files of one lowering share: the program's file and every module its
	 * {@code load} statements reach.
	 */
	private static final class Session {

		private final StarlarkStandard standard;

		private final StarlarkFiles files;

		/** The modules lowered so far, by {@link StarlarkNames#key}. */
		private final Map<String, Module> modules = new HashMap<>();

		/** The files being lowered, by key: loading one of them is a cycle. */
		private final Deque<String> lowering = new ArrayDeque<>();

		/**
		 * Every file's definitions -- the direct entries' {@code defun}s, a module's
		 * state -- which go ahead of every statement of the program.
		 */
		private final List<LispVal> definitions = new ArrayList<>();

		/** Whether the program's file is a session's buffer ({@link Interaction}). */
		private final boolean interactive;

		Session(StarlarkStandard standard, StarlarkFiles files, boolean interactive) {
			this.standard = standard;
			this.files = files;
			this.interactive = interactive;
		}

	}

	/**
	 * What the buffers of one session share: the modules loaded so far, the globals bound
	 * so far and whether the session's run has begun. A buffer whose lowering fails
	 * leaves it as it was.
	 */
	static final class Interaction {

		private final Session session;

		/** The globals the buffers lowered so far bind. */
		private final Set<String> globals = new LinkedHashSet<>();

		/** The last number a generated global took: the session's flags are globals. */
		private int counter;

		/** Whether a buffer started the session's run. */
		private boolean begun;

		Interaction(StarlarkStandard standard, StarlarkFiles files) {
			this.session = new Session(standard, files, true);
		}

	}

	/** A file a {@code load} statement read, lowered. */
	private static final class Module {

		/** The globals another file may load, by name. */
		private final Map<String, Export> exports;

		/** The statements, each behind the module's state. */
		private final List<LispVal> forms;

		/** Whether a {@code load} statement of this lowering already runs them. */
		private boolean emitted;

		Module(Map<String, Export> exports, List<LispVal> forms) {
			this.exports = exports;
			this.forms = forms;
		}

	}

	/**
	 * A global a module exports.
	 *
	 * @param symbol its variable, which is its function too when it has a direct entry
	 * @param assigned whether every path through the module assigns it
	 * @param signature how its direct entry is called, or {@code null} with none
	 */
	private record Export(LispSymbol symbol, boolean assigned, @Nullable Signature signature) {
	}

	/**
	 * A function a call enters directly.
	 *
	 * @param function its Common Lisp name
	 * @param signature the parameters a {@code def} takes -- a default the constant it
	 * is, else the global its {@code def} statement leaves the value in --, or
	 * {@code null} for a Common Lisp function, which takes any number of positional
	 * arguments
	 */
	private record Callee(LispSymbol function, @Nullable Signature signature) {
	}

	/**
	 * What a top-level {@code load} statement does where it stands.
	 *
	 * @param module the module whose statements run there first, or {@code null} for a
	 * package
	 * @param locals the names it binds
	 * @param assignments the assignment of each, in order
	 */
	private record Loaded(@Nullable Module module, List<String> locals, List<LispVal> assignments) {
	}

	private final Session session;

	/** The names the file binds at its top level. */
	private final Set<String> globals = new LinkedHashSet<>();

	/**
	 * The top-level functions of this file with a direct entry: a {@code def} standing at
	 * the top level, the only binding of its name in the file.
	 */
	private final Map<String, Def> direct = new LinkedHashMap<>();

	/**
	 * The names a call enters directly: this file's {@link #direct} ones, and what a
	 * {@code load} statement alone binds to a module's direct entry or to a Common Lisp
	 * function.
	 */
	private final Map<String, Callee> callees = new HashMap<>();

	/** What each top-level {@code load} statement does. */
	private final Map<Load, Loaded> loads = new IdentityHashMap<>();

	/** The globals only {@code load} statements bind: no other file loads them. */
	private final Set<String> loadedOnly = new HashSet<>();

	private final StarlarkStandard standard;

	/** The file being lowered, or {@code null} when unknown. */
	private final @Nullable String file;

	/** Whether the file is a {@code load} statement's module, with globals of its own. */
	private final boolean module;

	/**
	 * Whether the file is a session's buffer: a later buffer may bind any of its globals
	 * again, so no call of one is direct.
	 */
	private final boolean interactive;

	/** What a global of the file starts with: nothing unless it is a module. */
	private final String globalPrefix;

	/**
	 * What a global the lowering generates starts with: the file's prefix, which two
	 * files of one program do not share.
	 */
	private final String filePrefix;

	/**
	 * The file's state: the cell its lists and dicts are made under while it runs. A
	 * module's is a variable no run resets: unset before its first run and {@code T}
	 * after it, so its statements run once per program.
	 */
	private final LispSymbol state;

	/** What the file's globals are known to hold; nothing in a session's buffer. */
	private final Map<String, Kind> globalKinds = new HashMap<>();

	/** The globals some read checks: each starts as {@link #UNBOUND}. */
	private final Set<String> checkedGlobals = new LinkedHashSet<>();

	/**
	 * Under {@link StarlarkStandard#SPEC}, one variable per function declaration: a cons
	 * whose car is true while a call of it runs.
	 */
	private final List<LispSymbol> active = new ArrayList<>();

	/** What is assigned where the lowering stands. */
	private Flow flow = new Flow(new HashSet<>(), false);

	private int counter;

	/** The compile path's unit the forms are recorded in, or {@code null}. */
	private final SourceProvenance.@Nullable Unit unit;

	/**
	 * The file each form is located in as a {@link LocatedCons}, or {@code null}: a named
	 * file lowered with no compile-path scope open, i.e. the interpreter's.
	 */
	private final @Nullable String runtimeFile;

	/** The offset each line of the source starts at, for {@link #unit}. */
	private final int[] lineStarts;

	private StarlarkLowering(Session session, String source, @Nullable String file, boolean module) {
		this.session = session;
		this.standard = session.standard;
		this.file = file;
		this.module = module;
		this.interactive = session.interactive && !module;
		this.filePrefix = file == null ? "" : StarlarkNames.filePrefix(file);
		this.globalPrefix = module ? this.filePrefix : "";
		this.state = new LispSymbol(this.filePrefix + "%SL-FILE");
		this.unit = SourceProvenance.unit(file, source);
		this.runtimeFile = this.unit == null ? file : null;
		List<Integer> starts = new ArrayList<>(List.of(0));
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Lowers a file.
	 * @param file the file's statements
	 * @param standard what the file is read against
	 * @param source the file's text, which the positions are in
	 * @param name the origin file, or {@code null} when unknown
	 * @param files where a {@code load} statement's module is read from
	 * @return the top-level forms
	 * @throws LispReadException on a name no binding or built-in answers, on a construct
	 * the front end does not have yet, on one the standard refuses and on a module that
	 * cannot be loaded, at its position
	 */
	static List<LispVal> lower(List<Statement> file, StarlarkStandard standard, String source, @Nullable String name,
			StarlarkFiles files) {
		Session session = new Session(standard, files, false);
		if (name != null) {
			session.lowering.push(StarlarkNames.key(name));
		}
		return new StarlarkLowering(session, source, name, false).program(file);
	}

	/**
	 * Lowers one buffer of a session. The globals the buffers before it bound are its
	 * globals too, read through the check -- such a buffer may have stopped before it
	 * assigned one -- and the run the first buffer began goes on: nothing is frozen at a
	 * buffer's end.
	 * @param interaction what the session's buffers share
	 * @param statements the buffer's statements
	 * @param source the buffer's text, which the positions are in
	 * @return the buffer's steps: the first sets the run up, then one per statement, an
	 * expression statement's echoed
	 * @throws LispReadException as {@link #lower}, leaving the session as it was
	 */
	static List<StarlarkTopLevel> interact(Interaction interaction, List<Statement> statements, String source) {
		Session session = interaction.session;
		Map<String, Module> modules = new HashMap<>(session.modules);
		// Every buffer loading a module emits its statements again: the buffer that
		// emitted them first may have stopped before they ran. Its state runs them once.
		session.modules.values().forEach(module -> module.emitted = false);
		try {
			StarlarkLowering lowering = new StarlarkLowering(session, source, null, false);
			lowering.globals.addAll(interaction.globals);
			lowering.counter = interaction.counter;
			List<StarlarkTopLevel> steps = lowering.buffer(statements, interaction);
			interaction.globals.addAll(lowering.globals);
			interaction.counter = lowering.counter;
			interaction.begun = true;
			return steps;
		}
		catch (RuntimeException ex) {
			session.modules.clear();
			session.modules.putAll(modules);
			throw ex;
		}
		finally {
			session.definitions.clear();
			session.lowering.clear();
		}
	}

	/**
	 * A form located at a node's position: recorded in the compile path's table, or for
	 * the interpreter answered as a located copy -- so the caller uses the value
	 * returned.
	 */
	private LispVal located(LispVal form, SourceLocation at) {
		if (!(form instanceof LispCons cons) || form instanceof LocatedCons) {
			return form;
		}
		if (this.runtimeFile != null) {
			return new LocatedCons(cons.car(), cons.cdr(), this.runtimeFile, at.line());
		}
		if (this.unit != null && at.line() >= 1 && at.line() <= this.lineStarts.length) {
			SourceProvenance.record(cons, this.unit, this.lineStarts[at.line() - 1] + at.column() - 1);
		}
		return form;
	}

	/**
	 * The program's file: the definitions of every file of the lowering, then its
	 * statements between the two forms that bracket its run.
	 */
	private List<LispVal> program(List<Statement> statements) {
		List<LispVal> body = forms(body(statements));
		// Every direct entry ahead of the first statement: a defun exists from the start
		// in a compiled program and only once evaluated in the interpreter, so a call
		// written above its def would otherwise run on three backends and fail on one.
		// Such a call checks the global its def assigns, as any read before it does.
		List<LispVal> forms = new ArrayList<>(this.session.definitions);
		forms.add(list(SETQ, this.state, list(FILE_BEGIN)));
		forms.addAll(prelude());
		forms.addAll(body);
		forms.add(list(FILE_END, this.state));
		return List.copyOf(forms);
	}

	/**
	 * A {@code load} statement's file: its statements behind its state, which runs them
	 * once per program however often they are emitted -- a file loaded by two files
	 * lowered apart is emitted by both -- and what another file may load of it.
	 */
	private Module module(List<Statement> statements) {
		List<LispVal> body = forms(body(statements));
		Map<String, Export> exports = new LinkedHashMap<>();
		for (String name : this.globals) {
			if (this.loadedOnly.contains(name) || name.startsWith("_")) {
				continue;
			}
			// A session's buffer may load a module whose run stopped at an error: what
			// it loads is checked then.
			boolean assigned = this.flow.assigned.contains(name) && !this.session.interactive;
			if (!assigned) {
				// a load of it reads it through the check, so it starts unassigned
				this.checkedGlobals.add(name);
			}
			Callee callee = this.direct.containsKey(name) ? this.callees.get(name) : null;
			exports.put(name, new Export(global(name), assigned, callee == null ? null : callee.signature()));
		}
		this.session.definitions.add(list(DEFVAR, this.state, LispNil.INSTANCE));
		List<LispVal> forms = new ArrayList<>();
		// A state that is set -- the module ran, or stopped at an error -- stays set.
		forms.add(list(SETQ, this.state, list(IF, this.state, LispTrue.INSTANCE, list(FILE_BEGIN))));
		prelude().forEach(form -> forms.add(guarded(form)));
		forms.addAll(body);
		forms.add(guarded(list(SETQ, this.state, list(FILE_END, this.state))));
		return new Module(exports, List.copyOf(forms));
	}

	/**
	 * A session's buffer: a step setting the run up, then the statements' steps. The
	 * setup gives back what an error in an earlier buffer left counted
	 * ({@code %starlark-unwind}), makes the session's cell current again -- an error in a
	 * module's run left the module's -- or, in the first buffer, begins the run, and
	 * starts the globals the buffer binds first unassigned.
	 */
	private List<StarlarkTopLevel> buffer(List<Statement> statements, Interaction interaction) {
		List<StarlarkTopLevel> body = body(statements);
		List<LispVal> setup = new ArrayList<>(this.session.definitions);
		setup.add(list(UNWIND, LispNil.INSTANCE));
		setup.add(interaction.begun ? list(SETQ, MUTABILITY, this.state) : list(SETQ, this.state, list(FILE_BEGIN)));
		for (String name : this.globals) {
			if (!interaction.globals.contains(name)) {
				setup.add(list(SETQ, global(name), UNBOUND));
			}
		}
		for (LispSymbol flag : this.active) {
			setup.add(list(SETQ, flag, list(LIST, LispNil.INSTANCE)));
		}
		List<StarlarkTopLevel> steps = new ArrayList<>();
		steps.add(new StarlarkTopLevel(setup, false));
		steps.addAll(body);
		return steps;
	}

	private static List<LispVal> forms(List<StarlarkTopLevel> steps) {
		List<LispVal> forms = new ArrayList<>();
		steps.forEach(step -> forms.addAll(step.forms()));
		return forms;
	}

	/**
	 * A top-level statement's form where it runs: as it stands in a program, behind the
	 * state in a module, which holds a cons exactly while the module's first run lasts.
	 */
	private LispVal guarded(LispVal form) {
		return this.module ? list(IF, list(CONSP, this.state), form) : form;
	}

	/** What a file's run starts with: the checked globals unassigned, no call active. */
	private List<LispVal> prelude() {
		List<LispVal> forms = new ArrayList<>();
		for (String name : this.checkedGlobals) {
			forms.add(list(SETQ, global(name), UNBOUND));
		}
		for (LispSymbol flag : this.active) {
			forms.add(list(SETQ, flag, list(LIST, LispNil.INSTANCE)));
		}
		return forms;
	}

	/**
	 * The file's statements as top-level forms, in order, one step per statement: a
	 * {@code load} statement is its module's statements, the first time the lowering
	 * meets the module, then the assignments of what it binds. The files it loads are
	 * lowered first -- a function above a {@code load} statement calls what it binds --
	 * and the definitions go to the session. At a session's prompt an expression
	 * statement is the one step echoed, a literal included.
	 */
	private List<StarlarkTopLevel> body(List<Statement> statements) {
		if (this.standard == StarlarkStandard.SPEC) {
			specTopLevel(statements);
		}
		Map<String, Integer> bound = new LinkedHashMap<>();
		bindings(statements, bound);
		this.globals.addAll(bound.keySet());
		Map<String, Integer> loaded = new HashMap<>();
		if (!this.interactive) {
			// a later buffer of a session may bind a global to anything
			Map<String, List<Supplier<Kind>>> kinds = new LinkedHashMap<>();
			bound(statements, Scope.TOP, kinds);
			infer(kinds, List.of(), this.globalKinds);
		}
		for (Statement statement : statements) {
			if (statement instanceof Def def && !this.interactive && bound.getOrDefault(def.name(), 0) == 1) {
				this.direct.put(def.name(), def);
				this.callees.put(def.name(), new Callee(global(def.name()), signature(def)));
			}
			else if (statement instanceof Load load) {
				load.bindings().forEach(binding -> loaded.merge(binding.local(), 1, Integer::sum));
				this.loads.put(load, load(load, bound));
			}
		}
		loaded.forEach((name, count) -> {
			if (count.equals(bound.get(name))) {
				this.loadedOnly.add(name);
			}
		});
		List<StarlarkTopLevel> steps = new ArrayList<>();
		Frame frame = new Frame(Scope.TOP);
		for (Statement statement : statements) {
			if (statement instanceof Load load) {
				Loaded bindings = Objects.requireNonNull(this.loads.get(load));
				Module module = bindings.module();
				List<LispVal> forms = new ArrayList<>();
				if (module != null && !module.emitted) {
					module.emitted = true;
					forms.addAll(module.forms);
				}
				bindings.assignments().forEach(assignment -> forms.add(guarded(assignment)));
				this.flow.assigned.addAll(bindings.locals());
				steps.add(new StarlarkTopLevel(forms, false));
				continue;
			}
			ExpressionStatement shown = this.interactive && statement instanceof ExpressionStatement expression
					? expression : null;
			LispVal form = shown != null ? expression(shown.expression(), Scope.TOP) : statement(statement, frame);
			if (form != null) {
				steps.add(new StarlarkTopLevel(List.of(guarded(located(form, statement.at()))), shown != null));
			}
		}
		return steps;
	}

	// ---- load ----

	/**
	 * What a {@code load} statement binds: a module's globals, or the functions of a
	 * Common Lisp package when the module is spelled {@code @package}.
	 * @param load the statement
	 * @param bound how often the file binds each name
	 */
	private Loaded load(Load load, Map<String, Integer> bound) {
		boolean foreign = load.module().startsWith(PACKAGE_MARK);
		Module module = foreign ? null : module(load);
		List<String> locals = new ArrayList<>();
		List<LispVal> assignments = new ArrayList<>();
		for (LoadBinding binding : load.bindings()) {
			String local = binding.local();
			String remote = binding.remote();
			if (!StarlarkNames.isIdentifier(local)) {
				throw error("load: \"" + local + "\" is no identifier: write NAME = \"" + local + "\"", binding.at());
			}
			boolean only = bound.getOrDefault(local, 0) == 1;
			LispVal value;
			if (module == null) {
				LispSymbol function = foreignFunction(load, binding);
				value = list(FOREIGN, string(load.module().substring(PACKAGE_MARK.length()) + ":" + remote),
						list(FUNCTION, function));
				if (only) {
					this.callees.put(local, new Callee(function, null));
				}
			}
			else {
				if (remote.startsWith("_")) {
					throw error("load: names with leading underscores are not exported: " + remote, binding.at());
				}
				Export export = module.exports.get(remote);
				if (export == null) {
					throw error("load: name " + remote + " not found in module " + load.module(), binding.at());
				}
				value = export.assigned() ? export.symbol()
						: list(LOADED, export.symbol(), string(remote), string(load.module()));
				if (only && export.signature() != null) {
					this.callees.put(local, new Callee(export.symbol(), export.signature()));
				}
			}
			locals.add(local);
			assignments.add(located(list(SETQ, global(local), value), binding.at()));
		}
		return new Loaded(module, locals, assignments);
	}

	/** The module a {@code load} statement names, lowered once per lowering. */
	private Module module(Load load) {
		String path = load.module();
		StarlarkFiles.Source source = this.session.files.find(this.file, path);
		if (source == null) {
			throw error("cannot load " + path + ": file not found", load.at());
		}
		String key = StarlarkNames.key(source.path());
		if (this.session.lowering.contains(key)) {
			throw error("cannot load " + path + ": cycle in load graph", load.at());
		}
		Module module = this.session.modules.get(key);
		if (module == null) {
			this.session.lowering.push(key);
			module = new StarlarkLowering(this.session, source.text(), source.path(), true)
				.module(StarlarkParser.parse(source.text(), source.path()));
			this.session.lowering.pop();
			this.session.modules.put(key, module);
		}
		return module;
	}

	/**
	 * The Common Lisp function a {@code load} of a package names: the symbol the string
	 * spells as the Common Lisp reader reads it -- upcased, verbatim between vertical
	 * bars -- in the package after the {@code @}.
	 */
	private static LispSymbol foreignFunction(Load load, LoadBinding binding) {
		String pkg = load.module().substring(PACKAGE_MARK.length()).toUpperCase(Locale.ROOT);
		String name = binding.remote();
		if (pkg.isEmpty() || name.isEmpty()) {
			throw error("load: " + (pkg.isEmpty() ? "no package after @" : "empty name"), binding.at());
		}
		String member = name.length() > 2 && name.startsWith("|") && name.endsWith("|")
				? name.substring(1, name.length() - 1) : name.toUpperCase(Locale.ROOT);
		return new LispSymbol(pkg.equals("CL-USER") ? member : pkg + "::" + member);
	}

	/** A global of this file: behind the file's prefix when the file is a module. */
	private LispSymbol global(String name) {
		return new LispSymbol(this.globalPrefix + StarlarkNames.mangle(name));
	}

	/** The variable a name is where it is written: a local, or a global of the file. */
	private LispSymbol variable(String name, Scope scope) {
		return scope.binds(name) ? StarlarkNames.symbol(name) : global(name);
	}

	/**
	 * The specification's top-level rules: no {@code if} or {@code for}, and a global
	 * bound once.
	 */
	private static void specTopLevel(List<Statement> statements) {
		Map<String, SourceLocation> declared = new HashMap<>();
		Set<String> loaded = new HashSet<>();
		for (Statement statement : statements) {
			switch (statement) {
				case If conditional -> throw error("if statement not within a function", conditional.at());
				case For loop -> throw error("for loop not within a function", loop.at());
				case Assignment assignment -> declare(assignment.target(), declared, loaded);
				case AugmentedAssignment assignment -> declare(assignment.target(), declared, loaded);
				case Def def -> declare(def.name(), def.at(), declared, loaded);
				case Load load -> {
					for (LoadBinding binding : load.bindings()) {
						if (declared.putIfAbsent(binding.local(), binding.at()) != null) {
							throw error("cannot reassign top-level " + binding.local(), binding.at());
						}
						loaded.add(binding.local());
					}
				}
				default -> {
				}
			}
		}
	}

	private static void declare(Expression target, Map<String, SourceLocation> declared, Set<String> loaded) {
		switch (target) {
			case Identifier identifier -> declare(identifier.name(), identifier.at(), declared, loaded);
			case TupleLiteral tuple -> tuple.elements().forEach(element -> declare(element, declared, loaded));
			case ListLiteral list -> list.elements().forEach(element -> declare(element, declared, loaded));
			default -> {
			}
		}
	}

	// A name a load statement binds is the file's own, no global: Go's "local".
	private static void declare(String name, SourceLocation at, Map<String, SourceLocation> declared,
			Set<String> loaded) {
		SourceLocation first = declared.putIfAbsent(name, at);
		if (first != null) {
			throw error(
					"cannot reassign " + (loaded.contains(name) ? "local " : "global ") + name + " declared at "
							+ (first.file() == null ? "" : first.file() + ":") + first.line() + ":" + first.column(),
					at);
		}
	}

	// ---- definite assignment ----

	/**
	 * The names assigned on every path to where the lowering stands, or {@code dead} when
	 * no path reaches it (after a {@code return}, {@code break} or {@code continue}),
	 * where every name counts as assigned. Names, not bindings: a function or
	 * comprehension takes its own names off when it starts.
	 */
	private static final class Flow {

		private final Set<String> assigned;

		private boolean dead;

		Flow(Set<String> assigned, boolean dead) {
			this.assigned = assigned;
			this.dead = dead;
		}

		Flow copy() {
			return new Flow(new HashSet<>(this.assigned), this.dead);
		}

		boolean has(String name) {
			return this.dead || this.assigned.contains(name);
		}

		/** Where two paths join: what both assigned, a dead one contributing nothing. */
		static Flow join(Flow a, Flow b) {
			if (a.dead) {
				return b;
			}
			if (!b.dead) {
				a.assigned.retainAll(b.assigned);
			}
			return a;
		}

	}

	/**
	 * A read of a variable: as it stands where it is assigned on every path, else through
	 * the check that refuses {@link #UNBOUND}.
	 */
	private LispVal read(String name, Scope scope) {
		LispSymbol symbol = variable(name, scope);
		if (this.flow.has(name)) {
			return symbol;
		}
		boolean local = scope.binds(name);
		if (!local) {
			this.checkedGlobals.add(name);
		}
		return list(local ? LOCAL : GLOBAL, symbol, string(name));
	}

	/**
	 * Enters a function or comprehension body: what is assigned there at first is what is
	 * assigned where it is made, less the names it binds itself, plus those it is handed.
	 * @return the flow to restore when the body is done
	 */
	private Flow enter(Set<String> own, List<String> handed) {
		Flow outer = this.flow;
		this.flow = outer.copy();
		this.flow.assigned.removeAll(own);
		this.flow.assigned.addAll(handed);
		return outer;
	}

	// ---- scopes ----

	/**
	 * The names a function or a comprehension binds, inside the scope it stands in, and
	 * what those of them hold that the lowering knows ({@link #infer}).
	 */
	private record Scope(@Nullable Scope parent, Set<String> names, Map<String, Kind> kinds) {

		/** The file's top level: its names are the globals, no local. */
		static final Scope TOP = new Scope(null, Set.of(), Map.of());

		boolean binds(String name) {
			for (Scope scope = this; scope != null; scope = scope.parent) {
				if (scope.names.contains(name)) {
					return true;
				}
			}
			return false;
		}

		Scope inner(Set<String> names) {
			return new Scope(this, names, new HashMap<>());
		}

	}

	/**
	 * Where a statement stands: the scope its names resolve in and the loops of its own
	 * function around it -- those holding an iterable, innermost first, by the variable
	 * holding it, and how many there are in all.
	 */
	private static final class Frame {

		private final Scope scope;

		private final Deque<LispSymbol> loops = new ArrayDeque<>();

		private int depth;

		Frame(Scope scope) {
			this.scope = scope;
		}

	}

	/** Counts, per name, the bindings these statements make in their own block. */
	private static void bindings(List<Statement> statements, Map<String, Integer> out) {
		for (Statement statement : statements) {
			switch (statement) {
				case Assignment assignment -> targetNames(assignment.target(), out);
				case AugmentedAssignment assignment -> targetNames(assignment.target(), out);
				case For loop -> {
					targetNames(loop.target(), out);
					bindings(loop.body(), out);
				}
				case If conditional -> {
					bindings(conditional.then(), out);
					bindings(conditional.otherwise(), out);
				}
				case Def def -> out.merge(def.name(), 1, Integer::sum);
				case Load load -> load.bindings().forEach(binding -> out.merge(binding.local(), 1, Integer::sum));
				default -> {
				}
			}
		}
	}

	private static void targetNames(Expression target, Map<String, Integer> out) {
		switch (target) {
			case Identifier identifier -> out.merge(identifier.name(), 1, Integer::sum);
			case TupleLiteral tuple -> tuple.elements().forEach(element -> targetNames(element, out));
			case ListLiteral list -> list.elements().forEach(element -> targetNames(element, out));
			default -> {
			}
		}
	}

	private LispVal reference(Identifier identifier, Scope scope) {
		String name = identifier.name();
		if (isVariable(name, scope)) {
			return read(name, scope);
		}
		LispVal constant = CONSTANTS.get(name);
		if (constant != null) {
			return constant;
		}
		Signature builtin = StarlarkBuiltins.function(name);
		if (builtin != null) {
			return builtinValue(builtin);
		}
		throw error("undefined: " + name, identifier.at());
	}

	private boolean isVariable(String name, Scope scope) {
		return scope.binds(name) || this.globals.contains(name);
	}

	/** The built-in function the name is where it is written, or {@code null}. */
	private @Nullable Signature builtin(Expression callee, Scope scope) {
		return callee instanceof Identifier identifier && !isVariable(identifier.name(), scope)
				&& !CONSTANTS.containsKey(identifier.name()) ? StarlarkBuiltins.function(identifier.name()) : null;
	}

	// ---- kinds ----

	/**
	 * What the lowering knows of a value before the program runs: that it is an int, that
	 * it is {@code True} or {@code False} (the Common Lisp booleans), that it is of some
	 * other type -- no int --, or nothing.
	 */
	private enum Kind {

		INT, BOOL, OTHER, ANY;

		/** The kind of a value that is of either kind. */
		static Kind join(Kind a, Kind b) {
			if (a == b) {
				return a;
			}
			return a.mayBeInt() || b.mayBeInt() ? ANY : OTHER;
		}

		boolean mayBeInt() {
			return this == INT || this == ANY;
		}

	}

	/** The kind of an expression's value, when the expression answers one. */
	private Kind kind(Expression expression, Scope scope) {
		return switch (expression) {
			case IntLiteral _ -> Kind.INT;
			case Identifier identifier -> kind(identifier.name(), scope);
			case Unary unary -> {
				Kind operand = kind(unary.operand(), scope);
				yield unary.operator() == UnaryOperator.NOT ? Kind.BOOL
						: operand == Kind.INT ? Kind.INT : operand == Kind.ANY ? Kind.ANY : Kind.OTHER;
			}
			case Binary binary -> {
				Kind left = kind(binary.left(), scope);
				Kind right = kind(binary.right(), scope);
				yield switch (binary.operator()) {
					case AND, OR -> Kind.join(left, right);
					case EQUALS, NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS, IN, NOT_IN -> Kind.BOOL;
					case DIVIDE -> Kind.OTHER;
					// two ints answer an int; with an operand of another type the
					// operator answers no int: a float, a string, a list
					default -> left == Kind.INT && right == Kind.INT ? Kind.INT
							: left.mayBeInt() && right.mayBeInt() ? Kind.ANY : Kind.OTHER;
				};
			}
			case Conditional conditional ->
				Kind.join(kind(conditional.then(), scope), kind(conditional.otherwise(), scope));
			case Call call -> {
				Signature signature = call.function() instanceof Dot dot ? StarlarkBuiltins.method(dot.name())
						: builtin(call.function(), scope);
				yield signature == null ? Kind.ANY : switch (signature.result()) {
					case "int" -> Kind.INT;
					case "bool" -> Kind.BOOL;
					default -> Kind.ANY;
				};
			}
			case Dot _, Index _ -> Kind.ANY;
			case FloatLiteral _, StringLiteral _, BytesLiteral _, Lambda _, ListLiteral _, TupleLiteral _,
					DictLiteral _, ListComprehension _, DictComprehension _, Slice _ ->
				Kind.OTHER;
		};
	}

	/** The kind of what a name holds where it is written. */
	private Kind kind(String name, Scope scope) {
		for (Scope binding = scope; binding != null; binding = binding.parent()) {
			if (binding.names().contains(name)) {
				return binding.kinds().getOrDefault(name, Kind.ANY);
			}
		}
		if (this.globals.contains(name)) {
			return this.globalKinds.getOrDefault(name, Kind.ANY);
		}
		// None and a built-in function are no ints
		return name.equals("True") || name.equals("False") ? Kind.BOOL : Kind.OTHER;
	}

	/**
	 * Collects, per name, the kind of each value these statements bind it to in their own
	 * block, asked once the block's own kinds are being settled.
	 */
	private void bound(List<Statement> statements, Scope scope, Map<String, List<Supplier<Kind>>> out) {
		for (Statement statement : statements) {
			switch (statement) {
				case Assignment assignment -> bound(assignment.target(), assignment.value(), scope, out);
				case AugmentedAssignment assignment -> {
					if (assignment.target() instanceof Identifier identifier) {
						Binary applied = new Binary(assignment.operator(), identifier, assignment.value(),
								assignment.at());
						out.computeIfAbsent(identifier.name(), name -> new ArrayList<>())
							.add(() -> kind(applied, scope));
					}
				}
				case For loop -> {
					element(loop.target(), loop.iterable(), scope, out);
					bound(loop.body(), scope, out);
				}
				case If conditional -> {
					bound(conditional.then(), scope, out);
					bound(conditional.otherwise(), scope, out);
				}
				case Def def -> out.computeIfAbsent(def.name(), name -> new ArrayList<>()).add(() -> Kind.OTHER);
				case Load load -> load.bindings()
					.forEach(binding -> out.computeIfAbsent(binding.local(), name -> new ArrayList<>())
						.add(() -> Kind.ANY));
				default -> {
				}
			}
		}
	}

	/**
	 * A target bound to a value: a name to the value's kind, the names of a tuple or list
	 * of targets to the elements of a tuple or list expression of as many, and to
	 * anything when the value is not one ({@code null}: unknown).
	 */
	private void bound(Expression target, @Nullable Expression value, Scope scope,
			Map<String, List<Supplier<Kind>>> out) {
		List<Expression> targets = elements(target);
		if (target instanceof Identifier identifier) {
			out.computeIfAbsent(identifier.name(), name -> new ArrayList<>())
				.add(value == null ? () -> Kind.ANY : () -> kind(value, scope));
		}
		else if (targets != null) {
			List<Expression> values = value == null ? null : elements(value);
			for (int i = 0; i < targets.size(); i++) {
				bound(targets.get(i), values != null && values.size() == targets.size() ? values.get(i) : null, scope,
						out);
			}
		}
	}

	/**
	 * A loop's target bound to each element of its iterable: an int for a call of
	 * {@code range}, a pair starting with an int for one of {@code enumerate}.
	 */
	private void element(Expression target, Expression iterable, Scope scope, Map<String, List<Supplier<Kind>>> out) {
		Signature producer = iterable instanceof Call call ? builtin(call.function(), scope) : null;
		String name = producer == null ? "" : producer.name();
		List<Expression> targets = elements(target);
		if (target instanceof Identifier identifier && name.equals("range")) {
			out.computeIfAbsent(identifier.name(), key -> new ArrayList<>()).add(() -> Kind.INT);
		}
		else if (targets != null && targets.size() == 2 && targets.get(0) instanceof Identifier index
				&& name.equals("enumerate")) {
			out.computeIfAbsent(index.name(), key -> new ArrayList<>()).add(() -> Kind.INT);
			bound(targets.get(1), null, scope, out);
		}
		else {
			bound(target, null, scope, out);
		}
	}

	/** The elements of a tuple or list expression, or {@code null} for any other. */
	private static @Nullable List<Expression> elements(Expression expression) {
		return switch (expression) {
			case TupleLiteral tuple -> tuple.elements();
			case ListLiteral list -> list.elements();
			default -> null;
		};
	}

	/**
	 * Settles what a block's names hold: a name is an int when every value the block
	 * binds it to is one, given the names assumed so -- the assumption is dropped name by
	 * name until it holds --, and a Boolean likewise. A name the block is handed (a
	 * parameter) holds anything. Nothing outside the block binds its names: a nested
	 * function reads them, a session's later buffer is asked for no global.
	 * @param bound what {@link #bound} collected of the block
	 * @param handed the names the block is handed
	 * @param kinds the block's kinds, filled here; the collected bindings read it
	 */
	private static void infer(Map<String, List<Supplier<Kind>>> bound, List<String> handed, Map<String, Kind> kinds) {
		for (Kind assumed : List.of(Kind.INT, Kind.BOOL)) {
			for (String name : bound.keySet()) {
				if (!handed.contains(name)) {
					kinds.putIfAbsent(name, assumed);
				}
			}
			boolean settled = false;
			while (!settled) {
				settled = true;
				for (Map.Entry<String, List<Supplier<Kind>>> binding : bound.entrySet()) {
					if (kinds.get(binding.getKey()) == assumed
							&& !binding.getValue().stream().allMatch(value -> value.get() == assumed)) {
						kinds.remove(binding.getKey());
						settled = false;
					}
				}
			}
		}
	}

	// ---- functions ----

	/**
	 * A parameter list by what each parameter takes: positional (or named) arguments,
	 * named ones only, the surplus positional ones, the surplus named ones.
	 */
	private static final class Parameters {

		private final List<String> positional = new ArrayList<>();

		private final List<String> keywordOnly = new ArrayList<>();

		/** Parallel to {@link #filled()}: {@code null} where an argument is needed. */
		private final List<@Nullable Expression> defaults = new ArrayList<>();

		private @Nullable String varargs;

		private @Nullable String kwargs;

		Parameters(List<Parameter> declared) {
			boolean star = false;
			for (Parameter parameter : declared) {
				switch (parameter.kind()) {
					case STAR -> {
						star = true;
						this.varargs = parameter.name();
					}
					case STAR_STAR -> this.kwargs = parameter.name();
					case REQUIRED, OPTIONAL -> {
						(star ? this.keywordOnly : this.positional)
							.add(Objects.requireNonNull(parameter.name(), "a parameter has a name"));
						this.defaults.add(parameter.defaultValue());
					}
				}
			}
		}

		/** The parameters an argument fills, positional first. */
		List<String> filled() {
			List<String> names = new ArrayList<>(this.positional);
			names.addAll(this.keywordOnly);
			return names;
		}

		/** Every name the function's body is handed, in the order it takes them. */
		List<String> names() {
			List<String> names = filled();
			if (this.varargs != null) {
				names.add(this.varargs);
			}
			if (this.kwargs != null) {
				names.add(this.kwargs);
			}
			return names;
		}

		LispVal lambdaList() {
			return listOf(names().stream().map(StarlarkNames::symbol).toList());
		}

	}

	/**
	 * A {@code def} as a Common Lisp function: its lambda list, then its body -- a
	 * {@code let} of every name the statements assign, inside a {@code block} when one of
	 * them is a {@code return} that has to leave from the middle.
	 * @param parameters the function's parameters
	 * @param statements its statements
	 * @param enclosing the scope the function is written in
	 * @param name the function's name, which its body finds assigned
	 * @return the lambda list and the body forms, what follows {@code lambda} or a
	 * {@code defun}'s name
	 */
	private LispVal function(Parameters parameters, List<Statement> statements, Scope enclosing, String name) {
		Set<String> names = new LinkedHashSet<>(parameters.names());
		Map<String, Integer> assigned = new LinkedHashMap<>();
		bindings(statements, assigned);
		List<LispVal> locals = new ArrayList<>();
		for (String local : assigned.keySet()) {
			if (names.add(local)) {
				locals.add(list(StarlarkNames.symbol(local), UNBOUND));
			}
		}
		Flow outer = enter(names, parameters.names());
		// The body runs only once the def statement assigned the name.
		if (!names.contains(name)) {
			this.flow.assigned.add(name);
		}
		Scope scope = enclosing.inner(names);
		Map<String, List<Supplier<Kind>>> kinds = new LinkedHashMap<>();
		bound(statements, scope, kinds);
		infer(kinds, parameters.names(), scope.kinds());
		// A return ending the body is the body's value: it needs no block to leave.
		List<Statement> block = statements;
		Expression last = null;
		if (!block.isEmpty() && block.get(block.size() - 1) instanceof Return returned) {
			last = returned.value();
			block = block.subList(0, block.size() - 1);
		}
		List<LispVal> forms = new ArrayList<>(statements(block, new Frame(scope)));
		forms.add(last == null ? StarlarkBuiltins.NONE : expression(last, scope));
		if (!locals.isEmpty()) {
			forms = List.of(cons(LET, cons(listOf(locals), listOf(forms))));
		}
		if (returns(block)) {
			forms = List.of(cons(BLOCK, cons(FUNCTION_BLOCK, listOf(forms))));
		}
		this.flow = outer;
		return cons(parameters.lambdaList(), listOf(once(forms, name)));
	}

	/**
	 * A function's body forms, under {@link StarlarkStandard#SPEC} refusing a call while
	 * a call of the same declaration runs: the declaration's own flag is true meanwhile.
	 * It is set back on every normal exit; after an error it is what
	 * {@code rontolisp:starlark-call} gives back, with the loops the call left counted.
	 */
	private List<LispVal> once(List<LispVal> forms, String name) {
		if (this.standard != StarlarkStandard.SPEC) {
			return forms;
		}
		LispSymbol flag = new LispSymbol(this.filePrefix + fresh("ACTIVE").name());
		this.active.add(flag);
		LispSymbol result = fresh("RESULT");
		return List.of(list(ENTER, flag, string(name)),
				list(LET, list(list(result, progn(forms))), list(LEAVE, flag), result));
	}

	/** Whether a {@code return} of this function stands in the statements. */
	private static boolean returns(List<Statement> statements) {
		for (Statement statement : statements) {
			boolean found = switch (statement) {
				case Return _ -> true;
				case If conditional -> returns(conditional.then()) || returns(conditional.otherwise());
				case For loop -> returns(loop.body());
				default -> false;
			};
			if (found) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The function value a {@code def} or a {@code lambda} evaluates to: its name, its
	 * parameters and their defaults -- evaluated here, once, in the scope the function is
	 * written in -- around the Common Lisp function {@code implementation}.
	 * @param holders for a function with a direct entry, its signature's defaults: a
	 * default that is no constant is left in the global standing there, which a direct
	 * call reads
	 */
	private LispVal functionValue(String name, Parameters parameters, LispVal implementation, Scope enclosing,
			@Nullable List<@Nullable LispVal> holders) {
		List<LispVal> defaults = new ArrayList<>();
		boolean anyDefault = false;
		for (int i = 0; i < parameters.defaults.size(); i++) {
			Expression value = parameters.defaults.get(i);
			anyDefault |= value != null;
			LispVal holder = holders == null ? null : holders.get(i);
			LispVal form = value == null ? StarlarkBuiltins.REQUIRED : expression(value, enclosing);
			defaults.add(holder == null || isConstant(holder) ? form : list(SETQ, holder, form));
		}
		return list(MAKE_FUNCTION, string(name), quotedStrings(parameters.filled()),
				anyDefault ? cons(LIST, listOf(defaults)) : LispNil.INSTANCE,
				new LispInteger(parameters.positional.size()), bool(parameters.varargs != null),
				bool(parameters.kwargs != null), implementation);
	}

	private LispVal definition(Def def, Scope scope) {
		Parameters parameters = new Parameters(def.parameters());
		LispVal implementation;
		List<@Nullable LispVal> holders = null;
		if (scope == Scope.TOP && this.direct.get(def.name()) == def) {
			LispSymbol symbol = global(def.name());
			this.session.definitions
				.add(located(cons(DEFUN, cons(symbol, function(parameters, def.body(), scope, def.name()))), def.at()));
			implementation = list(FUNCTION, symbol);
			holders = Objects.requireNonNull(Objects.requireNonNull(this.callees.get(def.name())).signature())
				.defaults();
		}
		else {
			implementation = cons(LAMBDA, function(parameters, def.body(), scope, def.name()));
		}
		return functionValue(def.name(), parameters, implementation, scope, holders);
	}

	/**
	 * How a top-level {@code def} with a direct entry is called: its parameters as the
	 * entry takes them, each default the constant it is written as, or a global of this
	 * file's the {@code def} statement leaves the value in -- a default is evaluated when
	 * the statement runs, and a direct call, which runs after it, needs the value.
	 */
	private Signature signature(Def def) {
		Parameters parameters = new Parameters(def.parameters());
		List<@Nullable LispVal> defaults = new ArrayList<>();
		for (Expression value : parameters.defaults) {
			LispVal constant = value == null ? null : scalar(value, Scope.TOP);
			defaults.add(value == null || constant != null ? constant
					: new LispSymbol(this.filePrefix + fresh("DEFAULT").name()));
		}
		return new Signature(def.name(), global(def.name()).name(), List.of(), List.copyOf(parameters.filled()),
				Collections.unmodifiableList(defaults), parameters.positional.size(), 0, parameters.varargs != null,
				parameters.kwargs != null, "");
	}

	private LispVal lambda(Lambda lambda, Scope scope) {
		Parameters parameters = new Parameters(lambda.parameters());
		Set<String> names = new LinkedHashSet<>(parameters.names());
		Scope inner = scope.inner(names);
		Flow outer = enter(names, parameters.names());
		LispVal body = expression(lambda.body(), inner);
		this.flow = outer;
		return functionValue("lambda", parameters,
				cons(LAMBDA, cons(parameters.lambdaList(), listOf(once(List.of(body), "lambda")))), scope, null);
	}

	// ---- statements ----

	private List<LispVal> statements(List<Statement> statements, Frame frame) {
		List<LispVal> forms = new ArrayList<>();
		for (Statement statement : statements) {
			LispVal form = statement(statement, frame);
			if (form != null) {
				forms.add(located(form, statement.at()));
			}
		}
		return forms;
	}

	/** One statement as one form; {@code null} for a statement that does nothing. */
	private @Nullable LispVal statement(Statement statement, Frame frame) {
		Scope scope = frame.scope;
		return switch (statement) {
			case ExpressionStatement expression ->
				isLiteral(expression.expression()) ? null : expression(expression.expression(), scope);
			case Assignment assignment -> assignment(assignment, scope);
			case AugmentedAssignment assignment -> augmented(assignment, scope);
			case Def def -> {
				LispVal form = list(SETQ, variable(def.name(), scope), definition(def, scope));
				this.flow.assigned.add(def.name());
				yield form;
			}
			case If conditional -> {
				List<LispVal> form = new ArrayList<>(List.of(IF, test(conditional.condition(), scope)));
				Flow before = this.flow;
				this.flow = before.copy();
				form.add(progn(statements(conditional.then(), frame)));
				Flow then = this.flow;
				this.flow = before.copy();
				List<LispVal> otherwise = statements(conditional.otherwise(), frame);
				this.flow = Flow.join(then, this.flow);
				if (!otherwise.isEmpty()) {
					form.add(progn(otherwise));
				}
				yield listOf(form);
			}
			case For iteration -> {
				Loop loop = loop(iteration.target(), iteration.iterable(), scope).exits(iteration.body());
				// The body may run no time: what it assigns is not assigned after it.
				Flow before = this.flow;
				this.flow = before.copy();
				LispVal assignment = loop.assignment(scope);
				if (loop.iterable != null) {
					frame.loops.push(loop.iterable);
				}
				frame.depth++;
				List<LispVal> body = statements(iteration.body(), frame);
				frame.depth--;
				if (loop.iterable != null) {
					frame.loops.pop();
				}
				this.flow = before;
				yield loop.over(assignment, body);
			}
			case Return returned -> {
				LispVal form = returned(returned, frame);
				this.flow.dead = true;
				yield form;
			}
			case Break _ -> {
				this.flow.dead = true;
				yield list(RETURN_FROM, BREAK_BLOCK, LispNil.INSTANCE);
			}
			case Continue _ -> {
				this.flow.dead = true;
				yield list(RETURN_FROM, CONTINUE_BLOCK, LispNil.INSTANCE);
			}
			case Pass _ -> null;
			// a top-level one is the file's body's business
			case Load load ->
				throw error("load statement within a " + (frame.depth == 0 ? "conditional" : "loop"), load.at());
		};
	}

	/** A literal standing as a statement: a docstring. */
	private static boolean isLiteral(Expression expression) {
		return expression instanceof StringLiteral || expression instanceof IntLiteral
				|| expression instanceof FloatLiteral;
	}

	/**
	 * {@code return}: the function's block is left with the value, after every loop of
	 * the function it stands in that holds an iterable gave it back. The value is
	 * computed first: it may still read what the loops hold.
	 */
	private LispVal returned(Return returned, Frame frame) {
		Expression operand = returned.value();
		LispVal value = operand == null ? StarlarkBuiltins.NONE : expression(operand, frame.scope);
		if (frame.loops.isEmpty()) {
			return list(RETURN_FROM, FUNCTION_BLOCK, value);
		}
		List<LispVal> forms = new ArrayList<>();
		LispVal result = value;
		if (isSimple(value)) {
			forms.add(PROGN);
		}
		else {
			result = fresh("RESULT");
			forms.add(LET);
			forms.add(list(list(result, value)));
		}
		for (LispSymbol iterable : frame.loops) {
			forms.add(list(ITERATED, iterable));
		}
		forms.add(list(RETURN_FROM, FUNCTION_BLOCK, result));
		return listOf(forms);
	}

	/**
	 * A loop: over an iterable, whose elements are read off one vector by index while the
	 * iterable counts the loop from {@code %starlark-iterate} to
	 * {@code %starlark-iterated}, refusing a mutation meanwhile; or, over a call of
	 * {@code range}, over a counter, with no range and no vector made ({@link #loop}).
	 * Only a loop whose body leaves it gets the block to leave.
	 */
	private final class Loop {

		/** What the loop evaluates before it starts, as {@code let*} bindings. */
		private final List<LispVal> bindings = new ArrayList<>();

		/**
		 * The variable holding the iterable while the loop runs, or {@code null} for a
		 * loop that counts: it holds nothing to give back.
		 */
		private final @Nullable LispSymbol iterable;

		/** Whether an element is left. */
		private final LispVal test;

		/** The element the loop stands at. */
		private final LispVal element;

		/** Moves the loop to the next element. */
		private final LispVal step;

		private final LispSymbol top;

		private final Expression target;

		private boolean breaks;

		private boolean continues;

		/**
		 * A loop over an iterable.
		 * @param target what each element is assigned to
		 * @param iterated the iterable's form
		 */
		Loop(Expression target, LispVal iterated) {
			LispSymbol iterable = fresh("SEQ");
			LispSymbol items = fresh("ITEMS");
			LispSymbol count = fresh("COUNT");
			LispSymbol index = fresh("INDEX");
			this.bindings.addAll(List.of(list(iterable, iterated), list(items, list(ITERATE, iterable)),
					list(count, list(LENGTH, items)), list(index, new LispInteger(0))));
			this.iterable = iterable;
			this.test = list(LESS, index, count);
			this.element = list(AREF, items, index);
			this.step = list(SETQ, index, list(PLUS, index, new LispInteger(1)));
			this.top = fresh("TOP");
			this.target = target;
		}

		/**
		 * A loop that counts from {@code start} to {@code stop}, for a call of
		 * {@code range}: the bounds and the step are evaluated as the call's arguments
		 * are and checked as {@code range} checks them.
		 * @param target what each number is assigned to
		 * @param range the call
		 * @param start the first number's form
		 * @param stop the form of the number the loop stops before; a constant stands in
		 * the test itself
		 * @param constant the step when it is a constant, not zero, else {@code null}
		 * @param step the step's form
		 * @param ints whether the bounds are known ints, which need no check
		 */
		Loop(Expression target, Call range, LispVal start, LispVal stop, @Nullable BigInteger constant, LispVal step,
				boolean ints) {
			LispSymbol next = fresh("NEXT");
			this.bindings.add(list(next, start));
			LispVal limit = stop;
			if (constant == null || !ints || !isConstant(stop)) {
				LispSymbol held = fresh("STOP");
				this.bindings.add(
						list(held, constant == null || ints ? stop : located(list(LOOP_STOP, next, stop), range.at())));
				limit = held;
			}
			LispVal by = step;
			if (constant == null) {
				// the step's sign is known only when the loop runs, and it checks the
				// bounds with itself
				by = fresh("STEP");
				this.bindings.add(list(by, located(list(LOOP_STEP, next, limit, step), range.at())));
			}
			this.iterable = null;
			this.test = constant != null ? list(constant.signum() > 0 ? LESS : GREATER, next, limit)
					: list(IF, list(LESS, new LispInteger(0), by), list(LESS, next, limit), list(GREATER, next, limit));
			this.element = next;
			this.step = list(SETQ, next, list(PLUS, next, by));
			this.top = fresh("TOP");
			this.target = target;
		}

		/** Notes the {@code break} and {@code continue} of this loop in its body. */
		Loop exits(List<Statement> body) {
			this.breaks = leaves(body, true);
			this.continues = leaves(body, false);
			return this;
		}

		private static boolean leaves(List<Statement> statements, boolean byBreak) {
			for (Statement statement : statements) {
				boolean found = switch (statement) {
					case Break _ -> byBreak;
					case Continue _ -> !byBreak;
					case If conditional ->
						leaves(conditional.then(), byBreak) || leaves(conditional.otherwise(), byBreak);
					default -> false;
				};
				if (found) {
					return true;
				}
			}
			return false;
		}

		/**
		 * The assignment of each element to the target, lowered before the body, which
		 * finds the target assigned.
		 * @param scope where the target's names resolve
		 * @return the assignment
		 */
		LispVal assignment(Scope scope) {
			return assign(this.target, Value.element(this.element), scope);
		}

		/**
		 * The loop around its body, inside what it evaluates first.
		 * @param assignment what {@link #assignment} answered
		 * @param body the body's forms
		 * @return the loop
		 */
		LispVal over(LispVal assignment, List<LispVal> body) {
			return cons(LET_STAR, cons(listOf(this.bindings), listOf(walk(assignment, body))));
		}

		/**
		 * The loop around its body, for a place where {@link #bindings} are bound.
		 * @param assignment what {@link #assignment} answered
		 * @param body the body's forms
		 * @return the loop's forms
		 */
		List<LispVal> walk(LispVal assignment, List<LispVal> body) {
			List<LispVal> step = new ArrayList<>(List.of(PROGN, assignment, this.step));
			if (this.continues) {
				step.add(cons(BLOCK, cons(CONTINUE_BLOCK, listOf(body))));
			}
			else {
				step.addAll(body);
			}
			step.add(list(GO, this.top));
			LispVal walk = list(TAGBODY, this.top, list(IF, this.test, listOf(step)));
			List<LispVal> forms = new ArrayList<>();
			forms.add(this.breaks ? list(BLOCK, BREAK_BLOCK, walk) : walk);
			if (this.iterable != null) {
				forms.add(list(ITERATED, this.iterable));
			}
			return forms;
		}

	}

	/**
	 * The loop of a {@code for} statement or clause: over a call of the built-in
	 * {@code range} the loop counts, with no range made.
	 * @param target what each element is assigned to
	 * @param iterable the iterable
	 * @param scope where the iterable's names resolve
	 * @return the loop
	 */
	private Loop loop(Expression target, Expression iterable, Scope scope) {
		Call range = iterable instanceof Call call && builtin(call.function(), scope) != null
				&& ((Identifier) call.function()).name().equals("range") ? call : null;
		List<Argument> arguments = range == null ? List.of() : range.arguments();
		BigInteger constant = arguments.size() == 3 ? literalInt(arguments.get(2).value()) : BigInteger.ONE;
		// a step written as zero is the range's to refuse
		if (range == null || arguments.isEmpty() || arguments.size() > 3 || constant != null && constant.signum() == 0
				|| !arguments.stream().allMatch(argument -> argument.kind() == ArgumentKind.POSITIONAL)) {
			return new Loop(target, expression(iterable, scope));
		}
		Expression first = arguments.size() == 1 ? null : arguments.get(0).value();
		Expression last = arguments.get(arguments.size() == 1 ? 0 : 1).value();
		LispVal start = first == null ? new LispInteger(0) : expression(first, scope);
		LispVal stop = expression(last, scope);
		LispVal step = constant != null ? integer(constant) : expression(arguments.get(2).value(), scope);
		boolean ints = (first == null || kind(first, scope) == Kind.INT) && kind(last, scope) == Kind.INT;
		return new Loop(target, range, start, stop, constant, step, ints);
	}

	/** The int an expression is written as, or {@code null}. */
	private static @Nullable BigInteger literalInt(Expression expression) {
		return switch (expression) {
			case IntLiteral literal -> literal.value();
			case Unary unary when unary.operator() == UnaryOperator.MINUS
					&& unary.operand() instanceof IntLiteral literal ->
				literal.value().negate();
			default -> null;
		};
	}

	/**
	 * What a target is assigned.
	 *
	 * @param form the value's form
	 * @param pure whether the form is a read that can stand after the target's operands
	 * unobserved (an element of a vector the lowering holds)
	 */
	private record Value(LispVal form, boolean pure) {

		/**
		 * A value the statement computes: it is computed before the target's operands.
		 */
		static Value computed(LispVal form) {
			return new Value(form, false);
		}

		/** An element read off a vector nothing else writes. */
		static Value element(LispVal form) {
			return new Value(form, true);
		}

	}

	/**
	 * Assigns a value to a target: a name, an element, or a tuple or list of targets
	 * taking the value's elements.
	 */
	private LispVal assign(Expression target, Value value, Scope scope) {
		return switch (target) {
			case Identifier identifier -> {
				this.flow.assigned.add(identifier.name());
				yield list(SETQ, variable(identifier.name(), scope), value.form());
			}
			case Index index -> {
				List<LispVal> operands = List.of(value.form(), expression(index.receiver(), scope),
						expression(index.index(), scope));
				yield inSourceOrder(operands, value.pure() ? List.of(1, 2) : List.of(1, 2, 0),
						placed -> list(SET_INDEX, placed.get(1), placed.get(2), placed.get(0)));
			}
			case Dot dot -> {
				List<LispVal> operands = List.of(value.form(), expression(dot.receiver(), scope));
				yield inSourceOrder(operands, value.pure() ? List.of(1) : List.of(1, 0),
						placed -> list(SET_ATTRIBUTE, placed.get(1), string(dot.name()), placed.get(0)));
			}
			case TupleLiteral tuple -> unpack(tuple.elements(), value, scope);
			case ListLiteral list -> unpack(list.elements(), value, scope);
			default -> throw error("cannot assign to this expression", target.at());
		};
	}

	/**
	 * An assignment statement. A tuple or list of targets assigned a tuple or list
	 * expression of as many elements takes them one by one, with no tuple made: every
	 * element is evaluated, in order, before the first target is assigned.
	 */
	private LispVal assignment(Assignment assignment, Scope scope) {
		List<Expression> targets = new ArrayList<>();
		List<Expression> values = new ArrayList<>();
		pairs(assignment.target(), assignment.value(), targets, values);
		if (targets.size() == 1 && targets.get(0) == assignment.target() || targets.isEmpty()) {
			return assign(assignment.target(), Value.computed(expression(assignment.value(), scope)), scope);
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> held = new ArrayList<>();
		for (Expression value : values) {
			LispVal form = expression(value, scope);
			if (!isConstant(form)) {
				LispSymbol element = fresh("ELEMENT");
				bindings.add(list(element, form));
				form = element;
			}
			held.add(form);
		}
		List<LispVal> forms = new ArrayList<>();
		for (int i = 0; i < targets.size(); i++) {
			forms.add(assign(targets.get(i), Value.element(held.get(i)), scope));
		}
		return bindings.isEmpty() ? progn(forms) : cons(LET, cons(listOf(bindings), listOf(forms)));
	}

	/**
	 * Pairs a target with the value it takes: the targets of a tuple or list with the
	 * elements of a tuple or list expression of as many, else the target with the value.
	 */
	private static void pairs(Expression target, Expression value, List<Expression> targets, List<Expression> values) {
		List<Expression> inner = elements(target);
		List<Expression> elements = elements(value);
		if (inner != null && elements != null && inner.size() == elements.size()) {
			for (int i = 0; i < inner.size(); i++) {
				pairs(inner.get(i), elements.get(i), targets, values);
			}
		}
		else {
			targets.add(target);
			values.add(value);
		}
	}

	private LispVal unpack(List<Expression> targets, Value value, Scope scope) {
		LispSymbol elements = fresh("UNPACKED");
		List<LispVal> forms = new ArrayList<>(
				List.of(LET, list(list(elements, list(UNPACK, value.form(), new LispInteger(targets.size()))))));
		for (int i = 0; i < targets.size(); i++) {
			forms.add(assign(targets.get(i), Value.element(list(AREF, elements, new LispInteger(i))), scope));
		}
		return listOf(forms);
	}

	/**
	 * {@code target op= value}: the target's operands are evaluated once, before the
	 * value. {@code +=} extends a list in place, {@code |=}, {@code &=}, {@code -=} and
	 * {@code ^=} update a set, and {@code |=} a dict, in place.
	 */
	private LispVal augmented(AugmentedAssignment assignment, Scope scope) {
		BinaryOperator operator = assignment.operator();
		LispSymbol helper = switch (operator) {
			case PLUS -> ADD_IN_PLACE;
			case MINUS -> runtime("SUB-IN-PLACE");
			case BIT_OR -> runtime("BIT-OR-IN-PLACE");
			case BIT_AND -> runtime("BIT-AND-IN-PLACE");
			case BIT_XOR -> runtime("BIT-XOR-IN-PLACE");
			default -> arithmetic(operator);
		};
		BiFunction<LispVal, LispVal, LispVal> generic = (left, right) -> list(helper, left, right);
		Kind kind = kind(assignment.value(), scope);
		LispVal value = expression(assignment.value(), scope);
		return switch (assignment.target()) {
			case Identifier identifier -> {
				LispVal current = read(identifier.name(), scope);
				this.flow.assigned.add(identifier.name());
				yield list(SETQ, variable(identifier.name(), scope), operation(operator, current,
						kind(identifier.name(), scope), value, kind, assignment.value(), generic, assignment.at()));
			}
			case Index index -> {
				LispVal receiver = expression(index.receiver(), scope);
				LispVal key = expression(index.index(), scope);
				List<LispVal> bindings = new ArrayList<>();
				if (!isSimple(receiver)) {
					LispSymbol held = fresh("OBJECT");
					bindings.add(list(held, receiver));
					receiver = held;
				}
				if (!isSimple(key)) {
					LispSymbol held = fresh("KEY");
					bindings.add(list(held, key));
					key = held;
				}
				LispVal store = list(SET_INDEX, receiver, key, operation(operator, list(INDEX, receiver, key), Kind.ANY,
						value, kind, assignment.value(), generic, assignment.at()));
				yield bindings.isEmpty() ? store : list(LET_STAR, listOf(bindings), store);
			}
			case Dot dot -> list(SET_ATTRIBUTE, expression(dot.receiver(), scope), string(dot.name()), value);
			default -> throw error("cannot augment-assign to this expression", assignment.target().at());
		};
	}

	// ---- expressions ----

	private LispVal expression(Expression expression, Scope scope) {
		return located(lowered(expression, scope), expression.at());
	}

	private LispVal lowered(Expression expression, Scope scope) {
		return switch (expression) {
			case Identifier identifier -> reference(identifier, scope);
			case IntLiteral literal -> integer(literal.value());
			case FloatLiteral literal -> new LispDouble(literal.value());
			case StringLiteral literal -> string(literal.value());
			case BytesLiteral literal ->
				list(runtime("BYTES-LITERAL"), LispIntVector.wrapOctets(literal.value().clone()));
			case Unary unary -> unary(unary, scope);
			case Binary binary -> binary(binary, scope);
			case Conditional conditional -> list(IF, test(conditional.condition(), scope),
					expression(conditional.then(), scope), expression(conditional.otherwise(), scope));
			case Lambda lambda -> lambda(lambda, scope);
			case ListLiteral literal -> {
				LispVal constant = constant(literal, LIST_OF, scope);
				yield constant != null ? constant : cons(MAKE_LIST, expressions(literal.elements(), scope));
			}
			case TupleLiteral literal -> {
				LispVal constant = constant(literal, TUPLE_OF, scope);
				yield constant != null ? constant : cons(MAKE_TUPLE, expressions(literal.elements(), scope));
			}
			case DictLiteral literal -> {
				LispVal constant = constant(literal, DICT_OF, scope);
				if (constant != null) {
					yield constant;
				}
				List<LispVal> operands = new ArrayList<>();
				for (Entry entry : literal.entries()) {
					operands.add(expression(entry.key(), scope));
					operands.add(expression(entry.value(), scope));
				}
				yield cons(MAKE_DICT, listOf(operands));
			}
			case ListComprehension comprehension ->
				comprehension(List.of(comprehension.element()), comprehension.clauses(), scope);
			case DictComprehension comprehension ->
				comprehension(List.of(comprehension.entry().key(), comprehension.entry().value()),
						comprehension.clauses(), scope);
			case Call call -> call(call, scope);
			case Dot dot -> {
				LispVal receiver = expression(dot.receiver(), scope);
				Signature method = StarlarkBuiltins.method(dot.name());
				yield method == null ? list(NO_ATTRIBUTE, receiver, string(dot.name())) : methodValue(method, receiver);
			}
			case Index index -> list(INDEX, expression(index.receiver(), scope), expression(index.index(), scope));
			case Slice slice -> list(SLICE, expression(slice.receiver(), scope), bound(slice.start(), scope),
					bound(slice.stop(), scope), bound(slice.step(), scope));
		};
	}

	private LispVal expressions(List<Expression> expressions, Scope scope) {
		return listOf(expressions.stream().map(expression -> expression(expression, scope)).toList());
	}

	private LispVal bound(@Nullable Expression operand, Scope scope) {
		return operand == null ? StarlarkBuiltins.NONE : expression(operand, scope);
	}

	/**
	 * A list, tuple or dict expression whose elements are all constants, as quoted data:
	 * one constant however many elements, where the call of as many arguments passes what
	 * one JVM method holds from some 1,500 on. The value is made anew from it each time:
	 * a list over its elements, or, when an element is such an expression itself, what
	 * {@code %starlark-literal} builds of the marked form.
	 * @param literal the expression
	 * @param builder the helper taking the elements as a list
	 * @param scope where the expression stands
	 * @return the form, or {@code null} when an element is no constant or there is none
	 */
	private @Nullable LispVal constant(Expression literal, LispSymbol builder, Scope scope) {
		LispVal datum = datum(literal, scope);
		if (!(datum instanceof LispCons marked) || marked.cdr() == LispNil.INSTANCE) {
			return null;
		}
		boolean flat = marked.toList().stream().noneMatch(LispCons.class::isInstance);
		return flat ? list(builder, list(QUOTE, marked.cdr())) : list(LITERAL, list(QUOTE, datum));
	}

	/**
	 * A constant expression as a datum: a number, a string, {@code None}, {@code True} or
	 * {@code False} is itself, a list, tuple or dict expression of constants its
	 * elements' data behind a mark -- no other constant is a cons.
	 * @return the datum, or {@code null} when the expression is no constant
	 */
	private @Nullable LispVal datum(Expression expression, Scope scope) {
		List<Expression> elements = switch (expression) {
			case ListLiteral list -> list.elements();
			case TupleLiteral tuple -> tuple.elements();
			case DictLiteral dict ->
				dict.entries().stream().flatMap(entry -> Stream.of(entry.key(), entry.value())).toList();
			default -> null;
		};
		if (elements == null) {
			return scalar(expression, scope);
		}
		List<LispVal> data = new ArrayList<>();
		data.add(expression instanceof ListLiteral ? LIST_MARK
				: expression instanceof TupleLiteral ? TUPLE_MARK : DICT_MARK);
		for (Expression element : elements) {
			LispVal datum = datum(element, scope);
			if (datum == null) {
				return null;
			}
			data.add(datum);
		}
		return listOf(data);
	}

	/**
	 * The constant an expression is written as -- a number, a string, {@code None},
	 * {@code True} or {@code False} --, or {@code null}.
	 */
	private @Nullable LispVal scalar(Expression expression, Scope scope) {
		return switch (expression) {
			case IntLiteral literal -> integer(literal.value());
			case FloatLiteral literal -> new LispDouble(literal.value());
			case StringLiteral literal -> string(literal.value());
			case Unary unary when unary.operator() == UnaryOperator.MINUS
					&& unary.operand() instanceof IntLiteral literal ->
				integer(literal.value().negate());
			case Unary unary when unary.operator() == UnaryOperator.MINUS
					&& unary.operand() instanceof FloatLiteral literal ->
				new LispDouble(-literal.value());
			case Identifier identifier when !isVariable(identifier.name(), scope) -> CONSTANTS.get(identifier.name());
			default -> null;
		};
	}

	/** A form that is a constant: a number, a string, a keyword, T or NIL. */
	private static boolean isConstant(LispVal form) {
		return !(form instanceof LispCons) && !(form instanceof LispSymbol symbol && !symbol.name().startsWith(":"));
	}

	private static LispVal integer(BigInteger value) {
		return value.bitLength() < Long.SIZE ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	private LispVal unary(Unary unary, Scope scope) {
		Expression operand = unary.operand();
		boolean integer = kind(operand, scope) == Kind.INT;
		return switch (unary.operator()) {
			case NOT -> list(NOT, test(operand, scope));
			case MINUS -> switch (operand) {
				case IntLiteral literal -> integer(literal.value().negate());
				case FloatLiteral literal -> new LispDouble(-literal.value());
				default -> list(integer ? MINUS : runtime("NEGATE"), expression(operand, scope));
			};
			case PLUS -> integer ? expression(operand, scope) : list(runtime("PLUS"), expression(operand, scope));
			case INVERT -> list(integer ? LOGNOT : runtime("INVERT"), expression(operand, scope));
		};
	}

	private LispVal binary(Binary binary, Scope scope) {
		BinaryOperator operator = binary.operator();
		if (operator == BinaryOperator.AND || operator == BinaryOperator.OR) {
			// `a and b` is a when a is false, else b; `a or b` the other way round.
			boolean and = operator == BinaryOperator.AND;
			LispVal right = expression(binary.right(), scope);
			Kind kind = kind(binary.left(), scope);
			if (kind == Kind.BOOL) {
				LispVal left = test(binary.left(), scope);
				return and ? list(IF, left, right, LispNil.INSTANCE) : list(IF, left, LispTrue.INSTANCE, right);
			}
			LispSymbol held = fresh("VALUE");
			return list(LET, list(list(held, expression(binary.left(), scope))),
					and ? list(IF, truth(held, kind), right, held) : list(IF, truth(held, kind), held, right));
		}
		LispVal left = expression(binary.left(), scope);
		LispVal right = expression(binary.right(), scope);
		boolean equals = operator == BinaryOperator.EQUALS;
		if ((equals || operator == BinaryOperator.NOT_EQUALS) && (isSingleton(left) || isSingleton(right))) {
			// None, True and False equal themselves only
			LispVal same = list(EQ, left, right);
			return equals ? same : list(NOT, same);
		}
		BiFunction<LispVal, LispVal, LispVal> generic = switch (operator) {
			case EQUALS -> (a, b) -> list(EQUAL, a, b);
			case NOT_EQUALS -> (a, b) -> list(NOT, list(EQUAL, a, b));
			case LESS -> (a, b) -> list(runtime("LESS"), a, b);
			case LESS_EQUALS -> (a, b) -> list(runtime("LESS-EQUAL"), a, b);
			case GREATER -> (a, b) -> list(runtime("GREATER"), a, b);
			case GREATER_EQUALS -> (a, b) -> list(runtime("GREATER-EQUAL"), a, b);
			case IN -> (a, b) -> list(IN, a, b);
			case NOT_IN -> (a, b) -> list(NOT, list(IN, a, b));
			default -> (a, b) -> list(arithmetic(operator), a, b);
		};
		return operation(operator, left, kind(binary.left(), scope), right, kind(binary.right(), scope), binary.right(),
				generic, binary.at());
	}

	/** A form that is {@code None}, {@code True} or {@code False}. */
	private static boolean isSingleton(LispVal form) {
		return form == LispTrue.INSTANCE || form == LispNil.INSTANCE || StarlarkBuiltins.NONE.equals(form);
	}

	/**
	 * A binary operator over two lowered operands. Over two ints an arithmetic operator
	 * or a comparison is the Common Lisp operator, which answers what the generic helper
	 * answers for them; over operands that may be ints it is that operator behind a test
	 * of those not known to be, with the helper as the other branch; over an operand that
	 * is no int, and for any other operator, it is the helper.
	 * @param operator the operator
	 * @param left the left operand's form
	 * @param leftKind what the left operand is known to be
	 * @param right the right operand's form
	 * @param rightKind what the right operand is known to be
	 * @param divisor the right operand: a division or a shift is the Common Lisp operator
	 * only by a constant that cannot fail
	 * @param generic the helper's call over two operand forms
	 * @param at the operator's position, which the helper's call is located at
	 * @return the form
	 */
	private LispVal operation(BinaryOperator operator, LispVal left, Kind leftKind, LispVal right, Kind rightKind,
			Expression divisor, BiFunction<LispVal, LispVal, LispVal> generic, SourceLocation at) {
		BiFunction<LispVal, LispVal, LispVal> integers = integers(operator, divisor);
		if (integers == null || !leftKind.mayBeInt() || !rightKind.mayBeInt()) {
			return generic.apply(left, right);
		}
		if (leftKind == Kind.INT && rightKind == Kind.INT) {
			return integers.apply(left, right);
		}
		if (!TESTED.contains(operator)) {
			return generic.apply(left, right);
		}
		// each operand is evaluated once, in order, whichever branch is taken
		List<LispVal> bindings = new ArrayList<>();
		LispVal first = left;
		LispVal second = right;
		if (!isSimple(left)) {
			first = fresh("LEFT");
			bindings.add(list(first, left));
		}
		if (!isSimple(right)) {
			second = fresh("RIGHT");
			bindings.add(list(second, right));
		}
		List<LispVal> tests = new ArrayList<>();
		if (leftKind != Kind.INT) {
			tests.add(list(INTEGERP, first));
		}
		if (rightKind != Kind.INT) {
			tests.add(list(INTEGERP, second));
		}
		LispVal form = list(IF, tests.size() == 1 ? tests.get(0) : cons(AND, listOf(tests)),
				integers.apply(first, second), located(generic.apply(first, second), at));
		return bindings.isEmpty() ? form : list(LET_STAR, listOf(bindings), form);
	}

	/**
	 * The Common Lisp form of an operator over two ints, which answers what the generic
	 * helper answers for them and cannot fail: an integer of any size.
	 * @param operator the operator
	 * @param right the right operand: {@code //} and {@code %} need a constant that is
	 * not zero, a shift a constant count that is not negative
	 * @return the form's builder, or {@code null} when the operator has none
	 */
	private static @Nullable BiFunction<LispVal, LispVal, LispVal> integers(BinaryOperator operator, Expression right) {
		BigInteger constant = literalInt(right);
		boolean divides = constant != null && constant.signum() != 0;
		boolean shifts = constant != null && constant.signum() >= 0 && constant.bitLength() < Integer.SIZE;
		return switch (operator) {
			case PLUS -> (a, b) -> list(PLUS, a, b);
			case MINUS -> (a, b) -> list(MINUS, a, b);
			case MULTIPLY -> (a, b) -> list(TIMES, a, b);
			case FLOOR_DIVIDE -> divides ? (a, b) -> list(VALUES, list(FLOOR, a, b)) : null;
			case MODULO -> divides ? (a, b) -> list(MOD, a, b) : null;
			case BIT_AND -> (a, b) -> list(LOGAND, a, b);
			case BIT_OR -> (a, b) -> list(LOGIOR, a, b);
			case BIT_XOR -> (a, b) -> list(LOGXOR, a, b);
			case SHIFT_LEFT -> shifts ? (a, b) -> list(ASH, a, b) : null;
			case SHIFT_RIGHT ->
				shifts ? (a, b) -> list(ASH, a, integer(Objects.requireNonNull(constant).negate())) : null;
			case EQUALS -> (a, b) -> list(NUMBER_EQUAL, a, b);
			case NOT_EQUALS -> (a, b) -> list(NOT, list(NUMBER_EQUAL, a, b));
			case LESS -> (a, b) -> list(LESS, a, b);
			case LESS_EQUALS -> (a, b) -> list(LESS_EQUAL, a, b);
			case GREATER -> (a, b) -> list(GREATER, a, b);
			case GREATER_EQUALS -> (a, b) -> list(GREATER_EQUAL, a, b);
			default -> null;
		};
	}

	private static LispSymbol arithmetic(BinaryOperator operator) {
		return runtime(switch (operator) {
			case PLUS -> "ADD";
			case MINUS -> "SUB";
			case MULTIPLY -> "MUL";
			case DIVIDE -> "DIV";
			case FLOOR_DIVIDE -> "FLOORDIV";
			case MODULO -> "MOD";
			case BIT_OR -> "BIT-OR";
			case BIT_AND -> "BIT-AND";
			case BIT_XOR -> "BIT-XOR";
			case SHIFT_LEFT -> "SHIFT-LEFT";
			case SHIFT_RIGHT -> "SHIFT-RIGHT";
			default -> throw new IllegalStateException("not an arithmetic operator: " + operator);
		});
	}

	/**
	 * An expression as a condition: the Common Lisp boolean of its truth. {@code and} and
	 * {@code or} of conditions are {@code and} and {@code or} of their tests.
	 */
	private LispVal test(Expression expression, Scope scope) {
		if (expression instanceof Binary binary
				&& (binary.operator() == BinaryOperator.AND || binary.operator() == BinaryOperator.OR)) {
			return list(binary.operator() == BinaryOperator.AND ? AND : OR, test(binary.left(), scope),
					test(binary.right(), scope));
		}
		return truth(expression(expression, scope), kind(expression, scope));
	}

	/**
	 * The truth of a value: itself when it is known to be {@code True} or {@code False},
	 * which are the Common Lisp booleans, as a comparison, {@code not} and the Boolean
	 * constants answer; whether it is not zero for an int; else what
	 * {@code %starlark-truth} answers.
	 */
	private static LispVal truth(LispVal value, Kind kind) {
		return switch (kind) {
			case BOOL -> value;
			case INT -> list(NOT, list(NUMBER_EQUAL, value, new LispInteger(0)));
			default -> list(TRUTH, value);
		};
	}

	/**
	 * A comprehension: the loops and conditions of its clauses nested in order, around
	 * what it produces -- one element pushed onto a vector, or a key and a value stored
	 * in a dict. Its variables are its own, and the first clause's iterable is evaluated
	 * outside them.
	 * @param produced the element of a list comprehension, or the key and the value of a
	 * dict comprehension
	 * @param clauses the clauses
	 * @param scope where the comprehension stands
	 * @return the list or the dict
	 */
	private LispVal comprehension(List<Expression> produced, List<Clause> clauses, Scope scope) {
		Map<String, Integer> bound = new LinkedHashMap<>();
		for (Clause clause : clauses) {
			if (clause instanceof ForClause loop) {
				targetNames(loop.target(), bound);
			}
		}
		Scope inner = scope.inner(bound.keySet());
		Map<String, List<Supplier<Kind>>> kinds = new LinkedHashMap<>();
		for (int i = 0; i < clauses.size(); i++) {
			if (clauses.get(i) instanceof ForClause loop) {
				element(loop.target(), loop.iterable(), i == 0 ? scope : inner, kinds);
			}
		}
		infer(kinds, List.of(), inner.kinds());
		boolean dict = produced.size() == 2;
		LispSymbol accumulator = fresh("ACC");
		// The clauses in order, each loop's target assigned for the clauses after it;
		// the first iterable is the enclosing block's.
		ForClause outermost = (ForClause) clauses.get(0);
		Loop first = loop(outermost.target(), outermost.iterable(), scope);
		Flow outer = enter(bound.keySet(), List.of());
		// per clause: its loop and the target's assignment, or no loop and its test
		List<@Nullable Loop> loops = new ArrayList<>();
		List<LispVal> heads = new ArrayList<>();
		for (int i = 0; i < clauses.size(); i++) {
			switch (clauses.get(i)) {
				case IfClause condition -> {
					loops.add(null);
					heads.add(test(condition.condition(), inner));
				}
				case ForClause clause -> {
					Loop loop = i == 0 ? first : loop(clause.target(), clause.iterable(), inner);
					loops.add(loop);
					heads.add(loop.assignment(inner));
				}
			}
		}
		LispVal body = dict
				? list(SET_INDEX, accumulator, expression(produced.get(0), inner), expression(produced.get(1), inner))
				: list(VECTOR_PUSH_EXTEND, expression(produced.get(0), inner), accumulator);
		this.flow = outer;
		for (int i = clauses.size() - 1; i > 0; i--) {
			Loop loop = loops.get(i);
			body = loop == null ? list(IF, heads.get(i), body) : loop.over(heads.get(i), List.of(body));
		}
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(accumulator, list(dict ? EMPTY_DICT : VECTOR)));
		for (String name : bound.keySet()) {
			bindings.add(list(StarlarkNames.symbol(name), UNBOUND));
		}
		// The first loop's iterable is evaluated outside the comprehension's variables:
		// it reads the enclosing block's, one of the same name included.
		List<LispVal> forms = new ArrayList<>(List.of(LET, listOf(bindings)));
		forms.addAll(first.walk(heads.get(0), List.of(body)));
		forms.add(dict ? accumulator : list(LIST_FROM, accumulator));
		return list(LET_STAR, listOf(first.bindings), listOf(forms));
	}

	// ---- calls ----

	private LispVal call(Call call, Scope scope) {
		Expression callee = call.function();
		List<Argument> arguments = call.arguments();
		if (callee instanceof Dot dot) {
			Signature method = StarlarkBuiltins.method(dot.name());
			if (method == null) {
				// no value has it: the receiver is evaluated, the arguments never are
				return list(NO_ATTRIBUTE, expression(dot.receiver(), scope), string(dot.name()));
			}
			// the helper takes the receiver as its first parameter
			List<Argument> withReceiver = new ArrayList<>();
			withReceiver.add(new Argument(ArgumentKind.POSITIONAL, null, dot.receiver(), dot.at()));
			withReceiver.addAll(arguments);
			LispVal direct = direct(method.withReceiver(), withReceiver, scope);
			return direct != null ? direct
					: general(methodValue(method, expression(dot.receiver(), scope)), arguments, scope);
		}
		if (callee instanceof Identifier identifier) {
			String name = identifier.name();
			Signature builtin = builtin(identifier, scope);
			if (builtin != null) {
				LispVal direct = direct(builtin, arguments, scope);
				return direct != null ? direct : general(builtinValue(builtin), arguments, scope);
			}
			Callee entry = scope.binds(name) || this.interactive ? null : this.callees.get(name);
			LispVal direct = entry == null ? null : entry.signature() != null
					? direct(entry.signature(), arguments, scope) : foreign(entry.function(), arguments, scope);
			if (direct != null) {
				// the callee first: a call above the def, or above the load statement,
				// reads the global unassigned
				LispVal global = read(name, scope);
				return isSimple(global) ? direct : list(PROGN, global, direct);
			}
		}
		return general(expression(callee, scope), arguments, scope);
	}

	/**
	 * A Common Lisp function called with the positional arguments as they stand, or
	 * {@code null} when an argument is not positional.
	 */
	private @Nullable LispVal foreign(LispSymbol function, List<Argument> arguments, Scope scope) {
		if (!arguments.stream().allMatch(argument -> argument.kind() == ArgumentKind.POSITIONAL)) {
			return null;
		}
		return cons(function, listOf(arguments.stream().map(argument -> expression(argument.value(), scope)).toList()));
	}

	/**
	 * The direct tier: binds the arguments to the parameters of a built-in, or of a
	 * {@code def} with a direct entry, while lowering and calls its function with every
	 * parameter in place.
	 * @param signature the built-in, or the {@code def}
	 * @param arguments the call's arguments
	 * @param scope where the arguments' names resolve
	 * @return the call, or {@code null} when the arguments do not fit and the run-time
	 * binder has to answer (with a {@code *} or {@code **} argument, or with the error)
	 */
	private @Nullable LispVal direct(Signature signature, List<Argument> arguments, Scope scope) {
		int[] filledBy = new int[signature.params().size()];
		Arrays.fill(filledBy, -1);
		List<Integer> surplus = new ArrayList<>();
		List<Integer> named = new ArrayList<>();
		// a surplus name written twice is the binder's to refuse
		Set<String> names = new HashSet<>();
		int position = 0;
		for (int i = 0; i < arguments.size(); i++) {
			Argument argument = arguments.get(i);
			if (argument.kind() == ArgumentKind.POSITIONAL) {
				if (position < signature.positional()) {
					filledBy[position++] = i;
				}
				else if (signature.varargs()) {
					surplus.add(i);
				}
				else {
					return null;
				}
			}
			else if (argument.kind() == ArgumentKind.KEYWORD) {
				int parameter = signature.named(keyword(argument));
				if (parameter >= 0 && filledBy[parameter] < 0) {
					filledBy[parameter] = i;
				}
				else if (parameter < 0 && signature.kwargs() && names.add(keyword(argument))) {
					named.add(i);
				}
				else {
					return null;
				}
			}
			else {
				return null;
			}
		}
		for (int parameter = 0; parameter < filledBy.length; parameter++) {
			if (filledBy[parameter] < 0 && signature.defaults().get(parameter) == null) {
				return null;
			}
		}
		List<LispVal> operands = arguments.stream().map(argument -> expression(argument.value(), scope)).toList();
		List<Integer> order = new ArrayList<>();
		for (int source : filledBy) {
			if (source >= 0) {
				order.add(source);
			}
		}
		order.addAll(surplus);
		order.addAll(named);
		return inSourceOrder(operands, order, placed -> {
			List<LispVal> form = new ArrayList<>();
			form.add(new LispSymbol(signature.helper()));
			for (int parameter = 0; parameter < filledBy.length; parameter++) {
				form.add(Objects.requireNonNull(filledBy[parameter] >= 0 ? placed.get(filledBy[parameter])
						: signature.defaults().get(parameter)));
			}
			if (signature.varargs()) {
				form.add(cons(MAKE_TUPLE, listOf(surplus.stream().map(placed::get).toList())));
			}
			if (signature.kwargs()) {
				List<LispVal> entries = new ArrayList<>();
				for (int source : named) {
					entries.add(string(keyword(arguments.get(source))));
					entries.add(placed.get(source));
				}
				form.add(cons(MAKE_DICT, listOf(entries)));
			}
			return listOf(form);
		});
	}

	/**
	 * The general tier: the callee, the list of its positional arguments and the list of
	 * its named ones (name, value, name, value ...), bound when the call runs.
	 */
	private LispVal general(LispVal callee, List<Argument> arguments, Scope scope) {
		List<LispVal> operands = new ArrayList<>();
		operands.add(callee);
		List<Integer> positional = new ArrayList<>();
		List<Integer> named = new ArrayList<>();
		List<Integer> star = new ArrayList<>();
		List<Integer> starStar = new ArrayList<>();
		for (int i = 0; i < arguments.size(); i++) {
			Argument argument = arguments.get(i);
			operands.add(expression(argument.value(), scope));
			(switch (argument.kind()) {
				case POSITIONAL -> positional;
				case KEYWORD -> named;
				case STAR -> star;
				case STAR_STAR -> starStar;
			}).add(i + 1);
		}
		List<Integer> order = new ArrayList<>(List.of(0));
		order.addAll(positional);
		order.addAll(star);
		order.addAll(named);
		order.addAll(starStar);
		return inSourceOrder(operands, order, placed -> {
			List<LispVal> entries = new ArrayList<>();
			for (int source : named) {
				entries.add(string(keyword(arguments.get(source - 1))));
				entries.add(placed.get(source));
			}
			return list(CALL, placed.get(0),
					spliced(listOf(positional.stream().map(placed::get).toList()),
							star.isEmpty() ? null : list(STAR_ELEMENTS, placed.get(star.get(0)))),
					spliced(listOf(entries), starStar.isEmpty() ? null : list(KEYWORDS, placed.get(starStar.get(0)))));
		});
	}

	/** The list of {@code elements}, then of what {@code rest} answers. */
	private static LispVal spliced(LispVal elements, @Nullable LispVal rest) {
		if (elements == LispNil.INSTANCE) {
			return rest == null ? LispNil.INSTANCE : rest;
		}
		LispVal listed = cons(LIST, elements);
		return rest == null ? listed : list(APPEND, listed, rest);
	}

	private static String keyword(Argument argument) {
		return Objects.requireNonNull(argument.name(), "a named argument has a name");
	}

	/**
	 * Places operands in a form that evaluates them in another order than the source
	 * wrote them, keeping the source's order of evaluation: when the form would evaluate
	 * two operands that are more than a constant or a variable the other way round, each
	 * such operand is bound first, in source order. A variable is read where it is
	 * placed: nothing an expression calls can assign it.
	 * @param operands the operands' forms, in source order
	 * @param order the operands' indices in the order the built form evaluates them
	 * @param build the form over the placed operands, by source index
	 * @return the form, inside the bindings when it needs them
	 */
	private LispVal inSourceOrder(List<LispVal> operands, List<Integer> order, Function<List<LispVal>, LispVal> build) {
		int latest = -1;
		boolean inOrder = true;
		for (int index : order) {
			if (!isSimple(operands.get(index))) {
				inOrder &= index > latest;
				latest = index;
			}
		}
		if (inOrder) {
			return build.apply(operands);
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> placed = new ArrayList<>(operands);
		for (int i = 0; i < operands.size(); i++) {
			if (!isSimple(operands.get(i))) {
				LispSymbol held = fresh("ARG");
				bindings.add(list(held, operands.get(i)));
				placed.set(i, held);
			}
		}
		return list(LET_STAR, listOf(bindings), build.apply(placed));
	}

	/** A constant or a variable: evaluating it does nothing and cannot fail. */
	private static boolean isSimple(LispVal form) {
		return !(form instanceof LispCons cons) || QUOTE.equals(cons.car()) || FUNCTION.equals(cons.car());
	}

	/** A built-in function as a value. */
	private static LispVal builtinValue(Signature signature) {
		return cons(BUILTIN, listOf(description(signature)));
	}

	/** {@code receiver.name} as a value: the method bound to its receiver. */
	private static LispVal methodValue(Signature signature, LispVal receiver) {
		return cons(METHOD, cons(receiver, cons(quotedStrings(signature.kinds()), listOf(description(signature)))));
	}

	/** What the run-time binder needs of a signature, as constants. */
	private static List<LispVal> description(Signature signature) {
		List<LispVal> defaults = new ArrayList<>();
		boolean anyDefault = false;
		for (LispVal value : signature.defaults()) {
			anyDefault |= value != null;
			defaults.add(value == null ? StarlarkBuiltins.REQUIRED : value);
		}
		return List.of(string(signature.name()), quotedStrings(signature.params()),
				anyDefault ? list(QUOTE, listOf(defaults)) : LispNil.INSTANCE, new LispInteger(signature.positional()),
				new LispInteger(signature.namedFrom()), bool(signature.varargs()), bool(signature.kwargs()),
				list(FUNCTION, new LispSymbol(signature.helper())));
	}

	// ---- the run-time library's generated half ----

	/**
	 * The definitions {@code starlark.lisp} takes from the tables here, generated so each
	 * is written once: the attributes of each type, which {@code dir}, {@code getattr}
	 * and {@code hasattr} read, and the code points a string's {@code repr} escapes.
	 * @return the forms, in the library's canonical shape
	 */
	static List<LispVal> runtimeForms() {
		LispSymbol type = new LispSymbol("TYPE");
		LispSymbol x = new LispSymbol("X");
		LispSymbol name = new LispSymbol("NAME");
		LispSymbol cond = new LispSymbol("COND");
		LispSymbol stringEquals = new LispSymbol("STRING=");
		Map<String, List<String>> names = new TreeMap<>();
		List<LispVal> attributes = new ArrayList<>();
		for (Signature method : StarlarkBuiltins.methods()) {
			for (String kind : method.kinds()) {
				names.computeIfAbsent(kind, k -> new ArrayList<>()).add(method.name());
			}
			attributes.add(list(list(stringEquals, name, string(method.name())), methodValue(method, x)));
		}
		attributes.add(list(LispTrue.INSTANCE, list(NO_ATTRIBUTE, x, name)));
		List<LispVal> byType = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : names.entrySet()) {
			byType.add(list(list(stringEquals, type, string(entry.getKey())),
					quotedStrings(entry.getValue().stream().sorted().toList())));
		}
		byType.add(list(LispTrue.INSTANCE, LispNil.INSTANCE));
		List<LispVal> ranges = new ArrayList<>();
		int start = -1;
		for (int code = 0xA0; code <= Character.MAX_CODE_POINT + 1; code++) {
			boolean unprintable = code <= Character.MAX_CODE_POINT && isUnprintable(code);
			if (unprintable && start < 0) {
				start = code;
			}
			else if (!unprintable && start >= 0) {
				ranges.add(new LispInteger(start));
				ranges.add(new LispInteger(code - 1));
				start = -1;
			}
		}
		return List.of(list(DEFUN, runtime("ATTRIBUTE-NAMES"), list(type), cons(cond, listOf(byType))),
				list(DEFUN, runtime("ATTRIBUTE"), list(x, name), cons(cond, listOf(attributes))),
				list(DEFUN, runtime("UNPRINTABLE-RANGES"), LispNil.INSTANCE, list(QUOTE, listOf(ranges))));
	}

	/**
	 * Whether a string's {@code repr} escapes the code point, as Go's
	 * {@code strconv.IsPrint} answers past U+00A0: a control, format, private-use,
	 * surrogate or separator character. An unassigned code point prints as itself here.
	 */
	private static boolean isUnprintable(int code) {
		return switch (Character.getType(code)) {
			case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE,
					Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR ->
				true;
			default -> false;
		};
	}

	// ---- forms ----

	private LispSymbol fresh(String kind) {
		return new LispSymbol("%SL-" + kind + (++this.counter));
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%STARLARK-" + name);
	}

	private static LispVal string(String value) {
		return LispString.literal(value);
	}

	private static LispVal bool(boolean value) {
		return value ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	private static LispVal quotedStrings(List<String> strings) {
		if (strings.isEmpty()) {
			return LispNil.INSTANCE;
		}
		return list(QUOTE, listOf(strings.stream().map(StarlarkLowering::string).toList()));
	}

	private static LispVal progn(List<LispVal> forms) {
		if (forms.isEmpty()) {
			return LispNil.INSTANCE;
		}
		return forms.size() == 1 ? forms.get(0) : cons(PROGN, listOf(forms));
	}

	private static LispVal cons(LispVal car, LispVal cdr) {
		return new LispCons(car, cdr);
	}

	private static LispVal list(LispVal... elements) {
		return listOf(List.of(elements));
	}

	private static LispVal listOf(List<? extends LispVal> elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			list = new LispCons(elements.get(i), list);
		}
		return list;
	}

	private static LispReadException error(String message, SourceLocation at) {
		return new LispReadException(message, at);
	}

}
