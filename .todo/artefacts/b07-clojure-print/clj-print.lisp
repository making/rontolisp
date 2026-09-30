;; Spike: a Clojure print wrapper in plain Common Lisp.
;;
;; Question: can the CL-notation印字 (vectors as #(..), maps as #<HASH-TABLE ..>,
;; keywords nested as (C%KEYWORD ..), T for true, quoted symbols as c%foo) be
;; hidden behind a Lisp-level printer, the way scheme.lisp wraps the CL printer
;; for Scheme (rontolisp::%scheme-write)? If yes, Clojure's println/print/pr/prn,
;; str and the REPL echo can lower to calls into a spliced clojure.lisp instead
;; of princ-to-string/prin1-to-string, with no backend learning a Clojure name.
;;
;; Scope: readable + plain rendering of every value shape the lowering produces
;; (see .kb/clojure-frontend.md lowering table). Deliberately OUT: cycle labels
;; (depth cap only; Scheme needed ~130 lines for labels), empty-list-vs-nil
;; (nil IS the empty list here, so it stays "nil"), map/set walk order
;; (unspecified, same as keys/vals today), unreadable fallbacks.
;;
;; Portable CL on purpose: the same file must run on SBCL (logic check) and on
;; rontolisp's interpreter/JVM/WASM (backend check), since a real clojure.lisp
;; would be spliced Common Lisp like scheme.lisp.

