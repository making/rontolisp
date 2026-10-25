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
(defparameter *page-image-width* 1200 "The page image (page-image.lisp), the canvas at 5/8.")
(defparameter *page-image-height* 675)
(defparameter *margin-x* 112)
(defparameter *margin-top* 80)
(defparameter *margin-bottom* 72)
(defparameter *title-gap* 48)
(defparameter *column-gap* 80)
(defparameter *meta-size* 40)
(defparameter *page-number-size* 24)
(defparameter *footer-gap* 48)
(defparameter *kicker-size* 32)
(defparameter *kicker-lh* 40)
(defparameter *logo-height* 160)
(defparameter *qr-steps* '(320 280 240 200))
(defparameter *qr-caption-size* 24)
(defparameter *qr-caption-gap* 8)
(defparameter *qr-clearance* 24 "The free margin around the QR code.")
(defparameter *source-size* 32)
(defparameter *source-lh* 40)
(defparameter *source-gap* 24 "Between the body and the source line under it.")
(defparameter *progress-height* 6 "The progress bar along the bottom edge.")
(defparameter *title-block-gap*
  48
  "Between the logo, the title and the lines under it.")
(defparameter *tools-box*
  '(320 48 12)
  "Width, height and inset of the Run and Reset buttons in a runnable code block's
top right corner.")

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

;;; The layout result of one slide. LEFT and WIDTH place the text region, the content
;;; width of the safe area but on a split slide; BODY-TOP and BODY-HEIGHT place the body
;;; box; the sizes are px. BLEED is (FIGURE LEFT WIDTH), the image of a full or split
;;; slide and the part of the canvas width it covers, top to bottom. QR places the title
;;; slide's QR code and the URL under it as (LEFT TOP SIZE WIDTH ALIGN), WIDTH being the
;;; wider of the two. OVERFLOW is true when the slide does not fit even at the hard floor.
(defstruct fit
  layout
  left
  width
  bleed
  title-size
  title-lines
  body-top
  body-height
  body-size
  code-size
  stmt-size
  qr
  overflow)

;;; --- runs ------------------------------------------------------------------------

(defun prepare-list (b)
  (dolist (it (bullets-items b))
    (setf (item-run it)
          (make-text-run (item-inlines it) :bold (item-current it)))
    (when (item-sublist it) (prepare-list (item-sublist it)))))

(defparameter *runnable-langs* '("lisp" "scheme" "clojure"))

(defun runnable-lang-p (b)
  "True when the code block B is in a language the runtime runs. Code in any other
language is never run, whatever its :run says."
  (and (code-block-lang b)
       (member (string-downcase (code-block-lang b)) *runnable-langs*
               :test #'string=)))

(defun prepare-blocks (blocks runtime)
  "Attach a run to every piece of text in BLOCKS, and mark the code RUNTIME runs."
  (dolist (b blocks)
    (cond ((para-p b) (setf (para-run b) (make-text-run (para-inlines b))))
          ((bullets-p b) (prepare-list b))
          ((column-row-p b)
           (dolist (col (column-row-columns b))
             (prepare-blocks (column-blocks col) runtime)))
          ((code-block-p b)
           (setf (code-block-runnable b)
                 (and (runnable-lang-p b) (code-block-run b)
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
          ((diagram-p b)
           (setf (diagram-runs b) (mapcar #'make-text-run (diagram-labels b)))
           (let ((d (diagram-datum b)))
             (cond ((flow-graph-p d)
                    (setf (flow-graph-edge-runs d)
                          (mapcar #'make-text-run (flow-graph-edge-labels d))))
                   ((chart-p d)
                    (setf (chart-series-runs d)
                          (mapcar #'make-text-run (chart-series d)))))))
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

(defun full-p (slide)
  (let ((fit (slide-fit slide))) (and fit (equal (fit-layout fit) "full"))))

(defun page-number-shown-p (deck slide)
  "Whether SLIDE shows its page number: not on the title slide, nor over a full-bleed
image, where it would be text on a picture."
  (and (deck-page-numbers deck) (not (eq (slide-kind slide) :title))
       (not (full-p slide))))

(defun footer-shown-p (deck slide)
  "Whether SLIDE carries the deck's footer line: not where it would show no page number."
  (and (deck-footer deck) (eq (slide-footer slide) :deck)
       (not (eq (slide-kind slide) :title)) (not (full-p slide))))

(defun footer-width (deck)
  "The px the footer line has: the content width less the widest page number and a gap."
  (if (deck-page-numbers deck)
      (- (content-width deck)
         (run-full-width
          (make-text-run
           (list (make-string (length (format nil "~d" (length (deck-slides deck))))
                              :initial-element #\9)))
          *page-number-size*)
         *footer-gap*)
      (content-width deck)))

(defun prepare-runs (deck slide)
  (when (and (deck-footer deck) (null (deck-footer-run deck)))
    (setf (deck-footer-run deck) (make-text-run (deck-footer deck))))
  (when (slide-title slide)
    (setf (slide-title-run slide)
          (make-text-run (list (slide-title slide)) :bold t)))
  (when (eq (slide-kind slide) :title)
    (setf (slide-meta slide)
          (loop for text in (list (deck-author deck) (deck-date deck))
                when text
                collect (list text (make-text-run (list text))))))
  (when (stringp (slide-kicker slide))
    (setf (slide-kicker-run slide)
          (make-text-run (list (slide-kicker slide)) :bold t)))
  (when (slide-source slide)
    (setf (slide-source-run slide) (make-text-run (slide-source slide))))
  (prepare-blocks (slide-blocks slide) (deck-runtime deck))
  (dolist (c (slide-callouts slide))
    (setf (callout-run c) (make-text-run (list (callout-text c))))))

;;; --- layout selection --------------------------------------------------------------

(defparameter *layouts*
  '("standard" "statement" "code" "image" "table" "quote" "columns" "full"
    "image-left" "image-right"))

(defparameter *split-layouts* '("image-left" "image-right"))

(defun lone-image-p (slide)
  "True when SLIDE is one image to fill the canvas: no title, source or callout, and an
(image ...), (video ...) or (frame ...) without a caption as its only block. Text on a
picture is left out, since no lint can check its contrast."
  (let ((blocks (slide-blocks slide)))
    (and (null (slide-title slide)) (null (slide-source slide))
         (null (slide-callouts slide)) (= (length blocks) 1)
         (figure-p (car blocks)) (null (figure-caption (car blocks))))))

(defun split-figure (slide)
  "The image a split slide sets beside its text: its one (image ...), (video ...) or
(frame ...) outside columns, when that has no caption and no callout points at it; nil
otherwise."
  (let ((figures (remove-if-not #'figure-p (slide-blocks slide))))
    (and (= (length figures) 1)
         (null (figure-caption (car figures)))
         (notany (lambda (c) (eq (callout-target c) (car figures)))
                 (slide-callouts slide))
         (car figures))))

(defun automatic-layout (slide)
  "The layout SLIDE's shape asks for. A statement's centered, balanced lines are not where
the model puts them, so a slide with callouts never takes it."
  (let ((blocks (slide-blocks slide)))
    (cond ((and (null (slide-title slide)) (= (length blocks) 1)
                (para-p (car blocks)) (not (para-lead (car blocks)))
                (null (slide-callouts slide)))
           "statement")
          ((lone-image-p slide) "full")
          ((some #'column-row-p blocks) "columns")
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
          ((equal requested "full")
           (report :warning
                   "the full layout needs an (image ...), (video ...) or (frame ...) without :caption as the only block, and no title, source or callout")
           automatic)
          ((member requested *split-layouts* :test #'equal)
           (if (split-figure slide)
               requested
               (progn
                 (report :warning (format nil
                                   "the ~a layout needs one (image ...), (video ...) or (frame ...) outside columns, without :caption or a callout on it"
                                   requested))
                 automatic)))
          ((member "statement" (list requested automatic) :test #'equal)
           (report :warning
                   "the statement layout needs exactly one (p ...), no title and no callouts")
           automatic)
          ((member "columns" (list requested automatic) :test #'equal)
           (report :warning "the columns layout needs (column ...) blocks")
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

(defun console-p (b)
  (equal (and (code-block-lang b) (string-downcase (code-block-lang b)))
         "console"))

(defun prompt-line-p (line)
  "True for a command line of a console transcript: a $ prompt, then the command."
  (and (> (length line) 0) (char= (char line 0) #\$)
       (or (= (length line) 1) (char= (char line 1) #\Space))))

(defun wrapping-line-p (b line)
  "True for a line of B that wraps at the panel's edge: the output in a console
transcript, as a terminal wraps it. Every other line of code is set on one line."
  (and (console-p b) (not (prompt-line-p line))))

(defun fixed-lines (b)
  (remove-if (lambda (line) (wrapping-line-p b line)) (code-block-lines b)))

(defun lines-width (lines c)
  (reduce #'max (mapcar (lambda (line) (mono-width line c)) lines)
          :initial-value 0))

(defun tools-width ()
  "The narrowest panel the Run and Reset buttons fit in, inset on both sides."
  (destructuring-bind (tw th inset) *tools-box*
    (declare (ignore th))
    (+ tw (* 2 inset))))

(defun code-fits-p (b c width)
  (and (<= (lines-width (fixed-lines b) c) (- width (* 2 (code-pad c))))
       (or (not (code-block-runnable b)) (<= (tools-width) width))))

(defun line-starts (b line c width)
  "The index of the first character of each row LINE of the code block B takes at C px
in a panel WIDTH px wide."
  (if (wrapping-line-p b line)
      (mono-wrap line c (- width (* 2 (code-pad c))))
      (list 0)))

(defun code-rows (b c width)
  "The rows of the code block B at C px in a panel WIDTH px wide, as (LINE START END)
lists: a line wrapping at the panel's edge takes several."
  (loop for line in (code-block-lines b)
        nconc
          (loop for (start next) on (line-starts b line c width)
                collect (list line start (or next (length line))))))

(defun tools-band-p (b c width)
  "True when the Run and Reset buttons of the code block B, set at C px in a panel WIDTH
px wide, would come within the panel's padding of a row beside them. The rows then start
below the buttons (CODE-TOP-PAD)."
  (and (code-block-runnable b)
       (destructuring-bind (tw th inset) *tools-box*
         (let ((pad (code-pad c)))
           (loop for (line start end) in (code-rows b c width)
                 for top from pad by (code-lh c)
                 while (< top (+ inset th))
                 thereis
                   (> (+ pad (mono-width (subseq line start end) c) pad)
                      (- width inset tw)))))))

(defun code-top-pad (b c width)
  "The padding above the first row of the code block B: the panel's, or the buttons'
inset and height and the panel's below them when they would meet a row."
  (if (tools-band-p b c width)
      (destructuring-bind (tw th inset) *tools-box*
        (declare (ignore tw))
        (+ inset th (code-pad c)))
      (code-pad c)))

(defun code-height (b c width)
  (+ (* (max 1 (length (code-rows b c width))) (code-lh c))
     (code-top-pad b c width) (code-pad c)))

(defun code-size-at (blocks k width)
  "The code size at body step K: the step's size, or a smaller one when a line is too
wide for its block or column; nil when no step fits."
  (loop for j from k below (length *code-steps*)
        do
          (let ((c (nth j *code-steps*)))
            (when (every (lambda (b)
                           (cond ((code-block-p b) (code-fits-p b c width))
                                 ((column-row-p b)
                                  (nth-value 1 (row-widths b k c width)))
                                 (t t))) blocks)
              (return c)))))

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

(defun level-widths (floors caps avail)
  "Widths summing to AVAIL, each at least its floor and at most its cap (nil for none),
as even as that allows: the columns not held by a bound share one level. The rounding
remainder goes 1 px each to the leftmost columns at the level. The floors must sum to at
most AVAIL."
  (flet ((at (level)
           (loop for lo in floors
                 for hi in caps
                 collect (let ((w (max lo level))) (if hi (min hi w) w)))))
    (let ((level 0) (top avail))
      ;; The largest level whose widths sum to at most AVAIL.
      (loop while (< level top)
            do
              (let ((mid (ceiling (+ level top) 2)))
                (if (<= (reduce #'+ (at mid)) avail)
                    (setf level mid)
                    (setf top (1- mid)))))
      (let ((rest (- avail (reduce #'+ (at level)))))
        (loop for lo in floors
              for hi in caps
              for w in (at level)
              collect
                (if (and (> rest 0) (<= lo level) (or (null hi) (< level hi)))
                    (progn
                      (decf rest)
                      (1+ w))
                    w))))))

(defun column-widths (tb s avail)
  "Column widths in px at size S filling AVAIL px, or nil when the minima exceed it."
  (multiple-value-bind (mins maxs) (column-bounds tb s)
    (let* ((ncol (length mins))
           (sum-min (reduce #'+ mins))
           (sum-max (reduce #'+ maxs)))
      (cond ((<= sum-max avail)
             (level-widths (coerce maxs 'list) (make-list ncol) avail))
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

(defun diagram-fit (b k width)
  "The label size and drawing of the diagram B at step K, stepping further down while it
is wider than WIDTH; nil when no step fits."
  (loop for j from (min k (1- (length *diagram-steps*))) below
          (length *diagram-steps*)
        do
          (let* ((s (nth j *diagram-steps*)) (d (draw-diagram b s width)))
            (when (<= (drawing-width d) width) (return (values s d))))))

(defun diagram-layout (b k width)
  "The label size and drawing B is set at for body step K: the smallest size when nothing
fits."
  (multiple-value-bind (s d) (diagram-fit b k width)
    (if s
        (values s d)
        (let ((s (car (last *diagram-steps*))))
          (values s (draw-diagram b s width))))))

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

(defun run-bounds (run s) (values (run-min-width run s) (run-full-width run s)))

(defun list-bounds (b s)
  (let ((indent (list-indent s)) (lo 0) (hi 0))
    (dolist (it (bullets-items b) (values lo hi))
      (multiple-value-bind (ilo ihi) (run-bounds (item-run it) s)
        (setf lo (max lo (+ indent ilo)) hi (max hi (+ indent ihi))))
      (when (item-sublist it)
        (multiple-value-bind (slo shi) (list-bounds (item-sublist it) s)
          (setf lo (max lo (+ indent slo)) hi (max hi (+ indent shi))))))))

(defun block-bounds (b k c)
  "The narrowest width the block B can take at body step K and code size C, breaking
neither a unit of text nor a line of code, and the width it takes set unwrapped; nil for
the second of an image, which takes whatever width it is given."
  (let ((s (step-at *body-steps* k)))
    (cond ((para-p b) (run-bounds (para-run b) s))
          ((bullets-p b) (list-bounds b s))
          ((quotation-p b)
           (multiple-value-bind (lo hi) (run-bounds (quotation-run b) s)
             (when (quotation-cite-run b)
               (multiple-value-bind (clo chi)
                   (run-bounds (quotation-cite-run b) s)
                 (setf lo (max lo clo) hi (max hi chi))))
             (values (+ lo (quote-indent s)) (+ hi (quote-indent s)))))
          ((code-block-p b)
           ;; Runnable code is at least as wide as its Run and Reset buttons.
           (let ((pad (* 2 (code-pad c)))
                 (tools (if (code-block-runnable b) (tools-width) 0)))
             (values (max tools (+ pad (lines-width (fixed-lines b) c)))
                     (max tools (+ pad (lines-width (code-block-lines b) c))))))
          ((figure-p b)
           (values (if (figure-caption-run b)
                       (run-min-width (figure-caption-run b) s)
                       0) nil))
          ((table-block-p b)
           (multiple-value-bind (mins maxs)
               (column-bounds b (step-at *table-steps* k))
             (values (reduce #'+ mins) (reduce #'+ maxs))))
          ((diagram-p b) (diagram-bounds b (step-at *diagram-steps* k)))
          ((formula-p b) (let ((w (formula-width b s))) (values w w)))
          (t (values 0 0)))))

(defun row-widths (row k c width)
  "The widths of the columns of ROW at body step K and code size C in WIDTH px, and
whether they fit. Each column is at least as wide as its blocks need (BLOCK-BOUNDS).
When every column fits unwrapped, each gets at least that and the rest is spread evenly,
as a table's columns are; otherwise a column that fits unwrapped in less than an even
share takes only that, and the others share the rest evenly. When the minima exceed
WIDTH, the minima."
  (let* ((cols (column-row-columns row))
         (avail (- width (* *column-gap* (1- (length cols)))))
         (mins nil)
         (maxs nil))
    (dolist (col cols)
      (let ((lo 0) (hi 0))
        (dolist (b (column-blocks col))
          (multiple-value-bind (blo bhi) (block-bounds b k c)
            (setf lo (max lo blo) hi (and hi bhi (max hi bhi)))))
        (push lo mins)
        (push hi maxs)))
    (setf mins (nreverse mins) maxs (nreverse maxs))
    (cond ((> (reduce #'+ mins) avail) (values mins nil))
          ((and (every #'identity maxs) (<= (reduce #'+ maxs) avail))
           (values (level-widths maxs (make-list (length maxs)) avail) t))
          (t (values (level-widths mins maxs avail) t)))))

(defun block-height (b k c width body-height)
  "The height of the block B at body step K and code size C (nil when no code size
fits), and whether it fits its width. An image is counted at a third of the body, a row
of columns as its tallest column."
  (let ((s (step-at *body-steps* k)) (h 0) (ok t))
    (cond ((para-p b) (setf h (* (run-lines (para-run b) s width) (body-lh s))))
          ((quotation-p b) (setf h (quote-height b s width)))
          ((bullets-p b) (setf h (bullets-height b s width)))
          ((code-block-p b)
           (if (and c (code-fits-p b c width))
               (setf h (code-height b c width))
               (setf ok nil)))
          ((column-row-p b)
           (if c
               (loop for col in (column-row-columns b)
                     for w in (row-widths b k c width)
                     do
                       (multiple-value-bind (ch cok)
                           (blocks-height (column-blocks col) k c w body-height)
                         (setf h (max h ch))
                         (unless cok (setf ok nil))))
               (setf ok nil)))
          ((figure-p b)
           (setf h (+ (floor body-height 3) (caption-height b s width))))
          ((table-block-p b)
           (multiple-value-bind (ts widths th) (table-fit b k width)
             (declare (ignore widths))
             (if ts (setf h th) (setf ok nil))))
          ((diagram-p b)
           (multiple-value-bind (ds d) (diagram-fit b k width)
             (if ds (setf h (drawing-height d)) (setf ok nil))))
          ((formula-p b)
           ;; A display formula is set at the body size, so it steps down with it.
           (setf h (formula-height b s))
           (when (> (formula-width b s) width) (setf ok nil))))
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

(defun fit-centered (run width max-lines &optional (height (safe-height)))
  "The statement-scale size for RUN: the largest step with at most MAX-LINES lines that
fits HEIGHT px, the safe area by default. The second value is true on overflow."
  (dolist (s *statement-steps*)
    (let ((lines (run-lines run s width)))
      (when (and (<= lines max-lines) (<= (* lines (statement-lh s)) height))
        (return-from fit-centered (values s nil)))))
  (let* ((s (car (last *statement-steps*))) (lines (run-lines run s width)))
    (if (> (* lines (statement-lh s)) height)
        (progn
          (report
           :error "the text does not fit the slide even at the smallest size")
          (values s t))
        (progn
          (report :warning (format nil
                                   "the text takes ~d lines at ~dpx; shorten it"
                                   lines s))
          (values s nil)))))

(defun regions (blocks k c width)
  "The regions of a body of BLOCKS WIDTH px wide at body step K and code size C, as
(BLOCKS WIDTH) lists: the body itself, then each column of its rows."
  (cons (list blocks width)
        (loop for b in blocks
              when (column-row-p b)
              nconc
                (loop for col in (column-row-columns b)
                      for w in (row-widths b k c width)
                      collect (list (column-blocks col) w)))))

(defun fit-body (slide fit width)
  "Choose the largest body step at which the body fits, its rows of columns included.
Columns share the slide's sizes, so side-by-side text matches."
  (let* ((blocks (slide-blocks slide))
         (body-height (fit-body-height fit))
         (k
          (loop for k from 0 below (length *body-steps*)
                do
                  (multiple-value-bind (h ok) (blocks-height blocks k
                                               (code-size-at blocks k width)
                                               width body-height)
                    (when (and ok (<= h body-height)) (return k))))))
    (unless k
      (report :error
              "the content does not fit the slide even at the smallest sizes; split the slide")
      (setf (fit-overflow fit) t)
      (setf k (1- (length *body-steps*))))
    (setf k (fit-callouts slide fit k width))
    (let ((c (body-code-size blocks k width)))
      (setf (fit-body-size fit) (step-at *body-steps* k) (fit-code-size fit) c)
      (dolist (b blocks)
        (when (column-row-p b)
          (setf (column-row-widths b) (row-widths b k c width))))
      (loop for (rblocks rwidth) in (regions blocks k c width)
            do
              (dolist (b rblocks)
                (when (code-block-p b)
                  (setf (code-block-band b) (tools-band-p b c rwidth)))
                (when (table-block-p b)
                  (multiple-value-bind (s widths) (table-layout b k rwidth)
                    (setf (table-block-size b) s (table-block-widths b) widths
                          (table-block-aligns b)
                          (column-aligns b (table-columns b)))))
                (when (diagram-p b)
                  (multiple-value-bind (s d) (diagram-layout b k rwidth)
                    (setf (diagram-size b) s (diagram-drawing b) d))))))))

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

(defun title-extras-height (deck slide width)
  "The height the title slide's logo and the lines under its title take in WIDTH px."
  (+ (if (deck-logo deck) (+ *logo-height* *title-block-gap*) 0)
     (if (slide-meta slide)
         (+ *title-block-gap*
            (loop for (nil run) in (slide-meta slide)
                  sum
                    (* (run-lines run *meta-size* width)
                       (body-lh *meta-size*))))
         0)))

(defun title-rows (deck slide s)
  "The rows of the title slide at statement size S, top to bottom, as the centered column
sets them: (KIND TOP HEIGHT WIDTH RUN START END), KIND :logo, :title or :meta, a line
being characters START below END of RUN. The logo counts as the full width, its own
being unknown, and each title line as wide as the widest, which text-wrap: balance may
even the others up to."
  (let* ((width (content-width deck))
         (run (slide-title-run slide))
         (starts (wrap-run run s width))
         (widest
          (loop for (a b) on starts
                maximize
                  (milli-px->px
                   (run-span-width run a (or b (length (run-text run))) s))))
         (rows nil))
    ;; Rows as (KIND HEIGHT WIDTH RUN START END), a gap of no kind, bottom to top.
    (when (deck-logo deck)
      (push (list :logo *logo-height* width) rows)
      (push (list nil *title-block-gap*) rows))
    (loop for (a b) on starts
          do (push (list :title (statement-lh s) widest run a
                         (or b (length (run-text run))))
                   rows))
    (when (slide-meta slide)
      (push (list nil *title-block-gap*) rows)
      (loop for (nil mrun) in (slide-meta slide)
            do
              (let ((mstarts (wrap-run mrun *meta-size* width)))
                (loop for (a b) on mstarts
                      do
                        (let ((end (or b (length (run-text mrun)))))
                          (push (list :meta (body-lh *meta-size*)
                                      (milli-px->px
                                       (run-span-width mrun a end *meta-size*))
                                      mrun a end)
                                rows))))))
    (setf rows (nreverse rows))
    (let ((y
           (+ *margin-top*
              (floor (- (safe-height) (reduce #'+ (mapcar #'second rows))) 2))))
      (loop for row in rows
            when (first row)
            collect (list* (first row) y (cdr row))
            do (incf y (second row))))))

(defun title-rects (deck slide s)
  "The rectangles the title slide's logo, title and lines under it take at statement size
S (TITLE-ROWS)."
  (let ((cx (+ *margin-x* (floor (content-width deck) 2))))
    (loop for (nil y h w) in (title-rows deck slide s)
          when (> w 0)
          collect (list (- cx (ceiling w 2)) y (+ cx (ceiling w 2)) (+ y h)))))

(defun qr-lh () (ceiling (* *qr-caption-size* 14) 10))

(defun qr-corner (deck slide s)
  "The place of the title slide's QR code and the URL under it, as FIT-QR holds it: the
largest step and the first corner of the safe area, of bottom right, bottom left, top
right and top left, where the two cover none of the text at statement size S. The text
keeps its place whatever the corner. The smallest step at the bottom right, reported,
when every corner is taken."
  (let* ((cw
          (run-full-width (make-text-run (list (deck-url deck)))
                          *qr-caption-size*))
         (rects (title-rects deck slide s))
         (c *qr-clearance*)
         (lo *margin-x*)
         (hi (+ *margin-x* (content-width deck)))
         (bottom (+ *margin-top* (safe-height))))
    (flet ((place (q corner)
             (let* ((right (member corner '(:bottom-right :top-right)))
                    (w (max q cw))
                    (h (+ q *qr-caption-gap* (qr-lh)))
                    (x (if right (- hi w) lo))
                    (y
                     (if (member corner '(:bottom-right :bottom-left))
                         (- bottom h)
                         *margin-top*))
                    (qx (if right (- hi q) lo))
                    (cx (if right (- hi cw) lo))
                    (cy (+ y q *qr-caption-gap*)))
               (list x y q w (if right "right" "left")
                     ;; The code and the caption, each with its clearance.
                     (list (list (- qx c) (- y c) (+ qx q c) (+ y q c))
                      (list (- cx c) (- cy c) (+ cx cw c) (+ cy (qr-lh) c)))))))
      (dolist (q *qr-steps*)
        (dolist (corner '(:bottom-right :bottom-left :top-right :top-left))
          (let ((p (place q corner)))
            (when (notany (lambda (r)
                            (some (lambda (box) (rects-meet-p box r))
                                  (sixth p))) rects)
              (return-from qr-corner (subseq p 0 5))))))
      (report :warning
              "the QR code covers text of the title slide in every corner; shorten the title")
      (subseq (place (car (last *qr-steps*)) :bottom-right) 0 5))))

(defun source-height (slide width)
  "The height SLIDE's source line takes from the room above it, the gap included; 0
without one. The line stands at the bottom of the safe area, wrapped in the WIDTH of the
text region."
  (if (slide-source-run slide)
      (+ *source-gap*
         (* *source-lh* (run-lines (slide-source-run slide) *source-size* width)))
      0))

(defun share-width (deck figure)
  "The px of the canvas width the image of a split slide takes: its :share, half by
default."
  (destructuring-bind (n d) (or (figure-share figure) '(1 2))
    (floor (* (canvas-width deck) n) d)))

(defun place-bleed (deck slide fit)
  "Takes the image of the full or split slide SLIDE out of its blocks into FIT, with the
part of the canvas width it covers, and narrows the text region of a split slide to the
rest less the side margins."
  (let* ((layout (fit-layout fit))
         (full (equal layout "full"))
         (cw (canvas-width deck))
         (figure (if full (car (slide-blocks slide)) (split-figure slide)))
         (iw (if full cw (share-width deck figure))))
    (setf (slide-blocks slide) (remove figure (slide-blocks slide))
          (fit-bleed fit)
          (list figure (if (equal layout "image-right") (- cw iw) 0) iw))
    (unless full
      (setf (fit-width fit) (- cw iw (* 2 *margin-x*)))
      (when (equal layout "image-left") (setf (fit-left fit) (+ iw *margin-x*))))
    (when (and full (figure-share figure))
      (report-image-option figure ":share"))))

(defun report-image-option (figure option)
  (report :warning (format nil "~a ~a takes ~a only on ~a slide" (figure-noun figure)
                           (figure-src figure) option
                           (if (equal option ":fit")
                               "a :full, :image-left or :image-right"
                               "an :image-left or :image-right"))))

(defun fit-slide (deck slide)
  "Lay out SLIDE and store the result in its FIT slot."
  (prepare-runs deck slide)
  (let* ((layout (choose-layout slide))
         (fit
          (make-fit :layout layout :left *margin-x* :width (content-width deck))))
    (setf (slide-fit slide) fit)
    ;; Its one block, a step, would leave the slide empty until the first advance.
    (when (and (member layout '("full" "statement") :test #'equal)
               (block-step (car (slide-blocks slide)) (slide-steps slide)))
      (report :error ":step on the only block of an untitled slide would open it empty")
      (setf (slide-steps slide) nil (slide-step-count slide) 0))
    (when (or (equal layout "full")
              (member layout *split-layouts* :test #'equal))
      (place-bleed deck slide fit))
    ;; An image left in the body is fitted into its box, which :fit and :share leave alone.
    (dolist (b (region-blocks (slide-blocks slide)))
      (when (figure-p b)
        (when (figure-fit b) (report-image-option b ":fit"))
        (when (figure-share b) (report-image-option b ":share"))))
    (fit-region deck slide fit)
    fit))

(defun fit-region (deck slide fit)
  "Fits SLIDE's text into the region FIT gives it, by FIT's layout; a full slide has none."
  (let* ((layout (fit-layout fit))
         (width (fit-width fit))
         (source (source-height slide width)))
    (cond ((equal layout "full"))
          ((equal layout "title")
           (multiple-value-bind (s overflow) (fit-centered
                                              (slide-title-run slide) width 2
                                              (- (safe-height)
                                                 (title-extras-height deck slide
                                                                      width)))
             (setf (fit-stmt-size fit) s (fit-overflow fit) overflow)
             (when (deck-url deck)
               (if (qr-version (utf-8-octets (deck-url deck)))
                   (setf (fit-qr fit) (qr-corner deck slide s))
                   (report :error (format nil
                                   "the :url is ~d bytes; a QR code holds ~d at most"
                                   (length (utf-8-octets (deck-url deck)))
                                   *qr-max-bytes*))))))
          ((equal layout "section")
           (when (slide-title-run slide)
             (setf (fit-stmt-size fit)
                   (fit-centered (slide-title-run slide) width 2))))
          ((equal layout "statement")
           (let ((run
                  (make-text-run (para-inlines (car (slide-blocks slide)))
                                 :bold t)))
             (setf (para-run (car (slide-blocks slide))) run)
             (multiple-value-bind (s overflow)
                 (fit-centered run width 3 (- (safe-height) source))
               (setf (fit-stmt-size fit) s (fit-overflow fit) overflow))))
          (t (let ((top *margin-top*))
               (when (slide-title-run slide)
                 (multiple-value-bind (s lines) (fit-title slide width)
                   (setf (fit-title-size fit) s (fit-title-lines fit) lines)
                   (incf top (+ (* lines (title-lh s)) *title-gap*))))
               (setf (fit-body-top fit) top (fit-body-height fit)
                     (- *canvas-height* *margin-bottom* top source))
               (fit-body slide fit width))))))

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
                      ;; The text before the anchor keeps its spaces: they
                      ;; stand between it and the anchor.
                      (let ((before (run-span-width run start lo s t)))
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
  "Records the rows of the code block B at C px from X, Y, its Run and Reset buttons,
and the fragments of a callout naming a piece of it with :at; returns its height."
  (let ((pad (code-pad c))
        (top-pad (code-top-pad b c width))
        (lh (code-lh c))
        (rows (code-rows b c width)))
    (loop for (line start end) in rows
          for top from (+ y top-pad) by lh
          do
            (let ((w (mono-width (subseq line start end) c)))
              (when (> w 0)
                (obstacle (list (+ x pad) top (+ x pad w) (+ top lh))))))
    (when (code-block-runnable b)
      (destructuring-bind (tw th inset) *tools-box*
        (obstacle
         (list (- (+ x width) inset tw) (+ y inset) (- (+ x width) inset)
               (+ y inset th)))))
    (dolist (co *slide-callouts*)
      (when (and (eq (callout-target co) b) (callout-at co))
        (let* ((at (callout-at co))
               (line (find-if (lambda (l) (search at l)) (code-block-lines b)))
               (a (search at line))
               (e (+ a (length at))))
          ;; One fragment per row the text takes.
          (setf (callout-fragments co)
                (loop for (l start end) in rows
                      for top from (+ y top-pad) by lh
                      when (and (eq l line) (< (max a start) (min e end)))
                      collect
                        (list
                         (+ x pad (mono-width (subseq l start (max a start)) c))
                         top
                         (+ x pad (mono-width (subseq l start (min e end)) c))
                         (+ top lh)))))))
    (code-height b c width)))

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

(defun diagram-geometry (b s d x y)
  "Records the drawing D of the diagram B, set at S px from X, Y: every shape but a chart's
gridlines and gaps, and the lines of every flow box, arrow and chart label. A callout
naming a label with :at points at a box or an arrow's label as a whole, at an atom, at a
chart's row (its mark on a chart of one series, else its label) or series (its name in
the legend); one inside a label points where naming the label would, and one around a
chart's value at that value's mark."
  (let ((labels nil)
        (px (dg-pad-x s))
        (chart (and (chart-p (diagram-datum b)) (diagram-datum b))))
    (labels ((point (co box)
               ;; The anchor BOX, from the slide's top left; the player finds it from the
               ;; diagram's.
               (setf (callout-fragments co) (list box)
                     (callout-diagram-box co) (list (- (first box) x) (- (second box) y)
                                                    (- (third box) x) (- (fourth box) y))))
             (target (key)
               (let ((r (cdr (assoc key (drawing-targets d) :test #'equal))))
                 (list (+ x (first r)) (+ y (second r)) (+ x (third r)) (+ y (fourth r)))))
             (label-run (run w lx ly align box)
               (run-geometry run s w (dg-lh s) lx ly align)
               ;; Its text lies inside the box, which leaves a bubble no room beside it.
               (when box
                 (dolist (anchor (run-anchors run)) (point (first anchor) box)))))
      (dolist (shape (drawing-shapes d))
        (destructuring-bind (l top r bottom) (shape-box shape)
          (let ((box (list (+ x l) (+ y top) (+ x r) (+ y bottom))))
            (unless (chrome-p shape) (obstacle box))
            (case (car shape)
              (:box
               (destructuring-bind (bx by w h i pad) (cdr shape)
                 (declare (ignore h))
                 (label-run (nth i (diagram-runs b)) (- w (* 2 px)) (+ x bx px)
                            (+ y by pad) "center" box))
               (push box labels))
              (:edge-label
               (destructuring-bind (bx by w h j) (cdr shape)
                 (declare (ignore h))
                 (label-run (nth j (flow-graph-edge-runs (diagram-datum b))) w
                            (+ x bx) (+ y by) "center" box)))
              (:category
               (destructuring-bind (bx by w h j align) (cdr shape)
                 (declare (ignore h))
                 (label-run (nth j (diagram-runs b)) w (+ x bx) (+ y by) align
                            (target (list :category j)))))
              (:legend
               (destructuring-bind (bx by w h i) (cdr shape)
                 (declare (ignore h))
                 (label-run (nth i (chart-series-runs chart)) w (+ x bx) (+ y by) nil
                            (target (list :series i)))))
              (:atom (push box labels))))))
      (setf labels (nreverse labels))
      (when chart
        (dolist (m (chart-marks chart))
          (point (first m) (target (list :mark (second m) (third m))))))
      (dolist (co *slide-callouts*)
        (when (and (eq (callout-target co) b) (callout-at co))
          (let ((i (label-index (diagram-label-texts b) (callout-at co)))
                (rows (length (diagram-labels b))))
            (when i
              (point co (cond ((not chart) (nth i labels))
                              ((< i rows) (target (list :category i)))
                              (t (target (list :series (- i rows)))))))))))))

(defun grows-p (b)
  "True for a block that takes a share of the room the others leave: an image, or a row
of columns holding one."
  (or (figure-p b)
      (and (column-row-p b)
           (some (lambda (col) (some #'figure-p (column-blocks col)))
                 (column-row-columns b)))))

(defun blocks-geometry (blocks k c x y width height)
  "Records the geometry of BLOCKS, a region WIDTH x HEIGHT px from X, Y at body step K
and code size C, and the fragments of the callouts pointing at its blocks. Images, and
rows of columns holding one, share what the other blocks leave, as the stylesheet's flex
layout gives it to them."
  (let* ((s (step-at *body-steps* k))
         (gap (block-gap s))
         (growing (count-if #'grows-p blocks))
         (rest
          (- height (* gap (max 0 (1- (length blocks))))
             (loop for b in blocks
                   unless (grows-p b)
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
              ((diagram-p b)
               ;; Centered in its region, as the stylesheet centers it.
               (multiple-value-bind (ds d) (diagram-layout b k width)
                 (let ((dx (+ x (floor (- width (drawing-width d)) 2))))
                   (diagram-geometry b ds d dx top)
                   (setf h (drawing-height d) box
                         (list dx top (+ dx (drawing-width d))
                               (+ top (drawing-height d)))))))
              ((formula-p b)
               ;; Centered in its region, as the stylesheet centers it.
               (let* ((w (min width (formula-width b s)))
                      (dx (+ x (floor (- width w) 2))))
                 (setf h (formula-height b s) box (list dx top (+ dx w) (+ top h)))
                 (obstacle box)))
              ((figure-p b)
               (setf h (floor rest growing) box
                     (figure-geometry b s x top width h)))
              ((column-row-p b)
               (setf h
                     (if (grows-p b)
                         (floor rest growing)
                         (block-height b k c width height)))
               (let ((cx x))
                 (loop for col in (column-row-columns b)
                       for w in (row-widths b k c width)
                       do (blocks-geometry (column-blocks col) k c cx top w h)
                         (incf cx (+ w *column-gap*))))))
        ;; A text block's anchor is the rectangle around its lines.
        (unless box
          (loop for r on *obstacles*
                until (eq r before)
                do (setf box (if box (rect-union box (car r)) (car r)))))
        (dolist (co *slide-callouts*)
          (when (and box (eq (callout-target co) b) (null (callout-at co))
                     (null (callout-diagram-box co)))
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
  (let* ((blocks (slide-blocks slide))
         (c (body-code-size blocks k width))
         (top (fit-body-top fit))
         (height (fit-body-height fit))
         (callouts (slide-callouts slide))
         (figure-widths
          (loop for (rblocks rwidth) in (regions blocks k c width)
                nconc
                  (loop for b in rblocks
                        when (figure-p b)
                        collect (cons b rwidth))))
         (*slide-callouts* callouts)
         (*obstacles* nil))
    (dolist (co callouts)
      (multiple-value-bind (w h) (bubble-size co s
                                              (say-max-width co width
                                                             (or
                                                              (cdr
                                                               (assoc
                                                                (callout-target
                                                                 co)
                                                                figure-widths))
                                                              width)))
        (setf (callout-size co) s (callout-width co) w (callout-height co) h
              (callout-fragments co) nil (callout-diagram-box co) nil)))
    ;; An image gives up a strip on its right to its bubbles.
    (dolist (b (region-blocks blocks))
      (when (figure-p b)
        (let ((widths
               (loop for co in callouts
                     when (eq (callout-target co) b)
                     collect (callout-width co))))
          (setf (figure-strip b)
                (and widths (+ (reduce #'max widths) *tail-reach*))))))
    (blocks-geometry blocks k c (fit-left fit) top width height)
    (place-callouts (remove-if-not #'callout-fragments callouts)
                    (list (fit-left fit) top (+ (fit-left fit) width) (+ top height))
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
