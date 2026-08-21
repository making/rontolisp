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
import java.util.Objects;

import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.Version;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.CompileTimeBoundp;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.UncaughtReport;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.EnvironmentLibrary;
import am.ik.rontolisp.eval.ExitLibrary;
import am.ik.rontolisp.eval.GrayStreamsLibrary;
import am.ik.rontolisp.eval.HostFetchLibrary;
import am.ik.rontolisp.eval.HttpLibrary;
import am.ik.rontolisp.eval.HttpReactorInliner;
import am.ik.rontolisp.eval.HttpReactorLibrary;
import am.ik.rontolisp.eval.HttpServerLibrary;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.JsonLibrary;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.LinalgBlas;
import am.ik.rontolisp.eval.LinalgGpu;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.TorchLibrary;
import am.ik.rontolisp.eval.LispEvalException;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.eval.VecSimd;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.UnreadCharLibrary;
import am.ik.rontolisp.eval.UrlLibrary;
import am.ik.rontolisp.eval.UsocketLibrary;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.eval.SocketsLibrary;
import am.ik.rontolisp.eval.StdinLibrary;
import am.ik.rontolisp.eval.TlsLibrary;
import am.ik.rontolisp.eval.WaitForLibrary;
import am.ik.rontolisp.eval.WitExportInliner;
import am.ik.rontolisp.eval.WitImportInliner;
import am.ik.rontolisp.eval.WitLibrary;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
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
			repl(systemPath, dists, options.contains("--simd"), options.contains("--blas"), options.contains("--gpu"));
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
					options.contains("--no-prune"), options.contains("--emit-wit"), options.contains("--emit-js-glue"),
					options.contains("--host-random"), options.contains("--host-fetch"),
					options.contains("--reentrant"),
					options.contains("--host-boundary") ? HostBoundary.parse(options.get("--host-boundary")) : null,
					inputFile);
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
			interpret(source, baseDir, systemPath, dists, options.contains("--simd"), options.contains("--blas"),
					options.contains("--gpu"), inputFile);
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

	private void repl(List<String> systemPath, DistClient dists, boolean simd, boolean blas, boolean gpu) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setSystemPath(systemPath);
		evaluator.setDistClient(dists);
		if (simd) {
			enableSimd(evaluator);
		}
		if (blas) {
			enableBlas(evaluator);
		}
		if (gpu) {
			enableGpu(evaluator);
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
				if (isBalanced(buffer.toString())) {
					evalBuffer(evaluator, this.out, buffer);
					this.out.print("> ");
					this.out.flush();
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static void evalBuffer(LispEvaluator evaluator, PrintStream out, StringBuilder buffer) {
		try {
			// #. read-time eval at the REPL: only a buffer textually containing #. pays
			// for the marker read; each form's markers resolve just before it runs, the
			// same timing interpret/loadFile use.
			String source = buffer.toString();
			boolean markers = source.contains("#.");
			List<LispVal> exprs = markers ? LispReader.readAllWithReadEvalMarkers(source, Features.INTERPRETER)
					: LispReader.readAllFromString(source);
			// EVERY form in the buffer is echoed, right after it runs, and as a
			// multiple-value consumer would see it: one value per line, as in any CL
			// REPL ((floor 10 3) echoes 3 then 1; (values) echoes nothing). A form's
			// own output therefore precedes its own value, and two forms typed on one
			// line echo twice -- what SBCL does reading them one at a time.
			for (LispVal expr : exprs) {
				List<LispVal> values = evaluator.evalValues(markers ? evaluator.resolveReadTimeEvalInCode(expr) : expr);
				freshLine(evaluator);
				for (LispVal value : values) {
					out.println(value.print());
				}
			}
		}
		catch (RuntimeException ex) {
			freshLine(evaluator);
			out.println("Error: " + ex.getMessage());
		}
		buffer.setLength(0);
	}

	// The echoed result starts on its own line even when the evaluated form left
	// standard output mid-line (e.g. a print-family call without a trailing newline).
	private static void freshLine(LispEvaluator evaluator) {
		try {
			evaluator.eval(LispReader.readAllFromString("(fresh-line)").get(0));
		}
		catch (RuntimeException ignored) {
			// Echo the result anyway; fresh-line is cosmetic.
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

	// --gpu routes the linalg: matrix product to an NVIDIA GPU. Like --blas the answer is
	// a property of the machine rather than of the build, so a decline is an ordinary
	// outcome; unlike --blas the probe itself costs something (a dlopen, a cuInit, a
	// retained primary context and a PTX JIT), which is why nothing asks unless the flag
	// was given (.kb/gpu.md).
	private static void enableGpu(LispEvaluator evaluator) {
		if (LinalgGpu.available()) {
			evaluator.setGpu(true);
		}
		else {
			warn("--gpu: " + LinalgGpu.description() + "; running the linalg: matrix product unaccelerated.");
		}
	}

	private void interpret(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			boolean simd, boolean blas, boolean gpu, @Nullable String entryFile) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setLoadBaseDir(baseDir);
		evaluator.setSystemPath(systemPath);
		evaluator.setDistClient(dists);
		if (simd) {
			enableSimd(evaluator);
		}
		if (blas) {
			enableBlas(evaluator);
		}
		if (gpu) {
			enableGpu(evaluator);
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
			boolean simd, boolean blas, boolean gpu, boolean noPrune, boolean wit, boolean jsGlue, boolean hostRandom,
			boolean hostFetch, boolean reentrant, @Nullable HostBoundary hostBoundary, @Nullable String entryFile) {
		// The frontend records where every cons was read from, so a pass that fails long
		// after the read -- a macro body that signals, an operator no backend knows, a
		// malformed binding list a walker casts and fails on -- can still name
		// file:line:column instead of leaving the user a bare message about a program
		// that may be a hundred spliced files (.todo/151 phase 2). Compile path only:
		// see SourceProvenance for why the interpreter deliberately does not record.
		SourceProvenance.startRecording();
		try {
			compileRecorded(source, baseDir, systemPath, dists, outputFile, dynamic, component, noWasi, optimize, noGc,
					simd, blas, gpu, noPrune, wit, jsGlue, hostRandom, hostFetch, reentrant, hostBoundary, entryFile);
		}
		catch (RuntimeException ex) {
			throw locateCompileFailure(ex);
		}
		finally {
			SourceProvenance.stopRecording();
		}
	}

	/**
	 * Re-reports a frontend failure at the position of the form that failed. The original
	 * exception becomes the cause, so nothing about it is lost; a read error is left
	 * alone because {@link LispReadException} already carries its own prefix, and so is a
	 * failure whose position is unknown (nothing was recorded, or the failing form was
	 * entirely macro-generated) -- prefixing nothing would only add noise.
	 */
	private static RuntimeException locateCompileFailure(RuntimeException ex) {
		if (ex instanceof LispReadException) {
			return ex;
		}
		SourceLocation location = SourceProvenance.failureLocation(ex);
		String prefix = location == null ? "" : location.prefix();
		if (prefix.isEmpty()) {
			return ex;
		}
		return new LispCompileException(prefix + ex.getMessage(), ex);
	}

	private void compileRecorded(String source, @Nullable String baseDir, List<String> systemPath, DistClient dists,
			String outputFile, boolean dynamic, boolean component, boolean noWasi, OptimizeLevel optimize, boolean noGc,
			boolean simd, boolean blas, boolean gpu, boolean noPrune, boolean wit, boolean jsGlue, boolean hostRandom,
			boolean hostFetch, boolean reentrant, @Nullable HostBoundary hostBoundary, @Nullable String entryFile) {
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
		if (blas && !outputFile.endsWith(".class")) {
			throw new UnsupportedOperationException("--blas reaches the interpreter and the JVM class output only:"
					+ " a tuned CBLAS is called through the foreign function API, which WASM does not have."
					+ " Use --simd for the linalg: kernels on a .wasm output");
		}
		// --gpu is the same story one layer out: the CUDA driver is reached through the
		// foreign function API, so WASM cannot have it, and a silent no-op is exactly
		// what the flag exists to prevent.
		if (gpu && !outputFile.endsWith(".class")) {
			throw new UnsupportedOperationException("--gpu reaches the interpreter and the JVM class output only:"
					+ " a GPU is driven through the foreign function API, which WASM does not have."
					+ " Use --simd for the linalg: kernels on a .wasm output");
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
		HostBoundary boundary = hostBoundary == null ? HostBoundary.ENVELOPE : hostBoundary;
		// Inline top-level (load "path") forms at compile time: the compilers collect
		// defuns in a static pass that a runtime load cannot feed, so a program split
		// across files (a console driver loading a rendering-free core) would otherwise
		// fail to compile. The interpreter loads at runtime instead, so this is
		// compile-path only.
		// Expand user macros (defmacro) after inlining: the definitions are consumed
		// and every call site is fully expanded by the macro-time interpreter, so the
		// compilers see only ordinary forms. The interpreter path expands natively at
		// evaluation time instead.
		// Then splice the Lisp-source JSON library when the program references
		// rontolisp:json-parse / rontolisp:json-stringify, rewriting the call sites
		// to the fixed-arity helpers, the Lisp-source linalg library when the
		// program references the linalg package, and the Lisp-source URL library
		// when the program references rontolisp:url-* / query-param* (the
		// interpreter path instead loads the libraries lazily inside LispEvaluator).
		// The whole frontend reads with the target backend's feature set, so
		// #+rontolisp-jvm / #+rontolisp-wasm conditionals select per-backend code --
		// and #+rontolisp-reactor selects reactor-mode code: the
		// clack-handler-rontolisp shim's run picks the http-handler directive or the
		// %http-reactor marker with it. Reactor mode is --no-wasi (a Preview 1 module
		// with no WASI imports, or -- with --component -- a reactor component that
		// imports nothing) or --no-gc (a pure-compute reactor with or without the
		// component wrap).
		boolean reactor = noWasi || noGc;
		Features features = outputFile.endsWith(".wasm") ? (reactor ? Features.WASM_REACTOR : Features.WASM)
				: Features.JVM;
		// And #+rontolisp-component selects code for the COMPONENT BOUNDARY, which is a
		// different boundary rather than a different backend: a component's host
		// functions cross the canonical ABI, so the core-module directives
		// (rontolisp:wasm-import / a :bytes type) are refused there and a source that
		// wants one needs a way to say "not on this target" -- the reactor features
		// above cannot, since a reactor component carries them too. Additive, like every
		// other target-describing feature.
		if (component && outputFile.endsWith(".wasm")) {
			features = features.with(List.of(Features.COMPONENT));
		}
		// And #+rontolisp-body-imports selects the code a HAND-WRITTEN reactor needs only
		// where the :bytes body imports really exist: the --no-wasi wasm-GC core module,
		// built with the streaming boundary. It follows the FLAG, which is what no
		// combination of target features could do -- and it says the thing itself, where
		// the guard it replaces spelled out the targets that cannot carry the imports and
		// quietly got --no-gc wrong.
		if (outputFile.endsWith(".wasm") && noWasi && !component && !noGc && boundary.bodiesOutOfBand()) {
			features = features.with(List.of(Features.BODY_IMPORTS));
		}
		WitExportDirective.Backend witBackend = witBackend(outputFile, noGc, component);
		// (rontolisp:wit-import "kv.wit" :interface "..."): bind a WIT interface's
		// functions. Unlike wit-export this runs BEFORE UserMacroExpander, because the
		// names it binds live in a package the WIT names -- the (defpackage kv ...) it
		// synthesizes must exist before any pass resolves a kv:get call site, and the
		// macro expander resolves every top-level form through its own PackageResolver.
		// It
		// needs nothing macro expansion produces: a wit-import is checked against a WIT
		// file, not against the program.
		// #. read-time eval on the compile path: the marker read wraps each datum in a
		// (%read-eval datum) marker that UserMacroExpander later resolves against the
		// macro-time evaluator, per top-level form (the interpreter's loadFile timing).
		List<LispVal> read = source.contains("#.") ? LispReader.readAllWithReadEvalMarkers(source, features, entryFile)
				: LispReader.readAllFromString(source, features, entryFile);
		List<LispVal> loaded = LoadInliner.inline(read, SourceLoader.fileSystem(), baseDir, systemPath, features,
				dists);
		// Expand the (rontolisp:async (defun ...)) wrapper before anything scans for
		// definitions: HttpLibrary's handler reachability, WitExportInliner's defun
		// checks and the library pruner all recognize async-defun, never the sugar.
		loaded = LispMacroExpander.rewriteAsyncSugar(loaded);
		// Under --component the inliner also prunes the interface members the program
		// never references -- the core tree shaker cannot do that job even under
		// --optimize, because a WIT member costs a component-level import declaration and
		// a canon lower, not just a core function; --no-prune / --dynamic disable that,
		// like the library defun pruner.
		loaded = WitImportInliner.inline(loaded, baseDir, witBackend, SourceLoader.fileSystem(), !dynamic && !noPrune);
		// The --no-wasi (wasm-GC) reactor legs, BEFORE the serve-mode switch below reads
		// the program: a reactor owns no socket, so the rontolisp:http-handler directive
		// lowers to the host-driven transport (the same leg clack:clackup takes there),
		// which is also what lets the same http-handler source compile as a Worker; and
		// under --host-fetch, rontolisp:fetch gets the env.fetch lowering spliced when
		// the program fetches (before UserMacroExpander, so JsonLibrary and the prelude
		// pick up the splice's own call sites).
		if (outputFile.endsWith(".wasm") && noWasi && !noGc) {
			loaded = HttpReactorInliner.lowerHttpHandler(loaded);
			if (hostFetch) {
				loaded = HostFetchLibrary.process(loaded, boundary, reentrant);
			}
		}
		// Both rontolisp:fetch AND rontolisp:http-handler on the --component path are ONE
		// Lisp-source library (http.lisp) over a wit-imported wasi:http@0.3.0 surface,
		// spliced HERE -- right after WitImportInliner, which http.lisp's own wit-import
		// directives are lowered against (HttpLibrary does that itself), and before
		// UserMacroExpander, which its cond/handler-case bodies need. The splice's member
		// filter follows the reachable half, so a fetch-only program binds no serve
		// member and vice versa. The interpreter/JVM keep java.net.http / HttpServer;
		// Preview 1 has neither.
		boolean serve = component && HttpHandlerInliner.usesHttpHandler(loaded);
		// --no-wasi under --component asks for a component that imports NOTHING, and
		// the wasi:*-binding library splices below exist precisely to give a component
		// its WASI surface (http.lisp / wait.lisp / sockets.lisp / stdin.lisp /
		// environment.lisp are each the wit-imported wasi:* surface of their
		// primitives). ONE decision here gates all five: they see the Preview 1
		// backend, whose primitives already honor the --no-wasi contract (the fd_write
		// sink discards output, the rest trap or signal at call time;
		// .kb/wasm-export-no-wasi.md). wit-import/wit-export lowering keeps the real
		// backend -- a USER wit-import under --no-wasi is rejected by the compiler,
		// with a message naming both sides.
		WitExportDirective.Backend spliceBackend = component && noWasi ? WitExportDirective.Backend.WASM_GC
				: witBackend;
		// serve + rontolisp:wit-export is an error (a serve component's only export is
		// wasi:http/handler); the check fires below on the macro-expanded program.
		// Splicing http.lisp's %serve-handle wasm-export first would surface as a
		// DIFFERENT error (wit-export forbids a hand-written wasm-export beside it), so
		// gate the serve half off when a wit-export world is present and let the clearer
		// guard win.
		boolean serveGlue = serve && !WitExportInliner.usesWitExport(loaded);
		// The :raw-body mode must be read BEFORE HttpLibrary rewrites the directive
		// away (the wasm path drops it); it decides both the synthesized
		// %serve-request-body in there and the splice filter below.
		boolean bufferBody = HttpLibrary.usesBufferedBody(loaded);
		loaded = HttpLibrary.process(loaded, spliceBackend, serveGlue);
		// The host-driven reactor's counterpart of that splice: a Clack handler
		// backend whose run stores the app and leaves a rontolisp::%http-reactor
		// marker (the clack-handler-reactor shim always; the clack-handler-rontolisp
		// shim under #+rontolisp-reactor) gets the marker
		// lowered to nil and the wasm-export of a bridge to its dispatcher
		// synthesized -- so a Worker source is (clack:clackup #'app :server
		// :rontolisp) and nothing else. A no-op on the interpreter and the JVM (the
		// shims do not even read the marker there).
		loaded = HttpReactorInliner.process(loaded, witBackend, noWasi, boundary, reentrant);
		// The shared reactor machinery behind BOTH handler backends
		// (http-reactor.lisp: the one app store, the JSON envelope over
		// %http-make-env / %http-normalize-response): spliced for EVERY backend
		// whenever the program references it -- the synthesized bridge above does,
		// and so do the backends' run/handle/dispatch. Before HttpServerLibrary,
		// whose entry points the machinery calls; JsonLibrary later picks up its
		// json-parse / json-stringify call sites.
		loaded = HttpReactorLibrary.process(loaded);
		// The server-side HTTP value model (http-server.lisp): the Clack environment a
		// handler receives and the Clack response it returns, written once in rontolisp
		// so every backend agrees by construction. Spliced for EVERY backend (unlike
		// http.lisp, which is the --component transport) whenever the program serves,
		// and BEFORE GrayStreamsLibrary below, whose call-site rewrite the library's
		// bivalent :raw-body stream depends on. A default-mode (:raw-body :stream)
		// program gets the library without its buffered-body half.
		loaded = HttpServerLibrary.process(loaded, bufferBody);
		// rontolisp:wait-for on the --component path is the wait.lisp shim over a
		// wit-imported wasi:clocks/monotonic-clock@0.3.0 (a pending future the
		// scheduler settles). Spliced like http.lisp; a no-op elsewhere (the
		// interpreter/JVM keep their CompletableFuture timer, Preview 1 keeps the
		// compile error).
		loaded = WaitForLibrary.process(loaded, spliceBackend);
		// The CLIENT tls built-ins (tls-connect / tls-upgrade) on the --component path
		// are the tls.lisp library over a wit-imported wasi:tls@0.3.0-draft. It rides
		// sockets.lisp's entry table, so it must splice BEFORE SocketsLibrary: the
		// spliced forms reference rontolisp:tcp-connect, which fires the sockets
		// trigger for a program that only names tls. A no-op elsewhere (the
		// interpreter/JVM keep SSLSocket, Preview 1 keeps the compile error; the
		// tls-listen family is a compile error on every WASM target -- the wasi:tls
		// proposal has no server interface).
		loaded = TlsLibrary.process(loaded, spliceBackend);
		// The rontolisp:tcp-* built-ins on the --component path are the sockets.lisp
		// library over a wit-imported wasi:sockets/types@0.3.0 (this splice replaced
		// the hand-written sockets adapter). Spliced like http.lisp; a no-op elsewhere
		// (the interpreter/JVM keep java.net.Socket, Preview 1 keeps the compile
		// error). The trigger includes any usocket: reference: the usocket shim rides
		// tcp-*, and its own splice runs later in this pipeline.
		loaded = SocketsLibrary.process(loaded, spliceBackend);
		// Component stdin over wit-imported wasi:cli/stdin@0.3.0 (stdin.lisp), bound
		// FROM the fixed import block. Two shapes: the %stdin-*-or-raw-f helpers
		// sockets.lisp's dispatchers fall through to (a serve program gets the
		// raw-passthrough stub -- its service world has no stdin), and the full
		// dispatch splice for an ASYNC stdin-reading program, whose async-context
		// reads then promote to awaits. A non-async stdin program is left on the
		// preview1 adapter's stdin branch, byte-identical. Must run AFTER
		// SocketsLibrary (it keys on sockets.lisp's dispatchers being present).
		loaded = StdinLibrary.process(loaded, spliceBackend, serve);
		// The WIT runtime (wit.lisp: the provider registry, rontolisp:wit-provide and the
		// rontolisp:wit-error condition -- the provider MECHANISM, and no provider for
		// any
		// concrete interface) backs the %wit-call bodies the inliner just synthesized for
		// the interpreter/JVM boundary. On the WASM backends it splices nothing: there
		// the
		// bindings ARE rontolisp:wasm-import directives and the host is the provider.
		// GrayStreamsLibrary.process rewrites write-string/write-char call sites onto
		// the Gray dispatch helpers when the program uses the protocol (and splices
		// gray.lisp if no load already did), so a CLOS instance stream reaches the
		// generics in compiled programs like it does on the interpreter.
		// UnreadCharLibrary.process splices the handle-side pushback of unread-char and
		// rewrites the character-read call sites onto it. LAST of the four, so a call
		// site any of them introduced -- a Gray dispatch helper's handle FALLBACK above
		// all -- reaches the cell too; a program that never names unread-char is
		// returned unchanged.
		// TorchLibrary runs BEFORE LinalgLibrary so the linalg: references inside the
		// spliced torch definitions pull the linalg library in too.
		List<LispVal> program = UnreadCharLibrary
			.process(WitLibrary.process(UsocketLibrary.process(GrayStreamsLibrary.process(LispPreludeLibrary.process(
					UrlLibrary.process(LinalgLibrary
						.process(TorchLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))))),
					features)))));
		// uiop:getenv on the --component path is environment.lisp over a wit-imported
		// wasi:cli/environment@0.3.0 -- bound FROM the fixed import block on the base /
		// sockets variants and as an appended user import under serve, whose service
		// world declares no environment interface. Spliced like the libraries above; a
		// no-op elsewhere (the interpreter/JVM keep System.getenv, Preview 1 keeps the
		// host-filled environ buffer scan). It runs AFTER the whole splice chain (and
		// after user-macro expansion) because a getenv call any of them introduces has
		// to be seen too: the prelude's uiop:default-temporary-directory reads TMPDIR,
		// and with this pass upstream of the splice a smart-buffer program failed the
		// component compile with "compiled without EnvironmentLibrary.process".
		program = EnvironmentLibrary.process(program, spliceBackend);
		// uiop:quit on the WASM backends is exit.lisp over wasi_snapshot_preview1's
		// proc_exit (Preview 1) / wit-imported wasi:cli/exit@0.3.0 (--component, an
		// appended user import: the fixed block does not declare that interface). Same
		// position and same reason as the environment splice above -- a quit any earlier
		// pass introduced has to be seen too -- and a no-op elsewhere (the interpreter
		// raises its exit signal, the JVM emits System.exit).
		program = ExitLibrary.process(program, spliceBackend, features);
		// Splice the Lisp-source vec library (the scalar reference over the packed
		// double-float array type) when the program references the vec package. The
		// --no-gc scalar WASM backend is the exception: it has no general array type and
		// lowers the whole vec: surface to native fixed-width WASM SIMD itself
		// (NoGcWasmCompiler), so it must NOT get the splice.
		if (!(outputFile.endsWith(".wasm") && noGc)) {
			program = VecLibrary.process(program);
		}
		// (rontolisp:wit-export "world.wit"): check the program against the WIT world it
		// claims to implement and expand the directive into the rontolisp:wasm-export
		// directives the world declares -- the backends see nothing new. It runs here
		// because every defun (including a load-spliced or macro-produced one) is now a
		// literal top-level form, and because the synthesized directives must still count
		// as pruning roots below.
		boolean witWorld = WitExportInliner.usesWitExport(program);
		program = WitExportInliner.inline(program, baseDir, witBackend, SourceLoader.fileSystem());
		// Decide the (boundp 'name) probes whose answer the top-level order already fixes
		// (compiler/CompileTimeBoundp), and collapse the guards they were testing. It has
		// to happen BEFORE the tree-shaker below, not just inside the compilers: the
		// portable (unless (boundp '+k+) (defconstant +k+ v)) is what keeps a library's
		// constants from being top-level definers, and an unreachable one the shaker
		// cannot see stays in the artifact. Packages are still unresolved here, so only
		// the "unbound" direction is decided; the compilers run the pass again once the
		// spellings are canonical.
		program = CompileTimeBoundp.fold(program, dynamic, false);
		// Drop spliced library definitions unreachable from the user program (the AST
		// tree-shaker; see LibraryDefunPruner). Skipped under --dynamic (late binding
		// can resolve any name at runtime) and --no-prune (the explicit escape hatch) --
		// but the ASDF provenance markers the pruner reads are dropped either way, so
		// those two flags emit the artifact they emitted before the markers existed.
		program = (!dynamic && !noPrune) ? LibraryDefunPruner.prune(program)
				: LibraryDefunPruner.stripSystemMarkers(program);
		byte[] bytes;
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
			// The JVM backend cannot parse PEM in hand-assembled bytecode, so rewrite
			// rontolisp:tls-listen-pem to embed the compile-time-parsed PKCS12 keystore
			// (WASM keeps tls-listen-pem, which its compiler rejects outright).
			String className = outputFile.replace(".class", "");
			bytes = new JvmLispCompiler(className, dynamic, optimize, simd, blas, gpu).runtimeFeatures(features.names())
				.compile(TlsPemInliner.inline(program, baseDir));
		}
		try {
			Files.write(Path.of(outputFile), bytes);
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

	// The backend a rontolisp:wit-export world is checked against: only the WASM backends
	// impose the export boundary's backend-specific rules (s64 needs --no-gc, an async
	// func cannot be lifted by the --no-gc reactor). Compiling to a .class checks the
	// contract but exports nothing, like the interpreter. wit-export treats WASM_GC and
	// WASM_COMPONENT identically; wit-import lowers them differently (Preview 1 core
	// imports vs the canonical-ABI lower).
	private static WitExportDirective.Backend witBackend(String outputFile, boolean noGc, boolean component) {
		if (!outputFile.endsWith(".wasm")) {
			return WitExportDirective.Backend.OTHER;
		}
		if (noGc) {
			return WitExportDirective.Backend.WASM_NO_GC;
		}
		return component ? WitExportDirective.Backend.WASM_COMPONENT : WitExportDirective.Backend.WASM_GC;
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
		this.out.println("  file -o out.wasm    Compile to WASM");
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
		this.out.println("                     java --add-modules jdk.incubator.vector). WASM (.wasm, both");
		this.out.println("                     wasm-GC and --no-gc): emit native v128 (f64x2/f32x4). On wasm-GC");
		this.out.println("                     packed float arrays stay GC-managed (v128 lane groups), so memory");
		this.out.println("                     behaves as without --simd. Interpreter/REPL: run the vec: kernels");
		this.out.println("                     on the Vector API (baked into the native binary; on java -jar add");
		this.out.println("                     --add-modules jdk.incubator.vector, else it falls back to scalar).");
		this.out.println("  --blas             Route the linalg: matrix product to a tuned CBLAS from the OS");
		this.out.println("                     Interpreter (incl. the native binary) and JVM (.class) only --");
		this.out.println("                     WASM has no foreign function interface. macOS finds Accelerate");
		this.out.println("                     with no setup; on Linux install one (e.g. libopenblas0-pthread).");
		this.out.println("                     A machine with none runs the same programs, unaccelerated. The");
		this.out.println("                     library reorders its reduction, so results are close to but not");
		this.out.println("                     bit-identical to the other backends. RONTOLISP_BLAS names a");
		this.out.println("                     library outright; RONTOLISP_BLAS_VERBOSE=1 prints what was bound.");
		this.out.println("  --gpu              Route the linalg: matrix product to an NVIDIA GPU");
		this.out.println("                     Interpreter (incl. the native binary) and JVM (.class) only --");
		this.out.println("                     the CUDA driver is reached through the foreign function API,");
		this.out.println("                     which WASM does not have. A compiled .class carries the whole");
		this.out.println("                     binding and still runs with a plain `java Prog` (add");
		this.out.println("                     --enable-native-access=ALL-UNNAMED to silence the JVM warning).");
		this.out.println("                     Needs libcuda.so.1 (the driver) and nothing else:");
		this.out.println("                     no CUDA toolkit. A machine without a device runs the same");
		this.out.println("                     programs, unaccelerated. Only products above ~51x51x51 are");
		this.out.println("                     offered; everything smaller stays on the CPU. The device kernel");
		this.out.println("                     fuses each multiply-add, so results are close to but not");
		this.out.println("                     bit-identical to the other backends.");
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

	static boolean isBalanced(String input) {
		int depth = 0;
		boolean inString = false;
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (inString) {
				if (c == '\\' && i + 1 < input.length()) {
					i++;
				}
				else if (c == '"') {
					inString = false;
				}
			}
			else {
				if (c == '"') {
					inString = true;
				}
				else if (c == '(') {
					depth++;
				}
				else if (c == ')') {
					depth--;
				}
			}
		}
		return depth <= 0 && !inString;
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
			exit(runReporting(cli, args));
			return;
		}
		PrintStream bufferedOut = new PrintStream(
				new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
				StandardCharsets.UTF_8);
		System.setOut(bufferedOut);
		Runtime.getRuntime().addShutdownHook(new Thread(bufferedOut::flush));
		RontoLispCli cli = new RontoLispCli(System.in, bufferedOut);
		int code;
		try {
			code = runReporting(cli, args);
		}
		finally {
			bufferedOut.flush();
		}
		exit(code);
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
