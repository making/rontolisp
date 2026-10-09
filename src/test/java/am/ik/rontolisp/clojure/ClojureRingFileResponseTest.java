package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
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
 * {@code ring.util.response}'s file, URL and resource responses over a directory, the
 * source path's resources directory and a jar, on the interpreter, the JVM and both WASM
 * backends (a {@code --dir} preopen covering the whole tree), and the part of the
 * namespace they live in, which loads only where a program names one of them. The
 * expected output is the oracle's (clj 1.12.6 + ring-core 1.15.5, 2026-10-09) but where a
 * comment says otherwise.
 */
class ClojureRingFileResponseTest {

	@TempDir
	Path dir;

	/** Prints each response as its status, two headers and its body's path or text. */
	private static final String PROGRAM = """
			(ns app.main (:require [ring.util.response :as r] [clojure.java.io :as io]))
			(def root (str "%ROOT%" ""))
			(def www (str root "/www"))
			(defn show [resp]
			  (when resp
			    (let [b (:body resp)]
			      [(:status resp) (get-in resp [:headers "Content-Length"]) (get-in resp [:headers "Last-Modified"])
			       (if (instance? java.io.File b) (subs (str b) (count root)) (slurp b))])))
			(prn (show (r/file-response "a.txt" {:root www})))
			(prn (show (r/file-response "/a.txt" {:root www})) (slurp (:body (r/file-response "a.txt" {:root www}))))
			(prn (show (r/file-response "dir" {:root www})) (show (r/file-response "dir2" {:root www})))
			(prn (r/file-response "dir3" {:root www}) (r/file-response "dir" {:root www :index-files? false}))
			(prn (r/file-response "nope.txt" {:root www}) (r/file-response "../outside.txt" {:root www})
			     (r/file-response "../outside.txt" {:root www :allow-symlinks? true}))
			(prn (show (r/file-response "dir/../a.txt" {:root www})) (show (r/file-response (str www "/a.txt"))))
			(prn (sort (keys (r/file-response "a.txt" {:root www}))) (sort (keys (:headers (r/file-response "a.txt" {:root www})))))
			(prn (show (r/resource-response "public/r.txt")))
			(prn (= (r/resource-response "public/r.txt") (r/resource-response "r.txt" {:root "public"})
			        (r/resource-response "/r.txt" {:root "/public"}) (r/url-response (io/resource "public/r.txt"))))
			(prn (r/resource-response "public/sub") (r/resource-response "nope" {:root "public"})
			     (r/resource-response "../r.txt" {:root "public/sub"}))
			(prn (show (r/url-response (io/resource "jarres/j.txt"))))
			(let [d (r/resource-data (io/resource "jarres/j.txt"))]
			  (prn (sort (keys d)) (:content-length d) (:last-modified d) (instance? java.io.InputStream (:content d))))
			(let [d (r/resource-data (io/resource "public/r.txt"))]
			  (prn (subs (str (:content d)) (count root)) (:content-length d) (inst? (:last-modified d))))
			(prn (sort (keys (methods r/resource-data))))
			(prn (r/resource-response (str "jarres/" "j.txt")))
			""";

	private static final String FILE_DATE = "\"Tue, 02 Jan 2024 03:04:05 GMT\"";

	private static final String JAR_DATE = "\"Wed, 06 May 2020 07:08:09 GMT\"";

	/**
	 * The oracle's output, the last line aside: there a computed resource name a jar
	 * holds is found and served, here nil (clojure.java.io's documented deviation).
	 */
	private static final String OUT = """
			[200 "11" %F "/www/a.txt"]
			[200 "11" %F "/www/a.txt"] "hello file\\n"
			[200 "12" %F "/www/dir/index.html"] [200 "1" %F "/www/dir2/index.css"]
			nil nil
			nil nil nil
			[200 "11" %F "/www/dir/../a.txt"] [200 "11" %F "/www/a.txt"]
			(:body :headers :status) ("Content-Length" "Last-Modified")
			[200 "9" %F "/proj/resources/public/r.txt"]
			true
			nil nil nil
			[200 "7" %J "in jar\\n"]
			(:content :content-length :last-modified) 7 %I true
			"/proj/resources/public/r.txt" 9 true
			(:file :jar)
			nil
			""";

