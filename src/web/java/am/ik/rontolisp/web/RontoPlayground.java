package am.ik.rontolisp.web;

import java.io.FileNotFoundException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.JsonLibrary;
import am.ik.rontolisp.compiler.CompileTimeBoundp;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.ExitLibrary;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.WitExportInliner;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.GeomLibrary;
import am.ik.rontolisp.eval.GgufLibrary;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.TokenizersLibrary;
import am.ik.rontolisp.eval.TorchLibrary;
import am.ik.rontolisp.eval.PlaygroundRepl;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.eval.UrlLibrary;
import am.ik.rontolisp.eval.UsocketLibrary;
import am.ik.rontolisp.eval.WitImportInliner;
import am.ik.rontolisp.eval.WitLibrary;
import am.ik.rontolisp.reader.Features;

/**
 * Web Image entry point that exposes the rontolisp interpreter and compilers to
 * the browser. The actual UI lives in {@code playground.html}; this class only
 * installs callables on the JavaScript global scope:
 * <ul>
 * <li>{@code rontoSetLanguage(name)} - pick the language every other call reads
 * ({@code "lisp"}, the default, or {@code "scheme"}); answers the picked language's
 * name.</li>
 * <li>{@code rontoEval(source)} - interpret source in a persistent REPL
 * environment and return the captured output plus the last form's values.</li>
 * <li>{@code rontoRunProgram(source, stdin)} / {@code rontoRunSession(source, stdin)} -
 * run source on a FRESH interpreter reading {@code stdin}: as a whole program
 * (its output), or form by form with every value echoed (the documentation site's
 * Scheme cells).</li>
 * <li>{@code rontoCompileJvm(source, className)} - compile to a JVM
 * {@code .class} file, returned as a Base64 string; a program whose class needs files
 * beside it (a bridge, a runtime class) answers {@value #FILES_PREFIX} and one
 * {@code path}/Base64 line pair per file instead, which the page packs into a jar.</li>
 * <li>{@code rontoCompileWasm(source)} - compile to a {@code .wasm} module,
 * returned as a Base64 string.</li>
 * <li>{@code rontoPutFile(name, content)} - add an uploaded file.</li>
 * </ul>
 * Errors are returned as a plain string prefixed with {@code "ERROR:"} so the
 * front-end can distinguish them from a result. What the interpreter shows is
 * {@link PlaygroundRepl}'s, which the JVM test suite runs.
 * <p>
 * This class depends on the GraalVM-only {@code org.graalvm.webimage.api} module
 * and is compiled only under the {@code web} Maven profile, which adds
 * {@code src/web/java} as a source directory.
 */
public final class RontoPlayground {

	private static final String ERROR_PREFIX = "ERROR:";

	/** Marks a JVM compile that produced more than the one class file. */
	private static final String FILES_PREFIX = "FILES:";

	/**
	 * In-memory files uploaded from the browser, keyed by file name. There is no filesystem
	 * in the WASM sandbox, so {@code (load "name.lisp")} resolves against this map.
	 */
	private static final Map<String, String> uploadedFiles = new LinkedHashMap<>();

	/**
	 * Reads an uploaded file. Backs {@code (load "x.lisp")} and a Scheme {@code include}
	 * on the REPL, and {@code (rontolisp:wit-export "w.wit")} on both compile paths, so a
	 * WIT world is dropped onto the page like any other companion file.
	 */
	private static final SourceLoader uploads = path -> {
		String content = uploadedFiles.get(path);
		if (content == null) {
			throw new FileNotFoundException(
					path + " (upload it first; available: " + String.join(", ", uploadedFiles.keySet()) + ")");
		}
		return content;
	};

	/** The page's persistent interpreter, whose pick every call reads. */
	private static final PlaygroundRepl repl = new PlaygroundRepl(uploads);

	private RontoPlayground() {
	}

	/** Register (or replace) an uploaded file so that {@code load} can resolve it. */
	static String putFile(String name, String content) {
		uploadedFiles.put(name, content);
		return String.join("\n", uploadedFiles.keySet());
	}

	/** Pick the language the REPL, the fresh runs and the compile buttons read. */
	static String setLanguage(String name) {
		try {
			return repl.pick(SourceLanguage.parse(name)).language().name();
		}
		catch (RuntimeException ex) {
			return ERROR_PREFIX + ex.getMessage();
		}
	}

	/** Interpret {@code source} in the persistent REPL environment. */
	static String evalLine(String source) {
		// #. resolves per form, as at the CLI REPL; the COMPILE buttons keep the
		// error-mode read (frontend below). (uiop:quit code) / (exit): there is no
		// process to end, so the run stops where it stood and its output is the answer.
		return shown(() -> repl.eval(source));
	}

	/** Run {@code source} as a whole program on a fresh interpreter. */
	static String runProgram(String source, String stdin) {
		return shown(() -> fresh(stdin).run(source));
	}

	/** Run {@code source} form by form on a fresh interpreter, echoing every value. */
	static String runSession(String source, String stdin) {
		return shown(() -> fresh(stdin).transcript(source));
	}

	private static PlaygroundRepl fresh(String stdin) {
		return new PlaygroundRepl(uploads, stdin).pick(repl.language());
	}

	private static String shown(Supplier<String> run) {
		try {
			return run.get();
		}
		catch (RuntimeException ex) {
			return ERROR_PREFIX + ex.getMessage();
		}
	}

