package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL esrap sources
 * (vendored unmodified under {@code src/test/resources/esrap}, MIT; quicklisp dist
 * esrap-20260101-git) load via {@code asdf:load-system} over the vendored alexandria and
 * trivial-with-current-source-form, and the PEG parser runs on ALL FOUR backends via
 * {@link AsdfLibraryE2eSupport}. The parser is pure computation, so Preview 1 runs it
 * too.
 *
 * <p>
 * The exercise is {@code .todo/248}'s acceptance list, in two halves. First esrap's own
 * README smoke example -- an inline {@code (or "foo" "bar")} expression, a {@code rule}
 * instance registered with {@code add-rule}, a {@code defrule} with a {@code :lambda}
 * transform, and a semantic predicate ({@code (oddp decimal)}) with and without
 * {@code :junk-allowed}. Then mito's OWN grammar: {@code migration/sql-parse.lisp}
 * verbatim from mito-20260101-git, which {@code mito.migration:migrate} uses to split a
 * migration file into statements -- case-insensitive {@code (~ "END")} terminals, nested
 * {@code BEGIN}/{@code CASE}/{@code IF} blocks, single- and double-quoted literals and
 * PostgreSQL {@code $$ ... $$} dollar quoting, all of which exist precisely so a
 * {@code ;} inside a string or a function body does NOT end a statement. Every expected
 * line was verified against the same sources on SBCL 2.2.9 (byte-identical).
 *
 * <p>
 * Substrate the load and run exercise, each landed for this milestone: the
 * {@code (cons (eql x) ...)} compound type specifier (esrap's whole expression-kind
 * table), the SIZE of {@code (string 1)} and {@code (simple-vector 41)} (the ordered-
 * choice classifier and the packrat cache's representation dispatch), a constant DOTTED
 * pair in a template that also carries a nested backquote, more than one
 * {@code (:constructor ...)} on one {@code defstruct}, {@code :test-not}, {@code reduce}
 * over an EMPTY sequence, {@code count} with {@code :end}, the {@code char-lessp} family
 * and {@code graphic-char-p}, {@code write} plus the pprint-dispatch tables and
 * {@code pprint-logical-block} ({@code .kb/pretty-printer.md}), and the {@code format}
 * logical block with {@code ~/name/} ({@code .kb/format.md}) -- which is what makes
 * esrap's parse-error report readable rather than a wall of unrendered directives.
 */
class EsrapE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "esrap").toAbsolutePath().toString();

	// mito's migration/sql-parse.lisp, verbatim (mito-20260101-git). parse-statements is
	// what mito.migration:migrate calls on a migration file.
	private static final String MITO_SQL_PARSE = """
			(defpackage #:mito.migration.sql-parse
			  (:use #:cl
			        #:esrap)
			  (:shadow #:space)
			  (:export #:parse-statements))
			(in-package #:mito.migration.sql-parse)

			(defrule end (~ "END"))

			(defrule begin-end (and (~ "BEGIN") (+ (or quoted-symbol quoted-string begin-end case-end if-end (not end))) end)
			  (:destructure (b content e)
			    (format nil "~A~{~A~}~A" b content e)))

			(defrule case-end (and (~ "CASE") (+ (or quoted-symbol quoted-string begin-end case-end if-end (not end))) end)
			  (:destructure (b content e)
			    (format nil "~A~{~A~}~A" b content e)))

			(defrule if-end (and (~ "IF") (+ (or quoted-symbol quoted-string begin-end case-end if-end (not (~ "END IF")))) (~ "END IF"))
			  (:destructure (b content e)
			    (format nil "~A~{~A~}~A" b content e)))

			(defrule quoted-symbol (and #\\" (+ (or (not #\\") (and #\\\\ #\\"))) #\\")
			  (:destructure (d1 content d2)
			    (declare (ignore d1 d2))
			    (format nil "\\"~{~A~}\\"" content)))

			(defrule pg-dollar-sign (or (and #\\$ (* (not space)) #\\$)
			                            (and #\\$ #\\$)))

			(defrule quoted-string (or (and #\\'
			                                (+ (or (not #\\')
			                                       (and #\\\\ #\\')))
			                                #\\')
			                           (and pg-dollar-sign
			                                (+ (or (not #\\$)
			                                       (and #\\\\ #\\$)))
			                                pg-dollar-sign))
			  (:text t))

			(defrule space (or #\\Space #\\Newline #\\Return #\\Tab))

			(defrule statement (and (* space)
			                        (+ (or quoted-symbol quoted-string begin-end (not #\\;))) (? #\\;)
			                        (* space))
			  (:destructure (s1 content semicolon s2)
			    (declare (ignore s1 s2))
			    (format nil "~{~A~}~:[~;;~]" content semicolon)))

			(defun parse-statements (content)
			  (values (esrap:parse '(* statement) content)))
			""";

	private static final String EXERCISE = """
			(asdf:load-system "esrap")
			;; --- esrap's README smoke example ---
			(print (multiple-value-list (esrap:parse '(or "foo" "bar") "foo")))
			(esrap:add-rule 'foo+ (make-instance 'esrap:rule :expression '(+ "foo")))
			(print (multiple-value-list (esrap:parse 'foo+ "foofoofoo")))
			(esrap:defrule decimal (+ (or "0" "1" "2" "3" "4" "5" "6" "7" "8" "9"))
			  (:lambda (list) (parse-integer (format nil "~{~A~}" list))))
			(print (multiple-value-list (esrap:parse 'decimal "123")))
			(print (multiple-value-list (esrap:parse '(oddp decimal) "123")))
			(print (multiple-value-list (esrap:parse '(evenp decimal) "123" :junk-allowed t)))
			;; --- mito's migration grammar ---
			%s
			(in-package :cl-user)
			(dolist (s (mito.migration.sql-parse:parse-statements
			            "CREATE TABLE a (id int); CREATE INDEX i ON a (id); \
			CREATE FUNCTION f() RETURNS trigger AS $$ BEGIN RETURN 'x;y'; END $$ LANGUAGE plpgsql;"))
			  (format t "[~a]~%%" s))
			;; A ; inside a double-quoted identifier and inside a BEGIN...END block stays
			;; in its statement -- the whole reason this grammar exists.
			(dolist (s (mito.migration.sql-parse:parse-statements
			            "ALTER TABLE \\"we;ird\\" ADD COLUMN c int; SELECT 1;"))
			  (format t "[~a]~%%" s))
			;; The parse-error report: the format logical block and ~/esrap:print-terminal/
			(print (handler-case (esrap:parse 'decimal "12x")
			         (esrap:esrap-parse-error (e) (princ-to-string e))))
			""".formatted(MITO_SQL_PARSE);

	private static final List<String> EXPECTED = List.of("(\"foo\" NIL T)", "((\"foo\" \"foo\" \"foo\") NIL T)",
			"(123 NIL T)", "(123 NIL T)", "(NIL 0)", "[CREATE TABLE a (id int);]", "[CREATE INDEX i ON a (id);]",
			"[CREATE FUNCTION f() RETURNS trigger AS $$ BEGIN RETURN 'x;y'; END $$ LANGUAGE plpgsql;]",
			"[ALTER TABLE \"we;ird\" ADD COLUMN c int;]", "[SELECT 1;]",
			// The report is one printed string spanning several physical lines (the
			// driver trims each, and the blank separators stay blank). SBCL additionally
			// names each character (DIGIT_ZERO ...) -- a Unicode-name extension of its
			// char-name; CL specifies nil for a graphic character, which is what
			// rontolisp answers.
			"\"At", "", "12x", "^ (Line 1, Column 2, Position 2)", "", "In context DECIMAL:", "",
			"While parsing DECIMAL. Expected:", "", "the character 0", "or the character 1", "or the character 2",
			"or the character 3", "or the character 4", "or the character 5", "or the character 6",
			"or the character 7", "or the character 8", "or the character 9\"");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(Path.of("src", "test", "resources", "alexandria").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "trivial-with-current-source-form").toAbsolutePath().toString());
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "EsrapDemo";
	}

}
