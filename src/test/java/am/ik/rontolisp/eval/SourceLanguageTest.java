package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The source-language seam: one read for user source, one pick of the language.
 */
class SourceLanguageTest {

	@Test
	void thePlainReadParsesForms() {
		List<LispVal> forms = SourceLanguage.COMMON_LISP.read("(+ 1 2)", Features.INTERPRETER, null);
		assertThat(forms).hasSize(1);
	}

	@Test
	void aReadEvalDatumArrivesAsAMarker() {
		// Through the seam, #. takes the marker read -- the consumer resolves each
		// marker just before the top-level form holding it evaluates.
		List<LispVal> forms = SourceLanguage.COMMON_LISP.read("(+ 1 #.(+ 1 1))", Features.INTERPRETER, null);
		assertThat(forms).containsExactly(LispReader.readFromString("(+ 1 (%read-eval (+ 1 1)))"));
		assertThat(SourceLanguage.usesReadEvalMarkers("(+ 1 #.(+ 1 1))")).isTrue();
		assertThat(SourceLanguage.usesReadEvalMarkers("(+ 1 2)")).isFalse();
	}

	@Test
	void theStrictReadRefusesReadEval() {
		assertThatThrownBy(() -> SourceLanguage.COMMON_LISP.readStrict("(+ 1 #.(+ 1 1))", Features.INTERPRETER))
			.hasMessageContaining("#.");
	}

