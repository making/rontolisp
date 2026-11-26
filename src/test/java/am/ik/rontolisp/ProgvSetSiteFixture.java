package am.ik.rontolisp;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A program with {@value #SPECIALS} specials and functions and top-level forms holding
 * four {@code progv} sites, and four computed {@code set} sites, each. On the compile
 * paths {@code progv} dispatches each runtime name over the special set and {@code set}
 * over the global set; until 2026-10-04 every site spelled that dispatch inline, ~30 KB
 * of JVM bytecode a {@code progv} here, so the four-site top-level form overflowed the
 * JVM's 64 KB method limit. Shared by the backend suites, so every backend is held to one
 * expected text.
 */
public final class ProgvSetSiteFixture {

	private ProgvSetSiteFixture() {
	}

	/** How many specials the program declares. */
	public static final int SPECIALS = 300;

	private static final String SITES = """
			(defun pvs-four (a b)
			  (list (progv (list a) '(:x) (symbol-value a))
			        (progv (list b) '(:y) (list (symbol-value a) (symbol-value b)))
			        (progv (list a b) '(:p :q) (list (symbol-value a) (symbol-value b) *pvs-0* *pvs-299*))
			        (progv (list a 'pvs-free) '(:r :free) (list (symbol-value a) (symbol-value 'pvs-free)))))
			(defun pvs-read () (list *pvs-150* *pvs-151*))
			(defun pvs-set-four (a b)
			  (set a :sa)
			  (set b :sb)
			  (setf (symbol-value a) (list (symbol-value a)))
			  (set 'pvs-made :made)
			  (list *pvs-10* *pvs-290* (symbol-value 'pvs-made)))
			(print (pvs-four '*pvs-0* '*pvs-299*))
			(print (list *pvs-0* *pvs-299* (boundp 'pvs-free)))
			(print (let ((a '*pvs-1*) (b '*pvs-298*))
			         (list (progv (list a) '(:x) (symbol-value a))
			               (progv (list b) '(:y) (list (symbol-value a) (symbol-value b)))
			               (progv (list a b) '(:p :q) (list (symbol-value a) (symbol-value b) *pvs-1* *pvs-298*))
			               (progv (list b a) '(:s :t) (pvs-read) (list *pvs-298* *pvs-1*)))))
			(print (list (progv '(*pvs-150* *pvs-151*) '(:a :b) (pvs-read)) (pvs-read)))
			(print (list (ignore-errors (progv '(*pvs-5*) '(:e) (error "boom"))) *pvs-5*))
			(print (pvs-set-four '*pvs-10* '*pvs-290*))
			(print (let ((a '*pvs-11*) (b '*pvs-289*))
			         (set a 1)
			         (set b 2)
			         (setf (symbol-value a) (+ (symbol-value a) 10))
			         (set b (* (symbol-value b) 3))
			         (list *pvs-11* *pvs-289*)))
			""";

	/** The program. */
	public static final String SOURCE = IntStream.range(0, SPECIALS)
		.mapToObj(i -> "(defvar *pvs-" + i + "* " + i + ")")
		.collect(Collectors.joining("\n", "", "\n")) + SITES;

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(:X (0 :Y) (:P :Q :P :Q) (:R :FREE))", "(0 299 NIL)",
			"(:X (1 :Y) (:P :Q :P :Q) (:S :T))", "((:A :B) (150 151))", "(NIL 5)", "((:SA) :SB :MADE)", "(11 6)");

}
