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

import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.Version;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.EnvironmentLibrary;
import am.ik.rontolisp.eval.GrayStreamsLibrary;
import am.ik.rontolisp.eval.HttpLibrary;
import am.ik.rontolisp.eval.HttpServerLibrary;
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
import am.ik.rontolisp.eval.SocketsLibrary;
import am.ik.rontolisp.eval.StdinLibrary;
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
					options.contains("--emit-wit"), inputFile);
		}
		else {
			interpret(source, baseDir, systemPath, options.contains("--simd"), inputFile);
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

	private void interpret(String source, @Nullable String baseDir, List<String> systemPath, boolean simd,
			@Nullable String entryFile) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		evaluator.setLoadBaseDir(baseDir);
		evaluator.setSystemPath(systemPath);
		if (simd) {
			enableSimd(evaluator);
		}
		// #. read-time eval: only sources textually containing #. pay for the marker
		// read; each top-level form's markers resolve just before it evaluates, the
		// same timing the runtime loadFile uses.
		if (source.contains("#.")) {
			for (LispVal expr : LispReader.readAllWithReadEvalMarkers(source, Features.INTERPRETER, entryFile)) {
				evaluator.eval(evaluator.resolveReadTimeEvalInCode(expr));
			}
			return;
		}
		List<LispVal> exprs = LispReader.readAllFromString(source, Features.INTERPRETER, entryFile);
		for (LispVal expr : exprs) {
			evaluator.eval(expr);
		}
	}

	private void compileToFile(String source, @Nullable String baseDir, List<String> systemPath, String outputFile,
			boolean dynamic, boolean component, boolean noWasi, boolean optimize, boolean noGc, boolean simd,
			boolean noPrune, boolean wit, @Nullable String entryFile) {
		// The frontend records where every cons was read from, so a pass that fails long
		// after the read -- a macro body that signals, an operator no backend knows, a
		// malformed binding list a walker casts and fails on -- can still name
		// file:line:column instead of leaving the user a bare message about a program
		// that may be a hundred spliced files (.todo/151 phase 2). Compile path only:
		// see SourceProvenance for why the interpreter deliberately does not record.
		SourceProvenance.startRecording();
		try {
			compileRecorded(source, baseDir, systemPath, outputFile, dynamic, component, noWasi, optimize, noGc, simd,
					noPrune, wit, entryFile);
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

	private void compileRecorded(String source, @Nullable String baseDir, List<String> systemPath, String outputFile,
			boolean dynamic, boolean component, boolean noWasi, boolean optimize, boolean noGc, boolean simd,
			boolean noPrune, boolean wit, @Nullable String entryFile) {
		// --emit-wit describes a component's typed world, so it is meaningless for any
		// other
		// output; fail fast instead of silently ignoring the request.
		if (wit && !(component && outputFile.endsWith(".wasm"))) {
			throw new UnsupportedOperationException(
					"--emit-wit requires --component and a .wasm output (e.g. -o out.wasm --component --emit-wit)");
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
		List<LispVal> loaded = LoadInliner.inline(read, SourceLoader.fileSystem(), baseDir, systemPath, features);
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
		// Both rontolisp:fetch AND rontolisp:http-handler on the --component path are ONE
		// Lisp-source library (http.lisp) over a wit-imported wasi:http@0.3.0 surface,
		// spliced HERE -- right after WitImportInliner, which http.lisp's own wit-import
		// directives are lowered against (HttpLibrary does that itself), and before
		// UserMacroExpander, which its cond/handler-case bodies need. The splice's member
		// filter follows the reachable half, so a fetch-only program binds no serve
		// member and vice versa. The interpreter/JVM keep java.net.http / HttpServer;
		// Preview 1 has neither.
		boolean serve = component && HttpHandlerInliner.usesHttpHandler(loaded);
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
		loaded = HttpLibrary.process(loaded, witBackend, serveGlue);
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
		loaded = WaitForLibrary.process(loaded, witBackend);
		// The rontolisp:tcp-* built-ins on the --component path are the sockets.lisp
		// library over a wit-imported wasi:sockets/types@0.3.0 (this splice replaced
		// the hand-written sockets adapter). Spliced like http.lisp; a no-op elsewhere
		// (the interpreter/JVM keep java.net.Socket, Preview 1 keeps the compile
		// error). The trigger includes any usocket: reference: the usocket shim rides
		// tcp-*, and its own splice runs later in this pipeline.
		loaded = SocketsLibrary.process(loaded, witBackend);
		// Component stdin over wit-imported wasi:cli/stdin@0.3.0 (stdin.lisp), bound
		// FROM the fixed import block. Two shapes: the %stdin-*-or-raw-f helpers
		// sockets.lisp's dispatchers fall through to (a serve program gets the
		// raw-passthrough stub -- its service world has no stdin), and the full
		// dispatch splice for an ASYNC stdin-reading program, whose async-context
		// reads then promote to awaits. A non-async stdin program is left on the
		// preview1 adapter's stdin branch, byte-identical. Must run AFTER
		// SocketsLibrary (it keys on sockets.lisp's dispatchers being present).
		loaded = StdinLibrary.process(loaded, witBackend, serve);
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
		List<LispVal> program = WitLibrary
			.process(UsocketLibrary.process(GrayStreamsLibrary.process(LispPreludeLibrary.process(UrlLibrary
				.process(LinalgLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))))))));
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
		program = EnvironmentLibrary.process(program, witBackend);
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
		// Drop spliced library definitions unreachable from the user program (the AST
		// tree-shaker; see LibraryDefunPruner). Skipped under --dynamic (late binding
		// can resolve any name at runtime) and --no-prune (the explicit escape hatch) --
		// but the ASDF provenance markers the pruner reads are dropped either way, so
		// those two flags emit the artifact they emitted before the markers existed.
		program = (!dynamic && !noPrune) ? LibraryDefunPruner.prune(program)
				: LibraryDefunPruner.stripSystemMarkers(program);
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
				WasmLispCompiler compiler = new WasmLispCompiler(dynamic, component, noWasi, optimize, serve, simd);
				bytes = compiler.compile(program);
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
		this.out.println("  --emit-wit         With --component: also write the component's WIT world");
		this.out.println("                     (imports + typed exports) next to the .wasm output, so hosts");
		this.out.println("                     and binding generators (e.g. jco) need no wasm-tools introspection");
		this.out.println("                     It is the only thing that reports what the component actually");
		this.out.println("                     IMPORTS -- a hand-written world states only the export side");
		this.out.println("  --scaffold-wit W   Generate a skeleton implementing the WIT world W instead of");
		this.out.println("                     compiling: a rontolisp:wit-export directive plus one defun stub");
		this.out.println("                     per export (with -o FILE; prints to stdout otherwise). Pick the");
		this.out.println("                     world with --world NAME when the file declares several.");
		this.out.println("                     The program then IMPLEMENTS the .wit: the compiler checks every");
		this.out.println("                     defun against it, so no :params/:returns list is written by hand");
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
