;; The run-time half of the EXPERIMENTAL Scheme front end (am.ik.rontolisp.scheme),
;; written in Common Lisp so one definition runs on every backend and no emitter
;; learns a Scheme name: the interpreter loads it on the first resolution of a
;; rontolisp::%scheme- function, the compile path splices it when the program
;; references one (SchemeLibrary.java) and the tree-shaker drops what stays
;; unreachable.
;;
;; The value model these helpers share with the lowering (.kb/scheme-frontend.md):
;; '() is NIL, #t is T, and #f is the value of rontolisp::%scheme-false -- a DISTINCT
;; non-NIL object the lowered program binds before anything else runs. A helper
;; answering a Common Lisp boolean (T/NIL) is declared `pred` or `or-false` in
;; SchemeBuiltins, which converts at the call site; every other helper answers
;; Scheme values itself.
;;
;; Symbol names follow SchemeNames.mangle: a spelling that could collide with a
;; canonical name is escaped behind "s%" (with % -> %%, : -> %c). The rule is spelled
;; TWICE, there and in %scheme-needs-escape below; change the two together.

;; --- symbols ------------------------------------------------------------------

(defun rontolisp::%scheme-escaped-p (name)
  (and (>= (length name) 2) (char= (char name 0) #\s)
       (char= (char name 1) #\%)))

(defun rontolisp::%scheme-has-lowercase (name)
  (do ((i 0 (+ i 1)))
      ((>= i (length name)) nil)
    (let ((c (char name i)))
      (if (and (char>= c #\a) (char<= c #\z)) (return t)))))

(defun rontolisp::%scheme-needs-escape (name)
  (or (rontolisp::%scheme-escaped-p name)
      (and (> (length name) 0) (char= (char name 0) #\&)) (string= name "#f")
      (string= name "#!unspecific") (find #\: name)
      (not (rontolisp::%scheme-has-lowercase name))))

(defun rontolisp::%scheme-symbol->string (symbol)
  (let ((name (symbol-name symbol)))
    (if (rontolisp::%scheme-escaped-p name)
        (let ((out nil) (n (length name)))
          (do ((i 2 (+ i 1)))
              ((>= i n))
            (let ((c (char name i)))
              (if (and (char= c #\%) (< (+ i 1) n))
                  (progn
                    (setq i (+ i 1))
                    (setq out
                          (cons (if (char= (char name i) #\c) #\: (char name i))
                                out)))
                  (setq out (cons c out)))))
          (coerce (nreverse out) 'string))
        (copy-seq name))))

(defun rontolisp::%scheme-string->symbol (name)
  (if (rontolisp::%scheme-needs-escape name)
      (let ((out (list #\% #\s)))
        (do ((i 0 (+ i 1)))
            ((>= i (length name)))
          (let ((c (char name i)))
            (cond ((char= c #\%) (setq out (cons #\% (cons #\% out))))
                  ((char= c #\:) (setq out (cons #\c (cons #\% out))))
                  (t (setq out (cons c out))))))
        (intern (coerce (nreverse out) 'string)))
      (intern name)))

;; --- write / display ------------------------------------------------------------

(defun rontolisp::%scheme-write-char-datum (c)
  (write-string "#\\")
  (let ((code (char-code c)))
    (cond ((= code 32) (write-string "space"))
          ((= code 10) (write-string "newline"))
          ((= code 9) (write-string "tab"))
          ((= code 13) (write-string "return"))
          ((= code 0) (write-string "null"))
          ((= code 7) (write-string "alarm"))
          ((= code 8) (write-string "backspace"))
          ((= code 27) (write-string "escape"))
          ((= code 127) (write-string "delete"))
          (t (write-char c)))))

(defun rontolisp::%scheme-write-string-datum (s)
  (write-char #\")
  (do ((i 0 (+ i 1)))
      ((>= i (length s)))
    (let ((c (char s i)))
      (let ((code (char-code c)))
        (cond ((= code 34) (write-string "\\\""))
              ((= code 92) (write-string "\\\\"))
              ((= code 10) (write-string "\\n"))
              ((= code 9) (write-string "\\t"))
              ((= code 13) (write-string "\\r"))
              (t (write-char c))))))
  (write-char #\"))

;; Writes a symbol's Scheme spelling WITHOUT building it: the printer is in nearly every
;; program, and a string built from a character list drags the sequence runtime in
;; (measured 2026-09-17: a lone (display x) was 70 KB of class and 17.5 KB of wasm).
(defun rontolisp::%scheme-print-symbol (symbol)
  (let ((name (symbol-name symbol)))
    (if (rontolisp::%scheme-escaped-p name)
        (let ((n (length name)))
          (do ((i 2 (+ i 1)))
              ((>= i n))
            (let ((c (char name i)))
              (if (and (char= c #\%) (< (+ i 1) n))
                  (progn
                    (setq i (+ i 1))
                    (write-char
                     (if (char= (char name i) #\c) #\: (char name i))))
                  (write-char c)))))
        (write-string name))))

;; write and display must terminate on a circular structure (R7RS 6.13.3): a node a
;; cycle closes on is written with a datum label, #0=(1 2 . #0#). Sharing without a
;; cycle is written out each time, as write does; write-shared instead labels every
;; node occurring more than once (%scheme-write-shared below).
;;
;; No eq hash table: on wasm one costs 16 KB of every printing program and scans a
;; single bucket for an aggregate key, which made writing a 50,000-element list take
;; a minute (measured 2026-09-17). Instead the datum is first walked as the tree write
;; would print, which costs no more than the printing; only a structure that walk
;; cannot finish is searched for its cycles, with a list of the nodes seen.
(defun rontolisp::%scheme-print (x escape)
  (rontolisp::%scheme-print-datum x escape
                                  (if (and (rontolisp::%scheme-node-p x)
                                       (rontolisp::%scheme-may-cycle-p x 1000))
                                      (rontolisp::%scheme-cycle-labels x)))
  nil)

(defun rontolisp::%scheme-node-p (x)
  (or (consp x) (and (vectorp x) (not (stringp x)))))

;; Walks X as a tree and answers NIL when the walk ends, T as soon as it may not: a cdr
;; chain that meets itself (Brent's cycle detection), or car/element nesting deeper
;; than DEPTH -- where every cycle through a car or an element ends up, since the walk
;; of that car never returns.
(defun rontolisp::%scheme-may-cycle-p (x depth)
  (if (< depth 0)
      t
      (let ((tortoise x) (power 1) (steps 0) (cycle nil))
        (do ()
            ((or cycle (not (rontolisp::%scheme-node-p x))) cycle)
          (if (consp x)
              (if (and (rontolisp::%scheme-node-p (car x))
                       (rontolisp::%scheme-may-cycle-p (car x) (- depth 1)))
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
                              (setq steps 0))))))
              (let ((v x))
                (setq x nil)
                (do ((i 0 (+ i 1)))
                    ((or cycle (>= i (length v))))
                  (if (and (rontolisp::%scheme-node-p (aref v i))
                       (rontolisp::%scheme-may-cycle-p (aref v i) (- depth 1)))
                      (setq cycle t)))))))))

;; The entry (node . state) of X in the list ENTRIES, or NIL.
(defun rontolisp::%scheme-entry (x entries)
  (do ((l entries (cdr l))) ((or (null l) (eq (car (car l)) x)) (car l))))

;; A depth-first walk recording each node in (car SEEN) with a state: 1 while its walk
;; is open, 2 once closed; 3 and 4 the same for a node reached again while open -- one
;; a cycle closes on. The cdr direction is a loop.
(defun rontolisp::%scheme-mark-cycles (x seen)
  (let ((spine nil))
    (do ()
        ((not (rontolisp::%scheme-node-p x)))
      (let ((entry (rontolisp::%scheme-entry x (car seen))))
        (cond ((null entry)
               (setq entry (cons x 1))
               (rplaca seen (cons entry (car seen)))
               (setq spine (cons entry spine))
               (if (consp x)
                   (progn
                     (rontolisp::%scheme-mark-cycles (car x) seen)
                     (setq x (cdr x)))
                   (let ((v x))
                     (setq x nil)
                     (do ((i 0 (+ i 1)))
                         ((>= i (length v)))
                       (rontolisp::%scheme-mark-cycles (aref v i) seen)))))
              (t
               (if (= (cdr entry) 1) (rplacd entry 3))
               (setq x nil)))))
    (do ((l spine (cdr l)))
        ((null l))
      (rplacd (car l) (if (= (cdr (car l)) 3) 4 2)))))

;; The labels X needs, as (entries . next-number): entries (node . 4) for each node a
;; cycle closes on, or NIL when there is none (a nesting past the walk's depth).
(defun rontolisp::%scheme-cycle-labels (x)
  (let ((seen (list nil)) (labeled nil))
    (rontolisp::%scheme-mark-cycles x seen)
    (do ((l (car seen) (cdr l)))
        ((null l))
      (if (= (cdr (car l)) 4) (setq labeled (cons (car l) labeled))))
    (if labeled (cons labeled 0))))

;; write-shared labels every pair or vector that occurs more than once, not only the
;; nodes a cycle closes on (R7RS 6.13.3). Counting is one depth-first walk beside the
;; cycle walk above: a revisit -- of an open node (a cycle) or a closed one (sharing)
;; counts without recursing, so the walk ends on a cycle and visits each node once,
;; and only the outermost shared node takes a label, as in (#0=(1 2) #0#).
(defun rontolisp::%scheme-count-shared (x seen)
  (do ()
      ((not (rontolisp::%scheme-node-p x)))
    (let ((entry (rontolisp::%scheme-entry x (car seen))))
      (cond ((null entry)
             (rplaca seen (cons (cons x 1) (car seen)))
             (if (consp x)
                 (progn
                   (rontolisp::%scheme-count-shared (car x) seen)
                   (setq x (cdr x)))
                 (let ((v x))
                   (setq x nil)
                   (do ((i 0 (+ i 1)))
                       ((>= i (length v)))
                     (rontolisp::%scheme-count-shared (aref v i) seen)))))
            (t
             (rplacd entry (+ (cdr entry) 1))
             (setq x nil))))))

;; The labels X needs for write-shared, as (entries . next-number): entries (node . 4)
;; for each node occurring more than once, or NIL when there is none -- the shape
;; %scheme-print-datum already prints.
(defun rontolisp::%scheme-shared-labels (x)
  (let ((seen (list nil)) (labeled nil))
    (rontolisp::%scheme-count-shared x seen)
    (do ((l (car seen) (cdr l)))
        ((null l))
      (if (> (cdr (car l)) 1)
          (setq labeled (cons (cons (car (car l)) 4) labeled))))
    (if labeled (cons labeled 0))))

;; Whether X carries a label: one to define (4) or one already written (negative).
(defun rontolisp::%scheme-labeled-p (x labels)
  (rontolisp::%scheme-entry x (car labels)))

;; Writes X's label: #n= before its first appearance (answering NIL, the datum follows),
;; #n# after it (answering T, nothing more to write).
(defun rontolisp::%scheme-print-label (x labels)
  (let ((entry (rontolisp::%scheme-entry x (car labels))))
    (cond ((null entry) nil)
          ((= (cdr entry) 4)
           (let ((n (cdr labels)))
             (rplacd entry (- -1 n))
             (rplacd labels (+ n 1))
             (write-char #\#)
             (princ n)
             (write-char #\=)
             nil))
          (t
           (write-char #\#)
           (princ (- -1 (cdr entry)))
           (write-char #\#)
           t))))

(defun rontolisp::%scheme-print-datum (x escape labels)
  (cond ((eq x t) (write-string "#t"))
        ((eq x rontolisp::%scheme-false) (write-string "#f"))
        ((null x) (write-string "()"))
        ((rontolisp::%scheme-eof-p x) (write-string "#<eof>"))
        ((symbolp x) (rontolisp::%scheme-print-symbol x))
        ((stringp x)
         (if escape (rontolisp::%scheme-write-string-datum x) (write-string x)))
        ((characterp x)
         (if escape (rontolisp::%scheme-write-char-datum x) (write-char x)))
        ((and labels (rontolisp::%scheme-node-p x)
              (rontolisp::%scheme-print-label x labels)))
        ((consp x)
         (write-char #\()
         (rontolisp::%scheme-print-datum (car x) escape labels)
         (do ((rest (cdr x) (cdr rest)))
             ((or (not (consp rest))
                  (and labels (rontolisp::%scheme-labeled-p rest labels)))
              (if (not (null rest))
                  (progn
                    (write-string " . ")
                    (rontolisp::%scheme-print-datum rest escape labels))))
           (write-char #\Space)
           (rontolisp::%scheme-print-datum (car rest) escape labels))
         (write-char #\)))
        ((vectorp x)
         (write-string "#(")
         (do ((i 0 (+ i 1)))
             ((>= i (length x)))
           (if (> i 0) (write-char #\Space))
           (rontolisp::%scheme-print-datum (aref x i) escape labels))
         (write-char #\)))
        ((floatp x) (rontolisp::%scheme-print-flonum x))
        ((rontolisp::%scheme-promise-p x) (write-string "#<promise>"))
        ((functionp x) (write-string "#<procedure>"))
        (t (princ x))))

(defun rontolisp::%scheme-display (x) (rontolisp::%scheme-print x nil))

(defun rontolisp::%scheme-write (x) (rontolisp::%scheme-print x t))

(defun rontolisp::%scheme-write-shared (x)
  (rontolisp::%scheme-print-datum x t
   (if (rontolisp::%scheme-node-p x) (rontolisp::%scheme-shared-labels x)))
  nil)

;; --- (scheme read): a datum reader on the current input port ----------------------
;;
;; (read) is what makes the book's evaluators usable: a read-eval-print loop over
;; stdin. It is NOT the emitted Common Lisp reader (which upcases and knows #' and
;; packages): a datum comes back as what quoted data lowers to, or (eq? (read) 'quit)
;; is false. So this is a reader in the same language, over read-char on
;; *standard-input*, spliced like the rest of the run-time half, on all four backends.
;;
;; One Lisp-level pushback cell (a list, so "#|" can be un-read as two characters)
;; keyed on the current *standard-input* value: with-input-from-string rebinds the
;; stream, and the cell follows it rather than leaking across bindings (one stream at
;; a time, like CL's unread-char cell). peek is read + pushback, never CL's peek-char,
;; so no WASM peek slot is ever parked and read/read-line/char-ready? mix freely.
;; char-ready? is (listen) where threads exist; on WASM there is no non-blocking probe,
;; so it answers #t (true for a string stream with data and at EOF, the cases the
;; tests pin; a terminal with nothing typed is the stated deviation).

(defstruct (rontolisp::%scheme-eof (:constructor rontolisp::%make-scheme-eof)
                                   (:copier nil)))

(defvar rontolisp::%scheme-eof-instance (rontolisp::%make-scheme-eof))

(defun rontolisp::%scheme-eof-object? (x) (rontolisp::%scheme-eof-p x))

(defvar rontolisp::%scheme-dot (list nil))

(defvar rontolisp::%scheme-close (list nil))

(defvar rontolisp::%scheme-pushback-chars nil)

(defvar rontolisp::%scheme-pushback-stream nil)

(defun rontolisp::%scheme-pushback-sync ()
  (if (and rontolisp::%scheme-pushback-chars
           (not (eq rontolisp::%scheme-pushback-stream *standard-input*)))
      (progn
        (setq rontolisp::%scheme-pushback-chars nil)
        (setq rontolisp::%scheme-pushback-stream nil))))

(defun rontolisp::%scheme-peek-char ()
  (rontolisp::%scheme-pushback-sync)
  (if rontolisp::%scheme-pushback-chars
      (car rontolisp::%scheme-pushback-chars)
      (let ((c (read-char nil nil rontolisp::%scheme-eof-instance)))
        (setq rontolisp::%scheme-pushback-stream *standard-input*)
        (setq rontolisp::%scheme-pushback-chars (list c))
        c)))

(defun rontolisp::%scheme-next-char ()
  (rontolisp::%scheme-pushback-sync)
  (if rontolisp::%scheme-pushback-chars
      (let ((c (car rontolisp::%scheme-pushback-chars)))
        (setq rontolisp::%scheme-pushback-chars
              (cdr rontolisp::%scheme-pushback-chars))
        c)
      (read-char nil nil rontolisp::%scheme-eof-instance)))

(defun rontolisp::%scheme-pushback (c)
  (rontolisp::%scheme-pushback-sync)
  (setq rontolisp::%scheme-pushback-stream *standard-input*)
  (setq rontolisp::%scheme-pushback-chars
        (cons c rontolisp::%scheme-pushback-chars))
  c)

(defun rontolisp::%scheme-whitespace? (c)
  (let ((code (char-code c)))
    (or (= code 32) (= code 9) (= code 10) (= code 13) (= code 12)
        (= code 11))))

(defun rontolisp::%scheme-delimiter? (c)
  (or (rontolisp::%scheme-whitespace? c)
      (let ((code (char-code c)))
        (or (= code 40) (= code 41) (= code 34) (= code 59) (= code 39)
            (= code 96) (= code 44) (= code 124)))))

(defun rontolisp::%scheme-read-error (message datum)
  (error "~A"
   (rontolisp::%scheme-error-message message (if datum (list datum) nil))))

(defun rontolisp::%scheme-skip-atmosphere ()
  (do ()
      (nil)
    (let ((c (rontolisp::%scheme-peek-char)))
      (cond ((rontolisp::%scheme-eof-p c) (return nil))
            ((rontolisp::%scheme-whitespace? c) (rontolisp::%scheme-next-char))
            ((= (char-code c) 59)
             (rontolisp::%scheme-next-char)
             (do ()
                 ((rontolisp::%scheme-eof-p (rontolisp::%scheme-peek-char)))
               (if (= (char-code (rontolisp::%scheme-peek-char)) 10)
                   (return nil)
                   (rontolisp::%scheme-next-char))))
            ((= (char-code c) 35)
             (rontolisp::%scheme-next-char)
             (let ((d (rontolisp::%scheme-peek-char)))
               (cond
                ((and (not (rontolisp::%scheme-eof-p d)) (= (char-code d) 124))
                 (rontolisp::%scheme-next-char)
                 (rontolisp::%scheme-skip-block-comment 1))
                ((and (not (rontolisp::%scheme-eof-p d)) (= (char-code d) 59))
                 (rontolisp::%scheme-next-char)
                 (rontolisp::%scheme-skip-atmosphere)
                 (let ((skipped (rontolisp::%scheme-read-datum)))
                   (if (or (eq skipped rontolisp::%scheme-close)
                           (eq skipped rontolisp::%scheme-dot))
                       (rontolisp::%scheme-read-error "a datum must follow '#;'"
                                                      nil))))
                (t
                 (rontolisp::%scheme-pushback (code-char 35))
                 (return nil)))))
            (t (return nil))))))

(defun rontolisp::%scheme-skip-block-comment (depth)
  (do ()
      ((= depth 0))
    (let ((c (rontolisp::%scheme-next-char)))
      (cond ((rontolisp::%scheme-eof-p c)
             (rontolisp::%scheme-read-error "unterminated '#|' comment" nil))
            ((= (char-code c) 35)
             (let ((d (rontolisp::%scheme-peek-char)))
               (if (and (not (rontolisp::%scheme-eof-p d))
                        (= (char-code d) 124))
                   (progn
                     (rontolisp::%scheme-next-char)
                     (setq depth (+ depth 1))))))
            ((= (char-code c) 124)
             (let ((d (rontolisp::%scheme-peek-char)))
               (if (and (not (rontolisp::%scheme-eof-p d)) (= (char-code d) 35))
                   (progn
                     (rontolisp::%scheme-next-char)
                     (setq depth (- depth 1))))))))))

(defun rontolisp::%scheme-read ()
  (rontolisp::%scheme-skip-atmosphere)
  (let ((c (rontolisp::%scheme-peek-char)))
    (if (rontolisp::%scheme-eof-p c)
        rontolisp::%scheme-eof-instance
        (let ((datum (rontolisp::%scheme-read-datum)))
          (cond ((eq datum rontolisp::%scheme-close)
                 (rontolisp::%scheme-read-error "unexpected ')'" nil))
                ((eq datum rontolisp::%scheme-dot)
                 (rontolisp::%scheme-read-error "unexpected '.'" nil))
                (t datum))))))

(defun rontolisp::%scheme-read-datum ()
  (let ((c (rontolisp::%scheme-peek-char)))
    (cond ((rontolisp::%scheme-eof-p c)
           (rontolisp::%scheme-read-error "unexpected end of input" nil))
          ((= (char-code c) 40)
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-list))
          ((= (char-code c) 41)
           (rontolisp::%scheme-next-char)
           rontolisp::%scheme-close)
          ((= (char-code c) 39)
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-abbrev (quote |quote|)))
          ((= (char-code c) 96)
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-abbrev (quote |quasiquote|)))
          ((= (char-code c) 44)
           (rontolisp::%scheme-next-char)
           (let ((d (rontolisp::%scheme-peek-char)))
             (if (and (not (rontolisp::%scheme-eof-p d)) (= (char-code d) 64))
                 (progn
                   (rontolisp::%scheme-next-char)
                   (rontolisp::%scheme-read-abbrev (quote |unquote-splicing|)))
                 (rontolisp::%scheme-read-abbrev (quote |unquote|)))))
          ((= (char-code c) 34)
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-string))
          ((= (char-code c) 35)
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-hash))
          ((= (char-code c) 91)
           (rontolisp::%scheme-read-error
            "'[' is not a delimiter in R7RS; use parentheses" nil))
          ((= (char-code c) 93)
           (rontolisp::%scheme-read-error
            "']' is not a delimiter in R7RS; use parentheses" nil))
          ((= (char-code c) 123)
           (rontolisp::%scheme-read-error
            "'{' is not a delimiter in R7RS; use parentheses" nil))
          ((= (char-code c) 125)
           (rontolisp::%scheme-read-error
            "'}' is not a delimiter in R7RS; use parentheses" nil))
          ((= (char-code c) 124)
           (rontolisp::%scheme-read-error "|...| identifiers are not supported"
                                          nil))
          (t (rontolisp::%scheme-read-atom)))))

(defun rontolisp::%scheme-read-abbrev (operator)
  (rontolisp::%scheme-skip-atmosphere)
  (let ((datum (rontolisp::%scheme-read-datum)))
    (if (or (eq datum rontolisp::%scheme-close)
            (eq datum rontolisp::%scheme-dot))
        (rontolisp::%scheme-read-error "a datum must follow the abbreviation"
                                       nil)
        (list operator datum))))

(defun rontolisp::%scheme-read-list ()
  (let ((elems nil))
    (do ()
        (nil)
      (rontolisp::%scheme-skip-atmosphere)
      (if (rontolisp::%scheme-eof-p (rontolisp::%scheme-peek-char))
          (rontolisp::%scheme-read-error "unclosed '('" nil))
      (let ((datum (rontolisp::%scheme-read-datum)))
        (cond ((eq datum rontolisp::%scheme-close)
               (let ((result nil))
                 (dolist (e elems result) (setq result (cons e result)))
                 (return result)))
              ((eq datum rontolisp::%scheme-dot)
               (if (null elems)
                   (rontolisp::%scheme-read-error
                    "a dotted pair needs a datum before the '.'" nil))
               (rontolisp::%scheme-skip-atmosphere)
               (let ((tail (rontolisp::%scheme-read-datum)))
                 (if (or (eq tail rontolisp::%scheme-close)
                         (eq tail rontolisp::%scheme-dot))
                     (rontolisp::%scheme-read-error
                      "a dotted pair needs a datum after the '.'" nil))
                 (rontolisp::%scheme-skip-atmosphere)
                 (let ((closer (rontolisp::%scheme-read-datum)))
                   (if (not (eq closer rontolisp::%scheme-close))
                       (rontolisp::%scheme-read-error
                        "more than one datum after the '.'" nil)))
                 (let ((result tail))
                   (dolist (e elems result) (setq result (cons e result)))
                   (return result))))
              (t (setq elems (cons datum elems))))))))

(defun rontolisp::%scheme-read-vector ()
  (let ((elems nil))
    (do ()
        (nil)
      (rontolisp::%scheme-skip-atmosphere)
      (if (rontolisp::%scheme-eof-p (rontolisp::%scheme-peek-char))
          (rontolisp::%scheme-read-error "unclosed '#('" nil))
      (let ((datum (rontolisp::%scheme-read-datum)))
        (cond ((eq datum rontolisp::%scheme-close)
               (return (coerce (nreverse elems) (quote vector))))
              ((eq datum rontolisp::%scheme-dot)
               (rontolisp::%scheme-read-error "a vector cannot be dotted" nil))
              (t (setq elems (cons datum elems))))))))

(defun rontolisp::%scheme-accumulate-token (first)
  (let ((chars (list first)))
    (do ()
        ((rontolisp::%scheme-eof-p (rontolisp::%scheme-peek-char)))
      (let ((c (rontolisp::%scheme-peek-char)))
        (if (rontolisp::%scheme-delimiter? c)
            (return nil)
            (progn
              (rontolisp::%scheme-next-char)
              (setq chars (cons c chars))))))
    (coerce (nreverse chars) (quote string))))

(defun rontolisp::%scheme-read-atom ()
  (let ((c (rontolisp::%scheme-next-char)))
    (if (rontolisp::%scheme-eof-p c)
        (rontolisp::%scheme-read-error "unexpected end of input" nil)
        (let ((token (rontolisp::%scheme-accumulate-token c)))
          (cond ((string= token ".") rontolisp::%scheme-dot)
                ((or (string= token "+inf.0") (string= token "-inf.0")
                     (string= token "+nan.0") (string= token "-nan.0"))
                 (rontolisp::%scheme-read-error
                  "infinities and NaN are not supported" token))
                (t (let ((number (rontolisp::%scheme-string->number token 10)))
                     (if (not (eq number rontolisp::%scheme-false))
                         number
                         (rontolisp::%scheme-string->symbol token)))))))))

(defun rontolisp::%scheme-read-hash ()
  (let ((c (rontolisp::%scheme-peek-char)))
    (cond ((rontolisp::%scheme-eof-p c)
           (rontolisp::%scheme-read-error "a lone '#'" nil))
          ((= (char-code c) 40)
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-vector))
          ((= (char-code c) 92)
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-character))
          (t
           (rontolisp::%scheme-pushback (code-char 35))
           (let ((token (rontolisp::%scheme-read-hash-token)))
             (cond ((or (string= token "#t") (string= token "#true")) t)
                   ((or (string= token "#f") (string= token "#false"))
                    rontolisp::%scheme-false)
                   ((or (string= token "#u8") (>= (length token) 3))
                    (rontolisp::%scheme-hash-token-datum token))
                   (t (rontolisp::%scheme-read-error "unsupported '#' syntax"
                                                     token))))))))

(defun rontolisp::%scheme-read-hash-token ()
  (let ((c (rontolisp::%scheme-next-char)))
    (rontolisp::%scheme-accumulate-token c)))

(defun rontolisp::%scheme-hash-token-datum (token)
  (if (>= (length token) 3)
      (let ((second (char token 1)))
        (if (or (= (char-code second) 117) (= (char-code second) 85))
            (rontolisp::%scheme-read-error "bytevectors are not supported"
                                           token)
            (let ((radix
                   (cond
                    ((or (= (char-code second) 120) (= (char-code second) 88))
                     16)
                    ((or (= (char-code second) 98) (= (char-code second) 66)) 2)
                    ((or (= (char-code second) 111) (= (char-code second) 79))
                     8)
                    ((or (= (char-code second) 100) (= (char-code second) 68))
                     10)
                    (t nil))))
              (if (null radix)
                  (rontolisp::%scheme-read-error "unsupported '#' syntax" token)
                  (let ((digits (subseq token 2)))
                    (if (= (length digits) 0)
                        (rontolisp::%scheme-read-error "unsupported '#' syntax"
                                                       token)
                        (let ((number
                               (rontolisp::%scheme-string->number digits
                                                                  radix)))
                          (if (eq number rontolisp::%scheme-false)
                              (rontolisp::%scheme-read-error
                               "unsupported '#' syntax" token)
                              number))))))))
      (rontolisp::%scheme-read-error "unsupported '#' syntax" token)))

(defun rontolisp::%scheme-read-character ()
  (let ((first (rontolisp::%scheme-next-char)))
    (if (rontolisp::%scheme-eof-p first)
        (rontolisp::%scheme-read-error "a character must follow '#\\'" nil)
        (let ((chars (list first)))
          (do ()
              ((or (rontolisp::%scheme-eof-p (rontolisp::%scheme-peek-char))
                (rontolisp::%scheme-delimiter? (rontolisp::%scheme-peek-char))))
            (setq chars (cons (rontolisp::%scheme-next-char) chars)))
          (if (= (length chars) 1)
              first
              (let ((name (coerce (nreverse chars) (quote string))))
                (cond ((string= name "alarm") (code-char 7))
                      ((string= name "backspace") (code-char 8))
                      ((string= name "delete") (code-char 127))
                      ((string= name "escape") (code-char 27))
                      ((string= name "newline") (code-char 10))
                      ((string= name "null") (code-char 0))
                      ((string= name "nul") (code-char 0))
                      ((string= name "return") (code-char 13))
                      ((string= name "space") (code-char 32))
                      ((string= name "tab") (code-char 9))
                      ((string= name "linefeed") (code-char 10))
                      ((= (char-code (char name 0)) 120)
                       (let ((value
                              (rontolisp::%scheme-parse-hex (subseq name 1))))
                         (if (null value)
                             (rontolisp::%scheme-read-error
                              "unknown character name" name)
                             (code-char value))))
                      (t (rontolisp::%scheme-read-error "unknown character name"
                                                        name)))))))))

(defun rontolisp::%scheme-parse-hex (digits)
  (if (= (length digits) 0)
      nil
      (let ((value 0) (ok t))
        (do ((i 0 (+ i 1)))
            ((or (not ok) (>= i (length digits))) (if ok value nil))
          (let ((d (rontolisp::%scheme-digit (char digits i) 16)))
            (if (null d) (setq ok nil) (setq value (+ (* value 16) d))))))))

(defun rontolisp::%scheme-read-string ()
  (let ((chars nil))
    (do ()
        (nil)
      (let ((c (rontolisp::%scheme-next-char)))
        (cond ((rontolisp::%scheme-eof-p c)
               (rontolisp::%scheme-read-error "unterminated string" nil))
              ((= (char-code c) 34)
               (return (coerce (nreverse chars) (quote string))))
              ((= (char-code c) 92)
               (let ((e (rontolisp::%scheme-next-char)))
                 (cond
                  ((rontolisp::%scheme-eof-p e)
                   (rontolisp::%scheme-read-error "unterminated string" nil))
                  ((= (char-code e) 110)
                   (setq chars (cons (code-char 10) chars)))
                  ((= (char-code e) 116)
                   (setq chars (cons (code-char 9) chars)))
                  ((= (char-code e) 114)
                   (setq chars (cons (code-char 13) chars)))
                  ((= (char-code e) 97) (setq chars (cons (code-char 7) chars)))
                  ((= (char-code e) 98) (setq chars (cons (code-char 8) chars)))
                  ((= (char-code e) 34)
                   (setq chars (cons (code-char 34) chars)))
                  ((= (char-code e) 92)
                   (setq chars (cons (code-char 92) chars)))
                  ((= (char-code e) 124)
                   (setq chars (cons (code-char 124) chars)))
                  ((= (char-code e) 120)
                   (let ((hex nil))
                     (do ((d
                           (rontolisp::%scheme-peek-char)
                           (rontolisp::%scheme-peek-char)))
                         ((or (rontolisp::%scheme-eof-p d)
                              (= (char-code d) 59)))
                       (setq hex (cons (rontolisp::%scheme-next-char) hex)))
                     (if (rontolisp::%scheme-eof-p
                          (rontolisp::%scheme-peek-char))
                         (rontolisp::%scheme-read-error
                          "unterminated \\x escape" nil))
                     (rontolisp::%scheme-next-char)
                     (let ((value
                            (rontolisp::%scheme-parse-hex
                             (coerce (nreverse hex) (quote string)))))
                       (if (null value)
                           (rontolisp::%scheme-read-error "malformed \\x escape"
                                                          nil)
                           (setq chars (cons (code-char value) chars))))))
                  (t (if (not (rontolisp::%scheme-string-continuation e))
                         (rontolisp::%scheme-read-error "unknown string escape"
                                                        e))))))
              (t (setq chars (cons c chars))))))))

(defun rontolisp::%scheme-string-continuation (first)
  (let ((code (char-code first)))
    (if (not (or (= code 32) (= code 9) (= code 10) (= code 13)))
        nil
        (let ((seen-newline (= code 10)))
          (if (= code 13)
              (progn
                (setq seen-newline t)
                (let ((d (rontolisp::%scheme-peek-char)))
                  (if (and (not (rontolisp::%scheme-eof-p d))
                           (= (char-code d) 10))
                      (rontolisp::%scheme-next-char)))))
          (do ()
              ((let ((d (rontolisp::%scheme-peek-char)))
                 (or (rontolisp::%scheme-eof-p d)
                     (not (or (= (char-code d) 32) (= (char-code d) 9)))))))
          (rontolisp::%scheme-next-char))
        (if (not seen-newline)
            (let ((d (rontolisp::%scheme-peek-char)))
              (cond ((rontolisp::%scheme-eof-p d) nil)
                    ((= (char-code d) 10)
                     (rontolisp::%scheme-next-char)
                     (setq seen-newline t))
                    ((= (char-code d) 13)
                     (rontolisp::%scheme-next-char)
                     (setq seen-newline t)
                     (let ((e (rontolisp::%scheme-peek-char)))
                       (if (and (not (rontolisp::%scheme-eof-p e))
                                (= (char-code e) 10))
                           (rontolisp::%scheme-next-char))))
                    (t nil))))
        (if (not seen-newline)
            nil
            (progn
              (do ()
                  ((let ((d (rontolisp::%scheme-peek-char)))
                     (or (rontolisp::%scheme-eof-p d)
                         (not (or (= (char-code d) 32) (= (char-code d) 9)))))))
              (rontolisp::%scheme-next-char))
            t))))

(defun rontolisp::%scheme-read-char () (rontolisp::%scheme-next-char))

(defun rontolisp::%scheme-read-line ()
  (rontolisp::%scheme-pushback-sync)
  (let ((first
         (if rontolisp::%scheme-pushback-chars
             (let ((c (car rontolisp::%scheme-pushback-chars)))
               (setq rontolisp::%scheme-pushback-chars
                     (cdr rontolisp::%scheme-pushback-chars))
               c)
             nil)))
    (cond ((and first (rontolisp::%scheme-eof-p first))
           rontolisp::%scheme-eof-instance)
          ((and first (= (char-code first) 10)) "")
          (t
           (let ((chars (if first (list first) nil)))
             (do ()
                 (nil)
               (let ((c (rontolisp::%scheme-next-char)))
                 (cond ((rontolisp::%scheme-eof-p c)
                        (if (null chars)
                            (return rontolisp::%scheme-eof-instance)
                            (let ((s (coerce (nreverse chars) (quote string))))
                              (return (rontolisp::%scheme-strip-cr s)))))
                       ((= (char-code c) 10)
                        (let ((s (coerce (nreverse chars) (quote string))))
                          (return (rontolisp::%scheme-strip-cr s))))
                       (t (setq chars (cons c chars)))))))))))

(defun rontolisp::%scheme-strip-cr (s)
  (let ((n (length s)))
    (if (and (> n 0) (= (char-code (char s (- n 1))) 13))
        (subseq s 0 (- n 1))
        s)))

;; char-ready? has no non-blocking probe on WASM (listen is a call-time error
;; there), so it answers #t everywhere: true when input or EOF is ready (the cases
;; the tests pin), and -- as the stated deviation -- true as well on a terminal with
;; nothing typed, where the next read would hang.
(defun rontolisp::%scheme-char-ready? () t)

;; --- equivalence, lists -----------------------------------------------------------

;; equal? recurses into pairs, strings and vectors (CL's equal compares a general
;; vector by identity) and is eqv? on everything else, records included. The cdr
;; direction is a loop, so a long list costs no stack.
(defun rontolisp::%scheme-equal? (a b)
  (do ((x a (cdr x)) (y b (cdr y)))
      ((not (and (consp x) (consp y)))
       (cond ((eql x y) t)
             ((stringp x) (and (stringp y) (string= x y) t))
             ((and (vectorp x) (vectorp y) (not (stringp y))
                   (= (length x) (length y)))
              (do ((i 0 (+ i 1)))
                  ((>= i (length x)) t)
                (if (not (rontolisp::%scheme-equal? (aref x i) (aref y i)))
                    (return nil))))
             (t nil)))
    (if (not (rontolisp::%scheme-equal? (car x) (car y))) (return nil))))

(defun rontolisp::%scheme-member (x list)
  (do ((rest list (cdr rest)))
      ((not (consp rest)) nil)
    (if (rontolisp::%scheme-equal? x (car rest)) (return rest))))

(defun rontolisp::%scheme-member-by (x list same)
  (do ((rest list (cdr rest)))
      ((not (consp rest)) nil)
    (if (not (eq (funcall same x (car rest)) rontolisp::%scheme-false))
        (return rest))))

(defun rontolisp::%scheme-assoc (x alist)
  (do ((rest alist (cdr rest)))
      ((not (consp rest)) nil)
    (if (and (consp (car rest)) (rontolisp::%scheme-equal? x (car (car rest))))
        (return (car rest)))))

(defun rontolisp::%scheme-assoc-by (x alist same)
  (do ((rest alist (cdr rest)))
      ((not (consp rest)) nil)
    (if (and (consp (car rest))
         (not (eq (funcall same x (car (car rest))) rontolisp::%scheme-false)))
        (return (car rest)))))

(defun rontolisp::%scheme-list? (x)
  (do ((rest x (cdr rest))) ((not (consp rest)) (null rest))))

;; SRFI-1 filter: pred is a Scheme procedure, so its answer is compared against the
;; false value rather than trusted as a Common Lisp boolean.
(defun rontolisp::%scheme-filter (pred list)
  (do ((rest (reverse list) (cdr rest)) (kept nil))
      ((not (consp rest)) kept)
    (if (not (eq (funcall pred (car rest)) rontolisp::%scheme-false))
        (setq kept (cons (car rest) kept)))))

;; SRFI-1 list-index: the position of the first element pred does not reject, else #f
;; (SchemeBuiltins converts nil to false; 0 is a true index, not a false one).
(defun rontolisp::%scheme-list-index (pred list)
  (do ((rest list (cdr rest)) (i 0 (+ i 1)))
      ((not (consp rest)) nil)
    (if (not (eq (funcall pred (car rest)) rontolisp::%scheme-false))
        (return i))))

;; (< a b c ...) as a first-class procedure: the Common Lisp function values of the
;; comparisons are binary, so the chain is walked pairwise. Answers T/NIL.
(defun rontolisp::%scheme-chain (compare arguments)
  (do ((rest arguments (cdr rest)))
      ((or (null rest) (null (cdr rest))) t)
    (if (not (funcall compare (car rest) (car (cdr rest)))) (return nil))))

;; --- numbers ----------------------------------------------------------------------

(defun rontolisp::%scheme-integer? (x)
  (or (integerp x) (and (floatp x) (= x (truncate x)))))

;; --- (scheme inexact) ---------------------------------------------------------------

;; A real argument whose Common Lisp answer is a complex number (sqrt -4, log -1, asin 2)
;; is refused by name: this front end has no complex numbers to print or compute with.
(defun rontolisp::%scheme-no-complex (message x)
  (error "~A" (rontolisp::%scheme-error-message message (list x))))

;; The exact root of a non-negative integer, or NIL when it has none.
(defun rontolisp::%scheme-exact-root (n)
  (let ((r (isqrt n))) (if (= (* r r) n) r nil)))

(defun rontolisp::%scheme-sqrt (x)
  (cond ((and (rationalp x) (>= x 0))
         (let ((n (rontolisp::%scheme-exact-root (numerator x)))
               (d (rontolisp::%scheme-exact-root (denominator x))))
           (if (and n d) (/ n d) (sqrt (float x 1.0d0)))))
        ((and (realp x) (minusp x))
         (rontolisp::%scheme-no-complex "sqrt: a negative argument has a complex root, and complex numbers are not supported:"
                                        x))
        (t (sqrt x))))

(defun rontolisp::%scheme-exact-integer-sqrt (k)
  (if (and (integerp k) (>= k 0))
      (let ((s (isqrt k))) (values s (- k (* s s))))
      (error "~A"
             (rontolisp::%scheme-error-message
              "exact-integer-sqrt: not an exact non-negative integer:"
              (list k)))))

;; The exact anchors R7RS implementations answer exactly: (exp 0) is 1, (log 1) is 0,
;; and so on. Anything else is Common Lisp's inexact answer.
(defun rontolisp::%scheme-exp (x) (if (eql x 0) 1 (exp x)))

(defun rontolisp::%scheme-log (x)
  (cond ((eql x 1) 0)
        ((and (realp x) (minusp x))
         (rontolisp::%scheme-no-complex "log: a negative argument has a complex logarithm, and complex numbers are not supported:"
                                        x))
        (t (log x))))

(defun rontolisp::%scheme-log-base (x base)
  (if (and (realp base) (minusp base))
      (rontolisp::%scheme-no-complex "log: a negative base has a complex logarithm, and complex numbers are not supported:"
                                     base)
      (if (eql x 1) 0 (/ (rontolisp::%scheme-log x) (log base)))))

(defun rontolisp::%scheme-sin (x) (if (eql x 0) 0 (sin x)))

(defun rontolisp::%scheme-cos (x) (if (eql x 0) 1 (cos x)))

(defun rontolisp::%scheme-tan (x) (if (eql x 0) 0 (tan x)))

(defun rontolisp::%scheme-asin (x)
  (cond ((eql x 0) 0)
        ((and (realp x) (> (abs x) 1))
         (rontolisp::%scheme-no-complex "asin: an argument outside [-1, 1] has a complex arcsine, and complex numbers are not supported:"
                                        x))
        (t (asin x))))

(defun rontolisp::%scheme-acos (x)
  (cond ((eql x 1) 0)
        ((and (realp x) (> (abs x) 1))
         (rontolisp::%scheme-no-complex "acos: an argument outside [-1, 1] has a complex arccosine, and complex numbers are not supported:"
                                        x))
        (t (acos x))))

(defun rontolisp::%scheme-atan (x) (if (eql x 0) 0 (atan x)))

(defun rontolisp::%scheme-atan2 (y x)
  (if (and (eql y 0) (rationalp x) (plusp x)) 0 (atan y x)))

(defun rontolisp::%scheme-nan? (x) (and (floatp x) (/= x x)))

(defun rontolisp::%scheme-infinite? (x)
  (and (floatp x)
       (or (> x most-positive-double-float) (< x most-negative-double-float))))

(defun rontolisp::%scheme-finite? (x)
  (not (or (rontolisp::%scheme-nan? x) (rontolisp::%scheme-infinite? x))))

;; The I-th significant digit of a printed float whose INTEGER-DIGITS digits start at
;; START and are followed by a point.
(defun rontolisp::%scheme-flonum-digit (s start integer-digits i)
  (char s (if (< i integer-digits) (+ start i) (+ start i 1))))

;; A flonum the way Scheme writes it: the shortest digits the Common Lisp printer finds,
;; laid out positionally when the point falls within 21 digits left of or 6 zeros right
;; of the first digit (123456789.123, 100000000000000000000.0, 0.000001), and as
;; <digits>e<exponent> outside that range (1e21, 1.5e-7) -- the ECMAScript thresholds.
;; The Common Lisp printer answers 1.0e21 and 1.23456789123e8.
(defun rontolisp::%scheme-print-flonum (x)
  (cond ((/= x x) (write-string "+nan.0"))
        ((> x most-positive-double-float) (write-string "+inf.0"))
        ((< x most-negative-double-float) (write-string "-inf.0"))
        (t
         (let ((s (princ-to-string x)) (n 0) (e nil))
           (setq n (length s))
           (do ((i 0 (+ i 1)))
               ((or e (>= i n)))
             (if (char= (char s i) #\e) (setq e i)))
           (if (null e)
               (write-string s)
               (let ((start (if (char= (char s 0) #\-) 1 0))
                     (integer-digits 0)
                     (count 0)
                     (exponent 0)
                     (exponent-sign 1)
                     (point 0))
                 (do ((i start (+ i 1)))
                     ((or (>= i e) (char= (char s i) #\.)))
                   (setq integer-digits (+ integer-digits 1)))
                 (setq count (- e start 1))
                 (do ()
                     ((or (<= count 1)
                          (char/= (rontolisp::%scheme-flonum-digit s start
                                   integer-digits (- count 1)) #\0)))
                   (setq count (- count 1)))
                 (do ((i (+ e 1) (+ i 1)))
                     ((>= i n))
                   (if (char= (char s i) #\-)
                       (setq exponent-sign -1)
                       (setq exponent
                        (+ (* exponent 10) (- (char-code (char s i)) 48)))))
                 (setq point (+ integer-digits (* exponent-sign exponent)))
                 (if (= start 1) (write-char #\-))
                 (cond ((or (> point 21) (<= point -6))
                        (write-char
                         (rontolisp::%scheme-flonum-digit s start integer-digits
                                                          0))
                        (if (> count 1) (write-char #\.))
                        (do ((i 1 (+ i 1)))
                            ((>= i count))
                          (write-char
                           (rontolisp::%scheme-flonum-digit s start
                                                            integer-digits i)))
                        (write-char #\e)
                        (princ (- point 1)))
                       ((<= point 0)
                        (write-string "0.")
                        (do ((i point (+ i 1)))
                            ((>= i 0))
                          (write-char #\0))
                        (do ((i 0 (+ i 1)))
                            ((>= i count))
                          (write-char
                           (rontolisp::%scheme-flonum-digit s start
                                                            integer-digits i))))
                       (t
                        (do ((i 0 (+ i 1)))
                            ((>= i (max point count)))
                          (if (= i point) (write-char #\.))
                          (write-char
                           (if (< i count)
                               (rontolisp::%scheme-flonum-digit s start
                                                                integer-digits
                                                                i)
                               #\0)))
                        (if (>= point count) (write-string ".0"))))))))))

;; max / min are inexact when any argument is (R7RS 6.2.6); CL's may answer the exact one.
(defun rontolisp::%scheme-max (a b)
  (let ((m (max a b))) (if (or (floatp a) (floatp b)) (float m 1.0d0) m)))

(defun rontolisp::%scheme-min (a b)
  (let ((m (min a b))) (if (or (floatp a) (floatp b)) (float m 1.0d0) m)))

(defun rontolisp::%scheme-number->string (n radix)
  (if (or (= radix 10) (not (integerp n)))
      (if (floatp n)
          (with-output-to-string (*standard-output*)
            (rontolisp::%scheme-print-flonum n))
          (princ-to-string n))
      (if (zerop n)
          "0"
          (do ((m (abs n) (truncate m radix))
               (digits
                nil
                (cons
                 (char "0123456789abcdefghijklmnopqrstuvwxyz" (rem m radix))
                 digits)))
              ((zerop m)
               (coerce (if (< n 0) (cons #\- digits) digits) 'string))))))

(defun rontolisp::%scheme-digit (c radix)
  (let ((code (char-code c)))
    (let ((value
           (cond ((and (>= code 48) (<= code 57)) (- code 48))
                 ((and (>= code 97) (<= code 122)) (- code 87))
                 ((and (>= code 65) (<= code 90)) (- code 55))
                 (t radix))))
      (if (< value radix) value nil))))

;; Scans digits of S from START: (value . end), END = START when there are none.
(defun rontolisp::%scheme-scan-digits (s start radix)
  (let ((value 0) (end start))
    (do ((i start (+ i 1)))
        ((>= i (length s)))
      (let ((digit (rontolisp::%scheme-digit (char s i) radix)))
        (if digit
            (progn
              (setq value (+ (* value radix) digit))
              (setq end (+ i 1)))
            (return nil))))
    (cons value end)))

;; [sign] digits [/ digits], and in radix 10 also [sign] digits* [. digits*] [e [sign]
;; digits+]. The decimal is built EXACTLY and converted once, so the result is the
;; correctly rounded double the reader would have produced for the same text.
(defun rontolisp::%scheme-string->number (s radix)
  (let ((n (length s)) (start 0) (sign 1))
    (if (and (> n 0) (or (char= (char s 0) #\+) (char= (char s 0) #\-)))
        (progn
          (if (char= (char s 0) #\-) (setq sign -1))
          (setq start 1)))
    (let ((whole (rontolisp::%scheme-scan-digits s start radix)))
      (let ((whole-end (cdr whole)))
        (cond ((and (= whole-end n) (> whole-end start)) (* sign (car whole)))
              ((and (> whole-end start) (< whole-end n)
                    (char= (char s whole-end) #\/))
               (let ((denominator
                      (rontolisp::%scheme-scan-digits s (+ whole-end 1) radix)))
                 (if (and (= (cdr denominator) n)
                          (> (cdr denominator) (+ whole-end 1))
                          (> (car denominator) 0))
                     (/ (* sign (car whole)) (car denominator))
                     rontolisp::%scheme-false)))
              ((= radix 10)
               (rontolisp::%scheme-decimal s
                (list sign start (car whole) whole-end)))
              (t rontolisp::%scheme-false))))))

(defun rontolisp::%scheme-decimal (s state)
  (let ((n (length s))
        (sign (car state))
        (start (car (cdr state)))
        (whole (car (cdr (cdr state))))
        (i (car (cdr (cdr (cdr state))))))
    (let ((mantissa whole) (scale 0) (digits (- i start)) (exponent 0) (ok t))
      (if (and (< i n) (char= (char s i) #\.))
          (let ((fraction (rontolisp::%scheme-scan-digits s (+ i 1) 10)))
            (setq scale (- (cdr fraction) (+ i 1)))
            (setq digits (+ digits scale))
            (setq mantissa (+ (* whole (expt 10 scale)) (car fraction)))
            (setq i (cdr fraction))))
      (if (and (< i n) (or (char= (char s i) #\e) (char= (char s i) #\E)))
          (let ((exponent-sign 1) (j (+ i 1)))
            (if (and (< j n) (or (char= (char s j) #\+) (char= (char s j) #\-)))
                (progn
                  (if (char= (char s j) #\-) (setq exponent-sign -1))
                  (setq j (+ j 1))))
            (let ((scanned (rontolisp::%scheme-scan-digits s j 10)))
              (if (= (cdr scanned) j) (setq ok nil))
              (setq exponent (* exponent-sign (car scanned)))
              (setq i (cdr scanned)))))
      (if (and ok (= i n) (> digits 0))
          ;; Negated AFTER the conversion, so "-0.0" keeps its sign.
          (let ((magnitude
                 (float (* mantissa (expt 10 (- exponent scale))) 1.0d0)))
            (if (< sign 0) (- magnitude) magnitude))
          rontolisp::%scheme-false))))

;; --- control ----------------------------------------------------------------------

;; apply as a first-class procedure: (a b (c d)) -> (a b c d), the argument list
;; (apply f a b '(c d)) spreads. #'apply itself is not a function value on the compile
;; path.
(defun rontolisp::%scheme-spread (arguments)
  (if (null (cdr arguments))
      (car arguments)
      (cons (car arguments) (rontolisp::%scheme-spread (cdr arguments)))))

;; Escape-only, one-shot: the continuation is a closure over a block, so calling it
;; after the call/cc returned is an error, and re-entry does not exist.
(defun rontolisp::%scheme-call/cc (receiver)
  (block rontolisp::%scheme-continuation
    (funcall receiver
             (lambda (&rest results)
               (return-from rontolisp::%scheme-continuation
                            (values-list results))))))

;; The exit half runs on every way out -- a normal return, an escaping continuation,
;; an error. There is no re-entry, so BEFORE runs exactly once.
(defun rontolisp::%scheme-dynamic-wind (before thunk after)
  (funcall before)
  (unwind-protect (funcall thunk) (funcall after)))

(defun rontolisp::%scheme-error-message (message irritants)
  (with-output-to-string (*standard-output*)
    (if (stringp message)
        (write-string message)
        (rontolisp::%scheme-print message t))
    (dolist (irritant irritants)
      (write-char #\Space)
      (rontolisp::%scheme-print irritant t))))

;; exit and emergency-exit (R7RS 6.14): #t or no argument is success, #f failure, an
;; integer the status itself, masked to eight bits the way uiop:quit masks it. exit
;; throws to the catch tag every lowered file wraps its top-level forms in, so the
;; outstanding dynamic-wind afters run on the way out; the catch calls this function
;; with the thrown code. emergency-exit calls it directly, skipping the afters.
(defun rontolisp::%scheme-exit (code)
  (finish-output *standard-output*)
  (finish-output *error-output*)
  (%host-exit
   (cond ((integerp code) (logand code 255))
         ((eq code rontolisp::%scheme-false) 1)
         (t 0))))

;; --- (scheme lazy) and SICP streams ---------------------------------------------------

;; A promise is a record around a BOX, (state . payload): state 2 is forced and the
;; payload its value; 0 is (delay e), a thunk answering the value; 1 is (delay-force e),
;; a thunk answering another promise. Promises that delay-force chains into each other
;; SHARE one box (R7RS 4.2.5's promise-update!), so forcing the chain is a loop that
;; runs in constant space and memoizes every link at once.
(defstruct (rontolisp::%scheme-promise
            (:constructor rontolisp::%scheme-new-promise (box)) (:copier nil))
  box)

(defun rontolisp::%scheme-delay (state thunk)
  (rontolisp::%scheme-new-promise (cons state thunk)))

(defun rontolisp::%scheme-promise? (x) (rontolisp::%scheme-promise-p x))

(defun rontolisp::%scheme-make-promise (x)
  (if (rontolisp::%scheme-promise-p x)
      x
      (rontolisp::%scheme-new-promise (cons 2 x))))

;; Forcing a non-promise answers it. The box is read again after the thunk returns:
;; the thunk may have forced this same promise, and the first value to land wins.
(defun rontolisp::%scheme-force (p)
  (if (not (rontolisp::%scheme-promise-p p))
      p
      (do ()
          ((= (car (rontolisp::%scheme-promise-box p)) 2)
           (cdr (rontolisp::%scheme-promise-box p)))
        (let ((box (rontolisp::%scheme-promise-box p)))
          (let ((state (car box)) (result (funcall (cdr box))))
            (setq box (rontolisp::%scheme-promise-box p))
            (if (/= (car box) 2)
                (if (and (= state 1) (rontolisp::%scheme-promise-p result))
                    (let ((inner (rontolisp::%scheme-promise-box result)))
                      (rplaca box (car inner))
                      (rplacd box (cdr inner))
                      (setf (rontolisp::%scheme-promise-box result) box))
                    (progn
                      (rplaca box 2)
                      (rplacd box result)))))))))

(defun rontolisp::%scheme-stream-pair? (x)
  (and (consp x) (rontolisp::%scheme-promise-p (cdr x))))

(defun rontolisp::%scheme-stream-cdr (s) (rontolisp::%scheme-force (cdr s)))

;; The streams below are walked in loops, never by recursion: a sieve asked for its 50th
;; prime forces thousands of cells.
(defun rontolisp::%scheme-stream-tail (s n)
  (do ((i 0 (+ i 1)))
      ((>= i n) s)
    (setq s (rontolisp::%scheme-stream-cdr s))))

(defun rontolisp::%scheme-stream-ref (s n)
  (car (rontolisp::%scheme-stream-tail s n)))

;; The first N elements as a list; every element when N is NIL.
(defun rontolisp::%scheme-stream->list (s n)
  (let ((out nil) (i 0))
    (do ()
        ((or (not (consp s)) (and n (>= i n))) (nreverse out))
      (setq out (cons (car s) out))
      (setq i (+ i 1))
      (if (or (null n) (< i n)) (setq s (rontolisp::%scheme-stream-cdr s))))))

(defun rontolisp::%scheme-list->stream (list)
  (let ((s nil))
    (dolist (x (reverse list) s)
      (setq s (cons x (rontolisp::%scheme-new-promise (cons 2 s)))))))

(defun rontolisp::%scheme-stream-map (proc streams)
  (if (dolist (s streams nil) (if (not (consp s)) (return t)))
      nil
      (cons (apply proc (mapcar #'car streams))
            (rontolisp::%scheme-delay 0
                                      (lambda ()
                                        (rontolisp::%scheme-stream-map proc
                                         (mapcar #'rontolisp::%scheme-stream-cdr
                                                 streams)))))))

(defun rontolisp::%scheme-stream-for-each (proc s)
  (do ()
      ((not (consp s)) nil)
    (funcall proc (car s))
    (setq s (rontolisp::%scheme-stream-cdr s))))

(defun rontolisp::%scheme-stream-filter (pred s)
  (do ()
      ((or (not (consp s))
           (not (eq (funcall pred (car s)) rontolisp::%scheme-false)))
       (if (consp s)
           (cons (car s)
                 (rontolisp::%scheme-delay 0
                                           (lambda ()
                                             (rontolisp::%scheme-stream-filter
                                              pred
                                              (rontolisp::%scheme-stream-cdr
                                               s)))))
           nil))
    (setq s (rontolisp::%scheme-stream-cdr s))))

(defun rontolisp::%scheme-stream-append (streams)
  (do ()
      ((or (null streams) (consp (car streams)))
       (if (null streams)
           nil
           (let ((s (car streams)))
             (cons (car s)
                   (rontolisp::%scheme-delay 0
                                             (lambda ()
                                               (rontolisp::%scheme-stream-append
                                                (cons
                                                 (rontolisp::%scheme-stream-cdr
                                                  s) (cdr streams)))))))))
    (setq streams (cdr streams))))

;; --- SICP 3.4: parallel-execute and test-and-set! ---------------------------------------

;; Where threads exist (the interpreter and the JVM, .kb/threads.md) each thunk runs in
;; its own thread and every thread is JOINED before the call returns, so a program's
;; output is complete when it ends. The joins nest in unwind-protect: a thunk's error is
;; re-signaled by its join, but only after the remaining threads have been joined too.
#+thread-support
(defun rontolisp::%scheme-parallel-execute (thunks)
  (rontolisp::%scheme-join-all
   (mapcar (lambda (thunk) (rontolisp:make-thread thunk)) thunks)))

#+thread-support
(defun rontolisp::%scheme-join-all (threads)
  (if threads
      (unwind-protect (rontolisp:join-thread (car threads))
        (rontolisp::%scheme-join-all (cdr threads)))))

;; Both WASM backends are single-threaded: the thunks run one after another, in order.
;; That is one of the interleavings the threaded backends may produce, and since nothing
;; ever contends, a serializer's busy-wait on test-and-set! finds the cell clear.
#-thread-support
(defun rontolisp::%scheme-parallel-execute (thunks)
  (dolist (thunk thunks) (funcall thunk)))

;; One lock for every cell: the check and the set happen under it, which is all the
;; book's atomicity asks for. Answers a Common Lisp boolean (a `pred` in SchemeBuiltins).
(defvar rontolisp::%scheme-test-and-set-lock (rontolisp:make-mutex))

(defun rontolisp::%scheme-test-and-set! (cell)
  (rontolisp:with-mutex (rontolisp::%scheme-test-and-set-lock)
    (if (eq (car cell) rontolisp::%scheme-false)
        (progn
          (rplaca cell t)
          nil)
        t)))

;; --- eval: (scheme eval), (scheme repl) and the R5RS / MIT environment names ----------

;; A Scheme evaluator over Scheme DATUMS, so (eval datum env) runs on every backend from
;; one definition: the lowering (SchemeLowering.java) is not inside a compiled program,
;; and the run-time eval the compiled backends carry (.kb/eval-runtime.md) evaluates
;; Common Lisp core forms, not Scheme. Every environment specifier is the one global
;; environment, the symbol #[environment], which holds three things in this order: the
;; program's own variables (boundp -- including what eval itself defined, since `set`
;; makes a global appear at run time on every backend), the program's procedures
;; (fboundp, the names its file lowered), and the builtins, through the table
;; SchemeBuiltins generates (%scheme-builtin). A name the program defines wins over a
;; builtin, as in a file, and a later definition wins over an earlier one, whatever
;; namespace the earlier one lived in.
;;
;; A local environment is a list of frames, (alist . loop): an alist of (name . value)
;; cells -- set! mutates the cell, an internal define pushes onto the innermost frame,
;; which every closure over it shares -- and, for the frame of a named let or of a
;; procedure that calls itself, the LOOP (name formals body outer): a call of that name
;; found through the frames rebinds a fresh frame over OUTER and continues with the body
;; in %scheme-eval's own loop, in place of a call. In tail position that is a jump, so
;; the loop runs in constant stack; anywhere else it computes the same value a call
;; would, so no tail-position analysis is needed -- only that the name is never a value,
;; never assigned and never rebound in the body (%scheme-eval-called-only). Tail
;; positions of the other forms (if, begin, the let family, cond, case, and, or, when,
;; unless, the do result) iterate the same way; any other call recurses.

(defun rontolisp::%scheme-eval-in (x env)
  (if (eq env '|#[environment]|)
      (rontolisp::%scheme-eval x nil)
      (error "~A"
             (rontolisp::%scheme-error-message "eval: not an environment:"
                                               (list env)))))

;; (environment import-set ...): every set is checked to name a library this front end
;; has (%scheme-library-p is generated from the front end's list), and the answer is the
;; one global environment, which holds them all.
(defun rontolisp::%scheme-environment (sets)
  (dolist (spec sets '|#[environment]|)
    (rontolisp::%scheme-check-import-set spec)))

(defun rontolisp::%scheme-check-import-set (spec)
  (if (not
       (and (consp spec) (consp (cdr spec))
            (if (member (car spec) '(|only| |except| |prefix| |rename|))
                (progn
                  (rontolisp::%scheme-check-import-set (car (cdr spec)))
                  t)
                (and (eq (car spec) '|scheme|) (null (cdr (cdr spec)))
                     (rontolisp::%scheme-library-p (car (cdr spec)))))))
      (error "~A"
             (rontolisp::%scheme-error-message
              "environment: library is not available:" (list spec)))))

;; The keywords eval knows: the ones it implements and the ones it refuses by name. A
;; user binding of the same name, local or global, wins over the keyword as in a file.
;; A datum's symbols carry their mangled spelling (SchemeNames), so => is s%=> here: the
;; one keyword with no lowercase letter. The sicp keyword (cons-stream) is the generated
;; %scheme-eval-extension-keyword-p's to answer: none under --scheme-standard r7rs.
(defun rontolisp::%scheme-eval-keyword-p (name)
  (or (member name
              '(|quote| |quasiquote| |unquote| |unquote-splicing| |lambda| |if|
                        |set!| |define| |begin| |let| |let*| |letrec| |letrec*|
                        |do| |cond| |case| |and| |or| |when| |unless| |else|
                        |s%=>| |delay| |delay-force| |define-record-type|
                        |define-values| |let-values| |let*-values| |import|
                        |define-syntax| |let-syntax| |letrec-syntax|
                        |syntax-rules| |syntax-error| |define-library| |guard|
                        |parameterize| |case-lambda| |include| |include-ci|
                        |cond-expand|))
      (rontolisp::%scheme-eval-extension-keyword-p name)))

(defun rontolisp::%scheme-eval-syntax (head env)
  (if (and (rontolisp::%scheme-eval-keyword-p head)
           (null (rontolisp::%scheme-eval-cell head env)) (not (boundp head))
           (not (fboundp head)))
      head
      nil))

(defun rontolisp::%scheme-eval-identifier-p (x)
  (and (symbolp x) x (not (eq x t)) (not (eq x rontolisp::%scheme-false))))

;; The (name . value) cell of a local variable, or NIL.
(defun rontolisp::%scheme-eval-cell (name env)
  (dolist (frame env nil)
    (let ((cell (assoc name (car frame) :test #'eq))) (if cell (return cell)))))

;; The loop (name formals body outer) a call of NAME continues, or NIL: the nearest
;; frame that is NAME's loop, unless a variable of that name is bound before it.
(defun rontolisp::%scheme-eval-loop (name env)
  (if (symbolp name)
      (dolist (frame env nil)
        (if (assoc name (car frame) :test #'eq) (return nil))
        (if (and (cdr frame) (eq (car (cdr frame)) name)) (return (cdr frame))))
      nil))

(defun rontolisp::%scheme-eval-variable (name env)
  (let ((cell (rontolisp::%scheme-eval-cell name env)))
    (if cell (cdr cell) (rontolisp::%scheme-eval-global name))))

(defun rontolisp::%scheme-eval-global (name)
  (cond ((boundp name) (symbol-value name))
        ((fboundp name) (symbol-function name))
        (t (let ((value (rontolisp::%scheme-builtin name)))
             (cond ((not (eq value 'rontolisp::%scheme-unbound)) value)
                   ((rontolisp::%scheme-eval-keyword-p name)
                    (error "~A"
                     (rontolisp::%scheme-error-message
                      "Syntactic keyword may not be used as an expression:"
                      (list name))))
                   (t (error "~A"
                             (rontolisp::%scheme-error-message
                              "Unbound variable:" (list name)))))))))

;; define: at the top level a program global, through `set` -- visible to later evals
;; and to the program itself, on every backend alike; in a body into the innermost
;; frame.
(defun rontolisp::%scheme-eval-define (name value env)
  (if (null env)
      (set name value)
      (let ((frame (car env)))
        (let ((cell (assoc name (car frame) :test #'eq)))
          (if cell
              (rplacd cell value)
              (rplaca frame (cons (cons name value) (car frame))))))))

;; set!: a local cell, else the program's own global -- a variable, a procedure's
;; variable shadowing, or a builtin shadowed from here on, all through `set`, so the
;; program reads what eval wrote; an unknown name is an error, as in R7RS.
(defun rontolisp::%scheme-eval-assign (name value env)
  (let ((cell (rontolisp::%scheme-eval-cell name env)))
    (cond (cell (rplacd cell value))
          ((or (boundp name) (fboundp name)
               (not
                (eq (rontolisp::%scheme-builtin name)
                    'rontolisp::%scheme-unbound)))
           (set name value))
          (t (error "~A"
                    (rontolisp::%scheme-error-message "Unbound variable:"
                                                      (list name)))))))

(defun rontolisp::%scheme-ill-formed (x)
  (error "~A"
   (rontolisp::%scheme-error-message "Ill-formed special form:" (list x))))

;; LIST checked to be a proper list of MIN to MAX (NIL: any number of) elements, else
;; the form X is ill-formed.
(defun rontolisp::%scheme-eval-proper (list min max x)
  (let ((n 0))
    (do ((rest list (cdr rest)))
        ((not (consp rest)) (if rest (rontolisp::%scheme-ill-formed x)))
      (setq n (+ n 1)))
    (if (or (< n min) (and max (> n max))) (rontolisp::%scheme-ill-formed x))
    list))

;; The operands of the special form X.
(defun rontolisp::%scheme-eval-parts (x min max)
  (rontolisp::%scheme-eval-proper (cdr x) min max x))

(defun rontolisp::%scheme-eval-operands (x env)
  (let ((out nil))
    (dolist (operand (rontolisp::%scheme-eval-proper (cdr x) 0 nil x)
                     (nreverse out))
      (setq out (cons (rontolisp::%scheme-eval operand env) out)))))

(defun rontolisp::%scheme-eval-apply (f arguments)
  (if (functionp f)
      (apply f arguments)
      (error "~A"
             (rontolisp::%scheme-error-message "The object is not applicable:"
                                               (list f)))))

;; The check a lowered combination's operator goes through: a Scheme application
;; applies a PROCEDURE value, never a symbol designator, so a non-function --
;; #f, the unspecified object, a number, a symbol -- reports what eval reports
;; (.kb/scheme-frontend.md), on every backend, without any backend learning a
;; Scheme name. Answers f when it is one.
(defun rontolisp::%scheme-ensure-procedure (f)
  (if (functionp f)
      f
      (error "~A"
             (rontolisp::%scheme-error-message "The object is not applicable:"
                                               (list f)))))

;; Evaluates every form of BODY but the last, for effect, and answers the last one: the
;; tail form the caller continues with.
(defun rontolisp::%scheme-eval-butlast (body env)
  (do ()
      ((null (cdr body)) (car body))
    (rontolisp::%scheme-eval (car body) env)
    (setq body (cdr body))))

(defun rontolisp::%scheme-eval-body (body env)
  (rontolisp::%scheme-eval (rontolisp::%scheme-eval-butlast body env) env))

(defun rontolisp::%scheme-check-formals (formals x)
  (do ((rest formals (cdr rest)))
      ((not (consp rest))
       (if (not (or (null rest) (rontolisp::%scheme-eval-identifier-p rest)))
           (rontolisp::%scheme-ill-formed x)))
    (if (not (rontolisp::%scheme-eval-identifier-p (car rest)))
        (rontolisp::%scheme-ill-formed x))))

;; A closure: the body runs in a frame binding FORMALS -- a proper list, a dotted list or
;; one symbol collecting every argument -- over the environment of its creation. NAME is
;; what the procedure is being defined as, or NIL: a body that only ever CALLS its name
;; gets the loop frame, so its self calls in tail position jump.
(defun rontolisp::%scheme-eval-lambda (name formals body env x)
  (rontolisp::%scheme-check-formals formals x)
  (rontolisp::%scheme-eval-proper body 1 nil x)
  (let ((loop
         (if (and name (rontolisp::%scheme-eval-called-only-list name body))
             (list name formals body env)
             nil)))
    (lambda (&rest arguments)
      (rontolisp::%scheme-eval-body body
                                    (cons (cons (rontolisp::%scheme-eval-bind
                                                 formals arguments) loop)
                                          env)))))

;; The alist binding FORMALS to ARGUMENTS.
(defun rontolisp::%scheme-eval-bind (formals arguments)
  (let ((bindings nil) (rest formals) (args arguments))
    (do ()
        ((not (consp rest)))
      (if (null args) (rontolisp::%scheme-eval-arity formals arguments))
      (setq bindings (cons (cons (car rest) (car args)) bindings))
      (setq rest (cdr rest))
      (setq args (cdr args)))
    (if (null rest)
        (if args (rontolisp::%scheme-eval-arity formals arguments))
        (setq bindings (cons (cons rest args) bindings)))
    bindings))

(defun rontolisp::%scheme-eval-arity (formals arguments)
  (error "~A"
         (rontolisp::%scheme-error-message "Wrong number of arguments:"
                                           (list formals '|given| arguments))))

;; Whether every mention of NAME in X is the head of a call -- never a value, never
;; assigned, never rebound by a let, a lambda, a do or a define -- read with the
;; keywords as syntax. Conservative: a mention under quasiquote, or inside a form this
;; cannot read, answers NIL, which only costs the loop frame; a quoted datum is data.
(defun rontolisp::%scheme-eval-called-only (name x)
  (cond ((eq x name) nil)
   ((not (consp x)) t)
   ((eq (car x) '|quote|) t)
   ((eq (car x) '|quasiquote|) (rontolisp::%scheme-eval-mentions-not name x))
   ((eq (car x) '|set!|)
    (and (consp (cdr x)) (not (eq (car (cdr x)) name))
         (rontolisp::%scheme-eval-called-only-list name (cdr (cdr x)))))
   ((eq (car x) '|lambda|)
    (and (consp (cdr x))
         (rontolisp::%scheme-eval-mentions-not name (car (cdr x)))
         (rontolisp::%scheme-eval-called-only-list name (cdr (cdr x)))))
   ((eq (car x) '|define|)
    (and (consp (cdr x))
         (rontolisp::%scheme-eval-mentions-not name (car (cdr x)))
         (rontolisp::%scheme-eval-called-only-list name (cdr (cdr x)))))
   ((member (car x) '(|let| |let*| |letrec| |letrec*| |do|))
    (let ((rest (cdr x)))
      (if (and (eq (car x) '|let|) (consp rest) (symbolp (car rest)) (car rest))
          (setq rest (if (eq (car rest) name) (list name) (cdr rest))))
      (and (consp rest) (not (rontolisp::%scheme-eval-binds name (car rest)))
           (rontolisp::%scheme-eval-called-only-list name rest))))
   (t (and
       (or (eq (car x) name) (rontolisp::%scheme-eval-called-only name (car x)))
       (rontolisp::%scheme-eval-called-only-list name (cdr x))))))

(defun rontolisp::%scheme-eval-called-only-list (name forms)
  (do ((rest forms (cdr rest)))
      ((not (consp rest)) (not (eq rest name)))
    (if (not (rontolisp::%scheme-eval-called-only name (car rest)))
        (return nil))))

;; Whether a binding spec list, ((name init ...) ...), binds NAME.
(defun rontolisp::%scheme-eval-binds (name specs)
  (do ((rest specs (cdr rest)))
      ((not (consp rest)) nil)
    (if (and (consp (car rest)) (eq (car (car rest)) name)) (return t))))

(defun rontolisp::%scheme-eval-mentions-not (name x)
  (cond ((eq x name) nil)
        ((consp x)
         (and (rontolisp::%scheme-eval-mentions-not name (car x))
              (rontolisp::%scheme-eval-mentions-not name (cdr x))))
        (t t)))

;; ((name init) ...) as (names . inits).
(defun rontolisp::%scheme-eval-bindings (specs x)
  (let ((names nil) (inits nil))
    (dolist (spec (rontolisp::%scheme-eval-proper specs 0 nil x))
      (if (not
           (and (consp spec) (rontolisp::%scheme-eval-identifier-p (car spec))
                (consp (cdr spec)) (null (cdr (cdr spec)))))
          (rontolisp::%scheme-ill-formed x))
      (setq names (cons (car spec) names))
      (setq inits (cons (car (cdr spec)) inits)))
    (cons (nreverse names) (nreverse inits))))

;; The value of INIT being bound to NAME: a syntactic lambda is closed with its name, so
;; a letrec procedure or a define loops on its self calls like a named let.
(defun rontolisp::%scheme-eval-named (name init env x)
  (if (and (consp init)
           (eq (rontolisp::%scheme-eval-syntax (car init) env) '|lambda|))
      (let ((parts (rontolisp::%scheme-eval-parts init 2 nil)))
        (rontolisp::%scheme-eval-lambda name (car parts) (cdr parts) env x))
      (rontolisp::%scheme-eval init env)))

;; ((var init [step]) ...) as (names inits . steps); a variable without a step keeps its
;; value, so its step is the variable itself.
(defun rontolisp::%scheme-eval-do-specs (specs x)
  (let ((names nil) (inits nil) (steps nil))
    (dolist (spec (rontolisp::%scheme-eval-proper specs 0 nil x))
      (if (not
           (and (consp spec) (rontolisp::%scheme-eval-identifier-p (car spec))
                (consp (cdr spec)) (rontolisp::%scheme-list? spec)
                (null (cdr (cdr (cdr spec))))))
          (rontolisp::%scheme-ill-formed x))
      (setq names (cons (car spec) names))
      (setq inits (cons (car (cdr spec)) inits))
      (setq steps
       (cons (if (cdr (cdr spec)) (car (cdr (cdr spec))) (car spec)) steps)))
    (cons (nreverse names) (cons (nreverse inits) (nreverse steps)))))

;; The clause a cond takes: (t . value) when the value is final (a test alone, a =>
;; receiver), (nil . body) when the body is still to run, NIL when no clause is taken.
(defun rontolisp::%scheme-eval-cond (clauses env x)
  (dolist (clause (rontolisp::%scheme-eval-proper clauses 0 nil x) nil)
    (if (not (and (consp clause) (rontolisp::%scheme-list? clause)))
        (rontolisp::%scheme-ill-formed x))
    (if (eq (car clause) '|else|)
        (progn
          (if (null (cdr clause)) (rontolisp::%scheme-ill-formed x))
          (return (cons nil (cdr clause))))
        (let ((value (rontolisp::%scheme-eval (car clause) env)))
          (if (not (eq value rontolisp::%scheme-false))
              (return
               (cond ((null (cdr clause)) (cons t value))
                     ((eq (car (cdr clause)) '|s%=>|)
                      (cons t
                            (rontolisp::%scheme-eval-receiver (cdr (cdr clause))
                                                              value env x)))
                     (t (cons nil (cdr clause))))))))))

;; (=> receiver): the receiver, applied to the value.
(defun rontolisp::%scheme-eval-receiver (rest value env x)
  (if (not (and (consp rest) (null (cdr rest))))
      (rontolisp::%scheme-ill-formed x))
  (rontolisp::%scheme-eval-apply (rontolisp::%scheme-eval (car rest) env)
                                 (list value)))

;; The clause a case takes, in %scheme-eval-cond's shape; the data are compared by
;; eqv? (eql).
(defun rontolisp::%scheme-eval-case (key clauses env x)
  (dolist (clause (rontolisp::%scheme-eval-proper clauses 0 nil x) nil)
    (if (not
         (and (consp clause) (consp (cdr clause))
              (rontolisp::%scheme-list? clause)
              (or (eq (car clause) '|else|)
                  (rontolisp::%scheme-list? (car clause)))))
        (rontolisp::%scheme-ill-formed x))
    (if (or (eq (car clause) '|else|) (member key (car clause)))
        (return
         (if (eq (car (cdr clause)) '|s%=>|)
             (cons t
              (rontolisp::%scheme-eval-receiver (cdr (cdr clause)) key env x))
             (cons nil (cdr clause)))))))

;; quasiquote, depth-counted as R7RS asks: the innermost unquote of a nested template
;; is the one evaluated.
(defun rontolisp::%scheme-eval-quasi (template depth env)
  (cond ((and (vectorp template) (not (stringp template)))
         (coerce
          (rontolisp::%scheme-eval-quasi (coerce template 'list) depth env)
          'vector))
        ((not (consp template)) template)
        ((rontolisp::%scheme-eval-unquote-p template '|unquote|)
         (if (= depth 1)
             (rontolisp::%scheme-eval (car (cdr template)) env)
             (list '|unquote|
                   (rontolisp::%scheme-eval-quasi (car (cdr template))
                                                  (- depth 1) env))))
        ((rontolisp::%scheme-eval-unquote-p template '|quasiquote|)
         (list '|quasiquote|
          (rontolisp::%scheme-eval-quasi (car (cdr template)) (+ depth 1) env)))
        ((and (consp (car template))
              (rontolisp::%scheme-eval-unquote-p (car template)
                                                 '|unquote-splicing|))
         (let ((tail (rontolisp::%scheme-eval-quasi (cdr template) depth env)))
           (if (= depth 1)
               (append (rontolisp::%scheme-eval (car (cdr (car template))) env)
                       tail)
               (cons (list '|unquote-splicing|
                           (rontolisp::%scheme-eval-quasi
                            (car (cdr (car template))) (- depth 1) env))
                     tail))))
        (t (cons (rontolisp::%scheme-eval-quasi (car template) depth env)
                 (rontolisp::%scheme-eval-quasi (cdr template) depth env)))))

;; (keyword x): the keyword and exactly one operand.
(defun rontolisp::%scheme-eval-unquote-p (x keyword)
  (and (eq (car x) keyword) (consp (cdr x)) (null (cdr (cdr x)))))

(defun rontolisp::%scheme-eval (x env)
  (do ()
      (nil)
    (cond ((or (null x) (eq x t) (eq x rontolisp::%scheme-false)
               (eq x rontolisp::%scheme-unspecified) (eq x '|#[environment]|))
           (return x))
          ((symbolp x) (return (rontolisp::%scheme-eval-variable x env)))
          ((not (consp x)) (return x))
          (t
           (let ((head (rontolisp::%scheme-eval-syntax (car x) env)))
             (cond ((null head)
                    (let ((loop (rontolisp::%scheme-eval-loop (car x) env)))
                      (if loop
                          ;; (name args...) continues NAME's loop: a fresh frame over
                          ;; its outer environment, then its body.
                          (let ((arguments
                                 (rontolisp::%scheme-eval-operands x env)))
                            (setq env
                                  (cons (cons (rontolisp::%scheme-eval-bind
                                               (car (cdr loop)) arguments) loop)
                                        (car (cdr (cdr (cdr loop))))))
                            (setq x
                                  (rontolisp::%scheme-eval-butlast
                                   (car (cdr (cdr loop))) env)))
                          (return
                           (rontolisp::%scheme-eval-apply
                            (rontolisp::%scheme-eval (car x) env)
                            (rontolisp::%scheme-eval-operands x env))))))
                   ((eq head '|quote|)
                    (return (car (rontolisp::%scheme-eval-parts x 1 1))))
                   ((eq head '|quasiquote|)
                    (return
                     (rontolisp::%scheme-eval-quasi
                      (car (rontolisp::%scheme-eval-parts x 1 1)) 1 env)))
                   ((eq head '|if|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 3)))
                      (if (eq (rontolisp::%scheme-eval (car parts) env)
                              rontolisp::%scheme-false)
                          (if (cdr (cdr parts))
                              (setq x (car (cdr (cdr parts))))
                              (return rontolisp::%scheme-unspecified))
                          (setq x (car (cdr parts))))))
                   ((eq head '|define|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 1 nil)))
                      (let ((target (car parts)))
                        (cond ((consp target)
                               ;; (define (name . formals) body...); a curried
                               ;; define is not supported, as in a file.
                               (if (not
                                    (rontolisp::%scheme-eval-identifier-p
                                     (car target)))
                                   (rontolisp::%scheme-ill-formed x))
                               (rontolisp::%scheme-eval-define (car target)
                                (rontolisp::%scheme-eval-lambda (car target)
                                                                (cdr target)
                                                                (cdr parts) env
                                                                x) env))
                              ((and
                                (rontolisp::%scheme-eval-identifier-p target)
                                (null (cdr (cdr parts))))
                               (rontolisp::%scheme-eval-define target
                                (if (cdr parts)
                                    (rontolisp::%scheme-eval-named target
                                     (car (cdr parts)) env x)
                                    nil) env))
                              (t (rontolisp::%scheme-ill-formed x))))
                      (return rontolisp::%scheme-unspecified)))
                   ((eq head '|set!|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 2)))
                      (if (not
                           (rontolisp::%scheme-eval-identifier-p (car parts)))
                          (rontolisp::%scheme-ill-formed x))
                      (rontolisp::%scheme-eval-assign (car parts)
                       (rontolisp::%scheme-eval (car (cdr parts)) env) env)
                      (return rontolisp::%scheme-unspecified)))
                   ((eq head '|lambda|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 nil)))
                      (return
                       (rontolisp::%scheme-eval-lambda nil (car parts)
                                                       (cdr parts) env x))))
                   ((eq head '|begin|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 0 nil)))
                      (if (null parts)
                          (return rontolisp::%scheme-unspecified)
                          (setq x
                                (rontolisp::%scheme-eval-butlast parts env)))))
                   ((eq head '|let|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 nil)))
                      (if (rontolisp::%scheme-eval-identifier-p (car parts))
                          ;; A named let, the inits evaluated outside it: a loop
                          ;; frame when the body only ever calls the name, else a
                          ;; procedure in a frame of its own, called once.
                          (let ((rest
                                 (rontolisp::%scheme-eval-proper (cdr parts) 2
                                                                 nil x))
                                (name (car parts)))
                            (let ((bindings
                                   (rontolisp::%scheme-eval-bindings (car rest)
                                                                     x)))
                              (let ((arguments
                                     (rontolisp::%scheme-eval-operands bindings
                                                                       env)))
                                (if (rontolisp::%scheme-eval-called-only-list
                                     name (cdr rest))
                                    (progn
                                      (setq env
                                            (cons (cons
                                                   (rontolisp::%scheme-eval-bind
                                                    (car bindings) arguments)
                                                   (list name (car bindings)
                                                         (cdr rest) env)) env))
                                      (setq x
                                            (rontolisp::%scheme-eval-butlast
                                             (cdr rest) env)))
                                    (let ((frame (cons nil nil)))
                                      (let ((procedure
                                             (rontolisp::%scheme-eval-lambda nil
                                              (car bindings) (cdr rest)
                                              (cons frame env) x)))
                                        (rplaca frame
                                                (list (cons name procedure)))
                                        (return
                                         (rontolisp::%scheme-eval-apply
                                          procedure arguments))))))))
                          (let ((bindings
                                 (rontolisp::%scheme-eval-bindings (car parts)
                                                                   x)))
                            (setq env
                                  (cons (cons (rontolisp::%scheme-eval-bind
                                               (car bindings)
                                               (rontolisp::%scheme-eval-operands
                                                bindings env)) nil) env))
                            (setq x
                                  (rontolisp::%scheme-eval-butlast (cdr parts)
                                                                   env))))))
                   ((eq head '|let*|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 nil)))
                      (let ((bindings
                             (rontolisp::%scheme-eval-bindings (car parts) x)))
                        (do ((names (car bindings) (cdr names))
                             (inits (cdr bindings) (cdr inits)))
                            ((null names))
                          (setq env
                                (cons (cons (list
                                             (cons (car names)
                                                   (rontolisp::%scheme-eval
                                                    (car inits) env))) nil)
                                      env)))
                        (setq x
                         (rontolisp::%scheme-eval-butlast (cdr parts) env)))))
                   ((or (eq head '|letrec|) (eq head '|letrec*|))
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 nil)))
                      (let ((bindings
                             (rontolisp::%scheme-eval-bindings (car parts) x)))
                        (setq env
                              (cons (cons (mapcar
                                           (lambda (name) (cons name nil))
                                           (car bindings)) nil) env))
                        (do ((names (car bindings) (cdr names))
                             (inits (cdr bindings) (cdr inits)))
                            ((null names))
                          (rontolisp::%scheme-eval-assign (car names)
                           (rontolisp::%scheme-eval-named (car names)
                                                          (car inits) env x)
                           env))
                        (setq x
                         (rontolisp::%scheme-eval-butlast (cdr parts) env)))))
                   ((eq head '|do|)
                    ;; The variables are rebound per iteration, so a closure keeps
                    ;; its iteration's bindings, as the front end's loops do.
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 nil)))
                      (let ((specs
                             (rontolisp::%scheme-eval-do-specs (car parts) x))
                            (exit
                             (rontolisp::%scheme-eval-proper (car (cdr parts)) 1
                                                             nil x))
                            (body (cdr (cdr parts)))
                            (outer env))
                        (setq env
                              (cons (cons (rontolisp::%scheme-eval-bind
                                           (car specs)
                                           (rontolisp::%scheme-eval-operands
                                            (cons nil (car (cdr specs))) outer))
                                          nil) outer))
                        (do ()
                            ((not
                              (eq (rontolisp::%scheme-eval (car exit) env)
                                  rontolisp::%scheme-false)))
                          (dolist (form body)
                            (rontolisp::%scheme-eval form env))
                          (setq env
                                (cons (cons (rontolisp::%scheme-eval-bind
                                             (car specs)
                                             (rontolisp::%scheme-eval-operands
                                              (cons nil (cdr (cdr specs))) env))
                                            nil) outer)))
                        (if (null (cdr exit))
                            (return rontolisp::%scheme-unspecified)
                            (setq x
                                  (rontolisp::%scheme-eval-butlast (cdr exit)
                                                                   env))))))
                   ((eq head '|cond|)
                    (let ((taken (rontolisp::%scheme-eval-cond (cdr x) env x)))
                      (cond
                       ((null taken) (return rontolisp::%scheme-unspecified))
                       ((car taken) (return (cdr taken)))
                       (t
                        (setq x
                         (rontolisp::%scheme-eval-butlast (cdr taken) env))))))
                   ((eq head '|case|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 1 nil)))
                      (let ((taken
                             (rontolisp::%scheme-eval-case
                              (rontolisp::%scheme-eval (car parts) env)
                              (cdr parts) env x)))
                        (cond
                         ((null taken) (return rontolisp::%scheme-unspecified))
                         ((car taken) (return (cdr taken)))
                         (t (setq x
                                  (rontolisp::%scheme-eval-butlast (cdr taken)
                                                                   env)))))))
                   ((eq head '|and|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 0 nil))
                          (short nil))
                      (if (null parts) (return t))
                      (do ()
                          ((or short (null (cdr parts))))
                        (if (eq (rontolisp::%scheme-eval (car parts) env)
                                rontolisp::%scheme-false)
                            (setq short t)
                            (setq parts (cdr parts))))
                      (if short
                          (return rontolisp::%scheme-false)
                          (setq x (car parts)))))
                   ((eq head '|or|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 0 nil))
                          (found nil)
                          (value nil))
                      (if (null parts) (return rontolisp::%scheme-false))
                      (do ()
                          ((or found (null (cdr parts))))
                        (setq value (rontolisp::%scheme-eval (car parts) env))
                        (if (eq value rontolisp::%scheme-false)
                            (setq parts (cdr parts))
                            (setq found t)))
                      (if found (return value) (setq x (car parts)))))
                   ((or (eq head '|when|) (eq head '|unless|))
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 nil)))
                      (let ((false
                             (eq (rontolisp::%scheme-eval (car parts) env)
                                 rontolisp::%scheme-false)))
                        (if (if (eq head '|when|) (not false) false)
                            (setq x
                             (rontolisp::%scheme-eval-butlast (cdr parts) env))
                            (return rontolisp::%scheme-unspecified)))))
                   ((or (eq head '|delay|) (eq head '|delay-force|))
                    (let ((form (car (rontolisp::%scheme-eval-parts x 1 1)))
                          (inner env))
                      (return
                       (rontolisp::%scheme-delay (if (eq head '|delay|) 0 1)
                        (lambda () (rontolisp::%scheme-eval form inner))))))
                   ((eq head '|cons-stream|)
                    (let ((parts (rontolisp::%scheme-eval-parts x 2 2))
                          (inner env))
                      (return
                       (cons (rontolisp::%scheme-eval (car parts) env)
                             (rontolisp::%scheme-delay 0
                                                       (lambda ()
                                                         (rontolisp::%scheme-eval
                                                          (car (cdr parts))
                                                          inner)))))))
                   ((member head '(|unquote| |unquote-splicing| |else| |s%=>|))
                    (rontolisp::%scheme-ill-formed x))
                   (t (error "~A"
                             (rontolisp::%scheme-error-message
                              "Not supported inside eval:" (list x))))))))))
