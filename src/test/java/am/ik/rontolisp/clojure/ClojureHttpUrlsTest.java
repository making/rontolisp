package am.ik.rontolisp.clojure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.eval.ClojureLibrary;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code clojure.java.io}'s reads of an {@code http:} or {@code https:} URL, through the
 * program's own {@code rontolisp:fetch} ({@code .kb/clojure-frontend.md},
 * "clojure.java.io"): what makes a program name fetch -- a URL it spells in the form a
 * read opens, or {@code rontolisp.http-urls} -- the refusal of a URL any other program
 * reads, the reads on the interpreter and the JVM, and the refusal of a target with no
 * transport. What each transport answers is {@code clojure-http-spec.yaml}'s
 * ({@code FetchSpecE2eTest#clojureHttpClient}).
 */
class ClojureHttpUrlsTest {

	@TempDir
	Path dir;

	/** The statement that installs the program's fetch, ahead of everything it runs. */
	private static final String INSTALL = "(RONTOLISP::%CLOJURE-IO-INSTALL-FETCH"
			+ " #'(LAMBDA (URL% OPTIONS%) (RONTOLISP:FETCH URL% OPTIONS%)))";

	private static List<String> lowered(String source) {
		return Clojure.read(source, null).stream().map(LispVal::print).toList();
	}

	/**
	 * Asserts the program installs its fetch once, ahead of its first statement reading a
	 * URL (a {@code slurp}).
	 */
	private static void installedAhead(List<String> forms, String what) {
		int install = forms.indexOf(INSTALL);
		int read = 0;
		while (read < forms.size() && !forms.get(read).contains("(RONTOLISP::%CLOJURE-SLURP ")) {
			read++;
		}
		assertThat(install).as(what).isNotNegative().isLessThan(read);
		assertThat(forms.stream().filter(INSTALL::equals).count()).as(what).isOne();
	}

	@Test
	void aUrlSpelledInTheFormAReadOpensMakesTheProgramFetch() {
		// a literal in slurp's argument, clojure.java.io's reader's or input-stream's, or
		// .openStream's receiver, however deep and in any case of the scheme, behind a
		// threading macro too: the program names fetch once, before anything it runs, so
		// a read above the literal reads through it too
		String io = "(require '[clojure.java.io :as io]) (def path \"/x\") (def u (first *command-line-args*))"
				+ " (prn (slurp u)) ";
		for (String read : List.of("(slurp \"https://example.com/\")",
				"(slurp (str \"https://example.com\" path) :encoding \"UTF-8\")",
				"(clojure.java.io/reader \"HTTP://example.com/\")",
				"(io/input-stream (io/as-url \"http://example.com/\"))",
				"(.openStream (io/as-url (str \"https://example.com\" path)))",
				"(-> \"https://example.com/\" io/reader line-seq)",
				"(io/make-input-stream \"http://example.com/\" {})")) {
			installedAhead(lowered(io + read), read);
		}
	}

	@Test
	void aUrlTheProgramOnlyPrintsWritesOrHoldsNamesNoFetch() {
		// a URL that is no read's -- printed, written, held in a var a read takes, a
		// docstring's, a URL value made and asked its host, a writer's -- leaves the
		// program as it was: no fetch, which a Preview 1 build refuses and a component
		// imports wasi:http for
		for (String source : List.of("(println \"see https://example.com\") (println (slurp \"notes.txt\"))",
				"(def u \"https://example.com/\") (println (slurp u))", "(spit \"out.txt\" \"https://example.com/\")",
				"(defn fetch-it \"Reads https://example.com/.\" [x] (slurp x))",
				"(require '[clojure.java.io :as io]) (println (.getHost (io/as-url \"https://example.com/\")))",
				"(require '[clojure.java.io :as io]) (io/writer \"https://example.com/\")",
				"(println (slurp \"file:/etc/hostname\"))")) {
			assertThat(String.join("\n", lowered(source))).as(source)
				.doesNotContain("RONTOLISP:FETCH")
				.doesNotContain("INSTALL-FETCH");
		}
	}

	@Test
	void requiringRontolispHttpUrlsMakesTheProgramFetchFromItsStart() {
		// a URL the program computes: the require, wherever it stands, installs the
		// fetch before the first form runs
		installedAhead(
				lowered("(def u (first *command-line-args*)) (println (slurp u)) (require 'rontolisp.http-urls)"),
				"a late require");
		installedAhead(
				lowered("(ns app (:require [rontolisp.http-urls])) (println (slurp (first *command-line-args*)))"),
				"an ns require");
	}

	@Test
	void aSessionInstallsTheFetchAheadOfTheBufferThatFirstReadsAUrl() {
		ClojureSession session = new ClojureSession();
		assertThat(forms(session.read("(def u \"https://example.com/\")"))).doesNotContain(INSTALL);
		installedAhead(forms(session.read("(prn (slurp \"https://example.com/\"))")), "the first buffer reading one");
		assertThat(forms(session.read("(prn (slurp \"https://example.com/\"))"))).doesNotContain(INSTALL);
	}

	private static List<String> forms(List<ClojureTopLevel> tops) {
		return tops.stream().flatMap(top -> top.forms().stream()).map(LispVal::print).toList();
	}

	@Test
	void aProgramReadingNoUrlSplicesTheReadsWithoutTheirFetchArm() {
		// the arm a read takes for an http: URL is the fetch family's: a program that
		// reads none compiles clojure.java.io's reads as before http: URLs were read
		String reads = "(require '[clojure.java.io :as io]) (def x (first *command-line-args*))"
				+ " (prn (slurp (io/input-stream x)) (slurp (io/reader x)) (.openStream (io/as-url x)))";
		List<String> verbs = List.of("RONTOLISP::%CLOJURE-IO-OPEN-INPUT", "RONTOLISP::%CLOJURE-IO-OPEN-READER",
				"RONTOLISP::%CLOJURE-IO-M-OPEN-STREAM");
		List<LispVal> plain = ClojureLibrary.process(Clojure.read(reads, null));
		for (String verb : verbs) {
			assertThat(defun(plain, verb)).as(verb).doesNotContain("%CLOJURE-IO-REMOTE");
		}
		List<LispVal> fetching = ClojureLibrary.process(Clojure.read("(require 'rontolisp.http-urls) " + reads, null));
		for (String verb : verbs) {
			assertThat(defun(fetching, verb)).as(verb).contains("(RONTOLISP::%CLOJURE-IO-REMOTE-P ");
		}
	}

	private static String defun(List<LispVal> forms, String name) {
		return forms.stream()
			.map(LispVal::print)
			.filter(text -> text.startsWith("(DEFUN " + name + " "))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void aUrlAProgramReadsWithoutFetchIsRefusedPointingAtTheWayIn() throws Exception {
		// a URL the program computes and reads without the require: refused by name,
		// where the oracle reads it; another protocol still is not built in
		String program = """
				(require '[clojure.java.io :as io])
				(def u (str "http" "://127.0.0.1:1/x"))
				(prn (try (slurp u) (catch UnsupportedOperationException e (ex-message e))))
				(prn (try (io/input-stream (io/as-url u)) (catch UnsupportedOperationException e (ex-message e))))
				""";
		String refusal = "\"reading the http: URL http://127.0.0.1:1/x goes through rontolisp:fetch, which this"
				+ " program does not name: write the URL as a string literal in the form that reads it, or require"
				+ " rontolisp.http-urls\"\n";
		assertThat(interpret(program)).isEqualTo(refusal + refusal);
	}

	/**
	 * A program reading a URL it spells, against a local origin: its body by
	 * {@code :encoding} whatever its charset, a redirect followed, a 404 the oracle's
	 * {@code FileNotFoundException} of the URL; {@code %ORIGIN%} is the origin.
	 */
	private static final String READS = """
			(require '[clojure.java.io :as io])
			(prn (slurp (str "%ORIGIN%" "/text")))
			(prn (count (slurp (io/as-url (str "%ORIGIN%" "/text")) :encoding "ISO-8859-1")))
			(prn (slurp (str "%ORIGIN%" "/moved")))
			(prn (try (slurp (str "%ORIGIN%" "/gone")) (catch java.io.FileNotFoundException e (subs (ex-message e) (count "%ORIGIN%")))))
			(with-open [in (.openStream (io/as-url (str "%ORIGIN%" "/text")))]
			  (prn (instance? java.io.FilterInputStream in) (.read in)))
			""";

	private static final String READS_OUT = """
			"héllo"
			6
			"héllo"
			"/gone"
			true 104
			""";

	@Test
	void aUrlTheProgramSpellsIsReadOnTheInterpreterAndTheJvm() throws Exception {
		HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		origin.createContext("/text", exchange -> {
			// a charset the read does not go by
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=ISO-8859-1");
			answer(exchange, 200, "héllo".getBytes(StandardCharsets.UTF_8));
		});
		origin.createContext("/moved", exchange -> {
			exchange.getResponseHeaders().add("Location", "/text");
			answer(exchange, 301, new byte[0]);
		});
		origin.createContext("/gone", exchange -> answer(exchange, 404, new byte[0]));
		origin.start();
		try {
			String program = READS.replace("%ORIGIN%", "http://127.0.0.1:" + origin.getAddress().getPort());
			assertThat(interpret(program)).isEqualTo(READS_OUT);
			assertThat(runOnJvm(program, "HttpUrlReads")).isEqualTo(READS_OUT);
		}
		finally {
			origin.stop(0);
		}
	}

	private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	@Test
	void aTargetWithNoFetchTransportRefusesAProgramReadingAUrlWhenItCompiles() throws Exception {
		// plain Preview 1 has no host answering HTTP, a --no-wasi build no import unless
		// --host-fetch asks for env.fetch: both refusals name clojure.java.io's read
		Path source = this.dir.resolve("reads.clj");
		Files.writeString(source, "(println (slurp \"http://127.0.0.1:1/\"))");
		assertThatThrownBy(() -> compile(source, "-o", this.dir.resolve("p1.wasm").toString()))
			.hasMessageContaining("clojure.java.io")
			.hasMessageContaining("--component");
		assertThatThrownBy(() -> compile(source, "-o", this.dir.resolve("reactor.wasm").toString(), "--no-wasi"))
			.hasMessageContaining("clojure.java.io")
			.hasMessageContaining("--host-fetch");
	}

	private static void compile(Path source, String... args) {
		String[] all = new String[args.length + 1];
		all[0] = source.toString();
		System.arraycopy(args, 0, all, 1, args.length);
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(all);
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-http-urls", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator.clojureProgram(SourceLanguage.CLOJURE.read(program, Features.INTERPRETER,
					null, SourceStandards.DEFAULT, SourceLoader.fileSystem()))) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnJvm(String program, String name) throws Exception {
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null);
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
			CliStack.call("clojure-http-urls", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

}
