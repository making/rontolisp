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
;; an array a vector, an object a structure. A boxed int or double is the number, a
;; boxed long, byte, short or float the cons (number . tag), the tag :long, :byte, :short
;; or :float -- so an Integer and a Long of one number are two values, and equal tells
;; them apart as equals does --, a boxed char a character,
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

;; One condition type holds every thrown object. TO-STRING is the program's function
;; answering the object's toString(), which the report of an uncaught one asks. No
;; variable or slot here is named as a function of the prelude is: the name alone
;; keeps that function in the program (`describe` kept 10 KB of wasm in every
;; program that throws, .kb/java-frontend.md, "Exceptions").
(define-condition rontolisp::%java-throwable (error)
  ((object :initarg :object :reader rontolisp::%java-throwable-object)
   (to-string :initarg :to-string :reader rontolisp::%java-throwable-describe))
  (:report
   (lambda (condition stream)
     (write-string (funcall (rontolisp::%java-throwable-describe condition)
                            (rontolisp::%java-throwable-object condition))
                   stream))))

;; An exception the run time raises: a simple error whose text is what a JDK prints for
;; it, "java.lang.X" or "java.lang.X: message" -- a text, not a condition class of its
;; own, so a program that holds what it caught pays for no class (.kb/java-frontend.md).
(defun rontolisp::%java-raise (class message)
  (error "~A"
   (if message (%string-concat (%string-concat class ": ") message) class)))

(defun rontolisp::%java-npe ()
  (rontolisp::%java-raise "java.lang.NullPointerException" nil))

