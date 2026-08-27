package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL iterate sources
 * (vendored unmodified under {@code src/test/resources/iterate}, MIT; quicklisp dist
 * iterate-release-d27d7ff4-git) load via {@code asdf:load-system} and Jonathan
 * Amsterdam's iteration DSL runs on ALL FOUR backends via {@link AsdfLibraryE2eSupport}.
 * iterate is pure computation with no I/O, so Preview 1 runs it too.
 *
 * <p>
 * The exercise is the clause acceptance list: the numeric driver in all four spellings
 * ({@code from}/{@code to}/{@code downto}/{@code below}/{@code by}), the sequence drivers
 * ({@code in}, {@code on}, {@code in-string}, {@code in-vector}, {@code in-hashtable}),
 * every accumulator ({@code collect}/{@code collecting} with and without {@code into},
 * {@code sum}, {@code multiply}, {@code maximize}, {@code minimize}, {@code counting},
 * {@code always}, {@code thereis}, {@code appending}, {@code reducing}), the control
 * clauses ({@code repeat}, {@code with}, {@code while}, {@code until},
 * {@code if-first-time}, {@code finally}) and a nested {@code iter} feeding a named outer
 * one through {@code (in outer ...)}. Every expected line was verified against the same
 * sources on SBCL 2.2.9 (byte-identical).
 *
 * <p>
 * Substrate the load and run exercise, all of it landed when iterate first ran: the
 * {@code ,.} splice spelling ({@code .kb/defmacro-backquote.md}) that the whole of
 * {@code expand-iterate} is written in, the native {@code #L} / {@code #nL} reader macro
 * ({@code .kb/reader-features.md}), the {@code ldiff} / {@code sublis} / {@code gentemp}
 * prelude defuns, {@code with-hash-table-iterator} ({@code .kb/hash-tables.md}), and
 * {@code macro-function} answering nil for rontolisp's own {@code while}
 * ({@code .kb/symbol-runtime-api.md}) -- iterate's walker asks before it recognizes its
 * own clauses, and a yes made {@code (iter ... (while test))} loop forever. The
 * four-backend run is what keeps a regression in any of them from surfacing only the next
 * time someone quickloads iterate.
 *
 * <p>
 * iterate's own {@code iterate-test.lisp} does not ride along: it is written against
 * {@code rt}, which is not vendored here, so the acceptance list above is the proof.
 */
class IterateE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "iterate").toAbsolutePath().toString();

	// use-package, not iterate:iter: a clause head is an ordinary symbol read in the
	// current package, so the qualified spelling leaves FOR/COLLECT in CL-USER and
	// iterate does not recognize them. SBCL behaves identically.
	private static final String EXERCISE = """
			(asdf:load-system "iterate")
			(use-package :iterate)
			;; --- the numeric driver, in every spelling ---
			(print (iter (for i from 1 to 5) (collect i)))
			(print (iter (for i from 10 downto 6) (collect i)))
			(print (iter (for i from 0 below 5 by 2) (collect i)))
			(print (iter (for i from 1 to 10 by 3) (sum i)))
			;; --- the sequence drivers ---
			(print (iter (for x in '(1 2 3 4)) (multiply x)))
			(print (iter (for x on '(a b c)) (collect (copy-list x))))
			(print (iter (for c in-string "abc") (collect c)))
			(print (iter (for e in-vector #(3 1 2)) (maximize e)))
			;; --- the remaining accumulators ---
			(print (iter (for x in '(5 3 9 1)) (minimize x)))
			(print (iter (for x in '(1 2 3 4 5)) (counting (evenp x))))
			(print (iter (for x in '(2 4 6)) (always (evenp x))))
			(print (iter (for x in '(1 3 4 5)) (thereis (and (evenp x) x))))
			(print (iter (for x in '((1 2) (3) (4 5))) (appending x)))
			(print (iter (for x in '(1 2 3 4)) (reducing x by #'+ initial-value 100)))
			;; --- collecting into a named accumulator, read back by finally ---
			(print (iter (for x in '(1 2 3)) (collecting (* x x) into squares)
			             (finally (return squares))))
			;; --- the control clauses ---
			(print (iter (repeat 3) (for x from 7) (collect x)))
			(print (iter (with n = 4) (for i from 1 to n) (sum (* i i))))
			(print (iter (for i from 1) (while (< i 4)) (collect i)))
			(print (iter (for i from 1) (until (> i 3)) (collect i)))
			(print (iter (for x in '(a b c)) (if-first-time (collect :first) (collect x))))
			;; --- a nested iter feeding the named outer one ---
			(print (iter outer (for i from 1 to 3)
			             (iter (for j from 1 to i) (in outer (collect (list i j))))))
			;; --- the hash driver, sorted so the walk order cannot decide the answer ---
			(let ((h (make-hash-table)))
			  (setf (gethash 'a h) 1)
			  (setf (gethash 'b h) 2)
			  (setf (gethash 'c h) 3)
			  (print (sort (iter (for (k v) in-hashtable h) (collect (cons k v)))
			               #'string< :key (lambda (p) (symbol-name (car p))))))
			""";

	private static final List<String> EXPECTED = List.of("(1 2 3 4 5)", "(10 9 8 7 6)", "(0 2 4)", "22", "24",
			"((A B C) (B C) (C))", "(#\\a #\\b #\\c)", "3", "1", "2", "T", "4", "(1 2 3 4 5)", "110", "(1 4 9)",
			"(7 8 9)", "30", "(1 2 3)", "(1 2 3)", "(:FIRST B C)", "((1 1) (2 1) (2 2) (3 1) (3 2) (3 3))",
			"((A . 1) (B . 2) (C . 3))");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
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
		return "IterateDemo";
	}

}
