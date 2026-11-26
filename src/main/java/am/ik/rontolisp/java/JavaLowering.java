package am.ik.rontolisp.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.java.JavaClass.Field;
import am.ik.rontolisp.java.JavaClass.Method;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaEmitter.Invocation;
import am.ik.rontolisp.java.JavaEmitter.Typed;
import am.ik.rontolisp.java.JavaInitOrder.Effects;
import am.ik.rontolisp.java.JavaSyntax.ArrayInitializer;
import am.ik.rontolisp.java.JavaSyntax.Assign;
import am.ik.rontolisp.java.JavaSyntax.Binary;
import am.ik.rontolisp.java.JavaSyntax.Block;
import am.ik.rontolisp.java.JavaSyntax.BooleanLiteral;
import am.ik.rontolisp.java.JavaSyntax.Break;
import am.ik.rontolisp.java.JavaSyntax.Call;
import am.ik.rontolisp.java.JavaSyntax.Cast;
import am.ik.rontolisp.java.JavaSyntax.Catch;
import am.ik.rontolisp.java.JavaSyntax.Close;
import am.ik.rontolisp.java.JavaSyntax.CharLiteral;
import am.ik.rontolisp.java.JavaSyntax.CompilationUnit;
import am.ik.rontolisp.java.JavaSyntax.Conditional;
import am.ik.rontolisp.java.JavaSyntax.ConstructorCall;
import am.ik.rontolisp.java.JavaSyntax.Continue;
import am.ik.rontolisp.java.JavaSyntax.Declarator;
import am.ik.rontolisp.java.JavaSyntax.DoWhile;
import am.ik.rontolisp.java.JavaSyntax.DoubleLiteral;
import am.ik.rontolisp.java.JavaSyntax.Empty;
import am.ik.rontolisp.java.JavaSyntax.Expression;
import am.ik.rontolisp.java.JavaSyntax.ExpressionStatement;
import am.ik.rontolisp.java.JavaSyntax.For;
import am.ik.rontolisp.java.JavaSyntax.ForEach;
import am.ik.rontolisp.java.JavaSyntax.If;
import am.ik.rontolisp.java.JavaSyntax.Index;
import am.ik.rontolisp.java.JavaSyntax.InstanceOf;
import am.ik.rontolisp.java.JavaSyntax.IntLiteral;
import am.ik.rontolisp.java.JavaSyntax.Lambda;
import am.ik.rontolisp.java.JavaSyntax.LocalVariable;
import am.ik.rontolisp.java.JavaSyntax.Name;
import am.ik.rontolisp.java.JavaSyntax.New;
import am.ik.rontolisp.java.JavaSyntax.NewArray;
import am.ik.rontolisp.java.JavaSyntax.NullLiteral;
import am.ik.rontolisp.java.JavaSyntax.Parameter;
import am.ik.rontolisp.java.JavaSyntax.QualifiedThis;
import am.ik.rontolisp.java.JavaSyntax.Return;
import am.ik.rontolisp.java.JavaSyntax.Select;
import am.ik.rontolisp.java.JavaSyntax.Statement;
import am.ik.rontolisp.java.JavaSyntax.StringLiteral;
import am.ik.rontolisp.java.JavaSyntax.This;
import am.ik.rontolisp.java.JavaSyntax.Throw;
import am.ik.rontolisp.java.JavaSyntax.Try;
import am.ik.rontolisp.java.JavaSyntax.TypeName;
import am.ik.rontolisp.java.JavaSyntax.Unary;
import am.ik.rontolisp.java.JavaSyntax.While;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.java.JavaType.TypeParameter;
import am.ik.rontolisp.java.JavaType.Variable;
import am.ik.rontolisp.java.JavaType.Wildcard;
import am.ik.rontolisp.java.JavaTypes.Inference;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

import static am.ik.rontolisp.java.JavaForms.BREAK_BLOCK;
import static am.ik.rontolisp.java.JavaForms.CONTINUE_BLOCK;
import static am.ik.rontolisp.java.JavaForms.FUNCTION_BLOCK;
import static am.ik.rontolisp.java.JavaForms.NIL;
import static am.ik.rontolisp.java.JavaForms.T;
import static am.ik.rontolisp.java.JavaForms.THIS;
import static am.ik.rontolisp.java.JavaForms.call;
import static am.ik.rontolisp.java.JavaForms.cl;
import static am.ik.rontolisp.java.JavaForms.form;
import static am.ik.rontolisp.java.JavaForms.function;
import static am.ik.rontolisp.java.JavaForms.integer;
import static am.ik.rontolisp.java.JavaForms.isSimple;
import static am.ik.rontolisp.java.JavaForms.list;
import static am.ik.rontolisp.java.JavaForms.progn;
import static am.ik.rontolisp.java.JavaForms.quote;
import static am.ik.rontolisp.java.JavaForms.runtimeCall;
import static am.ik.rontolisp.java.JavaForms.string;

/**
 * Lowers a Java program -- the classes its file declares and the classes of the library
 * it names -- to the Common Lisp core forms the pipeline already consumes, so it joins
 * {@code CompileFrontend.expand} and {@code LispEvaluator} unchanged and no backend
 * learns a Java name. The lowering table and the decisions behind each row:
 * {@code .kb/java-frontend.md}.
 *
 * <p>
 * The shape of the pass:
 * <ul>
 * <li><b>Static types pick the form.</b> Every expression is lowered to a form and its
 * type ({@link Typed}): {@code +} is a string concatenation, a wrapped integer addition
 * or a double addition by its operands' types, and a conversion -- a widening, a boxing,
 * a number's text -- is made where the types say, never tested when the program
 * runs.</li>
 * <li><b>A class is a structure</b>, {@code extends} its {@code :include}, an instance
 * field a slot, a static field a global, an instance method a function taking
 * {@code this} first. A method's function carries its signature in its name
 * ({@link JavaNames}), so overloads never meet.</li>
 * <li><b>The program is closed, so a call is direct when it can be.</b> One
 * implementation under the receiver's static type is a plain call; several are one
 * function testing the structure predicates, deepest class first, made once per static
 * type and signature. A lambda is a {@code lambda}: an interface no class implements is
 * called with {@code funcall}.</li>
 * <li><b>A type parameter is erased.</b> A member is typed through the arguments of the
 * type it is read through ({@link JavaTypes}), a call's type arguments are found from its
 * arguments, its target and its lambdas, and all that reaches the forms is the
 * conversions the types then ask for: no cast, no bridge.</li>
 * <li><b>A construct the subset does not have is refused by name</b>, at its position,
 * and so is a program a compiler refuses where the lowering would otherwise have to
 * guess: nothing is lowered as something else.</li>
 * <li><b>Lowered until the class table is still.</b> A library class is read where the
 * program first names it, which can be inside a method body lowered after a call whose
 * dispatch it changes; {@link #lower} lowers again from the start when a pass added a
 * class.</li>
 * </ul>
 */
final class JavaLowering implements JavaEmitter.Context {

	/** A local variable, a parameter or a pattern's binding. */
	private static final class Local {

		final LispSymbol symbol;

		final JavaType type;

		/**
		 * What declares it, which names it to the flow ({@link JavaFlow.Facts}): a
		 * declarator, a parameter, a pattern, an enhanced {@code for}, a {@code catch}.
		 */
		final Object key;

		/** Whether it is {@code final} and has its value where it is declared. */
		boolean isFinal;

		/**
		 * Its value when it is a constant variable: {@code final}, of a primitive type or
		 * {@code String}, with a constant initializer.
		 */
		@Nullable Object constant;

		/** The method it is a written parameter of, or {@code null}. */
		@Nullable Method parameterOf;

		/** Its index among the parameters of {@link #parameterOf}. */
		int parameterIndex;

		Local(LispSymbol symbol, JavaType type, Object key) {
			this.symbol = symbol;
			this.type = type;
			this.key = key;
		}

		LispSymbol symbol() {
			return this.symbol;
		}

		JavaType type() {
			return this.type;
		}

	}

	/**
	 * A scope of names: a block's locals and local classes. A scope that starts a local
	 * class's body marks where a read from inside becomes a capture.
	 */
	private static final class Scope {

		final Map<String, Local> locals = new HashMap<>();

		final Map<String, JavaClass> classes = new HashMap<>();

		/** The local or anonymous class whose body starts here, or {@code null}. */
		final @Nullable JavaClass boundary;

		Scope(@Nullable JavaClass boundary) {
			this.boundary = boundary;
		}

	}

	/** A function body being lowered: whether a {@code return} leaves from its middle. */
	private static final class Frame {

		boolean exits;

		/** Whether it is an initializer, which no {@code return} leaves. */
		boolean initializer;

	}

	/**
	 * What a {@code break}, a {@code continue} or a {@code yield} can leave: a loop, a
	 * switch, a labeled statement.
	 */
	private static final class Target {

		final @Nullable String label;

		final boolean isLoop;

		/**
		 * Whether an unlabeled {@code break} stops here: a loop or a switch statement.
		 */
		final boolean takesBreak;

		/** Whether it is a switch expression: only a {@code yield} leaves it. */
		final boolean isExpression;

		final LispSymbol breakBlock;

		final LispSymbol continueBlock;

		boolean breaks;

		boolean continues;

		Target(@Nullable String label, boolean isLoop, boolean takesBreak, boolean isExpression) {
			this.label = label;
			this.isLoop = isLoop;
			this.takesBreak = takesBreak;
			this.isExpression = isExpression;
			this.breakBlock = isExpression ? YIELD_BLOCK
					: label == null ? BREAK_BLOCK : new LispSymbol(label + "%break");
			this.continueBlock = label == null ? CONTINUE_BLOCK : new LispSymbol(label + "%continue");
		}

	}

	/** What a simple name means where the lowering stands. */
	private sealed interface Named {

	}

	private record LocalRef(Local local) implements Named {
	}

	/** A local of an enclosing method read from a local class: a slot of the class. */
	private record CaptureRef(Typed read) implements Named {
	}

	/**
	 * A field: of the class the lowering stands in, or of a class around it.
	 *
	 * @param field the field
	 * @param object the instance holding it, or {@code null} for a static field and where
	 * no instance can be reached
	 * @param through the class it was found from, whose supertypes give its type
	 */
	private record FieldRef(Field field, @Nullable LispVal object, JavaClass through) implements Named {
	}

	/**
	 * Where a value is stored: how it is read, and how a new one is written.
	 *
	 * @param type what a read of it is
	 * @param stored what a value written to it must be: a field read through a wildcard
	 * is read as the wildcard's bound and takes only a value of the wildcard's capture
	 */
	private record Place(JavaType type, LispVal read, Function<LispVal, LispVal> write, List<LispVal> bindings,
			JavaType stored) {

		Place(JavaType type, LispVal read, Function<LispVal, LispVal> write, List<LispVal> bindings) {
			this(type, read, write, bindings, type);
		}

	}

	private static final LispSymbol OBJECT_STRING = new LispSymbol("%string");

	private static final LispSymbol OBJECT_EQUALS = new LispSymbol("%equals");

	private static final LispSymbol OBJECT_HASH = new LispSymbol("%hash");

	private static final LispSymbol CLASS_NAME = new LispSymbol("%class-name");

	/** The function refusing a value a bridge casts: {@link #castRefused}. */
	private static final LispSymbol CAST_REFUSED = new LispSymbol("%cast-refused");

	private static final LispSymbol SIMPLE_NAME = new LispSymbol("%simple-name");

	private static final LispSymbol LISP_ERROR = new LispSymbol("%lisp-error");

	private static final LispSymbol THROWN = new LispSymbol("%thrown");

	/** The functions reading, writing and swapping the field a field updater names. */
	private static final LispSymbol FIELD_GET = new LispSymbol("%field-get");

	private static final LispSymbol FIELD_SET = new LispSymbol("%field-set");

	private static final LispSymbol FIELD_SWAP = new LispSymbol("%field-swap");

	/**
	 * The classes of the exceptions the run time raises as Lisp conditions, which
	 * {@code %java-exception-of} in {@code java.lisp} -- the one table from a condition
	 * to an exception -- answers: a {@code catch} above one of them makes the program's
	 * object of that class from the condition.
	 */
	static final List<String> RAISED = List.of("java.lang.ArithmeticException",
			"java.lang.ArrayIndexOutOfBoundsException", "java.lang.NullPointerException",
			"java.lang.NegativeArraySizeException", "java.lang.StringIndexOutOfBoundsException",
			"java.lang.IllegalArgumentException", "java.lang.MatchException",
			"java.util.IllegalFormatConversionException", "java.util.UnknownFormatConversionException",
			"java.util.MissingFormatArgumentException", "java.lang.RuntimeException");

	/** The classes and interfaces above {@code Long} or {@code java.util.Date}. */
	private static final Set<String> MILLIS_HOLDERS = Set.of("java.lang.Object", "java.lang.Number",
			"java.lang.Comparable", "java.io.Serializable", "java.lang.Cloneable", "java.lang.constant.Constable",
			"java.lang.constant.ConstantDesc", "java.lang.Long", "java.util.Date");

	/**
	 * The refusals of the conversions of a format the run time leaves to the library
	 * ({@code java.util.Conversions}), raised as the run time's own, in a program whose
	 * controls can name those conversions.
	 */
	static final List<String> FORMAT_RAISED = List.of("java.util.FormatFlagsConversionMismatchException",
			"java.util.IllegalFormatFlagsException", "java.util.IllegalFormatPrecisionException",
			"java.util.MissingFormatWidthException");

	/**
	 * The classes whose intrinsic and {@code native} members read or write what is
	 * outside the program: a static initialization calling one is not run ahead.
	 */
	private static final Set<String> VISIBLE = Set.of(JavaIntrinsics.STANDARD_STREAM, "java.lang.IO",
			"java.io.StandardOutput", "java.io.StandardInput");

	/** The members of a {@code java.util.concurrent.locks.Lock} that take it. */
	private static final Set<String> LOCK_TAKING = Set.of("lock", "lockInterruptibly", "tryLock");

	private static final LispSymbol RECEIVER = new LispSymbol("%o");

	private static final LispSymbol YIELD_BLOCK = new LispSymbol("%yield");

	private static final LispSymbol OUTER = new LispSymbol("%outer");

	/** The instance around an anonymous class's superclass, given where it is made. */
	private static final LispSymbol SUPER_OUTER = new LispSymbol("%super-outer");

	/** The names an enum constructor's first two parameters have. */
	private static final List<String> ENUM_PARAMETERS = List.of("%name", "%ordinal");

	private static final Object NOT_CONSTANT = new Object();

	private final JavaClasses classes;

	private final Source program;

	private final List<JavaClass> declared;

	private final ClassType stringType;

	private final ClassType objectType;

	private final JavaTypes types;

	// ---- what one pass makes

	private final List<LispVal> functions = new ArrayList<>();

	private final Map<String, LispVal> dispatchers = new LinkedHashMap<>();

	/** The fields a field updater was made for, where the lowering saw it made. */
	private final Set<Field> updated = new LinkedHashSet<>();

	private final List<LispVal> variables = new ArrayList<>();

	/** The settings of the constant strings a program compares by reference. */
	private final List<LispVal> internings = new ArrayList<>();

	/**
	 * The settings of the static initialization that cannot throw: the class objects, the
	 * constant fields, the defaults a field holds ahead of its initializer, the flags of
	 * the lazy classes.
	 */
	private final List<LispVal> settings = new ArrayList<>();

	private final List<LispVal> initializers = new ArrayList<>();

	/** The interfaces {@code instanceof} or a cast tests. */
	private final Set<JavaClass> interfaceTests = new LinkedHashSet<>();

	private final Set<LispSymbol> generated = new LinkedHashSet<>();

	/**
	 * The definition of {@link #CAST_REFUSED}, as the last bridge asking for it made it.
	 */
	private @Nullable LispVal castRefusal;

	/**
	 * The name functions asked of a value that can be something other than an instance of
	 * a class of the program -- a string, a box, an array, a lambda: those end in the run
	 * time's answer, the others as a dispatcher ends.
	 */
	private final Set<LispSymbol> namesOfAny = new LinkedHashSet<>();

	/** The test of each catch clause's classes, by the classes ({@link #catchTest}). */
	private final Map<String, LispSymbol> catchTestNames = new HashMap<>();

	private final Map<String, LispVal> catchTests = new LinkedHashMap<>();

	/**
	 * The classes of {@link #RAISED} some {@code catch} of the program takes: the ones
	 * {@code %lisp-error} makes.
	 */
	private final Set<String> caughtRaised = new TreeSet<>();

	/** Whether the program throws objects: its entry's handler reports one. */
	private boolean raises;

	private final Map<Field, Object> constants = new HashMap<>();

	/** The string forms known to be no {@code null}. */
	private final Set<LispVal> nonNull = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * The constant text a string concatenation's value starts and ends with, by its form:
	 * what bounds the resources a name it computes may find.
	 */
	private final Map<LispVal, Affixes> affixes = new IdentityHashMap<>();

	/**
	 * The text every value of a string expression starts with and ends with, the two
	 * apart: empty where nothing is known.
	 */
	private record Affixes(String prefix, String suffix) {

		static final Affixes NONE = new Affixes("", "");

	}

	/** What a string expression's every value starts and ends with. */
	private Affixes affixes(Typed value) {
		Object constant = value.constant();
		return constant != null ? new Affixes(String.valueOf(constant), String.valueOf(constant))
				: this.affixes.getOrDefault(value.form(), Affixes.NONE);
	}

	/**
	 * The types of the elements of an array a call's variable arity made, by its form.
	 */
	private final Map<LispVal, List<JavaType>> spread = new IdentityHashMap<>();

	/** The forms reading {@code Locale.US} or {@code Locale.ENGLISH} (readsEnglish). */
	private final Set<LispVal> englishReads = Collections.newSetFromMap(new IdentityHashMap<>());

	private int temporaries;

	// ---- where the lowering stands

	private JavaClass currentClass;

	private JavaClasses.Unit unit;

	private boolean staticContext;

	private JavaType returnType = Primitive.VOID;

	/**
	 * The types of the values the lambda being lowered returns, when the type of its
	 * value is still to be found by the call it is an argument of; {@code null} anywhere
	 * else.
	 */
	private @Nullable List<JavaType> returns;

	/** Whether the function body being lowered is a lambda's. */
	private boolean lambdaBody;

	/**
	 * The name a compiler gives the method the code being lowered stands in -- a lambda's
	 * {@code lambda$main$0}, a constructor's {@code <init>}, a class's initialization
	 * {@code <clinit>} --: what a call of a logger tells it it was made from.
	 */
	private @Nullable String methodName;

	/** The static field whose initializer is being lowered, or {@code null}. */
	private @Nullable Initializing initializing;

	/**
	 * A static initializer being lowered.
	 *
	 * @param owner its class
	 * @param pos where it stands among the class's initializers
	 * @param field the field it initializes, or {@code null} for a block
	 */
	private record Initializing(JavaClass owner, int pos, @Nullable Field field) {
	}

	/** Whether a static field is read ahead of its own initializer. */
	private boolean forward(Field field) {
		Initializing running = this.initializing;
		return running != null && field.owner == running.owner()
				&& (field.pos > running.pos() || field == running.field()) && field.form == null
				&& field.initializer != null && constantOf(field) == null;
	}

	private Deque<Scope> scopes = new ArrayDeque<>();

	private final Deque<Frame> frames = new ArrayDeque<>();

	private final Deque<Target> targets = new ArrayDeque<>();

	/** What the function being lowered does. */
	private Effects effects = new Effects();

	private final Map<Method, Effects> methodEffects = new LinkedHashMap<>();

	/** The static initialization of each class: its forms, in the class's order. */
	private final Map<JavaClass, List<LispVal>> staticInits = new LinkedHashMap<>();

	private final Map<JavaClass, Effects> initEffects = new LinkedHashMap<>();

	/** What the flow of the classes lowered reads ({@link JavaFlow}). */
	private final JavaFlow.Facts facts = new JavaFlow.Facts();

	/** What the forms do not say of themselves that {@link JavaResumable} reads. */
	private final JavaResumable.Facts resumable = new JavaResumable.Facts();

	/**
	 * Whether the target has one stack: a thread of the program is then a body suspended
	 * and resumed ({@link JavaResumable}).
	 */
	private boolean suspended;

	/** What is told the analysis of the program's threads, when something asks. */
	private java.util.function.@Nullable Consumer<JavaResumable.Census> counted;

	/** The local and anonymous classes lowered already in this pass. */
	private final Set<JavaClass> loweredLocals = new LinkedHashSet<>();

	/**
	 * The anonymous classes made with {@code <>} in this pass, and what lowering each
	 * made: the class may be lowered again against another supertype.
	 */
	private final Map<JavaClass, Lowering> inferredLowerings = new HashMap<>();

	/**
	 * What lowering an anonymous class made: its functions and the classes lowered with
	 * it, itself first.
	 */
	private record Lowering(List<LispVal> forms, List<JavaClass> classes) {
	}

	/** The bindings of the patterns of the statement being lowered. */
	private List<Local> patternVariables = new ArrayList<>();

	/**
	 * Whether the lowering stands ahead of a constructor's {@code this(..)} or
	 * {@code super(..)}, where the instance is not to be read.
	 */
	private boolean early;

	/** The constructor or initializer being lowered: where a final field is assigned. */
	private @Nullable JavaClass constructing;

	private boolean constructingStatic;

	/** Whether an enum constant's own instance creation is being lowered. */
	private boolean makingConstant;

	private final JavaInitOrder order;

	/** The compile path's record of each file of the program, made where first asked. */
	private Map<Source, SourceProvenance.@Nullable Unit> provenance = new IdentityHashMap<>();

	/** The start of each line of each file of the program, for the interpreter. */
	private final Map<Source, int[]> lineStarts = new IdentityHashMap<>();

	private JavaLowering(JavaClasses classes, List<JavaClass> declared, Source program, JavaInitOrder order) {
		this.classes = classes;
		this.declared = declared;
		this.program = program;
		this.order = order;
		classes.localScope = this::localClass;
		this.stringType = classes.libraryType("java.lang.String");
		this.objectType = classes.libraryType("java.lang.Object");
		this.types = new JavaTypes(classes);
		this.facts.types = this.types;
		this.currentClass = declared.get(0);
		this.unit = classes.unit(this.currentClass);
	}

	/**
	 * Lowers a program: the file it starts at and the classes it names from the other
	 * files of its source tree, whose root is the directory the file's package names it
	 * below, as a source launcher finds them.
	 * @param tree the program's file
	 * @param source its text
	 * @param file the origin file, or {@code null} when unknown: no other file is read
	 * @param files where the other files are read
	 * @return the top-level forms
	 * @throws LispReadException at the first construct the subset does not have and at
	 * the first error a compiler reports that the lowering depends on
	 */
	static List<LispVal> lower(CompilationUnit tree, String source, @Nullable String file, JavaFiles files) {
		return lower(tree, source, file, files, null, false, null);
	}

	/**
	 * What a session's buffer asks of the lowering ({@link JavaSession}) and what the
	 * lowering tells it back. A session's program is lowered whole for every buffer, so a
	 * call and a dispatch see every class and method declared so far; what must not run
	 * again is the static initialization of a class an earlier buffer initialized.
	 */
	static final class Session {

		/** The classes the buffer declares: their static initialization starts over. */
		final Set<String> declared;

		/** The classes whose static state an earlier buffer set up. */
		final Set<String> initialized;

		/** The classes whose static state this buffer sets up, or has from before. */
		final Set<String> initializing = new HashSet<>();

		/**
		 * The type each variable of the session's class declared with {@code var} got.
		 */
		final Map<String, JavaType> inferred = new LinkedHashMap<>();

		/**
		 * What an earlier buffer's values may be, which a later buffer's dispatch must
		 * reach although it makes none: the classes of the library's Java source an
		 * instance of was made, and the interfaces a lambda was made for -- every one of
		 * them a structure holding the function, so that no later buffer finds a lambda
		 * of an interface it tells from another untagged. What this buffer makes is
		 * added.
		 */
		final Set<String> made;

		final Set<String> lambdas;

		/**
		 * Whether a buffer reached {@code Thread}: a thread it started may hold a
		 * monitor.
		 */
		boolean monitors;

		/**
		 * The regions of the tz database an earlier buffer held, and whether one held
		 * them all: a zone it made lives on in a variable, and what a later buffer does
		 * with it has to be lowered for a region.
		 */
		final Set<String> regions = new java.util.TreeSet<>();

		boolean everyRegion;

		/**
		 * The numbers of the uses of the JDK's members and their classes' tests: a
		 * {@code Method} an earlier buffer found holds its numbers.
		 */
		final Map<String, Integer> jdkNumbers = new HashMap<>();

		/**
		 * The arms of {@code %jdk-tests}, {@code %jdk-methods} and {@code %jdk-fields}
		 * the buffers so far made: a later buffer's functions answer the numbers of its
		 * own uses and of these.
		 */
		final JdkArmSet jdkArms = new JdkArmSet();

		Session(Set<String> declared, Set<String> initialized, Set<String> made, Set<String> lambdas,
				boolean monitors) {
			this.declared = declared;
			this.initialized = initialized;
			this.made = new LinkedHashSet<>(made);
			this.lambdas = new LinkedHashSet<>(lambdas);
			this.monitors = monitors;
		}

	}

	/** A class of the library by its canonical name, a member class's included. */
	private static @Nullable JavaClass known(JavaClasses classes, String name) {
		if (name.contains("&")) {
			// the interface of a lambda cast to an intersection
			List<JavaClass> interfaces = new ArrayList<>();
			for (String each : name.split("&")) {
				JavaClass found = known(classes, each);
				if (found == null) {
					return null;
				}
				interfaces.add(found);
			}
			return classes.intersection(interfaces);
		}
		JavaClass found = classes.qualified(name);
		int dot = name.lastIndexOf('.');
		if (found != null || dot < 0) {
			return found;
		}
		JavaClass outer = known(classes, name.substring(0, dot));
		return outer == null ? null : outer.members.get(name.substring(dot + 1));
	}

	/**
	 * Whether the program is a file Common Lisp loads: one with no {@code main} defines
	 * its classes and runs nothing.
	 */
	private boolean loaded;

	/** The buffer of a session being lowered, or {@code null} for a file's program. */
	private @Nullable Session session;

	/**
	 * Lowers a program, a session's buffer when {@code session} is given: then no
	 * {@code main} is looked for, every class is initialized at its first use, and only
	 * the classes the buffer declares or never initialized are set up.
	 */
	static List<LispVal> lower(CompilationUnit tree, String source, @Nullable String file, JavaFiles files,
			@Nullable Session session, boolean loaded, java.util.function.@Nullable Consumer<String> warnings) {
		return lower(tree, source, file, files, session, loaded, warnings, false);
	}

	/**
	 * {@link #lower(CompilationUnit, String, String, JavaFiles, Session, boolean, java.util.function.Consumer)}
	 * for a target told.
	 * @param suspended whether the target has one stack, where a thread of the program is
	 * a body suspended and resumed
	 */
	static List<LispVal> lower(CompilationUnit tree, String source, @Nullable String file, JavaFiles files,
			@Nullable Session session, boolean loaded, java.util.function.@Nullable Consumer<String> warnings,
			boolean suspended) {
		return lower(tree, source, file, files, session, loaded, warnings, suspended, null);
	}

	/**
	 * The lowering for a target with one stack, telling what {@link JavaResumable} found
	 * of the program's threads.
	 * @param counted what is told the analysis, or {@code null}
	 */
	static List<LispVal> lower(CompilationUnit tree, String source, @Nullable String file, JavaFiles files,
			@Nullable Session session, boolean loaded, java.util.function.@Nullable Consumer<String> warnings,
			boolean suspended, java.util.function.@Nullable Consumer<JavaResumable.Census> counted) {
		return lower(tree, source, file, files, session, loaded, warnings, suspended, counted, JavaBoundary.NONE);
	}

	/**
	 * The lowering against a host boundary: the WIT a {@code @WitImport} interface names
	 * is read through it, and it tells whether the target's host provides such an
	 * interface ({@link JavaWitImports}).
	 * @param boundary the boundary
	 */
	static List<LispVal> lower(CompilationUnit tree, String source, @Nullable String file, JavaFiles files,
			@Nullable Session session, boolean loaded, java.util.function.@Nullable Consumer<String> warnings,
			boolean suspended, java.util.function.@Nullable Consumer<JavaResumable.Census> counted,
			JavaBoundary boundary) {
		return lower(tree, source, file, files, session, loaded, warnings, suspended, counted, boundary, null,
				List.of());
	}

	/**
	 * The lowering of a program, or of the run of a file's tests ({@link JavaTests}): the
	 * program then starts at a generated class running the tests the file's classes
	 * declare.
	 * @param tests what the run is said to be of where it finds no test, or {@code null}
	 * for the program the file is
	 * @param testFiles the further files of the run of tests, of the file's source tree
	 */
	static List<LispVal> lower(CompilationUnit tree, String source, @Nullable String file, JavaFiles files,
			@Nullable Session session, boolean loaded, java.util.function.@Nullable Consumer<String> warnings,
			boolean suspended, java.util.function.@Nullable Consumer<JavaResumable.Census> counted,
			JavaBoundary boundary, @Nullable String tests, List<String> testFiles) {
		Source program = new Source(file, source, JavaClass.Origin.PROGRAM);
		if (tree.classes().isEmpty()) {
			throw JavaClasses.error(program, 0, "the file declares no class");
		}
		String root = "";
		if (file != null && tree.packageName() != null) {
			// the directories above the file spell its package, as a source launcher
			// requires: the root of the source tree is above them
			String[] directories = files.absolute(file).replace('\\', '/').split("/");
			String[] names = tree.packageName().split("\\.");
			boolean matches = directories.length > names.length;
			for (int i = 0; matches && i < names.length; i++) {
				matches = names[i].equals(directories[directories.length - 1 - names.length + i]);
			}
			if (!matches) {
				throw JavaClasses.error(program, 0,
						"end of path to source file does not match its package name " + tree.packageName());
			}
			root = "../".repeat(names.length);
		}
		JavaClasses classes = new JavaClasses(JavaIntrinsics.LIBRARY, files, file, root, JavaTypes::new);
		classes.wit.boundary = boundary;
		classes.session = session != null;
		// a later buffer may ask the class of an array an earlier one widened, and the
		// constants of an enum whose class object an earlier one made
		classes.arrayTypes = session != null;
		classes.enumConstants = session != null;
		List<JavaClass> declared = classes.declare(tree, program);
		JavaClass suite = tests == null ? null : JavaTests.declare(classes, declared, tests, testFiles, program);
		if (session != null) {
			// a class the buffer declares reads the session's variables and methods as
			// statically imported ones
			classes.unit(declared.get(0)).staticOnDemand().add(declared.get(0));
			for (String name : session.made) {
				JavaClass made = known(classes, name);
				if (made != null) {
					classes.made.add(made);
				}
			}
			for (String name : session.lambdas) {
				JavaClass target = known(classes, name);
				if (target != null) {
					classes.lambdaTargets.add(target);
					classes.taggedLambdas.add(target);
				}
			}
			classes.monitors |= session.monitors;
			classes.regions.addAll(session.regions);
			classes.everyRegion |= session.everyRegion;
		}
		Map<Source, SourceProvenance.@Nullable Unit> provenance = new IdentityHashMap<>();
		JavaInitOrder order = new JavaInitOrder();
		order.allLazy = session != null;
		while (true) {
			int version = classes.version();
			JavaLowering lowering = new JavaLowering(classes, declared, program, order);
			lowering.provenance = provenance;
			lowering.session = session;
			lowering.suite = suite;
			lowering.loaded = loaded;
			lowering.suspended = suspended;
			lowering.counted = counted;
			List<LispVal> forms;
			classes.wit.startPass();
			try {
				forms = lowering.program();
			}
			catch (JavaClass.Absent ex) {
				throw JavaClasses.error(lowering.unit.source(), 0, Objects.requireNonNull(ex.getMessage()));
			}
			if (classes.version() == version) {
				forms = lowering.resumed(forms);
				// every @WitImport interface is checked against its WIT, used or not; the
				// directives binding what the program reached of one come first
				classes.wit.checkAll();
				List<LispVal> directives = classes.wit.directives();
				if (!directives.isEmpty()) {
					List<LispVal> all = new ArrayList<>(directives);
					all.addAll(forms);
					forms = all;
				}
				// every @WitExport class is checked against its world, exported or not;
				// the worlds a program with no main exports come last
				classes.exports.checkAll();
				if (classes.exports.entries) {
					List<LispVal> all = new ArrayList<>(forms);
					all.addAll(classes.exports.directives());
					forms = all;
				}
				// and a reactor module's exports, the entry class's public static methods
				if (classes.reactor.entry != null) {
					List<LispVal> all = new ArrayList<>(forms);
					all.addAll(classes.reactor.directives());
					forms = all;
				}
				if (classes.census != null) {
					classes.census.written(program, lowering.loweredMethods, lowering.loweredInits);
				}
				if (session != null) {
					classes.made.forEach(made -> session.made.add(made.name));
					classes.lambdaTargets.forEach(target -> session.lambdas.add(target.name));
					session.monitors = classes.monitors;
					session.regions.addAll(classes.regions);
					session.everyRegion = classes.everyRegion;
				}
				String warned = lowering.warnings.text(program);
				if (warnings != null && !warned.isEmpty()) {
					warnings.accept(warned);
				}
				return forms;
			}
		}
	}

	// ---- errors and positions

	/** The warnings a compiler gives of the program. */
	private final JavaWarnings warnings = new JavaWarnings();

	/** An unchecked warning, said where the caret goes. */
	private void unchecked(int caret, String message, List<String> details, List<JavaType> mentioned) {
		this.warnings.unchecked(this.unit, caret, message, details, mentioned);
	}

	/**
	 * An unchecked warning about a value converted to the type it is wanted as, said
	 * after what was said while the value was lowered.
	 * @param since how many warnings there were before the value was lowered
	 */
	private void unchecked(int caret, int since, String message, List<String> details, List<JavaType> mentioned) {
		this.warnings.unchecked(this.unit, caret, since, message, details, mentioned);
	}

	/**
	 * Where a compiler puts the caret of what it says of a call: the parenthesis after a
	 * method's name, an instance creation's {@code new}; {@code -1} where the call is
	 * written as no such thing (a method reference).
	 * @param pos the call's position: the method's name, or {@code new}
	 * @param name the method's name, or {@code null} for a constructor
	 */
	private int callCaret(int pos, @Nullable String name) {
		String text = this.unit.source().text();
		String written = name == null ? "new" : name;
		if (pos < 0 || !text.startsWith(written, pos)) {
			return -1;
		}
		if (name == null) {
			return pos;
		}
		int at = pos + name.length();
		while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
			at++;
		}
		return at < text.length() && text.charAt(at) == '(' ? at : -1;
	}

	/**
	 * Where a compiler puts the caret of what it says of an expression: a call's
	 * parenthesis, a field access's dot, an operator, the start of anything else.
	 */
	private int caret(Expression written) {
		String text = this.unit.source().text();
		switch (written) {
			case Call call: {
				int at = callCaret(call.pos(), call.name());
				return at >= 0 ? at : call.pos();
			}
			case Select select: {
				int at = select.pos() - 1;
				while (at > 0 && Character.isWhitespace(text.charAt(at))) {
					at--;
				}
				return at >= 0 && text.charAt(at) == '.' ? at : select.pos();
			}
			default:
				return written.pos();
		}
	}

	private LispReadException error(int pos, String message) {
		return JavaClasses.error(this.unit.source(), pos, message);
	}

	@Override
	public LispReadException unsupported(int pos, String what) {
		return JavaClasses.unsupported(this.unit.source(), pos, what);
	}

	/**
	 * A form located at a node's position, as a reader locates what it reads: recorded in
	 * the compile path's table, or for the interpreter answered as a located copy -- so
	 * the caller uses the value returned. Only the program's own files are located.
	 */
	private LispVal located(LispVal form, int pos) {
		Source source = this.unit.source();
		String file = source.file();
		if (source.library() || file == null || !(form instanceof LispCons cons) || form instanceof LocatedCons) {
			return form;
		}
		SourceProvenance.Unit recorded = this.provenance.computeIfAbsent(source,
				read -> SourceProvenance.unit(file, read.text()));
		if (recorded != null) {
			SourceProvenance.record(cons, recorded, pos);
			return form;
		}
		int[] starts = this.lineStarts.computeIfAbsent(source, read -> {
			List<Integer> found = new ArrayList<>(List.of(0));
			for (int i = 0; i < read.text().length(); i++) {
				if (read.text().charAt(i) == '\n') {
					found.add(i + 1);
				}
			}
			return found.stream().mapToInt(Integer::intValue).toArray();
		});
		int found = java.util.Arrays.binarySearch(starts, pos);
		return new LocatedCons(cons.car(), cons.cdr(), file, found >= 0 ? found + 1 : -found - 1);
	}

	@Override
	public LispSymbol temporary() {
		return new LispSymbol("%t" + (this.temporaries++));
	}

	// ---- the program

	private List<LispVal> program() {
		this.launched = launched();
		exportEntries();
		if (this.suite != null) {
			// generated source is lowered as far as it is reached: from its entry
			reach(Objects.requireNonNull(mainOf(this.suite)));
		}
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			if (lowered(declaration) && (declaration.outer == null || !lowered(declaration.outer))) {
				lowerTree(declaration);
				flow(declaration);
			}
		}
		// the library's Java source: what the program reaches of it, and what that
		// reaches
		List<LispVal> generated;
		int reached;
		do {
			reached = this.loweredMethods.size() + this.loweredInits.size();
			lowerReached();
			generated = generatedFunctions();
			lowerReached();
		}
		while (reached != this.loweredMethods.size() + this.loweredInits.size());
		this.generationOver = true;
		boolean runs = this.session == null && !(this.loaded && mainOf(launched()) == null)
				&& !this.classes.exports.entries && this.classes.reactor.entry == null;
		LispVal entry = runs ? entry() : NIL;
		List<LispVal> initialization = initialization();
		if (runs && !initialization.isEmpty()) {
			// the static initializers that run before main are a function of their own:
			// a form is one method of a JVM class, and a form with an exit is not cut
			this.functions.add(list(cl("defun"), INITIALIZATION, NIL, progn(initialization)));
			initialization = List.of(list(INITIALIZATION));
		}
		List<LispVal> made = new ArrayList<>();
		made.addAll(this.variables);
		made.addAll(this.functions);
		made.addAll(this.dispatchers.values());
		made.addAll(this.catchTests.values());
		made.addAll(generated);
		made.addAll(this.internings);
		made.addAll(this.settings);
		LispVal started = runs ? uncaught(initialization, entry)
				: this.classes.exports.entries && this.classes.wit.boundary.reportsUncaught()
						? reportingExports(initialization) : progn(initialization);
		if (this.reporter != null) {
			made.add(this.reporter);
		}
		made.add(started);
		Set<String> named = new HashSet<>();
		for (LispVal form : made) {
			names(form, named);
		}
		List<LispVal> forms = new ArrayList<>();
		Set<JavaClass> defined = new HashSet<>();
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			if (!isPartial(declaration) || isLive(declaration) || isNamed(declaration, named)) {
				structure(declaration, defined, forms);
			}
		}
		for (JavaClass target : this.classes.taggedLambdas) {
			forms.add(list(cl("defstruct"), list(lambdaStructure(target),
					list(new LispSymbol(":CONSTRUCTOR"), lambdaMaker(target), list(LAMBDA_FUNCTION)),
					list(new LispSymbol(":PREDICATE"), lambdaTest(target)), list(new LispSymbol(":COPIER"), NIL)),
					LAMBDA_FUNCTION));
		}
		forms.addAll(made);
		if (this.suspended && scheduled() && !runs) {
			throw unsupported(0, "a program with no main starting threads or a server on a target with one stack");
		}
		return List.copyOf(forms);
	}

	/**
	 * The program's forms as a thread suspended and resumed runs them, where the target
	 * has one stack and the program starts threads or serves: run on the pass whose
	 * classes did not change, the one whose forms are kept -- each earlier pass is
	 * lowered again, and the analysis is as long as a pass's lowering.
	 */
	private List<LispVal> resumed(List<LispVal> forms) {
		if (!this.suspended || !scheduled()) {
			return forms;
		}
		this.resumable.dispatchers.addAll(this.dispatchers.keySet());
		for (LispSymbol dispatching : List.of(OBJECT_STRING, OBJECT_EQUALS, OBJECT_HASH)) {
			this.resumable.dispatchers.add(dispatching.name());
		}
		for (JavaClass declaration : this.classes.all()) {
			if (isLibrary(declaration)) {
				this.resumable.library.add(declaration.lispName());
			}
		}
		return JavaResumable.rewrite(jdkSuspending(forms), this.resumable,
				what -> JavaClasses.unsupported(this.program, 0, what), this.counted);
	}

	/**
	 * The forms with {@code %jdk-methods}' arms that can suspend a thread apart from the
	 * others ({@link #jdkFunctions}), which a body lowered resumable would cost: of the
	 * shiori's 309 arms, four (2026-11-25). The analysis is asked of each arm made a
	 * function of its own, then {@code %jdk-methods} takes one of two functions by the
	 * number.
	 */
	private List<LispVal> jdkSuspending(List<LispVal> forms) {
		LispSymbol methods = new LispSymbol("%jdk-methods");
		int at = -1;
		for (int i = 0; i < forms.size(); i++) {
			if (forms.get(i) instanceof LispCons defun && defun.toList().size() == 4
					&& defun.toList().get(0).equals(cl("defun")) && defun.toList().get(1).equals(methods)) {
				at = i;
			}
		}
		if (at < 0 || !(((LispCons) forms.get(at)).toList().get(3) instanceof LispCons body)) {
			return forms;
		}
		LispVal parameters = ((LispCons) forms.get(at)).toList().get(2);
		List<LispVal> arms = body.toList().subList(1, body.toList().size());
		List<LispVal> probed = new ArrayList<>(forms);
		List<LispVal> dispatch = new ArrayList<>(List.of(cl("cond")));
		for (int i = 0; i < arms.size(); i++) {
			List<LispVal> arm = ((LispCons) arms.get(i)).toList();
			LispSymbol named = new LispSymbol("%jdk-arm-" + i);
			probed.add(list(cl("defun"), named, parameters, arm.get(1)));
			dispatch.add(list(arm.get(0), form(named, ((LispCons) parameters).toList())));
		}
		probed.set(at, list(cl("defun"), methods, parameters, list(dispatch)));
		Set<String> resumable = JavaResumable.resumable(probed, this.resumable,
				what -> JavaClasses.unsupported(this.program, 0, what));
		List<LispVal> suspending = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> plain = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> which = new ArrayList<>(List.of(cl("or")));
		for (int i = 0; i < arms.size(); i++) {
			boolean suspends = resumable.contains("%jdk-arm-" + i);
			(suspends ? suspending : plain).add(arms.get(i));
			if (suspends) {
				which.add(((LispCons) arms.get(i)).toList().get(0));
			}
		}
		if (suspending.size() == 1 || plain.size() == 1) {
			return forms;
		}
		LispSymbol apart = new LispSymbol("%jdk-methods-suspending");
		LispSymbol rest = new LispSymbol("%jdk-methods-plain");
		List<LispVal> split = new ArrayList<>(forms);
		List<LispVal> passed = ((LispCons) parameters).toList();
		split.set(at, list(cl("defun"), methods, parameters,
				call("if", list(which), form(apart, passed), form(rest, passed))));
		split.add(list(cl("defun"), apart, parameters, list(suspending)));
		split.add(list(cl("defun"), rest, parameters, list(plain)));
		return split;
	}

	// ---- what the program reaches of the library

	/** The library methods and static initializations lowered already in this pass. */
	private final Set<Method> loweredMethods = new LinkedHashSet<>();

	private final Set<JavaClass> loweredInits = new LinkedHashSet<>();

	/** Whether a class is Java source of the library, and no class of the program. */
	private boolean isLibrary(JavaClass declaration) {
		return !declaration.intrinsic && declaration.source != null && declaration.source.library();
	}

	/**
	 * Whether a class is lowered only as far as the program reaches it: Java source of
	 * the library, or of a library the program uses from its source path.
	 */
	private boolean isPartial(JavaClass declaration) {
		return !declaration.intrinsic && declaration.source != null && declaration.source.partial();
	}

	/**
	 * Whether the program can hold an instance of a class: any class of the program, and
	 * a class of the library the program makes an instance of.
	 */
	private boolean isMade(JavaClass declaration) {
		return !declaration.intrinsic && !declaration.isInterface
				&& (!isPartial(declaration) || this.classes.made.contains(declaration));
	}

	/** Whether the program can hold an instance of a class or of a class under it. */
	private boolean isLive(JavaClass declaration) {
		for (JavaClass candidate : this.classes.all()) {
			if (isMade(candidate) && candidate.isSubclassOf(declaration)) {
				return true;
			}
		}
		return false;
	}

	/** Records a call; a method of the library's Java source is lowered because of it. */
	private void called(Method method) {
		if (this.classes.census != null) {
			this.classes.census.names(method);
		}
		if (this.passing == method) {
			this.passing = null;
		}
		else {
			Map<Integer, Set<JavaEmitter.Naming>> namings = this.classes.namingParameters.get(method);
			if (namings != null) {
				// reached otherwise than by a call whose arguments were noted
				namings.values().forEach(each -> each.forEach(naming -> naming.name(this, null)));
			}
		}
		this.effects.calls.add(method);
		reach(method);
	}

	/**
	 * The static or private method whose call is being lowered, whose arguments at the
	 * parameters whose texts name something have named it: {@link #called} notes nothing
	 * more.
	 */
	private @Nullable Method passing;

	/**
	 * Notes what a call of a static or private method passes at the parameters whose
	 * texts name something ({@link JavaClasses#namingParameters}).
	 */
	private void passes(Method method, List<Typed> arguments) {
		Map<Integer, Set<JavaEmitter.Naming>> namings = this.classes.namingParameters.get(method);
		if (namings == null || arguments.size() != method.parameters.size()) {
			return;
		}
		namings.forEach((index, each) -> each.forEach(naming -> names(arguments.get(index), naming)));
		this.passing = method;
	}

	private void reach(Method method) {
		if (method.emitter == null && isPartial(method.owner)) {
			if (this.classes.reached.add(method) && this.reaching != null) {
				this.classes.reachedFrom.put(method, this.reaching);
			}
			use(method.owner);
			String member = method.owner.name + "." + method.name;
			if (JavaIntrinsics.watched(member) && this.classes.reachedMembers.add(member)) {
				// what a question this member answers decided is lowered again
				this.classes.changed();
			}
			if (JavaIntrinsics.watchedOutside(member) && !this.unit.source().library()
					&& this.classes.reachedOutside.add(member)) {
				this.classes.changed();
			}
			if (!this.classes.annotationsRead && this.unit.source().origin() == JavaClass.Origin.PROGRAM
					&& (method.name.equals("getAnnotation") || method.name.equals("getDeclaredAnnotation"))
					&& (method.owner.name.equals(CLASS)
							|| method.owner.name.equals("java.lang.reflect.AnnotatedElement"))) {
				// the program's own code reads an annotation: what it answers of equals,
				// hashCode and toString is a JDK's
				this.classes.annotationsRead = true;
				this.classes.changed();
			}
			if (!this.classes.calendars && method.owner.name.equals(TEMPORAL_QUERIES)
					&& method.name.equals("chronology")) {
				// the classes of java.time answer the question of a calendar again
				this.classes.calendars = true;
				this.classes.changed();
			}
			if (!this.classes.monitors && (method.owner.name.equals(THREAD) || isServer(method.owner))) {
				// what was lowered holding no monitor is lowered again holding one: a
				// thread of the program's, or the threads a server's handlers run on
				this.classes.monitors = true;
				this.classes.changed();
			}
		}
	}

	/** Records that a class is initialized by what is being lowered. */
	private void uses(JavaClass declaration) {
		this.effects.uses.add(declaration);
		use(declaration);
	}

	private void use(JavaClass declaration) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			if (isPartial(type)) {
				this.classes.used.add(type);
			}
		}
	}

	/**
	 * Records that an instance of a class is made. The first one of a class of the
	 * library changes which bodies a call can run, so the program is lowered again.
	 */
	private void makes(JavaClass declaration) {
		if (isPartial(declaration) && this.classes.made.add(declaration)) {
			this.classes.changed();
		}
	}

	/**
	 * Lowers the methods of the library's Java source called so far and the static
	 * initialization of its classes used so far, until none is left: each reaches more. A
	 * local or an anonymous class is lowered whole where it is declared.
	 */
	private void lowerReached() {
		boolean more = true;
		while (more) {
			more = false;
			for (Method method : List.copyOf(this.classes.reached)) {
				if (method.owner.isLocal || method.isAbstract || !this.loweredMethods.add(method)) {
					continue;
				}
				more = true;
				this.currentClass = method.owner;
				this.unit = this.classes.unit(method.owner);
				this.reaching = method;
				Effects saved = this.effects;
				Deque<Scope> around = this.scopes;
				int depth = around.size();
				try {
					if (method.isConstructor()) {
						constructor(method);
					}
					else {
						method(method);
					}
				}
				catch (LispReadException | JavaClass.Absent ex) {
					if (!method.isConstructor() && throwing(method, ex, saved, around, depth)) {
						continue;
					}
					if (!counted(ex, saved, around, depth)) {
						throw ex instanceof LispReadException read ? reachedThrough(method, read) : ex;
					}
				}
				finally {
					this.reaching = null;
				}
			}
			for (JavaClass declaration : List.copyOf(this.classes.used)) {
				if (declaration.isLocal || !this.loweredInits.add(declaration)) {
					continue;
				}
				more = true;
				this.currentClass = declaration;
				this.unit = this.classes.unit(declaration);
				Effects saved = this.effects;
				Deque<Scope> around = this.scopes;
				int depth = around.size();
				try {
					staticInitialization(declaration);
				}
				catch (LispReadException | JavaClass.Absent ex) {
					if (!counted(ex, saved, around, depth)) {
						throw ex;
					}
				}
			}
		}
	}

	/**
	 * Notes what a body of a library the program uses refused, while a census is taken
	 * ({@link JavaCensus}): the body is left where it was refused and the lowering goes
	 * on.
	 * @return whether it was noted; the refusal is thrown otherwise
	 */
	private boolean counted(RuntimeException refused) {
		JavaCensus census = this.classes.census;
		if (census == null || !this.unit.source().dependency()) {
			return false;
		}
		census.refuses(refused,
				this.currentClass.name + (this.reaching != null ? "." + this.reaching.signature() : ".<clinit>"));
		return true;
	}

	/**
	 * What a statement of the body of a library the program uses throws where it runs,
	 * the statement naming what the subset lacks -- a class nobody has, a class or a
	 * method of a JDK's the library lacks --, or {@code null} where the refusal stands:
	 * in the program's own files, in the library's own source, while a census is taken. A
	 * JVM links a class's code to what it names where that code first runs, so a
	 * library's code that names a class the subset lacks is no error until it runs: the
	 * statement throws the error such a link throws, saying what the subset lacks, and
	 * the compilation warns of it.
	 * @param refused what the statement's lowering refused
	 * @param pos where the statement is: the place of a class nobody has, which no
	 * expression located
	 */
	private @Nullable LispVal deferred(RuntimeException refused, int pos) {
		if (this.classes.census != null || !this.unit.source().dependency()) {
			return null;
		}
		JavaClass.Absent absent = refused instanceof JavaClass.Absent named ? named
				: refused.getCause() instanceof JavaClass.Absent named ? named : null;
		LispReadException why;
		String error;
		String named;
		SourceLocation at;
		if (absent != null) {
			why = Objects.requireNonNull(absent.named.absent);
			error = JavaClasses.NO_CLASS;
			named = absent.named.name;
			at = refused instanceof LispReadException read ? read.location() : null;
		}
		else if (refused instanceof JavaClasses.Missing missing) {
			why = missing;
			error = missing.error;
			named = missing.named;
			at = missing.location();
		}
		else {
			return null;
		}
		String file = this.unit.source().file();
		SourceLocation place = at != null && Objects.equals(at.file(), file) ? at
				: SourceLocation.at(file, pos, this.unit.source().text());
		Method in = this.reaching;
		LispVal thrown = throwsWhereItRuns(why, error, named, place,
				in == null ? "" : reachedThrough(in, new LispReadException("")).reason());
		// what throws is seen: a static initialization that may is run at its class's
		// first use
		this.effects.visible = true;
		return thrown;
	}

	/**
	 * What {@link #deferred} makes of a refusal: the form throwing the error, saying what
	 * the subset lacks, and the compilation's warning of it at its place.
	 */
	private LispVal throwsWhereItRuns(LispReadException why, String error, @Nullable String named, SourceLocation place,
			String way) {
		// a class whose own file was refused is told with the refusal and its place
		String reason = named != null && this.classes.withdrawn(named)
				? "the class " + named + " cannot be read: " + why.getMessage() : why.reason();
		if (named != null && !reason.contains(named)) {
			// a class no root of the source path has
			reason = "the class " + named + " cannot be read: " + reason;
		}
		this.warnings.deferred(place.prefix() + reason + "; where it runs, it throws " + error + way);
		return raise(list(creator(error), string(reason)));
	}

	/**
	 * What reflection answers of a member of a library's class whose types name a class
	 * nobody has -- a class of a JDK's the library lacks among them --: the
	 * {@code NoClassDefFoundError} a JDK's reflection throws over a class whose member
	 * names a class its class path lacks, where the program asks for the member, and the
	 * compilation's warning of it at the member's place. The refusal stands in the
	 * program's own classes and while a census is taken.
	 * @param pos where the member is declared, or 0
	 */
	private LispVal describedAbsent(JavaClass declaration, int pos, JavaClass.Absent absent) {
		Source source = declaration.source;
		if (this.classes.census != null || source == null || !source.dependency()) {
			throw absent;
		}
		return throwsWhereItRuns(Objects.requireNonNull(absent.named.absent), JavaClasses.NO_CLASS, absent.named.name,
				SourceLocation.at(source.file(), pos, source.text()), "");
	}

	/**
	 * A method of a library the program uses whose lowering refused what it names outside
	 * its statements (a parameter's type): a function throwing where it is called, as
	 * {@link #deferred} says.
	 * @return whether it was made; the refusal stands otherwise
	 */
	private boolean throwing(Method method, RuntimeException refused, Effects saved, Deque<Scope> around, int depth) {
		JavaSyntax.Method written = method.declaration;
		Effects effects = new Effects();
		this.effects = effects;
		LispVal thrown = deferred(refused, written == null ? 0 : written.pos());
		this.effects = saved;
		this.scopes = around;
		while (this.scopes.size() > depth) {
			this.scopes.pop();
		}
		this.classes.methodParameters = List.of();
		if (thrown == null) {
			return false;
		}
		this.methodEffects.put(method, effects);
		List<LispVal> parameters = new ArrayList<>();
		if (!method.isStatic) {
			parameters.add(THIS);
		}
		for (int i = 0; i < method.parameters.size(); i++) {
			parameters.add(new LispSymbol("%a" + i));
		}
		this.functions.add(form(cl("defun"), List.of(JavaNames.function(method), list(parameters),
				list(cl("declare"), form(cl("ignorable"), parameters)), thrown)));
		return true;
	}

	/** {@link #counted(RuntimeException)}, back in the scope the body was entered in. */
	private boolean counted(RuntimeException refused, Effects saved, Deque<Scope> around, int depth) {
		if (!counted(refused)) {
			return false;
		}
		this.effects = saved;
		this.scopes = around;
		while (this.scopes.size() > depth) {
			this.scopes.pop();
		}
		this.classes.methodParameters = List.of();
		return true;
	}

	/**
	 * The method of a library whose body is being lowered because the program reaches it,
	 * or {@code null} while the program's own classes are.
	 */
	private @Nullable Method reaching;

	/**
	 * What a method of a library the program uses refuses, told with the way the program
	 * comes to it: the refusal stands in a file the program's author did not write, so
	 * the methods between it and the program are named, the nearest first.
	 */
	private LispReadException reachedThrough(Method method, LispReadException refused) {
		Source source = method.owner.source;
		if (source == null || !source.dependency() || refused.reason().contains("\n  reached through ")) {
			return refused;
		}
		StringBuilder way = new StringBuilder();
		Set<Method> seen = new HashSet<>();
		for (Method at = method; at != null && seen.add(at); at = this.classes.reachedFrom.get(at)) {
			way.append("\n  reached through ").append(at.owner.name).append('.').append(at.signature());
		}
		return new LispReadException(refused.reason() + way, refused.location());
	}

	/** The names of the symbols of a form. */
	private static void names(LispVal form, Set<String> into) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			names(cons.car(), into);
			rest = cons.cdr();
		}
		if (rest instanceof LispSymbol symbol) {
			into.add(symbol.name());
		}
	}

	/**
	 * Whether the forms name the structure of a class the program makes no instance of:
	 * its predicate, by an {@code instanceof} or a {@code catch}, or one of its slots.
	 */
	private static boolean isNamed(JavaClass declaration, Set<String> named) {
		if (declaration.intrinsic || declaration.isInterface) {
			return false;
		}
		if (named.contains(JavaNames.predicate(declaration).name())
				|| named.contains(JavaNames.allocator(declaration).name())) {
			return true;
		}
		for (Field field : declaration.fields) {
			if (!field.isStatic && named.contains(JavaNames.accessor(field).name())) {
				return true;
			}
		}
		return false;
	}

	private static final LispSymbol LAMBDA_FUNCTION = new LispSymbol("%f");

	/**
	 * The structure a lambda made for an interface is when the program can tell which
	 * interface a lambda was made for: it holds the function.
	 */
	private static LispSymbol lambdaStructure(JavaClass target) {
		return JavaNames.symbol(target.lispName() + "%lambda");
	}

	private static LispSymbol lambdaMaker(JavaClass target) {
		return JavaNames.symbol("%make-" + target.lispName() + "%lambda");
	}

	private static LispSymbol lambdaTest(JavaClass target) {
		return JavaNames.symbol(target.lispName() + "%lambda?");
	}

	/** The function a lambda that is a structure holds. */
	private static LispVal lambdaFunction(JavaClass target, LispVal lambda) {
		return list(new LispSymbol(lambdaStructure(target).name() + "-" + LAMBDA_FUNCTION.name()), lambda);
	}

	/** The slot holding the instance of the class a class's declaration stands in. */
	private static String outerSlot(JavaClass declaration) {
		return "this$" + declaration.depth();
	}

	private static LispSymbol slotAccessor(JavaClass declaration, String slot) {
		return new LispSymbol(declaration.lispName() + "." + JavaNames.mangle(slot));
	}

	private void structure(JavaClass declaration, Set<JavaClass> defined, List<LispVal> out) {
		if (declaration.intrinsic || declaration.isInterface || !defined.add(declaration)) {
			return;
		}
		List<LispVal> header = new ArrayList<>();
		header.add(JavaNames.structure(declaration));
		if (declaration.superclass != null) {
			structure(declaration.superclass, defined, out);
			header.add(list(new LispSymbol(":INCLUDE"), JavaNames.structure(declaration.superclass)));
		}
		header.add(list(new LispSymbol(":CONC-NAME"), JavaNames.accessorPrefix(declaration)));
		header.add(list(new LispSymbol(":CONSTRUCTOR"), JavaNames.allocator(declaration), NIL));
		header.add(list(new LispSymbol(":PREDICATE"), JavaNames.predicate(declaration)));
		header.add(list(new LispSymbol(":COPIER"), NIL));
		List<LispVal> structure = new ArrayList<>();
		structure.add(cl("defstruct"));
		structure.add(list(header));
		if (declaration.hasOuterInstance()) {
			structure.add(list(JavaNames.slot(outerSlot(declaration)), NIL));
		}
		for (JavaClass.Capture capture : declaration.captures.values()) {
			structure.add(list(JavaNames.slot(capture.slot()), NIL));
		}
		for (Field field : declaration.fields) {
			if (field.isStatic) {
				continue;
			}
			structure.add(list(JavaNames.slot(field.slot()), defaultValue(field.type)));
		}
		out.add(list(structure));
	}

	private static LispVal defaultValue(JavaType type) {
		if (type.isFloating()) {
			return new LispDouble(0.0);
		}
		return type.isIntegral() ? integer(0) : NIL;
	}

	/** A class that can be instantiated implements every abstract method above it. */
	private void implementations(JavaClass declaration) {
		if (declaration.isAbstract) {
			return;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		// as a compiler names it: of the first type that has one -- the superclass ahead
		// of the interfaces -- the one declared last (asked of javac over seven shapes)
		for (JavaClass type : above) {
			for (Method method : type.methods.reversed()) {
				if (method.isAbstract && implementation(declaration, method) == null) {
					String name = declaration.simpleName().isEmpty() ? "<anonymous " + declaration.binaryName + ">"
							: declaration.simpleName();
					throw JavaClasses.error(declaration.source, Objects.requireNonNull(declaration.declaration).pos(),
							name + " is not abstract and does not override abstract method " + method.signature()
									+ " in " + type.simpleName());
				}
			}
		}
	}

	private static void supertypes(JavaClass declaration, Set<JavaClass> into) {
		if (!into.add(declaration.present())) {
			return;
		}
		if (declaration.superclass != null) {
			supertypes(declaration.superclass, into);
		}
		if (declaration.intrinsicSuperclass != null) {
			supertypes(declaration.intrinsicSuperclass, into);
		}
		for (JavaClass implemented : declaration.interfaces) {
			supertypes(implemented, into);
		}
	}

	private void lowerClass(JavaClass declaration) {
		this.currentClass = declaration;
		this.unit = this.classes.unit(declaration);
		checkFunctional(declaration);
		if (!isPartial(declaration)) {
			inheritedThrows(declaration);
		}
		for (Method method : List.copyOf(declaration.methods)) {
			this.currentClass = declaration;
			this.unit = this.classes.unit(declaration);
			JavaSyntax.Method written = method.declaration;
			if (written != null && !isPartial(declaration)) {
				overridden(method, written.pos());
			}
			if (method.varargs && written != null && !written.modifiers().contains("@SafeVarargs")) {
				JavaType elements = ((ArrayType) method.parameters.get(method.parameters.size() - 1)).element();
				if (!reifiable(elements)) {
					unchecked(written.parameters().get(written.parameters().size() - 1).pos(),
							"Possible heap pollution from parameterized vararg type " + elements, List.of(),
							List.of(elements));
				}
			}
			if (method.isConstructor()) {
				constructor(method);
			}
			else if (!method.isAbstract && method.emitter == null) {
				method(method);
			}
		}
		staticInitialization(declaration);
	}

	/**
	 * Whether a class is lowered with the program: one of its own that is no local class.
	 */
	private boolean lowered(JavaClass declaration) {
		return !declaration.intrinsic && !declaration.isLocal && !isPartial(declaration);
	}

	/** A class and its member classes, each typed before the flow of any is asked. */
	private void lowerTree(JavaClass declaration) {
		implementations(declaration);
		lowerClass(declaration);
		for (JavaClass member : List.copyOf(declaration.members.values())) {
			if (lowered(member)) {
				lowerTree(member);
			}
		}
	}

	/**
	 * What the flow of a class and of the classes declared in it refuses, once they are
	 * all typed: a compiler types the whole of a top-level class before its flow.
	 */
	private void flow(JavaClass declaration) {
		JavaFlow.Problem problem = JavaFlow.check(declaration, this.facts);
		this.currentClass = declaration;
		this.unit = this.classes.unit(declaration);
		if (problem != null) {
			throw error(problem.pos(), problem.message());
		}
		for (JavaFlow.Problem warning : this.facts.warnings) {
			this.warnings.warn(this.unit, warning.pos(), warning.message());
		}
		this.facts.warnings.clear();
	}

	/**
	 * A class's static initialization: the initializers of its static fields and its
	 * static blocks, in the order they are written. When it runs is
	 * {@link #initialization}'s.
	 */
	private void staticInitialization(JavaClass declaration) {
		record Item(int pos, @Nullable Field field, JavaSyntax.@Nullable Initializer block) {
		}
		List<Item> items = new ArrayList<>();
		for (Field field : declaration.fields) {
			if (!field.isStatic) {
				continue;
			}
			// a variable holds its type's default from its declaration on (JLS 12.4:
			// before
			// its initializer runs, and when the step giving it its value fails); one
			// with no
			// value would be a special without one, every read of it a test for unbound
			this.variables.add(list(cl("defvar"), JavaNames.global(field), defaultValue(field.type)));
			if (field.initializer != null && constantOf(field) == null) {
				items.add(new Item(field.pos, field, null));
			}
		}
		for (JavaSyntax.Initializer block : Objects.requireNonNull(declaration.declaration).initializers()) {
			if (block.isStatic()) {
				items.add(new Item(block.pos(), null, block));
			}
		}
		if (items.isEmpty()) {
			return;
		}
		items.sort(Comparator.comparingInt(Item::pos));
		Effects saved = this.effects;
		this.effects = new Effects();
		List<LispVal> forms = new ArrayList<>();
		for (Item item : items) {
			Field field = item.field();
			forms.addAll(inContext(declaration, true, () -> {
				this.constructing = declaration;
				this.constructingStatic = true;
				this.initializing = new Initializing(declaration, item.pos(), field);
				if (field == null) {
					return patterned(() -> scoped(Objects.requireNonNull(item.block()).body(), false));
				}
				this.makingConstant = field.isConstant;
				return List.of(call("setq", JavaNames.global(field), initialValue(field)));
			}));
		}
		this.staticInits.put(declaration, forms);
		this.initEffects.put(declaration, this.effects);
		this.effects = saved;
	}

	/**
	 * The scopes a class's own members are lowered in: none of a method's, and for a
	 * local class the ones its declaration stands in.
	 */
	private Deque<Scope> scopesAround(JavaClass declaration) {
		Deque<Scope> around = new ArrayDeque<>();
		boolean found = false;
		for (Scope scope : this.scopes) {
			found |= scope.boundary == declaration;
			if (found) {
				around.addLast(scope);
			}
		}
		return around;
	}

	/** Runs a lowering with no local of a method in scope, in a class of the program. */
	private <R> R inContext(JavaClass declaration, boolean isStatic, Supplier<R> lowering) {
		JavaClass savedClass = this.currentClass;
		JavaClasses.Unit savedUnit = this.unit;
		boolean savedStatic = this.staticContext;
		Deque<Scope> savedScopes = this.scopes;
		Initializing savedInitializing = this.initializing;
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		boolean savedEarly = this.early;
		boolean savedMaking = this.makingConstant;
		JavaClass savedConstructing = this.constructing;
		boolean savedConstructingStatic = this.constructingStatic;
		JavaType savedReturn = this.returnType;
		List<JavaType> savedReturns = this.returns;
		boolean savedLambda = this.lambdaBody;
		String savedName = this.methodName;
		List<TypeParameter> savedParameters = this.classes.methodParameters;
		this.classes.methodParameters = List.of();
		this.returns = null;
		this.lambdaBody = false;
		this.methodName = isStatic ? "<clinit>" : "<init>";
		this.scopes = scopesAround(declaration);
		this.scopes.push(new Scope(null));
		this.currentClass = declaration;
		this.unit = this.classes.unit(declaration);
		this.staticContext = isStatic;
		this.initializing = null;
		this.targets.clear();
		this.early = false;
		this.makingConstant = false;
		this.returnType = Primitive.VOID;
		Frame frame = new Frame();
		frame.initializer = true;
		this.frames.push(frame);
		try {
			return lowering.get();
		}
		finally {
			this.frames.pop();
			this.currentClass = savedClass;
			this.unit = savedUnit;
			this.staticContext = savedStatic;
			this.scopes = savedScopes;
			this.initializing = savedInitializing;
			this.targets.clear();
			this.targets.addAll(savedTargets);
			this.early = savedEarly;
			this.makingConstant = savedMaking;
			this.constructing = savedConstructing;
			this.constructingStatic = savedConstructingStatic;
			this.returnType = savedReturn;
			this.returns = savedReturns;
			this.lambdaBody = savedLambda;
			this.methodName = savedName;
			this.classes.methodParameters = savedParameters;
		}
	}

	/**
	 * Lowers a local or an anonymous class where it is declared, once a pass: its members
	 * read the locals in scope there, each a slot of the class set where an instance is
	 * made.
	 */
	private void lowerLocal(JavaClass made) {
		this.facts.classes.put(Objects.requireNonNull(made.declaration), made);
		if (!this.loweredLocals.add(made)) {
			return;
		}
		checkFunctional(made);
		JavaClass savedClass = this.currentClass;
		JavaClasses.Unit savedUnit = this.unit;
		boolean savedStatic = this.staticContext;
		JavaType savedReturn = this.returnType;
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		boolean savedEarly = this.early;
		boolean savedMaking = this.makingConstant;
		JavaClass savedConstructing = this.constructing;
		boolean savedConstructingStatic = this.constructingStatic;
		Initializing savedInitializing = this.initializing;
		Effects savedEffects = this.effects;
		List<Local> savedPatterns = this.patternVariables;
		List<JavaType> savedReturns = this.returns;
		boolean savedLambda = this.lambdaBody;
		String savedName = this.methodName;
		List<TypeParameter> savedParameters = this.classes.methodParameters;
		this.patternVariables = new ArrayList<>();
		this.returns = null;
		this.lambdaBody = false;
		this.targets.clear();
		this.early = false;
		this.makingConstant = false;
		this.initializing = null;
		try {
			lowerMembers(made);
		}
		finally {
			this.returns = savedReturns;
			this.lambdaBody = savedLambda;
			this.methodName = savedName;
			this.classes.methodParameters = savedParameters;
			this.currentClass = savedClass;
			this.unit = savedUnit;
			this.staticContext = savedStatic;
			this.returnType = savedReturn;
			this.targets.clear();
			this.targets.addAll(savedTargets);
			this.early = savedEarly;
			this.makingConstant = savedMaking;
			this.constructing = savedConstructing;
			this.constructingStatic = savedConstructingStatic;
			this.initializing = savedInitializing;
			this.effects = savedEffects;
			this.patternVariables = savedPatterns;
		}
	}

	/**
	 * A local class and the member types declared in it, each behind its own boundary.
	 */
	private void lowerMembers(JavaClass made) {
		this.scopes.push(new Scope(made));
		implementations(made);
		lowerClass(made);
		for (JavaClass member : made.members.values()) {
			member.isLocal = true;
			if (this.loweredLocals.add(member)) {
				lowerMembers(member);
			}
		}
		this.scopes.pop();
	}

	private List<Local> bindParameters(Method method) {
		List<Local> locals = new ArrayList<>();
		JavaSyntax.Method written = method.declaration;
		for (int i = 0; i < method.parameters.size(); i++) {
			Local local;
			if (written == null || i < method.hidden) {
				// an enum constructor's name and ordinal; what an anonymous class's
				// constructor hands on
				String name = method.owner.isEnum && i < method.hidden ? ENUM_PARAMETERS.get(i) : "%a" + i;
				local = bind(name, method.inside(method.parameters.get(i)), 0, new Object());
			}
			else {
				Parameter parameter = written.parameters().get(i - method.hidden);
				local = bind(parameter.name(), method.inside(method.parameters.get(i)), parameter.pos(), parameter);
				local.isFinal = parameter.isFinal();
				local.parameterOf = method;
				local.parameterIndex = i;
			}
			locals.add(local);
		}
		return locals;
	}

	private static List<LispSymbol> symbols(List<Local> locals) {
		return locals.stream().map(Local::symbol).toList();
	}

	/**
	 * A body with the integral ones and the arrays among its variables declared of their
	 * types ({@link JavaForms#declaredType}) ahead of its forms.
	 */
	private static List<LispVal> declaring(List<Local> locals, List<LispVal> forms) {
		LispVal declaration = JavaForms.typeDeclaration(symbols(locals), locals.stream().map(Local::type).toList());
		if (declaration == null) {
			return forms;
		}
		List<LispVal> declared = new ArrayList<>(forms.size() + 1);
		declared.add(declaration);
		declared.addAll(forms);
		return declared;
	}

	/**
	 * Locals bound in turn around a body: one {@code let} each where one of them is of an
	 * integral or an array type, which its own {@code let} declares; one {@code let*}
	 * where none is.
	 */
	private static LispVal boundInTurn(List<Declared> locals, List<LispVal> body, String binder) {
		boolean typed = locals.stream().anyMatch(declared -> JavaForms.declaredType(declared.local().type()) != null);
		if (!typed) {
			List<LispVal> bindings = new ArrayList<>();
			locals.forEach(declared -> bindings.add(list(declared.local().symbol(), declared.value())));
			return form(cl(bindings.size() == 1 ? binder : "let*"), prepend(list(bindings), body));
		}
		List<LispVal> inner = body;
		for (int i = locals.size() - 1; i >= 0; i--) {
			Declared declared = locals.get(i);
			inner = List.of(form(cl("let"), prepend(list(list(declared.local().symbol(), declared.value())),
					declaring(List.of(declared.local()), inner))));
		}
		return inner.get(0);
	}

	/** The call running a lazy class's static initialization, or {@code null}. */
	private @Nullable LispVal initializationOf(JavaClass declaration) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			if (this.launched != null && this.launched.isSubclassOf(type)) {
				// initialized where the program starts
				return null;
			}
			if (this.order.isLazy(type)) {
				return list(JavaNames.initializer(type));
			}
		}
		return null;
	}

	private void method(Method method) {
		JavaSyntax.Method written = Objects.requireNonNull(method.declaration);
		// with <>, every method an anonymous class declares that is not private
		// overrides one
		if ((written.modifiers().contains("@Override")
				|| (!method.isPrivate && this.inferredLowerings.containsKey(method.owner))) && !isPartial(method.owner)
				&& !overrides(method)) {
			throw error(written.pos(), "method does not override or implement a method from a supertype");
		}
		this.staticContext = method.isStatic;
		this.returnType = method.inside(method.returnType);
		this.methodName = method.name;
		this.constructing = null;
		this.classes.methodParameters = method.insideParameters();
		Effects saved = this.effects;
		this.effects = new Effects();
		this.methodEffects.put(method, this.effects);
		this.scopes.push(new Scope(null));
		List<LispVal> parameters = new ArrayList<>();
		if (!method.isStatic) {
			parameters.add(THIS);
		}
		List<Local> bound = bindParameters(method);
		parameters.addAll(symbols(bound));
		LispVal body = functionBody(Objects.requireNonNull(written.body()));
		this.scopes.pop();
		this.effects = saved;
		this.classes.methodParameters = List.of();
		if (written.modifiers().contains("synchronized")) {
			// an instance method holds its object's monitor, a static one its class's
			body = holding(method.isStatic ? JavaForms.quote(JavaNames.structure(method.owner)) : THIS,
					new JavaResumable.Lock(method.owner, false, method.isStatic), List.of(body));
		}
		LispVal first = method.isStatic ? initializationOf(method.owner) : null;
		if (first != null) {
			body = call("progn", first, body);
		}
		this.functions.add(form(cl("defun"),
				prepend(JavaNames.function(method), prepend(list(parameters), declaring(bound, List.of(body))))));
	}

	/**
	 * The fields a class's instances start with a value in, and its initializer blocks.
	 */
	private static boolean initializesInstances(JavaClass owner) {
		for (Field field : owner.fields) {
			if (!field.isStatic && field.initializer != null) {
				return true;
			}
		}
		return owner.declaration != null
				&& owner.declaration.initializers().stream().anyMatch(block -> !block.isStatic());
	}

	/**
	 * Whether a constructor does nothing: no parameter, no statement, no field of its
	 * class initialized, and the same above it. Such a constructor is never called.
	 */
	private static boolean isTrivial(Method constructor) {
		JavaSyntax.Method written = constructor.declaration;
		if (!constructor.parameters.isEmpty()
				|| (written != null && !Objects.requireNonNull(written.body()).statements().isEmpty())) {
			return false;
		}
		JavaClass owner = constructor.owner;
		if (initializesInstances(owner)) {
			return false;
		}
		if (constructor.passesTo != null) {
			return isTrivial(constructor.passesTo);
		}
		if (owner.superclass == null) {
			return true;
		}
		for (Method above : owner.superclass.methods) {
			if (above.isConstructor() && above.parameters.isEmpty()) {
				return isTrivial(above);
			}
		}
		return false;
	}

	/**
	 * What every constructor not handing over to another runs after its superclass's: the
	 * initializers of the instance fields and the initializer blocks, in the order they
	 * are written.
	 */
	private List<LispVal> instanceInitializers(JavaClass owner) {
		record Item(int pos, @Nullable Field field, JavaSyntax.@Nullable Initializer block) {
		}
		List<Item> items = new ArrayList<>();
		for (Field field : owner.fields) {
			if (!field.isStatic && field.initializer != null) {
				items.add(new Item(field.pos, field, null));
			}
		}
		for (JavaSyntax.Initializer block : Objects.requireNonNull(owner.declaration).initializers()) {
			if (!block.isStatic()) {
				items.add(new Item(block.pos(), null, block));
			}
		}
		items.sort(Comparator.comparingInt(Item::pos));
		List<LispVal> forms = new ArrayList<>();
		for (Item item : items) {
			Field field = item.field();
			forms.addAll(inContext(owner, false, () -> {
				this.constructing = owner;
				this.constructingStatic = false;
				if (field == null) {
					return patterned(() -> scoped(Objects.requireNonNull(item.block()).body(), false));
				}
				return List.of(call("setf", list(JavaNames.accessor(field), THIS), initialValue(field)));
			}));
		}
		return forms;
	}

	/**
	 * The value a field's initializer gives it; in a class of a library the program uses,
	 * one naming what the subset lacks throws where it runs ({@link #deferred}).
	 */
	private LispVal initialValue(Field field) {
		Expression initializer = Objects.requireNonNull(field.initializer);
		try {
			return patterned(() -> List.of(assigned(initializer, field.type, field.pos))).get(0);
		}
		catch (LispReadException | JavaClass.Absent ex) {
			LispVal thrown = deferred(ex, initializer.pos());
			if (thrown == null) {
				throw ex;
			}
			return thrown;
		}
	}

	/** The {@code this(..)} or {@code super(..)} of the constructor being lowered. */
	private @Nullable ConstructorCall explicitCall;

	private void constructor(Method constructor) {
		JavaClass owner = constructor.owner;
		JavaSyntax.Method written = constructor.declaration;
		int pos = written != null ? written.pos() : Objects.requireNonNull(owner.declaration).pos();
		this.staticContext = false;
		this.returnType = Primitive.VOID;
		this.methodName = "<init>";
		this.classes.methodParameters = constructor.insideParameters();
		Effects saved = this.effects;
		this.effects = new Effects();
		this.methodEffects.put(constructor, this.effects);
		this.scopes.push(new Scope(null));
		List<Local> bound = bindParameters(constructor);
		List<LispSymbol> parameters = symbols(bound);
		List<Statement> statements = written == null ? List.of() : Objects.requireNonNull(written.body()).statements();
		ConstructorCall explicit = null;
		for (Statement statement : statements) {
			if (statement instanceof ExpressionStatement first && first.expression() instanceof ConstructorCall call) {
				explicit = call;
				break;
			}
		}
		if (constructor.passesTo == null && givesSuperOuter(owner)
				&& (explicit == null || (explicit.onSuper() && explicit.outer() == null))) {
			// the instance around an inner superclass the instance around this one does
			// not hold is given by outer.super(..)
			throw error(
					explicit != null ? explicit.pos()
							: written != null ? Objects.requireNonNull(written.body()).pos() : pos,
					"an enclosing instance that contains " + innerName(Objects.requireNonNull(owner.superclass))
							+ " is required");
		}
		Frame frame = new Frame();
		this.frames.push(frame);
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		this.targets.clear();
		this.constructing = owner;
		this.constructingStatic = false;
		List<LispVal> forms = new ArrayList<>();
		if (explicit == null) {
			if (constructor.passesTo != null) {
				// an anonymous class's constructor hands its arguments to its
				// superclass's
				called(constructor.passesTo);
				if (!isTrivial(constructor.passesTo)) {
					forms.add(form(JavaNames.function(constructor.passesTo), prepend(THIS, List.copyOf(parameters))));
				}
			}
			else if (owner.superclass != null) {
				List<Expression> arguments = new ArrayList<>();
				if (owner.isEnum) {
					for (String name : ENUM_PARAMETERS) {
						arguments.add(new JavaSyntax.Name(pos, name));
					}
				}
				// a compiler speaks of a super() not written at the body's brace, of a
				// default constructor's at its class
				forms.add(constructorCall(owner.superclass, arguments, pos,
						written != null ? written : Objects.requireNonNull(owner.declaration),
						written != null ? Objects.requireNonNull(written.body()).pos() : keyword(owner)));
			}
			forms.removeIf(form -> form == NIL);
			forms.addAll(instanceInitializers(owner));
			forms.addAll(sequence(statements, 0, false));
		}
		else {
			this.early = true;
			this.explicitCall = explicit;
			forms.addAll(sequence(statements, 0, false));
			this.explicitCall = null;
			this.early = false;
		}
		this.targets.addAll(savedTargets);
		this.frames.pop();
		this.scopes.pop();
		this.constructing = null;
		this.effects = saved;
		this.classes.methodParameters = List.of();
		boolean trivial = isTrivial(constructor);
		if (!trivial) {
			List<LispVal> all = new ArrayList<>();
			all.add(THIS);
			all.addAll(parameters);
			LispVal body = frame.exits ? form(cl("block"), prepend(FUNCTION_BLOCK, forms)) : progn(forms);
			this.functions.add(form(cl("defun"),
					prepend(JavaNames.function(constructor), prepend(list(all), declaring(bound, List.of(body))))));
		}
		if ((owner.isAbstract && !owner.isEnum) || (isPartial(owner) && !this.classes.made.contains(owner))) {
			// no instance is made: the constructor is reached from a subclass's only
			return;
		}
		// the function making an instance: the slots a nested class starts with -- the
		// instance around it, the locals it reads -- are set ahead of the constructor
		List<LispVal> arguments = new ArrayList<>();
		List<LispVal> setup = new ArrayList<>();
		if (owner.hasOuterInstance()) {
			arguments.add(OUTER);
		}
		if (owner.superOuterGiven) {
			arguments.add(SUPER_OUTER);
		}
		for (JavaClass type = owner; type != null; type = type.superclass) {
			if (!type.hasOuterInstance()) {
				continue;
			}
			LispVal outer = type == owner ? OUTER
					: owner.superOuterGiven && type == owner.superclass ? SUPER_OUTER : outerInstance(
							Objects.requireNonNull(type.outer), owner.outer, owner.hasOuterInstance() ? OUTER : null);
			if (outer != null) {
				// else the constructor of the class under it gives it, by outer.super(..)
				setup.add(call("setf", list(slotAccessor(type, outerSlot(type)), THIS), outer));
			}
		}
		arguments.addAll(parameters);
		int captured = 0;
		for (JavaClass type = owner; type != null; type = type.superclass) {
			for (JavaClass.Capture capture : type.captures.values()) {
				LispSymbol value = new LispSymbol("%c" + captured++);
				arguments.add(value);
				setup.add(call("setf", list(slotAccessor(type, capture.slot()), THIS), value));
			}
		}
		LispVal instance = list(JavaNames.allocator(owner));
		if (!trivial || !setup.isEmpty()) {
			List<LispVal> made = new ArrayList<>(setup);
			if (!trivial) {
				made.add(form(JavaNames.function(constructor), prepend(THIS, List.copyOf(parameters))));
			}
			made.add(THIS);
			instance = JavaForms.let(THIS, instance, made);
		}
		LispVal first = initializationOf(owner);
		if (first != null) {
			instance = call("progn", first, instance);
		}
		this.functions.add(list(cl("defun"), JavaNames.creator(constructor), list(arguments), instance));
	}

	/**
	 * Whether a class's superclass is an inner class whose outer instance the instance
	 * around the class does not hold: a constructor gives it with
	 * {@code outer.super(..)}, an anonymous class takes it from its {@code outer.new}.
	 */
	private static boolean givesSuperOuter(JavaClass owner) {
		JavaClass above = owner.superclass;
		return above != null && above.hasOuterInstance() && outerInstance(Objects.requireNonNull(above.outer),
				owner.outer, owner.hasOuterInstance() ? OUTER : null) == null;
	}

	/** An inner class's name as a compiler says it: {@code Outer.Inner}. */
	private static String innerName(JavaClass inner) {
		return Objects.requireNonNull(inner.outer).simpleName() + "." + inner.simpleName();
	}

	/**
	 * The instance of a class, or of a subclass of it, that is around a class whose own
	 * outer instance is given: the instance itself, or the one around that.
	 * @param wanted the class an instance is wanted of
	 * @param around the class the given instance is of, or {@code null}
	 * @param instance the instance of {@code around}, or {@code null} where there is none
	 * @return the form, or {@code null} when no such instance is around
	 */
	private static @Nullable LispVal outerInstance(JavaClass wanted, @Nullable JavaClass around,
			@Nullable LispVal instance) {
		LispVal path = instance;
		for (JavaClass type = around; type != null && path != null; type = type.outer) {
			if (type.isSubclassOf(wanted)) {
				return path;
			}
			path = type.hasOuterInstance() ? list(slotAccessor(type, outerSlot(type)), path) : null;
		}
		return null;
	}

	private static List<LispVal> prepend(LispVal first, List<? extends LispVal> rest) {
		List<LispVal> all = new ArrayList<>();
		all.add(first);
		all.addAll(rest);
		return all;
	}

	/**
	 * {@code this(..)} or {@code super(..)}: {@code nil} when the constructor is trivial.
	 * @param node what the flow knows the call by: the call written, or the declaration
	 * of the constructor or of the class whose {@code super()} is not written
	 * @param caret where a compiler says what the call throws
	 */
	private LispVal constructorCall(JavaClass target, List<Expression> arguments, int pos, Object node, int caret) {
		ClassType site = target == this.currentClass ? target.self() : this.currentClass.supertype(target);
		Resolved resolved = resolve(constructors(target), site, List.of(), arguments, null,
				"constructor " + target.simpleName(), target, pos);
		enumUniverse(resolved.method(), site);
		raises(node, caret, resolved.thrown(), null);
		checkAccess(resolved.method().isPrivate, resolved.method().declaration, resolved.method().owner,
				target.simpleName() + resolved.method().parameterList(), pos);
		called(resolved.method());
		if (isTrivial(resolved.method())) {
			return NIL;
		}
		return located(form(JavaNames.function(resolved.method()), prepend(THIS, resolved.forms())), pos);
	}

	private LispVal functionBody(Block body) {
		Frame frame = new Frame();
		this.frames.push(frame);
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		this.targets.clear();
		List<Local> savedPatterns = this.patternVariables;
		this.patternVariables = new ArrayList<>();
		this.scopes.push(new Scope(null));
		List<LispVal> forms = sequence(body.statements(), 0, true);
		this.scopes.pop();
		this.patternVariables = savedPatterns;
		this.targets.addAll(savedTargets);
		this.frames.pop();
		return frame.exits ? form(cl("block"), prepend(FUNCTION_BLOCK, forms)) : progn(forms);
	}

	/**
	 * The call that starts the program, as the launch protocol picks it (measured against
	 * a JDK 25's source launcher): the first class of the file when it has a
	 * {@code main}, else the class named as the file and no other; in it
	 * {@code main(String[])} before {@code main()}, declared or inherited; an instance
	 * {@code main} called on an instance of the class declaring it, made by its
	 * constructor of no argument.
	 */
	private LispVal entry() {
		JavaClass first = this.declared.get(0);
		JavaClass launched = launched();
		Method main = mainOf(launched);
		if (main == null) {
			throw JavaClasses.error(this.program, Objects.requireNonNull(first.declaration).pos(),
					"no main method found in " + first.simpleName()
							+ ": a program starts at the main of the first class of the file or of the class named as "
							+ "the file; with none it is a reactor, its entry points the public static methods of that "
							+ "class, which a module compiled -o out.wasm --no-wasi exports, or the exports of the "
							+ "@WitExport classes of its source tree, which a component exports");
		}
		// an inherited instance main runs on an instance of the class declaring it
		JavaClass started = main.owner;
		this.currentClass = started;
		this.unit = this.classes.unit(started);
		int at = Objects.requireNonNull(launched.declaration).pos();
		List<LispVal> arguments = new ArrayList<>();
		if (!main.isStatic) {
			if (started.isAbstract) {
				throw JavaClasses.error(this.program, at,
						started.simpleName() + " is abstract; cannot be instantiated");
			}
			Method constructor = constructors(started).stream()
				.filter(candidate -> candidate.parameters.isEmpty())
				.findFirst()
				.orElseThrow(() -> JavaClasses.error(this.program, at,
						"an instance main needs a constructor of no argument in " + started.simpleName()));
			arguments.add(list(JavaNames.creator(constructor)));
		}
		if (!main.parameters.isEmpty()) {
			arguments.add(readsItsArguments(main)
					? call("coerce", call("cdr", call("%host-argv")), list(cl("quote"), cl("simple-vector")))
					: call("vector"));
		}
		LispVal start = located(form(JavaNames.function(main), arguments),
				Objects.requireNonNull(main.declaration).pos());
		// the class the program starts at is initialized before anything of it runs
		for (JavaClass type = launched; type != null; type = type.superclass) {
			if (this.order.isLazy(type)) {
				start = call("progn", list(JavaNames.initializer(type)), start);
				break;
			}
		}
		return start;
	}

	/**
	 * Whether {@code main(String[])} names its array: only then is it the program's
	 * command line, the arguments after the program's name, so a program that does not
	 * read it reaches no host's command line and is the same module as before.
	 */
	private boolean readsItsArguments(Method main) {
		LispVal name = JavaNames.function(main);
		for (LispVal function : this.functions) {
			if (function instanceof LispCons defun && defun.cdr() instanceof LispCons named && named.car().equals(name)
					&& named.cdr() instanceof LispCons lambdaList && lambdaList.car() instanceof LispCons parameters) {
				LispVal array = parameters.car();
				for (LispVal rest = parameters.cdr(); rest instanceof LispCons more; rest = more.cdr()) {
					array = more.car();
				}
				for (LispVal rest = lambdaList.cdr(); rest instanceof LispCons form; rest = form.cdr()) {
					// a declaration of its type is no read
					boolean declaration = form.car() instanceof LispCons declared
							&& declared.car().equals(cl("declare"));
					if (!declaration && LispTrees.anyDatumNode(form.car(), array::equals)) {
						return true;
					}
				}
				return false;
			}
		}
		return true;
	}

	/**
	 * The static initialization before {@code main} and {@code main}, where what nothing
	 * caught is reported by the line a JDK writes, and the status 1: the pipeline's own
	 * report of an uncaught condition with the program's line in it, so no handler holds
	 * the condition. Only a program that throws objects asks the condition for one.
	 */
	private LispVal uncaught(List<LispVal> initialization, LispVal start) {
		List<LispVal> body = new ArrayList<>(initialization);
		body.add(start);
		int known = this.classes.reached.size();
		if (serves() && !this.suspended) {
			// the program lives while a server it started runs, as a JDK's does by the
			// server's own thread; on one stack the scheduler serves where no thread
			// can go on, the one main runs on among them
			body.add(library(JavaIntrinsics.SERVING, "serve", List.of()));
		}
		LispSymbol thrown = new LispSymbol("%o");
		LispVal entry;
		// a program that registered something for its end -- a shutdown hook, a file to
		// delete -- does it at each end there is no System.exit
		LispVal end = JavaIntrinsics.ends(this) ? library(JavaIntrinsics.SHUTDOWN, "end", List.of()) : null;
		if (startsThreads() || (this.suspended && serves())) {
			// the program ends when its threads that are no daemons have: the run time
			// reports what main left uncaught, then waits for them
			LispSymbol ended = new LispSymbol("%t");
			List<LispVal> operands = new ArrayList<>(List.of(list(cl("lambda"), NIL, progn(body)),
					list(cl("lambda"), list(ended), library(THREAD, "finish", List.of(ended))),
					list(cl("lambda"), list(thrown), library(THROWABLE, "trace", List.of(thrown)))));
			if (end != null) {
				operands.add(list(cl("lambda"), NIL, end));
			}
			entry = form(JavaForms.runtime(end == null ? "thread-main" : "thread-ending"), operands);
		}
		else {
			List<LispVal> reporting = new ArrayList<>();
			reporting.add(uncaughtLine());
			reporting
				.add(end == null ? call("%host-exit", integer(1)) : call("progn", end, call("%host-exit", integer(1))));
			reporting.addAll(body);
			if (end != null) {
				reporting.add(end);
			}
			entry = form(cl("%reporting-uncaught"), reporting);
		}
		if (this.classes.reached.size() != known) {
			// what the report reaches of the library is lowered by one more pass
			this.classes.changed();
		}
		return entry;
	}

	/**
	 * The function writing the line a JDK writes of what nothing caught: the
	 * {@code %reporting-uncaught} report's line.
	 */
	private LispVal uncaughtLine() {
		if (!this.raises || !enclosing()) {
			return function(JavaForms.runtime(this.raises ? "uncaught-thrown-line" : "uncaught-line"));
		}
		// an exception that can have a cause or suppress another is reported with them
		LispSymbol condition = new LispSymbol("%c");
		LispSymbol text = new LispSymbol("%text");
		LispSymbol thrown = new LispSymbol("%o");
		this.reporter = list(cl("defun"), UNCAUGHT, list(condition, text),
				runtimeCall("uncaught-thrown-line", condition, JavaForms.let(thrown, runtimeCall("thrown", condition),
						List.of(call("if", thrown, library(THROWABLE, "trace", List.of(thrown)), text)))));
		return function(UNCAUGHT);
	}

	/**
	 * The static initialization of a program whose entry points are the exports of its
	 * {@code @WitExport} classes, run by the first call, where what an export or the
	 * initialization leaves uncaught is reported by the line a JDK writes before the call
	 * traps: nothing follows it, since a host call ends as a trap and no program ends.
	 */
	private LispVal reportingExports(List<LispVal> initialization) {
		int known = this.classes.reached.size();
		List<LispVal> reporting = new ArrayList<>();
		reporting.add(uncaughtLine());
		reporting.add(NIL);
		reporting.addAll(initialization);
		if (this.classes.reached.size() != known) {
			// what the report reaches of the library is lowered by one more pass
			this.classes.changed();
		}
		return form(cl("%reporting-uncaught"), reporting);
	}

	private static final String THROWABLE = "java.lang.Throwable";

	private static final LispSymbol UNCAUGHT = new LispSymbol("%uncaught");

	/** The function running the static initializers of the classes initialized first. */
	private static final LispSymbol INITIALIZATION = new LispSymbol("%initialization");

	/** The function a program whose exceptions can enclose others is reported by. */
	private @Nullable LispVal reporter;

	/**
	 * Whether an exception of the program can have a cause or a suppressed exception:
	 * some constructor taking a cause, {@code initCause} or {@code addSuppressed} is
	 * reached, or an exception class of the program or a member of one of the library
	 * answers a cause of its own ({@code getCause} overridden: a
	 * {@code NamingException}'s root cause).
	 */
	private boolean enclosing() {
		for (JavaClass declaration : this.classes.all()) {
			if (lowered(declaration) && answersCause(declaration)) {
				return true;
			}
		}
		Set<JavaClass> asked = new HashSet<>();
		for (Method method : this.classes.reached) {
			if (!method.owner.name.equals(THROWABLE)) {
				if (asked.add(method.owner) && answersCause(method.owner)) {
					return true;
				}
				continue;
			}
			if (method.isConstructor()
					? method.parameters.size() == 2
							|| (method.parameters.size() == 1 && !method.parameters.get(0).equals(this.stringType))
					: method.name.equals("initCause") || method.name.equals("addSuppressed")) {
				return true;
			}
		}
		return false;
	}

	/** Whether a class under {@code Throwable} overrides {@code getCause}. */
	private static boolean answersCause(JavaClass owner) {
		boolean overrides = false;
		for (JavaClass type = owner; type != null; type = type.superclass) {
			if (type.name.equals(THROWABLE)) {
				return overrides;
			}
			for (Method declared : type.methods) {
				overrides |= !declared.isStatic && declared.name.equals("getCause") && declared.parameters.isEmpty();
			}
		}
		return false;
	}

	private static final String THREAD = "java.lang.Thread";

	private static final String TEMPORAL_ACCESSOR = "java.time.temporal.TemporalAccessor";

	private static final String TEMPORAL_QUERIES = "java.time.temporal.TemporalQueries";

	/**
	 * Whether the program can start a thread: some call reaches {@code Thread.start()}.
	 */
	private boolean startsThreads() {
		for (Method method : this.loweredMethods) {
			if (method.name.equals("start") && method.owner.name.equals(THREAD)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the program has threads beside the one it starts on: the ones it starts,
	 * and the ones the handlers of a server run on -- the transport's, or the executor's.
	 */
	private boolean scheduled() {
		return startsThreads() || serves();
	}

	/**
	 * Whether the program can start a server: some call reaches a member of the library's
	 * one server.
	 */
	private boolean serves() {
		for (Method method : this.classes.reached) {
			if (isServer(method.owner)) {
				return true;
			}
		}
		return false;
	}

	/** Whether a class is the library's server, or a class of its own. */
	private static boolean isServer(JavaClass owner) {
		return owner.name.equals(JavaIntrinsics.SERVING) || owner.name.startsWith(JavaIntrinsics.SERVING + ".");
	}

	/**
	 * The class the program starts at: the first class of the file when it has a
	 * {@code main}, else the class named as the file.
	 */
	private JavaClass launched() {
		if (this.suite != null) {
			return this.suite;
		}
		JavaClass first = this.declared.get(0);
		if (mainOf(first) != null || this.program.file() == null) {
			return first;
		}
		String file = this.program.file().replace('\\', '/');
		String name = file.substring(file.lastIndexOf('/') + 1).replaceFirst("\\.[^.]*$", "");
		JavaClass launched = first;
		for (JavaClass declaration : this.declared) {
			if (declaration.simpleName().equals(name)) {
				launched = declaration;
			}
		}
		return launched;
	}

	/**
	 * The class the program starts at: initialized before anything else of it runs;
	 * {@code null} where nothing starts it, its entry points being exports.
	 */
	private @Nullable JavaClass launched;

	/**
	 * The generated class a run of the file's tests starts at, or {@code null} for a
	 * program.
	 */
	private @Nullable JavaClass suite;

	/**
	 * Where a program with no {@code main} starts: at the {@code @WitExport} classes of
	 * its source tree, each declared a class of the program, their worlds' exports its
	 * entry points ({@link JavaWitExports}) -- which a component exports, so any other
	 * target refuses the program. With none, a module with no WASI exports the public
	 * static methods of the class the program starts at ({@link JavaReactorExports}), and
	 * any other target refuses it. A program with a {@code main} is a command, its
	 * {@code @WitExport} classes ordinary ones; so is one Common Lisp loads, whose
	 * functions the loading program calls, and a session's buffer.
	 */
	private void exportEntries() {
		JavaClass started = Objects.requireNonNull(this.launched);
		if (this.session != null || this.loaded || mainOf(started) != null) {
			return;
		}
		List<JavaClass> marked = this.classes.treeCarrying(JavaWitExports.WIT_EXPORT);
		List<JavaClass> entries = marked.isEmpty() ? this.classes.exports.annotated() : marked;
		if (entries.isEmpty()) {
			if (this.classes.wit.boundary.reactorModule()) {
				// a module with no WASI exports the class's public static methods
				this.classes.reactor.entry = started;
				startsNowhere();
			}
			// elsewhere entry() says there is no main
			return;
		}
		JavaBoundary boundary = this.classes.wit.boundary;
		if (!boundary.component()) {
			List<String> names = entries.stream().map(JavaClass::simpleName).toList();
			throw JavaClasses.error(this.program, Objects.requireNonNull(started.declaration).pos(),
					"no main method found in " + started.simpleName() + ": its entry points are the exports of the "
							+ "@WitExport class" + (names.size() == 1 ? " " : "es ") + String.join(", ", names)
							+ ", which a component exports, where a host calls them: compile it with "
							+ (boundary.hostImports() ? "--component, not to a module" : "-o out.wasm --component"));
		}
		this.classes.exports.entries = true;
		startsNowhere();
	}

	/**
	 * A program whose entry points are exports a host calls has no start that initializes
	 * the class it would start at, so that class is initialized at its first use, as any
	 * other: a lazy one at the top of its static methods.
	 */
	private void startsNowhere() {
		this.launched = null;
	}

	private @Nullable Method mainOf(JavaClass declaration) {
		ArrayType strings = new ArrayType(this.stringType);
		Method found = null;
		for (JavaClass type = declaration; type != null && found == null; type = type.superclass) {
			for (Method method : type.methods) {
				if (method.name.equals("main") && method.returnType == Primitive.VOID && !method.isPrivate
						&& (method.parameters.isEmpty() || method.parameters.equals(List.of(strings)))
						&& (found == null || !method.parameters.isEmpty())) {
					found = method;
				}
			}
		}
		return found;
	}

	// ---- the functions made for the whole program

	private List<LispVal> generatedFunctions() {
		List<LispVal> forms = new ArrayList<>();
		List<JavaClass> deepestFirst = this.classes.all()
			.stream()
			.filter(declaration -> !declaration.intrinsic && !declaration.isInterface && isLive(declaration))
			.sorted(Comparator.comparingInt(JavaClass::depth).reversed())
			.toList();
		// ahead of the tests it may ask for: a reference field's type
		for (LispSymbol name : List.of(FIELD_GET, FIELD_SET, FIELD_SWAP)) {
			if (this.generated.contains(name)) {
				forms.add(fieldFunction(name));
			}
		}
		// ahead of the tests a class object asks for
		classObjects(forms, deepestFirst);
		for (JavaClass tested : this.interfaceTests) {
			forms.add(interfaceTest(tested));
		}
		if (this.generated.contains(OBJECT_STRING)) {
			forms.add(objectFunction(OBJECT_STRING, "toString()", List.of(), deepestFirst,
					runtimeCall("to-string", RECEIVER)));
		}
		if (this.generated.contains(OBJECT_EQUALS)) {
			LispSymbol other = new LispSymbol("%a0");
			forms.add(objectFunction(OBJECT_EQUALS, "equals(Object)", List.of(other), deepestFirst,
					runtimeCall("equals", RECEIVER, other)));
		}
		if (this.generated.contains(OBJECT_HASH)) {
			forms.add(objectFunction(OBJECT_HASH, "hashCode()", List.of(), deepestFirst,
					runtimeCall("hash-code", RECEIVER)));
		}
		for (LispSymbol name : List.of(CLASS_NAME, SIMPLE_NAME)) {
			if (!this.generated.contains(name)) {
				continue;
			}
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			for (JavaClass declaration : deepestFirst) {
				if (!declaration.isAbstract && isMade(declaration)) {
					arms.add(list(list(JavaNames.predicate(declaration), RECEIVER),
							string(name == CLASS_NAME ? declaration.binaryName : declaration.simpleName())));
				}
			}
			arms.add(list(T,
					this.namesOfAny.contains(name) ? runtimeCall("class-name", RECEIVER, name == CLASS_NAME ? T : NIL)
							: runtimeCall("no-receiver", RECEIVER, string("getClass()"))));
			forms.add(list(cl("defun"), name, list(RECEIVER), list(arms)));
		}
		if (this.generated.contains(CAST_REFUSED)) {
			forms.add(Objects.requireNonNull(this.castRefusal));
		}
		if (this.generated.contains(LISP_ERROR)) {
			// the exception the table names, made as the program's object; nil for one
			// no catch takes
			LispSymbol condition = new LispSymbol("%c");
			LispSymbol entry = new LispSymbol("%e");
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			for (String name : this.caughtRaised) {
				arms.add(list(call("equal", call("car", entry), string(name)), raisedObject(name, call("cdr", entry))));
			}
			forms.add(list(cl("defun"), LISP_ERROR, list(condition),
					JavaForms.let(entry, runtimeCall("exception-of", condition), List.of(list(arms)))));
		}
		return forms;
	}

	/**
	 * A library exception of the run time's message: its static {@code raised(String)},
	 * where its constructors take no message, else its constructor of a message.
	 */
	private LispVal raisedObject(String exception, LispVal message) {
		JavaClass owner = this.classes.library(exception);
		for (Method method : owner.methods) {
			if (method.isStatic && method.name.equals("raised") && method.parameters.equals(List.of(this.stringType))) {
				called(method);
				uses(owner);
				return form(JavaNames.function(method), List.of(message));
			}
		}
		return form(creator(exception), creatorArguments(exception, message));
	}

	/**
	 * The function making a library exception of a message: its constructor of a message,
	 * else of a message and a cause.
	 */
	private LispSymbol creator(String exception) {
		Method constructor = messageConstructor(exception);
		reach(constructor);
		makes(constructor.owner);
		return JavaNames.creator(constructor);
	}

	private List<LispVal> creatorArguments(String exception, LispVal message) {
		return messageConstructor(exception).parameters.size() == 1 ? List.of(message) : List.of(message, NIL);
	}

	private Method messageConstructor(String exception) {
		Method withCause = null;
		for (Method constructor : constructors(this.classes.library(exception))) {
			if (constructor.parameters.equals(List.of(this.stringType))) {
				return constructor;
			}
			if (constructor.parameters.size() == 2 && constructor.parameters.get(0).equals(this.stringType)) {
				withCause = constructor;
			}
		}
		if (withCause == null) {
			throw new IllegalStateException(exception + " has no constructor of a message");
		}
		return withCause;
	}

	/**
	 * One of {@code Object}'s methods over any value: the classes of the program
	 * declaring it, deepest first, then the run-time library's answer for every other
	 * value.
	 */
	private LispVal objectFunction(LispSymbol name, String signature, List<LispSymbol> parameters,
			List<JavaClass> deepestFirst, LispVal otherwise) {
		List<LispVal> all = new ArrayList<>();
		all.add(RECEIVER);
		all.addAll(parameters);
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("cond"));
		for (JavaClass declaration : deepestFirst) {
			for (Method method : declaration.methods) {
				if (!method.isStatic && !method.isAbstract && method.signature().equals(signature)) {
					reach(method);
					arms.add(list(list(JavaNames.predicate(declaration), RECEIVER),
							form(JavaNames.function(method), all)));
				}
			}
		}
		arms.add(list(T, otherwise));
		return list(cl("defun"), name, list(all), list(arms));
	}

	/**
	 * {@code instanceof} of an interface: the classes implementing it, and the lambdas
	 * made for it; of a class whose values are Lisp values and that has classes of Java
	 * source under it, its own predicate and theirs. A lambda is a function, which does
	 * not tell the interface it was made for: so when the program has lambdas of this
	 * interface and of another, the ones of this interface are made structures holding
	 * the function, and the program is lowered again.
	 */
	private LispVal interfaceTest(JavaClass declaration) {
		List<LispVal> tests = new ArrayList<>();
		tests.add(cl("or"));
		List<JavaClass> plain = new ArrayList<>();
		boolean others = false;
		for (JavaClass target : this.classes.lambdaTargets) {
			if (this.classes.taggedLambdas.contains(target)) {
				if (target.isSubclassOf(declaration)) {
					tests.add(list(lambdaTest(target), RECEIVER));
				}
			}
			else if (target.isSubclassOf(declaration)) {
				plain.add(target);
			}
			else {
				others = true;
			}
		}
		if (!plain.isEmpty() && others) {
			tag(plain);
		}
		else if (!plain.isEmpty()) {
			tests.add(call("functionp", RECEIVER));
		}
		if (holdsArrays(declaration)) {
			// every array is Cloneable and Serializable
			tests.add(arrayValue(RECEIVER));
		}
		for (JavaClass candidate : this.classes.all()) {
			if (candidate.isInterface || !candidate.isSubclassOf(declaration)) {
				continue;
			}
			if (candidate.intrinsic) {
				// a class's own predicate answers for the Lisp values under it, where
				// the program can hold one
				LispSymbol predicate = candidate.held && (declaration.isInterface || candidate == declaration)
						? JavaIntrinsics.predicate(candidate) : null;
				if (predicate != null) {
					tests.add(list(predicate, RECEIVER));
				}
			}
			else if (isLive(candidate)
					&& (candidate.superclass == null || !candidate.superclass.isSubclassOf(declaration))) {
				tests.add(list(JavaNames.predicate(candidate), RECEIVER));
			}
		}
		return list(cl("defun"), JavaNames.interfaceTest(declaration), list(RECEIVER), list(tests));
	}

	/** Makes the lambdas of some interfaces structures: the program is lowered again. */
	private void tag(List<JavaClass> targets) {
		if (this.classes.taggedLambdas.addAll(targets)) {
			this.classes.changed();
		}
	}

	// ---- scopes

	/** The local class of a simple name in scope, or {@code null}. */
	private @Nullable JavaClass localClass(String name) {
		for (Scope scope : this.scopes) {
			JavaClass found = scope.classes.get(name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private Local bind(String name, JavaType type, int pos, Object key) {
		if (name.equals("_")) {
			// the unnamed variable: a value kept nowhere a name reaches
			return new Local(temporary(), type, key);
		}
		for (Scope scope : this.scopes) {
			if (scope.locals.containsKey(name)) {
				throw error(pos, "variable " + name + " is already defined");
			}
			if (scope.boundary != null) {
				break;
			}
		}
		Local bound = new Local(JavaNames.symbol(name), type, key);
		Objects.requireNonNull(this.scopes.peek()).locals.put(name, bound);
		return bound;
	}

	/** The slot of a local class holding a local it reads, made at the first read. */
	private JavaClass.Capture capture(JavaClass reader, String name, Local local) {
		JavaClass.Capture capture = reader.captures.get(name);
		if (capture == null) {
			capture = new JavaClass.Capture(name, local.type(), "val$" + name + "$" + reader.depth());
			reader.captures.put(name, capture);
			this.classes.changed();
		}
		return capture;
	}

	/**
	 * What a simple name means: a local, innermost first; a field of the class the
	 * lowering stands in, its own or inherited; then, for a class declared in a method,
	 * the locals around its declaration, each a slot of the class; then the fields of the
	 * classes around it, reached through the instance each inner class holds.
	 * @param probe whether the name is only looked for: nothing is recorded or refused
	 */
	private @Nullable Named variable(String name, int pos, boolean probe) {
		JavaClass type = this.currentClass;
		LispVal path = this.staticContext ? null : THIS;
		JavaClass reader = null;
		JavaClass crossed = null;
		Field hidden = null;
		for (Scope scope : this.scopes) {
			Local local = scope.locals.get(name);
			if (local != null) {
				if (reader == null || probe) {
					return new LocalRef(local);
				}
				// a local of a method around a local class: what the class reads is
				// assigned where it is declared and effectively final (JavaFlow)
				this.facts.captured(Objects.requireNonNull(Objects.requireNonNull(crossed).declaration), local.key,
						pos);
				if (this.staticContext) {
					throw error(pos, "non-static variable " + name + " cannot be referenced from a static context");
				}
				JavaClass.Capture capture = capture(reader, name, local);
				return new CaptureRef(new Typed(list(slotAccessor(reader, capture.slot()), THIS), local.type()));
			}
			if (scope.boundary != null) {
				crossed = scope.boundary;
			}
			if (scope.boundary != null && type != null) {
				Field field = field(type, name);
				if (field != null && inherits(type, field.owner, field.isPrivate, field.declaration)) {
					return new FieldRef(field, field.isStatic ? null : path, type);
				}
				hidden = hidden != null ? hidden : field;
				if (reader == null) {
					reader = type;
				}
				path = type.hasOuterInstance() && path != null ? list(slotAccessor(type, outerSlot(type)), path) : null;
				type = type.outer;
			}
		}
		while (type != null) {
			Field field = field(type, name);
			if (field != null && inherits(type, field.owner, field.isPrivate, field.declaration)) {
				return new FieldRef(field, field.isStatic ? null : path, type);
			}
			hidden = hidden != null ? hidden : field;
			path = type.hasOuterInstance() && path != null ? list(slotAccessor(type, outerSlot(type)), path) : null;
			type = type.outer;
		}
		List<JavaClass> owners = imported(name, owner -> field(owner, name) instanceof Field field && field.isStatic);
		if (!owners.isEmpty()) {
			return new FieldRef(Objects.requireNonNull(field(owners.get(0), name)), null, owners.get(0));
		}
		if (hidden != null && !probe) {
			// the one field of the name is of a class above, which did not hand it down
			throw error(pos, hidden.isPrivate ? name + " has private access in " + hidden.owner.simpleName() : name
					+ " is not public in " + hidden.owner.simpleName() + "; cannot be accessed from outside package");
		}
		return null;
	}

	/**
	 * Whether a class has a member a class above it declares: one that is not
	 * {@code private} and, from another package, {@code public} or {@code protected}.
	 * What it does not have, a simple name does not find in it: the name is then what the
	 * classes and the method around it say. A member the front end writes itself is
	 * {@code public}.
	 * @param declaration the member as written, or {@code null}
	 */
	private boolean inherits(JavaClass viewer, JavaClass owner, boolean isPrivate, @Nullable Object declaration) {
		if (owner == viewer) {
			return true;
		}
		if (isPrivate) {
			return false;
		}
		Set<String> modifiers = switch (declaration) {
			case JavaSyntax.Method method -> method.modifiers();
			case JavaSyntax.Field field -> field.modifiers();
			case null, default -> Set.of("public");
		};
		return owner.intrinsic || owner.isInterface || modifiers.contains("public") || modifiers.contains("protected")
				|| Objects.equals(this.classes.packageOf(owner), this.classes.packageOf(viewer));
	}

	/**
	 * The methods of a name a class has by its simple name: its own and the inherited
	 * ones.
	 */
	private List<Method> inherited(JavaClass declaration, String name) {
		return methods(declaration, name).stream()
			.filter(method -> inherits(declaration, method.owner, method.isPrivate, method.declaration))
			.toList();
	}

	/**
	 * The classes a static import of the file brings a member of a name from, as the
	 * member wanted: the single-static imports of the name, else the static imports on
	 * demand.
	 */
	private List<JavaClass> imported(String name, java.util.function.Predicate<JavaClass> has) {
		List<JavaClass> found = this.unit.statics().getOrDefault(name, List.of()).stream().filter(has).toList();
		return !found.isEmpty() ? found : this.unit.staticOnDemand().stream().filter(has).toList();
	}

	/** The instance of a class around the one the lowering stands in, or {@code null}. */
	private @Nullable LispVal enclosing(JavaClass wanted) {
		if (this.staticContext) {
			return null;
		}
		JavaClass current = this.currentClass;
		if (this.early) {
			// ahead of this(..) or super(..) the instance is out of reach, but the one
			// around it was set where it was made
			return current.isSubclassOf(wanted) || !current.hasOuterInstance() ? null
					: outerInstance(wanted, current.outer, list(slotAccessor(current, outerSlot(current)), THIS));
		}
		return outerInstance(wanted, current, THIS);
	}

	/**
	 * The instance {@code Name.this} names, or {@code null}: of the class the lowering
	 * stands in or of one its declaration stands in, and of no class under the one named
	 * -- an anonymous class of an interface, made in a default method of that interface,
	 * names the instance around it.
	 */
	private @Nullable LispVal lexicallyAround(JavaClass wanted) {
		if (this.staticContext || (this.early && this.currentClass == wanted)) {
			return null;
		}
		LispVal path = THIS;
		for (JavaClass type = this.currentClass; type != null && path != null; type = type.outer) {
			if (type == wanted) {
				return path;
			}
			path = type.hasOuterInstance() ? list(slotAccessor(type, outerSlot(type)), path) : null;
		}
		return null;
	}

	// ---- the class table

	/**
	 * The field of a name a class has: its own, one of a superclass, a constant of an
	 * interface.
	 */
	private static @Nullable Field field(JavaClass declaration, String name) {
		for (JavaClass type = declaration.present(); type != null; type = type.superclass) {
			for (Field field : type.fields) {
				if (field.name.equals(name)) {
					return field;
				}
			}
			for (JavaClass implemented : type.interfaces) {
				Field constant = field(implemented, name);
				if (constant != null) {
					return constant;
				}
			}
		}
		return null;
	}

	private List<Method> constructors(JavaClass declaration) {
		bring(declaration.present(), "<init>");
		return declaration.methods.stream().filter(Method::isConstructor).toList();
	}

	/**
	 * Reads the classes of the library's Java source whose types the members of a name
	 * have in an intrinsic class: such a member joins the class when the class it names
	 * is in the table, and a call naming that class only among its arguments would look
	 * for the member before.
	 */
	private void bring(JavaClass declaration, String name) {
		if (declaration.intrinsic) {
			for (String named : JavaIntrinsics.named(declaration.name, name)) {
				this.classes.library(named);
			}
		}
	}

	/**
	 * A method's name and parameter types as a class under its owner has them: the
	 * owner's type parameters replaced by what the class gives them, then erased. What an
	 * override shares with the method it overrides, whichever of the two is written with
	 * a type parameter: {@code compareTo(T)} of {@code Comparable<T>} is
	 * {@code compareTo(A)} in a class implementing {@code Comparable<A>}.
	 */
	private String signatureIn(JavaClass viewer, Method method) {
		if (method.owner == viewer || method.owner.typeParameters.isEmpty()) {
			return method.signature();
		}
		ClassType above = this.types.asSuper(viewer.self(), method.owner);
		if (above == null || above.arguments().isEmpty()) {
			return method.signature();
		}
		Map<TypeParameter, JavaType> bindings = this.types.bindings(above);
		StringBuilder signature = new StringBuilder(method.name).append('(');
		for (int i = 0; i < method.parameters.size(); i++) {
			signature.append(i > 0 ? "," : "")
				.append(JavaTypes.substitute(method.parameters.get(i), bindings).erasure());
		}
		return signature.append(')').toString();
	}

	/**
	 * The methods of a name a class has, an overridden one left out. An interface has
	 * {@code Object}'s public methods only.
	 */
	private List<Method> methods(JavaClass declaration, String name) {
		if (declaration.isEnum && (name.equals("values") || name.equals("valueOf"))) {
			this.classes.enumMethod(declaration, name);
		}
		bring(declaration, name);
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		above.add(this.objectType.declaration());
		List<Method> found = new ArrayList<>();
		Set<String> signatures = new HashSet<>();
		for (JavaClass type : above) {
			for (Method method : type.methods) {
				// a static method of an interface is a member of that interface alone
				if (method.name.equals(name) && !method.isConstructor()
						&& !(method.isStatic && type.isInterface && type != declaration)
						&& !(method.isProtected && declaration.isInterface)
						&& signatures.add(signatureIn(declaration, method))) {
					found.add(method);
				}
			}
		}
		return found;
	}

	/**
	 * The body a class runs for a method declared above it: its own or the nearest one
	 * above it, else an interface's default, the one of the most specific interface.
	 */
	private @Nullable Method implementation(JavaClass declaration, Method method) {
		String signature = signatureIn(declaration, method);
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			for (Method candidate : type.methods) {
				if (!candidate.isAbstract && !candidate.isStatic && candidate.name.equals(method.name)
						&& signatureIn(declaration, candidate).equals(signature)) {
					return candidate;
				}
			}
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		Method found = null;
		for (JavaClass type : above) {
			if (!type.isInterface) {
				continue;
			}
			for (Method candidate : type.methods) {
				if (candidate.isDefault() && candidate.name.equals(method.name)
						&& signatureIn(declaration, candidate).equals(signature)
						&& (found == null || type.isSubclassOf(found.owner))) {
					found = candidate;
				}
			}
		}
		return found;
	}

	/** What a compiler checks of {@code @FunctionalInterface}: the type is one. */
	private void checkFunctional(JavaClass declaration) {
		JavaSyntax.ClassDeclaration written = Objects.requireNonNull(declaration.declaration);
		if (written.modifiers().contains("@FunctionalInterface") && functionalMethod(declaration) == null) {
			throw error(written.pos(), "Unexpected @FunctionalInterface annotation: " + declaration.simpleName()
					+ " is not a functional interface");
		}
	}

	/**
	 * Whether an instance method overrides or implements one of a supertype -- every type
	 * has {@code Object}'s --, or is a record's accessor: what {@code @Override} asks.
	 */
	private boolean overrides(Method method) {
		JavaClass owner = method.owner;
		if (method.isStatic) {
			return false;
		}
		if (owner.isRecord && method.parameters.isEmpty()
				&& Objects.requireNonNull(owner.declaration)
					.components()
					.stream()
					.anyMatch(component -> component.name().equals(method.name))) {
			return true;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(owner, above);
		above.remove(owner);
		above.add(this.objectType.declaration());
		String signature = signatureIn(owner, method);
		for (JavaClass type : above) {
			for (Method candidate : type.methods) {
				if (!candidate.isStatic && !candidate.isPrivate && !candidate.isConstructor()
						&& candidate.name.equals(method.name) && signatureIn(owner, candidate).equals(signature)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A method overrides no {@code final} method (JLS 8.4.3.3), gives no weaker access
	 * than what it overrides or implements has, and throws no checked exception that it
	 * does not (JLS 8.4.8.3).
	 */
	private void overridden(Method method, int pos) {
		JavaClass owner = method.owner;
		if (method.isStatic || method.isConstructor()) {
			return;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(owner, above);
		above.remove(owner);
		above.add(this.objectType.declaration());
		String signature = signatureIn(owner, method);
		boolean checked = method.thrown.stream().anyMatch(JavaTypes::isChecked);
		for (JavaClass type : above) {
			for (Method candidate : type.methods) {
				if (candidate.isStatic || candidate.isPrivate || candidate.isConstructor()
						|| !candidate.name.equals(method.name) || !signatureIn(owner, candidate).equals(signature)
						|| (access(candidate) == 1 && !Objects.equals(this.classes.packageOf(candidate.owner),
								this.classes.packageOf(owner)))) {
					continue;
				}
				String overrides = shown(method)
						+ " in " + named(owner) + (candidate.owner.isInterface && !owner.isInterface
								? " cannot implement " : " cannot override ")
						+ shown(candidate) + " in " + named(candidate.owner) + "; ";
				if (!method.isPrivate && (candidate.isFinal
						|| (candidate.declaration != null && candidate.declaration.modifiers().contains("final")))) {
					throw error(pos, overrides + "overridden method is final");
				}
				if (access(method) < access(candidate)) {
					throw error(pos, overrides + "attempting to assign weaker access privileges; was "
							+ List.of("private", "package", "protected", "public").get(access(candidate)));
				}
				if (checked && !method.isPrivate) {
					throwsWithin(method, candidate, owner, pos);
				}
			}
		}
	}

	/**
	 * A method's access, from {@code private} (0) through no modifier and
	 * {@code protected} to {@code public} (3): an interface's is public, an intrinsic
	 * member's public but where it is protected.
	 */
	private static int access(Method method) {
		if (method.declaration == null) {
			return method.isProtected ? 2 : 3;
		}
		Set<String> modifiers = method.declaration.modifiers();
		return modifiers.contains("public") || (method.owner.isInterface && !modifiers.contains("private")) ? 3
				: modifiers.contains("protected") ? 2 : modifiers.contains("private") ? 0 : 1;
	}

	/**
	 * A method a class inherits from its superclass throws no checked exception that a
	 * method of an interface it thereby implements does not: said of the class.
	 */
	private void inheritedThrows(JavaClass declaration) {
		if (declaration.isInterface || declaration.superclass == null) {
			return;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		for (JavaClass type : above) {
			if (!type.isInterface || declaration.superclass.isSubclassOf(type)) {
				continue;
			}
			for (Method method : type.methods) {
				Method body = method.isStatic || method.isPrivate ? null : implementation(declaration, method);
				if (body != null && body.owner != declaration && !body.owner.isInterface) {
					throwsWithin(body, method, declaration, keyword(declaration));
				}
			}
		}
	}

	/** Refuses a method throwing a checked exception the one it overrides does not. */
	private void throwsWithin(Method method, Method overridden, JavaClass viewer, int pos) {
		List<JavaType> allowed = new ArrayList<>();
		ClassType site = this.types.asSuper(viewer.self(), overridden.owner);
		for (JavaType type : overridden.thrown) {
			allowed.add(this.types.memberType(type, site, overridden.owner));
		}
		for (String name : overridden.raises) {
			allowed.add(this.classes.libraryType(name));
		}
		ClassType own = this.types.asSuper(viewer.self(), method.owner);
		for (JavaType declared : method.thrown) {
			JavaType type = this.types.memberType(declared, own, method.owner);
			if (JavaTypes.isChecked(type) && allowed.stream().noneMatch(taken -> isSubtype(type, taken))) {
				throw error(pos,
						shown(method) + " in " + named(method.owner)
								+ (overridden.owner.isInterface && !method.owner.isInterface ? " cannot implement "
										: " cannot override ")
								+ shown(overridden) + " in " + named(overridden.owner)
								+ "; overridden method does not throw " + type);
			}
		}
	}

	/**
	 * A class as a compiler names it: an inner member class behind the classes around it,
	 * a static one alone.
	 */
	private static String named(JavaClass declaration) {
		JavaClass around = declaration.outer;
		return around != null && around.members.get(declaration.simple) == declaration && declaration.hasOuterInstance()
				? named(around) + "." + declaration.simple : declaration.simpleName();
	}

	/**
	 * Where a class's declaration starts, which is where a compiler says what is wrong
	 * with the class as a whole: the keyword ahead of its name.
	 */
	private int keyword(JavaClass declaration) {
		int pos = Objects.requireNonNull(declaration.declaration).pos();
		String text = this.classes.unit(declaration).source().text();
		int end = Math.min(pos, text.length());
		while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
			end--;
		}
		int start = end;
		while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
			start--;
		}
		return start < end ? start : pos;
	}

	/** A method as a compiler names it: its name and its parameters' types. */
	private static String shown(Method method) {
		return method.name + method.parameters.subList(method.hidden, method.parameters.size())
			.stream()
			.map(JavaType::toString)
			.collect(java.util.stream.Collectors.joining(",", "(", ")"));
	}

	/**
	 * What a lambda made for a functional interface is: the interface's one abstract
	 * method, with the types the interface's arguments give its parameters and its value.
	 */
	private record Descriptor(ClassType target, Method method, List<JavaType> parameters, JavaType returnType,
			List<JavaType> thrown) {
	}

	/** The one abstract method of a functional interface, or {@code null}. */
	private @Nullable Method functionalMethod(JavaClass declaration) {
		if (!declaration.isInterface || declaration.intrinsic) {
			return null;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		Map<String, Method> found = new LinkedHashMap<>();
		for (JavaClass type : above) {
			for (Method method : type.methods) {
				if (method.isAbstract && implementation(declaration, method) == null) {
					found.putIfAbsent(signatureIn(declaration, method), method);
				}
			}
		}
		return found.size() == 1 ? found.values().iterator().next() : null;
	}

	/**
	 * The function type of a functional interface type, or {@code null} for any other
	 * type. A wildcard among its arguments is read as its bound.
	 */
	private @Nullable Descriptor functional(@Nullable JavaType type) {
		if (!(type instanceof ClassType target)) {
			return null;
		}
		JavaClass declaration = target.declaration();
		Method method = functionalMethod(declaration);
		if (method == null) {
			return null;
		}
		if (!target.arguments().isEmpty()) {
			List<JavaType> arguments = new ArrayList<>();
			for (int i = 0; i < target.arguments().size(); i++) {
				JavaType argument = target.arguments().get(i);
				if (argument instanceof Wildcard wildcard) {
					List<JavaType> bounds = declaration.typeParameters.get(i).bounds;
					argument = wildcard.upper() != null ? wildcard.upper() : wildcard.lower() != null ? wildcard.lower()
							: bounds.isEmpty() ? this.objectType : this.types.erased(bounds.get(0));
				}
				arguments.add(argument);
			}
			target = new ClassType(declaration, List.copyOf(arguments));
		}
		List<JavaType> parameters = new ArrayList<>();
		for (JavaType parameter : method.parameters) {
			parameters.add(this.types.memberType(parameter, target, method.owner));
		}
		List<JavaType> thrown = new ArrayList<>();
		for (JavaType declared : method.thrown) {
			thrown.add(this.types.memberType(declared, target, method.owner));
		}
		return new Descriptor(target, method, List.copyOf(parameters),
				this.types.memberType(method.returnType, target, method.owner), List.copyOf(thrown));
	}

	// ---- conversions

	private boolean isSubtype(JavaType from, JavaType to) {
		return this.types.isSubtype(from, to);
	}

	/** Whether a widening primitive conversion takes one type to another. */
	private static boolean widens(JavaType from, JavaType to) {
		if (!(from instanceof Primitive source) || !(to instanceof Primitive target) || from == to) {
			return false;
		}
		return switch (source) {
			case BYTE ->
				target == Primitive.SHORT || target == Primitive.INT || target == Primitive.LONG || target.isFloating();
			case SHORT, CHAR -> target == Primitive.INT || target == Primitive.LONG || target.isFloating();
			case INT -> target == Primitive.LONG || target.isFloating();
			case LONG -> target.isFloating();
			case FLOAT -> target == Primitive.DOUBLE;
			default -> false;
		};
	}

	/** A primitive value widened to another primitive type. */
	private static Typed widen(Typed value, JavaType to) {
		if (value.type() == to) {
			return value;
		}
		Object constant = value.constant();
		JavaType from = value.type();
		if (to == Primitive.DOUBLE) {
			if (constant != null) {
				return constant(((Number) number(constant)).doubleValue());
			}
			return from == Primitive.FLOAT ? new Typed(value.form(), to)
					: new Typed(call("float", value.form(), new LispDouble(1.0)), to);
		}
		if (to == Primitive.FLOAT) {
			if (constant != null) {
				return constant(((Number) number(constant)).floatValue());
			}
			// a long past 2^53 is rounded once, to the float, not through the double
			return new Typed(from == Primitive.LONG ? runtimeCall("long-float", value.form())
					: call("%single-float", call("float", value.form(), new LispDouble(1.0))), to);
		}
		if (constant != null) {
			long number = ((Number) number(constant)).longValue();
			return switch ((Primitive) to) {
				case LONG -> constant(number);
				case SHORT -> constant((short) number);
				default -> constant((int) number);
			};
		}
		return new Typed(value.form(), to);
	}

	/** A constant as a number: a character is its code. */
	private static Object number(Object constant) {
		return constant instanceof Character character ? (Object) (int) character.charValue() : constant;
	}

	private static Typed constant(Object value) {
		return switch (value) {
			case Integer number -> new Typed(integer(number), Primitive.INT, value);
			case Byte number -> new Typed(integer(number), Primitive.BYTE, value);
			case Short number -> new Typed(integer(number), Primitive.SHORT, value);
			case Float number -> new Typed(new LispDouble(number), Primitive.FLOAT, value);
			case Long number -> new Typed(integer(number), Primitive.LONG, value);
			case Double number -> new Typed(new LispDouble(number), Primitive.DOUBLE, value);
			case Boolean truth -> new Typed(truth ? T : NIL, Primitive.BOOLEAN, value);
			case Character character -> new Typed(integer(character), Primitive.CHAR, value);
			default -> throw new IllegalStateException("no constant: " + value);
		};
	}

	private Typed constantString(String value) {
		LispVal form = this.classes.identicalStrings
				? this.interned.computeIfAbsent(value, text -> new LispSymbol("%string" + this.interned.size()))
				: string(value);
		this.nonNull.add(form);
		return new Typed(form, this.stringType, value);
	}

	/**
	 * The constant strings of a program that compares strings by reference, each a global
	 * holding the one object its text is.
	 */
	private final Map<String, LispSymbol> interned = new LinkedHashMap<>();

	private Typed box(Typed value) {
		Primitive primitive = (Primitive) value.type();
		ClassType box = JavaIntrinsics.boxed(primitive, this.classes);
		LispVal form = value.form();
		if (primitive == Primitive.BOOLEAN) {
			form = form == T ? T : form == NIL ? JavaForms.BOXED_FALSE : call("if", form, T, JavaForms.BOXED_FALSE);
		}
		else if (primitive == Primitive.CHAR) {
			form = call("code-char", form);
		}
		else if (JavaForms.boxTag(primitive) instanceof LispSymbol tag) {
			form = call("cons", form, tag);
		}
		return new Typed(form, box);
	}

	/**
	 * A value of a box type as the primitive it holds; any other value as it is. A box
	 * that is {@code null} raises: the test is one branch where the box is the number.
	 */
	private static Typed unbox(Typed value) {
		Primitive primitive = JavaIntrinsics.unboxed(value.type());
		if (primitive == null) {
			return value;
		}
		LispVal form = value.form();
		if (primitive == Primitive.BOOLEAN) {
			form = runtimeCall("unbox-boolean", form);
		}
		else {
			if (!(form instanceof LispInteger) && !(form instanceof LispDouble)) {
				form = call("or", form, runtimeCall("null-unbox"));
			}
			if (primitive == Primitive.CHAR) {
				form = call("char-code", form);
			}
			else if (JavaForms.boxTag(primitive) != null) {
				form = call("car", form);
			}
		}
		return new Typed(form, primitive);
	}

	/**
	 * A value converted to a type as an assignment or a call converts it, or {@code null}
	 * when it cannot be.
	 * @param boxing whether a boxing or an unboxing may be part of it
	 * @param narrowing whether an {@code int} constant may become a {@code char}
	 */
	private @Nullable LispVal conversion(Typed value, JavaType to, boolean boxing, boolean narrowing) {
		JavaType from = value.type();
		if (from.equals(to)) {
			return value.form();
		}
		if (from.isPrimitive() && to.isPrimitive()) {
			if (widens(from, to)) {
				return widen(value, to).form();
			}
			if (narrowing && fits(value, to)) {
				return value.form();
			}
			return null;
		}
		if (from.isPrimitive() && to.isReference()) {
			if (!boxing) {
				return null;
			}
			Typed boxed = box(value);
			if (isSubtype(boxed.type(), to)) {
				return boxed.form();
			}
			// JLS 5.2: a constant narrowed, then boxed (Byte b = 1)
			Primitive narrowed = JavaIntrinsics.unboxed(to);
			return narrowing && narrowed != null && fits(value, narrowed)
					? box(new Typed(value.form(), narrowed, value.constant())).form() : null;
		}
		if (to.isPrimitive()) {
			if (isOpen(from)) {
				// what an inference left open is the type it found, else the box its
				// context unboxes
				JavaType found = ((Variable) from).parameter().lower;
				if (found != null) {
					return conversion(new Typed(value.form(), found), to, boxing, narrowing);
				}
				return boxing
						? unbox(new Typed(value.form(), JavaIntrinsics.boxed((Primitive) to, this.classes))).form()
						: null;
			}
			Typed unboxed = unbox(value);
			if (!boxing || unboxed == value) {
				return null;
			}
			return conversion(unboxed, to, false, false);
		}
		return isSubtype(from, to) ? widened(value.form(), from, to) : null;
	}

	/**
	 * Whether a constant of type {@code int}, {@code short}, {@code char} or {@code byte}
	 * is a value of a narrower one of those, which an assignment then takes as it is.
	 */
	private static boolean fits(Typed value, JavaType to) {
		if (!(value.constant() instanceof Number) && !(value.constant() instanceof Character)
				|| !value.type().isIntegral() || value.type() == Primitive.LONG) {
			return false;
		}
		long number = ((Number) number(Objects.requireNonNull(value.constant()))).longValue();
		return switch (to) {
			case Primitive.CHAR -> number >= 0 && number <= Character.MAX_VALUE;
			case Primitive.BYTE -> number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE;
			case Primitive.SHORT -> number >= Short.MIN_VALUE && number <= Short.MAX_VALUE;
			default -> false;
		};
	}

	private static boolean isOpen(JavaType type) {
		return type instanceof Variable variable && variable.parameter().open;
	}

	/** A value as its target takes it in an assignment, a return or an initializer. */
	private LispVal assign(Typed value, JavaType to, int pos) {
		LispVal form = conversion(value, to, true, true);
		if (form == null) {
			throw error(pos, JavaTypes
				.diagnostic("incompatible types: " + value.type() + " cannot be converted to " + to, value.type(), to));
		}
		return form;
	}

	/**
	 * {@link #assign(Typed, JavaType, int)} of an expression a compiler warns of where
	 * its value converts only by the unchecked conversion of a raw type.
	 * @param since how many warnings there were before the expression was lowered
	 */
	private LispVal assign(Typed value, JavaType to, int pos, Expression written, int since) {
		LispVal form = assign(value, to, pos);
		if (uncheckedConversion(value.type(), to)) {
			unchecked(caret(written), since, "unchecked conversion",
					List.of("required: " + to, "found:    " + value.type()), List.of(to, value.type()));
		}
		return form;
	}

	/**
	 * An expression lowered and taken as its target takes it in an initializer:
	 * {@link #assign(Typed, JavaType, int, Expression, int)}.
	 */
	private LispVal assigned(Expression written, JavaType to, int pos) {
		int since = this.warnings.size();
		return assign(expression(written, to), to, pos, written, since);
	}

	/**
	 * Whether a value of one type is one of another only by the unchecked conversion a
	 * raw type allows: the class of the other is a raw supertype of it.
	 */
	private boolean uncheckedConversion(JavaType from, JavaType to) {
		if (from instanceof ArrayType source && to instanceof ArrayType target) {
			return uncheckedConversion(source.element(), target.element());
		}
		if (!from.isReference() || from == JavaType.Null.NULL || !(to instanceof ClassType target)
				|| target.arguments().isEmpty() || reifiable(target)) {
			return false;
		}
		ClassType above = this.types.asSuper(from, target.declaration());
		return above != null && JavaTypes.isRaw(above);
	}

	/**
	 * Whether a type is one the program that runs has whole: no type variable, and no
	 * argument but {@code ?}, which {@code ? extends Object} is (JLS 4.5.1).
	 */
	private static boolean reifiable(JavaType type) {
		return switch (type) {
			case Variable variable -> false;
			case ClassType named -> named.arguments()
				.stream()
				.allMatch(argument -> argument instanceof Wildcard wildcard && wildcard.lower() == null
						&& (wildcard.upper() == null || wildcard.upper() instanceof ClassType bound
								&& bound.declaration().name.equals("java.lang.Object")));
			case ArrayType array -> reifiable(array.element());
			default -> true;
		};
	}

	/**
	 * Whether a cast is one the program that runs cannot check whole: to a type variable,
	 * or to a parameterized type whose arguments the value's type does not say.
	 */
	private boolean uncheckedCast(JavaType from, JavaType to) {
		if (!from.isReference() || from == JavaType.Null.NULL) {
			return false;
		}
		return switch (to) {
			case Variable variable ->
				!variable.parameter().intersection && (!isSubtype(from, to) || uncheckedConversion(from, to));
			case ArrayType array -> from instanceof ArrayType source
					? source.element().isReference() && uncheckedCast(source.element(), array.element())
					: !reifiable(array);
			case ClassType target -> {
				if (reifiable(target) || (isSubtype(from, target) && !uncheckedConversion(from, target))) {
					yield false;
				}
				// a narrowing whose arguments the value's type gives
				ClassType refined = this.types.refine(target.declaration(), from);
				yield !isSubtype(refined, target) || uncheckedConversion(refined, target);
			}
			default -> false;
		};
	}

	/**
	 * A call of an instance member through a raw type, whose parameters the raw type
	 * erases: what a compiler warns of as unchecked.
	 */
	private void uncheckedCall(Method method, @Nullable JavaType site, int caret) {
		if (method.isStatic || site == null) {
			return;
		}
		ClassType seen = this.types.asSuper(site, method.owner);
		if (seen == null || !JavaTypes.isRaw(seen)
				|| method.parameters.stream().allMatch(parameter -> this.types.erased(parameter).equals(parameter))) {
			return;
		}
		unchecked(caret, "unchecked call to " + JavaWarnings.signature(method) + " as a member of the raw type " + seen,
				List.of(), JavaWarnings.mentioned(method));
	}

	/**
	 * A lowered condition and the pattern bindings it leaves: the ones set when it is
	 * true, the ones set when it is false.
	 */
	private record Flow(Typed value, Map<String, Local> whenTrue, Map<String, Local> whenFalse) {
	}

	private Flow test(Expression expression) {
		Flow flow = flow(expression);
		if (flow.value().type() != Primitive.BOOLEAN) {
			throw error(expression.pos(),
					"incompatible types: " + flow.value().type() + " cannot be converted to boolean");
		}
		return flow;
	}

	/** Runs a lowering with pattern bindings in scope. */
	private <R> R within(Map<String, Local> bindings, Supplier<R> lowering) {
		if (bindings.isEmpty()) {
			return lowering.get();
		}
		Scope scope = new Scope(null);
		scope.locals.putAll(bindings);
		this.scopes.push(scope);
		try {
			return lowering.get();
		}
		finally {
			this.scopes.pop();
		}
	}

	private static Map<String, Local> union(Map<String, Local> a, Map<String, Local> b) {
		if (a.isEmpty()) {
			return b;
		}
		if (b.isEmpty()) {
			return a;
		}
		Map<String, Local> both = new LinkedHashMap<>(a);
		both.putAll(b);
		return both;
	}

	/**
	 * Lowers an expression as a condition, following the bindings of its patterns: what
	 * {@code a && b} binds when true is in scope in {@code b}, {@code !a} swaps the two
	 * sets, {@code a || b} binds when false.
	 */
	private Flow flow(Expression expression) {
		Flow flow = condition(expression);
		if (flow.value().constant() instanceof Boolean truth) {
			// what is reached and what is assigned depend on it (JavaFlow)
			this.facts.constants.put(expression, truth);
		}
		return flow;
	}

	private Flow condition(Expression expression) {
		if (expression instanceof InstanceOf test && test.pattern() != null) {
			return matching(test);
		}
		if (expression instanceof Unary unary && unary.operator().equals("!")) {
			Flow operand = flow(unary.operand());
			Typed value = operand.value();
			if (value.type() != Primitive.BOOLEAN) {
				throw error(unary.pos(), "bad operand type " + value.type() + " for unary operator '!'");
			}
			Typed negated = value.constant() instanceof Boolean truth ? constant(!truth)
					: new Typed(call("not", value.form()), Primitive.BOOLEAN);
			return new Flow(negated, operand.whenFalse(), operand.whenTrue());
		}
		if (expression instanceof Binary binary && (binary.operator().equals("&&") || binary.operator().equals("||"))) {
			boolean and = binary.operator().equals("&&");
			Flow left = test(binary.left());
			Flow right = within(and ? left.whenTrue() : left.whenFalse(), () -> test(binary.right()));
			Typed value;
			if (left.value().constant() instanceof Boolean a && right.value().constant() instanceof Boolean b) {
				value = constant(and ? a && b : a || b);
			}
			else {
				value = new Typed(call(and ? "and" : "or", left.value().form(), right.value().form()),
						Primitive.BOOLEAN);
			}
			return and ? new Flow(value, union(left.whenTrue(), right.whenTrue()), Map.of())
					: new Flow(value, Map.of(), union(left.whenFalse(), right.whenFalse()));
		}
		Typed value = expression(expression, Primitive.BOOLEAN);
		if (isOpen(value.type())) {
			value = new Typed(Objects.requireNonNull(conversion(value, Primitive.BOOLEAN, true, false)),
					Primitive.BOOLEAN);
		}
		return new Flow(unbox(value), Map.of(), Map.of());
	}

	/** A binding of a pattern: a variable of the statement the pattern stands in. */
	private Local patternVariable(String name, JavaType type, int pos, Object key) {
		if (variable(name, 0, true) instanceof LocalRef) {
			throw error(pos, "variable " + name + " is already defined");
		}
		String symbol = JavaNames.mangle(name);
		for (Local pending : this.patternVariables) {
			if (pending.symbol().name().equals(symbol)) {
				symbol = symbol + "%" + (this.temporaries++);
				break;
			}
		}
		Local bound = new Local(new LispSymbol(symbol), type, key);
		this.patternVariables.add(bound);
		return bound;
	}

	/**
	 * Runs a lowering and declares the bindings of the patterns it met around its forms.
	 */
	private List<LispVal> patterned(Supplier<List<LispVal>> lowering) {
		List<Local> saved = this.patternVariables;
		this.patternVariables = new ArrayList<>();
		try {
			List<LispVal> forms = lowering.get();
			return this.patternVariables.isEmpty() ? forms : List.of(declared(this.patternVariables, forms));
		}
		finally {
			this.patternVariables = saved;
		}
	}

	private static LispVal declared(List<Local> variables, List<LispVal> body) {
		List<LispVal> bindings = new ArrayList<>();
		for (Local variable : variables) {
			bindings.add(list(variable.symbol(), defaultValue(variable.type())));
		}
		return form(cl("let"), prepend(list(bindings), body));
	}

	/**
	 * A pattern against a value: the tests it makes and the stores of its bindings.
	 * @param value the value's form, one that is evaluated as often as wanted
	 * @param from the value's static type
	 * @param top whether the pattern is the whole of an {@code instanceof}'s, which
	 * {@code null} never matches
	 */
	private void pattern(JavaSyntax.Pattern pattern, LispVal value, JavaType from, boolean top, List<LispVal> tests,
			List<LispVal> stores, Map<String, Local> bindings) {
		switch (pattern) {
			case JavaSyntax.TypePattern written: {
				boolean inferred = written.type().name().equals("var") && written.type().dimensions() == 0;
				JavaType to = inferred ? this.types.ground(from) : patternType(written.type(), from);
				if (from.isPrimitive() || to.isPrimitive()) {
					if (!from.equals(to)) {
						throw error(written.pos(), "incompatible types: " + from + " cannot be converted to " + to);
					}
				}
				else if (isSubtype(from, to)) {
					if (top) {
						tests.add(call("not", call("null", value)));
					}
				}
				else {
					tests.add(instanceTest(value, from, to, written.pos(), "a pattern"));
				}
				if (!written.name().equals("_")) {
					Local bound = patternVariable(written.name(), to == JavaType.Null.NULL ? this.objectType : to,
							written.pos(), written);
					stores.add(call("setq", bound.symbol(), value));
					bindings.put(written.name(), bound);
				}
				return;
			}
			case JavaSyntax.RecordPattern written: {
				if (!(patternType(written.type(), from) instanceof ClassType to) || !to.declaration().isRecord) {
					throw error(written.pos(), "deconstruction patterns can only be applied to records");
				}
				JavaClass record = to.declaration();
				ClassType through = this.types.capture(to);
				tests.add(isSubtype(from, to) ? call("not", call("null", value))
						: instanceTest(value, from, to, written.pos(), "a pattern"));
				List<Field> components = record.fields.stream().filter(field -> !field.isStatic).toList();
				if (components.size() != written.components().size()) {
					throw error(written.pos(), "incorrect number of nested patterns");
				}
				for (int i = 0; i < components.size(); i++) {
					Field component = components.get(i);
					// the accessor a record declares is the one a pattern reads through
					Method accessor = record.methods.stream()
						.filter(method -> method.name.equals(component.name) && method.parameters.isEmpty()
								&& !method.isStatic && method.declaration != null
								&& Objects.requireNonNull(record.declaration).methods().contains(method.declaration))
						.findFirst()
						.orElse(null);
					LispVal read = accessor != null ? list(JavaNames.function(accessor), value)
							: list(JavaNames.accessor(component), value);
					pattern(written.components().get(i), read,
							this.types.upward(this.types.memberType(component.type, through, record)), false, tests,
							stores, bindings);
				}
				return;
			}
		}
	}

	/**
	 * The type a pattern names: a parameterized class named with no argument has the
	 * arguments the value's static type settles.
	 */
	private JavaType patternType(TypeName written, JavaType from) {
		JavaType type = type(written);
		return type instanceof ClassType named && JavaTypes.isRaw(named) ? this.types.refine(named.declaration(), from)
				: type;
	}

	/**
	 * {@code instanceof} with a pattern: the test, storing the bindings when it holds.
	 */
	private Flow matching(InstanceOf test) {
		Typed operand = expression(test.operand(), null);
		if (!operand.type().isReference()) {
			throw error(test.pos(), "unexpected type; required: reference, found: " + operand.type());
		}
		LispSymbol held = isSimple(operand.form()) ? null : temporary();
		List<LispVal> tests = new ArrayList<>();
		List<LispVal> stores = new ArrayList<>();
		Map<String, Local> bindings = new LinkedHashMap<>();
		pattern(Objects.requireNonNull(test.pattern()), held != null ? held : operand.form(), operand.type(), true,
				tests, stores, bindings);
		LispVal form = matched(tests, stores);
		if (held != null) {
			form = JavaForms.let(held, operand.form(), List.of(form));
		}
		return new Flow(new Typed(form, Primitive.BOOLEAN), bindings, Map.of());
	}

	/** The test of a pattern: every test, then the stores, which hold. */
	private static LispVal matched(List<LispVal> tests, List<LispVal> stores) {
		List<LispVal> all = new ArrayList<>(tests);
		if (!stores.isEmpty()) {
			List<LispVal> stored = new ArrayList<>(stores);
			stored.add(T);
			all.add(progn(stored));
		}
		return all.isEmpty() ? T : all.size() == 1 ? all.get(0) : form(cl("and"), all);
	}

	/**
	 * {@code (if test then otherwise)}. A pattern's test, {@code (and tests.. (progn
	 * stores.. t))}, runs its stores at the head of the branch taken instead: no truth
	 * value is made to be tested again (measured 2026-11-17: a tenth of a loop of pattern
	 * tests on wasm, nothing on the JVM).
	 */
	private static LispVal branch(LispVal test, LispVal then, @Nullable LispVal otherwise) {
		List<LispVal> parts = elements(test);
		boolean and = parts.size() > 2 && cl("and").equals(parts.get(0));
		List<LispVal> stored = elements(and ? parts.get(parts.size() - 1) : test);
		if (stored.size() < 3 || !cl("progn").equals(stored.get(0)) || !T.equals(stored.get(stored.size() - 1))) {
			return otherwise == null ? call("if", test, then) : call("if", test, then, otherwise);
		}
		List<LispVal> tests = and ? parts.subList(1, parts.size() - 1) : List.of();
		List<LispVal> body = new ArrayList<>(stored.subList(1, stored.size() - 1));
		body.add(then);
		LispVal condition = tests.isEmpty() ? T : tests.size() == 1 ? tests.get(0) : form(cl("and"), tests);
		return otherwise == null ? call("if", condition, progn(body)) : call("if", condition, progn(body), otherwise);
	}

	/** The elements of a form that is a list; none of any other. */
	private static List<LispVal> elements(LispVal form) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			elements.add(cons.car());
			rest = cons.cdr();
		}
		return elements;
	}

	/** A value as text, as string concatenation and {@code String.valueOf} make it. */
	@Override
	public LispVal stringOf(Typed value, int pos) {
		JavaType type = value.type();
		LispVal form = value.form();
		Object constant = value.constant();
		if (constant != null) {
			return constantString(String.valueOf(constant)).form();
		}
		LispVal text;
		if (type.equals(this.stringType)) {
			return this.nonNull.contains(form) ? form : call("or", form, string("null"));
		}
		else if (type == Primitive.INT || type == Primitive.LONG || type == Primitive.BYTE || type == Primitive.SHORT) {
			text = call("%decimal-piece", form);
		}
		else if (type == Primitive.FLOAT) {
			text = runtimeCall("float-string", form);
		}
		else if (type == Primitive.CHAR) {
			text = runtimeCall("char-string", form);
		}
		else if (type == Primitive.BOOLEAN) {
			text = call("if", form, string("true"), string("false"));
		}
		else if (type == Primitive.DOUBLE) {
			text = runtimeCall("double-string", form);
		}
		else if (type == JavaType.Null.NULL) {
			text = string("null");
		}
		else if (type == Primitive.VOID) {
			throw error(pos, "'void' type not allowed here");
		}
		else if (isPlain(type)) {
			text = runtimeCall("to-string", form);
		}
		else {
			text = objectString(value);
		}
		this.nonNull.add(text);
		return text;
	}

	private LispVal objectString(Typed value) {
		this.generated.add(OBJECT_STRING);
		return list(OBJECT_STRING, value.form());
	}

	/**
	 * Whether every value of a type is one the run-time library answers {@code Object}'s
	 * methods for: a box, an array, a {@code StringBuilder}, a {@code Number} in a
	 * program with no class of its own under it.
	 */
	private boolean isPlain(JavaType type) {
		return JavaIntrinsics.unboxed(type) != null || type instanceof ArrayType
				|| (type instanceof ClassType named && named.declaration().intrinsic
						&& JavaIntrinsics.isPlain(named.declaration()) && !isExtended(named.declaration()));
	}

	/**
	 * Whether the run time answers one of {@code Object}'s methods for every value of a
	 * type: a class, not {@code Object}, under which no class declares the method. Not an
	 * interface or a type variable, whose values may be of any class. The program is
	 * closed: a class the table gains later lowers it again.
	 */
	private boolean runTimeAnswers(JavaType type, String signature) {
		if (!(type instanceof ClassType named) || named.declaration().isInterface
				|| named.declaration() == this.objectType.declaration()) {
			return false;
		}
		for (JavaClass candidate : this.classes.all()) {
			boolean under;
			try {
				under = candidate.isSubclassOf(named.declaration());
			}
			catch (JavaClass.Absent ex) {
				// what stands above a class nobody has cannot be told
				return false;
			}
			if (under && candidate.methods.stream()
				.anyMatch(method -> !method.isStatic && !method.isAbstract && method.signature().equals(signature))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public LispVal stringFunction() {
		this.generated.add(OBJECT_STRING);
		return function(OBJECT_STRING);
	}

	@Override
	public LispVal formatFunction() {
		if (!namesConversion('h') && !namesConversion('g') && !namesConversion('a') && !namesConversion('t')
				&& !makesUnder(JavaIntrinsics.EXACT) && !makesUnder(JavaIntrinsics.FORMATTABLE)) {
			return NIL;
		}
		List<LispVal> parameters = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			parameters.add(temporary());
		}
		return list(cl("lambda"), list(parameters), library(JavaIntrinsics.CONVERSIONS, "more", parameters));
	}

	@Override
	public LispVal formatFunction(LispVal locale) {
		List<LispVal> parameters = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			parameters.add(temporary());
		}
		List<LispVal> arguments = new ArrayList<>();
		arguments.add(locale);
		arguments.addAll(parameters);
		return list(cl("lambda"), list(parameters), library(JavaIntrinsics.CONVERSIONS, "moreIn", arguments));
	}

	@Override
	public boolean readsEnglish(LispVal value) {
		return this.englishReads.contains(value);
	}

	@Override
	public void namesFormat(@Nullable String control) {
		if (this.classes.everyFormat || (control == null && this.unit.source().library())) {
			// the library's own formats pass on a control a call of the program's named
			return;
		}
		boolean named = false;
		if (control == null) {
			this.classes.everyFormat = true;
			named = true;
		}
		else {
			for (int i = control.indexOf('%'); i >= 0 && i + 1 < control.length(); i = control.indexOf('%', i)) {
				int j = i + 1;
				while (j < control.length() && "0123456789$-#+ ,(.<".indexOf(control.charAt(j)) >= 0) {
					j++;
				}
				char conversion = j < control.length() ? Character.toLowerCase(control.charAt(j)) : '%';
				if ("hgat".indexOf(conversion) >= 0) {
					named |= this.classes.formatConversions.add(conversion);
				}
				if (conversion == 't' && j + 1 < control.length() && "Zc".indexOf(control.charAt(j + 1)) >= 0) {
					named |= this.classes.formatConversions.add('Z');
				}
				i = j + 1;
			}
		}
		if (named) {
			// what was lowered for controls naming none of these conversions is lowered
			// again
			this.classes.changed();
		}
	}

	@Override
	public void formatsArguments(Typed arguments) {
		if (this.classes.formatsMillis || this.unit.source().library()) {
			// the library's own formats pass on the arguments a call of the program's
			// gave
			return;
		}
		List<JavaType> types = this.spread.get(arguments.form());
		boolean millis = types == null;
		for (JavaType type : types == null ? List.<JavaType>of() : types) {
			millis |= holdsMillis(type);
		}
		if (millis) {
			this.classes.formatsMillis = true;
			this.classes.changed();
		}
	}

	/**
	 * Whether a value of a type can be a {@code long} or a {@code Date}, which {@code %t}
	 * reads as milliseconds: the primitive, or a class above {@code Long} or
	 * {@code Date}, or under {@code Date}.
	 */
	private boolean holdsMillis(JavaType type) {
		if (type == Primitive.LONG) {
			return true;
		}
		if (!(type instanceof ClassType named)) {
			return !(type instanceof Primitive) && !(type instanceof ArrayType);
		}
		if (MILLIS_HOLDERS.contains(named.declaration().name)) {
			return true;
		}
		for (JavaClass above = named.declaration(); above != null; above = above.superclass) {
			if (above.name.equals("java.util.Date")) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean formatsMillis() {
		return this.classes.session || this.classes.formatsMillis;
	}

	@Override
	public boolean namesConversion(char conversion) {
		return this.classes.session || this.classes.everyFormat || this.classes.formatConversions.contains(conversion);
	}

	@Override
	public boolean makesUnder(String name) {
		if (this.classes.session) {
			// a later buffer may make one
			return true;
		}
		JavaClass above = null;
		for (JavaClass declaration : this.classes.all()) {
			if (declaration.name.equals(name)) {
				above = declaration;
			}
		}
		return above != null && isLive(above);
	}

	@Override
	public LispVal arrayLike(LispVal made, LispVal original) {
		return this.classes.arrayTypes ? runtimeCall("array-like", made, original) : made;
	}

	@Override
	public LispVal madeCall(LispVal funcall) {
		this.resumable.made.add(funcall);
		return funcall;
	}

	@Override
	public LispVal holding(Invocation call, JavaEmitter member) {
		Typed receiver = Objects.requireNonNull(call.receiver());
		if (!this.classes.monitors) {
			return member.emit(this, call);
		}
		List<LispVal> bindings = new ArrayList<>();
		LispSymbol self = temporary();
		bindings.add(list(self, receiver.form()));
		List<Typed> arguments = new ArrayList<>();
		for (Typed argument : call.arguments()) {
			if (isSimple(argument.form())) {
				arguments.add(argument);
				continue;
			}
			LispSymbol held = temporary();
			bindings.add(list(held, argument.form()));
			arguments.add(new Typed(held, argument.type(), argument.constant()));
		}
		Invocation bound = new Invocation(new Typed(self, receiver.type()), arguments, call.discard(), call.pos());
		JavaResumable.Lock lock = receiver.type() instanceof ClassType held
				? new JavaResumable.Lock(held.declaration(), false, false) : null;
		return list(cl("let*"), list(bindings), holding(self, lock, List.of(member.emit(this, bound))));
	}

	@Override
	public void namesZone(@Nullable String name) {
		if (this.unit.source().library() || this.classes.everyRegion) {
			return;
		}
		if (name == null) {
			this.classes.everyRegion = true;
		}
		else if (!JavaZones.shipped().has(name) || !this.classes.regions.add(name)) {
			return;
		}
		// what was lowered for a program holding no region, or not this one, is lowered
		// again
		this.classes.changed();
	}

	@Override
	public void namesHostZone() {
		if (this.classes.everyRegion) {
			return;
		}
		this.classes.everyRegion = true;
		this.classes.changed();
	}

	@Override
	public void readsZones(@Nullable String text) {
		if (this.unit.source().library()) {
			return;
		}
		if (text == null) {
			namesZone(null);
			return;
		}
		JavaZones.shipped().within(text).forEach(this::namesZone);
	}

	@Override
	public boolean holdsRegions() {
		return this.classes.everyRegion || !this.classes.regions.isEmpty();
	}

	@Override
	public boolean hostLogs() {
		return this.classes.wit.boundary.hostLogs();
	}

	@Override
	public boolean foreignTemporals() {
		if (this.classes.session) {
			// a later buffer may declare one
			return true;
		}
		JavaClass accessor = this.classes.library(TEMPORAL_ACCESSOR);
		for (JavaClass candidate : this.classes.all()) {
			String name = candidate.name;
			boolean ours = name.startsWith("java.time.") && name.indexOf('.', "java.time.".length()) < 0;
			if (!ours && isMade(candidate) && candidate.isSubclassOf(accessor)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean asksCalendars() {
		return this.classes.session || this.classes.calendars;
	}

	@Override
	public void namesPattern(@Nullable String pattern) {
		if (this.unit.source().library() || this.classes.everyLetter) {
			// the library's own calls pass on a pattern a call of the program's named
			return;
		}
		boolean named = false;
		if (pattern == null) {
			this.classes.everyLetter = true;
			named = true;
		}
		else {
			boolean quoted = false;
			for (int i = 0; i < pattern.length(); i++) {
				char c = pattern.charAt(i);
				if (c == '\'') {
					quoted = !quoted;
				}
				else if (!quoted && ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
					int end = i + 1;
					while (end < pattern.length() && pattern.charAt(end) == c) {
						end++;
					}
					named |= this.classes.patternLetters.add(c);
					if (end - i >= 3 && "MLec".indexOf(c) >= 0) {
						named |= this.classes.patternLetters.add(JavaIntrinsics.WORDS);
					}
					if (end - i == 2 && (c == 'u' || c == 'y')) {
						named |= this.classes.patternLetters.add(JavaIntrinsics.TWO_DIGITS);
					}
					i = end - 1;
				}
				else if (!quoted && c == '[') {
					named |= this.classes.patternLetters.add(c);
				}
			}
		}
		if (named) {
			// what was lowered for patterns naming none of these letters is lowered again
			this.classes.changed();
		}
	}

	@Override
	public void names(Typed text, JavaEmitter.Naming naming) {
		if (text.constant() instanceof String constant) {
			naming.name(this, constant);
			return;
		}
		Local parameter = text.form() instanceof LispSymbol symbol ? localOf(symbol) : null;
		Method method = parameter == null ? null : parameter.parameterOf;
		if (parameter == null || method == null || this.unit.source().library() || this.classes.session
				|| !(method.isStatic || method.isPrivate) || method.isConstructor()
				|| this.classes.assignedParameters.contains(parameter.key)) {
			naming.name(this, null);
			return;
		}
		if (this.classes.namingParameters.computeIfAbsent(method, key -> new HashMap<>())
			.computeIfAbsent(parameter.parameterIndex, key -> new HashSet<>())
			.add(naming)) {
			// the calls lowered so far noted nothing of what they pass there
			this.classes.changed();
		}
	}

	/**
	 * The local a symbol is the variable of, in the scopes of the body being lowered up
	 * to a local class's, or {@code null}.
	 */
	private @Nullable Local localOf(LispSymbol symbol) {
		for (Scope scope : this.scopes) {
			for (Local local : scope.locals.values()) {
				if (local.symbol().equals(symbol)) {
					return local;
				}
			}
			if (scope.boundary != null) {
				return null;
			}
		}
		return null;
	}

	@Override
	public boolean namesLetter(String letters) {
		if (this.classes.session || this.classes.everyLetter) {
			return true;
		}
		for (int i = 0; i < letters.length(); i++) {
			if (this.classes.patternLetters.contains(letters.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean makesClass(String name) {
		for (JavaClass candidate : this.classes.all()) {
			if (candidate.name.equals(name) && isMade(candidate)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean makesOther(String supertype, String except) {
		if (this.classes.session) {
			// a later buffer may make one
			return true;
		}
		JavaClass above = this.classes.library(supertype);
		for (JavaClass candidate : this.classes.all()) {
			if (!candidate.name.equals(except) && isMade(candidate) && candidate.isSubclassOf(above)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean reaches(String member) {
		return this.classes.session || this.classes.reachedMembers.contains(member);
	}

	@Override
	public boolean reachesOutside(String member) {
		return this.classes.session || this.classes.reachedOutside.contains(member);
	}

	@Override
	public boolean wantedField() {
		Initializing at = this.initializing;
		return this.classes.session || (at != null && at.field() != null && this.classes.wanted.contains(at.field()));
	}

	/**
	 * Notes a read of a static field of the library whose initializer asks whether it is
	 * wanted: the first read lowers the program again, the field then made.
	 */
	private void noteWanted(Field field) {
		if (field.initializer instanceof Conditional conditional && conditional.condition() instanceof Call call
				&& call.target() == null && call.arguments().isEmpty() && call.name().equals("wanted")
				&& field.owner.source != null && field.owner.source.library() && this.classes.wanted.add(field)) {
			this.classes.changed();
		}
	}

	@Override
	public LispVal regions() {
		return JavaForms.string(JavaZones.shipped().table(this.classes.everyRegion ? null : this.classes.regions));
	}

	@Override
	public LispVal zoneNames(boolean read) {
		JavaZoneNames names = JavaZoneNames.shipped();
		java.util.Collection<String> held = this.classes.everyRegion ? null : this.classes.regions;
		return JavaForms.string(read ? names.read(held) : names.written(held));
	}

	/**
	 * The answer to a condition that is a question the library's own source asks of the
	 * program it is lowered into ({@link JavaIntrinsics#answered}), or that such answers
	 * decide: its negation, a conditional and or or whose first operand decides it or
	 * leaves it to a second that is decided; {@code null} for any other condition. What
	 * the answer does not take is not lowered. A library the program uses asks three
	 * questions so: whether a system property no target has ({@code java.class.path}) is
	 * {@code null}, whether a class object carries an annotation that no class of the
	 * program carries, and whether a class loader finds a resource of a name no place of
	 * the program holds.
	 */
	private @Nullable Boolean answered(Expression condition) {
		if (condition instanceof Unary not && not.operator().equals("!")) {
			Boolean answer = answered(not.operand());
			return answer == null ? null : !answer;
		}
		if (condition instanceof Binary both && (both.operator().equals("&&") || both.operator().equals("||"))) {
			// a question has no effect, so the operand it leaves untaken is not lowered
			boolean and = both.operator().equals("&&");
			Boolean first = answered(both.left());
			return first == null ? null : first == !and ? first : answered(both.right());
		}
		if (condition instanceof Binary compared && this.unit.source().partial()
				&& (compared.operator().equals("==") || compared.operator().equals("!="))) {
			// whether there is a system property no target has: what a library asks to
			// tell where it runs, and what it does where there is one is not lowered
			Expression asked = compared.left() instanceof JavaSyntax.NullLiteral ? compared.right()
					: compared.right() instanceof JavaSyntax.NullLiteral ? compared.left() : null;
			if (asked instanceof Call property && property.name().equals("getProperty")
					&& property.arguments().size() == 1 && property.arguments().get(0) instanceof StringLiteral key
					&& !key.value().isEmpty() && !JavaIntrinsics.PROPERTIES.contains(key.value())
					&& !key.value().equals(JavaIntrinsics.WORKING_DIRECTORY) && property.target() != null
					&& classNamed(property.target()) instanceof JavaClass system
					&& system.name.equals("java.lang.System")) {
				return compared.operator().equals("==");
			}
			// whether a class loader finds a resource of a constant name that no place of
			// the program holds: none does, whatever the loader, so what a library does
			// with one is not lowered
			if (asked instanceof Call lookup
					&& (lookup.name().equals("getResource") || lookup.name().equals("getResourceAsStream"))
					&& lookup.arguments().size() == 1 && lookup.arguments().get(0) instanceof StringLiteral name
					&& loaderHeld(lookup.target())
					&& (name.value().startsWith("/") || this.classes.located(name.value(), true, false).isEmpty())) {
				return compared.operator().equals("==");
			}
			return null;
		}
		if (condition instanceof Call present && present.name().equals("isAnnotationPresent")
				&& present.arguments().size() == 1
				&& present.arguments().get(0) instanceof JavaSyntax.ClassLiteral literal
				&& present.target() instanceof Name held && variable(held.identifier(), 0, true) != null
				&& this.unit.source().partial()) {
			// whether a class object carries an annotation no class of the program
			// carries: what a library does with one is not lowered. A class that does is
			// found later at most, and the program is lowered again then
			JavaType named;
			try {
				named = literalType(literal);
			}
			catch (LispReadException | JavaClass.Absent ex) {
				return null;
			}
			return named instanceof ClassType type && type.declaration().isAnnotation
					&& !this.classes.carried(type.declaration()) ? Boolean.FALSE : null;
		}
		if (!(condition instanceof Call call) || !call.arguments().isEmpty() || !this.unit.source().library()) {
			return null;
		}
		JavaClass owner = call.target() == null ? this.currentClass : classNamed(call.target());
		return owner == null ? null : JavaIntrinsics.answered(owner.name + "." + call.name() + "()", this);
	}

	/**
	 * Whether an expression is a class loader read with no effect: a local or a field
	 * named alone or through {@code this}, declared a {@code ClassLoader}.
	 */
	private boolean loaderHeld(@Nullable Expression target) {
		JavaType type = null;
		if (target instanceof Name held) {
			Named named = variable(held.identifier(), 0, true);
			type = named instanceof LocalRef local ? local.local().type()
					: named instanceof FieldRef field ? field.field().type : null;
		}
		else if (target instanceof Select select && select.target() instanceof This && !this.staticContext
				&& this.currentClass != null && field(this.currentClass, select.name()) instanceof Field field) {
			type = field.type;
		}
		return type instanceof ClassType loader && loader.declaration().name.equals(CLASS_LOADER);
	}

	@Override
	public LispVal library(String className, String name, List<LispVal> arguments) {
		JavaClass owner = this.classes.library(className);
		for (Method method : owner.methods) {
			if (method.isStatic && method.name.equals(name) && method.parameters.size() == arguments.size()) {
				called(method);
				uses(owner);
				return form(JavaNames.function(method), arguments);
			}
		}
		throw new IllegalStateException(className + " has no " + name + " of " + arguments.size() + " arguments");
	}

	@Override
	public boolean declaresBelow(String className, Set<String> names) {
		JavaClass above = this.classes.library(className);
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			if (declaration != above && declaration.isSubclassOf(above) && declaration.methods.stream()
				.anyMatch(method -> !method.isStatic && names.contains(method.name))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A call of one of {@code Object}'s methods no class of the receiver's type declares:
	 * the run time's answer where no class under that type declares it either -- what the
	 * universal function of the method would reach by its last arm --, else that
	 * function.
	 */
	@Override
	public LispVal objectMethod(String signature, Typed receiver, Invocation call) {
		boolean plain = isPlain(receiver.type()) || runTimeAnswers(receiver.type(), signature);
		switch (signature) {
			case "toString()":
				return plain ? runtimeCall("to-string", receiver.form()) : objectString(receiver);
			case "equals(Object)":
				if (plain) {
					return runtimeCall("equals", receiver.form(), call.argument(0));
				}
				this.generated.add(OBJECT_EQUALS);
				return list(OBJECT_EQUALS, receiver.form(), call.argument(0));
			default:
				if (plain) {
					return runtimeCall("hash-code", receiver.form());
				}
				this.generated.add(OBJECT_HASH);
				return list(OBJECT_HASH, receiver.form());
		}
	}

	/**
	 * A shallow copy of the receiver -- its fields as they are, a new identity -- where
	 * its class is {@code Cloneable}, else the {@code CloneNotSupportedException} naming
	 * its class: told from the classes the program makes under the receiver's type, so
	 * that the receiver is tested only where some of them are and some are not.
	 */
	@Override
	public LispVal objectClone(Typed receiver) {
		JavaClass through = this.types.site(receiver.type()).declaration();
		// Object's own, which reflection calls on any object: every class is under it
		boolean any = through == this.objectType.declaration();
		List<JavaClass> copied = new ArrayList<>();
		List<JavaClass> refused = new ArrayList<>();
		for (JavaClass candidate : this.classes.all()) {
			if (!candidate.isAbstract && isMade(candidate) && (any || candidate.isSubclassOf(through))) {
				(under(candidate, "java.lang.Cloneable") ? copied : refused).add(candidate);
			}
		}
		if (refused.isEmpty()) {
			return call("copy-structure", receiver.form());
		}
		LispSymbol value = receiver.form() instanceof LispSymbol symbol ? symbol : temporary();
		LispVal name = refused.size() == 1 ? string(refused.get(0).binaryName) : className(value);
		LispVal refusal = raise(list(creator("java.lang.CloneNotSupportedException"), name));
		LispVal answer = refusal;
		if (!copied.isEmpty()) {
			List<LispVal> tests = new ArrayList<>();
			tests.add(cl("or"));
			for (JavaClass candidate : copied) {
				tests.add(list(JavaNames.predicate(candidate), value));
			}
			answer = call("if", tests.size() == 2 ? tests.get(1) : list(tests), call("copy-structure", value), refusal);
		}
		return value == receiver.form() ? answer : JavaForms.let(value, receiver.form(), List.of(answer));
	}

	/** The binary name of the class of an instance of a class the program makes. */
	private LispVal className(LispVal value) {
		this.generated.add(CLASS_NAME);
		return list(CLASS_NAME, value);
	}

	// ---- expressions

	private JavaType type(TypeName written) {
		return this.classes.type(written, this.currentClass, this.unit);
	}

	private Typed thisValue(int pos) {
		if (this.staticContext) {
			throw error(pos, "non-static variable this cannot be referenced from a static context");
		}
		if (this.early) {
			throw error(pos, "cannot reference this before supertype constructor has been called");
		}
		return new Typed(THIS, this.currentClass.self());
	}

	/**
	 * A member that is {@code private} is reached from its own top-level class only; one
	 * of a class of the program with no access modifier from its package only, one that
	 * is {@code protected} from its package and from the classes under its class. A
	 * member the front end writes itself has the access of its class.
	 * @param declaration the member as written, or {@code null}
	 */
	private void checkAccess(boolean isPrivate, @Nullable Object declaration, JavaClass owner, String what, int pos) {
		if (this.classes.isGenerated(this.currentClass.topLevel())) {
			// what the front end writes for a class reaches what the class reaches
			return;
		}
		if (isPrivate && owner.topLevel() != this.currentClass.topLevel()) {
			throw error(pos, what + " has private access in " + owner.simpleName());
		}
		Set<String> modifiers = switch (declaration) {
			case JavaSyntax.Method method -> method.modifiers();
			case JavaSyntax.Field field -> field.modifiers();
			case null, default -> Set.of("public");
		};
		if (isPrivate || owner.intrinsic || isLibrary(owner) || owner.isInterface || modifiers.contains("public")
				|| Objects.equals(this.classes.packageOf(owner), this.unit.packageName())) {
			return;
		}
		if (!modifiers.contains("protected")) {
			throw error(pos,
					what + " is not public in " + owner.simpleName() + "; cannot be accessed from outside package");
		}
		for (JavaClass around = this.currentClass; around != null; around = around.outer) {
			if (around.isSubclassOf(owner)) {
				return;
			}
		}
		throw error(pos, what + " has protected access in " + owner.simpleName());
	}

	/**
	 * A {@code protected} instance method of another package -- {@code Object}'s, or one
	 * a class of the program calls, the library trusting itself -- called through a
	 * reference (JLS 6.6.2): only from a class under its class, where the reference's
	 * type -- a type variable's bound -- is that class or one under it.
	 */
	private void protectedReach(Method method, JavaType type, int pos) {
		JavaType reached = type instanceof Variable ? this.types.site(type) : type;
		if (reached instanceof ClassType named) {
			for (JavaClass around = this.currentClass; around != null; around = around.outer) {
				if ((method.owner == this.objectType.declaration() || around.isSubclassOf(method.owner))
						&& named.declaration().isSubclassOf(around)) {
					return;
				}
			}
		}
		throw error(pos, method.signature() + " has protected access in " + method.owner.simpleName());
	}

	/** A simple name read as a value. */
	private Typed read(Name written) {
		String name = written.identifier();
		int pos = written.pos();
		Named variable = variable(name, pos, false);
		return switch (variable) {
			case null -> throw this.classes.unknown(name, pos, this.unit);
			case LocalRef found -> {
				this.facts.variables.put(written, found.local().key);
				yield new Typed(found.local().symbol(), found.local().type(), found.local().constant);
			}
			case CaptureRef found -> found.read();
			case FieldRef found -> {
				Field field = found.field();
				this.facts.variables.put(written, field);
				if (!field.isStatic) {
					requireInstance(field, found.object(), pos);
				}
				else if (forward(field)) {
					throw error(pos, "illegal forward reference");
				}
				yield fieldRead(field, found.object(), pos, found.through().self());
			}
		};
	}

	/** Refuses an instance field named where no instance holds it. */
	private void requireInstance(Field field, @Nullable LispVal object, int pos) {
		if (object == null) {
			throw error(pos,
					"non-static variable " + (this.currentClass.isSubclassOf(field.owner) ? "this" : field.name)
							+ " cannot be referenced from a static context");
		}
		if (this.early && object == THIS) {
			throw error(pos, "cannot reference " + field.name + " before supertype constructor has been called");
		}
	}

	/** The dotted name an expression spells when it is names and selections only. */
	private static @Nullable String dotted(Expression expression) {
		if (expression instanceof Name name) {
			return name.identifier();
		}
		if (expression instanceof Select select) {
			String target = dotted(select.target());
			return target == null ? null : target + "." + select.name();
		}
		return null;
	}

	/** The class an expression names, when it names one and no variable. */
	private @Nullable JavaClass classNamed(Expression expression) {
		String name = dotted(expression);
		if (name == null) {
			return null;
		}
		String first = name.indexOf('.') < 0 ? name : name.substring(0, name.indexOf('.'));
		if (variable(first, 0, true) != null) {
			return null;
		}
		JavaClass found = this.classes.find(name, this.currentClass, this.unit, expression.pos());
		if (found != null) {
			this.classes.accessible(found, this.unit, expression.pos());
		}
		else if (this.classes.namesPlatformClass(name, this.currentClass, this.unit, expression.pos())) {
			throw JavaClasses.missing(this.unit.source(), expression.pos(), "the class " + name, name);
		}
		return found;
	}

	private Typed expression(Expression expression, @Nullable JavaType expected) {
		return expression(expression, expected, false);
	}

	/**
	 * Lowers an expression.
	 * @param expected the type its context gives it, which a lambda, a diamond and an
	 * array initializer take theirs from, or {@code null}
	 * @param discard whether its value is not used
	 */
	private Typed expression(Expression expression, @Nullable JavaType expected, boolean discard) {
		try {
			switch (expression) {
				case IntLiteral literal:
					return literal.isLong() ? constant(literal.value()) : constant((int) literal.value());
				case DoubleLiteral literal:
					return literal.isFloat() ? constant((float) literal.value()) : constant(literal.value());
				case CharLiteral literal:
					return constant((char) literal.value());
				case StringLiteral literal:
					return constantString(literal.value());
				case BooleanLiteral literal:
					return constant(literal.value());
				case NullLiteral literal:
					return new Typed(NIL, JavaType.Null.NULL);
				case This self:
					return thisValue(self.pos());
				case JavaSyntax.Super above: {
					// super.x: the field as the class above sees it, which one here may
					// hide
					if (this.staticContext) {
						throw error(above.pos(),
								"non-static variable super cannot be referenced from a static context");
					}
					Typed self = thisValue(above.pos());
					JavaClass superclass = this.currentClass.superclass;
					return new Typed(self.form(),
							superclass == null ? this.objectType : this.currentClass.supertype(superclass));
				}
				case JavaSyntax.ClassLiteral literal:
					return classLiteral(literal);
				case Name name:
					return read(name);
				case QualifiedThis self: {
					JavaClass named = classNamed(self.qualifier());
					if (named == null) {
						throw error(self.pos(), "cannot find symbol: class " + dotted(self.qualifier()));
					}
					if (this.staticContext) {
						throw error(self.pos(), "non-static variable this cannot be referenced from a static context");
					}
					LispVal instance = lexicallyAround(named);
					if (instance == null) {
						throw error(self.pos(),
								this.early && this.currentClass.isSubclassOf(named)
										? "cannot reference this before supertype constructor has been called"
										: "not an enclosing class: " + named.simpleName());
					}
					return new Typed(instance, named.self());
				}
				case Select select:
					return select(select);
				case Call call:
					return walking(call, invocation(call, expected, discard));
				case ConstructorCall call:
					throw error(call.pos(),
							"explicit constructor invocation may only appear within a constructor body");
				case New creation:
					return creation(creation, expected);
				case NewArray creation: {
					ArrayType type = (ArrayType) type(creation.type());
					if (creation.initializer() != null) {
						return arrayInitializer(creation.initializer(), type);
					}
					List<LispVal> lengths = new ArrayList<>();
					for (Expression written : creation.lengths()) {
						Typed length = unbox(expression(written, null));
						if (length.type() != Primitive.INT && !length.type().isBelowInt()) {
							throw error(written.pos(),
									"incompatible types: " + length.type() + " cannot be converted to int");
						}
						lengths.add(length.form());
					}
					LispVal array = newArray(type, lengths);
					this.nonNull.add(array);
					return new Typed(array, type);
				}
				case ArrayInitializer initializer:
					if (!(expected instanceof ArrayType type)) {
						throw error(initializer.pos(), "illegal initializer for " + expected);
					}
					return arrayInitializer(initializer, type);
				case Index index: {
					Place place = place(index, false, false);
					return new Typed(place.read(), place.type());
				}
				case Unary unary:
					return unary.operator().equals("!") ? flow(unary).value() : unary(unary, discard);
				case Binary binary:
					return binary.operator().equals("&&") || binary.operator().equals("||") ? flow(binary).value()
							: binary(binary);
				case Assign assignment:
					return assignment(assignment);
				case Conditional conditional:
					return conditional(conditional, expected);
				case Cast cast:
					return cast(cast);
				case InstanceOf test:
					return test.pattern() != null ? flow(test).value() : instanceOf(test);
				case Lambda lambda:
					return lambda(lambda, expected, null);
				case JavaSyntax.MethodReference reference:
					return methodReference(reference, expected, null);
				case JavaSyntax.Lowered lowered:
					return (Typed) lowered.value();
				case JavaSyntax.Echo echo:
					return echo(echo);
				case JavaSyntax.Switch block:
					return switchExpression(block, expected);
			}
		}
		catch (JavaClass.Absent ex) {
			// a class nobody has, named by a library: refused where the program reaches
			// it
			throw (LispReadException) error(expression.pos(), Objects.requireNonNull(ex.getMessage())).initCause(ex);
		}
	}

	/**
	 * The element type an array of a primitive type is made with: each has one of its
	 * own, which {@code instanceof} reads, but a {@code short} and a {@code char} share
	 * one and a {@code long} has none. An integer type's array holds the low bits of its
	 * values, which a read takes back signed ({@link #elementRead}); a {@code boolean}'s
	 * holds bits.
	 */
	private static @Nullable LispVal elementSpecifier(JavaType element) {
		if (!(element instanceof Primitive primitive)) {
			return null;
		}
		return switch (primitive) {
			case INT -> list(cl("unsigned-byte"), integer(32));
			case BYTE -> list(cl("unsigned-byte"), integer(8));
			case SHORT, CHAR -> list(cl("unsigned-byte"), integer(16));
			case FLOAT -> cl("single-float");
			case DOUBLE -> cl("double-float");
			case BOOLEAN -> cl("bit");
			default -> null;
		};
	}

	// ---- the class of an array

	/**
	 * Whether an array type is one its vector does not tell: an array of {@code long} or
	 * {@code short} (a {@code long[]} is the general vector an {@code Object[]} is, a
	 * {@code short[]} the packed one of a {@code char[]}) or of references other than
	 * {@code Object}. Such an array's class is what the run time recorded of it where its
	 * type was widened ({@code %java-array-widened}).
	 */
	private boolean isUntold(ArrayType array) {
		JavaType element = this.types.erased(array.element());
		if (element instanceof Primitive primitive) {
			return primitive == Primitive.LONG || primitive == Primitive.SHORT;
		}
		return !element.equals(this.objectType);
	}

	/**
	 * The name of a type as the name of an array type spells it: {@code I}, {@code [J},
	 * {@code Ljava.lang.String;}.
	 */
	private String descriptor(JavaType type) {
		JavaType erased = this.types.erased(type);
		return switch (erased) {
			case Primitive primitive -> switch (primitive) {
				case BOOLEAN -> "Z";
				case BYTE -> "B";
				case SHORT -> "S";
				case CHAR -> "C";
				case INT -> "I";
				case LONG -> "J";
				case FLOAT -> "F";
				case DOUBLE -> "D";
				default -> throw new IllegalStateException(erased.toString());
			};
			case ArrayType array -> "[" + descriptor(array.element());
			case ClassType named -> "L" + named.declaration().binaryName + ";";
			default -> throw new IllegalStateException(erased.toString());
		};
	}

	private String simpleArrayName(ArrayType array) {
		JavaType element = this.types.erased(array.element());
		return (element instanceof ArrayType inner ? simpleArrayName(inner)
				: element instanceof ClassType named ? named.declaration().simpleName() : element.toString()) + "[]";
	}

	/**
	 * The names of the array types an array of a type is an instance of, its own first:
	 * the arrays of its element type's supertypes, {@code Object[]} for an array of
	 * arrays.
	 */
	private List<String> arraySupertypes(ArrayType array) {
		JavaType element = this.types.erased(array.element());
		Set<String> names = new LinkedHashSet<>();
		names.add(descriptor(array));
		if (element instanceof ArrayType inner) {
			for (String name : arraySupertypes(inner)) {
				names.add("[" + name);
			}
			names.add(descriptor(new ArrayType(this.objectType)));
		}
		else if (element instanceof ClassType named) {
			List<JavaClass> pending = new ArrayList<>(List.of(named.declaration()));
			Set<JavaClass> seen = new HashSet<>();
			while (!pending.isEmpty()) {
				JavaClass type = pending.remove(0);
				if (seen.add(type)) {
					names.add("[L" + type.binaryName + ";");
					if (type.superclass != null) {
						pending.add(type.superclass);
					}
					if (type.intrinsicSuperclass != null) {
						pending.add(type.intrinsicSuperclass);
					}
					for (JavaClass implemented : type.interfaces) {
						if (listsInterface(type, implemented)) {
							pending.add(implemented);
						}
					}
				}
			}
			names.add(descriptor(new ArrayType(this.objectType)));
		}
		return List.copyOf(names);
	}

	/**
	 * What the run time keeps of an array's class: its simple name, then the names of the
	 * array types it is an instance of, its own first.
	 */
	private LispVal arrayClass(ArrayType array) {
		if (this.types.erased(array) instanceof ArrayType recorded && this.classes.arrayRecords.add(recorded)
				&& this.classes.arrayClassValues && this.generationOver) {
			this.classes.changed();
		}
		return arrayRecord(array);
	}

	/**
	 * What the run time keeps of the class of an array of a type ({@link #arrayClass}).
	 */
	private LispVal arrayRecord(ArrayType array) {
		List<LispVal> names = new ArrayList<>();
		names.add(string(simpleArrayName(array)));
		for (String name : arraySupertypes(array)) {
			names.add(string(name));
		}
		return list(names);
	}

	/**
	 * Whether every array of a type is of that type: one of a primitive type, or of such
	 * arrays.
	 */
	private boolean isExact(ArrayType array) {
		JavaType element = this.types.erased(array.element());
		return element.isPrimitive() || element instanceof ArrayType inner && isExact(inner);
	}

	/** Records that the program asks the class of an array its vector does not tell. */
	private void asksArrayTypes() {
		if (!this.classes.arrayTypes) {
			// what was lowered recording nothing is lowered again recording
			this.classes.arrayTypes = true;
			this.classes.changed();
		}
	}

	/**
	 * A value whose type is converted to another: an array its vector does not tell the
	 * class of, its type widened, has its class recorded -- and the arrays it holds
	 * theirs, as deep as their vectors do not tell either -- in a program that asks.
	 */
	private LispVal widened(LispVal form, JavaType from, JavaType to) {
		if (!this.classes.arrayTypes || !(from instanceof ArrayType array) || !isUntold(array)
				|| this.types.erased(to).equals(this.types.erased(from))
				|| form instanceof LispCons cons && cons.car().equals(JavaForms.runtime("array-widened"))) {
			return form;
		}
		List<LispVal> levels = new ArrayList<>();
		for (JavaType level = array; level instanceof ArrayType type
				&& isUntold(type); level = this.types.erased(type.element())) {
			levels.add(arrayClass(type));
		}
		return runtimeCall("array-widened", form, quote(list(levels)));
	}

	/**
	 * The test that a value of one static type is an instance of an array type: of a
	 * primitive type the vector tells, its element type; else the class the run time
	 * recorded, or the static type's when it is an array type.
	 */
	private LispVal arrayTest(LispVal value, JavaType from, ArrayType to, int pos) {
		JavaType innermost = to;
		while (innermost instanceof ArrayType nested) {
			innermost = this.types.erased(nested.element());
		}
		if (innermost instanceof ClassType named && REFLECTION_INTERFACES.contains(named.declaration().name)
				&& !this.classes.reflectionInterfacesNamed) {
			// the arrays of reflection's classes record those interfaces from now on
			this.classes.reflectionInterfacesNamed = true;
			this.classes.changed();
		}
		JavaType source = this.types.erased(this.types.upward(from));
		if (!(source instanceof ArrayType) && !source.equals(this.objectType)
				|| source instanceof ArrayType array && !castable(array, to)) {
			throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
		}
		if (!(source instanceof ArrayType) && elementSpecifier(to.element()) instanceof LispVal element
				&& to.element() != Primitive.SHORT && to.element() != Primitive.CHAR) {
			return call("typep", value, quote(list(cl("simple-array"), element, list(cl("*")))));
		}
		asksArrayTypes();
		return runtimeCall("array-is", value, string(descriptor(to)),
				source instanceof ArrayType array ? quote(arrayClass(array)) : NIL);
	}

	/** Whether a cast can take a value of one array type to another. */
	private boolean castable(ArrayType from, ArrayType to) {
		JavaType a = this.types.erased(from.element());
		JavaType b = this.types.erased(to.element());
		if (a.isPrimitive() || b.isPrimitive()) {
			return a.equals(b);
		}
		if (a instanceof ArrayType inner && b instanceof ArrayType other) {
			return castable(inner, other);
		}
		if (a instanceof ArrayType || b instanceof ArrayType) {
			return a.equals(this.objectType) || b.equals(this.objectType);
		}
		return ((ClassType) a).declaration().isInterface || ((ClassType) b).declaration().isInterface || isSubtype(a, b)
				|| isSubtype(b, a);
	}

	/**
	 * {@code new T[n]..}: a vector of each length the expression gives, every level but
	 * the last holding the next level's. A negative length is the language's exception,
	 * which the run time makes once every length is evaluated; what it answers is an
	 * {@code int}'s length, which tells the backends each level is a vector of an integer
	 * size.
	 */
	private LispVal newArray(ArrayType type, List<LispVal> lengths) {
		LispVal range = sizeRange();
		if (lengths.size() == 1) {
			LispVal length = lengths.get(0) instanceof LispInteger n && n.value() >= 0 ? lengths.get(0)
					: list(cl("the"), range, runtimeCall("array-length", lengths.get(0)));
			return vector(type, length);
		}
		List<LispVal> evaluated = new ArrayList<>();
		List<LispVal> checked = new ArrayList<>();
		List<LispVal> sizes = new ArrayList<>();
		List<LispVal> declared = new ArrayList<>(List.of(cl("type"), range));
		for (LispVal length : lengths) {
			if (length instanceof LispInteger n && n.value() >= 0) {
				sizes.add(length);
				continue;
			}
			LispSymbol given = temporary();
			LispSymbol size = temporary();
			evaluated.add(list(given, length));
			checked.add(list(size, runtimeCall("array-length", given)));
			sizes.add(size);
			declared.add(size);
		}
		LispVal made = rows(type, sizes, 0);
		if (checked.isEmpty()) {
			return made;
		}
		return list(cl("let"), list(evaluated),
				list(cl("let"), list(checked), list(cl("declare"), list(declared)), made));
	}

	/**
	 * The level {@code level} of {@code new T[n]..}, of an array type, its sizes made.
	 */
	private LispVal rows(ArrayType type, List<LispVal> sizes, int level) {
		LispVal size = sizes.get(level);
		if (level == sizes.size() - 1) {
			return vector(type, size);
		}
		LispSymbol rows = temporary();
		LispSymbol index = temporary();
		return list(cl("let"), list(list(rows, vector(type, size)), list(index, integer(0))),
				list(cl("declare"), list(cl("type"), sizeRange(), index)),
				list(cl("while"), call("<", index, size),
						call("setf", call("aref", rows, index), rows((ArrayType) type.element(), sizes, level + 1)),
						call("setq", index, call("+", index, integer(1)))),
				rows);
	}

	/** The sizes an array has: an {@code int} that is not negative. */
	private static LispVal sizeRange() {
		return list(cl("integer"), integer(0), integer(Integer.MAX_VALUE));
	}

	/** A vector of an array type, {@code size} long, of its element type's default. */
	private static LispVal vector(ArrayType type, LispVal size) {
		List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), size));
		made.addAll(elementType(type.element()));
		made.add(new LispSymbol(":INITIAL-ELEMENT"));
		made.add(type.element() == Primitive.BOOLEAN ? integer(0) : defaultValue(type.element()));
		return list(made);
	}

	private static List<LispVal> elementType(JavaType element) {
		LispVal specifier = elementSpecifier(element);
		return specifier == null ? List.of() : List.of(new LispSymbol(":ELEMENT-TYPE"), quote(specifier));
	}

	/** An element read from an array: as the array holds it, taken back as its type. */
	private static LispVal elementRead(JavaType element, LispVal read) {
		if (element == Primitive.INT) {
			return JavaForms.wrap(Primitive.INT, read);
		}
		if (element == Primitive.SHORT || element == Primitive.BYTE) {
			return JavaForms.signed(read, element == Primitive.SHORT ? 16 : 8);
		}
		return element == Primitive.BOOLEAN ? call("=", read, integer(1)) : read;
	}

	/** A value as an array of its type holds it: a {@code boolean} as a bit. */
	private static LispVal elementStored(JavaType element, LispVal value) {
		if (element != Primitive.BOOLEAN) {
			return value;
		}
		return value == T ? integer(1) : value == NIL ? integer(0) : call("if", value, integer(1), integer(0));
	}

	private Typed arrayInitializer(ArrayInitializer initializer, ArrayType type) {
		List<LispVal> elements = new ArrayList<>();
		for (Expression element : initializer.elements()) {
			elements.add(elementStored(type.element(), assigned(element, type.element(), element.pos())));
		}
		return new Typed(arrayOf(type, elements), type);
	}

	/**
	 * An array of an element type holding the values of forms. Integral elements that are
	 * all constants are a quoted list of what the packed array stores (a negative value
	 * as its bits), which the compile path folds to a packed literal: a table the module
	 * holds as data rather than code that conses its elements where it runs.
	 */
	private static LispVal arrayOf(ArrayType type, List<LispVal> elements) {
		List<LispVal> typed = elementType(type.element());
		if (typed.isEmpty()) {
			return form(cl("vector"), elements);
		}
		List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), integer(elements.size())));
		made.addAll(typed);
		made.add(new LispSymbol(":INITIAL-CONTENTS"));
		List<LispVal> stored = storedConstants(type.element(), elements);
		made.add(stored != null ? quote(list(stored)) : form(cl("list"), elements));
		return list(made);
	}

	/**
	 * The integers a packed array of an integral element type stores for constant
	 * elements, or {@code null} where an element is no constant or the type no integral
	 * one.
	 */
	private static @Nullable List<LispVal> storedConstants(JavaType element, List<LispVal> elements) {
		long mask = element == Primitive.INT ? 0xFFFFFFFFL : element == Primitive.BYTE ? 0xFFL
				: element == Primitive.SHORT || element == Primitive.CHAR ? 0xFFFFL : 0;
		if (mask == 0 || elements.isEmpty()) {
			return null;
		}
		List<LispVal> stored = new ArrayList<>(elements.size());
		for (LispVal value : elements) {
			if (!(value instanceof LispInteger integer)) {
				return null;
			}
			stored.add(integer(integer.value() & mask));
		}
		return stored;
	}

	/** Whether a class is initialized wherever the code being lowered runs. */
	private boolean initialized(JavaClass owner) {
		if (this.launched != null && this.launched.isSubclassOf(owner)) {
			return true;
		}
		for (JavaClass type = this.currentClass; type != null; type = type.isLocal || type.hasOuterInstance()
				? type.outer : null) {
			if (type.isSubclassOf(owner)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A field read.
	 * @param site the type it is read through, which gives the type parameters of the
	 * field's class their arguments, or {@code null} for a static field
	 */
	private Typed fieldRead(Field field, @Nullable LispVal object, int pos, @Nullable JavaType site) {
		if (field.accessor != null) {
			return new Typed(library(field.accessor.className(), field.accessor.method(), List.of()), field.type);
		}
		if (field.form != null) {
			Object constant = !(field.type instanceof Primitive type) ? null
					: field.form instanceof LispInteger number ? narrowed(number.value(), type)
							: field.form instanceof LispDouble number ? narrowed(number.value(), type) : null;
			return new Typed(field.form, field.type, constant);
		}
		checkAccess(field.isPrivate, field.declaration, field.owner, field.name, pos);
		if (!field.isStatic) {
			LispVal read = list(JavaNames.accessor(field), Objects.requireNonNull(object));
			if (isVolatile(field)) {
				// the object first: only the read itself is ordered among the threads
				List<LispVal> bindings = new ArrayList<>();
				read = bound(bindings, ordered(list(JavaNames.accessor(field), stabilized(object, true, bindings))));
			}
			return new Typed(read,
					inScope(this.types.upward(this.types.memberType(field.type, site, field.owner)), pos));
		}
		Object constant = constantOf(field);
		if (constant != null) {
			return constant instanceof String text ? constantString(text) : constant(constant);
		}
		this.effects.reads.add(field);
		noteWanted(field);
		uses(field.owner);
		if (forward(field)) {
			this.effects.forward.add(field);
		}
		LispVal global = JavaNames.global(field);
		LispVal read = isVolatile(field) ? ordered(global) : global;
		if (lazilyRead(field)) {
			return new Typed(call("progn", list(JavaNames.initializer(field.owner)), read), field.type);
		}
		// a static's variable is no local, which a declaration would cover
		LispVal asserted = JavaForms.asserted(field.type, read);
		if (field.owner.name.equals("java.util.Locale") && (field.name.equals("US") || field.name.equals("ENGLISH"))) {
			this.englishReads.add(asserted);
		}
		return new Typed(asserted, field.type);
	}

	/**
	 * Whether a read or a write of a static field runs its class's initialization first.
	 */
	private boolean lazilyRead(Field field) {
		return this.order.isLazy(field.owner) && !initialized(field.owner);
	}

	private static boolean isVolatile(Field field) {
		return field.declaration != null && field.declaration.modifiers().contains("volatile");
	}

	/**
	 * A read or a write of a {@code volatile} field: one at a time among the threads, so
	 * that each sees what the one before it left ({@code .kb/java-frontend.md},
	 * "Threads"). Nothing but the access itself runs inside. A program with no thread
	 * ({@link JavaClasses#monitors}) reads and writes the field as any other.
	 */
	private LispVal ordered(LispVal access) {
		if (!this.classes.monitors) {
			return access;
		}
		return list(new LispSymbol("RONTOLISP:WITH-MUTEX"), list(runtimeCall("volatile")), access);
	}

	/**
	 * A member's type, refused when it still names a type parameter of a class the
	 * lowering does not stand in: no type it was read through gave that parameter an
	 * argument. A type of an inner member class carries the arguments of the classes
	 * around it, so only a member of a local class could get here.
	 */
	private JavaType inScope(JavaType type, int pos) {
		switch (type) {
			case Variable variable: {
				JavaClass owner = variable.parameter().owner;
				boolean around = owner == null;
				for (JavaClass at = this.currentClass; at != null && !around; at = at.outer) {
					around = at == owner;
				}
				if (!around) {
					throw unsupported(pos, "a member typed by a type parameter of "
							+ Objects.requireNonNull(owner).simpleName() + " read from outside that class");
				}
				break;
			}
			case ClassType named:
				named.arguments().forEach(argument -> inScope(argument, pos));
				break;
			case ArrayType array:
				inScope(array.element(), pos);
				break;
			case Wildcard wildcard:
				if (wildcard.upper() != null) {
					inScope(wildcard.upper(), pos);
				}
				if (wildcard.lower() != null) {
					inScope(wildcard.lower(), pos);
				}
				break;
			default:
		}
		return type;
	}

	/**
	 * The value of a constant variable -- a {@code static final} field of a primitive
	 * type or {@code String} whose initializer is a constant expression --, which every
	 * read of it is; {@code null} for any other field.
	 */
	private @Nullable Object constantOf(Field field) {
		Expression initializer = field.initializer;
		if (!field.isStatic || !field.isFinal || initializer == null
				|| !(field.type.isPrimitive() || field.type.equals(this.stringType))) {
			return null;
		}
		Object known = this.constants.get(field);
		if (known == null) {
			// a field read while its own initializer is lowered is no constant
			this.constants.put(field, NOT_CONSTANT);
			Effects saved = this.effects;
			List<Local> savedPatterns = this.patternVariables;
			this.effects = new Effects();
			this.patternVariables = new ArrayList<>();
			known = inContext(field.owner, true, () -> {
				if (!constantShape(initializer)) {
					return NOT_CONSTANT;
				}
				Object constant = constantValue(expression(initializer, field.type), field.type);
				return constant == null ? NOT_CONSTANT : constant;
			});
			this.effects = saved;
			this.patternVariables = savedPatterns;
			this.constants.put(field, known);
		}
		return known == NOT_CONSTANT ? null : known;
	}

	/**
	 * The value a constant variable of a type holds when its initializer has a value: the
	 * initializer's, converted; {@code null} when it is no constant expression or the
	 * type no constant variable's.
	 */
	private @Nullable Object constantValue(Typed value, JavaType type) {
		Object constant = value.constant();
		if (constant == null || !(type.isPrimitive() || type.equals(this.stringType))
				|| conversion(value, type, false, true) == null) {
			return null;
		}
		if (type.isPrimitive() && value.type() != type) {
			constant = widens(value.type(), type) ? widen(value, type).constant()
					: narrowed(constant, (Primitive) type);
		}
		return constant;
	}

	/**
	 * Whether an expression can be a constant expression by its shape: literals, names,
	 * operators, casts and the conditional. Any other is never lowered to find out, so a
	 * class it declares or a method it calls is met once, where its initializer runs.
	 */
	private static boolean constantShape(Expression expression) {
		return switch (expression) {
			case IntLiteral literal -> true;
			case DoubleLiteral literal -> true;
			case CharLiteral literal -> true;
			case StringLiteral literal -> true;
			case BooleanLiteral literal -> true;
			case Name name -> true;
			case Select select -> constantShape(select.target());
			case Unary unary ->
				!unary.operator().equals("++") && !unary.operator().equals("--") && constantShape(unary.operand());
			case Binary binary -> constantShape(binary.left()) && constantShape(binary.right());
			case Conditional conditional -> constantShape(conditional.condition())
					&& constantShape(conditional.whenTrue()) && constantShape(conditional.whenFalse());
			case Cast cast -> constantShape(cast.operand());
			default -> false;
		};
	}

	private Typed select(Select select) {
		JavaClass named = classNamed(select.target());
		if (named != null) {
			bring(named, select.name());
			Field field = field(named, select.name());
			if (field == null) {
				throw named.intrinsic || isLibrary(named)
						? unsupported(select.pos(), named.simpleName() + "." + select.name()) : error(select.pos(),
								"cannot find symbol: variable " + select.name() + " in class " + named.simpleName());
			}
			if (!field.isStatic) {
				throw error(select.pos(),
						"non-static variable " + select.name() + " cannot be referenced from a static context");
			}
			return fieldRead(field, null, select.pos(), null);
		}
		Place place = place(select, false, false);
		return new Typed(place.read(), place.type());
	}

	/**
	 * Where an expression stores: a local, a field, an array element.
	 * @param stable whether the place is read and then written, so that what it is made
	 * of is evaluated once: an operand that is more than a variable is bound first
	 */
	private Place place(Expression expression, boolean stable, boolean assigning) {
		List<LispVal> bindings = new ArrayList<>();
		switch (expression) {
			case Name name: {
				Named variable = variable(name.identifier(), name.pos(), false);
				if (variable instanceof LocalRef found) {
					Local local = found.local();
					this.facts.variables.put(name, local.key);
					return new Place(local.type(), local.symbol(), value -> {
						if (local.isFinal) {
							throw error(name.pos(), "cannot assign a value to final variable " + name.identifier());
						}
						if (local.parameterOf != null && this.classes.assignedParameters.add(local.key)) {
							// a text read from it is no longer what the calls pass
							this.classes.changed();
						}
						return call("setq", local.symbol(), value);
					}, bindings);
				}
				if (variable instanceof CaptureRef found) {
					return new Place(found.read().type(), found.read().form(), value -> {
						throw error(name.pos(),
								"local variables referenced from an inner class must be final or effectively final");
					}, bindings);
				}
				if (!(variable instanceof FieldRef found)) {
					throw error(name.pos(), "cannot find symbol: variable " + name.identifier());
				}
				Field field = found.field();
				this.facts.variables.put(name, field);
				if (!field.isStatic && !(assigning && this.early && found.object() == THIS)) {
					requireInstance(field, found.object(), name.pos());
				}
				return fieldPlace(field, found.object(), name.pos(), bindings, found.through().self());
			}
			case Select select: {
				JavaClass named = classNamed(select.target());
				if (named != null) {
					Field field = field(named, select.name());
					if (field == null || !field.isStatic) {
						throw error(select.pos(), "cannot find symbol: static variable " + select.name() + " in class "
								+ named.simpleName());
					}
					return fieldPlace(field, null, select.pos(), bindings, null);
				}
				// a field may be given its value ahead of this(..) or super(..)
				Typed object = assigning && this.early && select.target() instanceof This
						? new Typed(THIS, this.currentClass.self()) : expression(select.target(), null);
				if (object.type() instanceof ArrayType && select.name().equals("length")) {
					return new Place(Primitive.INT,
							call("length", JavaForms.vector(object.type(), dereferenced(object.form()))), value -> {
								throw error(select.pos(), "cannot assign a value to final variable length");
							}, bindings);
				}
				if (!(object.type() instanceof ClassType) && !(object.type() instanceof Variable)) {
					throw error(select.pos(), object.type() + " cannot be dereferenced");
				}
				ClassType type = this.types.site(object.type());
				Field field = field(type.declaration(), select.name());
				if (field == null && object.type() instanceof Variable variable) {
					// a type variable with several bounds has the fields of each
					for (JavaType bound : variable.parameter().bounds) {
						ClassType candidate = this.types.site(bound);
						field = field(candidate.declaration(), select.name());
						if (field != null) {
							type = candidate;
							break;
						}
					}
				}
				if (field != null && select.target() instanceof This) {
					this.facts.variables.put(select, field);
				}
				if (field == null) {
					throw type.declaration().intrinsic || isLibrary(type.declaration())
							? unsupported(select.pos(), type.declaration().simpleName() + "." + select.name())
							: error(select.pos(), "cannot find symbol: variable " + select.name() + " in class "
									+ type.declaration().simpleName());
				}
				ClassType seen = field.isStatic ? null : this.types.asSuper(type, field.owner);
				if (assigning && seen != null && JavaTypes.isRaw(seen)
						&& !this.types.erased(field.type).equals(field.type)) {
					unchecked(caret(select),
							"unchecked assignment to variable " + field.name + " as member of raw type " + seen,
							List.of(), List.of());
				}
				return fieldPlace(field, field.isStatic ? null : stabilized(object.form(), stable, bindings),
						select.pos(), bindings, type);
			}
			case Index index: {
				Typed array = expression(index.array(), null);
				if (!(array.type() instanceof ArrayType type)) {
					throw error(index.pos(), "array required, but " + array.type() + " found");
				}
				Typed at = unbox(expression(index.index(), null));
				if (at.type() != Primitive.INT && !at.type().isBelowInt()) {
					throw error(index.index().pos(),
							"incompatible types: " + at.type() + " cannot be converted to int");
				}
				LispVal element = call("aref", stabilized(JavaForms.knownArray(type, array.form()), stable, bindings),
						stabilized(at.form(), stable, bindings));
				return new Place(type.element(), elementRead(type.element(), element),
						value -> call("setf", element, elementStored(type.element(), value)), bindings);
			}
			default:
				throw error(expression.pos(), "unexpected type; required: variable, found: value");
		}
	}

	private LispVal stabilized(LispVal form, boolean stable, List<LispVal> bindings) {
		if (!stable || isSimple(form)) {
			return form;
		}
		LispSymbol temporary = temporary();
		bindings.add(list(temporary, form));
		return temporary;
	}

	private Place fieldPlace(Field field, @Nullable LispVal object, int pos, List<LispVal> bindings,
			@Nullable JavaType site) {
		Typed read = fieldRead(field, object, pos, site);
		Function<LispVal, LispVal> write = value -> {
			// a final field is given its value once, where its class is constructed
			boolean constructed = this.constructing == field.owner && this.constructingStatic == field.isStatic
					&& field.initializer == null && (field.isStatic || object == THIS);
			if (field.form != null || read.constant() != null || (field.isFinal && !constructed)) {
				throw error(pos, "cannot assign a value to final variable " + field.name);
			}
			if (!field.isStatic) {
				if (isVolatile(field)) {
					// the object, then the value, then the store alone ordered
					List<LispVal> held = new ArrayList<>();
					LispVal target = stabilized(Objects.requireNonNull(object), true, held);
					LispVal stored = stabilized(value, true, held);
					return bound(held, ordered(call("setf", list(JavaNames.accessor(field), target), stored)));
				}
				return call("setf", read.form(), value);
			}
			this.effects.writes.add(field);
			LispVal global = JavaNames.global(field);
			// the value first, then the class's initialization, then the store
			LispVal given = lazilyRead(field) ? call("prog1", value, list(JavaNames.initializer(field.owner))) : value;
			if (isVolatile(field)) {
				List<LispVal> held = new ArrayList<>();
				LispVal stored = stabilized(given, true, held);
				return bound(held, ordered(call("setq", global, stored)));
			}
			return call("setq", global, given);
		};
		JavaType stored = field.isStatic || field.form != null ? read.type()
				: this.types.memberType(field.type, site, field.owner);
		return new Place(read.type(), read.form(), write, bindings, stored);
	}

	private static LispVal bound(List<LispVal> bindings, LispVal body) {
		return bindings.isEmpty() ? body : list(cl("let*"), list(bindings), body);
	}

	private Typed assignment(Assign assignment) {
		boolean simple = assignment.operator().equals("=");
		Place place = place(assignment.target(), !simple, simple);
		int since = this.warnings.size();
		Typed value = expression(assignment.value(), place.type());
		Typed read = new Typed(place.read(), place.type());
		LispVal stored;
		if (assignment.operator().equals("=")) {
			stored = assign(value, place.stored(), assignment.pos(), assignment.value(), since);
		}
		else {
			// a compound assignment casts its result to the place's type
			stored = castTo(place.type(), operation(assignment.operator(), read, value, assignment.pos()),
					assignment.pos())
				.form();
		}
		return new Typed(bound(place.bindings(), place.write().apply(stored)), place.type());
	}

	private Typed unary(Unary unary, boolean discard) {
		String operator = unary.operator();
		if (operator.equals("++") || operator.equals("--")) {
			Place place = place(unary.operand(), true, false);
			if (!unbox(new Typed(place.read(), place.type())).type().isNumeric()) {
				throw error(unary.pos(), "bad operand type " + place.type() + " for unary operator '" + operator + "'");
			}
			Typed read = new Typed(place.read(), place.type());
			LispVal next = castTo(place.type(), operation(operator.substring(1), read, constant(1), unary.pos()),
					unary.pos())
				.form();
			LispVal store = place.write().apply(next);
			LispVal form = unary.postfix() && !discard ? call("prog1", place.read(), store) : store;
			return new Typed(bound(place.bindings(), form), place.type());
		}
		Typed operand = unbox(expression(unary.operand(), null));
		JavaType type = operand.type();
		Object constant = operand.constant();
		switch (operator) {
			case "+", "-", "~": {
				if (!type.isNumeric() || (operator.equals("~") && !type.isIntegral())) {
					break;
				}
				Typed promoted = type.isBelowInt() ? widen(operand, Primitive.INT) : operand;
				if (operator.equals("+")) {
					return promoted;
				}
				if (promoted.constant() instanceof Double number) {
					return constant(-number);
				}
				if (promoted.constant() instanceof Float number) {
					return constant(-number);
				}
				if (promoted.constant() != null) {
					Object folded = fold(operator.equals("-") ? "-" : "^",
							operator.equals("-") ? zero(promoted.type()) : minusOne(promoted.type()),
							promoted.constant(), promoted.type());
					if (folded != null) {
						return constant(folded);
					}
				}
				LispVal known = JavaForms.known(promoted.type(), promoted.form());
				return operator.equals("-")
						? new Typed(JavaForms.wrap(promoted.type(), call("-", known)), promoted.type())
						: new Typed(call("lognot", known), promoted.type());
			}
			default:
		}
		throw error(unary.pos(), "bad operand type " + type + " for unary operator '" + operator + "'");
	}

	private static Object zero(JavaType type) {
		return type == Primitive.LONG ? (Object) 0L : (Object) 0;
	}

	private static Object minusOne(JavaType type) {
		return type == Primitive.LONG ? (Object) (-1L) : (Object) (-1);
	}

	private boolean isString(Typed value) {
		return value.type().equals(this.stringType);
	}

	private Typed binary(Binary binary) {
		String operator = binary.operator();
		if ((operator.equals("==") || operator.equals("!=")) && isClass(binary.left()) && isClass(binary.right())) {
			LispVal same = sameClass(binary.left(), binary.right(), true, binary.pos());
			return new Typed(operator.equals("==") ? same : call("not", same), Primitive.BOOLEAN);
		}
		return operation(operator, expression(binary.left(), null), expression(binary.right(), null), binary.pos());
	}

	/**
	 * A binary operator over two lowered operands, which a compound assignment shares.
	 */
	private Typed operation(String operator, Typed left, Typed right, int pos) {
		if (operator.equals("+") && (isString(left) || isString(right))) {
			if (left.type() == Primitive.VOID || right.type() == Primitive.VOID) {
				throw error(pos, "'void' type not allowed here");
			}
			if (left.constant() != null && right.constant() != null) {
				return constantString(String.valueOf(left.constant()) + right.constant());
			}
			LispVal joined = call("%string-concat", stringOf(left, pos), stringOf(right, pos));
			this.nonNull.add(joined);
			// a constant operand joins the other's prefix or suffix
			Affixes before = affixes(left);
			Affixes after = affixes(right);
			Affixes known = new Affixes(left.constant() != null ? before.prefix() + after.prefix() : before.prefix(),
					right.constant() != null ? before.suffix() + after.suffix() : after.suffix());
			if (!known.equals(Affixes.NONE)) {
				this.affixes.put(joined, known);
			}
			return new Typed(joined, this.stringType);
		}
		if (operator.equals("==") || operator.equals("!=")) {
			return equality(operator, left, right, pos);
		}
		Typed a = unbox(left);
		Typed b = unbox(right);
		JavaType lt = a.type();
		JavaType rt = b.type();
		boolean logical = lt == Primitive.BOOLEAN && rt == Primitive.BOOLEAN;
		switch (operator) {
			case "<", ">", "<=", ">=": {
				if (!lt.isNumeric() || !rt.isNumeric()) {
					break;
				}
				JavaType promoted = promote(lt, rt);
				a = widen(a, promoted);
				b = widen(b, promoted);
				Object folded = a.constant() != null && b.constant() != null
						? fold(operator, a.constant(), b.constant(), promoted) : null;
				return folded != null ? constant(folded) : new Typed(
						call(operator, JavaForms.known(promoted, a.form()), JavaForms.known(promoted, b.form())),
						Primitive.BOOLEAN);
			}
			case "&", "|", "^":
				if (logical) {
					if (operator.equals("^")) {
						return new Typed(call("not", call("eq", a.form(), b.form())), Primitive.BOOLEAN);
					}
					// both operands are evaluated: no short circuit
					String connective = operator.equals("&") ? "and" : "or";
					if (isSimple(b.form())) {
						return new Typed(call(connective, a.form(), b.form()), Primitive.BOOLEAN);
					}
					LispSymbol first = temporary();
					LispSymbol second = temporary();
					return new Typed(list(cl("let*"), list(list(first, a.form()), list(second, b.form())),
							call(connective, first, second)), Primitive.BOOLEAN);
				}
				if (!lt.isIntegral() || !rt.isIntegral()) {
					break;
				}
				return arithmetic(operator, a, b, promote(lt, rt));
			case "+", "-", "*", "/", "%":
				if (!lt.isNumeric() || !rt.isNumeric()) {
					break;
				}
				return arithmetic(operator, a, b, promote(lt, rt));
			case "<<", ">>", ">>>": {
				if (!lt.isIntegral() || !rt.isIntegral()) {
					break;
				}
				return shift(operator, lt.isBelowInt() ? widen(a, Primitive.INT) : a, b);
			}
			default:
		}
		throw error(pos,
				"bad operand types for binary operator '" + operator + "': " + left.type() + ", " + right.type());
	}

	private static JavaType promote(JavaType left, JavaType right) {
		if (left == Primitive.DOUBLE || right == Primitive.DOUBLE) {
			return Primitive.DOUBLE;
		}
		if (left == Primitive.FLOAT || right == Primitive.FLOAT) {
			return Primitive.FLOAT;
		}
		return left == Primitive.LONG || right == Primitive.LONG ? Primitive.LONG : Primitive.INT;
	}

	private static Typed arithmetic(String operator, Typed left, Typed right, JavaType type) {
		Typed a = widen(left, type);
		Typed b = widen(right, type);
		if (a.constant() != null && b.constant() != null) {
			Object folded = fold(operator, a.constant(), b.constant(), type);
			if (folded != null) {
				return constant(folded);
			}
		}
		LispVal x = JavaForms.known(type, a.form());
		LispVal y = JavaForms.known(type, b.form());
		if (type == Primitive.FLOAT) {
			// the binary64 result of two binary32 operands, rounded once more, is the
			// binary32 result: the double holds every bit the rounding reads
			return new Typed(call("%single-float",
					operator.equals("%") ? runtimeCall("double-rem", x, y) : call(operator, x, y)), type);
		}
		LispVal form = switch (operator) {
			case "+", "-", "*" -> JavaForms.wrap(type, call(operator, x, y));
			case "/" -> type == Primitive.DOUBLE ? call("/", x, y) : JavaForms.wrap(type, quotient(type, x, y));
			case "%" -> type == Primitive.DOUBLE ? runtimeCall("double-rem", x, y) : call("rem", x, y);
			case "&" -> call("logand", x, y);
			case "|" -> call("logior", x, y);
			default -> call("logxor", x, y);
		};
		return new Typed(form, type);
	}

	/**
	 * {@code (truncate x y)} of two integral operands: the quotient of two {@code int}s
	 * is one of {@code [-2^31, 2^31]} -- {@code MIN_VALUE / -1} the one past the type,
	 * which the wrap around takes back -- and asserted so; the quotient of two
	 * {@code long}s may leave i64 and is not.
	 */
	private static LispVal quotient(JavaType type, LispVal x, LispVal y) {
		LispVal quotient = call("truncate", x, y);
		return type == Primitive.LONG ? quotient
				: list(cl("the"), list(cl("integer"), integer(-(1L << 31)), integer(1L << 31)), quotient);
	}

	private static Typed shift(String operator, Typed given, Typed count) {
		JavaType type = given.type();
		Typed value = new Typed(JavaForms.known(type, given.form()), type, given.constant());
		int mask = type == Primitive.LONG ? 63 : 31;
		Object by = count.constant() == null ? null : number(count.constant());
		if (value.constant() != null && by != null) {
			Object folded = fold(operator, value.constant(), ((Number) by).intValue(), type);
			if (folded != null) {
				return constant(folded);
			}
		}
		LispVal distance = by != null ? integer(((Number) by).longValue() & mask)
				: call("logand", JavaForms.known(count.type(), count.form()), integer(mask));
		LispVal back = by != null ? integer(-(((Number) by).longValue() & mask)) : call("-", distance);
		if (operator.equals(">>>") && by != null) {
			// A shift by a known count keeps the bits below the sign's copies: no bignum.
			long d = ((Number) by).longValue() & mask;
			return new Typed(d == 0 ? value.form()
					: call("logand", call("ash", value.form(), integer(-d)), integer((1L << (mask + 1 - d)) - 1)),
					type);
		}
		LispVal form = switch (operator) {
			case "<<" -> JavaForms.wrap(type, call("ash", value.form(), distance));
			case ">>" -> call("ash", value.form(), back);
			default -> JavaForms.wrap(type,
					call("ash", call("logand", value.form(),
							type == Primitive.LONG
									? integer(java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE))
									: integer(0xFFFFFFFFL)),
							back));
		};
		return new Typed(form, type);
	}

	/**
	 * A binary operator over two constants of one promoted type, as the language computes
	 * it, or {@code null} where it raises when the program runs.
	 */
	private static @Nullable Object fold(String operator, Object left, Object right, JavaType type) {
		Number a = (Number) number(left);
		Number b = (Number) number(right);
		if (type == Primitive.FLOAT) {
			float x = a.floatValue();
			float y = b.floatValue();
			return switch (operator) {
				case "+" -> x + y;
				case "-" -> x - y;
				case "*" -> x * y;
				case "<" -> x < y;
				case ">" -> x > y;
				case "<=" -> x <= y;
				case ">=" -> x >= y;
				case "==" -> x == y;
				case "!=" -> x != y;
				default -> null;
			};
		}
		if (type == Primitive.DOUBLE) {
			double x = a.doubleValue();
			double y = b.doubleValue();
			return switch (operator) {
				case "+" -> x + y;
				case "-" -> x - y;
				case "*" -> x * y;
				case "<" -> x < y;
				case ">" -> x > y;
				case "<=" -> x <= y;
				case ">=" -> x >= y;
				case "==" -> x == y;
				case "!=" -> x != y;
				// a division is the run time's: it is not exact there yet
				default -> null;
			};
		}
		if (type == Primitive.LONG) {
			long x = a.longValue();
			long y = b.longValue();
			return switch (operator) {
				case "+" -> x + y;
				case "-" -> x - y;
				case "*" -> x * y;
				case "/" -> y == 0 ? null : (Object) (x / y);
				case "%" -> y == 0 ? null : (Object) (x % y);
				case "&" -> x & y;
				case "|" -> x | y;
				case "^" -> x ^ y;
				case "<<" -> x << y;
				case ">>" -> x >> y;
				case ">>>" -> x >>> y;
				case "<" -> x < y;
				case ">" -> x > y;
				case "<=" -> x <= y;
				case ">=" -> x >= y;
				case "==" -> x == y;
				case "!=" -> x != y;
				default -> null;
			};
		}
		int x = a.intValue();
		int y = b.intValue();
		return switch (operator) {
			case "+" -> x + y;
			case "-" -> x - y;
			case "*" -> x * y;
			case "/" -> y == 0 ? null : (Object) (x / y);
			case "%" -> y == 0 ? null : (Object) (x % y);
			case "&" -> x & y;
			case "|" -> x | y;
			case "^" -> x ^ y;
			case "<<" -> x << y;
			case ">>" -> x >> y;
			case ">>>" -> x >>> y;
			case "<" -> x < y;
			case ">" -> x > y;
			case "<=" -> x <= y;
			case ">=" -> x >= y;
			case "==" -> x == y;
			case "!=" -> x != y;
			default -> null;
		};
	}

	private Typed equality(String operator, Typed left, Typed right, int pos) {
		boolean leftBox = JavaIntrinsics.unboxed(left.type()) != null;
		boolean rightBox = JavaIntrinsics.unboxed(right.type()) != null;
		LispVal test;
		if (left.type().isReference() && right.type().isReference()) {
			if (isString(left) && isString(right) && !this.classes.identicalStrings) {
				// two constants of one text are one object: every constant of such a
				// program is a global, and the program is lowered again
				this.classes.identicalStrings = true;
				this.classes.changed();
			}
			if (left.constant() instanceof String a && right.constant() instanceof String b) {
				return constant(a.equals(b) == operator.equals("=="));
			}
			JavaType a = this.types.erased(this.types.upward(left.type()));
			JavaType b = this.types.erased(this.types.upward(right.type()));
			if (!isOpen(left.type()) && !isOpen(right.type())
					&& (!isSubtype(a, b) && !isSubtype(b, a) && !(isInterface(a) || isInterface(b))
							|| distinctArguments(left.type(), right.type()))) {
				throw error(pos, "incomparable types: " + left.type() + " and " + right.type());
			}
			// a box is its value, so two boxes of one value are one object: what the
			// language
			// promises of the small values, and allows of every other
			String identity = !leftBox || !rightBox ? "eq"
					: JavaForms.boxTag(Objects.requireNonNull(JavaIntrinsics.unboxed(left.type()))) != null ? "equal"
							: "eql";
			test = left.type() == JavaType.Null.NULL ? call("null", right.form()) : right.type() == JavaType.Null.NULL
					? call("null", left.form()) : call(identity, left.form(), right.form());
		}
		else {
			Typed a = unbox(left);
			Typed b = unbox(right);
			if (a.type().isNumeric() && b.type().isNumeric()) {
				JavaType promoted = promote(a.type(), b.type());
				a = widen(a, promoted);
				b = widen(b, promoted);
				if (a.constant() != null && b.constant() != null) {
					return constant(Objects.requireNonNull(fold(operator, a.constant(), b.constant(), promoted)));
				}
				test = call("=", JavaForms.known(promoted, a.form()), JavaForms.known(promoted, b.form()));
			}
			else if (a.type() == Primitive.BOOLEAN && b.type() == Primitive.BOOLEAN) {
				test = call("eq", a.form(), b.form());
			}
			else {
				throw error(pos, "incomparable types: " + left.type() + " and " + right.type());
			}
		}
		return new Typed(operator.equals("==") ? test : call("not", test), Primitive.BOOLEAN);
	}

	private static boolean isInterface(JavaType type) {
		return type instanceof ClassType declared && declared.declaration().isInterface;
	}

	/**
	 * Whether two types are one class given type arguments no value has both of: at some
	 * place each has a type that is neither a wildcard nor a type variable, and the two
	 * differ ({@code Class<String>} and {@code Class<Integer>}).
	 */
	private static boolean distinctArguments(JavaType left, JavaType right) {
		if (!(left instanceof ClassType a) || !(right instanceof ClassType b) || a.declaration() != b.declaration()
				|| a.arguments().size() != b.arguments().size()) {
			return false;
		}
		for (int i = 0; i < a.arguments().size(); i++) {
			JavaType x = a.arguments().get(i);
			JavaType y = b.arguments().get(i);
			if (!(x instanceof Wildcard) && !(x instanceof Variable) && !(y instanceof Wildcard)
					&& !(y instanceof Variable) && !x.equals(y)) {
				return true;
			}
		}
		return false;
	}

	private Typed conditional(Conditional conditional, @Nullable JavaType expected) {
		Boolean answer = answered(conditional.condition());
		if (answer != null) {
			return expression(answer ? conditional.whenTrue() : conditional.whenFalse(), expected);
		}
		Flow flow = test(conditional.condition());
		Typed test = flow.value();
		Typed a = within(flow.whenTrue(), () -> expression(conditional.whenTrue(), expected));
		Typed b = within(flow.whenFalse(), () -> expression(conditional.whenFalse(), expected));
		JavaType type = conditionalType(a, b, expected, conditional.pos());
		LispVal whenTrue = assign(a, type, conditional.whenTrue().pos());
		LispVal whenFalse = assign(b, type, conditional.whenFalse().pos());
		if (test.constant() instanceof Boolean truth && a.constant() != null && b.constant() != null) {
			Typed chosen = truth ? a : b;
			Object constant = Objects.requireNonNull(chosen.constant());
			if (chosen.type().equals(type)) {
				return new Typed(truth ? whenTrue : whenFalse, type, constant);
			}
		}
		LispVal form = branch(test.form(), whenTrue, whenFalse);
		if (type.equals(this.stringType) && this.nonNull.contains(whenTrue) && this.nonNull.contains(whenFalse)) {
			this.nonNull.add(form);
		}
		return new Typed(form, type);
	}

	private JavaType conditionalType(Typed a, Typed b, @Nullable JavaType expected, int pos) {
		JavaType at = a.type();
		JavaType bt = b.type();
		if (at.equals(bt)) {
			return at;
		}
		JavaType ap = unbox(a).type();
		JavaType bp = unbox(b).type();
		if (ap.isNumeric() && bp.isNumeric()) {
			// a byte, a short or a char beside a constant it holds, or a byte beside a
			// short
			if (ap.isBelowInt() && conversion(unbox(b), ap, false, true) != null) {
				return ap;
			}
			if (bp.isBelowInt() && conversion(unbox(a), bp, false, true) != null) {
				return bp;
			}
			return ap == bp ? ap : promote(ap, bp);
		}
		if (ap == Primitive.BOOLEAN && bp == Primitive.BOOLEAN) {
			return Primitive.BOOLEAN;
		}
		if (at == JavaType.Null.NULL && bt.isPrimitive()) {
			return JavaIntrinsics.boxed((Primitive) bt, this.classes);
		}
		if (bt == JavaType.Null.NULL && at.isPrimitive()) {
			return JavaIntrinsics.boxed((Primitive) at, this.classes);
		}
		if (expected != null && expected.isReference() && conversion(a, expected, true, false) != null
				&& conversion(b, expected, true, false) != null) {
			return expected;
		}
		JavaType ar = at.isPrimitive() ? JavaIntrinsics.boxed((Primitive) at, this.classes) : at;
		JavaType br = bt.isPrimitive() ? JavaIntrinsics.boxed((Primitive) bt, this.classes) : bt;
		if (ar.isReference() && br.isReference()) {
			if (isSubtype(ar, br)) {
				return br;
			}
			if (isSubtype(br, ar)) {
				return ar;
			}
			return this.types.lub(ar, br);
		}
		throw error(pos, "incompatible types in a conditional: " + at + " and " + bt);
	}

	/**
	 * A value cast to a type: every conversion a cast allows, checked where it can fail.
	 */
	private Typed castTo(JavaType to, Typed value, int pos) {
		JavaType from = value.type();
		if (from.equals(to)) {
			return value;
		}
		if (to instanceof Variable variable && variable.parameter().intersection) {
			// to each of its types in turn, as a compiler's checks are made
			Typed cast = value;
			for (JavaType bound : variable.parameter().bounds) {
				cast = castTo(bound, cast, pos);
			}
			return new Typed(cast.form(), to);
		}
		if (to.isPrimitive()) {
			Typed operand = unbox(value);
			from = operand.type();
			if (from == to) {
				return operand;
			}
			if (from.isReference()) {
				// through the box: a reference is cast to it, then unboxed
				ClassType box = JavaIntrinsics.boxed((Primitive) to, this.classes);
				if (!isSubtype(box, this.types.erased(this.types.upward(from)))) {
					throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
				}
				return unbox(castTo(box, operand, pos));
			}
			if (!from.isNumeric() || !to.isNumeric()) {
				throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
			}
			if (widens(from, to)) {
				return widen(operand, to);
			}
			if (operand.constant() != null) {
				return constant(narrowed(operand.constant(), (Primitive) to));
			}
			return new Typed(narrowing(operand.form(), from, (Primitive) to), to);
		}
		LispVal converted = conversion(value, to, true, false);
		if (converted != null) {
			return new Typed(converted, to);
		}
		if (!from.isReference()) {
			throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
		}
		if (isSubtype(this.types.erased(this.types.upward(from)), this.types.erased(to))) {
			// only the type arguments differ, which no program that runs has
			return new Typed(value.form(), to);
		}
		LispSymbol checked = temporary();
		LispVal test = instanceTest(checked, from, to, pos, "a cast");
		if (test == T) {
			return new Typed(value.form(), to);
		}
		LispVal failure = raise(castFailure(checked, to));
		return new Typed(JavaForms.let(checked, value.form(),
				List.of(call("if", call("or", call("null", checked), test), checked, failure))), to);
	}

	/** A {@code ClassCastException} with no message. */
	private LispVal castFailure() {
		Method failed = constructors(this.classes.library("java.lang.ClassCastException")).stream()
			.filter(constructor -> constructor.parameters.isEmpty())
			.findFirst()
			.orElseThrow();
		called(failed);
		makes(failed.owner);
		return list(JavaNames.creator(failed));
	}

	/**
	 * {@code newUpdater(Owner.class, "name")} of a field updater, and of a reference's
	 * {@code (Owner.class, Value.class, "name")}: the class and the name are written
	 * where it is called, so the field is found here and the updater made holds its name
	 * -- its accessor's symbol --, which the program's three functions choose the field
	 * by ({@link #fieldFunction}). What a JDK finds wrong with the field when the call
	 * runs -- no such field of the class itself, another type, not volatile, static -- is
	 * thrown where the call runs, in the order and the words a JDK 25 answered.
	 */
	private Typed fieldUpdater(Call call, JavaClass updater) {
		int pos = call.pos();
		boolean reference = updater.name.endsWith("AtomicReferenceFieldUpdater");
		List<Expression> arguments = call.arguments();
		int classes = reference ? 2 : 1;
		List<JavaSyntax.ClassLiteral> literals = new ArrayList<>();
		for (Expression argument : arguments.subList(0, Math.min(classes, arguments.size()))) {
			JavaSyntax.ClassLiteral literal = classLiteral(argument);
			if (literal != null) {
				literals.add(literal);
			}
		}
		if (arguments.size() != classes + 1 || literals.size() != classes) {
			throw unsupported(pos, "newUpdater but of class literals and a constant name");
		}
		if (!(type(literals.get(0).type()) instanceof ClassType holder)) {
			throw unsupported(pos, "newUpdater of a type that is no class");
		}
		JavaType value = reference ? type(literals.get(1).type()) : null;
		if (!(expression(arguments.get(classes), this.stringType).constant() instanceof String name)) {
			throw unsupported(pos, "newUpdater of a field named by no constant");
		}
		JavaClass owner = holder.declaration();
		List<JavaType> typed = new ArrayList<>(List.of(new ClassType(owner, List.of())));
		if (value != null) {
			typed.add(value instanceof Primitive primitive ? JavaIntrinsics.boxed(primitive, this.classes) : value);
		}
		ClassType type = new ClassType(updater, List.copyOf(typed));
		Field field = owner.fields.stream().filter(declared -> declared.name.equals(name)).findFirst().orElse(null);
		String fields = "java.util.concurrent.atomic.Fields";
		LispVal refusal = null;
		if (field == null) {
			refusal = library(fields, "missing", List.of(string(name)));
		}
		else if (value != null && !this.types.erased(field.type).equals(this.types.erased(value))) {
			refusal = library(fields, "mismatched", List.of());
		}
		else {
			JavaType wanted = reference ? null
					: updater.name.endsWith("LongFieldUpdater") ? Primitive.LONG : Primitive.INT;
			String message = value instanceof Primitive ? "Must be reference type"
					: wanted != null && field.type != wanted
							? "Must be " + (wanted == Primitive.INT ? "integer" : "long") + " type"
							: !isVolatile(field) ? "Must be volatile type" : null;
			if (message != null || field.isStatic) {
				refusal = library(fields, "refused", List.of(message == null ? NIL : string(message)));
			}
		}
		if (refusal != null) {
			return new Typed(located(refusal, pos), type);
		}
		Field found = Objects.requireNonNull(field);
		try {
			checkAccess(found.isPrivate, found.declaration, found.owner, found.name, pos);
		}
		catch (LispReadException e) {
			throw unsupported(pos, "newUpdater of a field the class calling it cannot read");
		}
		if (found.type.equals(this.stringType) && !this.classes.identicalStrings) {
			// a swap compares two strings as == does
			this.classes.identicalStrings = true;
			this.classes.changed();
		}
		this.updated.add(found);
		return new Typed(located(library(updater.name, "of", List.of(quote(JavaNames.accessor(found)))), pos), type);
	}

	/**
	 * The class literal an argument is, seen through the casts around it
	 * ({@code (Class<@Nullable V>) V.class}), each cast typed as anywhere else;
	 * {@code null} for any other argument.
	 */
	private JavaSyntax.@Nullable ClassLiteral classLiteral(Expression argument) {
		JavaSyntax.ClassLiteral literal = literalInside(argument);
		if (literal != null && argument instanceof Cast cast) {
			cast(cast);
		}
		return literal;
	}

	/**
	 * The class literal an expression is, seen through the casts around it, or
	 * {@code null}.
	 */
	private static JavaSyntax.@Nullable ClassLiteral literalInside(Expression argument) {
		Expression inside = argument;
		while (inside instanceof Cast cast) {
			inside = cast.operand();
		}
		return inside instanceof JavaSyntax.ClassLiteral literal ? literal : null;
	}

	/**
	 * The fields the program's three functions have an arm for: the ones an updater was
	 * seen made for. A session's buffer does not lower the statements of the buffers
	 * before it, one of which may have made an updater: there, every field of the program
	 * an updater can be made for.
	 */
	private List<Field> updatedFields() {
		if (this.session == null) {
			return List.copyOf(this.updated);
		}
		List<Field> fields = new ArrayList<>();
		for (JavaClass declaration : this.classes.all()) {
			if (declaration.intrinsic || isPartial(declaration)) {
				continue;
			}
			for (Field field : declaration.fields) {
				if (!field.isStatic && isVolatile(field)
						&& (field.type == Primitive.INT || field.type == Primitive.LONG || field.type.isReference())) {
					fields.add(field);
				}
			}
		}
		return fields;
	}

	@Override
	public LispVal updated(String operation, List<LispVal> arguments) {
		LispSymbol function = switch (operation) {
			case "get" -> FIELD_GET;
			case "set" -> FIELD_SET;
			default -> FIELD_SWAP;
		};
		this.generated.add(function);
		return form(function, arguments);
	}

	/**
	 * The function reading, writing or swapping the field of a name
	 * ({@link #fieldUpdater}): a {@code case} over the fields the program has an updater
	 * for ({@link #updatedFields}). An object that is none of the field's class,
	 * {@code null} too, is a {@code ClassCastException}, and so is a value that is none
	 * of a reference field's type -- what a program reaches through a raw type. A
	 * volatile access is one at a time among the threads ({@link #ordered}), and a swap
	 * is one access: it is atomic beside every plain read and write of the field.
	 */
	private LispVal fieldFunction(LispSymbol name) {
		LispSymbol number = new LispSymbol("%n");
		LispSymbol expected = new LispSymbol("%a0");
		LispSymbol given = new LispSymbol("%a1");
		List<LispVal> parameters = new ArrayList<>(List.of(number, RECEIVER));
		if (name == FIELD_SWAP) {
			parameters.add(expected);
		}
		if (name != FIELD_GET) {
			parameters.add(given);
		}
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("case"));
		arms.add(number);
		for (Field field : updatedFields()) {
			LispVal place = list(JavaNames.accessor(field), RECEIVER);
			LispVal store = call("setf", place, given);
			LispVal access;
			if (name == FIELD_GET) {
				access = place;
			}
			else if (name == FIELD_SET) {
				access = store;
			}
			else {
				Primitive box = JavaIntrinsics.unboxed(field.type);
				String identity = field.type.isPrimitive() || (box != null && JavaForms.boxTag(box) == null) ? "eql"
						: box != null ? "equal" : "eq";
				access = call("if", call(identity, place, expected), call("progn", store, T), NIL);
			}
			LispVal test = list(JavaNames.predicate(field.owner), RECEIVER);
			JavaType held = this.types.erased(field.type);
			if (name != FIELD_GET && held instanceof ClassType type && !held.equals(this.objectType)
					&& (!type.declaration().intrinsic || JavaIntrinsics.predicate(type.declaration()) != null)) {
				test = call("and", test,
						call("or", call("null", given), instanceTest(given, this.objectType, held, 0, "a cast")));
			}
			arms.add(list(JavaNames.accessor(field), call("if", test, ordered(access), raise(castFailure()))));
		}
		return list(cl("defun"), name, list(parameters), list(arms));
	}

	/**
	 * The {@code ClassCastException} of a value that is no {@code to}: the message names
	 * the two classes as a JDK's starts, without what it says of their modules and class
	 * loaders; a cast to an array type has none.
	 */
	private LispVal castFailure(LispSymbol value, JavaType to) {
		String target = to instanceof ClassType type ? type.declaration().binaryName
				: to instanceof ArrayType array ? descriptor(array) : null;
		if (target == null) {
			return castFailure();
		}
		this.generated.add(CLASS_NAME);
		this.namesOfAny.add(CLASS_NAME);
		LispVal message = call("%string-concat", call("%string-concat", string("class "), list(CLASS_NAME, value)),
				string(" cannot be cast to class " + target));
		// a lambda's class has a name only a JDK makes up: no message
		if (!this.classes.lambdaTargets.isEmpty()) {
			List<LispVal> lambda = new ArrayList<>();
			lambda.add(cl("or"));
			lambda.add(call("functionp", value));
			for (JavaClass tagged : this.classes.taggedLambdas) {
				lambda.add(list(lambdaTest(tagged), value));
			}
			message = call("if", lambda.size() == 2 ? lambda.get(1) : list(lambda), NIL, message);
		}
		return list(creator("java.lang.ClassCastException"), message);
	}

	/**
	 * The call raising the {@code ClassCastException} of a value that is no {@code to},
	 * as {@link #castFailure(LispSymbol, JavaType)} raises it, through one function of
	 * the program taking the value and the class's name: a bridge has one in each arm.
	 */
	private LispVal castRefused(LispSymbol value, JavaType to) {
		String target = to instanceof ClassType type ? type.declaration().binaryName : null;
		if (target == null) {
			return raise(castFailure(value, to));
		}
		LispSymbol refused = new LispSymbol("%v");
		LispSymbol name = new LispSymbol("%t");
		this.generated.add(CLASS_NAME);
		this.namesOfAny.add(CLASS_NAME);
		this.generated.add(CAST_REFUSED);
		LispVal message = call("%string-concat", call("%string-concat", string("class "), list(CLASS_NAME, refused)),
				call("%string-concat", string(" cannot be cast to class "), name));
		if (!this.classes.lambdaTargets.isEmpty()) {
			List<LispVal> lambda = new ArrayList<>();
			lambda.add(cl("or"));
			lambda.add(call("functionp", refused));
			for (JavaClass tagged : this.classes.taggedLambdas) {
				lambda.add(list(lambdaTest(tagged), refused));
			}
			message = call("if", lambda.size() == 2 ? lambda.get(1) : list(lambda), NIL, message);
		}
		this.castRefusal = list(cl("defun"), CAST_REFUSED, list(refused, name),
				raise(list(creator("java.lang.ClassCastException"), message)));
		return list(CAST_REFUSED, value, string(target));
	}

	/**
	 * A constant converted to a primitive type as a cast converts it: a floating value to
	 * an integral type through {@code int} or {@code long}, toward zero and saturating.
	 */
	private static Object narrowed(Object constant, Primitive to) {
		Object value = number(constant);
		if (value instanceof Double || value instanceof Float) {
			double d = ((Number) value).doubleValue();
			return switch (to) {
				case DOUBLE -> d;
				case FLOAT -> (float) d;
				case LONG -> (long) d;
				case INT -> (int) d;
				case SHORT -> (short) (int) d;
				case BYTE -> (byte) (int) d;
				default -> (char) (int) d;
			};
		}
		long n = ((Number) value).longValue();
		return switch (to) {
			case DOUBLE -> (double) n;
			case FLOAT -> (float) n;
			case LONG -> n;
			case INT -> (int) n;
			case SHORT -> (short) n;
			case BYTE -> (byte) n;
			default -> (char) n;
		};
	}

	/**
	 * A narrowing primitive conversion: a floating value to {@code int} or {@code long}
	 * first, then the integer to the low bits of the type.
	 */
	private static LispVal narrowing(LispVal form, JavaType from, Primitive to) {
		if (from.isFloating()) {
			if (to == Primitive.FLOAT) {
				return call("%single-float", form);
			}
			form = runtimeCall(to == Primitive.LONG ? "double-to-long" : "double-to-int", form);
			from = to == Primitive.LONG ? Primitive.LONG : Primitive.INT;
		}
		return switch (to) {
			case INT -> from == Primitive.LONG ? JavaForms.wrap(Primitive.INT, form) : form;
			case CHAR -> call("logand", form, integer(0xFFFF));
			case SHORT, BYTE -> JavaForms.signed(form, to == Primitive.SHORT ? 16 : 8);
			default -> form;
		};
	}

	private Typed cast(Cast cast) {
		JavaType to = type(cast.type());
		Expression operand = cast.operand();
		if (needsTarget(operand) && !(operand instanceof Lambda) && !(operand instanceof JavaSyntax.MethodReference)) {
			// a conditional or a switch is no poly expression in a cast
			Expression first = waiting(operand);
			throw error(first.pos(),
					(first instanceof Lambda ? "lambda expression" : "method reference") + " not expected here");
		}
		if (!cast.also().isEmpty()) {
			// (A & B) x: a test for each type, the class first
			List<JavaType> bounds = new ArrayList<>();
			bounds.add(to);
			for (TypeName written : cast.also()) {
				JavaType bound = type(written);
				if (!(bound instanceof ClassType named) || !named.declaration().isInterface) {
					throw error(written.pos(), "interface expected here");
				}
				bounds.add(bound);
			}
			if (!(to instanceof ClassType) && !(to instanceof Variable)) {
				throw error(cast.type().pos(), "unexpected type; required: class, found: " + to);
			}
			JavaType intersection = JavaTypes.intersection(bounds);
			if (needsTarget(operand)) {
				return intersectionLambda(operand, bounds, intersection);
			}
			to = intersection;
		}
		// a cast is no context a call infers its type arguments from: only a lambda's
		// type is the cast's; a signature polymorphic call returns it
		Call operandCall = this.castOperand;
		JavaType operandType = this.castType;
		if (operand instanceof Call polymorphic
				&& (polymorphic.name().equals("invokeExact") || polymorphic.name().equals("invoke"))) {
			this.castOperand = polymorphic;
			this.castType = to;
		}
		Typed value = expression(operand, needsTarget(operand) ? to : null);
		this.castOperand = operandCall;
		this.castType = operandType;
		if (cast.also().isEmpty() && uncheckedCast(value.type(), to)) {
			unchecked(caret(operand), "unchecked cast", List.of("required: " + to, "found:    " + value.type()),
					List.of(to, value.type()));
		}
		return castTo(to, value, cast.pos());
	}

	/** The first lambda or method reference of an expression that waits for its type. */
	private static Expression waiting(Expression written) {
		return switch (written) {
			case Conditional conditional -> needsTarget(conditional.whenTrue()) ? waiting(conditional.whenTrue())
					: waiting(conditional.whenFalse());
			case JavaSyntax.Switch choice -> waiting(choice.cases()
				.stream()
				.map(JavaSyntax.SwitchCase::value)
				.filter(value -> value != null && needsTarget(value))
				.findFirst()
				.orElseThrow());
			default -> written;
		};
	}

	/**
	 * A lambda or a method reference cast to an intersection of interfaces: made for the
	 * one interface extending them all, whose one abstract method is the function's, so
	 * it is an instance of each.
	 */
	private Typed intersectionLambda(Expression operand, List<JavaType> bounds, JavaType intersection) {
		List<JavaClass> interfaces = new ArrayList<>();
		for (JavaType bound : bounds) {
			if (bound.equals(this.objectType)) {
				continue;
			}
			if (!(bound instanceof ClassType named) || !named.declaration().isInterface) {
				throw error(operand.pos(),
						"incompatible types: bad intersection type target for lambda or method reference");
			}
			interfaces.add(named.declaration());
		}
		JavaClass made = this.classes.intersection(interfaces);
		Method single = interfaces.size() == 1 ? null : functionalMethod(made);
		JavaType functional = null;
		for (JavaType bound : bounds) {
			if (bound instanceof ClassType named && (single == null ? functionalMethod(named.declaration()) != null
					: named.declaration().isSubclassOf(single.owner))) {
				functional = bound;
				break;
			}
		}
		if (functional == null || (interfaces.size() > 1 && single == null)) {
			throw error(operand.pos(), "incompatible types: INT#1 is not a functional interface");
		}
		if (interfaces.size() > 1) {
			this.madeFor.put(operand, made);
		}
		return new Typed(expression(operand, functional).form(), intersection);
	}

	/**
	 * The interface each lambda or method reference cast to an intersection is made for,
	 * when it is no interface a declaration names.
	 */
	private final Map<Expression, JavaClass> madeFor = new IdentityHashMap<>();

	/**
	 * The test that a value of one static type is an instance of another type: a
	 * structure predicate, an interface's test, a library class's own.
	 */
	private LispVal instanceTest(LispVal value, JavaType from, JavaType to, int pos, String what) {
		boolean cast = what.equals("a cast");
		if (to instanceof Variable) {
			// a type variable is no type of the program that runs: its bound is
			if (!cast) {
				throw error(pos, from + " cannot be safely cast to " + to);
			}
			JavaType bound = this.types.erased(to);
			return bound.equals(this.objectType) ? T : instanceTest(value, from, bound, pos, what);
		}
		if (to instanceof ArrayType array) {
			return arrayTest(value, from, array, pos);
		}
		if (!(to instanceof ClassType target)) {
			throw unsupported(pos, what + " to " + to);
		}
		if (target.declaration().absent != null) {
			// a class nobody has, which a library's body names, has no instance: nothing
			// is one, and a cast lets null alone through
			return cast ? NIL : call("progn", value, NIL);
		}
		JavaClass declaration = target.declaration().present();
		if (!target.arguments().isEmpty() && !cast && !isSubtype(this.types.refine(declaration, from), target)) {
			throw error(pos, from + " cannot be safely cast to " + to);
		}
		JavaType source = this.types.erased(this.types.upward(from));
		if (source instanceof ClassType known && !known.declaration().isInterface && !declaration.isInterface
				&& !known.equals(this.objectType) && !declaration.isSubclassOf(known.declaration())) {
			throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
		}
		if (from instanceof ArrayType) {
			throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
		}
		if (declaration.intrinsic) {
			if (target.equals(this.objectType)) {
				return cast ? T : call("not", call("null", value));
			}
			LispSymbol predicate = JavaIntrinsics.predicate(declaration);
			if (predicate == null) {
				throw unsupported(pos, what + " to " + to);
			}
			if (isExtended(declaration)) {
				// its own values, and the classes of Java source under it
				this.interfaceTests.add(declaration);
				return list(JavaNames.interfaceTest(declaration), value);
			}
			return list(predicate, value);
		}
		if (declaration.isInterface) {
			this.interfaceTests.add(declaration);
			return list(JavaNames.interfaceTest(declaration), value);
		}
		return list(JavaNames.predicate(declaration), value);
	}

	private Typed instanceOf(InstanceOf test) {
		Typed operand = expression(test.operand(), null);
		if (!operand.type().isReference()) {
			throw error(test.pos(), "unexpected type; required: reference, found: " + operand.type());
		}
		JavaType to = type(Objects.requireNonNull(test.type()));
		if (operand.type() != JavaType.Null.NULL && isSubtype(operand.type(), to)) {
			return new Typed(call("not", call("null", operand.form())), Primitive.BOOLEAN);
		}
		return new Typed(instanceTest(operand.form(), operand.type(), to, test.pos(), "instanceof"), Primitive.BOOLEAN);
	}

	/** A value of a primitive type as its box; any other as it is. */
	private Typed boxedValue(Typed value) {
		return value.type().isPrimitive() ? box(value) : value;
	}

	/** Records that the program makes a lambda for an interface. */
	private JavaClass lambdaTarget(Descriptor function, Expression written) {
		JavaClass target = this.madeFor.getOrDefault(written, function.target().declaration());
		if (this.classes.lambdaTargets.add(target)) {
			this.classes.changed();
		}
		if (this.session != null) {
			tag(List.of(target));
		}
		return target;
	}

	/**
	 * A lambda as the value of its interface: the function, or a structure holding it
	 * when the program can tell which interface a lambda was made for.
	 */
	private LispVal lambdaValue(JavaClass target, LispVal function) {
		return this.classes.taggedLambdas.contains(target) ? list(lambdaMaker(target), function) : function;
	}

	/**
	 * A lambda expression.
	 * @param expected the functional interface type it is made for
	 * @param inference the inference of the call it is an argument of when the type of
	 * its value is still to be found there: what the body gives is told to it
	 */
	private Typed lambda(Lambda lambda, @Nullable JavaType expected, @Nullable Inference inference) {
		Descriptor function = functional(expected);
		if (function == null) {
			throw error(lambda.pos(), expected == null || isOpen(expected) ? "lambda expression not expected here"
					: "incompatible types: " + expected + " is not a functional interface");
		}
		if (!function.method().typeParameters.isEmpty()) {
			throw error(lambda.pos(), "incompatible types: invalid functional descriptor for lambda expression");
		}
		if (function.parameters().size() != lambda.parameters().size()) {
			throw error(lambda.pos(), "incompatible types: incompatible parameter types in lambda expression");
		}
		JavaClass target = lambdaTarget(function, lambda);
		// what its body may throw; a call it is an argument of says so again once it
		// has found its type arguments
		this.facts.lets.put(lambda, function.thrown());
		this.scopes.push(new Scope(null));
		Initializing savedInitializing = this.initializing;
		this.initializing = null;
		List<LispVal> parameters = new ArrayList<>();
		List<Local> boundParameters = new ArrayList<>();
		for (int i = 0; i < lambda.parameters().size(); i++) {
			Parameter parameter = lambda.parameters().get(i);
			JavaType type = function.parameters().get(i);
			if (parameter.type() != null) {
				JavaType declared = type(parameter.type());
				if (!this.types.sameType(declared, type)) {
					throw error(parameter.pos(),
							"incompatible types: incompatible parameter types in lambda expression");
				}
				type = declared;
			}
			Local bound = bind(parameter.name(), this.types.ground(type), parameter.pos(), parameter);
			parameters.add(bound.symbol());
			boundParameters.add(bound);
		}
		JavaType wanted = function.returnType();
		boolean inferring = inference != null && JavaTypes.mentions(wanted, inference.variables);
		JavaType savedReturn = this.returnType;
		List<JavaType> savedReturns = this.returns;
		boolean savedLambda = this.lambdaBody;
		String savedName = this.methodName;
		List<JavaType> returned = new ArrayList<>();
		this.returnType = inferring ? this.objectType : wanted;
		this.returns = inferring ? returned : null;
		this.lambdaBody = true;
		this.methodName = this.unit.tree().lambdaNames().get(lambda.pos());
		LispVal body;
		Expression written = lambda.expression();
		if (wanted == Primitive.VOID && written != null && !fits(lambda, true)) {
			throw error(written.pos(),
					"incompatible types: lambda body is not compatible with a void functional interface");
		}
		if (wanted != Primitive.VOID && !inferring && written == null
				&& JavaFlow.completes(Objects.requireNonNull(lambda.block()), this.facts)) {
			throw error(lambda.pos(), "incompatible types: bad return type in lambda expression");
		}
		if (written != null) {
			Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
			this.targets.clear();
			Deque<Scope> around = this.scopes;
			int depth = around.size();
			try {
				body = progn(patterned(() -> {
					if (inferring) {
						Typed value = expression(written, null);
						if (value.type() == Primitive.VOID) {
							return List.of(value.form());
						}
						Typed boxed = boxedValue(value);
						returned.add(boxed.type());
						return List.of(boxed.form());
					}
					Typed value = expression(written, wanted, wanted == Primitive.VOID);
					return List.of(wanted == Primitive.VOID ? value.form() : returned(value, wanted, written.pos()));
				}));
			}
			catch (LispReadException | JavaClass.Absent ex) {
				// a library's lambda throws where it is called, as a statement would
				LispVal thrown = deferred(ex, written.pos());
				if (thrown == null) {
					throw ex;
				}
				this.scopes = around;
				while (this.scopes.size() > depth) {
					this.scopes.pop();
				}
				body = located(thrown, written.pos());
			}
			this.targets.addAll(savedTargets);
		}
		else {
			body = functionBody(Objects.requireNonNull(lambda.block()));
		}
		this.returnType = savedReturn;
		this.returns = savedReturns;
		this.lambdaBody = savedLambda;
		this.methodName = savedName;
		this.initializing = savedInitializing;
		this.scopes.pop();
		if (inference != null && inferring) {
			JavaType shared = null;
			for (JavaType type : returned) {
				shared = shared == null ? type : this.types.lub(shared, type);
			}
			if (shared != null) {
				inference.lowerBound(wanted, shared);
			}
		}
		return new Typed(
				lambdaValue(target,
						madeFor(target,
								form(cl("lambda"),
										prepend(list(parameters), declaring(boundParameters, List.of(body)))))),
				Objects.requireNonNull(expected));
	}

	/** A lambda's form, told the interface it was made for. */
	private LispVal madeFor(JavaClass target, LispVal lambda) {
		this.resumable.lambdas.put(lambda, target);
		return lambda;
	}

	/** A call through a function value, told the interface it is called through. */
	private LispVal calledThrough(JavaClass receiver, boolean exact, LispVal funcall) {
		this.resumable.calls.put(funcall, new JavaResumable.Called(receiver, exact));
		return funcall;
	}

	/** A value as the function being lowered returns it. */
	private LispVal returned(Typed value, JavaType wanted, int pos) {
		if (!this.lambdaBody) {
			return assign(value, wanted, pos);
		}
		LispVal form = conversion(value, wanted, true, true);
		if (form == null) {
			throw error(pos, "incompatible types: bad return type in lambda expression");
		}
		return form;
	}

	/**
	 * A method reference: the lambda calling the method with the parameters its interface
	 * gives it. {@code Type::name} is a static method of all of them, or a method of the
	 * first on the rest; {@code expression::name} a method of the expression's value,
	 * which is evaluated once, where the reference stands.
	 */
	private Typed methodReference(JavaSyntax.MethodReference reference, @Nullable JavaType expected,
			@Nullable Inference inference) {
		int pos = reference.pos();
		Descriptor function = functional(expected);
		if (function == null) {
			throw error(pos, expected == null || isOpen(expected) ? "method reference not expected here"
					: "incompatible types: " + expected + " is not a functional interface");
		}
		JavaClass target = lambdaTarget(function, reference);
		JavaType wanted = function.returnType();
		boolean inferring = inference != null && JavaTypes.mentions(wanted, inference.variables);
		boolean discard = wanted == Primitive.VOID;
		JavaType hint = inferring || discard ? null : wanted;
		List<LispVal> parameters = new ArrayList<>();
		List<Expression> arguments = new ArrayList<>();
		for (JavaType type : function.parameters()) {
			LispSymbol parameter = temporary();
			parameters.add(parameter);
			arguments.add(new JavaSyntax.Lowered(pos, new Typed(parameter, this.types.ground(type))));
		}
		List<LispVal> bindings = new ArrayList<>();
		Typed value;
		List<JavaType> around = this.referenced;
		List<JavaType> thrown = new ArrayList<>();
		this.referenced = thrown;
		try {
			value = referenced(reference, function, arguments, parameters, bindings, hint, discard);
		}
		finally {
			this.referenced = around;
		}
		this.referenceThrows.put(reference, List.copyOf(thrown));
		if (inference == null) {
			thrownTypes(reference, function.thrown());
		}
		return referenceFunction(reference, function, target, value, parameters, bindings, expected, inference);
	}

	/** The call a method reference stands for, on the parameters its function takes. */
	private Typed referenced(JavaSyntax.MethodReference reference, Descriptor function, List<Expression> arguments,
			List<LispVal> parameters, List<LispVal> bindings, @Nullable JavaType hint, boolean discard) {
		int pos = reference.pos();
		Typed value;
		Expression written = reference.target();
		List<TypeName> typeArguments = reference.typeArguments();
		List<JavaType> thrown = Objects.requireNonNull(this.referenced);
		if (reference.name().equals("new")) {
			value = constructorReference(reference, arguments, hint);
		}
		else if (reference.onSuper()) {
			value = invocation(new Call(pos, null, reference.name(), arguments, true, typeArguments), hint, discard);
		}
		else if (written == null) {
			// List<String>::size, int[]::clone: a method of the first parameter, seen as
			// the type written
			JavaType seen = type(Objects.requireNonNull(reference.type()));
			JavaType taken = arguments.isEmpty() ? null : function.parameters().get(0);
			if (taken == null || !(isOpen(taken)
					|| (seen instanceof ClassType named ? this.types.asSuper(taken, named.declaration()) != null
							: seen instanceof ArrayType && isSubtype(taken, seen)))) {
				throw error(pos, "invalid method reference: cannot find symbol: method " + reference.name());
			}
			LispVal first = parameters.get(0);
			try {
				value = invocation(new Call(pos, new JavaSyntax.Lowered(pos, new Typed(first, seen)), reference.name(),
						arguments.subList(1, arguments.size()), false, typeArguments), hint, discard);
			}
			catch (LispReadException asInstance) {
				throw error(pos, "invalid method reference: " + asInstance.reason());
			}
		}
		else if (written instanceof JavaSyntax.ClassLiteral literal && arguments.size() == 1
				&& (reference.name().equals("isInstance") || reference.name().equals("cast"))) {
			// no class object is made: Name.class::isInstance is the test of an object
			// and Name.class::cast the cast of one
			Expression object = new JavaSyntax.Lowered(pos, new Typed(parameters.get(0), this.objectType));
			value = expression(
					reference.name().equals("cast") ? new JavaSyntax.Cast(pos, literal.type(), object, List.of())
							: new JavaSyntax.InstanceOf(pos, object, literal.type(), null),
					hint);
		}
		else if (classNamed(written) instanceof JavaClass named) {
			Typed found;
			try {
				found = invocation(new Call(pos, written, reference.name(), arguments, false, typeArguments), hint,
						discard);
			}
			catch (LispReadException asStatic) {
				if (arguments.isEmpty() || !isSubtype(function.parameters().get(0), new ClassType(named, List.of()))) {
					throw error(pos, "invalid method reference: " + asStatic.reason());
				}
				thrown.clear();
				try {
					found = invocation(new Call(pos, arguments.get(0), reference.name(),
							arguments.subList(1, arguments.size()), false, typeArguments), hint, discard);
				}
				catch (LispReadException asInstance) {
					throw error(pos, "invalid method reference: " + asInstance.reason());
				}
			}
			value = found;
		}
		else {
			Typed direct = directReceiver(written, reference.name(), discard);
			Typed receiver = direct != null ? direct : expression(written, null);
			if (!isSimple(receiver.form())) {
				LispSymbol held = temporary();
				bindings.add(list(held, receiver.form()));
				receiver = new Typed(held, receiver.type());
			}
			// what the receiver's own expression throws is thrown where the reference
			// stands, not by the function
			thrown.clear();
			value = invocation(new Call(pos, new JavaSyntax.Lowered(written.pos(), receiver), reference.name(),
					arguments, false, typeArguments), hint, discard);
		}
		return value;
	}

	/** The function a method reference is: its call's value as its interface gives it. */
	private Typed referenceFunction(JavaSyntax.MethodReference reference, Descriptor function, JavaClass target,
			Typed value, List<LispVal> parameters, List<LispVal> bindings, @Nullable JavaType expected,
			@Nullable Inference inference) {
		int pos = reference.pos();
		JavaType wanted = function.returnType();
		boolean inferring = inference != null && JavaTypes.mentions(wanted, inference.variables);
		boolean discard = wanted == Primitive.VOID;
		LispVal body;
		if (discard) {
			body = value.form();
		}
		else if (value.type() == Primitive.VOID) {
			throw error(pos, "incompatible types: bad return type in method reference");
		}
		else if (inference != null && inferring) {
			Typed boxed = boxedValue(value);
			inference.lowerBound(wanted, boxed.type());
			body = boxed.form();
		}
		else {
			body = conversion(value, wanted, true, false);
			if (body == null) {
				throw error(pos, "incompatible types: bad return type in method reference: " + value.type()
						+ " cannot be converted to " + wanted);
			}
		}
		return new Typed(
				bound(bindings, lambdaValue(target, madeFor(target, list(cl("lambda"), list(parameters), body)))),
				Objects.requireNonNull(expected));
	}

	/**
	 * The type {@code outer.new Inner<..>(..)} makes: the arguments written (its own
	 * parameters for {@code <>}), then those the outer instance's type gives the classes
	 * around; raw through a raw type.
	 */
	private ClassType innerType(JavaClass member, ClassType around, TypeName written) {
		List<JavaType> arguments = new ArrayList<>();
		if (!written.arguments().isEmpty()) {
			if (member.typeParameters.size() != written.arguments().size()) {
				throw error(written.pos(),
						member.typeParameters.isEmpty() ? "type " + member.simpleName() + " does not take parameters"
								: "wrong number of type arguments; required " + member.typeParameters.size());
			}
			for (TypeName argument : written.arguments()) {
				arguments.add(type(argument));
			}
		}
		int own = member.typeParameters.size();
		if (member.allTypeParameters().size() == own) {
			return new ClassType(member, List.copyOf(arguments));
		}
		ClassType seen = this.types.asSuper(this.types.capture(around), Objects.requireNonNull(member.outer));
		if (seen == null || seen.arguments().isEmpty() || (own > 0 && arguments.isEmpty() && !written.diamond())) {
			return new ClassType(member, List.of());
		}
		if (written.diamond()) {
			// its own parameters, which the creation finds
			arguments.addAll(diamondArguments(member));
		}
		arguments.addAll(seen.arguments());
		return new ClassType(member, List.copyOf(arguments));
	}

	/**
	 * What a class's own type parameters are in the type an instance creation with
	 * {@code <>} makes until the creation finds them: types of their own, bounded as the
	 * parameters are. The class's own parameters would be the types its body names too,
	 * and a creation of the class inside it ({@code new Box<>(List.of(held))} in
	 * {@code Box<T>}) finds an argument made of one.
	 */
	private static List<JavaType> diamondArguments(JavaClass made) {
		Map<TypeParameter, JavaType> found = new LinkedHashMap<>();
		List<TypeParameter> own = new ArrayList<>();
		for (TypeParameter declared : made.typeParameters) {
			TypeParameter one = new TypeParameter(declared.name, false, false);
			one.owner = declared.owner;
			own.add(one);
			found.put(declared, new Variable(one));
		}
		for (int i = 0; i < own.size(); i++) {
			List<JavaType> bounds = new ArrayList<>();
			for (JavaType bound : made.typeParameters.get(i).bounds) {
				bounds.add(JavaTypes.substitute(bound, found));
			}
			own.get(i).bounds = List.copyOf(bounds);
		}
		return List.copyOf(found.values());
	}

	/**
	 * The type parameters a creation with {@code <>} finds, of the type it makes: the
	 * class's own where that type is raw.
	 */
	private static List<TypeParameter> diamondParameters(ClassType type) {
		List<TypeParameter> declared = type.declaration().typeParameters;
		if (type.arguments().size() < declared.size()) {
			return declared;
		}
		List<TypeParameter> parameters = new ArrayList<>();
		for (JavaType argument : type.arguments().subList(0, declared.size())) {
			if (!(argument instanceof Variable variable)) {
				return declared;
			}
			parameters.add(variable.parameter());
		}
		return parameters;
	}

	/**
	 * The type an anonymous class made with {@code <>} extends: its arguments found as a
	 * creation with {@code <>} finds them -- from the constructor's arguments and the
	 * type the instance is wanted as --, each one a program can write.
	 */
	private ClassType anonymousType(ClassType type, New creation, @Nullable JavaType expected, int pos) {
		JavaClass made = type.declaration();
		boolean conforms = switch (expected) {
			case null -> true;
			case ClassType named ->
				named.declaration().name.equals("java.lang.Object") || made.isSubclassOf(named.declaration());
			case Primitive primitive -> primitive == Primitive.VOID;
			case ArrayType array -> false;
			default -> true;
		};
		if (!conforms) {
			// no type arguments make the class one: said before its body is lowered
			throw error(pos, "incompatible types: cannot infer type arguments for " + made.simpleName() + "<>");
		}
		JavaType found;
		if (made.isInterface) {
			Inference inference = this.types.new Inference(diamondParameters(type));
			if (expected != null && expected.isReference()) {
				inference.upperBound(type, expected);
			}
			found = JavaTypes.substitute(type, inference.solution(true));
		}
		else {
			// the arguments are lowered again with the class's constructor
			found = resolve(constructors(made), type, List.of(), creation.arguments(), expected,
					"constructor " + made.simpleName(), made, pos, diamondParameters(type), type)
				.returnType();
		}
		ClassType grounded = (ClassType) this.types.ground(found);
		for (JavaType argument : grounded.arguments()) {
			if (!denotable(argument)) {
				throw error(pos, "cannot infer type arguments for " + made.simpleName() + "<>");
			}
		}
		return grounded;
	}

	/** Whether a program can write a type: it names no captured wildcard. */
	private static boolean denotable(JavaType type) {
		return switch (type) {
			case Variable variable -> variable.parameter().captured == null && !variable.parameter().open;
			case ClassType named -> named.arguments().stream().allMatch(JavaLowering::denotable);
			case ArrayType array -> denotable(array.element());
			case Wildcard wildcard -> (wildcard.upper() == null || denotable(wildcard.upper()))
					&& (wildcard.lower() == null || denotable(wildcard.lower()));
			default -> true;
		};
	}

	/** {@code Type::new}: an instance creation, an array creation for an array type. */
	private Typed constructorReference(JavaSyntax.MethodReference reference, List<Expression> arguments,
			@Nullable JavaType hint) {
		int pos = reference.pos();
		TypeName written = reference.type();
		if (written == null) {
			String name = reference.target() == null ? null : dotted(reference.target());
			if (name == null) {
				throw error(pos, "invalid method reference: a class is expected before ::new");
			}
			written = new TypeName(Objects.requireNonNull(reference.target()).pos(), name, List.of(), false, 0);
		}
		if (written.dimensions() > 0) {
			if (arguments.size() != 1) {
				throw error(pos, "incompatible types: invalid constructor reference");
			}
			return expression(new NewArray(pos, written, arguments, null), null);
		}
		JavaClass made = this.classes.require(written.name(), written.pos(), this.currentClass, this.unit);
		boolean inferred = written.arguments().isEmpty() && !made.typeParameters.isEmpty();
		if (inferred && !reference.typeArguments().isEmpty()) {
			throw error(pos, "invalid constructor reference");
		}
		return creation(new New(pos,
				new TypeName(written.pos(), written.name(), written.arguments(), inferred, 0, written.enclosing()),
				arguments, null, null, reference.typeArguments()), hint);
	}

	// ---- calls

	/**
	 * A method chosen for a call, and the call's arguments as its parameters take them.
	 */
	private record Resolved(Method method, List<Typed> arguments, JavaType returnType, List<JavaType> thrown) {

		List<LispVal> forms() {
			return this.arguments.stream().map(Typed::form).toList();
		}

	}

	/**
	 * Whether an argument waits for its parameter's type before it can be lowered: a
	 * lambda, a method reference, a conditional of them.
	 */
	private static boolean needsTarget(Expression argument) {
		return switch (argument) {
			case Lambda lambda -> true;
			case JavaSyntax.MethodReference reference -> true;
			case Conditional conditional -> needsTarget(conditional.whenTrue()) || needsTarget(conditional.whenFalse());
			case JavaSyntax.Switch choice ->
				choice.cases().stream().anyMatch(arm -> arm.value() != null && needsTarget(arm.value()));
			default -> false;
		};
	}

	/** Whether a statement holds a {@code return} of a value that is its function's. */
	private static boolean returnsValue(@Nullable Statement statement) {
		return switch (statement) {
			case null -> false;
			case Return exit -> exit.value() != null;
			case Block block -> block.statements().stream().anyMatch(JavaLowering::returnsValue);
			case If branch -> returnsValue(branch.then()) || returnsValue(branch.otherwise());
			case While loop -> returnsValue(loop.body());
			case DoWhile loop -> returnsValue(loop.body());
			case For loop -> returnsValue(loop.body());
			case ForEach loop -> returnsValue(loop.body());
			case JavaSyntax.Labeled labeled -> returnsValue(labeled.body());
			case JavaSyntax.Synchronized held -> returnsValue(held.body());
			case Try attempt -> returnsValue(attempt.body())
					|| attempt.catches().stream().anyMatch(clause -> returnsValue(clause.body()))
					|| returnsValue(attempt.finallyBlock());
			case JavaSyntax.Switch block -> block.cases()
				.stream()
				.anyMatch(written -> written.body().stream().anyMatch(JavaLowering::returnsValue));
			default -> false;
		};
	}

	/**
	 * Whether a lambda's body can be a function of a kind: one giving a value, or one
	 * giving none.
	 */
	private boolean fits(Lambda lambda, boolean isVoid) {
		Expression value = lambda.expression();
		if (value != null) {
			return !isVoid || value instanceof Assign || value instanceof Call || value instanceof New
					|| (value instanceof Unary unary
							&& (unary.operator().equals("++") || unary.operator().equals("--")));
		}
		Block block = Objects.requireNonNull(lambda.block());
		return isVoid ? !returnsValue(block) : returnsValue(block) || exits(block);
	}

	/** Whether each lambda met so far gives no value, where that was looked for. */
	private final Map<Lambda, Boolean> givesNothing = new IdentityHashMap<>();

	/**
	 * Whether a lambda whose body is a call gives no value: the call is of a method
	 * giving none. Only a lambda that writes the types of its parameters, or has none,
	 * can be asked; its body is lowered once to find out, as the arms of a switch
	 * expression are, and what that lowering made is dropped.
	 */
	private boolean givesNothing(Lambda lambda) {
		Boolean known = this.givesNothing.get(lambda);
		if (known != null) {
			return known;
		}
		Expression body = Objects.requireNonNull(lambda.expression());
		Standing standing = standing();
		this.patternVariables = new ArrayList<>();
		this.scopes.push(new Scope(null));
		this.initializing = null;
		boolean nothing;
		try {
			for (Parameter parameter : lambda.parameters()) {
				bind(parameter.name(), type(Objects.requireNonNull(parameter.type())), parameter.pos(), parameter);
			}
			nothing = expression(body, null).type() == Primitive.VOID;
		}
		catch (LispReadException ex) {
			// what is wrong with it is said where it is lowered for its parameter
			nothing = false;
		}
		finally {
			restore(standing);
		}
		this.givesNothing.put(lambda, nothing);
		return nothing;
	}

	/**
	 * Where the lowering stands inside a body, to stand there again after a lowering that
	 * is dropped.
	 */
	private record Standing(List<Scope> scopes, List<Target> targets, List<Frame> frames, List<Local> patterns,
			JavaType returnType, @Nullable List<JavaType> returns, boolean lambdaBody,
			@Nullable Initializing initializing, int warnings, int deferred) {
	}

	private Standing standing() {
		return new Standing(List.copyOf(this.scopes), List.copyOf(this.targets), List.copyOf(this.frames),
				this.patternVariables, this.returnType, this.returns, this.lambdaBody, this.initializing,
				this.warnings.size(), this.warnings.deferredSize());
	}

	private void restore(Standing standing) {
		this.scopes.clear();
		this.scopes.addAll(standing.scopes());
		this.targets.clear();
		this.targets.addAll(standing.targets());
		this.frames.clear();
		this.frames.addAll(standing.frames());
		this.patternVariables = standing.patterns();
		this.returnType = standing.returnType();
		this.returns = standing.returns();
		this.lambdaBody = standing.lambdaBody();
		this.initializing = standing.initializing();
		this.flexible = false;
		this.warnings.truncate(standing.warnings());
		this.warnings.truncateDeferred(standing.deferred());
	}

	/**
	 * Whether an argument is a call whose own arguments may need the type it is wanted
	 * as: a lambda or a method reference among them takes the types of its parameters
	 * from what the call infers, which can come from that type alone; or an anonymous
	 * class made with {@code <>}, whose supertype can come from that type alone.
	 */
	private static boolean mayNeedTarget(Expression argument) {
		if (argument instanceof New creation && creation.type().diamond() && creation.body() != null) {
			return true;
		}
		List<Expression> arguments = switch (argument) {
			case Call call -> call.arguments();
			case New creation when creation.type().diamond() && creation.body() == null -> creation.arguments();
			default -> List.of();
		};
		return arguments.stream().anyMatch(each -> needsTarget(each) || mayNeedTarget(each));
	}

	/**
	 * Whether an argument is an anonymous class made with {@code <>}, or a call or a
	 * creation with {@code <>} holding one among its arguments.
	 */
	private static boolean anonymousDiamond(Expression argument) {
		return switch (argument) {
			case New creation when creation.type().diamond() ->
				creation.body() != null || creation.arguments().stream().anyMatch(JavaLowering::anonymousDiamond);
			case Call call -> call.arguments().stream().anyMatch(JavaLowering::anonymousDiamond);
			default -> false;
		};
	}

	/**
	 * Whether an argument that waits for its parameter's type can be an argument of a
	 * parameter of that type, by its shape.
	 * @param contested whether the call has several candidates: only then is a lambda's
	 * body asked what its shape does not say
	 */
	private boolean accepts(JavaType parameter, Expression written, boolean contested) {
		switch (written) {
			case Lambda lambda: {
				Descriptor function = functional(parameter);
				if (function == null || function.parameters().size() != lambda.parameters().size()
						|| !fits(lambda, function.returnType() == Primitive.VOID)) {
					return false;
				}
				return !contested || function.returnType() == Primitive.VOID || !(lambda.expression() instanceof Call)
						|| lambda.parameters().stream().anyMatch(each -> each.type() == null) || !givesNothing(lambda);
			}
			case JavaSyntax.MethodReference reference: {
				Descriptor function = functional(parameter);
				return function != null && (!contested || takes(reference, function.parameters().size()));
			}
			case Conditional conditional:
				// an arm that is no lambda (null, a variable) is lowered as the type's
				return (!needsTarget(conditional.whenTrue()) || accepts(parameter, conditional.whenTrue(), contested))
						&& (!needsTarget(conditional.whenFalse())
								|| accepts(parameter, conditional.whenFalse(), contested));
			case JavaSyntax.Switch choice:
				return choice.cases()
					.stream()
					.allMatch(arm -> arm.value() == null || !needsTarget(arm.value())
							|| accepts(parameter, arm.value(), contested));
			default:
				// a call lowered once its parameter's type is known: it fits by that
				return mayNeedTarget(written);
		}
	}

	/**
	 * Whether a method reference naming a class can be a function of so many parameters:
	 * a static method or a constructor taking them, or an instance method taking all but
	 * the first. One through an expression is not asked, its type being unknown here.
	 */
	private boolean takes(JavaSyntax.MethodReference reference, int count) {
		Expression written = reference.target();
		JavaClass named = written == null || reference.onSuper() ? null : classNamed(written);
		if (named == null) {
			return true;
		}
		List<Method> candidates = reference.name().equals("new") ? constructors(named)
				: methods(named, reference.name());
		for (Method candidate : candidates) {
			int declared = candidate.parameters.size() - candidate.hidden;
			int given = candidate.isStatic || candidate.isConstructor() ? count : count - 1;
			if (given >= 0 && (given == declared || (candidate.varargs && given >= declared - 1))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether an exact method reference naming a class -- its one method of the name, of
	 * fixed arity and with no type parameter -- gives what a parameter's function type
	 * answers: such a reference counts in a call's applicability by the type it returns,
	 * so of the overloads taking a function of one arity only those whose value the
	 * method's can be take it. Any other reference is taken by its arity alone.
	 * @param variables the type parameters the call infers: one of them as the function's
	 * value is whatever its bounds allow
	 */
	private boolean gives(JavaSyntax.MethodReference reference, JavaType formal, List<TypeParameter> variables) {
		Expression written = reference.target();
		JavaClass named = written == null || reference.onSuper() || !reference.typeArguments().isEmpty() ? null
				: classNamed(written);
		Descriptor function = functional(formal);
		if (named == null || function == null || function.returnType() == Primitive.VOID
				|| reference.name().equals("new")) {
			return true;
		}
		List<Method> candidates = methods(named, reference.name());
		if (candidates.size() != 1 || candidates.get(0).varargs || !candidates.get(0).typeParameters.isEmpty()) {
			return true;
		}
		JavaType given = candidates.get(0).returnType;
		if (given == Primitive.VOID) {
			return false;
		}
		Set<TypeParameter> open = new HashSet<>(variables);
		if (JavaTypes.mentions(given, new HashSet<>(candidates.get(0).owner.allTypeParameters()))) {
			// what the class's arguments make of it is the reference's own inference
			return true;
		}
		List<JavaType> wanted = function.returnType() instanceof Variable variable
				&& open.contains(variable.parameter()) ? variable.parameter().bounds : List.of(function.returnType());
		for (JavaType each : wanted) {
			JavaType target = JavaTypes.mentions(each, open) ? this.types.erased(each) : each;
			if (conversion(new Typed(NIL, given), target, true, false) == null) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a parameter is at least as specific as another's for an argument that waits
	 * for its parameter's type (JLS 15.12.2.5): a subtype is, for any argument; else a
	 * functional interface giving a value, against one that is no subtype of it giving
	 * none or a value it converts to.
	 */
	private boolean moreSpecific(JavaType mine, JavaType theirs) {
		if (conversion(new Typed(NIL, mine), theirs, false, false) != null) {
			return true;
		}
		Descriptor a = functional(mine);
		Descriptor b = functional(theirs);
		if (a == null || b == null || conversion(new Typed(NIL, theirs), mine, false, false) != null) {
			return false;
		}
		if (b.returnType() == Primitive.VOID) {
			return true;
		}
		return a.returnType() != Primitive.VOID
				&& (conversion(new Typed(NIL, a.returnType()), b.returnType(), false, false) != null
						|| (a.returnType().isPrimitive() && !b.returnType().isPrimitive()));
	}

	/**
	 * The type a method's parameter list gives the argument at an index.
	 * @param site the type the method is called through
	 * @param spread whether the last parameter takes the arguments from its index on,
	 * each as an element
	 */
	private JavaType parameterAt(Method method, int index, @Nullable JavaType site, boolean spread) {
		int last = method.parameters.size() - 1;
		JavaType declared = spread && index >= last ? ((ArrayType) method.parameters.get(last)).element()
				: method.parameters.get(index);
		return this.types.memberType(declared, site, method.owner);
	}

	/**
	 * Whether a call's arguments fit a method: what they say about its type parameters,
	 * or {@code null} when they do not fit.
	 * @param boxing whether a boxing or an unboxing may be part of the fit
	 * @param spread whether the arguments past the fixed parameters are the elements of
	 * the last
	 */
	private @Nullable Inference applicable(Method candidate, @Nullable JavaType site, List<JavaType> explicit,
			List<TypeParameter> inferred, List<@Nullable Typed> typed, List<Expression> arguments, boolean boxing,
			boolean spread, boolean contested, Late late, @Nullable JavaType expected, @Nullable JavaType made) {
		int count = arguments.size();
		if (spread ? !candidate.varargs || count < candidate.parameters.size() - 1
				: candidate.parameters.size() != count) {
			return null;
		}
		List<TypeParameter> variables = new ArrayList<>(inferred);
		variables.addAll(candidate.typeParameters);
		Inference inference = this.types.new Inference(variables);
		// a method that is not generic ignores the type arguments written
		if (!explicit.isEmpty() && !candidate.typeParameters.isEmpty()) {
			if (explicit.size() != candidate.typeParameters.size()) {
				return null;
			}
			for (int i = 0; i < explicit.size(); i++) {
				inference.exactly(candidate.typeParameters.get(i), explicit.get(i));
			}
		}
		if (candidate.raw) {
			// a member taking its argument as it is typed takes none that waits for a
			// type
			return typed.contains(null) ? null : inference;
		}
		for (int i = 0; i < count; i++) {
			JavaType formal = parameterAt(candidate, i, site, spread);
			Typed argument = typed.get(i);
			if (argument != null) {
				inference.lowerBound(formal, argument.type());
			}
			else if (formal instanceof Variable variable && variables.contains(variable.parameter())
					&& needsTarget(arguments.get(i))) {
				// a parameter whose type the call infers says nothing of a lambda's
				// shape: it waits for the type found
			}
			else if (late.indices().contains(i)
					&& (late.contested() || (candidate.varargs && !spread && i == count - 1))) {
				// with one argument for a variable arity parameter, the phases taking it
				// as the array come first: a call that does not lower against the array's
				// type does not fit there, and is an element in the last phase
				if (!late.lowers(this, i, arguments.get(i),
						JavaTypes.substitute(formal, tried(candidate, site, inference, variables, expected, made)))) {
					return null;
				}
			}
			else if (!accepts(formal, arguments.get(i), contested)) {
				return null;
			}
			else if (late.contested() && arguments.get(i) instanceof JavaSyntax.MethodReference reference
					&& !gives(reference, formal, variables)) {
				return null;
			}
		}
		Map<TypeParameter, JavaType> solution = inference.solution(true);
		if (!inference.withinBounds(solution)) {
			return null;
		}
		for (int i = 0; i < count; i++) {
			Typed argument = typed.get(i);
			if (argument != null && conversion(argument,
					JavaTypes.substitute(parameterAt(candidate, i, site, spread), solution), boxing, false) == null) {
				return null;
			}
		}
		return inference;
	}

	/**
	 * What a candidate's type parameters are, for an argument that waits tried against
	 * its parameter: what the arguments said, and of a parameter they said nothing of,
	 * what the type the call is wanted as says through the candidate's return type -- a
	 * creation with {@code <>} given a comparator its type is wanted for
	 * ({@code PriorityQueue
	 *
	<P>
	 *  q = new PriorityQueue<>(Comparator.comparingInt(p -> ..))}).
	 */
	private Map<TypeParameter, JavaType> tried(Method candidate, @Nullable JavaType site, Inference inference,
			List<TypeParameter> variables, @Nullable JavaType expected, @Nullable JavaType made) {
		Map<TypeParameter, JavaType> known = inference.solution(true);
		if (expected == null || expected.isPrimitive()) {
			return known;
		}
		JavaType returned = made != null ? made : this.types.memberType(candidate.returnType, site, candidate.owner);
		if (!JavaTypes.mentions(returned, inference.variables)) {
			return known;
		}
		Inference wanted = this.types.new Inference(variables);
		wanted.upperBound(returned, expected);
		Map<TypeParameter, JavaType> said = wanted.solution(false);
		Map<TypeParameter, JavaType> tried = new HashMap<>(known);
		for (Map.Entry<TypeParameter, JavaType> entry : said.entrySet()) {
			if (inference.isFree(entry.getKey())) {
				tried.put(entry.getKey(), entry.getValue());
			}
		}
		return tried;
	}

	/**
	 * The arguments of a call that failed with no target and wait for their parameter's
	 * type.
	 *
	 * @param indices where they are among the arguments
	 * @param contested whether several candidates have the call's arity: then a candidate
	 * takes such an argument only when it lowers against its parameter's type, as a
	 * variable arity candidate always does at its last parameter taken as the array
	 * @param tried whether each lowered against a type, by index and type
	 */
	private record Late(Set<Integer> indices, boolean contested, Map<List<Object>, Boolean> tried) {

		/**
		 * Whether an argument lowers against a type; what the lowering made is dropped.
		 */
		boolean lowers(JavaLowering lowering, int index, Expression argument, JavaType target) {
			return this.tried.computeIfAbsent(List.of(index, target), key -> {
				Standing standing = lowering.standing();
				try {
					lowering.expression(argument, target);
					return true;
				}
				catch (LispReadException ex) {
					return false;
				}
				finally {
					lowering.restore(standing);
				}
			});
		}

	}

	/**
	 * Lowers an argument that waited for its parameter's type, and tells the call's
	 * inference what its value says.
	 */
	private Typed deferred(Expression argument, JavaType parameter, Inference inference) {
		JavaType target = JavaTypes.substitute(parameter, inference.solution(false));
		if (!(argument instanceof Lambda) && !(argument instanceof JavaSyntax.MethodReference)) {
			Typed value = expression(argument, JavaTypes.substitute(parameter, inference.solution(true)));
			// each wildcard of its type is a type of its own, as an argument's lowered
			// ahead of the inference is
			if (value.type() instanceof ClassType named
					&& named.arguments().stream().anyMatch(Wildcard.class::isInstance)) {
				value = new Typed(value.form(), this.types.capture(named), value.constant());
			}
			if (mayNeedTarget(argument)) {
				inference.lowerBound(parameter, value.type());
			}
			throwsOf(argument, parameter, inference);
			return value;
		}
		Descriptor function = functional(target);
		if (function == null) {
			if (target instanceof Variable variable && inference.variables.contains(variable.parameter())) {
				throw error(argument.pos(), "incompatible types: cannot infer type-variable(s) " + variable);
			}
			// a type the call found that is no functional interface: said where it is
			// lowered
			return argument instanceof Lambda lambda ? lambda(lambda, target, inference)
					: methodReference((JavaSyntax.MethodReference) argument, target, inference);
		}
		Method exact = argument instanceof JavaSyntax.MethodReference reference ? exact(reference) : null;
		// this::name takes the receiver it is bound to; Type::name of an instance method
		// takes it as the first parameter
		boolean unbound = exact != null && !exact.isStatic && !exact.isConstructor()
				&& !(((JavaSyntax.MethodReference) argument).target() instanceof JavaSyntax.This);
		// what the function takes has to be known before its body is lowered
		for (int i = 0; i < function.parameters().size(); i++) {
			JavaType input = function.parameters().get(i);
			if (argument instanceof Lambda lambda && lambda.parameters().get(i).type() != null) {
				inference.same(input, type(Objects.requireNonNull(lambda.parameters().get(i).type())));
			}
			else if (exact != null && (i > 0 || !unbound)) {
				// an exact method reference: what it takes is under what the method takes
				int at = i - (unbound ? 1 : 0) + exact.hidden;
				if (at < exact.parameters.size() && JavaTypes.mentions(input, inference.variables)) {
					inference.upperBound(input, exact.parameters.get(at));
				}
			}
			else if (i == 0 && argument instanceof JavaSyntax.MethodReference reference && reference.target() != null
					&& !reference.name().equals("new") && input instanceof Variable variable
					&& inference.variables.contains(variable.parameter()) && inference.isFree(variable.parameter())
					&& classNamed(reference.target()) instanceof JavaClass named) {
				// Type::name of a first parameter nothing was said of: it is the type
				inference.exactly(variable.parameter(), new ClassType(named, List.of()));
			}
			for (TypeParameter variable : inference.variables) {
				if (inference.isFree(variable) && JavaTypes.mentions(input, Set.of(variable))) {
					inference.exactly(variable, this.types.erased(new Variable(variable)));
				}
			}
		}
		target = JavaTypes.substitute(parameter, inference.solution(false));
		Typed value = argument instanceof Lambda lambda ? lambda(lambda, target, inference)
				: methodReference((JavaSyntax.MethodReference) argument, target, inference);
		throwsOf(argument, parameter, inference);
		return value;
	}

	/**
	 * The one method a reference {@code Type::name} or {@code this::name} can mean, whose
	 * parameters then say what the function takes: the class has one method of the name,
	 * or one constructor, not generic and of no variable arity (JLS 15.13.1); a class
	 * that is generic only for {@code this::name}, whose type parameters are in scope as
	 * themselves. {@code null} for any other reference.
	 */
	private @Nullable Method exact(JavaSyntax.MethodReference reference) {
		Expression written = reference.target();
		if (written instanceof JavaSyntax.This && !reference.onSuper() && reference.typeArguments().isEmpty()
				&& !reference.name().equals("new")) {
			List<Method> candidates = methods(this.currentClass, reference.name());
			Method only = candidates.size() == 1 ? candidates.get(0) : null;
			return only == null || only.varargs || only.isStatic || !only.typeParameters.isEmpty()
					|| (only.owner != this.currentClass && !only.owner.allTypeParameters().isEmpty()) ? null : only;
		}
		JavaClass named = written == null || reference.onSuper() ? null : classNamed(written);
		if (named == null || !reference.typeArguments().isEmpty()) {
			return null;
		}
		List<Method> candidates = reference.name().equals("new") ? constructors(named)
				: methods(named, reference.name());
		if (candidates.size() != 1) {
			return null;
		}
		Method only = candidates.get(0);
		return only.varargs || !only.typeParameters.isEmpty() || !only.owner.allTypeParameters().isEmpty() ? null
				: only;
	}

	private static String kind(JavaClass declaration) {
		return declaration.isInterface ? "interface"
				: declaration.isEnum ? "enum" : declaration.isRecord ? "record" : "class";
	}

	/**
	 * The arguments of a call of the library with a charset's name written as a constant
	 * replaced by the charset, where the member taking the name has one beside it that
	 * takes a {@code Charset} in its place ({@code getBytes("UTF-8")},
	 * {@code new InputStreamReader(in, "UTF-8")}): the two do the same, and the member of
	 * the name looks its charset up among all of them where the program runs, so that a
	 * program naming UTF-8 would hold every charset's codec.
	 */
	private List<Expression> charsetsNamed(List<Method> candidates, List<Expression> arguments) {
		if (candidates.isEmpty() || !(candidates.get(0).owner.intrinsic || isLibrary(candidates.get(0).owner))) {
			return arguments;
		}
		List<Expression> replaced = arguments;
		for (int i = 0; i < arguments.size(); i++) {
			JavaClass.Accessor accessor = arguments.get(i) instanceof StringLiteral literal
					? JavaIntrinsics.charsetNamed(literal.value()) : null;
			if (accessor == null) {
				continue;
			}
			int at = i;
			ClassType charset = candidates.stream()
				.filter(named -> named.parameters.size() == arguments.size()
						&& named.parameters.get(at).equals(this.stringType))
				.flatMap(named -> candidates.stream()
					.filter(typed -> typed.parameters.size() == named.parameters.size()
							&& typed.parameters.get(at) instanceof ClassType taken
							&& taken.declaration().name.equals(JavaIntrinsics.CHARSET)
							&& IntStream.range(0, named.parameters.size())
								.allMatch(k -> k == at || typed.parameters.get(k).equals(named.parameters.get(k))))
					.map(typed -> (ClassType) typed.parameters.get(at)))
				.findFirst()
				.orElse(null);
			if (charset != null) {
				if (replaced == arguments) {
					replaced = new ArrayList<>(arguments);
				}
				replaced.set(i, new JavaSyntax.Lowered(arguments.get(i).pos(),
						new Typed(library(accessor.className(), accessor.method(), List.of()), charset)));
			}
		}
		return replaced;
	}

	private Resolved resolve(List<Method> candidates, @Nullable JavaType site, List<JavaType> explicit,
			List<Expression> arguments, @Nullable JavaType expected, String what, JavaClass in, int pos) {
		return resolve(candidates, site, explicit, arguments, expected, what, in, pos, List.of(), null);
	}

	/**
	 * Picks the method a call means: the candidates its arguments fit with no boxing, or
	 * else with it, or else as the elements of a variable arity parameter, and among
	 * those the one whose parameters are the most specific. The type parameters of the
	 * one picked are found from the arguments, from the type the call's value is wanted
	 * as, then from the lambdas among the arguments, each lowered once its own
	 * parameters' types are known.
	 * @param site the type the method is called through, which gives the type parameters
	 * of its class their arguments
	 * @param explicit the type arguments written at the call
	 * @param expected the type the call's value is wanted as, or {@code null}
	 * @param inferred the type parameters of the class an instance creation with
	 * {@code <>} has to find
	 * @param made the type such a creation makes, in those parameters
	 */
	private Resolved resolve(List<Method> candidates, @Nullable JavaType site, List<JavaType> explicit,
			List<Expression> written, @Nullable JavaType expected, String what, JavaClass in, int pos,
			List<TypeParameter> inferred, @Nullable JavaType made) {
		Standing standing = standing();
		boolean flexible = this.flexible;
		boolean[] eager = new boolean[1];
		try {
			return resolve(candidates, site, explicit, written, expected, what, in, pos, inferred, made, eager);
		}
		catch (LispReadException failed) {
			if (!eager[0]) {
				throw failed;
			}
			// an argument that is a call holding lambdas lowered with no target, and may
			// have settled there on types the rest of this call cannot take
			// (collectingAndThen(toList(), l -> ..) on a List<Object>): the call is
			// resolved once more, such arguments waiting for their parameters' types
			restore(standing);
			this.flexible = flexible;
			try {
				return resolve(candidates, site, explicit, written, expected, what, in, pos, inferred, made, null);
			}
			catch (LispReadException again) {
				throw failed;
			}
		}
	}

	/**
	 * @param eager set when an argument that is a call holding lambdas lowered with no
	 * target; {@code null} to have every such argument wait for its parameter's type
	 */
	private Resolved resolve(List<Method> candidates, @Nullable JavaType site, List<JavaType> explicit,
			List<Expression> written, @Nullable JavaType expected, String what, JavaClass in, int pos,
			List<TypeParameter> inferred, @Nullable JavaType made, boolean @Nullable [] eager) {
		List<Expression> arguments = charsetsNamed(candidates, written);
		boolean flexible = this.flexible;
		this.flexible = false;
		List<@Nullable Typed> typed = new ArrayList<>();
		// a call whose lambdas need the type this call gives it is lowered after this
		// call's own inference, against its parameter's type; with several candidates of
		// the arity, a candidate takes it when it lowers against that candidate's
		// parameter
		int count = arguments.size();
		Set<Integer> late = new HashSet<>();
		LispReadException lateError = null;
		boolean typedAsGiven = candidates.stream().anyMatch(candidate -> candidate.raw);
		for (Expression argument : arguments) {
			if (needsTarget(argument)) {
				typed.add(null);
				continue;
			}
			// a call that is an argument leaves open what this call may need above
			this.flexible = argument instanceof Call || (argument instanceof New creation && creation.type().diamond());
			Typed lowered;
			if (mayNeedTarget(argument)) {
				if (eager == null || (anonymousDiamond(argument) && !typedAsGiven)) {
					// an anonymous class made with <> is lowered once, against the type
					// its parameter gives it -- unless a member takes it as it is typed
					late.add(typed.size());
					typed.add(null);
					continue;
				}
				Standing standing = standing();
				try {
					lowered = expression(argument, null);
					eager[0] = true;
				}
				catch (LispReadException ex) {
					restore(standing);
					late.add(typed.size());
					typed.add(null);
					lateError = lateError != null ? lateError : ex;
					continue;
				}
			}
			else {
				lowered = expression(argument, null);
			}
			this.flexible = false;
			// each wildcard of an argument's type is a type of its own from here on
			typed.add(lowered.type() instanceof ClassType named
					&& named.arguments().stream().anyMatch(Wildcard.class::isInstance)
							? new Typed(lowered.form(), this.types.capture(named), lowered.constant()) : lowered);
		}
		List<Method> fitting = new ArrayList<>();
		Map<Method, Inference> inferences = new HashMap<>();
		boolean spread = false;
		long ofArity = candidates.stream()
			.filter(candidate -> candidate.parameters.size() == count
					|| (candidate.varargs && count >= candidate.parameters.size() - 1))
			.count();
		Late lates = new Late(late, ofArity > 1, new HashMap<>());
		for (int phase = 0; phase < 3 && fitting.isEmpty(); phase++) {
			spread = phase == 2;
			for (Method candidate : candidates) {
				Inference inference = applicable(candidate, site, explicit, inferred, typed, arguments, phase > 0,
						spread, candidates.size() > 1, lates, expected, made);
				if (inference != null) {
					fitting.add(candidate);
					inferences.put(candidate, inference);
				}
			}
		}
		boolean isConstructor = what.startsWith("constructor ");
		String name = what.substring(what.indexOf(' ') + 1);
		if (fitting.isEmpty() && lateError != null && ofArity > 0) {
			// what no candidate's parameter made it lower is said where it is
			throw lateError;
		}
		if (fitting.isEmpty()) {
			StringBuilder given = new StringBuilder();
			for (int i = 0; i < typed.size(); i++) {
				Typed argument = typed.get(i);
				given.append(i > 0 ? "," : "")
					.append(argument != null ? argument.type().toString()
							: arguments.get(i) instanceof Lambda ? "<lambda>"
									: arguments.get(i) instanceof JavaSyntax.MethodReference ? "<method reference>"
											: "<expression>");
			}
			if ((in.intrinsic || isLibrary(in)) && ofArity == 0
					&& !(in.isInterface && this.objectType.declaration().methods.stream()
						.anyMatch(method -> method.isProtected && method.name.equals(name)))) {
				// a member the library does not have yet; an interface has no protected
				// method of Object's
				throw JavaClasses.missing(this.unit.source(), pos,
						(isConstructor ? "new " + in.simpleName() : in.simpleName() + "." + name) + "(" + given + ")",
						null);
			}
			if (candidates.isEmpty()) {
				throw error(pos, "cannot find symbol: " + what + "(" + given + ")");
			}
			if (candidates.size() == 1 && !in.intrinsic) {
				Method only = candidates.get(0);
				StringBuilder required = new StringBuilder();
				for (int i = only.hidden; i < only.parameters.size(); i++) {
					required.append(i > only.hidden ? "," : "").append(only.parameters.get(i));
				}
				throw error(pos,
						what + " in " + kind(only.owner) + " " + only.owner.self()
								+ " cannot be applied to given types; required: "
								+ (required.isEmpty() ? "no arguments" : required) + "; found: "
								+ (given.isEmpty() ? "no arguments" : given));
			}
			throw error(pos, "no suitable " + (isConstructor ? "constructor" : "method") + " found for " + name + "("
					+ given + ")");
		}
		Method chosen = null;
		boolean spreads = spread;
		for (Method candidate : fitting) {
			boolean mostSpecific = true;
			for (Method other : fitting) {
				Map<TypeParameter, JavaType> theirs = new HashMap<>();
				for (TypeParameter parameter : other.typeParameters) {
					theirs.put(parameter, this.types.open(parameter, Map.of(), null));
				}
				// what the other's type parameters are inferred to be from this one's
				// parameter types is within their bounds (JLS 18.5.4): <T> f(T) is not
				// as specific as <T extends Throwable> f(T)
				Inference bounded = other == candidate || other.raw || other.typeParameters.isEmpty() ? null
						: this.types.new Inference(other.typeParameters);
				for (int i = 0; i < arguments.size() && mostSpecific; i++) {
					JavaType mine = parameterAt(candidate, i, site, spreads);
					JavaType against = JavaTypes.substitute(parameterAt(other, i, site, spreads), theirs);
					boolean functional = typed.get(i) == null && !late.contains(i);
					mostSpecific = other.raw || (functional ? moreSpecific(mine, against)
							: conversion(new Typed(NIL, mine), against, false, false) != null);
					if (bounded != null && !functional) {
						bounded.lowerBound(parameterAt(other, i, site, spreads), mine);
					}
				}
				if (mostSpecific && bounded != null) {
					Map<TypeParameter, JavaType> found = bounded.solution(true);
					mostSpecific = bounded.conflict(found) == null && bounded.withinBounds(found);
				}
			}
			if (mostSpecific) {
				chosen = candidate;
				break;
			}
		}
		if (chosen == null) {
			throw error(pos, "reference to " + name + " is ambiguous");
		}
		Inference inference = Objects.requireNonNull(inferences.get(chosen));
		// applicable only by the unchecked conversion of a raw argument (JLS 18.5.2): the
		// type the value is wanted as says nothing of the type parameters, and the value
		// is of the erasure of the type returned
		boolean uncheckedApplied = false;
		for (int i = 0; i < arguments.size() && !chosen.raw; i++) {
			Typed argument = typed.get(i);
			uncheckedApplied |= argument != null
					&& uncheckedConversion(argument.type(), parameterAt(chosen, i, site, spread));
		}
		// the value's type against the type it is wanted as
		JavaType returned = made != null ? made : this.types.memberType(chosen.returnType, site, chosen.owner);
		if (!uncheckedApplied && expected != null && expected != Primitive.VOID
				&& JavaTypes.mentions(returned, inference.variables)) {
			if (!expected.isPrimitive()) {
				inference.upperBound(returned, expected);
			}
			else if (returned instanceof Variable variable && inference.isFree(variable.parameter())) {
				inference.exactly(variable.parameter(), JavaIntrinsics.boxed((Primitive) expected, this.classes));
			}
		}
		List<@Nullable Typed> lowered = new ArrayList<>(typed);
		for (int i = 0; i < arguments.size(); i++) {
			if (lowered.get(i) == null) {
				lowered.set(i, deferred(arguments.get(i), parameterAt(chosen, i, site, spread), inference));
			}
		}
		Map<TypeParameter, JavaType> solution = inference.solution(true, flexible);
		TypeParameter conflict = inference.conflict(solution);
		if (conflict != null) {
			throw error(pos, made != null ? "incompatible types: cannot infer type arguments for " + name + "<>"
					: inference.given(conflict) > 1
							? "incompatible types: inferred type does not conform to upper bound(s)"
							: "incompatible types: inference variable " + conflict + " has incompatible bounds");
		}
		if (!inference.withinBounds(solution)) {
			throw error(pos, "incompatible types: inferred type does not conform to upper bound(s)");
		}
		// where the method applies by an unchecked conversion, what the type parameters
		// nothing said of are: what the value's type is the erasure of
		Map<TypeParameter, JavaType> bounded = uncheckedApplied ? bounded(inference, solution) : solution;
		List<Typed> converted = new ArrayList<>();
		for (int i = 0; i < arguments.size(); i++) {
			JavaType parameter = JavaTypes.substitute(parameterAt(chosen, i, site, spread), solution);
			Typed argument = Objects.requireNonNull(lowered.get(i));
			if (typed.get(i) == null && !late.contains(i)) {
				// lowered as the parameter takes it
				converted.add(new Typed(argument.form(), parameter));
			}
			else {
				// the method sees the argument as its declared type erases, which a type
				// argument may hide
				int last = chosen.parameters.size() - 1;
				JavaType declared = spread && i >= last ? ((ArrayType) chosen.parameters.get(last)).element()
						: chosen.parameters.get(i);
				if (!chosen.raw && uncheckedConversion(argument.type(),
						JavaTypes.substitute(parameterAt(chosen, i, site, spread), bounded))) {
					// a compiler names the parameter as the method declares it
					unchecked(caret(arguments.get(i)), "unchecked conversion",
							List.of("required: " + declared, "found:    " + argument.type()),
							List.of(declared, argument.type()));
				}
				converted
					.add(new Typed(
							chosen.raw ? argument.form() : widened(assign(argument, parameter, arguments.get(i).pos()),
									argument.type(), declared),
							this.types.ground(argument.type()), argument.constant()));
			}
		}
		int caret = callCaret(pos, isConstructor ? null : name);
		if (uncheckedApplied) {
			List<JavaType> found = new ArrayList<>();
			for (Typed argument : lowered) {
				found.add(Objects.requireNonNull(argument).type());
			}
			List<JavaType> required = chosen.parameters.subList(chosen.hidden, chosen.parameters.size());
			unchecked(caret,
					"unchecked method invocation: " + (isConstructor ? "constructor <init>" : "method " + name) + " in "
							+ kind(chosen.owner) + " " + this.types.erased(chosen.owner.self())
							+ " is applied to given types",
					List.of("required: " + String.join(",", required.stream().map(JavaType::toString).toList()),
							"found:    " + String.join(",", found.stream().map(JavaType::toString).toList())),
					required);
		}
		reflective(chosen, spread, converted.size() - (chosen.parameters.size() - 1));
		if (spread) {
			JavaType varargs = JavaTypes.substitute(
					this.types.memberType(chosen.parameters.get(chosen.parameters.size() - 1), site, chosen.owner),
					solution);
			if (!reifiable(varargs) && chosen.emitter == null
					&& (chosen.declaration == null || !chosen.declaration.modifiers().contains("@SafeVarargs"))) {
				unchecked(caret, "unchecked generic array creation for varargs parameter of type " + varargs, List.of(),
						List.of(varargs));
			}
			// the arguments past the fixed parameters are the elements of one array
			int fixed = chosen.parameters.size() - 1;
			ArrayType rest = (ArrayType) this.types.erased(JavaTypes
				.substitute(this.types.memberType(chosen.parameters.get(fixed), site, chosen.owner), solution));
			List<LispVal> elements = converted.subList(fixed, converted.size())
				.stream()
				.map(element -> elementStored(rest.element(), element.form()))
				.toList();
			List<JavaType> types = converted.subList(fixed, converted.size()).stream().map(Typed::type).toList();
			converted = new ArrayList<>(converted.subList(0, fixed));
			LispVal array = arrayOf(rest, elements);
			this.spread.put(array, types);
			converted.add(new Typed(array, rest));
		}
		// what a lambda among the arguments may throw and what a method reference among
		// them throws, now that the type each is made for is found
		for (int i = 0; i < arguments.size(); i++) {
			Descriptor function = typed.get(i) != null ? null
					: functional(JavaTypes.substitute(parameterAt(chosen, i, site, spread), solution));
			if (function == null) {
				continue;
			}
			List<JavaType> lets = new ArrayList<>();
			for (JavaType type : function.thrown()) {
				JavaType settled = settled(type);
				if (settled != null) {
					lets.add(settled);
				}
			}
			for (Expression waiting : functions(arguments.get(i), new ArrayList<>())) {
				if (waiting instanceof Lambda lambda) {
					this.facts.lets.put(lambda, lets);
				}
				else {
					thrownTypes((JavaSyntax.MethodReference) waiting, lets);
				}
			}
		}
		// the member a charset's name written as a constant was taken from is the one
		// the program calls: what that one throws
		Method named = chosen;
		if (arguments != written) {
			for (Method candidate : candidates) {
				Method picked = chosen;
				if (candidate != chosen && candidate.parameters.size() == chosen.parameters.size()
						&& IntStream.range(0, chosen.parameters.size())
							.allMatch(k -> candidate.parameters.get(k).equals(picked.parameters.get(k))
									|| (k < arguments.size() && arguments.get(k) != written.get(k)
											&& candidate.parameters.get(k).equals(this.stringType)))) {
					named = candidate;
				}
			}
		}
		return new Resolved(chosen, converted,
				inScope(uncheckedApplied ? this.types.erased(JavaTypes.substitute(returned, bounded))
						: this.types.upward(JavaTypes.substitute(returned, solution)), pos),
				thrownBy(named, site, solution));
	}

	/**
	 * Notes a call looking a member up by parameter types, or handing one found by
	 * reflection arguments, where the arguments past the fixed ones are not none
	 * ({@link JavaClasses#reflectsTypes}, {@link JavaClasses#reflectsArguments}).
	 * @param extra how many arguments a call spreading them gives past the fixed ones
	 */
	private void reflective(Method chosen, boolean spread, int extra) {
		if (!chosen.varargs || (spread && extra == 0)) {
			return;
		}
		String owner = chosen.owner.name;
		if (owner.equals(CLASS) && LOOKUPS.contains(chosen.name) && !this.classes.reflectsTypes) {
			this.classes.reflectsTypes = true;
			this.classes.changed();
		}
		if (((owner.equals(METHOD) && chosen.name.equals("invoke"))
				|| (owner.equals(CONSTRUCTOR) && chosen.name.equals("newInstance")))
				&& !this.classes.reflectsArguments) {
			this.classes.reflectsArguments = true;
			this.classes.changed();
		}
	}

	/** The members of {@code Class} looking a method or a constructor up by its types. */
	private static final Set<String> LOOKUPS = Set.of("getDeclaredMethod", "getMethod", "getDeclaredConstructor",
			"getConstructor");

	/**
	 * What a call applicable only by the unchecked conversion of a raw argument finds for
	 * its type parameters (JLS 18.5.2), the type it returns being the erasure of what
	 * they make of it: what the other arguments said of one, else the least of its
	 * bounds, as a compiler takes it ({@code Collections.max(raw)} is a raw
	 * {@code Comparable}).
	 */
	private Map<TypeParameter, JavaType> bounded(Inference inference, Map<TypeParameter, JavaType> solution) {
		Map<TypeParameter, JavaType> bounded = new HashMap<>(solution);
		for (TypeParameter parameter : inference.variables) {
			JavaType found = solution.get(parameter);
			if (found != null && !isOpen(found)) {
				continue;
			}
			JavaType below = found == null ? null : ((Variable) found).parameter().lower;
			JavaType least = this.types.object;
			for (JavaType bound : parameter.bounds) {
				if (!(bound instanceof ClassType named && named.declaration().name.equals("java.lang.Object"))) {
					least = bound;
					break;
				}
			}
			bounded.put(parameter, below != null ? below : least);
		}
		return bounded;
	}

	/**
	 * The checked exceptions a call of a method throws: its {@code throws} clause as the
	 * type it is called through and the type arguments found for the call give it.
	 */
	private List<JavaType> thrownBy(Method method, @Nullable JavaType site, Map<TypeParameter, JavaType> solution) {
		List<JavaType> declared = method.thrown;
		if (!method.raises.isEmpty()) {
			declared = new ArrayList<>();
			for (String name : method.raises) {
				declared.add(this.classes.libraryType(name));
			}
		}
		List<JavaType> thrown = new ArrayList<>();
		for (JavaType type : declared) {
			JavaType found = settled(JavaTypes.substitute(this.types.memberType(type, site, method.owner), solution));
			if (found != null && JavaTypes.isChecked(found)) {
				thrown.add(found);
			}
		}
		return thrown;
	}

	/**
	 * A type of a {@code throws} clause once an inference is over: a type argument it
	 * left open is the type it found, and where it found none -- nothing was thrown where
	 * the parameter stands -- {@code RuntimeException} when the parameter's bounds allow
	 * it (JLS 18.4), which is {@code null} here, no checked exception; else its bound.
	 */
	private @Nullable JavaType settled(JavaType type) {
		if (!isOpen(type)) {
			return type;
		}
		TypeParameter open = ((Variable) type).parameter();
		if (open.lower != null) {
			return settled(open.lower);
		}
		for (JavaType bound : open.bounds) {
			if (JavaTypes.isChecked(bound)
					&& !(bound instanceof ClassType named && (named.declaration().name.equals("java.lang.Exception")
							|| named.declaration().name.equals("java.lang.Throwable")))) {
				return bound;
			}
		}
		return null;
	}

	/**
	 * The lambdas and method references an argument that waits for its parameter's type
	 * is made of: itself, or those among the arms of a conditional or a switch.
	 */
	private static List<Expression> functions(Expression argument, List<Expression> into) {
		switch (argument) {
			case Lambda lambda -> into.add(lambda);
			case JavaSyntax.MethodReference reference -> into.add(reference);
			case Conditional conditional -> {
				functions(conditional.whenTrue(), into);
				functions(conditional.whenFalse(), into);
			}
			case JavaSyntax.Switch choice -> choice.cases().forEach(arm -> {
				if (arm.value() != null) {
					functions(arm.value(), into);
				}
			});
			default -> {
			}
		}
		return into;
	}

	/** What the method each method reference stands for throws that is checked. */
	private final Map<JavaSyntax.MethodReference, List<JavaType>> referenceThrows = new IdentityHashMap<>();

	/**
	 * Where the checked exceptions of the calls being lowered are gathered: those of the
	 * call a method reference stands for; {@code null} outside one.
	 */
	private @Nullable List<JavaType> referenced;

	/**
	 * A method reference's method throws no checked exception its function type does not
	 * (JLS 15.13.2).
	 */
	private void thrownTypes(JavaSyntax.MethodReference reference, List<JavaType> lets) {
		List<JavaType> beyond = new ArrayList<>();
		for (JavaType type : this.referenceThrows.getOrDefault(reference, List.of())) {
			if (lets.stream().noneMatch(taken -> isOpen(taken) || isSubtype(type, taken))) {
				beyond.add(type);
			}
		}
		if (!beyond.isEmpty()) {
			Expression target = reference.target();
			TypeName named = reference.type();
			throw error(target != null ? target.pos() : named != null ? named.pos() : reference.pos(),
					"incompatible thrown types "
							+ String.join(",", beyond.stream().map(JavaType::toString).distinct().toList())
							+ " in functional expression");
		}
	}

	/**
	 * Records what a call throws for the flow of the body it stands in.
	 * @param node the call
	 * @param pos where a compiler says what it throws
	 */
	private void raises(Object node, int pos, List<JavaType> thrown, @Nullable String detail) {
		if (thrown.isEmpty()) {
			this.facts.raises.remove(node);
		}
		else {
			this.facts.raises.put(node, new JavaFlow.Raise(pos, thrown, detail));
		}
		if (this.referenced != null) {
			this.referenced.addAll(thrown);
		}
	}

	/**
	 * Tells a call's inference what the lambdas and method references of an argument
	 * throw: a type parameter of the function type's {@code throws} clause is at least
	 * each checked exception no other type of the clause takes (JLS 18.2.5).
	 */
	private void throwsOf(Expression argument, JavaType parameter, Inference inference) {
		// the parameter's type as declared: what the inference found so far for a type
		// parameter of the clause is not yet what it is
		Descriptor function = functional(parameter);
		if (function == null) {
			return;
		}
		List<JavaType> variables = function.thrown()
			.stream()
			.filter(type -> type instanceof Variable variable && inference.variables.contains(variable.parameter()))
			.toList();
		if (variables.isEmpty()) {
			return;
		}
		for (Expression waiting : functions(argument, new ArrayList<>())) {
			List<JavaType> thrown = waiting instanceof Lambda lambda ? JavaFlow.thrown(lambda, this.facts)
					: this.referenceThrows.getOrDefault(waiting, List.of());
			for (JavaType type : thrown) {
				if (function.thrown()
					.stream()
					.noneMatch(taken -> !variables.contains(taken) && isSubtype(type, taken))) {
					variables.forEach(variable -> inference.lowerBound(variable, type));
				}
			}
		}
	}

	/**
	 * Whether the call about to be resolved is an argument of another: what only its own
	 * arguments say of its type parameters is then left open above the type they give.
	 */
	private boolean flexible;

	/**
	 * The call of a signature polymorphic method a cast's operand is, and the cast's
	 * type: what the call returns ({@link #polymorphic}).
	 */
	private @Nullable Call castOperand;

	private @Nullable JavaType castType;

	/**
	 * How many calls of a method handle hold the type of their call, each in a variable.
	 */
	private int sites;

	/**
	 * The expression of the statement being lowered: a signature polymorphic call that is
	 * one returns {@code void}, where a lambda's body of no value does not (javac's
	 * answer).
	 */
	private @Nullable Expression statementExpression;

	/**
	 * Whether an expression is a class: {@code getClass()} of an object or a class
	 * literal. Compared with another such, or asked its name, it is read as that name and
	 * no object is made; anywhere else it is the class object ({@link #classValue}).
	 */
	private static boolean isClass(@Nullable Expression expression) {
		return expression instanceof JavaSyntax.ClassLiteral || (expression instanceof Call call
				&& call.name().equals("getClass") && call.arguments().isEmpty() && !call.onSuper());
	}

	/**
	 * A class read ({@link #isClass}).
	 *
	 * @param name its name as a text: the binary name ({@code getName()}) or the simple
	 * one
	 * @param type the class a literal names, else the erased static type of the object
	 * whose class it is
	 * @param literal whether it is a class literal
	 */
	private record ClassRead(LispVal name, JavaType type, boolean literal) {
	}

	private ClassRead classRead(Expression written, boolean qualified) {
		if (written instanceof JavaSyntax.ClassLiteral literal) {
			JavaType of = literalType(literal);
			if (of instanceof ArrayType array) {
				return new ClassRead(string(qualified ? descriptor(array) : simpleArrayName(array)),
						this.types.erased(array), true);
			}
			if (!(of instanceof ClassType named)) {
				// no object is of a primitive type's class: compared as its box is
				return new ClassRead(string(of.toString()), boxOf(of), true);
			}
			JavaClass declaration = named.declaration();
			return new ClassRead(string(qualified ? declaration.binaryName : declaration.simpleName()),
					declaration.self(), true);
		}
		Call inner = (Call) written;
		Typed object = inner.target() == null ? thisValue(inner.pos()) : expression(inner.target(), null);
		if (!object.type().isReference()) {
			throw error(inner.pos(), object.type() + " cannot be dereferenced");
		}
		JavaType erased = this.types.erased(this.types.upward(object.type()));
		if (erased instanceof ArrayType array) {
			if (!isExact(array)) {
				asksArrayTypes();
			}
			return new ClassRead(
					runtimeCall("array-class-name", object.form(), quote(arrayClass(array)), qualified ? T : NIL),
					erased, false);
		}
		if (erased.equals(this.objectType)) {
			asksArrayTypes();
		}
		LispSymbol name = qualified ? CLASS_NAME : SIMPLE_NAME;
		this.generated.add(name);
		if (!(erased instanceof ClassType type) || type.declaration().intrinsic || type.declaration().isInterface
				|| erased.equals(this.objectType)) {
			this.namesOfAny.add(name);
		}
		return new ClassRead(list(name, object.form()), erased, false);
	}

	/**
	 * Whether two classes are one: their binary names are the same text. With {@code ==},
	 * two classes the types say cannot be one are the compiler's error: two literals of
	 * two classes, a literal of a class that is none of the object's type, two objects
	 * whose types no object has both.
	 */
	private LispVal sameClass(Expression left, Expression right, boolean checked, int pos) {
		ClassRead a = classRead(left, true);
		ClassRead b = classRead(right, true);
		if (checked) {
			JavaType x = this.types.erased(a.type());
			JavaType y = this.types.erased(b.type());
			boolean comparable = a.literal() && b.literal() ? x.equals(y) : a.literal() ? isSubtype(x, y) : b.literal()
					? isSubtype(y, x) : isSubtype(x, y) || isSubtype(y, x) || isInterface(x) || isInterface(y);
			if (!comparable) {
				String first = a.literal() ? simpleName(x) : "CAP#1";
				String second = b.literal() ? simpleName(y) : a.literal() ? "CAP#1" : "CAP#2";
				throw error(pos, "incomparable types: Class<" + first + "> and Class<" + second + ">");
			}
		}
		return call("equal", a.name(), b.name());
	}

	private static String simpleName(JavaType type) {
		return type instanceof ClassType named ? named.declaration().simpleName() : type.toString();
	}

	// ---- a class as a value

	private static final String CLASS = "java.lang.Class";

	/** The function answering the methods a class object's class declares. */
	private static final LispSymbol DECLARED_METHODS = new LispSymbol("%declared-methods");

	/** The functions answering a class by its name and running its initialization. */
	private static final LispSymbol CLASS_NAMED = new LispSymbol("%class-named");

	private static final LispSymbol CLASS_INITIALIZE = new LispSymbol("%class-initialize");

	/** The function answering an annotation of a type a class object's class carries. */
	private static final LispSymbol CLASS_ANNOTATION = new LispSymbol("%class-annotation");

	/** The function answering a class object's constructor of no parameter. */
	private static final LispSymbol CLASS_CONSTRUCTOR = new LispSymbol("%class-constructor");

	private static final String CONSTRUCTOR = "java.lang.reflect.Constructor";

	/**
	 * The functions answering a class object's method of no parameter and its field, by
	 * name.
	 */
	private static final LispSymbol CLASS_METHOD = new LispSymbol("%class-method");

	private static final LispSymbol CLASS_FIELD = new LispSymbol("%class-field");

	/**
	 * The functions answering a class object's members in full, by a kind, and the public
	 * methods of no parameter of Object and Enum by name.
	 */
	private static final LispSymbol CLASS_MEMBERS = new LispSymbol("%class-members");

	/** The function answering a class object's modifiers. */
	private static final LispSymbol CLASS_MODIFIERS = new LispSymbol("%class-modifiers");

	private static final String METHOD = "java.lang.reflect.Method";

	private static final String FIELD = "java.lang.reflect.Field";

	private static final String LOOKUP = "java.lang.invoke.MethodHandles.Lookup";

	private static final String METHOD_HANDLE = "java.lang.invoke.MethodHandle";

	private static final String METHOD_TYPE = "java.lang.invoke.MethodType";

	private static final LispSymbol ARRAY_CLASS = new LispSymbol("%array-class");

	/**
	 * The functions answering, of a class object, what the run time records of an array
	 * of its class, and the class object of that array type.
	 */
	private static final LispSymbol ARRAY_OF = new LispSymbol("%array-of");

	private static final LispSymbol ARRAY_TYPE = new LispSymbol("%array-type");

	/** The bits of {@code Class}'s {@code kind}, as the library's source names them. */
	private static final int CLASS_INTERFACE = 1;

	private static final int CLASS_ARRAY = 2;

	private static final int CLASS_PRIMITIVE = 4;

	private static final int CLASS_ENUM = 8;

	private static final int CLASS_RECORD = 16;

	/** The class objects made before anything of the program runs. */
	private List<LispVal> classInits = List.of();

	/** Whether the functions made for the whole program are this pass's last. */
	private boolean generationOver;

	private ClassType classType(JavaType argument) {
		return new ClassType(this.classes.library(CLASS), List.of(argument));
	}

	private static LispSymbol classVariable(String name) {
		return JavaNames.symbol(name + "%class");
	}

	/** The variable holding the class object of a type, which the program then has. */
	private LispSymbol classValue(JavaType type) {
		return classValue(type, true);
	}

	/**
	 * The variable holding a type's class object, noted as a value the program holds, or
	 * (not held) only as one a class object or a member found by reflection names.
	 */
	private LispSymbol classValue(JavaType type, boolean held) {
		JavaType erased = type instanceof Primitive ? type : this.types.erased(type);
		if (erased instanceof ClassType named) {
			erased = new ClassType(named.declaration().present(), List.of());
			if (this.classes.enumConstants) {
				enumValues(named.declaration());
			}
		}
		if (this.classes.classValues.add(erased) && this.generationOver) {
			this.classes.changed();
		}
		if (held && this.classes.heldValues.add(erased) && this.generationOver) {
			this.classes.changed();
		}
		return switch (erased) {
			case ClassType named -> classVariable(named.declaration().lispName());
			case ArrayType array -> classVariable(descriptor(array));
			default -> classVariable(erased.toString());
		};
	}

	/**
	 * The {@code values()} of an enum whose class object is held, which answers
	 * {@code getEnumConstants()}: called by what holds the object, as a lambda's body is
	 * by what makes the lambda, so that the enum is initialized ahead of it.
	 */
	private @Nullable Method enumValues(JavaClass declaration) {
		if (!declaration.isEnum || declaration.declaration == null) {
			return null;
		}
		this.classes.enumMethod(declaration, "values");
		for (Method method : declaration.methods) {
			if (method.isStatic && method.name.equals("values") && method.parameters.isEmpty()) {
				called(method);
				uses(declaration);
				return method;
			}
		}
		return null;
	}

	/**
	 * A call making an {@code EnumSet} (its static methods) or an {@code EnumMap} (its
	 * constructors) over an enum's constants, which the enum's class object hands it
	 * ({@code Support.universe}): the enum the call names as the type argument of what it
	 * makes is called {@code values()} of, as a class object held by a program reaching
	 * {@code getEnumConstants} is, and its class object holds the constants; one naming
	 * no enum (a type variable, a raw type) has every enum's hold them. A copy of a set
	 * or a map takes the constants of the one copied, and a call inside the class those
	 * of the enum its own caller named.
	 * @param made the type the call makes, or the class it is made as
	 */
	private void enumUniverse(Method method, @Nullable JavaType made) {
		boolean making = method.owner.name.equals("java.util.EnumSet") ? method.isStatic
				: method.owner.name.equals("java.util.EnumMap") && method.isConstructor();
		if (!making || method.parameters.size() == 1
				&& this.types.erased(method.parameters.get(0)) instanceof ClassType taken
				&& taken.declaration() == method.owner) {
			return;
		}
		JavaClass outermost = this.currentClass;
		while (outermost.outer != null) {
			outermost = outermost.outer;
		}
		if (outermost == method.owner) {
			return;
		}
		JavaType element = made instanceof ClassType type && !type.arguments().isEmpty() ? type.arguments().get(0)
				: null;
		if (element instanceof ClassType named && enumValues(named.declaration().present()) != null) {
			if (this.classes.universes.add(named.declaration()) && this.generationOver) {
				this.classes.changed();
			}
		}
		else if (!this.classes.enumConstants) {
			this.classes.enumConstants = true;
			this.classes.changed();
		}
	}

	/**
	 * The type a class literal names: a class, an interface, an array type, a primitive
	 * type or {@code void}.
	 */
	private JavaType literalType(JavaSyntax.ClassLiteral literal) {
		TypeName written = literal.type();
		JavaType named = written.name().equals("void") && written.dimensions() == 0 ? Primitive.VOID : type(written);
		if (named instanceof Variable) {
			throw error(literal.pos(), "cannot select from a type variable");
		}
		return named;
	}

	// ---- resources

	private static final String RESOURCES = "rontolisp.internal.Resources";

	private static final String RESOURCE_TABLE = "rontolisp.internal.ResourceTable";

	private static final String CLASS_LOADER = "java.lang.ClassLoader";

	/**
	 * Notes a resource looked up by a name computed where the program runs: the files its
	 * loader may find by a name of what it starts and ends with are carried
	 * ({@link JavaClasses#carry}), and too many are refused at the lookup.
	 * @param tree whether the lookup searches the program's source tree
	 * @param name what the name starts and ends with, as the loader reads it
	 */
	private void carries(boolean tree, Affixes name, int pos) {
		try {
			this.classes.carry(tree, name.prefix(), name.suffix());
		}
		catch (JavaResourceTable.Bounded ex) {
			throw unsupported(pos, "a resource named where the program runs while " + ex.getMessage());
		}
	}

	/**
	 * What a class's {@code getResourceAsStream} or {@code getResource} of a name
	 * computed where the program runs answers: the lookup of the files carried, beside
	 * the class; {@code null} for a class whose loader finds none.
	 * @param name the name, which is not {@code null}
	 * @param bound what the name starts and ends with
	 * @param url whether the URL is asked for, not the stream
	 */
	private @Nullable Answer computedBeside(@Nullable JavaClass declaration, LispVal name, Affixes bound, boolean url,
			int pos) {
		Boolean program = loader(declaration);
		if (program == null) {
			return null;
		}
		String packageName = this.classes.packageOf(Objects.requireNonNull(declaration));
		String directory = packageName == null || packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
		// a name that may start with '/' may name any file; one that does loses it, and
		// any other has the package before it
		String prefix = bound.prefix();
		carries(program,
				new Affixes(prefix.isEmpty() ? "" : prefix.startsWith("/") ? prefix.substring(1) : directory + prefix,
						bound.suffix()),
				pos);
		if (url) {
			this.classes.carryPlaces();
		}
		return new Answer((program ? "program " : "system ") + directory, library(RESOURCE_TABLE,
				url ? "besideUrl" : "beside", List.of(integer(program ? 2 : 1), string(directory), name)));
	}

	@Override
	public LispVal resourceTable() {
		return string(this.classes.carriedTable());
	}

	private static final String SERVICE_LOADER = "java.util.ServiceLoader";

	/** What {@code ServiceLoader} is told of a provider: its constants of these names. */
	private static final int PROVIDER_FOUND = 0;

	private static final int PROVIDER_NOT_FOUND = 1;

	private static final int PROVIDER_NOT_SUBTYPE = 2;

	private static final int PROVIDER_NO_CONSTRUCTOR = 3;

	private static final int PROVIDER_ABSTRACT = 4;

	private static final int PROVIDER_INACCESSIBLE = 5;

	/**
	 * {@code ServiceLoader.load}: the providers the service's files name are found while
	 * the program is lowered, for the program's loader ({@code load(S)}: the thread's
	 * context loader) or, for a loader given, for the program's and the system's, chosen
	 * where the program runs; the platform's finds none. A {@code Class<S>} of an exact
	 * {@code S} names its service; any other is told by its name at run time among the
	 * services of the program's classes the files name (under the type's bound).
	 * {@code loadInstalled} finds none: the platform's loader has no class of the
	 * program's world.
	 * @param kind 0 {@code load(Class)}, 1 {@code load(Class, ClassLoader)}, 2
	 * {@code loadInstalled(Class)}
	 */
	@Override
	public LispVal services(Invocation call, int kind) {
		Typed service = call.arguments().get(0);
		JavaType argument = service.type() instanceof ClassType type && type.arguments().size() == 1
				? type.arguments().get(0) : null;
		JavaClass named = argument == null || argument instanceof Wildcard || argument instanceof Variable ? null
				: locatedClass(argument);
		LispSymbol held = temporary();
		LispVal made = library(SERVICE_LOADER, "made", List.of(held));
		if (kind == 2) {
			return JavaForms.let(held, service.form(), List.of(made));
		}
		JavaType upper = argument instanceof Wildcard wildcard ? wildcard.upper() : argument;
		JavaClass bound = upper == null ? null : locatedClass(upper);
		LispVal program = named != null ? providers(made, named, true, call.pos())
				: byService(held, made, bound, true, call.pos());
		if (kind == 0) {
			return JavaForms.let(held, service.form(), List.of(program));
		}
		LispSymbol loader = temporary();
		LispVal system = named != null ? providers(made, named, false, call.pos())
				: byService(held, made, bound, false, call.pos());
		LispVal roots = call("if",
				call("or", call("null", loader), library(CLASS_LOADER, "searchesRoots", List.of(loader))), system,
				made);
		return list(cl("let*"), list(list(held, service.form()), list(loader, call.argument(1))),
				program.equals(system) ? roots : call("if",
						call("and", loader, library(CLASS_LOADER, "searchesTree", List.of(loader))), program, roots));
	}

	/**
	 * The providers of a service told by its class's name at run time: a {@code cond}
	 * over the services the files of a loader's resources name whose class the program
	 * has (under a bound), each a loader made with its providers; a loader of none for
	 * any other class.
	 */
	private LispVal byService(LispSymbol held, LispVal made, @Nullable JavaClass bound, boolean tree, int pos) {
		Map<String, JavaClass> known = new HashMap<>();
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			known.putIfAbsent(declaration.binaryName, declaration);
		}
		LispSymbol name = temporary();
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("cond"));
		Set<String> services = new LinkedHashSet<>(this.classes.serviceNames(tree));
		if (this.classes.wit.boundary.hostImports()) {
			// the host provides every @WitImport interface the program has
			for (JavaClass declaration : List.copyOf(this.classes.all())) {
				if (declaration.isInterface && this.classes.wit.of(declaration) != null) {
					services.add(declaration.binaryName);
				}
			}
		}
		for (String service : services) {
			JavaClass found = known.get(service);
			// an interface is under Object too
			if (found != null
					&& (bound == null || bound.name.equals("java.lang.Object") || found.isSubclassOf(bound))) {
				arms.add(list(call("equal", name, string(service)), providers(made, found, tree, pos)));
			}
		}
		if (arms.size() == 1) {
			return made;
		}
		arms.add(list(T, made));
		return JavaForms.let(name, library(CLASS, "located", List.of(held)), List.of(list(arms)));
	}

	/**
	 * A loader made with each provider the service's files name for a loader -- or, for a
	 * {@code @WitImport} interface on a target whose host provides it, with the host's
	 * binding alone: the program's own providers are not consulted there
	 * ({@link JavaWitImports}).
	 */
	private LispVal providers(LispVal made, JavaClass service, boolean tree, int pos) {
		if (this.classes.wit.boundary.hostImports()) {
			JavaClass binding = this.classes.wit.binding(service);
			if (binding != null) {
				return library(SERVICE_LOADER, "with", provider(made, binding.binaryName, binding, service, pos));
			}
		}
		LispVal chain = made;
		for (String name : this.classes.serviceProviders(service.binaryName, tree)) {
			JavaClass found = this.classes.binaryNamed(name);
			chain = library(SERVICE_LOADER, "with",
					found == null || (!tree && Boolean.TRUE.equals(loader(found)))
							? List.of(chain, string(name), integer(PROVIDER_NOT_FOUND), NIL, NIL)
							: provider(chain, name, found, service, pos));
		}
		return chain;
	}

	/**
	 * What {@code ServiceLoader} is told of a provider whose class there is: whether it
	 * is of the service's type, has a public constructor of no parameters and can be
	 * made, the function making one and the one answering its class object.
	 */
	private List<LispVal> provider(LispVal chain, String name, JavaClass found, JavaClass service, int pos) {
		// the maker and the type are functions ServiceLoader holds as Suppliers
		JavaClass supplier = this.classes.library("java.util.function.Supplier");
		if (this.classes.lambdaTargets.add(supplier)) {
			this.classes.changed();
		}
		if (this.session != null) {
			tag(List.of(supplier));
		}
		if (!found.isSubclassOf(service)) {
			return List.of(chain, string(name), integer(PROVIDER_NOT_SUBTYPE), NIL, NIL);
		}
		Method constructor = publicConstructor(found);
		if (constructor == null) {
			return List.of(chain, string(name), integer(PROVIDER_NO_CONSTRUCTOR), NIL, NIL);
		}
		// a Supplier's values: a structure holding the function where the program tells
		// a supplier from other functions
		LispVal type = lambdaValue(supplier, list(cl("lambda"), NIL, classValue(raw(found))));
		if (found.isAbstract || !isPublic(found)) {
			return List.of(chain, string(name), integer(found.isAbstract ? PROVIDER_ABSTRACT : PROVIDER_INACCESSIBLE),
					NIL, type);
		}
		return List.of(chain, string(name), integer(PROVIDER_FOUND),
				lambdaValue(supplier, list(cl("lambda"), NIL, made(found, constructor, pos))), type);
	}

	private static boolean isPublic(JavaClass declaration) {
		return declaration.declaration != null && declaration.declaration.modifiers().contains("public");
	}

	/**
	 * A class's public constructor of no parameters, which a class found by its name is
	 * made with, or {@code null}: an interface and an inner class have none.
	 */
	private @Nullable Method publicConstructor(JavaClass declaration) {
		if (declaration.isInterface || declaration.hasOuterInstance()) {
			return null;
		}
		for (Method candidate : constructors(declaration)) {
			JavaSyntax.Method written = candidate.declaration;
			if (candidate.parameters.size() == candidate.hidden
					&& (written == null ? isPublic(declaration) : written.modifiers().contains("public"))) {
				return candidate;
			}
		}
		return null;
	}

	/** An instance of a class found by its name, made with its constructor. */
	private LispVal made(JavaClass declaration, Method constructor, int pos) {
		called(constructor);
		uses(declaration);
		makes(declaration);
		return located(form(JavaNames.creator(constructor), instanceArguments(declaration, null, List.of(), pos)), pos);
	}

	/**
	 * Whether {@code ResourceBundle.getBundle} makes a class its bundle: a public class
	 * under {@code ResourceBundle} that can be made, with a public constructor of no
	 * parameters.
	 */
	private boolean isBundleClass(@Nullable JavaClass declaration) {
		return declaration != null && !declaration.isAbstract && isPublic(declaration)
				&& declaration.isSubclassOf(this.classes.library(RESOURCE_BUNDLE)) && loader(declaration) != null
				&& publicConstructor(declaration) != null;
	}

	/**
	 * The classes {@code ResourceBundle.getBundle} makes by their names: those of the
	 * constant base names some pass met, or, where a base name is computed where the
	 * program runs, every bundle class of the program and its libraries. A class of the
	 * program's source tree is found by the program's loader alone.
	 */
	@Override
	public LispVal classBundle(Invocation call) {
		Map<String, JavaClass> bundles = new LinkedHashMap<>(this.classes.classBundles);
		if (this.classes.everyClassBundle) {
			for (JavaClass declaration : List.copyOf(this.classes.all())) {
				if (isBundleClass(declaration)) {
					bundles.putIfAbsent(declaration.binaryName, declaration);
				}
			}
		}
		LispSymbol name = temporary();
		LispSymbol tree = temporary();
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("cond"));
		bundles.forEach((binaryName, declaration) -> {
			LispVal named = call("equal", name, string(binaryName));
			arms.add(list(Boolean.TRUE.equals(loader(declaration)) ? call("and", tree, named) : named,
					made(declaration, Objects.requireNonNull(publicConstructor(declaration)), call.pos())));
		});
		arms.add(list(T, NIL));
		return list(cl("let*"), list(list(name, call.argument(0)), list(tree, call.argument(1))), list(arms));
	}

	private static final String RESOURCE_BUNDLE = "java.util.ResourceBundle";

	/**
	 * {@code getBundle(base)} and {@code getBundle(base, locale)} ask the loader of the
	 * class calling them, {@code getBundle(base, locale, loader)} the one given, which
	 * may be the program's, and each with a {@code Control} too; {@code getBundle} of a
	 * module the module's loader. A constant base name carries the {@code .properties}
	 * files of its bundles; one computed where the program runs the {@code .properties}
	 * files under its constant prefix and every bundle class; a {@code Control} -- which
	 * may name any file and any class -- and a module every file and every bundle class,
	 * as a session's call does: a later buffer may name any bundle of the table its first
	 * lookup read.
	 */
	@Override
	public LispVal bundle(Invocation call) {
		Typed base = call.arguments().get(0);
		int given = call.arguments().size();
		JavaClass last = locatedClass(call.arguments().get(given - 1).type());
		boolean controlled = given > 1 && last != null
				&& last.isSubclassOf(Objects.requireNonNull(this.classes.binaryNamed(RESOURCE_BUNDLE + "$Control")));
		boolean module = given > 1 && last != null && last.isSubclassOf(this.classes.library("java.lang.Module"));
		int plain = controlled || module ? given - 1 : given;
		boolean tree = plain == 3 || module || Boolean.TRUE.equals(loader(this.currentClass));
		if (base.constant() instanceof String name && !this.classes.session && !controlled && !module) {
			try {
				this.classes.carryBundle(tree, name.replace('.', '/'));
			}
			catch (JavaResourceTable.Bounded ex) {
				throw unsupported(call.pos(), "a resource bundle while " + ex.getMessage());
			}
			for (String binaryName : this.classes.bundleClassNames(name, tree)) {
				JavaClass found = this.classes.binaryNamed(binaryName);
				if (isBundleClass(found)
						&& this.classes.classBundles.putIfAbsent(binaryName, Objects.requireNonNull(found)) == null) {
					this.classes.changed();
				}
			}
		}
		else {
			if (controlled || module || this.classes.session) {
				carries(tree, Affixes.NONE, call.pos());
			}
			else {
				try {
					this.classes.carryBundles(tree, affixes(base).prefix());
				}
				catch (JavaResourceTable.Bounded ex) {
					throw unsupported(call.pos(), "a resource named where the program runs while " + ex.getMessage());
				}
			}
			this.classes.declareBundleClasses(tree);
			if (!this.classes.everyClassBundle) {
				this.classes.everyClassBundle = true;
				this.classes.changed();
			}
		}
		LispVal locale = plain == 1 ? library("java.util.Locale", "getDefault", List.of()) : call.argument(1);
		List<LispVal> arguments = new ArrayList<>(List.of(base.form(), locale));
		if (module) {
			arguments.add(call.argument(given - 1));
			return library(RESOURCE_BUNDLE, "inModule", arguments);
		}
		arguments.add(plain == 3 ? call.argument(2) : integer(tree ? 2 : 1));
		if (controlled) {
			arguments.add(call.argument(given - 1));
		}
		return library(RESOURCE_BUNDLE, plain == 3 ? "given" : "called", arguments);
	}

	/**
	 * The class a resource is found beside for the class of a type: an array's element
	 * class; {@code null} for a primitive type.
	 */
	private @Nullable JavaClass locatedClass(JavaType type) {
		JavaType erased = this.types.erased(type);
		while (erased instanceof ArrayType array) {
			erased = this.types.erased(array.element());
		}
		return erased instanceof ClassType named ? named.declaration() : null;
	}

	/**
	 * The loader of a class whose resources the program has, as a source launcher has
	 * them: {@code true} the program's (its source tree, then the source path), for a
	 * class of the program; {@code false} the system's (the source path), for one of a
	 * library there; {@code null} for a class of the JDK -- the library's --, whose
	 * bootstrap loader finds none of them.
	 */
	private static @Nullable Boolean loader(@Nullable JavaClass declaration) {
		if (declaration == null || declaration.intrinsic || declaration.absent != null || declaration.source == null
				|| declaration.source.library()) {
			return null;
		}
		return !declaration.source.dependency();
	}

	/** A resource's name below a root, asked for beside a class. */
	private String resolvedName(JavaClass declaration, String name) {
		if (name.startsWith("/")) {
			return name.substring(1);
		}
		String packageName = this.classes.packageOf(declaration);
		return packageName == null || packageName.isEmpty() ? name : packageName.replace('.', '/') + "/" + name;
	}

	/**
	 * What a class's {@code getResourceAsStream} or {@code getResource} answers: the
	 * stream or the URL of the resource its loader finds, its key (which loader, which
	 * name) and the form; {@code null} where it finds none.
	 */
	private @Nullable Answer resourceBeside(@Nullable JavaClass declaration, String name, boolean url) {
		Boolean program = loader(declaration);
		if (program == null) {
			return null;
		}
		String resolved = resolvedName(Objects.requireNonNull(declaration), name);
		LispVal found = url ? resourceUrl(resolved, program) : resourceStream(resolved, program);
		return found == NIL ? null : new Answer((program ? "program " : "system ") + resolved, found);
	}

	/** What a class's {@code getClassLoader} answers, or {@code null} for none. */
	private @Nullable Answer loaderOf(@Nullable JavaClass declaration) {
		Boolean program = loader(declaration);
		return program == null ? null
				: new Answer(program.toString(), program ? library(CLASS_LOADER, "program", List.of())
						: library(CLASS_LOADER, "getSystemClassLoader", List.of()));
	}

	/**
	 * An answer told by the class a class object stands for: equal keys, equal forms.
	 */
	private record Answer(String key, LispVal form) {
	}

	/**
	 * A new stream of a resource's octets, which travel as a text of one character an
	 * octet, or {@code nil} where the loader finds none. A loader finds nothing of a name
	 * starting with {@code /}: {@code Class} takes one away, so {@code "//top.txt"}
	 * beside a class is none.
	 * @param program whether it is the program's loader, which looks in the program's
	 * source tree first
	 */
	private LispVal resourceStream(String name, boolean program) {
		if (name.startsWith("/")) {
			return NIL;
		}
		List<JavaClasses.Located> found = this.classes.located(name, program, true);
		return found.isEmpty() ? NIL : library(RESOURCES, "stream", List.of(string(octets(found.get(0).octets()))));
	}

	/** Octets as the library reads them: a text of one character an octet. */
	private static String octets(byte[] octets) {
		char[] text = new char[octets.length];
		for (int i = 0; i < octets.length; i++) {
			text[i] = (char) (octets[i] & 0xff);
		}
		return new String(text);
	}

	/**
	 * The URL of the resource a loader finds, where the program is compiled, or
	 * {@code nil}.
	 * @param program whether it is the program's loader, which looks in the program's
	 * source tree first
	 */
	private LispVal resourceUrl(String name, boolean program) {
		if (name.startsWith("/")) {
			return NIL;
		}
		List<JavaClasses.Located> found = this.classes.located(name, program, true);
		return found.isEmpty() ? NIL : url(found.get(0));
	}

	/** A new URL of a resource found, which holds its octets. */
	private LispVal url(JavaClasses.Located found) {
		return library(URL, "located", List.of(integer(found.kind()), string(found.place()), string(found.name()),
				string(octets(found.octets()))));
	}

	/**
	 * The URLs of every place a loader finds a resource in, as a list {@code URL.listed}
	 * makes an enumeration of.
	 */
	private LispVal urls(String name, boolean program) {
		LispVal list = library(URL, "none", List.of());
		if (!name.startsWith("/")) {
			for (JavaClasses.Located found : this.classes.located(name, program, false)) {
				list = library(URL, "more", List.of(list, url(found)));
			}
		}
		return list;
	}

	private static final String URL = "java.net.URL";

	/**
	 * A member of a class object answering by the class it stands for: {@code Class<X>}
	 * is the object of {@code X} alone; any other is told by its name at run time among
	 * the classes of the program and of its libraries under the type's bound, grouped by
	 * what each answers. Any other class answers {@code nil}.
	 */
	private LispVal byClass(Typed receiver, Function<@Nullable JavaClass, @Nullable Answer> answer) {
		JavaType argument = receiver.type() instanceof ClassType type && type.arguments().size() == 1
				? type.arguments().get(0) : null;
		if (argument != null && !(argument instanceof Wildcard) && !(argument instanceof Variable)) {
			Answer exact = answer.apply(locatedClass(argument));
			return call("progn", dereferenced(receiver.form()), exact == null ? NIL : exact.form());
		}
		JavaType upper = argument instanceof Wildcard wildcard ? wildcard.upper() : argument;
		JavaClass bound = upper == null ? null : locatedClass(upper);
		Map<String, Answer> answers = new LinkedHashMap<>();
		Map<String, List<LispVal>> classesByAnswer = new LinkedHashMap<>();
		for (JavaClass candidate : List.copyOf(this.classes.all())) {
			if (loader(candidate) != null && (bound == null || candidate.isSubclassOf(bound))) {
				Answer found = answer.apply(candidate);
				if (found != null) {
					answers.putIfAbsent(found.key(), found);
					classesByAnswer.computeIfAbsent(found.key(), key -> new ArrayList<>())
						.add(string(candidate.binaryName));
				}
			}
		}
		LispVal locatedName = library(CLASS, "located", List.of(receiver.form()));
		if (answers.isEmpty()) {
			return call("progn", locatedName, NIL);
		}
		LispSymbol located = temporary();
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("cond"));
		classesByAnswer.forEach((key, names) -> {
			List<LispVal> tests = new ArrayList<>();
			for (LispVal binaryName : names) {
				tests.add(call("equal", located, binaryName));
			}
			arms.add(list(form(cl("or"), tests), Objects.requireNonNull(answers.get(key)).form()));
		});
		return JavaForms.let(located, locatedName, List.of(list(arms)));
	}

	@Override
	public LispVal classResource(Invocation call, boolean url) {
		Typed receiver = Objects.requireNonNull(call.receiver());
		Typed argument = call.arguments().get(0);
		if (argument.constant() instanceof String name) {
			return byClass(receiver, declaration -> resourceBeside(declaration, name, url));
		}
		// the class object, then the name, each read once
		LispSymbol held = temporary();
		LispSymbol name = temporary();
		this.nonNull.add(held);
		LispVal chosen = byClass(new Typed(held, receiver.type()),
				declaration -> computedBeside(declaration, name, affixes(argument), url, call.pos()));
		return list(cl("let*"), list(list(held, receiver.form()), list(name, dereferenced(argument.form()))), chosen);
	}

	@Override
	public LispVal classLoader(Invocation call) {
		return byClass(Objects.requireNonNull(call.receiver()), this::loaderOf);
	}

	/**
	 * {@code getResourceAsStream}, {@code getResource} or {@code getResources} of a class
	 * loader, told at run time by how far it reaches -- the program's, the system's, the
	 * platform's, which finds none --; and the system's {@code getSystemResource..}: a
	 * name as it is.
	 * @param kind 0 the stream, 1 the URL, 2 every place's URL
	 */
	@Override
	public LispVal loaderResource(Invocation call, int kind) {
		Typed argument = call.arguments().get(0);
		if (!(argument.constant() instanceof String name)) {
			LispVal named = dereferenced(argument.form());
			carries(call.receiver() != null, affixes(argument), call.pos());
			if (kind != 0) {
				this.classes.carryPlaces();
			}
			return library(RESOURCE_TABLE, kind == 0 ? "find" : kind == 1 ? "findUrl" : "findAll",
					List.of(call.receiver() == null ? integer(1) : library(CLASS_LOADER, "reach", List.of(call.self())),
							named));
		}
		LispVal system = kind == 0 ? resourceStream(name, false)
				: kind == 1 ? resourceUrl(name, false) : urls(name, false);
		LispVal tree = name.startsWith("/") || !this.classes.treeFinds(name) ? null
				: kind == 0 ? resourceStream(name, true) : kind == 1 ? resourceUrl(name, true) : urls(name, true);
		LispVal found;
		if (call.receiver() == null) {
			found = system;
		}
		else {
			LispSymbol loader = temporary();
			LispVal roots = kind == 2
					? call("if", library(CLASS_LOADER, "searchesRoots", List.of(loader)), system,
							library(URL, "none", List.of()))
					: call("and", library(CLASS_LOADER, "searchesRoots", List.of(loader)), system);
			found = JavaForms.let(loader, call.self(), List.of(tree == null ? call("progn", loader, roots)
					: call("if", library(CLASS_LOADER, "searchesTree", List.of(loader)), tree, roots)));
		}
		return kind == 2 ? library(URL, "listed", List.of(found)) : found;
	}

	/**
	 * A class literal asked for a resource or its loader: the class is known, so no
	 * object of it is made. {@code null} for any other call.
	 */
	private @Nullable Typed literalResource(JavaSyntax.ClassLiteral literal, Call call) {
		boolean url = call.name().equals("getResource");
		if ((url || call.name().equals("getResourceAsStream")) && call.arguments().size() == 1) {
			Typed argument = expression(call.arguments().get(0), this.stringType);
			if (!isSubtype(argument.type(), this.stringType)) {
				throw error(call.arguments().get(0).pos(),
						"incompatible types: " + argument.type() + " cannot be converted to String");
			}
			JavaClass located = locatedClass(literalType(literal));
			ClassType stream = new ClassType(this.classes.library(url ? URL : "java.io.InputStream"), List.of());
			if (!(argument.constant() instanceof String name)) {
				LispVal named = dereferenced(argument.form());
				Answer found = computedBeside(located, named, affixes(argument), url, call.pos());
				return new Typed(found == null ? call("progn", named, NIL) : found.form(), stream);
			}
			Answer found = resourceBeside(located, name, url);
			return new Typed(found == null ? NIL : found.form(), stream);
		}
		if (call.name().equals("getClassLoader") && call.arguments().isEmpty()) {
			Answer found = loaderOf(locatedClass(literalType(literal)));
			return new Typed(found == null ? NIL : found.form(),
					new ClassType(this.classes.library(CLASS_LOADER), List.of()));
		}
		return null;
	}

	/**
	 * Notes the members of a class of the JDK a lookup names by constants -- a class
	 * literal, a literal name and class literals for the types --, which the compiled
	 * program then holds, as GraalVM's native image keeps what such a lookup names: the
	 * member found is then called, read or made by reflection ({@link #inProgram}). A
	 * computed name or type finds what the program holds anyway.
	 */
	private void literalLookup(JavaSyntax.ClassLiteral literal, Call call) {
		String lookup = call.name();
		boolean constructor = lookup.equals("getConstructor") || lookup.equals("getDeclaredConstructor");
		boolean field = lookup.equals("getField") || lookup.equals("getDeclaredField");
		if (!constructor && !field && !lookup.equals("getMethod") && !lookup.equals("getDeclaredMethod")) {
			return;
		}
		if (!(literalType(literal) instanceof ClassType named)) {
			return;
		}
		JavaJdkMembers.Type row = jdkRow(named.declaration());
		List<Expression> arguments = call.arguments();
		if (!constructor && (arguments.isEmpty() || !(arguments.get(0) instanceof JavaSyntax.StringLiteral))) {
			return;
		}
		if (row == null) {
			libraryLookup(named.declaration(), call, constructor, field);
			return;
		}
		String name = constructor ? "<init>" : ((JavaSyntax.StringLiteral) arguments.get(0)).value();
		List<String> types = new ArrayList<>();
		for (Expression argument : arguments.subList(constructor ? 0 : 1, arguments.size())) {
			if (!(argument instanceof JavaSyntax.ClassLiteral type)) {
				types = null;
				break;
			}
			JavaType written = this.types.erased(literalType(type));
			types.add(written instanceof ArrayType array ? descriptor(array)
					: written instanceof ClassType each ? each.declaration().binaryName : written.toString());
		}
		List<JavaJdkMembers.Member> candidates = field ? row.fields
				: constructor ? row.constructors : lookup.equals("getMethod") ? row.publicMethods : row.methods;
		literalMembers(candidates, name, types, null, field, constructor);
	}

	/**
	 * A lookup naming a member of a class of a library the program uses by constants -- a
	 * class literal, a literal name, class literals for the types --, which declares the
	 * member as an entry of reachability metadata would ({@link JavaClasses#folded}), as
	 * GraalVM's native image keeps what such a lookup names. A computed name or type
	 * declares nothing.
	 */
	private void libraryLookup(JavaClass declaration, Call call, boolean constructor, boolean field) {
		if (declaration.intrinsic || declaration.declaration == null || declaration.source == null
				|| !declaration.source.dependency()) {
			return;
		}
		List<Expression> arguments = call.arguments();
		List<String> types = new ArrayList<>();
		for (Expression argument : arguments.subList(constructor ? 0 : 1, arguments.size())) {
			if (!(argument instanceof JavaSyntax.ClassLiteral type)) {
				return;
			}
			types.add(metadataName(this.types.erased(literalType(type))));
		}
		JavaReachabilityMetadata.Declared declared = this.classes.folded.computeIfAbsent(declaration.binaryName,
				name -> new JavaReachabilityMetadata.Declared());
		boolean added = field ? declared.fields.add(((JavaSyntax.StringLiteral) arguments.get(0)).value())
				: declared.methods.add(JavaReachabilityMetadata
					.member(constructor ? "<init>" : ((JavaSyntax.StringLiteral) arguments.get(0)).value(), types));
		if (added) {
			this.classes.changed();
		}
	}

	/**
	 * {@code Class.forName} of a constant name: the class it names -- of the program, of
	 * a library on the source path or of the JDK the library has -- is found by it, as
	 * GraalVM's native image folds such a call into the class. A name of no class is not
	 * found ({@code ClassNotFoundException}).
	 */
	private void literalForName(String name) {
		JavaClass named = this.classes.metadataClass(name);
		if (named != null && named.absent == null && this.classes.findable.add(named)) {
			this.classes.changed();
		}
	}

	/**
	 * A lookup of a method handle naming a member of a class of the JDK by constants -- a
	 * class literal, a constant name, and {@code MethodType.methodType} of class
	 * literals, what it returns and what it takes --, which puts the member in the
	 * program as {@link #literalLookup} does.
	 */
	private void literalHandle(Call call) {
		boolean constructor = call.name().equals("findConstructor");
		List<Expression> arguments = call.arguments();
		if (arguments.size() != (constructor ? 2 : 3) || !(arguments.get(0) instanceof JavaSyntax.ClassLiteral literal)
				|| !(literalType(literal) instanceof ClassType named)
				|| (!constructor && !(arguments.get(1) instanceof JavaSyntax.StringLiteral))
				|| !(arguments.get(arguments.size() - 1) instanceof Call type) || !type.name().equals("methodType")
				|| type.arguments().isEmpty()) {
			return;
		}
		List<String> types = new ArrayList<>();
		for (Expression argument : type.arguments()) {
			if (!(argument instanceof JavaSyntax.ClassLiteral written)) {
				return;
			}
			JavaType erased = this.types.erased(literalType(written));
			types.add(erased instanceof ArrayType array ? descriptor(array)
					: erased instanceof ClassType each ? each.declaration().binaryName : erased.toString());
		}
		JavaJdkMembers.Type row = jdkRow(named.declaration());
		if (row == null) {
			return;
		}
		List<JavaJdkMembers.Member> candidates = new ArrayList<>(constructor ? row.constructors : row.methods);
		if (!constructor) {
			candidates.addAll(row.publicMethods);
		}
		literalMembers(candidates, constructor ? "<init>" : ((JavaSyntax.StringLiteral) arguments.get(1)).value(),
				types.subList(1, types.size()), constructor ? null : types.get(0), false, constructor);
	}

	/**
	 * Puts in the program the members of a class of the JDK of a name a lookup by
	 * constants names: of those types and of that return type, where it gives them.
	 */
	private void literalMembers(List<JavaJdkMembers.Member> candidates, String name, @Nullable List<String> types,
			@Nullable String returned, boolean field, boolean constructor) {
		for (JavaJdkMembers.Member member : candidates) {
			if (!member.name().equals(name) || (types != null && !field && !member.parameters().equals(types))
					|| (returned != null && !member.type().equals(returned))) {
				continue;
			}
			JavaClass owner = this.classes.jdkClass(member.owner().name);
			if (owner == null) {
				continue;
			}
			if (field) {
				Field held = field(owner, name);
				if (held != null && held.isStatic && held.form == null && held.accessor == null) {
					uses(held.owner);
				}
				continue;
			}
			Method implementation = jdkImplementation(owner, member.owner(), member);
			if (implementation == null) {
				continue;
			}
			if (implementation.emitter != null) {
				this.classes.intrinsicsCalled.add(implementation);
				continue;
			}
			called(implementation);
			uses(implementation.owner);
			if (constructor) {
				makes(owner);
			}
		}
	}

	/** What {@code Class<..>} of a type's class names: a primitive type's box. */
	private JavaType boxOf(JavaType type) {
		return type == Primitive.VOID ? this.classes.libraryType("java.lang.Void")
				: type instanceof Primitive primitive ? JavaIntrinsics.boxed(primitive, this.classes) : type;
	}

	/** {@code Name.class} as a value. */
	private Typed classLiteral(JavaSyntax.ClassLiteral literal) {
		JavaType named = literalType(literal);
		return new Typed(classValue(named), classType(boxOf(named)));
	}

	/** {@code getClass()} as a value: the class object of what the value is. */
	private Typed classOf(Call call) {
		Typed object = call.target() == null ? thisValue(call.pos()) : expression(call.target(), null);
		if (!object.type().isReference()) {
			throw error(call.pos(), object.type() + " cannot be dereferenced");
		}
		JavaType erased = this.types.erased(this.types.upward(object.type()));
		ClassType type = classType(new JavaType.Wildcard(erased, null));
		if (erased instanceof ArrayType array) {
			if (isExact(array)) {
				return new Typed(call("if", object.form(), classValue(array), runtimeCall("npe")), type);
			}
			asksArrayClasses();
			return new Typed(list(ARRAY_CLASS, object.form(), quote(arrayClass(array))), type);
		}
		if (!(erased instanceof ClassType named)) {
			throw error(call.pos(), object.type() + " cannot be dereferenced");
		}
		JavaClass declaration = named.declaration().present();
		if (this.classes.classOfs.add(declaration) && this.generationOver) {
			this.classes.changed();
		}
		if (holdsArrays(declaration)) {
			asksArrayClasses();
		}
		if (this.classes.enumConstants) {
			for (JavaClass candidate : List.copyOf(this.classes.all())) {
				if (candidate.isEnum && candidate.absent == null
						&& (declaration == this.objectType.declaration() || candidate.isSubclassOf(declaration))) {
					enumValues(candidate);
				}
			}
		}
		return new Typed(list(classOfName(declaration), object.form()), type);
	}

	/**
	 * {@code o.getClass().isArray()}: whether the object is an array, which needs no
	 * class object -- so a lambda, which has none, answers too. {@code null} raises.
	 */
	private LispVal isArray(Call call) {
		Typed object = call.target() == null ? thisValue(call.pos()) : expression(call.target(), null);
		if (!object.type().isReference()) {
			throw error(call.pos(), object.type() + " cannot be dereferenced");
		}
		LispSymbol value = temporary();
		LispVal array = this.types.erased(this.types.upward(object.type())) instanceof ArrayType ? T
				: arrayValue(value);
		return JavaForms.let(value, object.form(), List.of(call("if", value, array, runtimeCall("npe"))));
	}

	/**
	 * Whether a value is an array: a vector that is no string, and has no fill pointer as
	 * an {@code Object} of no other class has.
	 */
	private static LispVal arrayValue(LispSymbol value) {
		return call("and", call("vectorp", value), call("not", call("stringp", value)),
				call("not", call("array-has-fill-pointer-p", value)));
	}

	private static LispSymbol classOfName(JavaClass declaration) {
		return JavaNames.symbol(declaration.lispName() + "%class-of");
	}

	/** Whether a value typed by a class can be an array: every array is such a value. */
	private boolean holdsArrays(JavaClass declaration) {
		return declaration == this.objectType.declaration() || declaration.name.equals("java.io.Serializable")
				|| declaration.name.equals("java.lang.Cloneable");
	}

	/**
	 * Records that the program reads as a value the class of what can be an array its
	 * static type does not tell.
	 */
	private void asksArrayClasses() {
		asksArrayTypes();
		if (!this.classes.arrayClassValues) {
			this.classes.arrayClassValues = true;
			this.classes.changed();
		}
	}

	/**
	 * The class objects the program has as values, and the functions choosing one by what
	 * a value is. An object holds what the program asks of one: the classes above it
	 * where {@code getSuperclass} or {@code isAssignableFrom} is reached, the interfaces
	 * for the latter, the test for {@code isInstance} and {@code cast}, the constants for
	 * {@code getEnumConstants}. Each is made after what it names.
	 */
	private void classObjects(List<LispVal> forms, List<JavaClass> deepestFirst) {
		this.classInits = List.of();
		if (this.classes.classValues.isEmpty() && this.classes.classOfs.isEmpty()
				&& this.classes.nativeReflection.isEmpty() && !this.classes.arraysOfClasses
				&& this.classes.reached.stream()
					.noneMatch(method -> method.owner.name.equals(CLASS) && REFLECTED.contains(method.name))) {
			return;
		}
		this.classes.annotationsInFull = this.session != null || this.classes.annotationsRead
				|| this.classes.reachedMembers.contains(JavaIntrinsics.ANNOTATIONS_LISTED);
		JavaClass type = this.classes.library(CLASS);
		Set<String> reached = new HashSet<>();
		for (Method method : this.session != null ? type.methods : this.classes.reached) {
			// a later buffer may ask of an object what the one making it did not
			if (method.owner == type) {
				reached.add(method.name);
			}
		}
		reached.addAll(this.classes.nativeReflection);
		if (reached.stream().anyMatch(REFLECTED::contains)) {
			declare(reached.contains("forName"));
		}
		if (reached.contains("forName")) {
			// a class found by its name is a value
			for (JavaClass found : List.copyOf(this.classes.findable)) {
				classValue(raw(found));
			}
		}
		if (reached.contains("getEnumConstants") && !this.classes.enumConstants) {
			// what holds an enum's class object is lowered again calling its values()
			this.classes.enumConstants = true;
			this.classes.changed();
		}
		Effects saved = this.effects;
		this.effects = new Effects();
		for (JavaClass of : List.copyOf(this.classes.classOfs)) {
			forms.add(classOfFunction(of, deepestFirst));
		}
		if (this.classes.arrayClassValues) {
			forms.add(arrayClassFunction());
		}
		Map<JavaType, LispVal> made = new LinkedHashMap<>();
		List<LispVal> inits = new ArrayList<>();
		for (JavaType asked : List.copyOf(this.classes.classValues)) {
			classObject(asked, reached, made, inits, forms);
		}
		this.held = held(reached, true);
		this.named = held(reached, false);
		this.jdkTabled.clear();
		this.jdkTypePlaces.clear();
		this.programMembers = programMembers();
		boolean tables = memberTables(reached);
		if (tables != this.classes.memberTables) {
			// the natives asking for the members are lowered again
			this.classes.memberTables = tables;
			this.classes.changed();
		}
		Asked asked = asked();
		boolean constructors = reached.contains("getDeclaredConstructor") || reached.contains("getConstructor")
				|| reached.contains("newInstance");
		// the functions over the class objects, made again where making one made another
		// class object: each answers every object
		List<LispVal> generated;
		int before;
		do {
			before = made.size();
			generated = new ArrayList<>();
			this.jdkArms.clear(this.session != null ? this.session.jdkNumbers : this.jdkNumbers,
					this.session != null ? this.session.jdkArms : null);

			boolean methods = reached.contains("getDeclaredMethod") || reached.contains("getMethod");
			boolean fields = reached.contains("getDeclaredField") || reached.contains("getField");
			if (methods || fields) {
				classMemberFunctions(methods, fields, asked, reached, made, inits, generated);
			}
			if (tables) {
				classMembersFunction(asked, reached, made, inits, generated);
			}
			if (constructors) {
				generated.add(classConstructorFunction(asked, reached, made, inits, forms));
			}
			Making making = new Making(reached, made, inits, forms);
			if (reached.contains("generic0")) {
				generated.add(classGenericFunction(making));
			}
			if (reached.contains("components0")) {
				generated.add(classComponentsFunction(making));
			}
			if (reached.contains("annotations0")) {
				generated.add(classAnnotationsFunction(making));
			}
			if (reached.contains("parameters0")) {
				generated.add(classParametersFunction(making));
			}
			if (reached.contains("annotated0")) {
				generated.add(classAnnotatedFunction(making));
			}
			if (reached.contains("variableAnnotations0")) {
				generated.add(classVariableAnnotationsFunction(making));
			}
		}
		while (made.size() != before);
		if (this.session != null) {
			this.jdkArms.keep(this.session.jdkArms);
		}
		if (reached.contains("packageAnnotations0")) {
			forms.add(packageAnnotationsFunction());
		}
		if (this.jdkArms.used) {
			forms.addAll(jdkFunctions());
		}
		forms.addAll(generated);
		jdkNamedTexts(inits, forms);
		if (!this.jdkTypePlaces.isEmpty()) {
			// the class objects the texts of the JDK's members name, by their places:
			// made after them
			List<LispVal> named = new ArrayList<>();
			for (JavaType placed : this.jdkTypePlaces.keySet()) {
				named.add(Objects.requireNonNull(made.get(placed)));
			}
			forms.add(list(cl("defvar"), JDK_TYPES, NIL));
			inits.add(call("setq", JDK_TYPES, form(cl("vector"), named)));
		}

		if (this.classes.arraysOfClasses) {
			LispVal types = this.classes.arrayTypesMade ? arrayTypeFunction(reached, made, inits, forms) : null;
			forms.add(arrayOfFunction(made));
			if (types != null) {
				forms.add(types);
			}
		}
		if (reached.contains("getDeclaredMethods")) {
			forms.add(declaredMethodsFunction(made));
		}
		this.programMembers = Map.of();
		if (reached.contains("forName")) {
			forms.add(classNamedFunction(made));
			forms.add(classInitializeFunction(made));
		}
		if (reached.contains("getAnnotation") || reached.contains("getDeclaredAnnotation")) {
			forms.add(classAnnotationFunction(made));
		}
		if (reached.contains("getModifiers")) {
			forms.add(classModifiersFunction(made));
		}
		this.effects = saved;
		this.classInits = inits;
	}

	/**
	 * Makes the reachability metadata count where the program reflects
	 * ({@link JavaClasses#declared}): the entries whose condition names a class the
	 * program reaches, or none, joined by class with what lookups by constants name of a
	 * library's classes. A class an entry names is found by a name the program computes
	 * where it calls {@code Class.forName}; a member of the JDK's an entry names is put
	 * in the program, as a lookup by constants puts one.
	 * @param found whether the program calls {@code Class.forName}
	 */
	private void declare(boolean found) {
		Set<String> types = reachedTypes();
		Map<String, JavaReachabilityMetadata.Declared> entries = new LinkedHashMap<>();
		for (JavaReachabilityMetadata.Entry entry : this.classes.metadata()) {
			String condition = entry.condition();
			if (condition == null || types.contains(condition)) {
				entries.computeIfAbsent(entry.type(), name -> new JavaReachabilityMetadata.Declared()).add(entry);
			}
		}
		// with what lookups by constants declare, each class's joined anew
		Map<String, JavaReachabilityMetadata.Declared> declared = new LinkedHashMap<>();
		for (Map<String, JavaReachabilityMetadata.Declared> source : List.of(entries, this.classes.folded)) {
			source.forEach(
					(name, written) -> declared.computeIfAbsent(name, key -> new JavaReachabilityMetadata.Declared())
						.add(written));
		}
		if (!declared.equals(this.classes.declared)) {
			// what answers and runs by reflection is lowered again
			this.classes.declared = Map.copyOf(declared);
			this.classes.changed();
		}
		for (Map.Entry<String, JavaReachabilityMetadata.Declared> entry : entries.entrySet()) {
			JavaClass type = found || JavaClasses.isPlatform(entry.getKey())
					? this.classes.metadataClass(entry.getKey()) : null;
			if (type == null || type.absent != null) {
				continue;
			}
			if (found && this.classes.findable.add(type)) {
				this.classes.changed();
			}
			JavaJdkMembers.Type row = jdkRow(type);
			if (row != null) {
				jdkMetadataMembers(row, entry.getValue());
			}
		}
	}

	/**
	 * The binary names of the classes the program reaches, as a condition of the metadata
	 * asks (GraalVM's {@code typeReached}: a class is reached where it is initialized, or
	 * a class under it is): every class of the program, which is lowered whole, and every
	 * class of the JDK's that is no Java source of the library; a class of a library or
	 * of the library's Java source whose static initialization or an instance of which
	 * the program needs; and the classes and interfaces above each.
	 */
	private Set<String> reachedTypes() {
		Set<String> names = new HashSet<>();
		Deque<JavaClass> pending = new ArrayDeque<>();
		for (JavaClass declaration : this.classes.all()) {
			if (!isPartial(declaration)) {
				pending.add(declaration);
			}
		}
		pending.addAll(this.classes.used);
		pending.addAll(this.classes.made);
		while (!pending.isEmpty()) {
			JavaClass type = pending.pop();
			if (!names.add(type.binaryName)) {
				continue;
			}
			if (type.superclass != null) {
				pending.add(type.superclass);
			}
			pending.addAll(type.interfaces);
		}
		return names;
	}

	/**
	 * Puts in the program the members of a class of the JDK an entry of the metadata
	 * declares, as a lookup naming them by constants does ({@link #literalMembers}): its
	 * methods and constructors by name and types, its fields by name, and every member of
	 * a kind it declares whole that the JDK's table lists (the public and protected
	 * ones).
	 */
	private void jdkMetadataMembers(JavaJdkMembers.Type row, JavaReachabilityMetadata.Declared declared) {
		List<JavaJdkMembers.Member> methods = new ArrayList<>(row.methods);
		methods.addAll(row.publicMethods);
		for (String key : declared.methods) {
			int open = key.indexOf('(');
			String name = key.substring(0, open);
			String inside = key.substring(open + 1, key.length() - 1);
			List<String> types = null;
			if (!inside.equals("*")) {
				types = new ArrayList<>();
				for (String type : inside.isEmpty() ? new String[0] : inside.split(",")) {
					types.add(descriptorName(type));
				}
			}
			boolean constructor = name.equals("<init>");
			literalMembers(constructor ? row.constructors : methods, name, types, null, false, constructor);
		}
		for (String field : declared.fields) {
			literalMembers(row.fields, field, null, null, true, false);
		}
		int flags = declared.flags;
		for (JavaJdkMembers.Member member : row.methods) {
			if ((flags & JavaReachabilityMetadata.DECLARED_METHODS) != 0) {
				literalMembers(List.of(member), member.name(), member.parameters(), null, false, false);
			}
		}
		for (JavaJdkMembers.Member member : row.publicMethods) {
			if ((flags & JavaReachabilityMetadata.PUBLIC_METHODS) != 0) {
				literalMembers(List.of(member), member.name(), member.parameters(), null, false, false);
			}
		}
		for (JavaJdkMembers.Member member : row.fields) {
			if ((flags & JavaReachabilityMetadata.DECLARED_FIELDS) != 0
					|| ((flags & JavaReachabilityMetadata.PUBLIC_FIELDS) != 0 && member.isPublic())) {
				literalMembers(List.of(member), member.name(), null, null, true, false);
			}
		}
		for (JavaJdkMembers.Member member : row.constructors) {
			if ((flags & JavaReachabilityMetadata.DECLARED_CONSTRUCTORS) != 0
					|| ((flags & JavaReachabilityMetadata.PUBLIC_CONSTRUCTORS) != 0 && member.isPublic())) {
				literalMembers(List.of(member), "<init>", member.parameters(), null, false, true);
			}
		}
	}

	/**
	 * A type as the JDK's table names it, of the name reachability metadata gives it: an
	 * array by its descriptor ({@code [Ljava.lang.String;}, {@code [I}).
	 */
	private static String descriptorName(String name) {
		if (!name.endsWith("[]")) {
			return name;
		}
		String element = name;
		StringBuilder dimensions = new StringBuilder();
		while (element.endsWith("[]")) {
			dimensions.append('[');
			element = element.substring(0, element.length() - 2);
		}
		String letter = switch (element) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			default -> "L" + element + ";";
		};
		return dimensions + letter;
	}

	/**
	 * The function answering, of a class object, what the run time records of an array of
	 * its class ({@link #arrayRecord}): of each class object made, its own; of an array
	 * type's made where the program runs, the one of an array of its component's arrays.
	 */
	private LispVal arrayOfFunction(Map<JavaType, LispVal> made) {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (Map.Entry<JavaType, LispVal> object : made.entrySet()) {
			if (object.getKey() != Primitive.VOID) {
				arms.add(list(call("eq", RECEIVER, object.getValue()),
						quote(arrayRecord(new ArrayType(object.getKey())))));
			}
		}
		LispVal component = list(slotAccessor(this.classes.library(CLASS), "component"), RECEIVER);
		arms.add(list(T, runtimeCall("array-nest", list(ARRAY_OF, component))));
		return list(cl("defun"), ARRAY_OF, list(RECEIVER), list(arms));
	}

	/**
	 * The function answering a class object's array type's: of each array type whose
	 * class object is made, that object; of any other the one made where the program
	 * runs, the first made of its name -- an array's {@code Class}, its superclass,
	 * interfaces and test as the program asks of those made before it runs.
	 */
	private LispVal arrayTypeFunction(Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits,
			List<LispVal> forms) {
		boolean around = reached.contains("isAssignableFrom") || reached.contains("getInterfaces");
		boolean above = around || reached.contains("getSuperclass");
		LispVal superclass = above ? classObject(this.objectType, reached, made, inits, forms) : NIL;
		LispVal interfaces = NIL;
		if (around) {
			interfaces = call("vector",
					classObject(raw(this.classes.library("java.lang.Cloneable")), reached, made, inits, forms),
					classObject(raw(this.classes.library("java.io.Serializable")), reached, made, inits, forms));
		}
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (Map.Entry<JavaType, LispVal> object : made.entrySet()) {
			if (object.getKey() instanceof ArrayType array) {
				LispVal component = made.get(this.types.erased(array.element()));
				if (component != null) {
					arms.add(list(call("eq", RECEIVER, component), object.getValue()));
				}
			}
		}
		LispSymbol record = new LispSymbol("%a");
		LispVal name = call("car", call("cdr", record));
		LispVal test = NIL;
		if (reached.contains("isInstance") || reached.contains("cast")) {
			LispSymbol value = new LispSymbol("%v");
			test = list(cl("lambda"), list(value), runtimeCall("array-is", value, name, NIL));
		}
		Method constructor = constructors(this.classes.library(CLASS)).get(0);
		reach(constructor);
		makes(constructor.owner);
		LispVal object = form(JavaNames.creator(constructor), List.of(name, call("car", record), NIL,
				integer(CLASS_ARRAY), superclass, interfaces, RECEIVER, test, NIL));
		arms.add(list(T, JavaForms.let(record, list(ARRAY_OF, RECEIVER),
				List.of(runtimeCall("array-type", name, list(cl("lambda"), NIL, object))))));
		return list(cl("defun"), ARRAY_TYPE, list(RECEIVER), list(arms));
	}

	/** The members of {@code Class} a function over the class objects answers. */
	private static final Set<String> REFLECTED = Set.of("getDeclaredMethods", "forName", "getAnnotation",
			"getDeclaredAnnotation", "getDeclaredConstructor", "getDeclaredMethod", "getMethod", "getDeclaredField",
			"getField", "getInterfaces", "getMethods", "getDeclaredFields", "getFields", "getDeclaredConstructors",
			"getConstructors", "getConstructor", "getModifiers", "newInstance", "generic0", "annotations0",
			"components0", "parameters0", "annotated0", "variableAnnotations0");

	/**
	 * The function {@code Class.getModifiers()} asks: for each class object made of a
	 * class of the program, the modifiers its declaration writes and the language gives
	 * it as a JDK's reflection answers them -- a member class {@code static} where it is,
	 * an interface {@code abstract} and an interface, an enum or a record {@code static}
	 * and {@code final} where nothing under it is, an enum's bit (0x4000), a local or an
	 * anonymous class none but {@code final}, {@code abstract} and {@code static} --; an
	 * array type's {@code public abstract final}, with its element's access, and a
	 * primitive type's {@code public abstract final}; a class of the JDK's as its table
	 * has them; {@code NIL} for a class of a library, refused where the program runs.
	 */
	private LispVal classModifiersFunction(Map<JavaType, LispVal> made) {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (Map.Entry<JavaType, LispVal> object : made.entrySet()) {
			Integer modifiers = classModifiers(object.getKey());
			if (modifiers != null) {
				arms.add(list(call("eq", RECEIVER, object.getValue()), integer(modifiers)));
			}
		}
		if (this.classes.arrayTypesMade) {
			// an array type's made where the program runs, with its element's access
			LispVal component = list(slotAccessor(this.classes.library(CLASS), "component"), RECEIVER);
			arms.add(list(component,
					call("logior", integer(1040), call("logand", integer(7), list(CLASS_MODIFIERS, component)))));
		}
		arms.add(list(T, library(CLASS, "unknownModifiers", List.of(RECEIVER))));
		return list(cl("defun"), CLASS_MODIFIERS, list(RECEIVER), list(arms));
	}

	private @Nullable Integer classModifiers(JavaType type) {
		if (type instanceof Primitive) {
			return 1041;
		}
		if (type instanceof ArrayType array) {
			Integer element = classModifiers(this.types.erased(array.element()));
			return element == null ? null : 1040 | (element & 7);
		}
		if (!(type instanceof ClassType named)) {
			return null;
		}
		JavaClass declaration = named.declaration();
		if (!reflected(declaration)) {
			// a class of the JDK's, as its table has it; none of a library's
			JavaJdkMembers.Type row = declaration.source != null && declaration.source.dependency() ? null
					: JavaJdkMembers.shipped().of(declaration.binaryName);
			return row == null ? null : row.modifiers;
		}
		Set<String> written = Objects.requireNonNull(declaration.declaration).modifiers();
		int modifiers = bits(written) & (declaration.isLocal ? 0x418 : 0xC1F);
		if (declaration.isInterface) {
			modifiers |= 0x600 | (declaration.outer != null ? 8 : 0) | (declaration.isAnnotation ? 0x2000 : 0);
		}
		if (declaration.isEnum || declaration.isRecord) {
			modifiers |= declaration.outer != null && !declaration.isLocal ? 8 : 0;
			boolean extended = false;
			for (JavaClass candidate : this.classes.all()) {
				extended |= candidate != declaration && candidate.superclass == declaration;
			}
			if (!extended) {
				modifiers |= 16;
			}
		}
		if (declaration.isEnum) {
			modifiers |= 0x4000;
		}
		if (declaration.outer != null && declaration.outer.isInterface) {
			modifiers |= 9;
		}
		return modifiers & ~0x800;
	}

	@Override
	public LispVal arrays(Invocation call, int kind) {
		switch (kind) {
			case 0:
				// the class the run time records tells an array of long from one of
				// references, an array of short from one of char
				asksArrayTypes();
				return runtimeCall("array-kind", call.argument(0));
			case 1:
				if (!this.classes.arraysOfClasses) {
					this.classes.arraysOfClasses = true;
					this.classes.changed();
				}
				return list(ARRAY_OF, call.argument(0));
			case 2: {
				// an array made of a class object has its class object where the program
				// can ask an array its class object: made by the first that asks
				if (!this.classes.arrayClassValues) {
					return NIL;
				}
				arrayTypesMade();
				LispSymbol type = temporary();
				LispSymbol levels = temporary();
				return list(cl("let"), list(list(type, call.argument(0)), list(levels, call.argument(1))),
						list(cl("while"), call(">", levels, integer(0)), call("setq", type, list(ARRAY_TYPE, type)),
								call("setq", levels, call("-", levels, integer(1)))),
						NIL);
			}
			default:
				arrayTypesMade();
				return list(ARRAY_TYPE, call.argument(0));
		}
	}

	/**
	 * What a call by reflection can run: any method a class object answers -- of no
	 * parameter, or any where calls give arguments.
	 */
	private void reflectiveCalls() {
		for (JavaType held : List.copyOf(this.classes.classValues)) {
			if (held instanceof ClassType named && reflected(named.declaration())) {
				for (Method method : declaredMethodList(named.declaration())) {
					if (method == null || (method.parameters.size() != method.hidden && !this.classes.reflectsArguments)
							|| !runs(named.declaration(), method)) {
						continue;
					}
					called(method);
					uses(method.owner);
					if (!method.isStatic && !method.isPrivate) {
						bodies(method.owner, method).forEach(body -> called(body.method()));
					}
				}
			}
		}
	}

	/** What making an instance by reflection with arguments can run: any constructor. */
	private void reflectiveConstructions() {
		for (JavaType held : List.copyOf(this.classes.classValues)) {
			if (held instanceof ClassType named && reflected(named.declaration()) && !named.declaration().isAbstract
					&& !named.declaration().isEnum && !named.declaration().isLocal
					&& !named.declaration().isInterface) {
				for (Method constructor : constructors(named.declaration())) {
					if (runs(named.declaration(), constructor)) {
						called(constructor);
						uses(constructor.owner);
					}
				}
			}
		}
	}

	@Override
	public LispVal handles(Invocation call, int kind) {
		if (kind == 0) {
			// MethodHandles.lookup(): of the class calling, which holds its class object
			return library(LOOKUP, "made",
					List.of(classValue(raw(this.currentClass)), callerPackage(), callerTopLevel(), caller()));
		}
		// a handle's call gives arguments: any member a class object answers can run
		if (!this.classes.reflectsArguments) {
			this.classes.reflectsArguments = true;
			this.classes.changed();
		}
		if (kind == 1) {
			reflectiveCalls();
			return library(METHOD, "handled", List.of(call.argument(0), call.argument(1), call.argument(2)));
		}
		reflectiveConstructions();
		return library(CONSTRUCTOR, "handled", List.of(call.argument(0), call.argument(1)));
	}

	/** Records that the program makes the class object of an array type where it runs. */
	private void arrayTypesMade() {
		if (!this.classes.arrayTypesMade) {
			this.classes.arrayTypesMade = true;
			this.classes.arraysOfClasses = true;
			this.classes.changed();
		}
	}

	@Override
	public LispVal classReflection(Invocation call, int kind) {
		switch (kind) {
			case 0:
				return list(CLASS_NAMED, call.argument(0));
			case 1:
				// what it finds is initialized here
				this.classes.findable.forEach(this::uses);
				return list(CLASS_INITIALIZE, call.argument(0));
			case 2:
				return list(CLASS_ANNOTATION, call.argument(0), call.argument(1), call.argument(2));
			case 4:
				// who may call a constructor is decided by the class calling; with
				// arguments one of parameters can make any instance a class object
				// answers one of
				if (this.classes.reflectsArguments) {
					reflectiveConstructions();
				}
				return library(CONSTRUCTOR, "made",
						List.of(call.self(), call.argument(0), callerPackage(), callerTopLevel(), caller()));
			case 5:
				return list(CLASS_METHOD, call.argument(0), call.argument(1));
			case 6:
				return list(CLASS_FIELD, call.argument(0), call.argument(1));
			case 7:
				// a call by a Method can run any method a class object answers -- of no
				// parameter, or any where calls give arguments --, as the class calling
				// may
				reflectiveCalls();
				return library(METHOD, "invoked", List.of(call.self(), call.argument(0), call.argument(1),
						callerPackage(), callerTopLevel(), caller(), under()));
			case 8:
				// a read by a Field can read any static field a class object answers
				for (JavaType held : List.copyOf(this.classes.classValues)) {
					if (held instanceof ClassType named && reflected(named.declaration())) {
						for (Field field : reflectedFields(named.declaration())) {
							if (field.isStatic && runs(named.declaration(), field)) {
								this.effects.reads.add(field);
								uses(field.owner);
							}
						}
					}
				}
				return library(FIELD, "read",
						List.of(call.self(), call.argument(0), callerPackage(), callerTopLevel(), caller(), under()));
			case 9:
				return this.classes.memberTables ? list(CLASS_MEMBERS, call.argument(0), call.argument(1)) : NIL;
			case 11:
				written();
				return library(FIELD, "written", List.of(call.self(), call.argument(0), call.argument(1),
						callerPackage(), callerTopLevel(), caller(), under()));
			case 14:
				asks("getModifiers");
				return list(CLASS_MODIFIERS, call.self());
			case 15:
				asks("newInstance");
				// Class.newInstance(): the constructor of no parameter, as the class
				// calling may call it, what it throws thrown as it is
				for (JavaType held : List.copyOf(this.classes.classValues)) {
					Method constructor = held instanceof ClassType named ? nullaryConstructor(named.declaration())
							: null;
					if (constructor != null) {
						called(constructor);
						uses(constructor.owner);
					}
				}
				return library(CONSTRUCTOR, "instance", List.of(call.self(), list(CLASS_CONSTRUCTOR, call.self()),
						callerPackage(), callerTopLevel(), caller()));
			case 16:
				return raise(call.argument(0));
			case 18:
				return this.classes.reflectsTypes || this.session != null ? T : NIL;
			case 17:
				// the arguments a call of a member found by reflection gives: converted
				// where a call gives any, which no call of a program giving none does (a
				// session's later buffer may)
				return this.classes.reflectsArguments || this.session != null
						? library(EXECUTABLE, "converted", List.of(call.argument(0), call.argument(1)))
						: call.argument(1);
			case 19:
				// a constructor made of arguments: no function takes any where no call
				// gives them
				return this.classes.reflectsArguments || this.session != null
						? madeCall(call("funcall", call.argument(0), call.argument(1))) : NIL;
			case 40:
				asks("generic0");
				return list(CLASS_GENERIC, call.argument(0), call.argument(1), call.argument(2), call.argument(3));
			case 41:
				asks("annotations0");
				return list(CLASS_ANNOTATIONS, call.argument(0), call.argument(1), call.argument(2));
			case 42:
				asks("components0");
				return list(CLASS_COMPONENTS, call.argument(0));
			case 43:
				asks("parameters0");
				return list(CLASS_PARAMETERS, call.argument(0), call.argument(1));
			case 44:
				asks("packageAnnotations0");
				return list(PACKAGE_ANNOTATIONS, call.argument(0));
			case 45:
				asks("annotated0");
				return list(CLASS_ANNOTATED, call.argument(0), call.argument(1), call.argument(2));
			case 46:
				asks("variableAnnotations0");
				return list(CLASS_VARIABLE_ANNOTATIONS, call.argument(0), call.argument(1), call.argument(2));
			case 3:
				break;
			default:
				if (kind >= 20 && kind < 28) {
					// a typed read: the value as the primitive type of the member's name
					Primitive as = TYPED.get(kind - 20);
					reads();
					LispVal read = library(FIELD, "readAs", List.of(call.self(), call.argument(0), classValue(as),
							callerPackage(), callerTopLevel(), caller(), under()));
					return unbox(new Typed(read, JavaIntrinsics.boxed(as, this.classes))).form();
				}
				if (kind >= 30 && kind < 38) {
					Primitive as = TYPED.get(kind - 30);
					written();
					return library(FIELD, "writtenAs",
							List.of(call.self(), call.argument(0), box(new Typed(call.argument(1), as)).form(),
									callerPackage(), callerTopLevel(), caller(), under()));
				}
				break;
		}
		// 3: what makes an instance by a class object can make any it has one of
		for (JavaType held : List.copyOf(this.classes.classValues)) {
			Method constructor = held instanceof ClassType named ? nullaryConstructor(named.declaration()) : null;
			if (constructor != null) {
				called(constructor);
				uses(constructor.owner);
			}
		}
		return list(CLASS_CONSTRUCTOR, call.argument(0));
	}

	/**
	 * Notes a native member of {@code Class} the program calls
	 * ({@link JavaClasses#nativeReflection}).
	 */
	private void asks(String member) {
		if (this.classes.nativeReflection.add(member)) {
			this.classes.changed();
		}
	}

	/**
	 * The primitive types of the typed reads and writes of {@code Field}, by their place.
	 */
	private static final List<Primitive> TYPED = List.of(Primitive.BOOLEAN, Primitive.BYTE, Primitive.CHAR,
			Primitive.SHORT, Primitive.INT, Primitive.LONG, Primitive.FLOAT, Primitive.DOUBLE);

	/**
	 * The package of the class calling a member reflection checks the access to, told it.
	 */
	private LispVal callerPackage() {
		return string(Objects.requireNonNullElse(this.unit.packageName(), ""));
	}

	private LispVal callerTopLevel() {
		return string(this.currentClass.topLevel().binaryName);
	}

	/**
	 * The class calling a member reflection checks the access to, with the classes above
	 * it, each after a space: whose protected members it may use.
	 */
	private LispVal caller() {
		StringBuilder names = new StringBuilder(this.currentClass.binaryName);
		JavaClass type = this.currentClass;
		while (!type.isInterface && type != this.objectType.declaration()) {
			type = type.superclass != null ? type.superclass
					: type.intrinsicSuperclass != null ? type.intrinsicSuperclass : this.objectType.declaration();
			names.append(' ').append(type.binaryName);
		}
		return string(names.toString());
	}

	/**
	 * The function telling whether a value is an instance of the class calling a member:
	 * what a protected member of another package may be used on.
	 */
	private LispVal under() {
		LispSymbol value = new LispSymbol("%v");
		return list(cl("lambda"), list(value, new LispSymbol("%a")),
				instanceTest(value, this.objectType, raw(this.currentClass), 0, "instanceof"));
	}

	/** A read by a Field can read any static field a class object answers. */
	private void reads() {
		for (JavaType held : List.copyOf(this.classes.classValues)) {
			if (held instanceof ClassType named && reflected(named.declaration())) {
				for (Field field : reflectedFields(named.declaration())) {
					if (field.isStatic && runs(named.declaration(), field)) {
						this.effects.reads.add(field);
						uses(field.owner);
					}
				}
			}
		}
	}

	/** Whether the program writes a field by reflection: {@code set} or a typed write. */
	private boolean writesFields() {
		return this.session != null || reachedMember(FIELD, "written") || reachedMember(FIELD, "writtenAs");
	}

	/** A write by a Field can write any static field a class object answers. */
	private void written() {
		for (JavaType held : List.copyOf(this.classes.classValues)) {
			if (held instanceof ClassType named && reflected(named.declaration())) {
				for (Field field : reflectedFields(named.declaration())) {
					if (field.isStatic && runs(named.declaration(), field)) {
						this.effects.writes.add(field);
						uses(field.owner);
					}
				}
			}
		}
	}

	/**
	 * The function {@code Class.forName} asks: the object of the class of a binary name
	 * among the classes an index of annotated classes lists, {@code NIL} for any other
	 * name.
	 */
	private LispVal classNamedFunction(Map<JavaType, LispVal> made) {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (JavaClass found : this.classes.findable) {
			LispVal object = made.get(raw(found));
			if (object != null) {
				arms.add(list(call("equal", RECEIVER, string(found.binaryName)), object));
			}
		}
		return list(cl("defun"), CLASS_NAMED, list(RECEIVER), list(arms));
	}

	/**
	 * The function running the static initialization of a class found by its name, where
	 * the class is initialized at its first use.
	 */
	private LispVal classInitializeFunction(Map<JavaType, LispVal> made) {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (JavaClass found : this.classes.findable) {
			LispVal object = made.get(raw(found));
			LispVal initialization = initializationOf(found);
			if (object != null && initialization != null) {
				arms.add(list(call("eq", RECEIVER, object), initialization));
			}
		}
		return list(cl("defun"), CLASS_INITIALIZE, list(RECEIVER), list(arms), NIL);
	}

	/**
	 * The function {@code Class.getAnnotation} asks: for each class object made of a
	 * class carrying an annotation whose type's object the program has, a new annotation
	 * ({@link JavaClasses#annotationHolder}); one a class above carries where its type is
	 * inherited and the declared ones are not asked for; {@code NIL} for any other.
	 * @param made the variables holding the objects, by type
	 */
	private LispVal classAnnotationFunction(Map<JavaType, LispVal> made) {
		LispSymbol asked = new LispSymbol("%a0");
		LispSymbol declared = new LispSymbol("%a1");
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (Map.Entry<JavaType, LispVal> object : List.copyOf(made.entrySet())) {
			if (!(object.getKey() instanceof ClassType named)) {
				continue;
			}
			JavaJdkMembers.Type row = jdkRow(named.declaration());
			if (row != null) {
				// a class of the JDK's, as its table has them
				for (String text : row.annotations) {
					String name = text.substring(1, text.indexOf('('));
					LispVal type = null;
					for (Map.Entry<JavaType, LispVal> each : made.entrySet()) {
						if (each.getKey() instanceof ClassType held && held.declaration().binaryName.equals(name)) {
							type = each.getValue();
						}
					}
					if (type != null) {
						arms.add(list(call("and", call("eq", RECEIVER, object.getValue()), call("eq", asked, type)),
								jdkAnnotation(text, new Making(Set.of(), made, new ArrayList<>(), new ArrayList<>()))));
					}
				}
				continue;
			}
			Set<JavaClass> seen = new HashSet<>();
			for (JavaClass carrier = named.declaration(); carrier != null; carrier = carrier.superclass) {
				boolean own = carrier == named.declaration();
				for (JavaClasses.Carried carried : this.classes.annotations(carrier)) {
					LispVal type = made.get(raw(carried.type()));
					if (type == null || !seen.add(carried.type())
							|| !(own || this.classes.isInherited(carried.type()))) {
						continue;
					}
					List<LispVal> tests = new ArrayList<>(
							List.of(call("eq", RECEIVER, object.getValue()), call("eq", asked, type)));
					if (!own) {
						tests.add(call("not", declared));
					}
					arms.add(list(form(cl("and"), tests),
							library(this.classes.annotationHolder(carrier), "a" + carried.index(), List.of())));
				}
				if (named.declaration().isInterface) {
					break;
				}
			}
		}
		return list(cl("defun"), CLASS_ANNOTATION, list(RECEIVER, asked, declared), list(arms));
	}

	// ---- generic signatures, a record's components and the annotations of members
	// (.kb/java-frontend.md, "Generic types, records and the annotations of members")

	private static final String TYPES = "java.lang.reflect.Types";

	/**
	 * The functions answering a declaration's generic signature, the annotations of a
	 * class and of the declarations in its body, a record's components, what a compiler
	 * keeps of a parameter, and the annotations of a package.
	 */
	private static final LispSymbol CLASS_GENERIC = new LispSymbol("%class-generic");

	private static final LispSymbol CLASS_ANNOTATIONS = new LispSymbol("%class-annotations");

	private static final LispSymbol CLASS_COMPONENTS = new LispSymbol("%class-components");

	private static final LispSymbol CLASS_PARAMETERS = new LispSymbol("%class-parameters");

	private static final LispSymbol PACKAGE_ANNOTATIONS = new LispSymbol("%package-annotations");

	private static final LispSymbol CLASS_ANNOTATED = new LispSymbol("%class-annotated");

	private static final LispSymbol CLASS_VARIABLE_ANNOTATIONS = new LispSymbol("%class-variable-annotations");

	/**
	 * Where the forms making class objects go while a function over them is made: the
	 * names of the members of {@code Class} the program reaches, the objects made so far,
	 * the forms making them and the variables' declarations.
	 */
	private record Making(Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
	}

	private LispVal classObject(@Nullable JavaType type, Making making) {
		return classObject(type, making.reached(), making.made(), making.inits(), making.forms());
	}

	/**
	 * The function {@code Types.generic0} asks: for each class object made of a class
	 * whose members reflection answers ({@link #reflected}), by the declaration --
	 * {@code NIL} the class, a method's place among those {@code getDeclaredMethods()}
	 * lists, -1 less a constructor's place, a field's name -- and what is asked -- 0 the
	 * type parameters, 1 the class's generic superclass, a method's return type or a
	 * field's type, 2 the class's interfaces or the parameters' types, 3 the types
	 * thrown, 10 and on each type parameter's bounds -- the types as the declaration
	 * names them, made of the class objects of the classes they name; {@code NIL} where
	 * it names no generic type there, which reflection answers with the erased types. A
	 * type variable of the method or the constructor asked is of the declaration the
	 * function is told ({@code %d}). Any other class is refused where the program runs.
	 */
	private LispVal classGenericFunction(Making making) {
		LispSymbol member = new LispSymbol("%m");
		LispSymbol what = new LispSymbol("%w");
		LispSymbol asking = new LispSymbol("%d");
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> none = new ArrayList<>(List.of(cl("or")));
		Set<JavaType> answered = new HashSet<>();
		boolean more = true;
		while (more) {
			more = false;
			for (Map.Entry<JavaType, LispVal> object : List.copyOf(making.made().entrySet())) {
				if (!answered.add(object.getKey())) {
					continue;
				}
				more = true;
				if (!(object.getKey() instanceof ClassType named)) {
					continue;
				}
				JavaClass declaration = named.declaration();
				JavaJdkMembers.Type row = reflected(declaration) ? null : jdkRow(declaration);
				if (row == null && !reflected(declaration)) {
					// any other class is the last arm's
					continue;
				}
				LispVal answer = row != null ? jdkGenerics(declaration, row, member, what, asking, making)
						: declaredGenerics(declaration, member, what, asking, making);
				LispVal test = call("eq", RECEIVER, object.getValue());
				if (answer == NIL) {
					none.add(test);
				}
				else {
					arms.add(list(test, answer));
				}
			}
		}
		if (none.size() > 1) {
			arms.add(list(list(none), NIL));
		}
		arms.add(list(T, library(TYPES, "unknown", List.of(RECEIVER, what))));
		return list(cl("defun"), CLASS_GENERIC, list(RECEIVER, member, what, asking), list(arms));
	}

	/**
	 * What a class of the program answers of its declarations' generic signatures, by the
	 * declaration and what is asked ({@link #classGenericFunction}), or {@code NIL} where
	 * none names a generic type.
	 */
	private LispVal declaredGenerics(JavaClass declaration, LispSymbol member, LispSymbol what, LispSymbol asking,
			Making making) {
		LispVal object = classObject(raw(declaration), making);
		List<LispVal> byMember = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> own = new ArrayList<>();
		typeParameterAnswers(declaration.typeParameters, object, object, NIL, null, asking, own, making);
		if (!declaration.isInterface && declaration.superclass != null) {
			ClassType above = declaration.supertype(declaration.superclass);
			if (declaration.isEnum && above.arguments().isEmpty()
					&& declaration.superclass.name.equals("java.lang.Enum")) {
				// an enum extends Enum of itself
				above = new ClassType(declaration.superclass, List.of(raw(declaration)));
			}
			if (generic(above)) {
				own.add(list(integer(1), genericType(above, null, asking, making)));
			}
		}
		List<JavaType> implemented = new ArrayList<>();
		for (JavaClass each : declaration.interfaces) {
			implemented.add(declaration.supertype(each));
		}
		if (implemented.stream().anyMatch(JavaLowering::generic)) {
			own.add(list(integer(2), genericTypes(implemented, null, asking, making)));
		}
		if (!own.isEmpty()) {
			byMember.add(list(call("null", member), caseOf(what, own)));
		}
		List<LispVal> fields = new ArrayList<>(List.of(cl("cond")));
		for (Field field : reflectedFields(declaration)) {
			if (generic(field.type)) {
				fields.add(list(call("equal", member, string(field.name)),
						call("if", call("eql", what, integer(1)), genericType(field.type, null, asking, making), NIL)));
			}
		}
		if (fields.size() > 1) {
			byMember.add(list(call("stringp", member), list(fields)));
		}
		List<LispVal> places = new ArrayList<>(List.of(cl("case"), member));
		if (!declaration.isInterface) {
			List<Method> declared = constructors(declaration);
			for (int index = 0; index < declared.size(); index++) {
				List<LispVal> answers = executableAnswers(declaration, declared.get(index), -1 - index, asking, making);
				if (!answers.isEmpty()) {
					places.add(list(integer(-1 - index), caseOf(what, answers)));
				}
			}
		}
		List<Method> listed = declaredMethodList(declaration);
		for (int index = 0; index < listed.size(); index++) {
			Method method = listed.get(index);
			List<LispVal> answers = method == null ? List.of()
					: executableAnswers(declaration, method, index, asking, making);
			if (!answers.isEmpty()) {
				places.add(list(integer(index), caseOf(what, answers)));
			}
		}
		if (places.size() > 2) {
			byMember.add(list(call("integerp", member), list(places)));
		}
		return byMember.size() == 1 ? NIL : list(byMember);
	}

	/**
	 * What a method or a constructor answers of its generic signature: its type
	 * parameters and their bounds; a method's return type, parameters' types and the
	 * types it throws where they name a generic type; a constructor's declared parameters
	 * and the types it throws where a compiler keeps a generic signature of it, which
	 * leaves out an inner class's instance around it and an enum's name and ordinal
	 * ({@link #keepsSignature}).
	 */
	private List<LispVal> executableAnswers(JavaClass declaration, Method executable, int place, LispSymbol asking,
			Making making) {
		List<LispVal> answers = new ArrayList<>();
		LispVal holder = classObject(raw(declaration), making);
		typeParameterAnswers(executable.typeParameters, asking, holder, integer(place), executable, asking, answers,
				making);
		List<JavaType> declared = executable.parameters.subList(executable.hidden, executable.parameters.size());
		if (executable.isConstructor()) {
			if (keepsSignature(declaration, executable)) {
				answers.add(list(integer(2), genericTypes(declared, executable, asking, making)));
				if (!executable.thrown.isEmpty()) {
					answers.add(list(integer(3), genericTypes(executable.thrown, executable, asking, making)));
				}
			}
			return answers;
		}
		if (generic(executable.returnType)) {
			answers.add(list(integer(1), genericType(executable.returnType, executable, asking, making)));
		}
		if (declared.stream().anyMatch(JavaLowering::generic)) {
			answers.add(list(integer(2), genericTypes(declared, executable, asking, making)));
		}
		if (executable.thrown.stream().anyMatch(JavaLowering::generic)) {
			answers.add(list(integer(3), genericTypes(executable.thrown, executable, asking, making)));
		}
		return answers;
	}

	/**
	 * Whether a compiler keeps a generic signature of a constructor, read off javac's
	 * class files: an enum's always, any other where it declares type parameters or its
	 * declared parameters or the types it throws name a generic type.
	 */
	private static boolean keepsSignature(JavaClass declaration, Method constructor) {
		return declaration.isEnum || !constructor.typeParameters.isEmpty()
				|| constructor.parameters.subList(constructor.hidden, constructor.parameters.size())
					.stream()
					.anyMatch(JavaLowering::generic)
				|| constructor.thrown.stream().anyMatch(JavaLowering::generic);
	}

	/**
	 * The answers of a declaration's type parameters: 0 the variables, 10 and on each
	 * one's bounds ({@code Object} where it declares none).
	 * @param declaring the form of the declaration the variables are of
	 * @param holder the class object their bounds are asked of
	 * @param place the declaration's place there, {@code NIL} for the class's
	 * @param of the method or the constructor, {@code null} for the class's
	 */
	private void typeParameterAnswers(List<TypeParameter> parameters, LispVal declaring, LispVal holder, LispVal place,
			@Nullable Method of, LispSymbol asking, List<LispVal> answers, Making making) {
		if (parameters.isEmpty()) {
			return;
		}
		List<LispVal> variables = new ArrayList<>(List.of(cl("vector")));
		for (int index = 0; index < parameters.size(); index++) {
			variables.add(library(TYPES, "variable",
					List.of(declaring, string(parameters.get(index).name), integer(index), holder, place)));
		}
		answers.add(list(integer(0), list(variables)));
		for (int index = 0; index < parameters.size(); index++) {
			List<JavaType> bounds = parameters.get(index).bounds.isEmpty() ? List.of(this.objectType)
					: parameters.get(index).bounds;
			answers.add(list(integer(10 + index), genericTypes(bounds, of, asking, making)));
		}
	}

	/** {@code (case what ...)} over the answers given. */
	private static LispVal caseOf(LispSymbol what, List<LispVal> answers) {
		List<LispVal> form = new ArrayList<>(List.of(cl("case"), what));
		form.addAll(answers);
		return list(form);
	}

	/** Whether a type is no class object where reflection answers it as declared. */
	private static boolean generic(JavaType type) {
		return switch (type) {
			case Variable variable -> true;
			case Wildcard wildcard -> true;
			case ClassType named -> !named.arguments().isEmpty();
			case ArrayType array -> generic(array.element());
			default -> false;
		};
	}

	/** A new vector of the types, each as {@link #genericType} makes it. */
	private LispVal genericTypes(List<? extends JavaType> types, @Nullable Method of, LispSymbol asking,
			Making making) {
		List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
		for (JavaType type : types) {
			made.add(genericType(type, of, asking, making));
		}
		return list(made);
	}

	/**
	 * The form making a type as a declaration names it: a type naming no generic one its
	 * class object; a class with type arguments a {@code ParameterizedType} of its class,
	 * the type around it -- a member class's class, a parameterized type where an inner
	 * class's instance is of one -- and its own arguments; an array of a generic type a
	 * {@code GenericArrayType}; a wildcard its bounds; a type variable the variable of
	 * the class, the method or the constructor declaring it, the last as the declaration
	 * the function is told where it is the one asked.
	 * @param of the method or the constructor whose signature names the type, or
	 * {@code null}
	 */
	private LispVal genericType(JavaType type, @Nullable Method of, LispSymbol asking, Making making) {
		if (!generic(type)) {
			return classObject(type == Primitive.VOID ? type : this.types.erased(type), making);
		}
		switch (type) {
			case ArrayType array:
				return library(TYPES, "array", List.of(genericType(array.element(), of, asking, making)));
			case Wildcard wildcard: {
				List<JavaType> upper = List.of(wildcard.upper() != null ? wildcard.upper() : this.objectType);
				List<JavaType> lower = wildcard.lower() != null ? List.of(wildcard.lower()) : List.of();
				return library(TYPES, "wildcard", List.of(genericTypes(upper, of, asking, making),
						lower.isEmpty() ? NIL : genericTypes(lower, of, asking, making)));
			}
			case Variable variable:
				return typeVariable(variable.parameter(), of, asking, making);
			default: {
				ClassType named = (ClassType) type;
				JavaClass declaration = named.declaration();
				List<JavaType> arguments = named.arguments();
				int own = Math.min(declaration.typeParameters.size(), arguments.size());
				LispVal owner = NIL;
				JavaClass outer = declaration.outer;
				if (outer != null && !declaration.isLocal) {
					owner = arguments.size() > own
							? genericType(new ClassType(outer, arguments.subList(own, arguments.size())), of, asking,
									making)
							: classObject(raw(outer), making);
				}
				return library(TYPES, "parameterized", List.of(classObject(raw(declaration), making), owner,
						own == 0 ? NIL : genericTypes(arguments.subList(0, own), of, asking, making)));
			}
		}
	}

	/**
	 * The form making a type variable: of the class declaring it, else of the method or
	 * the constructor -- the declaration the function is told where that is the one
	 * asked, {@code NIL} for any other --, by its name and place, with where its bounds
	 * are asked.
	 */
	private LispVal typeVariable(TypeParameter parameter, @Nullable Method of, LispSymbol asking, Making making) {
		Method method = parameter.method;
		if (method != null) {
			int index = indexOf(method.typeParameters, parameter);
			JavaClass owner = method.owner;
			int place = method.isConstructor() ? -1 - constructors(owner).indexOf(method)
					: declaredMethodList(owner).indexOf(method);
			return library(TYPES, "variable", List.of(method == of ? asking : NIL, string(parameter.name),
					integer(index), classObject(raw(owner), making), integer(place)));
		}
		JavaClass owner = parameter.owner;
		if (owner == null) {
			return classObject(this.types.erased(new Variable(parameter)), making);
		}
		LispVal object = classObject(raw(owner), making);
		return library(TYPES, "variable", List.of(object, string(parameter.name),
				integer(indexOf(owner.typeParameters, parameter)), object, NIL));
	}

	/**
	 * A type parameter's place among a declaration's: the same one, else one of its name.
	 */
	private static int indexOf(List<TypeParameter> parameters, TypeParameter parameter) {
		int index = parameters.indexOf(parameter);
		for (int at = 0; index < 0 && at < parameters.size(); at++) {
			if (parameters.get(at).name.equals(parameter.name)) {
				index = at;
			}
		}
		return Math.max(index, 0);
	}

	/**
	 * The function {@code annotations0} asks: for each class object made of a class of
	 * the program or of its libraries, by the declaration ({@link #classGenericFunction})
	 * and what is asked -- 0 its own annotations, 1 a class's with those of an inherited
	 * type a class above carries, ahead of its own, as a JDK lists them, 2 each declared
	 * parameter's, 3 a record component's -- a new vector of new annotations, {@code NIL}
	 * where there is none. The annotations of the declarations in a class's body are a
	 * class of the program's (or one reachability metadata declares, {@link #reflected})
	 * alone, as its members are. Any other class is refused where the program runs.
	 */
	private LispVal classAnnotationsFunction(Making making) {
		LispSymbol member = new LispSymbol("%m");
		LispSymbol what = new LispSymbol("%w");
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> none = new ArrayList<>(List.of(cl("or")));
		for (Map.Entry<JavaType, LispVal> object : List.copyOf(making.made().entrySet())) {
			if (!(object.getKey() instanceof ClassType named)) {
				continue;
			}
			JavaClass declaration = named.declaration();
			LispVal answer;
			if (declaration.intrinsic || declaration.declaration == null || declaration.source == null
					|| declaration.source.library()) {
				JavaJdkMembers.Type row = jdkRow(declaration);
				if (row == null) {
					// any other class is the last arm's
					continue;
				}
				answer = jdkAnnotations(declaration, row, member, what, making);
			}
			else {
				answer = declaredAnnotations(declaration, member, what);
			}
			LispVal test = call("eq", RECEIVER, object.getValue());
			if (answer == NIL) {
				none.add(test);
			}
			else {
				arms.add(list(test, answer));
			}
		}
		if (none.size() > 1) {
			arms.add(list(list(none), NIL));
		}
		arms.add(list(T, library(TYPES, "unknownAnnotations", List.of(RECEIVER))));
		return list(cl("defun"), CLASS_ANNOTATIONS, list(RECEIVER, member, what), list(arms));
	}

	/**
	 * What a class answers of the annotations of its declarations, or {@code NIL} where
	 * none carries one.
	 */
	private LispVal declaredAnnotations(JavaClass declaration, LispSymbol member, LispSymbol what) {
		List<LispVal> byMember = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> own = new ArrayList<>();
		List<JavaClasses.Carried> written = this.classes.annotations(declaration);
		if (!written.isEmpty()) {
			List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
			for (JavaClasses.Carried carried : written) {
				made.add(library(this.classes.annotationHolder(declaration), "a" + carried.index(), List.of()));
			}
			own.add(list(integer(0), list(made)));
		}
		Collection<LispVal> all = classAnnotations(declaration).values();
		if (!all.isEmpty()) {
			List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
			made.addAll(all);
			own.add(list(integer(1), list(made)));
		}
		if (!own.isEmpty()) {
			byMember.add(list(call("null", member), caseOf(what, own)));
		}
		if (!reflected(declaration)) {
			return byMember.size() == 1 ? NIL : list(byMember);
		}
		Map<String, JavaSyntax.Parameter> components = new HashMap<>();
		for (JavaSyntax.Parameter component : Objects.requireNonNull(declaration.declaration).components()) {
			components.put(component.name(), component);
		}
		List<LispVal> fields = new ArrayList<>(List.of(cl("cond")));
		for (Field field : reflectedFields(declaration)) {
			List<LispVal> answers = new ArrayList<>();
			JavaSyntax.Parameter component = field.isStatic ? null : components.get(field.name);
			addAnnotations(answers, 0, declaration, field.pos, field.isConstant ? "ENUM_CONSTANT" : "FIELD");
			if (component != null) {
				addAnnotations(answers, 3, declaration, component.pos(), "RECORD_COMPONENT");
			}
			if (!answers.isEmpty()) {
				fields.add(list(call("equal", member, string(field.name)), caseOf(what, answers)));
			}
		}
		if (fields.size() > 1) {
			byMember.add(list(call("stringp", member), list(fields)));
		}
		List<LispVal> places = new ArrayList<>(List.of(cl("case"), member));
		if (!declaration.isInterface) {
			List<Method> declared = constructors(declaration);
			for (int index = 0; index < declared.size(); index++) {
				List<LispVal> answers = executableAnnotations(declaration, declared.get(index), "CONSTRUCTOR",
						components);
				if (!answers.isEmpty()) {
					places.add(list(integer(-1 - index), caseOf(what, answers)));
				}
			}
		}
		List<Method> listed = declaredMethodList(declaration);
		for (int index = 0; index < listed.size(); index++) {
			Method method = listed.get(index);
			List<LispVal> answers = method == null ? List.of()
					: executableAnnotations(declaration, method, "METHOD", components);
			if (!answers.isEmpty()) {
				places.add(list(integer(index), caseOf(what, answers)));
			}
		}
		if (places.size() > 2) {
			byMember.add(list(call("integerp", member), list(places)));
		}
		return byMember.size() == 1 ? NIL : list(byMember);
	}

	/**
	 * The annotations of a method or a constructor (0) and of each of its declared
	 * parameters (2). A record's accessor the record does not declare carries what its
	 * component carries that may stand on a method, its canonical constructor the record
	 * declares no parameter of carries the components' on its parameters, as javac makes
	 * them.
	 */
	private List<LispVal> executableAnnotations(JavaClass declaration, Method executable, String context,
			Map<String, JavaSyntax.Parameter> components) {
		List<LispVal> answers = new ArrayList<>();
		JavaSyntax.Method written = executable.declaration;
		JavaSyntax.ClassDeclaration source = Objects.requireNonNull(declaration.declaration);
		if (written == null) {
			return answers;
		}
		boolean own = source.methods().stream().anyMatch(method -> method == written);
		boolean canonical = written.parameters() == source.components();
		if (own || (canonical && written.pos() != source.pos())) {
			// the class's own, or a record's compact canonical constructor
			addAnnotations(answers, 0, declaration, written.pos(), context);
		}
		else if (!canonical && written.parameters().isEmpty() && components.containsKey(executable.name)) {
			addAnnotations(answers, 0, declaration, components.get(executable.name).pos(), context);
		}
		if (!own && !canonical) {
			// what the front end writes of an enum or a record carries none
			return answers;
		}
		List<LispVal> parameters = new ArrayList<>(List.of(cl("vector")));
		boolean any = false;
		for (JavaSyntax.Parameter parameter : written.parameters()) {
			LispVal made = annotationVector(declaration, parameter.pos(), "PARAMETER");
			any |= made != NIL;
			parameters.add(made == NIL ? list(cl("vector")) : made);
		}
		if (any) {
			answers.add(list(integer(2), list(parameters)));
		}
		return answers;
	}

	/**
	 * The function {@code Types.variableAnnotations0} asks: for each class object made of
	 * a class of the program, by the declaration ({@link #classGenericFunction}) and a
	 * type parameter's place, a new vector of the new annotations written on the type
	 * parameter that may stand on one; {@code NIL} where there is none and of a class of
	 * the JDK, which writes none. A class whose annotations are not kept is refused where
	 * the program runs. A function of its own beside {@code %class-annotations}, whose
	 * calls all ask a constant: a program holding type variables and asking a
	 * declaration's annotations through {@code AnnotatedElement} reaches a type
	 * variable's, and has arms for its classes' annotated type parameters alone.
	 */
	private LispVal classVariableAnnotationsFunction(Making making) {
		LispSymbol member = new LispSymbol("%m");
		LispSymbol index = new LispSymbol("%i");
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> unknown = new ArrayList<>(List.of(cl("or")));
		for (Map.Entry<JavaType, LispVal> object : List.copyOf(making.made().entrySet())) {
			if (!(object.getKey() instanceof ClassType named)) {
				continue;
			}
			JavaClass declaration = named.declaration();
			LispVal test = call("eq", RECEIVER, object.getValue());
			if (declaration.intrinsic || declaration.declaration == null || declaration.source == null
					|| declaration.source.library()) {
				// a type variable is of a class whose generic signatures are answered: of
				// any other class there is none to ask
				if (jdkRow(declaration) == null && reflected(declaration)) {
					unknown.add(test);
				}
				continue;
			}
			JavaSyntax.ClassDeclaration source = declaration.declaration;
			List<LispVal> byMember = new ArrayList<>(List.of(cl("cond")));
			List<LispVal> own = typeParameterAnnotations(declaration, source.typeParameters());
			if (!own.isEmpty()) {
				byMember.add(list(call("null", member), caseOf(index, own)));
			}
			List<LispVal> places = new ArrayList<>(List.of(cl("case"), member));
			if (!declaration.isInterface) {
				List<Method> declared = constructors(declaration);
				for (int at = 0; at < declared.size(); at++) {
					JavaSyntax.Method written = declared.get(at).declaration;
					List<LispVal> answers = written == null || !source.methods().contains(written) ? List.of()
							: typeParameterAnnotations(declaration, written.typeParameters());
					if (!answers.isEmpty()) {
						places.add(list(integer(-1 - at), caseOf(index, answers)));
					}
				}
			}
			List<Method> listed = declaredMethodList(declaration);
			for (int at = 0; at < listed.size(); at++) {
				JavaSyntax.Method written = listed.get(at) == null ? null : listed.get(at).declaration;
				List<LispVal> answers = written == null || !source.methods().contains(written) ? List.of()
						: typeParameterAnnotations(declaration, written.typeParameters());
				if (!answers.isEmpty()) {
					places.add(list(integer(at), caseOf(index, answers)));
				}
			}
			if (places.size() > 2) {
				byMember.add(list(call("integerp", member), list(places)));
			}
			if (byMember.size() > 1) {
				arms.add(list(test, list(byMember)));
			}
		}
		if (unknown.size() > 1) {
			arms.add(list(list(unknown), library(TYPES, "unknownAnnotations", List.of(RECEIVER))));
		}
		return list(cl("defun"), CLASS_VARIABLE_ANNOTATIONS, list(RECEIVER, member, index), list(arms));
	}

	/** The answers of each type parameter's annotations that has some, by its place. */
	private List<LispVal> typeParameterAnnotations(JavaClass declaration, List<JavaSyntax.TypeParameter> parameters) {
		List<LispVal> answers = new ArrayList<>();
		for (int index = 0; index < parameters.size(); index++) {
			addAnnotations(answers, index, declaration, parameters.get(index).pos(), "TYPE_PARAMETER");
		}
		return answers;
	}

	/**
	 * The function {@code AnnotatedTypes.annotated0} asks: for each class object made of
	 * a class of the program, by the declaration ({@link #classGenericFunction}) and what
	 * is asked -- 1 the use of its generic type (a class's superclass, a method's or a
	 * constructor's return type, a field's or a record component's type), 2 of each
	 * declared parameter (a class's interfaces), 3 of each type thrown, 4 of the
	 * receiver, 10 and on of each bound of a type parameter -- the type annotations
	 * written there, a vector of paths each followed by a new vector of new annotations
	 * ({@link JavaClasses#placed}), {@code NIL} where none is; of a class of the JDK
	 * {@code NIL}, which writes none. And of every class object, 7 the class object of
	 * the class a member class is declared in, 8 whether it is an inner one. Any other
	 * class is refused where the program runs, as its annotations are.
	 */
	private LispVal classAnnotatedFunction(Making making) {
		LispSymbol member = new LispSymbol("%m");
		LispSymbol what = new LispSymbol("%w");
		List<LispVal> owners = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> inners = new ArrayList<>(List.of(cl("or")));
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> none = new ArrayList<>(List.of(cl("or")));
		Set<JavaType> answered = new HashSet<>();
		boolean more = true;
		while (more) {
			more = false;
			for (Map.Entry<JavaType, LispVal> object : List.copyOf(making.made().entrySet())) {
				if (!answered.add(object.getKey())) {
					continue;
				}
				more = true;
				if (!(object.getKey() instanceof ClassType named)) {
					continue;
				}
				JavaClass declaration = named.declaration();
				LispVal test = call("eq", RECEIVER, object.getValue());
				JavaClass outer = declaration.outer;
				if (outer != null && !declaration.isLocal) {
					owners.add(list(test, classObject(raw(outer), making)));
					if (declaration.hasOuterInstance()) {
						inners.add(test);
					}
				}
				LispVal answer;
				if (declaration.intrinsic || declaration.declaration == null || declaration.source == null
						|| declaration.source.library()) {
					if (jdkRow(declaration) == null) {
						// any other class is the last arm's
						continue;
					}
					answer = NIL;
				}
				else {
					answer = declaredAnnotated(declaration, member, what);
				}
				if (answer == NIL) {
					none.add(test);
				}
				else {
					arms.add(list(test, answer));
				}
			}
		}
		if (none.size() > 1) {
			arms.add(list(list(none), NIL));
		}
		arms.add(list(T, library(TYPES, "unknownAnnotations", List.of(RECEIVER))));
		return list(cl("defun"), CLASS_ANNOTATED, list(RECEIVER, member, what),
				list(cl("cond"), list(call("eql", what, integer(7)), list(owners)),
						list(call("eql", what, integer(8)), list(inners)), list(T, list(arms))));
	}

	/**
	 * What a class of the program answers of the type annotations written in the types
	 * its declarations name ({@link #classAnnotatedFunction}), or {@code NIL} where none
	 * is written.
	 */
	private LispVal declaredAnnotated(JavaClass declaration, LispSymbol member, LispSymbol what) {
		JavaSyntax.ClassDeclaration source = Objects.requireNonNull(declaration.declaration);
		List<LispVal> byMember = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> own = new ArrayList<>();
		addPlaced(own, 1, declaration, source.superclass(), List.of());
		addPlacedAll(own, 2, declaration, source.interfaces(), List.of());
		boundsPlaced(own, declaration, source.typeParameters());
		if (!own.isEmpty()) {
			byMember.add(list(call("null", member), caseOf(what, own)));
		}
		if (!reflected(declaration)) {
			return byMember.size() == 1 ? NIL : list(byMember);
		}
		Map<String, JavaSyntax.Parameter> components = new HashMap<>();
		for (JavaSyntax.Parameter component : source.components()) {
			components.put(component.name(), component);
		}
		Map<Integer, JavaSyntax.Field> written = new HashMap<>();
		for (JavaSyntax.Field field : source.fields()) {
			written.put(field.pos(), field);
		}
		List<LispVal> fields = new ArrayList<>(List.of(cl("cond")));
		for (Field field : reflectedFields(declaration)) {
			JavaSyntax.Parameter component = field.isStatic ? null : components.get(field.name);
			JavaSyntax.Field declared = written.get(field.pos);
			List<LispVal> answers = new ArrayList<>();
			if (component != null) {
				addPlaced(answers, 1, declaration, component.type(),
						this.classes.writtenAt(declaration, component.pos()));
			}
			else if (declared != null && !field.isConstant) {
				addPlaced(answers, 1, declaration, declared.type(), this.classes.writtenAt(declaration, field.pos));
			}
			if (!answers.isEmpty()) {
				fields.add(list(call("equal", member, string(field.name)), caseOf(what, answers)));
			}
		}
		if (fields.size() > 1) {
			byMember.add(list(call("stringp", member), list(fields)));
		}
		List<LispVal> places = new ArrayList<>(List.of(cl("case"), member));
		if (!declaration.isInterface) {
			List<Method> declared = constructors(declaration);
			for (int index = 0; index < declared.size(); index++) {
				List<LispVal> answers = executablePlaced(declaration, declared.get(index), components);
				if (!answers.isEmpty()) {
					places.add(list(integer(-1 - index), caseOf(what, answers)));
				}
			}
		}
		List<Method> listed = declaredMethodList(declaration);
		for (int index = 0; index < listed.size(); index++) {
			Method method = listed.get(index);
			List<LispVal> answers = method == null ? List.of() : executablePlaced(declaration, method, components);
			if (!answers.isEmpty()) {
				places.add(list(integer(index), caseOf(what, answers)));
			}
		}
		if (places.size() > 2) {
			byMember.add(list(call("integerp", member), list(places)));
		}
		return byMember.size() == 1 ? NIL : list(byMember);
	}

	/**
	 * The type annotations of a method or a constructor: of what it returns (a
	 * constructor's those written on it), of each declared parameter, each type thrown,
	 * its receiver and its type parameters' bounds. A record's accessor the record does
	 * not declare has its component's, its canonical constructor the record declares no
	 * parameter of its components' on its parameters, as javac writes them.
	 */
	private List<LispVal> executablePlaced(JavaClass declaration, Method executable,
			Map<String, JavaSyntax.Parameter> components) {
		List<LispVal> answers = new ArrayList<>();
		JavaSyntax.Method written = executable.declaration;
		JavaSyntax.ClassDeclaration source = Objects.requireNonNull(declaration.declaration);
		if (written == null) {
			return answers;
		}
		boolean own = source.methods().stream().anyMatch(method -> method == written);
		boolean canonical = written.parameters() == source.components();
		List<JavaSyntax.Annotation> on = this.classes.writtenAt(declaration, written.pos());
		if (own || (canonical && written.pos() != source.pos())) {
			addPlaced(answers, 1, declaration, written.returnType(), on);
		}
		else if (!canonical && written.parameters().isEmpty() && components.containsKey(executable.name)) {
			JavaSyntax.Parameter component = components.get(executable.name);
			addPlaced(answers, 1, declaration, component.type(), this.classes.writtenAt(declaration, component.pos()));
		}
		if (!own && !canonical) {
			return answers;
		}
		List<LispVal> parameters = new ArrayList<>(List.of(cl("vector")));
		boolean any = false;
		for (JavaSyntax.Parameter parameter : written.parameters()) {
			LispVal made = placedForm(declaration, parameter.type(),
					this.classes.writtenAt(declaration, parameter.pos()));
			any |= made != NIL;
			parameters.add(made);
		}
		if (any) {
			answers.add(list(integer(2), list(parameters)));
		}
		if (own) {
			addPlacedAll(answers, 3, declaration, written.thrown(), List.of());
			addPlaced(answers, 4, declaration, this.classes.receiverType(declaration, written.pos()), List.of());
			boundsPlaced(answers, declaration, written.typeParameters());
		}
		return answers;
	}

	/** Adds the answers 10 and on: the uses of each type parameter's bounds. */
	private void boundsPlaced(List<LispVal> answers, JavaClass declaration, List<JavaSyntax.TypeParameter> parameters) {
		for (int index = 0; index < parameters.size(); index++) {
			addPlacedAll(answers, 10 + index, declaration, parameters.get(index).bounds(), List.of());
		}
	}

	/** Adds the answer {@code what} of the uses of several types, if any is annotated. */
	private void addPlacedAll(List<LispVal> answers, int what, JavaClass declaration, List<JavaSyntax.TypeName> types,
			List<JavaSyntax.Annotation> leading) {
		List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
		boolean any = false;
		for (JavaSyntax.TypeName type : types) {
			LispVal placed = placedForm(declaration, type, leading);
			any |= placed != NIL;
			made.add(placed);
		}
		if (any) {
			answers.add(list(integer(what), list(made)));
		}
	}

	/** Adds the answer {@code what} of the use of a type, if it is annotated. */
	private void addPlaced(List<LispVal> answers, int what, JavaClass declaration, JavaSyntax.@Nullable TypeName type,
			List<JavaSyntax.Annotation> leading) {
		if (type == null && what != 1) {
			return;
		}
		if (type != null && type.name().equals("void")) {
			return;
		}
		LispVal placed = placedForm(declaration, type, leading);
		if (placed != NIL) {
			answers.add(list(integer(what), placed));
		}
	}

	/**
	 * The form of the type annotations written in a use of a type: a new vector of each
	 * path followed by a new vector of new annotations, or {@code NIL} for none.
	 */
	private LispVal placedForm(JavaClass declaration, JavaSyntax.@Nullable TypeName type,
			List<JavaSyntax.Annotation> leading) {
		List<JavaClasses.Placed> placed = this.classes.placed(declaration, type, leading);
		if (placed.isEmpty()) {
			return NIL;
		}
		String holder = this.classes.memberAnnotationHolder(declaration);
		List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
		for (JavaClasses.Placed each : placed) {
			made.add(string(each.path()));
			List<LispVal> annotations = new ArrayList<>(List.of(cl("vector")));
			for (JavaClasses.Carried carried : each.carried()) {
				annotations.add(library(holder, JavaClasses.holderMethod(carried), List.of()));
			}
			made.add(list(annotations));
		}
		return list(made);
	}

	/** Adds the answer {@code what} of the annotations written at a position, if any. */
	private void addAnnotations(List<LispVal> answers, int what, JavaClass declaration, int pos, String context) {
		LispVal made = annotationVector(declaration, pos, context);
		if (made != NIL) {
			answers.add(list(integer(what), made));
		}
	}

	/**
	 * A new vector of new annotations of those written at a position of a class's body
	 * that may stand on a kind of declaration, or {@code NIL} for none.
	 */
	private LispVal annotationVector(JavaClass declaration, int pos, String context) {
		List<JavaClasses.Carried> carried = this.classes.carriedAt(declaration, pos, context);
		if (carried.isEmpty()) {
			return NIL;
		}
		List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
		for (JavaClasses.Carried each : carried) {
			made.add(library(this.classes.memberAnnotationHolder(declaration), JavaClasses.holderMethod(each),
					List.of()));
		}
		return list(made);
	}

	/**
	 * A class's annotations as a JDK lists them, by type: those of an inherited type the
	 * class above carries (its own and what it inherits), then the class's own, each
	 * taking the place of an inherited one of its type.
	 */
	private Map<JavaClass, LispVal> classAnnotations(JavaClass declaration) {
		Map<JavaClass, LispVal> found = new LinkedHashMap<>();
		JavaClass above = declaration.isInterface ? null : declaration.superclass;
		if (above != null) {
			classAnnotations(above).forEach((type, made) -> {
				if (this.classes.isInherited(type)) {
					found.put(type, made);
				}
			});
		}
		for (JavaClasses.Carried carried : this.classes.annotations(declaration)) {
			found.put(carried.type(),
					library(this.classes.annotationHolder(declaration), "a" + carried.index(), List.of()));
		}
		return found;
	}

	/**
	 * The function {@code Class.getRecordComponents()} asks: for each class object made
	 * of a record whose members reflection answers, a new vector of its components --
	 * each a name, the class object of its type and the generic signature a class file
	 * keeps of a generic one ({@link #signature}). Any other record is refused where the
	 * program runs.
	 */
	private LispVal classComponentsFunction(Making making) {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		Set<JavaType> answered = new HashSet<>();
		boolean more = true;
		while (more) {
			more = false;
			for (Map.Entry<JavaType, LispVal> object : List.copyOf(making.made().entrySet())) {
				if (!answered.add(object.getKey())) {
					continue;
				}
				more = true;
				if (!(object.getKey() instanceof ClassType named) || !named.declaration().isRecord
						|| !reflected(named.declaration())) {
					continue;
				}
				JavaClass declaration = named.declaration();
				List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
				for (JavaSyntax.Parameter component : Objects.requireNonNull(declaration.declaration).components()) {
					Field field = null;
					for (Field candidate : declaration.fields) {
						if (!candidate.isStatic && candidate.name.equals(component.name())) {
							field = candidate;
						}
					}
					if (field == null) {
						continue;
					}
					made.add(library("java.lang.reflect.RecordComponent", "made",
							List.of(object.getValue(), string(field.name),
									classObject(this.types.erased(field.type), making),
									generic(field.type) ? string(signature(field.type)) : NIL)));
				}
				arms.add(list(call("eq", RECEIVER, object.getValue()), list(made)));
			}
		}
		arms.add(list(T, library(TYPES, "unknownComponents", List.of(RECEIVER))));
		return list(cl("defun"), CLASS_COMPONENTS, list(RECEIVER), list(arms));
	}

	/**
	 * A type as a class file's generic signature writes it (JVMS 4.7.9.1): a class by its
	 * binary name with slashes and its type arguments, a member of an inner class's
	 * parameterized type after its outer's and a dot, a type variable by its name, a
	 * wildcard as {@code *}, {@code +} or {@code -}, an array as {@code [}.
	 */
	private static String signature(JavaType type) {
		return switch (type) {
			case ArrayType array -> "[" + signature(array.element());
			case Variable variable -> "T" + variable.parameter().name + ";";
			case Wildcard wildcard -> wildcard.upper() != null ? "+" + signature(wildcard.upper())
					: wildcard.lower() != null ? "-" + signature(wildcard.lower()) : "*";
			case ClassType named -> classSignature(named) + ";";
			case Primitive primitive -> switch (primitive) {
				case BOOLEAN -> "Z";
				case BYTE -> "B";
				case CHAR -> "C";
				case SHORT -> "S";
				case INT -> "I";
				case LONG -> "J";
				case FLOAT -> "F";
				case DOUBLE -> "D";
				default -> "V";
			};
			default -> "Ljava/lang/Object;";
		};
	}

	private static String classSignature(ClassType named) {
		JavaClass declaration = named.declaration();
		List<JavaType> arguments = named.arguments();
		int own = Math.min(declaration.typeParameters.size(), arguments.size());
		JavaClass outer = declaration.outer;
		StringBuilder written = new StringBuilder();
		if (outer != null && !declaration.isLocal && arguments.size() > own) {
			written.append(classSignature(new ClassType(outer, arguments.subList(own, arguments.size()))))
				.append('.')
				.append(declaration.simpleName());
		}
		else {
			written.append('L').append(declaration.binaryName.replace('.', '/'));
		}
		if (own > 0) {
			written.append('<');
			for (JavaType argument : arguments.subList(0, own)) {
				written.append(signature(argument));
			}
			written.append('>');
		}
		return written.toString();
	}

	/**
	 * The function {@code Executable.parameters0} asks: what javac with no
	 * {@code -parameters} keeps of a method's or a constructor's parameters, by the
	 * declaration ({@link #classGenericFunction}), read off its class files -- of a
	 * record's canonical constructor the names, its compact one's parameters implicit; of
	 * an inner class's constructor the instance around it final and implicit; of an
	 * enum's the name and the ordinal synthetic; of an enum's {@code valueOf} its
	 * parameter implicit --: a new vector of a name ({@code NIL} where none is kept) and
	 * the modifiers of each parameter, {@code NIL} where it keeps nothing.
	 */
	private LispVal classParametersFunction(Making making) {
		LispSymbol member = new LispSymbol("%m");
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (Map.Entry<JavaType, LispVal> object : List.copyOf(making.made().entrySet())) {
			if (!(object.getKey() instanceof ClassType named) || !reflected(named.declaration())) {
				continue;
			}
			JavaClass declaration = named.declaration();
			List<LispVal> places = new ArrayList<>(List.of(cl("case"), member));
			if (!declaration.isInterface) {
				List<Method> declared = constructors(declaration);
				for (int index = 0; index < declared.size(); index++) {
					LispVal kept = keptParameters(declaration, declared.get(index));
					if (kept != NIL) {
						places.add(list(integer(-1 - index), kept));
					}
				}
			}
			List<Method> listed = declaredMethodList(declaration);
			for (int index = 0; index < listed.size(); index++) {
				Method method = listed.get(index);
				if (method != null && declaration.isEnum && method.isStatic && method.name.equals("valueOf")
						&& method.parameters.size() == 1
						&& Objects.requireNonNull(declaration.declaration)
							.methods()
							.stream()
							.noneMatch(written -> written == method.declaration)) {
					places.add(list(integer(index), call("vector", NIL, integer(0x8000))));
				}
			}
			if (places.size() > 2) {
				arms.add(list(call("eq", RECEIVER, object.getValue()), list(places)));
			}
		}
		return list(cl("defun"), CLASS_PARAMETERS, list(RECEIVER, member), list(arms));
	}

	/**
	 * What javac keeps of a constructor's parameters ({@link #classParametersFunction}),
	 * or {@code NIL}.
	 */
	private LispVal keptParameters(JavaClass declaration, Method constructor) {
		List<LispVal> kept = new ArrayList<>(List.of(cl("vector")));
		JavaSyntax.Method written = constructor.declaration;
		List<JavaType> declared = constructor.parameters.subList(constructor.hidden, constructor.parameters.size());
		if (declaration.isRecord && written != null && canonical(declaration, constructor)) {
			boolean compact = written.parameters() == Objects.requireNonNull(declaration.declaration).components()
					&& written.pos() != declaration.declaration.pos();
			for (JavaSyntax.Parameter parameter : written.parameters()) {
				kept.add(string(parameter.name()));
				kept.add(integer(compact ? 0x8000 : 0));
			}
			return list(kept);
		}
		if (declaration.hasOuterInstance()) {
			kept.add(NIL);
			kept.add(integer(0x8010));
		}
		else if (declaration.isEnum) {
			for (int index = 0; index < 2; index++) {
				kept.add(NIL);
				kept.add(integer(0x1000));
			}
		}
		else {
			return NIL;
		}
		for (int index = 0; index < declared.size(); index++) {
			kept.add(NIL);
			kept.add(integer(0));
		}
		return list(kept);
	}

	/**
	 * The function {@code Package}'s annotations are asked of: by a package's name, a new
	 * vector of the new annotations its {@code package-info.java} in the program's source
	 * tree carries ({@link JavaClasses#packageAnnotations}), {@code NIL} for any other.
	 */
	private LispVal packageAnnotationsFunction() {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		this.classes.packageAnnotations().forEach((name, held) -> {
			List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
			for (int index = 0; index < held.count(); index++) {
				made.add(library(held.holder(), "a" + index, List.of()));
			}
			arms.add(list(call("equal", RECEIVER, string(name)), list(made)));
		});
		return list(cl("defun"), PACKAGE_ANNOTATIONS, list(RECEIVER), list(arms));
	}

	/**
	 * Where a type variable a signature of a class of the JDK names is declared: the type
	 * parameters of the method or the constructor asked, then those of its class.
	 *
	 * @param classParameters the class's type parameters, none where it declares none
	 * @param classObject the variable holding the class's object
	 * @param methodParameters the method's or the constructor's, or none
	 * @param place the method's or the constructor's place among its class's, or
	 * {@code NIL}
	 */
	private record JdkScope(List<JavaJdkMembers.ParameterSig> classParameters, LispVal classObject,
			List<JavaJdkMembers.ParameterSig> methodParameters, LispVal place) {
	}

	/**
	 * What a class of the JDK answers of its generic signatures
	 * ({@link #classGenericFunction}) from the table of the JDK's members: its own where
	 * the table writes one, its members' where it answers its members ({@link #held});
	 * {@code NIL} where none names a generic type.
	 */
	private LispVal jdkGenerics(JavaClass declaration, JavaJdkMembers.Type row, LispSymbol member, LispSymbol what,
			LispSymbol asking, Making making) {
		LispVal object = classObject(raw(declaration), making);
		List<LispVal> byMember = new ArrayList<>(List.of(cl("cond")));
		List<JavaJdkMembers.ParameterSig> classParameters = List.of();
		if (row.signature != null) {
			JavaJdkMembers.Signature signature = JavaJdkMembers.Signature.ofClass(row.signature);
			classParameters = signature.parameters();
			JdkScope scope = new JdkScope(classParameters, object, List.of(), NIL);
			List<LispVal> own = new ArrayList<>();
			jdkTypeParameterAnswers(classParameters, object, scope, asking, own, making);
			JavaJdkMembers.Sig superclass = signature.types().get(0);
			if (superclass != null && jdkGeneric(superclass)) {
				own.add(list(integer(1), jdkGenericType(superclass, scope, asking, making)));
			}
			List<JavaJdkMembers.Sig> interfaces = new ArrayList<>();
			for (JavaJdkMembers.Sig each : signature.types().subList(1, signature.types().size())) {
				interfaces.add(Objects.requireNonNull(each));
			}
			if (interfaces.stream().anyMatch(JavaLowering::jdkGeneric)) {
				own.add(list(integer(2), jdkGenericTypes(interfaces, scope, asking, making)));
			}
			if (!own.isEmpty()) {
				byMember.add(list(call("null", member), caseOf(what, own)));
			}
		}
		if (this.held.contains(declaration)) {
			List<LispVal> fields = new ArrayList<>(List.of(cl("cond")));
			for (JavaJdkMembers.Member field : row.fields) {
				String text = row.signatures.get(field);
				if (text != null) {
					JdkScope scope = new JdkScope(classParameters, object, List.of(), NIL);
					fields
						.add(list(call("equal", member, string(field.name())), call("if", call("eql", what, integer(1)),
								jdkGenericType(
										Objects.requireNonNull(JavaJdkMembers.Signature.ofField(text).types().get(0)),
										scope, asking, making),
								NIL)));
				}
			}
			if (fields.size() > 1) {
				byMember.add(list(call("stringp", member), list(fields)));
			}
			List<LispVal> places = new ArrayList<>(List.of(cl("case"), member));
			for (JavaJdkMembers.Member constructor : row.constructors) {
				String text = row.signatures.get(constructor);
				if (text != null) {
					places.add(list(integer(-1 - constructor.index()),
							caseOf(what, jdkExecutableAnswers(JavaJdkMembers.Signature.ofMethod(text), true,
									classParameters, object, -1 - constructor.index(), asking, making))));
				}
			}
			for (JavaJdkMembers.Member method : row.methods) {
				String text = row.signatures.get(method);
				if (text != null) {
					places.add(list(integer(method.index()),
							caseOf(what, jdkExecutableAnswers(JavaJdkMembers.Signature.ofMethod(text), false,
									classParameters, object, method.index(), asking, making))));
				}
			}
			if (places.size() > 2) {
				byMember.add(list(call("integerp", member), list(places)));
			}
		}
		return byMember.size() == 1 ? NIL : list(byMember);
	}

	/**
	 * What a method or a constructor of the JDK answers of its generic signature: its
	 * type parameters and their bounds, a method's generic return type, parameters' types
	 * and types thrown where they name a generic type, a constructor's parameters' types
	 * and types thrown as the signature names them.
	 */
	private List<LispVal> jdkExecutableAnswers(JavaJdkMembers.Signature signature, boolean constructor,
			List<JavaJdkMembers.ParameterSig> classParameters, LispVal object, int place, LispSymbol asking,
			Making making) {
		JdkScope scope = new JdkScope(classParameters, object, signature.parameters(), integer(place));
		List<LispVal> answers = new ArrayList<>();
		jdkTypeParameterAnswers(signature.parameters(), asking, scope, asking, answers, making);
		List<JavaJdkMembers.Sig> parameters = new ArrayList<>();
		for (JavaJdkMembers.Sig each : signature.types()) {
			parameters.add(Objects.requireNonNull(each));
		}
		JavaJdkMembers.Sig returned = Objects.requireNonNull(signature.returned());
		if (!constructor && jdkGeneric(returned)) {
			answers.add(list(integer(1), jdkGenericType(returned, scope, asking, making)));
		}
		if (constructor || parameters.stream().anyMatch(JavaLowering::jdkGeneric)) {
			answers.add(list(integer(2), jdkGenericTypes(parameters, scope, asking, making)));
		}
		if ((constructor && !signature.thrown().isEmpty())
				|| signature.thrown().stream().anyMatch(JavaLowering::jdkGeneric)) {
			answers.add(list(integer(3), jdkGenericTypes(signature.thrown(), scope, asking, making)));
		}
		return answers;
	}

	/** The answers of the type parameters a JDK's signature declares (0, 10 and on). */
	private void jdkTypeParameterAnswers(List<JavaJdkMembers.ParameterSig> parameters, LispVal declaring,
			JdkScope scope, LispSymbol asking, List<LispVal> answers, Making making) {
		if (parameters.isEmpty()) {
			return;
		}
		List<LispVal> variables = new ArrayList<>(List.of(cl("vector")));
		for (int index = 0; index < parameters.size(); index++) {
			variables.add(library(TYPES, "variable", List.of(declaring, string(parameters.get(index).name()),
					integer(index), scope.classObject(), scope.place())));
		}
		answers.add(list(integer(0), list(variables)));
		for (int index = 0; index < parameters.size(); index++) {
			answers
				.add(list(integer(10 + index), jdkGenericTypes(parameters.get(index).bounds(), scope, asking, making)));
		}
	}

	/**
	 * Whether a type of a JDK's signature is no class object where reflection answers it.
	 */
	private static boolean jdkGeneric(JavaJdkMembers.Sig type) {
		return switch (type) {
			case JavaJdkMembers.VariableSig variable -> true;
			case JavaJdkMembers.WildcardSig wildcard -> true;
			case JavaJdkMembers.Named named -> !named.arguments().isEmpty()
					|| (named.owner() != null && jdkGeneric(Objects.requireNonNull(named.owner())));
			case JavaJdkMembers.ArraySig array -> jdkGeneric(array.component());
			default -> false;
		};
	}

	private LispVal jdkGenericTypes(List<JavaJdkMembers.Sig> types, JdkScope scope, LispSymbol asking, Making making) {
		List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
		for (JavaJdkMembers.Sig type : types) {
			made.add(jdkGenericType(type, scope, asking, making));
		}
		return list(made);
	}

	/**
	 * The form making a type a JDK's signature names, as {@link #genericType} makes one a
	 * declaration of the program names: a type variable of the method or the constructor
	 * asked, else of its class (else {@code Object}).
	 */
	private LispVal jdkGenericType(JavaJdkMembers.Sig type, JdkScope scope, LispSymbol asking, Making making) {
		if (!jdkGeneric(type)) {
			return classObject(jdkErased(type), making);
		}
		switch (type) {
			case JavaJdkMembers.ArraySig array:
				return library(TYPES, "array", List.of(jdkGenericType(array.component(), scope, asking, making)));
			case JavaJdkMembers.VariableSig variable: {
				for (int index = 0; index < scope.methodParameters().size(); index++) {
					if (scope.methodParameters().get(index).name().equals(variable.name())) {
						return library(TYPES, "variable", List.of(asking, string(variable.name()), integer(index),
								scope.classObject(), scope.place()));
					}
				}
				for (int index = 0; index < scope.classParameters().size(); index++) {
					if (scope.classParameters().get(index).name().equals(variable.name())) {
						return library(TYPES, "variable", List.of(scope.classObject(), string(variable.name()),
								integer(index), scope.classObject(), NIL));
					}
				}
				return classObject(this.objectType, making);
			}
			case JavaJdkMembers.WildcardSig wildcard: {
				LispVal object = list(cl("vector"), classObject(this.objectType, making));
				JavaJdkMembers.Sig written = wildcard.bound();
				if (written == null) {
					return library(TYPES, "wildcard", List.of(object, NIL));
				}
				LispVal bound = list(cl("vector"), jdkGenericType(written, scope, asking, making));
				return library(TYPES, "wildcard",
						wildcard.kind() == '+' ? List.of(bound, NIL) : List.of(object, bound));
			}
			default: {
				JavaJdkMembers.Named named = (JavaJdkMembers.Named) type;
				LispVal owner = NIL;
				if (named.owner() != null) {
					owner = jdkGenericType(Objects.requireNonNull(named.owner()), scope, asking, making);
				}
				else if (named.name().indexOf('$') > 0) {
					owner = classObject(jdkType(named.name().substring(0, named.name().lastIndexOf('$'))), making);
				}
				return library(TYPES, "parameterized", List.of(classObject(jdkType(named.name()), making), owner,
						named.arguments().isEmpty() ? NIL : jdkGenericTypes(named.arguments(), scope, asking, making)));
			}
		}
	}

	/** The type a JDK's signature's type naming no generic one is. */
	private JavaType jdkErased(JavaJdkMembers.Sig type) {
		return switch (type) {
			case JavaJdkMembers.PrimitiveSig primitive -> switch (primitive.letter()) {
				case 'Z' -> Primitive.BOOLEAN;
				case 'B' -> Primitive.BYTE;
				case 'C' -> Primitive.CHAR;
				case 'S' -> Primitive.SHORT;
				case 'I' -> Primitive.INT;
				case 'J' -> Primitive.LONG;
				case 'F' -> Primitive.FLOAT;
				case 'D' -> Primitive.DOUBLE;
				default -> Primitive.VOID;
			};
			case JavaJdkMembers.ArraySig array -> new ArrayType(jdkErased(array.component()));
			case JavaJdkMembers.Named named -> jdkType(named.name());
			default -> this.objectType;
		};
	}

	/**
	 * What a class of the JDK answers of its annotations
	 * ({@link #classAnnotationsFunction}) from the table of the JDK's members: its own
	 * (none of a type inherited, as a JDK has none), its members' where it answers its
	 * members; {@code NIL} where none carries one.
	 */
	private LispVal jdkAnnotations(JavaClass declaration, JavaJdkMembers.Type row, LispSymbol member, LispSymbol what,
			Making making) {
		List<LispVal> byMember = new ArrayList<>(List.of(cl("cond")));
		if (!row.annotations.isEmpty()) {
			byMember.add(list(call("null", member), list(cl("case"), what,
					list(list(integer(0), integer(1)), jdkAnnotationVector(row.annotations, making)))));
		}
		if (this.held.contains(declaration)) {
			List<LispVal> fields = new ArrayList<>(List.of(cl("cond")));
			for (JavaJdkMembers.Member field : row.fields) {
				List<String> written = row.memberAnnotations.get(field);
				if (written != null) {
					fields.add(list(call("equal", member, string(field.name())),
							call("if", call("eql", what, integer(0)), jdkAnnotationVector(written, making), NIL)));
				}
			}
			if (fields.size() > 1) {
				byMember.add(list(call("stringp", member), list(fields)));
			}
			List<LispVal> places = new ArrayList<>(List.of(cl("case"), member));
			for (JavaJdkMembers.Member constructor : row.constructors) {
				List<LispVal> answers = jdkMemberAnnotations(row, constructor, making);
				if (!answers.isEmpty()) {
					places.add(list(integer(-1 - constructor.index()), caseOf(what, answers)));
				}
			}
			for (JavaJdkMembers.Member method : row.methods) {
				List<LispVal> answers = jdkMemberAnnotations(row, method, making);
				if (!answers.isEmpty()) {
					places.add(list(integer(method.index()), caseOf(what, answers)));
				}
			}
			if (places.size() > 2) {
				byMember.add(list(call("integerp", member), list(places)));
			}
		}
		return byMember.size() == 1 ? NIL : list(byMember);
	}

	/** A member of the JDK's own annotations (0) and its parameters' (2). */
	private List<LispVal> jdkMemberAnnotations(JavaJdkMembers.Type row, JavaJdkMembers.Member member, Making making) {
		List<LispVal> answers = new ArrayList<>();
		List<String> written = row.memberAnnotations.get(member);
		if (written != null) {
			answers.add(list(integer(0), jdkAnnotationVector(written, making)));
		}
		Map<Integer, List<String>> parameters = row.parameterAnnotations.get(member);
		if (parameters != null) {
			List<LispVal> each = new ArrayList<>(List.of(cl("vector")));
			for (int index = 0; index < member.parameters().size(); index++) {
				List<String> held = parameters.get(index);
				each.add(held == null ? list(cl("vector")) : jdkAnnotationVector(held, making));
			}
			answers.add(list(integer(2), list(each)));
		}
		return answers;
	}

	private LispVal jdkAnnotationVector(List<String> written, Making making) {
		List<LispVal> made = new ArrayList<>(List.of(cl("vector")));
		for (String text : written) {
			made.add(jdkAnnotation(text, making));
		}
		return list(made);
	}

	private static final String JDK_ANNOTATIONS = "rontolisp.internal.JdkAnnotations";

	/**
	 * The form making an annotation of the JDK from the text a JDK writes of it (the
	 * table of the JDK's members): of a type the library has, an instance of it with the
	 * elements the text gives, else one answering its type alone; each answering the
	 * text.
	 */
	private LispVal jdkAnnotation(String text, Making making) {
		int open = text.indexOf('(');
		String type = text.substring(1, open);
		String inside = text.substring(open + 1, text.length() - 1);
		LispVal object = classObject(jdkType(type), making);
		return switch (type) {
			case "java.lang.Deprecated" -> {
				int since = inside.indexOf("since=\"");
				StringBuilder value = new StringBuilder();
				for (int at = since + "since=\"".length(); since >= 0 && inside.charAt(at) != '"'; at++) {
					char c = inside.charAt(at);
					if (c == '\\') {
						c = inside.charAt(++at);
					}
					value.append(c);
				}
				yield library(JDK_ANNOTATIONS, "deprecated", List.of(object, string(value.toString()),
						inside.contains("forRemoval=true") ? T : NIL, string(text)));
			}
			case "java.lang.annotation.Retention" ->
				library(JDK_ANNOTATIONS, "retention", List.of(object, string(inside), string(text)));
			case "java.lang.annotation.Target" -> library(JDK_ANNOTATIONS, "target",
					List.of(object, string(inside.replace("{", "").replace("}", "").replace(" ", "")), string(text)));
			case "java.lang.FunctionalInterface" ->
				library(JDK_ANNOTATIONS, "functional", List.of(object, string(text)));
			case "java.lang.SafeVarargs" -> library(JDK_ANNOTATIONS, "safeVarargs", List.of(object, string(text)));
			case "java.lang.annotation.Documented" ->
				library(JDK_ANNOTATIONS, "documented", List.of(object, string(text)));
			case "java.lang.annotation.Inherited" ->
				library(JDK_ANNOTATIONS, "inherited", List.of(object, string(text)));
			default -> library(JDK_ANNOTATIONS, "marker", List.of(object, string(text)));
		};
	}

	/** Whether a record's constructor takes its components' types: its canonical one. */
	private boolean canonical(JavaClass declaration, Method constructor) {
		List<JavaType> components = new ArrayList<>();
		for (JavaSyntax.Parameter component : Objects.requireNonNull(declaration.declaration).components()) {
			for (Field field : declaration.fields) {
				if (!field.isStatic && field.name.equals(component.name())) {
					components.add(this.types.erased(field.type));
				}
			}
		}
		List<JavaType> taken = new ArrayList<>();
		for (JavaType parameter : constructor.parameters) {
			taken.add(this.types.erased(parameter));
		}
		return taken.equals(components);
	}

	/**
	 * The function {@code Class.getDeclaredConstructor()}, {@code getConstructor()} and
	 * {@code newInstance()} ask: for each class object made of a class of the program or
	 * of its libraries that declares a constructor of no parameter, a {@code Constructor}
	 * of it ({@link #constructorObject}), of a class of the JDK whose members it answers
	 * the JDK's ({@link #jdkConstructorObject}); {@code NIL} for a class declaring none.
	 * Any other class of the library is refused where the program runs.
	 * @param made the variables holding the objects, by type
	 */
	private LispVal classConstructorFunction(Asked asked, Set<String> reached, Map<JavaType, LispVal> made,
			List<LispVal> inits, List<LispVal> forms) {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (Map.Entry<JavaType, LispVal> object : List.copyOf(made.entrySet())) {
			if (!(object.getKey() instanceof ClassType named)) {
				continue;
			}
			JavaClass declaration = named.declaration();
			LispVal test = call("eq", RECEIVER, object.getValue());
			JavaJdkMembers.Type row = this.held.contains(declaration) ? jdkRow(declaration) : null;
			if (row != null) {
				// the JDK's public or protected constructor of no parameter, if it has
				// one
				for (JavaJdkMembers.Member member : row.constructors) {
					if (member.parameters().isEmpty()) {
						arms.add(list(test, jdkConstructorObject(declaration, row, member, RECEIVER, asked, reached,
								made, inits, forms)));
					}
				}
				continue;
			}
			if (declaration.intrinsic || declaration.source == null || declaration.source.library()) {
				if (!declaration.isInterface) {
					arms.add(list(test, library(CONSTRUCTOR, "unknown", List.of(RECEIVER))));
				}
				continue;
			}
			Method constructor = nullaryConstructor(declaration);
			if (constructor == null) {
				continue;
			}
			arms.add(list(test, constructorObject(declaration, constructor,
					constructors(declaration).indexOf(constructor), RECEIVER, asked, reached, made, inits, forms)));
		}
		return list(cl("defun"), CLASS_CONSTRUCTOR, list(RECEIVER), list(arms));
	}

	/**
	 * The constructor of no parameter a class declares, whoever may call it, or
	 * {@code null}: an interface, an enum, an inner and a local class have none a class
	 * object answers.
	 */
	private @Nullable Method nullaryConstructor(JavaClass declaration) {
		if (declaration.intrinsic || declaration.source == null || declaration.source.library()
				|| declaration.isInterface || declaration.isEnum || declaration.isLocal
				|| declaration.hasOuterInstance()) {
			return null;
		}
		for (Method candidate : constructors(declaration)) {
			if (candidate.parameters.size() == candidate.hidden) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * What a program asks of the members reflection answers, which decides what each
	 * holds.
	 */
	private record Asked(boolean returns, boolean types, boolean exceptions, boolean arguments) {
	}

	/**
	 * What the program asks of a member: what a method returns ({@code getReturnType},
	 * its text), the types of its parameters ({@code getParameterTypes}, its text, a
	 * lookup by types, arguments given), the types it throws ({@code getExceptionTypes},
	 * its text), a call of one of parameters. A session asks all of it: a later buffer
	 * may.
	 */
	private Asked asked() {
		if (this.session != null) {
			return new Asked(true, true, true, true);
		}
		boolean text = reachedMember(METHOD, "toString") || reachedMember(CONSTRUCTOR, "toString");
		return new Asked(text || reachedMember(METHOD, "getReturnType"),
				text || this.classes.reflectsTypes || this.classes.reflectsArguments
						|| reachedMember(METHOD, "getParameterTypes"),
				text || reachedMember(METHOD, "getExceptionTypes") || reachedMember(CONSTRUCTOR, "getExceptionTypes"),
				this.classes.reflectsArguments);
	}

	/** Whether the program reaches a member of a class of the library by its name. */
	private boolean reachedMember(String owner, String name) {
		for (Method method : this.classes.reached) {
			if (method.name.equals(name) && method.owner.name.equals(owner)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the class objects answer the members of their classes in full
	 * ({@code %class-members}): the program lists them, looks one up by its types, or
	 * asks of a method {@code getDeclaredMethods()} listed more than its name and its
	 * class -- not its text alone, which a call of {@code toString} on any object
	 * reaches.
	 */
	private boolean memberTables(Set<String> reached) {
		if (reached.contains("getDeclaredFields") || reached.contains("getFields")
				|| reached.contains("getDeclaredConstructors") || reached.contains("getConstructors")
				|| reached.contains("getMethods")) {
			return true;
		}
		if (this.classes.reflectsTypes && (reached.contains("getDeclaredMethod") || reached.contains("getMethod")
				|| reached.contains("getDeclaredConstructor") || reached.contains("getConstructor"))) {
			return true;
		}
		if (!reached.contains("getDeclaredMethods")) {
			return false;
		}
		if (this.session != null) {
			return true;
		}
		for (Method method : this.classes.reached) {
			if ((method.owner.name.equals(METHOD) || method.owner.name.equals(EXECUTABLE))
					&& IN_FULL.contains(method.name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What a program asks of a {@code Method} that one made by its name alone does not
	 * answer: its text left out, which a call of {@code toString} on any object reaches.
	 */
	private static final Set<String> IN_FULL = Set.of("getReturnType", "getParameterTypes", "getParameterCount",
			"getExceptionTypes", "getModifiers", "isVarArgs", "isDefault", "isBridge", "invoked");

	private static final String EXECUTABLE = "java.lang.reflect.Executable";

	/**
	 * The functions {@code Class.getDeclaredMethod} and {@code getDeclaredField} ask by a
	 * name alone: for each class object made of a class of the program
	 * ({@link #reflected}) or of a class of the JDK whose members it answers
	 * ({@link #held}), by name, a new {@code Method} of each method of no parameter its
	 * class declares and a new {@code Field} of each field; {@code NIL} for a name it
	 * declares none of. Any other class is refused where the program runs. A name is
	 * computed where the program runs, so every such member of a class whose object the
	 * program has is kept. The types a member answers are values too, so making their
	 * objects can make another whose members are answered, until none is left: of a class
	 * of the program, not of the JDK's.
	 */
	private void classMemberFunctions(boolean methods, boolean fields, Asked asked, Set<String> reached,
			Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		LispSymbol name = new LispSymbol("%n");
		List<LispVal> methodArms = new ArrayList<>(List.of(cl("cond")));
		List<LispVal> fieldArms = new ArrayList<>(List.of(cl("cond")));
		this.jdkNamedLines.clear();
		Set<JavaType> answered = new HashSet<>();
		boolean more = true;
		while (more) {
			more = false;
			for (Map.Entry<JavaType, LispVal> object : List.copyOf(made.entrySet())) {
				if (!answered.add(object.getKey())) {
					continue;
				}
				more = true;
				if (!(object.getKey() instanceof ClassType named)) {
					// an array type and a primitive type declare no member
					continue;
				}
				JavaClass declaration = named.declaration();
				LispVal test = call("eq", RECEIVER, object.getValue());
				if (!reflected(declaration)) {
					JavaJdkMembers.Type row = this.named.contains(declaration) ? jdkRow(declaration) : null;
					if (row != null) {
						jdkNamedLines(named, row, methods, fields, asked, reached, made, inits, forms);
					}
					// any other class is the last arm's
					continue;
				}
				if (methods) {
					List<LispVal> byName = new ArrayList<>(List.of(cl("cond")));
					List<Method> listed = declaredMethodList(declaration);
					Set<String> taken = new HashSet<>();
					for (int index = 0; index < listed.size(); index++) {
						Method method = listed.get(index);
						if (method != null && method.parameters.size() == method.hidden && taken.add(method.name)) {
							byName.add(list(call("equal", name, string(method.name)), methodObject(declaration, method,
									index, object.getValue(), asked, NIL, reached, made, inits, forms)));
						}
					}
					// a class declaring none answers none
					methodArms.add(list(test, list(byName)));
				}
				if (fields) {
					List<LispVal> byName = new ArrayList<>(List.of(cl("cond")));
					for (Field field : reflectedFields(declaration)) {
						byName.add(list(call("equal", name, string(field.name)),
								fieldObject(declaration, field, object.getValue(), reached, made, inits, forms)));
					}
					fieldArms.add(list(test, list(byName)));
				}
			}
		}
		// a class of the JDK by its line of the text, or one whose members are not kept;
		// Class asks of no array type and no primitive type
		boolean described = !this.jdkNamedLines.isEmpty();
		if (methods) {
			LispVal last = described
					? library(METHOD, "jdkNamed", List.of(RECEIVER, name, JDK_NAMED_METHODS, JDK_TYPES))
					: library(METHOD, "unknown", List.of(RECEIVER, name));
			forms.add(list(cl("defun"), CLASS_METHOD, list(RECEIVER, name), lastArm(methodArms, last)));
		}
		if (fields) {
			LispVal last = described ? library(FIELD, "jdkNamed", List.of(RECEIVER, name, JDK_NAMED_FIELDS, JDK_TYPES))
					: library(FIELD, "unknown", List.of(RECEIVER, name));
			forms.add(list(cl("defun"), CLASS_FIELD, list(RECEIVER, name), lastArm(fieldArms, last)));
		}
	}

	/** A {@code cond} of arms and a last one, or the last alone where there are none. */
	private static LispVal lastArm(List<LispVal> arms, LispVal last) {
		if (arms.size() == 1) {
			return last;
		}
		arms.add(list(T, last));
		return list(arms);
	}

	/**
	 * The texts of the JDK's classes' methods of no parameter and of their fields the
	 * lookups by a name alone read ({@code AccessibleObject.described}): a line a class,
	 * by the place of its class object among those the texts name ({@link #JDK_TYPES}),
	 * empty for a class it does not describe. A class costs its line alone: an arm of the
	 * lookups' a class, its test and its call, cost the shiori 73 KB of wasm.
	 */
	private void jdkNamedTexts(List<LispVal> inits, List<LispVal> forms) {
		if (this.jdkNamedLines.isEmpty()) {
			return;
		}
		int lines = Collections.max(this.jdkNamedLines.keySet()) + 1;
		for (int kind = 0; kind < 2; kind++) {
			StringBuilder text = new StringBuilder();
			boolean asked = false;
			for (int place = 0; place < lines; place++) {
				String[] line = this.jdkNamedLines.get(place);
				asked |= line != null && line[kind] != null;
				text.append(place == 0 ? "" : "\n").append(line == null || line[kind] == null ? "" : line[kind]);
			}
			if (!asked) {
				// the program looks up no member of the kind by a name
				continue;
			}
			LispSymbol variable = kind == 0 ? JDK_NAMED_METHODS : JDK_NAMED_FIELDS;
			forms.add(list(cl("defvar"), variable, NIL));
			inits.add(call("setq", variable, string(text.toString())));
		}
	}

	/**
	 * The lines of a class of the JDK for the lookups by a name alone
	 * ({@link #jdkNamedTexts}): its methods of no parameter by name (of a bridge and the
	 * method it stands for, the method, whose return type is the more specific, as a JDK
	 * picks it) and its fields ({@link #jdkNamedMethods}, {@link #jdkFields}), each after
	 * its head ({@link #jdkHead}).
	 */
	private void jdkNamedLines(ClassType type, JavaJdkMembers.Type row, boolean methods, boolean fields, Asked asked,
			Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		JavaClass declaration = type.declaration();
		String[] line = new String[2];
		if (methods) {
			jdkMethodCalls(declaration, row, asked);
			line[0] = jdkHead(declaration, row) + jdkNamedMethods(declaration, row, asked, reached, made, inits, forms);
		}
		if (fields) {
			jdkFieldUses(declaration, row);
			line[1] = jdkHead(declaration, row) + jdkFields(declaration, row, reached, made, inits, forms);
		}
		this.jdkNamedLines.put(jdkPlace(type, reached, made, inits, forms), line);
	}

	/**
	 * The function {@code Class.getDeclaredMethods()}, {@code getMethods()},
	 * {@code getDeclaredFields()}, {@code getDeclaredConstructors()} and the lookups by
	 * types ask ({@code members0}): for each class object made of a class of the program,
	 * by a kind, a new vector of its members in full -- 0 the methods it declares, by
	 * where each stands among the names {@code getDeclaredMethods()} answers; 1 its
	 * fields; 2 its constructors; 3 the public methods it has ({@link #publicMethods}),
	 * then the name of a class above it the library does not have, or {@code NIL} --; for
	 * a class of the JDK whose members it answers (held, or one whose methods another's
	 * public ones list) the same of the JDK's table; for an array type, Object's public
	 * methods. {@code NIL} for any other class: one of a library the program uses, the
	 * library's own that is no public class of the JDK, one of the JDK the program holds
	 * only as a member's type; refused where the program runs.
	 */
	private void classMembersFunction(Asked asked, Set<String> reached, Map<JavaType, LispVal> made,
			List<LispVal> inits, List<LispVal> forms) {
		LispSymbol kind = new LispSymbol("%k");
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		Set<JavaType> answered = new HashSet<>();
		boolean more = true;
		while (more) {
			more = false;
			for (Map.Entry<JavaType, LispVal> object : List.copyOf(made.entrySet())) {
				JavaType type = object.getKey();
				JavaJdkMembers.Type row = type instanceof ClassType named && !reflected(named.declaration())
						? jdkRow(named.declaration()) : null;
				if (answered.contains(type) || (row != null && !this.held.contains(((ClassType) type).declaration())
						&& !this.jdkTabled.contains(((ClassType) type).declaration()))) {
					// a class of the JDK no list names yet is the last arm's
					continue;
				}
				answered.add(type);
				more = true;
				LispVal test = call("eq", RECEIVER, object.getValue());
				if (type instanceof ArrayType) {
					JavaJdkMembers.Type objects = Objects.requireNonNull(jdkRow(this.objectType.declaration()));
					arms.add(list(test, call("if", call("eql", kind, integer(3)),
							jdkPublicMethods(objects, reached, made, inits, forms), call("vector"))));
					continue;
				}
				if (!(type instanceof ClassType named)) {
					// a primitive type: no member, nothing above it
					arms.add(
							list(test, call("if", call("eql", kind, integer(3)), call("vector", NIL), call("vector"))));
					continue;
				}
				JavaClass declaration = named.declaration();
				if (row != null) {
					arms.add(list(test,
							jdkMembers(declaration, row, object.getValue(), kind, asked, reached, made, inits, forms)));
					continue;
				}
				if (!reflected(declaration)) {
					continue;
				}
				List<LispVal> methods = new ArrayList<>(List.of(cl("vector")));
				List<Method> listed = declaredMethodList(declaration);
				// what an instance method no call can reach answers of a value: whether
				// it is one the method could be called on, the class's own test, which
				// each such method shares
				LispSymbol tester = new LispSymbol("%t");
				boolean tests = false;
				for (int index = 0; index < listed.size(); index++) {
					Method method = listed.get(index);
					tests |= method != null && !method.isStatic && !callable(method, asked)
							&& runs(declaration, method);
					methods.add(method == null ? NIL : methodObject(declaration, method, index, object.getValue(),
							asked, tester, reached, made, inits, forms));
				}
				List<LispVal> fields = new ArrayList<>(List.of(cl("vector")));
				for (Field field : reflectedFields(declaration)) {
					fields.add(fieldObject(declaration, field, object.getValue(), reached, made, inits, forms));
				}
				List<LispVal> constructors = new ArrayList<>(List.of(cl("vector")));
				if (!declaration.isInterface) {
					List<Method> declared = constructors(declaration);
					for (int index = 0; index < declared.size(); index++) {
						constructors.add(constructorObject(declaration, declared.get(index), index, object.getValue(),
								asked, reached, made, inits, forms));
					}
				}
				LispVal answer = list(cl("case"), kind, list(integer(0), list(methods)), list(integer(1), list(fields)),
						list(integer(2), list(constructors)),
						list(integer(3), publicMethods(declaration, asked, reached, made, inits, forms)));
				arms.add(
						list(test, tests
								? list(cl("let"),
										list(list(tester,
												list(cl("lambda"), list(new LispSymbol("%v"), new LispSymbol("%a")),
														instanceTest(new LispSymbol("%v"), this.objectType,
																raw(declaration), 0, "instanceof")))),
										answer)
								: answer));
			}
		}
		arms.add(list(T, NIL));
		forms.add(list(cl("defun"), CLASS_MEMBERS, list(RECEIVER, kind), list(arms)));
	}

	/**
	 * What a class of the JDK answers of its members by a kind: 0 its public and
	 * protected methods, 1 its fields, as the JDK's table lists them and the library
	 * reads them where the program runs ({@link #jdkMethods}, {@link #jdkFields}), 2 its
	 * constructors, 3 the public methods it has ({@link #jdkPublicMethods}).
	 */
	private LispVal jdkMembers(JavaClass declaration, JavaJdkMembers.Type row, LispVal object, LispSymbol kind,
			Asked asked, Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		jdkMethodCalls(declaration, row, asked);
		jdkFieldUses(declaration, row);
		LispVal test = integer(this.jdkArms.testOf.getOrDefault(declaration, -1));
		LispVal methods = library(METHOD, "jdkAll",
				List.of(object, string(jdkMethods(declaration, row, asked, reached, made, inits, forms)), JDK_TYPES,
						string(row.module), integer(jdkFlags(row)), test));
		LispVal fields = library(FIELD, "jdkAll",
				List.of(object, string(jdkFields(declaration, row, reached, made, inits, forms)), JDK_TYPES,
						string(row.module), integer(jdkFlags(row)), test));
		List<LispVal> constructors = new ArrayList<>(List.of(cl("vector")));
		for (JavaJdkMembers.Member member : row.constructors) {
			constructors
				.add(jdkConstructorObject(declaration, row, member, object, asked, reached, made, inits, forms));
		}
		return list(cl("case"), kind, list(integer(0), methods), list(integer(1), fields),
				list(integer(2), list(constructors)),
				list(integer(3), jdkPublicMethods(row, reached, made, inits, forms)));
	}

	/**
	 * A new vector of the public methods a class of the JDK has, as the JDK's table
	 * gathers them, each the one its declaring class's table answers -- which that class
	 * answers in full for it ({@link #jdkTabled}) --, then the name of a declaring class
	 * the library does not have, or {@code NIL}.
	 */
	private LispVal jdkPublicMethods(JavaJdkMembers.Type row, Set<String> reached, Map<JavaType, LispVal> made,
			List<LispVal> inits, List<LispVal> forms) {
		List<LispVal> bindings = new ArrayList<>();
		Map<JavaClass, LispSymbol> lists = new LinkedHashMap<>();
		List<LispVal> elements = new ArrayList<>(List.of(cl("vector")));
		String outside = null;
		for (JavaJdkMembers.Member member : row.publicMethods) {
			LispSymbol held = declaredList(member, lists, bindings, reached, made, inits, forms);
			if (held == null) {
				outside = outside == null ? member.owner().name : outside;
				continue;
			}
			elements.add(call("aref", held, integer(member.index())));
		}
		elements.add(outside == null ? NIL : string(outside));
		return bindings.isEmpty() ? list(elements) : list(cl("let*"), list(bindings), list(elements));
	}

	/**
	 * The variable a list of public methods binds to the methods the declaring class of a
	 * member of the JDK declares, or {@code null} where the library does not have that
	 * class.
	 */
	private @Nullable LispSymbol declaredList(JavaJdkMembers.Member member, Map<JavaClass, LispSymbol> lists,
			List<LispVal> bindings, Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits,
			List<LispVal> forms) {
		JavaClass owner = this.classes.jdkClass(member.owner().name);
		if (owner == null) {
			return null;
		}
		LispSymbol held = lists.get(owner);
		if (held == null) {
			held = new LispSymbol("%m" + lists.size());
			lists.put(owner, held);
			this.jdkTabled.add(owner);
			bindings
				.add(list(held, list(CLASS_MEMBERS, classObject(raw(owner), reached, made, inits, forms), integer(0))));
		}
		return held;
	}

	/**
	 * The bits the lowering adds to a member's modifiers, as {@code Method},
	 * {@code Constructor} and {@code AccessibleObject} name them: an interface's default
	 * method, a class named outside its package, a constructor's class abstract, an enum,
	 * an inner class; a member of a class of the JDK (whose module the member holds), one
	 * of which the library has no implementation, or one the compiled program holds none
	 * of.
	 */
	private static final int MEMBER_DEFAULT = 1 << 20;

	private static final int MEMBER_VISIBLE = 1 << 21;

	private static final int CONSTRUCTOR_ABSTRACT = 1 << 22;

	private static final int CONSTRUCTOR_ENUM = 1 << 23;

	private static final int CONSTRUCTOR_INNER = 1 << 24;

	private static final int MEMBER_JDK = 1 << 26;

	private static final int MEMBER_LACKING = 1 << 27;

	private static final int MEMBER_ABSENT = 1 << 28;

	/** A vector of the elements after {@code vector}, or {@code NIL} for none. */
	private static LispVal vector(List<LispVal> elements) {
		return elements.size() == 1 ? NIL : list(elements);
	}

	/**
	 * The function answering the public members of a class of the program
	 * ({@code getMethods()}): of the union of its public methods, those of the classes
	 * above it and the methods of its interfaces that are not static, of each signature
	 * the most specific -- one a class declares before an interface's, one below before
	 * one above --, a method that another of the union overrides or implements as the
	 * language decides it being of the same signature. A class of the JDK above it gives
	 * the public methods the JDK's table gathers ({@link #jdkStandIn}); one the library
	 * does not have names the vector's last element, the program's methods before it.
	 */
	private LispVal publicMethods(JavaClass declaration, Asked asked, Set<String> reached, Map<JavaType, LispVal> made,
			List<LispVal> inits, List<LispVal> forms) {
		List<Method> union = new ArrayList<>();
		gather(declaration, true, union, new HashSet<>());
		List<Method> kept = new ArrayList<>();
		for (Method method : union) {
			boolean hidden = false;
			for (Method other : union) {
				if (other != method && other.name.equals(method.name)
						&& signatureIn(declaration, other).equals(signatureIn(declaration, method))
						&& moreSpecific(other.owner, method.owner)) {
					hidden = true;
					break;
				}
			}
			if (!hidden && !kept.contains(method)) {
				kept.add(method);
			}
		}
		List<LispVal> bindings = new ArrayList<>();
		Map<JavaClass, LispSymbol> lists = new LinkedHashMap<>();
		List<LispVal> elements = new ArrayList<>(List.of(cl("vector")));
		String outside = null;
		for (Method method : kept) {
			JavaJdkMembers.Member member = this.jdkMembersOf.get(method);
			if (member != null) {
				LispSymbol held = declaredList(member, lists, bindings, reached, made, inits, forms);
				if (held == null) {
					outside = outside == null ? member.owner().name : outside;
				}
				else {
					elements.add(call("aref", held, integer(member.index())));
				}
				continue;
			}
			JavaClass owner = method.owner;
			if (!reflected(owner)) {
				outside = outside == null ? owner.name : outside;
				continue;
			}
			LispSymbol held = lists.get(owner);
			if (held == null) {
				held = new LispSymbol("%m" + lists.size());
				lists.put(owner, held);
				LispVal object = classObject(raw(owner), reached, made, inits, forms);
				bindings.add(list(held, list(CLASS_MEMBERS, object, integer(0))));
			}
			elements.add(call("aref", held, integer(declaredMethodList(owner).indexOf(method))));
		}
		elements.add(outside == null ? NIL : string(outside));
		return bindings.isEmpty() ? list(elements) : list(cl("let*"), list(bindings), list(elements));
	}

	/**
	 * The union {@code getMethods()} picks from: a type's public methods -- of an
	 * interface reached from below, those that are not static --, then those of the class
	 * above, then of its interfaces; a class of the JDK the public methods its table
	 * gathers, the classes above it included.
	 */
	private void gather(JavaClass type, boolean own, List<Method> union, Set<JavaClass> seen) {
		if (!seen.add(type)) {
			return;
		}
		JavaJdkMembers.Type row = reflected(type) ? null : jdkRow(type);
		if (row != null && row.listed) {
			for (JavaJdkMembers.Member member : row.publicMethods) {
				if (own || !member.isStatic()) {
					union.add(jdkStandIn(member));
				}
			}
			return;
		}
		List<Method> declared = reflected(type) ? declaredMethodList(type) : type.methods;
		for (Method method : declared) {
			if (method != null && !method.isConstructor() && (own || !method.isStatic) && isPublicMethod(method)) {
				union.add(method);
			}
		}
		if (!type.isInterface) {
			JavaClass above = type.superclass != null ? type.superclass
					: type.intrinsicSuperclass != null ? type.intrinsicSuperclass
							: type == this.objectType.declaration() ? null : this.objectType.declaration();
			if (above != null) {
				gather(above, true, union, seen);
			}
		}
		for (JavaClass implemented : type.interfaces) {
			gather(implemented, false, union, seen);
		}
	}

	/** Whether a method is public, as reflection answers it. */
	private static boolean isPublicMethod(Method method) {
		if (method.declaration == null) {
			return method.emitter == null || !method.isProtected;
		}
		Set<String> written = method.declaration.modifiers();
		return written.contains("public") || (method.owner.isInterface && !written.contains("private"));
	}

	/**
	 * Whether a method of one type is more specific than one of another of its signature:
	 * a class's than an interface's, one below than one above.
	 */
	private boolean moreSpecific(JavaClass one, JavaClass other) {
		if (one == other) {
			return false;
		}
		if (!one.isInterface && other.isInterface) {
			return true;
		}
		// every class is under Object, which the front end's classes do not name
		return one.isInterface == other.isInterface
				&& (one.isSubclassOf(other) || (!one.isInterface && other == this.objectType.declaration()));
	}

	// ---- the members of the JDK's classes (.kb/java-frontend.md, "Reflection over the
	// JDK's classes")

	/**
	 * The classes whose class objects answer their members where they are the JDK's
	 * ({@link #jdkRow}): the ones whose class object the program's code holds, and the
	 * classes above them where the program reads those ({@link #held(Set)}).
	 */
	private Set<JavaClass> held = Set.of();

	/**
	 * Of {@link #held}, the classes a lookup by a name alone answers the members of: an
	 * interface only where the program holds its class object itself, not where it
	 * reaches one only through {@code getInterfaces()} -- a template engine's walk over
	 * the interfaces of every value's class, whose descriptions and the bodies their
	 * members reach cost the shiori 77 KB of wasm (2026-11-25). Such an interface's
	 * lookup is refused as a class's whose members are not kept.
	 */
	private Set<JavaClass> named = Set.of();

	/**
	 * The classes of the JDK whose methods a list of public methods names, which answer
	 * their members in full for it.
	 */
	private final Set<JavaClass> jdkTabled = new HashSet<>();

	/**
	 * What the compiled program holds, by name: the methods it reaches of the library's
	 * source, the intrinsic members its calls are lowered to, and every method of its own
	 * classes. A member of a class of the JDK is called by reflection only where its
	 * implementation is among them ({@link #inProgram}).
	 */
	private Map<String, List<Method>> programMembers = Map.of();

	/**
	 * The class objects the texts of the JDK's members name, by their places in
	 * {@link #JDK_TYPES}.
	 */
	private final Map<JavaType, Integer> jdkTypePlaces = new LinkedHashMap<>();

	/** The vector of the class objects the texts of the JDK's members name. */
	private static final LispSymbol JDK_TYPES = new LispSymbol("%jdk-types");

	/**
	 * The lines of the texts the lookups by a name alone read, of methods and of fields,
	 * by the place of their class ({@link #jdkNamedTexts}).
	 */
	private final Map<Integer, String[]> jdkNamedLines = new HashMap<>();

	private static final LispSymbol JDK_NAMED_METHODS = new LispSymbol("%jdk-named-methods");

	private static final LispSymbol JDK_NAMED_FIELDS = new LispSymbol("%jdk-named-fields");

	/**
	 * The arms of the functions the members of the JDK's classes are used through
	 * ({@link #jdkFunctions}), and the numbers each class's test and each member's use
	 * have among them.
	 */
	private final JdkArms jdkArms = new JdkArms();

	/**
	 * The arms of {@code %jdk-tests}, {@code %jdk-methods} and {@code %jdk-fields}: a
	 * number each class's test and each member's use, kept across a session's buffers (a
	 * {@code Method} an earlier buffer found holds its numbers), an arm the forms of one
	 * text, of all the numbers that are its -- an inherited member's call, a class's
	 * test, made the same for a second class, is the first's arm.
	 */
	private static final class JdkArms {

		/**
		 * The numbers by kind and member, and the count of each kind after {@code #}.
		 */
		Map<String, Integer> numbers = new HashMap<>();

		final Map<String, JdkArm> tests = new LinkedHashMap<>();

		final Map<String, JdkArm> calls = new LinkedHashMap<>();

		/** A field's read and its write, or {@code NIL}. */
		final Map<String, JdkArm> uses = new LinkedHashMap<>();

		/** Each class's test, by the class, where a member of it is used. */
		final Map<JavaClass, Integer> testOf = new HashMap<>();

		/** Each member's use, where the program holds it. */
		final Map<JavaJdkMembers.Member, Integer> useOf = new IdentityHashMap<>();

		/** The classes whose members were numbered, of methods and of fields. */
		final Set<String> numbered = new HashSet<>();

		boolean used;

		/**
		 * Starts the arms over: those of the session's earlier buffers, where there is
		 * one (a {@code Method} a variable holds is called by its number in a later
		 * buffer).
		 */
		void clear(Map<String, Integer> kept, @Nullable JdkArmSet earlier) {
			this.numbers = kept;
			this.tests.clear();
			this.calls.clear();
			this.uses.clear();
			if (earlier != null) {
				earlier.copyTo(this.tests, this.calls, this.uses);
			}
			this.testOf.clear();
			this.useOf.clear();
			this.numbered.clear();
			this.used = false;
		}

		/** Hands the arms to the session, for its later buffers. */
		void keep(JdkArmSet session) {
			session.copyFrom(this.tests, this.calls, this.uses);
		}

		/**
		 * The number of a member or a class (its kind first), of the forms of its arm.
		 */
		int number(String key, Map<String, JdkArm> arms, LispVal... forms) {
			Integer known = this.numbers.get(key);
			if (known == null) {
				String count = "#" + key.charAt(0);
				known = this.numbers.getOrDefault(count, 0);
				this.numbers.put(count, known + 1);
				this.numbers.put(key, known);
			}
			// the arm an earlier buffer made of this number, which this buffer's forms
			// replace
			for (java.util.Iterator<JdkArm> each = arms.values().iterator(); each.hasNext();) {
				JdkArm arm = each.next();
				if (!java.util.Arrays.toString(forms).equals(java.util.Arrays.toString(arm.forms()))) {
					arm.numbers().remove(known);
					if (arm.numbers().isEmpty()) {
						each.remove();
					}
				}
			}
			List<Integer> numbers = arms
				.computeIfAbsent(java.util.Arrays.toString(forms), (text) -> new JdkArm(forms, new ArrayList<>()))
				.numbers();
			if (!numbers.contains(known)) {
				// a class's test, asked by its methods and by its fields
				numbers.add(known);
			}
			return known;
		}

	}

	/** The forms of an arm and the numbers that are its. */
	record JdkArm(LispVal[] forms, List<Integer> numbers) {
	}

	/** The arms of the three functions, as a session keeps them. */
	static final class JdkArmSet {

		private final Map<String, JdkArm> tests = new LinkedHashMap<>();

		private final Map<String, JdkArm> calls = new LinkedHashMap<>();

		private final Map<String, JdkArm> uses = new LinkedHashMap<>();

		void copyTo(Map<String, JdkArm> tests, Map<String, JdkArm> calls, Map<String, JdkArm> uses) {
			copy(this.tests, tests);
			copy(this.calls, calls);
			copy(this.uses, uses);
		}

		void copyFrom(Map<String, JdkArm> tests, Map<String, JdkArm> calls, Map<String, JdkArm> uses) {
			this.tests.clear();
			this.calls.clear();
			this.uses.clear();
			copy(tests, this.tests);
			copy(calls, this.calls);
			copy(uses, this.uses);
		}

		void copyFrom(JdkArmSet other) {
			copyFrom(other.tests, other.calls, other.uses);
		}

		private static void copy(Map<String, JdkArm> from, Map<String, JdkArm> to) {
			to.clear();
			from.forEach((key, arm) -> to.put(key, new JdkArm(arm.forms(), new ArrayList<>(arm.numbers()))));
		}

	}

	/** The numbers of {@link #jdkArms} where no session keeps them. */
	private final Map<String, Integer> jdkNumbers = new HashMap<>();

	/** The front end's methods standing for the JDK's in a list of public methods. */
	private final Map<JavaJdkMembers.Member, Method> jdkStandIns = new HashMap<>();

	/** The JDK's member each of {@link #jdkStandIns} stands for. */
	private final Map<Method, JavaJdkMembers.Member> jdkMembersOf = new IdentityHashMap<>();

	/**
	 * The row of the JDK's table for a class of the library, or {@code null} for a class
	 * of the program or of a library the program uses, and for one of the library's own
	 * that is no public class of the JDK.
	 */
	private static JavaJdkMembers.@Nullable Type jdkRow(JavaClass declaration) {
		if (programClass(declaration) || (declaration.source != null && declaration.source.dependency())
				|| isReflection(declaration)) {
			return null;
		}
		JavaJdkMembers.Type row = JavaJdkMembers.shipped().of(declaration.binaryName);
		return row != null && row.listed ? row : null;
	}

	/**
	 * Whether a class is one of reflection's own, {@code Class} and those of
	 * {@code java.lang.reflect}, whose members reflection does not answer: they are the
	 * subset's machinery, and their types the annotations and the generic types it lacks
	 * ({@code .kb/java-frontend.md}, "Reflection over the JDK's classes").
	 */
	private static boolean isReflection(JavaClass declaration) {
		return declaration.name.equals(CLASS) || declaration.name.startsWith("java.lang.reflect.");
	}

	/**
	 * The classes whose class object the program's code holds
	 * ({@link JavaClasses#heldValues}), and the classes above them where the program
	 * reads those -- the class above where it asks {@code getSuperclass()}, the
	 * interfaces where {@code getInterfaces()} or {@code isAssignableFrom}. A class of
	 * the JDK among them answers its members; one whose class object is made only as the
	 * type of a member reflection found answers none: the JDK's classes its members name
	 * would answer theirs, and so on through the JDK. The table is read where a member is
	 * asked, not here.
	 * @param interfaces whether the interfaces above them count ({@link #named})
	 */
	private Set<JavaClass> held(Set<String> reached, boolean interfaces) {
		boolean walked = reached.contains("isAssignableFrom") || reached.contains("getInterfaces");
		boolean around = interfaces && walked;
		boolean above = walked || reached.contains("getSuperclass");
		Set<JavaClass> seen = new HashSet<>();
		Deque<JavaClass> pending = new ArrayDeque<>();
		for (JavaType type : this.classes.heldValues) {
			if (type instanceof ClassType named) {
				pending.add(named.declaration());
			}
		}
		while (!pending.isEmpty()) {
			JavaClass type = pending.pop();
			if (!seen.add(type)) {
				continue;
			}
			if (above && !type.isInterface) {
				JavaClass superclass = type.superclass != null ? type.superclass
						: type.intrinsicSuperclass != null ? type.intrinsicSuperclass
								: type == this.objectType.declaration() ? null : this.objectType.declaration();
				if (superclass != null) {
					pending.add(superclass);
				}
			}
			if (around) {
				pending.addAll(type.interfaces);
			}
		}
		return seen;
	}

	/** What the compiled program holds, by name ({@link #programMembers}). */
	private Map<String, List<Method>> programMembers() {
		Map<String, List<Method>> held = new HashMap<>();
		for (Method method : this.classes.reached) {
			held.computeIfAbsent(method.name, name -> new ArrayList<>()).add(method);
		}
		for (Method method : this.classes.intrinsicsCalled) {
			held.computeIfAbsent(method.name, name -> new ArrayList<>()).add(method);
		}
		for (JavaClass declaration : this.classes.all()) {
			if (programClass(declaration)) {
				for (Method method : declaration.methods) {
					held.computeIfAbsent(method.name, name -> new ArrayList<>()).add(method);
				}
			}
		}
		return held;
	}

	/**
	 * The methods of {@code Object} and {@code Enum} reflection calls wherever they are
	 * found, as the program's own classes' are: their bodies are the run time's, in every
	 * program.
	 */
	private static final Set<String> ALWAYS_HELD = Set.of("java.lang.Object.equals(Object)",
			"java.lang.Object.hashCode()", "java.lang.Object.toString()", "java.lang.Enum.name()",
			"java.lang.Enum.ordinal()", "java.lang.Enum.compareTo(E)", "java.lang.Enum.compareTo(Enum)",
			"java.lang.Enum.equals(Object)", "java.lang.Enum.hashCode()", "java.lang.Enum.toString()");

	/**
	 * Whether the compiled program holds what a call of a member of a class of the JDK
	 * runs through the library's implementation: a static method or a constructor the
	 * program calls; an intrinsic member a call of the program's is lowered to; an
	 * instance method of the library's source whose every body a call on an instance of
	 * the class can run is lowered -- some call of the program's reaches each. Reflection
	 * adds nothing to what the program holds: a member a program calls nowhere would
	 * bring its implementation into every program that can look it up by a computed name.
	 */
	private boolean inProgram(JavaClass declaration, Method method) {
		if (ALWAYS_HELD.contains(method.owner.name + "." + method.signature())) {
			return true;
		}
		boolean called = this.programMembers.getOrDefault(method.name, List.of()).contains(method);
		if (method.isStatic || method.isConstructor() || (method.emitter != null && !called)) {
			return called;
		}
		List<Body> bodies = bodies(declaration, method);
		for (Body body : bodies) {
			if (isPartial(body.method().owner) && !this.loweredMethods.contains(body.method())) {
				return false;
			}
		}
		return called || !bodies.isEmpty();
	}

	/** A type of the JDK's table by its binary name, as the front end has it. */
	private JavaType jdkType(String name) {
		switch (name) {
			case "void":
				return Primitive.VOID;
			case "boolean":
				return Primitive.BOOLEAN;
			case "byte":
				return Primitive.BYTE;
			case "char":
				return Primitive.CHAR;
			case "short":
				return Primitive.SHORT;
			case "int":
				return Primitive.INT;
			case "long":
				return Primitive.LONG;
			case "float":
				return Primitive.FLOAT;
			case "double":
				return Primitive.DOUBLE;
			default:
				break;
		}
		if (name.startsWith("[")) {
			String element = name.substring(1);
			JavaType component = switch (element.charAt(0)) {
				case '[' -> jdkType(element);
				case 'L' -> jdkType(element.substring(1, element.length() - 1));
				case 'Z' -> Primitive.BOOLEAN;
				case 'B' -> Primitive.BYTE;
				case 'C' -> Primitive.CHAR;
				case 'S' -> Primitive.SHORT;
				case 'I' -> Primitive.INT;
				case 'J' -> Primitive.LONG;
				case 'F' -> Primitive.FLOAT;
				default -> Primitive.DOUBLE;
			};
			return new ArrayType(component);
		}
		JavaClass found = this.classes.jdkClass(name);
		if (found == null) {
			JavaJdkMembers.Type row = JavaJdkMembers.shipped().of(name);
			found = this.classes.standIn(name, row != null && row.isInterface());
		}
		return raw(found);
	}

	private List<JavaType> jdkTypes(List<String> names) {
		List<JavaType> types = new ArrayList<>();
		for (String name : names) {
			types.add(jdkType(name));
		}
		return types;
	}

	private List<JavaType> erasedAll(List<JavaType> types) {
		List<JavaType> erased = new ArrayList<>();
		for (JavaType type : types) {
			erased.add(this.types.erased(type));
		}
		return erased;
	}

	/** What the lowering adds to the modifiers of each member of a class of the JDK. */
	private static int jdkFlags(JavaJdkMembers.Type row) {
		return MEMBER_JDK | (java.lang.reflect.Modifier.isPublic(row.modifiers) ? MEMBER_VISIBLE : 0);
	}

	/**
	 * The library's method or constructor a member of a class of the JDK is called
	 * through: its class's own or an inherited one, of its name and its parameters'
	 * erasure, else one taking its one argument as it is typed; of a bridge, the method
	 * it stands for. {@code null} where the library has none, and for {@code getClass()},
	 * which would carry the class object of every class.
	 */
	private @Nullable Method jdkImplementation(JavaClass declaration, JavaJdkMembers.Type row,
			JavaJdkMembers.Member member) {
		if (member.kind() == 'm' && member.name().equals("getClass") && member.parameters().isEmpty()) {
			return null;
		}
		JavaJdkMembers.Member target = member;
		if (member.isBridge()) {
			target = null;
			for (JavaJdkMembers.Member other : row.methods) {
				if (!other.isBridge() && other.name().equals(member.name()) && other.isStatic() == member.isStatic()
						&& other.parameters().size() == member.parameters().size()) {
					target = other;
					break;
				}
			}
			if (target == null) {
				return null;
			}
		}
		List<JavaType> erased = erasedAll(jdkTypes(target.parameters()));
		List<Method> candidates = member.kind() == 'k' ? constructors(declaration)
				: methods(declaration, target.name());
		Method taking = null;
		for (Method candidate : candidates) {
			if (candidate.isStatic != target.isStatic()) {
				continue;
			}
			List<JavaType> parameters = candidate.parameters.subList(candidate.hidden, candidate.parameters.size());
			if (erasedAll(parameters).equals(erased)) {
				return candidate;
			}
			if (candidate.raw && parameters.size() == 1 && erased.size() == 1 && taking == null) {
				taking = candidate;
			}
		}
		return taking;
	}

	/**
	 * The place of a type's class object among those the texts of the JDK's members name
	 * ({@link #JDK_TYPES}), made where it is first named.
	 */
	private int jdkPlace(JavaType type, Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits,
			List<LispVal> forms) {
		JavaType erased = type instanceof Primitive ? type : this.types.erased(type);
		Integer known = this.jdkTypePlaces.get(erased);
		if (known != null) {
			return known;
		}
		classObject(erased, reached, made, inits, forms);
		int place = this.jdkTypePlaces.size();
		this.jdkTypePlaces.put(erased, place);
		return place;
	}

	/**
	 * The places of types joined by commas, {@code -} for none; {@code -1} each unasked.
	 */
	private String jdkPlaces(List<String> types, boolean asked, Set<String> reached, Map<JavaType, LispVal> made,
			List<LispVal> inits, List<LispVal> forms) {
		if (types.isEmpty()) {
			return "-";
		}
		StringBuilder places = new StringBuilder();
		for (String type : types) {
			places.append(places.isEmpty() ? "" : ",")
				.append(asked ? jdkPlace(jdkType(type), reached, made, inits, forms) : -1);
		}
		return places.toString();
	}

	/**
	 * The flags of a method of a class of the JDK as the library reads them
	 * ({@code AccessibleObject.flags}): its modifiers, then a letter for each of the
	 * lowering's bits -- {@code d} an interface's default, {@code l} the library lacks
	 * it, {@code a} the program does not hold it ({@link #inProgram}). Its class's bits
	 * are the table's ({@link #jdkFlags(JavaJdkMembers.Type)}).
	 */
	private String jdkFlags(JavaClass declaration, JavaJdkMembers.Member member, @Nullable Method implementation) {
		return member.modifiers() + (member.isDefault() ? "d" : "")
				+ (implementation == null ? "l" : inProgram(declaration, implementation) ? "" : "a");
	}

	/**
	 * The text of the methods a class of the JDK declares, as {@code Method.jdkAll} reads
	 * it: an entry each, {@code name flags returned parameters exceptions}, the types by
	 * their places as the program asks them, the flags with the member's use
	 * ({@link #jdkUse}), the exceptions left out where there are none.
	 */
	private String jdkMethods(JavaClass declaration, JavaJdkMembers.Type row, Asked asked, Set<String> reached,
			Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		StringBuilder text = new StringBuilder();
		for (JavaJdkMembers.Member member : row.methods) {
			text.append(text.isEmpty() ? "" : ";")
				.append(member.name())
				.append(' ')
				.append(jdkFlags(declaration, member, jdkImplementation(declaration, row, member)))
				.append(jdkUse(member))
				.append(' ')
				.append(asked.returns() ? jdkPlace(jdkType(member.type()), reached, made, inits, forms) : -1)
				.append(' ')
				.append(jdkPlaces(member.parameters(), asked.types(), reached, made, inits, forms))
				.append(jdkExceptions(member, asked, reached, made, inits, forms));
		}
		return text.toString();
	}

	/**
	 * The text of the methods of no parameter a class of the JDK declares, as
	 * {@code Method.jdkNamed} reads it: of a name, the one a JDK finds (no bridge where
	 * there is the method it stands for), {@code name flags returned place exceptions} as
	 * {@link #jdkMethods} writes them.
	 */
	private String jdkNamedMethods(JavaClass declaration, JavaJdkMembers.Type row, Asked asked, Set<String> reached,
			Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		Map<String, JavaJdkMembers.Member> chosen = new LinkedHashMap<>();
		for (JavaJdkMembers.Member member : row.methods) {
			JavaJdkMembers.Member before = chosen.get(member.name());
			if (member.parameters().isEmpty() && (before == null || (before.isBridge() && !member.isBridge()))) {
				chosen.put(member.name(), member);
			}
		}
		StringBuilder text = new StringBuilder();
		for (JavaJdkMembers.Member member : chosen.values()) {
			text.append(text.isEmpty() ? "" : ";")
				.append(member.name())
				.append(' ')
				.append(jdkFlags(declaration, member, jdkImplementation(declaration, row, member)))
				.append(jdkUse(member))
				.append(' ')
				.append(asked.returns() ? jdkPlace(jdkType(member.type()), reached, made, inits, forms) : -1)
				.append(' ')
				.append(member.index())
				.append(jdkExceptions(member, asked, reached, made, inits, forms));
		}
		return text.toString();
	}

	/**
	 * The head of a class's line of a text of the JDK's members: {@code +} of a public
	 * class or {@code -}, the number of its test where a member of it is used
	 * ({@link #jdkFunctions}), its module where it is not {@code java.base}, {@code |}.
	 */
	private String jdkHead(JavaClass declaration, JavaJdkMembers.Type row) {
		Integer test = this.jdkArms.testOf.get(declaration);
		return (java.lang.reflect.Modifier.isPublic(row.modifiers) ? "+" : "-") + (test == null ? "" : test)
				+ (row.module.equals("java.base") ? "" : row.module) + "|";
	}

	/**
	 * The number of a member's use after {@code u}, where the program holds it: the last
	 * of its flags' letters.
	 */
	private String jdkUse(JavaJdkMembers.Member member) {
		Integer use = this.jdkArms.useOf.get(member);
		return use == null ? "" : "u" + use;
	}

	/**
	 * The places of the types a method throws after a space, where the program asks them
	 * and there are any: most throw none (1,159 of the shiori's 1,311 methods described).
	 */
	private String jdkExceptions(JavaJdkMembers.Member member, Asked asked, Set<String> reached,
			Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		return !asked.exceptions() || member.exceptions().isEmpty() ? ""
				: " " + jdkPlaces(member.exceptions(), true, reached, made, inits, forms);
	}

	/**
	 * Numbers the calls of a class of the JDK's methods: the library's implementation of
	 * each the compiled program holds ({@link #inProgram}), of parameters where a call
	 * gives arguments, and the class's test, which one of parameters no call gives
	 * arguments asks alone. Made once.
	 */
	private void jdkMethodCalls(JavaClass declaration, JavaJdkMembers.Type row, Asked asked) {
		if (!this.jdkArms.numbered.add("m" + declaration.binaryName)) {
			return;
		}
		this.jdkArms.used = true;
		boolean any = false;
		for (JavaJdkMembers.Member member : row.methods) {
			Method implementation = jdkImplementation(declaration, row, member);
			if (implementation != null && inProgram(declaration, implementation)) {
				any = true;
				if (member.parameters().isEmpty() || asked.arguments()) {
					this.jdkArms.useOf.put(member,
							this.jdkArms.number("m" + declaration.binaryName + "." + member.index(), this.jdkArms.calls,
									jdkCall(declaration, member, implementation, JDK_VALUE, JDK_GIVEN)));
				}
			}
		}
		if (any) {
			jdkTestNumber(declaration);
		}
	}

	private void jdkTestNumber(JavaClass declaration) {
		this.jdkArms.testOf.put(declaration,
				this.jdkArms.number("t" + declaration.binaryName, this.jdkArms.tests, jdkTest(declaration, JDK_VALUE)));
	}

	private static final LispSymbol JDK_VALUE = new LispSymbol("%v");

	private static final LispSymbol JDK_GIVEN = new LispSymbol("%a");

	/**
	 * The functions the members of the JDK's classes are used through, each of a number
	 * the texts write ({@code AccessibleObject.calls0}, {@code reads0}):
	 * {@code %jdk-tests} of a class's test and a value, whether it is an instance of the
	 * class; {@code %jdk-methods} of a method's call, a value and the arguments;
	 * {@code %jdk-fields} of a field's use, a value and {@code T} for a read or a list of
	 * a value written. One function each, with an arm a use: a function a class and a
	 * dispatcher of the class objects cost the shiori 22 KB of wasm more (2026-11-25).
	 */
	private List<LispVal> jdkFunctions() {
		LispSymbol number = new LispSymbol("%k");
		List<LispVal> tests = jdkArms(number, this.jdkArms.tests, 0);
		List<LispVal> calls = jdkArms(number, this.jdkArms.calls, 0);
		List<LispVal> reads = jdkArms(number, this.jdkArms.uses, 0);
		List<LispVal> writes = jdkArms(number, this.jdkArms.uses, 1);
		return List.of(
				list(cl("defun"), new LispSymbol("%jdk-tests"), list(number, JDK_VALUE),
						tests.size() == 1 ? NIL : list(tests)),
				list(cl("defun"), new LispSymbol("%jdk-methods"), list(number, JDK_VALUE, JDK_GIVEN),
						calls.size() == 1 ? NIL : list(calls)),
				list(cl("defun"), new LispSymbol("%jdk-fields"), list(number, JDK_VALUE, JDK_GIVEN),
						call("if", call("consp", JDK_GIVEN),
								call("progn", writes.size() == 1 ? NIL : list(writes), NIL),
								reads.size() == 1 ? NIL : list(reads))));
	}

	/**
	 * The arms of a {@code cond} of a number: of each arm's form at a place, that form
	 * where the number is one of the arm's.
	 */
	private static List<LispVal> jdkArms(LispSymbol number, Map<String, JdkArm> arms, int at) {
		List<LispVal> made = new ArrayList<>(List.of(cl("cond")));
		for (JdkArm arm : arms.values()) {
			if (arm.forms()[at] == NIL) {
				continue;
			}
			List<LispVal> tests = new ArrayList<>(List.of(cl("or")));
			for (int each : arm.numbers()) {
				tests.add(call("eql", number, integer(each)));
			}
			made.add(list(tests.size() == 2 ? tests.get(1) : list(tests), arm.forms()[at]));
		}
		return made;
	}

	/**
	 * Whether a value is an instance of a class of the JDK: none is of an intrinsic class
	 * no value is of (a class of static members alone, as {@code Math}).
	 */
	private LispVal jdkTest(JavaClass declaration, LispSymbol value) {
		boolean held = !declaration.intrinsic || declaration == this.objectType.declaration()
				|| JavaIntrinsics.predicate(declaration) != null;
		return held ? instanceTest(value, this.objectType, raw(declaration), 0, "instanceof") : NIL;
	}

	/**
	 * A call of the library's implementation of a method of a class of the JDK, as
	 * {@link #invoker} makes one of the program's: each argument as the JDK's parameter
	 * takes it (a bridge's cast to the parameter of the method it stands for), the answer
	 * boxed.
	 */
	private LispVal jdkCall(JavaClass declaration, JavaJdkMembers.Member member, Method implementation,
			LispSymbol value, LispSymbol arguments) {
		List<JavaType> taken = implementation.parameters.subList(implementation.hidden,
				implementation.parameters.size());
		List<Typed> given = new ArrayList<>();
		List<LispVal> forms = new ArrayList<>();
		for (int i = 0; i < member.parameters().size(); i++) {
			JavaType parameter = this.types
				.erased(implementation.raw ? jdkType(member.parameters().get(i)) : taken.get(i));
			LispVal argument = call("aref", arguments, integer(i));
			if (member.isBridge() && !(parameter instanceof Primitive) && !parameter.equals(this.objectType)) {
				argument = castTo(parameter, new Typed(argument, this.objectType), 0).form();
			}
			Typed taking = new Typed(argument(argument, parameter), parameter);
			given.add(taking);
			forms.add(taking.form());
		}
		LispVal answer;
		if (implementation.emitter != null) {
			answer = intrinsicCall(implementation, new Invocation(
					implementation.isStatic ? null : new Typed(value, raw(declaration)), given, false, 0));
		}
		else if (implementation.isStatic) {
			called(implementation);
			uses(implementation.owner);
			answer = form(JavaNames.function(implementation), forms);
		}
		else {
			answer = virtual(declaration, implementation, value, forms);
		}
		JavaType returned = this.types.erased(implementation.returnType);
		if (returned == Primitive.VOID) {
			return call("progn", answer, NIL);
		}
		return returned instanceof Primitive primitive ? box(new Typed(answer, primitive)).form() : answer;
	}

	/**
	 * A new {@code Method} in full: of a method of no parameter that throws nothing the
	 * program asks, made without the two vectors ({@code Method.plain}), which each entry
	 * of a table would pass.
	 */
	private LispVal methodFound(LispVal object, LispVal name, int index, LispVal returned, List<LispVal> types,
			List<LispVal> thrown, int flags, String packageName, String topLevel, LispVal invoker) {
		LispVal takes = vector(types);
		LispVal throwing = vector(thrown);
		if (takes == NIL && throwing == NIL) {
			return library(METHOD, "plain", List.of(object, name, integer(index), returned, integer(flags),
					string(packageName), string(topLevel), invoker));
		}
		return library(METHOD, "found", List.of(object, name, integer(index), returned, takes, throwing, integer(flags),
				string(packageName), string(topLevel), invoker));
	}

	/**
	 * The library's field a field of a class of the JDK is read through: its class's own
	 * or an inherited one of its name, static where it is; {@code null} where the library
	 * has none.
	 */
	private @Nullable Field jdkField(JavaClass declaration, JavaJdkMembers.Member member) {
		Field found = field(declaration, member.name());
		return found != null && found.isStatic == member.isStatic() ? found : null;
	}

	/**
	 * The text of the fields a class of the JDK declares, as {@code Field.jdkAll} and
	 * {@code jdkNamed} read it: an entry each, {@code name flags type}.
	 */
	private String jdkFields(JavaClass declaration, JavaJdkMembers.Type row, Set<String> reached,
			Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		StringBuilder text = new StringBuilder();
		for (JavaJdkMembers.Member member : row.fields) {
			Field implementation = jdkField(declaration, member);
			String flags = member.modifiers() + (implementation == null ? "l" : readable(implementation) ? "" : "a");
			text.append(text.isEmpty() ? "" : ";")
				.append(member.name())
				.append(' ')
				.append(flags)
				.append(jdkUse(member))
				.append(' ')
				.append(jdkPlace(jdkType(member.type()), reached, made, inits, forms));
		}
		return text.toString();
	}

	/**
	 * Numbers the uses of a class of the JDK's fields ({@link #jdkFunctions}), as
	 * {@link #reader} makes one of the program's: of a field the compiled program holds
	 * ({@link #readable}) its read, and its write where the program reaches a write by
	 * reflection; and the class's test. Made once.
	 */
	private void jdkFieldUses(JavaClass declaration, JavaJdkMembers.Type row) {
		if (!this.jdkArms.numbered.add("f" + declaration.binaryName)) {
			return;
		}
		this.jdkArms.used = true;
		boolean any = false;
		for (JavaJdkMembers.Member member : row.fields) {
			Field field = jdkField(declaration, member);
			if (field == null || !readable(field)) {
				continue;
			}
			any = true;
			LispVal read;
			if (field.form != null || field.accessor != null) {
				read = fieldRead(field, null, 0, null).form();
			}
			else if (field.isStatic) {
				Object constant = constantOf(field);
				read = constant != null
						? (constant instanceof String text ? constantString(text) : constant(constant)).form()
						: JavaNames.global(field);
			}
			else {
				read = list(JavaNames.accessor(field), JDK_VALUE);
			}
			read = field.type instanceof Primitive ? box(new Typed(read, field.type)).form() : read;
			LispVal write = NIL;
			if (writesFields() && !field.isFinal && field.form == null && field.accessor == null) {
				LispVal stored = argument(call("car", JDK_GIVEN), field.type);
				write = field.isStatic ? call("setq", JavaNames.global(field), stored)
						: call("setf", list(JavaNames.accessor(field), JDK_VALUE), stored);
			}
			this.jdkArms.useOf.put(member, this.jdkArms.number("f" + declaration.binaryName + "." + member.name(),
					this.jdkArms.uses, read, write));
		}
		if (any) {
			jdkTestNumber(declaration);
		}
	}

	/**
	 * Whether the compiled program holds a field of the library: an intrinsic one, a
	 * constant, a static one whose class it initializes, an instance one.
	 */
	private boolean readable(Field field) {
		if (!field.isStatic || field.form != null || constantOf(field) != null) {
			return true;
		}
		if (field.accessor != null) {
			for (Method method : this.programMembers.getOrDefault(field.accessor.method(), List.of())) {
				if (method.owner.name.equals(field.accessor.className())) {
					return true;
				}
			}
			return false;
		}
		return this.classes.used.contains(field.owner);
	}

	/**
	 * A new {@code Constructor} of a constructor a class of the JDK declares: its types
	 * as the table has them, and the function making an instance with the library's
	 * constructor where the compiled program holds it; an abstract class's makes none.
	 */
	private LispVal jdkConstructorObject(JavaClass declaration, JavaJdkMembers.Type row, JavaJdkMembers.Member member,
			LispVal object, Asked asked, Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits,
			List<LispVal> forms) {
		List<LispVal> types = new ArrayList<>(List.of(cl("vector")));
		for (String parameter : member.parameters()) {
			types.add(classObject(this.types.erased(jdkType(parameter)), reached, made, inits, forms));
		}
		List<LispVal> thrown = new ArrayList<>(List.of(cl("vector")));
		if (asked.exceptions()) {
			for (String exception : member.exceptions()) {
				thrown.add(classObject(jdkType(exception), reached, made, inits, forms));
			}
		}
		boolean isAbstract = (row.modifiers & java.lang.reflect.Modifier.ABSTRACT) != 0;
		Method implementation = jdkImplementation(declaration, row, member);
		boolean held = implementation != null && inProgram(declaration, implementation);
		LispVal maker = NIL;
		if (held && !isAbstract && (member.parameters().isEmpty() || asked.arguments())) {
			maker = jdkMaker(declaration, member, Objects.requireNonNull(implementation));
		}
		int flags = member.modifiers() | jdkFlags(row) | (isAbstract ? CONSTRUCTOR_ABSTRACT : 0)
				| (implementation == null ? MEMBER_LACKING : held ? 0 : MEMBER_ABSENT);
		return library(CONSTRUCTOR, "found", List.of(object, integer(member.index()), vector(types), vector(thrown),
				integer(flags), string(""), string(row.module), maker));
	}

	/**
	 * The function making an instance of a class of the JDK with the library's
	 * constructor, of the arguments its parameters take, unboxed where they are
	 * primitive; of none, a function of none.
	 */
	private LispVal jdkMaker(JavaClass declaration, JavaJdkMembers.Member member, Method constructor) {
		if (constructor.emitter == null) {
			return maker(declaration, constructor);
		}
		LispSymbol arguments = new LispSymbol("%a");
		List<Typed> given = new ArrayList<>();
		for (int i = 0; i < member.parameters().size(); i++) {
			JavaType parameter = this.types.erased(jdkType(member.parameters().get(i)));
			given.add(new Typed(argument(call("aref", arguments, integer(i)), parameter), parameter));
		}
		return list(cl("lambda"), given.isEmpty() ? NIL : list(arguments),
				Objects.requireNonNull(constructor.emitter).emit(this, new Invocation(null, given, false, 0)));
	}

	/**
	 * The front end's method standing for a public method of the JDK in the union
	 * {@code getMethods()} of a class of the program picks from: the library's own of the
	 * member's class, of its name and its parameters' erasure -- so that the program's
	 * method of a generic interface it implements hides it, as {@link #signatureIn}
	 * decides --, else one made for the union alone. A bridge has one of its own.
	 */
	private Method jdkStandIn(JavaJdkMembers.Member member) {
		Method known = this.jdkStandIns.get(member);
		if (known != null) {
			return known;
		}
		JavaClass owner = this.classes.jdkClass(member.owner().name);
		if (owner == null) {
			owner = this.classes.standIn(member.owner().name, member.owner().isInterface());
		}
		List<JavaType> parameters = jdkTypes(member.parameters());
		Method found = null;
		if (!member.isBridge()) {
			bring(owner, member.name());
			for (Method candidate : owner.methods) {
				if (candidate.name.equals(member.name()) && !candidate.isConstructor()
						&& candidate.isStatic == member.isStatic() && candidate.parameters.size() == parameters.size()
						&& erasedAll(candidate.parameters).equals(erasedAll(parameters))) {
					found = candidate;
				}
			}
		}
		if (found == null || this.jdkMembersOf.containsKey(found)) {
			found = new Method(owner, member.name(), parameters, jdkType(member.type()), member.isStatic(), false, null,
					null, false);
		}
		this.jdkStandIns.put(member, found);
		this.jdkMembersOf.put(found, member);
		return found;
	}

	/**
	 * A new {@code Method} of a method a class of the program declares, at its place
	 * among the methods {@code getDeclaredMethods()} names: what it returns, takes and
	 * throws (as the program asks them), its modifiers, where its class is and the
	 * function calling it -- {@code NIL} for one of parameters where no call gives a
	 * method arguments.
	 */
	private LispVal methodObject(JavaClass declaration, Method method, int index, LispVal object, Asked asked,
			LispVal tester, Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits,
			List<LispVal> forms) {
		try {
			return describedMethod(declaration, method, index, object, asked, tester, reached, made, inits, forms);
		}
		catch (JavaClass.Absent ex) {
			return describedAbsent(declaration, method.declaration == null ? 0 : method.declaration.pos(), ex);
		}
	}

	private LispVal describedMethod(JavaClass declaration, Method method, int index, LispVal object, Asked asked,
			LispVal tester, Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits,
			List<LispVal> forms) {
		LispVal returned = asked.returns() ? classObject(
				method.returnType == Primitive.VOID ? Primitive.VOID : this.types.erased(method.returnType), reached,
				made, inits, forms) : NIL;
		List<LispVal> types = new ArrayList<>(List.of(cl("vector")));
		for (JavaType parameter : method.parameters.subList(method.hidden, method.parameters.size())) {
			types.add(asked.types() ? classObject(erasedParameter(parameter), reached, made, inits, forms) : NIL);
		}
		List<LispVal> thrown = new ArrayList<>(List.of(cl("vector")));
		if (asked.exceptions()) {
			for (JavaType type : method.thrown) {
				thrown.add(classObject(this.types.erased(type), reached, made, inits, forms));
			}
		}
		// a member of a library's class no metadata declares is refused where it is used
		boolean runs = runs(declaration, method);
		int flags = methodModifiers(declaration, method) | (method.varargs ? 0x80 : 0)
				| (method.isDefault() ? MEMBER_DEFAULT : 0) | (visibleClass(declaration) ? MEMBER_VISIBLE : 0)
				| (runs ? 0 : MEMBER_ABSENT);
		return methodFound(object, string(method.name), index, returned, types, thrown, flags,
				Objects.requireNonNullElse(this.classes.packageOf(declaration), ""), declaration.topLevel().binaryName,
				!runs ? NIL : callable(method, asked) ? invoker(declaration, method) : method.isStatic ? NIL : tester);
	}

	/**
	 * Whether a call of the program can reach a method by reflection: one of no
	 * parameter, or any where a call gives arguments. Of any other the count of the
	 * arguments is refused before the call.
	 */
	private static boolean callable(Method method, Asked asked) {
		return method.parameters.size() == method.hidden || asked.arguments();
	}

	/**
	 * A parameter's type as reflection answers it: erased, an array of what its elements
	 * erase to.
	 */
	private JavaType erasedParameter(JavaType type) {
		return this.types.erased(type);
	}

	/**
	 * A new {@code Field} of a field a class of the program declares: its type, its
	 * modifiers, where its class is and the function reading and writing it.
	 */
	private LispVal fieldObject(JavaClass declaration, Field field, LispVal object, Set<String> reached,
			Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		LispVal type;
		try {
			type = classObject(this.types.erased(field.type), reached, made, inits, forms);
		}
		catch (JavaClass.Absent ex) {
			return describedAbsent(declaration, field.declaration == null ? 0 : field.declaration.pos(), ex);
		}
		String packageName = Objects.requireNonNullElse(this.classes.packageOf(declaration), "");
		boolean runs = runs(declaration, field);
		return library(FIELD, "found",
				List.of(object, string(field.name), type,
						integer(fieldModifiers(declaration, field) | (visibleClass(declaration) ? MEMBER_VISIBLE : 0)
								| (runs ? 0 : MEMBER_ABSENT)),
						string(packageName), string(declaration.topLevel().binaryName),
						runs ? reader(declaration, field) : NIL));
	}

	/**
	 * A new {@code Constructor} of a constructor a class declares: the types it takes as
	 * a JDK's reflection lists them -- an inner class's instance around it first, an
	 * enum's name and ordinal --, the types it throws, its modifiers, the kind of its
	 * class (1 abstract, 2 an enum, 4 an inner class), where its class is and the
	 * function making an instance of the arguments ({@code NIL} where the class makes
	 * none here: abstract, an enum, a local or an anonymous class, or a constructor of
	 * parameters no call gives arguments).
	 */
	private LispVal constructorObject(JavaClass declaration, Method constructor, int index, LispVal object, Asked asked,
			Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		try {
			return describedConstructor(declaration, constructor, index, object, asked, reached, made, inits, forms);
		}
		catch (JavaClass.Absent ex) {
			return describedAbsent(declaration, constructor.declaration == null ? 0 : constructor.declaration.pos(),
					ex);
		}
	}

	private LispVal describedConstructor(JavaClass declaration, Method constructor, int index, LispVal object,
			Asked asked, Set<String> reached, Map<JavaType, LispVal> made, List<LispVal> inits, List<LispVal> forms) {
		List<JavaType> parameters = new ArrayList<>();
		if (declaration.hasOuterInstance()) {
			parameters.add(raw(Objects.requireNonNull(declaration.outer)));
		}
		parameters.addAll(constructor.parameters);
		List<LispVal> types = new ArrayList<>(List.of(cl("vector")));
		for (JavaType parameter : parameters) {
			types.add(classObject(erasedParameter(parameter), reached, made, inits, forms));
		}
		List<LispVal> thrown = new ArrayList<>(List.of(cl("vector")));
		if (asked.exceptions()) {
			for (JavaType type : constructor.thrown) {
				thrown.add(classObject(this.types.erased(type), reached, made, inits, forms));
			}
		}
		int kind = (declaration.isAbstract ? 1 : 0) | (declaration.isEnum ? 2 : 0)
				| (declaration.hasOuterInstance() ? 4 : 0);
		LispVal maker = NIL;
		boolean runs = runs(declaration, constructor);
		if (runs && (kind & 3) == 0 && !declaration.isLocal && (parameters.isEmpty() || asked.arguments())) {
			maker = maker(declaration, constructor);
		}
		int flags = constructorModifiers(declaration, constructor) | (constructor.varargs ? 0x80 : 0)
				| (visibleClass(declaration) ? MEMBER_VISIBLE : 0) | ((kind & 1) != 0 ? CONSTRUCTOR_ABSTRACT : 0)
				| ((kind & 2) != 0 ? CONSTRUCTOR_ENUM : 0) | ((kind & 4) != 0 ? CONSTRUCTOR_INNER : 0)
				| (runs ? 0 : MEMBER_ABSENT);
		return library(CONSTRUCTOR, "found",
				List.of(object, integer(index), vector(types), vector(thrown), integer(flags),
						string(Objects.requireNonNullElse(this.classes.packageOf(declaration), "")),
						string(declaration.topLevel().binaryName), maker));
	}

	/**
	 * The function making an instance with a constructor of the arguments its parameters
	 * take, unboxed where they are primitive.
	 */
	private LispVal maker(JavaClass declaration, Method constructor) {
		LispSymbol arguments = new LispSymbol("%a");
		called(constructor);
		uses(declaration);
		makes(declaration);
		List<LispVal> given = new ArrayList<>();
		int at = 0;
		if (declaration.hasOuterInstance()) {
			given.add(call("aref", arguments, integer(at++)));
		}
		for (JavaType parameter : constructor.parameters) {
			given.add(argument(call("aref", arguments, integer(at++)), parameter));
		}
		// of none, a function of none, as Constructor calls it
		return list(cl("lambda"), given.isEmpty() ? NIL : list(arguments), form(JavaNames.creator(constructor), given));
	}

	/** An argument a reflective call was given, as a parameter of that type takes it. */
	private LispVal argument(LispVal given, JavaType parameter) {
		JavaType type = this.types.erased(parameter);
		return type instanceof Primitive primitive
				? unbox(new Typed(given, JavaIntrinsics.boxed(primitive, this.classes))).form() : given;
	}

	/**
	 * The methods a class of the program declares as reflection lists them, by where each
	 * stands among the names {@code getDeclaredMethods()} answers: its source's in their
	 * order, then a record's accessors, {@code toString}, {@code hashCode} and
	 * {@code equals} it leaves out, an enum's {@code values} and {@code valueOf};
	 * {@code null} where the front end has no method of a name.
	 */
	private List<Method> declaredMethodList(JavaClass declaration) {
		List<String> names = declaredMethods(raw(declaration));
		List<Method> found = new ArrayList<>();
		if (names == null) {
			return found;
		}
		JavaSyntax.ClassDeclaration source = Objects.requireNonNull(declaration.declaration);
		for (JavaSyntax.Method written : source.methods()) {
			if (written.name().equals("<init>")) {
				continue;
			}
			Method own = null;
			for (Method candidate : declaration.methods) {
				if (candidate.declaration == written) {
					own = candidate;
				}
			}
			found.add(own);
		}
		for (int index = found.size(); index < names.size(); index++) {
			String implicit = names.get(index);
			Method own = null;
			if (implicit.equals("values")) {
				own = enumValues(declaration);
			}
			else if (implicit.equals("valueOf")) {
				this.classes.enumMethod(declaration, "valueOf");
				for (Method candidate : declaration.methods) {
					if (candidate.name.equals("valueOf") && candidate.isStatic
							&& candidate.signature().equals("valueOf(String)")) {
						own = candidate;
					}
				}
			}
			else {
				int count = implicit.equals("equals") ? 1 : 0;
				for (Method candidate : declaration.methods) {
					if (candidate.name.equals(implicit) && candidate.parameters.size() == count
							&& !candidate.isConstructor()
							&& source.methods().stream().noneMatch(written -> written == candidate.declaration)) {
						own = candidate;
					}
				}
			}
			found.add(own);
		}
		return found;
	}

	/**
	 * Whether a class is one of the program's, whose members reflection answers and runs
	 * whole. One of the library's own stands for a JDK's, whose source is not that one;
	 * one of a library the program uses is not kept whole, as a program's is: a name
	 * computed where the program runs would keep every member of every class it has an
	 * object of, and what each reaches (a container's request reaching the Servlet API's
	 * {@code Cookie}, which the subset refuses).
	 */
	private static boolean programClass(JavaClass declaration) {
		return !declaration.intrinsic && declaration.declaration != null && declaration.source != null
				&& declaration.source.origin() == JavaClass.Origin.PROGRAM;
	}

	/**
	 * Whether the members of a class are found by a name: it is a class of the program,
	 * or one of a library the program uses that reachability metadata declares (or a
	 * lookup by constants names), whose members reflection answers as the program's and
	 * runs where the metadata declares them ({@link #runs}).
	 */
	private boolean reflected(JavaClass declaration) {
		return programClass(declaration) || declaredLibraryClass(declaration) != null;
	}

	/**
	 * What reachability metadata declares of a class of a library the program uses, or
	 * {@code null} where it declares nothing of it or the class is no such class.
	 */
	private JavaReachabilityMetadata.@Nullable Declared declaredLibraryClass(JavaClass declaration) {
		if (declaration.intrinsic || declaration.declaration == null || declaration.source == null
				|| !declaration.source.dependency()) {
			return null;
		}
		return this.classes.declared.get(declaration.binaryName);
	}

	/**
	 * Whether reflection runs a method or a constructor of a class whose members it
	 * answers: any of a class of the program; of a library's class one reachability
	 * metadata declares, and its constructor of no parameter, which a class of a library
	 * answers declared or not ({@code getDeclaredConstructor()}). Any other is in the
	 * compiled program only as the program calls it, which reflection does not add to.
	 */
	private boolean runs(JavaClass declaration, Method member) {
		JavaReachabilityMetadata.Declared declared = declaredLibraryClass(declaration);
		if (declared == null) {
			return true;
		}
		List<JavaType> parameters = new ArrayList<>();
		if (member.isConstructor()) {
			if (declaration.hasOuterInstance()) {
				parameters.add(raw(Objects.requireNonNull(declaration.outer)));
			}
			parameters.addAll(member.parameters);
			if (parameters.isEmpty()) {
				return true;
			}
		}
		else {
			parameters.addAll(member.parameters.subList(member.hidden, member.parameters.size()));
		}
		List<String> names = new ArrayList<>();
		for (JavaType parameter : parameters) {
			names.add(metadataName(erasedParameter(parameter)));
		}
		boolean isPublic = member.isConstructor() ? (constructorModifiers(declaration, member) & 1) != 0
				: isPublicMethod(member);
		return declared.declaresMethod(member.isConstructor() ? "<init>" : member.name, names, isPublic);
	}

	/**
	 * Whether reflection reads and writes a field of a class whose members it answers.
	 */
	private boolean runs(JavaClass declaration, Field field) {
		JavaReachabilityMetadata.Declared declared = declaredLibraryClass(declaration);
		return declared == null || declared.declaresField(field.name, (fieldModifiers(declaration, field) & 1) != 0);
	}

	/**
	 * A type as reachability metadata names it: a class by its binary name, an array as
	 * its element's name and a {@code []} a dimension, a primitive type by its keyword.
	 */
	private static String metadataName(JavaType type) {
		return switch (type) {
			case ArrayType array -> metadataName(array.element()) + "[]";
			case ClassType named -> named.declaration().binaryName;
			default -> type.toString();
		};
	}

	/**
	 * Whether a class can be named outside its package, as reflection decides who may use
	 * its members: a class declared public or protected, or a member of an interface.
	 */
	private static boolean visibleClass(JavaClass declaration) {
		Set<String> modifiers = Objects.requireNonNull(declaration.declaration).modifiers();
		return !declaration.isLocal && (modifiers.contains("public") || modifiers.contains("protected")
				|| (declaration.outer != null && declaration.outer.isInterface));
	}

	/**
	 * The fields a class declares, as reflection answers them: those its source writes, a
	 * record's components, an enum's constants; none the front end adds.
	 */
	private static List<Field> reflectedFields(JavaClass declaration) {
		List<Field> found = new ArrayList<>();
		JavaSyntax.ClassDeclaration source = Objects.requireNonNull(declaration.declaration);
		Set<String> components = new HashSet<>();
		for (JavaSyntax.Parameter component : source.components()) {
			components.add(component.name());
		}
		for (Field field : declaration.fields) {
			if (field.declaration != null || field.isConstant || (!field.isStatic && components.contains(field.name))) {
				found.add(field);
			}
		}
		return found;
	}

	/**
	 * A method's modifiers as {@code java.lang.reflect.Modifier} spells them; a class
	 * file of today's has no {@code strictfp}.
	 */
	private static int methodModifiers(JavaClass declaration, Method method) {
		JavaSyntax.ClassDeclaration source = Objects.requireNonNull(declaration.declaration);
		if (method.declaration == null
				|| source.methods().stream().noneMatch(written -> written == method.declaration)) {
			// declared by the language: an enum's values() and valueOf, a record's
			// accessor, its toString, hashCode and equals
			return method.isStatic ? 9
					: source.components().stream().anyMatch(c -> c.name().equals(method.name)) ? 1 : 17;
		}
		Set<String> written = method.declaration.modifiers();
		int modifiers = bits(written) & ~0x800;
		if (declaration.isInterface && !written.contains("private")) {
			modifiers |= 1;
			if (!written.contains("static") && !written.contains("default")) {
				modifiers |= 1024;
			}
		}
		return modifiers;
	}

	/**
	 * A constructor's modifiers: as written; a default constructor's and a record's
	 * implicit canonical one's the access of its class (JLS 8.8.9, 8.10.4); an enum's
	 * private.
	 */
	private static int constructorModifiers(JavaClass declaration, Method constructor) {
		if (declaration.isEnum) {
			return 2;
		}
		JavaSyntax.ClassDeclaration source = Objects.requireNonNull(declaration.declaration);
		JavaSyntax.Method written = constructor.declaration;
		if (written != null && source.methods().stream().anyMatch(each -> each == written)) {
			return bits(written.modifiers()) & 7;
		}
		if (written != null && declaration.isRecord) {
			// a compact canonical constructor, written in other words
			for (JavaSyntax.Method each : source.methods()) {
				if (each.compact()) {
					return bits(each.modifiers()) & 7;
				}
			}
		}
		return declaration.isLocal ? 0 : bits(source.modifiers()) & 7;
	}

	/** A field's modifiers as {@code java.lang.reflect.Modifier} spells them. */
	private static int fieldModifiers(JavaClass declaration, Field field) {
		if (field.isConstant) {
			// an enum's constant
			return 0x4000 | 25;
		}
		if (field.declaration == null) {
			// a record's component
			return 18;
		}
		int modifiers = bits(field.declaration.modifiers());
		return declaration.isInterface ? modifiers | 25 : modifiers;
	}

	private static int bits(Set<String> written) {
		int modifiers = 0;
		String[] words = { "public", "private", "protected", "static", "final", "synchronized", "volatile", "transient",
				"native", "abstract", "strictfp" };
		int[] values = { 1, 2, 4, 8, 16, 32, 64, 128, 256, 1024, 2048 };
		for (int i = 0; i < words.length; i++) {
			if (written.contains(words[i])) {
				modifiers |= values[i];
			}
		}
		return modifiers;
	}

	/**
	 * The function a {@code Method} calls its method with: of a value and the arguments
	 * its parameters take (each the box of its type) -- the call's value boxed (a
	 * {@code void} method's {@code null}) --, or of a value and {@code NIL}, whether the
	 * value is an instance of the class (any value, for a static method). Two arguments,
	 * as l37's: a third would give every program a dispatch of its own over each such
	 * function (the shiori 22 KB).
	 */
	private LispVal invoker(JavaClass declaration, Method method) {
		LispSymbol value = new LispSymbol("%v");
		LispSymbol arguments = new LispSymbol("%a");
		List<LispVal> given = new ArrayList<>();
		int at = 0;
		for (JavaType parameter : method.parameters.subList(method.hidden, method.parameters.size())) {
			given.add(argument(call("aref", arguments, integer(at++)), parameter));
		}
		LispVal call;
		LispVal test;
		if (method.isStatic) {
			called(method);
			uses(method.owner);
			call = form(JavaNames.function(method), given);
			test = T;
		}
		else {
			call = virtual(declaration, method, value, given);
			test = instanceTest(value, this.objectType, raw(declaration), 0, "instanceof");
		}
		LispVal answer = method.returnType == Primitive.VOID ? call("progn", call, NIL)
				: method.returnType instanceof Primitive ? box(new Typed(call, method.returnType)).form() : call;
		return list(cl("lambda"), list(value, arguments), call("if", arguments, answer, test));
	}

	/**
	 * The function a {@code Field} reads and writes its field with: of a value and
	 * {@code T}, the field's value boxed; of a value and a list of the value to write
	 * (the box of the field's type), the field written -- only where the program reaches
	 * a write by reflection --; of a value and {@code NIL}, whether the value is an
	 * instance of the class (any value, for a static field).
	 */
	private LispVal reader(JavaClass declaration, Field field) {
		LispSymbol value = new LispSymbol("%v");
		LispSymbol given = new LispSymbol("%a");
		LispVal read;
		LispVal write;
		LispVal test;
		LispVal stored = argument(call("car", given), field.type);
		if (field.isStatic) {
			Object constant = constantOf(field);
			LispVal initialized = this.order.isLazy(field.owner) ? list(JavaNames.initializer(field.owner)) : null;
			if (constant != null) {
				read = (constant instanceof String text ? constantString(text) : constant(constant)).form();
				// a constant is final: no write reaches here
				write = NIL;
			}
			else {
				uses(field.owner);
				LispVal global = isVolatile(field) ? ordered(JavaNames.global(field)) : JavaNames.global(field);
				read = initialized != null ? call("progn", initialized, global) : global;
				LispVal set = isVolatile(field) ? ordered(call("setq", JavaNames.global(field), stored))
						: call("setq", JavaNames.global(field), stored);
				write = initialized != null ? call("progn", initialized, set) : set;
			}
			test = T;
		}
		else {
			LispVal slot = list(JavaNames.accessor(field), value);
			read = isVolatile(field) ? ordered(slot) : slot;
			LispVal set = call("setf", slot, stored);
			write = isVolatile(field) ? ordered(set) : set;
			test = instanceTest(value, this.objectType, raw(declaration), 0, "instanceof");
		}
		LispVal answer = field.type instanceof Primitive ? box(new Typed(read, field.type)).form() : read;
		if (!writesFields()) {
			return list(cl("lambda"), list(value, given), call("if", given, answer, test));
		}
		return list(cl("lambda"), list(value, given), list(cl("cond"), list(call("null", given), test),
				list(call("consp", given), call("progn", write, NIL)), list(T, answer)));
	}

	/**
	 * The function {@code Class.getDeclaredMethods()} asks: for each class object made,
	 * the names of the methods its class declares, a space between two -- of a class of
	 * the JDK whose members it answers, the table's --; {@code NIL} for a class whose
	 * methods are not known.
	 * @param made the variables holding the objects, by type
	 */
	private LispVal declaredMethodsFunction(Map<JavaType, LispVal> made) {
		List<LispVal> arms = new ArrayList<>(List.of(cl("cond")));
		for (Map.Entry<JavaType, LispVal> object : made.entrySet()) {
			List<String> names = declaredMethods(object.getKey());
			JavaJdkMembers.Type row = object.getKey() instanceof ClassType named
					&& this.held.contains(named.declaration()) ? jdkRow(named.declaration()) : null;
			if (names == null && row != null) {
				names = row.methods.stream().map(JavaJdkMembers.Member::name).toList();
			}
			if (names != null) {
				arms.add(list(list(cl("eq"), RECEIVER, object.getValue()), string(String.join(" ", names))));
			}
		}
		return list(cl("defun"), DECLARED_METHODS, list(RECEIVER), list(arms));
	}

	/**
	 * The names of the methods a type declares, as a JDK's reflection answers them: the
	 * ones its source declares, in their order, then the ones the language declares
	 * implicitly (JLS 8.9.3, 8.10.3) -- an enum's {@code values} and {@code valueOf}, a
	 * record's accessors and {@code toString}, {@code hashCode} and {@code equals} it
	 * leaves out --; none for an array or a primitive type. A compiler adds methods of
	 * its own (a lambda's body, a bridge), which are not among them. {@code null} for an
	 * intrinsic class and the library's own: the JDK's class is not that source.
	 */
	private static @Nullable List<String> declaredMethods(JavaType type) {
		if (!(type instanceof ClassType named)) {
			return List.of();
		}
		JavaClass declaration = named.declaration();
		JavaSyntax.ClassDeclaration source = declaration.declaration;
		if (declaration.intrinsic || source == null || declaration.source == null || declaration.source.library()) {
			return null;
		}
		List<String> names = new ArrayList<>();
		Set<String> unary = new HashSet<>();
		boolean equals = false;
		for (JavaSyntax.Method method : source.methods()) {
			if (!method.name().equals("<init>")) {
				names.add(method.name());
				if (method.parameters().isEmpty()) {
					unary.add(method.name());
				}
				else if (method.name().equals("equals") && method.parameters().size() == 1) {
					equals = true;
				}
			}
		}
		if (declaration.isRecord) {
			for (JavaSyntax.Parameter component : source.components()) {
				if (unary.add(component.name())) {
					names.add(component.name());
				}
			}
			for (String member : List.of("toString", "hashCode")) {
				if (unary.add(member)) {
					names.add(member);
				}
			}
			if (!equals) {
				names.add("equals");
			}
		}
		if (declaration.isEnum) {
			names.add("values");
			names.add("valueOf");
		}
		return names;
	}

	private static JavaType raw(JavaClass declaration) {
		return new ClassType(declaration, List.of());
	}

	/**
	 * The variable holding a type's class object, the object made after the ones it
	 * names; {@code NIL} for no type.
	 * @param reached the names of the methods of {@code Class} the program reaches
	 * @param made the objects made so far, by type
	 * @param inits where the forms making the objects go, in the order they run
	 * @param forms where the variables' declarations go
	 */
	private LispVal classObject(@Nullable JavaType type, Set<String> reached, Map<JavaType, LispVal> made,
			List<LispVal> inits, List<LispVal> forms) {
		if (type == null) {
			return NIL;
		}
		LispVal known = made.get(type);
		if (known != null) {
			return known;
		}
		LispSymbol variable = classValue(type, false);
		made.put(type, variable);
		boolean around = reached.contains("isAssignableFrom") || reached.contains("getInterfaces");
		boolean above = around || reached.contains("getSuperclass");
		String name;
		String simple;
		LispVal canonical = NIL;
		int kind = 0;
		JavaType superclass = null;
		List<JavaClass> interfaces = new ArrayList<>();
		JavaType component = null;
		LispVal constants = NIL;
		switch (type) {
			case ArrayType array -> {
				name = descriptor(array);
				simple = simpleArrayName(array);
				kind = CLASS_ARRAY;
				superclass = this.objectType;
				component = array.element();
				if (around) {
					// a JDK's two, in its order
					interfaces.add(this.classes.library("java.lang.Cloneable"));
					interfaces.add(this.classes.library("java.io.Serializable"));
				}
			}
			case ClassType named -> {
				JavaClass declaration = named.declaration();
				name = declaration.binaryName;
				simple = declaration.simpleName();
				if (declaration.isLocal) {
					canonical = string("");
				}
				else if (!declaration.name.equals(name)) {
					canonical = string(declaration.name);
				}
				kind = (declaration.isInterface ? CLASS_INTERFACE : 0) | (declaration.isEnum ? CLASS_ENUM : 0)
						| (declaration.isRecord ? CLASS_RECORD : 0);
				superclass = declaration.isInterface ? null
						: declaration.superclass != null ? raw(declaration.superclass)
								: declaration.intrinsicSuperclass != null ? raw(declaration.intrinsicSuperclass)
										: declaration == this.objectType.declaration() ? null : this.objectType;
				for (JavaClass implemented : declaration.interfaces) {
					if (listsInterface(declaration, implemented)) {
						interfaces.add(implemented);
					}
				}
				if (reached.contains("getEnumConstants") || reached.contains("universe")
						&& (this.classes.enumConstants || this.classes.universes.contains(declaration))) {
					Method values = enumValues(declaration);
					if (values != null) {
						constants = list(cl("lambda"), NIL, list(JavaNames.function(values)));
					}
				}
			}
			default -> {
				name = type.toString();
				simple = name;
				kind = CLASS_PRIMITIVE;
			}
		}
		LispVal test = NIL;
		if (reached.contains("isInstance") || reached.contains("cast")) {
			// a primitive type and a class holding no value have no instance
			boolean held = type instanceof ArrayType
					|| type instanceof ClassType named && (!named.declaration().intrinsic
							|| named.equals(this.objectType) || JavaIntrinsics.predicate(named.declaration()) != null);
			test = list(cl("lambda"), list(RECEIVER),
					held ? instanceTest(RECEIVER, this.objectType, type, 0, "instanceof") : NIL);
		}
		List<LispVal> implemented = new ArrayList<>();
		if (around) {
			for (JavaClass declared : interfaces) {
				implemented.add(classObject(raw(declared), reached, made, inits, forms));
			}
		}
		Method constructor = constructors(this.classes.library(CLASS)).get(0);
		reach(constructor);
		makes(constructor.owner);
		LispVal object = form(JavaNames.creator(constructor),
				List.of(string(name), string(simple), canonical, integer(kind),
						above ? classObject(superclass, reached, made, inits, forms) : NIL,
						around ? form(cl("vector"), implemented) : NIL,
						classObject(component, reached, made, inits, forms), test, constants));
		if (this.session != null) {
			// an earlier buffer's object is the class's
			forms.add(list(cl("defvar"), variable, NIL));
			inits.add(call("if", variable, NIL, call("setq", variable, object)));
		}
		else {
			forms.add(list(cl("defvar"), variable, NIL));
			inits.add(call("setq", variable, object));
		}
		return variable;
	}

	/**
	 * Whether a class object lists an interface its class implements: any, but the
	 * interfaces of generic types and annotations ({@code Type},
	 * {@code GenericDeclaration}, {@code AnnotatedElement}) of a class of the library --
	 * {@code Class} and the members of {@code java.lang.reflect} -- where the program
	 * asks no generic type or annotation list and holds no class object of the interface:
	 * a program walking the interfaces of the values it reflects on holds no class object
	 * for them (a template engine's walk).
	 */
	private boolean listsInterface(JavaClass declaration, JavaClass implemented) {
		if (declaration.source == null || !declaration.source.library()
				|| !REFLECTION_INTERFACES.contains(implemented.name)) {
			return true;
		}
		Set<String> asked = this.classes.nativeReflection;
		if (asked.contains("generic0") || asked.contains("annotations0") || asked.contains("components0")
				|| asked.contains("parameters0") || asked.contains("annotated0")
				|| asked.contains("variableAnnotations0") || this.classes.reflectionInterfacesNamed) {
			return true;
		}
		for (JavaType held : this.classes.classValues) {
			if (held instanceof ClassType named && REFLECTION_INTERFACES.contains(named.declaration().name)) {
				return true;
			}
		}
		return false;
	}

	private static final Set<String> REFLECTION_INTERFACES = Set.of("java.lang.reflect.Type",
			"java.lang.reflect.GenericDeclaration", "java.lang.reflect.AnnotatedElement");

	/**
	 * The function answering {@code getClass()} read as a value under a class: the object
	 * of the class the value is an instance of, among the classes under that one the
	 * program can hold an instance of. A lambda's class has no object.
	 */
	private LispVal classOfFunction(JavaClass of, List<JavaClass> deepestFirst) {
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("cond"));
		boolean any = of == this.objectType.declaration();
		for (JavaClass declaration : deepestFirst) {
			if (!declaration.isAbstract && isMade(declaration) && (any || declaration.isSubclassOf(of))) {
				arms.add(list(list(JavaNames.predicate(declaration), RECEIVER), classValue(raw(declaration))));
			}
		}
		Set<LispSymbol> predicates = new HashSet<>();
		List<JavaClass> intrinsics = new ArrayList<>(this.classes.all());
		// a StringBuffer is a StringBuilder's representation: the first of the two
		intrinsics.sort(Comparator.comparing(candidate -> candidate.name.equals("java.lang.StringBuffer")));
		for (JavaClass candidate : intrinsics) {
			LispSymbol predicate = candidate.intrinsic && candidate.held && !candidate.isInterface
					&& !candidate.isAbstract && (any || candidate.isSubclassOf(of))
							? JavaIntrinsics.predicate(candidate) : null;
			if (predicate != null && predicates.add(predicate)) {
				arms.add(list(list(predicate, RECEIVER), classValue(raw(candidate))));
			}
		}
		if (holdsArrays(of)) {
			LispVal array = list(ARRAY_CLASS, RECEIVER, NIL);
			arms.add(list(call("and", call("vectorp", RECEIVER), call("not", call("stringp", RECEIVER))),
					any ? call("if", call("array-has-fill-pointer-p", RECEIVER), classValue(this.objectType), array)
							: array));
		}
		arms.add(list(T, runtimeCall("no-class", RECEIVER)));
		return list(cl("defun"), classOfName(of), list(RECEIVER), list(arms));
	}

	/**
	 * The function answering the class object of an array by the class the run time tells
	 * of it: one a conversion recorded, the static type's, or what its vector says.
	 */
	private LispVal arrayClassFunction() {
		LispSymbol fallback = new LispSymbol("%f");
		LispSymbol name = new LispSymbol("%n");
		Set<ArrayType> known = new LinkedHashSet<>();
		for (Primitive told : List.of(Primitive.INT, Primitive.CHAR, Primitive.BYTE, Primitive.DOUBLE, Primitive.FLOAT,
				Primitive.BOOLEAN)) {
			known.add(new ArrayType(told));
		}
		known.add(new ArrayType(this.objectType));
		known.addAll(this.classes.arrayRecords);
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("cond"));
		Set<String> names = new HashSet<>();
		for (ArrayType array : known) {
			if (names.add(descriptor(array))) {
				arms.add(list(call("equal", name, string(descriptor(array))), classValue(array)));
			}
		}
		// one made where the program runs, by an array type's class object asked there
		arms.add(list(T,
				this.classes.arrayTypesMade
						? call("or", runtimeCall("array-type", name, NIL), runtimeCall("no-class", RECEIVER))
						: runtimeCall("no-class", RECEIVER)));
		return list(cl("defun"), ARRAY_CLASS, list(RECEIVER, fallback),
				JavaForms.let(name, runtimeCall("array-class-name", RECEIVER, fallback, T), List.of(list(arms))));
	}

	/**
	 * A call of {@code MethodHandle.invokeExact} or {@code invoke}, a signature
	 * polymorphic method (JLS 15.12.3): the call's type is the types of its arguments --
	 * {@code Void} the null literal's --, returning {@code void} where the call is a
	 * statement (not a lambda's body, as javac reads it), the cast's type erased where it
	 * is a cast's operand, else {@code Object}. The library is handed that type, made at
	 * the call's first run, and the arguments boxed as their types box them; what it
	 * answers, boxed as the type returns, is unboxed or taken as it is.
	 */
	private Typed polymorphic(Call call, Typed receiver, boolean statement, @Nullable JavaType castTo) {
		List<LispVal> types = new ArrayList<>(List.of(cl("vector")));
		List<LispVal> values = new ArrayList<>(List.of(cl("vector")));
		for (Expression argument : call.arguments()) {
			if (needsTarget(argument)) {
				// no type to take: javac's words
				throw error(call.pos(),
						"method " + call.name() + " in class MethodHandle cannot be applied to given types");
			}
			Typed value = expression(argument, null);
			JavaType type = value.type() == JavaType.Null.NULL ? this.classes.libraryType("java.lang.Void")
					: value.type() instanceof Primitive ? value.type() : this.types.erased(value.type());
			if (type == Primitive.VOID) {
				throw error(argument.pos(), "'void' type not allowed here");
			}
			types.add(classValue(type, false));
			values.add(type instanceof Primitive ? box(value).form() : value.form());
		}
		JavaType returned = statement ? Primitive.VOID
				: castTo == null ? this.objectType : castTo instanceof Primitive ? castTo : this.types.erased(castTo);
		LispVal made = library(METHOD_TYPE, "site", List.of(classValue(returned, false), list(types)));
		if (this.session == null) {
			// the type of a call is made once
			LispSymbol site = new LispSymbol("%site" + this.sites++);
			this.variables.add(list(cl("defvar"), site, NIL));
			made = call("or", site, call("setq", site, made));
		}
		// a handle that is null is refused by the library, after the arguments
		LispVal form = library(METHOD_HANDLE, call.name().equals("invokeExact") ? "exact" : "adapted",
				List.of(receiver.form(), made, list(values)));
		if (returned == Primitive.VOID) {
			return new Typed(form, Primitive.VOID);
		}
		if (returned instanceof Primitive primitive) {
			return unbox(new Typed(form, JavaIntrinsics.boxed(primitive, this.classes)));
		}
		return new Typed(form, returned);
	}

	private Typed invocation(Call call, @Nullable JavaType expected, boolean discard) {
		int pos = call.pos();
		boolean flexible = this.flexible;
		this.flexible = false;
		JavaType castTo = this.castOperand == call ? this.castType : null;
		boolean statement = this.statementExpression == call;
		if (isClass(call.target())) {
			Expression named = Objects.requireNonNull(call.target());
			if ((call.name().equals("getName") || call.name().equals("getSimpleName")) && call.arguments().isEmpty()) {
				LispVal text = classRead(named, call.name().equals("getName")).name();
				this.nonNull.add(text);
				return new Typed(text, this.stringType);
			}
			if (call.name().equals("isArray") && call.arguments().isEmpty() && named instanceof Call of) {
				return new Typed(isArray(of), Primitive.BOOLEAN);
			}
			if (call.name().equals("equals") && call.arguments().size() == 1 && isClass(call.arguments().get(0))) {
				return new Typed(sameClass(named, call.arguments().get(0), false, pos), Primitive.BOOLEAN);
			}
			if (named instanceof JavaSyntax.ClassLiteral literal) {
				literalLookup(literal, call);
				if (literalResource(literal, call) instanceof Typed read) {
					return read;
				}
			}
		}
		if (call.name().equals("getClass") && call.arguments().isEmpty() && !call.onSuper()) {
			return classOf(call);
		}
		if (call.name().startsWith("find") && call.target() != null) {
			literalHandle(call);
		}
		List<JavaType> explicit = new ArrayList<>();
		for (TypeName written : call.typeArguments()) {
			explicit.add(type(written));
		}
		if (call.onSuper()) {
			return superCall(call, explicit, expected);
		}
		Typed receiver = null;
		JavaClass in = this.currentClass;
		LispVal path = this.staticContext ? null : THIS;
		JavaType site = null;
		boolean qualified = false;
		List<JavaClass> imported = List.of();
		if (call.target() != null) {
			JavaClass named = classNamed(call.target());
			if (named != null && call.name().equals("newUpdater") && JavaIntrinsics.FIELD_UPDATERS.contains(named.name)
					&& call.arguments().stream().anyMatch(argument -> literalInside(argument) != null)) {
				return fieldUpdater(call, named);
			}
			if (named != null) {
				in = named;
				qualified = true;
			}
			else {
				Typed direct = directReceiver(call.target(), call.name(), discard);
				receiver = direct != null ? direct : expression(call.target(), null);
				if (receiver.type() instanceof ClassType || receiver.type() instanceof Variable) {
					ClassType through = this.types.site(receiver.type());
					if (receiver.type() instanceof Variable variable && variable.parameter().bounds.size() > 1) {
						// a type variable with several bounds has the methods of each
						for (JavaType bound : variable.parameter().bounds) {
							ClassType candidate = this.types.site(bound);
							if (!methods(candidate.declaration(), call.name()).isEmpty()) {
								through = candidate;
								break;
							}
						}
					}
					in = through.declaration();
					site = through;
				}
				else if (receiver.type() instanceof ArrayType array) {
					if (call.name().equals("clone") && call.arguments().isEmpty()) {
						if (isExact(array) || !this.classes.arrayTypes) {
							return new Typed(call("copy-seq", receiver.form()), array);
						}
						LispSymbol original = temporary();
						return new Typed(JavaForms.let(original, receiver.form(),
								List.of(arrayLike(call("copy-seq", original), original))), array);
					}
					in = this.objectType.declaration();
				}
				else {
					throw error(pos, receiver.type() + " cannot be dereferenced");
				}
			}
		}
		else {
			// the innermost class around the call that has a method of the name
			JavaClass type = this.currentClass;
			LispVal instance = path;
			while (type != null && inherited(type, call.name()).isEmpty()) {
				instance = type.hasOuterInstance() && instance != null
						? list(slotAccessor(type, outerSlot(type)), instance) : null;
				type = type.outer;
			}
			if (type != null) {
				in = type;
				path = instance;
			}
			else {
				imported = imported(call.name(),
						owner -> methods(owner, call.name()).stream().anyMatch(method -> method.isStatic));
				if (!imported.isEmpty()) {
					in = imported.get(0);
					qualified = true;
				}
			}
			site = in.self();
		}
		this.flexible = flexible;
		if (receiver != null && in.name.equals(METHOD_HANDLE) && explicit.isEmpty()
				&& (call.name().equals("invokeExact") || call.name().equals("invoke"))) {
			return polymorphic(call, receiver, statement, castTo);
		}
		List<Method> candidates = call.target() == null && imported.isEmpty() ? inherited(in, call.name())
				: methods(in, call.name());
		if (imported.size() > 1) {
			// the static methods of a name every class importing it has
			candidates = new ArrayList<>();
			for (JavaClass owner : imported) {
				for (Method method : methods(owner, call.name())) {
					if (method.isStatic && !candidates.contains(method)) {
						candidates.add(method);
					}
				}
			}
		}
		Resolved resolved = resolve(candidates, site, explicit, call.arguments(), expected, "method " + call.name(), in,
				pos);
		Method method = resolved.method();
		if (method.isStatic && method.name.equals("forName") && method.owner.name.equals(CLASS)
				&& resolved.arguments().get(0).constant() instanceof String named) {
			literalForName(named);
		}
		enumUniverse(method, resolved.returnType());
		int caret = callCaret(pos, call.name());
		raises(call, caret >= 0 ? caret : pos, resolved.thrown(), null);
		uncheckedCall(method, site, caret);
		checkAccess(method.isPrivate, method.declaration, method.owner, method.signature(), pos);
		if (receiver != null && !method.isStatic
				&& (method.isProtected || (!isLibrary(this.currentClass) && method.declaration != null
						&& method.declaration.modifiers().contains("protected")
						&& !Objects.equals(this.classes.packageOf(method.owner), this.unit.packageName())))) {
			protectedReach(method, receiver.type(), pos);
		}
		if (!method.isStatic && receiver == null) {
			if (qualified || path == null) {
				throw error(pos,
						"non-static method " + method.signature() + " cannot be referenced from a static context");
			}
			receiver = path == THIS ? thisValue(pos) : new Typed(path, in.self());
		}
		if (method.emitter != null) {
			if (VISIBLE.contains(method.owner.name)) {
				this.effects.visible = true;
			}
			if (receiver != null && call.target() != null) {
				receiver = new Typed(dereferenced(receiver.form()), receiver.type());
			}
			this.classes.intrinsicsCalled.add(method);
			LispVal form = intrinsicCall(method,
					new Invocation(method.isStatic ? null : receiver, resolved.arguments(), discard, pos));
			if (resolved.returnType().equals(this.stringType) && method.returnType.equals(this.stringType)
					&& !method.answersNull) {
				this.nonNull.add(form);
			}
			return new Typed(located(form, pos), resolved.returnType());
		}
		LispVal form;
		Integer control = JavaIntrinsics.formatControl(method.owner.name + "." + method.signature());
		if (control != null) {
			names(resolved.arguments().get(control), JavaIntrinsics.FORMAT_NAMING);
			formatsArguments(resolved.arguments().get(control + 1));
		}
		if (method.isStatic || method.isPrivate) {
			passes(method, resolved.arguments());
		}
		List<LispVal> arguments = toldCaller(method, resolved.forms());
		if (method.isStatic) {
			called(method);
			uses(method.owner);
			form = form(JavaNames.function(method), arguments);
		}
		else {
			LispVal object = toldReceiver(method, Objects.requireNonNull(receiver).form(), arguments);
			form = virtual(in, method, call.target() != null ? object : null, object, arguments);
		}
		this.passing = null;
		LispVal placed = located(form, pos);
		if (!method.isStatic) {
			acquires(method, call.target(), Objects.requireNonNull(receiver).type(), placed);
		}
		return new Typed(placed, resolved.returnType());
	}

	/**
	 * The class whose methods making a log record are told where they are called from.
	 */
	private static final String LOGGER = "java.util.logging.Logger";

	/** The methods of {@code Logger} that make a record or are handed one. */
	private static final Set<String> LOGGING = Set.of("log", "logp", "severe", "warning", "info", "config", "fine",
			"finer", "finest", "entering", "exiting", "throwing");

	/**
	 * SLF4J's logger and its fluent builder, whose methods making a record are told where
	 * they are called from as {@code Logger}'s are: under a JDK its binding to
	 * {@code java.util.logging} walks the stack for the frame calling SLF4J and names it
	 * in the record.
	 */
	private static final String SLF4J_LOGGER = "org.slf4j.Logger";

	private static final String SLF4J_BUILDER = "org.slf4j.spi.LoggingEventBuilder";

	/** The methods of SLF4J's logger that make a record. */
	private static final Set<String> SLF4J_LOGGING = Set.of("trace", "debug", "info", "warn", "error");

	/**
	 * The packages of SLF4J, whose classes -- its binding among them -- tell a logger
	 * nothing: their call of a logger stands for the program's call of SLF4J, which told
	 * it already.
	 */
	private static final String SLF4J = "org.slf4j.";

	/**
	 * Commons Logging's log, whose methods making a record are told where they are called
	 * from as SLF4J's are: under a JDK its implementations name the frame calling it,
	 * found on the stack.
	 */
	private static final String JCL_LOG = "org.apache.commons.logging.Log";

	/** The methods of Commons Logging's log that make a record. */
	private static final Set<String> JCL_LOGGING = Set.of("trace", "debug", "info", "warn", "error", "fatal");

	/**
	 * The packages of Commons Logging, whose classes tell a logger nothing, as SLF4J's:
	 * the program's call of Commons Logging told it already, through SLF4J too.
	 */
	private static final String JCL = "org.apache.commons.logging.";

	/**
	 * The arguments of a call, the last telling a logger's method making a record where
	 * the call stands once every argument is evaluated: the class and the method a JDK
	 * finds by walking the stack (the frame calling the logger). A method reference tells
	 * it nothing: what calls it is not known here.
	 */
	private List<LispVal> toldCaller(Method method, List<LispVal> arguments) {
		if (method.owner.name.equals(LOGGER) && method.name.startsWith("logp") && arguments.size() > 2
				&& facade(this.currentClass)) {
			// a facade's implementation giving the method it found on a stack the subset
			// does not keep (Commons Logging's Jdk14Logger: its stack's third frame)
			// gives
			// the method the program's call told
			List<LispVal> given = new ArrayList<>(arguments);
			given.set(2, library(LOGGER, "toldMethod", List.of(given.get(2))));
			return given;
		}
		if (arguments.isEmpty() || !tellsCaller(method)) {
			return arguments;
		}
		List<LispVal> given = new ArrayList<>(arguments);
		given.set(given.size() - 1, call("prog1", given.get(given.size() - 1), told()));
		return given;
	}

	/**
	 * The receiver of a call of a method making a record that takes no argument
	 * ({@code LoggingEventBuilder.log()}), telling where the call stands once it is
	 * evaluated; any other receiver as it is.
	 */
	private LispVal toldReceiver(Method method, LispVal receiver, List<LispVal> arguments) {
		return arguments.isEmpty() && tellsCaller(method) ? call("prog1", receiver, told()) : receiver;
	}

	/**
	 * Whether a call of the method makes a log record, or hands one on, and the class
	 * lowered calls it for itself: not the logger's own class, nor a class of a facade.
	 */
	private boolean tellsCaller(Method method) {
		boolean records = switch (method.owner.name) {
			case LOGGER -> LOGGING.contains(method.name);
			case SLF4J_LOGGER -> SLF4J_LOGGING.contains(method.name);
			case SLF4J_BUILDER -> method.name.equals("log");
			case JCL_LOG -> JCL_LOGGING.contains(method.name);
			default -> false;
		};
		return records && this.currentClass != method.owner && !facade(this.currentClass);
	}

	/** Whether a class is of a logging facade, SLF4J or Commons Logging. */
	private static boolean facade(JavaClass type) {
		return type.name.startsWith(SLF4J) || type.name.startsWith(JCL);
	}

	/** {@code Logger.calledFrom} of where the call lowered stands. */
	private LispVal told() {
		boolean known = this.referenced == null;
		return library(LOGGER, "calledFrom", List.of(known ? string(this.currentClass.binaryName) : NIL,
				known && this.methodName != null ? string(this.methodName) : NIL));
	}

	/**
	 * A call taking a lock -- {@code lock()}, {@code lockInterruptibly()},
	 * {@code tryLock()} of a {@code java.util.concurrent.locks.Lock} --, told to
	 * {@link JavaResumable} with what is known of the lock: a thread suspended while it
	 * holds one makes every {@code lock()} of a lock that may be the same object a wait.
	 * @param target the receiver as written, {@code null} for {@code this}
	 */
	private void acquires(Method method, @Nullable Expression target, JavaType type, LispVal call) {
		if (this.suspended && LOCK_TAKING.contains(method.name)
				&& under(method.owner, "java.util.concurrent.locks.Lock")) {
			this.resumable.acquired.put(call, lockOf(target, type));
		}
	}

	/**
	 * Whether a statement and the {@code try} after it are {@code x.lock(); try { .. }
	 * finally { x.unlock(); }}: the {@code try} gives back the lock the statement takes,
	 * read the same way at both ends -- a local, a {@code final} field named alone or
	 * through {@code this}.
	 */
	private boolean takesAround(Statement statement, Statement next) {
		if (!(statement instanceof ExpressionStatement first) || !(first.expression() instanceof Call taking)
				|| !taking.name().equals("lock") || !taking.arguments().isEmpty() || taking.onSuper()
				|| !(next instanceof Try attempt) || attempt.finallyBlock() == null
				|| attempt.finallyBlock().statements().size() != 1
				|| !(attempt.finallyBlock().statements().get(0) instanceof ExpressionStatement last)
				|| !(last.expression() instanceof Call giving) || !giving.name().equals("unlock")
				|| !giving.arguments().isEmpty() || giving.onSuper()) {
			return false;
		}
		if (taking.target() instanceof Name lock && giving.target() instanceof Name given
				&& lock.identifier().equals(given.identifier())) {
			Named read = variable(lock.identifier(), lock.pos(), true);
			return read instanceof LocalRef || read instanceof CaptureRef
					|| (read instanceof FieldRef field && field.field().isFinal);
		}
		return taking.target() instanceof Select lock && lock.target() instanceof This
				&& giving.target() instanceof Select given && given.target() instanceof This
				&& lock.name().equals(given.name()) && finalField(lock) != null;
	}

	/**
	 * {@code System.out} or {@code System.err} as the receiver of a member the host's
	 * stream answers itself -- of a call or of a method reference --, or {@code null}:
	 * the field's object is not made for such a call, so a program that only prints holds
	 * no class of the library's streams. A {@code printf} whose value is used is the
	 * object's.
	 */
	private @Nullable Typed directReceiver(@Nullable Expression target, String name, boolean discard) {
		Field field = null;
		if (target instanceof Select select) {
			JavaClass named = classNamed(select.target());
			field = named == null || !named.intrinsic ? null : field(named, select.name());
		}
		else if (target instanceof Name simple
				&& variable(simple.identifier(), simple.pos(), true) instanceof FieldRef imported) {
			// a static import of the field
			field = imported.field();
		}
		if (field == null || field.direct == null) {
			return null;
		}
		List<Method> answered = methods(((ClassType) field.direct.type()).declaration(), name);
		boolean answersNothing = !answered.isEmpty() && answered.get(0).returnType == Primitive.VOID;
		boolean answersItself = name.equals("printf") || name.equals("format");
		return answersNothing && (discard || !answersItself) ? field.direct : null;
	}

	/**
	 * {@code super.m(..)}, {@code Outer.super.m(..)} and {@code I.super.m(..)}: the body
	 * the superclass of this class or of the class around it has, or the default an
	 * interface this class implements has, called directly.
	 */
	private Typed superCall(Call call, List<JavaType> explicit, @Nullable JavaType expected) {
		int pos = call.pos();
		JavaClass self = this.currentClass;
		LispVal object = null;
		JavaClass above;
		ClassType site;
		Expression qualifier = call.target();
		JavaClass named = qualifier == null ? null : classNamed(qualifier);
		if (qualifier != null && named == null) {
			throw error(qualifier.pos(), "cannot find symbol: class " + dotted(qualifier));
		}
		if (named != null && named.isInterface) {
			// I.super.m(): a default of an interface the class names in its implements
			if (!self.interfaces.contains(named)) {
				throw error(pos, "not an enclosing class: " + named.simpleName());
			}
			above = named;
			site = Objects.requireNonNull(this.types.asSuper(self.self(), named));
		}
		else {
			if (named != null && named != self) {
				// Outer.super.m(): the superclass's body, on the instance around
				object = enclosing(named);
				if (object == null) {
					throw error(pos, "not an enclosing class: " + named.simpleName());
				}
				self = named;
			}
			JavaClass superclass = self.superclass != null ? self.superclass : self.intrinsicSuperclass;
			above = superclass != null ? superclass : this.objectType.declaration();
			site = superclass != null ? self.supertype(above) : this.objectType;
		}
		Resolved resolved = resolve(methods(above, call.name()), site, explicit, call.arguments(), expected,
				"method " + call.name(), above, pos);
		int caret = callCaret(pos, call.name());
		raises(call, caret >= 0 ? caret : pos, resolved.thrown(), null);
		Method body = implementation(above, resolved.method());
		String ownBody = resolved.method().emitter == null ? null : switch (resolved.method().signature()) {
			case "toString()" -> "to-string";
			case "equals(Object)" -> "equals";
			case "hashCode()" -> "hash-code";
			default -> null;
		};
		if (ownBody != null && resolved.method().owner == this.objectType.declaration()) {
			// no class above declares it: Object's own body, which the run time has
			LispVal receiver = object != null ? object : thisValue(pos).form();
			return new Typed(form(JavaForms.runtime(ownBody), prepend(receiver, resolved.forms())),
					resolved.returnType());
		}
		if (resolved.method().emitter != null && !resolved.method().isAbstract
				&& (isExtended(resolved.method().owner) || resolved.method().owner == this.objectType.declaration())) {
			// the body a class whose values are Lisp values has for it, or Object's other
			// members: its form, on this instance
			LispVal receiver = object != null ? object : thisValue(pos).form();
			JavaClass owner = resolved.method().owner;
			this.classes.intrinsicsCalled.add(resolved.method());
			return new Typed(located(resolved.method().emitter.emit(this,
					new Invocation(
							new Typed(receiver, owner == this.objectType.declaration() ? self.self() : owner.self()),
							resolved.arguments(), false, pos)),
					pos), resolved.returnType());
		}
		if (body == null || (resolved.method().emitter != null && !resolved.method().isAbstract)) {
			throw resolved.method().emitter != null && !resolved.method().isAbstract
					? unsupported(pos, "a call of one of Object's methods through super")
					: error(pos, "abstract method " + resolved.method().signature() + " in "
							+ resolved.method().owner.simpleName() + " cannot be accessed directly");
		}
		checkAccess(body.isPrivate, body.declaration, body.owner, body.signature(), pos);
		called(body);
		LispVal receiver = object != null ? object : thisValue(pos).form();
		LispVal placed = located(
				form(JavaNames.function(body), prepend(receiver, toldCaller(resolved.method(), resolved.forms()))),
				pos);
		acquires(body, null, self.self(), placed);
		return new Typed(placed, resolved.returnType());
	}

	/**
	 * A body a call can run, and the class whose instances run it.
	 *
	 * @param tested the class to test the receiver for: the one declaring the body, or
	 * for an interface's default the topmost class inheriting it
	 * @param method the body
	 */
	private record Body(JavaClass tested, Method method) {
	}

	/** The bodies a call of a method on a receiver of a static type can run. */
	private List<Body> bodies(JavaClass receiver, Method method) {
		Map<JavaClass, Method> tested = new LinkedHashMap<>();
		for (JavaClass candidate : this.classes.all()) {
			if (candidate.isAbstract || !isMade(candidate) || !candidate.isSubclassOf(receiver)) {
				continue;
			}
			Method body = implementation(candidate, method);
			if (body == null) {
				continue;
			}
			JavaClass top = body.owner;
			if (top.isInterface) {
				top = candidate;
				while (top.superclass != null && implementation(top.superclass, method) == body) {
					top = top.superclass;
				}
			}
			tested.put(top, body);
		}
		return tested.entrySet()
			.stream()
			.map(entry -> new Body(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingInt((Body body) -> body.tested().depth()).reversed())
			.toList();
	}

	/**
	 * What a lambda made for an interface runs for a method called on it: {@code null}
	 * for the lambda itself -- the method is the interface's one abstract method --, else
	 * the default method the interface has for it.
	 */
	private @Nullable Method lambdaBody(JavaClass target, Method method) {
		Method single = Objects.requireNonNull(functionalMethod(target));
		return signatureIn(target, single).equals(signatureIn(target, method)) ? null : implementation(target, method);
	}

	/**
	 * The function a call of an instance method goes to: the one body under the
	 * receiver's static type, or the function choosing among several by the receiver's
	 * class; {@code null} when the receiver can only be a lambda that is a function.
	 *
	 * <p>
	 * A lambda is a function, tested first. Where the lambdas a receiver can be would not
	 * all run the same thing -- one interface's own method is another's default -- they
	 * are made structures holding the function, each tested by its own predicate, and the
	 * program is lowered again.
	 */
	private @Nullable LispSymbol target(JavaClass receiver, Method method) {
		if (this.classes.census != null) {
			this.classes.census.names(method);
		}
		if (method.isPrivate) {
			called(method);
			return JavaNames.function(method);
		}
		List<Body> bodies = bodies(receiver, method);
		for (Body body : bodies) {
			called(body.method());
		}
		// the classes of the library whose values are Lisp values and implement the
		// method
		Map<JavaClass, Method> intrinsics = new LinkedHashMap<>();
		if (!receiver.intrinsic) {
			for (JavaClass candidate : this.classes.all()) {
				LispSymbol predicate = candidate.intrinsic && candidate.held && !candidate.isInterface
						&& candidate.isSubclassOf(receiver) ? JavaIntrinsics.predicate(candidate) : null;
				if (predicate == null) {
					continue;
				}
				String signature = signatureIn(candidate, method);
				for (Method member : candidate.methods) {
					if (!member.isStatic && member.emitter != null && member.signature().equals(signature)) {
						intrinsics.put(candidate, member);
					}
				}
			}
		}
		List<JavaClass> plain = new ArrayList<>();
		List<JavaClass> tagged = new ArrayList<>();
		Set<Object> ran = new HashSet<>();
		Method inherited = null;
		boolean called = false;
		for (JavaClass made : this.classes.lambdaTargets) {
			if (!made.isSubclassOf(receiver) || functionalMethod(made) == null) {
				continue;
			}
			Method body = lambdaBody(made, method);
			if (body != null) {
				called(body);
			}
			if (this.classes.taggedLambdas.contains(made)) {
				tagged.add(made);
				continue;
			}
			plain.add(made);
			ran.add(body == null ? T : body);
			inherited = body;
			called |= body == null;
		}
		if (ran.size() > 1) {
			// the function does not tell which of them it was made for
			tag(plain);
			tagged.addAll(plain);
			plain = List.of();
			called = false;
			inherited = null;
		}
		boolean lambdas = !plain.isEmpty() || !tagged.isEmpty();
		if (bodies.isEmpty() && intrinsics.isEmpty() && tagged.isEmpty() && !plain.isEmpty()) {
			return called ? null : JavaNames.function(Objects.requireNonNull(inherited));
		}
		if (bodies.size() == 1 && intrinsics.isEmpty() && tagged.isEmpty()
				&& (!lambdas || (!called && inherited == bodies.get(0).method()))) {
			return JavaNames.function(bodies.get(0).method());
		}
		LispSymbol name = JavaNames.dispatcher(receiver, method);
		if (!this.dispatchers.containsKey(name.name())) {
			List<LispVal> parameters = new ArrayList<>();
			parameters.add(RECEIVER);
			List<Typed> arguments = new ArrayList<>();
			for (int i = 0; i < method.parameters.size(); i++) {
				parameters.add(new LispSymbol("%a" + i));
			}
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			if (!plain.isEmpty()) {
				arms.add(list(call("functionp", RECEIVER),
						called ? calledThrough(receiver, false, form(cl("funcall"), parameters))
								: form(JavaNames.function(Objects.requireNonNull(inherited)), parameters)));
			}
			for (JavaClass made : tagged) {
				Method body = lambdaBody(made, method);
				arms.add(list(list(lambdaTest(made), RECEIVER),
						body != null ? form(JavaNames.function(body), parameters) : calledThrough(made, true, form(
								cl("funcall"),
								prepend(lambdaFunction(made, RECEIVER), parameters.subList(1, parameters.size()))))));
			}
			for (Body body : bodies) {
				arms.add(list(list(JavaNames.predicate(body.tested()), RECEIVER), bridged(method, body.method(),
						parameters, form(JavaNames.function(body.method()), parameters))));
			}
			for (Map.Entry<JavaClass, Method> intrinsic : intrinsics.entrySet()) {
				Method member = intrinsic.getValue();
				arguments.clear();
				for (int i = 0; i < member.parameters.size(); i++) {
					arguments.add(new Typed(parameters.get(i + 1), member.parameters.get(i)));
				}
				arms.add(list(list(Objects.requireNonNull(JavaIntrinsics.predicate(intrinsic.getKey())), RECEIVER),
						bridged(method, member, parameters,
								Objects.requireNonNull(member.emitter)
									.emit(this, new Invocation(new Typed(RECEIVER, intrinsic.getKey().self()),
											List.copyOf(arguments), false, 0)))));
			}
			arms.add(list(T, runtimeCall("no-receiver", RECEIVER, string(method.signature()))));
			this.dispatchers.put(name.name(), list(cl("defun"), name, list(parameters), list(arms)));
		}
		return name;
	}

	/**
	 * The parameters a body takes narrower, erased, than the method a call names: what a
	 * JDK's bridge method casts ({@code Integer.compareTo(Integer)} called as
	 * {@code Comparable.compareTo(Object)}), by index -- of a class a value can be tested
	 * for.
	 */
	private List<Integer> narrowed(Method called, Method body) {
		List<Integer> narrowed = new ArrayList<>();
		for (int i = 0; i < called.parameters.size(); i++) {
			JavaType declared = this.types.erased(called.parameters.get(i));
			JavaType taken = this.types.erased(body.parameters.get(i));
			if (taken instanceof ClassType type && !taken.equals(this.objectType) && !isSubtype(declared, taken)
					&& (!type.declaration().intrinsic || JavaIntrinsics.predicate(type.declaration()) != null)) {
				narrowed.add(i);
			}
		}
		return narrowed;
	}

	/**
	 * A body's call in a dispatcher, behind the casts a bridge method makes: an argument
	 * the body takes narrower is tested, the first parameter first, and one that is no
	 * {@code null} and no instance of the body's parameter is the
	 * {@code ClassCastException} a JDK's bridge throws.
	 */
	private LispVal bridged(Method called, Method body, List<LispVal> parameters, LispVal call) {
		List<Integer> narrowed = narrowed(called, body);
		LispVal guarded = call;
		for (int at = narrowed.size() - 1; at >= 0; at--) {
			int i = narrowed.get(at);
			LispSymbol argument = (LispSymbol) parameters.get(i + 1);
			JavaType taken = this.types.erased(body.parameters.get(i));
			guarded = call("if",
					call("or", call("null", argument), instanceTest(argument, this.objectType, taken, 0, "a cast")),
					guarded, castRefused(argument, taken));
		}
		return guarded;
	}

	/**
	 * Whether the program can hold an instance of a class of Java source under a class
	 * whose values are Lisp values: {@code Number}'s atomics, and a program's own.
	 */
	private boolean isExtended(JavaClass intrinsic) {
		if (!intrinsic.intrinsic || intrinsic.isInterface || !intrinsic.isAbstract) {
			return false;
		}
		for (JavaClass candidate : this.classes.all()) {
			if (isMade(candidate) && candidate.isSubclassOf(intrinsic)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A call of an intrinsic member: its form. Where the receiver can be an instance of a
	 * class of Java source under the member's class, the function choosing the body by
	 * the receiver's class, deepest first, whose last arm is that form -- for the values
	 * that are Lisp values, and for a class inheriting the member as it is.
	 */
	private LispVal intrinsicCall(Method method, Invocation call) {
		JavaEmitter emitter = Objects.requireNonNull(method.emitter);
		JavaClass owner = method.owner;
		// a protected member of Object is called through a class around the call or one
		// under it, whose own classes under it may override it
		JavaClass through = method.isProtected && call.receiver() != null
				&& (call.receiver().type() instanceof ClassType || call.receiver().type() instanceof Variable)
						? this.types.site(call.receiver().type()).declaration() : owner;
		List<Body> bodies = method.isStatic || method.isConstructor() || (through == owner && !isExtended(owner))
				? List.of() : bodies(through, method);
		if (bodies.isEmpty()) {
			return emitter.emit(this, call);
		}
		for (Body body : bodies) {
			called(body.method());
		}
		LispSymbol name = JavaNames.dispatcher(through, method);
		if (!this.dispatchers.containsKey(name.name())) {
			List<LispVal> parameters = new ArrayList<>();
			parameters.add(RECEIVER);
			List<Typed> arguments = new ArrayList<>();
			for (int i = 0; i < method.parameters.size(); i++) {
				LispSymbol parameter = new LispSymbol("%a" + i);
				parameters.add(parameter);
				arguments.add(new Typed(parameter, method.parameters.get(i)));
			}
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			for (Body body : bodies) {
				arms.add(list(list(JavaNames.predicate(body.tested()), RECEIVER),
						form(JavaNames.function(body.method()), parameters)));
			}
			arms.add(list(T,
					emitter.emit(this, new Invocation(new Typed(RECEIVER, through.self()), arguments, false, 0))));
			this.dispatchers.put(name.name(), list(cl("defun"), name, list(parameters), list(arms)));
		}
		List<LispVal> forms = new ArrayList<>();
		forms.add(call.self());
		for (Typed argument : call.arguments()) {
			forms.add(argument.form());
		}
		return form(name, forms);
	}

	@Override
	public LispVal member(String className, String signature, @Nullable Typed receiver) {
		for (Method method : this.classes.library(className).methods) {
			if (!method.isStatic && method.emitter != null && method.signature().equals(signature)) {
				return intrinsicCall(method, new Invocation(receiver, List.of(), false, 0));
			}
		}
		throw new IllegalStateException("no member " + signature + " of " + className);
	}

	private LispVal virtual(JavaClass receiver, Method method, LispVal object, List<LispVal> arguments) {
		return virtual(receiver, method, null, object, arguments);
	}

	/**
	 * A call of an instance method.
	 * @param written the receiver as the program wrote it, which can be {@code null}: a
	 * call going straight to a body tests it, where a dispatcher's last arm answers it
	 */
	private LispVal virtual(JavaClass receiver, Method method, @Nullable LispVal written, LispVal object,
			List<LispVal> arguments) {
		LispSymbol target = target(receiver, method);
		boolean dispatched = target != null && this.dispatchers.containsKey(target.name());
		LispVal checked = written == null || dispatched ? object : dereferenced(object);
		return target == null ? calledThrough(receiver, false, form(cl("funcall"), prepend(checked, arguments)))
				: form(target, prepend(checked, arguments));
	}

	/**
	 * A reference a member is read or called through: tested for {@code null}, which
	 * raises {@code NullPointerException}, unless it surely is none. A structure's
	 * accessor and an array's {@code aref} need no test: the host's type error over
	 * {@code nil} is the exception already ({@code %java-exception-of}).
	 */
	private LispVal dereferenced(LispVal form) {
		if (form == THIS || form == RECEIVER || form instanceof LispString || this.nonNull.contains(form)
				|| (form instanceof LispSymbol symbol && symbol.name().startsWith("*"))) {
			return form;
		}
		return call("or", form, runtimeCall("npe"));
	}

	/**
	 * What the function making an instance takes: the instance around an inner class
	 * first, the constructor's arguments, then the locals a local class reads, as they
	 * are where the instance is made.
	 */
	private List<LispVal> instanceArguments(JavaClass made, @Nullable Expression outer, List<LispVal> arguments,
			int pos) {
		List<LispVal> all = new ArrayList<>();
		Expression given = made.superOuterGiven ? null : outer;
		if (made.hasOuterInstance()) {
			JavaClass around = Objects.requireNonNull(made.outer);
			LispVal instance;
			if (given != null) {
				instance = outerOf(given, around);
			}
			else {
				instance = enclosing(around);
				if (instance == null) {
					throw error(pos,
							this.staticContext ? "non-static variable this cannot be referenced from a static context"
									: this.early && this.currentClass.isSubclassOf(around)
											? "cannot reference this before supertype constructor has been called"
											: "an enclosing instance that contains " + around.simpleName() + "."
													+ made.simpleName() + " is required");
				}
			}
			all.add(instance);
		}
		else if (given != null) {
			throw error(pos, "qualified new of static class");
		}
		if (made.superOuterGiven) {
			all.add(outerOf(Objects.requireNonNull(outer),
					Objects.requireNonNull(Objects.requireNonNull(made.superclass).outer)));
		}
		all.addAll(arguments);
		for (JavaClass type = made; type != null; type = type.superclass) {
			for (JavaClass.Capture capture : List.copyOf(type.captures.values())) {
				all.add(read(new Name(pos, capture.name())).form());
			}
		}
		return all;
	}

	/**
	 * The instance an inner class is made in, written before {@code .new} or
	 * {@code .super}.
	 */
	private LispVal outerOf(Expression outer, JavaClass around) {
		Typed given = expression(outer, null);
		if (!(given.type() instanceof ClassType type) || !type.declaration().isSubclassOf(around)) {
			throw error(outer.pos(),
					"incompatible types: " + given.type() + " cannot be converted to " + around.simpleName());
		}
		return dereferenced(given.form());
	}

	private Typed creation(New creation, @Nullable JavaType expected) {
		TypeName written = creation.type();
		int pos = creation.pos();
		boolean flexible = this.flexible;
		this.flexible = false;
		boolean constant = this.makingConstant;
		this.makingConstant = false;
		ClassType type;
		if (creation.outer() != null) {
			// outer.new Inner(..): Inner is a member of the outer instance's class
			Typed outer = expression(creation.outer(), null);
			JavaClass member = outer.type() instanceof ClassType around
					? this.classes.find(written.name(), around.declaration(), this.unit, written.pos()) : null;
			if (member == null || !member.hasOuterInstance()) {
				throw error(written.pos(), "cannot find symbol: class " + written.name());
			}
			type = innerType(member, (ClassType) outer.type(), written);
		}
		else if (written.diamond()) {
			JavaClass made = this.classes.require(written.name(), written.pos(), this.currentClass, this.unit);
			if (made.typeParameters.isEmpty()) {
				throw error(pos, "cannot infer type arguments for " + made.simpleName() + "<>");
			}
			// the arguments are found as a method's are: from the constructor's arguments
			// and the type the instance is wanted as; the place gives those of the
			// classes around an inner class
			List<JavaType> arguments = new ArrayList<>(diamondArguments(made));
			arguments.addAll(this.classes.enclosingArguments(made, this.currentClass));
			type = new ClassType(made, List.copyOf(arguments));
		}
		else if (type(written) instanceof ClassType named) {
			type = named;
		}
		else {
			throw error(pos, "unexpected type");
		}
		JavaClass made = type.declaration();
		if (made.isEnum && !constant) {
			throw error(pos, "enum classes may not be instantiated");
		}
		List<JavaType> explicit = new ArrayList<>();
		for (TypeName argument : creation.typeArguments()) {
			explicit.add(type(argument));
		}
		if (!explicit.isEmpty() && written.diamond()) {
			throw error(written.pos(), "cannot infer type arguments for " + made.self());
		}
		if (creation.body() != null) {
			return anonymous(creation, written.diamond() ? anonymousType(type, creation, expected, pos) : type);
		}
		if (made.isAbstract) {
			throw error(pos, made.simpleName() + " is abstract; cannot be instantiated");
		}
		Resolved resolved;
		if (written.diamond()) {
			this.flexible = flexible;
			resolved = resolve(constructors(made), type, List.of(), creation.arguments(), expected,
					"constructor " + made.simpleName(), made, pos, diamondParameters(type), type);
			type = (ClassType) resolved.returnType();
		}
		else {
			resolved = resolve(constructors(made), type, explicit, creation.arguments(), null,
					"constructor " + made.simpleName(), made, pos);
			uncheckedCall(resolved.method(), type, callCaret(pos, null));
		}
		raises(creation, pos, resolved.thrown(), null);
		enumUniverse(resolved.method(), type);
		if (resolved.method().emitter != null) {
			this.classes.intrinsicsCalled.add(resolved.method());
			return new Typed(
					resolved.method().emitter.emit(this, new Invocation(null, resolved.arguments(), false, pos)), type);
		}
		if (!constant) {
			checkAccess(resolved.method().isPrivate, resolved.method().declaration, made,
					made.simpleName() + resolved.method().parameterList(), pos);
		}
		called(resolved.method());
		uses(made);
		makes(made);
		return new Typed(located(form(JavaNames.creator(resolved.method()),
				instanceArguments(made, creation.outer(), resolved.forms(), pos)), pos), type);
	}

	/**
	 * {@code new T(..) { .. }}: a class under {@code T}, declared and lowered where it is
	 * made, whose constructor hands its arguments to the one of {@code T} they pick.
	 */
	private Typed anonymous(New creation, ClassType extendedType) {
		JavaClass extended = extendedType.declaration();
		int pos = creation.pos();
		if (extended.intrinsic && !extended.isAbstract && !extended.name.equals("java.lang.Object")) {
			throw unsupported(pos, "an anonymous class of " + extended.name);
		}
		JavaClass made = this.classes.local(Objects.requireNonNull(creation.body()), this.currentClass,
				this.staticContext || this.early, extendedType);
		if (creation.type().diamond() && !extendedType.equals(made.supertype(extended))) {
			// made with <> against another type (a candidate tried, a call lowered once
			// more, an earlier pass): what lowering it made in this pass is dropped, the
			// body lowered again
			Lowering earlier = this.inferredLowerings.remove(made);
			if (earlier != null) {
				Set<LispVal> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
				dropped.addAll(earlier.forms());
				this.functions.removeIf(dropped::contains);
				earlier.classes().forEach(this.loweredLocals::remove);
			}
			// what the passes repeat for is not this: a pass tries the same types
			made.parameterized.put(extended, extendedType);
		}
		// outer.new Inner() { .. }: the instance around its superclass is the one given
		made.superOuterGiven = creation.outer() != null;
		if (!made.superOuterGiven && givesSuperOuter(made)) {
			throw error(pos, "an enclosing instance that contains " + innerName(extended) + " is required");
		}
		Method above = null;
		List<LispVal> arguments = List.of();
		raises(creation, pos, List.of(), null);
		if (extended.isInterface || extended.intrinsic) {
			if (!creation.arguments().isEmpty()) {
				throw error(pos, "anonymous class implements interface; cannot have arguments");
			}
		}
		else {
			List<JavaType> explicit = new ArrayList<>();
			for (TypeName argument : creation.typeArguments()) {
				explicit.add(type(argument));
			}
			Resolved resolved = resolve(constructors(extended), extendedType, explicit, creation.arguments(), null,
					"constructor " + extended.simpleName(), extended, pos);
			if (!extended.isEnum) {
				checkAccess(resolved.method().isPrivate, resolved.method().declaration, extended,
						extended.simpleName() + resolved.method().parameterList(), pos);
			}
			above = resolved.method();
			arguments = resolved.forms();
			enumUniverse(above, extendedType);
			// its constructor throws what the one it hands its arguments to does
			raises(creation, pos, resolved.thrown(), null);
		}
		Method passed = above;
		Method constructor = made.methods.stream()
			.filter(method -> method.isConstructor() && method.passesTo == passed)
			.findFirst()
			.orElse(null);
		if (constructor == null) {
			constructor = new Method(made, "<init>", above == null ? List.of() : above.parameters, Primitive.VOID,
					false, false, null, null, false);
			constructor.passesTo = above;
			made.methods.add(constructor);
			this.classes.changed();
		}
		if (creation.type().diamond() && !this.loweredLocals.contains(made)) {
			int forms = this.functions.size();
			int classes = this.loweredLocals.size();
			this.inferredLowerings.put(made, new Lowering(List.of(), List.of()));
			try {
				lowerLocal(made);
			}
			finally {
				this.inferredLowerings.put(made,
						new Lowering(List.copyOf(this.functions.subList(forms, this.functions.size())),
								this.loweredLocals.stream().skip(classes).toList()));
			}
		}
		else {
			lowerLocal(made);
		}
		called(constructor);
		uses(made);
		makes(made);
		return new Typed(
				located(form(JavaNames.creator(constructor), instanceArguments(made, creation.outer(), arguments, pos)),
						pos),
				new ClassType(made, List.of()));
	}

	// ---- statements

	/**
	 * Whether a statement cannot complete normally (JLS 14.22): what follows it is not
	 * reached. A condition the lowering has not met yet is no constant.
	 */
	private boolean exits(Statement statement) {
		return !JavaFlow.completes(statement, this.facts);
	}

	/** The pattern bindings the statement just lowered leaves in scope after it. */
	private Map<String, Local> introduced = Map.of();

	/** The label of the statement about to be lowered, which a loop or a switch takes. */
	private @Nullable String pendingLabel;

	/**
	 * The type the switch expression being lowered has, or {@code null} while it is
	 * found.
	 */
	private @Nullable JavaType yieldType;

	/** The values of the arms of the switch expression whose type is being found. */
	private @Nullable List<Typed> yields;

	private List<LispVal> sequence(List<Statement> statements, int from, boolean tail) {
		return sequence(statements, from, tail, null);
	}

	/**
	 * Lowers the statements of a block from one of them on.
	 * @param tail whether the value of the last one is the value of the function, so that
	 * a {@code return} there is its value and no exit
	 * @param hoisted where the locals declared among the statements go when their scope
	 * is wider than the statements: a switch's block, whose cases share one; {@code null}
	 * for a block, where a declaration is a {@code let} around what follows it
	 */
	private List<LispVal> sequence(List<Statement> statements, int from, boolean tail,
			@Nullable List<LispVal> hoisted) {
		List<LispVal> forms = new ArrayList<>();
		// the lock() of x.lock(); try { .. } finally { x.unlock(); }, while the try after
		// it is lowered
		LispVal locking = null;
		for (int i = from; i < statements.size(); i++) {
			Statement statement = statements.get(i);
			boolean last = i == statements.size() - 1;
			LispVal locked = locking;
			locking = null;
			if (statement instanceof LocalVariable variable) {
				if (hoisted != null) {
					for (Declarator declarator : variable.declarators()) {
						Declared declared = declare(variable, declarator);
						hoisted.add(list(declared.local().symbol(), defaultValue(declared.local().type())));
						if (declarator.initializer() != null) {
							forms.add(located(call("setq", declared.local().symbol(), declared.value()),
									declarator.pos()));
						}
					}
					continue;
				}
				Deque<Scope> around = this.scopes;
				int depth = around.size();
				try {
					forms.add(located(localVariable(variable, statements, i + 1, tail), variable.pos()));
				}
				catch (LispReadException | JavaClass.Absent ex) {
					LispVal thrown = deferred(ex, variable.pos());
					if (thrown == null && !counted(ex)) {
						throw ex;
					}
					this.scopes = around;
					while (this.scopes.size() > depth) {
						this.scopes.pop();
					}
					if (thrown != null) {
						forms.add(located(thrown, variable.pos()));
					}
				}
				return forms;
			}
			// a question of the library's answered here, and an else-if's after it: only
			// what it takes is lowered
			boolean untaken = false;
			while (statement instanceof If asked) {
				Boolean answer = answered(asked.condition());
				if (answer == null) {
					break;
				}
				Statement taken = answer ? asked.then() : asked.otherwise();
				if (taken == null) {
					untaken = true;
					break;
				}
				if (exits(taken)) {
					// and nothing after a statement that never completes
					forms.addAll(scoped(taken, tail));
					return forms;
				}
				statement = taken;
			}
			if (untaken) {
				continue;
			}
			List<Local> saved = this.patternVariables;
			this.patternVariables = new ArrayList<>();
			this.introduced = Map.of();
			List<LispVal> lowered;
			boolean rest = false;
			if (tail && !last && hoisted == null && statement instanceof If branch && branch.otherwise() == null
					&& exits(branch.then())) {
				Flow flow;
				Deque<Scope> around = this.scopes;
				int depth = around.size();
				try {
					flow = test(branch.condition());
				}
				catch (LispReadException | JavaClass.Absent ex) {
					// a library's condition that throws where it runs: nothing after it
					// runs
					LispVal thrown = deferred(ex, branch.pos());
					if (thrown == null) {
						throw ex;
					}
					while (this.scopes.size() > depth) {
						this.scopes.pop();
					}
					this.patternVariables = saved;
					forms.add(located(thrown, branch.pos()));
					return forms;
				}
				LispVal then = progn(within(flow.whenTrue(), () -> scoped(branch.then(), true)));
				Objects.requireNonNull(this.scopes.peek()).locals.putAll(flow.whenFalse());
				lowered = List.of(located(branch(flow.value().form(), then, progn(sequence(statements, i + 1, true))),
						branch.pos()));
				rest = true;
			}
			else {
				Deque<Scope> around = this.scopes;
				int depth = around.size();
				try {
					lowered = statement(statement, tail && last);
				}
				catch (LispReadException | JavaClass.Absent ex) {
					LispVal thrown = deferred(ex, statement.pos());
					if (thrown == null && !counted(ex)) {
						throw ex;
					}
					this.scopes = around;
					while (this.scopes.size() > depth) {
						this.scopes.pop();
					}
					lowered = thrown == null ? List.of() : List.of(located(thrown, statement.pos()));
				}
			}
			List<Local> variables = this.patternVariables;
			this.patternVariables = saved;
			Map<String, Local> left = this.introduced;
			this.introduced = Map.of();
			if (rest) {
				forms.add(variables.isEmpty() ? lowered.get(0) : declared(variables, lowered));
				return forms;
			}
			if (variables.isEmpty()) {
				forms.addAll(lowered);
				if (this.suspended) {
					if (locked != null && lowered.size() == 1) {
						this.resumable.lockRegions.put(locked, lowered.get(0));
					}
					locking = !last && lowered.size() == 1 && this.resumable.acquired.containsKey(lowered.get(0))
							&& takesAround(statement, statements.get(i + 1)) ? lowered.get(0) : null;
				}
				continue;
			}
			if (left.isEmpty() || last) {
				forms.add(declared(variables, lowered));
				continue;
			}
			// the bindings the statement leaves are in scope to the end of its block
			Objects.requireNonNull(this.scopes.peek()).locals.putAll(left);
			List<LispVal> body = new ArrayList<>(lowered);
			body.addAll(sequence(statements, i + 1, tail, hoisted));
			forms.add(declared(variables, body));
			return forms;
		}
		return forms;
	}

	/** A declared local and the value it starts with. */
	private record Declared(Local local, LispVal value) {
	}

	private Declared declare(LocalVariable variable, Declarator declarator) {
		boolean inferred = variable.type().name().equals("var") && variable.type().dimensions() == 0;
		JavaType type = inferred ? null : type(variable.type());
		LispVal value;
		Object constant = null;
		Expression initializer = declarator.initializer();
		if (initializer == null) {
			if (type == null) {
				throw error(declarator.pos(), "cannot infer type for local variable " + declarator.name());
			}
			value = defaultValue(type);
		}
		else {
			List<Local> saved = this.patternVariables;
			this.patternVariables = new ArrayList<>();
			int since = this.warnings.size();
			Typed initial;
			try {
				initial = expression(initializer, type);
			}
			catch (LispReadException | JavaClass.Absent ex) {
				LispVal thrown = type == null ? null : deferred(ex, initializer.pos());
				if (type == null || (thrown == null && !counted(ex))) {
					throw ex;
				}
				initial = new Typed(thrown != null ? thrown : defaultValue(type), type);
			}
			if (type == null) {
				type = this.types.ground(initial.type());
				if (type == JavaType.Null.NULL || type == Primitive.VOID) {
					throw error(declarator.pos(), "cannot infer type for local variable " + declarator.name());
				}
			}
			value = assign(initial, type, declarator.pos(), initializer, since);
			if (!this.patternVariables.isEmpty()) {
				value = declared(this.patternVariables, List.of(value));
			}
			this.patternVariables = saved;
			constant = variable.isFinal() ? constantValue(initial, type) : null;
		}
		Local local = bind(declarator.name(), type, declarator.pos(), declarator);
		local.isFinal = variable.isFinal() && initializer != null;
		local.constant = constant;
		return new Declared(local, value);
	}

	private LispVal localVariable(LocalVariable variable, List<Statement> statements, int rest, boolean tail) {
		List<Declared> locals = new ArrayList<>();
		for (Declarator declarator : variable.declarators()) {
			locals.add(declare(variable, declarator));
		}
		return boundInTurn(locals, sequence(statements, rest, tail), "let");
	}

	private List<LispVal> scoped(Statement statement, boolean tail) {
		this.scopes.push(new Scope(null));
		try {
			return statement instanceof Block block ? sequence(block.statements(), 0, tail)
					: sequence(List.of(statement), 0, tail);
		}
		finally {
			this.scopes.pop();
		}
	}

	private LispVal expressionStatement(Expression expression) {
		boolean allowed = expression instanceof Assign || expression instanceof Call || expression instanceof New
				|| (expression instanceof Unary unary
						&& (unary.operator().equals("++") || unary.operator().equals("--")));
		if (!allowed) {
			throw error(expression.pos(), "not a statement");
		}
		Expression saved = this.statementExpression;
		this.statementExpression = expression;
		try {
			return located(expression(expression, null, true).form(), expression.pos());
		}
		finally {
			this.statementExpression = saved;
		}
	}

	/**
	 * {@code this(..)} or {@code super(..)} where a constructor's body has it: the call,
	 * and after a {@code super(..)} what every instance starts with. The statements ahead
	 * of it were lowered with the instance out of reach.
	 */
	private List<LispVal> constructorCallStatement(ConstructorCall call) {
		if (call != this.explicitCall) {
			throw error(call.pos(), "explicit constructor invocation may only appear within a constructor body");
		}
		JavaClass owner = this.currentClass;
		List<Expression> arguments = new ArrayList<>();
		if (owner.isEnum) {
			if (call.onSuper()) {
				throw error(call.pos(), "call to super not allowed in enum constructor");
			}
			for (String name : ENUM_PARAMETERS) {
				arguments.add(new JavaSyntax.Name(call.pos(), name));
			}
		}
		arguments.addAll(call.arguments());
		List<LispVal> forms = new ArrayList<>();
		JavaClass target = call.onSuper() ? owner.superclass : owner;
		Expression outer = call.outer();
		if (outer != null) {
			// outer.super(..): the instance around the superclass, an inner class
			if (target == null || !target.hasOuterInstance()) {
				throw error(call.pos(), "illegal qualifier; " + (target == null ? "Object" : target.simpleName())
						+ " is not an inner class");
			}
			forms.add(call("setf", list(slotAccessor(target, outerSlot(target)), THIS),
					outerOf(outer, Objects.requireNonNull(target.outer))));
		}
		if (target == null) {
			if (!call.arguments().isEmpty()) {
				throw error(call.pos(), "constructor Object in class Object cannot be applied to given types");
			}
		}
		else {
			int caret = call.pos() + (call.onSuper() ? "super" : "this").length();
			String text = this.unit.source().text();
			while (caret < text.length() && Character.isWhitespace(text.charAt(caret))) {
				caret++;
			}
			LispVal form = constructorCall(target, arguments, call.pos(), call,
					caret < text.length() && text.charAt(caret) == '(' ? caret : call.pos());
			if (form != NIL) {
				forms.add(form);
			}
		}
		this.early = false;
		if (call.onSuper()) {
			forms.addAll(instanceInitializers(owner));
		}
		return forms;
	}

	private Target loopTarget() {
		Target target = new Target(this.pendingLabel, true, true, false);
		this.pendingLabel = null;
		return target;
	}

	/** What a {@code break} or a {@code continue} leaves. */
	private Target target(@Nullable String label, boolean continues, int pos) {
		String exit = continues ? "continue" : "break";
		for (Target target : this.targets) {
			if (target.isExpression) {
				throw error(pos, "attempt to " + exit + " out of a switch expression");
			}
			boolean found;
			if (label == null) {
				found = continues ? target.isLoop : target.takesBreak;
			}
			else {
				found = label.equals(target.label);
				if (found && continues && !target.isLoop) {
					throw error(pos, "not a loop label: " + label);
				}
			}
			if (found) {
				return target;
			}
		}
		throw error(pos, label != null ? "undefined label: " + label
				: continues ? "continue outside of loop" : "break outside switch or loop");
	}

	private List<LispVal> statement(Statement statement, boolean tail) {
		switch (statement) {
			case ExpressionStatement expression:
				if (expression.expression() instanceof ConstructorCall call) {
					return constructorCallStatement(call);
				}
				return List.of(expressionStatement(expression.expression()));
			case Block block:
				return scoped(block, tail);
			case Empty empty:
				return List.of();
			case JavaSyntax.Assert asserted: {
				// assertions are disabled, as under a JDK started without -ea: neither
				// expression is evaluated (JLS 14.10). A program's own are typed; a
				// library's were by its compiler, and typing them would reach what they
				// name
				if (!this.unit.source().partial()) {
					test(asserted.condition());
					if (asserted.detail() != null && expression(asserted.detail(), null).type() == Primitive.VOID) {
						throw error(asserted.detail().pos(), "'void' type not allowed here");
					}
				}
				return List.of();
			}
			case JavaSyntax.LocalClass local: {
				JavaClass made = this.classes.local(local.declaration(), this.currentClass,
						this.staticContext || this.early, null);
				Objects.requireNonNull(this.scopes.peek()).classes.put(made.simpleName(), made);
				lowerLocal(made);
				return List.of();
			}
			case JavaSyntax.Labeled labeled: {
				Statement body = labeled.body();
				if (body instanceof While || body instanceof DoWhile || body instanceof For || body instanceof ForEach
						|| body instanceof JavaSyntax.Switch) {
					this.pendingLabel = labeled.label();
					return statement(body, false);
				}
				Target target = new Target(labeled.label(), false, false, false);
				this.targets.push(target);
				List<LispVal> forms = statement(body, false);
				this.targets.pop();
				return target.breaks ? List.of(form(cl("block"), prepend(target.breakBlock, forms))) : forms;
			}
			case If branch: {
				Flow flow = test(branch.condition());
				Statement otherwise = branch.otherwise();
				LispVal then = progn(within(flow.whenTrue(), () -> scoped(branch.then(), tail)));
				LispVal form = branch(flow.value().form(), then,
						otherwise == null ? null : progn(within(flow.whenFalse(), () -> scoped(otherwise, tail))));
				// a branch that never completes leaves the other's bindings in scope
				if (exits(branch.then()) && (otherwise == null || !exits(otherwise))) {
					this.introduced = flow.whenFalse();
				}
				else if (otherwise != null && exits(otherwise) && !exits(branch.then())) {
					this.introduced = flow.whenTrue();
				}
				else {
					this.introduced = Map.of();
				}
				return List.of(located(form, branch.pos()));
			}
			case While loop: {
				Target target = loopTarget();
				Flow flow = test(loop.condition());
				LispVal form = loop(flow.value().form(),
						() -> within(flow.whenTrue(), () -> scoped(loop.body(), false)), List::of, target);
				this.introduced = target.breaks ? Map.of() : flow.whenFalse();
				return List.of(located(form, loop.pos()));
			}
			case DoWhile loop: {
				// the test runs after the body, where a continue lands too
				Target target = loopTarget();
				LispSymbol again = temporary();
				this.targets.push(target);
				List<LispVal> body = loopBody(scoped(loop.body(), false), target);
				this.targets.pop();
				Flow flow = test(loop.condition());
				body.add(call("setq", again, flow.value().form()));
				LispVal form = JavaForms.let(again, T, List.of(form(cl("while"), prepend(again, body))));
				this.introduced = target.breaks ? Map.of() : flow.whenFalse();
				return List.of(located(target.breaks ? list(cl("block"), target.breakBlock, form) : form, loop.pos()));
			}
			case For loop: {
				Target target = loopTarget();
				this.scopes.push(new Scope(null));
				List<LispVal> before = new ArrayList<>();
				List<Declared> locals = new ArrayList<>();
				for (Statement initializer : loop.initializers()) {
					if (initializer instanceof LocalVariable variable) {
						for (Declarator declarator : variable.declarators()) {
							locals.add(declare(variable, declarator));
						}
					}
					else {
						before.add(expressionStatement(((ExpressionStatement) initializer).expression()));
					}
				}
				Flow flow = loop.condition() == null ? new Flow(constant(true), Map.of(), Map.of())
						: test(loop.condition());
				before.add(loop(flow.value().form(), () -> within(flow.whenTrue(), () -> scoped(loop.body(), false)),
						() -> within(flow.whenTrue(), () -> {
							List<LispVal> forms = new ArrayList<>();
							for (Expression update : loop.updates()) {
								forms.add(expressionStatement(update));
							}
							return forms;
						}), target));
				this.scopes.pop();
				LispVal form = locals.isEmpty() ? progn(before) : boundInTurn(locals, before, "let*");
				return List.of(located(form, loop.pos()));
			}
			case ForEach loop:
				return List.of(located(forEach(loop), loop.pos()));
			case Return exit: {
				for (Target target : this.targets) {
					if (target.isExpression) {
						throw error(exit.pos(), "attempt to return out of a switch expression");
					}
				}
				if (Objects.requireNonNull(this.frames.peek()).initializer) {
					throw error(exit.pos(), "return outside method");
				}
				LispVal value = NIL;
				if (exit.value() != null) {
					if (this.returnType == Primitive.VOID) {
						throw error(exit.pos(),
								this.lambdaBody ? "incompatible types: bad return type in lambda expression"
										: "incompatible types: unexpected return value");
					}
					if (this.returns != null) {
						// a lambda whose value's type the call it is an argument of finds
						Typed returned = expression(exit.value(), null);
						if (returned.type() == Primitive.VOID) {
							throw error(exit.value().pos(), "incompatible types: unexpected return value");
						}
						returned = boxedValue(returned);
						this.returns.add(returned.type());
						value = returned.form();
					}
					else {
						int since = this.warnings.size();
						Typed given = expression(exit.value(), this.returnType);
						value = returned(given, this.returnType, exit.value().pos());
						if (!this.lambdaBody && uncheckedConversion(given.type(), this.returnType)) {
							unchecked(caret(exit.value()), since, "unchecked conversion",
									List.of("required: " + this.returnType, "found:    " + given.type()),
									List.of(this.returnType, given.type()));
						}
					}
				}
				else if (this.returnType != Primitive.VOID) {
					throw error(exit.pos(), this.lambdaBody ? "incompatible types: bad return type in lambda expression"
							: "incompatible types: missing return value");
				}
				if (tail) {
					// located as the statement it is: what its value raises is reported
					// at this line
					return exit.value() == null ? List.of() : List.of(located(value, exit.pos()));
				}
				Objects.requireNonNull(this.frames.peek()).exits = true;
				return List.of(located(list(cl("return-from"), FUNCTION_BLOCK, value), exit.pos()));
			}
			case Break exit: {
				Target target = target(exit.label(), false, exit.pos());
				target.breaks = true;
				return List.of(list(cl("return-from"), target.breakBlock, NIL));
			}
			case Continue exit: {
				Target target = target(exit.label(), true, exit.pos());
				target.continues = true;
				return List.of(list(cl("return-from"), target.continueBlock, NIL));
			}
			case JavaSyntax.Yield exit: {
				Target target = null;
				for (Target candidate : this.targets) {
					if (candidate.isExpression) {
						target = candidate;
						break;
					}
				}
				if (target == null) {
					throw error(exit.pos(), "yield outside of switch expression");
				}
				LispVal value = yielded(expression(exit.value(), this.yieldType), exit.value().pos());
				if (tail) {
					return List.of(value);
				}
				target.breaks = true;
				return List.of(located(list(cl("return-from"), YIELD_BLOCK, value), exit.pos()));
			}
			case Throw exit: {
				Typed thrown = expression(exit.value(), null);
				if (!isSubtype(thrown.type(), this.classes.libraryType("java.lang.Throwable"))) {
					throw error(exit.pos(),
							"incompatible types: " + thrown.type() + " cannot be converted to Throwable");
				}
				if (JavaTypes.isChecked(thrown.type())) {
					this.facts.thrown.put(exit, thrown.type());
				}
				else {
					this.facts.thrown.remove(exit);
				}
				// a catch parameter thrown again, read through a class around it too
				if (exit.value() instanceof Name name
						&& variable(name.identifier(), name.pos(), true) instanceof LocalRef(Local local)
						&& local.key instanceof Catch clause) {
					this.facts.rethrown.put(exit, clause);
				}
				else {
					this.facts.rethrown.remove(exit);
				}
				this.effects.visible = true;
				return List.of(located(raise(thrown.form()), exit.pos()));
			}
			case Try attempt:
				return List.of(located(attempt(attempt), attempt.pos()));
			case JavaSyntax.Synchronized held: {
				LispVal lock;
				JavaResumable.Lock known = null;
				if (held.type() != null) {
					// a class's monitor: what its static synchronized methods hold
					if (!(type(held.type()) instanceof ClassType named)) {
						throw unsupported(held.type().pos(), "a class literal");
					}
					lock = quote(JavaNames.structure(named.declaration()));
					known = new JavaResumable.Lock(named.declaration(), false, true);
				}
				else {
					Expression written = Objects.requireNonNull(held.lock());
					Typed value = expression(written, null);
					if (value.type() instanceof Primitive) {
						throw error(written.pos(), "unexpected type: a reference is required, found " + value.type());
					}
					lock = value.form();
					known = lockOf(written, value.type());
				}
				return List.of(located(holding(lock, known, scoped(held.body(), false)), held.pos()));
			}
			case Close close:
				return close(close);
			case JavaSyntax.Switch block:
				return switchStatement(block, tail);
			case LocalVariable variable:
				throw error(variable.pos(), "a variable declaration is not allowed here");
		}
	}

	/** {@code throw}: the object, and the function the report of an uncaught one asks. */
	private LispVal raise(LispVal thrown) {
		JavaClass throwable = this.classes.library("java.lang.Throwable");
		Method describe = methods(throwable, "toString").get(0);
		LispSymbol target = Objects.requireNonNull(target(throwable, describe));
		this.raises = true;
		this.effects.thrown = true;
		return runtimeCall("throw", thrown, function(target));
	}

	private List<LispVal> loopBody(List<LispVal> body, Target target) {
		List<LispVal> forms = new ArrayList<>();
		if (target.continues) {
			forms.add(form(cl("block"), prepend(target.continueBlock, body)));
		}
		else {
			forms.addAll(body);
		}
		return forms;
	}

	/** {@code (while test body.. after..)}, inside the blocks its exits need. */
	private LispVal loop(LispVal test, Supplier<List<LispVal>> body, Supplier<List<LispVal>> after, Target target) {
		this.targets.push(target);
		List<LispVal> forms = loopBody(body.get(), target);
		this.targets.pop();
		forms.addAll(after.get());
		LispVal form = form(cl("while"), prepend(test, forms));
		return target.breaks ? list(cl("block"), target.breakBlock, form) : form;
	}

	private LispVal forEach(ForEach loop) {
		Target target = loopTarget();
		Typed iterable = expression(loop.iterable(), null);
		JavaType element;
		ClassType through = null;
		if (iterable.type() instanceof ArrayType type) {
			element = type.element();
		}
		else {
			ClassType site = iterable.type() instanceof ClassType || iterable.type() instanceof Variable
					? this.types.site(iterable.type()) : null;
			ClassType iterated = site == null ? null
					: this.types.asSuper(site, this.classes.library("java.lang.Iterable"));
			if (iterated == null) {
				throw error(loop.iterable().pos(), "for-each not applicable to expression type");
			}
			through = site;
			element = iterated.arguments().isEmpty() ? this.objectType : this.types.upward(iterated.arguments().get(0));
		}
		LispSymbol items = temporary();
		LispSymbol index = temporary();
		LispSymbol count = temporary();
		JavaType type = loop.type().name().equals("var") && loop.type().dimensions() == 0 ? element : type(loop.type());
		this.scopes.push(new Scope(null));
		Local bound = bind(loop.name(), type, loop.pos(), loop);
		LispSymbol variable = bound.symbol();
		// an array is read by a counter; anything else is asked for its iterator
		LispVal next = through == null ? elementRead(element, call("aref", items, index))
				: virtual(this.classes.library("java.util.Iterator"),
						iteratorMethod(this.classes.library("java.util.Iterator"), "next"), items, List.of());
		LispVal value = assign(new Typed(next, element), type, loop.pos());
		this.targets.push(target);
		List<LispVal> body = scoped(loop.body(), false);
		this.targets.pop();
		this.scopes.pop();
		List<LispVal> round = loopBody(List.of(JavaForms.let(variable, value, declaring(List.of(bound), body))),
				target);
		List<LispVal> bindings = new ArrayList<>();
		LispVal test;
		if (through == null) {
			round.add(call("setq", index, call("+", index, integer(1))));
			bindings.add(list(items, JavaForms.vector(iterable.type(), dereferenced(iterable.form()))));
			bindings.add(list(count, call("length", items)));
			bindings.add(list(index, integer(0)));
			test = call("<", index, count);
		}
		else {
			bindings.add(list(items, virtual(through.declaration(), iteratorMethod(through.declaration(), "iterator"),
					iterable.form(), iterable.form(), List.of())));
			test = virtual(this.classes.library("java.util.Iterator"),
					iteratorMethod(this.classes.library("java.util.Iterator"), "hasNext"), items, List.of());
		}
		if (through != null) {
			this.resumable.asks.put(test, items);
			this.resumable.takes.put(next, items);
		}
		LispVal walk = form(cl("while"), prepend(test, round));
		LispVal form = list(cl("let*"), list(bindings), walk);
		return target.breaks ? list(cl("block"), target.breakBlock, form) : form;
	}

	/**
	 * A call asking a local {@code Iterator} or {@code Enumeration} for more, or for its
	 * next element, told to {@link JavaResumable}: a loop whose test asks a local for
	 * more and whose body takes the next element of it walks it, as an enhanced
	 * {@code for} does, and polls nothing through that test.
	 */
	private Typed walking(Call call, Typed lowered) {
		if (!this.suspended || !call.arguments().isEmpty() || !(call.target() instanceof Name name)) {
			return lowered;
		}
		boolean asks = call.name().equals("hasNext") || call.name().equals("hasMoreElements");
		if (!asks && !call.name().equals("next") && !call.name().equals("nextElement")) {
			return lowered;
		}
		if (variable(name.identifier(), name.pos(), true) instanceof LocalRef(Local local)
				&& local.type instanceof ClassType type && (under(type.declaration(), "java.util.Iterator")
						|| under(type.declaration(), "java.util.Enumeration"))) {
			(asks ? this.resumable.asks : this.resumable.takes).put(lowered.form(), local.key);
		}
		return lowered;
	}

	/**
	 * Whether a class is the class of a name or under it, asked of the classes above it
	 * alone: the question reads no class into the table.
	 */
	private static boolean under(JavaClass declaration, String name) {
		if (declaration.name.equals(name)) {
			return true;
		}
		if (declaration.superclass != null && under(declaration.superclass, name)) {
			return true;
		}
		for (JavaClass implemented : declaration.interfaces) {
			if (under(implemented, name)) {
				return true;
			}
		}
		return false;
	}

	/** A method of no argument the enhanced {@code for} calls: of a class, by name. */
	private Method iteratorMethod(JavaClass declaration, String name) {
		for (Method method : methods(declaration, name)) {
			if (method.parameters.isEmpty() && !method.isStatic) {
				return method;
			}
		}
		throw new IllegalStateException(declaration + " has no " + name + "()");
	}

	/**
	 * {@code try}: each catch clause is a {@code handler-case} clause whose type is
	 * {@code (satisfies test)}, the test ({@link #catchTest}) asking whether the thrown
	 * object is of the clause's classes; {@code finally} is an {@code unwind-protect}. A
	 * condition no clause takes goes on as it came -- nothing signals it again, which
	 * would be a signal of a computed datum (the pipeline then keeps every condition
	 * class) and would lose where it was raised. A condition that is no thrown object --
	 * one the run time or the host raised -- is made the program's exception by
	 * {@code %lisp-error} when a clause can take that exception.
	 */
	private LispVal attempt(Try attempt) {
		LispVal body = progn(scoped(attempt.body(), false));
		if (!attempt.catches().isEmpty()) {
			LispSymbol condition = temporary();
			List<LispVal> clauses = new ArrayList<>();
			for (Catch clause : attempt.catches()) {
				List<JavaClass> taken = new ArrayList<>();
				JavaType type = null;
				boolean takesLispErrors = false;
				for (TypeName written : clause.types()) {
					if (this.unit.source().dependency() && type(written) instanceof ClassType named
							&& named.declaration().absent != null) {
						// a class nobody has, which a library's clause names: nothing
						// thrown is one
						continue;
					}
					JavaClass one = caught(written);
					for (JavaClass other : taken) {
						if (one.isSubclassOf(other) || other.isSubclassOf(one)) {
							throw error(written.pos(),
									"Alternatives in a multi-catch statement cannot be related by subclassing");
						}
					}
					taken.add(one);
					type = type == null ? one.self() : this.types.lub(type, one.self());
					takesLispErrors |= takesRaised(one);
				}
				if (taken.isEmpty()) {
					continue;
				}
				this.facts.caught.put(clause, List.copyOf(taken));
				this.facts.tries.put(clause, attempt);
				this.scopes.push(new Scope(null));
				Local bound = bind(clause.name(), Objects.requireNonNull(type), clause.pos(), clause);
				List<LispVal> handler = sequence(clause.body().statements(), 0, false);
				this.scopes.pop();
				clauses.add(list(list(cl("satisfies"), catchTest(taken, takesLispErrors)), list(condition),
						JavaForms.let(bound.symbol(), thrownObject(condition, takesLispErrors), handler)));
			}
			if (!clauses.isEmpty()) {
				body = form(cl("handler-case"), prepend(body, clauses));
			}
		}
		if (attempt.finallyBlock() != null) {
			List<LispVal> cleanup = scoped(attempt.finallyBlock(), false);
			body = form(cl("unwind-protect"), prepend(body, cleanup));
		}
		return body;
	}

	/**
	 * The object a condition stands for: the one it holds, else -- where the clause can
	 * take an exception of the run time's -- the one {@code %lisp-error} makes of it.
	 */
	private LispVal thrownObject(LispSymbol condition, boolean takesLispErrors) {
		LispVal object = runtimeCall("thrown", condition);
		if (takesLispErrors) {
			this.generated.add(LISP_ERROR);
			object = call("or", object, list(LISP_ERROR, condition));
		}
		return object;
	}

	/**
	 * The function a catch clause's {@code satisfies} type names: whether a condition's
	 * object is of one of the clause's classes. One per set of classes, made once.
	 */
	private LispSymbol catchTest(List<JavaClass> taken, boolean takesLispErrors) {
		StringBuilder key = new StringBuilder(takesLispErrors ? "+" : "-");
		for (JavaClass one : taken) {
			key.append(' ').append(JavaNames.predicate(one).name());
		}
		LispSymbol name = this.catchTestNames.computeIfAbsent(key.toString(),
				k -> new LispSymbol("%catch" + this.catchTestNames.size()));
		if (!this.catchTests.containsKey(name.name())) {
			LispSymbol condition = new LispSymbol("%c");
			LispVal object = thrownObject(condition, takesLispErrors);
			LispVal test;
			if (taken.size() == 1) {
				test = list(JavaNames.predicate(taken.get(0)), object);
			}
			else {
				List<LispVal> tests = new ArrayList<>();
				for (JavaClass one : taken) {
					tests.add(list(JavaNames.predicate(one), THROWN));
				}
				test = JavaForms.let(THROWN, object, List.of(form(cl("or"), tests)));
			}
			this.catchTests.put(name.name(), list(cl("defun"), name, list(condition), test));
		}
		return name;
	}

	/**
	 * The forms run holding an object's monitor: entered ahead of them -- a {@code null}
	 * is the language's exception there -- and left however they end. In a program that
	 * cannot tell a monitor held from one that is not ({@link JavaClasses#monitors}) only
	 * the exception is left.
	 */
	private LispVal holding(LispVal lock, JavaResumable.@Nullable Lock known, List<LispVal> body) {
		if (!this.classes.monitors) {
			// no thread is started and nothing waits or asks: what is left of a monitor
			// is the exception a null has
			boolean ofClass = lock instanceof LispCons quoted && quoted.car().equals(cl("quote"));
			LispVal tested = ofClass ? lock : dereferenced(lock);
			return tested == lock && (ofClass || isSimple(lock)) ? progn(body) : progn(prepend(tested, body));
		}
		LispSymbol monitor = temporary();
		LispVal entry = runtimeCall("monitor-enter", lock);
		if (known != null) {
			this.resumable.locks.put(entry, known);
		}
		return JavaForms.let(monitor, entry,
				List.of(list(cl("unwind-protect"), progn(body), runtimeCall("monitor-exit", monitor))));
	}

	/**
	 * What is known of the object a monitor is entered on or a lock taken on: the static
	 * type of the expression; for a final field, named alone or through {@code this},
	 * whose initializer makes the object, as {@code new Object()}, that class and no
	 * class under it, and the field, whose object no other such field holds. {@code null}
	 * for a value of no class type.
	 * @param written the expression, or {@code null} for {@code this}
	 */
	private JavaResumable.@Nullable Lock lockOf(@Nullable Expression written, JavaType type) {
		if (!(type instanceof ClassType locked)) {
			return null;
		}
		Field field = written == null ? null : finalField(written);
		if (field != null && field.declaration != null && field.declaration.initializer() instanceof JavaSyntax.New made
				&& made.body() == null && made.type().arguments().isEmpty()) {
			try {
				if (type(made.type()) instanceof ClassType exact) {
					return new JavaResumable.Lock(exact.declaration(), true, false,
							field.owner.name + "." + field.name);
				}
			}
			catch (LispReadException ex) {
				// a type no lowering reads: the static type is what is known
			}
		}
		return new JavaResumable.Lock(locked.declaration(), false, false);
	}

	/** The final field an expression reads, named alone or through {@code this}. */
	private @Nullable Field finalField(Expression read) {
		Field field = null;
		if (read instanceof Name name && variable(name.identifier(), name.pos(), true) instanceof FieldRef found) {
			field = found.field();
		}
		else if (read instanceof Select select && select.target() instanceof This && !this.staticContext) {
			field = field(this.currentClass, select.name());
		}
		return field != null && field.isFinal ? field : null;
	}

	/** The class a {@code catch} names, which is a {@code Throwable}. */
	private JavaClass caught(TypeName written) {
		JavaClass throwable = this.classes.library("java.lang.Throwable");
		if (!(type(written) instanceof ClassType type) || !type.declaration().isSubclassOf(throwable)) {
			throw error(written.pos(), "incompatible types: " + written.name() + " cannot be converted to Throwable");
		}
		return type.declaration();
	}

	/**
	 * Whether a clause of a class takes some exception of the run time's: then the
	 * classes of those it takes are made by {@code %lisp-error}.
	 */
	private boolean takesRaised(JavaClass clause) {
		if (!isLibrary(clause)) {
			return false;
		}
		boolean takes = false;
		List<String> raised = new ArrayList<>(RAISED);
		if (namesConversion('h') || namesConversion('g') || namesConversion('a') || namesConversion('t')
				|| makesUnder(JavaIntrinsics.FORMATTABLE)) {
			raised.addAll(FORMAT_RAISED);
		}
		for (String name : raised) {
			if (this.classes.library(name).isSubclassOf(clause)) {
				this.caughtRaised.add(name);
				takes = true;
			}
		}
		return takes;
	}

	/**
	 * Closing a resource of a {@code try}-with-resources: when it is no {@code null},
	 * {@code close()}; when the body threw, an exception of {@code close()} is suppressed
	 * by the body's.
	 */
	private List<LispVal> close(Close close) {
		int at = close.pos();
		Typed resource = expression(new Name(at, close.resource()), null);
		ClassType closeable = this.classes.libraryType("java.lang.AutoCloseable");
		if (!(resource.type() instanceof ClassType || resource.type() instanceof Variable)
				|| !isSubtype(resource.type(), closeable)) {
			throw error(at, "incompatible types: try-with-resources not applicable to variable type");
		}
		String suppressed = close.primary() + "%s";
		Call closed = new Call(at, new Name(at, close.resource()), "close", List.of(), false);
		Statement closing = new ExpressionStatement(at, closed);
		TypeName throwable = new TypeName(at, "Throwable", List.of(), false, 0);
		Statement suppressing = new Try(
				at, new Block(at, List.of(
						closing)),
				List.of(new Catch(at, List.of(throwable), suppressed,
						new Block(at, List.of(new ExpressionStatement(at, new Call(at, new Name(at, close.primary()),
								"addSuppressed", List.of(new Name(at, suppressed)), false)))))),
				null);
		Statement whenThrown = new If(at, new Binary(at, "!=", new Name(at, close.primary()), new NullLiteral(at)),
				suppressing, closing);
		List<LispVal> forms = statement(
				new If(at, new Binary(at, "!=", new Name(at, close.resource()), new NullLiteral(at)), whenThrown, null),
				false);
		// what close() throws is thrown where the resource is declared
		JavaFlow.Raise thrown = this.facts.raises.remove(closed);
		raises(close, at, thrown == null ? List.of() : thrown.types(), close.resource().startsWith("%") ? null
				: "exception thrown from implicit call to close() on resource variable '" + close.resource() + "'");
		return forms;
	}

	// ---- switch

	/**
	 * One case of a switch, lowered.
	 *
	 * @param written the case
	 * @param test what holds when the selector is the case's: {@code t} for a default
	 * @param bindings what its patterns bind
	 * @param isDefault whether it is, or includes, {@code default}
	 */
	private record Arm(JavaSyntax.SwitchCase written, LispVal test, Map<String, Local> bindings, boolean isDefault) {
	}

	/**
	 * A switch's selector and cases, lowered.
	 *
	 * @param held the variable holding the selector, or {@code null} when its form is
	 * evaluated as often as wanted
	 * @param value the selector's form
	 * @param arms the cases, in the order written
	 * @param refusesNull the clause a {@code null} selector takes when no case names it,
	 * or {@code null}
	 * @param early whether that clause stands ahead of the cases: a pattern could take a
	 * {@code null}
	 * @param covered whether some case takes every value
	 */
	private record Selection(@Nullable LispSymbol held, LispVal value, List<Arm> arms, @Nullable LispVal refusesNull,
			boolean early, boolean covered) {

		/**
		 * The clauses of the {@code cond} choosing a case, each case's body made by a
		 * function.
		 */
		List<LispVal> clauses(Function<Arm, List<LispVal>> body, @Nullable LispVal otherwise) {
			List<LispVal> clauses = new ArrayList<>();
			clauses.add(cl("cond"));
			if (this.refusesNull != null && this.early) {
				clauses.add(this.refusesNull);
			}
			LispVal last = null;
			for (Arm arm : this.arms) {
				List<LispVal> forms = body.apply(arm);
				LispVal clause = form(arm.test(), forms.isEmpty() ? List.of(NIL) : forms);
				if (arm.isDefault()) {
					last = clause;
				}
				else {
					clauses.add(clause);
				}
			}
			if (this.refusesNull != null && !this.early) {
				clauses.add(this.refusesNull);
			}
			if (last != null) {
				clauses.add(last);
			}
			else if (otherwise != null) {
				clauses.add(list(T, otherwise));
			}
			return clauses;
		}

		LispVal bound(LispVal form) {
			return this.held == null ? form : JavaForms.let(this.held, this.value, List.of(form));
		}

	}

	/** The shape of a pattern, for what a set of them covers. */
	private JavaPatterns.Shape shape(JavaSyntax.Pattern pattern) {
		return switch (pattern) {
			case JavaSyntax.TypePattern written ->
				written.type().name().equals("var") && written.type().dimensions() == 0 ? new JavaPatterns.Any()
						: new JavaPatterns.OfType(type(written.type()));
			case JavaSyntax.RecordPattern written ->
				new JavaPatterns.OfRecord(((ClassType) type(written.type())).declaration(),
						written.components().stream().map(this::shape).toList());
		};
	}

	/**
	 * Lowers a switch's selector and the tests of its cases: an {@code int} or a
	 * {@code char} by {@code eql}, a string by {@code equal}, an enum constant by
	 * {@code eq} to its field, a pattern by its own test.
	 * @param mustCover whether the cases have to take every value: a switch expression, a
	 * statement with a pattern or a {@code null}
	 */
	private Selection selection(JavaSyntax.Switch block, boolean mustCover) {
		Typed selector = expression(block.selector(), null);
		JavaType type = selector.type();
		boolean patterns = block.cases().stream().anyMatch(written -> !written.patterns().isEmpty());
		Typed plain = patterns ? selector : unbox(selector);
		boolean integral = plain.type() == Primitive.INT || plain.type().isBelowInt();
		boolean strings = type.equals(this.stringType);
		JavaClass constants = type instanceof ClassType named && named.declaration().isEnum ? named.declaration()
				: null;
		if (!integral && !type.isReference()) {
			throw unsupported(block.pos(), "a switch over " + type);
		}
		LispVal value = integral ? plain.form() : selector.form();
		LispSymbol held = isSimple(value) ? null : temporary();
		LispVal subject = held != null ? held : value;
		List<Arm> arms = new ArrayList<>();
		Set<Object> seen = new HashSet<>();
		List<List<JavaPatterns.Shape>> rows = new ArrayList<>();
		boolean hasDefault = false;
		boolean hasNull = false;
		for (JavaSyntax.SwitchCase written : block.cases()) {
			List<LispVal> tests = new ArrayList<>();
			Map<String, Local> bindings = new LinkedHashMap<>();
			if (written.hasNull()) {
				if (!type.isReference()) {
					throw error(written.pos(), "incompatible types: <null> cannot be converted to " + type);
				}
				hasNull = true;
				tests.add(call("null", subject));
			}
			for (Expression constant : written.constants()) {
				Object key;
				if (constants != null) {
					Field named = constant instanceof Name name ? field(constants, name.identifier())
							: constant instanceof Select select && classNamed(select.target()) == constants
									? field(constants, select.name()) : null;
					if (named == null || !named.isConstant) {
						throw error(constant.pos(),
								"an enum switch constant case label must be an enumeration constant");
					}
					key = named;
					tests.add(call("eq", subject, JavaNames.global(named)));
				}
				else {
					Typed label = expression(constant, integral ? plain.type() : type);
					key = label.constant();
					if (key == null) {
						throw error(constant.pos(), "constant expression required");
					}
					if (strings && key instanceof String) {
						tests.add(call("equal", subject, label.form()));
					}
					else if (integral && conversion(label, plain.type(), false, true) != null) {
						key = ((Number) number(key)).longValue();
						tests.add(call("eql", subject, integer((Long) key)));
					}
					else {
						throw error(constant.pos(), "constant label of type " + label.type()
								+ " is not compatible with switch selector type " + type);
					}
				}
				if (!seen.add(key)) {
					throw error(constant.pos(), "duplicate case label");
				}
			}
			for (JavaSyntax.Pattern pattern : written.patterns()) {
				List<LispVal> matches = new ArrayList<>();
				List<LispVal> stores = new ArrayList<>();
				pattern(pattern, subject, type, false, matches, stores, bindings);
				tests.add(matched(matches, stores));
				if (written.guard() == null) {
					rows.add(List.of(shape(pattern)));
				}
			}
			LispVal test = tests.isEmpty() ? T : tests.size() == 1 ? tests.get(0) : form(cl("or"), tests);
			Expression guard = written.guard();
			if (guard != null) {
				Flow guarded = within(bindings, () -> test(guard));
				test = call("and", test, guarded.value().form());
				bindings = union(bindings, guarded.whenTrue());
			}
			hasDefault |= written.hasDefault();
			arms.add(new Arm(written, written.hasDefault() ? T : test, bindings, written.hasDefault()));
		}
		boolean covered = hasDefault;
		if (!covered && constants != null) {
			covered = constants.fields.stream().filter(field -> field.isConstant).allMatch(seen::contains);
		}
		if (!covered && !rows.isEmpty()) {
			covered = JavaPatterns.covers(rows, List.of(type), this.types);
		}
		if ((mustCover || patterns || hasNull) && !covered) {
			throw error(block.pos(), "the switch " + (mustCover ? "expression" : "statement")
					+ " does not cover all possible input values");
		}
		if (patterns || hasNull || !(integral || strings || constants != null)) {
			// an enhanced switch takes every value (JavaFlow)
			this.facts.enhanced.add(block);
		}
		LispVal refusesNull = type.isReference() && !hasNull ? list(call("null", subject), runtimeCall("null-switch"))
				: null;
		return new Selection(held, value, arms, refusesNull, patterns, covered);
	}

	/**
	 * The statements of a case written with a colon, the {@code break} ending them left
	 * out.
	 */
	private static List<Statement> group(JavaSyntax.SwitchCase written) {
		List<Statement> body = written.body();
		if (!body.isEmpty() && body.get(body.size() - 1) instanceof Break exit && exit.label() == null) {
			return body.subList(0, body.size() - 1);
		}
		return body;
	}

	/**
	 * Whether no case written with a colon runs into the next: each is empty -- its
	 * labels are the next one's too -- or ends where it cannot go on.
	 */
	private boolean separate(List<Arm> arms) {
		for (int i = 0; i < arms.size() - 1; i++) {
			List<Statement> body = arms.get(i).written().body();
			if (!body.isEmpty() && !exits(body.get(body.size() - 1))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The cases written with a colon as cases of their own: one whose body is empty gives
	 * its labels to the next.
	 */
	private static List<Arm> merged(List<Arm> arms) {
		List<Arm> merged = new ArrayList<>();
		List<LispVal> tests = new ArrayList<>();
		boolean isDefault = false;
		for (int i = 0; i < arms.size(); i++) {
			Arm arm = arms.get(i);
			tests.add(arm.test());
			isDefault |= arm.isDefault();
			if (arm.written().body().isEmpty() && i < arms.size() - 1) {
				continue;
			}
			LispVal test = isDefault ? T : tests.size() == 1 ? tests.get(0) : form(cl("or"), List.copyOf(tests));
			merged.add(new Arm(arm.written(), test, arm.bindings(), isDefault));
			tests.clear();
			isDefault = false;
		}
		return merged;
	}

	private List<LispVal> switchStatement(JavaSyntax.Switch block, boolean tail) {
		Target target = new Target(this.pendingLabel, false, true, false);
		this.pendingLabel = null;
		Selection selection = selection(block, false);
		List<LispVal> hoisted = new ArrayList<>();
		this.targets.push(target);
		this.scopes.push(new Scope(null));
		LispVal form;
		if (block.arrow()) {
			form = list(selection.clauses(arm -> within(arm.bindings(), () -> {
				JavaSyntax.SwitchCase written = arm.written();
				return written.value() != null ? List.of(expressionStatement(written.value()))
						: scoped(written.body().get(0), tail);
			}), null));
		}
		else if (separate(selection.arms())) {
			Selection groups = new Selection(selection.held(), selection.value(), merged(selection.arms()),
					selection.refusesNull(), selection.early(), selection.covered());
			form = list(groups
				.clauses(arm -> within(arm.bindings(), () -> sequence(group(arm.written()), 0, tail, hoisted)), null));
		}
		else {
			form = runOn(selection, hoisted, null);
		}
		this.scopes.pop();
		this.targets.pop();
		if (!hoisted.isEmpty()) {
			form = list(cl("let"), list(hoisted), form);
		}
		if (target.breaks) {
			form = list(cl("block"), target.breakBlock, form);
		}
		return List.of(located(selection.bound(form), block.pos()));
	}

	/**
	 * The cases of a switch written with colons where one runs into the next: they are
	 * numbered, the selector picks the first to run, and each runs when the one picked is
	 * no later than it. A {@code break} or a {@code yield} leaves the block around.
	 * @param otherwise what runs after the last case, or {@code null}
	 */
	private LispVal runOn(Selection selection, List<LispVal> hoisted, @Nullable LispVal otherwise) {
		LispSymbol first = temporary();
		List<LispVal> picks = new ArrayList<>();
		picks.add(cl("cond"));
		if (selection.refusesNull() != null) {
			picks.add(selection.refusesNull());
		}
		int fallback = selection.arms().size();
		List<LispVal> bodies = new ArrayList<>();
		for (int i = 0; i < selection.arms().size(); i++) {
			Arm arm = selection.arms().get(i);
			if (arm.isDefault()) {
				fallback = i;
			}
			else {
				picks.add(list(arm.test(), integer(i)));
			}
			List<LispVal> body = sequence(arm.written().body(), 0, false, hoisted);
			if (!body.isEmpty()) {
				bodies.add(call("if", call("<=", first, integer(i)), progn(body)));
			}
		}
		picks.add(list(T, integer(fallback)));
		if (otherwise != null) {
			bodies.add(otherwise);
		}
		return JavaForms.let(first, list(picks), bodies);
	}

	/** The value of a switch expression's arm, as the expression's type takes it. */
	private LispVal yielded(Typed value, int pos) {
		if (this.yields != null) {
			this.yields.add(value);
			return value.form();
		}
		return assign(value, Objects.requireNonNull(this.yieldType), pos);
	}

	/**
	 * A switch expression: a {@code cond} whose clauses' values are the arms'. Its type
	 * is the one its context gives it; standing alone, the one its arms share, which they
	 * are lowered once to find.
	 */
	private Typed switchExpression(JavaSyntax.Switch block, @Nullable JavaType expected) {
		JavaType type = expected == Primitive.VOID ? null : expected;
		if (type == null) {
			int mark = this.patternVariables.size();
			List<Typed> results = new ArrayList<>();
			switchArms(block, null, results);
			this.patternVariables.subList(mark, this.patternVariables.size()).clear();
			Typed shared = null;
			for (Typed result : results) {
				shared = shared == null ? result : new Typed(NIL, conditionalType(shared, result, null, block.pos()));
			}
			type = shared == null || shared.type() == JavaType.Null.NULL ? this.objectType : shared.type();
		}
		return new Typed(located(switchArms(block, type, null), block.pos()), type);
	}

	private LispVal switchArms(JavaSyntax.Switch block, @Nullable JavaType type, @Nullable List<Typed> results) {
		Target target = new Target(null, false, false, true);
		Selection selection = selection(block, true);
		boolean runsOn = !block.arrow() && !separate(selection.arms());
		JavaType savedType = this.yieldType;
		List<Typed> savedYields = this.yields;
		this.yieldType = type;
		this.yields = results;
		List<LispVal> hoisted = new ArrayList<>();
		this.targets.push(target);
		this.scopes.push(new Scope(null));
		Selection arms = block.arrow() ? selection : new Selection(selection.held(), selection.value(),
				merged(selection.arms()), selection.refusesNull(), selection.early(), selection.covered());
		LispVal form = runsOn ? runOn(selection, hoisted, runtimeCall("no-match"))
				: list(arms.clauses(arm -> within(arm.bindings(), () -> {
					JavaSyntax.SwitchCase written = arm.written();
					if (written.value() != null) {
						Expression value = written.value();
						return List.of(yielded(expression(value, this.yieldType), value.pos()));
					}
					if (!block.arrow()) {
						return sequence(written.body(), 0, true, hoisted);
					}
					return scoped(written.body().get(0), true);
				}), runtimeCall("no-match")));
		this.scopes.pop();
		this.targets.pop();
		this.yieldType = savedType;
		this.yields = savedYields;
		if (!hoisted.isEmpty()) {
			form = list(cl("let"), list(hoisted), form);
		}
		if (target.breaks) {
			form = list(cl("block"), YIELD_BLOCK, form);
		}
		return selection.bound(form);
	}

	// ---- the order of static initialization

	/**
	 * Whether nothing can change what a value of a type holds: a primitive, a string, a
	 * box, and a class of the program every field of which -- its own, the ones above and
	 * the ones of every class under it -- is final and of such a type.
	 */
	private boolean immutable(JavaType type, Set<JavaClass> seen) {
		if (type.isPrimitive()) {
			return true;
		}
		if (!(type instanceof ClassType named)) {
			return false;
		}
		JavaClass declaration = named.declaration();
		if (declaration.intrinsic) {
			return type.equals(this.stringType) || JavaIntrinsics.unboxed(type) != null;
		}
		if (!seen.add(declaration)) {
			return true;
		}
		if (this.classes.lambdaTargets.stream().anyMatch(target -> target.isSubclassOf(declaration))) {
			return false;
		}
		Set<JavaClass> holders = new LinkedHashSet<>();
		for (JavaClass above = declaration; above != null; above = above.superclass) {
			holders.add(above);
		}
		for (JavaClass candidate : this.classes.all()) {
			if (!candidate.intrinsic && candidate.isSubclassOf(declaration)) {
				for (JavaClass above = candidate; above != null; above = above.superclass) {
					holders.add(above);
				}
			}
		}
		for (JavaClass holder : holders) {
			if (!holder.captures.isEmpty() || holder.hasOuterInstance()) {
				return false;
			}
			for (Field field : holder.fields) {
				if (!field.isStatic && (!field.isFinal || !immutable(field.type, seen))) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * When each class's static initialization runs ({@link JavaInitOrder}): the constant
	 * variables and the defaults first; then, in the order they need each other, the
	 * classes whose initialization nothing can tell from one made at first use; a class
	 * whose initialization shows is initialized at its first use, by a function its
	 * static methods, the functions making its instances and the reads of its fields from
	 * other classes call first.
	 */
	/**
	 * What a session shows of a top-level expression or variable: its value stored in the
	 * variable it is shown under, when it has one, then {@code label ==> value}, a string
	 * written as a literal, a {@code char} too, every other value as string conversion
	 * writes it. A {@code void} expression shows nothing.
	 */
	private Typed echo(JavaSyntax.Echo echo) {
		Session buffer = Objects.requireNonNull(this.session);
		Field field = echo.field() == null ? null : field(this.declared.get(0), Objects.requireNonNull(echo.field()));
		Expression written = echo.value();
		Typed value;
		if (written == null) {
			Field declared = Objects.requireNonNull(field);
			if (declared.inferred) {
				throw error(echo.pos(), "cannot infer type for variable " + echo.label());
			}
			value = new Typed(defaultValue(declared.type), declared.type);
		}
		else {
			value = expression(written, field == null || field.inferred ? null : field.type);
		}
		if (value.type() == Primitive.VOID) {
			if (field != null && !field.inferred) {
				throw error(echo.pos(), "incompatible types: void cannot be converted to " + field.type);
			}
			if (field != null) {
				buffer.inferred.remove(field.name);
			}
			return new Typed(call("progn", value.form(), NIL), this.stringType);
		}
		List<LispVal> body = new ArrayList<>();
		Typed shown;
		if (field != null) {
			if (field.inferred) {
				JavaType type = this.types.ground(value.type());
				if (type == JavaType.Null.NULL) {
					if (!field.name.startsWith("$")) {
						throw error(echo.pos(), "cannot infer type for variable " + echo.label());
					}
					type = this.objectType;
				}
				if (!type.equals(field.type)) {
					field.type = type;
					this.classes.changed();
				}
				buffer.inferred.put(field.name, type);
			}
			LispSymbol global = JavaNames.global(field);
			body.add(call("setq", global, assign(value, field.type, echo.pos())));
			shown = new Typed(global, field.type);
		}
		else {
			LispSymbol held = temporary();
			shown = new Typed(held, value.type());
			LispVal text = call("%string-concat", string(echo.label() + " ==> "), shown(shown, echo.pos()));
			return new Typed(JavaForms.let(held, value.form(), List.of(text)), this.stringType);
		}
		body.add(call("%string-concat", string(echo.label() + " ==> "), shown(shown, echo.pos())));
		return new Typed(progn(body), this.stringType);
	}

	/**
	 * A value as a session shows it: a string or a {@code char} as its literal, an array
	 * as its type, its length and each element shown so.
	 */
	private LispVal shown(Typed value, int pos) {
		if (value.type() instanceof ArrayType array) {
			LispSymbol element = temporary();
			JavaType leaf = array.element();
			StringBuilder below = new StringBuilder();
			while (leaf instanceof ArrayType inner) {
				leaf = inner.element();
				below.append("[]");
			}
			String name = leaf instanceof ClassType type ? type.declaration().simpleName() : leaf.toString();
			LispVal show = list(cl("lambda"), list(element),
					shown(new Typed(elementRead(array.element(), element), array.element()), pos));
			return runtimeCall("echo-array", value.form(), string(name), string(below.toString()), show);
		}
		if (value.type() == Primitive.CHAR) {
			return runtimeCall("echo-char", value.form());
		}
		if (value.type() instanceof ClassType type && type.declaration() == this.stringType.declaration()) {
			return runtimeCall("echo-string", value.form());
		}
		return stringOf(value, pos);
	}

	/** Whether a type names one of some classes, or a class declared inside one. */
	private static boolean names(JavaType type, Set<String> classes) {
		return switch (type) {
			case ArrayType array -> names(array.element(), classes);
			case ClassType named -> {
				for (JavaClass around = named.declaration(); around != null; around = around.outer) {
					if (classes.contains(around.name)) {
						yield true;
					}
				}
				yield named.arguments().stream().anyMatch(argument -> names(argument, classes));
			}
			default -> false;
		};
	}

	/**
	 * Whether a class's static state is set up by what is lowered: always for a file's
	 * program; in a session, for a class the buffer declares or no earlier buffer set up,
	 * never for the session's own class, whose variables are set by their steps.
	 */
	private boolean isFresh(JavaClass declaration) {
		Session buffer = this.session;
		if (buffer == null) {
			return true;
		}
		if (declaration == this.declared.get(0)) {
			return false;
		}
		buffer.initializing.add(declaration.name);
		for (JavaClass around = declaration; around != null; around = around.outer) {
			if (buffer.declared.contains(around.name)) {
				return true;
			}
		}
		return !buffer.initialized.contains(declaration.name);
	}

	/**
	 * {@return the static initializers of the classes initialized before {@code main}}
	 * What cannot throw is set ahead of them by top-level forms of its own
	 * ({@link #settings}), and the functions initializing the lazy classes are made.
	 */
	private List<LispVal> initialization() {
		if (this.order.decide(this.initEffects, this.methodEffects, type -> immutable(type, new HashSet<>()))) {
			this.classes.changed();
		}
		boolean threads = scheduled();
		// the settings that cannot throw are top-level forms ahead of the
		// initialization; what can throw is reported as what main leaves uncaught
		List<LispVal> settings = new ArrayList<>(this.classInits);
		for (JavaClass declaration : this.classes.all()) {
			if (declaration.intrinsic || (isPartial(declaration) && !this.classes.used.contains(declaration))) {
				continue;
			}
			for (Field field : declaration.fields) {
				// a constant string read by reference is one of the program's globals
				if (field.isStatic && constantOf(field) instanceof String text) {
					constantString(text);
				}
			}
		}
		Session buffer = this.session;
		if (buffer != null) {
			// a variable of a class the buffer declares again held an instance of the
			// class
			// replaced: it holds null
			for (Field field : this.declared.get(0).fields) {
				if (names(field.type, buffer.declared)) {
					settings.add(call("setq", JavaNames.global(field), defaultValue(field.type)));
				}
			}
		}
		// the constant strings of a program comparing strings by reference are
		// set ahead of the initialization, each a top-level form of its own: none
		// can throw, and one form holding them all can be more than a method of a
		// JVM class holds
		for (Map.Entry<String, LispSymbol> constant : this.interned.entrySet()) {
			this.variables.add(list(cl("defvar"), constant.getValue(), NIL));
			this.internings.add(call("setq", constant.getValue(), runtimeCall("intern", string(constant.getKey()))));
		}
		for (JavaClass declaration : this.classes.all()) {
			if (declaration.intrinsic || (isPartial(declaration) && !this.classes.used.contains(declaration))) {
				continue;
			}
			boolean fresh = isFresh(declaration);
			Effects effects = this.initEffects.get(declaration);
			// what the methods the initialization calls read: read ahead of an
			// initializer
			Effects called = new Effects();
			if (effects != null) {
				called.calls.addAll(effects.calls);
				called = JavaInitOrder.reached(called, this.methodEffects);
				called.reads.addAll(effects.forward);
			}
			for (Field field : declaration.fields) {
				if (!field.isStatic) {
					continue;
				}
				Object constant = constantOf(field);
				if (constant != null) {
					settings.add(call("setq", JavaNames.global(field),
							constant instanceof String text ? constantString(text).form() : constant(constant).form()));
				}
				else if (fresh && this.session != null && (field.initializer == null || this.order.isLazy(declaration)
						|| called.reads.contains(field))) {
					// a buffer declaring the class again starts it from its type's
					// default
					settings.add(call("setq", JavaNames.global(field), defaultValue(field.type)));
				}
			}
		}
		List<LispVal> forms = new ArrayList<>();
		for (JavaClass declaration : this.order.eager()) {
			forms.addAll(Objects.requireNonNull(this.staticInits.get(declaration)));
		}
		Set<JavaClass> failing = failingInitializations();
		for (JavaClass declaration : this.staticInits.keySet()) {
			if (!this.order.isLazy(declaration)) {
				continue;
			}
			LispSymbol done = JavaNames.initialized(declaration);
			this.variables.add(list(cl("defvar"), done, NIL));
			if (this.session != null && isFresh(declaration)) {
				settings.add(call("setq", done, NIL));
			}
			List<LispVal> body = new ArrayList<>();
			body.add(call("setq", done, T));
			LispVal above = declaration.superclass == null ? null : initializationOf(declaration.superclass);
			if (above != null) {
				body.add(above);
			}
			body.addAll(Objects.requireNonNull(this.staticInits.get(declaration)));
			if (failing.contains(declaration)) {
				this.functions.add(list(cl("defun"), JavaNames.initializer(declaration), NIL,
						failingInitialization(declaration, done, body.subList(1, body.size()), threads)));
				continue;
			}
			LispVal run = call("if", done, NIL, progn(body));
			if (threads) {
				// one thread initializes a class and every other asking for that class
				// waits until it has: the class's state is nil, the thread initializing
				// it, then :ended, written under a lock no initializer runs under. The
				// first test is of an initialization that ended, the second of the
				// thread initializing the class asking for it again
				LispVal ended = new LispSymbol(":ENDED");
				LispSymbol self = temporary();
				body.set(0, call("setq", done, self));
				body.add(1, runtimeCall("init-leave", NIL));
				LispVal end = progn(
						List.of(runtimeCall("init-enter"), call("setq", done, ended), runtimeCall("init-leave", T)));
				LispVal claim = progn(List.of(body.get(0), body.get(1),
						list(cl("unwind-protect"), progn(body.subList(2, body.size())), end)));
				run = call("if", call("eq", done, ended), NIL,
						JavaForms.let(self, runtimeCall("self"),
								List.of(call("if", call("eq", done, self), NIL,
										progn(List.of(runtimeCall("init-enter"),
												form(cl("while"), List.of(runtimeCall("init-busy", done))),
												call("if", done, runtimeCall("init-leave", NIL), claim)))))));
			}
			this.functions.add(list(cl("defun"), JavaNames.initializer(declaration), NIL, run));
		}
		this.settings.addAll(settings);
		return forms;
	}

	private static final String INITIALIZER_ERROR = "java.lang.ExceptionInInitializerError";

	/**
	 * The lazy classes whose static initialization can end with a thrown object: one that
	 * reaches a {@code throw}, one above a class it extends that can, one whose
	 * initialization uses such a class. An exception the run time raises is no object
	 * until a {@code catch} makes one, and passes as it came.
	 */
	private Set<JavaClass> failingInitializations() {
		Map<JavaClass, Effects> reached = new LinkedHashMap<>();
		Set<JavaClass> failing = new LinkedHashSet<>();
		for (JavaClass declaration : this.staticInits.keySet()) {
			Effects effects = this.initEffects.get(declaration);
			if (!this.order.isLazy(declaration) || effects == null) {
				continue;
			}
			Effects all = JavaInitOrder.reached(effects, this.methodEffects);
			reached.put(declaration, all);
			if (all.thrown) {
				failing.add(declaration);
			}
		}
		boolean moved = true;
		while (moved) {
			moved = false;
			for (Map.Entry<JavaClass, Effects> entry : reached.entrySet()) {
				JavaClass declaration = entry.getKey();
				if (!failing.contains(declaration) && initializes(declaration, entry.getValue(), failing)) {
					failing.add(declaration);
					moved = true;
				}
			}
		}
		return failing;
	}

	/**
	 * Whether a class's initialization runs the initialization of one of some classes.
	 */
	private static boolean initializes(JavaClass declaration, Effects reached, Set<JavaClass> some) {
		Set<JavaClass> needed = new LinkedHashSet<>(reached.uses);
		if (declaration.superclass != null) {
			needed.add(declaration.superclass);
		}
		for (JavaClass use : needed) {
			for (JavaClass type = use; type != null; type = type.superclass) {
				if (type != declaration && some.contains(type)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The static initialization of a lazy class that can end with a thrown object, as the
	 * language ends it: an exception that is no {@code Error} is thrown as the cause of
	 * an {@code ExceptionInInitializerError}, and the class keeps what a JDK says of it
	 * -- the text of {@code ExceptionInInitializerError.record} -- in place of its state,
	 * so every later use throws {@code NoClassDefFoundError.unusable}'s. The state is
	 * {@code nil}, {@code t} -- in a program that starts threads the carrier of the
	 * thread initializing the class, then {@code :ended} -- or that text.
	 */
	private LispVal failingInitialization(JavaClass declaration, LispSymbol done, List<LispVal> body, boolean threads) {
		LispSymbol condition = temporary();
		LispSymbol thrown = temporary();
		int known = this.classes.reached.size();
		LispVal thread = threads ? library(THREAD, "currentName", List.of()) : string("main");
		LispVal record = library(INITIALIZER_ERROR, "record", List.of(thrown, thread));
		LispVal wrapped = raise(library(INITIALIZER_ERROR, "of", List.of(thrown)));
		LispVal unusable = raise(
				library("java.lang.NoClassDefFoundError", "unusable", List.of(string(declaration.binaryName), done)));
		if (this.classes.reached.size() != known) {
			// what this reaches of the library is lowered by one more pass
			this.classes.changed();
		}
		// every thrown object is taken: the clause's test is the run time's reading of
		// one
		LispVal clauseType = list(cl("satisfies"), JavaForms.runtime("thrown"));
		if (!threads) {
			LispVal clause = list(clauseType, list(condition), JavaForms.let(thrown, runtimeCall("thrown", condition),
					List.of(call("setq", done, record), wrapped)));
			LispVal run = progn(List.of(call("setq", done, T), list(cl("handler-case"), progn(body), clause)));
			return call("if", call("eq", done, T), NIL, call("if", done, unusable, run));
		}
		// the text is kept until the class's state is written, under the lock
		LispVal ended = new LispSymbol(":ENDED");
		LispSymbol self = temporary();
		LispSymbol failed = temporary();
		LispVal clause = list(clauseType, list(condition), JavaForms.let(thrown, runtimeCall("thrown", condition),
				List.of(call("setq", failed, record), wrapped)));
		LispVal end = progn(List.of(runtimeCall("init-enter"), call("setq", done, call("if", failed, failed, ended)),
				runtimeCall("init-leave", T)));
		LispVal claim = JavaForms.let(failed, NIL, List.of(call("setq", done, self), runtimeCall("init-leave", NIL),
				list(cl("unwind-protect"), list(cl("handler-case"), progn(body), clause), end)));
		LispVal waited = progn(
				List.of(runtimeCall("init-leave", NIL), call("if", call("stringp", done), unusable, NIL)));
		return call("if", call("eq", done, ended), NIL,
				JavaForms.let(self, runtimeCall("self"),
						List.of(call("if", call("eq", done, self), NIL,
								progn(List.of(runtimeCall("init-enter"),
										form(cl("while"), List.of(runtimeCall("init-busy", done))),
										call("if", done, waited, claim)))))));
	}

}
