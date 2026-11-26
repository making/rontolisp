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
			Map.entry(API + "DisplayName", Set.of("value")), Map.entry(API + "io.TempDir", Set.of()),
			Map.entry(API + "RepeatedTest", Set.of("value", "name", "failureThreshold")),
			Map.entry(API + "Tag", Set.of("value")), Map.entry(API + "Tags", Set.of("value")),
			Map.entry(API + "TestInstance", Set.of("value")), Map.entry(API + "TestMethodOrder", Set.of("value")),
			Map.entry(API + "Order", Set.of("value")),
			Map.entry(PARAMS + "ParameterizedTest", Set.of("name", "autoCloseArguments")),
			Map.entry(PROVIDER + "ValueSource",
					Set.of("shorts", "bytes", "ints", "longs", "floats", "doubles", "chars", "booleans", "strings")),
			Map.entry(PROVIDER + "CsvSource",
					Set.of("value", "textBlock", "quoteCharacter", "delimiter", "delimiterString", "emptyValue",
							"nullValues", "ignoreLeadingAndTrailingWhitespace")),
			Map.entry(PROVIDER + "MethodSource", Set.of("value")),
			Map.entry(PROVIDER + "EnumSource", Set.of("value", "names", "mode")),
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
		ROW

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
			for (JavaSyntax.ElementPair pair : written.pairs()) {
				if (!read.contains(pair.name())) {
					throw JavaClasses.unsupported(unit.source(), written.pos(),
							"the element " + pair.name() + " of @" + type.simpleName());
				}
			}
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

		/**
		 * How the tests are ordered: {@code null} as declared, else the name of a
		 * {@code MethodOrderer} of JUnit's.
		 */
		@Nullable String orderer;

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
		Suite suite = suite(top, null);
		if (suite == null) {
			return null;
		}
		Unit unit = this.classes.unit(top);
		String simple = top.simpleName() + "$Tests";
		String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
		this.members = new StringBuilder();
		this.methods = 0;
		List<String> scopes = new ArrayList<>();
		scopes(suite, scopes);
		String run = run(suite);
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
						into.add("(" + text + ")");
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
								into.add("(" + text + ")");
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
		suite.orderer = orderer(type, around);
		tags(type, suite.tags);
		if (around != null) {
			suite.tags.addAll(around.tags);
		}
		List<Annotation> displayed = own.getOrDefault(API + "DisplayName", List.of());
		suite.displayName = displayed.isEmpty() ? literal(type.simpleName())
				: "(" + texts(displayed.get(0), "value", this.classes.unit(type)).get(0) + ")";
		constructor(suite);
		List<JavaClass> levels = levels(type);
		for (int i = 0; i < levels.size(); i++) {
			JavaClass declaring = levels.get(i);
			check(this.classes.unit(declaring));
			for (JavaClass.Field field : declaring.fields) {
				JavaSyntax.Field declaration = field.declaration;
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
				if (annotated.is(API + "Test") || annotated.is(PARAMS + "ParameterizedTest") || repeated) {
					if (method.isPrivate || method.isStatic || method.returnType != Primitive.VOID) {
						throw JavaClasses.error(source, declaration.pos(),
								"the test method " + method.name + " must not be " + (method.isPrivate ? "private"
										: method.isStatic ? "static" : "one that returns a value"));
					}
					if (annotated.is(PARAMS + "ParameterizedTest")) {
						givens(declaring, method, null);
					}
					else {
						givens(declaring, method, repeated ? "a @RepeatedTest method" : "a @Test method",
								Given.DIRECTORY, Given.INFO, repeated ? Given.REPETITION : Given.INFO);
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
	 * The {@code MethodOrderer} of JUnit's a class's tests run in, inherited as
	 * {@link #inherited} finds it, else the class around it's (measured), or {@code null}
	 * for the order declared.
	 */
	private @Nullable String orderer(JavaClass type, @Nullable Suite around) {
		Inherited found = inherited(type, API + "TestMethodOrder");
		if (found == null) {
			return around == null ? null : around.orderer;
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
		String name = orderer == null ? named : orderer.name;
		return switch (name) {
			case API + "MethodOrderer.OrderAnnotation" -> "OrderAnnotation";
			case API + "MethodOrderer.MethodName" -> "MethodName";
			case API + "MethodOrderer.DisplayName" -> "DisplayName";
			default ->
				throw JavaClasses.unsupported(unit.source(), found.annotation().pos(), "the method orderer " + name);
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
		if (suite.perClass && suite.constructor.contains(Given.DIRECTORY)) {
			throw JavaClasses.unsupported(this.classes.unit(type).source(),
					java.util.Objects.requireNonNull(constructor.declaration).pos(),
					"a @TempDir parameter of the constructor of a class of @TestInstance(PER_CLASS)");
		}
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
			}
			if (what == null) {
				if (kind == Given.INFO) {
					throw JavaClasses.unsupported(source, parameter.pos(),
							"a TestInfo parameter of a @ParameterizedTest method");
				}
				if (kind != Given.DIRECTORY && givens.contains(Given.DIRECTORY)) {
					throw JavaClasses.unsupported(source, parameter.pos(),
							"a parameter given by a row after a @TempDir parameter");
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
		return name.equals(API + "TestInfo") ? Given.INFO
				: name.equals(API + "RepetitionInfo") ? Given.REPETITION : Given.ROW;
	}

	private boolean isDirectory(JavaClass declaring, JavaSyntax.Parameter parameter) {
		return annotations(declaring, parameter.pos()).containsKey(TEMP_DIR);
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
	 * A new temporary directory kept in a slot of an array, as the type it is given as.
	 */
	private static String directory(JavaType type, String array, int slot) {
		String made = "(" + array + "[" + slot + "] = " + SOURCES + ".directory())";
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
			StringBuilder tests = new StringBuilder();
			List<String> each = new ArrayList<>();
			for (Annotated test : suite.tests) {
				each.add(test(suite, test));
			}
			if (suite.orderer == null) {
				each.forEach(tests::append);
			}
			else {
				tests.append("        java.util.List<" + RUN + ".Ordered> ordered = new java.util.ArrayList<>();\n");
				for (int i = 0; i < suite.tests.size(); i++) {
					tests.append("        ordered.add(new " + RUN + ".Ordered(")
						.append(orderKeys(suite, suite.tests.get(i)))
						.append(", () -> {\n")
						.append(each.get(i))
						.append("        }));\n");
				}
				tests.append("        " + RUN + ".ordered(ordered, ")
					.append(suite.orderer.equals("OrderAnnotation"))
					.append(");\n");
			}
			for (Suite nested : suite.nested) {
				tests.append("        ")
					.append(run(nested))
					.append("(run")
					.append(sharedArguments(nested, false))
					.append(");\n");
			}
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
						.append(directory(field.type, statics.array, statics.count++))
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
				after
					.append(statics.count == 0 ? ""
							: "            thrown = " + SOURCES + ".closed(thrown, null, statics);\n")
					.append("            if (thrown != null) {\n                throw thrown;\n            }\n");
				if (statics.count > 0) {
					body.append("        java.nio.file.Path[] statics = new java.nio.file.Path[")
						.append(statics.count)
						.append("];\n");
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
			order = "(" + texts(ordered.get(0), "value", unit).get(0) + ")";
		}
		StringBuilder parameters = new StringBuilder();
		for (JavaType each : method.parameters) {
			parameters.append(parameters.isEmpty() ? "" : ", ").append(typeName(each));
		}
		String name = java.util.Objects.equals(suite.orderer, "DisplayName") ? displayName(test) : literal(method.name);
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
		StringBuilder name = new StringBuilder(test.method().name).append('(');
		for (int i = 0; i < test.method().parameters.size(); i++) {
			name.append(i == 0 ? "" : ", ").append(simpleName(test.method().parameters.get(i)));
		}
		return literal(name.append(')').toString());
	}

	/** A test's {@code @DisplayName} as an expression, or {@code null}. */
	private @Nullable String shown(Annotated test) {
		List<Annotation> displayed = test.annotations().getOrDefault(API + "DisplayName", List.of());
		if (!displayed.isEmpty() && !displayed.get(0).pairs().isEmpty()
				&& displayed.get(0).pairs().get(0).value() instanceof JavaSyntax.ElementText text) {
			return "(" + this.classes.unit(test.method().owner).source().text().substring(text.from(), text.to()) + ")";
		}
		return null;
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
		Method method = test.method();
		made.append(method.owner.name).append(".class, ").append(literal(method.name)).append(", new Class<?>[] {");
		for (int i = 0; i < method.parameters.size(); i++) {
			made.append(i == 0 ? "" : ", ").append(classLiteral(method.parameters.get(i))).append(".class");
		}
		return made.append("})").toString();
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

		int count;

		Slots(String array) {
			this.array = array;
		}

	}

	/**
	 * A call of what runs before or after tests, each of its parameters given what JUnit
	 * gives it: a temporary directory of its own, the TestInfo, the RepetitionInfo.
	 */
	private String call(String target, Method method, Slots slots, String info) {
		StringBuilder call = new StringBuilder(target).append('.').append(method.name).append('(');
		call.append(arguments(method, slots, info, null));
		return call.append(')').toString();
	}

	/**
	 * The arguments of a method JUnit calls, each given what JUnit gives it.
	 * @param row what gives a row's arguments, or {@code null} where none is given
	 */
	private String arguments(Method method, Slots slots, String info, @Nullable String row) {
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		StringBuilder arguments = new StringBuilder();
		for (int p = 0; p < method.parameters.size(); p++) {
			JavaType type = method.parameters.get(p);
			arguments.append(p == 0 ? "" : ", ")
				.append(switch (given(method.owner, declaration.parameters().get(p), type)) {
					case DIRECTORY -> directory(type, slots.array, slots.count++);
					case INFO -> info;
					case REPETITION -> "repetition";
					case ROW -> argument(type, p);
				});
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
		String invoked = "test$" + (this.methods++);
		String shared = sharedArguments(suite, true);
		this.members.append("    private static void ")
			.append(invoked)
			.append("(")
			.append(sharedParameters(suite, true).replaceFirst("^, ", ""))
			.append(parameterized ? (shared.isEmpty() ? "" : ", ") + "Object[] row"
					: repeated ? (shared.isEmpty() ? "" : ", ") + REPETITION + " repetition" : "")
			.append(") throws Throwable {\n")
			.append(invocation(suite, test, closing))
			.append("    }\n");
		String named = literal(method.name);
		if (repeated) {
			Annotation written = test.annotations().getOrDefault(API + "RepeatedTest", List.of()).get(0);
			List<String> value = texts(written, "value", unit);
			List<String> pattern = texts(written, "name", unit);
			List<String> threshold = texts(written, "failureThreshold", unit);
			StringBuilder signature = new StringBuilder("void ").append(method.owner.binaryName)
				.append('.')
				.append(method.name)
				.append('(');
			for (int i = 0; i < method.parameters.size(); i++) {
				signature.append(i == 0 ? "" : ",").append(typeName(method.parameters.get(i)));
			}
			String made = made(suite, test);
			String repeat = "repeat$" + (this.methods++);
			this.members.append("    private static void ")
				.append(repeat)
				.append("(" + RUN + " run")
				.append(sharedParameters(suite, true))
				.append(") {\n        int total = (")
				.append(value.isEmpty() ? "0" : value.get(0))
				.append(");\n        String pattern = (")
				.append(pattern.isEmpty() ? API + "RepeatedTest.SHORT_DISPLAY_NAME" : pattern.get(0))
				.append(");\n        int threshold = (")
				.append(threshold.isEmpty() ? "Integer.MAX_VALUE" : threshold.get(0))
				.append(");\n        Throwable wrong = " + REPETITION + ".check(total, pattern, threshold, ")
				.append(literal(signature.append(')').toString()))
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
				.append("                run.repetition(")
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
		String rows = "rows$" + (this.methods++);
		this.members.append("    private static void ")
			.append(rows)
			.append("(" + RUN + " run")
			.append(sharedParameters(suite, true))
			.append(") {\n        java.util.List<Object[]> rows = new java.util.ArrayList<>();\n")
			.append("        try {\n")
			.append(sources)
			.append("            if (rows.isEmpty()) {\n                throw new IllegalStateException(\"Configuration")
			.append(" error: You must configure at least one set of arguments for this @ParameterizedTest\");\n")
			.append("            }\n        }\n        catch (Throwable thrown) {\n            run.broken(")
			.append(named)
			.append(", ")
			.append(shown)
			.append(", thrown);\n            return;\n        }\n")
			.append("        for (int i = 0; i < rows.size(); i++) {\n            Object[] row = rows.get(i);\n")
			.append("            run.test(")
			.append(named)
			.append(" + \"[\" + (i + 1) + \"]\", ")
			.append(shown)
			.append(", () -> ")
			.append(invoked)
			.append("(")
			.append(shared.replaceFirst("^, ", ""))
			.append(shared.isEmpty() ? "" : ", ")
			.append("row));\n        }\n    }\n");
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
			if (level.constructor.contains(Given.DIRECTORY) && !level.perClass) {
				JavaSyntax.Method declaration = java.util.Objects.requireNonNull(test.method().declaration);
				throw JavaClasses.unsupported(this.classes.unit(test.method().owner).source(), declaration.pos(),
						"a @RepeatedTest with a failureThreshold of a class whose constructor takes a @TempDir");
			}
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
		this.members.append(instances(suite, true, new Slots("directories"), "info", "        "))
			.append("        throw new org.opentest4j.TestAbortedException();\n    }\n");
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
	private String invocation(Suite suite, Annotated test, boolean closing) {
		Method method = test.method();
		List<Suite> nest = suite.nest();
		Slots slots = new Slots("directories");
		boolean info = gives(method, Given.INFO);
		for (Suite level : nest) {
			info |= level.constructor.contains(Given.INFO) && !level.perClass;
			for (Method each : level.beforeEach) {
				info |= gives(each, Given.INFO);
			}
			for (Method each : level.afterEach) {
				info |= gives(each, Given.INFO);
			}
		}
		StringBuilder body = new StringBuilder();
		if (info) {
			body.append("        " + INFORMATION + " info = ")
				.append(information(suite, test,
						test.is(API + "RepeatedTest") ? "repetition.displayName()" : displayName(test)))
				.append(";\n");
		}
		body.append(instances(suite, true, slots, "info", "        "));
		List<String> before = new ArrayList<>();
		List<String> after = new ArrayList<>();
		for (int i = 0; i < nest.size(); i++) {
			Suite own = nest.get(i);
			String variable = i == nest.size() - 1 ? "instance" : "outer" + i;
			for (JavaClass.Field field : own.directories) {
				// a field of a class above, which one of the level's may hide
				String target = field.owner == own.type ? variable : "((" + field.owner.name + ") " + variable + ")";
				before.add(target + "." + field.name + " = " + directory(field.type, slots.array, slots.count++));
			}
			for (Method each : own.beforeEach) {
				before.add(call(variable, each, slots, "info"));
			}
			for (Method each : own.afterEach) {
				after.add(0, call(variable, each, slots, "info"));
			}
		}
		String call = "instance." + method.name + "(" + arguments(method, slots, "info", "row") + ")";
		if (before.isEmpty() && after.isEmpty() && slots.count == 0 && !closing) {
			return body.append("        ").append(call).append(";\n").toString();
		}
		if (slots.count > 0) {
			body.insert(0, "        java.nio.file.Path[] directories = new java.nio.file.Path[" + slots.count + "];\n");
		}
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
		if (closing || slots.count > 0) {
			body.append("        thrown = " + SOURCES + ".closed(thrown, ")
				.append(closing ? "row" : "null")
				.append(slots.count > 0 ? ", directories" : "")
				.append(");\n");
		}
		return body.append("        if (thrown != null) {\n            throw thrown;\n        }\n").toString();
	}

	/** An argument of a row as the type of the parameter it is given to. */
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
		String text = JavaAnnotations.typeText(type);
		if (type instanceof ClassType named) {
			JavaClass declaration = named.declaration();
			String unboxed = switch (declaration.name) {
				case "java.lang.Boolean" -> "Boolean";
				case "java.lang.Byte" -> "Byte";
				case "java.lang.Short" -> "Short";
				case "java.lang.Character" -> "Char";
				case "java.lang.Integer" -> "Int";
				case "java.lang.Long" -> "Long";
				case "java.lang.Float" -> "Float";
				case "java.lang.Double" -> "Double";
				default -> null;
			};
			if (unboxed != null) {
				return "(" + SOURCES + ".isNull" + at + " ? null : (" + text + ") " + SOURCES + ".to" + unboxed + at
						+ ")";
			}
			if (declaration.name.equals("java.lang.String")) {
				return SOURCES + ".string" + at;
			}
			if (declaration.isEnum) {
				return SOURCES + ".constant(row, " + index + ", " + declaration.name + ".values())";
			}
		}
		return "(" + text + ") " + SOURCES + ".object" + at;
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
				return indent + SOURCES + ".values(rows, "
						+ this.classes.annotationValue(written, annotation, unit, this.members) + ");\n";
			case "CsvSource":
				return indent + SOURCES + ".csv(rows, "
						+ this.classes.annotationValue(written, annotation, unit, this.members) + ");\n";
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
					if (name == null || !name.isEmpty() && !name.chars().allMatch(Character::isJavaIdentifierPart)) {
						throw JavaClasses.unsupported(unit.source(), written.pos(),
								"a @MethodSource that does not name a method of the test's class by a string literal");
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
						.append(factory(suite, name, unit, written))
						.append('.')
						.append(name)
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
				return indent + SOURCES + ".constants(rows, " + constants + ".values(), new String[] {"
						+ String.join(", ", texts(written, "names", unit)) + "}, "
						+ (modes.isEmpty() ? PROVIDER + "EnumSource.Mode.INCLUDE" : "(" + modes.get(0) + ")") + ");\n";
			}
			default:
				return "";
		}
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
