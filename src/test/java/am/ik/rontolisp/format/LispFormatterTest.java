package am.ik.rontolisp.format;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispLexer;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.reader.Token;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class LispFormatterTest {

	@Test
	void indentsADefunBodyByTwo() {
		assertThat(LispFormatter.format("""
				(defun f (x)
				(print x)
				(terpri))
				""")).isEqualTo("""
				(defun f (x)
				  (print x)
				  (terpri))
				""");
	}

	@Test
	void keepsAFormThatFitsOnOneLine() {
		assertThat(LispFormatter.format("(defun square (x)\n   (* x x))\n")).isEqualTo("(defun square (x) (* x x))\n");
	}

	@Test
	void keepsASharpCLiteralIntact() {
		// #C( is one lexeme (the dispatch plus its contents), so format keeps it
		// glued and never splits it into "#C" and a list (.todo/751).
		assertThat(LispFormatter.format("(setq z #C(1 2))\n")).isEqualTo("(setq z #C(1 2))\n");
		assertThat(LispFormatter.format("(setq z #c(1/2 -1/3))\n")).isEqualTo("(setq z #c(1/2 -1/3))\n");
	}

	@Test
	void neverJoinsTwoBodyForms() {
		// Two forms performed in order are two lines however short they are; only a
		// single-form body may be joined onto the header line.
		assertThat(LispFormatter.format("(when x (a) (b))\n")).isEqualTo("""
				(when x
				  (a)
				  (b))
				""");
		assertThat(LispFormatter.format("(when x\n  (a))\n")).isEqualTo("(when x (a))\n");
	}

	@Test
	void neverJoinsABodyNestedInsideAFormThatWouldFit() {
		// The whole defun fits on one line, but its when may not be flattened, so neither
		// may anything containing it.
		assertThat(LispFormatter.format("(defun f (x) (when x (a) (b)))\n")).isEqualTo("""
				(defun f (x)
				  (when x
				    (a)
				    (b)))
				""");
	}

	@Test
	void alignsIfBranchesUnderTheTest() {
		assertThat(LispFormatter
			.format("(if (long-predicate-name a b c) (then-branch-here a b c) (else-branch-here a b c))\n"))
			.isEqualTo("""
					(if (long-predicate-name a b c)
					    (then-branch-here a b c)
					    (else-branch-here a b c))
					""");
	}

	@Test
	void alignsCallArgumentsUnderTheFirstOne() {
		assertThat(LispFormatter
			.format("(some-function (first-argument aaaa) (second-argument bbbb) (third-argument cccc))\n"))
			.isEqualTo("""
					(some-function (first-argument aaaa) (second-argument bbbb)
					               (third-argument cccc))
					""");
	}

	@Test
	void alignsCondClausesUnderTheFirstOne() {
		assertThat(LispFormatter.format("""
				(cond ((null-check a) (result-a b))
				((null-check b) (result-b a))
				(t (both a b)))
				""")).isEqualTo("""
				(cond ((null-check a) (result-a b))
				      ((null-check b) (result-b a))
				      (t (both a b)))
				""");
		// A cond short enough for one line keeps it: clauses are alternatives, not a
		// sequence, so nothing forces them apart.
		assertThat(LispFormatter.format("(cond ((null a) b) (t a))\n")).isEqualTo("(cond ((null a) b) (t a))\n");
	}

	@Test
	void keepsAClauseBodyBesideAOneTokenPredicate() {
		// (t ...) is the clause nearly every cond ends with, and a line holding nothing
		// but it is a line spent on one token. This body has to break wherever it starts,
		// so keeping it beside the t costs it no line and saves the clause one.
		assertThat(LispFormatter.format("""
				(defun app (env)
				  (let ((path (getf env :path-info)))
				    (cond ((string= path "/get") (echo-when env :GET nil))
				          (t (json-response 404 (rontolisp:plist-hash-table \
				(list :error "not found" :path path)))))))
				""".replace("\\\n", ""))).isEqualTo("""
				(defun app (env)
				  (let ((path (getf env :path-info)))
				    (cond ((string= path "/get") (echo-when env :GET nil))
				          (t (json-response 404
				                            (rontolisp:plist-hash-table
				                             (list :error "not found" :path path)))))))
				""");
	}

	@Test
	void keepsABodyOfTwoAndAListPredicateOffTheClauseLine() {
		// The two structural halves of the rule. A body of TWO forms may not be lifted at
		// all -- its first form would sit in one column and the rest in another, which is
		// the one thing a body may never do. A LIST predicate is a test the reader reads,
		// so its line is not a wasted one and its body is better off below it than broken
		// open to reach it.
		assertThat(LispFormatter.format("""
				(defun app (env)
				  (let ((path (getf env :path-info)))
				    (cond ((string= path "/get") (echo-when env :GET nil))
				          (t (json-response 404 (rontolisp:plist-hash-table \
				(list :error "not found"))) (log-it path))
				          ((and (stringp path) (search "/x" path)) (json-response 404 \
				(rontolisp:plist-hash-table (list :error "not found" :path path)))))))
				""".replace("\\\n", ""))).isEqualTo("""
				(defun app (env)
				  (let ((path (getf env :path-info)))
				    (cond ((string= path "/get") (echo-when env :GET nil))
				          (t
				           (json-response 404
				            (rontolisp:plist-hash-table (list :error "not found")))
				           (log-it path))
				          ((and (stringp path) (search "/x" path))
				           (json-response 404
				                          (rontolisp:plist-hash-table
				                           (list :error "not found" :path path)))))))
				""");
	}

	@Test
	void keepsABodyThatFitsBelowItsPredicateBelowIt() {
		// sxql's. The body fits on one line below the predicate, so the clause is two
		// lines either way -- and lifting it would buy that second line back by breaking
		// the body open to reach a nineteen-column name. A lift is taken only when it
		// SAVES a line.
		assertThat(LispFormatter.format("""
				(defun add-where-clause (query where-clause)
				  (etypecase query
				    (select-query-state (push where-clause \
				(select-query-state-where-clauses query)))
				    (update-query-state (push where-clause \
				(update-query-state-where-clauses query)))))
				""".replace("\\\n", ""))).isEqualTo("""
				(defun add-where-clause (query where-clause)
				  (etypecase query
				    (select-query-state
				     (push where-clause (select-query-state-where-clauses query)))
				    (update-query-state
				     (push where-clause (update-query-state-where-clauses query)))))
				""");
	}

	@Test
	void keepsABodyBelowItsPredicateWhenTheLiftWouldCostItRoom() {
		// cl-ppcre's, and the reason the rule is decided by rendering both ways rather
		// than by any bound on the predicate's width: what the lift costs is paid again
		// by
		// everything nested inside the body. Lifting :string here pushes its whole
		// twelve-line body eight columns right and past the margin, so it is refused --
		// while the (t ...) nested three levels deeper inside it still pays for itself.
		assertThat(LispFormatter.format("""
				(defun f (input)
				  (case input-type
				    (:string (write-string (cond (simple-calls (apply token (nsubseq target-string \
				match-start match-end) (map 'list #'reg reg-starts reg-ends))) (t (funcall token \
				target-string start end))) s))))
				""".replace("\\\n", ""))).isEqualTo("""
				(defun f (input)
				  (case input-type
				    (:string
				     (write-string (cond (simple-calls
				                          (apply token
				                                 (nsubseq target-string match-start match-end)
				                                 (map 'list #'reg reg-starts reg-ends)))
				                         (t (funcall token target-string start end))) s))))
				""");
	}

	@Test
	void alignsLetBindingsAndIndentsTheBodyByTwo() {
		assertThat(LispFormatter.format("""
				(let ((alpha (compute-something 1)) (beta (compute-something 2)) (gamma (compute 3)))
				(use alpha beta)
				(use gamma beta))
				""")).isEqualTo("""
				(let ((alpha (compute-something 1))
				      (beta (compute-something 2))
				      (gamma (compute 3)))
				  (use alpha beta)
				  (use gamma beta))
				""");
	}

	@Test
	void indentsALocalFunctionLikeADefun() {
		// (rec (args) body) is structurally a function call; only labels knows it is a
		// definition, which is what the binding-list child style carries.
		assertThat(LispFormatter.format("""
				(labels ((rec (list acc) (if (endp list) acc (rec (cdr list) (cons (car list) acc)))))
				(rec x nil))
				""")).isEqualTo("""
				(labels ((rec (list acc)
				           (if (endp list) acc (rec (cdr list) (cons (car list) acc)))))
				  (rec x nil))
				""");
	}

	@Test
	void givesEachLoopClauseALine() {
		assertThat(LispFormatter
			.format("(loop for item in collection when (predicate item) collect (transform item) into results)\n"))
			.isEqualTo("""
					(loop for item in collection
					      when (predicate item)
					      collect (transform item) into results)
					""");
	}

	@Test
	void neverCollapsesADoLoopThatHasABody() {
		// Its three parts -- bindings, end test, body -- are told apart by the layout and
		// nothing else, so a do with a body is never a one-liner however short it is. One
		// without a body has nothing to separate.
		assertThat(LispFormatter.format("(do ((i 0 (+ i 1))) ((>= i n) out) (setq out (cons 0 out)))\n")).isEqualTo("""
				(do ((i 0 (+ i 1)))
				    ((>= i n) out)
				  (setq out (cons 0 out)))
				""");
		assertThat(LispFormatter.format("(do ((i 0 (1+ i))) ((= i 5)))\n"))
			.isEqualTo("(do ((i 0 (1+ i))) ((= i 5)))\n");
	}

	@Test
	void putsTheDoEndTestPastTheBody() {
		assertThat(LispFormatter.format("(do ((i 0 (1+ i))) ((= i n) result) (body i) (more i))\n")).isEqualTo("""
				(do ((i 0 (1+ i)))
				    ((= i n) result)
				  (body i)
				  (more i))
				""");
	}

	private static String scheme(String source) {
		return LispFormatter.format(source, LispFormatter.DEFAULT_WIDTH, IndentRules.Dialect.SCHEME);
	}

	private static String scheme(String source, int width) {
		return LispFormatter.format(source, width, IndentRules.Dialect.SCHEME);
	}

	@Test
	void indentsANamedLetWithItsBindingsOnTheFirstLine() {
		// The loop name is taken for the binding list by the Common Lisp rule; in
		// Scheme the name AND the bindings stay on the first line.
		assertThat(
				scheme("(let walk ((n start) (path '()))\n(if (= n 1) (walk 2 path) (walk (foo n) (cons n path))))\n"))
			.isEqualTo("""
					(let walk ((n start) (path '()))
					  (if (= n 1) (walk 2 path) (walk (foo n) (cons n path))))
					""");
		assertThat(scheme("(let ((a 1) (b 2)) (+ a b))\n")).isEqualTo("(let ((a 1) (b 2)) (+ a b))\n");
		assertThat(scheme("(let* loop ((i 0)) (loop (+ i 1)))\n")).isEqualTo("(let* loop ((i 0)) (loop (+ i 1)))\n");
	}

	@Test
	void laysOutADefineByItsHeaderShape() {
		// A procedure keeps its header on the first line; a name ending in ! is
		// nothing special once define has a rule of its own.
		assertThat(
				scheme("(define (define-variable! name value env)\n(set-cdr! binding value) (set-car! env value))\n"))
			.isEqualTo("""
					(define (define-variable! name value env)
					  (set-cdr! binding value)
					  (set-car! env value))
					""");
		assertThat(scheme("(define limit 100000)\n")).isEqualTo("(define limit 100000)\n");
	}

	@Test
	void breaksADefineRecordTypeAfterItsName() {
		// The Common Lisp guess joins the constructor onto the first line.
		assertThat(scheme(
				"(define-record-type compound (make-compound parameters body env) compound? (parameters compound-parameters))\n"))
			.isEqualTo("""
					(define-record-type compound
					  (make-compound parameters body env)
					  compound?
					  (parameters compound-parameters))
					""");
	}

	@Test
	void laysOutDefineValuesAndLetValuesLikeLet() {
		assertThat(scheme("(define-values (a b) (values 1 2))\n")).isEqualTo("(define-values (a b) (values 1 2))\n");
		assertThat(scheme("(let-values (((a b) (values 1 2)) ((c d) (values 3 4))) (+ a b c d))\n", 40)).isEqualTo("""
				(let-values (((a b) (values 1 2))
				             ((c d) (values 3 4)))
				  (+ a b c d))
				""");
	}

	@Test
	void keepsADoHeaderOnOneLineWhileItFits() {
		// Unlike the Common Lisp do, nothing is forced onto a line of its own: the
		// bindings and the end test stay on the first line, the body follows at 2.
		assertThat(scheme("(do ((n 1 (+ n 1))) ((= n 1000))\n(display n))\n")).isEqualTo("""
				(do ((n 1 (+ n 1))) ((= n 1000))
				  (display n))
				""");
		assertThat(scheme("(do ((i 0 (+ i 1))) ((= i 5)))\n")).isEqualTo("(do ((i 0 (+ i 1))) ((= i 5)))\n");
	}

	@Test
	void laysOutDelayAndBeginAsBodiesInScheme() {
		assertThat(scheme("(delay (compute x))\n")).isEqualTo("(delay (compute x))\n");
		assertThat(scheme("(begin (m-eval (car exprs) env) (eval-sequence (cdr exprs) env))\n", 40)).isEqualTo("""
				(begin
				  (m-eval (car exprs) env)
				  (eval-sequence (cdr exprs) env))
				""");
		// In Common Lisp begin is an unknown call, and stays one.
		assertThat(LispFormatter.format("(begin (m-eval (car exprs) env) (eval-sequence (cdr exprs) env))\n", 40))
			.isEqualTo("""
					(begin (m-eval (car exprs) env)
					       (eval-sequence (cdr exprs) env))
					""");
	}

	@Test
	void liftsAClauseBodyOnlyWhenItFitsOnOneLine() {
		// A multi-line body broken open to reach the predicate reads worse than the
		// line the predicate keeps to itself, ties included; a single line stays.
		assertThat(scheme(
				"(cond (else (let ((s (chain-length n))) (if (> s best-steps) (loop bound (+ n 1) n s) (loop bound (+ n 1) best-start best-steps)))))\n",
				40))
			.isEqualTo("""
					(cond (else
					       (let ((s (chain-length n)))
					         (if (> s best-steps)
					             (loop bound (+ n 1) n s)
					             (loop
					               bound
					               (+ n 1)
					               best-start
					               best-steps)))))
					""");
		assertThat(scheme("(cond ((null? sequence-with-a-much-longer-name) '()) (else (keep pred (cdr seq))))\n"))
			.isEqualTo("""
					(cond ((null? sequence-with-a-much-longer-name) '())
					      (else (keep pred (cdr seq))))
					""");
	}

	@Test
	void keepsACaseRecipientBesideItsArrow() {
		assertThat(scheme("(case x ((1 2) (quote one)) ((3) => (lambda (y) (+ y 1))) (else (quote other)))\n", 40))
			.isEqualTo("""
					(case x
					  ((1 2) (quote one))
					  ((3) => (lambda (y) (+ y 1)))
					  (else (quote other)))
					""");
	}

	@Test
	void laysOutBrokenQuotedDataOnePerLine() {
		// A quote is data however operator-like its head, in both spellings.
		assertThat(scheme("'(+ - * = < > car cdr cons null? list display newline)\n", 40)).isEqualTo("""
				'(+
				  -
				  *
				  =
				  <
				  >
				  car
				  cdr
				  cons
				  null?
				  list
				  display
				  newline)
				""");
		assertThat(scheme("(quote (+ - * = < > car cdr cons null? list display newline))\n", 40)).isEqualTo("""
				(quote (+
				        -
				        *
				        =
				        <
				        >
				        car
				        cdr
				        cons
				        null?
				        list
				        display
				        newline))
				""");
	}

	@Test
	void readsADatumCommentAsAPrefix() {
		// #; comments out one datum; the formatter keeps it glued to that datum the
		// way it keeps every other prefix.
		assertThat(scheme("(define y #;(skipped datum) 5)\n")).isEqualTo("(define y #;(skipped datum) 5)\n");
		// #true, a named character and brackets are ordinary token characters here
		// and survive verbatim.
		assertThat(scheme("(define x #true)\n")).isEqualTo("(define x #true)\n");
		assertThat(scheme("(define z #\\x41)\n")).isEqualTo("(define z #\\x41)\n");
	}

	@Test
	void letsUnknownSchemeOperatorsReadAsCalls() {
		// The Common Lisp guess reads a def- name as a definition; in Scheme an
		// unknown head is a procedure call, ! or no.
		assertThat(scheme("(define-variable! target (m-eval (caddr expr) env) env)\n"))
			.isEqualTo("(define-variable! target (m-eval (caddr expr) env) env)\n");
	}

	@Test
	void keepsKeywordArgumentsWithTheirValues() {
		assertThat(LispFormatter
			.format("(with-open-file (s path :direction :output :if-exists :supersede :if-does-not-exist :create))\n"))
			.isEqualTo("""
					(with-open-file (s path
					                   :direction :output
					                   :if-exists :supersede
					                   :if-does-not-exist :create))
					""");
	}

	@Test
	void keepsAPlistOptionWithItsValueOutsideCallPosition() {
		// A defsystem's tail is a body of options, not an argument list, so it does not
		// go
		// through the call layout -- and without pairing there it split every keyword
		// from
		// its value, one item per line.
		assertThat(LispFormatter.format("""
				(defsystem "postmodern" :description "PostgreSQL programming API" :depends-on \
				("alexandria" "bordeaux-threads" "cl-postgres" "s-sql" "split-sequence" "uiop"))
				""".replace("\\\n", ""))).isEqualTo("""
				(defsystem "postmodern"
				  :description "PostgreSQL programming API"
				  :depends-on ("alexandria" "bordeaux-threads" "cl-postgres" "s-sql"
				               "split-sequence" "uiop"))
				""");
	}

	@Test
	void startsALoneArgumentAtTheShallowestColumnWhenItMustBreak() {
		// Aligning under the first argument is worth depth only when there are siblings
		// to
		// align WITH. One argument that has to break gets the shallowest column instead,
		// so
		// the calls nested in it inherit room rather than a deficit -- the whole reason a
		// chain of nested calls used to walk off the right edge.
		assertThat(LispFormatter.format("""
				(rontolisp:plist-hash-table (list :args (rontolisp:alist-hash-table \
				(rontolisp:query-params (getf env :query-string))) :headers (getf env :headers) \
				:path (getf env :path-info)))
				""".replace("\\\n", ""))).isEqualTo("""
				(rontolisp:plist-hash-table
				 (list :args (rontolisp:alist-hash-table
				              (rontolisp:query-params (getf env :query-string)))
				       :headers (getf env :headers)
				       :path (getf env :path-info)))
				""");
	}

	@Test
	void startsAnArgumentThatFitsInNoColumnAtTheShallowestOne() {
		// The same call at two depths. Its (format nil "...") argument fits at the
		// shallow
		// column in the outer one and in neither column in the inner one -- and that
		// alone
		// used to decide the two layouts apart, because an argument fitting nowhere was
		// left out of the vote, leaving the inner site aligned under 400 by 400, the only
		// argument with room to spare. The wrapping argument is the one the choice is
		// FOR:
		// it runs past the margin from either column, and by less from the shallow one
		// (here 74 columns against the 95 the alignment gave it).
		assertThat(LispFormatter.format("""
				(defun handle-task (env)
				  (let ((body (getf env :body)))
				    (if (json-object-p body)
				        (let ((payload (gethash "payload" (rontolisp:json-parse body))))
				          (if (stringp payload)
				              (accept payload)
				              (text-response 400 (format nil "expected a JSON object with a \
				string payload field~%"))))
				        (text-response 400 (format nil "expected a JSON object with a string \
				payload field~%")))))
				""".replace("\\\n", ""))).isEqualTo("""
				(defun handle-task (env)
				  (let ((body (getf env :body)))
				    (if (json-object-p body)
				        (let ((payload (gethash "payload" (rontolisp:json-parse body))))
				          (if (stringp payload)
				              (accept payload)
				              (text-response 400
				               (format nil
				                "expected a JSON object with a string payload field~%"))))
				        (text-response 400
				         (format nil "expected a JSON object with a string payload field~%")))))
				""");
	}

	@Test
	void keepsTheAlignmentForAnArgumentThatIsTooWideOnlyWhileItIsFlat() {
		// The other half of the rule above. This clause fits in no column FLAT -- 79
		// columns from column 8 -- but every line of it fits once it breaks, so the
		// shallow
		// column would buy it nothing and cost the clause above it its alignment. Only an
		// argument the alignment itself puts past the margin may ask for the move. What
		// is
		// pinned here is where the CLAUSES sit (column 8, under the first one); the prog1
		// keeping the t company is the clause rule.
		assertThat(LispFormatter.format("""
				(defun next-char-non-extended (lexer)
				  (cond ((end-of-string-p lexer) nil)
				        (t (prog1 (schar (lexer-str lexer) (lexer-pos lexer)) \
				(incf (lexer-pos lexer))))))
				""".replace("\\\n", ""))).isEqualTo("""
				(defun next-char-non-extended (lexer)
				  (cond ((end-of-string-p lexer) nil)
				        (t (prog1 (schar (lexer-str lexer) (lexer-pos lexer))
				             (incf (lexer-pos lexer))))))
				""");
	}

	@Test
	void keepsTheLambdaListOfADefmethodOnTheFirstLine() {
		assertThat(LispFormatter
			.format("(defmethod render :around ((s stream) (x thing)) (call-next-method) (flush s))\n")).isEqualTo("""
					(defmethod render :around ((s stream) (x thing))
					  (call-next-method)
					  (flush s))
					""");
	}

	@Test
	void guessesTheStyleOfAnUnknownWithMacro() {
		// Laid out as a call, the body would align under (socket-accept l) and every line
		// inside it would run past the margin.
		assertThat(LispFormatter.format("""
				(usocket:with-server-socket (sock (usocket:socket-accept listener))
				(handle sock)
				(close sock))
				""")).isEqualTo("""
				(usocket:with-server-socket (sock (usocket:socket-accept listener))
				  (handle sock)
				  (close sock))
				""");
	}

	@Test
	void givesAnUnknownDefinitionMacroWrittenLikeADefunTheShapeOfOne() {
		// A route macro and a pattern macro. With one distinguished argument the lambda
		// list becomes a body form, and a body of two never shares a line -- so a
		// 43-column definition took three.
		assertThat(LispFormatter.format("""
				(define-get "/hello" () (ok "hello world"))
				(define-get "/users/:id" (req) (ok (format nil "user ~A" (path-parameter req :id))))
				(defpattern class (name &rest slots) "Synonym to STRUCTURE pattern." `(structure ,name ,@slots))
				""")).isEqualTo("""
				(define-get "/hello" () (ok "hello world"))
				(define-get "/users/:id" (req)
				  (ok (format nil "user ~A" (path-parameter req :id))))
				(defpattern class (name &rest slots)
				  "Synonym to STRUCTURE pattern."
				  `(structure ,name ,@slots))
				""");
	}

	@Test
	void keepsABodyFormOffTheHeaderLineOfAnUnknownDefinitionMacro() {
		// (define-routes name &body routes): the second element is a route, not a lambda
		// list, and pulling it up beside the operator would align the rest under it.
		assertThat(LispFormatter.format("""
				(define-routes *app*
				(define-get "/hello" () (ok "hello world"))
				(define-any "*" () (not-found "nope")))
				""")).isEqualTo("""
				(define-routes *app*
				  (define-get "/hello" () (ok "hello world"))
				  (define-any "*" () (not-found "nope")))
				""");
	}

	@Test
	void keepsALambdaListFirstDefinitionMacroAtOneDistinguishedArgument() {
		// (define-route lambda-list &body body): the lambda list is already the FIRST
		// argument, so there is no name in front of it and nothing else belongs up there.
		assertThat(LispFormatter.format("""
				(define-route (req)
				(when (ppcre:scan "^/ping$" (path-info req)) (ok "pong") (log-it req)))
				""")).isEqualTo("""
				(define-route (req)
				  (when (ppcre:scan "^/ping$" (path-info req))
				    (ok "pong")
				    (log-it req)))
				""");
	}

	@Test
	void readsAListHoldingNilOrTAsAFormRatherThanALambdaList() {
		// The counter-example the guess exists to protect: alexandria's
		// (deftest NAME form values...) puts a FORM exactly where defun puts its lambda
		// list. In xor.3 that form is written entirely in bare symbols, and what still
		// tells it from a lambda list is that a lambda list may not bind a constant.
		assertThat(LispFormatter.format("""
				(deftest setp.1 (let ((x 1)) (setp x)) t)
				(deftest xor.3 (xor nil nil nil) nil t)
				""")).isEqualTo("""
				(deftest setp.1
				  (let ((x 1)) (setp x))
				  t)
				(deftest xor.3
				  (xor nil nil nil)
				  nil
				  t)
				""");
	}

	@Test
	void keepsAnOptionListOffTheHeaderLineOfAnUnknownDefinitionMacro() {
		// split-sequence's (define-test NAME (:input ... :output ...) body...): a keyword
		// cannot name a parameter, so the second element is an option list, not a lambda
		// list.
		assertThat(LispFormatter.format("""
				(define-test t1 (:input (in "") :output (out (""))) (is (epmv (split in) (values out))))
				""")).isEqualTo("""
				(define-test t1
				  (:input (in "") :output (out ("")))
				  (is (epmv (split in) (values out))))
				""");
	}

	@Test
	void keepsAnOwnLineCommentOnItsOwnLine() {
		assertThat(LispFormatter.format("""
				(defun f (x)
				;; why this is done
				(body x))
				""")).isEqualTo("""
				(defun f (x)
				  ;; why this is done
				  (body x))
				""");
	}

	@Test
	void keepsATrailingCommentOnItsCodeLine() {
		assertThat(LispFormatter.format("(defun f (x) ; the point of f\n(body x))\n")).isEqualTo("""
				(defun f (x) ; the point of f
				  (body x))
				""");
	}

	@Test
	void breaksBeforeAClosingParenThatWouldFollowAComment() {
		// A ; comment swallows the rest of its line, so a ) after one would be commented
		// out. This is the one place the formatter MUST add a line break.
		assertThat(LispFormatter.format("""
				(defun f (x)
				(body x)
				;; nothing more to do
				)
				""")).isEqualTo("""
				(defun f (x)
				  (body x)
				  ;; nothing more to do
				  )
				""");
	}

	@Test
	void alignsTheTrailingCommentsOfConsecutiveLines() {
		assertThat(LispFormatter.format("""
				(run 1) ; first
				(run 200) ; second
				(run 30) ; third
				""")).isEqualTo("""
				(run 1)   ; first
				(run 200) ; second
				(run 30)  ; third
				""");
	}

	@Test
	void keepsABlankLineAndCollapsesARunOfThem() {
		assertThat(LispFormatter.format("""
				(a)



				(b)
				""")).isEqualTo("""
				(a)

				(b)
				""");
	}

	@Test
	void keepsABlankLineInsideABody() {
		assertThat(LispFormatter.format("""
				(defun f ()
				  (one)

				  (two))
				""")).isEqualTo("""
				(defun f ()
				  (one)

				  (two))
				""");
	}

	@Test
	void neverAddsABlankLineBetweenTopLevelForms() {
		// trivial-formatter forces one; here the author's paragraphing is the author's.
		assertThat(LispFormatter.format("(a)\n(b)\n")).isEqualTo("(a)\n(b)\n");
	}

	@Test
	void reproducesEveryTokenVerbatim() {
		String source = """
				(list Foo |a b| #\\Space #xFF 1/3 1.5d0 1,000 #*1010 "a\\"b" 'q `(,x ,@y) #'f)
				""";
		assertThat(LispFormatter.format(source)).isEqualTo(source);
	}

	@Test
	void keepsTheSpaceThatSeparatesACommaFromAnAtOrDotDatum() {
		// A prefix prints glued to its datum, but `(, @x)` glued is `,@x` -- a DIFFERENT
		// reader macro (unquote-splicing), so the space is a token-stream fact and not
		// formatting. trivial-utf-8's pax-pages is written that way.
		assertThat(LispFormatter.format("`(:objects (, @manual))\n")).isEqualTo("`(:objects (, @manual))\n");
		assertThat(LispFormatter.format("`(a , .5)\n")).isEqualTo("`(a , .5)\n");
		// An ordinary datum still glues, and a real ,@ is untouched.
		assertThat(LispFormatter.format("`(a , b ,@c)\n")).isEqualTo("`(a ,b ,@c)\n");
	}

	@Test
	void keepsAFeatureGuardButGivesALongOneItsOwnLine() {
		assertThat(LispFormatter.format("#+sbcl (declaim (optimize speed))\n"))
			.isEqualTo("#+sbcl (declaim (optimize speed))\n");
		assertThat(LispFormatter.format("""
				#-(or ccl (and ecl little-endian) (and sbcl little-endian)) (defun ref (v i)
				(the (unsigned-byte 16) (dpb (aref v (1+ i)) (byte 8 8) (aref v i))))
				""")).isEqualTo("""
				#-(or ccl (and ecl little-endian) (and sbcl little-endian))
				(defun ref (v i)
				  (the (unsigned-byte 16) (dpb (aref v (1+ i)) (byte 8 8) (aref v i))))
				""");
	}

	@Test
	void leavesAMultiLineStringLiteralAlone() {
		assertThat(LispFormatter.format("""
				(defun f ()
				"first line
				second line"
				(body))
				""")).isEqualTo("""
				(defun f ()
				  "first line
				second line"
				  (body))
				""");
	}

	@Test
	void leavesABlockCommentAlone() {
		// Its interior lines are content, so they are reproduced exactly -- which is also
		// why the form after it cannot go back onto its last line.
		assertThat(LispFormatter.format("(a) #| kept\n   as written |# (b)\n"))
			.isEqualTo("(a) #| kept\n   as written |#\n(b)\n");
	}

	@Test
	void wrapsToTheGivenWidth() {
		String source = "(some-function (aaaa 1) (bbbb 2) (cccc 3))\n";
		assertThat(LispFormatter.format(source, 80)).isEqualTo(source);
		assertThat(LispFormatter.format(source, 30)).isEqualTo("""
				(some-function (aaaa 1)
				               (bbbb 2)
				               (cccc 3))
				""");
	}

	@Test
	void normalizesLineEndingsAndTheFinalNewline() {
		assertThat(LispFormatter.format("(a)\r\n(b)")).isEqualTo("(a)\n(b)\n");
		assertThat(LispFormatter.format("(a)\r(b)")).isEqualTo("(a)\n(b)\n");
		assertThat(LispFormatter.format("")).isEmpty();
		assertThat(LispFormatter.format("\n\n  \n")).isEmpty();
	}

	@Test
	void keepsACarriageReturnThatIsInsideALiteral() {
		// A CR between two tokens is a line ending and normalizes to LF (above); a CR
		// INSIDE a string or character literal is part of that token, and rewriting it
		// changes the program. The mustache spec suite -- vendored with cl-mustache, and
		// in the corpus below -- carries "{{! Comment }}\r\n" template literals whose
		// whole point is CRLF handling.
		assertThat(LispFormatter.format("(render \"a\r\nb\")\r\n")).contains("\"a\r\nb\"").endsWith(")\n");
		assertThat(LispFormatter.format("(render \"a\rb\")\n")).contains("\"a\rb\"");
		assertThat(LispFormatter.format("(f #\\\r)\n")).contains("#\\\r");
		// A CR ends a line comment; it does not become part of it (nor, on a CR-only
		// source, swallow the rest of the file).
		assertThat(LispFormatter.format("(a) ; note\r(b)\r")).isEqualTo("(a) ; note\n(b)\n");
		// ...and formatting stays a fixpoint over such a source.
		String once = LispFormatter.format("(render \"a\r\nb\" \"c\rd\")\r\n");
		assertThat(LispFormatter.format(once)).isEqualTo(once);
	}

	@Test
	void keepsASupplementaryPlaneCharacterLiteralWhole() {
		// #\<emoji> is a UTF-16 surrogate PAIR in the source. The CST reader's
		// readCharLiteral must consume both units of the pair, not just the high
		// surrogate -- otherwise the low surrogate is left to be re-scanned (and
		// mis-rendered) as the next token.
		assertThat(LispFormatter.format("(f #\\😀)\n")).isEqualTo("(f #\\😀)\n");
	}

	@Test
	void reportsAnUnreadableSourceWithItsPosition() {
		assertThatThrownBy(() -> LispFormatter.format("(defun f (x)\n  (g x)\n")).isInstanceOf(FormatException.class)
			.hasMessageContaining("1:1")
			.hasMessageContaining("no matching ')'");
		assertThatThrownBy(() -> LispFormatter.format("(a))\n")).isInstanceOf(FormatException.class)
			.hasMessageContaining("1:4");
		assertThatThrownBy(() -> LispFormatter.format("'\n")).isInstanceOf(FormatException.class)
			.hasMessageContaining("is not followed by a form");
	}

	/**
	 * Every {@code .lisp} and {@code .asd} file in the repository, formatted. The corpus
	 * is the point: it is thousands of forms of real Common Lisp -- cl-ppcre, ironclad,
	 * esrap, trivia, sxql -- written by people who never saw this formatter, which is the
	 * only kind of input that finds the cases a hand-written fixture never will.
	 * @return the files
	 * @throws IOException if the tree cannot be walked
	 */
	static Stream<Path> repositoryLispSources() throws IOException {
		return lispSourcesUnder(Path.of("."));
	}

	/**
	 * The four directories the "format Lisp" release step formats in place -- unlike the
	 * corpus above (vendored third-party libraries under {@code src/test/resources} that
	 * nobody ever ran through this formatter), a file here SHIPS as-is and is expected to
	 * already be a fixpoint. A file that needs reformatting here is silently rewritten
	 * the next time someone runs that step against an unrelated change.
	 * @return the shipped {@code .lisp} and {@code .asd} files
	 * @throws IOException if a tree cannot be walked
	 */
	static Stream<Path> shippedLispSources() throws IOException {
		List<Path> roots = List.of(Path.of("examples"), Path.of("src/main/resources"), Path.of("size-report/programs"),
				Path.of("bench-report/programs"));
		List<Path> found = new ArrayList<>();
		for (Path root : roots) {
			lispSourcesUnder(root).forEach(found::add);
		}
		return found.stream();
	}

	/**
	 * The walk itself, over an arbitrary root so the boundary cases can be pinned against
	 * a temporary tree rather than against the repository.
	 * @param root the directory to walk
	 * @return the {@code .lisp} and {@code .asd} files under it, in path order
	 * @throws IOException if the tree cannot be walked
	 */
	static Stream<Path> lispSourcesUnder(Path root) throws IOException {
		// Not Files.walk: it reports an entry it cannot stat by throwing
		// UncheckedIOException out of the stream, and this walks the live working
		// directory while the rest of the suite writes scratch files into it. An entry
		// the walk cannot read is not a corpus member and must not be a suite failure --
		// only a .lisp or .asd file it CAN read is.
		List<Path> found = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) {
					found.add(file);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException failure) {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException failure) {
				return FileVisitResult.CONTINUE;
			}
		});
		return found.stream()
			.filter(path -> path.toString().endsWith(".lisp") || path.toString().endsWith(".asd")
					|| path.toString().endsWith(".scm"))
			.filter(path -> !path.toString().contains("/target/"))
			// The ANSI suite checkout is foreign, git-ignored code we do not format;
			// whether it survives the fixpoint is the suite's business, not ours, and
			// a developer who ran ansi-test/fetch.sh must not get a different verdict
			// from `./mvnw test` than one who did not.
			.filter(path -> !path.toString().contains("/ansi-test/suite/"))
			// A `.claude/` directory (agent worktrees, or anything else the harness
			// puts there) is foreign for the same reason: a developer who has stale
			// agent worktrees on disk must not get a different verdict -- or a
			// different TEST COUNT -- from `./mvnw test` than one who does not, and a
			// worktree mid-edit must never make the MAIN tree's suite fail on a file
			// the main tree does not contain. See .todo/708.
			.filter(path -> !path.toString().contains("/.claude/"))
			.sorted(Comparator.comparing(Path::toString))
			.toList()
			.stream();
	}

	// The corpus walks the LIVE working directory while the rest of the suite is
	// running in it, and other tests write scratch files into the project root and
	// delete them again (JvmClassShakerCorpusTest's CORPUS_SCRATCH_FILES: bin.dat,
	// ci-stream-value.txt, ci-model.gguf, ...). A file that disappears between being
	// listed and being stat'd makes Files.walk throw UncheckedIOException, which failed
	// the whole suite on develop 2026-09-06 with
	// `NoSuchFileException: ./ci-stream-value.txt` -- from a file that is not a .lisp,
	// is no part of the corpus, and was never going to be formatted.
	//
	// An entry the walk cannot stat is the general shape, so that is what is pinned. The
	// deterministic stand-in is a directory the process may not read: the production
	// trigger (a file removed mid-walk) cannot be scheduled from a test, and both arrive
	// at the same handler.
	@Test
	void theCorpusWalkSurvivesAnEntryItCannotStat(@TempDir Path root) throws IOException {
		assumeThat(System.getProperty("user.name")).as("root can read a 000 directory").isNotEqualTo("root");
		Files.writeString(root.resolve("kept.lisp"), "(defun f (x) x)\n");
		Path opaque = Files.createDirectory(root.resolve("opaque"));
		Files.writeString(opaque.resolve("hidden.lisp"), "(defun g (x) x)\n");
		Files.setPosixFilePermissions(opaque, PosixFilePermissions.fromString("---------"));
		try {
			assertThat(lispSourcesUnder(root).toList()).as("the readable corpus survives the unreadable entry")
				.singleElement()
				.asString()
				.endsWith("kept.lisp");
		}
		finally {
			Files.setPosixFilePermissions(opaque, PosixFilePermissions.fromString("rwx------"));
		}
	}

	// Pins the corpus boundary above: without this, a new foreign directory the walk
	// does not know about (a third stale-worktree-shaped mechanism, after /target/ and
	// /ansi-test/suite/) inflates the parameterized test count silently, exactly as
	// .claude/worktrees/ did (.todo/708). `git ls-files` is the ground truth for what
	// the repository itself tracks; the corpus also includes untracked fixtures (e.g.
	// a fresh ansi-test checkout before it is git-ignored away above), so the bound is
	// a small factor rather than equality.
	@Test
	void repositoryCorpusStaysWithinASmallFactorOfTrackedSources() throws IOException, InterruptedException {
		List<Path> corpus = repositoryLispSources().toList();
		assertThat(corpus).as("no corpus path may come from a foreign .claude/ directory")
			.noneMatch(path -> path.toString().contains("/.claude/"));

		Process git = new ProcessBuilder("git", "ls-files", "*.lisp", "*.asd").redirectErrorStream(true).start();
		String output;
		try (var reader = git.inputReader()) {
			output = reader.lines().filter(line -> !line.isBlank()).reduce("", (a, b) -> a + b + "\n");
		}
		int exit = git.waitFor();
		// git is not available in every sandbox this test may run in; the boundary
		// filter above is still checked unconditionally.
		if (exit != 0) {
			return;
		}
		long tracked = output.lines().filter(line -> !line.isBlank()).count();
		assertThat(corpus.size()).as("corpus size %s vs. %s tracked .lisp/.asd files", corpus.size(), tracked)
			.isLessThan((int) (tracked * 3));
	}

	@ParameterizedTest
	@MethodSource("repositoryLispSources")
	void formattingIsIdempotentAndPreservesEveryToken(Path file) throws IOException {
		String source = Files.readString(file);
		String formatted = formatFor(file, source);
		// Formatting is a fixpoint: a formatted file formats to itself. Without this the
		// command could not be used as a CI gate, since --check would never go quiet.
		assertThat(formatFor(file, formatted)).as("formatting %s twice differs from once", file).isEqualTo(formatted);
		// ...and it changes NO code. The lexer discards exactly what the formatter is
		// allowed to change (whitespace and comments) and keeps everything else, so an
		// identical token stream is the precise statement of "same program".
		List<Token> before = tokens(source);
		if (before != null) {
			assertThat(tokens(formatted)).as("formatting %s changed its token stream", file).isEqualTo(before);
		}
	}

	@ParameterizedTest
	@MethodSource("shippedLispSources")
	void shippedLispResourcesAreAlreadyFormatted(Path file) throws IOException {
		String source = Files.readString(file);
		assertThat(formatFor(file, source))
			.as("%s is not formatter-clean; run the format step and commit the result", file)
			.isEqualTo(source);
	}

	// The rules follow the file, as rontolisp format does: a .scm file is indented as
	// Scheme, anything else as Common Lisp.
	private static String formatFor(Path file, String source) {
		return file.toString().endsWith(".scm")
				? LispFormatter.format(source, LispFormatter.DEFAULT_WIDTH, IndentRules.Dialect.SCHEME)
				: LispFormatter.format(source);
	}

	// The reader's own tokens, or null when the file does not read at all (a fixture that
	// deliberately uses syntax rontolisp does not support). The formatter still has to
	// reproduce such a file byte-for-byte in structure, but there is no token stream to
	// compare it against.
	@Nullable private static List<Token> tokens(String source) {
		try {
			return new LispLexer(source, Features.INTERPRETER, LispLexer.ReadEvalMode.MARKER).tokenize();
		}
		catch (LispReadException _) {
			return null;
		}
	}

}
