package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import am.ik.rontolisp.testsupport.YamlResources;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Clojure programs whose lowering must not depend on where the classes their host
 * forms name are read from -- reflection on a JVM, class files in a native image
 * ({@code .kb/clojure-frontend.md}, "Host classes"): {@code clojure-spec.yaml}'s program,
 * every Clojure example {@code examples.yaml} lists without a {@code cli} (its
 * {@code javaSources} compiled onto its Java class path), the Clojure examples it does
 * not list, a program of the host forms a native image's own classes answered wrongly,
 * and a program whose host forms name only the JDK classes the image registers, which the
 * binary must lower alike with no JDK found.
 */
final class ClojureLoweringCorpus {

	private static final Path EXAMPLES_DIR = Path.of("").toAbsolutePath().resolve("examples");

	/** Clojure examples the manifest does not run, lowered all the same. */
	private static final List<String> UNLISTED = List.of("clojure/java-interop.clj");

	/**
	 * Host forms whose meaning a native image's own classes once answered otherwise: a
	 * zero-argument static method it read as a field (it had none of {@code Thread}'s
	 * methods), plain throwables it left to {@code java:new} or could not name, a
	 * {@code Comparable} face without {@code compareTo}, a member whose answer it could
	 * not tell carries no {@code byte[]}.
	 */
	private static final String HOST_FORMS = """
			(ns host-forms)
			(defn thread-name [] (.getName (Thread/currentThread)))
			(def failures [(Exception. "outer" (ex-info "inner" {:code 42})) (IllegalStateException. "state")
			               (RuntimeException.) (java.util.concurrent.TimeoutException. "late")
			               (java.io.UncheckedIOException. "io" (java.io.IOException. "disk"))])
			(defn timed [f] (try (f) (catch java.util.concurrent.TimeoutException e :late)
			                     (catch java.util.concurrent.CancellationException e :cancelled)))
			(deftype Version [n] Comparable (compareTo [_ other] (compare n (.-n other))))
			(println (thread-name) (map ex-message failures) (timed (fn [] 1)) (.length "abc")
			         (map #(.-n %) (sort [(Version. 2) (Version. 1)])) (String/valueOf 42) (Math/max 1 2)
			         (java.util.Collections/sort (java.util.ArrayList. [(Version. 3) (Version. 1)])))
			""";

	/**
	 * The name of the program below, whose host forms name only the JDK classes the
	 * native image registers ({@code .kb/clojure-frontend.md}, "Host classes"), so the
	 * binary lowers it from its own reflection with no JDK found as the jar does from the
	 * JDK's classes.
	 */
	static final String NO_JDK_PROGRAM = "no-jdk-host-forms";

	/**
	 * Host forms naming only registered classes: plain throwables built as exception
	 * conditions, a catch of them, a {@code Comparable} face, an unmapped value method
	 * reaching a host kind's interface, and time-value members the kind scan answers.
	 */
	private static final String NO_JDK_HOST_FORMS = """
			(ns no-jdk-host-forms)
			(def failures [(Exception. "outer" (ex-info "inner" {:code 42}))
			               (IllegalStateException. "state")
			               (RuntimeException.)])
			(defn caught [f] (try (f) (catch IllegalStateException e :illegal) (catch java.io.IOException e :io)))
			(deftype Version [n] Comparable (compareTo [_ other] (compare n (.-n other))))
			(println (map ex-message failures) (caught (fn [] (throw (IllegalStateException. "deep"))))
			         (map #(.-n %) (sort [(Version. 2) (Version. 1)])) (vec (.toArray [1 2]))
			         (.getTime (java.util.Date. 0)) (.toInstant (java.util.Date. 0)))
			""";

	/**
	 * One program to lower.
	 *
	 * @param name what the test calls it
	 * @param file the entry file
	 * @param javaClassPath its Java class path
	 */
	record Program(String name, Path file, List<Path> javaClassPath) {
	}

	private ClojureLoweringCorpus() {
	}

	/**
	 * The corpus, its generated files written under a directory.
	 * @param workDir where the spec program, the host-form program and the compiled Java
	 * sources go
	 * @return the programs
	 * @throws IOException if a file cannot be written or read
	 */
	static List<Program> programs(Path workDir) throws IOException {
		List<Program> programs = new ArrayList<>();
		Path spec = workDir.resolve("spec.clj");
		Files.writeString(spec, specProgram());
		programs.add(new Program("clojure-spec.yaml", spec, List.of()));
		Path hostForms = workDir.resolve("host_forms.clj");
		Files.writeString(hostForms, HOST_FORMS);
		programs.add(new Program("host-forms", hostForms, List.of()));
		Path noJdkHostForms = workDir.resolve("no_jdk_host_forms.clj");
		Files.writeString(noJdkHostForms, NO_JDK_HOST_FORMS);
		programs.add(new Program(NO_JDK_PROGRAM, noJdkHostForms, List.of()));
		for (ExamplesE2eTest.Example example : ExamplesE2eTest.loadManifest().examples()) {
			String path = example.path();
			if (!(path.endsWith(".clj") || path.endsWith(".cljc"))
					|| (example.cli() != null && !example.cli().isEmpty())) {
				continue;
			}
			List<Path> classPath = example.javaSources() == null || example.javaSources().isEmpty() ? List.of()
					: List.of(compileJavaSources(workDir, path, example.javaSources()));
			programs.add(new Program(path, EXAMPLES_DIR.resolve(path), classPath));
		}
		for (String path : UNLISTED) {
			programs.add(new Program(path, EXAMPLES_DIR.resolve(path), List.of()));
		}
		return programs;
	}

	private static String specProgram() throws IOException {
		try (InputStream in = ClojureLoweringCorpus.class.getResourceAsStream("/clojure-spec.yaml")) {
			assertThat(in).as("the clojure-spec.yaml test resource").isNotNull();
			YamlResources.Spec spec = new YAMLMapper().readValue(
					YamlResources.safeReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
					YamlResources.Spec.class);
			StringBuilder program = new StringBuilder();
			for (YamlResources.Case c : spec.cases()) {
				program.append(c.source());
			}
			return program.toString();
		}
	}

	private static Path compileJavaSources(Path workDir, String example, List<String> sources) throws IOException {
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertThat(javac).as("a Java compiler in this JVM").isNotNull();
		Path classes = Files.createDirectories(workDir.resolve("classes-" + example.replace('/', '_')));
		List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
		for (String source : sources) {
			arguments.add(EXAMPLES_DIR.resolve(source).toString());
		}
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		int status = javac.run(null, null, errors, arguments.toArray(String[]::new));
		assertThat(status).as("%s: javac failed%n%s", example, errors.toString(StandardCharsets.UTF_8)).isZero();
		return classes;
	}

}
