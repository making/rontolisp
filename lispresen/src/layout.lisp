;;;; layout.lisp -- layout selection, the type scale and the fit solver.
;;;;
;;;; Every slide is laid out on a fixed canvas. Sizes only step down from the default,
;;;; and a slide that does not fit at the smallest step is reported, not shrunk further.
;;;; Every derived metric (line height, gaps, padding) is a function of a size here, and
;;;; the HTML emitter writes the same numbers, so the browser lays out what was measured.

(in-package :lispresen)

;;; --- type scale and geometry -----------------------------------------------------

(defparameter *title-steps* '(80 72 64 56))
(defparameter *body-steps* '(56 52 48 44 40))
(defparameter *code-steps* '(44 40 36 32 28))
(defparameter *table-steps* '(48 44 40 36))
(defparameter *statement-steps* '(120 104 88 72 56))

(defparameter *title-floor* 64)
(defparameter *body-floor* 48)
(defparameter *code-floor* 32)
(defparameter *table-floor* 40)

(defparameter *canvas-height* 1080)
(defparameter *margin-x* 112)
(defparameter *margin-top* 80)
(defparameter *margin-bottom* 72)
(defparameter *title-gap* 48)
(defparameter *meta-size* 40)
(defparameter *page-number-size* 24)

(defun title-lh (s) (ceiling (* s 13) 10))
(defun body-lh (s) (ceiling (* s 15) 10))
(defun code-lh (s) (ceiling (* s 14) 10))
(defun table-lh (s) (ceiling (* s 14) 10))
(defun statement-lh (s) (ceiling (* s 135) 100))
(defun block-gap (s) (floor (* s 3) 5))
(defun list-indent (s) (ceiling (* s 12) 10))
(defun item-gap (s) (floor s 4))
(defun code-pad (c) (floor c 2))
(defun cell-pad-x (s) (floor (* s 3) 5))
(defun cell-pad-y (s) (floor s 4))

(defun canvas-width (deck)
  (if (equal (symbol-value-name (deck-aspect deck)) "4X3") 1440 1920))

(defun content-width (deck) (- (canvas-width deck) (* 2 *margin-x*)))

(defun safe-height () (- *canvas-height* *margin-top* *margin-bottom*))

;;; The layout result of one slide. BODY-TOP and BODY-HEIGHT place the body box; the
;;; sizes are px. OVERFLOW is true when the slide does not fit even at the hard floor.
(defstruct fit
  layout
  title-size
  title-lines
  body-top
  body-height
  body-size
  code-size
  stmt-size
  overflow)

;;; --- runs ------------------------------------------------------------------------

(defun prepare-list (b)
  (dolist (it (bullets-items b))
    (setf (item-run it) (make-text-run (item-inlines it)))
    (when (item-sublist it) (prepare-list (item-sublist it)))))

(defun prepare-runs (slide)
  "Attach a run to every piece of text on SLIDE."
  (when (slide-title slide)
    (setf (slide-title-run slide)
          (make-text-run (list (slide-title slide)) :bold t)))
  (dolist (b (slide-blocks slide))
    (cond ((para-p b) (setf (para-run b) (make-text-run (para-inlines b))))
          ((bullets-p b) (prepare-list b))
          ((figure-p b)
           (when (figure-caption b)
             (setf (figure-caption-run b)
                   (make-text-run (list (figure-caption b))))))
          ((table-block-p b)
           (when (table-block-caption b)
             (setf (table-block-caption-run b)
                   (make-text-run (list (table-block-caption b)))))
           (dolist (c (table-block-header b))
             (setf (cell-run c) (make-text-run (cell-inlines c) :bold t)))
           (dolist (row (table-block-rows b))
             (let ((first t))
               (dolist (c row)
                 (setf (cell-run c)
                       (make-text-run (cell-inlines c)
                        :bold (and first (table-block-row-header b))))
                 (setf first nil))))))))

;;; --- layout selection --------------------------------------------------------------

(defparameter *layouts* '("standard" "statement" "code" "image" "table"))

(defun automatic-layout (slide)
  (let ((blocks (slide-blocks slide)))
    (cond ((and (null (slide-title slide)) (= (length blocks) 1)
                (para-p (car blocks)))
           "statement")
          ((/= (length blocks) 1) "standard")
          ((figure-p (car blocks)) "image")
          ((code-block-p (car blocks)) "code")
          ((table-block-p (car blocks)) "table")
          (t "standard"))))

(defun choose-layout (slide)
  (let ((requested (slide-layout slide)) (automatic (automatic-layout slide)))
    (cond ((eq (slide-kind slide) :title) "title")
          ((eq (slide-kind slide) :section) "section")
          ((null requested) automatic)
          ((not (member requested *layouts* :test #'equal))
           (report :warning (format nil "unknown layout :~a; using :~a"
                                    requested automatic))
           automatic)
          ((and (equal requested "statement")
                (not (equal automatic "statement")))
           (report
            :warning
            "the statement layout needs exactly one paragraph and no title")
           automatic)
          (t requested))))

;;; --- measuring blocks ----------------------------------------------------------------

(defun step-at (steps k) (nth (min k (1- (length steps))) steps))

(defun bullets-height (b s width)
  (let ((indent (list-indent s)) (gap (item-gap s)) (h 0) (first t))
    (dolist (it (bullets-items b) h)
      (unless first (incf h gap))
      (setf first nil)
      (incf h (* (run-lines (item-run it) s (- width indent)) (body-lh s)))
      (when (item-sublist it)
        (incf h
              (+ gap (bullets-height (item-sublist it) s (- width indent))))))))

(defun code-fits-p (b c width)
  (let ((inner (- width (* 2 (code-pad c)))))
    (every (lambda (line) (<= (mono-width line c) inner))
           (code-block-lines b))))

(defun code-height (b c)
  (+ (* (max 1 (length (code-block-lines b))) (code-lh c)) (* 2 (code-pad c))))

(defun code-size-at (blocks k width)
  "The code size at step K: the step's size, or a smaller one when a line is too wide;
nil when no step fits."
  (let ((codes (remove-if-not #'code-block-p blocks)))
    (loop for j from k below (length *code-steps*)
          do
            (let ((c (nth j *code-steps*)))
              (when (every (lambda (b) (code-fits-p b c width)) codes)
                (return c))))))

(defun numeric-text-p (s)
  "True when S reads as a number: an optional sign, digits with commas, an optional
fraction and an optional percent sign."
  (let* ((s (string-trim " " s)) (n (length s)) (i 0) (digits 0))
    (when (and (< i n) (find (char s i) "+-−")) (incf i))
    (loop while
            (and (< i n) (or (digit-char-p (char s i)) (char= (char s i) #\,)))
          do (when (digit-char-p (char s i)) (incf digits)) (incf i))
    (when (and (< i n) (char= (char s i) #\.))
      (incf i)
      (loop while (and (< i n) (digit-char-p (char s i)))
            do (incf digits) (incf i)))
    (when (and (< i n) (char= (char s i) #\%)) (incf i))
    (and (> digits 0) (= i n))))

(defun placeholder-text-p (s)
  (member (string-trim " " s) '("" "-" "--" "—" "–") :test #'string=))

(defun column-aligns (tb ncol)
  "Each column's alignment: the requested one, else right for a numeric column."
  (or (mapcar #'string-downcase (table-block-align tb))
      (loop for col from 0 below ncol
            collect
              (let ((numbers 0) (others 0))
                (dolist (row (table-block-rows tb))
                  (let ((at 0))
                    (dolist (c row)
                      (when (and (= at col) (= (cell-colspan c) 1)
                             (not (and (table-block-row-header tb) (= col 0))))
                        (let ((text (run-text (cell-run c))))
                          (cond ((numeric-text-p text) (incf numbers))
                           ((not (placeholder-text-p text)) (incf others)))))
                      (incf at (cell-colspan c)))))
                (if (and (> numbers 0) (= others 0)) "right" "left")))))

(defun table-rows-with-header (tb)
  (if (table-block-header tb)
      (cons (table-block-header tb) (table-block-rows tb))
      (table-block-rows tb)))

(defun table-columns (tb)
  (reduce #'+ (mapcar #'cell-colspan (car (table-block-rows tb)))))

(defun column-bounds (tb s)
  "Per-column minimum and maximum widths in px at size S, padding included."
  (let* ((ncol (table-columns tb))
         (pad (* 2 (cell-pad-x s)))
         (mins (make-array ncol :initial-element pad))
         (maxs (make-array ncol :initial-element pad))
         (spanned nil))
    (dolist (row (table-rows-with-header tb))
      (let ((col 0))
        (dolist (c row)
          (let ((lo (+ (run-min-width (cell-run c) s) pad))
                (hi (+ (run-full-width (cell-run c) s) pad)))
            (if (= (cell-colspan c) 1)
                (when (< col ncol)
                  (setf (svref mins col) (max (svref mins col) lo)
                        (svref maxs col) (max (svref maxs col) hi)))
                (push (list col (cell-colspan c) lo hi) spanned)))
          (incf col (cell-colspan c)))))
    ;; A cell spanning columns widens the last of them when the span is too narrow.
    (dolist (sp spanned)
      (destructuring-bind (col span lo hi) sp
        (let ((last (min (1- ncol) (+ col span -1))) (lo-sum 0) (hi-sum 0))
          (loop for j from col to last
                do (incf lo-sum (svref mins j)) (incf hi-sum (svref maxs j)))
          (when (> lo lo-sum) (incf (svref mins last) (- lo lo-sum)))
          (when (> hi hi-sum) (incf (svref maxs last) (- hi hi-sum))))))
    (values mins maxs)))

(defun column-widths (tb s avail)
  "Column widths in px at size S within AVAIL px, or nil when the minima exceed it."
  (multiple-value-bind (mins maxs) (column-bounds tb s)
    (let* ((ncol (length mins))
           (sum-min (reduce #'+ mins))
           (sum-max (reduce #'+ maxs)))
      (cond ((<= sum-max avail) (coerce maxs 'list))
            ((> sum-min avail) nil)
            (t
             (let* ((extra (- avail sum-min))
                    (slack (- sum-max sum-min))
                    (widths
                     (loop for j from 0 below ncol
                           collect
                             (+ (svref mins j)
                                (floor
                                 (* extra (- (svref maxs j) (svref mins j)))
                                 slack))))
                    (widest 0))
               ;; The rounding remainder goes to the column with the most slack.
               (loop for j from 1 below ncol
                     do
                       (when (> (- (svref maxs j) (svref mins j))
                                (- (svref maxs widest) (svref mins widest)))
                         (setf widest j)))
               (incf (nth widest widths) (- avail (reduce #'+ widths)))
               widths))))))

(defun table-height (tb s widths)
  (let ((lh (table-lh s)) (px (cell-pad-x s)) (py (cell-pad-y s)) (h 0))
    (when (table-block-caption-run tb)
      (incf h
            (+ (* (run-lines (table-block-caption-run tb) s (reduce #'+ widths))
                  lh) py)))
    (dolist (row (table-rows-with-header tb) h)
      (let ((col 0) (tallest 0))
        (dolist (c row)
          (let ((w
                 (reduce #'+
                         (subseq widths (min col (length widths))
                          (min (+ col (cell-colspan c)) (length widths))))))
            (setf tallest
                  (max tallest (run-lines (cell-run c) s (- w (* 2 px)))))
            (incf col (cell-colspan c))))
        (incf h (+ (* tallest lh) (* 2 py)))))))

(defun table-fit (tb k width)
  "The size, widths and height of TB at step K, stepping further down while its columns
do not fit; nil when none does."
  (loop for j from (min k (1- (length *table-steps*))) below
          (length *table-steps*)
        do
          (let* ((s (nth j *table-steps*)) (widths (column-widths tb s width)))
            (when widths
              (return (values s widths (table-height tb s widths)))))))

(defun blocks-height (blocks k width body-height)
  "The height of BLOCKS at step K, and whether every block fits its width."
  (let* ((s (step-at *body-steps* k))
         (c (code-size-at blocks k width))
         (h (* (block-gap s) (max 0 (1- (length blocks)))))
         (ok t))
    (dolist (b blocks)
      (cond
       ((para-p b) (incf h (* (run-lines (para-run b) s width) (body-lh s))))
       ((bullets-p b) (incf h (bullets-height b s width)))
       ((code-block-p b) (if c (incf h (code-height b c)) (setf ok nil)))
       ((figure-p b)
        (incf h (floor body-height 3))
        (when (figure-caption-run b)
          (incf h (* (run-lines (figure-caption-run b) s width) (body-lh s)))))
       ((table-block-p b)
        (multiple-value-bind (ts widths th) (table-fit b k width)
          (declare (ignore widths))
          (if ts (incf h th) (setf ok nil))))))
    (values h ok)))

;;; --- fitting slides --------------------------------------------------------------------

(defun fit-title (slide width)
  "The title size and line count: the largest step at which it takes 2 lines at most."
  (let ((run (slide-title-run slide)))
    (dolist (s *title-steps*)
      (let ((lines (run-lines run s width)))
        (when (<= lines 2) (return-from fit-title (values s lines)))))
    (let* ((s (car (last *title-steps*))) (lines (run-lines run s width)))
      (report :warning (format nil
                               "the title takes ~d lines at ~dpx; shorten it"
                               lines s))
      (values s lines))))

(defun fit-centered (run width max-lines)
  "The statement-scale size for RUN: the largest step with at most MAX-LINES lines that
fits the safe area. The second value is true on overflow."
  (dolist (s *statement-steps*)
    (let ((lines (run-lines run s width)))
      (when (and (<= lines max-lines)
                 (<= (* lines (statement-lh s)) (safe-height)))
        (return-from fit-centered (values s nil)))))
  (let* ((s (car (last *statement-steps*))) (lines (run-lines run s width)))
    (if (> (* lines (statement-lh s)) (safe-height))
        (progn
          (report
           :error "the text does not fit the slide even at the smallest size")
          (values s t))
        (progn
          (report :warning (format nil
                                   "the text takes ~d lines at ~dpx; shorten it"
                                   lines s))
          (values s nil)))))

(defun fit-body (slide fit width)
  (let* ((blocks (slide-blocks slide))
         (body-height (fit-body-height fit))
         (k
          (loop for k from 0 below (length *body-steps*)
                do
                  (multiple-value-bind (h ok)
                      (blocks-height blocks k width body-height)
                    (when (and ok (<= h body-height)) (return k))))))
    (unless k
      (report :error
              "the content does not fit the slide even at the smallest sizes; split the slide")
      (setf (fit-overflow fit) t)
      (setf k (1- (length *body-steps*))))
    (setf (fit-body-size fit) (step-at *body-steps* k) (fit-code-size fit)
          (or (code-size-at blocks k width) (car (last *code-steps*))))
    (dolist (b blocks)
      (when (table-block-p b)
        (multiple-value-bind (s widths) (table-fit b k width)
          (let ((s (or s (car (last *table-steps*)))))
            (setf (table-block-size b) s (table-block-widths b)
                  (or widths
                      (multiple-value-bind (mins) (column-bounds b s)
                        (coerce mins 'list))) (table-block-aligns b)
                  (column-aligns b (table-columns b)))))))))

(defun fit-slide (deck slide)
  "Lay out SLIDE and store the result in its FIT slot."
  (prepare-runs slide)
  (let* ((width (content-width deck))
         (layout (choose-layout slide))
         (fit (make-fit :layout layout)))
    (setf (slide-fit slide) fit)
    (cond ((member layout '("title" "section") :test #'equal)
           (when (slide-title-run slide)
             (setf (fit-stmt-size fit)
                   (fit-centered (slide-title-run slide) width 2))))
          ((equal layout "statement")
           (let ((run
                  (make-text-run (para-inlines (car (slide-blocks slide)))
                                 :bold t)))
             (setf (para-run (car (slide-blocks slide))) run)
             (multiple-value-bind (s overflow) (fit-centered run width 3)
               (setf (fit-stmt-size fit) s (fit-overflow fit) overflow))))
          (t (let ((top *margin-top*))
               (when (slide-title-run slide)
                 (multiple-value-bind (s lines) (fit-title slide width)
                   (setf (fit-title-size fit) s (fit-title-lines fit) lines)
                   (incf top (+ (* lines (title-lh s)) *title-gap*))))
               (setf (fit-body-top fit) top (fit-body-height fit)
                     (- *canvas-height* *margin-bottom* top))
               (fit-body slide fit width))))
    fit))