	private static final Instant FILE_TIME = Instant.parse("2024-01-02T03:04:05Z");

	private static final Instant JAR_TIME = Instant.parse("2020-05-06T07:08:09Z");

	private Path project() throws IOException {
		Path root = this.dir.toRealPath();
		Path proj = root.resolve("proj");
		write(proj.resolve("deps.edn"),
				"{:paths [\"src\" \"resources\"]\n :deps {my/res {:local/root \"../res.jar\"}}}\n");
		write(proj.resolve("resources/public/r.txt"), "res text\n");
		write(proj.resolve("resources/public/sub/s.txt"), "sub");
		write(root.resolve("www/a.txt"), "hello file\n");
		write(root.resolve("www/dir/index.html"), "<h1>idx</h1>");
		write(root.resolve("www/dir2/index.css"), "x");
		Files.createDirectories(root.resolve("www/dir3"));
		write(root.resolve("outside.txt"), "outside");
		for (String file : new String[] { "www/a.txt", "www/dir/index.html", "www/dir2/index.css",
				"proj/resources/public/r.txt" }) {
			Files.setLastModifiedTime(root.resolve(file), FileTime.from(FILE_TIME));
		}
		Path jar = root.resolve("res.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("jarres/j.txt"));
			zip.write("in jar\n".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		Files.setLastModifiedTime(jar, FileTime.from(JAR_TIME));
		return write(proj.resolve("src/app/main.clj"), PROGRAM.replace("%ROOT%", root.toString()));
	}

	private static final String EXPECTED = OUT.replace("%F", FILE_DATE)
		.replace("%J", JAR_DATE)
		.replace("%I", "#inst \"2020-05-06T07:08:09.000-00:00\"");

	@Test
	void fileUrlAndResourceResponsesOnTheInterpreterAndTheJvm() throws Exception {
		Path main = project();
		assertThat(interpret(Files.readString(main), main)).isEqualTo(EXPECTED);
		assertThat(runOnJvm(Files.readString(main), main, "RingFiles")).isEqualTo(EXPECTED);
	}

	@Test
	void fileUrlAndResourceResponsesOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path main = project();
		assertThat(runOnWasm(Files.readString(main), main, false)).isEqualTo(EXPECTED);
		assertThat(runOnWasm(Files.readString(main), main, true)).isEqualTo(EXPECTED);
	}

	@Test
	void theFileResponsesLoadOnlyWhereAProgramNamesOne() {
		String plain = lowered("(ns a (:require [ring.util.response :as r])) (prn (r/response 1))");
		assertThat(plain).doesNotContain("file-response").doesNotContain("%CLOJURE-IO-");
		assertThat(lowered("(ns a (:require [ring.util.response :as r])) (prn (r/file-response \"x\"))"))
			.contains("|c%ring.util.response/file-response");
		assertThat(lowered("(ns a (:require [ring.util.response :refer [url-response]])) (prn url-response)"))
			.contains("|c%ring.util.response/url-response");
		assertThat(lowered("(ns a (:require [ring.util.response :refer :all])) (prn (resource-response \"x\"))"))
			.contains("|c%ring.util.response/resource-response");
		assertThat(lowered("(ns a (:require [ring.util.response :refer :all])) (prn (response 1))"))
			.doesNotContain("file-response");
	}

	@Test
	void aPartVarAnswersAsTheNamespacesOwnOnTheInterpreter() throws Exception {
		String out = interpret("""
				(ns a (:require [ring.util.response :refer :all]))
				(prn (file-response "no/such/file") #'ring.util.response/resource-data
				     (str (:ns (meta #'url-response))) (fn? resource-response))
				""", null);
		assertThat(out).isEqualTo("nil #'ring.util.response/resource-data \"ring.util.response\" true\n");
	}

	private static String lowered(String program) {
		return Clojure.read(program, null).stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	private static Path write(Path path, String text) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
		return path;
	}

	private static String interpret(String program, @Nullable Path entry) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-ring-files", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER,
					entry == null ? null : entry.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem())) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnJvm(String program, Path entry, String name) throws Exception {
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
			.compile(program, entry.toString());
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
			CliStack.call("clojure-ring-files", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnWasm(String program, Path entry, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, entry.toString(), true,
				component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(this.dir, "ring", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", "--dir", this.dir.toRealPath().toString(), path.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

}
