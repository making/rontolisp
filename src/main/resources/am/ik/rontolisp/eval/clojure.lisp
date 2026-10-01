;; The run-time half of the EXPERIMENTAL Clojure front end: the printer behind
;; println/print/pr/prn, the string builders behind str/pr-str, and the REPL echo.
;; Spliced like scheme.lisp by eval/ClojureLibrary, so no backend learns a Clojure
;; name (.kb/clojure-frontend.md).
;;
;; A Clojure program is lowered to the Common Lisp core forms the pipeline already
;; consumes, so its values are Common Lisp values: vectors are vectors, maps and
;; sets are equal hash tables (a set wrapped as (:C%SET table)), keywords are
;; (:C%KEYWORD spelling) lists, true is T, false is the value of
;; rontolisp::%clojure-false, nil is NIL, and symbols mangle behind c%. This file
;; renders those shapes back in Clojure notation: [1 :a s], {:a 1}, #{1},
;; (true false nil :k), e2e-foo, \a/a, readable strings.
;;
;; Two entry points, both over one stream-direct writer in the %scheme-print shape:
;; - rontolisp::%clojure-write-datum writes one part to *standard-output*
;;   (println/print/pr/prn, answering nil like the oracle);
;; - rontolisp::%clojure-str-of answers one part's string (str/pr-str, the REPL
;;   echo), written to a string stream and read back.
;; Neither is with-output-to-string: a literal one flips a WASM module into EH
;; mode (measured gate, see .todo/artefacts/b07-clojure-print/NOTES.md finding 6),
;; while make-string-output-stream/get-output-stream-string compile without it
;; (measured 2026-09-30: 6,429 B vs 11,948 B of wasm for the same writes).
;;
;; Cycles print with Scheme-scale datum labels (#0=(...) . #0#), copied from
;; %scheme-print's design and extended to hash-table nodes: a map can close a
;; cycle through an atom ((def a (atom nil)) (def m {:self a}) (reset! a m)).
;; Sharing without a cycle is written out each time, as write does. The label
;; walk needs no eq hash table (the %scheme-print comment applies here too).
;;
;; Deliberate non-goals, each a documented deviation (.kb/clojure-frontend.md):
;; nil IS the empty list (stays nil, never ()); map/set walk order is unspecified
;; (same as keys/vals); *print-length*/*print-level* are not honored (a routed
;; println never passed through %print-cased either); ~S/~A on Clojure values
;; stay Common Lisp notation (format is a CL surface); print-method/pprint stay
;; absent; unreadable values (functions, conditions, host objects) print #<..>.

(defun rontolisp::%clojure-keyword-p (x)
  "Whether X is the (:C%KEYWORD spelling) wrapper the lowering lowers keywords to."
  (and (consp x) (eq (car x) :C%KEYWORD) (consp (cdr x)) (stringp (car (cdr x)))
       (null (cdr (cdr x)))))

(defun rontolisp::%clojure-set-p (x)
  "Whether X is the (:C%SET table) wrapper the lowering lowers sets to."
  (and (consp x) (eq (car x) :C%SET) (consp (cdr x))
       (hash-table-p (car (cdr x))) (null (cdr (cdr x)))))

(defun rontolisp::%clojure-record-p (x)
  "Whether X is the (:C%RECORD tag fields table) wrapper the lowering lowers
   records to: map-like, so the map verbs read through its entry table."
  (and (consp x) (eq (car x) :C%RECORD) (consp (cdr (cdr x)))
       (hash-table-p (car (cdr (cdr (cdr x)))))))

(defun rontolisp::%clojure-typed-opaque-p (x)
  "Whether X is a deftype or reify value: typed, but opaque to the map verbs,
   like the oracle."
  (or (and (consp x) (eq (car x) :C%TYPE) (consp (cdr (cdr x)))
           (hash-table-p (car (cdr (cdr (cdr x))))))
      (and (consp x) (eq (car x) :C%REIFY))))

(defun rontolisp::%clojure-atom-p (x)
  "Whether X is the (:C%ATOM #(value)) cell the lowering lowers atoms to."
  (and (consp x) (eq (car x) :C%ATOM) (consp (cdr x))
       (and (vectorp (car (cdr x))) (not (stringp (car (cdr x)))))
       (null (cdr (cdr x)))))

(defun rontolisp::%clojure-write-demangled (name stream)
  "Undo ClojureLowering.mangle over the symbol NAME straight to STREAM: strip the
   c% prefix, %% -> %, %c -> :. Case-sensitive on purpose: CL symbols upcase, so
   C%.. is never ours, and a lone % (unspellable by mangle) passes through."
  (if (or (< (length name) 2) (not (char= (char name 0) #\c))
          (not (char= (char name 1) #\%)))
      (write-string name stream)
      (do ((i 2 (+ i 1)))
          ((>= i (length name)))
        (let ((c (char name i)))
          (if (and (char= c #\%) (< (+ i 1) (length name))
                   (or (char= (char name (+ i 1)) #\%)
                       (char= (char name (+ i 1)) #\c)))
              (progn
                (write-char (if (char= (char name (+ i 1)) #\c) #\: #\%) stream)
                (setq i (+ i 1)))
              (write-char c stream))))))

(defun rontolisp::%clojure-write-readable-string (s stream)
  "S in double quotes with \" \\ \\n \\t \\r \\b \\f and \\u00XX, like the oracle."
  (write-char #\" stream)
  (do ((i 0 (+ i 1)))
      ((>= i (length s)))
    (let ((code (char-code (char s i))))
      (cond ((= code 34) (write-string "\\\"" stream))
            ((= code 92) (write-string "\\\\" stream))
            ((= code 10) (write-string "\\n" stream))
            ((= code 9) (write-string "\\t" stream))
            ((= code 13) (write-string "\\r" stream))
            ((= code 8) (write-string "\\b" stream))
            ((= code 12) (write-string "\\f" stream))
            ((< code 32)
             (write-string "\\u00" stream)
             (write-char (char "0123456789abcdef" (ash code -4)) stream)
             (write-char (char "0123456789abcdef" (logand code 15)) stream))
            (t (write-char (char s i) stream)))))
  (write-char #\" stream))

(defun rontolisp::%clojure-write-readable-char (c stream)
  "C with a backslash: the six names, else the raw glyph like the oracle."
  (write-char #\\ stream)
  (let ((code (char-code c)))
    (cond ((= code 32) (write-string "space" stream))
          ((= code 10) (write-string "newline" stream))
          ((= code 9) (write-string "tab" stream))
          ((= code 13) (write-string "return" stream))
          ((= code 8) (write-string "backspace" stream))
          ((= code 12) (write-string "formfeed" stream))
          (t (write-char c stream)))))

(defun rontolisp::%clojure-node-p (x)
  "Whether X can close a cycle: a pair, a non-string vector, or a table.
   A lazy wrapper is a leaf: its cell is machinery, never user data, and
   %clojure-write refuses it without forcing (b11)."
  (and (not (rontolisp::%clojure-lazy-p x))
       (or (consp x) (and (vectorp x) (not (stringp x))) (hash-table-p x))))

(defun rontolisp::%clojure-may-cycle-p (x depth)
  "NIL when printing X as a tree ends, T as soon as it may not: a cdr chain that
   meets itself (Brent), or car/element/table nesting past DEPTH -- where every
   cycle through one ends up, since that walk never returns."
  (if (< depth 0)
      t
      (let ((tortoise x) (power 1) (steps 0) (cycle nil))
        (do ()
            ((or cycle (not (rontolisp::%clojure-node-p x))) cycle)
          (cond ((consp x)
                 (if (and (rontolisp::%clojure-node-p (car x))
                          (rontolisp::%clojure-may-cycle-p (car x) (- depth 1)))
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
                                 (setq steps 0)))))))
                ((hash-table-p x)
                 (let ((table x))
                   (setq x nil)
                   (maphash (lambda (k v)
                              (if (or (and (rontolisp::%clojure-node-p k)
                                           (rontolisp::%clojure-may-cycle-p k
                                            (- depth 1)))
                                      (and (rontolisp::%clojure-node-p v)
                                           (rontolisp::%clojure-may-cycle-p v
                                            (- depth 1))))
                                  (setq cycle t))) table)))
                (t (let ((v x))
                     (setq x nil)
                     (do ((i 0 (+ i 1)))
                         ((or cycle (>= i (length v))))
                       (if (and (rontolisp::%clojure-node-p (aref v i))
                                (rontolisp::%clojure-may-cycle-p (aref v i)
                                                                 (- depth 1)))
                           (setq cycle t))))))))))

(defun rontolisp::%clojure-entry (x entries)
  "The (node . state) of X in ENTRIES, or NIL."
  (do ((l entries (cdr l))) ((or (null l) (eq (car (car l)) x)) (car l))))

(defun rontolisp::%clojure-mark-cycles (x seen)
  "A depth-first walk recording each node in (car SEEN) with a state: 1 while its
   walk is open, 2 once closed; 3 the same for a node reached again while open --
   one a cycle closes on. The cdr direction is a loop."
  (let ((spine nil))
    (do ()
        ((not (rontolisp::%clojure-node-p x)))
      (let ((entry (rontolisp::%clojure-entry x (car seen))))
        (cond ((null entry)
               (setq entry (cons x 1))
               (rplaca seen (cons entry (car seen)))
               (setq spine (cons entry spine))
               (cond ((consp x)
                      (rontolisp::%clojure-mark-cycles (car x) seen)
                      (setq x (cdr x)))
                     ((hash-table-p x)
                      (let ((table x))
                        (setq x nil)
                        (maphash (lambda (k v)
                                   (rontolisp::%clojure-mark-cycles k seen)
                                   (rontolisp::%clojure-mark-cycles v seen))
                                 table)))
                     (t
                      (let ((v x))
                        (setq x nil)
                        (do ((i 0 (+ i 1)))
                            ((>= i (length v)))
                          (rontolisp::%clojure-mark-cycles (aref v i) seen))))))
              (t
               (if (= (cdr entry) 1) (rplacd entry 3))
               (setq x nil)))))
    (do ((l spine (cdr l)))
        ((null l))
      (rplacd (car l) (if (= (cdr (car l)) 3) 4 2)))))

(defun rontolisp::%clojure-cycle-labels (x)
  "The labels X needs, as (entries . next-number): entries (node . 4) for each
   node a cycle closes on, or NIL when there is none."
  (let ((seen (list nil)) (labeled nil))
    (rontolisp::%clojure-mark-cycles x seen)
    (do ((l (car seen) (cdr l)))
        ((null l))
      (if (= (cdr (car l)) 4) (setq labeled (cons (car l) labeled))))
    (if labeled (cons labeled 0))))

(defun rontolisp::%clojure-labeled-p (x labels)
  "Whether X carries a label: one to define (4) or one already written (negative)."
  (rontolisp::%clojure-entry x (car labels)))

(defun rontolisp::%clojure-write-label (x labels stream)
  "Write X's label: #n= before its first appearance (answering NIL, the datum
   follows), #n# after it (answering T, nothing more to write)."
  (let ((entry (rontolisp::%clojure-entry x (car labels))))
    (cond ((null entry) nil)
          ((= (cdr entry) 4)
           (let ((n (cdr labels)))
             (rplacd entry (- -1 n))
             (rplacd labels (+ n 1))
             (write-char #\# stream)
             (princ n stream)
             (write-char #\= stream)
             nil))
          (t
           (write-char #\# stream)
           (princ (- -1 (cdr entry)) stream)
           (write-char #\# stream)
           t))))

(defun rontolisp::%clojure-write (x nil-replacement readable stream labels)
  "Write X to STREAM in Clojure notation. READABLE selects the pr side (quoted
   strings, \\chars) vs the print side (bare); NIL-REPLACEMENT is what nil prints
   as (\"\" for str, \"nil\" for print/pr); LABELS the cycle labels, or NIL."
  (cond ((eq x t) (write-string "true" stream))
        ((eq x rontolisp::%clojure-false) (write-string "false" stream))
        ((null x) (write-string nil-replacement stream))
        ((rontolisp::%clojure-lazy-p x) (write-string "#<LazySeq>" stream))
        ((rontolisp::%clojure-keyword-p x)
         (write-char #\: stream)
         (write-string (car (cdr x)) stream))
        ((and labels (rontolisp::%clojure-node-p x)
              (rontolisp::%clojure-write-label x labels stream)))
        ((rontolisp::%clojure-set-p x)
         (write-string "#{" stream)
         (let ((first t))
           (maphash (lambda (k v)
                      (if first (setq first nil) (write-char #\Space stream))
                      (rontolisp::%clojure-write v nil-replacement readable
                                                 stream labels)) (car (cdr x))))
         (write-char #\} stream))
        ((rontolisp::%clojure-atom-p x)
         (write-string "#<Atom " stream)
         (rontolisp::%clojure-write (aref (car (cdr x)) 0) nil-replacement
                                    readable stream labels)
         (write-char #\> stream))
        ((stringp x)
         (if readable
             (rontolisp::%clojure-write-readable-string x stream)
             (write-string x stream)))
        ((characterp x)
         (if readable
             (rontolisp::%clojure-write-readable-char x stream)
             (write-char x stream)))
        ((symbolp x)
         (if (keywordp x)
             (let ((name (symbol-name x)))
               (write-char #\: stream)
               (if (and (> (length name) 0) (char= (char name 0) #\:))
                   (write-string (subseq name 1) stream)
                   (write-string name stream)))
             (rontolisp::%clojure-write-demangled (symbol-name x) stream)))
        ((hash-table-p x)
         (write-char #\{ stream)
         (let ((first t))
           (maphash (lambda (k v)
                      (if first (setq first nil) (write-string ", " stream))
                      (rontolisp::%clojure-write k nil-replacement readable
                                                 stream labels)
                      (write-char #\Space stream)
                      (rontolisp::%clojure-write v nil-replacement readable
                                                 stream labels)) x))
         (write-char #\} stream))
        ((and (vectorp x) (not (stringp x)))
         (write-char #\[ stream)
         (do ((i 0 (+ i 1)))
             ((>= i (length x)))
           (if (> i 0) (write-char #\Space stream))
           (rontolisp::%clojure-write (aref x i) nil-replacement readable stream
                                      labels))
         (write-char #\] stream))
        ((consp x)
         (write-char #\( stream)
         (rontolisp::%clojure-write (car x) nil-replacement readable stream
                                    labels)
         (do ((rest (cdr x) (cdr rest)))
             ((or (not (consp rest)) (rontolisp::%clojure-lazy-p rest)
                  (and labels (rontolisp::%clojure-labeled-p rest labels)))
              (cond
               ((rontolisp::%clojure-lazy-p rest) (write-string " ..." stream))
               ((not (null rest))
                (progn
                  (write-string " . " stream)
                  (rontolisp::%clojure-write rest nil-replacement readable
                                             stream labels)))))
           (write-char #\Space stream)
           (rontolisp::%clojure-write (car rest) nil-replacement readable stream
                                      labels))
         (write-char #\) stream))
        ((functionp x) (write-string "#<procedure>" stream))
        (t (princ x stream))))

(defun rontolisp::%clojure-print (x nil-replacement readable stream)
  "Write X in Clojure notation to STREAM, with datum labels when it may cycle."
  (rontolisp::%clojure-write x nil-replacement readable stream
                             (if (and (rontolisp::%clojure-node-p x)
                                      (rontolisp::%clojure-may-cycle-p x 1000))
                                 (rontolisp::%clojure-cycle-labels x)))
  nil)

(defun rontolisp::%clojure-str-of (x nil-replacement readable)
  "X's Clojure-notation string: the str/pr-str building block. False is \"false\",
   T is \"true\", NIL is NIL-REPLACEMENT (\"\" for str, \"nil\" for print/pr), a
   keyword its colon spelling, anything else the datum."
  (let ((stream (make-string-output-stream)))
    (rontolisp::%clojure-print x nil-replacement readable stream)
    (get-output-stream-string stream)))

(defun rontolisp::%clojure-write-datum (x nil-replacement readable)
  "Write X in Clojure notation to *standard-output*: the println/print/pr/prn
   building block. Answers NIL, so a print call's value is nil like the oracle."
  (rontolisp::%clojure-print x nil-replacement readable *standard-output*)
  nil)

(defun rontolisp::%clojure-call (f args)
  "Apply F to the argument list ARGS: real functions through apply, collection
   values through their lookup, like the oracle's IFn. Sets answer the member,
   maps the value, vectors the indexed element, keywords the table-aware read --
   each with the next argument as the default (nil without one). Strings are no
   functions, like the oracle, and anything else signals."
  (cond ((functionp f) (apply f args))
        ((rontolisp::%clojure-set-p f)
         (gethash (car args) (car (cdr f))
                  (if (cdr args) (car (cdr args)) nil)))
        ((hash-table-p f)
         (gethash (car args) f (if (cdr args) (car (cdr args)) nil)))
        ((rontolisp::%clojure-record-p f)
         (gethash (car args) (car (cdr (cdr (cdr f))))
                  (if (cdr args) (car (cdr args)) nil)))
        ((and (vectorp f) (not (stringp f)))
         (let ((i (car args)))
           (if (and (integerp i) (<= 0 i) (< i (length f)))
               (elt f i)
               (if (cdr args) (car (cdr args)) nil))))
        ((rontolisp::%clojure-keyword-p f)
         (rontolisp::%clojure-call-keyword f (car args)
          (if (cdr args) (car (cdr args)) nil)))
        (t (error "not a function"))))

(defun rontolisp::%clojure-call-keyword (k coll dflt)
  "The keyword K read through COLL: sets answer the member, maps the value,
   records their entry table, anything else the default (a keyword never indexes
   a vector or a string)."
  (cond ((rontolisp::%clojure-set-p coll) (gethash k (car (cdr coll)) dflt))
        ((rontolisp::%clojure-record-p coll)
         (gethash k (car (cdr (cdr (cdr coll)))) dflt))
        ((hash-table-p coll) (gethash k coll dflt))
        (t dflt)))

;;;; Lazy seqs (b11): memoized-thunk wrappers over the strict seq view.
;;
;; A lazy seq is (LIST :C%LAZY cell) where CELL is (CONS thunk-or-nil
;; realized-seq), beside the (:C%SET table) and (:C%KEYWORD spelling) wrappers.
;; The thunk runs at most once per wrapper object: %clojure-realize funcalls it,
;; seqs the answer and clears the car, so every later force answers the memoized
;; seq. The cell mutates through rplaca/rplacd, primitives every backend already
;; compiles, so the representation is identical on all four backends with no new
;; per-backend code. The invariant the lowering keeps: any seq containing a lazy
;; tail IS a wrapper (cons/concat/map/filter wrap instead of exposing a strict
;; cons with a lazy tail), so callers test only the top level.
;;
;; Deliberate non-goals, each a documented deviation (.kb/clojure-frontend.md):
;; no chunking (every element realizes singly), no parallel realization, infinite
;; range stays refused, and lazy inputs to the non-listed verbs (doseq/for/reduce
;; and friends) consume one level through %clojure-seq -- pass a taken prefix.

(defun rontolisp::%clojure-lazy-p (x)
  "Whether X is the (:C%LAZY cell) wrapper lazy-seq and friends build."
  (and (consp x) (eq (car x) :C%LAZY) (consp (cdr x)) (consp (car (cdr x)))
       (null (cdr (cdr x)))))

(defun rontolisp::%clojure-make-lazy (thunk)
  "A lazy seq over the zero-argument closure THUNK, unrealized."
  (list :C%LAZY (cons thunk nil)))

(defun rontolisp::%clojure-strict-seq (coll)
  "The strict list view of COLL: the b03 cond, now shared by every backend
   through this one defun instead of inline in the lowering."
  (cond ((null coll) nil)
   ((rontolisp::%clojure-set-p coll)
    (let ((acc nil))
      (maphash (lambda (k v)
                 (declare (ignore v))
                 (setq acc (cons k acc))) (car (cdr coll)))
      acc))
   ((rontolisp::%clojure-record-p coll)
    (let ((acc nil))
      (maphash (lambda (k v) (setq acc (cons (vector k v) acc)))
               (car (cdr (cdr (cdr coll)))))
      acc))
   ((rontolisp::%clojure-typed-opaque-p coll) (error "seq needs a collection"))
   ((consp coll) coll)
   ((vectorp coll) (coerce coll 'list))
   ((stringp coll) (coerce coll 'list))
   ((hash-table-p coll)
    (let ((acc nil))
      (maphash (lambda (k v) (setq acc (cons (vector k v) acc))) coll)
      acc))
   ((eq coll rontolisp::%clojure-false) nil)
   (t (error "seq needs a collection"))))

(defun rontolisp::%clojure-realize (x)
  "Force the lazy wrapper X to its seq (nil or a cons), memoized at-most-once.
   A thunk answering another wrapper chains through it; anything else seqs
   strictly (a cons passes through, so a lazy tail stays lazy)."
  (let ((cell (car (cdr x))))
    (if (car cell)
        (let ((v (funcall (car cell))))
          (let ((s
                 (if (rontolisp::%clojure-lazy-p v)
                     (rontolisp::%clojure-realize v)
                     (rontolisp::%clojure-strict-seq v))))
            (rplaca cell nil)
            (rplacd cell s)
            s))
        (cdr cell))))

(defun rontolisp::%clojure-seq (coll)
  "The lazy-aware seq view: one-level realize for wrappers, the strict view
   otherwise. Never walks past one wrapper, so infinite seqs stay infinite."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-realize coll)
      (rontolisp::%clojure-strict-seq coll)))

(defun rontolisp::%clojure-take (n coll)
  "The first N of COLL as a strict list, stepping through one wrapper at a
   time so (take n infinite) terminates. Realizes exactly what it answers:
   the next wrapper stays unforcd when the count runs out."
  (let ((left n) (s (rontolisp::%clojure-seq coll)) (acc nil))
    (do ()
        ((or (<= left 0) (null s)) (reverse acc))
      (setq acc (cons (car s) acc))
      (setq left (- left 1))
      (if (> left 0) (setq s (rontolisp::%clojure-seq (cdr s)))))))

(defun rontolisp::%clojure-drop (n coll)
  "COLL past its first N: the remainder, which may stay lazy."
  (let ((left n) (s (rontolisp::%clojure-seq coll)))
    (do ()
        ((or (<= left 0) (null s)) s)
      (setq s (rontolisp::%clojure-seq (cdr s)))
      (setq left (- left 1)))))

(defun rontolisp::%clojure-any-lazy-p (colls)
  "Whether any member of the COLLS list is a lazy wrapper."
  (if (null colls)
      nil
      (if (rontolisp::%clojure-lazy-p (car colls))
          t
          (rontolisp::%clojure-any-lazy-p (cdr colls)))))

(defun rontolisp::%clojure-cons (item coll)
  "Cons ITEM onto COLL, wrapping when the tail is lazy (the top-level test
   keeps the wrapper invariant: no strict cons ever holds a lazy tail)."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-make-lazy (lambda () (cons item coll)))
      (cons item (rontolisp::%clojure-seq coll))))

(defun rontolisp::%clojure-map (f colls)
  "Map F over the COLLS list (one or more): a wrapper when any input is lazy
   (stopping at the shortest, like the oracle), the strict mapcar otherwise."
  (if (rontolisp::%clojure-any-lazy-p colls)
      (rontolisp::%clojure-map-lazy f colls)
      (apply #'mapcar (lambda (&rest xs) (rontolisp::%clojure-call f xs))
             (mapcar #'rontolisp::%clojure-seq colls))))

(defun rontolisp::%clojure-map-lazy (f colls)
  "The lazy arm of %clojure-map over the COLLS list."
  (rontolisp::%clojure-make-lazy
   (lambda () (rontolisp::%clojure-map-step f colls))))

(defun rontolisp::%clojure-map-step (f colls)
  "One mapped head over the COLLS list, or nil past the shortest."
  (let ((seqs (mapcar #'rontolisp::%clojure-seq colls)))
    (if (rontolisp::%clojure-map-done-p seqs)
        nil
        (cons (rontolisp::%clojure-call f (rontolisp::%clojure-map-heads seqs))
              (rontolisp::%clojure-map-lazy f
               (rontolisp::%clojure-map-tails seqs))))))

(defun rontolisp::%clojure-map-done-p (seqs)
  "Whether any member of the SEQS list is empty."
  (if (null seqs)
      nil
      (if (null (car seqs)) t (rontolisp::%clojure-map-done-p (cdr seqs)))))

(defun rontolisp::%clojure-map-heads (seqs)
  "The cars of the SEQS list."
  (if (null seqs)
      nil
      (cons (car (car seqs)) (rontolisp::%clojure-map-heads (cdr seqs)))))

(defun rontolisp::%clojure-map-tails (seqs)
  "The cdrs of the SEQS list."
  (if (null seqs)
      nil
      (cons (cdr (car seqs)) (rontolisp::%clojure-map-tails (cdr seqs)))))

(defun rontolisp::%clojure-filter-test (pred x)
  "Whether X passes PRED under Clojure truthiness (nil and false drop)."
  (let ((v (rontolisp::%clojure-call pred (list x))))
    (not (or (null v) (eq v rontolisp::%clojure-false)))))

(defun rontolisp::%clojure-filter (pred coll)
  "Filter COLL through PRED: a wrapper when COLL is lazy, remove-if-not else."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-filter-lazy pred coll)
      (remove-if-not (lambda (x) (rontolisp::%clojure-filter-test pred x))
                     (rontolisp::%clojure-seq coll))))

(defun rontolisp::%clojure-filter-lazy (pred coll)
  "The lazy arm of %clojure-filter."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((s (rontolisp::%clojure-seq coll)))
       (cond ((null s) nil)
             ((rontolisp::%clojure-filter-test pred (car s))
              (cons (car s) (rontolisp::%clojure-filter-lazy pred (cdr s))))
             (t (rontolisp::%clojure-seq
                 (rontolisp::%clojure-filter-lazy pred (cdr s)))))))))

(defun rontolisp::%clojure-concat (colls)
  "Append the COLLS list: a wrapper when any member is lazy, strict append
   of the seq views otherwise (of none, nil)."
  (if (rontolisp::%clojure-any-lazy-p colls)
      (rontolisp::%clojure-concat-lazy colls)
      (apply #'append (mapcar #'rontolisp::%clojure-seq colls))))

(defun rontolisp::%clojure-concat-lazy (colls)
  "The lazy arm of %clojure-concat over the COLLS list."
  (rontolisp::%clojure-make-lazy
   (lambda () (rontolisp::%clojure-concat-step colls))))

(defun rontolisp::%clojure-concat-step (colls)
  "The first surviving head of the COLLS list over its lazy tail, or nil."
  (if (null colls)
      nil
      (let ((s (rontolisp::%clojure-seq (car colls))))
        (if (null s)
            (rontolisp::%clojure-concat-step (cdr colls))
            (cons (car s)
             (rontolisp::%clojure-concat-lazy (cons (cdr s) (cdr colls))))))))

(defun rontolisp::%clojure-repeat (x)
  "The infinite seq of X."
  (rontolisp::%clojure-make-lazy
   (lambda () (cons x (rontolisp::%clojure-repeat x)))))

(defun rontolisp::%clojure-repeat-n (n x)
  "N copies of X as a strict list (of a non-positive N, nil like the oracle)."
  (let ((acc nil) (left n))
    (do ()
        ((<= left 0) acc)
      (setq acc (cons x acc))
      (setq left (- left 1)))))

(defun rontolisp::%clojure-cycle (coll)
  "The seq view of COLL cycled forever (of empty, nil)."
  (let ((s (rontolisp::%clojure-seq coll)))
    (if (null s) nil (rontolisp::%clojure-cycle-from s s))))

(defun rontolisp::%clojure-cycle-from (full cur)
  "FULL cycled from CUR, one wrapper per element."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((c (if (null cur) full cur)))
       (if (null c)
           nil
           (cons (car c) (rontolisp::%clojure-cycle-from full (cdr c))))))))

(defun rontolisp::%clojure-iterate (f x)
  "X, (f X), (f (f X)) ... as a lazy seq, through the IFn dispatcher."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (cons x
      (rontolisp::%clojure-iterate f (rontolisp::%clojure-call f (list x)))))))

(defun rontolisp::%clojure-repeatedly (f)
  "The infinite seq of (f) calls, through the IFn dispatcher."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (cons (rontolisp::%clojure-call f nil)
           (rontolisp::%clojure-repeatedly f)))))

(defun rontolisp::%clojure-repeatedly-n (n f)
  "N (f) calls as a strict list (of a non-positive N, nil)."
  (let ((acc nil) (left n))
    (do ()
        ((<= left 0) (reverse acc))
      (setq acc (cons (rontolisp::%clojure-call f nil) acc))
      (setq left (- left 1)))))
