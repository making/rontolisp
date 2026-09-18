package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.scheme.Scheme;

/**
 * The ONE seam between user source text and the core forms every pipeline consumes. A
 * language is something that PRODUCES {@code List<LispVal>} (the de-facto IR the
 * {@code reader}/{@code macro}/{@code compiler}/{@code codegen}/{@code eval} packages
 * share) and then joins the existing pipeline -- {@code CompileFrontend.expand} on the
 * compile path, {@code LispEvaluator} on the interpreter -- which is why this type lives
 * at (not above) {@code eval}: it must be reachable from {@code cli}, the web source set
 * AND the interpreter's {@code load}, and the package graph allows exactly that
 * direction.
 *
 * <p>
 * Every site that turns USER source text into forms reads through here, so the rules
 * about how source is read are stated once instead of hand-copied per site (the
 * restated-pipeline shape {@code CompileFrontend.expand} already forbids one step
 * downstream). Library source shipped inside the jar is NOT user source: it is Common
 * Lisp whatever the user's language is, so the {@code *Library} splices keep reading it
 * through {@code LispReader} directly (see {@code .kb/source-language.md} and
 * {@code SourceLanguageSeamTest} for the exempt-caller rule); the runtime
 * {@code read}/{@code read-from-string} data reads in {@code Environment} stay there too.
 *
 * <p>
 * The language is picked per FILE, from its extension, so one program may mix languages
 * file by file: {@code load} (both the interpreter's and the compile path's inliner)
 * picks by the LOADED file's extension, while the entry source is picked by the entry
 * file's extension unless the {@code --source-language} CLI override names one. The REPL
 * has no file and reads the override's language, one buffer at a time, through
 * {@link SourceSession}; the browser playground has no language pick and reads the
 * default.
 */
public enum SourceLanguage {

	/**
	 * Common Lisp: the reader from the {@code reader} package, with the {@code #.}
	 * read-time-eval decision ({@code readAllWithReadEvalMarkers} when the text contains
	 * one, the plain read otherwise).
	 */
	COMMON_LISP(".lisp"),

	/**
	 * Scheme, EXPERIMENTAL: a subset of R7RS-small {@code (scheme base)} read
	 * case-sensitively and lowered to the same core forms by the {@code scheme} package
	 * ({@code .kb/scheme-frontend.md}). Partial conformance by design, no compatibility
	 * promise. It has no {@code #.} and no reader features.
	 */
	SCHEME(".scm");

	private final String extension;

	SourceLanguage(String extension) {
		this.extension = extension;
	}

	/**
	 * Reads user source text into core forms.
	 * @param source the program text
	 * @param features the active reader features (the target backend's on the compile
	 * path, the evaluator's on the interpreter)
	 * @param file the origin file for diagnostics, or {@code null} when unknown
	 * ({@code -e}, the REPL, the playground)
	 * @return the parsed top-level forms
	 */
	public List<LispVal> read(String source, Features features, @Nullable String file) {
		return read(source, features, file, SourceStandards.DEFAULT);
	}

	/**
	 * Reads user source text into core forms against the program's standards.
	 * @param source the program text
	 * @param features the active reader features (the target backend's on the compile
	 * path, the evaluator's on the interpreter)
	 * @param file the origin file for diagnostics, or {@code null} when unknown
	 * ({@code -e}, the REPL, the playground)
	 * @param standards what each language is read against ({@code --scheme-standard})
	 * @return the parsed top-level forms
	 */
	public List<LispVal> read(String source, Features features, @Nullable String file, SourceStandards standards) {
		if (this == SCHEME) {
			return Scheme.read(source, file, standards.scheme());
		}
		return usesReadEvalMarkers(source) ? LispReader.readAllWithReadEvalMarkers(source, features, file)
				: LispReader.readAllFromString(source, features, file);
	}

