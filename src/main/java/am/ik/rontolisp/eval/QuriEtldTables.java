package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * Rewrites three forms of quri's {@code src/etld.lisp} so its effective-TLD tables are
 * built ON FIRST READ from a bundled copy of the data file, rather than at READ time into
 * a value no backend can represent.
 *
 * <p>
 * Upstream writes the tables as {@code (defvar *etlds* '#.(load-etld-data))}: a read-time
 * evaluation whose value is a list of two HASH TABLES. The interpreter can hold that, but
 * a compile backend has to emit the datum as a literal and there is no literal syntax for
 * a hash table -- {@code Cannot quote: #<HASH-TABLE ...>}. So the eager read-time build
 * becomes a lazy load-time one: the global starts {@code nil} and the three reads inside
 * {@code parse-domain} force it.
 *
 * <p>
 * The parser itself ({@code load-etld-data}, {@code parse-domain},
 * {@code make-subdomain-iter}) stays VERBATIM upstream, so the tables hold exactly what a
 * real load would put in them. Only two things change beyond the laziness, both in the
 * {@code with-open-file} header:
 *
 * <ul>
 * <li>the path becomes the LITERAL namestring of the bundled file, so
 * {@code cli.CompileTimePathnameFolder} can inline its 152 KB into the artifact as a
 * {@code with-input-from-string} -- without that a compiled program would have to find
 * {@code effective_tld_names.dat} on disk at run time, which the WASM backends cannot
 * do;</li>
 * <li>{@code :element-type 'character} is dropped, because it is exactly the option that
 * suppresses that inlining (and it names the default anyway).</li>
 * </ul>
 *
 * Consequence, recorded deliberately: {@code (load-etld-data OTHER-FILE)} reads the
 * bundled list, not {@code OTHER-FILE}. Nothing in quri passes an argument -- the
 * parameter exists for a caller that wants a newer public-suffix list -- and honoring it
 * would mean giving up the inlining for every backend to serve a caller that does not
 * exist here.
 *
 * <p>
 * Each span is located by a marker that must occur EXACTLY ONCE. An upstream release that
 * moves one throws, naming the marker: a silent fallback to the real source would put
 * back the un-emittable hash-table literal, and the failure would surface as a quote
 * error deep inside a backend with nothing pointing at this class. See
 * {@code .kb/asdf.md} for the substitution ladder this sits on (the same tier as
 * {@link Uax15Tables}).
 */
public final class QuriEtldTables {

	/** The ASDF system name whose component sources this class rewrites. */
	public static final String SYSTEM = "quri";

	/** The one component file rewritten, relative to the system's base directory. */
	public static final String COMPONENT = "src/etld.lisp";

	/** The bundled data file, relative to the system's base directory. */
	public static final String DATA_FILE = "data/effective_tld_names.dat";

	/**
	 * The {@code with-open-file} header: the path is the {@code etld-names-file}
	 * parameter and the option list carries the two per-implementation reader
	 * conditionals.
	 */
	private static final String OPEN_MARKER = """
			(with-open-file (in etld-names-file
			                        :element-type #+lispworks :default #-lispworks 'character
			                        :external-format #+clisp charset:utf-8 #-clisp :utf-8)""";

	/**
	 * The read-time-eval table build. The {@code #+} branch is dead here (no abcl/ecl),
	 * so the effective form is the quoted {@code #.} on the last line.
	 */
	private static final String DEFVAR_MARKER = """
			(defvar *etlds*
			    #+(or abcl (and ecl win32 msvc)) (load-etld-data)
			    #-(or abcl (and ecl win32 msvc)) '#.(load-etld-data))""";

	/** The builder's name -- internal to {@code quri.etld}, like the tables it fills. */
	private static final String BUILDER = "%lite-build-etlds";

	/**
	 * The three reads of {@code *etlds*} in {@code parse-domain}, each with enough
	 * surrounding text to be unique, paired with the forcing replacement. The count is an
	 * inventory: a read this rewrite fails to reach would see a table that is still
	 * {@code nil} and answer as if the public-suffix list were empty -- a wrong result
	 * rather than a crash, which is why a mismatch throws.
	 */
	private static final String[][] FORCED_READS = {
			{ "(dolist (tld (third *etlds*))", "(dolist (tld (third (or *etlds* (" + BUILDER + "))))" },
			{ "if (gethash subdomain (second *etlds*)) do",
					"if (gethash subdomain (second (or *etlds* (" + BUILDER + ")))) do" },
			{ "else if (gethash subdomain (first *etlds*)) do",
					"else if (gethash subdomain (first (or *etlds* (" + BUILDER + ")))) do" } };

	private QuriEtldTables() {
	}

	/**
	 * Rewrites {@code src/etld.lisp}; returns {@code null} for any other component (the
	 * caller then uses the real source unchanged).
	 * @param componentFile the component path relative to the system's base directory
	 * @param source the component's real source text
	 * @param baseDir the system's base directory, or {@code null} when unknown
	 * @return the rewritten source, or {@code null} when this component is not rewritten
	 */
	@Nullable public static String rewrite(String componentFile, String source, @Nullable String baseDir) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		String dataPath = (baseDir == null || baseDir.isEmpty() ? "./"
				: baseDir.endsWith("/") ? baseDir : baseDir + "/") + DATA_FILE;
		String out = replaceOnce(source, OPEN_MARKER,
				"(with-open-file (in \"" + dataPath + "\"\n                        :external-format :utf-8)");
		out = replaceOnce(out, DEFVAR_MARKER,
				"(defvar *etlds* nil)\n" + "(defun " + BUILDER + " ()\n  (setq *etlds* (load-etld-data)))");
		for (String[] read : FORCED_READS) {
			out = replaceOnce(out, read[0], read[1]);
		}
		return out;
	}

	private static String replaceOnce(String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException("quri " + COMPONENT + ": expected exactly one occurrence of the marker <<<"
					+ marker + ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
					+ QuriEtldTables.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
