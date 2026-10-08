package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * A Java library no class loader of the test JVM can see: its sources compiled at test
 * time into a jar of their own, so a program reaches its classes only through the class
 * path it is given ({@code --java-classpath}, {@code --java-dep}).
 *
 * <p>
 * {@link #LIBRARY_PROGRAM} uses each kind of {@code java:} access on its classes and
 * prints {@link #LIBRARY_OUTPUT} on the interpreter and the JVM backend alike.
 */
public final class JavaLibraryJar {

	/** The library's sources, by path below the source root. */
	public static final Map<String, String> SOURCES = Map.of("fixture/lib/Greeter.java", """
			package fixture.lib;

			public class Greeter {
				public static final String NAME = "fixture";
				private final String name;
				public Greeter(String name) { this.name = name; }
				public String greet() { return "hello, " + name; }
				public static int twice(int x) { return 2 * x; }
				public static String version() { return "1.0"; }
			}
			""", "fixture/lib/Shape.java", """
			package fixture.lib;

			public interface Shape {
				double area();
				default String describe() { return "area " + area(); }
			}
			""", "fixture/lib/Base.java", """
			package fixture.lib;

			public abstract class Base {
				public abstract String name();
				public String describe() { return "I am " + name(); }
			}
			""");

	/** Every kind of access to the library; prints {@link #LIBRARY_OUTPUT}. */
	public static final String LIBRARY_PROGRAM = """
			(print (java:call (java:new "fixture.lib.Greeter" "rontolisp") "greet"))
			(print (java:static "fixture.lib.Greeter" "twice" 21))
			(print (java:field "fixture.lib.Greeter" "NAME"))
			(print (java:call (java:reify "fixture.lib.Shape" "area" (lambda () 2.5d0)) "describe"))
			(print (java:call (java:subclass "fixture.lib.Base" '() '("name") (lambda (this name) "sub")) "describe"))
			(defun greet (g) (java:call g "greet"))
			(print (greet (java:new "fixture.lib.Greeter" "late")))
			""";

	/** What {@link #LIBRARY_PROGRAM} prints. */
	public static final String LIBRARY_OUTPUT = """
			"hello, rontolisp"
			42
			"fixture"
			"area 2.5"
			"I am sub"
			"hello, late\"""";

	private JavaLibraryJar() {
	}

	/**
	 * Compiles the library into {@code dir/fixture-lib.jar}.
	 * @param dir an empty directory
	 * @return the jar
	 */
	public static Path build(Path dir) {
		return build(dir, "fixture-lib.jar", SOURCES);
	}

	/**
	 * Compiles Java sources into a jar.
	 * @param dir a directory to work in
	 * @param jarName the jar's file name
	 * @param sources the sources, by path below the source root
	 * @return the jar
	 */
	public static Path build(Path dir, String jarName, Map<String, String> sources) {
		try {
			Path src = Files.createTempDirectory(dir, "src");
			Path classes = Files.createTempDirectory(dir, "classes");
			List<String> arguments = new ArrayList<>(List.of("-d", classes.toString(), "--release", "17"));
			for (Map.Entry<String, String> source : sources.entrySet()) {
				Path file = src.resolve(source.getKey());
				Files.createDirectories(file.getParent());
				Files.writeString(file, source.getValue());
				arguments.add(file.toString());
			}
			JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
			if (javac.run(null, null, null, arguments.toArray(String[]::new)) != 0) {
				throw new IllegalStateException("the fixture library did not compile");
			}
			Path jar = dir.resolve(jarName);
			try (OutputStream out = Files.newOutputStream(jar);
					JarOutputStream zip = new JarOutputStream(out);
					Stream<Path> files = Files.walk(classes)) {
				for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
					zip.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
					zip.write(Files.readAllBytes(file));
					zip.closeEntry();
				}
			}
			return jar;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
