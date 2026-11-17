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
;; an array a simple vector, an object a structure. A boxed number is the number, a
;; boxed char a character, a boxed true T and a boxed false :false, so that no box is
;; null. A StringBuilder is an adjustable character vector, an ArrayList an adjustable
;; vector, a HashMap an equal hash table, a lambda a function.
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

(defun rontolisp::%java-list-string (items element-string)
  (let ((text "[") (i 0) (n (length items)))
    (while (< i n)
      (if (> i 0) (setq text (%string-concat text ", ")))
      (setq text (%string-concat text (funcall element-string (aref items i))))
      (setq i (+ i 1)))
    (%string-concat text "]")))

;; Object.toString() of a value no class of the program answers for. ELEMENT-STRING is
;; the program's own function for a collection's elements, or nil.
(defun rontolisp::%java-to-string (x element-string)
  (cond ((null x) "null")
   ((stringp x) (if (simple-string-p x) x (rontolisp::%java-sb-string x)))
   ((integerp x) (%decimal-piece x))
   ((floatp x) (rontolisp::%java-double-string x))
   ((eq x t) "true")
   ((eq x :false) "false")
   ((characterp x) (%normalize-string (string x)))
   ((hash-table-p x)
    (error
     "the text of a map is not supported by the experimental Java subset yet"))
   ((functionp x) (rontolisp::%java-identity-string "Lambda" x))
   ((vectorp x)
    (if (array-has-fill-pointer-p x)
        (rontolisp::%java-list-string x
                                      (if element-string
                                          element-string
                                          (lambda (e)
                                            (rontolisp::%java-to-string e
                                                                        nil))))
        (rontolisp::%java-identity-string "[" x)))
   (t
    (rontolisp::%java-identity-string (rontolisp::%java-structure-name x) x))))

;; The class name of a value no class of the program answers for.
(defun rontolisp::%java-class-name (x qualified)
  (cond ((null x) (error "NullPointerException: getClass() called on null"))
   ((stringp x)
    (if (simple-string-p x)
        (if qualified "java.lang.String" "String")
        (if qualified "java.lang.StringBuilder" "StringBuilder")))
   ((integerp x) (if qualified "java.lang.Integer" "Integer"))
   ((floatp x) (if qualified "java.lang.Double" "Double"))
   ((or (eq x t) (eq x :false)) (if qualified "java.lang.Boolean" "Boolean"))
   ((characterp x) (if qualified "java.lang.Character" "Character"))
   ((hash-table-p x) (if qualified "java.util.HashMap" "HashMap"))
   ((and (vectorp x) (array-has-fill-pointer-p x))
    (if qualified "java.util.ArrayList" "ArrayList"))
   (t (error
       "getClass() of this value is not supported by the experimental Java subset yet"))))

;; --- Object.equals and hashCode ---------------------------------------------------

(defun rontolisp::%java-string-equals (a b)
  (if (and (stringp b) (simple-string-p b)) (equal a b) nil))

;; Object.equals of a value no class of the program answers for: a string and a box
;; by value, anything else by identity.
(defun rontolisp::%java-equals (a b)
  (cond ((null a) (error "NullPointerException: equals(Object) called on null"))
        ((stringp a)
         (if (simple-string-p a) (rontolisp::%java-string-equals a b) (eq a b)))
        ((integerp a) (if (integerp b) (= a b) nil))
        ((floatp a) (if (floatp b) (= a b) nil))
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

(defun rontolisp::%java-hash-code (x)
  (cond ((null x) (error "NullPointerException: hashCode() called on null"))
   ((stringp x)
    (if (simple-string-p x)
        (rontolisp::%java-string-hash x)
        (rontolisp::%java-identity-hash x)))
   ((integerp x)
    (if (and (>= x -2147483648) (<= x 2147483647))
        x
        ;; Long.hashCode as the API specifies it: (int) (value ^ (value >>> 32))
        (let ((bits (logand x 18446744073709551615)))
          (let ((h (logand (logxor bits (ash bits -32)) 4294967295)))
            (if (>= h 2147483648) (- h 4294967296) h)))))
   ((eq x t) 1231)
   ((eq x :false) 1237)
   ((characterp x) (char-code x))
   ((floatp x)
    (error
     "Double.hashCode() is not supported by the experimental Java subset yet"))
   (t (rontolisp::%java-identity-hash x))))

;; --- numbers ------------------------------------------------------------------------

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

(defun rontolisp::%java-sqrt (d)
  (if (< d 0d0) (let ((infinity (/ 1d0 0d0))) (- infinity infinity)) (sqrt d)))

(defun rontolisp::%java-parse-int (s)
  (let ((n (if (stringp s) (length s) 0)) (i 0) (negative nil) (value 0))
    (if (and (> n 1) (or (char= (char s 0) #\-) (char= (char s 0) #\+)))
        (progn
          (setq negative (char= (char s 0) #\-))
          (setq i 1)))
    (if (= n 0) (error "NumberFormatException: For input string: ~S" s))
    (while (< i n)
      (let ((digit (digit-char-p (char s i))))
        (if (or (null digit) (> value 214748364))
            (error "NumberFormatException: For input string: ~S" s))
        (setq value (+ (* value 10) digit)))
      (setq i (+ i 1)))
    (if negative (setq value (- value)))
    (if (or (> value 2147483647) (< value -2147483648))
        (error "NumberFormatException: For input string: ~S" s))
    value))

;; --- String, StringBuilder ----------------------------------------------------------

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

;; --- ArrayList, HashMap -------------------------------------------------------------

;; aref reads past a fill pointer, so the index is tested against the size here.
(defun rontolisp::%java-list-get (items index)
  (if (or (< index 0) (>= index (length items)))
      (error "IndexOutOfBoundsException: Index ~D out of bounds for length ~D"
             index (length items)))
  (aref items index))

(defun rontolisp::%java-list-set (items index value)
  (let ((old (rontolisp::%java-list-get items index)))
    (setf (aref items index) value)
    old))

(defun rontolisp::%java-map-put (map key value)
  (let ((old (gethash key map)))
    (setf (gethash key map) value)
    old))
