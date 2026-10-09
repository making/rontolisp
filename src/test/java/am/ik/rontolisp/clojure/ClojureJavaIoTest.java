package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code clojure.java.io} over directories and the source path, on the interpreter, the
 * JVM and both WASM backends (a {@code --dir} preopen covering the directory): what needs
 * a directory of its own, which the shared spec program ({@code clojure-spec.yaml}, the
 * {@code clojure-java-io-*} cases) cannot hold. The expected output is the oracle's
 * ({@code clj} 1.12.6, 2026-10-08) but where a comment says otherwise.
 */
class ClojureJavaIoTest {

	@TempDir
	Path dir;

	/**
	 * Makes, lists, walks and deletes below the root the program's first form names: the
	 * oracle's answers, each path printed below the root.
	 */
	private static final String DIRECTORIES = """
			(require '[clojure.java.io :as io])
			(def d (io/file root "tree"))
			(prn (.mkdirs d) (.mkdirs d) (.mkdir d) (.isDirectory d) (.exists d) (.isFile d))
			(prn (.mkdir (io/file d "x" "y")) (.mkdirs (io/file d "x" "y")) (.mkdir (io/file d "z")))
			(spit (io/file d "a.txt") "a")
			(spit (io/file d "x" "b.txt") "bb")
			(prn (sort (.list d)) (.list (io/file d "a.txt")) (.listFiles (io/file d "missing")))
			(prn (sort (map #(.getName %) (.listFiles d))) (every? #(instance? java.io.File %) (.listFiles d)))
			(prn (map #(subs (str %) (count root)) (sort-by str (file-seq d))))
			(prn (realized? (file-seq d)) (map #(.getName %) (take 1 (file-seq d))))
			(prn (io/make-parents d "p" "q" "r.txt") (io/make-parents d "p" "q" "r.txt") (.isDirectory (io/file d "p" "q")))
			(prn (.length (io/file d "x" "b.txt")) (.isDirectory (io/file d "x")) (.isFile (io/file d "x" "b.txt")))
			(prn (.delete d) (io/delete-file (io/file d "a.txt")) (.delete (io/file d "x" "b.txt")))
			(prn (try (slurp (io/file d "x")) (catch java.io.FileNotFoundException e (subs (ex-message e) (count root)))))
			(prn (try (spit (io/file d "nope" "f.txt") "x") (catch java.io.FileNotFoundException e (subs (ex-message e) (count root)))))
			""";

	private static final String DIRECTORIES_OUT = """
			true false false true true false
			false true true
			("a.txt" "x" "z") nil nil
			("a.txt" "x" "z") true
			("/tree" "/tree/a.txt" "/tree/x" "/tree/x/b.txt" "/tree/x/y" "/tree/z")
			false ("tree")
			true false true
			2 true true
			false true true
			"/tree/x (Is a directory)"
			"/tree/nope/f.txt (No such file or directory)"
			""";

	@Test
	void directoriesAreMadeListedWalkedAndDeletedOnTheInterpreterAndTheJvm() throws Exception {
		assertThat(interpret(rooted(DIRECTORIES, "i"), null)).isEqualTo(DIRECTORIES_OUT);
		assertThat(runOnJvm(rooted(DIRECTORIES, "j"), null, "JioDirs")).isEqualTo(DIRECTORIES_OUT);
	}

