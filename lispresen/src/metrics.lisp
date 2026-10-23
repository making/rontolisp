;;;; metrics.lisp -- character widths, break opportunities and greedy line wrapping.
;;;;
;;;; Widths are in milli-em per character and milli-px once scaled to a font size; every
;;;; quantity is an integer so all backends compute the same layout. Proportional widths
;;;; carry a margin and round up, so the estimate errs toward more lines.

(in-package :lispresen)

(defun wide-code-p (code)
  "True for a code point Japanese fonts set full-width."
  (or (<= #x1100 code #x115F) (<= #x2010 code #x203B) ; dashes, quotes, ellipsis, reference mark
      (<= #x2190 code #x21FF)                         ; arrows
      (<= #x2460 code #x24FF)                         ; circled numbers
      (<= #x25A0 code #x26FF)                         ; geometric shapes, symbols
      (<= #x2E80 code #x303E) (<= #x3041 code #x33FF) (<= #x3400 code #x4DBF)
      (<= #x4E00 code #x9FFF) (<= #xA000 code #xA4CF) (<= #xAC00 code #xD7A3)
      (<= #xF900 code #xFAFF) (<= #xFE30 code #xFE4F) (<= #xFF00 code #xFF60)
      (<= #xFFE0 code #xFFE6) (<= #x1F300 code #x1F64F)
      (<= #x1F900 code #x1F9FF) (<= #x20000 code #x3FFFD)
      ;; Emoji the color emoji face sets 1 em, measured in Chrome (DESIGN.md).
      (<= #x2700 code #x27BF)                         ; dingbats
      (<= #x231A code #x231B) (<= #x23E9 code #x23EC) (<= #x23EF code #x23F3) (<= #x23F8 code #x23FA)
      (<= #x2B05 code #x2B07) (<= #x2B1B code #x2B1C) (= code #x2B50) (= code #x2B55)
      (<= #x1F1E6 code #x1F1FF)                       ; regional indicators
      (<= #x1F650 code #x1F6FF)                       ; transport and map, ornaments
      (<= #x1FA00 code #x1FAFF)))

(defun cluster-end (text i)
  "The index after the emoji cluster TEXT starts at I, or I + 1 when the character at I
starts none. A cluster is one glyph: a flag (two regional indicators), a character with
a variation selector, a skin tone modifier or a keycap mark, and the characters a
zero width joiner joins to it."
  (let* ((n (length text)) (j (1+ i)) (code (char-code (char text i))) (special nil))
    (flet ((code-at (k) (and (< k n) (char-code (char text k)))))
      (cond ((<= #x1F1E6 code #x1F1FF)
             (when (and (code-at j) (<= #x1F1E6 (code-at j) #x1F1FF))
               (incf j)
               (setf special t)))
            ((or (> code 127) (find (char text i) "#*0123456789")))
            (t (return-from cluster-end j)))
      (loop
        (let ((c (code-at j)))
          (cond ((and c (or (= c #xFE0F) (= c #x20E3) (<= #x1F3FB c #x1F3FF)
                            (<= #xE0020 c #xE007F)))
                 (incf j)
                 (setf special t))
                ((and (eql c #x200D) (code-at (1+ j)) (> code 127))
                 (incf j 2)
                 (setf special t))
                (t (return)))))
      (if special j (1+ i)))))

(defun parse-widths (s)
  (coerce (mapcar #'parse-integer (split-spaces s)) 'simple-vector))

;;; Milli-em advance of each printable ASCII character (code 32..126) in Hiragino Sans at
;;; font-weight 400 and 700: the wider of Chrome 155 and Safari 26.3, measured on
;;; 2026-10-22. Safari sets Hiragino's Latin 2.7% wider on average (bold 4.4%, single
;;; characters up to 15%), at every size. Hiragino's Latin is the widest of the font
;;; stack, so the table over-estimates the other faces rather than under.
(defparameter *ascii-widths*
  (parse-widths
   "333 320 371 675 668 895 815 202 370 370 356 664 250 373 250 487 673 673 673 673 675 673 673 673 673 673 250 250 520 664 520 584 914 766 721 766 771 655 614 764 792 261 532 728 613 957 785 807 676 807 738 682 664 789 736 1021 747 708 679 363 487 363 549 502 500 592 663 591 663 602 417 653 633 248 276 596 243 954 634 638 664 664 447 553 405 634 561 887 559 574 510 365 389 365 562"))

(defparameter *ascii-bold-widths*
  (parse-widths
   "333 356 431 751 743 963 892 267 425 425 401 699 287 442 287 544 741 741 741 741 741 741 741 741 741 741 287 287 554 699 554 645 954 837 774 817 830 706 665 814 850 317 637 818 663 1006 846 859 736 859 797 738 739 857 819 1082 858 795 726 434 544 434 617 504 500 664 735 648 735 661 509 713 702 292 331 689 291 1051 702 691 737 737 521 622 455 702 661 890 660 667 562 438 446 439 641"))

(defun char-width (code style)
  "Milli-em width of CODE in STYLE (:normal, :bold or :mono). Full-width characters are
exactly 1 em in every face of the stack; proportional ones carry a 2% margin for
sub-pixel rounding and faces other than the measured one. A character outside ASCII and
the full-width ranges takes the width of a wide Latin letter."
  (cond
    ((<= #x23ED code #x23EE) 1020)      ; the two emoji Chrome sets 1003 milli-em
    ((wide-code-p code) 1000)
    (t
      (ceiling (* (cond ((eq style :mono) 602)
                        ((<= 32 code 126)
                         (svref (if (eq style :bold)
                                    *ascii-bold-widths*
                                    *ascii-widths*) (- code 32)))
                        ((eq style :bold) 840)
                        (t 800)) 102) 100))))

(defun px (milli size)
  "MILLI milli-em at SIZE px, in milli-px."
  (* milli size))

(defun milli-px->px (n) (ceiling n 1000))

(defun punctuation-break-p (prev c)
  "True where the browser breaks Japanese text under word-break: keep-all without a
<wbr>: after closing punctuation and before an opening bracket, which keep-all does
not treat as letters."
  (or (find prev "、。，．）」』】〕〉》！？") (find c "（「『【〔〈《")))

(defun no-start-p (c)
  "True for a character a line must not start with."
  (find c "、。，．）」』】〕〉》ー・！？：；ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ)]},.!?:;"))

(defun half-digit-p (text i)
  "True when I is an index of TEXT holding a half-width digit."
  (and (< -1 i (length text)) (char<= #\0 (char text i) #\9)))

(defun inside-number-p (text i)
  "True when I lies inside a digit run joined by - / . : or , (a date, a version, a time,
1,000): between two digits, or next to such a joiner with a digit on each side of it."
  (flet ((joiner-p (j) (find (char text j) "-/.:,")))
    (and (> i 0)
         (or (and (half-digit-p text (1- i)) (half-digit-p text i))
             (and (joiner-p i) (half-digit-p text (1- i))
                  (half-digit-p text (1+ i)))
             (and (joiner-p (1- i)) (half-digit-p text (- i 2))
                  (half-digit-p text i))))))

(defun number-space-p (text styles i)
  "True when the space before I separates a half-width number from the word that
follows it (1 時間, 211 件), which a line keeps together. Another number, a bracket
opening an aside and code are not such a word."
  (and (> i 1)
       (char= (char text (1- i)) #\Space)
       (half-digit-p text (- i 2))
       (not (find (char text i) " ([{（「『【〔〈《"))
       (not (half-digit-p text i))
       (notany (lambda (j) (eq (svref styles j) :mono)) (list (- i 2) (1- i) i))))

;;; --- runs ----------------------------------------------------------------------

;;; Inline code and a marker take this much padding on each side, a hair of room between
;;; their background and the text. The browser slices the box at a line break
;;; (box-decoration-break: slice), so the padding goes on the element's first and last
;;; character wherever the lines break, and the model adds it to their widths.
(defparameter *inline-pad* 125
  "Milli-em of horizontal padding on each side of inline code and a marker.")

;;; A paragraph's text with what wrapping needs. STYLES holds :normal, :bold or :mono
;;; per character, BREAKS is true at I when a line may start at character I, WIDTHS is
;;; milli-em per character, the padding of the elements it starts or ends included, and
;;; UNITS lists (START END W WSP) for each stretch between break opportunities, W
;;; excluding and WSP being its trailing spaces. ANCHORS lists (CALLOUT START END) for
;;; each callout whose anchor is the characters START below END.
(defstruct run text styles breaks widths units anchors)

(defun flatten-inlines (inlines bold)
  "The text of INLINES, the style of each of its characters, the anchors of the callouts
in it, and the indices of the characters inline code or a marker pads, one per side."
  (let ((styles nil) (anchors nil) (pads nil) (i 0))
    (values (with-output-to-string (out)
              (labels ((chars (s style)
                         (loop for c across s
                               do (write-char c out) (push style styles)
                                 (incf i)))
                       (padded (start)
                         (when (> i start) (push start pads) (push (1- i) pads)))
                       (walk (nodes style)
                         (dolist (n nodes)
                           (cond ((stringp n) (chars n style))
                                 ((eq (car n) :tt)
                                  (let ((start i))
                                    (chars (second n) :mono)
                                    (padded start)))
                                 ((eq (car n) :mark)
                                  (let ((start i))
                                    (walk (second n) style)
                                    (padded start)))
                                 ((eq (car n) :b) (walk (second n) :bold))
                                 ((eq (car n) :link) (walk (third n) style))
                                 ((eq (car n) :say)
                                  (let ((start i))
                                    (walk (third n) style)
                                    (push (list (second n) start i) anchors)))
                                 (t (walk (second n) style))))))
                (walk inlines (if bold :bold :normal))))
            (coerce (nreverse styles) 'simple-vector) (nreverse anchors) pads)))

(defun compute-units (text breaks widths)
  (let ((n (length text)) (units nil) (start 0))
    (loop for i from 1 to n
          do
            (when (or (= i n) (svref breaks i))
              (let ((k i) (w 0) (wsp 0))
                (loop while (and (> k start) (char= (char text (1- k)) #\Space))
                      do (decf k) (incf wsp (svref widths k)))
                (loop for j from start below k do (incf w (svref widths j)))
                (push (list start i w wsp) units)
                (setf start i))))
    (nreverse units)))

(defun make-text-run (inlines &key bold)
  "The run of INLINES; BOLD sets the whole text bold, as a title is."
  (multiple-value-bind (text styles anchors pads) (flatten-inlines inlines bold)
    (let* ((n (length text))
           (phrase (budoux-breaks text))
           (breaks (make-array n :initial-element nil))
           (widths (make-array n :initial-element 0))
           (inside (make-array n :initial-element nil)))
      (dolist (i pads) (incf (svref widths i) *inline-pad*))
      (let ((i 0))
        (loop while (< i n)
              do (let ((e (cluster-end text i)))
                   (incf (svref widths i)
                         (if (> e (1+ i))
                             1000
                             (char-width (char-code (char text i)) (svref styles i))))
                   (loop for k from (1+ i) below e do (setf (svref inside k) t))
                   (setf i e))))
      (dotimes (i n)
        (when (> i 0)
          (setf (svref breaks i)
                (cond ((svref inside i) nil)
                      ((number-space-p text styles i) nil)
                      ((char= (char text (1- i)) #\Space) t)
                      ((char= (char text i) #\Space) nil)
                      ((inside-number-p text i) nil)
                      ((and (eq (svref styles (1- i)) :mono)
                            (eq (svref styles i) :mono))
                       nil)
                      ((no-start-p (char text i)) nil)
                      (t (or (svref phrase i)
                             (punctuation-break-p (char text (1- i))
                                                  (char text i))))))))
      (make-run :text text
                :styles styles
                :breaks breaks
                :widths widths
                :units (compute-units text breaks widths)
                :anchors anchors))))

(defun split-unit (run unit size limit)
  "The starts of the lines beyond the first a unit wider than LIMIT takes when broken
between characters (never before a character a line must not start with), in reverse
order, and the width of its last line."
  (let* ((text (run-text run))
         (widths (run-widths run))
         (end (second unit))
         (starts nil)
         (cur 0)
         (count 0)
         (prev 0))
    (loop while (and (> end (first unit)) (char= (char text (1- end)) #\Space))
          do (decf end))
    (loop for j from (first unit) below
          end
          do
            (let ((cw (px (svref widths j) size)))
              (cond ((and (> count 0) (> cw 0) (> (+ cur cw) limit))
                     (if (and (> count 1) (no-start-p (char text j)))
                         (setf cur (+ prev cw) count 2 starts
                               (cons (1- j) starts))
                         (setf cur cw count 1 starts (cons j starts))))
                    (t
                     (incf cur cw)
                     (incf count)))
              (setf prev cw)))
    (values starts cur)))

(defun wrap-run (run size avail)
  "The index of the first character of each line RUN takes at SIZE px, wrapped greedily
into AVAIL px."
  (let ((limit (* avail 1000)) (starts (list 0)) (cur 0))
    (dolist (u (run-units run) (nreverse starts))
      (let ((w (px (third u) size)) (wsp (px (fourth u) size)))
        (cond ((<= (+ cur w) limit) (incf cur (+ w wsp)))
              (t
               (when (> cur 0)
                 (push (first u) starts)
                 (setf cur 0))
               (if (<= w limit)
                   (setf cur (+ w wsp))
                   (multiple-value-bind (more last)
                       (split-unit run u size limit)
                     (setf starts (append more starts))
                     (setf cur (+ last wsp))))))))))

(defun run-lines (run size avail)
  "The lines RUN takes at SIZE px, wrapped greedily into AVAIL px."
  (length (wrap-run run size avail)))

(defun run-span-width (run start end size &optional spaces)
  "The milli-px width of RUN's characters from START below END at SIZE, trailing spaces
excluded unless SPACES is true."
  (let ((text (run-text run)) (widths (run-widths run)) (m 0))
    (loop while (and (not spaces) (> end start)
                     (char= (char text (1- end)) #\Space))
          do (decf end))
    (loop for j from start below end do (incf m (px (svref widths j) size)))
    m))

(defun run-min-width (run size)
  "The px width of RUN's widest unbreakable unit at SIZE."
  (let ((m 0))
    (dolist (u (run-units run)) (setf m (max m (px (third u) size))))
    (milli-px->px m)))

(defun run-full-width (run size)
  "The px width of RUN set on one line at SIZE."
  (let ((total 0) (last-wsp 0))
    (dolist (u (run-units run))
      (setf last-wsp (px (fourth u) size))
      (incf total (+ (px (third u) size) last-wsp)))
    (milli-px->px (- total last-wsp))))

(defun mono-widths (line)
  "The milli-em width of each character of a line of code; an emoji cluster is its
first character's, the rest zero."
  (let ((widths (make-array (length line) :initial-element 0)) (i 0))
    (loop while (< i (length line))
          do (let ((e (cluster-end line i)))
               (setf (svref widths i)
                     (if (> e (1+ i))
                         1000
                         (char-width (char-code (char line i)) :mono))
                     i e)))
    widths))

(defun mono-width (line size)
  "The px width of a line of code at SIZE."
  (let ((m 0))
    (loop for w across (mono-widths line) do (incf m w))
    (milli-px->px (px m size))))

(defun mono-wrap (line size avail)
  "The index of the first character of each row a line of code takes at SIZE px when it
wraps between any two characters into AVAIL px, as a terminal wraps it."
  (let ((limit (* avail 1000)) (starts (list 0)) (cur 0))
    (loop for mw across (mono-widths line)
          for i from 0
          do
            (let ((w (px mw size)))
              (when (and (> cur 0) (> w 0) (> (+ cur w) limit))
                (push i starts)
                (setf cur 0))
              (incf cur w)))
    (nreverse starts)))
