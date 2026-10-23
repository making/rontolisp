package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The load-context variables per SPLICED file ({@code .kb/load-inliner.md}): on all four
 * backends {@code *load-pathname*} / {@code *load-truename*} hold the file being loaded
 * while its top-level forms run, go back to the enclosing file's values after a nested
 * load, and are {@code nil} once the load has finished -- read from a function called
 * later as much as at top level. The interpreter BINDS them around each file; the compile
 * paths lower the {@code LoadInliner} brackets to assignments, so the two agree byte for
 * byte. An ASDF component is loaded by its resolved path, which is what makes its
 * {@code *load-pathname*} equal {@code asdf:component-pathname} -- the correlation rove's
 * suite-to-file map is built on.
 *
 * <p>
 * The context is established at READ time as well: a {@code #.} datum runs before any
 * top-level form of its own file, so the interpreter's binding and the compile paths'
 * lowered assignments are both too late for it -- {@code UserMacroExpander} establishes
 * the {@code %begin-file} bracket's two values around the file's forms instead, which is
 * what makes the portable {@code (or *compile-file-pathname* *load-truename*)} idiom find
 * a data file shipping beside the source that reads it. Fixture:
 * {@code src/test/resources/load-context-demo}.
 *
 * <p>
 * {@code *load-truename*} is the file's truename -- absolute -- on every path, and an
 * ENTRY file is a file being loaded too (the exercise above is no file's, so its top
 * level reads nil). Together they make the portable
 * {@code (load (merge-pathnames "src/a.lisp" *load-truename*))} name the same file on the
 * interpreter as on SBCL, and let the compilers fold it and splice the file
 * ({@link #thePortableLoadIdiomSplicesTheEntrysSourcesOnEveryBackend}).
 */
class LoadContextE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "load-context-demo")
		.toAbsolutePath()
		.toString();

	// A relative path is enough for the plain load: it is resolved against the working
	// directory on both the interpreter and the compile path, so the value the program
	// records is the same short spelling on every machine.
	private static final String PLAIN_FILE = "src/test/resources/load-context-demo/plain.lisp";

	private static final String EXERCISE = """
			(asdf:load-system "lc-demo")
			(load "%s")
			(defun ends-with (s suffix)
			  (and (>= (length s) (length suffix))
			       (string= suffix s :start2 (- (length s) (length suffix)))))
			;; Outside every load both are nil -- at top level, and read at call time by
			;; a function the load defined.
			(print (list *load-pathname* *load-truename*))
			(print (one-context))
			;; A component records the path asdf:component-pathname answers for it, and
			;; its truename is the same path (ASDF loads a component by resolved path).
			(print (equal *one-pathname*
			              (asdf:component-pathname
			               (car (asdf:component-children (asdf:find-system "lc-demo"))))))
			(print (equal *one-pathname* *one-truename*))
			;; A nested load restores the enclosing file's context when it finishes.
			(print (equal *one-pathname* *one-pathname-after*))
			;; That nested load: the pathname is the spelling load was called with, the
			;; truename the path it resolved to (against the loading file's directory).
			(print *helper-pathname*)
			(print (ends-with *helper-truename* "/src/helper.lisp"))
			(print (ends-with *one-truename* "/src/one.lisp"))
			;; A plain top-level load resolves against the working directory: the
			;; pathname is the path as written, the truename that file's absolute path.
			(print (list *plain-pathname* (ends-with *plain-truename* "/load-context-demo/plain.lisp")
			             (char= #\\/ (char *plain-truename* 0))))
			;; READ time: a #. datum inside a loaded file sees that file's context, and
			;; the value it captured is byte-identical with the run-time pair -- for a
			;; component, for a load nested inside one, and for a plain load.
			(print (equal *one-read-time* (format nil "~A|~A" *one-pathname* *one-truename*)))
			(print (equal *helper-read-time* (format nil "~A|~A" *helper-pathname* *helper-truename*)))
			(print (equal *plain-read-time* (format nil "~A|~A" *plain-pathname* *plain-truename*)))
			;; The idiom that motivated it: a data file read at read time from beside the
			;; source that ships it, through (or *compile-file-pathname* *load-truename*).
			(print *one-data*)
			""".formatted(PLAIN_FILE);

	private static final List<String> EXPECTED = List.of("(NIL NIL)", "(NIL NIL)", "T", "T", "T", "\"helper.lisp\"",
			"T", "T", "(\"" + PLAIN_FILE + "\" T T)", "T", "T", "T", "\"lc-demo-data\"");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "TestLoadContext";
	}

	/**
	 * The multi-file program a standard Common Lisp runs too: the entry names its sources
	 * relative to its own truename, the way SBCL needs (it resolves a relative
	 * {@code load} against the working directory), and so does a source loading the next.
	 * The interpreter runs it with the entry bound like any loaded file; the compilers
	 * fold every path and splice the files, so the class and both modules run with
	 * {@code src/} deleted -- the modules with no directory granted at all.
	 */
	@Test
	void thePortableLoadIdiomSplicesTheEntrysSourcesOnEveryBackend(@TempDir Path dir) throws Exception {
		Path app = Files.createDirectories(dir.resolve("app"));
		Path src = Files.createDirectories(app.resolve("src"));
		Files.writeString(app.resolve("main.lisp"), """
				(load (merge-pathnames "src/a.lisp" *load-truename*))
				(print (greet "world"))
				(print (list *load-pathname* *load-truename*))
				""");
		Files.writeString(src.resolve("a.lisp"), """
				(load (uiop:merge-pathnames* "b.lisp" (or *compile-file-truename* *load-truename*)))
				(defun greet (who) (format nil "~a, ~a" (salutation) who))
				(print (list *load-pathname* *load-truename*))
				""");
		Files.writeString(src.resolve("b.lisp"), "(defun salutation () \"hello\")\n");
		String entry = app.resolve("main.lisp").toString();
		String root = dir.toRealPath().toString();
		List<String> expected = List.of("(\"%1$s/app/src/a.lisp\" \"%1$s/app/src/a.lisp\")".formatted(root),
				"\"hello, world\"", "(\"%s\" \"%s/app/main.lisp\")".formatted(entry, root));
		assertThat(lines(cli(entry))).isEqualTo(expected);
		cli(entry, "-o", dir.resolve("PortableLoad.class").toString());
		Path wasm = dir.resolve("portable-load.wasm");
		cli(entry, "-o", wasm.toString());
		Path component = dir.resolve("portable-load-component.wasm");
		cli(entry, "-o", component.toString(), "--component");
		Files.delete(src.resolve("a.lisp"));
		Files.delete(src.resolve("b.lisp"));
		Files.delete(src);
		assertThat(lines(runClass(dir, "PortableLoad"))).isEqualTo(expected);
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		for (Path module : List.of(wasm, component)) {
			HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
					"exceptions=y", module.toString());
			assertThat(run.exitCode()).as("%s: %s", module, run.stderr()).isZero();
			assertThat(lines(run.stdout())).as("%s", module).isEqualTo(expected);
		}
	}

	private static String cli(String... args) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("portable-load", () -> {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8))
				.run(args);
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static List<String> lines(String output) {
		return output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
	}

	private static String runClass(Path dir, String name) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader()); var _ = ThreadStdio.out(out)) {
			CliStack.call("portable-load", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

}
