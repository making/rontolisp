;;;; Regular expressions: one engine for every front end that has them.
;;;;
;;;; Provenance: written here from the syntax the java.util.regex.Pattern API
;;;; documentation describes (constructs, flags, quantifier kinds, the error
;;;; conditions) and from the answers of a JDK run beside it as an oracle
;;;; (ClojureRegexTest). No JDK source was read or translated; the structure
;;;; below is this file's own: a recursive-descent parser to a tree of lists,
;;;; and a compiler from the tree to closures (STATE POSITION) -> boolean, each
;;;; holding the closure that continues the match.
;;;;
;;;; Positions count code points: a string's elements are characters.

;;; ---------------------------------------------------------------------------
;;; The parser.
;;;
;;; The parser state is a simple vector:
;;;   0 the pattern (its \Q..\E quoting rewritten)   1 the cursor
;;;   2 the flags (Pattern's bits)                    3 the groups so far
;;;   4 the group names, ((name . index) ...)         5 the refusal function
;;;   6 the pattern as given                          7 the Unicode provider
;;;   8 the category provider                         9 the grapheme provider
;;;   10 the character names, ((name . code) ...)     11 a back reference read
;;;   12 the pattern's length
;;;
;;; Flags: 1 d (UNIX_LINES), 2 i (CASE_INSENSITIVE), 4 x (COMMENTS),
;;; 8 m (MULTILINE), 16 LITERAL, 32 s (DOTALL), 64 u (UNICODE_CASE),
;;; 128 c (CANON_EQ), 256 U (UNICODE_CHARACTER_CLASS).

(defun rontolisp::%regex-syntax-message (description index pattern)
  "The text of a refusal: the description, where it was found, the pattern and
   a caret under the index when the index is inside the pattern."
  (let ((out (make-string-output-stream)))
    (write-string description out)
    (if (>= index 0)
        (progn
          (write-string " near index " out)
          (write-string (princ-to-string index) out)))
    (write-char (code-char 10) out)
    (write-string pattern out)
    (if (and (>= index 0) (< index (length pattern)))
        (progn
          (write-char (code-char 10) out)
          ;; a tab of the pattern stands in the caret line too
          (dotimes (k index)
            (write-char (if (char= (char pattern k) (code-char 9))
                            (code-char 9)
                            (code-char 32)) out))
          (write-char (code-char 94) out)))
    (get-output-stream-string out)))

(defun rontolisp::%regex-p-fail (ps description index)
  "Refuses the pattern: the caller's refusal function does not return."
  (funcall (svref ps 5) description index)
  (error "~A"
         (rontolisp::%regex-syntax-message description index (svref ps 6))))

(defun rontolisp::%regex-p-fail-here (ps description)
  "Refuses the pattern at the character read last."
  (rontolisp::%regex-p-fail ps description (- (rontolisp::%regex-p-pos ps) 1)))

(defun rontolisp::%regex-p-pos (ps)
  "The cursor."
  (svref ps 1))

(defun rontolisp::%regex-p-move (ps k)
  "Moves the cursor to K."
  (setf (svref ps 1) k))

(defun rontolisp::%regex-p-len (ps)
  "The pattern's length."
  (svref ps 12))

(defun rontolisp::%regex-p-at (ps k)
  "The code of the pattern's character K, 0 outside the pattern."
  (if (and (>= k 0) (< k (rontolisp::%regex-p-len ps)))
      (char-code (char (svref ps 0) k))
      0))

(defun rontolisp::%regex-p-flag (ps bit) (/= 0 (logand (svref ps 2) bit)))

(defun rontolisp::%regex-line-end-code-p (c unix)
  "Whether the code is a line terminator: a newline alone under UNIX_LINES."
  (or (= c 10) (and (not unix) (or (= c 13) (= c 133) (= c 8232) (= c 8233)))))

(defun rontolisp::%regex-p-skip (ps)
  "In comments mode, moves the cursor past white space and # comments."
  (if (rontolisp::%regex-p-flag ps 4)
      (let ((k (rontolisp::%regex-p-pos ps))
            (n (rontolisp::%regex-p-len ps))
            (pat (svref ps 0))
            (unix (rontolisp::%regex-p-flag ps 1)))
        (loop
          (let ((c (if (< k n) (char-code (char pat k)) 0)))
            (cond ((and (< k n) (or (= c 32) (and (>= c 9) (<= c 13))))
                   (setq k (+ k 1)))
                  ((= c 35)
                   (loop
                     (if (or (>= k n)
                             (rontolisp::%regex-line-end-code-p
                              (char-code (char pat k)) unix))
                         (return nil))
                     (setq k (+ k 1))))
                  (t (return nil)))))
        (rontolisp::%regex-p-move ps k))))

(defun rontolisp::%regex-p-peek (ps)
  "The code at the cursor, past what comments mode skips."
  (rontolisp::%regex-p-skip ps)
  (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps)))

(defun rontolisp::%regex-p-read (ps)
  "Reads the code at the cursor, past what comments mode skips. Reading the
   end moves the cursor too, so a refusal there names the pattern's length."
  (let ((c (rontolisp::%regex-p-peek ps)))
    (rontolisp::%regex-p-move ps (+ (rontolisp::%regex-p-pos ps) 1))
    c))

(defun rontolisp::%regex-p-raw (ps)
  "Reads the code at the cursor as it stands."
  (let ((c (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps))))
    (rontolisp::%regex-p-move ps (+ (rontolisp::%regex-p-pos ps) 1))
    c))

(defun rontolisp::%regex-p-end-p (ps)
  (>= (rontolisp::%regex-p-pos ps) (rontolisp::%regex-p-len ps)))

(defun rontolisp::%regex-named (name names)
  "The entry of NAME in NAMES, a list of (name . value), or nil."
  (let ((rest names) (found nil))
    (loop
      (if (or found (null rest)) (return found))
      (if (string= (car (car rest)) name) (setq found (car rest)))
      (setq rest (cdr rest)))))

(defun rontolisp::%regex-index-of (text c from to)
  "Where the character C first stands in TEXT between FROM and TO, or nil."
  (let ((k from))
    (loop
      (if (>= k to) (return nil))
      (if (char= (char text k) c) (return k))
      (setq k (+ k 1)))))

(defun rontolisp::%regex-ascii-letter-p (c)
  (or (and (>= c 97) (<= c 122)) (and (>= c 65) (<= c 90))))

(defun rontolisp::%regex-ascii-digit-p (c) (and (>= c 48) (<= c 57)))

(defun rontolisp::%regex-hex-value (c)
  "The value of a hexadecimal digit's code, nil for any other."
  (cond ((and (>= c 48) (<= c 57)) (- c 48))
        ((and (>= c 97) (<= c 102)) (- c 87))
        ((and (>= c 65) (<= c 70)) (- c 55))
        (t nil)))

