package am.ik.rontolisp.maven;

import java.nio.file.Path;

/**
 * One source file failed to compile, carrying the rontolisp diagnostic verbatim.
 * <p>
 * The diagnostic already begins {@code file:line:column:} whenever the frontend could
 * place the failing form, which is what lets an IDE jump to it; the file name is prefixed
 * only when it is missing, so nothing is said twice.
 */
public final class LispCompilationException extends RuntimeException {

	private final transient Path source;

	LispCompilationException(Path source, RuntimeException cause) {
		super(message(source, cause), cause);
		this.source = source;
	}

	/**
	 * @return the source file that failed
	 */
	public Path source() {
		return this.source;
	}

	private static String message(Path source, RuntimeException cause) {
		String diagnostic = cause.getMessage();
		if (diagnostic == null || diagnostic.isEmpty()) {
			diagnostic = cause.getClass().getName();
		}
		return diagnostic.contains(source.toString()) ? diagnostic : source + ": " + diagnostic;
	}

}
