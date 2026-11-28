package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.java.JavaClass.Method;
import am.ik.rontolisp.java.JavaClasses.Unit;
import am.ik.rontolisp.java.JavaSyntax.Annotation;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The Java source that runs the tests of a file: what {@code rontolisp test} makes of a
 * file whose classes carry the test annotations of {@code org.junit.jupiter}. A compiled
 * program has no class file to scan and reads no annotation of a method where it runs, so
 * the tests are found here, while the file is compiled: each class with tests gets a
 * class of generated Java beside it, in its package and under its file's imports, whose
 * methods make an instance, call what runs before and after each test and call the test
 * -- a parameterized one once for each row its sources give --, and the program starts at
 * a generated class calling those in the order the classes are declared. What counts,
 * reports and decides the status is Java source of the library
 * ({@code rontolisp.internal.TestRun}, {@code TestSources}). The annotation types and
 * JUnit's assertions are JUnit Jupiter's own, read from the program's source path as a
 * library's: an annotation of JUnit's this does not run, or an element of one it does not
 * read, is refused by name. {@code .kb/java-frontend.md}, "Tests of a program".
 */
final class JavaTests {

	/**
	 * What the class a run of tests starts at is named after the first class of its file:
	 * it is of the file's package, as a class of the unnamed package is named from no
	 * other.
	 */
	private static final String SUITE = "$Suite";

	private static final String RUN = "rontolisp.internal.TestRun";

	private static final String SOURCES = "rontolisp.internal.TestSources";

	private static final String API = "org.junit.jupiter.api.";

	private static final String PARAMS = "org.junit.jupiter.params.";

	private static final String PROVIDER = PARAMS + "provider.";

	private static final String TEMP_DIR = API + "io.TempDir";

	private static final String INFORMATION = "rontolisp.internal.TestInformation";

	private static final String REPETITION = "rontolisp.internal.TestRepetition";

	private static final String DYNAMIC = "rontolisp.internal.TestDynamic";

	private static final String METHODS = "rontolisp.internal.TestMethods";

	private static final String CLASSES = "rontolisp.internal.TestClasses";

	private static final String TIMEOUT = "rontolisp.internal.TestTimeout";

	private static final String NAMES = "rontolisp.internal.TestNames";

	private static final String NODE = API + "DynamicNode";

	private static final String EXTEND_WITH = API + "extension.ExtendWith";

	/**
	 * AssertJ's extension giving a test its soft assertions, the one extension a run
	 * runs: the generated code does what it does.
	 */
	private static final String SOFT_EXTENSION = "org.assertj.core.api.junit.jupiter.SoftAssertionsExtension";

	private static final String INJECT_SOFT = "org.assertj.core.api.junit.jupiter.InjectSoftAssertions";

	private static final String SOFT_PROVIDER = "org.assertj.core.api.SoftAssertionsProvider";

	private static final String SOFT = "rontolisp.internal.Soft";

	/**
	 * The annotations of JUnit's that a run of tests runs, each with the elements of it
	 * that the run reads; {@code name} of a parameterized test only names its runs. Any
	 * other annotation of a package of JUnit's is refused where it is written, since
	 * leaving it out would change what runs.
	 */
	private static final Map<String, Set<String>> RAN = Map.ofEntries(Map.entry(API + "Test", Set.of()),
			Map.entry(API + "BeforeAll", Set.of()), Map.entry(API + "AfterAll", Set.of()),
			Map.entry(API + "BeforeEach", Set.of()), Map.entry(API + "AfterEach", Set.of()),
			Map.entry(API + "Nested", Set.of()), Map.entry(API + "Disabled", Set.of("value")),
			Map.entry(API + "DisplayName", Set.of("value")),
			Map.entry(API + "io.TempDir", Set.of("cleanup", "factory")),
			Map.entry(API + "RepeatedTest", Set.of("value", "name", "failureThreshold")),
			Map.entry(API + "Tag", Set.of("value")), Map.entry(API + "Tags", Set.of("value")),
			Map.entry(API + "TestInstance", Set.of("value")), Map.entry(API + "TestMethodOrder", Set.of("value")),
			Map.entry(API + "TestClassOrder", Set.of("value")), Map.entry(API + "Order", Set.of("value")),
			Map.entry(API + "TestFactory", Set.of()), Map.entry(API + "Timeout", Set.of("value", "unit", "threadMode")),
			Map.entry(EXTEND_WITH, Set.of("value")),
			Map.entry(PARAMS + "ParameterizedTest", Set.of("name", "quoteTextArguments", "autoCloseArguments")),
			Map.entry(PROVIDER + "ValueSource",
					Set.of("shorts", "bytes", "ints", "longs", "floats", "doubles", "chars", "booleans", "strings",
							"classes")),
			Map.entry(PROVIDER + "CsvSource",
					Set.of("value", "textBlock", "quoteCharacter", "delimiter", "delimiterString", "emptyValue",
							"nullValues", "ignoreLeadingAndTrailingWhitespace", "useHeadersInDisplayName",
							"maxCharsPerColumn", "commentCharacter")),
			Map.entry(PROVIDER + "CsvFileSource",
					Set.of("resources", "files", "encoding", "numLinesToSkip", "quoteCharacter", "delimiter",
							"delimiterString", "emptyValue", "nullValues", "ignoreLeadingAndTrailingWhitespace",
							"useHeadersInDisplayName", "maxCharsPerColumn", "commentCharacter")),
			Map.entry(PROVIDER + "MethodSource", Set.of("value")), Map.entry(PROVIDER + "FieldSource", Set.of("value")),
			Map.entry(PROVIDER + "EnumSource", Set.of("value", "names", "mode", "from", "to")),
			Map.entry(PROVIDER + "NullSource", Set.of()), Map.entry(PROVIDER + "EmptySource", Set.of()),
			Map.entry(PROVIDER + "NullAndEmptySource", Set.of()));

	/** The packages of JUnit's: JUnit Jupiter's, the JUnit Platform's, JUnit 4's. */
	private static final String JUNIT = "org.junit.";

	private final JavaClasses classes;

	/** The files whose annotations were checked against what a run runs. */
	private final Set<Unit> checked = Collections.newSetFromMap(new IdentityHashMap<>());

	/** The members of the class being written. */
	private StringBuilder members = new StringBuilder();

	/** How many methods the class being written has. */
	private int methods;

	/** The file of the class whose run is being written, whose imports its class has. */
	private @Nullable Unit unit;

	/**
	 * The classes holding what the run being written reads of the annotations of a class
	 * of another file -- a class above, an interface --, one for each such class, under
	 * its file's imports.
	 */
	private Map<JavaClass, Elsewhere> elsewhere = new LinkedHashMap<>();

	/** How many classes of {@link #elsewhere} the run has declared. */
	private int elsewhereDeclared;

	private JavaTests(JavaClasses classes) {
		this.classes = classes;
	}

	/**
	 * Declares the classes that run the tests of a file's classes, then of the further
	 * files', and the class the program starts at.
	 * @param declared the top-level classes of the file
	 * @param target what the run is said to be of where it finds no test
	 * @param further the further files of the run, of the file's source tree, in order
	 * @param program the file, where a further file that is not of its tree is refused
	 * @return the class the program starts at
	 */
	static JavaClass declare(JavaClasses classes, List<JavaClass> declared, String target, List<String> further,
			JavaClass.Source program) {
		JavaTests tests = new JavaTests(classes);
		List<List<JavaClass>> files = new ArrayList<>();
		files.add(declared);
		for (String file : further) {
			files.add(classes.treeFile(file, program));
		}
		Set<JavaClass> run = Collections.newSetFromMap(new IdentityHashMap<>());
		StringBuilder calls = new StringBuilder();
		for (List<JavaClass> file : files) {
			if (file.isEmpty()) {
				continue;
			}
			tests.check(classes.unit(file.get(0)));
			for (JavaClass top : file) {
				String holder = run.add(top) ? tests.holder(top) : null;
				if (holder != null) {
					calls.append("        ").append(holder).append(".run(run);\n");
				}
			}
		}
		Unit unit = classes.unit(declared.get(0));
		String simple = declared.get(0).simpleName() + SUITE;
		String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
		classes.declareGenerated(name, simple,
				(unit.packageName() == null ? "" : "package " + unit.packageName() + ";\n") + "public final class "
						+ simple + " {\n    public static void main(String[] args) {\n        " + RUN + " run = new "
						+ RUN + "();\n" + calls + "        System.exit(run.finish(" + literal(target)
						+ "));\n    }\n}\n");
		return java.util.Objects.requireNonNull(classes.qualified(name));
	}

	/**
	 * A test or what runs around tests: a method and its annotations by type, the types
	 * in the order each is first written and a type written more than once with each in
	 * the order written -- the order JUnit reads a repeated source in, which a compiler
	 * puts in one container where the first of them stands.
	 */
	private record Annotated(Method method, Map<String, List<Annotation>> annotations) {

		boolean is(String type) {
			return this.annotations.containsKey(type);
		}

	}

	/** An annotation a class inherits, and the class it is written on. */
	private record Inherited(JavaClass type, Annotation annotation) {

	}

	/**
	 * How the tests of a class or its {@code @Nested} classes are ordered: by a key of
	 * JUnit's own orderers, which the run sorts or shuffles by, or by an orderer of the
	 * program's, made where the class runs.
	 *
	 * @param key {@code OrderAnnotation}, {@code Name}, {@code DisplayName} or
	 * {@code Random}, or {@code null} for an orderer that is made
	 * @param type the orderer's class, by its qualified name
	 */
	private record Orderer(@Nullable String key, String type) {

	}

	/**
	 * A {@code @Timeout}: its value and its unit, as expressions.
	 *
	 * @param checked whether the value may be none JUnit takes, which is not positive
	 */
	private record Timed(String value, String unit, boolean checked) {

	}

	/**
	 * What JUnit gives a parameter of a test, of what runs around tests, or of a class.
	 */
	private enum Given {

		/** A temporary directory, of {@code @TempDir}. */
		DIRECTORY,

		/** The {@code TestInfo} of the test or of the class. */
		INFO,

		/** The {@code RepetitionInfo} of a run of a {@code @RepeatedTest}. */
		REPETITION,

		/** An argument of a parameterized test's row. */
		ROW,

		/** Soft assertions of AssertJ's, where its extension gives them. */
		SOFT

	}

	/**
	 * Refuses, in a file of the program whose classes a run of tests runs, what the run
	 * does not run, each where it is written: an annotation of a package of JUnit's that
	 * it has not, an element of one it has that it does not read, and one written on an
	 * annotation type, whose meaning JUnit finds on what that type annotates.
	 */
	private void check(Unit unit) {
		if (!this.checked.add(unit)) {
			return;
		}
		Set<Integer> onAnnotationTypes = new HashSet<>();
		onAnnotationTypes(unit.tree().classes(), onAnnotationTypes);
		Set<Integer> onClasses = new HashSet<>();
		onClasses(unit.tree().classes(), onClasses);
		for (Annotation written : unit.tree().annotations()) {
			JavaClass type = type(written, null, unit);
			if (type == null || !type.name.startsWith(JUNIT)) {
				continue;
			}
			Set<String> read = RAN.get(type.name);
			if (read == null) {
				throw JavaClasses.unsupported(unit.source(), written.pos(), "the annotation " + type.name);
			}
			if (onAnnotationTypes.contains(written.pos())) {
				throw JavaClasses.unsupported(unit.source(), written.pos(),
						"the annotation " + type.name + " on an annotation type");
			}
			if (type.name.equals(EXTEND_WITH)) {
				// the one extension a run runs, where the run reads it: on a class
				List<String> value = texts(written, "value", unit);
				String named = value.size() == 1 && value.get(0).endsWith(".class")
						? value.get(0).substring(0, value.get(0).length() - ".class".length()).strip() : "";
				JavaClass extension = named.isEmpty() ? null
						: type(new Annotation(written.pos(), named, List.of()), null, unit);
				if (extension == null || !extension.name.equals(SOFT_EXTENSION)) {
					throw JavaClasses.unsupported(unit.source(), written.pos(),
							"an @ExtendWith of " + (value.isEmpty() ? "no extension" : String.join(", ", value)));
				}
				if (!onClasses.contains(written.pos())) {
					throw JavaClasses.unsupported(unit.source(), written.pos(),
							"the annotation " + type.name + " on what is no class");
				}
			}
			for (JavaSyntax.ElementPair pair : written.pairs()) {
				if (!read.contains(pair.name())) {
					throw JavaClasses.unsupported(unit.source(), written.pos(),
							"the element " + pair.name() + " of @" + type.simpleName());
				}
			}
		}
	}

	/** Where the annotations of some classes, and of the classes in them, start. */
	private static void onClasses(List<JavaSyntax.ClassDeclaration> declarations, Set<Integer> into) {
		for (JavaSyntax.ClassDeclaration declaration : declarations) {
			for (Annotation each : declaration.annotations()) {
				into.add(each.pos());
			}
			onClasses(declaration.nested(), into);
		}
	}

	/** Where the annotations of the annotation types among some declarations start. */
	private static void onAnnotationTypes(List<JavaSyntax.ClassDeclaration> declarations, Set<Integer> into) {
		for (JavaSyntax.ClassDeclaration declaration : declarations) {
			if (declaration.modifiers().contains(JavaSyntax.ANNOTATION_TYPE)) {
				for (Annotation each : declaration.annotations()) {
					into.add(each.pos());
				}
			}
			onAnnotationTypes(declaration.nested(), into);
		}
	}