	@Test
	void directoriesAreMadeListedWalkedAndDeletedOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		assertThat(runOnWasm(rooted(DIRECTORIES, "w"), null, false, this.dir)).isEqualTo(DIRECTORIES_OUT);
		assertThat(runOnWasm(rooted(DIRECTORIES, "c"), null, true, this.dir)).isEqualTo(DIRECTORIES_OUT);
	}

	/**
	 * An empty directory is deleted and a non-empty one is not, like the oracle; a file's
	 * {@code .lastModified} is its modification time in whole seconds' worth of
	 * milliseconds (between the first instants of 2023 and 2100), a missing file's 0. The
	 * same answers on all four backends: wasm removes the directory through
	 * {@code path_remove_directory} and dates the file through {@code path_filestat_get}.
	 */
	private static final String EMPTY_DIRECTORY = """
			(def e (java.io.File. root "empty"))
			(prn (.mkdir e) (.delete e) (.exists e))
			(def full (java.io.File. root "full"))
			(def inner (java.io.File. full "f.txt"))
			(prn (.mkdir full) (.createNewFile inner) (.delete full) (.exists full))
			(def stamp (.lastModified inner))
			(prn (< 1672531200000 stamp 4102444800000) (zero? (mod stamp 1000)))
			(prn (.lastModified (java.io.File. root "missing")))
			""";

	private static final String EMPTY_DIRECTORY_OUT = """
			true true false
			true true false true
			true true
			0
			""";

	@Test
	void anEmptyDirectoryIsDeletedAndAFileDatedOnEveryBackend() throws Exception {
		assertThat(interpret(rooted(EMPTY_DIRECTORY, "ei"), null)).isEqualTo(EMPTY_DIRECTORY_OUT);
		assertThat(runOnJvm(rooted(EMPTY_DIRECTORY, "ej"), null, "JioEmpty")).isEqualTo(EMPTY_DIRECTORY_OUT);
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		assertThat(runOnWasm(rooted(EMPTY_DIRECTORY, "ew"), null, false, this.dir)).isEqualTo(EMPTY_DIRECTORY_OUT);
		assertThat(runOnWasm(rooted(EMPTY_DIRECTORY, "ec"), null, true, this.dir)).isEqualTo(EMPTY_DIRECTORY_OUT);
	}

	/**
	 * A project whose source path holds a resources directory and a jar: a literal name
	 * is found while the program lowers (a jar's entry too, which travels with the
	 * program), a computed one below the directory roots when the program runs. The
	 * oracle's answers, the last line aside: there a computed name a jar holds is found
	 * too, here nil (the documented deviation).
	 */
	private static final String RESOURCES = """
			(ns app.main (:require [clojure.java.io :as io]))
			(prn (slurp (io/resource "conf.edn")))
			(prn (slurp (io/resource "jarres/msg.txt")))
			(prn (str (io/resource "conf.edn")))
			(prn (str (io/resource "jarres/msg.txt")))
			(def computed (str "co" "nf.edn"))
			(prn (some-> (io/resource computed) slurp))
			(prn (io/resource "missing.txt") (io/resource (str "miss" "ing.txt")))
			(with-open [r (io/reader (io/resource "conf.edn"))] (prn (read-string (slurp r))))
			(prn (.getPath (io/resource "conf.edn")) (.getProtocol (io/resource "jarres/msg.txt")))
			(with-open [in (io/input-stream (io/resource "jarres/msg.txt"))] (prn (.read in)))
			(prn (io/resource (str "jarres/" "msg.txt")))
			""";

	private Path resourceProject() throws IOException {
		Path proj = this.dir.resolve("proj");
		write(proj.resolve("deps.edn"),
				"{:paths [\"src\" \"resources\"]\n :deps {my/res {:local/root \"../res.jar\"}}}\n");
		write(proj.resolve("resources/conf.edn"), "{:a 1}\n");
		Path main = write(proj.resolve("src/app/main.clj"), RESOURCES);
		try (OutputStream out = Files.newOutputStream(this.dir.resolve("res.jar"));
				ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, String> entry : Map.of("jarres/msg.txt", "from the jar\n").entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return main;
	}

	private String resourcesOut() throws IOException {
		String proj = this.dir.resolve("proj").toRealPath().toString();
		String jar = this.dir.resolve("res.jar").toRealPath().toString();
		return "\"{:a 1}\\n\"\n\"from the jar\\n\"\n\"file:" + proj + "/resources/conf.edn\"\n\"jar:file:" + jar
				+ "!/jarres/msg.txt\"\n\"{:a 1}\\n\"\nnil nil\n{:a 1}\n\"" + proj
				+ "/resources/conf.edn\" \"jar\"\n102\nnil\n";
	}

	@Test
	void resourcesComeFromTheSourcePathOnTheInterpreterAndTheJvm() throws Exception {
		Path main = resourceProject();
		assertThat(interpret(Files.readString(main), main)).isEqualTo(resourcesOut());
		assertThat(runOnJvm(Files.readString(main), main, "JioResources")).isEqualTo(resourcesOut());
	}

	@Test
	void resourcesComeFromTheSourcePathOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path main = resourceProject();
		// the computed lookup reads the resources directory, which the preopen covers
		Path proj = this.dir.resolve("proj").toRealPath();
		assertThat(runOnWasm(Files.readString(main), main, false, proj)).isEqualTo(resourcesOut());
		assertThat(runOnWasm(Files.readString(main), main, true, proj)).isEqualTo(resourcesOut());
	}

	/**
	 * A URL of another protocol than {@code file:} names no file: reading one is refused
	 * by name (the oracle opens a connection), writing one in the oracle's words.
	 */
	private static final String NON_FILE_URL = """
			(require '[clojure.java.io :as io])
			(prn (try (slurp (io/as-url "http://example.invalid/x")) (catch UnsupportedOperationException e (ex-message e))))
			(prn (try (io/writer "https://example.invalid/y") (catch IllegalArgumentException e (ex-message e))))
			""";

	@Test
	void aUrlOfAnotherProtocolThanFileIsRefused() throws Exception {
		String out = """
				"reading the http: URL http://example.invalid/x is not built in"
				"Can not write to non-file URL <https://example.invalid/y>"
				""";
		assertThat(interpret(NON_FILE_URL, null)).isEqualTo(out);
		assertThat(runOnJvm(NON_FILE_URL, null, "JioNonFileUrl")).isEqualTo(out);
	}

	/** The program with {@code root} defined first: a fresh directory of its own. */
	private String rooted(String program, String leg) throws IOException {
		Path root = Files.createDirectories(this.dir.resolve(leg)).toRealPath();
		// built when the program runs, as a path a program computes
		return "(def root (str \"" + root + "\" \"\"))\n" + program;
	}

	private static Path write(Path path, String text) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
		return path;
	}

	private static String interpret(String program, @Nullable Path entry) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-jio", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator.clojureProgram(SourceLanguage.CLOJURE.read(program, Features.INTERPRETER,
					entry == null ? null : entry.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem()))) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnJvm(String program, @Nullable Path entry, String name) throws Exception {
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
			.compile(program, entry == null ? null : entry.toString());
		Path classes = Files.createTempDirectory(this.dir, name);
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
				new java.net.URL[] { classes.toUri().toURL() }, ClassLoader.getSystemClassLoader());
				ThreadStdio.Scope redirected = ThreadStdio.out(out)) {
			CliStack.call("clojure-jio", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnWasm(String program, @Nullable Path entry, boolean component, Path preopen) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program,
				entry == null ? null : entry.toString(), true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(this.dir, "jio", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", "--dir", preopen.toString(), path.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

}