	/**
	 * Compile {@code source} to a JVM class, returned as Base64 -- or, when the class
	 * needs files beside it ({@link JvmLispCompiler#runtimeClassFiles()}: a hash table's
	 * runtime, a shipped bridge), every file as a {@code path} line and a Base64 line
	 * after {@value #FILES_PREFIX}, the class first.
	 */
	static String compileJvm(String source, String className) {
		try {
			List<LispVal> program = frontend(source, Features.JVM, WitExportDirective.Backend.OTHER);
			String name = (className == null || className.isBlank()) ? "Main" : className;
			JvmLispCompiler compiler = new JvmLispCompiler(name);
			byte[] bytes = compiler.compile(program);
			Map<String, byte[]> beside = compiler.runtimeClassFiles();
			if (beside.isEmpty()) {
				return Base64.getEncoder().encodeToString(bytes);
			}
			StringBuilder files = new StringBuilder(FILES_PREFIX);
			files.append('\n').append(name).append(".class\n").append(Base64.getEncoder().encodeToString(bytes));
			for (Map.Entry<String, byte[]> file : new java.util.TreeMap<>(beside).entrySet()) {
				files.append('\n')
					.append(file.getKey())
					.append('\n')
					.append(Base64.getEncoder().encodeToString(file.getValue()));
			}
			return files.toString();
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
		// The strict (error-mode) read is deliberate here, not a leftover: this
		// reduced frontend has no marker-resolution pass, so a #. must be a read
		// error rather than a marker no pass resolves. A Scheme source is read with
		// the uploaded files its include / define-library name.
		List<LispVal> read = WitImportInliner.inline(repl.language().readStrict(source, features, uploads), null,
				backend, uploads);
		// objc:, appkit:, metal: and scene: need the Objective-C runtime on the machine
		// that RUNS the program, and the JVM output would need the binding's class
		// files, which the browser build does not carry: refuse both outputs by the
		// reference, as the CLI does for its WASM outputs. geom, which scene models
		// with, needs none of that and runs here like any other library.
		String objcReference = am.ik.rontolisp.eval.AppKitLibrary.firstObjcReference(read);
		if (objcReference != null) {
			throw new IllegalArgumentException("Cannot compile: " + objcReference
					+ " -- the objc:, appkit:, metal: and scene: packages are not available in the browser "
					+ "playground");
		}
		// JsonLibrary runs AFTER GeomLibrary here too: geom:read-gltf parses its JSON
		// chunk through rontolisp:json-parse, so the geom splice introduces the
		// reference (the readers are then pruned out again -- no filesystem here).
		List<LispVal> program = am.ik.rontolisp.eval.UnreadCharLibrary
			.process(WitLibrary.process(UsocketLibrary.process(am.ik.rontolisp.eval.GrayStreamsLibrary
				.process(VecLibrary.process(LispPreludeLibrary.process(
						UrlLibrary.process(JsonLibrary
							.process(LinalgLibrary.process(GeomLibrary.process(TorchLibrary
								.process(GgufLibrary.process(TokenizersLibrary
									.process(am.ik.rontolisp.eval.SchemeLibrary.process(read, features,
										am.ik.rontolisp.eval.SourceStandards.DEFAULT)))))))),
						features))))));
		// uiop:quit on the WASM button is exit.lisp's wasi_snapshot_preview1 proc_exit
		// binding (eval/ExitLibrary), like the CLI's Preview 1 output; a no-op for the
		// JVM button and for a program that never quits.
		program = ExitLibrary.process(program, backend, features);
		// The (boundp 'name) fold runs before the shake for the same reason it does in the
		// CLI: a guarded library constant is not a top-level definer until its probe is
		// decided, and the shaker cannot drop what it cannot see
		// (compiler/CompileTimeBoundp).
		return LibraryDefunPruner
			.prune(CompileTimeBoundp.fold(WitExportInliner.inline(program, null, backend, uploads), false, false));
	}

	@JS(args = { "fn" }, value = "globalThis.rontoEval = fn;")
	private static native void exportEval(Function<JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoSetLanguage = fn;")
	private static native void exportSetLanguage(Function<JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoRunProgram = fn;")
	private static native void exportRunProgram(BiFunction<JSString, JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoRunSession = fn;")
	private static native void exportRunSession(BiFunction<JSString, JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoCompileJvm = fn;")
	private static native void exportCompileJvm(BiFunction<JSString, JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoCompileWasm = fn;")
	private static native void exportCompileWasm(Function<JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoPutFile = fn;")
	private static native void exportPutFile(BiFunction<JSString, JSString, JSString> fn);

	public static void main(String[] args) {
		exportSetLanguage(name -> JSString.of(setLanguage(name.asString())));
		exportEval(source -> JSString.of(evalLine(source.asString())));
		exportRunProgram((source, stdin) -> JSString.of(runProgram(source.asString(), stdin.asString())));
		exportRunSession((source, stdin) -> JSString.of(runSession(source.asString(), stdin.asString())));
		exportCompileJvm((source, className) -> JSString.of(compileJvm(source.asString(), className.asString())));
		exportCompileWasm(source -> JSString.of(compileWasm(source.asString())));
		exportPutFile((name, content) -> JSString.of(putFile(name.asString(), content.asString())));
	}

}
