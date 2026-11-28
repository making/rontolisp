package am.ik.rontolisp.java;

import java.util.List;
import java.util.function.Consumer;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL front end for a subset of the Java language: a file is read by the
 * grammar of the language specification, its expressions are given their static types,
 * and the program is LOWERED to the Common Lisp core forms every pipeline already
 * consumes. A subset by design and no compatibility promise; the lowering table, the
 * value model and what is refused are in {@code .kb/java-frontend.md} and on the
 * user-facing {@code doc/en/java/} section.
 *
 * <p>
 * Nothing here is copied or translated from a JDK: the lexer, the parser, the library
 * classes and the run-time helpers are written from the published specification
 * ({@code .kb/java-frontend-provenance.md}).
 *
 * <p>
 * This package depends on the AST types and {@code reader} only. It is reached through
 * the source-language seam ({@code eval/SourceLanguage.JAVA}), never directly; its
 * run-time helpers are Common Lisp source shipped like every other library
 * ({@code eval/JavaLibrary}), and the library classes that are plain Java are source in
 * the jar beside this package, lowered with the program that names them.
 */
public final class Java {

	private Java() {
	}

	/**
	 * Reads the Java program a file starts and lowers it to Common Lisp core forms: the
	 * file's classes and every class they name from the other files of its source tree,
	 * lowered together.
	 * @param source the file's text
	 * @param file the origin file for diagnostics, for the class the program starts at
	 * and for where its source tree is, or {@code null} when unknown
	 * @param files where the other files of the source tree are read
	 * @return the top-level forms
	 * @throws LispReadException on a lexical or syntax error, a construct the subset does
	 * not have yet, or an error a compiler reports that the lowering depends on, each
	 * with its position
	 */
	public static List<LispVal> read(String source, @Nullable String file, JavaFiles files) {
		return read(source, file, files, null);
	}

	/**
	 * {@link #read(String, String, JavaFiles)}, telling the warnings a compiler prints of
	 * the program (its unchecked ones) as a compiler prints them.
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @param warnings what is told the text of the warnings, every line ended, when there
	 * are some; {@code null} to drop them
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read(String, String, JavaFiles)}
	 */
	public static List<LispVal> read(String source, @Nullable String file, JavaFiles files,
			@Nullable Consumer<String> warnings) {
		return read(source, file, files, false, warnings, false, JavaBoundary.NONE, null, List.of());
	}

	/**
	 * {@link #read(String, String, JavaFiles, Consumer)} for a target told: where it has
	 * one stack, a program that starts threads or serves is lowered with every function a
	 * thread can wait in resumable, so a thread is a body suspended and resumed
	 * ({@code .kb/java-frontend.md}, "Threads").
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @param warnings what is told the text of the warnings, or {@code null}
	 * @param suspended whether the target has one stack
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read(String, String, JavaFiles)}
	 */
	public static List<LispVal> read(String source, @Nullable String file, JavaFiles files,
			@Nullable Consumer<String> warnings, boolean suspended) {
		return read(source, file, files, false, warnings, suspended, JavaBoundary.NONE, null, List.of());
	}

	/**
	 * {@link #read(String, String, JavaFiles, Consumer, boolean)} against a host
	 * boundary: the WIT a {@code @WitImport} interface names is read through it, and it
	 * tells whether the target's host provides such an interface.
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @param warnings what is told the text of the warnings, or {@code null}
	 * @param suspended whether the target has one stack
	 * @param boundary the host boundary
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read(String, String, JavaFiles)}
	 */
	public static List<LispVal> read(String source, @Nullable String file, JavaFiles files,
			@Nullable Consumer<String> warnings, boolean suspended, JavaBoundary boundary) {
		return read(source, file, files, false, warnings, suspended, boundary, null, List.of());
	}

	/**
	 * {@link #read(String, String, JavaFiles, Consumer, boolean, JavaBoundary)}, or the
	 * run of the file's tests: the program then runs each test the file's classes declare
	 * with the annotations of {@code org.junit.jupiter}, reports them and ends with the
	 * status 1 where one failed or none ran ({@code .kb/java-frontend.md}, "Tests of a
	 * program").
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @param warnings what is told the text of the warnings, or {@code null}
	 * @param suspended whether the target has one stack
	 * @param boundary the host boundary
	 * @param tests what the run is said to be of where it finds no test, or {@code null}
	 * for the program the file is
	 * @param testFiles the further files of the run of tests, of the file's source tree,
	 * whose classes' tests run after its own, in order
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read(String, String, JavaFiles)}
	 */
	public static List<LispVal> read(String source, @Nullable String file, JavaFiles files,
			@Nullable Consumer<String> warnings, boolean suspended, JavaBoundary boundary, @Nullable String tests,
			List<String> testFiles) {
		return read(source, file, files, false, warnings, suspended, boundary, tests, testFiles);
	}

