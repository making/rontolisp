package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsdfSystemsTest {

	private static LispVal form(String source) {
		return LispReader.readFromString(source);
	}

	private static AsdfSystems.LispSystem parse(String source) {
		return AsdfSystems.parseDefsystem(form(source), null);
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
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :in-order-to ((test-op (test-op :lib/tests))))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unsupported option :in-order-to");
	}

	@Test
	void unsupportedComponentTypeIsAHardError() {
		assertThatThrownBy(() -> parse("(asdf:defsystem :lib :components ((:doc-file \"README\")))"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unsupported component type :doc-file");
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
				(defsystem :lib/tests :components ((:file "tests")))""", "proj/lib.asd");
		assertThat(systems).hasSize(2);
		assertThat(systems.get(0).name()).isEqualTo("lib");
		assertThat(systems.get(0).baseDir()).isEqualTo("proj");
		assertThat(systems.get(1).name()).isEqualTo("lib/tests");
	}

	@Test
	void parseAsdSourceRejectsOtherForms() {
		assertThatThrownBy(() -> AsdfSystems.parseAsdSource("(print 1)", "lib.asd"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("lib.asd")
			.hasMessageContaining("unsupported form in .asd file");
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

}