(defun rontolisp::%java-throw (object to-string)
  (if (null object)
      (rontolisp::%java-npe)
      (error 'rontolisp::%java-throwable :object object :to-string to-string)))

;; The object a condition holds, nil for a condition that is no thrown object.
(defun rontolisp::%java-thrown (condition)
  (if (typep condition 'rontolisp::%java-throwable)
      (rontolisp::%java-throwable-object condition)
      nil))

;; The message of ArrayIndexOutOfBoundsException for a condition that is an index
;; outside an array -- a type error whose type is (integer 0 (length)) -- else nil.
(defun rontolisp::%java-index-error (condition)
  (let ((type (type-error-expected-type condition))
        (index (type-error-datum condition)))
    (if (and (consp type) (eq (car type) 'integer) (eql (cadr type) 0)
             (consp (cddr type)) (consp (caddr type)) (integerp index))
        (rontolisp::%java-index-text index (car (caddr type)))
        nil)))

(defun rontolisp::%java-index-text (index length)
  (%string-concat (%string-concat
                   (%string-concat "Index " (%decimal-piece index))
                   " out of bounds for length ") (%decimal-piece length)))

(defun rontolisp::%java-range-text (start end length)
  (%string-concat (%string-concat (%string-concat (%string-concat "Range ["
                                                   (%decimal-piece start)) ", ")
                                  (%string-concat (%decimal-piece end)
                                   ") out of bounds for length "))
                  (%decimal-piece length)))

;; The text of an exception the run time raised, else nil.
(defun rontolisp::%java-raised-text (condition)
  (if (typep condition 'simple-error)
      (let ((text (simple-condition-format-control condition)))
        (if (and (stringp text) (> (length text) 5)
                 (string= (%substring text 0 5) "java."))
            text
            nil))
      nil))

;; The text of the exception a condition of the host is -- a division by zero, an index
;; outside an array, a structure's accessor, an array's element or a string's character
;; read on NIL, which is null -- else nil.
(defun rontolisp::%java-host-text (condition)
  (cond ((typep condition 'division-by-zero)
         "java.lang.ArithmeticException: / by zero")
        ((typep condition 'type-error)
         (let ((index (rontolisp::%java-index-error condition)))
           (cond (index (%string-concat
                         "java.lang.ArrayIndexOutOfBoundsException: " index))
                 ((null (type-error-datum condition))
                  "java.lang.NullPointerException")
                 (t nil))))
        (t nil)))

;; THE TABLE: the exception a condition the run time or the host raised is, as
;; (class . message); any other error is a RuntimeException of its text. A thrown
;; object's own condition is no entry: it IS the object. JavaLowering.RAISED lists the
;; classes this answers; JavaLoweringTest holds the two together.
(defun rontolisp::%java-exception-of (condition)
  (let ((text (rontolisp::%java-raised-text condition)))
    (if (null text) (setq text (rontolisp::%java-host-text condition)))
    (if text
        (let ((colon (search ": " text)))
          (if colon
              (cons (%substring text 0 colon)
                    (%substring text (+ colon 2) (length text)))
              (cons text nil)))
        (cons "java.lang.RuntimeException"
              (rontolisp::%java-condition-text condition)))))

(defun rontolisp::%java-condition-text (condition)
  (%normalize-string (princ-to-string condition)))

;; Where PART first is in TEXT, -1 when nowhere: the backend's own search of two
;; whole strings, the one string operation the report of an uncaught condition reads
;; its text with (`search` brings its keywords and the sequence arms into every
;; program, a loop over the characters its signal sites). Both are the host's
;; immutable strings, which every backend decides.
(defun rontolisp::%java-text-at (part text)
  (%string-search part text 0 nil 0 nil))

;; The text of the exception a report of the host is: the same three failures
;; rontolisp::%java-host-text reads off a condition, read off the one line every backend
;; reports them by (.kb/error-handling.md, "An uncaught condition reports ONE line"),
;; where no condition is held. Else nil.
(defun rontolisp::%java-host-report (text)
  (let ((value (rontolisp::%java-text-at "The value " text))
        (type (rontolisp::%java-text-at " is not of type " text)))
    (cond ((and (eql (rontolisp::%java-text-at "Division by zero" text) 0)
                (eql (length text) 16))
           "java.lang.ArithmeticException: / by zero")
          ((and (< -1 value type)
                (or (eql value 0)
                    (eql (rontolisp::%java-text-at ": The value " text)
                         (- value 2))))
           (rontolisp::%java-type-report (%substring text (+ value 10) type)
            (%substring text (+ type 16) (length text))))
          (t nil))))

;; A type error's report, by the value and the type it names: nil is null, an integer
;; outside (INTEGER 0 (length)) an index outside an array.
(defun rontolisp::%java-type-report (value type)
  (cond ((and (eql (rontolisp::%java-text-at "NIL" value) 0)
              (eql (length value) 3))
         "java.lang.NullPointerException")
        ((and (eql (rontolisp::%java-text-at "(INTEGER 0 (" type) 0)
              (eql (rontolisp::%java-text-at "))" type) (- (length type) 2)))
         (%string-concat (%string-concat
                          (%string-concat
                           "java.lang.ArrayIndexOutOfBoundsException: Index "
                           value) " out of bounds for length ")
                         (%substring type 12 (- (length type) 2))))
        (t nil)))

;; What nothing caught, as the line a JDK writes first, for the pipeline's report of
;; an uncaught condition to write (%reporting-uncaught; the status 1 follows it). TEXT
;; is the condition's report: what the run time raised is its own text, a failure of
;; the host the exception it is, any other error of rontolisp's own a RuntimeException
;; of its text. No condition is held, so no signal site builds one
;; (.kb/java-frontend.md, "Exceptions").
(defun rontolisp::%java-uncaught-line (condition text)
  (finish-output)
  (let ((text (%normalize-string text)))
    (%string-concat "Exception in thread \"main\" "
                    (cond ((eql (rontolisp::%java-text-at "java." text) 0) text)
                          ((rontolisp::%java-host-report text))
                          (t (%string-concat "java.lang.RuntimeException: "
                                             text))))))

;; The same in a program that throws objects: one is reported by its toString(), which
;; is its condition's report.
(defun rontolisp::%java-uncaught-thrown-line (condition text)
  (if (and condition (typep condition 'rontolisp::%java-throwable))
      (progn
        (finish-output)
        (%string-concat "Exception in thread \"main\" " text))
      (rontolisp::%java-uncaught-line condition text)))

;; What a session's step left uncaught, signalled again as one line naming the
;; exception: the REPL reports it and reads the next buffer (.kb/java-frontend.md,
;; "A session").
(defun rontolisp::%java-session-error (condition)
  (error "~A"
         (%string-concat "Exception "
                         (if (typep condition 'rontolisp::%java-throwable)
                             (funcall
                              (rontolisp::%java-throwable-describe condition)
                              (rontolisp::%java-throwable-object condition))
                             (let ((exception
                                    (rontolisp::%java-exception-of condition)))
                               (if (cdr exception)
                                   (%string-concat
                                    (%string-concat (car exception) ": ")
                                    (cdr exception))
                                   (car exception)))))))

;; What a Common Lisp function a native method names answers for a String: the
;; program's immutable string.
(defun rontolisp::%java-lisp-string (s) (if (null s) nil (%normalize-string s)))

;; A session shows a string and a char as their literals.
(defun rontolisp::%java-echo-string (s)
  (if (null s)
      "null"
      (%normalize-string
       (with-output-to-string (out)
         (write-char #\" out)
         (loop for c across s do (rontolisp::%java-echo-escape c #\" out))
         (write-char #\" out)))))

(defun rontolisp::%java-echo-char (code)
  (%normalize-string
   (with-output-to-string (out)
     (write-char #\' out)
     (rontolisp::%java-echo-escape (code-char code) #\' out)
     (write-char #\' out))))

;; A session shows an array as int[2] { 1, 2 }: SHOW writes an element.
(defun rontolisp::%java-echo-array (array name below show)
  (if (null array)
      "null"
      (%normalize-string
       (with-output-to-string (out)
         (write-string name out)
         (write-char #\[ out)
         (write-string (%decimal-piece (length array)) out)
         (write-char #\] out)
         (write-string below out)
         (write-string " {" out)
         (let ((first t))
           (loop for e across array
                 do (write-string (if first " " ", ") out) (setq first nil)
                   (write-string (funcall show e) out)))
         (write-string " }" out)))))

(defun rontolisp::%java-echo-escape (c quote out)
  (cond ((char= c #\Newline) (write-string "\\n" out))
        ((char= c #\Tab) (write-string "\\t" out))
        ((char= c #\Return) (write-string "\\r" out))
        (t
         (if (or (char= c quote) (char= c #\\)) (write-char #\\ out))
         (write-char c out))))

(defun rontolisp::%java-no-receiver (object signature)
  (if (null object)
      (rontolisp::%java-npe)
      (error "~A has no implementation for its receiver" signature)))

;; new T[n]: a negative length is the language's exception.
(defun rontolisp::%java-array-length (n)
  (if (< n 0)
      (rontolisp::%java-raise "java.lang.NegativeArraySizeException"
                              (%decimal-piece n))
      n))

;; String.charAt outside the string: the language's exception.
(defun rontolisp::%java-string-index (index s)
  (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
                          (rontolisp::%java-index-text index (length s))))

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

;; What a program that started a thread does of the run time's own state
;; (rontolisp::%java-thread-start sets it): the tables are shared from then on, and an
;; exit is every thread's. Nil until then, so a program that starts none pays a test
;; and holds no lock.
(defvar rontolisp::%java-shared nil)

;; An object's identity hash: a number of its own, kept beside it from the first time
;; it is asked.
(defun rontolisp::%java-identity-hash (object)
  (if rontolisp::%java-shared
      (let ((number (funcall rontolisp::%java-shared t object))) number)
      (rontolisp::%java-identity-number object)))

(defun rontolisp::%java-identity-number (object)
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
        ((consp x)
         (if (eq (cdr x) :float)
             (rontolisp::%java-float-string (car x))
             (%decimal-piece (car x))))
        ((eq x t) "true")
        ((eq x :false) "false")
        ((characterp x) (%normalize-string (string x)))
        ((functionp x) (rontolisp::%java-identity-string "Lambda" x))
        ((vectorp x)
         (if (array-has-fill-pointer-p x)
             (rontolisp::%java-identity-string "java.lang.Object" x)
             (rontolisp::%java-identity-string
              (car (cdr (rontolisp::%java-array-class x nil))) x)))
        (t (rontolisp::%java-identity-string (rontolisp::%java-structure-name x)
                                             x))))

;; The class name of a value no class of the program answers for.
(defun rontolisp::%java-class-name (x qualified)
  (cond ((null x) (rontolisp::%java-npe))
   ((stringp x)
    (if (simple-string-p x)
        (if qualified "java.lang.String" "String")
        (if qualified "java.lang.StringBuilder" "StringBuilder")))
   ((integerp x) (if qualified "java.lang.Integer" "Integer"))
   ((consp x)
    (let ((name (rontolisp::%java-box-name x)))
      (if qualified (%string-concat "java.lang." name) name)))
   ((floatp x) (if qualified "java.lang.Double" "Double"))
   ((or (eq x t) (eq x :false)) (if qualified "java.lang.Boolean" "Boolean"))
   ((characterp x) (if qualified "java.lang.Character" "Character"))
   ((and (vectorp x) (array-has-fill-pointer-p x))
    (if qualified "java.lang.Object" "Object"))
   ((vectorp x)
    (let ((class (rontolisp::%java-array-class x nil)))
      (if qualified (car (cdr class)) (car class))))
   (t (error
       "getClass() of this value is not supported by the experimental Java subset yet"))))

;; --- the class of an array ------------------------------------------------------------
;; An array is a vector, which tells the class of an array of int, byte, char, float,
;; double and boolean by its element type and reads as an Object[] otherwise. The class
;; of an array of long, of short or of references other than Object is what the run
;; time recorded where a program that asks an array's class widened its type: a CLASS
;; is the array's simple name, then the names of the array types it is an instance of,
;; its own first.

(defvar rontolisp::%java-array-classes nil)

;; An array whose type is widened: its class is recorded, and the classes of the arrays
;; it holds as deep as LEVELS goes, but where one is recorded already.
(defun rontolisp::%java-array-widened (array levels)
  (if array
      (if rontolisp::%java-shared
          (funcall rontolisp::%java-shared :array (cons array levels))
          (rontolisp::%java-record-array array levels)))
  array)

(defun rontolisp::%java-record-array (array levels)
  (if (null rontolisp::%java-array-classes)
      (setq rontolisp::%java-array-classes (make-hash-table :test 'eq)))
  (if (null (gethash array rontolisp::%java-array-classes))
      (progn
        (setf (gethash array rontolisp::%java-array-classes) (car levels))
        (if (cdr levels)
            (let ((i 0) (n (length array)))
              (while (< i n)
                (let ((element (aref array i)))
                  (if element
                      (rontolisp::%java-record-array element (cdr levels))))
                (setq i (+ i 1)))))))
  nil)

;; The class of an array: what was recorded, else FALLBACK -- the class of its static
;; type when that is an array type --, else what its vector tells.
(defun rontolisp::%java-array-class (array fallback)
  (let ((recorded (rontolisp::%java-array-recorded array)))
    (if recorded
        recorded
        (if fallback
            fallback
            (let ((type (array-element-type array)))
              (cond ((equal type '(unsigned-byte 32)) '("int[]" "[I"))
                    ((equal type '(unsigned-byte 16)) '("char[]" "[C"))
                    ((equal type '(unsigned-byte 8)) '("byte[]" "[B"))
                    ((eq type 'double-float) '("double[]" "[D"))
                    ((eq type 'single-float) '("float[]" "[F"))
                    ((eq type 'bit) '("boolean[]" "[Z"))
                    (t '("Object[]" "[Ljava.lang.Object;"))))))))

(defun rontolisp::%java-array-recorded (array)
  (if rontolisp::%java-array-classes
      (if rontolisp::%java-shared
          (funcall rontolisp::%java-shared :array array)
          (gethash array rontolisp::%java-array-classes))
      nil))

;; Whether a value is an instance of the array type NAMED, FALLBACK as
;; %java-array-class takes it.
(defun rontolisp::%java-array-is (value name fallback)
  (if (and (vectorp value) (not (stringp value))
           (not (array-has-fill-pointer-p value)))
      (if (member name (cdr (rontolisp::%java-array-class value fallback))
                  :test #'equal)
          t
          nil)
      nil))

;; getClass().getName() and getSimpleName() of an array whose static type is an array
;; type of the class FALLBACK.
(defun rontolisp::%java-array-class-name (array fallback qualified)
  (if (null array) (rontolisp::%java-npe))
  (let ((class (rontolisp::%java-array-class array fallback)))
    (if qualified (car (cdr class)) (car class))))

;; An array made as a copy of ORIGINAL: of its class.
(defun rontolisp::%java-array-like (made original)
  (let ((class (rontolisp::%java-array-recorded original)))
    (if class (rontolisp::%java-array-widened made (list class))))
  made)

;; The simple name of the class of a box that is a cons.
(defun rontolisp::%java-box-name (x)
  (cond ((eq (cdr x) :long) "Long")
        ((eq (cdr x) :float) "Float")
        ((eq (cdr x) :byte) "Byte")
        (t "Short")))

(defun rontolisp::%java-long-p (x)
  (if (and (consp x) (eq (cdr x) :long)) t nil))

(defun rontolisp::%java-byte-p (x)
  (if (and (consp x) (eq (cdr x) :byte)) t nil))

(defun rontolisp::%java-short-p (x)
  (if (and (consp x) (eq (cdr x) :short)) t nil))

(defun rontolisp::%java-float-p (x)
  (if (and (consp x) (eq (cdr x) :float)) t nil))

;; --- Object.equals and hashCode ---------------------------------------------------

;; Long.hashCode as the API specifies it: (int) (value ^ (value >>> 32)).

(defun rontolisp::%java-string-equals (a b)
  (if (and (stringp b) (simple-string-p b)) (equal a b) nil))

;; Object.equals of a value no class of the program answers for: a string and a box
;; by value, anything else by identity.
(defun rontolisp::%java-equals (a b)
  (cond ((null a) (rontolisp::%java-npe))
        ((stringp a)
         (if (simple-string-p a) (rontolisp::%java-string-equals a b) (eq a b)))
        ((integerp a) (if (integerp b) (= a b) nil))
        ((consp a)
         (if (eq (cdr a) :float)
             (if (rontolisp::%java-float-p b)
                 (= (rontolisp::%java-float-bits (car a))
                    (rontolisp::%java-float-bits (car b)))
                 nil)
             (equal a b)))
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
  (cond ((null x) (rontolisp::%java-npe))
        ((stringp x)
         (if (simple-string-p x)
             (rontolisp::%java-string-hash x)
             (rontolisp::%java-identity-hash x)))
        ((integerp x) x)
        ((consp x)
         (cond ((eq (cdr x) :long) (rontolisp::%java-long-hash (car x)))
               ((eq (cdr x) :float) (rontolisp::%java-float-bits (car x)))
               (t (car x))))
        ((eq x t) 1231)
        ((eq x :false) 1237)
        ((characterp x) (char-code x))
        ((floatp x) (rontolisp::%java-double-hash x))
        (t (rontolisp::%java-identity-hash x))))

;; --- numbers ------------------------------------------------------------------------

(defun rontolisp::%java-boolean-p (x) (if (or (eq x t) (eq x :false)) t nil))

;; What reading a box that is null as its primitive raises.
(defun rontolisp::%java-null-unbox () (rontolisp::%java-npe))

(defun rontolisp::%java-unbox-boolean (x)
  (cond ((eq x t) t) ((eq x :false) nil) (t (rontolisp::%java-npe))))

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

;; new T[n][m]..: every level but the last holds arrays. KIND is the leaf's element
;; type (JavaLowering.arrayKind): 1 an int, 2 a double, 3 a byte, 4 a short or a char,
;; 5 a float, 6 a boolean; 0 any other, which starts as INITIAL.
(defun rontolisp::%java-new-array (lengths kind initial)
  (let ((rest lengths))
    (while rest
      (rontolisp::%java-array-length (car rest))
      (setq rest (cdr rest))))
  (rontolisp::%java-new-rows lengths kind initial))

(defun rontolisp::%java-new-rows (lengths kind initial)
  (let ((n (car lengths)))
    (if (null (cdr lengths))
        (cond
         ((eql kind 1)
          (make-array n :element-type '(unsigned-byte 32) :initial-element 0))
         ((eql kind 2)
          (make-array n :element-type 'double-float :initial-element 0d0))
         ((eql kind 3)
          (make-array n :element-type '(unsigned-byte 8) :initial-element 0))
         ((eql kind 4)
          (make-array n :element-type '(unsigned-byte 16) :initial-element 0))
         ((eql kind 5)
          (make-array n :element-type 'single-float :initial-element 0d0))
         ((eql kind 6) (make-array n :element-type 'bit :initial-element 0))
         (t (make-array n :initial-element initial)))
        (let ((rows (make-array n :initial-element nil)) (i 0))
          (while (< i n)
            (setf (aref rows i)
                  (rontolisp::%java-new-rows (cdr lengths) kind initial))
            (setq i (+ i 1)))
          rows))))

(defun rontolisp::%java-null-switch () (rontolisp::%java-npe))

;; A switch that covers its selector's type by its cases reaches this only when the
;; lowering is wrong.
(defun rontolisp::%java-no-match ()
  (rontolisp::%java-raise "java.lang.MatchException" nil))

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
        (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
         (rontolisp::%java-range-text start end (length s))))
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
  (rontolisp::%java-sb-index builder index)
  (char-code (aref builder index)))

;; --- numbers: bits, parsing, text ------------------------------------------------------

(defun rontolisp::%java-number-p (x)
  (if (or (integerp x) (floatp x) (consp x)) t nil))

(defun rontolisp::%java-number-int (x)
  (cond ((integerp x) x)
        ((consp x)
         (if (floatp (car x))
             (rontolisp::%java-double-to-int (car x))
             (- (logand (+ (car x) 2147483648) 4294967295) 2147483648)))
        (t (rontolisp::%java-double-to-int x))))

(defun rontolisp::%java-number-long (x)
  (cond ((integerp x) x)
        ((consp x)
         (if (floatp (car x))
             (rontolisp::%java-double-to-long (car x))
             (car x)))
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

;; The remainder of two doubles, as the language specifies %: the dividend's sign, a
;; NaN for a zero divisor or an infinite dividend. The host's rem is exact elsewhere.
(defun rontolisp::%java-double-rem (x y)
  (if (or (= y 0d0) (/= x x) (/= y y) (rontolisp::%java-infinite-p x))
      (rontolisp::%java-nan)
      (let ((r (rem x y))) (if (= r 0d0) (* 0d0 x) r))))

;; --- float ------------------------------------------------------------------------
;; A float is the double of its value: every binary32 is a binary64, and the lowering
;; rounds each operation's result to the float (%single-float).

;; A long as a float: the nearest, rounded once.
(defun rontolisp::%java-long-float (n)
  (cond ((< (abs n) 16777216) (float n 1d0))
        ((> n 0) (rontolisp::%java-nearest-binary n 1 24 -149 128))
        (t (- (rontolisp::%java-nearest-binary (- n) 1 24 -149 128)))))

;; Float.floatToIntBits as the API specifies it: the sign, the eight bits of the
;; exponent and the twenty-three of the fraction; one NaN.
(defun rontolisp::%java-float-bits (f)
  (cond ((/= f f) 2143289344)
        ((> f 3.4028234663852886d38) 2139095040)
        ((< f -3.4028234663852886d38) -8388608)
        ((= f 0d0) (if (< (/ 1d0 f) 0d0) -2147483648 0))
        (t
         (multiple-value-bind (mantissa exponent sign) (integer-decode-float f)
           (let ((digits (integer-length mantissa)))
             (let ((biased (+ exponent digits 126))
                   (fraction (ash mantissa (- 24 digits))))
               (let ((bits
                      (if (<= biased 0)
                          (ash fraction (- biased 1))
                          (+ (ash biased 23) (- fraction 8388608)))))
                 (if (< sign 0) (- bits 2147483648) bits))))))))

;; --- Math over the bits of a double or a float ---------------------------------------
;; WIDE is t for a double and nil for a float, whose value is the double holding it.

(defun rontolisp::%java-binary-bits (x wide)
  (if wide (rontolisp::%java-double-bits x) (rontolisp::%java-float-bits x)))

(defun rontolisp::%java-binary-of-bits (bits wide)
  (if wide
      (rontolisp::%java-bits-double bits)
      (rontolisp::%java-bits-float bits)))

;; Math.ulp: the distance from a finite value to the next one away from zero, as the
;; API specifies it at the ends: of a NaN a NaN, of an infinity the positive one.
(defun rontolisp::%java-ulp (x wide)
  (cond ((/= x x) x)
        ((rontolisp::%java-infinite-p x) (rontolisp::%java-infinity nil))
        (t (let* ((fraction (if wide 52 23))
                  (biased
                   (logand
                    (ash (rontolisp::%java-binary-bits x wide) (- fraction))
                    (if wide 2047 255))))
             (rontolisp::%java-binary-of-bits (cond ((> biased fraction)
                                                     (ash (- biased fraction)
                                                          fraction))
                                                    ((= biased 0) 1)
                                                    (t (ash 1 (- biased 1))))
                                              wide)))))

;; Math.nextUp (UP t) and Math.nextDown: the neighbour toward an infinity.
(defun rontolisp::%java-next (x up wide)
  (cond ((/= x x) x)
        ((and (rontolisp::%java-infinite-p x) (eq up (> x 0d0))) x)
        ((= x 0d0)
         (rontolisp::%java-binary-of-bits
          (if up 1 (if wide -9223372036854775807 -2147483647)) wide))
        (t (rontolisp::%java-binary-of-bits (if (eq up (> x 0d0))
                                                (+ (rontolisp::%java-binary-bits
                                                    x wide) 1)
                                                (- (rontolisp::%java-binary-bits
                                                    x wide) 1)) wide))))

;; The value of the integer N times 2^POWER, rounded once to the format: a double
;; (WIDE) or a float.
(defun rontolisp::%java-scaled-binary (n power wide)
  (let ((magnitude
         (if (>= power 0)
             (rontolisp::%java-nearest-binary (ash (abs n) power) 1
                                              (if wide 53 24)
                                              (if wide -1074 -149)
                                              (if wide 1024 128))
             (rontolisp::%java-nearest-binary (abs n) (ash 1 (- power))
                                              (if wide 53 24)
                                              (if wide -1074 -149)
                                              (if wide 1024 128)))))
    (if (< n 0) (- magnitude) magnitude)))

;; Math.fma: a * b + c computed exactly and rounded once.
(defun rontolisp::%java-fma (a b c wide)
  (cond ((or (/= a a) (/= b b) (/= c c) (rontolisp::%java-infinite-p a)
             (rontolisp::%java-infinite-p b))
         (+ (* a b) c))
        ((rontolisp::%java-infinite-p c) c)
        (t (multiple-value-bind (ma ea sa) (integer-decode-float a)
             (multiple-value-bind (mb eb sb) (integer-decode-float b)
               (multiple-value-bind (mc ec sc) (integer-decode-float c)
                 (let* ((product (* sa ma sb mb))
                        (pe (+ ea eb))
                        (least (min pe ec))
                        (sum
                         (+ (ash product (- pe least))
                            (ash (* sc mc) (- ec least)))))
                   (cond
                    ((/= sum 0) (rontolisp::%java-scaled-binary sum least wide))
                    ;; an exact zero: -0.0 only from two zeros of that sign
                    ((and (= product 0) (= mc 0)) (+ (* a b) c))
                    (t 0d0)))))))))

;; Math.scalb: x * 2^n rounded once; a NaN, an infinity and a zero are themselves.
(defun rontolisp::%java-scalb (x n wide)
  (if (or (/= x x) (= x 0d0) (rontolisp::%java-infinite-p x))
      x
      (multiple-value-bind (m e s) (integer-decode-float x)
        ;; past these the result is an infinity or a zero whatever n is
        (let ((power (+ e (max -2200 (min 2200 n)))))
          (rontolisp::%java-scaled-binary (* s m) power wide)))))

;; The integers C with C * SCALE between LO and HI, the ends included when INCLUSIVE:
;; (first . last).
(defun rontolisp::%java-scaled-range (lo hi scale inclusive)
  (let ((a (/ lo scale)) (b (/ hi scale)))
    (let ((first (ceiling a)) (last (floor b)))
      (if (and (null inclusive) (= first a)) (setq first (+ first 1)))
      (if (and (null inclusive) (= last b)) (setq last (- last 1)))
      (cons first last))))

;; Float.toString as the API specifies it: of the decimals that read back as the
;; float, the shortest -- of two digits at least -- and of those the nearest, a tie to
;; the even last digit; laid out as a double's text, which that decimal's nearest
;; double prints.
(defun rontolisp::%java-float-string (f)
  (if (or (/= f f) (= f 0d0) (rontolisp::%java-infinite-p f))
      (rontolisp::%java-double-string f)
      (let ((v (rational (abs f))))
        (multiple-value-bind (mantissa exponent) (integer-decode-float (abs f))
          (let ((k (+ (integer-length mantissa) exponent -1)))
            ;; the last place of the float, and the gap to the float below it
            (let ((ulp (expt 2 (- (max k -126) 23)))
                  (e (floor (* k 0.3010299956639812d0))))
              (while (> (expt 10 e) v) (setq e (- e 1)))
              (while (<= (expt 10 (+ e 1)) v) (setq e (+ e 1)))
              (let ((below (if (and (> k -126) (= v (expt 2 k))) (/ ulp 2) ulp))
                    (inclusive (evenp (/ v ulp)))
                    (n 1)
                    (range nil))
                (let ((lo (- v (/ below 2))) (hi (+ v (/ ulp 2))))
                  (while (null range)
                    (let ((r
                           (rontolisp::%java-scaled-range lo hi
                                                          (expt 10 (- e n -1))
                                                          inclusive)))
                      (if (<= (car r) (cdr r))
                          (setq range r)
                          (setq n (+ n 1)))))
                  (if (= n 1)
                      (progn
                        (setq n 2)
                        (setq range
                              (rontolisp::%java-scaled-range lo hi
                                                             (expt 10 (- e 1))
                                                             inclusive))))
                  (let ((scale (expt 10 (- e n -1))))
                    (let ((c (round (/ v scale))))
                      (if (< c (car range)) (setq c (car range)))
                      (if (> c (cdr range)) (setq c (cdr range)))
                      (let ((value (* c scale)))
                        (let ((d
                               (rontolisp::%java-nearest-double
                                (numerator value) (denominator value))))
                          (rontolisp::%java-double-string
                           (if (< f 0d0) (- d) d))))))))))))))

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

;; toBinaryString, toHexString, toOctalString, toUnsignedString: the two's complement
;; of BITS bits, in base ten where RADIX is no radix.
(defun rontolisp::%java-unsigned-string (n radix bits)
  (rontolisp::%java-digits (logand n (- (ash 1 bits) 1))
                           (if (and (>= radix 2) (<= radix 36)) radix 10)))

;; --- Integer, Long: the bits ---------------------------------------------------------

;; The members of Integer and Long over the two's complement of BITS bits (32 or 64).

;; The number whose two's complement of BITS bits U is.
(defun rontolisp::%java-signed (u bits)
  (if (logbitp (- bits 1) u) (- u (ash 1 bits)) u))

(defun rontolisp::%java-highest-one-bit (n bits)
  (let ((u (logand n (- (ash 1 bits) 1))))
    (if (= u 0)
        0
        (rontolisp::%java-signed (ash 1 (- (integer-length u) 1)) bits))))

(defun rontolisp::%java-lowest-one-bit (n bits)
  (rontolisp::%java-signed (logand n (- n) (- (ash 1 bits) 1)) bits))

(defun rontolisp::%java-leading-zeros (n bits)
  (- bits (integer-length (logand n (- (ash 1 bits) 1)))))

(defun rontolisp::%java-trailing-zeros (n bits)
  (if (= n 0) bits (- (integer-length (logand n (- n))) 1)))

;; reverse (STEP 1) and reverseBytes (STEP 8): the groups of STEP bits in the other
;; order.
(defun rontolisp::%java-reverse-bits (n bits step)
  (let ((u (logand n (- (ash 1 bits) 1)))
        (group (- (ash 1 step) 1))
        (r 0)
        (i 0))
    (while (< i bits)
      (setq r (+ (ash r step) (logand u group)))
      (setq u (ash u (- step)))
      (setq i (+ i step)))
    (rontolisp::%java-signed r bits)))

;; rotateLeft; rotateRight is the distance negated.
(defun rontolisp::%java-rotate (n distance bits)
  (let ((mask (- (ash 1 bits) 1)) (d (mod distance bits)))
    (let ((u (logand n mask)))
      (rontolisp::%java-signed
       (logand (logior (ash u d) (ash u (- d bits))) mask) bits))))

(defun rontolisp::%java-unsigned-divide (a b bits)
  (let ((mask (- (ash 1 bits) 1)))
    (rontolisp::%java-signed (values (floor (logand a mask) (logand b mask)))
                             bits)))

(defun rontolisp::%java-unsigned-remainder (a b bits)
  (let ((mask (- (ash 1 bits) 1)))
    (rontolisp::%java-signed (mod (logand a mask) (logand b mask)) bits)))

;; Math.ceilMod: what is left of A over the quotient rounded up.
(defun rontolisp::%java-ceil-mod (a b) (- a (* b (values (ceiling a b)))))

;; Math.nextAfter: X itself turned to the float of DIRECTION where the two are equal,
;; else its neighbour on DIRECTION's side.
(defun rontolisp::%java-next-after (x direction wide)
  (cond ((or (/= x x) (/= direction direction)) (rontolisp::%java-nan))
        ((= x direction) (if wide direction (%single-float direction)))
        (t (rontolisp::%java-next x (> direction x) wide))))

;; Math.getExponent of a float: the eight bits of its exponent, unbiased.
(defun rontolisp::%java-float-exponent (f)
  (- (logand (ash (rontolisp::%java-float-bits f) -23) 255) 127))

(defun rontolisp::%java-parse-boolean (s)
  (if (and s (string-equal s "true")) t nil))

;; The value a text is as a double, or nil when it is none: an optional sign, digits
;; with at most one point, an optional exponent; or Infinity or NaN. Exact arithmetic
;; over the digits, then the nearest double.
(defun rontolisp::%java-read-double (text)
  (rontolisp::%java-read-number text 53 -1074 1024))

;; The same text as a float: the nearest binary32 to the digits, rounded once.
(defun rontolisp::%java-read-float (text)
  (rontolisp::%java-read-number text 24 -149 128))

(defun rontolisp::%java-read-number (text precision least most)
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
                                 (rontolisp::%java-nearest-binary
                                  (* mantissa (expt 10 power)) 1 precision least
                                  most))
                                (t
                                 (rontolisp::%java-nearest-binary mantissa
                                  (expt 10 (- power)) precision least most)))))
                     (if negative (- value) value)))))))))

;; The double nearest to a positive ratio of two integers, a tie to the even one.
(defun rontolisp::%java-nearest-double (numerator denominator)
  (rontolisp::%java-nearest-binary numerator denominator 53 -1074 1024))

;; The binary floating-point value of PRECISION bits nearest to a positive ratio of two
;; integers, a tie to the even one, as the double holding it: PRECISION bits of the
;; quotient and what is left of it. LEAST is the exponent of the last place of the
;; smallest subnormal, MOST the exponent past which the value is infinite.
(defun rontolisp::%java-nearest-binary
    (numerator denominator precision least most)
  (let ((shift
         (- (integer-length numerator) (integer-length denominator)
            (+ precision 1))))
    (let ((scaled
           (if (>= shift 0)
               (floor numerator (ash denominator shift))
               (floor (ash numerator (- shift)) denominator))))
      ;; SCALED has PRECISION + 1 or + 2 bits; the value is SCALED * 2^SHIFT and
      ;; something below
      (let ((exact
             (if (>= shift 0)
                 (= (* scaled (ash denominator shift)) numerator)
                 (= (* scaled denominator) (ash numerator (- shift))))))
        (let ((extra (- (integer-length scaled) precision)))
          ;; a subnormal keeps fewer bits: its last place is 2^LEAST
          (if (< (+ shift extra) least) (setq extra (- least shift)))
          (if (> extra 60)
              0d0
              (let ((kept (ash scaled (- extra)))
                    (rest (logand scaled (- (ash 1 extra) 1)))
                    (half (ash 1 (- extra 1))))
                (if (or (> rest half)
                        (and (= rest half) (or (null exact) (oddp kept))))
                    (setq kept (+ kept 1)))
                (if (> (+ (integer-length kept) shift extra) most)
                    (rontolisp::%java-infinity nil)
                    (scale-float (float kept 1d0) (+ shift extra))))))))))

;; --- characters ------------------------------------------------------------------------

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

;; A code point, or the exception of a number that is none.
(defun rontolisp::%java-code-point (code)
  (if (or (< code 0) (> code 1114111))
      (rontolisp::%java-raise "java.lang.IllegalArgumentException"
                              (%string-concat
                               "Not a valid Unicode code point: 0x"
                               (%normalize-string
                                (write-to-string (logand code 4294967295)
                                                 :base 16
                                                 :radix nil)))))
  code)

;; What the Unicode tables say of a code point: its general category in the low five
;; bits, the predicates of Character above them (regex.lisp, %regex-uni-combo). The
;; tables are the regular-expression engine's (regex-unicode.lisp, a running JDK's
;; answers), so the four backends answer alike where the host's own predicates do
;; not, and only a program calling one of these carries them. The first 256 code
;; points are read once into a vector: a search of the runs is some six times the
;; host's test (.kb/java-frontend.md, "The library").
(defvar rontolisp::%java-char-low nil)

(defun rontolisp::%java-char-combo (code)
  (cond ((or (< code 0) (> code 1114111)) 0)
        ((< code 256)
         (if (null rontolisp::%java-char-low)
             (let ((low (make-array 256 :initial-element 0)) (i 0))
               (while (< i 256)
                 (%aset low i (rontolisp::%regex-uni-combo i))
                 (setq i (+ i 1)))
               (setq rontolisp::%java-char-low low)))
         (svref rontolisp::%java-char-low code))
        (t (rontolisp::%regex-uni-combo code))))

;; Character.getType: the general category as the number the API names it by.
(defun rontolisp::%java-char-type (code)
  (logand (rontolisp::%java-char-combo code) 31))

;; The predicates of Character the tables hold: MASK is the predicate's bit
;; (regex.lisp, %regex-uni-plain's `java` names).
(defun rontolisp::%java-char-is (code mask)
  (if (/= 0 (logand (ash (rontolisp::%java-char-combo code) -5) mask)) t nil))

;; Character.isISOControl: the two ranges of control codes.
(defun rontolisp::%java-char-iso-control-p (code)
  (if (or (and (>= code 0) (<= code 31)) (and (>= code 127) (<= code 159)))
      t
      nil))

;; Character.toChars: the UTF-16 units of a code point.
(defun rontolisp::%java-code-point-chars (code)
  (rontolisp::%java-to-chars
   (rontolisp::%java-char-string (rontolisp::%java-code-point code))))

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

;; lastIndexOf from an index: the last occurrence starting at FROM or before it.
(defun rontolisp::%java-last-index-of-from (s text from)
  (if (< from 0)
      -1
      (let ((found
             (search text s
                     :from-end t
                     :end2 (min (length s) (+ from (length text))))))
        (if found found -1))))

(defun rontolisp::%java-last-index-of-char-from (s code from)
  (if (< from 0)
      -1
      (let ((found
             (position (code-char code) s
                       :from-end t
                       :end (min (length s) (+ from 1)))))
        (if found found -1))))

;; String.regionMatches: COUNT characters of S from OFFSET are those of OTHER from
;; FROM, whatever their case where IGNORE; a region outside either string matches
;; nothing.
(defun rontolisp::%java-region-matches (s ignore offset other from count)
  (if (null other) (rontolisp::%java-npe))
  (cond ((or (< offset 0) (< from 0) (> offset (- (length s) count))
             (> from (- (length other) count)))
         nil)
        ((<= count 0) t)
        (t (let ((a (%substring s offset (+ offset count)))
                 (b (%substring other from (+ from count))))
             (if ignore
                 (rontolisp::%java-string-equals-ignore-case a b)
                 (if (equal a b) t nil))))))

;; String.getChars: the UTF-16 units of the characters from START to END into TARGET
;; from AT.
(defun rontolisp::%java-get-chars (s start end target at)
  (if (or (< start 0) (> start end) (> end (length s)))
      (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
       (rontolisp::%java-range-text start end (length s))))
  (if (null target) (rontolisp::%java-npe))
  (let ((codes (rontolisp::%java-to-chars (%substring s start end))) (i 0))
    (if (or (< at 0) (> at (- (length target) (length codes))))
        (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
         (rontolisp::%java-count-text at (length codes) (length target))))
    (while (< i (length codes))
      (%aset target (+ at i) (aref codes i))
      (setq i (+ i 1))))
  nil)

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
      (rontolisp::%java-raise "java.lang.IllegalArgumentException"
       (%string-concat "count is negative: " (%decimal-piece count))))
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

;; String.toCharArray: the UTF-16 form, a character outside the BMP its two
;; surrogates.
(defun rontolisp::%java-to-chars (s)
  (let ((count 0) (i 0) (n (length s)))
    (while (< i n)
      (setq count (+ count (if (< (char-code (char s i)) 65536) 1 2)))
      (setq i (+ i 1)))
    (let ((codes
           (make-array count
                       :element-type '(unsigned-byte 16)
                       :initial-element 0))
          (j 0))
      (setq i 0)
      (while (< i n)
        (let ((code (char-code (char s i))))
          (if (< code 65536)
              (progn
                (%aset codes j code)
                (setq j (+ j 1)))
              (let ((v (- code 65536)))
                (%aset codes j (+ 55296 (ash v -10)))
                (%aset codes (+ j 1) (+ 56320 (logand v 1023)))
                (setq j (+ j 2)))))
        (setq i (+ i 1)))
      codes)))

;; What an offset and a count outside an array of LENGTH are called.
(defun rontolisp::%java-count-text (offset count length)
  (%string-concat
   (%string-concat (%string-concat
                    (%string-concat "Range [" (%decimal-piece offset))
                    (%string-concat ", " (%decimal-piece offset)))
                   (%string-concat " + " (%decimal-piece count)))
   (%string-concat ") out of bounds for length " (%decimal-piece length))))

;; String.valueOf(char[]) and new String(char[], offset, count).
(defun rontolisp::%java-chars-string (codes offset count)
  (if (null codes) (rontolisp::%java-npe))
  (if (and count
           (or (< offset 0) (< count 0) (> offset (- (length codes) count))))
      (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
       (rontolisp::%java-count-text offset count (length codes))))
  (let ((builder (rontolisp::%java-sb-new))
        (i offset)
        (end (if count (+ offset count) (length codes))))
    ;; a surrogate pair is the one character it encodes
    (while (< i end)
      (let ((code (aref codes i)))
        (if (and (>= code 55296) (< code 56320) (< (+ i 1) end)
                 (>= (aref codes (+ i 1)) 56320) (< (aref codes (+ i 1)) 57344))
            (progn
              (vector-push-extend (code-char
                                   (+ 65536 (ash (- code 55296) 10)
                                      (- (aref codes (+ i 1)) 56320))) builder)
              (setq i (+ i 2)))
            (progn
              (vector-push-extend (code-char code) builder)
              (setq i (+ i 1))))))
    (rontolisp::%java-sb-string builder)))

;; An array of the elements of a list.
(defun rontolisp::%java-list-array (items)
  (let ((result (make-array (length items) :initial-element nil)) (k 0))
    (dolist (item items result)
      (%aset result k item)
      (setq k (+ k 1)))))

;; String.split by a text with no operator (a constant, which the lowering reads): the
;; texts between its occurrences, at most LIMIT of them where LIMIT is above zero, the
;; empty ones at the end dropped where it is zero. A string it does not occur in is the
;; one piece.
(defun rontolisp::%java-split-text (s text limit)
  (let ((pieces nil) (count 0) (at 0) (i 0) (n (length s)) (k (length text)))
    (while (and (<= (+ i k) n) (or (<= limit 0) (< count (- limit 1))))
      (if (rontolisp::%java-starts-with s text i)
          (progn
            (setq pieces (cons (%substring s at i) pieces))
            (setq count (+ count 1))
            (setq i (+ i k))
            (setq at i))
          (setq i (+ i 1))))
    (if (null pieces)
        (rontolisp::%java-list-array (list s))
        (progn
          (setq pieces (cons (%substring s at n) pieces))
          (if (= limit 0)
              (while (and pieces (= (length (car pieces)) 0))
                (setq pieces (cdr pieces))))
          (rontolisp::%java-list-array (reverse pieces))))))

;; java.util.regex over the engine every front end shares (regex.lisp). What the engine
;; refuses comes back as a value, (:refused text index), for the library's Java source
;; to throw as the exception it is; a grapheme boundary past the input is the run
;; time's own StringIndexOutOfBoundsException.
(defun rontolisp::%java-regex-compile (regex flags unicode category grapheme)
  (block refused
    (rontolisp::%regex-compile regex flags
                               :none (lambda (description index)
                                       (if (eq index :bounds)
                                           (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
                                                                   description)
                                           (return-from refused
                                            (list :refused description index))))
                               unicode category grapheme)))

(defun rontolisp::%java-regex-refused-p (made)
  (if (and (consp made) (eq (car made) :refused)) t nil))

(defun rontolisp::%java-regex-refusal-text (refusal) (car (cdr refusal)))

(defun rontolisp::%java-regex-refusal-index (refusal) (car (cdr (cdr refusal))))

;; The index of a named group, -1 for a name the pattern has no group of.
(defun rontolisp::%java-regex-group-index (program name)
  (or (rontolisp::%regex-group-index program name) -1))

(defun rontolisp::%java-regex-split (program input limit)
  (rontolisp::%java-list-array (rontolisp::%regex-split program input limit)))

;; Matcher.replaceAll and replaceFirst: the text, or the refusal of a replacement that
;; names no group -- its index 1 for a group number past the groups, the oracle's
;; IndexOutOfBoundsException, 0 for its IllegalArgumentException.
(defun rontolisp::%java-regex-replace (program input replacement all)
  (block refused
    (rontolisp::%regex-replace program input replacement all
                               (lambda (kind message)
                                 (return-from refused
                                              (list :refused message
                                               (if (eq kind :bounds) 1 0)))))))

;; Matcher.appendReplacement: the replacement of the last match with its group
;; references filled in, or the refusal as %java-regex-replace answers it, with what
;; was written before the reference refused.
(defun rontolisp::%java-regex-expand (matcher replacement)
  (let ((out (make-string-output-stream)))
    (block refused
      (rontolisp::%regex-expand-replacement matcher replacement
                                            (lambda (kind message)
                                              (return-from refused
                                               (list :refused message
                                                     (if (eq kind :bounds) 1 0)
                                                     (%normalize-string
                                                      (get-output-stream-string
                                                       out))))) out)
      (%normalize-string (get-output-stream-string out)))))

(defun rontolisp::%java-regex-refusal-partial (refusal)
  (car (cdr (cdr (cdr refusal)))))

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
  (if rontolisp::%java-shared
      (let ((interned (funcall rontolisp::%java-shared nil s))) interned)
      (rontolisp::%java-interned-of s)))

(defun rontolisp::%java-interned-of (s)
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
      (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
       (rontolisp::%java-range-text start end (length builder)))))

;; An index of a character of a StringBuilder.
(defun rontolisp::%java-sb-index (builder index)
  (if (or (< index 0) (>= index (length builder)))
      (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
       (rontolisp::%java-index-text index (length builder)))))

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
  (rontolisp::%java-sb-index builder index)
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
  (if (< length 0)
      (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
       (%string-concat "String index out of range: " (%decimal-piece length))))
  (while (< (length builder) length) (vector-push-extend (code-char 0) builder))
  (setf (fill-pointer builder) length)
  nil)

(defun rontolisp::%java-sb-set-char (builder index code)
  (rontolisp::%java-sb-index builder index)
  (setf (aref builder index) (code-char code))
  nil)

;; StringBuilder.repeat: TEXT appended COUNT times.
(defun rontolisp::%java-sb-repeat (builder text count)
  (if (< count 0)
      (rontolisp::%java-raise "java.lang.IllegalArgumentException"
       (%string-concat "count is negative: " (%decimal-piece count))))
  (let ((i 0))
    (while (< i count)
      (rontolisp::%java-sb-append builder text)
      (setq i (+ i 1))))
  builder)

;; StringBuilder.repeat of a code point: the count is looked at first.
(defun rontolisp::%java-sb-repeat-code (builder code count)
  (rontolisp::%java-sb-repeat builder
                              (if (< count 0)
                                  ""
                                  (rontolisp::%java-char-string
                                   (rontolisp::%java-code-point code))) count))

(defun rontolisp::%java-sb-index-of (builder text)
  (let ((found (search text builder))) (if found found -1)))

;; --- System, IO ------------------------------------------------------------------------

;; System.exit: the output written so far, then the host's exit with the low byte of
;; the status, as a POSIX host sees it.
(defun rontolisp::%java-exit (status)
  (finish-output)
  (finish-output *error-output*)
  (if rontolisp::%java-shared
      (funcall rontolisp::%java-shared :exit (logand status 255)))
  (%host-exit (logand status 255)))

(defun rontolisp::%java-identity-hash-code (object)
  (if (null object) 0 (rontolisp::%java-identity-hash object)))

;; System.arraycopy: the host's replace, which copies at the host's speed where an
;; element at a time is a generic read and a generic store each. Inside one array the
;; range is copied out first: replace says nothing of two ranges that overlap. FROM-KIND
;; and TO-KIND name the arrays' types as the language's message does ("int", "object
;; array"), from their static types; nil where those are no array types.
(defun rontolisp::%java-arraycopy
    (from from-start to to-start count from-kind to-kind)
  (if (or (null from) (null to)) (rontolisp::%java-npe))
  (let ((problem
         (cond ((< from-start 0)
                (rontolisp::%java-copy-text "source index " from-start from
                                            from-kind))
               ((< to-start 0)
                (rontolisp::%java-copy-text "destination index " to-start to
                                            to-kind))
               ((< count 0)
                (%string-concat
                 (%string-concat "arraycopy: length " (%decimal-piece count))
                 " is negative"))
               ((> (+ from-start count) (length from))
                (rontolisp::%java-copy-text "last source index "
                                            (+ from-start count) from
                                            from-kind))
               ((> (+ to-start count) (length to))
                (rontolisp::%java-copy-text "last destination index "
                                            (+ to-start count) to to-kind))
               (t nil))))
    (if problem
        (rontolisp::%java-raise "java.lang.ArrayIndexOutOfBoundsException"
                                problem)))
  (if (> count 0)
      (if (eq from to)
          (replace to (subseq from from-start (+ from-start count))
                   :start1 to-start)
          (replace to from
                   :start1 to-start
                   :start2 from-start
                   :end2 (+ from-start count))))
  nil)

(defun rontolisp::%java-copy-text (what index array kind)
  (%string-concat
   (%string-concat (%string-concat "arraycopy: " what) (%decimal-piece index))
   (%string-concat
    (%string-concat " out of bounds for " (if kind kind "object array"))
    (%string-concat (%string-concat "[" (%decimal-piece (length array))) "]"))))

;; IO.readln: a line of the standard input without its end, null at the end.
(defun rontolisp::%java-readln ()
  (let ((line (read-line *standard-input* nil nil)))
    (if line (%normalize-string line) nil)))

;; A character of the standard input as its code, -1 at the end: what System.in reads.
(defun rontolisp::%java-read-character ()
  (let ((c (read-char *standard-input* nil nil))) (if c (char-code c) -1)))

;; Whether the standard input has a character at hand with no wait.
#-rontolisp-wasm
(defun rontolisp::%java-input-ready () (if (listen *standard-input*) t nil))

;; A wasm host cannot say.
#+rontolisp-wasm (defun rontolisp::%java-input-ready () nil)

;; Whether a read of several bytes or characters goes on after the one of code LAST: as
;; long as the next is at hand. Where the host cannot say (wasm), to the line's end.
#-rontolisp-wasm
(defun rontolisp::%java-input-more (last) (if (listen *standard-input*) t nil))

#+rontolisp-wasm
(defun rontolisp::%java-input-more (last) (if (= last 10) nil t))

;; --- bytes and text ----------------------------------------------------------------

;; String.getBytes(): the UTF-8 of a string, a byte[].
(defun rontolisp::%java-utf8-bytes (s)
  (rontolisp:string-to-octets (or s (rontolisp::%java-npe))))

;; new String(byte[], offset, count) and new String(byte[]) (COUNT nil) in UTF-8. What
;; is no UTF-8 is U+FFFD, as many as a JDK 25 answers (measured, .kb/java-frontend.md):
;; a byte that starts nothing is one; a start whose next byte cannot follow it is one,
;; alone; a sequence a byte that cannot continue it or the end cuts short is one; the
;; three bytes of a surrogate are one.
(defun rontolisp::%java-utf8-string (bytes offset count)
  (if (null bytes) (rontolisp::%java-npe))
  (if (and count
           (or (< offset 0) (< count 0) (> offset (- (length bytes) count))))
      (rontolisp::%java-raise "java.lang.StringIndexOutOfBoundsException"
       (rontolisp::%java-count-text offset count (length bytes))))
  (let ((builder (rontolisp::%java-sb-new))
        (i offset)
        (end (if count (+ offset count) (length bytes))))
    (while (< i end)
      (let ((b (aref bytes i)))
        (cond
         ((< b 128)
          (vector-push-extend (code-char b) builder)
          (setq i (+ i 1)))
         ((or (< b 194) (> b 244))
          (vector-push-extend (code-char 65533) builder)
          (setq i (+ i 1)))
         (t (let ((size (cond ((< b 224) 2) ((< b 240) 3) (t 4)))
                  (code (logand b (cond ((< b 224) 31) ((< b 240) 15) (t 7))))
                  ;; what the second byte may be depends on the first
                  (low (cond ((= b 224) 160) ((= b 240) 144) (t 128)))
                  (high (if (= b 244) 143 191))
                  (taken 1)
                  (cut nil))
              (while (and (< taken size) (not cut))
                (if (>= (+ i taken) end)
                    (setq cut t)
                    (let ((c (aref bytes (+ i taken))))
                      (if (and (>= c (if (= taken 1) low 128))
                               (<= c (if (= taken 1) high 191)))
                          (progn
                            (setq code (+ (ash code 6) (logand c 63)))
                            (setq taken (+ taken 1)))
                          (setq cut t)))))
              (vector-push-extend (code-char
                                   (if (or cut
                                           (and (>= code 55296) (< code 57344)))
                                       65533
                                       code)) builder)
              (setq i (+ i taken)))))))
    (rontolisp::%java-sb-string builder)))

;; Double.longBitsToDouble as the API specifies it: the sign, the eleven bits of the
;; exponent and the fifty-two of the fraction.
(defun rontolisp::%java-bits-double (bits)
  (rontolisp::%java-bits-binary (logand bits 18446744073709551615) 52 11 53
                                -1074 1024))

;; Float.intBitsToFloat: the sign, eight bits of exponent, twenty-three of fraction;
;; the float is the double of its value.
(defun rontolisp::%java-bits-float (bits)
  (rontolisp::%java-bits-binary (logand bits 4294967295) 23 8 24 -149 128))

;; The value of the unsigned BITS of a binary format: FRACTION bits under EXPONENT
;; bits under the sign. PRECISION, LEAST and MOST are the format's, as
;; %java-nearest-binary takes them.
(defun rontolisp::%java-bits-binary
    (bits fraction exponent precision least most)
  (let ((negative (logbitp (+ fraction exponent) bits))
        (scale (logand (ash bits (- fraction)) (- (ash 1 exponent) 1)))
        (digits (logand bits (- (ash 1 fraction) 1))))
    (cond ((= scale (- (ash 1 exponent) 1))
           (if (= digits 0)
               (rontolisp::%java-infinity negative)
               (rontolisp::%java-nan)))
          ((and (= scale 0) (= digits 0)) (if negative (- 0d0) 0d0))
          (t
           (let* ((mantissa (if (= scale 0) digits (+ digits (ash 1 fraction))))
                  (power (+ least (if (= scale 0) 0 (- scale 1))))
                  (value
                   (if (< power 0)
                       (rontolisp::%java-nearest-binary mantissa
                                                        (ash 1 (- power))
                                                        precision least most)
                       (rontolisp::%java-nearest-binary (ash mantissa power) 1
                                                        precision least most))))
             (if negative (- value) value))))))

;; --- Arrays ----------------------------------------------------------------------------

;; Arrays.sort of a range of numbers: the range is copied, sorted and written back.
(defun rontolisp::%java-sort-numbers (items from to)
  (let ((sorted (sort (subseq items from to) #'<)) (i from))
    (while (< i to)
      (%aset items i (aref sorted (- i from)))
      (setq i (+ i 1))))
  nil)

;; Arrays.sort of an int[], whose elements are held as their unsigned 32 bits.
(defun rontolisp::%java-sort-ints (items from to)
  (let ((sorted
         (sort (subseq items from to)
               (lambda (a b)
                 (< (- (logand (+ a 2147483648) 4294967295) 2147483648)
                    (- (logand (+ b 2147483648) 4294967295) 2147483648)))))
        (i from))
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
  (rontolisp::%java-raise "java.util.IllegalFormatConversionException"
                          (%string-concat
                           (%string-concat
                            (%normalize-string (string conversion)) " != ")
                           (rontolisp::%java-class-name-or argument))))

(defun rontolisp::%java-class-name-or (x)
  (cond ((stringp x) "java.lang.String")
        ((integerp x) "java.lang.Integer")
        ((consp x) (%string-concat "java.lang." (rontolisp::%java-box-name x)))
        ((floatp x) "java.lang.Double")
        ((or (eq x t) (eq x :false)) "java.lang.Boolean")
        ((characterp x) "java.lang.Character")
        (t "an object")))

;; One conversion of String.format over its argument.
(defun rontolisp::%java-format-one
    (conversion flags width precision argument to-string to-hash)
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
                       ((and (consp argument) (integerp (car argument)))
                        (car argument))
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
                            ((and (consp argument) (integerp (car argument)))
                             (logand (car argument)
                                     (cond ((eq (cdr argument) :byte) 255)
                                           ((eq (cdr argument) :short) 65535)
                                           (t 18446744073709551615))))
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
                            ((rontolisp::%java-float-p argument) (car argument))
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
          ;; the hexadecimal digits of the argument's hash code
          ((or (char= conversion #\h) (char= conversion #\H))
           (let ((text
                  (if (null argument)
                      "null"
                      (rontolisp::%java-digits
                       (logand (funcall to-hash argument) 4294967295) 16))))
             (if (and precision (< precision (length text)))
                 (setq text (%substring text 0 precision)))
             (rontolisp::%java-pad
              (if (char= conversion #\H) (%string-upcase-piece text) text) width
              left nil)))
          (t (error "the conversion %~A of String.format is not supported by the experimental Java subset yet"
                    (%normalize-string (string conversion)))))))

;; String.format: the text, each %[index$][flags][width][.precision]conversion replaced
;; by its argument's text. TO-STRING is the program's function for %s, TO-HASH the one
;; for %h, nil where the lowering read a control that has none.
(defun rontolisp::%java-format (control arguments to-string to-hash)
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
                  (rontolisp::%java-raise
                   "java.util.UnknownFormatConversionException"
                   "Conversion = '%'"))
              (let ((conversion (char control j)))
                (cond
                 ((char= conversion #\n) (vector-push-extend #\Newline builder))
                 ((char= conversion #\%)
                  (rontolisp::%java-sb-append builder
                   (rontolisp::%java-pad "%" width (position #\- flags) nil)))
                 (t
                  (let ((at (if index index next)))
                    (if (null index) (setq next (+ next 1)))
                    (if (>= at (length arguments))
                        (rontolisp::%java-raise
                         "java.util.MissingFormatArgumentException"
                         (%string-concat (%string-concat "Format specifier '%"
                                                         (%normalize-string
                                                          (string conversion)))
                                         "'")))
                    (rontolisp::%java-sb-append builder
                                                (rontolisp::%java-format-one
                                                 conversion flags width
                                                 precision (aref arguments at)
                                                 to-string to-hash))))))
              (setq i (+ j 1))))))
    (rontolisp::%java-sb-string builder)))

;; --- threads ------------------------------------------------------------------------

;; A thread of a program is the host's where the target has threads -- the interpreter
;; and the JVM, #-rontolisp-wasm below -- and a body suspended and resumed where the
;; target has one stack -- wasm, #+rontolisp-wasm: there the lowering makes every
;; function of a program that starts threads from which a thread can wait an
;; async-defun and every call of one an await (java/JavaResumable), a thread that
;; cannot go on awaits a future of its own, and the thread the program started on
;; settles those futures in turn (.kb/java-frontend.md, "Threads"). Everything a
;; program can tell of a thread is Java source (library/java/lang/Thread.java) over the
;; functions here.
;;
;; A CARRIER is the run time's record of a thread, #(thread mutex condition interrupted
;; monitor): the Thread object it runs, what it parks on, whether it was interrupted, the
;; monitor it entered last. The running thread's is the value of
;; rontolisp::%java-carrier, bound in each thread the run time starts and made at its
;; first ask for the thread the program started on. On wasm it is #(thread turn queued
;; interrupted monitor holds): the future the thread awaits while it cannot go on,
;; whether it is in the line of the threads that can, how many monitors it holds; the
;; variable is set to the thread whose turn it is.
;;
;; A MONITOR is #(mutex owner count waiters object waiting): the owner a carrier, the
;; waiters the tickets (carrier . notified) of the threads in wait() nothing notified
;; yet, oldest first, the object nil once the monitor left the table, waiting how many
;; threads are in wait(). An object has none until it is first locked; it is found in a
;; table keyed by identity under rontolisp::%java-guard. On wasm a seventh slot holds
;; the carriers of the threads that wait to enter it.

(defvar rontolisp::%java-carrier nil)

(defvar rontolisp::%java-guard (rontolisp:make-mutex))

;; The status System.exit was given on some thread, and the carrier of the thread the
;; program started on. Where the host ends the process at the call nothing reads them;
;; the interpreter's exit ends the thread it is called on, so the thread the program
;; started on takes the status where it next parks, or at the program's end.
(defvar rontolisp::%java-exiting nil)

(defvar rontolisp::%java-main nil)

;; What a program that started a thread does of the run time's own state: WHAT is t
;; for an object's identity number, nil for the one object a text is and :array for
;; the class of an array -- KEY the array, or (array . levels) to record it --, each
;; read one thread at a time, and :exit for the status System.exit was given.
(defun rontolisp::%java-among-threads (what key)
  (if (eq what :exit)
      (progn
        (setq rontolisp::%java-exiting key)
        (if rontolisp::%java-main
            (rontolisp::%java-unpark rontolisp::%java-main nil nil)))
      (progn
        (rontolisp:mutex-acquire rontolisp::%java-guard)
        (let ((found
               (cond ((eq what :array)
                      (if (consp key)
                          (rontolisp::%java-record-array (car key) (cdr key))
                          (gethash key rontolisp::%java-array-classes)))
                     (what (rontolisp::%java-identity-number key))
                     (t (rontolisp::%java-interned-of key)))))
          (rontolisp:mutex-release rontolisp::%java-guard)
          found))))

;; The line a thread that left a condition uncaught writes after its name: what
;; rontolisp::%java-uncaught-thrown-line answers of it for the thread the program started on.
(defun rontolisp::%java-thrown-text (condition)
  (if (typep condition 'rontolisp::%java-throwable)
      (funcall (rontolisp::%java-throwable-describe condition)
               (rontolisp::%java-throwable-object condition))
      (let ((text (rontolisp::%java-raised-text condition)))
        (if (null text) (setq text (rontolisp::%java-host-text condition)))
        (cond (text text)
              ((typep condition 'simple-error)
               (%string-concat "java.lang.RuntimeException: "
                               (simple-condition-format-control condition)))
              (t "java.lang.RuntimeException")))))

(defvar rontolisp::%java-monitors nil)

;; The static initialization of a class in a program that starts threads: one thread
;; initializes a class, and any other asking for THAT class waits until it has. A
;; class's state -- nil, the carrier of the thread initializing it, :ended or, once its
;; initialization threw, the text every later use says of it -- is read and written
;; under this lock, which no initializer runs under.
(defvar rontolisp::%java-initializing-lock (rontolisp:make-mutex))

#-rontolisp-wasm
(defvar rontolisp::%java-initialized (rontolisp::%condition-variable-new))

(defun rontolisp::%java-init-enter ()
  (rontolisp:mutex-acquire rontolisp::%java-initializing-lock))

;; Whether the thread asking for a class in STATE had to wait: another thread
;; initializes it, and this one holds the lock again once some class ended.
#-rontolisp-wasm
(defun rontolisp::%java-init-busy (state)
  (if (or (null state) (eq state :ended) (stringp state))
      nil
      (progn
        (rontolisp::%condition-wait rontolisp::%java-initialized
                                    rontolisp::%java-initializing-lock nil)
        t)))

;; Where no suspended thread can be initializing a class -- its initialization
;; reaches nothing a thread waits in -- nothing is waited for.
#+rontolisp-wasm
(defun rontolisp::%java-init-busy (state)
  (if (or (null state) (eq state :ended) (stringp state))
      nil
      (rontolisp::%java-unforeseen)))

;; The threads waiting for a class another initializes.
#+rontolisp-wasm (defvar rontolisp::%java-initialized nil)

#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-init (state)
  (if (or (null state) (eq state :ended) (stringp state))
      nil
      (let ((carrier (rontolisp::%java-self)))
        (setq rontolisp::%java-initialized
              (cons carrier rontolisp::%java-initialized))
        (rontolisp:await (rontolisp::%java-thread-park carrier nil))
        t)))

;; Gives the lock up; ENDED once a class's initialization has, which the threads
;; waiting for a class are told.
#-rontolisp-wasm
(defun rontolisp::%java-init-leave (ended)
  (if ended (rontolisp::%condition-notify rontolisp::%java-initialized t))
  (rontolisp:mutex-release rontolisp::%java-initializing-lock))

#+rontolisp-wasm
(defun rontolisp::%java-init-leave (ended)
  (if ended
      (let ((waiting rontolisp::%java-initialized))
        (setq rontolisp::%java-initialized nil)
        (while waiting
          (rontolisp::%java-thread-ready (car waiting))
          (setq waiting (cdr waiting)))))
  (rontolisp:mutex-release rontolisp::%java-initializing-lock))

;; What a read or a write of a volatile field holds: one access at a time, so each sees
;; what the one before it left, whatever the host does with a field no lock guards.
(defvar rontolisp::%java-volatile-lock (rontolisp:make-mutex))

(defun rontolisp::%java-volatile () rontolisp::%java-volatile-lock)

#-rontolisp-wasm
(defun rontolisp::%java-thread-carrier (thread)
  (vector thread (rontolisp:make-mutex) (rontolisp::%condition-variable-new) nil
          nil))

#+rontolisp-wasm
(defun rontolisp::%java-thread-carrier (thread)
  (vector thread nil nil nil nil 0))

(defun rontolisp::%java-self ()
  (or rontolisp::%java-carrier
      (setq rontolisp::%java-carrier (rontolisp::%java-thread-carrier nil))))

;; Thread.currentThread(): nil for the thread the program started on until the
;; program asks, when Thread.java makes its object and hands it over.
(defun rontolisp::%java-thread-current () (aref (rontolisp::%java-self) 0))

(defun rontolisp::%java-thread-adopt (thread)
  (let ((carrier (rontolisp::%java-self)))
    (%aset carrier 0 thread)
    carrier))

;; Waits until the ticket is taken, the thread is interrupted or the nanoseconds have
;; passed; with no ticket and no limit, until it is interrupted.
#-rontolisp-wasm
(defun rontolisp::%java-park (carrier ticket nanos)
  (let ((deadline (if nanos (+ (%nano-time) nanos) nil)) (waiting t))
    (rontolisp:mutex-acquire (aref carrier 1))
    (while waiting
      (cond
       ((or (and ticket (cdr ticket)) (aref carrier 3) rontolisp::%java-exiting)
        (setq waiting nil))
       (deadline (let ((left (- deadline (%nano-time))))
                   (if (<= left 0)
                       (setq waiting nil)
                       (rontolisp::%condition-wait (aref carrier 2)
                                                   (aref carrier 1) left))))
       (t (rontolisp::%condition-wait (aref carrier 2) (aref carrier 1) nil))))
    (rontolisp:mutex-release (aref carrier 1))
    (if rontolisp::%java-exiting (%host-exit rontolisp::%java-exiting))))

;; Takes a ticket, or interrupts, and wakes the thread: what it parks on is told under
;; its own mutex, so a thread about to park finds it.
#-rontolisp-wasm
(defun rontolisp::%java-unpark (carrier ticket interrupt)
  (rontolisp:mutex-acquire (aref carrier 1))
  (if ticket (rplacd ticket t))
  (if interrupt (%aset carrier 3 t))
  (rontolisp::%condition-notify (aref carrier 2) t)
  (rontolisp:mutex-release (aref carrier 1)))

;; The thread finds the ticket taken, or itself interrupted, at its next turn.
#+rontolisp-wasm
(defun rontolisp::%java-unpark (carrier ticket interrupt)
  (if ticket (rplacd ticket t))
  (if interrupt (%aset carrier 3 t))
  (rontolisp::%java-thread-ready carrier))

(defun rontolisp::%java-thread-interrupt (carrier)
  (rontolisp::%java-unpark carrier nil t)
  nil)

#-rontolisp-wasm
(defun rontolisp::%java-thread-interrupted (carrier clear)
  (rontolisp:mutex-acquire (aref carrier 1))
  (let ((was (aref carrier 3)))
    (if clear (%aset carrier 3 nil))
    (rontolisp:mutex-release (aref carrier 1))
    (if was t nil)))

#+rontolisp-wasm
(defun rontolisp::%java-thread-interrupted (carrier clear)
  (let ((was (aref carrier 3)))
    (if clear (%aset carrier 3 nil))
    (if was t nil)))

;; The threads started that are no daemons, which the program's end waits for.
(defvar rontolisp::%java-threads nil)

(defvar rontolisp::%java-threads-kept 64)

#-rontolisp-wasm
(defun rontolisp::%java-thread-start (carrier daemon body)
  (if (null rontolisp::%java-shared)
      (progn
        ;; the first thread is started by the one the program started on
        (setq rontolisp::%java-main (rontolisp::%java-self))
        (setq rontolisp::%java-shared
              (function rontolisp::%java-among-threads))))
  (rontolisp:with-mutex (rontolisp::%java-guard)
    (let ((handle
           (rontolisp:make-thread body
            (list (cons 'rontolisp::%java-carrier carrier)))))
      (if (not daemon)
          (progn
            (setq rontolisp::%java-threads
                  (cons handle rontolisp::%java-threads))
            ;; a program that goes on starting threads keeps the live ones only
            (if (> (length rontolisp::%java-threads)
                   rontolisp::%java-threads-kept)
                (progn
                  (setq rontolisp::%java-threads
                        (remove-if-not (function rontolisp:thread-alive-p)
                                       rontolisp::%java-threads))
                  (setq rontolisp::%java-threads-kept
                        (+ 64 (* 2 (length rontolisp::%java-threads))))))))))
  nil)

;; The end of the program: every thread that is no daemon ends first, the ones those
;; start included.
#-rontolisp-wasm
(defun rontolisp::%java-thread-finish ()
  (let ((waiting t))
    (while waiting
      (let ((handle nil))
        (rontolisp:with-mutex (rontolisp::%java-guard)
          (if rontolisp::%java-threads
              (progn
                (setq handle (car rontolisp::%java-threads))
                (setq rontolisp::%java-threads
                      (cdr rontolisp::%java-threads)))))
        (if handle
            (handler-case (rontolisp:join-thread handle) (error (c) nil))
            (setq waiting nil))))
    (if rontolisp::%java-exiting (%host-exit rontolisp::%java-exiting))))

#-rontolisp-wasm
(defun rontolisp::%java-thread-sleep (nanos)
  (let ((carrier (rontolisp::%java-self)))
    (if (> nanos 0) (rontolisp::%java-park carrier nil nanos))
    (rontolisp::%java-thread-interrupted carrier t)))

#-rontolisp-wasm (defun rontolisp::%java-thread-yield () nil)

;; Wasm. A program that starts no thread has one, which nothing can wake: its sleep
;; passes the time, its yield does nothing, and a wait with no limit is refused by
;; name. The functions a thread suspends in -- %java-suspending-.. -- are the ones the
;; lowering calls instead in a program that starts threads.

;; What cannot go on is refused by name, and ends the program: no handler of the
;; program takes it, and nothing goes on as if the wait had ended.
#+rontolisp-wasm
(defun rontolisp::%java-refused (text)
  (finish-output)
  (%warn text)
  (finish-output *error-output*)
  (%host-exit 1))

;; Every thread that is no daemon waits and none is left to wake one.
#+rontolisp-wasm
(defun rontolisp::%java-stuck ()
  (rontolisp::%java-refused
   "every thread of the program waits for another and none can go on: the experimental Java subset ends the program here"))

;; A thread met, where the lowering had found that none can wait, what a suspended
;; thread holds: a monitor, a class it initializes.
#+rontolisp-wasm
(defun rontolisp::%java-unforeseen ()
  (rontolisp::%java-refused
   "a thread met a monitor or a class a suspended thread holds where the experimental Java subset had found none could: not supported yet"))

;; What a resumable function answered where the run time called it -- a toString() it
;; asks for a text, a comparator -- and nothing awaits: its value when it went on to
;; its end, which a thread that has to wait there cannot.
#+rontolisp-wasm
(defun rontolisp::%java-now (value)
  (cond ((not (rontolisp:futurep value)) value)
        ((rontolisp::%future-settled-p value) (rontolisp::%future-force value))
        (t (rontolisp::%java-refused
            "a thread waiting inside a method the run time calls, as a toString() it asks for a text, is not supported by the experimental Java subset yet"))))

#+rontolisp-wasm
(defun rontolisp::%java-sleep-until (time)
  (let ((left (- time (%nano-time)))) (if (> left 0) (sleep (/ left 1.0d9)))))

;; The one thread of a program that starts none cannot go on until the ticket is
;; taken, it is interrupted or the time is up: only the time can still pass.
#+rontolisp-wasm
(defun rontolisp::%java-thread-wait (carrier ticket deadline)
  (cond ((or (and ticket (cdr ticket)) (aref carrier 3)) nil)
        (deadline (rontolisp::%java-sleep-until deadline))
        (t (rontolisp::%java-stuck))))

#+rontolisp-wasm
(defun rontolisp::%java-thread-sleep (nanos)
  (let ((carrier (rontolisp::%java-self)))
    (if (> nanos 0)
        (rontolisp::%java-thread-wait carrier nil (+ (%nano-time) nanos)))
    (rontolisp::%java-thread-interrupted carrier t)))

#+rontolisp-wasm (defun rontolisp::%java-thread-yield () nil)

;; A program that starts threads. A thread is a body the lowering made resumable; one
;; that cannot go on awaits a future of its own (carrier slot 1) and the scheduler --
;; the thread the program started on, at its end -- settles the futures of the threads
;; that can, the one waiting longest first. The line of those is two lists, the
;; second the newest first.
#+rontolisp-wasm (defvar rontolisp::%java-ready nil)

#+rontolisp-wasm (defvar rontolisp::%java-ready-last nil)

;; The threads that wait for a time, (deadline carrier . turn), the soonest first.
;; One that went on for another reason awaits another future, or none.
#+rontolisp-wasm (defvar rontolisp::%java-timed nil)

;; How many threads that are no daemons have not ended.
#+rontolisp-wasm (defvar rontolisp::%java-live 0)

;; What passes the time until a thread waiting for one can go on: set by a sleep and
;; by a wait, so a program with neither names no sleep.
#+rontolisp-wasm (defvar rontolisp::%java-pausing nil)

;; How many more turns of a loop a thread takes before it lets another go on.
#+rontolisp-wasm (defvar rontolisp::%java-slice 1024)

;; The future a thread that cannot go on awaits, until DEADLINE at most.
#+rontolisp-wasm
(defun rontolisp::%java-thread-park (carrier deadline)
  (let ((turn (rontolisp::%future-new)))
    (%aset carrier 1 turn)
    (if deadline
        (let ((entry (cons deadline (cons carrier turn)))
              (timed rontolisp::%java-timed))
          (if (or (null timed) (< deadline (car (car timed))))
              (setq rontolisp::%java-timed (cons entry timed))
              (progn
                (while (and (cdr timed) (>= deadline (car (car (cdr timed)))))
                  (setq timed (cdr timed)))
                (rplacd timed (cons entry (cdr timed)))))))
    turn))

;; A thread that awaits its turn joins the line of the threads that can go on.
#+rontolisp-wasm
(defun rontolisp::%java-thread-ready (carrier)
  (if (and (aref carrier 1) (not (aref carrier 2)))
      (progn
        (%aset carrier 2 t)
        (setq rontolisp::%java-ready-last
              (cons carrier rontolisp::%java-ready-last)))))

;; The future of a thread that lets the others go on first.
#+rontolisp-wasm
(defun rontolisp::%java-thread-queued (carrier)
  (let ((turn (rontolisp::%java-thread-park carrier nil)))
    (rontolisp::%java-thread-ready carrier)
    turn))

;; The threads whose time is up join the line; answers the soonest time one still
;; waits for, or nil.
#+rontolisp-wasm
(defun rontolisp::%java-thread-due ()
  (if (null rontolisp::%java-timed)
      nil
      (let ((now (%nano-time)) (going t))
        (while (and going rontolisp::%java-timed)
          (let ((entry (car rontolisp::%java-timed)))
            (cond ((not (eq (aref (car (cdr entry)) 1) (cdr (cdr entry))))
                   (setq rontolisp::%java-timed (cdr rontolisp::%java-timed)))
                  ((<= (car entry) now)
                   (rontolisp::%java-thread-ready (car (cdr entry)))
                   (setq rontolisp::%java-timed (cdr rontolisp::%java-timed)))
                  (t (setq going nil)))))
        (if rontolisp::%java-timed (car (car rontolisp::%java-timed)) nil))))

;; Whether another thread can go on now.
#+rontolisp-wasm
(defun rontolisp::%java-thread-others ()
  (rontolisp::%java-thread-due)
  (if (or rontolisp::%java-ready rontolisp::%java-ready-last) t nil))

;; The thread waiting longest for its turn takes it: nil when none can go on.
#+rontolisp-wasm
(defun rontolisp::%java-thread-next ()
  (if (and (null rontolisp::%java-ready) rontolisp::%java-ready-last)
      (progn
        (setq rontolisp::%java-ready (reverse rontolisp::%java-ready-last))
        (setq rontolisp::%java-ready-last nil)))
  (if rontolisp::%java-ready
      (let ((carrier (car rontolisp::%java-ready)))
        (setq rontolisp::%java-ready (cdr rontolisp::%java-ready))
        (let ((turn (aref carrier 1)))
          (%aset carrier 1 nil)
          (%aset carrier 2 nil)
          (setq rontolisp::%java-carrier carrier)
          (rontolisp::%future-settle turn nil))
        t)
      nil))

;; A started thread awaits its turn first, so start() returns before the thread runs.
#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-thread-enter (carrier daemon body)
  (rontolisp:await (rontolisp::%java-thread-queued carrier))
  (unwind-protect (rontolisp:await (funcall body))
    (if (not daemon) (setq rontolisp::%java-live (- rontolisp::%java-live 1)))))

#+rontolisp-wasm
(defun rontolisp::%java-thread-start (carrier daemon body)
  (if (not daemon) (setq rontolisp::%java-live (+ rontolisp::%java-live 1)))
  (rontolisp::%java-thread-enter carrier daemon body)
  nil)

;; The thread cannot go on until the ticket is taken, it is interrupted or the time is
;; up.
#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-park
    (carrier ticket deadline)
  (let ((waiting t))
    (while waiting
      (cond ((or (and ticket (cdr ticket)) (aref carrier 3)) (setq waiting nil))
       ((and deadline (>= (%nano-time) deadline)) (setq waiting nil))
       (t (rontolisp:await (rontolisp::%java-thread-park carrier deadline)))))))

#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-sleep (nanos)
  (setq rontolisp::%java-pausing (function rontolisp::%java-sleep-until))
  (let ((carrier (rontolisp::%java-self)))
    (if (> nanos 0)
        (rontolisp:await
         (rontolisp::%java-suspending-park carrier nil (+ (%nano-time) nanos))))
    (rontolisp::%java-thread-interrupted carrier t)))

#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-yield ()
  (if (rontolisp::%java-thread-others)
      (rontolisp:await
       (rontolisp::%java-thread-queued (rontolisp::%java-self))))
  nil)

;; What a loop a thread could turn in for ever does once in so many turns: another
;; thread that can go on does. A thread holding a monitor keeps its turn, so a
;; monitor held over such a loop is none a suspended thread holds.
#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-turn ()
  (setq rontolisp::%java-slice 1024)
  (let ((carrier (rontolisp::%java-self)))
    (if (and (= (aref carrier 5) 0) (rontolisp::%java-thread-others))
        (rontolisp:await (rontolisp::%java-thread-queued carrier))))
  nil)

;; The end of the program: the threads take their turns until those that are no
;; daemons have ended; with none that can go on, the time passes until one can.
#+rontolisp-wasm
(defun rontolisp::%java-thread-finish ()
  (while (> rontolisp::%java-live 0)
    (let ((soonest (rontolisp::%java-thread-due)))
      (cond ((rontolisp::%java-thread-next) nil)
            (soonest (funcall rontolisp::%java-pausing soonest))
            (t (rontolisp::%java-stuck))))))

;; What a thread writes of a condition it left uncaught, after its name: a thrown
;; object as TRACE answers it -- with its causes and what it suppressed -- any other
;; condition as the exception it is.
(defun rontolisp::%java-traced-text (condition trace)
  (let ((thrown (rontolisp::%java-thrown condition)))
    (if thrown
        (funcall trace thrown)
        (rontolisp::%java-thrown-text condition))))

;; The entry of a program that starts threads: what MAIN leaves uncaught is what a JDK
;; writes of it but the frames (TRACE), the thread the program started on ends (FINISH,
;; on its Thread object when the program asked for one), the threads that are no
;; daemons end, and the status is 1 when MAIN left something uncaught.
#-rontolisp-wasm
(defun rontolisp::%java-thread-main (main finish trace)
  (let ((failed nil))
    (handler-case (funcall main)
      (error (condition)
        (finish-output)
        (%warn
         (%string-concat "Exception in thread \"main\" "
                         (rontolisp::%java-traced-text condition trace)))
        (setq failed t)))
    (let ((thread (rontolisp::%java-thread-current)))
      (if thread (funcall finish thread)))
    (rontolisp::%java-thread-finish)
    (if failed
        (progn
          (finish-output)
          (finish-output *error-output*)
          (%host-exit 1)))))

;; Wasm: the thread the program started on is a body as the others are; where it
;; cannot go on, and at its end, the threads take their turns.
#+rontolisp-wasm (defvar rontolisp::%java-failed nil)

#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-thread-first (main finish trace)
  (unwind-protect (progn
                    (handler-case (rontolisp:await (funcall main))
                      (error (condition)
                        (finish-output)
                        (%warn
                         (%string-concat "Exception in thread \"main\" "
                          (rontolisp::%java-traced-text condition trace)))
                        (setq rontolisp::%java-failed t)))
                    (let ((thread (rontolisp::%java-thread-current)))
                      (if thread (rontolisp:await (funcall finish thread)))))
    (setq rontolisp::%java-live (- rontolisp::%java-live 1))))

#+rontolisp-wasm
(defun rontolisp::%java-thread-main (main finish trace)
  (setq rontolisp::%java-live 1)
  (rontolisp::%java-self)
  (rontolisp::%java-thread-first main finish trace)
  (rontolisp::%java-thread-finish)
  (if rontolisp::%java-failed
      (progn
        (finish-output)
        (finish-output *error-output*)
        (%host-exit 1))))

;; The monitor of an object, made when it has none.
(defun rontolisp::%java-monitor (object)
  (rontolisp:mutex-acquire rontolisp::%java-guard)
  (if (null rontolisp::%java-monitors)
      (setq rontolisp::%java-monitors (make-hash-table :test 'eq)))
  (let ((monitor (gethash object rontolisp::%java-monitors)))
    (if (null monitor)
        (progn
          (setq monitor
                (vector (rontolisp:make-mutex) nil 0 nil object 0
                        #+rontolisp-wasm nil))
          (setf (gethash object rontolisp::%java-monitors) monitor)))
    (rontolisp:mutex-release rontolisp::%java-guard)
    monitor))

;; The monitor of an object when the thread holds it, else nil.
(defun rontolisp::%java-monitor-owned (object carrier)
  (if (null object) (rontolisp::%java-npe))
  (rontolisp:mutex-acquire rontolisp::%java-guard)
  (let ((monitor
         (if rontolisp::%java-monitors
             (gethash object rontolisp::%java-monitors)
             nil)))
    (rontolisp:mutex-release rontolisp::%java-guard)
    (if (and monitor (eq (aref monitor 1) carrier)) monitor nil)))

(defun rontolisp::%java-monitor-held (object)
  (if (rontolisp::%java-monitor-owned object (rontolisp::%java-self)) t nil))

;; The monitor of an object for a thread about to enter it: the one the thread entered
;; last when that is the object's, which a thread taking one lock again and again finds
;; with no look in the table. A null is the language's exception.
(defun rontolisp::%java-monitor-of (object carrier)
  (if (null object) (rontolisp::%java-npe))
  (let ((last (aref carrier 4)))
    (if (and last (eq (aref last 4) object))
        last
        (let ((monitor (rontolisp::%java-monitor object)))
          (%aset carrier 4 monitor)
          monitor))))

;; synchronized: the monitor entered, which the exit takes. A monitor that left the
;; table while the thread waited for it, or since the thread entered it last, is no
;; longer the object's: found so under the monitor's own mutex, where it was said.
#-rontolisp-wasm
(defun rontolisp::%java-monitor-enter (object)
  (let ((carrier (rontolisp::%java-self)))
    (let ((monitor (rontolisp::%java-monitor-of object carrier)))
      (rontolisp:mutex-acquire (aref monitor 0))
      (while (null (aref monitor 4))
        (rontolisp:mutex-release (aref monitor 0))
        (setq monitor (rontolisp::%java-monitor object))
        (%aset carrier 4 monitor)
        (rontolisp:mutex-acquire (aref monitor 0)))
      (%aset monitor 1 carrier)
      (%aset monitor 2 (+ (aref monitor 2) 1))
      monitor)))

;; Wasm: the entry of a monitor no suspended thread can hold -- no thread waits in a
;; region of its lock but for the lock itself -- so the monitor is free or the
;; thread's own.
#+rontolisp-wasm
(defun rontolisp::%java-monitor-enter (object)
  (let ((carrier (rontolisp::%java-self)))
    (let ((monitor (rontolisp::%java-monitor-of object carrier)))
      (let ((owner (aref monitor 1)))
        (cond ((null owner)
               (%aset monitor 1 carrier)
               (%aset carrier 5 (+ (aref carrier 5) 1)))
              ((not (eq owner carrier)) (rontolisp::%java-unforeseen))))
      (%aset monitor 2 (+ (aref monitor 2) 1))
      monitor)))

;; The thread takes a monitor no thread holds; while a suspended thread holds it, it
;; waits among the threads the monitor's last exit lets go on. An interrupt does not
;; end that wait.
#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-claim (monitor carrier)
  (while (aref monitor 1)
    (if (not (member carrier (aref monitor 6)))
        (%aset monitor 6 (cons carrier (aref monitor 6))))
    (rontolisp:await (rontolisp::%java-thread-park carrier nil)))
  (if (aref monitor 6) (%aset monitor 6 (remove carrier (aref monitor 6))))
  (%aset monitor 1 carrier)
  (%aset carrier 5 (+ (aref carrier 5) 1)))

;; The entry of a monitor a suspended thread may hold.
#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-enter (object)
  (let ((carrier (rontolisp::%java-self)))
    (let ((monitor (rontolisp::%java-monitor-of object carrier)))
      (if (not (eq (aref monitor 1) carrier))
          (rontolisp:await (rontolisp::%java-suspending-claim monitor carrier)))
      (%aset monitor 2 (+ (aref monitor 2) 1))
      monitor)))

;; A monitor nothing holds any more: the thread that held it holds one fewer, and the
;; threads waiting to enter it can go on.
#+rontolisp-wasm
(defun rontolisp::%java-monitor-left (monitor carrier)
  (%aset carrier 5 (- (aref carrier 5) 1))
  (let ((waiting (aref monitor 6)))
    (while waiting
      (rontolisp::%java-thread-ready (car waiting))
      (setq waiting (cdr waiting)))))

;; A table grown large keeps no monitor that nothing holds and nothing waits in: an
;; object locked once is not kept for ever. Said under the monitor's own mutex, which a
;; thread that found the monitor earlier takes before it reads it.
(defun rontolisp::%java-monitor-exit (monitor)
  (let ((count (- (aref monitor 2) 1)))
    (%aset monitor 2 count)
    (if (= count 0)
        (progn
          #+rontolisp-wasm
          (rontolisp::%java-monitor-left monitor (aref monitor 1))
          (%aset monitor 1 nil)
          (if (and (= (aref monitor 5) 0)
                   #+rontolisp-wasm (null (aref monitor 6))
                   (> (hash-table-count rontolisp::%java-monitors) 4096))
              (progn
                (rontolisp:mutex-acquire rontolisp::%java-guard)
                (remhash (aref monitor 4) rontolisp::%java-monitors)
                (%aset monitor 4 nil)
                (rontolisp:mutex-release rontolisp::%java-guard)))))
    (rontolisp:mutex-release (aref monitor 0))))

;; Object.wait: 0 when the thread was notified or its time is up, 1 when it was
;; interrupted, 2 when it does not hold the monitor. Every hold is given up while it
;; waits and taken again before it goes on.
(defun rontolisp::%java-monitor-wait (object millis)
  (let ((carrier (rontolisp::%java-self)))
    (let ((monitor (rontolisp::%java-monitor-owned object carrier)))
      (cond ((null monitor) 2)
            ((rontolisp::%java-thread-interrupted carrier t) 1)
            (t (let ((count (aref monitor 2)) (ticket (cons carrier nil)))
                 (%aset monitor 3 (append (aref monitor 3) (list ticket)))
                 (%aset monitor 1 nil)
                 (%aset monitor 2 0)
                 (%aset monitor 5 (+ (aref monitor 5) 1))
                 (rontolisp::%java-monitor-waiting monitor carrier ticket count
                  (if (> millis 0) (* millis 1000000) nil))
                 (%aset monitor 5 (- (aref monitor 5) 1))
                 (%aset monitor 1 carrier)
                 (%aset monitor 2 count)
                 (if (cdr ticket)
                     0
                     (progn
                       (%aset monitor 3 (remove ticket (aref monitor 3)))
                       (if (rontolisp::%java-thread-interrupted carrier t)
                           1
                           0)))))))))

#-rontolisp-wasm
(defun rontolisp::%java-monitor-waiting (monitor carrier ticket count nanos)
  (let ((i 0))
    (while (< i count)
      (rontolisp:mutex-release (aref monitor 0))
      (setq i (+ i 1))))
  (rontolisp::%java-park carrier ticket nanos)
  (let ((i 0))
    (while (< i count)
      (rontolisp:mutex-acquire (aref monitor 0))
      (setq i (+ i 1)))))

;; Wasm, a program that starts no thread: nothing can notify its one thread.
#+rontolisp-wasm
(defun rontolisp::%java-monitor-waiting (monitor carrier ticket count nanos)
  (rontolisp::%java-thread-wait carrier ticket
                                (if nanos (+ (%nano-time) nanos) nil)))

;; Object.wait of a program that starts threads: as %java-monitor-wait, the thread
;; suspended while it waits and while a suspended thread holds the monitor after.
#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-wait (object millis)
  (setq rontolisp::%java-pausing (function rontolisp::%java-sleep-until))
  (let ((carrier (rontolisp::%java-self)))
    (let ((monitor (rontolisp::%java-monitor-owned object carrier)))
      (cond ((null monitor) 2)
            ((rontolisp::%java-thread-interrupted carrier t) 1)
            (t (let ((count (aref monitor 2)) (ticket (cons carrier nil)))
                 (%aset monitor 3 (append (aref monitor 3) (list ticket)))
                 (%aset monitor 5 (+ (aref monitor 5) 1))
                 (%aset monitor 2 0)
                 (rontolisp::%java-monitor-left monitor carrier)
                 (%aset monitor 1 nil)
                 (rontolisp:await
                  (rontolisp::%java-suspending-park carrier ticket
                   (if (> millis 0) (+ (%nano-time) (* millis 1000000)) nil)))
                 (rontolisp:await
                  (rontolisp::%java-suspending-claim monitor carrier))
                 (%aset monitor 5 (- (aref monitor 5) 1))
                 (%aset monitor 2 count)
                 (if (cdr ticket)
                     0
                     (progn
                       (%aset monitor 3 (remove ticket (aref monitor 3)))
                       (if (rontolisp::%java-thread-interrupted carrier t)
                           1
                           0)))))))))

;; Object.notify and notifyAll: the oldest waiter, or every one, goes on once the
;; monitor is left; nil when the thread does not hold the monitor.
(defun rontolisp::%java-monitor-notify (object all)
  (let ((monitor
         (rontolisp::%java-monitor-owned object (rontolisp::%java-self))))
    (if (null monitor)
        nil
        (let ((going t))
          (while (and going (aref monitor 3))
            (let ((ticket (car (aref monitor 3))))
              (%aset monitor 3 (cdr (aref monitor 3)))
              (rontolisp::%java-unpark (car ticket) ticket nil))
            (if (not all) (setq going nil)))
          t))))

;; --- java.net.http ----------------------------------------------------------------

;; The client is Java source (rontolisp.internal.Http) over rontolisp:fetch, which the
;; lowered program itself calls, so every transport fetch has carries it and none is
;; new (.kb/fetch-http.md). An exchange is a vector holding the future fetch answered,
;; or the text of what it refused at the call: the future is never a value of the
;; program, since a function a thread is suspended in answers through an await, which
;; would wait for a future handed back bare.

;; The request fields as fetch takes them, from an array of names and values in turn.
(defun rontolisp::%java-http-fields (flat)
  (let ((fields nil) (i (- (length flat) 2)))
    (while (>= i 0)
      (setq fields (cons (cons (aref flat i) (aref flat (+ i 1))) fields))
      (setq i (- i 2)))
    fields))

;; The next chunk of a reply's body, nil at its end: a read whose chunk is in flight
;; settles to the future of it, which is waited for in turn.
(defun rontolisp::%java-http-chunk (body)
  (let ((chunk (rontolisp::%future-force (rontolisp:stream-read body))))
    (while (rontolisp:futurep chunk)
      (setq chunk (rontolisp::%future-force chunk)))
    chunk))

;; The reply of an exchange once all of it is in, #(status fields octets) -- the
;; fields an array of names and values in turn, as fetch lists them --, or the text of
;; what failed: the request refused at the call, the transport, the transfer.
(defun rontolisp::%java-http-reply (exchange)
  (if (stringp exchange)
      exchange
      (handler-case (let* ((reply (rontolisp::%future-force (aref exchange 0)))
                           (body (getf reply :body))
                           (chunks nil)
                           (total 0)
                           (reading t))
                      (while reading
                        (let ((chunk (rontolisp::%java-http-chunk body)))
                          (if (null chunk)
                              (setq reading nil)
                              (progn
                                (setq total (+ total (length chunk)))
                                (setq chunks (cons chunk chunks))))))
                      (let ((flat nil))
                        (dolist (field (getf reply :headers))
                          (setq flat
                                (cons (cdr field) (cons (car field) flat))))
                        (vector (getf reply :status)
                                (coerce (reverse flat) 'simple-vector)
                                (if chunks
                                    (rontolisp::%octets-join (reverse chunks)
                                                             total)
                                    (make-array 0
                                     :element-type '(unsigned-byte 8))))))
        (error (condition) (princ-to-string condition)))))

;; A thread of a program that starts threads on one stack: the threads that can go on
;; take a turn before the reply is waited for, and none goes on while it is.
#+rontolisp-wasm
(rontolisp:async-defun rontolisp::%java-suspending-http-reply (exchange)
  (if (rontolisp::%java-thread-others)
      (rontolisp:await
       (rontolisp::%java-thread-queued (rontolisp::%java-self))))
  (rontolisp::%java-http-reply exchange))