	/**
	 * The annotation type an annotation written in a file names, or {@code null} where it
	 * names none the file sees.
	 */
	private @Nullable JavaClass type(Annotation written, @Nullable JavaClass context, Unit unit) {
		try {
			return this.classes.find(written.name(), context, unit, written.pos());
		}
		catch (LispReadException | JavaClass.Absent ex) {
			return null;
		}
	}

	/** The tests of one class and what runs around them. */
	private static final class Suite {

		final JavaClass type;

		/** The class's suite whose {@code @Nested} class this is, or {@code null}. */
		final @Nullable Suite around;

		final List<Annotated> tests = new ArrayList<>();

		final List<Method> beforeAll = new ArrayList<>();

		final List<Method> afterAll = new ArrayList<>();

		final List<Method> beforeEach = new ArrayList<>();

		final List<Method> afterEach = new ArrayList<>();

		/** The static fields given a temporary directory around the class's tests. */
		final List<JavaClass.Field> staticDirectories = new ArrayList<>();

		/** The instance fields given a temporary directory for each test. */
		final List<JavaClass.Field> directories = new ArrayList<>();

		/**
		 * Whether AssertJ's extension gives the class's tests their soft assertions: it
		 * is named on the class, on one above it or on one around it.
		 */
		boolean soft;

		/** The instance fields given soft assertions for each test. */
		final List<JavaClass.Field> softFields = new ArrayList<>();

		/** The {@code @Nested} classes with tests in it, which run inside its run. */
		final List<Suite> nested = new ArrayList<>();

		/** What the class's constructor is given, one for each of its parameters. */
		final List<Given> constructor = new ArrayList<>();

		/**
		 * The tags of the class, as expressions in the order JUnit gives them: the class
		 * above's, each interface's, its own, then the class around it's.
		 */
		final List<String> tags = new ArrayList<>();

		/** The class's display name, as an expression. */
		String displayName = "";

		boolean disabled;

		/** Whether one instance of the class runs all its tests: PER_CLASS. */
		boolean perClass;

		/** How the tests are ordered: {@code null} as declared. */
		@Nullable Orderer orderer;

		/** How the {@code @Nested} classes are ordered: {@code null} as declared. */
		@Nullable Orderer classOrderer;

		/**
		 * The time each test of the class has, or {@code null} for no limit: the
		 * {@code @Timeout} the class inherits, else the class around it's.
		 */
		@Nullable Timed timeout;

		/** Whether {@link #timeout} is the class's own or one it inherits. */
		boolean timeoutOwn;

		/**
		 * What runs around each test and is given its RepetitionInfo, or {@code null}.
		 */
		@Nullable Method repetitionGiven;

		Suite(JavaClass type, @Nullable Suite around) {
			this.type = type;
			this.around = around;
		}

		/**
		 * How many tests a class that does not run skips: each test and each
		 * {@code @Nested} class one, as surefire counts them.
		 */
		int skipped() {
			return this.tests.size() + this.nested.size();
		}

		/** The suites from the outermost class in, this one last. */
		List<Suite> nest() {
			List<Suite> nest = new ArrayList<>();
			for (Suite each = this; each != null; each = each.around) {
				nest.add(0, each);
			}
			return nest;
		}

		/** Where the class is in its nest: 0 for a top-level class. */
		int depth() {
			return this.around == null ? 0 : this.around.depth() + 1;
		}

	}

	/**
	 * Writes and declares the class running the tests of a top-level class and of the
	 * {@code @Nested} classes in it.
	 * @return its qualified name, or {@code null} for a class with no test
	 */
	private @Nullable String holder(JavaClass top) {
		Unit unit = this.classes.unit(top);
		this.unit = unit;
		this.elsewhere = new LinkedHashMap<>();
		Suite suite = suite(top, null);
		if (suite == null) {
			return null;
		}
		String simple = top.simpleName() + "$Tests";
		String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
		this.members = new StringBuilder();
		this.methods = 0;
		List<String> scopes = new ArrayList<>();
		scopes(suite, scopes);
		String run = run(suite);
		for (Map.Entry<JavaClass, Elsewhere> each : this.elsewhere.entrySet()) {
			Elsewhere other = each.getValue();
			List<String> around = new ArrayList<>();
			for (JavaClass level = each.getKey(); level != null; level = level.outer) {
				around.add(0, level.name);
			}
			this.classes.declareGenerated(other.name, other.simple,
					JavaAnnotations.header(this.classes.unit(each.getKey()), around) + "public final class "
							+ other.simple + " {\n" + other.members + "}\n");
		}
		this.classes.declareGenerated(name, simple,
				JavaAnnotations.header(unit, scopes) + "public final class " + simple + " {\n"
						+ "    public static void run(" + RUN + " run) {\n        " + run + "(run);\n    }\n"
						+ this.members + "}\n");
		return name;
	}

	private static void scopes(Suite suite, List<String> into) {
		into.add(suite.type.name);
		for (Suite nested : suite.nested) {
			scopes(nested, into);
		}
	}

	/**
	 * The class of generated Java holding what a run reads of the annotations of a class
	 * of another file: declared in its package, under its file's imports and the static
	 * members of the class and of those around it, so that a name means there what it
	 * means where it is written.
	 */
	private static final class Elsewhere {

		final String name;

		final String simple;

		final StringBuilder members = new StringBuilder();

		/** The methods answering an expression, by its type and its text. */
		final Map<String, String> answering = new LinkedHashMap<>();

		Elsewhere(String name, String simple) {
			this.name = name;
			this.simple = simple;
		}

	}

	/** The class of {@link #elsewhere} of a class of a file other than the holder's. */
	private Elsewhere elsewhere(JavaClass context) {
		return this.elsewhere.computeIfAbsent(context, key -> {
			String packageName = this.classes.unit(key).packageName();
			String simple = key.topLevel().simpleName() + "$Tests$" + (this.elsewhereDeclared++);
			return new Elsewhere(packageName == null ? simple : packageName + "." + simple, simple);
		});
	}

	/**
	 * An expression written in an annotation of a class, as the run's class writes it:
	 * itself where the class is of the run's file, else a call of a method answering it,
	 * of the class of {@link #elsewhere} of the class.
	 * @param type the expression's type, as Java source names it from any file
	 * @param context the class the annotation is written in, or on
	 */
	private String written(String expression, String type, JavaClass context) {
		if (this.classes.unit(context) == this.unit) {
			return expression;
		}
		Elsewhere other = elsewhere(context);
		String method = other.answering.computeIfAbsent(type + " " + expression, key -> {
			String made = "v$" + other.answering.size();
			other.members.append("    public static ")
				.append(type)
				.append(' ')
				.append(made)
				.append("() {\n        return ")
				.append(expression)
				.append(";\n    }\n");
			return made;
		});
		return other.name + "." + method + "()";
	}

	/** An annotation as an instance, made where it is written ({@link #written}). */
	private String annotationValue(Annotation annotation, JavaClass type, JavaClass context) {
		Unit unit = this.classes.unit(context);
		StringBuilder members = unit == this.unit ? this.members : elsewhere(context).members;
		return written(this.classes.annotationValue(annotation, type, unit, members), type.name, context);
	}

	/**
	 * What a class runs with the {@code @Nested} classes it holds, or {@code null} for a
	 * class that runs nothing: one with no test in it, an abstract class, an interface.
	 */
	private @Nullable Suite suite(JavaClass type, @Nullable Suite around) {
		if (type.isAbstract || type.isInterface) {
			return null;
		}
		Suite suite = collect(type, around);
		for (JavaClass member : type.members.values()) {
			if (member.declaration == null
					|| !annotations(member, member.declaration.annotations(), type).containsKey(API + "Nested")) {
				continue;
			}
			if (member.isStatic) {
				throw JavaClasses.error(this.classes.unit(member).source(), member.declaration.pos(),
						"a @Nested test class must not be static");
			}
			Suite nested = suite(member, suite);
			if (nested != null) {
				suite.nested.add(nested);
			}
		}
		if (suite.tests.isEmpty() && suite.nested.isEmpty()) {
			return null;
		}
		if (around == null) {
			repetitions(suite, null);
		}
		return suite;
	}

	/**
	 * Refuses a RepetitionInfo given to what runs around a test that is not repeated,
	 * which JUnit has none for.
	 * @param given what runs around the tests of the classes around and gives one
	 */
	private void repetitions(Suite suite, @Nullable Method given) {
		Method giving = given != null ? given : suite.repetitionGiven;
		if (giving != null) {
			for (Annotated test : suite.tests) {
				if (!test.is(API + "RepeatedTest")) {
					throw JavaClasses.unsupported(this.classes.unit(giving.owner).source(),
							java.util.Objects.requireNonNull(giving.declaration).pos(),
							"a RepetitionInfo given to what runs around a test that is not a @RepeatedTest");
				}
			}
		}
		for (Suite nested : suite.nested) {
			repetitions(nested, giving);
		}
	}

	/**
	 * The annotations written, by the qualified names of the types the file sees: each
	 * type where it is first written, with every annotation of it in the order written.
	 */
	private Map<String, List<Annotation>> annotations(JavaClass owner, List<Annotation> written,
			@Nullable JavaClass context) {
		Unit unit = this.classes.unit(owner);
		Map<String, List<Annotation>> found = new LinkedHashMap<>();
		for (Annotation each : written) {
			JavaClass type = type(each, context, unit);
			if (type != null) {
				found.computeIfAbsent(type.name, key -> new ArrayList<>()).add(each);
			}
		}
		return found;
	}

	private Map<String, List<Annotation>> annotations(JavaClass owner, int pos) {
		return annotations(owner, this.classes.unit(owner).tree().declarationAnnotations().getOrDefault(pos, List.of()),
				owner);
	}

	/** The annotations written on a class, by type. */
	private Map<String, List<Annotation>> annotations(JavaClass type) {
		return type.declaration == null ? Map.of()
				: annotations(type, type.declaration.annotations(), type.outer == null ? type : type.outer);
	}

	/** Whether a class is one of the program's, whose declarations the run reads. */
	private static boolean isProgram(JavaClass type) {
		return !type.intrinsic && type.source != null && !type.source.library();
	}

	/**
	 * An annotation of a class as JUnit finds one it inherits: written on the class, else
	 * on its interfaces in the order named, each before the interfaces it extends, else
	 * on the class above, found the same way. Measured: the class's own before an
	 * interface's, an interface's before the class above's.
	 * @return the class it is written on and the annotation, or {@code null}
	 */
	private @Nullable Inherited inherited(@Nullable JavaClass type, String annotation) {
		if (type == null || !isProgram(type)) {
			return null;
		}
		List<Annotation> own = annotations(type).get(annotation);
		if (own != null) {
			return new Inherited(type, own.get(0));
		}
		for (JavaClass each : type.interfaces) {
			Inherited found = inherited(each, annotation);
			if (found != null) {
				return found;
			}
		}
		return type.isInterface ? null : inherited(type.superclass, annotation);
	}

	/**
	 * The tags of a class as expressions, in the order JUnit gives them (measured): the
	 * class above's, each interface's in the order named, then the class's own in the
	 * order written. An interface's are those of the interfaces it extends, then its own.
	 */
	private void tags(@Nullable JavaClass type, List<String> into) {
		if (type == null || !isProgram(type)) {
			return;
		}
		if (!type.isInterface) {
			tags(type.superclass, into);
		}
		for (JavaClass each : type.interfaces) {
			tags(each, into);
		}
		tags(type, annotations(type), into);
	}

	/** The tags of {@code @Tag} and {@code @Tags} among annotations, as expressions. */
	private void tags(JavaClass owner, Map<String, List<Annotation>> annotations, List<String> into) {
		Unit unit = this.classes.unit(owner);
		for (Map.Entry<String, List<Annotation>> each : annotations.entrySet()) {
			for (Annotation written : each.getValue()) {
				if (each.getKey().equals(API + "Tag")) {
					for (String text : texts(written, "value", unit)) {
						into.add(written("(" + text + ")", "String", owner));
					}
				}
				else if (each.getKey().equals(API + "Tags")) {
					for (JavaSyntax.ElementPair pair : written.pairs()) {
						List<JavaSyntax.ElementValue> values = pair.value() instanceof JavaSyntax.ElementArray array
								? array.values() : List.of(pair.value());
						for (JavaSyntax.ElementValue value : values) {
							if (!(value instanceof Annotation tag)) {
								throw JavaClasses.unsupported(unit.source(), written.pos(),
										"a @Tags whose tags are not written in it");
							}
							for (String text : texts(tag, "value", unit)) {
								into.add(written("(" + text + ")", "String", owner));
							}
						}
					}
				}
			}
		}
	}

	/**
	 * The classes whose members a class's run reads, from the top: for each class from
	 * the class above all down, the interfaces it names that no class above it does, each
	 * after the interfaces it extends, then the class. Measured: what runs before each
	 * test runs in this order.
	 */
	private static List<JavaClass> levels(JavaClass type) {
		List<JavaClass> chain = new ArrayList<>();
		for (JavaClass above = type; above != null && isProgram(above); above = above.superclass) {
			chain.add(0, above);
		}
		List<JavaClass> levels = new ArrayList<>();
		Set<JavaClass> seen = new HashSet<>();
		for (JavaClass declaring : chain) {
			interfaceLevels(declaring.interfaces, seen, levels);
			levels.add(declaring);
		}
		return levels;
	}

	private static void interfaceLevels(List<JavaClass> interfaces, Set<JavaClass> seen, List<JavaClass> into) {
		for (JavaClass each : interfaces) {
			if (isProgram(each) && seen.add(each)) {
				interfaceLevels(each.interfaces, seen, into);
				into.add(each);
			}
		}
	}

