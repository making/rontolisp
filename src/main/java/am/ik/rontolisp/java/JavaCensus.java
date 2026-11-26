package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.java.JavaClass.Method;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * A measurement of how far a library is from compiling, taken instead of a program while
 * {@code -Drontolisp.java.census=FILE} is set: a refused statement of a library's body is
 * left out and the lowering goes on, so the file holds every refusal the program reaches
 * around the refused statements, not the first one, and then what was lowered.
 * {@code -Drontolisp.java.census.go=true} compiles what is left, for a lower bound of an
 * output's size; such a program is not the program. Not a user surface
 * ({@code .kb/java-frontend.md}, "A census of a library").
 */
final class JavaCensus {

	/**
	 * How a class of the library written for a measurement alone starts: the members of
	 * such a class that the lowered calls name are listed.
	 */
	static final String SHELL = "// STUB";

	private final Path file;

	private final boolean goesOn;

	private final Set<String> refusals = new LinkedHashSet<>();

	private final Set<Method> named = new LinkedHashSet<>();

	private JavaCensus(Path file, boolean goesOn) {
		this.file = file;
		this.goesOn = goesOn;
	}

	/** The census asked for by the system property, or {@code null}. */
	static @Nullable JavaCensus asked() {
		String file = System.getProperty("rontolisp.java.census");
		return file == null ? null : new JavaCensus(Path.of(file), Boolean.getBoolean("rontolisp.java.census.go"));
	}

	/** Notes a method a lowered call names. */
	void names(Method method) {
		this.named.add(method);
	}

	/**
	 * Notes what a body of a library refused.
	 * @param refused the refusal
	 * @param in the class and the member whose body it is
	 */
	void refuses(RuntimeException refused, String in) {
		String reason = refused instanceof LispReadException read ? where(read) + read.reason()
				: String.valueOf(refused.getMessage());
		int cut = reason.indexOf("\n  reached through ");
		this.refusals.add((cut < 0 ? reason : reason.substring(0, cut)) + "\n  in " + in);
	}

	private static String where(LispReadException refused) {
		SourceLocation at = refused.location();
		String file = at == null ? null : at.file();
		if (at == null || file == null) {
			return "";
		}
		return file.substring(file.lastIndexOf('!') + 1) + ":" + at.line() + ": ";
	}

	/**
	 * Writes the census once the lowering is settled; a census with refusals ends the
	 * compilation unless it was asked to go on.
	 * @param lowered the methods lowered
	 * @param initialized the classes whose static initialization was lowered
	 */
	void written(Source program, Set<Method> lowered, Set<JavaClass> initialized) {
		StringBuilder text = new StringBuilder();
		text.append("# ").append(this.refusals.size()).append(" refusals\n");
		this.refusals.forEach(refusal -> text.append(refusal).append('\n'));
		text.append("# the members of the shells that the calls name\n");
		for (Method method : this.named) {
			if (isShell(method.owner)) {
				text.append("NAMED\t").append(method.owner.name).append('.').append(method.signature()).append('\n');
			}
		}
		text.append("# ").append(lowered.size()).append(" methods lowered, by origin and file\n");
		for (Method method : lowered) {
			text.append(origin(method.owner))
				.append('\t')
				.append(method.owner.name)
				.append('.')
				.append(method.signature())
				.append('\n');
		}
		for (JavaClass declaration : initialized) {
			text.append(origin(declaration)).append('\t').append(declaration.name).append(".<clinit>\n");
		}
		try {
			Files.writeString(this.file, text);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (!this.refusals.isEmpty() && !this.goesOn) {
			throw JavaClasses.error(program, 0, this.refusals.size() + " refusals reached, written to " + this.file);
		}
	}

	private static boolean isShell(JavaClass declaration) {
		Source source = declaration.source;
		return source != null && source.text().startsWith(SHELL);
	}

	private static String origin(JavaClass declaration) {
		Source source = declaration.source;
		if (source == null) {
			return "-";
		}
		return (isShell(declaration) ? "SHELL" : source.origin().toString()) + " " + source.file();
	}

}
