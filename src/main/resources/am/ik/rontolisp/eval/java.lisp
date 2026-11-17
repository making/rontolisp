;; The run-time half of the EXPERIMENTAL front end for a subset of the Java language
;; (am.ik.rontolisp.java), written in Common Lisp so one definition runs on every
;; backend and no emitter learns a Java name: the interpreter loads it on the first
;; resolution of a rontolisp::%java- function, the compile path splices it when the
;; program references one (JavaLibrary.java) and the tree-shaker drops what stays
;; unreachable.
;;
;; Written here from the published specification; nothing is copied or translated from
;; a JDK (.kb/java-frontend-provenance.md).
;;
;; The value model these helpers share with the lowering (.kb/java-frontend.md): null
;; is NIL, a boolean T or NIL, an int and a long an integer kept in range by the
;; lowering, a char an integer, a double a double, a String the host's immutable string,
;; an array a simple vector, an object a structure. A boxed int or double is the number,
;; a boxed long the cons (number . :long) -- so an Integer and a Long of one number are
;; two values, and equal tells them apart as equals does --, a boxed char a character,
;; a boxed true T and a boxed false :false, so that no box is null. No other value of
;; a program is a cons. A StringBuilder is an adjustable character vector, a lambda a
;; function, an Object made by `new Object()` an empty vector with a fill pointer. Every
;; other class of the library is Java source lowered with the program (JavaIntrinsics).
;;
;; Three rules every definition here keeps:
;;  - What the lowering calls is a DEFUN: the interpreter loads this file when it first
;;    resolves one of its functions.
;;  - A string handed to the program is the host's IMMUTABLE string: joined by
;;    %string-concat, sliced by %substring, anything built another way wrapped in
;;    %normalize-string (.kb/string-write-runtime.md).
;;  - An `error`'s control is a literal string.

;; --- exceptions -----------------------------------------------------------------

;; One condition type holds every thrown object. DESCRIBE is the program's function
;; answering the object's toString(), which the report of an uncaught one asks.
(define-condition rontolisp::%java-throwable (error)
  ((object :initarg :object :reader rontolisp::%java-throwable-object)
   (describe :initarg :describe :reader rontolisp::%java-throwable-describe))
  (:report
   (lambda (condition stream)
     (write-string (funcall (rontolisp::%java-throwable-describe condition)
                            (rontolisp::%java-throwable-object condition))
                   stream))))

