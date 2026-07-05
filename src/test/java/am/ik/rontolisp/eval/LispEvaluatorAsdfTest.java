package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter side of the limited ASDF subset: {@code asdf:defsystem} as a special
 * form and {@code asdf:load-system} as a runtime function over the {@code load}
 * machinery, with {@code .asd} files served by an in-memory {@link SourceLoader}.
 */
class LispEvaluatorAsdfTest {

	private static SourceLoader loaderOf(Map<String, String> files) {
		return path -> {
			String src = files.get(path);
			if (src == null) {
				throw new java.io.FileNotFoundException(path);
			}
			return src;
		};
	}

	private String run(String source, Map<String, String> files, List<String> systemPath) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSourceLoader(loaderOf(files));
		evaluator.setSystemPath(systemPath);
		for (LispVal expr : LispReader.readAllFromString(source)) {
			evaluator.eval(expr);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void loadSystemLoadsComponentsInDependencyOrder() {
		String output = run("(asdf:load-system \"my-lib\") (print (my-lib:greet \"world\"))", Map.of(//
				"my-lib.asd", """
						(defsystem :my-lib
						  :components ((:file "main" :depends-on ("package"))
						               (:file "package")))""", //
				"package.lisp", "(defpackage :my-lib (:use :cl) (:export :greet))", //
				"main.lisp", """
						(in-package :my-lib)
						(defun greet (name) (concatenate 'string "Hello, " name))"""), List.of());
		assertThat(output).contains("\"Hello, world\"");
	}

	@Test
	void loadSystemAcceptsAComputedNameAtRuntime() {
		String output = run("""
				(defvar *sys* "my-lib")
				(asdf:load-system *sys*)
				(print (f))""", Map.of("my-lib.asd", "(defsystem :my-lib :components ((:file \"main\")))", //
				"main.lisp", "(defun f () 42)"), List.of());
		assertThat(output).contains("42");
	}

	@Test
	void loadSystemIsIdempotent() {
		String output = run("""
				(asdf:load-system :my-lib)
				(asdf:load-system :my-lib)""",
				Map.of("my-lib.asd", "(defsystem :my-lib :components ((:file \"main\")))", //
						"main.lisp", "(print \"loaded\")"),
				List.of());
		// The component file runs once: the second load-system is a no-op.
		assertThat(output.split("loaded", -1)).hasSize(2);
	}

	@Test
	void loadSystemLoadsDependencySystemsFirst() {
		String output = run("(asdf:load-system :app)", Map.of(//
				"app.asd", "(defsystem :app :depends-on (:base) :components ((:file \"app\")))", //
				"base.asd", "(defsystem :base :components ((:file \"base\")))", //
				"base.lisp", "(print \"base\")", //
				"app.lisp", "(print \"app\")"), List.of());
		assertThat(output.indexOf("base")).isLessThan(output.indexOf("app"));
	}

	@Test
	void loadSystemSearchesTheSystemPath() {
		String output = run("(asdf:load-system :lib)", Map.of(//
				"registry/lib.asd", "(defsystem :lib :components ((:file \"main\")))", //
				"registry/main.lisp", "(print \"from registry\")"), List.of("registry"));
		assertThat(output).contains("from registry");
	}

	@Test
	void componentFilesResolveAgainstTheAsdDirectory() {
		String output = run("(asdf:load-system :lib)", Map.of(//
				"registry/lib.asd", """
						(defsystem :lib
						  :components ((:module "src" :components ((:file "main")))))""", //
				"registry/src/main.lisp", "(print \"nested\")"), List.of("registry"));
		assertThat(output).contains("nested");
	}

	@Test
	void inlineDefsystemRegistersForALaterLoadSystem() {
		String output = run("""
				(asdf:defsystem :inline-sys :components ((:file "main")))
				(asdf:load-system :inline-sys)""", Map.of("main.lisp", "(print \"inline\")"), List.of());
		assertThat(output).contains("inline");
	}

	@Test
	void missingSystemNamesTheTriedPathsAndTheRegistryOptions() {
		assertThatThrownBy(() -> run("(asdf:load-system :nope)", Map.of(), List.of("registry")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("system 'nope' not found")
			.hasMessageContaining("--system-path");
	}

	@Test
	void circularSystemDependencyIsAHardError() {
		assertThatThrownBy(() -> run("(asdf:load-system :a)", Map.of(//
				"a.asd", "(defsystem :a :depends-on (:b))", //
				"b.asd", "(defsystem :b :depends-on (:a))"), List.of()))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Circular system :depends-on");
	}

	@Test
	void asdNotDefiningTheRequestedSystemIsAHardError() {
		assertThatThrownBy(() -> run("(asdf:load-system :lib)",
				Map.of("lib.asd", "(defsystem :something-else :components nil)"), List.of()))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("does not define system 'lib'");
	}

}
