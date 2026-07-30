package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL alexandria 1.0.1
 * sources (vendored unmodified under {@code src/test/resources/alexandria}, public domain
 * / 0-clause MIT) load via {@code asdf:load-system} and the public API of BOTH its
 * packages runs on ALL FOUR backends via {@link AsdfLibraryE2eSupport}. Alexandria is the
 * most-depended-on library in the Quicklisp ecosystem ({@code .todo/152}), so it is the
 * one already reached indirectly by cl-postgres, s-sql, postmodern and quri -- this test
 * pins it directly.
 *
 * <p>
 * What the exercise covers, and why each part is here: the binding and control-flow
 * macros ({@code if-let}/{@code when-let*}/{@code switch} family/{@code nth-value-or},
 * which need {@code &whole} and a destructuring pattern after {@code &body}), the
 * macro-writing macros ({@code once-only}/{@code with-gensyms}/{@code named-lambda}/
 * {@code destructuring-case}/{@code parse-body}) used from a user {@code defmacro} at
 * macro-expansion time, the function combinators ({@code compose}/{@code curry}/
 * {@code rcurry}/{@code conjoin}/{@code multiple-value-compose}), the list and
 * plist/alist utilities including {@code mappend} -- whose
 * {@code (apply #'mapcar f lists)} shape is what the variadic {@code #'mapcar} wrapper in
 * {@code BuiltinFunctionWrappers} exists for -- the
 * {@code appendf}/{@code removef}/{@code maxf} modify macros over {@code setf} places,
 * the sequence predicates, {@code map-combinations}/{@code map-permutations}, the
 * hash-table conversions ({@code alist-hash-table} + {@code hash-table-keys}, with
 * results sorted because iteration order is unspecified), {@code ensure-gethash}, the
 * number helpers, {@code symbolicate}/{@code make-keyword}, the condition helpers
 * ({@code required-argument}/{@code ignore-some-conditions}/{@code unwind-protect-case}),
 * {@code featurep} and alexandria-2's {@code subseq*}/{@code line-up-first}/
 * {@code line-up-last} (whose package splices alexandria-1's external symbols with a
 * dotted {@code #.} read-time-eval form).
 *
 * <p>
 * The exercise is {@code examples/asdf/alexandria-demo.lisp} verbatim (minus its header
 * comment); keep the two in sync. Deliberately absent -- each one a gap owned elsewhere,
 * listed in {@code doc/*&#47;guides/asdf-systems.md}:
 * {@code copy-sequence}/{@code median} ({@code coerce} with a computed result type),
 * {@code rotate} ({@code (last list n)}), {@code type=} ({@code subtypep}'s secondary
 * value, {@code .todo/214}), {@code read-file-into-string} ({@code read-sequence} into a
 * character buffer), {@code format-symbol}/{@code ensure-symbol} ({@code intern} with a
 * runtime package) and alexandria-2's {@code dim-in-bounds-p} family ({@code every} over
 * two sequences).
 */
class AlexandriaE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "alexandria")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :alexandria)

			;; binding constructs
			(print (alexandria:if-let ((x (find 3 '(1 2 3)))) (* x 10) :none))
			(print (alexandria:if-let ((x (find 9 '(1 2 3)))) (* x 10) :none))
			(print (alexandria:when-let ((x 5)) (* x 2)))
			(print (alexandria:when-let* ((x 1) (y (+ x 1))) (list x y)))

			;; control flow
			(print (alexandria:switch (3) (1 :one) (3 :three) (t :other)))
			(print (alexandria:eswitch (:a) (:a :got-a) (:b :got-b)))
			(print (alexandria:cswitch (2) (1 :one) (2 :two)))
			(print (alexandria:xor nil 3 nil))
			(print (alexandria:whichever 42))
			(print (alexandria:nth-value-or 0 (values nil :second) :fallback))

			;; definitions
			(alexandria:define-constant +greeting+ "hello" :test #'string=)
			(print +greeting+)

			;; macro-writing macros
			(defmacro my-square (x) (alexandria:once-only (x) `(* ,x ,x)))
			(let ((calls 0))
			  (flet ((bump () (incf calls)))
			    (print (my-square (bump)))
			    (print calls)))
			(defmacro my-double (x) (alexandria:with-gensyms (g) `(let ((,g ,x)) (+ ,g ,g))))
			(print (my-double 21))
			(print (funcall (alexandria:named-lambda greeter (who) (list :hi who)) :you))
			(print (alexandria:destructuring-case '(:add 1 2)
			         ((:add a b) (list :sum (+ a b)))
			         ((t &rest rest) rest)))
			(print (multiple-value-list (alexandria:parse-body '("doc" (+ 1 2)) :documentation t)))

			;; functions
			(print (funcall (alexandria:compose #'1+ #'1+) 40))
			(print (mapcar (alexandria:compose #'car #'cdr) '((1 2 3) (4 5 6))))
			(print (funcall (alexandria:multiple-value-compose #'list (lambda (x) (values x (* x 10)))) 4))
			(print (funcall (alexandria:curry #'+ 1 2) 3))
			(print (funcall (alexandria:rcurry #'- 1) 10))
			(print (funcall (alexandria:conjoin #'evenp #'plusp) 4))
			(print (funcall (alexandria:disjoin #'evenp #'minusp) 3))
			(print (funcall (alexandria:ensure-function #'car) '(1 2)))

			;; lists
			(print (alexandria:iota 5))
			(print (alexandria:iota 4 :start 1 :step 2))
			(print (alexandria:flatten '(1 (2 (3 (4))) 5)))
			(print (alexandria:mappend #'list '(1 2) '(3 4)))
			(print (list (alexandria:proper-list-p '(1 2 3)) (alexandria:circular-list-p '(1 2 3))))
			(print (list (alexandria:lastcar '(1 2 3)) (alexandria:ensure-list 1) (alexandria:ensure-cons '(1 2))))
			(print (alexandria:alist-plist '((:a . 1) (:b . 2))))
			(print (alexandria:plist-alist '(:a 1 :b 2)))
			(print (alexandria:remove-from-plist '(:a 1 :b 2 :c 3) :b))
			(print (alexandria:delete-from-plist (list :a 1 :b 2) :a))
			(print (list (alexandria:assoc-value '((:a . 1) (:b . 2)) :b)
			             (alexandria:rassoc-value '((:a . 1) (:b . 2)) 1)))
			(print (list (alexandria:set-equal '(1 2 3) '(3 2 1)) (alexandria:setp '(1 2 2))))
			(print (alexandria:doplist (k v '(:a 1 :b 2) :done) (print (list k v))))

			;; modify macros over setf places
			(let ((xs (list 1 2)) (n 3))
			  (alexandria:appendf xs '(3 4))
			  (alexandria:removef xs 2)
			  (alexandria:maxf n 10)
			  (alexandria:minf n 4)
			  (print (list xs n)))

			;; sequences
			(print (list (alexandria:emptyp '()) (alexandria:emptyp "x")))
			(print (alexandria:length= '(1 2) #(3 4)))
			(print (list (alexandria:first-elt "abc") (alexandria:last-elt "abc") (alexandria:last-elt '(1 2 3))))
			(print (list (alexandria:starts-with-subseq "he" "hello") (alexandria:ends-with-subseq "lo" "hello")))
			(print (list (alexandria:starts-with 1 '(1 2 3)) (alexandria:ends-with 3 '(1 2 3))))
			(print (alexandria:extremum '(3 1 4 1 5) #'<))
			(print (alexandria:extremum '(3 1 4 1 5) #'>))
			(print (alexandria:sequence-of-length-p '(1 2 3) 3))
			(alexandria:map-combinations (lambda (c) (print c)) '(1 2 3) :length 2)
			(alexandria:map-permutations (lambda (p) (print p)) '(1 2) :length 2)
			(let* ((a (vector 1 2 3)) (b (alexandria:copy-array a)))
			  (setf (aref b 0) 99)
			  (print (list (aref a 0) (aref b 0))))

			;; hash tables (results sorted -- iteration order is unspecified)
			(let ((h (alexandria:alist-hash-table '((:a . 1) (:b . 2)) :test #'eq)))
			  (print (sort (alexandria:hash-table-keys h) #'string< :key #'symbol-name))
			  (print (sort (alexandria:hash-table-values h) #'<))
			  (print (alexandria:ensure-gethash :c h 3))
			  (print (gethash :c h)))
			(print (alexandria:hash-table-plist (alexandria:plist-hash-table '(:x 1))))
			(print (alexandria:hash-table-alist (alexandria:copy-hash-table (alexandria:plist-hash-table '(:x 1)))))
			(alexandria:maphash-keys #'print (alexandria:plist-hash-table '(:x 1)))
			(alexandria:maphash-values #'print (alexandria:plist-hash-table '(:x 1)))

			;; numbers
			(print (list (alexandria:clamp 15 0 10) (alexandria:clamp -1 0 10) (alexandria:clamp 5 0 10)))
			(print (alexandria:lerp 1/2 0 10))
			(print (alexandria:mean '(1 2 3 4)))
			(print (alexandria:variance '(1 2 3 4)))
			(print (alexandria:factorial 10))
			(print (alexandria:binomial-coefficient 10 3))
			(print (list (alexandria:subfactorial 5) (alexandria:count-permutations 5 2)))

			;; symbols
			(print (alexandria:symbolicate 'foo '- 'bar))
			(print (alexandria:make-keyword "HELLO"))

			;; conditions
			(print (handler-case (alexandria:required-argument :x) (error () :signalled)))
			(print (handler-case (alexandria:simple-parse-error "bad ~A" 1) (error () :parse-error)))
			(print (alexandria:ignore-some-conditions (error) (error "boom")))
			(alexandria:unwind-protect-case ()
			    (print :body)
			  (:normal (print :normal))
			  (:abort (print :abort)))

			;; features
			(print (alexandria:featurep :rontolisp))

			;; alexandria-2
			(print (alexandria-2:subseq* "hello world" 6))
			(print (alexandria-2:line-up-first 5 (+ 1) (* 2)))
			(print (alexandria-2:line-up-last 5 (+ 1) (- 20)))
			(print (alexandria-2:delete-from-plist* (list :a 1 :b 2) :a))
			""";

	private static final List<String> EXPECTED = List.of("30", ":NONE", "10", "(1 2)", ":THREE", ":GOT-A", ":TWO", "3",
			"42", ":FALLBACK", "\"hello\"", "1", "1", "42", "(:HI :YOU)", "(:SUM 3)", "(((+ 1 2)) NIL \"doc\")", "42",
			"(2 5)", "(4 40)", "6", "9", "T", "NIL", "1", "(0 1 2 3 4)", "(1 3 5 7)", "(1 2 3 4 5)", "(1 3 2 4)",
			"(T NIL)", "(3 (1) (1 2))", "(:A 1 :B 2)", "((:A . 1) (:B . 2))", "(:A 1 :C 3)", "(:B 2)", "(2 :A)",
			"(T NIL)", "(:A 1)", "(:B 2)", ":DONE", "((1 3 4) 4)", "(T NIL)", "T", "(#\\a #\\c 3)", "(T T)", "(T T)",
			"1", "5", "T", "(1 2)", "(1 3)", "(2 3)", "(1 2)", "(2 1)", "(1 99)", "(:A :B)", "(1 2)", "3", "3",
			"(:X 1)", "((:X . 1))", ":X", "1", "(10 0 5)", "5.0", "5/2", "5/4", "3628800", "120", "(44 20)", "FOO-BAR",
			":HELLO", ":SIGNALLED", ":PARSE-ERROR", "NIL", ":BODY", ":NORMAL", "T", "\"world\"", "12", "14", "(:B 2)");

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
		return "TestAlexandria";
	}

}
