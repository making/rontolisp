package am.ik.rontolisp.web;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.JsonLibrary;
import am.ik.rontolisp.compiler.CompileTimeBoundp;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.WitExportInliner;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.eval.UrlLibrary;
import am.ik.rontolisp.eval.UsocketLibrary;
import am.ik.rontolisp.eval.WitImportInliner;
import am.ik.rontolisp.eval.WitLibrary;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * Web Image entry point that exposes the rontolisp interpreter and compilers to
 * the browser. The actual UI lives in {@code index.html}; this class only
 * installs three callables on the JavaScript global scope:
 * <ul>
 * <li>{@code rontoEval(source)} - interpret source in a persistent REPL
 * environment and return the captured output plus the last value.</li>
 * <li>{@code rontoCompileJvm(source, className)} - compile to a JVM
 * {@code .class} file, returned as a Base64 string.</li>
 * <li>{@code rontoCompileWasm(source)} - compile to a {@code .wasm} module,
 * returned as a Base64 string.</li>
 * </ul>
 * Compilation errors are returned as a plain string prefixed with
 * {@code "ERROR:"} so the front-end can distinguish them from Base64 payloads.
 * <p>
 * This class depends on the GraalVM-only {@code org.graalvm.webimage.api} module
 * and is compiled only under the {@code web} Maven profile, which adds
 * {@code src/web/java} as a source directory.
 */
public final class RontoPlayground {

	private static final String ERROR_PREFIX = "ERROR:";

	private static final ByteArrayOutputStream replBuffer = new ByteArrayOutputStream();

	private static final PrintStream replOut = new PrintStream(replBuffer);

	private static final LispEvaluator evaluator = new LispEvaluator(replOut);

	/**
	 * In-memory files uploaded from the browser, keyed by file name. There is no filesystem
	 * in the WASM sandbox, so {@code (load "name.lisp")} resolves against this map.
	 */
	private static final Map<String, String> uploadedFiles = new LinkedHashMap<>();

	/**
	 * Reads an uploaded file. Backs {@code (load "x.lisp")} on the REPL and
	 * {@code (rontolisp:wit-export "w.wit")} on both compile paths, so a WIT world is
	 * dropped onto the page like any other companion file.
	 */
	private static final SourceLoader uploads = path -> {
		String content = uploadedFiles.get(path);
		if (content == null) {
			throw new FileNotFoundException(
					path + " (upload it first; available: " + String.join(", ", uploadedFiles.keySet()) + ")");
		}
		return content;
	};

	static {
		evaluator.setSourceLoader(uploads);
	}

	private RontoPlayground() {
	}

	/** Register (or replace) an uploaded file so that {@code load} can resolve it. */
	static String putFile(String name, String content) {
		uploadedFiles.put(name, content);
		return String.join("\n", uploadedFiles.keySet());
	}

	/** Interpret {@code source} in the persistent REPL environment. */
	static String evalLine(String source) {
		replBuffer.reset();
		try {
			// #. read-time eval, like the CLI REPL: only a source textually containing
			// #. pays for the marker read; each form's markers resolve just before it
			// runs. The COMPILE buttons keep the error-mode read (frontend below): their
			// pipeline has no macro-time marker resolution pass.
			boolean markers = source.contains("#.");
			List<LispVal> exprs = markers
					? LispReader.readAllWithReadEvalMarkers(source, am.ik.rontolisp.reader.Features.INTERPRETER)
					: LispReader.readAllFromString(source);
			// The last form's values are echoed one per line: (floor 10 3) echoes 3
			// then 1. Deliberately only the LAST form, unlike the CLI REPL (which
			// echoes every form, as SBCL does reading them one at a time): this same
			// entry point backs the documentation site's "Run" cells, whose blocks are
			// a setup-plus-expression whose FINAL value is the annotated one.
			// See .kb/multiple-values.md.
			List<LispVal> values = List.of();
			for (int i = 0; i < exprs.size(); i++) {
				LispVal expr = markers ? evaluator.resolveReadTimeEvalInCode(exprs.get(i)) : exprs.get(i);
				if (i == exprs.size() - 1) {
					values = evaluator.evalValues(expr);
				}
				else {
					evaluator.eval(expr);
				}
			}
			replOut.flush();
			StringBuilder echoed = new StringBuilder(replBuffer.toString());
			for (int i = 0; i < values.size(); i++) {
				echoed.append(i == 0 ? "" : "\n").append(values.get(i).print());
			}
			return echoed.toString();
		}
		catch (RuntimeException ex) {
			return ERROR_PREFIX + ex.getMessage();
		}
	}

