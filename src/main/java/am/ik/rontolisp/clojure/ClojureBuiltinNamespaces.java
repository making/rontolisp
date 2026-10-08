package am.ik.rontolisp.clojure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * The namespaces the Clojure front end ships as Clojure source: the pure-function subset
 * of ring-core and ring-codec, the counterpart of the built-in Clack shims. Each is a
 * classpath resource below {@code lib/} next to this class, read through the namespace
 * loader like a project file -- after the project's own source roots, so a project file
 * of the name wins, as a source directory precedes a dependency jar on the oracle's
 * classpath. What Clojure would spell through generic verbs at ten times the size lives
 * in the {@link ClojureRingUtilLowering} kernels, which only these files can require.
 *
 * <p>
 * A var of the oracle's namespace outside the subset, and a ring-core namespace not
 * shipped, are refused by name rather than with the missing-var or missing-file words.
 */
final class ClojureBuiltinNamespaces {

	/**
	 * The shipped namespaces, each with the public vars of the oracle's namespace
	 * (ring-core 1.15.5, ring-codec 1.3.0) it leaves out and why.
	 */
	private static final Map<String, Map<String, String>> SHIPPED = Map.of("ring.util.response", Map.of("file-response",
			"it serves a java.io.File", "url-response", "it reads a java.net.URL", "resource-response",
			"it reads a class-loader resource", "resource-data", "it reads a java.net.URL"), "ring.util.request",
			Map.of(), "ring.util.codec",
			Map.of("base64-encode", "it takes a byte array", "base64-decode", "it answers a byte array", "form-encode*",
					"form-encode is a function, not a protocol, here", "FormEncodeable",
					"form-encode is a function, not a protocol, here"),
			"ring.util.mime-type", Map.of(), "ring.middleware.params", Map.of(), "ring.middleware.keyword-params",
			Map.of(), "ring.middleware.content-type", Map.of());

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
	 * The source of a shipped namespace.
	 * @param ns the namespace
	 * @return its text, or {@code null} for a namespace not shipped
	 */
	static @Nullable String source(String ns) {
		if (!isShipped(ns)) {
			return null;
		}
		String resource = "lib/" + ClojureSourcePath.resourceOf(ns);
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
	 * namespaces this front end does not ship.
	 * @param ns the namespace
	 * @return the refusal, or {@code null} for any other namespace
	 */
	static @Nullable String notShipped(String ns) {
		if (ns.equals("ring.adapter.jetty")) {
			return "ring.adapter.jetty is not built in: serve a Ring handler with ring.adapter.rontolisp/run-server";
		}
		return NOT_SHIPPED.contains(ns) ? ns + " is not built in: " + shippedList() : null;
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

	private static String shippedList() {
		return "the built-in Ring namespaces are " + String.join(", ", new TreeSet<>(SHIPPED.keySet()));
	}

}
