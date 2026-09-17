package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.function.Supplier;

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
	 * The unspecified object's symbol name, MIT Scheme's spelling of it. Escaped by
	 * {@link SchemeNames#mangle}, so no identifier and no {@code string->symbol} can
	 * forge it.
	 */
	static final String UNSPECIFIED_NAME = "#!unspecific";

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
			("quotient" base value ((a b) (values (truncate a b))))
			("remainder" base value ((a b) (rem a b)))
			("modulo" base value ((a b) (mod a b)))
			("truncate-quotient" base value ((a b) (values (truncate a b))))
			("truncate-remainder" base value ((a b) (rem a b)))
			("floor-quotient" base value ((a b) (values (floor a b))))
			("floor-remainder" base value ((a b) (mod a b)))
			("abs" base value ((x) (abs x)))
			("min" base value ((a) (values a)) ((a b) (rontolisp::%scheme-min a b))
			 ((a &rest r) (reduce #'rontolisp::%scheme-min (list . r) :initial-value a))
			 :function (lambda (a &rest r) (reduce #'rontolisp::%scheme-min r :initial-value a)))
			("max" base value ((a) (values a)) ((a b) (rontolisp::%scheme-max a b))
			 ((a &rest r) (reduce #'rontolisp::%scheme-max (list . r) :initial-value a))
			 :function (lambda (a &rest r) (reduce #'rontolisp::%scheme-max r :initial-value a)))
			("gcd" base value ((&rest r) (gcd . r)) :function (lambda (&rest r) (reduce #'gcd r :initial-value 0)))
			("lcm" base value ((&rest r) (lcm . r)) :function (lambda (&rest r) (reduce #'lcm r :initial-value 1)))
			("expt" base value ((a b) (expt a b)))
			("square" base value ((x) (* x x)))
			("floor" base value ((x) (if (floatp x) (float (floor x) 1.0d0) (values (floor x)))))
			("ceiling" base value ((x) (if (floatp x) (float (ceiling x) 1.0d0) (values (ceiling x)))))
			("round" base value ((x) (if (floatp x) (float (round x) 1.0d0) (values (round x)))))
			("truncate" base value ((x) (if (floatp x) (float (truncate x) 1.0d0) (values (truncate x)))))
			("zero?" base pred ((x) (zerop x)))
			("positive?" base pred ((x) (plusp x)))
			("negative?" base pred ((x) (minusp x)))
			("odd?" base pred ((x) (oddp x)))
			("even?" base pred ((x) (evenp x)))
			("number?" base pred ((x) (numberp x)))
			("real?" base pred ((x) (realp x)))
			("rational?" base pred ((x) (realp x)))
			("integer?" base pred ((x) (rontolisp::%scheme-integer? x)))
			("exact?" base pred ((x) (rationalp x)))
			("inexact?" base pred ((x) (floatp x)))
			("exact-integer?" base pred ((x) (integerp x)))
			("exact" base value ((x) (rational x)))
			("inexact" base value ((x) (float x 1.0d0)))
			("inexact->exact" base value ((x) (rational x)))
			("exact->inexact" base value ((x) (float x 1.0d0)))
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
			("list-copy" base value ((l) (copy-list l)))
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
			("reduce" sicp value ((op initial l) (if (null l) initial (reduce op l))))
			("fold-left" sicp value ((op initial l) (reduce op l :initial-value initial)))
			("fold-right" sicp value ((op initial l) (reduce op l :initial-value initial :from-end t)))
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
			("stream-car" sicp value ((s) (car s)))
			("stream-cdr" sicp value ((s) (rontolisp::%scheme-stream-cdr s)))
			("stream-first" sicp value ((s) (car s)))
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
			("string" base value ((&rest r) (coerce (list . r) 'string))
			 :function (lambda (&rest r) (coerce r 'string)))
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
			("list->string" base value ((l) (coerce l 'string)))

			;; --- vectors ---
			("vector?" base pred ((x) (and (vectorp x) (not (stringp x)))))
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

			;; --- control ---
			("procedure?" base pred ((x) (functionp x)))
			("apply" base value ((f a &rest r) (apply f a . r)) :function (lambda (f &rest r) (apply f (rontolisp::%scheme-spread r))))
			("map" base value ((f l &rest r) (mapcar f l . r)) :function (lambda (f &rest r) (apply #'mapcar f r)))
			("for-each" base effect ((f l &rest r) (mapc f l . r))
			 :function (lambda (f &rest r) (apply #'mapc f r) rontolisp::%scheme-unspecified))
			("call/cc" base value ((f) (rontolisp::%scheme-call/cc f)))
			("call-with-current-continuation" base value ((f) (rontolisp::%scheme-call/cc f)))
			("dynamic-wind" base value ((before thunk after) (rontolisp::%scheme-dynamic-wind before thunk after)))
			("values" base value ((&rest r) (values . r)) :function #'values)
			("call-with-values" base value
			 ((producer consumer) (apply consumer (multiple-value-list (funcall producer)))))
			("error" base value ((message &rest r) (error "~A" (rontolisp::%scheme-error-message message (list . r))))
			 :function (lambda (message &rest r) (error "~A" (rontolisp::%scheme-error-message message r))))

			;; --- output: the current output port only ---
			("newline" base effect (() (terpri)))
			("write-char" base effect ((c) (write-char c)))
			("write-string" base effect ((s) (write-string s)))
			("display" write effect ((x) (rontolisp::%scheme-display x)))
			("write" write effect ((x) (rontolisp::%scheme-write x)))
			("write-shared" write effect ((x) (rontolisp::%scheme-write x)))
			("write-simple" write effect ((x) (rontolisp::%scheme-write x)))

			;; --- (scheme eval), (scheme repl) and the R5RS scheme-report-environment:
			;; every environment specifier is the one global environment, the symbol
			;; #[environment] (ENVIRONMENT_NAME); the evaluator is %scheme-eval in
			;; scheme.lisp, over the run-time table generated from these entries
			;; (runtimeForms). r5rs is no importable library: (scheme r5rs) would promise
			;; the whole of R5RS, so its one name rides the no-import default like sicp.
			("eval" eval value ((x) (rontolisp::%scheme-eval x nil)) ((x env) (rontolisp::%scheme-eval-in x env))
			 :function (lambda (x &optional (env '|#[environment]|)) (rontolisp::%scheme-eval-in x env)))
			("environment" eval value ((&rest r) (rontolisp::%scheme-environment (list . r)))
			 :function (lambda (&rest r) (rontolisp::%scheme-environment r)))
			("interaction-environment" repl value (() '|#[environment]|))
			("scheme-report-environment" r5rs value ((v) (progn v '|#[environment]|)))

			;; --- (scheme process-context): the process ends where the call stands, on
			;; every backend -- no dynamic-wind after thunk runs, for exit either.
			("exit" process-context effect (() (rontolisp::%scheme-exit t)) ((code) (rontolisp::%scheme-exit code))
			 :function (lambda (&optional (code t)) (rontolisp::%scheme-exit code)))
			("emergency-exit" process-context effect (() (rontolisp::%scheme-exit t))
			 ((code) (rontolisp::%scheme-exit code))
			 :function (lambda (&optional (code t)) (rontolisp::%scheme-exit code)))
			""";

	private static final SequencedMap<String, Entry> ENTRIES = parse();

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
	 * @param spelled whether a mangled name is spelled by the program the table is for
	 * @return the definition
	 */
	static List<LispVal> runtimeForms(java.util.function.Predicate<String> spelled) {
		List<LispVal> arms = new ArrayList<>();
		for (Entry entry : ENTRIES.values()) {
			String mangled = SchemeNames.mangle(entry.name());
			if (spelled.test(mangled)) {
				arms.add(list(list(new LispSymbol(mangled)), entry.function()));
			}
		}
		CONSTANTS.forEach((name, form) -> {
			String mangled = SchemeNames.mangle(name);
			if (spelled.test(mangled)) {
				arms.add(list(list(new LispSymbol(mangled)), form));
			}
		});
		// The otherwise clause spelled by the reader, so its `t` is whatever the reader
		// makes of one.
		arms.add(LispReader
			.readAllFromString("(t '" + UNBOUND.toLowerCase(java.util.Locale.ROOT) + ")", Features.INTERPRETER)
			.get(0));
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

	private static SequencedMap<String, Entry> parse() {
		SequencedMap<String, Entry> entries = new LinkedHashMap<>();
		for (LispVal row : LispReader.readAllFromString(TABLE, Features.INTERPRETER)) {
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
