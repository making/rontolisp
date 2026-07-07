package am.ik.rontolisp.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.eval.QuicklispClient;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.eval.QuicklispTestSupport;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadInlinerTest {

	// An in-memory SourceLoader so the tests need no files on disk.
	private static SourceLoader loaderOf(Map<String, String> files) {
		return path -> {
			String src = files.get(path);
			if (src == null) {
				throw new java.io.FileNotFoundException(path);
			}
			return src;
		};
	}

	private static List<LispVal> inline(String source, Map<String, String> files) {
		return LoadInliner.inline(LispReader.readAllFromString(source), loaderOf(files));
	}

	@Test
	void inlinesTopLevelLoadInPlace() {
		List<LispVal> result = inline("(load \"core.lisp\") (print (f 5))",
				Map.of("core.lisp", "(defun f (x) (* x x))"));
		// (defun f ...) from the loaded file, then the original (print (f 5)).
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun f (x) (* x x))", "(print (f 5))");
	}

	@Test
	void inlineIsRecursive() {
		List<LispVal> result = inline("(load \"a.lisp\")",
				Map.of("a.lisp", "(load \"b.lisp\") (defun a () 1)", "b.lisp", "(defun b () 2)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun b nil 2)", "(defun a nil 1)");
	}

	@Test
	void resolvesRelativePathsAgainstTheLoadingFile() {
		// The entry source lives in proj/; a top-level (load "core.lisp") resolves to
		// proj/core.lisp, and the nested (load "common.lisp") inside it resolves relative
		// to core.lisp's directory (proj/), not against the process working directory.
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString("(load \"core.lisp\") (print (cube 3))"),
				loaderOf(Map.of("proj/core.lisp", "(load \"common.lisp\") (defun cube (x) (* x (sq x)))", //
						"proj/common.lisp", "(defun sq (x) (* x x))")),
				"proj");
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun sq (x) (* x x))",
				"(defun cube (x) (* x (sq x)))", "(print (cube 3))");
	}

	@Test
	void leavesNonLiteralLoadUntouched() {
		// A load with a computed argument is not a compile-time include.
		List<LispVal> result = inline("(load (concatenate 'string \"a\" \".lisp\"))", Map.of());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).startsWith("(load");
	}

	@Test
	void detectsCircularLoad() {
		assertThatThrownBy(
				() -> inline("(load \"a.lisp\")", Map.of("a.lisp", "(load \"b.lisp\")", "b.lisp", "(load \"a.lisp\")")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Circular load");
	}

	@Test
	void requireSplicesTheModuleFile() {
		List<LispVal> result = inline("(require :util) (print (u-sq 5))",
				Map.of("util.lisp", "(provide :util) (defun u-sq (x) (* x x))"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(quote util)", "(defun u-sq (x) (* x x))",
				"(print (u-sq 5))");
	}

	@Test
	void requireIsIdempotentAcrossDiamondDependencies() {
		// a.lisp and b.lisp both require utils; the provide inside utils.lisp marks the
		// module, so the second require is consumed without splicing again.
		List<LispVal> result = inline("(require :a) (require :b)",
				Map.of("a.lisp", "(provide :a) (require :utils) (defun a () (u 1))", //
						"b.lisp", "(provide :b) (require :utils) (defun b () (u 2))", //
						"utils.lisp", "(provide :utils) (defun u (x) x)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(quote a)", "(quote utils)", "(defun u (x) x)",
				"(defun a nil (u 1))", "(quote b)", "(quote utils)", "(defun b nil (u 2))");
	}

	@Test
	void provideFirstConsumesALaterRequire() {
		List<LispVal> result = inline("(provide :util) (require :util)", Map.of());
		assertThat(result.stream().map(LispVal::print)).containsExactly("(quote util)", "(quote util)");
	}

	@Test
	void requireAcceptsAnExplicitPathAndAllDesignatorSpellings() {
		Map<String, String> files = Map.of("lib/util-v2.lisp", "(provide :util) (defun u () 1)", //
				"util.lisp", "(provide :util) (defun u () 2)");
		List<LispVal> result = inline("(require :util \"lib/util-v2.lisp\") (require 'util) (require \"util\")", files);
		// The explicit path wins; the later requires (quoted-symbol and string
		// designators) see the module as provided and are consumed.
		assertThat(result.stream().map(LispVal::print)).containsExactly("(quote util)", "(defun u nil 1)",
				"(quote util)", "(quote util)");
	}

	@Test
	void requireMissingModuleFileThrows() {
		assertThatThrownBy(() -> inline("(require :nope)", Map.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("require: cannot read file nope.lisp");
	}

	@Test
	void requireNonLiteralModuleNameThrows() {
		// Unlike load, require cannot be deferred to runtime (the compiled runtime
		// reader does not know it), so a non-literal designator is a hard error.
		assertThatThrownBy(() -> inline("(require (concatenate 'string \"u\" \"til\"))", Map.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("literal module name");
		assertThatThrownBy(() -> inline("(provide some-variable)", Map.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("literal module name");
	}

	@Test
	void requireResolvesRelativeToTheRequiringFile() {
		// The entry requires proj/core, whose own require of util resolves against
		// proj/, not the working directory.
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString("(require :core)"),
				loaderOf(Map.of("proj/core.lisp", "(provide :core) (require :util) (defun c () (u))", //
						"proj/util.lisp", "(provide :util) (defun u () 42)")),
				"proj");
		assertThat(result.stream().map(LispVal::print)).containsExactly("(quote core)", "(quote util)",
				"(defun u nil 42)", "(defun c nil (u))");
	}

	@Test
	void requiredProgramCompilesAndRunsOnJvm() throws Exception {
		List<LispVal> program = inline("(require :util) (require :util) (print (u-sq 7))",
				Map.of("util.lisp", "(provide :util) (defun u-sq (x) (* x x))"));
		byte[] classBytes = new JvmLispCompiler("TestRequire").compile(program);
		assertThat(runMain(classBytes, "TestRequire")).isEqualTo("49");
	}

	@Test
	void loadSystemSplicesTheComponentFilesInDependencyOrder() {
		List<LispVal> result = inline("(asdf:load-system \"my-lib\") (print (my-lib:greet))", Map.of("my-lib.asd", """
				(defsystem :my-lib
				  :components ((:file "main" :depends-on ("package"))
				               (:file "package")))""", //
				"package.lisp", "(defpackage :my-lib (:use :cl) (:export :greet))", //
				"main.lisp", "(in-package :my-lib) (defun greet () 1)"));
		// main.lisp selects a package, so it is bracketed with package save/restore
		// markers (package.lisp has no in-package, so it is spliced verbatim).
		assertThat(result.stream().map(LispVal::print)).containsExactly(
				"(defpackage :my-lib (:use :cl) (:export :greet))", "(%push-package)", "(in-package :my-lib)",
				"(defun greet nil 1)", "(%pop-package)", "(quote my-lib)", "(print (my-lib:greet))");
	}

	@Test
	void loadSystemIsIdempotentAndLoadsDependencySystemsFirst() {
		List<LispVal> result = inline("(asdf:load-system :app) (asdf:load-system :base)",
				Map.of("app.asd", "(defsystem :app :depends-on (:base) :components ((:file \"app\")))", //
						"base.asd", "(defsystem :base :components ((:file \"base\")))", //
						"base.lisp", "(defun base () 1)", //
						"app.lisp", "(defun app () (base))"));
		// base splices once, before app; the second load-system is consumed.
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun base nil 1)", "(defun app nil (base))",
				"(quote app)", "(quote base)");
	}

	@Test
	void inlineDefsystemIsConsumedAndRegisteredForALaterLoadSystem() {
		List<LispVal> result = inline("""
				(asdf:defsystem :inline-sys :components ((:file "main")))
				(asdf:load-system :inline-sys)""", Map.of("main.lisp", "(defun f () 42)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(quote inline-sys)", "(defun f nil 42)",
				"(quote inline-sys)");
	}

	@Test
	void systemComponentsResolveAgainstTheAsdDirectoryAndSearchTheSystemPath() {
		// The .asd is found on the system path, not next to the entry source, and its
		// component (including a nested module) resolves against the .asd's directory.
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString("(asdf:load-system :lib)"),
				loaderOf(Map.of("registry/lib.asd",
						"(defsystem :lib :components ((:module \"src\" :components ((:file \"main\")))))",
						"registry/src/main.lisp", "(defun f () 1)")),
				"proj", List.of("registry"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun f nil 1)", "(quote lib)");
	}

	@Test
	void splicedComponentFilesMayLoadAndRequireTheirOwnCompanions() {
		List<LispVal> result = inline("(asdf:load-system :lib)",
				Map.of("lib.asd", "(defsystem :lib :components ((:file \"main\")))", //
						"main.lisp", "(load \"helper.lisp\") (defun f () (h))", //
						"helper.lisp", "(defun h () 7)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun h nil 7)", "(defun f nil (h))",
				"(quote lib)");
	}

	@Test
	void loadSystemNonLiteralNameThrows() {
		// Unlike the interpreter's runtime function, the compile path cannot evaluate a
		// computed system name.
		assertThatThrownBy(() -> inline("(asdf:load-system (concatenate 'string \"l\" \"ib\"))", Map.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("literal system name");
	}

	@Test
	void loadSystemMissingSystemNamesTheTriedPaths() {
		assertThatThrownBy(() -> inline("(asdf:load-system :nope)", Map.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("system 'nope' not found");
	}

	@Test
	void circularSystemDependencyThrows() {
		assertThatThrownBy(() -> inline("(asdf:load-system :a)",
				Map.of("a.asd", "(defsystem :a :depends-on (:b))", "b.asd", "(defsystem :b :depends-on (:a))")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Circular system :depends-on");
	}

	@Test
	void loadSystemProgramCompilesAndRunsOnJvm() throws Exception {
		List<LispVal> program = inline("(asdf:load-system :sq) (print (sq 7))",
				Map.of("sq.asd", "(defsystem :sq :components ((:file \"sq\")))", //
						"sq.lisp", "(defun sq (x) (* x x))"));
		byte[] classBytes = new JvmLispCompiler("TestAsdf").compile(program);
		assertThat(runMain(classBytes, "TestAsdf")).isEqualTo("49");
	}

	@Test
	void loadSystemDoesNotLeakTheCurrentPackageToTheCallerOnJvm() throws Exception {
		// A component file's (in-package :my-lib) must not leak past the load: a defun
		// AFTER the load-system (referenced by unqualified quoted symbol) must resolve in
		// the caller's package, the shape of examples/net/http-handler-cl-who.lisp.
		// Without
		// the package save/restore markers, `handle` would be defined under my-lib and
		// the
		// quoted 'handle lookup would fail (see .todo/83).
		List<LispVal> program = UserMacroExpander.expand(inline("""
				(asdf:load-system :my-lib)
				(defun handle () 42)
				(print (funcall (symbol-function 'handle)))""", Map.of(//
				"my-lib.asd", """
						(defsystem :my-lib
						  :components ((:file "package") (:file "main" :depends-on ("package"))))""", //
				"package.lisp", "(defpackage :my-lib (:use :cl) (:export :greet))", //
				"main.lisp", "(in-package :my-lib) (defun greet () 1)")));
		byte[] classBytes = new JvmLispCompiler("TestPkgLeak").compile(program);
		assertThat(runMain(classBytes, "TestPkgLeak")).isEqualTo("42");
	}

	// --- ql:quickload (compile-time download + asdf splice) ---

	private static final String QL_MYLIB_URL = "http://fake.quicklisp/archive/mylib-1.0.tar.gz";

	private static QuicklispClient quicklispClient(Path home, String componentBody) {
		return new QuicklispClient(home, QuicklispTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + QL_MYLIB_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(QL_MYLIB_URL, QuicklispTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", componentBody)))));
	}

	@Test
	void quickloadDownloadsAndSplicesTheComponentFiles(@TempDir Path home) {
		// The downloaded system's component file is spliced in exactly like
		// asdf:load-system (the download happens here, at compile time).
		List<LispVal> result = LoadInliner.inline(
				LispReader.readAllFromString("(ql:quickload \"mylib\") (print (mylib-answer))"),
				SourceLoader.fileSystem(), null, List.of(), Features.INTERPRETER,
				quicklispClient(home, "(defun mylib-answer () 42)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun mylib-answer nil 42)", "(quote mylib)",
				"(print (mylib-answer))");
	}

	@Test
	void quickloadProgramCompilesAndRunsOnJvm(@TempDir Path home) throws Exception {
		List<LispVal> program = LoadInliner.inline(
				LispReader.readAllFromString("(ql:quickload \"mylib\") (print (mylib-answer))"),
				SourceLoader.fileSystem(), null, List.of(), Features.JVM,
				quicklispClient(home, "(defun mylib-answer () (* 6 7))"));
		byte[] classBytes = new JvmLispCompiler("TestQuickload").compile(program);
		assertThat(runMain(classBytes, "TestQuickload")).isEqualTo("42");
	}

	@Test
	void quickloadNonLiteralNameThrows(@TempDir Path home) {
		assertThatThrownBy(() -> LoadInliner.inline(
				LispReader.readAllFromString("(ql:quickload (concatenate 'string \"my\" \"lib\"))"),
				SourceLoader.fileSystem(), null, List.of(), Features.INTERPRETER,
				quicklispClient(home, "(defun mylib-answer () 42)")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("literal system name");
	}

	@Test
	void loadedFilesAreReadWithTheGivenFeatures() throws Exception {
		// The compile path passes the target backend's feature set, so #+/#-
		// conditionals inside loaded files (and .asd files) select per-backend code.
		List<LispVal> program = LoadInliner.inline(
				LispReader.readAllFromString("(asdf:load-system :lib) (print (which))", Features.JVM),
				loaderOf(Map.of("lib.asd",
						"(defsystem :lib :components ((:file \"jvm\" :if-feature :rontolisp-jvm)"
								+ " (:file \"wasm\" :if-feature :rontolisp-wasm)))", //
						"jvm.lisp", "#+rontolisp-jvm (defun which () \"jvm\")")),
				null, List.of(), Features.JVM);
		byte[] classBytes = new JvmLispCompiler("TestFeatures").compile(program);
		assertThat(runMain(classBytes, "TestFeatures")).isEqualTo("\"jvm\"");
	}

	@Test
	void splitProgramCompilesAndRunsOnJvm() throws Exception {
		// This is the regression: before compile-time inlining, a console driver that
		// (load "core.lisp")s its functions failed to compile on the JVM backend because
		// the static defun-collection pass never saw the loaded definitions.
		List<LispVal> program = inline("(load \"core.lisp\") (print (square 7))",
				Map.of("core.lisp", "(defun square (x) (* x x))"));
		byte[] classBytes = new JvmLispCompiler("Test").compile(program);
		assertThat(runMain(classBytes, "Test")).isEqualTo("49");
	}

	// Defines the compiled class from its bytes and runs main, capturing stdout.
	private static String runMain(byte[] classBytes, String name) throws Exception {
		ClassLoader loader = new ClassLoader(LoadInlinerTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				return super.findClass(n);
			}
		};
		java.lang.reflect.Method main = loader.loadClass(name).getMethod("main", String[].class);
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		java.io.PrintStream oldOut = System.out;
		System.setOut(new java.io.PrintStream(baos));
		try {
			main.invoke(null, (Object) new String[0]);
		}
		finally {
			System.setOut(oldOut);
		}
		return baos.toString().trim();
	}

}
