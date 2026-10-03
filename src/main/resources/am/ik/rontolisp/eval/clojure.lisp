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
;; absent; unreadable values (functions, conditions, host objects) print #<..>,
;; except a host class object, which prints its name like the oracle's.

(defun rontolisp::%clojure-keyword-p (x)
  "Whether X is the (:C%KEYWORD spelling) wrapper the lowering lowers keywords to."
  (and (consp x) (eq (car x) :C%KEYWORD) (consp (cdr x)) (stringp (car (cdr x)))
       (null (cdr (cdr x)))))

(defun rontolisp::%clojure-set-p (x)
  "Whether X is the (:C%SET table) wrapper the lowering lowers sets to."
  (and (consp x) (eq (car x) :C%SET) (consp (cdr x))
       (hash-table-p (car (cdr x))) (null (cdr (cdr x)))))

(defun rontolisp::%clojure-record-p (x)
  "Whether X is the (:C%RECORD tag fields table class) wrapper the lowering lowers
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
   %clojure-write realizes it where it stands, so the walk never forces one
   (an infinite seq then prints without end, like the oracle's, instead of
   hanging silently in the walk)."
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
        ((rontolisp::%clojure-lazy-p x)
         ;; realized as it prints, like the oracle: empty is (), anything
         ;; else a seq the cons arm writes, realizing each lazy tail it meets
         (let ((s (rontolisp::%clojure-realize x)))
           (if (null s)
               (write-string "()" stream)
               (rontolisp::%clojure-write s nil-replacement readable stream
                                          labels))))
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
        ((rontolisp::%clojure-var-p x)
         (write-string "#'" stream)
         (write-string (car (cdr x)) stream))
        ((and labels (rontolisp::%clojure-node-p x)
              (rontolisp::%clojure-write-label x labels stream)))
        ((rontolisp::%clojure-record-p x)
         (rontolisp::%clojure-write-record x nil-replacement readable stream
                                           labels))
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
         (do ((rest
               (rontolisp::%clojure-seq-rest x)
               (rontolisp::%clojure-seq-rest rest)))
             ((or (not (consp rest))
                  (and labels (rontolisp::%clojure-labeled-p rest labels)))
              (if (not (null rest))
                  (progn
                    (write-string " . " stream)
                    (rontolisp::%clojure-write rest nil-replacement readable
                                               stream labels))))
           (write-char #\Space stream)
           (rontolisp::%clojure-write (car rest) nil-replacement readable stream
                                      labels))
         (write-char #\) stream))
        ((functionp x) (write-string "#<procedure>" stream))
        (t (let ((name (rontolisp::%clojure-host-class-name x)))
             (if name (write-string name stream) (princ x stream))))))

(defun rontolisp::%clojure-write-record
    (x nil-replacement readable stream labels)
  "Write record X as its literal, #ns.Name{:k v, ...}, like the oracle: the
   declared fields first in declaration order, then the extension keys in the
   table's walk order."
  (let ((fields (car (cdr (cdr x))))
        (table (car (cdr (cdr (cdr x)))))
        (first t))
    (write-char #\# stream)
    (write-string (car (cdr (cdr (cdr (cdr x))))) stream)
    (write-char #\{ stream)
    (dolist (k fields)
      (if first (setq first nil) (write-string ", " stream))
      (rontolisp::%clojure-write k nil-replacement readable stream labels)
      (write-char #\Space stream)
      (rontolisp::%clojure-write (gethash k table) nil-replacement readable
                                 stream labels))
    (maphash (lambda (k v)
               (let ((declared nil))
                 (dolist (f fields) (if (equal f k) (setq declared t)))
                 (if (not declared)
                     (progn
                       (if first (setq first nil) (write-string ", " stream))
                       (rontolisp::%clojure-write k nil-replacement readable
                                                  stream labels)
                       (write-char #\Space stream)
                       (rontolisp::%clojure-write v nil-replacement readable
                                                  stream labels))))) table)
    (write-char #\} stream)))

(defun rontolisp::%clojure-print (x nil-replacement readable stream)
  "Write X in Clojure notation to STREAM, with datum labels when it may cycle."
  (rontolisp::%clojure-write x nil-replacement readable stream
                             (if (and (rontolisp::%clojure-node-p x)
                                      (rontolisp::%clojure-may-cycle-p x 1000))
                                 (rontolisp::%clojure-cycle-labels x)))
  nil)

(defun rontolisp::%clojure-str-of (x nil-replacement readable)
  "X's Clojure-notation string: the str/pr-str building block. READABLE is the
   pr side (pr-str, the REPL echo): strings quoted, characters with their
   backslash, NIL as NIL-REPLACEMENT. Otherwise it is str, the oracle's
   toString: NIL is NIL-REPLACEMENT (\"\" for str), a string itself, a
   character its glyph, a pattern its source, and anything else its readable
   spelling -- a collection quotes the strings inside it and spells nil, so
   spit writes what read reads back. A string OUTPUT stream answers the text
   so far WITHOUT clearing it under str (a zero-argument java.io.StringWriter
   lowers to one, so binding *out* to it and reading it back runs on every
   backend); readably it prints as the stream it is, like the oracle's
   #object. A host object answers its toString, so a class object answers
   \"class java.lang.String\"."
  (cond ((null readable)
         (cond ((null x) nil-replacement)
               ((stringp x) x)
               ((characterp x) (string x))
               ((and (typep x 'string-stream) (output-stream-p x))
                (let ((text (get-output-stream-string x)))
                  (write-string text x)
                  text))
               ((rontolisp::%clojure-re-pattern-p x)
                (rontolisp::%clojure-re-pat-source x))
               (t (or (rontolisp::%clojure-host-string x)
                      (rontolisp::%clojure-str-of x "nil" t)))))
        ((rontolisp::%clojure-re-pattern-p x)
         (concatenate 'string "#\"" (rontolisp::%clojure-re-pat-source x) "\""))
        (t (let ((stream (make-string-output-stream)))
             (rontolisp::%clojure-print x nil-replacement readable stream)
             (get-output-stream-string stream)))))

(defun rontolisp::%clojure-write-datum (x nil-replacement readable)
  "Write X in Clojure notation to *standard-output*: the println/print/pr/prn
   building block. Answers NIL, so a print call's value is nil like the oracle."
  (rontolisp::%clojure-print x nil-replacement readable *standard-output*)
  nil)

(defun rontolisp::%clojure-host-class (x)
  "class of a value of no Clojure kind: a host object's class (the oracle's
   answer), anything else the refusal. java:call refuses every value that is no
   host object, so its refusal IS the host test, the same one on the interpreter
   and the JVM. eval/ClojureLibrary splices a refusal-only body instead into a
   program with no java: operator, where no host object can exist: a java:
   reference changes the JVM output and is a call-time error on wasm."
  (handler-case (java:call x "getClass")
    (error () (error "class needs a value of a known kind"))))

(defun rontolisp::%clojure-lisp-value-p (x)
  "Whether X is a value of a Lisp kind, so no host object: the cheap test the
   printer's host arms ask before a java:call refusal could (numbers above all
   reach the printer's fall-through)."
  (or (numberp x) (characterp x) (symbolp x) (consp x) (arrayp x)
      (hash-table-p x) (functionp x) (streamp x)))

(defun rontolisp::%clojure-host-class-name (x)
  "X's name when X is a host class object, which the oracle prints by its name
   (java.lang.String, long), else NIL. A host arm like %clojure-host-class: a
   program with no java: operator gets a body answering NIL."
  (if (not (rontolisp::%clojure-lisp-value-p x))
      (handler-case (if (equal (java:call (java:call x "getClass") "getName")
                               "java.lang.Class")
                        (java:call x "getName"))
        (error () nil))))

(defun rontolisp::%clojure-host-string (x)
  "X's toString when X is a host object, str's answer (a class object's is
   \"class java.lang.String\"), else NIL. The host test is the getClass
   refusal; toString runs outside it, so its own exception propagates like the
   oracle's. A host arm like %clojure-host-class: a program with no java:
   operator gets a body answering NIL."
  (if (and (not (rontolisp::%clojure-lisp-value-p x))
           (handler-case (progn
                           (java:call x "getClass")
                           t)
             (error () nil)))
      (java:call x "toString")))

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
  "Two tables with the same count whose every entry agrees under =, a key
   found by = (a structural key through the representative B holds)."
  (and (eql (hash-table-count a) (hash-table-count b))
       (let ((ok t) (miss (list nil)))
         (maphash (lambda (k v)
                    (let ((w
                           (gethash (rontolisp::%clojure-table-key k b) b
                                    miss)))
                      (if (or (eq w miss) (not (rontolisp::%clojure-equal v w)))
                          (setq ok nil)))) a)
         ok)))

(defun rontolisp::%clojure-equal (a b)
  "Clojure = over two values, T or NIL: two sets by membership, two records by
   tag plus entries, a deftype or reify by identity, two maps entry by entry,
   two sequentials element by element, two floats numerically, anything else
   with equal (numbers keep their category, strings and characters compare by
   value)."
  (cond ((and (vectorp a) (vectorp b) (not (stringp a)) (not (stringp b)))
         ;; two vectors read in place, without the seq view's copies
         (and (eql (length a) (length b))
              (let ((same t))
                (dotimes (i (length a))
                  (if (and same
                       (not (rontolisp::%clojure-equal (aref a i) (aref b i))))
                      (setq same nil)))
                same)))
        ((and (rontolisp::%clojure-set-p a) (rontolisp::%clojure-set-p b))
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
        ((and (floatp a) (floatp b))
         ;; numeric, like Numbers.equiv: the zeros are equal, NaN is not
         (= a b))
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

;;;; Structural keys: a map, set or memo table finds a key by =.
;;
;; The tables are equal tables, whose equal is identity on a vector or a table,
;; so [1 1] built twice would key two entries. The runtime keeps no custom-test
;; table on any backend, so every structural key (a non-string vector, a list,
;; a lazy seq, a map, a set, a record) is stored under a REPRESENTATIVE: the
;; first key = to it of its own kind (vector, list, lazy seq, other) the program
;; stored. %clojure-key-classes groups the representatives = to each other into
;; a class -- one per kind, so (assoc {} '(1 2) :b) keeps a list key even after
;; [1 2] keyed another table -- bucketed under %clojure-hash, which agrees with
;; %clojure-equal. A lookup reads the representative its table holds; a store
;; reuses it, so an equal key of another kind replaces the value under the key
;; already there, like the oracle. Every table verb goes through
;; %clojure-table-key (lookups) or %clojure-store-key (stores); a lowered site
;; whose key is a literal scalar skips both. A copy of one table needs neither:
;; its keys are representatives already.
;;
;; Deliberate non-goal (.kb/clojure-frontend.md, "Deviations"): the classes keep
;; one representative per distinct stored value and kind for the program's
;; lifetime, like the metadata side table.

(defvar rontolisp::%clojure-key-classes
  nil
  "The structural-key classes: an equal table from a %clojure-hash to the list
   of classes under it, each class the list of its representatives (the first
   one created first), NIL until the first structural key is stored.")

(defvar rontolisp::%clojure-key-reps
  nil
  "Every representative's class: an eq table, so a key that already is one (a
   key read out of a table, merged into another) finds its class without
   hashing. Being an eq table, it also gives a wasm module the identity-hash
   slot, so a vector or map key of an equal table is placed by identity instead
   of sharing one bucket (.kb/hash-tables.md).")

(defun rontolisp::%clojure-structural-key-p (k)
  "Whether K is a key whose = an equal table does not decide: a non-string
   vector, a map, a set, a record, a lazy seq or a list (every list, since a
   list = a vector of equal members). Keywords and the other tagged wrappers
   compare by equal already."
  (cond ((consp k)
         (let ((h (car k)))
           (cond ((eq h :C%KEYWORD) nil)
            ((keywordp h) (or (eq h :C%SET) (eq h :C%LAZY) (eq h :C%RECORD)))
            (t t))))
        ((vectorp k) (not (stringp k)))
        (t (hash-table-p k))))

(defun rontolisp::%clojure-hash-string (s)
  "A hash of the string S's characters, below 2^20."
  (let ((h 7))
    (dotimes (i (length s))
      (setq h (logand (+ (* h 1021) (char-code (char s i))) 1048575)))
    h))

(defun rontolisp::%clojure-hash-entries (table members)
  "The order-free hash of TABLE: the sum of its keys' hashes for a set's
   table (MEMBERS true), of its entries' for a map's."
  (let ((h (if members 3 5)))
    (maphash (lambda (k v)
               (setq h
                     (logand (+ h
                                (if members
                                    (rontolisp::%clojure-hash k)
                                    (logand (+ (* 1021
                                                  (rontolisp::%clojure-hash k))
                                               (rontolisp::%clojure-hash v))
                                            1048575))) 1048575))) table)
    h))

(defun rontolisp::%clojure-hash (x)
  "A hash of X below 2^20 that agrees with %clojure-equal: = values hash
   alike, so a sequential hashes its members in order whatever its kind, a map
   or set its entries in any order, and an identity-compared value one
   constant. A fold step multiplies by 1021 under a 2^20 mask, so every
   intermediate stays below 2^30, a wasm fixnum, and the members of a small
   integer pair [x y] (x, y < 1021) never collide."
  (cond ((null x) 1)
        ((integerp x) (logand x 1048575))
        ((stringp x) (rontolisp::%clojure-hash-string x))
        ((vectorp x)
         ;; the sequential fold below, read in place
         (let ((h 1))
           (dotimes (i (length x))
             (setq h
                   (logand (+ (* h 1021) (rontolisp::%clojure-hash (aref x i)))
                           1048575)))
           h))
        ((consp x)
         (cond ((rontolisp::%clojure-keyword-p x)
                (logand (+ 11 (rontolisp::%clojure-hash-string (car (cdr x))))
                        1048575))
               ((rontolisp::%clojure-set-p x)
                (rontolisp::%clojure-hash-entries (car (cdr x)) t))
               ((rontolisp::%clojure-record-p x)
                (logand (+ (rontolisp::%clojure-hash (car (cdr x)))
                           (rontolisp::%clojure-hash-entries
                            (car (cdr (cdr (cdr x)))) nil)) 1048575))
               ((rontolisp::%clojure-sequential-p x)
                (let ((h 1) (s (rontolisp::%clojure-seq x)))
                  (do ()
                      ((null s) h)
                    (setq h
                          (logand
                           (+ (* h 1021) (rontolisp::%clojure-hash (car s)))
                           1048575))
                    (setq s (rontolisp::%clojure-seq (cdr s))))))
               (t 0)))
        ((characterp x) (char-code x))
        ((symbolp x) (rontolisp::%clojure-hash-string (symbol-name x)))
        ((floatp x)
         (if (and (< x 1.0e9) (> x -1.0e9))
             (logand (truncate (* x 1024)) 1048575)
             2))
        ((numberp x) 4)
        ((hash-table-p x) (rontolisp::%clojure-hash-entries x nil))
        (t 0)))

(defun rontolisp::%clojure-key-kind (k)
  "The kind a representative keeps for structural K: 0 a vector, 1 a lazy
   seq, 2 a list, 3 anything else (= already tells maps, sets and records
   apart)."
  (cond ((vectorp k) 0)
        ((rontolisp::%clojure-lazy-p k) 1)
        ((consp k) (if (keywordp (car k)) 3 2))
        (t 3)))

(defun rontolisp::%clojure-key-class (k create)
  "The class of the structural key K: the representatives = to it. NIL when
   there is none and CREATE is false; a fresh class holding K when CREATE is
   true."
  (unless rontolisp::%clojure-key-classes
    (setq rontolisp::%clojure-key-classes (make-hash-table :test 'equal))
    (setq rontolisp::%clojure-key-reps (make-hash-table :test 'eq)))
  (or (gethash k rontolisp::%clojure-key-reps)
      (let* ((h (rontolisp::%clojure-hash k))
             (bucket (gethash h rontolisp::%clojure-key-classes))
             (found nil))
        (do ((b bucket (cdr b)))
            ((or found (null b)))
          (if (rontolisp::%clojure-equal (car (car b)) k) (setq found (car b))))
        (if (and (null found) create)
            (progn
              (setq found (list k))
              (setf (gethash h rontolisp::%clojure-key-classes)
                    (cons found bucket))
              (setf (gethash k rontolisp::%clojure-key-reps) found)))
        found)))

(defun rontolisp::%clojure-held-key (class table)
  "The representative in CLASS that TABLE holds as a key, or NIL."
  (let ((miss (list nil)) (held nil))
    (do ((c class (cdr c)))
        ((or held (null c)) held)
      (if (not (eq (gethash (car c) table miss) miss)) (setq held (car c))))))

(defun rontolisp::%clojure-zero-key (k table)
  "The float zero TABLE holds when K is the other float zero (= to K, though
   equal tells them apart), else K."
  (let ((other (if (eql k 0.0) (- 0.0) 0.0)) (miss (list nil)))
    (if (and (eq (gethash k table miss) miss)
             (not (eq (gethash other table miss) miss)))
        other
        k)))

(defun rontolisp::%clojure-table-key (k table)
  "The key TABLE holds K under: when K is structural, the representative = to
   it that TABLE holds; the float zero TABLE holds when K is the other one;
   otherwise (or when TABLE holds none) K itself, which then misses like any
   absent key."
  (cond ((rontolisp::%clojure-structural-key-p k)
         (or (rontolisp::%clojure-held-key
              (rontolisp::%clojure-key-class k nil) table) k))
        ((and (floatp k) (= k 0.0)) (rontolisp::%clojure-zero-key k table))
        (t k)))

(defun rontolisp::%clojure-store-key (k table)
  "The key to store K under in TABLE: when K is structural, the representative
   = to it that TABLE already holds (its value is replaced, its key kept, like
   the oracle), else the representative of K's own kind, else K, which becomes
   that representative; a float zero is the one TABLE holds (the other zero),
   any other K is itself."
  (if (not (rontolisp::%clojure-structural-key-p k))
      (if (and (floatp k) (= k 0.0)) (rontolisp::%clojure-zero-key k table) k)
      (let* ((class (rontolisp::%clojure-key-class k t))
             (held (rontolisp::%clojure-held-key class table)))
        (if held
            held
            (let ((kind (rontolisp::%clojure-key-kind k)) (own nil))
              (do ((c class (cdr c)))
                  ((or own (null c)))
                (if (eql (rontolisp::%clojure-key-kind (car c)) kind)
                    (setq own (car c))))
              (if own
                  own
                  (progn
                    (rplacd class (cons k (cdr class)))
                    (setf (gethash k rontolisp::%clojure-key-reps) class)
                    k)))))))

(defun rontolisp::%clojure-set-put (table x)
  "X added to the set table TABLE as a member stored under itself (an = member
   already there stays); answers the stored member."
  (let ((k (rontolisp::%clojure-store-key x table)))
    (setf (gethash k table) k)))

(defun rontolisp::%clojure-plist-table (base plist)
  "A fresh map: BASE's entries (a table copied as is, or nil) plus PLIST's
   alternating keys and values left to right, each key stored through
   %clojure-store-key, later pairs winning."
  (let ((out
         (if base
             (rontolisp:plist-hash-table (rontolisp:hash-table-plist base)
                                         :test 'equal)
             (make-hash-table :test 'equal))))
    (do ((p plist (cdr (cdr p))))
        ((null p) out)
      (setf (gethash (rontolisp::%clojure-store-key (car p) out) out)
            (car (cdr p))))))

(defun rontolisp::%clojure-memo-key (args)
  "The argument list ARGS as a memoize table key: ARGS itself unless an
   argument is structural, else a fresh list with each structural argument
   replaced by its class's first representative, so = argument lists are equal
   lists."
  (let ((structural nil))
    (dolist (x args)
      (if (rontolisp::%clojure-structural-key-p x) (setq structural t)))
    (if structural
        (mapcar (lambda (x)
                  (if (rontolisp::%clojure-structural-key-p x)
                      (car (rontolisp::%clojure-key-class x t))
                      x)) args)
        args)))

(defun rontolisp::%clojure-call (f args)
  "Apply F to the argument list ARGS: real functions through apply, collection
   values through their lookup, like the oracle's IFn. Sets answer the member,
   maps the value, vectors the indexed element, keywords the table-aware read --
   each with the next argument as the default (nil without one). Strings are no
   functions, like the oracle, and anything else signals."
  (cond ((functionp f) (apply f args))
        ((rontolisp::%clojure-set-p f)
         (gethash (rontolisp::%clojure-table-key (car args) (car (cdr f)))
                  (car (cdr f)) (if (cdr args) (car (cdr args)) nil)))
        ((hash-table-p f)
         (gethash (rontolisp::%clojure-table-key (car args) f) f
                  (if (cdr args) (car (cdr args)) nil)))
        ((rontolisp::%clojure-record-p f)
         (gethash
          (rontolisp::%clojure-table-key (car args) (car (cdr (cdr (cdr f)))))
          (car (cdr (cdr (cdr f)))) (if (cdr args) (car (cdr args)) nil)))
        ((and (vectorp f) (not (stringp f)))
         (let ((i (car args)))
           (if (and (integerp i) (<= 0 i) (< i (length f)))
               (elt f i)
               (if (cdr args) (car (cdr args)) nil))))
        ((rontolisp::%clojure-keyword-p f)
         (rontolisp::%clojure-call-keyword f (car args)
          (if (cdr args) (car (cdr args)) nil)))
        ((rontolisp::%clojure-var-p f)
         (rontolisp::%clojure-call (rontolisp::%clojure-var-get f) args))
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
;; A realized seq's tail may be another wrapper, so a consumer walking the list
;; with CL list operations takes %clojure-seq-all (every tail realized), and one
;; that stops early steps with %clojure-seq-rest.
;;
;; Deliberate non-goals, each a documented deviation (.kb/clojure-frontend.md):
;; no chunking (every element realizes singly), no parallel realization, infinite
;; range stays refused.

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
   A thunk answering another wrapper chains through it in a loop
   (%clojure-realize-chain); anything else seqs strictly (a cons passes
   through, so a lazy tail stays lazy). The cell reads as realized and empty
   from the moment its thunk answers until the seq is known, so a throw
   further down the chain or in the seq leaves nil behind, as the oracle's
   LazySeq does."
  (let ((cell (car (cdr x))))
    (if (car cell)
        (let ((v (funcall (car cell))))
          (rplaca cell nil)
          (let ((s
                 (if (rontolisp::%clojure-lazy-p v)
                     (rontolisp::%clojure-realize-chain v)
                     (rontolisp::%clojure-strict-seq v))))
            (rplacd cell s)
            s))
        (cdr cell))))

(defun rontolisp::%clojure-realize-chain (v)
  "The seq at the end of the chain of wrappers from V, each forced in turn in
   a loop like the oracle's LazySeq.seq, so a chain of any length (a lazy-seq
   body answering another lazy-seq per skipped element) runs in constant
   stack; every cell it forces memoizes the final seq. A cell reads as
   realized and empty while the chain runs, so a thunk answering its own
   wrapper answers nil, like the oracle. Only a thunk's answer is seqed: a
   realized cell already holds a seq."
  (let ((more nil) (s nil) (done nil))
    (do ()
        (done (do ((p more (cdr p)))
                  ((null p) s)
                (rplacd (car p) s)))
      (let ((c (car (cdr v))))
        (if (car c)
            (let ((w (funcall (car c))))
              (rplaca c nil)
              (setq more (cons c more))
              (if (rontolisp::%clojure-lazy-p w)
                  (setq v w)
                  (progn
                    (setq s (rontolisp::%clojure-strict-seq w))
                    (setq done t))))
            (progn
              (setq s (cdr c))
              (setq done t)))))))

(defun rontolisp::%clojure-seq (coll)
  "The lazy-aware seq view: one-level realize for wrappers, the strict view
   otherwise. Never walks past one wrapper, so infinite seqs stay infinite."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-realize coll)
      (rontolisp::%clojure-strict-seq coll)))

(defun rontolisp::%clojure-seq-all (coll)
  "The whole-collection view: the seq of COLL with every lazy tail realized.
   The strict view itself when its spine holds no wrapper (no copy), a fresh
   list otherwise. An infinite input never answers, like the oracle's
   whole-collection consumers (count, last, sort, apply ...)."
  (let ((s (rontolisp::%clojure-seq coll)))
    (do ((p s (cdr p)))
        ((or (not (consp p)) (rontolisp::%clojure-lazy-p (cdr p)))
         (if (consp p) (rontolisp::%clojure-realize-all s) s)))))

(defun rontolisp::%clojure-nth (coll i dflt)
  "The member of COLL at index I, or DFLT past either end: a vector or string
   indexes directly, anything else steps through its seq one realized level at
   a time, so an infinite input still answers."
  (cond ((< i 0) dflt)
        ((vectorp coll) (if (< i (length coll)) (aref coll i) dflt))
        (t (let ((s (rontolisp::%clojure-seq coll)) (left i))
             (do ()
                 ((or (null s) (<= left 0)) (if (null s) dflt (car s)))
               (setq s (rontolisp::%clojure-seq-rest s))
               (setq left (- left 1)))))))

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
   (stopping at the shortest, like the oracle), the strict mapcar over the
   whole-collection views otherwise (a seq holding a lazy tail realizes)."
  (if (rontolisp::%clojure-any-lazy-p colls)
      (rontolisp::%clojure-map-lazy f colls)
      (apply #'mapcar (lambda (&rest xs) (rontolisp::%clojure-call f xs))
             (mapcar #'rontolisp::%clojure-seq-all colls))))

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
  "Filter COLL through PRED: a wrapper when COLL is lazy, remove-if-not over
   the whole-collection view else."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-filter-lazy pred coll)
      (remove-if-not (lambda (x) (rontolisp::%clojure-filter-test pred x))
                     (rontolisp::%clojure-seq-all coll))))

(defun rontolisp::%clojure-filter-lazy (pred coll)
  "The lazy arm of %clojure-filter. A run of dropped members is a loop inside
   one realization, never one nested realization per member."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((s (rontolisp::%clojure-seq coll)))
       (do ()
           ((or (null s) (rontolisp::%clojure-filter-test pred (car s)))
            (if (null s)
                nil
                (cons (car s) (rontolisp::%clojure-filter-lazy pred (cdr s)))))
         (setq s (rontolisp::%clojure-seq-rest s)))))))

(defun rontolisp::%clojure-concat (colls)
  "Append the COLLS list: a wrapper when any member is lazy, strict append
   of the whole-collection views otherwise (of none, nil)."
  (if (rontolisp::%clojure-any-lazy-p colls)
      (rontolisp::%clojure-concat-lazy colls)
      (apply #'append (mapcar #'rontolisp::%clojure-seq-all colls))))

(defun rontolisp::%clojure-concat-lazy (colls)
  "The lazy arm of %clojure-concat over the COLLS list."
  (rontolisp::%clojure-make-lazy
   (lambda () (rontolisp::%clojure-concat-step colls))))

(defun rontolisp::%clojure-concat-step (colls)
  "The first surviving head of the COLLS list over its lazy tail, or nil. The
   last member answers its own seq, like the oracle's concat: re-wrapping it
   would stack one more layer per member reached, so a concat whose last
   member is again a concat (a for over several levels) walked each element
   through every earlier layer. A run of empty members is a loop."
  (let ((cs colls) (s nil))
    (do ()
        ((or (null (cdr cs))
             (progn
               (setq s (rontolisp::%clojure-seq (car cs)))
               s))
         (if (null (cdr cs))
             (rontolisp::%clojure-seq (car cs))
             (cons (car s)
                   (rontolisp::%clojure-concat-lazy (cons (cdr s) (cdr cs))))))
      (setq cs (cdr cs)))))

;; remove, keep, keep-indexed, map-indexed, distinct, interpose, partition and
;; interleave follow the lazy-or-strict rule like map and filter: a lazy input
;; answers a wrapper, so (take n (verb ... infinite)) answers; a strict input
;; keeps a strict loop over the whole-collection view. A realized cons of a lazy
;; arm holds a wrapper as its tail (never a strict cons holding one), and a run
;; of dropped members is a loop inside one realization. A function argument is
;; a real function: the lowering wraps a collection or keyword value in the IFn
;; dispatcher at the call site, so a program calling these with a function
;; never carries the dispatcher.

(defun rontolisp::%clojure-keep-lazy (f coll i)
  "The lazy arm of the dropping verbs: (F index member) over COLL's members
   from index I, every answer but :C%SKIP kept (a CL keyword no Clojure value
   is)."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((s (rontolisp::%clojure-seq coll)) (n i) (v nil))
       (do ()
           ((or (null s)
                (progn
                  (setq v (funcall f n (car s)))
                  (not (eq v :C%SKIP))))
            (if (null s)
                nil
                (cons v (rontolisp::%clojure-keep-lazy f (cdr s) (+ n 1)))))
         (setq s (rontolisp::%clojure-seq-rest s))
         (setq n (+ n 1)))))))

(defun rontolisp::%clojure-remove (pred coll)
  "(remove pred coll): the members PRED answers nil or false for."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-keep-lazy (lambda (i x)
                                       (declare (ignore i))
                                       (if (rontolisp::%clojure-truthy
                                            (funcall pred x))
                                           :C%SKIP x)) coll 0)
      (remove-if (lambda (x) (rontolisp::%clojure-truthy (funcall pred x)))
                 (rontolisp::%clojure-seq-all coll))))

(defun rontolisp::%clojure-keep (f coll)
  "(keep f coll): F's non-nil answers (false is kept, a signalling F
   signals)."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-keep-lazy (lambda (i x)
                                       (declare (ignore i))
                                       (let ((v (funcall f x)))
                                         (if (null v) :C%SKIP v))) coll 0)
      (let ((acc nil))
        (dolist (x (rontolisp::%clojure-seq-all coll) (reverse acc))
          (let ((v (funcall f x))) (if v (setq acc (cons v acc))))))))

(defun rontolisp::%clojure-indexed (f coll keep)
  "(keep-indexed f coll) when KEEP, (map-indexed f coll) otherwise: F over the
   index from 0 and each member, a nil answer dropped when KEEP."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-keep-lazy (if keep
                                         (lambda (i x)
                                           (let ((v (funcall f i x)))
                                             (if (null v) :C%SKIP v)))
                                         f) coll 0)
      (let ((acc nil) (i 0))
        (dolist (x (rontolisp::%clojure-seq-all coll) (reverse acc))
          (let ((v (funcall f i x)))
            (if (or v (not keep)) (setq acc (cons v acc))))
          (setq i (+ i 1))))))

(defun rontolisp::%clojure-distinct (coll)
  "(distinct coll): first occurrences in order, by = membership through the
   structural-key runtime, like a set's. A lazy cell realizes at most once, so
   the seen table grows in member order."
  (let ((seen (make-hash-table :test 'equal)))
    (if (rontolisp::%clojure-lazy-p coll)
        (rontolisp::%clojure-keep-lazy (lambda (i x)
                                         (declare (ignore i))
                                         (if (rontolisp::%clojure-distinct-new-p
                                              x seen)
                                             x
                                             :C%SKIP)) coll 0)
        (let ((acc nil))
          (dolist (x (rontolisp::%clojure-seq-all coll) (reverse acc))
            (if (rontolisp::%clojure-distinct-new-p x seen)
                (setq acc (cons x acc))))))))

(defun rontolisp::%clojure-distinct-new-p (x seen)
  "Whether X is = to no key of the table SEEN, recording it when new."
  (let ((k (rontolisp::%clojure-store-key x seen)))
    (if (gethash k seen)
        nil
        (progn
          (setf (gethash k seen) t)
          t))))

(defun rontolisp::%clojure-interpose (sep coll)
  "(interpose sep coll): SEP between every two members, so one member never
   shows it."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-interpose-lazy sep coll nil)
      (let ((acc nil))
        (dolist (x (rontolisp::%clojure-seq-all coll) (reverse acc))
          (if acc (setq acc (cons sep acc)))
          (setq acc (cons x acc))))))

(defun rontolisp::%clojure-interpose-lazy (sep coll gap)
  "The lazy arm of %clojure-interpose: SEP first when GAP and COLL still has a
   member."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((s (rontolisp::%clojure-seq coll)))
       (cond ((null s) nil)
             (gap (cons sep (rontolisp::%clojure-interpose-lazy sep s nil)))
             (t (cons (car s)
                      (rontolisp::%clojure-interpose-lazy sep (cdr s) t))))))))

(defun rontolisp::%clojure-partition (n step coll)
  "(partition n step coll): runs of N every STEP members, an incomplete tail
   dropped, like the oracle. A non-positive size signals."
  (cond ((<= n 0) (error "partition takes a positive size"))
        ((rontolisp::%clojure-lazy-p coll)
         (rontolisp::%clojure-partition-lazy n step coll))
        (t (let ((s (rontolisp::%clojure-seq-all coll)) (acc nil) (part nil))
             (do ()
                 ((progn
                    (setq part (rontolisp::%clojure-take n s))
                    (< (length part) n))
                  (reverse acc))
               (setq acc (cons part acc))
               (setq s (nthcdr step s)))))))

(defun rontolisp::%clojure-partition-lazy (n step coll)
  "The lazy arm of %clojure-partition (each run strict)."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let* ((s (rontolisp::%clojure-seq coll))
            (part (rontolisp::%clojure-take n s)))
       (if (< (length part) n)
           nil
           (cons part
                 (rontolisp::%clojure-partition-lazy n step
                  (rontolisp::%clojure-drop step s))))))))

(defun rontolisp::%clojure-partition-v (&rest args)
  "partition as a value: [n coll] or [n step coll]."
  (if (= (rontolisp::%clojure-check-arity args 2 3 "partition") 2)
      (rontolisp::%clojure-partition (car args) (car args) (car (cdr args)))
      (rontolisp::%clojure-partition (car args) (car (cdr args))
                                     (car (cdr (cdr args))))))

(defun rontolisp::%clojure-interleave (colls)
  "(interleave c1 c2 ...) over the COLLS list: each one's first member, then
   each one's second ..., stopping at the shortest (of none, nil)."
  (cond ((null colls) nil)
        ((rontolisp::%clojure-any-lazy-p colls)
         (rontolisp::%clojure-interleave-lazy colls))
        (t (let ((seqs (mapcar #'rontolisp::%clojure-seq colls)) (acc nil))
             (do ()
                 ((rontolisp::%clojure-map-done-p seqs) (reverse acc))
               (dolist (s seqs) (setq acc (cons (car s) acc)))
               (setq seqs (mapcar #'rontolisp::%clojure-seq-rest seqs)))))))

(defun rontolisp::%clojure-interleave-lazy (colls)
  "The lazy arm of %clojure-interleave: one round per realization."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((seqs (mapcar #'rontolisp::%clojure-seq colls)))
       (if (rontolisp::%clojure-map-done-p seqs)
           nil
           (rontolisp::%clojure-interleave-round
            (rontolisp::%clojure-map-heads seqs)
            (rontolisp::%clojure-map-tails seqs)))))))

(defun rontolisp::%clojure-interleave-round (heads tails)
  "The HEADS list one wrapper apart, then the next round over the TAILS list."
  (cons (car heads)
        (if (cdr heads)
            (rontolisp::%clojure-make-lazy
             (lambda ()
               (rontolisp::%clojure-interleave-round (cdr heads) tails)))
            (rontolisp::%clojure-interleave-lazy tails))))

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
  "FULL (the realized, non-empty seq) cycled from CUR, one wrapper per element:
   CUR realizes one level when its element is wanted, and an exhausted CUR
   starts over at FULL, whose tails stay memoized."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((c (rontolisp::%clojure-seq cur)))
       (if (null c) (setq c full))
       (cons (car c) (rontolisp::%clojure-cycle-from full (cdr c)))))))

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

(defun rontolisp::%clojure-dorun (coll)
  "(dorun coll): the seq of COLL walked to its end, realizing a lazy one member
   by member; answers nil."
  (do ((s (rontolisp::%clojure-seq coll) (rontolisp::%clojure-seq-rest s)))
      ((null s) nil)))

(defun rontolisp::%clojure-dorun-n (n coll)
  "(dorun n coll): the oracle's walk -- step to the next while the seq is not
   empty and N stays positive -- so up to N+1 members realize; answers nil."
  (let ((s (rontolisp::%clojure-seq coll)) (left n))
    (do ()
        ((or (null s) (<= left 0)) nil)
      (setq s (rontolisp::%clojure-seq-rest s))
      (setq left (- left 1)))))

(defun rontolisp::%clojure-doall (coll)
  "(doall coll): COLL realized like dorun, answered itself (never coerced)."
  (rontolisp::%clojure-dorun coll)
  coll)

(defun rontolisp::%clojure-doall-n (n coll)
  "(doall n coll): COLL realized like (dorun n coll), answered itself."
  (rontolisp::%clojure-dorun-n n coll)
  coll)

;;;; for: the oracle's comprehension over per-level step closures.
;;
;; The lowering compiles each binding level to a step closure over one element:
;; it binds the pattern, runs the level's modifiers in order and answers
;; :C%FOR-SKIP (a :when failed), :C%FOR-STOP (a :while failed, so the level
;; ends), the body's value (the innermost level) or (coll . step) for the next
;; level (any other level). The lazy-or-strict rule of map/filter, per
;; collection met: while every collection a level steps over is strict, the
;; answer realizes at once through nested loops (no per-element wrapper); from
;; the first lazy one on -- the first collection included -- the rest is a lazy
;; seq, so first/take realize only what they answer and an infinite level ends
;; behind them.

(defun rontolisp::%clojure-for (coll step depth)
  "(for ...) over the first collection COLL, the outermost STEP closure and the
   DEPTH (the number of binding levels): a lazy seq when COLL is lazy, else the
   realized strict list, or that list's prefix concatenated before the lazy
   rest when a later level meets a lazy collection."
  (if (rontolisp::%clojure-lazy-p coll)
      (rontolisp::%clojure-for-lazy coll step depth)
      (let ((box (list nil)))
        (let ((rest (rontolisp::%clojure-for-walk coll step depth box)))
          (if rest
              (rontolisp::%clojure-concat (cons (reverse (car box)) rest))
              (reverse (car box)))))))

(defun rontolisp::%clojure-for-walk (coll step depth box)
  "The level over COLL walked eagerly, each result pushed onto (car BOX); a
   :while stop ends the level before the next element realizes. Answers nil
   when the level ran to its end, or the lazy rest as a list of seqs once a
   level below meets a lazy collection: that level's seq, then the rest of
   every level above it, innermost first."
  (let ((s (rontolisp::%clojure-seq coll)) (done nil) (rest nil))
    (do ()
        ((or done (null s)) rest)
      (let ((r (funcall step (car s))))
        (cond ((eq r :C%FOR-SKIP) nil)
              ((eq r :C%FOR-STOP) (setq done t))
              ((= depth 1) (rplaca box (cons r (car box))))
              ((rontolisp::%clojure-lazy-p (car r))
               (setq rest
                     (list
                      (rontolisp::%clojure-for-lazy (car r) (cdr r) (- depth 1))
                      (rontolisp::%clojure-for-lazy (cdr s) step depth)))
               (setq done t))
              (t
               (let ((below
                      (rontolisp::%clojure-for-walk (car r) (cdr r) (- depth 1)
                                                    box)))
                 (if below
                     (progn
                       (setq rest
                             (append below
                                     (list
                                      (rontolisp::%clojure-for-lazy (cdr s) step
                                                                    depth))))
                       (setq done t)))))))
      (if (not done) (setq s (rontolisp::%clojure-seq-rest s))))))

(defun rontolisp::%clojure-for-lazy (coll step depth)
  "The level over COLL as a lazy seq, like the oracle's
   (fn iter [s] (lazy-seq (loop [s s] ...))): realizing it steps COLL to the
   next element the level keeps."
  (rontolisp::%clojure-make-lazy
   (lambda () (rontolisp::%clojure-for-next coll step depth))))

(defun rontolisp::%clojure-for-next (coll step depth)
  "One realization of a lazy for level: the next kept element's result consed
   onto the level's rest (the innermost level), the next non-empty inner level
   concatenated before the rest (any other level), or nil at the end or a
   :while stop. A run of skipped elements or empty inner levels is a loop,
   never a deeper stack."
  (let ((s (rontolisp::%clojure-seq coll)) (out nil) (done nil))
    (do ()
        ((or done (null s)) out)
      (let ((r (funcall step (car s))))
        (cond ((eq r :C%FOR-SKIP) (setq s (rontolisp::%clojure-seq-rest s)))
              ((eq r :C%FOR-STOP) (setq done t))
              ((= depth 1)
               (setq out
                     (cons r (rontolisp::%clojure-for-lazy (cdr s) step depth)))
               (setq done t))
              (t (let ((fs
                        (rontolisp::%clojure-seq
                         (rontolisp::%clojure-for-lazy (car r) (cdr r)
                                                       (- depth 1)))))
                   (if (null fs)
                       (setq s (rontolisp::%clojure-seq-rest s))
                       (progn
                         (setq out
                               (rontolisp::%clojure-concat
                                (list fs
                                      (rontolisp::%clojure-for-lazy (cdr s) step
                                                                    depth))))
                         (setq done t))))))))))

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

;;;; The core backlog (b57).
;;
;; The backlog verbs follow the lazy rows above: a lazy input answers a lazy
;; wrapper, a strict one a strict list (nil, never ()); dedupe and partition-by
;; compare with %clojure-equal. Each verb has a fixed-parameter worker the call
;; lowering calls after its own arity check and a -v entry the value lowering
;; names, which checks the count at run time with the oracle's wording. pmap is map (single-threaded, no entry of its own).

(defun rontolisp::%clojure-arity-error (n name)
  "Signal the oracle's arity error for the core fn NAME called with N args."
  (error "Wrong number of args (~D) passed to: clojure.core/~A" n name))

(defun rontolisp::%clojure-check-arity (args min max name)
  "The count of ARGS, or the oracle's arity error when it falls outside MIN..MAX
   (a nil MAX has no upper bound)."
  (let ((n (length args)))
    (if (or (< n min) (and max (> n max)))
        (rontolisp::%clojure-arity-error n name)
        n)))

(defun rontolisp::%clojure-truthy (v)
  "Whether V is truthy the Clojure way: neither nil nor the false object."
  (not (or (null v) (eq v rontolisp::%clojure-false))))

(defun rontolisp::%clojure-lazy-or-strict (coll lazy)
  "LAZY (a wrapper) when COLL is lazy, LAZY realized to a strict list otherwise."
  (if (rontolisp::%clojure-lazy-p coll)
      lazy
      (rontolisp::%clojure-realize-all lazy)))

(defun rontolisp::%clojure-drop-last (n coll)
  "COLL without its last N members (the oracle's map over COLL and its drop)."
  (rontolisp::%clojure-map (lambda (x y)
                             (declare (ignore y))
                             x) (list coll (rontolisp::%clojure-drop n coll))))

(defun rontolisp::%clojure-drop-last-v (&rest args)
  "drop-last as a value: [coll] or [n coll]."
  (if (= (rontolisp::%clojure-check-arity args 1 2 "drop-last") 1)
      (rontolisp::%clojure-drop-last 1 (car args))
      (rontolisp::%clojure-drop-last (car args) (car (cdr args)))))

(defun rontolisp::%clojure-split-at (n coll)
  "[(take n coll) (drop n coll)]."
  (vector (rontolisp::%clojure-take n coll) (rontolisp::%clojure-drop n coll)))

(defun rontolisp::%clojure-split-at-v (&rest args)
  "split-at as a value."
  (rontolisp::%clojure-check-arity args 2 2 "split-at")
  (rontolisp::%clojure-split-at (car args) (car (cdr args))))

(defun rontolisp::%clojure-split-with (pred coll)
  "[(take-while pred coll) (drop-while pred coll)] in one walk."
  (let ((s (rontolisp::%clojure-seq coll)) (acc nil))
    (do ()
        ((or (null s) (not (rontolisp::%clojure-filter-test pred (car s))))
         (vector (reverse acc) s))
      (setq acc (cons (car s) acc))
      (setq s (rontolisp::%clojure-seq (cdr s))))))

(defun rontolisp::%clojure-split-with-v (&rest args)
  "split-with as a value."
  (rontolisp::%clojure-check-arity args 2 2 "split-with")
  (rontolisp::%clojure-split-with (car args) (car (cdr args))))

(defun rontolisp::%clojure-take-last (n coll)
  "The last N members of COLL as a strict list (nil when none), walking a
   lead N ahead like the oracle."
  (let ((s (rontolisp::%clojure-seq coll))
        (lead (rontolisp::%clojure-seq (rontolisp::%clojure-drop n coll))))
    (do ()
        ((null lead) (rontolisp::%clojure-realize-all s))
      (setq s (rontolisp::%clojure-seq (cdr s)))
      (setq lead (rontolisp::%clojure-seq (cdr lead))))))

(defun rontolisp::%clojure-take-last-v (&rest args)
  "take-last as a value."
  (rontolisp::%clojure-check-arity args 2 2 "take-last")
  (rontolisp::%clojure-take-last (car args) (car (cdr args))))

(defun rontolisp::%clojure-nthnext (coll n)
  "The seq of COLL past its first N members, nil when nothing is left."
  (let ((s (rontolisp::%clojure-seq coll)) (left n))
    (do ()
        ((or (null s) (not (> left 0))) s)
      (setq s (rontolisp::%clojure-seq (cdr s)))
      (setq left (- left 1)))))

(defun rontolisp::%clojure-nthnext-v (&rest args)
  "nthnext as a value."
  (rontolisp::%clojure-check-arity args 2 2 "nthnext")
  (rontolisp::%clojure-nthnext (car args) (car (cdr args))))

(defun rontolisp::%clojure-nthrest (coll n)
  "COLL itself for a non-positive N, else the rest past its first N members
   (nil once it runs out, the oracle's ())."
  (let ((xs coll) (left n) (done nil))
    (do ()
        (done xs)
      (if (> left 0)
          (let ((s (rontolisp::%clojure-seq xs)))
            (if (null s)
                (progn
                  (setq xs nil)
                  (setq done t))
                (progn
                  (setq xs (cdr s))
                  (setq left (- left 1)))))
          (setq done t)))))

(defun rontolisp::%clojure-nthrest-v (&rest args)
  "nthrest as a value."
  (rontolisp::%clojure-check-arity args 2 2 "nthrest")
  (rontolisp::%clojure-nthrest (car args) (car (cdr args))))

(defun rontolisp::%clojure-stack-p (x)
  "Whether X is a stack for peek/pop: a non-string vector or a plain list (a
   cons headed by no wrapper tag; every wrapper starts with a keyword)."
  (or (and (vectorp x) (not (stringp x)))
      (and (consp x) (not (keywordp (car x))))))

(defun rontolisp::%clojure-peek (coll)
  "A vector's last member, a list's first, nil of nil or an empty vector."
  (cond ((null coll) nil)
        ((not (rontolisp::%clojure-stack-p coll))
         (error "peek needs a vector or a list"))
        ((consp coll) (car coll))
        ((= (length coll) 0) nil)
        (t (aref coll (- (length coll) 1)))))

(defun rontolisp::%clojure-peek-v (&rest args)
  "peek as a value."
  (rontolisp::%clojure-check-arity args 1 1 "peek")
  (rontolisp::%clojure-peek (car args)))

(defun rontolisp::%clojure-pop (coll)
  "A vector without its last member, a list without its first; nil of nil."
  (cond ((null coll) nil)
        ((not (rontolisp::%clojure-stack-p coll))
         (error "pop needs a vector or a list"))
        ((consp coll) (cdr coll))
        ((= (length coll) 0) (error "Can't pop empty vector"))
        (t (subseq coll 0 (- (length coll) 1)))))

(defun rontolisp::%clojure-pop-v (&rest args)
  "pop as a value."
  (rontolisp::%clojure-check-arity args 1 1 "pop")
  (rontolisp::%clojure-pop (car args)))

(defun rontolisp::%clojure-not-empty (coll)
  "COLL itself when it has a member, else nil."
  (if (rontolisp::%clojure-seq coll) coll nil))

(defun rontolisp::%clojure-not-empty-v (&rest args)
  "not-empty as a value."
  (rontolisp::%clojure-check-arity args 1 1 "not-empty")
  (rontolisp::%clojure-not-empty (car args)))

(defun rontolisp::%clojure-dedupe (coll)
  "COLL without consecutive = duplicates."
  (rontolisp::%clojure-lazy-or-strict coll
   (rontolisp::%clojure-dedupe-lazy coll nil nil)))

(defun rontolisp::%clojure-dedupe-lazy (coll have prev)
  "The lazy arm of %clojure-dedupe: PREV is the last kept member when HAVE."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((s (rontolisp::%clojure-seq coll)))
       (do ()
           ((or (null s) (not have)
                (not (rontolisp::%clojure-equal prev (car s))))
            (if (null s)
                nil
                (cons (car s)
                      (rontolisp::%clojure-dedupe-lazy (cdr s) t (car s)))))
         (setq s (rontolisp::%clojure-seq (cdr s))))))))

(defun rontolisp::%clojure-dedupe-v (&rest args)
  "dedupe as a value: [] the transducer, [coll] the seq."
  (if (null args)
      (rontolisp::%clojure-xf-dedupe)
      (progn
        (rontolisp::%clojure-check-arity args 1 1 "dedupe")
        (rontolisp::%clojure-dedupe (car args)))))

(defun rontolisp::%clojure-partition-all (n step coll)
  "COLL in runs of N every STEP members, the short tail kept. A non-positive
   size or step signals (the oracle answers an endless seq of ())."
  (if (and (> n 0) (> step 0))
      (rontolisp::%clojure-lazy-or-strict coll
       (rontolisp::%clojure-partition-all-lazy n step coll))
      (error "partition-all needs a positive size and step")))

(defun rontolisp::%clojure-partition-all-lazy (n step coll)
  "The lazy arm of %clojure-partition-all."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((s (rontolisp::%clojure-seq coll)))
       (if (null s)
           nil
           (cons (rontolisp::%clojure-take n s)
                 (rontolisp::%clojure-partition-all-lazy n step
                  (rontolisp::%clojure-drop step s))))))))

(defun rontolisp::%clojure-partition-all-v (&rest args)
  "partition-all as a value: [n] the transducer, [n coll] or [n step coll]."
  (let ((n (rontolisp::%clojure-check-arity args 1 3 "partition-all")))
    (cond ((= n 1) (rontolisp::%clojure-xf-partition-all (car args)))
          ((= n 2)
           (rontolisp::%clojure-partition-all (car args) (car args)
                                              (car (cdr args))))
          (t (rontolisp::%clojure-partition-all (car args) (car (cdr args))
                                                (car (cdr (cdr args))))))))

(defun rontolisp::%clojure-partition-by (f coll)
  "COLL split into runs where (f member) stays =."
  (rontolisp::%clojure-lazy-or-strict coll
   (rontolisp::%clojure-partition-by-lazy f coll)))

(defun rontolisp::%clojure-partition-by-lazy (f coll)
  "The lazy arm of %clojure-partition-by: each run is strict."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((s (rontolisp::%clojure-seq coll)))
       (if (null s)
           nil
           (let ((v (rontolisp::%clojure-call f (list (car s))))
                 (run (list (car s)))
                 (more (rontolisp::%clojure-seq (cdr s))))
             (do ()
                 ((or (null more)
                      (not
                       (rontolisp::%clojure-equal v
                        (rontolisp::%clojure-call f (list (car more))))))
                  (cons (reverse run)
                        (rontolisp::%clojure-partition-by-lazy f more)))
               (setq run (cons (car more) run))
               (setq more (rontolisp::%clojure-seq (cdr more))))))))))

(defun rontolisp::%clojure-partition-by-v (&rest args)
  "partition-by as a value: [f] the transducer, [f coll] the seq."
  (if (= (rontolisp::%clojure-check-arity args 1 2 "partition-by") 1)
      (rontolisp::%clojure-xf-partition-by (car args))
      (rontolisp::%clojure-partition-by (car args) (car (cdr args)))))

(defun rontolisp::%clojure-extreme-key (k x more greatest)
  "The member of X and the list MORE whose (k member) is the greatest (GREATEST
   true) or the least, the last of equals winning, like the oracle: K runs once
   per member, and not at all for X alone."
  (if (null more)
      x
      (let ((v x) (kv (rontolisp::%clojure-call k (list x))))
        (dolist (w more v)
          (let ((kw (rontolisp::%clojure-call k (list w))))
            (if (if greatest (>= kw kv) (<= kw kv))
                (progn
                  (setq v w)
                  (setq kv kw))))))))

(defun rontolisp::%clojure-max-key-v (&rest args)
  "max-key as a value."
  (rontolisp::%clojure-check-arity args 2 nil "max-key")
  (rontolisp::%clojure-extreme-key (car args) (car (cdr args)) (cdr (cdr args))
                                   t))

(defun rontolisp::%clojure-min-key-v (&rest args)
  "min-key as a value."
  (rontolisp::%clojure-check-arity args 2 nil "min-key")
  (rontolisp::%clojure-extreme-key (car args) (car (cdr args)) (cdr (cdr args))
                                   nil))

(defun rontolisp::%clojure-juxt (fns)
  "A function answering the vector of every member of FNS applied to its
   arguments."
  (lambda (&rest args)
    (coerce (mapcar (lambda (f) (rontolisp::%clojure-call f args)) fns)
            'vector)))

(defun rontolisp::%clojure-juxt-v (&rest fns)
  "juxt as a value."
  (rontolisp::%clojure-check-arity fns 1 nil "juxt")
  (rontolisp::%clojure-juxt fns))

(defun rontolisp::%clojure-fnil-patch (args defaults)
  "ARGS with each leading nil replaced by its member of DEFAULTS."
  (if (null defaults)
      args
      (cons (if (null (car args)) (car defaults) (car args))
            (rontolisp::%clojure-fnil-patch (cdr args) (cdr defaults)))))

(defun rontolisp::%clojure-fnil (f defaults)
  "F behind nil-patching of its leading arguments: at least as many arguments
   as DEFAULTS, like the oracle's arities."
  (lambda (&rest args)
    (if (< (length args) (length defaults))
        (error "Wrong number of args (~D) passed to: clojure.core/fnil/fn"
               (length args))
        (rontolisp::%clojure-call f
         (rontolisp::%clojure-fnil-patch args defaults)))))

(defun rontolisp::%clojure-fnil-v (&rest args)
  "fnil as a value: a function and one to three defaults."
  (rontolisp::%clojure-check-arity args 2 4 "fnil")
  (rontolisp::%clojure-fnil (car args) (cdr args)))

(defun rontolisp::%clojure-every-pred (preds)
  "A predicate answering T when every member of PREDS holds for every argument
   (of none, T), else the false object."
  (lambda (&rest args)
    (let ((ok t))
      (dolist (p preds)
        (dolist (x args)
          (if (and ok (not (rontolisp::%clojure-filter-test p x)))
              (setq ok nil))))
      (if ok t rontolisp::%clojure-false))))

(defun rontolisp::%clojure-every-pred-v (&rest preds)
  "every-pred as a value."
  (rontolisp::%clojure-check-arity preds 1 nil "every-pred")
  (rontolisp::%clojure-every-pred preds))

(defun rontolisp::%clojure-some-arg-major (preds args)
  "The first truthy (p x) over ARGS, every member of PREDS per argument, else the
   last (p x) tried (nil when none)."
  (let ((last nil) (found nil))
    (dolist (x args)
      (dolist (p preds)
        (if (not found)
            (progn
              (setq last (rontolisp::%clojure-call p (list x)))
              (if (rontolisp::%clojure-truthy last) (setq found t))))))
    last))

(defun rontolisp::%clojure-some-pred-major (preds args)
  "The first truthy (p x) over PREDS, every member of ARGS per predicate, else
   nil."
  (let ((found nil))
    (dolist (p preds)
      (dolist (x args)
        (if (not found)
            (let ((v (rontolisp::%clojure-call p (list x))))
              (if (rontolisp::%clojure-truthy v) (setq found v))))))
    found))

(defun rontolisp::%clojure-some-fn-apply (preds args)
  "The oracle's some-fn answer: one or two predicates walk the first three
   arguments argument-major (failing with the last (p x)), three or more walk
   them predicate-major (failing with nil); the arguments past three follow the
   same order and fail with nil."
  (let ((head nil) (tail args) (i 0))
    (do ()
        ((or (null tail) (= i 3)))
      (setq head (cons (car tail) head))
      (setq tail (cdr tail))
      (setq i (+ i 1)))
    (setq head (reverse head))
    (if (cdr (cdr preds))
        (let ((v (rontolisp::%clojure-some-pred-major preds head)))
          (if v v (rontolisp::%clojure-some-pred-major preds tail)))
        (let ((v (rontolisp::%clojure-some-arg-major preds head)))
          (cond ((rontolisp::%clojure-truthy v) v)
                ((null tail) v)
                (t (let ((w (rontolisp::%clojure-some-arg-major preds tail)))
                     (if (rontolisp::%clojure-truthy w) w nil))))))))

(defun rontolisp::%clojure-some-fn (preds)
  "A function answering the first truthy (p x) over PREDS and its arguments."
  (lambda (&rest args) (rontolisp::%clojure-some-fn-apply preds args)))

(defun rontolisp::%clojure-some-fn-v (&rest preds)
  "some-fn as a value."
  (rontolisp::%clojure-check-arity preds 1 nil "some-fn")
  (rontolisp::%clojure-some-fn preds))

(defun rontolisp::%clojure-kv-pairs (coll name)
  "The (key . value) pairs NAME walks: a map's or record's entries in the
   table's walk order, a vector's (index . member) pairs, none of nil; anything
   else signals."
  (cond ((null coll) nil)
        ((or (hash-table-p coll) (rontolisp::%clojure-record-p coll))
         (let ((acc nil))
           (maphash (lambda (k v) (setq acc (cons (cons k v) acc)))
                    (if (hash-table-p coll) coll (car (cdr (cdr (cdr coll))))))
           (reverse acc)))
        ((and (vectorp coll) (not (stringp coll)))
         (let ((acc nil))
           (dotimes (i (length coll))
             (setq acc (cons (cons i (aref coll i)) acc)))
           (reverse acc)))
        (t (error "~A needs a map or a vector" name))))

(defun rontolisp::%clojure-reduce-kv (f init coll)
  "(f acc k v) folded over COLL's pairs from INIT, stopping at a reduced
   answer (unwrapped)."
  (let ((acc init) (pairs (rontolisp::%clojure-kv-pairs coll "reduce-kv")))
    (do ()
        ((null pairs) acc)
      (setq acc
            (rontolisp::%clojure-call f
             (list acc (car (car pairs)) (cdr (car pairs)))))
      (if (rontolisp::%clojure-reduced-p acc)
          (progn
            (setq acc (car (cdr acc)))
            (setq pairs nil))
          (setq pairs (cdr pairs))))))

(defun rontolisp::%clojure-reduce-kv-v (&rest args)
  "reduce-kv as a value."
  (rontolisp::%clojure-check-arity args 3 3 "reduce-kv")
  (rontolisp::%clojure-reduce-kv (car args) (car (cdr args))
                                 (car (cdr (cdr args)))))

(defun rontolisp::%clojure-update-keys (m f)
  "A fresh map of M's entries under (f key) (a colliding key keeps one entry)."
  (let ((out (make-hash-table :test 'equal)))
    (dolist (kv (rontolisp::%clojure-kv-pairs m "update-keys") out)
      (setf (gethash (rontolisp::%clojure-store-key
                      (rontolisp::%clojure-call f (list (car kv))) out) out)
            (cdr kv)))))

(defun rontolisp::%clojure-update-keys-v (&rest args)
  "update-keys as a value."
  (rontolisp::%clojure-check-arity args 2 2 "update-keys")
  (rontolisp::%clojure-update-keys (car args) (car (cdr args))))

(defun rontolisp::%clojure-update-vals (m f)
  "M with (f value) for every value: a vector stays a vector, a map or record
   answers a fresh map, nil the empty map."
  (if (and (vectorp m) (not (stringp m)))
      (coerce (mapcar (lambda (x) (rontolisp::%clojure-call f (list x)))
                      (coerce m 'list)) 'vector)
      (let ((out (make-hash-table :test 'equal)))
        (dolist (kv (rontolisp::%clojure-kv-pairs m "update-vals") out)
          (setf (gethash (car kv) out)
                (rontolisp::%clojure-call f (list (cdr kv))))))))

(defun rontolisp::%clojure-update-vals-v (&rest args)
  "update-vals as a value."
  (rontolisp::%clojure-check-arity args 2 2 "update-vals")
  (rontolisp::%clojure-update-vals (car args) (car (cdr args))))

;;;; clojure.set: the relational set library over the set wrapper.
;;
;; The oracle's own algorithms (clojure/set.clj), so an answer's kind follows the
;; same input as there: union grows its largest input, intersection shrinks its
;; smallest, difference and select shrink the first. A set grows or shrinks in a
;; fresh copy, nil stays nil, and an input nothing changes is answered itself. A
;; relation is a set of maps; a member may be a record (read through its entry
;; table), and merge / rename-keys keep the record where the oracle keeps it.
;; Each var has a worker the call lowering (ClojureSetLowering) calls after its own
;; arity check -- the variadic ones over a list of their sets -- and a -v entry the
;; value lowering names, which checks the count at run time in the oracle's
;; wording.

(defun rontolisp::%clojure-set-arity (args min max name)
  "The count of ARGS, or the oracle's arity error for clojure.set/NAME when it
   falls outside MIN..MAX (a nil MAX has no upper bound)."
  (let ((n (length args)))
    (if (or (< n min) (and max (> n max)))
        (error "Wrong number of args (~D) passed to: clojure.set/~A" n name)
        n)))

(defun rontolisp::%clojure-set-count (coll)
  "count as the set algorithms read it: a set, map or record by its entries,
   nil none, a vector or string by length, a seq by its realized length."
  (cond ((null coll) 0)
        ((rontolisp::%clojure-set-p coll) (hash-table-count (car (cdr coll))))
        ((rontolisp::%clojure-record-p coll)
         (hash-table-count (car (cdr (cdr (cdr coll))))))
        ((hash-table-p coll) (hash-table-count coll))
        ((vectorp coll) (length coll))
        (t (length (rontolisp::%clojure-seq-all coll)))))

(defun rontolisp::%clojure-set-has (coll x)
  "contains? as the set algorithms read it: a set by member, a map or record by
   key (found by =), a vector or string by index; nil holds nothing, anything
   else signals."
  (let ((miss (list nil)))
    (cond ((null coll) nil)
          ((rontolisp::%clojure-set-p coll)
           (let ((table (car (cdr coll))))
             (not
              (eq (gethash (rontolisp::%clojure-table-key x table) table miss)
                  miss))))
          ((rontolisp::%clojure-record-p coll)
           (rontolisp::%clojure-set-has (car (cdr (cdr (cdr coll)))) x))
          ((hash-table-p coll)
           (not
            (eq (gethash (rontolisp::%clojure-table-key x coll) coll miss)
                miss)))
          ((vectorp coll) (and (integerp x) (<= 0 x) (< x (length coll))))
          (t (error "contains? not supported on this collection")))))

(defun rontolisp::%clojure-set-of (members)
  "A fresh set of the list MEMBERS."
  (let ((table (make-hash-table :test 'equal)))
    (dolist (x members) (rontolisp::%clojure-set-put table x))
    (list :C%SET table)))

(defun rontolisp::%clojure-set-copy (s)
  "A fresh set wrapper holding the members of the set S (already
   representatives, so stored as they are)."
  (let ((table (make-hash-table :test 'equal)))
    (maphash (lambda (k v)
               (declare (ignore v))
               (setf (gethash k table) k)) (car (cdr s)))
    (list :C%SET table)))

(defun rontolisp::%clojure-set-grow (base items)
  "(reduce conj BASE ITEMS) for the list ITEMS: BASE itself when ITEMS is
   empty; else a set gains each in a fresh copy, a vector each at its end, nil
   or a seq each at its front; anything else signals."
  (cond ((null items) base)
        ((rontolisp::%clojure-set-p base)
         (let ((out (rontolisp::%clojure-set-copy base)))
           (dolist (x items out)
             (rontolisp::%clojure-set-put (car (cdr out)) x))))
        ((and (vectorp base) (not (stringp base)))
         (coerce (append (coerce base 'list) items) 'vector))
        ((rontolisp::%clojure-sequential-p base)
         (let ((out (rontolisp::%clojure-seq-all base)))
           (dolist (x items out) (setq out (cons x out)))))
        (t (error "clojure.set needs sets"))))

(defun rontolisp::%clojure-set-shrink (base drops)
  "(reduce disj BASE DROPS) for the list DROPS: BASE itself when DROPS is empty
   or BASE is nil; else a set loses each in a fresh copy; anything else
   signals."
  (cond ((or (null drops) (null base)) base)
        ((rontolisp::%clojure-set-p base)
         (let ((out (rontolisp::%clojure-set-copy base)))
           (dolist (x drops out)
             (remhash (rontolisp::%clojure-table-key x (car (cdr out)))
                      (car (cdr out))))))
        (t (error "disj needs a set"))))

(defun rontolisp::%clojure-set-bubble (sets largest)
  "The oracle's bubble-max-key over the list SETS by count (LARGEST true) or by
   its negation: the last extreme set first, then the others without any copy
   identical to it."
  (let ((best (car sets))
        (best-n (rontolisp::%clojure-set-count (car sets)))
        (rest nil))
    (dolist (s (cdr sets))
      (let ((n (rontolisp::%clojure-set-count s)))
        (if (if largest (>= n best-n) (<= n best-n))
            (progn
              (setq best s)
              (setq best-n n)))))
    (dolist (s sets) (if (not (eq s best)) (setq rest (cons s rest))))
    (cons best (reverse rest))))

(defun rontolisp::%clojure-set-union (sets)
  "clojure.set/union over the list SETS: of none the empty set, of one itself,
   of two the smaller conjoined onto the larger, of more every other set into
   the largest."
  (cond ((null sets) (list :C%SET (make-hash-table :test 'equal)))
        ((null (cdr sets)) (car sets))
        ((null (cdr (cdr sets)))
         (let ((a (car sets)) (b (car (cdr sets))))
           (if (< (rontolisp::%clojure-set-count a)
                  (rontolisp::%clojure-set-count b))
               (rontolisp::%clojure-set-grow b (rontolisp::%clojure-seq-all a))
               (rontolisp::%clojure-set-grow a
                                             (rontolisp::%clojure-seq-all b)))))
        (t (let* ((bubbled (rontolisp::%clojure-set-bubble sets t))
                  (acc (car bubbled)))
             (dolist (s (cdr bubbled) acc)
               (setq acc
                     (rontolisp::%clojure-set-grow acc
                      (rontolisp::%clojure-seq-all s))))))))

(defun rontolisp::%clojure-set-union-v (&rest sets)
  "union as a value."
  (rontolisp::%clojure-set-union sets))

(defun rontolisp::%clojure-set-intersection-2 (a b)
  "The members of A that B holds, shrinking the smaller of the two."
  (if (< (rontolisp::%clojure-set-count b) (rontolisp::%clojure-set-count a))
      (rontolisp::%clojure-set-intersection-2 b a)
      (let ((drops nil))
        (dolist (x (rontolisp::%clojure-seq-all a))
          (if (not (rontolisp::%clojure-set-has b x))
              (setq drops (cons x drops))))
        (rontolisp::%clojure-set-shrink a drops))))

(defun rontolisp::%clojure-set-intersection (sets)
  "clojure.set/intersection over the non-empty list SETS: of more than two,
   from the smallest on."
  (cond ((null (cdr sets)) (car sets))
        ((null (cdr (cdr sets)))
         (rontolisp::%clojure-set-intersection-2 (car sets) (car (cdr sets))))
        (t (let* ((bubbled (rontolisp::%clojure-set-bubble sets nil))
                  (acc (car bubbled)))
             (dolist (s (cdr bubbled) acc)
               (setq acc (rontolisp::%clojure-set-intersection-2 acc s)))))))

(defun rontolisp::%clojure-set-intersection-v (&rest sets)
  "intersection as a value."
  (rontolisp::%clojure-set-arity sets 1 nil "intersection")
  (rontolisp::%clojure-set-intersection sets))

(defun rontolisp::%clojure-set-difference-2 (a b)
  "A without the members B holds: walking A when it is the smaller, else
   dropping every member of B."
  (if (< (rontolisp::%clojure-set-count a) (rontolisp::%clojure-set-count b))
      (let ((drops nil))
        (dolist (x (rontolisp::%clojure-seq-all a))
          (if (rontolisp::%clojure-set-has b x) (setq drops (cons x drops))))
        (rontolisp::%clojure-set-shrink a drops))
      (rontolisp::%clojure-set-shrink a (rontolisp::%clojure-seq-all b))))

(defun rontolisp::%clojure-set-difference (sets)
  "clojure.set/difference over the non-empty list SETS, left to right."
  (let ((acc (car sets)))
    (dolist (s (cdr sets) acc)
      (setq acc (rontolisp::%clojure-set-difference-2 acc s)))))

(defun rontolisp::%clojure-set-difference-v (&rest sets)
  "difference as a value."
  (rontolisp::%clojure-set-arity sets 1 nil "difference")
  (rontolisp::%clojure-set-difference sets))

(defun rontolisp::%clojure-set-select (pred xset)
  "The members of XSET that PRED passes (a set shrunk, nil itself)."
  (let ((drops nil))
    (dolist (x (rontolisp::%clojure-seq-all xset))
      (if (not (rontolisp::%clojure-filter-test pred x))
          (setq drops (cons x drops))))
    (rontolisp::%clojure-set-shrink xset drops)))

(defun rontolisp::%clojure-set-select-v (&rest args)
  "select as a value."
  (rontolisp::%clojure-set-arity args 2 2 "select")
  (rontolisp::%clojure-set-select (car args) (car (cdr args))))

(defun rontolisp::%clojure-set-entries (m name)
  "The entry table of the map M (a record's own), nil for nil; anything else
   signals in NAME's words."
  (cond ((null m) nil)
        ((rontolisp::%clojure-record-p m) (car (cdr (cdr (cdr m)))))
        ((hash-table-p m) m)
        (t (error "~A needs a map" name))))

(defun rontolisp::%clojure-set-keys (m name)
  "The keys of the map M as a list (none of nil)."
  (let ((acc nil) (table (rontolisp::%clojure-set-entries m name)))
    (if table
        (maphash (lambda (k v)
                   (declare (ignore v))
                   (setq acc (cons k acc))) table))
    acc))

(defun rontolisp::%clojure-set-rewrap (m table)
  "TABLE in the record M's place when M is a record, else TABLE."
  (if (rontolisp::%clojure-record-p m)
      (list :C%RECORD (car (cdr m)) (car (cdr (cdr m))) table
            (car (cdr (cdr (cdr (cdr m))))))
      table))

(defun rontolisp::%clojure-set-select-keys (m ks)
  "(select-keys M KS) for the key list KS: a fresh map of M's entries under
   those keys, each kept under M's own key."
  (let ((out (make-hash-table :test 'equal))
        (miss (list nil))
        (src (rontolisp::%clojure-set-entries m "select-keys")))
    (if src
        (dolist (k ks)
          (let* ((held (rontolisp::%clojure-table-key k src))
                 (v (gethash held src miss)))
            (if (not (eq v miss)) (setf (gethash held out) v)))))
    out))

(defun rontolisp::%clojure-set-merge (a b)
  "(merge A B) for two relation members: A's entries plus B's, B winning, in a
   fresh map, kept in A's record when A is one."
  (let ((eb (rontolisp::%clojure-set-entries b "merge")))
    (rontolisp::%clojure-set-rewrap a
                                    (rontolisp::%clojure-plist-table
                                     (rontolisp::%clojure-set-entries a "merge")
                                     (if eb
                                         (rontolisp:hash-table-plist eb)
                                         nil)))))

(defun rontolisp::%clojure-set-project (xrel ks)
  "The set of every member of XREL narrowed to the keys KS."
  (let ((keys (rontolisp::%clojure-seq-all ks)))
    (rontolisp::%clojure-set-of
     (mapcar (lambda (x) (rontolisp::%clojure-set-select-keys x keys))
             (rontolisp::%clojure-seq-all xrel)))))

(defun rontolisp::%clojure-set-project-v (&rest args)
  "project as a value."
  (rontolisp::%clojure-set-arity args 2 2 "project")
  (rontolisp::%clojure-set-project (car args) (car (cdr args))))

(defun rontolisp::%clojure-set-rename-keys (m kmap)
  "M with each key of KMAP present in it renamed to KMAP's value for it, every
   KMAP key dropped first; nil is nil. A record stays one unless a declared
   field is dropped, like the oracle's dissoc."
  (if (null m)
      nil
      (let* ((src (rontolisp::%clojure-set-entries m "rename-keys"))
             (pairs (rontolisp::%clojure-kv-pairs kmap "rename-keys"))
             (out
              (rontolisp:plist-hash-table (rontolisp:hash-table-plist src)
                                          :test 'equal))
             (miss (list nil))
             (record (rontolisp::%clojure-record-p m)))
        (dolist (p pairs)
          (remhash (rontolisp::%clojure-table-key (car p) out) out))
        (if record
            (dolist (f (car (cdr (cdr m))))
              (if (eq (gethash f out miss) miss) (setq record nil))))
        (dolist (p pairs)
          (let ((v
                 (gethash (rontolisp::%clojure-table-key (car p) src) src
                          miss)))
            (if (not (eq v miss))
                (setf (gethash (rontolisp::%clojure-store-key (cdr p) out) out)
                      v))))
        (if record (rontolisp::%clojure-set-rewrap m out) out))))

(defun rontolisp::%clojure-set-rename-keys-v (&rest args)
  "rename-keys as a value."
  (rontolisp::%clojure-set-arity args 2 2 "rename-keys")
  (rontolisp::%clojure-set-rename-keys (car args) (car (cdr args))))

(defun rontolisp::%clojure-set-rename (xrel kmap)
  "The set of every member of XREL with its keys renamed by KMAP."
  (rontolisp::%clojure-set-of
   (mapcar (lambda (x) (rontolisp::%clojure-set-rename-keys x kmap))
           (rontolisp::%clojure-seq-all xrel))))

(defun rontolisp::%clojure-set-rename-v (&rest args)
  "rename as a value."
  (rontolisp::%clojure-set-arity args 2 2 "rename")
  (rontolisp::%clojure-set-rename (car args) (car (cdr args))))

(defun rontolisp::%clojure-set-index (xrel ks)
  "A map from each distinct narrowing of XREL's members to the keys KS to the
   set of the members narrowing to it."
  (let ((keys (rontolisp::%clojure-seq-all ks))
        (out (make-hash-table :test 'equal))
        (miss (list nil)))
    (dolist (x (rontolisp::%clojure-seq-all xrel) out)
      (let* ((k
              (rontolisp::%clojure-store-key
               (rontolisp::%clojure-set-select-keys x keys) out))
             (s (gethash k out miss)))
        (if (eq s miss)
            (progn
              (setq s (list :C%SET (make-hash-table :test 'equal)))
              (setf (gethash k out) s)))
        (rontolisp::%clojure-set-put (car (cdr s)) x)))))

(defun rontolisp::%clojure-set-index-v (&rest args)
  "index as a value."
  (rontolisp::%clojure-set-arity args 2 2 "index")
  (rontolisp::%clojure-set-index (car args) (car (cdr args))))

(defun rontolisp::%clojure-set-map-invert (m)
  "A fresh map from each value of M to its key."
  (let ((out (make-hash-table :test 'equal)))
    (dolist (kv (rontolisp::%clojure-kv-pairs m "map-invert") out)
      (setf (gethash (rontolisp::%clojure-store-key (cdr kv) out) out)
            (car kv)))))

(defun rontolisp::%clojure-set-map-invert-v (&rest args)
  "map-invert as a value."
  (rontolisp::%clojure-set-arity args 1 1 "map-invert")
  (rontolisp::%clojure-set-map-invert (car args)))

(defun rontolisp::%clojure-set-join-index (r s ks rekey)
  "The join walk: every member x of S merged onto each member of R that the
   index of R by KS files under x's narrowing to (REKEY x)'s keys."
  (let ((idx (rontolisp::%clojure-set-index r ks))
        (out (make-hash-table :test 'equal))
        (miss (list nil)))
    (dolist (x (rontolisp::%clojure-seq-all s))
      (let* ((k (funcall rekey x))
             (found (gethash (rontolisp::%clojure-table-key k idx) idx miss)))
        (if (not (eq found miss))
            (dolist (m (rontolisp::%clojure-seq-all found))
              (rontolisp::%clojure-set-put out
               (rontolisp::%clojure-set-merge m x))))))
    (list :C%SET out)))

(defun rontolisp::%clojure-set-join (xrel yrel)
  "The natural join of two relations on the keys their first members share,
   indexing the smaller; the empty set when either is empty."
  (if (and (rontolisp::%clojure-seq xrel) (rontolisp::%clojure-seq yrel))
      (let* ((yfirst
              (rontolisp::%clojure-set-entries
               (car (rontolisp::%clojure-seq yrel)) "join"))
             (ks nil)
             (small
              (<= (rontolisp::%clojure-set-count xrel)
                  (rontolisp::%clojure-set-count yrel))))
        (dolist (k
                 (rontolisp::%clojure-set-keys
                  (car (rontolisp::%clojure-seq xrel)) "join"))
          (if (rontolisp::%clojure-set-has yfirst k) (setq ks (cons k ks))))
        (rontolisp::%clojure-set-join-index (if small xrel yrel)
         (if small yrel xrel) ks
         (lambda (x) (rontolisp::%clojure-set-select-keys x ks))))
      (list :C%SET (make-hash-table :test 'equal))))

(defun rontolisp::%clojure-set-join-km (xrel yrel km)
  "The join of two relations where KM maps XREL's keys to YREL's, indexing the
   smaller."
  (let* ((small
          (<= (rontolisp::%clojure-set-count xrel)
              (rontolisp::%clojure-set-count yrel)))
         (k (if small (rontolisp::%clojure-set-map-invert km) km))
         (pairs (rontolisp::%clojure-kv-pairs k "join"))
         (from (mapcar (lambda (p) (car p)) pairs)))
    (rontolisp::%clojure-set-join-index (if small xrel yrel)
                                        (if small yrel xrel)
                                        (mapcar (lambda (p) (cdr p)) pairs)
                                        (lambda (x)
                                          (rontolisp::%clojure-set-rename-keys
                                           (rontolisp::%clojure-set-select-keys
                                            x from) k)))))

(defun rontolisp::%clojure-set-join-v (&rest args)
  "join as a value: two relations, or two and a key map."
  (if (= (rontolisp::%clojure-set-arity args 2 3 "join") 2)
      (rontolisp::%clojure-set-join (car args) (car (cdr args)))
      (rontolisp::%clojure-set-join-km (car args) (car (cdr args))
                                       (car (cdr (cdr args))))))

(defun rontolisp::%clojure-set-subset-p (a b)
  "Whether every member of A is in B, the false object otherwise."
  (let ((ok
         (<= (rontolisp::%clojure-set-count a)
             (rontolisp::%clojure-set-count b))))
    (if ok
        (dolist (x (rontolisp::%clojure-seq-all a))
          (if (not (rontolisp::%clojure-set-has b x)) (setq ok nil))))
    (if ok t rontolisp::%clojure-false)))

(defun rontolisp::%clojure-set-subset-p-v (&rest args)
  "subset? as a value."
  (rontolisp::%clojure-set-arity args 2 2 "subset?")
  (rontolisp::%clojure-set-subset-p (car args) (car (cdr args))))

(defun rontolisp::%clojure-set-superset-p (a b)
  "Whether every member of B is in A, the false object otherwise."
  (rontolisp::%clojure-set-subset-p b a))

(defun rontolisp::%clojure-set-superset-p-v (&rest args)
  "superset? as a value."
  (rontolisp::%clojure-set-arity args 2 2 "superset?")
  (rontolisp::%clojure-set-subset-p (car (cdr args)) (car args)))

;;;; Metadata: with-meta and meta over an identity side table.
;;
;; A value's metadata lives in rontolisp::%clojure-meta-table, an eq table from the
;; object with-meta answered to its map, made on first use (a program that never
;; attaches metadata allocates nothing). with-meta answers a fresh shallow copy,
;; like the oracle's new object: the original keeps its own metadata and = still
;; compares contents (a reify copy keeps its tag, so it dispatches the same and is
;; a different object, like the oracle's). The kinds that carry metadata are the
;; oracle's IObj kinds the lowering has: maps, vectors, lists, sets, records, reify
;; values, lazy seqs and functions (a wrapping closure).
;;
;; Deliberate non-goals, each a documented deviation (.kb/clojure-frontend.md): a
;; derived value (assoc, conj, ...) starts without metadata where the oracle
;; carries it over; a symbol answers itself without metadata (an interned symbol
;; has no copy to hang it on, and = on symbols compares names); the table keeps
;; every object it was handed for the program's lifetime.

(defvar rontolisp::%clojure-meta-table
  nil
  "The metadata side table: an eq table from an object to its metadata map, NIL
   until the first metadata is attached.")

(defun rontolisp::%clojure-check-meta (m)
  "M, or the oracle's refusal when it is neither nil nor a map."
  (if (or (null m) (hash-table-p m) (rontolisp::%clojure-record-p m))
      m
      (error "with-meta takes a map as metadata")))

(defun rontolisp::%clojure-put-meta (x m)
  "X with the metadata map M recorded for it (none for a nil M); answers X."
  (when (rontolisp::%clojure-check-meta m)
    (unless rontolisp::%clojure-meta-table
      (setq rontolisp::%clojure-meta-table (make-hash-table :test 'eq)))
    (setf (gethash x rontolisp::%clojure-meta-table) m))
  x)

(defun rontolisp::%clojure-with-meta (x m)
  "A copy of X carrying the metadata map M, like the oracle's withMeta; a symbol
   answers itself (see above), anything else that is no IObj in the oracle
   signals, like its cast."
  (cond ((hash-table-p x)
         (let ((copy (make-hash-table :test 'equal)))
           (maphash (lambda (k v) (setf (gethash k copy) v)) x)
           (rontolisp::%clojure-put-meta copy m)))
        ((and (vectorp x) (not (stringp x)))
         (rontolisp::%clojure-put-meta (copy-seq x) m))
        ((functionp x)
         (rontolisp::%clojure-put-meta (lambda (&rest args) (apply x args)) m))
        ((and (consp x) (not (rontolisp::%clojure-keyword-p x))
              (not (rontolisp::%clojure-atom-p x))
              (not
               (member (car x)
                '(:C%TYPE :C%PATTERN :C%MATCHER :C%REDUCED :C%NIL :C%VAR))))
         (rontolisp::%clojure-put-meta (copy-list x) m))
        ((and x (symbolp x) (not (eq x t))
              (not (eq x rontolisp::%clojure-false)))
         (rontolisp::%clojure-check-meta m)
         x)
        (t (error "with-meta takes a collection, a record or a function"))))

(defun rontolisp::%clojure-with-meta-v (&rest args)
  "with-meta as a value."
  (rontolisp::%clojure-check-arity args 2 2 "with-meta")
  (rontolisp::%clojure-with-meta (car args) (car (cdr args))))

(defun rontolisp::%clojure-meta (x)
  "X's metadata map, nil when it carries none."
  (if rontolisp::%clojure-meta-table
      (values (gethash x rontolisp::%clojure-meta-table))
      nil))

(defun rontolisp::%clojure-meta-v (&rest args)
  "meta as a value."
  (rontolisp::%clojure-check-arity args 1 1 "meta")
  (rontolisp::%clojure-meta (car args)))

(defun rontolisp::%clojure-vary-meta (x f args)
  "A copy of X carrying (apply f (meta x) args)."
  (rontolisp::%clojure-with-meta x
   (rontolisp::%clojure-call f (cons (rontolisp::%clojure-meta x) args))))

(defun rontolisp::%clojure-vary-meta-v (&rest args)
  "vary-meta as a value."
  (rontolisp::%clojure-check-arity args 2 nil "vary-meta")
  (rontolisp::%clojure-vary-meta (car args) (car (cdr args)) (cdr (cdr args))))

(defun rontolisp::%clojure-meta-method (x method)
  "The implementation X's metadata holds under the qualified METHOD symbol, or
   NIL when it holds none or a falsey one: the oracle's extend-via-metadata
   lookup, which then invokes it like any IFn."
  (let ((m (rontolisp::%clojure-meta x)))
    (if m
        (let ((f
               (gethash method
                        (if (hash-table-p m) m (car (cdr (cdr (cdr m))))))))
          (if (rontolisp::%clojure-truthy f) f nil))
        nil)))

;;;; Vars: #'x as a value (b80).
;;
;; A var is (:C%VAR "ns/name" getter), interned per name in
;; rontolisp::%clojure-var-table (made on first use), so #'x answers the same
;; object at every site, like the oracle's one Var per name. GETTER is a
;; closure over the lowered value of the name, so deref and an invocation read
;; the root through it; its metadata lives in %clojure-meta-table like any other
;; value's. Each site hands both in: the lowering records a definition's
;; metadata at lower time (docstring, arglists, position, name metadata), and a
;; site lowered after a redefinition sees the newest one.

(defvar rontolisp::%clojure-var-table
  nil
  "The interned vars: an equal table from \"ns/name\" to its var, NIL until the
   first #' runs.")

(defun rontolisp::%clojure-var-p (x)
  "Whether X is the (:C%VAR name getter) var #'x lowers to."
  (and (consp x) (eq (car x) :C%VAR)))

(defun rontolisp::%clojure-var (name getter meta)
  "The var NAME (\"ns/name\"), interned on first use, reading its root through
   GETTER and carrying META."
  (unless rontolisp::%clojure-var-table
    (setq rontolisp::%clojure-var-table (make-hash-table :test 'equal)))
  (let ((v (gethash name rontolisp::%clojure-var-table)))
    (if v
        (rplaca (cdr (cdr v)) getter)
        (progn
          (setq v (list :C%VAR name getter))
          (setf (gethash name rontolisp::%clojure-var-table) v)))
    (rontolisp::%clojure-put-meta v meta)))

(defun rontolisp::%clojure-var-get (v)
  "The root of the var V."
  (funcall (car (cdr (cdr v)))))

(defun rontolisp::%clojure-var-test (v)
  "clojure.core/test: call the fn at :test in V's metadata, answering :ok, or
   :no-test when there is none; whatever the fn throws passes through."
  (let ((f
         (rontolisp::%clojure-call-keyword (list :C%KEYWORD "test")
                                           (rontolisp::%clojure-meta v) nil)))
    (if (rontolisp::%clojure-truthy f)
        (progn
          (rontolisp::%clojure-call f nil)
          (list :C%KEYWORD "ok"))
        (list :C%KEYWORD "no-test"))))

(defun rontolisp::%clojure-var-test-v (&rest args)
  "test as a value."
  (rontolisp::%clojure-check-arity args 1 1 "test")
  (rontolisp::%clojure-var-test (car args)))

;;;; Reduction and transducers (b60).
;;
;; A transducer is what the oracle's is: a function from a reducing function to
;; a reducing function, so comp composes them left to right with no help and a
;; program may write its own (fn [rf] (fn ([] ...) ([acc] ...) ([acc x] ...))).
;; A reducing function takes zero arguments (init), one (completion) or two
;; (step); the built-in ones here are &optional lambdas telling the three apart
;; by the supplied-p flags. Per-reduction state (take's count, partition-all's
;; buffer) lives in variables the (lambda (rf) ...) closes over, created when
;; the transducer is applied to a reducing function, like the oracle's
;; volatiles. (reduced x) is the (:C%REDUCED x) wrapper beside the other
;; keyword-tagged cells; reduce, reduce-kv, transduce and every stepping
;; consumer stop at one and unwrap it.
;;
;; reduce walks the lazy-aware seq view one element at a time, so a lazy input
;; reduces whole (it used to consume one level and fold the wrapper's own
;; cells). sequence and eduction step their inputs through the transducer one
;; element at a time behind a lazy wrapper, so a lazy input answers a lazy seq
;; and stays lazy (take of an infinite one terminates); a strict input answers
;; the realized strict list (the lazy-or-strict rule of the rows above).
;;
;; Deliberate non-goals, each a documented deviation (.kb/clojure-frontend.md):
;; an eduction is that seq, computed once, where the oracle re-runs the
;; transformation every time it is reduced; a reduced value prints as its
;; wrapper list.

(defun rontolisp::%clojure-reduced (x)
  "(reduced x): X wrapped so a reduction stops and answers it."
  (list :C%REDUCED x))

(defun rontolisp::%clojure-reduced-p (x)
  "Whether X is the (:C%REDUCED value) wrapper."
  (and (consp x) (eq (car x) :C%REDUCED) (consp (cdr x)) (null (cdr (cdr x)))))

(defun rontolisp::%clojure-reduced-pred (x)
  "reduced? answering T-or-false."
  (if (rontolisp::%clojure-reduced-p x) t rontolisp::%clojure-false))

(defun rontolisp::%clojure-unreduced (x)
  "The value inside a reduced X, else X."
  (if (rontolisp::%clojure-reduced-p x) (car (cdr x)) x))

(defun rontolisp::%clojure-ensure-reduced (x)
  "X when it is reduced, else X wrapped."
  (if (rontolisp::%clojure-reduced-p x) x (rontolisp::%clojure-reduced x)))

(defun rontolisp::%clojure-deref-other (x)
  "deref of anything but an atom cell: a reduced value's content (the oracle's
   Reduced is an IDeref), a var's root, else the oracle's cast failure."
  (cond ((rontolisp::%clojure-reduced-p x) (car (cdr x)))
        ((rontolisp::%clojure-var-p x) (rontolisp::%clojure-var-get x))
        (t (error "deref needs an atom"))))

(defun rontolisp::%clojure-call-1 (f x)
  "F applied to X: a real function directly, anything else through the IFn
   dispatcher (no argument list consed for the common case)."
  (if (functionp f) (funcall f x) (rontolisp::%clojure-call f (list x))))

(defun rontolisp::%clojure-rf-init (rf)
  "The reducing function RF's init arity."
  (if (functionp rf) (funcall rf) (rontolisp::%clojure-call rf nil)))

(defun rontolisp::%clojure-rf-complete (rf acc)
  "The reducing function RF's completion arity."
  (if (functionp rf) (funcall rf acc) (rontolisp::%clojure-call rf (list acc))))

(defun rontolisp::%clojure-rf-step (rf acc x)
  "The reducing function RF's step arity."
  (if (functionp rf)
      (funcall rf acc x)
      (rontolisp::%clojure-call rf (list acc x))))

(defun rontolisp::%clojure-seq-rest (s)
  "The seq past the head of the realized seq S: its tail, realized one level
   when it is a lazy wrapper (a strict tail is already a seq)."
  (let ((r (cdr s)))
    (if (rontolisp::%clojure-lazy-p r) (rontolisp::%clojure-realize r) r)))

(defun rontolisp::%clojure-reduce-seq (f acc s)
  "The real function F folded from ACC over the realized seq S, stopping at a
   reduced answer (unwrapped), like the oracle: only F's answers are tested,
   never ACC. F is funcalled: the lowering wraps a value that may hold a
   collection in a dispatcher lambda, so a plain reduce never carries the IFn
   dispatcher."
  (let ((done nil))
    (do ()
        ((or done (null s)) acc)
      (setq acc (funcall f acc (car s)))
      (if (rontolisp::%clojure-reduced-p acc)
          (progn
            (setq acc (car (cdr acc)))
            (setq done t))
          (setq s (rontolisp::%clojure-seq-rest s))))))

(defun rontolisp::%clojure-reduce (f coll)
  "(reduce f coll): (f) of empty, the lone member of one, else F folded from
   the head over the rest."
  (let ((s (rontolisp::%clojure-seq coll)))
    (if (null s)
        (funcall f)
        (rontolisp::%clojure-reduce-seq f (car s)
                                        (rontolisp::%clojure-seq-rest s)))))

(defun rontolisp::%clojure-reduce-init (f init coll)
  "(reduce f init coll)."
  (rontolisp::%clojure-reduce-seq f init (rontolisp::%clojure-seq coll)))

(defun rontolisp::%clojure-xf-rf (rf step complete)
  "A reducing function over RF: STEP (a two-argument closure) for the step
   arity, COMPLETE (one argument) for the completion or nil to pass it to RF,
   and the init arity passed to RF."
  (lambda (&optional (acc nil acc-p) (x nil x-p))
    (cond (x-p (funcall step acc x))
          (acc-p (if complete
                     (funcall complete acc)
                     (rontolisp::%clojure-rf-complete rf acc)))
          (t (rontolisp::%clojure-rf-init rf)))))

(defun rontolisp::%clojure-xf-map (f)
  "(map f): each input through F (several inputs, from a multi-collection
   sequence, spread as F's arguments)."
  (lambda (rf)
    (lambda (&optional (acc nil acc-p) (x nil x-p) &rest more)
      (cond (x-p (rontolisp::%clojure-rf-step rf acc
                                              (if more
                                                  (rontolisp::%clojure-call f
                                                   (cons x more))
                                                  (rontolisp::%clojure-call-1 f
                                                   x))))
            (acc-p (rontolisp::%clojure-rf-complete rf acc))
            (t (rontolisp::%clojure-rf-init rf))))))

(defun rontolisp::%clojure-xf-filter (pred keep)
  "(filter pred) when KEEP, (remove pred) otherwise: Clojure truthiness."
  (lambda (rf)
    (rontolisp::%clojure-xf-rf rf
                               (lambda (acc x)
                                 (if (rontolisp::%clojure-truthy
                                      (rontolisp::%clojure-call-1 pred x))
                                     (if keep
                                         (rontolisp::%clojure-rf-step rf acc x)
                                         acc)
                                     (if keep
                                         acc
                                         (rontolisp::%clojure-rf-step rf acc
                                                                      x))))
                               nil)))

(defun rontolisp::%clojure-xf-keep (f)
  "(keep f): F's non-nil answers (false is kept, like the oracle)."
  (lambda (rf)
    (rontolisp::%clojure-xf-rf rf
                               (lambda (acc x)
                                 (let ((v (rontolisp::%clojure-call-1 f x)))
                                   (if (null v)
                                       acc
                                       (rontolisp::%clojure-rf-step rf acc v))))
                               nil)))

(defun rontolisp::%clojure-xf-indexed (f keep)
  "(keep-indexed f) when KEEP, (map-indexed f) otherwise: F over the index
   from 0 and the input."
  (lambda (rf)
    (let ((i -1))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (setq i (+ i 1))
                                   (let ((v
                                          (rontolisp::%clojure-call f
                                           (list i x))))
                                     (if (and keep (null v))
                                         acc
                                         (rontolisp::%clojure-rf-step rf acc
                                                                      v))))
                                 nil))))

(defun rontolisp::%clojure-xf-take (n)
  "(take n): the first N inputs, then a reduced answer, so the reduction
   stops reading (the oracle's count: (take 0) still reads one input)."
  (lambda (rf)
    (let ((left n))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (let ((was left))
                                     (setq left (- left 1))
                                     (let ((r
                                            (if (> was 0)
                                                (rontolisp::%clojure-rf-step rf
                                                                             acc
                                                                             x)
                                                acc)))
                                       (if (> left 0)
                                           r
                                           (rontolisp::%clojure-ensure-reduced
                                            r))))) nil))))

(defun rontolisp::%clojure-xf-drop (n)
  "(drop n): every input past the first N."
  (lambda (rf)
    (let ((left n))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (let ((was left))
                                     (setq left (- left 1))
                                     (if (> was 0)
                                         acc
                                         (rontolisp::%clojure-rf-step rf acc
                                                                      x))))
                                 nil))))

(defun rontolisp::%clojure-xf-take-while (pred)
  "(take-while pred): inputs while PRED holds, then a reduced answer."
  (lambda (rf)
    (rontolisp::%clojure-xf-rf rf
                               (lambda (acc x)
                                 (if (rontolisp::%clojure-truthy
                                      (rontolisp::%clojure-call-1 pred x))
                                     (rontolisp::%clojure-rf-step rf acc x)
                                     (rontolisp::%clojure-reduced acc))) nil)))

(defun rontolisp::%clojure-xf-drop-while (pred)
  "(drop-while pred): every input from the first PRED rejects."
  (lambda (rf)
    (let ((dropping t))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (if (and dropping
                                            (rontolisp::%clojure-truthy
                                             (rontolisp::%clojure-call-1 pred
                                                                         x)))
                                       acc
                                       (progn
                                         (setq dropping nil)
                                         (rontolisp::%clojure-rf-step rf acc
                                                                      x))))
                                 nil))))

(defun rontolisp::%clojure-xf-take-nth (n)
  "(take-nth n): every Nth input from the first (a zero N signals, like the
   oracle's division)."
  (lambda (rf)
    (let ((i -1))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (setq i (+ i 1))
                                   (if (= (rem i n) 0)
                                       (rontolisp::%clojure-rf-step rf acc x)
                                       acc)) nil))))

(defun rontolisp::%clojure-xf-cat (rf)
  "cat: each input's members stepped through RF in turn. A reduced answer is
   wrapped once more so the inner reduce stops and hands it on, still reduced
   (the oracle's preserving-reduced)."
  (rontolisp::%clojure-xf-rf rf
                             (lambda (acc x)
                               (rontolisp::%clojure-reduce-init
                                (lambda (a y)
                                  (let ((r
                                         (rontolisp::%clojure-rf-step rf a y)))
                                    (if (rontolisp::%clojure-reduced-p r)
                                        (rontolisp::%clojure-reduced r)
                                        r))) acc x)) nil))

(defun rontolisp::%clojure-xf-mapcat (f)
  "(mapcat f): (comp (map f) cat)."
  (lambda (rf)
    (funcall (rontolisp::%clojure-xf-map f) (rontolisp::%clojure-xf-cat rf))))

(defun rontolisp::%clojure-xf-flush (rf acc buf)
  "The completion of a buffering transducer: the reversed BUF stepped as one
   vector when it holds anything (unwrapped, so the completion still runs),
   then RF's completion."
  (rontolisp::%clojure-rf-complete rf
                                   (if buf
                                       (rontolisp::%clojure-unreduced
                                        (rontolisp::%clojure-rf-step rf acc
                                         (coerce (reverse buf) 'vector)))
                                       acc)))

(defun rontolisp::%clojure-xf-partition-all (n)
  "(partition-all n): vectors of N inputs, the short tail flushed at the end."
  (lambda (rf)
    (let ((buf nil) (count 0))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (setq buf (cons x buf))
                                   (setq count (+ count 1))
                                   (if (= count n)
                                       (let ((v (coerce (reverse buf) 'vector)))
                                         (setq buf nil)
                                         (setq count 0)
                                         (rontolisp::%clojure-rf-step rf acc v))
                                       acc))
                                 (lambda (acc)
                                   (let ((pending buf))
                                     (setq buf nil)
                                     (setq count 0)
                                     (rontolisp::%clojure-xf-flush rf acc
                                      pending)))))))

(defun rontolisp::%clojure-xf-partition-by (f)
  "(partition-by f): vectors of consecutive inputs whose (f input) stays =."
  (lambda (rf)
    (let ((buf nil) (have nil) (prev nil))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (let ((v (rontolisp::%clojure-call-1 f x))
                                         (p prev)
                                         (h have))
                                     (setq prev v)
                                     (setq have t)
                                     (if (or (not h)
                                             (rontolisp::%clojure-equal v p))
                                         (progn
                                           (setq buf (cons x buf))
                                           acc)
                                         (let ((out
                                                (coerce (reverse buf) 'vector)))
                                           (setq buf nil)
                                           (let ((r
                                                  (rontolisp::%clojure-rf-step
                                                   rf acc out)))
                                             (if (not
                                                  (rontolisp::%clojure-reduced-p
                                                   r))
                                                 (setq buf (cons x buf)))
                                             r)))))
                                 (lambda (acc)
                                   (let ((pending buf))
                                     (setq buf nil)
                                     (rontolisp::%clojure-xf-flush rf acc
                                      pending)))))))

(defun rontolisp::%clojure-xf-dedupe ()
  "(dedupe): inputs not = to the one before."
  (lambda (rf)
    (let ((have nil) (prior nil))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (let ((h have) (p prior))
                                     (setq have t)
                                     (setq prior x)
                                     (if (and h (rontolisp::%clojure-equal p x))
                                         acc
                                         (rontolisp::%clojure-rf-step rf acc
                                                                      x))))
                                 nil))))

(defun rontolisp::%clojure-xf-distinct ()
  "(distinct): first occurrences, by = membership (the seq arity's)."
  (lambda (rf)
    (let ((seen (make-hash-table :test 'equal)))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (let ((k
                                          (rontolisp::%clojure-store-key x
                                                                         seen)))
                                     (if (gethash k seen)
                                         acc
                                         (progn
                                           (setf (gethash k seen) t)
                                           (rontolisp::%clojure-rf-step rf acc
                                                                        x)))))
                                 nil))))

(defun rontolisp::%clojure-xf-interpose (sep)
  "(interpose sep): SEP between consecutive inputs."
  (lambda (rf)
    (let ((started nil))
      (rontolisp::%clojure-xf-rf rf
                                 (lambda (acc x)
                                   (if started
                                       (let ((s
                                              (rontolisp::%clojure-rf-step rf
                                               acc sep)))
                                         (if (rontolisp::%clojure-reduced-p s)
                                             s
                                             (rontolisp::%clojure-rf-step rf s
                                                                          x)))
                                       (progn
                                         (setq started t)
                                         (rontolisp::%clojure-rf-step rf acc
                                                                      x))))
                                 nil))))

(defun rontolisp::%clojure-xf-comp (xfs)
  "The transducers of the XFS list composed like comp: the first sees each
   input first."
  (lambda (rf)
    (let ((r rf))
      (dolist (xf (reverse xfs) r)
        (setq r (rontolisp::%clojure-call-1 xf r))))))

(defun rontolisp::%clojure-completing (f cf)
  "(completing f cf): F's init and step arities over CF as the completion."
  (lambda (&optional (acc nil acc-p) (x nil x-p))
    (cond (x-p (rontolisp::%clojure-rf-step f acc x))
          (acc-p (rontolisp::%clojure-call-1 cf acc))
          (t (rontolisp::%clojure-rf-init f)))))

(defun rontolisp::%clojure-transduce (xf f init coll)
  "(transduce xf f init coll): (xf f) reduced from INIT over COLL, then its
   completion run on the answer."
  (let ((rf (rontolisp::%clojure-call-1 xf f)))
    (rontolisp::%clojure-rf-complete rf
     (rontolisp::%clojure-reduce-init rf init coll))))

(defun rontolisp::%clojure-transduce-3 (xf f coll)
  "(transduce xf f coll): the init is (f), called before XF sees F."
  (rontolisp::%clojure-transduce xf f (rontolisp::%clojure-rf-init f) coll))

(defun rontolisp::%clojure-into-xf (to xf from step)
  "(into to xf from): FROM through XF conjoined onto TO by STEP."
  (rontolisp::%clojure-transduce xf
   (rontolisp::%clojure-completing step #'identity) to from))

(defun rontolisp::%clojure-sequence (coll)
  "(sequence coll): a lazy COLL itself, else its strict seq view."
  (if (rontolisp::%clojure-lazy-p coll)
      coll
      (rontolisp::%clojure-strict-seq coll)))

(defun rontolisp::%clojure-xf-puller (xf colls)
  "A zero-argument closure answering XF's next outputs over the COLLS list as
   a list in order, or nil once the inputs end (or a step answers reduced) and
   the completion has flushed. Several collections step in lockstep, each
   input spread as the step's arguments, to the shortest."
  (let ((out nil) (seqs colls) (done nil))
    (let ((xrf
           (rontolisp::%clojure-call-1 xf
                                       (lambda (&optional acc (x nil x-p))
                                         (if x-p (setq out (cons x out)))
                                         acc))))
      (lambda ()
        (do ()
            ((or out done)
             (let ((batch (reverse out)))
               (setq out nil)
               batch))
          (let ((ss (mapcar #'rontolisp::%clojure-seq seqs)))
            (if (rontolisp::%clojure-map-done-p ss)
                (progn
                  (setq done t)
                  (rontolisp::%clojure-rf-complete xrf nil))
                (let ((r
                       (if (cdr ss)
                           (rontolisp::%clojure-call xrf
                            (cons nil (rontolisp::%clojure-map-heads ss)))
                           (rontolisp::%clojure-rf-step xrf nil
                                                        (car (car ss))))))
                  (setq seqs (rontolisp::%clojure-map-tails ss))
                  (if (rontolisp::%clojure-reduced-p r)
                      (progn
                        (setq done t)
                        (rontolisp::%clojure-rf-complete xrf nil)))))))))))

(defun rontolisp::%clojure-xf-lazy (pull batch)
  "The lazy seq of BATCH and then every batch PULL answers, one member per
   wrapper."
  (rontolisp::%clojure-make-lazy
   (lambda ()
     (let ((b (if batch batch (funcall pull))))
       (if (null b)
           nil
           (cons (car b) (rontolisp::%clojure-xf-lazy pull (cdr b))))))))

(defun rontolisp::%clojure-sequence-xf (xf colls)
  "(sequence xf coll...): the inputs stepped through XF: a lazy seq when any
   input is lazy, the realized strict list otherwise."
  (let ((lazy
         (rontolisp::%clojure-xf-lazy (rontolisp::%clojure-xf-puller xf colls)
                                      nil)))
    (if (rontolisp::%clojure-any-lazy-p colls)
        lazy
        (rontolisp::%clojure-realize-all lazy))))

(defun rontolisp::%clojure-take-nth (n coll)
  "(take-nth n coll): every Nth member from the first."
  (rontolisp::%clojure-sequence-xf (rontolisp::%clojure-xf-take-nth n)
                                   (list coll)))

(defun rontolisp::%clojure-take-nth-v (&rest args)
  "take-nth as a value: [n] the transducer, [n coll] the seq."
  (if (= (rontolisp::%clojure-check-arity args 1 2 "take-nth") 1)
      (rontolisp::%clojure-xf-take-nth (car args))
      (rontolisp::%clojure-take-nth (car args) (car (cdr args)))))

(defun rontolisp::%clojure-transduce-v (&rest args)
  "transduce as a value: [xf f coll] or [xf f init coll]."
  (if (= (rontolisp::%clojure-check-arity args 3 4 "transduce") 3)
      (rontolisp::%clojure-transduce-3 (car args) (car (cdr args))
                                       (car (cdr (cdr args))))
      (rontolisp::%clojure-transduce (car args) (car (cdr args))
                                     (car (cdr (cdr args)))
                                     (car (cdr (cdr (cdr args)))))))

(defun rontolisp::%clojure-eduction-v (&rest args)
  "eduction as a value: transducers then one collection."
  (rontolisp::%clojure-check-arity args 1 nil "eduction")
  (let ((rev (reverse args)))
    (rontolisp::%clojure-sequence-xf
     (rontolisp::%clojure-xf-comp (reverse (cdr rev))) (list (car rev)))))

(defun rontolisp::%clojure-sequence-v (&rest args)
  "sequence as a value: [coll] or [xf coll...]."
  (if (= (rontolisp::%clojure-check-arity args 1 nil "sequence") 1)
      (rontolisp::%clojure-sequence (car args))
      (rontolisp::%clojure-sequence-xf (car args) (cdr args))))

(defun rontolisp::%clojure-completing-v (&rest args)
  "completing as a value: [f] or [f cf]."
  (if (= (rontolisp::%clojure-check-arity args 1 2 "completing") 1)
      (rontolisp::%clojure-completing (car args) #'identity)
      (rontolisp::%clojure-completing (car args) (car (cdr args)))))
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

;;;; Reading (b85): read-string and read over one run-time reader.
;;
;; The reader reads the language the source reader (ClojureReader) reads and
;; answers what a quote of the same text answers, so (= (read-string s) 's)
;; holds: an identifier interns behind c%, a keyword is (:C%KEYWORD spelling)
;; with ::kw resolved against the calling namespace, a vector is a CL vector,
;; a map and a set the equal tables, reader metadata drops, #(...) is the
;; source reader's (fn %anon ...), and a record literal builds the record
;; through the classes the program registered (%clojure-read-register). The
;; two readers part in one place, where this one follows the oracle: a token
;; ends where the oracle's does (# and ' inside a token are constituents, a
;; backslash ends one) -- the source reader looks two characters past a #,
;; which a stream cannot. Both discard a #_ before a closing bracket and take a
;; character literal's first character whatever it is (\( reads back what pr
;; wrote); the end of input and an unmatched closing bracket are worded like
;; the oracle's here.
;;
;; The SOURCE is a (string . index) cursor for read-string, or a character
;; input stream for read (a clojure.java.io/reader, a PushbackReader over one,
;; *in*), consumed through peek-char and read-char, so a read leaves the stream
;; right after its datum like the oracle's PushbackReader. No unread-char:
;; this file is spliced whole into every Clojure program before
;; eval/UnreadCharLibrary looks, and a program naming it routes every
;; character read through the pushback cell; one character of lookahead is all
;; the grammar needs. RD is (source . context); CONTEXT is the calling
;; namespace and its aliases, ("ns" ("alias" "full.ns") ...).

(defvar rontolisp::%clojure-read-records
  nil
  "The record and deftype classes a record literal may name, newest first:
   entries (class tag fields record-p) over strings, registered by the
   lowering of a program that reads.")

(defun rontolisp::%clojure-read-register (entries)
  "Make the classes of ENTRIES readable, ahead of the ones registered before
   (a redefinition wins). Answers nil."
  (setq rontolisp::%clojure-read-records
        (append entries rontolisp::%clojure-read-records))
  nil)

(defun rontolisp::%clojure-rd-peek (rd)
  "The next character of RD's source, left in place, or NIL at its end."
  (let ((src (car rd)))
    (if (consp src)
        (if (< (cdr src) (length (car src))) (char (car src) (cdr src)) nil)
        (peek-char nil src nil nil))))

(defun rontolisp::%clojure-rd-next (rd)
  "The next character of RD's source, consumed, or NIL at its end."
  (let ((src (car rd)))
    (if (consp src)
        (let ((i (cdr src)))
          (if (< i (length (car src)))
              (progn
                (rplacd src (+ i 1))
                (char (car src) i))
              nil))
        (read-char src nil nil))))

(defun rontolisp::%clojure-rd-space-p (c)
  "Whether the character C is whitespace to the reader: space, tab, newline,
   return, formfeed and the comma, the source reader's set."
  (let ((code (char-code c)))
    (or (= code 32) (= code 44) (= code 10) (= code 9) (= code 13)
        (= code 12))))

(defun rontolisp::%clojure-rd-ends-token-p (c)
  "Whether the character C ends a token: whitespace or a terminating macro
   character of the oracle's reader (# ' and % stay inside a token)."
  (or (rontolisp::%clojure-rd-space-p c)
      (let ((code (char-code c)))
        (or (= code 40) (= code 41) (= code 91) (= code 93) (= code 123)
            (= code 125) (= code 34) (= code 59) (= code 64) (= code 94)
            (= code 96) (= code 126) (= code 92)))))

(defun rontolisp::%clojure-rd-escape-stops-p (c)
  "Whether a \\u or octal string escape stops before C: whitespace or a macro
   character, like the oracle's shared unicode reader (the source reader's
   escapeStops)."
  (or (rontolisp::%clojure-rd-ends-token-p c)
      (let ((code (char-code c))) (or (= code 39) (= code 37) (= code 35)))))

(defun rontolisp::%clojure-rd-digit (c radix)
  "The value of the digit character C in RADIX (2 to 36), or NIL."
  (let* ((code (char-code c))
         (value
          (cond ((and (>= code 48) (<= code 57)) (- code 48))
                ((and (>= code 97) (<= code 122)) (- code 87))
                ((and (>= code 65) (<= code 90)) (- code 55))
                (t nil))))
    (if (and value (< value radix)) value nil)))

(defun rontolisp::%clojure-rd-integer (s start end radix)
  "The integer the characters of S from START below END spell in RADIX, one
   sign allowed first (Java's BigInteger rule); NIL when there is no digit or
   a character is no digit of RADIX."
  (let ((sign 1) (i start) (value 0) (ok t))
    (if (and (< i end) (or (char= (char s i) #\+) (char= (char s i) #\-)))
        (progn
          (if (char= (char s i) #\-) (setq sign -1))
          (setq i (+ i 1))))
    (if (>= i end) (setq ok nil))
    (do ((j i (+ j 1)))
        ((or (not ok) (>= j end)))
      (let ((d (rontolisp::%clojure-rd-digit (char s j) radix)))
        (if d (setq value (+ (* value radix) d)) (setq ok nil))))
    (if ok (* sign value) nil)))

(defun rontolisp::%clojure-rd-skip (rd)
  "Skip the whitespace, commas and ; comments ahead of RD's next datum."
  (let ((c (rontolisp::%clojure-rd-peek rd)))
    (do ()
        ((not (and c (or (rontolisp::%clojure-rd-space-p c) (char= c #\;)))))
      (if (char= c #\;)
          (do ((d
                (rontolisp::%clojure-rd-next rd)
                (rontolisp::%clojure-rd-next rd)))
              ((or (null d) (char= d #\Newline))))
          (rontolisp::%clojure-rd-next rd))
      (setq c (rontolisp::%clojure-rd-peek rd)))))

(defun rontolisp::%clojure-rd-symbol (name)
  "The symbol a quote of the identifier NAME answers: mangled behind c%."
  (intern (concatenate 'string "c%" (rontolisp::%clojure-escape-part name))))

(defun rontolisp::%clojure-rd-token (rd first)
  "FIRST and the characters after it up to a token end, as a string."
  (let ((chars (list first)))
    (do ((c (rontolisp::%clojure-rd-peek rd) (rontolisp::%clojure-rd-peek rd)))
        ((or (null c) (rontolisp::%clojure-rd-ends-token-p c))
         (coerce (nreverse chars) 'string))
      (setq chars (cons (rontolisp::%clojure-rd-next rd) chars)))))

(defun rontolisp::%clojure-rd-form (rd)
  "The datum starting at RD's next character, or :C%READ-SKIP after a #_
   discard; the end of input signals."
  (let ((c (rontolisp::%clojure-rd-next rd)))
    (cond ((null c) (error "EOF while reading"))
          ((char= c #\() (rontolisp::%clojure-rd-seq rd #\)))
          ((char= c #\[) (coerce (rontolisp::%clojure-rd-seq rd #\]) 'vector))
          ((char= c #\{) (rontolisp::%clojure-rd-map rd))
          ((or (char= c #\)) (char= c #\]) (char= c #\}))
           (error "~A"
                  (concatenate 'string "Unmatched delimiter: " (string c))))
          ((char= c #\") (rontolisp::%clojure-rd-string rd))
          ((char= c #\\) (rontolisp::%clojure-rd-char rd))
          ((char= c #\') (rontolisp::%clojure-rd-wrap rd "quote"))
          ((char= c #\`) (rontolisp::%clojure-rd-wrap rd "syntax-quote"))
          ((char= c #\~)
           (if (eql (rontolisp::%clojure-rd-peek rd) #\@)
               (progn
                 (rontolisp::%clojure-rd-next rd)
                 (rontolisp::%clojure-rd-wrap rd "unquote-splicing"))
               (rontolisp::%clojure-rd-wrap rd "unquote")))
          ((char= c #\@) (rontolisp::%clojure-rd-wrap rd "deref"))
          ((char= c #\^) (rontolisp::%clojure-rd-meta rd))
          ((char= c #\#) (rontolisp::%clojure-rd-dispatch rd))
          (t (rontolisp::%clojure-rd-atom rd c)))))

(defun rontolisp::%clojure-rd-required (rd)
  "The next datum at RD past whitespace and #_ discards; the end of input
   signals."
  (let ((form :C%READ-SKIP))
    (do ()
        ((not (eq form :C%READ-SKIP)) form)
      (rontolisp::%clojure-rd-skip rd)
      (setq form (rontolisp::%clojure-rd-form rd)))))

(defun rontolisp::%clojure-rd-wrap (rd name)
  "(name datum) over the next datum: the quote, deref and syntax-quote family
   and #', spelled as the source reader spells them."
  (list (rontolisp::%clojure-rd-symbol name)
        (rontolisp::%clojure-rd-required rd)))

(defun rontolisp::%clojure-rd-meta (rd)
  "^meta form, the caret consumed: the form, its metadata read and dropped
   (a quote drops reader metadata too)."
  (rontolisp::%clojure-rd-required rd)
  (rontolisp::%clojure-rd-required rd))

(defun rontolisp::%clojure-rd-seq (rd close)
  "The datums up to the character CLOSE, consumed, as a list; the end of
   input signals."
  (let ((items nil) (done nil))
    (do ()
        (done (nreverse items))
      (rontolisp::%clojure-rd-skip rd)
      (let ((c (rontolisp::%clojure-rd-peek rd)))
        (cond ((null c) (error "EOF while reading"))
              ((char= c close)
               (rontolisp::%clojure-rd-next rd)
               (setq done t))
              (t (let ((form (rontolisp::%clojure-rd-form rd)))
                   (if (not (eq form :C%READ-SKIP))
                       (setq items (cons form items))))))))))

(defun rontolisp::%clojure-rd-map (rd)
  "A map literal's entries up to }, as a map whose keys go through the
   structural-key store like a literal's; a later key wins, like a map literal
   in source."
  (let ((items (rontolisp::%clojure-rd-seq rd #\})))
    (if (oddp (length items))
        (error "Map literal must contain an even number of forms"))
    (rontolisp::%clojure-plist-table nil items)))

(defun rontolisp::%clojure-rd-set (rd)
  "A set literal's members up to }, as the set wrapper; a repeated member
   signals, like the source reader."
  (let ((table (make-hash-table :test 'equal)) (miss (list nil)))
    (dolist (x (rontolisp::%clojure-rd-seq rd #\}))
      (if (not
           (eq (gethash (rontolisp::%clojure-table-key x table) table miss)
               miss))
          (error "~A"
                 (concatenate 'string "Duplicate key: "
                              (rontolisp::%clojure-str-of x "nil" t))))
      (rontolisp::%clojure-set-put table x))
    (list :C%SET table)))

(defun rontolisp::%clojure-rd-string (rd)
  "A string literal's characters up to the closing quote, its escapes decoded
   like the source reader's; a \\u surrogate pair joins into one character."
  (let ((chars nil) (high nil) (done nil))
    (do ()
        (done (coerce (nreverse (if high (cons (code-char high) chars) chars))
                      'string))
      (let ((c (rontolisp::%clojure-rd-next rd)))
        (cond ((null c) (error "EOF while reading string"))
              ((char= c #\") (setq done t))
              (t (let ((code
                        (if (char= c #\\)
                            (rontolisp::%clojure-rd-escape rd)
                            (char-code c))))
                   (cond ((and high (>= code 56320) (<= code 57343))
                          (setq chars
                                (cons (code-char
                                       (+ 65536 (* (- high 55296) 1024)
                                          (- code 56320))) chars))
                          (setq high nil))
                         (t
                          (if high (setq chars (cons (code-char high) chars)))
                          (if (and (>= code 55296) (<= code 56319))
                              (setq high code)
                              (progn
                                (setq high nil)
                                (setq chars
                                      (cons (code-char code) chars)))))))))))))

(defun rontolisp::%clojure-rd-escape (rd)
  "The code point of one string escape, its backslash consumed: \\n \\t \\r
   \\f \\b \\\\ \\\", \\u plus four hex digits, \\0 to \\377 in octal; any other
   is the oracle's refusal."
  (let ((e (rontolisp::%clojure-rd-next rd)))
    (if (null e) (error "EOF while reading string"))
    (let ((code (char-code e)))
      (cond ((= code 110) 10)
            ((= code 116) 9)
            ((= code 114) 13)
            ((= code 102) 12)
            ((= code 98) 8)
            ((or (= code 92) (= code 34)) code)
            ((= code 117) (rontolisp::%clojure-rd-unicode rd))
            ((and (>= code 48) (<= code 55))
             (rontolisp::%clojure-rd-octal rd (- code 48)))
            ((or (= code 56) (= code 57))
             (error "~A" (concatenate 'string "Invalid digit: " (string e))))
            (t (error "~A"
                      (concatenate 'string "Unsupported escape character: \\"
                                   (string e))))))))

(defun rontolisp::%clojure-rd-unicode (rd)
  "A \\u escape's value, the u consumed: four hex digits, stopping early at a
   stop character; a bad first digit, a bad later digit and a short escape
   are the oracle's refusals."
  (let ((first (rontolisp::%clojure-rd-peek rd)))
    (if (null first) (error "EOF while reading string"))
    (let ((d (rontolisp::%clojure-rd-digit first 16)))
      (if (null d)
          (error "~A"
           (concatenate 'string "Invalid unicode escape: \\u" (string first))))
      (rontolisp::%clojure-rd-next rd)
      (let ((value d) (count 1))
        (do ((c
              (rontolisp::%clojure-rd-peek rd)
              (rontolisp::%clojure-rd-peek rd)))
            ((or (>= count 4) (null c)
                 (rontolisp::%clojure-rd-escape-stops-p c)))
          (let ((digit (rontolisp::%clojure-rd-digit c 16)))
            (if (null digit)
                (error "~A" (concatenate 'string "Invalid digit: " (string c))))
            (rontolisp::%clojure-rd-next rd)
            (setq value (+ (* value 16) digit))
            (setq count (+ count 1))))
        (if (< count 4)
            (error "~A"
                   (concatenate 'string "Invalid character length: "
                                (string (code-char (+ 48 count)))
                                ", should be: 4")))
        value))))

(defun rontolisp::%clojure-rd-octal (rd value)
  "An octal string escape's value: VALUE its first digit, up to two more
   while no stop character comes; past \\377 the oracle's range refusal."
  (let ((count 1))
    (do ((c (rontolisp::%clojure-rd-peek rd) (rontolisp::%clojure-rd-peek rd)))
        ((or (>= count 3) (null c) (rontolisp::%clojure-rd-escape-stops-p c)))
      (let ((digit (rontolisp::%clojure-rd-digit c 8)))
        (if (null digit)
            (error "~A" (concatenate 'string "Invalid digit: " (string c))))
        (rontolisp::%clojure-rd-next rd)
        (setq value (+ (* value 8) digit))
        (setq count (+ count 1))))
    (if (> value 255)
        (error "Octal escape sequence must be in range [0, 377]."))
    value))

(defun rontolisp::%clojure-rd-char (rd)
  "A character literal, its backslash consumed: the first character whatever
   it is plus the token after it -- one character, a u and four hex digits,
   an o and one to three octal digits, or a lowercase name."
  (let ((first (rontolisp::%clojure-rd-next rd)))
    (if (null first) (error "EOF while reading character"))
    (let* ((token (rontolisp::%clojure-rd-token rd first))
           (n (length token))
           (code
            (cond ((= n 1) (char-code first))
                  ((and (= n 5) (char= first #\u))
                   (let ((cp (rontolisp::%clojure-rd-integer token 1 5 16)))
                     (if (and cp (or (< cp 55296) (> cp 57343))) cp nil)))
                  ((and (char= first #\o) (<= n 4))
                   (rontolisp::%clojure-rd-integer token 1 n 8))
                  ((string= token "newline") 10)
                  ((string= token "space") 32)
                  ((string= token "tab") 9)
                  ((string= token "return") 13)
                  ((string= token "backspace") 8)
                  ((string= token "formfeed") 12)
                  (t nil))))
      (if (null code)
          (error "~A" (concatenate 'string "Unsupported character: \\" token))
          (code-char code)))))

(defun rontolisp::%clojure-rd-atom (rd first)
  "An atom starting with the character FIRST: nil, true, false, a number when
   the token is number-shaped, a keyword after a colon, else a symbol."
  (let ((token (rontolisp::%clojure-rd-token rd first)))
    (cond ((string= token "nil") nil)
          ((string= token "true") t)
          ((string= token "false") rontolisp::%clojure-false)
          ((rontolisp::%clojure-rd-number-shaped-p token)
           (let ((number (rontolisp::%clojure-rd-number token)))
             (if (null number)
                 (error "~A" (concatenate 'string "Invalid number: " token))
                 number)))
          ((char= first #\:) (rontolisp::%clojure-rd-keyword rd token))
          (t (rontolisp::%clojure-rd-symbol token)))))

(defun rontolisp::%clojure-rd-number-shaped-p (token)
  "Whether TOKEN reads as a number: a leading digit, a sign before a digit or
   a dot, or a dot before a digit (the source reader's numberShaped)."
  (let ((n (length token)))
    (if (= n 0)
        nil
        (let ((c (char token 0)))
          (cond ((rontolisp::%clojure-rd-digit c 10) t)
                ((or (char= c #\+) (char= c #\-))
                 (and (> n 1)
                      (or (rontolisp::%clojure-rd-digit (char token 1) 10)
                          (char= (char token 1) #\.)) t))
                ((char= c #\.)
                 (and (> n 1) (rontolisp::%clojure-rd-digit (char token 1) 10)
                      t))
                (t nil))))))

(defun rontolisp::%clojure-rd-number (token)
  "The number the number-shaped TOKEN spells, or NIL (the source reader's
   parseNumber): a ratio, a 0x hex or Nr radix integer, a leading-zero
   octal, an M decimal as its exact ratio, a double, or an integer; the
   integer forms take an N suffix."
  (let* ((n (length token))
         (neg (char= (char token 0) #\-))
         (u (if (or neg (char= (char token 0) #\+)) 1 0))
         (slash (search "/" token))
         (big-end (if (char= (char token (- n 1)) #\N) (- n 1) n))
         (mark
          (max (rontolisp::%clojure-rd-index token #\r u)
               (rontolisp::%clojure-rd-index token #\R u)))
         (last (char token (- n 1))))
    (cond ((and slash (> slash 0) (rontolisp::%clojure-rd-ratio-chars-p token))
           (let ((num (rontolisp::%clojure-rd-integer token 0 slash 10))
                 (den (rontolisp::%clojure-rd-integer token (+ slash 1) n 10)))
             (if (and num den (/= den 0)) (/ num den) nil)))
          ((and (> (- n u) 1) (char= (char token u) #\0)
                (or (char= (char token (+ u 1)) #\x)
                    (char= (char token (+ u 1)) #\X)))
           (rontolisp::%clojure-rd-signed
            (rontolisp::%clojure-rd-integer token (+ u 2) big-end 16) neg))
          ((> mark u)
           (let ((radix (rontolisp::%clojure-rd-integer token u mark 10)))
             (if (and radix (>= radix 2) (<= radix 36))
                 (rontolisp::%clojure-rd-signed (rontolisp::%clojure-rd-integer
                                                 token (+ mark 1) big-end radix)
                                                neg)
                 nil)))
          ((and (> (- n u) 1) (char= (char token u) #\0)
                (rontolisp::%clojure-rd-integer token u n 10))
           (rontolisp::%clojure-rd-signed
            (rontolisp::%clojure-rd-integer token u n 8) neg))
          ((char= last #\M)
           (let ((decimal (rontolisp::%clojure-rd-decimal token (- n 1))))
             (if decimal
                 (let ((exact
                        (* (car (cdr decimal))
                           (expt 10 (car (cdr (cdr decimal)))))))
                   (if (car decimal) (- exact) exact))
                 nil)))
          ((or (search "." token) (search "e" token) (search "E" token))
           (let ((decimal
                  (if (char= last #\N)
                      nil
                      (rontolisp::%clojure-rd-decimal token
                                                      (if (or (char= last #\f)
                                                              (char= last #\F)
                                                              (char= last #\d)
                                                              (char= last #\D))
                                                          (- n 1)
                                                          n)))))
             (if decimal
                 ;; the nearest double, ties to even like parseDouble, negated
                 ;; after the conversion so -0.0 keeps its sign
                 (let ((magnitude
                        (%decimal-double (car (cdr decimal))
                                         (car (cdr (cdr decimal))))))
                   (if (car decimal) (- magnitude) magnitude))
                 nil)))
          (t (rontolisp::%clojure-rd-integer token 0 big-end 10)))))

(defun rontolisp::%clojure-rd-ratio-chars-p (token)
  "Whether TOKEN holds only digits, slashes and signs (isDecimalRatio)."
  (let ((ok t))
    (do ((i 0 (+ i 1)))
        ((or (not ok) (>= i (length token))) ok)
      (let ((c (char token i)))
        (if (not
             (or (rontolisp::%clojure-rd-digit c 10) (char= c #\/) (char= c #\+)
                 (char= c #\-)))
            (setq ok nil))))))

(defun rontolisp::%clojure-rd-signed (value neg)
  "VALUE negated when NEG; NIL stays NIL."
  (if (and value neg) (- value) value))

(defun rontolisp::%clojure-rd-decimal (token end)
  "The decimal the characters of TOKEN below END spell -- a sign, digits
   with at most one dot (a digit at least), an optional exponent -- as
   (negative-p mantissa k), the magnitude MANTISSA * 10^K; or NIL."
  (let ((i 0) (neg nil) (mantissa 0) (scale 0) (digits 0) (exponent 0) (ok t))
    (if (and (< i end)
             (or (char= (char token i) #\+) (char= (char token i) #\-)))
        (progn
          (setq neg (char= (char token i) #\-))
          (setq i (+ i 1))))
    (do ()
        ((not (and (< i end) (rontolisp::%clojure-rd-digit (char token i) 10))))
      (setq mantissa
       (+ (* mantissa 10) (rontolisp::%clojure-rd-digit (char token i) 10)))
      (setq digits (+ digits 1))
      (setq i (+ i 1)))
    (if (and (< i end) (char= (char token i) #\.))
        (progn
          (setq i (+ i 1))
          (do ()
              ((not
                (and (< i end)
                     (rontolisp::%clojure-rd-digit (char token i) 10))))
            (setq mantissa
                  (+ (* mantissa 10)
                     (rontolisp::%clojure-rd-digit (char token i) 10)))
            (setq digits (+ digits 1))
            (setq scale (+ scale 1))
            (setq i (+ i 1)))))
    (if (and (< i end)
             (or (char= (char token i) #\e) (char= (char token i) #\E)))
        (let ((e (rontolisp::%clojure-rd-integer token (+ i 1) end 10)))
          (if e
              (progn
                (setq exponent e)
                (setq i end))
              (setq ok nil))))
    (if (and ok (= i end) (> digits 0))
        (list neg mantissa (- exponent scale))
        nil)))

(defun rontolisp::%clojure-rd-keyword (rd token)
  "The keyword TOKEN spells: ::name in the calling namespace, ::alias/name
   through its aliases (its own name and the libraries known without a
   require resolve too), anything else verbatim; the source reader's
   refusals otherwise."
  (let ((ctx (cdr rd)))
    (if (< (length token) 2)
        (error "~A" (concatenate 'string "a keyword needs a name: " token)))
    (if (not (char= (char token 1) #\:))
        (list :C%KEYWORD (subseq token 1))
        (let* ((rest (subseq token 2)) (slash (search "/" rest)))
          (cond ((null slash)
                 (if (= (length rest) 0)
                     (error "~A" (concatenate 'string "Invalid token: " token)))
                 (list :C%KEYWORD (concatenate 'string (car ctx) "/" rest)))
                (t (let* ((alias (subseq rest 0 slash))
                          (tail (subseq rest (+ slash 1)))
                          (ns (rontolisp::%clojure-rd-alias ctx alias)))
                     (if (or (= (length alias) 0) (= (length tail) 0)
                             (search "/" tail) (null ns))
                         (error "~A"
                                (concatenate 'string "Invalid token: " token)))
                     (list :C%KEYWORD (concatenate 'string ns "/" tail)))))))))

(defun rontolisp::%clojure-rd-alias (ctx alias)
  "The namespace ALIAS names in CTX: an alias of the calling namespace, the
   namespace's own name, or a library known without a require (the libraries
   ClojureNamespaceLowering.isKnownNamespace names); else NIL."
  (let ((found nil))
    (dolist (pair (cdr ctx))
      (if (and (null found) (string= (car pair) alias))
          (setq found (car (cdr pair)))))
    (cond (found found)
          ((or (string= alias (car ctx)) (string= alias "clojure.string")
               (string= alias "clojure.set") (string= alias "clojure.java.io")
               (string= alias "clojure.test"))
           alias)
          (t nil))))

(defun rontolisp::%clojure-rd-dispatch (rd)
  "A # form, the hash consumed: #' #_ #( #{ #\" #^ and a record literal;
   anything else is the source reader's refusal."
  (let ((c (rontolisp::%clojure-rd-peek rd)))
    (cond ((null c) (error "EOF while reading"))
          ((char= c #\')
           (rontolisp::%clojure-rd-next rd)
           (rontolisp::%clojure-rd-wrap rd "var"))
          ((char= c #\_)
           (rontolisp::%clojure-rd-next rd)
           (rontolisp::%clojure-rd-required rd)
           :C%READ-SKIP)
          ((char= c #\()
           (rontolisp::%clojure-rd-next rd)
           (cons (rontolisp::%clojure-rd-symbol "fn")
                 (cons (rontolisp::%clojure-rd-symbol "%anon")
                       (rontolisp::%clojure-rd-seq rd #\)))))
          ((char= c #\{)
           (rontolisp::%clojure-rd-next rd)
           (rontolisp::%clojure-rd-set rd))
          ((char= c #\")
           (rontolisp::%clojure-rd-next rd)
           (rontolisp::%clojure-rd-regex rd))
          ((char= c #\^)
           (rontolisp::%clojure-rd-next rd)
           (rontolisp::%clojure-rd-meta rd))
          ((alpha-char-p c) (rontolisp::%clojure-rd-record rd))
          (t (error "~A"
              (concatenate 'string "unsupported reader form #" (string c)))))))

(defun rontolisp::%clojure-rd-regex (rd)
  "A regex literal, its #\" consumed: the source verbatim up to the closing
   quote (an escaped character stays escaped, \\Q copies raw through \\E),
   compiled like the source reader's literal."
  (let ((chars nil) (done nil))
    (do ()
        (done
         (rontolisp::%clojure-re-compile (coerce (nreverse chars) 'string)))
      (let ((c (rontolisp::%clojure-rd-next rd)))
        (cond ((null c) (error "EOF while reading regex"))
              ((char= c #\") (setq done t))
              ((char= c #\\)
               (let ((e (rontolisp::%clojure-rd-next rd)))
                 (if (null e) (error "EOF while reading regex"))
                 (setq chars (cons e (cons c chars)))
                 (if (char= e #\Q)
                     (setq chars
                           (rontolisp::%clojure-rd-regex-quoted rd chars)))))
              (t (setq chars (cons c chars))))))))

(defun rontolisp::%clojure-rd-regex-quoted (rd chars)
  "A \\Q span's raw characters onto CHARS (reversed), through the closing \\E."
  (let ((done nil))
    (do ()
        (done chars)
      (let ((c (rontolisp::%clojure-rd-next rd)))
        (if (null c) (error "EOF while reading regex"))
        (setq chars (cons c chars))
        (if (and (char= c #\\) (eql (rontolisp::%clojure-rd-peek rd) #\E))
            (progn
              (setq chars (cons (rontolisp::%clojure-rd-next rd) chars))
              (setq done t)))))))

(defun rontolisp::%clojure-rd-record (rd)
  "A record literal #ns.Name{:k v ...} or #ns.Name[v ...], the hash consumed:
   the record over the body read as data (never evaluated); an undotted tag
   is the source reader's refusal."
  (let ((tag
         (rontolisp::%clojure-rd-token rd (rontolisp::%clojure-rd-next rd))))
    (if (not (search "." tag))
        (if (or (string= tag "inst") (string= tag "uuid"))
            (error "~A" (concatenate 'string "unsupported reader form #" tag))
            (error "~A"
                   (concatenate 'string "No reader function for tag " tag))))
    (rontolisp::%clojure-rd-skip rd)
    (let ((c (rontolisp::%clojure-rd-peek rd)))
      (cond ((eql c #\[)
             (rontolisp::%clojure-rd-next rd)
             (rontolisp::%clojure-rd-build-record tag
              (rontolisp::%clojure-rd-seq rd #\]) nil))
            ((eql c #\{)
             (rontolisp::%clojure-rd-next rd)
             (rontolisp::%clojure-rd-build-record tag
              (rontolisp::%clojure-rd-seq rd #\}) t))
            (t (error "~A"
                      (concatenate 'string
                       "Unreadable constructor form starting with \"#" tag
                       "\"")))))))

(defun rontolisp::%clojure-rd-build-record (tag items map-body)
  "The record of class TAG over the body ITEMS: keyword/value pairs when
   MAP-BODY (the declared fields first, nil until named, then the extension
   keys in body order -- an equal table keeps a key where it first went in),
   else one value per declared field."
  (if map-body
      (let ((seen nil))
        (if (oddp (length items))
            (error "Map literal must contain an even number of forms"))
        (do ((rest items (cdr (cdr rest))))
            ((null rest))
          (if (not (rontolisp::%clojure-keyword-p (car rest)))
              (error "~A"
                     (concatenate 'string
                      "Unreadable defrecord form: key must be of type clojure.lang.Keyword, got "
                      (rontolisp::%clojure-str-of (car rest) "nil" nil))))
          (dolist (k seen)
            (if (equal k (car rest))
                (error "~A"
                       (concatenate 'string "Duplicate key: :"
                                    (car (cdr (car rest)))))))
          (setq seen (cons (car rest) seen)))))
  (let ((entry nil))
    (dolist (e rontolisp::%clojure-read-records)
      (if (and (null entry) (string= (car e) tag)) (setq entry e)))
    (if (null entry)
        (error "~A"
               (concatenate 'string
                "a record literal needs a defined record class, not " tag)))
    (if (null (car (cdr (cdr (cdr entry)))))
        (error "~A"
         (concatenate 'string "a deftype literal is not supported yet: " tag)))
    (let ((keys
           (mapcar (lambda (f) (list :C%KEYWORD f)) (car (cdr (cdr entry)))))
          (table (make-hash-table :test 'equal)))
      (dolist (k keys) (setf (gethash k table) nil))
      (if map-body
          (do ((rest items (cdr (cdr rest))))
              ((null rest))
            (setf (gethash (car rest) table) (car (cdr rest))))
          (progn
            (if (/= (length items) (length keys))
                (error "~A"
                       (concatenate 'string
                        "Unexpected number of constructor arguments to class "
                        tag ": got "
                        (rontolisp::%clojure-str-of (length items) "nil" nil))))
            (do ((k keys (cdr k)) (v items (cdr v)))
                ((null k))
              (setf (gethash (car k) table) (car v)))))
      (list :C%RECORD (list :C%KEYWORD (car (cdr entry))) keys table tag))))

(defun rontolisp::%clojure-rd-index (s c start)
  "The index of the first character C in S at or after START, or -1."
  (let ((at -1))
    (do ((i start (+ i 1)))
        ((or (>= at 0) (>= i (length s))) at)
      (if (char= (char s i) c) (setq at i)))))

(defun rontolisp::%clojure-read-from (rd eof-error eof-value)
  "One datum from RD, past whitespace and #_ discards: at the end of input
   before a datum, EOF-VALUE, or the oracle's error when EOF-ERROR; an end
   inside a datum always signals."
  (let ((form :C%READ-SKIP) (eof nil))
    (do ()
        ((or eof (not (eq form :C%READ-SKIP))) (if eof eof-value form))
      (rontolisp::%clojure-rd-skip rd)
      (if (null (rontolisp::%clojure-rd-peek rd))
          (if eof-error (error "EOF while reading") (setq eof t))
          (setq form (rontolisp::%clojure-rd-form rd))))))

(defun rontolisp::%clojure-read-opt-eof (opts)
  "The (eof-error . eof-value) an options map OPTS asks for: an :eof entry
   answers its value at the end of input, its absence signals."
  (let ((miss (list nil)))
    (let ((v
           (if (hash-table-p opts)
               (gethash (list :C%KEYWORD "eof") opts miss)
               miss)))
      (if (eq v miss) (cons t nil) (cons nil v)))))

(defun rontolisp::%clojure-read-string (s ctx)
  "(read-string s): the first datum of the string S, CTX the calling
   namespace context."
  (if (not (stringp s)) (error "read-string needs a string"))
  (rontolisp::%clojure-read-from (cons (cons s 0) ctx) t nil))

(defun rontolisp::%clojure-read-string-opts (opts s ctx)
  "(read-string opts s): the first datum of the string S, the options map
   OPTS deciding the end of input."
  (if (not (stringp s)) (error "read-string needs a string"))
  (let ((eof (rontolisp::%clojure-read-opt-eof opts)))
    (rontolisp::%clojure-read-from (cons (cons s 0) ctx) (car eof) (cdr eof))))

(defun rontolisp::%clojure-read-stream (x)
  "X when it is a character input stream; anything else signals."
  (if (streamp x)
      x
      (error
       "read needs a reader: a clojure.java.io/reader, a PushbackReader over one, or *in*")))

(defun rontolisp::%clojure-read (stream eof-error eof-value ctx)
  "(read stream eof-error? eof-value): one datum from STREAM, which is left
   right after it; EOF-ERROR is truthy the Clojure way."
  (rontolisp::%clojure-read-from
   (cons (rontolisp::%clojure-read-stream stream) ctx)
   (rontolisp::%clojure-truthy eof-error) eof-value))

(defun rontolisp::%clojure-read-opts (opts stream ctx)
  "(read opts stream): one datum from STREAM, the options map OPTS deciding
   the end of input."
  (let ((eof (rontolisp::%clojure-read-opt-eof opts)))
    (rontolisp::%clojure-read-from
     (cons (rontolisp::%clojure-read-stream stream) ctx) (car eof) (cdr eof))))

(defun rontolisp::%clojure-read-string-v (ctx args)
  "read-string as a value over ARGS, the count checked at run time."
  (if (= (rontolisp::%clojure-check-arity args 1 2 "read-string") 1)
      (rontolisp::%clojure-read-string (car args) ctx)
      (rontolisp::%clojure-read-string-opts (car args) (car (cdr args)) ctx)))

(defun rontolisp::%clojure-read-v (ctx args)
  "read as a value over ARGS, the count checked at run time: none reads *in*,
   two are options and a stream, three or four a stream, the end-of-input
   flag and value (the recursive flag ignored)."
  (let ((n (rontolisp::%clojure-check-arity args 0 4 "read")))
    (cond ((= n 0) (rontolisp::%clojure-read *standard-input* t nil ctx))
     ((= n 1) (rontolisp::%clojure-read (car args) t nil ctx))
     ((= n 2) (rontolisp::%clojure-read-opts (car args) (car (cdr args)) ctx))
     (t (rontolisp::%clojure-read (car args) (car (cdr args))
                                  (car (cdr (cdr args))) ctx)))))
