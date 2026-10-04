package am.ik.rontolisp;

/**
 * {@code remf} past the first key compares the indicator with {@code eq}, as the first
 * key does: a {@code nil} key and indicator, a character and a fixnum by value, a fresh
 * string or list only by identity. Each row prints the list and then {@code remf}'s
 * answer ({@code T} when a pair was removed, whichever key it was; the wasm tail walk
 * answered {@code NIL} past the first key before 2026-10-04). Before 2026-10-04 the tail
 * walk asked the key's own {@code equals} on the interpreter (two fresh {@code "s"} were
 * one key) and on the JVM (a {@code nil} key threw a {@code NullPointerException}, a
 * character was never found). The expected text is SBCL's. Shared by the backend suites,
 * so every backend is held to one expected text.
 */
public final class RemfIndicatorFixture {

	private RemfIndicatorFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun remf-row (plist indicator)
			  (let ((p (copy-list plist)))
			    (let ((removed (remf p indicator)))
			      (print p)
			      (print removed))))
			(let ((k (list 1)) (s (copy-seq "s")))
			  (remf-row (list 'a 1 nil 2 'c 3) 'c)
			  (remf-row (list 'a 1 'b 2 nil 3) nil)
			  (remf-row (list 'a 1 #\\x 2 'c 3) (code-char 120))
			  (remf-row (list 'a 1 70000 2 'c 3) (* 7 10000))
			  (remf-row (list 'a 1 (copy-seq "s") 2 'c 3) (copy-seq "s"))
			  (remf-row (list 'a 1 s 2 'c 3) s)
			  (remf-row (list 'a 1 (list 1) 2 'c 3) k)
			  (remf-row (list 'a 1 k 2 'c 3) k)
			  (remf-row (list 'a 1 'b 2 'c 3) 'a)
			  (remf-row (list 'a 1 'b 2 'c 3) 'z)
			  (remf-row (list 'a 1 'b 2) 'b))
			""";

	/** What {@link #SOURCE} prints, two lines per row. */
	public static final String EXPECTED = """
			(A 1 NIL 2)
			T
			(A 1 B 2)
			T
			(A 1 C 3)
			T
			(A 1 C 3)
			T
			(A 1 "s" 2 C 3)
			NIL
			(A 1 C 3)
			T
			(A 1 (1) 2 C 3)
			NIL
			(A 1 C 3)
			T
			(B 2 C 3)
			T
			(A 1 B 2 C 3)
			NIL
			(A 1)
			T""";

}
