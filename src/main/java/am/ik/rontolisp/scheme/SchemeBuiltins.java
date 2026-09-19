package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The Scheme procedures the front end knows, each as the Common Lisp form a CALL lowers
 * to and the function VALUE a bare reference lowers to. No backend learns a new name:
 * every template is spelled in operators the pipeline already compiles, or in a
 * {@code rontolisp::%scheme-*} helper from {@code scheme.lisp}.
 *
 * <p>
 * The table is Lisp data read with the project's own reader, one entry per procedure:
 *
 * <pre>
 * ("name" library result ((params) template)... [:function form])
 * </pre>
 *
 * {@code library} is the R7RS library exporting the name ({@code base} / {@code write} /
 * {@code inexact} / {@code cxr} / {@code lazy} / {@code process-context} / {@code eval} /
 * {@code repl}, or {@code sicp} / {@code r5rs} for a name no import can reach),
 * {@code result} says what the template answers -- {@code value}, {@code pred} (a Common
 * Lisp boolean, {@code T}/{@code NIL}, which fuses into an {@code if} test and is
 * converted to {@code #t}/{@code #f} anywhere else), {@code or-false} (a value, or
 * {@code NIL} meaning {@code #f}) or {@code effect} (the template's value is discarded
 * and the call answers the unspecified object, which a REPL does not echo). One
 * {@code ((params) template)} pair per accepted argument count; {@code &rest r} params
 * splice as the template's dotted tail {@code (f a . r)}. {@code :function} is the
 * first-class value; it may be omitted only for a single fixed-arity alternative, where
 * it is derived as a {@code lambda} around the template.
 *
 * <p>
 * Parameter names are uppercase symbols (the reader upcases them), which no user variable
 * can be ({@link SchemeNames}); none may be {@code T} or {@code NIL}.
 */
final class SchemeBuiltins {

	/** The variable holding the false value, as spelled in the emitted program. */
	static final String FALSE_VARIABLE = "RONTOLISP::%SCHEME-FALSE";

	/**
	 * The variable holding the unspecified object: what an effect ({@code display},
	 * {@code set!}, an {@code if} with no taken arm) answers. A symbol, like the false
	 * value, so no backend learns it; true in a test, and one element of a list.
	 */
	static final String UNSPECIFIED_VARIABLE = "RONTOLISP::%SCHEME-UNSPECIFIED";

	/**
	 * The one global environment every environment specifier denotes, as a symbol -- like
	 * the false value and the unspecified object, so no backend learns it -- in MIT
	 * Scheme's spelling, which is what {@code display} then shows. What
	 * {@code (interaction-environment)}, {@code user-initial-environment} and
	 * {@code (environment ...)} answer and what {@code eval} accepts.
	 */
	static final String ENVIRONMENT_NAME = "#[environment]";

	/** What a template's value is. */
	enum Result {

		/** An ordinary Scheme value. */
		VALUE,

		/** A Common Lisp boolean: {@code T} or {@code NIL}. */
		PREDICATE,

		/** A Scheme value, or {@code NIL} standing for {@code #f}. */
		OR_FALSE,

		/**
		 * Called for its effect: the call answers the unspecified object, which an
		 * interactive session does not echo.
		 */
		EFFECT

	}

	/**
	 * One accepted argument shape.
	 *
	 * @param required the required parameter names
	 * @param rest the {@code &rest} parameter name, or {@code null}
	 * @param template the form a call lowers to
	 */
	record Alternative(List<String> required, @Nullable String rest, LispVal template) {

		boolean accepts(int argumentCount) {
			return this.rest == null ? argumentCount == this.required.size() : argumentCount >= this.required.size();
		}

	}

	/**
	 * A known procedure.
	 *
	 * @param name the Scheme name
	 * @param library the exporting library's last component ({@code base}, {@code write},
	 * {@code inexact}, {@code cxr}, {@code lazy}, {@code process-context}, {@code eval},
	 * {@code repl}), or a tag no import names ({@code sicp}, {@code r5rs})
	 * @param result what the templates answer
	 * @param alternatives the accepted argument shapes
	 * @param function the first-class value: a form answering a function that returns
	 * SCHEME values (booleans already converted)
	 */
	record Entry(String name, String library, Result result, List<Alternative> alternatives, LispVal function) {

		/**
		 * The raw template expansion of a call, NOT yet converted by {@link #result}.
		 * @param arguments the lowered argument forms
		 * @param temporaries mints a fresh variable for an argument a template uses more
		 * than once
		 * @return the form, or {@code null} when no alternative takes that many arguments
		 */
		@Nullable LispVal call(List<LispVal> arguments, Supplier<LispSymbol> temporaries) {
			for (Alternative alternative : this.alternatives) {
				if (alternative.accepts(arguments.size())) {
					return expand(alternative, arguments, temporaries);
				}
			}
			return null;
		}

	}

	private static final String TABLE = """
			;; --- equivalence ---
			("eq?" base pred ((a b) (eq a b)))
			("eqv?" base pred ((a b) (eql a b)))
			("equal?" base pred ((a b) (rontolisp::%scheme-equal? a b)))

			;; --- numbers ---
			("+" base value ((&rest r) (+ . r)) :function #'+)
			("-" base value ((a &rest r) (- a . r)) :function #'-)
			("*" base value ((&rest r) (* . r)) :function #'*)
			("/" base value ((a &rest r) (/ a . r)) :function #'/)
			("=" base pred ((a b) (= a b)) ((a b &rest r) (= a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'= r) t rontolisp::%scheme-false)))
			("<" base pred ((a b) (< a b)) ((a b &rest r) (< a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'< r) t rontolisp::%scheme-false)))
			(">" base pred ((a b) (> a b)) ((a b &rest r) (> a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'> r) t rontolisp::%scheme-false)))
			("<=" base pred ((a b) (<= a b)) ((a b &rest r) (<= a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'<= r) t rontolisp::%scheme-false)))
			(">=" base pred ((a b) (>= a b)) ((a b &rest r) (>= a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'>= r) t rontolisp::%scheme-false)))
			;; Two exact integers take the inline division; anything else goes through the
			;; helper, which refuses a non-integer and keeps an inexact quotient inexact.
			("quotient" base value ((a b) (if (and (integerp a) (integerp b)) (values (truncate a b))
			                                   (rontolisp::%scheme-quotient "quotient" a b))))
			("remainder" base value ((a b) (rem a b)))
			("modulo" base value ((a b) (mod a b)))
			("truncate-quotient" base value ((a b) (if (and (integerp a) (integerp b)) (values (truncate a b))
			                                            (rontolisp::%scheme-quotient "truncate-quotient" a b))))
			("truncate-remainder" base value ((a b) (rem a b)))
			("floor-quotient" base value ((a b) (if (and (integerp a) (integerp b)) (values (floor a b))
			                                         (rontolisp::%scheme-floor-quotient a b))))
			("floor-remainder" base value ((a b) (mod a b)))
			("abs" base value ((x) (abs x)))
			("min" base value ((a) (values a)) ((a b) (rontolisp::%scheme-min a b))
			 ((a &rest r) (reduce #'rontolisp::%scheme-min (list . r) :initial-value a))
			 :function (lambda (a &rest r) (reduce #'rontolisp::%scheme-min r :initial-value a)))
			("max" base value ((a) (values a)) ((a b) (rontolisp::%scheme-max a b))
			 ((a &rest r) (reduce #'rontolisp::%scheme-max (list . r) :initial-value a))
			 :function (lambda (a &rest r) (reduce #'rontolisp::%scheme-max r :initial-value a)))
			("gcd" base value ((a b) (if (and (integerp a) (integerp b)) (gcd a b) (rontolisp::%scheme-gcd (list a b))))
			 ((&rest r) (rontolisp::%scheme-gcd (list . r))) :function (lambda (&rest r) (rontolisp::%scheme-gcd r)))
			("lcm" base value ((a b) (if (and (integerp a) (integerp b)) (lcm a b) (rontolisp::%scheme-lcm (list a b))))
			 ((&rest r) (rontolisp::%scheme-lcm (list . r))) :function (lambda (&rest r) (rontolisp::%scheme-lcm r)))
			("expt" base value ((a b) (expt a b)))
			("square" base value ((x) (* x x)))
			("floor" base value ((x) (if (floatp x) (float (floor x) 1.0d0) (values (floor x)))))
			("ceiling" base value ((x) (if (floatp x) (float (ceiling x) 1.0d0) (values (ceiling x)))))
			("round" base value ((x) (if (floatp x) (float (round x) 1.0d0) (values (round x)))))
			("truncate" base value ((x) (if (floatp x) (float (truncate x) 1.0d0) (values (truncate x)))))
			("zero?" base pred ((x) (zerop x)))
			("positive?" base pred ((x) (plusp x)))
			("negative?" base pred ((x) (minusp x)))
			("odd?" base pred ((x) (if (integerp x) (oddp x) (rontolisp::%scheme-odd? x))))
			("even?" base pred ((x) (if (integerp x) (evenp x) (rontolisp::%scheme-even? x))))
			("number?" base pred ((x) (numberp x)))
			("real?" base pred ((x) (realp x)))
			("rational?" base pred ((x) (or (rationalp x) (and (floatp x) (rontolisp::%scheme-finite? x)))))
			("integer?" base pred ((x) (rontolisp::%scheme-integer? x)))
			("exact?" base pred ((x) (rationalp x)))
			("inexact?" base pred ((x) (floatp x)))
			("exact-integer?" base pred ((x) (integerp x)))
			("exact" base value ((x) (rational x)))
			("inexact" base value ((x) (float x 1.0d0)))
			("inexact->exact" r5rs value ((x) (rational x)))
			("exact->inexact" r5rs value ((x) (float x 1.0d0)))
			("number->string" base value ((n) (rontolisp::%scheme-number->string n 10))
			 ((n radix) (rontolisp::%scheme-number->string n radix))
			 :function (lambda (n &optional (radix 10)) (rontolisp::%scheme-number->string n radix)))
			("string->number" base value ((s) (rontolisp::%scheme-string->number s 10))
			 ((s radix) (rontolisp::%scheme-string->number s radix))
			 :function (lambda (s &optional (radix 10)) (rontolisp::%scheme-string->number s radix)))
			("exact-integer-sqrt" base value ((k) (rontolisp::%scheme-exact-integer-sqrt k)))

			;; --- (scheme inexact): a result Common Lisp would answer as a complex number is
			;; refused by name, and an exact argument with an exact answer stays exact.
			("sqrt" inexact value ((x) (rontolisp::%scheme-sqrt x)))
			("exp" inexact value ((x) (rontolisp::%scheme-exp x)))
			("log" inexact value ((x) (rontolisp::%scheme-log x)) ((x b) (rontolisp::%scheme-log-base x b))
			 :function (lambda (x &rest b) (if b (rontolisp::%scheme-log-base x (car b)) (rontolisp::%scheme-log x))))
			("sin" inexact value ((x) (rontolisp::%scheme-sin x)))
			("cos" inexact value ((x) (rontolisp::%scheme-cos x)))
			("tan" inexact value ((x) (rontolisp::%scheme-tan x)))
			("asin" inexact value ((x) (rontolisp::%scheme-asin x)))
			("acos" inexact value ((x) (rontolisp::%scheme-acos x)))
			("atan" inexact value ((y) (rontolisp::%scheme-atan y)) ((y x) (rontolisp::%scheme-atan2 y x))
			 :function (lambda (y &rest x) (if x (rontolisp::%scheme-atan2 y (car x)) (rontolisp::%scheme-atan y))))
			("finite?" inexact pred ((x) (rontolisp::%scheme-finite? x)))
			("infinite?" inexact pred ((x) (rontolisp::%scheme-infinite? x)))
			("nan?" inexact pred ((x) (rontolisp::%scheme-nan? x)))

			;; --- booleans ---
			("not" base pred ((x) (eq x rontolisp::%scheme-false)))
			("boolean?" base pred ((x) (or (eq x t) (eq x rontolisp::%scheme-false))))

			;; --- pairs and lists ---
			("cons" base value ((a b) (cons a b)) :function #'cons)
			("car" base value ((p) (car p)) :function #'car)
			("cdr" base value ((p) (cdr p)) :function #'cdr)
			("set-car!" base effect ((p v) (rplaca p v)))
			("set-cdr!" base effect ((p v) (rplacd p v)))
			("caar" base value ((p) (caar p)))
			("cadr" base value ((p) (cadr p)))
			("cdar" base value ((p) (cdar p)))
			("cddr" base value ((p) (cddr p)))
			("list" base value ((&rest r) (list . r)) :function #'list)
			("length" base value ((l) (length l)))
			("append" base value ((&rest r) (append . r)) :function (lambda (&rest r) (apply #'append r)))
			("reverse" base value ((l) (reverse l)))
			("list-tail" base value ((l k) (nthcdr k l)))
			("list-ref" base value ((l k) (nth k l)))
			("list-copy" base value ((l) (rontolisp::%scheme-list-copy l)))
			("memq" base or-false ((x l) (member x l :test #'eq)))
			("memv" base or-false ((x l) (member x l)))
			("member" base or-false ((x l) (rontolisp::%scheme-member x l))
			 ((x l p) (rontolisp::%scheme-member-by x l p))
			 :function (lambda (x l &optional p)
			             (or (if p (rontolisp::%scheme-member-by x l p) (rontolisp::%scheme-member x l))
			                 rontolisp::%scheme-false)))
			("assq" base or-false ((x l) (assoc x l :test #'eq)))
			("assv" base or-false ((x l) (assoc x l)))
			("assoc" base or-false ((x l) (rontolisp::%scheme-assoc x l))
			 ((x l p) (rontolisp::%scheme-assoc-by x l p))
			 :function (lambda (x l &optional p)
			             (or (if p (rontolisp::%scheme-assoc-by x l p) (rontolisp::%scheme-assoc x l))
			                 rontolisp::%scheme-false)))
			("null?" base pred ((x) (null x)))
			("pair?" base pred ((x) (consp x)))
			("list?" base pred ((x) (rontolisp::%scheme-list? x)))

			;; --- (scheme cxr): every three- and four-deep car/cdr composition; caar/cadr/
			;; cdar/cddr above are (scheme base). All 28 names are standard Common Lisp
			;; functions, so each template just forwards to the one of the same name.
			("caaar" cxr value ((p) (caaar p)))
			("caadr" cxr value ((p) (caadr p)))
			("cadar" cxr value ((p) (cadar p)))
			("caddr" cxr value ((p) (caddr p)))
			("cdaar" cxr value ((p) (cdaar p)))
			("cdadr" cxr value ((p) (cdadr p)))
			("cddar" cxr value ((p) (cddar p)))
			("cdddr" cxr value ((p) (cdddr p)))
			("caaaar" cxr value ((p) (caaaar p)))
			("caaadr" cxr value ((p) (caaadr p)))
			("caadar" cxr value ((p) (caadar p)))
			("caaddr" cxr value ((p) (caaddr p)))
			("cadaar" cxr value ((p) (cadaar p)))
			("cadadr" cxr value ((p) (cadadr p)))
			("caddar" cxr value ((p) (caddar p)))
			("cadddr" cxr value ((p) (cadddr p)))
			("cdaaar" cxr value ((p) (cdaaar p)))
			("cdaadr" cxr value ((p) (cdaadr p)))
			("cdadar" cxr value ((p) (cdadar p)))
			("cdaddr" cxr value ((p) (cdaddr p)))
			("cddaar" cxr value ((p) (cddaar p)))
			("cddadr" cxr value ((p) (cddadr p)))
			("cdddar" cxr value ((p) (cdddar p)))
			("cddddr" cxr value ((p) (cddddr p)))

			;; --- sicp: names the SICP corpus assumes an implementation already provides,
			;; NOT R7RS exports -- unreachable by (import ...), visible only through the
			;; same no-import default as a bare-metal REPL (SchemeLowering.imports).
			("filter" sicp value ((pred l) (rontolisp::%scheme-filter pred l)))
			;; SRFI-1 / MIT reduce: (f elem acc), so (reduce - 0 '(1 2 3 4)) is 2.
			("reduce" sicp value ((op initial l) (rontolisp::%scheme-reduce op initial l)))
			("fold-left" sicp value ((op initial l) (reduce op l :initial-value initial))
			 ((op initial l &rest r) (rontolisp::%scheme-fold-left op initial (list l . r)))
			 :function (lambda (op initial l &rest r)
			             (if r (rontolisp::%scheme-fold-left op initial (cons l r)) (reduce op l :initial-value initial))))
			("fold-right" sicp value ((op initial l) (reduce op l :initial-value initial :from-end t))
			 ((op initial l &rest r) (rontolisp::%scheme-fold-right op initial (list l . r)))
			 :function (lambda (op initial l &rest r)
			             (if r (rontolisp::%scheme-fold-right op initial (cons l r))
			                 (reduce op l :initial-value initial :from-end t))))
			("delete" sicp value ((x l) (remove x l :test #'rontolisp::%scheme-equal?)))
			("last-pair" sicp value ((l) (last l)))
			("append!" sicp value ((&rest r) (nconc . r)) :function (lambda (&rest r) (apply #'nconc r)))
			("list-index" sicp or-false ((pred l) (rontolisp::%scheme-list-index pred l)))
			("1+" sicp value ((x) (+ x 1)))
			("-1+" sicp value ((x) (- x 1)))
			("random" sicp value ((n) (random n)))
			("runtime" sicp value (() (/ (float (get-internal-real-time) 1.0d0) internal-time-units-per-second)))
			;; SICP 3.4: every thunk in its own thread, all joined before the call returns
			;; (in order on wasm, which has no threads); test-and-set! under one lock.
			("parallel-execute" sicp effect ((&rest r) (rontolisp::%scheme-parallel-execute (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-parallel-execute r)))
			("test-and-set!" sicp pred ((cell) (rontolisp::%scheme-test-and-set! cell)))

			;; --- (scheme lazy): delay and delay-force are syntax (SchemeLowering) ---
			("force" lazy value ((p) (rontolisp::%scheme-force p)))
			("make-promise" lazy value ((x) (rontolisp::%scheme-make-promise x)))
			("promise?" lazy pred ((x) (rontolisp::%scheme-promise? x)))

			;; --- sicp streams: a stream is '() or (value . promise), so the-empty-stream
			;; is '() and stream-null? is null?; cons-stream is syntax (SchemeLowering).
			;; Common Lisp helpers, so a user definition of apply or map cannot reach them.
			("stream-car" sicp value ((s) (rontolisp::%scheme-stream-car s)))
			("stream-cdr" sicp value ((s) (rontolisp::%scheme-stream-cdr s)))
			("stream-first" sicp value ((s) (rontolisp::%scheme-stream-car s)))
			("stream-rest" sicp value ((s) (rontolisp::%scheme-stream-cdr s)))
			("stream-pair?" sicp pred ((x) (rontolisp::%scheme-stream-pair? x)))
			("stream-null?" sicp pred ((x) (null x)))
			("empty-stream?" sicp pred ((x) (null x)))
			("stream" sicp value ((&rest r) (rontolisp::%scheme-list->stream (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-list->stream r)))
			("list->stream" sicp value ((l) (rontolisp::%scheme-list->stream l)))
			("stream->list" sicp value ((s) (rontolisp::%scheme-stream->list s nil))
			 ((s k) (rontolisp::%scheme-stream->list s k))
			 :function (lambda (s &optional k) (rontolisp::%scheme-stream->list s k)))
			("stream-head" sicp value ((s k) (rontolisp::%scheme-stream->list s k)))
			("stream-tail" sicp value ((s k) (rontolisp::%scheme-stream-tail s k)))
			("stream-ref" sicp value ((s k) (rontolisp::%scheme-stream-ref s k)))
			("stream-map" sicp value ((f s &rest r) (rontolisp::%scheme-stream-map f (list s . r)))
			 :function (lambda (f &rest r) (rontolisp::%scheme-stream-map f r)))
			("stream-for-each" sicp effect ((f s) (rontolisp::%scheme-stream-for-each f s)))
			("stream-filter" sicp value ((pred s) (rontolisp::%scheme-stream-filter pred s)))
			("stream-append" sicp value ((&rest r) (rontolisp::%scheme-stream-append (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-stream-append r)))

			;; --- symbols ---
			("symbol?" base pred ((x) (and (symbolp x) x (not (eq x t)) (not (eq x rontolisp::%scheme-false))
			                              (not (eq x rontolisp::%scheme-unspecified)))))
			("symbol->string" base value ((s) (rontolisp::%scheme-symbol->string s)))
			("string->symbol" base value ((s) (rontolisp::%scheme-string->symbol s)))

			;; --- characters ---
			("char?" base pred ((x) (characterp x)))
			("char->integer" base value ((c) (char-code c)))
			("integer->char" base value ((n) (code-char n)))
			("char=?" base pred ((a b) (char= a b)) ((a b &rest r) (char= a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'char= r) t rontolisp::%scheme-false)))
			("char<?" base pred ((a b) (char< a b)) ((a b &rest r) (char< a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'char< r) t rontolisp::%scheme-false)))
			("char>?" base pred ((a b) (char> a b)) ((a b &rest r) (char> a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'char> r) t rontolisp::%scheme-false)))
			("char<=?" base pred ((a b) (char<= a b)) ((a b &rest r) (char<= a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'char<= r) t rontolisp::%scheme-false)))
			("char>=?" base pred ((a b) (char>= a b)) ((a b &rest r) (char>= a b . r))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'char>= r) t rontolisp::%scheme-false)))

			;; --- strings ---
			("string?" base pred ((x) (stringp x)))
			("make-string" base value ((n) (make-string n :initial-element #\\Space))
			 ((n c) (make-string n :initial-element c))
			 :function (lambda (n &optional (c #\\Space)) (make-string n :initial-element c)))
			("string" base value ((&rest r) (rontolisp::%scheme-list->string "string" (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-list->string "string" r)))
			("string-length" base value ((s) (length s)))
			("string-ref" base value ((s k) (char s k)))
			("string-set!" base effect ((s k c) (setf (char s k) c)))
			("string=?" base pred ((a b) (string= a b))
			 ((a b &rest r) (rontolisp::%scheme-chain #'string= (list a b . r)))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'string= r) t rontolisp::%scheme-false)))
			("string<?" base pred ((a b) (string< a b))
			 ((a b &rest r) (rontolisp::%scheme-chain #'string< (list a b . r)))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'string< r) t rontolisp::%scheme-false)))
			("string>?" base pred ((a b) (string> a b))
			 ((a b &rest r) (rontolisp::%scheme-chain #'string> (list a b . r)))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'string> r) t rontolisp::%scheme-false)))
			("string<=?" base pred ((a b) (string<= a b))
			 ((a b &rest r) (rontolisp::%scheme-chain #'string<= (list a b . r)))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'string<= r) t rontolisp::%scheme-false)))
			("string>=?" base pred ((a b) (string>= a b))
			 ((a b &rest r) (rontolisp::%scheme-chain #'string>= (list a b . r)))
			 :function (lambda (&rest r) (if (rontolisp::%scheme-chain #'string>= r) t rontolisp::%scheme-false)))
			("substring" base value ((s from to) (subseq s from to)))
			("string-append" base value ((&rest r) (concatenate 'string . r))
			 :function (lambda (&rest r) (apply #'concatenate 'string r)))
			("string-copy" base value ((s) (copy-seq s)) ((s from) (subseq s from)) ((s from to) (subseq s from to))
			 :function (lambda (s &optional (from 0) to) (subseq s from to)))
			("string->list" base value ((s) (coerce s 'list)) ((s from) (coerce (subseq s from) 'list))
			 ((s from to) (coerce (subseq s from to) 'list))
			 :function (lambda (s &optional (from 0) to) (coerce (subseq s from to) 'list)))
			("list->string" base value ((l) (rontolisp::%scheme-list->string "list->string" l)))

			;; --- vectors ---
			("vector?" base pred ((x) (simple-vector-p x)))
			("make-vector" base value ((n) (make-array n :initial-element 0)) ((n fill) (make-array n :initial-element fill))
			 :function (lambda (n &optional (fill 0)) (make-array n :initial-element fill)))
			("vector" base value ((&rest r) (vector . r)) :function #'vector)
			("vector-length" base value ((v) (length v)))
			("vector-ref" base value ((v k) (aref v k)))
			("vector-set!" base effect ((v k x) (setf (aref v k) x)))
			("vector->list" base value ((v) (coerce v 'list)) ((v from) (coerce (subseq v from) 'list))
			 ((v from to) (coerce (subseq v from to) 'list))
			 :function (lambda (v &optional (from 0) to) (coerce (subseq v from to) 'list)))
			("list->vector" base value ((l) (coerce l 'vector)))
			("vector-fill!" base effect ((v x) (fill v x)) ((v x from) (fill v x :start from))
			 ((v x from to) (fill v x :start from :end to))
			 :function (lambda (v x &optional (from 0) to) (fill v x :start from :end to) rontolisp::%scheme-unspecified))

			;; --- bytevectors: the (unsigned-byte 8) pack. Every constructor is a helper
			;; (SchemeLibrary.makesBytevectors gates the printer's #u8( arm on them).
			("bytevector?" base pred ((x) (typep x '(simple-array (unsigned-byte 8) (*)))))
			("make-bytevector" base value ((n) (rontolisp::%scheme-make-bytevector n 0))
			 ((n fill) (rontolisp::%scheme-make-bytevector n fill))
			 :function (lambda (n &optional (fill 0)) (rontolisp::%scheme-make-bytevector n fill)))
			("bytevector" base value ((&rest r) (rontolisp::%scheme-bytevector (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-bytevector r)))
			("bytevector-length" base value ((v) (length v)))
			("bytevector-u8-ref" base value ((v k) (aref v k)))
			("bytevector-u8-set!" base effect ((v k b) (setf (aref v k) (rontolisp::%scheme-byte "bytevector-u8-set!" b))))
			("bytevector-copy" base value ((v) (copy-seq v)) ((v from) (subseq v from)) ((v from to) (subseq v from to))
			 :function (lambda (v &optional (from 0) to) (subseq v from to)))
			("bytevector-copy!" base effect ((to at from) (replace to from :start1 at))
			 ((to at from start) (replace to from :start1 at :start2 start))
			 ((to at from start end) (replace to from :start1 at :start2 start :end2 end))
			 :function (lambda (to at from &optional (start 0) end) (replace to from :start1 at :start2 start :end2 end) rontolisp::%scheme-unspecified))
			("bytevector-append" base value ((&rest r) (rontolisp::%scheme-bytevector-append (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-bytevector-append r)))
			("utf8->string" base value ((v) (rontolisp:octets-to-string v)) ((v from) (rontolisp:octets-to-string (subseq v from)))
			 ((v from to) (rontolisp:octets-to-string (subseq v from to)))
			 :function (lambda (v &optional (from 0) to) (rontolisp:octets-to-string (subseq v from to))))
			("string->utf8" base value ((s) (rontolisp::%scheme-string->utf8 s)) ((s from) (rontolisp::%scheme-string->utf8 (subseq s from)))
			 ((s from to) (rontolisp::%scheme-string->utf8 (subseq s from to)))
			 :function (lambda (s &optional (from 0) to) (rontolisp::%scheme-string->utf8 (subseq s from to))))

			;; --- control ---
			("procedure?" base pred ((x) (functionp x)))
			("apply" base value ((f a &rest r) (apply (rontolisp::%scheme-ensure-procedure f) a . r)) :function (lambda (f &rest r) (apply (rontolisp::%scheme-ensure-procedure f) (rontolisp::%scheme-spread r))))
			("map" base value ((f l &rest r) (mapcar (rontolisp::%scheme-ensure-procedure f) l . r)) :function (lambda (f &rest r) (apply #'mapcar (rontolisp::%scheme-ensure-procedure f) r)))
			("for-each" base effect ((f l &rest r) (mapc (rontolisp::%scheme-ensure-procedure f) l . r))
			 :function (lambda (f &rest r) (apply #'mapc (rontolisp::%scheme-ensure-procedure f) r) rontolisp::%scheme-unspecified))
			("call/cc" base value ((f) (rontolisp::%scheme-call/cc (rontolisp::%scheme-ensure-procedure f))))
			("call-with-current-continuation" base value ((f) (rontolisp::%scheme-call/cc (rontolisp::%scheme-ensure-procedure f))))
			("dynamic-wind" base value ((before thunk after) (rontolisp::%scheme-dynamic-wind (rontolisp::%scheme-ensure-procedure before) (rontolisp::%scheme-ensure-procedure thunk) (rontolisp::%scheme-ensure-procedure after))))
			;; parameterize is syntax (SchemeLowering); a parameter object is a procedure.
			("make-parameter" base value ((x) (rontolisp::%scheme-make-parameter x nil))
			 ((x converter) (rontolisp::%scheme-make-parameter x (rontolisp::%scheme-ensure-procedure converter)))
			 :function (lambda (x &optional converter)
			             (rontolisp::%scheme-make-parameter x (if converter (rontolisp::%scheme-ensure-procedure converter)))))
			("values" base value ((&rest r) (values . r)) :function #'values)
			("call-with-values" base value
			 ((producer consumer) (apply (rontolisp::%scheme-ensure-procedure consumer) (multiple-value-list (funcall (rontolisp::%scheme-ensure-procedure producer))))))

			;; --- exceptions (R7RS 6.11): guard is syntax (SchemeLowering). An error object
			;; is a condition, so a guard and a handler see what a built-in signals too.
			("error" base value ((message &rest r) (rontolisp::%scheme-signal-error message (list . r)))
			 :function (lambda (message &rest r) (rontolisp::%scheme-signal-error message r)))
			("raise" base value ((x) (rontolisp::%scheme-raise x)))
			("raise-continuable" base value ((x) (rontolisp::%scheme-raise-continuable x)))
			("with-exception-handler" base value
			 ((handler thunk) (rontolisp::%scheme-with-exception-handler (rontolisp::%scheme-ensure-procedure handler)
			                                                            (rontolisp::%scheme-ensure-procedure thunk))))
			("error-object?" base pred ((x) (typep x 'condition)))
			("error-object-message" base value ((x) (rontolisp::%scheme-error-object-message x)))
			("error-object-irritants" base value ((x) (rontolisp::%scheme-error-object-irritants x)))
			("read-error?" base pred ((x) (typep x 'reader-error)))
			("file-error?" base pred ((x) (typep x 'file-error)))

			;; --- output: an optional port argument; without one the current output
			;; port, which is whatever *standard-output* holds (parameterize binds it).
			("newline" base effect (() (terpri)) ((p) (terpri (rontolisp::%scheme-output-stream "newline" p)))
			 :function (lambda (&optional p)
			             (if p (terpri (rontolisp::%scheme-output-stream "newline" p)) (terpri))
			             rontolisp::%scheme-unspecified))
			("write-char" base effect ((c) (write-char c)) ((c p) (write-char c (rontolisp::%scheme-output-stream "write-char" p)))
			 :function (lambda (c &optional p)
			             (if p (write-char c (rontolisp::%scheme-output-stream "write-char" p)) (write-char c))
			             rontolisp::%scheme-unspecified))
			("write-string" base effect ((s) (write-string s)) ((s p) (rontolisp::%scheme-write-string-to s p nil nil))
			 ((s p start) (rontolisp::%scheme-write-string-to s p start nil))
			 ((s p start end) (rontolisp::%scheme-write-string-to s p start end))
			 :function (lambda (s &optional p start end)
			             (if p (rontolisp::%scheme-write-string-to s p start end) (write-string s))
			             rontolisp::%scheme-unspecified))
			("display" write effect ((x) (rontolisp::%scheme-display x)) ((x p) (rontolisp::%scheme-display-to x p))
			 :function (lambda (x &optional p)
			             (if p (rontolisp::%scheme-display-to x p) (rontolisp::%scheme-display x))
			             rontolisp::%scheme-unspecified))
			("write" write effect ((x) (rontolisp::%scheme-write x)) ((x p) (rontolisp::%scheme-write-to "write" x p))
			 :function (lambda (x &optional p)
			             (if p (rontolisp::%scheme-write-to "write" x p) (rontolisp::%scheme-write x))
			             rontolisp::%scheme-unspecified))
			("write-shared" write effect ((x) (rontolisp::%scheme-write-shared x)) ((x p) (rontolisp::%scheme-write-shared-to x p))
			 :function (lambda (x &optional p)
			             (if p (rontolisp::%scheme-write-shared-to x p) (rontolisp::%scheme-write-shared x))
			             rontolisp::%scheme-unspecified))
			("write-simple" write effect ((x) (rontolisp::%scheme-write x)) ((x p) (rontolisp::%scheme-write-to "write-simple" x p))
			 :function (lambda (x &optional p)
			             (if p (rontolisp::%scheme-write-to "write-simple" x p) (rontolisp::%scheme-write x))
			             rontolisp::%scheme-unspecified))
			("flush-output-port" base effect (() (finish-output *standard-output*))
			 ((p) (rontolisp::%scheme-flush-output-port p))
			 :function (lambda (&optional p)
			             (if p (rontolisp::%scheme-flush-output-port p) (finish-output *standard-output*))
			             rontolisp::%scheme-unspecified))

			;; --- input: an optional port argument; without one the current input port.
			;; (scheme read) exports read alone; the rest and the EOF object are (scheme base).
			("read" read value (() (rontolisp::%scheme-read)) ((p) (rontolisp::%scheme-read-from p))
			 :function (lambda (&optional p) (if p (rontolisp::%scheme-read-from p) (rontolisp::%scheme-read))))
			("eof-object" base value (() (rontolisp::%scheme-eof-object)))
			("eof-object?" base pred ((x) (rontolisp::%scheme-eof-object? x)))
			("read-char" base value (() (rontolisp::%scheme-read-char)) ((p) (rontolisp::%scheme-read-char-from p))
			 :function (lambda (&optional p) (if p (rontolisp::%scheme-read-char-from p) (rontolisp::%scheme-read-char))))
			("peek-char" base value (() (rontolisp::%scheme-peek-char)) ((p) (rontolisp::%scheme-peek-char-from p))
			 :function (lambda (&optional p) (if p (rontolisp::%scheme-peek-char-from p) (rontolisp::%scheme-peek-char))))
			("read-line" base value (() (rontolisp::%scheme-read-line)) ((p) (rontolisp::%scheme-read-line-from p))
			 :function (lambda (&optional p) (if p (rontolisp::%scheme-read-line-from p) (rontolisp::%scheme-read-line))))
			("read-string" base value ((k) (rontolisp::%scheme-read-chars k)) ((k p) (rontolisp::%scheme-read-chars-from k p))
			 :function (lambda (k &optional p) (if p (rontolisp::%scheme-read-chars-from k p) (rontolisp::%scheme-read-chars k))))
			("char-ready?" base pred (() (rontolisp::%scheme-char-ready?)) ((p) (rontolisp::%scheme-char-ready-from p))
			 :function (lambda (&optional p) (if p (rontolisp::%scheme-char-ready-from p)) t))

			;; --- ports (R7RS 6.13.1): records in scheme.lisp. The current ports are
			;; parameter objects: their value is the parameter, a call the current port.
			("current-input-port" base value (() (rontolisp::%scheme-current-port 0))
			 :function (rontolisp::%scheme-port-parameter 0))
			("current-output-port" base value (() (rontolisp::%scheme-current-port 1))
			 :function (rontolisp::%scheme-port-parameter 1))
			("current-error-port" base value (() (rontolisp::%scheme-current-port 2))
			 :function (rontolisp::%scheme-port-parameter 2))
				("port?" base pred ((x) (rontolisp::%scheme-port? x)))
				("input-port?" base pred ((x) (rontolisp::%scheme-port-kind? x t t)))
				("output-port?" base pred ((x) (rontolisp::%scheme-port-kind? x nil t)))
				("textual-port?" base pred ((x) (rontolisp::%scheme-port-kind? x nil nil)))
				("binary-port?" base pred ((x) (rontolisp::%scheme-port-kind? x t nil)))
			("input-port-open?" base pred ((p) (rontolisp::%scheme-port-open-p "input-port-open?" p t)))
			("output-port-open?" base pred ((p) (rontolisp::%scheme-port-open-p "output-port-open?" p nil)))
			("close-port" base effect ((p) (rontolisp::%scheme-close-port "close-port" p :any)))
			("close-input-port" base effect ((p) (rontolisp::%scheme-close-port "close-input-port" p t)))
			("close-output-port" base effect ((p) (rontolisp::%scheme-close-port "close-output-port" p nil)))
			("call-with-port" base value ((p f) (rontolisp::%scheme-call-with-port p (rontolisp::%scheme-ensure-procedure f))))
			("open-input-string" base value ((s) (rontolisp::%scheme-open-input-string s)))
			("open-output-string" base value (() (rontolisp::%scheme-open-output-string)))
			("get-output-string" base value ((p) (rontolisp::%scheme-get-output-string p)))

			;; --- binary ports over bytevectors; the standard ports are textual, so no
			;; port argument is the current port's refusal by name.
			("open-input-bytevector" base value ((v) (rontolisp::%scheme-open-input-bytevector v)))
			("open-output-bytevector" base value (() (rontolisp::%scheme-open-output-bytevector)))
			("get-output-bytevector" base value ((p) (rontolisp::%scheme-get-output-bytevector p)))
			("read-u8" base value (() (rontolisp::%scheme-read-u8 (rontolisp::%scheme-current-port 0) t))
			 ((p) (rontolisp::%scheme-read-u8 p t))
			 :function (lambda (&optional p) (rontolisp::%scheme-read-u8 (or p (rontolisp::%scheme-current-port 0)) t)))
			("peek-u8" base value (() (rontolisp::%scheme-read-u8 (rontolisp::%scheme-current-port 0) nil))
			 ((p) (rontolisp::%scheme-read-u8 p nil))
			 :function (lambda (&optional p) (rontolisp::%scheme-read-u8 (or p (rontolisp::%scheme-current-port 0)) nil)))
			("u8-ready?" base pred (() (rontolisp::%scheme-u8-ready (rontolisp::%scheme-current-port 0)))
			 ((p) (rontolisp::%scheme-u8-ready p))
			 :function (lambda (&optional p) (rontolisp::%scheme-u8-ready (or p (rontolisp::%scheme-current-port 0))) t))
			("read-bytevector" base value ((k) (rontolisp::%scheme-read-bytes k (rontolisp::%scheme-current-port 0)))
			 ((k p) (rontolisp::%scheme-read-bytes k p))
			 :function (lambda (k &optional p) (rontolisp::%scheme-read-bytes k (or p (rontolisp::%scheme-current-port 0)))))
			("read-bytevector!" base value ((v) (rontolisp::%scheme-read-bytes! v (rontolisp::%scheme-current-port 0) 0 nil))
			 ((v p) (rontolisp::%scheme-read-bytes! v p 0 nil))
			 ((v p start) (rontolisp::%scheme-read-bytes! v p start nil))
			 ((v p start end) (rontolisp::%scheme-read-bytes! v p start end))
			 :function (lambda (v &optional p (start 0) end)
			             (rontolisp::%scheme-read-bytes! v (or p (rontolisp::%scheme-current-port 0)) start end)))
			("write-u8" base effect ((b) (rontolisp::%scheme-write-u8 b (rontolisp::%scheme-current-port 1)))
			 ((b p) (rontolisp::%scheme-write-u8 b p))
			 :function (lambda (b &optional p)
			             (rontolisp::%scheme-write-u8 b (or p (rontolisp::%scheme-current-port 1)))
			             rontolisp::%scheme-unspecified))
			("write-bytevector" base effect ((v) (rontolisp::%scheme-write-bytevector v (rontolisp::%scheme-current-port 1) 0 nil))
			 ((v p) (rontolisp::%scheme-write-bytevector v p 0 nil))
			 ((v p start) (rontolisp::%scheme-write-bytevector v p start nil))
			 ((v p start end) (rontolisp::%scheme-write-bytevector v p start end))
			 :function (lambda (v &optional p (start 0) end)
			             (rontolisp::%scheme-write-bytevector v (or p (rontolisp::%scheme-current-port 1)) start end)
			             rontolisp::%scheme-unspecified))

			;; --- (scheme eval), (scheme repl) and the R5RS scheme-report-environment:
			;; every environment specifier is the one global environment, the symbol
			;; #[environment] (ENVIRONMENT_NAME); the evaluator is %scheme-eval in
			;; scheme.lisp, over the run-time table generated from these entries
			;; (runtimeForms). r5rs is no importable library: (scheme r5rs) would promise
			;; the whole of R5RS, so its names (this one, exact->inexact and
			;; inexact->exact) ride the no-import default like sicp.
			("eval" eval value ((x) (rontolisp::%scheme-eval x nil)) ((x env) (rontolisp::%scheme-eval-in x env))
			 :function (lambda (x &optional (env '|#[environment]|)) (rontolisp::%scheme-eval-in x env)))
			("environment" eval value ((&rest r) (rontolisp::%scheme-environment (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-environment r)))
			("interaction-environment" repl value (() '|#[environment]|))
			("scheme-report-environment" r5rs value ((v) (progn v '|#[environment]|)))

			;; --- (scheme process-context): exit throws to the catch tag every
			;; lowered file wraps its top-level forms in (SchemeLowering), unwinding
			;; through the outstanding dynamic-wind afters on its way out; the catch
			;; calls %scheme-exit. Only emergency-exit ends the process where the
			;; call stands, on every backend.
			("exit" process-context effect (() (throw 'rontolisp::%scheme-exit-tag t))
			 ((code) (throw 'rontolisp::%scheme-exit-tag code))
			 :function (lambda (&optional (code t)) (throw 'rontolisp::%scheme-exit-tag code)))
			("emergency-exit" process-context effect (() (rontolisp::%scheme-exit t))
			 ((code) (rontolisp::%scheme-exit code))
			 :function (lambda (&optional (code t)) (rontolisp::%scheme-exit code)))
			""";

	private static final SequencedMap<String, Entry> ENTRIES = parse(TABLE);

	/**
	 * What {@link SchemeStandard#R7RS} reads differently, in the table's own shape: the
	 * environment argument of {@code eval} is required (R7RS 6.12).
	 */
	private static final String R7RS_TABLE = """
			("eval" eval value ((x env) (rontolisp::%scheme-eval-in x env))
			 :function (lambda (x env) (rontolisp::%scheme-eval-in x env)))
			""";

	/**
	 * The entries under {@link SchemeStandard#R7RS}: no {@code sicp} or {@code r5rs}
	 * name, and the {@link #R7RS_TABLE} rows in place of their defaults.
	 */
	private static final SequencedMap<String, Entry> R7RS_ENTRIES = strictEntries();

	/**
	 * The bare VALUES (not procedures) the {@code sicp} tag provides, each as the form
	 * answering it: MIT Scheme's {@code true} / {@code false} / {@code nil}, the empty
	 * stream, and the two MIT names of the global environment. The lowering binds each as
	 * a constant; the run-time table answers it beside the procedures.
	 */
	private static final SequencedMap<String, LispVal> CONSTANTS = buildConstants();

	/** What the run-time table answers for a name it does not know. */
	private static final String UNBOUND = "RONTOLISP::%SCHEME-UNBOUND";

	private SchemeBuiltins() {
	}

	/**
	 * Every known procedure, keyed by its Scheme name, in table order.
	 * @return the entries
	 */
	static SequencedMap<String, Entry> entries() {
		return ENTRIES;
	}

	/**
	 * Every procedure a program read against the standard can reach, keyed by its Scheme
	 * name, in table order.
	 * @param standard the standard
	 * @return the entries
	 */
	static SequencedMap<String, Entry> entries(SchemeStandard standard) {
		return standard == SchemeStandard.R7RS ? R7RS_ENTRIES : ENTRIES;
	}

	/**
	 * The bare values a program read against the standard can reach: none under
	 * {@link SchemeStandard#R7RS}, where they are all {@code sicp}.
	 * @param standard the standard
	 * @return the name to the form answering its value
	 */
	static SequencedMap<String, LispVal> constants(SchemeStandard standard) {
		return standard == SchemeStandard.R7RS ? Collections.emptySortedMap() : CONSTANTS;
	}

	/**
	 * Whether a library tag is an R7RS library, as opposed to {@code sicp} /
	 * {@code r5rs}, which strict R7RS never sees.
	 * @param library the tag
	 * @return {@code true} for an R7RS library
	 */
	static boolean isR7rsLibrary(String library) {
		return !library.equals("sicp") && !library.equals("r5rs");
	}

	private static SequencedMap<String, Entry> strictEntries() {
		SequencedMap<String, Entry> overrides = parse(R7RS_TABLE);
		SequencedMap<String, Entry> entries = new LinkedHashMap<>();
		ENTRIES.forEach((name, entry) -> {
			if (isR7rsLibrary(entry.library())) {
				entries.put(name, overrides.getOrDefault(name, entry));
			}
		});
		if (!entries.keySet().containsAll(overrides.keySet())) {
			throw new IllegalStateException("an R7RS override names no R7RS entry: " + overrides.keySet());
		}
		return Collections.unmodifiableSequencedMap(entries);
	}

	/**
	 * The {@code sicp} tag's bare values, keyed by their Scheme name.
	 * @return the name to the form answering its value
	 */
	static SequencedMap<String, LispVal> constants() {
		return CONSTANTS;
	}

	private static SequencedMap<String, LispVal> buildConstants() {
		SequencedMap<String, LispVal> constants = new LinkedHashMap<>();
		LispVal environment = list(new LispSymbol("QUOTE"), new LispSymbol(ENVIRONMENT_NAME));
		constants.put("true", LispTrue.INSTANCE);
		constants.put("false", new LispSymbol(FALSE_VARIABLE));
		constants.put("nil", LispNil.INSTANCE);
		constants.put("the-empty-stream", LispNil.INSTANCE);
		constants.put("user-initial-environment", environment);
		constants.put("system-global-environment", environment);
		return Collections.unmodifiableSequencedMap(constants);
	}

	/**
	 * The run-time half of this table, for {@code eval}:
	 * {@code (rontolisp::%scheme-builtin name)} answers the first-class value of the
	 * procedure or constant whose MANGLED name is {@code name}, or the symbol
	 * {@code rontolisp::%scheme-unbound}. Generated here so the table is spelled once;
	 * {@code eval/SchemeLibrary} appends it to {@code scheme.lisp}'s forms, in the same
	 * canonical shape.
	 *
	 * <p>
	 * A compiled program carries only the entries whose names it SPELLS: every
	 * {@code :function} value in one {@code case} reaches every helper there is (the
	 * sequence runtime behind {@code coerce}, {@code subseq}, {@code reduce}, ...) -- 291
	 * KB of class and 235 KB of wasm for one {@code (eval '(+ 1 2))} against 81 KB / 16
	 * KB without, measured 2026-09-17 -- and a datum a program can hand {@code eval} is
	 * built from the symbols and strings it spells, the same rule the compiled name
	 * registry applies to Common Lisp's {@code eval} ({@code .kb/eval-runtime.md}).
	 * @param mangle a Scheme name to its symbol name ({@code SchemeNames.mangle}, which
	 * this table does not reach for itself: the names reach for this table)
	 * @param spelled whether a mangled name is spelled by the program the table is for
	 * @param standard the standard the program is read against: under
	 * {@link SchemeStandard#R7RS} the table holds no {@code sicp} or {@code r5rs} name,
	 * so {@code eval} cannot reach one either
	 * @return the definition
	 */
	static List<LispVal> runtimeForms(UnaryOperator<String> mangle, Predicate<String> spelled,
			SchemeStandard standard) {
		List<LispVal> arms = new ArrayList<>();
		for (Entry entry : entries(standard).values()) {
			String key = mangle.apply(entry.name());
			if (spelled.test(key)) {
				arms.add(list(list(new LispSymbol(key)), entry.function()));
			}
		}
		constants(standard).forEach((name, form) -> {
			String key = mangle.apply(name);
			if (spelled.test(key)) {
				arms.add(list(list(new LispSymbol(key)), form));
			}
		});
		arms.add(list(LispTrue.INSTANCE, list(new LispSymbol("QUOTE"), new LispSymbol(UNBOUND))));
		LispSymbol name = new LispSymbol("NAME");
		return List.of(list(new LispSymbol("DEFUN"), new LispSymbol("RONTOLISP::%SCHEME-BUILTIN"), list(name),
				new LispCons(new LispSymbol("CASE"), new LispCons(name, listOf(arms)))));
	}

	/**
	 * Converts a raw template value to a Scheme value: a predicate's {@code T}/
	 * {@code NIL} becomes {@code #t}/{@code #f}, an {@code or-false}'s {@code NIL}
	 * becomes {@code #f}.
	 * @param result what the raw form answers
	 * @param raw the raw form
	 * @return a form answering a Scheme value
	 */
	static LispVal toSchemeValue(Result result, LispVal raw) {
		LispSymbol falseVariable = new LispSymbol(FALSE_VARIABLE);
		return switch (result) {
			case VALUE -> raw;
			case EFFECT -> list(new LispSymbol("PROGN"), raw, new LispSymbol(UNSPECIFIED_VARIABLE));
			case PREDICATE -> list(new LispSymbol("IF"), raw, LispTrue.INSTANCE, falseVariable);
			case OR_FALSE -> list(new LispSymbol("OR"), raw, falseVariable);
		};
	}

	private static SequencedMap<String, Entry> parse(String table) {
		SequencedMap<String, Entry> entries = new LinkedHashMap<>();
		for (LispVal row : LispReader.readAllFromString(table, Features.INTERPRETER)) {
			Entry entry = entry(((LispCons) row).toList());
			if (entries.put(entry.name(), entry) != null) {
				throw new IllegalStateException("duplicate Scheme builtin: " + entry.name());
			}
		}
		return Collections.unmodifiableSequencedMap(entries);
	}

	private static Entry entry(List<LispVal> row) {
		String name = ((LispString) row.get(0)).value();
		String library = ((LispSymbol) row.get(1)).name().toLowerCase(java.util.Locale.ROOT);
		Result result = switch (((LispSymbol) row.get(2)).name()) {
			case "VALUE" -> Result.VALUE;
			case "PRED" -> Result.PREDICATE;
			case "OR-FALSE" -> Result.OR_FALSE;
			case "EFFECT" -> Result.EFFECT;
			default -> throw new IllegalStateException("unknown result kind for " + name);
		};
		List<Alternative> alternatives = new ArrayList<>();
		LispVal function = null;
		for (int i = 3; i < row.size(); i++) {
			if (row.get(i) instanceof LispSymbol keyword && keyword.name().equals(":FUNCTION")) {
				function = row.get(++i);
			}
			else {
				alternatives.add(alternative(((LispCons) row.get(i)).toList()));
			}
		}
		if (function == null) {
			function = derivedFunction(name, result, alternatives);
		}
		return new Entry(name, library, result, List.copyOf(alternatives), function);
	}

	private static Alternative alternative(List<LispVal> pair) {
		List<String> required = new ArrayList<>();
		String rest = null;
		List<LispVal> params = pair.get(0) instanceof LispCons cons ? cons.toList() : List.of();
		for (int i = 0; i < params.size(); i++) {
			String param = ((LispSymbol) params.get(i)).name();
			if (param.equals("&REST")) {
				rest = ((LispSymbol) params.get(++i)).name();
			}
			else {
				required.add(param);
			}
		}
		return new Alternative(List.copyOf(required), rest, pair.get(1));
	}

	// (lambda (params) template), converted to Scheme values; only a single fixed-arity
	// alternative says enough to derive it.
	private static LispVal derivedFunction(String name, Result result, List<Alternative> alternatives) {
		if (alternatives.size() != 1 || alternatives.get(0).rest() != null) {
			throw new IllegalStateException("Scheme builtin " + name + " needs an explicit :function");
		}
		Alternative only = alternatives.get(0);
		List<LispVal> params = new ArrayList<>();
		for (String param : only.required()) {
			params.add(new LispSymbol(param));
		}
		return list(new LispSymbol("LAMBDA"), listOf(params), toSchemeValue(result, only.template()));
	}

	private static LispVal expand(Alternative alternative, List<LispVal> arguments, Supplier<LispSymbol> temporaries) {
		SequencedMap<String, LispVal> substitution = new LinkedHashMap<>();
		List<LispVal> bindings = new ArrayList<>();
		for (int i = 0; i < alternative.required().size(); i++) {
			String param = alternative.required().get(i);
			LispVal argument = arguments.get(i);
			// An argument the template names more than once is evaluated once.
			if (argument instanceof LispCons && occurrences(alternative.template(), param) > 1) {
				LispSymbol temporary = temporaries.get();
				bindings.add(list(temporary, argument));
				argument = temporary;
			}
			substitution.put(param, argument);
		}
		List<LispVal> rest = arguments.subList(alternative.required().size(), arguments.size());
		LispVal body = substitute(alternative.template(), new Substitution(substitution,
				alternative.rest() == null ? null : new RestArguments(alternative.rest(), rest)));
		// Nested single-binding lets keep the arguments' evaluation order on every
		// backend.
		for (int i = bindings.size() - 1; i >= 0; i--) {
			body = list(new LispSymbol("LET"), list(bindings.get(i)), body);
		}
		return body;
	}

	private record RestArguments(String name, List<LispVal> arguments) {
	}

	private record Substitution(SequencedMap<String, LispVal> required, @Nullable RestArguments rest) {
	}

	private static LispVal substitute(LispVal template, Substitution substitution) {
		RestArguments rest = substitution.rest();
		if (template instanceof LispSymbol symbol) {
			if (rest != null && symbol.name().equals(rest.name())) {
				return listOf(rest.arguments());
			}
			LispVal argument = substitution.required().get(symbol.name());
			return argument == null ? symbol : argument;
		}
		if (template instanceof LispCons cons) {
			// (quote x) and (function x) name things, they never hold a parameter.
			if (cons.car() instanceof LispSymbol head
					&& (head.name().equals("QUOTE") || head.name().equals("FUNCTION"))) {
				return cons;
			}
			// The operator position names an operator, never a parameter.
			LispVal head = cons.car() instanceof LispSymbol ? cons.car() : substitute(cons.car(), substitution);
			return new LispCons(head, substituteArguments(cons.cdr(), substitution));
		}
		return template;
	}

	private static LispVal substituteArguments(LispVal arguments, Substitution substitution) {
		if (arguments instanceof LispCons cons) {
			return new LispCons(substitute(cons.car(), substitution), substituteArguments(cons.cdr(), substitution));
		}
		// The dotted tail: the &rest parameter, spliced.
		return substitute(arguments, substitution);
	}

	private static int occurrences(LispVal template, String param) {
		if (template instanceof LispSymbol symbol) {
			return symbol.name().equals(param) ? 1 : 0;
		}
		if (template instanceof LispCons cons) {
			return occurrences(cons.car(), param) + occurrences(cons.cdr(), param);
		}
		return 0;
	}

	static LispVal list(LispVal... elements) {
		return listOf(List.of(elements));
	}

	static LispVal listOf(List<LispVal> elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			list = new LispCons(elements.get(i), list);
		}
		return list;
	}

}
