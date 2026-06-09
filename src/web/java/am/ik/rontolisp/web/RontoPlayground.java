package am.ik.rontolisp.web;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
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

	private RontoPlayground() {
	}

	/** Interpret {@code source} in the persistent REPL environment. */
	static String evalLine(String source) {
		replBuffer.reset();
		try {
			List<LispVal> exprs = LispReader.readAllFromString(source);
			LispVal result = LispNil.INSTANCE;
			for (LispVal expr : exprs) {
				result = evaluator.eval(expr);
			}
			replOut.flush();
			return replBuffer.toString() + result.print();
		}
		catch (RuntimeException ex) {
			return ERROR_PREFIX + ex.getMessage();
		}
	}

	/** Compile {@code source} to a JVM class, returned as Base64. */
	static String compileJvm(String source, String className) {
		try {
			List<LispVal> program = LispReader.readAllFromString(source);
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
			List<LispVal> program = LispReader.readAllFromString(source);
			byte[] bytes = new WasmLispCompiler().compile(program);
			return Base64.getEncoder().encodeToString(bytes);
		}
		catch (RuntimeException ex) {
			return ERROR_PREFIX + ex.getMessage();
		}
	}

	@JS(args = { "fn" }, value = "globalThis.rontoEval = fn;")
	private static native void exportEval(Function<JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoCompileJvm = fn;")
	private static native void exportCompileJvm(BiFunction<JSString, JSString, JSString> fn);

	@JS(args = { "fn" }, value = "globalThis.rontoCompileWasm = fn;")
	private static native void exportCompileWasm(Function<JSString, JSString> fn);

	public static void main(String[] args) {
		exportEval(source -> JSString.of(evalLine(source.asString())));
		exportCompileJvm((source, className) -> JSString.of(compileJvm(source.asString(), className.asString())));
		exportCompileWasm(source -> JSString.of(compileWasm(source.asString())));
	}

}
