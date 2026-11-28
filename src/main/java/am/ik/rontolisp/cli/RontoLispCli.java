package am.ik.rontolisp.cli;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntConsumer;

import am.ik.maven.MavenResolver;
import am.ik.maven.RemoteRepository;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.Version;
import am.ik.rontolisp.codegen.wasm.WasmReportLocations;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.SplicedComponentGroups;
import am.ik.rontolisp.compiler.UncaughtReport;
import am.ik.rontolisp.eval.ClojureCommandLine;
import am.ik.rontolisp.eval.ClojureNrepl;
import am.ik.rontolisp.eval.CommonLispSwank;
import am.ik.rontolisp.eval.ClojureDepsRepositories;
import am.ik.rontolisp.eval.HttpLibrary;
import am.ik.rontolisp.eval.ImageBuilder;
import am.ik.rontolisp.eval.LinalgBlas;
import am.ik.rontolisp.eval.LinalgGpu;
import am.ik.rontolisp.eval.LispEvalException;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.ObjcInterop;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.eval.SourceSession;
import am.ik.rontolisp.eval.VecSimd;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.macro.BuiltinMacroLowering;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.Features;
import org.jspecify.annotations.Nullable;

/**
 * CLI entry point for rontolisp.
 */
public final class RontoLispCli {

	private final PrintStream out;

	private final InputStream in;

	private int exitCode;

	/**
	 * Whether the last {@link #runReporting} ended by the program's own quit
	 * ({@code uiop:quit}, {@code System/exit}, Scheme's {@code exit}).
	 */
	private boolean quitRequested;

	private @Nullable Boolean assumedTerminal;

	/**
	 * The stack of the thread the command line runs on ({@code --stack}), which each
	 * session of an nREPL server's thread takes too.
	 */
	private long stackBytes = SizedThread.WORKER_STACK_BYTES;

	/**
	 * What ends the process when a request to a SWANK server a program started quits:
	 * {@code System.exit} under {@code main}, nothing when embedded (that server only
	 * closes).
	 */
	private @Nullable IntConsumer processExit;

	private @Nullable JavaDependencyResolver javaDependencyResolver;

	private @Nullable ClojureDepsRepositories clojureRepositories;

	/** What compiles an image a program of this run dumps ({@link #imageBuilder}). */
	private @Nullable ImageBuilder imageBuilder;

	/**
	 * Create a new CLI instance.
	 * @param in the input stream
	 * @param out the output stream
	 */
	public RontoLispCli(InputStream in, PrintStream out) {
		this.in = in;
		this.out = out;
	}

	/**
	 * Where {@code --java-dep} coordinates resolve -- a test's fixture repository; by
	 * default Maven Central through the user's local repository.
	 * @param resolver the resolver
	 */
	void javaDependencyResolver(MavenResolver resolver) {
		this.javaDependencyResolver = (repositories, update) -> resolver;
	}

	/**
	 * Builds the resolver of {@code --java-dep} coordinates from the repositories to
	 * search (Maven Central, then the {@code --java-repository} ones) -- a test's fixture
	 * repositories.
	 * @param resolver the resolver factory
	 */
	void javaDependencyResolver(Function<List<RemoteRepository>, MavenResolver> resolver) {
		this.javaDependencyResolver = (repositories, update) -> resolver.apply(repositories);
	}

	/**
	 * Like {@link #javaDependencyResolver(Function)}, told whether
	 * {@code --java-update-snapshots} was given -- a test's fixture repositories under
	 * the resolver the command line builds.
	 * @param resolver the resolver factory
	 */
	void javaDependencyResolver(JavaDependencyResolver resolver) {
		this.javaDependencyResolver = resolver;
	}

	/**
	 * Where a Clojure program's {@code deps.edn} Maven and git coordinates are fetched
	 * from -- a test's fixture repositories; by default the network through the user's
	 * caches ({@link ClojureDepsRepositories#createDefault()}).
	 * @param repositories the repositories
	 */
	void clojureRepositories(ClojureDepsRepositories repositories) {
		this.clojureRepositories = repositories;
	}

	/**
	 * The exit code the last {@link #run} produced. Every mode but {@code format} leaves
	 * it at 0 and reports failure by throwing, which {@code main} turns into one line on
	 * standard error and exit code 1; the {@code format} subcommand instead has to
	 * distinguish "these files are not formatted" (1) from "something went wrong" (2) so
	 * it can be used as a CI gate, and a REPL reading from a pipe answers 1 when any form
	 * failed -- it reported each failure and went on, so there is nothing to throw.
	 * @return the exit code, 0 when there was nothing to report
	 */
	public int exitCode() {
		return this.exitCode;
	}

