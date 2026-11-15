;; The run-time half of the EXPERIMENTAL Starlark front end (am.ik.rontolisp.starlark),
;; written in Common Lisp so one definition runs on every backend and no emitter learns
;; a Starlark name: the interpreter loads it on the first resolution of a
;; rontolisp::%starlark- function, the compile path splices it when the program
;; references one (StarlarkLibrary.java) and the tree-shaker drops what stays
;; unreachable. One name is public: rontolisp:starlark-call, what Common Lisp calls a
;; Starlark function value with.
;;
;; The value model these helpers share with the lowering (.kb/starlark-frontend.md):
;; None is :none, True and False are T and NIL, an int is an integer, a float a double,
;; a string a string whose elements are code points, and a list, a tuple, a dict, a
;; range and a function are the structures below.
;;
;; Three rules every definition here keeps:
;;  - A structure accessor is called only behind that structure's predicate. An accessor
;;    handed an instance of ANOTHER structure reads the wrong slot, or fails with a text
;;    that differs per backend and a trap on wasm (.kb/defstruct.md, "Accessors check
;;    their object").
;;  - A value of the program reaches a Common Lisp function only behind the type test
;;    that function needs, so every failure is an `error` with a Starlark message, the
;;    same on every backend.
;;  - What the lowering calls is a DEFUN: the interpreter loads this file when it first
;;    resolves one of its functions, which a structure's own constructor, predicate or
;;    accessor does not trigger.

;; --- values ---------------------------------------------------------------------

;; The cell every list and dict made now is made under: its car turns true when the
;; file that was running has run, and what was made under it is frozen. A file's run
;; puts a cell of its own in front (%starlark-file-begin), whose cdr is the one before.
(defvar rontolisp::%starlark-mutability (list nil))

;; What a call that does not return leaves to give back, innermost first: a list or a
;; dict a loop counts itself on, and under --starlark-standard spec the flag of a
;; declaration a call of which runs (a cons). %starlark-unwind gives them back.
(defvar rontolisp::%starlark-frames nil)

;; ITEMS is an adjustable vector with a fill pointer. MUTABILITY is the cell the list
;; was made under: the list is frozen once its car is true. ITERATING counts the loops
;; running over the list: a list is not mutated while one runs.
(defstruct (rontolisp::%starlark-list (:%intern-package "RONTOLISP")
            (:constructor rontolisp::%starlark-new-list (items)) (:copier nil))
  items
  (mutability rontolisp::%starlark-mutability)
  (iterating 0))

;; ITEMS is a simple vector, never written after the tuple is made.
(defstruct (rontolisp::%starlark-tuple (:%intern-package "RONTOLISP")
            (:constructor rontolisp::%starlark-new-tuple (items)) (:copier nil))
  items)

;; TABLE is an `equal` hash table from a key's normal form (%starlark-key) to the cell
;; (key . value). The table walks in insertion order on every backend, a key stored
;; again keeping its place and a key removed and stored again going last
;; (.kb/hash-tables.md, "Representation"), which is the order a dict iterates in.
(defstruct (rontolisp::%starlark-dict (:%intern-package "RONTOLISP")
            (:constructor rontolisp::%starlark-new-dict (table)) (:copier nil))
  table
  (mutability rontolisp::%starlark-mutability)
  (iterating 0))

(defstruct (rontolisp::%starlark-range (:%intern-package "RONTOLISP")
            (:constructor rontolisp::%starlark-new-range (start stop step))
            (:copier nil))
  start
  stop
  step)

;; What s.elems(), s.elem_ords(), s.codepoints() and s.codepoint_ords() answer: an
;; iterable over the elements of STRING, as one-element strings or, when ORDS, as their
;; codes. An element is a code point in this string model, so the four differ only in
;; NAME, the method that made the value, which its type and `repr` show.
(defstruct (rontolisp::%starlark-string-iterable (:%intern-package "RONTOLISP")
            (:constructor rontolisp::%starlark-new-string-iterable
                          (string name ords)) (:copier nil))
  string
  name
  ords)

;; A function value: a `def` or `lambda` (KIND nil), a built-in function (:builtin) or
;; a method bound to its receiver SELF (:method). PARAMS names the parameters BODY
;; takes in order, the first POSITIONAL of them fillable by position and those from
;; NAMED-FROM on by name; DEFAULTS is parallel to PARAMS (:%required where an argument
;; is needed) or nil when every parameter is required. BODY takes the parameters, then
;; the tuple of surplus positional arguments when VARARGS, then the dict of surplus
;; named ones when KWARGS -- after SELF for a method.
(defstruct (rontolisp::%starlark-function (:%intern-package "RONTOLISP")
                                          (:constructor
                                           rontolisp::%starlark-new-function
                                           (name params defaults positional
                                                 named-from varargs kwargs body
                                                 kind self)) (:copier nil))
  name
  params
  defaults
  positional
  named-from
  varargs
  kwargs
  body
  kind
  self)

(defun rontolisp::%starlark-vector ()
  (make-array 0 :adjustable t :fill-pointer 0))

;; A list over VECTOR, an adjustable vector with a fill pointer nothing else holds:
;; what a comprehension accumulates into.
(defun rontolisp::%starlark-list-from (vector)
  (rontolisp::%starlark-new-list vector))

;; A fresh list of the elements of a Common Lisp list.
(defun rontolisp::%starlark-list-of (xs)
  (let ((v (rontolisp::%starlark-vector)))
    (dolist (x xs) (vector-push-extend x v))
    (rontolisp::%starlark-new-list v)))

(defun rontolisp::%starlark-make-list (&rest xs)
  (rontolisp::%starlark-list-of xs))

(defun rontolisp::%starlark-tuple-of (xs)
  (rontolisp::%starlark-new-tuple (coerce xs 'vector)))

(defun rontolisp::%starlark-make-tuple (&rest xs)
  (rontolisp::%starlark-tuple-of xs))

(defun rontolisp::%starlark-make-function
    (name params defaults positional varargs kwargs body)
  (rontolisp::%starlark-new-function name params defaults positional 0 varargs
                                     kwargs body nil nil))

(defun rontolisp::%starlark-builtin
    (name params defaults positional named-from varargs kwargs body)
  (rontolisp::%starlark-new-function name params defaults positional named-from
                                     varargs kwargs body
                                     :builtin nil))

(defun rontolisp::%starlark-type (x)
  (cond ((eq x :none) "NoneType")
        ((or (eq x t) (null x)) "bool")
        ((integerp x) "int")
        ((floatp x) "float")
        ((stringp x) "string")
        ((rontolisp::%starlark-list-p x) "list")
        ((rontolisp::%starlark-tuple-p x) "tuple")
        ((rontolisp::%starlark-dict-p x) "dict")
        ((rontolisp::%starlark-range-p x) "range")
        ((rontolisp::%starlark-string-iterable-p x)
         (if (or (string= (rontolisp::%starlark-string-iterable-name x) "elems")
                 (string= (rontolisp::%starlark-string-iterable-name x)
                          "elem_ords"))
             "string.elems"
             "string.codepoints"))
        ((rontolisp::%starlark-function-p x)
         (if (rontolisp::%starlark-function-kind x)
             "builtin_function_or_method"
             "function"))
        ((functionp x) "builtin_function_or_method")
        (t "unknown")))

(defun rontolisp::%starlark-no-attribute (x name)
  (error "~a has no .~a field or method" (rontolisp::%starlark-type x) name))

(defun rontolisp::%starlark-set-attribute (x name value)
  (error "can't assign to .~a field of ~a" name (rontolisp::%starlark-type x)))

;; R is a range.
(defun rontolisp::%starlark-range-length (r)
  (let ((start (rontolisp::%starlark-range-start r))
        (stop (rontolisp::%starlark-range-stop r))
        (step (rontolisp::%starlark-range-step r)))
    (if (> step 0)
        (if (< start stop) (values (ceiling (- stop start) step)) 0)
        (if (> start stop) (values (ceiling (- start stop) (- step))) 0))))

(defun rontolisp::%starlark-truth (x)
  (cond ((eq x t) t)
        ((null x) nil)
        ((eq x :none) nil)
        ((numberp x) (/= x 0))
        ((stringp x) (> (length x) 0))
        ((rontolisp::%starlark-list-p x)
         (> (length (rontolisp::%starlark-list-items x)) 0))
        ((rontolisp::%starlark-tuple-p x)
         (> (length (rontolisp::%starlark-tuple-items x)) 0))
        ((rontolisp::%starlark-dict-p x)
         (> (hash-table-count (rontolisp::%starlark-dict-table x)) 0))
        ((rontolisp::%starlark-range-p x)
         (> (rontolisp::%starlark-range-length x) 0))
        (t t)))

;; --- iteration --------------------------------------------------------------------

;; R is a range. Its elements as a fresh simple vector.
(defun rontolisp::%starlark-range-vector (r)
  (let* ((n (rontolisp::%starlark-range-length r))
         (v (make-array n))
         (x (rontolisp::%starlark-range-start r))
         (step (rontolisp::%starlark-range-step r)))
    (dotimes (i n)
      (setf (aref v i) x)
      (setq x (+ x step)))
    v))

;; D is a dict. Its keys in iteration order, as a fresh Common Lisp list.
(defun rontolisp::%starlark-dict-key-list (d)
  (let ((acc nil))
    (maphash (lambda (k cell) (push (car cell) acc))
             (rontolisp::%starlark-dict-table d))
    (nreverse acc)))

(defun rontolisp::%starlark-iterable-p (x)
  (or (rontolisp::%starlark-list-p x) (rontolisp::%starlark-tuple-p x)
      (rontolisp::%starlark-dict-p x) (rontolisp::%starlark-range-p x)
      (rontolisp::%starlark-string-iterable-p x)))

;; X is a string iterable. Its elements as a fresh simple vector.
(defun rontolisp::%starlark-string-iterable-vector (x)
  (let* ((s (rontolisp::%starlark-string-iterable-string x))
         (ords (rontolisp::%starlark-string-iterable-ords x))
         (n (length s))
         (v (make-array n)))
    (dotimes (i n)
      (setf (aref v i) (if ords (char-code (char s i)) (subseq s i (+ i 1)))))
    v))

;; The elements of X, an iterable, as a fresh Common Lisp list; MESSAGE is the error
;; when X is no iterable, a format string over its type.
(defun rontolisp::%starlark-elements-or (x message)
  (cond ((rontolisp::%starlark-list-p x)
         (coerce (rontolisp::%starlark-list-items x) 'list))
        ((rontolisp::%starlark-tuple-p x)
         (coerce (rontolisp::%starlark-tuple-items x) 'list))
        ((rontolisp::%starlark-dict-p x) (rontolisp::%starlark-dict-key-list x))
        ((rontolisp::%starlark-range-p x)
         (coerce (rontolisp::%starlark-range-vector x) 'list))
        ((rontolisp::%starlark-string-iterable-p x)
         (coerce (rontolisp::%starlark-string-iterable-vector x) 'list))
        (t (error message (rontolisp::%starlark-type x)))))

;; The elements of an iterable as a fresh Common Lisp list.
(defun rontolisp::%starlark-elements (x)
  (rontolisp::%starlark-elements-or x "got ~a, want iterable"))

;; The elements of the iterable of a `*` argument.
(defun rontolisp::%starlark-star-elements (x)
  (rontolisp::%starlark-elements-or x
   "argument after * must be iterable, not ~a"))

;; A fresh simple vector of the elements of vector V.
(defun rontolisp::%starlark-copy-vector (v)
  (let* ((n (length v)) (out (make-array n)))
    (dotimes (i n) (setf (aref out i) (aref v i)))
    out))

;; Takes X off the frames. It is the innermost one on every exit the lowering makes;
;; one below is looked for only after an error left frames behind.
(defun rontolisp::%starlark-pop-frame (x)
  (let ((frames rontolisp::%starlark-frames))
    (if (eq (car frames) x)
        (setq rontolisp::%starlark-frames (cdr frames))
        (loop while (cdr frames)
              do
                (if (eq (cadr frames) x)
                    (progn
                      (rplacd frames (cddr frames))
                      (return nil))) (setq frames (cdr frames))))))

;; Gives back every frame above MARK, what the frames were when a call started: the
;; loops and declarations a call that did not return left counted. Nothing when MARK
;; is no tail of the frames any more.
(defun rontolisp::%starlark-unwind (mark)
  (let ((frames rontolisp::%starlark-frames))
    (loop while (and frames (not (eq frames mark)))
          do (setq frames (cdr frames)))
    (if (eq frames mark)
        (loop until (eq rontolisp::%starlark-frames mark)
              do
                (let ((x (car rontolisp::%starlark-frames)))
                  (cond ((consp x) (rplaca x nil))
                        ((rontolisp::%starlark-list-p x)
                         (setf (rontolisp::%starlark-list-iterating x)
                               (- (rontolisp::%starlark-list-iterating x) 1)))
                        ((rontolisp::%starlark-dict-p x)
                         (setf (rontolisp::%starlark-dict-iterating x)
                               (- (rontolisp::%starlark-dict-iterating x) 1)))))
                (setq rontolisp::%starlark-frames
                      (cdr rontolisp::%starlark-frames))))))

;; Starts a loop over X: the vector the loop indexes. A list and a dict count the loop
;; until %starlark-iterated, and refuse a mutation meanwhile; the list's own vector is
;; what the loop reads, since nothing can write it before then.
(defun rontolisp::%starlark-iterate (x)
  (cond ((rontolisp::%starlark-list-p x)
         (setf (rontolisp::%starlark-list-iterating x)
               (+ (rontolisp::%starlark-list-iterating x) 1))
         (setq rontolisp::%starlark-frames (cons x rontolisp::%starlark-frames))
         (rontolisp::%starlark-list-items x))
        ((rontolisp::%starlark-tuple-p x) (rontolisp::%starlark-tuple-items x))
        ((rontolisp::%starlark-dict-p x)
         (setf (rontolisp::%starlark-dict-iterating x)
               (+ (rontolisp::%starlark-dict-iterating x) 1))
         (setq rontolisp::%starlark-frames (cons x rontolisp::%starlark-frames))
         (coerce (rontolisp::%starlark-dict-key-list x) 'vector))
        ((rontolisp::%starlark-range-p x) (rontolisp::%starlark-range-vector x))
        ((rontolisp::%starlark-string-iterable-p x)
         (rontolisp::%starlark-string-iterable-vector x))
        (t (error "~a value is not iterable" (rontolisp::%starlark-type x)))))

;; Ends the loop %starlark-iterate started over X.
(defun rontolisp::%starlark-iterated (x)
  (cond ((rontolisp::%starlark-list-p x)
         (setf (rontolisp::%starlark-list-iterating x)
               (- (rontolisp::%starlark-list-iterating x) 1))
         (rontolisp::%starlark-pop-frame x))
        ((rontolisp::%starlark-dict-p x)
         (setf (rontolisp::%starlark-dict-iterating x)
               (- (rontolisp::%starlark-dict-iterating x) 1))
         (rontolisp::%starlark-pop-frame x)))
  nil)

;; The N elements of X for a compound target, as a vector nothing writes.
(defun rontolisp::%starlark-unpack (x n)
  (let ((v
         (cond ((rontolisp::%starlark-tuple-p x)
                (rontolisp::%starlark-tuple-items x))
               ((rontolisp::%starlark-list-p x)
                (rontolisp::%starlark-copy-vector
                 (rontolisp::%starlark-list-items x)))
               (t (coerce (rontolisp::%starlark-elements-or x
                           "got ~a in sequence assignment") 'vector)))))
    (cond ((< (length v) n)
           (error "too few values to unpack (got ~a, want ~a)" (length v) n))
          ((> (length v) n)
           (error "too many values to unpack (got ~a, want ~a)" (length v) n)))
    v))

;; L is a list. VERB names the mutation, as in "cannot append to frozen list".
(defun rontolisp::%starlark-list-writable (l verb)
  (cond ((car (rontolisp::%starlark-list-mutability l))
         (error "cannot ~a frozen list" verb))
        ((> (rontolisp::%starlark-list-iterating l) 0)
         (error "cannot ~a list during iteration" verb))))

;; D is a dict.
(defun rontolisp::%starlark-dict-writable (d verb)
  (cond ((car (rontolisp::%starlark-dict-mutability d))
         (error "cannot ~a frozen dict" verb))
        ((> (rontolisp::%starlark-dict-iterating d) 0)
         (error "cannot ~a dict during iteration" verb))))

;; --- equality and ordering --------------------------------------------------------

;; A and B are vectors.
(defun rontolisp::%starlark-vector-equal (a b)
  (let ((n (length a)) (i 0))
    (if (/= n (length b))
        nil
        (loop
          (if (>= i n) (return t))
          (if (not (rontolisp::%starlark-equal (aref a i) (aref b i)))
              (return nil))
          (setq i (+ i 1))))))

;; A and B are dicts.
(defun rontolisp::%starlark-dict-equal (a b)
  (let ((ta (rontolisp::%starlark-dict-table a))
        (tb (rontolisp::%starlark-dict-table b))
        (same t))
    (if (/= (hash-table-count ta) (hash-table-count tb))
        nil
        (progn
          (maphash (lambda (k cell)
                     (let ((other (gethash k tb)))
                       (if (or (null other)
                               (not
                                (rontolisp::%starlark-equal (cdr cell)
                                                            (cdr other))))
                           (setq same nil)))) ta)
          same))))

;; A and B are ranges: equal when they denote the same integers.
(defun rontolisp::%starlark-range-equal (a b)
  (let ((n (rontolisp::%starlark-range-length a)))
    (cond ((/= n (rontolisp::%starlark-range-length b)) nil)
          ((= n 0) t)
          ((/= (rontolisp::%starlark-range-start a)
               (rontolisp::%starlark-range-start b))
           nil)
          ((= n 1) t)
          (t (= (rontolisp::%starlark-range-step a)
                (rontolisp::%starlark-range-step b))))))

;; A and B are functions. A `def` is itself only; a built-in is made anew wherever its
;; name is written, so two of one name -- and one receiver, for a method -- are equal.
(defun rontolisp::%starlark-function-equal (a b)
  (cond ((eq a b) t)
        ((null (rontolisp::%starlark-function-kind a)) nil)
        (t (and (eq (rontolisp::%starlark-function-kind a)
                    (rontolisp::%starlark-function-kind b))
                (string= (rontolisp::%starlark-function-name a)
                         (rontolisp::%starlark-function-name b))
                (eq (rontolisp::%starlark-function-self a)
                    (rontolisp::%starlark-function-self b))))))

;; X is a number: 0 for an int or a finite float, 1 for +inf, -1 for -inf, 2 for NaN --
;; the order of the classes, NaN above everything.
(defun rontolisp::%starlark-number-class (x)
  (cond ((integerp x) 0)
        ((/= x x) 2)
        ((> x 1.7976931348623157d308) 1)
        ((< x -1.7976931348623157d308) -1)
        (t 0)))

;; A and B are numbers: -1, 0 or 1. An int and a float compare as the values they are,
;; exactly; NaN is greater than every other float and equal to itself.
(defun rontolisp::%starlark-number-compare (a b)
  (let ((ca (rontolisp::%starlark-number-class a))
        (cb (rontolisp::%starlark-number-class b)))
    (if (or (/= ca 0) (/= cb 0))
        (cond ((< ca cb) -1) ((> ca cb) 1) (t 0))
        (let ((x (if (and (floatp a) (integerp b)) (rational a) a))
              (y (if (and (floatp b) (integerp a)) (rational b) b)))
          (cond ((< x y) -1) ((> x y) 1) (t 0))))))

(defun rontolisp::%starlark-equal (a b)
  (cond ((integerp a)
         (cond ((integerp b) (= a b))
               ((floatp b) (= (rontolisp::%starlark-number-compare a b) 0))
               (t nil)))
        ((floatp a)
         (and (numberp b) (= (rontolisp::%starlark-number-compare a b) 0)))
        ((stringp a) (and (stringp b) (string= a b)))
        ((rontolisp::%starlark-list-p a)
         (and (rontolisp::%starlark-list-p b)
              (rontolisp::%starlark-vector-equal
               (rontolisp::%starlark-list-items a)
               (rontolisp::%starlark-list-items b))))
        ((rontolisp::%starlark-tuple-p a)
         (and (rontolisp::%starlark-tuple-p b)
              (rontolisp::%starlark-vector-equal
               (rontolisp::%starlark-tuple-items a)
               (rontolisp::%starlark-tuple-items b))))
        ((rontolisp::%starlark-dict-p a)
         (and (rontolisp::%starlark-dict-p b)
              (rontolisp::%starlark-dict-equal a b)))
        ((rontolisp::%starlark-range-p a)
         (and (rontolisp::%starlark-range-p b)
              (rontolisp::%starlark-range-equal a b)))
        ((rontolisp::%starlark-function-p a)
         (and (rontolisp::%starlark-function-p b)
              (rontolisp::%starlark-function-equal a b)))
        (t (eq a b))))

;; A and B are vectors: -1, 0 or 1, element by element, then the shorter first.
(defun rontolisp::%starlark-vector-compare (a b)
  (let ((na (length a)) (nb (length b)) (i 0))
    (loop
      (cond ((and (>= i na) (>= i nb)) (return 0))
            ((>= i na) (return -1))
            ((>= i nb) (return 1))
            ((not (rontolisp::%starlark-equal (aref a i) (aref b i)))
             (return (rontolisp::%starlark-compare (aref a i) (aref b i)))))
      (setq i (+ i 1)))))

(defun rontolisp::%starlark-compare (a b)
  (cond ((and (integerp a) (integerp b)) (cond ((< a b) -1) ((> a b) 1) (t 0)))
   ((and (numberp a) (numberp b)) (rontolisp::%starlark-number-compare a b))
   ((and (stringp a) (stringp b))
    (cond ((string< a b) -1) ((string> a b) 1) (t 0)))
   ((and (or (eq a t) (null a)) (or (eq b t) (null b)))
    (cond ((eq a b) 0) ((null a) -1) (t 1)))
   ((and (rontolisp::%starlark-list-p a) (rontolisp::%starlark-list-p b))
    (rontolisp::%starlark-vector-compare (rontolisp::%starlark-list-items a)
                                         (rontolisp::%starlark-list-items b)))
   ((and (rontolisp::%starlark-tuple-p a) (rontolisp::%starlark-tuple-p b))
    (rontolisp::%starlark-vector-compare (rontolisp::%starlark-tuple-items a)
                                         (rontolisp::%starlark-tuple-items b)))
   (t (error "unsupported comparison: ~a <=> ~a" (rontolisp::%starlark-type a)
             (rontolisp::%starlark-type b)))))

(defun rontolisp::%starlark-less (a b) (< (rontolisp::%starlark-compare a b) 0))

(defun rontolisp::%starlark-less-equal (a b)
  (<= (rontolisp::%starlark-compare a b) 0))

(defun rontolisp::%starlark-greater (a b)
  (> (rontolisp::%starlark-compare a b) 0))

(defun rontolisp::%starlark-greater-equal (a b)
  (>= (rontolisp::%starlark-compare a b) 0))

;; --- dict -------------------------------------------------------------------------

;; The normal form a key is stored under, so that equal keys meet in an `equal` table:
;; 1 and 1.0 are one key, True and 1 are two, every NaN is one key, a tuple is the list
;; of its elements' normal forms, and so is a frozen list. A `def` or `lambda` is itself,
;; by identity; a built-in is its name and receiver, as two of them are equal.
(defun rontolisp::%starlark-key (k)
  (cond ((stringp k) k)
        ((integerp k) k)
        ((floatp k)
         (cond ((/= k k) :nan)
               ((and (<= k 1.7976931348623157d308)
                     (>= k -1.7976931348623157d308) (= k (float (round k) 1d0)))
                (values (round k)))
               (t k)))
        ((rontolisp::%starlark-function-p k)
         (if (rontolisp::%starlark-function-kind k)
             (list :builtin (rontolisp::%starlark-function-name k)
                   (rontolisp::%starlark-function-self k))
             k))
        ((eq k t) :true)
        ((null k) :false)
        ((eq k :none) :none)
        ((rontolisp::%starlark-tuple-p k)
         (let ((items (rontolisp::%starlark-tuple-items k)) (acc nil))
           (dotimes (i (length items))
             (push (rontolisp::%starlark-key (aref items i)) acc))
           (cons :tuple (nreverse acc))))
        ((and (rontolisp::%starlark-list-p k)
              (car (rontolisp::%starlark-list-mutability k)))
         (let ((items (rontolisp::%starlark-list-items k)) (acc nil))
           (dotimes (i (length items))
             (push (rontolisp::%starlark-key (aref items i)) acc))
           (cons :list (nreverse acc))))
        (t (error "unhashable type: ~a" (rontolisp::%starlark-type k)))))

(defun rontolisp::%starlark-empty-dict ()
  (rontolisp::%starlark-new-dict (make-hash-table :test 'equal)))

;; D is a dict the caller may write.
(defun rontolisp::%starlark-dict-store (d k v)
  (let* ((key (rontolisp::%starlark-key k))
         (table (rontolisp::%starlark-dict-table d))
         (cell (gethash key table)))
    (if cell (rplacd cell v) (setf (gethash key table) (cons k v)))
    v))

;; D is a dict. The cell (key . value) of K, or nil.
(defun rontolisp::%starlark-dict-cell (d k)
  (values
   (gethash (rontolisp::%starlark-key k) (rontolisp::%starlark-dict-table d))))

;; A dict expression: a key written twice is an error.
(defun rontolisp::%starlark-make-dict (&rest kvs)
  (let ((d (rontolisp::%starlark-empty-dict)))
    (loop while kvs
          do
            (if (rontolisp::%starlark-dict-cell d (car kvs))
                (error "duplicate key: ~a"
                       (rontolisp::%starlark-b-repr (car kvs))))
            (rontolisp::%starlark-dict-store d (car kvs) (cadr kvs))
            (setq kvs (cddr kvs)))
    d))

;; The named arguments a `**` argument supplies: name, value, name, value ...
(defun rontolisp::%starlark-keywords (d)
  (if (not (rontolisp::%starlark-dict-p d))
      (error "argument after ** must be a mapping, not ~a"
             (rontolisp::%starlark-type d)))
  (let ((acc nil))
    (maphash (lambda (k cell)
               (if (not (stringp (car cell)))
                   (error "keywords must be strings, not ~a"
                          (rontolisp::%starlark-type (car cell))))
               (push (car cell) acc)
               (push (cdr cell) acc)) (rontolisp::%starlark-dict-table d))
    (nreverse acc)))

;; D is a dict the caller may write. Stores the entries of PAIRS, another dict or an
;; iterable of two-element iterables; WHO names the caller in an error.
(defun rontolisp::%starlark-dict-merge (d pairs who)
  (cond ((rontolisp::%starlark-dict-p pairs)
         (if (not (eq d pairs))
             (maphash (lambda (k cell)
                        (rontolisp::%starlark-dict-store d (car cell)
                                                         (cdr cell)))
                      (rontolisp::%starlark-dict-table pairs))))
        ((not (rontolisp::%starlark-iterable-p pairs))
         (error "~a: got ~a, want iterable" who
                (rontolisp::%starlark-type pairs)))
        (t
         (dolist (pair (rontolisp::%starlark-elements pairs))
           (if (not (rontolisp::%starlark-iterable-p pair))
               (error
                "~a: dictionary update sequence element is not iterable (~a)"
                who (rontolisp::%starlark-type pair)))
           (let ((kv (coerce (rontolisp::%starlark-elements pair) 'vector)))
             (if (/= (length kv) 2)
                 (error
                  "~a: dictionary update sequence element has length ~a, want 2"
                  who (length kv)))
             (rontolisp::%starlark-dict-store d (aref kv 0) (aref kv 1)))))))

;; --- printing ---------------------------------------------------------------------

;; Whether the character of CODE prints as itself in a string's `repr`: not a control,
;; format, private-use or separator character other than the space (Go's
;; strconv.IsPrint, the unassigned code points aside). The ranges past U+00A0 are
;; %starlark-unprintable-ranges, generated from the JDK's tables (Starlark.java).
(defun rontolisp::%starlark-printable-p (code)
  (cond ((< code 127) (>= code 32))
        ((< code 160) nil)
        (t (let ((ranges (rontolisp::%starlark-unprintable-ranges)) (found nil))
             (loop
               (if (or found (null ranges) (< code (car ranges))) (return nil))
               (if (<= code (cadr ranges)) (setq found t))
               (setq ranges (cddr ranges)))
             (not found)))))

;; Writes the hexadecimal digits of N, at least WIDTH of them.
(defun rontolisp::%starlark-write-hex (n width s)
  (let ((text (format nil "~(~x~)" n)))
    (dotimes (i (- width (length text))) (write-char #\0 s))
    (write-string text s)))

;; X is a string: the literal that denotes it, as Go's syntax.Quote writes one.
(defun rontolisp::%starlark-write-string (x s)
  (write-char #\" s)
  (dotimes (i (length x))
    (let* ((c (char x i)) (code (char-code c)))
      (cond ((char= c #\") (write-string "\\\"" s))
       ((char= c #\\) (write-string "\\\\" s))
       ((rontolisp::%starlark-printable-p code) (write-char c s))
       ((= code 7) (write-string "\\a" s))
       ((= code 8) (write-string "\\b" s))
       ((= code 12) (write-string "\\f" s))
       ((= code 10) (write-string "\\n" s))
       ((= code 13) (write-string "\\r" s))
       ((= code 9) (write-string "\\t" s))
       ((= code 11) (write-string "\\v" s))
       ((or (< code 32) (= code 127))
        (write-string "\\x" s)
        (rontolisp::%starlark-write-hex code 2 s))
       ((< code 65536)
        (write-string "\\u" s)
        (rontolisp::%starlark-write-hex code 4 s))
       (t
        (write-string "\\U" s)
        (rontolisp::%starlark-write-hex code 8 s)))))
  (write-char #\" s))

;; X is a finite float other than zero: the shortest decimal digits that read back as
;; |X|, as a string with no leading or trailing zero, and as the second value DP, the
;; place of the decimal point: |X| is 0.DIGITS times ten to the power DP. They are
;; read off the printer's text, the one shortest-digit selection of every backend.
(defun rontolisp::%starlark-float-digits (x)
  (let* ((text (prin1-to-string (abs x)))
         (n (length text))
         (mark (position-if (lambda (c) (or (char= c #\e) (char= c #\d))) text))
         (end (if mark mark n))
         (exponent (if mark (parse-integer text :start (+ mark 1)) 0))
         (point end)
         (count 0)
         (acc nil))
    (dotimes (i end)
      (let ((c (char text i)))
        (if (char= c #\.)
            (setq point count)
            (progn
              (push c acc)
              (setq count (+ count 1))))))
    (if (= point end) (setq point count))
    (let ((digits (coerce (nreverse acc) 'string)) (dp (+ point exponent)))
      (loop while (and (> (length digits) 1) (char= (char digits 0) #\0))
            do (setq digits (subseq digits 1)) (setq dp (- dp 1)))
      (let ((last (length digits)))
        (loop while (and (> last 1) (char= (char digits (- last 1)) #\0))
              do (setq last (- last 1)))
        (values (subseq digits 0 last) dp)))))

;; The exponent of an %e or %g text: its sign and at least two digits.
(defun rontolisp::%starlark-exponent-string (e marker)
  (format nil "~a~a~a~a" marker (if (< e 0) "-" "+") (if (< (abs e) 10) "0" "")
          (abs e)))

;; A non-negative integer Q as the text of Q / 10^PLACES with PLACES decimals.
(defun rontolisp::%starlark-scaled-string (q places)
  (let* ((text (format nil "~d" q))
         (padded
          (if (<= (length text) places)
              (concatenate 'string
               (make-string (- (+ places 1) (length text)) :initial-element #\0)
               text)
              text))
         (cut (- (length padded) places)))
    (concatenate 'string (subseq padded 0 cut) "." (subseq padded cut))))

;; NUM / DEN rounded to the nearest integer, a tie to the even one.
(defun rontolisp::%starlark-round-ratio (num den)
  (multiple-value-bind (q r) (floor num den)
    (let ((twice (* 2 r)))
      (if (or (> twice den) (and (= twice den) (oddp q))) (+ q 1) q))))

;; X is a finite float: |X| as the exact ratio NUM / DEN of two integers.
(defun rontolisp::%starlark-float-ratio (x)
  (multiple-value-bind (m e) (integer-decode-float (abs x))
    (if (>= e 0) (values (* m (expt 2 e)) 1) (values m (expt 2 (- e))))))

;; X is a finite float: |X| in %f form with 6 decimals, correctly rounded.
(defun rontolisp::%starlark-fixed-string (x)
  (multiple-value-bind (num den) (rontolisp::%starlark-float-ratio x)
    (rontolisp::%starlark-scaled-string
     (rontolisp::%starlark-round-ratio (* num 1000000) den) 6)))

;; X is a finite float: |X| in %e form with 6 decimals, correctly rounded; MARKER is
;; "e" or "E".
(defun rontolisp::%starlark-exponential-string (x marker)
  (if (= x 0)
      (concatenate 'string "0.000000"
                   (rontolisp::%starlark-exponent-string 0 marker))
      (multiple-value-bind (num den) (rontolisp::%starlark-float-ratio x)
        (multiple-value-bind (digits dp) (rontolisp::%starlark-float-digits x)
          (declare (ignore digits))
          ;; E is the decade of |X|: 10^E <= |X| < 10^(E+1). The shortest digits
          ;; give it, or one more when they rounded up to a power of ten.
          (let ((e (- dp 1)) (q 0))
            (if (< (* num (if (< e 0) (expt 10 (- e)) 1))
                   (* den (if (> e 0) (expt 10 e) 1)))
                (setq e (- e 1)))
            ;; Q is |X| / 10^(E-6), rounded: seven digits, or 10^7 when the rounding
            ;; carried into the next decade.
            (setq q
                  (rontolisp::%starlark-round-ratio
                   (* num (if (< (- e 6) 0) (expt 10 (- 6 e)) 1))
                   (* den (if (> (- e 6) 0) (expt 10 (- e 6)) 1))))
            (if (>= q 10000000)
                (progn
                  (setq e (+ e 1))
                  (setq q
                        (rontolisp::%starlark-round-ratio
                         (* num (if (< (- e 6) 0) (expt 10 (- 6 e)) 1))
                         (* den (if (> (- e 6) 0) (expt 10 (- e 6)) 1))))))
            (concatenate 'string (rontolisp::%starlark-scaled-string q 6)
                         (rontolisp::%starlark-exponent-string e marker)))))))

;; X is a finite float: |X| in the shortest form that reads back as X, as Go's
;; strconv.FormatFloat 'g' with precision -1 writes it: an exponent below 1e-4 and from
;; 1e+06 on; MARKER is "e" or "E".
(defun rontolisp::%starlark-shortest-string (x marker)
  (if (= x 0)
      "0"
      (multiple-value-bind (digits dp) (rontolisp::%starlark-float-digits x)
        (let ((nd (length digits)) (e (- dp 1)))
          (cond ((or (< e -4) (>= e 6))
                 (concatenate 'string (subseq digits 0 1) (if (> nd 1) "." "")
                              (subseq digits 1)
                              (rontolisp::%starlark-exponent-string e marker)))
                ((<= dp 0)
                 (concatenate 'string "0."
                              (make-string (- dp) :initial-element #\0) digits))
                ((>= dp nd)
                 (concatenate 'string digits
                              (make-string (- dp nd) :initial-element #\0)))
                (t (concatenate 'string (subseq digits 0 dp) "."
                                (subseq digits dp))))))))

;; X is a float, CONV one of the `%` conversions e, E, f, F, g and G: the text Go's
;; Starlark writes. `str` and `repr` are %g, which always shows a point or an exponent.
(defun rontolisp::%starlark-format-float (x conv)
  (cond ((/= x x) "nan")
        ((> x 1.7976931348623157d308) "+inf")
        ((< x -1.7976931348623157d308) "-inf")
        (t
         (let* ((upper (or (char= conv #\E) (char= conv #\G)))
                (marker (if upper "E" "e"))
                (text
                 (cond ((or (char= conv #\f) (char= conv #\F))
                        (rontolisp::%starlark-fixed-string x))
                       ((or (char= conv #\e) (char= conv #\E))
                        (rontolisp::%starlark-exponential-string x marker))
                       (t
                        (let ((g
                               (rontolisp::%starlark-shortest-string x marker)))
                          (if (or (search marker g) (find #\. g))
                              g
                              (concatenate 'string g ".0")))))))
           (if (minusp (float-sign x)) (concatenate 'string "-" text) text)))))

(defun rontolisp::%starlark-float-string (x)
  (rontolisp::%starlark-format-float x #\g))

;; V is a vector.
(defun rontolisp::%starlark-write-vector (v s open close)
  (write-string open s)
  (dotimes (i (length v))
    (if (> i 0) (write-string ", " s))
    (rontolisp::%starlark-write (aref v i) s))
  (write-string close s))

;; Writes X as `repr` does.
(defun rontolisp::%starlark-write (x s)
  (cond ((eq x :none) (write-string "None" s))
        ((eq x t) (write-string "True" s))
        ((null x) (write-string "False" s))
        ((integerp x) (format s "~d" x))
        ((floatp x) (write-string (rontolisp::%starlark-float-string x) s))
        ((stringp x) (rontolisp::%starlark-write-string x s))
        ((rontolisp::%starlark-list-p x)
         (rontolisp::%starlark-write-vector (rontolisp::%starlark-list-items x)
                                            s "[" "]"))
        ((rontolisp::%starlark-tuple-p x)
         (let ((items (rontolisp::%starlark-tuple-items x)))
           (if (= (length items) 1)
               (progn
                 (write-string "(" s)
                 (rontolisp::%starlark-write (aref items 0) s)
                 (write-string ",)" s))
               (rontolisp::%starlark-write-vector items s "(" ")"))))
        ((rontolisp::%starlark-dict-p x)
         (write-string "{" s)
         (let ((first t))
           (maphash (lambda (k cell)
                      (if first (setq first nil) (write-string ", " s))
                      (rontolisp::%starlark-write (car cell) s)
                      (write-string ": " s)
                      (rontolisp::%starlark-write (cdr cell) s))
                    (rontolisp::%starlark-dict-table x)))
         (write-string "}" s))
        ((rontolisp::%starlark-range-p x)
         (if (= (rontolisp::%starlark-range-step x) 1)
             (format s "range(~d, ~d)" (rontolisp::%starlark-range-start x)
                     (rontolisp::%starlark-range-stop x))
             (format s "range(~d, ~d, ~d)" (rontolisp::%starlark-range-start x)
                     (rontolisp::%starlark-range-stop x)
                     (rontolisp::%starlark-range-step x))))
        ((rontolisp::%starlark-string-iterable-p x)
         (rontolisp::%starlark-write-string
          (rontolisp::%starlark-string-iterable-string x) s)
         (format s ".~a()" (rontolisp::%starlark-string-iterable-name x)))
        ((rontolisp::%starlark-function-p x)
         (let ((kind (rontolisp::%starlark-function-kind x))
               (name (rontolisp::%starlark-function-name x)))
           (cond ((null kind) (format s "<function ~a>" name))
                 ((eq kind :builtin) (format s "<built-in function ~a>" name))
                 (t (format s "<built-in method ~a of ~a value>" name
                            (rontolisp::%starlark-type
                             (rontolisp::%starlark-function-self x)))))))
        ((functionp x) (write-string "<built-in function>" s))
        (t (write-string "<unknown>" s))))

(defun rontolisp::%starlark-b-repr (x)
  (with-output-to-string (s) (rontolisp::%starlark-write x s)))

(defun rontolisp::%starlark-b-str (x)
  (if (stringp x) x (rontolisp::%starlark-b-repr x)))

;; ARGS is a Common Lisp list: the `str` of each, SEP between them.
(defun rontolisp::%starlark-join-strings (args sep)
  (with-output-to-string (s)
    (let ((first t))
      (dolist (a args)
        (if first (setq first nil) (write-string sep s))
        (write-string (rontolisp::%starlark-b-str a) s)))))

;; --- arithmetic -------------------------------------------------------------------

(defun rontolisp::%starlark-binary-error (op a b)
  (error "unknown binary op: ~a ~a ~a" (rontolisp::%starlark-type a) op
         (rontolisp::%starlark-type b)))

;; V is a vector: its elements N times over, as a Common Lisp list.
(defun rontolisp::%starlark-repeat (v n)
  (let ((acc nil))
    (dotimes (i n) (dotimes (j (length v)) (push (aref v j) acc)))
    (nreverse acc)))

;; X is a string.
(defun rontolisp::%starlark-repeat-string (x n)
  (with-output-to-string (s) (dotimes (i n) (write-string x s))))

(defun rontolisp::%starlark-add (a b)
  (cond ((and (integerp a) (integerp b)) (+ a b))
        ((and (numberp a) (numberp b))
         (+ (rontolisp::%starlark-to-float a)
            (rontolisp::%starlark-to-float b)))
        ((and (stringp a) (stringp b)) (concatenate 'string a b))
        ((and (rontolisp::%starlark-list-p a) (rontolisp::%starlark-list-p b))
         (rontolisp::%starlark-list-of
          (append (rontolisp::%starlark-elements a)
                  (rontolisp::%starlark-elements b))))
        ((and (rontolisp::%starlark-tuple-p a) (rontolisp::%starlark-tuple-p b))
         (rontolisp::%starlark-tuple-of
          (append (rontolisp::%starlark-elements a)
                  (rontolisp::%starlark-elements b))))
        (t (rontolisp::%starlark-binary-error "+" a b))))

;; `a += b`: a list is extended in place, anything else is `a + b`.
(defun rontolisp::%starlark-add-in-place (a b)
  (if (and (rontolisp::%starlark-list-p a) (rontolisp::%starlark-list-p b))
      (let ((xs (rontolisp::%starlark-elements b)))
        (rontolisp::%starlark-list-writable a "extend")
        (dolist (x xs)
          (vector-push-extend x (rontolisp::%starlark-list-items a)))
        a)
      (rontolisp::%starlark-add a b)))

(defun rontolisp::%starlark-sub (a b)
  (cond ((and (integerp a) (integerp b)) (- a b))
        ((and (numberp a) (numberp b))
         (- (rontolisp::%starlark-to-float a)
            (rontolisp::%starlark-to-float b)))
        (t (rontolisp::%starlark-binary-error "-" a b))))

(defun rontolisp::%starlark-mul (a b)
  (cond ((and (integerp a) (integerp b)) (* a b))
   ((and (numberp a) (numberp b))
    (* (rontolisp::%starlark-to-float a) (rontolisp::%starlark-to-float b)))
   ((and (stringp a) (integerp b)) (rontolisp::%starlark-repeat-string a b))
   ((and (integerp a) (stringp b)) (rontolisp::%starlark-repeat-string b a))
   ((and (rontolisp::%starlark-list-p a) (integerp b))
    (rontolisp::%starlark-list-of
     (rontolisp::%starlark-repeat (rontolisp::%starlark-list-items a) b)))
   ((and (integerp a) (rontolisp::%starlark-list-p b))
    (rontolisp::%starlark-list-of
     (rontolisp::%starlark-repeat (rontolisp::%starlark-list-items b) a)))
   ((and (rontolisp::%starlark-tuple-p a) (integerp b))
    (rontolisp::%starlark-tuple-of
     (rontolisp::%starlark-repeat (rontolisp::%starlark-tuple-items a) b)))
   ((and (integerp a) (rontolisp::%starlark-tuple-p b))
    (rontolisp::%starlark-tuple-of
     (rontolisp::%starlark-repeat (rontolisp::%starlark-tuple-items b) a)))
   (t (rontolisp::%starlark-binary-error "*" a b))))

(defun rontolisp::%starlark-div (a b)
  (if (and (numberp a) (numberp b))
      (if (= b 0)
          (error "floating-point division by zero")
          (/ (rontolisp::%starlark-to-float a)
             (rontolisp::%starlark-to-float b)))
      (rontolisp::%starlark-binary-error "/" a b)))

(defun rontolisp::%starlark-floordiv (a b)
  (cond ((and (integerp a) (integerp b))
         (if (= b 0) (error "floored division by zero") (values (floor a b))))
        ((and (numberp a) (numberp b))
         (if (= b 0)
             (error "floored division by zero")
             (float (floor (rontolisp::%starlark-to-float a)
                           (rontolisp::%starlark-to-float b)) 1d0)))
        (t (rontolisp::%starlark-binary-error "//" a b))))

(defun rontolisp::%starlark-mod (a b)
  (cond ((and (integerp a) (integerp b))
         (if (= b 0) (error "integer modulo by zero") (mod a b)))
        ((and (numberp a) (numberp b))
         (if (= b 0)
             (error "floating-point modulo by zero")
             (mod (rontolisp::%starlark-to-float a)
                  (rontolisp::%starlark-to-float b))))
        ((stringp a)
         (rontolisp::%starlark-interpolate a
                                           (if (rontolisp::%starlark-tuple-p b)
                                               (rontolisp::%starlark-elements b)
                                               (list b))))
        (t (rontolisp::%starlark-binary-error "%" a b))))

(defun rontolisp::%starlark-negate (a)
  (if (numberp a)
      (- a)
      (error "unknown unary op: -~a" (rontolisp::%starlark-type a))))

(defun rontolisp::%starlark-plus (a)
  (if (numberp a)
      a
      (error "unknown unary op: +~a" (rontolisp::%starlark-type a))))

(defun rontolisp::%starlark-invert (a)
  (if (integerp a)
      (lognot a)
      (error "unknown unary op: ~~~a" (rontolisp::%starlark-type a))))

(defun rontolisp::%starlark-bit-and (a b)
  (if (and (integerp a) (integerp b))
      (logand a b)
      (rontolisp::%starlark-binary-error "&" a b)))

(defun rontolisp::%starlark-bit-xor (a b)
  (if (and (integerp a) (integerp b))
      (logxor a b)
      (rontolisp::%starlark-binary-error "^" a b)))

;; `|` is also the union of two dicts, the right one's values winning.
(defun rontolisp::%starlark-bit-or (a b)
  (cond ((and (integerp a) (integerp b)) (logior a b))
        ((and (rontolisp::%starlark-dict-p a) (rontolisp::%starlark-dict-p b))
         (let ((d (rontolisp::%starlark-empty-dict)))
           (rontolisp::%starlark-dict-merge d a "|")
           (rontolisp::%starlark-dict-merge d b "|")
           d))
        (t (rontolisp::%starlark-binary-error "|" a b))))

(defun rontolisp::%starlark-shift-left (a b)
  (cond ((not (and (integerp a) (integerp b)))
         (rontolisp::%starlark-binary-error "<<" a b))
        ((< b 0) (error "negative shift count: ~a" b))
        (t (ash a b))))

(defun rontolisp::%starlark-shift-right (a b)
  (cond ((not (and (integerp a) (integerp b)))
         (rontolisp::%starlark-binary-error ">>" a b))
        ((< b 0) (error "negative shift count: ~a" b))
        (t (ash a (- b)))))

;; --- indexing ---------------------------------------------------------------------

;; The index of element I of a sequence of length N; WHAT names the sequence.
(defun rontolisp::%starlark-element-index (i n what)
  (if (not (integerp i))
      (error "~a index: got ~a, want int" what (rontolisp::%starlark-type i)))
  (let ((j (if (< i 0) (+ i n) i)))
    (if (or (< j 0) (>= j n)) (error "~a index ~a out of range" what i))
    j))

(defun rontolisp::%starlark-index (x i)
  (cond ((rontolisp::%starlark-list-p x)
         (let ((items (rontolisp::%starlark-list-items x)))
           (aref items
                 (rontolisp::%starlark-element-index i (length items) "list"))))
        ((rontolisp::%starlark-tuple-p x)
         (let ((items (rontolisp::%starlark-tuple-items x)))
           (aref items
            (rontolisp::%starlark-element-index i (length items) "tuple"))))
        ((stringp x)
         (let ((j (rontolisp::%starlark-element-index i (length x) "string")))
           (subseq x j (+ j 1))))
        ((rontolisp::%starlark-dict-p x)
         (let ((cell (rontolisp::%starlark-dict-cell x i)))
           (if cell
               (cdr cell)
               (error "key ~a not found in dictionary"
                      (rontolisp::%starlark-b-repr i)))))
        ((rontolisp::%starlark-range-p x)
         (+ (rontolisp::%starlark-range-start x)
            (* (rontolisp::%starlark-range-step x)
               (rontolisp::%starlark-element-index i
                (rontolisp::%starlark-range-length x) "range"))))
        (t
         (error "unhandled index operation ~a[~a]" (rontolisp::%starlark-type x)
                (rontolisp::%starlark-type i)))))

(defun rontolisp::%starlark-set-index (x i v)
  (cond ((rontolisp::%starlark-list-p x)
         (rontolisp::%starlark-list-writable x "assign to element of")
         (let ((items (rontolisp::%starlark-list-items x)))
           (setf (aref items
                  (rontolisp::%starlark-element-index i (length items) "list"))
                 v)))
        ((rontolisp::%starlark-dict-p x)
         (rontolisp::%starlark-dict-writable x "insert into")
         (rontolisp::%starlark-dict-store x i v))
        (t (error "~a value does not support item assignment"
                  (rontolisp::%starlark-type x)))))

;; A slice bound I over a sequence of length N: DEFAULT when omitted, else moved by N
;; when negative and held within LO..HI.
(defun rontolisp::%starlark-slice-bound (i n lo hi default)
  (cond ((eq i :none) default)
        ((not (integerp i))
         (error "slice index: got ~a, want int" (rontolisp::%starlark-type i)))
        (t (let ((j (if (< i 0) (+ i n) i))) (max lo (min hi j))))))

;; The indices a slice selects of a sequence of length N, as a Common Lisp list.
(defun rontolisp::%starlark-slice-indices (n lo hi step)
  (if (not (or (eq step :none) (integerp step)))
      (error "slice step: got ~a, want int" (rontolisp::%starlark-type step)))
  (let ((st (if (eq step :none) 1 step)) (acc nil))
    (cond ((= st 0) (error "slice step cannot be zero"))
          ((> st 0)
           (let ((i (rontolisp::%starlark-slice-bound lo n 0 n 0))
                 (end (rontolisp::%starlark-slice-bound hi n 0 n n)))
             (loop while (< i end) do (push i acc) (setq i (+ i st)))))
          (t
           (let ((i (rontolisp::%starlark-slice-bound lo n -1 (- n 1) (- n 1)))
                 (end (rontolisp::%starlark-slice-bound hi n -1 (- n 1) -1)))
             (loop while (> i end) do (push i acc) (setq i (+ i st))))))
    (nreverse acc)))

;; V is a vector: the elements a slice selects, as a Common Lisp list.
(defun rontolisp::%starlark-slice-vector (v lo hi step)
  (mapcar (lambda (i) (aref v i))
          (rontolisp::%starlark-slice-indices (length v) lo hi step)))

(defun rontolisp::%starlark-slice (x lo hi step)
  (cond ((rontolisp::%starlark-list-p x)
         (rontolisp::%starlark-list-of
          (rontolisp::%starlark-slice-vector (rontolisp::%starlark-list-items x)
                                             lo hi step)))
        ((rontolisp::%starlark-tuple-p x)
         (rontolisp::%starlark-tuple-of
          (rontolisp::%starlark-slice-vector
           (rontolisp::%starlark-tuple-items x) lo hi step)))
        ((stringp x)
         (if (or (eq step :none) (eql step 1))
             (let* ((n (length x))
                    (from (rontolisp::%starlark-slice-bound lo n 0 n 0))
                    (to (rontolisp::%starlark-slice-bound hi n 0 n n)))
               (if (< from to) (subseq x from to) ""))
             (coerce (mapcar (lambda (i) (char x i))
                             (rontolisp::%starlark-slice-indices (length x) lo
                                                                 hi step))
                     'string)))
        ((rontolisp::%starlark-range-p x)
         (rontolisp::%starlark-list-of
          (rontolisp::%starlark-slice-vector
           (rontolisp::%starlark-range-vector x) lo hi step)))
        (t (error "invalid slice operand ~a" (rontolisp::%starlark-type x)))))

;; R is a range, X an integer: whether X is one of its integers, without listing them.
(defun rontolisp::%starlark-range-holds (r x)
  (let ((start (rontolisp::%starlark-range-start r))
        (stop (rontolisp::%starlark-range-stop r))
        (step (rontolisp::%starlark-range-step r)))
    (and
     (if (> step 0) (and (>= x start) (< x stop)) (and (<= x start) (> x stop)))
     (= (mod (- x start) step) 0))))

;; V is a vector: the index of the first element equal to X from START on and before
;; END, or nil.
(defun rontolisp::%starlark-position (x v start end)
  (let ((i start))
    (loop
      (if (>= i end) (return nil))
      (if (rontolisp::%starlark-equal x (aref v i)) (return i))
      (setq i (+ i 1)))))

(defun rontolisp::%starlark-in (x c)
  (cond ((stringp c)
         (if (stringp x)
             (if (search x c) t nil)
             (error "'in <string>' requires string as left operand, not ~a"
                    (rontolisp::%starlark-type x))))
        ((rontolisp::%starlark-dict-p c)
         (if (rontolisp::%starlark-dict-cell c x) t nil))
        ((rontolisp::%starlark-list-p c)
         (let ((items (rontolisp::%starlark-list-items c)))
           (if (rontolisp::%starlark-position x items 0 (length items)) t nil)))
        ((rontolisp::%starlark-tuple-p c)
         (let ((items (rontolisp::%starlark-tuple-items c)))
           (if (rontolisp::%starlark-position x items 0 (length items)) t nil)))
        ((rontolisp::%starlark-range-p c)
         (if (integerp x)
             (rontolisp::%starlark-range-holds c x)
             (let ((items (rontolisp::%starlark-range-vector c)))
               (if (rontolisp::%starlark-position x items 0 (length items))
                   t
                   nil))))
        (t (error "unknown binary op: ~a in ~a" (rontolisp::%starlark-type x)
                  (rontolisp::%starlark-type c)))))

;; --- calls ------------------------------------------------------------------------

;; F is a function: how an error names it.
(defun rontolisp::%starlark-function-title (f)
  (if (rontolisp::%starlark-function-kind f)
      (rontolisp::%starlark-function-name f)
      (concatenate 'string "function " (rontolisp::%starlark-function-name f))))

;; The index of the parameter NAME may fill by name, or nil.
(defun rontolisp::%starlark-named-parameter (name params from)
  (let ((i 0) (found nil))
    (dolist (p params)
      (if (and (null found) (>= i from) (string= p name)) (setq found i))
      (setq i (+ i 1)))
    found))

;; F is a function. Binds the positional arguments POS (a list) and the named ones KW
;; (name, value, name, value ...) to its parameters: the arguments its body takes.
(defun rontolisp::%starlark-bind (f pos kw)
  (let* ((params (rontolisp::%starlark-function-params f))
         (n (length params))
         (positional (rontolisp::%starlark-function-positional f))
         (named-from (rontolisp::%starlark-function-named-from f))
         (vals (make-array n :initial-element :%unset))
         (extra nil)
         (kwargs
          (if (rontolisp::%starlark-function-kwargs f)
              (rontolisp::%starlark-empty-dict)
              nil))
         (i 0))
    (dolist (a pos)
      (if (< i positional) (setf (aref vals i) a) (push a extra))
      (setq i (+ i 1)))
    (if (and extra (not (rontolisp::%starlark-function-varargs f)))
        (error "~a accepts at most ~a positional argument~a (~a given)"
               (rontolisp::%starlark-function-title f) positional
               (if (= positional 1) "" "s") (length pos)))
    (loop while kw
          do
            (let ((idx
                   (rontolisp::%starlark-named-parameter (car kw) params
                                                         named-from)))
              (cond
               ((and idx (eq (aref vals idx) :%unset))
                (setf (aref vals idx) (cadr kw)))
               (idx (error "~a got multiple values for parameter ~a"
                           (rontolisp::%starlark-function-title f) (car kw)))
               ((null kwargs)
                (error "~a got an unexpected keyword argument ~a"
                       (rontolisp::%starlark-function-title f) (car kw)))
               ((rontolisp::%starlark-dict-cell kwargs (car kw))
                (error "~a got multiple values for keyword argument ~a"
                       (rontolisp::%starlark-function-title f) (car kw)))
               (t (rontolisp::%starlark-dict-store kwargs (car kw) (cadr kw)))))
            (setq kw (cddr kw)))
    (let ((defaults (rontolisp::%starlark-function-defaults f)) (missing nil))
      (dotimes (j n)
        (if (eq (aref vals j) :%unset)
            (if (or (null defaults) (eq (car defaults) :%required))
                (push (nth j params) missing)
                (setf (aref vals j) (car defaults))))
        (setq defaults (cdr defaults)))
      (setq missing (nreverse missing))
      (cond ((null missing))
            ((rontolisp::%starlark-function-kind f)
             (error "~a: missing argument for ~a"
                    (rontolisp::%starlark-function-name f) (car missing)))
            (t (error "function ~a missing ~a argument~a (~a)"
                      (rontolisp::%starlark-function-name f) (length missing)
                      (if (cdr missing) "s" "")
                      (rontolisp::%starlark-join-strings missing ", ")))))
    (append (coerce vals 'list)
            (if (rontolisp::%starlark-function-varargs f)
                (list (rontolisp::%starlark-tuple-of (nreverse extra)))
                nil) (if kwargs (list kwargs) nil))))

;; A Common Lisp function is called with the positional arguments as they are.
(defun rontolisp::%starlark-call (f pos kw)
  (cond ((rontolisp::%starlark-function-p f)
         (let ((args (rontolisp::%starlark-bind f pos kw)))
           (if (eq (rontolisp::%starlark-function-kind f) :method)
               (apply (rontolisp::%starlark-function-body f)
                      (rontolisp::%starlark-function-self f) args)
               (apply (rontolisp::%starlark-function-body f) args))))
        ((functionp f)
         (if kw
             (error
              "a Common Lisp function got an unexpected keyword argument ~a"
              (car kw)))
         (apply f pos))
        (t (error "invalid call of non-function (~a)"
                  (rontolisp::%starlark-type f)))))

;; What Common Lisp calls a Starlark function, a built-in or a method value with: the
;; positional arguments, as they are. A call that does not return -- an error some
;; handler takes, a throw -- gives back what it left counted: the loops over a list or
;; a dict, and under --starlark-standard spec the declarations a call of which ran.
(defun rontolisp:starlark-call (f &rest args)
  (let ((frames rontolisp::%starlark-frames)
        (mutability rontolisp::%starlark-mutability))
    (unwind-protect (rontolisp::%starlark-call f args nil)
      (rontolisp::%starlark-unwind frames)
      (setq rontolisp::%starlark-mutability mutability))))

;; `load("@package", name = "symbol")`: the Common Lisp function FUNCTION as a built-in
;; function value named NAME, taking positional arguments only.
(defun rontolisp::%starlark-foreign (name function)
  (rontolisp::%starlark-new-function name nil nil 0 0 t nil
                                     (lambda (args)
                                       (apply function
                                              (coerce
                                               (rontolisp::%starlark-tuple-items
                                                args) 'list)))
                                     :builtin nil))

;; `load("module", "name")`: VALUE is what the global NAME of the module holds.
(defun rontolisp::%starlark-loaded (value name module)
  (if (eq value :%unbound)
      (error "load: name ~a not found in module ~a" name module)
      value))

;; Starts a file's run: every list and dict made until %starlark-file-end is made under
;; a cell of the file's own, which is answered.
(defun rontolisp::%starlark-file-begin ()
  (setq rontolisp::%starlark-mutability
        (cons nil rontolisp::%starlark-mutability)))

;; Ends the run CELL started: what was made under it is frozen, and what is made next is
;; made under the cell before it. Answers T, what a module's state is from then on.
(defun rontolisp::%starlark-file-end (cell)
  (rplaca cell t)
  (setq rontolisp::%starlark-mutability (cdr cell))
  t)

;; A read of the variable NAME holding VALUE where the lowering cannot tell it was
;; assigned: :%unbound is what it holds until then.
(defun rontolisp::%starlark-local (value name)
  (if (eq value :%unbound)
      (error "local variable ~a referenced before assignment" name)
      value))

(defun rontolisp::%starlark-global (value name)
  (if (eq value :%unbound)
      (error "global variable ~a referenced before assignment" name)
      value))

;; --starlark-standard spec: starts a call of the function NAME, whose declaration's
;; flag FLAG is -- a cons whose car is true while a call of it runs.
(defun rontolisp::%starlark-enter (flag name)
  (if (car flag) (error "function ~a called recursively" name))
  (rplaca flag t)
  (setq rontolisp::%starlark-frames (cons flag rontolisp::%starlark-frames)))

;; Ends the call %starlark-enter started.
(defun rontolisp::%starlark-leave (flag)
  (rplaca flag nil)
  (rontolisp::%starlark-pop-frame flag))

;; `x.name` where NAME is a method of the types KINDS: the method bound to X.
(defun rontolisp::%starlark-method
    (x kinds name params defaults positional named-from varargs kwargs body)
  (if (not (member (rontolisp::%starlark-type x) kinds :test #'string=))
      (rontolisp::%starlark-no-attribute x name))
  (rontolisp::%starlark-new-function name params defaults positional named-from
                                     varargs kwargs body
                                     :method x))

;; --- built-in functions -----------------------------------------------------------
;; A built-in takes its parameters in the order StarlarkBuiltins declares them, each
;; filled: :%unset stands for an optional argument that was not given.

(defun rontolisp::%starlark-b-len (x)
  (cond ((stringp x) (length x))
        ((rontolisp::%starlark-list-p x)
         (length (rontolisp::%starlark-list-items x)))
        ((rontolisp::%starlark-tuple-p x)
         (length (rontolisp::%starlark-tuple-items x)))
        ((rontolisp::%starlark-dict-p x)
         (hash-table-count (rontolisp::%starlark-dict-table x)))
        ((rontolisp::%starlark-range-p x) (rontolisp::%starlark-range-length x))
        (t (error "len: value of type ~a has no len"
                  (rontolisp::%starlark-type x)))))

;; WHO's `sep` argument.
(defun rontolisp::%starlark-separator (sep who)
  (if (stringp sep)
      sep
      (error "~a: for parameter sep: got ~a, want string" who
             (rontolisp::%starlark-type sep))))

(defun rontolisp::%starlark-b-print (sep args)
  (write-string
   (rontolisp::%starlark-join-strings (rontolisp::%starlark-elements args)
    (rontolisp::%starlark-separator sep "print")))
  (terpri)
  :none)

(defun rontolisp::%starlark-b-fail (sep args)
  (error "fail: ~a"
         (rontolisp::%starlark-join-strings (rontolisp::%starlark-elements args)
          (rontolisp::%starlark-separator sep "fail"))))

(defun rontolisp::%starlark-b-type (x) (rontolisp::%starlark-type x))

;; The attributes of X are the methods of its type: %starlark-attribute-names and
;; %starlark-attribute are generated from the front end's table of them (Starlark.java).
(defun rontolisp::%starlark-b-dir (x)
  (rontolisp::%starlark-list-of
   (copy-list
    (rontolisp::%starlark-attribute-names (rontolisp::%starlark-type x)))))

;; Whether X has the attribute NAME, which WHO's call names.
(defun rontolisp::%starlark-has-attribute (x name who)
  (if (not (stringp name))
      (error "~a: for parameter name: got ~a, want string" who
             (rontolisp::%starlark-type name)))
  (if (member name
       (rontolisp::%starlark-attribute-names (rontolisp::%starlark-type x))
       :test #'string=)
      t
      nil))

(defun rontolisp::%starlark-b-hasattr (x name)
  (rontolisp::%starlark-has-attribute x name "hasattr"))

(defun rontolisp::%starlark-b-getattr (x name default)
  (cond ((rontolisp::%starlark-has-attribute x name "getattr")
         (rontolisp::%starlark-attribute x name))
        ((eq default :%unset)
         (error "getattr: ~a has no .~a field or method"
                (rontolisp::%starlark-type x) name))
        (t default)))

(defun rontolisp::%starlark-b-bool (x) (rontolisp::%starlark-truth x))

(defun rontolisp::%starlark-b-list (x)
  (if (eq x :%unset)
      (rontolisp::%starlark-make-list)
      (rontolisp::%starlark-list-of (rontolisp::%starlark-elements x))))

(defun rontolisp::%starlark-b-tuple (x)
  (cond ((eq x :%unset) (rontolisp::%starlark-make-tuple))
        ((rontolisp::%starlark-tuple-p x) x)
        (t (rontolisp::%starlark-tuple-of (rontolisp::%starlark-elements x)))))

(defun rontolisp::%starlark-b-dict (pairs kwargs)
  (let ((d (rontolisp::%starlark-empty-dict)))
    (if (not (eq pairs :%unset))
        (rontolisp::%starlark-dict-merge d pairs "dict"))
    (rontolisp::%starlark-dict-merge d kwargs "dict")
    d))

(defun rontolisp::%starlark-b-range (a b c)
  (if (not (and (integerp a) (or (eq b :%unset) (integerp b)) (integerp c)))
      (error "range: got ~a, want int"
       (rontolisp::%starlark-type (if (integerp a) (if (integerp b) c b) a))))
  (if (= c 0) (error "range: step argument must not be zero"))
  (if (eq b :%unset)
      (rontolisp::%starlark-new-range 0 a 1)
      (rontolisp::%starlark-new-range a b c)))

(defun rontolisp::%starlark-digit (c)
  (let ((code (char-code c)))
    (cond ((and (>= code 48) (<= code 57)) (- code 48))
          ((and (>= code 97) (<= code 122)) (+ 10 (- code 97)))
          ((and (>= code 65) (<= code 90)) (+ 10 (- code 65)))
          (t 99))))

;; TEXT is a string, BASE an integer.
(defun rontolisp::%starlark-parse-int (text base)
  (let* ((s text) (n (length s)) (i 0) (neg nil) (acc 0) (b base))
    (if (and (< i n) (or (char= (char s i) #\-) (char= (char s i) #\+)))
        (progn
          (setq neg (char= (char s i) #\-))
          (setq i (+ i 1))))
    (if (and (< (+ i 1) n) (char= (char s i) #\0))
        (let ((p (char-downcase (char s (+ i 1)))))
          (cond ((and (char= p #\x) (or (= b 0) (= b 16)))
                 (setq b 16)
                 (setq i (+ i 2)))
                ((and (char= p #\o) (or (= b 0) (= b 8)))
                 (setq b 8)
                 (setq i (+ i 2)))
                ((and (char= p #\b) (or (= b 0) (= b 2)))
                 (setq b 2)
                 (setq i (+ i 2))))))
    (if (= b 0) (setq b 10))
    (if (>= i n)
        (error "int: invalid literal with base ~a: ~a" base
               (rontolisp::%starlark-b-repr text)))
    (loop while (< i n)
          do
            (let ((d (rontolisp::%starlark-digit (char s i))))
              (if (>= d b)
                  (error "int: invalid literal with base ~a: ~a" base
                         (rontolisp::%starlark-b-repr text)))
              (setq acc (+ (* acc b) d))
              (setq i (+ i 1))))
    (if neg (- acc) acc)))

(defun rontolisp::%starlark-b-int (x base)
  (cond
   ((stringp x)
    (cond ((eq base :%unset) (rontolisp::%starlark-parse-int x 10))
          ((not (integerp base))
           (error "int: for parameter base: got ~a, want int"
                  (rontolisp::%starlark-type base)))
          ((or (< base 0) (= base 1) (> base 36))
           (error "int: base must be an integer >= 2 && <= 36"))
          (t (rontolisp::%starlark-parse-int x base))))
   ((not (eq base :%unset))
    (error "int: can't convert non-string with explicit base"))
   ((integerp x) x)
   ((floatp x)
    (if (and (<= x 1.7976931348623157d308) (>= x -1.7976931348623157d308))
        (values (truncate x))
        (error "int: cannot convert float ~a to integer"
               (rontolisp::%starlark-float-string x))))
   ((eq x t) 1)
   ((null x) 0)
   (t (error "int: cannot convert ~a to int" (rontolisp::%starlark-type x)))))

;; X is an int: the nearest float, an error when that is an infinity -- when |X| is at
;; least the halfway point between the greatest float and 2^1024.
(defun rontolisp::%starlark-int-float (x)
  (if (>= (abs x) (- (expt 2 1024) (expt 2 970)))
      (error "int too large to convert to float")
      (float x 1d0)))

;; The digits of S from I short of N: the integer they spell and how many there were.
(defun rontolisp::%starlark-scan-digits (s i n)
  (let ((acc 0) (count 0))
    (loop while (and (< i n) (<= 48 (char-code (char s i)) 57))
          do (setq acc (+ (* acc 10) (- (char-code (char s i)) 48)))
            (setq count (+ count 1)) (setq i (+ i 1)))
    (values acc count)))

;; The float the string S spells, as Go's float() reads one: an optional sign, then
;; inf, infinity or nan in any case, or digits with an optional point and fraction and an
;; optional exponent.
(defun rontolisp::%starlark-parse-float (s)
  (let* ((n (length s)) (i 0) (neg nil) (word nil))
    (if (= n 0) (error "float: empty string"))
    (if (or (char= (char s 0) #\+) (char= (char s 0) #\-))
        (progn
          (setq neg (char= (char s 0) #\-))
          (setq i 1)))
    (setq word (string-downcase (subseq s i)))
    (cond ((or (string= word "inf") (string= word "infinity"))
           (if neg (/ -1d0 0d0) (/ 1d0 0d0)))
          ((string= word "nan") (/ 0d0 0d0))
          (t
           (multiple-value-bind (whole whole-digits)
               (rontolisp::%starlark-scan-digits s i n)
             (let ((mantissa whole)
                   (digits whole-digits)
                   (places 0)
                   (exponent 0))
               (setq i (+ i whole-digits))
               (if (and (< i n) (char= (char s i) #\.))
                   (multiple-value-bind (fraction count)
                       (rontolisp::%starlark-scan-digits s (+ i 1) n)
                     (setq mantissa (+ (* mantissa (expt 10 count)) fraction))
                     (setq digits (+ digits count))
                     (setq places count)
                     (setq i (+ i 1 count))))
               (if (and (> digits 0) (< i n)
                        (or (char= (char s i) #\e) (char= (char s i) #\E)))
                   (let ((j (+ i 1)) (eneg nil))
                     (if (and (< j n)
                          (or (char= (char s j) #\+) (char= (char s j) #\-)))
                         (progn
                           (setq eneg (char= (char s j) #\-))
                           (setq j (+ j 1))))
                     (multiple-value-bind (e count)
                         (rontolisp::%starlark-scan-digits s j n)
                       (if (= count 0) (setq digits 0))
                       (setq exponent (if eneg (- e) e))
                       (setq i (+ j count)))))
               (if (or (= digits 0) (< i n))
                   (error "invalid float literal: ~a" s))
               (let* ((scale (- exponent places))
                      (magnitude
                       (if (= mantissa 0)
                           0
                           (+ (length (format nil "~d" mantissa)) scale)))
                      (v
                       (cond ((= mantissa 0) 0d0)
                        ((> magnitude 310)
                         (error "floating-point number too large"))
                        ((< magnitude -330) 0d0)
                        ((>= scale 0) (float (* mantissa (expt 10 scale)) 1d0))
                        (t (float (/ mantissa (expt 10 (- scale))) 1d0)))))
                 (if (> v 1.7976931348623157d308)
                     (error "floating-point number too large"))
                 (if neg (- v) v))))))))

;; X is a number: itself when a float, else the nearest float.
(defun rontolisp::%starlark-to-float (x)
  (if (floatp x) x (rontolisp::%starlark-int-float x)))

(defun rontolisp::%starlark-b-float (x)
  (cond ((eq x t) 1d0)
        ((null x) 0d0)
        ((integerp x) (rontolisp::%starlark-int-float x))
        ((floatp x) x)
        ((stringp x) (rontolisp::%starlark-parse-float x))
        (t (error "float got ~a, want number or string"
                  (rontolisp::%starlark-type x)))))

(defun rontolisp::%starlark-b-abs (x)
  (if (numberp x)
      (abs x)
      (error "abs: got ~a, want int or float" (rontolisp::%starlark-type x))))

;; java.lang.String.hashCode over the UTF-16 form of the string, as the specification
;; gives it: the same number on every backend.
(defun rontolisp::%starlark-b-hash (x)
  (if (not (stringp x))
      (error "hash: got ~a, want string" (rontolisp::%starlark-type x)))
  (let ((h 0))
    (dotimes (i (length x))
      (let ((c (char-code (char x i))))
        (if (< c 65536)
            (setq h (logand (+ (* h 31) c) 4294967295))
            (let ((v (- c 65536)))
              (setq h (logand (+ (* h 31) 55296 (ash v -10)) 4294967295))
              (setq h
                    (logand (+ (* h 31) 56320 (logand v 1023)) 4294967295))))))
    (if (>= h 2147483648) (- h 4294967296) h)))

(defun rontolisp::%starlark-b-any (x)
  (let ((found nil))
    (dolist (e (rontolisp::%starlark-elements x))
      (if (and (not found) (rontolisp::%starlark-truth e)) (setq found t)))
    found))

(defun rontolisp::%starlark-b-all (x)
  (let ((every t))
    (dolist (e (rontolisp::%starlark-elements x))
      (if (and every (not (rontolisp::%starlark-truth e))) (setq every nil)))
    every))

(defun rontolisp::%starlark-b-reversed (x)
  (rontolisp::%starlark-list-of (reverse (rontolisp::%starlark-elements x))))

(defun rontolisp::%starlark-b-enumerate (x start)
  (if (not (integerp start))
      (error "enumerate: for parameter start: got ~a, want int"
             (rontolisp::%starlark-type start)))
  (let ((acc nil) (i start))
    (dolist (e (rontolisp::%starlark-elements x))
      (push (rontolisp::%starlark-make-tuple i e) acc)
      (setq i (+ i 1)))
    (rontolisp::%starlark-list-of (nreverse acc))))

(defun rontolisp::%starlark-b-zip (args)
  (let ((lists nil) (i 0) (acc nil))
    (dolist (x (rontolisp::%starlark-elements args))
      (setq i (+ i 1))
      (if (not (rontolisp::%starlark-iterable-p x))
          (error "zip: argument #~a is not iterable: ~a" i
                 (rontolisp::%starlark-type x)))
      (push (rontolisp::%starlark-elements x) lists))
    (setq lists (nreverse lists))
    (if lists
        (loop while (not (member nil lists))
              do (push (rontolisp::%starlark-tuple-of (mapcar #'car lists)) acc)
                (setq lists (mapcar #'cdr lists))))
    (rontolisp::%starlark-list-of (nreverse acc))))

;; What `key` stands for: nil for None, else a function of one element.
(defun rontolisp::%starlark-key-function (key)
  (if (eq key :none)
      nil
      (lambda (x) (rontolisp::%starlark-call key (list x) nil))))

(defun rontolisp::%starlark-b-sorted (x key reverse)
  (let* ((key-function (rontolisp::%starlark-key-function key))
         (pairs
          (mapcar
           (lambda (e) (cons (if key-function (funcall key-function e) e) e))
           (rontolisp::%starlark-elements x)))
         (sorted
          (stable-sort pairs
                       (if (rontolisp::%starlark-truth reverse)
                           (lambda (a b)
                             (rontolisp::%starlark-less (car b) (car a)))
                           (lambda (a b)
                             (rontolisp::%starlark-less (car a) (car b)))))))
    (rontolisp::%starlark-list-of (mapcar #'cdr sorted))))

;; `min` (WANT -1) and `max` (WANT 1) over ARGS, a tuple: its one iterable, or its
;; elements.
(defun rontolisp::%starlark-extreme (name args key want)
  (let* ((given (rontolisp::%starlark-elements args))
         (xs
          (if (and given (null (cdr given)))
              (rontolisp::%starlark-elements-or (car given)
               (concatenate 'string name ": ~a value is not iterable"))
              given))
         (key-function (rontolisp::%starlark-key-function key)))
    (if (null xs) (error "~a: empty sequence" name))
    (let* ((best (car xs))
           (best-key (if key-function (funcall key-function best) best)))
      (dolist (x (cdr xs))
        (let ((k (if key-function (funcall key-function x) x)))
          (if (= (rontolisp::%starlark-compare k best-key) want)
              (progn
                (setq best x)
                (setq best-key k)))))
      best)))

(defun rontolisp::%starlark-b-min (key args)
  (rontolisp::%starlark-extreme "min" args key -1))

(defun rontolisp::%starlark-b-max (key args)
  (rontolisp::%starlark-extreme "max" args key 1))

;; --- list and dict methods --------------------------------------------------------
;; A method takes its receiver first and tests it before anything else: a receiver of
;; another type has no such method.

;; The items of L, the receiver of the list method NAME that mutates it as VERB says.
(defun rontolisp::%starlark-list-receiver (l name verb)
  (if (not (rontolisp::%starlark-list-p l))
      (rontolisp::%starlark-no-attribute l name))
  (rontolisp::%starlark-list-writable l verb)
  (rontolisp::%starlark-list-items l))

;; L is a list: replaces its elements by those of the Common Lisp list XS.
(defun rontolisp::%starlark-list-replace (l xs)
  (let ((v (rontolisp::%starlark-vector)))
    (dolist (x xs) (vector-push-extend x v))
    (setf (rontolisp::%starlark-list-items l) v)))

(defun rontolisp::%starlark-m-append (l x)
  (vector-push-extend x
   (rontolisp::%starlark-list-receiver l "append" "append to"))
  :none)

(defun rontolisp::%starlark-m-extend (l xs)
  (if (not (rontolisp::%starlark-list-p l))
      (rontolisp::%starlark-no-attribute l "extend"))
  (let ((elements (rontolisp::%starlark-elements xs)))
    (rontolisp::%starlark-list-writable l "extend")
    (dolist (x elements)
      (vector-push-extend x (rontolisp::%starlark-list-items l))))
  :none)

(defun rontolisp::%starlark-m-insert (l i x)
  (let* ((items (rontolisp::%starlark-list-receiver l "insert" "insert into"))
         (xs (coerce items 'list))
         (n (length xs)))
    (if (not (integerp i))
        (error "insert: for parameter index: got ~a, want int"
               (rontolisp::%starlark-type i)))
    (let ((j (max 0 (min n (if (< i 0) (+ i n) i)))))
      (rontolisp::%starlark-list-replace l
       (append (subseq xs 0 j) (list x) (nthcdr j xs)))))
  :none)

(defun rontolisp::%starlark-m-remove (l x)
  (let ((items (rontolisp::%starlark-list-receiver l "remove" "remove from"))
        (acc nil)
        (found nil))
    (dotimes (i (length items))
      (let ((e (aref items i)))
        (if (and (not found) (rontolisp::%starlark-equal e x))
            (setq found t)
            (push e acc))))
    (if (not found) (error "remove: element not found"))
    (rontolisp::%starlark-list-replace l (nreverse acc)))
  :none)

(defun rontolisp::%starlark-m-clear (x)
  (cond ((rontolisp::%starlark-list-p x)
         (rontolisp::%starlark-list-writable x "clear")
         (rontolisp::%starlark-list-replace x nil))
        ((rontolisp::%starlark-dict-p x)
         (rontolisp::%starlark-dict-writable x "clear")
         (clrhash (rontolisp::%starlark-dict-table x)))
        (t (rontolisp::%starlark-no-attribute x "clear")))
  :none)

;; list.pop([index]) and dict.pop(key[, default]).
(defun rontolisp::%starlark-m-pop (x a b)
  (cond ((rontolisp::%starlark-list-p x)
         (if (not (eq b :%unset))
             (error "pop: got 2 arguments, want at most 1"))
         (rontolisp::%starlark-list-writable x "pop from")
         (let* ((xs (coerce (rontolisp::%starlark-list-items x) 'list))
                (n (length xs))
                (j
                 (rontolisp::%starlark-element-index (if (eq a :%unset) -1 a) n
                                                     "pop")))
           (rontolisp::%starlark-list-replace x
            (append (subseq xs 0 j) (nthcdr (+ j 1) xs)))
           (nth j xs)))
        ((rontolisp::%starlark-dict-p x)
         (if (eq a :%unset) (error "pop: missing argument for key"))
         (rontolisp::%starlark-dict-writable x "delete from")
         (let ((cell (rontolisp::%starlark-dict-cell x a)))
           (cond (cell
                  (remhash (rontolisp::%starlark-key a)
                           (rontolisp::%starlark-dict-table x))
                  (cdr cell))
                 ((eq b :%unset) (error "pop: missing key"))
                 (t b))))
        (t (rontolisp::%starlark-no-attribute x "pop"))))

;; The dict D, the receiver of the dict method NAME.
(defun rontolisp::%starlark-dict-receiver (d name)
  (if (rontolisp::%starlark-dict-p d)
      d
      (rontolisp::%starlark-no-attribute d name)))

(defun rontolisp::%starlark-m-get (d k default)
  (let ((cell
         (rontolisp::%starlark-dict-cell
          (rontolisp::%starlark-dict-receiver d "get") k)))
    (if cell (cdr cell) default)))

(defun rontolisp::%starlark-m-setdefault (d k default)
  (let ((cell
         (rontolisp::%starlark-dict-cell
          (rontolisp::%starlark-dict-receiver d "setdefault") k)))
    (if cell
        (cdr cell)
        (progn
          (rontolisp::%starlark-dict-writable d "insert into")
          (rontolisp::%starlark-dict-store d k default)))))

(defun rontolisp::%starlark-m-keys (d)
  (rontolisp::%starlark-list-of
   (rontolisp::%starlark-dict-key-list
    (rontolisp::%starlark-dict-receiver d "keys"))))

(defun rontolisp::%starlark-m-values (d)
  (let ((acc nil))
    (maphash (lambda (k cell) (push (cdr cell) acc))
             (rontolisp::%starlark-dict-table
              (rontolisp::%starlark-dict-receiver d "values")))
    (rontolisp::%starlark-list-of (nreverse acc))))

(defun rontolisp::%starlark-m-items (d)
  (let ((acc nil))
    (maphash (lambda (k cell)
               (push (rontolisp::%starlark-make-tuple (car cell) (cdr cell))
                     acc))
             (rontolisp::%starlark-dict-table
              (rontolisp::%starlark-dict-receiver d "items")))
    (rontolisp::%starlark-list-of (nreverse acc))))

(defun rontolisp::%starlark-m-update (d pairs kwargs)
  (rontolisp::%starlark-dict-writable
   (rontolisp::%starlark-dict-receiver d "update") "insert into")
  (if (not (eq pairs :%unset))
      (rontolisp::%starlark-dict-merge d pairs "update"))
  (rontolisp::%starlark-dict-merge d kwargs "update")
  :none)

(defun rontolisp::%starlark-m-popitem (d)
  (rontolisp::%starlark-dict-writable
   (rontolisp::%starlark-dict-receiver d "popitem") "delete from")
  (let ((keys (rontolisp::%starlark-dict-key-list d)))
    (if (null keys) (error "popitem: empty dict"))
    (let ((cell (rontolisp::%starlark-dict-cell d (car keys))))
      (remhash (rontolisp::%starlark-key (car keys))
               (rontolisp::%starlark-dict-table d))
      (rontolisp::%starlark-make-tuple (car cell) (cdr cell)))))

;; --- string methods ---------------------------------------------------------------

;; The string S, the receiver of the string method NAME.
(defun rontolisp::%starlark-string-receiver (s name)
  (if (stringp s) s (rontolisp::%starlark-no-attribute s name)))

;; The string argument X of the method NAME.
(defun rontolisp::%starlark-string-argument (x name)
  (if (stringp x)
      x
      (error "~a: got ~a, want string" name (rontolisp::%starlark-type x))))

;; list.index(x[, start[, end]]) and string.index(sub[, start[, end]]).
(defun rontolisp::%starlark-m-index (x y start end)
  (cond ((stringp x)
         (let ((r (rontolisp::%starlark-find x y start end nil "index")))
           (if (< r 0) (error "substring not found") r)))
        ((rontolisp::%starlark-list-p x)
         (let* ((items (rontolisp::%starlark-list-items x))
                (n (length items))
                (found
                 (rontolisp::%starlark-position y items
                  (rontolisp::%starlark-slice-bound start n 0 n 0)
                  (rontolisp::%starlark-slice-bound end n 0 n n))))
           (if found found (error "index: value not in list"))))
        (t (rontolisp::%starlark-no-attribute x "index"))))

;; The index of SUB in S between START and END, the last one when FROM-END, or -1.
(defun rontolisp::%starlark-find (s sub start end from-end name)
  (rontolisp::%starlark-string-receiver s name)
  (rontolisp::%starlark-string-argument sub name)
  (let* ((n (length s))
         (lo (rontolisp::%starlark-slice-bound start n 0 n 0))
         (hi (rontolisp::%starlark-slice-bound end n 0 n n)))
    (if (> lo hi)
        -1
        (let ((r
               (if from-end
                   (search sub s :start2 lo :end2 hi :from-end t)
                   (search sub s :start2 lo :end2 hi))))
          (if r r -1)))))

(defun rontolisp::%starlark-m-find (s sub start end)
  (rontolisp::%starlark-find s sub start end nil "find"))

(defun rontolisp::%starlark-m-rfind (s sub start end)
  (rontolisp::%starlark-find s sub start end t "rfind"))

(defun rontolisp::%starlark-m-rindex (s sub start end)
  (let ((r (rontolisp::%starlark-find s sub start end t "rindex")))
    (if (< r 0) (error "substring not found") r)))

(defun rontolisp::%starlark-m-count (s sub start end)
  (rontolisp::%starlark-string-receiver s "count")
  (rontolisp::%starlark-string-argument sub "count")
  (let* ((n (length s))
         (i (rontolisp::%starlark-slice-bound start n 0 n 0))
         (hi (rontolisp::%starlark-slice-bound end n 0 n n))
         (c 0)
         (step (max 1 (length sub))))
    (loop
      (let ((r (if (> i hi) nil (search sub s :start2 i :end2 hi))))
        (if (null r) (return c))
        (setq c (+ c 1))
        (setq i (+ r step))))))

;; Whether S between START and END begins (or, when AT-END, ends) with P, a string or
;; a tuple of strings.
(defun rontolisp::%starlark-affix (s p start end at-end name)
  (rontolisp::%starlark-string-receiver s name)
  (let* ((n (length s))
         (lo (rontolisp::%starlark-slice-bound start n 0 n 0))
         (hi (rontolisp::%starlark-slice-bound end n 0 n n))
         (found nil))
    (dolist (q
             (if (rontolisp::%starlark-tuple-p p)
                 (rontolisp::%starlark-elements p)
                 (list p)))
      (if (not (stringp q))
          (error "~a: got ~a, want string or tuple of string" name
                 (rontolisp::%starlark-type q)))
      (let ((m (length q)))
        (if (and (not found) (<= (+ lo m) hi)
                 (if at-end
                     (string= s q :start1 (- hi m) :end1 hi)
                     (string= s q :start1 lo :end1 (+ lo m))))
            (setq found t))))
    found))

(defun rontolisp::%starlark-m-startswith (s p start end)
  (rontolisp::%starlark-affix s p start end nil "startswith"))

(defun rontolisp::%starlark-m-endswith (s p start end)
  (rontolisp::%starlark-affix s p start end t "endswith"))

(defun rontolisp::%starlark-m-upper (s)
  (string-upcase (rontolisp::%starlark-string-receiver s "upper")))

(defun rontolisp::%starlark-m-lower (s)
  (string-downcase (rontolisp::%starlark-string-receiver s "lower")))

;; The characters a strip removes: white space, or those of the string CUTSET.
(defun rontolisp::%starlark-cutset (cutset name)
  (if (eq cutset :none)
      (list #\Space #\Tab #\Newline #\Return)
      (coerce (rontolisp::%starlark-string-argument cutset name) 'list)))

(defun rontolisp::%starlark-m-strip (s cutset)
  (string-trim (rontolisp::%starlark-cutset cutset "strip")
               (rontolisp::%starlark-string-receiver s "strip")))

(defun rontolisp::%starlark-m-lstrip (s cutset)
  (string-left-trim (rontolisp::%starlark-cutset cutset "lstrip")
                    (rontolisp::%starlark-string-receiver s "lstrip")))

(defun rontolisp::%starlark-m-rstrip (s cutset)
  (string-right-trim (rontolisp::%starlark-cutset cutset "rstrip")
                     (rontolisp::%starlark-string-receiver s "rstrip")))

(defun rontolisp::%starlark-m-join (sep xs)
  (rontolisp::%starlark-string-receiver sep "join")
  (with-output-to-string (s)
    (let ((first t))
      (dolist (x (rontolisp::%starlark-elements xs))
        (if (not (stringp x))
            (error "join: in list, want string, got ~a"
                   (rontolisp::%starlark-type x)))
        (if first (setq first nil) (write-string sep s))
        (write-string x s)))))

(defun rontolisp::%starlark-m-replace (s old new count)
  (rontolisp::%starlark-string-receiver s "replace")
  (rontolisp::%starlark-string-argument old "replace")
  (rontolisp::%starlark-string-argument new "replace")
  (if (not (or (eq count :%unset) (integerp count)))
      (error "replace: for parameter count: got ~a, want int"
             (rontolisp::%starlark-type count)))
  (with-output-to-string (out)
    (let ((i 0)
          (n (length s))
          (c (if (or (eq count :%unset) (< count 0)) -1 count)))
      (loop
        (let ((r (if (= c 0) nil (search old s :start2 i))))
          (if (or (null r) (and (= (length old) 0) (>= i n)))
              (progn
                (write-string (subseq s i) out)
                (return nil)))
          (write-string (subseq s i r) out)
          (write-string new out)
          (if (= (length old) 0)
              (progn
                (write-string (subseq s r (+ r 1)) out)
                (setq i (+ r 1)))
              (setq i (+ r (length old))))
          (setq c (- c 1)))))))

;; S is a string: its runs of other than white space.
(defun rontolisp::%starlark-fields (s)
  (let ((acc nil) (cur nil))
    (dotimes (i (length s))
      (let ((c (char s i)))
        (if (member c (list #\Space #\Tab #\Newline #\Return))
            (if cur
                (progn
                  (push (coerce (nreverse cur) 'string) acc)
                  (setq cur nil)))
            (push c cur))))
    (if cur (push (coerce (nreverse cur) 'string) acc))
    (nreverse acc)))

;; The `maxsplit` argument of the method NAME: -1 for no limit.
(defun rontolisp::%starlark-split-limit (maxsplit name)
  (cond ((eq maxsplit :none) -1)
        ((integerp maxsplit) (if (< maxsplit 0) -1 maxsplit))
        (t (error "~a: for parameter maxsplit: got ~a, want int" name
                  (rontolisp::%starlark-type maxsplit)))))

(defun rontolisp::%starlark-m-split (s sep maxsplit)
  (rontolisp::%starlark-string-receiver s "split")
  (let ((c (rontolisp::%starlark-split-limit maxsplit "split")))
    (if (eq sep :none)
        (rontolisp::%starlark-list-of (rontolisp::%starlark-fields s))
        (let ((acc nil) (i 0))
          (rontolisp::%starlark-string-argument sep "split")
          (if (= (length sep) 0) (error "split: empty separator"))
          (loop
            (let ((r (if (= c 0) nil (search sep s :start2 i))))
              (if (null r)
                  (progn
                    (push (subseq s i) acc)
                    (return nil)))
              (push (subseq s i r) acc)
              (setq i (+ r (length sep)))
              (setq c (- c 1))))
          (rontolisp::%starlark-list-of (nreverse acc))))))

(defun rontolisp::%starlark-m-rsplit (s sep maxsplit)
  (rontolisp::%starlark-string-receiver s "rsplit")
  (let ((c (rontolisp::%starlark-split-limit maxsplit "rsplit")))
    (if (or (eq sep :none) (< c 0))
        (rontolisp::%starlark-m-split s sep maxsplit)
        (let ((acc nil) (end (length s)))
          (rontolisp::%starlark-string-argument sep "rsplit")
          (if (= (length sep) 0) (error "rsplit: empty separator"))
          (loop
            (let ((r (if (= c 0) nil (search sep s :end2 end :from-end t))))
              (if (null r)
                  (progn
                    (push (subseq s 0 end) acc)
                    (return nil)))
              (push (subseq s (+ r (length sep)) end) acc)
              (setq end r)
              (setq c (- c 1))))
          (rontolisp::%starlark-list-of acc)))))

(defun rontolisp::%starlark-m-partition (s sep)
  (rontolisp::%starlark-string-receiver s "partition")
  (rontolisp::%starlark-string-argument sep "partition")
  (if (= (length sep) 0) (error "partition: empty separator"))
  (let ((r (search sep s)))
    (if r
        (rontolisp::%starlark-make-tuple (subseq s 0 r) sep
                                         (subseq s (+ r (length sep))))
        (rontolisp::%starlark-make-tuple s "" ""))))

(defun rontolisp::%starlark-m-rpartition (s sep)
  (rontolisp::%starlark-string-receiver s "rpartition")
  (rontolisp::%starlark-string-argument sep "rpartition")
  (if (= (length sep) 0) (error "rpartition: empty separator"))
  (let ((r (search sep s :from-end t)))
    (if r
        (rontolisp::%starlark-make-tuple (subseq s 0 r) sep
                                         (subseq s (+ r (length sep))))
        (rontolisp::%starlark-make-tuple "" "" s))))

;; The iterable over the elements of S that the string method NAME answers.
(defun rontolisp::%starlark-string-iterable (s name ords)
  (rontolisp::%starlark-new-string-iterable
   (rontolisp::%starlark-string-receiver s name) name ords))

(defun rontolisp::%starlark-m-elems (s)
  (rontolisp::%starlark-string-iterable s "elems" nil))

(defun rontolisp::%starlark-m-elem_ords (s)
  (rontolisp::%starlark-string-iterable s "elem_ords" t))

(defun rontolisp::%starlark-m-codepoints (s)
  (rontolisp::%starlark-string-iterable s "codepoints" nil))

(defun rontolisp::%starlark-m-codepoint_ords (s)
  (rontolisp::%starlark-string-iterable s "codepoint_ords" t))

;; The lines of S, split at \n, \r and \r\n, each with its line ending when KEEPENDS.
(defun rontolisp::%starlark-m-splitlines (s keepends)
  (rontolisp::%starlark-string-receiver s "splitlines")
  (if (not (or (eq keepends t) (null keepends)))
      (error "splitlines: for parameter keepends: got ~a, want bool"
             (rontolisp::%starlark-type keepends)))
  (let ((acc nil) (start 0) (i 0) (n (length s)))
    (loop while (< i n)
          do
            (let ((c (char s i)))
              (if (or (char= c #\Newline) (char= c #\Return))
                  (let ((end
                         (if (and (char= c #\Return) (< (+ i 1) n)
                                  (char= (char s (+ i 1)) #\Newline))
                             (+ i 2)
                             (+ i 1))))
                    (push (subseq s start (if keepends end i)) acc)
                    (setq start end)
                    (setq i end))
                  (setq i (+ i 1)))))
    (if (< start n) (push (subseq s start) acc))
    (rontolisp::%starlark-list-of (nreverse acc))))

;; The case of the character C: :upper, :lower or :title by the case mappings it has
;; (a titlecase letter has both), or nil when it is uncased.
(defun rontolisp::%starlark-char-case (c)
  (let ((up (char/= (char-upcase c) c)) (down (char/= (char-downcase c) c)))
    (cond ((and up down) :title) (up :lower) (down :upper) (t nil))))

;; Whether S has a cased letter and every one is of the case WANT (:lower or :upper).
(defun rontolisp::%starlark-all-cased (s want name)
  (rontolisp::%starlark-string-receiver s name)
  (let ((cased nil) (ok t))
    (dotimes (i (length s))
      (let ((k (rontolisp::%starlark-char-case (char s i))))
        (if k
            (progn
              (setq cased t)
              (if (not (or (eq k want) (and (eq want :upper) (eq k :title))))
                  (setq ok nil))))))
    (and cased ok)))

(defun rontolisp::%starlark-m-islower (s)
  (rontolisp::%starlark-all-cased s :lower "islower"))

(defun rontolisp::%starlark-m-isupper (s)
  (rontolisp::%starlark-all-cased s :upper "isupper"))

;; Whether S has a cased letter, an uppercase or titlecase one follows only an uncased
;; character and a lowercase one only a cased character.
(defun rontolisp::%starlark-m-istitle (s)
  (rontolisp::%starlark-string-receiver s "istitle")
  (let ((cased nil) (previous nil) (ok t))
    (dotimes (i (length s))
      (let ((k (rontolisp::%starlark-char-case (char s i))))
        (cond ((null k) (setq previous nil))
         ((eq k :lower)
          (if (not previous) (setq ok nil))
          (setq previous t)
          (setq cased t))
         (t
          (if previous (setq ok nil))
          (setq previous t)
          (setq cased t)))))
    (and cased ok)))

;; S with each letter that follows a cased character lowercased, every other one
;; uppercased.
(defun rontolisp::%starlark-m-title (s)
  (rontolisp::%starlark-string-receiver s "title")
  (let ((out (copy-seq s)) (previous nil))
    (dotimes (i (length s))
      (let ((c
             (if previous (char-downcase (char s i)) (char-upcase (char s i)))))
        (setf (char out i) c)
        (setq previous (rontolisp::%starlark-char-case c))))
    out))

(defun rontolisp::%starlark-m-removeprefix (s prefix)
  (rontolisp::%starlark-string-receiver s "removeprefix")
  (rontolisp::%starlark-string-argument prefix "removeprefix")
  (if (and (<= (length prefix) (length s))
           (string= s prefix :end1 (length prefix)))
      (subseq s (length prefix))
      s))

(defun rontolisp::%starlark-m-removesuffix (s suffix)
  (rontolisp::%starlark-string-receiver s "removesuffix")
  (rontolisp::%starlark-string-argument suffix "removesuffix")
  (if (and (<= (length suffix) (length s))
           (string= s suffix :start1 (- (length s) (length suffix))))
      (subseq s 0 (- (length s) (length suffix)))
      s))

(defun rontolisp::%starlark-m-capitalize (s)
  (rontolisp::%starlark-string-receiver s "capitalize")
  (if (= (length s) 0)
      s
      (concatenate 'string (string-upcase (subseq s 0 1))
                   (string-downcase (subseq s 1)))))

;; Whether S is non-empty and every character of it is of a class: ASCII letters
;; (CLASS 0), digits (1), letters and digits (2) or white space (3). The Common Lisp
;; classifiers answer differently per backend past ASCII.
(defun rontolisp::%starlark-every-char (s class name)
  (rontolisp::%starlark-string-receiver s name)
  (let ((ok (> (length s) 0)))
    (dotimes (i (length s))
      (let* ((code (char-code (char s i)))
             (letter
              (or (and (>= code 65) (<= code 90))
                  (and (>= code 97) (<= code 122))))
             (digit (and (>= code 48) (<= code 57))))
        (if (not
             (cond ((= class 0) letter)
                   ((= class 1) digit)
                   ((= class 2) (or letter digit))
                   (t (or (= code 32) (= code 9) (= code 10) (= code 13)))))
            (setq ok nil))))
    ok))

(defun rontolisp::%starlark-m-isalpha (s)
  (rontolisp::%starlark-every-char s 0 "isalpha"))

(defun rontolisp::%starlark-m-isdigit (s)
  (rontolisp::%starlark-every-char s 1 "isdigit"))

(defun rontolisp::%starlark-m-isalnum (s)
  (rontolisp::%starlark-every-char s 2 "isalnum"))

(defun rontolisp::%starlark-m-isspace (s)
  (rontolisp::%starlark-every-char s 3 "isspace"))

;; NAME is a replacement field's name: its digits as an integer, or nil when it is
;; something else.
(defun rontolisp::%starlark-decimal (name)
  (if (and (> (length name) 0)
           (every (lambda (c) (<= 48 (char-code c) 57)) name))
      (rontolisp::%starlark-parse-int name 10)
      nil))

;; "{} {0} {name!r}".format(...), as Go's Starlark reads the template: ARGS is the
;; tuple of positional arguments, KWARGS the dict of named ones. A field is numbered
;; automatically or by hand, never both; it has no attribute, element or format spec.
(defun rontolisp::%starlark-m-format (fmt args kwargs)
  (rontolisp::%starlark-string-receiver fmt "format")
  (let ((pos (coerce (rontolisp::%starlark-elements args) 'vector))
        (n (length fmt))
        (i 0)
        (index 0)
        (auto 0))
    (with-output-to-string (out)
      (loop while (< i n)
            do
              (let ((c (char fmt i)))
                (cond ((char= c #\})
                       (if (and (< (+ i 1) n) (char= (char fmt (+ i 1)) #\}))
                           (progn
                             (write-char #\} out)
                             (setq i (+ i 2)))
                           (error "format: single '}' in format")))
                      ((char/= c #\{)
                       (write-char c out)
                       (setq i (+ i 1)))
                      ((and (< (+ i 1) n) (char= (char fmt (+ i 1)) #\{))
                       (write-char #\{ out)
                       (setq i (+ i 2)))
                      (t (let ((close (position #\} fmt :start (+ i 1))))
                           (if (null close)
                               (error "format: unmatched '{' in format"))
                           (let* ((field (subseq fmt (+ i 1) close))
                                  (bang (position #\! field))
                                  (name
                                   (subseq field 0
                                           (or bang (position #\: field)
                                               (length field))))
                                  (rest (if bang (subseq field (+ bang 1)) ""))
                                  (colon
                                   (if bang
                                       (position #\: rest)
                                       (position #\: field)))
                                  (conv
                                   (if bang
                                       (subseq rest 0 (or colon (length rest)))
                                       "s"))
                                  (spec
                                   (cond (colon (subseq (if bang rest field)
                                                        (+ colon 1)))
                                         (t "")))
                                  (number (rontolisp::%starlark-decimal name))
                                  (arg nil))
                             (cond ((= (length name) 0)
                                    (if (< auto 0)
                                        (error
                                         "format: cannot switch from manual field specification to automatic field numbering"))
                                    (setq auto 1)
                                    (if (>= index (length pos))
                                        (error
                                         "format: tuple index out of range"))
                                    (setq arg (aref pos index))
                                    (setq index (+ index 1)))
                                   (number
                                    (if (> auto 0)
                                        (error
                                         "format: cannot switch from automatic field numbering to manual field specification"))
                                    (setq auto -1)
                                    (if (>= number (length pos))
                                        (error
                                         "format: tuple index out of range"))
                                    (setq arg (aref pos number)))
                                   (t (let ((cell
                                             (rontolisp::%starlark-dict-cell
                                              kwargs name)))
                                        (cond (cell (setq arg (cdr cell)))
                                              ((find #\. name)
                                               (error "format: attribute syntax x.y is not supported in replacement fields: ~a"
                                                      name))
                                              ((find #\[ name)
                                               (error "format: element syntax a[i] is not supported in replacement fields: ~a"
                                                      name))
                                              ((find #\{ name)
                                               (error
                                                "format: nested replacement fields not supported"))
                                              (t (error
                                                  "format: keyword ~a not found"
                                                  name))))))
                             (if (> (length spec) 0)
                                 (error "format spec features not supported in replacement fields: ~a"
                                        spec))
                             (cond ((string= conv "s")
                                    (write-string
                                     (rontolisp::%starlark-b-str arg) out))
                                   ((string= conv "r")
                                    (write-string
                                     (rontolisp::%starlark-b-repr arg) out))
                                   (t (error "format: unknown conversion ~a"
                                       (rontolisp::%starlark-b-repr conv))))
                             (setq i (+ close 1)))))))))))

;; "%s %d %r" % args: FMT is a string, ARGS a Common Lisp list.
(defun rontolisp::%starlark-interpolate (fmt args)
  (with-output-to-string (out)
    (let ((i 0) (n (length fmt)))
      (loop while (< i n)
            do
              (let ((c (char fmt i)))
                (if (char/= c #\%)
                    (progn
                      (write-char c out)
                      (setq i (+ i 1)))
                    (progn
                      (if (>= (+ i 1) n) (error "incomplete format"))
                      (let ((d (char fmt (+ i 1))))
                        (if (char= d #\%)
                            (write-char #\% out)
                            (progn
                              (if (null args)
                                  (error
                                   "not enough arguments for format string"))
                              (let ((a (car args)))
                                (setq args (cdr args))
                                (cond ((char= d #\s)
                                       (write-string
                                        (rontolisp::%starlark-b-str a) out))
                                      ((char= d #\r)
                                       (write-string
                                        (rontolisp::%starlark-b-repr a) out))
                                      ((find d "eEfFgG")
                                       (if (not (numberp a))
                                           (error
                                            "%~a format requires float, not ~a"
                                            d (rontolisp::%starlark-type a)))
                                       (write-string
                                        (rontolisp::%starlark-format-float
                                         (rontolisp::%starlark-to-float a) d)
                                        out))
                                      ((not
                                        (or (char= d #\d) (char= d #\i)
                                            (char= d #\x) (char= d #\X)
                                            (char= d #\o)))
                                       (error "unknown conversion %~a" d))
                                      ((not (numberp a))
                                       (error
                                        "%~a format requires integer, not ~a" d
                                        (rontolisp::%starlark-type a)))
                                      (t
                                       (let ((v
                                              (if (integerp a)
                                                  a
                                                  (rontolisp::%starlark-b-int a
                                                   :%unset))))
                                         (cond ((or (char= d #\d) (char= d #\i))
                                                (format out "~d" v))
                                               ((char= d #\x)
                                                (format out "~(~x~)" v))
                                               ((char= d #\X)
                                                (format out "~:@(~x~)" v))
                                               (t (format out "~o" v))))))))))
                      (setq i (+ i 2))))))
      (if args (error "too many arguments for format string")))))
