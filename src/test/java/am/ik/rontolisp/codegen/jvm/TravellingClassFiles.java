package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the class files that travel beside a compiled class -- a shipped bridge and the
 * library it renames, the runtime classes -- under a class path root, as
 * {@code -o X.class} lays them out, so a class loader rooted there finds them.
 */
final class TravellingClassFiles {

	private TravellingClassFiles() {
	}

	/**
	 * Writes {@link JvmLispCompiler#runtimeClassFiles()} under {@code root}.
	 * @param compiler a compiler that has compiled the class
	 * @param root the class path root the class itself is written under
	 */
	static void write(JvmLispCompiler compiler, Path root) {
		try {
			for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
				Path target = root.resolve(file.getKey());
				Files.createDirectories(Objects.requireNonNull(target.getParent()));
				Files.write(target, file.getValue());
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
