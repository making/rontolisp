;; The run-time half of the EXPERIMENTAL Scheme front end (am.ik.rontolisp.scheme),
;; written in Common Lisp so one definition runs on every backend and no emitter
;; learns a Scheme name: the interpreter loads it on the first resolution of a
;; rontolisp::%scheme- function, the compile path splices it when the program
;; references one (SchemeLibrary.java) and the tree-shaker drops what stays
;; unreachable.
;;
;; The value model these helpers share with the lowering (.kb/scheme-frontend.md):
;; '() is NIL, #t is T, and #f is the value of rontolisp::%scheme-false -- a DISTINCT
;; non-NIL object the lowered program binds before anything else runs. A helper
;; answering a Common Lisp boolean (T/NIL) is declared `pred` or `or-false` in
;; SchemeBuiltins, which converts at the call site; every other helper answers
;; Scheme values itself.
;;
;; Symbol names follow SchemeNames.mangle: a spelling that could collide with a
;; canonical name is escaped behind "s%" (with % -> %%, : -> %c). The rule is spelled
;; TWICE, there and in %scheme-needs-escape below; change the two together.

;; --- symbols ------------------------------------------------------------------

(defun rontolisp::%scheme-escaped-p (name)
  (and (>= (length name) 2) (char= (char name 0) #\s)
       (char= (char name 1) #\%)))

(defun rontolisp::%scheme-has-lowercase (name)
  (do ((i 0 (+ i 1)))
      ((>= i (length name)) nil)
    (let ((c (char name i)))
      (if (and (char>= c #\a) (char<= c #\z)) (return t)))))

(defun rontolisp::%scheme-needs-escape (name)
  (or (rontolisp::%scheme-escaped-p name)
      (and (> (length name) 0) (char= (char name 0) #\&)) (string= name "#f")
      (find #\: name) (not (rontolisp::%scheme-has-lowercase name))))

(defun rontolisp::%scheme-symbol->string (symbol)
  (let ((name (symbol-name symbol)))
    (if (rontolisp::%scheme-escaped-p name)
        (let ((out nil) (n (length name)))
          (do ((i 2 (+ i 1)))
              ((>= i n))
            (let ((c (char name i)))
              (if (and (char= c #\%) (< (+ i 1) n))
                  (progn
                    (setq i (+ i 1))
                    (setq out
                          (cons (if (char= (char name i) #\c) #\: (char name i))
                                out)))
                  (setq out (cons c out)))))
          (coerce (nreverse out) 'string))
        (copy-seq name))))

(defun rontolisp::%scheme-string->symbol (name)
  (if (rontolisp::%scheme-needs-escape name)
      (let ((out (list #\% #\s)))
        (do ((i 0 (+ i 1)))
            ((>= i (length name)))
          (let ((c (char name i)))
            (cond ((char= c #\%) (setq out (cons #\% (cons #\% out))))
                  ((char= c #\:) (setq out (cons #\c (cons #\% out))))
                  (t (setq out (cons c out))))))
        (intern (coerce (nreverse out) 'string)))
      (intern name)))

;; --- write / display ------------------------------------------------------------

(defun rontolisp::%scheme-write-char-datum (c)
  (write-string "#\\")
  (let ((code (char-code c)))
    (cond ((= code 32) (write-string "space"))
          ((= code 10) (write-string "newline"))
          ((= code 9) (write-string "tab"))
          ((= code 13) (write-string "return"))
          ((= code 0) (write-string "null"))
          ((= code 7) (write-string "alarm"))
          ((= code 8) (write-string "backspace"))
          ((= code 27) (write-string "escape"))
          ((= code 127) (write-string "delete"))
          (t (write-char c)))))

(defun rontolisp::%scheme-write-string-datum (s)
  (write-char #\")
  (do ((i 0 (+ i 1)))
      ((>= i (length s)))
    (let ((c (char s i)))
      (let ((code (char-code c)))
        (cond ((= code 34) (write-string "\\\""))
              ((= code 92) (write-string "\\\\"))
              ((= code 10) (write-string "\\n"))
              ((= code 9) (write-string "\\t"))
              ((= code 13) (write-string "\\r"))
              (t (write-char c))))))
  (write-char #\"))

;; Writes a symbol's Scheme spelling WITHOUT building it: the printer is in nearly every
;; program, and a string built from a character list drags the sequence runtime in
;; (measured 2026-09-17: a lone (display x) was 70 KB of class and 17.5 KB of wasm).
(defun rontolisp::%scheme-print-symbol (symbol)
  (let ((name (symbol-name symbol)))
    (if (rontolisp::%scheme-escaped-p name)
        (let ((n (length name)))
          (do ((i 2 (+ i 1)))
              ((>= i n))
            (let ((c (char name i)))
              (if (and (char= c #\%) (< (+ i 1) n))
                  (progn
                    (setq i (+ i 1))
                    (write-char
                     (if (char= (char name i) #\c) #\: (char name i))))
                  (write-char c)))))
        (write-string name))))

;; write and display must terminate on a circular structure (R7RS 6.13.3): a node a
;; cycle closes on is written with a datum label, #0=(1 2 . #0#). Sharing without a
;; cycle is written out each time, as write does.
;;
;; No eq hash table: on wasm one costs 16 KB of every printing program and scans a
;; single bucket for an aggregate key, which made writing a 50,000-element list take
;; a minute (measured 2026-09-17). Instead the datum is first walked as the tree write
;; would print, which costs no more than the printing; only a structure that walk
;; cannot finish is searched for its cycles, with a list of the nodes seen.
(defun rontolisp::%scheme-print (x escape)
  (rontolisp::%scheme-print-datum x escape
                                  (if (and (rontolisp::%scheme-node-p x)
                                       (rontolisp::%scheme-may-cycle-p x 1000))
                                      (rontolisp::%scheme-cycle-labels x)))
  nil)

(defun rontolisp::%scheme-node-p (x)
  (or (consp x) (and (vectorp x) (not (stringp x)))))

;; Walks X as a tree and answers NIL when the walk ends, T as soon as it may not: a cdr
;; chain that meets itself (Brent's cycle detection), or car/element nesting deeper
;; than DEPTH -- where every cycle through a car or an element ends up, since the walk
;; of that car never returns.
(defun rontolisp::%scheme-may-cycle-p (x depth)
  (if (< depth 0)
      t
      (let ((tortoise x) (power 1) (steps 0) (cycle nil))
        (do ()
            ((or cycle (not (rontolisp::%scheme-node-p x))) cycle)
          (if (consp x)
              (if (and (rontolisp::%scheme-node-p (car x))
                       (rontolisp::%scheme-may-cycle-p (car x) (- depth 1)))
                  (setq cycle t)
                  (progn
                    (setq x (cdr x))
                    (setq steps (+ steps 1))
                    (if (eq x tortoise)
                        (setq cycle t)
                        (if (= steps power)
                            (progn
                              (setq tortoise x)
                              (setq power (+ power power))
                              (setq steps 0))))))
              (let ((v x))
                (setq x nil)
                (do ((i 0 (+ i 1)))
                    ((or cycle (>= i (length v))))
                  (if (and (rontolisp::%scheme-node-p (aref v i))
                       (rontolisp::%scheme-may-cycle-p (aref v i) (- depth 1)))
                      (setq cycle t)))))))))

;; The entry (node . state) of X in the list ENTRIES, or NIL.
(defun rontolisp::%scheme-entry (x entries)
  (do ((l entries (cdr l))) ((or (null l) (eq (car (car l)) x)) (car l))))

;; A depth-first walk recording each node in (car SEEN) with a state: 1 while its walk
;; is open, 2 once closed; 3 and 4 the same for a node reached again while open -- one
;; a cycle closes on. The cdr direction is a loop.
(defun rontolisp::%scheme-mark-cycles (x seen)
  (let ((spine nil))
    (do ()
        ((not (rontolisp::%scheme-node-p x)))
      (let ((entry (rontolisp::%scheme-entry x (car seen))))
        (cond ((null entry)
               (setq entry (cons x 1))
               (rplaca seen (cons entry (car seen)))
               (setq spine (cons entry spine))
               (if (consp x)
                   (progn
                     (rontolisp::%scheme-mark-cycles (car x) seen)
                     (setq x (cdr x)))
                   (let ((v x))
                     (setq x nil)
                     (do ((i 0 (+ i 1)))
                         ((>= i (length v)))
                       (rontolisp::%scheme-mark-cycles (aref v i) seen)))))
              (t
               (if (= (cdr entry) 1) (rplacd entry 3))
               (setq x nil)))))
    (do ((l spine (cdr l)))
        ((null l))
      (rplacd (car l) (if (= (cdr (car l)) 3) 4 2)))))

;; The labels X needs, as (entries . next-number): entries (node . 4) for each node a
;; cycle closes on, or NIL when there is none (a nesting past the walk's depth).
(defun rontolisp::%scheme-cycle-labels (x)
  (let ((seen (list nil)) (labeled nil))
    (rontolisp::%scheme-mark-cycles x seen)
    (do ((l (car seen) (cdr l)))
        ((null l))
      (if (= (cdr (car l)) 4) (setq labeled (cons (car l) labeled))))
    (if labeled (cons labeled 0))))

;; Whether X carries a label: one to define (4) or one already written (negative).
(defun rontolisp::%scheme-labeled-p (x labels)
  (rontolisp::%scheme-entry x (car labels)))

;; Writes X's label: #n= before its first appearance (answering NIL, the datum follows),
;; #n# after it (answering T, nothing more to write).
(defun rontolisp::%scheme-print-label (x labels)
  (let ((entry (rontolisp::%scheme-entry x (car labels))))
    (cond ((null entry) nil)
          ((= (cdr entry) 4)
           (let ((n (cdr labels)))
             (rplacd entry (- -1 n))
             (rplacd labels (+ n 1))
             (write-char #\#)
             (princ n)
             (write-char #\=)
             nil))
          (t
           (write-char #\#)
           (princ (- -1 (cdr entry)))
           (write-char #\#)
           t))))

(defun rontolisp::%scheme-print-datum (x escape labels)
  (cond ((eq x t) (write-string "#t"))
        ((eq x rontolisp::%scheme-false) (write-string "#f"))
        ((null x) (write-string "()"))
        ((symbolp x) (rontolisp::%scheme-print-symbol x))
        ((stringp x)
         (if escape (rontolisp::%scheme-write-string-datum x) (write-string x)))
        ((characterp x)
         (if escape (rontolisp::%scheme-write-char-datum x) (write-char x)))
        ((and labels (rontolisp::%scheme-node-p x)
              (rontolisp::%scheme-print-label x labels)))
        ((consp x)
         (write-char #\()
         (rontolisp::%scheme-print-datum (car x) escape labels)
         (do ((rest (cdr x) (cdr rest)))
             ((or (not (consp rest))
                  (and labels (rontolisp::%scheme-labeled-p rest labels)))
              (if (not (null rest))
                  (progn
                    (write-string " . ")
                    (rontolisp::%scheme-print-datum rest escape labels))))
           (write-char #\Space)
           (rontolisp::%scheme-print-datum (car rest) escape labels))
         (write-char #\)))
        ((vectorp x)
         (write-string "#(")
         (do ((i 0 (+ i 1)))
             ((>= i (length x)))
           (if (> i 0) (write-char #\Space))
           (rontolisp::%scheme-print-datum (aref x i) escape labels))
         (write-char #\)))
        ((floatp x) (rontolisp::%scheme-print-flonum x))
        ((functionp x) (write-string "#<procedure>"))
        (t (princ x))))

(defun rontolisp::%scheme-display (x) (rontolisp::%scheme-print x nil))

(defun rontolisp::%scheme-write (x) (rontolisp::%scheme-print x t))

;; --- equivalence, lists -----------------------------------------------------------

;; equal? recurses into pairs, strings and vectors (CL's equal compares a general
;; vector by identity) and is eqv? on everything else, records included. The cdr
;; direction is a loop, so a long list costs no stack.
(defun rontolisp::%scheme-equal? (a b)
  (do ((x a (cdr x)) (y b (cdr y)))
      ((not (and (consp x) (consp y)))
       (cond ((eql x y) t)
             ((stringp x) (and (stringp y) (string= x y) t))
             ((and (vectorp x) (vectorp y) (not (stringp y))
                   (= (length x) (length y)))
              (do ((i 0 (+ i 1)))
                  ((>= i (length x)) t)
                (if (not (rontolisp::%scheme-equal? (aref x i) (aref y i)))
                    (return nil))))
             (t nil)))
    (if (not (rontolisp::%scheme-equal? (car x) (car y))) (return nil))))

(defun rontolisp::%scheme-member (x list)
  (do ((rest list (cdr rest)))
      ((not (consp rest)) nil)
    (if (rontolisp::%scheme-equal? x (car rest)) (return rest))))

(defun rontolisp::%scheme-member-by (x list same)
  (do ((rest list (cdr rest)))
      ((not (consp rest)) nil)
    (if (not (eq (funcall same x (car rest)) rontolisp::%scheme-false))
        (return rest))))

(defun rontolisp::%scheme-assoc (x alist)
  (do ((rest alist (cdr rest)))
      ((not (consp rest)) nil)
    (if (and (consp (car rest)) (rontolisp::%scheme-equal? x (car (car rest))))
        (return (car rest)))))

(defun rontolisp::%scheme-assoc-by (x alist same)
  (do ((rest alist (cdr rest)))
      ((not (consp rest)) nil)
    (if (and (consp (car rest))
         (not (eq (funcall same x (car (car rest))) rontolisp::%scheme-false)))
        (return (car rest)))))

(defun rontolisp::%scheme-list? (x)
  (do ((rest x (cdr rest))) ((not (consp rest)) (null rest))))

;; SRFI-1 filter: pred is a Scheme procedure, so its answer is compared against the
;; false value rather than trusted as a Common Lisp boolean.
(defun rontolisp::%scheme-filter (pred list)
  (do ((rest (reverse list) (cdr rest)) (kept nil))
      ((not (consp rest)) kept)
    (if (not (eq (funcall pred (car rest)) rontolisp::%scheme-false))
        (setq kept (cons (car rest) kept)))))

;; SRFI-1 list-index: the position of the first element pred does not reject, else #f
;; (SchemeBuiltins converts nil to false; 0 is a true index, not a false one).
(defun rontolisp::%scheme-list-index (pred list)
  (do ((rest list (cdr rest)) (i 0 (+ i 1)))
      ((not (consp rest)) nil)
    (if (not (eq (funcall pred (car rest)) rontolisp::%scheme-false))
        (return i))))

;; (< a b c ...) as a first-class procedure: the Common Lisp function values of the
;; comparisons are binary, so the chain is walked pairwise. Answers T/NIL.
(defun rontolisp::%scheme-chain (compare arguments)
  (do ((rest arguments (cdr rest)))
      ((or (null rest) (null (cdr rest))) t)
    (if (not (funcall compare (car rest) (car (cdr rest)))) (return nil))))

;; --- numbers ----------------------------------------------------------------------

(defun rontolisp::%scheme-integer? (x)
  (or (integerp x) (and (floatp x) (= x (truncate x)))))

;; --- (scheme inexact) ---------------------------------------------------------------

;; A real argument whose Common Lisp answer is a complex number (sqrt -4, log -1, asin 2)
;; is refused by name: this front end has no complex numbers to print or compute with.
(defun rontolisp::%scheme-no-complex (message x)
  (error "~A" (rontolisp::%scheme-error-message message (list x))))

;; The exact root of a non-negative integer, or NIL when it has none.
(defun rontolisp::%scheme-exact-root (n)
  (let ((r (isqrt n))) (if (= (* r r) n) r nil)))

(defun rontolisp::%scheme-sqrt (x)
  (cond ((and (rationalp x) (>= x 0))
         (let ((n (rontolisp::%scheme-exact-root (numerator x)))
               (d (rontolisp::%scheme-exact-root (denominator x))))
           (if (and n d) (/ n d) (sqrt (float x 1.0d0)))))
        ((and (realp x) (minusp x))
         (rontolisp::%scheme-no-complex "sqrt: a negative argument has a complex root, and complex numbers are not supported:"
                                        x))
        (t (sqrt x))))

(defun rontolisp::%scheme-exact-integer-sqrt (k)
  (if (and (integerp k) (>= k 0))
      (let ((s (isqrt k))) (values s (- k (* s s))))
      (error "~A"
             (rontolisp::%scheme-error-message
              "exact-integer-sqrt: not an exact non-negative integer:"
              (list k)))))

;; The exact anchors R7RS implementations answer exactly: (exp 0) is 1, (log 1) is 0,
;; and so on. Anything else is Common Lisp's inexact answer.
(defun rontolisp::%scheme-exp (x) (if (eql x 0) 1 (exp x)))

(defun rontolisp::%scheme-log (x)
  (cond ((eql x 1) 0)
        ((and (realp x) (minusp x))
         (rontolisp::%scheme-no-complex "log: a negative argument has a complex logarithm, and complex numbers are not supported:"
                                        x))
        (t (log x))))

(defun rontolisp::%scheme-log-base (x base)
  (if (and (realp base) (minusp base))
      (rontolisp::%scheme-no-complex "log: a negative base has a complex logarithm, and complex numbers are not supported:"
                                     base)
      (if (eql x 1) 0 (/ (rontolisp::%scheme-log x) (log base)))))

(defun rontolisp::%scheme-sin (x) (if (eql x 0) 0 (sin x)))

(defun rontolisp::%scheme-cos (x) (if (eql x 0) 1 (cos x)))

(defun rontolisp::%scheme-tan (x) (if (eql x 0) 0 (tan x)))

(defun rontolisp::%scheme-asin (x)
  (cond ((eql x 0) 0)
        ((and (realp x) (> (abs x) 1))
         (rontolisp::%scheme-no-complex "asin: an argument outside [-1, 1] has a complex arcsine, and complex numbers are not supported:"
                                        x))
        (t (asin x))))

(defun rontolisp::%scheme-acos (x)
  (cond ((eql x 1) 0)
        ((and (realp x) (> (abs x) 1))
         (rontolisp::%scheme-no-complex "acos: an argument outside [-1, 1] has a complex arccosine, and complex numbers are not supported:"
                                        x))
        (t (acos x))))

(defun rontolisp::%scheme-atan (x) (if (eql x 0) 0 (atan x)))

(defun rontolisp::%scheme-atan2 (y x)
  (if (and (eql y 0) (rationalp x) (plusp x)) 0 (atan y x)))

(defun rontolisp::%scheme-nan? (x) (and (floatp x) (/= x x)))

(defun rontolisp::%scheme-infinite? (x)
  (and (floatp x)
       (or (> x most-positive-double-float) (< x most-negative-double-float))))

(defun rontolisp::%scheme-finite? (x)
  (not (or (rontolisp::%scheme-nan? x) (rontolisp::%scheme-infinite? x))))

;; The I-th significant digit of a printed float whose INTEGER-DIGITS digits start at
;; START and are followed by a point.
(defun rontolisp::%scheme-flonum-digit (s start integer-digits i)
  (char s (if (< i integer-digits) (+ start i) (+ start i 1))))

;; A flonum the way Scheme writes it: the shortest digits the Common Lisp printer finds,
;; laid out positionally when the point falls within 21 digits left of or 6 zeros right
;; of the first digit (123456789.123, 100000000000000000000.0, 0.000001), and as
;; <digits>e<exponent> outside that range (1e21, 1.5e-7) -- the ECMAScript thresholds.
;; The Common Lisp printer answers 1.0e21 and 1.23456789123e8.
(defun rontolisp::%scheme-print-flonum (x)
  (cond ((/= x x) (write-string "+nan.0"))
        ((> x most-positive-double-float) (write-string "+inf.0"))
        ((< x most-negative-double-float) (write-string "-inf.0"))
        (t
         (let ((s (princ-to-string x)) (n 0) (e nil))
           (setq n (length s))
           (do ((i 0 (+ i 1)))
               ((or e (>= i n)))
             (if (char= (char s i) #\e) (setq e i)))
           (if (null e)
               (write-string s)
               (let ((start (if (char= (char s 0) #\-) 1 0))
                     (integer-digits 0)
                     (count 0)
                     (exponent 0)
                     (exponent-sign 1)
                     (point 0))
                 (do ((i start (+ i 1)))
                     ((or (>= i e) (char= (char s i) #\.)))
                   (setq integer-digits (+ integer-digits 1)))
                 (setq count (- e start 1))
                 (do ()
                     ((or (<= count 1)
                          (char/= (rontolisp::%scheme-flonum-digit s start
                                   integer-digits (- count 1)) #\0)))
                   (setq count (- count 1)))
                 (do ((i (+ e 1) (+ i 1)))
                     ((>= i n))
                   (if (char= (char s i) #\-)
                       (setq exponent-sign -1)
                       (setq exponent
                        (+ (* exponent 10) (- (char-code (char s i)) 48)))))
                 (setq point (+ integer-digits (* exponent-sign exponent)))
                 (if (= start 1) (write-char #\-))
                 (cond ((or (> point 21) (<= point -6))
                        (write-char
                         (rontolisp::%scheme-flonum-digit s start integer-digits
                                                          0))
                        (if (> count 1) (write-char #\.))
                        (do ((i 1 (+ i 1)))
                            ((>= i count))
                          (write-char
                           (rontolisp::%scheme-flonum-digit s start
                                                            integer-digits i)))
                        (write-char #\e)
                        (princ (- point 1)))
                       ((<= point 0)
                        (write-string "0.")
                        (do ((i point (+ i 1)))
                            ((>= i 0))
                          (write-char #\0))
                        (do ((i 0 (+ i 1)))
                            ((>= i count))
                          (write-char
                           (rontolisp::%scheme-flonum-digit s start
                                                            integer-digits i))))
                       (t
                        (do ((i 0 (+ i 1)))
                            ((>= i (max point count)))
                          (if (= i point) (write-char #\.))
                          (write-char
                           (if (< i count)
                               (rontolisp::%scheme-flonum-digit s start
                                                                integer-digits
                                                                i)
                               #\0)))
                        (if (>= point count) (write-string ".0"))))))))))

;; max / min are inexact when any argument is (R7RS 6.2.6); CL's may answer the exact one.
(defun rontolisp::%scheme-max (a b)
  (let ((m (max a b))) (if (or (floatp a) (floatp b)) (float m 1.0d0) m)))

(defun rontolisp::%scheme-min (a b)
  (let ((m (min a b))) (if (or (floatp a) (floatp b)) (float m 1.0d0) m)))

(defun rontolisp::%scheme-number->string (n radix)
  (if (or (= radix 10) (not (integerp n)))
      (if (floatp n)
          (with-output-to-string (*standard-output*)
            (rontolisp::%scheme-print-flonum n))
          (princ-to-string n))
      (if (zerop n)
          "0"
          (do ((m (abs n) (truncate m radix))
               (digits
                nil
                (cons
                 (char "0123456789abcdefghijklmnopqrstuvwxyz" (rem m radix))
                 digits)))
              ((zerop m)
               (coerce (if (< n 0) (cons #\- digits) digits) 'string))))))

(defun rontolisp::%scheme-digit (c radix)
  (let ((code (char-code c)))
    (let ((value
           (cond ((and (>= code 48) (<= code 57)) (- code 48))
                 ((and (>= code 97) (<= code 122)) (- code 87))
                 ((and (>= code 65) (<= code 90)) (- code 55))
                 (t radix))))
      (if (< value radix) value nil))))

;; Scans digits of S from START: (value . end), END = START when there are none.
(defun rontolisp::%scheme-scan-digits (s start radix)
  (let ((value 0) (end start))
    (do ((i start (+ i 1)))
        ((>= i (length s)))
      (let ((digit (rontolisp::%scheme-digit (char s i) radix)))
        (if digit
            (progn
              (setq value (+ (* value radix) digit))
              (setq end (+ i 1)))
            (return nil))))
    (cons value end)))

;; [sign] digits [/ digits], and in radix 10 also [sign] digits* [. digits*] [e [sign]
;; digits+]. The decimal is built EXACTLY and converted once, so the result is the
;; correctly rounded double the reader would have produced for the same text.
(defun rontolisp::%scheme-string->number (s radix)
  (let ((n (length s)) (start 0) (sign 1))
    (if (and (> n 0) (or (char= (char s 0) #\+) (char= (char s 0) #\-)))
        (progn
          (if (char= (char s 0) #\-) (setq sign -1))
          (setq start 1)))
    (let ((whole (rontolisp::%scheme-scan-digits s start radix)))
      (let ((whole-end (cdr whole)))
        (cond ((and (= whole-end n) (> whole-end start)) (* sign (car whole)))
              ((and (> whole-end start) (< whole-end n)
                    (char= (char s whole-end) #\/))
               (let ((denominator
                      (rontolisp::%scheme-scan-digits s (+ whole-end 1) radix)))
                 (if (and (= (cdr denominator) n)
                          (> (cdr denominator) (+ whole-end 1))
                          (> (car denominator) 0))
                     (/ (* sign (car whole)) (car denominator))
                     rontolisp::%scheme-false)))
              ((= radix 10)
               (rontolisp::%scheme-decimal s
                (list sign start (car whole) whole-end)))
              (t rontolisp::%scheme-false))))))

(defun rontolisp::%scheme-decimal (s state)
  (let ((n (length s))
        (sign (car state))
        (start (car (cdr state)))
        (whole (car (cdr (cdr state))))
        (i (car (cdr (cdr (cdr state))))))
    (let ((mantissa whole) (scale 0) (digits (- i start)) (exponent 0) (ok t))
      (if (and (< i n) (char= (char s i) #\.))
          (let ((fraction (rontolisp::%scheme-scan-digits s (+ i 1) 10)))
            (setq scale (- (cdr fraction) (+ i 1)))
            (setq digits (+ digits scale))
            (setq mantissa (+ (* whole (expt 10 scale)) (car fraction)))
            (setq i (cdr fraction))))
      (if (and (< i n) (or (char= (char s i) #\e) (char= (char s i) #\E)))
          (let ((exponent-sign 1) (j (+ i 1)))
            (if (and (< j n) (or (char= (char s j) #\+) (char= (char s j) #\-)))
                (progn
                  (if (char= (char s j) #\-) (setq exponent-sign -1))
                  (setq j (+ j 1))))
            (let ((scanned (rontolisp::%scheme-scan-digits s j 10)))
              (if (= (cdr scanned) j) (setq ok nil))
              (setq exponent (* exponent-sign (car scanned)))
              (setq i (cdr scanned)))))
      (if (and ok (= i n) (> digits 0))
          ;; Negated AFTER the conversion, so "-0.0" keeps its sign.
          (let ((magnitude
                 (float (* mantissa (expt 10 (- exponent scale))) 1.0d0)))
            (if (< sign 0) (- magnitude) magnitude))
          rontolisp::%scheme-false))))

;; --- control ----------------------------------------------------------------------

;; apply as a first-class procedure: (a b (c d)) -> (a b c d), the argument list
;; (apply f a b '(c d)) spreads. #'apply itself is not a function value on the compile
;; path.
(defun rontolisp::%scheme-spread (arguments)
  (if (null (cdr arguments))
      (car arguments)
      (cons (car arguments) (rontolisp::%scheme-spread (cdr arguments)))))

;; Escape-only, one-shot: the continuation is a closure over a block, so calling it
;; after the call/cc returned is an error, and re-entry does not exist.
(defun rontolisp::%scheme-call/cc (receiver)
  (block rontolisp::%scheme-continuation
    (funcall receiver
             (lambda (&rest results)
               (return-from rontolisp::%scheme-continuation
                            (values-list results))))))

;; The exit half runs on every way out -- a normal return, an escaping continuation,
;; an error. There is no re-entry, so BEFORE runs exactly once.
(defun rontolisp::%scheme-dynamic-wind (before thunk after)
  (funcall before)
  (unwind-protect (funcall thunk) (funcall after)))

(defun rontolisp::%scheme-error-message (message irritants)
  (with-output-to-string (*standard-output*)
    (if (stringp message)
        (write-string message)
        (rontolisp::%scheme-print message t))
    (dolist (irritant irritants)
      (write-char #\Space)
      (rontolisp::%scheme-print irritant t))))
