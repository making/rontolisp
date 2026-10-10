package am.ik.rontolisp.clojure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * The namespaces the Clojure front end ships as Clojure source: the pure-function subset
 * of ring-core and ring-codec, the counterpart of the built-in Clack shims, and the
 * map-shaped namespaces of clojure.jar, written for this front end from their documented
 * behaviour (none of clojure.jar's own source is copied). Each is a classpath resource
 * below {@code lib/} next to this class, read through the namespace loader like a project
 * file -- after the project's own source roots, so a project file of the name wins, as a
 * source directory precedes a dependency jar on the oracle's classpath -- except a
 * namespace the oracle loads before the program ({@link #isStartup}), whose file a
 * project never shadows. What Clojure would spell through generic verbs at ten times the
 * size lives in the {@link ClojureKernelLowering} kernels, which only these files can
 * require.
 *
 * <p>
 * A var of the oracle's namespace outside the subset, and a ring-core namespace not
 * shipped, are refused by name rather than with the missing-var or missing-file words.
 */
final class ClojureBuiltinNamespaces {

	/**
	 * The shipped namespaces, each with the public vars of the oracle's namespace
	 * (ring-core 1.15.5, ring-codec 1.3.0, clj 1.12.6; babashka.http-client 0.4.23 for
	 * {@code rontolisp.http-client}, which has its API) it leaves out and why;
	 * {@code rontolisp.http-urls} defines no var: its load is the program's choice to
	 * read {@code http:} URLs through its fetch ({@link ClojureLowering#readsHttpUrls}).
	 */
	private static final Map<String, Map<String, String>> SHIPPED = Map.ofEntries(
			Map.entry("ring.util.response", Map.of()), Map.entry("ring.util.request", Map.of()),
			Map.entry("ring.util.codec",
					Map.of("form-encode*", "form-encode is a function, not a protocol, here", "FormEncodeable",
							"form-encode is a function, not a protocol, here")),
			Map.entry("ring.util.mime-type", Map.of()), Map.entry("ring.middleware.params", Map.of()),
			Map.entry("ring.middleware.keyword-params", Map.of()), Map.entry("ring.middleware.content-type", Map.of()),
			Map.entry("clojure.walk", Map.of()), Map.entry("clojure.template", Map.of()),
			Map.entry("clojure.data", Map.of()), Map.entry("clojure.zip", Map.of()),
			Map.entry("clojure.core.protocols", Map.of("iterator-reduce!", "it reduces a java.util.Iterator")),
			Map.entry("clojure.core.reducers",
					Map.of("fjtask", "there is no fork/join pool: fold reduces its parts one after the other", "pool",
							"there is no fork/join pool: fold reduces its parts one after the other", "->Cat",
							"cat joins two collections into one accumulator, no Cat")),
			Map.entry("clojure.datafy", Map.of()), Map.entry("clojure.stacktrace", Map.of()),
			Map.entry("clojure.instant", Map.of()), Map.entry("clojure.uuid", Map.of()),
			Map.entry("clojure.math", Map.of()), Map.entry("clojure.java.io", Map.of()),
			Map.entry("clojure.repl", replLeftOut()), Map.entry("clojure.main", mainLeftOut()),
			Map.entry("clojure.java.shell", Map.of()), Map.entry("clojure.xml", xmlLeftOut()),
			Map.entry("clojure.pprint", Map.of()), Map.entry("rontolisp.http-client", httpClientLeftOut()),
			Map.entry(ClojureIoLowering.HTTP_URLS, Map.of()));

	/**
	 * The public vars of babashka.http-client 0.4.23 that {@code rontolisp.http-client}
	 * leaves out: each builds or configures a {@code java.net.http.HttpClient}, and the
	 * transport here is {@code rontolisp:fetch}, which the target picks.
	 */
	private static Map<String, String> httpClientLeftOut() {
		String why = "it builds a java.net.http client; the transport is rontolisp:fetch, which the target picks";
		return Map.of("client", why, "default-client-opts", why, "->ProxySelector", why, "->SSLContext", why,
				"->Authenticator", why, "->CookieHandler", why, "->SSLParameters", why, "->Executor", why);
	}

	/**
	 * The public vars of {@code clojure.repl} it leaves out: what reads every namespace's
	 * vars, a definition's text, or the host's signals and threads.
	 */
	private static Map<String, String> replLeftOut() {
		String vars = "a namespace's vars are known only while the program is lowered,"
				+ " so ns-publics and all-ns are not built in";
		String text = "a definition's text is not kept at run time";
		return Map.of("dir", vars, "dir-fn", vars, "apropos", vars, "find-doc", vars, "source", text, "source-fn", text,
				"set-break-handler!", "there is no INT signal handler", "thread-stopper",
				"it stops a thread with Thread.stop, which the JDK no longer supports");
	}

	/**
	 * The public vars of {@code clojure.main} it leaves out: the REPL and the script
	 * runner, which evaluate forms read at run time, and their parts.
	 */
	private static Map<String, String> mainLeftOut() {
		String eval = "no compiler runs at run time, so eval and load are not built in";
		String repl = "it is part of clojure.main/repl, which needs eval: no compiler runs at run time";
		return Map.of("repl", eval, "main", eval, "load-script", eval, "repl-read", repl, "renumbering-read", repl,
				"skip-whitespace", repl, "skip-if-eol", repl, "with-bindings", repl, "report-error",
				"it writes the report clojure.main/main makes of an uncaught exception, and main is not built in");
	}

	/**
	 * The public vars of {@code clojure.xml} it leaves out: the oracle's one
	 * {@code ContentHandler} and the vars it keeps its state in.
	 */
	private static Map<String, String> xmlLeftOut() {
		String state = "parse keeps its state in the call, not in vars";
		return Map.of("content-handler", "parse hands a startparse function a ContentHandler made for that call",
				"*stack*", state, "*current*", state, "*state*", state, "*sb*", state);
	}

	/**
	 * The shipped namespaces whose vars need the host's own API, with what for: the
	 * interpreter and the JVM have it, a WebAssembly target does not, so a
	 * {@code require} there is refused while lowering rather than at the first call.
	 */
	private static final Map<String, String> HOST_ONLY = Map.of("clojure.java.shell",
			"sh launches a host process, which the interpreter and the JVM can and a WebAssembly target cannot");

	/**
	 * A part of a shipped namespace: a file below {@code lib/} defining some of the
	 * namespace's vars, loaded into it where a program first names one of them.
	 *
	 * @param resource the file, below {@code lib/}
	 * @param vars the public vars it defines
	 */
	record Part(String resource, Set<String> vars) {
	}

	/**
	 * The parts of the shipped namespaces. {@code ring.util.response}'s file, URL and
	 * resource responses make a {@code java.io.File} and a byte stream, and every program
	 * that could make one carries the io family's arms (+44 KB on
	 * {@code ring-hello.clj}'s wasm module, measured 2026-10-09); as a part, only a
	 * program naming one of them does. {@code clojure.walk}'s {@code macroexpand-all}
	 * expands at run time, which keeps every macro expander of the program
	 * ({@code ClojureMacroLowering.macroRuntime}); as a part, a program walking data
	 * keeps none. {@code ring.util.codec}'s base64 pair takes and answers a byte array,
	 * so every program that could make one carries the byte-array family's arms
	 * ({@link ClojureBytesLowering}); as a part, only a program naming one of them does.
	 */
	private static final Map<String, Part> PARTS = Map.of("ring.util.response",
			new Part("ring/util/response_files.clj",
					Set.of("file-response", "url-response", "resource-response", "resource-data")),
			"clojure.walk", new Part("clojure/walk_macroexpand.clj", Set.of("macroexpand-all")), "ring.util.codec",
			new Part("ring/util/codec_base64.clj", Set.of("base64-encode", "base64-decode")));

	/**
	 * The part of a shipped namespace defining the var.
	 * @param ns the namespace
	 * @param var the var name
	 * @return the part, or {@code null} when the namespace's own file defines the var or
	 * no part does
	 */
	static @Nullable Part partOf(String ns, String var) {
		Part part = PARTS.get(ns);
		return part != null && part.vars().contains(var) ? part : null;
	}

	/**
	 * The public vars the parts of a shipped namespace define, which {@code :refer :all}
	 * refers like the namespace's own.
	 * @param ns the namespace
	 * @return the var names
	 */
	static Set<String> partVars(String ns) {
		Part part = PARTS.get(ns);
		return part == null ? Set.of() : part.vars();
	}

	/**
	 * The source of a part.
	 * @param part the part
	 * @return its text
	 */
	static String partSource(Part part) {
		return read("lib/" + part.resource());
	}

	/**
	 * The shipped namespaces {@code clj -M} has loaded before the program runs: a
	 * qualified name reaches one without a {@code require}, and a {@code require} of one
	 * reads no project file.
	 */
	private static final Set<String> STARTUP = Set.of("clojure.walk", "clojure.core.protocols", "clojure.instant",
			"clojure.uuid", "clojure.java.io", "clojure.main");

	/**
	 * The namespaces clojure.jar 1.12.6 defines, plus those of the spec jars it depends
	 * on: the language's own, which are lowerings or built-in files here and never
	 * project files. Any other {@code clojure.*} namespace (a contrib library such as
	 * {@code clojure.data.json}) is a library like any other, found on the source path.
	 */
	private static final Set<String> LANGUAGE = Set.of("clojure.core", "clojure.core.protocols",
			"clojure.core.reducers", "clojure.core.server", "clojure.data", "clojure.datafy", "clojure.edn",
			"clojure.inspector", "clojure.instant", "clojure.java.basis", "clojure.java.basis.impl",
			"clojure.java.browse", "clojure.java.browse-ui", "clojure.java.io", "clojure.java.javadoc",
			"clojure.java.process", "clojure.java.shell", "clojure.main", "clojure.math", "clojure.parallel",
			"clojure.pprint", "clojure.reflect", "clojure.repl", "clojure.repl.deps", "clojure.set",
			"clojure.stacktrace", "clojure.string", "clojure.template", "clojure.test", "clojure.test.junit",
			"clojure.test.tap", "clojure.tools.deps.interop", "clojure.uuid", "clojure.walk", "clojure.xml",
			"clojure.zip", "clojure.spec.alpha", "clojure.spec.gen.alpha", "clojure.spec.test.alpha",
			"clojure.core.specs.alpha");

	/**
	 * The language's namespaces the oracle's REPL requires that are not shipped, with
	 * why: a refer of one of their vars names it.
	 */
	private static final Map<String, String> LANGUAGE_NOT_SHIPPED = Map.of("clojure.java.javadoc",
			"it opens a web browser on a class's Javadoc", "clojure.repl.deps",
			"it adds libraries to a running REPL; a session's libraries are the project's deps.edn");

	/**
	 * The refers {@code clojure.main/repl-requires} (clj 1.12.6) names, per namespace:
	 * what the oracle's REPL refers into {@code user} before the first input.
	 */
	private static final Map<String, List<String>> REPL_REQUIRES = Map.of("clojure.repl",
			List.of("source", "apropos", "dir", "pst", "doc", "find-doc"), "clojure.java.javadoc", List.of("javadoc"),
			"clojure.pprint", List.of("pp", "pprint"), "clojure.repl.deps",
			List.of("add-libs", "add-lib", "sync-deps"));

	/** The ring-core namespaces left out, refused by name. */
	private static final Set<String> NOT_SHIPPED = Set.of("ring.middleware.content-length", "ring.middleware.cookies",
			"ring.middleware.file", "ring.middleware.file-info", "ring.middleware.flash", "ring.middleware.head",
			"ring.middleware.multipart-params", "ring.middleware.multipart-params.byte-array",
			"ring.middleware.multipart-params.temp-file", "ring.middleware.nested-params",
			"ring.middleware.not-modified", "ring.middleware.resource", "ring.middleware.session",
			"ring.middleware.session.cookie", "ring.middleware.session.memory", "ring.middleware.session.store",
			"ring.util.async", "ring.util.io", "ring.util.parsing", "ring.util.test", "ring.util.time",
			"ring.websocket", "ring.core.protocols", "ring.websocket.protocols", "ring.adapter.jetty");

	private ClojureBuiltinNamespaces() {
	}

	/**
	 * Whether the namespace is shipped.
	 * @param ns the namespace
	 * @return whether a built-in file defines it
	 */
	static boolean isShipped(String ns) {
		return SHIPPED.containsKey(ns);
	}

	/**
	 * Whether the namespace is one of the language's own (clojure.jar's, or a spec jar's
	 * it depends on), which no project file defines.
	 * @param ns the namespace
	 * @return whether it is the language's
	 */
	static boolean isLanguage(String ns) {
		return LANGUAGE.contains(ns);
	}

	/**
	 * Why one of the language's namespaces is refused, when the oracle's REPL requires it
	 * and it is not shipped.
	 * @param ns the namespace
	 * @return the refusal, or {@code null} for any other namespace
	 */
	static @Nullable String languageNotShipped(String ns) {
		String why = LANGUAGE_NOT_SHIPPED.get(ns);
		return why == null ? null : ns + " is not built in: " + why;
	}

	/**
	 * The refers of the oracle's REPL, which a session refers into {@code user}.
	 * @return the vars, per namespace
	 */
	static Map<String, List<String>> replRequires() {
		return REPL_REQUIRES;
	}

	/**
	 * Whether the oracle's REPL loads the namespace before the first input.
	 * @param ns the namespace
	 * @return whether a session reaches its vars without a {@code require}
	 */
	static boolean isReplRequire(String ns) {
		return REPL_REQUIRES.containsKey(ns);
	}

	/**
	 * Why a var the oracle's REPL refers is refused: one its namespace leaves out, or any
	 * of a namespace not shipped.
	 * @param ns the namespace
	 * @param var the var name
	 * @return the refusal, or {@code null} for a var the namespace defines
	 */
	static @Nullable String replRefusal(String ns, String var) {
		String why = LANGUAGE_NOT_SHIPPED.get(ns);
		return why == null ? leftOut(ns, var) : ns + "/" + var + " is not built in: " + why;
	}

	/**
	 * Whether the namespace is shipped and loaded before the program, like the oracle's
	 * {@code clojure.walk}.
	 * @param ns the namespace
	 * @return whether a qualified name reaches it without a {@code require}
	 */
	static boolean isStartup(String ns) {
		return STARTUP.contains(ns);
	}

	/**
	 * The shipped namespaces the oracle loads before the program.
	 * @return their names
	 */
	static Set<String> startup() {
		return STARTUP;
	}

	/**
	 * The source of a shipped namespace.
	 * @param ns the namespace
	 * @return its text, or {@code null} for a namespace not shipped
	 */
	static @Nullable String source(String ns) {
		if (!isShipped(ns)) {
			return null;
		}
		return read("lib/" + ClojureSourcePath.resourceOf(ns));
	}

	private static String read(String resource) {
		try (InputStream in = ClojureBuiltinNamespaces.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Why a namespace no root holds is refused, when it is one of the oracle's Ring
	 * namespaces this front end does not ship, or babashka.http-client's, whose API
	 * {@code rontolisp.http-client} has under its own name.
	 * @param ns the namespace
	 * @return the refusal, or {@code null} for any other namespace
	 */
	static @Nullable String notShipped(String ns) {
		if (ns.equals("ring.adapter.jetty")) {
			return "ring.adapter.jetty is not built in: serve a Ring handler with ring.adapter.rontolisp/run-server";
		}
		if (ns.equals("babashka.http-client") || ns.startsWith("babashka.http-client.")) {
			// a claimed name promises its options; the client here has a name of its own
			return ns + " is not built in: rontolisp.http-client has its API over rontolisp:fetch";
		}
		return NOT_SHIPPED.contains(ns) ? ns + " is not built in: " + shippedRingList() : null;
	}

	/**
	 * Why a shipped namespace is refused on a target without the host.
	 * @param ns the namespace
	 * @return the refusal, or {@code null} for a namespace every target runs
	 */
	static @Nullable String hostOnly(String ns) {
		String why = HOST_ONLY.get(ns);
		return why == null ? null : ns + " is not built in on this target: " + why;
	}

	/**
	 * Why a var of a shipped namespace is refused, when the oracle's namespace has it.
	 * @param ns the namespace
	 * @param var the var name
	 * @return the refusal, or {@code null} for any other name
	 */
	static @Nullable String leftOut(String ns, String var) {
		Map<String, String> out = SHIPPED.get(ns);
		String why = out == null ? null : out.get(var);
		return why == null ? null : ns + "/" + var + " is not built in: " + why;
	}

	private static String shippedRingList() {
		Set<String> ring = new TreeSet<>();
		for (String ns : SHIPPED.keySet()) {
			if (ns.startsWith("ring.")) {
				ring.add(ns);
			}
		}
		return "the built-in Ring namespaces are " + String.join(", ", ring);
	}

}
