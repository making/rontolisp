package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interop lowering ({@code .}, {@code ..}, {@code Class/member}, {@code Class.},
 * {@code new}) over the {@code java:} surface, on the interpreter and the JVM. The wasm
 * backends reject {@code java:} outright, so these cases cannot live in
 * {@code clojure-spec.yaml}.
 */
class ClojureInteropTest {

	@Test
	void instanceCallsOnStringsAnswerCoreValues() throws Exception {
		assertBothEqual("(println (.toUpperCase \"hi\"))", "HI\n");
		assertBothEqual("(println (. \"hi\" toUpperCase))", "HI\n");
		assertBothEqual("(println (. \"hi\" (toUpperCase)))", "HI\n");
		assertBothEqual("(println (. \"hi\" (substring 1)))", "i\n");
		assertBothEqual("(println (.. \"hi\" (toUpperCase) (substring 1)))", "I\n");
		assertBothEqual("(println (.length \"hi\"))", "2\n");
		assertBothEqual("(println (. \"hihi\" (indexOf \"i\")))", "1\n");
		assertBothEqual("(println (. \"hihi\" (indexOf \"z\")))", "-1\n");
	}

	@Test
	void staticsConstructorsAndFields() throws Exception {
		assertBothEqual("(println (Integer/parseInt \"42\"))", "42\n");
		assertBothEqual("(println (Math/max 3 7))", "7\n");
		assertBothEqual("(println (. Math max 3 7))", "7\n");
		assertBothEqual("(println (String. \"hi\"))", "hi\n");
		assertBothEqual("(println (new String \"hi\"))", "hi\n");
		assertBothEqual("(println (Integer/MAX_VALUE))", "2147483647\n");
		assertBothEqual("(println (.-x (java.awt.Point. 1 2)))", "1\n");
		assertBothEqual("(ns b05-imp (:import (java.awt Point))) (println (Point. 3 4))", "#<java java.awt.Point>\n");
	}

	@Test
	void hostObjectsChainThroughCalls() throws Exception {
		assertBothEqual("(println (.toString (. (StringBuilder. \"a\") (append \"b\"))))", "ab\n");
		assertBothEqual("(println (try (Integer/parseInt \"xx\") (catch Exception e \"bad\")))", "bad\n");
	}

	@Test
	void memfnCallsHostMethods() throws Exception {
		assertBothEqual("(println (.toString ((memfn append x) (StringBuilder. \"a\") \"b\")))", "ab\n");
		assertBothEqual("(println (map (memfn toString) [(StringBuilder. \"a\")]))", "(a)\n");
	}

	@Test
	void proxyImplementsASingleInterface() throws Exception {
		assertBothEqual("(println (.get (proxy [java.util.function.Supplier] [] (get [] 42))))", "42\n");
		assertBothEqual("(println (.get (proxy [java.util.function.Supplier] [] (get [] (+ 40 2)))))", "42\n");
		assertBothEqual("(println (let [p (proxy [java.util.function.Supplier] [] (get [] \"hi\"))] (.get p)))",
				"hi\n");
	}

	private static void assertBothEqual(String source, String expected) throws Exception {
		assertThat(interpret(source)).isEqualTo(expected);
		assertThat(runOnJvm(source)).isEqualTo(expected);
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-interop", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "test.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	@org.junit.jupiter.api.io.TempDir
	static java.nio.file.Path workDir;

	private static String runOnJvm(String program) throws Exception {
		String name = "ClojureInterop";
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null);
		java.nio.file.Files.write(workDir.resolve(name + ".class"), result.classBytes());
		for (var file : result.runtimeClasses().entrySet()) {
			java.nio.file.Path target = workDir.resolve(file.getKey());
			java.nio.file.Files.createDirectories(target.getParent());
			java.nio.file.Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] { workDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader()); var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-interop", () -> {
				try {
					Method main = loader.loadClass(name).getMethod("main", String[].class);
					main.invoke(null, (Object) new String[0]);
				}
				catch (ReflectiveOperationException ex) {
					throw new IllegalStateException(ex);
				}
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

}
