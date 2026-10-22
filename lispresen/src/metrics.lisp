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
      (<= #x1F900 code #x1F9FF) (<= #x20000 code #x3FFFD)))

(defun parse-widths (s)
  (coerce (mapcar #'parse-integer (split-spaces s)) 'simple-vector))

;;; Milli-em advance of each printable ASCII character (code 32..126) in Hiragino Sans
;;; W3 and W6, measured in Chrome on 2026-10-22. Hiragino's Latin is the widest of the
;;; font stack, so the table over-estimates the other faces rather than under.
(defparameter *ascii-widths*
  (parse-widths
   "333 311 356 657 650 879 797 195 356 356 346 656 240 358 240 474 657 657 657 657 661 657 657 657 657 657 240 240 512 656 512 571 905 750 712 756 758 643 602 754 778 249 509 707 602 945 774 795 662 795 726 669 649 773 717 1007 722 688 669 346 474 346 533 501 500 575 646 578 646 588 417 641 617 238 265 575 232 931 618 625 647 646 430 537 392 618 544 770 536 554 498 347 375 347 543"))

(defparameter *ascii-bold-widths*
  (parse-widths
   "333 339 406 719 712 935 860 234 406 406 382 685 270 412 270 520 712 712 712 712 712 712 712 712 712 712 270 270 540 685 540 617 937 808 754 793 806 685 644 790 826 293 591 780 643 987 821 838 711 838 773 715 709 829 784 1058 811 758 707 406 520 406 588 504 500 634 706 625 707 637 477 692 673 271 309 652 271 1010 673 670 708 706 492 593 434 673 621 850 617 628 541 411 424 411 609"))

(defun char-width (code style)
  "Milli-em width of CODE in STYLE (:normal, :bold or :mono). Full-width characters are
exactly 1 em in every face of the stack; proportional ones carry a 2% margin for
sub-pixel rounding and faces other than the measured one. A character outside ASCII and
the full-width ranges takes the width of a wide Latin letter."
  (if (wide-code-p code)
      1000
      (ceiling (* (cond ((eq style :mono) 602)
                        ((<= 32 code 126)
                         (svref (if (eq style :bold)
                                    *ascii-bold-widths*
                                    *ascii-widths*) (- code 32)))
                        ((eq style :bold) 840)
                        (t 800)) 102) 100)))

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

;;; --- runs ----------------------------------------------------------------------

;;; A paragraph's text with what wrapping needs. STYLES holds :normal, :bold or :mono
;;; per character, BREAKS is true at I when a line may start at character I, WIDTHS is
;;; milli-em per character, and UNITS lists (START END W WSP) for each stretch between
;;; break opportunities, W excluding and WSP being its trailing spaces. ANCHORS lists
;;; (CALLOUT START END) for each callout whose anchor is the characters START below END.
(defstruct run text styles breaks widths units anchors)

(defun flatten-inlines (inlines bold)
  "The text of INLINES, the style of each of its characters, and the anchors of the
callouts in it."
  (let ((styles nil) (anchors nil) (i 0))
    (values (with-output-to-string (out)
              (labels ((chars (s style)
                         (loop for c across s
                               do (write-char c out) (push style styles)
                                 (incf i)))
                       (walk (nodes style)
                         (dolist (n nodes)
                           (cond ((stringp n) (chars n style))
                                 ((eq (car n) :tt) (chars (second n) :mono))
                                 ((eq (car n) :b) (walk (second n) :bold))
                                 ((eq (car n) :link) (walk (third n) style))
                                 ((eq (car n) :say)
                                  (let ((start i))
                                    (walk (third n) style)
                                    (push (list (second n) start i) anchors)))
                                 (t (walk (second n) style))))))
                (walk inlines (if bold :bold :normal))))
            (coerce (nreverse styles) 'simple-vector) (nreverse anchors))))

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
  (multiple-value-bind (text styles anchors) (flatten-inlines inlines bold)
    (let* ((n (length text))
           (phrase (budoux-breaks text))
           (breaks (make-array n :initial-element nil))
           (widths (make-array n :initial-element 0)))
      (dotimes (i n)
        (setf (svref widths i)
              (char-width (char-code (char text i)) (svref styles i)))
        (when (> i 0)
          (setf (svref breaks i)
                (cond ((char= (char text (1- i)) #\Space) t)
                      ((char= (char text i) #\Space) nil)
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
              (cond ((and (> count 0) (> (+ cur cw) limit))
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

(defun run-span-width (run start end size)
  "The milli-px width of RUN's characters from START below END at SIZE, trailing spaces
excluded."
  (let ((text (run-text run)) (widths (run-widths run)) (m 0))
    (loop while (and (> end start) (char= (char text (1- end)) #\Space))
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

(defun mono-width (line size)
  "The px width of a line of code at SIZE."
  (let ((m 0))
    (loop for c across line do (incf m (char-width (char-code c) :mono)))
    (milli-px->px (px m size))))

(defun mono-wrap (line size avail)
  "The index of the first character of each row a line of code takes at SIZE px when it
wraps between any two characters into AVAIL px, as a terminal wraps it."
  (let ((limit (* avail 1000)) (starts (list 0)) (cur 0))
    (loop for c across line
          for i from 0
          do
            (let ((w (px (char-width (char-code c) :mono) size)))
              (when (and (> cur 0) (> (+ cur w) limit))
                (push i starts)
                (setf cur 0))
              (incf cur w)))
    (nreverse starts)))
