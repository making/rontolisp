package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Hand-authored replacements for {@code .asd} files that cannot be parsed as plain data.
 * A real library's {@code .asd} is sometimes an executable program (ironclad's defines
 * component classes and generates its defsystems with a macro); {@link AsdfSystems}
 * deliberately never evaluates system definitions, so such a file cannot be read at all.
 * For those libraries this registry substitutes a bundled replacement source -- written
 * in the supported defsystem subset, declaring the subsystems rontolisp can actually load
 * -- at {@link AsdfSystems#locate} time. The located path (and therefore the base
 * directory the component files resolve against) is kept, so the REAL library sources are
 * loaded; only the system metadata is redeclared. Both system loaders (the interpreter's
 * {@code loadSystem} and the compile-time {@code LoadInliner}) go through {@code locate},
 * so one entry serves every backend.
 */
public final class AsdOverrides {

	/**
	 * The {@code .asd} file name (as computed from the primary system name by
	 * {@code locate}) to the classpath resource holding the replacement source.
	 */
	private static final Map<String, String> RESOURCES = Map.of("ironclad.asd", "ironclad-slice.asd");

	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private AsdOverrides() {
	}

	/**
	 * Returns the replacement {@code .asd} source for the given file name, or
	 * {@code null} when the real file should be parsed.
	 * @param asdFileName the located file's name (e.g. {@code "ironclad.asd"})
	 * @return the replacement source, or {@code null}
	 */
	@Nullable public static String replacementSource(String asdFileName) {
		String resource = RESOURCES.get(asdFileName);
		if (resource == null) {
			return null;
		}
		return CACHE.computeIfAbsent(resource, AsdOverrides::readSource);
	}

	private static String readSource(String resource) {
		try (InputStream in = AsdOverrides.class.getResourceAsStream(resource)) {
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