	/**
	 * What a class runs: its methods and the ones it inherits from the classes and the
	 * interfaces of the program above it, in the order of {@link #levels}, each in the
	 * order declared.
	 */
	private Suite collect(JavaClass type, @Nullable Suite around) {
		Suite suite = new Suite(type, around);
		Map<String, List<Annotation>> own = annotations(type);
		suite.disabled = own.containsKey(API + "Disabled");
		Inherited lifecycle = inherited(type, API + "TestInstance");
		if (lifecycle != null) {
			List<String> value = texts(lifecycle.annotation(), "value", this.classes.unit(lifecycle.type()));
			suite.perClass = !value.isEmpty() && value.get(0).endsWith("PER_CLASS");
		}
		suite.orderer = orderer(type, API + "TestMethodOrder", API + "MethodOrderer.", "MethodName",
				around == null ? null : around.orderer);
		suite.classOrderer = orderer(type, API + "TestClassOrder", API + "ClassOrderer.", "ClassName",
				around == null ? null : around.classOrderer);
		Inherited timed = inherited(type, API + "Timeout");
		suite.timeoutOwn = timed != null;
		suite.timeout = timed != null ? timed(timed.annotation(), timed.type())
				: around == null ? null : around.timeout;
		suite.soft = inherited(type, EXTEND_WITH) != null || around != null && around.soft;
		this.soft = suite.soft;
		tags(type, suite.tags);
		if (around != null) {
			suite.tags.addAll(around.tags);
		}
		List<Annotation> displayed = own.getOrDefault(API + "DisplayName", List.of());
		suite.displayName = displayed.isEmpty() ? literal(type.simpleName()) : written(
				kept(texts(displayed.get(0), "value", this.classes.unit(type)).get(0), literal(type.simpleName())),
				"String", type);
		constructor(suite);
		List<JavaClass> levels = levels(type);
		for (int i = 0; i < levels.size(); i++) {
			JavaClass declaring = levels.get(i);
			check(this.classes.unit(declaring));
			for (JavaClass.Field field : declaring.fields) {
				JavaSyntax.Field declaration = field.declaration;
				if (suite.soft && field.owner == declaring && declaration != null
						&& annotations(declaring, declaration.pos()).containsKey(INJECT_SOFT)) {
					JavaClass.Source source = this.classes.unit(declaring).source();
					if (field.isStatic || field.isFinal) {
						throw JavaClasses.unsupported(source, declaration.pos(),
								"an @InjectSoftAssertions field that is " + (field.isStatic ? "static" : "final"));
					}
					provider(field.type, source, declaration.pos());
					suite.softFields.add(field);
				}
				if (field.owner != declaring || declaration == null
						|| !annotations(declaring, declaration.pos()).containsKey(TEMP_DIR)) {
					continue;
				}
				JavaClass.Source source = this.classes.unit(declaring).source();
				if (field.isFinal) {
					throw JavaClasses.error(source, declaration.pos(),
							"the @TempDir field " + field.name + " must not be final");
				}
				directory(field.type, "field " + field.name, source, declaration.pos());
				cleanup(declaring, declaration.pos());
				(field.isStatic ? suite.staticDirectories : suite.directories).add(field);
			}
			for (Method method : declaring.methods) {
				JavaSyntax.Method declaration = method.declaration;
				if (method.owner != declaring || declaration == null || method.name.equals("<init>")
						|| overridden(method, levels.subList(i + 1, levels.size()))) {
					continue;
				}
				Annotated annotated = new Annotated(method, annotations(declaring, declaration.pos()));
				JavaClass.Source source = this.classes.unit(declaring).source();
				boolean repeated = annotated.is(API + "RepeatedTest");
				boolean factory = annotated.is(API + "TestFactory");
				if (annotated.is(API + "Test") || annotated.is(PARAMS + "ParameterizedTest") || repeated || factory) {
					if (method.isPrivate || method.isStatic || !factory && method.returnType != Primitive.VOID) {
						throw JavaClasses.error(source, declaration.pos(),
								"the test method " + method.name + " must not be " + (method.isPrivate ? "private"
										: method.isStatic ? "static" : "one that returns a value"));
					}
					if (factory && !isNodes(method.returnType)) {
						throw JavaClasses.error(source, declaration.pos(),
								"the @TestFactory method " + method.name + " must return a single " + NODE
										+ " or a Stream, Collection, Iterable, Iterator or array of " + NODE);
					}
					if (annotated.is(PARAMS + "ParameterizedTest")) {
						givens(declaring, method, null);
					}
					else if (factory) {
						givens(declaring, method, "a @TestFactory method", Given.DIRECTORY, Given.INFO, Given.SOFT);
					}
					else {
						givens(declaring, method, repeated ? "a @RepeatedTest method" : "a @Test method",
								Given.DIRECTORY, Given.INFO, repeated ? Given.REPETITION : Given.INFO, Given.SOFT);
					}
					suite.tests.add(annotated);
				}
				for (String each : List.of("BeforeAll", "AfterAll", "BeforeEach", "AfterEach")) {
					if (!annotated.is(API + each)) {
						continue;
					}
					boolean all = each.endsWith("All");
					if (all && !method.isStatic && !suite.perClass || !all && method.isStatic || method.isPrivate) {
						throw JavaClasses.error(source, declaration.pos(),
								"the @" + each + " method " + method.name + " must "
										+ (method.isPrivate ? "not be private" : all ? "be static" : "not be static"));
					}
					List<Given> givens = givens(declaring, method, "a @" + each + " method", Given.DIRECTORY,
							Given.INFO, all ? Given.INFO : Given.REPETITION);
					if (givens.contains(Given.REPETITION) && suite.repetitionGiven == null) {
						suite.repetitionGiven = method;
					}
					(switch (each) {
						case "BeforeAll" -> suite.beforeAll;
						case "AfterAll" -> suite.afterAll;
						case "BeforeEach" -> suite.beforeEach;
						default -> suite.afterEach;
					}).add(method);
				}
			}
		}
		return suite;
	}

	/**
	 * How a class's tests, or its {@code @Nested} classes, are ordered: by the orderer of
	 * an annotation inherited as {@link #inherited} finds it, else as the class around it
	 * orders its own (measured), or {@code null} for the order declared, which JUnit's
	 * {@code Default} orderer names too.
	 * @param annotation {@code @TestMethodOrder} or {@code @TestClassOrder}
	 * @param orderers what the names of JUnit's own orderers of the kind start with
	 * @param byName the simple name of JUnit's orderer by name
	 * @param around how the class around orders its own, or {@code null}
	 */
	private @Nullable Orderer orderer(JavaClass type, String annotation, String orderers, String byName,
			@Nullable Orderer around) {
		Inherited found = inherited(type, annotation);
		if (found == null) {
			return around;
		}
		Unit unit = this.classes.unit(found.type());
		List<String> value = texts(found.annotation(), "value", unit);
		String named = value.isEmpty() || !value.get(0).endsWith(".class") ? ""
				: value.get(0).substring(0, value.get(0).length() - ".class".length()).strip();
		JavaClass orderer;
		try {
			orderer = this.classes.find(named, found.type(), unit, found.annotation().pos());
		}
		catch (LispReadException | JavaClass.Absent ex) {
			orderer = null;
		}
		if (orderer == null) {
			throw JavaClasses.error(unit.source(), found.annotation().pos(), "cannot find symbol: class " + named);
		}
		String name = orderer.name;
		if (name.equals(orderers + "Default")) {
			return null;
		}
		if (name.equals(orderers + "OrderAnnotation") || name.equals(orderers + "DisplayName")
				|| name.equals(orderers + "Random")) {
			return new Orderer(name.substring(orderers.length()), name);
		}
		return name.equals(orderers + byName) ? new Orderer("Name", name) : new Orderer(null, name);
	}

	/**
	 * A {@code @Timeout} as written. A test run in a thread of its own is refused: the
	 * thread it leaves running when its time is over would run on where the target has
	 * threads and never get a turn where it has one stack.
	 */
	private Timed timed(Annotation written, JavaClass context) {
		Unit unit = this.classes.unit(context);
		for (String mode : texts(written, "threadMode", unit)) {
			if (!mode.endsWith("SAME_THREAD") && !mode.endsWith("INFERRED")) {
				throw JavaClasses.unsupported(unit.source(), written.pos(),
						"the thread mode " + mode.substring(mode.lastIndexOf('.') + 1) + " of @Timeout");
			}
		}
		List<String> value = texts(written, "value", unit);
		List<String> given = texts(written, "unit", unit);
		if (value.isEmpty()) {
			throw JavaClasses.error(unit.source(), written.pos(),
					"annotation @Timeout is missing a default value for the element 'value'");
		}
		return new Timed(written(value.get(0), "long", context),
				given.isEmpty() ? "java.util.concurrent.TimeUnit.SECONDS"
						: written(given.get(0), "java.util.concurrent.TimeUnit", context),
				!value.get(0).matches("[1-9][0-9_]*[lL]?"));
	}

	/** The {@code @Timeout} written on a method, or {@code null}. */
	private @Nullable Timed timed(Method method) {
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		List<Annotation> written = annotations(method.owner, declaration.pos()).get(API + "Timeout");
		return written == null ? null : timed(written.get(0), method.owner);
	}

	/**
	 * A call with a time for it, the method named as JUnit names it where the time is
	 * over (measured): its name and its parameters' types as a class names itself.
	 */
	private static String timed(@Nullable Timed timeout, Method method, String call) {
		return timeout == null ? call : TIMEOUT + ".timed(" + limit(timeout, method) + ", () -> " + call + ")";
	}

	/** The first arguments of a call with a time for it: the time and the method. */
	private static String limit(Timed timeout, Method method) {
		StringBuilder signature = new StringBuilder(method.name).append('(');
		for (int i = 0; i < method.parameters.size(); i++) {
			signature.append(i == 0 ? "" : ", ").append(className(method.parameters.get(i)));
		}
		return "(" + timeout.value() + "), " + timeout.unit() + ", " + literal(signature.append(')').toString());
	}

	/** A type as its class names itself: an array's by its descriptor. */
	private static String className(JavaType type) {
		if (!(type instanceof ArrayType array)) {
			return typeName(type);
		}
		JavaType element = array.element();
		String dimensions = "[";
		while (element instanceof ArrayType inner) {
			element = inner.element();
			dimensions += "[";
		}
		return dimensions + (element instanceof Primitive primitive ? switch (primitive) {
			case BOOLEAN -> "Z";
			case BYTE -> "B";
			case SHORT -> "S";
			case CHAR -> "C";
			case INT -> "I";
			case LONG -> "J";
			case FLOAT -> "F";
			default -> "D";
		} : "L" + typeName(element) + ";");
	}

	/**
	 * Whether a type is what a {@code @TestFactory} method returns: a node, an array of
	 * nodes, or a stream, an {@code Iterable} or an iterator declared of nodes.
	 */
	private boolean isNodes(JavaType type) {
		if (type instanceof ArrayType array) {
			return isNode(array.element());
		}
		if (isNode(type)) {
			return true;
		}
		for (String holder : List.of("java.util.stream.Stream", "java.lang.Iterable", "java.util.Iterator")) {
			JavaClass above = this.classes.qualified(holder);
			ClassType held = above == null ? null : this.classes.types().asSuper(type, above);
			if (held != null) {
				return held.arguments().size() == 1 && isNode(held.arguments().get(0));
			}
		}
		return false;
	}

	private boolean isNode(@Nullable JavaType type) {
		JavaClass node = this.classes.qualified(NODE);
		return switch (type) {
			case ClassType named -> node != null && named.declaration().isSubclassOf(node);
			case JavaType.Wildcard wildcard -> isNode(wildcard.upper());
			case JavaType.Variable variable ->
				!variable.parameter().bounds.isEmpty() && isNode(variable.parameter().bounds.get(0));
			case null, default -> false;
		};
	}

	/**
	 * Reads what a class's one constructor is given: a temporary directory, the TestInfo.
	 */
	private void constructor(Suite suite) {
		JavaClass type = suite.type;
		Method constructor = null;
		for (Method method : type.methods) {
			if (method.owner != type || !method.name.equals("<init>") || method.declaration == null) {
				continue;
			}
			if (constructor != null) {
				throw JavaClasses.unsupported(this.classes.unit(type).source(), method.declaration.pos(),
						"a test class declaring more than one constructor");
			}
			constructor = method;
		}
		if (constructor == null) {
			return;
		}
		suite.constructor.addAll(givens(type, constructor, "a test class's constructor", Given.DIRECTORY, Given.INFO));
	}

