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
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.Version;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.JsonLibrary;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.eval.VecSimd;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.UrlLibrary;
import am.ik.rontolisp.eval.UsocketLibrary;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * CLI entry point for rontolisp.
 */
public final class RontoLispCli {

	private final PrintStream out;

	private final InputStream in;

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
	 * Run the CLI with the given arguments.
	 * @param args the command-line arguments
	 */
	public void run(String[] args) {
		CliOptions options = CliOptions.build(args);

		if (options.contains("-h") || options.contains("--help")) {
			printUsage();
			return;
		}
		if (options.contains("-v") || options.contains("--version")) {
			this.out.println(Version.getVersionAsJson());
			return;
		}

		// The .asd search path for asdf:load-system: the --system-path option first,
		// then the RONTOLISP_SOURCE_REGISTRY environment variable, both accepting
		// several directories joined with the platform path separator (like PATH). The
		// directory of the loading file is always searched first, before these.
		List<String> systemPath = systemPath(options.get("--system-path"), System.getenv("RONTOLISP_SOURCE_REGISTRY"));

		if (!options.containsNoKey()) {
			repl(systemPath, options.contains("--simd"));
			return;
		}

		String inputFile = Objects.requireNonNull(options.getNokey());
		String source = readFile(inputFile);
		// Relative (load "...") paths resolve against the entry file's directory, so a
		// program can be run or compiled from any working directory and still find its
		// companion files (like Common Lisp's *load-pathname*).
		String baseDir = SourceLoader.parentDir(inputFile);

		if (options.contains("-o")) {
			String outputFile = Objects.requireNonNull(options.get("-o"));
			compileToFile(source, baseDir, systemPath, outputFile, options.contains("--dynamic"),
					options.contains("--component"), options.contains("--no-wasi"), options.contains("--optimize"),
					options.contains("--no-gc"), options.contains("--simd"), options.contains("--no-prune"),
					options.contains("--wit"));
		}
		else {
			interpret(source, baseDir, systemPath, options.contains("--simd"));
		}
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

	private void repl(List<String> systemPath, boolean simd) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setSystemPath(systemPath);
		if (simd) {
			enableSimd(evaluator);
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
			List<LispVal> exprs = LispReader.readAllFromString(buffer.toString());
			LispVal result = LispNil.INSTANCE;
			for (LispVal expr : exprs) {
				result = evaluator.eval(expr);
			}
			out.println(result.print());
		}
		catch (RuntimeException ex) {
			out.println("Error: " + ex.getMessage());
		}
		buffer.setLength(0);
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

	private void interpret(String source, @Nullable String baseDir, List<String> systemPath, boolean simd) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setLoadBaseDir(baseDir);
		evaluator.setSystemPath(systemPath);
		if (simd) {
			enableSimd(evaluator);
		}
		List<LispVal> exprs = LispReader.readAllFromString(source);
		for (LispVal expr : exprs) {
			evaluator.eval(expr);
		}
	}

