;; The run-time half of the EXPERIMENTAL Starlark front end (am.ik.rontolisp.starlark),
;; written in Common Lisp so one definition runs on every backend and no emitter learns
;; a Starlark name: the interpreter loads it on the first resolution of a
;; rontolisp::%starlark- function, the compile path splices it when the program
;; references one (StarlarkLibrary.java) and the tree-shaker drops what stays
;; unreachable.
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

;; ITEMS is an adjustable vector with a fill pointer. ITERATING counts the loops
;; running over the list: a list is not mutated while one runs.
(defstruct (rontolisp::%starlark-list (:%intern-package "RONTOLISP")
            (:constructor rontolisp::%starlark-new-list (items)) (:copier nil))
  items
  (frozen nil)
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
  (frozen nil)
  (iterating 0))

(defstruct (rontolisp::%starlark-range (:%intern-package "RONTOLISP")
            (:constructor rontolisp::%starlark-new-range (start stop step))
            (:copier nil))
  start
  stop
  step)

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
        ((rontolisp::%starlark-function-p x)
         (if (rontolisp::%starlark-function-kind x)
             "builtin_function_or_method"
             "function"))
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
      (rontolisp::%starlark-dict-p x) (rontolisp::%starlark-range-p x)))

;; The elements of an iterable as a fresh Common Lisp list.
(defun rontolisp::%starlark-elements (x)
  (cond ((rontolisp::%starlark-list-p x)
         (coerce (rontolisp::%starlark-list-items x) 'list))
        ((rontolisp::%starlark-tuple-p x)
         (coerce (rontolisp::%starlark-tuple-items x) 'list))
        ((rontolisp::%starlark-dict-p x) (rontolisp::%starlark-dict-key-list x))
        ((rontolisp::%starlark-range-p x)
         (coerce (rontolisp::%starlark-range-vector x) 'list))
        (t (error "type '~a' is not iterable" (rontolisp::%starlark-type x)))))

;; A fresh simple vector of the elements of vector V.
(defun rontolisp::%starlark-copy-vector (v)
  (let* ((n (length v)) (out (make-array n)))
    (dotimes (i n) (setf (aref out i) (aref v i)))
    out))

;; Starts a loop over X: the vector the loop indexes. A list and a dict count the loop
;; until %starlark-iterated, and refuse a mutation meanwhile; the list's own vector is
;; what the loop reads, since nothing can write it before then.
(defun rontolisp::%starlark-iterate (x)
  (cond ((rontolisp::%starlark-list-p x)
         (setf (rontolisp::%starlark-list-iterating x)
               (+ (rontolisp::%starlark-list-iterating x) 1))
         (rontolisp::%starlark-list-items x))
        ((rontolisp::%starlark-tuple-p x) (rontolisp::%starlark-tuple-items x))
        ((rontolisp::%starlark-dict-p x)
         (setf (rontolisp::%starlark-dict-iterating x)
               (+ (rontolisp::%starlark-dict-iterating x) 1))
         (coerce (rontolisp::%starlark-dict-key-list x) 'vector))
        ((rontolisp::%starlark-range-p x) (rontolisp::%starlark-range-vector x))
        (t (error "type '~a' is not iterable" (rontolisp::%starlark-type x)))))

;; Ends the loop %starlark-iterate started over X.
(defun rontolisp::%starlark-iterated (x)
  (cond ((rontolisp::%starlark-list-p x)
         (setf (rontolisp::%starlark-list-iterating x)
               (- (rontolisp::%starlark-list-iterating x) 1)))
        ((rontolisp::%starlark-dict-p x)
         (setf (rontolisp::%starlark-dict-iterating x)
               (- (rontolisp::%starlark-dict-iterating x) 1))))
  nil)

;; The N elements of X for a compound target, as a vector nothing writes.
(defun rontolisp::%starlark-unpack (x n)
  (let ((v
         (cond ((rontolisp::%starlark-tuple-p x)
                (rontolisp::%starlark-tuple-items x))
               ((rontolisp::%starlark-list-p x)
                (rontolisp::%starlark-copy-vector
                 (rontolisp::%starlark-list-items x)))
               (t (coerce (rontolisp::%starlark-elements x) 'vector)))))
    (cond ((< (length v) n)
           (error "too few values to unpack (got ~a, want ~a)" (length v) n))
          ((> (length v) n)
           (error "too many values to unpack (got ~a, want ~a)" (length v) n)))
    v))

;; L is a list. VERB names the mutation, as in "cannot append to frozen list".
(defun rontolisp::%starlark-list-writable (l verb)
  (cond
   ((rontolisp::%starlark-list-frozen l) (error "cannot ~a frozen list" verb))
   ((> (rontolisp::%starlark-list-iterating l) 0)
    (error "cannot ~a list during iteration" verb))))

;; D is a dict.
(defun rontolisp::%starlark-dict-writable (d verb)
  (cond
   ((rontolisp::%starlark-dict-frozen d) (error "cannot ~a frozen dict" verb))
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

(defun rontolisp::%starlark-equal (a b)
  (cond ((numberp a) (and (numberp b) (= a b)))
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
  (cond ((and (numberp a) (numberp b)) (cond ((< a b) -1) ((> a b) 1) (t 0)))
        ((and (stringp a) (stringp b))
         (cond ((string< a b) -1) ((string> a b) 1) (t 0)))
        ((and (or (eq a t) (null a)) (or (eq b t) (null b)))
         (cond ((eq a b) 0) ((null a) -1) (t 1)))
        ((and (rontolisp::%starlark-list-p a) (rontolisp::%starlark-list-p b))
         (rontolisp::%starlark-vector-compare
          (rontolisp::%starlark-list-items a)
          (rontolisp::%starlark-list-items b)))
        ((and (rontolisp::%starlark-tuple-p a) (rontolisp::%starlark-tuple-p b))
         (rontolisp::%starlark-vector-compare
          (rontolisp::%starlark-tuple-items a)
          (rontolisp::%starlark-tuple-items b)))
        (t
         (error "unsupported comparison: ~a <=> ~a"
                (rontolisp::%starlark-type a) (rontolisp::%starlark-type b)))))

(defun rontolisp::%starlark-less (a b) (< (rontolisp::%starlark-compare a b) 0))

(defun rontolisp::%starlark-less-equal (a b)
  (<= (rontolisp::%starlark-compare a b) 0))

(defun rontolisp::%starlark-greater (a b)
  (> (rontolisp::%starlark-compare a b) 0))

(defun rontolisp::%starlark-greater-equal (a b)
  (>= (rontolisp::%starlark-compare a b) 0))

;; --- dict -------------------------------------------------------------------------

;; The normal form a key is stored under, so that equal keys meet in an `equal` table:
;; 1 and 1.0 are one key, True and 1 are two, a tuple is the list of its elements'
;; normal forms.
(defun rontolisp::%starlark-key (k)
  (cond ((stringp k) k)
        ((integerp k) k)
        ((floatp k)
         (if (and (<= k 1.7976931348623157d308) (>= k -1.7976931348623157d308)
                  (= k (float (round k) 1d0)))
             (values (round k))
             k))
        ((eq k t) :true)
        ((null k) :false)
        ((eq k :none) :none)
        ((rontolisp::%starlark-tuple-p k)
         (let ((items (rontolisp::%starlark-tuple-items k)) (acc nil))
           (dotimes (i (length items))
             (push (rontolisp::%starlark-key (aref items i)) acc))
           (cons :tuple (nreverse acc))))
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

;; X is a string.
(defun rontolisp::%starlark-write-string (x s)
  (write-char #\" s)
  (dotimes (i (length x))
    (let ((c (char x i)))
      (cond ((char= c #\") (write-string "\\\"" s))
            ((char= c #\\) (write-string "\\\\" s))
            ((char= c #\Newline) (write-string "\\n" s))
            ((char= c #\Tab) (write-string "\\t" s))
            ((char= c #\Return) (write-string "\\r" s))
            (t (write-char c s)))))
  (write-char #\" s))

;; X is a float.
(defun rontolisp::%starlark-float-string (x)
  (if (and (< x 1d17) (> x -1d17) (= x (float (round x) 1d0)))
      (format nil "~a.0" (round x))
      (let* ((text (format nil "~a" x)) (d (position #\d text)))
        (if d (subseq text 0 d) text))))

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
        ((rontolisp::%starlark-function-p x)
         (let ((kind (rontolisp::%starlark-function-kind x))
               (name (rontolisp::%starlark-function-name x)))
           (cond ((null kind) (format s "<function ~a>" name))
                 ((eq kind :builtin) (format s "<built-in function ~a>" name))
                 (t (format s "<built-in method ~a of ~a value>" name
                            (rontolisp::%starlark-type
                             (rontolisp::%starlark-function-self x)))))))
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
        ((and (numberp a) (numberp b)) (+ (float a 1d0) (float b 1d0)))
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
        ((and (numberp a) (numberp b)) (- (float a 1d0) (float b 1d0)))
        (t (rontolisp::%starlark-binary-error "-" a b))))

(defun rontolisp::%starlark-mul (a b)
  (cond ((and (integerp a) (integerp b)) (* a b))
   ((and (numberp a) (numberp b)) (* (float a 1d0) (float b 1d0)))
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
          (/ (float a 1d0) (float b 1d0)))
      (rontolisp::%starlark-binary-error "/" a b)))

(defun rontolisp::%starlark-floordiv (a b)
  (cond ((and (integerp a) (integerp b))
         (if (= b 0) (error "floored division by zero") (values (floor a b))))
        ((and (numberp a) (numberp b))
         (if (= b 0)
             (error "floored division by zero")
             (float (floor (float a 1d0) (float b 1d0)) 1d0)))
        (t (rontolisp::%starlark-binary-error "//" a b))))

(defun rontolisp::%starlark-mod (a b)
  (cond ((and (integerp a) (integerp b))
         (if (= b 0) (error "integer modulo by zero") (mod a b)))
        ((and (numberp a) (numberp b))
         (if (= b 0)
             (error "floating-point modulo by zero")
             (mod (float a 1d0) (float b 1d0))))
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
    (let ((defaults (rontolisp::%starlark-function-defaults f)))
      (dotimes (j n)
        (if (eq (aref vals j) :%unset)
            (if (or (null defaults) (eq (car defaults) :%required))
                (error "~a missing argument for ~a"
                       (rontolisp::%starlark-function-title f) (nth j params))
                (setf (aref vals j) (car defaults))))
        (setq defaults (cdr defaults))))
    (append (coerce vals 'list)
            (if (rontolisp::%starlark-function-varargs f)
                (list (rontolisp::%starlark-tuple-of (nreverse extra)))
                nil) (if kwargs (list kwargs) nil))))

(defun rontolisp::%starlark-call (f pos kw)
  (if (not (rontolisp::%starlark-function-p f))
      (error "invalid call of non-function (~a)" (rontolisp::%starlark-type f)))
  (let ((args (rontolisp::%starlark-bind f pos kw)))
    (if (eq (rontolisp::%starlark-function-kind f) :method)
        (apply (rontolisp::%starlark-function-body f)
               (rontolisp::%starlark-function-self f) args)
        (apply (rontolisp::%starlark-function-body f) args))))

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
          ((not (integerp base)) (error "int: base must be int"))
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

(defun rontolisp::%starlark-b-float (x)
  (cond ((numberp x) (float x 1d0))
        ((eq x t) 1d0)
        ((null x) 0d0)
        (t (error "float: cannot convert ~a to float"
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
  (let ((lists
         (mapcar #'rontolisp::%starlark-elements
                 (rontolisp::%starlark-elements args)))
        (acc nil))
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
              (rontolisp::%starlark-elements (car given))
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

(defun rontolisp::%starlark-m-elems (s)
  (rontolisp::%starlark-string-receiver s "elems")
  (let ((acc nil))
    (dotimes (i (length s)) (push (subseq s i (+ i 1)) acc))
    (rontolisp::%starlark-list-of (nreverse acc))))

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

;; "{} {0} {name}".format(...): ARGS is the tuple of positional arguments, KWARGS the
;; dict of named ones.
(defun rontolisp::%starlark-m-format (fmt args kwargs)
  (rontolisp::%starlark-string-receiver fmt "format")
  (let ((pos (rontolisp::%starlark-elements args)))
    (with-output-to-string (out)
      (let ((i 0) (n (length fmt)) (auto 0))
        (loop while (< i n)
              do
                (let ((c (char fmt i)))
                  (cond ((and (char= c #\{) (< (+ i 1) n)
                              (char= (char fmt (+ i 1)) #\{))
                         (write-char #\{ out)
                         (setq i (+ i 2)))
                        ((and (char= c #\}) (< (+ i 1) n)
                              (char= (char fmt (+ i 1)) #\}))
                         (write-char #\} out)
                         (setq i (+ i 2)))
                        ((char= c #\}) (error "format: single '}' in format"))
                        ((char= c #\{)
                         (let ((close (position #\} fmt :start i)))
                           (if (null close)
                               (error "format: unmatched '{' in format"))
                           (let ((name (subseq fmt (+ i 1) close)))
                             (cond ((= (length name) 0)
                                    (if (>= auto (length pos))
                                        (error
                                         "format: tuple index out of range"))
                                    (write-string
                                     (rontolisp::%starlark-b-str (nth auto pos))
                                     out)
                                    (setq auto (+ auto 1)))
                                   ((<
                                     (rontolisp::%starlark-digit (char name 0))
                                     10)
                                    (let ((idx
                                           (rontolisp::%starlark-parse-int name
                                                                           10)))
                                      (if (>= idx (length pos))
                                          (error
                                           "format: tuple index out of range"))
                                      (write-string (rontolisp::%starlark-b-str
                                                     (nth idx pos)) out)))
                                   (t
                                    (let ((cell
                                           (rontolisp::%starlark-dict-cell
                                            kwargs name)))
                                      (if (null cell)
                                          (error "format: keyword ~a not found"
                                                 name))
                                      (write-string
                                       (rontolisp::%starlark-b-str (cdr cell))
                                       out)))))
                           (setq i (+ close 1))))
                        (t
                         (write-char c out)
                         (setq i (+ i 1))))))))))

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
                                      ((not
                                        (or (char= d #\d) (char= d #\x)
                                            (char= d #\X) (char= d #\o)))
                                       (error "unsupported format character ~a"
                                              d))
                                      ((not (numberp a))
                                       (error
                                        "%~a format requires integer, not ~a" d
                                        (rontolisp::%starlark-type a)))
                                      (t (let ((v
                                                (if (integerp a)
                                                    a
                                                    (rontolisp::%starlark-b-int
                                                     a :%unset))))
                                           (cond
                                            ((char= d #\d) (format out "~d" v))
                                            ((char= d #\x)
                                             (format out "~(~x~)" v))
                                            ((char= d #\X)
                                             (format out "~:@(~x~)" v))
                                            (t (format out "~o" v))))))))))
                      (setq i (+ i 2))))))
      (if args (error "too many arguments for format string")))))
