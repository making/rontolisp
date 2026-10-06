package am.ik.rontolisp;

/**
 * A {@code #+}/{@code #-} guard in front of the datum {@code read} and the
 * multi-argument {@code read-from-string} scan, shared by the backend suites and
 * mirrored by the `read-skips-a-failed-feature-guard` ci-spec case. The guard is tested
 * against the live {@code *features*}; a failed one skips the form behind it and the
 * read answers the next datum, at top level and inside a list or vector. Covered: the
 * stop index and the stream position after the skip, an end of input behind a skipped
 * form ({@code eof-value}), compound expressions, stacked guards (a nested guard inside a
 * skipped form is evaluated too), the keyword reading of an unqualified feature name
 * against a qualified one, and the errors: a missing guarded form, a {@code )} where it
 * is due, a malformed feature expression.
 */
public final class ReadFeatureGuardFixture {

	private ReadFeatureGuardFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defun rfg-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (end-of-file () :end-of-file)
			    (reader-error () :reader-error)
			    (error () :other-error)))
			(defmacro rfg-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(rfg-probe (lambda () ,f))) forms))))
			(defun rfg-reads (text n)
			  (with-input-from-string (s text)
			    (let ((r nil))
			      (dotimes (i n (nreverse r))
			        (push (read s nil :eof) r)))))
			(defun rfg-on (thunk)
			  (let ((*features* (cons :rfg-on *features*))) (funcall thunk)))
			(rfg-row (with-input-from-string (s "#+nope (a b) c d") (read s))
			         (rfg-reads "#+nope a b #-nope c" 3)
			         (rfg-reads "#+nope a" 1)
			         (rfg-reads "(x #+nope y z) #(1 #+nope 2 3)" 2))
			(rfg-row (read-from-string "#+nope (a b) c d" nil nil)
			         (read-from-string "#+nope a" nil :eof)
			         (read-from-string "(a #-nope b #+nope c d)" nil nil)
			         (read-from-string (format nil "  #+nope ; c~% (a b) #|z|# c d") nil nil :start 1))
			(rfg-row (read-from-string "#+(or nope (not nope)) x y" nil nil)
			         (read-from-string "#+(and nope x) x y" nil nil)
			         (read-from-string "#-(or) x y" nil nil)
			         (read-from-string "(#+(and) x #+(or) y)" nil nil))
			(rfg-row (read-from-string "#+nope #+nope a b c" nil nil)
			         (rfg-on (lambda () (read-from-string "#+nope #+rfg-on a b c" nil nil)))
			         (rfg-on (lambda () (read-from-string "#+nope #-rfg-on a b c" nil nil)))
			         (rfg-on (lambda () (read-from-string "#+rfg-on #+nope a b c" nil nil))))
			(rfg-row (rfg-reads "(#+nope #+nope a b c)" 1)
			         (rfg-on (lambda () (rfg-reads "(#+nope #+rfg-on a b c) #-rfg-on d e" 2)))
			         (rfg-on (lambda () (rfg-reads "#+rfg-on 1 2" 2)))
			         (rfg-on (lambda () (rfg-reads "#-rfg-on 1 2" 1))))
			(rfg-row (rfg-on (lambda () (rfg-reads "#+:rfg-on 1 2" 1)))
			         (rfg-on (lambda () (rfg-reads "#+RFG-ON 1 2" 1)))
			         (let ((*features* (cons 'rfg-sym *features*))) (rfg-reads "#+rfg-sym 1 2" 1))
			         (let ((*features* (cons 'rfg-sym *features*))) (rfg-reads "#+cl-user::rfg-sym 1 2" 1)))
			(rfg-row (read-from-string "(a #+nope)" nil nil)
			         (read-from-string "#+nope" nil nil)
			         (read-from-string "#+(foo) a b" nil nil)
			         (read-from-string "#+(not) a b" nil nil))
			(rfg-row (with-input-from-string (s "#+nope a b  c") (list (read s) (char-code (read-char s)) (read s)))
			         (read-from-string "#+nope a b  c" nil nil :preserve-whitespace t)
			         (read-from-string "#+nope a (b) c" nil nil)
			         (rfg-reads "#+nope a #+nope (c) #-nope d" 1))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "((C) ((B C :EOF)) ((:EOF)) (((X Z) #(1 3))))", "((C 15) (:EOF 8) ((A B D) 23) (C 28))", "((X 25) (Y 18) (X 9) ((X) 20))", "((C 19) (B 20) (C 21) (B 20))", "((((C))) (((B C) E)) ((1 2)) ((2)))", "(((1)) ((1)) ((2)) ((1)))", "(:READER-ERROR :END-OF-FILE :OTHER-ERROR :OTHER-ERROR)", "(((B 32 C)) (B 10) ((B) 13) ((D)))");

}
