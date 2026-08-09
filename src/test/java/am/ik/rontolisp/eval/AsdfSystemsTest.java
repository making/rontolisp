package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class AsdfSystemsTest {

	// The component order the vendored alexandria.asd resolves to: package first, then
	// the topological sort of its per-file :depends-on, modules staying contiguous.
	private static final String[] ALEXANDRIA_FILES = { "alexandria-1/package.lisp", "alexandria-1/definitions.lisp",
			"alexandria-1/binding.lisp", "alexandria-1/strings.lisp", "alexandria-1/conditions.lisp",
			"alexandria-1/symbols.lisp", "alexandria-1/macros.lisp", "alexandria-1/hash-tables.lisp",
			"alexandria-1/control-flow.lisp", "alexandria-1/functions.lisp", "alexandria-1/lists.lisp",
			"alexandria-1/types.lisp", "alexandria-1/arrays.lisp", "alexandria-1/sequences.lisp",
			"alexandria-1/numbers.lisp", "alexandria-1/features.lisp", "alexandria-1/io.lisp",
			"alexandria-2/package.lisp", "alexandria-2/arrays.lisp", "alexandria-2/control-flow.lisp",
			"alexandria-2/sequences.lisp", "alexandria-2/lists.lisp" };

	private static LispVal form(String source) {
		return LispReader.readFromString(source);
	}

	private static AsdfSystems.LispSystem parse(String source) {
		return AsdfSystems.parseDefsystem(form(source), null, Features.INTERPRETER);
	}

	/** What {@link #parseCapturingStderr} saw on {@code System.err}. */
	private String stderr = "";

	/**
	 * Parses a {@code .asd} with {@code System.err} redirected: the consumer-decides rule
	 * for {@code #.} markers is as much about what is NOT printed as about what parses,
	 * and only a capture can pin "says nothing".
	 */
	private List<AsdfSystems.LispSystem> parseCapturingStderr(String source, String asdPath) {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(captured));
		try {
			return AsdfSystems.parseAsdSource(source, asdPath, Features.INTERPRETER);
		}
		finally {
			System.setErr(oldErr);
			this.stderr = captured.toString();
		}
	}

	private static SourceLoader loaderOf(Map<String, String> files) {
		return path -> {
			String src = files.get(path);
			if (src == null) {
				throw new java.io.FileNotFoundException(path);
			}
			return src;
		};
	}

	@Test
	void parsesNameDependsOnAndComponents() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :my-lib
				  :description "test"
				  :version "0.1.0"
				  :author "someone"
				  :license "MIT"
				  :depends-on ("other" :third)
				  :components ((:file "package")
				               (:file "main" :depends-on ("package"))))""");
		assertThat(system.name()).isEqualTo("my-lib");
		assertThat(system.dependsOn()).containsExactly("other", "third");
		assertThat(system.files()).containsExactly("package.lisp", "main.lisp");
	}

	@Test
	void nameDesignatorAcceptsStringKeywordAndSymbol() {
		assertThat(parse("(asdf:defsystem \"lib\")").name()).isEqualTo("lib");
		assertThat(parse("(asdf:defsystem :lib)").name()).isEqualTo("lib");
		assertThat(parse("(asdf:defsystem lib)").name()).isEqualTo("lib");
		// The package resolver may have qualified a bare symbol; the qualifier is
		// stripped.
		assertThat(parse("(asdf:defsystem my-pkg::lib)").name()).isEqualTo("lib");
	}

	@Test
	void componentDependsOnReordersFiles() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "api" :depends-on ("core" "util"))
				               (:file "core" :depends-on ("util"))
				               (:file "util")))""");
		assertThat(system.files()).containsExactly("util.lisp", "core.lisp", "api.lisp");
	}

	@Test
	void unconstrainedComponentsKeepOriginalOrder() {
		AsdfSystems.LispSystem system = parse("(asdf:defsystem :lib :components ((:file \"a\") (:file \"b\")))");
		assertThat(system.files()).containsExactly("a.lisp", "b.lisp");
	}

	@Test
	void serialMakesEachFileDependOnThePrevious() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :serial t
				  :components ((:file "one") (:file "two") (:file "three")))""");
		assertThat(system.files()).containsExactly("one.lisp", "two.lisp", "three.lisp");
	}

	@Test
	void moduleAddsAPathPrefix() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:module "src"
				                :serial t
				                :components ((:file "package") (:file "main")))
				               (:file "driver" :depends-on ("src"))))""");
		assertThat(system.files()).containsExactly("src/package.lisp", "src/main.lisp", "driver.lisp");
	}

	@Test
	void staticFileContributesNoSource() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:static-file "version.sexp") (:file "main")))""");
		assertThat(system.files()).containsExactly("main.lisp");
	}

	@Test
	void unsupportedOptionIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :defsystem-depends-on (:some-plugin))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unsupported option :DEFSYSTEM-DEPENDS-ON");
	}

	@Test
	void testOpWiringOptionsAreToleratedAndIgnored() {
		// Real libraries wire their test systems through :in-order-to/:perform (e.g.
		// split-sequence); there is no operate/test-op machinery, so both parse as
		// ignored metadata instead of failing the whole .asd.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "main"))
				  :in-order-to ((test-op (test-op :lib/tests)))
				  :perform (test-op (o c) (symbol-call :5am :run! :lib)))""");
		assertThat(system.files()).containsExactly("main.lisp");
	}

	@Test
	void unsupportedComponentTypeIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :components ((:c-source-file \"ffi\")))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unsupported component type :C-SOURCE-FILE");
	}

	@Test
	void asdfsOwnDocComponentTypesAreOrderingOnly() {
		AsdfSystems.LispSystem system = parse(
				"(asdf:defsystem :lib :components ((:doc-file \"README\") (:html-file \"index\") (:file \"main\")))");
		assertThat(system.files()).containsExactly("main.lisp");
	}

	@Test
	void unknownComponentDependencyIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :components ((:file \"a\" :depends-on (\"nope\"))))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(":depends-on unknown component nope");
	}

	@Test
	void circularComponentDependencyIsAHardError() {
		assertThatThrownBy(() -> parse("""
				(asdf:defsystem :lib
				  :components ((:file "a" :depends-on ("b")) (:file "b" :depends-on ("a"))))"""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("circular component :depends-on");
	}

	@Test
	void parseAsdSourceSkipsInPackageAndResolvesAgainstTheAsdDirectory() {
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(in-package :asdf-user)
				(defsystem :lib :components ((:file "main")))
				(defsystem :lib/tests :components ((:file "tests")))""", "proj/lib.asd", Features.INTERPRETER);
		assertThat(systems).hasSize(2);
		assertThat(systems.get(0).name()).isEqualTo("lib");
		assertThat(systems.get(0).baseDir()).isEqualTo("proj");
		assertThat(systems.get(1).name()).isEqualTo("lib/tests");
	}

	@Test
	void parseAsdSourceRejectsOtherForms() {
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("(print 1)", "lib.asd", Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("lib.asd")
			.hasMessageContaining("unsupported form in .asd file");
	}

	@Test
	void parsesTheBundledDbiReplacementAsd() {
		// The dbi.asd upstream ships parses fine as data, but its cache selection
		// rides a thread-capability feature expression that can never match
		// rontolisp's feature set -- so the verbatim parse would pick the
		// single-threaded cache on backends that really run concurrent handlers.
		// AsdOverrides substitutes this replacement: thread.lisp + the real
		// bordeaux-threads dependency on the thread-capable backends, single.lisp
		// (upstream's own threadless choice) on WASM.
		String source = AsdOverrides.replacementSource("dbi.asd");
		assertThat(source).isNotNull();
		for (Features features : List.of(Features.INTERPRETER, Features.JVM)) {
			List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource(source, "sw/dbi.asd", features);
			assertThat(systems).hasSize(1);
			assertThat(systems.get(0).dependsOn()).containsExactly("split-sequence", "closer-mop", "cl-ppcre",
					"bordeaux-threads");
			assertThat(systems.get(0).files()).containsExactly("src/utils.lisp", "src/cache/thread.lisp",
					"src/logger.lisp", "src/error.lisp", "src/driver.lisp", "src/dbi.lisp");
		}
		List<AsdfSystems.LispSystem> wasmSystems = AsdfSystems.parseAsdSource(source, "sw/dbi.asd", Features.WASM);
		assertThat(wasmSystems.get(0).files()).containsExactly("src/utils.lisp", "src/cache/single.lisp",
				"src/logger.lisp", "src/error.lisp", "src/driver.lisp", "src/dbi.lisp");
	}

	@Test
	void aVersionDependsOnEntryResolvesToThePlainDependency() {
		// mito-core.asd's shape. The version constraint is parsed and IGNORED like the
		// defsystem-level :version metadata: the systems reachable here carry no
		// reliable :version to check against.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem "mito-core"
				  :depends-on ((:version "dbi" "0.11.1") "sxql")
				  :components ((:file "src/core")))""");
		assertThat(system.dependsOn()).containsExactly("dbi", "sxql");
	}

	@Test
	void aVersionDependsOnEntryWithoutAVersionIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :x :depends-on ((:version \"dbi\")))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(":version");
	}

	@Test
	void toleratesATopLevelPerformDefmethod() {
		// iterate.asd's test-op wiring and esrap.asd's feature-pushing load-op hook.
		// The esrap pushes are IGNORED with the whole body (grep-verified 2026-08-03:
		// nothing in the dist reads #+esrap.*), so they must NOT surface as system
		// features.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(defsystem :lib :components ((:file "main")))
				(defmethod perform ((operation test-op) (component (eql (find-system :lib))))
				  (uiop:symbol-call '#:lib.test '#:run))
				(defmethod perform :after ((op load-op) (sys (eql (find-system "lib"))))
				  (pushnew :lib.extra *features*)
				  (provide :lib))
				(defsystem :lib/late :components ((:file "late")))""", "sw/lib.asd", Features.INTERPRETER);
		assertThat(systems).hasSize(2);
		assertThat(systems.get(0).features()).isEmpty();
		assertThat(systems.get(1).features()).isEmpty();
	}

	@Test
	void aTopLevelDefmethodOnAnotherNameIsAHardError() {
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("(defmethod operation-done-p ((o compile-op) (c t)) nil)",
				"lib.asd", Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("lib.asd")
			.hasMessageContaining("OPERATION-DONE-P");
	}

	@Test
	void toleratesADocFileDefclassAndItsComponentTypes() {
		// chipz.asd's documentation-file machinery: two defclasses over ASDF's
		// doc-file, whose component types then appear inside a :module. They
		// participate in ordering but contribute no source, like :static-file.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(cl:defpackage :chipz-system (:use :cl :asdf) (:export #:gray-streams))
				(cl:in-package :chipz-system)
				(defclass txt-file (doc-file) ((type :initform "txt")))
				(defclass css-file (doc-file) ((type :initform "css")))
				(asdf:defsystem :chipz
				  :components ((:file "package")
				               (:module "doc"
				                :components ((:html-file "index")
				                             (:txt-file "chipz-doc")
				                             (:css-file "style")))
				               (:file "inflate" :depends-on ("package"))))""", "sw/chipz.asd", Features.INTERPRETER);
		assertThat(systems).hasSize(1);
		assertThat(systems.get(0).files()).containsExactly("package.lisp", "inflate.lisp");
	}

	@Test
	void aDefclassWithANonDocSuperclassIsAHardError() {
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("(defclass my-file (cl-source-file) ())", "lib.asd",
				Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("lib.asd")
			.hasMessageContaining("MY-FILE");
	}

	@Test
	void aDocComponentTypeWithoutItsDefclassIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :x :components ((:txt-file \"readme\")))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(":TXT-FILE");
	}

	// The verbatim fast-io.asd header: an eval-when pushing :fast-io onto *features*,
	// then a second one gated OFF by #+ that would have pushed :fast-io-sv.
	private static final String FAST_IO_ASD = """
			(eval-when (:compile-toplevel :load-toplevel :execute)
			  (pushnew :fast-io *features*))

			#+(or sbcl ccl cmucl ecl lispworks allegro)
			(eval-when (:compile-toplevel :load-toplevel :execute)
			  (pushnew :fast-io-sv *features*))

			(defsystem :fast-io
			  :depends-on (:alexandria :trivial-gray-streams
			               #+fast-io-sv
			               :static-vectors)
			  :pathname "src"
			  :serial t
			  :components ((:file "package") (:file "types") (:file "io") (:file "gray")))""";

	@Test
	void parsesTheFastIoAsdFeatureAnnouncementHeader() {
		// The push is recorded as a declared feature of the system defined after it (so
		// its component files are read with :fast-io active); the #+-gated one never
		// happens, so the #+fast-io-sv :depends-on entry drops :static-vectors. Every
		// backend, because the two reader spellings of *features* differ: the
		// interpreter leaves the symbol standing, the compile paths substitute the
		// quoted active list at read time.
		for (Features features : List.of(Features.INTERPRETER, Features.JVM, Features.WASM)) {
			List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource(FAST_IO_ASD, "sw/fast-io.asd", features);
			assertThat(systems).hasSize(1);
			assertThat(systems.get(0).features()).containsExactly("fast-io");
			assertThat(systems.get(0).dependsOn()).containsExactly("alexandria", "trivial-gray-streams");
			assertThat(systems.get(0).files()).containsExactly("src/package.lisp", "src/types.lisp", "src/io.lisp",
					"src/gray.lisp");
		}
	}

	@Test
	void anAnnouncedFeatureReachesTheSystemsOwnFeatureClauses() {
		// What the declaration buys inside the same defsystem: :if-feature and
		// (:feature ...) see the pushed name. (A #+ in the same file does not -- the
		// reader resolved it before this parse, .todo/181.)
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(eval-when (:load-toplevel :execute) (pushnew :lib-sv *features*))
				(defsystem :lib
				  :depends-on ((:feature :lib-sv "static-vectors"))
				  :components ((:file "main") (:file "sv" :if-feature :lib-sv)))""", "lib.asd", Features.INTERPRETER);
		assertThat(systems.get(0).dependsOn()).containsExactly("static-vectors");
		assertThat(systems.get(0).files()).containsExactly("main.lisp", "sv.lisp");
	}

	@Test
	void anAnnouncedFeatureReachesOnlyTheSystemsDefinedAfterIt() {
		// A push takes effect where it stands, like the load-time push it encodes.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(defsystem :lib/early)
				(pushnew :lib *features*)
				(defsystem :lib)""", "lib.asd", Features.INTERPRETER);
		assertThat(systems.get(0).features()).isEmpty();
		assertThat(systems.get(1).features()).containsExactly("lib");
	}

	@Test
	void anAnnouncedFeatureJoinsTheDeclaredOnes() {
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(eval-when (:execute) (pushnew :pushed *features*))
				(defsystem :lib :rontolisp-features (:declared))""", "lib.asd", Features.INTERPRETER);
		assertThat(systems.get(0).features()).containsExactly("pushed", "declared");
	}

	@Test
	void aCompileToplevelOnlyPushIsInert() {
		// ASDF loads a .asd, it never compiles one, so such a push has no effect in a
		// real implementation either.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(eval-when (:compile-toplevel) (pushnew :never *features*))
				(defsystem :lib)""", "lib.asd", Features.INTERPRETER);
		assertThat(systems.get(0).features()).isEmpty();
	}

	@Test
	void anEvalWhenThatIsNotAFeatureAnnouncementIsRejected() {
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("""
				(eval-when (:load-toplevel :execute) (load "helper.lisp"))
				(defsystem :lib)""", "lib.asd", Features.INTERPRETER)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("lib.asd")
			.hasMessageContaining("only a feature announcement")
			.hasMessageContaining("(LOAD \"helper.lisp\")");
	}

	@Test
	void aPushOntoSomethingOtherThanFeaturesIsRejected() {
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("(pushnew :x '(:a :b))", "lib.asd", Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("only a feature announcement");
	}

	@Test
	void ifFeatureDisabledComponentContributesNoFiles() {
		// The split-sequence shape: the CLOS-only file is gated behind other
		// implementations' features, so it drops out while keeping its graph slot.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "main")
				               (:file "extended" :depends-on ("main") :if-feature (:or :sbcl :abcl))))""");
		assertThat(system.files()).containsExactly("main.lisp");
	}

	@Test
	void ifFeatureEnabledComponentKeepsItsFiles() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "main" :if-feature :rontolisp)
				               (:file "extra" :if-feature (:not :sbcl))))""");
		assertThat(system.files()).containsExactly("main.lisp", "extra.lisp");
	}

	@Test
	void ifFeatureDisabledComponentStillSatisfiesDependencies() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "b" :depends-on ("a"))
				               (:file "a" :if-feature :sbcl)))""");
		assertThat(system.files()).containsExactly("b.lisp");
	}

	@Test
	void ifFeatureOnAModuleDropsTheWholeModule() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:module "impl" :if-feature :sbcl :components ((:file "sbcl")))
				               (:file "main")))""");
		assertThat(system.files()).containsExactly("main.lisp");
	}

	@Test
	void ifFeatureIsEvaluatedAgainstTheGivenFeatures() {
		AsdfSystems.LispSystem system = AsdfSystems.parseDefsystem(
				form("(asdf:defsystem :lib :components ((:file \"jvm\" :if-feature :rontolisp-jvm)))"), null,
				Features.JVM);
		assertThat(system.files()).containsExactly("jvm.lisp");
	}

	@Test
	void parseAsdSourceSkipsATopLevelReadEvalGuard() {
		// The ASDF-version-guard idiom: a leading top-level #. form is skipped, silently
		// -- nothing consumes its value, so nothing may complain about it.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				#.(unless (uiop-version<= "3.1" (asdf-version)) (error "too old"))
				(defsystem :lib :components ((:file "main")))""", "lib.asd", Features.INTERPRETER);
		assertThat(systems).hasSize(1);
		assertThat(systems.get(0).files()).containsExactly("main.lisp");
	}

	@Test
	void parseAsdSourceHonorsFeatureConditionals() {
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(defsystem :lib
				  :components (#+rontolisp (:file "main")
				               #+sbcl (:file "sbcl-only")))""", "lib.asd", Features.INTERPRETER);
		assertThat(systems.get(0).files()).containsExactly("main.lisp");
	}

	@Test
	void designatorsAcceptUninternedSymbols() {
		// The portable defsystem idiom spells names #:lib.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem #:lib
				  :depends-on (#:other)
				  :components ((:file "main")))""");
		assertThat(system.name()).isEqualTo("lib");
		assertThat(system.dependsOn()).containsExactly("other");
		assertThat(AsdfSystems.loadSystemName(form("(asdf:load-system #:lib)"))).isEqualTo("lib");
	}

	@Test
	void loadSystemNameMatchesLiteralDesignators() {
		assertThat(AsdfSystems.loadSystemName(form("(asdf:load-system \"lib\")"))).isEqualTo("lib");
		assertThat(AsdfSystems.loadSystemName(form("(asdf:load-system :lib)"))).isEqualTo("lib");
		assertThat(AsdfSystems.loadSystemName(form("(asdf:load-system 'lib)"))).isEqualTo("lib");
		assertThat(AsdfSystems.loadSystemName(form("(load \"lib.lisp\")"))).isNull();
	}

	@Test
	void loadSystemNameRejectsAComputedName() {
		assertThatThrownBy(() -> AsdfSystems.loadSystemName(form("(asdf:load-system (concatenate 'string \"l\"))")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("literal system name");
	}

	@Test
	void locateSearchesDirectoriesInOrder() {
		SourceLoader loader = loaderOf(Map.of("registry/lib.asd", "(defsystem :lib)"));
		AsdfSystems.LocatedAsd asd = AsdfSystems.locate("lib", List.of("proj", "registry"), loader);
		assertThat(asd.path()).isEqualTo("registry/lib.asd");
	}

	@Test
	void locateUsesThePrimaryNameForASecondarySystem() {
		SourceLoader loader = loaderOf(Map.of("lib.asd", "(defsystem :lib)"));
		assertThat(AsdfSystems.locate("lib/tests", List.of(""), loader).path()).isEqualTo("lib.asd");
	}

	@Test
	void locateReportsTheTriedPaths() {
		assertThatThrownBy(() -> AsdfSystems.locate("lib", List.of("a", "b"), loaderOf(Map.of())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("system 'lib' not found")
			.hasMessageContaining("a/lib.asd")
			.hasMessageContaining("b/lib.asd");
	}

	@Test
	void parsesTheClPostgresAsdHeaderShape() {
		// The verbatim cl-postgres.asd header shape: defpackage +
		// in-package, #+/#- conditional defparameters feeding #. component names, and
		// (:feature ...) clauses inside :depends-on. rontolisp advertises :unicode, so
		// *unicode* is true and #.*string-file* resolves to "strings-utf-8".
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(defpackage :cl-postgres-system
				  (:use :common-lisp :asdf))
				(in-package :cl-postgres-system)

				(defparameter *unicode*
				  #+(or sb-unicode unicode ics openmcl-unicode-strings abcl) t
				  #-(or sb-unicode unicode ics openmcl-unicode-strings abcl) nil)
				(defparameter *string-file* (if *unicode* "strings-utf-8" "strings-ascii"))

				(defsystem "cl-postgres"
				  :description "Low-level client library for PostgreSQL"
				  :author "Marijn Haverbeke <marijnh@gmail.com>"
				  :license "zlib"
				  :version "1.33.11"
				  :depends-on ("md5" "split-sequence" "ironclad" "cl-base64" "uax-15"
				                     (:feature (:or :allegro :ccl :clisp :genera
				                                :armedbear :cmucl :lispworks :ecl)
				                               "usocket")
				                     (:feature :sbcl (:require :sb-bsd-sockets)))
				  :components
				  ((:module "cl-postgres"
				    :components ((:file "package")
				                 (:file "trivial-utf-8" :depends-on ("package"))
				                 (:file #.*string-file*
				                  :depends-on ("package" "trivial-utf-8"))
				                 (:file "communicate"
				                  :depends-on (#.*string-file*))))))""", "proj/cl-postgres.asd", Features.INTERPRETER);
		assertThat(systems).hasSize(1);
		AsdfSystems.LispSystem system = systems.get(0);
		assertThat(system.name()).isEqualTo("cl-postgres");
		// The usocket clause is feature-gated to implementations without built-in
		// sockets and the (:require ...) clause to sbcl; both drop here.
		assertThat(system.dependsOn()).containsExactly("md5", "split-sequence", "ironclad", "cl-base64", "uax-15");
		assertThat(system.files()).containsExactly("cl-postgres/package.lisp", "cl-postgres/trivial-utf-8.lisp",
				"cl-postgres/strings-utf-8.lisp", "cl-postgres/communicate.lisp");
	}

	@Test
	void parsesTheVendoredAlexandriaAsd() throws Exception {
		// The verbatim alexandria.asd (vendored under src/test/resources/alexandria) is
		// the richest ORDERING case on the loadable list: two :modules, a :static-file
		// per module that must contribute no source while keeping its place, per-file
		// :depends-on in an order that is NOT the declared one (io comes before the
		// macros/lists/types it needs), and an :in-order-to test-op clause the data-only
		// front end tolerates and ignores. Pinned as data, so a regression in the
		// topological sort shows up here rather than as a mid-load "undefined function"
		// in AlexandriaE2eTest.
		String source = java.nio.file.Files
			.readString(java.nio.file.Path.of("src", "test", "resources", "alexandria", "alexandria.asd"));
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource(source, "alexandria/alexandria.asd",
				Features.INTERPRETER);
		// The file defines two systems: alexandria and the secondary alexandria/tests
		// (whose own :depends-on carries the #+sbcl/#-sbcl pair -- :rt survives here).
		assertThat(systems).extracting(AsdfSystems.LispSystem::name).containsExactly("alexandria", "alexandria/tests");
		assertThat(systems.get(1).dependsOn()).containsExactly("alexandria", "rt");
		AsdfSystems.LispSystem system = systems.get(0);
		assertThat(system.name()).isEqualTo("alexandria");
		assertThat(system.dependsOn()).isEmpty();
		assertThat(system.files()).containsExactly(ALEXANDRIA_FILES);
		// Every dependency of every component precedes it, and no :static-file
		// (LICENCE, the two tests.lisp) contributes a source file.
		assertThat(system.files()).doesNotContain("alexandria-1/tests.lisp", "alexandria-2/tests.lisp", "LICENCE");
		assertThat(system.files().indexOf("alexandria-1/package.lisp")).isZero();
		assertThat(system.files().indexOf("alexandria-1/macros.lisp"))
			.isLessThan(system.files().indexOf("alexandria-1/io.lisp"));
		assertThat(system.files().indexOf("alexandria-1/functions.lisp"))
			.isLessThan(system.files().indexOf("alexandria-1/lists.lisp"));
	}

	@Test
	void featureDependencyContributesWhenEnabled() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :depends-on ("base" (:feature :rontolisp "extra") (:feature :sbcl "sbcl-only")))""");
		assertThat(system.dependsOn()).containsExactly("base", "extra");
	}

	@Test
	void parsesTheBundledPostmodernReplacementAsd() {
		// The .asd upstream postmodern ships opens with an eval-when that pushes its two
		// features, which the data-only front end cannot read; AsdOverrides substitutes
		// this replacement. Pin the resolved graph: the MOP build is ON (table.lisp in,
		// deftable after it, closer-mop through upstream's (:feature ...) clause),
		// global-vars (declared upstream, zero call sites) is gone, and cl-ppcre +
		// uax-15 (called by the sources, never declared upstream) are present.
		String source = AsdOverrides.replacementSource("postmodern.asd");
		assertThat(source).isNotNull();
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource(source, "sw/postmodern.asd",
				Features.INTERPRETER);
		assertThat(systems).hasSize(1);
		AsdfSystems.LispSystem system = systems.get(0);
		assertThat(system.name()).isEqualTo("postmodern");
		assertThat(system.dependsOn()).containsExactly("alexandria", "bordeaux-threads", "cl-postgres", "s-sql",
				"split-sequence", "uiop", "cl-ppcre", "uax-15", "closer-mop");
		// Both features are declared statically (upstream pushes them from an
		// eval-when, which the reader never sees): the lock sites take their
		// #+postmodern-thread-safe branch, package.lisp/table.lisp/deftable.lisp/
		// json-encoder.lisp their #+postmodern-use-mop branches.
		assertThat(system.features()).containsExactly("postmodern-thread-safe", "postmodern-use-mop");
		assertThat(system.files()).containsExactly("postmodern/package.lisp", "postmodern/config.lisp",
				"postmodern/connect.lisp", "postmodern/json-encoder.lisp", "postmodern/query.lisp",
				"postmodern/prepare.lisp", "postmodern/roles.lisp", "postmodern/util.lisp",
				"postmodern/transaction.lisp", "postmodern/namespace.lisp", "postmodern/execute-file.lisp",
				"postmodern/table.lisp", "postmodern/deftable.lisp");
	}

	@Test
	void thePostmodernMopBuildIsAFeatureFlip() {
		// The :if-feature / (:feature ...) clauses stay verbatim in the replacement so
		// the DAO layer rides the feature, not a file edit: with the declared features
		// stripped of :postmodern-use-mop, table.lisp (and the closer-mop dependency)
		// would leave the build again -- pinned by re-parsing the same source with the
		// declaration line reduced to :postmodern-thread-safe alone.
		String source = AsdOverrides.replacementSource("postmodern.asd");
		assertThat(source).isNotNull();
		String nonMop = source.replace(":rontolisp-features (:postmodern-thread-safe :postmodern-use-mop)",
				":rontolisp-features (:postmodern-thread-safe)");
		assertThat(nonMop).isNotEqualTo(source);
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource(nonMop, "sw/postmodern.asd",
				Features.INTERPRETER);
		assertThat(systems.get(0).files()).doesNotContain("postmodern/table.lisp").contains("postmodern/deftable.lisp");
		assertThat(systems.get(0).dependsOn()).doesNotContain("closer-mop");
	}

	@Test
	void aModulePathnameDecouplesTheComponentNameFromItsDirectory() {
		// quri's shape: the module is NAMED uri-classes (so a sibling can depend on
		// that name) but its files live in src/uri/.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:module "src"
				                :components ((:file "uri")
				                             (:module "uri-classes"
				                              :pathname "uri"
				                              :depends-on ("uri")
				                              :components ((:file "http")))))))
				""");
		assertThat(system.files()).containsExactly("src/uri.lisp", "src/uri/http.lisp");
	}

	@Test
	void anEmptyModulePathnameAddsNoDirectoryLevel() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:module "flat" :pathname "" :components ((:file "a")))))
				""");
		assertThat(system.files()).containsExactly("a.lisp");
	}

	@Test
	void aFilePathnameNamesTheSourceFile() {
		// With an extension the namestring is used verbatim; without one, .lisp is
		// appended exactly as the component name would have been.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "one" :pathname "strings-utf-8.lisp")
				               (:file "two" :pathname "other")))
				""");
		assertThat(system.files()).containsExactly("strings-utf-8.lisp", "other.lisp");
	}

	@Test
	void aComputedComponentPathnameIsAHardError() {
		assertThatThrownBy(() -> parse("""
				(asdf:defsystem :lib
				  :components ((:module "m" :pathname (foo) :components ((:file "a")))))
				""")).isInstanceOf(IllegalStateException.class).hasMessageContaining(":pathname");
	}

	@Test
	void componentFeatureDependencyIsDroppedWhenTheFeatureIsOff() {
		// postmodern's deftable depends on the DAO layer only in the MOP build. The
		// gated component keeps its place in the graph (it is :if-feature'd, so it
		// contributes no source) and the dependency simply disappears, so the
		// remaining components still order.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "query")
				               (:file "table" :depends-on ("query") :if-feature :postmodern-use-mop)
				               (:file "deftable" :depends-on
				                      ("query" (:feature :postmodern-use-mop "table")))))""");
		assertThat(system.files()).containsExactly("query.lisp", "deftable.lisp");
	}

	@Test
	void componentFeatureDependencyContributesWhenTheFeatureIsOn() {
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :components ((:file "deftable" :depends-on ((:feature :rontolisp "table")))
				               (:file "table")))""");
		// deftable really depends on table now, so the declaration order is reversed.
		assertThat(system.files()).containsExactly("table.lisp", "deftable.lisp");
	}

	@Test
	void featureDependencyIgnoresElementsPastTheDependency() {
		// Real ASDF's :feature combinator reads exactly the feature expression and the
		// first dependency; upstream postmodern's deftable carries a third element
		// ("config") that has never had an effect. Erroring on it would make a
		// widely-deployed .asd unreadable.
		AsdfSystems.LispSystem system = parse("""
				(asdf:defsystem :lib
				  :depends-on ((:feature :rontolisp "base" "ignored"))
				  :components ((:file "deftable" :depends-on
				                      ("query" (:feature :rontolisp "table" "ignored")))
				               (:file "query")
				               (:file "table")))""");
		assertThat(system.dependsOn()).containsExactly("base");
		assertThat(system.files()).containsExactly("query.lisp", "table.lisp", "deftable.lisp");
	}

	@Test
	void featureDependencyWithoutADependencyIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :depends-on ((:feature :rontolisp)))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("(:feature FEATURE-EXPR DEPENDENCY-DEF)");
	}

	@Test
	void requireDependencyIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :depends-on ((:require :sb-bsd-sockets)))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("(:require ...) is not supported");
	}

	@Test
	void asdDefparameterWithAnImpureValueIsAHardError() {
		// Deny by default: the .asd is never really evaluated, so a defparameter value
		// outside the pure-data mini evaluator fails the parse instead of guessing.
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("(defparameter *when* (get-universal-time))", "lib.asd",
				Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("lib.asd")
			.hasMessageContaining("DEFPARAMETER")
			.hasMessageContaining("pure data");
	}

	@Test
	void unresolvableReadEvalInIgnoredMetadataIsSilent() {
		// A #. in ignored metadata (the :version read-file indirection) must not fail
		// the parse -- and must not SAY anything either: the option loop throws the value
		// away one line later, so a warning about it is noise with nothing to act on.
		List<AsdfSystems.LispSystem> systems = parseCapturingStderr("""
				(defsystem :lib
				  :version #.(uiop:read-file-form "version.sexp")
				  :components ((:file "main")))""", "lib.asd");
		assertThat(systems.get(0).files()).containsExactly("main.lisp");
		assertThat(stderr).isEmpty();
	}

	@Test
	void theClProjectLongDescriptionSkeletonIsSilent() {
		// Verbatim myway.asd (the cl-project skeleton every fukamachi library carries):
		// a :long-description that opens the project's README at load time. Seven of
		// these are on the ningle/clack dependency graph, and each one used to print a
		// 370-character warning about a value :long-description discards.
		List<AsdfSystems.LispSystem> systems = parseCapturingStderr("""
				(defsystem myway
				  :version "0.1.0"
				  :author "Eitaro Fukamachi"
				  :license "LLGPL"
				  :depends-on (:cl-ppcre :quri)
				  :components ((:module "src"
				                :components ((:file "myway"))))
				  :description "Sinatra-compatible routing library."
				  :long-description
				  #.(with-open-file (stream (merge-pathnames
				                             #p"README.markdown"
				                             (or *load-pathname* *compile-file-pathname*))
				                            :if-does-not-exist nil
				                            :direction :input)
				      (when stream
				        (let ((seq (make-array (file-length stream)
				                               :element-type 'character
				                               :fill-pointer t)))
				          (setf (fill-pointer seq) (read-sequence seq stream))
				          seq)))
				  :in-order-to ((test-op (test-op myway-test))))""", "myway/myway.asd");
		assertThat(systems.get(0).name()).isEqualTo("myway");
		assertThat(systems.get(0).dependsOn()).containsExactly("cl-ppcre", "quri");
		assertThat(systems.get(0).files()).containsExactly("src/myway.lisp");
		assertThat(stderr).isEmpty();
	}

	@Test
	void aPerformBodyReadEvalIsSilent() {
		// The other ignored-option shape, from the seven *-test.asd files on the same
		// graph: a #. inside the :perform test-op wiring, which has no operate machinery
		// to run and is dropped whole.
		List<AsdfSystems.LispSystem> systems = parseCapturingStderr("""
				(defsystem "lib-test"
				  :depends-on ("lib")
				  :components ((:file "main"))
				  :perform (test-op :after (op c)
				                    (funcall (intern #.(string :run-test-system) :prove-asdf) c)))""", "lib-test.asd");
		assertThat(systems.get(0).files()).containsExactly("main.lisp");
		assertThat(stderr).isEmpty();
	}

	@Test
	void anUnresolvableReadEvalInDependsOnIsAHardError() {
		// The other half of the same rule: where the value DECIDES something, a silent
		// nil drops a dependency and surfaces much later as an undefined symbol far from
		// the cause, so the parse fails naming the .asd and the clause instead.
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("""
				(defsystem :lib
				  :depends-on ("base" #.(uiop:read-file-form "extra.sexp"))
				  :components ((:file "main")))""", "proj/lib.asd", Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("proj/lib.asd")
			.hasMessageContaining(":depends-on")
			.hasMessageContaining("READ-FILE-FORM");
	}

	@Test
	void anUnresolvableReadEvalInComponentsIsAHardError() {
		// Same rule one level down: an unresolvable #. inside :components would silently
		// REMOVE a source file from the system (the cffi-toolchain :if-feature shape).
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("""
				(defsystem :lib
				  :components ((:file "main")
				               (:file "bundle" :if-feature (#.(if (version< "3.1.8" (asdf-version)) :or :and)))))""",
				"proj/lib.asd", Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("proj/lib.asd")
			.hasMessageContaining(":components")
			.hasMessageContaining("VERSION<");
	}

	@Test
	void anUnreadableReadEvalIsSilentInMetadataAndNamedWhereItDecides() {
		// A #. whose datum the lexer cannot even re-lex reaches the parse as raw source
		// text rather than as a warning: same consumer-decides rule, so it is dropped
		// with the metadata and quoted verbatim where it would pick a dependency.
		List<AsdfSystems.LispSystem> systems = parseCapturingStderr("""
				(defsystem :lib
				  :long-description #.,readme
				  :components ((:file "main")))""", "lib.asd");
		assertThat(systems.get(0).files()).containsExactly("main.lisp");
		assertThat(stderr).isEmpty();
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("""
				(defsystem :lib :depends-on (#.,extra) :components ((:file "main")))""", "proj/lib.asd",
				Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("proj/lib.asd")
			.hasMessageContaining(":depends-on")
			.hasMessageContaining(",extra");
	}

	@Test
	void aSystemPathnameIsAPrefixForEveryComponent() {
		// lack.asd's shape: the system says :pathname "src" once and then names its
		// components bare.
		AsdfSystems.LispSystem system = parse("""
				(defsystem "lack"
				  :depends-on ("lack-component" "lack-util")
				  :pathname "src"
				  :components ((:file "lack" :depends-on ("builder"))
				               (:file "builder")))""");
		assertThat(system.dependsOn()).containsExactly("lack-component", "lack-util");
		assertThat(system.files()).containsExactly("src/builder.lisp", "src/lack.lisp");
	}

	@Test
	void aSystemPathnameComposesWithModulesAndComponentPathnames() {
		// The prefix nests: a module adds its own level inside it, and a component-level
		// :pathname still names the file within the composed directory.
		AsdfSystems.LispSystem system = parse("""
				(defsystem :lib
				  :pathname "src/"
				  :components ((:file "one")
				               (:file "two" :pathname "other.lisp")
				               (:module "sub" :components ((:file "three")))))""");
		assertThat(system.files()).containsExactly("src/one.lisp", "src/other.lisp", "src/sub/three.lisp");
	}

	@Test
	void anEmptySystemPathnameAddsNoDirectoryLevel() {
		AsdfSystems.LispSystem system = parse("""
				(defsystem :lib :pathname "" :components ((:file "main")))""");
		assertThat(system.files()).containsExactly("main.lisp");
	}

	@Test
	void aComputedSystemPathnameIsAHardError() {
		assertThatThrownBy(() -> parse("(defsystem :lib :pathname (foo) :components ((:file \"a\")))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(":pathname expects a namestring literal");
	}

	@Test
	void parseAsdSourceSkipsRegisterSystemPackages() {
		// lack-component.asd verbatim: real ASDF maps the package onto the system for
		// its own autoloading; nothing here consults such a map, so the form is dropped
		// like in-package/defpackage rather than failing the whole .asd.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(defsystem "lack-component"
				  :version "0.2.0"
				  :author "Eitaro Fukamachi"
				  :license "MIT"
				  :components ((:file "src/component")))

				(register-system-packages "lack-component" '(:lack.component))""", "lack-component.asd",
				Features.INTERPRETER);
		assertThat(systems).hasSize(1);
		assertThat(systems.get(0).files()).containsExactly("src/component.lisp");
	}

	@Test
	void parsesTheVerbatimLackAsd() {
		// The whole upstream lack.asd (lack-20260101-git): the :pathname system, the
		// eighteen one-line alias systems, and the lack/tests system whose :pathname,
		// nested :module, #+todo-guarded component and :perform must all parse.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(defsystem "lack"
				  :version "0.3.0"
				  :author "Eitaro Fukamachi"
				  :license "MIT"
				  :depends-on ("lack-component"
				               "lack-util")
				  :pathname "src"
				  :components ((:file "lack" :depends-on ("builder"))
				               (:file "builder"))
				  :description "A minimal Clack"
				  :in-order-to ((test-op (test-op "lack/tests"))))

				(defsystem "lack/component" :depends-on ("lack-component"))
				(defsystem "lack/middleware/backtrace" :depends-on ("lack-middleware-backtrace"))

				(defsystem "lack/tests"
				  :depends-on ("lack" "lack/component" "rove")
				  :pathname "tests"
				  :serial t
				  :components ((:file "builder")
				               (:file "util")
				               (:module "middleware"
				                :components
				                ((:file "static")
				                 (:file "auth/basic")))
				               (:module "session"
				                :components
				                ((:module "store"
				                  :components
				                  ((:file "dbi")
				                   #+todo
				                   (:file "redis"))))))
				  :perform (test-op (op c) (symbol-call :rove :run c)))""", "lack/lack.asd", Features.INTERPRETER);
		assertThat(systems.stream().map(AsdfSystems.LispSystem::name)).containsExactly("lack", "lack/component",
				"lack/middleware/backtrace", "lack/tests");
		assertThat(systems.get(0).files()).containsExactly("src/builder.lisp", "src/lack.lisp");
		assertThat(systems.get(0).baseDir()).isEqualTo("lack");
		assertThat(systems.get(1).dependsOn()).containsExactly("lack-component");
		assertThat(systems.get(1).files()).isEmpty();
		// :serial t makes the modules follow the two files; the #+todo component is
		// read-suppressed, so the store module contributes dbi.lisp alone.
		assertThat(systems.get(3).files()).containsExactly("tests/builder.lisp", "tests/util.lisp",
				"tests/middleware/static.lisp", "tests/middleware/auth/basic.lisp", "tests/session/store/dbi.lisp");
	}

	@Test
	void loadSystemNameIgnoresKeywordOptions() {
		// lack's find-package-or-load spells its runtime reload
		// (asdf:load-system name :verbose nil).
		assertThat(AsdfSystems.loadSystemName(form("(asdf:load-system \"lib\" :verbose nil)"))).isEqualTo("lib");
		assertThat(AsdfSystems.loadSystemName(form("(asdf:load-system :lib :force t :verbose nil)"))).isEqualTo("lib");
	}

	@Test
	void loadSystemNameRejectsANonKeywordSecondArgument() {
		// The shape is still checked, so a second system name is an error rather than a
		// silently dropped load.
		assertThatThrownBy(() -> AsdfSystems.loadSystemName(form("(asdf:load-system \"a\" \"b\" \"c\")")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("expects a keyword option");
		assertThatThrownBy(() -> AsdfSystems.loadSystemName(form("(asdf:load-system \"a\" :verbose)")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("expects :option value pairs");
	}

	// --- :class :package-inferred-system -------------------------------------------

	/** The verbatim ningle.asd (ningle 0.3.0, LLGPL): the whole file, nothing elided. */
	private static final String NINGLE_ASD = """
			(defsystem "ningle"
			  :class :package-inferred-system
			  :version "0.3.0"
			  :author "Eitaro Fukamachi"
			  :license "LLGPL"
			  :depends-on ("ningle/main")
			  :description "Super micro framework for Common Lisp."
			  :in-order-to ((test-op (test-op "ningle-test"))))

			(register-system-packages "lack-component" '(#:lack.component))
			(register-system-packages "lack-request" '(#:lack.request))
			(register-system-packages "lack-response" '(#:lack.response))
			""";

	/** The leading defpackage of each of ningle's four sources, verbatim. */
	private static final Map<String, String> NINGLE_FILES = Map.of("sw/ningle/main.lisp", """
			(uiop:define-package #:ningle
			  (:nicknames #:ningle/main)
			  (:use #:cl)
			  (:use-reexport #:ningle/app
			                 #:ningle/context)
			  (:import-from #:myway
			                #:next-route)
			  (:export #:next-route))
			(in-package #:ningle)
			""", "sw/ningle/app.lisp", """
			(defpackage #:ningle/app
			  (:nicknames #:ningle.app)
			  (:use #:cl)
			  (:shadowing-import-from #:ningle/context
			                          #:*context*)
			  (:import-from #:ningle/route
			                #:ningle-route)
			  (:import-from #:lack.request
			                #:request-headers)
			  (:import-from #:lack.response
			                #:response-body)
			  (:import-from #:lack.component
			                #:lack-component)
			  (:import-from #:myway
			                #:make-mapper)
			  (:import-from #:alexandria
			                #:delete-from-plist)
			  (:export #:app))
			(in-package #:ningle/app)
			""", "sw/ningle/context.lisp", """
			(defpackage #:ningle/context
			  (:nicknames #:ningle.context)
			  (:use #:cl)
			  (:export #:*context*))
			(in-package #:ningle/context)
			""", "sw/ningle/route.lisp", """
			(defpackage #:ningle/route
			  (:nicknames #:ningle.route)
			  (:use #:cl)
			  (:import-from #:myway
			                #:route)
			  (:export #:ningle-route))
			(in-package #:ningle/route)
			""");

	private static Map<String, AsdfSystems.LispSystem> registryOf(List<AsdfSystems.LispSystem> systems) {
		Map<String, AsdfSystems.LispSystem> registry = new java.util.HashMap<>();
		for (AsdfSystems.LispSystem system : systems) {
			registry.put(system.name(), system);
		}
		return registry;
	}

	private static AsdfSystems.LispSystem registered(Map<String, AsdfSystems.LispSystem> registry, String name) {
		AsdfSystems.LispSystem system = registry.get(name);
		assertThat(system).as(name).isNotNull();
		return java.util.Objects.requireNonNull(system);
	}

	@Test
	void parsesTheVerbatimNingleAsd() {
		// The .asd names ONE sub-system and no components at all: everything the load
		// needs beyond "ningle/main" is reachable only through the component files.
		Map<String, String> systemPackages = new java.util.HashMap<>();
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource(NINGLE_ASD, "sw/ningle/ningle.asd",
				Features.INTERPRETER, systemPackages);
		assertThat(systems).hasSize(1);
		AsdfSystems.LispSystem ningle = systems.get(0);
		assertThat(ningle.name()).isEqualTo("ningle");
		assertThat(ningle.dependsOn()).containsExactly("ningle/main");
		assertThat(ningle.files()).isEmpty();
		// No :pathname, so sub-system names resolve beside the .asd.
		assertThat(ningle.packageInferredDir()).isEmpty();
		assertThat(systemPackages).containsOnly(entry("lack.component", "lack-component"),
				entry("lack.request", "lack-request"), entry("lack.response", "lack-response"));
	}

	@Test
	void anOrdinarySystemIsNotPackageInferred() {
		assertThat(parse("(asdf:defsystem :lib :components ((:file \"main\")))").packageInferredDir()).isNull();
	}

	@Test
	void packageInferredSubSystemsAreDerivedFromTheirFilesDefpackage() {
		Map<String, String> systemPackages = new java.util.HashMap<>();
		Map<String, AsdfSystems.LispSystem> registry = registryOf(
				AsdfSystems.parseAsdSource(NINGLE_ASD, "sw/ningle/ningle.asd", Features.INTERPRETER, systemPackages));
		AsdfSystems.inferPackageInferredSystems("ningle/main", registry, systemPackages, loaderOf(NINGLE_FILES),
				Features.INTERPRETER);
		// One pass derives the whole closure reachable from ningle/main, so the .asd is
		// not re-read once per sub-system.
		assertThat(registry).containsOnlyKeys("ningle", "ningle/main", "ningle/app", "ningle/context", "ningle/route");
		AsdfSystems.LispSystem main = registered(registry, "ningle/main");
		assertThat(main.files()).containsExactly("main.lisp");
		assertThat(main.baseDir()).isEqualTo("sw/ningle");
		// :use-reexport contributes every package it names; :import-from only its first.
		assertThat(main.dependsOn()).containsExactly("ningle/app", "ningle/context", "myway");
		// :use #:cl drops out, :nicknames/:export contribute nothing, and the three
		// lack.* packages resolve through register-system-packages -- without that map
		// this would ask for a system called lack.request.
		assertThat(registered(registry, "ningle/app").dependsOn()).containsExactly("ningle/context", "ningle/route",
				"lack-request", "lack-response", "lack-component", "myway", "alexandria");
		assertThat(registered(registry, "ningle/context").dependsOn()).isEmpty();
		assertThat(registered(registry, "ningle/route").dependsOn()).containsExactly("myway");
		// A derived sub-system is a component file, never itself a root for more names.
		assertThat(registered(registry, "ningle/route").packageInferredDir()).isNull();
	}

	@Test
	void aNestedSubSystemNameResolvesToANestedPath() {
		// rove's shape: rove/tests/main is tests/main.lisp, and rove/tests is defined by
		// the .asd itself so the derivation must not overwrite it.
		Map<String, String> systemPackages = new java.util.HashMap<>();
		Map<String, AsdfSystems.LispSystem> registry = registryOf(AsdfSystems.parseAsdSource("""
				(defsystem "rove"
				  :class :package-inferred-system
				  :depends-on ("rove/main"))
				(defsystem "rove/tests"
				  :class :package-inferred-system
				  :depends-on ("rove" "rove/tests/main"))""", "sw/rove/rove.asd", Features.INTERPRETER,
				systemPackages));
		SourceLoader loader = loaderOf(Map.of("sw/rove/tests/main.lisp", """
				(defpackage #:rove/tests/main
				  (:use #:cl #:rove/core/suite/package))""", "sw/rove/core/suite/package.lisp", """
				(defpackage #:rove/core/suite/package
				  (:use #:cl))"""));
		AsdfSystems.inferPackageInferredSystems("rove/tests/main", registry, systemPackages, loader,
				Features.INTERPRETER);
		assertThat(registered(registry, "rove/tests/main").files()).containsExactly("tests/main.lisp");
		assertThat(registered(registry, "rove/tests/main").dependsOn()).containsExactly("rove/core/suite/package");
		assertThat(registered(registry, "rove/core/suite/package").files()).containsExactly("core/suite/package.lisp");
		// The explicitly defined secondary system keeps its own :depends-on.
		assertThat(registered(registry, "rove/tests").dependsOn()).containsExactly("rove", "rove/tests/main");
	}

	@Test
	void thePrimarysPathnameIsWhereItsSubSystemsResolve() {
		// array-operations.asd's shape: :pathname "src/" plus a package-inferred class,
		// so array-operations/all is src/all.lisp.
		Map<String, String> systemPackages = new java.util.HashMap<>();
		Map<String, AsdfSystems.LispSystem> registry = registryOf(AsdfSystems.parseAsdSource("""
				(defsystem "array-operations"
				  :class :package-inferred-system
				  :pathname "src/"
				  :depends-on ("let-plus" "array-operations/all"))""", "sw/aops/array-operations.asd",
				Features.INTERPRETER, systemPackages));
		assertThat(registered(registry, "array-operations").packageInferredDir()).isEqualTo("src");
		AsdfSystems.inferPackageInferredSystems("array-operations/all", registry, systemPackages,
				loaderOf(Map.of("sw/aops/src/all.lisp", """
						(uiop:define-package :array-operations/all
						  (:nicknames :array-operations :aops)
						  (:use-reexport :array-operations/generic))""", "sw/aops/src/generic.lisp", """
						(defpackage :array-operations/generic (:use :cl))""")), Features.INTERPRETER);
		assertThat(registered(registry, "array-operations/all").files()).containsExactly("src/all.lisp");
		assertThat(registered(registry, "array-operations/generic").files()).containsExactly("src/generic.lisp");
	}

	@Test
	void aSubSystemCycleIsLeftToTheLoadersDependsOnCheck() {
		// Two siblings that name each other: the derivation registers both and stops,
		// rather than recursing until the stack ends. The cycle itself is reported by
		// the loader's own :depends-on guard, with the stack that reached it.
		Map<String, String> systemPackages = new java.util.HashMap<>();
		Map<String, AsdfSystems.LispSystem> registry = registryOf(AsdfSystems.parseAsdSource(
				"(defsystem \"lib\" :class :package-inferred-system :depends-on (\"lib/a\"))", "sw/lib/lib.asd",
				Features.INTERPRETER, systemPackages));
		AsdfSystems.inferPackageInferredSystems("lib/a", registry, systemPackages,
				loaderOf(Map.of("sw/lib/a.lisp", "(defpackage #:lib/a (:use #:cl #:lib/b))", "sw/lib/b.lisp",
						"(defpackage #:lib/b (:use #:cl #:lib/a))")),
				Features.INTERPRETER);
		assertThat(registry).containsOnlyKeys("lib", "lib/a", "lib/b");
		assertThat(registered(registry, "lib/a").dependsOn()).containsExactly("lib/b");
		assertThat(registered(registry, "lib/b").dependsOn()).containsExactly("lib/a");
	}

	@Test
	void aSubSystemOfAnOrdinarySystemIsNotDerived() {
		// Only a package-inferred PRIMARY roots sub-system names; a secondary name of an
		// ordinary system stays "the .asd does not define it", as before.
		Map<String, String> systemPackages = new java.util.HashMap<>();
		Map<String, AsdfSystems.LispSystem> registry = registryOf(
				AsdfSystems.parseAsdSource("(defsystem \"lib\" :components ((:file \"main\")))", "sw/lib/lib.asd",
						Features.INTERPRETER, systemPackages));
		AsdfSystems.inferPackageInferredSystems("lib/tests", registry, systemPackages,
				loaderOf(Map.of("sw/lib/tests.lisp", "(defpackage #:lib/tests (:use #:cl))")), Features.INTERPRETER);
		assertThat(registry).containsOnlyKeys("lib");
	}

	@Test
	void aMissingSubSystemFileNamesThePathItLooked() {
		Map<String, String> systemPackages = new java.util.HashMap<>();
		Map<String, AsdfSystems.LispSystem> registry = registryOf(
				AsdfSystems.parseAsdSource("(defsystem \"lib\" :class :package-inferred-system)", "sw/lib/lib.asd",
						Features.INTERPRETER, systemPackages));
		assertThatThrownBy(() -> AsdfSystems.inferPackageInferredSystems("lib/missing", registry, systemPackages,
				loaderOf(Map.of()), Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("sw/lib/missing.lisp");
	}

	@Test
	void aSubSystemFileWithoutALeadingDefpackageIsAHardError() {
		Map<String, String> systemPackages = new java.util.HashMap<>();
		Map<String, AsdfSystems.LispSystem> registry = registryOf(
				AsdfSystems.parseAsdSource("(defsystem \"lib\" :class :package-inferred-system)", "sw/lib/lib.asd",
						Features.INTERPRETER, systemPackages));
		assertThatThrownBy(() -> AsdfSystems.inferPackageInferredSystems("lib/a", registry, systemPackages,
				loaderOf(Map.of("sw/lib/a.lisp", "(in-package #:lib)\n(defun f () 1)")), Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("must be a DEFPACKAGE");
	}

	@Test
	void anUnsupportedClassNamesTheClause() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :class :my-system)"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unsupported :class")
			.hasMessageContaining(":package-inferred-system");
	}

	@Test
	void aPackageInferredSystemWithComponentsIsAHardError() {
		// The class means "there is no component list"; one that also lists components
		// is declaring two graphs, and the derived one would silently win.
		assertThatThrownBy(
				() -> parse("(asdf:defsystem :lib :class :package-inferred-system :components ((:file \"main\")))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("has no :components");
	}

	@Test
	void registerSystemPackagesAcceptsEverySpelling() {
		Map<String, String> systemPackages = new java.util.HashMap<>();
		AsdfSystems.parseAsdSource("""
				(defsystem "lib")
				(register-system-packages "a-sys" '(#:a.one #:a.two))
				(register-system-packages :b-sys :b.one)
				(register-system-packages "c-sys" '("C.ONE"))""", "lib.asd", Features.INTERPRETER, systemPackages);
		assertThat(systemPackages).containsOnly(entry("a.one", "a-sys"), entry("a.two", "a-sys"),
				entry("b.one", "b-sys"), entry("c.one", "c-sys"));
	}

	@Test
	void registerSystemPackagesChecksItsShape() {
		assertThatThrownBy(
				() -> AsdfSystems.parseAsdSource("(register-system-packages \"a\")", "lib.asd", Features.INTERPRETER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("REGISTER-SYSTEM-PACKAGES expects");
	}

}