	/** Compile {@code source} to a JVM class, returned as Base64. */
	static String compileJvm(String source, String className) {
		try {
			List<LispVal> program = frontend(source, Features.JVM, WitExportDirective.Backend.OTHER);
			String name = (className == null || className.isBlank()) ? "Main" : className;
			byte[] bytes = new JvmLispCompiler(name).compile(program);
			return Base64.getEncoder().encodeToString(bytes);
		}
		catch (RuntimeException ex) {
			return ERROR_PREFIX + ex.getMessage();
		}
	}

	/** Compile {@code source} to a WebAssembly module, returned as Base64. */
	static String compileWasm(String source) {
		try {
			// The playground emits a Preview 1 core module (no --component), so the world
			// is checked against the wasm-GC backend's rules.
			List<LispVal> program = frontend(source, Features.WASM, WitExportDirective.Backend.WASM_GC);
			byte[] bytes = new WasmLispCompiler().compile(program);
			return Base64.getEncoder().encodeToString(bytes);
		}
		catch (RuntimeException ex) {
			return ERROR_PREFIX + ex.getMessage();
		}
	}

	// The playground's compile-time frontend: the WIT interface bindings (a
	// rontolisp:wit-import directive becomes the defpackage + bindings it stands for --
	// first, because the package it declares must exist before anything resolves a call
	// site in it), then the library splices, then the WIT world check (a
	// rontolisp:wit-export directive is lowered into the wasm-export directives it stands
	// for -- without this the backends would meet the directive itself and report an
	// unhelpful "Cannot compile"), then the library tree-shake. It has no LoadInliner: the
	// browser resolves (load ...) at run time, against the same uploaded files a WIT file
	// is read from.
	private static List<LispVal> frontend(String source, Features features, WitExportDirective.Backend backend) {
		List<LispVal> read = WitImportInliner.inline(LispReader.readAllFromString(source, features), null, backend,
				uploads);
		List<LispVal> program = WitLibrary
			.process(UsocketLibrary.process(am.ik.rontolisp.eval.GrayStreamsLibrary.process(VecLibrary.process(
					LispPreludeLibrary.process(
							UrlLibrary.process(LinalgLibrary.process(JsonLibrary.process(read))), features)))));
		// The (boundp 'name) fold runs before the shake for the same reason it does in the
		// CLI: a guarded library constant is not a top-level definer until its probe is
		// decided, and the shaker cannot drop what it cannot see
		// (compiler/CompileTimeBoundp).
		return LibraryDefunPruner
			.prune(CompileTimeBoundp.fold(WitExportInliner.inline(program, null, backend, uploads), false, false));
	}

	@JS(args = { "fn" }, value = "globalThis.rontoEval = fn;")
	private static native void exportEval(Function<JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoCompileJvm = fn;")
	private static native void exportCompileJvm(BiFunction<JSString, JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoCompileWasm = fn;")
	private static native void exportCompileWasm(Function<JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoPutFile = fn;")
	private static native void exportPutFile(BiFunction<JSString, JSString, JSString> fn);

	public static void main(String[] args) {
		exportEval(source -> JSString.of(evalLine(source.asString())));
		exportCompileJvm((source, className) -> JSString.of(compileJvm(source.asString(), className.asString())));
		exportCompileWasm(source -> JSString.of(compileWasm(source.asString())));
		exportPutFile((name, content) -> JSString.of(putFile(name.asString(), content.asString())));
	}

}