	/**
	 * Whether the source needs the marker read and the per-form marker resolution: only a
	 * text textually containing {@code #.} pays for either, every other source keeps the
	 * plain read. One predicate, asked by every site that resolves markers after the
	 * read, so the rule is stated here and not hand-copied per site.
	 * @param source the program text
	 * @return {@code true} when the source contains a {@code #.} read-time-eval datum
	 */
	public static boolean usesReadEvalMarkers(String source) {
		return source.contains("#.");
	}

	/**
	 * Reads user source text that has no marker-resolution pass downstream, in the
	 * reader's error mode -- a {@code #.} is a read error rather than a marker. Only the
	 * browser playground's compile buttons use this: their reduced frontend has no
	 * {@code UserMacroExpander} to resolve markers against, so the marker read would
	 * trade a positioned read error for an unhelpful failure downstream.
	 * @param source the program text
	 * @param features the active reader features
	 * @return the parsed top-level forms
	 */
	public List<LispVal> readStrict(String source, Features features) {
		if (this == SCHEME) {
			return Scheme.read(source, null);
		}
		return LispReader.readAllFromString(source, features);
	}

	/**
	 * Picks the language for a source file: the CLI override when one names a language,
	 * otherwise the file's extension. Unknown extensions (and a {@code null} path:
	 * {@code -e}, the REPL, generated programs) read as Common Lisp until a language
	 * claims them -- that is the change a second language makes here, in this method
	 * alone.
	 * @param path the file the source was read from, or {@code null}
	 * @param override the {@code --source-language} value, or {@code null}
	 * @return the language to read the file's source with
	 */
	public static SourceLanguage forFile(@Nullable String path, @Nullable String override) {
		if (override != null) {
			return parse(override);
		}
		if (path != null && path.endsWith(SCHEME.defaultExtension())) {
			return SCHEME;
		}
		return COMMON_LISP;
	}

	/**
	 * The file extension this language's sources carry, including the dot. One spelling
	 * for the sites that derive a file name from a name that is already a path fragment
	 * (a package-inferred sub-system); the bare-module mapping is
	 * {@link #fileNameForModule}.
	 * @return the extension, e.g. {@code ".lisp"}
	 */
	public String defaultExtension() {
		return this.extension;
	}

	/**
	 * Whether the path looks like a Lisp source file target, as opposed to an ASDF system
	 * designator. This is the {@code rontolisp test} question ("a missing
	 * {@code foo.lisp} is an error, a missing {@code foo} is a system to look up"), and
	 * it stays extension-spelled: a second language widens it when it arrives.
	 * @param path the command-line target
	 * @return {@code true} when the target names a source file
	 */
	public static boolean isSourceFile(String path) {
		return path.endsWith(COMMON_LISP.defaultExtension()) || path.endsWith(SCHEME.defaultExtension());
	}

	/**
	 * The file a bare module name maps to: {@code (require :util)} loads
	 * {@code util.lisp}, like ASDF's downcasing coerce-name rule. One spelling for the
	 * interpreter's {@code require}, the compile path's inliner and the package-inferred
	 * system derivation.
	 * @param moduleName the module name, in whatever case the caller spelled it
	 * @return the source file name
	 */
	public static String fileNameForModule(String moduleName) {
		return moduleName.toLowerCase(Locale.ROOT) + COMMON_LISP.defaultExtension();
	}

	/**
	 * Parses a {@code --source-language} value.
	 * @param override the flag value
	 * @return the named language
	 */
	public static SourceLanguage parse(String override) {
		String name = override.trim().toLowerCase(Locale.ROOT);
		if (name.equals("common-lisp") || name.equals("cl") || name.equals("lisp")) {
			return COMMON_LISP;
		}
		if (name.equals("scheme") || name.equals("scm")) {
			return SCHEME;
		}
		throw new IllegalArgumentException("--source-language '" + override
				+ "' names no language this build reads (try common-lisp, or the experimental scheme)");
	}

}
