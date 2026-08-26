package am.ik.rontolisp.cli;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.Version;
import am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.UncaughtReport;
import am.ik.rontolisp.eval.HttpLibrary;
import am.ik.rontolisp.eval.LinalgBlas;
import am.ik.rontolisp.eval.LinalgGpu;
import am.ik.rontolisp.eval.LispEvalException;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.ObjcInterop;
import am.ik.rontolisp.eval.VecSimd;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * CLI entry point for rontolisp.
 */
public final class RontoLispCli {

	private final PrintStream out;

	private final InputStream in;

	private int exitCode;

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
	 * The exit code the last {@link #run} produced. Every mode but {@code format} leaves
	 * it at 0 and reports failure by throwing, which {@code main} turns into one line on
	 * standard error and exit code 1; the {@code format} subcommand instead has to
	 * distinguish "these files are not formatted" (1) from "something went wrong" (2) so
	 * it can be used as a CI gate.
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
		// `test` is the exception: it takes one target and every compiler flag the
		// ordinary modes take, because it GENERATES a program (the rove runner around
		// the target) and then runs or compiles it through the very same pipeline. So
		// its arguments are parsed by CliOptions like any other run's, and only the
		// source is different.
		boolean test = args.length > 0 && "test".equals(args[0]);
		if (test) {
			args = Arrays.copyOfRange(args, 1, args.length);
		}

		CliOptions options = CliOptions.build(args);

		if (options.contains("-h") || options.contains("--help")) {
			if (test) {
				TestCommand.printUsage(this.out);
			}
			else {
				printUsage();
			}
			return;
		}
		if (options.contains("-v") || options.contains("--version")) {
			this.out.println(Version.getVersionAsJson());
			return;
		}

		// --scaffold-wit generates a program instead of running one, so it takes no .lisp
		// input and must short-circuit before the no-positional-argument REPL fallback.
		if (options.contains("--scaffold-wit")) {
			scaffold(Objects.requireNonNull(options.get("--scaffold-wit")), options.get("-o"), options.get("--world"));
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
		if (!test && inline == null && !options.containsNoKey()) {
			repl(systemPath, dists, options.contains("--simd"), options.contains("--blas"), options.contains("--gpu"),
					options.contains("--parallel"), commandLine(null, options.arguments()));
			return;
		}

		String inputFile;
		String source;
		String baseDir;
		if (test) {
			// The generated program is nobody's file, so it names none: an error inside
			// it prints line:column, while everything the target itself contributes
			// keeps the target's own positions.
			TestCommand.Program program = TestCommand.build(options, systemPath);
			if (program == null) {
				// The command line was wrong, not the tests: 2 keeps that distinct from
				// the 1 a failing suite exits with, the way `format` separates them.
				this.exitCode = 2;
				return;
			}
			inputFile = null;
			source = program.source();
			baseDir = program.baseDir();
		}
		else {
			inputFile = inline == null ? Objects.requireNonNull(options.getNokey()) : null;
			source = inputFile == null ? Objects.requireNonNull(inline) : readFile(inputFile);
			// Relative (load "...") paths resolve against the entry file's directory, so
			// a program can be run or compiled from any working directory and still find
			// its companion files (like Common Lisp's *load-pathname*).
			baseDir = inputFile == null ? null : SourceLoader.parentDir(inputFile);
		}

		if (options.contains("-o")) {
			String outputFile = Objects.requireNonNull(options.get("-o"));
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
			compileToFile(source, baseDir, systemPath, dists, outputFile, options.contains("--dynamic"),
					options.contains("--component"), options.contains("--no-wasi"),
					OptimizeLevel.parse(options.get("--optimize")), options.contains("--no-gc"),
					options.contains("--simd"), options.contains("--blas"), options.contains("--gpu"),
					options.contains("--parallel"), options.contains("--no-prune"), options.contains("--no-main"),
					options.contains("--emit-wit"), options.contains("--emit-js-glue"),
					options.contains("--host-random"), options.contains("--host-fetch"),
					options.contains("--reentrant"),
					options.contains("--host-boundary") ? HostBoundary.parse(options.get("--host-boundary")) : null,
					JvmArtifactOptions.from(options), inputFile);
		}
		else {
			// A side-artifact flag names a file to write BESIDE the output, so without
			// one there is nothing it could mean -- and interpreting the program instead
			// is the least useful answer (a Worker source would try to bind a socket).
			// Fail fast, the same way the flags do on the wrong output shape.
			for (String flag : List.of("--emit-wit", "--emit-js-glue")) {
				if (options.contains(flag)) {
					throw new UnsupportedOperationException(
							flag + " writes a file next to a compiled output, so it needs -o <file>");
				}
			}
			if (options.contains("--reentrant")) {
				throw new UnsupportedOperationException(
						"--reentrant is a WASM module contract (overlapped JSPI calls), so it needs -o <file>.wasm");
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
			interpret(source, baseDir, systemPath, dists, options.contains("--simd"), options.contains("--blas"),
					options.contains("--gpu"), options.contains("--parallel"), inputFile,
					commandLine(inputFile, options.arguments()));
		}
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

	private void repl(List<String> systemPath, DistClient dists, boolean simd, boolean blas, boolean gpu,
			boolean parallel, List<String> commandLine) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setSystemPath(systemPath);
		evaluator.setCommandLineArguments(commandLine);
		evaluator.setDistClient(dists);
		requireSimdForParallel(simd, parallel);
		if (simd) {
			enableSimd(evaluator);
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
		StringBuilder buffer = new StringBuilder();
		if (System.console() != null && isJLineAvailable()) {
			JLineRepl.run(evaluator, this.out, buffer);
		}
		else {
			replWithBufferedReader(evaluator, buffer);
		}
	}

	private void replWithBufferedReader(LispEvaluator evaluator, StringBuilder buffer) {
		BufferedReader reader = new BufferedReader(new InputStreamReader(this.in));
		this.out.print("> ");
		this.out.flush();
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				if ("(quit)".equals(line.trim())) {
					break;
				}
				buffer.append(line).append('\n');
				if (ReplBuffer.isBalanced(buffer.toString())) {
					ReplBuffer.eval(evaluator, this.out, buffer);
					this.out.print("> ");
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
	private static void enableSimd(LispEvaluator evaluator) {
		if (VecSimd.available()) {
			evaluator.setSimd(true);
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

	private void interpret(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			boolean simd, boolean blas, boolean gpu, boolean parallel, @Nullable String entryFile,
			List<String> commandLine) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setLoadBaseDir(baseDir);
		evaluator.setSystemPath(systemPath);
		evaluator.setCommandLineArguments(commandLine);
		evaluator.setDistClient(dists);
		requireSimdForParallel(simd, parallel);
		if (simd) {
			enableSimd(evaluator);
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
		// same timing the runtime loadFile uses.
		if (source.contains("#.")) {
			for (LispVal expr : LispReader.readAllWithReadEvalMarkers(source, Features.INTERPRETER, entryFile)) {
				evaluator.eval(evaluator.resolveReadTimeEvalInCode(expr));
			}
			this.out.flush();
			return;
		}
		List<LispVal> exprs = LispReader.readAllFromString(source, Features.INTERPRETER, entryFile);
		for (LispVal expr : exprs) {
			evaluator.eval(expr);
		}
		// A program whose last write is a raw octet (write-byte to standard output) has
		// nothing left to flush it: an auto-flushing PrintStream only drains on a
		// newline, and a byte-oriented filter's output need not end in one.
		this.out.flush();
	}

	private void compileToFile(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			String outputFile, boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean noGc,
			boolean simd, boolean blas, boolean gpu, boolean parallel, boolean noPrune, boolean noMain, boolean wit,
			boolean jsGlue, boolean hostRandom, boolean hostFetch, boolean reentrant,
			@Nullable HostBoundary hostBoundary, JvmArtifactOptions jvmArtifact, @Nullable String entryFile) {
		CompileDiagnostics.recording(() -> {
			compileRecorded(source, baseDir, systemPath, dists, outputFile, dynamic, component, noWasi, optimize, noGc,
					simd, blas, gpu, parallel, noPrune, noMain, wit, jsGlue, hostRandom, hostFetch, reentrant,
					hostBoundary, jvmArtifact, entryFile);
			return null;
		});
	}

	private void compileRecorded(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			String outputFile, boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean noGc,
			boolean simd, boolean blas, boolean gpu, boolean parallel, boolean noPrune, boolean noMain, boolean wit,
			boolean jsGlue, boolean hostRandom, boolean hostFetch, boolean reentrant,
			@Nullable HostBoundary hostBoundary, JvmArtifactOptions jvmArtifact, @Nullable String entryFile) {
		// --emit-wit describes a component's typed world, so it is meaningless for any
		// other
		// output; fail fast instead of silently ignoring the request.
		if (wit && !(component && outputFile.endsWith(".wasm"))) {
			throw new UnsupportedOperationException(
					"--emit-wit requires --component and a .wasm output (e.g. -o out.wasm --component --emit-wit)");
		}
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
		// --emit-js-glue writes the host half of a boundary only a --no-wasi core module
		// has: a component is instantiated through its own bindings (jco), and --no-gc
		// rejects rontolisp:wasm-import outright, so there is no import object to write
		// and `new WebAssembly.Instance(module, {})` is already the whole of its glue.
		if (jsGlue && !(noWasi && !component && !noGc && outputFile.endsWith(".wasm"))) {
			throw new UnsupportedOperationException("--emit-js-glue requires --no-wasi and a .wasm output, without"
					+ " --component (a component's host glue is its bindings generator's) or --no-gc (which imports"
					+ " nothing: `new WebAssembly.Instance(module, {})` is the whole glue)"
					+ " -- e.g. -o out.wasm --no-wasi --emit-js-glue");
		}
		// --host-random routes the wasm-GC backend's random_get slot at a host import,
		// so it means nothing anywhere else. The backend-selection half is checked here
		// (the other two backends never see the flag); the --no-wasi / --component half
		// is the compiler's own guard, next to the contract it protects.
		if (hostRandom && !outputFile.endsWith(".wasm")) {
			throw new UnsupportedOperationException(
					"--host-random requires a .wasm output: it routes the WASM random_get slot at a host import "
							+ "(the interpreter and the JVM backend draw from the JVM's own generator)");
		}
		if (hostRandom && noGc) {
			throw new UnsupportedOperationException("--host-random cannot be combined with --no-gc: the scalar "
					+ "(non-GC) backend has no `random` at all, so there is no draw to route");
		}
		// --host-fetch is the same family: it routes rontolisp:fetch at a host import on
		// the wasm-GC reactor, so it means nothing anywhere else. The --no-wasi /
		// --component half is the compiler's own guard, next to the contract it
		// protects.
		if (hostFetch && !outputFile.endsWith(".wasm")) {
			throw new UnsupportedOperationException(
					"--host-fetch requires a .wasm output: it routes rontolisp:fetch at a host import "
							+ "(the interpreter and the JVM backend fetch through the JDK HttpClient)");
		}
		if (hostFetch && noGc) {
			throw new UnsupportedOperationException("--host-fetch cannot be combined with --no-gc: the scalar "
					+ "(non-GC) backend has no fetch (or strings) at all, so there is nothing to route");
		}
		// --host-boundary chooses between two shapes only the --no-wasi wasm-GC core
		// module HAS. Everywhere else the envelope is not a choice: a reactor component's
		// host functions cross the canonical ABI (no :bytes import to take a body out
		// through), --no-gc has no packed array to carry one in, and a WASI command
		// module's host is `wasmtime run`, which satisfies no env.* import. Refused
		// rather than ignored -- a build script that names a boundary it is not getting
		// is exactly the silence this flag exists to end.
		if (hostBoundary != null && !(noWasi && !component && !noGc && outputFile.endsWith(".wasm"))) {
			throw new UnsupportedOperationException("--host-boundary requires --no-wasi and a .wasm output, without"
					+ " --component (whose host functions cross the canonical ABI, so its bodies are in band"
					+ " already) or --no-gc (which imports nothing at all)"
					+ " -- e.g. -o out.wasm --no-wasi --host-boundary=" + HostBoundary.ENVELOPE.spelling());
		}
		// --reentrant relaxes the wasm-GC reactor's one-call-at-a-time contract, so it
		// means nothing anywhere else; the finer guards (needs a suspending import,
		// no streaming boundary, no --dynamic) live in the compiler, next to the
		// contracts they protect.
		if (reentrant && !(noWasi && !component && !noGc && outputFile.endsWith(".wasm"))) {
			throw new UnsupportedOperationException("--reentrant requires --no-wasi and a .wasm output, without"
					+ " --component (whose concurrency is the component model's) or --no-gc (which has no suspending"
					+ " imports to overlap on) -- e.g. -o out.wasm --no-wasi --host-fetch --reentrant");
		}
		// The compile path's whole front end -- the read, the (load ...) inlining, user
		// macro expansion, the library splice chain, the WIT lowerings, the boundp fold
		// and the library tree-shaker -- in the one place all four backends and the
		// embedded JVM seam (JvmSourceCompiler) share (CompileFrontend).
		CompileFrontend.Result frontend = CompileFrontend.run(source, entryFile, baseDir, systemPath, dists,
				outputFile.endsWith(".wasm"), outputFile.endsWith(".war"), dynamic, component, noWasi, noGc, hostFetch,
				hostBoundary, reentrant, noPrune);
		List<LispVal> program = frontend.program();
		Features features = frontend.features();
		boolean serve = frontend.serve();
		boolean witWorld = frontend.witWorld();
		byte[] bytes;
		Map<String, byte[]> jvmRuntimeClasses = Map.of();
		// The internal name of the class a JVM compile emitted, needed again below to
		// place it inside a jar or to root the runtime classes beside a .class.
		String jvmClassName = null;
		String witText = null;
		String glueText = null;
		String glueFile = jsGlue ? outputFile.substring(0, outputFile.length() - ".wasm".length()) + ".js" : null;
		if (outputFile.endsWith(".wasm")) {
			if (noGc) {
				// --no-gc selects the separate scalar (non-GC) lowering: a plain MVP
				// module
				// with no wasm-GC types, no imports and no memory, for pure-numeric
				// rontolisp:wasm-export functions. A PRINT-FREE program is a pure-compute
				// reactor already; a printing one imports the single fd_write, which
				// --no-wasi replaces with the discarding sink (the GC contract), so the
				// module keeps zero imports. With --component the same core module is
				// wrapped as a compact reactor-style component (typed scalar exports, no
				// adapter, no wasm-GC requirement) instead of the GC component pipeline.
				// --simd is the orthogonal acceleration switch: with it the vec: kernels
				// lower to native v128 (f64x2/f32x4); without it to plain scalar loops
				// that
				// run on a runtime lacking the SIMD proposal.
				NoGcWasmCompiler compiler = new NoGcWasmCompiler(optimize, simd, component, noWasi);
				bytes = compiler.compile(program);
				witText = compiler.componentWit();
			}
			else {
				// rontolisp:http-handler compiles to a wasi:http/incoming-handler
				// component (--component only). The HttpHandlerInliner splices in a
				// %http-dispatch wasm-export wrapper that the serve adapter calls per
				// request.
				// `serve` was computed above (http-handler is a special form the library
				// splices leave untouched, so its presence is unchanged here).
				// A serve-mode component's only export is wasi:http/incoming-handler (the
				// component builder lifts no user exports there), so a world of function
				// exports could not be honored -- say so instead of dropping it.
				if (serve && witWorld) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-export cannot be combined with rontolisp:http-handler: a serve-mode "
									+ "component exports only wasi:http/incoming-handler");
				}
				// Serve (plain or serve+fetch) already carries http.lisp's %serve-handle
				// wasm-export, spliced by HttpLibrary above; the http-handler directive
				// was
				// removed there. Nothing more to synthesize -- the WAT serve adapter is
				// gone.
				// --simd routes the vectorizable vec: kernels to emitted v128 helpers and
				// switches a packed float array's storage to an (array (mut v128)) of
				// lane
				// groups -- still a GC object the engine collects, so memory behaves as
				// it
				// does without the flag.
				// --host-random: the one opt-in out of "instantiate with nothing". The
				// module then imports env.random_get(buf, len) -> errno and every
				// `random` draw is the host's entropy, which is also what makes
				// rontolisp:random-bytes sound again.
				// runtimeFeatures: the compiled program's *features* starts out holding
				// exactly what the frontend READ it with -- component / reactor /
				// body-imports included -- so a run-time (member :F *features*) and the
				// #+F beside it cannot disagree.
				WasmLispCompiler compiler = new WasmLispCompiler(dynamic, component, noWasi, optimize, serve, simd,
						hostRandom, hostFetch, reentrant)
					.runtimeFeatures(features.names());
				bytes = compiler.compile(program);
				witText = compiler.componentWit();
				if (glueFile != null) {
					glueText = compiler.hostGlueJs(Path.of(glueFile).getFileName().toString());
				}
			}
		}
		else {
			// The class name is the -o path with .class taken off, or --class-name where
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
				.compileProgram(program, features);
			jvmClassName = compiled.internalClassName();
			bytes = compiled.classBytes();
			jvmRuntimeClasses = compiled.runtimeClasses();
		}
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
				// consumer, not an error here), and the coordinates when given.
				Files.write(outputPath, JvmJarWriter.jar(Objects.requireNonNull(jvmClassName), bytes, jvmRuntimeClasses,
						!noMain, jvmArtifact.coordinates(), simd));
				if (jvmArtifact.emitPom()) {
					writePom(outputFile, Objects.requireNonNull(jvmArtifact.coordinates()), simd);
				}
			}
			else if (outputFile.endsWith(".war")) {
				// A war is the same bytecode (compiled in servlet mode) inside
				// WEB-INF/classes, plus the one-line service declaration that lets any
				// Servlet 6 container discover the program itself -- no web.xml, no
				// configuration (JvmWarWriter).
				Files.write(outputPath, JvmWarWriter.war(Objects.requireNonNull(jvmClassName), bytes, jvmRuntimeClasses,
						jvmArtifact.coordinates(), simd));
				if (jvmArtifact.emitPom()) {
					writePom(outputFile, Objects.requireNonNull(jvmArtifact.coordinates()), simd);
				}
			}
			else {
				Files.write(outputPath, bytes);
				if (!jvmRuntimeClasses.isEmpty()) {
					// Rooted where the output class's own package root is -- the -o path
					// IS the package, so `-o com/acme/Kernels.class` writes them under
					// the same tree and `javac -cp .` / `jar cf` pick them up with no
					// arrangement.
					String root = JvmArtifactOptions.classRoot(outputFile, Objects.requireNonNull(jvmClassName));
					for (Map.Entry<String, byte[]> runtimeClass : jvmRuntimeClasses.entrySet()) {
						Path runtimePath = Path.of(root + runtimeClass.getKey());
						Files.createDirectories(Objects.requireNonNull(runtimePath.getParent()));
						Files.write(runtimePath, runtimeClass.getValue());
					}
				}
			}
			if (wit) {
				String witFile = outputFile.substring(0, outputFile.length() - ".wasm".length()) + ".wit";
				Files.writeString(Path.of(witFile), Objects.requireNonNull(witText));
			}
			if (glueFile != null) {
				// The glue's name is the module's, so in a Worker directory `-o
				// src/index.wasm` aims it straight at a hand-written src/index.js. Only
				// a file this flag wrote before is overwritten; anything else is the
				// host's own and is refused by name, because the build has already
				// committed the .wasm by the time we get here.
				Path glue = Path.of(glueFile);
				if (Files.exists(glue)
						&& !Files.readString(glue, StandardCharsets.UTF_8).startsWith(HostGlueEmitter.MARKER)) {
					throw new UnsupportedOperationException("--emit-js-glue would overwrite " + glueFile
							+ ", which it did not write (it does not start with \"" + HostGlueEmitter.MARKER
							+ "\"). Compile to a different -o name, or move that file aside");
				}
				Files.writeString(glue, Objects.requireNonNull(glueText));
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * {@code --scaffold-wit world.wit [-o impl.lisp] [--world name]}: writes a runnable
	 * skeleton implementing the world (a {@code rontolisp:wit-export} directive plus one
	 * {@code defun} stub per export), or prints it to stdout when no {@code -o} is given.
	 */
	private void scaffold(String witFile, @Nullable String outputFile, @Nullable String world) {
		String witSource = readFile(witFile);
		// The path the generated source names must resolve against the generated file's
		// own directory, the way wit-export (like load) resolves it.
		String directivePath = outputFile == null ? witFile
				: relativeToOutput(SourceLoader.parentDir(outputFile), witFile);
		String lisp = WitScaffolder.scaffold(witSource, directivePath, world);
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
	private static void writePom(String outputFile, MavenCoordinates coordinates, boolean simd) throws IOException {
		// .jar and .war are the same length, so one cut serves both archive outputs.
		Path pom = Path.of(outputFile.substring(0, outputFile.length() - ".jar".length()) + ".pom");
		if (Files.exists(pom)
				&& !Files.readString(pom, StandardCharsets.UTF_8).startsWith(MavenCoordinates.POM_MARKER)) {
			throw new UnsupportedOperationException("--emit-pom would overwrite " + pom
					+ ", which it did not write (it does not start with \"" + MavenCoordinates.POM_MARKER
					+ "\"). Compile to a different -o name, or move that file aside");
		}
		Files.writeString(pom, coordinates.pomXml(simd));
	}

	// Emits a one-line warning to stderr. Kept off stdout (this.out) so it never corrupts
	// a
	// compiled program's piped output or the REPL transcript.
	private static void warn(String message) {
		System.err.println("rontolisp: warning: " + message);
	}

	private void printUsage() {
		this.out.println("Usage: rontolisp [options] [file]");
		this.out.println("  (no args)          REPL mode");
		this.out.println("  file               Interpret the file");
		this.out.println("  -e \"FORMS\"         Interpret the given program instead of a file (--eval)");
		this.out.println("  file -o out.class   Compile to JVM bytecode");
		this.out.println("  file -o out.jar     Compile to an executable jar (java -jar out.jar)");
		this.out.println("  file -o app.war     Compile an http-handler program to a Servlet war that");
		this.out.println("                     deploys unmodified on any Servlet 6 container (Tomcat,");
		this.out.println("                     Jetty, ...): no web.xml, no configuration; the container");
		this.out.println("                     owns the port, so a written port is ignored");
		this.out.println("  file -o out.wasm    Compile to WASM");
		this.out.println("  file -- ARG...     Interpret the file with ARG... as the PROGRAM's own");
		this.out.println("                     arguments: everything after -- is (uiop:command-line-");
		this.out.println("                     arguments), never a rontolisp option. A compiled");
		this.out.println("                     artifact takes them from its own host instead");
		this.out.println("                     (java Out a b, wasmtime run out.wasm a b)");
		this.out.println();
		this.out.println("Subcommands:");
		this.out.println("  format PATH...     Re-indent Lisp source files in place (a");
		this.out.println("                     directory is walked for .lisp and .asd files).");
		this.out.println("                     See: rontolisp format --help");
		this.out.println("  test TARGET        Run a rove test file, .asd or system and EXIT with");
		this.out.println("                     its verdict: 0 all passed, 1 a test failed or none");
		this.out.println("                     ran. With -o it compiles the run instead, and the");
		this.out.println("                     artifact carries the same exit contract.");
		this.out.println("                     See: rontolisp test --help");
		this.out.println();
		this.out.println("Options:");
		this.out.println("  -h, --help         Show this help message");
		this.out.println("  -v, --version      Show version");
		this.out.println("  -e, --eval FORMS   Take the program from this argument, not from a file");
		this.out.println("                     Repeatable: every -e appends another top-level form, so");
		this.out.println("                     -e '(defun f () 1)' -e '(print (f))' is one program.");
		this.out.println("                     Combines with -o (compiles the inline program) and with");
		this.out.println("                     every flag below; it cannot be given beside an input file.");
		this.out.println("                     A relative (load \"...\") resolves against the working");
		this.out.println("                     directory, there being no file to resolve against");
		this.out.println("  --dynamic          Resolve unknown calls/vars at runtime (late binding)");
		this.out.println("                     Lets sources that define functions via load compile as-is");
		this.out.println("  --component        Emit a WASI 0.3 component (run with: wasmtime run)");
		this.out.println("                     WASM only; print/stdin/file I/O work. Pure-compute");
		this.out.println("                     rontolisp:wasm-export functions (incl. :string/:s-expr)");
		this.out.println("                     become typed component exports, callable via");
		this.out.println("                     wasmtime run --invoke 'name(args)'");
		this.out.println("                     With --no-gc: a compact reactor component (typed exports");
		this.out.println("                     incl. :long and :string; print works via a tiny WASI 0.2");
		this.out.println("                     stdio bridge; no wasm-GC, no flags)");
		this.out.println("  --emit-wit         With --component: also write the component's WIT world");
		this.out.println("                     (imports + typed exports) next to the .wasm output, so hosts");
		this.out.println("                     and binding generators (e.g. jco) need no wasm-tools introspection");
		this.out.println("                     It is the only thing that reports what the component actually");
		this.out.println("                     IMPORTS -- a hand-written world states only the export side");
		this.out.println("  --emit-js-glue     With --no-wasi: also write the JavaScript host glue next to");
		this.out.println("                     the .wasm output (out.wasm -> out.js), derived from the");
		this.out.println("                     program's own wasm-import/wasm-export declarations: the");
		this.out.println("                     import object, the (ptr, len) staging and __ronto_alloc");
		this.out.println("                     bracket, the WebAssembly.Suspending / promising entries a");
		this.out.println("                     JSPI host needs, and the one-call-at-a-time queue. A host");
		this.out.println("                     then supplies only what a declaration cannot say: one plain");
		this.out.println("                     function per import");
		this.out.println("  --scaffold-wit W   Generate a skeleton implementing the WIT world W instead of");
		this.out.println("                     compiling: a rontolisp:wit-export directive plus one defun stub");
		this.out.println("                     per export (with -o FILE; prints to stdout otherwise). Pick the");
		this.out.println("                     world with --world NAME when the file declares several.");
		this.out.println("                     The program then IMPLEMENTS the .wit: the compiler checks every");
		this.out.println("                     defun against it, so no :params/:returns list is written by hand");
		this.out.println("  --no-main          With a .class or .jar output: compile a LIBRARY class");
		this.out.println("                     instead of a command -- no main method (and no Main-Class in");
		this.out.println("                     a jar), and the class is entered through its");
		this.out.println("                     rontolisp:jvm-export typed methods (at least one is required;");
		this.out.println("                     main is the only tree-shaker root otherwise). The top level");
		this.out.println("                     runs once, at class initialization, exactly as a --no-wasi");
		this.out.println("                     reactor runs its top level at instantiation");
		this.out.println("  --class-name NAME  With a .class, .jar or .war output: the fully qualified name of");
		this.out.println("                     the emitted class (com.example.Kernels). REQUIRED for a");
		this.out.println("                     --no-main library jar, whose class IS its Java API; a program");
		this.out.println("                     jar derives one from the -o file name (app.jar -> App) and is");
		this.out.println("                     entered through the manifest. For a .class it replaces the");
		this.out.println("                     name the -o path would give, so -o build/K.class can still be");
		this.out.println("                     com.example.Kernels");
		this.out.println("  --maven-coordinates G:A:V");
		this.out.println("                     With a .jar or .war output: embed META-INF/maven/G/A/pom.xml and");
		this.out.println("                     pom.properties, so the coordinates travel INSIDE the jar and");
		this.out.println("                     `mvn install:install-file -Dfile=out.jar` needs no -DgroupId,");
		this.out.println("                     -DartifactId, -Dversion or -DpomFile. The generated pom has an");
		this.out.println("                     empty <dependencies>, which is the truth: a compiled class");
		this.out.println("                     embeds everything it calls");
		this.out.println("  --emit-pom         Write that same pom next to the jar as out.pom (for");
		this.out.println("                     deploy-file), the way --emit-wit writes the world next to the");
		this.out.println("                     .wasm. Needs --maven-coordinates");
		this.out.println("  --no-wasi          Emit WASM with no WASI imports (reactor mode)");
		this.out.println("                     Instantiates without an import object (beyond any");
		this.out.println("                     rontolisp:wasm-import host functions); pure-compute");
		this.out.println("                     rontolisp:wasm-export functions work, print is discarded,");
		this.out.println("                     other I/O traps. The core module also exports two host hooks,");
		this.out.println("                     __ronto_seed_random and __ronto_set_time (nanos since the Unix");
		this.out.println("                     epoch), for the two services it cannot answer alone -- call them");
		this.out.println("                     before _initialize. With --component: a reactor component that");
		this.out.println("                     imports NOTHING and runs its top level at instantiation");
		this.out.println("  --host-random      With --no-wasi (core module only): draw `random` from the HOST");
		this.out.println("                     instead of the built-in generator. The module then imports");
		this.out.println("                     exactly one function, env.random_get(buf, len) -> errno (the");
		this.out.println("                     preview1 signature), so a JS host adds one line to its import");
		this.out.println("                     object; rontolisp:random-bytes works, and no");
		this.out.println("                     __ronto_seed_random export is emitted (nothing left to seed)");
		this.out.println("  --host-fetch       With --no-wasi (core module only): route rontolisp:fetch at the");
		this.out.println("                     HOST's own HTTP client. The module then imports");
		this.out.println("                     env.fetch(request-json) -> response-json (strings via the");
		this.out.println("                     wasm-import ABI) -- plus, on the streaming boundary below,");
		this.out.println("                     env.readResponseBody(ptr, cap) -> i32 for the reply's body (a");
		this.out.println("                     chunk per call into a buffer the module passes; 0 ends it), and");
		this.out.println("                     fetch answers the same (:status :headers :body) plist as every");
		this.out.println("                     other backend, :body the same asynchronous stream. A JS host");
		this.out.println("                     implements them with fetch() behind WebAssembly.Suspending");
		this.out.println("                     (JSPI) and enters exports via promising, or answers");
		this.out.println("                     synchronously; the build prints the exact obligation");
		this.out.println("  --host-boundary=B  With --no-wasi (core module only): where an HTTP body crosses.");
		this.out.println("                     Both shapes speak the same JSON envelope; B says whether a body");
		this.out.println("                     rides inside it or streams beside it");
		this.out.println("                       envelope   (default) every body rides the envelope's own");
		this.out.println("                                  \"body\" key. No body imports and no host-side");
		this.out.println("                                  cursor: a Worker that reads one document and");
		this.out.println("                                  answers one imports at most env.fetch");
		this.out.println("                       streaming  the request and response bodies leave the envelope");
		this.out.println("                                  through env.readRequestBody / env.writeResponseBody");
		this.out.println("                                  -- and, with --host-fetch, env.readResponseBody.");
		this.out.println("                                  Ask for it when a body is BINARY (the envelope");
		this.out.println("                                  carries one as JSON text, so ff fe 41 arrives as");
		this.out.println("                                  seven bytes), LARGE (the envelope costs linear");
		this.out.println("                                  memory proportional to it) or RELAYED from an");
		this.out.println("                                  upstream reply you would forward a chunk at a time");
		this.out.println("                     Either way --emit-js-glue writes the whole host half, so the");
		this.out.println("                     JavaScript is three lines on both");
		this.out.println("  --reentrant        With --no-wasi (core module only, and a suspending import: an");
		this.out.println("                     :async t wasm-import or --host-fetch with fetch used): let a JSPI");
		this.out.println("                     host OVERLAP calls into ONE instance instead of serialising them.");
		this.out.println("                     The module then owns its per-call state -- dynamic (special)");
		this.out.println("                     bindings move into a per-call task record, and cross-call staging");
		this.out.println("                     into __ronto_park_alloc/__ronto_park_free blocks (a :string result");
		this.out.println("                     is one the reader frees; --emit-js-glue writes all of it). Buys");
		this.out.println("                     I/O overlap, never CPU parallelism: one stack still runs at a");
		this.out.println("                     time. Composes with --host-boundary=streaming: the body imports");
		this.out.println("                     then lead with an :int call id (the envelope's \"call-id\" key,");
		this.out.println("                     the fetch reply's \"body-id\"), so each pull names its call.");
		this.out.println("                     Not combinable with --dynamic or --component");
		this.out.println("  --optimize[=LEVEL] Dead-code-eliminate the compiled output -- ON BY DEFAULT.");
		this.out.println("                     Pass nothing and you get --optimize=default; the way to");
		this.out.println("                     decline it is --optimize=off, not the absence of the flag");
		this.out.println("                     WASM: drop functions unreachable from the exports/_start, in");
		this.out.println("                     --component mode too; great with --no-wasi");
		this.out.println("                     JVM: drop methods unreachable from main + compact the constant pool");
		this.out.println("                     A function the program never takes as a value gets no funcall");
		this.out.println("                     dispatch case, so library code goes too -- unless the program");
		this.out.println("                     can name a function out of data (eval/read/load/~/name/), which");
		this.out.println("                     keeps everything. -Drontolisp.debug.dispatchgate=true says which");
		this.out.println("                     LEVEL says what to optimize FOR (no flag = bare flag = default)");
		this.out.println("                       off      drop nothing: what the backends emit on their own,");
		this.out.println("                                which is what a flagless build used to produce --");
		this.out.println("                                and ~300x the bytes of the default on a hello world");
		this.out.println("                       default  the elimination described above, nothing traded for it");
		this.out.println("                       size     default, plus: give up speed for size. On wasm-GC");
		this.out.println("                                that drops the two emissions that spend bytes on");
		this.out.println("                                speed (fused integer trees, unboxed locals): about");
		this.out.println("                                -20% of any module, but only integer arithmetic");
		this.out.println("                                fuses, so the price runs from ~+10% on a float");
		this.out.println("                                kernel to ~4x on integer crypto.");
		this.out.println("                                Accepted and identical to default on JVM and --no-gc");
		this.out.println("  --no-gc            Emit a plain (non-wasm-GC) WASM module for pure-numeric exports");
		this.out.println("                     Runs on any MVP runtime (no -W gc, no import object). Only");
		this.out.println("                     scalar rontolisp:wasm-export functions (:int/:float/:bool) work;");
		this.out.println("                     ineligible (cons/string/I/O/...) functions are a compile error.");
		this.out.println("                     Scalar vec: loops by default; add --simd for native v128.");
		this.out.println("                     Packed vec: arrays are bump-allocated and never freed; reclaim");
		this.out.println("                     with rontolisp:with-arena or use the -into kernels in hot loops.");
		this.out.println("                     Add --component for a compact typed component-model wrap.");
		this.out.println("  --simd             Accelerate the vec: and linalg: kernels with hardware SIMD");
		this.out.println("                     JVM (.class): route to the jdk.incubator.vector bridge (run with");
		this.out.println("                     java --add-modules jdk.incubator.vector). A runtime without the");
		this.out.println("                     module runs the same class unaccelerated instead of failing --");
		this.out.println("                     the consumer need not have compiled it -- warning once on stderr.");
		this.out.println("                     WASM (.wasm, both wasm-GC and --no-gc): emit native v128");
		this.out.println("                     (f64x2/f32x4). On wasm-GC packed float arrays stay GC-managed");
		this.out.println("                     (v128 lane groups), so memory behaves as without --simd.");
		this.out.println("                     Interpreter/REPL: run the vec: kernels on the Vector API (baked");
		this.out.println("                     into the native binary; on java -jar add --add-modules");
		this.out.println("                     jdk.incubator.vector, else it falls back to scalar).");
		this.out.println("  --parallel         With --simd: split the matrix products across CPU cores");
		this.out.println("                     vec:matvec / vec:matvec-into, linalg:dot over a matrix and");
		this.out.println("                     linalg:matmul (the stacked product included) run a row range");
		this.out.println("                     per thread once a call is big enough (~2^15 multiply-adds);");
		this.out.println("                     every other kernel, every reduction included, stays on the");
		this.out.println("                     calling thread. The rows are independent chains, so the");
		this.out.println("                     results are bit-identical to --simd alone. RONTOLISP_THREADS");
		this.out.println("                     sets the thread count (the caller included; default: the");
		this.out.println("                     available processors; 1 = serial); the workers are daemon");
		this.out.println("                     threads that spin ~1 ms between calls before sleeping, so a");
		this.out.println("                     loop of products keeps them busy. Interpreter (incl. the");
		this.out.println("                     native binary) and JVM (.class) only -- WASM has no threads.");
		this.out.println("                     Needs --simd: without it there is nothing to split.");
		this.out.println("  --blas             Route the linalg: matrix product to a tuned CBLAS from the OS");
		this.out.println("                     Interpreter (incl. the native binary) and JVM (.class) only --");
		this.out.println("                     WASM has no foreign function interface. macOS finds Accelerate");
		this.out.println("                     with no setup; on Linux install one (e.g. libopenblas0-pthread).");
		this.out.println("                     A machine with none runs the same programs, unaccelerated. The");
		this.out.println("                     library reorders its reduction, so results are close to but not");
		this.out.println("                     bit-identical to the other backends. RONTOLISP_BLAS names a");
		this.out.println("                     library outright; RONTOLISP_BLAS_VERBOSE=1 prints what was bound.");
		this.out.println("  --gpu              Route the linalg: matrix product and the transcendentals to");
		this.out.println("                     an NVIDIA GPU, or to Apple Silicon through Metal");
		this.out.println("                     Both product shapes: the rank-2 one and the stacked rank >= 3");
		this.out.println("                     one (torch.bmm), which is a transformer's whole hot path; plus");
		this.out.println("                     the element-wise exp/log/tanh/sin/cos/tan/asin/acos/atan/");
		this.out.println("                     sinh/cosh/erf. sqrt/abs/negative/sign and add/sub/mul/div stay");
		this.out.println("                     on the CPU: they are one instruction per element and a round");
		this.out.println("                     trip cannot pay for them. And vec:matvec, the GEMV a decode");
		this.out.println("                     loop is made of, over a matrix that stays on the device.");
		this.out.println("                     Interpreter (incl. the native binary) and JVM (.class) only --");
		this.out.println("                     the device is reached through the foreign function API, which");
		this.out.println("                     WASM does not have. A compiled .class carries the whole");
		this.out.println("                     binding for BOTH platforms and still runs with a plain");
		this.out.println("                     `java Prog` (add --enable-native-access=ALL-UNNAMED to silence");
		this.out.println("                     the JVM warning).");
		this.out.println("                     Needs libcuda.so.1 (the NVIDIA driver) and nothing else, or on");
		this.out.println("                     a Mac nothing at all: no CUDA toolkit, no Xcode. A machine");
		this.out.println("                     without a device runs the same programs, unaccelerated.");
		this.out.println("                     ON APPLE SILICON THE FLAG IS SINGLE-FLOAT ONLY: Metal has no");
		this.out.println("                     double, so a #d array always stays on the CPU -- use #f arrays");
		this.out.println("                     (torch: already defaults to them). Its floor is five times");
		this.out.println("                     CUDA's, so it accepts from ~166x166x166 rather than ~51 cubed,");
		this.out.println("                     from 131072 element-wise rather than 16384, and it declines");
		this.out.println("                     the axis folds outright; the CUDA thresholds and member set");
		this.out.println("                     are unchanged. For a rank >= 3 stack the product bound is on");
		this.out.println("                     the TOTAL work, since the whole stack is one round trip.");
		this.out.println("                     THE ONLY FLAG WHOSE RESULTS DO NOT MATCH THE OTHER BACKENDS");
		this.out.println("                     ELEMENTWISE: the product fuses each multiply-add and the device");
		this.out.println("                     has its own libm, so an accelerated exp/erf/... differs in the");
		this.out.println("                     last few digits (and more at single float).");
		this.out.println("  --no-prune         Keep every spliced library function in the compiled output");
		this.out.println("                     By default unreachable library definitions (linalg:/vec:/...)");
		this.out.println("                     are dropped at compile time; names forged at runtime from");
		this.out.println("                     computed strings need this flag (or --dynamic) to resolve.");
		this.out.println("  --buffered-output  Block-buffer stdout (avoids interleaving when piped)");
		this.out.println("                     Off by default so the REPL responds to each line");
		this.out.println("  --system-path DIRS Directories searched for NAME.asd by asdf:load-system");
		this.out.println("                     (joined with the platform path separator, like PATH; the");
		this.out.println("                     RONTOLISP_SOURCE_REGISTRY environment variable adds more)");
		this.out.println("                     ql:quickload downloads systems into ~/.rontolisp/quicklisp");
		this.out.println("                     (override with the RONTOLISP_QUICKLISP_HOME env variable)");
		this.out.println("  --dist DISTS       Quicklisp-format distributions ql:quickload may download from,");
		this.out.println("                     beside quicklisp: a name (ultralisp) or a distinfo URL, several");
		this.out.println("                     comma-separated (the RONTOLISP_DISTS env variable adds more).");
		this.out.println("                     Searched in the order given, quicklisp first unless named;");
		this.out.println("                     each caches under ~/.rontolisp/<dist> (RONTOLISP_DIST_HOME).");
		this.out.println("                     A program can install one itself: (ql-dist:install-dist NAME)");
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
		// In the native binary on macOS, main runs on the process's FIRST thread -- the
		// one AppKit demands for every window and the one nothing drains. So the binary
		// does what the java launcher does for a jar: the CLI moves to a second thread
		// and thread 0 parks in the run loop, which pumps AppKit and the main dispatch
		// queue the objc: package hops through. Unconditional, because thread 0 cannot
		// be handed over later; on a JVM, on Linux and in the browser it answers false
		// and nothing changes (.kb/objc.md).
		if (ObjcInterop.mainThreadHandOverRequired()) {
			Thread worker = new Thread(null, () -> {
				int code;
				try {
					code = launch(args);
				}
				catch (Throwable ex) {
					ex.printStackTrace();
					code = 1;
				}
				// Thread 0 never returns from the run loop, so the exit code has to
				// leave through System.exit whatever its value.
				System.exit(code);
			}, "main", WORKER_STACK_BYTES);
			worker.start();
			ObjcInterop.parkMainThread();
			return;
		}
		exit(launch(args));
	}

	/**
	 * The stack of the thread the CLI runs on when thread 0 is handed over: at least what
	 * the OS gives the first thread (8 MiB), since the interpreter's recursion depth is
	 * the program's.
	 */
	private static final long WORKER_STACK_BYTES = 16L << 20;

	/** The whole command line, answering the exit code. */
	private static int launch(String[] args) {
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
			return runReporting(cli, args);
		}
		PrintStream bufferedOut = new PrintStream(
				new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
				StandardCharsets.UTF_8);
		System.setOut(bufferedOut);
		Runtime.getRuntime().addShutdownHook(new Thread(bufferedOut::flush));
		RontoLispCli cli = new RontoLispCli(System.in, bufferedOut);
		try {
			return runReporting(cli, args);
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
	}

	/**
	 * The one line a failure prints. A condition the program signaled and nobody caught
	 * gets the cross-backend {@link UncaughtReport} wording -- the same line the JVM and
	 * wasm backends print for the same condition; anything else (a read error, a compile
	 * failure, a bad command line) is a rontolisp diagnostic and says {@code error:},
	 * keeping whatever {@code file:line:column:} prefix the frontend already put on it.
	 */
	private static String failureLine(RuntimeException ex) {
		String message = ex.getMessage();
		if (message == null || message.isEmpty()) {
			message = ex.getClass().getName();
		}
		return ex instanceof LispEvalException ? UncaughtReport.line(message) : "error: " + message;
	}

	// A non-zero exit code has to go through System.exit -- returning from main is always
	// 0.
	// Zero returns normally instead, so an embedded caller (tests, the native binary's
	// own
	// smoke checks) is not killed by a successful run.
	private static void exit(int code) {
		if (code != 0) {
			System.exit(code);
		}
	}

}