	/**
	 * Run the CLI with the given arguments.
	 * @param args the command-line arguments
	 */
	public void run(String[] args) {
		// Subcommands are matched before option parsing: they take their own arguments
		// (any
		// number of paths, unlike the single input file every other mode takes) and share
		// none of the compiler flags.
		if (args.length > 0 && "format".equals(args[0])) {
			this.exitCode = new FormatCommand(this.in, this.out).run(Arrays.copyOfRange(args, 1, args.length));
			return;
		}
		if (args.length > 0 && "notices".equals(args[0])) {
			this.exitCode = new NoticesCommand(this.out).run(Arrays.copyOfRange(args, 1, args.length));
			return;
		}
		// `test` is the exception: it takes a target and every compiler flag the
		// ordinary modes take, because it GENERATES a program (the rove runner around
		// the target) and then runs or compiles it through the very same pipeline. So
		// its arguments are parsed by CliOptions like any other run's, and only the
		// source is different.
		boolean test = args.length > 0 && "test".equals(args[0]);
		if (test) {
			args = Arrays.copyOfRange(args, 1, args.length);
		}
		// `build` likewise: asdf:make of a system, interpreted, its compile flags handed
		// to the image's dump (BuildCommand).
		boolean build = !test && args.length > 0 && "build".equals(args[0]);
		if (build) {
			args = Arrays.copyOfRange(args, 1, args.length);
		}

		// `test` alone takes several targets: the tests of several Java files run as one
		// program.
		CliOptions options = CliOptions.build(args, test);

		// every spelling of an option is stored under its first (CliOptionTable)
		if (options.contains("-h")) {
			if (test) {
				TestCommand.printUsage(this.out);
			}
			else if (build) {
				BuildCommand.printUsage(this.out);
			}
			else {
				CliOptionTable.printUsage(this.out, options.get("-h"));
			}
			return;
		}
		if (options.contains("-v")) {
			this.out.println(Version.getVersionAsJson());
			return;
		}

		// --scaffold-wit generates a program instead of running one, so it takes no .lisp
		// input and must short-circuit before the no-positional-argument REPL fallback.
		if (options.contains("--scaffold-wit")) {
			scaffold(Objects.requireNonNull(options.get("--scaffold-wit")), options.get("-o"), options.get("--world"),
					options.get("--source-language"));
			return;
		}

		// The .asd search path for asdf:load-system: the --system-path option first,
		// then the RONTOLISP_SOURCE_REGISTRY environment variable, both accepting
		// several directories joined with the platform path separator (like PATH). The
		// directory of the loading file is always searched first, before these.
		List<String> systemPath = systemPath(options.get("--system-path"), System.getenv("RONTOLISP_SOURCE_REGISTRY"));

		// The distributions ql:quickload downloads from: quicklisp always, plus whatever
		// --dist / RONTOLISP_DISTS name (a dist name or a distinfo URL, comma-separated).
		// A program can install one itself with (ql-dist:install-dist ...); these two are
		// for the invocations that have nowhere to put a form -- `rontolisp test SYSTEM`,
		// or a build that must not edit the sources it compiles.
		DistClient dists = DistClient.createDefault(distSpecs(options.get("--dist"), System.getenv("RONTOLISP_DISTS")));

		// --feature NAME: read-time features the USER adds to whatever set the target
		// already has, for a portable library whose #+ chain predates rontolisp and so
		// names no feature of ours (see declaredFeatures).
		List<String> features = declaredFeatures(options.get("--feature"));

		// --source-language NAME: the entry source's language, overriding the pick
		// from its extension. Parsed (not just carried) here so a misspelling fails
		// fast, before any file is read.
		String sourceLanguage = options.get("--source-language");
		if (sourceLanguage != null) {
			SourceLanguage.parse(sourceLanguage);
		}
		// --scheme-standard NAME: what EVERY Scheme file of the program is read against
		// -- the entry file, a loaded one, the REPL -- like gosh -r7; --starlark-standard
		// the same for Starlark. Parsed here for the same reason. The user-level deps.edn
		// every Clojure project merges is located here, from the environment the
		// oracle's clj reads, and nowhere else.
		SourceStandards standards = SourceStandards
			.parse(options.get("--scheme-standard"), options.get("--starlark-standard"))
			.withClojureConfigDir(SourceStandards.clojureConfigDir(System.getenv("CLJ_CONFIG"),
					System.getenv("XDG_CONFIG_HOME"), System.getProperty("user.home")))
			// and so are the repositories its Maven and git coordinates come from:
			// nothing is read or fetched until a deps.edn names one
			.withClojureRepositories(this.clojureRepositories != null ? this.clojureRepositories
					: ClojureDepsRepositories.createDefault(options.contains("--java-update-snapshots")));

		// --java-classpath / --java-dep / --java-release / --warn-java-reflection: the
		// program's Java classes and how java: call sites resolve
		// (compiler/JavaSiteResolver). Read once here so a malformed release number or
		// coordinate fails before any file is read; the coordinates resolve where the
		// program runs or compiles.
		JavaResolutionOptions javaResolution = JavaResolutionOptions.from(options);
		// --java-source-path / --java-source-dep: the libraries a program of the Java
		// subset uses, read as source and lowered with it on every backend. Every Java
		// file of the program reads them, so they travel with the standards, as the
		// shared repository of reachability metadata does (--java-metadata-repository).
		JavaSourcePath.Libraries libraries = JavaSourcePath.of(options, javaResolution.repositories(),
				this.javaDependencyResolver, System.err);
		standards = standards.withJavaSourcePath(libraries.roots())
			.withJavaMetadataRepository(libraries.metadataRepository(), libraries.coordinates());

		// What compiles an image the program dumps (uiop:dump-image, asdf:make of a
		// program-op system), installed on every evaluator this run creates: the run's
		// own compile flags reach the artifact, as they reach an -o compile.
		this.imageBuilder = imageBuilder(options, dists, standards, javaResolution);

		if (build) {
			BuildCommand.Program program = BuildCommand.build(options);
			if (program == null) {
				// The command line was wrong, not the build: 2, as `test` answers it.
				this.exitCode = 2;
				return;
			}
			for (String flag : List.of("--no-main", "--no-wasi", "--emit-wit", "--emit-js-glue", "--emit-worker-entry",
					"--host-random", "--host-output", "--host-fetch", "--host-sockets", "--host-timer",
					"--wasi-logging", "--host-boundary", "--reentrant", "--dump-ir", "--nrepl", "--swank",
					"--scaffold-wit")) {
				if (options.contains(flag)) {
					throw new UnsupportedOperationException("rontolisp build makes an executable program, which " + flag
							+ " does not describe: compile a library or a reactor with rontolisp FILE -o OUT " + flag);
				}
			}
			// The build image is the interpreter, the artifact its dump: the
			// acceleration flags reach both, every other compile flag the dump alone.
			interpret(program.source(), program.baseDir(), systemPath, dists, features, options.contains("--simd"),
					options.contains("--blas"), options.contains("--gpu"), options.contains("--parallel"), null,
					commandLine(null, List.of()), null, standards, javaResolution.warnReflection(),
					javaClassPath(javaResolution));
			return;
		}

		// -e/--eval "FORMS": the program is the argument itself rather than a file, and
		// nothing downstream can tell the difference -- it interprets, and with -o it
		// compiles. Only what a file itself provides is missing: a directory for a
		// relative (load "...") to resolve against (the working directory stands in) and
		// a name for a position-carrying error to print (line:column alone).
		String inline = options.get("-e");
		if (inline != null && options.containsNoKey()) {
			throw new IllegalArgumentException("-e/--eval cannot be combined with the input file '" + options.getNokey()
					+ "': give the program either inline or in a file");
		}

		// -A / -M / -X: a deps.edn project run the oracle's way. The aliases reach every
		// Clojure read through the standards; -M and -X decide what runs -- a generated
		// program calling a namespace's -main or an :exec-fn, a file, or the REPL.
		String positional = options.getNokey();
		List<String> programArguments = options.arguments();
		String generated = null;
		List<String> clojureAliases = options.clojureAliases();
		if (!clojureAliases.isEmpty()) {
			standards = standards.withClojureAliases(clojureAliases);
		}
		if (!test && (options.clojureRun() != null || options.contains("-A"))) {
			ClojureCommandLine.Resolved resolved = resolveClojureRun(options, standards);
			for (String warning : resolved.warnings()) {
				System.err.println(warning);
			}
			switch (resolved.entry()) {
				case ClojureCommandLine.Program program -> {
					generated = program.source();
					positional = null;
					programArguments = List.of();
					sourceLanguage = "clojure";
				}
				case ClojureCommandLine.Script script -> {
					positional = script.file();
					programArguments = script.arguments();
				}
				case ClojureCommandLine.Repl repl -> {
					positional = null;
					programArguments = repl.arguments();
				}
			}
		}
		if (options.contains("--nrepl")) {
			// --nrepl[=PORT]: a REPL served to editors, so it takes no program
			if (options.contains("--swank")) {
				throw new IllegalArgumentException("--nrepl and --swank cannot be combined: one server per process");
			}
			if (test || inline != null || positional != null || generated != null || options.contains("-o")) {
				throw new IllegalArgumentException("--nrepl serves a Clojure REPL over nREPL: it takes no program to"
						+ " run or compile (no input file, -e, -o or test)");
			}
			if (sourceLanguage != null && SourceLanguage.parse(sourceLanguage) != SourceLanguage.CLOJURE) {
				throw new IllegalArgumentException(
						"--nrepl serves Clojure: --source-language " + sourceLanguage + " cannot be served over nREPL");
			}
			refuseCompileOnlyJavaOptions(javaResolution);
			nrepl(nreplPort(Objects.requireNonNull(options.get("--nrepl"))),
					interactive(this.out, InputStream.nullInputStream(), systemPath, dists, features,
							options.contains("--simd"), options.contains("--blas"), options.contains("--gpu"),
							options.contains("--parallel"), commandLine(null, programArguments), standards,
							javaResolution.warnReflection(), javaClassPath(javaResolution)),
					standards);
			return;
		}
		if (options.contains("--swank")) {
			// --swank[=PORT]: a Common Lisp REPL served to SLIME, so it takes no program
			if (test || inline != null || positional != null || generated != null || options.contains("-o")) {
				throw new IllegalArgumentException("--swank serves a Common Lisp REPL over SWANK: it takes no program"
						+ " to run or compile (no input file, -e, -o or test)");
			}
			if (sourceLanguage != null && SourceLanguage.parse(sourceLanguage) != SourceLanguage.COMMON_LISP) {
				throw new IllegalArgumentException("--swank serves Common Lisp: --source-language " + sourceLanguage
						+ " cannot be served over SWANK");
			}
			refuseCompileOnlyJavaOptions(javaResolution);
			swank(swankPort(Objects.requireNonNull(options.get("--swank"))),
					interactive(this.out, InputStream.nullInputStream(), systemPath, dists, features,
							options.contains("--simd"), options.contains("--blas"), options.contains("--gpu"),
							options.contains("--parallel"), commandLine(null, programArguments), standards,
							javaResolution.warnReflection(), javaClassPath(javaResolution)));
			return;
		}
		if (!test && inline == null && positional == null && generated == null) {
			refuseCompileOnlyJavaOptions(javaResolution);
			repl(systemPath, dists, features, options.contains("--simd"), options.contains("--blas"),
					options.contains("--gpu"), options.contains("--parallel"), commandLine(null, programArguments),
					sourceLanguage, standards, javaResolution.warnReflection(), javaClassPath(javaResolution));
			return;
		}

		String inputFile;
		String source;
		String baseDir;
		if (test) {
			// The generated program is nobody's file, so it names none: an error inside
			// it prints line:column, while everything the target itself contributes
			// keeps the target's own positions. A Java target is its file itself.
			TestCommand.Program program = TestCommand.build(options, systemPath, standards);
			if (program == null) {
				// The command line was wrong, not the tests: 2 keeps that distinct from
				// the 1 a failing suite exits with, the way `format` separates them.
				this.exitCode = 2;
				return;
			}
			inputFile = program.file();
			source = program.source();
			baseDir = program.baseDir();
			standards = program.standards();
			if (program.sourceLanguage() != null) {
				sourceLanguage = program.sourceLanguage();
			}
		}
		else if (generated != null) {
			// A generated program is nobody's file either; its project is the working
			// directory's, like the oracle's.
			inputFile = null;
			source = generated;
			baseDir = null;
		}
		else {
			inputFile = inline == null ? Objects.requireNonNull(positional) : null;
			source = inputFile == null ? Objects.requireNonNull(inline) : readFile(inputFile);
			// Relative (load "...") paths resolve against the entry file's directory, so
			// a program can be run or compiled from any working directory and still find
			// its companion files (like Common Lisp's *load-pathname*).
			baseDir = inputFile == null ? null : SourceLoader.parentDir(inputFile);
		}

		// --dump-ir prints the compile path's final form -- the post-expansion IR, what
		// the backends would consume -- one top-level form per line, and exits without
		// compiling the backend half or writing anything. The front end reads with the
		// target's features and lowers for it (a Java class with no main is a reactor on
		// --no-wasi alone), so the dump is a compile's: the one -o and its flags name.
		boolean dumpIr = options.contains("--dump-ir");
		if (dumpIr || options.contains("-o")) {
			String outputFile = options.contains("-o") ? Objects.requireNonNull(options.get("-o"))
					: dumpTarget(options);
			// --emit-js-glue is a boolean and stays one: it writes a file, where
			// --host-boundary changes what the MODULE imports. A value on it used to be
			// accepted and thrown away (the key is in CliOptions.noValueKeys, which still
			// takes an `=` form), so the mode a build script asked for compiled the other
			// boundary without a word. Name the flag that means it instead.
			String glueValue = options.get("--emit-js-glue");
			if (glueValue != null && !glueValue.isEmpty()) {
				throw new IllegalArgumentException("--emit-js-glue takes no value ('" + glueValue
						+ "' given): it writes the JavaScript half of whatever boundary was built."
						+ " The boundary itself is --host-boundary=" + HostBoundary.spellings());
			}
			// Nor does --emit-worker-entry take a value: the entry's name is the one
			// wrangler.jsonc's `main` already states, so a second spelling of it here
			// could only disagree.
			String entryValue = options.get("--emit-worker-entry");
			if (entryValue != null && !entryValue.isEmpty()) {
				throw new IllegalArgumentException("--emit-worker-entry takes no value ('" + entryValue
						+ "' given): it writes " + HostGlueEmitter.WORKER_ENTRY
						+ " beside the -o module, the main a wrangler.jsonc names");
			}
			compileToFile(source, baseDir, systemPath, dists, features, outputFile, options.contains("--dynamic"),
					options.contains("--component"), options.contains("--no-wasi"),
					OptimizeLevel.parse(options.get("--optimize")), options.contains("--no-gc"), nativeTarget(options),
					options.contains("--simd"), options.contains("--blas"), options.contains("--gpu"),
					options.contains("--parallel"), options.contains("--no-prune"), options.contains("--no-main"),
					options.contains("--emit-wit"), options.contains("--emit-js-glue"),
					options.contains("--emit-worker-entry"), options.contains("--host-random"),
					options.contains("--host-output"), options.contains("--host-fetch"),
					options.contains("--host-sockets"), options.contains("--host-timer"),
					options.contains("--wasi-logging"), options.contains("--reentrant"),
					options.contains("--host-boundary") ? HostBoundary.parse(options.get("--host-boundary")) : null,
					options.contains("--report-locations")
							? WasmReportLocations.parse(options.get("--report-locations")) : null,
					JvmArtifactOptions.from(options), javaResolution, options.contains("--warnings-as-errors"),
					inputFile, sourceLanguage, standards, dumpIr);
		}
		else {
			// A side-artifact flag names a file to write BESIDE the output, so without
			// one there is nothing it could mean -- and interpreting the program instead
			// is the least useful answer (a Worker source would try to bind a socket).
			// Fail fast, the same way the flags do on the wrong output shape.
			for (String flag : List.of("--emit-wit", "--emit-js-glue", "--emit-worker-entry")) {
				if (options.contains(flag)) {
					throw new UnsupportedOperationException(
							flag + " writes a file next to a compiled output, so it needs -o <file>");
				}
			}
			if (options.contains("--reentrant")) {
				throw new UnsupportedOperationException(
						"--reentrant is a WASM module contract (overlapped JSPI calls), so it needs -o <file>.wasm");
			}
			if (options.contains("--host-timer")) {
				throw new UnsupportedOperationException("--host-timer is a WASM module's contract with its host (the"
						+ " waits it suspends in through JSPI), so it needs -o <file>.wasm and --no-wasi (the"
						+ " interpreter waits itself)");
			}
			if (options.contains("--wasi-logging")) {
				throw new UnsupportedOperationException("--wasi-logging imports wasi:logging, so it needs -o"
						+ " <file>.wasm and --component or --no-wasi (the interpreter logs to the standard error)");
			}
			if (options.contains("--report-locations")) {
				throw new UnsupportedOperationException("--report-locations chooses what a compiled wasm-GC module"
						+ " spends on its uncaught report, so it needs -o <file>.wasm (the interpreter always"
						+ " prints the location lines)");
			}
			if (options.contains("--native")) {
				throw new UnsupportedOperationException(
						"--native writes a native executable, so it needs -o <file> (e.g. -o hello)");
			}
			nativeTarget(options);
			// The interpreter emits none of the compile-time warnings: a wrong-count call
			// or an undefined function is only its call-time condition there.
			if (options.contains("--warnings-as-errors")) {
				throw new UnsupportedOperationException("--warnings-as-errors fails a COMPILE on its warnings, so it"
						+ " needs -o <file>; the interpreter warns about nothing before it runs");
			}
			if (options.contains("--no-main")) {
				throw new UnsupportedOperationException("--no-main compiles a JVM library class (no main method), so"
						+ " it needs -o <file>.class or -o <file>.jar");
			}
			// The artifact-describing flags name what a JVM compile WRITES, so without a
			// compile there is nothing they could describe.
			for (String flag : List.of("--class-name", "--maven-coordinates", "--emit-pom")) {
				if (options.contains(flag)) {
					throw new UnsupportedOperationException(flag
							+ " describes a compiled JVM artifact, so it needs -o <file>.class" + " or -o <file>.jar");
				}
			}
			refuseCompileOnlyJavaOptions(javaResolution);
			interpret(source, baseDir, systemPath, dists, features, options.contains("--simd"),
					options.contains("--blas"), options.contains("--gpu"), options.contains("--parallel"), inputFile,
					commandLine(inputFile, programArguments), sourceLanguage, standards,
					javaResolution.warnReflection(), javaClassPath(javaResolution));
		}
	}

