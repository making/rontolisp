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
  "Whether X can close a cycle: a pair, a non-string vector, or a table."
  (or (consp x) (and (vectorp x) (not (stringp x))) (hash-table-p x)))

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
