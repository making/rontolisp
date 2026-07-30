package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsdfSystemsTest {

	private static LispVal form(String source) {
		return LispReader.readFromString(source);
	}

	private static AsdfSystems.LispSystem parse(String source) {
		return AsdfSystems.parseDefsystem(form(source), null, Features.INTERPRETER);
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
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :components ((:doc-file \"README\")))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unsupported component type :DOC-FILE");
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
	void parseAsdSourceSkipsAReadEvalGuardWithAWarning() {
		// The ASDF-version-guard idiom: a leading top-level #. form is skipped.
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
		// this replacement. Pin the resolved graph: the non-MOP build drops table.lisp,
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
				"split-sequence", "uiop", "cl-ppcre", "uax-15");
		// :postmodern-thread-safe is declared statically (upstream pushes it from an
		// eval-when, which the reader never sees), so the lock sites take their
		// #+postmodern-thread-safe branch.
		assertThat(system.features()).containsExactly("postmodern-thread-safe");
		assertThat(system.files()).containsExactly("postmodern/package.lisp", "postmodern/config.lisp",
				"postmodern/connect.lisp", "postmodern/json-encoder.lisp", "postmodern/query.lisp",
				"postmodern/prepare.lisp", "postmodern/roles.lisp", "postmodern/util.lisp",
				"postmodern/transaction.lisp", "postmodern/namespace.lisp", "postmodern/execute-file.lisp",
				"postmodern/deftable.lisp");
	}

	@Test
	void thePostmodernMopBuildIsAFeatureFlip() {
		// The :if-feature / (:feature ...) clauses are kept verbatim in the replacement
		// so the DAO layer arrives by activating the feature, not by re-editing the
		// file: table.lisp joins the build and deftable depends on it.
		String source = AsdOverrides.replacementSource("postmodern.asd");
		assertThat(source).isNotNull();
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource(source, "sw/postmodern.asd",
				Features.of("rontolisp", "postmodern-use-mop"));
		assertThat(systems.get(0).files()).contains("postmodern/table.lisp")
			.containsSubsequence("postmodern/table.lisp", "postmodern/deftable.lisp");
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
	void unresolvableReadEvalInIgnoredMetadataDegradesToNil() {
		// A #. in ignored metadata (the :version read-file indirection) must not fail
		// the parse: it degrades to the old warn-and-nil placeholder.
		List<AsdfSystems.LispSystem> systems = AsdfSystems.parseAsdSource("""
				(defsystem :lib
				  :version #.(uiop:read-file-form "version.sexp")
				  :components ((:file "main")))""", "lib.asd", Features.INTERPRETER);
		assertThat(systems.get(0).files()).containsExactly("main.lisp");
	}

}