(defun rontolisp::%regex-unquote (source)
  "SOURCE with every \\Q..\\E section written as escaped literals: a letter or
   a non-ASCII character as it is, a digit that starts a section as its \\x
   escape, any other character behind a backslash. An escaped backslash is no
   start of a section."
  (if (not (search "\\Q" source))
      source
      (let ((out (make-string-output-stream))
            (n (length source))
            (i 0)
            (quoting nil)
            (first nil))
        (loop
          (if (>= i n) (return nil))
          (let ((c (char source i))
                (d (if (< (+ i 1) n) (char source (+ i 1)) nil)))
            (cond (quoting
                   (cond ((and (char= c #\\) d (char= d #\E))
                          (setq quoting nil i (+ i 2)))
                         (t
                          (let ((code (char-code c)))
                            (cond ((or (>= code 128)
                                       (rontolisp::%regex-ascii-letter-p code))
                                   (write-char c out))
                                  ((rontolisp::%regex-ascii-digit-p code)
                                   (if first
                                       (progn
                                         (write-string "\\x3" out)
                                         (write-char c out))
                                       (write-char c out)))
                                  (t
                                   (write-char #\\ out)
                                   (write-char c out))))
                          (setq first nil i (+ i 1)))))
                  ((and (char= c #\\) d (char= d #\Q))
                   (setq quoting t first t i (+ i 2)))
                  ((and (char= c #\\) d)
                   (write-char c out)
                   (write-char d out)
                   (setq i (+ i 2)))
                  (t
                   (write-char c out)
                   (setq i (+ i 1))))))
        (get-output-stream-string out))))

;;; The tree. A node is a list headed by its kind:
;;;   (:char code mode)  (:str text mode)
;;;     mode 0 exact, 1 ASCII case, 2 Unicode case
;;;   (:set test)        test a function of a code
;;;   (:seq nodes)  (:alt nodes)  (:empty)
;;;   (:group index body)  (:nc body)  (:atomic body)
;;;   (:look positive body)  (:lookb positive body)
;;;   (:rep body min max kind)
;;;     max -1 unbounded; kind :greedy :lazy :possessive
;;;   (:ref index mode)  (:bol)  (:bolm unix)  (:eol unix)  (:eolm unix)
;;;   (:endz)
;;;   (:last)  (:word positive unicode)  (:linebreak)  (:graph)  (:gbound)

(defun rontolisp::%regex-parse (ps)
  "The whole pattern's tree."
  (let ((tree (rontolisp::%regex-parse-alt ps)))
    (if (not (rontolisp::%regex-p-end-p ps))
        ;; only a closing parenthesis stops the alternatives short of the end
        (rontolisp::%regex-p-fail ps "Unmatched closing ')'"
                                  (- (rontolisp::%regex-p-pos ps) 1)))
    tree))

(defun rontolisp::%regex-parse-alt (ps)
  (let ((branches (list (rontolisp::%regex-parse-seq ps))))
    (loop
      (if (and (= (rontolisp::%regex-p-peek ps) 124)
               (not (rontolisp::%regex-p-end-p ps)))
          (progn
            (rontolisp::%regex-p-read ps)
            (setq branches (cons (rontolisp::%regex-parse-seq ps) branches)))
          (return nil)))
    (if (cdr branches) (list :alt (reverse branches)) (car branches))))

(defun rontolisp::%regex-parse-seq (ps)
  (let ((items nil))
    (loop
      (let ((c (rontolisp::%regex-p-peek ps)))
        (if (or (rontolisp::%regex-p-end-p ps) (= c 124) (= c 41)) (return nil))
        (let ((atom (rontolisp::%regex-parse-atom ps c)))
          ;; a group of flags alone gives no node
          (if atom
              (setq items
               (cons (rontolisp::%regex-parse-quantifier ps atom) items))))))
    (rontolisp::%regex-make-seq (reverse items))))

(defun rontolisp::%regex-make-seq (items)
  "The sequence of ITEMS, its runs of two or more literal characters of one
   mode made strings."
  (let ((out nil) (run nil) (mode 0))
    (dolist (item (append items (list (list :run-end))))
      (if (and (eq (car item) :char)
               (or (null run) (= mode (car (cdr (cdr item))))))
          (setq run (cons (car (cdr item)) run) mode (car (cdr (cdr item))))
          (progn
            (if run
                (if (cdr run)
                    (let ((text (make-string-output-stream)))
                      (dolist (code (reverse run))
                        (write-char (code-char code) text))
                      (setq out
                            (cons
                             (list :str (get-output-stream-string text) mode)
                             out)))
                    (setq out (cons (list :char (car run) mode) out))))
            (setq run nil)
            (cond ((eq (car item) :char)
                   (setq run (list (car (cdr item))) mode
                         (car (cdr (cdr item)))))
                  ((eq (car item) :run-end) nil)
                  (t (setq out (cons item out)))))))
    (setq out (reverse out))
    (cond ((null out) (list :empty))
          ((null (cdr out)) (car out))
          (t (list :seq out)))))

(defun rontolisp::%regex-char-mode (ps)
  "How a literal character compares under the flags."
  (cond ((not (rontolisp::%regex-p-flag ps 2)) 0)
        ((rontolisp::%regex-p-flag ps 64) 2)
        (t 1)))

(defun rontolisp::%regex-char-node (ps code)
  (let ((mode (rontolisp::%regex-char-mode ps)))
    (cond ((= mode 0) (list :char code 0))
          ((= mode 1)
           (list :char code (if (rontolisp::%regex-ascii-letter-p code) 1 0)))
          ;; under Unicode case a character with no other case compares exactly
          ;; when it stands alone; a run compares every character folded
          (t (list :char code 2)))))

(defun rontolisp::%regex-parse-atom (ps c)
  (cond ((= c 40) (rontolisp::%regex-parse-group ps))
        ((= c 91)
         (rontolisp::%regex-p-read ps)
         (list
          :set (rontolisp::%regex-set-test (rontolisp::%regex-parse-class ps))))
        ((= c 92)
         (rontolisp::%regex-p-read ps)
         (let ((escape (rontolisp::%regex-parse-escape ps nil)))
           (cond ((eq (car escape) :code)
                  (rontolisp::%regex-char-node ps (car (cdr escape))))
                 ((eq (car escape) :item)
                  (list :set (rontolisp::%regex-set-test (car (cdr escape)))))
                 (t escape))))
        ((= c 46)
         (rontolisp::%regex-p-read ps)
         (list :set (rontolisp::%regex-dot-test (rontolisp::%regex-p-flag ps 32)
                     (rontolisp::%regex-p-flag ps 1))))
        ((= c 94)
         (rontolisp::%regex-p-read ps)
         (if (rontolisp::%regex-p-flag ps 8)
             (list :bolm (rontolisp::%regex-p-flag ps 1))
             (list :bol)))
        ((= c 36)
         (rontolisp::%regex-p-read ps)
         (if (rontolisp::%regex-p-flag ps 8)
             (list :eolm (rontolisp::%regex-p-flag ps 1))
             (list :eol (rontolisp::%regex-p-flag ps 1))))
        ((or (= c 42) (= c 43) (= c 63))
         (rontolisp::%regex-p-read ps)
         (rontolisp::%regex-p-skip ps)
         (rontolisp::%regex-p-fail-here ps
                                        (concatenate 'string
                                                     "Dangling meta character '"
                                                     (string (code-char c))
                                                     "'")))
        ;; a count with nothing before it repeats nothing
        ((= c 123) (list :empty))
        (t
         (rontolisp::%regex-p-read ps)
         (rontolisp::%regex-char-node ps c))))

(defun rontolisp::%regex-parse-quantifier (ps atom)
  "ATOM under the quantifier at the cursor, or ATOM itself."
  (let ((c (rontolisp::%regex-p-peek ps)) (min nil) (max nil))
    (cond ((rontolisp::%regex-p-end-p ps) nil)
          ((= c 63)
           (rontolisp::%regex-p-read ps)
           (setq min 0 max 1))
          ((= c 42)
           (rontolisp::%regex-p-read ps)
           (setq min 0 max -1))
          ((= c 43)
           (rontolisp::%regex-p-read ps)
           (setq min 1 max -1))
          ((= c 123)
           (rontolisp::%regex-p-read ps)
           (let ((count (rontolisp::%regex-parse-count ps)))
             (setq min (car count) max (cdr count)))))
    (if (null min)
        atom
        (let ((k (rontolisp::%regex-p-peek ps)) (kind :greedy))
          (cond ((rontolisp::%regex-p-end-p ps) nil)
           ((= k 63)
            (rontolisp::%regex-p-read ps)
            (setq kind :lazy))
           ((= k 43)
            (rontolisp::%regex-p-read ps)
            (setq kind :possessive)))
          (list :rep atom min max kind)))))

(defun rontolisp::%regex-parse-number (ps c)
  "The decimal number whose first digit's code is C, and the code read after
   it: (number . code). Past the int range it is no repetition count."
  (let ((value 0))
    (loop
      (if (not (rontolisp::%regex-ascii-digit-p c)) (return nil))
      (setq value (+ (* value 10) (- c 48)))
      (if (> value 2147483647)
          (rontolisp::%regex-p-fail-here ps "Illegal repetition range"))
      (setq c (rontolisp::%regex-p-read ps)))
    (cons value c)))

(defun rontolisp::%regex-parse-count (ps)
  "The bounds of a counted repetition whose brace was read: (min . max)."
  (let ((c (rontolisp::%regex-p-raw ps)))
    (if (not (rontolisp::%regex-ascii-digit-p c))
        (rontolisp::%regex-p-fail-here ps "Illegal repetition"))
    (let* ((low (rontolisp::%regex-parse-number ps c))
           (min (car low))
           (max (car low)))
      (setq c (cdr low))
      (if (= c 44)
          (progn
            (setq c (rontolisp::%regex-p-read ps) max -1)
            (if (/= c 125)
                (progn
                  (if (not (rontolisp::%regex-ascii-digit-p c))
                      (rontolisp::%regex-p-fail-here ps
                       "Unclosed counted closure"))
                  (let ((high (rontolisp::%regex-parse-number ps c)))
                    (setq max (car high) c (cdr high)))))))
      (if (/= c 125)
          (rontolisp::%regex-p-fail-here ps "Unclosed counted closure"))
      (if (and (>= max 0) (< max min))
          (rontolisp::%regex-p-fail-here ps "Illegal repetition range"))
      (cons min max))))

(defun rontolisp::%regex-p-close (ps saved)
  "Reads the parenthesis closing a group and gives the flags back."
  (if (/= (rontolisp::%regex-p-read ps) 41)
      (rontolisp::%regex-p-fail-here ps "Unclosed group"))
  (setf (svref ps 2) saved))

(defun rontolisp::%regex-parse-group (ps)
  (rontolisp::%regex-p-read ps)
  (let ((saved (svref ps 2)) (c (rontolisp::%regex-p-peek ps)))
    (if (and (= c 63) (not (rontolisp::%regex-p-end-p ps)))
        (progn
          (rontolisp::%regex-p-read ps)
          (rontolisp::%regex-parse-special-group ps saved))
        (let ((index (+ (svref ps 3) 1)))
          (setf (svref ps 3) index)
          (let ((body (rontolisp::%regex-parse-alt ps)))
            (rontolisp::%regex-p-close ps saved)
            (list :group index body))))))

(defun rontolisp::%regex-parse-group-name (ps)
  "A group name, read through the > that ends it."
  (let ((c (rontolisp::%regex-p-read ps)) (out (make-string-output-stream)))
    (if (not (rontolisp::%regex-ascii-letter-p c))
        (rontolisp::%regex-p-fail-here ps
         "capturing group name does not start with a Latin letter"))
    (loop
      (if (not
           (or (rontolisp::%regex-ascii-letter-p c)
               (rontolisp::%regex-ascii-digit-p c)))
          (return nil))
      (write-char (code-char c) out)
      (setq c (rontolisp::%regex-p-read ps)))
    (if (/= c 62)
        (rontolisp::%regex-p-fail-here ps
         "named capturing group is missing trailing '>'"))
    (get-output-stream-string out)))

(defun rontolisp::%regex-parse-special-group (ps saved)
  "The group whose opening parenthesis and question mark were read."
  (let ((c (rontolisp::%regex-p-read ps)))
    (cond ((= c 58)
           (let ((body (rontolisp::%regex-parse-alt ps)))
             (rontolisp::%regex-p-close ps saved)
             (list :nc body)))
          ((or (= c 61) (= c 33))
           (let ((body (rontolisp::%regex-parse-alt ps)))
             (rontolisp::%regex-p-close ps saved)
             (list :look (= c 61) body)))
          ((= c 62)
           (let ((body (rontolisp::%regex-parse-alt ps)))
             (rontolisp::%regex-p-close ps saved)
             (list :atomic body)))
          ((= c 60)
           (let ((d (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps))))
             (if (or (= d 61) (= d 33))
                 (progn
                   (rontolisp::%regex-p-raw ps)
                   (let ((body (rontolisp::%regex-parse-alt ps))
                         (info (vector 0 0 t)))
                     (rontolisp::%regex-p-skip ps)
                     (rontolisp::%regex-study body info)
                     (if (not (svref info 2))
                         (rontolisp::%regex-p-fail ps
                          "Look-behind group does not have an obvious maximum length"
                          (- (rontolisp::%regex-p-pos ps) 1)))
                     (rontolisp::%regex-p-close ps saved)
                     (list :lookb (= d 61) body (svref info 0) (svref info 1))))
                 (let ((name (rontolisp::%regex-parse-group-name ps)))
                   (if (rontolisp::%regex-named name (svref ps 4))
                       (rontolisp::%regex-p-fail-here ps
                                                      (concatenate 'string
                                                       "Named capturing group <"
                                                       name
                                                       "> is already defined")))
                   (let ((index (+ (svref ps 3) 1)))
                     (setf (svref ps 3) index)
                     (setf (svref ps 4) (cons (cons name index) (svref ps 4)))
                     (let ((body (rontolisp::%regex-parse-alt ps)))
                       (rontolisp::%regex-p-close ps saved)
                       (list :group index body)))))))
          ((or (= c 36) (= c 64))
           (rontolisp::%regex-p-fail-here ps "Unknown group type"))
          (t
           (rontolisp::%regex-p-move ps (- (rontolisp::%regex-p-pos ps) 1))
           (rontolisp::%regex-parse-flags ps saved)))))

(defun rontolisp::%regex-flag-bit (c)
  "The flag a letter names, nil for any other code."
  (cond ((= c 105) 2)
        ((= c 109) 8)
        ((= c 115) 32)
        ((= c 100) 1)
        ((= c 117) 64)
        ((= c 99) 128)
        ((= c 120) 4)
        ((= c 85) 320)
        (t nil)))

(defun rontolisp::%regex-parse-flags (ps saved)
  "The flags after a group's question mark: set for the rest of the enclosing
   group (no node), or for the group they open."
  (let ((flags (svref ps 2)) (on t))
    (loop
      (let* ((c (rontolisp::%regex-p-read ps))
             (bit (rontolisp::%regex-flag-bit c)))
        (cond
         (bit
          (setq flags (if on (logior flags bit) (logand flags (- 511 bit)))))
         ((and (= c 45) on) (setq on nil))
         ((= c 58)
          (setf (svref ps 2) flags)
          (let ((body (rontolisp::%regex-parse-alt ps)))
            (rontolisp::%regex-p-close ps saved)
            (return (list :nc body))))
         ((= c 41)
          (setf (svref ps 2) flags)
          (return nil))
         (t (rontolisp::%regex-p-fail-here ps "Unknown inline modifier")))))))

;;; Escapes. An escape answers (:code c) for a literal character, (:item set)
;;; for a class, or a node; inside a class only the first two.

(defun rontolisp::%regex-parse-escape (ps in-class)
  (let ((c (rontolisp::%regex-p-raw ps)))
    (cond
     ((> (rontolisp::%regex-p-pos ps) (rontolisp::%regex-p-len ps))
      (if in-class
          (rontolisp::%regex-p-fail-here ps "Unclosed character class")
          (rontolisp::%regex-p-fail-here ps "Unescaped trailing backslash")))
     ((= c 48) (list :code (rontolisp::%regex-parse-octal ps)))
     ((rontolisp::%regex-ascii-digit-p c)
      (if in-class
          (rontolisp::%regex-p-fail-here ps
                                         "Illegal/unsupported escape sequence")
          (rontolisp::%regex-parse-back-reference ps (- c 48))))
     ((not (rontolisp::%regex-ascii-letter-p c)) (list :code c))
     ((= c 120) (list :code (rontolisp::%regex-parse-hex ps)))
     ((= c 117) (list :code (rontolisp::%regex-parse-unicode-escape ps)))
     ((= c 99)
      (if (rontolisp::%regex-p-end-p ps)
          (rontolisp::%regex-p-fail-here ps "Illegal control escape sequence"))
      (list :code (logxor (rontolisp::%regex-p-raw ps) 64)))
     ((= c 116) (list :code 9))
     ((= c 110) (list :code 10))
     ((= c 114) (list :code 13))
     ((= c 102) (list :code 12))
     ((= c 97) (list :code 7))
     ((= c 101) (list :code 27))
     ((or (= c 100) (= c 68) (= c 119) (= c 87) (= c 115) (= c 83) (= c 104)
          (= c 72) (= c 118) (= c 86))
      (list :item (rontolisp::%regex-class-escape ps c)))
     ((or (= c 112) (= c 80))
      (list :item (rontolisp::%regex-parse-property ps (= c 80))))
     ((= c 78) (list :code (rontolisp::%regex-parse-named-char ps)))
     (in-class
      (rontolisp::%regex-p-fail-here ps "Illegal/unsupported escape sequence"))
     ((= c 98) (rontolisp::%regex-parse-word-boundary ps))
     ((= c 66) (list :word nil (rontolisp::%regex-p-flag ps 256) (svref ps 8)))
     ((= c 65) (list :bol))
     ((= c 122) (list :endz))
     ((= c 90) (list :eol (rontolisp::%regex-p-flag ps 1)))
     ((= c 71) (list :last))
     ((= c 107) (rontolisp::%regex-parse-named-reference ps))
     ((= c 82) (list :linebreak))
     ((= c 88)
      (if (null (svref ps 9)) (error "unsupported regex: \\X is not supported"))
      (list :graph (svref ps 9)))
     (t (rontolisp::%regex-p-fail-here ps
         "Illegal/unsupported escape sequence")))))

(defun rontolisp::%regex-parse-octal (ps)
  "The code of \\0n, \\0nn or \\0mnn (m at most 3), the zero read."
  (let ((c (rontolisp::%regex-p-read ps)))
    (if (not (and (>= c 48) (<= c 55)))
        (rontolisp::%regex-p-fail-here ps "Illegal octal escape sequence"))
    (let ((value (- c 48))
          (d (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps))))
      (if (and (>= d 48) (<= d 55))
          (progn
            (rontolisp::%regex-p-raw ps)
            (setq value (+ (* value 8) (- d 48)))
            (let ((e (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps))))
              (if (and (>= e 48) (<= e 55) (<= c 51))
                  (progn
                    (rontolisp::%regex-p-raw ps)
                    (setq value (+ (* value 8) (- e 48))))))))
      value)))

(defun rontolisp::%regex-parse-hex (ps)
  "The code of \\xhh or \\x{h...h}, the x read."
  (let ((c (rontolisp::%regex-p-read ps)))
    (if (= c 123)
        (let ((value 0) (digits 0) (d nil))
          (loop
            (setq c (rontolisp::%regex-p-read ps) d
                  (rontolisp::%regex-hex-value c))
            (if (null d) (return nil))
            (setq value (+ (* value 16) d) digits (+ digits 1))
            (if (> value 1114111)
                (rontolisp::%regex-p-fail-here ps
                 "Hexadecimal codepoint is too big")))
          (if (= digits 0)
              (rontolisp::%regex-p-fail ps "Illegal hexadecimal escape sequence"
                                        (- (rontolisp::%regex-p-pos ps) 2)))
          (if (/= c 125)
              (rontolisp::%regex-p-fail-here ps
               "Unclosed hexadecimal escape sequence"))
          value)
        (let ((high (rontolisp::%regex-hex-value c)))
          (if (null high)
              (rontolisp::%regex-p-fail-here ps
               "Illegal hexadecimal escape sequence"))
          (let ((low
                 (rontolisp::%regex-hex-value (rontolisp::%regex-p-read ps))))
            (if (null low)
                (rontolisp::%regex-p-fail-here ps
                 "Illegal hexadecimal escape sequence"))
            (+ (* high 16) low))))))

(defun rontolisp::%regex-parse-unicode-unit (ps)
  "The four hexadecimal digits of a \\u escape, the u read."
  (let ((value 0))
    (dotimes (k 4)
      (let ((d (rontolisp::%regex-hex-value (rontolisp::%regex-p-read ps))))
        (if (null d)
            (rontolisp::%regex-p-fail-here ps
                                           "Illegal Unicode escape sequence"))
        (setq value (+ (* value 16) d))))
    value))

(defun rontolisp::%regex-parse-unicode-escape (ps)
  "The code of \\uhhhh, a surrogate pair of two escapes joined."
  (let ((value (rontolisp::%regex-parse-unicode-unit ps))
        (k (rontolisp::%regex-p-pos ps)))
    (if (and (>= value 55296) (<= value 56319)
             (= (rontolisp::%regex-p-at ps k) 92)
             (= (rontolisp::%regex-p-at ps (+ k 1)) 117))
        (progn
          (rontolisp::%regex-p-move ps (+ k 2))
          (let ((low (rontolisp::%regex-parse-unicode-unit ps)))
            (if (and (>= low 56320) (<= low 57343))
                (+ 65536 (* (- value 55296) 1024) (- low 56320))
                (progn
                  (rontolisp::%regex-p-move ps k)
                  value))))
        value)))

(defun rontolisp::%regex-parse-back-reference (ps n)
  "The reference whose first digit N was read: further digits belong to it
   while that many groups have been opened."
  (loop
    (let ((d (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps))))
      (if (and (rontolisp::%regex-ascii-digit-p d)
               (<= (+ (* n 10) (- d 48)) (svref ps 3)))
          (progn
            (rontolisp::%regex-p-raw ps)
            (setq n (+ (* n 10) (- d 48))))
          (return nil))))
  (setf (svref ps 11) t)
  (list :ref n (rontolisp::%regex-char-mode ps)))

(defun rontolisp::%regex-parse-named-reference (ps)
  (if (/= (rontolisp::%regex-p-read ps) 60)
      (rontolisp::%regex-p-fail-here ps
       "\\k is not followed by '<' for named capturing group"))
  (let* ((name (rontolisp::%regex-parse-group-name ps))
         (found (rontolisp::%regex-named name (svref ps 4))))
    (if (null found)
        (rontolisp::%regex-p-fail-here ps
                                       (concatenate 'string
                                                    "named capturing group <"
                                                    name "> does not exist")))
    (setf (svref ps 11) t)
    (list :ref (cdr found) (rontolisp::%regex-char-mode ps))))

(defun rontolisp::%regex-parse-word-boundary (ps)
  "\\b, or \\b{g} where a brace and a g follow."
  (if (and (= (rontolisp::%regex-p-peek ps) 123)
       (= (rontolisp::%regex-p-at ps (+ (rontolisp::%regex-p-pos ps) 1)) 103))
      (progn
        (rontolisp::%regex-p-move ps (+ (rontolisp::%regex-p-pos ps) 2))
        (if (/= (rontolisp::%regex-p-read ps) 125)
            (rontolisp::%regex-p-fail-here ps
             "Illegal/unsupported escape sequence"))
        (if (null (svref ps 9))
            (error "unsupported regex: \\b{g} is not supported"))
        (list :gbound (svref ps 9)))
      (list :word t (rontolisp::%regex-p-flag ps 256) (svref ps 8))))

(defun rontolisp::%regex-parse-named-char (ps)
  "The code of \\N{name}, the N read: the name is the pattern's text between
   the braces (the closing one found past what comments mode skips), looked up
   in the table the pattern was given."
  (if (/= (rontolisp::%regex-p-read ps) 123)
      (rontolisp::%regex-p-fail-here ps
                                     "Illegal character name escape sequence"))
  (let ((start (rontolisp::%regex-p-pos ps)))
    (loop
      (if (= (rontolisp::%regex-p-read ps) 125) (return nil))
      (if (rontolisp::%regex-p-end-p ps)
          (rontolisp::%regex-p-fail-here ps
           "Unclosed character name escape sequence")))
    (let ((name (subseq (svref ps 0) start (- (rontolisp::%regex-p-pos ps) 1))))
      (if (eq (svref ps 10) :none)
          (error "unsupported regex: \\N{...} is not supported"))
      (let ((found (rontolisp::%regex-named name (svref ps 10))))
        (if found
            (cdr found)
            (rontolisp::%regex-p-fail-here ps
             (concatenate 'string "Unknown character name [" name "]")))))))

;;; ---------------------------------------------------------------------------
;;; Character classes.
;;;
;;; A set is a list: (:c code), (:r low high), (:or sets), (:and a b),
;;; (:not set), (:fn function), (:bits cell), (:none). %regex-set-test makes
;;; its test, a function of a code.

(defun rontolisp::%regex-fold-equal-p (a b)
  "Whether the codes A and B are one character ignoring case: equal, or equal
   upcased, or equal upcased then downcased."
  (or (= a b)
      (let ((ua (char-upcase (code-char a))) (ub (char-upcase (code-char b))))
        (or (char= ua ub) (char= (char-downcase ua) (char-downcase ub))))))

(defun rontolisp::%regex-ascii-other-case (c)
  "The other case of an ASCII letter's code, the code itself for any other."
  (cond ((and (>= c 97) (<= c 122)) (- c 32))
        ((and (>= c 65) (<= c 90)) (+ c 32))
        (t c)))

(defun rontolisp::%regex-class-char (ps code)
  "The set of one literal character under the flags."
  (let ((mode (rontolisp::%regex-char-mode ps)))
    (cond ((= mode 0) (list :c code))
          ((= mode 1)
           (if (rontolisp::%regex-ascii-letter-p code)
               (list :or (list (list :c code)
                          (list :c (rontolisp::%regex-ascii-other-case code))))
               (list :c code)))
          (t (list :fn (lambda (x) (rontolisp::%regex-fold-equal-p x code)))))))

(defun rontolisp::%regex-class-range (ps low high)
  "The set of a range under the flags."
  (let ((mode (rontolisp::%regex-char-mode ps)))
    (cond ((= mode 0) (list :r low high))
          ((= mode 1)
           (list :fn (lambda (x)
                       (or (and (>= x low) (<= x high))
                           (and (< x 128)
                                (let ((y
                                       (rontolisp::%regex-ascii-other-case x)))
                                  (and (>= y low) (<= y high))))))))
          (t (list :fn (lambda (x)
                         (or (and (>= x low) (<= x high))
                             (let ((u (char-code (char-upcase (code-char x)))))
                               (or (and (>= u low) (<= u high))
                                   (let ((l
                                          (char-code
                                           (char-downcase (code-char u)))))
                                     (and (>= l low) (<= l high))))))))))))

(defun rontolisp::%regex-class-escape (ps c)
  "The set of a predefined class, C its letter's code."
  (if (and (rontolisp::%regex-p-flag ps 256)
           (or (= c 100) (= c 68) (= c 119) (= c 87) (= c 115) (= c 83)))
      (let ((uni (svref ps 7)))
        (if (null uni) (error "unsupported regex: (?U) is not supported"))
        (let ((members
               (funcall uni (code-char (if (< c 97) (+ c 32) c)) (svref ps 2))))
          (if (< c 97) (list :not members) members)))
      (let ((members
             (cond ((or (= c 100) (= c 68)) (list :r 48 57))
                   ((or (= c 119) (= c 87))
                    (list :or (list (list :r 97 122) (list :r 65 90)
                                    (list :r 48 57) (list :c 95))))
                   ((or (= c 115) (= c 83))
                    (list :or (list (list :c 32) (list :r 9 13))))
                   ((or (= c 104) (= c 72))
                    (list :or (list (list :c 32) (list :c 9) (list :c 160)
                                    (list :c 5760) (list :c 6158)
                                    (list :r 8192 8202) (list :c 8239)
                                    (list :c 8287) (list :c 12288))))
                   (t (list :or (list (list :r 10 13) (list :c 133)
                                      (list :r 8232 8233)))))))
        (if (< c 97) (list :not members) members))))

(defun rontolisp::%regex-dot-test (dotall unix)
  (cond (dotall (lambda (x)
                  (declare (ignore x))
                  t))
        (unix (lambda (x) (/= x 10)))
        (t (lambda (x)
             (not (or (= x 10) (= x 13) (= x 133) (= x 8232) (= x 8233)))))))

(defun rontolisp::%regex-set-union (a b)
  "The union of two sets, either of which may be nil."
  (cond ((null a) b) ((null b) a) (t (list :or (list a b)))))

(defun rontolisp::%regex-parse-class (ps)
  "The set of a bracket class whose opening bracket was read."
  (let ((negated
         (= (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps)) 94)))
    (if negated (rontolisp::%regex-p-raw ps))
    (let ((members (rontolisp::%regex-parse-class-body ps t)))
      (if (rontolisp::%regex-p-flag ps 128)
          (error
           "unsupported regex: a character class under (?c) is not supported"))
      (if negated (list :not members) members))))

(defun rontolisp::%regex-class-intersection-p (ps)
  "At an ampersand in a class: reads a && (in comments mode the second
   ampersand may stand past what the mode skips) and answers T. Else the
   ampersand is no operator: :lone with the cursor still at it, or nil where
   comments mode skipped something after it, the cursor then at what
   follows."
  (let* ((at (rontolisp::%regex-p-pos ps))
         (next (rontolisp::%regex-p-at ps (+ at 1))))
    (cond ((= next 38)
           (rontolisp::%regex-p-move ps (+ at 2))
           t)
          (t
           (rontolisp::%regex-p-move ps (+ at 1))
           (rontolisp::%regex-p-skip ps)
           (cond ((= (rontolisp::%regex-p-pos ps) (+ at 1))
                  (rontolisp::%regex-p-move ps at)
                  :lone)
                 ((= (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps))
                     38)
                  (rontolisp::%regex-p-move ps
                                            (+ (rontolisp::%regex-p-pos ps) 1))
                  t)
                 (t nil))))))

(defun rontolisp::%regex-parse-class-body (ps top)
  "The union of a class's members, each && intersecting what stands before it
   with what follows up to the closing bracket. TOP reads the closing bracket;
   otherwise the body is what follows a && and stops before the bracket.

   The oracle's answers for a && with nothing after it, or with a lone & later,
   are those of this bookkeeping (measured, ClojureRegexTest): the characters
   below 256 written one by one are a table of their own, BITS, that joins the
   other members (PREV) at a && and at the end, and stays the same table when
   more are written after the &&; a && with nothing after it intersects with
   the member read last (CURR), which a character of the table is not."
  (let ((bits (list :bits (cons nil nil)))
        (has-bits nil)
        (prev nil)
        (curr nil)
        (first top))
    (loop
      (let ((c (rontolisp::%regex-p-peek ps)))
        (cond ((rontolisp::%regex-p-end-p ps)
               (if top
                   (rontolisp::%regex-p-fail ps "Unclosed character class"
                    (- (rontolisp::%regex-p-pos ps) 1)))
               (return nil))
              ((and (= c 93) (not first))
               (if top (rontolisp::%regex-p-read ps))
               (return nil))
              ((= c 91)
               (rontolisp::%regex-p-read ps)
               (setq curr (rontolisp::%regex-parse-class ps))
               (setq prev (rontolisp::%regex-set-union prev curr)))
              ;; a lone ampersand is read as a member below; one that comments
              ;; mode found no second ampersand after is dropped, and the
              ;; character there is the member, a closing bracket too (measured)
              ((and (= c 38) (eq (rontolisp::%regex-class-intersection-p ps) t))
               (let ((right nil))
                 (loop
                   (let ((d (rontolisp::%regex-p-peek ps)))
                     (cond ((or (rontolisp::%regex-p-end-p ps) (= d 93)
                                (= d 38))
                            (return nil))
                           ((= d 91)
                            (rontolisp::%regex-p-read ps)
                            (setq right
                                  (rontolisp::%regex-set-union right
                                   (rontolisp::%regex-parse-class ps))))
                           (t (setq right
                                    (rontolisp::%regex-set-union right
                                     (rontolisp::%regex-parse-class-body ps
                                      nil)))))))
                 (if (rontolisp::%regex-p-end-p ps)
                     (rontolisp::%regex-p-fail ps "Unclosed character class"
                      (- (rontolisp::%regex-p-pos ps) 1)))
                 (if has-bits
                     (progn
                       (if (null prev)
                           (setq prev bits curr bits)
                           (setq prev (rontolisp::%regex-set-union prev bits)))
                       (setq has-bits nil)))
                 (if right (setq curr right))
                 (cond ((null prev)
                        (if (null right)
                            (rontolisp::%regex-p-fail-here ps
                                                           "Bad class syntax"))
                        (setq prev right))
                       (t
                        (if (null curr)
                            (rontolisp::%regex-p-fail-here ps
                             "Bad intersection syntax"))
                        (setq prev (list :and prev curr))))))
              ;; a dropped ampersand may have left the cursor at the end
              ((rontolisp::%regex-p-end-p ps) nil)
              (t
               (let ((member (rontolisp::%regex-parse-class-atom ps)))
                 (if (and (eq (car member) :single) (< (car (cdr member)) 256))
                     (let ((cell (car (cdr bits))))
                       (setf (car cell)
                             (cons (car (cdr (cdr member))) (car cell)))
                       (setq has-bits t curr nil))
                     (progn
                       (setq curr
                             (if (eq (car member) :single)
                                 (car (cdr (cdr member)))
                                 member))
                       (setq prev (rontolisp::%regex-set-union prev curr)))))))
        (setq first nil)))
    (if has-bits (setq prev (rontolisp::%regex-set-union prev bits)))
    (or prev (list :none))))

(defun rontolisp::%regex-range-follows-p (ps)
  "Whether a hyphen and the end of a range stand at the cursor: a hyphen (past
   what comments mode skips) that no bracket follows at once."
  (and (= (rontolisp::%regex-p-peek ps) 45)
       (let ((e (rontolisp::%regex-p-at ps (+ (rontolisp::%regex-p-pos ps) 1))))
         (or
          (>= (+ (rontolisp::%regex-p-pos ps) 1) (rontolisp::%regex-p-len ps))
          (not (or (= e 93) (= e 91)))))))

(defun rontolisp::%regex-parse-class-atom (ps)
  "One member of a class: a range's or an escape's set, or for one character
   (:single code set). At either end of a range \\v is the vertical tab; any
   other class there is no range."
  (let ((c (rontolisp::%regex-p-read ps)) (low nil))
    (if (= c 92)
        (let* ((letter (rontolisp::%regex-p-at ps (rontolisp::%regex-p-pos ps)))
               (escape (rontolisp::%regex-parse-escape ps t)))
          (cond ((eq (car escape) :code) (setq low (car (cdr escape))))
                ((and (= letter 118) (rontolisp::%regex-range-follows-p ps))
                 (setq low 11))
                (t (return-from rontolisp::%regex-parse-class-atom
                                (car (cdr escape))))))
        (setq low c))
    (if (rontolisp::%regex-range-follows-p ps)
        (progn
          (rontolisp::%regex-p-read ps)
          (let ((high (rontolisp::%regex-p-read ps)))
            (if (= high 92)
                (let ((letter
                       (rontolisp::%regex-p-at ps
                                               (rontolisp::%regex-p-pos ps))))
                  (cond
                   ((= letter 118)
                    (rontolisp::%regex-p-raw ps)
                    (setq high 11))
                   ((or (= letter 112) (= letter 80))
                    (rontolisp::%regex-p-raw ps)
                    (rontolisp::%regex-p-fail-here ps
                     "Illegal/unsupported escape sequence"))
                   (t (let ((escape (rontolisp::%regex-parse-escape ps t)))
                        (if (eq (car escape) :item)
                            (rontolisp::%regex-p-fail-here ps
                             "Illegal character range"))
                        (setq high (car (cdr escape))))))))
            (if (< high low)
                (rontolisp::%regex-p-fail-here ps "Illegal character range"))
            (rontolisp::%regex-class-range ps low high)))
        (list :single low (rontolisp::%regex-class-char ps low)))))

(defun rontolisp::%regex-set-test (members)
  "The test of a set: a function of a code."
  (let ((kind (car members)))
    (cond ((eq kind :c) (let ((c (car (cdr members)))) (lambda (x) (= x c))))
          ((eq kind :r)
           (let ((low (car (cdr members))) (high (car (cdr (cdr members)))))
             (lambda (x) (and (>= x low) (<= x high)))))
          ((eq kind :fn) (car (cdr members)))
          ((eq kind :bits)
           (rontolisp::%regex-set-test (list :or (car (car (cdr members))))))
          ((eq kind :not)
           (let ((f (rontolisp::%regex-set-test (car (cdr members)))))
             (lambda (x) (not (funcall f x)))))
          ((eq kind :and)
           (let ((f (rontolisp::%regex-set-test (car (cdr members))))
                 (g (rontolisp::%regex-set-test (car (cdr (cdr members))))))
             (lambda (x) (and (funcall f x) (funcall g x)))))
          ((eq kind :or)
           (let ((tests
                  (mapcar #'rontolisp::%regex-set-test (car (cdr members)))))
             (cond ((null tests)
                    (lambda (x)
                      (declare (ignore x))
                      nil))
                   ((null (cdr tests)) (car tests))
                   ((null (cdr (cdr tests)))
                    (let ((f (car tests)) (g (car (cdr tests))))
                      (lambda (x) (or (funcall f x) (funcall g x)))))
                   (t (lambda (x)
                        (let ((rest tests) (found nil))
                          (loop
                            (if (or found (null rest)) (return found))
                            (setq found (funcall (car rest) x) rest
                                  (cdr rest)))))))))
          (t (lambda (x)
               (declare (ignore x))
               nil)))))

(defun rontolisp::%regex-parse-property (ps negated)
  "The set of \\p{name} or \\pL, the p read; NEGATED for \\P. A braced name is
   the pattern's text from the brace to the closing one, which comments mode
   finds past its comments."
  (let ((c (rontolisp::%regex-p-read ps)) (name nil))
    (if (= c 123)
        (progn
          (rontolisp::%regex-p-skip ps)
          (let ((start (rontolisp::%regex-p-pos ps)))
            (loop
              (setq c (rontolisp::%regex-p-read ps))
              (if (or (= c 125)
                      (> (rontolisp::%regex-p-pos ps)
                         (rontolisp::%regex-p-len ps)))
                  (return nil)))
            (if (/= c 125)
                (rontolisp::%regex-p-fail-here ps "Unclosed character family"))
            (setq name
             (subseq (svref ps 0) start (- (rontolisp::%regex-p-pos ps) 1)))))
        (setq name (string (code-char c))))
    (let ((uni (svref ps 7)))
      (if (null uni) (error "unsupported regex: \\p is not supported"))
      (let ((members (funcall uni name (svref ps 2))))
        (if (stringp members) (rontolisp::%regex-p-fail-here ps members))
        (if (rontolisp::%regex-p-flag ps 128)
            (error
             "unsupported regex: a character class under (?c) is not supported"))
        (if negated (list :not members) members)))))
;;; ---------------------------------------------------------------------------
;;; The compiler: a tree to closures.
;;;
;;; A matcher is a function (STATE I) -> boolean: whether the pattern from this
;;; node on matches from position I. Each is made holding NEXT, the matcher of
;;; what follows, so a sequence costs no allocation while it runs and a failure
;;; is a return.
;;;
;;; The state of one search is a simple vector:
;;;   0 the input        1 its length          2 whether the search read the end
;;;   3 the last end: where the last match ended, moved by each sub-match of
;;;     the search under way (the oracle's matcher shows it: a search that
;;;     fails leaves it where a sub-match put it, and the next one starts there)
;;;   4 where a sub-match ended (read right after it returns)
;;;   5 whether the match must end at the length
;;;   6 where \G holds: the last end as the search began
;;;   7 the length of the whole input where the state's is a prefix of it,
;;;     else nil
;;;   8 + 2g, 9 + 2g     the start and the end of group g, -1 while it has none
;;;   the rest           the counters, positions and memos the nodes own
;;;
;;; The compile context is a simple vector: 0 the next free slot, 1 whether a
;;; loop may remember where it failed (the pattern has no back reference),
;;; 2 the depth of general loops around the node, 3 whether a look-behind is
;;; around it, 4 the number of groups, 5 the refusal function, 6 the source.

(defun rontolisp::%regex-slot (cx)
  (let ((k (svref cx 0)))
    (setf (svref cx 0) (+ k 1))
    k))

(defun rontolisp::%regex-hit (st)
  "Marks that the search read the end of the input; answers nil, a failure."
  (setf (svref st 2) t)
  nil)

(defun rontolisp::%regex-sub-accept (st i)
  "Ends a sub-match (a lookahead's, an atomic group's, one iteration's) at I."
  (setf (svref st 4) i)
  (setf (svref st 3) i)
  t)

(defun rontolisp::%regex-final-accept (st i)
  "Ends the whole match at I, where the search asks for no more than a match;
   at the length alone where it asks for the whole input."
  (if (and (svref st 5) (< i (svref st 1)))
      nil
      (progn
        (if (svref st 5) (setf (svref st 2) t))
        (setf (svref st 4) i)
        t)))

(defun rontolisp::%regex-char-test (code mode)
  "The test of one literal character."
  (cond ((= mode 0) (lambda (x) (= x code)))
        ((= mode 1)
         (let ((other (rontolisp::%regex-ascii-other-case code)))
           (lambda (x) (or (= x code) (= x other)))))
        (t (let ((c (code-char code)))
             (if (and (char= (char-upcase c) c) (char= (char-downcase c) c))
                 (lambda (x) (= x code))
                 (lambda (x) (rontolisp::%regex-fold-equal-p x code)))))))

(defun rontolisp::%regex-single-test (node)
  "The test of a node that takes exactly one character, nil for any other."
  (cond ((eq (car node) :char)
         (rontolisp::%regex-char-test (car (cdr node)) (car (cdr (cdr node)))))
        ((eq (car node) :set) (car (cdr node)))
        (t nil)))

(defun rontolisp::%regex-emit (node next cx)
  (let ((kind (car node)))
    (cond
     ((eq kind :char)
      (if (= (car (cdr (cdr node))) 0)
          (rontolisp::%regex-emit-char (code-char (car (cdr node))) next)
          (rontolisp::%regex-emit-set
           (rontolisp::%regex-char-test (car (cdr node)) (car (cdr (cdr node))))
           next)))
     ((eq kind :str)
      (rontolisp::%regex-emit-str (car (cdr node)) (car (cdr (cdr node))) next))
     ((eq kind :set) (rontolisp::%regex-emit-set (car (cdr node)) next))
     ((eq kind :seq)
      (let ((k next))
        (dolist (n (reverse (car (cdr node))))
          (setq k (rontolisp::%regex-emit n k cx)))
        k))
     ((eq kind :alt)
      (rontolisp::%regex-emit-alt
       (mapcar (lambda (n) (rontolisp::%regex-emit n next cx))
               (car (cdr node)))))
     ((eq kind :empty) next)
     ((eq kind :group)
      (rontolisp::%regex-emit-group (car (cdr node)) (car (cdr (cdr node))) next
                                    cx))
     ((eq kind :nc) (rontolisp::%regex-emit (car (cdr node)) next cx))
     ((eq kind :atomic)
      (let ((body
             (rontolisp::%regex-emit (car (cdr node))
                                     #'rontolisp::%regex-sub-accept cx)))
        (lambda (st i)
          (and (funcall body st i) (funcall next st (svref st 4))))))
     ((eq kind :look)
      (let ((body
             (rontolisp::%regex-emit (car (cdr (cdr node)))
                                     #'rontolisp::%regex-sub-accept cx)))
        (if (car (cdr node))
            (lambda (st i) (and (funcall body st i) (funcall next st i)))
            (lambda (st i)
              (and (not (funcall body st i)) (funcall next st i))))))
     ((eq kind :lookb)
      (rontolisp::%regex-emit-look-behind (car (cdr node))
                                          (car (cdr (cdr node)))
                                          (car (cdr (cdr (cdr node))))
                                          (car (cdr (cdr (cdr (cdr node)))))
                                          next cx))
     ((eq kind :rep) (rontolisp::%regex-emit-rep node next cx))
     ((eq kind :ref)
      (rontolisp::%regex-emit-ref (car (cdr node)) (car (cdr (cdr node))) next
                                  cx))
     ((eq kind :bol) (lambda (st i) (and (= i 0) (funcall next st i))))
     ((eq kind :bolm) (rontolisp::%regex-emit-line-start (car (cdr node)) next))
     ((eq kind :eol) (rontolisp::%regex-emit-end (car (cdr node)) next))
     ((eq kind :eolm) (rontolisp::%regex-emit-line-end (car (cdr node)) next))
     ((eq kind :endz)
      (lambda (st i)
        (and (>= i (svref st 1))
             (progn
               (setf (svref st 2) t)
               (funcall next st i)))))
     ((eq kind :last)
      (lambda (st i) (and (= i (svref st 6)) (funcall next st i))))
     ((eq kind :word)
      (rontolisp::%regex-emit-word (car (cdr node)) (car (cdr (cdr node)))
                                   (car (cdr (cdr (cdr node)))) next))
     ((eq kind :linebreak) (rontolisp::%regex-emit-linebreak next))
     ((eq kind :graph) (rontolisp::%regex-emit-grapheme (car (cdr node)) next))
     ((eq kind :gbound)
      (rontolisp::%regex-emit-grapheme-boundary (car (cdr node)) next cx))
     (t (error "regex: unknown node ~A" kind)))))

(defun rontolisp::%regex-emit-char (c next)
  (lambda (st i)
    (if (< i (svref st 1))
        (and (char= (char (svref st 0) i) c) (funcall next st (+ i 1)))
        (rontolisp::%regex-hit st))))

(defun rontolisp::%regex-emit-set (test next)
  (lambda (st i)
    (if (< i (svref st 1))
        (and (funcall test (char-code (char (svref st 0) i)))
             (funcall next st (+ i 1)))
        (rontolisp::%regex-hit st))))

(defun rontolisp::%regex-code-equal-p (a b mode)
  "Whether two codes of a run or a back reference are one character."
  (cond ((= a b) t)
        ((= mode 0) nil)
        ((= mode 1)
         (and (< a 128) (= (rontolisp::%regex-ascii-other-case a) b)
              (rontolisp::%regex-ascii-letter-p a)))
        (t (rontolisp::%regex-fold-equal-p a b))))

(defun rontolisp::%regex-emit-str (text mode next)
  (let ((n (length text)))
    (lambda (st i)
      (if (> (+ i n) (svref st 1))
          (rontolisp::%regex-hit st)
          (let ((in (svref st 0)) (k 0))
            (if (= mode 0)
                (loop
                  (if (or (>= k n)
                          (not (char= (char in (+ i k)) (char text k))))
                      (return nil))
                  (setq k (+ k 1)))
                (loop
                  (if (or (>= k n)
                          (not
                           (rontolisp::%regex-code-equal-p
                            (char-code (char text k))
                            (char-code (char in (+ i k))) mode)))
                      (return nil))
                  (setq k (+ k 1))))
            (and (= k n) (funcall next st (+ i n))))))))

(defun rontolisp::%regex-emit-alt (branches)
  (if (and (cdr branches) (null (cdr (cdr branches))))
      (let ((a (car branches)) (b (car (cdr branches))))
        (lambda (st i) (or (funcall a st i) (funcall b st i))))
      (lambda (st i)
        (let ((rest branches) (found nil))
          (loop
            (if (or found (null rest)) (return found))
            (setq found (funcall (car rest) st i) rest (cdr rest)))))))

(defun rontolisp::%regex-emit-group (index body next cx)
  "A capturing group: its bounds are set where its body ends and given back
   when what follows fails."
  (let* ((s (+ 8 (* 2 index)))
         (e (+ s 1))
         (tmp (rontolisp::%regex-slot cx))
         (close
          (lambda (st i)
            (let ((os (svref st s)) (oe (svref st e)))
              (setf (svref st s) (svref st tmp))
              (setf (svref st e) i)
              (if (funcall next st i)
                  t
                  (progn
                    (setf (svref st s) os)
                    (setf (svref st e) oe)
                    nil)))))
         (inner (rontolisp::%regex-emit body close cx)))
    (lambda (st i)
      (let ((old (svref st tmp)))
        (setf (svref st tmp) i)
        (if (funcall inner st i)
            t
            (progn
              (setf (svref st tmp) old)
              nil))))))

(defun rontolisp::%regex-emit-ref (index mode next cx)
  (if (> index (svref cx 4))
      (lambda (st i)
        (declare (ignore st i))
        nil)
      (let ((s (+ 8 (* 2 index))))
        (lambda (st i)
          (let ((gs (svref st s)) (ge (svref st (+ s 1))))
            (if (or (< gs 0) (< ge 0))
                nil
                (let ((n (- ge gs)) (in (svref st 0)) (k 0))
                  (if (> (+ i n) (svref st 1))
                      (rontolisp::%regex-hit st)
                      (progn
                        (loop
                          (if (or (>= k n)
                                  (not
                                   (rontolisp::%regex-code-equal-p
                                    (char-code (char in (+ gs k)))
                                    (char-code (char in (+ i k))) mode)))
                              (return nil))
                          (setq k (+ k 1)))
                        (and (= k n) (funcall next st (+ i n))))))))))))

;;; Anchors.

(defun rontolisp::%regex-emit-line-start (unix next)
  "^ under MULTILINE: the start, or after a line terminator but not between a
   carriage return and its newline; never at the end, the start of an empty
   input included (measured: the oracle finds no line there)."
  (lambda (st i)
    (and (if (>= i (svref st 1))
             (rontolisp::%regex-hit st)
             (or (= i 0)
                 (let ((before (char-code (char (svref st 0) (- i 1)))))
                   (and (rontolisp::%regex-line-end-code-p before unix)
                        (not
                         (and (= before 13)
                              (= (char-code (char (svref st 0) i)) 10)))))))
         (funcall next st i))))

(defun rontolisp::%regex-emit-end (unix next)
  "$ without MULTILINE and \\Z: the end, or before the line terminator that
   ends the input."
  (lambda (st i)
    (let ((len (svref st 1)) (in (svref st 0)))
      (if (>= i (- len 2)) (setf (svref st 2) t))
      (and (cond ((>= i len) t)
                 ((= i (- len 1))
                  (let ((c (char-code (char in i))))
                    (and (rontolisp::%regex-line-end-code-p c unix)
                         (not
                          (and (= c 10) (not unix) (> i 0)
                               (= (char-code (char in (- i 1))) 13))))))
                 ((and (= i (- len 2)) (not unix))
                  (and (= (char-code (char in i)) 13)
                       (= (char-code (char in (+ i 1))) 10)))
                 (t nil)) (funcall next st i)))))

(defun rontolisp::%regex-emit-line-end (unix next)
  "$ under MULTILINE: the end, or before a line terminator but not between a
   carriage return and its newline."
  (lambda (st i)
    (let ((len (svref st 1)) (in (svref st 0)))
      (and (if (>= i len)
               (progn
                 (setf (svref st 2) t)
                 t)
               (let ((c (char-code (char in i))))
                 (and (rontolisp::%regex-line-end-code-p c unix)
                      (not
                       (and (= c 10) (not unix) (> i 0)
                            (= (char-code (char in (- i 1))) 13))))))
           (funcall next st i)))))

(defun rontolisp::%regex-word-code-p (c)
  (or (and (>= c 97) (<= c 122)) (and (>= c 65) (<= c 90))
      (and (>= c 48) (<= c 57)) (= c 95)))

(defun rontolisp::%regex-emit-word (positive unicode cat next)
  "\\b and \\B: whether exactly one of the characters around I is a word
   character. Past ASCII the category provider's test answers, where a
   provider was given."
  (let ((word (if cat (funcall cat unicode) nil)))
    (lambda (st i)
      (let* ((in (svref st 0))
             (len (svref st 1))
             (before
              (and (> i 0)
                   (let ((c (char-code (char in (- i 1)))))
                     (if (and word (or unicode (>= c 128)))
                         (funcall word in (- i 1))
                         (rontolisp::%regex-word-code-p c)))))
             (after
              (if (< i len)
                  (let ((c (char-code (char in i))))
                    (if (and word (or unicode (>= c 128)))
                        (funcall word in i)
                        (rontolisp::%regex-word-code-p c)))
                  (rontolisp::%regex-hit st))))
        (and (if positive
                 (if before (not after) after)
                 (if before after (not after))) (funcall next st i))))))

(defun rontolisp::%regex-linebreak-code-p (c)
  (or (and (>= c 10) (<= c 13)) (= c 133) (= c 8232) (= c 8233)))

(defun rontolisp::%regex-emit-linebreak (next)
  "\\R on its own: a carriage return and its newline, else the return alone
   or any other line break character."
  (lambda (st i)
    (let ((len (svref st 1)) (in (svref st 0)))
      (if (>= i (- len 1)) (setf (svref st 2) t))
      (and (< i len)
           (let ((c (char-code (char in i))))
             (cond
              ((and (= c 13) (< (+ i 1) len)
                    (= (char-code (char in (+ i 1))) 10))
               (or (funcall next st (+ i 2)) (funcall next st (+ i 1))))
              ((rontolisp::%regex-linebreak-code-p c) (funcall next st (+ i 1)))
              (t nil)))))))

(defun rontolisp::%regex-emit-grapheme (provider next)
  "\\X: one grapheme cluster."
  (lambda (st i)
    (let ((len (svref st 1)))
      (if (>= i len)
          (rontolisp::%regex-hit st)
          (let ((end (funcall provider (svref st 0) i)))
            (if (>= end len) (setf (svref st 2) t))
            (funcall next st end))))))

(defun rontolisp::%regex-emit-grapheme-boundary (provider next cx)
  "\\b{g}: the start, the end, or a position the cluster that starts at the
   state's last end does not reach past. That is the oracle's test, measured:
   it asks about the one cluster from where the last match (or the last
   sub-match of this search) ended, so it holds inside a cluster further on.
   Where the last end is the input's end the oracle fails with an index out
   of bounds; here the refusal function is told, with :bounds for the index.
   (No function the engine calls takes more than two arguments: a wider call
   through a function value makes every compiled program carry a dispatcher
   of that width.)"
  (let ((fail (svref cx 5)))
    (lambda (st i)
      (let ((len (svref st 1)))
        (if (>= i len) (setf (svref st 2) t))
        (and (or (= i 0) (>= i len)
                 (let ((last (svref st 3)))
                   (if (>= last len)
                       (if (and (svref st 7) (> (svref st 7) len))
                           ;; a prefix of a longer input: the search reads on
                           (rontolisp::%regex-hit st)
                           (let ((text
                                  (concatenate 'string "Index "
                                               (princ-to-string len)
                                               " out of bounds for length "
                                               (princ-to-string len))))
                             (funcall fail text :bounds)
                             (error "~A" text)))
                       (let ((end (funcall provider (svref st 0) last)))
                         (if (>= end len) (setf (svref st 2) t))
                         (<= end i))))) (funcall next st i))))))

;;; Look-behind.
;;;
;;; A look-behind's body is tried from each start between its least and its
;;; greatest length back from I. The bounds are the oracle's, measured: it
;;; counts in 32-bit integers that wrap, an unbounded count is the largest of
;;; them, and it refuses a body whose bound it cannot state. So one unbounded
;;; repetition of one character reaches back to the start, a second one wraps
;;; the bound below zero and the look-behind never matches, and a counted or
;;; lazy repetition after anything unbounded is refused (ClojureRegexTest pins
;;; the probes these rules were read from).

(defun rontolisp::%regex-wrap (x)
  "X as a 32-bit signed integer."
  (let ((y (logand x 4294967295))) (if (>= y 2147483648) (- y 4294967296) y)))

(defun rontolisp::%regex-info-add (info least most)
  "Adds LEAST and MOST characters to INFO's bounds, the greatest wrapping."
  (setf (svref info 0) (+ (svref info 0) least))
  (setf (svref info 1) (rontolisp::%regex-wrap (+ (svref info 1) most))))

(defun rontolisp::%regex-study (node info)
  "Adds to INFO -- (vector min max valid) -- the least and the greatest number
   of characters NODE takes, and clears VALID where the greatest is not known."
  (rontolisp::%regex-study-chain (list node) info))

(defun rontolisp::%regex-study-chain (nodes info)
  "Studies NODES, what follows one another. After alternatives (an alternation,
   an optional group) what follows is counted from zero and then added, so a
   repetition there is judged without what stands before the alternatives."
  (loop
    (if (null nodes) (return nil))
    (let* ((node (car nodes)) (kind (car node)))
      (setq nodes (cdr nodes))
      (cond ((eq kind :seq) (setq nodes (append (car (cdr node)) nodes)))
            ((eq kind :group) (setq nodes (cons (car (cdr (cdr node))) nodes)))
            ((eq kind :nc) (setq nodes (cons (car (cdr node)) nodes)))
            ((or (eq kind :alt) (rontolisp::%regex-optional-group-p node))
             ;; the greatest starts below zero, as the oracle's does:
             ;; alternatives whose bounds all wrapped below it leave it there
             (let ((least nil) (most -1) (valid t) (rest (vector 0 0 t)))
               (dolist (branch
                        (if (eq kind :alt)
                            (car (cdr node))
                            (list (car (cdr node)) (list :empty))))
                 (let ((sub (vector 0 0 t)))
                   (rontolisp::%regex-study-chain (list branch) sub)
                   (setq least
                         (if least (min least (svref sub 0)) (svref sub 0)))
                   (setq most (max most (svref sub 1)))
                   (if (not (svref sub 2)) (setq valid nil))))
               (rontolisp::%regex-study-chain nodes rest)
               (rontolisp::%regex-info-add info (+ least (svref rest 0))
                                           (+ most (svref rest 1)))
               (if (not (and valid (svref rest 2))) (setf (svref info 2) nil))
               (return nil)))
            ((or (eq kind :char) (eq kind :set))
             (rontolisp::%regex-info-add info 1 1))
            ((eq kind :str)
             (let ((n (length (car (cdr node)))))
               (rontolisp::%regex-info-add info n n)))
            ((eq kind :atomic)
             (rontolisp::%regex-study-chain (list (car (cdr node))) info))
            ((eq kind :rep) (rontolisp::%regex-study-rep node info))
            ((eq kind :ref) (setf (svref info 2) nil))
            ((eq kind :linebreak) (rontolisp::%regex-info-add info 1 2))
            ((eq kind :graph) (rontolisp::%regex-info-add info 1 0))
            (t nil)))))

(defun rontolisp::%regex-optional-group-p (node)
  "Whether NODE is a group under ?, which is tried as two alternatives."
  (and (eq (car node) :rep) (= (car (cdr (cdr node))) 0)
       (= (car (cdr (cdr (cdr node)))) 1)
       (not (eq (car (cdr (cdr (cdr (cdr node))))) :possessive))
       (or (eq (car (car (cdr node))) :group) (eq (car (car (cdr node))) :nc))))

(defun rontolisp::%regex-study-rep (node info)
  (let* ((atom (car (cdr node)))
         (min (car (cdr (cdr node))))
         (max (car (cdr (cdr (cdr node)))))
         (kind (car (cdr (cdr (cdr (cdr node))))))
         (single (or (eq (car atom) :char) (eq (car atom) :set)))
         (group (or (eq (car atom) :group) (eq (car atom) :nc))))
    (cond ((and single (eq kind :greedy) (< max 0))
           (rontolisp::%regex-info-add info min 2147483647))
          ((and (= min 0) (= max 1))
           ;; an optional unit is counted where it stands: only the least stays
           (let ((least (svref info 0)))
             (rontolisp::%regex-study-chain (list atom) info)
             (setf (svref info 0) least)))
          ((and group (not (eq kind :possessive))
                (not
                 (rontolisp::%regex-fixed-p
                  (if (eq (car atom) :group)
                      (car (cdr (cdr atom)))
                      (car (cdr atom))))))
           (setf (svref info 2) nil))
          (t (let ((sub (vector 0 0 t)) (before (svref info 1)))
               (rontolisp::%regex-study atom sub)
               (setf (svref info 0) (+ (svref info 0) (* (svref sub 0) min)))
               (if (and (svref info 2) (svref sub 2))
                   (let ((most
                          (rontolisp::%regex-wrap
                           (+ (rontolisp::%regex-wrap
                               (* (svref sub 1) (if (< max 0) 2147483647 max)))
                              before))))
                     (setf (svref info 1) most)
                     (if (< most before) (setf (svref info 2) nil)))
                   (setf (svref info 2) nil)))))))

(defun rontolisp::%regex-emit-look-behind (positive body least most next cx)
  "A look-behind: the body is tried from each start it can have, the nearest
   first, and must end at I."
  (let* ((end (rontolisp::%regex-slot cx))
         (check (lambda (st k) (= k (svref st end))))
         (was (svref cx 3))
         (inner
          (progn
            (setf (svref cx 3) t)
            (rontolisp::%regex-emit body check cx))))
    (setf (svref cx 3) was)
    (lambda (st i)
      (let ((old (svref st end))
            (j (- i least))
            (low (max 0 (rontolisp::%regex-wrap (- i most))))
            (found nil))
        (setf (svref st end) i)
        (loop
          (if (or found (< j low)) (return nil))
          (if (funcall inner st j) (setq found t) (setq j (- j 1))))
        (setf (svref st end) old)
        (and (if positive found (not found)) (funcall next st i))))))

;;; Quantifiers.

(defun rontolisp::%regex-fixed-p (node)
  "Whether NODE matches one way: no alternative, no optional or variable
   repetition, no back reference. A loop over such a body keeps or drops whole
   iterations and never searches inside one."
  (let ((kind (car node)))
    (cond ((eq kind :seq)
           (let ((all t))
             (dolist (n (car (cdr node)))
               (if (not (rontolisp::%regex-fixed-p n)) (setq all nil)))
             all))
          ((eq kind :group) (rontolisp::%regex-fixed-p (car (cdr (cdr node)))))
          ((or (eq kind :nc) (eq kind :atomic))
           (rontolisp::%regex-fixed-p (car (cdr node))))
          ((eq kind :rep)
           (and (= (car (cdr (cdr node))) (car (cdr (cdr (cdr node)))))
                (rontolisp::%regex-fixed-p (car (cdr node)))))
          ((or (eq kind :alt) (eq kind :ref) (eq kind :graph)) nil)
          (t t))))

(defun rontolisp::%regex-emit-rep (node next cx)
  (let* ((atom (car (cdr node)))
         (min (car (cdr (cdr node))))
         (max (car (cdr (cdr (cdr node)))))
         (kind (car (cdr (cdr (cdr (cdr node))))))
         (test (rontolisp::%regex-single-test atom)))
    (cond (test (rontolisp::%regex-emit-run test min max kind next))
     ((or (eq (car atom) :group) (eq (car atom) :nc))
      (let ((body
             (if (eq (car atom) :group)
                 (car (cdr (cdr atom)))
                 (car (cdr atom))))
            (index (if (eq (car atom) :group) (car (cdr atom)) nil)))
        (cond ((and (= min 0) (= max 1))
               (cond ((eq kind :greedy)
                      (let ((inner (rontolisp::%regex-emit atom next cx)))
                        (lambda (st i)
                          (or (funcall inner st i) (funcall next st i)))))
                     ((eq kind :lazy)
                      (let ((inner (rontolisp::%regex-emit atom next cx)))
                        (lambda (st i)
                          (or (funcall next st i) (funcall inner st i)))))
                     (t (let ((inner
                               (rontolisp::%regex-emit atom
                                #'rontolisp::%regex-sub-accept cx)))
                          (lambda (st i)
                            (if (funcall inner st i)
                                (funcall next st (svref st 4))
                                (funcall next st i)))))))
              ((eq kind :possessive)
               ;; the group is the unit, taken whole each time whatever its
               ;; body: its bounds stay as its own end set them
               (rontolisp::%regex-emit-fixed-loop atom nil min max kind next
                                                  cx))
              ((rontolisp::%regex-fixed-p body)
               (rontolisp::%regex-emit-fixed-loop body index min max kind next
                                                  cx))
              (t (rontolisp::%regex-emit-loop atom min max (eq kind :lazy) next
                                              cx)))))
     (t (rontolisp::%regex-emit-fixed-loop atom nil min max kind next cx)))))

(defun rontolisp::%regex-run-end (test st i max)
  "Where the run of characters TEST takes from I ends: at most MAX of them
   (no bound below zero). Marks the state where the run reaches the input's
   end."
  (let* ((in (svref st 0))
         (len (svref st 1))
         (limit (if (< max 0) len (min len (+ i max))))
         (j i))
    (loop
      (if (or (>= j limit) (not (funcall test (char-code (char in j)))))
          (return nil))
      (setq j (+ j 1)))
    (if (>= j len) (setf (svref st 2) t))
    j))

(defun rontolisp::%regex-emit-run (test min max kind next)
  "A repetition of one character: counted, never searched inside. Every kind
   but the greedy one with no upper bound moves the state's last end to where
   the furthest character it took ends (measured: the oracle's matcher shows
   it), and leaves it there when it gives characters back."
  (cond ((eq kind :greedy)
         (let ((moves (>= max 0)))
           (lambda (st i)
             (let ((j (rontolisp::%regex-run-end test st i max))
                   (low (+ i min)))
               (if (and moves (> j i)) (setf (svref st 3) j))
               (if (< j low)
                   nil
                   (loop
                     (if (funcall next st j) (return t))
                     (if (<= j low) (return nil))
                     (setq j (- j 1))))))))
        ((eq kind :lazy)
         (lambda (st i)
           (let ((j (rontolisp::%regex-run-end test st i min)) (low (+ i min)))
             (if (> j i) (setf (svref st 3) j))
             (if (< j low)
                 nil
                 (let* ((in (svref st 0))
                        (len (svref st 1))
                        (limit (if (< max 0) len (min len (+ i max)))))
                   (loop
                     (if (funcall next st j) (return t))
                     (if (>= j limit)
                         (return
                          (if (>= j len) (rontolisp::%regex-hit st) nil)))
                     (if (not (funcall test (char-code (char in j))))
                         (return nil))
                     (setq j (+ j 1))
                     (setf (svref st 3) j)))))))
        (t (lambda (st i)
             (let ((j (rontolisp::%regex-run-end test st i max)))
               (if (> j i) (setf (svref st 3) j))
               (and (>= (- j i) min) (funcall next st j)))))))

(defun rontolisp::%regex-set-group (st slot start end)
  "Sets the bounds of the group whose start is in SLOT; answers nil."
  (setf (svref st slot) start)
  (setf (svref st (+ slot 1)) end)
  nil)

(defun rontolisp::%regex-emit-fixed-loop (body index min max kind next cx)
  "A repetition of a body that matches one way: each iteration is taken whole
   (its end read back from the state), and giving back is dropping the last
   one. INDEX is the group the body is, whose bounds follow the last iteration
   kept; groups inside keep what the furthest iteration set. An iteration past
   the minimum that takes nothing is not taken; a possessive loop takes it and
   ends."
  (let ((inner (rontolisp::%regex-emit body #'rontolisp::%regex-sub-accept cx))
        (gs (if index (+ 8 (* 2 index)) nil)))
    (if (eq kind :lazy)
        (lambda (st i)
          (let ((count 0)
                (pos i)
                (start -1)
                (ok t)
                (os (if gs (svref st gs) -1))
                (oe (if gs (svref st (+ gs 1)) -1)))
            (loop
              (if (>= count min) (return nil))
              (if (not (funcall inner st pos))
                  (progn
                    (setq ok nil)
                    (return nil)))
              (setq start pos pos (svref st 4) count (+ count 1)))
            (and ok
                 (loop
                   (if (and gs (> count 0))
                       (rontolisp::%regex-set-group st gs start pos))
                   (if (funcall next st pos) (return t))
                   (if (or (and (>= max 0) (>= count max))
                           (not (funcall inner st pos)))
                       (return
                        (if gs (rontolisp::%regex-set-group st gs os oe) nil)))
                   ;; an iteration that took nothing is the last: after a lazy
                   ;; ? what follows is tried once more (the unit may have set
                   ;; a group), after any other count it is not (measured)
                   (if (= (svref st 4) pos)
                       (return
                        (or (and (= max 1) (funcall next st pos))
                            (if gs
                                (rontolisp::%regex-set-group st gs os oe)
                                nil))))
                   (setq start pos pos (svref st 4) count (+ count 1))))))
        (let ((possessive (eq kind :possessive)))
          (lambda (st i)
            (let ((count 0)
                  (pos i)
                  (starts nil)
                  (os (if gs (svref st gs) -1))
                  (oe (if gs (svref st (+ gs 1)) -1)))
              (loop
                (if (and (>= max 0) (>= count max)) (return nil))
                (if (not (funcall inner st pos)) (return nil))
                (let ((e (svref st 4)))
                  (if (and (= e pos) (>= count min) (not possessive))
                      (return nil))
                  (setq starts (cons pos starts) count (+ count 1))
                  (if (and (= e pos) (>= count min)) (return nil))
                  (setq pos e)))
              (if (< count min)
                  nil
                  (loop
                    (if gs
                        (if starts
                            (rontolisp::%regex-set-group st gs (car starts) pos)
                            (rontolisp::%regex-set-group st gs os oe)))
                    (if (funcall next st pos) (return t))
                    (if (or possessive (<= count min))
                        (return
                         (if gs (rontolisp::%regex-set-group st gs os oe) nil)))
                    (setq pos (car starts) starts (cdr starts) count
                          (- count 1))))))))))

(defun rontolisp::%regex-loop-begin (st i cnt ipos n)
  "Sets the count and the start of one more iteration of a general loop, the
   one after N, from I; answers the start that stood there."
  (let ((p (svref st ipos)))
    (setf (svref st cnt) (+ n 1))
    (setf (svref st ipos) i)
    p))

(defun rontolisp::%regex-loop-undo (st cnt ipos n p)
  "Gives the count N and the iteration start P back after an iteration that
   failed; answers nil. (The body is called by the step itself, not from a
   helper: every frame between two iterations is depth a long input pays.)"
  (setf (svref st cnt) n)
  (setf (svref st ipos) p)
  nil)

(defun rontolisp::%regex-loop-remember (st memo i)
  "Remembers that the loop whose memo slot is MEMO cannot continue from I;
   answers nil."
  (let ((m (svref st memo)))
    (if (integerp m)
        (progn
          (setq m (make-array (+ (svref st 1) 1) :initial-element nil))
          (setf (svref st memo) m)))
    (setf (svref m i) t)
    nil))

(defun rontolisp::%regex-emit-loop (body min max lazy next cx)
  "A repetition of a body that can match several ways: the count and the start
   of the iteration under way live in the state, and the body's own matcher
   continues with the step, so every way through every iteration is tried. An
   iteration that took nothing is the last. A greedy loop with no upper bound,
   outside every other loop and look-behind, in a pattern with no back
   reference, remembers the positions it could not continue from."
  (let* ((cnt (rontolisp::%regex-slot cx))
         (ipos (rontolisp::%regex-slot cx))
         (memo
          (if (and (not lazy) (< max 0) (svref cx 1) (= (svref cx 2) 0)
                   (not (svref cx 3)))
              (rontolisp::%regex-slot cx)
              nil))
         (inner nil)
         (step
          (if lazy
              (lambda (st i)
                (let ((n (svref st cnt)))
                  (cond
                   ((< n min)
                    (let ((p (rontolisp::%regex-loop-begin st i cnt ipos n)))
                      (or (funcall inner st i)
                          (rontolisp::%regex-loop-undo st cnt ipos n p))))
                   ((funcall next st i) t)
                   ((or (= i (svref st ipos)) (and (>= max 0) (>= n max))) nil)
                   (t (let ((p (rontolisp::%regex-loop-begin st i cnt ipos n)))
                        (or (funcall inner st i)
                            (rontolisp::%regex-loop-undo st cnt ipos n p)))))))
              (lambda (st i)
                (let ((n (svref st cnt)))
                  (cond ((< n min)
                         (let ((p
                                (rontolisp::%regex-loop-begin st i cnt ipos n)))
                           (or (funcall inner st i)
                               (rontolisp::%regex-loop-undo st cnt ipos n p))))
                        ((or (= i (svref st ipos)) (and (>= max 0) (>= n max)))
                         (funcall next st i))
                        ((and memo
                              (let ((m (svref st memo)))
                                (and (not (integerp m)) (svref m i))))
                         nil)
                        ((let ((p
                                (rontolisp::%regex-loop-begin st i cnt ipos n)))
                           (or (funcall inner st i)
                               (rontolisp::%regex-loop-undo st cnt ipos n p)))
                         t)
                        ((funcall next st i) t)
                        (memo (rontolisp::%regex-loop-remember st memo i))
                        (t nil)))))))
    (setf (svref cx 2) (+ (svref cx 2) 1))
    (setq inner (rontolisp::%regex-emit body step cx))
    (setf (svref cx 2) (- (svref cx 2) 1))
    (lambda (st i)
      (let ((n0 (svref st cnt)) (p0 (svref st ipos)))
        (setf (svref st cnt) 0)
        (setf (svref st ipos) -1)
        (if (funcall step st i)
            t
            (progn
              (setf (svref st cnt) n0)
              (setf (svref st ipos) p0)
              nil))))))

(defun rontolisp::%regex-min-length (node)
  "The least number of characters NODE takes."
  (let ((kind (car node)))
    (cond
     ((or (eq kind :char) (eq kind :set) (eq kind :linebreak) (eq kind :graph))
      1)
     ((eq kind :str) (length (car (cdr node))))
     ((eq kind :seq)
      (let ((total 0))
        (dolist (n (car (cdr node)) total)
          (setq total (+ total (rontolisp::%regex-min-length n))))))
     ((eq kind :alt)
      (let ((least nil))
        (dolist (n (car (cdr node)) least)
          (let ((m (rontolisp::%regex-min-length n)))
            (setq least (if least (min least m) m))))))
     ((eq kind :group) (rontolisp::%regex-min-length (car (cdr (cdr node)))))
     ((or (eq kind :nc) (eq kind :atomic))
      (rontolisp::%regex-min-length (car (cdr node))))
     ((eq kind :rep)
      (* (car (cdr (cdr node)))
         (rontolisp::%regex-min-length (car (cdr node)))))
     (t 0))))

;;; ---------------------------------------------------------------------------
;;; Patterns and searches.

(defun rontolisp::%regex-compile (source flags names fail uni cat graph)
  "The program of the pattern SOURCE under FLAGS (Pattern's bits): a simple
   vector of the entry matcher, the number of groups, the group names, the
   size of a search's state, the source, the flags and the least length of a
   match. NAMES is the table a \\N{name} is looked up in, ((name . code) ...),
   or :none. FAIL is called with a description and an index for a pattern
   refused (%regex-syntax-message makes the oracle's text of them), and does
   not return. UNI, CAT and GRAPH are the providers of the Unicode properties,
   of the word characters past ASCII and of the grapheme clusters; a pattern
   naming one it was not given is refused as unsupported."
  (let* ((literal (/= 0 (logand flags 16)))
         (pat (if literal source (rontolisp::%regex-unquote source)))
         (ps
          (vector pat 0 flags 0 nil fail source uni cat graph names nil
                  (length pat)))
         (tree
          (if literal
              (let ((items nil))
                (dotimes (k (length source))
                  (setq items
                        (cons (rontolisp::%regex-char-node ps
                               (char-code (char source k))) items)))
                (rontolisp::%regex-make-seq (reverse items)))
              (rontolisp::%regex-parse ps)))
         (groups (svref ps 3))
         (cx
          (vector (+ 8 (* 2 (+ groups 1))) (not (svref ps 11)) 0 nil groups fail
                  source))
         (entry
          (rontolisp::%regex-emit tree #'rontolisp::%regex-final-accept cx)))
    (vector entry groups (svref ps 4) (svref cx 0) source flags
            (rontolisp::%regex-min-length tree))))

(defun rontolisp::%regex-group-count (program) (svref program 1))

(defun rontolisp::%regex-group-names (program) (svref program 2))

(defun rontolisp::%regex-source (program) (svref program 4))

(defun rontolisp::%regex-flags (program) (svref program 5))

(defun rontolisp::%regex-state (program input)
  "A fresh search state over INPUT."
  (let ((st (make-array (svref program 3) :initial-element -1)))
    (setf (svref st 0) input)
    (setf (svref st 1) (length input))
    (setf (svref st 2) nil)
    (setf (svref st 3) 0)
    (setf (svref st 5) nil)
    (setf (svref st 7) nil)
    st))

(defun rontolisp::%regex-search (program st from mode anchor)
  "Searches the state's input: MODE 0 for a match starting at FROM or later,
   1 for one starting at FROM, 2 for one from FROM to the end. \\G holds at
   ANCHOR. A match leaves its bounds in the state's group slots (group 0 the
   whole match) and its end as the last end."
  (let ((n (svref program 3)) (k 8))
    (loop (if (>= k n) (return nil)) (setf (svref st k) -1) (setq k (+ k 1))))
  (setf (svref st 5) (= mode 2))
  (setf (svref st 6) anchor)
  (let* ((entry (svref program 0))
         (len (svref st 1))
         (i from)
         (found nil)
         ;; no match starts where fewer characters are left than one takes
         (stop (- len (svref program 6))))
    (if (= mode 0)
        (loop
          (if (> i stop)
              (progn
                (setf (svref st 2) t)
                (return nil)))
          (if (funcall entry st i)
              (progn
                (setq found t)
                (return nil)))
          (setq i (+ i 1)))
        (setq found (funcall entry st i)))
    (if found
        (progn
          (setf (svref st 8) i)
          (setf (svref st 9) (svref st 4))
          (setf (svref st 3) (svref st 4))
          t)
        nil)))

(defun rontolisp::%regex-group-start (st group) (svref st (+ 8 (* 2 group))))

(defun rontolisp::%regex-group-end (st group) (svref st (+ 9 (* 2 group))))
;;; ---------------------------------------------------------------------------
;;; Matchers: the searches of one pattern over one input, in a row.
;;;
;;; A matcher is a simple vector: 0 the program, 1 the search state, 2 the
;;; start of the last match (-1 when the last search found none), 3 where \G
;;; holds for the next search (-1 until the first search since a reset, which
;;; holds it where it starts), 4 whether the last search found a match.
;;;
;;; What the API documentation leaves open was measured against a JDK's
;;; Matcher: find starts where the last match ended, one further after an
;;; empty one; a search that finds nothing keeps the last end (which the
;;; sub-matches of the failed search may have moved) and the next find starts
;;; there.

(defun rontolisp::%regex-matcher (program input)
  (vector program (rontolisp::%regex-state program input) -1 -1 nil))

(defun rontolisp::%regex-matcher-program (m) (svref m 0))

(defun rontolisp::%regex-matcher-input (m) (svref (svref m 1) 0))

(defun rontolisp::%regex-matcher-matched-p (m) (svref m 4))

(defun rontolisp::%regex-matcher-whole-length (m total)
  "Tells the matcher its input is the first part of one TOTAL characters long
   (%regex-matcher-search-growing reads the rest as searches need it)."
  (setf (svref (svref m 1) 7) total)
  m)

(defun rontolisp::%regex-matcher-reset (m)
  "Forgets the last match: the next find starts at 0."
  (let ((st (svref m 1)))
    (setf (svref st 3) 0)
    (setf (svref m 2) -1)
    (setf (svref m 3) -1)
    (setf (svref m 4) nil)
    m))

(defun rontolisp::%regex-matcher-search (m from mode)
  "One search (MODE as %regex-search) from FROM; answers whether it matched."
  (let* ((st (svref m 1))
         (found
          (rontolisp::%regex-search (svref m 0) st from mode
                                    (if (< (svref m 3) 0) from (svref m 3)))))
    (setf (svref m 2) (if found (svref st 8) -1))
    (setf (svref m 3) (svref st 3))
    (setf (svref m 4) found)
    found))

(defun rontolisp::%regex-matcher-search-growing (m from mode grow)
  "As %regex-matcher-search over an input known only in part. GROW, a function
   of the part the state holds, answers a longer part, or nil when that is the
   whole input. A search that read the end of the part runs again over a
   longer one, so its answer is the one over the whole input."
  (let* ((st (svref m 1))
         (first (svref m 2))
         (anchor (svref m 3))
         (last (svref st 3)))
    (loop
      (setf (svref st 2) nil)
      (let ((found
             (and (<= from (svref st 1))
                  (rontolisp::%regex-matcher-search m from mode))))
        (if (and (<= from (svref st 1)) (not (svref st 2))) (return found))
        (let ((more (funcall grow (svref st 0))))
          (if (null more)
              (progn
                (if (> from (svref st 1)) (setf (svref m 4) nil))
                (return found)))
          (setf (svref st 0) more)
          (setf (svref st 1) (length more))
          (setf (svref st 3) last)
          (setf (svref m 2) first)
          (setf (svref m 3) anchor))))))

(defun rontolisp::%regex-matcher-next-start (m)
  "Where the next find starts: the last end, one further after an empty match."
  (let ((last (svref (svref m 1) 3)))
    (if (= (svref m 2) last) (+ last 1) last)))

(defun rontolisp::%regex-matcher-find (m)
  "The next match after the last one: whether there is one."
  (let ((from (rontolisp::%regex-matcher-next-start m)))
    (if (> from (svref (svref m 1) 1))
        (progn
          (setf (svref m 4) nil)
          nil)
        (rontolisp::%regex-matcher-search m from 0))))

(defun rontolisp::%regex-matcher-find-from (m start)
  "Resets the matcher and finds the first match at START or later."
  (rontolisp::%regex-matcher-reset m)
  (rontolisp::%regex-matcher-search m start 0))

(defun rontolisp::%regex-matcher-looking-at (m)
  (rontolisp::%regex-matcher-search m 0 1))

(defun rontolisp::%regex-matcher-matches (m)
  (rontolisp::%regex-matcher-search m 0 2))

(defun rontolisp::%regex-matcher-start (m group)
  "The start of GROUP in the last match, -1 for a group that took no part."
  (svref (svref m 1) (+ 8 (* 2 group))))

(defun rontolisp::%regex-matcher-end (m group)
  (svref (svref m 1) (+ 9 (* 2 group))))

(defun rontolisp::%regex-matcher-group (m group)
  "The text of GROUP in the last match, nil for a group that took no part."
  (let* ((st (svref m 1))
         (s (svref st (+ 8 (* 2 group))))
         (e (svref st (+ 9 (* 2 group)))))
    (if (or (< s 0) (< e 0)) nil (subseq (svref st 0) s e))))

(defun rontolisp::%regex-group-index (program name)
  "The index of the group named NAME, nil for no such group."
  (let ((found (rontolisp::%regex-named name (svref program 2))))
    (if found (cdr found) nil)))

;;; Replacement, split and quote.

(defun rontolisp::%regex-expand-replacement (m replacement refuse out)
  "Writes REPLACEMENT to OUT for the last match of M: $n and ${name} are the
   groups (a group that took no part is nothing), a backslash quotes the next
   character. REFUSE is called with :argument or :bounds and a message for a
   replacement that names no group, and does not return."
  (let ((n (length replacement)) (i 0) (program (svref m 0)))
    (loop
      (if (>= i n) (return nil))
      (let ((c (char replacement i)))
        (cond ((char= c #\\)
               (setq i (+ i 1))
               (if (>= i n)
                   (funcall refuse
                            :argument "character to be escaped is missing"))
               (write-char (char replacement i) out)
               (setq i (+ i 1)))
              ((char= c #\$)
               (setq i (+ i 1))
               (if (>= i n)
                   (funcall refuse
                            :argument
                            "Illegal group reference: group index is missing"))
               (let ((d (char-code (char replacement i))) (group nil))
                 (cond ((= d 123)
                        (setq i (+ i 1))
                        (let ((start i))
                          (loop
                            (if (or (>= i n)
                                    (let ((k (char-code (char replacement i))))
                                      (not
                                       (or (rontolisp::%regex-ascii-letter-p k)
                                        (rontolisp::%regex-ascii-digit-p k)))))
                                (return nil))
                            (setq i (+ i 1)))
                          (if (= i start)
                              (funcall refuse
                               :argument
                               "named capturing group has 0 length name"))
                          (let ((name (subseq replacement start i)))
                            (if (or (>= i n)
                                    (not (char= (char replacement i) #\})))
                                (funcall refuse
                                         :argument
                                         "named capturing group is missing trailing '}'"))
                            (if (rontolisp::%regex-ascii-digit-p
                                 (char-code (char name 0)))
                                (funcall refuse
                                         :argument
                                         (concatenate 'string
                                          "capturing group name {" name
                                          "} starts with digit character")))
                            (setq group
                                  (rontolisp::%regex-group-index program name))
                            (if (null group)
                                (funcall refuse
                                         :argument
                                         (concatenate 'string
                                                      "No group with name {"
                                                      name "}")))
                            (setq i (+ i 1)))))
                       ((rontolisp::%regex-ascii-digit-p d)
                        (setq group (- d 48) i (+ i 1))
                        (if (> group (svref program 1))
                            (funcall refuse
                                     :bounds
                                     (concatenate 'string "No group "
                                                  (princ-to-string group))))
                        (loop
                          (if (>= i n) (return nil))
                          (let ((k (char-code (char replacement i))))
                            (if (or (not (rontolisp::%regex-ascii-digit-p k))
                                    (> (+ (* group 10) (- k 48))
                                       (svref program 1)))
                                (return nil))
                            (setq group (+ (* group 10) (- k 48)) i (+ i 1)))))
                       (t (funcall refuse :argument "Illegal group reference")))
                 (let ((text (rontolisp::%regex-matcher-group m group)))
                   (if text (write-string text out)))))
              (t
               (write-char c out)
               (setq i (+ i 1))))))))

(defun rontolisp::%regex-replace (program input replacement all refuse)
  "INPUT with every match (ALL), or the first one, replaced. REPLACEMENT is a
   replacement string, or a function of the matcher that answers the text to
   put there."
  (let ((m (rontolisp::%regex-matcher program input))
        (out (make-string-output-stream))
        (at 0)
        (any nil))
    (loop
      (if (not (rontolisp::%regex-matcher-find m)) (return nil))
      (setq any t)
      (write-string (subseq input at (rontolisp::%regex-matcher-start m 0)) out)
      (if (stringp replacement)
          (rontolisp::%regex-expand-replacement m replacement refuse out)
          (write-string (funcall replacement m) out))
      (setq at (rontolisp::%regex-matcher-end m 0))
      (if (not all) (return nil)))
    (if any
        (progn
          (write-string (subseq input at) out)
          (get-output-stream-string out))
        input)))

(defun rontolisp::%regex-split (program input limit)
  "The pieces of INPUT around the matches, as a list: at most LIMIT of them
   where LIMIT is above zero, the empty ones at the end dropped where it is
   zero. A match of no characters at the start makes no piece."
  (let ((m (rontolisp::%regex-matcher program input))
        (pieces nil)
        (count 0)
        (at 0))
    (loop
      (if (and (> limit 0) (>= count (- limit 1))) (return nil))
      (if (not (rontolisp::%regex-matcher-find m)) (return nil))
      (let ((s (rontolisp::%regex-matcher-start m 0))
            (e (rontolisp::%regex-matcher-end m 0)))
        (if (not (and (= at 0) (= s 0) (= e 0)))
            (progn
              (setq pieces (cons (subseq input at s) pieces) count (+ count 1))
              (setq at e)))))
    (if (and (= at 0) (null pieces))
        (list input)
        (progn
          (setq pieces (cons (subseq input at) pieces))
          (if (= limit 0)
              (loop
                (if (or (null pieces) (> (length (car pieces)) 0)) (return nil))
                (setq pieces (cdr pieces))))
          (reverse pieces)))))

(defun rontolisp::%regex-quote (text)
  "The pattern that matches TEXT literally: TEXT between \\Q and \\E, a \\E
   inside it closed, escaped and opened again."
  (let ((out (make-string-output-stream)) (n (length text)) (i 0))
    (write-string "\\Q" out)
    (loop
      (if (>= i n) (return nil))
      (if (and (char= (char text i) #\\) (< (+ i 1) n)
               (char= (char text (+ i 1)) #\E))
          (progn
            (write-string "\\E\\\\E\\Q" out)
            (setq i (+ i 2)))
          (progn
            (write-char (char text i) out)
            (setq i (+ i 1)))))
    (write-string "\\E" out)
    (get-output-stream-string out)))
;;; ---------------------------------------------------------------------------
;;; Unicode properties: \p{..}, the U flag's classes and the word characters
;;; past ASCII.
;;;
;;; The tables are regex-unicode.lisp (generated from a running JDK's public
;;; Character API by RegexUnicodeTablesTest). Nothing here is reached from the
;;; parser or the matchers by name: a pattern gets these as PROVIDERS, the
;;; functions %regex-unicode-provider and %regex-category-provider answer, so a
;;; program none of whose patterns can name a property carries no table.
;;;
;;; The names are those the Pattern documentation lists (general categories,
;;; binary properties, scripts, blocks, the POSIX and the java.lang.Character
;;; classes); how a name is looked up (which prefix, which spelling, in which
;;; order) was measured against a JDK.

(defun rontolisp::%regex-uni-numbers (text count)
  "The COUNT numbers of TEXT: base-32 digits, the most significant first and
   each but the last offset by 32; a digit d is the character 48 + d, skipping
   the backslash."
  (let ((out (make-array count :initial-element 0)) (value 0) (k 0))
    (dotimes (i (length text) out)
      (let* ((c (char-code (char text i))) (d (if (> c 92) (- c 49) (- c 48))))
        (if (>= d 32)
            (setq value (+ (* value 32) (- d 32)))
            (progn
              (setf (svref out k) (+ (* value 32) d))
              (setq k (+ k 1) value 0)))))))

(defun rontolisp::%regex-uni-decode (text count numbers)
  "The COUNT runs of TEXT as a simple vector of start and value pairs: a run is
   its distance from the start of the one before, then its value, which is an
   index into NUMBERS where that is given."
  (let ((runs (rontolisp::%regex-uni-numbers text (* 2 count))) (start 0))
    (dotimes (k count runs)
      (setq start (+ start (svref runs (* 2 k))))
      (setf (svref runs (* 2 k)) start)
      (if numbers
          (setf (svref runs (+ (* 2 k) 1))
                (svref numbers (svref runs (+ (* 2 k) 1))))))))

(defun rontolisp::%regex-uni-run-value (runs code)
  "The value of the run CODE is in."
  (let ((low 0) (high (- (truncate (length runs) 2) 1)))
    (loop
      (if (>= low high) (return (svref runs (+ (* 2 low) 1))))
      (let ((middle (truncate (+ low high 1) 2)))
        (if (<= (svref runs (* 2 middle)) code)
            (setq low middle)
            (setq high (- middle 1)))))))

(defun rontolisp::%regex-uni-combo (code)
  "The general category of CODE (Character.getType's number) in bits 0-4 and
   the Character predicates from bit 5."
  (rontolisp::%regex-uni-run-value (rontolisp::%regex-uni-props) code))

(defun rontolisp::%regex-uni-category (code)
  (logand (rontolisp::%regex-uni-combo code) 31))

(defun rontolisp::%regex-uni-category-set (mask)
  "The set of the general categories whose bits MASK holds."
  (list
   :fn (lambda (x) (logbitp (logand (rontolisp::%regex-uni-combo x) 31) mask))))

(defun rontolisp::%regex-uni-bits-set (mask)
  "The set of the code points one of whose predicates MASK names holds."
  (list :fn (lambda (x)
              (/= 0 (logand (ash (rontolisp::%regex-uni-combo x) -5) mask)))))

(defun rontolisp::%regex-uni-entry (table name)
  "The value after NAME in TABLE, a string of NAME:VALUE entries each ending in
   a comma, or nil."
  (let* ((key (concatenate 'string "," name ":")) (at (search key table)))
    (if at
        (let* ((start (+ at (length key)))
               (end
                (rontolisp::%regex-index-of table #\, start (length table))))
          (subseq table start end))
        nil)))

(defun rontolisp::%regex-uni-number (text)
  (let ((value 0))
    (dotimes (k (length text) value)
      (setq value (+ (* value 10) (- (char-code (char text k)) 48))))))

(defun rontolisp::%regex-uni-category-mask (name)
  "The mask of the general category NAME, nil for any other name."
  (let ((entry
         (rontolisp::%regex-uni-entry ",Cn:1,Lu:2,Ll:4,Lt:8,Lm:16,Lo:32,Mn:64,Me:128,Mc:256,Nd:512,Nl:1024,No:2048,Zs:4096,Zl:8192,Zp:16384,Cc:32768,Cf:65536,Co:262144,Cs:524288,Pd:1048576,Ps:2097152,Pe:4194304,Pc:8388608,Po:16777216,Sm:33554432,Sc:67108864,Sk:134217728,So:268435456,Pi:536870912,Pf:1073741824,L:62,M:448,N:3584,Z:28672,C:884737,P:1643118592,S:503316480,LC:14,LD:574,"
                                      name)))
    (if entry (rontolisp::%regex-uni-number entry) nil)))

(defun rontolisp::%regex-uni-white-space-p (x)
  (or (and (>= x 9) (<= x 13)) (= x 133)
      (logbitp (rontolisp::%regex-uni-category x) 28672)))

(defun rontolisp::%regex-uni-hex-digit-p (x)
  (or (logbitp 10 (rontolisp::%regex-uni-combo x)) (and (>= x 97) (<= x 102))
      (and (>= x 65) (<= x 70)) (and (>= x 65296) (<= x 65305))
      (and (>= x 65313) (<= x 65318)) (and (>= x 65345) (<= x 65350))))

(defun rontolisp::%regex-uni-word-p (x)
  "A word character under the U flag: alphabetic, a mark, a digit, a connector
   punctuation or a join control."
  (let ((combo (rontolisp::%regex-uni-combo x)))
    (or (/= 0 (logand combo 1056)) (logbitp (logand combo 31) 8389056)
        (= x 8204) (= x 8205))))

(defun rontolisp::%regex-uni-binary (name ci)
  "The set of the binary property NAME (upper case), nil for any other name.
   CI widens a case to every case."
  (let ((entry
         (rontolisp::%regex-uni-entry ",ALPHABETIC:1,ALPHA:1,LETTER:64,IDEOGRAPHIC:16,LOWERCASE:2,LOWER:2,UPPERCASE:4,UPPER:4,TITLECASE:8,DIGIT:32,ALNUM:33,EMOJI:262144,EMOJI_PRESENTATION:524288,EMOJI_MODIFIER:1048576,EMOJI_MODIFIER_BASE:2097152,EMOJI_COMPONENT:4194304,EXTENDED_PICTOGRAPHIC:8388608,"
                                      name)))
    (cond (entry
           (let ((mask (rontolisp::%regex-uni-number entry)))
             (rontolisp::%regex-uni-bits-set
              (if (and ci (or (= mask 2) (= mask 4) (= mask 8))) 14 mask))))
          ((or (string= name "WHITE_SPACE") (string= name "WHITESPACE")
               (string= name "SPACE"))
           (list :fn #'rontolisp::%regex-uni-white-space-p))
          ((or (string= name "CONTROL") (string= name "CNTRL"))
           (rontolisp::%regex-uni-category-set 32768))
          ((or (string= name "PUNCTUATION") (string= name "PUNCT"))
           (rontolisp::%regex-uni-category-set 1643118592))
          ((or (string= name "HEX_DIGIT") (string= name "HEXDIGIT")
               (string= name "XDIGIT"))
           (list :fn #'rontolisp::%regex-uni-hex-digit-p))
          ((string= name "ASSIGNED")
           (list :fn (lambda (x) (/= 0 (rontolisp::%regex-uni-category x)))))
          ((or (string= name "NONCHARACTER_CODE_POINT")
               (string= name "NONCHARACTERCODEPOINT"))
           (list :fn (lambda (x)
                       (or (= (logand x 65534) 65534)
                           (and (>= x 64976) (<= x 65007))))))
          ((string= name "BLANK")
           (list :fn (lambda (x)
                       (or (= x 9) (= (rontolisp::%regex-uni-category x) 12)))))
          ((string= name "GRAPH")
           (list :fn (lambda (x)
                       (not
                        (or (rontolisp::%regex-uni-white-space-p x)
                            (logbitp (rontolisp::%regex-uni-category x)
                                     557057))))))
          ((string= name "PRINT")
           (list :fn (lambda (x)
                       (let ((category (rontolisp::%regex-uni-category x)))
                         (and (/= category 15)
                              (or (= x 9) (= category 12)
                                  (not
                                   (or (rontolisp::%regex-uni-white-space-p x)
                                       (logbitp category 557057)))))))))
          ((string= name "WORD") (list :fn #'rontolisp::%regex-uni-word-p))
          ((or (string= name "JOIN_CONTROL") (string= name "JOINCONTROL"))
           (list :fn (lambda (x) (or (= x 8204) (= x 8205)))))
          (t nil))))

(defun rontolisp::%regex-uni-plain (name ci)
  "The set of a name written with no prefix -- a general category, L1, all, a
   POSIX class over ASCII, a java.lang.Character class -- nil for any other."
  (let ((mask (rontolisp::%regex-uni-category-mask name)))
    (cond
     (mask (rontolisp::%regex-uni-category-set
            (if (and ci (or (= mask 2) (= mask 4) (= mask 8))) 14 mask)))
     ((string= name "L1") (list :r 0 255))
     ((string= name "all") (list :r 0 1114111))
     ((string= name "ASCII") (list :r 0 127))
     ((string= name "Alnum")
      (list :or (list (list :r 48 57) (list :r 65 90) (list :r 97 122))))
     ((string= name "Alpha") (list :or (list (list :r 65 90) (list :r 97 122))))
     ((string= name "Blank") (list :or (list (list :c 32) (list :c 9))))
     ((string= name "Cntrl") (list :or (list (list :r 0 31) (list :c 127))))
     ((string= name "Digit") (list :r 48 57))
     ((string= name "Graph") (list :r 33 126))
     ((string= name "Lower")
      (if ci
          (list :or (list (list :r 65 90) (list :r 97 122)))
          (list :r 97 122)))
     ((string= name "Print") (list :r 32 126))
     ((string= name "Punct")
      (list :or (list (list :r 33 47) (list :r 58 64) (list :r 91 96)
                      (list :r 123 126))))
     ((string= name "Upper")
      (if ci
          (list :or (list (list :r 65 90) (list :r 97 122)))
          (list :r 65 90)))
     ((string= name "XDigit")
      (list :or (list (list :r 48 57) (list :r 65 70) (list :r 97 102))))
     ((string= name "Space") (list :or (list (list :c 32) (list :r 9 13))))
     ((and (> (length name) 4) (string= (subseq name 0 4) "java"))
      (let ((entry
             (rontolisp::%regex-uni-entry ",Alphabetic:1,LowerCase:2,UpperCase:4,TitleCase:8,Ideographic:16,Digit:32,Letter:64,LetterOrDigit:128,Defined:256,Mirrored:512,JavaIdentifierStart:1024,JavaIdentifierPart:2048,UnicodeIdentifierStart:4096,UnicodeIdentifierPart:8192,IdentifierIgnorable:16384,SpaceChar:32768,Whitespace:65536,ISOControl:131072,"
                                          (subseq name 4))))
        (if entry
            (let ((bits (rontolisp::%regex-uni-number entry)))
              (rontolisp::%regex-uni-bits-set
               (if (and ci (or (= bits 2) (= bits 4) (= bits 8))) 14 bits)))
            nil)))
     (t nil))))

(defun rontolisp::%regex-uni-name-index (table name)
  "The place of the entry of TABLE one of whose names NAME is, or nil. An entry
   is its names joined by =, the entries by commas; * stands for the first
   name with a space for each underscore, and a name with spaces is taken
   without them too."
  (let ((n (length table)) (start 0) (index 0) (found nil))
    (loop
      (if (or found (>= start n)) (return found))
      (let* ((end (or (rontolisp::%regex-index-of table #\, start n) n))
             (first-end
              (or (rontolisp::%regex-index-of table #\= start end) end))
             (first (subseq table start first-end))
             (at start))
        (loop
          (if (or found (>= at end)) (return nil))
          (let* ((stop (or (rontolisp::%regex-index-of table #\= at end) end))
                 (spelled (subseq table at stop))
                 (full
                  (if (string= spelled "*")
                      (rontolisp::%regex-uni-respell first t)
                      spelled)))
            (if (or (string= full name)
                    (string= (rontolisp::%regex-uni-respell full nil) name))
                (setq found index))
            (setq at (+ stop 1))))
        (setq start (+ end 1) index (+ index 1))))))

(defun rontolisp::%regex-uni-respell (name spaced)
  "NAME with a space for each underscore (SPACED), or without its spaces."
  (let ((out (make-string-output-stream)))
    (dotimes (k (length name))
      (let ((c (char name k)))
        (cond ((and spaced (char= c #\_)) (write-char #\Space out))
              ((and (not spaced) (char= c #\Space)) nil)
              (t (write-char c out)))))
    (get-output-stream-string out)))

(defun rontolisp::%regex-uni-script-set (name)
  "The set of the script NAME (its constant's name or its ISO 15924 alias, in
   any case), nil for no script."
  (let ((index
         (rontolisp::%regex-uni-name-index (rontolisp::%regex-uni-script-names)
                                           (string-upcase name))))
    (if index
        (list :fn (lambda (x)
                    (= (rontolisp::%regex-uni-run-value
                        (rontolisp::%regex-uni-scripts) x) index)))
        nil)))

(defun rontolisp::%regex-uni-block-set (name)
  "The set of the block NAME, in any case, nil for no block."
  (let ((index
         (rontolisp::%regex-uni-name-index (rontolisp::%regex-uni-block-names)
                                           (string-upcase name))))
    (if index
        (list :fn (lambda (x)
                    (= (rontolisp::%regex-uni-run-value
                        (rontolisp::%regex-uni-blocks) x) (+ index 1))))
        nil)))

(defun rontolisp::%regex-uni-posix (name)
  "Under the U flag, the binary property a POSIX class name stands for, nil for
   any other name."
  (let ((upper (string-upcase name)))
    (if (rontolisp::%regex-uni-entry ",ALPHA:,LOWER:,UPPER:,SPACE:,PUNCT:,XDIGIT:,ALNUM:,CNTRL:,DIGIT:,BLANK:,GRAPH:,PRINT:,"
                                     upper)
        upper
        nil)))

(defun rontolisp::%regex-uni-property (name ci unicode)
  "The set of the property NAME as \\p{NAME} reads it, or the description of
   the refusal."
  (let ((equals (rontolisp::%regex-index-of name #\= 0 (length name))))
    (cond ((= (length name) 0) "Empty character family")
          (equals
           (let* ((key (string-downcase (subseq name 0 equals)))
                  (value (subseq name (+ equals 1)))
                  (members
                   (cond ((or (string= key "sc") (string= key "script"))
                          (rontolisp::%regex-uni-script-set value))
                         ((or (string= key "blk") (string= key "block"))
                          (rontolisp::%regex-uni-block-set value))
                         ((or (string= key "gc")
                              (string= key "general_category"))
                          (rontolisp::%regex-uni-plain value ci))
                         (t nil))))
             (or members
                 (concatenate 'string "Unknown Unicode property {name=<"
                              (subseq name 0 equals) ">, value=<" value ">}"))))
          (t (let ((members
                    (cond ((and (> (length name) 2)
                                (string= (subseq name 0 2) "In"))
                           (rontolisp::%regex-uni-block-set (subseq name 2)))
                          ((and (> (length name) 2)
                                (string= (subseq name 0 2) "Is"))
                           (let ((rest (subseq name 2)))
                             (or (rontolisp::%regex-uni-binary
                                  (string-upcase rest) ci)
                                 (rontolisp::%regex-uni-plain rest ci)
                                 (rontolisp::%regex-uni-script-set rest))))
                          (t (let ((posix
                                    (and unicode
                                         (rontolisp::%regex-uni-posix name))))
                               (if posix
                                   (rontolisp::%regex-uni-binary posix ci)
                                   (rontolisp::%regex-uni-plain name ci)))))))
               (or members
                   (concatenate 'string "Unknown character property name {" name
                                "}")))))))

(defun rontolisp::%regex-unicode-provider ()
  "The provider of the Unicode properties: a function of a name and the
   flags. For a string it answers the set of \\p{NAME}, or the description of
   a refusal; for the character d, w or s the set that class stands for under
   the U flag."
  (lambda (name flags)
    (cond ((stringp name)
           (rontolisp::%regex-uni-property name (/= 0 (logand flags 2))
                                           (/= 0 (logand flags 256))))
          ((char= name #\d) (rontolisp::%regex-uni-bits-set 32))
          ((char= name #\s) (list :fn #'rontolisp::%regex-uni-white-space-p))
          (t (list :fn #'rontolisp::%regex-uni-word-p)))))

(defun rontolisp::%regex-category-word-p (in k)
  "Whether the character K of IN is a word character for \\b without the U
   flag: a letter, a digit or an underscore of ASCII, or a non-spacing mark
   whose base character, past the marks before it, is a letter or a digit of
   any script."
  (let ((c (char-code (char in k))))
    (cond ((< c 128) (rontolisp::%regex-word-code-p c))
          ((= (rontolisp::%regex-uni-category c) 6)
           (let ((j (- k 1)))
             (loop
               (if (or (< j 0)
                       (/=
                        (rontolisp::%regex-uni-category (char-code (char in j)))
                        6))
                   (return nil))
               (setq j (- j 1)))
             (and (>= j 0)
                  (logbitp 12
                   (rontolisp::%regex-uni-combo (char-code (char in j)))))))
          (t nil))))

(defun rontolisp::%regex-category-provider ()
  "The provider of \\b's word characters past ASCII: a function of whether
   the U flag is set that answers the test of the character K of an input.
   Under the U flag that is \\w's."
  (lambda (unicode)
    (if unicode
        (lambda (in k) (rontolisp::%regex-uni-word-p (char-code (char in k))))
        #'rontolisp::%regex-category-word-p)))
;;; ---------------------------------------------------------------------------
;;; Grapheme clusters: \X and \b{g}.
;;;
;;; The boundary rules are those of Unicode Standard Annex #29 (extended
;;; grapheme clusters, GB1 to GB999, with GB9c's Indic conjuncts), written
;;; from the annex over the table regex-unicode.lisp carries: a code point's
;;; break type times four plus its conjunct class. The types: 0 other, 1 CR,
;;; 2 LF, 3 control, 4 extend (spacing marks with them), 5 ZWJ, 6 regional
;;; indicator, 7 prepend, 8 L, 9 V, 10 T, 11 LV, 12 LVT, 13 extended
;;; pictographic. The conjunct classes: 1 consonant, 2 extend, 3 linker.

(defun rontolisp::%regex-gr-class (code)
  (rontolisp::%regex-uni-run-value (rontolisp::%regex-uni-graphemes) code))

(defun rontolisp::%regex-gr-next (in i)
  "The end of the grapheme cluster of IN that starts at I."
  (let* ((len (length in))
         (class (rontolisp::%regex-gr-class (char-code (char in i))))
         (prev (truncate class 4))
         ;; the regional indicators in a row up to the character before J
         (indicators (if (= prev 6) 1 0))
         ;; GB11: 1 after a pictograph and extends, 2 after their ZWJ
         (pictograph (if (= prev 13) 1 0))
         ;; GB9c: 1 after a consonant and extends, 2 once a linker stood there
         (conjunct (if (= (mod class 4) 1) 1 0))
         (j (+ i 1)))
    (loop
      (if (>= j len) (return j))
      (let* ((c (rontolisp::%regex-gr-class (char-code (char in j))))
             (type (truncate c 4))
             (linking (mod c 4)))
        (if (not
             (or (and (= prev 1) (= type 2))
                 (and (not (and (>= prev 1) (<= prev 3)))
                      (not (and (>= type 1) (<= type 3)))
                      (or
                       (and (= prev 8)
                            (or (= type 8) (= type 9) (= type 11) (= type 12)))
                       (and (or (= prev 11) (= prev 9))
                            (or (= type 9) (= type 10)))
                       (and (or (= prev 12) (= prev 10)) (= type 10)) (= type 4)
                       (= type 5) (= prev 7) (and (= conjunct 2) (= linking 1))
                       (and (= pictograph 2) (= type 13))
                       (and (= prev 6) (= type 6) (= (mod indicators 2) 1))))))
            (return j))
        (cond ((= linking 1) (setq conjunct 1))
              ((= linking 3) (if (>= conjunct 1) (setq conjunct 2)))
              ((= linking 2) nil)
              (t (setq conjunct 0)))
        (cond ((= type 13) (setq pictograph 1))
              ((and (= pictograph 1) (= type 4)) nil)
              ((and (= pictograph 1) (= type 5)) (setq pictograph 2))
              (t (setq pictograph 0)))
        (setq indicators (if (= type 6) (+ indicators 1) 0))
        (setq prev type j (+ j 1))))))

(defun rontolisp::%regex-grapheme-provider ()
  "The provider of the grapheme clusters: a function of an input and a
   position that answers where the cluster starting there ends."
  #'rontolisp::%regex-gr-next)
