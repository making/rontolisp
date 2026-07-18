package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;

/**
 * The lite dependency-shim libraries behind the built-in ASDF systems that real
 * third-party libraries name in {@code :depends-on} clauses: {@code closer-mop} (no slot
 * metaobjects -- class introspection yields no slots), {@code flexi-streams} (a flexi
 * stream is the underlying stream), {@code float-features} (wrappers over the IEEE 754
 * bit primitives) and {@code trivial-gray-streams} (the Gray base classes + generics the
 * {@code write-string} built-in dispatches to for CLOS-instance streams). Each is a small
 * canonical-shape {@code .lisp} resource next to this class (the {@code usocket.lisp}
 * pattern); the packages are seeded in {@code PackageRegistry}.
 */
public final class ShimLibraries {

	/** Built-in system name to its classpath resource. */
	private static final Map<String, String> RESOURCES = Map.of(LispNames.CLOSER_MOP_PKG, "closer-mop.lisp",
			LispNames.FLEXI_STREAMS_PKG, "flexi-streams.lisp", "float-features", "float-features.lisp",
			LispNames.TRIVIAL_GRAY_STREAMS_PKG, "trivial-gray-streams.lisp");

	private static final Map<String, List<LispVal>> CACHE = new ConcurrentHashMap<>();

	private ShimLibraries() {
	}

	/**
	 * Returns whether the named system is one of the dependency shims.
	 * @param name the system name (canonical lower-case)
	 * @return {@code true} when a shim satisfies it
	 */
	public static boolean isShim(String name) {
		return RESOURCES.containsKey(name);
	}

	/**
	 * Returns the parsed library definitions of the named shim system. The source is in
	 * canonical shape (qualified public names), so it needs no package resolution. Parsed
	 * once and cached.
	 * @param name a name for which {@link #isShim} is true
	 * @return the library forms
	 */
	public static List<LispVal> forms(String name) {
		String resource = RESOURCES.get(name);
		if (resource == null) {
			throw new IllegalArgumentException("Not a shim system: " + name);
		}
		return CACHE.computeIfAbsent(name, key -> {
			List<LispVal> parsed = LispReader.readAllFromString(readSource(resource));
			if (LispNames.TRIVIAL_GRAY_STREAMS_PKG.equals(key)) {
				// The adapter's superclasses and delegation targets are rontolisp's
				// own Gray protocol: its definitions must precede the adapter's.
				List<LispVal> combined = new java.util.ArrayList<>(GrayStreamsLibrary.forms());
				combined.addAll(parsed);
				return List.copyOf(combined);
			}
			return parsed;
		});
	}

	private static String readSource(String resource) {
		try (InputStream in = ShimLibraries.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
