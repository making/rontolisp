;;;; morph.lisp -- the code of a (slide :morph t ...) changing from the previous slide's.
;;;;
;;;; The pairing is decided here, so every backend emits the same one: the two code blocks
;;;; are cut into tokens, the highlighter's segments split at blanks, and a token of the
;;;; new block that continues one of the old block names it by index (data-m). The player
;;;; only plays it: paired tokens move from their old place to their new one, the others
;;;; fade (*PLAYER-SCRIPT*).

(in-package :lispresen)

(defparameter *morph-cells*
  1000000
  "The most cells a longest-common-subsequence table of one morph may take: the tokens
of the two sides of a gap without anchors, multiplied.")

(defun code-text (b) (format nil "~{~a~^~%~}" (code-block-lines b)))

(defun code-tokens (b)
  "The tokens of the code block B as (START END CLASS) in its text: each blank-free run
of a highlighter segment, so no token holds a blank or a line end. A block in a language
without rules is one segment."
  (let* ((text (code-text b))
         (segments (highlight-segments text (code-block-lang b)))
         (tokens nil))
    (loop for (start end class) in segments
          do
            (let ((i start))
              (loop
                (loop while
                        (and (< i end) (blank-code-p (char-code (char text i))))
                      do (incf i))
                (when (>= i end) (return))
                (let ((j i))
                  (loop while
                          (and (< j end)
                               (not (blank-code-p (char-code (char text j)))))
                        do (incf j))
                  (push (list i j class) tokens)
                  (setf i j)))))
    (nreverse tokens)))

(defun token-texts (b)
  (let ((text (code-text b)))
    (map 'vector (lambda (tok) (subseq text (first tok) (second tok)))
         (code-tokens b))))

(defun pair-tokens (a b)
  "For the token texts A and B, a vector holding, for each token of B, the index of the
token of A it continues, or nil; nil instead when a part the two do not share is too long
to compare. The pairs keep the order of both (PAIR-RANGE): first a bracket is compared
together with the token inside it next to it, so ( stays with the defun it opens, then
the gaps between those pairs by text alone. Last, each text left exactly once among the
unpaired tokens of both is paired too, a token that moved."
  (let* ((n (length a))
         (m (length b))
         (pairs (make-array m :initial-element nil))
         (taken (make-array n :initial-element nil)))
    (unless (pair-range (bracket-keys a) (bracket-keys b) 0 n 0 m pairs)
      (return-from pair-tokens nil))
    (let ((i0 -1) (j0 -1))
      (dotimes (j (1+ m))
        (let ((i (if (= j m) n (aref pairs j))))
          (when i
            (when (and (> (- i i0) 1) (> (- j j0) 1)
                       (not (pair-range a b (1+ i0) i (1+ j0) j pairs)))
              (return-from pair-tokens nil))
            (setf i0 i j0 j)))))
    (loop for k across pairs when k do (setf (aref taken k) t))
    (flet ((once (texts free text)
             (let ((found nil))
               (dotimes (k (length texts) found)
                 (when (and (funcall free k) (string= (aref texts k) text))
                   (when found (return nil))
                   (setf found k))))))
      (dotimes (j m)
        (unless (aref pairs j)
          (let ((i (once a (lambda (k) (not (aref taken k))) (aref b j))))
            (when (and i (once b (lambda (k) (not (aref pairs k))) (aref b j)))
              (setf (aref pairs j) i (aref taken i) t))))))
    pairs))

(defun bracket-keys (texts)
  "TEXTS with each opening bracket joined to the text after it and each closing bracket
to the text before it."
  (let ((n (length texts)))
    (coerce (loop for k from 0 below n
                  for text = (aref texts k)
                  collect
                    (cond ((and (member text '("(" "[" "{") :test #'string=)
                                (< (1+ k) n))
                           (concatenate 'string text " " (aref texts (1+ k))))
                          ((and (member text '(")" "]" "}") :test #'string=)
                                (> k 0))
                           (concatenate 'string (aref texts (1- k)) " " text))
                          (t text))) 'vector)))

(defun pair-range (a b alo ahi blo bhi pairs)
  "Pairs tokens of A from ALO below AHI with tokens of B from BLO below BHI in PAIRS, in
the order of both, the way a patience diff does: the shared start and end, then the texts
found once in each range as anchors (the longest run of them in the same order), each gap
between anchors paired again, and a gap without anchors by a longest common subsequence.
Anchoring on rare texts keeps a common token such as ( with its neighbours. Nil when a
gap is too long to compare."
  (loop while (and (< alo ahi) (< blo bhi) (string= (aref a alo) (aref b blo)))
        do (setf (aref pairs blo) alo) (incf alo) (incf blo))
  (loop while
          (and (< alo ahi) (< blo bhi)
               (string= (aref a (1- ahi)) (aref b (1- bhi))))
        do (decf ahi) (decf bhi) (setf (aref pairs bhi) ahi))
  (if (or (= alo ahi) (= blo bhi))
      t
      (let ((anchors (unique-anchors a b alo ahi blo bhi)))
        (if anchors
            (and (loop for (i . j) in anchors
                       always (pair-range a b alo i blo j pairs)
                       do (setf (aref pairs j) i alo (1+ i) blo (1+ j)))
                 (pair-range a b alo ahi blo bhi pairs))
            (pair-lcs a b alo ahi blo bhi pairs)))))

(defun unique-anchors (a b alo ahi blo bhi)
  "The (I . J) of the texts found exactly once in A from ALO below AHI and once in B from
BLO below BHI, the longest run of them whose I and J both increase, in that order."
  (let ((counts (make-hash-table :test #'equal)) (found nil))
    ;; Each text's count in A, count in B and index in A.
    (loop for i from alo below ahi
          do
            (let ((c
                   (or (gethash (aref a i) counts)
                       (setf (gethash (aref a i) counts) (list 0 0 i)))))
              (incf (first c))))
    (loop for j from blo below bhi
          do (let ((c (gethash (aref b j) counts))) (when c (incf (second c)))))
    (loop for j from blo below bhi
          do
            (let ((c (gethash (aref b j) counts)))
              (when (and c (= (first c) 1) (= (second c) 1))
                (push (cons (third c) j) found))))
    (let* ((v (coerce (nreverse found) 'vector))
           (k (length v))
           (len (make-array k :initial-element 1))
           (prev (make-array k :initial-element nil))
           (best nil))
      ;; The longest run whose A indices increase, as B's order already does.
      (dotimes (x k)
        (dotimes (y x)
          (when (and (< (car (aref v y)) (car (aref v x)))
                     (> (1+ (aref len y)) (aref len x)))
            (setf (aref len x) (1+ (aref len y)) (aref prev x) y)))
        (when (or (null best) (> (aref len x) (aref len best))) (setf best x)))
      (let ((run nil))
        (loop while best
              do (push (aref v best) run) (setf best (aref prev best)))
        run))))

(defun pair-lcs (a b alo ahi blo bhi pairs)
  "Pairs a longest common subsequence of A from ALO below AHI and B from BLO below BHI;
nil when the table would exceed *MORPH-CELLS*."
  (let* ((n (- ahi alo)) (m (- bhi blo)) (w (1+ m)))
    (when (> (* n m) *morph-cells*) (return-from pair-lcs nil))
    ;; TABLE at I, J is the length of a longest common subsequence of the ranges from
    ;; their tokens I and J on.
    (let ((table (make-array (* (1+ n) w) :initial-element 0)))
      (flet ((at (i j) (aref table (+ (* i w) j)))
             (same (i j) (string= (aref a (+ alo i)) (aref b (+ blo j)))))
        (loop for i from (1- n) downto 0
              do
                (loop for j from (1- m) downto 0
                      do
                        (setf (aref table (+ (* i w) j))
                              (if (same i j)
                                  (1+ (at (1+ i) (1+ j)))
                                  (max (at (1+ i) j) (at i (1+ j)))))))
        (let ((i 0) (j 0))
          (loop while (and (< i n) (< j m))
                do
                  (cond ((same i j)
                         (setf (aref pairs (+ blo j)) (+ alo i))
                         (incf i)
                         (incf j))
                        ((>= (at (1+ i) j) (at i (1+ j))) (incf i))
                        (t (incf j)))))
        t))))

(defun first-code-block (slide)
  (and slide (find-if #'code-block-p (region-blocks (slide-blocks slide)))))

(defun morph-code (slide previous)
  "Pairs the tokens of the first code block of SLIDE, a (slide :morph t ...), with those
of the first code block of PREVIOUS, the slide before it."
  (let ((to (first-code-block slide)) (from (first-code-block previous)))
    (cond ((null to)
           (report
            :warning
            ":morph changes the slide's first code block; this slide has none"))
          ((null from)
           (report
            :warning
            ":morph changes the code of the slide before; that slide has none"))
          ((or (console-p to) (console-p from))
           (report :warning ":morph changes code, not a console transcript"))
          (t (let ((pairs (pair-tokens (token-texts from) (token-texts to))))
               (if (null pairs)
                   (report :warning
                           ":morph: the two code blocks differ in too many tokens to pair")
                   (progn
                     (unless (code-block-morph from)
                       (setf (code-block-morph from) :source))
                     (setf (code-block-morph to)
                           (cons :target (coerce pairs 'list))))))))))

(defun write-morph-text (b out)
  "The text of the code block B with each token in a <span class=\"tk\">, its highlight
class added, and the index of the token of the previous slide's block it continues in
data-m. A token never spans a line end, so the line spans of :steps can wrap them."
  (let ((text (code-text b))
        (i 0)
        (pairs (and (consp (code-block-morph b)) (cdr (code-block-morph b)))))
    (loop for (start end class) in (code-tokens b)
          for pair = (pop pairs)
          do (write-escaped (subseq text i start) out)
            (format out "<span class=\"tk~@[ hl-~a~]\"~@[ data-m=\"~d\"~]>"
                    class pair) (write-escaped (subseq text start end) out)
            (write-string "</span>" out) (setf i end))
    (write-escaped (subseq text i) out)))