(defvar *clj-false* nil
  "The Clojure false object: the value of rontolisp::%clojure-false.
   The driver binds it; a spliced library would read the global directly.")

(defvar *clj-depth-limit* 50
  "Nesting cap so a cyclic value prints finitely in the spike.
   The real printer needs Scheme-style datum labels, not a cap.")

(defun clj%keyword-p (x)
  "The (:C%KEYWORD name) wrapper ClojureLowering lowers keywords to."
  (and (consp x)
       (eq (car x) :C%KEYWORD)
       (consp (cdr x))
       (stringp (car (cdr x)))
       (null (cdr (cdr x)))))

(defun clj%set-p (x)
  "The (:C%SET table) wrapper ClojureLowering lowers sets to."
  (and (consp x)
       (eq (car x) :C%SET)
       (consp (cdr x))
       (hash-table-p (car (cdr x)))
       (null (cdr (cdr x)))))

(defun clj%demangle (name)
  "Undo ClojureLowering.mangle: strip the c% prefix, %% -> %, %c -> :.
   Case-sensitive on purpose: CL symbols are upcased, so C%.. is never ours."
  (if (not (and (>= (length name) 2)
                (char= (char name 0) #\c)
                (char= (char name 1) #\%)))
      name
      (let ((out nil) (i 2) (n (length name)))
        (do () ((>= i n) (coerce (nreverse out) 'string))
          (let ((c (char name i)))
            (cond ((and (char= c #\%) (< (+ i 1) n)
                        (let ((d (char name (+ i 1))))
                          (or (char= d #\c) (char= d #\%))))
                   (setq out (cons (if (char= (char name (+ i 1)) #\c)
                                       #\:
                                       #\%)
                                   out))
                   (setq i (+ i 2)))
                  (t
                   (setq out (cons c out))
                   (setq i (+ i 1)))))))))

(defun clj%write-string-datum (s stream)
  "A readable string: double quotes with \" \\ \\n \\t \\r \\b \\f and \\uXXXX."
  (write-char #\" stream)
  (do ((i 0 (+ i 1)))
      ((>= i (length s)))
    (let ((c (char s i)) (code (char-code (char s i))))
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
            (t (write-char c stream)))))
  (write-char #\" stream))

(defun clj%list-cycle-start (x)
  "The cell a cdr cycle restarts at, or NIL when the spine ends.
   Floyd in two phases (cf. RenderCycleGuard's Floyd prewalk and
   %scheme-may-cycle-p): without this, a cdr cycle loops at one depth
   forever -- a depth cap alone cannot see it. Measured with SBCL: the
   naive walk exhausted 500 MB on (let ((x (list 1))) (setf (cdr x) x) x)."
  (let ((tortoise x) (hare x) (met nil))
    (do () ((or met (not (consp hare))) nil)
      (setq hare (cdr hare))
      (if (consp hare)
          (progn
            (setq hare (cdr hare))
            (setq tortoise (cdr tortoise))
            (if (eq hare tortoise) (setq met t)))))
    (if (not met)
        nil
        (let ((ptr x))
          (do () ((eq ptr hare) ptr)
            (setq ptr (cdr ptr))
            (setq hare (cdr hare)))))))

(defun clj%write-char-datum (c stream)
  "A readable character: \\a, \\newline/space/tab/return, else \\X."
  (write-char #\\ stream)
  (let ((code (char-code c)))
    (cond ((= code 32) (write-string "space" stream))
          ((= code 10) (write-string "newline" stream))
          ((= code 9) (write-string "tab" stream))
          ((= code 13) (write-string "return" stream))
          (t (write-char c stream)))))

(defun clj%print-datum (x readable stream depth)
  "Write X in Clojure notation. READABLE selects pr-side (quoted strings,
   \\chars) vs print-side (bare). Returns NIL so callers stay effect-style."
  (cond ((eq x t) (write-string "true" stream))
        ((eq x *clj-false*) (write-string "false" stream))
        ((null x) (write-string "nil" stream))
        ((clj%keyword-p x)
         (write-char #\: stream)
         (write-string (car (cdr x)) stream))
        ((clj%set-p x)
         (write-string "#{" stream)
         (let ((first t))
           (maphash (lambda (k v)
                      (declare (ignore v))
                      (if first (setq first nil) (write-char #\Space stream))
                      (clj%print-datum k readable stream (+ depth 1)))
                    (car (cdr x))))
         (write-char #\} stream))
        ((and (>= depth *clj-depth-limit*)) (write-char #\# stream))
        ((stringp x)
         (if readable (clj%write-string-datum x stream) (write-string x stream)))
        ((characterp x)
         (if readable (clj%write-char-datum x stream) (write-char x stream)))
        ((symbolp x)
         (cond ((keywordp x)
                ;; A bare :kw symbol (never produced by the lowering, but cheap
                ;; to keep honest). symbol-name keeps the colon on rontolisp
                ;; and drops it on SBCL -- print exactly one.
                (write-char #\: stream)
                (let ((name (symbol-name x)))
                  (if (and (> (length name) 0) (char= (char name 0) #\:))
                      (write-string (subseq name 1) stream)
                      (write-string name stream))))
               (t (write-string (clj%demangle (symbol-name x)) stream))))
        ((hash-table-p x)
         (write-char #\{ stream)
         (let ((first t))
           (maphash (lambda (k v)
                      (if first (setq first nil) (write-string ", " stream))
                      (clj%print-datum k readable stream (+ depth 1))
                      (write-char #\Space stream)
                      (clj%print-datum v readable stream (+ depth 1)))
                    x))
         (write-char #\} stream))
        ((and (vectorp x) (not (stringp x)))
         (write-char #\[ stream)
         (do ((i 0 (+ i 1)))
             ((>= i (length x)))
           (if (> i 0) (write-char #\Space stream))
           (clj%print-datum (aref x i) readable stream (+ depth 1)))
         (write-char #\] stream))
        ((consp x)
         (let ((start (clj%list-cycle-start x)))
           (write-char #\( stream)
           (do ((cell x) (first t) (done nil)) (done)
             (if first (setq first nil) (write-char #\Space stream))
             (clj%print-datum (car cell) readable stream (+ depth 1))
             (setq cell (cdr cell))
             (cond ((and start (eq cell start))
                    ;; Second arrival at the cycle-start cell: RenderCycleGuard
                    ;; spells it " . #", e.g. (1 . #).
                    (write-string " . #" stream)
                    (setq done t))
                   ((not (consp cell))
                    (if (not (null cell))
                        (progn
                          (write-string " . " stream)
                          (clj%print-datum cell readable stream (+ depth 1))))
                    (setq done t))))
           (write-char #\) stream)))
        ((numberp x) (princ x stream))
        ((functionp x) (write-string "#<procedure>" stream))
        (t (princ x stream)))
  nil)

(defun clj%to-string (x readable nil-replacement)
  "The str/print building block: false->false, T->true, NIL->replacement
   (\"\" for str, \"nil\" for print/pr), keywords with colon, else the datum.
   Returns a string via with-output-to-string (what SourceSession's Scheme echo
   already pays; .kb/pretty-printer.md notes the WASM gate cost)."
  (cond ((eq x *clj-false*) "false")
        ((eq x t) "true")
        ((null x) nil-replacement)
        ((clj%keyword-p x)
         (concatenate 'string ":" (car (cdr x))))
        (t (with-output-to-string (s) (clj%print-datum x readable s 0)))))
