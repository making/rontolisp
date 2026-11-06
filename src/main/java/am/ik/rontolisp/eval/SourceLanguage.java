package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.clojure.Clojure;
import am.ik.rontolisp.clojure.ClojureFiles;
import am.ik.rontolisp.clojure.ClojureJavaClasses;
import am.ik.rontolisp.clojure.ClojureRepositories;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.scheme.Scheme;
import am.ik.rontolisp.scheme.SchemeFiles;

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
 * {@link SourceSession}; the browser playground reads the language its page picks, the
 * same way ({@link PlaygroundRepl}).
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
	SCHEME(".scm"),

	/**
	 * Clojure, EXPERIMENTAL SPIKE: a small subset read case-sensitively and lowered to
	 * the same core forms by the {@code clojure} package. A feasibility probe; no subset
	 * or compatibility promise yet. A {@code .cljc} file is Clojure too, read with its
	 * reader conditionals ({@link #CLOJURE_COMMON_EXTENSION}).
	 */
	CLOJURE(".clj");

	/**
	 * The extension of a Clojure file shared between platforms, whose reader conditionals
	 * read (a {@code .clj} file refuses one, like the oracle).
	 */
	public static final String CLOJURE_COMMON_EXTENSION = ".cljc";

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
		return read(source, features, file, standards, null);
	}

	/**
	 * Reads user source text into core forms against the program's standards, with the
	 * files the source names read through a loader: a Scheme {@code include} and the
	 * {@code define-library} files a Scheme program imports, resolved against the
	 * directory of {@code file} like {@code load} (a Common Lisp read names none).
	 * @param source the program text
	 * @param features the active reader features (the target backend's on the compile
	 * path, the evaluator's on the interpreter)
	 * @param file the origin file for diagnostics and for what the source's file names
	 * are relative to, or {@code null} when unknown ({@code -e}, the REPL, the
	 * playground)
	 * @param standards what each language is read against ({@code --scheme-standard})
	 * @param loader where named files are read from, or {@code null} when none can be
	 * @return the parsed top-level forms
	 */
	public List<LispVal> read(String source, Features features, @Nullable String file, SourceStandards standards,
			@Nullable SourceLoader loader) {
		LispReader.ReadPrefix read = readUntilError(source, features, file, standards, loader);
		LispReadException error = read.error();
		if (error != null) {
			throw error;
		}
		return read.forms();
	}

	/**
	 * Reads user source text into core forms up to its first read error rather than
	 * throwing it -- what a form-at-a-time {@code load} evaluates before it signals. A
	 * Common Lisp source answers every form before the bad one; a Scheme or Clojure
	 * source is lowered as a whole, so a read error there answers no form.
	 * @param source the program text
	 * @param features the active reader features
	 * @param file the origin file for diagnostics and for what the source's file names
	 * are relative to, or {@code null} when unknown
	 * @param standards what each language is read against ({@code --scheme-standard})
	 * @param loader where named files are read from, or {@code null} when none can be
	 * @return the forms before the first read error, and that error ({@code null} when
	 * the whole source read)
	 */
	public LispReader.ReadPrefix readUntilError(String source, Features features, @Nullable String file,
			SourceStandards standards, @Nullable SourceLoader loader) {
		// What the seam reads is the PROGRAM's source; a library a splice reads goes to
		// the reader directly. The compile path tells the two apart by this mark
		// (CompileWarnings counts only the program's warnings).
		return SourceProvenance
			.readingProgramSource(() -> shieldCircles(readProgram(source, features, file, standards, loader), source));
	}

	private LispReader.ReadPrefix readProgram(String source, Features features, @Nullable String file,
			SourceStandards standards, @Nullable SourceLoader loader) {
		if (this == COMMON_LISP) {
			return usesReadEvalMarkers(source) ? LispReader.readPrefixWithReadEvalMarkers(source, features, file)
					: LispReader.readPrefixFromString(source, features, file);
		}
		try {
			if (this == SCHEME) {
				return new LispReader.ReadPrefix(Scheme.read(source, file, standards.scheme(), schemeFiles(loader)),
						null);
			}
			return new LispReader.ReadPrefix(clojure(source, features, file, standards, loader, false, null).forms(),
					null);
		}
		catch (LispReadException ex) {
			return new LispReader.ReadPrefix(List.of(), ex);
		}
	}

	/**
	 * Reads a whole Clojure program for a run that lowers as the program runs where its
	 * target does -- the interpreter's and the JVM's: a program whose run-time code
	 * reaches a lowering verb over computed arguments ({@code eval}, a computed
	 * {@code resolve} or {@code require} ...) lowers open and keeps its lowering
	 * ({@link Clojure.Program#runTime}), which the interpreter hands those verbs to
	 * ({@link LispEvaluator#clojureProgram(Clojure.Program)}) and a compiled JVM class
	 * lowers again when it first runs one ({@code cli/JvmRunTimeLowering}).
	 * @param source the program text
	 * @param features the active reader features: the interpreter's and the JVM's lower
	 * at run time
	 * @param file the origin file for diagnostics and for the source path, or
	 * {@code null} when unknown
	 * @param standards what the program is read against
	 * @param loader where named files are read from, or {@code null} when none can be
	 * @return the lowered program
	 */
	public Clojure.Program readClojure(String source, Features features, @Nullable String file,
			SourceStandards standards, @Nullable SourceLoader loader) {
		return readClojure(source, features, file, standards, loader, null);
	}

	/**
	 * {@link #readClojure(String, Features, String, SourceStandards, SourceLoader)} from
	 * where a compile's open passes began ({@link Clojure.Program#decisions}): a compiled
	 * JVM class's read of its own program as it runs.
	 * @param source the program text
	 * @param features the active reader features
	 * @param file the origin file, or {@code null} when unknown
	 * @param standards what the program is read against
	 * @param loader where named files are read from, or {@code null} when none can be
	 * @param decisions the decisions, or {@code null} to lower from nothing
	 * @return the lowered program
	 */
	public Clojure.Program readClojure(String source, Features features, @Nullable String file,
			SourceStandards standards, @Nullable SourceLoader loader, @Nullable String decisions) {
		if (this != CLOJURE) {
			throw new IllegalStateException(this + " is no Clojure source");
		}
		return SourceProvenance.readingProgramSource(() -> clojure(source, features, file, standards, loader,
				features.contains("rontolisp-interpreter") || features.contains("rontolisp-jvm"), decisions));
	}

	private static Clojure.Program clojure(String source, Features features, @Nullable String file,
			SourceStandards standards, @Nullable SourceLoader loader, boolean runTimeTarget,
			@Nullable String decisions) {
		// The host is wherever the target is no wasm one: a java: member can throw and
		// take an exception there. A JVM class links its library when it is compiled.
		return Clojure.program(source, file,
				() -> loader == null ? ClojureMacroTime.create() : ClojureMacroTime.create(loader.javaClassLoader()),
				clojureFiles(loader, standards), !features.contains("rontolisp-wasm"), ClojureHostBoundary.INSTANCE,
				runTimeTarget, runTimeTarget && features.contains("rontolisp-jvm"), decisions);
	}

	/**
	 * Hides every circle a {@code #n=} label closed in the source from the passes that
	 * follow ({@link LispTrees#shieldProgramForm}). Only a source that defines a label
	 * pays for the walk.
	 */
	private static LispReader.ReadPrefix shieldCircles(LispReader.ReadPrefix read, String source) {
		if (!LABEL_DEFINITION.matcher(source).find()) {
			return read;
		}
		return new LispReader.ReadPrefix(read.forms().stream().map(LispTrees::shieldProgramForm).toList(),
				read.error());
	}

	private static final Pattern LABEL_DEFINITION = Pattern.compile("#[0-9]+=");

	/**
	 * The files a Scheme program names, through a loader: a path relative to the naming
	 * file's directory, as {@code load} resolves one ({@link SourceLoader#resolve}).
	 * @param loader the loader, or {@code null} for none
	 * @return the files
	 */
	static SchemeFiles schemeFiles(@Nullable SourceLoader loader) {
		if (loader == null) {
			return SchemeFiles.NONE;
		}
		return (from, path) -> {
			String resolved = SourceLoader.resolve(from == null ? null : SourceLoader.parentDir(from), path);
			if (!loader.exists(resolved)) {
				return null;
			}
			try {
				return new SchemeFiles.Source(resolved, loader.load(resolved));
			}
			catch (IOException ex) {
				return null;
			}
		};
	}

	/**
	 * The files a Clojure program names, through a loader: the project namespaces a
	 * {@code require} loads, the {@code deps.edn} files naming their roots and
	 * dependencies, a dependency's directory or jar (read in place), the user-level
	 * {@code deps.edn}, the aliases and the repositories the standards name, and the
	 * loader's Java class path, which the jars of the program's dependencies join. Where
	 * the roots are is the front end's decision ({@code clojure/ClojureSourcePath}); this
	 * only reads and joins paths. A parent directory is absolute, so the search for a
	 * {@code deps.edn} walks past the top of a relative entry path; a host with no
	 * working directory (the browser) keeps the lexical parent.
	 * @param loader the loader, or {@code null} for none
	 * @param standards where the user-level {@code deps.edn} is, and the selected aliases
	 * @return the files
	 */
	static ClojureFiles clojureFiles(@Nullable SourceLoader loader, SourceStandards standards) {
		if (loader == null) {
			return ClojureFiles.NONE;
		}
		String userConfigDir = standards.clojureConfigDir();
		List<String> aliases = standards.clojureAliases();
		ClojureRepositories repositories = standards.clojureRepositories();
		return new ClojureFiles() {
			@Override
			public @Nullable String read(String path) {
				if (!loader.exists(path)) {
					return null;
				}
				try {
					return loader.load(path);
				}
				catch (IOException ex) {
					return null;
				}
			}

			@Override
			public byte @Nullable [] readBytes(String path) {
				if (!loader.exists(path)) {
					return null;
				}
				try {
					return loader.loadBytes(path);
				}
				catch (IOException ex) {
					return null;
				}
			}

			@Override
			public @Nullable String parent(String path) {
				try {
					java.nio.file.Path parent = java.nio.file.Path.of(path).toAbsolutePath().normalize().getParent();
					return parent == null ? null : parent.toString();
				}
				catch (RuntimeException ex) {
					return SourceLoader.parentDir(path);
				}
			}

			@Override
			public String resolve(@Nullable String dir, String relative) {
				return SourceLoader.resolve(dir, relative);
			}

			@Override
			public ClassLoader javaClassLoader() {
				return loader.javaClassLoader();
			}

			@Override
			public ClojureJavaClasses javaClasses() {
				JavaClassLookup classFiles = loader.javaClassLookup();
				return classFiles == null ? ClojureFiles.super.javaClasses() : new ClojureClassLookup(classFiles);
			}

			@Override
			public boolean exists(String path) {
				return loader.exists(path);
			}

			@Override
			public boolean isDirectory(String path) {
				return loader.listDirectory(path) != null;
			}

			@Override
			public String canonical(String path) {
				return loader.canonicalPath(path);
			}

			@Override
			public String absolute(String path) {
				try {
					return java.nio.file.Path.of(path.isEmpty() ? "." : path).toAbsolutePath().normalize().toString();
				}
				catch (RuntimeException ex) {
					// a host with no working directory (the browser) keeps the spelling
					return path;
				}
			}

			@Override
			public @Nullable List<String> archiveEntries(String path) {
				return loader.listArchive(path);
			}

			@Override
			public @Nullable String readArchiveEntry(String archive, String entry) {
				try {
					return loader.loadArchiveEntry(archive, entry);
				}
				catch (IOException ex) {
					return null;
				}
			}

			@Override
			public byte @Nullable [] readArchiveEntryBytes(String archive, String entry) {
				try {
					return loader.loadArchiveEntryBytes(archive, entry);
				}
				catch (IOException ex) {
					return null;
				}
			}

			@Override
			public @Nullable String userConfigDir() {
				return userConfigDir;
			}

			@Override
			public List<String> aliases() {
				return aliases;
			}

			@Override
			public @Nullable List<String> list(String dir) {
				return loader.listDirectory(dir.isEmpty() ? "." : dir);
			}

			@Override
			public @Nullable ClojureRepositories repositories() {
				return repositories;
			}

			@Override
			public void addJavaClassPath(String jar, @Nullable String mavenCoordinate) {
				loader.addJavaClassPath(jar, mavenCoordinate);
			}
		};
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
	 * Opens a FORM-AT-A-TIME read of Common Lisp source -- what the interpreter's
	 * {@code load} and its entry file read with ({@code .kb/readtables.md}): each form is
	 * read with the readtable current when the read reaches it, after the forms before it
	 * ran, so an {@code in-readtable} or a {@code set-macro-character} changes how the
	 * rest of the file reads and a reader macro function runs in source order. The rules
	 * of {@link #read} hold per form: the {@code #.} decision (marker mode when the text
	 * holds one) and the wrapping of a circle in a literal.
	 * @param source the program text
	 * @param features the active reader features
	 * @param file the origin file, or {@code null} when unknown
	 * @return the read
	 */
	public static Forms readForms(String source, Features features, @Nullable String file) {
		return readFormsFrom(source, features, file, 0);
	}

	/**
	 * {@link #readForms} resumed at an offset of the source: the read of a form again
	 * where a reader macro function read it, in another image (the compile path's
	 * macro-time pass after its read-time image, {@code ReaderMacroReads}).
	 * @param source the program text
	 * @param features the features the guards test there, as {@link Forms#features}
	 * answered them
	 * @param file the origin file, or {@code null} when unknown
	 * @param start the UTF-16 offset the read resumes at
	 * @return the read
	 */
	public static Forms readFormsFrom(String source, Features features, @Nullable String file, int start) {
		LispReader.Forms forms = SourceProvenance.readingProgramSource(
				() -> LispReader.forms(source, features, usesReadEvalMarkers(source), file, null, start, true));
		return new Forms(forms, LABEL_DEFINITION.matcher(source).find());
	}

	/** A form-at-a-time read of Common Lisp source ({@link #readForms}). */
	public static final class Forms {

		private final LispReader.Forms forms;

		private final boolean labels;

		private Forms(LispReader.Forms forms, boolean labels) {
			this.forms = forms;
			this.labels = labels;
		}

		/**
		 * Reads the next top-level form.
		 * @param syntax the readtable to read it with, or {@code null} for the standard
		 * syntax
		 * @return the form, or {@code null} at the end of the source
		 * @throws LispReadException when the form does not read
		 */
		public @Nullable LispVal next(am.ik.rontolisp.reader.@Nullable ReadSyntax syntax) {
			LispVal form = this.forms.next(syntax);
			return (form != null && this.labels) ? LispTrees.shieldProgramForm(form) : form;
		}

		/**
		 * Reads the next top-level form once what the forms before it pushed onto
		 * {@code *features*} decides its guards too ({@code .kb/reader-features.md}).
		 * @param syntax the readtable to read it with, or {@code null} for the standard
		 * syntax
		 * @param pushed the feature names pushed since the source opened
		 * @return the form, or {@code null} at the end of the source
		 * @throws LispReadException when the form does not read
		 */
		public @Nullable LispVal next(am.ik.rontolisp.reader.@Nullable ReadSyntax syntax, List<String> pushed) {
			this.forms.widenFeatures(pushed);
			return next(syntax);
		}

		/**
		 * {@return where the read stands: just behind the last form read}
		 */
		public int position() {
			return this.forms.position();
		}

		/**
		 * {@return the features the guards of the forms still to read test}
		 */
		public Features features() {
			return this.forms.features();
		}

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
		return readStrict(source, features, null);
	}

	/**
	 * {@link #readStrict(String, Features)} with the files the source names read through
	 * a loader: a Scheme {@code include} and the {@code define-library} files a Scheme
	 * program imports (Scheme has no {@code #.}, so its read is the ordinary one).
	 * @param source the program text
	 * @param features the active reader features
	 * @param loader where named files are read from, or {@code null} when none can be
	 * @return the parsed top-level forms
	 */
	public List<LispVal> readStrict(String source, Features features, @Nullable SourceLoader loader) {
		if (this == SCHEME) {
			return read(source, features, null, SourceStandards.DEFAULT, loader);
		}
		if (this == CLOJURE) {
			return read(source, features, null, SourceStandards.DEFAULT, loader);
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
		if (path != null && (path.endsWith(CLOJURE.defaultExtension()) || path.endsWith(CLOJURE_COMMON_EXTENSION))) {
			return CLOJURE;
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
		return path.endsWith(COMMON_LISP.defaultExtension()) || path.endsWith(SCHEME.defaultExtension())
				|| path.endsWith(CLOJURE.defaultExtension()) || path.endsWith(CLOJURE_COMMON_EXTENSION);
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
		if (name.equals("clojure") || name.equals("clj")) {
			return CLOJURE;
		}
		throw new IllegalArgumentException("--source-language '" + override
				+ "' names no language this build reads (try common-lisp, or the experimental scheme, clojure)");
	}

}
