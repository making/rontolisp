package am.ik.rontolisp.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
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

	private static final LispSymbol SIMPLE_NAME = new LispSymbol("%simple-name");

	private static final LispSymbol LISP_ERROR = new LispSymbol("%lisp-error");

	private static final LispSymbol THROWN = new LispSymbol("%thrown");

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

	/**
	 * The classes whose intrinsic and {@code native} members read or write what is
	 * outside the program: a static initialization calling one is not run ahead.
	 */
	private static final Set<String> VISIBLE = Set.of(JavaIntrinsics.STANDARD_STREAM, "java.lang.IO",
			"java.io.StandardOutput", "java.io.StandardInput");

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

	private final List<LispVal> variables = new ArrayList<>();

	private final List<LispVal> initializers = new ArrayList<>();

	/** The interfaces {@code instanceof} or a cast tests. */
	private final Set<JavaClass> interfaceTests = new LinkedHashSet<>();

	private final Set<LispSymbol> generated = new LinkedHashSet<>();

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

	private final Map<Method, Effects> methodEffects = new HashMap<>();

	/** The static initialization of each class: its forms, in the class's order. */
	private final Map<JavaClass, List<LispVal>> staticInits = new LinkedHashMap<>();

	private final Map<JavaClass, Effects> initEffects = new HashMap<>();

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
	private final Set<JavaClass> loweredLocals = new HashSet<>();

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
		Source program = new Source(file, source, false);
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
		classes.session = session != null;
		// a later buffer may ask the class of an array an earlier one widened
		classes.arrayTypes = session != null;
		List<JavaClass> declared = classes.declare(tree, program);
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
		}
		Map<Source, SourceProvenance.@Nullable Unit> provenance = new IdentityHashMap<>();
		JavaInitOrder order = new JavaInitOrder();
		order.allLazy = session != null;
		while (true) {
			int version = classes.version();
			JavaLowering lowering = new JavaLowering(classes, declared, program, order);
			lowering.provenance = provenance;
			lowering.session = session;
			lowering.loaded = loaded;
			lowering.suspended = suspended;
			lowering.counted = counted;
			List<LispVal> forms = lowering.program();
			if (classes.version() == version) {
				if (session != null) {
					classes.made.forEach(made -> session.made.add(made.name));
					classes.lambdaTargets.forEach(target -> session.lambdas.add(target.name));
					session.monitors = classes.monitors;
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
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			if (!declaration.intrinsic && !declaration.isLocal && !isLibrary(declaration)) {
				implementations(declaration);
				lowerClass(declaration);
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
		boolean runs = this.session == null && !(this.loaded && mainOf(launched()) == null);
		LispVal entry = runs ? entry() : NIL;
		List<LispVal> initialization = initialization();
		List<LispVal> made = new ArrayList<>();
		made.addAll(this.variables);
		made.addAll(this.functions);
		made.addAll(this.dispatchers.values());
		made.addAll(this.catchTests.values());
		made.addAll(generated);
		LispVal started = runs ? uncaught(initialization, entry) : progn(initialization);
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
			if (!isLibrary(declaration) || isLive(declaration) || isNamed(declaration, named)) {
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
		if (this.suspended && startsThreads()) {
			if (!runs) {
				throw unsupported(0, "a program with no main starting threads on a target with one stack");
			}
			this.resumable.dispatchers.addAll(this.dispatchers.keySet());
			for (LispSymbol dispatching : List.of(OBJECT_STRING, OBJECT_EQUALS, OBJECT_HASH)) {
				this.resumable.dispatchers.add(dispatching.name());
			}
			return JavaResumable.rewrite(forms, this.resumable, what -> JavaClasses.unsupported(this.program, 0, what),
					this.counted);
		}
		return List.copyOf(forms);
	}

	// ---- what the program reaches of the library

	/** The library methods and static initializations lowered already in this pass. */
	private final Set<Method> loweredMethods = new HashSet<>();

	private final Set<JavaClass> loweredInits = new HashSet<>();

	/** Whether a class is Java source of the library, and no class of the program. */
	private boolean isLibrary(JavaClass declaration) {
		return !declaration.intrinsic && declaration.source != null && declaration.source.library();
	}

	/**
	 * Whether the program can hold an instance of a class: any class of the program, and
	 * a class of the library the program makes an instance of.
	 */
	private boolean isMade(JavaClass declaration) {
		return !declaration.intrinsic && !declaration.isInterface
				&& (!isLibrary(declaration) || this.classes.made.contains(declaration));
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
		this.effects.calls.add(method);
		reach(method);
	}

	private void reach(Method method) {
		if (method.emitter == null && isLibrary(method.owner)) {
			this.classes.reached.add(method);
			use(method.owner);
			if (!this.classes.monitors && method.owner.name.equals(THREAD)) {
				// what was lowered holding no monitor is lowered again holding one
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
			if (isLibrary(type)) {
				this.classes.used.add(type);
			}
		}
	}

	/**
	 * Records that an instance of a class is made. The first one of a class of the
	 * library changes which bodies a call can run, so the program is lowered again.
	 */
	private void makes(JavaClass declaration) {
		if (isLibrary(declaration) && this.classes.made.add(declaration)) {
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
				if (method.isConstructor()) {
					constructor(method);
				}
				else {
					method(method);
				}
			}
			for (JavaClass declaration : List.copyOf(this.classes.used)) {
				if (declaration.isLocal || !this.loweredInits.add(declaration)) {
					continue;
				}
				more = true;
				this.currentClass = declaration;
				this.unit = this.classes.unit(declaration);
				staticInitialization(declaration);
			}
		}
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
			Field hidden = declaration.superclass == null ? null : field(declaration.superclass, field.name);
			if (hidden != null && !hidden.isStatic) {
				// a structure has one slot of a name
				throw JavaClasses.unsupported(declaration.source, field.pos,
						"a field hiding " + hidden.owner.simpleName() + "." + field.name);
			}
			structure.add(list(JavaNames.slot(field.name), defaultValue(field.type)));
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
		for (JavaClass type : above) {
			for (Method method : type.methods) {
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
		if (!into.add(declaration)) {
			return;
		}
		if (declaration.superclass != null) {
			supertypes(declaration.superclass, into);
		}
		for (JavaClass implemented : declaration.interfaces) {
			supertypes(implemented, into);
		}
	}

	private void lowerClass(JavaClass declaration) {
		this.currentClass = declaration;
		this.unit = this.classes.unit(declaration);
		checkFunctional(declaration);
		if (!isLibrary(declaration)) {
			inheritedThrows(declaration);
		}
		for (Method method : List.copyOf(declaration.methods)) {
			this.currentClass = declaration;
			this.unit = this.classes.unit(declaration);
			JavaSyntax.Method written = method.declaration;
			if (written != null && !isLibrary(declaration)) {
				overriddenThrows(method, written.pos());
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
		if (!isLibrary(declaration)) {
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
			// a session's variable holds its type's default from its declaration on, also
			// when the step giving it its value fails
			this.variables.add(this.session != null && declaration == this.declared.get(0)
					? list(cl("defvar"), JavaNames.global(field), defaultValue(field.type))
					: list(cl("defvar"), JavaNames.global(field)));
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
				Expression initializer = Objects.requireNonNull(field.initializer);
				LispVal value = patterned(
						() -> List.of(assign(expression(initializer, field.type), field.type, field.pos, initializer)))
					.get(0);
				return List.of(call("setq", JavaNames.global(field), value));
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
		List<TypeParameter> savedParameters = this.classes.methodParameters;
		this.classes.methodParameters = List.of();
		this.returns = null;
		this.lambdaBody = false;
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
			this.classes.methodParameters = savedParameters;
		}
	}

	/**
	 * Lowers a local or an anonymous class where it is declared, once a pass: its members
	 * read the locals in scope there, each a slot of the class set where an instance is
	 * made.
	 */
	private void lowerLocal(JavaClass made) {
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

	private List<LispSymbol> bindParameters(Method method) {
		List<LispSymbol> symbols = new ArrayList<>();
		JavaSyntax.Method written = method.declaration;
		for (int i = 0; i < method.parameters.size(); i++) {
			Local local;
			if (written == null || i < method.hidden) {
				// an enum constructor's name and ordinal; what an anonymous class's
				// constructor hands on
				String name = method.owner.isEnum && i < method.hidden ? ENUM_PARAMETERS.get(i) : "%a" + i;
				local = bind(name, method.parameters.get(i), 0, new Object());
			}
			else {
				Parameter parameter = written.parameters().get(i - method.hidden);
				local = bind(parameter.name(), method.parameters.get(i), parameter.pos(), parameter);
				local.isFinal = parameter.isFinal();
			}
			symbols.add(local.symbol());
		}
		return symbols;
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
		if (written.modifiers().contains("@Override") && !isLibrary(method.owner) && !overrides(method)) {
			throw error(written.pos(), "method does not override or implement a method from a supertype");
		}
		this.staticContext = method.isStatic;
		this.returnType = method.returnType;
		this.constructing = null;
		this.classes.methodParameters = method.typeParameters;
		Effects saved = this.effects;
		this.effects = new Effects();
		this.methodEffects.put(method, this.effects);
		this.scopes.push(new Scope(null));
		List<LispVal> parameters = new ArrayList<>();
		if (!method.isStatic) {
			parameters.add(THIS);
		}
		parameters.addAll(bindParameters(method));
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
		this.functions.add(list(cl("defun"), JavaNames.function(method), list(parameters), body));
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
				Expression initializer = Objects.requireNonNull(field.initializer);
				LispVal value = patterned(
						() -> List.of(assign(expression(initializer, field.type), field.type, field.pos, initializer)))
					.get(0);
				return List.of(call("setf", list(JavaNames.accessor(field), THIS), value));
			}));
		}
		return forms;
	}

	/** The {@code this(..)} or {@code super(..)} of the constructor being lowered. */
	private @Nullable ConstructorCall explicitCall;

	private void constructor(Method constructor) {
		JavaClass owner = constructor.owner;
		JavaSyntax.Method written = constructor.declaration;
		int pos = written != null ? written.pos() : Objects.requireNonNull(owner.declaration).pos();
		this.staticContext = false;
		this.returnType = Primitive.VOID;
		this.classes.methodParameters = constructor.typeParameters;
		Effects saved = this.effects;
		this.effects = new Effects();
		this.methodEffects.put(constructor, this.effects);
		this.scopes.push(new Scope(null));
		List<LispSymbol> parameters = bindParameters(constructor);
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
			this.functions.add(list(cl("defun"), JavaNames.function(constructor), list(all), body));
		}
		if ((owner.isAbstract && !owner.isEnum) || (isLibrary(owner) && !this.classes.made.contains(owner))) {
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
							+ ": the program starts at the first class of the file or the class named as the file");
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
			arguments.add(call("vector"));
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
	 * The static initialization before {@code main} and {@code main}, where what nothing
	 * caught is reported by the line a JDK writes, and the status 1: the pipeline's own
	 * report of an uncaught condition with the program's line in it, so no handler holds
	 * the condition. Only a program that throws objects asks the condition for one.
	 */
	private LispVal uncaught(List<LispVal> initialization, LispVal start) {
		List<LispVal> body = new ArrayList<>(initialization);
		body.add(start);
		int known = this.classes.reached.size();
		LispSymbol thrown = new LispSymbol("%o");
		LispVal entry;
		if (startsThreads()) {
			// the program ends when its threads that are no daemons have: the run time
			// reports what main left uncaught, then waits for them
			LispSymbol ended = new LispSymbol("%t");
			entry = runtimeCall("thread-main", list(cl("lambda"), NIL, progn(body)),
					list(cl("lambda"), list(ended), library(THREAD, "finish", List.of(ended))),
					list(cl("lambda"), list(thrown), library(THROWABLE, "trace", List.of(thrown))));
		}
		else {
			LispVal line = function(JavaForms.runtime(this.raises ? "uncaught-thrown-line" : "uncaught-line"));
			if (this.raises && enclosing()) {
				// an exception that can have a cause or suppress another is reported
				// with them
				LispSymbol condition = new LispSymbol("%c");
				LispSymbol text = new LispSymbol("%text");
				this.reporter = list(cl("defun"), UNCAUGHT, list(condition, text),
						runtimeCall("uncaught-thrown-line", condition, JavaForms.let(thrown,
								runtimeCall("thrown", condition),
								List.of(call("if", thrown, library(THROWABLE, "trace", List.of(thrown)), text)))));
				line = function(UNCAUGHT);
			}
			List<LispVal> reporting = new ArrayList<>();
			reporting.add(line);
			reporting.add(call("%host-exit", integer(1)));
			reporting.addAll(body);
			entry = form(cl("%reporting-uncaught"), reporting);
		}
		if (this.classes.reached.size() != known) {
			// what the report reaches of the library is lowered by one more pass
			this.classes.changed();
		}
		return entry;
	}

	private static final String THROWABLE = "java.lang.Throwable";

	private static final LispSymbol UNCAUGHT = new LispSymbol("%uncaught");

	/** The function a program whose exceptions can enclose others is reported by. */
	private @Nullable LispVal reporter;

	/**
	 * Whether an exception of the program can have a cause or a suppressed exception:
	 * some constructor taking a cause, {@code initCause} or {@code addSuppressed} is
	 * reached.
	 */
	private boolean enclosing() {
		for (Method method : this.classes.reached) {
			if (!method.owner.name.equals(THROWABLE)) {
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

	private static final String THREAD = "java.lang.Thread";

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
	 * The class the program starts at: the first class of the file when it has a
	 * {@code main}, else the class named as the file.
	 */
	private JavaClass launched() {
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

	/** The class the program starts at: initialized before anything else of it runs. */
	private @Nullable JavaClass launched;

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
		if (this.generated.contains(LISP_ERROR)) {
			// the exception the table names, made as the program's object; nil for one
			// no catch takes
			LispSymbol condition = new LispSymbol("%c");
			LispSymbol entry = new LispSymbol("%e");
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			for (String name : this.caughtRaised) {
				arms.add(list(call("equal", call("car", entry), string(name)),
						form(creator(name), creatorArguments(name, call("cdr", entry)))));
			}
			forms.add(list(cl("defun"), LISP_ERROR, list(condition),
					JavaForms.let(entry, runtimeCall("exception-of", condition), List.of(list(arms)))));
		}
		return forms;
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
	 * made for it. A lambda is a function, which does not tell the interface it was made
	 * for: so when the program has lambdas of this interface and of another, the ones of
	 * this interface are made structures holding the function, and the program is lowered
	 * again.
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
		for (JavaClass candidate : this.classes.all()) {
			if (candidate.isInterface || !candidate.isSubclassOf(declaration)) {
				continue;
			}
			if (candidate.intrinsic) {
				LispSymbol predicate = JavaIntrinsics.predicate(candidate);
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

	// ---- the class table

	/**
	 * The field of a name a class has: its own, one of a superclass, a constant of an
	 * interface.
	 */
	private static @Nullable Field field(JavaClass declaration, String name) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
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
		bring(declaration, "<init>");
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

	/** The methods of a name a class has, an overridden one left out. */
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
				if (method.name.equals(name) && !method.isConstructor()
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
	 * A method throws no checked exception that a method it overrides or implements does
	 * not (JLS 8.4.8.3).
	 */
	private void overriddenThrows(Method method, int pos) {
		JavaClass owner = method.owner;
		if (method.isStatic || method.isConstructor() || method.isPrivate
				|| method.thrown.stream().noneMatch(JavaTypes::isChecked)) {
			return;
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
					throwsWithin(method, candidate, owner, pos);
				}
			}
		}
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

	/** A class as a compiler names it: a member class behind the classes around it. */
	private static String named(JavaClass declaration) {
		JavaClass around = declaration.outer;
		return around != null && around.members.get(declaration.simple) == declaration
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
			return isSubtype(boxed.type(), to) ? boxed.form() : null;
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
	 */
	private LispVal assign(Typed value, JavaType to, int pos, Expression written) {
		LispVal form = assign(value, to, pos);
		if (uncheckedConversion(value.type(), to)) {
			unchecked(caret(written), "unchecked conversion", List.of("required: " + to, "found:    " + value.type()),
					List.of(to, value.type()));
		}
		return form;
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
	 * argument but {@code ?}.
	 */
	private static boolean reifiable(JavaType type) {
		return switch (type) {
			case Variable variable -> false;
			case ClassType named -> named.arguments()
				.stream()
				.allMatch(argument -> argument instanceof Wildcard wildcard && wildcard.upper() == null
						&& wildcard.lower() == null);
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
	 * methods for: a box, an array, a {@code StringBuilder}.
	 */
	private static boolean isPlain(JavaType type) {
		return JavaIntrinsics.unboxed(type) != null || type instanceof ArrayType || (type instanceof ClassType named
				&& named.declaration().intrinsic && JavaIntrinsics.isPlain(named.declaration()));
	}

	@Override
	public LispVal stringFunction() {
		this.generated.add(OBJECT_STRING);
		return function(OBJECT_STRING);
	}

	@Override
	public LispVal hashFunction() {
		this.generated.add(OBJECT_HASH);
		return function(OBJECT_HASH);
	}

	@Override
	public LispVal arrayLike(LispVal made, LispVal original) {
		return this.classes.arrayTypes ? runtimeCall("array-like", made, original) : made;
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

	/**
	 * A call of one of {@code Object}'s methods no class of the receiver's type declares.
	 */
	@Override
	public LispVal objectMethod(String signature, Typed receiver, Invocation call) {
		boolean plain = isPlain(receiver.type());
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

	/** A simple name read as a value. */
	private Typed read(Name written) {
		String name = written.identifier();
		int pos = written.pos();
		Named variable = variable(name, pos, false);
		return switch (variable) {
			case null -> throw error(pos, "cannot find symbol: variable " + name);
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
		JavaClass found = this.classes.find(name, this.currentClass, this.unit);
		if (found != null) {
			this.classes.accessible(found, this.unit, expression.pos());
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
			case JavaSyntax.ClassLiteral literal:
				throw unsupported(literal.pos(), "a class literal but compared with a class or before getName()");
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
				LispVal instance = enclosing(named);
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
				return invocation(call, expected, discard);
			case ConstructorCall call:
				throw error(call.pos(), "explicit constructor invocation may only appear within a constructor body");
			case New creation:
				return creation(creation, expected);
			case NewArray creation: {
				ArrayType type = (ArrayType) type(creation.type());
				if (creation.initializer() != null) {
					return arrayInitializer(creation.initializer(), type);
				}
				List<LispVal> lengths = new ArrayList<>();
				JavaType leaf = type;
				for (Expression written : creation.lengths()) {
					Typed length = unbox(expression(written, null));
					if (length.type() != Primitive.INT && !length.type().isBelowInt()) {
						throw error(written.pos(),
								"incompatible types: " + length.type() + " cannot be converted to int");
					}
					lengths.add(length.form());
					leaf = ((ArrayType) leaf).element();
				}
				if (lengths.size() > 1) {
					// every level but the last holds arrays; the last, the leaf's default
					return new Typed(runtimeCall("new-array", form(cl("list"), lengths), integer(arrayKind(leaf)),
							defaultValue(leaf)), type);
				}
				// a negative length is the language's exception, which the run time makes
				LispVal length = lengths.get(0) instanceof LispInteger n && n.value() >= 0 ? lengths.get(0)
						: runtimeCall("array-length", lengths.get(0));
				List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), length));
				made.addAll(elementType(type.element()));
				made.add(new LispSymbol(":INITIAL-ELEMENT"));
				made.add(type.element() == Primitive.BOOLEAN ? integer(0) : defaultValue(type.element()));
				LispVal array = list(made);
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
					pending.addAll(type.interfaces);
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

	/** The leaf of {@code %java-new-array}: which element type it makes. */
	private static int arrayKind(JavaType element) {
		return element == Primitive.INT ? 1
				: element == Primitive.DOUBLE ? 2
						: element == Primitive.BYTE ? 3 : element == Primitive.SHORT || element == Primitive.CHAR ? 4
								: element == Primitive.FLOAT ? 5 : element == Primitive.BOOLEAN ? 6 : 0;
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
			elements.add(elementStored(type.element(),
					assign(expression(element, type.element()), type.element(), element.pos(), element)));
		}
		return new Typed(arrayOf(type, elements), type);
	}

	/** An array of an element type holding the values of forms. */
	private static LispVal arrayOf(ArrayType type, List<LispVal> elements) {
		List<LispVal> typed = elementType(type.element());
		if (typed.isEmpty()) {
			return form(cl("vector"), elements);
		}
		List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), integer(elements.size())));
		made.addAll(typed);
		made.add(new LispSymbol(":INITIAL-CONTENTS"));
		made.add(form(cl("list"), elements));
		return list(made);
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
		uses(field.owner);
		if (forward(field)) {
			this.effects.forward.add(field);
		}
		LispVal global = JavaNames.global(field);
		LispVal read = isVolatile(field) ? ordered(global) : global;
		if (lazilyRead(field)) {
			return new Typed(call("progn", list(JavaNames.initializer(field.owner)), read), field.type);
		}
		return new Typed(read, field.type);
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
					return new Place(Primitive.INT, call("length", dereferenced(object.form())), value -> {
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
				LispVal element = call("aref", stabilized(array.form(), stable, bindings),
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
		Typed value = expression(assignment.value(), place.type());
		Typed read = new Typed(place.read(), place.type());
		LispVal stored;
		if (assignment.operator().equals("=")) {
			stored = assign(value, place.stored(), assignment.pos(), assignment.value());
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
				return operator.equals("-")
						? new Typed(JavaForms.wrap(promoted.type(), call("-", promoted.form())), promoted.type())
						: new Typed(call("lognot", promoted.form()), promoted.type());
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
				return folded != null ? constant(folded)
						: new Typed(call(operator, a.form(), b.form()), Primitive.BOOLEAN);
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
		LispVal x = a.form();
		LispVal y = b.form();
		if (type == Primitive.FLOAT) {
			// the binary64 result of two binary32 operands, rounded once more, is the
			// binary32 result: the double holds every bit the rounding reads
			return new Typed(call("%single-float",
					operator.equals("%") ? runtimeCall("double-rem", x, y) : call(operator, x, y)), type);
		}
		LispVal form = switch (operator) {
			case "+", "-", "*" -> JavaForms.wrap(type, call(operator, x, y));
			case "/" -> type == Primitive.DOUBLE ? call("/", x, y) : JavaForms.wrap(type, call("truncate", x, y));
			case "%" -> type == Primitive.DOUBLE ? runtimeCall("double-rem", x, y) : call("rem", x, y);
			case "&" -> call("logand", x, y);
			case "|" -> call("logior", x, y);
			default -> call("logxor", x, y);
		};
		return new Typed(form, type);
	}

	private static Typed shift(String operator, Typed value, Typed count) {
		JavaType type = value.type();
		int mask = type == Primitive.LONG ? 63 : 31;
		Object by = count.constant() == null ? null : number(count.constant());
		if (value.constant() != null && by != null) {
			Object folded = fold(operator, value.constant(), ((Number) by).intValue(), type);
			if (folded != null) {
				return constant(folded);
			}
		}
		LispVal distance = by != null ? integer(((Number) by).longValue() & mask)
				: call("logand", count.form(), integer(mask));
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
			if (!isOpen(left.type()) && !isOpen(right.type()) && !isSubtype(a, b) && !isSubtype(b, a)
					&& !(isInterface(a) || isInterface(b))) {
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
				test = call("=", a.form(), b.form());
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

	private Typed conditional(Conditional conditional, @Nullable JavaType expected) {
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

	/**
	 * The {@code ClassCastException} of a value that is no {@code to}: the message names
	 * the two classes as a JDK's starts, without what it says of their modules and class
	 * loaders; a cast to an array type has none.
	 */
	private LispVal castFailure(LispSymbol value, JavaType to) {
		String target = to instanceof ClassType type ? type.declaration().binaryName
				: to instanceof ArrayType array ? descriptor(array) : null;
		if (target == null) {
			Method failed = constructors(this.classes.library("java.lang.ClassCastException")).stream()
				.filter(constructor -> constructor.parameters.isEmpty())
				.findFirst()
				.orElseThrow();
			called(failed);
			makes(failed.owner);
			return list(JavaNames.creator(failed));
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
		// type is the cast's
		Typed value = expression(operand, needsTarget(operand) ? to : null);
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
		JavaClass declaration = target.declaration();
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
		}
		JavaType wanted = function.returnType();
		boolean inferring = inference != null && JavaTypes.mentions(wanted, inference.variables);
		JavaType savedReturn = this.returnType;
		List<JavaType> savedReturns = this.returns;
		boolean savedLambda = this.lambdaBody;
		List<JavaType> returned = new ArrayList<>();
		this.returnType = inferring ? this.objectType : wanted;
		this.returns = inferring ? returned : null;
		this.lambdaBody = true;
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
			this.targets.addAll(savedTargets);
		}
		else {
			body = functionBody(Objects.requireNonNull(lambda.block()));
		}
		this.returnType = savedReturn;
		this.returns = savedReturns;
		this.lambdaBody = savedLambda;
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
		return new Typed(lambdaValue(target, madeFor(target, list(cl("lambda"), list(parameters), body))),
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
			// List<String>::size: a method of the first parameter, seen as the type
			// written
			JavaType seen = type(Objects.requireNonNull(reference.type()));
			if (arguments.isEmpty() || !(seen instanceof ClassType named) || (!isOpen(function.parameters().get(0))
					&& this.types.asSuper(function.parameters().get(0), named.declaration()) == null)) {
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
			arguments.addAll(member.self().arguments().subList(0, own));
		}
		arguments.addAll(seen.arguments());
		return new ClassType(member, List.copyOf(arguments));
	}

	/**
	 * The type an anonymous class made with {@code <>} extends: its arguments found as a
	 * creation with {@code <>} finds them -- from the constructor's arguments and the
	 * type the instance is wanted as --, each one a program can write.
	 */
	private ClassType anonymousType(ClassType type, New creation, @Nullable JavaType expected, int pos) {
		JavaClass made = type.declaration();
		JavaType found;
		if (made.isInterface) {
			Inference inference = this.types.new Inference(made.typeParameters);
			if (expected != null && expected.isReference()) {
				inference.upperBound(type, expected);
			}
			found = JavaTypes.substitute(type, inference.solution(true));
		}
		else {
			// the arguments are lowered again with the class's constructor
			found = resolve(constructors(made), type, List.of(), creation.arguments(), expected,
					"constructor " + made.simpleName(), made, pos, made.typeParameters, type)
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
			@Nullable Initializing initializing, int warnings) {
	}

	private Standing standing() {
		return new Standing(List.copyOf(this.scopes), List.copyOf(this.targets), List.copyOf(this.frames),
				this.patternVariables, this.returnType, this.returns, this.lambdaBody, this.initializing,
				this.warnings.size());
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
	}

	/**
	 * Whether an argument is a call whose own arguments may need the type it is wanted
	 * as: a lambda or a method reference among them takes the types of its parameters
	 * from what the call infers, which can come from that type alone.
	 */
	private static boolean mayNeedTarget(Expression argument) {
		List<Expression> arguments = switch (argument) {
			case Call call -> call.arguments();
			case New creation when creation.type().diamond() && creation.body() == null -> creation.arguments();
			default -> List.of();
		};
		return arguments.stream().anyMatch(each -> needsTarget(each) || mayNeedTarget(each));
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
				return accepts(parameter, conditional.whenTrue(), contested)
						&& accepts(parameter, conditional.whenFalse(), contested);
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
	 * Whether a parameter is at least as specific as another's for an argument that waits
	 * for its parameter's type: a functional interface giving a value is, against one
	 * giving none or a value it converts to.
	 */
	private boolean moreSpecific(JavaType mine, JavaType theirs) {
		Descriptor a = functional(mine);
		Descriptor b = functional(theirs);
		if (a == null || b == null) {
			return conversion(new Typed(NIL, mine), theirs, false, false) != null;
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
			boolean spread, boolean contested, Late late) {
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
			else if (late.indices().contains(i) && late.contested()) {
				if (!late.lowers(this, i, arguments.get(i), JavaTypes.substitute(formal, inference.solution(true)))) {
					return null;
				}
			}
			else if (!accepts(formal, arguments.get(i), contested)) {
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
	 * The arguments of a call that failed with no target and wait for their parameter's
	 * type.
	 *
	 * @param indices where they are among the arguments
	 * @param contested whether several candidates have the call's arity: then a candidate
	 * takes such an argument only when it lowers against its parameter's type
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
		// what the function takes has to be known before its body is lowered
		for (int i = 0; i < function.parameters().size(); i++) {
			JavaType input = function.parameters().get(i);
			boolean unbound = exact != null && !exact.isStatic && !exact.isConstructor();
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
	 * The one method a reference {@code Type::name} can mean, whose parameters then say
	 * what the function takes: the class has one method of the name, or one constructor,
	 * not generic and of no variable arity; {@code null} for any other reference.
	 */
	private @Nullable Method exact(JavaSyntax.MethodReference reference) {
		Expression written = reference.target();
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
		for (Expression argument : arguments) {
			if (needsTarget(argument)) {
				typed.add(null);
				continue;
			}
			// a call that is an argument leaves open what this call may need above
			this.flexible = argument instanceof Call || (argument instanceof New creation && creation.type().diamond());
			Typed lowered;
			if (mayNeedTarget(argument)) {
				Standing standing = standing();
				try {
					lowered = expression(argument, null);
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
						spread, candidates.size() > 1, lates);
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
			if ((in.intrinsic || isLibrary(in)) && ofArity == 0) {
				// a member the library does not have yet
				throw unsupported(pos,
						(isConstructor ? "new " + in.simpleName() : in.simpleName() + "." + name) + "(" + given + ")");
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
				for (int i = 0; i < arguments.size() && mostSpecific; i++) {
					JavaType mine = parameterAt(candidate, i, site, spreads);
					JavaType against = JavaTypes.substitute(parameterAt(other, i, site, spreads), theirs);
					mostSpecific = other.raw || (typed.get(i) == null && !late.contains(i) ? moreSpecific(mine, against)
							: conversion(new Typed(NIL, mine), against, false, false) != null);
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
		// the value's type against the type it is wanted as
		JavaType returned = made != null ? made : this.types.memberType(chosen.returnType, site, chosen.owner);
		if (expected != null && expected != Primitive.VOID && JavaTypes.mentions(returned, inference.variables)) {
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
		List<Typed> converted = new ArrayList<>();
		boolean uncheckedArguments = false;
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
				uncheckedArguments |= !chosen.raw && uncheckedConversion(argument.type(), parameter);
				converted.add(new Typed(
						chosen.raw ? argument.form()
								: widened(assign(argument, parameter, arguments.get(i).pos(), arguments.get(i)),
										argument.type(), declared),
						this.types.ground(argument.type()), argument.constant()));
			}
		}
		int caret = callCaret(pos, isConstructor ? null : name);
		if (uncheckedArguments) {
			List<JavaType> found = new ArrayList<>();
			for (Typed argument : lowered) {
				found.add(Objects.requireNonNull(argument).type());
			}
			List<JavaType> required = chosen.parameters.subList(chosen.hidden, chosen.parameters.size());
			List<JavaType> mentioned = new ArrayList<>(List.of(chosen.owner.self()));
			mentioned.addAll(required);
			unchecked(caret,
					"unchecked method invocation: " + (isConstructor ? "constructor <init>" : "method " + name) + " in "
							+ kind(chosen.owner) + " " + chosen.owner.self() + " is applied to given types",
					List.of("required: " + String.join(",", required.stream().map(JavaType::toString).toList()),
							"found:    " + String.join(",", found.stream().map(JavaType::toString).toList())),
					mentioned);
		}
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
			converted = new ArrayList<>(converted.subList(0, fixed));
			converted.add(new Typed(arrayOf(rest, elements), rest));
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
				inScope(this.types.upward(JavaTypes.substitute(returned, solution)), pos),
				thrownBy(named, site, solution));
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
	 * Whether an expression is a class: {@code getClass()} of an object or a class
	 * literal. No object stands for a class; a class is read where it is compared with
	 * another and where its name is asked.
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
			if (!(type(literal.type()) instanceof ClassType named)) {
				throw unsupported(literal.pos(), "a class literal of a type that is no class");
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

	private Typed invocation(Call call, @Nullable JavaType expected, boolean discard) {
		int pos = call.pos();
		boolean flexible = this.flexible;
		this.flexible = false;
		if (isClass(call.target())) {
			Expression named = Objects.requireNonNull(call.target());
			if ((call.name().equals("getName") || call.name().equals("getSimpleName")) && call.arguments().isEmpty()) {
				LispVal text = classRead(named, call.name().equals("getName")).name();
				this.nonNull.add(text);
				return new Typed(text, this.stringType);
			}
			if (call.name().equals("equals") && call.arguments().size() == 1 && isClass(call.arguments().get(0))) {
				return new Typed(sameClass(named, call.arguments().get(0), false, pos), Primitive.BOOLEAN);
			}
		}
		if (call.name().equals("getClass") && call.arguments().isEmpty()) {
			throw unsupported(pos, "getClass() but compared with a class or before getName() and getSimpleName()");
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
		int caret = callCaret(pos, call.name());
		raises(call, caret >= 0 ? caret : pos, resolved.thrown(), null);
		uncheckedCall(method, site, caret);
		checkAccess(method.isPrivate, method.declaration, method.owner, method.signature(), pos);
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
			LispVal form = method.emitter.emit(this,
					new Invocation(method.isStatic ? null : receiver, resolved.arguments(), discard, pos));
			if (resolved.returnType().equals(this.stringType) && method.returnType.equals(this.stringType)
					&& !method.answersNull) {
				this.nonNull.add(form);
			}
			return new Typed(located(form, pos), resolved.returnType());
		}
		LispVal form;
		if (method.isStatic) {
			called(method);
			uses(method.owner);
			form = form(JavaNames.function(method), resolved.forms());
		}
		else {
			LispVal object = Objects.requireNonNull(receiver).form();
			form = virtual(in, method, call.target() != null ? object : null, object, resolved.forms());
		}
		return new Typed(located(form, pos), resolved.returnType());
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
			JavaClass superclass = self.superclass;
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
		if (body == null || resolved.method().emitter != null) {
			throw resolved.method().emitter != null
					? unsupported(pos, "a call of one of Object's methods through super")
					: error(pos, "abstract method " + resolved.method().signature() + " cannot be accessed directly");
		}
		checkAccess(body.isPrivate, body.declaration, body.owner, body.signature(), pos);
		called(body);
		LispVal receiver = object != null ? object : thisValue(pos).form();
		return new Typed(located(form(JavaNames.function(body), prepend(receiver, resolved.forms())), pos),
				resolved.returnType());
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
				LispSymbol predicate = candidate.intrinsic && !candidate.isInterface && candidate.isSubclassOf(receiver)
						? JavaIntrinsics.predicate(candidate) : null;
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
				arms.add(list(list(JavaNames.predicate(body.tested()), RECEIVER),
						form(JavaNames.function(body.method()), parameters)));
			}
			for (Map.Entry<JavaClass, Method> intrinsic : intrinsics.entrySet()) {
				Method member = intrinsic.getValue();
				arguments.clear();
				for (int i = 0; i < member.parameters.size(); i++) {
					arguments.add(new Typed(parameters.get(i + 1), member.parameters.get(i)));
				}
				arms.add(list(list(Objects.requireNonNull(JavaIntrinsics.predicate(intrinsic.getKey())), RECEIVER),
						Objects.requireNonNull(member.emitter)
							.emit(this, new Invocation(new Typed(RECEIVER, intrinsic.getKey().self()),
									List.copyOf(arguments), false, 0))));
			}
			arms.add(list(T, runtimeCall("no-receiver", RECEIVER, string(method.signature()))));
			this.dispatchers.put(name.name(), list(cl("defun"), name, list(parameters), list(arms)));
		}
		return name;
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
					? this.classes.find(written.name(), around.declaration(), this.unit) : null;
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
			List<JavaType> enclosing = this.classes.enclosingArguments(made, this.currentClass);
			if (enclosing.isEmpty()) {
				type = made.self();
			}
			else {
				List<JavaType> arguments = new ArrayList<>(
						made.self().arguments().subList(0, made.typeParameters.size()));
				arguments.addAll(enclosing);
				type = new ClassType(made, List.copyOf(arguments));
			}
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
					"constructor " + made.simpleName(), made, pos, made.typeParameters, type);
			type = (ClassType) resolved.returnType();
		}
		else {
			resolved = resolve(constructors(made), type, explicit, creation.arguments(), null,
					"constructor " + made.simpleName(), made, pos);
			uncheckedCall(resolved.method(), type, callCaret(pos, null));
		}
		raises(creation, pos, resolved.thrown(), null);
		if (resolved.method().emitter != null) {
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
		if (extended.intrinsic && !extended.name.equals("java.lang.Object")) {
			throw unsupported(pos, "an anonymous class of " + extended.name);
		}
		JavaClass made = this.classes.local(Objects.requireNonNull(creation.body()), this.currentClass,
				this.staticContext || this.early, extendedType);
		// outer.new Inner() { .. }: the instance around its superclass is the one given
		made.superOuterGiven = creation.outer() != null;
		if (!made.superOuterGiven && givesSuperOuter(made)) {
			throw error(pos, "an enclosing instance that contains " + innerName(extended) + " is required");
		}
		Method above = null;
		List<LispVal> arguments = List.of();
		this.facts.anonymous.put(creation.body(), made);
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
		lowerLocal(made);
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
		for (int i = from; i < statements.size(); i++) {
			Statement statement = statements.get(i);
			boolean last = i == statements.size() - 1;
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
				forms.add(located(localVariable(variable, statements, i + 1, tail), variable.pos()));
				return forms;
			}
			List<Local> saved = this.patternVariables;
			this.patternVariables = new ArrayList<>();
			this.introduced = Map.of();
			List<LispVal> lowered;
			boolean rest = false;
			if (tail && !last && hoisted == null && statement instanceof If branch && branch.otherwise() == null
					&& exits(branch.then())) {
				Flow flow = test(branch.condition());
				LispVal then = progn(within(flow.whenTrue(), () -> scoped(branch.then(), true)));
				Objects.requireNonNull(this.scopes.peek()).locals.putAll(flow.whenFalse());
				lowered = List.of(located(branch(flow.value().form(), then, progn(sequence(statements, i + 1, true))),
						branch.pos()));
				rest = true;
			}
			else {
				lowered = statement(statement, tail && last);
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
			Typed initial = expression(initializer, type);
			if (type == null) {
				type = this.types.ground(initial.type());
				if (type == JavaType.Null.NULL || type == Primitive.VOID) {
					throw error(declarator.pos(), "cannot infer type for local variable " + declarator.name());
				}
			}
			value = assign(initial, type, declarator.pos(), initializer);
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
		List<LispVal> bindings = new ArrayList<>();
		for (Declarator declarator : variable.declarators()) {
			Declared declared = declare(variable, declarator);
			bindings.add(list(declared.local().symbol(), declared.value()));
		}
		List<LispVal> let = new ArrayList<>();
		let.add(cl(bindings.size() == 1 ? "let" : "let*"));
		let.add(list(bindings));
		let.addAll(sequence(statements, rest, tail));
		return list(let);
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
		return located(expression(expression, null, true).form(), expression.pos());
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
				List<LispVal> bindings = new ArrayList<>();
				for (Statement initializer : loop.initializers()) {
					if (initializer instanceof LocalVariable variable) {
						for (Declarator declarator : variable.declarators()) {
							Declared declared = declare(variable, declarator);
							bindings.add(list(declared.local().symbol(), declared.value()));
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
				LispVal form = bindings.isEmpty() ? progn(before) : form(cl("let*"), prepend(list(bindings), before));
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
						Typed given = expression(exit.value(), this.returnType);
						value = returned(given, this.returnType, exit.value().pos());
						if (!this.lambdaBody && uncheckedConversion(given.type(), this.returnType)) {
							unchecked(caret(exit.value()), "unchecked conversion",
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
					if (value.type() instanceof ClassType locked) {
						JavaClass made = madeAs(written);
						known = new JavaResumable.Lock(made != null ? made : locked.declaration(), made != null, false);
					}
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
		List<LispVal> round = loopBody(List.of(JavaForms.let(variable, value, body)), target);
		List<LispVal> bindings = new ArrayList<>();
		LispVal test;
		if (through == null) {
			round.add(call("setq", index, call("+", index, integer(1))));
			bindings.add(list(items, dereferenced(iterable.form())));
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
		LispVal walk = form(cl("while"), prepend(test, round));
		this.resumable.iterations.add(walk);
		LispVal form = list(cl("let*"), list(bindings), walk);
		return target.breaks ? list(cl("block"), target.breakBlock, form) : form;
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
				this.facts.caught.put(clause, List.copyOf(taken));
				this.facts.tries.put(clause, attempt);
				this.scopes.push(new Scope(null));
				Local bound = bind(clause.name(), Objects.requireNonNull(type), clause.pos(), clause);
				List<LispVal> handler = sequence(clause.body().statements(), 0, false);
				this.scopes.pop();
				clauses.add(list(list(cl("satisfies"), catchTest(taken, takesLispErrors)), list(condition),
						JavaForms.let(bound.symbol(), thrownObject(condition, takesLispErrors), handler)));
			}
			body = form(cl("handler-case"), prepend(body, clauses));
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
	 * The class a lock is surely an instance of, itself and no class under it: a final
	 * field named alone whose initializer makes one, as {@code new Object()}. Else
	 * {@code null}.
	 */
	private @Nullable JavaClass madeAs(Expression lock) {
		if (lock instanceof Name name && variable(name.identifier(), name.pos(), true) instanceof FieldRef read
				&& read.field().isFinal && read.field().declaration != null
				&& read.field().declaration.initializer() instanceof JavaSyntax.New made && made.body() == null
				&& made.type().arguments().isEmpty()) {
			try {
				return type(made.type()) instanceof ClassType type ? type.declaration() : null;
			}
			catch (LispReadException ex) {
				return null;
			}
		}
		return null;
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
		for (String name : RAISED) {
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

	private List<LispVal> initialization() {
		if (this.order.decide(this.initEffects, this.methodEffects, type -> immutable(type, new HashSet<>()))) {
			this.classes.changed();
		}
		boolean threads = startsThreads();
		List<LispVal> forms = new ArrayList<>();
		for (JavaClass declaration : this.classes.all()) {
			if (declaration.intrinsic || (isLibrary(declaration) && !this.classes.used.contains(declaration))) {
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
					forms.add(call("setq", JavaNames.global(field), defaultValue(field.type)));
				}
			}
		}
		for (Map.Entry<String, LispSymbol> constant : this.interned.entrySet()) {
			this.variables.add(list(cl("defvar"), constant.getValue()));
			forms.add(call("setq", constant.getValue(), runtimeCall("intern", string(constant.getKey()))));
		}
		for (JavaClass declaration : this.classes.all()) {
			if (declaration.intrinsic || (isLibrary(declaration) && !this.classes.used.contains(declaration))) {
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
					forms.add(call("setq", JavaNames.global(field),
							constant instanceof String text ? constantString(text).form() : constant(constant).form()));
				}
				else if (fresh && (field.initializer == null || this.order.isLazy(declaration)
						|| called.reads.contains(field))) {
					// a field read ahead of its initializer holds its type's default
					forms.add(call("setq", JavaNames.global(field), defaultValue(field.type)));
				}
			}
		}
		for (JavaClass declaration : this.order.eager()) {
			forms.addAll(Objects.requireNonNull(this.staticInits.get(declaration)));
		}
		Set<JavaClass> failing = failingInitializations();
		for (JavaClass declaration : this.staticInits.keySet()) {
			if (!this.order.isLazy(declaration)) {
				continue;
			}
			LispSymbol done = JavaNames.initialized(declaration);
			this.variables.add(list(cl("defvar"), done));
			if (isFresh(declaration)) {
				forms.add(call("setq", done, NIL));
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
