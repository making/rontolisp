package am.ik.rontolisp.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.eval.DistTestSupport;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
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
		// (defun F ...) from the loaded file, bracketed with the file's load-context
		// marker pair (what *load-pathname* / *load-truename* lower from), then the
		// original (print (F 5)).
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-FILE \"core.lisp\" \"core.lisp\")",
				"(DEFUN F (X) (* X X))", "(%END-FILE)", "(PRINT (F 5))");
	}

	@Test
	void inlineIsRecursive() {
		List<LispVal> result = inline("(load \"a.lisp\")",
				Map.of("a.lisp", "(load \"b.lisp\") (defun a () 1)", "b.lisp", "(defun b () 2)"));
		// The load-context brackets nest with the loads.
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-FILE \"a.lisp\" \"a.lisp\")",
				"(%BEGIN-FILE \"b.lisp\" \"b.lisp\")", "(DEFUN B NIL 2)", "(%END-FILE)", "(DEFUN A NIL 1)",
				"(%END-FILE)");
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
		// The load-context marker carries both spellings: the path the load was CALLED
		// with (*load-pathname*) and the one it resolved to (*load-truename*).
		assertThat(result.stream().map(LispVal::print)).containsExactly(
				"(%BEGIN-FILE \"core.lisp\" \"proj/core.lisp\")", "(%BEGIN-FILE \"common.lisp\" \"proj/common.lisp\")",
				"(DEFUN SQ (X) (* X X))", "(%END-FILE)", "(DEFUN CUBE (X) (* X (SQ X)))", "(%END-FILE)",
				"(PRINT (CUBE 3))");
	}

	@Test
	void rebasesAWitImportPathWrittenInALoadedFile() {
		// A wit-import names its .wit relative to the file that WRITES it, like load. The
		// splice flattens both files into one program, though, and the later
		// WitImportInliner has only the ENTRY's directory left to resolve against -- so
		// the path a loaded library wrote is rewritten into that frame here. Without
		// this,
		// a library one directory over (examples/browser/webgl-common/gl.lisp naming its
		// own gl.wit) would send every consumer looking for the .wit beside ITSELF.
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString("(require :gl \"../common/gl.lisp\")"),
				loaderOf(Map.of("common/gl.lisp", "(provide :gl) (rontolisp:wit-import \"gl.wit\" :interface gl)")),
				"demo");
		assertThat(result.stream().map(LispVal::print))
			.contains("(RONTOLISP:WIT-IMPORT \"../common/gl.wit\" :INTERFACE GL)");
	}

	@Test
	void leavesAWitImportInTheEntrySourceExactlyAsWritten() {
		// The entry is already in the frame the path is resolved against, so it is passed
		// through untouched -- an error still quotes the path its author typed.
		List<LispVal> result = LoadInliner.inline(
				LispReader.readAllFromString("(rontolisp:wit-import \"wit/gl.wit\" :interface gl)"), loaderOf(Map.of()),
				"demo");
		assertThat(result.stream().map(LispVal::print))
			.containsExactly("(RONTOLISP:WIT-IMPORT \"wit/gl.wit\" :INTERFACE GL)");
	}

	@Test
	void leavesNonLiteralLoadUntouched() {
		// A load with a computed argument is not a compile-time include.
		List<LispVal> result = inline("(load (concatenate 'string \"a\" \".lisp\"))", Map.of());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).startsWith("(LOAD");
	}

	@Test
	void readerErrorInALoadedFileNamesTheOriginFile() {
		// A stray token in a spliced file must name the FILE it came from plus its
		// line/column -- not a line of the flattened entry program.
		assertThatThrownBy(() -> inline("(load \"core.lisp\")", Map.of("core.lisp", "(defun f (x)\n  #\\Nope)")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("core.lisp:2:")
			.hasMessageContaining("Unknown character name: #\\Nope");
	}

	@Test
	void readerErrorInAQuickloadedSystemNamesTheOriginFile(@TempDir Path home) {
		// The ql:quickload download splices the system's component file through the same
		// path as load, so a reader error inside it names the downloaded component file.
		assertThatThrownBy(() -> LoadInliner.inline(LispReader.readAllFromString("(ql:quickload \"mylib\")"),
				SourceLoader.fileSystem(), null, List.of(), Features.INTERPRETER,
				quicklispClient(home, "(defun mylib-answer () 42)\n  #\\Nope)")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("mylib.lisp:")
			.hasMessageContaining("Unknown character name: #\\Nope");
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
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-FILE \"util.lisp\" \"util.lisp\")",
				"(QUOTE UTIL)", "(DEFUN U-SQ (X) (* X X))", "(%END-FILE)", "(PRINT (U-SQ 5))");
	}

	@Test
	void requireIsIdempotentAcrossDiamondDependencies() {
		// a.lisp and b.lisp both require utils; the provide inside utils.lisp marks the
		// module, so the second require is consumed without splicing again.
		List<LispVal> result = inline("(require :a) (require :b)",
				Map.of("a.lisp", "(provide :a) (require :utils) (defun a () (u 1))", //
						"b.lisp", "(provide :b) (require :utils) (defun b () (u 2))", //
						"utils.lisp", "(provide :utils) (defun u (x) x)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-FILE \"a.lisp\" \"a.lisp\")",
				"(QUOTE A)", "(%BEGIN-FILE \"utils.lisp\" \"utils.lisp\")", "(QUOTE UTILS)", "(DEFUN U (X) X)",
				"(%END-FILE)", "(DEFUN A NIL (U 1))", "(%END-FILE)", "(%BEGIN-FILE \"b.lisp\" \"b.lisp\")", "(QUOTE B)",
				"(QUOTE UTILS)", "(DEFUN B NIL (U 2))", "(%END-FILE)");
	}

	@Test
	void provideFirstConsumesALaterRequire() {
		List<LispVal> result = inline("(provide :util) (require :util)", Map.of());
		assertThat(result.stream().map(LispVal::print)).containsExactly("(QUOTE UTIL)", "(QUOTE UTIL)");
	}

	@Test
	void requireAcceptsAnExplicitPathAndAllDesignatorSpellings() {
		Map<String, String> files = Map.of("lib/util-v2.lisp", "(provide :util) (defun u () 1)", //
				"util.lisp", "(provide :util) (defun u () 2)");
		List<LispVal> result = inline("(require :util \"lib/util-v2.lisp\") (require 'util) (require \"UTIL\")", files);
		// The explicit path wins; the later requires (quoted-symbol and string
		// designators) see the module as provided and are consumed.
		assertThat(result.stream().map(LispVal::print)).containsExactly(
				"(%BEGIN-FILE \"lib/util-v2.lisp\" \"lib/util-v2.lisp\")", "(QUOTE UTIL)", "(DEFUN U NIL 1)",
				"(%END-FILE)", "(QUOTE UTIL)", "(QUOTE UTIL)");
	}

	@Test
	void requireMissingModuleFileThrows() {
		assertThatThrownBy(() -> inline("(require :nope)", Map.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("REQUIRE: cannot read file nope.lisp");
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
		assertThat(result.stream().map(LispVal::print)).containsExactly(
				"(%BEGIN-FILE \"core.lisp\" \"proj/core.lisp\")", "(QUOTE CORE)",
				"(%BEGIN-FILE \"util.lisp\" \"proj/util.lisp\")", "(QUOTE UTIL)", "(DEFUN U NIL 42)", "(%END-FILE)",
				"(DEFUN C NIL (U))", "(%END-FILE)");
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
		// The system's forms are bracketed with provenance markers (the tree-shaker's
		// only record of which forms came from a library) and each FILE with its
		// load-context pair; main.lisp additionally selects a package, so it is
		// bracketed with package save/restore markers inside its own file bracket
		// (package.lisp has no in-package, so it gets no package markers).
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-SYSTEM \"my-lib\")",
				"(%BEGIN-FILE \"package.lisp\" \"package.lisp\")", "(DEFPACKAGE :MY-LIB (:USE :CL) (:EXPORT :GREET))",
				"(%END-FILE)", "(%BEGIN-FILE \"main.lisp\" \"main.lisp\")", "(%PUSH-PACKAGE)", "(IN-PACKAGE :MY-LIB)",
				"(DEFUN GREET NIL 1)", "(%POP-PACKAGE)", "(%END-FILE)", "(%END-SYSTEM)", "(QUOTE my-lib)",
				"(PRINT (MY-LIB:GREET))");
	}

	@Test
	void loadSystemIsIdempotentAndLoadsDependencySystemsFirst() {
		List<LispVal> result = inline("(asdf:load-system :app) (asdf:load-system :base)",
				Map.of("app.asd", "(defsystem :app :depends-on (:base) :components ((:file \"app\")))", //
						"base.asd", "(defsystem :base :components ((:file \"base\")))", //
						"base.lisp", "(defun base () 1)", //
						"app.lisp", "(defun app () (base))"));
		// base splices once, before app; the second load-system is consumed. base's
		// provenance bracket nests inside app's, so the tree-shaker attributes each defun
		// to the system whose file it came from.
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-SYSTEM \"app\")",
				"(%BEGIN-SYSTEM \"base\")", "(%BEGIN-FILE \"base.lisp\" \"base.lisp\")", "(DEFUN BASE NIL 1)",
				"(%END-FILE)", "(%END-SYSTEM)", "(%BEGIN-FILE \"app.lisp\" \"app.lisp\")", "(DEFUN APP NIL (BASE))",
				"(%END-FILE)", "(%END-SYSTEM)", "(QUOTE app)", "(QUOTE base)");
	}

	@Test
	void inlineDefsystemIsConsumedAndRegisteredForALaterLoadSystem() {
		List<LispVal> result = inline("""
				(asdf:defsystem :inline-sys :components ((:file "main")))
				(asdf:load-system :inline-sys)""", Map.of("main.lisp", "(defun f () 42)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(QUOTE inline-sys)",
				"(%BEGIN-SYSTEM \"inline-sys\")", "(%BEGIN-FILE \"main.lisp\" \"main.lisp\")", "(DEFUN F NIL 42)",
				"(%END-FILE)", "(%END-SYSTEM)", "(QUOTE inline-sys)");
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
		// A COMPONENT is loaded by its resolved path, so both halves of its load-context
		// marker are that path -- which is what asdf:component-pathname answers for it.
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-SYSTEM \"lib\")",
				"(%BEGIN-FILE \"registry/src/main.lisp\" \"registry/src/main.lisp\")", "(DEFUN F NIL 1)", "(%END-FILE)",
				"(%END-SYSTEM)", "(QUOTE lib)");
	}

	@Test
	void splicedComponentFilesMayLoadAndRequireTheirOwnCompanions() {
		List<LispVal> result = inline("(asdf:load-system :lib)",
				Map.of("lib.asd", "(defsystem :lib :components ((:file \"main\")))", //
						"main.lisp", "(load \"helper.lisp\") (defun f () (h))", //
						"helper.lisp", "(defun h () 7)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(%BEGIN-SYSTEM \"lib\")",
				"(%BEGIN-FILE \"main.lisp\" \"main.lisp\")", "(%BEGIN-FILE \"helper.lisp\" \"helper.lisp\")",
				"(DEFUN H NIL 7)", "(%END-FILE)", "(DEFUN F NIL (H))", "(%END-FILE)", "(%END-SYSTEM)", "(QUOTE lib)");
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
	void nestedLoadSystemOfASplicedSystemAnswersNilOnJvm() throws Exception {
		// lack's find-package-or-load shape: a load-system nested in a defun. The
		// reference splices the asdf runtime (AsdfRuntimeLibrary), whose
		// asdf:load-system defun answers nil for the already-spliced system -- and the
		// call-time error for anything else.
		List<LispVal> program = UserMacroExpander.expand(inline("""
				(asdf:load-system :sq)
				(defun probe () (asdf:load-system "sq" :verbose nil))
				(print (null (probe)))
				(print (sq 7))""", Map.of("sq.asd", "(defsystem :sq :components ((:file \"sq\")))", //
				"sq.lisp", "(defun sq (x) (* x x))")));
		byte[] classBytes = new JvmLispCompiler("TestNestedLoad").compile(program);
		assertThat(runMain(classBytes, "TestNestedLoad").lines().map(String::trim)).containsExactly("T", "49");
	}

	@Test
	void loadSystemDoesNotLeakTheCurrentPackageToTheCallerOnJvm() throws Exception {
		// A component file's (in-package :my-lib) must not leak past the load: a defun
		// AFTER the load-system (referenced by unqualified quoted symbol) must resolve in
		// the caller's package, the shape of examples/net/http-handler-cl-who.lisp.
		// Without the package save/restore markers, `handle` would be defined under
		// my-lib and the quoted 'handle lookup would fail.
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

	// --- built-in systems (BuiltinSystems: "usocket") ---

	private static long countUsocketSocketConnectDefuns(List<LispVal> program) {
		return program.stream()
			.map(LispVal::print)
			.filter(printed -> printed.startsWith("(DEFUN USOCKET:SOCKET-CONNECT "))
			.count();
	}

	@Test
	void loadSystemSplicesBuiltinUsocketWithoutAnAsdFile() {
		// "usocket" is a built-in system: the embedded library is spliced, no
		// usocket.asd is looked up (the empty file map would throw if it were), and a
		// duplicate load-system is a no-op.
		List<LispVal> result = inline("""
				(asdf:load-system "usocket")
				(asdf:load-system "usocket")
				(print (usocket:socket-stream 42))
				""", Map.of());
		assertThat(countUsocketSocketConnectDefuns(result)).isEqualTo(1);
		assertThat(result.getLast().print()).isEqualTo("(PRINT (USOCKET:SOCKET-STREAM 42))");
	}

	@Test
	void dependsOnBuiltinUsocketSplicesTheShimBeforeTheComponents() {
		List<LispVal> result = inline("(asdf:load-system :net-lib)", Map.of(//
				"net-lib.asd", """
						(defsystem :net-lib
						  :depends-on ("usocket")
						  :components ((:file "net")))""", //
				"net.lisp", "(defun stream-of (s) (USOCKET:SOCKET-STREAM s))"));
		assertThat(countUsocketSocketConnectDefuns(result)).isEqualTo(1);
		List<String> printed = result.stream().map(LispVal::print).toList();
		// The shim precedes the dependent component file.
		assertThat(printed.indexOf("(DEFUN STREAM-OF (S) (USOCKET:SOCKET-STREAM S))"))
			.isGreaterThan(printed.indexOf("(defparameter usocket:*wildcard-host* \"0.0.0.0\")"));
	}

	@Test
	void usocketProcessDoesNotSpliceASecondCopyAfterTheBuiltinSystemHook() {
		// A program that both load-systems usocket AND references usocket symbols
		// directly: the LoadInliner splices the library, and the outer
		// UsocketLibrary.process pre-pass must detect that and not prepend a second
		// copy (the dedup guard).
		List<LispVal> result = am.ik.rontolisp.eval.UsocketLibrary.process(inline("""
				(asdf:load-system "usocket")
				(print (usocket:socket-stream 42))
				""", Map.of()));
		assertThat(countUsocketSocketConnectDefuns(result)).isEqualTo(1);
	}

	@Test
	void quickloadBuiltinUsocketSkipsTheDownloader(@TempDir Path home) {
		// The dist knows nothing about usocket, so reaching the downloader would
		// throw; the built-in check must short-circuit before it.
		List<LispVal> result = LoadInliner.inline(
				LispReader.readAllFromString("(ql:quickload :usocket) (print (usocket:socket-stream 1))"),
				SourceLoader.fileSystem(), null, List.of(), Features.INTERPRETER,
				quicklispClient(home, "(defun mylib-answer () 42)"));
		assertThat(countUsocketSocketConnectDefuns(result)).isEqualTo(1);
	}

	@Test
	void builtinUsocketProgramCompilesAndRunsOnJvm() throws Exception {
		List<LispVal> program = UserMacroExpander.expand(inline("""
				(asdf:load-system "usocket")
				(print (usocket:socket-stream 42))
				""", Map.of()));
		byte[] classBytes = new JvmLispCompiler("TestUsocketSystem").compile(program);
		assertThat(runMain(classBytes, "TestUsocketSystem")).isEqualTo("42");
	}

	// --- ql:quickload (compile-time download + asdf splice) ---

	private static final String QL_MYLIB_URL = "http://fake.quicklisp/archive/mylib-1.0.tar.gz";

	private static DistClient quicklispClient(Path home, String componentBody) {
		return new DistClient(home, DistTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + QL_MYLIB_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(QL_MYLIB_URL, DistTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", componentBody)))));
	}

	private static final String UL_FRESH_URL = "http://fake.ultralisp/archive/fresh-1.0.tar.gz";

	@Test
	void installDistIsConsumedAtCompileTimeAndTheQuickloadBelowItUsesTheDist(@TempDir Path home) {
		// The system exists only in the second dist, so this splices at all only because
		// the install-dist ABOVE it was applied while inlining -- the compiled program
		// downloads nothing, so a run-time install would be too late.
		DistClient client = new DistClient(home, DistTestSupport.dists(//
				DistTestSupport.quicklisp("", "", Map.of()), //
				DistTestSupport.ultralisp(//
						"fresh fresh fresh\n", //
						"fresh " + UL_FRESH_URL + " 100 md5 sha1 fresh-1.0 fresh\n", //
						Map.of(UL_FRESH_URL, DistTestSupport.tarGz(Map.of(//
								"fresh-1.0/fresh.asd", "(defsystem \"fresh\" :components ((:file \"fresh\")))", //
								"fresh-1.0/fresh.lisp", "(defun fresh-answer () 42)"))))));

		List<LispVal> result = LoadInliner.inline(
				LispReader.readAllFromString(
						"(ql-dist:install-dist \"ultralisp\") (ql:quickload \"fresh\") (print (fresh-answer))"),
				SourceLoader.fileSystem(), null, List.of(), Features.INTERPRETER, client);

		assertThat(result.stream().map(LispVal::print).filter(form -> !form.startsWith("(%BEGIN-FILE ")))
			.containsExactly("\"ultralisp\"", "(%BEGIN-SYSTEM \"fresh\")", "(DEFUN FRESH-ANSWER NIL 42)", "(%END-FILE)",
					"(%END-SYSTEM)", "(QUOTE fresh)", "(PRINT (FRESH-ANSWER))");
	}

	@Test
	void quickloadDownloadsAndSplicesTheComponentFiles(@TempDir Path home) {
		// The downloaded system's component file is spliced in exactly like
		// asdf:load-system (the download happens here, at compile time).
		List<LispVal> result = LoadInliner.inline(
				LispReader.readAllFromString("(ql:quickload \"mylib\") (print (mylib-answer))"),
				SourceLoader.fileSystem(), null, List.of(), Features.INTERPRETER,
				quicklispClient(home, "(defun mylib-answer () 42)"));
		// The component's load-context marker carries the path inside the temporary
		// quicklisp cache, so it is matched rather than spelled out.
		assertThat(result.stream().map(LispVal::print).filter(form -> !form.startsWith("(%BEGIN-FILE ")))
			.containsExactly("(%BEGIN-SYSTEM \"mylib\")", "(DEFUN MYLIB-ANSWER NIL 42)", "(%END-FILE)", "(%END-SYSTEM)",
					"(QUOTE mylib)", "(PRINT (MYLIB-ANSWER))");
		assertThat(result.stream().map(LispVal::print).filter(form -> form.startsWith("(%BEGIN-FILE ")))
			.singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
			.endsWith("software/mylib-1.0/mylib.lisp\" \"" + home + "/quicklisp/software/mylib-1.0/mylib.lisp\")");
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
	void aDeclaredRontolispFeatureWidensTheBackendSetForThatSystemOnly() throws Exception {
		// :rontolisp-features is the static encoding of a .asd that pushes onto
		// *features* from an eval-when -- which the reader honors inside that .asd but
		// cannot carry to the component files it names. It must
		// WIDEN the backend set rather than replace it -- the backend feature is still
		// the compile target's -- and it must not reach a dependency, which declares its
		// own.
		List<LispVal> program = LoadInliner.inline(
				LispReader.readAllFromString("(asdf:load-system :app) (print (which)) (print (dep-which))",
						Features.JVM),
				loaderOf(Map.of("app.asd",
						"(defsystem :app :rontolisp-features (:app-fancy) :depends-on (\"dep\")"
								+ " :components ((:file \"main\") (:file \"fancy\" :if-feature :app-fancy)))", //
						"main.lisp", "#+(and rontolisp-jvm app-fancy) (defun which () \"jvm+fancy\")", //
						"fancy.lisp", "(defun app-fancy-marker () t)", //
						"dep.asd", "(defsystem :dep :components ((:file \"dep\")))", //
						"dep.lisp", "(defun dep-which () #+app-fancy \"leaked\" #-app-fancy \"clean\")")),
				null, List.of(), Features.JVM);
		byte[] classBytes = new JvmLispCompiler("TestSystemFeatures").compile(program);
		assertThat(runMain(classBytes, "TestSystemFeatures")).isEqualTo("\"jvm+fancy\"\n\"clean\"");
	}

	@Test
	void aDefsystemDependsOnEntryIsSplicedAheadOfTheDependenciesAndAnnouncesItsFeatures() throws Exception {
		// dexador.asd's opener: the .asd-read-time dependency resolves through the
		// ordinary ladder (trivial-features is a built-in shim), splices BEFORE
		// :depends-on, and its announced :unix is in force while this system's own
		// component files are read -- but not for the dependency, which declares its own.
		List<LispVal> program = LoadInliner.inline(
				LispReader.readAllFromString("(asdf:load-system :app) (print (which)) (print (dep-which))",
						Features.JVM),
				loaderOf(Map.of("app.asd",
						"(defsystem :app :defsystem-depends-on (\"trivial-features\") :depends-on (\"dep\")"
								+ " :components ((:file \"main\")))", //
						"main.lisp", "(defun which () #+unix \"unix\" #-unix \"unannounced\")", //
						"dep.asd", "(defsystem :dep :components ((:file \"dep\")))", //
						"dep.lisp", "(defun dep-which () #+unix \"leaked\" #-unix \"clean\")")),
				null, List.of(), Features.JVM);
		byte[] classBytes = new JvmLispCompiler("TestDefsystemDependsOn").compile(program);
		assertThat(runMain(classBytes, "TestDefsystemDependsOn")).isEqualTo("\"unix\"\n\"clean\"");
	}

	@Test
	void loadedFileWithReadEvalCompilesAndRunsOnJvm() throws Exception {
		// #. in a loaded file rides the marker read; the markers resolve in
		// UserMacroExpander against the macro-time evaluator, mirroring the runtime
		// loadFile.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander
			.expand(inline("(load \"lib.lisp\") (print (lib-get))",
					Map.of("lib.lisp", "(defvar +lib-val+ #.(+ 100 20 3)) (defun lib-get () +lib-val+)")));
		byte[] classBytes = new JvmLispCompiler("TestReadEval").compile(program);
		assertThat(runMain(classBytes, "TestReadEval")).isEqualTo("123");
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

	// -- compile-time pathname folding (CompileTimePathnameFolder via LoadInliner) --

	@Test
	void foldsMakePathnameLiteralArgs() {
		// A literal (make-pathname ...) with only self-evaluating args collapses to a
		// pathname VALUE so the compilers never see the primitive.
		List<LispVal> program = LispReader.readAllFromString(
				"(defparameter *dir* (make-pathname :directory '(:absolute \"a\" \"b\") :name nil :type nil))");
		List<LispVal> result = LoadInliner.inline(program, loaderOf(Map.of()));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(DEFPARAMETER *DIR* #P\"/a/b/\")");
	}

	@Test
	void foldsMergePathnamesStarOfTwoLiterals() {
		List<LispVal> program = LispReader
			.readAllFromString("(defparameter *p* (uiop:merge-pathnames* \"file.txt\" \"/abs/\"))");
		List<LispVal> result = LoadInliner.inline(program, loaderOf(Map.of()));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(DEFPARAMETER *P* #P\"/abs/file.txt\")");
	}

	@Test
	void foldsMergePathnamesStarAgainstRecordedDefparameter() {
		List<LispVal> program = LispReader.readAllFromString("""
				(defparameter *dir* "/base/")
				(defparameter *file* (uiop:merge-pathnames* *dir* "child.txt"))""");
		List<LispVal> result = LoadInliner.inline(program, loaderOf(Map.of()));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(DEFPARAMETER *DIR* \"/base/\")",
				"(DEFPARAMETER *FILE* #P\"/base/child.txt\")");
	}

	@Test
	void foldsFindSystemAndSystemSourceDirectory() {
		// Inline defsystem registers the system's baseDir; a literal find-system in
		// system-source-directory's designator position unwraps (find-system itself no
		// longer folds -- at run time it answers a metaobject), and the composition
		// reduces to the baseDir with a trailing slash.
		List<LispVal> program = LispReader.readAllFromString("""
				(asdf:defsystem :demo :components ((:file "main")))
				(defparameter *root* (asdf:system-source-directory (asdf:find-system 'demo nil)))""");
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:defsystem :demo :components ((:file "main")))
				(defparameter *root* (asdf:system-source-directory (asdf:find-system 'demo nil)))"""),
				loaderOf(Map.of("main.lisp", "(defun m () 1)")), "projects/demo");
		List<String> printed = result.stream().map(LispVal::print).toList();
		assertThat(printed).contains("(DEFPARAMETER *ROOT* \"projects/demo/\")");
	}

	@Test
	void foldsMakePathnameNestedInsideMergePathnamesStar() {
		// The uax-15 seed shape end-to-end: system-source-directory + find-system on the
		// currently-loading system merged with a make-pathname directory literal.
		List<LispVal> program = LispReader.readAllFromString("""
				(asdf:defsystem :demo :components ((:file "main")))
				(defparameter *data-dir*
				  (uiop:merge-pathnames*
				    (make-pathname :directory (list :relative "data") :name nil :type nil)
				    (asdf:system-source-directory (asdf:find-system 'demo nil))))""");
		List<LispVal> result = LoadInliner.inline(program, loaderOf(Map.of("main.lisp", "(defun m () 1)")),
				"projects/demo");
		assertThat(result.stream().map(LispVal::print)).contains("(DEFPARAMETER *DATA-DIR* #P\"projects/demo/data/\")");
	}

	@Test
	void leavesUnfoldableMergePathnamesStarIntact() {
		// A non-literal specified path with no defparameter binding stays as-is so a
		// runtime uiop:merge-pathnames* implementation (interpreter path) still handles
		// it.
		List<LispVal> program = LispReader.readAllFromString("(defparameter *p* (uiop:merge-pathnames* (foo) \"x\"))");
		List<LispVal> result = LoadInliner.inline(program, loaderOf(Map.of()));
		assertThat(result.stream().map(LispVal::print))
			.containsExactly("(DEFPARAMETER *P* (UIOP:MERGE-PATHNAMES* (FOO) \"x\"))");
	}

	@Test
	void bundlesLiteralUtf8WithOpenFile(@TempDir Path tempDir) throws Exception {
		// A (with-open-file (var <literal path> :external-format :utf-8) BODY) whose
		// path names a UTF-8 file on disk becomes a (with-input-from-string (var
		// "<contents>") BODY) so the compiled binary carries the data instead of
		// depending on the same absolute path at run time.
		Path dataFile = tempDir.resolve("payload.txt");
		java.nio.file.Files.writeString(dataFile, "hello world\n");
		String source = "(with-open-file (in \"" + dataFile.toAbsolutePath() + "\" :external-format :utf-8)"
				+ " (read-line in nil nil))";
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString(source), loaderOf(Map.of()));
		assertThat(result.stream().map(LispVal::print).findFirst().orElseThrow())
			.startsWith("(WITH-INPUT-FROM-STRING (IN \"hello world\n\")");
	}

	@Test
	void bundlesChunksLargeUtf8Files(@TempDir Path tempDir) throws Exception {
		// A file larger than the safe single-string threshold is split into chunks
		// reassembled at runtime via (concatenate 'string CHUNK1 CHUNK2 ...) so the
		// JVM's 65535 UTF-8 byte per-string constant-pool ceiling is not exceeded.
		Path dataFile = tempDir.resolve("big.txt");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 50_000; i++) {
			sb.append('a');
		}
		java.nio.file.Files.writeString(dataFile, sb.toString());
		String source = "(with-open-file (in \"" + dataFile.toAbsolutePath() + "\" :external-format :utf-8)"
				+ " (read-line in nil nil))";
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString(source), loaderOf(Map.of()));
		String printed = result.stream().map(LispVal::print).findFirst().orElseThrow();
		assertThat(printed).startsWith("(WITH-INPUT-FROM-STRING (IN (CONCATENATE (QUOTE STRING)");
	}

	@Test
	void doesNotBundleAFileTheProgramItselfWrites(@TempDir Path tempDir) throws Exception {
		// The one shape where bundling is NOT conservative: a program that opens the
		// same literal path for OUTPUT would otherwise read what was on disk when it
		// was COMPILED instead of what it just wrote (the append-then-read round trip).
		Path dataFile = tempDir.resolve("roundtrip.txt");
		java.nio.file.Files.writeString(dataFile, "stale\n");
		String path = dataFile.toAbsolutePath().toString();
		String source = "(with-open-file (out \"" + path + "\" :direction :output) (write-string \"fresh\" out))"
				+ "(with-open-file (in \"" + path + "\" :external-format :utf-8) (read-line in nil nil))";
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString(source), loaderOf(Map.of()));
		assertThat(result.stream().map(LispVal::print))
			.allSatisfy(form -> assertThat(form).doesNotContain("WITH-INPUT-FROM-STRING").doesNotContain("stale"));
	}

	@Test
	void leavesWithOpenFileUnchangedForMissingLiteralPath() {
		// Path is a literal but the file does not exist: pass through so the JVM path
		// still opens the file at run time with the folded literal.
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString(
				"(with-open-file (in \"/nonexistent/path.txt\" :external-format :utf-8) (read-line in nil nil))"),
				loaderOf(Map.of()));
		assertThat(result.stream().map(LispVal::print).findFirst().orElseThrow())
			.startsWith("(WITH-OPEN-FILE (IN \"/nonexistent/path.txt\"");
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