	/**
	 * What a {@code -M}/{@code -X} run, or an {@code -A} selection, runs. {@code -M} and
	 * {@code -X} take everything after them, so a file or {@code -e} before them is
	 * refused rather than silently dropped. {@code -A} keeps the ordinary command line --
	 * a file, {@code -e} or the REPL -- unless its aliases name {@code :main-opts}, which
	 * the oracle runs (with a warning) ahead of the file and the arguments.
	 */
	private static ClojureCommandLine.Resolved resolveClojureRun(CliOptions options, SourceStandards standards) {
		CliOptions.ClojureRun run = options.clojureRun();
		SourceLoader files = SourceLoader.fileSystem();
		if (run != null) {
			String flag = run.exec() ? "-X" : "-M";
			if (options.containsNoKey() || options.contains("-e")) {
				throw new IllegalArgumentException(flag + " ends rontolisp's options, and what runs comes after it"
						+ " (rontolisp [options] "
						+ (run.exec() ? "-X:alias [fn] [k v]..." : "-M:alias -m my.app [args]")
						+ "), so it cannot be combined with "
						+ (options.containsNoKey() ? "the input file '" + options.getNokey() + "'" : "-e/--eval"));
			}
			if (!options.arguments().isEmpty()) {
				throw new IllegalArgumentException(
						flag + " passes every argument after it to the run, so '--' has nothing left to separate");
			}
			boolean replAliases = options.contains("-A");
			return run.exec() ? ClojureCommandLine.exec(run.arguments(), replAliases, standards, files)
					: ClojureCommandLine.main(run.arguments(), replAliases, standards, files);
		}
		boolean inline = options.contains("-e");
		List<String> argv = new ArrayList<>();
		if (options.containsNoKey()) {
			argv.add(Objects.requireNonNull(options.getNokey()));
		}
		if (!inline) {
			argv.addAll(options.arguments());
		}
		ClojureCommandLine.Resolved resolved = ClojureCommandLine.main(argv, true, standards, files);
		if (inline && !(resolved.entry() instanceof ClojureCommandLine.Repl)) {
			throw new IllegalArgumentException("-e/--eval cannot be combined with the :main-opts the -A aliases name;"
					+ " select them with -M to run them");
		}
		return inline
				? new ClojureCommandLine.Resolved(new ClojureCommandLine.Repl(options.arguments()), resolved.warnings())
				: resolved;
	}

	// The interpreter resolves java: sites against the classes it runs with, by
	// reflection: a JDK-release or no-reflection option would be silently ignored, so it
	// is refused. The class path is the interpreter's too.
	private static void refuseCompileOnlyJavaOptions(JavaResolutionOptions javaResolution) {
		if (javaResolution.namesCompileOnly()) {
			throw new UnsupportedOperationException("--java-release and --java-static decide how a compiled JVM"
					+ " program calls Java, so they need -o <file>.class or -o <file>.jar; the interpreter resolves"
					+ " against the JDK it runs on and its --java-classpath / --java-dep classes");
		}
	}

	// The program's Java class path, the coordinates resolved now.
	private JavaClassPath javaClassPath(JavaResolutionOptions javaResolution) {
		return JavaClassPath.of(javaResolution, this.javaDependencyResolver, System.err);
	}

	/**
	 * The dists named by {@code --dist} and by {@code RONTOLISP_DISTS}, in that order:
	 * each is a comma-separated list of dist names ({@code ultralisp}) or distinfo URLs.
	 * Comma-separated rather than {@code File.pathSeparator}-joined like
	 * {@code --system-path}, because a URL contains the separator itself.
	 * @param option the {@code --dist} value, or {@code null}
	 * @param env the {@code RONTOLISP_DISTS} value, or {@code null}
	 * @return the dist specs, in search order, without duplicates
	 */
	static List<String> distSpecs(@Nullable String option, @Nullable String env) {
		List<String> specs = new ArrayList<>();
		for (String joined : new String[] { option, env }) {
			if (joined == null) {
				continue;
			}
			// A repeated --dist arrives newline-joined (CliOptions.repeatableKeys).
			for (String spec : joined.split("[,\n]")) {
				String trimmed = spec.trim();
				if (!trimmed.isEmpty() && !specs.contains(trimmed)) {
					specs.add(trimmed);
				}
			}
		}
		return specs;
	}

	/**
	 * The features named by {@code --feature}: each value is a comma-separated list of
	 * feature names, spelled with or without the leading {@code :}, and they WIDEN the
	 * read-time feature set the target already has -- never narrow it
	 * ({@link Features#with}).
	 * <p>
	 * This is the one channel through which the USER -- rather than rontolisp -- decides
	 * what {@code #+} sees, and it exists because a portable library's {@code #+sbcl} /
	 * {@code #+clisp} / {@code #-(or ...)} chain was written before we existed and names
	 * no rontolisp feature at all, so the whole chain falls into its else branch
	 * (typically an {@code (error "not implemented")}) even where every primitive the
	 * sbcl branch calls answers exactly as SBCL does. Announcing {@code :sbcl} ourselves
	 * would be a lie told by us; a flag makes it a claim the user makes, about a library
	 * the user has read, for the run the user is starting -- and the consequence is the
	 * user's too, because the same claim also selects the branches that really do call
	 * that implementation's internals.
	 * <p>
	 * The names describing the BUILD are refused. {@code :rontolisp}, the backend
	 * features and the target-describing ones ({@code :rontolisp-component},
	 * {@code :rontolisp-reactor}, ...) say what the output is, and what the output is is
	 * decided by {@code -o} and the flags beside it: a source that read
	 * {@code #+rontolisp-wasm} because the command line said so and then compiled to a
	 * class would be broken by its own conditional rather than helped.
	 * @param option the {@code --feature} value, or {@code null}
	 * @return the feature names, without the leading colon, downcased, without duplicates
	 */
	static List<String> declaredFeatures(@Nullable String option) {
		List<String> names = new ArrayList<>();
		if (option == null) {
			return names;
		}
		// A repeated --feature arrives newline-joined (CliOptions.repeatableKeys); a
		// single one may still name several, comma-separated, like --dist.
		for (String spec : option.split("[,\n]")) {
			String trimmed = spec.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String stripped = trimmed.startsWith("#:") ? trimmed.substring(2)
					: trimmed.startsWith(":") ? trimmed.substring(1) : trimmed;
			String name = stripped.toLowerCase(Locale.ROOT);
			if (name.isEmpty()) {
				throw new IllegalArgumentException("--feature needs a feature name, e.g. --feature sbcl");
			}
			if (name.equals("rontolisp") || name.startsWith("rontolisp-")) {
				throw new IllegalArgumentException("--feature cannot declare '" + name
						+ "': the rontolisp features describe the build itself (which backend, the component"
						+ " boundary, reactor and servlet mode), and -o and the flags beside it are what decide"
						+ " those. --feature is for the names a portable library's #+ chain expects from the"
						+ " HOST implementation -- e.g. --feature sbcl");
			}
			if (!names.contains(name)) {
				names.add(name);
			}
		}
		return names;
	}

	static List<String> systemPath(@Nullable String option, @Nullable String env) {
		List<String> dirs = new ArrayList<>();
		for (String joined : new String[] { option, env }) {
			if (joined == null) {
				continue;
			}
			for (String dir : joined.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
				if (!dir.isEmpty() && !dirs.contains(dir)) {
					dirs.add(dir);
				}
			}
		}
		return dirs;
	}

	private static boolean isJLineAvailable() {
		try {
			Class.forName("org.jline.reader.LineReader");
			return true;
		}
		catch (ClassNotFoundException _) {
			return false;
		}
	}

	private void repl(List<String> systemPath, DistClient dists, List<String> declaredFeatures, boolean simd,
			boolean blas, boolean gpu, boolean parallel, List<String> commandLine, @Nullable String sourceLanguage,
			SourceStandards standards, boolean warnJavaReflection, JavaClassPath javaClassPath) {
		Interactive interactive = interactive(this.out, this.in, systemPath, dists, declaredFeatures, simd, blas, gpu,
				parallel, commandLine, standards, warnJavaReflection, javaClassPath);
		LispEvaluator evaluator = interactive.evaluator();
		// The REPL has no file to pick a language from: the override, else the default.
		SourceSession session = new SourceSession(SourceLanguage.forFile(null, sourceLanguage), standards,
				interactive.files());
		boolean systemTerminal = this.in == System.in && System.console() != null && System.console().isTerminal();
		boolean terminal = this.assumedTerminal != null ? this.assumedTerminal : systemTerminal;
		ReplBuffer repl = new ReplBuffer(session, evaluator, new ReplBuffer.Channels(this.out, System.err, terminal));
		try {
			if (systemTerminal && isJLineAvailable()) {
				JLineRepl.run(repl);
			}
			else {
				replWithBufferedReader(repl, evaluator);
			}
		}
		finally {
			// The session's end is the program's end, as interpret's is.
			evaluator.flushOpenStreams();
		}
		// A piped session is a script runner: a form that failed makes the whole run
		// fail, as a file's uncaught error does.
		if (repl.failedOnAPipe()) {
			this.exitCode = 1;
		}
	}

