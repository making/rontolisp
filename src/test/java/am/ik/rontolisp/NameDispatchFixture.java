package am.ik.rontolisp;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A program that reads, sets and {@code progv}-binds specials BY NAME over more names
 * than one segment of a shared name dispatch holds, among them two pairs whose names'
 * Java {@code hashCode}s collide ({@code *XO*} / {@code *Y0*}, {@code *A_*} /
 * {@code *B@*}): the compilers search a dispatch by key -- the JVM by the name's hash, so
 * a collision must still be told apart by the exact test -- and the answers must be the
 * chain's. Shared by the backend suites, so every backend is held to one expected text.
 */
public final class NameDispatchFixture {

	private NameDispatchFixture() {
	}

	/** How many numbered specials the program declares beside the colliding four. */
	public static final int SPECIALS = 150;

	/** The program. */
	public static final String SOURCE = "(defvar *xo* :xo) (defvar *y0* :y0) (defvar *a_* :a_) (defvar *b@* :b@)\n"
			+ IntStream.range(0, SPECIALS)
				.mapToObj(i -> "(defvar *nd-" + i + "* " + i + ")")
				.collect(Collectors.joining(" ", "", "\n"))
			+ """
					(setq nd-plain :plain)
					(defun nd-get (s) (symbol-value s))
					(defun nd-sum ()
					  (let ((acc 0))
					    (dotimes (i 150) (setq acc (+ acc (nd-get (intern (format nil "*ND-~D*" i))))))
					    acc))
					(print (list (nd-get '*xo*) (nd-get '*y0*) (nd-get '*a_*) (nd-get '*b@*) (nd-get 'nd-plain) (nd-sum)))
					(set '*y0* :y0-set)
					(set (intern "*XO*") :xo-set)
					(set '*nd-0* 1000)
					(print (list *xo* *y0* (eval '*y0*) (eval '(list *a_* *b@*)) (nd-sum)))
					(print (progv '(*b@* *nd-149*) '(:pv 0)
					         (list (nd-get '*b@*) (nd-get '*a_*) (nd-sum) (eval '*nd-149*) (eval '(setq *a_* :ev)))))
					(print (list *b@* *a_* (nd-get '*a_*) (nd-sum)))
					""";

	/**
	 * {@link #SOURCE} with user definitions taking the names of the {@code symbol-value},
	 * {@code progv} and {@code set} runtimes, so those dispatches are spelled inline at
	 * each site -- a {@code set}'s in statement position. The eval runtime keeps the
	 * shared accessor.
	 */
	public static final String INLINE_SOURCE = """
			(defun %symbol-value-dynamic-shadow () nil)
			(defun %progv-bind-shadow () nil)
			(defun %progv-unbind-shadow () nil)
			(defun %set-global-shadow () nil)
			""" + SOURCE;

	/** What {@link #SOURCE} and {@link #INLINE_SOURCE} print, one value per line. */
	public static final String EXPECTED = String.join("\n", "(:XO :Y0 :A_ :B@ :PLAIN 11175)",
			"(:XO-SET :Y0-SET :Y0-SET (:A_ :B@) 12175)", "(:PV :A_ 12026 0 :EV)", "(:B@ :EV :EV 12175)");

}
