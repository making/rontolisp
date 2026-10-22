;;;; diagram.lisp -- the drawings of (flow ...) and (cells ...).
;;;;
;;;; A drawing is a list of shapes in canvas px from its top left corner. Every shape is a
;;;; filled area, never a stroke, so every coordinate is an integer and the box a browser
;;;; reports for a shape is the box the callouts avoid here. Every measure of a diagram
;;;; derives from its label size, which steps down as a table's does.

(in-package :lispresen)

(defparameter *diagram-steps* '(48 44 40 36))
(defparameter *diagram-floor* 40)
(defparameter *dg-line*
  4
  "The thickness of every line of a diagram: borders, dividers, arrows.")
(defparameter *box-radius* 12)

(defun dg-lh (s) (ceiling (* s 14) 10))
(defun dg-pad-x (s) (floor (* s 3) 5))
(defun dg-pad-y (s) (floor s 3))
(defun flow-gap (s)
  "Between the boxes of a row."
  (ceiling (* s 3) 2))
(defun flow-row-gap (s)
  "Between rows, where the arrow turns back."
  (* s 2))
(defun arrow-margin (s)
  "Between an arrow and the box it leaves or enters."
  (floor s 6))
(defun head-length (s) (floor (* s 2) 5))
(defun head-half (s) (floor s 4))
(defun dot-radius (s) (floor s 8))

;;; WIDTH and HEIGHT in px; SHAPES, painted in order, are
;;;   (:rect X Y W H CLASS RX)   a rectangle: CLASS dg-b a border, dg-f the face inside
;;;                              it, dg-e part of an arrow
;;;   (:poly CLASS X1 Y1 ...)    a polygon: an arrowhead, or the slash of nil
;;;   (:dot X Y R)               where a pointer starts
;;;   (:box X Y W H I PAD)       the label of a flow's Ith box, PAD px above its lines
;;;   (:atom X Y W H TEXT)       an atom of cells, as printed
(defstruct drawing width height shapes)

(defun shape-layer (shape)
  (case (car shape)
    (:rect (cond ((equal (sixth shape) "dg-b") 0)
                 ((equal (sixth shape) "dg-f") 1)
                 (t 2)))
    ((:poly :dot) 2)
    (t 3)))

(defun shape-box (shape)
  "The rectangle SHAPE covers, as (LEFT TOP RIGHT BOTTOM)."
  (case (car shape)
    (:poly (let ((xs (loop for (x) on (cddr shape) by #'cddr collect x))
                 (ys (loop for (nil y) on (cddr shape) by #'cddr collect y)))
             (list (reduce #'min xs) (reduce #'min ys) (reduce #'max xs)
                   (reduce #'max ys))))
    (:dot (destructuring-bind (x y r) (cdr shape)
            (list (- x r) (- y r) (+ x r) (+ y r))))
    (t (destructuring-bind (x y w h) (subseq shape 1 5)
         (list x y (+ x w) (+ y h))))))

(defun shift-shape (shape dx dy)
  (if (eq (car shape) :poly)
      (list* :poly (second shape)
             (loop for (x y) on (cddr shape) by #'cddr
                   collect (+ x dx)
                   collect (+ y dy)))
      (list* (first shape) (+ (second shape) dx) (+ (third shape) dy)
             (cdddr shape))))

(defun finish-drawing (shapes left width height)
  "The drawing of SHAPES moved right by -LEFT, painted borders first and labels last."
  (make-drawing :width width
                :height height
                :shapes (stable-sort (mapcar (lambda (shape)
                                               (shift-shape shape (- left) 0))
                                             shapes) #'<
                                     :key #'shape-layer)))

(defun arrow-right (x1 x2 y s)
  "The shapes of an arrow along Y from X1 to its tip at X2."
  (let ((a (head-length s)) (b (head-half s)) (half (floor *dg-line* 2)))
    (list (list :rect x1 (- y half) (max 0 (- x2 a x1)) *dg-line* "dg-e" 0)
          (list :poly "dg-e" (- x2 a) (- y b) x2 y (- x2 a) (+ y b)))))

(defun arrow-down (x y1 y2 s)
  "The shapes of an arrow along X from Y1 down to its tip at Y2."
  (let ((a (head-length s)) (b (head-half s)) (half (floor *dg-line* 2)))
    (list (list :rect (- x half) y1 *dg-line* (max 0 (- y2 a y1)) "dg-e" 0)
          (list :poly "dg-e" (- x b) (- y2 a) x y2 (+ x b) (- y2 a)))))

(defun framed-box (x y w h radius)
  "A border and the face inside it."
  (let ((l *dg-line*))
    (list (list :rect x y w h "dg-b" radius)
          (list :rect (+ x l) (+ y l) (- w (* 2 l)) (- h (* 2 l)) "dg-f"
                (max 0 (- radius l))))))

;;; --- flow ----------------------------------------------------------------------------

(defun greedy-rows (widths gap limit)
  "How many boxes each row takes when boxes WIDTHS px wide, GAP px apart, fill rows at
most LIMIT px wide in turn; a box wider than LIMIT takes a row of its own."
  (let ((rows nil) (count 0) (used 0))
    (dolist (w widths)
      (if (and (> count 0) (> (+ used gap w) limit))
          (progn
            (push count rows)
            (setf count 1 used w))
          (setf used (if (> count 0) (+ used gap w) w) count (1+ count))))
    (nreverse (cons count rows))))

(defun flow-rows (widths gap avail)
  "How many boxes each row takes: as few rows as fit AVAIL px, as even in width as that
allows (the narrowest limit that needs no more rows)."
  (let* ((n (length (greedy-rows widths gap avail)))
         (lo (reduce #'max widths))
         (hi (max lo avail)))
    (loop while (< lo hi)
          do
            (let ((mid (floor (+ lo hi) 2)))
              (if (<= (length (greedy-rows widths gap mid)) n)
                  (setf hi mid)
                  (setf lo (1+ mid)))))
    (greedy-rows widths gap lo)))

(defun flow-text-width (run s avail)
  "The width a flow label's text takes at S px in a flow AVAIL px wide: its own, wrapped
at its phrases when wider than a third of the flow."
  (let ((cap (- (floor (- avail (* 2 (flow-gap s))) 3) (* 2 (dg-pad-x s)))))
    (max (run-min-width run s) (min (run-full-width run s) cap))))

(defun flow-drawing (b s avail)
  "The flow B at label size S in AVAIL px: its boxes left to right, one height, in as few
rows as fit, each row's last box joined to the next row's first by an arrow that turns
back below it."
  (let* ((runs (diagram-runs b))
         (px (dg-pad-x s))
         (lh (dg-lh s))
         (gap (flow-gap s))
         (row-gap (flow-row-gap s))
         (m (arrow-margin s))
         (half (floor *dg-line* 2))
         (texts (mapcar (lambda (run) (flow-text-width run s avail)) runs))
         (lines (mapcar (lambda (run w) (run-lines run s w)) runs texts))
         (h (+ (* (reduce #'max lines) lh) (* 2 (dg-pad-y s))))
         ;; No box is narrower than it is tall.
         (widths (mapcar (lambda (w) (max h (+ w (* 2 px)))) texts))
         (shapes nil)
         (width 0)
         (i 0)
         (y 0)
         (turn nil))
    (flet ((add (list) (setf shapes (revappend list shapes))))
      (dolist (count (flow-rows widths gap avail))
        (let ((x 0))
          (dotimes (j count)
            (let ((w (nth i widths)) (cy (+ y (floor h 2))))
              (cond ((> j 0)
                     (add (arrow-right (+ x m) (- (+ x gap) m) cy s))
                     (incf x gap))
                    (turn
                     ;; Down from the last box of the row above, back along the gap,
                     ;; down into this one.
                     (destructuring-bind (bx by) turn
                       (let ((mid (+ by (floor row-gap 2)))
                             (fx (+ x (floor w 2))))
                         (add
                          (list (list :rect (- bx half) (+ by m) *dg-line*
                                      (- (+ mid half) by m) "dg-e" 0)
                                (list :rect (- (min bx fx) half) (- mid half)
                                      (+ (abs (- bx fx)) *dg-line*) *dg-line*
                                      "dg-e" 0)))
                         (add (arrow-down fx (- mid half) (- y m) s))))))
              (add (framed-box x y w h *box-radius*))
              (add
               (list
                (list :box x y w h i (floor (- h (* (nth i lines) lh)) 2))))
              (incf x w)
              (incf i)))
          (setf width (max width x) turn
                (list (- x (floor (nth (1- i) widths) 2)) (+ y h)))
          (incf y (+ h row-gap))))
      (finish-drawing (nreverse shapes) 0 width (- y row-gap)))))

;;; --- cells ---------------------------------------------------------------------------

(defun atom-label (x)
  "How cells writes the atom X: symbols in lower case unless written with lower case,
integers, ratios, strings and characters. Nil for any other atom, whose printing (a
float's above all) is not the same on every backend."
  (cond ((symbolp x)
         (let ((name (symbol-name x)))
           (concatenate 'string (if (keywordp x) ":" "")
                        (if (string= name (string-upcase name))
                            (string-downcase name)
                            name))))
        ((integerp x) (format nil "~d" x))
        ((rationalp x) (format nil "~d/~d" (numerator x) (denominator x)))
        ((stringp x)
         (with-output-to-string (out)
           (write-char (code-char 34) out)
           (loop for c across x
                 do
                   (when (member (char-code c) '(34 92))
                     (write-char (code-char 92) out)) (write-char c out))
           (write-char (code-char 34) out)))
        ((characterp x)
         (concatenate 'string "#" (string (code-char 92))
                      (cond ((char= x #\Space) "Space")
                            ((char= x #\Newline) "Newline")
                            ((graphic-char-p x) (string x))
                            (t (format nil "U+~4,'0x" (char-code x))))))
        (t nil)))

(defun datum-atoms (x)
  "The labels of the atoms of the datum X but nil, cars before cdrs: the order cells
draws them in."
  (let ((out nil))
    (labels ((walk (x)
               (loop while (consp x)
                     do
                       (if (consp (car x))
                           (walk (car x))
                           (when (car x) (push (atom-label (car x)) out)))
                       (setf x (cdr x)))
               (when x (push (atom-label x) out))))
      (walk x))
    (nreverse out)))

(defun print-datum (x)
  "The datum X as Lisp prints it, with the atoms as cells writes them."
  (with-output-to-string (out)
    (labels ((pr (x)
               (if (not (consp x))
                   (write-string (atom-label x) out)
                   (progn
                     (write-char #\( out)
                     (loop
                       (pr (car x))
                       (setf x (cdr x))
                       (cond ((null x) (return))
                             ((consp x) (write-char #\Space out))
                             (t
                              (write-string " . " out)
                              (pr x)
                              (return))))
                     (write-char #\) out)))))
      (pr x))))

(defun slash (x y w h)
  "The slash of nil across the face X, Y, W x H, bottom left to top right."
  (let ((k (floor (* *dg-line* 3) 2)))
    (list :poly "dg-e" x (+ y h) (+ x k) (+ y h) (+ x w) y (- (+ x w) k) y)))

(defun cells-layout (x s)
  "The shapes of the list X at S px, its first cell's left at 0 and its row at the top, and
the left and right edges and the rows they take. Each cell of the cdr chain stands in one
row, a car's drawing below its cell: an atom as its label, a list as its own row of cells.
Cells move right as far as the drawings below them need."
  (let* ((q (dg-lh s))
         (link s)
         (sep s)
         (pitch (+ q s))
         (half (floor q 2))
         (l *dg-line*)
         (face (- q l (floor l 2)))
         (cars nil)
         (tail nil))
    (loop while (consp x) do (push (car x) cars) (setf x (cdr x)))
    (setf tail x cars (nreverse cars))
    ;; The drawing below each cell, as (SHAPES LEFT RIGHT ROWS) from the cell's left.
    (let* ((subs
            (mapcar (lambda (a)
                      (cond ((null a) nil)
                            ((consp a) (multiple-value-list (cells-layout a s)))
                            (t (let* ((text (atom-label a))
                                      (w (mono-width text s))
                                      (left (- half (floor w 2))))
                                 (list (list (list :atom left 0 w q text)) left
                                       (+ left w) 1))))) cars))
           (xs nil)
           (frontier nil))
      ;; Each cell's left: past the one before it, and its drawing past the drawings
      ;; before it.
      (let ((cx 0))
        (loop for sub in subs
              for first = t then nil
              do (unless first (incf cx (+ (* 2 q) link)))
                (when (and sub frontier)
                  (setf cx (max cx (- (+ frontier sep) (second sub)))))
                (when sub (setf frontier (+ cx (third sub)))) (push cx xs)))
      (setf xs (nreverse xs))
      (let ((shapes nil) (left 0) (right 0) (rows 1))
        (flet ((add (list) (setf shapes (revappend list shapes))))
          (loop for (cx next) on xs
                for sub in subs
                for a in cars
                do
                ;; A border, and a face for the car and one for the cdr either side
                ;; of the divider.
                  (add
                   (list (list :rect cx 0 (* 2 q) q "dg-b" 0)
                         (list :rect (+ cx l) l face (- q (* 2 l)) "dg-f" 0)
                         (list :rect (+ cx q (floor l 2)) l face (- q (* 2 l))
                               "dg-f" 0)))
                  (setf right (max right (+ cx (* 2 q))))
                ;; The car: a pointer down to its drawing, or nil's slash.
                  (if (null a)
                      (add (list (slash (+ cx l) l face (- q (* 2 l)))))
                      (destructuring-bind (sub-shapes sl sr srows) sub
                        (add (list (list :dot (+ cx half) half (dot-radius s))))
                        (add (arrow-down (+ cx half) half pitch s))
                        (add
                         (mapcar (lambda (shape) (shift-shape shape cx pitch))
                                 sub-shapes))
                        (setf left (min left (+ cx sl)) right
                              (max right (+ cx sr)) rows
                              (max rows (1+ srows)))))
                ;; The cdr: a pointer right to the next cell or to a dotted pair's
                ;; atom, or nil's slash.
                  (let ((dx (+ cx q half)))
                    (cond (next
                           (add (list (list :dot dx half (dot-radius s))))
                           (add (arrow-right dx next half s)))
                          (tail
                           (let* ((text (atom-label tail))
                                  (w (mono-width text s))
                                  (tx (+ cx (* 2 q) link (arrow-margin s))))
                             (add (list (list :dot dx half (dot-radius s))))
                             (add
                              (arrow-right dx (- tx (arrow-margin s)) half s))
                             (add (list (list :atom tx 0 w q text)))
                             (setf right (max right (+ tx w)))))
                          (t (add
                              (list
                               (slash (+ cx q (floor l 2)) l face
                                      (- q (* 2 l))))))))))
        (values (nreverse shapes) left right rows)))))

(defun cells-drawing (b s)
  "The cells B at label size S."
  (multiple-value-bind (shapes left right rows)
      (cells-layout (diagram-datum b) s)
    (finish-drawing shapes left (- right left) (- (* rows (+ (dg-lh s) s)) s))))

(defun draw-diagram (b s avail)
  "The diagram B at label size S in AVAIL px; wider than AVAIL when it does not fit."
  (if (eq (diagram-kind b) :flow) (flow-drawing b s avail) (cells-drawing b s)))

(defun diagram-bounds (b s)
  "The narrowest width the diagram B takes at S px, and its width on one row."
  (if (eq (diagram-kind b) :flow)
      (let* ((px (* 2 (dg-pad-x s)))
             (h (+ (dg-lh s) (* 2 (dg-pad-y s))))
             (runs (diagram-runs b)))
        (values (reduce #'max
                        (mapcar
                         (lambda (run) (max h (+ px (run-min-width run s))))
                         runs))
                (+ (* (flow-gap s) (1- (length runs)))
                   (reduce #'+
                           (mapcar
                            (lambda (run) (max h (+ px (run-full-width run s))))
                            runs)))))
      (let ((w (drawing-width (cells-drawing b s)))) (values w w))))