(defun rontolisp::%java-throw (object describe)
  (if (null object)
      (error "NullPointerException: throw of null")
      (error 'rontolisp::%java-throwable :object object :describe describe)))

;; The object a condition holds, nil for a condition that is no thrown object.
(defun rontolisp::%java-thrown (condition)
  (if (typep condition 'rontolisp::%java-throwable)
      (rontolisp::%java-throwable-object condition)
      nil))

(defun rontolisp::%java-division-by-zero-p (condition)
  (if (typep condition 'division-by-zero) t nil))

(defun rontolisp::%java-condition-text (condition)
  (%normalize-string (princ-to-string condition)))

(defun rontolisp::%java-no-receiver (object signature)
  (if (null object)
      (error "NullPointerException: ~A called on null" signature)
      (error "~A has no implementation for its receiver" signature)))

;; --- text -------------------------------------------------------------------------

;; The host prints a double with the shortest digits that read back, in the layout
;; the language specifies but for the exponent's letter.
(defun rontolisp::%java-double-string (d)
  (let ((text (%princ-piece d)))
    (let ((e (position #\e text)))
      (if e
          (%string-concat (%string-concat (%substring text 0 e) "E")
                          (%substring text (+ e 1) (length text)))
          text))))

(defun rontolisp::%java-char-string (code)
  (%normalize-string (string (code-char code))))

(defun rontolisp::%java-hex (n) (%normalize-string (format nil "~(~X~)" n)))

(defvar rontolisp::%java-identities nil)

(defvar rontolisp::%java-identity-count 0)

;; An object's identity hash: a number of its own, kept beside it from the first time
;; it is asked.
(defun rontolisp::%java-identity-hash (object)
  (if (null rontolisp::%java-identities)
      (setq rontolisp::%java-identities (make-hash-table :test 'eq)))
  (let ((known (gethash object rontolisp::%java-identities)))
    (if known
        known
        (progn
          (setq rontolisp::%java-identity-count
                (logand (+ (* rontolisp::%java-identity-count 1103515245) 12345)
                        2147483647))
          (setf (gethash object rontolisp::%java-identities)
                rontolisp::%java-identity-count)))))

;; The name a structure of the program was declared with: the lowering escapes a name
;; holding no lowercase letter behind "j%".
(defun rontolisp::%java-structure-name (object)
  (let ((name (symbol-name (type-of object))))
    (if (and (> (length name) 2) (char= (char name 0) #\j)
             (char= (char name 1) #\%))
        (%substring name 2 (length name))
        name)))

(defun rontolisp::%java-identity-string (name object)
  (%string-concat (%string-concat name "@")
   (rontolisp::%java-hex (rontolisp::%java-identity-hash object))))

;; Object.toString() of a value no class of the program answers for.
(defun rontolisp::%java-to-string (x)
  (cond ((null x) "null")
        ((stringp x) (if (simple-string-p x) x (rontolisp::%java-sb-string x)))
        ((integerp x) (%decimal-piece x))
        ((floatp x) (rontolisp::%java-double-string x))
        ((consp x) (%decimal-piece (car x)))
        ((eq x t) "true")
        ((eq x :false) "false")
        ((characterp x) (%normalize-string (string x)))
        ((functionp x) (rontolisp::%java-identity-string "Lambda" x))
        ((vectorp x)
         (if (array-has-fill-pointer-p x)
             (rontolisp::%java-identity-string "java.lang.Object" x)
             (rontolisp::%java-identity-string "[" x)))
        (t (rontolisp::%java-identity-string (rontolisp::%java-structure-name x)
                                             x))))

;; The class name of a value no class of the program answers for.
(defun rontolisp::%java-class-name (x qualified)
  (cond ((null x) (error "NullPointerException: getClass() called on null"))
   ((stringp x)
    (if (simple-string-p x)
        (if qualified "java.lang.String" "String")
        (if qualified "java.lang.StringBuilder" "StringBuilder")))
   ((integerp x) (if qualified "java.lang.Integer" "Integer"))
   ((consp x) (if qualified "java.lang.Long" "Long"))
   ((floatp x) (if qualified "java.lang.Double" "Double"))
   ((or (eq x t) (eq x :false)) (if qualified "java.lang.Boolean" "Boolean"))
   ((characterp x) (if qualified "java.lang.Character" "Character"))
   ((and (vectorp x) (array-has-fill-pointer-p x))
    (if qualified "java.lang.Object" "Object"))
   (t (error
       "getClass() of this value is not supported by the experimental Java subset yet"))))

;; --- Object.equals and hashCode ---------------------------------------------------

;; Long.hashCode as the API specifies it: (int) (value ^ (value >>> 32)).

(defun rontolisp::%java-string-equals (a b)
  (if (and (stringp b) (simple-string-p b)) (equal a b) nil))

;; Object.equals of a value no class of the program answers for: a string and a box
;; by value, anything else by identity.
(defun rontolisp::%java-equals (a b)
  (cond ((null a) (error "NullPointerException: equals(Object) called on null"))
        ((stringp a)
         (if (simple-string-p a) (rontolisp::%java-string-equals a b) (eq a b)))
        ((integerp a) (if (integerp b) (= a b) nil))
        ((consp a) (equal a b))
        ((floatp a)
         (if (floatp b)
             (= (rontolisp::%java-double-bits a)
                (rontolisp::%java-double-bits b))
             nil))
        ((characterp a) (if (characterp b) (char= a b) nil))
        (t (eq a b))))

;; String.hashCode as the API specifies it, s[0]*31^(n-1) + .. + s[n-1] in int
;; arithmetic, over the UTF-16 form: a character outside the BMP is its two surrogates.
(defun rontolisp::%java-string-hash (s)
  (let ((h 0) (i 0) (n (length s)))
    (while (< i n)
      (let ((code (char-code (char s i))))
        (if (< code 65536)
            (setq h (logand (+ (* 31 h) code) 4294967295))
            (let ((v (- code 65536)))
              (setq h (logand (+ (* 31 h) 55296 (ash v -10)) 4294967295))
              (setq h (logand (+ (* 31 h) 56320 (logand v 1023)) 4294967295)))))
      (setq i (+ i 1)))
    (if (>= h 2147483648) (- h 4294967296) h)))

(defun rontolisp::%java-long-hash (x)
  (let ((bits (logand x 18446744073709551615)))
    (let ((h (logand (logxor bits (ash bits -32)) 4294967295)))
      (if (>= h 2147483648) (- h 4294967296) h))))

(defun rontolisp::%java-hash-code (x)
  (cond ((null x) (error "NullPointerException: hashCode() called on null"))
        ((stringp x)
         (if (simple-string-p x)
             (rontolisp::%java-string-hash x)
             (rontolisp::%java-identity-hash x)))
        ((integerp x) x)
        ((consp x) (rontolisp::%java-long-hash (car x)))
        ((eq x t) 1231)
        ((eq x :false) 1237)
        ((characterp x) (char-code x))
        ((floatp x) (rontolisp::%java-double-hash x))
        (t (rontolisp::%java-identity-hash x))))

;; --- numbers ------------------------------------------------------------------------

(defun rontolisp::%java-boolean-p (x) (if (or (eq x t) (eq x :false)) t nil))

;; What reading a box that is null as its primitive raises.
(defun rontolisp::%java-null-unbox ()
  (error "NullPointerException: a box that is null was read as its primitive"))

(defun rontolisp::%java-unbox-boolean (x)
  (cond ((eq x t) t)
   ((eq x :false) nil)
   (t (error
       "NullPointerException: a Boolean that is null was read as a boolean"))))

;; (int) and (long) of a double: toward zero, and the nearest value of the type for
;; one outside it.
(defun rontolisp::%java-double-to-int (d)
  (cond ((>= d 2147483647d0) 2147483647)
        ((<= d -2147483648d0) -2147483648)
        ((= d d) (truncate d))
        (t 0)))

(defun rontolisp::%java-double-to-long (d)
  (cond ((>= d 9223372036854775807d0) 9223372036854775807)
        ((<= d -9223372036854775808d0) -9223372036854775808)
        ((= d d) (truncate d))
        (t 0)))

;; Double.compare as the API specifies it: a NaN is equal to itself and above every
;; other value, and 0.0 is above -0.0, which the reciprocals tell apart.
(defun rontolisp::%java-double-compare (a b)
  (cond ((< a b) -1)
        ((> a b) 1)
        ((= a b)
         (if (= a 0d0)
             (let ((x (/ 1d0 a)) (y (/ 1d0 b)))
               (cond ((< x y) -1) ((> x y) 1) (t 0)))
             0))
        ((= a a) -1)
        ((= b b) 1)
        (t 0)))

;; Integer.compare and Long.compare: -1, 0 or 1.
(defun rontolisp::%java-compare (a b) (cond ((< a b) -1) ((> a b) 1) (t 0)))

;; --- arrays, switch -----------------------------------------------------------------

;; new T[n][m]..: every level but the last holds arrays. KIND is 1 for an int leaf,
;; 2 for a double one, 0 for any other, which starts as INITIAL.
(defun rontolisp::%java-new-array (lengths kind initial)
  (let ((n (car lengths)))
    (if (null (cdr lengths))
        (cond ((eql kind 1)
               (make-array n
                           :element-type '(signed-byte 32)
                           :initial-element 0))
              ((eql kind 2)
               (make-array n :element-type 'double-float :initial-element 0d0))
              (t (make-array n :initial-element initial)))
        (let ((rows (make-array n :initial-element nil)) (i 0))
          (while (< i n)
            (setf (aref rows i)
                  (rontolisp::%java-new-array (cdr lengths) kind initial))
            (setq i (+ i 1)))
          rows))))

(defun rontolisp::%java-null-switch ()
  (error "NullPointerException: a switch over null"))

;; A switch that covers its selector's type by its cases reaches this only when the
;; lowering is wrong.
(defun rontolisp::%java-no-match ()
  (error "MatchException: no case of the switch takes the value"))

;; --- String, StringBuilder ----------------------------------------------------------

;; The UTF-16 unit a string's element starts with: the element itself inside the BMP,
;; its high surrogate outside it.
(defun rontolisp::%java-leading-unit (code)
  (if (< code 65536) code (+ 55296 (ash (- code 65536) -10))))

;; String.compareTo as the API specifies it: the difference of the first two UTF-16
;; units that differ, else of the lengths.
(defun rontolisp::%java-string-compare (a b)
  (let ((i 0) (n (min (length a) (length b))) (found nil))
    (while (and (< i n) (null found))
      (let ((x (char-code (char a i))) (y (char-code (char b i))))
        (if (= x y)
            (setq i (+ i 1))
            (let ((p (rontolisp::%java-leading-unit x))
                  (q (rontolisp::%java-leading-unit y)))
              (setq found
               (if (= p q) (- (logand x 1023) (logand y 1023)) (- p q)))))))
    (if found found (- (length a) (length b)))))

(defun rontolisp::%java-sb-p (x)
  (if (and (stringp x) (not (simple-string-p x))) t nil))

(defun rontolisp::%java-substring (s start end)
  (let ((end (if end end (length s))))
    (if (or (< start 0) (> start end) (> end (length s)))
        (error "StringIndexOutOfBoundsException: begin ~D, end ~D, length ~D"
               start end (length s)))
    (%substring s start end)))

(defun rontolisp::%java-sb-append (builder text)
  (let ((i 0) (n (length text)))
    (while (< i n)
      (vector-push-extend (char text i) builder)
      (setq i (+ i 1))))
  builder)

(defun rontolisp::%java-sb-append-char (builder code)
  (vector-push-extend (code-char code) builder)
  builder)

(defun rontolisp::%java-sb-string (builder)
  (%normalize-string (subseq builder 0 (length builder))))

(defun rontolisp::%java-sb-char (builder index)
  (if (or (< index 0) (>= index (length builder)))
      (error "StringIndexOutOfBoundsException: index ~D, length ~D" index
             (length builder)))
  (char-code (aref builder index)))

;; --- numbers: bits, parsing, text ------------------------------------------------------

(defun rontolisp::%java-number-p (x)
  (if (or (integerp x) (floatp x) (consp x)) t nil))

(defun rontolisp::%java-number-int (x)
  (cond ((integerp x) x)
        ((consp x) (- (logand (+ (car x) 2147483648) 4294967295) 2147483648))
        (t (rontolisp::%java-double-to-int x))))

(defun rontolisp::%java-number-long (x)
  (cond ((integerp x) x)
        ((consp x) (car x))
        (t (rontolisp::%java-double-to-long x))))

(defun rontolisp::%java-number-double (x)
  (cond ((integerp x) (float x 1d0)) ((consp x) (float (car x) 1d0)) (t x)))

(defun rontolisp::%java-nan-p (d) (if (= d d) nil t))

(defun rontolisp::%java-infinite-p (d)
  (if (and (= d d)
           (or (> d 1.7976931348623157d308) (< d -1.7976931348623157d308)))
      t
      nil))

(defun rontolisp::%java-infinity (negative)
  (if negative (/ -1d0 0d0) (/ 1d0 0d0)))

(defun rontolisp::%java-nan ()
  (let ((infinity (/ 1d0 0d0))) (- infinity infinity)))

;; Double.doubleToLongBits as the API specifies it: the sign, the eleven bits of the
;; exponent and the fifty-two of the fraction; one NaN.
(defun rontolisp::%java-double-bits (d)
  (cond ((/= d d) 9221120237041090560)
        ((> d 1.7976931348623157d308) 9218868437227405312)
        ((< d -1.7976931348623157d308) -4503599627370496)
        ((= d 0d0) (if (< (/ 1d0 d) 0d0) -9223372036854775808 0))
        (t
         (multiple-value-bind (mantissa exponent sign) (integer-decode-float d)
           (let ((digits (integer-length mantissa)))
             ;; a normal number's mantissa has fifty-three bits, a subnormal's fewer
             (let ((biased (+ exponent 1075 (- digits 53)))
                   (fraction (ash mantissa (- 53 digits))))
               (let ((bits
                      (if (<= biased 0)
                          (ash fraction (- biased 1))
                          (+ (ash biased 52) (- fraction 4503599627370496)))))
                 (if (< sign 0) (- bits 9223372036854775808) bits))))))))

;; Double.hashCode as the API specifies it: the bits folded as a long's are.
(defun rontolisp::%java-double-hash (d)
  (rontolisp::%java-long-hash (rontolisp::%java-double-bits d)))

;; Math.max and Math.min of two doubles: a NaN wins, and 0.0 is above -0.0.
(defun rontolisp::%java-double-max (a b)
  (cond ((/= a a) a)
        ((/= b b) b)
        ((> (rontolisp::%java-double-compare a b) 0) a)
        (t b)))

(defun rontolisp::%java-double-min (a b)
  (cond ((/= a a) a)
        ((/= b b) b)
        ((< (rontolisp::%java-double-compare a b) 0) a)
        (t b)))

;; Math.round: the nearest long, a tie toward positive infinity.
(defun rontolisp::%java-round (d)
  (cond ((/= d d) 0)
   ((>= d 9223372036854775807d0) 9223372036854775807)
   ((<= d -9223372036854775808d0) -9223372036854775808)
   (t (let ((below (floor d))) (if (>= (- d below) 0.5d0) (+ below 1) below)))))

(defun rontolisp::%java-digits (n radix)
  (%normalize-string
   (string-downcase (write-to-string n :base radix :radix nil))))

;; Integer.toString(n, radix) and Long.toString(n, radix).
(defun rontolisp::%java-radix-string (n radix)
  (rontolisp::%java-digits n (if (and (>= radix 2) (<= radix 36)) radix 10)))

;; toBinaryString, toHexString, toOctalString: the two's complement of BITS bits.
(defun rontolisp::%java-unsigned-string (n radix bits)
  (rontolisp::%java-digits (logand n (- (ash 1 bits) 1)) radix))

(defun rontolisp::%java-parse-boolean (s)
  (if (and s (string-equal s "true")) t nil))

;; The value a text is as a double, or nil when it is none: an optional sign, digits
;; with at most one point, an optional exponent; or Infinity or NaN. Exact arithmetic
;; over the digits, then the nearest double.
(defun rontolisp::%java-read-double (text)
  (let ((s (rontolisp::%java-strip text t t)))
    (let ((n (length s))
          (i 0)
          (negative nil)
          (mantissa 0)
          (scale 0)
          (digits 0)
          (seen-point nil)
          (exponent 0)
          (bad nil))
      (if (and (< i n) (or (char= (char s i) #\-) (char= (char s i) #\+)))
          (progn
            (setq negative (char= (char s i) #\-))
            (setq i (+ i 1))))
      (cond ((equal (%substring s i n) "Infinity")
             (rontolisp::%java-infinity negative))
            ((equal (%substring s i n) "NaN") (rontolisp::%java-nan))
            (t
             (while (and (< i n)
                         (or (digit-char-p (char s i))
                             (and (char= (char s i) #\.) (null seen-point))))
               (if (char= (char s i) #\.)
                   (setq seen-point t)
                   (progn
                     (setq mantissa
                           (+ (* mantissa 10) (digit-char-p (char s i))))
                     (setq digits (+ digits 1))
                     (if seen-point (setq scale (+ scale 1)))))
               (setq i (+ i 1)))
             (if (= digits 0) (setq bad t))
             (if (and (< i n)
                      (or (char= (char s i) #\e) (char= (char s i) #\E)))
                 (let ((minus nil) (count 0))
                   (setq i (+ i 1))
                   (if (and (< i n)
                            (or (char= (char s i) #\-) (char= (char s i) #\+)))
                       (progn
                         (setq minus (char= (char s i) #\-))
                         (setq i (+ i 1))))
                   (while (and (< i n) (digit-char-p (char s i)))
                     (if (< exponent 100000)
                         (setq exponent
                               (+ (* exponent 10) (digit-char-p (char s i)))))
                     (setq count (+ count 1))
                     (setq i (+ i 1)))
                   (if (= count 0) (setq bad t))
                   (if minus (setq exponent (- exponent)))))
             (if (and (< i n)
                      (or (char= (char s i) #\d) (char= (char s i) #\D)
                          (char= (char s i) #\f) (char= (char s i) #\F)))
                 (setq i (+ i 1)))
             (if (or bad (< i n))
                 nil
                 (let ((power (- exponent scale)))
                   (let ((value
                          (cond ((= mantissa 0) 0d0)
                                ((> (+ power digits) 400)
                                 (rontolisp::%java-infinity nil))
                                ((< (+ power digits) -400) 0d0)
                                ((>= power 0)
                                 (rontolisp::%java-nearest-double
                                  (* mantissa (expt 10 power)) 1))
                                (t (rontolisp::%java-nearest-double mantissa
                                    (expt 10 (- power)))))))
                     (if negative (- value) value)))))))))

;; The double nearest to a positive ratio of two integers, a tie to the even one:
;; fifty-three bits of the quotient and what is left of it.
(defun rontolisp::%java-nearest-double (numerator denominator)
  (let ((shift (- (integer-length numerator) (integer-length denominator) 54)))
    (let ((scaled
           (if (>= shift 0)
               (floor numerator (ash denominator shift))
               (floor (ash numerator (- shift)) denominator))))
      ;; SCALED has 54 or 55 bits; the value is SCALED * 2^SHIFT and something below
      (let ((exact
             (if (>= shift 0)
                 (= (* scaled (ash denominator shift)) numerator)
                 (= (* scaled denominator) (ash numerator (- shift))))))
        (let ((extra (- (integer-length scaled) 53)))
          ;; a subnormal keeps fewer bits: its last place is 2^-1074
          (if (< (+ shift extra) -1074) (setq extra (- -1074 shift)))
          (if (> extra 60)
              0d0
              (let ((kept (ash scaled (- extra)))
                    (rest (logand scaled (- (ash 1 extra) 1)))
                    (half (ash 1 (- extra 1))))
                (if (or (> rest half)
                        (and (= rest half) (or (null exact) (oddp kept))))
                    (setq kept (+ kept 1)))
                (if (> (+ (integer-length kept) shift extra) 1024)
                    (rontolisp::%java-infinity nil)
                    (scale-float (float kept 1d0) (+ shift extra))))))))))

;; --- characters ------------------------------------------------------------------------

(defun rontolisp::%java-char-digit-p (code)
  (if (digit-char-p (code-char code)) t nil))

(defun rontolisp::%java-char-letter-p (code)
  (if (alpha-char-p (code-char code)) t nil))

(defun rontolisp::%java-char-alphabetic-p (code)
  (if (alpha-char-p (code-char code)) t nil))

(defun rontolisp::%java-char-letter-or-digit-p (code)
  (if (alphanumericp (code-char code)) t nil))

;; Character.isWhitespace: the separators but the no-break spaces, and the controls
;; from tab to carriage return and from file to unit separator.
(defun rontolisp::%java-char-whitespace-p (code)
  (if (or (= code 32) (and (>= code 9) (<= code 13))
          (and (>= code 28) (<= code 31)) (= code 5760)
          (and (>= code 8192) (<= code 8202) (/= code 8199)) (= code 8232)
          (= code 8233) (= code 8287) (= code 12288))
      t
      nil))

(defun rontolisp::%java-char-space-char-p (code)
  (if (or (= code 32) (= code 160) (= code 5760)
          (and (>= code 8192) (<= code 8202)) (= code 8232) (= code 8233)
          (= code 8239) (= code 8287) (= code 12288))
      t
      nil))

(defun rontolisp::%java-char-upper-case-p (code)
  (if (upper-case-p (code-char code)) t nil))

(defun rontolisp::%java-char-lower-case-p (code)
  (if (lower-case-p (code-char code)) t nil))

(defun rontolisp::%java-char-upcase (code)
  (char-code (char-upcase (code-char code))))

(defun rontolisp::%java-char-downcase (code)
  (char-code (char-downcase (code-char code))))

(defun rontolisp::%java-digit (code radix)
  (let ((value
         (digit-char-p (code-char code)
                       (if (and (>= radix 2) (<= radix 36)) radix 10))))
    (if (and value (>= radix 2) (<= radix 36)) value -1)))

(defun rontolisp::%java-numeric-value (code) (rontolisp::%java-digit code 36))

(defun rontolisp::%java-for-digit (digit radix)
  (if (and (>= radix 2) (<= radix 36) (>= digit 0) (< digit radix))
      (char-code (char-downcase (digit-char digit radix)))
      0))

;; --- String --------------------------------------------------------------------------

(defun rontolisp::%java-blank-p (s)
  (let ((i 0) (n (length s)) (blank t))
    (while (and blank (< i n))
      (if (rontolisp::%java-char-whitespace-p (char-code (char s i)))
          (setq i (+ i 1))
          (setq blank nil)))
    blank))

(defun rontolisp::%java-string-equals-ignore-case (a b)
  (if (and b (string-equal a b)) t nil))

(defun rontolisp::%java-string-compare-ignore-case (a b)
  (rontolisp::%java-string-compare
   (%string-downcase-piece (%string-upcase-piece a))
   (%string-downcase-piece (%string-upcase-piece b))))

(defun rontolisp::%java-index-of (s text from)
  (let ((start (cond ((< from 0) 0) ((> from (length s)) (length s)) (t from))))
    (let ((found (search text s :start2 start))) (if found found -1))))

(defun rontolisp::%java-index-of-char (s code from)
  (let ((start (cond ((< from 0) 0) ((> from (length s)) (length s)) (t from))))
    (let ((found (position (code-char code) s :start start)))
      (if found found -1))))

(defun rontolisp::%java-last-index-of (s text)
  (let ((found (search text s :from-end t))) (if found found -1)))

(defun rontolisp::%java-last-index-of-char (s code)
  (let ((found (position (code-char code) s :from-end t))) (if found found -1)))

(defun rontolisp::%java-starts-with (s prefix offset)
  (let ((n (length prefix)))
    (if (and (>= offset 0) (<= (+ offset n) (length s))
             (equal (%substring s offset (+ offset n)) prefix))
        t
        nil)))

(defun rontolisp::%java-ends-with (s suffix)
  (rontolisp::%java-starts-with s suffix (- (length s) (length suffix))))

;; String.trim: the characters up to the space are cut from both ends.
(defun rontolisp::%java-trim (s)
  (let ((start 0) (end (length s)))
    (while (and (< start end) (<= (char-code (char s start)) 32))
      (setq start (+ start 1)))
    (while (and (< start end) (<= (char-code (char s (- end 1))) 32))
      (setq end (- end 1)))
    (if (and (= start 0) (= end (length s))) s (%substring s start end))))

(defun rontolisp::%java-strip (s leading trailing)
  (let ((start 0) (end (length s)))
    (if leading
        (while (and (< start end)
                (rontolisp::%java-char-whitespace-p (char-code (char s start))))
          (setq start (+ start 1))))
    (if trailing
        (while (and (< start end)
                    (rontolisp::%java-char-whitespace-p
                     (char-code (char s (- end 1)))))
          (setq end (- end 1))))
    (if (and (= start 0) (= end (length s))) s (%substring s start end))))

(defun rontolisp::%java-repeat (s count)
  (if (< count 0)
      (error "IllegalArgumentException: count is negative: ~D" count))
  (let ((builder (rontolisp::%java-sb-new)) (i 0))
    (while (< i count)
      (rontolisp::%java-sb-append builder s)
      (setq i (+ i 1)))
    (rontolisp::%java-sb-string builder)))

(defun rontolisp::%java-replace-char (s from to)
  (if (position (code-char from) s)
      (%normalize-string (substitute (code-char to) (code-char from) s))
      s))

;; String.replace of a text by a text: every occurrence, left to right; an empty
;; target stands before each character and at the end.
(defun rontolisp::%java-replace-text (s target replacement)
  (let ((builder (rontolisp::%java-sb-new))
        (i 0)
        (n (length s))
        (m (length target)))
    (if (= m 0)
        (progn
          (while (< i n)
            (rontolisp::%java-sb-append builder replacement)
            (vector-push-extend (char s i) builder)
            (setq i (+ i 1)))
          (rontolisp::%java-sb-append builder replacement))
        (let ((found (search target s)))
          (while found
            (rontolisp::%java-sb-append builder (%substring s i found))
            (rontolisp::%java-sb-append builder replacement)
            (setq i (+ found m))
            (setq found (search target s :start2 i)))
          (rontolisp::%java-sb-append builder (%substring s i n))))
    (rontolisp::%java-sb-string builder)))

(defun rontolisp::%java-to-chars (s)
  (let ((codes (make-array (length s) :initial-element 0)) (i 0) (n (length s)))
    (while (< i n)
      (%aset codes i (char-code (char s i)))
      (setq i (+ i 1)))
    codes))

;; String.valueOf(char[]) and new String(char[], offset, count).
(defun rontolisp::%java-chars-string (codes offset count)
  (if (null codes)
      (error "NullPointerException: a char[] that is null was read as text"))
  (let ((builder (rontolisp::%java-sb-new))
        (i offset)
        (end (if count (+ offset count) (length codes))))
    (while (< i end)
      (vector-push-extend (code-char (aref codes i)) builder)
      (setq i (+ i 1)))
    (rontolisp::%java-sb-string builder)))

;; What a regular expression String.split takes can be: a text with no operator, each
;; character possibly behind a backslash; or one of the character classes \s \d \w and
;; a set in brackets (characters, ranges, those classes), alone or repeated by +. Answers (kind . data), kind :text with
;; the text, or :class with (members . repeated), members a string of characters and of
;; the letters after a backslash; nil for anything else.
(defun rontolisp::%java-split-pattern (regex)
  (let ((n (length regex))
        (i 0)
        (text (rontolisp::%java-sb-new))
        (plain t)
        (members nil)
        (repeated nil))
    (cond ((and (>= n 2) (char= (char regex 0) #\\)
                (position (char regex 1) "sdw")
                (or (= n 2) (and (= n 3) (char= (char regex 2) #\+))))
           (cons :class (cons (%substring regex 0 2) (= n 3))))
          ((and (>= n 3) (char= (char regex 0) #\[)
                (not (char= (char regex 1) #\^))
                (let ((close (position #\] regex :start 2)))
                  (and close
                       (or (= close (- n 1))
                           (and (= close (- n 2))
                                (char= (char regex (- n 1)) #\+)))
                       (progn
                         (setq members (%substring regex 1 close))
                         (setq repeated (= close (- n 2)))
                         t))) (null (position #\[ members))
                (null (position #\& members)))
           (cons :class (cons members repeated)))
          (t
           (while (and plain (< i n))
             (let ((c (char regex i)))
               (cond ((char= c #\\)
                      (if (and (< (+ i 1) n)
                               (not (alphanumericp (char regex (+ i 1)))))
                          (progn
                            (vector-push-extend (char regex (+ i 1)) text)
                            (setq i (+ i 2)))
                          (setq plain nil)))
                     ((position c ".[]{}()*+?^$|") (setq plain nil))
                     (t
                      (vector-push-extend c text)
                      (setq i (+ i 1))))))
           (if plain (cons :text (rontolisp::%java-sb-string text)) nil)))))

;; Whether a character is a member of a class as %java-split-pattern answers it.
(defun rontolisp::%java-class-member-p (members c)
  (let ((i 0) (n (length members)) (found nil))
    (while (and (null found) (< i n))
      (let ((m (char members i)))
        (if (and (char= m #\\) (< (+ i 1) n))
            (let ((kind (char members (+ i 1))))
              (setq found
                    (cond ((char= kind #\s)
                           (let ((code (char-code c)))
                             (or (= code 32) (and (>= code 9) (<= code 13)))))
                          ((char= kind #\d) (and (char>= c #\0) (char<= c #\9)))
                          ((char= kind #\w)
                           (or (and (char>= c #\0) (char<= c #\9))
                               (and (char>= c #\a) (char<= c #\z))
                               (and (char>= c #\A) (char<= c #\Z))
                               (char= c #\_)))
                          (t (char= kind c))))
              (setq i (+ i 2)))
            (if (and (< (+ i 2) n) (char= (char members (+ i 1)) #\-))
                ;; a range, both ends in it
                (progn
                  (setq found
                        (and (char>= c m) (char<= c (char members (+ i 2)))))
                  (setq i (+ i 3)))
                (progn
                  (setq found (char= m c))
                  (setq i (+ i 1)))))))
    found))

;; String.split: the texts between the matches, a leading empty one kept only before
;; a match that is not empty, the trailing empty ones dropped unless LIMIT is negative;
;; a positive LIMIT is the most pieces made.
(defun rontolisp::%java-split (s regex limit)
  (let ((pattern (rontolisp::%java-split-pattern regex)))
    (if (null pattern)
        (error "String.split by the regular expression ~S is not supported by the experimental Java subset yet"
               regex))
    (let ((pieces (make-array 4 :adjustable t :fill-pointer 0))
          (start 0)
          (i 0)
          (n (length s))
          (text (eq (car pattern) :text)))
      (if (and text (= (length (cdr pattern)) 0))
          ;; an empty expression splits between the characters
          (progn
            (while (and (< i n)
                        (or (<= limit 0) (< (+ (length pieces) 1) limit)))
              (vector-push-extend (%substring s i (+ i 1)) pieces)
              (setq i (+ i 1)))
            (if (< i n) (vector-push-extend (%substring s i n) pieces))
            (if (= n 0) (vector-push-extend "" pieces)))
          (progn
            (while (and (< i n)
                        (or (<= limit 0) (< (+ (length pieces) 1) limit)))
              (let ((end
                     (if text
                         (if (rontolisp::%java-starts-with s (cdr pattern) i)
                             (+ i (length (cdr pattern)))
                             nil)
                         (if (rontolisp::%java-class-member-p
                              (car (cdr pattern)) (char s i))
                             (let ((j (+ i 1)))
                               (if (cdr (cdr pattern))
                                   (while (and (< j n)
                                               (rontolisp::%java-class-member-p
                                                (car (cdr pattern)) (char s j)))
                                     (setq j (+ j 1))))
                               j)
                             nil))))
                (if end
                    (progn
                      (if (or (> i 0) (> (length pieces) 0))
                          (vector-push-extend (%substring s start i) pieces)
                          (if (= i 0)
                              ;; a match at the very start leaves an empty first piece
                              (vector-push-extend "" pieces)))
                      (setq start end)
                      (setq i end))
                    (setq i (+ i 1)))))
            (vector-push-extend (%substring s start n) pieces)))
      (if (= limit 0)
          (while (and (> (length pieces) 1)
                      (= (length (aref pieces (- (length pieces) 1))) 0))
            (vector-pop pieces)))
      (let ((result (make-array (length pieces) :initial-element nil)) (k 0))
        (while (< k (length pieces))
          (%aset result k (aref pieces k))
          (setq k (+ k 1)))
        result))))

;; String.join over the elements of an array, each through TO-STRING.
(defun rontolisp::%java-join (delimiter items to-string)
  (let ((builder (rontolisp::%java-sb-new)) (i 0) (n (length items)))
    (while (< i n)
      (if (> i 0) (rontolisp::%java-sb-append builder delimiter))
      (rontolisp::%java-sb-append builder (funcall to-string (aref items i)))
      (setq i (+ i 1)))
    (rontolisp::%java-sb-string builder)))

;; String.intern: the one object a text is. The constants of a program that compares
;; strings by reference are made through it.
(defvar rontolisp::%java-interned nil)

(defun rontolisp::%java-intern (s)
  (if (null rontolisp::%java-interned)
      (setq rontolisp::%java-interned (make-hash-table :test 'equal)))
  (let ((known (gethash s rontolisp::%java-interned)))
    (if known known (setf (gethash s rontolisp::%java-interned) s))))

;; new String(s): another object of the same text.
(defun rontolisp::%java-string-copy (s)
  (rontolisp::%java-sb-string
   (rontolisp::%java-sb-append (rontolisp::%java-sb-new) s)))

;; --- StringBuilder -------------------------------------------------------------------

(defun rontolisp::%java-sb-new ()
  (make-array 16 :element-type 'character :adjustable t :fill-pointer 0))

(defun rontolisp::%java-sb-check (builder start end)
  (if (or (< start 0) (> start end) (> end (length builder)))
      (error "StringIndexOutOfBoundsException: start ~D, end ~D, length ~D"
             start end (length builder))))

(defun rontolisp::%java-sb-substring (builder start end)
  (let ((end (if end end (length builder))))
    (rontolisp::%java-sb-check builder start end)
    (%normalize-string (subseq builder start end))))

;; Makes room for COUNT characters at INDEX, moving what is after it.
(defun rontolisp::%java-sb-open (builder index count)
  (let ((n (length builder)) (k 0))
    (while (< k count)
      (vector-push-extend #\Space builder)
      (setq k (+ k 1)))
    (let ((i (- n 1)))
      (while (>= i index)
        (setf (aref builder (+ i count)) (aref builder i))
        (setq i (- i 1))))))

(defun rontolisp::%java-sb-insert (builder index text)
  (rontolisp::%java-sb-check builder index (length builder))
  (rontolisp::%java-sb-open builder index (length text))
  (let ((i 0) (n (length text)))
    (while (< i n)
      (setf (aref builder (+ index i)) (char text i))
      (setq i (+ i 1))))
  builder)

(defun rontolisp::%java-sb-delete (builder start end)
  (let ((end (if (> end (length builder)) (length builder) end)))
    (rontolisp::%java-sb-check builder start end)
    (let ((i end) (n (length builder)) (count (- end start)))
      (while (< i n)
        (setf (aref builder (- i count)) (aref builder i))
        (setq i (+ i 1)))
      (setf (fill-pointer builder) (- n count))))
  builder)

(defun rontolisp::%java-sb-delete-char (builder index)
  (if (or (< index 0) (>= index (length builder)))
      (error "StringIndexOutOfBoundsException: index ~D, length ~D" index
             (length builder)))
  (rontolisp::%java-sb-delete builder index (+ index 1)))

(defun rontolisp::%java-sb-replace (builder start end text)
  (rontolisp::%java-sb-delete builder start end)
  (rontolisp::%java-sb-insert builder start text))

(defun rontolisp::%java-sb-reverse (builder)
  (let ((i 0) (j (- (length builder) 1)))
    (while (< i j)
      (let ((c (aref builder i)))
        (setf (aref builder i) (aref builder j))
        (setf (aref builder j) c))
      (setq i (+ i 1))
      (setq j (- j 1))))
  builder)

(defun rontolisp::%java-sb-set-length (builder length)
  (if (< length 0) (error "StringIndexOutOfBoundsException: length ~D" length))
  (while (< (length builder) length) (vector-push-extend (code-char 0) builder))
  (setf (fill-pointer builder) length)
  nil)

(defun rontolisp::%java-sb-set-char (builder index code)
  (if (or (< index 0) (>= index (length builder)))
      (error "StringIndexOutOfBoundsException: index ~D, length ~D" index
             (length builder)))
  (setf (aref builder index) (code-char code))
  nil)

(defun rontolisp::%java-sb-index-of (builder text)
  (let ((found (search text builder))) (if found found -1)))

;; --- System, IO ------------------------------------------------------------------------

;; System.exit: the output written so far, then the host's exit with the low byte of
;; the status, as a POSIX host sees it.
(defun rontolisp::%java-exit (status)
  (finish-output)
  (finish-output *error-output*)
  (%host-exit (logand status 255)))

(defun rontolisp::%java-identity-hash-code (object)
  (if (null object) 0 (rontolisp::%java-identity-hash object)))

;; System.arraycopy: the host's replace, which copies at the host's speed where an
;; element at a time is a generic read and a generic store each. Inside one array the
;; range is copied out first: replace says nothing of two ranges that overlap.
(defun rontolisp::%java-arraycopy (from from-start to to-start count)
  (if (or (null from) (null to))
      (error "NullPointerException: System.arraycopy of null"))
  (if (or (< from-start 0) (< to-start 0) (< count 0)
          (> (+ from-start count) (length from))
          (> (+ to-start count) (length to)))
      (error "ArrayIndexOutOfBoundsException: arraycopy: last source index ~D out of bounds for length ~D"
             (+ from-start count) (length from)))
  (if (> count 0)
      (if (eq from to)
          (replace to (subseq from from-start (+ from-start count))
                   :start1 to-start)
          (replace to from
                   :start1 to-start
                   :start2 from-start
                   :end2 (+ from-start count))))
  nil)

;; IO.readln: a line of the standard input without its end, null at the end.
(defun rontolisp::%java-readln ()
  (let ((line (read-line *standard-input* nil nil)))
    (if line (%normalize-string line) nil)))

;; --- Arrays ----------------------------------------------------------------------------

;; Arrays.sort of a range of numbers: the range is copied, sorted and written back.
(defun rontolisp::%java-sort-numbers (items from to)
  (let ((sorted (sort (subseq items from to) #'<)) (i from))
    (while (< i to)
      (%aset items i (aref sorted (- i from)))
      (setq i (+ i 1))))
  nil)

;; Arrays.sort of doubles orders them as Double.compare does.
(defun rontolisp::%java-sort-doubles (items from to)
  (let ((sorted
         (sort (subseq items from to)
               (lambda (a b) (< (rontolisp::%java-double-compare a b) 0))))
        (i from))
    (while (< i to)
      (%aset items i (aref sorted (- i from)))
      (setq i (+ i 1))))
  nil)

;; --- String.format ---------------------------------------------------------------------

(defun rontolisp::%java-pad (text width left zero)
  (let ((missing (- width (length text))))
    (if (<= missing 0)
        text
        (let ((fill (rontolisp::%java-repeat (if zero "0" " ") missing)))
          (cond (left (%string-concat text fill))
                ((and zero (> (length text) 0)
                      (or (char= (char text 0) #\-) (char= (char text 0) #\+)
                          (char= (char text 0) #\Space)))
                 (%string-concat (%string-concat (%substring text 0 1) fill)
                                 (%substring text 1 (length text))))
                (t (%string-concat fill text)))))))

;; The digits of a whole number with a comma between each three.
(defun rontolisp::%java-grouped (digits)
  (let ((n (length digits)))
    (if (<= n 3)
        digits
        (%string-concat (%string-concat (rontolisp::%java-grouped
                                         (%substring digits 0 (- n 3))) ",")
                        (%substring digits (- n 3) n)))))

;; A sign's text ahead of the digits of a number that is not negative.
(defun rontolisp::%java-format-sign (negative flags digits)
  (cond ((and negative (position #\( flags))
         (%string-concat (%string-concat "(" digits) ")"))
        (negative (%string-concat "-" digits))
        ((position #\+ flags) (%string-concat "+" digits))
        ((position #\Space flags) (%string-concat " " digits))
        (t digits)))

;; The digits of a double that is not negative with PRECISION digits after the point:
;; the shortest digits that read back, rounded half up. Answers (whole . fraction).
(defun rontolisp::%java-fixed-digits (d precision)
  (rontolisp::%java-fixed-digits-scaled d 0 precision))

(defun rontolisp::%java-format-fixed (d flags precision)
  (cond ((/= d d) "NaN")
        ((rontolisp::%java-infinite-p d)
         (rontolisp::%java-format-sign (< d 0d0) flags "Infinity"))
        (t (let ((negative (or (< d 0d0) (and (= d 0d0) (< (/ 1d0 d) 0d0)))))
             (let ((parts (rontolisp::%java-fixed-digits (abs d) precision)))
               (let ((whole
                      (if (position #\, flags)
                          (rontolisp::%java-grouped (car parts))
                          (car parts))))
                 (rontolisp::%java-format-sign negative flags
                                               (if (or (> precision 0)
                                                       (position #\# flags))
                                                   (%string-concat
                                                    (%string-concat whole ".")
                                                    (cdr parts))
                                                   whole))))))))

;; %e: one digit, the point, PRECISION digits, the exponent of at least two digits.
(defun rontolisp::%java-format-scientific (d flags precision upper)
  (cond ((/= d d) "NaN")
        ((rontolisp::%java-infinite-p d)
         (rontolisp::%java-format-sign (< d 0d0) flags "Infinity"))
        (t
         (let ((negative (or (< d 0d0) (and (= d 0d0) (< (/ 1d0 d) 0d0))))
               (value (abs d))
               (exponent 0))
           (if (> value 0d0)
               (let ((text (rontolisp::%java-double-string value)))
                 (let ((e (position #\E text)) (point (position #\. text)))
                   (if e
                       (setq exponent (parse-integer text :start (+ e 1)))
                       (if (>= value 1d0)
                           (setq exponent (- point 1))
                           (let ((k (+ point 1)))
                             (while (char= (char text k) #\0) (setq k (+ k 1)))
                             (setq exponent (- point k))))))))
           (let ((parts
                  (rontolisp::%java-fixed-digits-scaled value exponent
                                                        precision)))
             ;; rounding can carry into a second digit ahead of the point
             (if (> (length (car parts)) 1)
                 (progn
                   (setq exponent (+ exponent 1))
                   (setq parts
                         (rontolisp::%java-fixed-digits-scaled value exponent
                                                               precision))))
             (let ((digits
                    (if (or (> precision 0) (position #\# flags))
                        (%string-concat (%string-concat (car parts) ".")
                                        (cdr parts))
                        (car parts)))
                   (power (%decimal-piece (abs exponent))))
               (rontolisp::%java-format-sign negative flags
                                             (%string-concat (%string-concat
                                                              (%string-concat
                                                               digits
                                                               (if upper
                                                                   "E"
                                                                   "e"))
                                                              (if (< exponent 0)
                                                                  "-"
                                                                  "+"))
                                                             (if (<
                                                                  (length power)
                                                                  2)
                                                                 (%string-concat
                                                                  "0" power)
                                                                 power)))))))))

;; The digits of VALUE / 10^EXPONENT with PRECISION places, as %java-fixed-digits.
(defun rontolisp::%java-fixed-digits-scaled (value exponent precision)
  (let ((text (rontolisp::%java-double-string value)))
    (let ((e (position #\E text)) (point (position #\. text)))
      (let ((written (if e (parse-integer text :start (+ e 1)) 0))
            (whole (%substring text 0 point))
            (fraction (%substring text (+ point 1) (if e e (length text)))))
        (let ((digits (parse-integer (%string-concat whole fraction)))
              (scale (+ (- (length fraction) written) exponent)))
          (let ((shifted (- scale precision)))
            (let ((rounded
                   (cond ((<= shifted 0) (* digits (expt 10 (- shifted))))
                         (t (floor (+ digits (floor (expt 10 shifted) 2))
                                   (expt 10 shifted))))))
              (let ((all (%decimal-piece rounded)))
                (if (<= (length all) precision)
                    (setq all
                          (%string-concat (rontolisp::%java-repeat "0"
                                           (- (+ precision 1) (length all)))
                                          all)))
                (cons (%substring all 0 (- (length all) precision))
                      (%substring all (- (length all) precision)
                                  (length all)))))))))))

;; The class a conversion names for an argument it does not take.
(defun rontolisp::%java-format-mismatch (conversion argument)
  (error "IllegalFormatConversionException: ~A != ~A" conversion
         (rontolisp::%java-class-name-or argument)))

(defun rontolisp::%java-class-name-or (x)
  (cond ((stringp x) "java.lang.String")
        ((integerp x) "java.lang.Integer")
        ((consp x) "java.lang.Long")
        ((floatp x) "java.lang.Double")
        ((or (eq x t) (eq x :false)) "java.lang.Boolean")
        ((characterp x) "java.lang.Character")
        (t "an object")))

;; One conversion of String.format over its argument.
(defun rontolisp::%java-format-one
    (conversion flags width precision argument to-string)
  (let ((left (if (position #\- flags) t nil))
        (zero (if (position #\0 flags) t nil)))
    (cond ((or (char= conversion #\s) (char= conversion #\S))
           (let ((text
                  (if (null argument) "null" (funcall to-string argument))))
             (if (and precision (< precision (length text)))
                 (setq text (%substring text 0 precision)))
             (rontolisp::%java-pad
              (if (char= conversion #\S) (%string-upcase-piece text) text) width
              left nil)))
          ((char= conversion #\d)
           (if (null argument)
               (rontolisp::%java-pad "null" width left nil)
               (let ((n
                      (cond ((integerp argument) argument)
                       ((consp argument) (car argument))
                       (t (rontolisp::%java-format-mismatch "d" argument)))))
                 (let ((digits (%decimal-piece (abs n))))
                   (rontolisp::%java-pad (rontolisp::%java-format-sign (< n 0)
                                          flags
                                          (if (position #\, flags)
                                              (rontolisp::%java-grouped digits)
                                              digits)) width left zero)))))
          ((or (char= conversion #\x) (char= conversion #\X)
               (char= conversion #\o))
           (if (null argument)
               (rontolisp::%java-pad "null" width left nil)
               (let ((n
                      (cond ((integerp argument) (logand argument 4294967295))
                            ((consp argument)
                             (logand (car argument) 18446744073709551615))
                            (t (rontolisp::%java-format-mismatch
                                (%normalize-string (string conversion))
                                argument)))))
                 (let ((digits
                        (rontolisp::%java-digits n
                         (if (char= conversion #\o) 8 16))))
                   (if (position #\# flags)
                       (setq digits
                             (%string-concat
                              (if (char= conversion #\o) "0" "0x") digits)))
                   (rontolisp::%java-pad (if (char= conversion #\X)
                                             (%string-upcase-piece digits)
                                             digits) width left zero)))))
          ((or (char= conversion #\f) (char= conversion #\e)
               (char= conversion #\E))
           (if (null argument)
               (rontolisp::%java-pad "null" width left nil)
               (let ((d
                      (cond ((floatp argument) argument)
                            (t (rontolisp::%java-format-mismatch
                                (%normalize-string (string conversion))
                                argument)))))
                 (rontolisp::%java-pad
                  (if (char= conversion #\f)
                      (rontolisp::%java-format-fixed d flags
                                                     (if precision precision 6))
                      (rontolisp::%java-format-scientific d flags
                       (if precision precision 6) (char= conversion #\E))) width
                  left
                  (and zero (= d d) (not (rontolisp::%java-infinite-p d)))))))
          ((or (char= conversion #\c) (char= conversion #\C))
           (let ((text
                  (cond ((null argument) "null")
                   ((characterp argument) (%normalize-string (string argument)))
                   ((integerp argument) (rontolisp::%java-char-string argument))
                   (t (rontolisp::%java-format-mismatch "c" argument)))))
             (rontolisp::%java-pad
              (if (char= conversion #\C) (%string-upcase-piece text) text) width
              left nil)))
          ((or (char= conversion #\b) (char= conversion #\B))
           (let ((text
                  (if (or (null argument) (eq argument :false))
                      "false"
                      "true")))
             (rontolisp::%java-pad
              (if (char= conversion #\B) (%string-upcase-piece text) text) width
              left nil)))
          (t (error "the conversion %~A of String.format is not supported by the experimental Java subset yet"
                    (%normalize-string (string conversion)))))))

;; String.format: the text, each %[index$][flags][width][.precision]conversion replaced
;; by its argument's text. TO-STRING is the program's function for %s.
(defun rontolisp::%java-format (control arguments to-string)
  (let ((builder (rontolisp::%java-sb-new)) (i 0) (n (length control)) (next 0))
    (while (< i n)
      (let ((c (char control i)))
        (if (not (char= c #\%))
            (progn
              (vector-push-extend c builder)
              (setq i (+ i 1)))
            (let ((j (+ i 1))
                  (flags (rontolisp::%java-sb-new))
                  (width 0)
                  (precision nil)
                  (index nil))
              ;; an argument index: digits and a dollar sign
              (let ((k j))
                (while (and (< k n) (digit-char-p (char control k)))
                  (setq k (+ k 1)))
                (if (and (> k j) (< k n) (char= (char control k) #\$))
                    (progn
                      (setq index (- (parse-integer control :start j :end k) 1))
                      (setq j (+ k 1)))))
              (while (and (< j n) (position (char control j) "-#+ 0,("))
                (vector-push-extend (char control j) flags)
                (setq j (+ j 1)))
              (while (and (< j n) (digit-char-p (char control j)))
                (setq width (+ (* width 10) (digit-char-p (char control j))))
                (setq j (+ j 1)))
              (if (and (< j n) (char= (char control j) #\.))
                  (progn
                    (setq j (+ j 1))
                    (setq precision 0)
                    (while (and (< j n) (digit-char-p (char control j)))
                      (setq precision
                       (+ (* precision 10) (digit-char-p (char control j))))
                      (setq j (+ j 1)))))
              (if (>= j n)
                  (error "UnknownFormatConversionException: Conversion = '%'"))
              (let ((conversion (char control j)))
                (cond
                 ((char= conversion #\n) (vector-push-extend #\Newline builder))
                 ((char= conversion #\%)
                  (rontolisp::%java-sb-append builder
                   (rontolisp::%java-pad "%" width (position #\- flags) nil)))
                 (t (let ((at (if index index next)))
                      (if (null index) (setq next (+ next 1)))
                      (if (>= at (length arguments))
                          (error "MissingFormatArgumentException: Format specifier '%~A'"
                                 (%normalize-string (string conversion))))
                      (rontolisp::%java-sb-append builder
                                                  (rontolisp::%java-format-one
                                                   conversion flags width
                                                   precision (aref arguments at)
                                                   to-string))))))
              (setq i (+ j 1))))))
    (rontolisp::%java-sb-string builder)))
