;;; Unicode normalization: the four forms of Unicode Standard Annex #15, as the
;;; front ends that have them share them (the Java subset's java.text.Normalizer).
;;;
;;; Written here from the annex and the Unicode Standard it rests on: a form is the
;;; decomposition of every code point, the canonical ordering of the marks between
;;; two starters and, for the two forms that compose, the canonical composition
;;; (section 3.11, "Normalization Forms"); a Hangul syllable is taken apart and put
;;; together by arithmetic (section 3.12, "Conjoining Jamo Behavior"). Nothing is
;;; copied or translated from a JDK or from ICU (.kb/java-frontend-provenance.md).
;;; The tables are unicode-normalization-tables.lisp, a running JDK's answers
;;; (.kb/unicode-normalization.md). Spliced by eval/UnicodeNormalizationLibrary into
;;; a program that names one of these functions.

(defvar rontolisp::%unorm-tables-cache nil)

(defun rontolisp::%unorm-next (cursor)
  "The next number of the text CURSOR, (text . index), stands in: base-32
   digits, the most significant first and each but the last offset by 32; a
   digit d is the character 48 + d, skipping the backslash."
  (let ((text (car cursor)) (i (cdr cursor)) (value 0))
    (loop
      (let* ((c (char-code (char text i))) (d (if (> c 92) (- c 49) (- c 48))))
        (setq i (+ i 1))
        (if (>= d 32)
            (setq value (+ (* value 32) (- d 32)))
            (progn
              (rplacd cursor i)
              (return (+ (* value 32) d))))))))

(defun rontolisp::%unorm-reversed (list)
  "LIST the other way round: the sequence functions' reverse brings what reads
   any sequence into a program."
  (let ((out nil)) (dolist (item list out) (setq out (cons item out)))))

(defun rontolisp::%unorm-read ()
  "The tables: a cons of what a code point is -- the class of a mark in the
   order of the classes, 0 for a starter that composes with a starter before
   it, or a cons of its canonical and its compatibility mapping -- and of the
   composites by their first code point, each (second . composite). A
   canonical mapping is a list of code points, or (first . second) for a
   composite that a text of code points with no entry and of such composites
   keeps; a compatibility mapping is the whole decomposition. The two texts
   are numbers (%unorm-next): the classes as runs -- the distance from the end
   of the run before, the length, the class --, the mappings by code point,
   each headed by eight times the distance from the code point before plus a
   kind: 0 a canonical mapping to one code point, 1 a composite, 7 a composite
   whose decomposition starts with a code point of class 0 in the classes, 2 a
   canonical mapping of a stated length, 3, 4 and 5 a compatibility mapping to
   one, to two and to a stated number of code points, 6 a run of a stated
   length of compatibility mappings, each of the next code point to one more
   than the mapping before. A mapping's first code point is its distance from
   the first of the mapping before of its sort, canonical or compatibility,
   twice that or, below it, one less than twice the distance down."
  (let ((properties (make-hash-table)) (pairs (make-hash-table)))
    (let* ((text (rontolisp::%unorm-classes)) (cursor (cons text 0)) (code 0))
      (loop
        (if (>= (cdr cursor) (length text)) (return nil))
        (setq code (+ code (rontolisp::%unorm-next cursor)))
        (let* ((count (rontolisp::%unorm-next cursor))
               (class (rontolisp::%unorm-next cursor)))
          (dotimes (k count)
            (setf (gethash code properties) class)
            (setq code (+ code 1))))))
    (let* ((text (rontolisp::%unorm-mappings))
           (cursor (cons text 0))
           (code 0)
           (canonical 0)
           (compatibility 0))
      (loop
        (if (>= (cdr cursor) (length text)) (return nil))
        (let* ((head (rontolisp::%unorm-next cursor)) (kind (logand head 7)))
          (setq code (+ code (ash head -3)))
          (if (= kind 6)
              ;; a run: each code point one after the one before it, as is
              ;; what it maps to
              (dotimes (k (rontolisp::%unorm-next cursor))
                (if (> k 0) (setq code (+ code 1)))
                (setq compatibility (+ compatibility 1))
                (setf (gethash code properties)
                      (cons nil (list compatibility))))
              (let* ((count
                      (cond ((or (= kind 0) (= kind 3)) 1)
                            ((or (= kind 2) (= kind 5))
                             (rontolisp::%unorm-next cursor))
                            (t 2)))
                     (compat (and (> kind 2) (< kind 6)))
                     (step (rontolisp::%unorm-next cursor))
                     (first
                      (+ (if compat compatibility canonical)
                         (if (logbitp 0 step)
                             (- (ash (+ step 1) -1))
                             (ash step -1))))
                     (rest nil))
                (dotimes (k (- count 1))
                  (setq rest (cons (rontolisp::%unorm-next cursor) rest)))
                (setq rest (rontolisp::%unorm-reversed rest))
                (cond (compat
                       (setq compatibility first)
                       (let ((entry (gethash code properties)))
                         (if entry
                             (rplacd entry (cons first rest))
                             (setf (gethash code properties)
                                   (cons nil (cons first rest))))))
                      (t
                       (setq canonical first)
                       (if (or (= kind 1) (= kind 7))
                           (setf (gethash first pairs)
                                 (cons (cons (car rest) code)
                                       (gethash first pairs))))
                       (setf (gethash code properties)
                             (cons (cons first (if (= kind 1) (car rest) rest))
                                   nil)))))))))
    (cons properties pairs)))

(defun rontolisp::%unorm-tables ()
  "The tables of %unorm-read, read by the first text that needs them."
  (or rontolisp::%unorm-tables-cache
      (setq rontolisp::%unorm-tables-cache (rontolisp::%unorm-read))))

(defun rontolisp::%unorm-quiet-p (code compat compose properties)
  "Whether a text of such code points alone is in the form already: one with
   no entry, a Hangul syllable or a composite where the form composes, one
   whose only mapping is a compatibility mapping where the form is canonical."
  (if (and (>= code 44032) (<= code 55203))
      compose
      (let ((entry (gethash code properties)))
        (cond ((not entry) t)
              ((integerp entry) nil)
              ((and compat (cdr entry)) nil)
              ((not (car entry)) t)
              (t (and compose (integerp (cdr (car entry)))))))))

(defun rontolisp::%unorm-pair (first second pairs)
  "The composite of FIRST and SECOND, or nil: a Hangul syllable of a leading
   consonant and a vowel, or of such a syllable and a trailing consonant, by
   arithmetic; any other from the table."
  (cond ((and (>= first 4352) (< first 4371) (>= second 4449) (< second 4470))
         (+ 44032 (* 28 (+ (* 21 (- first 4352)) (- second 4449)))))
        ((and (>= first 44032) (<= first 55203) (> second 4519) (< second 4547)
              (= 0 (rem (- first 44032) 28)))
         (+ first (- second 4519)))
        (t (cdr (assoc second (gethash first pairs))))))

(defun rontolisp::%unorm-settle (state next joins)
  "Writes the starter STATE holds and the marks after it, which are all there:
   NEXT is the starter that follows, nil at the end, and JOINS whether it is
   one that composes with a starter before it. Where the form composes, a mark
   joins the starter when they have a composite and no mark kept before it is
   of its class or a higher one, and NEXT joins it when no mark was kept at
   all. STATE is the starter, the marks from the last one back as
   (code . class), the characters written and the composites, nil for a form
   that does not compose."
  (let ((starter (svref state 0))
        (pairs (svref state 3))
        (out (svref state 2))
        (kept nil)
        (last 0))
    (if (svref state 1)
        (progn
          (dolist (mark (rontolisp::%unorm-reversed (svref state 1)))
            (let ((composite
                   (if (and pairs starter (< last (cdr mark)))
                       (rontolisp::%unorm-pair starter (car mark) pairs)
                       nil)))
              (if composite
                  (setq starter composite)
                  (setq kept (cons (car mark) kept) last (cdr mark)))))
          (setf (svref state 1) nil)))
    (let ((composite
           (if (and joins pairs starter (not kept))
               (rontolisp::%unorm-pair starter next pairs)
               nil)))
      (cond (composite (setf (svref state 0) composite))
            (t
             (if starter (vector-push-extend (code-char starter) out))
             (if kept
                 (dolist (code (rontolisp::%unorm-reversed kept))
                   (vector-push-extend (code-char code) out)))
             (setf (svref state 0) next))))))

(defun rontolisp::%unorm-feed (code class state)
  "Takes the next code point of a decomposition: CLASS is the combining class
   of a mark, 0 for a starter that composes with a starter before it, nil for
   any other starter. A mark goes behind the marks of its class and of the
   lower ones (the canonical ordering), a starter settles what came before it."
  (if (and class (> class 0))
      (let ((marks (svref state 1)) (mark (cons code class)))
        (if (or (not marks) (<= (cdr (car marks)) class))
            (setf (svref state 1) (cons mark marks))
            (let ((cell marks))
              (loop
                (if (or (not (cdr cell)) (<= (cdr (car (cdr cell))) class))
                    (return nil))
                (setq cell (cdr cell)))
              (rplacd cell (cons mark (cdr cell))))))
      (rontolisp::%unorm-settle state code class)))

(defun rontolisp::%unorm-decompose (code compat properties state)
  "Feeds STATE the decomposition of CODE: the canonical one, or with COMPAT the
   compatibility one. A Hangul syllable is its two or three jamo by
   arithmetic, and itself where the form composes them again."
  (if (and (>= code 44032) (<= code 55203))
      (if (svref state 3)
          (rontolisp::%unorm-feed code nil state)
          (let* ((at (- code 44032)) (trail (rem at 28)))
            (rontolisp::%unorm-feed (+ 4352 (truncate at 588)) nil state)
            (rontolisp::%unorm-feed (+ 4449 (truncate (rem at 588) 28)) nil
                                    state)
            (if (> trail 0) (rontolisp::%unorm-feed (+ 4519 trail) nil state))))
      (let ((entry (gethash code properties)))
        (cond ((not (consp entry)) (rontolisp::%unorm-feed code entry state))
              ((and compat (cdr entry))
               (dolist (c (cdr entry))
                 (rontolisp::%unorm-decompose c compat properties state)))
              ((not (car entry)) (rontolisp::%unorm-feed code nil state))
              ((integerp (cdr (car entry)))
               (rontolisp::%unorm-decompose (car (car entry)) compat properties
                                            state)
               (rontolisp::%unorm-decompose (cdr (car entry)) compat properties
                                            state))
              (t (dolist (c (car entry))
                   (rontolisp::%unorm-decompose c compat properties state)))))))

(defun rontolisp::%unorm-normalize (text compat compose)
  "TEXT in a normalization form of Unicode Standard Annex #15 -- NFD, with
   COMPOSE NFC, with COMPAT NFKD, with both NFKC --, TEXT itself when it is in
   the form. No table is read for a text below the first code point the form
   changes, and a text of code points the form keeps beside one another is
   only looked up."
  (let ((n (length text))
        (limit (cond (compat 160) (compose 768) (t 192)))
        (i 0))
    (loop
      (if (or (>= i n) (>= (char-code (char text i)) limit)) (return nil))
      (setq i (+ i 1)))
    (if (>= i n)
        text
        (let* ((tables (rontolisp::%unorm-tables)) (properties (car tables)))
          (loop
            (if (or (>= i n)
                    (not
                     (rontolisp::%unorm-quiet-p (char-code (char text i)) compat
                                                compose properties)))
                (return nil))
            (setq i (+ i 1)))
          (if (>= i n)
              text
              (let* ((out
                      (make-array (+ n 8)
                                  :element-type 'character
                                  :adjustable t
                                  :fill-pointer 0))
                     (state (vector nil nil out (if compose (cdr tables) nil))))
                (dotimes (k n)
                  (rontolisp::%unorm-decompose (char-code (char text k)) compat
                                               properties state))
                (rontolisp::%unorm-settle state nil nil)
                (let ((result (%normalize-string (subseq out 0 (length out)))))
                  (if (string= result text) text result))))))))
