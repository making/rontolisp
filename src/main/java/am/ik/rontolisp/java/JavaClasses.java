package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaSyntax.ClassDeclaration;
import am.ik.rontolisp.java.JavaSyntax.CompilationUnit;
import am.ik.rontolisp.java.JavaSyntax.Import;
import am.ik.rontolisp.java.JavaSyntax.Parameter;
import am.ik.rontolisp.java.JavaSyntax.TypeName;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.java.JavaType.TypeParameter;
import am.ik.rontolisp.java.JavaType.Variable;
import am.ik.rontolisp.java.JavaType.Wildcard;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The class table of one program: the classes its file declares and the classes of the
 * library it names, found by name where it first names them. A library class is either
 * one whose values are Lisp values ({@link JavaIntrinsics}) or Java source shipped in the
 * jar beside this package ({@code library/java/lang/Throwable.java}), read and lowered
 * like the program's own.
 */
final class JavaClasses {

	/** The packages of the library: what {@code import module java.base} imports. */
	private static final List<String> LIBRARY_PACKAGES = List.of("java.lang", "java.lang.annotation", "java.lang.ref",
			"java.lang.reflect", "java.util", "java.util.function", "java.io", "java.nio.charset",
			"java.util.concurrent", "java.util.concurrent.atomic", "java.util.concurrent.locks", "java.net",
			"java.time", "java.time.chrono", "java.time.format", "java.time.temporal", "java.time.zone",
			"java.security", "java.security.spec", "java.util.stream", "java.text", "java.math", "java.nio",
			"java.nio.file", "java.nio.file.attribute", "javax.net", "javax.crypto", "javax.crypto.interfaces",
			"javax.crypto.spec", "javax.security.auth");

	/** The one package of the module {@code java.net.http}. */
	private static final String HTTP_MODULE = "java.net.http";

	/**
	 * The packages of the module {@code java.sql}, the first of the library outside
	 * {@code java.base}, and those of the modules it requires transitively that the
	 * library has: {@code import module java.sql} imports them too.
	 */
	private static final List<String> SQL_MODULE = List.of("java.sql", "javax.sql", "java.util.logging",
			"javax.transaction.xa", "javax.xml.transform");

	/**
	 * The packages of the module {@code java.naming} that the library has: what
	 * {@code import module java.naming} imports.
	 */
	private static final List<String> NAMING_MODULE = List.of("javax.naming", "javax.naming.spi");

	/** The module of the HTTP server, and the one package of it the library has. */
	private static final String SERVER_MODULE = "jdk.httpserver";

	private static final String SERVER_PACKAGE = "com.sun.net.httpserver";

	/**
	 * Whether a qualified name is one of a JDK's packages: a class of it that the library
	 * does not have is refused by name, and no file of the program is looked for.
	 */
	static boolean isPlatform(String name) {
		return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith(SERVER_PACKAGE + ".")
				|| TEST_PACKAGES.stream().anyMatch(name::startsWith);
	}

	/**
	 * The packages of the test libraries the library has a part of, for a run of a file's
	 * tests: as with a JDK's, a class of them the library lacks is refused by name and
	 * looked for on no source path. JUnit Jupiter is not one of them: it is the program's
	 * own dependency, read from its source path ({@link #junitMissing}).
	 */
	private static final List<String> TEST_PACKAGES = List.of("org.assertj.core.", "org.opentest4j.");

	private static final String JUNIT_JUPITER = "org.junit.jupiter.";

	/**
	 * What is said of a class of JUnit Jupiter where its artifact is on no root of the
	 * source path: its API is the program's own dependency, which the program is compiled
	 * with from its sources ({@code .kb/java-frontend.md}, "Tests of a program"), and the
	 * coordinate that brings what the file imports of it -- the parameterized tests',
	 * which bring the API, where it imports any of theirs. {@code null} for a class of no
	 * package of JUnit Jupiter's, and for one the artifact on the source path lacks,
	 * which is a class of another version.
	 */
	private @Nullable String junitMissing(String qualified, Unit unit) {
		if (!qualified.startsWith(JUNIT_JUPITER)) {
			return null;
		}
		String params = JUNIT_JUPITER + "params.";
		if (this.files.library(qualified.startsWith(params) ? "org/junit/jupiter/params/ParameterizedTest.java"
				: "org/junit/jupiter/api/Test.java") != null) {
			return null;
		}
		boolean parameterized = qualified.startsWith(params)
				|| unit.tree().imports().stream().anyMatch(imported -> imported.name().startsWith(params));
		return "the class " + qualified + " is not on the source path: JUnit Jupiter is a dependency of the "
				+ "program, compiled from its sources (--java-source-dep org.junit.jupiter:"
				+ (parameterized ? "junit-jupiter-params" : "junit-jupiter-api") + ":VERSION)";
	}

	/**
	 * The qualified name a simple name no class has is read as: what a file's single-type
	 * import names, else the name in a package of JUnit Jupiter's the file imports on
	 * demand (so that {@link #junitMissing} can say where it is), else the name.
	 */
	private static String missingName(String name, Unit unit) {
		Import imported = unit.imports().get(name);
		if (imported != null) {
			return imported.name();
		}
		for (String onDemand : unit.onDemand()) {
			if (onDemand.startsWith(JUNIT_JUPITER) && name.indexOf('.') < 0) {
				return onDemand + "." + name;
			}
		}
		return name;
	}

	/**
	 * The public classes of a JDK 25's {@code java.lang}, by simple name (asked of its
	 * run-time image): such a name no class has is one the library lacks, refused by
	 * name, as every file imports the package.
	 */
	private static final Set<String> JAVA_LANG = Set.of("AbstractMethodError", "Appendable", "ArithmeticException",
			"ArrayIndexOutOfBoundsException", "ArrayStoreException", "AssertionError", "AutoCloseable", "Boolean",
			"BootstrapMethodError", "Byte", "CharSequence", "Character", "Class", "ClassCastException",
			"ClassCircularityError", "ClassFormatError", "ClassLoader", "ClassNotFoundException", "ClassValue",
			"CloneNotSupportedException", "Cloneable", "Comparable", "Deprecated", "Double", "Enum",
			"EnumConstantNotPresentException", "Error", "Exception", "ExceptionInInitializerError", "Float",
			"FunctionalInterface", "IO", "IllegalAccessError", "IllegalAccessException", "IllegalArgumentException",
			"IllegalCallerException", "IllegalMonitorStateException", "IllegalStateException",
			"IllegalThreadStateException", "IncompatibleClassChangeError", "IndexOutOfBoundsException",
			"InheritableThreadLocal", "InstantiationError", "InstantiationException", "Integer", "InternalError",
			"InterruptedException", "Iterable", "LayerInstantiationException", "LinkageError", "Long", "MatchException",
			"Math", "Module", "ModuleLayer", "NegativeArraySizeException", "NoClassDefFoundError", "NoSuchFieldError",
			"NoSuchFieldException", "NoSuchMethodError", "NoSuchMethodException", "NullPointerException", "Number",
			"NumberFormatException", "Object", "OutOfMemoryError", "Override", "Package", "Process", "ProcessBuilder",
			"ProcessHandle", "Readable", "Record", "ReflectiveOperationException", "Runnable", "Runtime",
			"RuntimeException", "RuntimePermission", "SafeVarargs", "ScopedValue", "SecurityException",
			"SecurityManager", "Short", "StableValue", "StackOverflowError", "StackTraceElement", "StackWalker",
			"StrictMath", "String", "StringBuffer", "StringBuilder", "StringIndexOutOfBoundsException",
			"SuppressWarnings", "System", "Thread", "ThreadDeath", "ThreadGroup", "ThreadLocal", "Throwable",
			"TypeNotPresentException", "UnknownError", "UnsatisfiedLinkError", "UnsupportedClassVersionError",
			"UnsupportedOperationException", "VerifyError", "VirtualMachineError", "Void", "WrongThreadException");

	private static final String COMPARABLE = "java.lang.Comparable";

	private static final String ENUM = "java.lang.Enum";

	/**
	 * The file a class was declared in: what a simple name is resolved against.
	 *
	 * @param source its text
	 * @param packageName its package, or {@code null}
	 * @param imports the single-type imports, by simple name
	 * @param onDemand the packages imported on demand
	 * @param modules the packages of the modules imported, which those imported on demand
	 * shadow: {@code java.base}'s for a compact source file
	 * @param declared the classes it declares, by simple name
	 * @param statics the classes a static import names, by the simple name of the member
	 * it imports
	 * @param staticOnDemand the classes whose static members are imported on demand
	 * @param unchecked where its unchecked warnings are suppressed
	 * @param tree the file as it was read
	 */
	record Unit(Source source, @Nullable String packageName, Map<String, Import> imports, List<String> onDemand,
			List<String> modules, Map<String, JavaClass> declared, Map<String, List<JavaClass>> statics,
			List<JavaClass> staticOnDemand, List<JavaSyntax.Span> unchecked, CompilationUnit tree) {
	}

	private final Map<String, JavaClass> classes = new LinkedHashMap<>();

	/** The program's {@code @WitImport} interfaces and their bindings. */
	final JavaWitImports wit = new JavaWitImports(this);

	/** The program's {@code @WitExport} classes and the worlds they implement. */
	final JavaWitExports exports = new JavaWitExports(this);

	/** The exports of a program with no main compiled to a module with no WASI. */
	final JavaReactorExports reactor = new JavaReactorExports();

	private final Map<String, JavaClass> lispNames = new HashMap<>();

	private final Map<JavaClass, Unit> units = new IdentityHashMap<>();

	private final Set<String> missing = new HashSet<>();

	/** The local and anonymous classes, by declaration: a pass made again finds them. */
	private final Map<ClassDeclaration, JavaClass> locals = new IdentityHashMap<>();

	private final Map<String, Integer> localNumbers = new HashMap<>();

	private final Map<ClassDeclaration, Integer> numbers = new IdentityHashMap<>();

	/** The classes whose bodies' local and anonymous classes are numbered. */
	private final Set<ClassDeclaration> numbered = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	private int version;

	private final Function<JavaClasses, Relations> relationsMaker;

	private @Nullable Relations relations;

	/**
	 * The bound checks of the type arguments written in the declarations being read, made
	 * once their bounds are set; {@code null} outside a declaration, where a type
	 * argument is checked where it is written.
	 */
	private @Nullable List<Runnable> boundChecks;

	/** The local classes in scope where the lowering stands, by simple name. */
	@Nullable Function<String, @Nullable JavaClass> localScope;

	/**
	 * The type parameters of the method whose signature is being read or whose body is
	 * being lowered.
	 */
	List<TypeParameter> methodParameters = List.of();

	/** The interfaces a lambda or a method reference of the program is made for. */
	final Set<JavaClass> lambdaTargets = new java.util.LinkedHashSet<>();

	/**
	 * The interfaces whose lambdas are structures holding the function, because the
	 * program can tell which interface a lambda was made for.
	 */
	final Set<JavaClass> taggedLambdas = new java.util.LinkedHashSet<>();

	/**
	 * The interface a lambda cast to an intersection of interfaces is made for, by those
	 * interfaces: one no declaration names, extending each, so a lambda made for it is an
	 * instance of every one.
	 */
	private final Map<List<JavaClass>, JavaClass> intersections = new HashMap<>();

	/**
	 * The interface a lambda cast to an intersection of interfaces is made for.
	 * @param interfaces the interfaces, as written
	 * @return the one interface extending them all, the same for the same interfaces
	 */
	JavaClass intersection(List<JavaClass> interfaces) {
		return this.intersections.computeIfAbsent(List.copyOf(interfaces), key -> {
			JavaClass made = new JavaClass(String.join("&", key.stream().map(each -> each.name).toList()), true, true,
					false, null, null);
			made.interfaces.addAll(key);
			return made;
		});
	}

	/**
	 * The methods of the library's Java source a pass called: only these are lowered.
	 * Kept over the passes, as the two sets after it.
	 */
	final Set<JavaClass.Method> reached = new java.util.LinkedHashSet<>();

	/**
	 * The method whose body first called each method reached from the Java source of a
	 * library: the way a refusal inside one is told to come from the program.
	 */
	final Map<JavaClass.Method, JavaClass.Method> reachedFrom = new IdentityHashMap<>();

	/** The classes of the library's Java source a pass makes an instance of. */
	final Set<JavaClass> made = new java.util.LinkedHashSet<>();

	/**
	 * Whether the program can tell a monitor that is held from one that is not: it
	 * reaches {@code Thread}, which is where a thread is started and where {@code wait},
	 * {@code notify} and {@code holdsLock} are written. Until a pass finds it does,
	 * {@code synchronized} holds nothing and a {@code volatile} field is any field.
	 */
	boolean monitors;

	/**
	 * The regions of the tz database the program holds ({@link JavaZones}): the ones it
	 * names as constants, in the order of their names. Until a pass finds one, a region
	 * is unknown to the program and nothing a region needs is lowered.
	 */
	final Set<String> regions = new java.util.TreeSet<>();

	/**
	 * Whether the program holds every region: it computes a zone's name where it runs, or
	 * asks for the names.
	 */
	boolean everyRegion;

	/**
	 * Whether the program reaches {@code TemporalQueries.chronology()}, so that a date or
	 * a time may be asked its calendar. Until a pass finds it does, no class of
	 * {@code java.time} compares a query with that question.
	 */
	boolean calendars;

	/**
	 * The letters the patterns of the program's formatters name, outside their quoted
	 * texts, with a mark of words and of a year of two digits where a run of letters
	 * names one and the {@code [} of an optional section: the constant ones it was seen
	 * making. A letter of weeks, quarters or the modified Julian day, words, a fraction,
	 * a zone's id, an offset makes its parts only once one names it.
	 */
	final Set<Character> patternLetters = new java.util.TreeSet<>();

	/** Whether the program makes a formatter of a pattern computed where it runs. */
	boolean everyLetter;

	/**
	 * The conversions the constant controls of the program's formats name that the run
	 * time leaves to the library: {@code h}, {@code g}, {@code a} and {@code t} whatever
	 * their case, and {@code Z} for a {@code %t} writing a zone's name. A conversion's
	 * code is in a program only once one names it.
	 */
	final Set<Character> formatConversions = new java.util.TreeSet<>();

	/** Whether the program writes a format of a control computed where it runs. */
	boolean everyFormat;

	/**
	 * Whether a format of the program can be given a {@code long} or a {@code Date},
	 * which {@code %t} reads in the host's zone: an argument of a type that can hold one,
	 * or an array of arguments given as it is.
	 */
	boolean formatsMillis;

	/**
	 * The parameters of static and private methods whose texts name something to the
	 * lowering (a formatter's pattern, a zone's id), by method and index, each with what
	 * a text passed there names: a call of the method names what it passes, any other way
	 * to it what a text computed where the program runs names.
	 */
	final Map<JavaClass.Method, Map<Integer, Set<JavaEmitter.Naming>>> namingParameters = new IdentityHashMap<>();

	/** The parameters some body assigns, by their declarations. */
	final Set<Object> assignedParameters = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * The methods of the library some pass reached that a question of the library is
	 * answered by ({@code JavaIntrinsics.reaches}), as class and name: until a pass finds
	 * one, what it decides is not lowered.
	 */
	final Set<String> reachedMembers = new java.util.TreeSet<>();

	/**
	 * The methods of the library code outside it reached -- the program's or a library's
	 * from its source path -- that a question of the library is answered by, as class and
	 * name ({@code java.util.Locale.<init>}).
	 */
	final Set<String> reachedOutside = new java.util.TreeSet<>();

	/**
	 * The static fields of the library some pass read whose initializers ask whether they
	 * are wanted: a constant no program reads is not made (a formatter's ISO forms).
	 */
	final Set<JavaClass.Field> wanted = new java.util.LinkedHashSet<>();

	/**
	 * Whether the program can ask the class of an array whose vector does not say it --
	 * an array of {@code long}, of {@code short} or of references other than
	 * {@code Object}: it casts or tests such a type, or asks the class name of a value
	 * that can be one. Until a pass finds it does, a conversion that widens such an
	 * array's type records nothing; a session always records.
	 */
	boolean arrayTypes;

	/**
	 * The types whose class object some pass read as a value, erased: a class literal's,
	 * the classes {@code getClass()} can answer, and what those are made of.
	 */
	final Set<JavaType> classValues = new java.util.LinkedHashSet<>();

	/**
	 * The classes {@code getClass()} is read as a value under: each has the function
	 * choosing the class object by what the value is.
	 */
	final Set<JavaClass> classOfs = new java.util.LinkedHashSet<>();

	/**
	 * Whether {@code getClass()} is read as a value of what can be an array its static
	 * type does not tell the class of: the program then has the class object of every
	 * array type it records ({@link #arrayRecords}).
	 */
	boolean arrayClassValues;

	/** The array types whose class a conversion records, or a read falls back on. */
	final Set<JavaType.ArrayType> arrayRecords = new java.util.LinkedHashSet<>();

	/**
	 * Whether the program makes an array of a class object ({@code Array.newInstance}):
	 * the class objects then answer what the run time records of an array of their class
	 * ({@code %array-of}).
	 */
	boolean arraysOfClasses;

	/**
	 * Whether the program makes the class object of an array type where it runs
	 * ({@code Class.arrayType()}, or an array made of a class object in a program asking
	 * an array its class object): the class objects then answer their array types', and
	 * an array's class object is found among those made too.
	 */
	boolean arrayTypesMade;

	/**
	 * Whether the program reaches {@code Class.getEnumConstants()}: what holds an enum's
	 * class object then initializes the enum as a call of its {@code values()} does. Set
	 * too by a set or a map of enum constants made over an enum no call names.
	 */
	boolean enumConstants;

	/**
	 * The enums a call making an {@code EnumSet} or an {@code EnumMap} names: their class
	 * objects hold their constants where only those calls ask for them.
	 */
	final Set<JavaClass> universes = new HashSet<>();

	/**
	 * Whether a call looks a method or a constructor up by parameter types
	 * ({@code getDeclaredMethod(name, types..)}, {@code getConstructor(types..)}): the
	 * class objects then answer every member of their classes, not those of no parameter
	 * by their names alone.
	 */
	boolean reflectsTypes;

	/**
	 * Whether a call hands a method or a constructor found by reflection arguments
	 * ({@code invoke(o, args..)}, {@code newInstance(args..)}): the members of parameters
	 * are then called.
	 */
	boolean reflectsArguments;

	/**
	 * Whether the class objects answer the members of their classes in full
	 * ({@code %class-members}), which the natives asking for them read.
	 */
	boolean memberTables;

	/**
	 * The members of {@code Class} that are natives lowered where the program calls them
	 * ({@code getModifiers}, {@code newInstance}), which no method body reaches: the
	 * functions over the class objects they ask are made as for a member reached.
	 */
	final Set<String> nativeReflection = new java.util.LinkedHashSet<>();

	/**
	 * The types whose class object the program's code holds: a class literal's, the
	 * classes {@code getClass()} can answer, a class found by its name. Of a class of the
	 * JDK, these and the classes above them answer their members; one
	 * {@link #classValues} holds only as the type of a member reflection found does not
	 * ({@code .kb/java-frontend.md}, "Reflection over the JDK's classes").
	 */
	final Set<JavaType> heldValues = new java.util.LinkedHashSet<>();

	/**
	 * The intrinsic members a call of the program's (or of the library's source it
	 * reaches) is lowered to: with {@link #reached}, what the compiled program holds of a
	 * member of a class of the JDK, which reflection calls only there.
	 */
	final Set<JavaClass.Method> intrinsicsCalled = new java.util.LinkedHashSet<>();

	/**
	 * The classes standing for a JDK's type the library does not have, which a member
	 * reflection found names, by binary name: a class object's name and kind, no more.
	 */
	private final Map<String, JavaClass> standIns = new HashMap<>();

	/**
	 * The class standing for a type of the JDK the library does not have: its class
	 * object's names and kind, which the table of the JDK's members gives. It is in no
	 * table of the program's classes.
	 */
	JavaClass standIn(String binaryName, boolean isInterface) {
		return this.standIns.computeIfAbsent(binaryName, name -> {
			String simple = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
			return new JavaClass(name.replace('$', '.'), name, simple, isInterface, isInterface, false, null, null);
		});
	}

	/**
	 * The library's class of a JDK's binary name, or {@code null} where the library lacks
	 * it: read as a library's declaration reads a class, so that naming an intrinsic
	 * class here holds no value of it ({@link #held}).
	 */
	@Nullable JavaClass jdkClass(String binaryName) {
		this.declaringLibrary++;
		try {
			int nested = binaryName.indexOf('$');
			if (nested > 0 && qualified(binaryName.substring(0, nested)) == null) {
				return null;
			}
			return qualified(binaryName.replace('$', '.'));
		}
		finally {
			this.declaringLibrary--;
		}
	}

	/**
	 * The classes of the library's Java source whose static initialization a pass needs.
	 */
	final Set<JavaClass> used = new java.util.LinkedHashSet<>();

	/**
	 * Whether the program compares two strings by reference: its constant strings are
	 * then one object a text.
	 */
	boolean identicalStrings;

	private final Library library;

	/**
	 * The classes of the library that are no Java source: made by name, checked by type.
	 */
	interface Library {

		/** Makes the class of a qualified name and adds it, or answers {@code null}. */
		@Nullable JavaClass create(String name, JavaClasses classes);

		/**
		 * Tells that a class which is no Java source and a class of the library's Java
		 * source are both in the table: the first may gain members naming the second.
		 */
		void meets(JavaClass intrinsic, JavaClass named, JavaClasses classes);

		/**
		 * The interfaces of the library's Java source that a class which is no Java
		 * source implements, by qualified name: each with the class itself as its
		 * argument when it takes one.
		 */
		List<String> implemented(String name);

		/**
		 * The form a {@code native} member of the library's Java source is, or
		 * {@code null}: found by its class's name, its own and its erased parameters.
		 */
		@Nullable JavaEmitter emitter(String member);

		/**
		 * Whether the {@code String} a {@code native} member of the library's Java source
		 * answers may be {@code null}, so that a concatenation writes {@code null} for
		 * it.
		 */
		default boolean answersNull(String member) {
			return false;
		}

	}