	/**
	 * An evaluator for an interactive session -- the REPL's, an nREPL server's -- and the
	 * files its inputs read.
	 *
	 * @param evaluator the evaluator
	 * @param files where a {@code load}, a {@code require} or a {@code load-file} reads
	 */
	private record Interactive(LispEvaluator evaluator, SourceLoader files) {
	}

	private Interactive interactive(PrintStream out, InputStream in, List<String> systemPath, DistClient dists,
			List<String> declaredFeatures, boolean simd, boolean blas, boolean gpu, boolean parallel,
			List<String> commandLine, SourceStandards standards, boolean warnJavaReflection,
			JavaClassPath javaClassPath) {
		LispEvaluator evaluator = new LispEvaluator(out, in);
		evaluator.setWarnOnJavaReflection(warnJavaReflection);
		evaluator.setSystemPath(systemPath);
		evaluator.setDeclaredFeatures(declaredFeatures);
		evaluator.setCommandLineArguments(commandLine);
		evaluator.setDistClient(dists);
		SourceLoader files = SourceLoader.fileSystem(javaClassPath.classLoader(), javaClassPath.classFiles(),
				javaClassPath::add);
		evaluator.setSourceLoader(files);
		requireSimdForParallel(simd, parallel);
		if (simd) {
			enableSimd(evaluator, parallel);
		}
		if (blas) {
			enableBlas(evaluator);
		}
		if (gpu) {
			enableGpu(evaluator);
		}
		if (parallel) {
			evaluator.setParallel(true);
		}
		evaluator.setSourceStandards(standards);
		serveSwankFromPrograms(evaluator);
		dumpImagesFromPrograms(evaluator);
		return new Interactive(evaluator, files);
	}

	/** Lets the program an evaluator runs dump its image ({@code uiop:dump-image}). */
	private void dumpImagesFromPrograms(LispEvaluator evaluator) {
		if (this.imageBuilder != null) {
			evaluator.setImageBuilder(this.imageBuilder);
		}
	}

	/**
	 * The compile of an image a program dumps ({@code uiop:dump-image},
	 * {@code ImageDump}): the program it wrote, to the artifact the dump names, with this
	 * run's compile flags, as an {@code -o} compile of a file takes them. The artifact's
	 * name decides what it is, the way {@code -o} does except for a name with no type,
	 * which is a native executable here: a program-op's artifact on a Unix host is the
	 * executable file itself ({@code --native} with no {@code -o} of its own).
	 */
	private ImageBuilder imageBuilder(CliOptions options, DistClient dists, SourceStandards standards,
			JavaResolutionOptions javaResolution) {
		return image -> {
			String output = image.output();
			if (output.endsWith(".war")) {
				throw new UnsupportedOperationException("uiop:dump-image: " + output + " names a .war, which a servlet"
						+ " container runs, not an executable program: name a .jar, a .wasm, or no type for a native"
						+ " executable");
			}
			boolean nativeOutput = !jvmOutput(output) && !output.endsWith(".wasm");
			NativeTarget target = null;
			if (nativeOutput) {
				target = NativeTarget.of(options.get("--native-target"), options.get("--native-cpu"));
			}
			else {
				for (String flag : List.of("--native", "--native-target", "--native-cpu")) {
					if (options.contains(flag)) {
						throw new UnsupportedOperationException(
								flag + " describes a native executable, and the image is" + " dumped to " + output
										+ ": a name with no type is a native executable");
					}
				}
			}
			compileToFile(image.source(), null, image.systemPath(), dists, image.declaredFeatures(), output,
					options.contains("--dynamic"), options.contains("--component"), options.contains("--no-wasi"),
					OptimizeLevel.parse(options.get("--optimize")), options.contains("--no-gc"), target,
					options.contains("--simd"), options.contains("--blas"), options.contains("--gpu"),
					options.contains("--parallel"), options.contains("--no-prune"), options.contains("--no-main"),
					options.contains("--emit-wit"), options.contains("--emit-js-glue"),
					options.contains("--emit-worker-entry"), options.contains("--host-random"),
					options.contains("--host-output"), options.contains("--host-fetch"),
					options.contains("--host-sockets"), options.contains("--host-timer"),
					options.contains("--wasi-logging"), options.contains("--reentrant"),
					options.contains("--host-boundary") ? HostBoundary.parse(options.get("--host-boundary")) : null,
					options.contains("--report-locations")
							? WasmReportLocations.parse(options.get("--report-locations")) : null,
					JvmArtifactOptions.from(options), javaResolution, options.contains("--warnings-as-errors"), null,
					null, standards, false);
		};
	}

	/**
	 * The port {@code --nrepl} names: any free one when it names none.
	 * @param option the option's value, empty for the bare flag
	 * @return the port
	 */
	static int nreplPort(String option) {
		if (option.isEmpty()) {
			return 0;
		}
		try {
			int port = Integer.parseInt(option);
			if (port >= 0 && port <= 65535) {
				return port;
			}
		}
		catch (NumberFormatException ex) {
			// refused below
		}
		throw new IllegalArgumentException("--nrepl=PORT expects a port between 0 and 65535, not '" + option + "'");
	}

	/**
	 * Serves a Clojure REPL over nREPL on the loopback interface until the process ends:
	 * prints the line the oracle prints, which editors parse for the port, and writes the
	 * port to {@code .nrepl-port} in the working directory, where editors look for it,
	 * deleted when the process ends.
	 */
	private void nrepl(int port, Interactive interactive, SourceStandards standards) {
		LispEvaluator evaluator = interactive.evaluator();
		ClojureNrepl clojure = new ClojureNrepl(evaluator, standards, interactive.files());
		Path portFile = Path.of(".nrepl-port");
		try (NreplServer server = new NreplServer(clojure, evaluator, InetAddress.getLoopbackAddress(), port,
				this.stackBytes)) {
			this.out.println(server.startedMessage());
			this.out.flush();
			Files.writeString(portFile, Integer.toString(server.port()));
			portFile.toFile().deleteOnExit();
			server.serve();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			try {
				Files.deleteIfExists(portFile);
			}
			catch (IOException ex) {
				// deleteOnExit tries again
			}
			evaluator.flushOpenStreams();
		}
	}

	/**
	 * The port {@code --swank} names: SWANK's 4005 when it names none, any free one for
	 * 0.
	 * @param option the option's value, empty for the bare flag
	 * @return the port
	 */
	static int swankPort(String option) {
		if (option.isEmpty()) {
			return 4005;
		}
		try {
			int port = Integer.parseInt(option);
			if (port >= 0 && port <= 65535) {
				return port;
			}
		}
		catch (NumberFormatException ex) {
			// refused below
		}
		throw new IllegalArgumentException("--swank=PORT expects a port between 0 and 65535, not '" + option + "'");
	}

	/**
	 * Lets the program an evaluator runs start the SWANK server on it
	 * ({@code swank:create-server}) and go on.
	 */
	private void serveSwankFromPrograms(LispEvaluator evaluator) {
		evaluator.setSwankHost(new SwankServers(evaluator, this.stackBytes, this.processExit));
	}

	/**
	 * Serves a Common Lisp REPL over SWANK on the loopback interface until the process
	 * ends, printing the line SWANK's {@code create-server} prints.
	 */
	private void swank(int port, Interactive interactive) {
		LispEvaluator evaluator = interactive.evaluator();
		CommonLispSwank lisp = new CommonLispSwank(evaluator);
		try (SwankServer server = new SwankServer(lisp, evaluator, InetAddress.getLoopbackAddress(), port,
				this.stackBytes)) {
			this.out.println(server.startedMessage());
			this.out.flush();
			server.serve();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			evaluator.flushOpenStreams();
		}
	}

	/**
	 * Makes the REPL behave as on a terminal (prompts, failures on standard output) or as
	 * on a pipe, whatever the input stream is: a test drives both from a byte array.
	 * @param terminal whether to behave as on a terminal
	 */
	void assumeTerminal(boolean terminal) {
		this.assumedTerminal = terminal;
	}