	/**
	 * Reads the Java program a file Common Lisp loads starts: as {@link #read}, but a
	 * file with no {@code main} is one whose classes the loading program calls, defined
	 * and nothing run.
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read}
	 */
	public static List<LispVal> load(String source, @Nullable String file, JavaFiles files) {
		return load(source, file, files, null);
	}

	/**
	 * {@link #load(String, String, JavaFiles)}, telling the warnings as
	 * {@link #read(String, String, JavaFiles, Consumer)} does.
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @param warnings what is told the text of the warnings, or {@code null}
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read(String, String, JavaFiles)}
	 */
	public static List<LispVal> load(String source, @Nullable String file, JavaFiles files,
			@Nullable Consumer<String> warnings) {
		return read(source, file, files, true, warnings, false, JavaBoundary.NONE, null, List.of());
	}

	/**
	 * {@link #load(String, String, JavaFiles, Consumer)} for a target told, as
	 * {@link #read(String, String, JavaFiles, Consumer, boolean)}.
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @param warnings what is told the text of the warnings, or {@code null}
	 * @param suspended whether the target has one stack
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read(String, String, JavaFiles)}
	 */
	public static List<LispVal> load(String source, @Nullable String file, JavaFiles files,
			@Nullable Consumer<String> warnings, boolean suspended) {
		return read(source, file, files, true, warnings, suspended, JavaBoundary.NONE, null, List.of());
	}

	/**
	 * {@link #load(String, String, JavaFiles, Consumer, boolean)} against a host
	 * boundary, as
	 * {@link #read(String, String, JavaFiles, Consumer, boolean, JavaBoundary)}.
	 * @param source the file's text
	 * @param file the origin file
	 * @param files where the other files of the source tree are read
	 * @param warnings what is told the text of the warnings, or {@code null}
	 * @param suspended whether the target has one stack
	 * @param boundary the host boundary
	 * @return the top-level forms
	 * @throws LispReadException as {@link #read(String, String, JavaFiles)}
	 */
	public static List<LispVal> load(String source, @Nullable String file, JavaFiles files,
			@Nullable Consumer<String> warnings, boolean suspended, JavaBoundary boundary) {
		return read(source, file, files, true, warnings, suspended, boundary, null, List.of());
	}

	/**
	 * The skeleton of a Java program implementing a WIT world
	 * ({@code rontolisp --scaffold-wit world.wit -o Greeter.java}): a {@code @WitExport}
	 * class whose static methods are the world's exports, a {@code @WitImport} member
	 * interface for each interface it imports, and the types they reach, written from the
	 * mapping the checks apply.
	 * @param boundary where the WIT is read, as a program's {@code @WitExport} reads it
	 * @param wit the WIT as the annotations name it: relative to the generated file
	 * @param paths where the WIT is read from
	 * @param world the world's name
	 * @param className the class's name
	 * @param docs the WIT's {@code ///} lines, by {@code <scope>#<name>}: the scope an
	 * interface's canonical id, an inline one's name or empty for the world's own; the
	 * name a function's, a type's, {@code <resource>.<function>} of a resource's
	 * function, empty for the scope itself
	 * @return the source and what was left out
	 * @throws LispReadException when the WIT cannot be read or holds no such world
	 */
	public static JavaWitSkeleton scaffoldWit(JavaBoundary boundary, String wit, List<String> paths, String world,
			String className, java.util.Map<String, List<String>> docs) {
		return JavaWitScaffold.scaffold(boundary, wit, paths, world, className, docs);
	}

	private static List<LispVal> read(String source, @Nullable String file, JavaFiles files, boolean loaded,
			@Nullable Consumer<String> warnings, boolean suspended, JavaBoundary boundary, @Nullable String tests,
			List<String> testFiles) {
		String name = "Main";
		if (file != null) {
			String path = file.replace('\\', '/');
			String base = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.[^.]*$", "");
			if (!base.isEmpty() && Character.isJavaIdentifierStart(base.charAt(0))
					&& base.chars().allMatch(Character::isJavaIdentifierPart)) {
				name = base;
			}
		}
		return JavaLowering.lower(JavaParser.parse(source, file, name), source, file, files, null, loaded, warnings,
				suspended, null, boundary, tests, testFiles);
	}

}
