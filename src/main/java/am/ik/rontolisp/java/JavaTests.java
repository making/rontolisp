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

		boolean disabled;

		Suite(JavaClass type) {
			this.type = type;
		}

		/**
		 * How many tests a class that does not run skips: each test and each
		 * {@code @Nested} class one, as surefire counts them.
		 */
		int skipped() {
			return this.tests.size() + this.nested.size();
		}

	}

	/**
	 * Writes and declares the class running the tests of a top-level class and of the
	 * {@code @Nested} classes in it.
	 * @return its qualified name, or {@code null} for a class with no test
	 */
	private @Nullable String holder(JavaClass top) {
		Suite suite = suite(top);
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
	private @Nullable Suite suite(JavaClass type) {
		if (type.isAbstract || type.isInterface) {
			return null;
		}
		Suite suite = collect(type);
		for (JavaClass member : type.members.values()) {
			if (member.declaration == null
					|| !annotations(member, member.declaration.annotations(), type).containsKey(API + "Nested")) {
				continue;
			}
			if (member.isStatic) {
				throw JavaClasses.error(this.classes.unit(member).source(), member.declaration.pos(),
						"a @Nested test class must not be static");
			}
			Suite nested = suite(member);
			if (nested != null) {
				suite.nested.add(nested);
			}
		}
		return suite.tests.isEmpty() && suite.nested.isEmpty() ? null : suite;
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

	/**
	 * What a class runs: its methods and the ones it inherits from the classes of the
	 * program above it, a class above first, each in the order declared.
	 */
	private Suite collect(JavaClass type) {
		Suite suite = new Suite(type);
		if (type.declaration != null) {
			suite.disabled = annotations(type, type.declaration.annotations(), type.outer == null ? type : type.outer)
				.containsKey(API + "Disabled");
		}
		List<JavaClass> chain = new ArrayList<>();
		for (JavaClass above = type; above != null && !above.intrinsic && above.source != null
				&& !above.source.library(); above = above.superclass) {
			chain.add(0, above);
		}
		for (JavaClass declaring : chain) {
			interfaces(declaring.interfaces, new HashSet<>());
		}
		for (int i = 0; i < chain.size(); i++) {
			JavaClass declaring = chain.get(i);
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
						|| overridden(method, chain.subList(i + 1, chain.size()))) {
					continue;
				}
				Annotated annotated = new Annotated(method, annotations(declaring, declaration.pos()));
				JavaClass.Source source = this.classes.unit(declaring).source();
				if (annotated.is(API + "Test") || annotated.is(PARAMS + "ParameterizedTest")) {
					if (method.isPrivate || method.isStatic || method.returnType != Primitive.VOID) {
						throw JavaClasses.error(source, declaration.pos(),
								"the test method " + method.name + " must not be " + (method.isPrivate ? "private"
										: method.isStatic ? "static" : "one that returns a value"));
					}
					boolean parameterized = annotated.is(PARAMS + "ParameterizedTest");
					int directories = directories(declaring, method, parameterized ? null : "a @Test method");
					for (int p = 0; p < method.parameters.size() - directories; p++) {
						// what JUnit would make of its own rather than take from a row
						if (method.parameters.get(p) instanceof ClassType named
								&& named.declaration().name.startsWith(JUNIT)) {
							throw JavaClasses.unsupported(source, declaration.parameters().get(p).pos(),
									"a parameter of the type " + named.declaration().name);
						}
					}
					suite.tests.add(annotated);
				}
				for (String each : List.of("BeforeAll", "AfterAll", "BeforeEach", "AfterEach")) {
					if (!annotated.is(API + each)) {
						continue;
					}
					boolean all = each.endsWith("All");
					if (all != method.isStatic || method.isPrivate) {
						throw JavaClasses.error(source, declaration.pos(),
								"the @" + each + " method " + method.name + " must "
										+ (method.isPrivate ? "not be private" : all ? "be static" : "not be static"));
					}
					directories(declaring, method, "a @" + each + " method");
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
	 * Refuses what an interface of the program declares that JUnit would run on a class
	 * implementing it: a test, or what runs around tests.
	 */
	private void interfaces(List<JavaClass> implemented, Set<JavaClass> seen) {
		for (JavaClass type : implemented) {
			if (type.intrinsic || type.source == null || type.source.library() || !seen.add(type)) {
				continue;
			}
			for (Method method : type.methods) {
				JavaSyntax.Method declaration = method.declaration;
				if (method.owner != type || declaration == null) {
					continue;
				}
				for (String annotation : annotations(type, declaration.pos()).keySet()) {
					if (Set
						.of(API + "Test", PARAMS + "ParameterizedTest", API + "BeforeAll", API + "AfterAll",
								API + "BeforeEach", API + "AfterEach")
						.contains(annotation)) {
						throw JavaClasses.unsupported(this.classes.unit(type).source(), declaration.pos(),
								"a test, or what runs around tests, declared by an interface");
					}
				}
			}
			interfaces(type.interfaces, seen);
		}
	}

	/**
	 * How many of a method's parameters are given a temporary directory: its last ones,
	 * after those a parameterized test's rows give.
	 * @param others what the method is where every parameter is to be one, or
	 * {@code null} for a parameterized test
	 */
	private int directories(JavaClass declaring, Method method, @Nullable String others) {
		JavaSyntax.Method declaration = java.util.Objects.requireNonNull(method.declaration);
		JavaClass.Source source = this.classes.unit(declaring).source();
		int directories = 0;
		for (int p = 0; p < method.parameters.size(); p++) {
			JavaSyntax.Parameter parameter = declaration.parameters().get(p);
			if (isDirectory(declaring, parameter)) {
				directory(method.parameters.get(p), "parameter " + parameter.name(), source, parameter.pos());
				directories++;
			}
			else if (others != null) {
				throw JavaClasses.unsupported(source, parameter.pos(), "a parameter of " + others);
			}
			else if (directories > 0) {
				throw JavaClasses.unsupported(source, parameter.pos(),
						"a parameter given by a row after a @TempDir parameter");
			}
		}
		return directories;
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

	/** Whether a class below declares the method again. */
	private static boolean overridden(Method method, List<JavaClass> below) {
		for (JavaClass each : below) {
			for (Method other : each.methods) {
				if (other.owner == each && other.name.equals(method.name)
						&& other.parameters.equals(method.parameters)) {
					return true;
				}
			}
		}
		return false;
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
			for (Annotated test : suite.tests) {
				tests.append(test(suite, test));
			}
			for (Suite nested : suite.nested) {
				tests.append("        ").append(run(nested)).append("(run);\n");
			}
			boolean around = !suite.beforeAll.isEmpty() || !suite.afterAll.isEmpty()
					|| !suite.staticDirectories.isEmpty();
			if (around) {
				// the static fields' directories, then what runs before all, each
				// parameter of it given a directory of its own
				Slots statics = new Slots("statics");
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
					before.append("            ").append(call(method.owner.name, method, statics)).append(";\n");
				}
				StringBuilder after = new StringBuilder("            Throwable thrown = null;\n");
				for (Method method : suite.afterAll.reversed()) {
					after.append(guarded(call(method.owner.name, method, statics), "            "));
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
				body.append("        if (run.around(() -> {\n")
					.append(before)
					.append("        }, ")
					.append(suite.skipped())
					.append(")) {\n")
					.append(tests)
					.append("        }\n        run.around(() -> {\n")
					.append(after)
					.append("        }, 0);\n");
			}
			else {
				body.append(tests);
			}
		}
		body.append("        run.finished();\n");
		this.members.append("    private static void ")
			.append(name)
			.append("(" + RUN + " run) {\n")
			.append(body)
			.append("    }\n");
		return name;
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
	 * A call of what runs before or after tests, each of its parameters, which are all
	 * temporary directories, given one of its own.
	 */
	private static String call(String target, Method method, Slots slots) {
		StringBuilder call = new StringBuilder(target).append('.').append(method.name).append('(');
		for (int p = 0; p < method.parameters.size(); p++) {
			call.append(p == 0 ? "" : ", ").append(directory(method.parameters.get(p), slots.array, slots.count++));
		}
		return call.append(')').toString();
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
		String shown = "null";
		List<Annotation> displayed = test.annotations().getOrDefault(API + "DisplayName", List.of());
		if (!displayed.isEmpty() && !displayed.get(0).pairs().isEmpty()
				&& displayed.get(0).pairs().get(0).value() instanceof JavaSyntax.ElementText text) {
			shown = "(" + unit.source().text().substring(text.from(), text.to()) + ")";
		}
		if (test.is(API + "Disabled")) {
			return "        run.skipped(1);\n";
		}
		boolean parameterized = test.is(PARAMS + "ParameterizedTest");
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
		this.members.append("    private static void ")
			.append(invoked)
			.append(parameterized ? "(Object[] row) throws Throwable {\n" : "() throws Throwable {\n")
			.append(invocation(suite, method, closing))
			.append("    }\n");
		if (!parameterized) {
			return "        run.test(" + literal(method.name) + ", " + shown + ", () -> " + invoked + "());\n";
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
		String named = literal(method.name);
		this.members.append("    private static void ")
			.append(rows)
			.append("(" + RUN + " run) {\n        java.util.List<Object[]> rows = new java.util.ArrayList<>();\n")
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
			.append("(row));\n        }\n    }\n");
		return "        " + rows + "(run);\n";
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
	private String invocation(Suite suite, Method method, boolean closing) {
		List<JavaClass> nest = new ArrayList<>();
		for (JavaClass around = suite.type; around != null; around = around.outer) {
			nest.add(0, around);
		}
		StringBuilder body = new StringBuilder();
		Slots slots = new Slots("directories");
		List<String> before = new ArrayList<>();
		List<String> after = new ArrayList<>();
		for (int i = 0; i < nest.size(); i++) {
			JavaClass level = nest.get(i);
			String variable = i == nest.size() - 1 ? "instance" : "outer" + i;
			body.append("        ")
				.append(level.name)
				.append(' ')
				.append(variable)
				.append(" = ")
				.append(i == 0 ? "new " + level.name + "()" : "outer" + (i - 1) + ".new " + level.simpleName() + "()")
				.append(";\n");
			Suite own = level == suite.type ? suite : collect(level);
			for (JavaClass.Field field : own.directories) {
				// a field of a class above, which one of the level's may hide
				String target = field.owner == level ? variable : "((" + field.owner.name + ") " + variable + ")";
				before.add(target + "." + field.name + " = " + directory(field.type, slots.array, slots.count++));
			}
			for (Method each : own.beforeEach) {
				before.add(call(variable, each, slots));
			}
			for (Method each : own.afterEach) {
				after.add(0, call(variable, each, slots));
			}
		}
		StringBuilder arguments = new StringBuilder();
		for (int i = 0; i < method.parameters.size(); i++) {
			JavaSyntax.Parameter parameter = java.util.Objects.requireNonNull(method.declaration).parameters().get(i);
			arguments.append(i == 0 ? "" : ", ")
				.append(isDirectory(method.owner, parameter)
						? directory(method.parameters.get(i), slots.array, slots.count++)
						: argument(method.parameters.get(i), i));
		}
		String call = "instance." + method.name + "(" + arguments + ")";
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
						.append(suite.type.name)
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
