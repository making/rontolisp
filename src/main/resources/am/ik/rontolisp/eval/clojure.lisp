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
        ((rontolisp::%clojure-re-pattern-p x)
         (write-string "#\"" stream)
         (write-string (rontolisp::%clojure-re-pat-source x) stream)
         (write-char #\" stream))
        ((rontolisp::%clojure-re-matcher-p x)
         (write-string "#<Matcher " stream)
         (write-string (rontolisp::%clojure-re-pat-source
                        (rontolisp::%clojure-re-match-pat x)) stream)
         (write-char #\> stream))
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
   keyword its colon spelling, anything else the datum. A pattern spells its
   source under str but hash-quote readably (like the oracle); a matcher spells
   unreadably either way."
  (if (rontolisp::%clojure-re-pattern-p x)
      (if readable
          (concatenate 'string "#\"" (rontolisp::%clojure-re-pat-source x) "\"")
          (rontolisp::%clojure-re-pat-source x))
      (let ((stream (make-string-output-stream)))
        (rontolisp::%clojure-print x nil-replacement readable stream)
        (get-output-stream-string stream))))

(defun rontolisp::%clojure-write-datum (x nil-replacement readable)
  "Write X in Clojure notation to *standard-output*: the println/print/pr/prn
   building block. Answers NIL, so a print call's value is nil like the oracle."
  (rontolisp::%clojure-print x nil-replacement readable *standard-output*)
  nil)

;;;; Equality: the = family over every value shape.

(defun rontolisp::%clojure-sequential-p (x)
  "Whether X is sequential for =: nil (the empty list here), a list that is no
   tagged wrapper (a wrapper's car is a CL keyword, which no user list holds),
   a lazy seq, or a non-string vector."
  (or (null x) (rontolisp::%clojure-lazy-p x)
      (and (consp x) (not (keywordp (car x))))
      (and (vectorp x) (not (stringp x)))))

(defun rontolisp::%clojure-seq-equal (a b)
  "Two sequentials compared element by element through the seq view, so a
   vector equals a list or a lazy seq holding equal elements."
  (let ((x (rontolisp::%clojure-seq a))
        (y (rontolisp::%clojure-seq b))
        (same t)
        (done nil))
    (do ()
        (done same)
      (cond ((and (null x) (null y)) (setq done t))
            ((or (null x) (null y))
             (setq same nil)
             (setq done t))
            ((not (rontolisp::%clojure-equal (car x) (car y)))
             (setq same nil)
             (setq done t))
            (t
             (setq x (rontolisp::%clojure-seq (cdr x)))
             (setq y (rontolisp::%clojure-seq (cdr y))))))))

(defun rontolisp::%clojure-table-equal (a b)
  "Two tables with the same count whose every entry agrees under =."
  (and (eql (hash-table-count a) (hash-table-count b))
       (let ((ok t) (miss (list nil)))
         (maphash (lambda (k v)
                    (let ((w (gethash k b miss)))
                      (if (or (eq w miss) (not (rontolisp::%clojure-equal v w)))
                          (setq ok nil)))) a)
         ok)))

(defun rontolisp::%clojure-equal (a b)
  "Clojure = over two values, T or NIL: two sets by membership, two records by
   tag plus entries, a deftype or reify by identity, two maps entry by entry,
   two sequentials element by element, anything else with equal (numbers keep
   their category, strings and characters compare by value)."
  (cond ((and (rontolisp::%clojure-set-p a) (rontolisp::%clojure-set-p b))
         (rontolisp::%clojure-table-equal (car (cdr a)) (car (cdr b))))
        ((and (rontolisp::%clojure-record-p a) (rontolisp::%clojure-record-p b))
         (and (equal (car (cdr a)) (car (cdr b)))
              (rontolisp::%clojure-table-equal (car (cdr (cdr (cdr a))))
                                               (car (cdr (cdr (cdr b)))))))
        ((or (rontolisp::%clojure-typed-opaque-p a)
             (rontolisp::%clojure-typed-opaque-p b)
             (rontolisp::%clojure-record-p a) (rontolisp::%clojure-record-p b))
         (eq a b))
        ((and (hash-table-p a) (hash-table-p b))
         (rontolisp::%clojure-table-equal a b))
        ((and (rontolisp::%clojure-sequential-p a)
              (rontolisp::%clojure-sequential-p b))
         (rontolisp::%clojure-seq-equal a b))
        (t (equal a b))))

(defun rontolisp::%clojure-equal-values (&rest values)
  "= as a function value: T when every neighbouring pair is equal, the false
   object otherwise (no values, or one, is true)."
  (let ((same t))
    (do ((rest values (cdr rest)))
        ((or (not same) (null rest) (null (cdr rest))))
      (if (not (rontolisp::%clojure-equal (car rest) (car (cdr rest))))
          (setq same nil)))
    (if same t rontolisp::%clojure-false)))

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
   ((or (rontolisp::%clojure-re-pattern-p coll)
        (rontolisp::%clojure-re-matcher-p coll))
    (error "seq needs a collection"))
   ;; atoms (and refs/agents/volatiles, the same cell) are cons wrappers
   ;; too, so the oracle signals instead of seqing (b45, the b42 conj-guard
   ;; precedent)
   ((rontolisp::%clojure-atom-p coll) (error "seq needs a collection"))
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

;;;; Core convenience fns (b18): strict vector answers, names and randomness.
;;
;; mapv/filterv answer vectors (never lazy wrappers); mapcat concats the mapped
;; seq views strictly (nil-safe, like concat); shuffle Fisher-Yates over a fresh
;; vector (membership and count pin, never order); name/namespace read the
;; (:C%KEYWORD spelling) wrapper and demangled symbol names, split at the first
;; slash; rand/rand-int/rand-nth/shuffle draw from the program-owned generator
;; behind the CL random primitive (never a host call per draw, .kb/random.md).

(defun rontolisp::%clojure-realize-all (coll)
  "The fully strict list view of COLL: steps through lazy wrappers one level at
   a time, so mapv/filterv/mapcat/rand-nth answer strictly even over lazy
   inputs (an infinite input hangs, like the oracle's)."
  (let ((s (rontolisp::%clojure-seq coll)) (acc nil))
    (do ()
        ((null s) (reverse acc))
      (setq acc (cons (car s) acc))
      (setq s (rontolisp::%clojure-seq (cdr s))))))

(defun rontolisp::%clojure-mapv (f colls)
  "Map F over the COLLS list, answering a vector (of empty, the empty vector)."
  (coerce (apply #'mapcar (lambda (&rest xs) (rontolisp::%clojure-call f xs))
                 (mapcar #'rontolisp::%clojure-realize-all colls)) 'vector))

(defun rontolisp::%clojure-filterv (pred coll)
  "Filter COLL through PRED under Clojure truthiness, answering a vector."
  (coerce (remove-if-not (lambda (x) (rontolisp::%clojure-filter-test pred x))
                         (rontolisp::%clojure-realize-all coll)) 'vector))

(defun rontolisp::%clojure-mapcat (f colls)
  "Map F over the COLLS list and concat the mapped seq views, strictly
   (nil-safe: a nil result contributes nothing, like concat)."
  (apply #'append
         (mapcar #'rontolisp::%clojure-realize-all
                 (apply #'mapcar
                        (lambda (&rest xs) (rontolisp::%clojure-call f xs))
                        (mapcar #'rontolisp::%clojure-realize-all colls)))))

(defun rontolisp::%clojure-shuffle (items)
  "Fisher-Yates over the strict ITEMS list, answering a fresh vector (never the
   input: coerce from a list always copies)."
  (let ((w (coerce items 'vector)) (n (length items)))
    (do ((i (- n 1) (- i 1)))
        ((< i 1) w)
      (let ((j (random (+ i 1))))
        (let ((tmp (aref w i)))
          (setf (aref w i) (aref w j))
          (setf (aref w j) tmp))))))

(defun rontolisp::%clojure-real-symbol-p (x)
  "Whether X is a real symbol for name/namespace/keyword/symbol: symbolp minus
   nil, T and the false object (the symbol? rule)."
  (and (symbolp x) (not (null x)) (not (eq x t))
       (not (eq x rontolisp::%clojure-false))))

(defun rontolisp::%clojure-unescape-part (s)
  "Undo the mangle over the string S: %c -> :, %% -> %, anything else literal
   (the %clojure-write-demangled rule, as a value)."
  (let ((out "") (i 0) (n (length s)))
    (do ()
        ((>= i n) out)
      (let ((c (char s i)))
        (if (and (char= c #\%) (< (+ i 1) n)
                 (or (char= (char s (+ i 1)) #\%) (char= (char s (+ i 1)) #\c)))
            (progn
              (setq out
                    (concatenate 'string out
                     (string (if (char= (char s (+ i 1)) #\c) #\: #\%))))
              (setq i (+ i 2)))
            (progn
              (setq out (concatenate 'string out (string c)))
              (setq i (+ i 1))))))))

(defun rontolisp::%clojure-escape-part (s)
  "The mangle over the string S: % -> %%, : -> %c (slash passes through, so a
   later split at the first slash sees the real separator)."
  (let ((out "") (n (length s)))
    (do ((i 0 (+ i 1)))
        ((>= i n) out)
      (let ((c (char s i)))
        (cond ((char= c #\%) (setq out (concatenate 'string out "%%")))
              ((char= c #\:) (setq out (concatenate 'string out "%c")))
              (t (setq out (concatenate 'string out (string c)))))))))

(defun rontolisp::%clojure-symbol-full-name (x)
  "The Clojure spelling of the symbol X: the member name demangled (the c%
   prefix stripped, escapes decoded), or the raw name when unprefixed (a
   gensym keeps its own spelling)."
  (let ((name (symbol-name x)))
    (if (and (>= (length name) 2) (char= (char name 0) #\c)
             (char= (char name 1) #\%))
        (rontolisp::%clojure-unescape-part (subseq name 2))
        name)))

(defun rontolisp::%clojure-split-name (s)
  "The part of the S spelling past the first slash (the whole S when none)."
  (let ((at (search "/" s))) (if at (subseq s (+ at 1)) s)))

(defun rontolisp::%clojure-split-namespace (s)
  "The part of the S spelling before the first slash, or NIL when none."
  (let ((at (search "/" s))) (if at (subseq s 0 at) nil)))

(defun rontolisp::%clojure-name (x)
  "The name of X: a string itself, a keyword's spelling past the slash, a
   symbol's demangled name past the slash; anything else signals."
  (cond ((stringp x) x)
        ((rontolisp::%clojure-keyword-p x)
         (rontolisp::%clojure-split-name (car (cdr x))))
        ((rontolisp::%clojure-real-symbol-p x)
         (rontolisp::%clojure-split-name
          (rontolisp::%clojure-symbol-full-name x)))
        (t (error "name needs a string, keyword or symbol"))))

(defun rontolisp::%clojure-namespace (x)
  "The namespace of X: a keyword's spelling before the slash, a symbol's
   demangled name before the slash, NIL when absent; strings and anything else
   signal, like the oracle."
  (cond ((rontolisp::%clojure-keyword-p x)
         (rontolisp::%clojure-split-namespace (car (cdr x))))
        ((rontolisp::%clojure-real-symbol-p x)
         (rontolisp::%clojure-split-namespace
          (rontolisp::%clojure-symbol-full-name x)))
        (t (error "namespace needs a keyword or symbol"))))

(defun rontolisp::%clojure-keyword-1 (x)
  "The keyword for X: itself for a keyword, the demangled spelling for a
   symbol, the string itself for a string, NIL for anything else."
  (cond ((rontolisp::%clojure-keyword-p x) x)
        ((rontolisp::%clojure-real-symbol-p x)
         (list :C%KEYWORD (rontolisp::%clojure-symbol-full-name x)))
        ((stringp x) (list :C%KEYWORD x))
        (t nil)))

(defun rontolisp::%clojure-symbol-1 (x)
  "The symbol for X: itself for a symbol, the spelled one for a keyword or a
   string; anything else signals."
  (cond ((rontolisp::%clojure-real-symbol-p x) x)
        ((rontolisp::%clojure-keyword-p x)
         (intern
          (concatenate 'string "c%"
                       (rontolisp::%clojure-escape-part (car (cdr x))))))
        ((stringp x)
         (intern
          (concatenate 'string "c%" (rontolisp::%clojure-escape-part x))))
        (t (error "symbol needs a string, keyword or symbol"))))

(defun rontolisp::%clojure-keyword-2 (ns nm)
  "The keyword for namespace NS and name NM (a NIL namespace drops, like the
   oracle; a NIL name signals)."
  (if (null ns)
      (if (null nm)
          (error "keyword needs a name")
          (rontolisp::%clojure-keyword-1 nm))
      (if (null nm)
          (error "keyword needs a name")
          (list :C%KEYWORD (concatenate 'string ns "/" nm)))))

(defun rontolisp::%clojure-symbol-2 (ns nm)
  "The symbol for namespace NS and name NM (a NIL namespace is the one-argument
   shape; NIL spells \"null\", like the oracle's a/null)."
  (if (null ns)
      (rontolisp::%clojure-symbol-1 nm)
      (intern
       (concatenate 'string "c%" (rontolisp::%clojure-escape-part ns) "/"
        (rontolisp::%clojure-escape-part (if (null nm) "null" nm))))))

(defun rontolisp::%clojure-char (x)
  "The character for X: itself for a character, the code point (truncated)
   for a number; anything else signals."
  (cond ((characterp x) x)
        ((numberp x) (code-char (truncate x)))
        (t (error "char needs a character or a number"))))

;;;; Regular expressions (b21): patterns, matchers, and the pattern arms of
;;;; split/replace.
;;
;; A pattern is (LIST :C%PATTERN stamp source ops ngroups): STAMP a fresh
;; gensym, so EQUAL is identity like the oracle; SOURCE the pattern string;
;; OPS the parsed opcode tree; NGROUPS the capturing-group count. A matcher is
;; (LIST :C%MATCHER stamp pattern input cell) where CELL is (CONS next-pos
;; last), mutated through rplaca/rplacd like the lazy cell; LAST is NIL or
;; (start end groups). Opcodes are keyword-headed lists: (:lit code),
;; (:dot), (:cls negated items) over integer codes, (:r lo hi) ranges and :w
;; :s :d members (a nested (:cls ...) member is a use of [\W] and friends),
;; (:bol) (:zend) (:eol) (:wb) (:nwb), (:seq ops...), (:alt a b),
;; (:rep op min max mode) with a NIL max unbounded and a :greedy,
;; :reluctant or :possessive mode, (:grp idx op), (:backref idx).
;; The parser signals unsupported constructs (lookarounds,
;; named groups, inline flags, POSIX classes, &&
;; intersections, \G, \E outside \Q..\E) instead of answering wrongly; an
;; unknown alphabetic escape signals too, while a backslashed
;; non-alphanumeric spells itself. What differs from the oracle on purpose:
;; . is any character but \n and \r; $ is the end or just before one final
;; \n or \r; \s and \b are ASCII; there is no \p, octal tops up at two digits
;; past the leading zero, and an unmatched group substitutes "" in a
;; replacement (missing groups and a trailing $ still signal, like the
;; oracle). A replacement function renders through str, so NIL answers ""
;; where the oracle throws its NullPointerException.

(defun rontolisp::%clojure-re-pattern-p (x)
  "Whether X is the (:C%PATTERN stamp source ops ngroups) wrapper the
   lowering lowers regex literals to."
  (and (consp x) (eq (car x) :C%PATTERN) (consp (cdr x)) (consp (cdr (cdr x)))
       (stringp (car (cdr (cdr x)))) (consp (cdr (cdr (cdr x))))
       (consp (cdr (cdr (cdr (cdr x)))))
       (null (cdr (cdr (cdr (cdr (cdr x))))))))

(defun rontolisp::%clojure-re-matcher-p (x)
  "Whether X is the (:C%MATCHER stamp pattern input cell) wrapper re-matcher
   builds."
  (and (consp x) (eq (car x) :C%MATCHER) (consp (cdr x)) (consp (cdr (cdr x)))
       (rontolisp::%clojure-re-pattern-p (car (cdr (cdr x))))
       (consp (cdr (cdr (cdr x)))) (stringp (car (cdr (cdr (cdr x)))))
       (consp (cdr (cdr (cdr (cdr x))))) (consp (car (cdr (cdr (cdr (cdr x))))))
       (null (cdr (cdr (cdr (cdr (cdr x))))))))

(defun rontolisp::%clojure-re-pat-source (p)
  "The SOURCE slot of the pattern P."
  (car (cdr (cdr p))))

(defun rontolisp::%clojure-re-pat-ops (p)
  "The OPS slot of the pattern P."
  (car (cdr (cdr (cdr p)))))

(defun rontolisp::%clojure-re-pat-ngroups (p)
  "The NGROUPS slot of the pattern P."
  (car (cdr (cdr (cdr (cdr p))))))

(defun rontolisp::%clojure-re-match-pat (m)
  "The PATTERN slot of the matcher M."
  (car (cdr (cdr m))))

(defun rontolisp::%clojure-re-match-input (m)
  "The INPUT slot of the matcher M."
  (car (cdr (cdr (cdr m)))))

(defun rontolisp::%clojure-re-match-cell (m)
  "The CELL slot of the matcher M."
  (car (cdr (cdr (cdr (cdr m))))))

(defun rontolisp::%clojure-re-as-pattern (x message)
  "X when it is a pattern, else the MESSAGE signal (re-find and friends take
   patterns, never strings, like the oracle)."
  (if (rontolisp::%clojure-re-pattern-p x) x (error message)))

(defun rontolisp::%clojure-re-word-char-p (code)
  "Whether CODE is an ASCII word character ([A-Za-z0-9_], like the oracle)."
  (or (and (<= 48 code) (<= code 57)) (and (<= 65 code) (<= code 90))
      (or (and (<= 97 code) (<= code 122)) (= code 95))))

(defun rontolisp::%clojure-re-digit-code-p (code)
  "Whether CODE is an ASCII digit."
  (and (<= 48 code) (<= code 57)))

(defun rontolisp::%clojure-re-alpha-code-p (code)
  "Whether CODE is ASCII alphanumeric."
  (or (rontolisp::%clojure-re-digit-code-p code) (and (<= 65 code) (<= code 90))
      (and (<= 97 code) (<= code 122))))

(defun rontolisp::%clojure-re-space-code-p (code)
  "Whether CODE is ASCII whitespace (like the oracle's \\s)."
  (or (= code 32) (= code 9) (= code 10) (= code 12) (= code 13) (= code 11)))

(defun rontolisp::%clojure-re-hex-value (code)
  "The hex value of CODE, or NIL."
  (cond ((and (<= 48 code) (<= code 57)) (- code 48))
        ((and (<= 65 code) (<= code 70)) (- code 55))
        ((and (<= 97 code) (<= code 102)) (- code 87))
        (t nil)))

(defun rontolisp::%clojure-re-parse-digits (s len i)
  "Digits at I: (pos value), or NIL without one."
  (if (or (>= i len)
          (not (rontolisp::%clojure-re-digit-code-p (char-code (char s i)))))
      nil
      (rontolisp::%clojure-re-parse-digits-acc s len (+ i 1)
                                               (- (char-code (char s i)) 48))))

(defun rontolisp::%clojure-re-parse-digits-acc (s len i v)
  "The digit run value from I onto V: (pos value)."
  (if (or (>= i len)
          (not (rontolisp::%clojure-re-digit-code-p (char-code (char s i)))))
      (list i v)
      (rontolisp::%clojure-re-parse-digits-acc s len (+ i 1)
       (+ (* v 10) (- (char-code (char s i)) 48)))))

(defun rontolisp::%clojure-re-parse (source)
  "The (ops ngroups) of the pattern SOURCE, or a signal."
  (let ((r (rontolisp::%clojure-re-parse-alt source (length source) 0 0)))
    (if (not (= (car r) (length source)))
        (error "unsupported regex: unmatched )")
        (list (car (cdr r)) (car (cdr (cdr r)))))))

(defun rontolisp::%clojure-re-parse-alt (s len i n)
  "An alternation at I: (pos node ngroups)."
  (let ((r (rontolisp::%clojure-re-parse-seq s len i n)))
    (let ((i1 (car r)) (a (car (cdr r))) (n1 (car (cdr (cdr r)))))
      (if (and (< i1 len) (= (char-code (char s i1)) (char-code #\|)))
          (let ((r2 (rontolisp::%clojure-re-parse-alt s len (+ i1 1) n1)))
            (list (car r2) (list :alt a (car (cdr r2))) (car (cdr (cdr r2)))))
          r))))

(defun rontolisp::%clojure-re-seq-node (ops)
  "The node for the OPS list: empty is (:seq) (matches empty), one is itself."
  (if (null ops) (list :seq) (if (null (cdr ops)) (car ops) (cons :seq ops))))

(defun rontolisp::%clojure-re-parse-seq (s len i n)
  "A sequence at I: (pos node ngroups)."
  (rontolisp::%clojure-re-parse-seq-acc s len i n nil))

(defun rontolisp::%clojure-re-parse-seq-acc (s len i n acc)
  "The sequence tail at I over the reversed ACC: (pos node ngroups)."
  (if (or (>= i len) (= (char-code (char s i)) (char-code #\)))
          (= (char-code (char s i)) (char-code #\|)))
      (list i (rontolisp::%clojure-re-seq-node (reverse acc)) n)
      (let ((r (rontolisp::%clojure-re-parse-atom s len i n)))
        (rontolisp::%clojure-re-parse-seq-acc s len (car r) (car (cdr (cdr r)))
                                              (cons (car (cdr r)) acc)))))

(defun rontolisp::%clojure-re-parse-atom (s len i n)
  "One quantified atom at I: (pos node ngroups)."
  (let ((r (rontolisp::%clojure-re-parse-base s len i n)))
    (let ((i1 (car r)) (b (car (cdr r))) (n1 (car (cdr (cdr r)))))
      (let ((q
             (if (< i1 len) (rontolisp::%clojure-re-parse-quant s len i1) nil)))
        (if (null q)
            r
            (list (car q)
                  (list :rep b (car (cdr q)) (car (cdr (cdr q)))
                        (car (cdr (cdr (cdr q))))) n1))))))

(defun rontolisp::%clojure-re-quant-tail (s len j min max greedy)
  "A quantifier past its body: reluctant on ?, possessive on +."
  (if (and (< j len) (= (char-code (char s j)) (char-code #\?)))
      (list (+ j 1) min max :reluctant)
      (if (and (< j len) (= (char-code (char s j)) (char-code #\+)))
          (list (+ j 1) min max :possessive)
          (list j min max greedy))))

(defun rontolisp::%clojure-re-parse-quant (s len i)
  "A quantifier at I: (pos min max greedy-p), or NIL."
  (let ((c (char-code (char s i))))
    (cond ((= c (char-code #\*))
           (rontolisp::%clojure-re-quant-tail s len (+ i 1) 0 nil :greedy))
          ((= c (char-code #\+))
           (rontolisp::%clojure-re-quant-tail s len (+ i 1) 1 nil :greedy))
          ((= c (char-code #\?))
           (rontolisp::%clojure-re-quant-tail s len (+ i 1) 0 1 :greedy))
          ((= c (char-code #\{)) (rontolisp::%clojure-re-parse-braces s len i))
          (t nil))))

(defun rontolisp::%clojure-re-parse-braces (s len i)
  "A {n[,m]} quantifier at I: (pos min max greedy-p), or NIL when the braces
   hold no quantifier (a literal { instead, like the oracle)."
  (let ((r (rontolisp::%clojure-re-parse-digits s len (+ i 1))))
    (if (null r)
        nil
        (let ((j (car r)) (lo (car (cdr r))))
          (if (and (< j len) (= (char-code (char s j)) (char-code #\,)))
              (let ((r2 (rontolisp::%clojure-re-parse-digits s len (+ j 1))))
                (let ((k (if r2 (car r2) (+ j 1)))
                      (hi (if r2 (car (cdr r2)) nil)))
                  (if (or (>= k len)
                          (not (= (char-code (char s k)) (char-code #\}))))
                      nil
                      (if (and hi (< hi lo))
                          (error "unsupported regex: bad repetition range")
                          (rontolisp::%clojure-re-quant-tail s len (+ k 1) lo hi
                                                             :greedy)))))
              (if (or (>= j len)
                      (not (= (char-code (char s j)) (char-code #\}))))
                  nil
                  (rontolisp::%clojure-re-quant-tail s len (+ j 1) lo lo
                                                     :greedy)))))))

(defun rontolisp::%clojure-re-parse-base (s len i n)
  "One atom at I: (pos node ngroups)."
  (let ((c (char-code (char s i))))
    (cond ((= c (char-code #\()) (rontolisp::%clojure-re-parse-group s len i n))
     ((= c (char-code #\[))
      (let ((r (rontolisp::%clojure-re-parse-class s len (+ i 1))))
        (list (car r) (car (cdr r)) n)))
     ((= c (char-code #\.)) (list (+ i 1) (list :dot) n))
     ((= c (char-code #\^)) (list (+ i 1) (list :bol) n))
     ((= c (char-code #\$)) (list (+ i 1) (list :eol) n))
     ((= c (char-code #\\)) (rontolisp::%clojure-re-parse-escape s len i n nil))
     ((or (= c (char-code #\*)) (= c (char-code #\+)) (= c (char-code #\?)))
      (error "unsupported regex: dangling quantifier"))
     ((or (= c (char-code #\{)) (= c (char-code #\})))
      (list (+ i 1) (list :lit c) n))
     ((or (= c (char-code #\))) (= c (char-code #\|)))
      (error "unsupported regex: unmatched delimiter"))
     (t (list (+ i 1) (list :lit c) n)))))

(defun rontolisp::%clojure-re-parse-group (s len i n)
  "A group at I (the opening paren): (pos node ngroups)."
  (if (and (< (+ i 1) len) (= (char-code (char s (+ i 1))) (char-code #\?)))
      (rontolisp::%clojure-re-parse-group-q s len i n)
      (let ((idx (+ n 1)))
        (let ((r (rontolisp::%clojure-re-parse-alt s len (+ i 1) idx)))
          (let ((j (car r)))
            (if (or (>= j len) (not (= (char-code (char s j)) (char-code #\)))))
                (error "unsupported regex: unclosed group")
                (list (+ j 1) (list :grp idx (car (cdr r)))
                      (car (cdr (cdr r))))))))))

(defun rontolisp::%clojure-re-parse-group-q (s len i n)
  "A (? group at I: only (?:...) lowers, the rest is refused by name."
  (if (or (>= (+ i 2) len)
          (not (= (char-code (char s (+ i 2))) (char-code #\:))))
      (error "unsupported regex: only (?:...) groups are supported")
      (let ((r (rontolisp::%clojure-re-parse-alt s len (+ i 3) n)))
        (let ((j (car r)))
          (if (or (>= j len) (not (= (char-code (char s j)) (char-code #\)))))
              (error "unsupported regex: unclosed group")
              (list (+ j 1) (car (cdr r)) (car (cdr (cdr r)))))))))

(defun rontolisp::%clojure-re-parse-escape (s len i n in-class)
  "An escape at I (the backslash): (pos node ngroups)."
  (if (>= (+ i 1) len)
      (error "unsupported regex: trailing backslash")
      (let ((e (char-code (char s (+ i 1)))))
        (cond ((= e (char-code #\w)) (list (+ i 2) (list :cls nil (list :w)) n))
              ((= e (char-code #\W)) (list (+ i 2) (list :cls t (list :w)) n))
              ((= e (char-code #\s)) (list (+ i 2) (list :cls nil (list :s)) n))
              ((= e (char-code #\S)) (list (+ i 2) (list :cls t (list :s)) n))
              ((= e (char-code #\d)) (list (+ i 2) (list :cls nil (list :d)) n))
              ((= e (char-code #\D)) (list (+ i 2) (list :cls t (list :d)) n))
              ((= e (char-code #\b))
               (if in-class
                   (list (+ i 2) (list :lit 8) n)
                   (list (+ i 2) (list :wb) n)))
              ((= e (char-code #\B))
               (if in-class
                   (error "unsupported regex: bad escape")
                   (list (+ i 2) (list :nwb) n)))
              ((= e (char-code #\A))
               (if in-class
                   (error "unsupported regex: bad escape")
                   (list (+ i 2) (list :bol) n)))
              ((= e (char-code #\z))
               (if in-class
                   (error "unsupported regex: bad escape")
                   (list (+ i 2) (list :zend) n)))
              ((= e (char-code #\G))
               (error "unsupported regex: \\G is not supported"))
              ((= e (char-code #\n)) (list (+ i 2) (list :lit 10) n))
              ((= e (char-code #\t)) (list (+ i 2) (list :lit 9) n))
              ((= e (char-code #\r)) (list (+ i 2) (list :lit 13) n))
              ((= e (char-code #\f)) (list (+ i 2) (list :lit 12) n))
              ((= e (char-code #\a)) (list (+ i 2) (list :lit 7) n))
              ((= e (char-code #\e)) (list (+ i 2) (list :lit 27) n))
              ((= e (char-code #\u))
               (rontolisp::%clojure-re-parse-hex s len (+ i 2) 4 n))
              ((= e (char-code #\x))
               (rontolisp::%clojure-re-parse-hex s len (+ i 2) 2 n))
              ((= e (char-code #\c))
               (rontolisp::%clojure-re-parse-control s len (+ i 2) n))
              ((= e (char-code #\Q))
               (rontolisp::%clojure-re-parse-quoted s len (+ i 2) n))
              ((= e (char-code #\E)) (error "unsupported regex: lone \\E"))
              ((= e 48) (rontolisp::%clojure-re-parse-octal s len (+ i 1) n))
              ((and (<= 49 e) (<= e 57))
               (rontolisp::%clojure-re-parse-backref s len (+ i 2) (- e 48) n))
              ((rontolisp::%clojure-re-alpha-code-p e)
               (error "unsupported regex: bad escape"))
              (t (list (+ i 2) (list :lit e) n))))))

(defun rontolisp::%clojure-re-parse-hex (s len j count n)
  "COUNT hex digits at J: (pos node ngroups)."
  (if (> (+ j count) len)
      (error "unsupported regex: bad hex escape")
      (let ((v (rontolisp::%clojure-re-hex-acc s j (+ j count) 0)))
        (if (null v)
            (error "unsupported regex: bad hex escape")
            (list (+ j count) (list :lit v) n)))))

(defun rontolisp::%clojure-re-hex-acc (s j end v)
  "The hex value over [J, END): the number, or NIL past a non-hex digit."
  (if (>= j end)
      v
      (let ((d (rontolisp::%clojure-re-hex-value (char-code (char s j)))))
        (if (null d)
            nil
            (rontolisp::%clojure-re-hex-acc s (+ j 1) end (+ (* v 16) d))))))

(defun rontolisp::%clojure-re-parse-control (s len j n)
  "A \\cX control character at J: (pos node ngroups)."
  (if (>= j len)
      (error "unsupported regex: bad control escape")
      (let ((c (char-code (char s j))))
        (if (and (<= 65 c) (<= c 90))
            (list (+ j 1) (list :lit (- c 64)) n)
            (if (and (<= 97 c) (<= c 122))
                (list (+ j 1) (list :lit (- c 96)) n)
                (error "unsupported regex: bad control escape"))))))

(defun rontolisp::%clojure-re-parse-octal (s len j n)
  "An octal escape at J (the leading zero): (pos node ngroups)."
  (let ((k (rontolisp::%clojure-re-octal-end s len (+ j 1) (+ j 3))))
    (list k (list :lit (rontolisp::%clojure-re-octal-value s j k 0)) n)))

(defun rontolisp::%clojure-re-octal-end (s len j stop)
  "Past up to two more octal digits from J: the end position."
  (if (or (>= j stop) (>= j len)
          (not
           (and (<= 48 (char-code (char s j))) (<= (char-code (char s j)) 55))))
      j
      (rontolisp::%clojure-re-octal-end s len (+ j 1) stop)))

(defun rontolisp::%clojure-re-octal-value (s j end v)
  "The octal value over [J, END)."
  (if (>= j end)
      v
      (rontolisp::%clojure-re-octal-value s (+ j 1) end
       (+ (* v 8) (- (char-code (char s j)) 48)))))

(defun rontolisp::%clojure-re-parse-backref (s len j v n)
  "A backreference from J (past the first digit) over V: (pos node ngroups).
   Any digit run compiles (like the oracle); a group that never participates
   never matches."
  (if (or (>= j len)
          (not (rontolisp::%clojure-re-digit-code-p (char-code (char s j)))))
      (list j (list :backref v) n)
      (rontolisp::%clojure-re-parse-backref s len (+ j 1)
       (+ (* v 10) (- (char-code (char s j)) 48)) n)))

(defun rontolisp::%clojure-re-parse-quoted (s len j n)
  "A \\Q..\\E span from J: (pos node ngroups) of literal codes."
  (let ((end (rontolisp::%clojure-re-quoted-end s len j)))
    (let ((codes (rontolisp::%clojure-re-quoted-codes s j end nil)))
      (list (if (>= end len) len (+ end 2))
            (rontolisp::%clojure-re-seq-node
             (mapcar (lambda (c) (list :lit c)) codes)) n))))

(defun rontolisp::%clojure-re-quoted-end (s len j)
  "The position of the \\E closing the quote from J, or LEN."
  (if (or (>= j len)
          (and (= (char-code (char s j)) (char-code #\\)) (< (+ j 1) len)
               (= (char-code (char s (+ j 1))) (char-code #\E))))
      j
      (rontolisp::%clojure-re-quoted-end s len (+ j 1))))

(defun rontolisp::%clojure-re-quoted-codes (s j end acc)
  "The codes over [J, END), reversed onto ACC."
  (if (>= j end)
      (reverse acc)
      (rontolisp::%clojure-re-quoted-codes s (+ j 1) end
                                           (cons (char-code (char s j)) acc))))

(defun rontolisp::%clojure-re-parse-class (s len i)
  "A character class body from I (past the opening bracket): (pos node)."
  (let ((neg nil) (j i) (first nil))
    (if (and (< j len) (= (char-code (char s j)) 94))
        (progn
          (setq neg t)
          (setq j (+ j 1)))
        nil)
    (if (and (< j len) (= (char-code (char s j)) 93))
        (progn
          (setq first (list 93))
          (setq j (+ j 1)))
        nil)
    (let ((r (rontolisp::%clojure-re-class-rest s len j first)))
      (list (car r) (list :cls neg (reverse (car (cdr r))))))))

(defun rontolisp::%clojure-re-class-rest (s len j acc)
  "The class tail from J over the reversed ACC: (endpos items)."
  (cond ((>= j len) (error "unsupported regex: unclosed character class"))
        ((= (char-code (char s j)) 93) (list (+ j 1) acc))
        (t (let ((step (rontolisp::%clojure-re-class-item s len j acc)))
             (rontolisp::%clojure-re-class-rest s len (car step)
                                                (car (cdr step)))))))

(defun rontolisp::%clojure-re-class-item (s len j acc)
  "One class item at J over the reversed ACC: (pos items)."
  (let ((c (char-code (char s j))))
    (cond ((= c 92) (rontolisp::%clojure-re-class-escaped s len j acc))
          ((and (= c 91) (< (+ j 1) len)
                (= (char-code (char s (+ j 1))) (char-code #\:)))
           (error "unsupported regex: POSIX classes are not supported"))
          ((and (= c 38) (< (+ j 1) len) (= (char-code (char s (+ j 1))) 38))
           (error "unsupported regex: class intersection is not supported"))
          (t (rontolisp::%clojure-re-class-range s len j acc c (+ j 1))))))

(defun rontolisp::%clojure-re-class-range (s len j acc lo k)
  "The item for the code LO at J, a range past K when a dash follows: (pos
   items). A dash at the end (or past the end) is literal."
  (if (and (< k len) (= (char-code (char s k)) (char-code #\-)) (< (+ k 1) len)
           (not (= (char-code (char s (+ k 1))) (char-code #\]))))
      (let ((r (rontolisp::%clojure-re-class-range-end s len (+ k 1))))
        (if (> lo (car (cdr r)))
            (error "unsupported regex: bad character range")
            (list (car r) (cons (list :r lo (car (cdr r))) acc))))
      (list k (cons lo acc))))

(defun rontolisp::%clojure-re-class-range-end (s len j)
  "A range endpoint at J: (pos code)."
  (if (= (char-code (char s j)) 92)
      (let ((r (rontolisp::%clojure-re-parse-escape s len j 0 t)))
        (let ((v (car (cdr r))))
          (if (and (consp v) (eq (car v) :lit))
              (list (car r) (car (cdr v)))
              (error "unsupported regex: bad character range"))))
      (list (+ j 1) (char-code (char s j)))))

(defun rontolisp::%clojure-re-class-escaped (s len j acc)
  "A backslashed class item at J (the backslash) over the reversed ACC:
   (pos items). Single codes may open ranges; class nodes never do."
  (let ((r (rontolisp::%clojure-re-parse-escape s len j 0 t)))
    (let ((k (car r)) (v (car (cdr r))))
      (cond ((and (consp v) (eq (car v) :lit))
             (rontolisp::%clojure-re-class-range s len j acc (car (cdr v)) k))
            ((and (consp v) (eq (car v) :cls))
             (if (rontolisp::%clojure-re-dash-follows s len k)
                 (error "unsupported regex: bad character range")
                 (list k (cons v acc))))
            ((and (consp v) (eq (car v) :seq))
             (rontolisp::%clojure-re-class-splice s len k acc (cdr v)))
            (t (error "unsupported regex: bad escape"))))))

(defun rontolisp::%clojure-re-dash-follows (s len k)
  "Whether a range dash follows at K (a dash past a non-] char)."
  (and (< k len) (= (char-code (char s k)) (char-code #\-)) (< (+ k 1) len)
       (not (= (char-code (char s (+ k 1))) (char-code #\])))))

(defun rontolisp::%clojure-re-class-splice (s len k acc lits)
  "The \\Q..\\E literals LITS spliced raw (no ranges open inside a quote)."
  (if (null lits)
      (list k acc)
      (rontolisp::%clojure-re-class-splice s len k
                                           (cons (car (cdr (car lits))) acc)
                                           (cdr lits))))

(defun rontolisp::%clojure-re-item-test (item code)
  "Whether the class ITEM matches CODE."
  (cond ((integerp item) (= item code))
        ((eq item :w) (rontolisp::%clojure-re-word-char-p code))
        ((eq item :s) (rontolisp::%clojure-re-space-code-p code))
        ((eq item :d) (rontolisp::%clojure-re-digit-code-p code))
        ((and (consp item) (eq (car item) :r))
         (and (<= (car (cdr item)) code) (<= code (car (cdr (cdr item))))))
        ((and (consp item) (eq (car item) :cls))
         (rontolisp::%clojure-re-cls-test (car (cdr item)) (cdr (cdr item))
                                          code))
        (t nil)))

(defun rontolisp::%clojure-re-any-item (items code)
  "Whether any member of ITEMS matches CODE."
  (if (null items)
      nil
      (or (rontolisp::%clojure-re-item-test (car items) code)
          (rontolisp::%clojure-re-any-item (cdr items) code))))

(defun rontolisp::%clojure-re-cls-test (neg items code)
  "Whether CODE is in the class (NEG negated)."
  (let ((hit (rontolisp::%clojure-re-any-item items code)))
    (if neg (not hit) hit)))

(defun rontolisp::%clojure-re-word-boundary-p (s len pos)
  "Whether POS is a word boundary (ASCII word characters, like the oracle)."
  (let ((left
         (and (> pos 0)
          (rontolisp::%clojure-re-word-char-p (char-code (char s (- pos 1))))))
        (right
         (and (< pos len)
              (rontolisp::%clojure-re-word-char-p (char-code (char s pos))))))
    (if left (not right) right)))

(defun rontolisp::%clojure-re-match (op s len pos groups k)
  "Match OP at POS: the success continuation K over (end groups), or NIL.
   Backtracking rides OR: a continuation answering NIL retries the next
   alternative, so each combinator tries every choice in order."
  (let ((tag (car op)))
    (cond ((eq tag :lit)
           (if (and (< pos len) (= (char-code (char s pos)) (car (cdr op))))
               (funcall k (+ pos 1) groups)
               nil))
          ((eq tag :dot)
           (if (and (< pos len) (not (= (char-code (char s pos)) 10))
                    (not (= (char-code (char s pos)) 13)))
               (funcall k (+ pos 1) groups)
               nil))
          ((eq tag :cls)
           (if (and (< pos len)
                    (rontolisp::%clojure-re-cls-test (car (cdr op))
                                                     (car (cdr (cdr op)))
                                                     (char-code (char s pos))))
               (funcall k (+ pos 1) groups)
               nil))
          ((eq tag :bol) (if (= pos 0) (funcall k pos groups) nil))
          ((eq tag :zend) (if (= pos len) (funcall k pos groups) nil))
          ((eq tag :eol)
           (if (or (= pos len)
                   (and (= pos (- len 1)) (> len 0)
                        (let ((c (char-code (char s (- len 1)))))
                          (or (= c 10) (= c 13)))))
               (funcall k pos groups)
               nil))
          ((eq tag :wb)
           (if (rontolisp::%clojure-re-word-boundary-p s len pos)
               (funcall k pos groups)
               nil))
          ((eq tag :nwb)
           (if (rontolisp::%clojure-re-word-boundary-p s len pos)
               nil
               (funcall k pos groups)))
          ((eq tag :seq)
           (rontolisp::%clojure-re-match-seq (cdr op) s len pos groups k))
          ((eq tag :alt)
           (or (rontolisp::%clojure-re-match (car (cdr op)) s len pos groups k)
               (rontolisp::%clojure-re-match (car (cdr (cdr op))) s len pos
                                             groups k)))
          ((eq tag :backref)
           (rontolisp::%clojure-re-match-backref (car (cdr op)) groups s len pos
                                                 k))
          ((eq tag :rep)
           (rontolisp::%clojure-re-match-rep (car (cdr op)) (car (cdr (cdr op)))
                                             (car (cdr (cdr (cdr op))))
                                             (car (cdr (cdr (cdr (cdr op))))) s
                                             len pos groups k))
          ((eq tag :grp)
           (rontolisp::%clojure-re-match (car (cdr (cdr op))) s len pos groups
                                         (lambda (p2 g2)
                                           (funcall k p2
                                                    (cons (list (car (cdr op))
                                                                pos p2) g2)))))
          (t (error "unsupported regex: bad opcode")))))

(defun rontolisp::%clojure-re-match-seq (ops s len pos groups k)
  "Match the OPS list in order at POS."
  (if (null ops)
      (funcall k pos groups)
      (rontolisp::%clojure-re-match (car ops) s len pos groups
                                    (lambda (p2 g2)
                                      (rontolisp::%clojure-re-match-seq
                                       (cdr ops) s len p2 g2 k)))))

(defun rontolisp::%clojure-re-match-backref (idx groups s len pos k)
  "Match the IDX group's captured string at POS (fail past no match)."
  (let ((b (assoc idx groups)))
    (if (null b)
        nil
        (let ((gs (car (cdr b))) (ge (car (cdr (cdr b)))))
          (if (and (<= (+ pos (- ge gs)) len)
                   (string= (subseq s gs ge) (subseq s pos (+ pos (- ge gs)))))
              (funcall k (+ pos (- ge gs)) groups)
              nil)))))

(defun rontolisp::%clojure-re-match-rep (unit min max mode s len pos groups k)
  "Match UNIT between MIN and (MAX, NIL unbounded) times."
  (cond ((eq mode :reluctant)
         (rontolisp::%clojure-re-match-rep-reluctant unit min max s len pos
                                                     groups k))
        ((eq mode :possessive)
         (rontolisp::%clojure-re-match-rep-possessive unit min max s len pos
                                                      groups k))
        (t (rontolisp::%clojure-re-match-rep-greedy unit min max s len pos
                                                    groups k))))

(defun rontolisp::%clojure-re-match-rep-greedy (unit min max s len pos groups k)
  "Greedy repetition: one more iteration first, fewer on failure. An empty
   iteration settles (it must not loop, like the oracle)."
  (if (and max (= max 0))
      (if (> min 0) nil (funcall k pos groups))
      (or (rontolisp::%clojure-re-match unit s len pos groups
           (lambda (p2 g2)
             (if (= p2 pos)
                 (if (> min 0) nil (funcall k pos groups))
                 (or (rontolisp::%clojure-re-match-rep-greedy unit
                      (if (> min 0) (- min 1) 0) (if max (- max 1) nil) s len p2
                      g2 k) (if (> min 0) nil (funcall k pos groups))))))
          (if (> min 0) nil (funcall k pos groups)))))

(defun rontolisp::%clojure-re-match-first (unit s len pos groups)
  "UNIT's first success as (end . groups), or NIL: the capturing
   continuation never fails, so no alternative is ever retried."
  (rontolisp::%clojure-re-match unit s len pos groups
                                (lambda (end g) (cons end g))))

(defun rontolisp::%clojure-re-match-max-commit (unit min max s len pos groups)
  "UNIT consumed greedily (each iteration its first success, like the
   oracle's possessive lock): (end . groups), or NIL past an unsatisfied MIN."
  (if (and max (= max 0))
      (if (> min 0) nil (cons pos groups))
      (let ((once (rontolisp::%clojure-re-match-first unit s len pos groups)))
        (if (null once)
            (if (> min 0) nil (cons pos groups))
            (if (= (car once) pos)
                (if (> min 0) nil (cons pos groups))
                (rontolisp::%clojure-re-match-max-commit unit
                 (if (> min 0) (- min 1) 0) (if max (- max 1) nil) s len
                 (car once) (cdr once)))))))

(defun rontolisp::%clojure-re-match-rep-possessive
    (unit min max s len pos groups k)
  "Possessive repetition: the greedy consumption commits (no count is given
   back), then K runs once."
  (let ((res
         (rontolisp::%clojure-re-match-max-commit unit min max s len pos
                                                  groups)))
    (if (null res) nil (funcall k (car res) (cdr res)))))

(defun rontolisp::%clojure-re-match-rep-reluctant
    (unit min max s len pos groups k)
  "Reluctant repetition: settle first, iterate on failure."
  (or (if (> min 0) nil (funcall k pos groups))
      (and (or (null max) (> max 0))
           (rontolisp::%clojure-re-match unit s len pos groups
            (lambda (p2 g2)
              (if (= p2 pos)
                  nil
                  (rontolisp::%clojure-re-match-rep-reluctant unit
                   (if (> min 0) (- min 1) 0) (if max (- max 1) nil) s len p2 g2
                   k)))))))

(defun rontolisp::%clojure-re-find-from (ops s len from)
  "The leftmost match at or past FROM: (start end groups), or NIL."
  (do ((pos from (+ pos 1)) (found nil))
      ((or found (> pos len)) found)
    (let ((hit
           (rontolisp::%clojure-re-match ops s len pos nil
                                         (lambda (end g) (list pos end g)))))
      (if hit (setq found hit)))))

(defun rontolisp::%clojure-re-next (m)
  "The matcher's next match: (start end groups), or NIL. An empty match
   advances one character past itself, like the oracle."
  (let ((cell (rontolisp::%clojure-re-match-cell m))
        (input (rontolisp::%clojure-re-match-input m))
        (pat (rontolisp::%clojure-re-match-pat m)))
    (let ((len (length input)) (pos (car cell)) (last (cdr cell)))
      (if (> pos len)
          nil
          (let ((from
                 (if (and last (= (car last) (car (cdr last)))) (+ pos 1) pos)))
            (if (> from len)
                (progn
                  (rplaca cell (+ len 1))
                  (rplacd cell nil)
                  nil)
                (let ((found
                       (rontolisp::%clojure-re-find-from
                        (rontolisp::%clojure-re-pat-ops pat) input len from)))
                  (if (null found)
                      (progn
                        (rplaca cell (+ len 1))
                        (rplacd cell nil)
                        nil)
                      (progn
                        (rplaca cell (car (cdr found)))
                        (rplacd cell found)
                        found)))))))))

(defun rontolisp::%clojure-re-group-strings (s bindings n i)
  "The group strings 1..N over the BINDINGS alist (NIL past no match)."
  (if (> i n)
      nil
      (cons (let ((b (assoc i bindings)))
              (if (null b) nil (subseq s (car (cdr b)) (car (cdr (cdr b))))))
            (rontolisp::%clojure-re-group-strings s bindings n (+ i 1)))))

(defun rontolisp::%clojure-re-value (s found ngroups)
  "The match value for (start end groups) FOUND: the string without groups, a
   vector of the whole plus every group (NIL past no match) with them."
  (let ((start (car found))
        (end (car (cdr found)))
        (bindings (car (cdr (cdr found)))))
    (if (= ngroups 0)
        (subseq s start end)
        (coerce (cons (subseq s start end)
                 (rontolisp::%clojure-re-group-strings s bindings ngroups 1))
                'vector))))

(defun rontolisp::%clojure-re-compile (source)
  "The pattern value for the SOURCE string (parsed eagerly, like the oracle)."
  (if (not (stringp source))
      (error "re-pattern takes a pattern or a string")
      (let ((parsed (rontolisp::%clojure-re-parse source)))
        (list :C%PATTERN (gensym "re") source (car parsed)
              (car (cdr parsed))))))

(defun rontolisp::%clojure-re-pattern (x)
  "The pattern for X: itself for a pattern, compiled for a string."
  (rontolisp::%clojure-re-as-pattern
   (if (stringp x) (rontolisp::%clojure-re-compile x) x)
   "re-pattern takes a pattern or a string"))

(defun rontolisp::%clojure-re-matcher (pat s)
  "A matcher of the pattern PAT over the string S (a pattern only, like the
   oracle)."
  (let ((p
         (rontolisp::%clojure-re-as-pattern pat
          "re-matcher takes a pattern and a string")))
    (if (not (stringp s))
        (error "re-matcher takes a pattern and a string")
        (list :C%MATCHER (gensym "re") p s (cons 0 nil)))))

(defun rontolisp::%clojure-re-find (pat s)
  "The first match of PAT in S, or NIL."
  (let ((p
         (rontolisp::%clojure-re-as-pattern pat
          "re-find takes a matcher, or a pattern and a string")))
    (if (not (stringp s))
        (error "re-find takes a matcher, or a pattern and a string")
        (let ((m (list :C%MATCHER (gensym "re") p s (cons 0 nil))))
          (let ((found (rontolisp::%clojure-re-next m)))
            (if (null found)
                nil
                (rontolisp::%clojure-re-value s found
                 (rontolisp::%clojure-re-pat-ngroups p))))))))

(defun rontolisp::%clojure-re-find-m (m)
  "The matcher's next match, or NIL."
  (if (not (rontolisp::%clojure-re-matcher-p m))
      (error "re-find takes a matcher, or a pattern and a string")
      (let ((found (rontolisp::%clojure-re-next m)))
        (if (null found)
            nil
            (rontolisp::%clojure-re-value (rontolisp::%clojure-re-match-input m)
                                          found
                                          (rontolisp::%clojure-re-pat-ngroups
                                           (rontolisp::%clojure-re-match-pat
                                            m)))))))

(defun rontolisp::%clojure-re-seq (pat s)
  "Every match of PAT in S as a strict list (the oracle answers lazy, which
   prints the same)."
  (let ((p
         (rontolisp::%clojure-re-as-pattern pat
          "re-seq takes a pattern and a string")))
    (if (not (stringp s))
        (error "re-seq takes a pattern and a string")
        (let ((m (list :C%MATCHER (gensym "re") p s (cons 0 nil)))
              (ngroups (rontolisp::%clojure-re-pat-ngroups p)))
          (do ((found
                (rontolisp::%clojure-re-next m)
                (rontolisp::%clojure-re-next m))
               (acc nil))
              ((null found) (reverse acc))
            (setq acc
             (cons (rontolisp::%clojure-re-value s found ngroups) acc)))))))

(defun rontolisp::%clojure-re-matches (pat s)
  "The whole-string match of PAT against S, or NIL."
  (let ((p
         (rontolisp::%clojure-re-as-pattern pat
          "re-matches takes a pattern and a string")))
    (if (not (stringp s))
        (error "re-matches takes a pattern and a string")
        (let ((len (length s)))
          (let ((hit
                 (rontolisp::%clojure-re-match
                  (rontolisp::%clojure-re-pat-ops p) s len 0 nil
                  (lambda (end g) (if (= end len) (list 0 end g) nil)))))
            (if (null hit)
                nil
                (rontolisp::%clojure-re-value s hit
                 (rontolisp::%clojure-re-pat-ngroups p))))))))

(defun rontolisp::%clojure-re-groups (m)
  "The last match's groups as a vector (the whole first), or a signal past no
   match, like the oracle."
  (if (not (rontolisp::%clojure-re-matcher-p m))
      (error "re-groups takes a matcher")
      (let ((last (cdr (rontolisp::%clojure-re-match-cell m))))
        (if (null last)
            (error "No match found")
            (rontolisp::%clojure-re-value (rontolisp::%clojure-re-match-input m)
                                          last
                                          (rontolisp::%clojure-re-pat-ngroups
                                           (rontolisp::%clojure-re-match-pat
                                            m)))))))

(defun rontolisp::%clojure-re-fresh-matcher (p s)
  "A matcher of the pattern value P over S."
  (list :C%MATCHER (gensym "re") p s (cons 0 nil)))

(defun rontolisp::%clojure-re-drop-empty (xs)
  "XS past its leading empty strings (trailing empties drop reversed)."
  (if (and xs (stringp (car xs)) (string= (car xs) ""))
      (rontolisp::%clojure-re-drop-empty (cdr xs))
      xs))

(defun rontolisp::%clojure-re-split-loop (m s len lim index count acc)
  "The split parts, reversed: every match cuts, a positive LIM caps (the last
   part holding the rest), and a zero-width match at the start cuts nothing
   (the oracle skips the leading empty the same way)."
  (if (and (integerp lim) (> lim 0) (= count (- lim 1)))
      (reverse (cons (subseq s index len) acc))
      (let ((found (rontolisp::%clojure-re-next m)))
        (if (null found)
            (reverse (cons (subseq s index len) acc))
            (let ((fs (car found)) (fe (car (cdr found))))
              (if (and (= index 0) (= fs 0) (= fe 0))
                  (rontolisp::%clojure-re-split-loop m s len lim index count
                                                     acc)
                  (rontolisp::%clojure-re-split-loop m s len lim fe (+ count 1)
                   (cons (subseq s index fs) acc))))))))

(defun rontolisp::%clojure-re-split (pat s lim)
  "S cut around the pattern PAT as a strict list: an empty input answers one
   empty part, a positive LIM caps, any other limit keeps every part but the
   trailing empties (like the literal arm)."
  (let ((p
         (rontolisp::%clojure-re-as-pattern pat
          "split takes a string and a pattern")))
    (if (not (stringp s))
        (error "split takes a string and a pattern")
        (if (= (length s) 0)
            (list "")
            (let ((parts
                   (rontolisp::%clojure-re-split-loop
                    (rontolisp::%clojure-re-fresh-matcher p s) s (length s) lim
                    0 0 nil)))
              (if (and (integerp lim) (not (= lim 0)))
                  parts
                  (reverse
                   (rontolisp::%clojure-re-drop-empty (reverse parts)))))))))

(defun rontolisp::%clojure-re-group-string (nn start end bindings s)
  "The $N substitution: the whole for 0, the group or \"\" past no match."
  (if (= nn 0)
      (subseq s start end)
      (let ((b (assoc nn bindings)))
        (if (null b) "" (subseq s (car (cdr b)) (car (cdr (cdr b))))))))

(defun rontolisp::%clojure-re-interp-pieces
    (repl len i s start end bindings ngroups acc)
  "The replacement pieces over the reversed ACC."
  (if (>= i len)
      (reverse acc)
      (let ((c (char-code (char repl i))))
        (cond ((= c 92)
               (if (>= (+ i 1) len)
                   (rontolisp::%clojure-re-interp-pieces repl len (+ i 1) s
                                                         start end bindings
                                                         ngroups
                                                         (cons "\\" acc))
                   (rontolisp::%clojure-re-interp-pieces repl len (+ i 2) s
                    start end bindings ngroups
                    (cons (subseq repl (+ i 1) (+ i 2)) acc))))
              ((= c 36)
               (let ((r (rontolisp::%clojure-re-parse-digits repl len (+ i 1))))
                 (if (null r)
                     (error "Illegal group reference: group index is missing")
                     (let ((nn (car (cdr r))))
                       (if (> nn ngroups)
                           (error
                            (concatenate 'string "No group "
                             (rontolisp::%clojure-str-of nn "" nil)))
                           (rontolisp::%clojure-re-interp-pieces repl len
                            (car r) s start end bindings ngroups
                            (cons (rontolisp::%clojure-re-group-string nn start
                                                                       end
                                                                       bindings
                                                                       s)
                                  acc)))))))
              (t (rontolisp::%clojure-re-interp-pieces repl len (+ i 1) s start
                  end bindings ngroups (cons (subseq repl i (+ i 1)) acc)))))))

(defun rontolisp::%clojure-re-interpolate (repl s found ngroups)
  "The string REPL over (start end groups) FOUND: \\ quotes, $N the group."
  (let ((out (make-string-output-stream)))
    (let ((pieces
           (rontolisp::%clojure-re-interp-pieces repl (length repl) 0 s
                                                 (car found) (car (cdr found))
                                                 (car (cdr (cdr found))) ngroups
                                                 nil)))
      (do ((rest pieces (cdr rest)))
          ((null rest) (get-output-stream-string out))
        (write-string (car rest) out)))))

(defun rontolisp::%clojure-re-subst (rep s found ngroups)
  "The substitution for FOUND: a string interpolates, anything else applies
   through str (like the oracle's function arm, whose NIL is \"\")."
  (if (stringp rep)
      (rontolisp::%clojure-re-interpolate rep s found ngroups)
      (rontolisp::%clojure-str-of (rontolisp::%clojure-call rep
                                   (list
                                    (rontolisp::%clojure-re-value s found
                                                                  ngroups))) ""
                                  nil)))

(defun rontolisp::%clojure-re-replace (s pat rep once)
  "S with the pattern PAT swapped for REP: every match, or the first for ONCE.
   A string replacement interpolates $ groups (re-quote-replacement quotes
   them); anything else applies to the match through str."
  (let ((p
         (rontolisp::%clojure-re-as-pattern pat
          "replace takes a string, a match and a replacement")))
    (if (not (stringp s))
        (error "replace takes a string, a match and a replacement")
        (let ((m (rontolisp::%clojure-re-fresh-matcher p s))
              (len (length s))
              (ngroups (rontolisp::%clojure-re-pat-ngroups p)))
          (if once
              (let ((found (rontolisp::%clojure-re-next m)))
                (if (null found)
                    s
                    (let ((out (make-string-output-stream)))
                      (write-string (subseq s 0 (car found)) out)
                      (write-string
                       (rontolisp::%clojure-re-subst rep s found ngroups) out)
                      (write-string (subseq s (car (cdr found)) len) out)
                      (get-output-stream-string out))))
              (let ((out (make-string-output-stream)))
                (do ((found
                      (rontolisp::%clojure-re-next m)
                      (rontolisp::%clojure-re-next m))
                     (pos 0))
                    ((null found)
                     (write-string (subseq s pos len) out)
                     (get-output-stream-string out))
                  (write-string (subseq s pos (car found)) out)
                  (write-string
                   (rontolisp::%clojure-re-subst rep s found ngroups) out)
                  (setq pos (car (cdr found))))))))))

;;;; clojure.test (b55): the run-time half of deftest/is/are/testing and the
;;;; run-tests summary runner.
;;
;; The lowering keeps the shapes the oracle's macros expand to: a deftest is a
;; zero-argument function running its body through %clojure-test-var (which
;; counts the test and reports an uncaught error) and registered per namespace
;; in definition order; an is is a thunk run through %clojure-test-try (which
;; reports an error inside the assertion) around one of the assertion kinds --
;; a predicate call (the arguments evaluated first, so a failure shows
;; (not (f values...))), any other form (a failure shows its value), thrown?
;; and thrown-with-msg?. The report layout is the oracle's (clj 1.12.6.1673):
;; "FAIL in (names) (file:line)", the testing contexts, the message, then the
;; expected form and the actual value readably; run-tests prints "Testing ns"
;; per namespace and the "Ran N tests containing M assertions." summary, and
;; answers the {:test :pass :fail :error :type} map.
;;
;; Reports go to the stream *standard-output* was when the test runtime
;; started (%clojure-test-init, which every lowered program using clojure.test
;; runs first), the oracle's *test-out*: a with-out-str in a test never
;; captures a report. The counters exist only while run-tests runs, like the
;; oracle's *report-counters*, so an is outside it reports without counting.
;;
;; Deliberate non-goals, each a documented deviation (.kb/clojure-frontend.md):
;; tests run in definition order (the oracle's order is its namespace map's,
;; unspecified); thrown? and thrown-with-msg? catch every condition whatever the
;; class names (the catch-all try precedent); an error report prints the
;; condition's message and no stack trace, at the is form's position (the
;; oracle names the frame that threw); fixtures are refused by name.

(defvar rontolisp::%clojure-test-out
  nil
  "The stream test reports go to: *standard-output* when the runtime started.")

(defvar rontolisp::%clojure-test-ex-info
  nil
  "A function answering (message . data) for an ex-info condition, NIL for any
   other, so an error report spells ex-info the oracle's way.")

(defvar rontolisp::%clojure-test-registry
  nil
  "The tests per namespace, in definition order: ((ns (name . fn) ...) ...).")

(defvar rontolisp::%clojure-test-counters
  nil
  "While run-tests runs, #(tests passes failures errors); NIL otherwise.")

(defvar rontolisp::%clojure-test-names
  nil
  "The names of the tests running, innermost first.")

(defvar rontolisp::%clojure-test-contexts
  nil
  "The testing context strings in effect, innermost first.")

(defun rontolisp::%clojure-test-init (ex-info)
  "Start the test runtime: reports go to the stream *standard-output* is now,
   and EX-INFO answers (message . data) for an ex-info condition."
  (setq rontolisp::%clojure-test-out *standard-output*)
  (setq rontolisp::%clojure-test-ex-info ex-info)
  nil)

(defun rontolisp::%clojure-test-truthy-p (x)
  "Clojure truthiness: anything but nil and the false object."
  (not (or (null x) (eq x rontolisp::%clojure-false))))

(defun rontolisp::%clojure-test-count (index)
  "Bump one summary counter (0 tests, 1 passes, 2 failures, 3 errors) while
   run-tests runs; outside it nothing counts, like the oracle."
  (let ((counters rontolisp::%clojure-test-counters))
    (if counters (setf (aref counters index) (+ (aref counters index) 1)))
    nil))

(defun rontolisp::%clojure-test-write-joined (strings stream)
  "Write STRINGS to STREAM separated by single spaces."
  (let ((first t))
    (dolist (s strings)
      (if (not first) (write-char #\Space stream))
      (setq first nil)
      (write-string s stream))))

(defun rontolisp::%clojure-test-header (kind msg loc)
  "The head of one FAIL or ERROR report: the kind with the running test names
   and the position, then the testing contexts and the message when present."
  (let ((s rontolisp::%clojure-test-out))
    (terpri s)
    (write-string kind s)
    (write-string " in (" s)
    (rontolisp::%clojure-test-write-joined
     (reverse rontolisp::%clojure-test-names) s)
    (write-string ") " s)
    (write-string loc s)
    (terpri s)
    (if rontolisp::%clojure-test-contexts
        (progn
          (rontolisp::%clojure-test-write-joined
           (reverse rontolisp::%clojure-test-contexts) s)
          (terpri s)))
    (if (rontolisp::%clojure-test-truthy-p msg)
        (progn
          (write-string (rontolisp::%clojure-str-of msg "nil" nil) s)
          (terpri s)))))

(defun rontolisp::%clojure-test-expected-actual (expected actual)
  "The expected and actual lines of a report: the form readably, then the
   already-rendered ACTUAL string."
  (let ((s rontolisp::%clojure-test-out))
    (write-string "expected: " s)
    (write-string (rontolisp::%clojure-str-of expected "nil" t) s)
    (terpri s)
    (write-string "  actual: " s)
    (write-string actual s)
    (terpri s)))

(defun rontolisp::%clojure-test-fail (expected actual msg loc)
  "Report one failed assertion: ACTUAL is the rendered actual value."
  (rontolisp::%clojure-test-count 2)
  (rontolisp::%clojure-test-header "FAIL" msg loc)
  (rontolisp::%clojure-test-expected-actual expected actual))

(defun rontolisp::%clojure-test-ex-info-of (e)
  "(message . data) when E is an ex-info condition, NIL otherwise."
  (if rontolisp::%clojure-test-ex-info
      (funcall rontolisp::%clojure-test-ex-info e)
      nil))

(defun rontolisp::%clojure-test-describe (e)
  "The actual line of an error report: an ex-info condition the oracle's way
   (its class and message, then the data on a line of its own), anything else
   its report."
  (let ((info (rontolisp::%clojure-test-ex-info-of e)))
    (if info
        (concatenate 'string "clojure.lang.ExceptionInfo: "
                     (rontolisp::%clojure-str-of (car info) "nil" nil)
                     (string #\Newline)
                     (rontolisp::%clojure-str-of (cdr info) "nil" t))
        (format nil "~a" e))))

(defun rontolisp::%clojure-test-message (e)
  "The message a thrown-with-msg? pattern searches: an ex-info condition's own
   message, anything else its report."
  (let ((info (rontolisp::%clojure-test-ex-info-of e)))
    (if info
        (rontolisp::%clojure-str-of (car info) "" nil)
        (format nil "~a" e))))

(defun rontolisp::%clojure-test-error (expected e msg loc)
  "Report one error: the condition E escaped an assertion (or a test body)."
  (rontolisp::%clojure-test-count 3)
  (rontolisp::%clojure-test-header "ERROR" msg loc)
  (rontolisp::%clojure-test-expected-actual expected
   (rontolisp::%clojure-test-describe e)))

(defun rontolisp::%clojure-test-pass ()
  "Count one passed assertion."
  (rontolisp::%clojure-test-count 1))

(defun rontolisp::%clojure-test-try (thunk expected msg loc)
  "One is: run the assertion THUNK, reporting an error that escapes it as an
   ERROR (answering nil), like the oracle's try around every assertion."
  (handler-case (funcall thunk)
    (error (e)
      (rontolisp::%clojure-test-error expected e msg loc)
      nil)))

(defun rontolisp::%clojure-test-any (value expected msg loc)
  "An is over any form: VALUE passes when truthy; a failure shows it."
  (if (rontolisp::%clojure-test-truthy-p value)
      (rontolisp::%clojure-test-pass)
      (rontolisp::%clojure-test-fail expected
                                     (rontolisp::%clojure-str-of value "nil" t)
                                     msg loc))
  value)

(defun rontolisp::%clojure-test-pred (value call expected msg loc)
  "An is over a function call: VALUE passes when truthy; a failure shows
   CALL, the (not (f values...)) form over the evaluated arguments."
  (if (rontolisp::%clojure-test-truthy-p value)
      (rontolisp::%clojure-test-pass)
      (rontolisp::%clojure-test-fail expected
                                     (rontolisp::%clojure-str-of call "nil" t)
                                     msg loc))
  value)

(defun rontolisp::%clojure-test-caught (thunk)
  "The condition THUNK signals, or NIL when it returns."
  (handler-case (progn
                  (funcall thunk)
                  nil)
    (error (e) e)))

(defun rontolisp::%clojure-test-thrown (thunk expected msg loc)
  "(is (thrown? C body...)): passes answering the condition when the body
   signals; fails with actual nil when it returns."
  (let ((caught (rontolisp::%clojure-test-caught thunk)))
    (if caught
        (rontolisp::%clojure-test-pass)
        (rontolisp::%clojure-test-fail expected "nil" msg loc))
    caught))

(defun rontolisp::%clojure-test-thrown-msg (thunk re expected msg loc)
  "(is (thrown-with-msg? C re body...)): passes when the body signals and RE
   finds a match in the message; a mismatch fails showing the condition, a
   return fails with actual nil. Answers the condition, or nil."
  (let ((caught (rontolisp::%clojure-test-caught thunk)))
    (cond ((null caught) (rontolisp::%clojure-test-fail expected "nil" msg loc))
          ((rontolisp::%clojure-test-truthy-p
            (rontolisp::%clojure-re-find re
             (rontolisp::%clojure-test-message caught)))
           (rontolisp::%clojure-test-pass))
          (t (rontolisp::%clojure-test-fail expected
              (rontolisp::%clojure-test-describe caught) msg loc)))
    caught))

(defun rontolisp::%clojure-test-testing (context thunk)
  "(testing context body...): THUNK with CONTEXT (as str spells it) pushed on
   the contexts every report inside names."
  (let ((rontolisp::%clojure-test-contexts
         (cons (rontolisp::%clojure-str-of context "" nil)
               rontolisp::%clojure-test-contexts)))
    (funcall thunk)))

(defun rontolisp::%clojure-test-var (name body loc)
  "Run one test: count it, run BODY with NAME pushed on the running names, and
   report an error escaping the body as uncaught. Answers nil, like the oracle."
  (let ((rontolisp::%clojure-test-names
         (cons name rontolisp::%clojure-test-names)))
    (rontolisp::%clojure-test-count 0)
    (handler-case (funcall body)
      (error (e)
        (rontolisp::%clojure-test-error nil e
                                        "Uncaught exception, not in assertion."
                                        loc)))
    nil))

(defun rontolisp::%clojure-test-entry (ns)
  "The registry entry of the namespace NS, or NIL."
  (let ((found nil))
    (dolist (entry rontolisp::%clojure-test-registry)
      (if (and (null found) (equal (car entry) ns)) (setq found entry)))
    found))

(defun rontolisp::%clojure-test-register (ns name fn)
  "Record the test NAME of the namespace NS as FN: a redefinition replaces the
   old one in place, a new test goes last."
  (let ((entry (rontolisp::%clojure-test-entry ns)))
    (if (null entry)
        (setq rontolisp::%clojure-test-registry
              (append rontolisp::%clojure-test-registry
                      (list (list ns (cons name fn)))))
        (let ((test nil))
          (dolist (pair (cdr entry))
            (if (and (null test) (equal (car pair) name)) (setq test pair)))
          (if test
              (rplacd test fn)
              (rplacd entry (append (cdr entry) (list (cons name fn))))))))
  nil)

(defun rontolisp::%clojure-test-ns-name (x)
  "A namespace designator's name: a string itself, a symbol its spelling."
  (if (stringp x) x (rontolisp::%clojure-symbol-full-name x)))

(defun rontolisp::%clojure-test-summary-map (counters)
  "The {:test :pass :fail :error :type :summary} map of COUNTERS."
  (let ((m (make-hash-table :test 'equal)))
    (setf (gethash (list :C%KEYWORD "test") m) (aref counters 0))
    (setf (gethash (list :C%KEYWORD "pass") m) (aref counters 1))
    (setf (gethash (list :C%KEYWORD "fail") m) (aref counters 2))
    (setf (gethash (list :C%KEYWORD "error") m) (aref counters 3))
    (setf (gethash (list :C%KEYWORD "type") m) (list :C%KEYWORD "summary"))
    m))

(defun rontolisp::%clojure-test-summary (counters)
  "Print the oracle's two summary lines for COUNTERS."
  (let ((s rontolisp::%clojure-test-out))
    (terpri s)
    (write-string "Ran " s)
    (princ (aref counters 0) s)
    (write-string " tests containing " s)
    (princ (+ (aref counters 1) (aref counters 2) (aref counters 3)) s)
    (write-string " assertions." s)
    (terpri s)
    (princ (aref counters 2) s)
    (write-string " failures, " s)
    (princ (aref counters 3) s)
    (write-string " errors." s)
    (terpri s)))

(defun rontolisp::%clojure-test-known-p (name known)
  "Whether the namespace NAME exists: it defined a test, or the program named
   it (KNOWN, the names an ns or in-ns spelled before the call)."
  (or (rontolisp::%clojure-test-entry name)
      (let ((found nil))
        (dolist (k known) (if (equal k name) (setq found t)))
        found)))

(defun rontolisp::%clojure-test-run-tests (namespaces known)
  "Run every test of each namespace in NAMESPACES (names or symbols), print
   the oracle's summary and answer the summary map. A namespace that neither
   defined a test nor is in KNOWN signals before anything runs, like the
   oracle."
  (dolist (ns namespaces)
    (let ((name (rontolisp::%clojure-test-ns-name ns)))
      (if (not (rontolisp::%clojure-test-known-p name known))
          (error (concatenate 'string "No namespace: " name " found")))))
  (let ((rontolisp::%clojure-test-counters (vector 0 0 0 0)))
    (dolist (ns namespaces)
      (let ((name (rontolisp::%clojure-test-ns-name ns))
            (s rontolisp::%clojure-test-out))
        (terpri s)
        (write-string "Testing " s)
        (write-string name s)
        (terpri s)
        (let ((entry (rontolisp::%clojure-test-entry name)))
          (if entry (dolist (pair (cdr entry)) (funcall (cdr pair)))))))
    (rontolisp::%clojure-test-summary rontolisp::%clojure-test-counters)
    (rontolisp::%clojure-test-summary-map rontolisp::%clojure-test-counters)))

(defun rontolisp::%clojure-test-run-all (re known)
  "Run the tests of every namespace the program named (KNOWN, in order) or
   that defined a test, narrowed to the names RE matches when RE is given."
  (let ((all (reverse known)))
    (dolist (entry rontolisp::%clojure-test-registry)
      (let ((seen nil))
        (dolist (name all) (if (equal name (car entry)) (setq seen t)))
        (if (not seen) (setq all (cons (car entry) all)))))
    (let ((names nil))
      (dolist (name (reverse all))
        (if (or (null re)
                (rontolisp::%clojure-test-truthy-p
                 (rontolisp::%clojure-re-matches re name)))
            (setq names (cons name names))))
      (rontolisp::%clojure-test-run-tests (reverse names) known))))

(defun rontolisp::%clojure-test-successful (summary)
  "(successful? summary): true when it counts no failure and no error."
  (if (and (eql (rontolisp::%clojure-call-keyword (list :C%KEYWORD "fail")
                                                  summary 0) 0)
           (eql (rontolisp::%clojure-call-keyword (list :C%KEYWORD "error")
                                                  summary 0) 0))
      t
      rontolisp::%clojure-false))
