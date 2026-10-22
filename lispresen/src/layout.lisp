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
(defparameter *column-gap* 80)
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
(defun quote-indent (s) (ceiling (* s 12) 10))

(defun canvas-width (deck)
  (if (equal (symbol-value-name (deck-aspect deck)) "4X3") 1440 1920))

(defun content-width (deck) (- (canvas-width deck) (* 2 *margin-x*)))

(defun safe-height () (- *canvas-height* *margin-top* *margin-bottom*))

;;; The layout result of one slide. BODY-TOP and BODY-HEIGHT place the body box; the
;;; sizes are px; REGION-WIDTH is the width of one column. OVERFLOW is true when the
;;; slide does not fit even at the hard floor.
(defstruct fit
  layout
  region-width
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

(defparameter *runnable-langs* '("lisp" "scheme" "clojure"))

(defun highlighted-p (b)
  (and (code-block-lang b)
       (member (string-downcase (code-block-lang b)) *runnable-langs*
               :test #'string=)))

(defun prepare-blocks (blocks runtime)
  "Attach a run to every piece of text in BLOCKS, and mark the code RUNTIME runs."
  (dolist (b blocks)
    (cond ((para-p b) (setf (para-run b) (make-text-run (para-inlines b))))
          ((bullets-p b) (prepare-list b))
          ((column-p b) (prepare-blocks (column-blocks b) runtime))
          ((code-block-p b)
           (setf (code-block-runnable b)
                 (and (highlighted-p b) (code-block-run b)
                      (not (eq runtime :none)) t)))
          ((quotation-p b)
           (setf (quotation-run b) (make-text-run (quotation-inlines b)))
           (when (quotation-cite b)
             (setf (quotation-cite-run b)
                   (make-text-run (list (cite-text b))))))
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

(defun cite-text (q)
  (concatenate 'string (string (code-char #x2014)) " " (quotation-cite q)))

(defun prepare-runs (deck slide)
  (when (slide-title slide)
    (setf (slide-title-run slide)
          (make-text-run (list (slide-title slide)) :bold t)))
  (prepare-blocks (slide-blocks slide) (deck-runtime deck))
  (dolist (c (slide-callouts slide))
    (setf (callout-run c) (make-text-run (list (callout-text c))))))

(defun region-blocks (blocks)
  "BLOCKS with every column replaced by its blocks."
  (mapcan (lambda (b) (if (column-p b) (copy-list (column-blocks b)) (list b)))
          blocks))

;;; --- layout selection --------------------------------------------------------------

(defparameter *layouts*
  '("standard" "statement" "code" "image" "table" "quote" "columns"))

(defun automatic-layout (slide)
  "The layout SLIDE's shape asks for. A statement's centered, balanced lines are not where
the model puts them, so a slide with callouts never takes it."
  (let ((blocks (slide-blocks slide)))
    (cond ((and (null (slide-title slide)) (= (length blocks) 1)
                (para-p (car blocks)) (null (slide-callouts slide)))
           "statement")
          ((and blocks (every #'column-p blocks)) "columns")
          ((/= (length blocks) 1) "standard")
          ((figure-p (car blocks)) "image")
          ((code-block-p (car blocks)) "code")
          ((quotation-p (car blocks)) "quote")
          ((table-block-p (car blocks)) "table")
          (t "standard"))))

(defun choose-layout (slide)
  (let ((requested (slide-layout slide)) (automatic (automatic-layout slide)))
    (cond ((eq (slide-kind slide) :title) "title")
          ((eq (slide-kind slide) :section) "section")
          ((or (null requested) (equal requested automatic)) automatic)
          ((not (member requested *layouts* :test #'equal))
           (report :warning (format nil "unknown layout :~a; using :~a"
                                    requested automatic))
           automatic)
          ((member "statement" (list requested automatic) :test #'equal)
           (report :warning
                   "the statement layout needs exactly one paragraph, no title and no callouts")
           automatic)
          ((member "columns" (list requested automatic) :test #'equal)
           (report :warning "the columns layout needs (column ...) blocks only")
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

(defun even-widths (maxs avail)
  "Widths of at least MAXS summing to AVAIL, the narrowest raised first to one level, so
the columns are as even as their content allows. The rounding remainder goes 1 px each
to the leftmost raised columns."
  (let ((raised (loop for j from 0 below (length maxs) collect j)) (level 0))
    ;; Fix every column wider than the level at its maximum, until none is.
    (loop
      (setf level
            (floor (- avail
                      (loop for j from 0 below (length maxs)
                            unless (member j raised)
                            sum (svref maxs j))) (length raised)))
      (let ((wide (remove-if (lambda (j) (<= (svref maxs j) level)) raised)))
        (if wide (setf raised (set-difference raised wide)) (return))))
    (let ((rest
           (- avail (* level (length raised))
              (loop for j from 0 below (length maxs)
                    unless (member j raised)
                    sum (svref maxs j)))))
      (loop for j from 0 below (length maxs)
            collect
              (if (member j raised)
                  (+ level
                     (if (> rest 0)
                         (progn
                           (decf rest)
                           1)
                         0))
                  (svref maxs j))))))

(defun column-widths (tb s avail)
  "Column widths in px at size S filling AVAIL px, or nil when the minima exceed it."
  (multiple-value-bind (mins maxs) (column-bounds tb s)
    (let* ((ncol (length mins))
           (sum-min (reduce #'+ mins))
           (sum-max (reduce #'+ maxs)))
      (cond ((<= sum-max avail) (even-widths maxs avail))
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

(defun quote-height (q s width)
  (let ((w (- width (quote-indent s))))
    (+ (* (run-lines (quotation-run q) s w) (body-lh s))
       (if (quotation-cite-run q)
           (+ (item-gap s)
              (* (run-lines (quotation-cite-run q) s w) (body-lh s)))
           0))))

(defun caption-height (b s width)
  (if (figure-caption-run b)
      (* (run-lines (figure-caption-run b) s width) (body-lh s))
      0))

(defun block-height (b k c width body-height)
  "The height of the block B at body step K and code size C (nil when no code size
fits), and whether it fits its width. An image is counted at a third of the body."
  (let ((s (step-at *body-steps* k)) (h 0) (ok t))
    (cond ((para-p b) (setf h (* (run-lines (para-run b) s width) (body-lh s))))
          ((quotation-p b) (setf h (quote-height b s width)))
          ((bullets-p b) (setf h (bullets-height b s width)))
          ((code-block-p b) (if c (setf h (code-height b c)) (setf ok nil)))
          ((figure-p b)
           (setf h (+ (floor body-height 3) (caption-height b s width))))
          ((table-block-p b)
           (multiple-value-bind (ts widths th) (table-fit b k width)
             (declare (ignore widths))
             (if ts (setf h th) (setf ok nil)))))
    (values h ok)))

(defun blocks-height (blocks k c width body-height)
  "The height of BLOCKS at body step K and code size C (nil when no code size fits),
and whether every block fits its width."
  (let ((h
         (* (block-gap (step-at *body-steps* k)) (max 0 (1- (length blocks)))))
        (ok t))
    (dolist (b blocks)
      (multiple-value-bind (bh fits) (block-height b k c width body-height)
        (incf h bh)
        (unless fits (setf ok nil))))
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

(defun regions (slide width)
  "The regions of SLIDE as (BLOCKS WIDTH) lists: one per column, else the body."
  (let ((columns (remove-if-not #'column-p (slide-blocks slide))))
    (if columns
        (let ((w
               (floor (- width (* (1- (length columns)) *column-gap*))
                      (length columns))))
          (mapcar (lambda (col) (list (column-blocks col) w)) columns))
        (list (list (slide-blocks slide) width)))))

(defun fit-body (slide fit width)
  "Choose the largest body step at which every region fits. Columns share it, and the
code size, so side-by-side text matches."
  (let* ((regions (regions slide width))
         (rwidth (second (car regions)))
         (all (region-blocks (slide-blocks slide)))
         (body-height (fit-body-height fit))
         (k
          (loop for k from 0 below (length *body-steps*)
                do
                  (let ((c (code-size-at all k rwidth)))
                    (when (every (lambda (r)
                                   (multiple-value-bind (h ok) (blocks-height
                                                                (first r) k c
                                                                rwidth
                                                                body-height)
                                     (and ok (<= h body-height)))) regions)
                      (return k))))))
    (unless k
      (report :error
              "the content does not fit the slide even at the smallest sizes; split the slide")
      (setf (fit-overflow fit) t)
      (setf k (1- (length *body-steps*))))
    (setf k (fit-callouts slide fit k width))
    (setf (fit-region-width fit) rwidth (fit-body-size fit)
          (step-at *body-steps* k) (fit-code-size fit)
          (body-code-size all k rwidth))
    (dolist (b all)
      (when (table-block-p b)
        (multiple-value-bind (s widths) (table-layout b k rwidth)
          (setf (table-block-size b) s (table-block-widths b) widths
                (table-block-aligns b) (column-aligns b (table-columns b))))))))

(defun body-code-size (blocks k width)
  (or (code-size-at blocks k width) (car (last *code-steps*))))

(defun table-layout (tb k width)
  "The size and column widths TB is set at for body step K: the smallest size and the
columns' minimum widths when nothing fits."
  (multiple-value-bind (s widths) (table-fit tb k width)
    (if s
        (values s widths)
        (let ((s (car (last *table-steps*))))
          (values s
                  (multiple-value-bind (mins) (column-bounds tb s)
                    (coerce mins 'list)))))))

(defun fit-slide (deck slide)
  "Lay out SLIDE and store the result in its FIT slot."
  (prepare-runs deck slide)
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

;;; --- callouts ----------------------------------------------------------------------
;;;
;;; A callout's bubble is an overlay: it takes no room in the flow. It is placed in the
;;; free space of the body's geometry, the rectangles where the fit solver put text, so
;;; that it covers neither its anchor nor other text. Rectangles are (LEFT TOP RIGHT
;;; BOTTOM) in canvas px.

(defparameter *say-steps* '(44 40 36))
(defparameter *say-floor* 40)
(defparameter *say-border* 3)
(defparameter *tail-reach*
  36
  "From the anchor to the bubble's edge: the 32 px tail and a 4 px gap.")
(defparameter *tail-half* 18 "Half the tail's base.")
(defparameter *tail-inset*
  34
  "The least distance from the tail's middle to a corner: the 16 px radius and half
the base.")
(defparameter *say-clearance* 12 "The free margin around a bubble.")
(defparameter *tools-box*
  '(320 48 12)
  "Width, height and inset of the Run and Reset buttons in a runnable code block's
top right corner.")

(defun say-lh (s) (ceiling (* s 14) 10))
(defun say-pad-x (s) (floor s 2))
(defun say-pad-y (s) (floor s 4))

(defvar *obstacles* nil "The rectangles a bubble must not meet.")
(defvar *slide-callouts* nil "The callouts of the slide being laid out.")

(defun obstacle (r) (push r *obstacles*))

(defun rect-union (a b)
  (list (min (first a) (first b)) (min (second a) (second b))
        (max (third a) (third b)) (max (fourth a) (fourth b))))

(defun rects-meet-p (a b)
  (and (< (first a) (third b)) (< (first b) (third a)) (< (second a) (fourth b))
       (< (second b) (fourth a))))

(defun line-offset (lw width align)
  (cond ((equal align "right") (- width lw))
        ((equal align "center") (floor (- width lw) 2))
        (t 0)))

(defun run-geometry (run s width lh x y &optional align)
  "Records each line of RUN, set at S px into WIDTH px from X, Y, as an obstacle, and
the fragments of the callouts anchored in it. Returns the rectangle around its lines."
  (let* ((n (length (run-text run)))
         (starts (wrap-run run s width))
         (lines
          (loop for (start next) on starts
                for top from y by lh
                collect
                  (let* ((end (or next n))
                         (lw (milli-px->px (run-span-width run start end s))))
                    (list start end top (+ x (line-offset lw width align))
                          lw))))
         (box nil))
    (dolist (line lines)
      (destructuring-bind (start end top lx lw) line
        (declare (ignore start end))
        (when (> lw 0)
          (let ((r (list lx top (+ lx lw) (+ top lh))))
            (obstacle r)
            (setf box (if box (rect-union box r) r))))))
    (dolist (anchor (run-anchors run))
      (destructuring-bind (c a e) anchor
        (setf (callout-fragments c)
              (loop for (start end top lx) in lines
                    for lo = (max a start)
                    for hi = (min e end)
                    for w = (if (< lo hi) (run-span-width run lo hi s) 0)
                    when (> w 0)
                    collect
                      (let ((before (run-span-width run start lo s)))
                        (list (+ lx (floor before 1000)) top
                              (+ lx (milli-px->px (+ before w)))
                              (+ top lh)))))))
    box))

(defun list-geometry (b s x y width)
  "Records the list B set at S px from X, Y; returns its height, as BULLETS-HEIGHT."
  (let ((indent (list-indent s))
        (gap (item-gap s))
        (lh (body-lh s))
        (h 0)
        (first t))
    (dolist (it (bullets-items b) h)
      (unless first (incf h gap))
      (setf first nil)
      ;; The marker sits in the indent, beside the item's first line.
      (obstacle (list x (+ y h) (+ x indent) (+ y h lh)))
      (run-geometry (item-run it) s (- width indent) lh (+ x indent) (+ y h))
      (incf h (* (run-lines (item-run it) s (- width indent)) lh))
      (when (item-sublist it)
        (incf h gap)
        (incf h
              (list-geometry (item-sublist it) s (+ x indent) (+ y h)
                             (- width indent)))))))

(defun quote-geometry (q s x y width)
  (let* ((indent (quote-indent s)) (lh (body-lh s)))
    (run-geometry (quotation-run q) s (- width indent) lh (+ x indent) y)
    (when (quotation-cite-run q)
      (run-geometry (quotation-cite-run q) s (- width indent) lh (+ x indent)
                    (+ y (* (run-lines (quotation-run q) s (- width indent)) lh)
                       (item-gap s))))
    (quote-height q s width)))

(defun code-geometry (b c x y width)
  "Records the lines of the code block B at C px from X, Y, its Run and Reset buttons,
and the fragment of a callout naming a piece of it with :at; returns its height."
  (let ((pad (code-pad c)) (lh (code-lh c)))
    (loop for line in (code-block-lines b)
          for top from (+ y pad) by lh
          do
            (let ((w (mono-width line c)))
              (when (> w 0)
                (obstacle (list (+ x pad) top (+ x pad w) (+ top lh))))))
    (when (code-block-runnable b)
      (destructuring-bind (tw th inset) *tools-box*
        (obstacle
         (list (- (+ x width) inset tw) (+ y inset) (- (+ x width) inset)
               (+ y inset th)))))
    (dolist (co *slide-callouts*)
      (when (and (eq (callout-target co) b) (callout-at co))
        (let ((at (callout-at co)))
          (loop for line in (code-block-lines b)
                for top from (+ y pad) by lh
                for col = (search at line)
                when col
                do
                  (setf (callout-fragments co)
                        (list
                         (list (+ x pad (mono-width (subseq line 0 col) c)) top
                               (+ x pad
                                  (mono-width
                                   (subseq line 0 (+ col (length at))) c))
                               (+ top lh)))) (return)))))
    (code-height b c)))

(defun table-geometry (tb k x y width)
  "Records the caption and cells of TB at body step K from X, Y; returns its height and
its box."
  (multiple-value-bind (s widths) (table-layout tb k width)
    (let ((lh (table-lh s))
          (px (cell-pad-x s))
          (py (cell-pad-y s))
          (aligns (column-aligns tb (table-columns tb)))
          (total (reduce #'+ widths))
          (top y))
      (when (table-block-caption-run tb)
        (run-geometry (table-block-caption-run tb) s total lh x top)
        (incf top
              (+ (* (run-lines (table-block-caption-run tb) s total) lh) py)))
      (dolist (row (table-rows-with-header tb))
        (let ((col 0) (cx x) (tallest 0))
          (dolist (c row)
            (let ((w
                   (reduce #'+
                           (subseq widths (min col (length widths))
                            (min (+ col (cell-colspan c)) (length widths))))))
              (run-geometry (cell-run c) s (- w (* 2 px)) lh (+ cx px)
                            (+ top py) (nth col aligns))
              (setf tallest
                    (max tallest (run-lines (cell-run c) s (- w (* 2 px)))))
              (incf cx w)
              (incf col (cell-colspan c))))
          (incf top (+ (* tallest lh) (* 2 py)))))
      (values (- top y) (list x y (+ x total) top)))))

(defun figure-geometry (b s x y width height)
  "Records the image B given HEIGHT px from X, Y: its picture area, whose size the
image's own is unknown within, and its caption. Returns the picture area."
  (let* ((w (- width (or (figure-strip b) 0)))
         (cap (caption-height b s w))
         (img (list x y (+ x w) (+ y (- height cap)))))
    (obstacle img)
    (when (figure-caption-run b)
      (run-geometry (figure-caption-run b) s w (body-lh s) x (fourth img)
                    "center"))
    img))

(defun blocks-geometry (blocks k c x y width height)
  "Records the geometry of BLOCKS, a region WIDTH x HEIGHT px from X, Y at body step K
and code size C, and the fragments of the callouts pointing at its blocks. Images share
what the other blocks leave, as the stylesheet's flex layout gives it to them."
  (let* ((s (step-at *body-steps* k))
         (gap (block-gap s))
         (images (count-if #'figure-p blocks))
         (rest
          (- height (* gap (max 0 (1- (length blocks))))
             (loop for b in blocks
                   unless (figure-p b)
                   sum (block-height b k c width height))))
         (top y))
    (dolist (b blocks)
      (let ((before *obstacles*) (h 0) (box nil))
        (cond ((para-p b)
               (setf h (* (run-lines (para-run b) s width) (body-lh s)))
               (run-geometry (para-run b) s width (body-lh s) x top))
              ((bullets-p b) (setf h (list-geometry b s x top width)))
              ((quotation-p b) (setf h (quote-geometry b s x top width)))
              ((code-block-p b)
               (setf h (code-geometry b c x top width) box
                     (list x top (+ x width) (+ top h))))
              ((table-block-p b)
               (multiple-value-bind (th tbox) (table-geometry b k x top width)
                 (setf h th box tbox)))
              ((figure-p b)
               (setf h (floor rest images) box
                     (figure-geometry b s x top width h))))
        ;; A text block's anchor is the rectangle around its lines.
        (unless box
          (loop for r on *obstacles*
                until (eq r before)
                do (setf box (if box (rect-union box (car r)) (car r)))))
        (dolist (co *slide-callouts*)
          (when (and box (eq (callout-target co) b) (null (callout-at co)))
            (setf (callout-fragments co) (list box))))
        (incf top (+ h gap))))))

(defun say-max-width (c content-width region-width)
  "The widest C's bubble may be: half the content width, and on an image half the
image's region less the tail, so the image keeps the other half."
  (let ((m (floor content-width 2)))
    (if (figure-p (callout-target c))
        (min m (- (floor region-width 2) *tail-reach*))
        m)))

(defun bubble-size (c s max)
  "The width, height and line count of C's bubble at S px when at most MAX px wide."
  (let* ((frame (* 2 (+ (say-pad-x s) *say-border*)))
         (lines (run-lines (callout-run c) s (- max frame))))
    (values (if (> lines 1) max (+ frame (run-full-width (callout-run c) s)))
            (+ (* lines (say-lh s)) (* 2 (+ (say-pad-y s) *say-border*)))
            lines)))

(defun say-candidates (frags w h bounds)
  "The places tried for a W x H bubble pointing at FRAGS, in order, as (SIDE X Y TAIL
TAIL-BOX): right of the last fragment, below it, above the first, left of it. Each
side is tried
centered on the anchor, then running on past it, then back, moved inside BOUNDS along
the side. TAIL is the tail's position along the side from the bubble's corner."
  (let ((f1 (car frags))
        (fl (car (last frags)))
        (reach *tail-reach*)
        (inset *tail-inset*)
        (half *tail-half*)
        (out nil))
    (destructuring-bind (left top right bottom) bounds
      (flet ((along (a extent lo hi)
               (mapcar (lambda (p) (max lo (min (- hi extent) p)))
                       (list (- a (floor extent 2)) (- a inset)
                             (+ (- a extent) inset)))))
        (let ((ax (third fl)) (ay (floor (+ (second fl) (fourth fl)) 2)))
          (dolist (y (along ay h top bottom))
            (push (list "right" (+ ax reach) y (- ay y)
                        (list ax (- ay half) (+ ax reach) (+ ay half))) out)))
        (let ((ax (floor (+ (first fl) (third fl)) 2)) (ay (fourth fl)))
          (dolist (x (along ax w left right))
            (push (list "below" x (+ ay reach) (- ax x)
                        (list (- ax half) ay (+ ax half) (+ ay reach))) out)))
        (let ((ax (floor (+ (first f1) (third f1)) 2)) (ay (second f1)))
          (dolist (x (along ax w left right))
            (push (list "above" x (- ay reach h) (- ax x)
                        (list (- ax half) (- ay reach) (+ ax half) ay)) out)))
        (let ((ax (first f1)) (ay (floor (+ (second f1) (fourth f1)) 2)))
          (dolist (y (along ay h top bottom))
            (push (list "left" (- ax reach w) y (- ay y)
                        (list (- ax reach) (- ay half) ax (+ ay half))) out)))))
    (nreverse out)))

(defun candidate-free-p (candidate w h obstacles bounds)
  "True when the bubble CANDIDATE places stays in BOUNDS, keeps its tail off its
corners, and meets no obstacle, its clearance included."
  (destructuring-bind (side x y tail tail-box) candidate
    (let ((r (list x y (+ x w) (+ y h))) (c *say-clearance*))
      (and (<= (first bounds) x) (<= (+ x w) (third bounds))
           (<= (second bounds) y) (<= (+ y h) (fourth bounds))
           (<= *tail-inset* tail
               (- (if (member side '("right" "left") :test #'equal) h w)
                  *tail-inset*))
           (notany (lambda (o)
                     (or (rects-meet-p (list (- x c) (- y c) (+ (third r) c)
                                             (+ (fourth r) c)) o)
                         (rects-meet-p tail-box o))) obstacles)))))

(defun place-callouts (callouts bounds final)
  "Places CALLOUTS in turn among *OBSTACLES*, each an obstacle for the next; true when
every one found room. On the FINAL attempt one without room is put below its anchor
inside BOUNDS, marked and reported."
  (let ((ok t))
    (dolist (c callouts ok)
      (let* ((w (callout-width c))
             (h (callout-height c))
             (candidates (say-candidates (callout-fragments c) w h bounds))
             (spot
              (find-if (lambda (p) (candidate-free-p p w h *obstacles* bounds))
                       candidates)))
        (setf (callout-overflow c) (null spot))
        (unless spot
          (setf ok nil)
          (when final
            (report :error (format nil
                                   "the callout ~s has no room beside its anchor; shorten it or the slide"
                                   (callout-text c)))
            (destructuring-bind (side x y tail tail-box) (nth 3 candidates)
              (setf spot
                    (list side x
                          (max (second bounds) (min (- (fourth bounds) h) y))
                          tail tail-box)))))
        (when spot
          (destructuring-bind (side x y tail tail-box) spot
            (setf (callout-side c) side (callout-x c) x (callout-y c) y
                  (callout-tail c) tail)
            (obstacle (list x y (+ x w) (+ y h)))
            (obstacle tail-box)))))))

(defun say-steps (k)
  "The callout sizes tried at body step K: none larger than the body text."
  (let ((body (step-at *body-steps* k)))
    (remove-if (lambda (s) (> s body)) *say-steps*)))

(defun place-at (slide fit k s width final)
  "Lays out SLIDE's body at step K and places its callouts at S px; true when every one
found room."
  (let* ((regions (regions slide width))
         (rwidth (second (car regions)))
         (c (body-code-size (region-blocks (slide-blocks slide)) k rwidth))
         (top (fit-body-top fit))
         (height (fit-body-height fit))
         (callouts (slide-callouts slide))
         (*slide-callouts* callouts)
         (*obstacles* nil))
    (dolist (co callouts)
      (multiple-value-bind (w h)
          (bubble-size co s (say-max-width co width rwidth))
        (setf (callout-size co) s (callout-width co) w (callout-height co) h
              (callout-fragments co) nil)))
    ;; An image gives up a strip on its right to its bubbles.
    (dolist (b (region-blocks (slide-blocks slide)))
      (when (figure-p b)
        (let ((widths
               (loop for co in callouts
                     when (eq (callout-target co) b)
                     collect (callout-width co))))
          (setf (figure-strip b)
                (and widths (+ (reduce #'max widths) *tail-reach*))))))
    (loop for (blocks w) in regions
          for x from *margin-x* by (+ rwidth *column-gap*)
          do (blocks-geometry blocks k c x top w height))
    (place-callouts (remove-if-not #'callout-fragments callouts)
                    (list *margin-x* top (+ *margin-x* width) (+ top height))
                    final)))

(defun fit-callouts (slide fit k width)
  "The body step at which SLIDE's callouts all find room: K, or a smaller size when
they need the room shorter lines leave, at the largest callout size that does. When
none does, K, with the callouts placed at the smallest size and the ones without room
reported. Leaves the callouts placed; one whose anchor is not laid out is dropped."
  (when (slide-callouts slide)
    (setf k
          (or (loop for j from k below (length *body-steps*)
                    thereis
                      (and (some (lambda (s) (place-at slide fit j s width nil))
                                 (say-steps j)) j))
              (progn
                (place-at slide fit k (car (last (say-steps k))) width t)
                k)))
    (setf (slide-callouts slide)
          (remove-if-not #'callout-fragments (slide-callouts slide))))
  k)
