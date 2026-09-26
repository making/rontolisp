package am.ik.rontolisp.codegen.jvm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaSiteResolver;
import am.ik.rontolisp.compiler.JavaStaticType;
import am.ik.rontolisp.compiler.JavaType;
import am.ik.rontolisp.compiler.ReflectiveJavaClasses;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The class-file lookup (the JVM compiler's) must describe every class exactly as
 * reflection (the interpreter's) does, or a site would resolve to one member interpreted
 * and another compiled: the same candidates for every method name, the same constructors
 * and fields, the same subtype relation -- over a corpus of JDK classes read from the
 * running JDK's {@code ct.sym} for its own release -- and so the same resolution of every
 * site in a corpus of {@code java:} call sites.
 */
class JvmClassFileLookupTest {

	private static final List<String> CORPUS = List.of("java.lang.Object", "java.lang.String",
			"java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.CharSequence", "java.lang.Appendable",
			"java.lang.Math", "java.lang.StrictMath", "java.lang.Character", "java.lang.Integer", "java.lang.Long",
			"java.lang.Double", "java.lang.Boolean", "java.lang.Number", "java.lang.System", "java.lang.Thread",
			"java.lang.Runtime", "java.lang.Iterable", "java.lang.Comparable", "java.util.ArrayList", "java.util.List",
			"java.util.Collection", "java.util.AbstractList", "java.util.Collections", "java.util.Arrays",
			"java.util.HashMap", "java.util.Map", "java.util.Map$Entry", "java.util.TreeMap", "java.util.LinkedList",
			"java.util.ArrayDeque", "java.util.Optional", "java.util.Objects", "java.util.Random", "java.util.UUID",
			"java.util.Iterator", "java.util.stream.IntStream", "java.util.stream.Stream",
			"java.util.stream.Collectors", "java.util.function.Function", "java.util.function.Supplier",
			"java.util.concurrent.ConcurrentHashMap", "java.util.regex.Pattern", "java.util.regex.Matcher",
			"java.io.StringWriter", "java.io.Writer", "java.io.PrintStream", "java.io.File", "java.nio.file.Path",
			"java.nio.file.Files", "java.nio.ByteBuffer", "java.net.URI", "java.math.BigInteger",
			"java.math.BigDecimal", "java.time.LocalDate", "java.time.Duration", "java.awt.Point",
			"java.awt.BorderLayout", "java.awt.Container", "javax.swing.JFrame", "javax.swing.JButton",
			"javax.swing.JLabel", "javax.swing.JPanel", "javax.swing.WindowConstants");

	private static JvmClassFileLookup classFiles;

	private static final JavaClassLookup REFLECTION = ReflectiveJavaClasses.instance();

	@BeforeAll
	static void open() {
		Path ctSym = JvmClassFileLookup.findCtSym();
		assertThat(ctSym).as("the test JDK's lib/ct.sym").isNotNull();
		classFiles = JvmClassFileLookup.of(ctSym, Runtime.version().feature(), List.of());
	}

	@AfterAll
	static void close() {
		classFiles.close();
	}

	@Test
	void everyMethodNameHasTheSameCandidates() throws Exception {
		for (String className : CORPUS) {
			Class<?> type = Class.forName(className);
			JavaType reflected = Objects.requireNonNull(REFLECTION.find(className));
			JavaType read = Objects.requireNonNull(classFiles.find(className), className);
			TreeSet<String> names = new TreeSet<>();
			for (Method method : type.getMethods()) {
				names.add(method.getName());
			}
			for (String name : names) {
				assertThat(signatures(read.methods(name))).as("%s.%s", className, name)
					.isEqualTo(signatures(reflected.methods(name)));
			}
			assertThat(signatures(read.constructors())).as("%s constructors", className)
				.isEqualTo(signatures(reflected.constructors()));
		}
	}

	@Test
	void everyPublicFieldIsTheSame() throws Exception {
		for (String className : CORPUS) {
			Class<?> type = Class.forName(className);
			JavaType reflected = Objects.requireNonNull(REFLECTION.find(className));
			JavaType read = Objects.requireNonNull(classFiles.find(className));
			for (Field field : type.getFields()) {
				assertThat(describe(read.field(field.getName()))).as("%s.%s", className, field.getName())
					.isEqualTo(describe(reflected.field(field.getName())));
			}
			assertThat(read.field("noSuchField")).isNull();
		}
	}

	@Test
	void theTypesAgree() throws Exception {
		List<JavaType> reflected = new ArrayList<>();
		List<JavaType> read = new ArrayList<>();
		for (String className : CORPUS) {
			reflected.add(Objects.requireNonNull(REFLECTION.find(className)));
			read.add(Objects.requireNonNull(classFiles.find(className)));
		}
		for (String name : List.of("int", "[I", "[Ljava.lang.String;", "[[Ljava.lang.Object;")) {
			reflected.add(Objects.requireNonNull(REFLECTION.find(name)));
			read.add(Objects.requireNonNull(classFiles.find(name)));
		}
		for (int i = 0; i < read.size(); i++) {
			JavaType a = read.get(i);
			JavaType ra = reflected.get(i);
			assertThat(a.name()).isEqualTo(ra.name());
			assertThat(a.isInterface()).as("%s interface", a).isEqualTo(ra.isInterface());
			assertThat(a.isFinal()).as("%s final", a).isEqualTo(ra.isFinal());
			assertThat(a.isArray()).as("%s array", a).isEqualTo(ra.isArray());
			assertThat(a.isAccessible()).as("%s accessible", a).isEqualTo(ra.isAccessible());
			for (int j = 0; j < read.size(); j++) {
				assertThat(a.isAssignableFrom(read.get(j))).as("%s <- %s", a, read.get(j))
					.isEqualTo(ra.isAssignableFrom(reflected.get(j)));
			}
		}
		assertThat(classFiles.find("no.such.Type")).isNull();
		// A package-private class of an exported package: present or not, never callable.
		JavaType listN = classFiles.find("java.util.ImmutableCollections$ListN");
		assertThat(listN == null || !listN.isAccessible()).isTrue();
		assertThat(Objects.requireNonNull(classFiles.find("java.lang.AbstractStringBuilder")).isAccessible()).isFalse();
	}

	// A corpus of sites: each resolves to the same member (or stays unresolved for the
	// same reason) whichever lookup describes the classes.
	@Test
	void everySiteResolvesTheSame() {
		String corpus = """
				(java:static "java.lang.Math" "max" 3 7)
				(java:static "java.lang.Math" "max" 3 7.5)
				(java:static "java.lang.Math" "max(long,long)" 3 7)
				(java:static "java.lang.Math" "max(long,_)" 3 7)
				(java:static "java.lang.Math" "abs" -5.5)
				(java:static "java.lang.Math" "sqrt" 16)
				(java:static "java.lang.Integer" "parseInt" "100")
				(java:static "java.lang.Character" "charCount" #\\a)
				(java:static "java.lang.Character" "toString" #\\a)
				(java:static "java.lang.String" "valueOf" 5)
				(java:static "java.lang.String" "valueOf" #\\a)
				(java:static "java.lang.String" "format" "%s-%s" 1 "x")
				(java:static "java.lang.String" "join" "-" "a" "b")
				(java:static "java.util.List" "of" 1 2 3)
				(java:static "java.util.List" "of")
				(java:static "java.util.Objects" "equals" "a" "b")
				(java:static "java.lang.Thread" "sleep" 1)
				(java:new "java.lang.StringBuilder")
				(java:new "java.lang.StringBuilder" "hi")
				(java:new "java.lang.StringBuilder" 16)
				(java:new "java.lang.StringBuilder(int)" 16)
				(java:new "java.util.ArrayList")
				(java:new "java.awt.Point" 3 4)
				(java:new "javax.swing.JFrame" "title")
				(java:new "java.lang.Thread" (lambda () nil))
				(java:call (java:new "java.lang.StringBuilder") "append" "x")
				(java:call (java:new "java.lang.StringBuilder") "append" "xy")
				(java:call (java:new "java.lang.StringBuilder") "append" 42)
				(java:call (java:new "java.lang.StringBuilder") "append" 4.2)
				(java:call (java:new "java.lang.StringBuilder") "append" #\\a)
				(java:call (java:new "java.lang.StringBuilder") "append" t)
				(java:call (java:new "java.lang.StringBuilder") "length")
				(java:call (java:call (java:new "java.lang.StringBuilder") "append" "x") "reverse")
				(java:call (java:call (java:new "java.lang.StringBuilder") "reverse") "toString")
				(java:call (java:new "java.util.ArrayList") "add" 42)
				(java:call (java:new "java.util.ArrayList") "add" 0 42)
				(java:call (java:new "java.util.ArrayList") "remove" 1)
				(java:call (java:new "java.util.ArrayList") "size")
				(java:call (the (java:object "java.util.Collection") x) "remove" 1)
				(java:call (the (java:object "java.util.List") x) "get" 0)
				(java:call (the (java:object "java.util.Map") x) "put" "k" 1)
				(java:call (the (java:object "java.lang.Appendable") x) "append" #\\a)
				(java:call (the (java:object "java.io.Writer") x) "write" "abc")
				(java:call (java:new "java.io.StringWriter") "write" 65)
				(java:call (java:new "javax.swing.JFrame") "setSize" 360 180)
				(java:call (java:new "javax.swing.JFrame") "setVisible" t)
				(java:call (java:new "javax.swing.JFrame") "setTitle" "x")
				(java:call (java:new "javax.swing.JButton" "x") "addActionListener" (lambda (m e) nil))
				(java:call (java:new "javax.swing.JPanel") "add" (java:new "javax.swing.JLabel" "x"))
				(java:call (java:new "java.lang.StringBuilder") "append" x)
				(java:call x "length")
				(java:field "java.lang.Integer" "MAX_VALUE")
				(java:field "javax.swing.WindowConstants" "DISPOSE_ON_CLOSE")
				(java:field "javax.swing.JFrame" "EXIT_ON_CLOSE")
				(java:field (java:new "java.awt.Point" 3 4) "y")
				(java:field "java.awt.BorderLayout" "CENTER")
				(java:static "java.lang.Math" "noSuchMethod" 1)
				(java:static "no.such.Class" "m")
				""";
		JavaSiteResolver byReflection = new JavaSiteResolver(REFLECTION);
		JavaSiteResolver byClassFiles = new JavaSiteResolver(classFiles);
		int resolved = 0;
		for (LispVal form : LispReader.readAllFromString(corpus)) {
			LispCons site = (LispCons) form;
			JavaSite a = byReflection.resolve(site);
			JavaSite b = byClassFiles.resolve(site);
			assertThat(describe(b)).as(site.print()).isEqualTo(describe(a));
			if (a.resolved()) {
				resolved++;
			}
		}
		assertThat(resolved).as("the corpus exercises resolution").isGreaterThan(40);
	}

	// A class-path root (here the project's own compiled classes, loaded in this JVM by
	// reflection too) is described the same way: its classes are in the unnamed module,
	// every one accessible.
	@Test
	void aClassPathClassIsDescribedAsReflectionDescribesIt() throws Exception {
		Path classes = Path.of(JvmClassFileLookupTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
			.resolveSibling("classes");
		try (JvmClassFileLookup withClassPath = JvmClassFileLookup.of(JvmClassFileLookup.findCtSym(),
				Runtime.version().feature(), List.of(classes))) {
			for (String className : List.of("am.ik.rontolisp.LispCons", "am.ik.rontolisp.LispString",
					"am.ik.jvm.ClassFileInfo", "am.ik.rontolisp.compiler.JavaOverloads")) {
				Class<?> type = Class.forName(className);
				JavaType reflected = Objects.requireNonNull(REFLECTION.find(className));
				JavaType read = Objects.requireNonNull(withClassPath.find(className), className);
				assertThat(read.isAccessible()).isEqualTo(reflected.isAccessible());
				for (Method method : type.getMethods()) {
					assertThat(signatures(read.methods(method.getName()))).as("%s.%s", className, method.getName())
						.isEqualTo(signatures(reflected.methods(method.getName())));
				}
				assertThat(signatures(read.constructors())).isEqualTo(signatures(reflected.constructors()));
			}
		}
	}

	// --java-release: a site resolves against that release's API. StringBuilder.repeat
	// arrived in 21, so under 17 the site is left to run time.
	@Test
	void aSiteResolvesAgainstTheReleaseItIsCompiledFor() {
		LispCons site = (LispCons) LispReader
			.readAllFromString("(java:call (java:new \"java.lang.StringBuilder\") \"repeat\" \"ab\" 2)")
			.get(0);
		try (JvmClassFileLookup release17 = JvmClassFileLookup.of(JvmClassFileLookup.findCtSym(), 17, List.of());
				JvmClassFileLookup release21 = JvmClassFileLookup.of(JvmClassFileLookup.findCtSym(), 21, List.of())) {
			assertThat(new JavaSiteResolver(release17).resolve(site).reason())
				.isEqualTo("class java.lang.StringBuilder has no public method repeat");
			assertThat(new JavaSiteResolver(release21).resolve(site).designator())
				.isEqualTo("repeat(java.lang.CharSequence,int)");
		}
	}

	// Where the JDK is found: a home first, else the java a PATH directory holds,
	// followed through a symbolic link (the sdkman / alternatives layout) to its JDK.
	@Test
	void theJdkIsFoundFromAHomeOrThroughTheJavaOnPath(@TempDir Path dir) throws Exception {
		Path jdk = dir.resolve("jdk");
		java.nio.file.Files.createDirectories(jdk.resolve("lib"));
		java.nio.file.Files.createDirectories(jdk.resolve("bin"));
		java.nio.file.Files.write(jdk.resolve("lib").resolve("ct.sym"), new byte[0]);
		java.nio.file.Files.write(jdk.resolve("bin").resolve("java"), new byte[0]);
		Path shims = dir.resolve("shims");
		java.nio.file.Files.createDirectories(shims);
		java.nio.file.Files.createSymbolicLink(shims.resolve("java"), jdk.resolve("bin").resolve("java"));
		Path expected = jdk.resolve("lib").resolve("ct.sym").toRealPath();
		assertThat(JvmClassFileLookup.findCtSym(List.of(jdk.toString()), null)).isEqualTo(jdk.resolve("lib/ct.sym"));
		assertThat(JvmClassFileLookup.findCtSym(List.of(dir.resolve("nowhere").toString()),
				dir.resolve("empty") + java.io.File.pathSeparator + shims))
			.isEqualTo(expected);
		assertThat(JvmClassFileLookup.findCtSym(List.of(), dir.toString())).isNull();
	}

	private static String describe(JavaSite site) {
		JavaExecutable executable = site.executable();
		return site.operator() + " " + site.staticClass() + " " + site.designator() + " packed=" + site.packed()
				+ " result=" + describe(site.result()) + " reason=" + site.reason()
				+ (executable != null ? " -> " + signature(executable) : "");
	}

	private static String describe(JavaStaticType type) {
		return switch (type) {
			case JavaStaticType.Kinds kinds -> {
				TreeSet<String> names = new TreeSet<>();
				for (JavaKind kind : kinds.kinds()) {
					names.add(kind instanceof JavaType t ? t.name() : kind.toString());
				}
				yield "kinds" + names;
			}
			case JavaStaticType.Bounded bounded -> "bounded " + bounded.type().name();
			case JavaStaticType.Unknown ignored -> "unknown";
		};
	}

	private static List<String> signatures(List<? extends JavaExecutable> executables) {
		List<String> list = new ArrayList<>();
		for (JavaExecutable e : executables) {
			list.add(signature(e));
		}
		list.sort(null);
		return list;
	}

	private static String signature(JavaExecutable e) {
		List<String> params = new ArrayList<>();
		for (JavaType p : e.parameterTypes()) {
			params.add(p.name());
		}
		return e.declaringClass().name() + "." + e.name() + params + e.returnType().name()
				+ (e.isStatic() ? " static" : "") + (e.isVarArgs() ? " varargs" : "");
	}

	private static String describe(@Nullable JavaField field) {
		if (field == null) {
			return "none";
		}
		return field.declaringClass().name() + "." + field.name() + ":" + field.type().name()
				+ (field.isStatic() ? " static" : "");
	}

}