	/**
	 * Whether the program is a session's: its implicit class's variables may be declared
	 * with {@code var} ({@code JavaSession}).
	 */
	boolean session;

	/** Where the files of the program's source tree are read. */
	private final JavaFiles files;

	/** The file the program starts at, or {@code null}: no other file is read. */
	private final @Nullable String entry;

	/** The path from the starting file's directory up to the root of its source tree. */
	private final String root;

	/**
	 * @param library the classes of the library that are no Java source
	 * @param files where the other files of the program's source tree are read
	 * @param entry the file the program starts at, or {@code null} when it has none and
	 * no other file is read
	 * @param root the path from the starting file's directory up to the root of its
	 * source tree: a {@code ../} for each name of its package
	 * @param relations makes what the table asks of the relations between its types
	 */
	/** The measurement taken instead of a program, or {@code null}: nearly always. */
	final @Nullable JavaCensus census = JavaCensus.asked();

	JavaClasses(Library library, JavaFiles files, @Nullable String entry, String root,
			Function<JavaClasses, Relations> relations) {
		this.library = library;
		this.files = files;
		this.entry = entry;
		this.root = root;
		this.relationsMaker = relations;
	}

	/**
	 * What the table asks of the relations between its types, which the lowering's type
	 * relations answer: the arguments of an inner class's enclosing type, a type
	 * argument's bounds.
	 */
	interface Relations {

		@Nullable ClassType asSuper(JavaType type, JavaClass above);

		boolean isSubtype(JavaType from, JavaType to);

		JavaType substituted(JavaType type, Map<TypeParameter, JavaType> bindings);

	}

	static LispReadException error(@Nullable Source source, int pos, String message) {
		return new LispReadException(message,
				source == null ? null : SourceLocation.at(source.file(), pos, source.text()));
	}

	static LispReadException unsupported(@Nullable Source source, int pos, String what) {
		return error(source, pos, what + " is not supported by the experimental Java subset yet");
	}

	/** The error a JVM throws where code runs that links to a class it does not have. */
	static final String NO_CLASS = "java.lang.NoClassDefFoundError";

	/** The error a JVM throws where code runs that calls a method its class lacks. */
	static final String NO_METHOD = "java.lang.NoSuchMethodError";

	/**
	 * A class or a member the subset lacks, named where a body is lowered: refused in the
	 * program's own files; in the body of a library the program uses, what the statement
	 * naming it throws where it runs ({@code JavaLowering.deferred}), as a JVM links the
	 * code of a class to what it names where that code first runs.
	 */
	static final class Missing extends LispReadException {

		private static final long serialVersionUID = 1L;

		/** The class of the error the statement throws where it runs. */
		final String error;

		/** The class the code links to, or {@code null} for a member. */
		final @Nullable String named;

		Missing(String message, @Nullable SourceLocation location, String error, @Nullable String named) {
			super(message, location);
			this.error = error;
			this.named = named;
		}

	}

	/**
	 * {@link #unsupported} of a class or a member the subset lacks.
	 * @param named the class, or {@code null} for a member
	 */
	static LispReadException missing(@Nullable Source source, int pos, String what, @Nullable String named) {
		return new Missing(what + " is not supported by the experimental Java subset yet",
				source == null ? null : SourceLocation.at(source.file(), pos, source.text()),
				named == null ? NO_METHOD : NO_CLASS, named);
	}

	Collection<JavaClass> all() {
		return this.classes.values();
	}

	/**
	 * How many times the table changed: a class added, a local found to be read by a
	 * local class. A lowering made while it moved is made again.
	 */
	int version() {
		return this.version;
	}

	void changed() {
		this.version++;
	}

	Unit unit(JavaClass declaration) {
		Unit unit = this.units.get(declaration);
		if (unit == null) {
			throw new IllegalStateException(declaration + " was read from no file");
		}
		return unit;
	}

	/**
	 * Adds a class to the table. Two classes whose Lisp names are one -- a class of the
	 * program named as a {@code java.lang} class the program also uses -- are refused:
	 * every name made of them would be shared.
	 */
	void register(JavaClass declaration) {
		JavaClass namesake = this.lispNames.get(declaration.lispName());
		if (namesake != null && namesake != declaration) {
			JavaClass ours = namesake.name.startsWith("java.") ? declaration : namesake;
			JavaClass theirs = ours == namesake ? declaration : namesake;
			throw unsupported(ours.source, ours.declaration == null ? 0 : ours.declaration.pos(),
					"a class named as " + theirs.name + ", which the program also uses,");
		}
		this.classes.put(declaration.name, declaration);
		this.lispNames.put(declaration.lispName(), declaration);
		this.version++;
		if (!declaration.intrinsic && (declaration.isInterface || isLibrarySource(declaration))) {
			for (JavaClass known : List.copyOf(this.classes.values())) {
				implement(known, declaration);
			}
		}
		if (declaration.intrinsic || declaration.name.equals(ENUM)) {
			for (JavaClass known : List.copyOf(this.classes.values())) {
				implement(declaration, known);
			}
		}
	}

	private static boolean isLibrarySource(JavaClass declaration) {
		return declaration.source != null && declaration.source.library();
	}

	/**
	 * Makes a class of the library implement an interface of the library's Java source
	 * when it does: an enum's superclass and a box are {@code Comparable} to themselves,
	 * a string a {@code CharSequence} too. Only in a program that names the interface, so
	 * that every other program holds no interface it does not name.
	 */
	private void implement(JavaClass declaration, JavaClass implemented) {
		if (implemented.intrinsic) {
			return;
		}
		if (declaration.intrinsic && (implemented.isInterface || isLibrarySource(implemented))) {
			this.library.meets(declaration, implemented, this);
		}
		if (!implemented.isInterface) {
			return;
		}
		boolean enumeration = declaration.name.equals(ENUM) && implemented.name.equals(COMPARABLE);
		if ((!enumeration
				&& !(declaration.intrinsic && this.library.implemented(declaration.name).contains(implemented.name)))
				|| declaration.interfaces.contains(implemented)) {
			return;
		}
		declaration.interfaces.add(implemented);
		if (!implemented.typeParameters.isEmpty()) {
			declaration.parameterized.put(implemented, new ClassType(implemented, List.of(enumeration
					? new Variable(declaration.typeParameters.get(0)) : new ClassType(declaration, List.of()))));
		}
		this.version++;
	}

	/**
	 * Adds the classes a file declares: every class first, so that one may name another,
	 * then what each extends and its members.
	 * @param tree the file
	 * @param source its text
	 * @return the top-level classes, in the file's order
	 */
	List<JavaClass> declare(CompilationUnit tree, Source source) {
		Unit unit = unitOf(tree, source);
		List<JavaClass> declared = new ArrayList<>();
		List<JavaClass> all = new ArrayList<>();
		for (ClassDeclaration declaration : tree.classes()) {
			String name = tree.packageName() == null ? declaration.name()
					: tree.packageName() + "." + declaration.name();
			if (this.classes.containsKey(name) || unit.declared().containsKey(declaration.name())) {
				throw error(source, declaration.pos(), "duplicate class: " + name);
			}
			JavaClass made = create(declaration, name, name, null, true, unit, all);
			unit.declared().put(declaration.name(), made);
			declared.add(made);
		}
		complete(all, unit);
		for (JavaSyntax.Annotation annotation : tree.annotations()) {
			annotation(annotation, unit, all);
		}
		applicable(tree, unit, all);
		for (Import imported : tree.imports()) {
			if (imported.isStatic()) {
				importStatic(imported, unit);
			}
			else if (!imported.onDemand() && !imported.module() && !isPlatform(imported.name())) {
				// a class of the program is looked for where it is imported, as a
				// compiler
				// does; one of the library where it is used, so that a program holds only
				// the library classes it uses
				require(imported.name(), imported.pos() + "import ".length(), null, unit);
			}
		}
		return declared;
	}

	/** A file's scope: its package and what it imports. */
	private Unit unitOf(CompilationUnit tree, Source source) {
		Map<String, Import> imports = new HashMap<>();
		List<String> onDemand = new ArrayList<>();
		List<String> modules = new ArrayList<>();
		boolean compact = tree.classes().stream().anyMatch(ClassDeclaration::implicit);
		if (compact) {
			modules.addAll(LIBRARY_PACKAGES);
		}
		for (Import imported : tree.imports()) {
			if (imported.isStatic()) {
				continue;
			}
			if (imported.module()) {
				if (imported.name().equals(HTTP_MODULE)) {
					modules.add(HTTP_MODULE);
				}
				else if (imported.name().equals(SERVER_MODULE)) {
					modules.add(SERVER_PACKAGE);
				}
				else if (imported.name().equals("java.sql")) {
					modules.addAll(SQL_MODULE);
				}
				else if (imported.name().equals("java.naming")) {
					modules.addAll(NAMING_MODULE);
				}
				else if (imported.name().equals("java.logging")) {
					modules.add("java.util.logging");
				}
				else if (imported.name().equals("java.base")) {
					modules.addAll(LIBRARY_PACKAGES);
				}
				else {
					throw unsupported(source, imported.pos(), "the module " + imported.name());
				}
			}
			else if (imported.onDemand()) {
				onDemand.add(imported.name());
			}
			else {
				imports.put(imported.name().substring(imported.name().lastIndexOf('.') + 1), imported);
			}
		}
		return new Unit(source, tree.packageName(), imports, onDemand, modules, new LinkedHashMap<>(), new HashMap<>(),
				new ArrayList<>(), tree.unchecked(), tree);
	}

	/**
	 * The class making the annotations a package of the program's source tree carries,
	 * where its {@code package-info.java} writes some of runtime retention that may stand
	 * on a package: by the package's name, generated Java source under the file's
	 * imports, a static method {@code a<index>} each. Read once, when first asked for.
	 *
	 * @param holder the class's qualified name
	 * @param count how many it makes
	 */
	record PackageAnnotations(String holder, int count) {
	}

	private @Nullable Map<String, PackageAnnotations> packageAnnotations;

	Map<String, PackageAnnotations> packageAnnotations() {
		Map<String, PackageAnnotations> known = this.packageAnnotations;
		if (known != null) {
			return known;
		}
		Map<String, PackageAnnotations> found = new LinkedHashMap<>();
		this.packageAnnotations = found;
		treeSources().forEach((path, file) -> {
			if (!path.equals("package-info.java") && !path.endsWith("/package-info.java")) {
				return;
			}
			CompilationUnit tree = JavaParser.parse(file.text(), file.path(), "package-info");
			String packageName = tree.packageName();
			if (packageName == null || tree.packageAnnotations().isEmpty() || found.containsKey(packageName)) {
				return;
			}
			Unit unit = unitOf(tree, new Source(file.path(), file.text(), JavaClass.Origin.PROGRAM));
			for (Import imported : tree.imports()) {
				if (imported.isStatic()) {
					importStatic(imported, unit);
				}
			}
			StringBuilder members = new StringBuilder();
			int count = 0;
			List<Carried> carried = new ArrayList<>();
			for (JavaSyntax.Annotation annotation : tree.packageAnnotations()) {
				JavaClass type = annotationTypeIn(annotation, null, unit);
				if (type != null && isRuntime(type)) {
					carried.add(new Carried(type, annotation, carried.size()));
				}
			}
			for (Carried each : wrapped(carried)) {
				if (appliesTo(each.type(), "PACKAGE")) {
					String value = annotationValue(each.written(), each.type(), unit, members, true);
					members.append("    public static Object a")
						.append(count++)
						.append("() {\n        return ")
						.append(value)
						.append(";\n    }\n");
				}
			}
			if (count > 0) {
				String simple = "$package$info$Annotations";
				String name = packageName + "." + simple;
				declareGenerated(name, simple, JavaAnnotations.header(unit, List.of()) + "public final class " + simple
						+ " {\n" + members + "}\n");
				found.put(packageName, new PackageAnnotations(name, count));
			}
		});
		return found;
	}

	/**
	 * The annotation types of {@code java.lang}: none changes what a program that
	 * compiles does, and no file is read for one.
	 */
	private static final Set<String> ANNOTATIONS = Set.of("Override", "Deprecated", "SuppressWarnings", "SafeVarargs",
			"FunctionalInterface");

	/**
	 * Checks that an annotation of a program's file names an annotation type the file can
	 * see, as a compiler does: its meaning, if it has one, is a processor's or a
	 * framework's, and its absence would change the program. The annotation itself is
	 * dropped. One of a library the program uses is not looked for: a compiler took that
	 * library where the type was, and reads the library's class without it.
	 */
	private void annotation(JavaSyntax.Annotation written, Unit unit, List<JavaClass> all) {
		String name = written.name();
		if (unit.source().partial()
				|| ANNOTATIONS.contains(name.startsWith("java.lang.") ? name.substring("java.lang.".length()) : name)) {
			return;
		}
		JavaClass type = find(name, null, unit, written.pos());
		for (int i = 0; type == null && i < all.size(); i++) {
			type = find(name, all.get(i), unit, written.pos());
		}
		if (type == null) {
			String qualified = missingName(name, unit);
			if (isPlatform(qualified)) {
				// an annotation type of the packages the library has a part of
				throw unsupported(unit.source(), written.pos(), "the class " + qualified);
			}
			String junit = junitMissing(qualified, unit);
			throw error(unit.source(), written.pos(), junit != null ? junit : "cannot find symbol: class " + name);
		}
		if (!type.isAnnotation) {
			throw error(unit.source(), written.pos(),
					"incompatible types: " + type.simpleName() + " cannot be converted to Annotation");
		}
		accessible(type, unit, written.pos());
	}

	/**
	 * Checks that each annotation of a program's file stands where its type's
	 * {@code @Target} lets it (JLS 9.6.4.1, 9.7.4), with javac's words: on a declaration,
	 * one that applies to that kind of declaration or, where the declaration has a type,
	 * to a use of a type the annotation can stand on; in a type, one that applies to a
	 * use of a type, standing ahead of a type a qualified name names and not of a package
	 * or a class only scoping a static one. A library's file is not asked: a compiler
	 * took it.
	 */
	private void applicable(CompilationUnit tree, Unit unit, List<JavaClass> all) {
		if (unit.source().partial()) {
			return;
		}
		Map<Integer, JavaSyntax.Annotation> written = new HashMap<>();
		for (JavaSyntax.Annotation annotation : tree.annotations()) {
			written.put(annotation.pos(), annotation);
		}
		for (Map.Entry<Integer, JavaSyntax.AnnotationSite> each : new java.util.TreeMap<>(tree.annotationSites())
			.entrySet()) {
			JavaSyntax.Annotation annotation = written.get(each.getKey());
			JavaSyntax.AnnotationSite site = each.getValue();
			Set<String> targets = annotation == null ? Set.of() : targets(annotation, unit, all);
			if (annotation == null || targets == null || declaresOn(targets, site.kind())) {
				continue;
			}
			boolean typeUse = targets.contains("TYPE_USE");
			if (site.kind().equals("TYPE_PARAMETER")) {
				throw error(unit.source(), annotation.pos(),
						"annotation @" + annotation.name() + " not applicable in this type context");
			}
			JavaSyntax.TypeName type = site.type();
			if (!typeUse || type == null || !TYPED_SITES.contains(site.kind())) {
				throw error(unit.source(), annotation.pos(),
						"annotation interface not applicable to this kind of declaration");
			}
			if (!admissible(type.name(), 0, unit, all)) {
				throw error(unit.source(), annotation.pos(),
						"type annotation @" + annotationTypeName(annotation, unit, all) + " is not expected here");
			}
		}
		for (JavaSyntax.TypeAnnotations typed : tree.typeAnnotations().values()) {
			List<Map.Entry<Integer, List<JavaSyntax.Annotation>>> words = new ArrayList<>(typed.words().entrySet());
			for (List<JavaSyntax.Annotation> dimension : typed.dimensions()) {
				words.add(Map.entry(-1, dimension));
			}
			for (Map.Entry<Integer, List<JavaSyntax.Annotation>> word : words) {
				for (JavaSyntax.Annotation annotation : word.getValue()) {
					Set<String> targets = targets(annotation, unit, all);
					if (targets == null || !targets.contains("TYPE_USE")) {
						throw error(unit.source(), annotation.pos(),
								"annotation @" + annotation.name() + " not applicable in this type context");
					}
					if (word.getKey() >= 0 && !typed.name().startsWith("?")
							&& !admissible(typed.name(), word.getKey(), unit, all)) {
						throw error(unit.source(), annotation.pos(), "type annotation @"
								+ annotationTypeName(annotation, unit, all) + " is not expected here");
					}
				}
			}
		}
	}

	/** The declarations whose type a type annotation written on them stands on. */
	private static final Set<String> TYPED_SITES = Set.of("FIELD", "METHOD", "PARAMETER", "LOCAL_VARIABLE");

	/**
	 * Whether an annotation type whose {@code @Target} names these may stand on a kind of
	 * declaration (JLS 9.6.4.1): a use of a type is a class's and a type parameter's too,
	 * a record's component takes what its field, its accessor or its constructor's
	 * parameter does, an enum's constant is a field whose type a type annotation may not
	 * be read on.
	 */
	private static boolean declaresOn(Set<String> targets, String kind) {
		boolean typeUse = targets.contains("TYPE_USE");
		return switch (kind) {
			case "TYPE" -> typeUse || targets.contains("TYPE");
			case "ANNOTATION_TYPE" -> typeUse || targets.contains("TYPE") || targets.contains("ANNOTATION_TYPE");
			case "TYPE_PARAMETER", "CONSTRUCTOR" -> typeUse || targets.contains(kind);
			case "ENUM_CONSTANT" -> typeUse || targets.contains("FIELD");
			case "RECORD_COMPONENT" -> typeUse || targets.contains(kind) || targets.contains("FIELD")
					|| targets.contains("METHOD") || targets.contains("PARAMETER");
			default -> targets.contains(kind);
		};
	}

	/**
	 * The {@code @Target} of an annotation's type: the names of the kinds it names, or
	 * {@code null} where it has none, which is every kind of declaration and no use of a
	 * type (JLS 9.6.4.1). {@code java.lang}'s by their documentation, no file read; an
	 * annotation of a type not found is not asked ({@code null}).
	 */
	private @Nullable Set<String> targets(JavaSyntax.Annotation written, Unit unit, List<JavaClass> all) {
		String name = written.name();
		Set<String> known = LANG_TARGETS
			.get(name.startsWith("java.lang.") ? name.substring("java.lang.".length()) : name);
		if (known != null && !name.contains(".") || known != null && name.startsWith("java.lang.")) {
			return known;
		}
		JavaClass type = writtenType(written, unit, all);
		return type == null ? null : targets(type);
	}

	/** The annotation type an annotation of a program's file names, or {@code null}. */
	private @Nullable JavaClass writtenType(JavaSyntax.Annotation written, Unit unit, List<JavaClass> all) {
		try {
			JavaClass type = find(written.name(), null, unit, written.pos());
			for (int i = 0; type == null && i < all.size(); i++) {
				type = find(written.name(), all.get(i), unit, written.pos());
			}
			return type != null && type.isAnnotation ? type : null;
		}
		catch (LispReadException | JavaClass.Absent ex) {
			return null;
		}
	}

	/** An annotation's type as javac names it in a refusal: its canonical name. */
	private String annotationTypeName(JavaSyntax.Annotation written, Unit unit, List<JavaClass> all) {
		JavaClass type = writtenType(written, unit, all);
		return type == null ? written.name() : type.name;
	}

	/** The {@code @Target} of {@code java.lang}'s annotation types, as documented. */
	private static final Map<String, Set<String>> LANG_TARGETS = Map.of("Override", Set.of("METHOD"), "Deprecated",
			Set.of("CONSTRUCTOR", "FIELD", "LOCAL_VARIABLE", "METHOD", "PACKAGE", "MODULE", "PARAMETER", "TYPE"),
			"SuppressWarnings",
			Set.of("TYPE", "FIELD", "METHOD", "PARAMETER", "CONSTRUCTOR", "LOCAL_VARIABLE", "MODULE"), "SafeVarargs",
			Set.of("CONSTRUCTOR", "METHOD"), "FunctionalInterface", Set.of("TYPE"));

	/**
	 * Whether a type annotation written ahead of a word of a type's name stands on a type
	 * (JLS 9.7.4): the last word, or a class around the one the name ends with, each
	 * class from there to it an inner class. Ahead of a package's name, or of a class
	 * only scoping a static member, it stands on none. A name not found is not refused
	 * here.
	 */
	boolean admissible(String name, int word, Unit unit, List<JavaClass> all) {
		int steps = name.split("\\.").length - 1 - word;
		if (steps <= 0) {
			return true;
		}
		JavaClass type;
		try {
			type = find(name, null, unit, 0);
			for (int i = 0; type == null && i < all.size(); i++) {
				type = find(name, all.get(i), unit, 0);
			}
		}
		catch (LispReadException | JavaClass.Absent ex) {
			return true;
		}
		if (type == null) {
			return true;
		}
		for (int step = 0; step < steps; step++) {
			if (!type.hasOuterInstance() || type.isLocal) {
				return false;
			}
			type = java.util.Objects.requireNonNull(type.outer);
		}
		return true;
	}

	/**
	 * Records what a static import names: a type and its static members by a simple name,
	 * or all of them on demand. A compiler refuses a type that is not there, and a member
	 * that is no static one of it: in a file of the program, once the type's declaration
	 * is whole; a library's file is not asked, a compiler having taken it.
	 */
	private void importStatic(Import imported, Unit unit) {
		String name = imported.name();
		int dot = name.lastIndexOf('.');
		String typeName = imported.onDemand() ? name : name.substring(0, Math.max(dot, 0));
		int at = imported.pos() + "import static ".length();
		if (typeName.isEmpty()) {
			throw error(unit.source(), at, "cannot find symbol: class " + name);
		}
		JavaClass type = find(typeName, null, unit, at);
		if (type == null) {
			String junit = junitMissing(typeName, unit);
			throw error(unit.source(), at, junit != null ? junit
					: "cannot find symbol: class " + typeName.substring(typeName.lastIndexOf('.') + 1));
		}
		accessible(type, unit, at);
		if (imported.onDemand()) {
			unit.staticOnDemand().add(type);
			return;
		}
		String member = name.substring(dot + 1);
		unit.statics().computeIfAbsent(member, key -> new ArrayList<>()).add(type);
		if (unit.source().partial()) {
			return;
		}
		Runnable check = () -> {
			if (!hasStatic(type, member, new HashSet<>()) && member(type, member, new HashSet<>()) == null) {
				throw error(unit.source(), imported.pos(), "cannot find symbol: static " + member);
			}
		};
		if (this.completing == 0) {
			check.run();
		}
		else {
			this.waiting.add(check);
		}
	}