	/**
	 * What JUnit gives each of a method's parameters, each refused where the run gives it
	 * none: a parameterized test's rows give its first parameters, temporary directories
	 * its last ones.
	 * @param what what the method is, where JUnit gives each parameter, or {@code null}
	 * for a parameterized test
	 * @param given what the method's parameters may be given
	 */
	private List<Given> givens(JavaClass declaring, Method method, @Nullable String what, Given... given) {
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		JavaClass.Source source = this.classes.unit(declaring).source();
		List<Given> givens = new ArrayList<>();
		for (int p = 0; p < method.parameters.size(); p++) {
			JavaSyntax.Parameter parameter = declaration.parameters().get(p);
			JavaType type = method.parameters.get(p);
			Given kind = given(declaring, parameter, type);
			if (kind == Given.DIRECTORY) {
				directory(type, "parameter " + parameter.name(), source, parameter.pos());
				cleanup(declaring, parameter.pos());
			}
			if (kind == Given.SOFT) {
				provider(type, source, parameter.pos());
			}
			if (what == null) {
				if (kind == Given.REPETITION) {
					throw JavaClasses.unsupported(source, parameter.pos(),
							"a RepetitionInfo parameter of a @ParameterizedTest method");
				}
				if (kind == Given.ROW && givens.stream().anyMatch(each -> each != Given.ROW)) {
					throw JavaClasses.unsupported(source, parameter.pos(),
							"a parameter given by a row after a parameter JUnit gives");
				}
				// what JUnit would make of its own rather than take from a row
				if (kind == Given.ROW && type instanceof ClassType named
						&& named.declaration().name.startsWith(JUNIT)) {
					throw JavaClasses.unsupported(source, parameter.pos(),
							"a parameter of the type " + named.declaration().name);
				}
			}
			else if (!List.of(given).contains(kind)) {
				throw JavaClasses.unsupported(source, parameter.pos(), "a parameter of " + what);
			}
			givens.add(kind);
		}
		return givens;
	}

	private Given given(JavaClass declaring, JavaSyntax.Parameter parameter, JavaType type) {
		if (isDirectory(declaring, parameter)) {
			return Given.DIRECTORY;
		}
		String name = type instanceof ClassType named ? named.declaration().name : "";
		if (this.soft && type instanceof ClassType named && isProvider(named.declaration())) {
			return Given.SOFT;
		}
		return name.equals(API + "TestInfo") ? Given.INFO
				: name.equals(API + "RepetitionInfo") ? Given.REPETITION : Given.ROW;
	}

	/**
	 * Whether AssertJ's extension gives the tests of the class being read or written
	 * their soft assertions: a parameter of a provider's type is then given one.
	 */
	private boolean soft;

	/** Whether a class is a provider of AssertJ's soft assertions. */
	private boolean isProvider(JavaClass type) {
		JavaClass provider = this.classes.qualified(SOFT_PROVIDER);
		return provider != null && type.isSubclassOf(provider);
	}

	/**
	 * Refuses soft assertions declared of a type the run makes no instance of: AssertJ's
	 * extension makes one with the constructor of no parameter.
	 */
	private void provider(JavaType type, JavaClass.Source source, int pos) {
		if (!(type instanceof ClassType named) || !isProvider(named.declaration()) || named.declaration().isAbstract
				|| named.declaration().isInterface) {
			throw JavaClasses.unsupported(source, pos, "soft assertions of the type " + type);
		}
	}

	private boolean isDirectory(JavaClass declaring, JavaSyntax.Parameter parameter) {
		return annotations(declaring, parameter.pos()).containsKey(TEMP_DIR);
	}

	/**
	 * The cleanup mode of the {@code @TempDir} of a field or a parameter: {@code ALWAYS}
	 * (also {@code DEFAULT}, where a run configures nothing), {@code ON_SUCCESS} or
	 * {@code NEVER}. A factory is refused but JUnit's standard one: a program's own is
	 * given an {@code ExtensionContext}, which a run has none of.
	 */
	private String cleanup(JavaClass declaring, int pos) {
		Unit unit = this.classes.unit(declaring);
		Annotation written = java.util.Objects.requireNonNull(annotations(declaring, pos).get(TEMP_DIR)).get(0);
		for (String factory : texts(written, "factory", unit)) {
			String named = factory.endsWith(".class")
					? factory.substring(0, factory.length() - ".class".length()).strip() : "";
			JavaClass found = named.isEmpty() ? null
					: type(new Annotation(written.pos(), named, List.of()), declaring, unit);
			if (found == null || !found.name.equals(API + "io.TempDirFactory")
					&& !found.name.equals(API + "io.TempDirFactory.Standard")) {
				throw JavaClasses.unsupported(unit.source(), written.pos(), "the factory " + factory + " of @TempDir");
			}
		}
		List<String> modes = texts(written, "cleanup", unit);
		String mode = modes.isEmpty() ? "ALWAYS" : modes.get(0).substring(modes.get(0).lastIndexOf('.') + 1).strip();
		return switch (mode) {
			case "ALWAYS", "DEFAULT" -> "ALWAYS";
			case "ON_SUCCESS", "NEVER" -> mode;
			default -> throw JavaClasses.unsupported(unit.source(), written.pos(),
					"the cleanup mode " + modes.get(0) + " of @TempDir");
		};
	}

	/** Refuses a temporary directory declared of a type JUnit gives none of. */
	private static void directory(JavaType type, String what, JavaClass.Source source, int pos) {
		String name = type instanceof ClassType named ? named.declaration().name : "";
		if (!name.equals("java.nio.file.Path") && !name.equals("java.io.File")) {
			throw JavaClasses.error(source, pos,
					"the @TempDir " + what + " must be of the type java.nio.file.Path or java.io.File");
		}
	}

	/**
	 * A new temporary directory, as the type it is given as, kept in a slot of an array
	 * by its cleanup mode: one deleted when its run is over, one deleted where the run
	 * succeeded, none for one never deleted.
	 */
	private static String directory(JavaType type, Slots slots, String cleanup) {
		String made = switch (cleanup) {
			case "NEVER" -> SOURCES + ".directory()";
			case "ON_SUCCESS" -> "(" + slots.array + "Kept[" + (slots.kept++) + "] = " + SOURCES + ".directory())";
			default -> "(" + slots.array + "[" + (slots.count++) + "] = " + SOURCES + ".directory())";
		};
		return type instanceof ClassType named && named.declaration().name.equals("java.io.File") ? made + ".toFile()"
				: made;
	}

