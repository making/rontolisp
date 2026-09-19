package am.ik.rontolisp.scheme;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code define-library} and {@code include} as the Common Lisp the front end emits
 * ({@code .kb/scheme-frontend.md}, "Libraries and include"). What the emitted forms DO on
 * each backend is {@code SchemeSpecE2eTest}'s business.
 */
class SchemeLibrariesTest {

	// An in-memory tree: a path is relative to the naming file's directory.
	private static SchemeFiles files(Map<String, String> tree) {
		return (from, path) -> {
			String key = directory(from) + path;
			String text = tree.get(key);
			return text == null ? null : new SchemeFiles.Source(key, text);
		};
	}

	private static String directory(@Nullable String file) {
		return file == null || !file.contains("/") ? "" : file.substring(0, file.lastIndexOf('/') + 1);
	}

	private static String lowered(String source, Map<String, String> tree) {
		return lowered(source, tree, SchemeStandard.RONTOLISP);
	}

	// Everything after the leading (setq false '|#f|).
	private static String lowered(String source, Map<String, String> tree, SchemeStandard standard) {
		List<LispVal> forms = Scheme.read(source, "main.scm", standard, files(tree));
		return forms.subList(1, forms.size()).stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	private static String lowered(String source) {
		return lowered(source, Map.of());
	}

	@Test
	void aLibraryIsLoweredWhereItIsImportedAndItsNamesArePrivate() {
		// The importer's own g is not the library's; the export rename reaches g as h.
		assertThat(lowered("""
				(define-library (m) (export f (rename g h) count) (import (scheme base))
				  (begin (define (f x) (g x)) (define (g x) (* x 2)) (define count 0)))
				(import (scheme base) (m))
				(define (g x) 'mine)
				(f 1) (h 2) count (g 3)""")).isEqualTo("""
				(DEFVAR |s%%(m)%SCM-INSTANTIATED| NIL)
				(DEFUN |s%%(m)f| (|x|) (|s%%(m)g| |x|))
				(DEFUN |s%%(m)g| (|x|) (* |x| 2))
				(IF |s%%(m)%SCM-INSTANTIATED| NIL (PROGN (SETQ |s%%(m)%SCM-INSTANTIATED| T) (SETQ |s%%(m)count| 0)))
				(DEFUN |g| (|x|) '|mine|)
				(|s%%(m)f| 1)
				(|s%%(m)g| 2)
				|s%%(m)count|
				(|g| 3)""");
	}

	@Test
	void aLibraryNamePartWithASpaceKeepsItsNamesApart() {
		// (|a b|) and (a b) would both be s%%(a b) with the parts joined verbatim.
		assertThat(lowered("""
				(define-library (a b) (export f) (import (scheme base)) (begin (define (f) 1)))
				(define-library (|a b|) (export f) (import (scheme base)) (begin (define (f) 2)))
				(import (prefix (a b) x-) (prefix (|a b|) y-))
				(x-f) (y-f)""")).isEqualTo("""
				(DEFUN |s%%(a b)f| NIL 1)
				(DEFUN |s%%(a\\|sb)f| NIL 2)
				(|s%%(a b)f|)
				(|s%%(a\\|sb)f|)""");
	}

	@Test
	void aLibraryOfDefinitionsAloneNeedsNoInstantiationFlag() {
		assertThat(lowered("""
				(define-library (m) (export f) (import (scheme base)) (begin (define (f) 1)))
				(import (m))
				(f)""")).isEqualTo("""
				(DEFUN |s%%(m)f| NIL 1)
				(|s%%(m)f|)""");
	}

	@Test
	void aLibraryIsLoweredOnceAndAfterTheLibrariesItImports() {
		assertThat(lowered("""
				(define-library (b) (export g) (import (scheme base) (a)) (begin (define (g) (f))))
				(define-library (a) (export f) (import (scheme base)) (begin (define (f) 1)))
				(import (a) (b) (only (a) f))
				(g)""")).isEqualTo("""
				(DEFUN |s%%(a)f| NIL 1)
				(DEFUN |s%%(b)g| NIL (|s%%(a)f|))
				(|s%%(b)g|)""");
	}

	@Test
	void anExportedRecordKeepsItsPredicateFusedAndItsAccessorsDirect() {
		assertThat(lowered("""
				(define-library (p) (export make-p p? p-x) (import (scheme base))
				  (begin (define-record-type p (make-p x) p? (x p-x))))
				(import (scheme base) (p))
				(if (p? 1) (p-x (make-p 2)) p?)""")).isEqualTo("""
				(DEFSTRUCT (|s%%(p)p| (:CONSTRUCTOR |s%%(p)make-p| (|s%%(p)p-x|)) (:PREDICATE |s%%(p)p?|) \
				(:COPIER NIL) (:CONC-NAME NIL)) |s%%(p)p-x|)
				(IF (|s%%(p)p?| 1) (|s%%(p)p-x| (|s%%(p)make-p| 2)) \
				(LAMBDA (%SCM-X1) (IF (|s%%(p)p?| %SCM-X1) T RONTOLISP::%SCHEME-FALSE)))""");
	}

	@Test
	void aLibraryIsFoundAsAnSldFileBesideTheProgram() {
		Map<String, String> tree = Map.of("lib/util.sld", """
				(define-library (lib util) (export twice) (import (scheme base))
				  (include "body.scm"))""", "lib/body.scm", "(define (twice x) (* 2 x))");
		assertThat(lowered("(import (lib util)) (twice 3)", tree)).isEqualTo("""
				(DEFUN |s%%(lib util)twice| (|x|) (* 2 |x|))
				(|s%%(lib util)twice| 3)""");
		assertThatThrownBy(() -> lowered("(import (lib other))", tree)).isInstanceOf(LispReadException.class)
			.hasMessage("main.scm:1:1: library (lib other) is not available: no define-library of it precedes the"
					+ " program and there is no lib/other.sld");
		assertThatThrownBy(() -> lowered("(import (lib util))", Map.of("lib/util.sld", "(define x 1)")))
			.hasMessage("main.scm:1:1: lib/util.sld does not define library (lib util)");
	}

	@Test
	void includeSplicesAFileWhereItStandsRelativeToTheIncludingFile() {
		Map<String, String> tree = Map.of("defs.scm", "(define (f x) x) (include \"sub/more.scm\")", "sub/more.scm",
				"(define (g) (include \"leaf.scm\"))", "sub/leaf.scm", "1 2", "upper.scm", "(DEFINE (H) 'ABC)");
		assertThat(lowered("(include \"defs.scm\") (include-ci \"upper.scm\") '(include \"defs.scm\") (f (g))", tree))
			.isEqualTo("""
					(DEFUN |f| (|x|) |x|)
					(DEFUN |g| NIL 1 2)
					(DEFUN |h| NIL '|abc|)
					'(|include| "defs.scm")
					(|f| (|g|))""");
		// A program that includes nothing is emitted exactly as before.
		assertThat(lowered("(define (f x) x) (f 1)", tree)).isEqualTo("(DEFUN |f| (|x|) |x|)\n(|f| 1)");
	}

	@Test
	void anErrorInAnIncludedFileNamesThatFile() {
		Map<String, String> tree = Map.of("sub/bad.scm", "\n  (if)");
		assertThatThrownBy(() -> lowered("(include \"sub/bad.scm\")", tree)).isInstanceOf(LispReadException.class)
			.hasMessage("sub/bad.scm:2:3: malformed if");
		assertThatThrownBy(() -> lowered("(include \"missing.scm\")", tree))
			.hasMessage("main.scm:1:1: include: cannot read missing.scm");
		assertThatThrownBy(() -> lowered("(include x)", tree))
			.hasMessage("main.scm:1:1: include takes file names as strings, got x");
		assertThatThrownBy(() -> lowered("(include \"self.scm\")", Map.of("self.scm", "(include \"self.scm\")")))
			.hasMessage("self.scm:1:1: include: self.scm includes itself");
	}

	@Test
	void aMisdeclaredLibraryIsAPositionedError() {
		assertThatThrownBy(() -> lowered("(define-library (m) (export f) (begin (define g 1)))\n(import (m))"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("main.scm:1:21: the library exports f, which it neither defines nor imports");
		assertThatThrownBy(() -> lowered(
				"(define-library (m) (export f (rename g f)) (begin (define f 1) (define g 1)))" + "\n(import (m))"))
			.hasMessage("main.scm:1:21: the library exports f twice");
		assertThatThrownBy(() -> lowered(
				"(define-library (m) (export m) (begin (define-syntax m (syntax-rules () ((_) 1)))))\n(import (m))"))
			.hasMessage("main.scm:1:21: exporting syntax from a library is not supported by this experimental"
					+ " front end yet: m");
		assertThatThrownBy(() -> lowered("(define-library (m) (frob))\n(import (m))"))
			.hasMessage("main.scm:1:21: unknown library declaration: frob");
		assertThatThrownBy(() -> lowered("(define-library (m) (begin))\n(define-library (m) (begin))"))
			.hasMessage("main.scm:2:1: library (m) is defined twice");
		assertThatThrownBy(() -> lowered("(define-library (scheme mine) (begin))"))
			.hasMessage("main.scm:1:1: library names beginning with scheme are reserved: (scheme mine)");
		assertThatThrownBy(() -> lowered("(define-library (m \"x\") (begin))"))
			.hasMessage("main.scm:1:1: malformed library name: (m \"x\")");
		assertThatThrownBy(() -> lowered("(import (scheme base))\n(define-library (m) (begin))"))
			.hasMessage("main.scm:2:1: define-library must come before the program's import declarations");
		assertThatThrownBy(() -> lowered("""
				(define-library (a) (export f) (import (b)) (begin (define f 1)))
				(define-library (b) (export g) (import (a)) (begin (define g 1)))
				(import (a))""")).hasMessage("main.scm:2:32: library import cycle: (a) -> (b) -> (a)");
	}

	@Test
	void anImportedLibraryVariableCannotBeAssignedAndStrictR7rsRefusesRedefiningIt() {
		String library = "(define-library (m) (export v f) (import (scheme base)) (begin (define v 1) (define (f) v)))\n";
		assertThatThrownBy(() -> lowered(library + "(import (scheme base) (m)) (set! v 2)"))
			.hasMessageEndingWith("cannot assign v: it is imported from a library");
		assertThatThrownBy(
				() -> lowered(library + "(import (scheme base) (m)) (define (f) 2)", Map.of(), SchemeStandard.R7RS))
			.hasMessage("main.scm:2:28: cannot redefine f: it is imported (R7RS 5.6.1)");
		// The default lets the program's own definition win, without touching the
		// library's: the library's f still reads the library's v.
		assertThat(lowered(library + "(import (scheme base) (m)) (define v 5) (define (f) 6) v (f)")).endsWith("""
				(SETQ |v| 5)
				(DEFUN |f| NIL 6)
				|v|
				(|f|)""");
		assertThatThrownBy(() -> lowered("(define-library (m) (begin (define v 1)))\n(import (scheme base) (m))",
				Map.of(), SchemeStandard.R7RS))
			.hasMessage("main.scm:1:1: a library that imports nothing binds nothing, not even define:"
					+ " add (import (scheme base))");
	}

	@Test
	void anR7rsProgramMayBeginWithItsLibraries() {
		assertThat(lowered(
				"(define-library (m) (export f) (import (scheme base)) (begin (define (f) 1)))\n" + "(import (m)) (f)",
				Map.of(), SchemeStandard.R7RS))
			.isEqualTo("(DEFUN |s%%(m)f| NIL 1)\n(|s%%(m)f|)");
		assertThatThrownBy(() -> lowered("(define-library (m) (begin))\n(display 1)", Map.of(), SchemeStandard.R7RS))
			.hasMessage("main.scm:2:1: an R7RS program begins with an import declaration");
	}

	@Test
	void aSessionDeclaresALibraryAtOnePromptAndLowersItAtTheImport() {
		SchemeSession session = Scheme.session(SchemeStandard.RONTOLISP, files(Map.of()));
		assertThat(session.read("(define-library (m) (export f) (import (scheme base)) (begin (define (f) 1)))")
			.stream()
			.flatMap(entry -> entry.forms().stream())
			.map(LispVal::print))
			.containsExactly("(SETQ RONTOLISP::%SCHEME-FALSE '|#f| RONTOLISP::%SCHEME-UNSPECIFIED '|#!unspecific|)");
		List<SchemeTopLevel> entries = session.read("(import (m)) (f)");
		assertThat(entries.getFirst().forms().stream().map(LispVal::print)).containsExactly("(DEFUN |s%%(m)f| NIL 1)");
		assertThat(entries.getFirst().echoes()).isFalse();
		assertThat(entries.getLast().echoes()).isTrue();
		assertThat(entries.getLast().forms().getLast().print()).contains("(|s%%(m)f|)");
	}

}
