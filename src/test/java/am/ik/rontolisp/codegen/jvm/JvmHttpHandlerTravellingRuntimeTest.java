package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.cli.JvmSourceCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a compiled {@code rontolisp:http-handler} program is SELF-CONTAINED: the class
 * files it calls into travel beside it, so it serves on a bare {@code java -cp .} with no
 * rontolisp jar anywhere.
 *
 * <p>
 * Three things are pinned, and the first two are the ones that rot:
 * <ul>
 * <li>the travelling set is exactly what {@code JvmHttpHandlerRuntimeBuilder} lists --
 * recomputed here from the emitted class's own constant pool, transitively, so a class
 * added to {@code am.ik.rontolisp.runtime} and forgotten in that list fails HERE instead
 * of as a {@code NoClassDefFoundError} in a user's deployment;</li>
 * <li>nothing in the closure reaches outside {@code java.base}, {@code jdk.httpserver}
 * and the travelling set itself -- an {@code eval} or {@code compiler} name creeping back
 * in (or the build's {@code @Nullable}, which is {@code RuntimeVisible}) would make the
 * output depend on the jar again;</li>
 * <li>a program that does NOT serve still emits exactly one file.</li>
 * </ul>
 *
 * <p>
 * The run is done through a {@link URLClassLoader} whose parent is the PLATFORM loader:
 * the test's own rontolisp classes are invisible to it, so anything the emitted code
 * needs must be in the directory or the JDK.
 */
class JvmHttpHandlerTravellingRuntimeTest {

	private static final String TRAVELLING_PACKAGE = "am/ik/rontolisp/runtime/";

	@TempDir
	Path outputDirectory;

	@Test
	void aServingProgramCarriesItsRuntimeAndNothingElse() {
		JvmSourceCompiler.Result compiled = compile("""
				(defun handle (env)
				  (list 200 '(:content-type "text/plain") (list (getf env :path-info))))

				(rontolisp:http-handler 'handle 8080)
				""");

		assertThat(compiled.runtimeClasses().keySet())
			.containsExactlyInAnyOrderElementsOf(JvmHttpHandlerRuntimeBuilder.RUNTIME_CLASS_FILES);
		// The closure the emitted class actually has, walked from its constant pool: the
		// list above is a hand-written mirror of it and this is what keeps the two equal.
		assertThat(projectClosure(compiled)).containsExactlyInAnyOrderElementsOf(compiled.runtimeClasses().keySet());
	}

	@Test
	void anOrdinaryProgramCarriesNothing() {
		assertThat(compile("(print (+ 1 2))").runtimeClasses()).isEmpty();
	}

	/**
	 * The war-mode arm: the servlet transport travels IN ADDITION to the served closure,
	 * and only there -- a {@code .class}/{@code .jar} compile (the arm above) never
	 * carries it, so no existing artifact gains the {@code jakarta.servlet} reference.
	 * The war closure is self-contained GIVEN a container, so the walk admits
	 * {@code jakarta/servlet/**} as provided and keeps failing for any other outside
	 * reference (an {@code eval} name, the build's {@code @Nullable}, a new library).
	 */
	@Test
	void aWarCarriesTheServletTransportWhoseOnlyOutsideReferenceIsTheServletApi() {
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler("Served").servlet(true).compile("""
				(defun handle (env)
				  (list 200 '(:content-type "text/plain") (list (getf env :path-info))))

				(rontolisp:http-handler 'handle)
				""", null);

		Set<String> expected = new LinkedHashSet<>(JvmHttpHandlerRuntimeBuilder.RUNTIME_CLASS_FILES);
		expected.addAll(JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES);
		assertThat(compiled.runtimeClasses().keySet()).containsExactlyInAnyOrderElementsOf(expected);
		for (String warClass : JvmHttpHandlerRuntimeBuilder.WAR_RUNTIME_CLASS_FILES) {
			byte[] bytes = compiled.runtimeClasses().get(warClass);
			assertThat(bytes).isNotNull();
			for (String reference : allReferences(bytes)) {
				assertThat(reference)
					.as("%s may reach only the JDK, the servlet API and the travelling runtime itself", warClass)
					.matches(name -> name.startsWith("java/") || name.startsWith("jakarta/servlet/")
							|| name.startsWith(TRAVELLING_PACKAGE), "an allowed prefix");
				if (reference.startsWith("am/ik/")) {
					assertThat(compiled.runtimeClasses()).as("%s is referenced but does not travel", reference)
						.containsKey(reference + ".class");
				}
			}
		}
	}

	@Test
	void theCompiledClassServesWithOnlyItsOwnOutputOnTheClasspath() throws Exception {
		// The stoppable seam rather than the http-handler directive: it binds an
		// ephemeral port and RETURNS, where the directive blocks forever. Both compile
		// through the same runtime, which is the thing under test.
		JvmSourceCompiler.Result compiled = compile("""
				(defun handle (env)
				  (list 200 '(:content-type "text/plain")
				        (list (format nil "~a ~a" (getf env :request-method) (getf env :path-info)))))

				(defvar *server* (rontolisp::%http-server-start #'handle 0 nil))
				(print (rontolisp::%http-server-port *server*))
				""");
		write(compiled);

		try (URLClassLoader isolated = new URLClassLoader(new URL[] { this.outputDirectory.toUri().toURL() },
				ClassLoader.getPlatformClassLoader())) {
			Class<?> program = isolated.loadClass(compiled.className());
			// Proof the isolation is real: the server class the program serves through is
			// the one that travelled, not this JVM's own.
			Class<?> server = isolated.loadClass("am.ik.rontolisp.runtime.RontoHttpServer");
			assertThat(server.getClassLoader()).isSameAs(isolated);

			int port = runAndReadPort(program);
			try {
				HttpResponse<String> response = HttpClient.newHttpClient()
					.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).build(),
							HttpResponse.BodyHandlers.ofString());
				assertThat(response.statusCode()).isEqualTo(200);
				assertThat(response.body()).isEqualTo("GET /hello");
			}
			finally {
				server.getMethod("stopAllForTesting").invoke(null);
			}
		}
	}

	// Runs the program's main (which starts the server and prints its bound port) and
	// answers the port it printed.
	private static int runAndReadPort(Class<?> program) throws Exception {
		PrintStream out = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			program.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
		}
		finally {
			System.setOut(out);
		}
		return Integer.parseInt(captured.toString(StandardCharsets.UTF_8).replaceAll("\\D", ""));
	}

	/**
	 * Every {@code am.ik.rontolisp} class file the emitted class needs, transitively --
	 * the closure the travelling set has to cover. A reference outside the travelling
	 * package fails the walk by name, because that is precisely the dependency this
	 * feature exists to remove.
	 */
	private Set<String> projectClosure(JvmSourceCompiler.Result compiled) {
		Set<String> found = new LinkedHashSet<>();
		Deque<byte[]> pending = new ArrayDeque<>();
		pending.add(compiled.classBytes());
		while (!pending.isEmpty()) {
			for (String path : projectReferences(pending.poll())) {
				assertThat(path).as("a compiled http-handler class may only reach %s", TRAVELLING_PACKAGE)
					.startsWith(TRAVELLING_PACKAGE);
				byte[] bytes = compiled.runtimeClasses().get(path);
				assertThat(bytes).as("%s is referenced but does not travel", path).isNotNull();
				if (found.add(path)) {
					pending.add(bytes);
				}
			}
		}
		return found;
	}

	// EVERY class reference in one class file's constant pool (internal names, array
	// element types unwrapped, the class itself excluded) -- what the war arm checks
	// against its allowed prefixes.
	private static Set<String> allReferences(byte[] classBytes) {
		Set<String> references = new TreeSet<>();
		var model = ClassFile.of().parse(classBytes);
		String self = model.thisClass().asInternalName();
		for (PoolEntry entry : model.constantPool()) {
			if (entry instanceof ClassEntry classEntry) {
				String name = classEntry.asInternalName();
				int element = name.lastIndexOf('[');
				if (element >= 0) {
					name = name.substring(element + 1).replaceAll("^L|;$", "");
					if (name.length() == 1) {
						// A primitive array descriptor ([B, [J, ...) names no class.
						continue;
					}
				}
				if (!name.equals(self)) {
					references.add(name);
				}
			}
		}
		return references;
	}

	// The am.ik.* class-file paths named in one class file's constant pool. An array
	// descriptor ([Lam/ik/...;) and a nested name both arrive here as ordinary entries.
	private static Set<String> projectReferences(byte[] classBytes) {
		Set<String> references = new TreeSet<>();
		var model = ClassFile.of().parse(classBytes);
		String self = model.thisClass().asInternalName();
		for (PoolEntry entry : model.constantPool()) {
			if (entry instanceof ClassEntry classEntry) {
				String name = classEntry.asInternalName();
				int element = name.lastIndexOf('[');
				if (element >= 0) {
					name = name.substring(element + 1).replaceAll("^L|;$", "");
				}
				if (name.startsWith("am/ik/") && !name.equals(self)) {
					references.add(name + ".class");
				}
			}
		}
		return references;
	}

	private JvmSourceCompiler.Result compile(String source) {
		return new JvmSourceCompiler("Served").compile(source, null);
	}

	private void write(JvmSourceCompiler.Result compiled) throws Exception {
		Files.write(this.outputDirectory.resolve(compiled.internalClassName() + ".class"), compiled.classBytes());
		for (Map.Entry<String, byte[]> runtimeClass : compiled.runtimeClasses().entrySet()) {
			Path target = this.outputDirectory.resolve(runtimeClass.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, runtimeClass.getValue());
		}
	}

}