	/** Whether a class has a static field or method of a name, its own or inherited. */
	static boolean hasStatic(JavaClass type, String name, Set<JavaClass> seen) {
		if (!seen.add(type)) {
			return false;
		}
		if (type.fields.stream().anyMatch(field -> field.isStatic && field.name.equals(name))
				|| type.methods.stream().anyMatch(method -> method.isStatic && method.name.equals(name))) {
			return true;
		}
		if (type.superclass != null && hasStatic(type.superclass, name, seen)) {
			return true;
		}
		for (JavaClass implemented : type.interfaces) {
			if (hasStatic(implemented, name, seen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The package of a class of the program or of the library's Java source, or
	 * {@code null} for the unnamed package and for a class whose values are Lisp values.
	 */
	@Nullable String packageOf(JavaClass declaration) {
		Unit unit = declaration.intrinsic ? null : this.units.get(declaration.topLevel());
		return unit == null ? null : unit.packageName();
	}

	/**
	 * Refuses a class of the program that is not {@code public} where it is named from
	 * another package, as a compiler does.
	 */
	void accessible(JavaClass type, Unit from, int pos) {
		for (JavaClass around = type; around != null && !around.isLocal; around = around.outer) {
			if (around.intrinsic || around.source == null || around.source.library()
					|| java.util.Objects.equals(packageOf(around), from.packageName())) {
				return;
			}
			ClassDeclaration declaration = around.declaration;
			java.util.Set<String> modifiers = declaration == null ? Set.of("public") : declaration.modifiers();
			boolean inInterface = around.outer != null && around.outer.isInterface;
			if (!modifiers.contains("public") && !modifiers.contains("protected") && !inInterface) {
				throw error(from.source(), pos,
						around.simpleName() + " is not public in "
								+ (around.outer == null ? packageOf(around) : around.outer.simpleName())
								+ "; cannot be accessed from outside package");
			}
		}
	}

	/**
	 * Adds a local or an anonymous class, once: a lowering made again finds the class its
	 * first pass made.
	 * @param declaration the class's declaration
	 * @param outer the class whose body it stands in
	 * @param isStatic whether it stands in a static context
	 * @param extended the type an anonymous class is made of, or {@code null} for a local
	 * class
	 * @return the class
	 */
	JavaClass local(ClassDeclaration declaration, JavaClass outer, boolean isStatic, @Nullable ClassType extendedType) {
		JavaClass extended = extendedType == null ? null : extendedType.declaration();
		JavaClass known = this.locals.get(declaration);
		if (known != null) {
			return known;
		}
		Unit unit = unit(outer);
		String name = outer.binaryName + "$" + number(declaration, outer) + declaration.name();
		List<JavaClass> all = new ArrayList<>();
		JavaClass made = create(declaration, name, name, outer, isStatic || declaration.kind() != JavaSyntax.Kind.CLASS,
				unit, all);
		made.isLocal = true;
		made.scope = this.methodParameters;
		this.locals.put(declaration, made);
		if (extended != null) {
			if (extended.isFinal && !extended.isEnum) {
				throw error(unit.source(), declaration.pos(), "cannot inherit from final " + extended.simpleName());
			}
			if (extended.isSealed && !extended.isEnum) {
				throw error(unit.source(), declaration.pos(), "local classes must not extend sealed classes");
			}
			if (extended.isInterface) {
				made.interfaces.add(extended);
			}
			else {
				made.superclass = extended.intrinsic ? null : extended;
				made.intrinsicSuperclass = extended.intrinsic && extended.isAbstract ? extended : null;
			}
			if (extendedType != null && !extendedType.arguments().isEmpty()) {
				made.parameterized.put(extended, extendedType);
			}
		}
		complete(all, unit);
		return made;
	}

	/**
	 * The number in a local or an anonymous class's name: the classes of one simple name
	 * declared in a class's body are counted in the order they are written, as a compiler
	 * names them.
	 */
	private int number(ClassDeclaration declaration, JavaClass outer) {
		Integer known = this.numbers.get(declaration);
		if (known == null && outer.declaration != null && this.numbered.add(outer.declaration)) {
			ClassDeclaration around = outer.declaration;
			List<Object> members = new ArrayList<>();
			members.addAll(around.constants());
			members.addAll(around.fields());
			members.addAll(around.methods());
			members.addAll(around.initializers());
			members.sort(java.util.Comparator.comparingInt(member -> switch (member) {
				case JavaSyntax.EnumConstant constant -> constant.pos();
				case JavaSyntax.Field field -> field.pos();
				case JavaSyntax.Method method -> method.pos();
				case JavaSyntax.Initializer block -> block.pos();
				default -> 0;
			}));
			List<ClassDeclaration> found = new ArrayList<>();
			for (Object member : members) {
				declared(member, found);
			}
			for (ClassDeclaration local : found) {
				this.numbers.put(local,
						this.localNumbers.merge(outer.binaryName + "$" + local.name(), 1, Integer::sum));
			}
			known = this.numbers.get(declaration);
		}
		return known != null ? known
				: this.localNumbers.merge(outer.binaryName + "$" + declaration.name(), 1, Integer::sum);
	}

	/**
	 * The local and anonymous classes declared in a part of a class's body, in the order
	 * written; the ones declared inside them are theirs to count.
	 */
	private static void declared(@Nullable Object node, List<ClassDeclaration> into) {
		switch (node) {
			case null -> {
			}
			case List<?> nodes -> nodes.forEach(each -> declared(each, into));
			case JavaSyntax.EnumConstant constant -> {
				declared(constant.arguments(), into);
				declared(constant.body(), into);
			}
			case ClassDeclaration local -> into.add(local);
			case JavaSyntax.Field field -> declared(field.initializer(), into);
			case JavaSyntax.Method method -> declared(method.body(), into);
			case JavaSyntax.Initializer block -> declared(block.body(), into);
			case JavaSyntax.New creation -> {
				declared(creation.outer(), into);
				declared(creation.arguments(), into);
				declared(creation.body(), into);
			}
			case JavaSyntax.LocalClass local -> into.add(local.declaration());
			case JavaSyntax.Select select -> declared(select.target(), into);
			case JavaSyntax.QualifiedThis self -> {
			}
			case JavaSyntax.Call call -> {
				declared(call.target(), into);
				declared(call.arguments(), into);
			}
			case JavaSyntax.ConstructorCall call -> declared(call.arguments(), into);
			case JavaSyntax.NewArray creation -> {
				declared(creation.lengths(), into);
				declared(creation.initializer(), into);
			}
			case JavaSyntax.ArrayInitializer initializer -> declared(initializer.elements(), into);
			case JavaSyntax.Index index -> {
				declared(index.array(), into);
				declared(index.index(), into);
			}
			case JavaSyntax.Unary unary -> declared(unary.operand(), into);
			case JavaSyntax.Binary binary -> {
				declared(binary.left(), into);
				declared(binary.right(), into);
			}
			case JavaSyntax.Assign assignment -> {
				declared(assignment.target(), into);
				declared(assignment.value(), into);
			}
			case JavaSyntax.Conditional conditional -> {
				declared(conditional.condition(), into);
				declared(conditional.whenTrue(), into);
				declared(conditional.whenFalse(), into);
			}
			case JavaSyntax.Cast cast -> declared(cast.operand(), into);
			case JavaSyntax.InstanceOf test -> declared(test.operand(), into);
			case JavaSyntax.Lambda lambda -> {
				declared(lambda.expression(), into);
				declared(lambda.block(), into);
			}
			case JavaSyntax.MethodReference reference -> declared(reference.target(), into);
			case JavaSyntax.Switch block -> {
				declared(block.selector(), into);
				for (JavaSyntax.SwitchCase written : block.cases()) {
					declared(written.constants(), into);
					declared(written.guard(), into);
					declared(written.body(), into);
					declared(written.value(), into);
				}
			}
			case JavaSyntax.LocalVariable variable ->
				variable.declarators().forEach(declarator -> declared(declarator.initializer(), into));
			case JavaSyntax.ExpressionStatement statement -> declared(statement.expression(), into);
			case JavaSyntax.If branch -> {
				declared(branch.condition(), into);
				declared(branch.then(), into);
				declared(branch.otherwise(), into);
			}
			case JavaSyntax.While loop -> {
				declared(loop.condition(), into);
				declared(loop.body(), into);
			}
			case JavaSyntax.DoWhile loop -> {
				declared(loop.body(), into);
				declared(loop.condition(), into);
			}
			case JavaSyntax.For loop -> {
				declared(loop.initializers(), into);
				declared(loop.condition(), into);
				declared(loop.updates(), into);
				declared(loop.body(), into);
			}
			case JavaSyntax.ForEach loop -> {
				declared(loop.iterable(), into);
				declared(loop.body(), into);
			}
			case JavaSyntax.Return exit -> declared(exit.value(), into);
			case JavaSyntax.Yield exit -> declared(exit.value(), into);
			case JavaSyntax.Throw exit -> declared(exit.value(), into);
			case JavaSyntax.Labeled labeled -> declared(labeled.body(), into);
			case JavaSyntax.Block block -> declared(block.statements(), into);
			case JavaSyntax.Try attempt -> {
				declared(attempt.body(), into);
				attempt.catches().forEach(clause -> declared(clause.body(), into));
				declared(attempt.finallyBlock(), into);
			}
			case JavaSyntax.Synchronized held -> {
				declared(held.lock(), into);
				declared(held.body(), into);
			}
			case JavaSyntax.Echo echo -> declared(echo.value(), into);
			default -> {
			}
		}
	}

	private JavaClass create(ClassDeclaration declaration, String name, String binaryName, @Nullable JavaClass outer,
			boolean isStatic, Unit unit, List<JavaClass> all) {
		Set<String> modifiers = declaration.modifiers();
		boolean isInterface = declaration.isInterface();
		JavaClass made = new JavaClass(name, binaryName, declaration.name(), isInterface,
				isInterface || modifiers.contains("abstract"), false, declaration, unit.source());
		for (JavaSyntax.TypeParameter parameter : declaration.typeParameters()) {
			TypeParameter declared = new TypeParameter(parameter.name(), false, false);
			declared.owner = made;
			made.typeParameters.add(declared);
		}
		made.outer = outer;
		made.isStatic = isStatic;
		made.isEnum = declaration.kind() == JavaSyntax.Kind.ENUM;
		made.isRecord = declaration.kind() == JavaSyntax.Kind.RECORD;
		made.isAnnotation = modifiers.contains(JavaSyntax.ANNOTATION_TYPE);
		made.isSealed = modifiers.contains("sealed");
		made.isFinal = modifiers.contains("final") || made.isRecord
				|| (made.isEnum && declaration.constants().stream().noneMatch(constant -> constant.body() != null));
		this.units.put(made, unit);
		register(made);
		all.add(made);
		for (ClassDeclaration nested : declaration.nested()) {
			if (made.members.containsKey(nested.name())) {
				throw error(unit.source(), nested.pos(), "duplicate class: " + nested.name());
			}
			boolean nestedStatic = nested.modifiers().contains("static") || nested.kind() != JavaSyntax.Kind.CLASS
					|| isInterface;
			made.members.put(nested.name(), create(nested, name + "." + nested.name(), binaryName + "$" + nested.name(),
					made, nestedStatic, unit, all));
		}
		return made;
	}

	/**
	 * What each new class extends, what a sealed one permits, then the members; then
	 * whether each type argument their declarations wrote is within its parameter's
	 * bounds, which the declarations set. A declaration read while another one is being
	 * completed hands its checks to that one: a class it names may be the one whose
	 * supertypes are being read ({@code LocalDate} implementing {@code ChronoLocalDate},
	 * whose members name a class whose supertype is
	 * {@code ChronoZonedDateTime<LocalDate>}).
	 */
	private void complete(List<JavaClass> all, Unit unit) {
		List<Runnable> outer = this.boundChecks;
		this.boundChecks = new ArrayList<>();
		this.completing++;
		try {
			declareMembers(all, unit);
			if (outer != null) {
				outer.addAll(this.boundChecks);
			}
			else {
				for (Runnable check : this.boundChecks) {
					check.run();
				}
			}
		}
		finally {
			this.boundChecks = outer;
			this.completing--;
		}
		if (this.completing == 0) {
			List<Runnable> checks = List.copyOf(this.waiting);
			this.waiting.clear();
			checks.forEach(Runnable::run);
		}
	}

	/** How many declarations are being completed, one inside the other. */
	private int completing;

	/**
	 * The checks that wait for the outermost declaration, made once it is: a class's
	 * against the {@code permits} clause of a sealed type above it that was still being
	 * declared (the clause names the class, whose file is read where the clause is,
	 * before the clause is whole), and a static import's of a member of a class still
	 * being declared (a member's type names the importing file's class, whose file is
	 * read before the members after it are there).
	 */
	private final List<Runnable> waiting = new ArrayList<>();

	private void declareMembers(List<JavaClass> all, Unit unit) {
		Source source = unit.source();
		for (JavaClass made : all) {
			bounds(made.typeParameters, declaration(made).typeParameters(), made, unit);
		}
		for (JavaClass made : all) {
			supertypes(made, unit);
		}
		for (JavaClass made : all) {
			for (JavaClass up = made.superclass; up != null; up = up.superclass) {
				if (up == made) {
					throw error(source, declaration(made).pos(), "cyclic inheritance involving " + made.name);
				}
			}
			List<JavaClass> above = new ArrayList<>(made.interfaces);
			if (made.superclass != null) {
				above.add(made.superclass);
			}
			for (JavaClass parent : above) {
				if (parent.isFinal && !(parent.isEnum && made.isLocal)) {
					throw error(source, declaration(made).pos(), "cannot inherit from final " + parent.simpleName());
				}
				if (!parent.isSealed || parent.isEnum) {
					continue;
				}
				if (made.isLocal) {
					// whatever the clause permits (JLS 8.1.4, 8.1.5)
					throw error(source, declaration(made).pos(), "local classes must not extend sealed classes");
				}
				if (declaration(parent).permits().isEmpty()) {
					if (unit(parent) != unit) {
						throw error(source, declaration(made).pos(),
								"class is not allowed to extend sealed class: " + parent.simpleName());
					}
					parent.permits.add(made);
				}
				else if (parent.permits.size() < declaration(parent).permits().size()) {
					this.waiting.add(() -> permitted(parent, made, source));
				}
				else {
					permitted(parent, made, source);
				}
				Set<String> modifiers = declaration(made).modifiers();
				if (!made.isFinal && !made.isSealed && !modifiers.contains("non-sealed")) {
					throw error(source, declaration(made).pos(), "sealed, non-sealed or final modifiers expected");
				}
			}
		}
		for (JavaClass made : all) {
			members(made, unit);
		}
	}

	private static void permitted(JavaClass parent, JavaClass made, Source source) {
		if (!parent.permits.contains(made)) {
			throw error(source, declaration(made).pos(), "class is not allowed to extend sealed class: "
					+ parent.simpleName() + " (as it is not listed in its 'permits' clause)");
		}
	}

	private static ClassDeclaration declaration(JavaClass made) {
		return java.util.Objects.requireNonNull(made.declaration);
	}

	/** The bounds of declared type parameters, each of which may name the others. */
	private void bounds(List<TypeParameter> parameters, List<JavaSyntax.TypeParameter> written,
			@Nullable JavaClass context, Unit unit) {
		for (int i = 0; i < written.size(); i++) {
			List<JavaType> bounds = new ArrayList<>();
			for (TypeName bound : written.get(i).bounds()) {
				JavaType type = type(bound, context, unit);
				if (!(type instanceof ClassType) && !(type instanceof Variable)) {
					throw error(unit.source(), bound.pos(), "unexpected type; required: class, found: " + type);
				}
				bounds.add(type);
			}
			parameters.get(i).bounds = List.copyOf(bounds);
		}
	}

	/** A type a class extends or implements: a class, with the arguments written. */
	private ClassType supertype(TypeName written, JavaClass made, Unit unit) {
		JavaType type = resolve(written, made.outer, made, unit);
		if (!(type instanceof ClassType named)) {
			throw error(unit.source(), written.pos(), "unexpected type; required: class, found: " + type);
		}
		// a class under one nobody has is one nobody has
		named.declaration().present();
		for (JavaType argument : named.arguments()) {
			if (argument instanceof Wildcard) {
				throw error(unit.source(), written.pos(),
						"unexpected type; required: class or interface without bounds, found: " + argument);
			}
		}
		if (!named.arguments().isEmpty()) {
			made.parameterized.put(named.declaration(), named);
		}
		return named;
	}

	private void supertypes(JavaClass made, Unit unit) {
		ClassDeclaration declaration = declaration(made);
		Source source = unit.source();
		TypeName extended = declaration.superclass();
		if (extended != null) {
			JavaClass superclass = supertype(extended, made, unit).declaration();
			if (superclass.isInterface) {
				throw error(source, extended.pos(), "no interface expected here");
			}
			if (superclass.intrinsic && !superclass.name.equals("java.lang.Object")) {
				// an abstract class whose values are Lisp values can have classes of
				// Java source under it: Number
				if (!superclass.isAbstract) {
					throw unsupported(source, extended.pos(), "a class extending " + superclass.name);
				}
				made.intrinsicSuperclass = superclass;
			}
			if (superclass.isEnum) {
				throw error(source, extended.pos(), "cannot inherit from final " + superclass.simpleName());
			}
			made.superclass = superclass.intrinsic ? null : superclass;
		}
		else if (made.isEnum) {
			made.superclass = library("java.lang.Enum");
			made.parameterized.put(made.superclass, new ClassType(made.superclass, List.of(made.self())));
		}
		else if (made.isRecord) {
			made.superclass = library("java.lang.Record");
		}
		for (TypeName implemented : declaration.interfaces()) {
			JavaClass type = supertype(implemented, made, unit).declaration();
			if (!type.isInterface) {
				throw error(source, implemented.pos(), "interface expected here");
			}
			if (type.intrinsic) {
				throw unsupported(source, implemented.pos(), "a class implementing " + type.name);
			}
			made.interfaces.add(type);
		}
		if (made.isAnnotation) {
			// JLS 9.6: the superinterface of every annotation interface
			made.interfaces.add(library("java.lang.annotation.Annotation"));
		}
		for (TypeName permitted : declaration.permits()) {
			if (!made.isSealed) {
				throw error(source, permitted.pos(), "invalid permits clause: " + made.simpleName() + " is not sealed");
			}
			made.permits.add(require(permitted.name(), permitted.pos(), made, unit).present());
		}
	}

	/**
	 * What a receiver parameter has to be (JLS 8.4): an instance method's names its
	 * class, an inner class's constructor's the class around it, each with its own type
	 * parameters. Nothing of it is a parameter: it is written for its annotations.
	 */
	private void receiver(JavaClass made, JavaSyntax.Method method, JavaSyntax.Receiver receiver, Unit unit) {
		Source source = unit.source();
		boolean constructor = method.name().equals("<init>");
		JavaClass owner = constructor ? made.outer : made;
		if (owner == null) {
			throw error(source, receiver.start(),
					"receiver parameter not applicable for constructor of top-level class");
		}
		if (constructor ? !made.hasOuterInstance() : method.modifiers().contains("static")) {
			throw error(source, receiver.at(), "non-static variable this cannot be referenced from a static context");
		}
		String enclosing = constructor ? "enclosing outer class type" : "enclosing class type";
		int own = owner.typeParameters.size();
		if (!(type(receiver.type(), made, unit) instanceof ClassType written) || written.declaration() != owner
				|| written.arguments().size() < own
				|| !written.arguments().subList(0, own).equals(owner.self().arguments().subList(0, own))) {
			throw error(source, receiver.type().pos(), "the receiver type does not match the " + enclosing);
		}
		String name = receiver.qualifier();
		JavaClass named = name == null ? null : require(name, receiver.start(), made, unit);
		if (named != null) {
			boolean around = false;
			for (JavaClass at = made; at != null && !around; at = at.outer) {
				around = at == named;
			}
			if (!around) {
				throw error(source, receiver.at(), "not an enclosing class: " + name);
			}
		}
		if (constructor ? named != owner : named != null && named != made) {
			throw error(source, receiver.at(), "the receiver name does not match the " + enclosing);
		}
	}

	private static TypeName simpleType(JavaClass made, int pos, int dimensions) {
		return new TypeName(pos, made.name, List.of(), false, dimensions);
	}

	private void members(JavaClass made, Unit unit) {
		ClassDeclaration declaration = declaration(made);
		Source source = unit.source();
		Set<String> fieldNames = new HashSet<>();
		List<JavaSyntax.Method> methods = new ArrayList<>(declaration.methods());
		ClassType self = made.self();
		// an enum's constants: static fields made once, by the constructor the arguments
		// pick, behind the name and the ordinal every constructor takes first
		int ordinal = 0;
		for (JavaSyntax.EnumConstant constant : declaration.constants()) {
			if (!fieldNames.add(constant.name())) {
				throw error(source, constant.pos(),
						"variable " + constant.name() + " is already defined in class " + made.simpleName());
			}
			JavaClass.Field field = new JavaClass.Field(made, constant.name(), self, true, true, null, null);
			List<JavaSyntax.Expression> arguments = new ArrayList<>();
			arguments.add(new JavaSyntax.StringLiteral(constant.pos(), constant.name()));
			arguments.add(new JavaSyntax.IntLiteral(constant.pos(), ordinal++, false));
			arguments.addAll(constant.arguments());
			field.initializer = new JavaSyntax.New(constant.pos(), simpleType(made, constant.pos(), 0), arguments,
					constant.body(), null, List.of());
			field.pos = constant.pos();
			field.isConstant = true;
			made.fields.add(field);
		}
		// a record's components: a private final field, an accessor and the canonical
		// constructor each, unless the record declares its own
		for (Parameter component : declaration.components()) {
			if (!fieldNames.add(component.name())) {
				throw error(source, component.pos(), "record component " + component.name() + " is already defined");
			}
			JavaClass.Field field = new JavaClass.Field(made, component.name(),
					type(java.util.Objects.requireNonNull(component.type()), made, unit), false, true, null, null);
			field.pos = component.pos();
			field.isPrivate = true;
			made.fields.add(field);
		}
		if (made.isRecord) {
			methods = recordMethods(made, declaration, methods, unit);
		}
		for (JavaSyntax.Field field : declaration.fields()) {
			if (!fieldNames.add(field.name())) {
				throw error(source, field.pos(),
						"variable " + field.name() + " is already defined in class " + made.simpleName());
			}
			boolean isStatic = field.modifiers().contains("static") || made.isInterface;
			if (made.isRecord && !isStatic) {
				throw error(source, field.pos(), "field declaration must be static");
			}
			// a session's variable declared with var has the type its first value has
			boolean inferred = this.session && declaration.implicit() && field.type().name().equals("var");
			JavaClass.Field declared = new JavaClass.Field(made, field.name(),
					inferred ? libraryType("java.lang.Object") : type(field.type(), made, unit), isStatic,
					field.modifiers().contains("final") || made.isInterface, field, null);
			declared.inferred = inferred;
			made.fields.add(declared);
		}
		if (this.session) {
			enumValuesField(made);
		}
		Set<String> signatures = new HashSet<>();
		boolean constructed = false;
		for (JavaSyntax.Method method : methods) {
			JavaSyntax.Receiver receiver = unit.tree().receivers().get(method.pos());
			if (receiver != null) {
				receiver(made, method, receiver, unit);
			}
			List<JavaType> parameters = new ArrayList<>();
			boolean isConstructor = method.name().equals("<init>");
			if (made.isEnum && isConstructor) {
				parameters.add(libraryType("java.lang.String"));
				parameters.add(Primitive.INT);
			}
			List<TypeParameter> typeParameters = new ArrayList<>();
			for (JavaSyntax.TypeParameter parameter : method.typeParameters()) {
				typeParameters.add(new TypeParameter(parameter.name(), true, false));
			}
			List<TypeParameter> around = this.methodParameters;
			this.methodParameters = typeParameters;
			JavaType returnType;
			List<JavaType> thrown = new ArrayList<>();
			try {
				bounds(typeParameters, method.typeParameters(), made, unit);
				for (Parameter parameter : method.parameters()) {
					parameters.add(type(java.util.Objects.requireNonNull(parameter.type()), made, unit));
				}
				TypeName returned = method.returnType();
				returnType = returned == null || returned.name().equals("void") ? Primitive.VOID
						: type(returned, made, unit);
				for (TypeName written : method.thrown()) {
					JavaType type = type(written, made, unit);
					// asked once the declarations are whole: the class thrown may be one
					// whose
					// superclass is being read (Throwable's members name java.io's
					// classes,
					// whose members throw an IOException under Exception)
					Runnable check = () -> {
						if (!isThrowable(type)) {
							throw error(source, written.pos(),
									"incompatible types: " + type + " cannot be converted to Throwable");
						}
					};
					if (this.boundChecks != null) {
						this.boundChecks.add(check);
					}
					else {
						check.run();
					}
					thrown.add(type);
				}
			}
			finally {
				this.methodParameters = around;
			}
			Set<String> modifiers = method.modifiers();
			boolean isStatic = modifiers.contains("static");
			JavaEmitter emitter = null;
			String nativeMember = null;
			String signature = null;
			if (modifiers.contains("native")) {
				StringBuilder member = new StringBuilder(made.name).append('.').append(method.name()).append('(');
				for (int i = 0; i < parameters.size(); i++) {
					member.append(i > 0 ? "," : "").append(parameters.get(i).erasure());
				}
				signature = member.append(')').toString();
			}
			JavaEmitter bound = signature == null ? null : this.wit.natives.get(signature);
			if (bound != null) {
				// a member of a @WitImport interface's generated binding
				emitter = bound;
			}
			else if (modifiers.contains("native") && source.dependency()) {
				throw unsupported(source, method.pos(), "a native method of a library");
			}
			else if (modifiers.contains("native") && !source.library()) {
				// a Common Lisp function, called with the program's values
				if (!isStatic || method.body() != null) {
					throw error(source, method.pos(), isStatic ? "native methods cannot have a body"
							: "a native method of a program is static: it is a Common Lisp function");
				}
				emitter = JavaForms.lispFunction(method.name(), parameters, returnType);
			}
			else if (signature != null) {
				nativeMember = signature;
				emitter = this.library.emitter(nativeMember);
				if (emitter == null || method.body() != null) {
					throw new IllegalStateException(signature + " is native and has no form, or has a body");
				}
			}
			boolean isAbstract = method.body() == null && emitter == null;
			if (made.isInterface) {
				boolean bodied = modifiers.contains("default") || isStatic || modifiers.contains("private");
				if (!isAbstract && !bodied && emitter == null) {
					throw error(source, method.pos(), "interface abstract methods cannot have body");
				}
				if (isAbstract && bodied) {
					throw error(source, method.pos(), "missing method body, or declare abstract");
				}
			}
			else if (modifiers.contains("default")) {
				throw error(source, method.pos(), "modifier default not allowed here");
			}
			if (isAbstract && !made.isInterface && !modifiers.contains("abstract")) {
				throw error(source, method.pos(), "missing method body, or declare abstract");
			}
			if (!isAbstract && modifiers.contains("abstract")) {
				throw error(source, method.pos(), "abstract methods cannot have a body");
			}
			if (isAbstract && made.isEnum) {
				// every constant of such an enum has a body of its own
				made.isAbstract = true;
			}
			if (isAbstract && !made.isAbstract) {
				throw error(source, method.pos(),
						made.simpleName() + " is not abstract and declares the abstract method " + method.name());
			}
			JavaClass.Method member = new JavaClass.Method(made, method.name(), parameters, returnType, isStatic,
					isAbstract, method, emitter, false);
			member.typeParameters = List.copyOf(typeParameters);
			member.thrown = List.copyOf(thrown);
			member.answersNull = nativeMember != null && this.library.answersNull(nativeMember);
			typeParameters.forEach(parameter -> parameter.method = member);
			if (made.isEnum && isConstructor) {
				member.hidden = 2;
				member.isPrivate = true;
			}
			if (!signatures.add(member.signature())) {
				throw error(source, method.pos(),
						"method " + member.signature() + " is already defined in class " + made.simpleName());
			}
			constructed |= member.isConstructor();
			if (made.isInterface && member.isObjectMethod()) {
				// every implementation has it already
				continue;
			}
			made.methods.add(member);
		}
		if (made.isLocal && declaration.name().isEmpty()) {
			// an anonymous class: its constructors are made where it is created, one for
			// each constructor of its superclass an instance creation picks
			return;
		}
		if (!made.isInterface && !constructed) {
			JavaClass.Method constructor = new JavaClass.Method(made, "<init>",
					made.isEnum ? List.of(libraryType("java.lang.String"), Primitive.INT) : List.of(), Primitive.VOID,
					false, false, null, null, false);
			constructor.hidden = made.isEnum ? 2 : 0;
			made.methods.add(constructor);
		}
	}

	/** Whether a type is {@code Throwable} or under it: what a {@code throws} names. */
	private boolean isThrowable(JavaType type) {
		return switch (type) {
			case ClassType named ->
				named.declaration().absent != null || named.declaration().isSubclassOf(library("java.lang.Throwable"));
			case Variable variable -> variable.parameter().bounds.stream().anyMatch(this::isThrowable);
			default -> false;
		};
	}

	/**
	 * The methods of a record: its own, the canonical constructor, the accessors and
	 * {@code toString}, {@code equals} and {@code hashCode} it does not declare.
	 */
	private List<JavaSyntax.Method> recordMethods(JavaClass made, ClassDeclaration declaration,
			List<JavaSyntax.Method> declared, Unit unit) {
		int at = declaration.pos();
		List<Parameter> components = declaration.components();
		List<JavaSyntax.Statement> stores = new ArrayList<>();
		for (Parameter component : components) {
			stores.add(new JavaSyntax.ExpressionStatement(at,
					new JavaSyntax.Assign(at, "=", new JavaSyntax.Select(at, new JavaSyntax.This(at), component.name()),
							new JavaSyntax.Name(at, component.name()))));
		}
		List<JavaType> componentTypes = new ArrayList<>();
		for (JavaClass.Field field : made.fields) {
			componentTypes.add(field.type);
		}
		List<JavaSyntax.Method> methods = new ArrayList<>();
		boolean canonical = false;
		Set<String> names = new HashSet<>();
		for (JavaSyntax.Method method : declared) {
			if (method.compact()) {
				if (canonical) {
					throw error(unit.source(), method.pos(), "constructor is already defined in the record");
				}
				List<JavaSyntax.Statement> body = new ArrayList<>(
						java.util.Objects.requireNonNull(method.body()).statements());
				body.addAll(stores);
				methods.add(new JavaSyntax.Method(method.pos(), method.modifiers(), null, "<init>", components,
						new JavaSyntax.Block(method.pos(), body)));
				canonical = true;
				continue;
			}
			if (method.name().equals("<init>")) {
				List<JavaType> parameters = new ArrayList<>();
				for (Parameter parameter : method.parameters()) {
					parameters.add(type(java.util.Objects.requireNonNull(parameter.type()), made, unit));
				}
				canonical |= parameters.equals(componentTypes);
			}
			else if (method.parameters().isEmpty()) {
				names.add(method.name());
			}
			else if (method.name().equals("equals") && method.parameters().size() == 1) {
				names.add("equals");
			}
			methods.add(method);
		}
		if (!canonical) {
			methods.add(new JavaSyntax.Method(at, Set.of("public"), null, "<init>", components,
					new JavaSyntax.Block(at, stores)));
		}
		for (Parameter component : components) {
			if (!names.contains(component.name())) {
				methods.add(new JavaSyntax.Method(at, Set.of("public"), component.type(), component.name(), List.of(),
						new JavaSyntax.Block(at, List.of(new JavaSyntax.Return(at,
								new JavaSyntax.Select(at, new JavaSyntax.This(at), component.name()))))));
			}
		}
		String simple = made.simpleName();
		// java.lang's classes by their qualified names: a record or a sibling of it may
		// be
		// named Boolean or String (a WIT variant's case), and its members read that first
		StringBuilder text = new StringBuilder();
		if (!names.contains("toString")) {
			text.append("public java.lang.String toString() { return \"").append(simple).append("[\"");
			for (int i = 0; i < components.size(); i++) {
				String name = components.get(i).name();
				text.append(" + \"").append(i > 0 ? ", " : "").append(name).append("=\" + this.").append(name);
			}
			text.append(" + \"]\"; }\n");
		}
		if (!names.contains("equals")) {
			text.append("public boolean equals(java.lang.Object $o) { return $o instanceof ")
				.append(simple)
				.append(components.isEmpty() ? "" : " $that");
			for (int i = 0; i < components.size(); i++) {
				String name = components.get(i).name();
				JavaType type = componentTypes.get(i);
				String mine = "this." + name;
				String theirs = "$that." + name;
				text.append(" && ");
				if (type.isFloating()) {
					// a float widens to the double of its value: one comparison for both
					text.append("java.lang.Double.compare(").append(mine).append(", ").append(theirs).append(") == 0");
				}
				else if (type.isPrimitive()) {
					text.append(mine).append(" == ").append(theirs);
				}
				else {
					text.append("(")
						.append(mine)
						.append(" == null ? ")
						.append(theirs)
						.append(" == null : ")
						.append(mine)
						.append(".equals(")
						.append(theirs)
						.append("))");
				}
			}
			text.append("; }\n");
		}
		if (!names.contains("hashCode")) {
			text.append("public int hashCode() { int $h = 0;");
			for (int i = 0; i < components.size(); i++) {
				String mine = "this." + components.get(i).name();
				JavaType type = componentTypes.get(i);
				String hash = type == Primitive.INT || type.isBelowInt() ? mine
						: type == Primitive.LONG ? "java.lang.Long.hashCode(" + mine + ")"
								: type == Primitive.FLOAT ? "rontolisp.internal.Support.floatHash(" + mine + ")"
										: type == Primitive.DOUBLE ? "java.lang.Double.hashCode(" + mine + ")"
												: type == Primitive.BOOLEAN ? "java.lang.Boolean.hashCode(" + mine + ")"
														: "(" + mine + " == null ? 0 : " + mine + ".hashCode())";
				text.append(" $h = 31 * $h + ").append(hash).append(";");
			}
			text.append(" return $h; }\n");
		}
		methods.addAll(JavaParser.synthetic(text.toString(), simple, JavaSyntax.Kind.RECORD, at));
		return methods;
	}

	/**
	 * Adds the array {@code values()} and {@code valueOf(String)} read, once. A session
	 * adds it where the enum is declared: a buffer that first names {@code values()}
	 * finds the enum's initialization done by an earlier one, so nothing would set a
	 * field added by then.
	 * @param made the enum
	 */
	void enumValuesField(JavaClass made) {
		if (!made.isEnum || made.declaration == null
				|| made.fields.stream().anyMatch(field -> field.name.equals("$VALUES"))) {
			return;
		}
		ClassDeclaration declaration = made.declaration;
		int at = declaration.pos();
		List<JavaSyntax.Expression> constants = new ArrayList<>();
		for (JavaSyntax.EnumConstant constant : declaration.constants()) {
			constants.add(new JavaSyntax.Name(at, constant.name()));
		}
		JavaClass.Field values = new JavaClass.Field(made, "$VALUES", new ArrayType(made.self()), true, true, null,
				null);
		values.initializer = new JavaSyntax.ArrayInitializer(at, constants);
		// set right after the constants: an initializer below them may ask for values()
		values.pos = declaration.constants().isEmpty() ? at
				: declaration.constants().get(declaration.constants().size() - 1).pos();
		values.isPrivate = true;
		values.frozen = true;
		made.fields.add(values);
	}

	/**
	 * Adds an enum's {@code values()} or {@code valueOf(String)}, and the array both
	 * read, where a program first names one: the second throws, and a program that does
	 * not name it holds no exception class for it.
	 * @param made the enum
	 * @param name {@code values} or {@code valueOf}
	 */
	void enumMethod(JavaClass made, String name) {
		// an enum may declare an overload of either name: valueOf(int)
		String implicit = name.equals("values") ? "values()" : "valueOf(String)";
		if (!made.isEnum || made.declaration == null
				|| made.methods.stream().anyMatch(method -> method.signature().equals(implicit))) {
			return;
		}
		ClassDeclaration declaration = made.declaration;
		Unit unit = unit(made);
		int at = declaration.pos();
		enumValuesField(made);
		String type = made.simpleName();
		String text = name.equals("values") ? "public static " + type + "[] values() { return $VALUES.clone(); }\n"
				: "public static " + type + " valueOf(String name) { for (" + type
						+ " constant : $VALUES) { if (constant.name().equals(name)) { return constant; } }"
						+ " throw new IllegalArgumentException(\"No enum constant " + made.name + ".\" + name); }\n";
		for (JavaSyntax.Method method : JavaParser.synthetic(text, type, JavaSyntax.Kind.ENUM, at)) {
			List<JavaType> parameters = new ArrayList<>();
			for (Parameter parameter : method.parameters()) {
				parameters.add(type(java.util.Objects.requireNonNull(parameter.type()), made, unit));
			}
			made.methods.add(new JavaClass.Method(made, method.name(), parameters,
					type(java.util.Objects.requireNonNull(method.returnType()), made, unit), true, false, method, null,
					false));
		}
		this.version++;
	}

	/** The member type of a name a class has: its own, or one it inherits. */
	private static @Nullable JavaClass member(JavaClass declaration, String name, Set<JavaClass> seen) {
		if (!seen.add(declaration)) {
			return null;
		}
		JavaClass found = declaration.members.get(name);
		if (found == null && declaration.superclass != null) {
			found = member(declaration.superclass, name, seen);
		}
		for (int i = 0; found == null && i < declaration.interfaces.size(); i++) {
			found = member(declaration.interfaces.get(i), name, seen);
		}
		return found;
	}

	/**
	 * The class a name written in a class's body means, or {@code null}: a local class in
	 * scope, the class itself or a member type of it or of a class around it, a class of
	 * the file, a single-type import, a class of the file's package, of
	 * {@code java.lang}, of a package imported on demand, of a module imported. A
	 * qualified name is a class and the member types under it.
	 * @param pos where the name is written: a name two packages imported alike have is
	 * refused there
	 */
	@Nullable JavaClass find(String name, @Nullable JavaClass context, Unit unit, int pos) {
		int dot = name.indexOf('.');
		if (dot >= 0) {
			JavaClass whole = qualified(name);
			if (whole != null) {
				return whole;
			}
			// the longest prefix that is a class, then member types
			for (int end = name.lastIndexOf('.'); end > 0; end = name.lastIndexOf('.', end - 1)) {
				String prefix = name.substring(0, end);
				JavaClass outer = prefix.indexOf('.') < 0 ? find(prefix, context, unit, pos) : qualified(prefix);
				if (outer == null) {
					continue;
				}
				for (String segment : name.substring(end + 1).split("\\.")) {
					outer = outer == null ? null : member(outer, segment, new HashSet<>());
				}
				return outer;
			}
			return null;
		}
		JavaClass local = this.localScope == null ? null : this.localScope.apply(name);
		if (local != null) {
			return local;
		}
		for (JavaClass around = context; around != null; around = around.outer) {
			if (around.simpleName().equals(name)) {
				return around;
			}
			JavaClass member = member(around, name, new HashSet<>());
			if (member != null) {
				return member;
			}
		}
		JavaClass declared = unit.declared().get(name);
		if (declared != null) {
			return declared;
		}
		Import imported = unit.imports().get(name);
		if (imported != null) {
			return canonical(imported.name());
		}
		for (JavaClass type : unit.statics().getOrDefault(name, List.of())) {
			JavaClass member = member(type, name, new HashSet<>());
			if (member != null) {
				return member;
			}
		}
		JavaClass sibling = qualified(unit.packageName() == null ? name : unit.packageName() + "." + name);
		if (sibling != null) {
			return sibling;
		}
		JavaClass implicit = qualified("java.lang." + name);
		if (implicit != null) {
			return implicit;
		}
		JavaClass onDemand = imported(name, unit.onDemand(), unit, pos);
		if (onDemand != null) {
			return onDemand;
		}
		JavaClass module = imported(name, unit.modules(), unit, pos);
		if (module != null) {
			return module;
		}
		for (JavaClass type : unit.staticOnDemand()) {
			JavaClass member = member(type, name, new HashSet<>());
			if (member != null) {
				return member;
			}
		}
		return null;
	}

	/**
	 * The class a single-type import names by its canonical name: a top-level class, or
	 * the member types under the longest prefix that is one. A member class is found once
	 * its file is read, which the declarations of the importing file's member classes ask
	 * for before the file's imports are looked for ({@link #declare}).
	 */
	private @Nullable JavaClass canonical(String name) {
		JavaClass whole = qualified(name);
		if (whole != null) {
			return whole;
		}
		for (int end = name.lastIndexOf('.'); end > 0; end = name.lastIndexOf('.', end - 1)) {
			JavaClass outer = qualified(name.substring(0, end));
			if (outer != null) {
				for (String segment : name.substring(end + 1).split("\\.")) {
					outer = outer == null ? null : member(outer, segment, new HashSet<>());
				}
				return outer;
			}
		}
		return null;
	}

	/**
	 * The class of a simple name in the packages one kind of import brings, or
	 * {@code null}; two of them are the error a compiler reports ({@code java.util.Date}
	 * and {@code java.sql.Date}).
	 */
	private @Nullable JavaClass imported(String name, List<String> packages, Unit unit, int pos) {
		JavaClass found = null;
		for (String packageName : packages) {
			JavaClass candidate = qualified(packageName + "." + name);
			if (candidate != null && found != null && candidate != found) {
				throw error(unit.source(), pos, "reference to " + name + " is ambiguous");
			}
			if (candidate != null) {
				found = candidate;
			}
		}
		return found;
	}

	/**
	 * Whether a qualified name no class has, read as a value, names a class of a JDK's
	 * packages the library lacks: it starts with one of their packages and no name before
	 * its last is a class ({@code java.util.ResourceBundle} in
	 * {@code java.util.ResourceBundle.getBundle("m")}), so it is refused as the class it
	 * names and not as an unknown variable {@code java}.
	 */
	boolean namesPlatformClass(String name, @Nullable JavaClass context, Unit unit, int pos) {
		if (!isPlatform(name)) {
			return false;
		}
		for (int end = name.lastIndexOf('.'); end > 0; end = name.lastIndexOf('.', end - 1)) {
			String prefix = name.substring(0, end);
			if ((prefix.indexOf('.') < 0 ? find(prefix, context, unit, pos) : qualified(prefix)) != null) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@link #find}, or the error a compiler reports; in a file of a library the program
	 * uses, a class nobody has is a name ({@link #absent}), its declarations' and its
	 * bodies' alike.
	 */
	JavaClass require(String name, int pos, @Nullable JavaClass context, Unit unit) {
		if (!unit.source().dependency()) {
			return required(name, pos, context, unit);
		}
		Import imported = unit.imports().get(name);
		try {
			return required(name, pos, context, unit);
		}
		catch (LispReadException ex) {
			String refusedName = this.refusedNames.get(ex);
			return absent(refusedName != null ? refusedName : imported != null ? imported.name() : name, ex);
		}
	}

	private JavaClass required(String name, int pos, @Nullable JavaClass context, Unit unit) {
		JavaClass found = find(name, context, unit, pos);
		if (found != null) {
			accessible(found, unit, pos);
			return found;
		}
		String qualified = unit.imports().get(name) == null && JAVA_LANG.contains(name) ? "java.lang." + name
				: missingName(name, unit);
		if (isPlatform(qualified)) {
			throw missing(unit.source(), pos, "the class " + qualified, qualified);
		}
		String junit = junitMissing(qualified, unit);
		throw error(unit.source(), pos, junit != null ? junit : "cannot find symbol: class " + name);
	}

	/**
	 * What is said of a simple name read as a value that is no variable: a compiler's
	 * words, or, where the file imports a class of a JDK's packages under the name, that
	 * the library does not have the class.
	 */
	LispReadException unknown(String name, int pos, Unit unit) {
		Import imported = unit.imports().get(name);
		if (imported != null && isPlatform(imported.name())) {
			return missing(unit.source(), pos, "the class " + imported.name(), imported.name());
		}
		if (imported == null && JAVA_LANG.contains(name)) {
			return missing(unit.source(), pos, "the class java.lang." + name, "java.lang." + name);
		}
		String junit = imported == null ? null : junitMissing(imported.name(), unit);
		if (junit == null && imported != null && unit.source().dependency()) {
			// a class a library imports that no root has
			return new Missing("cannot find symbol: class " + imported.name(),
					SourceLocation.at(unit.source().file(), pos, unit.source().text()), NO_CLASS, imported.name());
		}
		return error(unit.source(), pos, junit != null ? junit : "cannot find symbol: variable " + name);
	}

	/** The class of a qualified name: one declared already, or one of the library. */
	@Nullable JavaClass qualified(String name) {
		JavaClass known = this.classes.get(name);
		if (known != null || this.missing.contains(name)) {
			return known == null ? null : held(known);
		}
		this.declaringLibrary++;
		JavaClass intrinsic;
		try {
			intrinsic = this.library.create(name, this);
		}
		finally {
			this.declaringLibrary--;
		}
		if (intrinsic != null) {
			return held(intrinsic);
		}
		String path = JavaParser.LIBRARY + name.replace('.', '/') + ".java";
		String text;
		try (InputStream in = JavaClasses.class.getResourceAsStream(path)) {
			if (in == null) {
				JavaClass found = programClass(name);
				if (found == null) {
					this.missing.add(name);
				}
				return found;
			}
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String simpleName = name.substring(name.lastIndexOf('.') + 1);
		// a class of the library names no local class of the program
		Function<String, @Nullable JavaClass> scope = this.localScope;
		List<TypeParameter> parameters = this.methodParameters;
		this.localScope = null;
		this.methodParameters = List.of();
		this.declaringLibrary++;
		try {
			declare(JavaParser.parse(text, path, simpleName), new Source(path, text, JavaClass.Origin.LIBRARY));
		}
		finally {
			this.declaringLibrary--;
			this.localScope = scope;
			this.methodParameters = parameters;
		}
		return java.util.Objects.requireNonNull(this.classes.get(name), () -> path + " does not declare " + name);
	}

	/** How many declarations of library classes are being read, one inside the other. */
	private int declaringLibrary;

	/**
	 * Notes that the program can hold a value of an intrinsic class: a declaration of the
	 * program or a body being lowered names it. A class of the library that only names it
	 * in the type of a member -- {@code Stream<Double> boxed()}, which every program with
	 * a collection reads -- does not, so that such a program's interface tests and
	 * dispatchers keep to the values it has.
	 */
	private JavaClass held(JavaClass named) {
		if (named.intrinsic && !named.held && this.declaringLibrary == 0) {
			named.held = true;
			this.version++;
		}
		return named;
	}

	/**
	 * The class of a qualified name from the file of the program's source tree that the
	 * name spells -- {@code app.util.Strings} in {@code app/util/Strings.java} under the
	 * root -- or {@code null}: as a source launcher finds the classes of a program of
	 * several files, where they are first named. The file's other classes join with it. A
	 * class no file of the tree declares is looked for the same way under the roots of
	 * the program's source path, which hold the libraries it uses.
	 */
	private @Nullable JavaClass programClass(String name) {
		if (isPlatform(name)) {
			return null;
		}
		LispReadException refused = this.refused.get(name);
		if (refused != null) {
			throw refused;
		}
		String path = name.replace('.', '/') + ".java";
		JavaFiles.Source found = this.entry == null ? this.files.tree(path)
				: this.files.find(this.entry, this.root + path);
		boolean dependency = found == null;
		if (dependency) {
			found = this.files.library(path);
		}
		if (found == null) {
			found = declaring(name);
		}
		if (found == null) {
			return null;
		}
		int dot = name.lastIndexOf('.');
		String simpleName = name.substring(dot + 1);
		Source source = new Source(found.path(), found.text(),
				dependency ? JavaClass.Origin.DEPENDENCY : JavaClass.Origin.PROGRAM);
		Function<String, @Nullable JavaClass> scope = this.localScope;
		List<TypeParameter> parameters = this.methodParameters;
		this.localScope = null;
		this.methodParameters = List.of();
		if (dependency) {
			// its declarations name classes as a library's do, and may name one nobody
			// has
			this.declaringLibrary++;
		}
		try {
			CompilationUnit tree = JavaParser.parse(found.text(), found.path(), simpleName);
			if (!java.util.Objects.equals(tree.packageName(), dot < 0 ? null : name.substring(0, dot))
					|| tree.classes().stream().noneMatch(declared -> declared.name().equals(simpleName))) {
				throw error(source, 0, "bad source file: " + found.path() + ": file does not contain class " + name);
			}
			declare(tree, source);
		}
		catch (LispReadException | JavaClass.Absent ex) {
			if (!dependency) {
				throw ex;
			}
			LispReadException refusal = ex instanceof JavaClass.Absent absent
					? java.util.Objects.requireNonNull(absent.named.absent) : (LispReadException) ex;
			// what a library's body naming the class throws where it runs
			Missing reason = new Missing(refusal.reason(), refusal.location(), NO_CLASS, name);
			withdraw(source, name, reason);
			throw reason;
		}
		finally {
			if (dependency) {
				this.declaringLibrary--;
			}
			this.localScope = scope;
			this.methodParameters = parameters;
		}
		return this.classes.get(name);
	}

	/**
	 * The octets of a resource of the program's source tree, or {@code null}: what the
	 * class loader of a source launcher holds in memory, which finds a name as it is
	 * written -- no {@code .}, {@code ..} or empty segment is resolved, so a name holding
	 * one is none, but for the {@code /} a directory's name may end in. A directory's
	 * octets are its listing ({@link #listing}); the top of the tree is none. A program
	 * given with {@code -e} has no tree.
	 * @param name the name below the top of the tree, with no leading {@code /}
	 */
	byte @Nullable [] treeResource(String name) {
		return this.treeResources.computeIfAbsent(name, key -> {
			String[] segments = key.split("/", -1);
			for (int i = 0; i < segments.length; i++) {
				String segment = segments[i];
				if ((segment.isEmpty() && (i == 0 || i < segments.length - 1)) || segment.equals(".")
						|| segment.equals("..")) {
					return Optional.empty();
				}
			}
			String entry = this.entry;
			byte[] file = key.endsWith("/") ? null
					: entry == null ? this.files.treeResource(key) : this.files.findResource(entry, this.root + key);
			if (file != null) {
				return Optional.of(file);
			}
			String directory = key.endsWith("/") ? key : key + "/";
			List<String> entries = entry == null ? this.files.treeListing(directory)
					: this.files.findListing(entry, this.root + directory);
			if (entries == null) {
				return Optional.empty();
			}
			this.treeDirectories.add(key);
			return Optional.of(listing(entries));
		}).orElse(null);
	}

	/**
	 * Where a file or directory the compiler reads as a resource may be, as the host
	 * names it, in the order a loader looks: the program's source tree where the loader
	 * searches it (the working directory for a program that has none), then each root of
	 * the source path.
	 * @param name the name below a root, resolved: no {@code .} or {@code ..}
	 * @param tree whether the loader searches the program's source tree
	 */
	List<String> resourcePaths(String name, boolean tree) {
		List<String> paths = new ArrayList<>();
		String own = !tree ? null
				: this.entry == null ? this.files.treePath(name) : this.files.findPath(this.entry, this.root + name);
		if (own != null) {
			paths.add(own);
		}
		for (int root = 0; root < this.files.libraryRoots(); root++) {
			paths.add(this.files.libraryPath(root, name));
		}
		return paths;
	}

	/** The names {@link #treeResource} found a directory of. */
	private final Set<String> treeDirectories = new HashSet<>();

	/**
	 * What a directory answers as a resource: the names of its entries, each followed by
	 * a line end, a directory's with no {@code /}, in the order of their characters, as
	 * UTF-8. The launcher sorts them as the default locale's collation does, which
	 * depends on the host that runs the program.
	 * @param entries the entries, as a listing names them
	 */
	static byte[] listing(List<String> entries) {
		List<String> names = new ArrayList<>();
		for (String entry : entries) {
			names.add(entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry);
		}
		names.sort(null);
		StringBuilder text = new StringBuilder();
		for (String name : names) {
			text.append(name).append('\n');
		}
		return text.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A resource found, with where it was found, for its URL.
	 *
	 * @param kind 0 the source tree, 1 a directory of the source path, 2 a jar
	 * @param place the absolute path of the tree's top or of the root
	 * @param name the name below it: as asked in the tree and a jar, resolved in a
	 * directory
	 * @param octets its octets
	 */
	record Located(int kind, String place, String name, byte[] octets) {
	}

	/** Where the top of the program's source tree is, or the empty string for none. */
	private String treeTop() {
		String top = this.files.treeTop(this.entry, this.root);
		return top == null ? "" : top;
	}

	/**
	 * The top-level classes of a further file of a run of tests, in the order declared: a
	 * file of the program's source tree, named as a command line names it, whose path
	 * below the top of the tree spells its package and its class's name, as javac's
	 * source path requires of a file it finds.
	 * @param file the file
	 * @param program the file the program starts at, where a file that is not of its tree
	 * is refused
	 * @return the classes, none where the file is the one the program starts at
	 * @throws LispReadException where the file is not of the tree, or declares no class
	 * of its path's name in its path's package
	 */
	List<JavaClass> treeFile(String file, Source program) {
		String top = treeTop().replace('\\', '/');
		String absolute = this.files.absolute(file).replace('\\', '/');
		if (this.entry != null && absolute.equals(this.files.absolute(this.entry).replace('\\', '/'))) {
			return List.of();
		}
		String prefix = top.endsWith("/") ? top : top + "/";
		if (top.isEmpty() || !absolute.startsWith(prefix) || !absolute.endsWith(".java")) {
			throw error(program, 0, file + " is not a file of the source tree " + top
					+ ": the files of one run of tests are of the tree of the first");
		}
		String name = absolute.substring(prefix.length(), absolute.length() - ".java".length()).replace('/', '.');
		JavaClass named = qualified(name);
		if (named == null || named.source == null || named.source.library()) {
			throw error(program, 0, file + " declares no class " + name + ": a file of the source tree declares the"
					+ " class its path names");
		}
		Unit unit = unit(named);
		String prefixOfPackage = unit.packageName() == null ? "" : unit.packageName() + ".";
		List<JavaClass> declared = new ArrayList<>();
		for (ClassDeclaration each : unit.tree().classes()) {
			JavaClass found = this.classes.get(prefixOfPackage + each.name());
			if (found != null) {
				declared.add(found);
			}
		}
		return declared;
	}

	/**
	 * Every place a loader finds a resource in, the source tree first where the loader
	 * searches it, then the roots of the source path in order.
	 * @param name the name, with no leading {@code /}
	 * @param tree whether the loader searches the program's source tree
	 * @param first whether the first place is enough
	 */
	List<Located> located(String name, boolean tree, boolean first) {
		return this.locatedResources.computeIfAbsent((tree ? "T" : "S") + (first ? "1" : "*") + name,
				key -> locate(name, tree, first));
	}

	private final Map<String, List<Located>> locatedResources = new HashMap<>();

	private List<Located> locate(String name, boolean tree, boolean first) {
		List<Located> found = new ArrayList<>();
		byte[] own = tree ? treeResource(name) : null;
		if (own != null) {
			// the tree's directory is named with its slash
			found.add(new Located(0, treeTop(),
					this.treeDirectories.contains(name) && !name.endsWith("/") ? name + "/" : name, own));
		}
		// after a place's own file, the index of its annotated classes
		Indexed index = indexed(name);
		if (tree && index != null && !index.tree().isEmpty()) {
			found.add(new Located(0, treeTop(), name, index(index.tree())));
		}
		for (int root = 0; root < this.files.libraryRoots() && !(first && !found.isEmpty()); root++) {
			Located located = this.files.libraryArchive(root) ? inArchive(root, name) : inDirectory(root, name);
			if (located != null) {
				found.add(located);
			}
			if (index != null && !index.roots().get(root).isEmpty()) {
				found.add(new Located(this.files.libraryArchive(root) ? 2 : 1, this.files.libraryTop(root), name,
						index(index.roots().get(root))));
			}
		}
		return List.copyOf(first && found.size() > 1 ? found.subList(0, 1) : found);
	}

	/**
	 * A resource of a jar of the source path: the entry of the name as it is, a file's
	 * or, where the jar holds the entry, a directory's, which reads as empty.
	 */
	private @Nullable Located inArchive(int root, String name) {
		if (name.isEmpty()) {
			return null;
		}
		byte[] octets = name.endsWith("/") ? null : this.files.libraryResource(root, name);
		if (octets == null && this.files.libraryEntry(root, name.endsWith("/") ? name : name + "/")) {
			octets = new byte[0];
		}
		return octets == null ? null : new Located(2, this.files.libraryTop(root), name, octets);
	}

	/**
	 * A resource of a directory of the source path: the name's {@code .} and {@code ..}
	 * resolved, a file's or a directory's listing, the root itself the empty name. Its
	 * URL keeps the slash the name ends in, or that a last {@code .} or {@code ..}
	 * leaves.
	 */
	private @Nullable Located inDirectory(int root, String name) {
		String key = JavaFiles.below(name);
		if (key == null) {
			return null;
		}
		// a path ending in a slash asks for a directory: a last '.' after a file is no
		// path
		boolean directory = key.endsWith("/");
		if (directory) {
			key = key.substring(0, key.length() - 1);
		}
		String named = !key.isEmpty() && (name.endsWith("/") || name.endsWith("/.") || name.endsWith("/..")) ? key + "/"
				: key;
		byte[] octets = key.isEmpty() || directory ? null : this.files.libraryResource(root, key);
		if (octets == null) {
			List<String> entries = this.files.libraryListing(root, key.isEmpty() ? "" : key + "/");
			octets = entries == null ? null : listing(entries);
		}
		return octets == null ? null : new Located(1, this.files.libraryTop(root), named, octets);
	}

	/** The resources looked for, by name: a pass lowered again asks again. */
	private final Map<String, Optional<byte[]>> treeResources = new HashMap<>();

	/**
	 * The files a resource name computed where the program runs may find, which the
	 * program carries ({@link JavaResourceTable}): what lookups some pass made select.
	 */
	private final JavaResourceTable carried = new JavaResourceTable();

	/** The selections made of {@link #carried}, each once. */
	private final Set<String> selections = new java.util.HashSet<>();

	/**
	 * Notes a lookup of a resource by a name computed where the program runs: every file
	 * its loader may find is carried, and a pass made before is made again.
	 * @param tree whether the lookup searches the program's source tree
	 * @throws JavaResourceTable.Bounded where the files are too many to carry
	 */
	void carry(boolean tree) throws JavaResourceTable.Bounded {
		select(tree, JavaResourceTable.Selection.EVERY, JavaResourceTable.Selection.EVERY, true, "*");
	}

	/**
	 * Notes a lookup of a resource by a name computed where the program runs that starts
	 * and ends with constant text: the files its loader may find by such a name are
	 * carried. Where a place matches the name as written (the tree, a jar), those whose
	 * path starts with the prefix and ends with the suffix, the two apart; under a
	 * directory of the source path, which resolves a {@code ..} the computed part may
	 * hold, those whose last segment ends with the suffix's. A session carries every
	 * file: its table is read once, and a later buffer may compute any name.
	 * @param tree whether the lookup searches the program's source tree
	 * @param prefix what the name starts with as the loader reads it, a class's package
	 * before it
	 * @param suffix what the name ends with
	 * @throws JavaResourceTable.Bounded where the files are too many to carry
	 */
	void carry(boolean tree, String prefix, String suffix) throws JavaResourceTable.Bounded {
		if (this.session || prefix.isEmpty() && suffix.isEmpty()) {
			carry(tree);
			return;
		}
		int length = prefix.length() + suffix.length();
		java.util.function.Predicate<String> named = name -> name.length() >= length && name.startsWith(prefix)
				&& name.endsWith(suffix);
		JavaResourceTable.Selection written = new JavaResourceTable.Selection(
				prefix.substring(0, prefix.lastIndexOf('/') + 1),
				directory -> directory.startsWith(prefix) || prefix.startsWith(directory),
				path -> path.endsWith("/") ? named.test(path) || named.test(path.substring(0, path.length() - 1))
						: JavaResourceTable.anyFile(path) && named.test(path));
		// a resolved name's last segment is the written one's, which ends with the
		// suffix's, unless that one is empty, . or ..
		String last = suffix.substring(suffix.lastIndexOf('/') + 1);
		JavaResourceTable.Selection resolved = last.isEmpty() || last.equals(".") || last.equals("..")
				? JavaResourceTable.Selection.EVERY
				: new JavaResourceTable.Selection("", directory -> true,
						path -> path.endsWith("/") ? path.substring(0, path.length() - 1).endsWith(last)
								: JavaResourceTable.anyFile(path) && path.endsWith(last));
		select(tree, written, resolved, true, "* " + prefix + "\0" + suffix);
	}

	/**
	 * Notes {@code ResourceBundle.getBundle} of a constant base name: the
	 * {@code .properties} files of its bundles its loader may find are carried.
	 * @param tree whether the loader searches the program's source tree
	 * @param path the base name's path, its {@code .} made {@code /}
	 * @throws JavaResourceTable.Bounded where the files are too many to carry
	 */
	void carryBundle(boolean tree, String path) throws JavaResourceTable.Bounded {
		int slash = path.lastIndexOf('/');
		String directory = path.substring(0, slash + 1);
		String base = path.substring(slash + 1);
		JavaResourceTable.Selection bundles = new JavaResourceTable.Selection(directory, below -> false, file -> {
			String name = file.substring(directory.length());
			return name.endsWith(".properties") && (name.equals(base + ".properties") || name.startsWith(base + "_"));
		});
		select(tree, bundles, bundles, false, "bundle " + path);
	}

	/**
	 * Notes {@code ResourceBundle.getBundle} of a base name computed where the program
	 * runs: the {@code .properties} files its loader may find under the base name's
	 * constant prefix are carried. A bundle's path has no {@code .} but its last one, so
	 * a directory of the source path resolves none of it; one with an empty segment is
	 * bounded by no prefix.
	 * @param tree whether the loader searches the program's source tree
	 * @param prefix what the base name starts with
	 * @throws JavaResourceTable.Bounded where the files are too many to carry
	 */
	void carryBundles(boolean tree, String prefix) throws JavaResourceTable.Bounded {
		String path = prefix.replace('.', '/');
		String start = path.contains("//") ? "" : path;
		JavaResourceTable.Selection bundles = new JavaResourceTable.Selection(
				start.substring(0, start.lastIndexOf('/') + 1),
				directory -> directory.startsWith(start) || start.startsWith(directory),
				file -> file.endsWith(".properties") && file.startsWith(start));
		select(tree, bundles, bundles, false, "bundles " + start);
	}

	/**
	 * Selects files of the places a loader looks in, once: the roots, and the tree where
	 * the loader looks there. A directory selected is what {@link #inDirectory} and
	 * {@link #inArchive} find of it, the tree's top none.
	 * @param written the selection of a place matching a name as written: the tree, a jar
	 * @param resolved the selection of a directory of the source path, which resolves a
	 * name's {@code .} and {@code ..}
	 */
	private void select(boolean tree, JavaResourceTable.Selection written, JavaResourceTable.Selection resolved,
			boolean directories, String selection) throws JavaResourceTable.Bounded {
		if (!this.selections.contains("R " + selection)) {
			for (int i = 0; i < this.files.libraryRoots(); i++) {
				int root = i;
				boolean archive = this.files.libraryArchive(root);
				JavaResourceTable.Directories answers = !directories ? null : archive
						? (path, entries) -> !path.isEmpty() && this.files.libraryEntry(root, path) ? new byte[0] : null
						: (path, entries) -> listing(entries);
				this.carried.select(root, archive ? 'J' : 'D', this.files.libraryTop(root),
						path -> this.files.libraryListing(root, path), path -> this.files.libraryResource(root, path),
						archive ? written : resolved, answers, "the source path");
			}
			this.selections.add("R " + selection);
			changed();
		}
		if (tree && !this.selections.contains("T " + selection)) {
			String entry = this.entry;
			this.carried.select(-1, 'T', treeTop(),
					path -> entry == null ? this.files.treeListing(path)
							: this.files.findListing(entry, this.root + path),
					this::treeResource, written,
					directories ? (path, entries) -> path.isEmpty() ? null : listing(entries) : null,
					"the source tree");
			this.selections.add("T " + selection);
			changed();
		}
	}

	/**
	 * Notes a URL asked of a name computed where the program runs: the table then names
	 * where each place is, which a program reading only streams does without.
	 */
	void carryPlaces() {
		if (!this.placesCarried) {
			this.placesCarried = true;
			changed();
		}
	}

	private boolean placesCarried;

	/** The table of what is carried, as the library reads it: empty when nothing is. */
	String carriedTable() {
		return this.carried.text(this.placesCarried);
	}

	/**
	 * The providers of a service the files {@code META-INF/services/<service>} of a
	 * loader's resources name, as {@code ServiceLoader} reads them: the source tree's
	 * first where the loader searches it, then each root's in order; a name once. A
	 * line's text from its first {@code #} on is a comment, and the spaces and tabs
	 * around a name are dropped.
	 * @param service the service's binary name
	 * @param tree whether the loader searches the program's source tree
	 * @return the binary names, in order
	 */
	List<String> serviceProviders(String service, boolean tree) {
		String path = "META-INF/services/" + service;
		List<byte[]> read = new ArrayList<>();
		byte[] own = tree ? treeResource(path) : null;
		if (own != null) {
			read.add(own);
		}
		for (int root = 0; root < this.files.libraryRoots(); root++) {
			byte[] found = this.files.libraryResource(root, path);
			if (found != null) {
				read.add(found);
			}
		}
		Set<String> names = new java.util.LinkedHashSet<>();
		for (byte[] file : read) {
			for (String line : new String(file, StandardCharsets.UTF_8).split("\r\n|\r|\n")) {
				int comment = line.indexOf('#');
				String name = comment < 0 ? line : line.substring(0, comment);
				int start = 0;
				int end = name.length();
				while (start < end && (name.charAt(start) == ' ' || name.charAt(start) == '\t')) {
					start++;
				}
				while (end > start && (name.charAt(end - 1) == ' ' || name.charAt(end - 1) == '\t')) {
					end--;
				}
				if (start < end) {
					names.add(name.substring(start, end));
				}
			}
		}
		return List.copyOf(names);
	}

	/**
	 * The services the files of a loader's resources name: the names of the files in
	 * {@code META-INF/services/} of the source tree, where the loader searches it, and of
	 * each root, a name once.
	 * @param tree whether the loader searches the program's source tree
	 */
	Set<String> serviceNames(boolean tree) {
		String directory = "META-INF/services/";
		List<@Nullable List<String>> listings = new ArrayList<>();
		String entry = this.entry;
		if (tree) {
			listings.add(entry == null ? this.files.treeListing(directory)
					: this.files.findListing(entry, this.root + directory));
		}
		for (int root = 0; root < this.files.libraryRoots(); root++) {
			listings.add(this.files.libraryListing(root, directory));
		}
		Set<String> names = new java.util.TreeSet<>();
		for (List<String> listing : listings) {
			for (String file : listing == null ? List.<String>of() : listing) {
				if (!file.endsWith("/") && !file.startsWith(".")) {
					names.add(file);
				}
			}
		}
		return names;
	}

	/**
	 * The classes {@code ResourceBundle.getBundle} of the constant base names some pass
	 * met may make by their names, by binary name.
	 */
	final Map<String, JavaClass> classBundles = new LinkedHashMap<>();

	/**
	 * Whether a base name is computed where the program runs: every bundle class of the
	 * program and its libraries may then be made by its name.
	 */
	boolean everyClassBundle;

	/**
	 * The names of the classes that may be bundles of a base name: the top-level classes
	 * whose files, in the base name's package under the source tree (where the loader
	 * searches it) and every root, are named as the base name or as it and a {@code _}.
	 * @param base the base name, a class's qualified name
	 * @param tree whether the loader searches the program's source tree
	 * @return the binary names, the tree's first
	 */
	List<String> bundleClassNames(String base, boolean tree) {
		int dot = base.lastIndexOf('.');
		String packageName = base.substring(0, dot + 1);
		String directory = packageName.replace('.', '/');
		String simple = base.substring(dot + 1);
		List<List<String>> listings = new ArrayList<>();
		String entry = this.entry;
		if (tree) {
			listings.add(entry == null ? this.files.treeListing(directory)
					: this.files.findListing(entry, this.root + directory));
		}
		for (int root = 0; root < this.files.libraryRoots(); root++) {
			listings.add(this.files.libraryListing(root, directory));
		}
		Set<String> names = new java.util.LinkedHashSet<>();
		int dollar = simple.indexOf('$');
		for (List<String> listing : listings) {
			for (String file : listing == null ? List.<String>of() : listing) {
				if (dollar > 0 && file.equals(simple.substring(0, dollar) + ".java")) {
					// a member class's bundles: the classes nested in the file's class
					JavaClass outer = binaryNamed(packageName + simple.substring(0, dollar));
					if (outer != null) {
						nestedBundles(outer, base, names);
					}
				}
				else if (dollar < 0 && file.endsWith(".java")
						&& (file.equals(simple + ".java") || file.startsWith(simple + "_")) && !simple.isEmpty()) {
					names.add(packageName + file.substring(0, file.length() - ".java".length()));
				}
			}
		}
		return List.copyOf(names);
	}

	/**
	 * Declares the classes a base name computed where the program runs may find as its
	 * bundles, which the program need name nowhere else: as a launcher's loader compiles
	 * the class {@code ResourceBundle} asks it for, which no other part of the program
	 * named. Only a file declaring a class under one of the bundle classes of the library
	 * or under such a class of its own, nested or not, is read past its text: a class
	 * declared is lowered whole where it is the program's, so a file of the tree the
	 * program never uses stays unread, as the launcher leaves it uncompiled. The files
	 * are those of the tree, where the loader looks there, and of every root.
	 * @param tree whether the loader searches the program's source tree
	 */
	void declareBundleClasses(boolean tree) {
		if (!this.selections.add("B " + tree)) {
			return;
		}
		List<JavaFiles.Source> sources = new ArrayList<>();
		if (tree) {
			sources.addAll(treeSources().values());
		}
		Set<String> directories = new java.util.TreeSet<>();
		for (int i = 0; i < this.files.libraryRoots(); i++) {
			int root = i;
			directories(path -> this.files.libraryListing(root, path), "", directories);
		}
		for (String directory : directories) {
			sources.addAll(this.files.libraryPackage(directory));
		}
		// the simple names of the classes a bundle class is under: the library's, then
		// those of the files read that are under one of them
		Set<String> bases = new HashSet<>(Set.of("ResourceBundle", "ListResourceBundle", "PropertyResourceBundle"));
		Map<JavaFiles.Source, Optional<CompilationUnit>> parsed = new IdentityHashMap<>();
		boolean grew;
		do {
			for (JavaFiles.Source source : sources) {
				if (!parsed.containsKey(source) && bases.stream().anyMatch(source.text()::contains)) {
					parsed.put(source, parsed(source));
				}
			}
			int known = bases.size();
			for (Optional<CompilationUnit> unit : parsed.values()) {
				for (ClassDeclaration declared : unit.map(CompilationUnit::classes).orElse(List.of())) {
					underBundle(declared, bases);
				}
			}
			grew = bases.size() > known;
		}
		while (grew);
		for (Optional<CompilationUnit> read : parsed.values()) {
			if (read.isEmpty()) {
				continue;
			}
			CompilationUnit unit = read.get();
			for (ClassDeclaration declared : unit.classes()) {
				if (underBundle(declared, bases)) {
					String packageName = unit.packageName();
					try {
						binaryNamed(packageName == null ? declared.name() : packageName + "." + declared.name());
					}
					catch (LispReadException ex) {
						// a class that cannot be declared is no bundle the program has
					}
				}
			}
		}
		changed();
	}

	/**
	 * The classes of the program's source tree an annotation of a type whose retention is
	 * {@code RUNTIME} marks, in the order of their files and of their binary names, each
	 * declared a class of the program: what a build finds by the annotation, as an index
	 * the program asks for lists them ({@link #indexed}), but of the tree alone -- the
	 * program's own sources. Only a file whose text holds the type's simple name on a
	 * declaration is read past its text, once.
	 * @param annotation the annotation type's binary name
	 * @return the classes, none where the type is no such annotation the program can see
	 */
	List<JavaClass> treeCarrying(String annotation) {
		List<JavaClass> known = this.treeCarried.get(annotation);
		if (known != null) {
			return known;
		}
		// asked again while the classes are read: none yet
		this.treeCarried.put(annotation, List.of());
		JavaClass type;
		try {
			type = binaryNamed(annotation);
		}
		catch (LispReadException | JavaClass.Absent ex) {
			type = null;
		}
		if (type == null || !isRuntime(type)) {
			return List.of();
		}
		JavaClass named = type;
		List<JavaClass> found = new ArrayList<>();
		treeSources().forEach((path, file) -> carrying(named, path, file.text(), found));
		List<JavaClass> carried = List.copyOf(found);
		this.treeCarried.put(annotation, carried);
		return carried;
	}

	/** The classes of the tree each annotation asked for marks. */
	private final Map<String, List<JavaClass>> treeCarried = new HashMap<>();

	/**
	 * The {@code .java} files of the program's source tree, by their paths below its top.
	 */
	private Map<String, JavaFiles.Source> treeSources() {
		Map<String, JavaFiles.Source> known = this.treeSources;
		if (known != null) {
			return known;
		}
		Map<String, JavaFiles.Source> sources = new LinkedHashMap<>();
		this.treeSources = sources;
		String entry = this.entry;
		Function<String, @Nullable List<String>> listing = path -> entry == null ? this.files.treeListing(path)
				: this.files.findListing(entry, this.root + path);
		Set<String> directories = new java.util.TreeSet<>();
		directories(listing, "", directories);
		for (String directory : directories) {
			List<String> names = listing.apply(directory);
			for (String name : names == null ? List.<String>of() : names) {
				JavaFiles.Source file = !name.endsWith(".java") ? null : entry == null
						? this.files.tree(directory + name) : this.files.find(entry, this.root + directory + name);
				if (file != null) {
					sources.put(directory + name, file);
				}
			}
		}
		return sources;
	}

	/** The files of the tree, read once: an index of each annotation asks for them. */
	private @Nullable Map<String, JavaFiles.Source> treeSources;

	/** The texts of the {@code .java} files of each root, by their paths, read once. */
	private final Map<Integer, Map<String, String>> rootSources = new HashMap<>();

	private Map<String, String> rootSources(int root) {
		return this.rootSources.computeIfAbsent(root, key -> {
			Map<String, String> sources = new LinkedHashMap<>();
			Set<String> directories = new java.util.TreeSet<>();
			directories(path -> this.files.libraryListing(root, path), "", directories);
			for (String directory : directories) {
				List<String> names = this.files.libraryListing(root, directory);
				for (String name : names == null ? List.<String>of() : names) {
					byte[] octets = name.endsWith(".java") ? this.files.libraryResource(root, directory + name) : null;
					if (octets != null) {
						sources.put(directory + name, new String(octets, StandardCharsets.UTF_8));
					}
				}
			}
			return sources;
		});
	}

	// ---- the annotations a program reads

	/**
	 * An annotation a class carries that a program can read where it runs: one of a type
	 * whose retention is {@code RUNTIME}.
	 *
	 * @param type the annotation type
	 * @param written the annotation
	 * @param index its place among the annotations written on the class
	 */
	record Carried(JavaClass type, JavaSyntax.Annotation written, int index) {
	}

	private final Map<JavaClass, List<Carried>> carriedAnnotations = new IdentityHashMap<>();

	/**
	 * The annotations a class of the program or of its libraries carries where the
	 * program runs. A class of the front end's own library carries none: its source is
	 * not the JDK's.
	 */
	List<Carried> annotations(JavaClass declaration) {
		List<Carried> known = this.carriedAnnotations.get(declaration);
		if (known != null) {
			return known;
		}
		List<Carried> carried = new ArrayList<>();
		ClassDeclaration written = declaration.declaration;
		if (!declaration.intrinsic && written != null && declaration.source != null && !declaration.source.library()
				&& !declaration.isLocal) {
			Unit unit = unit(declaration);
			for (int index = 0; index < written.annotations().size(); index++) {
				JavaSyntax.Annotation annotation = written.annotations().get(index);
				JavaClass type = annotationType(annotation, declaration, unit);
				if (type != null && isRuntime(type)) {
					carried.add(new Carried(type, annotation, index));
				}
			}
		}
		List<Carried> wrapped = List.copyOf(wrapped(carried));
		this.carriedAnnotations.put(declaration, wrapped);
		return wrapped;
	}

	/**
	 * The annotations a declaration carries as a class file holds them: those of a
	 * repeatable type written more than once one annotation of its container in the first
	 * one's place, its {@code value} the ones written (JLS 9.7.5), as javac writes it.
	 */
	List<Carried> wrapped(List<Carried> carried) {
		Map<JavaClass, List<Carried>> byType = new LinkedHashMap<>();
		for (Carried each : carried) {
			byType.computeIfAbsent(each.type(), type -> new ArrayList<>()).add(each);
		}
		if (byType.size() == carried.size()) {
			return carried;
		}
		List<Carried> held = new ArrayList<>();
		for (Carried each : carried) {
			List<Carried> same = java.util.Objects.requireNonNull(byType.get(each.type()));
			JavaClass container = same.size() < 2 ? null : containerOf(each.type());
			if (container == null || !isRuntime(container)) {
				held.add(each);
				continue;
			}
			if (same.get(0) != each) {
				continue;
			}
			List<JavaSyntax.ElementValue> values = new ArrayList<>();
			for (Carried repeated : same) {
				values.add(repeated.written());
			}
			held.add(new Carried(container,
					new JavaSyntax.Annotation(-1 - each.written().pos(), container.name,
							List.of(new JavaSyntax.ElementPair("value", new JavaSyntax.ElementArray(values)))),
					each.index()));
		}
		return held;
	}

	/**
	 * The container of a repeatable annotation type, its {@code @Repeatable}'s class, or
	 * {@code null}.
	 */
	private @Nullable JavaClass containerOf(JavaClass type) {
		List<JavaSyntax.Annotation> repeatable = meta(type, "Repeatable");
		if (repeatable.isEmpty() || repeatable.get(0).pairs().isEmpty()
				|| !(repeatable.get(0).pairs().get(0).value() instanceof JavaSyntax.ElementText text)) {
			return null;
		}
		String written = unit(type).source().text().substring(text.from(), text.to()).strip();
		if (!written.endsWith(".class")) {
			return null;
		}
		try {
			JavaClass found = find(written.substring(0, written.length() - ".class".length()).strip(), type.outer,
					unit(type), text.from());
			return found != null && found.isAnnotation ? found : null;
		}
		catch (LispReadException | JavaClass.Absent ex) {
			return null;
		}
	}

	/**
	 * The name of the static method of a holder making an annotation: by where it is
	 * written, a container javac makes of repeated ones by where the first is.
	 */
	static String holderMethod(Carried carried) {
		int pos = carried.written().pos();
		return pos < 0 ? "r" + (-1 - pos) : "p" + pos;
	}

	/**
	 * Whether a class the program has carries an annotation of a type where the program
	 * runs. The table grows while a program is lowered: an answer is asked again by the
	 * next pass.
	 */
	boolean carried(JavaClass type) {
		for (JavaClass declaration : List.copyOf(this.classes.values())) {
			for (Carried carried : annotations(declaration)) {
				if (carried.type() == type) {
					return true;
				}
			}
		}
		return false;
	}

	/** The annotation type an annotation of a class names, or {@code null}. */
	@Nullable JavaClass annotationType(JavaSyntax.Annotation annotation, JavaClass on, Unit unit) {
		return annotationTypeIn(annotation, on.outer, unit);
	}

	/**
	 * The annotation type an annotation names where it is written, or {@code null}:
	 * {@code java.lang}'s two of source retention are none.
	 */
	private @Nullable JavaClass annotationTypeIn(JavaSyntax.Annotation annotation, @Nullable JavaClass scope,
			Unit unit) {
		String name = annotation.name();
		String simple = name.startsWith("java.lang.") ? name.substring("java.lang.".length()) : name;
		if (simple.equals("Override") || simple.equals("SuppressWarnings")) {
			return null;
		}
		try {
			JavaClass type = find(name, scope, unit, annotation.pos());
			return type != null && type.isAnnotation && type.absent == null ? type : null;
		}
		catch (LispReadException | JavaClass.Absent ex) {
			// a library's annotation of a type nobody has, which a compiler leaves out
			return null;
		}
	}

	/**
	 * The annotations written on a declaration in a class's body -- a method's, a
	 * constructor's, a field's, a parameter's, a record component's, an enum constant's,
	 * by the position of its name -- that the program can read where it runs: of a type
	 * of runtime retention that may stand on that kind of declaration
	 * ({@link #appliesTo}). A class of the front end's own library carries none.
	 * @param context the kind of declaration, as {@code ElementType} names it
	 */
	List<Carried> carriedAt(JavaClass declaration, int pos, String context) {
		List<Carried> carried = new ArrayList<>();
		for (Carried each : runtimeAt(declaration, pos)) {
			if (appliesTo(each.type(), context)) {
				carried.add(each);
			}
		}
		return carried;
	}

	/**
	 * The annotations of runtime retention written on a declaration in a class's body,
	 * repeated ones in their container ({@link #wrapped}).
	 */
	private List<Carried> runtimeAt(JavaClass declaration, int pos) {
		if (declaration.intrinsic || declaration.declaration == null || declaration.source == null
				|| declaration.source.library()) {
			return List.of();
		}
		Unit unit = unit(declaration);
		List<JavaSyntax.Annotation> written = unit.tree().declarationAnnotations().getOrDefault(pos, List.of());
		List<Carried> carried = new ArrayList<>();
		for (int index = 0; index < written.size(); index++) {
			JavaSyntax.Annotation annotation = written.get(index);
			JavaClass type = annotationTypeIn(annotation, declaration, unit);
			if (type != null && isRuntime(type)) {
				carried.add(new Carried(type, annotation, index));
			}
		}
		return wrapped(carried);
	}

	/**
	 * Whether an annotation of a type written on a kind of declaration is the
	 * declaration's where the program runs: its {@code @Target} names that kind, or it
	 * has none, which is every kind of declaration (JLS 9.6.4.1).
	 */
	boolean appliesTo(JavaClass type, String context) {
		Set<String> targets = targets(type);
		if (targets == null) {
			return true;
		}
		// one of a use of a type is a type parameter's and an enum constant's too (JDK 25
		// measured); of a record component, a field, a method, a constructor and a
		// parameter it is a type's alone
		boolean typeUse = targets.contains("TYPE_USE");
		return switch (context) {
			case "TYPE_PARAMETER" -> typeUse || targets.contains(context);
			case "ENUM_CONSTANT" -> typeUse || targets.contains("FIELD");
			default -> targets.contains(context);
		};
	}

	/**
	 * Whether an annotation type is one of a use of a type: its {@code @Target} names
	 * {@code TYPE_USE}.
	 */
	boolean typeUse(JavaClass type) {
		Set<String> targets = targets(type);
		return targets != null && targets.contains("TYPE_USE");
	}

	/**
	 * The names of the kinds an annotation type's {@code @Target} names, or {@code null}
	 * where it has none.
	 */
	private @Nullable Set<String> targets(JavaClass type) {
		List<JavaSyntax.Annotation> targets = meta(type, "Target");
		if (targets.isEmpty()) {
			return null;
		}
		if (targets.get(0).pairs().isEmpty()) {
			return Set.of();
		}
		String text = unit(type).source().text();
		JavaSyntax.ElementValue value = targets.get(0).pairs().get(0).value();
		List<JavaSyntax.ElementValue> named = value instanceof JavaSyntax.ElementArray array ? array.values()
				: List.of(value);
		Set<String> kinds = new HashSet<>();
		for (JavaSyntax.ElementValue each : named) {
			if (each instanceof JavaSyntax.ElementText written) {
				String kind = text.substring(written.from(), written.to()).strip();
				kinds.add(kind.substring(kind.lastIndexOf('.') + 1));
			}
		}
		return kinds;
	}

	/**
	 * Whether an annotation type is kept where a program runs: its declaration carries
	 * {@code @Retention(RetentionPolicy.RUNTIME)}.
	 */
	boolean isRuntime(JavaClass type) {
		return type.isAnnotation && meta(type, "Retention").stream()
			.anyMatch(retention -> retention.pairs().size() == 1
					&& retention.pairs().get(0).value() instanceof JavaSyntax.ElementText text
					&& unit(type).source().text().substring(text.from(), text.to()).strip().endsWith("RUNTIME"));
	}

	/** Whether an annotation type is inherited by the classes under one carrying it. */
	boolean isInherited(JavaClass type) {
		return !meta(type, "Inherited").isEmpty();
	}

	/**
	 * The annotations of {@code java.lang.annotation} of a name a declaration carries.
	 */
	private static List<JavaSyntax.Annotation> meta(JavaClass type, String name) {
		ClassDeclaration written = type.declaration;
		return written == null ? List.of()
				: written.annotations()
					.stream()
					.filter(annotation -> annotation.name().equals(name)
							|| annotation.name().equals("java.lang.annotation." + name))
					.toList();
	}

	/** Where the index of the classes carrying an annotation is, as a resource. */
	static final String ANNOTATION_INDEX = "META-INF/annotations/";

	/**
	 * The classes carrying an annotation whose index a program asks for, by the place
	 * they are of.
	 *
	 * @param tree those of the program's source tree, in the order of their names
	 * @param roots those of each root of the source path
	 */
	record Indexed(List<JavaClass> tree, List<List<JavaClass>> roots) {
	}

	private final Map<String, Optional<Indexed>> indexes = new HashMap<>();

	/**
	 * The classes a name computed where the program runs can find: the ones an index the
	 * program asks for lists, the ones reachability metadata declares, and the ones a
	 * call names by a constant.
	 */
	final Set<JavaClass> findable = new java.util.LinkedHashSet<>();

	// ---- reachability metadata (.kb/java-frontend.md, "Reflection from reachability
	// metadata")

	/**
	 * The reflection entries of the reachability metadata of the program's source tree,
	 * of each root of its source path, and those the host hands in (the shared
	 * repository's for the program's dependencies): read the first time the program
	 * reflects, {@code null} until then.
	 */
	private @Nullable List<JavaReachabilityMetadata.Entry> metadata;

	/**
	 * The reflection entries of the reachability metadata, read once: the files of
	 * {@code META-INF/native-image/} of the source tree, then of each root, then the
	 * documents {@link JavaFiles#metadata} hands in.
	 */
	List<JavaReachabilityMetadata.Entry> metadata() {
		List<JavaReachabilityMetadata.Entry> known = this.metadata;
		if (known != null) {
			return known;
		}
		List<JavaReachabilityMetadata.Entry> entries = new ArrayList<>();
		String entry = this.entry;
		metadataFiles(
				path -> entry == null ? this.files.treeListing(path) : this.files.findListing(entry, this.root + path),
				path -> entry == null ? this.files.treeResource(path)
						: this.files.findResource(entry, this.root + path),
				path -> java.util.Objects.requireNonNullElse(
						entry == null ? this.files.treePath(path) : this.files.findPath(entry, this.root + path), path),
				entries);
		for (int i = 0; i < this.files.libraryRoots(); i++) {
			int root = i;
			metadataFiles(path -> this.files.libraryListing(root, path), path -> this.files.libraryResource(root, path),
					path -> this.files.libraryPath(root, path), entries);
		}
		for (String coordinate : this.files.dependencies()) {
			String[] parts = coordinate.split(":");
			if (parts.length != 3) {
				continue;
			}
			String directory = parts[0] + "/" + parts[1] + "/";
			JavaFiles.Source index = this.files.metadataRepository(directory + "index.json");
			String version = index == null ? null
					: JavaReachabilityMetadata.repositoryVersion(index.path(), index.text(), parts[2]);
			JavaFiles.Source document = version == null ? null
					: this.files.metadataRepository(directory + version + "/" + JavaReachabilityMetadata.FILE);
			if (document != null) {
				// what a JDK's own classes ask by reflection, traced where the metadata
				// was made, is nothing the library's classes ask
				for (JavaReachabilityMetadata.Entry read : JavaReachabilityMetadata.read(document.path(),
						document.text(), false)) {
					if (!JavaReachabilityMetadata.ofTheJdk(read.type())) {
						entries.add(read);
					}
				}
			}
		}
		this.metadata = List.copyOf(entries);
		return this.metadata;
	}

	/** Reads the metadata files below a place's {@code META-INF/native-image/}. */
	private static void metadataFiles(Function<String, @Nullable List<String>> listing,
			Function<String, byte @Nullable []> reading, Function<String, String> named,
			List<JavaReachabilityMetadata.Entry> into) {
		Set<String> directories = new java.util.TreeSet<>();
		directories(listing, JavaReachabilityMetadata.DIRECTORY, directories);
		for (String directory : directories) {
			List<String> names = listing.apply(directory);
			// in the order of their names, which a file system's listing need not keep
			for (String name : new java.util.TreeSet<>(names == null ? List.<String>of() : names)) {
				boolean legacy = name.equals(JavaReachabilityMetadata.LEGACY_FILE);
				if (!legacy && !name.equals(JavaReachabilityMetadata.FILE)) {
					continue;
				}
				byte[] octets = reading.apply(directory + name);
				if (octets != null) {
					into.addAll(JavaReachabilityMetadata.read(named.apply(directory + name),
							new String(octets, StandardCharsets.UTF_8), legacy));
				}
			}
		}
	}

	/**
	 * What the metadata declares of each class it names, by binary name: the entries that
	 * count where the program is lowered (no condition, or one naming a class the program
	 * reaches), with the members lookups by constants name of a library's class
	 * ({@link #folded}). Made again over the passes, as the reach grows.
	 */
	Map<String, JavaReachabilityMetadata.Declared> declared = Map.of();

	/**
	 * The members of a library's classes a lookup by constants names
	 * ({@code Name.class.getMethod("m")}), by the class's binary name: declared as an
	 * entry naming them would declare them, as GraalVM's native image keeps what such a
	 * lookup names.
	 */
	final Map<String, JavaReachabilityMetadata.Declared> folded = new LinkedHashMap<>();

	/**
	 * The class of a binary name an entry of the metadata or a constant name names: one
	 * of the program's source tree, of a library on its source path or of the JDK the
	 * library has, declared where it was not; {@code null} for a name of none of them.
	 * Naming a class of the JDK here holds no value of it.
	 */
	@Nullable JavaClass metadataClass(String binaryName) {
		if (binaryName.isEmpty() || binaryName.endsWith("[]") || binaryName.indexOf('/') >= 0) {
			return null;
		}
		if (isPlatform(binaryName)) {
			try {
				return jdkClass(binaryName);
			}
			catch (LispReadException | JavaClass.Absent ex) {
				return null;
			}
		}
		try {
			return binaryNamed(binaryName);
		}
		catch (LispReadException | JavaClass.Absent ex) {
			return null;
		}
	}

	/**
	 * The index a resource name asks for, or {@code null}: for
	 * {@code META-INF/annotations/<the binary name of an annotation type>} whose
	 * retention is {@code RUNTIME}, the classes of the program's source tree and of the
	 * roots of its source path that carry the annotation. A compiled program has no class
	 * path to look for them in where it runs, so they are looked for while it is lowered,
	 * as a build writing such an index would, and each is a class of the program from
	 * then on. Only a file whose text holds the type's simple name on a declaration is
	 * read past its text.
	 */
	@Nullable Indexed indexed(String resource) {
		if (!resource.startsWith(ANNOTATION_INDEX)) {
			return null;
		}
		Optional<Indexed> known = this.indexes.get(resource);
		if (known != null) {
			return known.orElse(null);
		}
		// asked again while the classes are read: none yet
		this.indexes.put(resource, Optional.empty());
		JavaClass named;
		try {
			named = binaryNamed(resource.substring(ANNOTATION_INDEX.length()));
		}
		catch (LispReadException | JavaClass.Absent ex) {
			named = null;
		}
		if (named == null || !isRuntime(named)) {
			return null;
		}
		JavaClass type = named;
		List<JavaClass> tree = new ArrayList<>();
		treeSources().forEach((path, file) -> carrying(type, path, file.text(), tree));
		List<List<JavaClass>> roots = new ArrayList<>();
		Set<String> read = new HashSet<>();
		for (int root = 0; root < this.files.libraryRoots(); root++) {
			List<JavaClass> under = new ArrayList<>();
			for (Map.Entry<String, String> file : rootSources(root).entrySet()) {
				// the first root's of a path is the class a program has
				if (read.add(file.getKey())) {
					carrying(type, file.getKey(), file.getValue(), under);
				}
			}
			roots.add(List.copyOf(under));
		}
		Indexed index = new Indexed(List.copyOf(tree), List.copyOf(roots));
		this.indexes.put(resource, Optional.of(index));
		this.findable.addAll(tree);
		roots.forEach(this.findable::addAll);
		changed();
		return index;
	}

	/**
	 * Adds the classes of a file that carry an annotation of a type, in the order of
	 * their binary names.
	 * @param path the file's path below the top of its place
	 */
	private void carrying(JavaClass type, String path, String text, List<JavaClass> into) {
		if (!text.contains(type.simpleName())) {
			return;
		}
		String stem = path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length());
		CompilationUnit unit;
		try {
			unit = JavaParser.parse(text, path, stem);
		}
		catch (LispReadException ex) {
			return;
		}
		if (unit.classes().stream().noneMatch(declared -> names(declared, type.simpleName()))) {
			return;
		}
		String directory = path.substring(0, path.lastIndexOf('/') + 1).replace('/', '.');
		List<JavaClass> found = new ArrayList<>();
		try {
			if (qualified(directory + stem) == null) {
				return;
			}
			for (ClassDeclaration declared : unit.classes()) {
				JavaClass top = this.classes.get(directory + declared.name());
				if (top != null && top.source != null && !top.source.library()) {
					carrying(type, top, found);
				}
			}
		}
		catch (LispReadException | JavaClass.Absent ex) {
			// a file that cannot be declared holds no class the program has
			return;
		}
		found.sort(java.util.Comparator.comparing(each -> each.binaryName));
		into.addAll(found);
	}

	private void carrying(JavaClass type, JavaClass declaration, List<JavaClass> into) {
		if (annotations(declaration).stream().anyMatch(carried -> carried.type() == type)) {
			into.add(declaration);
		}
		for (JavaClass member : declaration.members.values()) {
			carrying(type, member, into);
		}
	}

	/** Whether a declaration or one nested in it is annotated by a simple name. */
	private static boolean names(ClassDeclaration declared, String simpleName) {
		for (JavaSyntax.Annotation annotation : declared.annotations()) {
			if (annotation.name().equals(simpleName) || annotation.name().endsWith("." + simpleName)) {
				return true;
			}
		}
		return declared.nested().stream().anyMatch(nested -> names(nested, simpleName));
	}

	/** An index as a resource: the binary names of its classes, a name a line. */
	private static byte[] index(List<JavaClass> listed) {
		StringBuilder lines = new StringBuilder();
		for (JavaClass each : listed) {
			lines.append(each.binaryName).append('\n');
		}
		return lines.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Whether a loader that searches the program's source tree finds there a resource the
	 * roots of the source path do not answer for: a file of the tree, or the index of the
	 * tree's annotated classes.
	 */
	boolean treeFinds(String name) {
		Indexed index = indexed(name);
		return treeResource(name) != null || (index != null && !index.tree().isEmpty());
	}

	private final Map<JavaClass, String> annotationClasses = new IdentityHashMap<>();

	/**
	 * The classes making a class's annotations in full -- equal, hashed and written as a
	 * JDK's ({@link JavaAnnotations#implementation}) --, by class.
	 */
	private final Map<JavaClass, String> fullAnnotationClasses = new IdentityHashMap<>();

	/**
	 * Whether the program's own code reads an annotation of a class by its type
	 * ({@code getAnnotation}, {@code getDeclaredAnnotation}): what an annotation answers
	 * of {@code equals}, {@code hashCode} and {@code toString} is then made in full.
	 */
	boolean annotationsRead;

	/**
	 * Whether an annotation answers {@code equals}, {@code hashCode} and {@code toString}
	 * as a JDK's does: the program's own code reads one by its type, or something lists
	 * annotations ({@code getAnnotations} and the rest). Else they are an object's and
	 * the text is refused, so that a program whose libraries read annotations for their
	 * own use -- a container finding its servlets -- holds none of it. Set by the
	 * lowering, read where an annotation's class is written.
	 */
	boolean annotationsInFull;

	/**
	 * Whether the program tests a value against an array of one of the interfaces of
	 * generic types and annotations ({@code Type}, {@code GenericDeclaration},
	 * {@code AnnotatedElement}), which the arrays of reflection's classes then record.
	 */
	boolean reflectionInterfacesNamed;

	/**
	 * The classes answering the defaults of annotation types, by type: an annotation type
	 * carries annotations of its own too, which its {@code $Annotations} makes.
	 */
	private final Map<JavaClass, String> defaultClasses = new IdentityHashMap<>();

	/** The classes answering the defaults of annotation types in full, by type. */
	private final Map<JavaClass, String> fullDefaultClasses = new IdentityHashMap<>();

	/**
	 * The class making the annotations a class carries, declared when first asked for:
	 * generated Java source ({@link JavaAnnotations}), a static method {@code a<index>}
	 * each, in the class's package and under its file's imports, so that the values
	 * written mean what they mean where they are written.
	 * @return its qualified name
	 */
	String annotationHolder(JavaClass declaration) {
		boolean full = this.annotationsInFull;
		String known = (full ? this.fullAnnotationClasses : this.annotationClasses).get(declaration);
		if (known != null) {
			return known;
		}
		Unit unit = unit(declaration);
		// one made before the program was found to compare or write annotations stays
		// beside the one that does, under another name, and nothing reaches it
		String simple = generatedName(declaration,
				full && this.annotationClasses.containsKey(declaration) ? "$AnnotationsInFull" : "$Annotations");
		String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
		(full ? this.fullAnnotationClasses : this.annotationClasses).put(declaration, name);
		StringBuilder members = new StringBuilder();
		for (Carried carried : annotations(declaration)) {
			String made = annotationValue(carried.written(), carried.type(), unit, members, full);
			members.append("    public static Object a")
				.append(carried.index())
				.append("() {\n        return ")
				.append(made)
				.append(";\n    }\n");
		}
		declareGenerated(name, simple, JavaAnnotations.header(unit, enclosing(declaration.outer))
				+ "public final class " + simple + " {\n" + members + "}\n");
		return name;
	}

	/**
	 * The class making the annotations the declarations in a class's body carry
	 * ({@link #carriedAt}), declared when first asked for: generated Java source, a
	 * static method {@code p<position>} each annotation by where it is written, in the
	 * class's package, under its file's imports and the static members of the class and
	 * those around it, so that the values written mean what they mean in the class's
	 * body. A class of its own beside the class's {@code $Annotations}, which a program
	 * reading the class's alone holds as before.
	 * @return its qualified name
	 */
	String memberAnnotationHolder(JavaClass declaration) {
		String known = this.memberAnnotationClasses.get(declaration);
		if (known != null) {
			return known;
		}
		Unit unit = unit(declaration);
		String simple = generatedName(declaration, "$Members");
		String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
		this.memberAnnotationClasses.put(declaration, name);
		StringBuilder members = new StringBuilder();
		Set<String> made = new HashSet<>();
		List<Carried> all = new ArrayList<>();
		for (Integer pos : memberAnnotationPositions(declaration)) {
			all.addAll(runtimeAt(declaration, pos));
		}
		List<TypeName> types = typeNames(declaration);
		for (JavaSyntax.Method method : java.util.Objects.requireNonNull(declaration.declaration).methods()) {
			JavaSyntax.Receiver receiver = unit.tree().receivers().get(method.pos());
			if (receiver != null) {
				types.add(receiver.type());
			}
		}
		for (TypeName type : types) {
			for (List<JavaSyntax.Annotation> written : typeAnnotationGroups(unit, type)) {
				all.addAll(typeCarried(declaration, unit, written));
			}
		}
		for (Carried carried : all) {
			if (made.add(holderMethod(carried))) {
				String value = annotationValue(carried.written(), carried.type(), unit, members, true);
				members.append("    public static Object ")
					.append(holderMethod(carried))
					.append("() {\n        return ")
					.append(value)
					.append(";\n    }\n");
			}
		}
		declareGenerated(name, simple, JavaAnnotations.header(unit, enclosing(declaration)) + "public final class "
				+ simple + " {\n" + members + "}\n");
		return name;
	}

	private final Map<JavaClass, String> memberAnnotationClasses = new IdentityHashMap<>();

	/**
	 * Where the declarations in a class's body that may carry annotations are: its
	 * methods' and constructors' names and their parameters', its fields', its record
	 * components' and its enum constants'.
	 */
	private static List<Integer> memberAnnotationPositions(JavaClass declaration) {
		ClassDeclaration written = java.util.Objects.requireNonNull(declaration.declaration);
		List<Integer> positions = new ArrayList<>();
		for (JavaSyntax.Parameter component : written.components()) {
			positions.add(component.pos());
		}
		for (JavaSyntax.EnumConstant constant : written.constants()) {
			positions.add(constant.pos());
		}
		for (JavaSyntax.Field field : written.fields()) {
			positions.add(field.pos());
		}
		for (JavaSyntax.Method method : written.methods()) {
			positions.add(method.pos());
			for (JavaSyntax.Parameter parameter : method.parameters()) {
				positions.add(parameter.pos());
			}
			for (JavaSyntax.TypeParameter parameter : method.typeParameters()) {
				positions.add(parameter.pos());
			}
		}
		for (JavaSyntax.TypeParameter parameter : written.typeParameters()) {
			positions.add(parameter.pos());
		}
		return positions;
	}

	// ---- type annotations (.kb/java-frontend.md, "Annotated types")

	/**
	 * The type annotations written in a use of a type, at the path from the use to the
	 * type each stands on ({@code rontolisp.internal.AnnotatedTypes}): {@code C} an
	 * array's component, {@code O} the type a member type is a member of, {@code A<n>;} a
	 * type argument, {@code U<n>;} and {@code L<n>;} a wildcard's bounds.
	 *
	 * @param path where they stand
	 * @param carried those of runtime retention, repeated ones in their container
	 */
	record Placed(String path, List<Carried> carried) {
	}

	/**
	 * The type annotations of runtime retention written in a type a declaration in a
	 * class's body names, by where each stands (JLS 9.7.4).
	 * @param written the type, or {@code null} for none written: a constructor's
	 * @param leading the declaration's own annotations, those of a use of a type the
	 * type's when they stand ahead of a type and not of a package or a class scoping a
	 * static member
	 */
	List<Placed> placed(JavaClass declaration, @Nullable TypeName written, List<JavaSyntax.Annotation> leading) {
		Unit unit = unit(declaration);
		Map<String, List<JavaSyntax.Annotation>> at = new LinkedHashMap<>();
		if (written == null) {
			at.put("", List.copyOf(leading));
		}
		else {
			placeType(declaration, unit, written, "", leading, at);
		}
		List<Placed> placed = new ArrayList<>();
		at.forEach((path, annotations) -> {
			List<Carried> carried = typeCarried(declaration, unit, annotations);
			if (!carried.isEmpty()) {
				placed.add(new Placed(path, carried));
			}
		});
		return placed;
	}

	private void placeType(JavaClass declaration, Unit unit, TypeName type, String path,
			List<JavaSyntax.Annotation> leading, Map<String, List<JavaSyntax.Annotation>> at) {
		JavaSyntax.TypeAnnotations written = unit.tree().typeAnnotations().get(type.pos());
		for (int dimension = 0; written != null && dimension < written.dimensions().size()
				&& dimension < type.dimensions(); dimension++) {
			put(at, path + "C".repeat(dimension), written.dimensions().get(dimension));
		}
		String element = path + "C".repeat(type.dimensions());
		if (type.isWildcard()) {
			put(at, element, written == null ? List.of() : written.words().getOrDefault(0, List.of()));
			if (!type.arguments().isEmpty()) {
				placeType(declaration, unit, type.arguments().get(0),
						element + (type.name().equals("? super") ? "L0;" : "U0;"), List.of(), at);
			}
			return;
		}
		int words = type.name().split("\\.").length;
		if (!leading.isEmpty()) {
			// a declaration's own stand ahead of the first word a type annotation may
			// stand ahead of, as javac writes them: the type a qualified name names, not
			// a
			// package's name or a class scoping a static member
			int word = 0;
			while (word < words - 1 && !admissible(type.name(), word, unit, List.of(declaration))) {
				word++;
			}
			put(at, element + "O".repeat(words - 1 - word), leading);
		}
		if (written != null) {
			written.words().forEach((word, annotations) -> {
				if (admissible(type.name(), word, unit, List.of(declaration))) {
					put(at, element + "O".repeat(words - 1 - word), annotations);
				}
			});
		}
		for (int index = 0; index < type.arguments().size(); index++) {
			placeType(declaration, unit, type.arguments().get(index), element + "A" + index + ";", List.of(), at);
		}
		for (TypeName around = type.enclosing(); around != null; around = around.enclosing()) {
			String owner = element + "O".repeat(words - around.name().split("\\.").length);
			for (int index = 0; index < around.arguments().size(); index++) {
				placeType(declaration, unit, around.arguments().get(index), owner + "A" + index + ";", List.of(), at);
			}
		}
	}

	private static void put(Map<String, List<JavaSyntax.Annotation>> at, String path,
			List<JavaSyntax.Annotation> annotations) {
		if (!annotations.isEmpty()) {
			at.computeIfAbsent(path, key -> new ArrayList<>()).addAll(annotations);
		}
	}

	/**
	 * The annotations of a use of a type among those written: of runtime retention and of
	 * a type whose {@code @Target} names {@code TYPE_USE}, repeated ones in their
	 * container.
	 */
	private List<Carried> typeCarried(JavaClass declaration, Unit unit, List<JavaSyntax.Annotation> written) {
		List<Carried> carried = new ArrayList<>();
		for (JavaSyntax.Annotation annotation : written) {
			JavaClass type = annotationTypeIn(annotation, declaration, unit);
			if (type != null && isRuntime(type) && typeUse(type)) {
				carried.add(new Carried(type, annotation, carried.size()));
			}
		}
		return wrapped(carried);
	}

	/**
	 * The groups of annotations written inside a type, its arguments' and bounds' and
	 * those of the types its qualified name's prefix spells included: a word's or a
	 * dimension's each.
	 */
	private static List<List<JavaSyntax.Annotation>> typeAnnotationGroups(Unit unit, TypeName type) {
		List<List<JavaSyntax.Annotation>> groups = new ArrayList<>();
		JavaSyntax.TypeAnnotations written = unit.tree().typeAnnotations().get(type.pos());
		if (written != null) {
			groups.addAll(written.words().values());
			groups.addAll(written.dimensions());
		}
		for (TypeName argument : type.arguments()) {
			groups.addAll(typeAnnotationGroups(unit, argument));
		}
		if (type.enclosing() != null) {
			groups.addAll(typeAnnotationGroups(unit, type.enclosing()));
		}
		return groups;
	}

	/**
	 * The types a class's declaration and the declarations in its body write: what it
	 * extends and implements, its type parameters' bounds, its record components', its
	 * fields', its methods' and constructors' -- returned, taken, thrown, received, their
	 * type parameters' bounds.
	 */
	private static List<TypeName> typeNames(JavaClass declaration) {
		ClassDeclaration written = java.util.Objects.requireNonNull(declaration.declaration);
		List<TypeName> types = new ArrayList<>();
		if (written.superclass() != null) {
			types.add(written.superclass());
		}
		types.addAll(written.interfaces());
		for (JavaSyntax.TypeParameter parameter : written.typeParameters()) {
			types.addAll(parameter.bounds());
		}
		for (JavaSyntax.Parameter component : written.components()) {
			if (component.type() != null) {
				types.add(component.type());
			}
		}
		for (JavaSyntax.Field field : written.fields()) {
			types.add(field.type());
		}
		for (JavaSyntax.Method method : written.methods()) {
			if (method.returnType() != null) {
				types.add(method.returnType());
			}
			for (JavaSyntax.Parameter parameter : method.parameters()) {
				if (parameter.type() != null) {
					types.add(parameter.type());
				}
			}
			types.addAll(method.thrown());
			for (JavaSyntax.TypeParameter parameter : method.typeParameters()) {
				types.addAll(parameter.bounds());
			}
		}
		return types;
	}

	/** The type of the receiver parameter a method or a constructor declares, or null. */
	@Nullable TypeName receiverType(JavaClass declaration, int pos) {
		JavaSyntax.Receiver receiver = unit(declaration).tree().receivers().get(pos);
		return receiver == null ? null : receiver.type();
	}

	/**
	 * The annotations written on a declaration in a class's body, by its name's position.
	 */
	List<JavaSyntax.Annotation> writtenAt(JavaClass declaration, int pos) {
		return unit(declaration).tree().declarationAnnotations().getOrDefault(pos, List.of());
	}

	/**
	 * The class answering the defaults of an annotation type's elements, declared when
	 * first asked for: generated Java source, a static method an element that has one, in
	 * the type's package and under its file's imports, where a default means what it
	 * means.
	 * @return its qualified name
	 */
	String annotationDefaults(JavaClass type, boolean full) {
		Map<JavaClass, String> classes = full ? this.fullDefaultClasses : this.defaultClasses;
		String known = classes.get(type);
		if (known != null) {
			return known;
		}
		Unit unit = unit(type);
		// one made before the program was found to compare or write annotations stays
		// beside the one that does, under another name
		String simple = generatedName(type,
				full && this.defaultClasses.containsKey(type) ? "$DefaultsInFull" : "$Defaults");
		String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
		classes.put(type, name);
		StringBuilder members = new StringBuilder();
		for (JavaClass.Method element : elements(type)) {
			JavaSyntax.ElementValue preset = unit.tree()
				.elementDefaults()
				.get(java.util.Objects.requireNonNull(element.declaration).pos());
			if (preset != null) {
				String answer = converted(preset, element.returnType, unit, members, full);
				members.append("    public static ")
					.append(JavaAnnotations.typeText(element.returnType))
					.append(' ')
					.append(element.name)
					.append("() {\n        return ")
					.append(answer)
					.append(";\n    }\n");
			}
		}
		// a default is written in the type's body, where its members are in scope
		declareGenerated(name, simple, JavaAnnotations.header(unit, enclosing(type)) + "public final class " + simple
				+ " {\n" + members + "}\n");
		return name;
	}

	/** The elements of an annotation type, in the order declared. */
	private static List<JavaClass.Method> elements(JavaClass type) {
		return type.methods.stream()
			.filter(method -> !method.isStatic && method.declaration != null && method.parameters.isEmpty()
					&& method.owner == type)
			.toList();
	}

	/** A class's name below its package, with a suffix: a name no program writes. */
	private static String generatedName(JavaClass declaration, String suffix) {
		return declaration.binaryName.substring(declaration.binaryName.lastIndexOf('.') + 1) + suffix;
	}

	/** The classes around a declaration, the outermost first, by their names. */
	private static List<String> enclosing(@Nullable JavaClass outer) {
		List<String> names = new ArrayList<>();
		for (JavaClass around = outer; around != null; around = around.outer) {
			names.add(0, around.name);
		}
		return names;
	}

	/**
	 * The expression making an annotation as it is written: an instance of a class of its
	 * own, a member of the generated class the expression stands in, whose methods answer
	 * each element's value -- the one written, or the type's default.
	 * @param unit the file the annotation is written in
	 * @param members where the class is declared, after the ones declared so far
	 */
	String annotationValue(JavaSyntax.Annotation written, JavaClass type, Unit unit, StringBuilder members) {
		return annotationValue(written, type, unit, members, this.annotationsInFull);
	}

	/**
	 * The expression making an annotation, its class equal, hashed and written as a JDK's
	 * where {@code full}.
	 */
	String annotationValue(JavaSyntax.Annotation written, JavaClass type, Unit unit, StringBuilder members,
			boolean full) {
		List<JavaAnnotations.Element> elements = new ArrayList<>();
		List<String> defaulted = new ArrayList<>();
		for (JavaClass.Method element : elements(type)) {
			JavaSyntax.ElementValue value = null;
			for (JavaSyntax.ElementPair pair : written.pairs()) {
				if (pair.name().equals(element.name)) {
					value = pair.value();
				}
			}
			boolean preset = unit(type).tree()
				.elementDefaults()
				.containsKey(java.util.Objects.requireNonNull(element.declaration).pos());
			if (preset) {
				defaulted.add(element.name);
			}
			String answer;
			if (value != null) {
				answer = converted(value, element.returnType, unit, members, full);
			}
			else if (preset) {
				answer = annotationDefaults(type, full) + "." + element.name + "()";
			}
			else {
				throw error(unit.source(), written.pos(), "annotation @" + type.simpleName()
						+ " is missing a default value for the element '" + element.name + "'");
			}
			elements.add(new JavaAnnotations.Element(JavaAnnotations.typeText(element.returnType), element.name, answer,
					element.returnType));
		}
		// a JDK's text: the elements with a default, then those written with none
		List<String> given = new ArrayList<>();
		for (JavaSyntax.ElementPair pair : written.pairs()) {
			if (!defaulted.contains(pair.name()) && !given.contains(pair.name())) {
				given.add(pair.name());
			}
		}
		String simple = written.pos() < 0 ? "R$" + (-1 - written.pos()) : "A$" + written.pos();
		members.append(JavaAnnotations.implementation(simple, type.name, elements,
				JavaAnnotations.textOrder(defaulted, given), full));
		return "new " + simple + "()";
	}

	/**
	 * A value written for an element as an expression of the element's type: the values
	 * between braces, or one value, an array of an array type; an annotation an instance
	 * of its class; any other the expression written.
	 */
	private String converted(JavaSyntax.ElementValue value, JavaType type, Unit unit, StringBuilder members,
			boolean full) {
		if (type instanceof JavaType.ArrayType array) {
			List<JavaSyntax.ElementValue> values = value instanceof JavaSyntax.ElementArray written ? written.values()
					: List.of(value);
			List<String> each = new ArrayList<>();
			for (JavaSyntax.ElementValue one : values) {
				each.add(converted(one, array.element(), unit, members, full));
			}
			return "new " + JavaAnnotations.erasedText(array.element()) + "[] {" + String.join(", ", each) + "}";
		}
		return switch (value) {
			case JavaSyntax.Annotation nested when type instanceof ClassType named
					&& named.declaration().isAnnotation ->
				annotationValue(nested, named.declaration(), unit, members, full);
			case JavaSyntax.ElementText text -> "(" + unit.source().text().substring(text.from(), text.to()) + ")";
			default -> throw error(unit.source(), value instanceof JavaSyntax.Annotation nested ? nested.pos() : 0,
					"incompatible types: an element of type " + type + " is given another kind of value");
		};
	}

	/** The classes of generated Java source, by qualified name. */
	private final Set<String> generated = new HashSet<>();

	/**
	 * Whether a top-level class is generated Java source: written for a class of the
	 * program, it reaches what that class reaches.
	 */
	boolean isGenerated(JavaClass topLevel) {
		return this.generated.contains(topLevel.name);
	}

	/**
	 * Declares a class of generated Java source, lowered as far as the program reaches.
	 */
	void declareGenerated(String name, String simpleName, String text) {
		this.generated.add(name);
		String path = name.replace('.', '/') + ".java";
		Function<String, @Nullable JavaClass> scope = this.localScope;
		List<TypeParameter> parameters = this.methodParameters;
		this.localScope = null;
		this.methodParameters = List.of();
		this.declaringLibrary++;
		try {
			declare(JavaParser.parse(text, path, simpleName), new Source(path, text, JavaClass.Origin.DEPENDENCY));
		}
		finally {
			this.declaringLibrary--;
			this.localScope = scope;
			this.methodParameters = parameters;
		}
	}

	/** The directories below one, it included, whose names start with no dot. */
	private static void directories(Function<String, @Nullable List<String>> listing, String directory,
			Set<String> into) {
		List<String> entries = listing.apply(directory);
		if (entries == null || !into.add(directory)) {
			return;
		}
		for (String entry : entries) {
			if (entry.endsWith("/") && !entry.startsWith(".")) {
				directories(listing, directory + entry, into);
			}
		}
	}

	/** A file read as the class it is named after, or none where it is no Java. */
	private static Optional<CompilationUnit> parsed(JavaFiles.Source source) {
		String file = source.path().substring(source.path().lastIndexOf('/') + 1);
		try {
			return Optional.of(JavaParser.parse(source.text(), source.path(),
					file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file));
		}
		catch (LispReadException ex) {
			return Optional.empty();
		}
	}

	/**
	 * Whether a class or one nested in it is under a class of the simple names given; the
	 * names of those that are are added to them.
	 */
	private static boolean underBundle(ClassDeclaration declared, Set<String> bases) {
		boolean under = false;
		TypeName superclass = declared.superclass();
		if (superclass != null && bases.contains(superclass.name().substring(superclass.name().lastIndexOf('.') + 1))) {
			bases.add(declared.name());
			under = true;
		}
		for (ClassDeclaration nested : declared.nested()) {
			under |= underBundle(nested, bases);
		}
		return under;
	}

	/**
	 * The binary names of the classes nested in a class that may be bundles of a base
	 * name: named as it, or as it and a {@code _}.
	 */
	private static void nestedBundles(JavaClass outer, String base, Set<String> names) {
		for (JavaClass member : outer.members.values()) {
			if (member.binaryName.equals(base) || member.binaryName.startsWith(base + "_")) {
				names.add(member.binaryName);
			}
			nestedBundles(member, base, names);
		}
	}

	/**
	 * The class of a binary name ({@code app.Main$Mine}), or {@code null}: a qualified
	 * name, then the member classes the names after each {@code $} spell.
	 */
	@Nullable JavaClass binaryNamed(String name) {
		String[] parts = name.split("\\$", -1);
		JavaClass found = parts[0].isEmpty() || parts[0].endsWith(".") ? null : qualified(parts[0]);
		for (int i = 1; found != null && i < parts.length; i++) {
			found = member(found, parts[i], new HashSet<>());
		}
		return found;
	}

	/**
	 * The file of a library that declares a class not named after it, or {@code null}. A
	 * library was compiled whole, so its classes reach such a class wherever it is
	 * declared, and its binary jar holds the class file a program is compiled against;
	 * its sources jar holds the class only in the file of another, which javac's
	 * {@code -sourcepath} and a source launcher read only once that class is named. Such
	 * a class is not {@code public} (JLS 7.6), so it is declared in its package's
	 * directory. Every simple name a library's package resolves is first looked for as a
	 * class of the package, so the files of that directory under the roots are read once
	 * a package into the names their texts declare a class of, nested or not, and only a
	 * file declaring the name asked for is lexed, for whether it does so outside every
	 * brace.
	 */
	private JavaFiles.@Nullable Source declaring(String name) {
		int dot = name.lastIndexOf('.');
		String directory = dot < 0 ? "" : name.substring(0, dot).replace('.', '/') + "/";
		String simpleName = name.substring(dot + 1);
		for (JavaFiles.Source file : this.declaredIn.computeIfAbsent(directory, this::declaredNames)
			.getOrDefault(simpleName, List.of())) {
			if (JavaParser.declaresTopLevel(file.text(), simpleName)) {
				return file;
			}
		}
		return null;
	}

	/** The files of a library's packages by the names they declare a class of. */
	private final Map<String, Map<String, List<JavaFiles.Source>>> declaredIn = new HashMap<>();

	/**
	 * The files of a library's package by the names they declare a class of, in the order
	 * of the roots; the first root's of a path.
	 */
	private Map<String, List<JavaFiles.Source>> declaredNames(String directory) {
		Map<String, List<JavaFiles.Source>> declared = new HashMap<>();
		for (JavaFiles.Source file : this.files.libraryPackage(directory)) {
			for (String simpleName : JavaParser.declaredNames(file.text())) {
				declared.computeIfAbsent(simpleName, key -> new ArrayList<>()).add(file);
			}
		}
		return declared;
	}

	/** The classes nobody has that a library's files name, by qualified name. */
	private final Map<String, JavaClass> absent = new HashMap<>();

	/** The classes of a library whose file was refused, with the refusal. */
	private final Map<String, LispReadException> refused = new HashMap<>();

	/** The class each such refusal is of. */
	private final Map<LispReadException, String> refusedNames = new IdentityHashMap<>();

	/**
	 * Takes the classes of a library's file that was refused out of the table: each is a
	 * name from now on, for the declarations read meanwhile that hold it.
	 */
	private void withdraw(Source source, String name, LispReadException reason) {
		for (JavaClass declared : List.copyOf(this.classes.values())) {
			if (declared.source == source) {
				withdrawn(declared, reason);
			}
		}
		// and a class read meanwhile under one of them, as a JVM loads no class whose
		// superclass or interface it cannot load
		boolean more = true;
		while (more) {
			more = false;
			for (JavaClass declared : List.copyOf(this.classes.values())) {
				JavaClass above = declared.superclass != null && declared.superclass.absent != null
						? declared.superclass
						: declared.interfaces.stream().filter(type -> type.absent != null).findFirst().orElse(null);
				if (above != null) {
					withdrawn(declared, java.util.Objects.requireNonNull(above.absent));
					more = true;
				}
			}
		}
		this.refused.put(name, reason);
		this.refusedNames.put(reason, name);
		this.version++;
	}

	/**
	 * Whether a class of a library the program uses was taken out for what its file
	 * names.
	 */
	boolean withdrawn(String name) {
		return this.refused.containsKey(name)
				|| (this.absent.get(name) instanceof JavaClass gone && gone.source != null);
	}

	/** Takes one class out of the table and out of what the program was found to hold. */
	private void withdrawn(JavaClass declared, LispReadException reason) {
		this.classes.remove(declared.name);
		this.lispNames.remove(declared.lispName(), declared);
		declared.absent = reason;
		this.absent.put(declared.name, declared);
		this.lambdaTargets.remove(declared);
		this.taggedLambdas.remove(declared);
		this.made.remove(declared);
		this.used.remove(declared);
	}

	/** The name a class nobody has stands for in a library's file. */
	private JavaClass absent(String name, LispReadException reason) {
		return this.absent.computeIfAbsent(name, key -> {
			JavaClass made = new JavaClass(key, false, false, false, null, null);
			made.absent = reason;
			return made;
		});
	}

	/** The library class of a qualified name, which the front end itself relies on. */
	JavaClass library(String name) {
		return java.util.Objects.requireNonNull(qualified(name), () -> name + " is missing from the library");
	}

	ClassType libraryType(String name) {
		return new ClassType(library(name), List.of());
	}

	/** The type a type written in a class's body means. */
	JavaType type(TypeName written, @Nullable JavaClass context, Unit unit) {
		return resolve(written, context, context, unit);
	}

	/**
	 * The type a type as written means.
	 * @param context the class a class's name is looked for from
	 * @param variables the class a type variable's name is looked for from: the class
	 * itself where its own header is read, which names no member type of its own
	 */
	private JavaType resolve(TypeName written, @Nullable JavaClass context, @Nullable JavaClass variables, Unit unit) {
		JavaType type = switch (written.name()) {
			case "int" -> Primitive.INT;
			case "long" -> Primitive.LONG;
			case "double" -> Primitive.DOUBLE;
			case "boolean" -> Primitive.BOOLEAN;
			case "char" -> Primitive.CHAR;
			case "byte" -> Primitive.BYTE;
			case "short" -> Primitive.SHORT;
			case "float" -> Primitive.FLOAT;
			case "void" -> throw error(unit.source(), written.pos(), "void is not allowed here");
			case "var" -> throw error(unit.source(), written.pos(), "var is not allowed here");
			case "?", "? extends", "? super" ->
				throw error(unit.source(), written.pos(), "a wildcard stands only as a type argument");
			default -> named(written, context, variables, unit);
		};
		for (int i = 0; i < written.dimensions(); i++) {
			type = new ArrayType(type);
		}
		return type;
	}

	/**
	 * The type parameter a simple name means where a type is written, or {@code null}.
	 */
	private @Nullable TypeParameter typeVariable(String name, @Nullable JavaClass context) {
		for (TypeParameter parameter : this.methodParameters) {
			if (parameter.name.equals(name)) {
				return parameter;
			}
		}
		for (JavaClass around = context; around != null; around = around.outer) {
			for (TypeParameter parameter : around.typeParameters) {
				if (parameter.name.equals(name)) {
					return parameter;
				}
			}
			for (TypeParameter parameter : around.scope) {
				if (parameter.name.equals(name)) {
					return parameter;
				}
			}
			if (around.isStatic && !around.isLocal) {
				// a static member reads no type parameter of the class around it
				return null;
			}
		}
		return null;
	}

	private JavaType named(TypeName written, @Nullable JavaClass context, @Nullable JavaClass variables, Unit unit) {
		Source source = unit.source();
		JavaClass local = written.name().indexOf('.') < 0 && this.localScope != null
				? this.localScope.apply(written.name()) : null;
		TypeParameter parameter = written.name().indexOf('.') < 0 && local == null
				? typeVariable(written.name(), variables) : null;
		if (parameter != null) {
			if (!written.arguments().isEmpty() || written.diamond()) {
				throw error(source, written.pos(),
						"unexpected type; required: class, found: type parameter " + parameter.name);
			}
			return new Variable(parameter);
		}
		JavaClass declaration = require(written.name(), written.pos(), context, unit);
		if (declaration.absent != null) {
			// a name: how many parameters it takes is not known
			return new ClassType(declaration, List.of());
		}
		if (written.diamond()) {
			throw error(source, written.pos(), "cannot infer type arguments for " + declaration.simpleName() + " here");
		}
		int declared = declaration.typeParameters.size();
		List<JavaType> enclosing = enclosingArguments(written, declaration, context, variables, unit);
		if (written.arguments().isEmpty()) {
			if (declared > 0 && written.enclosing() != null && !enclosing.isEmpty()) {
				throw error(source, written.pos(), "improperly formed type, some parameters are missing");
			}
			// a class with no parameter, an inner class of a parameterized one, or a raw
			// type
			return new ClassType(declaration, declared > 0 ? List.of() : enclosing);
		}
		if (declared == 0) {
			throw error(source, written.pos(), "type " + declaration.simpleName() + " does not take parameters");
		}
		if (declared != written.arguments().size()) {
			throw error(source, written.pos(), "wrong number of type arguments; required " + declared);
		}
		if (enclosing.isEmpty() && declaration.allTypeParameters().size() > declared) {
			throw error(source, written.pos(), "improperly formed type, type arguments given on a raw type");
		}
		List<JavaType> arguments = new ArrayList<>();
		for (TypeName argument : written.arguments()) {
			JavaType type;
			if (argument.isWildcard()) {
				JavaType bound = argument.arguments().isEmpty() ? null
						: resolve(argument.arguments().get(0), context, variables, unit);
				if (bound != null && bound.isPrimitive()) {
					throw error(source, argument.pos(), "unexpected type; required: reference, found: " + bound);
				}
				type = argument.name().equals("? super") ? new Wildcard(null, bound) : new Wildcard(bound, null);
			}
			else {
				type = resolve(argument, context, variables, unit);
				if (type.isPrimitive()) {
					throw error(source, argument.pos(), "unexpected type; required: reference, found: " + type);
				}
			}
			arguments.add(type);
		}
		ClassType type = new ClassType(declaration, List.copyOf(arguments));
		Runnable check = () -> withinBounds(type, written, unit);
		if (unit.source().dependency()) {
			// a compiler took the library: its bounds hold, and one may be a name
		}
		else if (this.boundChecks != null) {
			this.boundChecks.add(check);
		}
		else {
			check.run();
		}
		arguments.addAll(enclosing);
		return new ClassType(declaration, List.copyOf(arguments));
	}

	/**
	 * Refuses a type argument that is not under the bounds its parameter declares, as a
	 * compiler does. A wildcard is not checked.
	 * @param type the class with its own arguments as written
	 */
	private void withinBounds(ClassType type, TypeName written, Unit unit) {
		List<TypeParameter> parameters = type.declaration().typeParameters;
		Map<TypeParameter, JavaType> bindings = new HashMap<>();
		for (int i = 0; i < parameters.size(); i++) {
			bindings.put(parameters.get(i), type.arguments().get(i));
		}
		for (int i = 0; i < parameters.size(); i++) {
			JavaType argument = type.arguments().get(i);
			if (argument instanceof Wildcard) {
				continue;
			}
			for (JavaType bound : parameters.get(i).bounds) {
				if (!types().isSubtype(argument, types().substituted(bound, bindings))) {
					throw error(unit.source(), written.arguments().get(i).pos(), "type argument " + argument
							+ " is not within bounds of type-variable " + parameters.get(i).name);
				}
			}
		}
	}

	/**
	 * The arguments the type parameters of the classes around an inner member class have
	 * in a type of it: those its enclosing type is written with
	 * ({@code Outer<String>.Inner}), else those of the class around where it is written,
	 * which is or is under the class declaring it ({@code Inner} in {@code Outer<T>}'s
	 * body is {@code Outer<T>.Inner}); none for a raw type.
	 */
	private List<JavaType> enclosingArguments(TypeName written, JavaClass declaration, @Nullable JavaClass context,
			@Nullable JavaClass variables, Unit unit) {
		List<TypeParameter> all = declaration.allTypeParameters();
		int own = declaration.typeParameters.size();
		JavaClass outer = declaration.outer;
		TypeName prefix = written.enclosing();
		if (all.size() == own || outer == null) {
			if (prefix != null && !prefix.arguments().isEmpty() && outer != null) {
				throw error(unit.source(), written.pos(), "cannot select a static class from a parameterized type");
			}
			return List.of();
		}
		if (prefix != null) {
			JavaType type = resolve(prefix, context, variables, unit);
			ClassType above = type instanceof ClassType named ? types().asSuper(named, outer) : null;
			if (above == null) {
				throw error(unit.source(), prefix.pos(), "unexpected type; required: class, found: " + type);
			}
			return above.arguments();
		}
		return enclosingArguments(declaration, variables);
	}

	/**
	 * The arguments the type parameters of the classes around an inner member class have
	 * where its simple name is written: those of the class around that place which is or
	 * is under the class declaring it; none when no such class is around.
	 */
	List<JavaType> enclosingArguments(JavaClass declaration, @Nullable JavaClass from) {
		JavaClass outer = declaration.outer;
		if (outer == null || declaration.allTypeParameters().size() == declaration.typeParameters.size()) {
			return List.of();
		}
		for (JavaClass around = from; around != null; around = around.outer) {
			if (around.isSubclassOf(outer)) {
				ClassType above = types().asSuper(around.self(), outer);
				return above == null ? List.of() : above.arguments();
			}
			if (around.isStatic && !around.isLocal) {
				break;
			}
		}
		return List.of();
	}

	private Relations types() {
		Relations relations = this.relations;
		if (relations == null) {
			relations = this.relationsMaker.apply(this);
			this.relations = relations;
		}
		return relations;
	}

}