	private void compileToFile(String source, @Nullable String baseDir, List<String> systemPath, String outputFile,
			boolean dynamic, boolean component, boolean noWasi, boolean optimize, boolean noGc, boolean simd,
			boolean noPrune, boolean wit) {
		// --wit describes a component's typed world, so it is meaningless for any other
		// output; fail fast instead of silently ignoring the request.
		if (wit && !(component && outputFile.endsWith(".wasm"))) {
			throw new UnsupportedOperationException(
					"--wit requires --component and a .wasm output (e.g. -o out.wasm --component --wit)");
		}
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
		// #+rontolisp-jvm / #+rontolisp-wasm conditionals select per-backend code.
		Features features = outputFile.endsWith(".wasm") ? Features.WASM : Features.JVM;
		List<LispVal> program = UsocketLibrary
			.process(LispPreludeLibrary.process(UrlLibrary.process(LinalgLibrary.process(JsonLibrary
				.process(UserMacroExpander.expand(LoadInliner.inline(LispReader.readAllFromString(source, features),
						SourceLoader.fileSystem(), baseDir, systemPath, features)))))));
		// Splice the Lisp-source vec library (the scalar reference over the packed
		// double-float array type) when the program references the vec package. The
		// --no-gc scalar WASM backend is the exception: it has no general array type and
		// lowers the whole vec: surface to native fixed-width WASM SIMD itself
		// (NoGcWasmCompiler), so it must NOT get the splice.
		if (!(outputFile.endsWith(".wasm") && noGc)) {
			program = VecLibrary.process(program);
		}
		// Drop spliced library definitions unreachable from the user program (the AST
		// tree-shaker; see LibraryDefunPruner). Skipped under --dynamic (late binding
		// can resolve any name at runtime) and --no-prune (the explicit escape hatch).
		if (!dynamic && !noPrune) {
			program = LibraryDefunPruner.prune(program);
		}
		byte[] bytes;
		String witText = null;
		if (outputFile.endsWith(".wasm")) {
			if (noGc) {
				// --no-gc selects the separate scalar (non-GC) lowering: a plain MVP
				// module
				// with no wasm-GC types, no imports and no memory, for pure-numeric
				// rontolisp:wasm-export functions. It is a pure-compute reactor, so it
				// implies --no-wasi. With --component the same core module is wrapped as
				// a compact reactor-style component (typed scalar exports, no adapter,
				// no wasm-GC requirement) instead of the GC component pipeline.
				// --simd is the orthogonal acceleration switch: with it the vec: kernels
				// lower to native v128 (f64x2/f32x4); without it to plain scalar loops
				// that
				// run on a runtime lacking the SIMD proposal.
				NoGcWasmCompiler compiler = new NoGcWasmCompiler(optimize, simd, component);
				bytes = compiler.compile(program);
				witText = compiler.componentWit();
			}
			else {
				// rontolisp:http-handler compiles to a wasi:http/incoming-handler
				// component
				// (--component only). The HttpHandlerInliner splices in a %http-dispatch
				// wasm-export wrapper that the serve adapter calls per request.
				boolean serve = component && HttpHandlerInliner.usesHttpHandler(program);
				List<LispVal> wasmProgram = serve ? HttpHandlerInliner.inline(program) : program;
				// --simd routes the vectorizable vec: kernels to emitted v128 helpers and
				// switches a packed float array's storage to an (array (mut v128)) of
				// lane
				// groups -- still a GC object the engine collects, so memory behaves as
				// it
				// does without the flag.
				WasmLispCompiler compiler = new WasmLispCompiler(dynamic, component, noWasi, optimize, serve, simd);
				bytes = compiler.compile(wasmProgram);
				witText = compiler.componentWit();
			}
		}
		else {
			// The JVM backend cannot parse PEM in hand-assembled bytecode, so rewrite
			// rontolisp:tls-listen-pem to embed the compile-time-parsed PKCS12 keystore
			// (WASM keeps tls-listen-pem, which its compiler rejects outright).
			String className = outputFile.replace(".class", "");
			bytes = new JvmLispCompiler(className, dynamic, optimize, simd)
				.compile(TlsPemInliner.inline(program, baseDir));
		}
		try {
			Files.write(Path.of(outputFile), bytes);
			if (wit) {
				String witFile = outputFile.substring(0, outputFile.length() - ".wasm".length()) + ".wit";
				Files.writeString(Path.of(witFile), Objects.requireNonNull(witText));
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
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
		this.out.println("  file -o out.class   Compile to JVM bytecode");
		this.out.println("  file -o out.wasm    Compile to WASM");
		this.out.println();
		this.out.println("Options:");
		this.out.println("  -h, --help         Show this help message");
		this.out.println("  -v, --version      Show version");
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
		this.out.println("  --wit              With --component: also write the component's WIT world");
		this.out.println("                     (imports + typed exports) next to the .wasm output, so hosts");
		this.out.println("                     and binding generators (e.g. jco) need no wasm-tools introspection");
		this.out.println("  --no-wasi          Emit a WASM module with no WASI imports (reactor mode)");
		this.out.println("                     Preview 1 only; instantiates without an import object (beyond");
		this.out.println("                     any rontolisp:wasm-import host functions), only pure-compute");
		this.out.println("                     rontolisp:wasm-export functions work (I/O traps)");
		this.out.println("  --optimize         Dead-code-eliminate the compiled output");
		this.out.println("                     WASM: drop functions unreachable from the exports/_start; great");
		this.out.println("                     with --no-wasi. No effect in --component mode.");
		this.out.println("                     JVM: drop methods unreachable from main + compact the constant pool");
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
			new RontoLispCli(System.in, System.out).run(args);
			return;
		}
		PrintStream bufferedOut = new PrintStream(
				new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
				StandardCharsets.UTF_8);
		System.setOut(bufferedOut);
		Runtime.getRuntime().addShutdownHook(new Thread(bufferedOut::flush));
		try {
			new RontoLispCli(System.in, bufferedOut).run(args);
		}
		finally {
			bufferedOut.flush();
		}
	}

}
