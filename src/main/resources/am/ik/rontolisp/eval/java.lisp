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

;; getClass() read as a value of what no class object stands for: a lambda.
(defun rontolisp::%java-no-class (x)
  (if (null x)
      (rontolisp::%java-npe)
      (error
       "getClass() of this value is not supported by the experimental Java subset yet")))

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

;; --- the arrays java.lang.reflect.Array makes and uses ------------------------------

;; The letter the class of an array spells after its first '[' ("[I" an array of int's
;; I, "[[I" a '['), as a code, or 0 for a value that is no array.
(defun rontolisp::%java-array-kind (x)
  (if (and (vectorp x) (not (stringp x)) (not (array-has-fill-pointer-p x)))
      (char-code (char (car (cdr (rontolisp::%java-array-class x nil))) 1))
      0))

;; The class of an array of the arrays of the class CLASS: what an array of the elements'
;; supertypes is, and an Object[].
(defun rontolisp::%java-array-nest (class)
  (let ((names (list "[Ljava.lang.Object;")) (rest (reverse (cdr class))))
    (while rest
      (setq names (cons (%string-concat "[" (car rest)) names))
      (setq rest (cdr rest)))
    (cons (%string-concat (car class) "[]") names)))

;; A new array of LENGTH elements of the class CLASS, each its element type's default:
;; the vector of an element type that has one, recorded where it does not tell the class
;; (of long, of short, of references but Object).
(defun rontolisp::%java-array-new (class length)
  (let ((name (car (cdr class))))
    (let ((kind (if (= (length name) 2) (char name 1) #\L)))
      (let ((made
             (cond ((char= kind #\I)
                    (make-array length
                                :element-type '(unsigned-byte 32)
                                :initial-element 0))
                   ((char= kind #\B)
                    (make-array length
                                :element-type '(unsigned-byte 8)
                                :initial-element 0))
                   ((or (char= kind #\C) (char= kind #\S))
                    (make-array length
                                :element-type '(unsigned-byte 16)
                                :initial-element 0))
                   ((char= kind #\Z)
                    (make-array length :element-type 'bit :initial-element 0))
                   ((char= kind #\D)
                    (make-array length
                                :element-type 'double-float
                                :initial-element 0d0))
                   ((char= kind #\F)
                    (make-array length
                                :element-type 'single-float
                                :initial-element 0d0))
                   ((char= kind #\J) (make-array length :initial-element 0))
                   (t (make-array length :initial-element nil)))))
        (if (or (char= kind #\S) (char= kind #\J)
                (and (char= kind #\L) (not (equal name "[Ljava.lang.Object;"))))
            (rontolisp::%java-array-widened made (list class))
            made)))))

;; The class objects of array types made where the program runs, by name: the first made
;; of each, which MAKE makes where none is. Shared by the threads under the run time's
;; guard once one was started.
(defvar rontolisp::%java-array-types nil)

(defun rontolisp::%java-array-type (name make)
  (if rontolisp::%java-shared (rontolisp:mutex-acquire rontolisp::%java-guard))
  (if (null rontolisp::%java-array-types)
      (setq rontolisp::%java-array-types (make-hash-table :test 'equal)))
  (let ((known (gethash name rontolisp::%java-array-types)))
    (if (and (null known) make)
        (progn
          (setq known (funcall make))
          (setf (gethash name rontolisp::%java-array-types) known)))
    (if rontolisp::%java-shared
        (rontolisp:mutex-release rontolisp::%java-guard))
    known))

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
  (if (null b) (rontolisp::%java-npe))
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

;; The text a builder holds: where a high surrogate is followed by a low one, the
;; character the two spell (%normalize-string joins them on every backend).
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

;; The value a text is as a double, or nil when it is none: an optional sign, then digits
;; with at most one point and an optional exponent e or E of ten, or 0x or 0X, hexadecimal
;; digits with at most one point and an exponent p or P of two, which it must have; an
;; optional d, D, f or F; or Infinity or NaN. Exact arithmetic over the digits, then the
;; nearest double; a value past either end of the format by far is infinite or zero
;; without it, measured from the first digit that is not 0.
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
          (radix 10)
          (mantissa 0)
          (scale 0)
          (digits 0)
          (seen nil)
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
             (if (and (< (+ i 1) n) (char= (char s i) #\0)
                      (or (char= (char s (+ i 1)) #\x)
                          (char= (char s (+ i 1)) #\X)))
                 (progn
                   (setq radix 16)
                   (setq i (+ i 2))))
             (let ((going t))
               (while (and going (< i n))
                 (let ((d (rontolisp::%java-radix-digit (char s i) radix)))
                   (cond (d
                          (setq mantissa (+ (* mantissa radix) d))
                          (setq seen t)
                          ;; the digits that tell the size, from the first that is not 0
                          (if (> mantissa 0) (setq digits (+ digits 1)))
                          (if seen-point (setq scale (+ scale 1)))
                          (setq i (+ i 1)))
                         ((and (char= (char s i) #\.) (null seen-point))
                          (setq seen-point t)
                          (setq i (+ i 1)))
                         (t (setq going nil))))))
             (if (null seen) (setq bad t))
             (if (and (< i n)
                      (if (= radix 10)
                          (or (char= (char s i) #\e) (char= (char s i) #\E))
                          (or (char= (char s i) #\p) (char= (char s i) #\P))))
                 (let ((minus nil) (count 0))
                   (setq i (+ i 1))
                   (if (and (< i n)
                            (or (char= (char s i) #\-) (char= (char s i) #\+)))
                       (progn
                         (setq minus (char= (char s i) #\-))
                         (setq i (+ i 1))))
                   (while (and (< i n)
                               (rontolisp::%java-ascii-digit (char s i)))
                     (if (< exponent 100000)
                         (setq exponent
                               (+ (* exponent 10)
                                  (rontolisp::%java-ascii-digit (char s i)))))
                     (setq count (+ count 1))
                     (setq i (+ i 1)))
                   (if (= count 0) (setq bad t))
                   (if minus (setq exponent (- exponent))))
                 (if (= radix 16) (setq bad t)))
             (if (and (< i n)
                      (or (char= (char s i) #\d) (char= (char s i) #\D)
                          (char= (char s i) #\f) (char= (char s i) #\F)))
                 (setq i (+ i 1)))
             (if (or bad (< i n))
                 nil
                 ;; a hexadecimal digit after the point is four bits; the size is in
                 ;; digits of ten, or in bits
                 (let ((power (- exponent (if (= radix 10) scale (* 4 scale))))
                       (limit (if (= radix 10) 400 1200)))
                   (let ((size (+ power (if (= radix 10) digits (* 4 digits)))))
                     (let ((value
                            (cond ((= mantissa 0) 0d0)
                             ((> size limit) (rontolisp::%java-infinity nil))
                             ((< size (- limit)) 0d0)
                             (t (let ((scaled
                                       (if (= radix 10)
                                           (expt 10 (abs power))
                                           (ash 1 (abs power)))))
                                  (if (>= power 0)
                                      (rontolisp::%java-nearest-binary
                                       (* mantissa scaled) 1 precision least
                                       most)
                                      (rontolisp::%java-nearest-binary mantissa
                                       scaled precision least most)))))))
                       (if negative (- value) value))))))))))

;; A digit of radix 10 or 16 written in ASCII, as its value, or nil.
(defun rontolisp::%java-radix-digit (c radix)
  (let ((code (char-code c)))
    (cond ((and (>= code 48) (<= code 57)) (- code 48))
          ((< radix 16) nil)
          ((and (>= code 97) (<= code 102)) (- code 87))
          ((and (>= code 65) (<= code 70)) (- code 55))
          (t nil))))

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

;; Character.toTitleCase: the uppercase, but for the three digraphs, whose title case is
;; the form of a capital and a small letter, and Georgian's Mkhedruli, which has none.
(defun rontolisp::%java-char-titlecase (code)
  (cond ((and (>= code 452) (<= code 460))
         (cond ((<= code 454) 453) ((<= code 457) 456) (t 459)))
        ((and (>= code 497) (<= code 499)) 498)
        ((and (>= code 4304) (<= code 4351)) code)
        (t (rontolisp::%java-char-upcase code))))

;; Character.digit above ASCII: a fullwidth Latin letter as the ASCII one, a decimal
;; digit of Unicode (the general category Nd) as its distance from the zero of its set
;; of ten, the zeros in order. A running JDK's, as the regular-expression engine's
;; tables are (JavaLoweringTest#theLoweringTable pins them to it). Third-party: Unicode
;; Character Database data (Unicode-3.0), Copyright (c) 1991-2026 Unicode, Inc.
;; Notice: THIRD-PARTY-NOTICES.txt, "Unicode".
(defun rontolisp::%java-unicode-digit (code)
  (if (and (>= code 65313) (<= code 65370))
      (digit-char-p (code-char (- code 65248)) 36)
      (let ((zeros
             '(1632 1776 1984 2406 2534 2662 2790 2918 3046 3174 3302 3430 3558
               3664 3792 3872 4160 4240 6112 6160 6470 6608 6784 6800 6992 7088
               7232 7248 42528 43216 43264 43472 43504 43600 44016 65296 66720
               68912 68928 69734 69872 69942 70096 70384 70736 70864 71248 71360
               71376 71386 71472 71904 72016 72688 72784 73040 73120 73552 90416
               92768 92864 93008 93552 118000 120782 120792 120802 120812 120822
               123200 123632 124144 124401 125264 130032))
            (found nil))
        (while zeros
          (let ((above (- code (car zeros))))
            (if (and (>= above 0) (< above 10)) (setq found above)))
          (setq zeros (cdr zeros)))
        found)))

;; Character.digit: an ASCII digit or letter as Common Lisp reads it in base 36, which
;; every backend does alike, anything above as Unicode has it; -1 for what is no digit
;; under the radix, and for a radix outside 2 to 36.
(defun rontolisp::%java-digit (code radix)
  (let ((value
         (cond ((< code 0) nil)
               ((< code 128) (digit-char-p (code-char code) 36))
               (t (rontolisp::%java-unicode-digit code)))))
    (if (and value (>= radix 2) (<= radix 36) (< value radix)) value -1)))

;; An ASCII digit's value, or nil: what a number's text and a format's control read.
(defun rontolisp::%java-ascii-digit (c)
  (let ((code (char-code c)))
    (if (and (>= code 48) (<= code 57)) (- code 48) nil)))

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
  (if (null b) (rontolisp::%java-npe))
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
    ;; a surrogate pair is the one character it encodes, which the string made of the
    ;; units is (%normalize-string)
    (while (< i end)
      (vector-push-extend (code-char (aref codes i)) builder)
      (setq i (+ i 1)))
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

;; --- files -----------------------------------------------------------------------
;; The host's file system as the library's rontolisp.internal.Disk reaches it: a path
;; is a string, a relative one resolved by the host (a .wasm's, against its first
;; preopen), and a file opened a stream of octets. Nothing here signals: what fails
;; answers nil, and the library's Java says why.

;; What the host's stat (FOLLOW true) or lstat says of PATH, with no file opened: the
;; long[] of its kind (1 a regular file, 2 a directory, 3 a link -- where not
;; following --, 4 anything else), its size, the times it was last written, last read
;; and made, in nanoseconds since 1970, the time of making the time of last writing where
;; the host does not say (a WASI host), and its permission bits, -1 where the host has
;; none (a WASI host); or #(0 why), why 1 nothing is there, 2 the host refused, 3 too
;; many links, 4 another reason.
(defun rontolisp::%java-file-stat (path follow)
  (let ((stat (%file-stat path follow)))
    (if (consp stat)
        (let ((modified (third stat)))
          (vector (first stat) (second stat) modified (fourth stat)
                  (or (fifth stat) modified) (or (sixth stat) -1)))
        (vector 0 stat))))

;; Whether the host keeps permission bits: a WASI host keeps none, and what is asked of
;; them answers there as a file system without them does.
#-rontolisp-wasm (defun rontolisp::%java-file-modes () t)

#+rontolisp-wasm (defun rontolisp::%java-file-modes () nil)

;; Whether the host lets the program read (WHAT 4), write (2) or execute (1) PATH.
#-rontolisp-wasm
(defun rontolisp::%java-file-access (path what)
  (if (%file-access path what) t nil))

#+rontolisp-wasm (defun rontolisp::%java-file-access (path what) nil)

;; The size of the partition holding PATH (WHICH 0), the bytes free in it (1) or those
;; the program may use (2); 0 where the host does not say.
#-rontolisp-wasm
(defun rontolisp::%java-file-space (path which) (%file-space path which))

#+rontolisp-wasm (defun rontolisp::%java-file-space (path which) 0)

;; Gives PATH the permission bits MODE: 0, or %java-file-stat's why not.
#-rontolisp-wasm
(defun rontolisp::%java-file-set-mode (path mode) (%file-set-mode path mode))

#+rontolisp-wasm (defun rontolisp::%java-file-set-mode (path mode) 4)

;; Makes a file (DIRP nil) or a directory at PATH with the permission bits MODE less the
;; umask: 0, or %java-file-stat's why not.
#-rontolisp-wasm
(defun rontolisp::%java-file-make (path mode dirp) (%file-make path mode dirp))

#+rontolisp-wasm (defun rontolisp::%java-file-make (path mode dirp) 4)

;; What the host's stat (FOLLOW true) or lstat says of PATH beyond %java-file-stat: the
;; long[] of its owner's and group's numbers, its inode, its device, the device it is,
;; its links, the time its status last changed (nanoseconds since 1970) and its whole
;; mode; or #(why), %java-file-stat's why.
#-rontolisp-wasm
(defun rontolisp::%java-file-unix (path follow)
  (let ((unix (%file-unix path follow nil)))
    (if (consp unix) (rontolisp::%java-list-array unix) (vector unix))))

#+rontolisp-wasm (defun rontolisp::%java-file-unix (path follow) (vector 4))

;; The String[] of the names of PATH's owner and group, nil where the host cannot say.
#-rontolisp-wasm
(defun rontolisp::%java-file-owners (path follow)
  (let ((unix (%file-unix path follow t)))
    (if (consp unix) (rontolisp::%java-list-array (nthcdr 8 unix)) nil)))

#+rontolisp-wasm (defun rontolisp::%java-file-owners (path follow) nil)

;; Gives PATH the owner UID and the group GID, -1 leaving one: 0, or %java-file-stat's
;; why not, 5 where the host does not allow it.
#-rontolisp-wasm
(defun rontolisp::%java-file-set-owner (path follow uid gid)
  (%file-set-owner path follow uid gid))

#+rontolisp-wasm (defun rontolisp::%java-file-set-owner (path follow uid gid) 4)

;; Gives PATH its times of last writing and last access, each a Long of nanoseconds since
;; 1970 -- the cons (value . :long) -- or nil to leave it: 0, or %java-file-stat's why not.
(defun rontolisp::%java-file-set-times (path follow modified accessed)
  (%file-set-times path follow (if accessed (car accessed) nil)
                   (if modified (car modified) nil)))

;; PATH opened for its octets in MODE (Disk's: 0 read, 1 write emptied or made, 2
;; append or made, 3 read and write over what is there, 4 write over what is there),
;; nil where the host refused.
(defun rontolisp::%java-file-open (path mode)
  (handler-case (cond ((= mode 0)
                       (open path
                             :direction :input
                             :element-type '(unsigned-byte 8)))
                      ((= mode 1)
                       (open path
                             :direction :output
                             :element-type '(unsigned-byte 8)
                             :if-exists :supersede
                             :if-does-not-exist :create))
                      ((= mode 2)
                       (open path
                             :direction :output
                             :element-type '(unsigned-byte 8)
                             :if-exists :append
                             :if-does-not-exist :create))
                      ((= mode 3)
                       (open path
                             :direction :io
                             :element-type '(unsigned-byte 8)
                             :if-exists :overwrite))
                      (t (open path
                               :direction :output
                               :element-type '(unsigned-byte 8)
                               :if-exists :overwrite)))
    (error (c) nil)))

;; An octet of FILE, -1 at its end.
(defun rontolisp::%java-file-read-byte (file) (read-byte file nil -1))

;; How many octets of FILE went into BYTES from START, at most WANTED, -1 at its end.
(defun rontolisp::%java-file-read (file bytes start wanted)
  (let ((end (read-sequence bytes file :start start :end (+ start wanted))))
    (if (= end start) -1 (- end start))))

;; Writes reach the file before they answer, as a JDK's unbuffered streams do.
(defun rontolisp::%java-file-write-byte (file b)
  (write-byte (logand b 255) file)
  (finish-output file)
  nil)

(defun rontolisp::%java-file-write (file bytes start wanted)
  (write-sequence bytes file :start start :end (+ start wanted))
  (finish-output file)
  nil)

(defun rontolisp::%java-file-close (file)
  (close file)
  nil)

(defun rontolisp::%java-file-position (file) (file-position file))

(defun rontolisp::%java-file-seek (file offset)
  (file-position file offset)
  nil)

(defun rontolisp::%java-file-length (file) (or (file-length file) 0))

;; The names in the directory at PATH in the host's order, nil for anything else.
(defun rontolisp::%java-file-names (path)
  (let ((listing
         (%list-directory
          (if (char= (char path (- (length path) 1)) #\/)
              path
              (concatenate 'string path "/"))))
        (names nil))
    (if listing
        (progn
          (dolist (name (cdr listing))
            (let ((n (length name)))
              (setq names
                    (cons (if (and (> n 1) (char= (char name (- n 1)) #\/))
                              (%substring name 0 (- n 1))
                              name) names))))
          (rontolisp::%java-list-array (reverse names)))
        nil)))

;; Makes the directory at PATH and every one above it that is missing: whether it is
;; there now.
(defun rontolisp::%java-file-make-directories (path)
  (if (%make-directories (concatenate 'string path "/")) t nil))

;; Deletes the file or the empty directory at PATH.
(defun rontolisp::%java-file-delete (path) (if (%delete-file path) t nil))

(defun rontolisp::%java-file-rename (from to) (if (%rename-file from to) t nil))

;; The target the symbolic link at PATH holds, nil for anything else.
(defun rontolisp::%java-file-read-link (path) (%read-link path))

;; Makes a symbolic link at PATH holding TARGET: whether it was made.
(defun rontolisp::%java-file-link (target path)
  (if (%make-link target path) t nil))

;; The working directory, nil where the host names none.
(defun rontolisp::%java-file-working-directory ()
  (let ((cwd (%host-getcwd))) (if cwd (%normalize-string cwd) nil)))

;; The absolute PATH with its links followed and its . and .. taken out.
(defun rontolisp::%java-file-real (path) (%normalize-string (%real-path path)))

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

;; --- java.security ---------------------------------------------------------------------

;; The digests are the library's Java source (rontolisp.internal.Md5 and its siblings).
;; A target with a kernel of its own answers a digest's blocks and a PBKDF2 derivation
;; there: the interpreter puts its kernels in these two functions' place once it has
;; loaded this file (eval/JavaDigestNative). Here each answers that there is none, and
;; the Java does the work.
(defun rontolisp::%java-digest-blocks (digest state data offset blocks)
  (declare (ignore digest state data offset blocks))
  nil)

(defun rontolisp::%java-digest-derive (digest password salt iterations length)
  (declare (ignore digest password salt iterations length))
  nil)

;; SecureRandom: the host's entropy (rontolisp:random-bytes' own primitive) into every
;; element of a byte array, and as the 32 bits of an int.
(defun rontolisp::%java-random-fill (bytes)
  (dotimes (i (length bytes)) (%aset bytes i (rontolisp::%random-byte)))
  nil)

(defun rontolisp::%java-random-bits ()
  (%mask-signed-field 32
                      (logior (ash (rontolisp::%random-byte) 24)
                              (ash (rontolisp::%random-byte) 16)
                              (ash (rontolisp::%random-byte) 8)
                              (rontolisp::%random-byte))))

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
                          (char= (char text 0) #\Space)
                          (char= (char text 0) #\()))
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
        (t
         (rontolisp::%java-fixed-text
          (or (< d 0d0) (and (= d 0d0) (< (/ 1d0 d) 0d0)))
          (rontolisp::%java-fixed-digits (abs d) precision) flags precision))))

;; %f of the digits (whole . fraction) of a number that is NEGATIVE or not.
(defun rontolisp::%java-fixed-text (negative parts flags precision)
  (let ((whole
         (if (position #\, flags)
             (rontolisp::%java-grouped (car parts))
             (car parts))))
    (rontolisp::%java-format-sign negative flags
                                  (if (or (> precision 0) (position #\# flags))
                                      (%string-concat (%string-concat whole ".")
                                                      (cdr parts))
                                      whole))))

;; %e: one digit, the point, PRECISION digits, the exponent of at least two digits.
(defun rontolisp::%java-format-scientific (d flags precision)
  (cond ((/= d d) "NaN")
        ((rontolisp::%java-infinite-p d)
         (rontolisp::%java-format-sign (< d 0d0) flags "Infinity"))
        (t
         (let ((rounded (rontolisp::%java-scientific-digits (abs d) precision)))
           (rontolisp::%java-scientific-text
            (or (< d 0d0) (and (= d 0d0) (< (/ 1d0 d) 0d0))) (cdr rounded)
            (car rounded) flags precision)))))

;; The digits of a double that is not negative rounded to one digit and PRECISION more:
;; (exponent . (one . fraction)).
(defun rontolisp::%java-scientific-digits (value precision)
  (let ((exponent 0))
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
           (rontolisp::%java-fixed-digits-scaled value exponent precision)))
      ;; rounding can carry into a second digit ahead of the point
      (if (> (length (car parts)) 1)
          (progn
            (setq exponent (+ exponent 1))
            (setq parts
             (rontolisp::%java-fixed-digits-scaled value exponent precision))))
      (cons exponent parts))))

;; %g of a double, padded to WIDTH: PRECISION significant digits (6 where it is -1, 1
;; where it is 0) of the shortest digits rounded half up, in %f's form where the number
;; so rounded is zero or from 10^-4 up to 10^PRECISION, in %e's otherwise.
(defun rontolisp::%java-format-general (d flags width precision)
  (let ((places
         (cond ((< precision 0) 5) ((= precision 0) 0) (t (- precision 1))))
        (finite (and (= d d) (not (rontolisp::%java-infinite-p d)))))
    (let ((exponent
           (if (and finite (/= d 0d0))
               (car (rontolisp::%java-scientific-digits (abs d) places))
               0)))
      (rontolisp::%java-pad (if (and (>= exponent -4) (<= exponent places))
                                (rontolisp::%java-format-fixed d flags
                                 (- places exponent))
                                (rontolisp::%java-format-scientific d flags
                                                                    places))
                            width (position #\- flags)
                            (and finite (position #\0 flags))))))

;; %e of the digits (one . fraction) of a number and its exponent.
(defun rontolisp::%java-scientific-text
    (negative parts exponent flags precision)
  (let ((digits
         (if (or (> precision 0) (position #\# flags))
             (%string-concat (%string-concat (car parts) ".") (cdr parts))
             (car parts)))
        (power (%decimal-piece (abs exponent))))
    (rontolisp::%java-format-sign negative flags
                                  (%string-concat (%string-concat
                                                   (%string-concat digits "e")
                                                   (if (< exponent 0) "-" "+"))
                                                  (if (< (length power) 2)
                                                      (%string-concat "0" power)
                                                      power)))))

;; The digits of VALUE / 10^EXPONENT with PRECISION places, as %java-fixed-digits.
(defun rontolisp::%java-fixed-digits-scaled (value exponent precision)
  (let ((text (rontolisp::%java-double-string value)))
    (let ((e (position #\E text)) (point (position #\. text)))
      (let ((written (if e (parse-integer text :start (+ e 1)) 0))
            (whole (%substring text 0 point))
            (fraction (%substring text (+ point 1) (if e e (length text)))))
        (rontolisp::%java-rounded-digits
         (parse-integer (%string-concat whole fraction))
         (+ (- (length fraction) written) exponent) precision)))))

;; DIGITS / 10^SCALE with PRECISION places, rounded half up: (whole . fraction).
(defun rontolisp::%java-rounded-digits (digits scale precision)
  (let ((shifted (- scale precision)))
    (let ((rounded
           (cond ((<= shifted 0) (* digits (expt 10 (- shifted))))
                 (t (floor (+ digits (floor (expt 10 shifted) 2))
                           (expt 10 shifted))))))
      (let ((all (%decimal-piece rounded)))
        (if (<= (length all) precision)
            (setq all
                  (%string-concat (rontolisp::%java-repeat "0"
                                   (- (+ precision 1) (length all))) all)))
        (cons (%substring all 0 (- (length all) precision))
              (%substring all (- (length all) precision) (length all)))))))

;; A conversion of a format over a number written by its own digits, which only a program
;; that makes one holds (java.util.Conversions): CODE is the conversion's character
;; code, PRECISION -1 where none is given, EXACT #(digits scale) of a BigDecimal and
;; #(digits nil) of a BigInteger, FLAGS the flags as written. Answers the text, and nil
;; where the conversion reads no number (%s, %b, %h, %t); %g is
;; %java-format-exact-general's.
(defun rontolisp::%java-format-exact (code flags width precision exact)
  (let ((conversion (code-char code))
        (left (if (position #\- flags) t nil))
        (zero (if (position #\0 flags) t nil))
        (digits (aref exact 0))
        (scale (aref exact 1))
        (places (if (< precision 0) 6 precision)))
    (cond ((or (char= conversion #\d) (char= conversion #\x)
               (char= conversion #\X) (char= conversion #\o))
           (cond (scale (rontolisp::%java-format-refused conversion
                         "java.math.BigDecimal"))
                 ((char= conversion #\d)
                  (let ((text (%decimal-piece (abs digits))))
                    (rontolisp::%java-pad (rontolisp::%java-format-sign
                                           (< digits 0) flags
                                           (if (position #\, flags)
                                               (rontolisp::%java-grouped text)
                                               text)) width left zero)))
                 (t (rontolisp::%java-exact-radix digits conversion flags width
                                                  left zero))))
          ((or (char= conversion #\f) (char= conversion #\e)
               (char= conversion #\E))
           (if (null scale)
               (rontolisp::%java-format-refused conversion
                                                "java.math.BigInteger")
               (let ((text
                      (if (char= conversion #\f)
                          (rontolisp::%java-fixed-text (< digits 0)
                           (rontolisp::%java-rounded-digits (abs digits) scale
                                                            places) flags
                           places)
                          (rontolisp::%java-exact-scientific digits scale flags
                                                             places))))
                 (rontolisp::%java-pad
                  (if (char= conversion #\E) (%string-upcase-piece text) text)
                  width left zero))))
          ((or (char= conversion #\c) (char= conversion #\C)
               (char= conversion #\a) (char= conversion #\A))
           (rontolisp::%java-format-refused conversion
            (if scale "java.math.BigDecimal" "java.math.BigInteger")))
          (t nil))))

;; %g and %G of a number written by its own digits, as %java-format-exact: %f's form
;; where the number rounded to PRECISION significant digits is from 10^-4 up to
;; 10^PRECISION, %e's otherwise -- a zero's too, but that of the scale 0. A BigInteger is
;; refused.
(defun rontolisp::%java-format-exact-general (code flags width precision exact)
  (if (null (aref exact 1))
      (rontolisp::%java-format-refused (code-char code) "java.math.BigInteger")
      (let ((text
             (rontolisp::%java-exact-general (aref exact 0) (aref exact 1) flags
                                             precision)))
        (rontolisp::%java-pad (if (= code 71) (%string-upcase-piece text) text)
                              width (position #\- flags)
                              (position #\0 flags)))))

(defun rontolisp::%java-exact-general (digits scale flags precision)
  (let ((places
         (cond ((< precision 0) 5) ((= precision 0) 0) (t (- precision 1))))
        (size (abs digits)))
    (let ((exponent (- (- (length (%decimal-piece size)) 1) scale)))
      (if (> (length
              (car
               (rontolisp::%java-rounded-digits size (+ scale exponent)
                                                places))) 1)
          (setq exponent (+ exponent 1)))
      ;; a zero of no scale is the one zero %f's form writes
      (if (and (or (/= digits 0) (null scale) (= scale 0)) (>= exponent -4)
               (<= exponent places))
          (rontolisp::%java-fixed-text (< digits 0)
           (rontolisp::%java-rounded-digits size scale (- places exponent))
           flags (- places exponent))
          (rontolisp::%java-exact-scientific digits scale flags places)))))

;; %e of DIGITS / 10^SCALE: the exponent is the place of its first digit, of a zero the
;; place its scale gives it.
(defun rontolisp::%java-exact-scientific (digits scale flags precision)
  (let ((size (abs digits)))
    (let ((exponent (- (- (length (%decimal-piece size)) 1) scale)))
      (let ((parts
             (rontolisp::%java-rounded-digits size (+ scale exponent)
                                              precision)))
        ;; rounding can carry into a second digit ahead of the point
        (if (> (length (car parts)) 1)
            (progn
              (setq exponent (+ exponent 1))
              (setq parts
                    (rontolisp::%java-rounded-digits size (+ scale exponent)
                                                     precision))))
        (rontolisp::%java-scientific-text (< digits 0) parts exponent flags
                                          precision)))))

;; %x and %o of a whole number of any size: its sign, then its digits, where an int
;; and a long are written as the bits they are held in. The zeros that fill a width
;; stand between the prefix and the digits.
(defun rontolisp::%java-exact-radix (n conversion flags width left zero)
  (let ((digits
         (rontolisp::%java-digits (abs n) (if (char= conversion #\o) 8 16)))
        (prefix
         (cond ((not (position #\# flags)) "")
               ((char= conversion #\o) "0")
               (t "0x"))))
    (if (and zero (not left))
        (let ((missing
               (- width
                  (length
                   (rontolisp::%java-format-sign (< n 0) flags
                    (%string-concat prefix digits))))))
          (if (> missing 0)
              (setq digits
               (%string-concat (rontolisp::%java-repeat "0" missing) digits)))))
    (let ((text
           (rontolisp::%java-format-sign (< n 0) flags
                                         (%string-concat prefix digits))))
      (rontolisp::%java-pad
       (if (char= conversion #\X) (%string-upcase-piece text) text) width left
       nil))))

;; The class a conversion names for an argument it does not take.
(defun rontolisp::%java-format-mismatch (conversion argument)
  (rontolisp::%java-format-refused conversion
                                   (rontolisp::%java-class-name-or argument)))

(defun rontolisp::%java-format-refused (conversion class)
  (rontolisp::%java-raise "java.util.IllegalFormatConversionException"
                          (%string-concat (%string-concat (%normalize-string
                                                           (string
                                                            (char-downcase
                                                             conversion)))
                                                          " != ") class)))

(defun rontolisp::%java-class-name-or (x)
  (cond ((stringp x) "java.lang.String")
        ((integerp x) "java.lang.Integer")
        ((consp x) (%string-concat "java.lang." (rontolisp::%java-box-name x)))
        ((floatp x) "java.lang.Double")
        ((or (eq x t) (eq x :false)) "java.lang.Boolean")
        ((characterp x) "java.lang.Character")
        (t "an object")))

;; The text of a null argument: null, cut to PRECISION characters, in capitals under an
;; uppercase conversion.
(defun rontolisp::%java-format-null (conversion width precision left)
  (rontolisp::%java-pad (if (and precision (< precision 4))
                            (%substring
                             (if (upper-case-p conversion) "NULL" "null") 0
                             precision)
                            (if (upper-case-p conversion) "NULL" "null")) width
                        left nil))

;; One conversion of a format the run time writes itself over its argument.
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
          ((or (char= conversion #\b) (char= conversion #\B))
           (let ((text
                  (if (or (null argument) (eq argument :false))
                      "false"
                      "true")))
             (if (and precision (< precision (length text)))
                 (setq text (%substring text 0 precision)))
             (rontolisp::%java-pad
              (if (char= conversion #\B) (%string-upcase-piece text) text) width
              left nil)))
          ((not (position conversion "dxXofeEcC"))
           (rontolisp::%java-raise "java.util.UnknownFormatConversionException"
                                   (%string-concat (%string-concat
                                                    "Conversion = '"
                                                    (%normalize-string
                                                     (string conversion)))
                                                   "'")))
          ((null argument)
           (rontolisp::%java-format-null conversion width precision left))
          ((char= conversion #\d)
           (let ((n
                  (cond ((integerp argument) argument)
                   ((and (consp argument) (integerp (car argument)))
                    (car argument))
                   (t (rontolisp::%java-format-mismatch conversion argument)))))
             (let ((digits (%decimal-piece (abs n))))
               (rontolisp::%java-pad (rontolisp::%java-format-sign (< n 0) flags
                                      (if (position #\, flags)
                                          (rontolisp::%java-grouped digits)
                                          digits)) width left zero))))
          ((or (char= conversion #\x) (char= conversion #\X)
               (char= conversion #\o))
           (let ((n
                  (cond ((integerp argument) (logand argument 4294967295))
                   ((and (consp argument) (integerp (car argument)))
                    (logand (car argument)
                            (cond ((eq (cdr argument) :byte) 255)
                                  ((eq (cdr argument) :short) 65535)
                                  (t 18446744073709551615))))
                   (t (rontolisp::%java-format-mismatch conversion argument)))))
             (let ((digits
                    (rontolisp::%java-digits n
                                             (if (char= conversion #\o) 8 16))))
               (if (position #\# flags)
                   (setq digits
                         (%string-concat (if (char= conversion #\o) "0" "0x")
                                         digits)))
               (rontolisp::%java-pad
                (if (char= conversion #\X) (%string-upcase-piece digits) digits)
                width left zero))))
          ((or (char= conversion #\f) (char= conversion #\e)
               (char= conversion #\E))
           (let ((d
                  (cond ((floatp argument) argument)
                   ((rontolisp::%java-float-p argument) (car argument))
                   (t (rontolisp::%java-format-mismatch conversion argument)))))
             (rontolisp::%java-pad
              (cond ((char= conversion #\f)
                     (rontolisp::%java-format-fixed d flags
                                                    (if precision precision 6)))
                    ((char= conversion #\E)
                     (%string-upcase-piece
                      (rontolisp::%java-format-scientific d flags
                       (if precision precision 6))))
                    (t (rontolisp::%java-format-scientific d flags
                        (if precision precision 6)))) width left
              (and zero (= d d) (not (rontolisp::%java-infinite-p d))))))
          (t
           (let ((text
                  (cond
                   ((characterp argument) (%normalize-string (string argument)))
                   ((integerp argument) (rontolisp::%java-char-string argument))
                   (t (rontolisp::%java-format-mismatch conversion argument)))))
             (rontolisp::%java-pad
              (if (char= conversion #\C) (%string-upcase-piece text) text) width
              left nil))))))

;; Appends TEXT from START to END, or all of it where END is nil, to OUT: a builder, or,
;; where APPENDER is a function, the Formatter OUT through it.
(defun rontolisp::%java-format-put (out appender text start end)
  (if appender
      (funcall appender out text start (if end end -1))
      (let ((i start) (n (if end end (length text))))
        (while (< i n)
          (vector-push-extend (char text i) out)
          (setq i (+ i 1))))))

;; String.format: the text, each %[index$][flags][width][.precision]conversion replaced
;; by its argument's text. TO-STRING is the program's function for %s; TO-MORE, nil in a
;; program needing none, answers the conversions the run time leaves to the library
;; (java.util.Conversions).
(defun rontolisp::%java-format (control arguments to-string to-more)
  (let ((builder (rontolisp::%java-sb-new)))
    (rontolisp::%java-format-to builder nil control arguments to-string to-more)
    (rontolisp::%java-sb-string builder)))

;; A format appended to OUT through %java-format-put: each run of the control's text
;; as a range of it, each converted argument as a text of its own. A null array of
;; arguments is a null for each specifier.
(defun rontolisp::%java-format-to
    (out appender control arguments to-string to-more)
  (if (null control) (rontolisp::%java-npe))
  (let ((i 0) (n (length control)) (next 0))
    (while (< i n)
      (let ((at (position #\% control :start i)))
        (if (null at) (setq at n))
        (if (> at i) (rontolisp::%java-format-put out appender control i at))
        (setq i at)
        (if (< at n)
            (let ((j (+ at 1))
                  (width 0)
                  (precision nil)
                  (index nil)
                  (suffix nil))
              ;; an argument index: digits and a dollar sign
              (let ((k j))
                (while (and (< k n)
                            (rontolisp::%java-ascii-digit (char control k)))
                  (setq k (+ k 1)))
                (if (and (> k j) (< k n) (char= (char control k) #\$))
                    (progn
                      (setq index (- (parse-integer control :start j :end k) 1))
                      (setq j (+ k 1)))))
              ;; the flags as written, after the index
              (let ((flags
                     (let ((from j))
                       (while (and (< j n)
                                   (position (char control j) "-#+ 0,("))
                         (setq j (+ j 1)))
                       (%substring control from j))))
                (while (and (< j n)
                            (rontolisp::%java-ascii-digit (char control j)))
                  (setq width
                        (+ (* width 10)
                           (rontolisp::%java-ascii-digit (char control j))))
                  (setq j (+ j 1)))
                (if (and (< j n) (char= (char control j) #\.))
                    (progn
                      (setq j (+ j 1))
                      (setq precision 0)
                      (while (and (< j n)
                              (rontolisp::%java-ascii-digit (char control j)))
                        (setq precision
                              (+ (* precision 10)
                               (rontolisp::%java-ascii-digit (char control j))))
                        (setq j (+ j 1)))))
                (if (>= j n)
                    (rontolisp::%java-raise
                     "java.util.UnknownFormatConversionException"
                     "Conversion = '%'"))
                (let ((conversion (char control j)))
                  (if (or (char= conversion #\t) (char= conversion #\T))
                      (progn
                        (if (>= (+ j 1) n)
                            (rontolisp::%java-raise
                             "java.util.UnknownFormatConversionException"
                             (%string-concat (%string-concat "Conversion = '"
                                                             (%normalize-string
                                                              (string
                                                               conversion)))
                                             "'")))
                        (setq j (+ j 1))
                        (setq suffix (char control j))))
                  (cond ((char= conversion #\n)
                         (rontolisp::%java-format-put out appender
                                                      (string #\Newline) 0 nil))
                        ((char= conversion #\%)
                         (rontolisp::%java-format-put out appender
                                                      (rontolisp::%java-pad "%"
                                                       width
                                                       (position #\- flags) nil)
                                                      0 nil))
                        (t
                         (let ((at (if index index next)))
                           (if (null index) (setq next (+ next 1)))
                           (if (and arguments (>= at (length arguments)))
                               (rontolisp::%java-raise
                                "java.util.MissingFormatArgumentException"
                                (%string-concat (%string-concat
                                                 "Format specifier '%"
                                                 (%substring control (+ i 1)
                                                             (+ j 1))) "'")))
                           (let ((argument
                                  (if arguments (aref arguments at) nil)))
                             (rontolisp::%java-format-put out appender
                              (or (if to-more
                                      (funcall to-more (char-code conversion)
                                               (if suffix (char-code suffix) 0)
                                               flags (if index (+ index 1) 0)
                                               width (if precision precision -1)
                                               argument out)
                                      nil)
                                  (rontolisp::%java-format-one conversion flags
                                                               width precision
                                                               argument
                                                               to-string)) 0
                              nil)))))))
              (setq i (+ j 1))))))))

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

;; How many monitors the table holds: read at every last exit, where asking the table
;; cost 30 ns of the 170 an entry and its exit take on wasm.
(defvar rontolisp::%java-monitors-held 0)

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
          nil nil))

#+rontolisp-wasm
(defun rontolisp::%java-thread-carrier (thread)
  (vector thread nil nil nil nil 0))

(defun rontolisp::%java-self ()
  (or rontolisp::%java-carrier
      (setq rontolisp::%java-carrier (rontolisp::%java-thread-carrier nil))))

;; Thread.currentThread(): nil for a thread the run time did not start until the
;; program asks, when Thread.java makes its object and hands it over. Until then the
;; carrier of a transport's thread holds the name that object is given.
(defun rontolisp::%java-thread-current ()
  (let ((thread (aref (rontolisp::%java-self) 0)))
    (if (stringp thread) nil thread)))

(defun rontolisp::%java-thread-named ()
  (let ((name (aref (rontolisp::%java-self) 0))) (if (stringp name) name nil)))

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

;; Whether a thread waits to enter a monitor another holds (Thread.State.BLOCKED): on
;; the host what its entry said when it found the monitor held (carrier slot 5), on
;; one stack whether it is among the threads the monitor's last exit lets go on.
#-rontolisp-wasm
(defun rontolisp::%java-thread-blocked (carrier) (if (aref carrier 5) t nil))

#+rontolisp-wasm
(defun rontolisp::%java-thread-blocked (carrier)
  (let ((monitor (aref carrier 4)))
    (if (and monitor (member carrier (aref monitor 6))) t nil)))

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

;; What a ReentrantLock's lock() answered where the lowering had found that no
;; suspended thread can hold the lock: it took the lock at once, as a resumable function
;; that went on to its end answers -- its value, or a future settled.
#+rontolisp-wasm
(defun rontolisp::%java-locked (value)
  (cond ((not (rontolisp:futurep value)) value)
        ((rontolisp::%future-settled-p value) (rontolisp::%future-force value))
        (t (rontolisp::%java-refused
            "a thread met a lock a suspended thread holds where the experimental Java subset had found none could: not supported yet"))))

;; What a resumable function answered where the run time called it -- a toString() it
;; asks for a text, a comparator -- and nothing awaits: its value when it went on to
;; its end, which a thread that has to wait there cannot.
#+rontolisp-wasm
(defun rontolisp::%java-now (value)
  (cond ((not (rontolisp:futurep value)) value)
        ((rontolisp::%future-settled-p value) (rontolisp::%future-force value))
        (t (rontolisp::%java-refused
            "a thread waiting inside a method the run time calls, as a toString() it asks for a text, is not supported by the experimental Java subset yet"))))

#+(or (and rontolisp-wasm (not rontolisp-reactor)) rontolisp-host-timer)
(defun rontolisp::%java-sleep-until (time)
  (let ((left (- time (%nano-time)))) (if (> left 0) (sleep (/ left 1.0d9)))))

;; A reactor's clock is the one its host sets before each call: no time passes within
;; a call, so a wait for a time that no other thread can end is one for ever -- unless
;; its host waits for it (--host-timer), where sleep is the host's timer.
#+(and rontolisp-reactor (not rontolisp-host-timer))
(defun rontolisp::%java-sleep-until (time)
  (declare (ignore time))
  (rontolisp::%java-refused
   "a thread waits for a time and no other can go on: a reactor answers within its host's call, where no time passes, so the experimental Java subset ends the instance here"))

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
;; by a wait, so a program with neither names no sleep. Under a component it is what
;; asks the host for a timer (rontolisp::%java-thread-arm, below).
#+rontolisp-wasm (defvar rontolisp::%java-pausing nil)

;; How many more turns of a loop a thread takes before it lets another go on.
#+rontolisp-wasm (defvar rontolisp::%java-slice 1024)

;; What a started server gives the scheduler where no thread can go on: a function of
;; the soonest time a thread waits for, or nil. It serves the next request, waiting
;; for it until that time at most, and answers t when it did; :host where a host
;; calls the program for each request, so there is nothing to wait for here. Under a
;; component it is t while a server runs.
#+rontolisp-wasm (defvar rontolisp::%java-serving nil)

;; Under a component: how many replies and moments of the host's the holders hold
;; between them (rontolisp::%java-hold, below).
#+rontolisp-component (defvar rontolisp::%java-waits 0)

;; While threads wait for sockets (a --native module): a function of milliseconds --
;; 0 not to wait, -1 for no limit -- and a server of the runner's or nil, that waits
;; in the host until a socket one of them waits on has something, the server's next
;; request has arrived or the time has passed; it lets the thread waiting for the
;; socket go on and answers whether one went on, :request for the request.
#+(and rontolisp-wasm (not rontolisp-component))
(defvar rontolisp::%java-polling nil)

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

;; The threads whose time is up join the line, and those whose socket has something;
;; answers the soonest time one still waits for, or nil.
#+rontolisp-wasm
(defun rontolisp::%java-thread-due ()
  #-rontolisp-component
  (if rontolisp::%java-polling (funcall rontolisp::%java-polling 0 nil))
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

;; Whether another thread can go on now. Under a component the host counts as one
;; while it may have something for the program -- a request of a server's, a reply --
;; so a thread turning or yielding for ever lets it in (rontolisp::%java-thread-pump).
#+rontolisp-wasm
(defun rontolisp::%java-thread-others ()
  (rontolisp::%java-thread-due)
  (if (or rontolisp::%java-ready rontolisp::%java-ready-last
          #+rontolisp-component rontolisp::%java-serving
          #+rontolisp-component (> rontolisp::%java-waits 0))
      t
      nil))

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
  (setq rontolisp::%java-pausing
        #-rontolisp-component (function rontolisp::%java-sleep-until)
        #+rontolisp-component (function rontolisp::%java-thread-arm))
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

;; The milliseconds until SOONEST, rounded up: nil for no time.
#+(and rontolisp-wasm (not rontolisp-component))
(defun rontolisp::%java-millis-until (soonest)
  (if soonest
      (let ((left (- soonest (%nano-time))))
        (if (> left 0) (+ 1 (truncate left 1000000)) 0))
      nil))

;; Where no thread can go on and threads wait for sockets: the wait in the host until
;; one of the sockets has something or SOONEST has come -- not here while a server
;; runs, whose wait for its next request waits for the sockets too; whether a thread
;; can go on now.
#+(and rontolisp-wasm (not rontolisp-component))
(defun rontolisp::%java-thread-polled (soonest)
  (if (and rontolisp::%java-polling (not rontolisp::%java-serving))
      (funcall rontolisp::%java-polling
               (or (rontolisp::%java-millis-until soonest) -1) nil)
      nil))

;; The end of the program: the threads take their turns until those that are no
;; daemons have ended and no server runs. With none that can go on, the threads
;; waiting for sockets are waited for, a server's next request is served -- a request
;; is a thread, below --, or the program's start ends here where a host calls it for
;; each request; else the time passes until a thread can go on.
#+(and rontolisp-wasm (not rontolisp-component))
(defun rontolisp::%java-thread-finish ()
  (let ((going t))
    (while (and going (or (> rontolisp::%java-live 0) rontolisp::%java-serving))
      (let ((soonest (rontolisp::%java-thread-due)))
        (cond ((rontolisp::%java-thread-next) nil)
              ((rontolisp::%java-thread-polled soonest) nil)
              (rontolisp::%java-serving
               (if (eq (funcall rontolisp::%java-serving soonest) :host)
                   (setq going nil)))
              (soonest (funcall rontolisp::%java-pausing soonest))
              (t (rontolisp::%java-stuck)))))))

;; The threads take their turns until ENDED, #(ended ..), says so or none can go on:
;; what a caller that is no thread waits with for what a thread ends -- a reactor's
;; call, for the exchange of its request. Answers whether ENDED said so.
#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-native))
(defun rontolisp::%java-thread-run (ended)
  (let ((going t))
    (while (and going (null (aref ended 0)))
      (let ((soonest (rontolisp::%java-thread-due)))
        (cond ((rontolisp::%java-thread-next) nil)
              ;; threads wait for sockets (--host-sockets): the wait is the
              ;; host's, in which the time passes too
              #+rontolisp-host-sockets
              (rontolisp::%java-polling
               (funcall rontolisp::%java-polling
                        (or (rontolisp::%java-millis-until soonest) -1) nil))
              (soonest (funcall rontolisp::%java-pausing soonest))
              (t (setq going nil))))))
  (aref ended 0))

;; A component. The host has timers and replies of its own, and under `wasmtime
;; serve` it calls the program for each request, at once with the others when the
;; one it called before is suspended. So the program never waits in the host while a
;; thread could go on, and never loops over its threads to pass the time: a thread
;; waiting for a time is woken by a timer of the host's, one waiting for a reply by
;; the reply, a request's by the request, and each gives the threads their turns
;; (rontolisp::%java-thread-pump) where the host delivers it.
;;
;; What the host delivers it delivers to the one that asked: the program's start, or
;; the task of the request being answered then. That one is a HOLDER,
;; #(bell replies ended failure responder queue writer task gone answered): the future
;; it waits with while it has nothing to do, how many replies in flight it holds, and
;; -- of a request -- whether its thread ended, what failed its response (a condition
;; the thread left, or the report of a body cut short), the transport's responder,
;; what its threads wrote that the transport was not handed yet (the newest first),
;; the transport's writer once the head went out and the host's task; then whether
;; the host delivers it nothing any more, and whether the response ended. A holder
;; stays in hand until what it holds has come: a request's task ends after its
;; response, once no thread waits for a timer or a reply of its.
#+rontolisp-component
(defvar rontolisp::%java-top (vector nil 0 nil nil nil nil nil nil nil nil))

;; The holders of the requests in hand.
#+rontolisp-component (defvar rontolisp::%java-holders nil)

;; A holder holds one more thing the host is to deliver, or one fewer.
#+rontolisp-component
(defun rontolisp::%java-hold (holder by)
  (%aset holder 1 (+ (aref holder 1) by))
  (setq rontolisp::%java-waits (+ rontolisp::%java-waits by)))

;; The holder whose task the threads take their turns on, while they do.
#+rontolisp-component (defvar rontolisp::%java-host nil)

#+rontolisp-component (defvar rontolisp::%java-pumping nil)

;; The futures of the holders told to look again while the threads took their turns:
;; settled once they have, each on its own task.
#+rontolisp-component (defvar rontolisp::%java-rung nil)

;; The timer that wakes the scheduler, (deadline . holder), or nil.
#+rontolisp-component (defvar rontolisp::%java-armed nil)

#+rontolisp-component
(defun rontolisp::%java-holder-of (task)
  (if (null task)
      (if (aref rontolisp::%java-top 8) nil rontolisp::%java-top)
      (let ((holders rontolisp::%java-holders) (found nil))
        (while (and holders (null found))
          (if (eq (aref (car holders) 7) task) (setq found (car holders)))
          (setq holders (cdr holders)))
        found)))

;; The future a holder with nothing to do waits with.
#+rontolisp-component
(defun rontolisp::%java-bell (holder)
  (let ((bell (rontolisp::%future-new)))
    (%aset holder 0 bell)
    bell))

;; A holder is told to look again.
#+rontolisp-component
(defun rontolisp::%java-ring (holder)
  (let ((bell (aref holder 0)))
    (if bell
        (progn
          (%aset holder 0 nil)
          (if rontolisp::%java-pumping
              (setq rontolisp::%java-rung (cons bell rontolisp::%java-rung))
              (rontolisp::%future-settle bell nil))))))

;; Whether a thread of the program waits for what the host delivers to this holder.
#+rontolisp-component
(defun rontolisp::%java-carrying (holder)
  (or (> (aref holder 1) 0)
      (and rontolisp::%java-armed (eq (cdr rontolisp::%java-armed) holder))))

;; A timer of the host's: the threads whose time is up take their turns when it
;; fires, wherever the others are.
#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-thread-alarm (armed millis)
  (rontolisp:await (rontolisp:wait-for millis))
  (if (eq rontolisp::%java-armed armed) (setq rontolisp::%java-armed nil))
  (rontolisp::%java-thread-pump)
  (rontolisp::%java-ring (cdr armed)))

#+rontolisp-component
(defun rontolisp::%java-thread-arm (deadline holder)
  (let ((left (- deadline (%nano-time)))
        (armed (cons deadline holder))
        (was rontolisp::%java-armed))
    (setq rontolisp::%java-armed armed)
    (if was (rontolisp::%java-ring (cdr was)))
    (rontolisp::%java-thread-alarm armed
     (if (> left 0) (+ 1 (truncate left 1000000)) 0))))

;; Once no thread can go on: a timer for the soonest time one waits for, unless one
;; that fires no later is armed with a holder the host still delivers to.
#+rontolisp-component
(defun rontolisp::%java-thread-timed (soonest)
  (let ((armed rontolisp::%java-armed))
    (if (and armed (or (null soonest) (aref (cdr armed) 8)))
        (progn
          (setq rontolisp::%java-armed nil)
          (rontolisp::%java-ring (cdr armed))
          (setq armed nil)))
    (if (and soonest rontolisp::%java-host
             (or (null armed) (< soonest (car armed))))
        (funcall rontolisp::%java-pausing soonest rontolisp::%java-host))))

;; Threads that go on turning give the host a moment once in a while, when it may
;; have something for the program: one holder holds a timer of one millisecond, and
;; the threads take their turns again when it fires.
#+rontolisp-component (defvar rontolisp::%java-napping nil)

#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-thread-nap (holder)
  (setq rontolisp::%java-napping holder)
  (rontolisp::%java-hold holder 1)
  (rontolisp:await (rontolisp:wait-for 1))
  (rontolisp::%java-hold holder -1)
  (if (eq rontolisp::%java-napping holder) (setq rontolisp::%java-napping nil))
  (rontolisp::%java-thread-pump)
  (rontolisp::%java-ring holder))

;; The threads that can go on take their turns until none can -- or, in a program
;; that serves nothing, until those left are daemons; or for some ten milliseconds
;; when the host may have something for the program meanwhile. Their frames belong to
;; no task of the host's (rontolisp::%task-swap): a thread outlives the request it
;; was started under and is resumed wherever the host delivers what it waits for.
#+rontolisp-component
(defun rontolisp::%java-thread-pump ()
  (if (null rontolisp::%java-pumping)
      (let ((task (rontolisp::%task-swap nil)) (going t) (turns 0) (since nil))
        (setq rontolisp::%java-pumping t)
        (setq rontolisp::%java-host (rontolisp::%java-holder-of task))
        (while going
          (let ((soonest (rontolisp::%java-thread-due)))
            (cond ((and (eq rontolisp::%java-host rontolisp::%java-top)
                        (not
                         (or (> rontolisp::%java-live 0)
                             rontolisp::%java-serving)))
                   (setq going nil))
                  ((not (rontolisp::%java-thread-next))
                   (setq going nil)
                   (rontolisp::%java-thread-timed soonest))
                  ((and
                    (or rontolisp::%java-serving (> rontolisp::%java-waits 0))
                    rontolisp::%java-host (> (setq turns (+ turns 1)) 64))
                   (setq turns 0)
                   (cond ((null since) (setq since (%nano-time)))
                         ((> (- (%nano-time) since) 10000000)
                          (setq going nil)
                          (rontolisp::%java-thread-timed soonest)
                          (if (or (null rontolisp::%java-napping)
                                  (aref rontolisp::%java-napping 8))
                              (rontolisp::%java-thread-nap
                               rontolisp::%java-host))))))))
        (setq rontolisp::%java-host nil)
        (setq rontolisp::%java-pumping nil)
        (rontolisp::%task-swap task)
        (let ((rung rontolisp::%java-rung))
          (setq rontolisp::%java-rung nil)
          (while rung
            (rontolisp::%future-settle (car rung) nil)
            (setq rung (cdr rung))))))
  nil)

;; The end of the program: the threads take their turns, and where none can go on
;; the program waits in the host for the timer or the reply one waits for, until
;; those that are no daemons have ended. A program serving returns to its host
;; instead, which calls it for each request: what its start held is no longer
;; delivered.
#+rontolisp-component
(defun rontolisp::%java-thread-finish ()
  (let ((going t) (top rontolisp::%java-top))
    (while going
      (rontolisp::%java-thread-pump)
      (cond ((not (or (> rontolisp::%java-live 0) rontolisp::%java-serving))
             (setq going nil))
            (rontolisp::%java-serving
             (%aset top 8 t)
             (setq going nil))
            ((rontolisp::%java-carrying top)
             (rontolisp::%future-force (rontolisp::%java-bell top)))
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

;; The entry of a program that starts threads and registered something for its end --
;; a shutdown hook, a file to delete: as rontolisp::%java-thread-main, with END called
;; once the threads that are no daemons have ended, before the status.
#-rontolisp-wasm
(defun rontolisp::%java-thread-ending (main finish trace end)
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
    (funcall end)
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
  ;; what main asks of the host before it first waits is held by the program's start
  #+rontolisp-component (setq rontolisp::%java-host rontolisp::%java-top)
  (rontolisp::%java-thread-first main finish trace)
  #+rontolisp-component (setq rontolisp::%java-host nil)
  (rontolisp::%java-thread-finish)
  (if rontolisp::%java-failed
      (progn
        (finish-output)
        (finish-output *error-output*)
        (%host-exit 1))))

;; END is a body the threads take turns with, on the carrier of the thread the program
;; started on, so that it can wait for the hooks; not while a server goes on serving.
#+rontolisp-wasm
(defun rontolisp::%java-thread-ending (main finish trace end)
  (setq rontolisp::%java-live 1)
  (let ((carrier (rontolisp::%java-self)))
    #+rontolisp-component (setq rontolisp::%java-host rontolisp::%java-top)
    (rontolisp::%java-thread-first main finish trace)
    #+rontolisp-component (setq rontolisp::%java-host nil)
    (rontolisp::%java-thread-finish)
    (if (not rontolisp::%java-serving)
        (progn
          (rontolisp::%java-thread-start carrier nil end)
          (rontolisp::%java-thread-finish))))
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
          (setf (gethash object rontolisp::%java-monitors) monitor)
          (setq rontolisp::%java-monitors-held
                (+ rontolisp::%java-monitors-held 1))))
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
      ;; a thread that finds the monitor another's says so while it waits for it
      (let ((owner (aref monitor 1)))
        (if (and owner (not (eq owner carrier)))
            (progn
              (%aset carrier 5 t)
              (rontolisp:mutex-acquire (aref monitor 0))
              (%aset carrier 5 nil))
            (rontolisp:mutex-acquire (aref monitor 0))))
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
                   (> rontolisp::%java-monitors-held 4096))
              (progn
                (rontolisp:mutex-acquire rontolisp::%java-guard)
                (remhash (aref monitor 4) rontolisp::%java-monitors)
                (%aset monitor 4 nil)
                (setq rontolisp::%java-monitors-held
                      (- rontolisp::%java-monitors-held 1))
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
  (setq rontolisp::%java-pausing
        #-rontolisp-component (function rontolisp::%java-sleep-until)
        #+rontolisp-component (function rontolisp::%java-thread-arm))
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

;; A reply all of whose body is in, #(status fields octets): the fields an array of
;; names and values in turn, as fetch lists them; the chunks the newest first.
(defun rontolisp::%java-http-answered (reply chunks total)
  (let ((flat nil))
    (dolist (field (getf reply :headers))
      (setq flat (cons (cdr field) (cons (car field) flat))))
    (vector (getf reply :status) (coerce (reverse flat) 'simple-vector)
            (if chunks
                (rontolisp::%octets-join (reverse chunks) total)
                (make-array 0 :element-type '(unsigned-byte 8))))))

;; The reply of an exchange once all of it is in, or the text of what failed: the
;; request refused at the call, the transport, the transfer.
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
                      (rontolisp::%java-http-answered reply chunks total))
        (error (condition) (princ-to-string condition)))))

;; The exchange of a request sent, around the future fetch answered.
(defun rontolisp::%java-http-exchange (future) (vector future))

;; A thread of a program that starts threads on one stack: the threads that can go on
;; take a turn before the reply is waited for, and none goes on while it is.
#+(and rontolisp-wasm (not rontolisp-component))
(defun rontolisp::%java-http-launched (future) (vector future))

#+(and rontolisp-wasm (not rontolisp-component))
(rontolisp:async-defun rontolisp::%java-suspending-http-reply (exchange)
  (if (rontolisp::%java-thread-others)
      (rontolisp:await
       (rontolisp::%java-thread-queued (rontolisp::%java-self))))
  (rontolisp::%java-http-reply exchange))

;; A component, a program whose threads take turns: the exchange is
;; #(future reply waiting), and its reply is read as the host delivers it, from the
;; request on, whatever the threads do meanwhile -- the reply once all of it is in,
;; or the text of what failed, then the threads waiting for it go on. The holder
;; the request was sent under holds it until then.
#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-http-awaited (future)
  (handler-case (let ((reply (rontolisp:await future)))
                  (let ((body (getf reply :body))
                        (chunks nil)
                        (total 0)
                        (reading t))
                    (while reading
                      (let ((chunk
                             (rontolisp:await (rontolisp:stream-read body))))
                        (if (null chunk)
                            (setq reading nil)
                            (progn
                              (setq total (+ total (length chunk)))
                              (setq chunks (cons chunk chunks))))))
                    (rontolisp::%java-http-answered reply chunks total)))
    (error (condition) (princ-to-string condition))))

#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-http-reading (exchange holder)
  (let ((reply
         (rontolisp:await (rontolisp::%java-http-awaited (aref exchange 0)))))
    (%aset exchange 1 reply)
    (if holder (rontolisp::%java-hold holder -1))
    (let ((waiting (aref exchange 2)))
      (%aset exchange 2 nil)
      (while waiting
        (rontolisp::%java-thread-ready (car waiting))
        (setq waiting (cdr waiting))))
    (rontolisp::%java-thread-pump)
    (if holder (rontolisp::%java-ring holder))
    nil))

#+rontolisp-component
(defun rontolisp::%java-http-launched (future)
  (let ((exchange (vector future nil nil)) (holder rontolisp::%java-host))
    (if holder (rontolisp::%java-hold holder 1))
    (rontolisp::%java-http-reading exchange holder)
    exchange))

;; The thread waits for the reply among the threads that cannot go on.
#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-suspending-http-reply (exchange)
  (if (stringp exchange)
      exchange
      (let ((carrier (rontolisp::%java-self)))
        (while (null (aref exchange 1))
          (if (not (member carrier (aref exchange 2)))
              (%aset exchange 2 (cons carrier (aref exchange 2))))
          (rontolisp:await (rontolisp::%java-thread-park carrier nil)))
        (aref exchange 1))))

;; --- java.net.Socket and ServerSocket --------------------------------------------------

;; The program's own TCP sockets (rontolisp:tcp-*) as the library's
;; rontolisp.internal.Sockets reaches them. A socket is the handle the host answered,
;; and a call that fails answers the text of why -- the operator's words, then the
;; host's -- of which the Java makes the exception a JDK throws. A connect, an accept
;; and a read are where a thread waits: a host's thread blocks in them, and where the
;; threads of a program take turns on one stack the lowering calls the suspending
;; functions below instead (JavaResumable), which wait among the threads.

;; Why the host answered nil for a connect, a listen or an accept, in the words a JDK
;; reports the host's reason with; HOST where the name was not found. Only a
;; component answers nil: the other targets signal with the host's words.
#-rontolisp-component
(defun rontolisp::%java-socket-why (host) "no socket was made")

#+rontolisp-component
(defun rontolisp::%java-socket-why (host)
  (let ((code rontolisp::*sock-last-error*))
    (cond ((eq code :connection-refused) "Connection refused")
          ((eq code :address-in-use) "Address already in use")
          ((eq code :remote-unreachable) "No route to host")
          ((eq code :timeout) "Connection timed out")
          ((eq code :connection-reset) "Connection reset")
          ((eq code :connection-aborted) "Software caused connection abort")
          ((eq code :connection-broken) "Broken pipe")
          ((eq code :access-denied) "Permission denied")
          ((eq code :invalid-argument) "Invalid argument")
          ((eq code :address-not-bindable) "Cannot assign requested address")
          ((eq code :not-supported) "Operation not supported")
          ((eq code :connect-timed-out) "Connect timed out")
          ((eq code :closed) "Socket closed")
          ((or (eq code :name-unresolvable)
               (eq code :temporary-resolver-failure)
               (eq code :permanent-resolver-failure))
           (or host "no address"))
          (code (princ-to-string code))
          (t "no socket was made"))))

;; The text of a failure: what the operator SAID, HOST:PORT -- * for a nil HOST --
;; and WHY.
(defun rontolisp::%java-socket-said (said host port why)
  (%string-concat (%string-concat (%string-concat said (or host "*"))
                                  (%string-concat ":" (%decimal-piece port)))
                  (%string-concat ": " why)))

;; What a connect, a listen or a bind made, or, for a component's nil, the text of
;; why not.
(defun rontolisp::%java-socket-made (made said host port)
  (if made
      made
      (rontolisp::%java-socket-said said host port
                                    (rontolisp::%java-socket-why host))))

;; A socket not connected yet, bound to HOST:PORT for a PORT not below 0 -- every
;; interface for a nil HOST -- else not bound; or the text of why there is none.
(defun rontolisp::%java-socket-make (host port)
  (handler-case (rontolisp::%java-socket-made
                 (rontolisp::%tcp-socket host (if (< port 0) nil port))
                 "%tcp-socket: cannot bind to " host port)
    (error (condition) (princ-to-string condition))))

;; SOCKET connected to HOST:PORT -- a name the host looks up -- within MILLIS, 0 for
;; as long as the host's connect waits: the socket, or the text of why not.
(defun rontolisp::%java-socket-connect (socket host port millis)
  (handler-case (rontolisp::%java-socket-made (rontolisp::%tcp-connect-socket
                                               socket host port
                                               (if (> millis 0) millis nil))
                                              "tcp-connect: cannot connect to "
                                              host port)
    (error (condition) (princ-to-string condition))))

;; A listener on HOST:PORT, on every interface for a nil HOST.
(defun rontolisp::%java-socket-listen (host port)
  (handler-case (rontolisp::%java-socket-made (rontolisp:tcp-listen port host)
                                              "tcp-listen: cannot listen on "
                                              host port)
    (error (condition) (princ-to-string condition))))

(defun rontolisp::%java-socket-accepted (made)
  (if made
      made
      (%string-concat "tcp-accept: " (rontolisp::%java-socket-why nil))))

;; The next connection LISTENER takes.
(defun rontolisp::%java-socket-accept (listener)
  (handler-case (rontolisp::%java-socket-accepted
                 (rontolisp:tcp-accept listener))
    (error (condition) (princ-to-string condition))))

;; At least one byte of SOCKET into BYTES from START once one has come, then what
;; else has come, at most WANTED in all: the count, -1 at the end, or the text of
;; what failed. One read of the host's (rontolisp::%tcp-read-some).
(defun rontolisp::%java-socket-read (socket bytes start wanted)
  (handler-case (let ((at
                       (rontolisp::%tcp-read-some socket bytes start
                                                  (+ start wanted) t)))
                  (if (= at start) -1 (- at start)))
    (error (condition) (princ-to-string condition))))

;; What has come of SOCKET with no wait: the count, or the text of what failed.
(defun rontolisp::%java-socket-take (socket bytes start wanted)
  (handler-case (- (rontolisp::%tcp-read-some socket bytes start
                                              (+ start wanted) nil) start)
    (error (condition) (princ-to-string condition))))

;; COUNT bytes of BYTES from START sent before it answers, as a JDK's socket sends
;; what it is given: nil, or the text of what failed.
(defun rontolisp::%java-socket-write (socket bytes start count)
  (handler-case (progn
                  (write-sequence bytes socket
                                  :start start
                                  :end (+ start count))
                  (force-output socket)
                  nil)
    (error (condition) (princ-to-string condition))))

;; Half of SOCKET ended, its output for a non-nil OUTPUT, else its input: nil, or
;; the text of what failed.
(defun rontolisp::%java-socket-shutdown (socket output)
  (handler-case (rontolisp::%tcp-shutdown socket output)
    (error (condition) (princ-to-string condition))))

;; OPTION of SOCKET given to the host, or a byte of urgent data sent
;; (LispNames.TCP_OPTION_INTERNAL): nil, or the text of what failed.
(defun rontolisp::%java-socket-option (socket option value)
  (handler-case (rontolisp::%tcp-option socket option value)
    (error (condition) (princ-to-string condition))))

;; The read deadline of SOCKET, or the accept deadline of a listener, in
;; milliseconds, 0 for none.
(defun rontolisp::%java-socket-deadline (socket millis)
  (handler-case (progn
                  (rontolisp:tcp-set-timeout socket
                                             (if (> millis 0) millis nil))
                  nil)
    (error (condition) (princ-to-string condition))))

(defun rontolisp::%java-socket-close (handle)
  (handler-case (close handle) (error (condition) nil))
  nil)

(defun rontolisp::%java-socket-local-port (handle)
  (or (handler-case (rontolisp:tcp-local-port handle) (error (condition) nil))
      -1))

(defun rontolisp::%java-socket-local-address (handle)
  (handler-case (rontolisp:tcp-local-address handle) (error (condition) nil)))

(defun rontolisp::%java-socket-peer-address (handle)
  (handler-case (rontolisp:tcp-peer-address handle) (error (condition) nil)))

(defun rontolisp::%java-socket-peer-port (handle)
  (or (handler-case (rontolisp:tcp-peer-port handle) (error (condition) nil))
      0))

;; Preview 1, and a reactor without its host's sockets: no socket is made, so no
;; thread waits for one.
#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-native) (not rontolisp-host-sockets))
(rontolisp:async-defun rontolisp::%java-suspending-socket-connect
    (socket host port millis)
  (rontolisp::%java-socket-connect socket host port millis))

#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-native) (not rontolisp-host-sockets))
(rontolisp:async-defun rontolisp::%java-suspending-socket-accept (listener)
  (rontolisp::%java-socket-accept listener))

#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-native) (not rontolisp-host-sockets))
(rontolisp:async-defun rontolisp::%java-suspending-socket-read
    (socket bytes start wanted)
  (rontolisp::%java-socket-read socket bytes start wanted))

;; A --native module: every call of the runner's sockets blocks the module's one
;; thread, so a thread waits for a socket among the others -- until the runner says
;; the socket has something for a read or an accept (a byte, the peer's close, a
;; connection), that its connect has ended, or the thread's time limit has come --
;; and where no thread can go on the scheduler waits in the runner for any socket a
;; thread waits on, until the soonest time a thread waits for
;; (rontolisp::%java-polling). A connect begins with no wait and ends once the
;; socket can be written to. A reactor with its host's sockets (--host-sockets)
;; is the same program over the other host: the module is suspended in that wait,
;; and the host has something for it only then.

;; The threads waiting for a socket, (carrier . socket), the first to wait first.
#+(or rontolisp-native rontolisp-host-sockets)
(defvar rontolisp::%java-sockets nil)

;; Whether SOCKET has something for the thread, which waits among the others until it
;; has or DEADLINE, a time of %nano-time's or nil, has come: nil for the deadline.
#+(or rontolisp-native rontolisp-host-sockets)
(rontolisp:async-defun rontolisp::%java-socket-ready (socket &optional deadline)
  (if (rontolisp::%sock-wait (list socket) 0 nil)
      t
      (let ((entry (cons (rontolisp::%java-self) socket)))
        (setq rontolisp::%java-sockets
              (append rontolisp::%java-sockets (list entry)))
        (setq rontolisp::%java-polling (function rontolisp::%java-socket-poll))
        ;; an interrupt wakes it, and leaves it waiting, as a JDK's read goes on
        (while (and (member entry rontolisp::%java-sockets)
                    (or (null deadline) (< (%nano-time) deadline)))
          (rontolisp:await (rontolisp::%java-thread-park (car entry) deadline)))
        (if (member entry rontolisp::%java-sockets)
            (progn
              (setq rontolisp::%java-sockets
                    (remove entry rontolisp::%java-sockets))
              (if (null rontolisp::%java-sockets)
                  (setq rontolisp::%java-polling nil))
              nil)
            t))))

;; The time MILLIS from now, nil for none.
#+(or rontolisp-native rontolisp-host-sockets)
(defun rontolisp::%java-socket-deadline-of (millis)
  (if (and millis (> millis 0)) (+ (%nano-time) (* millis 1000000)) nil))

;; Waits up to MILLIS (0: not at all, -1: no limit) for a socket a thread waits on to
;; have something, or for SERVER's next request; the thread waiting longest on a
;; socket that has goes on, and the answer is whether one had, :request when the
;; request came first.
#+(or rontolisp-native rontolisp-host-sockets)
(defun rontolisp::%java-socket-poll (millis server)
  (let* ((sockets (mapcar (function cdr) rontolisp::%java-sockets))
         (at (rontolisp::%sock-wait sockets millis server)))
    (cond ((null at) nil)
          ((= at (length sockets)) :request)
          (t (let ((entry (nth at rontolisp::%java-sockets)))
               (setq rontolisp::%java-sockets
                     (remove entry rontolisp::%java-sockets))
               (if (null rontolisp::%java-sockets)
                   (setq rontolisp::%java-polling nil))
               (rontolisp::%java-thread-ready (car entry))
               t)))))

;; The connect of SOCKET begun with no wait: t once connected, nil while it goes on,
;; or the text of what failed.
#+(or rontolisp-native rontolisp-host-sockets)
(defun rontolisp::%java-socket-begin (socket host port)
  (handler-case (rontolisp::%sock-connect-begin socket host port)
    (error (condition) (princ-to-string condition))))

;; The connect of SOCKET ended once the socket can be written to: the socket, or the
;; text of what failed.
#+(or rontolisp-native rontolisp-host-sockets)
(defun rontolisp::%java-socket-end (socket host port)
  (handler-case (rontolisp::%sock-connect-end socket host port)
    (error (condition) (princ-to-string condition))))

#+(or rontolisp-native rontolisp-host-sockets)
(rontolisp:async-defun rontolisp::%java-suspending-socket-connect
    (socket host port millis)
  (let ((begun (rontolisp::%java-socket-begin socket host port)))
    (cond ((stringp begun) begun)
          (begun socket)
          ((rontolisp:await
            (rontolisp::%java-socket-ready socket
             (rontolisp::%java-socket-deadline-of millis)))
           (rontolisp::%java-socket-end socket host port))
          (t (rontolisp::%java-socket-said "tcp-connect: cannot connect to "
                                           host port "Connect timed out")))))

;; An accept waits among the threads for the listener's accept deadline at most.
#+(or rontolisp-native rontolisp-host-sockets)
(rontolisp:async-defun rontolisp::%java-suspending-socket-accept (listener)
  (if (rontolisp:await
       (rontolisp::%java-socket-ready listener
                                      (rontolisp::%java-socket-deadline-of
                                       (rontolisp::%sock-read-deadline
                                        listener))))
      (rontolisp::%java-socket-accept listener)
      "tcp-accept: Accept timed out"))

;; A read waits among the threads for the socket's read deadline at most.
#+(or rontolisp-native rontolisp-host-sockets)
(rontolisp:async-defun rontolisp::%java-suspending-socket-read
    (socket bytes start wanted)
  (if (rontolisp:await
       (rontolisp::%java-socket-ready socket
                                      (rontolisp::%java-socket-deadline-of
                                       (rontolisp::%sock-read-deadline
                                        socket))))
      (rontolisp::%java-socket-read socket bytes start wanted)
      "read-sequence: Read timed out"))

;; A component: what a thread waits for of the host on a socket -- a connect, an
;; accept, a read's first byte -- is the future sockets.lisp answers, which a frame of
;; its own awaits as a reply's is (rontolisp::%java-http-reading): it keeps what the
;; future came to in CELL, #(done value waiting), readies the threads waiting for it
;; and gives the threads their turns. The holder the wait began under holds it until
;; then.
#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-socket-awaited (future cell holder)
  (let ((value
         (handler-case (rontolisp:await future)
           (error (condition) (princ-to-string condition)))))
    (%aset cell 1 value)
    (%aset cell 0 t)
    (if holder (rontolisp::%java-hold holder -1))
    (let ((waiting (aref cell 2)))
      (%aset cell 2 nil)
      (while waiting
        (rontolisp::%java-thread-ready (car waiting))
        (setq waiting (cdr waiting))))
    (rontolisp::%java-thread-pump)
    (if holder (rontolisp::%java-ring holder))
    nil))

;; FUTURE's value, or the text of what it signalled; the thread waits for it among
;; the threads that cannot go on.
#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-socket-waited (future)
  (if (rontolisp::%future-settled-p future)
      (handler-case (rontolisp::%future-force future)
        (error (condition) (princ-to-string condition)))
      (let ((cell (vector nil nil nil))
            (holder rontolisp::%java-host)
            (carrier (rontolisp::%java-self)))
        (if holder (rontolisp::%java-hold holder 1))
        (rontolisp::%java-socket-awaited future cell holder)
        (while (null (aref cell 0))
          (if (not (member carrier (aref cell 2)))
              (%aset cell 2 (cons carrier (aref cell 2))))
          (rontolisp:await (rontolisp::%java-thread-park carrier nil)))
        (aref cell 1))))

;; Whether a read of SOCKET answers with no wait for the host: bytes are at hand, or
;; the probe failed, which the read reports.
#+rontolisp-component
(defun rontolisp::%java-socket-at-hand (socket)
  (handler-case (listen socket) (error (condition) t)))

#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-suspending-socket-connect
    (socket host port millis)
  (let ((made
         (rontolisp:await
          (rontolisp::%java-socket-waited
           (rontolisp::%tcp-connect-socket-f socket host port
                                             (if (> millis 0) millis nil))))))
    (if (stringp made)
        made
        (rontolisp::%java-socket-made made "tcp-connect: cannot connect to "
                                      host port))))

#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-suspending-socket-accept (listener)
  (let ((made
         (rontolisp:await
          (rontolisp::%java-socket-waited
           (rontolisp::%tcp-accept-f listener)))))
    (if (stringp made) made (rontolisp::%java-socket-accepted made))))

#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-suspending-socket-read
    (socket bytes start wanted)
  (if (rontolisp::%java-socket-at-hand socket)
      (rontolisp::%java-socket-read socket bytes start wanted)
      (let ((first
             (rontolisp:await
              (rontolisp::%java-socket-waited
               (rontolisp::%read-byte-eof-future socket nil -1)))))
        (cond ((stringp first) first)
              ((< first 0) -1)
              (t
               (setf (aref bytes start) first)
               (let ((more
                      (rontolisp::%java-socket-take socket bytes (+ start 1)
                                                    (- wanted 1))))
                 (if (stringp more) more (+ 1 more))))))))

;; --- com.sun.net.httpserver ---------------------------------------------------------

;; The server is Java source (rontolisp.internal.HttpServing) over the transport every
;; adapter serves through, rontolisp::%http-serve-begin, which the lowered program
;; itself calls (http-serve.lisp; .kb/http-server.md). The application it hands over
;; takes Clack's environment and answers Clack's DELAYED response, a function of the
;; transport's responder: an exchange answers through the responder where its handler
;; says so -- a whole response at once, or the head and then a writer of the body's
;; chunks, which a transport that can sends as they are written.

;; The request's facts, off the environment.
(defun rontolisp::%java-http-method (env) (string (getf env :request-method)))

(defun rontolisp::%java-http-target (env) (getf env :request-uri))

(defun rontolisp::%java-http-protocol (env)
  (string (getf env :server-protocol)))

;; The host and the port of the peer (LOCAL nil) or of the server as the request
;; names it: #(host port), each nil where the transport does not know it.
(defun rontolisp::%java-http-end (env local)
  (if local
      (vector (getf env :server-name) (getf env :server-port))
      (vector (getf env :remote-addr) (getf env :remote-port))))

;; The request fields, an array of names and values in turn: the environment's table,
;; names in lower case, a field sent more than once one value.
(defun rontolisp::%java-http-request-fields (env)
  (let ((flat nil))
    (maphash (lambda (name value) (setq flat (cons value (cons name flat))))
             (getf env :headers))
    (coerce (reverse flat) 'simple-vector)))

;; The request body's octets: the transport's buffered body read to its end, none
;; without a body.
(defun rontolisp::%java-http-request-body (env)
  (let ((raw (getf env :raw-body)) (chunks nil) (total 0))
    (if raw
        (let ((reading t))
          (while reading
            (let ((chunk (make-array 4096 :element-type '(unsigned-byte 8)))
                  (n 0)
                  (b 0))
              (while (and (< n 4096) (>= b 0))
                (setq b (read-byte raw nil -1))
                (if (>= b 0)
                    (progn
                      (setf (aref chunk n) b)
                      (setq n (+ n 1)))))
              (if (> n 0)
                  (progn
                    (setq chunks
                     (cons (if (< n 4096) (subseq chunk 0 n) chunk) chunks))
                    (setq total (+ total n))))
              (if (< n 4096) (setq reading nil))))))
    (if chunks
        (rontolisp::%octets-join (reverse chunks) total)
        (make-array 0 :element-type '(unsigned-byte 8)))))

;; An exchange lives until its response ended, whoever ends it: its handler, or any
;; thread once the handler returned, as a JDK's does. HttpServing answers it through
;; what the transport handed the request -- the RESPONDER below -- in four calls: a
;; whole response (%java-http-respond: the status, the fields -- an array of names and
;; values in turn -- and the body's octets), the head of a body in chunks
;; (%java-http-stream, answering the WRITER the next three take), a chunk, the end;
;; and %java-http-cut, the end of a body in chunks whose handler failed, what it threw
;; reported.

;; The socket legs (the interpreter, the JVM): the responder is the transport's reply
;; of the exchange (http-serve.lisp), whose response is kept until it ends and then
;; written whole, by the thread that ends it.
#-rontolisp-wasm
(defun rontolisp::%java-http-stream (reply status flat)
  (rontolisp::%http-server-head reply status
                                (rontolisp::%http-response-headers-alist
                                 (rontolisp::%java-http-fields flat)))
  reply)

#-rontolisp-wasm
(defun rontolisp::%java-http-write (reply octets)
  (rontolisp::%http-server-write reply octets)
  nil)

#-rontolisp-wasm
(defun rontolisp::%java-http-finish (reply)
  (rontolisp::%http-server-finish reply nil)
  nil)

#-rontolisp-wasm
(defun rontolisp::%java-http-respond (reply status flat octets)
  (rontolisp::%java-http-stream reply status flat)
  (rontolisp::%java-http-write reply octets)
  (rontolisp::%java-http-finish reply))

;; The body went nowhere yet: the failure is answered as an application's, the 500 of
;; the socket legs, its report on standard error.
#-rontolisp-wasm
(defun rontolisp::%java-http-cut (reply report)
  (%warn (%string-concat "HTTP-HANDLER: handler failed: " report))
  (rontolisp::%http-server-finish reply t)
  nil)

;; One stack, the transport's responder in hand (a --native module, a reactor): the
;; responder is an EXCHANGE, #(ended failure responder writer answered): whether the
;; response ended -- whole or failed --, the report of what failed it, the
;; transport's responder, the writer its head answered, and on a --native module the
;; future its transport waits for (rontolisp::%java-http-request, below).
#+(and rontolisp-wasm (not rontolisp-component))
(defun rontolisp::%java-http-ended (exchange failure)
  (if (null (aref exchange 0))
      (progn
        (%aset exchange 1 failure)
        (%aset exchange 0 t)
        (if (aref exchange 4)
            (rontolisp::%future-settle (aref exchange 4) nil))))
  nil)

#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-host-timer))
(defun rontolisp::%java-http-respond (exchange status flat octets)
  (funcall (aref exchange 2)
           (list status (rontolisp::%java-http-fields flat) octets))
  (rontolisp::%java-http-ended exchange nil))

#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-host-timer))
(defun rontolisp::%java-http-stream (exchange status flat)
  (%aset exchange 3
         (funcall (aref exchange 2)
                  (list status (rontolisp::%java-http-fields flat))))
  exchange)

#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-host-timer))
(defun rontolisp::%java-http-write (exchange octets)
  (funcall (aref exchange 3) octets)
  nil)

#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-host-timer))
(defun rontolisp::%java-http-finish (exchange)
  (funcall (aref exchange 3) nil :close t)
  (rontolisp::%java-http-ended exchange nil))

#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-host-timer))
(defun rontolisp::%java-http-cut (exchange report)
  (rontolisp::%java-http-ended exchange report))

;; A server's gate: nil while it is bound and not started, then :started, then
;; :stopped. A request that arrives before the start waits for it, as one a JDK's
;; server has not accepted yet does.
(defun rontolisp::%java-http-gate () (vector nil))

#-rontolisp-wasm (defvar rontolisp::%java-http-guard (rontolisp:make-mutex))

#-rontolisp-wasm
(defvar rontolisp::%java-http-turned (rontolisp::%condition-variable-new))

;; The servers started and not stopped, and the ticket the thread at the program's end
;; waits with for the last of them to stop.
#-rontolisp-wasm (defvar rontolisp::%java-http-running 0)

#-rontolisp-wasm (defvar rontolisp::%java-http-waiter nil)

;; A server is bound: from here on the transport's threads run the program beside the
;; thread it started on, so the run time's own tables are read one thread at a time,
;; as in a program that started a thread.
#-rontolisp-wasm
(defun rontolisp::%java-http-shared ()
  (if (null rontolisp::%java-shared)
      (progn
        (setq rontolisp::%java-main (rontolisp::%java-self))
        (setq rontolisp::%java-shared
              (function rontolisp::%java-among-threads))))
  nil)

#+rontolisp-wasm (defun rontolisp::%java-http-shared () nil)

;; A server is started, or stopped: SERVER is the transport's.
#-rontolisp-wasm
(defun rontolisp::%java-http-turn (gate server state)
  (rontolisp:mutex-acquire rontolisp::%java-http-guard)
  (let ((was (aref gate 0)) (waiter nil))
    (%aset gate 0 state)
    (if (eq state :started)
        (setq rontolisp::%java-http-running
              (+ rontolisp::%java-http-running 1)))
    (if (and (eq state :stopped) (eq was :started))
        (progn
          (setq rontolisp::%java-http-running
                (- rontolisp::%java-http-running 1))
          (setq waiter rontolisp::%java-http-waiter)))
    (rontolisp::%condition-notify rontolisp::%java-http-turned t)
    (rontolisp:mutex-release rontolisp::%java-http-guard)
    (if waiter (rontolisp::%java-unpark (car waiter) waiter nil)))
  nil)

;; One stack: while the server runs, the scheduler serves where no thread can go on
;; (rontolisp::%java-serving). The transport takes its limit in milliseconds; while
;; threads wait for sockets (a --native module) the one wait in the runner is for the
;; request or a socket, whichever comes first, and a socket's thread goes on first.
#+rontolisp-component
(defun rontolisp::%java-http-turn (gate server state)
  (%aset gate 0 state)
  (setq rontolisp::%java-serving (eq state :started))
  nil)

#+(and rontolisp-wasm (not rontolisp-component))
(defun rontolisp::%java-http-turn (gate server state)
  (%aset gate 0 state)
  (setq rontolisp::%java-serving
        (if (eq state :started)
            (lambda (soonest)
              (let ((limit (rontolisp::%java-millis-until soonest)))
                #+rontolisp-native
                (if rontolisp::%java-polling
                    (let ((got
                           (funcall rontolisp::%java-polling (or limit -1)
                                    (rontolisp::%http-serve-number server))))
                      (if (eq got :request)
                          (rontolisp::%http-serve-step server 0)
                          got))
                    (rontolisp::%http-serve-step server limit))
                #-rontolisp-native (rontolisp::%http-serve-step server limit)))
            nil))
  nil)

;; Whether a request is let in: once the server was started, not once it was stopped.
#-rontolisp-wasm
(defun rontolisp::%java-http-admitted (gate)
  (rontolisp:mutex-acquire rontolisp::%java-http-guard)
  (while (null (aref gate 0))
    (rontolisp::%condition-wait rontolisp::%java-http-turned
                                rontolisp::%java-http-guard nil))
  (rontolisp:mutex-release rontolisp::%java-http-guard)
  (eq (aref gate 0) :started))

#+rontolisp-wasm
(defun rontolisp::%java-http-admitted (gate) (eq (aref gate 0) :started))

;; One request on the transport's thread, THUNK a function of the responder. The run
;; time never started that thread, so its carrier is bound here -- without it every
;; request would hold a monitor as the thread the program started on does --,
;; holding the name of the Thread a handler asking for it is given.
#-rontolisp-wasm
(defun rontolisp::%java-http-request (thunk responder)
  (let ((rontolisp::%java-carrier
         (rontolisp::%java-thread-carrier "HTTP-Dispatcher")))
    (funcall thunk responder)))

;; One stack: a request is a thread of the scheduler's, so its handler can wait for
;; the program's other threads. What the request left uncaught fails its exchange.
#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-host-timer))
(rontolisp:async-defun rontolisp::%java-http-thread (carrier thunk exchange)
  (rontolisp:await (rontolisp::%java-thread-queued carrier))
  (handler-case (rontolisp:await (funcall thunk exchange))
    (error (condition) (rontolisp::%java-http-ended exchange condition))))

;; What failed an exchange, signalled: a report, or the condition its thread left.
#+(and rontolisp-wasm (not rontolisp-component))
(defun rontolisp::%java-http-failed (exchange)
  (let ((failure (aref exchange 1)))
    (cond ((null failure) nil)
          ((stringp failure) (error "~A" failure))
          (t (error failure)))))

;; A --native module: the transport's call answers the future of the exchange's end at
;; once, and the module goes on -- its threads take their turns, its server takes the
;; next requests -- while the request is in hand: the transport answers once that
;; future settles, from the stack of whoever ended the exchange.
#+rontolisp-native
(rontolisp:async-defun rontolisp::%java-http-ending (exchange)
  (if (null (aref exchange 0)) (rontolisp:await (aref exchange 4)))
  (rontolisp::%java-http-failed exchange)
  nil)

;; A reactor: the host's call answers once the exchange ended, the threads taking
;; their turns meanwhile; one no thread of the call can end is answered as an
;; exchange left unanswered, a 500, or, once its head went out, as one that failed.
;; A thread outlives the request that started it and is resumed by whichever request
;; runs the scheduler next, so the frames made here belong to no task of the host's
;; (rontolisp::%task-swap): one owned by a request long answered would wait for that
;; request to resume it.
#+(and rontolisp-wasm (not rontolisp-component) (not rontolisp-host-timer))
(defun rontolisp::%java-http-request (thunk responder)
  (let ((exchange
         (vector nil nil responder nil
                 #+rontolisp-native (rontolisp::%future-new)
                 #-rontolisp-native nil))
        (task (rontolisp::%task-swap nil)))
    (unwind-protect (progn
                      (rontolisp::%java-http-thread
                       (rontolisp::%java-thread-carrier "HTTP-Dispatcher") thunk
                       exchange)
                      #-rontolisp-native
                      (if (not (rontolisp::%java-thread-run exchange))
                          (if (aref exchange 3)
                              (rontolisp::%java-http-ended exchange
                               "the exchange was left unanswered")
                              (progn
                                (funcall responder (list 500 nil nil))
                                (rontolisp::%java-http-ended exchange nil)))))
      (rontolisp::%task-swap task))
    #+rontolisp-native (rontolisp::%java-http-ending exchange)
    #-rontolisp-native (rontolisp::%java-http-failed exchange)))

;; A reactor whose host waits for it (--host-timer): the call goes on until its
;; exchange ended, and where no thread can go on it IDLES in the host, which calls the
;; program for other requests meanwhile, until it is rung -- or, keeping the time for
;; the threads that wait for one, until the soonest such time. Any thread may end an
;; exchange, a request's or another's, but only the exchange's own call has its
;; request's body and response at the host: what a thread answers is queued in the
;; exchange, done where its call runs, and rings the call if it idles. A thread
;; outlives the request that started it and takes its turns in whichever call runs.
;;
;; The exchange is #(ended failure responder writer nil queue token idle): as above,
;; then what the threads answered and the call has not done yet (functions, the newest
;; first), the number the host knows the call by, and whether the call idles now.

;; The exchanges whose calls the host has in hand, the last number given one, and the
;; call that keeps the time, (deadline . exchange): the one idling until the soonest
;; time a thread waits for.
#+rontolisp-host-timer (defvar rontolisp::%java-calls nil)

#+rontolisp-host-timer (defvar rontolisp::%java-tokens 0)

#+rontolisp-host-timer (defvar rontolisp::%java-keeper nil)

;; A call that idles is told to look again.
#+rontolisp-host-timer
(defun rontolisp::%java-ring (exchange)
  (if (aref exchange 7)
      (progn
        (%aset exchange 7 nil)
        (rontolisp::%host-ring (aref exchange 6))))
  nil)

;; What a thread answers of an exchange, done by the exchange's own call.
#+rontolisp-host-timer
(defun rontolisp::%java-http-later (exchange thunk)
  (%aset exchange 5 (cons thunk (aref exchange 5)))
  (rontolisp::%java-ring exchange))

#+rontolisp-host-timer
(defun rontolisp::%java-http-respond (exchange status flat octets)
  (let ((fields (rontolisp::%java-http-fields flat)))
    (rontolisp::%java-http-later exchange
                                 (lambda ()
                                   (funcall (aref exchange 2)
                                            (list status fields octets))
                                   (rontolisp::%java-http-ended exchange
                                                                nil)))))

#+rontolisp-host-timer
(defun rontolisp::%java-http-stream (exchange status flat)
  (let ((fields (rontolisp::%java-http-fields flat)))
    (rontolisp::%java-http-later exchange
                                 (lambda ()
                                   (%aset exchange 3
                                          (funcall (aref exchange 2)
                                                   (list status fields))))))
  exchange)

#+rontolisp-host-timer
(defun rontolisp::%java-http-write (exchange octets)
  (rontolisp::%java-http-later exchange
                               (lambda () (funcall (aref exchange 3) octets))))

#+rontolisp-host-timer
(defun rontolisp::%java-http-finish (exchange)
  (rontolisp::%java-http-later exchange
                               (lambda ()
                                 (funcall (aref exchange 3) nil :close t)
                                 (rontolisp::%java-http-ended exchange nil))))

#+rontolisp-host-timer
(defun rontolisp::%java-http-cut (exchange report)
  (rontolisp::%java-http-later exchange
   (lambda () (rontolisp::%java-http-ended exchange report))))

;; A request is a thread of the scheduler's; what it leaves uncaught fails its
;; exchange.
#+rontolisp-host-timer
(rontolisp:async-defun rontolisp::%java-http-thread (carrier thunk exchange)
  (rontolisp:await (rontolisp::%java-thread-queued carrier))
  (handler-case (rontolisp:await (funcall thunk exchange))
    (error (condition)
      (rontolisp::%java-http-later exchange
       (lambda () (rontolisp::%java-http-ended exchange condition))))))

;; The call has nothing to do: it idles in the host until it is rung -- or, keeping
;; the time, until the soonest time a thread waits for, where no call idling waits for
;; a sooner one.
#+rontolisp-host-timer
(defun rontolisp::%java-idle (exchange)
  (let ((soonest (rontolisp::%java-thread-due))
        (keeper rontolisp::%java-keeper))
    (if (and soonest (or (null keeper) (< soonest (car keeper))))
        (setq rontolisp::%java-keeper (cons soonest exchange)))
    (%aset exchange 7 t)
    (rontolisp::%host-idle (aref exchange 6)
     (if (eq (cdr rontolisp::%java-keeper) exchange) soonest 0))
    (%aset exchange 7 nil)
    (if (eq (cdr rontolisp::%java-keeper) exchange)
        (setq rontolisp::%java-keeper nil))))

;; A call that leaves the threads something to do hands it on: a call idling is rung
;; to take their turns, or the time where no call idling keeps a soon enough one.
#+rontolisp-host-timer
(defun rontolisp::%java-hand-on ()
  (let ((soonest (rontolisp::%java-thread-due))
        (keeper rontolisp::%java-keeper))
    (if (or rontolisp::%java-ready rontolisp::%java-ready-last
            (and soonest (or (null keeper) (< soonest (car keeper)))))
        (let ((calls rontolisp::%java-calls) (found nil))
          (while (and calls (null found))
            (if (aref (car calls) 7) (setq found (car calls)))
            (setq calls (cdr calls)))
          (if found (rontolisp::%java-ring found)))))
  nil)

;; A stop that does not drain cuts the exchanges in hand, as a JDK's closes their
;; connections: each call the host has in hand ends, its exchange failed
;; (http-serve.lisp's %http-serve-cutter).
#+rontolisp-host-timer
(defun rontolisp::%java-http-cut-in-hand ()
  (let ((calls rontolisp::%java-calls))
    (while calls
      (let ((exchange (car calls)))
        (rontolisp::%java-http-later exchange
         (lambda ()
           (rontolisp::%java-http-ended exchange
            "the server stopped with the exchange in hand"))))
      (setq calls (cdr calls))))
  nil)

;; What the threads answered of the exchange is done, they take their turns, and the
;; call idles where none can go on -- until the exchange ended.
#+rontolisp-host-timer
(defun rontolisp::%java-http-serve (exchange)
  (let ((going t))
    (while going
      (let ((queue (reverse (aref exchange 5))))
        (%aset exchange 5 nil)
        (while queue
          (funcall (car queue))
          (setq queue (cdr queue))))
      (cond ((aref exchange 0) (setq going nil))
            ((progn
               (rontolisp::%java-thread-due)
               (rontolisp::%java-thread-next))
             nil)
            ;; threads wait for sockets (--host-sockets): the call waits in the
            ;; host's sockets, for the soonest time a thread waits for at most,
            ;; and keeps the module meanwhile
            #+rontolisp-host-sockets
            (rontolisp::%java-polling
             (funcall rontolisp::%java-polling
                      (or (rontolisp::%java-millis-until
                           (rontolisp::%java-thread-due)) -1) nil))
            (t (rontolisp::%java-idle exchange))))))

#+rontolisp-host-timer
(defun rontolisp::%java-http-request (thunk responder)
  (let ((exchange
         (vector nil nil responder nil nil nil
                 (setq rontolisp::%java-tokens
                       (+ 1 (mod rontolisp::%java-tokens 1000000000))) nil))
        (task (rontolisp::%task-swap nil)))
    (setq rontolisp::%java-calls (cons exchange rontolisp::%java-calls))
    (setq rontolisp::%http-serve-cutter
          (function rontolisp::%java-http-cut-in-hand))
    (unwind-protect (progn
                      (rontolisp::%java-http-thread
                       (rontolisp::%java-thread-carrier "HTTP-Dispatcher") thunk
                       exchange)
                      (rontolisp::%java-http-serve exchange))
      (setq rontolisp::%java-calls (remove exchange rontolisp::%java-calls))
      (rontolisp::%task-swap task)
      (rontolisp::%java-hand-on))
    (rontolisp::%java-http-failed exchange)))

;; A component: the host calls the program for a request while others it called for
;; are suspended, and a request's thread takes its turns on whichever task the host
;; delivers something to. But a response's head leaves through the task of its own
;; request (the host's task.return). So the responder a request's threads are handed
;; is the request's holder, and what they answer is queued in it (the head a list, a
;; chunk its octets, the body's end t): sent where the request's own task runs, or
;; by anyone once the head is out, the holder told when there is something for it to
;; send. Every response goes out as a head and the chunks after it, so it is out once
;; its exchange is closed, however long its task then stays in hand.
#+rontolisp-component
(defun rontolisp::%java-http-send (holder)
  (let ((queue (reverse (aref holder 5))))
    (%aset holder 5 nil)
    (while queue
      (let ((item (car queue)))
        (cond ((consp item) (%aset holder 6 (funcall (aref holder 4) item)))
              ((eq item t) (funcall (aref holder 6) nil :close t))
              (t (funcall (aref holder 6) item))))
      (setq queue (cdr queue)))))

#+rontolisp-component
(defun rontolisp::%java-http-queue (holder item)
  (%aset holder 5 (cons item (aref holder 5)))
  (if (or (aref holder 6) (eq rontolisp::%java-host holder))
      (rontolisp::%java-http-send holder)
      (rontolisp::%java-ring holder))
  nil)

#+rontolisp-component
(defun rontolisp::%java-http-stream (holder status flat)
  (rontolisp::%java-http-queue holder
   (list status (rontolisp::%java-http-fields flat)))
  holder)

#+rontolisp-component
(defun rontolisp::%java-http-write (holder octets)
  (if (> (length octets) 0) (rontolisp::%java-http-queue holder octets))
  nil)

#+rontolisp-component
(defun rontolisp::%java-http-finish (holder)
  (%aset holder 9 t)
  (rontolisp::%java-http-queue holder t))

#+rontolisp-component
(defun rontolisp::%java-http-respond (holder status flat octets)
  (rontolisp::%java-http-stream holder status flat)
  (rontolisp::%java-http-write holder octets)
  (rontolisp::%java-http-finish holder))

;; A body whose head went out ends short: the task of the request is told, and fails
;; its transport's future.
#+rontolisp-component
(defun rontolisp::%java-http-cut (holder report)
  (%aset holder 3 report)
  (%aset holder 9 t)
  (rontolisp::%java-ring holder))

;; A request is a thread of the program's, as on every one stack. What it leaves
;; uncaught before its response ended fails the response.
#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-http-thread (carrier thunk holder)
  (rontolisp:await (rontolisp::%java-thread-queued carrier))
  (unwind-protect (handler-case (rontolisp:await (funcall thunk holder))
                    (error (condition)
                      (if (not (aref holder 9))
                          (progn
                            (%aset holder 3 condition)
                            (%aset holder 9 t)))))
    (progn
      (%aset holder 2 t)
      (rontolisp::%java-ring holder))))

;; What the task of a request does: the threads take their turns, and it waits --
;; suspended, so the host can call the program for another request -- until its
;; request's thread has ended, its response has ended -- by its handler, or by any
;; thread once the handler returned -- and nothing it holds is waited for, sending
;; what the threads wrote. What failed the response is the transport's to answer.
#+rontolisp-component
(rontolisp:async-defun rontolisp::%java-http-held (holder)
  (rontolisp::%java-thread-pump)
  (let ((going t))
    (while going
      (rontolisp::%java-http-send holder)
      (cond ((aref holder 3) (setq going nil))
            ((or (not (aref holder 2)) (not (aref holder 9))
                 (rontolisp::%java-carrying holder))
             (rontolisp:await (rontolisp::%java-bell holder)))
            (t (setq going nil)))))
  (%aset holder 8 t)
  (setq rontolisp::%java-holders (remove holder rontolisp::%java-holders))
  (let ((failure (aref holder 3)))
    (cond ((null failure) nil)
          ((stringp failure) (error "~A" failure))
          (t (error failure))))
  nil)

;; The future of that: what the delayed response answers its transport, which waits
;; for it before it takes the response as ended (http-server.lisp).
#+rontolisp-component
(defun rontolisp::%java-http-request (thunk responder)
  (let* ((task (rontolisp::%task-swap nil))
         (holder (vector nil 0 nil nil responder nil nil task nil nil)))
    (setq rontolisp::%java-holders (cons holder rontolisp::%java-holders))
    (rontolisp::%java-http-thread
     (rontolisp::%java-thread-carrier "HTTP-Dispatcher") thunk holder)
    (rontolisp::%task-swap task)
    (rontolisp::%java-http-held holder)))

;; The end of a program that started a server: where the host's threads serve, the
;; thread the program started on waits until the last server stopped, or for
;; System.exit on a thread of the transport's.
#-rontolisp-wasm
(defun rontolisp::%java-http-wait ()
  (let ((carrier (rontolisp::%java-self)) (waiting t))
    (while waiting
      (let ((ticket (cons carrier nil)))
        (rontolisp:mutex-acquire rontolisp::%java-http-guard)
        (if (> rontolisp::%java-http-running 0)
            (setq rontolisp::%java-http-waiter ticket)
            (setq waiting nil))
        (rontolisp:mutex-release rontolisp::%java-http-guard)
        (if waiting
            (progn
              (rontolisp::%java-park carrier ticket nil)
              (rontolisp::%java-thread-interrupted carrier t))))))
  nil)

#+rontolisp-wasm (defun rontolisp::%java-http-wait () nil)