	/**
	 * Whether a class or interface below declares the method again: a class below every
	 * class and interface above it, an interface below those it extends.
	 */
	private static boolean overridden(Method method, List<JavaClass> below) {
		for (JavaClass each : below) {
			if (each.isInterface && !extendsInterface(each, method.owner)) {
				continue;
			}
			for (Method other : each.methods) {
				if (other.owner == each && other.name.equals(method.name)
						&& other.parameters.equals(method.parameters)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean extendsInterface(JavaClass type, JavaClass above) {
		for (JavaClass each : type.interfaces) {
			if (each == above || extendsInterface(each, above)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The parameters through which the method of a class's run, or of one of its tests,
	 * is given the instances shared by the tests of the classes around it and of its own:
	 * those of {@code @TestInstance(PER_CLASS)}.
	 * @param own whether the class's own instance is among them
	 */
	private static String sharedParameters(Suite suite, boolean own) {
		StringBuilder parameters = new StringBuilder();
		List<Suite> nest = suite.nest();
		for (int i = 0; i < nest.size(); i++) {
			if (nest.get(i).perClass && (own || i < nest.size() - 1)) {
				parameters.append(", ").append(nest.get(i).type.name).append(" shared$").append(i);
			}
		}
		return parameters.toString();
	}

	/** The arguments of {@link #sharedParameters}. */
	private static String sharedArguments(Suite suite, boolean own) {
		StringBuilder arguments = new StringBuilder();
		List<Suite> nest = suite.nest();
		for (int i = 0; i < nest.size(); i++) {
			if (nest.get(i).perClass && (own || i < nest.size() - 1)) {
				arguments.append(", shared$").append(i);
			}
		}
		return arguments.toString();
	}

	/**
	 * Writes the method running one class's tests and, inside what the class runs before
	 * and after all of them, those of the {@code @Nested} classes it holds.
	 * @return its name
	 */
	private String run(Suite suite) {
		String name = "run$" + (this.methods++);
		StringBuilder body = new StringBuilder();
		body.append("        run.started(")
			.append(literal(suite.type.binaryName))
			.append(", ")
			.append(suite.disabled || !suite.tests.isEmpty())
			.append(");\n");
		if (suite.disabled) {
			body.append("        run.skipped(").append(suite.skipped()).append(");\n");
		}
		else {
			int checked = body.length();
			StringBuilder tests = new StringBuilder();
			List<String> each = new ArrayList<>();
			for (Annotated test : suite.tests) {
				each.add(test(suite, test));
			}
			// an orderer is handed the tests of a class that has some (measured)
			Orderer orderer = suite.tests.isEmpty() ? null : suite.orderer;
			if (orderer == null) {
				each.forEach(tests::append);
			}
			else if (orderer.key() == null) {
				tests.append("        java.util.List<" + METHODS + ".Test> ordered = new java.util.ArrayList<>();\n");
				for (int i = 0; i < suite.tests.size(); i++) {
					tests.append("        ordered.add(new " + METHODS + ".Test(")
						.append(reflected(suite.tests.get(i).method()))
						.append(", ")
						.append(displayName(suite.tests.get(i)))
						.append(", () -> {\n")
						.append(each.get(i))
						.append("        }));\n");
				}
				tests.append("        " + METHODS + ".ordered(new ")
					.append(orderer.type())
					.append("(), ")
					.append(suite.type.name)
					.append(".class, ordered);\n");
			}
			else {
				tests.append("        java.util.List<" + RUN + ".Ordered> ordered = new java.util.ArrayList<>();\n");
				for (int i = 0; i < suite.tests.size(); i++) {
					tests.append("        ordered.add(new " + RUN + ".Ordered(")
						.append(orderer.key().equals("Random") ? "0, \"\", \"\"" : orderKeys(suite, suite.tests.get(i)))
						.append(", () -> {\n")
						.append(each.get(i))
						.append("        }));\n");
				}
				tests.append("        ").append(sorted(orderer, "ordered")).append(";\n");
			}
			tests.append(nested(suite));
			boolean classInfo = false;
			if (suite.perClass) {
				for (Suite level : suite.nest()) {
					classInfo |= level.constructor.contains(Given.INFO) && (level == suite || !level.perClass);
				}
			}
			for (Method method : suite.beforeAll) {
				classInfo |= gives(method, Given.INFO);
			}
			for (Method method : suite.afterAll) {
				classInfo |= gives(method, Given.INFO);
			}
			if (classInfo) {
				body.append("        " + INFORMATION + " classInfo = ").append(information(suite, null)).append(";\n");
			}
			boolean around = !suite.beforeAll.isEmpty() || !suite.afterAll.isEmpty()
					|| !suite.staticDirectories.isEmpty() || suite.perClass;
			if (around) {
				Slots statics = new Slots("statics");
				// one instance for all the tests, made before what runs before all:
				// where it cannot be made, nothing of the class runs (measured)
				String made = suite.perClass ? instances(suite, false, statics, "classInfo", "            ") : "";
				// the static fields' directories, then what runs before all, each
				// parameter of it given a directory of its own
				StringBuilder before = new StringBuilder();
				for (JavaClass.Field field : suite.staticDirectories) {
					before.append("            ")
						.append(field.owner.name)
						.append('.')
						.append(field.name)
						.append(" = ")
						.append(directory(field.type, statics,
								cleanup(field.owner, java.util.Objects.requireNonNull(field.declaration).pos())))
						.append(";\n");
				}
				for (Method method : suite.beforeAll) {
					before.append("            ")
						.append(call(method.isStatic ? method.owner.name : "self[0]", method, statics, "classInfo"))
						.append(";\n");
				}
				StringBuilder after = new StringBuilder("            Throwable thrown = null;\n");
				for (Method method : suite.afterAll.reversed()) {
					after.append(
							guarded(call(method.isStatic ? method.owner.name : "self[0]", method, statics, "classInfo"),
									"            "));
				}
				// a directory kept where something of the class did not succeed
				// (measured):
				// a test, a class inside, what runs around them
				after.append(statics.closed("            ", "null", "thrown != null || run.unsuccessful() != mark"))
					.append("            if (thrown != null) {\n                throw thrown;\n            }\n");
				body.append(statics.declared("        "));
				if (statics.kept > 0) {
					body.append("        int mark = run.unsuccessful();\n");
				}
				if (suite.perClass) {
					String type = suite.type.name;
					body.append("        ")
						.append(type)
						.append("[] self = new ")
						.append(type)
						.append("[1];\n        if (run.around(() -> {\n")
						.append(made)
						.append("        }, ")
						.append(suite.skipped())
						.append(")) {\n");
					tests.insert(0, "        " + type + " shared$" + suite.depth() + " = self[0];\n");
				}
				body.append("        if (run.around(() -> {\n")
					.append(before)
					.append("        }, ")
					.append(suite.skipped())
					.append(")) {\n")
					.append(tests)
					.append("        }\n        run.around(() -> {\n")
					.append(after)
					.append("        }, 0);\n");
				if (suite.perClass) {
					body.append("        }\n");
				}
			}
			else {
				body.append(tests);
			}
			Timed timeout = suite.timeout;
			if (timeout != null && suite.timeoutOwn && timeout.checked()) {
				// a class whose tests are given no time JUnit takes runs nothing: its
				// error
				body.insert(checked, "        if (run.around(() -> " + TIMEOUT + ".positive(" + timeout.value() + "), "
						+ suite.skipped() + ")) {\n")
					.append("        }\n");
			}
		}
		body.append("        run.finished();\n");
		this.members.append("    private static void ")
			.append(name)
			.append("(" + RUN + " run")
			.append(sharedParameters(suite, false))
			.append(") {\n")
			.append(body)
			.append("    }\n");
		return name;
	}

	/**
	 * The statements running the {@code @Nested} classes of a class, in the order
	 * declared or in the one its orderer of classes gives.
	 */
	private String nested(Suite suite) {
		List<String> runs = new ArrayList<>();
		for (Suite nested : suite.nested) {
			runs.add(run(nested) + "(run" + sharedArguments(nested, false) + ")");
		}
		StringBuilder classes = new StringBuilder();
		Orderer orderer = suite.classOrderer;
		if (orderer == null || runs.isEmpty()) {
			runs.forEach(run -> classes.append("        ").append(run).append(";\n"));
			return classes.toString();
		}
		String list = orderer.key() == null ? CLASSES + ".Nested" : RUN + ".Ordered";
		classes.append("        java.util.List<" + list + "> classes = new java.util.ArrayList<>();\n");
		for (int i = 0; i < runs.size(); i++) {
			Suite nested = suite.nested.get(i);
			String keys;
			if (orderer.key() == null) {
				keys = nested.type.name + ".class, " + nested.displayName;
			}
			else if (orderer.key().equals("Random")) {
				keys = "0, \"\", \"\"";
			}
			else {
				List<Annotation> ordered = annotations(nested.type).getOrDefault(API + "Order", List.of());
				keys = (ordered.isEmpty() ? API + "Order.DEFAULT"
						: written("(" + texts(ordered.get(0), "value", this.classes.unit(nested.type)).get(0) + ")",
								"int", nested.type))
						+ ", "
						+ (orderer.key().equals("DisplayName") ? nested.displayName : literal(nested.type.binaryName))
						+ ", \"\"";
			}
			classes.append("        classes.add(new " + list + "(")
				.append(keys)
				.append(", () -> ")
				.append(runs.get(i))
				.append("));\n");
		}
		return classes
			.append(orderer.key() == null ? "        " + CLASSES + ".ordered(new " + orderer.type() + "(), classes);\n"
					: "        " + sorted(orderer, "classes") + ";\n")
			.toString();
	}

	/** The call running what a list holds in the order of a key of JUnit's orderers. */
	private static String sorted(Orderer orderer, String list) {
		return "Random".equals(orderer.key()) ? RUN + ".shuffled(" + list + ")"
				: RUN + ".ordered(" + list + ", " + "OrderAnnotation".equals(orderer.key()) + ")";
	}

	/**
	 * The arguments a method is reflected by: the class declaring it, its name and its
	 * parameters' types.
	 */
	private static String reflected(Method method) {
		StringBuilder made = new StringBuilder(method.owner.name).append(".class, ")
			.append(literal(method.name))
			.append(", new Class<?>[] {");
		for (int i = 0; i < method.parameters.size(); i++) {
			made.append(i == 0 ? "" : ", ").append(classLiteral(method.parameters.get(i))).append(".class");
		}
		return made.append('}').toString();
	}

	/** Whether JUnit gives one of a method's parameters something of a kind. */
	private boolean gives(Method method, Given kind) {
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		for (int p = 0; p < method.parameters.size(); p++) {
			if (given(method.owner, declaration.parameters().get(p), method.parameters.get(p)) == kind) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What a test is sorted by among its class's: its {@code @Order}, its name or display
	 * name, and its parameters' types.
	 */
	private String orderKeys(Suite suite, Annotated test) {
		Method method = test.method();
		Unit unit = this.classes.unit(method.owner);
		String order = "org.junit.jupiter.api.Order.DEFAULT";
		List<Annotation> ordered = test.annotations().getOrDefault(API + "Order", List.of());
		if (!ordered.isEmpty()) {
			order = written("(" + texts(ordered.get(0), "value", unit).get(0) + ")", "int", method.owner);
		}
		StringBuilder parameters = new StringBuilder();
		for (JavaType each : method.parameters) {
			parameters.append(parameters.isEmpty() ? "" : ", ").append(typeName(each));
		}
		String name = suite.orderer != null && "DisplayName".equals(suite.orderer.key()) ? displayName(test)
				: literal(method.name);
		return order + ", " + name + ", " + literal(parameters.toString());
	}

	/**
	 * A test's display name as an expression: its {@code @DisplayName}, else its name and
	 * the simple names of its parameters' types.
	 */
	private String displayName(Annotated test) {
		String shown = shown(test);
		if (shown != null) {
			return shown;
		}
		return literal(methodDisplayName(test.method()));
	}

	/**
	 * A method's display name where none is given: its name and its parameters' types.
	 */
	private static String methodDisplayName(Method method) {
		StringBuilder name = new StringBuilder(method.name).append('(');
		for (int i = 0; i < method.parameters.size(); i++) {
			name.append(i == 0 ? "" : ", ").append(simpleName(method.parameters.get(i)));
		}
		return name.append(')').toString();
	}

	/** A test's {@code @DisplayName} as an expression, or {@code null}. */
	private @Nullable String shown(Annotated test) {
		List<Annotation> displayed = test.annotations().getOrDefault(API + "DisplayName", List.of());
		if (!displayed.isEmpty() && !displayed.get(0).pairs().isEmpty()
				&& displayed.get(0).pairs().get(0).value() instanceof JavaSyntax.ElementText text) {
			return written(
					kept(this.classes.unit(test.method().owner).source().text().substring(text.from(), text.to()),
							literal(methodDisplayName(test.method()))),
					"String", test.method().owner);
		}
		return null;
	}

	/**
	 * A {@code @DisplayName} as JUnit keeps it, as an expression (measured): stripped, a
	 * blank one the name it stands for, a line break written as {@code <CR>} or
	 * {@code <LF>}, any other control character as U+FFFD -- a string literal escaping
	 * nothing, with no blank at either end, as it is.
	 * @param otherwise the name where it is blank, as an expression
	 */
	private static String kept(String expression, String otherwise) {
		String text = expression.strip();
		boolean plain = text.length() >= 3 && text.startsWith("\"") && text.endsWith("\"") && !text.startsWith("\"\"\"")
				&& text.chars().noneMatch(c -> c == '\\' || c < ' ' || c == 0x7f)
				&& text.indexOf('"', 1) == text.length() - 1 && !Character.isWhitespace(text.charAt(1))
				&& !Character.isWhitespace(text.charAt(text.length() - 2));
		return plain ? "(" + text + ")" : NAMES + ".displayed(" + text + ", " + otherwise + ")";
	}

	/** A type as a class's simple name writes it. */
	private static String simpleName(JavaType type) {
		return switch (type) {
			case ArrayType array -> simpleName(array.element()) + "[]";
			case ClassType named -> named.declaration().simpleName();
			case JavaType.Variable variable ->
				variable.parameter().bounds.isEmpty() ? "Object" : simpleName(variable.parameter().bounds.get(0));
			default -> type.toString();
		};
	}

	/**
	 * A method as {@code Method.toGenericString} writes it, which JUnit names a method by
	 * in what it throws (measured): its modifiers, its type parameters, its return type,
	 * its class's name, its parameters' types with no space between them, the last of a
	 * variable arity with {@code ...}, and what it throws.
	 */
	private static String methodText(Method method) {
		return methodText(method, true);
	}

	/**
	 * The same, or as {@code Method.toString} writes it, which JUnit names a repeated
	 * test by (measured): with no type parameter, each type erased.
	 */
	private static String methodText(Method method, boolean generic) {
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		Set<String> modifiers = declaration.modifiers();
		boolean inInterface = method.owner.isInterface;
		StringBuilder text = new StringBuilder();
		for (String modifier : List.of("public", "protected", "private", "abstract", "static", "final", "synchronized",
				"native", "strictfp")) {
			boolean has = modifiers.contains(modifier)
					|| inInterface && modifier.equals("public") && !modifiers.contains("private")
					|| inInterface && modifier.equals("abstract") && declaration.body() == null;
			if (has) {
				text.append(modifier).append(' ');
			}
		}
		if (inInterface && declaration.body() != null && !modifiers.contains("static")
				&& !modifiers.contains("private")) {
			text.append("default ");
		}
		if (generic && !method.typeParameters.isEmpty()) {
			text.append('<');
			for (int i = 0; i < method.typeParameters.size(); i++) {
				JavaType.TypeParameter parameter = method.typeParameters.get(i);
				text.append(i == 0 ? "" : ",").append(parameter.name);
				for (int b = 0; b < parameter.bounds.size(); b++) {
					text.append(b == 0 ? " extends " : " & ").append(genericName(parameter.bounds.get(b)));
				}
			}
			text.append("> ");
		}
		text.append(generic ? genericName(method.returnType) : typeName(method.returnType))
			.append(' ')
			.append(method.owner.binaryName)
			.append('.')
			.append(method.name)
			.append('(');
		for (int i = 0; i < method.parameters.size(); i++) {
			String type = generic ? genericName(method.parameters.get(i)) : typeName(method.parameters.get(i));
			if (generic && method.varargs && i == method.parameters.size() - 1 && type.endsWith("[]")) {
				type = type.substring(0, type.length() - 2) + "...";
			}
			text.append(i == 0 ? "" : ",").append(type);
		}
		text.append(')');
		for (int i = 0; i < method.thrown.size(); i++) {
			text.append(i == 0 ? " throws " : ",")
				.append(generic ? genericName(method.thrown.get(i)) : typeName(method.thrown.get(i)));
		}
		return text.toString();
	}

	/**
	 * A type as a reflected type's {@code getTypeName} writes it: a nested class's name
	 * with {@code $}, type arguments after a comma and a space.
	 */
	private static String genericName(JavaType type) {
		return switch (type) {
			case ArrayType array -> genericName(array.element()) + "[]";
			case ClassType named -> {
				StringBuilder text = new StringBuilder(named.declaration().binaryName);
				for (int i = 0; i < named.arguments().size(); i++) {
					text.append(i == 0 ? "<" : ", ").append(genericName(named.arguments().get(i)));
				}
				yield named.arguments().isEmpty() ? text.toString() : text.append('>').toString();
			}
			case JavaType.Wildcard wildcard -> wildcard.upper() != null ? "? extends " + genericName(wildcard.upper())
					: wildcard.lower() != null ? "? super " + genericName(wildcard.lower()) : "?";
			case JavaType.Variable variable -> variable.parameter().name;
			default -> type.toString();
		};
	}

	/** A type as a class's name writes it: a nested class's with {@code $}. */
	private static String typeName(JavaType type) {
		return switch (type) {
			case ArrayType array -> typeName(array.element()) + "[]";
			case ClassType named -> named.declaration().binaryName;
			case JavaType.Variable variable -> variable.parameter().bounds.isEmpty() ? "java.lang.Object"
					: typeName(variable.parameter().bounds.get(0));
			default -> type.toString();
		};
	}

	/** A type as a class literal names it. */
	private static String classLiteral(JavaType type) {
		return switch (type) {
			case ArrayType array -> classLiteral(array.element()) + "[]";
			case ClassType named -> named.declaration().name;
			case JavaType.Variable variable -> variable.parameter().bounds.isEmpty() ? "java.lang.Object"
					: classLiteral(variable.parameter().bounds.get(0));
			default -> type.toString();
		};
	}

	/**
	 * The expression making the TestInfo of a test, or of a class where {@code test} is
	 * {@code null}.
	 * @param display the test's display name, as an expression
	 */
	private String information(Suite suite, @Nullable Annotated test, String display) {
		StringBuilder tags = new StringBuilder();
		if (test != null) {
			List<String> own = new ArrayList<>();
			tags(test.method().owner, test.annotations(), own);
			own.forEach(each -> tags.append(tags.isEmpty() ? "" : ", ").append(each));
		}
		suite.tags.forEach(each -> tags.append(tags.isEmpty() ? "" : ", ").append(each));
		StringBuilder made = new StringBuilder("new " + INFORMATION + "(").append(display)
			.append(", new String[] {")
			.append(tags)
			.append("}, ")
			.append(suite.type.name)
			.append(".class, ");
		if (test == null) {
			return made.append("null, null, null)").toString();
		}
		return made.append(reflected(test.method())).append(')').toString();
	}

	private String information(Suite suite, @Nullable Annotated test) {
		return information(suite, test, suite.displayName);
	}

	/**
	 * The slots of the temporary directories of a class's run or of a test's, in an array
	 * of the generated code: what is made in them is deleted when the run is over.
	 */
	private static final class Slots {

		final String array;

		/** How many directories are deleted when the run is over. */
		int count;

		/**
		 * How many directories are deleted where all of the run succeeded, those of
		 * {@code CleanupMode.ON_SUCCESS}: in the array named with {@code Kept}.
		 */
		int kept;

		Slots(String array) {
			this.array = array;
		}

		/** The statements declaring the arrays. */
		String declared(String indent) {
			return (this.count == 0 ? ""
					: indent + "java.nio.file.Path[] " + this.array + " = new java.nio.file.Path[" + this.count
							+ "];\n")
					+ (this.kept == 0 ? "" : indent + "java.nio.file.Path[] " + this.array
							+ "Kept = new java.nio.file.Path[" + this.kept + "];\n");
		}

		/**
		 * The statement closing what a run was given -- a row's arguments, its
		 * directories --, or nothing where there is none.
		 * @param row the row, or {@code null}
		 * @param failed whether the run did not succeed, as an expression
		 */
		String closed(String indent, String row, String failed) {
			if (this.kept > 0) {
				return indent + "thrown = " + SOURCES + ".closed(thrown, " + row + ", "
						+ (this.count == 0 ? "null" : this.array) + ", " + this.array + "Kept, " + failed + ");\n";
			}
			if (this.count > 0) {
				return indent + "thrown = " + SOURCES + ".closed(thrown, " + row + ", " + this.array + ");\n";
			}
			return row.equals("null") ? "" : indent + "thrown = " + SOURCES + ".closed(thrown, " + row + ");\n";
		}

		boolean any() {
			return this.count + this.kept > 0;
		}

	}

	/**
	 * A call of what runs before or after tests, each of its parameters given what JUnit
	 * gives it: a temporary directory of its own, the TestInfo, the RepetitionInfo.
	 */
	private String call(String target, Method method, Slots slots, String info) {
		StringBuilder call = new StringBuilder(target).append('.').append(method.name).append('(');
		call.append(arguments(method, slots, info, null));
		return timed(timed(method), method, call.append(')').toString());
	}

	/**
	 * The arguments of a method JUnit calls, each given what JUnit gives it.
	 * @param row what gives a row's arguments, or {@code null} where none is given
	 */
	private String arguments(Method method, Slots slots, String info, @Nullable String row) {
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		StringBuilder arguments = new StringBuilder();
		boolean alone = row == null;
		for (int p = 0; p < method.parameters.size(); p++) {
			JavaType type = method.parameters.get(p);
			Given kind = given(method.owner, declaration.parameters().get(p), type);
			String argument = switch (kind) {
				case DIRECTORY -> directory(type, slots, cleanup(method.owner, declaration.parameters().get(p).pos()));
				case INFO -> info;
				case REPETITION -> "repetition";
				case ROW -> argument(type, p);
				case SOFT -> SOFT + ".delegating(new " + JavaAnnotations.erasedText(type) + "(), soft)";
			};
			if (!alone && kind != Given.ROW) {
				// the first that JUnit gives, which a row's argument would compete for
				alone = true;
				argument = NAMES + ".alone(row, " + p + ", " + literal(genericName(type) + " arg" + p) + ", "
						+ literal(methodText(method)) + ", " + literal(switch (kind) {
							case DIRECTORY -> "org.junit.jupiter.engine.extension.TempDirectory";
							case INFO -> "org.junit.jupiter.engine.extension.TestInfoParameterResolver";
							default -> SOFT_EXTENSION;
						}) + ", " + argument + ")";
			}
			arguments.append(p == 0 ? "" : ", ").append(argument);
		}
		return arguments.toString();
	}

	/**
	 * A statement that runs whatever was thrown before it, what it throws joining that in
	 * {@code thrown}.
	 */
	private static String guarded(String statement, String indent) {
		return indent + "try {\n" + indent + "    " + statement + ";\n" + indent + "}\n" + indent
				+ "catch (Throwable caught) {\n" + indent + "    thrown = " + RUN + ".then(thrown, caught);\n" + indent
				+ "}\n";
	}

	/** The statements of a class's run that run one test, or one for each row of it. */
	private String test(Suite suite, Annotated test) {
		Method method = test.method();
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		Unit unit = this.classes.unit(method.owner);
		String shown = shown(test);
		if (shown == null) {
			shown = "null";
		}
		if (test.is(API + "Disabled")) {
			return "        run.skipped(1);\n";
		}
		boolean parameterized = test.is(PARAMS + "ParameterizedTest");
		boolean repeated = test.is(API + "RepeatedTest");
		boolean factory = test.is(API + "TestFactory");
		boolean closing = parameterized;
		for (Annotation written : test.annotations().getOrDefault(PARAMS + "ParameterizedTest", List.of())) {
			for (String text : texts(written, "autoCloseArguments", unit)) {
				if (!text.equals("true") && !text.equals("false")) {
					throw JavaClasses.unsupported(unit.source(), written.pos(),
							"an autoCloseArguments of @ParameterizedTest that is no literal");
				}
				closing = text.equals("true");
			}
		}
		// the runs' display names: made where the test gives its runs a name of its
		// own, which may be wrong, or where a TestInfo shows them
		String runName = null;
		String quoted = "true";
		boolean informed = informed(suite, test);
		for (Annotation written : test.annotations().getOrDefault(PARAMS + "ParameterizedTest", List.of())) {
			for (String text : texts(written, "name", unit)) {
				runName = written("(" + text + ")", "String", method.owner);
			}
			for (String text : texts(written, "quoteTextArguments", unit)) {
				quoted = written("(" + text + ")", "boolean", method.owner);
			}
		}
		boolean names = parameterized && (runName != null || informed);
		String invoked = "test$" + (this.methods++);
		String shared = sharedArguments(suite, true);
		this.members.append("    private static void ")
			.append(invoked)
			.append("(")
			.append(factory ? RUN + " run" + (shared.isEmpty() ? "" : ", ") : "")
			.append(sharedParameters(suite, true).replaceFirst("^, ", ""))
			.append(parameterized
					? (shared.isEmpty() ? "" : ", ") + "Object[] row" + (informed ? ", String display" : "")
					: repeated ? (shared.isEmpty() ? "" : ", ") + REPETITION + " repetition" : "")
			.append(") throws Throwable {\n")
			.append(invocation(suite, test, closing,
					repeated ? "repetition.displayName()" : parameterized && informed ? "display" : displayName(test)))
			.append("    }\n");
		String named = literal(method.name);
		if (factory) {
			return "        " + DYNAMIC + ".factory(run, " + named + ", " + shown + ", () -> " + invoked + "(run"
					+ shared + "));\n";
		}
		if (repeated) {
			Annotation written = test.annotations().getOrDefault(API + "RepeatedTest", List.of()).get(0);
			List<String> value = texts(written, "value", unit);
			List<String> pattern = texts(written, "name", unit);
			List<String> threshold = texts(written, "failureThreshold", unit);
			String made = made(suite, test);
			String repeat = "repeat$" + (this.methods++);
			this.members.append("    private static void ")
				.append(repeat)
				.append("(" + RUN + " run")
				.append(sharedParameters(suite, true))
				.append(") {\n        int total = (")
				.append(value.isEmpty() ? "0" : written(value.get(0), "int", method.owner))
				.append(");\n        String pattern = (")
				.append(pattern.isEmpty() ? API + "RepeatedTest.SHORT_DISPLAY_NAME"
						: written(pattern.get(0), "String", method.owner))
				.append(");\n        int threshold = (")
				.append(threshold.isEmpty() ? "Integer.MAX_VALUE" : written(threshold.get(0), "int", method.owner))
				.append(");\n        Throwable wrong = " + REPETITION + ".check(total, pattern, threshold, ")
				.append(literal(methodText(method, false)))
				.append(");\n        if (wrong != null) {\n            run.broken(")
				.append(named)
				.append(", ")
				.append(shown)
				.append(", wrong);\n            return;\n        }\n        int failures = 0;\n")
				.append("        for (int i = 1; i <= total; i++) {\n")
				.append("            " + REPETITION + " repetition = new ")
				.append(REPETITION + "(i, total, failures, threshold, pattern, ")
				.append(displayName(test))
				.append(");\n            if (failures >= threshold) {\n")
				.append("                run.exceeded(")
				.append(named)
				.append(" + \"[\" + i + \"]\", ")
				.append(shown)
				.append(", () -> ")
				.append(made)
				.append("(")
				.append(shared.replaceFirst("^, ", ""))
				.append(shared.isEmpty() ? "" : ", ")
				.append("repetition));\n                continue;\n            }\n            if (run.repetition(")
				.append(named)
				.append(" + \"[\" + i + \"]\", ")
				.append(shown)
				.append(", () -> ")
				.append(invoked)
				.append("(")
				.append(shared.replaceFirst("^, ", ""))
				.append(shared.isEmpty() ? "" : ", ")
				.append("repetition))) {\n                failures++;\n            }\n        }\n    }\n");
			return "        " + repeat + "(run" + shared + ");\n";
		}
		if (!parameterized) {
			return "        run.test(" + named + ", " + shown + ", () -> " + invoked + "("
					+ shared.replaceFirst("^, ", "") + "));\n";
		}
		StringBuilder sources = new StringBuilder();
		for (Map.Entry<String, List<Annotation>> each : test.annotations().entrySet()) {
			for (Annotation written : each.getValue()) {
				sources.append(source(suite, method, each.getKey(), written, unit));
			}
		}
		if (sources.isEmpty()) {
			throw JavaClasses.error(unit.source(), declaration.pos(), "the @ParameterizedTest method " + method.name
					+ " names no source of arguments the experimental Java subset has");
		}
		boolean texts = test.is(PROVIDER + "CsvSource") || test.is(PROVIDER + "CsvFileSource");
		for (Annotation written : test.annotations().getOrDefault(PROVIDER + "ValueSource", List.of())) {
			texts |= !texts(written, "strings", unit).isEmpty();
		}
		for (int p = 0; p < method.parameters.size() && texts; p++) {
			if (method.parameters.get(p) instanceof ClassType parameter
					&& parameter.declaration().name.equals("java.lang.Class")) {
				throw JavaClasses.unsupported(unit.source(), declaration.parameters().get(p).pos(),
						"the conversion of a text to a java.lang.Class");
			}
		}
		String rows = "rows$" + (this.methods++);
		this.members.append("    private static void ")
			.append(rows)
			.append("(" + RUN + " run")
			.append(sharedParameters(suite, true))
			.append(names
					? ") {\n        " + NAMES + ".Rows rows = new " + NAMES + ".Rows();\n        " + NAMES
							+ " names = null;\n"
					: ") {\n        java.util.List<Object[]> rows = new java.util.ArrayList<>();\n")
			.append("        try {\n")
			.append(names ? "            names = new " + NAMES + "("
					+ (runName == null ? literal("[{index}] {argumentSetNameOrArgumentsWithNames}") : runName) + ", "
					+ quoted + ", " + literal(methodText(method)) + ", " + displayName(test) + ", "
					+ method.parameters.size() + ");\n" : "")
			.append(sources)
			.append("            if (rows.isEmpty()) {\n                throw new IllegalStateException(\"Configuration")
			.append(" error: You must configure at least one set of arguments for this @ParameterizedTest\");\n")
			.append("            }\n        }\n        catch (Throwable thrown) {\n            run.broken(")
			.append(named)
			.append(", ")
			.append(shown)
			.append(", thrown);\n            return;\n        }\n")
			.append("        for (int i = 0; i < rows.size(); i++) {\n            Object[] row = rows.get(i);\n")
			.append(!names ? "" : "            String display = null;\n            try {\n"
					+ "                display = names.name(i + 1, row, rows.set(i));\n            }\n"
					+ "            catch (Throwable thrown) {\n                run.broken(" + named + ", " + shown
					+ ", thrown);\n                return;\n            }\n" + "            String given = display;\n")
			.append("            run.test(")
			.append(named)
			.append(" + \"[\" + (i + 1) + \"]\", ")
			.append(shown)
			.append(", () -> ")
			.append(invoked)
			.append("(")
			.append(shared.replaceFirst("^, ", ""))
			.append(shared.isEmpty() ? "" : ", ")
			.append(informed ? "row, given));\n        }\n    }\n" : "row));\n        }\n    }\n");
		return "        " + rows + "(run" + shared + ");\n";
	}

	/**
	 * Writes the method standing for a run of a repeated test that is skipped, the
	 * failures having reached the threshold: the instances are made, then the run is
	 * skipped, as JUnit does (measured: a constructor throwing is the run's error).
	 * @return its name
	 */
	private String made(Suite suite, Annotated test) {
		String name = "made$" + (this.methods++);
		boolean info = false;
		for (Suite level : suite.nest()) {
			info |= level.constructor.contains(Given.INFO) && !level.perClass;
		}
		this.members.append("    private static void ")
			.append(name)
			.append("(")
			.append(sharedParameters(suite, true).replaceFirst("^, ", ""))
			.append(sharedParameters(suite, true).isEmpty() ? "" : ", ")
			.append(REPETITION + " repetition) throws Throwable {\n");
		if (info) {
			this.members.append("        " + INFORMATION + " info = ")
				.append(information(suite, test, "repetition.displayName()"))
				.append(";\n");
		}
		// the constructors' directories are made and deleted, a skipped run counting as
		// one that succeeded (measured)
		Slots slots = new Slots("directories");
		String made = instances(suite, true, slots, "info", "            ");
		if (!slots.any()) {
			this.members.append(instances(suite, true, slots, "info", "        ")).append("    }\n");
			return name;
		}
		this.members.append(slots.declared("        "))
			.append("        Throwable thrown = null;\n        try {\n")
			.append(made)
			.append("        }\n        catch (Throwable caught) {\n            thrown = caught;\n        }\n")
			.append(slots.closed("        ", "null", "thrown != null"))
			.append("        if (thrown != null) {\n            throw thrown;\n        }\n    }\n");
		return name;
	}

	/**
	 * The statements making the instances of a class's nest, from the outermost class in:
	 * one shared by the tests of a class of {@code @TestInstance(PER_CLASS)}, else a new
	 * one, its constructor given what JUnit gives it. The innermost is {@code instance}
	 * in a test's run, {@code self[0]} where a class's shared one is made.
	 * @param test whether they are a test's, else the class's shared instance
	 */
	private String instances(Suite suite, boolean test, Slots slots, String info, String indent) {
		List<Suite> nest = suite.nest();
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < nest.size(); i++) {
			Suite level = nest.get(i);
			boolean last = i == nest.size() - 1;
			String variable = last ? test ? "instance" : "self[0]" : "outer" + i;
			body.append(indent);
			if (!last || test) {
				body.append(level.type.name).append(' ');
			}
			body.append(variable).append(" = ");
			if (level.perClass && (test || !last)) {
				body.append("shared$").append(i);
			}
			else {
				String arguments = constructorArguments(level, slots, info);
				body.append(i == 0 ? "new " + level.type.name + "(" + arguments + ")"
						: "outer" + (i - 1) + ".new " + level.type.simpleName() + "(" + arguments + ")");
			}
			body.append(";\n");
		}
		return body.toString();
	}

	/** The arguments of a class's constructor, each given what JUnit gives it. */
	private String constructorArguments(Suite level, Slots slots, String info) {
		Method constructor = null;
		for (Method method : level.type.methods) {
			if (method.owner == level.type && method.name.equals("<init>") && method.declaration != null) {
				constructor = method;
			}
		}
		return constructor == null || level.constructor.isEmpty() ? "" : arguments(constructor, slots, info, null);
	}

	/**
	 * The body of one run of a test: an instance of its class -- of an inner class, in
	 * instances of the classes around it --, its fields' temporary directories, what runs
	 * before each test from the outermost class in, the test, and what runs after each
	 * from the innermost out, all of it whatever was thrown; then what the test was given
	 * is closed: the arguments of its row where it closes them, its directories.
	 * @param closing whether the arguments of a row that are {@code AutoCloseable} are
	 * closed
	 */
	private String invocation(Suite suite, Annotated test, boolean closing, String display) {
		Method method = test.method();
		List<Suite> nest = suite.nest();
		Slots slots = new Slots("directories");
		this.soft = suite.soft;
		StringBuilder body = new StringBuilder();
		if (informed(suite, test)) {
			body.append("        " + INFORMATION + " info = ").append(information(suite, test, display)).append(";\n");
		}
		body.append(instances(suite, true, slots, "info", "        "));
		List<String> before = new ArrayList<>();
		List<String> after = new ArrayList<>();
		if (suite.soft) {
			// the collector of the test's soft assertions: the ones its fields and its
			// parameters are given hand their failures to it
			body.append("        org.assertj.core.api.DefaultAssertionErrorCollector soft = "
					+ "new org.assertj.core.api.DefaultAssertionErrorCollector();\n");
			before.addAll(softFields(nest));
		}
		for (int i = 0; i < nest.size(); i++) {
			Suite own = nest.get(i);
			String variable = i == nest.size() - 1 ? "instance" : "outer" + i;
			for (JavaClass.Field field : own.directories) {
				// a field of a class above, which one of the level's may hide
				String target = field.owner == own.type ? variable : "((" + field.owner.name + ") " + variable + ")";
				before.add(target + "." + field.name + " = " + directory(field.type, slots,
						cleanup(field.owner, java.util.Objects.requireNonNull(field.declaration).pos())));
			}
			for (Method each : own.beforeEach) {
				before.add(call(variable, each, slots, "info"));
			}
			for (Method each : own.afterEach) {
				after.add(0, call(variable, each, slots, "info"));
			}
		}
		Timed timeout = timed(method);
		if (timeout == null) {
			timeout = suite.timeout;
		}
		if (timeout != null && timeout.checked()) {
			// no time JUnit takes: the test's error once its instance is made (measured)
			body.append("        " + TIMEOUT + ".positive(").append(timeout.value()).append(");\n");
		}
		String called = "instance." + method.name + "("
				+ arguments(method, slots, "info", test.is(PARAMS + "ParameterizedTest") ? "row" : null) + ")";
		String call;
		if (!test.is(API + "TestFactory")) {
			call = timed(timeout, method, called);
		}
		else if (timeout == null) {
			call = DYNAMIC + ".run(run, " + literal(method.name) + ", " + called + ")";
		}
		else {
			// the time is the factory's, not that of the tests it makes (measured)
			call = JavaAnnotations.typeText(method.returnType) + " made = " + TIMEOUT + ".called("
					+ limit(timeout, method) + ", () -> " + called + ");\n            " + DYNAMIC + ".run(run, "
					+ literal(method.name) + ", made)";
		}
		if (suite.soft) {
			// what the test's soft assertions collected is thrown where the test ends
			// without throwing, before what runs after each test (measured)
			call += ";\n            org.assertj.core.api.AbstractSoftAssertions.assertAll(soft)";
		}
		if (before.isEmpty() && after.isEmpty() && !slots.any() && !closing) {
			return body.append("        ").append(call.replace("\n    ", "\n")).append(";\n").toString();
		}
		body.insert(0, slots.declared("        "));
		body.append("        Throwable thrown = null;\n        try {\n");
		for (String each : before) {
			body.append("            ").append(each).append(";\n");
		}
		body.append("            ")
			.append(call)
			.append(";\n        }\n        catch (Throwable caught) {\n")
			.append("            thrown = caught;\n        }\n");
		for (String each : after) {
			body.append(guarded(each, "        "));
		}
		body.append(slots.closed("        ", closing ? "row" : "null", "thrown != null"));
		return body.append("        if (thrown != null) {\n            throw thrown;\n        }\n").toString();
	}

	/**
	 * Whether a run of a test makes its TestInfo: the test, what runs around it or the
	 * constructor of a class whose instance is the run's own is given one.
	 */
	private boolean informed(Suite suite, Annotated test) {
		this.soft = suite.soft;
		boolean info = gives(test.method(), Given.INFO);
		for (Suite level : suite.nest()) {
			info |= level.constructor.contains(Given.INFO) && !level.perClass;
			for (Method each : level.beforeEach) {
				info |= gives(each, Given.INFO);
			}
			for (Method each : level.afterEach) {
				info |= gives(each, Given.INFO);
			}
		}
		return info;
	}

	/**
	 * The statements giving the fields of {@code @InjectSoftAssertions} of a test's
	 * instances their soft assertions, as AssertJ's extension gives them (measured): one
	 * provider for all the fields of one type, handing its failures to the test's
	 * collector; an instance shared by the tests of its class keeps the provider it was
	 * given first.
	 */
	private static List<String> softFields(List<Suite> nest) {
		// each field's type and what it is assigned through, and for each type the first
		// field of a shared instance
		List<String[]> fields = new ArrayList<>();
		Map<String, String> kept = new LinkedHashMap<>();
		for (int i = 0; i < nest.size(); i++) {
			Suite own = nest.get(i);
			String variable = i == nest.size() - 1 ? "instance" : "outer" + i;
			for (JavaClass.Field field : own.softFields) {
				String type = JavaAnnotations.erasedText(field.type);
				String target = (field.owner == own.type ? variable : "((" + field.owner.name + ") " + variable + ")")
						+ "." + field.name;
				fields.add(new String[] { type, target });
				if (own.perClass) {
					kept.putIfAbsent(type, target);
				}
			}
		}
		Map<String, String> providers = new LinkedHashMap<>();
		List<String> statements = new ArrayList<>();
		for (String[] field : fields) {
			String type = field[0];
			String provider = providers.get(type);
			if (provider == null) {
				provider = "soft$" + providers.size();
				providers.put(type, provider);
				String shared = kept.get(type);
				statements.add(type + " " + provider + " = " + SOFT + ".delegating("
						+ (shared == null ? "" : shared + " != null ? " + shared + " : ") + "new " + type
						+ "(), soft)");
			}
			statements.add(field[1] + " = " + provider);
		}
		return statements;
	}

	/**
	 * An argument of a row as the type of the parameter it is given to, converted as
	 * JUnit converts it (measured, JUnit Jupiter 6.0.3): a primitive type or its box, a
	 * text, or an instance of a class type -- else, from a text, by the conversion JUnit
	 * has for a type of the JDK's ({@link #CONVERSIONS}), by an enum's constant of that
	 * name, or by the type's own factory method or constructor ({@link #factory}).
	 */
	private static String argument(JavaType type, int index) {
		String at = "(row, " + index + ")";
		if (type instanceof Primitive primitive) {
			return SOURCES + ".to" + switch (primitive) {
				case BOOLEAN -> "Boolean";
				case BYTE -> "Byte";
				case SHORT -> "Short";
				case CHAR -> "Char";
				case INT -> "Int";
				case LONG -> "Long";
				case FLOAT -> "Float";
				default -> "Double";
			} + at;
		}
		if (type instanceof JavaType.Variable variable) {
			// a type variable's erasure, which the method's call infers it as
			List<JavaType> bounds = variable.parameter().bounds;
			return bounds.isEmpty() ? SOURCES + ".object" + at
					: bounds.get(0) instanceof ClassType bound && !bound.arguments().isEmpty()
							? "((" + bound.declaration().name + ") " + SOURCES + ".object" + at + ")"
							: argument(bounds.get(0), index);
		}
		String text = JavaAnnotations.typeText(type);
		if (!(type instanceof ClassType) && !(type instanceof ArrayType)) {
			return "(" + text + ") " + SOURCES + ".object" + at;
		}
		String erased = JavaAnnotations.erasedText(type);
		if (type instanceof ClassType named) {
			JavaClass declaration = named.declaration();
			String box = switch (declaration.name) {
				case "java.lang.Boolean" -> "boolean";
				case "java.lang.Byte" -> "byte";
				case "java.lang.Short" -> "short";
				case "java.lang.Character" -> "char";
				case "java.lang.Integer" -> "int";
				case "java.lang.Long" -> "long";
				case "java.lang.Float" -> "float";
				case "java.lang.Double" -> "double";
				default -> null;
			};
			if (box != null) {
				return SOURCES + "." + box + "Box" + at;
			}
			if (declaration.name.equals("java.lang.String")) {
				return SOURCES + ".string" + at;
			}
			if (declaration.name.equals("java.lang.Object")) {
				return SOURCES + ".object" + at;
			}
		}
		String conversion = null;
		if (type instanceof ClassType named) {
			JavaClass declaration = named.declaration();
			String made = CONVERSIONS.get(declaration.name);
			conversion = made != null ? made
					: declaration.isEnum ? declaration.name + ".valueOf(text)" : factory(declaration);
		}
		return "((" + text + ") " + SOURCES + ".converted(row, " + index + ", " + SOURCES + ".object" + at
				+ " instanceof " + erased + ", " + literal(typeName(type)) + ", "
				+ (conversion == null ? "null" : "text -> " + conversion) + "))";
	}

	/**
	 * The conversions JUnit has of a text to a type of the JDK's, each an expression of
	 * {@code text} (measured, JUnit Jupiter 6.0.3: a number's underscores dropped, a
	 * locale an IETF language tag). {@code java.lang.Class} has none here: it is refused
	 * where a source gives a text to it.
	 */
	private static final Map<String, String> CONVERSIONS = Map.ofEntries(
			Map.entry("java.io.File", "new java.io.File(text)"),
			Map.entry("java.math.BigDecimal", "new java.math.BigDecimal(text.replace(\"_\", \"\"))"),
			Map.entry("java.math.BigInteger", "new java.math.BigInteger(text.replace(\"_\", \"\"))"),
			Map.entry("java.net.URI", "java.net.URI.create(text)"),
			Map.entry("java.net.URL", "java.net.URI.create(text).toURL()"),
			Map.entry("java.nio.charset.Charset", "java.nio.charset.Charset.forName(text)"),
			Map.entry("java.nio.file.Path", "java.nio.file.Path.of(text)"),
			Map.entry("java.time.Duration", "java.time.Duration.parse(text)"),
			Map.entry("java.time.Instant", "java.time.Instant.parse(text)"),
			Map.entry("java.time.LocalDate", "java.time.LocalDate.parse(text)"),
			Map.entry("java.time.LocalDateTime", "java.time.LocalDateTime.parse(text)"),
			Map.entry("java.time.LocalTime", "java.time.LocalTime.parse(text)"),
			Map.entry("java.time.MonthDay", "java.time.MonthDay.parse(text)"),
			Map.entry("java.time.OffsetDateTime", "java.time.OffsetDateTime.parse(text)"),
			Map.entry("java.time.OffsetTime", "java.time.OffsetTime.parse(text)"),
			Map.entry("java.time.Period", "java.time.Period.parse(text)"),
			Map.entry("java.time.Year", "java.time.Year.parse(text)"),
			Map.entry("java.time.YearMonth", "java.time.YearMonth.parse(text)"),
			Map.entry("java.time.ZonedDateTime", "java.time.ZonedDateTime.parse(text)"),
			Map.entry("java.time.ZoneId", "java.time.ZoneId.of(text)"),
			Map.entry("java.time.ZoneOffset", "java.time.ZoneOffset.of(text)"),
			Map.entry("java.util.Currency", "java.util.Currency.getInstance(text)"),
			Map.entry("java.util.Locale", "java.util.Locale.forLanguageTag(text)"),
			Map.entry("java.util.UUID", "java.util.UUID.fromString(text)"));

	/**
	 * A type's own conversion of a text, as JUnit finds one (measured, JUnit Jupiter
	 * 6.0.3): its one static method that is not private, takes a {@code String} and
	 * returns the type, inherited or not; else its constructor taking a {@code String};
	 * else the same of a {@code CharSequence}. A constructor only of a class that is
	 * neither abstract nor inner. {@code null} for none.
	 */
	private static @Nullable String factory(JavaClass type) {
		for (String parameter : List.of("java.lang.String", "java.lang.CharSequence")) {
			Method found = null;
			int factories = 0;
			Method constructor = null;
			for (Method method : type.methods) {
				if (method.isPrivate || method.parameters.size() != 1
						|| !(method.parameters.get(0) instanceof ClassType taken)
						|| !taken.declaration().name.equals(parameter)) {
					continue;
				}
				if (method.name.equals("<init>")) {
					if (method.owner == type && !type.isAbstract && !type.isInterface && !type.hasOuterInstance()
							&& !type.isLocal) {
						constructor = method;
					}
				}
				else if (method.isStatic && method.returnType instanceof ClassType returned
						&& returned.declaration() == type) {
					found = method;
					factories++;
				}
			}
			if (factories == 1) {
				return java.util.Objects.requireNonNull(found).owner.name + "." + found.name + "(text)";
			}
			if (constructor != null) {
				return "new " + type.name + (type.typeParameters.isEmpty() ? "" : "<>") + "(text)";
			}
		}
		return null;
	}

	/**
	 * The statements adding the rows one annotation of a parameterized test gives, or
	 * nothing for an annotation that is no source of arguments.
	 * @param unit the file the annotation is written in
	 */
	private String source(Suite suite, Method method, String type, Annotation written, Unit unit) {
		String indent = "            ";
		if (!type.startsWith(PROVIDER)) {
			return "";
		}
		JavaClass annotation = java.util.Objects.requireNonNull(this.classes.qualified(type));
		switch (type.substring(PROVIDER.length())) {
			case "ValueSource":
				// a class literal's array read only where one is written
				return indent + SOURCES
						+ (texts(written, "classes", unit).isEmpty() ? ".values(rows, " : ".classes(rows, ")
						+ annotationValue(written, annotation, method.owner) + ");\n";
			case "CsvSource":
				return indent + SOURCES + (headed(written, unit) ? ".headed(rows, " : ".csv(rows, ")
						+ annotationValue(written, annotation, method.owner) + ");\n";
			case "CsvFileSource": {
				// each resource found where the program is lowered, by the test's class
				// as
				// JUnit finds it (measured: a name with no slash in the class's package)
				StringBuilder streams = new StringBuilder();
				for (String resource : texts(written, "resources", unit)) {
					streams.append(streams.isEmpty() ? "" : ", ")
						.append(suite.type.name)
						.append(".class.getResourceAsStream(")
						.append(written("(" + resource + ")", "String", method.owner))
						.append(')');
				}
				return indent + SOURCES + (headed(written, unit) ? ".headedFiles(rows, " : ".files(rows, ")
						+ annotationValue(written, annotation, method.owner) + ", new java.io.InputStream[] {" + streams
						+ "});\n";
			}
			case "NullSource":
				return indent + "rows.add(new Object[] { null });\n";
			case "EmptySource":
				return indent + "rows.add(new Object[] { " + empty(method, written, unit) + " });\n";
			case "NullAndEmptySource":
				return indent + "rows.add(new Object[] { null });\n" + indent + "rows.add(new Object[] { "
						+ empty(method, written, unit) + " });\n";
			case "MethodSource": {
				List<String> names = new ArrayList<>();
				for (String text : texts(written, "value", unit)) {
					String name = text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
							? text.substring(1, text.length() - 1) : null;
					if (name != null && name.contains("#")) {
						names.add(name);
						continue;
					}
					if (name == null || !name.isEmpty() && !name.chars().allMatch(Character::isJavaIdentifierPart)) {
						throw JavaClasses.unsupported(unit.source(), written.pos(),
								"a @MethodSource that does not name a method by a string literal");
					}
					names.add(name.isEmpty() ? method.name : name);
				}
				if (names.isEmpty()) {
					names.add(method.name);
				}
				StringBuilder calls = new StringBuilder();
				for (String name : names) {
					calls.append(indent)
						.append(SOURCES + ".rows(rows, ")
						.append(name.contains("#") ? external(name, false, unit, written)
								: factory(suite, name, unit, written) + "." + name)
						.append("());\n");
				}
				return calls.toString();
			}
			case "EnumSource": {
				List<String> named = texts(written, "value", unit);
				String constants;
				if (!named.isEmpty() && named.get(0).endsWith(".class")) {
					constants = named.get(0).substring(0, named.get(0).length() - ".class".length()).strip();
				}
				else if (named.isEmpty() && !method.parameters.isEmpty()
						&& method.parameters.get(0) instanceof ClassType first && first.declaration().isEnum) {
					constants = first.declaration().name;
				}
				else {
					throw JavaClasses.error(unit.source(), written.pos(),
							"@EnumSource names no enum, and the first parameter of " + method.name + " is none");
				}
				List<String> modes = texts(written, "mode", unit);
				List<String> from = texts(written, "from", unit);
				List<String> to = texts(written, "to", unit);
				JavaClass enumeration;
				try {
					enumeration = this.classes.find(constants, method.owner, unit, written.pos());
				}
				catch (LispReadException | JavaClass.Absent ex) {
					enumeration = null;
				}
				String canonical = literal(enumeration == null ? constants : enumeration.name);
				List<String> names = new ArrayList<>();
				for (String name : texts(written, "names", unit)) {
					names.add(written(name, "String", method.owner));
				}
				return indent + SOURCES + ".constants(rows, " + (enumeration == null ? constants : enumeration.name)
						+ ".values(), new String[] {" + String.join(", ", names) + "}, "
						+ (modes.isEmpty() ? PROVIDER + "EnumSource.Mode.INCLUDE"
								: written("(" + modes.get(0) + ")", PROVIDER + "EnumSource.Mode", method.owner))
						+ ", " + (from.isEmpty() ? "\"\"" : written("(" + from.get(0) + ")", "String", method.owner))
						+ ", " + (to.isEmpty() ? "\"\"" : written("(" + to.get(0) + ")", "String", method.owner)) + ", "
						+ (named.isEmpty() ? literal(PROVIDER + "NullEnum") : canonical) + ", " + canonical + ");\n";
			}
			case "FieldSource": {
				List<String> names = new ArrayList<>();
				for (String text : texts(written, "value", unit)) {
					String name = text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
							? text.substring(1, text.length() - 1) : null;
					if (name == null || name.isBlank()
							|| !name.contains("#") && !name.chars().allMatch(Character::isJavaIdentifierPart)) {
						throw JavaClasses.unsupported(unit.source(), written.pos(),
								"a @FieldSource that does not name a field by a string literal");
					}
					names.add(name);
				}
				if (names.isEmpty()) {
					names.add(method.name);
				}
				StringBuilder reads = new StringBuilder();
				for (String name : names) {
					String external = name.contains("#") ? external(name, true, unit, written) : null;
					reads.append(indent)
						.append(SOURCES + ".field(rows, ")
						.append(external != null ? external : field(suite, name, unit, written))
						.append(", ")
						.append(literal(name.substring(name.indexOf('#') + 1)))
						.append(", ")
						.append(literal(
								external != null ? name.substring(0, name.indexOf('#')) : suite.type.binaryName))
						.append(");\n");
				}
				return reads.toString();
			}
			default:
				return "";
		}
	}

	/**
	 * Whether a CSV source's runs are named by the headers of its first record: a
	 * literal, since the generated code reads only where it is so.
	 */
	private static boolean headed(Annotation written, Unit unit) {
		boolean headed = false;
		for (String text : texts(written, "useHeadersInDisplayName", unit)) {
			if (!text.equals("true") && !text.equals("false")) {
				throw JavaClasses.unsupported(unit.source(), written.pos(),
						"a useHeadersInDisplayName that is no literal");
			}
			headed = text.equals("true");
		}
		return headed;
	}

	/**
	 * A static method or field of another class that a source names by its fully
	 * qualified name, {@code "pkg.Class#member"} (a method's with an empty parameter list
	 * or none), as an expression: what JUnit finds wrong with the name is a compile error
	 * here, in its words (measured).
	 * @param field whether it names a field, else a method, which the expression leaves
	 * uncalled
	 */
	private String external(String name, boolean field, Unit unit, Annotation written) {
		int hash = name.indexOf('#');
		String className = name.substring(0, hash);
		String member = name.substring(hash + 1);
		if (!field && member.endsWith("()")) {
			member = member.substring(0, member.length() - 2);
		}
		if (className.isEmpty() || member.isEmpty() || !member.chars().allMatch(Character::isJavaIdentifierPart)) {
			if (!className.isEmpty() && !field && member.contains("(")) {
				throw JavaClasses.unsupported(unit.source(), written.pos(),
						"a @MethodSource factory method that takes parameters");
			}
			throw JavaClasses.error(unit.source(), written.pos(),
					"[" + name + "] is not a valid fully qualified " + (field ? "field" : "method")
							+ " name: it must start with a fully qualified class name followed by a '#' and then the "
							+ (field ? "field name."
									: "method name, optionally followed by a parameter list enclosed in parentheses."));
		}
		JavaClass type = this.classes.qualified(className.replace('$', '.'));
		if (type == null) {
			throw JavaClasses.error(unit.source(), written.pos(), "Could not load class [" + className + "]");
		}
		if (field) {
			for (JavaClass.Field each : type.fields) {
				if (each.name.equals(member)) {
					if (!each.isStatic) {
						throw JavaClasses.unsupported(unit.source(), written.pos(),
								"a @FieldSource field of another class that is not static");
					}
					return each.owner.name + "." + member;
				}
			}
			throw JavaClasses.error(unit.source(), written.pos(),
					"Could not find field named [" + member + "] in class [" + className + "]");
		}
		for (Method each : type.methods) {
			if (each.name.equals(member) && each.parameters.isEmpty() && each.declaration != null) {
				if (!each.isStatic) {
					throw JavaClasses.error(unit.source(), written.pos(), "Method '" + methodText(each)
							+ "' must be static: local factory methods must be static unless the PER_CLASS "
							+ "@TestInstance lifecycle mode is used; external factory methods must always be static.");
				}
				return each.owner.name + "." + member;
			}
		}
		throw JavaClasses.error(unit.source(), written.pos(),
				"Could not find factory method [" + member + "] in class [" + className + "]");
	}

	/**
	 * What a {@code @MethodSource} method of a test's class is called on: the class for a
	 * static one, the instance its tests share for one that is not, which JUnit calls
	 * only where the class is of {@code @TestInstance(PER_CLASS)}.
	 */
	private String factory(Suite suite, String name, Unit unit, Annotation written) {
		for (JavaClass level : levels(suite.type).reversed()) {
			for (Method method : level.methods) {
				if (method.owner != level || !method.name.equals(name) || !method.parameters.isEmpty()) {
					continue;
				}
				if (!method.isStatic) {
					if (!suite.perClass) {
						throw JavaClasses.unsupported(unit.source(), written.pos(),
								"a @MethodSource method that is not static, of a class that is not of "
										+ "@TestInstance(PER_CLASS)");
					}
					return "shared$" + suite.depth();
				}
				return suite.type.name;
			}
		}
		return suite.type.name;
	}

	/**
	 * A {@code @FieldSource} field of a test's class, as JUnit finds it (measured): of
	 * the class or a class above, not of the class around a {@code @Nested} one; a static
	 * one read on its class, one that is not on the instance the tests share, which JUnit
	 * reads only where the class is of {@code @TestInstance(PER_CLASS)}.
	 * @return the expression reading it
	 */
	private String field(Suite suite, String name, Unit unit, Annotation written) {
		for (JavaClass level : levels(suite.type).reversed()) {
			for (JavaClass.Field field : level.fields) {
				if (field.owner != level || !field.name.equals(name)) {
					continue;
				}
				if (!field.isStatic) {
					if (!suite.perClass) {
						throw JavaClasses.unsupported(unit.source(), written.pos(),
								"a @FieldSource field that is not static, of a class that is not of "
										+ "@TestInstance(PER_CLASS)");
					}
					return "shared$" + suite.depth() + "." + name;
				}
				return level.name + "." + name;
			}
		}
		throw JavaClasses.error(unit.source(), written.pos(),
				"Could not find field named [" + name + "] in class [" + suite.type.binaryName + "]");
	}

	/**
	 * The value of no element a parameter of a type takes: an empty text, array or
	 * collection.
	 */
	private static String empty(Method method, Annotation written, Unit unit) {
		JavaType type = method.parameters.isEmpty() ? null : method.parameters.get(0);
		if (type instanceof ArrayType array) {
			return "new " + JavaAnnotations.erasedText(array.element()) + "[0]";
		}
		String name = type instanceof ClassType named ? named.declaration().name : "";
		return switch (name) {
			case "java.lang.String" -> "\"\"";
			case "java.util.List", "java.util.Collection" -> "java.util.List.of()";
			case "java.util.Set" -> "java.util.Set.of()";
			case "java.util.Map" -> "java.util.Map.of()";
			default -> throw JavaClasses.unsupported(unit.source(), written.pos(),
					"an empty argument for a parameter of the type " + type);
		};
	}

	/**
	 * The expressions written for an element of an annotation, as the stretches of the
	 * file they are: the values between braces, or the one value; none when the element
	 * is not written.
	 */
	private static List<String> texts(Annotation written, String element, Unit unit) {
		List<String> texts = new ArrayList<>();
		for (JavaSyntax.ElementPair pair : written.pairs()) {
			if (!pair.name().equals(element)) {
				continue;
			}
			List<JavaSyntax.ElementValue> values = pair.value() instanceof JavaSyntax.ElementArray array
					? array.values() : List.of(pair.value());
			for (JavaSyntax.ElementValue value : values) {
				if (!(value instanceof JavaSyntax.ElementText text)) {
					throw JavaClasses.error(unit.source(), written.pos(),
							"the element " + element + " of the annotation is given another kind of value");
				}
				texts.add(unit.source().text().substring(text.from(), text.to()).strip());
			}
		}
		return texts;
	}

	/** A text as a string literal of Java source. */
	private static String literal(String text) {
		StringBuilder literal = new StringBuilder("\"");
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> literal.append("\\\"");
				case '\\' -> literal.append("\\\\");
				case '\n' -> literal.append("\\n");
				case '\r' -> literal.append("\\r");
				case '\t' -> literal.append("\\t");
				default -> literal.append(c);
			}
		}
		return literal.append('"').toString();
	}

}