	@Test
	void everyExtensionReadsCommonLispUntilALanguageClaimsIt() {
		assertThat(SourceLanguage.forFile("hello.lisp", null)).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.forFile("hello.txt", null)).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.forFile(null, null)).isEqualTo(SourceLanguage.COMMON_LISP);
	}

	@Test
	void schemeClaimsItsExtension() {
		assertThat(SourceLanguage.forFile("hello.scm", null)).isEqualTo(SourceLanguage.SCHEME);
		assertThat(SourceLanguage.forFile("hello.lisp", "scheme")).isEqualTo(SourceLanguage.SCHEME);
		assertThat(SourceLanguage.forFile("hello.scm", "common-lisp")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.isSourceFile("foo.scm")).isTrue();
		assertThat(SourceLanguage.SCHEME.defaultExtension()).isEqualTo(".scm");
		// The seam's read is the whole front end: read, desugar, lower to core forms.
		assertThat(
				SourceLanguage.SCHEME.read("(car x)", Features.INTERPRETER, null).stream().map(LispVal::print).toList())
			.containsExactly("(SETQ RONTOLISP::%SCHEME-FALSE '|#f| RONTOLISP::%SCHEME-UNSPECIFIED '|#!unspecific|)",
					"(CAR |x|)");
	}

	@Test
	void clojureClaimsItsExtension() {
		assertThat(SourceLanguage.forFile("hello.clj", null)).isEqualTo(SourceLanguage.CLOJURE);
		assertThat(SourceLanguage.forFile("hello.lisp", "clojure")).isEqualTo(SourceLanguage.CLOJURE);
		assertThat(SourceLanguage.forFile("hello.lisp", "clj")).isEqualTo(SourceLanguage.CLOJURE);
		assertThat(SourceLanguage.forFile("hello.clj", "common-lisp")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.isSourceFile("foo.clj")).isTrue();
		assertThat(SourceLanguage.CLOJURE.defaultExtension()).isEqualTo(".clj");
		// a .cljc file is Clojure too, its reader conditionals read
		assertThat(SourceLanguage.forFile("hello.cljc", null)).isEqualTo(SourceLanguage.CLOJURE);
		assertThat(SourceLanguage.isSourceFile("foo.cljc")).isTrue();
		assertThat(SourceLanguage.CLOJURE.read("(def x #?(:cljs 1 :clj 2))", Features.INTERPRETER, "x.cljc"))
			.isNotEmpty();
		// The seam's read is the whole front end: read and lower to core forms.
		assertThat(SourceLanguage.CLOJURE.read("(defn f [x] x)", Features.INTERPRETER, null)
			.stream()
			.map(LispVal::print)
			.toList()).containsExactly("(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)",
					"(DEFUN |c%f| (|c%x|) (DECLARE (%ARITY-REPORT \"Wrong number of args (\" \") passed to: user/f\")) |c%x|)",
					"(RONTOLISP::%CLOJURE-NOTE-FN-CLASS #'|c%f| \"user$f\")");
	}

	@Test
	void starlarkClaimsItsExtension() {
		assertThat(SourceLanguage.forFile("build.star", null)).isEqualTo(SourceLanguage.STARLARK);
		assertThat(SourceLanguage.forFile("build.txt", "starlark")).isEqualTo(SourceLanguage.STARLARK);
		assertThat(SourceLanguage.forFile("build.star", "common-lisp")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.isSourceFile("build.star")).isTrue();
		assertThat(SourceLanguage.STARLARK.defaultExtension()).isEqualTo(".star");
		// The seam's read is the whole front end: read and lower to core forms. It has
		// no #., so the strict read is the same one.
		assertThat(SourceLanguage.STARLARK.read("x = len(\"#.\")\n", Features.INTERPRETER, null))
			.isEqualTo(SourceLanguage.STARLARK.readStrict("x = len(\"#.\")\n", Features.INTERPRETER))
			.extracting(LispVal::print)
			.containsExactly("(SETQ %SL-FILE (RONTOLISP::%STARLARK-FILE-BEGIN))",
					"(SETQ |x| (RONTOLISP::%STARLARK-B-LEN \"#.\"))", "(RONTOLISP::%STARLARK-FILE-END %SL-FILE)");
		// A buffer at a time: a block ends at a blank line, a bracket goes on.
		SourceSession session = new SourceSession(SourceLanguage.STARLARK);
		assertThat(List.of("x = 1\n", "def f():\n    pass\n\n", "x = )\n")).allMatch(session::isComplete);
		assertThat(List.of("x = [1,\n", "def f():\n", "def f():\n    pass\n")).noneMatch(session::isComplete);
	}

	@Test
	void javaClaimsItsExtension() {
		assertThat(SourceLanguage.forFile("Hello.java", null)).isEqualTo(SourceLanguage.JAVA);
		assertThat(SourceLanguage.forFile("hello.txt", "java")).isEqualTo(SourceLanguage.JAVA);
		assertThat(SourceLanguage.forFile("Hello.java", "common-lisp")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.isSourceFile("Hello.java")).isTrue();
		assertThat(SourceLanguage.JAVA.defaultExtension()).isEqualTo(".java");
		// The seam's read is the whole front end: read, type and lower to core forms. It
		// has no #., so the strict read is the same one.
		String program = "void main() { IO.println(\"#.\"); }\n";
		assertThat(SourceLanguage.JAVA.read(program, Features.INTERPRETER, null))
			.isEqualTo(SourceLanguage.JAVA.readStrict(program, Features.INTERPRETER))
			.extracting(LispVal::print)
			.containsExactly(
					"(DEFSTRUCT (|Main| (:CONC-NAME |Main.|) (:CONSTRUCTOR |%make-Main| NIL) (:PREDICATE |Main?|)"
							+ " (:COPIER NIL)))",
					"(DEFUN |Main.main()| (|this|) (WRITE-LINE \"#.\"))", "(DEFUN |Main.new()| NIL (|%make-Main|))",
					"(HANDLER-CASE (|Main.main()| (|Main.new()|)) (ERROR (|%c|) (RONTOLISP::%JAVA-UNCAUGHT |%c|)))");
		// A program is a whole file: there is no prompt to read a buffer at.
		assertThatThrownBy(() -> new SourceSession(SourceLanguage.JAVA)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--source-language java has no prompt yet");
	}

	@Test
	void theOverrideNamesTheEntryLanguage() {
		assertThat(SourceLanguage.forFile("hello.lisp", "common-lisp")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.forFile("hello.lisp", "cl")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThatThrownBy(() -> SourceLanguage.forFile("hello.lisp", "elvish"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--source-language");
	}

	@Test
	void theFileSpellingsLiveInOnePlace() {
		assertThat(SourceLanguage.isSourceFile("foo.lisp")).isTrue();
		assertThat(SourceLanguage.isSourceFile("foo")).isFalse();
		assertThat(SourceLanguage.fileNameForModule("UTIL")).isEqualTo("util.lisp");
	}

}
