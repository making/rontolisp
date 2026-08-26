package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code am.ik.rontolisp.runtime} class files that TRAVEL with a compiled
 * program off the compiler's own classpath.
 *
 * <p>
 * They are copied verbatim, at their canonical names -- NOT renamed into the program's
 * package the way the acceleration bridges are ({@code .kb/template-class-embedding.md}).
 * The rename is what makes a bridge private to one program, and privacy is exactly wrong
 * here: a boundary TYPE must be the same class in two rontolisp libraries for a caller to
 * chain them, and the served-request runtime is the same class the interpreter runs. One
 * canonical name, identical bytes, and an output that still has no dependency
 * ({@code .kb/jvm-export.md}).
 *
 * <p>
 * {@code package-info.class} is never in a list: it carries only the build's nullness
 * annotation, which is the compiler's business and not the artifact's. That is also why
 * no class in {@code runtime} may carry one itself -- the annotation is
 * {@code RuntimeVisible}, so the reference would follow the class out.
 */
final class JvmRuntimeClassFiles {

	private JvmRuntimeClassFiles() {
	}

	/**
	 * Reads the named class files off the compiler's own classpath.
	 * @param paths each class file's path within an output tree (or jar)
	 * @return those paths mapped to their bytes
	 */
	static Map<String, byte[]> read(List<String> paths) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		for (String path : paths) {
			try (InputStream in = JvmRuntimeClassFiles.class.getClassLoader().getResourceAsStream(path)) {
				if (in == null) {
					throw new IllegalStateException(path + " not found on the classpath");
				}
				files.put(path, in.readAllBytes());
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return Map.copyOf(files);
	}

}
