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
        (values (intern (coerce (nreverse out) 'string))))
      ;; values: intern's second value is not Scheme's to answer.
      (values (intern name))))

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

;; How write spells a symbol: between vertical lines when the spelling would not read
;; back as that symbol (|foo bar|, ||, |1|, |+inf.0|, |a\x0a;b|), bare otherwise -- the
;; lines Gauche writes. The printer calls it only in a program that can hold such a
;; symbol (the rontolisp-scheme-bar-symbols feature, SchemeLibrary); SchemeNames spells
;; the same grammar for that decision. Change the two together. The spelling is a list
;; of characters, never a string: see %scheme-print-symbol.
(defun rontolisp::%scheme-write-symbol (symbol)
  (let ((chars (rontolisp::%scheme-symbol-chars symbol)))
    ;; The unspecified object and the environment are symbols printed as themselves.
    (if (or (eq symbol rontolisp::%scheme-unspecified)
            (string= (symbol-name symbol) "#[environment]")
            (rontolisp::%scheme-plain-identifier-p chars))
        (rontolisp::%scheme-print-symbol symbol)
        (progn
          (write-char #\|)
          (dolist (c chars)
            (let ((code (char-code c)))
              (cond ((= code 124) (write-string "\\|"))
                    ((= code 92) (write-string "\\\\"))
                    ((or (< code 32) (= code 127))
                     (write-string "\\x")
                     (write-char (char "01234567" (ash code -4)))
                     (write-char (char "0123456789abcdef" (logand code 15)))
                     (write-char #\;))
                    (t (write-char c)))))
          (write-char #\|)))))

;; The Scheme spelling of SYMBOL (SchemeNames.mangle undone) as a list of characters.
(defun rontolisp::%scheme-symbol-chars (symbol)
  (let ((name (symbol-name symbol)) (backward nil) (chars nil))
    (if (rontolisp::%scheme-escaped-p name)
        (progn
          (do ((i 2 (+ i 1)))
              ((>= i (length name)))
            (if (and (char= (char name i) #\%) (< (+ i 1) (length name)))
                (progn
                  (setq i (+ i 1))
                  (setq backward
                        (cons (if (char= (char name i) #\c) #\: (char name i))
                              backward)))
                (setq backward (cons (char name i) backward))))
          (dolist (c backward chars) (setq chars (cons c chars))))
        (do ((i (- (length name) 1) (- i 1)))
            ((< i 0) chars)
          (setq chars (cons (char name i) chars))))))

;; R7RS 7.1.1 <initial>, every non-ASCII character counted as a letter (Gauche writes
;; lambda bare).
(defun rontolisp::%scheme-identifier-initial-p (c)
  (let ((code (char-code c)))
    (or (and (>= code 97) (<= code 122)) (and (>= code 65) (<= code 90))
        (>= code 128) (= code 33) (and (>= code 36) (<= code 38)) (= code 42)
        (= code 47) (= code 58) (and (>= code 60) (<= code 63)) (= code 94)
        (= code 95) (= code 126))))

(defun rontolisp::%scheme-sign-subsequent-p (c)
  (let ((code (char-code c)))
    (or (rontolisp::%scheme-identifier-initial-p c) (= code 43) (= code 45)
        (= code 64))))

(defun rontolisp::%scheme-identifier-subsequent-p (c)
  (let ((code (char-code c)))
    ;; . / and the digits; / is an initial anyway.
    (or (rontolisp::%scheme-sign-subsequent-p c)
        (and (>= code 46) (<= code 57)))))

(defun rontolisp::%scheme-dot-subsequent-p (c)
  (or (char= c #\.) (rontolisp::%scheme-sign-subsequent-p c)))

;; Whether CHARS read back as the identifier they spell: R7RS <identifier> without the
;; vertical lines, less the <infnan> spellings a reader takes for numbers.
(defun rontolisp::%scheme-plain-identifier-p (chars)
  (if (or (null chars) (rontolisp::%scheme-infnan-chars-p chars))
      nil
      (let ((c (car chars)) (rest (cdr chars)))
        ;; The tail left for <subsequent>*, or T when the start is not an identifier's.
        (let ((tail
               (cond ((rontolisp::%scheme-identifier-initial-p c) rest)
                     ((or (char= c #\+) (char= c #\-))
                      (cond ((null rest) nil)
                            ((rontolisp::%scheme-sign-subsequent-p (car rest))
                             (cdr rest))
                            ((and (char= (car rest) #\.) (cdr rest)
                                  (rontolisp::%scheme-dot-subsequent-p
                                   (car (cdr rest))))
                             (cdr (cdr rest)))
                            (t t)))
                     ((and (char= c #\.) rest
                           (rontolisp::%scheme-dot-subsequent-p (car rest)))
                      (cdr rest))
                     (t t))))
          (and (not (eq tail t))
               (do ((l tail (cdr l)))
                   ((or (null l)
                     (not (rontolisp::%scheme-identifier-subsequent-p (car l))))
                    (null l))))))))

;; Whether CHARS spell +inf.0 -inf.0 +nan.0 or -nan.0, ignoring ASCII case.
(defun rontolisp::%scheme-infnan-chars-p (chars)
  (and chars (or (char= (car chars) #\+) (char= (car chars) #\-))
       (let ((inf t) (nan t) (i 0))
         (dolist (c (cdr chars))
           (if (>= i 5)
               (progn
                 (setq inf nil)
                 (setq nan nil))
               (let ((code (rontolisp::%scheme-ascii-downcase (char-code c))))
                 (if (/= code (char-code (char "inf.0" i))) (setq inf nil))
                 (if (/= code (char-code (char "nan.0" i))) (setq nan nil))))
           (setq i (+ i 1)))
         (and (= i 5) (or inf nan)))))

(defun rontolisp::%scheme-ascii-downcase (code)
  (if (and (>= code 65) (<= code 90)) (+ code 32) code))

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
        #-rontolisp-scheme-bar-symbols
        ((symbolp x) (rontolisp::%scheme-print-symbol x))
        ;; Only a program that can hold a symbol write must put between vertical lines
        ;; has this arm (SchemeLibrary), so every other printer keeps its bytes.
        #+rontolisp-scheme-bar-symbols
        ((symbolp x)
         (if escape
             (rontolisp::%scheme-write-symbol x)
             (rontolisp::%scheme-print-symbol x)))
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
        ;; A bytevector is the (unsigned-byte 8) pack. The arm exists only in a program
        ;; that can make one (SchemeLibrary reads this file with the feature then), so a
        ;; program that cannot keeps its printer byte for byte.
        #+rontolisp-scheme-bytevectors
        ((rontolisp::%scheme-bytevector-p x)
         (write-string "#u8(")
         (do ((i 0 (+ i 1)))
             ((>= i (length x)))
           (if (> i 0) (write-char #\Space))
           (princ (aref x i)))
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
        #+rontolisp-scheme-ports
        ((rontolisp::%scheme-port-p x) (rontolisp::%scheme-print-port x))
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
;; A Lisp-level pushback cell (a list, so "#|" can be un-read as two characters): in a
;; program without ports, ONE cell keyed on the current *standard-input* value --
;; with-input-from-string rebinds the stream, and the cell follows it rather than
;; leaking across bindings (one stream at a time, like CL's unread-char cell); with
;; ports, the cell of the port being read. peek is read + pushback, never CL's peek-char,
;; so no WASM peek slot is ever parked and read/read-line/char-ready? mix freely.
;; char-ready? is (listen) where threads exist; on WASM there is no non-blocking probe,
;; so it answers #t (true for a string stream with data and at EOF, the cases the
;; tests pin; a terminal with nothing typed is the stated deviation).

(defstruct (rontolisp::%scheme-eof (:constructor rontolisp::%make-scheme-eof)
                                   (:copier nil)))

(defvar rontolisp::%scheme-eof-instance (rontolisp::%make-scheme-eof))

(defun rontolisp::%scheme-eof-object? (x) (rontolisp::%scheme-eof-p x))

;; (eof-object) calls this rather than reading the variable: the interpreter loads this
;; library on the first resolution of one of its FUNCTIONS, so a bare variable
;; reference as a program's first use of it would be unbound.
(defun rontolisp::%scheme-eof-object () rontolisp::%scheme-eof-instance)

(defvar rontolisp::%scheme-dot (list nil))

(defvar rontolisp::%scheme-close (list nil))

;; Without ports -- a program that makes no port object -- the reader's state is ONE
;; pushback cell and one fold-case flag, keyed on the current *standard-input*.
#-rontolisp-scheme-ports (defvar rontolisp::%scheme-pushback-chars nil)

#-rontolisp-scheme-ports (defvar rontolisp::%scheme-pushback-stream nil)

;; #!fold-case / #!no-fold-case (R7RS 7.1.1): a directive is per FILE, so it is kept
;; alongside the same stream-keyed state as the pushback cell above and reset the same
;; way -- whichever stream *standard-input* names next starts with folding off.
#-rontolisp-scheme-ports (defvar rontolisp::%scheme-fold-case nil)

#-rontolisp-scheme-ports (defvar rontolisp::%scheme-fold-case-stream nil)

#-rontolisp-scheme-ports
(defun rontolisp::%scheme-pushback-sync ()
  (if (not (eq rontolisp::%scheme-fold-case-stream *standard-input*))
      (progn
        (setq rontolisp::%scheme-fold-case-stream *standard-input*)
        (setq rontolisp::%scheme-fold-case nil)))
  (if (and rontolisp::%scheme-pushback-chars
           (not (eq rontolisp::%scheme-pushback-stream *standard-input*)))
      (progn
        (setq rontolisp::%scheme-pushback-chars nil)
        (setq rontolisp::%scheme-pushback-stream nil))))

#-rontolisp-scheme-ports
(defun rontolisp::%scheme-peek-char ()
  (rontolisp::%scheme-pushback-sync)
  (if rontolisp::%scheme-pushback-chars
      (car rontolisp::%scheme-pushback-chars)
      (let ((c (read-char nil nil rontolisp::%scheme-eof-instance)))
        (setq rontolisp::%scheme-pushback-stream *standard-input*)
        (setq rontolisp::%scheme-pushback-chars (list c))
        c)))

#-rontolisp-scheme-ports
(defun rontolisp::%scheme-next-char ()
  (rontolisp::%scheme-pushback-sync)
  (if rontolisp::%scheme-pushback-chars
      (let ((c (car rontolisp::%scheme-pushback-chars)))
        (setq rontolisp::%scheme-pushback-chars
              (cdr rontolisp::%scheme-pushback-chars))
        c)
      (read-char nil nil rontolisp::%scheme-eof-instance)))

#-rontolisp-scheme-ports
(defun rontolisp::%scheme-pushback (c)
  (rontolisp::%scheme-pushback-sync)
  (setq rontolisp::%scheme-pushback-stream *standard-input*)
  (setq rontolisp::%scheme-pushback-chars
        (cons c rontolisp::%scheme-pushback-chars))
  c)

;; With ports, the state lives in the port being read: an explicit port argument binds
;; %scheme-reading-port, and no argument reads the current input port -- the parameterized
;; one, or a wrapper around whatever *standard-input* is (the ports section below).
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-reading-port ()
  (or rontolisp::%scheme-reading-port (rontolisp::%scheme-current-port 0)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-peek-char ()
  (let ((port (rontolisp::%scheme-reading-port)))
    (if (rontolisp::%scheme-port-pushback port)
        (car (rontolisp::%scheme-port-pushback port))
        (let ((c
               (read-char (rontolisp::%scheme-port-stream port) nil
                          rontolisp::%scheme-eof-instance)))
          (setf (rontolisp::%scheme-port-pushback port) (list c))
          c))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-next-char ()
  (let ((port (rontolisp::%scheme-reading-port)))
    (if (rontolisp::%scheme-port-pushback port)
        (let ((c (car (rontolisp::%scheme-port-pushback port))))
          (setf (rontolisp::%scheme-port-pushback port)
                (cdr (rontolisp::%scheme-port-pushback port)))
          c)
        (read-char (rontolisp::%scheme-port-stream port) nil
                   rontolisp::%scheme-eof-instance))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-pushback (c)
  (let ((port (rontolisp::%scheme-reading-port)))
    (setf (rontolisp::%scheme-port-pushback port)
          (cons c (rontolisp::%scheme-port-pushback port)))
    c))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-set-fold-case (on)
  (setf (rontolisp::%scheme-port-fold-case (rontolisp::%scheme-reading-port))
        on))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-folding-p ()
  (rontolisp::%scheme-port-fold-case (rontolisp::%scheme-reading-port)))

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
  (rontolisp::%scheme-raise
   (make-condition 'rontolisp::%scheme-read-error-condition
                   :message message
                   :irritants (if datum (list datum) nil))))

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
                ((and (not (rontolisp::%scheme-eof-p d)) (= (char-code d) 33))
                 (rontolisp::%scheme-next-char)
                 (rontolisp::%scheme-read-directive))
                (t
                 (rontolisp::%scheme-pushback (code-char 35))
                 (return nil)))))
            (t (return nil))))))

;; #!fold-case / #!no-fold-case, the '!' already consumed: an R7RS <directive>, part of
;; <atmosphere> like a comment -- no datum, only the side effect of toggling folding for
;; the rest of this stream (or until the counterpart directive).
(defun rontolisp::%scheme-read-directive ()
  (let ((first (rontolisp::%scheme-next-char)))
    (if (rontolisp::%scheme-eof-p first)
        (rontolisp::%scheme-read-error "unsupported '#' syntax" "#!")
        (let ((word (rontolisp::%scheme-accumulate-token first)))
          (cond ((string= word "fold-case")
                 #-rontolisp-scheme-ports (setq rontolisp::%scheme-fold-case t)
                 #+rontolisp-scheme-ports (rontolisp::%scheme-set-fold-case t))
                ((string= word "no-fold-case")
                 #-rontolisp-scheme-ports
                 (setq rontolisp::%scheme-fold-case nil)
                 #+rontolisp-scheme-ports
                 (rontolisp::%scheme-set-fold-case nil))
                (t (rontolisp::%scheme-read-error "unsupported '#' syntax"
                    (concatenate 'string "#!" word))))))))

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
           (rontolisp::%scheme-next-char)
           (rontolisp::%scheme-read-bar-symbol))
          (t (rontolisp::%scheme-read-atom)))))

;; |...|, the opening line consumed: any characters up to the closing one, with the
;; escapes of a string except the line continuation. Never case-folded, never a number.
(defun rontolisp::%scheme-read-bar-symbol ()
  (let ((chars nil))
    (do ()
        (nil)
      (let ((c (rontolisp::%scheme-next-char)))
        (cond ((rontolisp::%scheme-eof-p c)
               (rontolisp::%scheme-read-error "unterminated '|' identifier"
                                              nil))
              ((= (char-code c) 124)
               (return
                (rontolisp::%scheme-string->symbol
                 (coerce (nreverse chars) (quote string)))))
              ((= (char-code c) 92)
               (let ((e (rontolisp::%scheme-next-char)))
                 (if (rontolisp::%scheme-eof-p e)
                     (rontolisp::%scheme-read-error
                      "unterminated '|' identifier" nil))
                 (let ((escaped (rontolisp::%scheme-read-escape e)))
                   (if (null escaped)
                       (rontolisp::%scheme-read-error
                        "unknown identifier escape" e))
                   (setq chars (cons escaped chars)))))
              (t (setq chars (cons c chars))))))))

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

(defun rontolisp::%scheme-read-bytevector ()
  (let ((elems nil))
    (do ()
        (nil)
      (rontolisp::%scheme-skip-atmosphere)
      (if (rontolisp::%scheme-eof-p (rontolisp::%scheme-peek-char))
          (rontolisp::%scheme-read-error "unclosed '#u8('" nil))
      (let ((datum (rontolisp::%scheme-read-datum)))
        (cond ((eq datum rontolisp::%scheme-close)
               (return (rontolisp::%scheme-bytevector (nreverse elems))))
              ((eq datum rontolisp::%scheme-dot)
               (rontolisp::%scheme-read-error "a bytevector cannot be dotted"
                                              nil))
              ((not (and (integerp datum) (<= 0 datum) (<= datum 255)))
               (rontolisp::%scheme-read-error
                "a bytevector element must be a byte (0-255)" datum))
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
                (t (let ((number (rontolisp::%scheme-string->number token 10)))
                     (if (not (eq number rontolisp::%scheme-false))
                         number
                         (rontolisp::%scheme-string->symbol
                          (if #-rontolisp-scheme-ports
                              rontolisp::%scheme-fold-case
                              #+rontolisp-scheme-ports
                              (rontolisp::%scheme-folding-p)
                              (rontolisp::%scheme-string-foldcase token)
                              token))))))))))

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
                   ((and (string-equal token "#u8")
                         (not
                          (rontolisp::%scheme-eof-p
                           (rontolisp::%scheme-peek-char)))
                         (= (char-code (rontolisp::%scheme-peek-char)) 40))
                    (rontolisp::%scheme-next-char)
                    (rontolisp::%scheme-read-bytevector))
                   ((>= (length token) 3)
                    (rontolisp::%scheme-hash-token-datum token))
                   (t (rontolisp::%scheme-read-error "unsupported '#' syntax"
                                                     token))))))))

(defun rontolisp::%scheme-read-hash-token ()
  (let ((c (rontolisp::%scheme-next-char)))
    (rontolisp::%scheme-accumulate-token c)))

;; TOKEN is the whole "#..." spelling (its caller only calls here once it is at least
;; three characters); %scheme-string->number reads its own #x/#b/#o/#d/#e/#i prefixes, so
;; this is just that call plus the read error a bad '#' token raises instead of #f.
(defun rontolisp::%scheme-hash-token-datum (token)
  (let ((number (rontolisp::%scheme-string->number token 10)))
    (if (eq number rontolisp::%scheme-false)
        (rontolisp::%scheme-read-error "unsupported '#' syntax" token)
        number)))

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
              (let* ((name (coerce (nreverse chars) (quote string)))
                     ;; #!fold-case folds the NAME, not the character it names -- the
                     ;; single-codepoint case above (an unadorned #\A) never reaches
                     ;; here.
                     (lookup
                      (if #-rontolisp-scheme-ports rontolisp::%scheme-fold-case
                          #+rontolisp-scheme-ports
                          (rontolisp::%scheme-folding-p)
                          (rontolisp::%scheme-string-foldcase name)
                          name)))
                (cond ((string= lookup "alarm") (code-char 7))
                      ((string= lookup "backspace") (code-char 8))
                      ((string= lookup "delete") (code-char 127))
                      ((string= lookup "escape") (code-char 27))
                      ((string= lookup "newline") (code-char 10))
                      ((string= lookup "null") (code-char 0))
                      ((string= lookup "nul") (code-char 0))
                      ((string= lookup "return") (code-char 13))
                      ((string= lookup "space") (code-char 32))
                      ((string= lookup "tab") (code-char 9))
                      ((string= lookup "linefeed") (code-char 10))
                      ((= (char-code (char lookup 0)) 120)
                       (let ((value
                              (rontolisp::%scheme-parse-hex (subseq lookup 1))))
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
                 (if (rontolisp::%scheme-eof-p e)
                     (rontolisp::%scheme-read-error "unterminated string" nil))
                 (let ((escaped (rontolisp::%scheme-read-escape e)))
                   (cond (escaped (setq chars (cons escaped chars)))
                         ((not (rontolisp::%scheme-string-continuation e))
                          (rontolisp::%scheme-read-error "unknown string escape"
                                                         e))))))
              (t (setq chars (cons c chars))))))))

;; The character a backslash escape E stands for inside a string or a |...| identifier --
;; \n \t \r \a \b \" \\ \| and \xHH; (reading the hex digits) -- or NIL for any other E.
(defun rontolisp::%scheme-read-escape (e)
  (let ((code (char-code e)))
    (cond ((= code 110) (code-char 10))
          ((= code 116) (code-char 9))
          ((= code 114) (code-char 13))
          ((= code 97) (code-char 7))
          ((= code 98) (code-char 8))
          ((or (= code 34) (= code 92) (= code 124)) e)
          ((= code 120)
           (let ((hex nil))
             (do ((d
                   (rontolisp::%scheme-peek-char)
                   (rontolisp::%scheme-peek-char)))
                 ((or (rontolisp::%scheme-eof-p d) (= (char-code d) 59)))
               (setq hex (cons (rontolisp::%scheme-next-char) hex)))
             (if (rontolisp::%scheme-eof-p (rontolisp::%scheme-peek-char))
                 (rontolisp::%scheme-read-error "unterminated \\x escape" nil))
             (rontolisp::%scheme-next-char)
             (let ((value
                    (rontolisp::%scheme-parse-hex
                     (coerce (nreverse hex) (quote string)))))
               (if (null value)
                   (rontolisp::%scheme-read-error "malformed \\x escape" nil)
                   (code-char value)))))
          (t nil))))

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

#-rontolisp-scheme-ports
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

;; With ports the pushback is the port's, and next-char already takes it first.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-line ()
  (let ((chars nil))
    (do ()
        (nil)
      (let ((c (rontolisp::%scheme-next-char)))
        (cond ((rontolisp::%scheme-eof-p c)
               (return
                (if (null chars)
                    rontolisp::%scheme-eof-instance
                    (rontolisp::%scheme-strip-cr
                     (coerce (nreverse chars) (quote string))))))
              ((= (char-code c) 10)
               (return
                (rontolisp::%scheme-strip-cr
                 (coerce (nreverse chars) (quote string)))))
              (t (setq chars (cons c chars))))))))

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
                   #+rontolisp-scheme-bytevectors
                   (eq (rontolisp::%scheme-bytevector-p x)
                       (rontolisp::%scheme-bytevector-p y))
                   (= (length x) (length y)))
              (do ((i 0 (+ i 1)))
                  ((>= i (length x)) t)
                (if (not (rontolisp::%scheme-equal? (aref x i) (aref y i)))
                    (return nil))))
             (t nil)))
    (if (not (rontolisp::%scheme-equal? (car x) (car y))) (return nil))))

;; --- bytevectors: the (unsigned-byte 8) pack (.kb/packed-integer-vectors.md) -------
;;
;; Every constructor refuses what is not a byte: the pack itself would mask 256 to 0.
;; A function spelling (unsigned-byte 8) or string-to-octets, and every caller of one, is
;; what tells SchemeLibrary.makesBytevectors that a program can make a bytevector.

(defun rontolisp::%scheme-bytevector-p (x)
  (typep x '(simple-array (unsigned-byte 8) (*))))

(defun rontolisp::%scheme-byte (who x)
  (if (and (integerp x) (<= 0 x) (<= x 255))
      x
      (error "~A"
             (rontolisp::%scheme-error-message
              (concatenate 'string who ": not a byte:") (list x)))))

(defun rontolisp::%scheme-make-bytevector (n fill)
  (make-array n
   :element-type '(unsigned-byte 8)
   :initial-element (rontolisp::%scheme-byte "make-bytevector" fill)))

(defun rontolisp::%scheme-bytevector (bytes)
  (let ((v (make-array (length bytes) :element-type '(unsigned-byte 8))))
    (do ((rest bytes (cdr rest)) (i 0 (+ i 1)))
        ((null rest) v)
      (setf (aref v i) (rontolisp::%scheme-byte "bytevector" (car rest))))))

(defun rontolisp::%scheme-bytevector-append (bytevectors)
  (let ((n 0))
    (dolist (b bytevectors) (setq n (+ n (length b))))
    (let ((v (make-array n :element-type '(unsigned-byte 8))) (at 0))
      (dolist (b bytevectors v)
        (replace v b :start1 at)
        (setq at (+ at (length b)))))))

(defun rontolisp::%scheme-string->utf8 (s) (rontolisp:string-to-octets s))

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
  ;; Floyd: FAST takes two steps per SLOW's one, so a circular list meets itself (#f)
  ;; instead of walking forever.
  (let ((slow x) (fast x))
    (do ()
        (nil)
      (if (not (consp fast)) (return (null fast)))
      (setq fast (cdr fast))
      (if (not (consp fast)) (return (null fast)))
      (setq fast (cdr fast))
      (setq slow (cdr slow))
      (if (eq fast slow) (return nil)))))

;; R7RS list-copy: the spine is copied up to its last pair, a dotted tail is kept, and
;; an object that is not a pair is answered itself.
(defun rontolisp::%scheme-list-copy (x)
  (if (consp x)
      (let* ((head (cons (car x) nil)) (tail head))
        (do ((rest (cdr x) (cdr rest)))
            ((not (consp rest))
             (rplacd tail rest)
             head)
          (let ((cell (cons (car rest) nil)))
            (rplacd tail cell)
            (setq tail cell))))
      x))

;; SRFI-1 / MIT reduce: (f elem acc) left to right, seeded with the first element.
(defun rontolisp::%scheme-reduce (f initial list)
  (if (consp list)
      (let ((acc (car list)))
        (do ((rest (cdr list) (cdr rest)))
            ((not (consp rest)) acc)
          (setq acc (funcall f (car rest) acc))))
      initial))

;; Whether any of LISTS has run out: the multi-list folds stop at the shortest.
(defun rontolisp::%scheme-any-empty (lists)
  (do ((rest lists (cdr rest)))
      ((null rest) nil)
    (if (not (consp (car rest))) (return t))))

;; MIT fold-left / fold-right over several lists: (f acc e1 e2 ...) from the left,
;; (f e1 e2 ... acc) from the right.
(defun rontolisp::%scheme-fold-left (f initial lists)
  (let ((acc initial))
    (do ((rest lists (mapcar #'cdr rest)))
        ((rontolisp::%scheme-any-empty rest) acc)
      (setq acc (apply f acc (mapcar #'car rest))))))

(defun rontolisp::%scheme-fold-right (f initial lists)
  (let ((rows nil) (acc initial))
    (do ((rest lists (mapcar #'cdr rest)))
        ((rontolisp::%scheme-any-empty rest))
      (setq rows (cons (mapcar #'car rest) rows)))
    (do ((rest rows (cdr rest)))
        ((null rest) acc)
      (setq acc (apply f (append (car rest) (list acc)))))))

;; string / list->string: every element must be a character, (string #\a 1) is an error
;; rather than "a1".
(defun rontolisp::%scheme-list->string (who list)
  (do ((rest list (cdr rest)))
      ((not (consp rest)))
    (if (not (characterp (car rest)))
        (error "~A"
               (rontolisp::%scheme-error-message
                (concatenate 'string who ": not a character:")
                (list (car rest))))))
  (coerce list 'string))

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

;; --- (scheme char) -------------------------------------------------------------------
;; The Unicode properties are the JDK's, read from range tables SchemeCharacters
;; generates and appends to this library (%scheme-alphabetic-ranges and the rest): a
;; string of inclusive [from, to] pairs, each bound four base-64 characters (48 + d,
;; skipping the backslash), decoded into a vector on first use. ASCII answers first
;; without a table. Case folding is
;; spelled again in SchemeCharacters.foldcase, which #!fold-case uses at compile time;
;; change the two together.

;; A generated table, decoded into a simple vector of its bounds.
(defun rontolisp::%scheme-decode-ranges (table)
  (let* ((n (floor (length table) 4))
         (bounds (make-array n :initial-element 0)))
    (dotimes (i n bounds)
      (let ((value 0))
        (dotimes (k 4)
          (let ((d (- (char-code (char table (+ (* i 4) k))) 48)))
            (setq value (+ (* value 64) (if (> d 43) (- d 1) d)))))
        (setf (svref bounds i) value)))))

;; The index of the [from, to] pair of the decoded BOUNDS holding CODE, or NIL.
(defun rontolisp::%scheme-range-index (code bounds)
  (let ((low 0) (high (- (ash (length bounds) -1) 1)) (found nil))
    (do ()
        ((or found (> low high)) found)
      (let ((middle (ash (+ low high) -1)))
        (cond ((< code (svref bounds (* 2 middle))) (setq high (- middle 1)))
         ((> code (svref bounds (+ (* 2 middle) 1))) (setq low (+ middle 1)))
         (t (setq found middle)))))))

(defun rontolisp::%scheme-char-alphabetic? (c)
  (let ((code (char-code c)))
    (if (< code 128)
        (or (<= 65 code 90) (<= 97 code 122))
        (if (rontolisp::%scheme-range-index code
             (rontolisp::%scheme-alphabetic-ranges))
            t
            nil))))

;; Numeric_Type=Decimal (general category Nd), which comes in runs of ten from a zero
;; -- adjacent runs share one table range (U+1D7CE..U+1D7FF is five), hence the mod.
(defun rontolisp::%scheme-digit-value (c)
  (let ((code (char-code c)))
    (if (< code 128)
        (if (<= 48 code 57) (- code 48) nil)
        (let* ((table (rontolisp::%scheme-decimal-ranges))
               (run (rontolisp::%scheme-range-index code table)))
          (if run (mod (- code (svref table (* 2 run))) 10) nil)))))

(defun rontolisp::%scheme-char-numeric? (c)
  (if (rontolisp::%scheme-digit-value c) t nil))

;; White_Space; SchemeCharacters.isWhiteSpace says the same through the JDK.
(defun rontolisp::%scheme-char-whitespace? (c)
  (let ((code (char-code c)))
    (or (<= 9 code 13) (= code 32) (= code 133) (= code 160) (= code 5760)
        (<= 8192 code 8202) (= code 8232) (= code 8233) (= code 8239)
        (= code 8287) (= code 12288))))

;; The Uppercase property: "has a lowercase mapping", corrected by a table where
;; the two disagree (U+2102 has none and is uppercase, a titlecase letter has one
;; and is not).
(defun rontolisp::%scheme-char-upper-case? (c)
  (let ((code (char-code c)))
    (if (< code 128)
        (<= 65 code 90)
        (let ((mapped (char/= c (char-downcase c))))
          (if (rontolisp::%scheme-range-index code
               (rontolisp::%scheme-uppercase-exceptions))
              (not mapped)
              mapped)))))

(defun rontolisp::%scheme-char-lower-case? (c)
  (let ((code (char-code c)))
    (if (< code 128)
        (<= 97 code 122)
        (let ((mapped (char/= c (char-upcase c))))
          (if (rontolisp::%scheme-range-index code
               (rontolisp::%scheme-lowercase-exceptions))
              (not mapped)
              mapped)))))

;; Unicode simple case folding: the lowercase of the uppercase, except that the
;; dotted and dotless i have none and Cherokee folds to its capitals.
(defun rontolisp::%scheme-char-foldcase (c)
  (let ((code (char-code c)))
    (cond ((< code 128) (char-downcase c))
          ((or (= code 304) (= code 305)) c)
          (t (let ((upper (char-upcase c)))
               (if (<= 5024 (char-code upper) 5109)
                   upper
                   (char-downcase upper)))))))

(defun rontolisp::%scheme-char-ci=? (a b)
  (char= (rontolisp::%scheme-char-foldcase a)
         (rontolisp::%scheme-char-foldcase b)))

(defun rontolisp::%scheme-char-ci<? (a b)
  (char< (rontolisp::%scheme-char-foldcase a)
         (rontolisp::%scheme-char-foldcase b)))

(defun rontolisp::%scheme-char-ci>? (a b)
  (char> (rontolisp::%scheme-char-foldcase a)
         (rontolisp::%scheme-char-foldcase b)))

(defun rontolisp::%scheme-char-ci<=? (a b)
  (char<= (rontolisp::%scheme-char-foldcase a)
          (rontolisp::%scheme-char-foldcase b)))

(defun rontolisp::%scheme-char-ci>=? (a b)
  (char>= (rontolisp::%scheme-char-foldcase a)
          (rontolisp::%scheme-char-foldcase b)))

(defun rontolisp::%scheme-ascii-string-p (s)
  (let ((n (length s)) (i 0))
    (do ()
        ((or (>= i n) (>= (char-code (char s i)) 128)) (>= i n))
      (setq i (+ i 1)))))

;; Pushes the characters of STRING onto the list ACCUMULATOR, mapped by F.
(defun rontolisp::%scheme-push-mapped (string f accumulator)
  (dotimes (i (length string) accumulator)
    (setq accumulator (cons (funcall f (char string i)) accumulator))))

;; The full mappings (R7RS 6.7): a character may map to several, so the result may
;; be longer than the argument. An ASCII string takes Common Lisp's per-character
;; fold, which is the same there.
(defun rontolisp::%scheme-string-upcase (s)
  (if (rontolisp::%scheme-ascii-string-p s)
      (string-upcase s)
      (let ((out nil))
        (dotimes (i (length s))
          (let* ((c (char s i))
                 (special (rontolisp::%scheme-special-upcase (char-code c))))
            (setq out
                  (if special
                      (rontolisp::%scheme-push-mapped special
                                                      (function identity) out)
                      (cons (char-upcase c) out)))))
        (coerce (nreverse out) (quote string)))))

(defun rontolisp::%scheme-string-foldcase (s)
  (if (rontolisp::%scheme-ascii-string-p s)
      (string-downcase s)
      (let ((out nil))
        (dotimes (i (length s))
          (let* ((c (char s i)) (code (char-code c)))
            (setq out
                  (cond ((= code 304) (cons (code-char 775) (cons #\i out)))
                        ((= code 7838) (cons #\s (cons #\s out)))
                        ((= code 305) (cons c out))
                        (t
                         (let ((special
                                (rontolisp::%scheme-special-upcase code)))
                           (if special
                               (rontolisp::%scheme-push-mapped special
                                (function rontolisp::%scheme-char-foldcase) out)
                               (cons (rontolisp::%scheme-char-foldcase c)
                                     out))))))))
        (coerce (nreverse out) (quote string)))))

;; Cased (Unicode 3.13): Uppercase, Lowercase or titlecase -- a titlecase letter has a
;; lowercase mapping.
(defun rontolisp::%scheme-cased-p (c)
  (or (rontolisp::%scheme-char-upper-case? c)
      (rontolisp::%scheme-char-lower-case? c) (char/= c (char-downcase c))))

(defun rontolisp::%scheme-case-ignorable-p (c)
  (if (rontolisp::%scheme-range-index (char-code c)
       (rontolisp::%scheme-case-ignorable-ranges))
      t
      nil))

;; Whether a cased character comes STEP-wise from index I of S, looking through
;; case-ignorable ones.
(defun rontolisp::%scheme-cased-beside-p (s i step)
  (let ((j (+ i step)) (n (length s)) (answer 0))
    (do ()
        ((not (eql answer 0)) answer)
      (if (or (< j 0) (>= j n))
          (setq answer nil)
          (let ((c (char s j)))
            (cond ((rontolisp::%scheme-cased-p c) (setq answer t))
                  ((rontolisp::%scheme-case-ignorable-p c) (setq j (+ j step)))
                  (t (setq answer nil))))))))

;; The Final_Sigma condition for the capital sigma at index I.
(defun rontolisp::%scheme-final-sigma-p (s i)
  (and (rontolisp::%scheme-cased-beside-p s i -1)
       (not (rontolisp::%scheme-cased-beside-p s i 1))))

(defun rontolisp::%scheme-string-downcase (s)
  (if (rontolisp::%scheme-ascii-string-p s)
      (string-downcase s)
      (let ((out nil))
        (dotimes (i (length s))
          (let* ((c (char s i)) (code (char-code c)))
            (setq out
                  (cond ((= code 304) (cons (code-char 775) (cons #\i out)))
                        ((= code 931)
                         (cons (code-char
                                (if (rontolisp::%scheme-final-sigma-p s i)
                                    962
                                    963)) out))
                        (t (cons (char-downcase c) out))))))
        (coerce (nreverse out) (quote string)))))

(defun rontolisp::%scheme-string-ci=? (a b)
  (string= (rontolisp::%scheme-string-foldcase a)
           (rontolisp::%scheme-string-foldcase b)))

(defun rontolisp::%scheme-string-ci<? (a b)
  (if (string< (rontolisp::%scheme-string-foldcase a)
               (rontolisp::%scheme-string-foldcase b))
      t
      nil))

(defun rontolisp::%scheme-string-ci>? (a b)
  (if (string> (rontolisp::%scheme-string-foldcase a)
               (rontolisp::%scheme-string-foldcase b))
      t
      nil))

(defun rontolisp::%scheme-string-ci<=? (a b)
  (if (string<= (rontolisp::%scheme-string-foldcase a)
                (rontolisp::%scheme-string-foldcase b))
      t
      nil))

(defun rontolisp::%scheme-string-ci>=? (a b)
  (if (string>= (rontolisp::%scheme-string-foldcase a)
                (rontolisp::%scheme-string-foldcase b))
      t
      nil))

;; --- numbers ----------------------------------------------------------------------

(defun rontolisp::%scheme-integer? (x)
  (or (integerp x)
      (and (floatp x) (rontolisp::%scheme-finite? x) (= x (truncate x)))))

;; X when it is an integer, exact or inexact; otherwise an error naming WHO (R7RS makes
;; a non-integer argument to quotient, gcd, odd? ... an error).
(defun rontolisp::%scheme-integer-argument (who x)
  (if (and (realp x) (rontolisp::%scheme-integer? x))
      x
      (error "~A"
             (rontolisp::%scheme-error-message
              (concatenate 'string who ": not an integer:") (list x)))))

;; EXACT made inexact when INEXACT is a flonum: plus a zero computed FROM that flonum.
;; Never `float`, which builds a flonum out of an exact value: that constructor would stay
;; reachable in a program that never makes a flonum, and the wasm type-test fold could no
;; longer drop the flonum arms of its arithmetic and printer (+12 KB for (quotient 17 5)).
(defun rontolisp::%scheme-inexact-like (exact inexact)
  (if (floatp inexact) (+ exact (- inexact inexact)) exact))

;; The integer divisions past their inline exact-integer path: an inexact operand makes
;; the quotient inexact, (quotient 7.0 2) is 3.0.
(defun rontolisp::%scheme-quotient (who a b)
  (rontolisp::%scheme-integer-argument who a)
  (rontolisp::%scheme-integer-argument who b)
  (rontolisp::%scheme-inexact-like
   (rontolisp::%scheme-inexact-like (values (truncate a b)) a) b))

(defun rontolisp::%scheme-floor-quotient (a b)
  (rontolisp::%scheme-integer-argument "floor-quotient" a)
  (rontolisp::%scheme-integer-argument "floor-quotient" b)
  (rontolisp::%scheme-inexact-like
   (rontolisp::%scheme-inexact-like (values (floor a b)) a) b))

(defun rontolisp::%scheme-odd? (x)
  (oddp (truncate (rontolisp::%scheme-integer-argument "odd?" x))))

(defun rontolisp::%scheme-even? (x)
  (evenp (truncate (rontolisp::%scheme-integer-argument "even?" x))))

;; gcd / lcm over any number of integers, inexact when any argument is: (gcd 2.0 4) is
;; 2.0.
(defun rontolisp::%scheme-gcd (arguments)
  (let ((result 0) (inexact nil))
    (do ((rest arguments (cdr rest)))
        ((null rest) (rontolisp::%scheme-inexact-like result inexact))
      (if (floatp (car rest)) (setq inexact (car rest)))
      (setq result
            (gcd result
                 (values
                  (truncate
                   (rontolisp::%scheme-integer-argument "gcd" (car rest)))))))))

(defun rontolisp::%scheme-lcm (arguments)
  (let ((result 1) (inexact nil))
    (do ((rest arguments (cdr rest)))
        ((null rest) (rontolisp::%scheme-inexact-like result inexact))
      (if (floatp (car rest)) (setq inexact (car rest)))
      (setq result
            (lcm result
                 (values
                  (truncate
                   (rontolisp::%scheme-integer-argument "lcm" (car rest)))))))))

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

;; floor, ceiling, round and truncate of a flonum, as a flonum. One of magnitude 2^52 or
;; more is integral already -- the infinities included -- and a NaN fails the test and
;; answers itself: Common Lisp's floor of a non-finite float answers a clamped fixnum.
(defun rontolisp::%scheme-flonum-floor (x)
  (if (< (abs x) 4503599627370496.0d0) (float (floor x) 1.0d0) x))

(defun rontolisp::%scheme-flonum-ceiling (x)
  (if (< (abs x) 4503599627370496.0d0) (float (ceiling x) 1.0d0) x))

(defun rontolisp::%scheme-flonum-round (x)
  (if (< (abs x) 4503599627370496.0d0) (float (round x) 1.0d0) x))

(defun rontolisp::%scheme-flonum-truncate (x)
  (if (< (abs x) 4503599627370496.0d0) (float (truncate x) 1.0d0) x))

;; exact of a flonum: an infinity or a NaN has no exact counterpart (R7RS 6.2.6 lets
;; exact raise an implementation restriction), refused by name on every backend. The
;; messages are constants: formatting the irritant would bring in the string-stream
;; machinery of %scheme-error-message (+25 KB of wasm for a lone (exact 2.5)).
(defun rontolisp::%scheme-exact-flonum (x)
  (cond ((/= x x) (error "exact: +nan.0 has no exact representation"))
        ((> x most-positive-double-float)
         (error "exact: +inf.0 has no exact representation"))
        ((< x most-negative-double-float)
         (error "exact: -inf.0 has no exact representation"))
        (t (rational x))))

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

(defun rontolisp::%scheme-integer->string (n radix)
  (if (zerop n)
      "0"
      (do ((m (abs n) (truncate m radix))
           (digits
            nil
            (cons (char "0123456789abcdefghijklmnopqrstuvwxyz" (rem m radix))
                  digits)))
          ((zerop m) (coerce (if (< n 0) (cons #\- digits) digits) 'string)))))

;; RADIX applies to every exact number, a ratio's numerator and denominator alike
;; ((number->string 1/3 2) is "1/11"); a flonum is written in decimal.
(defun rontolisp::%scheme-number->string (n radix)
  (cond ((floatp n)
         (with-output-to-string (*standard-output*)
           (rontolisp::%scheme-print-flonum n)))
        ((or (= radix 10) (not (rationalp n))) (princ-to-string n))
        ((integerp n) (rontolisp::%scheme-integer->string n radix))
        (t (concatenate 'string
            (rontolisp::%scheme-integer->string (numerator n) radix) "/"
            (rontolisp::%scheme-integer->string (denominator n) radix)))))

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
;;
;; S may itself carry an R7RS <prefix>: #x/#b/#o/#d picks the radix (overriding RADIX,
;; the caller's own default), #e/#i asks for an exact or an inexact result -- each at
;; most once, in either order (%scheme-number-prefix). An exactness prefix on an
;; infinity or a NaN is a no-op, same as Gauche; on a decimal it builds the exact
;; rational the digits spell rather than rounding through a double, so "#e1.1" reads
;; 11/10; on an already-exact result it is the plain float conversion.
(defun rontolisp::%scheme-string->number (s radix)
  (multiple-value-bind (digits digits-radix exactness)
      (rontolisp::%scheme-number-prefix s radix)
    (let ((infnan (rontolisp::%scheme-infnan digits)))
      (if infnan
          infnan
          (rontolisp::%scheme-string->real digits digits-radix exactness)))))

;; Reads S's leading #x/#b/#o/#d and #e/#i pairs (each at most once, either order) and
;; answers (values remainder radix exactness): RADIX is the caller's default unless a
;; radix pair overrides it, EXACTNESS is NIL/:exact/:inexact. A pair that repeats a kind
;; already seen, or is not one of these six letters, is left IN the remainder -- it then
;; fails %scheme-infnan / %scheme-string->real, so an invalid prefix ends up #f exactly
;; like an invalid number, the same way SchemeReader's prefixedNumber does for source.
(defun rontolisp::%scheme-number-prefix (s radix)
  (let ((n (length s)) (index 0) (radix-set nil) (exactness nil) (stop nil))
    (do ()
        (stop)
      (if (not (and (< (+ index 1) n) (char= (char s index) #\#)))
          (setq stop t)
          (let ((c
                 (rontolisp::%scheme-ascii-downcase
                  (char-code (char s (+ index 1))))))
            (let ((candidate
                   (cond ((= c (char-code #\x)) 16)
                         ((= c (char-code #\b)) 2)
                         ((= c (char-code #\o)) 8)
                         ((= c (char-code #\d)) 10)
                         (t nil))))
              (cond (candidate (if radix-set
                                   (setq stop t)
                                   (progn
                                     (setq radix candidate)
                                     (setq radix-set t)
                                     (setq index (+ index 2)))))
                    ((or (= c (char-code #\e)) (= c (char-code #\i)))
                     (if exactness
                         (setq stop t)
                         (progn
                           (setq exactness
                                 (if (= c (char-code #\i)) :inexact :exact))
                           (setq index (+ index 2)))))
                    (t (setq stop t)))))))
    (values (subseq s index) radix exactness)))

;; #i on an already-exact number converts to the nearest double; #e (or no exactness
;; prefix at all) leaves it exactly as it is.
(defun rontolisp::%scheme-apply-exactness (exact exactness)
  (if (eq exactness :inexact) (float exact 1.0d0) exact))

;; R7RS <infnan> -- +inf.0 -inf.0 +nan.0 -nan.0, case-insensitively and in any radix --
;; or NIL. A NaN's sign is not kept: every NaN is written +nan.0.
(defun rontolisp::%scheme-infnan (s)
  (if (and (= (length s) 6) (or (char= (char s 0) #\+) (char= (char s 0) #\-)))
      (let ((inf t) (nan t))
        (do ((i 1 (+ i 1)))
            ((>= i 6))
          (let ((code
                 (rontolisp::%scheme-ascii-downcase (char-code (char s i)))))
            (if (/= code (char-code (char "inf.0" (- i 1)))) (setq inf nil))
            (if (/= code (char-code (char "nan.0" (- i 1)))) (setq nan nil))))
        (if (or inf nan)
            (let ((infinity (* most-positive-double-float 2.0d0)))
              (cond (nan (- infinity infinity))
                    ((char= (char s 0) #\+) infinity)
                    (t (- infinity))))
            nil))
      nil))

(defun rontolisp::%scheme-string->real (s radix exactness)
  (let ((n (length s)) (start 0) (sign 1))
    (if (and (> n 0) (or (char= (char s 0) #\+) (char= (char s 0) #\-)))
        (progn
          (if (char= (char s 0) #\-) (setq sign -1))
          (setq start 1)))
    (let ((whole (rontolisp::%scheme-scan-digits s start radix)))
      (let ((whole-end (cdr whole)))
        (cond ((and (= whole-end n) (> whole-end start))
               (rontolisp::%scheme-apply-exactness (* sign (car whole))
                                                   exactness))
              ((and (> whole-end start) (< whole-end n)
                    (char= (char s whole-end) #\/))
               (let ((denominator
                      (rontolisp::%scheme-scan-digits s (+ whole-end 1) radix)))
                 (if (and (= (cdr denominator) n)
                          (> (cdr denominator) (+ whole-end 1))
                          (> (car denominator) 0))
                     (rontolisp::%scheme-apply-exactness
                      (/ (* sign (car whole)) (car denominator)) exactness)
                     rontolisp::%scheme-false)))
              ((= radix 10)
               (rontolisp::%scheme-decimal s
                (list sign start (car whole) whole-end) exactness))
              (t rontolisp::%scheme-false))))))

(defun rontolisp::%scheme-decimal (s state exactness)
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
          (if (eq exactness :exact)
              ;; The exact rational the digits spell -- mantissa * 10^(exponent-scale) --
              ;; never rounded through a double, so "#e1.1" reads 11/10.
              (* sign (/ mantissa (expt 10 scale)) (expt 10 exponent))
              ;; Negated AFTER the conversion, so "-0.0" keeps its sign.
              (let ((magnitude
                     (float (* mantissa (expt 10 (- exponent scale))) 1.0d0)))
                (if (< sign 0) (- magnitude) magnitude)))
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

;; --- parameter objects (R7RS 4.2.6) ------------------------------------------------
;; A parameter object is a closure over its record, which holds the global value and the
;; converter. parameterize binds %scheme-parameterizations, an alist of record -> value,
;; innermost first, with a special let: the restore then rides every exit channel the
;; backends give a special binding, and a thread starts from the global values. The
;; record is a defstruct so that nothing but a parameter object can answer one: the
;; token argument is how parameterize asks a procedure for it.
(defvar rontolisp::%scheme-parameterizations nil)

(defvar rontolisp::%scheme-parameter-token (list nil))

(defstruct (rontolisp::%scheme-parameter
            (:constructor rontolisp::%scheme-new-parameter (value converter))
            (:copier nil))
  value
  converter)

(defun rontolisp::%scheme-make-parameter (value converter)
  (let ((record
         (rontolisp::%scheme-new-parameter
          (if converter (funcall converter value) value) converter)))
    (lambda (&rest arguments)
      (cond ((null arguments) (rontolisp::%scheme-parameter-lookup record))
            ((and (eq (car arguments) rontolisp::%scheme-parameter-token)
                  (null (cdr arguments)))
             record)
            (t (error "~A"
                      (rontolisp::%scheme-error-message
                       "a parameter object takes no argument:" arguments)))))))

(defun rontolisp::%scheme-parameter-lookup (record)
  (do ((bindings rontolisp::%scheme-parameterizations (cdr bindings)))
      ((null bindings) (rontolisp::%scheme-parameter-value record))
    (if (eq (car (car bindings)) record) (return (cdr (car bindings))))))

;; PARAMETERS-AND-VALUES alternate, as parameterize wrote them. Every value is converted
;; before any is bound, so a converter's error leaves every parameter as it was.
(defun rontolisp::%scheme-parameterize (parameters-and-values body)
  (let ((bindings rontolisp::%scheme-parameterizations)
        (rest parameters-and-values)
        #+rontolisp-scheme-ports (ports nil))
    (do ()
        ((null rest))
      (let ((record
             (if (functionp (car rest))
                 (funcall (car rest) rontolisp::%scheme-parameter-token)
                 nil)))
        (if (not (rontolisp::%scheme-parameter-p record))
            (error "~A"
                   (rontolisp::%scheme-error-message
                    "parameterize: not a parameter object:" (list (car rest)))))
        (setq bindings
              (cons (cons record
                          (if (rontolisp::%scheme-parameter-converter record)
                              (funcall
                               (rontolisp::%scheme-parameter-converter record)
                               (car (cdr rest)))
                              (car (cdr rest)))) bindings))
        #+rontolisp-scheme-ports
        (setq ports
              (rontolisp::%scheme-note-port-binding ports record
                                                    (cdr (car bindings)))))
      (setq rest (cdr (cdr rest))))
    (let ((rontolisp::%scheme-parameterizations bindings))
      #-rontolisp-scheme-ports (funcall body)
      #+rontolisp-scheme-ports
      (if ports
          (rontolisp::%scheme-with-port-streams ports body)
          (funcall body)))))

;; --- ports (R7RS 6.13) --------------------------------------------------------------
;; A port is a record, never a bare Common Lisp stream: the standard streams are the T
;; designator on the compiled backends (not a value, .kb/read-load-streams.md) and
;; *error-output* answers a fresh wrapper per read there, so neither could be told
;; apart or compared. A textual port holds the Common Lisp stream it reads or writes;
;; a binary input port the bytevector and, in PUSHBACK, the read position; a binary
;; output port the bytes written so far, newest first. A textual input port keeps the
;; reader's own state -- its pushback characters and the #!fold-case flag -- so several
;; ports can be read in turn.
;;
;; The three current ports are parameter objects over these records. Parameterizing one
;; also binds the Common Lisp special it stands for (%scheme-with-port-streams), so
;; (display x) with no port, and Common Lisp code called from the body, write where
;; the port does. Not parameterized, a current port is a wrapper around what the
;; special holds now, cached while that stays the same object: (current-output-port)
;; answers one port, and a Common Lisp caller's with-output-to-string is honored.
;;
;; The whole section exists only under the ports feature, which SchemeLibrary turns on
;; for a program calling one of its functions: every other program -- (read) on
;; standard input included -- carries none of it, not even the record's layout.
#+rontolisp-scheme-ports
(defstruct (rontolisp::%scheme-port (:constructor rontolisp::%scheme-new-port
                                                  (input binary string stream
                                                         open pushback
                                                         fold-case))
                                    (:copier nil))
  input
  binary
  string
  stream
  open
  pushback
  fold-case
  ;; A file port ((scheme file)): STREAM is a Common Lisp file stream, which closing
  ;; the port closes; a binary input file port keeps its peeked byte in PUSHBACK.
  #+rontolisp-scheme-files file)

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-make-port (input binary string stream)
  (rontolisp::%scheme-new-port input binary string stream t
                               (if (and binary input) 0 nil) nil))

#+rontolisp-scheme-ports (defvar rontolisp::%scheme-reading-port nil)

;; Defuns, not the defstruct's own predicate and accessors: the interpreter loads this
;; library on the first resolution of one of its FUNCTIONS, which a template calling
;; only a structure predicate would never trigger.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-port? (x) (rontolisp::%scheme-port-p x))

;; DIRECTION-P: test the direction (input when WHICH) instead of the kind (binary when
;; WHICH).
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-port-kind? (x which direction-p)
  (and (rontolisp::%scheme-port-p x)
       (if direction-p
           (eq (rontolisp::%scheme-port-input x) which)
           (eq (rontolisp::%scheme-port-binary x) which))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-print-port (port)
  (write-string
   (if (rontolisp::%scheme-port-binary port) "#<binary-" "#<textual-"))
  (write-string
   (if (rontolisp::%scheme-port-input port) "input-port>" "output-port>")))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-port-error (who message port)
  (error "~A"
         (rontolisp::%scheme-error-message
          (concatenate 'string who ": " message) (list port))))

;; PORT, when it is an open port of the direction and kind asked for.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-check-port (who port input binary)
  (cond ((not
          (and (rontolisp::%scheme-port-p port)
               (eq (rontolisp::%scheme-port-input port) input)
               (eq (rontolisp::%scheme-port-binary port) binary)))
         (rontolisp::%scheme-port-error who
                                        (if binary
                                            (if input
                                                "not a binary input port:"
                                                "not a binary output port:")
                                            (if input
                                                "not a textual input port:"
                                                "not a textual output port:"))
                                        port))
        ((not (rontolisp::%scheme-port-open port))
         (rontolisp::%scheme-port-error who "the port is closed:" port))
        (t port)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-port-converter (who input)
  (lambda (port) (rontolisp::%scheme-check-port who port input nil)))

#+rontolisp-scheme-ports
(defvar rontolisp::%scheme-port-records
  (list (rontolisp::%scheme-new-parameter nil
         (rontolisp::%scheme-port-converter "current-input-port" t))
        (rontolisp::%scheme-new-parameter nil
         (rontolisp::%scheme-port-converter "current-output-port" nil))
        (rontolisp::%scheme-new-parameter nil
         (rontolisp::%scheme-port-converter "current-error-port" nil))))

;; The cached wrappers of the three standard streams, input, output, error.
#+rontolisp-scheme-ports
(defvar rontolisp::%scheme-port-wrappers (list nil nil nil))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-standard-stream (which)
  (cond ((eql which 0) *standard-input*)
        ((eql which 1) *standard-output*)
        (t *error-output*)))

;; The current input (0), output (1) or error (2) port. equal, not eq: the compiled
;; backends answer a new wrapper of one stream for every read of *error-output*.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-current-port (which)
  (let ((stream (rontolisp::%scheme-standard-stream which))
        (port
         (rontolisp::%scheme-parameter-lookup
          (nth which rontolisp::%scheme-port-records))))
    (if (and port (equal (rontolisp::%scheme-port-stream port) stream))
        port
        (let ((cell (nthcdr which rontolisp::%scheme-port-wrappers)))
          (if (and (car cell)
                   (equal (rontolisp::%scheme-port-stream (car cell)) stream))
              (car cell)
              (car
               (rplaca cell
                       (rontolisp::%scheme-make-port (eql which 0) nil nil
                                                     stream))))))))

;; current-input-port and the rest are parameter objects: called with no argument, the
;; current port; with the parameter token, their record (%scheme-parameterize).
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-port-parameter (which)
  (let ((record (nth which rontolisp::%scheme-port-records)))
    (lambda (&rest arguments)
      (cond ((null arguments) (rontolisp::%scheme-current-port which))
            ((and (eq (car arguments) rontolisp::%scheme-parameter-token)
                  (null (cdr arguments)))
             record)
            (t (error "~A"
                      (rontolisp::%scheme-error-message
                       "a parameter object takes no argument:" arguments)))))))

;; PORTS is (input output error), what this parameterize binds each current port to, or
;; NIL while it binds none: an ordinary parameterize then costs no frame more, and a
;; deep recursion through one reaches as deep as before.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-note-port-binding (ports record value)
  (do ((records rontolisp::%scheme-port-records (cdr records)) (i 0 (+ i 1)))
      ((null records) ports)
    (if (eq (car records) record)
        (let ((noted (or ports (list nil nil nil))))
          (rplaca (nthcdr i noted) value)
          (return noted)))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-with-port-streams (ports body)
  (let ((*standard-input*
         (if (car ports)
             (rontolisp::%scheme-port-stream (car ports))
             *standard-input*))
        (*standard-output*
         (if (car (cdr ports))
             (rontolisp::%scheme-port-stream (car (cdr ports)))
             *standard-output*))
        (*error-output*
         (if (car (cdr (cdr ports)))
             (rontolisp::%scheme-port-stream (car (cdr (cdr ports))))
             *error-output*)))
    (funcall body)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-open-input-string (s)
  (rontolisp::%scheme-make-port t nil t (make-string-input-stream s)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-open-output-string ()
  (rontolisp::%scheme-make-port nil nil t (make-string-output-stream)))

;; Common Lisp's get-output-stream-string empties the stream; R7RS's get-output-string
;; does not, so what it answers is written back.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-get-output-string (port)
  (if (not
       (and (rontolisp::%scheme-port-p port)
            (rontolisp::%scheme-port-string port)
            (not (rontolisp::%scheme-port-input port))))
      (rontolisp::%scheme-port-error "get-output-string"
                                     "not a string output port:" port)
      (let ((s
             (get-output-stream-string (rontolisp::%scheme-port-stream port))))
        (write-string s (rontolisp::%scheme-port-stream port))
        s)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-open-input-bytevector (bytes)
  (rontolisp::%scheme-make-port t t nil
                                (if (rontolisp::%scheme-bytevector-p bytes)
                                    (copy-seq bytes)
                                    (rontolisp::%scheme-port-error
                                     "open-input-bytevector" "not a bytevector:"
                                     bytes))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-open-output-bytevector ()
  (rontolisp::%scheme-make-port nil t nil nil))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-get-output-bytevector (port)
  (if (not
       (and (rontolisp::%scheme-port-p port)
            (rontolisp::%scheme-port-binary port)
            (not (rontolisp::%scheme-port-input port))
            #+rontolisp-scheme-files (not (rontolisp::%scheme-port-file port))))
      (rontolisp::%scheme-port-error "get-output-bytevector"
                                     "not a bytevector output port:" port)
      (rontolisp::%scheme-bytevector
       (reverse (rontolisp::%scheme-port-stream port)))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-output-stream (who port)
  (rontolisp::%scheme-port-stream
   (rontolisp::%scheme-check-port who port nil nil)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-display-to (x port)
  (let ((*standard-output* (rontolisp::%scheme-output-stream "display" port)))
    (rontolisp::%scheme-print x nil)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-write-to (who x port)
  (let ((*standard-output* (rontolisp::%scheme-output-stream who port)))
    (rontolisp::%scheme-print x t)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-write-shared-to (x port)
  (let ((*standard-output*
         (rontolisp::%scheme-output-stream "write-shared" port)))
    (rontolisp::%scheme-write-shared x)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-write-string-to (s port start end)
  (write-string (if (or start end) (subseq s (or start 0) end) s)
                (rontolisp::%scheme-output-stream "write-string" port)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-flush-output-port (port)
  (finish-output (rontolisp::%scheme-output-stream "flush-output-port" port)))

;; A textual input procedure with a port argument: the same procedure, reading PORT.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-input-port (who port)
  (rontolisp::%scheme-check-port who port t nil))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-from (port)
  (let ((rontolisp::%scheme-reading-port
         (rontolisp::%scheme-input-port "read" port)))
    (rontolisp::%scheme-read)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-char-from (port)
  (let ((rontolisp::%scheme-reading-port
         (rontolisp::%scheme-input-port "read-char" port)))
    (rontolisp::%scheme-next-char)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-peek-char-from (port)
  (let ((rontolisp::%scheme-reading-port
         (rontolisp::%scheme-input-port "peek-char" port)))
    (rontolisp::%scheme-peek-char)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-line-from (port)
  (let ((rontolisp::%scheme-reading-port
         (rontolisp::%scheme-input-port "read-line" port)))
    (rontolisp::%scheme-read-line)))

;; (read-string k): at most K characters, the EOF object when none is left.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-chars (k)
  (let ((chars nil) (n 0))
    (do ()
        ((>= n k))
      (let ((c (rontolisp::%scheme-next-char)))
        (if (rontolisp::%scheme-eof-p c)
            (return nil)
            (progn
              (setq chars (cons c chars))
              (setq n (+ n 1))))))
    (if (and (null chars) (> k 0))
        rontolisp::%scheme-eof-instance
        (coerce (nreverse chars) (quote string)))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-chars-from (k port)
  (let ((rontolisp::%scheme-reading-port
         (rontolisp::%scheme-input-port "read-string" port)))
    (rontolisp::%scheme-read-chars k)))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-char-ready-from (port)
  (rontolisp::%scheme-input-port "char-ready?" port)
  t)

;; Binary input: the bytevector in STREAM, the position in PUSHBACK.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-binary-input (who port)
  (rontolisp::%scheme-check-port who port t t))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-u8 (port advance)
  (let* ((port
          (rontolisp::%scheme-binary-input (if advance "read-u8" "peek-u8")
                                           port))
         #+rontolisp-scheme-files
         (port
          (if (rontolisp::%scheme-port-file port)
              (return-from rontolisp::%scheme-read-u8
                           (rontolisp::%scheme-file-read-u8 port advance))
              port))
         (bytes (rontolisp::%scheme-port-stream port))
         (at (rontolisp::%scheme-port-pushback port)))
    (if (>= at (length bytes))
        rontolisp::%scheme-eof-instance
        (progn
          (if advance (setf (rontolisp::%scheme-port-pushback port) (+ at 1)))
          (aref bytes at)))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-u8-ready (port)
  (rontolisp::%scheme-binary-input "u8-ready?" port)
  t)

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-bytes (k port)
  (let* ((port (rontolisp::%scheme-binary-input "read-bytevector" port))
         #+rontolisp-scheme-files
         (port
          (if (rontolisp::%scheme-port-file port)
              (return-from rontolisp::%scheme-read-bytes
                           (rontolisp::%scheme-file-read-bytes k port))
              port))
         (bytes (rontolisp::%scheme-port-stream port))
         (at (rontolisp::%scheme-port-pushback port))
         (end (min (length bytes) (+ at k))))
    (if (and (>= at (length bytes)) (> k 0))
        rontolisp::%scheme-eof-instance
        (progn
          (setf (rontolisp::%scheme-port-pushback port) end)
          (subseq bytes at end)))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-read-bytes! (to port start end)
  (let* ((port (rontolisp::%scheme-binary-input "read-bytevector!" port))
         #+rontolisp-scheme-files
         (port
          (if (rontolisp::%scheme-port-file port)
              (return-from rontolisp::%scheme-read-bytes!
               (rontolisp::%scheme-file-read-bytes! to port start end))
              port))
         (bytes (rontolisp::%scheme-port-stream port))
         (at (rontolisp::%scheme-port-pushback port))
         (end (or end (length to)))
         (n (max 0 (min (- end start) (- (length bytes) at)))))
    (if (and (= n 0) (< start end))
        rontolisp::%scheme-eof-instance
        (progn
          (do ((i 0 (+ i 1)))
              ((>= i n))
            (setf (aref to (+ start i)) (aref bytes (+ at i))))
          (setf (rontolisp::%scheme-port-pushback port) (+ at n))
          n))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-binary-output (who port)
  (rontolisp::%scheme-check-port who port nil t))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-write-u8 (byte port)
  (let ((port (rontolisp::%scheme-binary-output "write-u8" port)))
    #+rontolisp-scheme-files
    (if (rontolisp::%scheme-port-file port)
        (return-from rontolisp::%scheme-write-u8
                     (write-byte (rontolisp::%scheme-byte "write-u8" byte)
                                 (rontolisp::%scheme-port-stream port))))
    (setf (rontolisp::%scheme-port-stream port)
          (cons (rontolisp::%scheme-byte "write-u8" byte)
                (rontolisp::%scheme-port-stream port)))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-write-bytevector (bytes port start end)
  (let ((port (rontolisp::%scheme-binary-output "write-bytevector" port)))
    (do ((i start (+ i 1)))
        ((>= i (or end (length bytes))))
      #-rontolisp-scheme-files
      (setf (rontolisp::%scheme-port-stream port)
            (cons (aref bytes i) (rontolisp::%scheme-port-stream port)))
      #+rontolisp-scheme-files
      (if (rontolisp::%scheme-port-file port)
          (write-byte (aref bytes i) (rontolisp::%scheme-port-stream port))
          (setf (rontolisp::%scheme-port-stream port)
                (cons (aref bytes i) (rontolisp::%scheme-port-stream port)))))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-port-open-p (who port input)
  (if (and (rontolisp::%scheme-port-p port)
           (eq (rontolisp::%scheme-port-input port) input))
      (rontolisp::%scheme-port-open port)
      (rontolisp::%scheme-port-error who
       (if input "not an input port:" "not an output port:") port)))

;; close-port (INPUT :any), close-input-port (INPUT t), close-output-port (INPUT nil):
;; closing twice is harmless, and a standard port is only marked closed.
#+rontolisp-scheme-ports
(defun rontolisp::%scheme-close-port (who port input)
  (if (not
       (and (rontolisp::%scheme-port-p port)
        (or (eq input :any) (eq (rontolisp::%scheme-port-input port) input))))
      (rontolisp::%scheme-port-error who
                                     (cond ((eq input :any) "not a port:")
                                           (input "not an input port:")
                                           (t "not an output port:")) port)
      (progn
        #+rontolisp-scheme-files (rontolisp::%scheme-release-port port)
        (setf (rontolisp::%scheme-port-open port) nil))))

#+rontolisp-scheme-ports
(defun rontolisp::%scheme-call-with-port (port proc)
  (if (not (rontolisp::%scheme-port-p port))
      (rontolisp::%scheme-port-error "call-with-port" "not a port:" port))
  (let ((results (multiple-value-list (funcall proc port))))
    #+rontolisp-scheme-files (rontolisp::%scheme-release-port port)
    (setf (rontolisp::%scheme-port-open port) nil)
    (values-list results)))

;; --- file ports ((scheme file), R7RS 6.13.1) ----------------------------------------
;; A file port is the same record over a Common Lisp file stream. open's :direction and
;; :element-type must be literal (.kb/read-load-streams.md), so each opener spells its
;; own open. A failed open raises an error object file-error? answers #t for, whatever
;; the backend's own open signalled. Only under the files feature, which SchemeLibrary
;; turns on for a program calling one of these helpers.
#+rontolisp-scheme-files
(defun rontolisp::%scheme-file-error (who message path)
  (rontolisp::%scheme-raise
   (make-condition 'rontolisp::%scheme-file-error-condition
                   :message (concatenate 'string who ": " message)
                   :irritants (list path))))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-file-name (who path)
  (if (stringp path)
      path
      (rontolisp::%scheme-port-error who "not a file name:" path)))

;; STREAM is what the opener's open answered, NIL when it failed.
#+rontolisp-scheme-files
(defun rontolisp::%scheme-file-port (who path stream input binary)
  (if (null stream)
      (rontolisp::%scheme-file-error who "cannot open file:" path)
      (let ((port (rontolisp::%scheme-make-port input binary nil stream)))
        (setf (rontolisp::%scheme-port-file port) t)
        (if binary (setf (rontolisp::%scheme-port-pushback port) nil))
        port)))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-open-input-file (who path)
  (let ((path (rontolisp::%scheme-file-name who path)))
    (rontolisp::%scheme-file-port who path
     (handler-case (open path :direction :input) (error () nil)) t nil)))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-open-output-file (who path)
  (let ((path (rontolisp::%scheme-file-name who path)))
    (rontolisp::%scheme-file-port who path
                                  (handler-case (open path
                                                 :direction :output
                                                 :if-exists :supersede
                                                 :if-does-not-exist :create)
                                    (error () nil)) nil nil)))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-open-binary-input-file (path)
  (let ((path (rontolisp::%scheme-file-name "open-binary-input-file" path)))
    (rontolisp::%scheme-file-port "open-binary-input-file" path
                                  (handler-case (open path
                                                      :direction :input
                                                      :element-type
                                                      '(unsigned-byte 8))
                                    (error () nil)) t t)))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-open-binary-output-file (path)
  (let ((path (rontolisp::%scheme-file-name "open-binary-output-file" path)))
    (rontolisp::%scheme-file-port "open-binary-output-file" path
                                  (handler-case (open path
                                                 :direction :output
                                                 :element-type
                                                 '(unsigned-byte 8)
                                                 :if-exists :supersede
                                                 :if-does-not-exist :create)
                                    (error () nil)) nil t)))

;; Closing a file port closes its stream, once.
#+rontolisp-scheme-files
(defun rontolisp::%scheme-release-port (port)
  (if (and (rontolisp::%scheme-port-file port)
           (rontolisp::%scheme-port-open port))
      (close (rontolisp::%scheme-port-stream port))))

;; with-input-from-file and with-output-to-file: PORT is the current port WHICH for
;; THUNK and is closed on every way out. (call-with-input-file and
;; call-with-output-file are call-with-port over the opened port.)
#+rontolisp-scheme-files
(defun rontolisp::%scheme-with-file (port which thunk)
  (unwind-protect (rontolisp::%scheme-parameterize
                   (list (rontolisp::%scheme-port-parameter which) port) thunk)
    (rontolisp::%scheme-release-port port)
    (setf (rontolisp::%scheme-port-open port) nil)))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-delete-file (path)
  (let ((path (rontolisp::%scheme-file-name "delete-file" path)))
    (if (not
         (handler-case (progn
                         (delete-file path)
                         t)
           (error () nil)))
        (rontolisp::%scheme-file-error "delete-file" "cannot delete file:"
                                       path))))

;; Binary file input: PUSHBACK holds the byte (or EOF) a peek-u8 read ahead.
#+rontolisp-scheme-files
(defun rontolisp::%scheme-file-read-u8 (port advance)
  (let ((b
         (if (rontolisp::%scheme-port-pushback port)
             (rontolisp::%scheme-port-pushback port)
             (read-byte (rontolisp::%scheme-port-stream port) nil
                        rontolisp::%scheme-eof-instance))))
    (setf (rontolisp::%scheme-port-pushback port) (if advance nil b))
    b))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-file-read-bytes (k port)
  (let ((bytes nil) (n 0))
    (do ()
        ((>= n k))
      (let ((b (rontolisp::%scheme-file-read-u8 port t)))
        (if (rontolisp::%scheme-eof-p b)
            (return nil)
            (progn
              (setq bytes (cons b bytes))
              (setq n (+ n 1))))))
    (if (and (null bytes) (> k 0))
        rontolisp::%scheme-eof-instance
        (rontolisp::%scheme-bytevector (nreverse bytes)))))

#+rontolisp-scheme-files
(defun rontolisp::%scheme-file-read-bytes! (to port start end)
  (let ((end (or end (length to))) (n 0))
    (do ()
        ((>= (+ start n) end))
      (let ((b (rontolisp::%scheme-file-read-u8 port t)))
        (if (rontolisp::%scheme-eof-p b)
            (return nil)
            (progn
              (setf (aref to (+ start n)) b)
              (setq n (+ n 1))))))
    (if (and (= n 0) (< start end)) rontolisp::%scheme-eof-instance n)))

(defun rontolisp::%scheme-error-message (message irritants)
  (with-output-to-string (*standard-output*)
    (if (stringp message)
        (write-string message)
        (rontolisp::%scheme-print message t))
    (dolist (irritant irritants)
      (write-char #\Space)
      (rontolisp::%scheme-print irritant t))))

;; --- exceptions (R7RS 6.11) -------------------------------------------------------
;; The handlers raise and raise-continuable consult are a Scheme-level stack,
;; %scheme-handlers: a with-exception-handler procedure, or the catch tag of a guard.
;; A raise calls the innermost procedure with the stack outside it in effect -- where
;; the raise stands, with no Common Lisp condition made -- or throws its object to the
;; innermost guard. A condition a built-in signals is caught by the handler-case of the
;; nearest guard or with-exception-handler instead, so its handler runs at that
;; boundary, after the unwinding, on every backend alike. A raise no handler takes
;; becomes a %scheme-raise condition around its object: what an uncaught raise
;; reports, and what a Common Lisp handler-case around Scheme code sees.
;; An error object is any condition: error makes a %scheme-error, which keeps its
;; message and irritants apart; a built-in's condition answers its report and ().
(defvar rontolisp::%scheme-handlers nil)

(define-condition rontolisp::%scheme-error (error)
  ((rontolisp::%scheme-error-message-slot :initarg :message
    :reader rontolisp::%scheme-error-message-of)
   (rontolisp::%scheme-error-irritants-slot :initarg :irritants
    :reader rontolisp::%scheme-error-irritants-of))
  (:report
   (lambda (c stream)
     (write-string (rontolisp::%scheme-error-message
                    (rontolisp::%scheme-error-message-of c)
                    (rontolisp::%scheme-error-irritants-of c)) stream))))

;; What read raises on malformed input: an error object read-error? answers #t for.
(define-condition rontolisp::%scheme-read-error-condition
    (rontolisp::%scheme-error reader-error)
  ())

;; What a failed file operation raises: an error object file-error? answers #t for.
#+rontolisp-scheme-files
(define-condition rontolisp::%scheme-file-error-condition
    (rontolisp::%scheme-error file-error)
  ())

(define-condition rontolisp::%scheme-raise (error)
  ((rontolisp::%scheme-raise-payload-slot :initarg :payload
    :reader rontolisp::%scheme-raise-payload))
  (:report
   (lambda (c stream)
     (write-string (with-output-to-string (*standard-output*)
                     (rontolisp::%scheme-print
                      (rontolisp::%scheme-raise-payload c) t)) stream))))

(defun rontolisp::%scheme-signal-error (message irritants)
  (rontolisp::%scheme-raise
   (make-condition 'rontolisp::%scheme-error
                   :message message
                   :irritants irritants)))

(defun rontolisp::%scheme-raise (x) (rontolisp::%scheme-dispatch x nil))

(defun rontolisp::%scheme-raise-continuable (x)
  (rontolisp::%scheme-dispatch x t))

;; Hands X to the innermost handler, with the handlers outside it in effect. A
;; procedure's value answers a continuable raise; returning from any other raise is a
;; secondary error, raised where the handler ran (R7RS 6.11).
(defun rontolisp::%scheme-dispatch (x continuable)
  (let ((handlers rontolisp::%scheme-handlers))
    (cond ((null handlers) (error 'rontolisp::%scheme-raise :payload x))
          ((functionp (car handlers))
           (let ((value
                  (let ((rontolisp::%scheme-handlers (cdr handlers)))
                    (funcall (car handlers) x))))
             (if continuable
                 value
                 (let ((rontolisp::%scheme-handlers (cdr handlers)))
                   (rontolisp::%scheme-handler-returned x)))))
          (t (throw (car handlers) x)))))

(defun rontolisp::%scheme-handler-returned (x)
  (rontolisp::%scheme-signal-error
   (rontolisp::%scheme-error-message
    "handler returned from non-continuable exception:" (list x)) nil))

;; What a guard clause or a handler is handed for the condition C a handler-case caught.
(defun rontolisp::%scheme-condition-object (c)
  (if (typep c 'rontolisp::%scheme-raise)
      (rontolisp::%scheme-raise-payload c)
      c))

(defun rontolisp::%scheme-with-exception-handler (handler thunk)
  (handler-case (let ((rontolisp::%scheme-handlers
                       (cons handler rontolisp::%scheme-handlers)))
                  (funcall thunk))
    ;; A raise no handler took passed this one already: on its way out.
    (rontolisp::%scheme-raise (c)
      (error 'rontolisp::%scheme-raise
             :payload (rontolisp::%scheme-raise-payload c)))
    (error (c)
      (funcall handler c)
      (rontolisp::%scheme-handler-returned c))))

;; (guard (var clause...) body...): BODY a thunk, HANDLER a procedure of the raised
;; object running the clauses -- and raising the object again when none is taken --
;; in the guard's dynamic environment, after the body has been unwound. The body
;; answers its first value only.
(defun rontolisp::%scheme-guard (body handler)
  (let ((tag (list nil)) (result nil))
    (let ((x
           (catch tag
             (handler-case (progn
                             (setq result
                                   (let ((rontolisp::%scheme-handlers
                                          (cons tag
                                                rontolisp::%scheme-handlers)))
                                     (funcall body)))
                             tag)
               (error (c) (rontolisp::%scheme-condition-object c))))))
      (if (eq x tag) result (funcall handler x)))))

(defun rontolisp::%scheme-error-object-message (x)
  (cond
   ((typep x 'rontolisp::%scheme-error) (rontolisp::%scheme-error-message-of x))
   ((typep x 'condition) (princ-to-string x))
   (t (rontolisp::%scheme-not-an-error-object "error-object-message" x))))

(defun rontolisp::%scheme-error-object-irritants (x)
  (cond
   ((typep x 'rontolisp::%scheme-error)
    (rontolisp::%scheme-error-irritants-of x))
   ((typep x 'condition) nil)
   (t (rontolisp::%scheme-not-an-error-object "error-object-irritants" x))))

(defun rontolisp::%scheme-not-an-error-object (name x)
  (rontolisp::%scheme-signal-error
   (concatenate 'string name ": not an error object:") (list x)))

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

(defun rontolisp::%scheme-not-a-stream-pair (who s)
  (error "~A"
         (rontolisp::%scheme-error-message
          (concatenate 'string who ": not a stream pair:") (list s))))

(defun rontolisp::%scheme-stream-car (s)
  (if (consp s) (car s) (rontolisp::%scheme-not-a-stream-pair "stream-car" s)))

(defun rontolisp::%scheme-stream-cdr (s)
  (if (consp s)
      (rontolisp::%scheme-force (cdr s))
      (rontolisp::%scheme-not-a-stream-pair "stream-cdr" s)))

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
;; A thread starts with the caller's parameterize bindings, which is what the sequential
;; WASM run sees as well.
#+thread-support
(defun rontolisp::%scheme-parallel-execute (thunks)
  (let ((bindings
         (list
          (cons 'rontolisp::%scheme-parameterizations
                rontolisp::%scheme-parameterizations))))
    (rontolisp::%scheme-join-all
     (mapcar (lambda (thunk) (rontolisp:make-thread thunk bindings)) thunks))))

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

;; A case-lambda none of whose clauses accepts ARGUMENTS (R7RS 4.2.9): an error object
;; naming them, as Gauche reports it.
(defun rontolisp::%scheme-case-lambda-arity (arguments)
  (error "~A"
         (rontolisp::%scheme-error-message
          "wrong number of arguments to case-lambda:" (list arguments))))

;; The body of the clause (cond-expand clause...) X takes, as the lowering picks it
;; (SchemeFeatures): the first whose requirement holds, else a last else clause. A
;; (library ...) requirement holds for an importable (scheme <name>) only: eval sees no
;; user library, as environment does not.
(defun rontolisp::%scheme-eval-cond-expand (x)
  (let ((clauses (rontolisp::%scheme-eval-parts x 0 nil)))
    (dolist (clause clauses
             (error "~A"
              (rontolisp::%scheme-error-message
               "no cond-expand clause is fulfilled and there is no else clause:"
               (list x))))
      (if (not (consp clause)) (rontolisp::%scheme-ill-formed x))
      (if (eq (car clause) '|else|)
          (if (cdr (member clause clauses :test #'eq))
              (rontolisp::%scheme-ill-formed x)
              (return (cdr clause))))
      (if (rontolisp::%scheme-eval-feature-p (car clause) x)
          (return (cdr clause))))))

(defun rontolisp::%scheme-eval-feature-p (requirement x)
  (cond ((and (symbolp requirement) requirement)
         (if (member requirement (rontolisp::%scheme-features)) t nil))
        ((not (consp requirement)) (rontolisp::%scheme-ill-formed x))
        ((eq (car requirement) '|and|)
         (dolist (operand (cdr requirement) t)
           (if (not (rontolisp::%scheme-eval-feature-p operand x))
               (return nil))))
        ((eq (car requirement) '|or|)
         (dolist (operand (cdr requirement) nil)
           (if (rontolisp::%scheme-eval-feature-p operand x) (return t))))
        ((not (and (consp (cdr requirement)) (null (cdr (cdr requirement)))))
         (rontolisp::%scheme-ill-formed x))
        ((eq (car requirement) '|not|)
         (not (rontolisp::%scheme-eval-feature-p (car (cdr requirement)) x)))
        ((eq (car requirement) '|library|)
         (let ((name (car (cdr requirement))))
           (if (and (consp name) (eq (car name) '|scheme|) (consp (cdr name))
                    (null (cdr (cdr name))))
               (rontolisp::%scheme-library-p (car (cdr name)))
               nil)))
        (t (rontolisp::%scheme-ill-formed x))))

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
                   ;; Only for a program that spells cond-expand: no other can hand
                   ;; eval one (SchemeLibrary.COND_EXPAND_FEATURE).
                   #+rontolisp-scheme-cond-expand
                   ((eq head '|cond-expand|)
                    (let ((parts (rontolisp::%scheme-eval-cond-expand x)))
                      (if (null parts)
                          (return rontolisp::%scheme-unspecified)
                          (setq x
                                (rontolisp::%scheme-eval-butlast parts env)))))
                   ((member head '(|unquote| |unquote-splicing| |else| |s%=>|))
                    (rontolisp::%scheme-ill-formed x))
                   (t (error "~A"
                             (rontolisp::%scheme-error-message
                              "Not supported inside eval:" (list x))))))))))