	private void replWithBufferedReader(ReplBuffer repl, LispEvaluator evaluator) {
		// Lines come through the evaluator's shared standard input reader -- the same
		// BufferedReader the read family uses -- so a piped session holds no second
		// look-ahead of its own and a (read) inside the session sees what was typed
		// next (a (driver-loop) typed at the prompt takes over).
		this.out.print(repl.prompt());
		this.out.flush();
		try {
			String line;
			while ((line = evaluator.readReplLine()) != null) {
				if ("(quit)".equals(line.trim())) {
					break;
				}
				repl.accept(line);
				if (repl.isEmpty()) {
					this.out.print(repl.prompt());
					this.out.flush();
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// --simd on the interpreter (file or REPL) routes the vec: and linalg: kernels to
	// jdk.incubator.vector. The native binary bakes the module in; a plain
	// `java -jar` does not, so probe and fall back to the scalar reference with a
	// note instead of failing. One probe covers both: the two kernel classes live in
	// the same incubator module.
	//
	// --parallel changes the calculus: it is asked for only by someone about to run
	// something large enough to be worth splitting across threads, and without the
	// module that "acceleration" is threads racing the scalar defun -- a ~100x
	// slowdown from the native kernel that reads as a hang under a long program's own
	// output, with the warning below as the only tell (an hour was once lost to it,
	// the warning scrolled off under an LLM decode loop's load line; .kb/vec.md). Unlike
	// the
	// compiled .class output (JvmSimdModuleFallbackTest), which may run on a different,
	// module-equipped machine LATER and so must keep degrading, the interpreter knows
	// RIGHT NOW whether the module is there: refuse instead of degrading.
	private static void enableSimd(LispEvaluator evaluator, boolean parallel) {
		if (VecSimd.available()) {
			evaluator.setSimd(true);
		}
		else if (parallel) {
			throw new UnsupportedOperationException(
					"--simd --parallel: jdk.incubator.vector is unavailable, so --parallel would only split"
							+ " the scalar vec:/linalg: kernels across threads instead of accelerating them -- a"
							+ " slowdown, not a speedup; re-run with `java --add-modules jdk.incubator.vector -jar"
							+ " ...`, or use the native binary.");
		}
		else {
			warn("--simd: jdk.incubator.vector is unavailable, running the scalar vec:/linalg: kernels; "
					+ "re-run with `java --add-modules jdk.incubator.vector -jar ...`, or use the native binary.");
		}
	}

	// --blas routes the linalg: matrix product to a tuned CBLAS found in the OS. Unlike
	// --simd there is nothing to bake in: whether one is there is a property of the
	// machine, so a decline is an ordinary outcome and says which library was rejected
	// and why (.kb/linalg-blas.md).
	private static void enableBlas(LispEvaluator evaluator) {
		if (LinalgBlas.available()) {
			evaluator.setBlas(true);
		}
		else {
			warn("--blas: " + LinalgBlas.description() + "; running the linalg: matrix product unaccelerated.");
		}
	}

	// --parallel is a modifier of --simd -- it splits the rows of the --simd matrix
	// products across threads and intercepts nothing of its own -- so without --simd it
	// could only be ignored, and a silent no-op is what an acceleration flag exists to
	// make visible (the --simd dead-flag lesson, CliOptionsTest). Checked on every path
	// that takes the flag: the interpreter, the REPL and the compiler.
	private static void requireSimdForParallel(boolean simd, boolean parallel) {
		if (parallel && !simd) {
			throw new UnsupportedOperationException(
					"--parallel splits the --simd kernels across threads, so it needs --simd:"
							+ " pass both (e.g. --simd --parallel)");
		}
	}

	// --gpu routes the linalg: matrix product and the element-wise transcendentals to an
	// NVIDIA GPU, or on a Mac to Apple Silicon through Metal. Like --blas the answer is
	// a property of the machine rather than of the build, so a decline is an ordinary
	// outcome; unlike --blas the probe itself costs something (a dlopen, a cuInit, a
	// retained primary context and a PTX JIT -- on Apple, MTLCreateSystemDefaultDevice
	// and an MSL compile), which is why nothing asks unless the flag was given
	// (.kb/gpu.md).
	private static void enableGpu(LispEvaluator evaluator) {
		if (LinalgGpu.available()) {
			evaluator.setGpu(true);
		}
		else {
			warn("--gpu: " + LinalgGpu.description() + "; running the linalg: kernels unaccelerated.");
		}
	}

	/**
	 * The argument vector the interpreted program reads as its own: the input file as
	 * argv0 -- {@code rontolisp} itself when the program came from {@code -e}, from
	 * {@code test} or from the REPL, where there is no program file to name -- followed
	 * by everything after the {@code --} separator. It is what {@code %host-argv}
	 * answers, and therefore what the {@code uiop/image} command-line family reads. The
	 * compile paths take no vector here: a compiled artifact gets its own at RUN time,
	 * from the JVM's {@code main} or from WASI.
	 * @param inputFile the program file, or {@code null} when there is none
	 * @param arguments the arguments after the {@code --} separator
	 * @return the vector, argv0 first
	 */
	private static List<String> commandLine(@Nullable String inputFile, List<String> arguments) {
		List<String> argv = new ArrayList<>();
		argv.add(inputFile == null ? "rontolisp" : inputFile);
		argv.addAll(arguments);
		return List.copyOf(argv);
	}

	// The IR a compile's front end handed its backend, one top-level form per line.
	// Printed with LispVal.print, whose output the reader reads back (the round trip:
	// rontolisp prog.lisp --dump-ir > ir.lisp; rontolisp ir.lisp runs the same program).
	private void printIr(List<LispVal> program) {
		// The load-context brackets are lowered as every backend lowers them first:
		// to the assignments a program reading the variables needs, and to nothing
		// otherwise -- a marker is no Common Lisp to read back.
		// So are the spliced components' brackets: to the asdf:perform calls and
		// top-level groups a program defining a perform method compiles, and to
		// nothing otherwise.
		for (LispVal form : SplicedComponentGroups.lower(LispMacroExpander.lowerLoadContextMarkers(program), null)) {
			// A (%struct-definition (defstruct ...)) marker is compile-path bookkeeping
			// the BACKENDS consume (LispMacroExpander.expandTopLevelDefinitions); the
			// interpreter knows only the defstruct itself, whose generated defuns
			// already ride the stream. The dump carries the payload, so the output runs
			// as it stands.
			this.out.println(BuiltinMacroLowering
				.lower(LispMacroExpander.structDefinitionPayload(form) instanceof LispCons payload ? payload : form)
				.print());
		}
		this.out.flush();
	}

	/**
	 * The output a {@code --dump-ir} with no {@code -o} dumps the compile of: a native
	 * executable with {@code --native}, a {@code .wasm} where a flag only a {@code .wasm}
	 * output takes is given, else a {@code .class}. Only its kind is read; nothing is
	 * written.
	 */
	private static String dumpTarget(CliOptions options) {
		if (options.contains("--native")) {
			return "program";
		}
		for (String flag : List.of("--no-wasi", "--component", "--no-gc", "--host-random", "--host-output",
				"--host-fetch", "--host-sockets", "--host-timer", "--wasi-logging", "--host-boundary", "--reentrant",
				"--report-locations", "--emit-wit", "--emit-js-glue", "--emit-worker-entry")) {
			if (options.contains(flag)) {
				return "program.wasm";
			}
		}
		return "program.class";
	}

	private void interpret(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			List<String> declaredFeatures, boolean simd, boolean blas, boolean gpu, boolean parallel,
			@Nullable String entryFile, List<String> commandLine, @Nullable String sourceLanguage,
			SourceStandards standards, boolean warnJavaReflection, JavaClassPath javaClassPath) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setWarnOnJavaReflection(warnJavaReflection);
		// java: and a Clojure host form see the program's Java class path, which the
		// jars of a Clojure program's dependencies join as it lowers.
		evaluator.setSourceLoader(
				SourceLoader.fileSystem(javaClassPath.classLoader(), javaClassPath.classFiles(), javaClassPath::add));
		evaluator.setLoadBaseDir(baseDir);
		evaluator.setSystemPath(systemPath);
		// The entry file, every file it loads and every ASDF component under it read
		// with the widened set -- and the run-time *features* holds the same names, so
		// a (member :F *features*) and the #+F beside it cannot disagree.
		evaluator.setDeclaredFeatures(declaredFeatures);
		evaluator.setCommandLineArguments(commandLine);
		evaluator.setDistClient(dists);
		requireSimdForParallel(simd, parallel);
		if (simd) {
			enableSimd(evaluator, parallel);
		}
		if (blas) {
			enableBlas(evaluator);
		}
		if (gpu) {
			enableGpu(evaluator);
		}
		if (parallel) {
			evaluator.setParallel(true);
		}
		// #. read-time eval: only sources textually containing #. pay for the marker
		// read; each top-level form's markers resolve just before it evaluates, the
		// same timing the runtime loadFile uses. Both the read and the #. question
		// are the source-language seam's, in the entry file's language.
		SourceLanguage language = SourceLanguage.forFile(entryFile, sourceLanguage);
		evaluator.setSourceStandards(standards);
		serveSwankFromPrograms(evaluator);
		dumpImagesFromPrograms(evaluator);
		InterruptSignal.listenFor(evaluator);
		// a whole Clojure program: its library without the arms it can never take, or,
		// where it lowers as it runs, every arm and its lowering kept for the run
		// A Common Lisp program is read form at a time as it runs (evalSource), so a form
		// is read with the readtable the forms before it left (.kb/readtables.md).
		List<LispVal> exprs = language == SourceLanguage.CLOJURE
				? evaluator.clojureProgram(language.readClojure(source, evaluator.features(), entryFile, standards,
						evaluator.sourceLoader()))
				: language == SourceLanguage.COMMON_LISP ? List.of()
						: language.read(source, evaluator.features(), entryFile, standards, evaluator.sourceLoader());
		boolean markers = SourceLanguage.usesReadEvalMarkers(source);
		// A Common Lisp entry file is the file being loaded: its forms -- and its #.
		// data, resolved just before each form runs -- see *load-pathname* (the path as
		// given) and *load-truename* (its truename), as the compile paths' bracket
		// around the same file does (LoadInliner).
		String loadedFile = language == SourceLanguage.COMMON_LISP ? entryFile : null;
		boolean entryContext = loadedFile != null;
		if (loadedFile != null) {
			evaluator.pushLoadContext(loadedFile, evaluator.sourceLoader().canonicalPath(loadedFile));
		}
		boolean ranOff = false;
		try {
			if (language == SourceLanguage.COMMON_LISP) {
				evaluator.evalSource(source, entryFile);
			}
			for (LispVal expr : exprs) {
				evaluator.eval(markers ? evaluator.resolveReadTimeEvalInCode(expr) : expr);
			}
			ranOff = true;
		}
		finally {
			if (entryContext) {
				evaluator.popLoadContext();
			}
			// The program has ended -- by its last form, by quit/exit (LispExitSignal) or
			// by an uncaught condition -- so an output file it never closed gets what it
			// still buffers, as it does on the other three backends.
			evaluator.flushOpenStreams();
			// Its process ends here, and the threads it started with it, unless it ran
			// off its end: then a thread that is no daemon (a server's) may hold the
			// process, except for a Java program, whose end has waited for those. In
			// process nothing else would end them.
			if (!ranOff || language == SourceLanguage.JAVA) {
				evaluator.stopThreads();
			}
		}
		// A program whose last write is a raw octet (write-byte to standard output) has
		// nothing left to flush it: an auto-flushing PrintStream only drains on a
		// newline, and a byte-oriented filter's output need not end in one.
		this.out.flush();
	}

	private void compileToFile(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			List<String> declaredFeatures, String outputFile, boolean dynamic, boolean component, boolean noWasi,
			OptimizeLevel optimize, boolean noGc, @Nullable NativeTarget nativeTarget, boolean simd, boolean blas,
			boolean gpu, boolean parallel, boolean noPrune, boolean noMain, boolean wit, boolean jsGlue,
			boolean workerEntry, boolean hostRandom, boolean hostOutput, boolean hostFetch, boolean hostSockets,
			boolean hostTimer, boolean wasiLogging, boolean reentrant, @Nullable HostBoundary hostBoundary,
			@Nullable WasmReportLocations reportLocations, JvmArtifactOptions jvmArtifact,
			JavaResolutionOptions javaResolution, boolean warningsAsErrors, @Nullable String entryFile,
			@Nullable String sourceLanguage, SourceStandards standards, boolean dumpIr) {
		CompileDiagnostics.recording(dists, () -> {
			compileRecorded(source, baseDir, systemPath, dists, declaredFeatures, outputFile, dynamic, component,
					noWasi, optimize, noGc, nativeTarget, simd, blas, gpu, parallel, noPrune, noMain, wit, jsGlue,
					workerEntry, hostRandom, hostOutput, hostFetch, hostSockets, hostTimer, wasiLogging, reentrant,
					hostBoundary, reportLocations, jvmArtifact, javaResolution, warningsAsErrors, entryFile,
					sourceLanguage, standards, dumpIr);
			return null;
		});
	}

	private void compileRecorded(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			List<String> declaredFeatures, String outputFile, boolean dynamic, boolean component, boolean noWasi,
			OptimizeLevel optimize, boolean noGc, @Nullable NativeTarget nativeTarget, boolean simd, boolean blas,
			boolean gpu, boolean parallel, boolean noPrune, boolean noMain, boolean wit, boolean jsGlue,
			boolean workerEntry, boolean hostRandom, boolean hostOutput, boolean hostFetch, boolean hostSockets,
			boolean hostTimer, boolean wasiLogging, boolean reentrant, @Nullable HostBoundary hostBoundary,
			@Nullable WasmReportLocations reportLocations, JvmArtifactOptions jvmArtifact,
			JavaResolutionOptions javaResolution, boolean warningsAsErrors, @Nullable String entryFile,
			@Nullable String sourceLanguage, SourceStandards standards, boolean dumpIr) {
		// The wasm flags, refused by name where the output or each other cannot honor
		// them; the same seam an embedder compiles a .wasm through (WasmSourceCompiler).
		// --native's -o name is refused before its flags, and the shim is loaded and the
		// target tried before the front end runs, so a host this build has no shim for,
		// a platform it has no stub for or a CPU level the shim does not know fails
		// before any work.
		WasmSourceCompiler wasm = new WasmSourceCompiler().dynamic(dynamic)
			.component(component)
			.noWasi(noWasi)
			.noGc(noGc)
			.optimize(optimize)
			.simd(simd)
			.hostRandom(hostRandom)
			.hostOutput(hostOutput)
			.hostFetch(hostFetch)
			.hostSockets(hostSockets)
			.hostTimer(hostTimer)
			.wasiLogging(wasiLogging)
			.reentrant(reentrant)
			.hostBoundary(hostBoundary)
			.reportLocations(reportLocations)
			.emitWit(wit)
			.emitJsGlue(jsGlue)
			.emitWorkerEntry(workerEntry)
			.nativeTarget(nativeTarget)
			.noPrune(noPrune);
		if (nativeTarget != null) {
			refuseNativeOutputName(outputFile);
		}
		wasm.refuseConflicts(outputFile.endsWith(".wasm"));
		// A dump runs no backend, so it needs no shim.
		if (nativeTarget != null && !dumpIr) {
			NativeToolchain.load().check(nativeTarget);
		}
		boolean wasmOutput = nativeTarget != null || outputFile.endsWith(".wasm");
		// --blas binds a native library through the foreign function API, which the
		// interpreter and the JVM backend have and WASM does not: a .wasm output could
		// only ignore the flag, and silently running unaccelerated is exactly what the
		// flag exists to make visible.
		// --no-main is the JVM backend's library mode (the --no-wasi reactor turn's
		// twin): it drops the main entry point, so it only means something for a
		// .class output.
		// --no-main and a .war are mutually exclusive: a war never has a main, so the
		// flag is either redundant or a mistake. Refuse it by name, before the general
		// jvmOutput check below would wave it through.
		if (noMain && outputFile.endsWith(".war")) {
			throw new UnsupportedOperationException("--no-main cannot be combined with a .war output: a war has no"
					+ " main to remove (its entry point is the servlet container). Drop the flag");
		}
		if (noMain && !jvmOutput(outputFile)) {
			throw new UnsupportedOperationException("--no-main compiles a JVM library class (no main method), so it"
					+ " needs a .class or .jar output -- e.g. -o Kernels.class --no-main."
					+ " The WASM equivalent is --no-wasi (reactor mode)");
		}
		// --class-name names the class a JVM compile emits. A --no-main jar REQUIRES it:
		// there the class is the artifact's Java API, and a name derived from the file
		// would be the caller's import. A program jar derives one instead (the manifest's
		// Main-Class is what runs it), and a .class takes it from the -o path unless the
		// flag overrides it.
		if (noMain && outputFile.endsWith(".jar") && jvmArtifact.className() == null) {
			throw new UnsupportedOperationException("--no-main makes the class itself the artifact's Java API, so a"
					+ " library jar has to name it: add --class-name com.example.Kernels."
					+ " Only a program jar derives its class name from the -o file name");
		}
		// --java-classpath / --java-dep / --java-release name the class files a JVM
		// compile resolves java: sites against, --java-static refuses the sites that
		// need reflection; java: has no WASM lowering at all.
		if ((javaResolution.namesCompileOnly() || javaResolution.namesClassPath()) && !jvmOutput(outputFile)) {
			throw new UnsupportedOperationException("--java-classpath, --java-dep, --java-release and --java-static"
					+ " decide how a JVM program calls Java, so they need a .class, .jar or .war output (or no -o:"
					+ " the interpreter takes the class path too)");
		}
		if (jvmArtifact.className() != null && !jvmOutput(outputFile)) {
			throw new UnsupportedOperationException("--class-name names the class a JVM compile emits, so it needs a"
					+ " .class or .jar output -- e.g. -o kernels.jar --class-name com.example.Kernels");
		}
		// --maven-coordinates stamps an artifact with its own identity, and the place it
		// travels is META-INF/maven inside a JAR or a WAR (a war IS a Maven artifact):
		// a bare .class has nowhere to carry it.
		if (jvmArtifact.coordinates() != null && !outputFile.endsWith(".jar") && !outputFile.endsWith(".war")) {
			throw new UnsupportedOperationException(
					"--maven-coordinates rides inside a jar's or a war's META-INF/maven,"
							+ " so it needs a .jar or .war output -- e.g. -o kernels-1.0.0.jar"
							+ " --class-name com.example.Kernels --maven-coordinates com.example:kernels:1.0.0");
		}
		if (jvmArtifact.emitPom() && jvmArtifact.coordinates() == null) {
			throw new UnsupportedOperationException("--emit-pom writes the pom of the coordinates the jar carries,"
					+ " so it needs --maven-coordinates groupId:artifactId:version");
		}
		if (blas && !jvmOutput(outputFile)) {
			throw new UnsupportedOperationException("--blas reaches the interpreter and the JVM class output only:"
					+ " a tuned CBLAS is called through the foreign function API, which WASM does not have."
					+ " Use --simd for the linalg: kernels on a .wasm output");
		}
		// --gpu is the same story one layer out: the CUDA driver and Metal are both
		// reached through the foreign function API, so WASM cannot have either, and a
		// silent no-op is exactly what the flag exists to prevent.
		if (gpu && !jvmOutput(outputFile)) {
			throw new UnsupportedOperationException("--gpu reaches the interpreter and the JVM class output only:"
					+ " a GPU is driven through the foreign function API, which WASM does not have."
					+ " Use --simd for the linalg: kernels on a .wasm output");
		}
		// --parallel needs threads, which neither WASM backend has (no threads in wasm-GC
		// or --no-gc), and it needs --simd, whose kernels are what it splits: both are
		// hard errors for the same reason as above -- the flag must never be a silent
		// no-op -- and the wasm outputs stay byte-identical to what they were.
		requireSimdForParallel(simd, parallel);
		if (parallel && !jvmOutput(outputFile)) {
			throw new UnsupportedOperationException("--parallel reaches the interpreter and the JVM class output only:"
					+ " a .wasm module has no threads to split the --simd kernels across."
					+ " Use --simd alone on a .wasm output");
		}
		// The compile path's whole front end -- the read, the (load ...) inlining, user
		// macro expansion, the library splice chain, the WIT lowerings, the boundp fold
		// and the library tree-shaker -- in the one place all four backends and the
		// embedded JVM seam (JvmSourceCompiler) share (CompileFrontend).
		// The program's Java class path: what its java: sites resolve against and what a
		// jar or war carries. A wasm output carries none and names none (refused above),
		// but a Clojure program's dependencies still bring their jars to the lowering and
		// its macro time, which run here, on the JVM.
		JavaClassPath javaClassPath = jvmOutput(outputFile) ? javaClassPath(javaResolution)
				: JavaClassPath.of(List.of());
		CompileFrontend.Result frontend = CompileFrontend.run(CompileFrontend.Request.builder()
			.source(source)
			.entryFile(entryFile)
			.sourceLanguage(sourceLanguage)
			.standards(standards)
			.systemPath(systemPath)
			.dists(dists)
			.declaredFeatures(declaredFeatures)
			.javaClassLoader(javaClassPath.classLoader())
			.javaClassLookup(javaClassPath.classFiles())
			.javaClassPath(javaClassPath::add)
			.options(CompileFrontend.Options.builder()
				.baseDir(baseDir)
				.wasm(wasmOutput)
				.servlet(outputFile.endsWith(".war"))
				.dynamic(dynamic)
				.component(component)
				.noWasi(noWasi)
				.noGc(noGc)
				.hostFetch(hostFetch)
				.hostSockets(hostSockets)
				.hostTimer(hostTimer)
				.wasiLogging(wasiLogging)
				.hostBoundary(hostBoundary)
				.reentrant(reentrant)
				.noPrune(noPrune)
				// Every runner answers the rlhttp imports of a fetch; only the macOS /
				// Apple-silicon one the rlobjc imports, so only that target accepts an
				// objc: program (.kb/objc.md, "--native").
				.nativePlatform(nativeTarget != null ? nativeTarget.platform() : null)
				.build())
			.build());
		if (dumpIr) {
			// Nothing is written, so a failed dump prints nothing, as a failed compile
			// writes nothing.
			CompileDiagnostics.failOnWarnings(warningsAsErrors);
			printIr(frontend.program());
			return;
		}
		List<LispVal> program = frontend.program();
		Features features = frontend.features();
		String runTimeImage = frontend.runTimeImage();
		byte[] bytes = null;
		WasmSourceCompiler.Result wasmCompiled = null;
		Map<String, byte[]> jvmRuntimeClasses = Map.of();
		// The internal name of the class a JVM compile emitted, needed again below to
		// place it inside a jar or to root the runtime classes beside a .class.
		String jvmClassName = null;
		if (wasmOutput) {
			wasmCompiled = wasm.compileProgram(frontend);
		}
		else {
			// The class name is the -o path with .class taken off (its directories the
			// class's package, but only where the JVM would load one -- an absolute path
			// leaves just the file's stem), or --class-name where
			// one was given -- and a jar output has no path to read one from, so a
			// program jar derives one from its file name and a --no-main library jar
			// (whose class is its API) has to be given one
			// (JvmArtifactOptions.internalClassName).
			// The backend half itself is JvmSourceCompiler's, which is also what an
			// embedder (the Maven plugin) compiles through, so the two cannot drift.
			JvmSourceCompiler.Result compiled = new JvmSourceCompiler(jvmArtifact.internalClassName(outputFile))
				.dynamic(dynamic)
				.optimize(optimize)
				.simd(simd)
				.blas(blas)
				.gpu(gpu)
				.parallel(parallel)
				.noMain(noMain)
				.servlet(outputFile.endsWith(".war"))
				.baseDir(baseDir)
				.javaRelease(javaResolution.release())
				.javaClasspath(javaClassPath.entries())
				.warnJavaReflection(javaResolution.warnReflection())
				.javaStatic(javaResolution.javaStatic())
				.compileProgram(program, features, runTimeImage);
			jvmClassName = compiled.internalClassName();
			bytes = compiled.classBytes();
			jvmRuntimeClasses = compiled.runtimeClasses();
		}
		// --warnings-as-errors: every warning is printed by now and nothing is written
		// yet, so the failed compile writes nothing, exactly as a compile error does.
		CompileDiagnostics.failOnWarnings(warningsAsErrors);
		try {
			// -o com/acme/Kernels.class places the class in a package via its path, so
			// the directory is part of the request; create it instead of failing.
			Path outputPath = Path.of(outputFile);
			if (outputPath.getParent() != null) {
				Files.createDirectories(outputPath.getParent());
			}
			if (outputFile.endsWith(".jar")) {
				// A jar is the same bytecode with the packaging a consumer needs around
				// it: the manifest, the runtime classes (which the .class path writes
				// beside the output -- leaving them out is a NoClassDefFoundError in the
				// consumer, not an error here), and the coordinates when given. A program
				// jar's manifest names its Java class path, copied beside it so that
				// java -jar finds it; a library jar's pom names the --java-dep
				// coordinates and leaves the class path to its consumer.
				List<String> classPath = noMain ? List.of() : javaClassPath.copyBeside(outputPath);
				Files.write(outputPath,
						JvmJarWriter.jar(Objects.requireNonNull(jvmClassName), Objects.requireNonNull(bytes),
								jvmRuntimeClasses, !noMain, jvmArtifact.coordinates(), simd, classPath,
								javaClassPath.dependencies()));
				if (jvmArtifact.emitPom()) {
					writePom(outputFile, Objects.requireNonNull(jvmArtifact.coordinates()), simd,
							javaClassPath.dependencies());
				}
			}
			else if (outputFile.endsWith(".war")) {
				// A war is the same bytecode (compiled in servlet mode) inside
				// WEB-INF/classes, plus the one-line service declaration that lets any
				// Servlet 6 container discover the program itself -- no web.xml, no
				// configuration (JvmWarWriter). The Java class path rides in WEB-INF/lib.
				Files.write(outputPath,
						JvmWarWriter.war(Objects.requireNonNull(jvmClassName), Objects.requireNonNull(bytes),
								jvmRuntimeClasses, jvmArtifact.coordinates(), simd, javaClassPath.warEntries(),
								javaClassPath.dependencies()));
				if (jvmArtifact.emitPom()) {
					writePom(outputFile, Objects.requireNonNull(jvmArtifact.coordinates()), simd,
							javaClassPath.dependencies());
				}
			}
			else if (wasmCompiled != null) {
				wasmCompiled.write(outputPath);
			}
			else {
				Files.write(outputPath, Objects.requireNonNull(bytes));
				if (!jvmRuntimeClasses.isEmpty()) {
					// Rooted where the output class's own package root is -- the -o path
					// IS the package, so `-o com/acme/Kernels.class` writes them under
					// the same tree and `javac -cp .` / `jar cf` pick them up with no
					// arrangement.
					String root = JvmArtifactOptions.classRoot(outputFile, Objects.requireNonNull(jvmClassName));
					for (Map.Entry<String, byte[]> runtimeClass : jvmRuntimeClasses.entrySet()) {
						Path runtimePath = Path.of(root + runtimeClass.getKey());
						// A default-package program's $PartN class sits in the root
						// itself, so it has no directory of its own to create.
						Path runtimeDir = runtimePath.getParent();
						if (runtimeDir != null) {
							Files.createDirectories(runtimeDir);
						}
						Files.write(runtimePath, runtimeClass.getValue());
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * {@code --scaffold-wit world.wit [-o impl.lisp] [--world name]}: writes a runnable
	 * skeleton implementing the world (a {@code rontolisp:wit-export} directive plus one
	 * {@code defun} stub per export; for {@code -o Greeter.java} or
	 * {@code --source-language java}, a {@code @WitExport} class), or prints it to stdout
	 * when no {@code -o} is given.
	 */
	private void scaffold(String witFile, @Nullable String outputFile, @Nullable String world,
			@Nullable String sourceLanguage) {
		String witSource = readFile(witFile);
		// The path the generated source names must resolve against the generated file's
		// own directory, the way wit-export (like load) resolves it.
		String directivePath = outputFile == null ? witFile
				: relativeToOutput(SourceLoader.parentDir(outputFile), witFile);
		SourceLanguage language = SourceLanguage.forFile(outputFile, sourceLanguage);
		String lisp;
		if (language == SourceLanguage.JAVA) {
			// the annotation names the WIT as Class.getResource names a resource: below
			// the source tree's top, a leading '/' from that top
			if (directivePath.startsWith("..") || Path.of(directivePath).isAbsolute()) {
				warn("--scaffold-wit: the annotations name the WIT " + directivePath
						+ ", which a source tree's resource is not: put the WIT below the Java file's directory");
			}
			lisp = WitScaffolder.scaffoldJava(witSource, witFile, directivePath, outputFile, world,
					scaffoldClassName(outputFile, witSource, world, directivePath),
					refusal -> warn("--scaffold-wit left out " + refusal));
		}
		else if (language == SourceLanguage.COMMON_LISP) {
			lisp = WitScaffolder.scaffold(witSource, directivePath, world);
		}
		else {
			throw new UnsupportedOperationException(
					"--scaffold-wit writes Common Lisp or Java, not " + language.name().toLowerCase(Locale.ROOT));
		}
		if (outputFile == null) {
			this.out.print(lisp);
			return;
		}
		try {
			Files.writeString(Path.of(outputFile), lisp);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// The class a Java skeleton declares: the output file's name, else the world's in
	// upper camel case.
	private static String scaffoldClassName(@Nullable String outputFile, String witSource, @Nullable String world,
			String witPath) {
		String name;
		if (outputFile != null) {
			String file = Path.of(outputFile).getFileName().toString();
			name = file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
		}
		else {
			StringBuilder camel = new StringBuilder();
			for (String word : WitScaffolder.worldName(witSource, world, witPath).split("-")) {
				camel.append(word.isEmpty() ? "" : Character.toUpperCase(word.charAt(0)) + word.substring(1));
			}
			name = camel.toString();
		}
		if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))
				|| !name.chars().allMatch(Character::isJavaIdentifierPart)) {
			throw new UnsupportedOperationException("--scaffold-wit: " + outputFile
					+ " names no Java class: the file's name is the class's, a Java identifier");
		}
		return name;
	}

	private static String relativeToOutput(@Nullable String outputDir, String witFile) {
		if (outputDir == null || outputDir.isEmpty()) {
			return witFile;
		}
		try {
			return Path.of(outputDir)
				.toAbsolutePath()
				.normalize()
				.relativize(Path.of(witFile).toAbsolutePath().normalize())
				.toString();
		}
		catch (IllegalArgumentException _) {
			// No relative path exists (e.g. different roots on Windows): keep it as
			// given.
			return witFile;
		}
	}

	/**
	 * The target of a {@code --native} output, or {@code null} for any other: its two
	 * options describe the executable, so either one without {@code --native} is refused
	 * rather than ignored.
	 */
	private static @Nullable NativeTarget nativeTarget(CliOptions options) {
		if (!options.contains("--native")) {
			for (String flag : List.of("--native-target", "--native-cpu")) {
				if (options.contains(flag)) {
					throw new UnsupportedOperationException(
							flag + " describes a native executable, so it needs --native -o <file>");
				}
			}
			return null;
		}
		return NativeTarget.of(options.get("--native-target"), options.get("--native-cpu"));
	}

	/**
	 * Refuses an {@code -o} name that says another output than {@code --native}'s
	 * executable; its flags are {@link WasmSourceCompiler#refuseConflicts}'s.
	 */
	private static void refuseNativeOutputName(String outputFile) {
		for (String extension : List.of(".wasm", ".class", ".jar", ".war")) {
			if (outputFile.endsWith(extension)) {
				throw new UnsupportedOperationException(
						"--native writes a native executable, but -o " + outputFile + " names a " + extension
								+ " output: drop --native for that, or name the executable (e.g. -o hello)");
			}
		}
	}

	/**
	 * A JVM compile: a bare {@code .class}, the {@code .jar} that packages it, or the
	 * {@code .war} that packages it for a servlet container. All three carry the same
	 * bytecode (the war additionally compiled in servlet mode), so every flag that
	 * reaches the JVM backend reaches each of them.
	 */
	private static boolean jvmOutput(String outputFile) {
		return outputFile.endsWith(".class") || outputFile.endsWith(".jar") || outputFile.endsWith(".war");
	}

	/**
	 * {@code --emit-pom}: writes the generated pom NEXT to the jar as well, the way
	 * {@code --emit-wit} writes the world next to the {@code .wasm} -- for a
	 * {@code deploy-file} that wants the pom as a separate file. Only a pom this flag
	 * wrote before is overwritten; anything else is someone's own and is refused by name.
	 */
	private static void writePom(String outputFile, MavenCoordinates coordinates, boolean simd,
			List<am.ik.maven.Artifact> dependencies) throws IOException {
		// .jar and .war are the same length, so one cut serves both archive outputs.
		Path pom = Path.of(outputFile.substring(0, outputFile.length() - ".jar".length()) + ".pom");
		if (Files.exists(pom)
				&& !Files.readString(pom, StandardCharsets.UTF_8).startsWith(MavenCoordinates.POM_MARKER)) {
			throw new UnsupportedOperationException("--emit-pom would overwrite " + pom
					+ ", which it did not write (it does not start with \"" + MavenCoordinates.POM_MARKER
					+ "\"). Compile to a different -o name, or move that file aside");
		}
		Files.writeString(pom, coordinates.pomXml(simd, dependencies));
	}

	// Emits a one-line warning to stderr. Kept off stdout (this.out) so it never corrupts
	// a
	// compiled program's piped output or the REPL transcript.
	private static void warn(String message) {
		System.err.println("rontolisp: warning: " + message);
	}

	private static String readFile(String path) {
		try {
			return Files.readString(Path.of(path));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Main entry point.
	 * @param args the command-line arguments
	 */
	public static void main(String[] args) {
		// The CLI ALWAYS runs on a thread of its own, never on the one the launcher
		// happened to call main on: the interpreter's recursion depth is the PROGRAM's,
		// and thread 0 carries whatever stack the platform gives it -- about 8 MiB on
		// macOS, 1 MiB on linux-x64, which an ordinary vendored library's own test suite
		// already recurses past. The depth ceiling is therefore one number everywhere,
		// SizedThread.WORKER_STACK_BYTES or --stack (.kb/interpreter-stack.md).
		LaunchStack stack;
		try {
			stack = LaunchStack.of(args);
		}
		catch (IllegalArgumentException ex) {
			System.err.println("error: " + ex.getMessage());
			System.exit(1);
			return;
		}
		String[] rest = stack.args();
		long stackBytes = stack.bytes();
		// In the native binary on macOS, main runs on the process's FIRST thread -- the
		// one AppKit demands for every window and the one nothing drains. So there thread
		// 0 parks in the run loop, which pumps AppKit and the main dispatch queue the
		// objc: package hops through, and never comes back; everywhere else it waits for
		// the worker and carries its outcome out of main (.kb/objc.md).
		if (ObjcInterop.mainThreadHandOverRequired()) {
			Thread worker = new Thread(null, () -> {
				int code;
				try {
					code = launch(rest, stackBytes).code();
				}
				catch (Throwable ex) {
					ex.printStackTrace();
					code = 1;
				}
				// Thread 0 never returns from the run loop, so the exit code has to
				// leave through System.exit whatever its value.
				System.exit(code);
			}, "main", stackBytes);
			worker.start();
			ObjcInterop.parkMainThread();
			return;
		}
		exit(joinLaunch(rest, stackBytes));
	}

	/**
	 * Runs the whole command line on a worker with the chosen stack and waits for it,
	 * answering the exit code.
	 * <p>
	 * What the worker threw is rethrown HERE rather than reported on the worker, so an
	 * {@code Error} the CLI does not handle -- a {@code StackOverflowError} from a
	 * program deeper than its stack, an {@code OutOfMemoryError} -- still reaches main's
	 * default handler with its own trace and still ends the process with a failure,
	 * exactly as it did when launch ran on thread 0.
	 */
	private static Launched joinLaunch(String[] args, long stackBytes) {
		return SizedThread.call("main", stackBytes, () -> launch(args, stackBytes));
	}

	/**
	 * How the command line ended.
	 *
	 * @param code the process status
	 * @param quit whether the program asked for it by quitting, which ends the process
	 * even at status 0 while threads it started still run
	 */
	private record Launched(int code, boolean quit) {
	}

	/** The largest {@code --stack} accepted, in MiB. */
	private static final long MAX_STACK_MIB = 65536L;

	private static final String STACK_OPTION = "--stack";

	/**
	 * What {@code --stack} asked for and the command line with it removed.
	 *
	 * @param bytes the stack the CLI's thread is given
	 * @param args every other argument, in order
	 */
	// Package-private, not private, so RontoLispCliTest can drive it: main itself may end
	// with System.exit and cannot be called from a test JVM.
	record LaunchStack(long bytes, String[] args) {

		/**
		 * Reads {@code --stack <MiB>} (or {@code --stack=<MiB>}) off the raw arguments.
		 * <p>
		 * Raw, because the thread has to exist before anything parses a command line on
		 * it -- and the flag exists because a {@code java -jar}'s {@code -Xss} no longer
		 * reaches the interpreter once the CLI has a stack of its own, so this is the
		 * knob that replaces it (the native binary never had one). The option is
		 * CONSUMED: no subcommand or option parser downstream has to know it exists.
		 * Everything after the first bare {@code --} belongs to the interpreted program,
		 * so the scan stops there and passes the rest through untouched.
		 * @param args the command-line arguments
		 * @return the stack size and the arguments that are not this option
		 * @throws IllegalArgumentException if the value is not a size in MiB a thread can
		 * be given
		 */
		static LaunchStack of(String[] args) {
			String value = null;
			List<String> rest = new ArrayList<>(args.length);
			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				if ("--".equals(arg)) {
					rest.addAll(Arrays.asList(args).subList(i, args.length));
					break;
				}
				if (arg.startsWith(STACK_OPTION + "=")) {
					value = arg.substring(STACK_OPTION.length() + 1);
				}
				else if (STACK_OPTION.equals(arg) && i + 1 < args.length) {
					value = args[++i];
				}
				else {
					rest.add(arg);
				}
			}
			return new LaunchStack(bytes(value), rest.toArray(String[]::new));
		}

		private static long bytes(@Nullable String value) {
			if (value == null) {
				return SizedThread.WORKER_STACK_BYTES;
			}
			long mib;
			try {
				mib = Long.parseLong(value.trim());
			}
			catch (NumberFormatException ex) {
				mib = -1;
			}
			if (mib <= 0 || mib > MAX_STACK_MIB) {
				throw new IllegalArgumentException(STACK_OPTION + " expects a stack size in MiB between 1 and "
						+ MAX_STACK_MIB + ", not '" + value + "'");
			}
			return mib << 20;
		}
	}

	/** The whole command line, on a thread of the given stack, answering how it ended. */
	private static Launched launch(String[] args, long stackBytes) {
		// By default stdout stays auto-flushing so each REPL input gets an immediate
		// response. Opt in to a block-buffered, non-auto-flushing stream with
		// --buffered-output: flushing on every print/format call interleaves badly when
		// the output is piped, and buffering coalesces it into whole writes. A shutdown
		// hook drains the buffer even on System.exit, and a final flush covers normal
		// exit.
		boolean buffered = false;
		for (String arg : args) {
			if ("--buffered-output".equals(arg)) {
				buffered = true;
				break;
			}
		}
		if (!buffered) {
			RontoLispCli cli = new RontoLispCli(System.in, System.out);
			cli.stackBytes = stackBytes;
			cli.processExit = System::exit;
			int code = runReporting(cli, args);
			return new Launched(code, cli.quitRequested);
		}
		PrintStream bufferedOut = new PrintStream(
				new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
				StandardCharsets.UTF_8);
		System.setOut(bufferedOut);
		Runtime.getRuntime().addShutdownHook(new Thread(bufferedOut::flush));
		RontoLispCli cli = new RontoLispCli(System.in, bufferedOut);
		cli.stackBytes = stackBytes;
		cli.processExit = System::exit;
		try {
			int code = runReporting(cli, args);
			return new Launched(code, cli.quitRequested);
		}
		finally {
			bufferedOut.flush();
		}
	}

	/**
	 * Runs the CLI and turns a failure into ONE line on standard error plus exit code 1.
	 * <p>
	 * Only {@code main} reports: {@link #run} still throws, so an embedded caller (the
	 * tests, the playground) keeps the exception with its type and its cause. What the
	 * catch replaces is the default handler's stack trace, which names the INTERPRETER's
	 * frames rather than the program's -- 212 lines of {@code LispEvaluator.evalLet}
	 * above the one line that carried the diagnosis. The trace is still one
	 * {@code RONTOLISP_DEBUG} away, because it is the right answer when the bug being
	 * chased is rontolisp's own.
	 */
	// Package-private, not private, so RontoLispCliTest can drive it: main itself ends
	// with System.exit and cannot be called from a test JVM.
	static int runReporting(RontoLispCli cli, String[] args) {
		try {
			cli.run(args);
			return cli.exitCode();
		}
		catch (LispExitSignal exit) {
			// (uiop:quit code): the program asked for a status code, so main hands the
			// host exactly that and reports nothing. The buffered stream is drained the
			// way a normal return drains it -- quit finishes the Lisp output streams
			// before it signals, and this covers the CLI's own buffer underneath them.
			cli.out.flush();
			cli.quitRequested = true;
			return exit.code();
		}
		catch (RuntimeException ex) {
			// The program's own output precedes the report even under
			// --buffered-output, whose stream is drained only at the exit below.
			cli.out.flush();
			System.err.println(failureLine(ex));
			if (UncaughtReport.debugTraceRequested()) {
				ex.printStackTrace();
			}
			return 1;
		}
		catch (StackOverflowError ex) {
			// Not a condition the program signaled, and its trace is thousands of
			// interpreter frames repeating one call: the one line names the knob.
			cli.out.flush();
			System.err.println("error: " + ReplBuffer.STACK_OVERFLOW);
			if (UncaughtReport.debugTraceRequested()) {
				ex.printStackTrace();
			}
			return 1;
		}
	}

	/**
	 * What a failure prints. A condition the program signaled and nobody caught gets the
	 * cross-backend {@link UncaughtReport} wording -- the same line the JVM and wasm
	 * backends print for the same condition -- followed by the location lines naming
	 * where it happened in the program's files; anything else (a read error, a compile
	 * failure, a bad command line) is a rontolisp diagnostic and says {@code error:},
	 * keeping whatever {@code file:line:column:} prefix the frontend already put on it.
	 */
	private static String failureLine(RuntimeException ex) {
		String message = ex.getMessage();
		if (message == null || message.isEmpty()) {
			message = ex.getClass().getName();
		}
		if (!(ex instanceof LispEvalException condition)) {
			return "error: " + message;
		}
		// The report line, then where it happened (UncaughtReport's location lines).
		StringBuilder report = new StringBuilder(UncaughtReport.line(message));
		for (String location : condition.locationLines()) {
			report.append(System.lineSeparator()).append(location);
		}
		if (!condition.advice().isEmpty()) {
			report.append(System.lineSeparator()).append(UncaughtReport.adviceLine(condition.advice()));
		}
		return report.toString();
	}

	// A non-zero exit code has to go through System.exit -- returning from main is always
	// 0 -- and so does a quit: returning would leave the process running for as long as
	// a thread the program started does (a server's), where System.exit on the JVM
	// backend ends it. A program that ran off its end returns normally instead, waiting
	// for its threads as the JVM waits for any, and an embedded caller (tests, the native
	// binary's own smoke checks) is not killed by a successful run.
	private static void exit(Launched launched) {
		if (launched.code() != 0 || launched.quit()) {
			System.exit(launched.code());
		}
	}

}
